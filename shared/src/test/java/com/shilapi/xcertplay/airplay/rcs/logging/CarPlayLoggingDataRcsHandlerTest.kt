package com.shilapi.xcertplay.airplay.rcs.logging

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamLimits
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayLoggingDataRcsHandlerTest {
    private val codec = CarPlayLoggingDataCodec()
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val serverReadKey = ByteArray(32) { 31 }
    private val serverWriteKey = ByteArray(32) { 32 }

    @Test
    fun factoryIsOffByDefaultAndOnlyRegistersExactClientType() {
        val acquisitions = AtomicInteger()
        val factory = CarPlayLoggingDataRcsHandlerFactory(
            CarPlayLoggingArchiveProvider {
                acquisitions.incrementAndGet()
                CarPlayLoggingArchiveBytes(byteArrayOf(1))
            },
        )
        assertEquals(null, factory.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
        val descriptor = factory.descriptor(RcsClientTypes.CAR_PLAY_LOGGING_DATA)!!
        assertFalse(descriptor.probe().isReady)
        assertEquals("log_transfer_disabled", descriptor.probe().reasonCode)
        val stream = newStream()
        try {
            assertThrows(IOException::class.java) { descriptor.create(stream) }
            assertEquals(0, acquisitions.get())
        } finally {
            stream.close()
        }
    }

    @Test
    fun archiveIsAcquiredOnlyAfterEncryptedPeerRequestAndAckIsSessionBound() {
        val acquisitions = AtomicInteger()
        val completed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<CarPlayLoggingDataSessionEvent>()
        val factory = CarPlayLoggingDataRcsHandlerFactory(
            archiveProvider = CarPlayLoggingArchiveProvider {
                acquisitions.incrementAndGet()
                CarPlayLoggingArchiveBytes(byteArrayOf(4, 5, 6))
            },
            capabilityGate = { true },
            onEvent = { _, event ->
                events += event
                if (event == CarPlayLoggingDataSessionEvent.ArchiveCompleted) completed.countDown()
            },
        )
        val descriptor = factory.descriptor(RcsClientTypes.CAR_PLAY_LOGGING_DATA)!!
        assertTrue(descriptor.probe().isReady)
        val stream = newStream()
        val handler = descriptor.create(stream) as CarPlayLoggingDataRcsHandler
        stream.listen(handler)
        assertEquals(0, acquisitions.get())

        // A same-address TCP connection is not an authenticated RCS peer. No source is opened.
        Socket().use { candidate ->
            candidate.connect(InetSocketAddress(loopback, stream.port), 2_000)
            candidate.getOutputStream().write(byteArrayOf(1, 0, 0))
        }
        assertEquals(0, acquisitions.get())

        val client = connectClient(stream.port)
        try {
            client.send(codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive))
            assertTrue(stream.awaitPeerConnection(2_000))
            val header = codec.decodeToPhone(requireNotNull(client.receiveNext(2_000)))
                as CarPlayLoggingDataMessage.ArchiveHeader
            assertEquals(1, header.chunkCount)
            val chunk = codec.decodeToPhone(requireNotNull(client.receiveNext(2_000)))
                as CarPlayLoggingDataMessage.ArchiveChunk
            assertEquals(header.sessionId, chunk.sessionId)
            assertEquals(0, chunk.chunkIndex)
            assertArrayEquals(byteArrayOf(4, 5, 6), chunk.data)
            assertEquals(1, acquisitions.get())

            client.send(codec.encodeFromPhone(CarPlayLoggingDataMessage.Ack(0)))
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    CarPlayLoggingDataSessionEvent.ArchiveSent,
                    CarPlayLoggingDataSessionEvent.ArchiveCompleted,
                ),
                events,
            )
            assertEquals(CarPlayLoggingArchiveState.COMPLETED, handler.archiveState)
        } finally {
            client.close()
            stream.close()
        }
    }

    @Test
    fun serialDispatchRejectsMalformedInputWithoutOpeningArchive() {
        val acquisitions = AtomicInteger()
        val fatal = CountDownLatch(1)
        val session = CarPlayLoggingDataSerialSession(
            sessionId = "session",
            archiveProvider = CarPlayLoggingArchiveProvider {
                acquisitions.incrementAndGet()
                CarPlayLoggingArchiveBytes(byteArrayOf(1))
            },
            send = {},
            capabilityGate = { true },
            onTerminalFailure = { fatal.countDown() },
        )
        try {
            session.onMessage(RcsMessage.comm(byteArrayOf(0)))
            assertTrue(fatal.await(2, TimeUnit.SECONDS))
            assertTrue(session.awaitStopped(2_000))
            assertEquals(0, acquisitions.get())
            assertThrows(IOException::class.java) {
                session.onMessage(codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive))
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun boundedQueueDoesNotBlockReceiveCallbackBehindSlowArchiveSource() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = CarPlayLoggingDataSerialSession(
            sessionId = "session",
            archiveProvider = CarPlayLoggingArchiveProvider {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                CarPlayLoggingArchiveBytes(byteArrayOf(1))
            },
            send = {},
            capabilityGate = { true },
            maxQueuedMessages = 1,
        )
        try {
            val request = codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive)
            session.onMessage(request)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.onMessage(request)
            val started = System.nanoTime()
            assertThrows(IOException::class.java) { session.onMessage(request) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        } finally {
            release.countDown()
            session.close()
            assertTrue(session.awaitStopped(2_000))
        }
    }

    @Test
    fun statisticsNeedsRequestAndExplicitPublisher() {
        val requested = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<RcsMessage>()
        val session = CarPlayLoggingDataSerialSession(
            sessionId = "session",
            archiveProvider = CarPlayLoggingArchiveProvider {
                throw AssertionError("statistics must not acquire archive")
            },
            send = { sent += it },
            capabilityGate = { true },
            onEvent = {
                if (it == CarPlayLoggingDataSessionEvent.StatisticsIntervalRequested(17uL)) {
                    requested.countDown()
                }
            },
        )
        try {
            assertThrows(CarPlayLoggingDataProtocolException::class.java) {
                session.publishStatisticsBlocking(mapOf("opaque" to 1L))
            }
            session.onMessage(codec.encodeFromPhone(
                CarPlayLoggingDataMessage.RequestStatistics(17uL),
            ))
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            session.publishStatisticsBlocking(mapOf("opaque" to byteArrayOf(7, 8)))
            val stats = codec.decodeToPhone(sent.single()) as CarPlayLoggingDataMessage.Statistics
            assertArrayEquals(byteArrayOf(7, 8), stats.data["opaque"] as ByteArray)
        } finally {
            session.close()
            assertTrue(session.awaitStopped(2_000))
        }
    }

    @Test
    fun withdrawingCapabilityAfterHeaderPreventsSendingAnyArchiveChunk() {
        val enabled = AtomicBoolean(true)
        val sent = CopyOnWriteArrayList<RcsMessage>()
        val fatal = CountDownLatch(1)
        val session = CarPlayLoggingDataSerialSession(
            sessionId = "session",
            archiveProvider = CarPlayLoggingArchiveProvider {
                CarPlayLoggingArchiveBytes(byteArrayOf(1, 2, 3))
            },
            send = {
                sent += it
                enabled.set(false)
            },
            capabilityGate = enabled::get,
            onTerminalFailure = { fatal.countDown() },
        )
        try {
            session.onMessage(codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive))
            assertTrue(fatal.await(2, TimeUnit.SECONDS))
            assertTrue(session.awaitStopped(2_000))
            assertEquals(1, sent.size)
            assertTrue(codec.decodeToPhone(sent.single()) is CarPlayLoggingDataMessage.ArchiveHeader)
        } finally {
            session.close()
        }
    }

    private fun newStream(): RcsDataStream = RcsDataStream.open(
        clientType = RcsClientTypes.CAR_PLAY_LOGGING_DATA,
        readKey = serverReadKey,
        writeKey = serverWriteKey,
        bindAddress = loopback,
        expectedPeerAddress = loopback,
        limits = RcsDataStreamLimits(
            acceptPollMillis = 20,
            candidateAuthenticationTimeoutMillis = 500,
            receivePollMillis = 20,
            peerConnectTimeoutMillis = 2_000,
        ),
    )

    private fun connectClient(port: Int): RcsChannel = RcsChannel.connect(
        remote = InetSocketAddress(loopback, port),
        readKey = serverWriteKey,
        writeKey = serverReadKey,
    )
}
