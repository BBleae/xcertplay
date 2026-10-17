package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlaySessionTransport
import com.shilapi.xcertplay.airplay.PairingStore
import java.io.Closeable
import java.math.BigInteger
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedV27SetupTransactionTest {
    @Test
    fun closeCancelsBlockedApapBindWithoutPublishingAndRejectsSecondPrepare() {
        val enteredBind = CountDownLatch(1)
        val releaseBind = CountDownLatch(1)
        val ticketCopies = ArrayList<ByteArray>()
        lateinit var owner: MainBufferedV27SetupSession
        owner = owner(
            MainBufferedV27PortMode.DATA_PORT,
            closeJoinMillis = 100L,
            tcpIngressHooks = MainBufferedTcpIngressHooks(beforeBind = {
                assertFalse(Thread.holdsLock(owner.lock))
                enteredBind.countDown()
                check(releaseBind.await(3L, TimeUnit.SECONDS))
            }),
        ) { _, _ -> throw AssertionError("cancelled APAP may not deliver") }
        val failure = AtomicReference<Throwable?>()
        val ticket = MainBufferedV27RequestTicket.freeze(request(), ticketCopies::add)
        val worker = Thread({
            ticket.use {
                try {
                    owner.prepare(it)
                    failure.set(AssertionError("cancelled APAP prepared a response"))
                } catch (problem: Throwable) {
                    failure.set(problem)
                }
            }
        }, "blocked-apap-prepare")
        try {
            worker.start()
            assertTrue(enteredBind.await(2L, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { owner.prepare(request()) }
            val start = System.nanoTime()
            val pending = assertThrows(IOException::class.java) { owner.close() }
            assertTrue(pending.message!!.contains("pending cleanup"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 750L)
            assertThrows(IllegalStateException::class.java) { owner.prepare(request()) }
        } finally {
            releaseBind.countDown()
            worker.join(3_000L)
            owner.close()
        }
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(ticketCopies.isNotEmpty())
        assertTrue(ticketCopies.all { copy -> copy.all { it == 0.toByte() } })
        assertFalse(Thread.getAllStackTraces().keys.any {
            it.isAlive && it.name == "main-buffered-tcp-g${owner.generation}"
        })
    }

    @Test
    fun closeCancelsBlockedMctlBindAndRetainsFirstRollbackFailureForRetry() {
        val enteredMctl = CountDownLatch(1)
        val releaseMctl = CountDownLatch(1)
        lateinit var owner: MainBufferedV27SetupSession
        val preparer = RecordingMctlPreparer(
            failFirstRetirement = true,
            beforePrepare = {
                assertFalse(Thread.holdsLock(owner.lock))
                enteredMctl.countDown()
                check(releaseMctl.await(3L, TimeUnit.SECONDS))
            },
            beforeRollback = { assertFalse(Thread.holdsLock(owner.lock)) },
        )
        val lifecycleCloses = AtomicInteger()
        owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            closeJoinMillis = 100L,
            mctlPortPreparer = preparer,
            lifecycleRetirement = Closeable { lifecycleCloses.incrementAndGet() },
        ) { _, _ -> throw AssertionError("cancelled mctl may not deliver") }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                owner.prepare(requestWithMctl(17L))
                failure.set(AssertionError("cancelled mctl prepared a response"))
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }, "blocked-mctl-prepare")
        try {
            worker.start()
            assertTrue(enteredMctl.await(2L, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { owner.prepare(request(modern = true)) }
            assertThrows(IOException::class.java) { owner.close() }
            assertEquals("M4 preparation fence must remain until binder cleanup", 0,
                lifecycleCloses.get())
        } finally {
            releaseMctl.countDown()
            worker.join(3_000L)
        }
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(1, lifecycleCloses.get())
        assertTrue(checkNotNull(failure.get()).suppressed.any {
            it is IOException && it.message == "mctl close must be retried"
        })
        val port = preparer.ports.single()
        assertEquals(1, port.rollbackCalls)
        assertFalse(port.serverClosed)
        owner.close()
        assertEquals(2, port.rollbackCalls)
        assertEquals(0, port.closeCalls)
        assertTrue(port.serverClosed)
        assertEquals(1, lifecycleCloses.get())
        assertFalse(Thread.getAllStackTraces().keys.any {
            it.isAlive && it.name == "main-buffered-tcp-g${owner.generation}"
        })
    }

    @Test
    fun revokedM4LeaseDuringApapBindCannotPublishResponseOrKeepKey() =
        withDetachedAirPlaySession { airPlaySession ->
            val enteredBind = CountDownLatch(1)
            val releaseBind = CountDownLatch(1)
            val live = AtomicBoolean(true)
            val marker = Any()
            val expectedGeneration = 507L
            lateinit var owner: MainBufferedV27SetupSession
            val lease = object : MainBufferedM4FlushedSessionLease {
                override val session = airPlaySession
                override val generation = expectedGeneration
                override val bindAddress = LOOPBACK
                override val expectedPeer = LOOPBACK
                override val allocatedStreamId = 77L
                override val sourceByteOrder: MainBufferedPcm16ByteOrder? = null
                override fun isCurrent(requestedGeneration: Long): Boolean {
                    assertFalse("M4 liveness must run outside SETUP owner.lock",
                        Thread.holdsLock(owner.lock))
                    return live.get() && requestedGeneration == expectedGeneration
                }
                override fun borrowVerifiedSharedSecret(requestedGeneration: Long):
                    MainBufferedVerifiedSecretBorrow? = null
                override fun close() { live.set(false) }
            }
            owner = MainBufferedV27SetupSession.create(
                generation = expectedGeneration,
                bindAddress = LOOPBACK,
                expectedPeer = LOOPBACK,
                allocatedStreamId = 77L,
                acceptedFormat = MainBufferedV27AcceptedFormat(
                    FOURCC_ALAC, 352L, MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
                ),
                audioBufferSize = 4_096L,
                portMode = MainBufferedV27PortMode.DATA_PORT,
                packetConsumer = MainBufferedIngressPacketConsumer { _, _ ->
                    throw AssertionError("revoked lease may not deliver")
                },
                tcpIngressHooks = MainBufferedTcpIngressHooks(beforeBind = {
                    assertFalse(Thread.holdsLock(owner.lock))
                    enteredBind.countDown()
                    check(releaseBind.await(3L, TimeUnit.SECONDS))
                }),
                lifecycleRetirement = lease,
                claimedLease = lease,
                claimedRequestMarker = marker,
            )
            val copied = ArrayList<ByteArray>()
            val ticket = MainBufferedV27RequestTicket.freeze(request(), copied::add)
            assertTrue(ticket.bindClaim(marker))
            val failure = AtomicReference<Throwable?>()
            val worker = Thread({
                ticket.use {
                    try {
                        owner.prepare(it)
                        failure.set(AssertionError("revoked lease prepared a response"))
                    } catch (problem: Throwable) { failure.set(problem) }
                }
            }, "revoked-m4-apap-prepare")
            try {
                worker.start()
                assertTrue(enteredBind.await(2L, TimeUnit.SECONDS))
                live.set(false) // The same boundary as STOPPING's M4 child revocation.
            } finally {
                releaseBind.countDown()
                worker.join(3_000L)
                owner.close()
            }
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IllegalStateException)
            assertTrue(copied.isNotEmpty())
            assertTrue(copied.all { copy -> copy.all { it == 0.toByte() } })
            assertFalse(Thread.getAllStackTraces().keys.any {
                it.isAlive && it.name == "main-buffered-tcp-g$expectedGeneration"
            })
        }

    @Test
    fun exactIngressIdentityCanBeInjectedBeforePcmAndMctlOwnersArePrepared() {
        val identity = MainBufferedIngressIdentity.create(901L)
        val owner = MainBufferedV27SetupSession.create(
            generation = 901L,
            bindAddress = LOOPBACK,
            expectedPeer = LOOPBACK,
            allocatedStreamId = 77L,
            acceptedFormat = MainBufferedV27AcceptedFormat(
                FOURCC_ALAC, 352L, MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
            ),
            audioBufferSize = 4096L,
            portMode = MainBufferedV27PortMode.DATA_PORT,
            packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> },
            ingressIdentity = identity,
        )
        assertTrue(owner.ingressIdentity === identity)
        owner.close()
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedV27SetupSession.create(
                generation = 902L,
                bindAddress = LOOPBACK,
                expectedPeer = LOOPBACK,
                allocatedStreamId = 77L,
                acceptedFormat = MainBufferedV27AcceptedFormat(
                    FOURCC_ALAC, 352L, MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
                ),
                audioBufferSize = 4096L,
                portMode = MainBufferedV27PortMode.DATA_PORT,
                packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> },
                ingressIdentity = identity,
            )
        }
    }

    @Test
    fun topLevelDataPortCommitsOnlyAfterResponseFlushThenAuthenticatesRealSocket() {
        exerciseAuthorizedSocket(MainBufferedV27PortMode.DATA_PORT)
    }

    @Test
    fun streamConnectionsApapPortCommitsOnlyAfterResponseFlushThenAuthenticatesRealSocket() {
        exerciseAuthorizedSocket(MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP)
    }

    private fun exerciseAuthorizedSocket(mode: MainBufferedV27PortMode) {
        val key = ByteArray(32) { (it + 1).toByte() }
        val original = key.copyOf()
        val delivered = CountDownLatch(1)
        val sequences = AtomicInteger(-1)
        val owner = owner(mode) { _, packet ->
            sequences.set(packet.header.sequence)
            delivered.countDown()
        }
        val transaction = owner.prepare(
            request(key, modern = mode == MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP),
        )
        key.fill(0x5a)
        val response = transaction.responseBody
        assertEquals(setOf("streams"), response.keys)
        val stream = (response["streams"] as List<*>).single() as Map<*, *>
        assertEquals(103L, stream["type"])
        assertEquals(77L, stream["streamID"])
        assertEquals(UINT64_HIGH, stream["streamConnectionID"])
        assertEquals(4096L, stream["audioBufferSize"])
        val port = when (mode) {
            MainBufferedV27PortMode.DATA_PORT -> {
                assertFalse(stream.containsKey("streamConnections"))
                (stream["dataPort"] as Long).toInt()
            }
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP -> {
                assertFalse(stream.containsKey("dataPort"))
                val connections = stream["streamConnections"] as Map<*, *>
                assertEquals(setOf("streamConnectionTypeAPAP"), connections.keys)
                val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
                assertEquals(setOf("streamConnectionKeyPort"), apap.keys)
                (apap["streamConnectionKeyPort"] as Long).toInt()
            }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (response as MutableMap<String, Any?>)["other"] = true
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (stream as MutableMap<String, Any?>)["dataPort"] = 1L
        }
        assertTrue(port in 1..65535)

        val writer = EncryptedApapWriter(original, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        try {
            Socket(LOOPBACK, port).use { sender ->
                sender.getOutputStream().write(frame(writer, 29))
                sender.getOutputStream().flush()
                assertEquals(MainBufferedV27SetupState.PREPARED, transaction.state())
                assertEquals(MainBufferedTcpIngressState.PREPARED, transaction.tcpSnapshot().state)
                assertEquals(0, transaction.tcpSnapshot().candidateCount)
                assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))
                assertThrows(IllegalStateException::class.java) {
                    transaction.awaitAuthenticatedSession(0)
                }

                transaction.flushResponseAndCommit { flushed ->
                    assertTrue(flushed === response)
                }
                assertEquals(MainBufferedV27SetupState.COMMITTED, transaction.state())
                val admitted = transaction.awaitAuthenticatedSession(TIMEOUT_MILLIS)
                assertNotNull(admitted)
                assertTrue(delivered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                sender.shutdownOutput()
                assertTrue(admitted!!.awaitClosed(TIMEOUT_MILLIS))
                assertEquals(29, sequences.get())
                assertEquals(
                    MainBufferedIngressTerminationReason.CLEAN_EOF,
                    admitted.snapshot().terminationReason,
                )
            }
        } finally {
            owner.close()
            writer.close()
        }
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertTrue(transaction.tcpSnapshot().pendingKeyZeroized)
        assertTrue(transaction.tcpSnapshot().serverClosed)
        assertArrayEquals(ByteArray(32) { 0x5a }, key)
    }

    @Test
    fun failedResponseFlushRollsBackPortAndNeverAccepts() {
        val owner = owner(MainBufferedV27PortMode.DATA_PORT) { _, _ ->
            throw AssertionError("rolled back SETUP cannot deliver")
        }
        val transaction = owner.prepare(request())
        val port = dataPort(transaction)
        val flushFailure = IllegalStateException("RTSP response write failed")
        assertThrows(IllegalStateException::class.java) {
            transaction.flushResponseAndCommit { throw flushFailure }
        }

        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        assertTrue(transaction.tcpSnapshot().pendingKeyZeroized)
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, port).close() }
        assertThrows(IllegalStateException::class.java) { transaction.commitAfterResponseFlushed() }
        assertThrows(IllegalStateException::class.java) { owner.prepare(request()) }
        owner.close()
    }

    @Test
    fun malformedOrContradictorySetupNeverReservesOrCommits() {
        val owner = owner(MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP) { _, _ ->
            throw AssertionError("invalid SETUP cannot deliver")
        }
        try {
            val valid = request(modern = true)
            listOf(
                valid - "shk",
                valid + ("shk" to "not raw Data"),
                valid + ("shk" to ByteArray(31)),
                valid + ("streamID" to 77L),
                valid + ("type" to 100L),
                valid + ("supportsDynamicStreamID" to false),
                valid + ("packetFormatAPAP" to false),
                valid + ("isMedia" to false),
                valid + ("audioType" to "default"),
                valid + ("ct" to "aac "),
                valid + ("spf" to 1024L),
                valid + ("audioFormat" to MainBufferedAudioFormatBits.AAC_LC_44100_STEREO) +
                    ("audioFormatIndex" to 22L),
                valid + ("audioFormatIndex" to 17L),
                valid - "streamConnections",
                valid + ("streamConnections" to emptyMap<String, Any>()),
                valid + ("streamConnections" to mapOf("streamConnectionTypeAPAP" to true)),
                valid + ("streamConnections" to mapOf(
                    "streamConnectionTypeAPAP" to emptyMap<String, Any>(),
                    "streamConnectionTypeMediaDataControl" to emptyMap<String, Any>(),
                )),
                valid + ("streamConnections" to mapOf(
                    "streamConnectionTypeAPAP" to emptyMap<String, Any>(),
                    "unknownFutureConnection" to emptyMap<String, Any>(),
                )),
            ).forEach { malformed ->
                assertThrows(IllegalArgumentException::class.java) { owner.prepare(malformed) }
            }
            val transaction = owner.prepare(valid)
            assertEquals(MainBufferedTcpIngressState.PREPARED, transaction.tcpSnapshot().state)
        } finally {
            owner.close()
        }
    }

    @Test
    fun incorrectApapKeyCannotAuthorizeCommittedSetup() {
        val owner = owner(MainBufferedV27PortMode.DATA_PORT) { _, _ ->
            throw AssertionError("wrong key cannot deliver")
        }
        val transaction = owner.prepare(request(ByteArray(32) { 0x17 }))
        val writer = EncryptedApapWriter(
            ByteArray(32) { 0x18 },
            maxEncryptedBytes = MAX_FRAME_BYTES - 4,
        )
        try {
            transaction.commitAfterResponseFlushed()
            Socket(LOOPBACK, dataPort(transaction)).use { sender ->
                sender.getOutputStream().write(frame(writer, 7))
                sender.getOutputStream().flush()
                // Authentication failure now terminates the *paired* one-shot SETUP, not just
                // the APAP listener. CLOSING is a transient retirement state, never the final
                // authorization result.
                assertTrue(waitUntil { transaction.state() == MainBufferedV27SetupState.CLOSED })
                assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
                assertEquals(
                    MainBufferedIngressTerminationReason.AUTHENTICATION_FAILURE,
                    transaction.tcpSnapshot().ingress?.terminationReason,
                )
            }
        } finally {
            owner.close()
            writer.close()
        }
    }

    @Test
    fun modernRequestMetadataDoesNotReplaceShkOrEscapeIntoResponse() {
        val owner = owner(MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP) { _, _ -> }
        try {
            val transaction = owner.prepare(request(modern = true))
            val responseText = transaction.responseBody.toString()
            assertFalse(responseText.contains("streamConnectionKeyEncryptionSeed"))
            assertFalse(responseText.contains("streamConnectionKeyUseStreamEncryptionKey"))
            assertFalse(responseText.contains("streamConnectionTypeMediaDataControl"))
            assertFalse(responseText.contains("shk"))
        } finally {
            owner.close()
        }

        val legacy = owner(MainBufferedV27PortMode.DATA_PORT) { _, _ -> }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                legacy.prepare(request(modern = true))
            }
        } finally {
            legacy.close()
        }
    }

    @Test
    fun modernRequestAcceptsEmptyApapInnerDictionary() {
        val owner = owner(MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP) { _, _ -> }
        try {
            val request = request(modern = true) +
                ("streamConnections" to mapOf("streamConnectionTypeAPAP" to emptyMap<String, Any?>()))
            val transaction = owner.prepare(request)
            val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
            val connections = stream["streamConnections"] as Map<*, *>
            val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
            assertEquals(setOf("streamConnectionKeyPort"), apap.keys)
        } finally {
            owner.close()
        }
    }

    @Test
    fun mediaDataControlRequiresAnExactIndependentPreparerBeforeAnyResponse() {
        val unsupported = owner(MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP) { _, _ -> }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                unsupported.prepare(requestWithMctl(1L))
            }
            // Rejection happens before an APAP port is reserved; APAP-only remains valid.
            assertNotNull(unsupported.prepare(request(modern = true)))
        } finally {
            unsupported.close()
        }

        val preparer = RecordingMctlPreparer()
        val owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> }
        try {
            listOf(
                requestWithMctl(null),
                requestWithMctl("18446744073709551615"),
                requestWithMctl(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
                requestWithMctl(BigInteger.ONE.shiftLeft(64)),
                requestWithMctl(-1),
                request(modern = true) + ("streamConnections" to mapOf(
                    "streamConnectionTypeAPAP" to emptyMap<String, Any?>(),
                    "streamConnectionTypeMediaDataControl" to emptyMap<String, Any?>(),
                )),
            ).forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { owner.prepare(invalid) }
            }
            assertTrue(preparer.requests.isEmpty())
            assertNotNull(owner.prepare(requestWithMctl(7L)))
        } finally {
            owner.close()
        }
    }

    @Test
    fun dualPortResponsePreservesFullUInt64SeedsAndOpensTogetherAfterFlush() {
        val seeds = listOf(BigInteger.ONE.shiftLeft(63), UINT64_MAX)
        seeds.forEach { seed ->
            val key = ByteArray(32) { (it + 1).toByte() }
            val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
            val delivered = CountDownLatch(1)
            val preparer = RecordingMctlPreparer()
            val owner = owner(
                MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
                mctlPortPreparer = preparer,
            ) { _, _ -> delivered.countDown() }
            try {
                val transaction = owner.prepare(requestWithMctl(seed, key))
                val mctl = preparer.ports.single()
                val preparedRequest = preparer.requests.single()
                assertEquals(seed.toLong(), preparedRequest.encryptionSeedBits)
                assertEquals(UINT64_HIGH.toLong(), preparedRequest.streamConnectionIdBits)
                assertEquals(owner.generation, preparedRequest.generation)
                assertEquals(LOOPBACK, preparedRequest.expectedPeer)
                val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
                val connections = stream["streamConnections"] as Map<*, *>
                assertEquals(
                    setOf("streamConnectionTypeAPAP", "streamConnectionTypeMediaDataControl"),
                    connections.keys,
                )
                val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
                val control = connections["streamConnectionTypeMediaDataControl"] as Map<*, *>
                assertEquals(setOf("streamConnectionKeyPort"), apap.keys)
                assertEquals(setOf("streamConnectionKeyPort"), control.keys)
                assertEquals(mctl.localAddress.port.toLong(), control["streamConnectionKeyPort"])
                assertFalse(transaction.responseBody.toString().contains("streamConnectionKeyEncryptionSeed"))
                val apapPort = (apap["streamConnectionKeyPort"] as Long).toInt()
                assertTrue(apapPort != mctl.localAddress.port)

                Socket(LOOPBACK, apapPort).use { sender ->
                    sender.getOutputStream().write(frame(writer, 36))
                    sender.getOutputStream().flush()
                    assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))
                    assertEquals(0, transaction.tcpSnapshot().candidateCount)
                    assertEquals(0, mctl.commitCalls)
                    transaction.flushResponseAndCommit { body ->
                        assertTrue(body === transaction.responseBody)
                        assertEquals(MainBufferedV27SetupState.FLUSHING, transaction.state())
                        assertEquals(MainBufferedTcpIngressState.PREPARED, transaction.tcpSnapshot().state)
                        assertEquals(0, mctl.commitCalls)
                    }
                    assertEquals(MainBufferedV27SetupState.COMMITTED, transaction.state())
                    assertEquals(1, mctl.commitCalls)
                    assertEquals(1, mctl.activateCalls)
                    assertTrue(checkNotNull(mctl.gate).awaitOpen())
                    assertNotNull(transaction.awaitAuthenticatedSession(TIMEOUT_MILLIS))
                    assertTrue(delivered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                    sender.shutdownOutput()
                }
            } finally {
                owner.close()
                writer.close()
            }
            assertTrue(preparer.ports.single().serverClosed)
        }
    }

    @Test
    fun workerFailureAfterReceiptBeforeFinalCommitCannotPublishCommittedState() {
        val preparer = RecordingMctlPreparer(failAfterReceipt = true)
        val delivered = AtomicInteger()
        val owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> delivered.incrementAndGet() }
        val writer = EncryptedApapWriter(ByteArray(32) { (it + 1).toByte() },
            maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        try {
            val transaction = owner.prepare(requestWithMctl(17L))
            val response = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
            val connections = response["streamConnections"] as Map<*, *>
            val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
            val port = (apap["streamConnectionKeyPort"] as Long).toInt()
            Socket(LOOPBACK, port).use { sender ->
                sender.getOutputStream().write(frame(writer, 38))
                sender.getOutputStream().flush()
                assertThrows(IllegalStateException::class.java) {
                    transaction.flushResponseAndCommit { }
                }
            }
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertTrue(transaction.tcpSnapshot().serverClosed)
            assertEquals(0, transaction.tcpSnapshot().candidateCount)
            assertEquals(0, delivered.get())
            assertTrue(preparer.ports.single().serverClosed)
        } finally {
            owner.close()
            writer.close()
        }
    }

    @Test
    fun bothWorkersReadyButFinalReleaseBlockedCannotDeliverQueuedApap() {
        val enteredFinalCheck = CountDownLatch(1)
        val releaseFinalCheck = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val preparer = RecordingMctlPreparer(beforeFinalCommit = {
            enteredFinalCheck.countDown()
            check(releaseFinalCheck.await(5, TimeUnit.SECONDS))
        })
        val owner = owner(MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer) { _, _ -> delivered.countDown() }
        val transaction = owner.prepare(requestWithMctl(17L))
        val response = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
        val connections = response["streamConnections"] as Map<*, *>
        val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
        val port = (apap["streamConnectionKeyPort"] as Long).toInt()
        val writer = EncryptedApapWriter(ByteArray(32) { (it + 1).toByte() },
            maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val failure = AtomicReference<Throwable?>()
        try {
            Socket(LOOPBACK, port).use { sender ->
                sender.getOutputStream().write(frame(writer, 37))
                sender.getOutputStream().flush()
                val committing = Thread {
                    try { transaction.commitAfterResponseFlushed() }
                    catch (problem: Throwable) { failure.set(problem) }
                }
                committing.start()
                assertTrue(enteredFinalCheck.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertEquals(0, transaction.tcpSnapshot().candidateCount)
                assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))
                releaseFinalCheck.countDown()
                committing.join(TIMEOUT_MILLIS)
                assertFalse(committing.isAlive)
                assertNull(failure.get())
                assertEquals(MainBufferedV27SetupState.COMMITTED, transaction.state())
                assertNotNull(transaction.awaitAuthenticatedSession(TIMEOUT_MILLIS))
                assertTrue(delivered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                sender.shutdownOutput()
            }
        } finally {
            releaseFinalCheck.countDown()
            owner.close()
            writer.close()
        }
    }

    @Test
    fun secondPortPreparationFailureOrPeerMismatchCannotPublishApapOnly() {
        val failed = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = MainBufferedMctlPortPreparer {
                throw IOException("mctl bind failed")
            },
        ) { _, _ -> }
        try {
            assertThrows(IOException::class.java) { failed.prepare(requestWithMctl(5L)) }
            assertThrows(IllegalStateException::class.java) {
                failed.prepare(request(modern = true))
            }
        } finally {
            failed.close()
        }

        val preparer = RecordingMctlPreparer(
            peerOverride = InetAddress.getByName("127.0.0.2"),
            failFirstRetirement = true,
        )
        val mismatch = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                mismatch.prepare(requestWithMctl(6L))
            }
            assertFalse(preparer.ports.single().serverClosed)
            assertEquals(1, preparer.ports.single().rollbackCalls)
            assertEquals(0, preparer.ports.single().closeCalls)
            assertThrows(IllegalStateException::class.java) {
                mismatch.prepare(request(modern = true))
            }
        } finally {
            mismatch.close()
        }
        assertTrue(preparer.ports.single().serverClosed)
        assertEquals(2, preparer.ports.single().rollbackCalls)
        assertEquals(0, preparer.ports.single().closeCalls)
    }

    @Test
    fun secondPortCommitFailureClosesBothBeforeAdmission() {
        val preparer = RecordingMctlPreparer(failCommit = true)
        val delivered = AtomicInteger()
        val owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> delivered.incrementAndGet() }
        val transaction = owner.prepare(requestWithMctl(8L))
        val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
        val connections = stream["streamConnections"] as Map<*, *>
        val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
        val apapPort = (apap["streamConnectionKeyPort"] as Long).toInt()
        Socket(LOOPBACK, apapPort).use {
            assertThrows(IOException::class.java) {
                transaction.flushResponseAndCommit { }
            }
        }
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        assertEquals(0, transaction.tcpSnapshot().candidateCount)
        assertTrue(preparer.ports.single().serverClosed)
        assertEquals(0, delivered.get())
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, apapPort).close() }
        owner.close()
    }

    @Test
    fun secondPortActivationFailureKeepsSharedGateClosedAndRetiresBoth() {
        val preparer = RecordingMctlPreparer(failActivation = true)
        val delivered = AtomicInteger()
        val owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> delivered.incrementAndGet() }
        val transaction = owner.prepare(requestWithMctl(11L))
        val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
        val connections = stream["streamConnections"] as Map<*, *>
        val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
        val apapPort = (apap["streamConnectionKeyPort"] as Long).toInt()
        Socket(LOOPBACK, apapPort).use {
            assertThrows(IOException::class.java) {
                transaction.flushResponseAndCommit { }
            }
        }
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        assertEquals(0, transaction.tcpSnapshot().candidateCount)
        assertFalse(checkNotNull(preparer.ports.single().gate).awaitOpen())
        assertTrue(preparer.ports.single().serverClosed)
        assertEquals(0, delivered.get())
        owner.close()
    }

    @Test
    fun changedMctlPeerAfterResponsePreparationFailsBeforeEitherPortCommits() {
        val preparer = RecordingMctlPreparer()
        val owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> throw AssertionError("changed peer cannot deliver") }
        val transaction = owner.prepare(requestWithMctl(12L))
        preparer.ports.single().expectedPeer = InetAddress.getByName("127.0.0.2")
        assertThrows(IllegalStateException::class.java) {
            transaction.flushResponseAndCommit { }
        }
        assertEquals(0, preparer.ports.single().commitCalls)
        assertEquals(0, transaction.tcpSnapshot().candidateCount)
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        owner.close()
    }

    @Test
    fun failedDualFlushAndRetryableRollbackRetireBothPorts() {
        val preparer = RecordingMctlPreparer(failFirstRetirement = true)
        val owner = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = preparer,
        ) { _, _ -> }
        val transaction = owner.prepare(requestWithMctl(9L))
        assertThrows(IOException::class.java) { transaction.rollback() }
        assertEquals(MainBufferedV27SetupState.RETIRING, transaction.state())
        owner.close()
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertTrue(preparer.ports.single().serverClosed)
        assertEquals(2, preparer.ports.single().rollbackCalls)
        assertEquals(0, preparer.ports.single().closeCalls)

        val nextPreparer = RecordingMctlPreparer()
        val next = owner(
            MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            mctlPortPreparer = nextPreparer,
        ) { _, _ -> }
        try {
            val nextTransaction = next.prepare(requestWithMctl(10L))
            assertThrows(IOException::class.java) {
                nextTransaction.flushResponseAndCommit { throw IOException("partial response") }
            }
            assertEquals(MainBufferedV27SetupState.CLOSED, nextTransaction.state())
            assertTrue(nextPreparer.ports.single().serverClosed)
        } finally {
            next.close()
        }
    }

    @Test
    fun duplicateFlushAndSessionGenerationReplacementFailClosed() {
        val old = owner(MainBufferedV27PortMode.DATA_PORT) { _, _ -> }
        val transaction = old.prepare(request())
        val oldPort = dataPort(transaction)
        assertThrows(IllegalStateException::class.java) { old.prepare(request()) }
        transaction.commitAfterResponseFlushed()
        assertThrows(IllegalStateException::class.java) { transaction.commitAfterResponseFlushed() }
        val duplicateWrites = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            transaction.flushResponseAndCommit { duplicateWrites.incrementAndGet() }
        }
        assertEquals(0, duplicateWrites.get())
        assertThrows(IllegalStateException::class.java) { transaction.rollback() }
        old.close()
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, oldPort).close() }
        assertThrows(IllegalStateException::class.java) { transaction.commitAfterResponseFlushed() }

        val replacement = owner(MainBufferedV27PortMode.DATA_PORT) { _, _ -> }
        try {
            val next = replacement.prepare(request())
            assertEquals(MainBufferedV27SetupState.PREPARED, next.state())
            assertEquals(MainBufferedTcpIngressState.PREPARED, next.tcpSnapshot().state)
        } finally {
            replacement.close()
        }
    }

    @Test
    fun sessionCloseBeforeResponseFlushRevokesReservedPortAndKey() {
        val owner = owner(MainBufferedV27PortMode.DATA_PORT) { _, _ ->
            throw AssertionError("closed generation cannot deliver")
        }
        val transaction = owner.prepare(request())
        val port = dataPort(transaction)
        owner.close()

        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertTrue(transaction.tcpSnapshot().pendingKeyZeroized)
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, port).close() }
        assertThrows(IllegalStateException::class.java) { transaction.commitAfterResponseFlushed() }
        assertThrows(IllegalStateException::class.java) { owner.prepare(request()) }
    }

    @Test
    fun matchingTcpAddressIsRequiredBeforeApapAuthentication() {
        val delivered = AtomicInteger()
        val owner = owner(
            mode = MainBufferedV27PortMode.DATA_PORT,
            expectedPeer = InetAddress.getByName("127.0.0.2"),
            maximumCandidates = 1,
        ) { _, _ -> delivered.incrementAndGet() }
        val transaction = owner.prepare(request())
        try {
            transaction.commitAfterResponseFlushed()
            Socket(LOOPBACK, dataPort(transaction)).close()
            // A rejected peer is now terminal for the exact one-shot SETUP; its asynchronous
            // paired retirement may already have revoked awaitAuthenticatedSession().
            assertTrue(waitUntil { transaction.state() == MainBufferedV27SetupState.CLOSED })
            assertEquals(0, delivered.get())
            assertEquals(1, transaction.tcpSnapshot().candidateCount)
            assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
            assertNull(transaction.tcpSnapshot().ingress)
            assertTrue(transaction.tcpSnapshot().serverClosed)
            assertTrue(transaction.tcpSnapshot().pendingKeyZeroized)
        } finally {
            owner.close()
        }
    }

    @Test
    fun invalidReceiverAllocationAndSinkCapacityFailBeforePublication() {
        assertThrows(IllegalArgumentException::class.java) {
            owner(MainBufferedV27PortMode.DATA_PORT, allocatedStreamId = 0) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner(MainBufferedV27PortMode.DATA_PORT, audioBufferSize = 0) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner(MainBufferedV27PortMode.DATA_PORT, audioBufferSize = UINT32_MAX + 1) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedV27AcceptedFormat(0L, 0L, MainBufferedAudioFormatBits.ALAC_44100_16_STEREO)
        }
    }

    private fun owner(
        mode: MainBufferedV27PortMode,
        allocatedStreamId: Long = 77L,
        audioBufferSize: Long = 4096L,
        expectedPeer: InetAddress = LOOPBACK,
        maximumCandidates: Int = 4,
        closeJoinMillis: Long = 1_000L,
        tcpIngressHooks: MainBufferedTcpIngressHooks = MainBufferedTcpIngressHooks(),
        mctlPortPreparer: MainBufferedMctlPortPreparer? = null,
        lifecycleRetirement: Closeable? = null,
        consumer: MainBufferedIngressPacketConsumer,
    ): MainBufferedV27SetupSession = MainBufferedV27SetupSession.create(
        generation = nextGeneration.getAndIncrement().toLong(),
        bindAddress = LOOPBACK,
        expectedPeer = expectedPeer,
        allocatedStreamId = allocatedStreamId,
        acceptedFormat = MainBufferedV27AcceptedFormat(
            contentType = FOURCC_ALAC,
            samplesPerFrame = 352L,
            audioFormat = MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
        ),
        audioBufferSize = audioBufferSize,
        portMode = mode,
        packetConsumer = consumer,
        ingressLimits = testIngressLimits(),
        tcpLimits = MainBufferedTcpIngressLimits(
            maximumCandidates = maximumCandidates,
            acceptDeadlineMillis = 2_000L,
            firstPacketDeadlineMillis = 2_000L,
            closeJoinMillis = closeJoinMillis,
        ),
        tcpIngressHooks = tcpIngressHooks,
        mctlPortPreparer = mctlPortPreparer,
        lifecycleRetirement = lifecycleRetirement,
    )

    private fun withDetachedAirPlaySession(action: (AirPlaySession) -> Unit) {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            Socket(LOOPBACK, server.localPort).use {
                server.accept().use { accepted ->
                    val session = AirPlaySession(
                        socket = accepted,
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
                        media = object : AirPlayMediaHandler {},
                        sessionTransport = AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK,
                    )
                    try { action(session) } finally { session.close() }
                }
            }
        }
    }

    private fun requestWithMctl(
        seed: Any?,
        key: ByteArray = ByteArray(32) { (it + 1).toByte() },
    ): Map<String, Any?> = request(key, modern = true) + ("streamConnections" to linkedMapOf(
        "streamConnectionTypeAPAP" to emptyMap<String, Any?>(),
        "streamConnectionTypeMediaDataControl" to mapOf(
            "streamConnectionKeyEncryptionSeed" to seed,
        ),
    ))

    private class RecordingMctlPreparer(
        private val failCommit: Boolean = false,
        private val failActivation: Boolean = false,
        private val failFirstRetirement: Boolean = false,
        private val peerOverride: InetAddress? = null,
        private val failAfterReceipt: Boolean = false,
        private val beforeFinalCommit: () -> Unit = {},
        private val beforePrepare: () -> Unit = {},
        private val beforeRollback: () -> Unit = {},
    ) : MainBufferedMctlPortPreparer {
        val requests = ArrayList<MainBufferedMctlPortRequest>()
        val ports = ArrayList<RecordingMctlPort>()

        override fun prepare(request: MainBufferedMctlPortRequest): MainBufferedPreparedMctlPort {
            beforePrepare()
            requests += request
            return RecordingMctlPort(
                request,
                failCommit,
                failActivation,
                failFirstRetirement,
                peerOverride,
                failAfterReceipt,
                beforeFinalCommit,
                beforeRollback,
            ).also { ports += it }
        }
    }

    private class RecordingMctlPort(
        request: MainBufferedMctlPortRequest,
        private val failCommit: Boolean,
        private val failActivation: Boolean,
        private var failFirstRetirement: Boolean,
        peerOverride: InetAddress?,
        private val failAfterReceipt: Boolean,
        private val beforeFinalCommit: () -> Unit,
        private val beforeRollback: () -> Unit,
    ) : MainBufferedPreparedMctlPort {
        private val server = ServerSocket(0, 1, request.bindAddress)
        override val localAddress: InetSocketAddress = server.localSocketAddress as InetSocketAddress
        override val generation: Long = request.generation
        override var expectedPeer: InetAddress = peerOverride ?: request.expectedPeer
        var commitCalls = 0
        var activateCalls = 0
        var rollbackCalls = 0
        var closeCalls = 0
        private var committed = false
        var gate: MainBufferedAdmissionGate? = null
        private var worker: Thread? = null
        var terminalFailureListener: ((Throwable) -> Unit)? = null
        private var receiptStillCurrent = true
        val serverClosed: Boolean get() = server.isClosed

        override fun commitDeferred() {
            commitCalls++
            if (failCommit) throw IOException("mctl deferred commit failed")
            committed = true
        }

        override fun activateWhen(gate: MainBufferedAdmissionGate) {
            activateCalls++
            this.gate = gate
            if (failActivation) throw IOException("mctl activation failed before gate opened")
            worker = Thread({ gate.arriveAndAwaitOpeningTimeNanos(this) },
                "fake-main-buffered-mctl-ready").apply { isDaemon = true; start() }
        }

        override fun awaitAdmissionReceipt(timeoutMillis: Long): MainBufferedMctlAdmissionReceipt? {
            val exactGate = gate ?: return null
            if (!exactGate.awaitReady(this, timeoutMillis) || serverClosed || !committed ||
                worker?.isAlive != true
            ) return null
            val receipt = MainBufferedMctlAdmissionReceipt(
                generation, localAddress, expectedPeer, exactGate, workerAlive = true,
            )
            if (failAfterReceipt) receiptStillCurrent = false
            return receipt
        }

        override fun commitIfAdmissionCurrent(
            receipt: MainBufferedMctlAdmissionReceipt,
            markCommitted: () -> Unit,
        ): Boolean {
            if (!receiptStillCurrent || serverClosed || !committed ||
                gate?.isReady(this) != true || worker?.isAlive != true ||
                receipt.generation != generation || receipt.localAddress != localAddress ||
                receipt.expectedPeer != expectedPeer || receipt.gate !== gate
            ) return false
            beforeFinalCommit()
            markCommitted()
            return true
        }

        override fun onTerminalFailure(listener: (Throwable) -> Unit) {
            check(terminalFailureListener == null)
            terminalFailureListener = listener
        }

        override fun rollback() {
            beforeRollback()
            rollbackCalls++
            check(!committed) { "committed mctl must close, not roll back" }
            retireSocket()
        }

        override fun close() {
            closeCalls++
            check(committed) { "uncommitted mctl must roll back, not close" }
            retireSocket()
        }

        private fun retireSocket() {
            if (failFirstRetirement) {
                failFirstRetirement = false
                throw IOException("mctl close must be retried")
            }
            server.close()
            gate?.close()
        }
    }

    private fun request(
        key: ByteArray = ByteArray(32) { (it + 1).toByte() },
        modern: Boolean = false,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
            "type" to 103L,
            "streamConnectionID" to UINT64_HIGH,
            "supportsDynamicStreamID" to true,
            "ct" to "alac",
            "spf" to 352L,
            "audioFormat" to MainBufferedAudioFormatBits.ALAC_44100_16_STEREO,
            "audioFormatIndex" to 18L,
            "isMedia" to true,
            "audioType" to "media",
            "packetFormatAPAP" to true,
            "shk" to key,
        ).apply {
            if (modern) {
                this["streamConnections"] = linkedMapOf<String, Any?>(
                    "streamConnectionTypeAPAP" to linkedMapOf<String, Any?>(
                        // The plist types/semantics of these fields are not confirmed; they
                        // remain bounded opaque metadata, never substitutes for `shk`.
                        "streamConnectionKeyEncryptionSeed" to byteArrayOf(9, 8, 7),
                        "streamConnectionKeyUseStreamEncryptionKey" to "opaque",
                    ),
                )
            }
        }

    private fun dataPort(transaction: MainBufferedV27SetupTransaction): Int {
        val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
        return (stream["dataPort"] as Long).toInt()
    }

    private fun frame(writer: EncryptedApapWriter, sequence: Int): ByteArray =
        BufferedApapTcpCodec.encode(
            writer.encrypt(
                ApapPacket(
                    header = ApapMediaHeader(sequence.toLong(), 48_000L, sequence),
                    extensions = emptyList(),
                    mediaPayload = byteArrayOf(sequence.toByte()),
                ),
                testIngressLimits().packetLimits,
            ),
            MAX_FRAME_BYTES,
        )

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val UINT64_HIGH: BigInteger = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(5))
        private val UINT64_MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private val nextGeneration = AtomicInteger(10_000)
        private const val FOURCC_ALAC = 0x616c6163L
        private const val UINT32_MAX = 0xffff_ffffL
        private const val MAX_FRAME_BYTES = 1024
        private const val TIMEOUT_MILLIS = 5_000L

        private fun waitUntil(predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
            while (System.nanoTime() < deadline) {
                if (predicate()) return true
                Thread.sleep(5)
            }
            return predicate()
        }

        private fun testIngressLimits(): MainBufferedIngressLimits = MainBufferedIngressLimits(
            maximumFrameBytes = MAX_FRAME_BYTES,
            receiveChunkBytes = MAX_FRAME_BYTES,
            receiveTimeoutMillis = 25L,
            maximumQueuedPackets = 4,
            maximumQueuedBytes = MAX_FRAME_BYTES * 2,
            closeJoinMillis = 1_000L,
            packetLimits = ApapCodecLimits(
                maxPacketBytes = MAX_FRAME_BYTES -
                    BufferedApapTcpCodec.PREFIX_BYTES - EncryptedApapPacketCodec.OVERHEAD_BYTES,
                maxExtensions = 16,
                maxExtensionBytes = 512,
                maxTotalExtensionBytes = 512,
            ),
        )
    }
}
