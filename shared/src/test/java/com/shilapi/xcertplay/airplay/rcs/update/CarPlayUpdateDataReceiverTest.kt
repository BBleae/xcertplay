package com.shilapi.xcertplay.airplay.rcs.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUpdateDataReceiverTest {
    private val codec = CarPlayUpdateDataCodec()

    @Test
    fun streamsMultipleChunksToQuarantineAndWaitsForDurableCommitBeforeAckZero() {
        val sink = MemoryQuarantineSink()
        val receiver = CarPlayUpdateDataReceiver(sink)
        val archive = byteArrayOf(1, 2, 3, 4, 5)
        assertThrows(IllegalStateException::class.java) {
            receiver.confirmDurableQuarantineCommit(fakeReceipt())
        }

        assertEquals(
            CarPlayUpdateDataReceiveResult.Receiving(0, 0),
            receiver.receive(codec.encode(header(2))),
        )
        assertEquals(
            CarPlayUpdateDataReceiveResult.Receiving(1, 2),
            receiver.receive(codec.encode(chunk(0, byteArrayOf(1, 2)))),
        )
        val pending = receiver.receive(
            codec.encode(chunk(1, byteArrayOf(3, 4, 5), sha256(archive))),
        ) as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit
        assertArrayEquals(archive, sink.bytes.toByteArray())
        assertFalse(sink.aborted)
        assertEquals(archive.size.toLong(), pending.receipt.archiveBytes)
        assertEquals(sha256(archive), pending.receipt.archiveSha256)
        assertEquals(2, pending.receipt.chunkCount)
        assertEquals(27L, pending.receipt.version)
        assertEquals(
            CarPlayUpdateDataMetadataDigest(2, sha256(byteArrayOf(8, 9))),
            pending.receipt.certificate,
        )
        assertThrows(IllegalStateException::class.java) {
            receiver.confirmDurableQuarantineCommit(fakeReceipt())
        }
        // The sink must perform and confirm the durable commit; a receipt alone is insufficient.
        sink.allowCommit = true
        assertEquals(0, receiver.confirmDurableQuarantineCommit(pending.receipt).accepted)
        assertTrue(sink.durablyCommitted)
        receiver.close()
        assertFalse(sink.aborted)
        assertThrows(IllegalStateException::class.java) {
            receiver.confirmDurableQuarantineCommit(pending.receipt)
        }
    }

    @Test
    fun rejectsWrongSessionOrderDuplicateAndChecksum() {
        listOf(
            "wrong ID" to chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)), OTHER_SESSION_ID),
            "wrong order" to chunk(1, byteArrayOf(1), sha256(byteArrayOf(1))),
            "missing checksum" to chunk(0, byteArrayOf(1)),
            "incorrect checksum" to chunk(0, byteArrayOf(1), "0".repeat(64)),
        ).forEach { (_, message) ->
            val sink = MemoryQuarantineSink()
            val receiver = CarPlayUpdateDataReceiver(sink)
            receiver.receive(codec.encode(header(1)))
            val rejection = receiver.receive(codec.encode(message))
                as CarPlayUpdateDataReceiveResult.Rejected
            assertEquals(1, rejection.ack.accepted)
            assertTrue(sink.aborted)
            assertEquals(0, sink.bytes.size())
        }

        val duplicateHeaderSink = MemoryQuarantineSink()
        val duplicateHeaderReceiver = CarPlayUpdateDataReceiver(duplicateHeaderSink)
        duplicateHeaderReceiver.receive(codec.encode(header(2)))
        assertEquals(
            1,
            (duplicateHeaderReceiver.receive(codec.encode(header(2)))
                as CarPlayUpdateDataReceiveResult.Rejected).ack.accepted,
        )
        assertTrue(duplicateHeaderSink.aborted)

        val duplicateChunkSink = MemoryQuarantineSink()
        val duplicateChunkReceiver = CarPlayUpdateDataReceiver(duplicateChunkSink)
        duplicateChunkReceiver.receive(codec.encode(header(2)))
        duplicateChunkReceiver.receive(codec.encode(chunk(0, byteArrayOf(1))))
        assertEquals(
            1,
            (duplicateChunkReceiver.receive(codec.encode(chunk(0, byteArrayOf(1))))
                as CarPlayUpdateDataReceiveResult.Rejected).ack.accepted,
        )
        assertTrue(duplicateChunkSink.aborted)
    }

    @Test
    fun refusesPrematureChecksumExtraMessageAndArchiveOversize() {
        val firstSink = MemoryQuarantineSink()
        val first = CarPlayUpdateDataReceiver(firstSink)
        first.receive(codec.encode(header(2)))
        assertTrue(
            first.receive(codec.encode(chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)))))
                is CarPlayUpdateDataReceiveResult.Rejected,
        )

        val secondSink = MemoryQuarantineSink()
        val second = CarPlayUpdateDataReceiver(secondSink)
        second.receive(codec.encode(header(1)))
        second.receive(codec.encode(chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)))))
        val lateMessage = second.receive(codec.encode(chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)))))
        assertTrue(lateMessage is CarPlayUpdateDataReceiveResult.Rejected)
        assertTrue(secondSink.aborted)

        val limits = CarPlayUpdateDataLimits(maxArchiveBytes = 3)
        val thirdSink = MemoryQuarantineSink()
        val third = CarPlayUpdateDataReceiver(thirdSink, limits)
        third.receive(codec.encode(header(2)))
        third.receive(codec.encode(chunk(0, byteArrayOf(1, 2))))
        val oversized = third.receive(
            codec.encode(chunk(1, byteArrayOf(3, 4), sha256(byteArrayOf(1, 2, 3, 4)))),
        )
        assertTrue(oversized is CarPlayUpdateDataReceiveResult.Rejected)
        assertTrue(thirdSink.aborted)
    }

    @Test
    fun malformedOpackAndUnexpectedAckRejectWithoutSuccess() {
        val sink = MemoryQuarantineSink()
        val receiver = CarPlayUpdateDataReceiver(sink)
        val malformed = receiver.receive(com.shilapi.xcertplay.airplay.rcs.RcsMessage.comm(byteArrayOf()))
        assertEquals(1, (malformed as CarPlayUpdateDataReceiveResult.Rejected).ack.accepted)
        assertTrue(sink.aborted)

        val ackSink = MemoryQuarantineSink()
        val ackReceiver = CarPlayUpdateDataReceiver(ackSink)
        val unexpected = ackReceiver.receive(codec.encode(CarPlayUpdateDataMessage.Ack(0)))
        assertEquals(1, (unexpected as CarPlayUpdateDataReceiveResult.Rejected).ack.accepted)
        assertTrue(ackSink.aborted)
    }

    @Test
    fun cancellationAndCloseInvalidatePartialOrPendingQuarantine() {
        val partialSink = MemoryQuarantineSink()
        val partial = CarPlayUpdateDataReceiver(partialSink)
        partial.receive(codec.encode(header(2)))
        partial.receive(codec.encode(chunk(0, byteArrayOf(1))))
        assertEquals(1, partial.cancel().ack.accepted)
        assertTrue(partialSink.aborted)
        assertThrows(IllegalStateException::class.java) {
            partial.receive(codec.encode(chunk(1, byteArrayOf(2), sha256(byteArrayOf(1, 2)))))
        }

        val pendingSink = MemoryQuarantineSink()
        val pending = CarPlayUpdateDataReceiver(pendingSink)
        pending.receive(codec.encode(header(1)))
        val receipt = (pending.receive(codec.encode(chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)))))
            as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit).receipt
        pending.close()
        assertTrue(pendingSink.aborted)
        assertThrows(IllegalStateException::class.java) {
            pending.confirmDurableQuarantineCommit(receipt)
        }
    }

    @Test
    fun sinkFailuresRejectAndReportFailedCleanupWithoutLeakingExceptionText() {
        val sink = MemoryQuarantineSink().apply {
            failAppend = true
            failAbort = true
        }
        val receiver = CarPlayUpdateDataReceiver(sink)
        receiver.receive(codec.encode(header(1)))
        val rejection = receiver.receive(codec.encode(chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)))))
            as CarPlayUpdateDataReceiveResult.Rejected
        assertEquals(1, rejection.ack.accepted)
        assertTrue(rejection.cleanupFailed)
        assertFalse(rejection.reason.contains("secret"))
        sink.failAbort = false
        receiver.close()
        assertTrue(sink.aborted)
    }

    @Test
    fun failedDurableCommitReturnsNonzeroAckAndCloseRemainsTerminalWhenAbortThrows() {
        val sink = MemoryQuarantineSink()
        val receiver = CarPlayUpdateDataReceiver(sink)
        receiver.receive(codec.encode(header(1)))
        val receipt = (receiver.receive(
            codec.encode(chunk(0, byteArrayOf(1), sha256(byteArrayOf(1)))),
        ) as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit).receipt
        assertEquals(1, receiver.confirmDurableQuarantineCommit(receipt).accepted)
        assertTrue(sink.aborted)
        assertFalse(sink.durablyCommitted)

        val throwingSink = MemoryQuarantineSink().apply { failAbort = true }
        val another = CarPlayUpdateDataReceiver(throwingSink)
        another.receive(codec.encode(header(1)))
        val anotherReceipt = (another.receive(
            codec.encode(chunk(0, byteArrayOf(2), sha256(byteArrayOf(2)))),
        ) as CarPlayUpdateDataReceiveResult.AwaitingDurableCommit).receipt
        assertThrows(IOException::class.java) { another.close() }
        assertThrows(IllegalStateException::class.java) {
            another.confirmDurableQuarantineCommit(anotherReceipt)
        }
        throwingSink.failAbort = false
        another.close()
        assertTrue(throwingSink.aborted)
    }

    private fun header(count: Int) = CarPlayUpdateDataMessage.Header(
        SESSION_ID,
        version = 27,
        chunkCount = count,
        certificate = byteArrayOf(8, 9),
    )

    private fun chunk(
        index: Int,
        data: ByteArray,
        checksum: String? = null,
        sessionId: String = SESSION_ID,
    ) = CarPlayUpdateDataMessage.Chunk(sessionId, index, data, checksum)

    private fun fakeReceipt() = UntrustedCarPlayUpdateDataReceipt(
        SESSION_ID, 27, 1, 1, sha256(byteArrayOf(1)), null, null, null,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class MemoryQuarantineSink : CarPlayUpdateDataQuarantineSink {
        val bytes = ByteArrayOutputStream()
        var aborted = false
        var durablyCommitted = false
        var allowCommit = false
        var failAppend = false
        var failAbort = false

        override fun begin(header: CarPlayUpdateDataMessage.Header) {
            assertEquals(SESSION_ID, header.sessionId)
        }

        override fun append(bytes: ByteArray) {
            if (failAppend) throw IOException("secret append failure")
            this.bytes.write(bytes)
        }

        override fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean {
            if (!allowCommit) return false
            assertEquals(receipt.archiveBytes.toInt(), bytes.size())
            durablyCommitted = true
            return true
        }

        override fun abort() {
            if (failAbort) throw IOException("secret abort failure")
            aborted = true
            bytes.reset()
        }
    }

    companion object {
        private const val SESSION_ID = "C0DE1234-5678-4ABC-9DEF-0123456789AB"
        private const val OTHER_SESSION_ID = "C0DE1234-5678-4ABC-9DEF-0123456789AC"
    }
}
