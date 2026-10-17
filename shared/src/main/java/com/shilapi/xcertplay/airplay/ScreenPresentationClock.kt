package com.shilapi.xcertplay.airplay

import java.math.BigInteger

/**
 * Maps an unsigned NTP 32.32 presentation value into the local monotonic clock domain.
 *
 * AirPlay screen headers use the same 32.32 layout but omit the 1900-to-1970 epoch offset carried
 * by the timing-port clock. The offset is added modulo UInt64 first. NTP's seconds field wraps
 * every 2^32 seconds; the nearest modular delta to the synchronized sample is then selected and
 * bounded so malformed or stale media cannot schedule a Surface buffer arbitrarily far away.
 */
internal object ScreenPresentationClock {
    const val DEFAULT_MAXIMUM_DELTA_NANOS = 5_000_000_000L

    fun toMonotonicNanos(
        presentationTimeNtp64Bits: Long,
        synchronizedNowNtp: BigInteger,
        sampleMonotonicNanos: Long,
        maximumDeltaNanos: Long = DEFAULT_MAXIMUM_DELTA_NANOS,
    ): Long? {
        require(maximumDeltaNanos > 0) { "maximumDeltaNanos must be positive" }
        val now = synchronizedNowNtp.and(UINT64_MASK)
        val target = unsignedLong(presentationTimeNtp64Bits)
            .add(UNIX_TO_NTP_EPOCH_TICKS)
            .and(UINT64_MASK)
        var delta = target.subtract(now)
        if (delta >= HALF_RANGE) delta = delta.subtract(UINT64_MODULUS)
        if (delta < HALF_RANGE.negate()) delta = delta.add(UINT64_MODULUS)

        val deltaNanos = delta.multiply(NANOS_PER_SECOND).divide(NTP_FRACTION_SCALE)
        if (deltaNanos.abs() > BigInteger.valueOf(maximumDeltaNanos)) return null
        return try {
            Math.addExact(sampleMonotonicNanos, deltaNanos.toLong())
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun unsignedLong(bits: Long): BigInteger = if (bits >= 0) {
        BigInteger.valueOf(bits)
    } else {
        BigInteger.valueOf(bits and Long.MAX_VALUE).setBit(Long.SIZE_BITS - 1)
    }

    private val NTP_FRACTION_SCALE = BigInteger.ONE.shiftLeft(32)
    private val UNIX_TO_NTP_EPOCH_TICKS =
        BigInteger.valueOf(2_208_988_800L).shiftLeft(32)
    private val NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L)
    private val UINT64_MODULUS = BigInteger.ONE.shiftLeft(64)
    private val UINT64_MASK = UINT64_MODULUS.subtract(BigInteger.ONE)
    private val HALF_RANGE = BigInteger.ONE.shiftLeft(63)
}
