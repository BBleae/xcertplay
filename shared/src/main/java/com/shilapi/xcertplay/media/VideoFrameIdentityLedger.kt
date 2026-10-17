package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle

/**
 * Diagnostic evidence that one authenticated type-111 access unit reached MediaCodec's exact
 * output buffer and that the buffer was released to the exact decoder Surface generation.
 * This is not proof of SurfaceTexture consumption, EGL composition, or physical scanout.
 * The decoder emits one only after a matching output was successfully released.
 */
class AndroidVideoFrameReleaseReceipt private constructor(
    val handle: AirPlayVideoStreamHandle,
    val surface: Surface,
    val surfaceLeaseGeneration: Long,
    val authenticatedFrameCounter: Long,
    val presentationTimeNtp64Bits: Long,
    /** Type-111 header +0x28 timelineID bits; never a scheduling clock. */
    val timelineIdRawBits: Long?,
    val codecInputPresentationTimeUs: Long,
    val codecOutputPresentationTimeUs: Long,
    val releaseCalledAtMonotonicNanos: Long,
    val releaseCompletedAtMonotonicNanos: Long,
    val releaseTargetMonotonicNanos: Long?,
    /** Exact timestamp expected on SurfaceTexture, never a nearest-time heuristic. */
    val expectedSurfaceTextureTimestampNanos: Long,
) {
    /** Source-compatibility alias for consumers predating the timelineID disassembly. */
    @Deprecated("This field is a timeline ID, not network time; use timelineIdRawBits")
    val auxiliaryNetworkTimeRawBits: Long?
        get() = timelineIdRawBits

    init {
        require(handle.type == 111)
        require(surfaceLeaseGeneration > 0)
        require(authenticatedFrameCounter >= 0)
        require(codecInputPresentationTimeUs == codecOutputPresentationTimeUs)
        require(releaseCompletedAtMonotonicNanos >= releaseCalledAtMonotonicNanos)
    }

    companion object {
        @JvmSynthetic
        internal fun issue(
            handle: AirPlayVideoStreamHandle,
            surface: Surface,
            surfaceLeaseGeneration: Long,
            matched: VideoFrameIdentityLedger.Match,
            codecOutputPresentationTimeUs: Long,
            releaseCalledAtMonotonicNanos: Long,
            releaseCompletedAtMonotonicNanos: Long,
            releaseTargetMonotonicNanos: Long?,
            expectedSurfaceTextureTimestampNanos: Long,
        ): AndroidVideoFrameReleaseReceipt = AndroidVideoFrameReleaseReceipt(
            handle = handle,
            surface = surface,
            surfaceLeaseGeneration = surfaceLeaseGeneration,
            authenticatedFrameCounter = matched.frameCounter,
            presentationTimeNtp64Bits = matched.presentationTimeNtp64Bits,
            timelineIdRawBits = matched.timelineIdRawBits,
            codecInputPresentationTimeUs = matched.inputPresentationTimeUs,
            codecOutputPresentationTimeUs = codecOutputPresentationTimeUs,
            releaseCalledAtMonotonicNanos = releaseCalledAtMonotonicNanos,
            releaseCompletedAtMonotonicNanos = releaseCompletedAtMonotonicNanos,
            releaseTargetMonotonicNanos = releaseTargetMonotonicNanos,
            expectedSurfaceTextureTimestampNanos = expectedSurfaceTextureTimestampNanos,
        )
    }
}

/**
 * Bounded, per-handle one-time MediaCodec input/output join. All methods are called from the
 * decoder worker. A stale/duplicate output or a Surface replacement consumes no authority.
 */
internal class VideoFrameIdentityLedger<H : Any, S : Any>(
    private val handle: H,
    private val capacity: Int = 64,
) {
    internal data class Match(
        val frameCounter: Long,
        val presentationTimeNtp64Bits: Long,
        val inputPresentationTimeUs: Long,
        val timelineIdRawBits: Long?,
    ) {
        /** Source-compatibility alias for consumers predating the timelineID disassembly. */
        @Deprecated("This field is a timeline ID, not network time; use timelineIdRawBits")
        val auxiliaryNetworkTimeRawBits: Long?
            get() = timelineIdRawBits
    }

    private class Pending<S : Any>(
        val match: Match,
        val surface: S,
        val surfaceLeaseGeneration: Long,
    )

    private val pending = LinkedHashMap<Long, Pending<S>>()
    private var lastQueuedFrameCounter = -1L
    private var lastReleasedFrameCounter = -1L
    private var lastIssuedSurface: S? = null
    private var lastIssuedSurfaceLeaseGeneration = -1L
    private var lastIssuedExpectedTimestampNanos: Long? = null

    init {
        require(capacity in 1..256)
    }

    fun recordQueued(
        owner: H,
        frameCounter: Long,
        presentationTimeNtp64Bits: Long,
        inputPresentationTimeUs: Long,
        surface: S,
        surfaceLeaseGeneration: Long,
        timelineIdRawBits: Long? = null,
    ): Boolean {
        if (owner !== handle || frameCounter < 0 || frameCounter <= lastQueuedFrameCounter ||
            inputPresentationTimeUs < 0 || surfaceLeaseGeneration <= 0 ||
            pending.containsKey(inputPresentationTimeUs)
        ) return false
        lastQueuedFrameCounter = frameCounter
        pending[inputPresentationTimeUs] = Pending(
            Match(
                frameCounter,
                presentationTimeNtp64Bits,
                inputPresentationTimeUs,
                timelineIdRawBits,
            ),
            surface,
            surfaceLeaseGeneration,
        )
        while (pending.size > capacity) pending.remove(pending.keys.first())
        return true
    }

    fun matchReleased(
        owner: H,
        outputPresentationTimeUs: Long,
        surface: S,
        surfaceLeaseGeneration: Long,
        expectedSurfaceTextureTimestampNanos: Long,
    ): Match? {
        if (owner !== handle) return null
        val entry = pending.remove(outputPresentationTimeUs) ?: return null
        val match = entry.match
        if (entry.surface !== surface ||
            entry.surfaceLeaseGeneration != surfaceLeaseGeneration ||
            match.frameCounter <= lastReleasedFrameCounter
        ) return null
        if (lastIssuedSurface === surface &&
            lastIssuedSurfaceLeaseGeneration == surfaceLeaseGeneration &&
            lastIssuedExpectedTimestampNanos?.let {
                expectedSurfaceTextureTimestampNanos <= it
            } == true
        ) return null
        lastReleasedFrameCounter = match.frameCounter
        lastIssuedSurface = surface
        lastIssuedSurfaceLeaseGeneration = surfaceLeaseGeneration
        lastIssuedExpectedTimestampNanos = expectedSurfaceTextureTimestampNanos
        return match
    }

    /** MediaCodec reset/detach discards all pending input without resetting the wire counter. */
    fun discardPending() {
        pending.clear()
    }

    val pendingCount: Int get() = pending.size
}
