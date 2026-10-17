package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class VideoFrameIdentityLedgerTest {
    @Test
    fun exactOutputPtsCanBeConsumedOnlyOnceForTheSameHandleAndSurfaceGeneration() {
        val handle = Any()
        val surface = Any()
        val ledger = VideoFrameIdentityLedger<Any, Any>(handle)
        val ntp = Long.MIN_VALUE

        assertTrue(ledger.recordQueued(handle, 7, ntp, 101, surface, 3))
        assertNull(ledger.matchReleased(Any(), 101, surface, 3, 101_000))
        assertNull(ledger.matchReleased(handle, 102, surface, 3, 102_000))
        val match = ledger.matchReleased(handle, 101, surface, 3, 101_000)
        assertEquals(7L, match?.frameCounter)
        assertEquals(ntp, match?.presentationTimeNtp64Bits)
        assertEquals(101L, match?.inputPresentationTimeUs)
        assertNull(ledger.matchReleased(handle, 101, surface, 3, 101_000))
        assertFalse(ledger.recordQueued(handle, 7, ntp, 103, surface, 3))
        assertFalse(ledger.recordQueued(Any(), 8, ntp, 104, surface, 3))
    }

    @Test
    fun staleSurfaceAndGenerationConsumeEntryWithoutProducingEvidence() {
        val handle = Any()
        val surface = Any()
        val replacement = Any()
        val ledger = VideoFrameIdentityLedger<Any, Any>(handle)

        assertTrue(ledger.recordQueued(handle, 1, 10, 100, surface, 3))
        assertNull(ledger.matchReleased(handle, 100, replacement, 3, 100_000))
        assertNull(ledger.matchReleased(handle, 100, surface, 3, 100_000))
        assertTrue(ledger.recordQueued(handle, 2, 20, 200, surface, 3))
        assertNull(ledger.matchReleased(handle, 200, surface, 4, 200_000))
        assertNull(ledger.matchReleased(handle, 200, surface, 3, 200_000))
        assertTrue(ledger.recordQueued(handle, 3, 30, 300, replacement, 4))
        assertEquals(3L, ledger.matchReleased(handle, 300, replacement, 4, 300_000)?.frameCounter)
    }

    @Test
    fun rejectsReorderedOrDuplicateFramesAndBoundsUnmatchedInputs() {
        val handle = Any()
        val surface = Any()
        val ledger = VideoFrameIdentityLedger<Any, Any>(handle, capacity = 2)

        assertTrue(ledger.recordQueued(handle, 8, 8, 80, surface, 1))
        assertFalse(ledger.recordQueued(handle, 7, 7, 70, surface, 1))
        assertTrue(ledger.recordQueued(handle, 9, 9, 90, surface, 1))
        assertTrue(ledger.recordQueued(handle, 10, 10, 100, surface, 1))
        assertEquals(2, ledger.pendingCount)
        assertNull(ledger.matchReleased(handle, 80, surface, 1, 80_000))
        assertEquals(10L, ledger.matchReleased(handle, 100, surface, 1, 100_000)?.frameCounter)
        assertNull(ledger.matchReleased(handle, 90, surface, 1, 90_000))
    }

    @Test
    fun decoderResetDropsPendingWithoutAllowingCounterReplay() {
        val handle = Any()
        val surface = Any()
        val ledger = VideoFrameIdentityLedger<Any, Any>(handle)

        assertTrue(ledger.recordQueued(handle, 4, 4, 40, surface, 1))
        ledger.discardPending()
        assertNull(ledger.matchReleased(handle, 40, surface, 1, 40_000))
        assertFalse(ledger.recordQueued(handle, 4, 4, 41, surface, 2))
        assertTrue(ledger.recordQueued(handle, 5, 5, 50, surface, 2))
        assertEquals(5L, ledger.matchReleased(handle, 50, surface, 2, 50_000)?.frameCounter)
    }

    @Test
    fun collidingOrReorderedSurfaceTextureTimestampCannotGetASecondReceipt() {
        val handle = Any()
        val surface = Any()
        val ledger = VideoFrameIdentityLedger<Any, Any>(handle)

        assertTrue(ledger.recordQueued(handle, 1, 1, 100, surface, 1))
        assertEquals(1L, ledger.matchReleased(handle, 100, surface, 1, 500_000)?.frameCounter)
        assertTrue(ledger.recordQueued(handle, 2, 2, 200, surface, 1))
        assertNull(ledger.matchReleased(handle, 200, surface, 1, 500_000))
        assertTrue(ledger.recordQueued(handle, 3, 3, 300, surface, 1))
        assertNull(ledger.matchReleased(handle, 300, surface, 1, 499_999))
        assertTrue(ledger.recordQueued(handle, 4, 4, 400, surface, 1))
        assertEquals(4L, ledger.matchReleased(handle, 400, surface, 1, 500_001)?.frameCounter)

        // A new physical attachment starts a new timestamp domain, not a replay of the old one.
        assertTrue(ledger.recordQueued(handle, 5, 5, 500, surface, 2))
        assertEquals(5L, ledger.matchReleased(handle, 500, surface, 2, 10)?.frameCounter)
    }

    @Test
    fun timelineIdSurvivesExactCodecMatchAndReleaseReceiptWithoutClockConversion() {
        val handle = AirPlayVideoStreamHandle(
            AirPlayStreamIdentity(111, "cluster", null),
            AirPlayVideoSessionEpoch(),
        )
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe
        val surface = unsafe.allocateInstance(Surface::class.java) as Surface
        val ledger = VideoFrameIdentityLedger<AirPlayVideoStreamHandle, Surface>(handle)
        val rawTimelineIdBits = Long.MIN_VALUE

        assertTrue(ledger.recordQueued(
            owner = handle,
            frameCounter = 123L,
            presentationTimeNtp64Bits = 0x1234L,
            inputPresentationTimeUs = 456L,
            surface = surface,
            surfaceLeaseGeneration = 7L,
            timelineIdRawBits = rawTimelineIdBits,
        ))
        val matched = requireNotNull(ledger.matchReleased(handle, 456L, surface, 7L, 456_000L))
        val receipt = AndroidVideoFrameReleaseReceipt.issue(
            handle = handle,
            surface = surface,
            surfaceLeaseGeneration = 7L,
            matched = matched,
            codecOutputPresentationTimeUs = 456L,
            releaseCalledAtMonotonicNanos = 900L,
            releaseCompletedAtMonotonicNanos = 901L,
            releaseTargetMonotonicNanos = null,
            expectedSurfaceTextureTimestampNanos = 456_000L,
        )

        assertEquals(123L, receipt.authenticatedFrameCounter)
        assertEquals(0x1234L, receipt.presentationTimeNtp64Bits)
        assertEquals(rawTimelineIdBits, matched.timelineIdRawBits)
        assertEquals(rawTimelineIdBits, receipt.timelineIdRawBits)
        @Suppress("DEPRECATION")
        assertEquals(rawTimelineIdBits, receipt.auxiliaryNetworkTimeRawBits)
        assertEquals(456_000L, receipt.expectedSurfaceTextureTimestampNanos)
    }
}
