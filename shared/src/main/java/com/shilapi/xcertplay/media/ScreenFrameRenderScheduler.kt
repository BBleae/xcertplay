package com.shilapi.xcertplay.media

/**
 * Chooses whether a decoded Surface frame may use Android's timed release API.
 *
 * Untrusted/outlier targets fall back to immediate release. If a previous frame is already queued
 * for a future VSYNC, later frames are kept strictly after it so fallback cannot reorder buffers.
 */
internal class ScreenFrameRenderScheduler(
    private val maximumLateNanos: Long = DEFAULT_MAXIMUM_LATE_NANOS,
    private val maximumEarlyNanos: Long = DEFAULT_MAXIMUM_EARLY_NANOS,
) {
    private var lastTimedReleaseNanos: Long? = null

    init {
        require(maximumLateNanos >= 0)
        require(maximumEarlyNanos > 0)
    }

    /** Returns an absolute `System.nanoTime()` timestamp, or null for immediate release. */
    fun releaseTimestampOrNull(targetNanos: Long?, nowNanos: Long): Long? {
        val targetDelta = targetNanos?.let { differenceOrNull(it, nowNanos) }
        if (targetNanos == null || targetDelta == null ||
            targetDelta < -maximumLateNanos || targetDelta > maximumEarlyNanos
        ) {
            return orderedFallback(nowNanos)
        }

        val previous = lastTimedReleaseNanos
        val ordered = if (previous != null && targetNanos <= previous) {
            incrementOrNull(previous) ?: return orderedFallback(nowNanos)
        } else {
            targetNanos
        }
        val orderedDelta = differenceOrNull(ordered, nowNanos)
            ?: return orderedFallback(nowNanos)
        if (orderedDelta > maximumEarlyNanos) return orderedFallback(nowNanos)
        lastTimedReleaseNanos = ordered
        return ordered
    }

    fun reset() {
        lastTimedReleaseNanos = null
    }

    private fun orderedFallback(nowNanos: Long): Long? {
        val previous = lastTimedReleaseNanos
        if (previous != null && previous > nowNanos) {
            val ordered = incrementOrNull(previous)
            if (ordered != null) {
                val delta = differenceOrNull(ordered, nowNanos)
                if (delta != null && delta <= maximumEarlyNanos) {
                    lastTimedReleaseNanos = ordered
                    return ordered
                }
            }
        }
        lastTimedReleaseNanos = nowNanos
        return null
    }

    private fun differenceOrNull(left: Long, right: Long): Long? = try {
        Math.subtractExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun incrementOrNull(value: Long): Long? = try {
        Math.addExact(value, 1L)
    } catch (_: ArithmeticException) {
        null
    }

    private companion object {
        const val DEFAULT_MAXIMUM_LATE_NANOS = 100_000_000L
        const val DEFAULT_MAXIMUM_EARLY_NANOS = 500_000_000L
    }
}
