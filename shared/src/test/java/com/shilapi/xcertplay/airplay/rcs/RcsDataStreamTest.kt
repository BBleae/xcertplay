package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcsDataStreamTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val serverReadKey = ByteArray(32) { 21 }
    private val serverWriteKey = ByteArray(32) { 22 }

    @Test
    fun exactBindAndLateHandlerAttachmentPreserveTheAuthenticatedFirstFrame() {
        val stream = newStream()
        val port = stream.port
        assertEquals(loopback, stream.localAddress)
        assertFalse(stream.localAddress.isAnyLocalAddress)

        val client = connectClient(port)
        val opened = CountDownLatch(1)
        val received = CountDownLatch(1)
        val messages = CopyOnWriteArrayList<RcsMessage>()
        try {
            client.sendComm(byteArrayOf(1, 2, 3))
            assertTrue(stream.awaitPeerConnection(2_000))

            stream.attach(
                object : RcsDataStreamHandler {
                    override fun onStreamOpened(stream: RcsDataStream) {
                        opened.countDown()
                    }

                    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                        messages += message
                        received.countDown()
                    }
                },
            )
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(1, 2, 3), messages.single().body)
            stream.close()
            assertFalse(stream.awaitPeerConnection(40))
        } finally {
            client.close()
            stream.close()
        }
    }

    @Test
    fun invalidSameIpCandidateCannotConsumeTheWinningCipherCounter() {
        val received = CountDownLatch(1)
        val bodies = CopyOnWriteArrayList<ByteArray>()
        val stream = newStream(
            handler = object : RcsDataStreamHandler {
                override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                    bodies += message.body
                    received.countDown()
                }
            },
        )
        val port = stream.port

        val invalid = Socket()
        invalid.connect(InetSocketAddress(loopback, port), 2_000)
        val candidateCodec = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        val sealed = candidateCodec.encrypt(RcsMessage.comm(byteArrayOf(99)).encoded())
        sealed[sealed.lastIndex] = (sealed.last() xor 0x01)
        invalid.getOutputStream().apply {
            write(sealed)
            flush()
        }
        invalid.close()

        val valid = connectClient(port)
        try {
            valid.sendComm(byteArrayOf(7, 8, 9))
            assertTrue(stream.awaitPeerConnection(3_000))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            assertEquals(1, bodies.size)
            assertArrayEquals(byteArrayOf(7, 8, 9), bodies.single())
        } finally {
            valid.close()
            stream.close()
        }
    }

    @Test
    fun racingValidCandidatesProduceExactlyOneWinnerAndOneOpenCallback() {
        val openedCount = AtomicInteger()
        val messageLatch = CountDownLatch(1)
        val stream = newStream(
            handler = object : RcsDataStreamHandler {
                override fun onStreamOpened(stream: RcsDataStream) {
                    openedCount.incrementAndGet()
                }

                override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                    messageLatch.countDown()
                }
            },
        )
        val first = connectClient(stream.port)
        val second = connectClient(stream.port)
        val start = CountDownLatch(1)
        val senders = listOf(first to 1, second to 2).map { (client, value) ->
            Thread {
                start.await()
                runCatching { client.sendComm(byteArrayOf(value.toByte())) }
            }.apply { start() }
        }
        try {
            start.countDown()
            senders.forEach { it.join(2_000) }
            assertTrue(stream.awaitPeerConnection(3_000))
            assertTrue(messageLatch.await(3, TimeUnit.SECONDS))
            assertEquals(1, openedCount.get())

            val firstEnded = endedOrFailed(first)
            val secondEnded = endedOrFailed(second)
            assertTrue("one alternate candidate must be closed", firstEnded.xor(secondEnded))
        } finally {
            first.close()
            second.close()
            stream.close()
        }
    }

    @Test
    fun peerWaitTimesOutAndCloseIsIdempotentWithOneCloseCallback() {
        val closedCount = AtomicInteger()
        val stream = newStream(
            handler = object : RcsDataStreamHandler {
                override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
                    closedCount.incrementAndGet()
                }
            },
        )
        val ownedReadKey = RcsDataStream::class.java.getDeclaredField("readKey").run {
            isAccessible = true
            get(stream) as ByteArray
        }
        val ownedWriteKey = RcsDataStream::class.java.getDeclaredField("writeKey").run {
            isAccessible = true
            get(stream) as ByteArray
        }
        assertFalse(stream.awaitPeerConnection(40))
        stream.close()
        stream.close()
        assertTrue(ownedReadKey.all { it == 0.toByte() })
        assertTrue(ownedWriteKey.all { it == 0.toByte() })
        assertTrue(serverReadKey.all { it == 21.toByte() })
        assertTrue(serverWriteKey.all { it == 22.toByte() })
        assertEquals(1, closedCount.get())
        assertFalse(stream.awaitPeerConnection(40))
    }

    @Test
    fun concurrentAuthenticationAndAttachmentEmitOpenExactlyOnce() {
        repeat(12) {
            val stream = newStream()
            val client = connectClient(stream.port)
            val openedCount = AtomicInteger()
            val messageCount = AtomicInteger()
            val received = CountDownLatch(1)
            val start = CountDownLatch(1)
            val handler = object : RcsDataStreamHandler {
                override fun onStreamOpened(stream: RcsDataStream) {
                    openedCount.incrementAndGet()
                }

                override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
                    messageCount.incrementAndGet()
                    received.countDown()
                }
            }
            val sender = Thread {
                start.await()
                client.sendComm(byteArrayOf(5))
            }.apply { start() }
            val attacher = Thread {
                start.await()
                stream.attach(handler)
            }.apply { start() }
            val repeatedAttacher = Thread {
                start.await()
                stream.attach(handler)
            }.apply { start() }
            try {
                start.countDown()
                sender.join(2_000)
                attacher.join(2_000)
                repeatedAttacher.join(2_000)
                assertTrue(stream.awaitPeerConnection(2_000))
                assertTrue(received.await(2, TimeUnit.SECONDS))
                assertEquals(1, openedCount.get())
                assertEquals(1, messageCount.get())
            } finally {
                client.close()
                stream.close()
            }
        }
    }

    @Test
    fun readinessProbeFailureIsFailClosedAndReasonIsBounded() {
        val descriptor = RcsDataStreamHandlerDescriptor(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            implementationId = "throwing-probe",
            readinessProbe = { error("sensitive details") },
            handlerFactory = { object : RcsDataStreamHandler {} },
        )
        val probe = descriptor.probe()
        assertFalse(probe.isReady)
        assertEquals("probe_failed", probe.reasonCode)
    }

    private fun newStream(handler: RcsDataStreamHandler? = null): RcsDataStream {
        val stream = RcsDataStream.open(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
            expectedPeerAddress = loopback,
            limits = RcsDataStreamLimits(
                acceptPollMillis = 20,
                candidateAuthenticationTimeoutMillis = 500,
                receivePollMillis = 20,
                peerConnectTimeoutMillis = 2_000,
                maxConcurrentCandidates = 4,
                maxCandidateAttempts = 8,
            ),
        )
        stream.listen(handler)
        return stream
    }

    private fun connectClient(port: Int): RcsChannel = RcsChannel.connect(
        remote = InetSocketAddress(loopback, port),
        readKey = serverWriteKey,
        writeKey = serverReadKey,
    )

    private fun endedOrFailed(channel: RcsChannel): Boolean {
        val result = runCatching { channel.receiveNext(250) }
        return result.isFailure || channel.isPeerEnded || channel.isClosed
    }

    private infix fun Byte.xor(mask: Int): Byte = (toInt() xor mask).toByte()
}
