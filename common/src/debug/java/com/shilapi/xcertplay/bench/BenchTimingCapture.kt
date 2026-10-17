package com.shilapi.xcertplay.bench

import java.io.Closeable
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Debug-only, same-session timing evidence. This does not synchronize clocks, schedule frames,
 * establish renderer readiness, or prove physical scan-out. Callers must supply type-2 values
 * from the accepted UI Sync event and video values from the authenticated release and exact EGL
 * match receipts; this recorder deliberately has no access to their payload dictionaries. UI
 * Sync type-2's phone-host ticks and boot-relative UpTicksToNTP 32.32 are separate from type-111
 * video-header APSNetworkTime 32.32 through the network-clock path. The export labels those
 * domains separately and does not infer an epoch, scale, offset, or conversion between them.
 * The optional type-111 timelineID at +0x28 is an unsigned identifier and does not affect
 * matching or presentation scheduling.
 *
 * The export limit applies to serialized UTF-8 NDJSON, including its header. All event rows are
 * ASCII and immutable. Object identities and raw generation numbers are retained only in bounded
 * in-memory join state and are removed by [revoke] or [close]. An export already returned to a
 * caller cannot be erased by this object and must be managed by that caller.
 */
internal class BenchTimingCapture(
    sessionToken: Any,
    private val maximumRecords: Int = MAXIMUM_RECORDS,
    private val maximumExportBytes: Int = MAXIMUM_EXPORT_BYTES,
) : Closeable {
    enum class State { CURRENT, BUSY, REVOKED }

    enum class TimestampBasis { CODEC_OUTPUT_PTS, TIMED_CODEC_RELEASE }

    data class Snapshot(
        val captureId: String?,
        val state: State,
        val retainedRecords: Int,
        /** Serialized event-row bytes; the export also contains a bounded header. */
        val retainedBytes: Int,
        val droppedOverflow: Long,
        /** Absolute loss count reported by the uncounted UI Sync diagnostic observer. */
        val observerSnapshotDrops: Long,
        /** Absolute loss count from the presentation source's retained-event windows. */
        val sourceRetainedEventDrops: Long,
        val rejectedRecords: Long,
    )

    private data class Record(val line: String, val bytes: Int)

    private data class PendingRelease(
        val frameCounter: Long,
        val presentationTimeNtp64Bits: Long,
        val codecOutputPresentationTimeUs: Long,
        val releaseTargetMonotonicNanos: Long?,
        val expectedSurfaceTextureTimestampNanos: Long,
    )

    private class Route(
        val handle: Any,
        val surface: Any,
        val surfaceLeaseGeneration: Long,
        val displayAlias: String,
        val alias: String,
    ) {
        var lastReleasedFrameCounter = -1L
        var ingressGeneration: Long? = null
        var ingressAlias: String? = null
        var lastEglUpdateSequence = 0L
        val pending = LinkedHashMap<Long, PendingRelease>()
    }

    private val lock = Any()
    private var owner: Any? = sessionToken
    private var captureId: String? = UUID.randomUUID().toString()
    private var state = State.CURRENT
    private val records = ArrayDeque<Record>()
    private var recordsBytes = 0
    private var nextSequence = 1L
    private var droppedOverflow = 0L
    private var observerSnapshotDrops = 0L
    private var sourceRetainedEventDrops = 0L
    private var rejectedRecords = 0L
    private var nextDisplayAlias = 1
    private var nextRouteAlias = 1
    private var nextIngressAlias = 1
    private val displays = IdentityHashMap<Any, String>()
    private val lastType2Revision = IdentityHashMap<Any, Long>()
    private val routes = ArrayList<Route>()

    init {
        require(maximumRecords in 1..MAXIMUM_RECORDS)
        require(maximumExportBytes in MINIMUM_EXPORT_BYTES..MAXIMUM_EXPORT_BYTES)
    }

    /** Repeated or non-increasing revisions for the same exact display are not captured. */
    fun recordType2(
        sessionToken: Any,
        displayToken: Any,
        revision: Long,
        presentationHostTimeBits: Long,
        presentationNtpTimeBits: Long,
        presentationNtpTimeOriginalBits: Long?,
        currentNtpTimeBits: Long,
        acceptedAtMonotonicNanos: Long,
    ): Boolean = synchronized(lock) {
        if (!mayRecord(sessionToken) || revision <= 0L ||
            lastType2Revision[displayToken]?.let { revision <= it } == true
        ) return@synchronized reject()
        val display = displayAlias(displayToken) ?: return@synchronized reject()
        val line = buildString {
            append("{\"kind\":\"ui_sync_type2\",\"seq\":")
            append(nextSequence)
            append(",\"display\":\"")
            append(display)
            append("\",\"revision\":")
            append(revision)
            append(",\"presentation_host_time\":{\"domain\":\"phone_host_ticks_raw_u64_unmapped\",\"bits\":\"")
            append(unsignedHex(presentationHostTimeBits))
            append("\"},\"presentation_ntp_time\":{\"domain\":\"phone_boot_relative_ntp32_32_raw_u64_unmapped\",\"bits\":\"")
            append(unsignedHex(presentationNtpTimeBits))
            append("\"},\"presentation_ntp_time_original\":")
            if (presentationNtpTimeOriginalBits == null) append("null") else {
                append("{\"domain\":\"phone_boot_relative_ntp32_32_raw_u64_unmapped\",\"bits\":\"")
                append(unsignedHex(presentationNtpTimeOriginalBits))
                append("\"}")
            }
            append(",\"current_ntp_time\":{\"domain\":\"phone_boot_relative_ntp32_32_raw_u64_unmapped\",\"bits\":\"")
            append(unsignedHex(currentNtpTimeBits))
            append("\"},\"accepted_at\":{\"domain\":\"android_monotonic_nanos\",\"value\":")
            append(acceptedAtMonotonicNanos)
            append("}}\n")
        }
        if (!appendRecord(line)) return@synchronized false
        lastType2Revision[displayToken] = revision
        true
    }

    /** Inputs must be copied from one authenticated MediaCodec output-release receipt. */
    fun recordVideoRelease(
        sessionToken: Any,
        displayToken: Any,
        handleToken: Any,
        surfaceToken: Any,
        surfaceLeaseGeneration: Long,
        authenticatedFrameCounter: Long,
        presentationTimeNtp64Bits: Long,
        codecOutputPresentationTimeUs: Long,
        releaseCalledAtMonotonicNanos: Long,
        releaseCompletedAtMonotonicNanos: Long,
        releaseTargetMonotonicNanos: Long?,
        expectedSurfaceTextureTimestampNanos: Long,
        timelineIdRawBits: Long? = null,
    ): Boolean = synchronized(lock) {
        if (!mayRecord(sessionToken) || surfaceLeaseGeneration <= 0L ||
            authenticatedFrameCounter < 0L || codecOutputPresentationTimeUs < 0L ||
            releaseCompletedAtMonotonicNanos < releaseCalledAtMonotonicNanos ||
            expectedSurfaceTextureTimestampNanos <= 0L ||
            !validReleaseTimestamp(codecOutputPresentationTimeUs,
                releaseTargetMonotonicNanos, expectedSurfaceTextureTimestampNanos)
        ) return@synchronized reject()
        val display = displayAlias(displayToken) ?: return@synchronized reject()
        val existing = routes.firstOrNull { it.handle === handleToken }
        if (existing != null && (surfaceLeaseGeneration < existing.surfaceLeaseGeneration ||
                surfaceLeaseGeneration == existing.surfaceLeaseGeneration &&
                    (surfaceToken !== existing.surface || display != existing.displayAlias))
        ) return@synchronized reject()
        val route = if (existing != null &&
            surfaceLeaseGeneration == existing.surfaceLeaseGeneration
        ) existing else {
            if (existing == null && routes.size >= MAXIMUM_ROUTES) return@synchronized reject()
            if (existing != null) routes.remove(existing)
            Route(handleToken, surfaceToken, surfaceLeaseGeneration, display,
                "route#${nextRouteAlias++}").also(routes::add)
        }
        if (authenticatedFrameCounter <= route.lastReleasedFrameCounter) {
            return@synchronized reject()
        }
        val line = buildString {
            append("{\"kind\":\"video_release\",\"seq\":")
            append(nextSequence)
            append(",\"display\":\"")
            append(display)
            append("\",\"route\":\"")
            append(route.alias)
            append("\",\"frame_counter\":")
            append(authenticatedFrameCounter)
            append(",\"presentation_ntp_time\":{\"domain\":\"video_network_clock_ntp64_raw_u64_unmapped\",\"bits\":\"")
            append(unsignedHex(presentationTimeNtp64Bits))
            append("\"},\"codec_output_pts\":{\"domain\":\"media_codec_presentation_us\",\"value\":")
            append(codecOutputPresentationTimeUs)
            append("},\"release_called_at\":{\"domain\":\"android_monotonic_nanos\",\"value\":")
            append(releaseCalledAtMonotonicNanos)
            append("},\"release_completed_at\":{\"domain\":\"android_monotonic_nanos\",\"value\":")
            append(releaseCompletedAtMonotonicNanos)
            append("},\"release_target\":")
            if (releaseTargetMonotonicNanos == null) append("null") else {
                append("{\"domain\":\"android_monotonic_nanos\",\"value\":")
                append(releaseTargetMonotonicNanos)
                append('}')
            }
            append(",\"expected_texture_timestamp\":{\"domain\":\"surface_texture_timestamp_nanos\",\"value\":")
            append(expectedSurfaceTextureTimestampNanos)
            append("},\"timeline_id\":")
            if (timelineIdRawBits == null) append("null") else {
                append("{\"domain\":\"video_timeline_id_raw_u64\",\"bits\":\"")
                append(unsignedHex(timelineIdRawBits))
                append("\"}")
            }
            append("}\n")
        }
        if (!appendRecord(line)) return@synchronized false
        route.lastReleasedFrameCounter = authenticatedFrameCounter
        route.pending[authenticatedFrameCounter] = PendingRelease(
            authenticatedFrameCounter, presentationTimeNtp64Bits,
            codecOutputPresentationTimeUs, releaseTargetMonotonicNanos,
            expectedSurfaceTextureTimestampNanos)
        if (route.pending.size > MAXIMUM_PENDING_PER_ROUTE) {
            route.pending.remove(route.pending.keys.first())
            droppedOverflow++
        }
        true
    }

    /** An EGL match is retained only if its exact earlier release is still pending. */
    fun recordEglMatched(
        sessionToken: Any,
        displayToken: Any,
        handleToken: Any,
        surfaceToken: Any,
        surfaceLeaseGeneration: Long,
        eglIngressGeneration: Long,
        eglUpdateSequence: Long,
        surfaceTextureTimestampNanos: Long,
        timestampBasis: TimestampBasis,
        authenticatedFrameCounter: Long,
        presentationTimeNtp64Bits: Long,
        codecOutputPresentationTimeUs: Long,
    ): Boolean = synchronized(lock) {
        if (!mayRecord(sessionToken) || surfaceLeaseGeneration <= 0L ||
            eglIngressGeneration <= 0L || eglUpdateSequence <= 0L ||
            surfaceTextureTimestampNanos <= 0L || authenticatedFrameCounter < 0L ||
            codecOutputPresentationTimeUs < 0L
        ) return@synchronized reject()
        val route = routes.firstOrNull { it.handle === handleToken &&
            it.surface === surfaceToken &&
            it.surfaceLeaseGeneration == surfaceLeaseGeneration
        } ?: return@synchronized reject()
        if (displays[displayToken] != route.displayAlias ||
            route.ingressGeneration?.let { it != eglIngressGeneration } == true ||
            eglUpdateSequence <= route.lastEglUpdateSequence
        ) return@synchronized reject()
        val pending = route.pending[authenticatedFrameCounter]
            ?: return@synchronized reject()
        if (pending.frameCounter != authenticatedFrameCounter ||
            pending.presentationTimeNtp64Bits != presentationTimeNtp64Bits ||
            pending.codecOutputPresentationTimeUs != codecOutputPresentationTimeUs ||
            pending.expectedSurfaceTextureTimestampNanos != surfaceTextureTimestampNanos ||
            (pending.releaseTargetMonotonicNanos != null) !=
                (timestampBasis == TimestampBasis.TIMED_CODEC_RELEASE)
        ) return@synchronized reject()
        val ingress = route.ingressAlias ?: "ingress#${nextIngressAlias++}"
        val line = buildString {
            append("{\"kind\":\"egl_matched\",\"seq\":")
            append(nextSequence)
            append(",\"display\":\"")
            append(route.displayAlias)
            append("\",\"route\":\"")
            append(route.alias)
            append("\",\"ingress\":\"")
            append(ingress)
            append("\",\"frame_counter\":")
            append(authenticatedFrameCounter)
            append(",\"presentation_ntp_time\":{\"domain\":\"video_network_clock_ntp64_raw_u64_unmapped\",\"bits\":\"")
            append(unsignedHex(presentationTimeNtp64Bits))
            append("\"},\"codec_output_pts\":{\"domain\":\"media_codec_presentation_us\",\"value\":")
            append(codecOutputPresentationTimeUs)
            append("},\"egl_update_sequence\":")
            append(eglUpdateSequence)
            append(",\"surface_texture_timestamp\":{\"domain\":\"surface_texture_timestamp_nanos\",\"value\":")
            append(surfaceTextureTimestampNanos)
            append("},\"timestamp_basis\":\"")
            append(timestampBasis.name)
            append("\"}\n")
        }
        if (!appendRecord(line)) return@synchronized false
        route.ingressGeneration = eglIngressGeneration
        route.ingressAlias = ingress
        route.lastEglUpdateSequence = eglUpdateSequence
        route.pending.remove(authenticatedFrameCounter)
        true
    }

    /** BUSY is not CURRENT: freeze recording and discard unjoined resource identities. */
    fun markBusy(sessionToken: Any): Boolean = synchronized(lock) {
        if (owner !== sessionToken || state == State.REVOKED) return@synchronized false
        state = State.BUSY
        routes.clear()
        true
    }

    /** The caller must independently establish CURRENT before invoking this method. */
    fun resumeCurrentFromExternalAuthority(sessionToken: Any): Boolean = synchronized(lock) {
        if (owner !== sessionToken || state == State.REVOKED) return@synchronized false
        state = State.CURRENT
        true
    }

    /** Absolute, monotone observer-mailbox loss count; BUSY retains it without claiming CURRENT. */
    fun noteObserverSnapshotDrops(sessionToken: Any, absoluteCount: Long): Boolean =
        synchronized(lock) {
            if (owner !== sessionToken || state == State.REVOKED || absoluteCount < 0L ||
                absoluteCount < observerSnapshotDrops
            ) return@synchronized false
            observerSnapshotDrops = absoluteCount
            true
        }

    /** Presentation-source retention loss, independent of observer and recorder ring loss. */
    fun noteSourceRetainedEventDrops(sessionToken: Any, absoluteCount: Long): Boolean =
        synchronized(lock) {
            if (owner !== sessionToken || state == State.REVOKED || absoluteCount < 0L ||
                absoluteCount < sourceRetainedEventDrops
            ) return@synchronized false
            sourceRetainedEventDrops = absoluteCount
            true
        }

    fun revoke(sessionToken: Any): Boolean = synchronized(lock) {
        if (owner !== sessionToken || state == State.REVOKED) return@synchronized false
        clear()
        true
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(captureId, state, records.size, recordsBytes, droppedOverflow,
            observerSnapshotDrops, sourceRetainedEventDrops, rejectedRecords)
    }

    /** Deterministic for an unchanged capture; no wall-clock or source strings are serialized. */
    fun exportNdjson(): String = synchronized(lock) {
        val id = captureId ?: return@synchronized ""
        val header = "{\"kind\":\"capture\",\"schema\":\"xcertplay-timing-v2\"," +
            "\"capture_id\":\"$id\",\"clock_policy\":\"raw_no_conversion\"," +
            "\"state\":\"${state.name}\",\"retained_records\":${records.size}," +
            "\"dropped_overflow\":$droppedOverflow," +
            "\"observer_snapshot_drops\":$observerSnapshotDrops," +
            "\"source_retained_event_drops\":$sourceRetainedEventDrops," +
            "\"rejected_records\":$rejectedRecords}\n"
        buildString(header.length + recordsBytes) {
            append(header)
            records.forEach { append(it.line) }
        }.also { check(it.toByteArray(Charsets.UTF_8).size <= maximumExportBytes) }
    }

    override fun close() = synchronized(lock) { clear() }

    private fun clear() {
        owner = null
        captureId = null
        state = State.REVOKED
        records.clear()
        recordsBytes = 0
        displays.clear()
        lastType2Revision.clear()
        routes.clear()
        nextDisplayAlias = 1
        nextRouteAlias = 1
        nextIngressAlias = 1
        nextSequence = 1L
        droppedOverflow = 0L
        observerSnapshotDrops = 0L
        sourceRetainedEventDrops = 0L
        rejectedRecords = 0L
    }

    private fun mayRecord(candidate: Any): Boolean = owner === candidate && state == State.CURRENT

    private fun reject(): Boolean {
        if (state != State.REVOKED) rejectedRecords++
        return false
    }

    private fun displayAlias(token: Any): String? {
        displays[token]?.let { return it }
        if (displays.size >= MAXIMUM_DISPLAYS) return null
        return "display#${nextDisplayAlias++}".also { displays[token] = it }
    }

    private fun appendRecord(line: String): Boolean {
        val bytes = line.toByteArray(Charsets.UTF_8).size
        val budget = maximumExportBytes - HEADER_RESERVE_BYTES
        if (bytes > budget) {
            droppedOverflow++
            return false
        }
        while (records.size >= maximumRecords || recordsBytes + bytes > budget) {
            recordsBytes -= records.removeFirst().bytes
            droppedOverflow++
        }
        records.addLast(Record(line, bytes))
        recordsBytes += bytes
        nextSequence++
        return true
    }

    private companion object {
        const val MAXIMUM_RECORDS = 4_096
        const val MAXIMUM_EXPORT_BYTES = 1_048_576
        const val MINIMUM_EXPORT_BYTES = 1_024
        const val HEADER_RESERVE_BYTES = 512
        const val MAXIMUM_DISPLAYS = 16
        const val MAXIMUM_ROUTES = 16
        const val MAXIMUM_PENDING_PER_ROUTE = 64

        fun unsignedHex(bits: Long): String =
            "0x" + java.lang.Long.toUnsignedString(bits, 16).padStart(16, '0')

        fun validReleaseTimestamp(
            codecOutputPresentationTimeUs: Long,
            releaseTargetMonotonicNanos: Long?,
            expectedSurfaceTextureTimestampNanos: Long,
        ): Boolean = if (releaseTargetMonotonicNanos != null) {
            expectedSurfaceTextureTimestampNanos == releaseTargetMonotonicNanos
        } else {
            codecOutputPresentationTimeUs > 0L && try {
                expectedSurfaceTextureTimestampNanos ==
                    Math.multiplyExact(codecOutputPresentationTimeUs, 1_000L)
            } catch (_: ArithmeticException) {
                false
            }
        }
    }
}
