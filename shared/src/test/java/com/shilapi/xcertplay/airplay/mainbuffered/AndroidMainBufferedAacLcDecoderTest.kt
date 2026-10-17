package com.shilapi.xcertplay.airplay.mainbuffered

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidMainBufferedAacLcDecoderTest {
    @Test
    fun rawAuIsSubmittedUnmodifiedAndOnlyDecodedPcmCreatesOutputHead() {
        val output = FakeOutput()
        val codec = FakeCodec(44_100)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { rate ->
            assertEquals(44_100, rate)
            output
        })
        try {
            assertTrue(decoder.configure(config(44_100, epoch = 1)))
            assertTrue(decoder.isReady)
            assertNull(decoder.firstQueuedMediaTimeValue)
            val raw = byteArrayOf(0x21, 0x7a, 0x03, 0x42)
            val receipt = decoder.decode(au(1, 44_100L, 44_100, 1, raw))
            assertTrue(receipt.accepted)
            assertEquals(1_024, receipt.queuedPcmFrames)
            assertEquals(44_100L, receipt.firstQueuedMediaTimeValue)
            assertArrayEquals(raw, codec.queuedInput)
            assertEquals(1_000_000L, codec.queuedPtsUs)
            assertEquals(1, output.offered.size)
            assertEquals(44_100L, output.offered.single().first)
            assertEquals(4_096, output.offered.single().second.size)
            assertEquals(0, output.nativeStarts)
        } finally {
            decoder.close()
        }
        assertEquals(1, codec.closes)
        assertEquals(1, output.closes)
    }

    @Test
    fun codecInputAloneDoesNotClaimPcmOrPlaybackReadiness() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000, emitPcm = false)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        try {
            assertTrue(decoder.configure(config(48_000, epoch = 1)))
            val receipt = decoder.decode(au(1, 4_800L, 48_000, 1))
            assertTrue(receipt.accepted)
            assertEquals(0, receipt.queuedPcmFrames)
            assertNull(receipt.firstQueuedMediaTimeValue)
            assertNull(decoder.firstQueuedMediaTimeValue)
            assertEquals(0, output.nativeStarts)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun wrongCodecOutputFormatFailsClosedBeforeAnyPcmIsQueued() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000, reportedRate = 44_100)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        assertTrue(decoder.configure(config(48_000, epoch = 1)))
        expectFailure { decoder.decode(au(1, 48_000L, 48_000, 1)) }
        assertFalse(decoder.isReady)
        assertEquals(0, output.offered.size)
        assertEquals(1, output.closes)
        assertEquals(1, codec.closes)
        decoder.close()
    }

    @Test
    fun overProducedPcmFailsBeforeOfferingAnyUnauthenticatedFrames() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000, decodedFrames = 2_048)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        assertTrue(decoder.configure(config(48_000, epoch = 1)))
        expectFailure { decoder.decode(au(1, 48_000L, 48_000, 1)) }
        assertEquals(0, output.offered.size)
        assertEquals(1, output.closes)
        decoder.close()
    }

    @Test
    fun decoderTimestampMismatchCannotBorrowAuthenticatedMediaTime() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000, timestampOffsetUs = 25_000L)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        assertTrue(decoder.configure(config(48_000, epoch = 1)))
        expectFailure { decoder.decode(au(1, 48_000L, 48_000, 1)) }
        assertEquals(0, output.offered.size)
        assertEquals(1, output.closes)
        decoder.close()
    }

    @Test
    fun flushThenReconfigureRetiresOldEpochAndRejectsItsAu() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        try {
            assertTrue(decoder.configure(config(48_000, epoch = 1)))
            assertTrue(decoder.decode(au(7, 48_000L, 48_000, 1)).accepted)
            decoder.flush()
            assertNull(decoder.firstQueuedMediaTimeValue)
            assertEquals(1L, decoder.playbackEpoch)
            assertTrue(decoder.configure(config(48_000, epoch = 2)))
            assertEquals(2L, decoder.playbackEpoch)
            expectFailure { decoder.decode(au(8, 49_024L, 48_000, 1)) }
            assertFalse(decoder.isReady)
            assertEquals(1, output.closes)
        } finally {
            decoder.close()
        }
    }

    @Test
    fun unavailableInputHasBoundedFailureAndRetiresNativeOutput() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000, inputAvailable = false)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        assertTrue(decoder.configure(config(48_000, epoch = 1)))
        expectFailure { decoder.decode(au(1, 0L, 48_000, 1)) }
        assertEquals(5_000L, codec.lastInputTimeoutUs)
        assertEquals(1, output.closes)
        decoder.close()
    }

    @Test
    fun flushWinningNativeStartRaceMakesOldClockReceiptInert() {
        val output = FakeOutput()
        val codec = FakeCodec(48_000)
        val decoder = AndroidMainBufferedAacLcDecoder({ codec }, { output })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        output.startEntered = entered
        output.allowStart = release
        try {
            assertTrue(decoder.configure(config(48_000, epoch = 1)))
            assertTrue(decoder.decode(au(1, 48_000L, 48_000, 1)).accepted)
            val network = MainBufferedNetworkTime(1, 2L, 3L, 0L)
            val anchor = MainBufferedPlaybackAnchor(
                MainBufferedTimelinePoint(7L, 48_000L, 48_000L), network,
                MainBufferedTimelinePoint(7L, 48_000L, 48_000L),
            )
            val proof = checkNotNull(MainBufferedPcm16VerifiedAnchor.verify(
                anchor, output.outputInstance, output.playbackEpoch,
                MainBufferedPcm16AudioClockMapping { instance, _ ->
                    MainBufferedPcm16CalibratedPlayCall(instance, network,
                        100L, 5_000_100L, 5_000_000L)
                },
                100L,
            ))
            val started = AtomicReference<Boolean>()
            val startThread = Thread { started.set(decoder.startPlayback(proof)) }
            startThread.start()
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            decoder.flush()
            release.countDown()
            startThread.join(2_000L)
            assertFalse(startThread.isAlive)
            assertEquals(false, started.get())
            assertEquals(0, output.nativeStarts)
            assertEquals(1L, output.playbackEpoch)
        } finally {
            release.countDown()
            decoder.close()
        }
    }

    private fun config(rate: Int, epoch: Long) = AacLcDecoderConfig(
        rate, 2, 1_024, epoch,
        if (rate == 44_100) byteArrayOf(0x12, 0x10)
        else byteArrayOf(0x11, 0x90.toByte()),
    )

    private fun au(
        sequence: Int,
        mediaTime: Long,
        rate: Int,
        epoch: Long,
        payload: ByteArray = byteArrayOf(0x21, 0x22, 0x23),
    ) = AacLcAccessUnit(sequence, mediaTime, rate.toLong(), epoch, payload)

    private fun expectFailure(action: () -> Unit) {
        try {
            action()
            fail("expected AAC-LC fail-closed rejection")
        } catch (_: IllegalStateException) {
            // The decoder must retire the output, rather than claiming a playable AU.
        } catch (_: java.io.IOException) {
            // Bounded codec/native failures may use IOException.
        }
    }

    private class FakeCodec(
        private val rate: Int,
        private val reportedRate: Int = rate,
        private val decodedFrames: Int = 1_024,
        private val emitPcm: Boolean = true,
        private val inputAvailable: Boolean = true,
        private val timestampOffsetUs: Long = 0L,
    ) : MainBufferedAacLcCodecDriver {
        private val input = ByteBuffer.allocate(64 * 1024)
        private val events = ArrayDeque<MainBufferedAacLcCodecEvent>()
        private var outputBytes = ByteBuffer.allocate(0)
        var queuedInput = byteArrayOf()
        var queuedPtsUs = -1L
        var lastInputTimeoutUs = -1L
        var closes = 0

        override fun dequeueInputBuffer(timeoutUs: Long): Int {
            lastInputTimeoutUs = timeoutUs
            return if (inputAvailable) 0 else -1
        }

        override fun inputBuffer(index: Int): ByteBuffer = input

        override fun queueInputBuffer(index: Int, size: Int, presentationTimeUs: Long) {
            queuedInput = input.array().copyOf(size)
            queuedPtsUs = presentationTimeUs
            if (emitPcm) {
                outputBytes = ByteBuffer.wrap(ByteArray(decodedFrames * 4) { (it and 0x7f).toByte() })
                events.addLast(MainBufferedAacLcCodecEvent.Format(reportedRate, 2, 2))
                events.addLast(MainBufferedAacLcCodecEvent.Buffer(
                    1, 0, outputBytes.remaining(), presentationTimeUs + timestampOffsetUs, 0,
                ))
            }
        }

        override fun dequeueOutputBuffer(timeoutUs: Long): MainBufferedAacLcCodecEvent =
            if (events.isEmpty()) MainBufferedAacLcCodecEvent.TryAgain else events.removeFirst()

        override fun outputBuffer(index: Int): ByteBuffer = outputBytes
        override fun releaseOutputBuffer(index: Int) = Unit
        override fun flush() { events.clear() }
        override fun close() { closes++ }
    }

    private class FakeOutput : MainBufferedPcm16Output {
        val offered = mutableListOf<Pair<Long, ByteArray>>()
        var closes = 0
        var nativeStarts = 0
        var startEntered: CountDownLatch? = null
        var allowStart: CountDownLatch? = null
        private var epoch = 0L
        override val isReady: Boolean get() = closes == 0
        override val outputInstance: Any = Any()
        override val playbackEpoch: Long get() = epoch
        override val firstQueuedMediaTimeValue: Long? get() = offered.firstOrNull()?.first
        override fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean {
            if (!isReady) return false
            offered.add(mediaTimeValue to pcm.copyOf())
            return true
        }
        override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean {
            startEntered?.countDown()
            allowStart?.await(2L, TimeUnit.SECONDS)
            if (!isReady || anchor.outputInstance !== outputInstance ||
                anchor.outputEpoch != epoch ||
                anchor.firstMediaTimeValue != firstQueuedMediaTimeValue
            ) return false
            nativeStarts++
            return true
        }
        override fun pausePlayback() = flush()
        override fun flush() {
            offered.clear()
            epoch++
        }
        override fun close() { closes++ }
    }
}
