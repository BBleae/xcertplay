package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenFrameRenderSchedulerTest {
    @Test
    fun acceptsBoundedTargetsAndKeepsTimedReleasesStrictlyOrdered() {
        val scheduler = ScreenFrameRenderScheduler()
        val now = 1_000_000_000L

        assertEquals(now + 50_000_000L, scheduler.releaseTimestampOrNull(now + 50_000_000L, now))
        assertEquals(
            now + 50_000_001L,
            scheduler.releaseTimestampOrNull(now + 40_000_000L, now + 1_000_000L),
        )
        assertEquals(
            now + 50_000_002L,
            scheduler.releaseTimestampOrNull(null, now + 2_000_000L),
        )
    }

    @Test
    fun outliersAndOverflowFallBackToImmediateRelease() {
        val scheduler = ScreenFrameRenderScheduler()
        val now = 2_000_000_000L

        assertNull(scheduler.releaseTimestampOrNull(now + 500_000_001L, now))
        assertNull(scheduler.releaseTimestampOrNull(now - 100_000_001L, now))
        assertNull(scheduler.releaseTimestampOrNull(Long.MAX_VALUE, Long.MIN_VALUE))
    }

    @Test
    fun resetDropsOrderingHistoryAndLimitsMustBeValid() {
        val scheduler = ScreenFrameRenderScheduler()
        val now = 3_000_000_000L
        scheduler.releaseTimestampOrNull(now + 20_000_000L, now)
        scheduler.reset()

        assertNull(scheduler.releaseTimestampOrNull(null, now + 1_000_000L))
        assertThrows(IllegalArgumentException::class.java) {
            ScreenFrameRenderScheduler(maximumLateNanos = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenFrameRenderScheduler(maximumEarlyNanos = 0)
        }
    }
}
