package com.shilapi.xcertplay.airplay

/** Opaque provenance of the header/counter pair used by a successfully opened AEAD frame. */
class AuthenticatedScreenFrameIdentity private constructor(
    val frameCounter: Long,
    val presentationTimeNtp64Bits: Long?,
    /**
     * Unsigned type-111 timelineID at header +0x28, retained as raw [Long] bits. The iOS 27
     * sender stores this u64 natively (little-endian on the observed arm64 platform). This is
     * an identifier, not a second presentation time or a clock-calibration sample.
     */
    val timelineIdRawBits: Long?,
) {
    /** Source-compatibility alias for consumers predating the timelineID disassembly. */
    @Deprecated("This field is a timeline ID, not network time; use timelineIdRawBits")
    val auxiliaryNetworkTimeRawBits: Long?
        get() = timelineIdRawBits

    companion object {
        internal fun issue(
            frameCounter: Long,
            presentationTimeNtp64Bits: Long?,
            timelineIdRawBits: Long?,
        ): AuthenticatedScreenFrameIdentity {
            require(frameCounter >= 0)
            return AuthenticatedScreenFrameIdentity(
                frameCounter,
                presentationTimeNtp64Bits,
                timelineIdRawBits,
            )
        }
    }
}

/**
 * Timing metadata carried by one complete AirPlay screen frame.
 *
 * [presentationTimeNtp64Bits] is the unmodified APSNetworkTime 32.32 value at header +8. It is
 * deliberately stored as raw [Long] bits: callers must not assume a wall-clock epoch or compare
 * it with type-2 UI Sync ticks without measured calibration. [timelineIdRawBits] is the separate
 * u64 timelineID at +0x28, read as native little-endian bytes on the observed iOS 27 arm64
 * sender. It is an identifier, never a second timestamp or a frame-scheduling clock.
 */
data class ScreenFrameTiming(
    val presentationTimeNtp64Bits: Long?,
    val arrivalMonotonicNanos: Long,
    /** Issued only by the authenticated ScreenStream path; absent for legacy callers. */
    val authenticatedFrameIdentity: AuthenticatedScreenFrameIdentity? = null,
    val timelineIdRawBits: Long? = null,
) {
    val authenticatedFrameCounter: Long?
        get() = authenticatedFrameIdentity?.frameCounter

    /** Source-compatibility alias for consumers predating the timelineID disassembly. */
    @Deprecated("This field is a timeline ID, not network time; use timelineIdRawBits")
    val auxiliaryNetworkTimeRawBits: Long?
        get() = timelineIdRawBits
}

/** Parses frame timing without consulting a clock or interpreting the unsigned NTP value. */
internal fun parseScreenFrameTiming(
    header: ByteArray,
    arrivalMonotonicNanos: Long,
    authenticatedFrameCounter: Long? = null,
): ScreenFrameTiming {
    if (authenticatedFrameCounter != null) {
        require(header.size == SCREEN_HEADER_LENGTH) { "authenticated screen header must be 128 bytes" }
    }
    val rawNtp = if (header.size == SCREEN_HEADER_LENGTH) {
        readU64LeBitsOrNull(header, PRESENTATION_TIME_OFFSET)
    } else {
        null
    }
    val rawTimelineId = if (header.size == SCREEN_HEADER_LENGTH) {
        readU64LeBitsOrNull(header, TIMELINE_ID_OFFSET)
    } else {
        null
    }
    return ScreenFrameTiming(
        presentationTimeNtp64Bits = rawNtp,
        arrivalMonotonicNanos = arrivalMonotonicNanos,
        authenticatedFrameIdentity = authenticatedFrameCounter?.let {
            AuthenticatedScreenFrameIdentity.issue(it, rawNtp, rawTimelineId)
        },
        timelineIdRawBits = rawTimelineId,
    )
}

private fun readU64LeBitsOrNull(source: ByteArray, offset: Int): Long? {
    if (offset < 0 || source.size - offset < Long.SIZE_BYTES) return null
    var bits = 0L
    repeat(Long.SIZE_BYTES) { index ->
        bits = bits or ((source[offset + index].toLong() and 0xffL) shl (index * 8))
    }
    return bits
}

private const val PRESENTATION_TIME_OFFSET = 8
private const val TIMELINE_ID_OFFSET = 0x28
private const val SCREEN_HEADER_LENGTH = 128
