package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUpdateDataRcsHandlerTest {
    private val codec = CarPlayUpdateDataCodec()

    @Test
    fun terminalAckEvidenceAppearsOnlyAfterSuccessfulAckSend() {
        val sent = CopyOnWriteArrayList<RcsMessage>()
        val sender = CarPlayUpdateDataTerminalAckSender({ sent += it })
        assertFalse(sender.terminalAckSent)

        val ack = codec.encode(CarPlayUpdateDataMessage.Ack(0))
        sender.send(ack)

        assertTrue(sender.terminalAckSent)
        assertEquals(listOf(ack), sent)
    }

    @Test
    fun failedSendOrNonAckDoesNotCreateTerminalAckEvidence() {
        val failedSender = CarPlayUpdateDataTerminalAckSender({ throw IOException("send failed") })
        val ack = codec.encode(CarPlayUpdateDataMessage.Ack(1))
        assertTrue(runCatching { failedSender.send(ack) }.exceptionOrNull() is IOException)
        assertFalse(failedSender.terminalAckSent)

        val sent = CopyOnWriteArrayList<RcsMessage>()
        val wrongMessageSender = CarPlayUpdateDataTerminalAckSender({ sent += it })
        assertTrue(
            runCatching { wrongMessageSender.send(codec.encode(header(1))) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(sent.isEmpty())
        assertFalse(wrongMessageSender.terminalAckSent)
    }

    @Test
    fun pumpAcknowledgesOnlyAfterDurableQuarantineCommit() {
        val sink = RecordingSink()
        val acks = CopyOnWriteArrayList<Int>()
        val pump = CarPlayUpdateDataSessionPump(sink, { message ->
            assertTrue("ACK must follow durable commit", sink.committed)
            acks += (codec.decode(message) as CarPlayUpdateDataMessage.Ack).accepted
        })

        assertFalse(pump.accept(codec.encode(header(2))))
        assertFalse(pump.accept(codec.encode(chunk(0, byteArrayOf(1, 2)))))
        assertTrue(acks.isEmpty())
        assertTrue(pump.accept(codec.encode(chunk(1, byteArrayOf(3), sha256(byteArrayOf(1, 2, 3))))))
        assertEquals(listOf(0), acks)
        assertEquals(1, sink.commitCalls)
        assertFalse(sink.aborted)
        pump.close()
        assertEquals(listOf(0), acks)
    }

    @Test
    fun malformedOrDuplicateMessageGetsOnlyRejectionAck() {
        val malformedSink = RecordingSink()
        val malformedAcks = CopyOnWriteArrayList<Int>()
        val malformed = CarPlayUpdateDataSessionPump(malformedSink, ackCollector(malformedAcks))
        assertTrue(malformed.accept(RcsMessage.comm(byteArrayOf())))
        assertEquals(listOf(1), malformedAcks)
        assertTrue(malformedSink.aborted)
        malformed.close()

        val duplicateSink = RecordingSink()
        val duplicateAcks = CopyOnWriteArrayList<Int>()
        val duplicate = CarPlayUpdateDataSessionPump(duplicateSink, ackCollector(duplicateAcks))
        assertFalse(duplicate.accept(codec.encode(header(2))))
        assertFalse(duplicate.accept(codec.encode(chunk(0, byteArrayOf(1)))))
        assertTrue(duplicate.accept(codec.encode(chunk(0, byteArrayOf(1)))))
        assertEquals(listOf(1), duplicateAcks)
        assertTrue(duplicateSink.aborted)
        assertEquals(0, duplicateSink.bytes.size())
        duplicate.close()
    }

    @Test
    fun failedSinkCommitGetsRejectionAckAndCleanup() {
        val sink = RecordingSink().apply { commitAllowed = false }
        val acks = CopyOnWriteArrayList<Int>()
        val pump = CarPlayUpdateDataSessionPump(sink, ackCollector(acks))
        assertFalse(pump.accept(codec.encode(header(1))))
        assertTrue(pump.accept(codec.encode(chunk(0, byteArrayOf(7), sha256(byteArrayOf(7))))))
        assertEquals(listOf(1), acks)
        assertEquals(1, sink.commitCalls)
        assertTrue(sink.aborted)
        pump.close()
    }

    @Test
    fun closeDuringCommitSuppressesPostCloseSuccessAck() {
        val sink = RecordingSink().apply {
            commitEntered = CountDownLatch(1)
            releaseCommit = CountDownLatch(1)
        }
        val acks = CopyOnWriteArrayList<Int>()
        val pump = CarPlayUpdateDataSessionPump(sink, ackCollector(acks))
        assertFalse(pump.accept(codec.encode(header(1))))
        val processor = Thread {
            pump.accept(codec.encode(chunk(0, byteArrayOf(5), sha256(byteArrayOf(5)))))
        }
        processor.start()
        assertTrue(sink.commitEntered!!.await(2, TimeUnit.SECONDS))
        pump.requestClose()
        sink.releaseCommit!!.countDown()
        processor.join(2_000)
        assertFalse(processor.isAlive)
        pump.close()
        assertTrue("no ACK may be sent after close was requested", acks.isEmpty())
    }

    @Test
    fun boundedSerialWorkerRejectsOverflowWithoutBlockingCallback() {
        val sink = RecordingSink().apply {
            beginEntered = CountDownLatch(1)
            releaseBegin = CountDownLatch(1)
        }
        val acks = CopyOnWriteArrayList<Int>()
        val finished = CountDownLatch(1)
        val session = CarPlayUpdateDataSerialSession(
            sink = sink,
            send = ackCollector(acks),
            maxQueuedMessages = 1,
            onFinished = { finished.countDown() },
        )
        try {
            session.onMessage(codec.encode(header(2)))
            assertTrue(sink.beginEntered!!.await(2, TimeUnit.SECONDS))
            session.onMessage(codec.encode(chunk(0, byteArrayOf(1))))
            val callbackReturned = CountDownLatch(1)
            Thread {
                session.onMessage(codec.encode(chunk(1, byteArrayOf(2), sha256(byteArrayOf(1, 2)))))
                callbackReturned.countDown()
            }.start()
            assertTrue("RCS callback must not wait for disk I/O", callbackReturned.await(2, TimeUnit.SECONDS))
            sink.releaseBegin!!.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1), acks)
            assertTrue(sink.aborted)
            assertEquals(0, sink.commitCalls)
        } finally {
            sink.releaseBegin?.countDown()
            session.close()
        }
    }

    @Test
    fun extraMessageQueuedDuringFinalCommitDowngradesSuccessAck() {
        val sink = RecordingSink().apply {
            commitEntered = CountDownLatch(1)
            releaseCommit = CountDownLatch(1)
        }
        val acks = CopyOnWriteArrayList<Int>()
        val finished = CountDownLatch(1)
        val session = CarPlayUpdateDataSerialSession(
            sink, ackCollector(acks), onFinished = { finished.countDown() },
        )
        try {
            session.onMessage(codec.encode(header(1)))
            session.onMessage(codec.encode(chunk(0, byteArrayOf(9), sha256(byteArrayOf(9)))))
            assertTrue(sink.commitEntered!!.await(2, TimeUnit.SECONDS))

            val callbackReturned = CountDownLatch(1)
            Thread {
                session.onMessage(codec.encode(header(1)))
                callbackReturned.countDown()
            }.start()
            assertTrue("RCS callback must not wait for fsync", callbackReturned.await(2, TimeUnit.SECONDS))
            sink.releaseCommit!!.countDown()

            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1), acks)
            assertEquals(1, sink.commitCalls)
            assertTrue("the durable quarantine record is still untrusted", sink.committed)
            assertTrue(
                "a second transfer on the same stream must not be silently ignored",
                runCatching { session.onMessage(codec.encode(header(1))) }.exceptionOrNull()
                    is IOException,
            )
        } finally {
            sink.releaseCommit?.countDown()
            session.close()
        }
    }

    @Test
    fun overflowRecordedDuringFinalCommitCannotRaceThroughAsAckZero() {
        val sink = RecordingSink().apply {
            beginEntered = CountDownLatch(1)
            commitEntered = CountDownLatch(1)
            releaseCommit = CountDownLatch(1)
        }
        val acks = CopyOnWriteArrayList<Int>()
        val finished = CountDownLatch(1)
        val session = CarPlayUpdateDataSerialSession(
            sink = sink,
            send = ackCollector(acks),
            maxQueuedMessages = 1,
            onFinished = { finished.countDown() },
        )
        try {
            session.onMessage(codec.encode(header(1)))
            assertTrue(sink.beginEntered!!.await(2, TimeUnit.SECONDS))
            session.onMessage(codec.encode(chunk(0, byteArrayOf(6), sha256(byteArrayOf(6)))))
            assertTrue(sink.commitEntered!!.await(2, TimeUnit.SECONDS))

            session.onMessage(codec.encode(header(1)))
            val callbackReturned = CountDownLatch(1)
            Thread {
                session.onMessage(codec.encode(header(1)))
                callbackReturned.countDown()
            }.start()
            assertTrue("overflow must not wait for the ACK gate", callbackReturned.await(2, TimeUnit.SECONDS))
            sink.releaseCommit!!.countDown()

            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1), acks)
            assertEquals(1, sink.commitCalls)
        } finally {
            sink.releaseCommit?.countDown()
            session.close()
        }
    }

    @Test
    fun messageAfterTerminalSealFailsWhileAckSendIsInFlight() {
        val sink = RecordingSink()
        val acks = CopyOnWriteArrayList<Int>()
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val session = CarPlayUpdateDataSerialSession(
            sink = sink,
            send = { message ->
                sendEntered.countDown()
                assertTrue(releaseSend.await(2, TimeUnit.SECONDS))
                acks += (codec.decode(message) as CarPlayUpdateDataMessage.Ack).accepted
            },
            onFinished = { finished.countDown() },
        )
        try {
            session.onMessage(codec.encode(header(1)))
            session.onMessage(codec.encode(chunk(0, byteArrayOf(4), sha256(byteArrayOf(4)))))
            assertTrue(sendEntered.await(2, TimeUnit.SECONDS))

            val callbackReturned = CountDownLatch(1)
            val protocolError = CopyOnWriteArrayList<Throwable?>()
            Thread {
                protocolError += runCatching {
                    session.onMessage(codec.encode(header(1)))
                }.exceptionOrNull()
                callbackReturned.countDown()
            }.start()
            assertTrue("RCS callback must not wait for ACK send", callbackReturned.await(2, TimeUnit.SECONDS))
            assertTrue(protocolError.single() is IOException)

            releaseSend.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(0), acks)
        } finally {
            releaseSend.countDown()
            session.close()
        }
    }

    @Test
    fun serialCloseWhileCommitBlockedCannotSendAckZero() {
        val sink = RecordingSink().apply {
            commitEntered = CountDownLatch(1)
            releaseCommit = CountDownLatch(1)
        }
        val acks = CopyOnWriteArrayList<Int>()
        val finished = CountDownLatch(1)
        val session = CarPlayUpdateDataSerialSession(
            sink, ackCollector(acks), onFinished = { finished.countDown() },
        )
        try {
            session.onMessage(codec.encode(header(1)))
            session.onMessage(codec.encode(chunk(0, byteArrayOf(9), sha256(byteArrayOf(9)))))
            assertTrue(sink.commitEntered!!.await(2, TimeUnit.SECONDS))
            session.close()
            sink.releaseCommit!!.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(acks.isEmpty())
        } finally {
            sink.releaseCommit?.countDown()
            session.close()
        }
    }

    @Test
    fun serialCloseAbortsPartialQuarantineAndDropsQueuedInput() {
        val sink = RecordingSink().apply { beginEntered = CountDownLatch(1) }
        val acks = CopyOnWriteArrayList<Int>()
        val finished = CountDownLatch(1)
        val session = CarPlayUpdateDataSerialSession(
            sink, ackCollector(acks), onFinished = { finished.countDown() },
        )
        session.onMessage(codec.encode(header(2)))
        assertTrue(sink.beginEntered!!.await(2, TimeUnit.SECONDS))
        session.onMessage(codec.encode(chunk(0, byteArrayOf(4))))
        session.close()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(sink.aborted)
        assertTrue(acks.isEmpty())
        assertEquals(0, sink.commitCalls)
    }

    @Test
    fun interruptedWorkerClearsInterruptBeforeRetryingQuarantineAbort() {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val sink = object : CarPlayUpdateDataQuarantineSink {
            @Volatile var aborted = false

            override fun begin(header: CarPlayUpdateDataMessage.Header) {
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted test begin")
                }
            }

            override fun append(bytes: ByteArray) = Unit

            override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean = false

            override fun abort() {
                if (Thread.currentThread().isInterrupted) throw ClosedByInterruptException()
                aborted = true
            }
        }
        val acks = CopyOnWriteArrayList<Int>()
        val session = CarPlayUpdateDataSerialSession(
            sink, ackCollector(acks), onFinished = { finished.countDown() },
        )
        session.onMessage(codec.encode(header(1)))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        session.close()
        session.close() // must not send a second interrupt while cleanup is running
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(sink.aborted)
        assertTrue(acks.isEmpty())
    }

    private fun ackCollector(acks: MutableList<Int>): (RcsMessage) -> Unit = { message ->
        acks += (codec.decode(message) as CarPlayUpdateDataMessage.Ack).accepted
    }

    private fun header(count: Int) = CarPlayUpdateDataMessage.Header(SESSION_ID, 27, count)

    private fun chunk(index: Int, bytes: ByteArray, checksum: String? = null) =
        CarPlayUpdateDataMessage.Chunk(SESSION_ID, index, bytes, checksum)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class RecordingSink : CarPlayUpdateDataQuarantineSink {
        val bytes = ByteArrayOutputStream()
        @Volatile var committed = false
        @Volatile var aborted = false
        @Volatile var commitAllowed = true
        @Volatile var commitCalls = 0
        var beginEntered: CountDownLatch? = null
        var releaseBegin: CountDownLatch? = null
        var commitEntered: CountDownLatch? = null
        var releaseCommit: CountDownLatch? = null

        override fun begin(header: CarPlayUpdateDataMessage.Header) {
            beginEntered?.countDown()
            await(releaseBegin)
        }

        override fun append(bytes: ByteArray) {
            this.bytes.write(bytes)
        }

        override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean {
            commitCalls++
            commitEntered?.countDown()
            await(releaseCommit)
            committed = commitAllowed
            return committed
        }

        override fun abort() {
            aborted = true
            bytes.reset()
        }

        private fun await(latch: CountDownLatch?) {
            if (latch == null) return
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (latch.count != 0L) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw IOException("test sink timed out")
                try {
                    if (latch.await(remaining, TimeUnit.NANOSECONDS)) return
                } catch (_: InterruptedException) {
                    // A close may interrupt the worker; keep waiting until test-controlled release.
                }
            }
        }
    }

    companion object {
        private const val SESSION_ID = "C0DE1234-5678-4ABC-9DEF-0123456789AB"
    }
}
