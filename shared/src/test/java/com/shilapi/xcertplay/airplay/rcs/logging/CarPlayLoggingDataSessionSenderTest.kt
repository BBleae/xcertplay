package com.shilapi.xcertplay.airplay.rcs.logging

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayLoggingDataSessionSenderTest {
    private val sessionId = "109a5129-f9f0-468f-8e4f-6eb9972ac92c"
    private val limits = CarPlayLoggingDataLimits(
        maxRcsBodyBytes = 512,
        maxChunkBytes = 4,
        maxStatisticsDataBytes = 128,
        maxArchiveBytes = 16,
        maxChunkCount = 4,
    )
    private val codec = CarPlayLoggingDataCodec(limits)

    @Test
    fun sendsOneBoundedArchiveThenAcceptsOnlyTerminalAck() {
        val source = CountingSource(9, ByteArray(9) { it.toByte() })
        val sent = mutableListOf<RcsMessage>()
        val sender = CarPlayLoggingDataSessionSender(sessionId, source, sent::add, codec)

        assertEquals(CarPlayLoggingArchiveState.IDLE, sender.archiveState)
        assertEquals(
            CarPlayLoggingDataSessionEvent.ArchiveSent,
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive)),
        )
        assertEquals(1, source.opens)
        assertEquals(CarPlayLoggingArchiveState.AWAITING_ACK, sender.archiveState)
        assertEquals(4, sent.size)
        val header = codec.decodeToPhone(sent[0]) as CarPlayLoggingDataMessage.ArchiveHeader
        assertEquals(sessionId, header.sessionId)
        assertEquals(3, header.chunkCount)
        val chunks = sent.drop(1).map {
            codec.decodeToPhone(it) as CarPlayLoggingDataMessage.ArchiveChunk
        }
        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.sessionId == sessionId })
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), chunks[0].data)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), chunks[1].data)
        assertArrayEquals(byteArrayOf(8), chunks[2].data)

        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(1, source.opens)
        assertEquals(
            CarPlayLoggingDataSessionEvent.ArchiveCompleted,
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.Ack(0))),
        )
        assertEquals(CarPlayLoggingArchiveState.COMPLETED, sender.archiveState)
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.Ack(0)))
        }
        sender.close()
        assertEquals(CarPlayLoggingArchiveState.CLOSED, sender.archiveState)
    }

    @Test
    fun rejectsInvalidOrOppositeDirectionBeforeOpeningSource() {
        val source = CountingSource(1, byteArrayOf(7))
        val sent = mutableListOf<RcsMessage>()
        val sender = CarPlayLoggingDataSessionSender(sessionId, source, sent::add, codec)
        val malformed = RcsMessage.comm(OpackCodec.encode(
            mapOf("messageType" to 3, "request" to 1, "surprise" to true),
        ))
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(malformed)
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(codec.encodeToPhone(
                CarPlayLoggingDataMessage.ArchiveHeader(sessionId, 1),
            ))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.Ack(0)))
        }
        assertEquals(CarPlayLoggingArchiveState.IDLE, sender.archiveState)
        assertEquals(0, source.opens)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun phoneSaveFailureIsTerminalAndNeverRetriesArchive() {
        val source = CountingSource(2, byteArrayOf(1, 2))
        val sender = CarPlayLoggingDataSessionSender(sessionId, source, {}, codec)
        sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        assertEquals(
            CarPlayLoggingDataSessionEvent.ArchiveSaveFailed,
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.Ack(5))),
        )
        assertEquals(CarPlayLoggingArchiveState.SAVE_FAILED, sender.archiveState)
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(1, source.opens)
    }

    @Test
    fun statisticsRequestPreservesOpaqueIntervalAndRequiresExplicitPublisher() {
        val source = CountingSource(1, byteArrayOf(1))
        val sent = mutableListOf<RcsMessage>()
        val sender = CarPlayLoggingDataSessionSender(sessionId, source, sent::add, codec)
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.publishStatistics(mapOf("opaque" to 1L))
        }
        assertEquals(
            CarPlayLoggingDataSessionEvent.StatisticsIntervalRequested(0x1_0000_0000uL),
            sender.acceptFromPhoneBlocking(phone(
                CarPlayLoggingDataMessage.RequestStatistics(0x1_0000_0000uL),
            )),
        )
        assertEquals(0x1_0000_0000uL, sender.statisticsInterval)
        sender.publishStatistics(mapOf("opaque" to listOf(1L, 2L)))
        val data = (codec.decodeToPhone(sent.single()) as CarPlayLoggingDataMessage.Statistics).data
        assertEquals(listOf(1L, 2L), data["opaque"])
        assertEquals(0, source.opens)
    }

    @Test
    fun sessionPolicyRejectsLargeValidIntervalBeforeStateMutation() {
        val source = CountingSource(1, byteArrayOf(1))
        val sent = mutableListOf<RcsMessage>()
        val sender = CarPlayLoggingDataSessionSender(
            sessionId,
            source,
            sent::add,
            codec,
            CarPlayLoggingDataSessionPolicy(maxStatisticsInterval = 100uL),
        )
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            sender.acceptFromPhoneBlocking(phone(
                CarPlayLoggingDataMessage.RequestStatistics(ULong.MAX_VALUE),
            ))
        }
        assertEquals(null, sender.statisticsInterval)
        assertTrue(sent.isEmpty())
        assertEquals(0, source.opens)
        assertEquals(
            CarPlayLoggingDataSessionEvent.StatisticsIntervalRequested(100uL),
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestStatistics(100uL))),
        )
    }

    @Test
    fun sizeAndShortSourceFailuresCannotBecomeSuccessfulTransfers() {
        val tooLarge = CountingSource(17, byteArrayOf())
        val largeSender = CarPlayLoggingDataSessionSender(sessionId, tooLarge, {}, codec)
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            largeSender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(0, tooLarge.opens)
        assertEquals(CarPlayLoggingArchiveState.FAILED, largeSender.archiveState)

        val shortSource = CountingSource(3, byteArrayOf(1, 2))
        val shortSender = CarPlayLoggingDataSessionSender(sessionId, shortSource, {}, codec)
        assertThrows(IOException::class.java) {
            shortSender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(CarPlayLoggingArchiveState.FAILED, shortSender.archiveState)
        assertThrows(IOException::class.java) {
            shortSender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.Ack(0)))
        }

    }

    @Test
    fun declaredSizeIsConsumedWithoutPotentiallyBlockingEofProbe() {
        val source = object : CarPlayLoggingArchiveSource {
            override val sizeBytes = 2L
            override fun openStream(): InputStream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun read(): Int = throw AssertionError("must not probe EOF")
            }
        }
        val sent = mutableListOf<RcsMessage>()
        val sender = CarPlayLoggingDataSessionSender(sessionId, source, sent::add, codec)
        sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        assertEquals(CarPlayLoggingArchiveState.AWAITING_ACK, sender.archiveState)
        assertArrayEquals(
            byteArrayOf(1, 2),
            (codec.decodeToPhone(sent[1]) as CarPlayLoggingDataMessage.ArchiveChunk).data,
        )
    }

    @Test
    fun sendFailureAndCloseStopFurtherChunksAndInvalidateAck() {
        val failedMessages = mutableListOf<RcsMessage>()
        val failedSource = CountingSource(9, ByteArray(9))
        val failedSender = CarPlayLoggingDataSessionSender(
            sessionId,
            failedSource,
            { message ->
                failedMessages += message
                if (failedMessages.size == 2) throw IOException("send failed")
            },
            codec,
        )
        assertThrows(IOException::class.java) {
            failedSender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(2, failedMessages.size)
        assertEquals(CarPlayLoggingArchiveState.FAILED, failedSender.archiveState)

        val sent = mutableListOf<RcsMessage>()
        val source = CountingSource(9, ByteArray(9))
        lateinit var sender: CarPlayLoggingDataSessionSender
        sender = CarPlayLoggingDataSessionSender(
            sessionId,
            source,
            { message ->
                sent += message
                sender.close()
            },
            codec,
        )
        assertThrows(IOException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(1, sent.size)
        assertEquals(CarPlayLoggingArchiveState.CLOSED, sender.archiveState)
        assertTrue(source.lastStream!!.closed)
        assertThrows(IOException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.Ack(0)))
        }
    }

    @Test
    fun emptyArchiveHasZeroChunksAndStillWaitsForAck() {
        val sent = mutableListOf<RcsMessage>()
        val sender = CarPlayLoggingDataSessionSender(
            sessionId,
            CarPlayLoggingArchiveBytes(byteArrayOf()),
            sent::add,
            codec,
        )
        sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        assertEquals(1, sent.size)
        assertEquals(0, (codec.decodeToPhone(sent.single()) as
            CarPlayLoggingDataMessage.ArchiveHeader).chunkCount)
        assertEquals(CarPlayLoggingArchiveState.AWAITING_ACK, sender.archiveState)
    }

    @Test
    fun failedSourceCloseIsRetainedForRetry() {
        val stream = object : ByteArrayInputStream(byteArrayOf(4)) {
            var closeAttempts = 0

            override fun close() {
                closeAttempts++
                if (closeAttempts == 1) throw IOException("transient close failure")
                super.close()
            }
        }
        val source = object : CarPlayLoggingArchiveSource {
            override val sizeBytes = 1L
            override fun openStream(): InputStream = stream
        }
        val sender = CarPlayLoggingDataSessionSender(sessionId, source, {}, codec)
        assertThrows(IOException::class.java) {
            sender.acceptFromPhoneBlocking(phone(CarPlayLoggingDataMessage.RequestArchive))
        }
        assertEquals(CarPlayLoggingArchiveState.FAILED, sender.archiveState)
        sender.close()
        assertEquals(2, stream.closeAttempts)
        assertEquals(CarPlayLoggingArchiveState.CLOSED, sender.archiveState)
    }

    private fun phone(message: CarPlayLoggingDataMessage.FromPhone): RcsMessage =
        codec.encodeFromPhone(message)

    private class CountingSource(
        override val sizeBytes: Long,
        private val contents: ByteArray,
    ) : CarPlayLoggingArchiveSource {
        var opens = 0
        var lastStream: CloseTrackingStream? = null

        override fun openStream(): InputStream = CloseTrackingStream(contents).also {
            opens++
            lastStream = it
        }
    }

    private class CloseTrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
