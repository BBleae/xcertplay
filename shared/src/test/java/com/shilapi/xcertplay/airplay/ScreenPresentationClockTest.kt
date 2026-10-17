package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenPresentationClockTest {
    @Test
    fun mapsFutureAndPastNtpFractionsIntoMonotonicTime() {
        val now = ntp(seconds = UNIX_TO_NTP_SECONDS + 16, fraction = 0)
        val sample = 9_000_000_000L

        assertEquals(
            sample + 500_000_000L,
            ScreenPresentationClock.toMonotonicNanos(
                ntpBits(seconds = 16, fraction = 0x8000_0000L),
                now,
                sample,
            ),
        )
        assertEquals(
            sample - 250_000_000L,
            ScreenPresentationClock.toMonotonicNanos(
                ntpBits(seconds = 15, fraction = 0xc000_0000L),
                now,
                sample,
            ),
        )
    }

    @Test
    fun selectsNearestDeltaAcrossTheNtpEraRollover() {
        val rawNow = ntp(seconds = 0xffff_ffffL, fraction = 0xf000_0000L)
        val now = rawNow.add(UNIX_TO_NTP_TICKS).and(UINT64_MASK)

        assertEquals(
            1_125_000_000L,
            ScreenPresentationClock.toMonotonicNanos(
                ntpBits(seconds = 0, fraction = 0x1000_0000L),
                now,
                sampleMonotonicNanos = 1_000_000_000L,
            ),
        )
    }

    @Test
    fun treatsHighBitValuesAsUnsignedAndRejectsOutliersOrOverflow() {
        val highNow = BigInteger.ONE.shiftLeft(63).add(UNIX_TO_NTP_TICKS).and(UINT64_MASK)
        assertEquals(
            42L,
            ScreenPresentationClock.toMonotonicNanos(Long.MIN_VALUE, highNow, 42L),
        )
        assertNull(
            ScreenPresentationClock.toMonotonicNanos(
                ntpBits(seconds = 20, fraction = 0),
                ntp(seconds = UNIX_TO_NTP_SECONDS + 10, fraction = 0),
                sampleMonotonicNanos = 42L,
            ),
        )
        assertNull(
            ScreenPresentationClock.toMonotonicNanos(
                ntpBits(seconds = 1, fraction = 0x8000_0000L),
                ntp(seconds = UNIX_TO_NTP_SECONDS + 1, fraction = 0),
                sampleMonotonicNanos = Long.MAX_VALUE - 1,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScreenPresentationClock.toMonotonicNanos(0, BigInteger.ZERO, 0, 0)
        }
    }

    private fun ntpBits(seconds: Long, fraction: Long): Long =
        ntp(seconds, fraction).and(UINT64_MASK).toLong()

    private fun ntp(seconds: Long, fraction: Long): BigInteger =
        BigInteger.valueOf(seconds).shiftLeft(32).or(BigInteger.valueOf(fraction))

    private companion object {
        const val UNIX_TO_NTP_SECONDS = 2_208_988_800L
        val UNIX_TO_NTP_TICKS: BigInteger =
            BigInteger.valueOf(UNIX_TO_NTP_SECONDS).shiftLeft(32)
        val UINT64_MASK: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    }
}
