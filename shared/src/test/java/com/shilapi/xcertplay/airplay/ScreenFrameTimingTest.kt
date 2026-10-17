package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.MediaTap
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFrameTimingTest {
    @Test
    fun parsesLittleEndianPresentationTimeAtHeaderOffsetEight() {
        val header = headerWithPresentationBytes(
            0xf0, 0xde, 0xbc, 0x9a, 0x78, 0x56, 0x34, 0x12,
        )

        val timing = parseScreenFrameTiming(header, arrivalMonotonicNanos = 42L)

        assertEquals(0x123456789abcdef0L, timing.presentationTimeNtp64Bits)
        assertEquals(42L, timing.arrivalMonotonicNanos)
        assertNull(timing.authenticatedFrameCounter)
    }

    @Test
    fun authenticatedFrameCounterTravelsWithRawHeaderBitsWithoutNtpConversion() {
        val header = headerWithPresentationBytes(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80,
        )

        val timing = parseScreenFrameTiming(
            header,
            arrivalMonotonicNanos = 987L,
            authenticatedFrameCounter = 42L,
        )

        assertEquals(Long.MIN_VALUE, timing.presentationTimeNtp64Bits)
        assertEquals(987L, timing.arrivalMonotonicNanos)
        assertEquals(42L, timing.authenticatedFrameCounter)
        val modifiedRawView = timing.copy(presentationTimeNtp64Bits = 0L)
        assertEquals(Long.MIN_VALUE,
            modifiedRawView.authenticatedFrameIdentity?.presentationTimeNtp64Bits)
    }

    @Test
    fun preservesUnsignedTimelineIdSeparatelyFromPresentationTime() {
        val header = headerWithPresentationBytes(
            0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23, 0x01,
        )
        // iOS 27 stores timelineID as a native u64 at +0x28; the high bit belongs to the ID,
        // not a signed timestamp, and cannot alter the +8 APSNetworkTime presentation field.
        val timelineIdBytes = byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x80.toByte())
        timelineIdBytes.copyInto(header, destinationOffset = 0x28)

        val timing = parseScreenFrameTiming(header, 44L, authenticatedFrameCounter = 9L)

        assertEquals(0x0123456789abcdefL, timing.presentationTimeNtp64Bits)
        assertEquals(0x8002030405060708UL.toLong(), timing.timelineIdRawBits)
        assertEquals(9L, timing.authenticatedFrameCounter)
        assertEquals(timing.timelineIdRawBits,
            timing.authenticatedFrameIdentity?.timelineIdRawBits)
        assertEquals(0x8002030405060708UL.toLong(),
            timing.copy(timelineIdRawBits = 0L)
                .authenticatedFrameIdentity?.timelineIdRawBits)
        @Suppress("DEPRECATION")
        assertEquals(timing.timelineIdRawBits, timing.auxiliaryNetworkTimeRawBits)
    }

    @Test
    fun preservesOneAndAHalfSecondsAsRawNtpBits() {
        val header = headerWithPresentationBytes(
            0x00, 0x00, 0x00, 0x80, 0x01, 0x00, 0x00, 0x00,
        )

        val timing = parseScreenFrameTiming(header, arrivalMonotonicNanos = 7L)

        assertEquals(0x0000000180000000L, timing.presentationTimeNtp64Bits)
    }

    @Test
    fun preservesHighBitWithoutSignedInterpretation() {
        val header = headerWithPresentationBytes(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80,
        )

        val timing = parseScreenFrameTiming(header, arrivalMonotonicNanos = 9L)

        assertEquals(Long.MIN_VALUE, timing.presentationTimeNtp64Bits)
    }

    @Test
    fun wrongLengthHeaderCannotYieldPartialTimingOrAuthenticatedIdentity() {
        for (length in listOf(15, 48, 127, 129)) {
            val header = ByteArray(length).also {
                it[8] = 0x7f
                if (length > 0x28) it[0x28] = 0x55
            }
            val timing = parseScreenFrameTiming(header, arrivalMonotonicNanos = 11L)

            assertNull(timing.presentationTimeNtp64Bits)
            assertNull(timing.timelineIdRawBits)
            assertNull(timing.authenticatedFrameIdentity)
            assertEquals(11L, timing.arrivalMonotonicNanos)
            assertThrows(IllegalArgumentException::class.java) {
                parseScreenFrameTiming(header, 11L, authenticatedFrameCounter = 0L)
            }
        }
    }

    @Test
    fun screenStreamSamplesInjectedArrivalClockOncePerFrameTiming() {
        var samples = 0
        val loopback = InetAddress.getLoopbackAddress()
        val stream = ScreenStream(
            key = ByteArray(32),
            bindAddress = loopback,
            expectedPeerAddress = loopback,
            monotonicNanos = {
                samples++
                -123_456_789L
            },
        )

        val timing = stream.timingForFrame(ByteArray(128))

        assertEquals(1, samples)
        assertEquals(-123_456_789L, timing.arrivalMonotonicNanos)
    }

    @Test
    fun timedOverloadsDelegateToEachLegacyCallbackExactlyOnce() {
        var listenerCalls = 0
        var sinkCalls = 0
        var tapCalls = 0
        var tapArrivalUs = -1L
        val timing = ScreenFrameTiming(0x180000000L, 9_876_543L)
        val listener = object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray) {
                listenerCalls++
            }
        }
        val sink = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                sinkCalls++
            }
        }
        val tap = object : MediaTap {
            override fun forwardsVideo(type: Int) = true

            override fun onVideoFrame(type: Int, accessUnit: ByteArray, arrivalUs: Long) {
                tapCalls++
                tapArrivalUs = arrivalUs
            }
        }

        listener.onFrame(byteArrayOf(1), timing)
        sink.onVideoFrame(110, byteArrayOf(2), timing)
        tap.onVideoFrame(111, byteArrayOf(3), timing)

        assertEquals(1, listenerCalls)
        assertEquals(1, sinkCalls)
        assertEquals(1, tapCalls)
        assertEquals(9_876L, tapArrivalUs)
    }

    @Test
    fun targetAwareSinkDefaultsToTheRawTimingCallback() {
        var received: ScreenFrameTiming? = null
        val sink = object : MediaSink {
            override fun onVideoFrame(
                type: Int,
                naluBytes: ByteArray,
                timing: ScreenFrameTiming,
            ) {
                received = timing
            }
        }
        val timing = ScreenFrameTiming(0x1234L, 99L)

        sink.onVideoFrame(111, byteArrayOf(1), timing, 123_456L)

        assertEquals(timing, received)
    }

    @Test
    fun androidMediaSinkForwardsIndependentTimingToMediaTapForBothScreens() {
        data class Received(
            val timing: ScreenFrameTiming,
            val accessUnit: ByteArray,
        )

        val received = linkedMapOf<Int, Received>()
        val tap = object : MediaTap {
            override fun forwardsVideo(type: Int) = true

            override fun onVideoFrame(
                type: Int,
                accessUnit: ByteArray,
                timing: ScreenFrameTiming,
            ) {
                received[type] = Received(timing, accessUnit)
            }
        }
        val mainTiming = ScreenFrameTiming(0x123456789abcdef0L, 10_000L)
        val alternateTiming = ScreenFrameTiming(Long.MIN_VALUE, 20_000L)
        val mainFrame = byteArrayOf(0, 0, 0, 1, 0x65)
        val alternateFrame = byteArrayOf(0, 0, 0, 1, 0x26)

        assertTrue(AndroidMediaSink.forwardVideoFrameToMediaTap(tap, 110, mainFrame, mainTiming))
        assertTrue(AndroidMediaSink.forwardVideoFrameToMediaTap(tap, 111, alternateFrame, alternateTiming))

        assertEquals(mainTiming, received[110]?.timing)
        assertEquals(alternateTiming, received[111]?.timing)
        assertArrayEquals(mainFrame, received[110]?.accessUnit)
        assertArrayEquals(alternateFrame, received[111]?.accessUnit)
        assertTrue(received.keys.containsAll(listOf(110, 111)))
    }

    private fun headerWithPresentationBytes(vararg bytes: Int): ByteArray =
        ByteArray(128).also { header ->
            require(bytes.size == 8)
            bytes.forEachIndexed { index, value -> header[8 + index] = value.toByte() }
        }
}
