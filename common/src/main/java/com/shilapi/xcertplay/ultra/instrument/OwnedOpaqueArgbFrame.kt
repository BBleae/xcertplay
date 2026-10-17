package com.shilapi.xcertplay.ultra.instrument

/**
 * A complete, opaque ARGB snapshot with exactly one owner at a time. [capture] copies the mutable
 * raster once. A handoff invalidates the sender's token without copying the pixels; only the final
 * EGL command may [take] the array. The command must retain it until execution settles, including
 * a control timeout whose running action may still read the frame.
 */
internal class OwnedOpaqueArgbFrame private constructor(
    private var pixels: IntArray?,
) {
    internal fun isCompleteFor(expectedPixelCount: Long): Boolean = synchronized(this) {
        pixels?.size?.toLong() == expectedPixelCount
    }

    /** A failed/duplicate transfer has no pixels and cannot be retried with this token. */
    internal fun transfer(): OwnedOpaqueArgbFrame? = synchronized(this) {
        val exactPixels = pixels ?: return@synchronized null
        pixels = null
        OwnedOpaqueArgbFrame(exactPixels)
    }

    /** Terminal transfer into an EGL command or a test-only fallback adapter. */
    internal fun take(): IntArray? = synchronized(this) {
        pixels.also { pixels = null }
    }

    internal companion object {
        private const val MAXIMUM_PIXELS = 1_600_000

        /** Validate the private copy, not caller memory that may change during submission. */
        fun capture(argbPixels: IntArray): OwnedOpaqueArgbFrame? {
            if (argbPixels.size !in 1..MAXIMUM_PIXELS) return null
            val snapshot = argbPixels.copyOf()
            if (snapshot.any { it ushr 24 != 0xff }) return null
            return OwnedOpaqueArgbFrame(snapshot)
        }
    }
}
