package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlaySessionTransport
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.PairingStore
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedPcm16ProductPreparerTest {
    @Test
    fun closeableSecretBorrowErasesTransferredBytesAndRejectsReuse() {
        val raw = ByteArray(32) { (it + 1).toByte() }
        val borrow = MainBufferedVerifiedSecretBorrow.takeOwnership(507L, raw)
        borrow.withRawSecret { selected ->
            assertTrue(selected === raw)
            assertEquals(32, selected.size)
        }
        borrow.close()
        assertTrue(borrow.isReturnedAndZeroized())
        assertTrue(raw.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) {
            borrow.withRawSecret { throw AssertionError("returned borrow must not be used") }
        }
        val invalid = ByteArray(31) { 9 }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedVerifiedSecretBorrow.takeOwnership(507L, invalid)
        }
        assertTrue(invalid.all { it == 0.toByte() })
    }

    @Test
    fun defaultEngineHasNoProductPreparerOrLeaseProvider() = fixture { session ->
        val engine = CarPlayMediaEngine(object : MediaSink {})
        assertNull(engine.prepareMainBuffered(session, identity(), request()))
        engine.close()
    }

    @Test
    fun missingLeaseAndWrongSessionGenerationOrPeerNeverOpenOutput() = fixture { session ->
        var opens = 0
        val outputFactory = MainBufferedPcm16ProductOutputFactory {
            opens++
            MainBufferedPcm16ProductOutput(FakeOutput(), 4_096L)
        }
        val absent = preparer(MainBufferedM4FlushedLeaseProvider { _, _ -> null }, outputFactory)
        assertNull(absent.prepare(session, identity(), request()))

        val wrongGeneration = lease(session).apply { current = false }
        assertNull(preparer({ _, _ -> wrongGeneration }, outputFactory)
            .prepare(session, identity(), request()))
        assertEquals(1, wrongGeneration.closes)

        val wrongPeer = lease(session).apply {
            peer = InetAddress.getByName("127.0.0.2")
        }
        assertNull(preparer({ _, _ -> wrongPeer }, outputFactory)
            .prepare(session, identity(), request()))
        assertEquals(1, wrongPeer.closes)
        assertEquals(0, opens)
    }

    @Test
    fun unknownByteOrderOrUnplayableControlShapeDeniesBeforeOutput() = fixture { session ->
        var opens = 0
        val outputFactory = MainBufferedPcm16ProductOutputFactory {
            opens++
            MainBufferedPcm16ProductOutput(FakeOutput(), 4_096L)
        }
        val unknownOrder = lease(session).apply { sourceByteOrder = null }
        assertNull(preparer({ _, _ -> unknownOrder }, outputFactory)
            .prepare(session, identity(), request()))
        assertEquals(1, unknownOrder.closes)
        val apapOnly = lease(session)
        assertNull(preparer({ _, _ -> apapOnly }, outputFactory).prepare(
            session, identity(), request() + mapOf(
                "streamConnections" to mapOf("streamConnectionTypeAPAP" to emptyMap<String, Any>()),
            ),
        ))
        assertEquals(1, apapOnly.closes)
        assertEquals(0, opens)
    }

    @Test
    fun wrongFormatOrMissingRawSecretRetiresNativeOutputAndLease() = fixture { session ->
        val badFormatLease = lease(session)
        val badFormatOutput = FakeOutput()
        val badFormat = preparer({ _, _ -> badFormatLease }, outputFactory(badFormatOutput))
        assertThrows(IllegalArgumentException::class.java) {
            badFormat.prepare(session, identity(), request() + mapOf(
                "audioFormat" to MainBufferedAudioFormatBits.ALAC_48000_16_STEREO,
            ))
        }
        assertEquals(0, badFormatOutput.closes)
        assertEquals(0, badFormatLease.closes)

        val noSecretLease = lease(session).apply { rawSecretAvailable = false }
        val noSecretOutput = FakeOutput()
        val noSecret = preparer({ _, _ -> noSecretLease }, outputFactory(noSecretOutput))
        assertThrows(IllegalStateException::class.java) {
            noSecret.prepare(session, identity(), request())
        }
        assertEquals(1, noSecretOutput.closes)
        assertEquals(1, noSecretLease.closes)

        val wrongBorrowGeneration = lease(session).apply { borrowedGeneration = generation + 1 }
        val wrongBorrowOutput = FakeOutput()
        assertThrows(IllegalArgumentException::class.java) {
            preparer({ _, _ -> wrongBorrowGeneration }, outputFactory(wrongBorrowOutput))
                .prepare(session, identity(), request())
        }
        assertTrue(checkNotNull(wrongBorrowGeneration.lastBorrow).isReturnedAndZeroized())
        assertEquals(1, wrongBorrowOutput.closes)
        assertEquals(1, wrongBorrowGeneration.closes)
    }

    @Test
    fun firstConstructionCloseFailureRetainsExactOwnerForLaterRetry() = fixture { session ->
        val lease = lease(session).apply { rawSecretAvailable = false }
        val output = FakeOutput(closeFailures = 2)
        val preparer = preparer({ _, _ -> lease }, outputFactory(output))
        assertThrows(IllegalStateException::class.java) {
            preparer.prepare(session, identity(), request())
        }
        assertEquals(2, output.closeAttempts)
        assertEquals(0, output.closes)
        assertEquals(1, lease.closes)
        assertNull(preparer.retireFailedPreparations(session))
        assertEquals(3, output.closeAttempts)
        assertEquals(1, output.closes)
    }

    @Test
    fun rejectedSecondEngineTransactionCloseFailureKeepsFirstAndRetriesSecond() =
        fixture { session ->
            val firstLease = lease(session)
            val secondLease = lease(session)
            val leases = ArrayDeque(listOf(firstLease, secondLease))
            val firstOutput = FakeOutput()
            val secondOutput = FakeOutput(closeFailures = 1)
            val outputs = ArrayDeque(listOf(firstOutput, secondOutput))
            val product = preparer(
                MainBufferedM4FlushedLeaseProvider { _, _ -> leases.removeFirst() },
                MainBufferedPcm16ProductOutputFactory {
                    MainBufferedPcm16ProductOutput(outputs.removeFirst(), 4_096L)
                },
            )
            val engine = CarPlayMediaEngine(object : MediaSink {}, product)
            try {
                val first = checkNotNull(engine.prepareMainBuffered(session, identity(), request()))
                    .transaction
                assertThrows(IOException::class.java) {
                    engine.prepareMainBuffered(session, identity(), request())
                }
                assertEquals(MainBufferedV27SetupState.PREPARED, first.state())
                assertEquals(0, firstOutput.closes)
                assertEquals(0, firstLease.closes)
                assertEquals(1, secondOutput.closeAttempts)
                engine.onTeardown(session, identity())
                assertEquals(MainBufferedV27SetupState.CLOSED, first.state())
                assertEquals(1, firstOutput.closes)
                assertEquals(1, secondOutput.closes)
                assertEquals(1, firstLease.closes)
                assertEquals(1, secondLease.closes)
            } finally { engine.close() }
        }

    @Test
    fun responseRemainsUnadmittedUntilFlushAndRollbackRetiresBothPortsAndLease() =
        fixture { session ->
            val lease = lease(session)
            val output = FakeOutput()
            val transaction = checkNotNull(preparer({ _, _ -> lease }, outputFactory(output))
                .prepare(session, identity(), request()))
            val response = transaction.responseBody["streams"] as List<*>
            val entry = response.single() as Map<*, *>
            val connections = entry["streamConnections"] as Map<*, *>
            assertEquals(setOf("streamConnectionTypeAPAP", "streamConnectionTypeMediaDataControl"),
                connections.keys)
            assertEquals(MainBufferedV27SetupState.PREPARED, transaction.state())
            assertTrue(checkNotNull(lease.lastBorrow).isReturnedAndZeroized())
            assertEquals(MainBufferedTcpIngressState.PREPARED, transaction.tcpSnapshot().state)
            assertFalse(transaction.tcpSnapshot().acceptWorkerAlive)
            transaction.rollback()
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
            assertEquals(1, output.closes)
            assertEquals(1, lease.closes)
        }

    @Test
    fun failedResponseFlushCannotAdmitEitherPortAndRetiresExactOutput() = fixture { session ->
        val lease = lease(session)
        val output = FakeOutput()
        val transaction = checkNotNull(preparer({ _, _ -> lease }, outputFactory(output))
            .prepare(session, identity(), request()))
        assertThrows(IOException::class.java) {
            transaction.flushResponseAndCommit { throw IOException("incomplete RTSP write") }
        }
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        assertEquals(1, output.closes)
        assertEquals(1, lease.closes)
    }

    @Test
    fun injectedEngineStopsExactPendingProductAndRejectsAnotherPrepare() = fixtureWithEngine {
            session, engine, lease, output ->
        val transaction = checkNotNull(engine.prepareMainBuffered(session, identity(), request()))
            .transaction
        assertEquals(MainBufferedV27SetupState.PREPARED, transaction.state())
        engine.onSessionStopping(session)
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(1, output.closes)
        assertEquals(1, lease.closes)
        assertNull(engine.prepareMainBuffered(session, identity(), request()))
        engine.close()
    }

    @Test
    fun retiredLeaseBeforeResponseCommitFailsClosedOnBothPorts() = fixture { session ->
        val lease = lease(session)
        val output = FakeOutput()
        val transaction = checkNotNull(preparer({ _, _ -> lease }, outputFactory(output))
            .prepare(session, identity(), request()))
        lease.current = false // Analogous to STOPPING or a replacement M1 revocation.
        assertThrows(IllegalStateException::class.java) {
            transaction.commitAfterResponseFlushed()
        }
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        assertEquals(1, output.closes)
        assertEquals(1, lease.closes)
    }

    private fun preparer(
        provider: MainBufferedM4FlushedLeaseProvider,
        factory: MainBufferedPcm16ProductOutputFactory,
    ) = MainBufferedPcm16ProductPreparer(provider, factory, samplesPerFrame = 2L)

    private fun outputFactory(output: FakeOutput) =
        MainBufferedPcm16ProductOutputFactory { MainBufferedPcm16ProductOutput(output, 4_096L) }

    private fun identity() = AirPlayStreamIdentity(103, "7", null)

    private fun request(): Map<String, Any?> = linkedMapOf(
        "type" to 103L,
        "streamConnectionID" to 7L,
        "supportsDynamicStreamID" to true,
        "ct" to "lpcm",
        "spf" to 2L,
        "audioFormat" to MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
        "audioFormatIndex" to 15L,
        "isMedia" to true,
        "audioType" to "media",
        "packetFormatAPAP" to true,
        "shk" to ByteArray(32) { (it + 1).toByte() },
        "streamConnections" to mapOf(
            "streamConnectionTypeAPAP" to emptyMap<String, Any>(),
            "streamConnectionTypeMediaDataControl" to mapOf(
                "streamConnectionKeyEncryptionSeed" to 17L,
            ),
        ),
    )

    private fun lease(session: AirPlaySession) = FakeLease(session)

    private class FakeLease(override val session: AirPlaySession) : MainBufferedM4FlushedSessionLease {
        override val generation = 507L
        override val bindAddress: InetAddress = checkNotNull(session.localAddress)
        override var expectedPeer: InetAddress = checkNotNull(session.remoteAddress)
        override val allocatedStreamId = 23L
        override var sourceByteOrder: MainBufferedPcm16ByteOrder? =
            MainBufferedPcm16ByteOrder.LITTLE_ENDIAN
        var current = true
        var rawSecretAvailable = true
        var borrowedGeneration = generation
        var closes = 0
        override fun isCurrent(requestedGeneration: Long): Boolean =
            current && requestedGeneration == generation
        var lastBorrow: MainBufferedVerifiedSecretBorrow? = null
        override fun borrowVerifiedSharedSecret(requestedGeneration: Long):
            MainBufferedVerifiedSecretBorrow? =
            if (isCurrent(requestedGeneration) && rawSecretAvailable) {
                MainBufferedVerifiedSecretBorrow.takeOwnership(borrowedGeneration,
                    ByteArray(32) { 7 })
                    .also { lastBorrow = it }
            } else null
        override fun close() { closes++; current = false }
        var peer: InetAddress
            get() = expectedPeer
            set(value) { expectedPeer = value }
    }

    private class FakeOutput(var closeFailures: Int = 0) : MainBufferedPcm16Output {
        override var isReady = true
        override var playbackEpoch = 0L
        override val outputInstance = Any()
        override var firstQueuedMediaTimeValue: Long? = null
        var closes = 0
        var closeAttempts = 0
        override fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean {
            firstQueuedMediaTimeValue = mediaTimeValue
            return true
        }
        override fun pausePlayback() { playbackEpoch++; firstQueuedMediaTimeValue = null }
        override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean = false
        override fun flush() { playbackEpoch++; firstQueuedMediaTimeValue = null }
        override fun close() {
            if (!isReady) return
            closeAttempts++
            if (closeFailures > 0) {
                closeFailures--
                throw IOException("native output retirement must be retried")
            }
            isReady = false
            closes++
        }
    }

    private fun fixture(action: (AirPlaySession) -> Unit) {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { client ->
                server.accept().use { accepted ->
                    val session = newSession(accepted, object : AirPlayMediaHandler {})
                    try { action(session) } finally { session.close() }
                }
            }
        }
    }

    private fun fixtureWithEngine(
        action: (AirPlaySession, CarPlayMediaEngine, FakeLease, FakeOutput) -> Unit,
    ) {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            Socket(loopback, server.localPort).use { client ->
                server.accept().use { accepted ->
                    lateinit var lease: FakeLease
                    val output = FakeOutput()
                    val engine = CarPlayMediaEngine(object : MediaSink {}, preparer(
                        MainBufferedM4FlushedLeaseProvider { _, _ -> lease }, outputFactory(output),
                    ))
                    val session = newSession(accepted, engine)
                    lease = lease(session)
                    try { action(session, engine, lease, output) } finally {
                        session.close()
                        engine.close()
                    }
                }
            }
        }
    }

    private fun newSession(socket: Socket, media: AirPlayMediaHandler) = AirPlaySession(
        socket = socket,
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(800, 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onSessionEnded(session: AirPlaySession) = Unit
        },
        media = media,
        sessionTransport = AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK,
    )
}
