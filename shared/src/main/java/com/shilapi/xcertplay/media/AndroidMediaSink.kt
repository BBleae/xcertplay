package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.ScreenFrameTiming
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.airplay.mainbuffered.AndroidMainBufferedPcm16Output
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16Output
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class LegacyVideoRouteChange(
    val type: Int,
    val previousHandle: AirPlayVideoStreamHandle?,
    val currentHandle: AirPlayVideoStreamHandle?,
    val wasActive: Boolean,
    val isActive: Boolean,
) {
    val selectionChanged: Boolean
        get() = previousHandle !== currentHandle

    val activeChanged: Boolean
        get() = wasActive != isActive
}

/** A pending attach may replace only the exact registration observed before it started. */
internal fun <S : Any> matchesLegacySurfaceRegistration(
    currentSurface: S?,
    currentSource: Any?,
    expected: Pair<S, Any>?,
): Boolean = if (expected == null) {
    currentSurface == null && currentSource == null
} else {
    currentSurface === expected.first && currentSource === expected.second
}

/** A clean rollback cannot overwrite a newer route or revive a different selected session. */
internal fun <S : Any, H : Any> matchesLegacyHandoffRestoreRoute(
    currentLegacySurface: S?,
    currentLegacySource: Any?,
    currentSelectedHandle: H?,
    capturedSelectedHandle: H?,
    selectedExactSurface: S?,
    expectedExactSurface: S?,
    processOwner: Any?,
    expectedSource: Any,
    currentSurfaceGeneration: Long,
    handoffSurfaceGeneration: Long,
): Boolean = currentLegacySurface == null && currentLegacySource == null &&
    currentSelectedHandle === capturedSelectedHandle &&
    selectedExactSurface === expectedExactSurface &&
    processOwner === expectedSource &&
    currentSurfaceGeneration == handoffSurfaceGeneration

/** An exact override is unchanged only when its Surface, source, output and token are identical. */
internal fun <S : Any, O : Any, T : Any> matchesExactOverrideAttachment(
    currentSurface: S?,
    currentSource: Any?,
    currentOutput: O?,
    currentOutputSource: Any?,
    currentToken: T?,
    expectedSurface: S,
    expectedSource: Any,
    expectedOutput: O,
    expectedToken: T,
): Boolean = currentSurface === expectedSurface && currentSource === expectedSource &&
    currentOutput === expectedOutput && currentOutputSource === expectedSource &&
    currentToken === expectedToken

/** Distinguishes A → B → empty from an untouched empty registration. Guarded by routing lock. */
internal class SurfaceRouteGenerationTracker {
    private val generations = mutableMapOf<Int, Long>()

    fun current(type: Int): Long = generations[type] ?: 0L

    fun advance(type: Int): Long {
        val previous = current(type)
        check(previous < Long.MAX_VALUE) { "Surface route generation exhausted" }
        return (previous + 1L).also { generations[type] = it }
    }
}

/** Once the sink owns the registration, decoder publication cannot revert ownership transfer. */
internal fun completeCommittedLegacyHandoffRestore(
    publishDecoder: () -> Unit,
    onPublicationFailure: (Throwable) -> Unit,
): AndroidLegacySurfaceRestoreResult {
    runCatching(publishDecoder).exceptionOrNull()?.let { failure ->
        runCatching { onPublicationFailure(failure) }
    }
    return AndroidLegacySurfaceRestoreResult.Restored
}

/**
 * Deterministic adapter for UI code that can address only a stream type. The first observed
 * candidate owns that type until a committed active candidate must replace it or it terminates.
 * This keeps legacy callers useful without ever coalescing decoder state for distinct identities.
 */
internal class LegacyVideoHandleRoutes {
    internal data class TypeSnapshot(
        val type: Int,
        val candidates: LinkedHashSet<AirPlayVideoStreamHandle>,
        val active: LinkedHashSet<AirPlayVideoStreamHandle>,
        val selected: AirPlayVideoStreamHandle?,
        val generation: Long,
    )

    private val candidates = mutableMapOf<Int, LinkedHashSet<AirPlayVideoStreamHandle>>()
    private val active = mutableMapOf<Int, LinkedHashSet<AirPlayVideoStreamHandle>>()
    private val selected = mutableMapOf<Int, AirPlayVideoStreamHandle>()
    private val generations = mutableMapOf<Int, Long>()

    @Synchronized
    fun generation(type: Int): Long = generations[type] ?: 0L

    @Synchronized
    fun observe(handle: AirPlayVideoStreamHandle): LegacyVideoRouteChange = mutate(handle.type) {
        candidates.getOrPut(handle.type, ::linkedSetOf).add(handle)
    }

    @Synchronized
    fun setActive(handle: AirPlayVideoStreamHandle, isActive: Boolean): LegacyVideoRouteChange =
        mutate(handle.type) {
            if (isActive) {
                candidates.getOrPut(handle.type, ::linkedSetOf).add(handle)
                active.getOrPut(handle.type, ::linkedSetOf).add(handle)
            } else {
                candidates[handle.type]?.remove(handle)
                active[handle.type]?.remove(handle)
            }
        }

    @Synchronized
    fun selected(type: Int): AirPlayVideoStreamHandle? = selected[type]

    @Synchronized
    fun isSelected(handle: AirPlayVideoStreamHandle): Boolean = selected[handle.type] === handle

    @Synchronized
    fun snapshot(type: Int): TypeSnapshot = TypeSnapshot(
        type = type,
        candidates = LinkedHashSet(candidates[type].orEmpty()),
        active = LinkedHashSet(active[type].orEmpty()),
        selected = selected[type],
        generation = generation(type),
    )

    @Synchronized
    fun restore(snapshot: TypeSnapshot) = restore(snapshot, invalidateGeneration = true)

    private fun restore(snapshot: TypeSnapshot, invalidateGeneration: Boolean) {
        if (snapshot.candidates.isEmpty()) candidates.remove(snapshot.type)
        else candidates[snapshot.type] = LinkedHashSet(snapshot.candidates)
        if (snapshot.active.isEmpty()) active.remove(snapshot.type)
        else active[snapshot.type] = LinkedHashSet(snapshot.active)
        if (snapshot.selected == null) selected.remove(snapshot.type)
        else selected[snapshot.type] = snapshot.selected
        if (invalidateGeneration) advanceGeneration(snapshot.type)
        else generations[snapshot.type] = snapshot.generation
    }

    /** Computes an active-state transition without publishing it to concurrent readers. */
    @Synchronized
    fun previewSetActive(
        handle: AirPlayVideoStreamHandle,
        isActive: Boolean,
    ): LegacyVideoRouteChange {
        val before = snapshot(handle.type)
        return setActive(handle, isActive).also {
            // This is only a speculative transition. It must not invalidate an independently
            // captured physical route or a legacy handoff proof when the selected route stayed put.
            restore(before, invalidateGeneration = false)
        }
    }

    @Synchronized
    fun clear() {
        candidates.clear()
        active.clear()
        selected.clear()
    }

    private inline fun mutate(type: Int, block: () -> Unit): LegacyVideoRouteChange {
        val previous = selected[type]
        val wasActive = active[type]?.isNotEmpty() == true
        block()
        val candidatesForType = candidates[type]
        val activeForType = active[type]
        if (candidatesForType.isNullOrEmpty()) candidates.remove(type)
        if (activeForType.isNullOrEmpty()) active.remove(type)

        val remainingCandidates = candidates[type]
        val remainingActive = active[type]
        val current = when {
            previous != null && previous in remainingCandidates.orEmpty() &&
                (remainingActive.isNullOrEmpty() || previous in remainingActive) -> previous
            !remainingActive.isNullOrEmpty() -> remainingActive.first()
            !remainingCandidates.isNullOrEmpty() -> remainingCandidates.first()
            else -> null
        }
        if (current == null) selected.remove(type) else selected[type] = current
        // Candidate discovery and repeated frame observation do not change the selected output.
        // Advancing here made an otherwise sound long-lived physical route stale on every frame.
        if (previous !== current) advanceGeneration(type)
        return LegacyVideoRouteChange(
            type = type,
            previousHandle = previous,
            currentHandle = current,
            wasActive = wasActive,
            isActive = active[type]?.isNotEmpty() == true,
        )
    }

    private fun advanceGeneration(type: Int) {
        val previous = generations[type] ?: 0L
        check(previous < Long.MAX_VALUE) { "Legacy video route generation exhausted" }
        generations[type] = previous + 1L
    }
}

/**
 * Success-only two-stage close state for [AndroidMediaSink].
 *
 * Admission closes before waiting for another close attempt. Direct sink resources and the exact
 * external [onClosed] owner advance independently, so a callback failure cannot be hidden by an
 * already-retired sink body. A same-thread reentrant close fails fast; another thread waits on
 * [attemptLock] and observes or retries the resulting state.
 */
internal class AndroidMediaSinkCloseProgress(
    private val admissionGate: AtomicBoolean = AtomicBoolean(false),
) {
    private val attemptLock = Any()
    private val completed = AtomicBoolean(false)
    private var sinkResourcesRetired = false
    private var externalOwnerClosed = false
    private var attemptOwner: Thread? = null

    val isComplete: Boolean
        get() = completed.get()
    val isAdmissionClosed: Boolean
        get() = admissionGate.get()

    fun close(
        retireSinkResources: () -> String?,
        onClosed: () -> Unit,
    ) {
        admissionGate.set(true)
        synchronized(attemptLock) {
            if (completed.get()) return
            val currentThread = Thread.currentThread()
            check(attemptOwner !== currentThread) {
                "Android media sink close is already in flight on this thread"
            }
            check(attemptOwner == null) { "Android media sink close attempt owner was not released" }
            attemptOwner = currentThread
            try {
                if (!sinkResourcesRetired) {
                    retireSinkResources()?.let { throw IllegalStateException(it) }
                    sinkResourcesRetired = true
                }
                if (!externalOwnerClosed) {
                    // Mark success only after the exact callback returns. Exception and Error both
                    // leave this owner pending, and Error is deliberately propagated unchanged.
                    onClosed()
                    externalOwnerClosed = true
                }
                check(sinkResourcesRetired && externalOwnerClosed)
                completed.set(true)
            } finally {
                attemptOwner = null
            }
        }
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Call [close] when the session tears down.
 */
class AndroidMediaSink(
    private val context: Context,
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val mainMediaAudioBufferDurationMs: Int = MainMediaAudioBuffer.DEFAULT_DURATION_MS,
    private val microphoneGainPercent: Int = MicrophoneGain.DEFAULT_PERCENT,
    private val timedVideoPresentation: Boolean = false,
    mediaMetricsMonitor: MediaMetricsMonitor? = null,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val videoSizes: Map<Int, Pair<Int, Int>> = emptyMap(),
    private val mediaTap: MediaTap? = null,
    private val onClosed: () -> Unit = {},
    private val audioFocus: AudioFocusSession? = null,
    /** Decoder-release evidence only; consumers must prove SurfaceTexture/EGL use separately. */
    private val onVideoFrameReleased: ((AndroidVideoFrameReleaseReceipt) -> Unit)? = null,
) : MediaSink {
    private class ScreenActivityBinding(val callback: (Int, Boolean) -> Unit)

    private data class EffectiveSurface(val surface: Surface, val source: Any)

    private data class SurfaceTransition(
        val output: SurfaceLeaseOutput<Surface>,
        val request: SurfaceLeaseStateMachine.ApplyRequest<Surface>?,
        val decoder: VideoDecoder?,
        val installedToken: SurfaceLeaseStateMachine.LeaseToken?,
    )

    private data class ExactOverrideWitness(
        val surface: Surface,
        val source: Any,
        val output: SurfaceLeaseOutput<Surface>,
        val token: SurfaceLeaseStateMachine.LeaseToken,
    )

    private class LegacyHandoffRestoreState {
        var committed = false
        var transition: SurfaceTransition? = null
    }

    private val closed = AtomicBoolean(false)
    private val closeProgress = AndroidMediaSinkCloseProgress(closed)
    private val screenActivityBindingLock = Any()
    @Volatile private var screenActivityBinding =
        onScreenStreamActiveChanged?.let(::ScreenActivityBinding)
    private val surfaceRoutingLock = Any()
    private val legacySurfaceOperationLocks = ConcurrentHashMap<Int, ReentrantLock>()
    private val poisonedVideoTypes = ConcurrentHashMap.newKeySet<Int>()
    private val surfaces = ConcurrentHashMap<AirPlayVideoStreamHandle, Surface>()
    private val surfaceSources = ConcurrentHashMap<AirPlayVideoStreamHandle, Any>()
    private val legacySurfaces = ConcurrentHashMap<Int, Surface>()
    private val legacySurfaceSources = ConcurrentHashMap<Int, Any>()
    private val surfaceRouteGenerations = SurfaceRouteGenerationTracker()
    private val pendingSurfaceReservations = IdentityHashMap<Surface, Any>()
    private data class PhysicalLegacyRouteWitness(
        val configuredPixels: Pair<Int, Int>,
        val source: Any,
        val selected: AirPlayVideoStreamHandle?,
        val routeGeneration: Long,
        val surfaceGeneration: Long,
        val output: SurfaceLeaseOutput<Surface>?,
        val outputToken: SurfaceLeaseStateMachine.LeaseToken?,
        val decoderGeneration: Long?,
        val decoderAtAttach: VideoDecoder?,
    )

    private class PhysicalLegacyRouteClaim(
        val token: Any,
        val surface: Surface,
        val configuredPixels: Pair<Int, Int>,
    ) {
        var source: Any? = null
        var witness: PhysicalLegacyRouteWitness? = null
        var handoffGateToken: Any? = null
        var transferReservation: AndroidVideoSurfaceHandoffReservation? = null
        var revoked = false
        val revocationListeners = LinkedHashMap<Any, () -> Unit>()
    }

    private val physicalLegacyRouteClaims = HashMap<Int, PhysicalLegacyRouteClaim>()
    private val decoderRouteGenerations = IdentityHashMap<AirPlayVideoStreamHandle, Long>()
    private data class PreviewHandoffGateState(
        val token: Any,
        val surface: Surface,
        val physicalClaimToken: Any? = null,
    )
    private val previewHandoffGates = HashMap<Int, PreviewHandoffGateState>()
    private val retiredPreviewHandoffSurfaces = IdentityHashMap<Surface, Unit>()
    /** Deterministic scheduling seam; null in production and never bypasses final admission. */
    @Volatile internal var previewAfterReserveTestHook: (() -> Unit)? = null
    private val surfaceOutputs =
        SurfaceLeaseOutputRegistry<AirPlayVideoStreamHandle, Surface>()
    private val legacyVideoRoutes = LegacyVideoHandleRoutes()
    private val compatibilityEpoch = AirPlayVideoSessionEpoch()
    private val compatibilityHandles =
        ConcurrentHashMap<AirPlayStreamIdentity, AirPlayVideoStreamHandle>()
    private val videoHandleTerminationGate =
        VideoHandleTerminationGate<AirPlayVideoStreamHandle>()
    private val videoDecoders = ConcurrentHashMap<AirPlayVideoStreamHandle, VideoDecoder>()
    /** Keep counter high-watermarks across decoder recreation for the same AirPlay handle. */
    private val videoFrameIdentityLedgers = ConcurrentHashMap<
        AirPlayVideoStreamHandle,
        VideoFrameIdentityLedger<AirPlayVideoStreamHandle, Surface>,
    >()
    private val audioRenderers = ConcurrentHashMap<Int, AudioRenderer>()
    private val mainBufferedPcmOutputCreationLock = Any()
    private val mainBufferedPcmOutputs =
        ConcurrentHashMap.newKeySet<AndroidMainBufferedPcm16Output>()
    private val microphoneUplinks = ConcurrentHashMap<Int, MicrophoneUplink>()
    private val videoRecoveryHandlers =
        ConcurrentHashMap<AirPlayVideoStreamHandle, () -> Boolean>()
    private val pendingVideoCodec = ConcurrentHashMap<AirPlayVideoStreamHandle, VideoCodec>()
    private val videoConfigs =
        ConcurrentHashMap<AirPlayVideoStreamHandle, Pair<VideoCodec, ByteArray>>()
    private val mediaTapVideoGates = ConcurrentHashMap<
        Int,
        OrderedVideoCallbackGate<AirPlayVideoStreamHandle, Pair<VideoCodec, ByteArray>>,
    >()
    private val screenLifecycleCallbackQueues = ConcurrentHashMap<Int, SerializedCallbackQueue>()
    private val forwardedVideoTypes = ConcurrentHashMap<Int, Boolean>()
    private val previewLock = Any()
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor

    init {
        (videoSizes.keys + setOf(LEGACY_MAIN_SCREEN_TYPE, LEGACY_ALT_SCREEN_TYPE)).forEach { type ->
            forwardedVideoTypes[type] = mediaTap?.forwardsVideo(type) == true
        }
        // The constructor predates multiple display streams and denotes the primary screen only.
        if (surface != null) {
            val source = processSurfaceReservations.reserve(surface)
            legacySurfaces[LEGACY_MAIN_SCREEN_TYPE] = surface
            legacySurfaceSources[LEGACY_MAIN_SCREEN_TYPE] = source
            surfaceRouteGenerations.advance(LEGACY_MAIN_SCREEN_TYPE)
        }
        audioFocus?.retain(this)
    }

    /** Exact stream surface API used by session-aware presentation owners. */
    fun setSurface(handle: AirPlayVideoStreamHandle, surface: Surface) {
        acquireSurfaceLease(handle, surface)
    }

    /** Compatibility adapter; session-aware callers should retain and use the stream handle. */
    fun setSurface(identity: AirPlayStreamIdentity, surface: Surface) =
        setSurface(compatibilityHandle(identity), surface)

    /**
     * Installs an exact-stream Surface and returns a generation-bound lease. Replacement waits for
     * proof that the prior generation is no longer referenced, forcing a bounded decoder close if
     * the normal worker acknowledgement does not arrive.
     */
    fun acquireSurfaceLease(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
    ): AndroidVideoSurfaceLease? = withLiveVideoHandle(handle, null) {
        acquireLiveSurfaceLease(handle, surface)
    }

    private fun acquireTransferredIngressSurfaceLease(
        route: PhysicalExternalLegacyRouteLease,
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
    ): AndroidVideoSurfaceLease? {
        require(handle.type == route.type) { "Transferred ingress must target the claimed type" }
        return withLiveVideoHandle(handle, null) {
            acquireLiveSurfaceLease(handle, surface, route.token)
        }
    }

    private fun acquireLiveSurfaceLease(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
        transferredClaimToken: Any? = null,
    ): AndroidVideoSurfaceLease? = legacySurfaceOperationLock(handle.type).withLock {
        checkVideoTypeNotPoisoned(handle.type)
        val reservation = synchronized(surfaceRoutingLock) {
            if (closed.get()) return null
            check(exactRouteMutationAuthorizedLocked(handle.type, transferredClaimToken)) {
                "Physical external route or EGL handoff owns type=${handle.type}"
            }
            val old = surfaces[handle]?.let { oldSurface ->
                oldSurface to checkNotNull(surfaceSources[handle])
            }
            reserveUniqueSurfaceLocked(surface) to old
        }
        val source = reservation.first
        val oldRegistration = reservation.second
        var committed = false
        var previousRetired = false
        try {
            val (output, replacementResult) = retireInstalledBinding(
                handle,
                "replace exact Surface for $handle",
            )
            previousRetired = true
            val transition = synchronized(surfaceRoutingLock) {
                check(exactRouteMutationAuthorizedLocked(handle.type, transferredClaimToken)) {
                    "Physical external route or EGL handoff owns type=${handle.type}"
                }
                check(!closed.get() && !videoHandleTerminationGate.isTerminal(handle)) {
                    "Video stream terminated before Surface installation"
                }
                check(processSurfaceReservations.owner(surface) === source) { "Surface reservation was lost" }
                val installed = installBindingLocked(
                    handle,
                    output,
                    EffectiveSurface(surface, source),
                )
                surfaces[handle] = surface
                surfaceSources[handle] = source
                surfaceRouteGenerations.advance(handle.type)
                commitSurfaceReservationLocked(surface, source)
                oldRegistration?.let { (oldSurface, oldSource) ->
                    releaseSurfaceReservationLocked(oldSurface, oldSource)
                }
                committed = true
                installed
            }
            publishInstalledBinding(handle, transition)
            val token = checkNotNull(transition.installedToken)
            AndroidVideoSurfaceLease.issue(
                handle = handle,
                surface = surface,
                generation = token.generation,
                replacementRetirementResult = replacementResult,
                retireAction = { timeoutMillis ->
                    retireExactSurface(handle, surface, source, transition.output, token, timeoutMillis)
                },
            )
        } catch (error: Throwable) {
            if (!committed) {
                synchronized(surfaceRoutingLock) { releaseSurfaceReservationLocked(surface, source) }
            }
            if (previousRetired && !committed && !closed.get()) {
                try {
                    reconcileBindingToDesired(handle, "rollback exact Surface replacement $handle")
                } catch (rollback: Throwable) {
                    poisonedVideoTypes += handle.type
                    error.addSuppressed(rollback)
                }
            }
            throw error
        }
    }

    fun acquireSurfaceLease(
        identity: AirPlayStreamIdentity,
        surface: Surface,
    ): AndroidVideoSurfaceLease? = acquireSurfaceLease(compatibilityHandle(identity), surface)

    /**
     * Legacy Host adapter. At most one full identity is selected for each type; a Surface is
     * never installed into two same-type decoders.
     */
    fun setSurface(type: Int, surface: Surface) {
        setLegacySurface(type, surface, previewAdmission = false)
    }

    /**
     * Installs a type-111 physical external route against configured cluster pixels, including
     * before SETUP has selected a video stream. The returned lease is the evidence: a successful
     * type-only [setSurface] is not. The provisional lease proves exclusive route registration,
     * not decoder liveness; a first stream selected before EGL transfer must bind this same
     * Surface/source generation.
     * It owns a per-type exclusion before decoder replacement starts, so no preview or exact
     * route can slip into the interval between retirement and publication.
     */
    fun acquirePhysicalExternalLegacyRouteLease(
        type: Int,
        surface: Surface,
    ): PhysicalExternalLegacyRouteLease? {
        require(type == LEGACY_ALT_SCREEN_TYPE) { "Physical external route requires type-111" }
        return legacySurfaceOperationLock(type).withLock {
            val claim = synchronized(surfaceRoutingLock) {
                if (closed.get() || type in poisonedVideoTypes ||
                    physicalLegacyRouteClaims.containsKey(type) ||
                    previewHandoffGates.containsKey(type)
                ) return@withLock null
                val configuredPixels = videoSizes[type]
                if (configuredPixels == null || configuredPixels.first <= 0 ||
                    configuredPixels.second <= 0
                ) return@withLock null
                val selected = legacyVideoRoutes.selected(type)
                if (selected != null &&
                    (videoHandleTerminationGate.isTerminal(selected) || surfaces[selected] != null)
                ) return@withLock null
                PhysicalLegacyRouteClaim(Any(), surface, configuredPixels).also {
                    physicalLegacyRouteClaims[type] = it
                }
            }
            try {
                check(setLegacySurface(type, surface, previewAdmission = false, claim.token)) {
                    "Media sink closed before physical external route installation"
                }
                synchronized(surfaceRoutingLock) {
                    check(physicalLegacyRouteClaims[type] === claim &&
                        legacySurfaces[type] === surface
                    ) { "Physical external route changed during installation" }
                    val source = checkNotNull(legacySurfaceSources[type])
                    val selected = legacyVideoRoutes.selected(type)
                    check(processSurfaceReservations.owner(surface) === source) {
                        "Physical external Surface reservation was lost"
                    }
                    val output = selected?.let { handle ->
                        check(!videoHandleTerminationGate.isTerminal(handle) &&
                            surfaces[handle] == null
                        ) { "Physical external route selected an unavailable stream" }
                        checkNotNull(surfaceOutputs[handle]).also {
                            check(it.source === source && it.token?.let(it.state::isCurrent) == true) {
                                "Physical external Surface did not bind the selected stream"
                            }
                        }
                    }
                    if (selected == null) {
                        check(surfaceOutputs.entriesSnapshot().none { it.second.source === source }) {
                            "Provisional physical route unexpectedly owns a decoder output"
                        }
                    }
                    claim.witness = PhysicalLegacyRouteWitness(
                        claim.configuredPixels,
                        source,
                        selected,
                        legacyVideoRoutes.generation(type),
                        surfaceRouteGenerations.current(type),
                        output,
                        output?.token,
                        selected?.let { decoderRouteGenerations[it] ?: 0L },
                        selected?.let(videoDecoders::get),
                    )
                    PhysicalExternalLegacyRouteLease(
                        this, type, surface, claim.token,
                        claim.configuredPixels.first, claim.configuredPixels.second,
                    )
                }
            } catch (failure: Throwable) {
                // A failed attach may already have installed a route. Do not free the exclusion
                // until its exact decoder binding and process reservation are proven retired.
                runCatching { clearLegacySurface(type, surface, claim.token) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                val stillOwned = synchronized(surfaceRoutingLock) {
                    val source = claim.source
                    val owned = legacySurfaces[type] === surface ||
                        (source != null && processSurfaceReservations.owner(surface) === source)
                    if (!owned) physicalLegacyRouteClaims.remove(type, claim)
                    owned
                }
                if (stillOwned) {
                    Log.e(TAG, "physical external route attach is uncertain; retaining cleanup lease", failure)
                    return@withLock PhysicalExternalLegacyRouteLease(
                        this, type, surface, claim.token,
                        claim.configuredPixels.first, claim.configuredPixels.second,
                    )
                }
                throw failure
            }
        }
    }

    class PhysicalExternalLegacyRouteLease internal constructor(
        private val sink: AndroidMediaSink,
        val type: Int,
        val surface: Surface,
        internal val token: Any,
        val configuredWidthPixels: Int,
        val configuredHeightPixels: Int,
    ) : Closeable {
        /** Exact physical authority: legacy binding before transfer, gate/reservation after it. */
        val isCurrent: Boolean get() = sink.isPhysicalExternalRouteCurrent(this)

        /** Diagnostic only: pre-SETUP route proof may be current while no decoder exists yet. */
        val decoderAttached: Boolean get() = sink.isPhysicalExternalDecoderAttached(this)

        /** The only exact type-111 ingress route admitted after this lease entered EGL handoff. */
        fun acquireTransferredIngressSurfaceLease(
            handle: AirPlayVideoStreamHandle,
            surface: Surface,
        ): AndroidVideoSurfaceLease? = sink.acquireTransferredIngressSurfaceLease(this, handle, surface)

        /** One-shot revocation, delivered asynchronously and never under a sink routing lock. */
        fun subscribeRevocation(onRevoked: () -> Unit): Closeable =
            sink.subscribePhysicalExternalRouteRevocation(this, onRevoked)

        /** Retryable: an uncertain decoder retirement leaves this exact lease held. */
        override fun close() = sink.retirePhysicalExternalRoute(this)
    }

    private fun isPhysicalExternalRouteCurrent(lease: PhysicalExternalLegacyRouteLease): Boolean {
        val (current, callbacks) = legacySurfaceOperationLock(lease.type).withLock {
            synchronized(surfaceRoutingLock) {
                val claim = physicalLegacyRouteClaims[lease.type]
                if (claim?.token !== lease.token || claim.surface !== lease.surface ||
                    claim.revoked
                ) return@synchronized false to emptyList<() -> Unit>()
                if (isPhysicalExternalRouteWitnessCurrentLocked(lease.type, claim)) {
                    true to emptyList()
                } else false to revokePhysicalRouteClaimLocked(claim)
            }
        }
        dispatchPhysicalRouteRevocations(callbacks)
        return current
    }

    private fun isPhysicalExternalDecoderAttached(lease: PhysicalExternalLegacyRouteLease): Boolean =
        legacySurfaceOperationLock(lease.type).withLock {
            synchronized(surfaceRoutingLock) {
                val claim = physicalLegacyRouteClaims[lease.type]
                if (claim?.token !== lease.token || claim.surface !== lease.surface ||
                    claim.revoked || !isPhysicalExternalRouteWitnessCurrentLocked(lease.type, claim)
                ) return@synchronized false
                if (claim.transferReservation != null) false
                else claim.witness?.selected?.let { videoDecoders[it]?.isLiveForPhysicalRoute } == true
            }
        }

    private fun subscribePhysicalExternalRouteRevocation(
        lease: PhysicalExternalLegacyRouteLease,
        onRevoked: () -> Unit,
    ): Closeable {
        val listenerToken = Any()
        val immediateCallbacks = legacySurfaceOperationLock(lease.type).withLock {
            synchronized(surfaceRoutingLock) {
                val claim = physicalLegacyRouteClaims[lease.type]
                if (claim?.token !== lease.token || claim.surface !== lease.surface ||
                    claim.revoked
                ) listOf(onRevoked)
                else if (!isPhysicalExternalRouteWitnessCurrentLocked(lease.type, claim)) {
                    revokePhysicalRouteClaimLocked(claim) + onRevoked
                } else {
                    claim.revocationListeners[listenerToken] = onRevoked
                    emptyList()
                }
            }
        }
        dispatchPhysicalRouteRevocations(immediateCallbacks)
        return Closeable {
            synchronized(surfaceRoutingLock) {
                physicalLegacyRouteClaims[lease.type]
                    ?.takeIf { it.token === lease.token }
                    ?.revocationListeners?.remove(listenerToken)
            }
        }
    }

    private fun notifyPhysicalRouteIfInvalid(type: Int) {
        val callbacks = legacySurfaceOperationLock(type).withLock {
            synchronized(surfaceRoutingLock) {
                val claim = physicalLegacyRouteClaims[type] ?: return@synchronized emptyList()
                if (claim.revoked || isPhysicalExternalRouteWitnessCurrentLocked(type, claim)) {
                    emptyList()
                } else revokePhysicalRouteClaimLocked(claim)
            }
        }
        dispatchPhysicalRouteRevocations(callbacks)
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun revokePhysicalRouteClaimLocked(claim: PhysicalLegacyRouteClaim): List<() -> Unit> {
        if (claim.revoked) return emptyList()
        claim.revoked = true
        return claim.revocationListeners.values.toList().also {
            claim.revocationListeners.clear()
        }
    }

    private fun dispatchPhysicalRouteRevocations(callbacks: List<() -> Unit>) {
        if (callbacks.isEmpty()) return
        physicalRouteRevocationExecutor.execute {
            callbacks.forEach { callback ->
                runCatching(callback).exceptionOrNull()?.let { error ->
                    Log.e(TAG, "physical external route revocation callback failed", error)
                }
            }
        }
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun isPhysicalExternalRouteWitnessCurrentLocked(
        type: Int,
        claim: PhysicalLegacyRouteClaim,
    ): Boolean {
        val witness = claim.witness ?: return false
        val selected = witness.selected
        val registrationCurrent = !closed.get() && type !in poisonedVideoTypes &&
            videoSizes[type] == witness.configuredPixels &&
            processSurfaceReservations.owner(claim.surface) === witness.source
        if (!registrationCurrent) return false
        claim.transferReservation?.let { reservation ->
            val gate = previewHandoffGates[type]
            return gate != null && gate.token === claim.handoffGateToken &&
                gate.surface === claim.surface && gate.physicalClaimToken === claim.token &&
                reservation.matches(this, type, claim.surface) &&
                legacySurfaces[type] == null && legacySurfaceSources[type] == null
        }
        if (legacySurfaces[type] !== claim.surface ||
            legacySurfaceSources[type] !== witness.source ||
            legacyVideoRoutes.generation(type) != witness.routeGeneration ||
            surfaceRouteGenerations.current(type) != witness.surfaceGeneration
        ) return false
        if (selected == null) {
            return legacyVideoRoutes.selected(type) == null &&
                surfaceOutputs.entriesSnapshot().none { it.second.source === witness.source }
        }
        val output = witness.output ?: return false
        val token = witness.outputToken ?: return false
        val decoder = videoDecoders[selected]
        return legacyVideoRoutes.selected(type) === selected &&
            !videoHandleTerminationGate.isTerminal(selected) &&
            surfaces[selected] == null && surfaceOutputs[selected] === output &&
            output.source === witness.source && output.token === token &&
            output.state.isCurrent(token) &&
            (decoderRouteGenerations[selected] ?: 0L) == witness.decoderGeneration &&
            (witness.decoderAtAttach == null || decoder === witness.decoderAtAttach) &&
            (decoder == null || decoder.isLiveForPhysicalRoute)
    }

    private fun retirePhysicalExternalRoute(lease: PhysicalExternalLegacyRouteLease) {
        val type = lease.type
        try {
            val callbacks = legacySurfaceOperationLock(type).withLock {
                val claim = synchronized(surfaceRoutingLock) {
                    physicalLegacyRouteClaims[type]
                        ?.takeIf { it.token === lease.token && it.surface === lease.surface }
                } ?: return@withLock emptyList()
                check(synchronized(surfaceRoutingLock) { claim.handoffGateToken == null }) {
                    "Physical external route transfer is still held by the EGL handoff gate"
                }
                val source = claim.source ?: claim.witness?.source
                val registrationIsOwned = synchronized(surfaceRoutingLock) {
                    legacySurfaces[type] === lease.surface &&
                        source != null && legacySurfaceSources[type] === source
                }
                if (registrationIsOwned && !closed.get()) {
                    clearLegacySurface(type, lease.surface, lease.token)
                }
                synchronized(surfaceRoutingLock) {
                    if (source != null && pendingSurfaceReservations[lease.surface] === source) {
                        releaseSurfaceReservationLocked(lease.surface, source)
                    }
                    check(legacySurfaces[type] !== lease.surface ||
                        (source != null && legacySurfaceSources[type] !== source)
                    ) { "Physical external route is still registered" }
                    check(if (source == null) !processSurfaceReservations.contains(lease.surface)
                        else processSurfaceReservations.owner(lease.surface) !== source
                    ) {
                        "Physical external route reservation is still held; retry retirement"
                    }
                    val revoked = revokePhysicalRouteClaimLocked(claim)
                    physicalLegacyRouteClaims.remove(type, claim)
                    revoked
                }
            }
            dispatchPhysicalRouteRevocations(callbacks)
        } finally {
            notifyPhysicalRouteIfInvalid(type)
        }
    }

    /**
     * Atomic preview admission against the same process reservation used by a physical handoff.
     * A busy exact Surface returns false without mutating the route or throwing into the UI.
     * Ordinary [setSurface] keeps its existing strict duplicate-surface behavior.
     */
    fun trySetSurfaceForPreview(type: Int, surface: Surface): Boolean =
        setLegacySurface(type, surface, previewAdmission = true)

    /**
     * One type-wide preview exclusion starts before the legacy binding is captured. Its token is
     * retained through decoder retirement, EGL use and final reservation close. This closes the
     * gap where a different WebDisplay Session might attach a successor Surface during handoff.
     */
    fun beginPhysicalSurfaceHandoff(type: Int, surface: Surface): PreviewPhysicalHandoffGate? =
        legacySurfaceOperationLock(type).withLock {
            synchronized(surfaceRoutingLock) {
                if (closed.get() || type in poisonedVideoTypes ||
                    previewHandoffGates.containsKey(type) ||
                    physicalLegacyRouteClaims.containsKey(type)
                ) return null
                val token = Any()
                previewHandoffGates[type] = PreviewHandoffGateState(token, surface)
                PreviewPhysicalHandoffGate(this, type, surface, token)
            }
        }

    /**
     * Transfers an exact physical route into the existing EGL handoff protocol without opening a
     * type-wide admission gap. A type/surface-only caller cannot transfer someone else's claim.
     * The caller must retain both this gate and the route lease until the handoff reservation is
     * fully retired; [PhysicalExternalLegacyRouteLease.close] then releases the route exclusion.
     */
    fun beginPhysicalSurfaceHandoff(
        lease: PhysicalExternalLegacyRouteLease,
    ): PreviewPhysicalHandoffGate? = legacySurfaceOperationLock(lease.type).withLock {
        synchronized(surfaceRoutingLock) {
            val claim = physicalLegacyRouteClaims[lease.type]
                ?.takeIf { it.token === lease.token && it.surface === lease.surface }
                ?: return null
            if (previewHandoffGates.containsKey(lease.type) ||
                !isPhysicalExternalRouteWitnessCurrentLocked(lease.type, claim)
            ) return null
            val token = Any()
            claim.handoffGateToken = token
            previewHandoffGates[lease.type] = PreviewHandoffGateState(
                token, lease.surface, lease.token,
            )
            PreviewPhysicalHandoffGate(this, lease.type, lease.surface, token)
        }
    }

    class PreviewPhysicalHandoffGate internal constructor(
        private val sink: AndroidMediaSink,
        private val type: Int,
        private val surface: Surface,
        private val token: Any,
    ) : Closeable {
        private var reserved = false
        private var closed = false

        val isHeld: Boolean get() = sink.isPreviewHandoffGateCurrent(type, surface, token)

        @Synchronized fun markReserved() {
            check(!closed && isHeld) { "Preview handoff gate is no longer current" }
            reserved = true
        }

        @Synchronized override fun close() {
            if (closed) return
            sink.endPreviewHandoffGate(type, surface, token, reserved)
            closed = true
        }
    }

    private fun isPreviewHandoffGateCurrent(type: Int, surface: Surface, token: Any): Boolean =
        synchronized(surfaceRoutingLock) {
            !closed.get() &&
                previewHandoffGates[type]?.let { it.token === token && it.surface === surface } == true
        }

    private fun endPreviewHandoffGate(
        type: Int,
        surface: Surface,
        token: Any,
        wasReserved: Boolean,
    ) {
        synchronized(surfaceRoutingLock) {
            val current = previewHandoffGates[type]
            check(current?.token === token && current.surface === surface) {
                "Preview handoff gate belongs to another sink generation"
            }
            if (wasReserved) retiredPreviewHandoffSurfaces[surface] = Unit
            previewHandoffGates.remove(type)
            current.physicalClaimToken?.let { claimToken ->
                physicalLegacyRouteClaims[type]?.takeIf { it.token === claimToken &&
                    it.handoffGateToken === token
                }?.handoffGateToken = null
            }
        }
        notifyPhysicalRouteIfInvalid(type)
    }

    private fun setLegacySurface(
        type: Int,
        surface: Surface,
        previewAdmission: Boolean,
        physicalClaimToken: Any? = null,
    ): Boolean {
        checkVideoTypeNotPoisoned(type)
        val operationLock = legacySurfaceOperationLock(type)
        val reservation = operationLock.withLock {
            synchronized(surfaceRoutingLock) {
                if (closed.get()) return false
                if (routeMutationBlockedLocked(type, physicalClaimToken)) {
                    if (previewAdmission) return false
                    error("Physical external route or EGL handoff owns type=$type")
                }
                val old = legacySurfaces[type]?.let { oldSurface ->
                    oldSurface to checkNotNull(legacySurfaceSources[type])
                }
                if (previewAdmission && (previewHandoffGates.containsKey(type) ||
                    retiredPreviewHandoffSurfaces.containsKey(surface))
                ) return false
                val source = if (previewAdmission) {
                    try {
                        reserveUniqueSurfaceLocked(surface)
                    } catch (_: IllegalArgumentException) {
                        // The reserve attempt and competing handoff use one identity registry;
                        // this is a failed atomic claim, not a racy advisory state check.
                        return false
                    }
                } else reserveUniqueSurfaceLocked(surface)
                if (physicalClaimToken != null) {
                    physicalLegacyRouteClaims[type]
                        ?.takeIf { it.token === physicalClaimToken }
                        ?.source = source
                }
                Triple(source, legacyVideoRoutes.selected(type), old)
            }
        }
        val source = reservation.first
        val selected = reservation.second
        val oldRegistration = reservation.third
        if (previewAdmission) {
            try {
                previewAfterReserveTestHook?.invoke()
            } catch (failure: Throwable) {
                synchronized(surfaceRoutingLock) {
                    releaseSurfaceReservationLocked(surface, source)
                }
                throw failure
            }
        }
        var committed = false
        if (selected == null) {
            operationLock.withLock {
                synchronized(surfaceRoutingLock) {
                    if (closed.get() || processSurfaceReservations.owner(surface) !== source ||
                        routeMutationBlockedLocked(type, physicalClaimToken) ||
                        (previewAdmission && (previewHandoffGates.containsKey(type) ||
                            retiredPreviewHandoffSurfaces.containsKey(surface)))
                    ) {
                        releaseSurfaceReservationLocked(surface, source)
                    } else {
                        if (legacyVideoRoutes.selected(type) != null ||
                            !legacyRegistrationMatchesLocked(type, oldRegistration)
                        ) {
                            releaseSurfaceReservationLocked(surface, source)
                            if (previewAdmission) return false
                            error("Legacy Surface registration changed; retry the operation")
                        }
                        legacySurfaces[type] = surface
                        legacySurfaceSources[type] = source
                        surfaceRouteGenerations.advance(type)
                        commitSurfaceReservationLocked(surface, source)
                        oldRegistration?.let { (oldSurface, oldSource) ->
                            releaseSurfaceReservationLocked(oldSurface, oldSource)
                        }
                        committed = true
                    }
                }
            }
            return committed
        }
        var previousRetired = false
        var previewRejected = false
        try {
            val installed = withLiveVideoHandle(checkNotNull(selected), false) {
                operationLock.withLock {
                    if (previewAdmission && synchronized(surfaceRoutingLock) {
                            routeMutationBlockedLocked(type, physicalClaimToken) ||
                                retiredPreviewHandoffSurfaces.containsKey(surface) ||
                                legacyVideoRoutes.selected(type) !== selected ||
                                !legacyRegistrationMatchesLocked(type, oldRegistration)
                        }
                    ) {
                        previewRejected = true
                        return@withLock false
                    }
                    check(legacyVideoRoutes.selected(type) === selected) {
                        "Legacy Surface route changed; retry the operation"
                    }
                    check(synchronized(surfaceRoutingLock) {
                        !routeMutationBlockedLocked(type, physicalClaimToken)
                    }) { "Physical external route or EGL handoff owns type=$type" }
                    check(!closed.get() && !videoHandleTerminationGate.isTerminal(selected) &&
                        processSurfaceReservations.owner(surface) === source
                    ) { "Video stream terminated before legacy Surface installation" }
                    check(synchronized(surfaceRoutingLock) {
                        legacyRegistrationMatchesLocked(type, oldRegistration)
                    }) { "Legacy Surface registration changed; retry the operation" }
                    val hasExact = synchronized(surfaceRoutingLock) { surfaces[selected] != null }
                    if (hasExact) {
                        synchronized(surfaceRoutingLock) {
                            check(!routeMutationBlockedLocked(type, physicalClaimToken)) {
                                "Physical external route or EGL handoff owns type=$type"
                            }
                            check(legacyRegistrationMatchesLocked(type, oldRegistration)) {
                                "Legacy Surface registration changed; retry the operation"
                            }
                            legacySurfaces[type] = surface
                            legacySurfaceSources[type] = source
                            surfaceRouteGenerations.advance(type)
                            commitSurfaceReservationLocked(surface, source)
                            oldRegistration?.let { (oldSurface, oldSource) ->
                                releaseSurfaceReservationLocked(oldSurface, oldSource)
                            }
                            committed = true
                        }
                    } else {
                        val (output, _) = retireInstalledBinding(
                            selected,
                            "replace legacy Surface type=$type",
                        )
                        previousRetired = true
                        val transition = synchronized(surfaceRoutingLock) {
                            check(!routeMutationBlockedLocked(type, physicalClaimToken)) {
                                "Physical external route or EGL handoff owns type=$type"
                            }
                            check(!closed.get() &&
                                !videoHandleTerminationGate.isTerminal(selected)
                            ) { "Video stream terminated before legacy Surface installation" }
                            check(processSurfaceReservations.owner(surface) === source) {
                                "Surface reservation was lost"
                            }
                            check(legacyRegistrationMatchesLocked(type, oldRegistration)) {
                                "Legacy Surface registration changed; retry the operation"
                            }
                            val next = installBindingLocked(
                                selected,
                                output,
                                EffectiveSurface(surface, source),
                            )
                            legacySurfaces[type] = surface
                            legacySurfaceSources[type] = source
                            surfaceRouteGenerations.advance(type)
                            commitSurfaceReservationLocked(surface, source)
                            oldRegistration?.let { (oldSurface, oldSource) ->
                                releaseSurfaceReservationLocked(oldSurface, oldSource)
                            }
                            committed = true
                            next
                        }
                        publishInstalledBinding(selected, transition)
                    }
                    true
                }
            }
            if (previewRejected) return false
            check(installed) { "Selected video stream is terminal; Surface was not installed" }
        } catch (error: Throwable) {
            if (previousRetired && !committed && !closed.get()) {
                try {
                    reconcileBindingToDesired(selected, "rollback legacy Surface type=$type")
                } catch (rollback: Throwable) {
                    poisonedVideoTypes += type
                    error.addSuppressed(rollback)
                }
            }
            throw error
        } finally {
            if (!committed) {
                synchronized(surfaceRoutingLock) { releaseSurfaceReservationLocked(surface, source) }
            }
        }
        return committed
    }

    fun clearSurface(handle: AirPlayVideoStreamHandle, surface: Surface) {
        withLiveVideoHandle(handle, Unit) {
            legacySurfaceOperationLock(handle.type).withLock {
                checkVideoTypeNotPoisoned(handle.type)
                check(synchronized(surfaceRoutingLock) {
                    !routeMutationBlockedLocked(handle.type, null)
                }) { "Physical external route or EGL handoff owns type=${handle.type}" }
                val source = synchronized(surfaceRoutingLock) {
                    surfaceSources[handle].takeIf { surfaces[handle] === surface }
                }
                if (source != null) {
                    val fallback = synchronized(surfaceRoutingLock) {
                        if (!legacyVideoRoutes.isSelected(handle)) null
                        else legacySurfaces[handle.type]?.let { legacy ->
                            require(legacy !== surface) {
                                "Exact-to-legacy handoff cannot reuse the same Surface instance"
                            }
                            val fallbackSource = checkNotNull(legacySurfaceSources[handle.type])
                            EffectiveSurface(legacy, fallbackSource)
                        }
                    }
                    val (output, _) = retireInstalledBinding(
                        handle,
                        "clear exact Surface for $handle",
                    )
                    var fallbackFailure: Throwable? = null
                    val transition = synchronized(surfaceRoutingLock) {
                        check(surfaces[handle] === surface && surfaceSources[handle] === source) {
                            "Exact Surface changed while clearing"
                        }
                        surfaces.remove(handle)
                        surfaceSources.remove(handle)
                        surfaceRouteGenerations.advance(handle.type)
                        releaseSurfaceReservationLocked(surface, source)
                        try {
                            fallback?.let {
                                check(!closed.get() &&
                                    !videoHandleTerminationGate.isTerminal(handle)
                                ) { "Video stream terminated before fallback Surface installation" }
                                installBindingLocked(handle, output, it)
                            }
                        } catch (error: Throwable) {
                            fallbackFailure = error
                            null
                        }
                    }
                    if (fallbackFailure != null) {
                        poisonedVideoTypes += handle.type
                        Log.e(TAG, "fallback Surface installation failed handle=$handle", fallbackFailure)
                    } else if (transition != null) publishInstalledBinding(handle, transition)
                    else reconcileSurfaceDecoder(handle, output)
                }
            }
        }
    }

    fun clearSurface(identity: AirPlayStreamIdentity, surface: Surface) =
        clearSurface(compatibilityHandle(identity), surface)

    fun clearSurface(type: Int, surface: Surface) = clearLegacySurface(type, surface, null)

    private fun clearLegacySurface(type: Int, surface: Surface, physicalClaimToken: Any?) {
        checkVideoTypeNotPoisoned(type)
        val operationLock = legacySurfaceOperationLock(type)
        val observed = operationLock.withLock {
            synchronized(surfaceRoutingLock) {
                if (legacySurfaces[type] !== surface) return
                check(!routeMutationBlockedLocked(type, physicalClaimToken)) {
                    "Physical external route or EGL handoff owns type=$type"
                }
                legacyVideoRoutes.selected(type) to checkNotNull(legacySurfaceSources[type])
            }
        }
        val selected = observed.first
        val source = observed.second
        if (selected == null) {
            operationLock.withLock {
                synchronized(surfaceRoutingLock) {
                    if (legacyRegistrationMatchesLocked(type, surface to source)) {
                        legacySurfaces.remove(type)
                        legacySurfaceSources.remove(type)
                        surfaceRouteGenerations.advance(type)
                        releaseSurfaceReservationLocked(surface, source)
                    }
                }
            }
            return
        }
        val cleared = withLiveVideoHandle(selected, false) {
            operationLock.withLock {
                if (!synchronized(surfaceRoutingLock) {
                        legacyRegistrationMatchesLocked(type, surface to source)
                    }
                ) return@withLock true
                check(legacyVideoRoutes.selected(type) === selected) {
                    "Legacy Surface route changed; retry the operation"
                }
                val hasExact = synchronized(surfaceRoutingLock) { surfaces[selected] != null }
                if (hasExact) {
                    synchronized(surfaceRoutingLock) {
                        check(legacyRegistrationMatchesLocked(type, surface to source)) {
                            "Legacy Surface generation changed while clearing"
                        }
                        legacySurfaces.remove(type)
                        legacySurfaceSources.remove(type)
                        surfaceRouteGenerations.advance(type)
                        releaseSurfaceReservationLocked(surface, source)
                    }
                } else {
                    val (output, _) = retireInstalledBinding(
                        selected,
                        "clear legacy Surface type=$type",
                    )
                    synchronized(surfaceRoutingLock) {
                        check(legacyRegistrationMatchesLocked(type, surface to source)) {
                            "Legacy Surface generation changed while clearing"
                        }
                        legacySurfaces.remove(type)
                        legacySurfaceSources.remove(type)
                        surfaceRouteGenerations.advance(type)
                        releaseSurfaceReservationLocked(surface, source)
                    }
                    reconcileSurfaceDecoder(selected, output)
                }
                true
            }
        }
        check(cleared) { "Selected video stream is terminal; Surface was not cleared" }
    }

    /**
     * Captures the exact current legacy Host registration. A later type-only clear is not proof
     * that this generation's decoder released [surface]. A null result means there is no matching
     * registration at the instant of capture; callers must still inspect the process reservation
     * or acquire a reservation before handing the Surface to another renderer.
     */
    fun captureLegacySurfaceLease(
        type: Int,
        surface: Surface,
        timeoutMillis: Long = DEFAULT_SURFACE_RETIREMENT_MS,
    ): AndroidLegacyVideoSurfaceLease? {
        val operationLock = legacySurfaceOperationLock(type)
        when (tryLockBeforeDeadline(operationLock, BoundedOperationDeadline(timeoutMillis))) {
            BoundedLockResult.ACQUIRED -> Unit
            BoundedLockResult.TIMED_OUT ->
                throw IllegalStateException("Legacy Surface capture timed out; ownership is uncertain")
            BoundedLockResult.INTERRUPTED ->
                throw InterruptedException("Legacy Surface capture interrupted; ownership is uncertain")
        }
        return try {
            synchronized(surfaceRoutingLock) {
                checkVideoTypeNotPoisoned(type)
                check(!closed.get()) { "Media sink close is in progress; Surface ownership is uncertain" }
                val physicalClaim = physicalLegacyRouteClaims[type]
                if (physicalClaim != null &&
                    previewHandoffGates[type]?.physicalClaimToken !== physicalClaim.token
                ) return null
                if (legacySurfaces[type] !== surface) return null
                val source = legacySurfaceSources[type] ?: return null
                check(processSurfaceReservations.owner(surface) === source) {
                    "Legacy Surface registration lost its process reservation"
                }
                val selected = legacyVideoRoutes.selected(type)
                val routeGeneration = legacyVideoRoutes.generation(type)
                val surfaceGeneration = surfaceRouteGenerations.current(type)
                val output = selected?.let(surfaceOutputs::get)
                val exactOverride = selected?.let { handle ->
                    surfaces[handle]?.let { exactSurface ->
                        val exactSource = checkNotNull(surfaceSources[handle])
                        val exactOutput = checkNotNull(output)
                        val exactToken = checkNotNull(exactOutput.token)
                        check(exactOutput.source === exactSource) {
                            "Selected exact Surface has no matching decoder binding"
                        }
                        ExactOverrideWitness(exactSurface, exactSource, exactOutput, exactToken)
                    }
                }
                val installedToken = output?.takeIf { it.source === source }?.token
                check(output?.source !== source || installedToken != null) {
                    "Legacy Surface output source exists without a decoder generation"
                }
                check(surfaceOutputs.entriesSnapshot().all { (handle, candidate) ->
                    candidate.source !== source || handle === selected
                }) { "Legacy Surface is still referenced by another decoder output" }
                val proof = LegacySurfaceBindingProof.issue(
                    this,
                    type,
                    surface,
                    source,
                    selected,
                    routeGeneration,
                    surfaceGeneration,
                    installedToken,
                )
                AndroidLegacyVideoSurfaceLease.issue(proof) { timeoutMillis ->
                    retireLegacySurfaceForHandoff(
                        type,
                        surface,
                        source,
                        selected,
                        routeGeneration,
                        surfaceGeneration,
                        output.takeIf { installedToken != null },
                        installedToken,
                        exactOverride,
                        proof,
                        timeoutMillis,
                    )
                }
            }
        } finally {
            operationLock.unlock()
            notifyPhysicalRouteIfInvalid(type)
        }
    }

    /**
     * Atomic empty-Surface claim for a Host that observed no legacy registration. It fails closed
     * if any sink is attaching, closing, or already owns this exact Surface instance.
     */
    fun tryReserveUnboundSurfaceForHandoff(
        type: Int,
        surface: Surface,
    ): AndroidVideoSurfaceHandoffReservation? = synchronized(surfaceRoutingLock) {
        if (closed.get() || physicalLegacyRouteClaims.containsKey(type) ||
            processSurfaceReservations.contains(surface)
        ) return@synchronized null
        val source = try {
            processSurfaceReservations.reserve(surface)
        } catch (_: IllegalArgumentException) {
            // Another sink won the race after the advisory contains() snapshot.
            return@synchronized null
        }
        issueHandoffReservation(type, surface, source)
    }

    private fun retireLegacySurfaceForHandoff(
        type: Int,
        surface: Surface,
        source: Any,
        selectedAtCapture: AirPlayVideoStreamHandle?,
        routeGenerationAtCapture: Long,
        surfaceGenerationAtCapture: Long,
        outputAtCapture: SurfaceLeaseOutput<Surface>?,
        tokenAtCapture: SurfaceLeaseStateMachine.LeaseToken?,
        exactOverrideAtCapture: ExactOverrideWitness?,
        proof: LegacySurfaceBindingProof<Surface>,
        timeoutMillis: Long,
    ): AndroidLegacySurfaceHandoffResult {
        val deadline = BoundedOperationDeadline(timeoutMillis)
        val operationLock = legacySurfaceOperationLock(type)
        when (tryLockBeforeDeadline(operationLock, deadline)) {
            BoundedLockResult.ACQUIRED -> Unit
            BoundedLockResult.TIMED_OUT -> return AndroidLegacySurfaceHandoffResult.Uncertain(
                AndroidVideoSurfaceRetirementResult.DECODER_CLOSE_TIMED_OUT,
            )
            BoundedLockResult.INTERRUPTED -> return AndroidLegacySurfaceHandoffResult.Uncertain(
                AndroidVideoSurfaceRetirementResult.INTERRUPTED,
            )
        }
        try {
            if (type in poisonedVideoTypes) return AndroidLegacySurfaceHandoffResult.Failed(
                IllegalStateException("Video output route is poisoned; ownership is uncertain"),
            )
            val transition = synchronized(surfaceRoutingLock) {
                if (closed.get()) return AndroidLegacySurfaceHandoffResult.Failed(
                    IllegalStateException("Media sink close is in progress"),
                )
                if (!isCapturedLegacyBindingCurrentLocked(
                        type, surface, source, selectedAtCapture, outputAtCapture,
                        tokenAtCapture, routeGenerationAtCapture,
                        surfaceGenerationAtCapture, exactOverrideAtCapture, proof,
                    )
                ) return AndroidLegacySurfaceHandoffResult.NoMatchingBinding
                tokenAtCapture?.let { token ->
                    val output = checkNotNull(outputAtCapture)
                    val retirement = output.state.retire(token)
                    SurfaceTransition(
                        output,
                        retirement.request,
                        selectedAtCapture?.let(videoDecoders::get),
                        null,
                    )
                }
            }
            val decoderRetirement = if (transition != null) {
                try {
                    publishSurfaceTransition(transition)
                    awaitSurfaceRetirement(
                        checkNotNull(selectedAtCapture),
                        checkNotNull(outputAtCapture),
                        checkNotNull(tokenAtCapture),
                        deadline.remainingMillis(),
                    )
                } catch (error: Throwable) {
                    return AndroidLegacySurfaceHandoffResult.Failed(error)
                }
            } else null
            if (decoderRetirement != null && !decoderRetirement.isSafeToReleaseSurface) {
                return AndroidLegacySurfaceHandoffResult.Uncertain(decoderRetirement)
            }
            val reservation = synchronized(surfaceRoutingLock) {
                if (closed.get()) return AndroidLegacySurfaceHandoffResult.Failed(
                    IllegalStateException("Media sink close is in progress"),
                )
                if (!isCapturedLegacyBindingCurrentLocked(
                        type, surface, source, selectedAtCapture, outputAtCapture,
                        tokenAtCapture, routeGenerationAtCapture,
                        surfaceGenerationAtCapture, exactOverrideAtCapture, proof,
                    )
                ) return AndroidLegacySurfaceHandoffResult.NoMatchingBinding
                // The decoder worker has acknowledged the detach or released MediaCodec. Keep the
                // process reservation across the old-map removal, closing the reattach race.
                outputAtCapture?.let { output ->
                    output.token = null
                    output.source = null
                }
                legacySurfaces.remove(type)
                legacySurfaceSources.remove(type)
                val handoffSurfaceGeneration = surfaceRouteGenerations.advance(type)
                val restoreState = LegacyHandoffRestoreState()
                val issued = issueHandoffReservation(type, surface, source) { restoreTimeoutMillis ->
                    restoreLegacyBindingFromHandoff(
                        type,
                        surface,
                        source,
                        selectedAtCapture,
                        routeGenerationAtCapture,
                        handoffSurfaceGeneration,
                        outputAtCapture,
                        exactOverrideAtCapture,
                        restoreState,
                        restoreTimeoutMillis,
                    )
                }
                physicalLegacyRouteClaims[type]?.takeIf { claim ->
                    claim.surface === surface && claim.witness?.source === source &&
                        previewHandoffGates[type]?.physicalClaimToken === claim.token &&
                        previewHandoffGates[type]?.token === claim.handoffGateToken
                }?.also { claim ->
                    claim.transferReservation = issued
                }
                issued
            }
            // The worker has applied the detach and released its MediaCodec. It may remain idle
            // for a future preview, but cannot regain this Surface while the reservation is held.
            return AndroidLegacySurfaceHandoffResult.Reserved(
                reservation,
                decoderRetirement,
            )
        } finally {
            operationLock.unlock()
            notifyPhysicalRouteIfInvalid(type)
        }
    }

    /** Must be called with [surfaceRoutingLock] held and the type's operation lock held. */
    private fun isCapturedLegacyBindingCurrentLocked(
        type: Int,
        surface: Surface,
        source: Any,
        selectedAtCapture: AirPlayVideoStreamHandle?,
        outputAtCapture: SurfaceLeaseOutput<Surface>?,
        tokenAtCapture: SurfaceLeaseStateMachine.LeaseToken?,
        routeGenerationAtCapture: Long,
        surfaceGenerationAtCapture: Long,
        exactOverrideAtCapture: ExactOverrideWitness?,
        proof: LegacySurfaceBindingProof<Surface>,
    ): Boolean = !closed.get() &&
        legacySurfaces[type] === surface && legacySurfaceSources[type] === source &&
        processSurfaceReservations.owner(surface) === source &&
        legacyVideoRoutes.selected(type) === selectedAtCapture &&
        legacyVideoRoutes.generation(type) == routeGenerationAtCapture &&
        surfaceRouteGenerations.current(type) == surfaceGenerationAtCapture &&
        proof.isCurrent(
            this, type, surface, source, selectedAtCapture,
            routeGenerationAtCapture, surfaceGenerationAtCapture, tokenAtCapture,
        ) &&
        if (tokenAtCapture == null) {
            surfaceOutputs.entriesSnapshot().none { it.second.source === source } &&
                exactOverrideStillCurrentLocked(selectedAtCapture, exactOverrideAtCapture)
        } else {
            exactOverrideAtCapture == null && selectedAtCapture != null &&
                outputAtCapture?.source === source &&
                outputAtCapture.token === tokenAtCapture &&
                surfaceOutputs[selectedAtCapture] === outputAtCapture &&
                surfaceOutputs.entriesSnapshot().all { (handle, output) ->
                    output.source !== source || handle === selectedAtCapture
                }
        }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun exactOverrideStillCurrentLocked(
        selected: AirPlayVideoStreamHandle?,
        witness: ExactOverrideWitness?,
    ): Boolean = if (selected == null) {
        witness == null
    } else if (witness == null) {
        surfaces[selected] == null && surfaceSources[selected] == null
    } else {
        matchesExactOverrideAttachment(
            surfaces[selected], surfaceSources[selected], surfaceOutputs[selected],
            witness.output.source, witness.output.token, witness.surface, witness.source,
            witness.output, witness.token,
        )
    }

    private fun issueHandoffReservation(
        type: Int,
        surface: Surface,
        source: Any,
        restoreAction: ((Long) -> AndroidLegacySurfaceRestoreResult)? = null,
    ): AndroidVideoSurfaceHandoffReservation = AndroidVideoSurfaceHandoffReservation.issue(
        this,
        type,
        surface,
        heldAction = { processSurfaceReservations.owner(surface) === source },
        releaseAction = {
            val released = processSurfaceReservations.release(surface, source)
            // The reservation lifecycle holds its monitor here. Rechecking on the daemon avoids
            // taking the sink routing lock in the opposite order from isCurrent -> isHeld.
            schedulePhysicalRouteRecheck(type)
            released
        },
        restoreAction = restoreAction?.let { action ->
            { timeoutMillis ->
                action(timeoutMillis).also { schedulePhysicalRouteRecheck(type) }
            }
        },
    )

    private fun schedulePhysicalRouteRecheck(type: Int) {
        physicalRouteRevocationExecutor.execute { notifyPhysicalRouteIfInvalid(type) }
    }

    /** Retains the exact process reservation while restoring the old Host registration. */
    private fun restoreLegacyBindingFromHandoff(
        type: Int,
        surface: Surface,
        source: Any,
        selectedAtCapture: AirPlayVideoStreamHandle?,
        routeGenerationAtCapture: Long,
        handoffSurfaceGeneration: Long,
        outputAtCapture: SurfaceLeaseOutput<Surface>?,
        exactOverrideAtCapture: ExactOverrideWitness?,
        restoreState: LegacyHandoffRestoreState,
        timeoutMillis: Long,
    ): AndroidLegacySurfaceRestoreResult {
        val operationLock = legacySurfaceOperationLock(type)
        when (tryLockBeforeDeadline(operationLock, BoundedOperationDeadline(timeoutMillis))) {
            BoundedLockResult.ACQUIRED -> Unit
            BoundedLockResult.TIMED_OUT -> return AndroidLegacySurfaceRestoreResult.Uncertain(
                IllegalStateException("Legacy Surface restore timed out"),
            )
            BoundedLockResult.INTERRUPTED -> return AndroidLegacySurfaceRestoreResult.Uncertain(
                InterruptedException("Legacy Surface restore interrupted"),
            )
        }
        try {
            if (!restoreState.committed) {
                val prepared = synchronized(surfaceRoutingLock) {
                    if (closed.get() || type in poisonedVideoTypes ||
                        legacyVideoRoutes.generation(type) != routeGenerationAtCapture ||
                        !matchesLegacyHandoffRestoreRoute(
                            legacySurfaces[type],
                            legacySurfaceSources[type],
                            legacyVideoRoutes.selected(type),
                            selectedAtCapture,
                            selectedAtCapture?.let(surfaces::get),
                            exactOverrideAtCapture?.surface,
                            processSurfaceReservations.owner(surface),
                            source,
                            surfaceRouteGenerations.current(type),
                            handoffSurfaceGeneration,
                        ) ||
                        !exactOverrideStillCurrentLocked(
                            selectedAtCapture, exactOverrideAtCapture,
                        ) ||
                        (selectedAtCapture != null &&
                            videoHandleTerminationGate.isTerminal(selectedAtCapture)) ||
                        surfaceOutputs.entriesSnapshot().any { it.second.source === source }
                    ) return AndroidLegacySurfaceRestoreResult.StaleOrUnavailable
                    val output = selectedAtCapture?.let(surfaceOutputs::getOrCreate)
                    if (outputAtCapture != null && output !== outputAtCapture) {
                        return AndroidLegacySurfaceRestoreResult.StaleOrUnavailable
                    }
                    if (exactOverrideAtCapture == null && output != null &&
                        (output.token != null || output.source != null)
                    ) {
                        return AndroidLegacySurfaceRestoreResult.StaleOrUnavailable
                    }
                    val transition = try {
                        if (selectedAtCapture == null || exactOverrideAtCapture != null) null
                        else installBindingLocked(
                            selectedAtCapture,
                            checkNotNull(output),
                            EffectiveSurface(surface, source),
                        )
                    } catch (error: Throwable) {
                        return AndroidLegacySurfaceRestoreResult.Uncertain(error)
                    }
                    legacySurfaces[type] = surface
                    legacySurfaceSources[type] = source
                    surfaceRouteGenerations.advance(type)
                    restoreState.transition = transition
                    restoreState.committed = true
                    transition
                }
                if (prepared == null) {
                    return AndroidLegacySurfaceRestoreResult.Restored
                }
            }
            val transition = restoreState.transition
            // Map/source/token ownership has already transferred to this sink. Decoder
            // publication is recoverable sink work, not grounds to keep an external token
            // that could later deadlock after the selected route terminates.
            return completeCommittedLegacyHandoffRestore(
                publishDecoder = {
                    if (transition != null) {
                        publishInstalledBinding(checkNotNull(selectedAtCapture), transition)
                    }
                },
                onPublicationFailure = { error ->
                    Log.e(TAG, "restored legacy route needs decoder recovery type=$type", error)
                },
            )
        } finally {
            operationLock.unlock()
        }
    }

    /** Asks the phone for a random access picture; false when the stream cannot be asked. */
    fun requestKeyFrame(handle: AirPlayVideoStreamHandle): Boolean {
        val handler = withLiveVideoHandle(handle, null) { videoRecoveryHandlers[handle] }
            ?: return false
        if (closed.get() || videoHandleTerminationGate.isTerminal(handle)) return false
        // This external callback is intentionally outside the terminal admission gate.
        return handler.invoke()
    }

    fun requestKeyFrame(identity: AirPlayStreamIdentity): Boolean =
        requestKeyFrame(compatibilityHandle(identity))

    /** Legacy Host adapter targeting only the selected identity for [type]. */
    fun requestKeyFrame(type: Int): Boolean = legacyVideoRoutes.selected(type)
        ?.let(::requestKeyFrame)
        ?: requestKeyFrame(compatibilityHandle(legacyIdentity(type)))

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenActivityBindingLock) {
            screenActivityBinding = listener?.let(::ScreenActivityBinding)
        }
    }

    /** Identity-bound UI callback; closing an old Activity lease cannot detach a newer one. */
    fun attachScreenStreamActiveChangedListener(
        listener: (Int, Boolean) -> Unit,
    ): Closeable? {
        val binding = ScreenActivityBinding(listener)
        synchronized(screenActivityBindingLock) {
            if (closed.get()) return null
            screenActivityBinding = binding
        }
        return Closeable {
            synchronized(screenActivityBindingLock) {
                if (screenActivityBinding === binding) screenActivityBinding = null
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
        videoDecoders.values.forEach { it.setMediaMetricsMonitor(monitor) }
        audioRenderers.values.forEach { it.setMediaMetricsMonitor(monitor) }
    }

    override fun onVideoRecoveryHandler(
        handle: AirPlayVideoStreamHandle,
        requestKeyFrame: (() -> Boolean)?,
    ) {
        val selection = withLiveVideoHandle(handle, null) {
            if (requestKeyFrame == null) {
                videoRecoveryHandlers.remove(handle)
                null
            } else {
                val task = observeVideoHandle(handle)
                if (!videoHandleTerminationGate.isTerminal(handle)) {
                    videoRecoveryHandlers[handle] = requestKeyFrame
                }
                task
            }
        }
        selection?.run()
    }

    override fun onVideoRecoveryHandler(
        identity: AirPlayStreamIdentity,
        requestKeyFrame: (() -> Boolean)?,
    ) = onVideoRecoveryHandler(compatibilityHandle(identity), requestKeyFrame)

    override fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {
        onVideoRecoveryHandler(compatibilityHandle(legacyIdentity(type)), requestKeyFrame)
    }

    override fun onVideoCodec(handle: AirPlayVideoStreamHandle, codec: VideoCodec) {
        val selection = withLiveVideoHandle(handle, null) {
            val task = observeVideoHandle(handle)
            if (!videoHandleTerminationGate.isTerminal(handle)) pendingVideoCodec[handle] = codec
            task
        }
        selection?.run()
    }

    override fun onVideoCodec(identity: AirPlayStreamIdentity, codec: VideoCodec) =
        onVideoCodec(compatibilityHandle(identity), codec)

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        onVideoCodec(compatibilityHandle(legacyIdentity(type)), codec)
    }

    override fun onVideoConfig(handle: AirPlayVideoStreamHandle, codecData: ByteArray) {
        val tasks = withLiveVideoHandle(handle, emptyList()) {
            val tasks = mutableListOf<SerializedCallbackQueue.Task>()
            observeVideoHandle(handle)?.let(tasks::add)
            if (videoHandleTerminationGate.isTerminal(handle)) return@withLiveVideoHandle tasks
            val codec = pendingVideoCodec[handle] ?: VideoCodec.H264
            val copiedData = codecData.copyOf()
            videoConfigs[handle] = codec to copiedData
            if (!forwardedType(handle.type)) {
                videoDecoder(handle).configure(codec, copiedData)
                return@withLiveVideoHandle tasks
            }
            reserveMediaTapVideoConfig(handle, codec, copiedData)?.let(tasks::add)
            if (videoHandleTerminationGate.isTerminal(handle)) return@withLiveVideoHandle tasks
            synchronized(previewLock) {
                if (effectiveSurface(handle) != null) {
                    videoDecoder(handle).configure(codec, copiedData)
                }
            }
            tasks
        }
        tasks.forEach(SerializedCallbackQueue.Task::run)
    }

    override fun onVideoConfig(identity: AirPlayStreamIdentity, codecData: ByteArray) =
        onVideoConfig(compatibilityHandle(identity), codecData)

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        onVideoConfig(compatibilityHandle(legacyIdentity(type)), codecData)
    }

    override fun onVideoFrame(handle: AirPlayVideoStreamHandle, naluBytes: ByteArray) {
        val tasks = withLiveVideoHandle(handle, emptyList()) {
            handleVideoFrame(
                handle,
                naluBytes,
                ScreenFrameTiming(
                    presentationTimeNtp64Bits = null,
                    arrivalMonotonicNanos = System.nanoTime(),
                ),
                presentationTargetMonotonicNanos = null,
            )
        }
        tasks.forEach(SerializedCallbackQueue.Task::run)
    }

    override fun onVideoFrame(identity: AirPlayStreamIdentity, naluBytes: ByteArray) =
        onVideoFrame(compatibilityHandle(identity), naluBytes)

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        onVideoFrame(compatibilityHandle(legacyIdentity(type)), naluBytes)
    }

    override fun onVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) {
        val tasks = withLiveVideoHandle(handle, emptyList()) {
            handleVideoFrame(handle, naluBytes, timing, presentationTargetMonotonicNanos = null)
        }
        tasks.forEach(SerializedCallbackQueue.Task::run)
    }

    override fun onVideoFrame(
        identity: AirPlayStreamIdentity,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) = onVideoFrame(compatibilityHandle(identity), naluBytes, timing)

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, timing: ScreenFrameTiming) {
        onVideoFrame(compatibilityHandle(legacyIdentity(type)), naluBytes, timing)
    }

    override fun onVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        val tasks = withLiveVideoHandle(handle, emptyList()) {
            handleVideoFrame(handle, naluBytes, timing, presentationTargetMonotonicNanos)
        }
        tasks.forEach(SerializedCallbackQueue.Task::run)
    }

    override fun onVideoFrame(
        identity: AirPlayStreamIdentity,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) = onVideoFrame(
        compatibilityHandle(identity),
        naluBytes,
        timing,
        presentationTargetMonotonicNanos,
    )

    override fun onVideoFrame(
        type: Int,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        onVideoFrame(
            compatibilityHandle(legacyIdentity(type)),
            naluBytes,
            timing,
            presentationTargetMonotonicNanos,
        )
    }

    private fun handleVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ): List<SerializedCallbackQueue.Task> {
        val tasks = mutableListOf<SerializedCallbackQueue.Task>()
        observeVideoHandle(handle)?.let(tasks::add)
        if (videoHandleTerminationGate.isTerminal(handle)) return tasks
        val arrivalUs = timing.arrivalMonotonicNanos / 1_000L
        val renderTarget = presentationTargetMonotonicNanos.takeIf { timedVideoPresentation }
        if (!forwardedType(handle.type)) {
            videoDecoder(handle).submit(naluBytes, arrivalUs, renderTarget, timing)
            return tasks
        }
        reserveMediaTapVideoFrame(handle, naluBytes, timing)?.let(tasks::add)
        if (videoHandleTerminationGate.isTerminal(handle)) return tasks
        // Only a preview decoder created with a Surface may take frames; it never holds them.
        videoDecoders[handle]?.submit(naluBytes, arrivalUs, renderTarget, timing)
        return tasks
    }

    override fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
        if (closed.get()) return
        val notification = try {
            if (active) {
                withLiveVideoHandle(handle, null) { updateScreenStreamActive(handle, true) }
            } else {
                finishTerminalCleanup(handle, TERMINAL_CALLBACK_DRAIN_TIMEOUT_MS)
            }
        } finally {
            if (!active) notifyPhysicalRouteIfInvalid(handle.type)
        }
        notification?.run()
    }

    private fun updateScreenStreamActive(
        handle: AirPlayVideoStreamHandle,
        active: Boolean,
    ): SerializedCallbackQueue.Task {
        if (active) checkVideoTypeNotPoisoned(handle.type)
        lateinit var routeChange: LegacyVideoRouteChange
        var mediaTapSelection: SerializedCallbackQueue.Task? = null
        try {
            legacySurfaceOperationLock(handle.type).withLock {
                routeChange = if (active) {
                    val snapshot: LegacyVideoHandleRoutes.TypeSnapshot
                    val changed = synchronized(surfaceRoutingLock) {
                        snapshot = legacyVideoRoutes.snapshot(handle.type)
                        legacyVideoRoutes.setActive(handle, true)
                    }
                    try {
                        if (changed.selectionChanged) applyLegacyRouteChangeSerial(changed)
                        changed
                    } catch (error: Throwable) {
                        rollbackLegacyRouteBindings(snapshot, changed, error, "active")
                    }
                } else {
                    deactivateTerminalHandleLocked(handle)
                }
                if (routeChange.selectionChanged) {
                    mediaTapSelection = reserveMediaTapSelection(
                        routeChange.type,
                        routeChange.currentHandle,
                    )
                }
            }
        } finally {
            notifyPhysicalRouteIfInvalid(handle.type)
        }
        return screenLifecycleCallbackQueues
            .computeIfAbsent(handle.type) { SerializedCallbackQueue() }
            .reserve {
                if (closed.get()) return@reserve
                mediaTapSelection?.run()
                if (routeChange.activeChanged) {
                    mediaTap?.onVideoActive(handle.type, routeChange.isActive)
                    screenActivityBinding?.callback?.invoke(handle.type, routeChange.isActive)
                }
            }
    }

    /** Called with this display type's operation lock held. */
    private fun deactivateTerminalHandleLocked(
        handle: AirPlayVideoStreamHandle,
    ): LegacyVideoRouteChange {
        val proposed = legacyVideoRoutes.previewSetActive(handle, false)
        val (output, _) = retireInstalledBinding(handle, "terminate Surface for $handle")
        return commitAfterProvenRelease(
            release = { closeVideoDecoder(handle, output) },
        ) {
            // Make the successor physically ready before publishing the logical route change.
            proposed.currentHandle?.let { successor ->
                val target = synchronized(surfaceRoutingLock) { selectedSurfaceLocked(successor) }
                reconcileBindingToTarget(successor, target, "prepare successor route $successor")
            }

            val committed = synchronized(surfaceRoutingLock) {
                val actual = legacyVideoRoutes.setActive(handle, false)
                check(actual.previousHandle === proposed.previousHandle &&
                    actual.currentHandle === proposed.currentHandle &&
                    actual.activeChanged == proposed.activeChanged
                ) { "Legacy route changed while terminal cleanup was in flight" }
                val terminalSurface = surfaces.remove(handle)
                val terminalSource = surfaceSources.remove(handle)
                if (terminalSurface != null && terminalSource != null) {
                    surfaceRouteGenerations.advance(handle.type)
                    releaseSurfaceReservationLocked(terminalSurface, terminalSource)
                }
                actual
            }
            pendingVideoCodec.remove(handle)
            videoConfigs.remove(handle)
            videoRecoveryHandlers.remove(handle)
            videoFrameIdentityLedgers.remove(handle)?.discardPending()
            removeTerminalSurfaceOutput(handle, output)
            committed
        }
    }

    private fun finishTerminalCleanup(
        handle: AirPlayVideoStreamHandle,
        timeoutMillis: Long,
    ): SerializedCallbackQueue.Task? {
        var notification: SerializedCallbackQueue.Task? = null
        val result = videoHandleTerminationGate.terminate(handle, timeoutMillis) {
            if (!closed.get()) notification = updateScreenStreamActive(handle, false)
        }
        if (result == VideoHandleTerminationResult.TIMED_OUT) {
            Log.w(TAG, "terminal cleanup deferred for in-flight callback handle=$handle")
        } else if (result == VideoHandleTerminationResult.INTERRUPTED) {
            Log.w(TAG, "terminal cleanup interrupted handle=$handle")
        }
        return notification
    }

    override fun onScreenStreamActive(identity: AirPlayStreamIdentity, active: Boolean) {
        val handle = compatibilityHandle(identity)
        onScreenStreamActive(handle, active)
        if (!active) compatibilityHandles.remove(identity, handle)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        onScreenStreamActive(legacyIdentity(type), active)
    }

    private fun forwardedType(type: Int): Boolean = forwardedVideoTypes[type] == true

    override fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {
        audioRenderer(type, format).start()
    }

    override fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(type, format).submit(rtp, sample)
    }

    override fun onAudioStopped(type: Int) {
        audioRenderers.remove(type)?.close()
        mediaTap?.onAudioStopped(type)
    }

    /**
     * Detached, exact PCM16 APAP track preparation. This is not called by the product's RTP
     * callbacks and does not make type-103 feature negotiation or SETUP available. The caller
     * retains its exact output lease; sink teardown also retires any lease it still owns.
     */
    internal fun prepareMainBufferedPcm16Output(): MainBufferedPcm16Output =
        synchronized(mainBufferedPcmOutputCreationLock) {
            check(!closed.get()) { "media sink has closed" }
            val output = AndroidMainBufferedPcm16Output.open { retired ->
                mainBufferedPcmOutputs.remove(retired)
            }
            mainBufferedPcmOutputs.add(output)
            if (closed.get()) {
                output.close()
                throw IllegalStateException("media sink closed during APAP PCM preparation")
            }
            output
        }

    override fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {
        val uplink = microphoneUplinks.computeIfAbsent(type) {
            MicrophoneUplink(context, config, microphoneGainPercent)
        }
        if (!uplink.start()) microphoneUplinks.remove(type, uplink)
    }

    override fun onMicrophoneStopped(type: Int) {
        microphoneUplinks.remove(type)?.close()
    }

    fun close() {
        // Close admission is terminal even when resource retirement needs a later retry. Publish
        // route revocation immediately, before the potentially bounded decoder drain.
        closed.set(true)
        val callbacks = synchronized(surfaceRoutingLock) {
            physicalLegacyRouteClaims.values.flatMap(::revokePhysicalRouteClaimLocked)
        }
        dispatchPhysicalRouteRevocations(callbacks)
        closeProgress.close(::retireSinkResources, onClosed)
    }

    /**
     * Retires only resources owned directly by this sink. [onClosed] is a separate, retryable owner
     * stage: completing this method must never make a failed WebDisplay close look successful.
     */
    private fun retireSinkResources(): String? {
        // APAP PCM has an independent AudioTrack. Silence it before any video callback or decoder
        // drain can time out; otherwise a retryable video close could leave old audio audible.
        val pcmOutputFailures = mutableListOf<Throwable>()
        val pcmOutputs = synchronized(mainBufferedPcmOutputCreationLock) {
            mainBufferedPcmOutputs.toList()
        }
        pcmOutputs.forEach { output ->
            try {
                output.close()
            } catch (failure: Throwable) {
                pcmOutputFailures += failure
            }
        }
        if (pcmOutputFailures.isNotEmpty()) {
            return "APAP PCM output retirement failed: ${pcmOutputFailures.joinToString { it.message.orEmpty() }}"
        }
        val closeDeadline = BoundedOperationDeadline(SINK_CLOSE_TIMEOUT_MS)
        val callbackDrain = videoHandleTerminationGate.markAllTerminalAndAwait(
            minOf(
                TERMINAL_CALLBACK_DRAIN_TIMEOUT_MS,
                closeDeadline.remainingMillis(),
            ),
        )
        if (callbackDrain != VideoHandleTerminationResult.CLEANED) {
            return "video callback drain failed: $callbackDrain"
        }
        synchronized(screenActivityBindingLock) { screenActivityBinding = null }
        val decoderOutputs: List<Pair<VideoDecoder, SurfaceLeaseOutput<Surface>>>
        val outputs: List<SurfaceLeaseOutput<Surface>>
        val registeredSurfaces: List<Pair<Surface, Any>>
        val pendingReservations: List<Pair<Surface, Any>>
        synchronized(surfaceRoutingLock) {
            outputs = surfaceOutputs.valuesSnapshot()
            decoderOutputs = videoDecoders.mapNotNull { (handle, decoder) ->
                surfaceOutputs[handle]?.let { decoder to it }
            }
            registeredSurfaces = buildList {
                surfaces.forEach { (handle, surface) ->
                    surfaceSources[handle]?.let { add(surface to it) }
                }
                legacySurfaces.forEach { (type, surface) ->
                    legacySurfaceSources[type]?.let { add(surface to it) }
                }
            }
            pendingReservations = pendingSurfaceReservations.entries.map { it.key to it.value }
            pendingSurfaceReservations.clear()
            outputs.forEach { it.state.beginShutdown() }
            decoderOutputs.forEach { (decoder, _) -> decoder.requestClose() }
        }
        pendingReservations.forEach { (surface, owner) ->
            processSurfaceReservations.release(surface, owner)
        }
        val releasedOutputs = mutableSetOf<SurfaceLeaseOutput<Surface>>()
        val releasedDecoders = mutableSetOf<VideoDecoder>()
        val failedResults = mutableListOf<DecoderCloseResult>()
        forEachWithinDeadline(decoderOutputs, closeDeadline) { (decoder, output), remaining ->
            val result = decoder.awaitClosed(remaining)
            if (result == DecoderCloseResult.RELEASED) {
                releasedOutputs += output
                releasedDecoders += decoder
            } else {
                failedResults += result
                Log.e(TAG, "video decoder did not release within close timeout result=$result")
            }
        }
        synchronized(surfaceRoutingLock) {
            videoDecoders.entries.removeAll { (_, decoder) -> decoder in releasedDecoders }
            surfaceOutputs.removeIf { _, output ->
                output in releasedOutputs || decoderOutputs.none { it.second === output }
            }
        }
        outputs.filter {
            it in releasedOutputs || decoderOutputs.none { pair -> pair.second === it }
        }.forEach { it.state.acknowledgeTermination() }
        if (failedResults.isNotEmpty()) {
            return "video decoder retirement failed: ${failedResults.joinToString()}"
        }
        synchronized(surfaceRoutingLock) {
            (surfaces.keys.map { it.type } + legacySurfaces.keys).toSet().forEach {
                surfaceRouteGenerations.advance(it)
            }
            surfaces.clear()
            surfaceSources.clear()
            legacySurfaces.clear()
            legacySurfaceSources.clear()
            legacyVideoRoutes.clear()
            compatibilityHandles.clear()
        }
        registeredSurfaces.forEach { (surface, owner) ->
            processSurfaceReservations.release(surface, owner)
        }
        videoRecoveryHandlers.clear()
        pendingVideoCodec.clear()
        videoConfigs.clear()
        videoFrameIdentityLedgers.clear()
        mediaTapVideoGates.clear()
        screenLifecycleCallbackQueues.clear()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        microphoneUplinks.values.forEach(MicrophoneUplink::close)
        microphoneUplinks.clear()
        audioFocus?.release(this)
        videoHandleTerminationGate.clear()
        poisonedVideoTypes.clear()
        synchronized(surfaceRoutingLock) {
            physicalLegacyRouteClaims.clear()
            decoderRouteGenerations.clear()
        }
        return null
    }

    private fun videoDecoder(handle: AirPlayVideoStreamHandle): VideoDecoder =
        synchronized(surfaceRoutingLock) {
            videoDecoders[handle]?.let { return@synchronized it }
            val output = surfaceOutputLocked(handle)
            VideoDecoder(
                output.state.currentRequest(),
                handle,
                videoSizes[handle.type]?.first ?: videoWidth,
                videoSizes[handle.type]?.second ?: videoHeight,
                preferSoftwareHevcDecoder,
                timedVideoPresentation,
                mediaMetricsMonitor,
                requestKeyFrame = { requestKeyFrame(handle) },
                onSurfaceRequestApplied = output.state::acknowledge,
                onUnavailable = { notifyPhysicalRouteIfInvalid(handle.type) },
                onVideoFrameReleased = onVideoFrameReleased,
                frameIdentityLedger = videoFrameIdentityLedgers.computeIfAbsent(handle) {
                    VideoFrameIdentityLedger(it)
                },
            ).also { videoDecoders[handle] = it }
        }

    private fun observeVideoHandle(
        handle: AirPlayVideoStreamHandle,
    ): SerializedCallbackQueue.Task? {
        checkVideoTypeNotPoisoned(handle.type)
        var mediaTapSelection: SerializedCallbackQueue.Task? = null
        var selectedRouteChanged = false
        try {
            legacySurfaceOperationLock(handle.type).withLock {
                val snapshot: LegacyVideoHandleRoutes.TypeSnapshot
                val change = synchronized(surfaceRoutingLock) {
                    snapshot = legacyVideoRoutes.snapshot(handle.type)
                    legacyVideoRoutes.observe(handle)
                }
                selectedRouteChanged = change.selectionChanged
                if (change.selectionChanged) {
                    try {
                        applyLegacyRouteChangeSerial(change)
                    } catch (error: Throwable) {
                        rollbackLegacyRouteBindings(snapshot, change, error, "observed")
                    }
                    mediaTapSelection = reserveMediaTapSelection(change.type, change.currentHandle)
                }
            }
        } finally {
            if (selectedRouteChanged) notifyPhysicalRouteIfInvalid(handle.type)
        }
        return mediaTapSelection
    }

    /** Called while the per-type operation lock is held; never while [surfaceRoutingLock] is held. */
    private fun applyLegacyRouteChangeSerial(
        change: LegacyVideoRouteChange,
    ): AirPlayVideoStreamHandle? {
        check(change.selectionChanged)
        change.previousHandle?.let { previous ->
            reconcileBindingToDesired(previous, "retire previous legacy route $previous")
        }
        val current = change.currentHandle ?: return null
        reconcileBindingToDesired(current, "install current legacy route $current")
        promoteProvisionalPhysicalRouteIfBound(change.type, current)
        return current
    }

    /** First selected stream may promote a pre-SETUP claim, but a later stream cannot rebase it. */
    private fun promoteProvisionalPhysicalRouteIfBound(
        type: Int,
        selected: AirPlayVideoStreamHandle,
    ) = synchronized(surfaceRoutingLock) {
        val claim = physicalLegacyRouteClaims[type] ?: return@synchronized
        val prior = claim.witness ?: return@synchronized
        if (claim.revoked || prior.selected != null ||
            legacySurfaces[type] !== claim.surface ||
            legacySurfaceSources[type] !== prior.source ||
            processSurfaceReservations.owner(claim.surface) !== prior.source ||
            surfaces[selected] != null || videoHandleTerminationGate.isTerminal(selected)
        ) return@synchronized
        val output = surfaceOutputs[selected] ?: return@synchronized
        val token = output.token ?: return@synchronized
        if (output.source !== prior.source || !output.state.isCurrent(token)) return@synchronized
        claim.witness = prior.copy(
            selected = selected,
            routeGeneration = legacyVideoRoutes.generation(type),
            output = output,
            outputToken = token,
            decoderGeneration = decoderRouteGenerations[selected] ?: 0L,
            decoderAtAttach = videoDecoders[selected],
        )
    }

    private fun rollbackLegacyRouteBindings(
        snapshot: LegacyVideoHandleRoutes.TypeSnapshot,
        change: LegacyVideoRouteChange,
        original: Throwable,
        context: String,
    ): Nothing {
        synchronized(surfaceRoutingLock) { legacyVideoRoutes.restore(snapshot) }
        try {
            rollbackRouteBindingsWithoutOverlap(
                failedCandidate = change.currentHandle,
                previous = change.previousHandle,
            ) { affected ->
                reconcileBindingToDesired(affected, "rollback $context route $affected")
            }
        } catch (rollback: Throwable) {
            poisonedVideoTypes += change.type
            original.addSuppressed(rollback)
        }
        throw original
    }

    private fun reserveMediaTapSelection(
        type: Int,
        handle: AirPlayVideoStreamHandle?,
    ): SerializedCallbackQueue.Task? {
        val tap = mediaTap?.takeIf { forwardedType(type) } ?: return null
        val config = handle?.let(videoConfigs::get)?.let { (codec, data) ->
            codec to data.copyOf()
        }
        val gate = mediaTapVideoGates.computeIfAbsent(type) { OrderedVideoCallbackGate() }
        return gate.reserveSelection(handle, config) { (codec, data) ->
            if (!closed.get() &&
                (handle == null || !videoHandleTerminationGate.isTerminal(handle))
            ) {
                tap.onVideoConfig(type, codec, data)
            }
        }
    }

    private fun reserveMediaTapVideoConfig(
        handle: AirPlayVideoStreamHandle,
        codec: VideoCodec,
        data: ByteArray,
    ): SerializedCallbackQueue.Task? {
        val tap = mediaTap ?: return null
        val gate = mediaTapVideoGates.computeIfAbsent(handle.type) { OrderedVideoCallbackGate() }
        return gate.reserveConfig(handle, codec to data.copyOf()) { (publishedCodec, publishedData) ->
            if (!closed.get() && !videoHandleTerminationGate.isTerminal(handle)) {
                tap.onVideoConfig(handle.type, publishedCodec, publishedData)
            }
        }
    }

    private fun reserveMediaTapVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ): SerializedCallbackQueue.Task? {
        val tap = mediaTap ?: return null
        val gate = mediaTapVideoGates.computeIfAbsent(handle.type) { OrderedVideoCallbackGate() }
        return gate.reserveFrame(handle) {
            if (!closed.get() && !videoHandleTerminationGate.isTerminal(handle)) {
                forwardVideoFrameToMediaTap(tap, handle.identity, naluBytes, timing)
            }
        }
    }

    private fun effectiveSurface(handle: AirPlayVideoStreamHandle): Surface? =
        synchronized(surfaceRoutingLock) { effectiveSurfaceLocked(handle)?.surface }

    private fun effectiveSurfaceLocked(handle: AirPlayVideoStreamHandle): EffectiveSurface? {
        val exact = surfaces[handle]
        val exactSource = surfaceSources[handle]
        if (exact != null && exactSource != null) return EffectiveSurface(exact, exactSource)
        if (!legacyVideoRoutes.isSelected(handle)) return null
        val legacy = legacySurfaces[handle.type] ?: return null
        val legacySource = legacySurfaceSources[handle.type] ?: return null
        return EffectiveSurface(legacy, legacySource)
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun legacyRegistrationMatchesLocked(
        type: Int,
        registration: Pair<Surface, Any>?,
    ): Boolean = matchesLegacySurfaceRegistration(
        legacySurfaces[type], legacySurfaceSources[type], registration,
    )

    /** Must be called with [surfaceRoutingLock] held. */
    private fun routeMutationBlockedLocked(type: Int, physicalClaimToken: Any?): Boolean {
        if (previewHandoffGates.containsKey(type)) return true
        val claim = physicalLegacyRouteClaims[type]
        return if (physicalClaimToken == null) claim != null else claim?.token !== physicalClaimToken
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun exactRouteMutationAuthorizedLocked(type: Int, transferredClaimToken: Any?): Boolean {
        if (transferredClaimToken == null) return !routeMutationBlockedLocked(type, null)
        val claim = physicalLegacyRouteClaims[type]
        return claim?.token === transferredClaimToken && !claim.revoked &&
            claim.transferReservation != null &&
            isPhysicalExternalRouteWitnessCurrentLocked(type, claim)
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun reserveUniqueSurfaceLocked(surface: Surface): Any {
        require(surfaces.values.none { it === surface } &&
            legacySurfaces.values.none { it === surface } &&
            !processSurfaceReservations.contains(surface)
        ) {
            "Surface instance is already registered; reuse its existing lease"
        }
        return processSurfaceReservations.reserve(surface).also {
            pendingSurfaceReservations[surface] = it
        }
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun commitSurfaceReservationLocked(surface: Surface, source: Any) {
        check(pendingSurfaceReservations[surface] === source) { "Surface reservation is not pending" }
        pendingSurfaceReservations.remove(surface)
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun releaseSurfaceReservationLocked(surface: Surface, source: Any) {
        if (pendingSurfaceReservations[surface] === source) pendingSurfaceReservations.remove(surface)
        processSurfaceReservations.release(surface, source)
    }

    /**
     * Detaches the currently installed generation without changing desired routing maps. Unsafe
     * retirement leaves [SurfaceLeaseOutput.source]/token intact so the same operation can retry.
     */
    private fun retireInstalledBinding(
        handle: AirPlayVideoStreamHandle,
        operation: String,
    ): Pair<SurfaceLeaseOutput<Surface>, AndroidVideoSurfaceRetirementResult?> {
        val output: SurfaceLeaseOutput<Surface>
        val token: SurfaceLeaseStateMachine.LeaseToken?
        val transition: SurfaceTransition?
        synchronized(surfaceRoutingLock) {
            output = surfaceOutputLocked(handle)
            token = output.token
            if (token == null) {
                check(output.source == null) { "Surface output source exists without a lease token" }
                transition = null
            } else {
                val retirement = output.state.retire(token)
                transition = SurfaceTransition(
                    output = output,
                    request = retirement.request,
                    decoder = videoDecoders[handle],
                    installedToken = null,
                )
            }
        }
        if (transition == null || token == null) return output to null
        publishSurfaceTransition(transition)
        val result = awaitSurfaceRetirement(
            handle,
            output,
            token,
            DEFAULT_SURFACE_RETIREMENT_MS,
        )
        requireSafeSurfaceRetirement(result, operation)
        synchronized(surfaceRoutingLock) {
            check(output.token === token) { "Surface binding changed during retirement" }
            output.token = null
            output.source = null
        }
        return output to result
    }

    /** Installs one already-reserved desired target; no previous token may remain. */
    private fun installBindingLocked(
        handle: AirPlayVideoStreamHandle,
        output: SurfaceLeaseOutput<Surface>,
        target: EffectiveSurface,
    ): SurfaceTransition {
        check(output.token == null && output.source == null) {
            "Previous Surface generation was not retired"
        }
        val attachment = output.state.attach(target.surface)
        output.source = target.source
        output.token = attachment.token
        return SurfaceTransition(
            output = output,
            request = attachment.request,
            decoder = videoDecoders[handle],
            installedToken = attachment.token,
        )
    }

    private fun publishInstalledBinding(
        handle: AirPlayVideoStreamHandle,
        transition: SurfaceTransition,
    ) {
        try {
            publishSurfaceTransition(transition)
            reconcileSurfaceDecoder(handle, transition.output)
        } catch (error: Throwable) {
            // Ownership has already been committed to the returned lease. Preserve that lease and
            // let its owner retire/retry instead of throwing away the only handle to this Surface.
            Log.e(TAG, "installed Surface requires later decoder recovery handle=$handle", error)
        }
    }

    /** Two-phase physical reconciliation: retire the old generation before publishing the next. */
    private fun reconcileBindingToDesired(
        handle: AirPlayVideoStreamHandle,
        operation: String,
    ) = reconcileBindingToTarget(
        handle,
        synchronized(surfaceRoutingLock) { effectiveSurfaceLocked(handle) },
        operation,
    )

    private fun reconcileBindingToTarget(
        handle: AirPlayVideoStreamHandle,
        target: EffectiveSurface?,
        operation: String,
    ) {
        val unchanged = synchronized(surfaceRoutingLock) {
            val output = surfaceOutputLocked(handle)
            output.source === target?.source
        }
        if (unchanged) return
        val (output, _) = retireInstalledBinding(handle, operation)
        val transition = synchronized(surfaceRoutingLock) {
            target?.let {
                check(!closed.get() && !videoHandleTerminationGate.isTerminal(handle)) {
                    "Video stream terminated before route Surface installation"
                }
                installBindingLocked(handle, output, it)
            }
        }
        if (transition != null) publishSurfaceTransition(transition)
        reconcileSurfaceDecoderForTarget(handle, output, target?.surface)
    }

    /** Desired output when [handle] is selected, independent of the currently committed route. */
    private fun selectedSurfaceLocked(handle: AirPlayVideoStreamHandle): EffectiveSurface? {
        val exact = surfaces[handle]
        val exactSource = surfaceSources[handle]
        if (exact != null && exactSource != null) return EffectiveSurface(exact, exactSource)
        val legacy = legacySurfaces[handle.type] ?: return null
        return EffectiveSurface(legacy, checkNotNull(legacySurfaceSources[handle.type]))
    }

    private fun surfaceOutputLocked(
        handle: AirPlayVideoStreamHandle,
    ): SurfaceLeaseOutput<Surface> = surfaceOutputs.getOrCreate(handle)

    private fun legacySurfaceOperationLock(type: Int): ReentrantLock =
        legacySurfaceOperationLocks.computeIfAbsent(type) { ReentrantLock() }

    private fun checkVideoTypeNotPoisoned(type: Int) {
        check(type !in poisonedVideoTypes) {
            "Video output type=$type is poisoned after an unrecoverable route rollback"
        }
    }

    private fun compatibilityHandle(identity: AirPlayStreamIdentity): AirPlayVideoStreamHandle =
        compatibilityHandles.computeIfAbsent(identity) {
            AirPlayVideoStreamHandle(it, compatibilityEpoch)
        }

    private fun <T> withLiveVideoHandle(
        handle: AirPlayVideoStreamHandle,
        rejected: T,
        action: () -> T,
    ): T {
        if (closed.get()) return rejected
        return try {
            videoHandleTerminationGate.withLive(handle, rejected) {
                if (closed.get()) rejected else action()
            }
        } finally {
            if (!closed.get() && videoHandleTerminationGate.isTerminal(handle) &&
                !videoHandleTerminationGate.cleanupComplete(handle)
            ) {
                finishTerminalCleanup(handle, 0)?.run()
            }
        }
    }

    private fun publishSurfaceTransition(transition: SurfaceTransition) {
        val request = transition.request ?: return
        val decoder = transition.decoder
        if (decoder == null) transition.output.state.acknowledge(request)
        else decoder.setSurface(request)
    }

    private fun reconcileSurfaceDecoder(
        handle: AirPlayVideoStreamHandle,
        output: SurfaceLeaseOutput<Surface>,
    ) {
        val target = synchronized(surfaceRoutingLock) { effectiveSurfaceLocked(handle)?.surface }
        reconcileSurfaceDecoderForTarget(handle, output, target)
    }

    private fun reconcileSurfaceDecoderForTarget(
        handle: AirPlayVideoStreamHandle,
        output: SurfaceLeaseOutput<Surface>,
        target: Surface?,
    ) {
        val decoder = synchronized(surfaceRoutingLock) { videoDecoders[handle] }
        if (target == null && forwardedType(handle.type)) {
            closeVideoDecoder(handle, output)
        } else if (target != null && decoder == null) {
            synchronized(previewLock) {
                videoConfigs[handle]?.let { (codec, data) ->
                    videoDecoder(handle).configure(codec, data)
                }
            }
        }
    }

    private fun retireExactSurface(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
        source: Any,
        output: SurfaceLeaseOutput<Surface>,
        token: SurfaceLeaseStateMachine.LeaseToken,
        timeoutMillis: Long,
    ): AndroidVideoSurfaceRetirementResult {
        val deadline = BoundedOperationDeadline(timeoutMillis)
        val operationLock = legacySurfaceOperationLock(handle.type)
        when (tryLockBeforeDeadline(operationLock, deadline)) {
            BoundedLockResult.ACQUIRED -> Unit
            BoundedLockResult.TIMED_OUT ->
                return AndroidVideoSurfaceRetirementResult.DECODER_CLOSE_TIMED_OUT
            BoundedLockResult.INTERRUPTED ->
                return AndroidVideoSurfaceRetirementResult.INTERRUPTED
        }
        try {
            val isCurrent = synchronized(surfaceRoutingLock) {
                surfaces[handle] === surface && surfaceSources[handle] === source &&
                    output.token === token
            }
            val fallback = if (isCurrent) {
                synchronized(surfaceRoutingLock) {
                    if (!legacyVideoRoutes.isSelected(handle)) null
                    else legacySurfaces[handle.type]?.let { legacy ->
                        require(legacy !== surface) {
                            "Exact-to-legacy handoff cannot reuse the same Surface instance"
                        }
                        EffectiveSurface(legacy, checkNotNull(legacySurfaceSources[handle.type]))
                    }
                }
            } else {
                null
            }
            val transition = synchronized(surfaceRoutingLock) {
                val retirement = output.state.retire(token)
                SurfaceTransition(
                    output = output,
                    request = retirement.request,
                    decoder = videoDecoders[handle],
                    installedToken = null,
                )
            }
            publishSurfaceTransition(transition)
            val result = awaitSurfaceRetirement(
                handle,
                output,
                token,
                deadline.remainingMillis(),
            )
            if (!result.isSafeToReleaseSurface) return result
            if (isCurrent && !closed.get()) {
                var fallbackFailure: Throwable? = null
                val installed = synchronized(surfaceRoutingLock) {
                    check(surfaces[handle] === surface && surfaceSources[handle] === source &&
                        output.token === token
                    ) { "Exact Surface changed during retirement" }
                    output.token = null
                    output.source = null
                    surfaces.remove(handle)
                    surfaceSources.remove(handle)
                    surfaceRouteGenerations.advance(handle.type)
                    releaseSurfaceReservationLocked(surface, source)
                    try {
                        fallback?.let { installBindingLocked(handle, output, it) }
                    } catch (error: Throwable) {
                        fallbackFailure = error
                        null
                    }
                }
                if (fallbackFailure != null) {
                    poisonedVideoTypes += handle.type
                    Log.e(TAG, "fallback Surface installation failed handle=$handle", fallbackFailure)
                } else if (installed != null) {
                    publishInstalledBinding(handle, installed)
                }
                // With no fallback, the acknowledged detach already proves Surface safety. Keep
                // the empty worker reusable rather than spending caller budget closing it.
            } else if (!closed.get()) {
                // A stale lease may force-close the current decoder; restore only the current binding.
                val hasCurrentTarget = synchronized(surfaceRoutingLock) {
                    effectiveSurfaceLocked(handle) != null
                }
                if (hasCurrentTarget) reconcileSurfaceDecoder(handle, output)
            }
            return result
        } finally {
            operationLock.unlock()
        }
    }

    private fun awaitSurfaceRetirement(
        handle: AirPlayVideoStreamHandle,
        output: SurfaceLeaseOutput<Surface>,
        token: SurfaceLeaseStateMachine.LeaseToken,
        timeoutMillis: Long,
    ): AndroidVideoSurfaceRetirementResult {
        val deadline = BoundedOperationDeadline(timeoutMillis)
        val retirement = output.state.retire(token)
        if (retirement.alreadyRetired) {
            return if (closed.get()) AndroidVideoSurfaceRetirementResult.SINK_CLOSED
            else AndroidVideoSurfaceRetirementResult.ALREADY_RETIRED
        }
        retirement.request?.let { request ->
            val decoder = synchronized(surfaceRoutingLock) { videoDecoders[handle] }
            if (decoder == null) output.state.acknowledge(request) else decoder.setSurface(request)
        }
        val normalWaitMillis = minOf(
            SURFACE_ACK_GRACE_MS,
            deadline.remainingMillis(),
        )
        when (output.state.awaitRetirement(token, normalWaitMillis)) {
            SurfaceLeaseStateMachine.AwaitResult.RETIRED -> {
                return if (closed.get()) AndroidVideoSurfaceRetirementResult.SINK_CLOSED
                else AndroidVideoSurfaceRetirementResult.RETIRED
            }
            SurfaceLeaseStateMachine.AwaitResult.INTERRUPTED ->
                return AndroidVideoSurfaceRetirementResult.INTERRUPTED
            SurfaceLeaseStateMachine.AwaitResult.TIMED_OUT -> Unit
        }

        val decoder: VideoDecoder?
        val watermark: SurfaceLeaseStateMachine.ApplyRequest<Surface>
        synchronized(surfaceRoutingLock) {
            decoder = videoDecoders[handle]
            watermark = output.state.currentRequest()
            decoder?.setSurface(watermark)
            decoder?.requestClose()
        }
        if (decoder == null) {
            output.state.acknowledge(watermark)
            return AndroidVideoSurfaceRetirementResult.RETIRED
        }
        when (decoder.awaitClosed(deadline.remainingMillis())) {
            DecoderCloseResult.RELEASED -> Unit
            DecoderCloseResult.RELEASE_FAILED ->
                return AndroidVideoSurfaceRetirementResult.DECODER_RELEASE_FAILED
            DecoderCloseResult.INTERRUPTED ->
                return AndroidVideoSurfaceRetirementResult.INTERRUPTED
            DecoderCloseResult.TIMED_OUT,
            DecoderCloseResult.REQUESTED_FROM_WORKER,
            -> return AndroidVideoSurfaceRetirementResult.DECODER_CLOSE_TIMED_OUT
        }
        synchronized(surfaceRoutingLock) {
            if (videoDecoders.remove(handle, decoder)) advanceDecoderRouteGenerationLocked(handle)
        }
        output.state.acknowledge(watermark)
        return AndroidVideoSurfaceRetirementResult.FORCED_DECODER_CLOSE
    }

    private fun closeVideoDecoder(
        handle: AirPlayVideoStreamHandle,
        output: SurfaceLeaseOutput<Surface>,
    ): Boolean {
        val decoder = synchronized(surfaceRoutingLock) { videoDecoders[handle] } ?: return true
        decoder.requestClose()
        val result = decoder.awaitClosed(DECODER_FORCE_CLOSE_TIMEOUT_MS)
        return if (result == DecoderCloseResult.RELEASED) {
            synchronized(surfaceRoutingLock) {
                if (videoDecoders.remove(handle, decoder)) advanceDecoderRouteGenerationLocked(handle)
            }
            output.state.acknowledge(output.state.currentRequest())
            true
        } else {
            Log.e(TAG, "video decoder close timed out handle=$handle result=$result")
            false
        }
    }

    private fun removeTerminalSurfaceOutput(
        handle: AirPlayVideoStreamHandle,
        output: SurfaceLeaseOutput<Surface>,
    ) {
        val removed = synchronized(surfaceRoutingLock) {
            videoDecoders[handle] == null &&
                effectiveSurfaceLocked(handle) == null &&
                surfaceOutputs.remove(handle, output)
        }
        if (removed) output.state.acknowledgeTermination()
    }

    /** Must be called with [surfaceRoutingLock] held. */
    private fun advanceDecoderRouteGenerationLocked(handle: AirPlayVideoStreamHandle) {
        val previous = decoderRouteGenerations[handle] ?: 0L
        check(previous < Long.MAX_VALUE) { "Decoder route generation exhausted" }
        decoderRouteGenerations[handle] = previous + 1L
    }

    private fun requireSafeSurfaceRetirement(
        result: AndroidVideoSurfaceRetirementResult?,
        operation: String,
    ) {
        if (result == null || result.isSafeToReleaseSurface) return
        throw IllegalStateException("$operation did not retire safely: $result")
    }

    @Synchronized
    private fun audioRenderer(type: Int, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[type]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            type,
            format,
            advancedAudioChannelMapping,
            mainMediaAudioBufferDurationMs,
            mediaMetricsMonitor,
            mediaTap,
        ).also {
            audioRenderers[type] = it
        }
    }

    companion object {
        private val processSurfaceReservations = IdentityInstanceRegistry<Surface>()
        private val physicalRouteRevocationExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "carplay-physical-route-revocation").apply { isDaemon = true }
        }

        /**
         * A conservative instant snapshot. RESERVED includes pending attach, decoder close and
         * external handoff tokens; FREE alone is not a race-free permission to attach elsewhere.
         */
        fun surfaceReservationState(surface: Surface): AndroidSurfaceReservationState =
            if (processSurfaceReservations.contains(surface)) {
                AndroidSurfaceReservationState.RESERVED
            } else {
                AndroidSurfaceReservationState.FREE
            }

        internal fun forwardVideoFrameToMediaTap(
            mediaTap: MediaTap,
            identity: AirPlayStreamIdentity,
            naluBytes: ByteArray,
            timing: ScreenFrameTiming,
        ): Boolean = forwardVideoFrameToMediaTap(
            mediaTap,
            identity.type,
            naluBytes,
            timing,
        )

        internal fun forwardVideoFrameToMediaTap(
            mediaTap: MediaTap,
            type: Int,
            naluBytes: ByteArray,
            timing: ScreenFrameTiming,
        ): Boolean {
            val accessUnit = MediaCodecSupport.toLengthPrefixed(naluBytes)
            if (accessUnit.isEmpty()) return false
            mediaTap.onVideoFrame(type, accessUnit, timing)
            return true
        }

        fun createAudioFocus(context: Context): AudioFocusSession = AudioFocusSession(
            context.applicationContext.getSystemService(AudioManager::class.java),
        )

        private const val TAG = "xcertplay-usb"
        private const val LEGACY_MAIN_SCREEN_TYPE = 110
        private const val LEGACY_ALT_SCREEN_TYPE = 111
        private const val DEFAULT_SURFACE_RETIREMENT_MS =
            AndroidVideoSurfaceLease.DEFAULT_RETIREMENT_TIMEOUT_MILLIS
        private const val DECODER_FORCE_CLOSE_TIMEOUT_MS = 2_000L
        private const val TERMINAL_CALLBACK_DRAIN_TIMEOUT_MS = 250L
        private const val SINK_CLOSE_TIMEOUT_MS = 2_000L
        private const val SURFACE_ACK_GRACE_MS = 100L


        private fun legacyIdentity(type: Int) = AirPlayStreamIdentity(type, null, null)
    }

    /** Shared by the visible host and the background media pipeline. */
    class AudioFocusSession internal constructor(private val manager: AudioManager) {
        private val owners = mutableSetOf<Any>()
        private var enabled = true
        private var requested = false
        private var hasFocus = false
        private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChanged, Handler(Looper.getMainLooper()))
            .build()

        @Synchronized
        fun retain(owner: Any) {
            owners.add(owner)
        }

        /** When disabled, focus is never requested so phone audio mixes with other apps. */
        @Synchronized
        fun setEnabled(value: Boolean) {
            if (enabled == value) return
            enabled = value
            if (!value && requested) abandon()
            Log.i(TAG, "audio focus ${if (value) "enabled" else "disabled"}")
        }

        /** Called only when the user enters the host, never by arriving audio packets. */
        @Synchronized
        fun requestFromHost() {
            if (!enabled || owners.isEmpty() || hasFocus) return
            val result = manager.requestAudioFocus(request)
            requested = result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.i(TAG, "audio focus request result=$result")
        }

        @Synchronized
        fun release(owner: Any) {
            owners.remove(owner)
            if (owners.isNotEmpty()) return
            abandon()
        }

        private fun abandon() {
            manager.abandonAudioFocusRequest(request)
            requested = false
            hasFocus = false
            Log.i(TAG, "audio focus released")
        }

        @Synchronized
        private fun onFocusChanged(change: Int) {
            if (owners.isEmpty() || !requested) return
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> hasFocus = true
                AudioManager.AUDIOFOCUS_LOSS -> {
                    requested = false
                    hasFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> hasFocus = false
            }
            // Focus tracks ownership only; the phone's audio keeps flowing at its normal volume.
            Log.i(TAG, "audio focus change=$change hasFocus=$hasFocus")
        }

        private companion object {
            const val TAG = "xcertplay-usb"
        }
    }
}

private sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedUs: Long,
        val presentationTargetMonotonicNanos: Long?,
        val timing: ScreenFrameTiming,
    ) : VideoJob
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private enum class DecoderCloseResult {
    RELEASED,
    RELEASE_FAILED,
    TIMED_OUT,
    INTERRUPTED,
    REQUESTED_FROM_WORKER,
}

private class VideoDecoder(
    initialSurfaceRequest: SurfaceLeaseStateMachine.ApplyRequest<Surface>,
    private val handle: AirPlayVideoStreamHandle,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val timedVideoPresentation: Boolean,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val requestKeyFrame: () -> Boolean,
    private val onSurfaceRequestApplied: (
        SurfaceLeaseStateMachine.ApplyRequest<Surface>,
    ) -> Unit,
    private val onUnavailable: () -> Unit,
    private val onVideoFrameReleased: ((AndroidVideoFrameReleaseReceipt) -> Unit)?,
    private val frameIdentityLedger:
        VideoFrameIdentityLedger<AirPlayVideoStreamHandle, Surface>,
) : Closeable {
    private val queue = VideoWorkQueue<VideoJob>(8)
    @Volatile private var running = true
    @Volatile private var decoderResource: RetryableResourceRelease<MediaCodec>? = null
    private var pump: VideoDecodePump? = null
    private var syncGate: VideoSyncGate? = null
    private var pendingSinceNs = 0L
    private var lastPtsUs = 0L
    private var statsStartNs = System.nanoTime()
    private var inputCount = 0
    private var outputCount = 0
    private var inputRetries = 0
    private var syncSkips = 0
    private var maxOutputAgeUs = 0L
    private var needsKeyFrame = false
    private var lastKeyFrameRequestNs = 0L
    private val surfaceRequestLock = Any()
    @Volatile private var requestedSurfaceRequest = initialSurfaceRequest
    private var outputSurfaceRequest = initialSurfaceRequest
    private var outputSurface: Surface? = initialSurfaceRequest.surface
    private var heldFrame: VideoJob.Frame? = null
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var awaitingConfigLogged = false
    private val submittedFrameTimings = LinkedHashMap<Long, SubmittedFrameTiming>()
    private val renderScheduler = ScreenFrameRenderScheduler()
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val closeRequested = AtomicBoolean(false)
    private val workerReleased = CountDownLatch(1)
    @Volatile private var terminalCloseResult: DecoderCloseResult? = null
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    val isLiveForPhysicalRoute: Boolean
        get() = running && !closeRequested.get() && terminalCloseResult == null

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.control(VideoJob.Config(codec, codecData.copyOf()))
    }

    fun submit(
        nalus: ByteArray,
        arrivalUs: Long,
        presentationTargetMonotonicNanos: Long?,
        timing: ScreenFrameTiming,
    ) {
        queue.frame(VideoJob.Frame(nalus, arrivalUs, presentationTargetMonotonicNanos, timing))
    }

    fun setSurface(request: SurfaceLeaseStateMachine.ApplyRequest<Surface>) {
        synchronized(surfaceRequestLock) {
            if (request.generation > requestedSurfaceRequest.generation) {
                requestedSurfaceRequest = request
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        requestClose()
        val result = awaitClosed(DEFAULT_CLOSE_TIMEOUT_MS)
        if (result != DecoderCloseResult.RELEASED) {
            Log.e(TAG, "video decoder worker did not release result=$result")
        }
    }

    fun requestClose() {
        if (!closeRequested.compareAndSet(false, true)) return
        running = false
        queue.close()
        thread.interrupt()
    }

    fun awaitClosed(timeoutMillis: Long): DecoderCloseResult {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        val deadline = BoundedOperationDeadline(timeoutMillis)
        requestClose()
        if (Thread.currentThread() === thread) return DecoderCloseResult.REQUESTED_FROM_WORKER
        return try {
            if (workerReleased.await(deadline.remainingMillis(), TimeUnit.MILLISECONDS)) {
                retryReleaseAfterWorkerExit(deadline.remainingMillis())
            } else {
                DecoderCloseResult.TIMED_OUT
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            DecoderCloseResult.INTERRUPTED
        }
    }

    private fun run() {
        try {
            acknowledgeSurfaceRequest(outputSurfaceRequest)
            while (running) {
                try {
                    val requested = requestedSurfaceRequest
                    if (outputSurfaceRequest !== requested) changeSurface(requested)
                    if (needsKeyFrame && outputSurface != null &&
                        System.nanoTime() - lastKeyFrameRequestNs >= KEY_FRAME_REQUEST_INTERVAL_NS
                    ) {
                        lastKeyFrameRequestNs = System.nanoTime()
                        Log.i(TAG, "video recovery key frame requested sent=${requestKeyFrame()}")
                    }
                    // Poll output independently of input arrival, including the final/static frame.
                    pump?.tick()
                    logVideoStats()
                    if (pump?.hasPending == true) {
                        check(System.nanoTime() - pendingSinceNs < INPUT_STALL_NS) {
                            "Video decoder input stalled; waiting for a new random access frame"
                        }
                        Thread.sleep(2)
                        continue
                    }
                    if (heldFrame != null && outputSurface == null) {
                        Thread.sleep(5)
                        continue
                    }
                    when (val job = heldFrame ?: queue.poll(5)) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            // Preserve the initial random access picture until a Surface exists.
                            heldFrame = if (outputSurface == null) job else null
                            if (outputSurface != null) feed(job)
                        }
                        null -> Unit
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder failed; restarting at random access", error)
                    if (releaseDecoder() == ResourceReleaseResult.RELEASE_FAILED) {
                        running = false
                        closeRequested.set(true)
                    }
                    needsKeyFrame = true
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            heldFrame = null
            val releaseResult = releaseDecoder()
            try {
                if (releaseResult == ResourceReleaseResult.RELEASED) {
                    // Worker exit plus proven MediaCodec release retires even a request that arrived
                    // during shutdown.
                    acknowledgeSurfaceRequest(requestedSurfaceRequest)
                    terminalCloseResult = DecoderCloseResult.RELEASED
                } else {
                    terminalCloseResult = DecoderCloseResult.RELEASE_FAILED
                }
            } finally {
                workerReleased.countDown()
                onUnavailable()
            }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoderResource != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        awaitingConfigLogged = false
        check(releaseDecoder() == ResourceReleaseResult.RELEASED) {
            "Previous MediaCodec release failed"
        }
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd: ByteArray
        val pps: ByteArray?
        if (codec == VideoCodec.H265) {
            csd = MediaCodecSupport.hevcCodecSpecificData(codecData)
            pps = null
        } else {
            val sets = MediaCodecSupport.avcParameterSets(codecData)
            require(sets.first.isNotEmpty() && sets.second.isNotEmpty()) { "Missing AVC parameter sets" }
            csd = START_CODE + sets.first
            pps = START_CODE + sets.second
        }
        require(csd.isNotEmpty()) { "Missing or invalid video parameter sets" }
        val parameters = VideoParameters.parse(codec, csd)
        val format = MediaFormat.createVideoFormat(mime, parameters.width, parameters.height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            if (pps != null) setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // Unspecified VUI fields remain unspecified; do not force full range or BT.709.
            if (parameters.colorStandard != -1) setInteger(MediaFormat.KEY_COLOR_STANDARD, parameters.colorStandard)
            if (parameters.colorRange != -1) setInteger(MediaFormat.KEY_COLOR_RANGE, parameters.colorRange)
            if (parameters.colorTransfer != -1) setInteger(MediaFormat.KEY_COLOR_TRANSFER, parameters.colorTransfer)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val next = createDecoder(mime)
        val nextResource = RetryableResourceRelease(
            resource = next,
            stopAction = MediaCodec::stop,
            releaseAction = MediaCodec::release,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                next.codecInfo.getCapabilitiesForType(mime)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            next.configure(format, surface, null, 0)
            next.start()
        } catch (error: Exception) {
            decoderResource = nextResource
            if (nextResource.release() == ResourceReleaseResult.RELEASE_FAILED) {
                nextResource.lastFailure?.let(error::addSuppressed)
            } else {
                decoderResource = null
            }
            throw error
        }
        decoderResource = nextResource
        syncGate = VideoSyncGate(codec)
        pump = VideoDecodePump(object : VideoDecodePump.Port {
            override fun drain() = drainOutput(next)
            override fun queue(bytes: ByteArray, presentationTimeUs: Long): Boolean {
                val index = next.dequeueInputBuffer(0)
                if (index < 0) {
                    inputRetries++
                    return false
                }
                val input = checkNotNull(next.getInputBuffer(index)) { "Missing video input buffer" }
                input.clear()
                check(bytes.size <= input.remaining()) { "Video access unit exceeds decoder input capacity" }
                input.put(bytes)
                next.queueInputBuffer(index, 0, bytes.size, presentationTimeUs, 0)
                if (handle.type == 111) {
                    val submitted = submittedFrameTimings[presentationTimeUs]
                    val surface = outputSurface
                    val request = outputSurfaceRequest
                    if (submitted?.authenticatedFrameCounter != null &&
                        submitted.presentationTimeNtp64Bits != null &&
                        surface != null && request.surface === surface
                    ) {
                        frameIdentityLedger.recordQueued(
                            owner = handle,
                            frameCounter = submitted.authenticatedFrameCounter,
                            presentationTimeNtp64Bits = submitted.presentationTimeNtp64Bits,
                            inputPresentationTimeUs = presentationTimeUs,
                            surface = surface,
                            surfaceLeaseGeneration = request.generation,
                            timelineIdRawBits = submitted.timelineIdRawBits,
                        )
                    }
                }
                inputCount++
                return true
            }
        })
        Log.i(TAG, "video SPS parameters=$parameters negotiated=${width}x$height inputFormat=$format")
        renderedFrameLogged = false
        submittedFrameLogged = false
        Log.i(TAG, "video decoder configured name=${next.name} mime=$mime; waiting for random access")
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(request: SurfaceLeaseStateMachine.ApplyRequest<Surface>) {
        if (outputSurfaceRequest === request) return
        // A delayed publisher must never roll the worker back to an older generation.
        if (requestedSurfaceRequest !== request) return
        val surface = request.surface
        if (outputSurface === surface) {
            outputSurfaceRequest = request
            acknowledgeSurfaceRequest(request)
            return
        }
        if (surface == null) {
            check(releaseDecoder() == ResourceReleaseResult.RELEASED) {
                "MediaCodec release failed while detaching Surface"
            }
            outputSurface = null
            outputSurfaceRequest = request
            acknowledgeSurfaceRequest(request)
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoderResource?.resource
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                outputSurface = surface
                outputSurfaceRequest = request
                acknowledgeSurfaceRequest(request)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        check(releaseDecoder() == ResourceReleaseResult.RELEASED) {
            "MediaCodec release failed while changing Surface"
        }
        outputSurface = surface
        outputSurfaceRequest = request
        acknowledgeSurfaceRequest(request)
        if (requestedSurfaceRequest === request) lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        if (decoderResource == null) {
            if (outputSurface == null) return
            val config = lastConfig
            if (config == null) {
                // The phone emits its codec config (opcode=1) before the first
                // frame; frames without one mean it skipped that step (seen with
                // HEVC). Nudge it instead of dropping frames forever.
                if (!awaitingConfigLogged) {
                    awaitingConfigLogged = true
                    Log.i(TAG, "video frames without codec config; requesting keyframe")
                }
                needsKeyFrame = true
                return
            }
            configureDecoder(config)
        }
        val activePump = pump ?: return
        val annexB = MediaCodecSupport.toAnnexB(frame.nalus)
        require(annexB.isNotEmpty()) { "Malformed video access unit" }
        if (syncGate?.accept(annexB) != true) {
            // RASL intentionally skipped after CRA does not mean sync was lost.
            if (syncGate?.waitingForRandomAccess == true) {
                needsKeyFrame = true
                syncSkips++
            }
            return
        }
        needsKeyFrame = false
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(TAG, "video decoder first random access input bytes=${annexB.size}")
        }
        lastPtsUs = maxOf(lastPtsUs + 1, frame.receivedUs)
        submittedFrameTimings[lastPtsUs] = SubmittedFrameTiming(
            arrivalNanos = frame.receivedUs * 1_000L,
            targetNanos = frame.presentationTargetMonotonicNanos.takeIf { timedVideoPresentation },
            authenticatedFrameCounter = frame.timing.authenticatedFrameIdentity?.frameCounter,
            presentationTimeNtp64Bits =
                frame.timing.authenticatedFrameIdentity?.presentationTimeNtp64Bits,
            timelineIdRawBits =
                frame.timing.authenticatedFrameIdentity?.timelineIdRawBits,
        )
        while (submittedFrameTimings.size > MAX_SUBMITTED_FRAME_TIMINGS) {
            submittedFrameTimings.remove(submittedFrameTimings.keys.first())
        }
        activePump.submit(annexB, lastPtsUs)
        pendingSinceNs = System.nanoTime()
        activePump.tick()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val submittedTiming = submittedFrameTimings.remove(info.presentationTimeUs)
                    val surface = outputSurface
                    val surfaceRequest = outputSurfaceRequest
                    val render = surface != null &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    outputCount++
                    maxOutputAgeUs = maxOf(maxOutputAgeUs, System.nanoTime() / 1000 - info.presentationTimeUs)
                    val schedulerNowNanos = System.nanoTime()
                    val timedRelease = if (render && timedVideoPresentation) {
                        renderScheduler.releaseTimestampOrNull(
                            submittedTiming?.targetNanos,
                            schedulerNowNanos,
                        )
                    } else {
                        null
                    }
                    val expectedSurfaceTextureTimestampNanos = if (!render) {
                        null
                    } else {
                        timedRelease ?: try {
                            Math.multiplyExact(info.presentationTimeUs, 1_000L)
                        } catch (_: ArithmeticException) {
                            null
                        }
                    }
                    val matchedIdentity = if (render && handle.type == 111 &&
                        surfaceRequest.surface === surface &&
                        requestedSurfaceRequest === surfaceRequest && !closeRequested.get() &&
                        expectedSurfaceTextureTimestampNanos != null
                    ) {
                        frameIdentityLedger.matchReleased(
                            owner = handle,
                            outputPresentationTimeUs = info.presentationTimeUs,
                            surface = surface,
                            surfaceLeaseGeneration = surfaceRequest.generation,
                            expectedSurfaceTextureTimestampNanos =
                                expectedSurfaceTextureTimestampNanos,
                        )
                    } else {
                        null
                    }
                    val releaseCalledAtNanos = System.nanoTime()
                    if (render && timedRelease != null) {
                        codec.releaseOutputBuffer(index, timedRelease)
                    } else {
                        codec.releaseOutputBuffer(index, render)
                    }
                    val releaseCompletedAtNanos = System.nanoTime()
                    if (render && matchedIdentity != null &&
                        expectedSurfaceTextureTimestampNanos != null &&
                        requestedSurfaceRequest === surfaceRequest && !closeRequested.get()
                    ) {
                        val receipt = AndroidVideoFrameReleaseReceipt.issue(
                            handle = handle,
                            surface = surface,
                            surfaceLeaseGeneration = surfaceRequest.generation,
                            matched = matchedIdentity,
                            codecOutputPresentationTimeUs = info.presentationTimeUs,
                            releaseCalledAtMonotonicNanos = releaseCalledAtNanos,
                            releaseCompletedAtMonotonicNanos = releaseCompletedAtNanos,
                            releaseTargetMonotonicNanos = timedRelease,
                            expectedSurfaceTextureTimestampNanos =
                                expectedSurfaceTextureTimestampNanos,
                        )
                        try {
                            onVideoFrameReleased?.invoke(receipt)
                        } catch (error: Exception) {
                            Log.w(TAG, "video frame-release observer failed", error)
                        }
                    }
                    if (render) {
                        // Input PTS remains arrival-based so metrics and WebDisplay framing retain
                        // their existing clock domain even when the Surface uses a timed release.
                        mediaMetricsMonitor?.recordVideoFrameRendered(
                            arrivalNs = submittedTiming?.arrivalNanos
                                ?: info.presentationTimeUs * 1_000L,
                            renderedNs = releaseCalledAtNanos,
                        )
                    }
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logVideoStats() {
        val now = System.nanoTime()
        if (now - statsStartNs < 5_000_000_000L) return
        if (inputCount + outputCount + inputRetries + syncSkips > 0) {
            val seconds = (now - statsStartNs) / 1e9
            Log.i(TAG, "video stats inputFps=${inputCount / seconds} decodedFps=${outputCount / seconds} " +
                "inputRetries=$inputRetries syncSkips=$syncSkips queued=${queue.size} " +
                "maxOutputAgeMs=${maxOutputAgeUs / 1000} waitingForSync=${syncGate?.waitingForRandomAccess}")
        }
        statsStartNs = now
        inputCount = 0
        outputCount = 0
        inputRetries = 0
        syncSkips = 0
        maxOutputAgeUs = 0
    }

    private fun logOutputFormat(format: MediaFormat) {
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder(): ResourceReleaseResult {
        val resource = decoderResource
        pump = null
        syncGate = null
        submittedFrameTimings.clear()
        frameIdentityLedger.discardPending()
        renderScheduler.reset()
        if (resource != null) {
            needsKeyFrame = true
            val result = releaseWithEvidence(resource) {
                acknowledgeSurfaceRequest(outputSurfaceRequest)
            }
            if (result == ResourceReleaseResult.RELEASE_FAILED) {
                Log.e(TAG, "MediaCodec release failed; retaining resource for retry", resource.lastFailure)
                return ResourceReleaseResult.RELEASE_FAILED
            }
            decoderResource = null
            return ResourceReleaseResult.RELEASED
        }
        // With no MediaCodec, no decoder resource can still reference this Surface generation.
        acknowledgeSurfaceRequest(outputSurfaceRequest)
        return ResourceReleaseResult.RELEASED
    }

    private fun retryReleaseAfterWorkerExit(timeoutMillis: Long): DecoderCloseResult {
        val existing = terminalCloseResult ?: DecoderCloseResult.RELEASE_FAILED
        if (existing == DecoderCloseResult.RELEASED) return existing
        val resource = decoderResource
        if (resource == null) {
            acknowledgeSurfaceRequest(requestedSurfaceRequest)
            return DecoderCloseResult.RELEASED.also { terminalCloseResult = it }
        }
        val result = try {
            resource.awaitTrackedRelease(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return DecoderCloseResult.INTERRUPTED
        }
        return when (result) {
            null -> DecoderCloseResult.TIMED_OUT
            ResourceReleaseResult.RELEASE_FAILED ->
                DecoderCloseResult.RELEASE_FAILED.also { terminalCloseResult = it }
            ResourceReleaseResult.RELEASED -> {
                check(releaseDecoder() == ResourceReleaseResult.RELEASED)
                acknowledgeSurfaceRequest(requestedSurfaceRequest)
                DecoderCloseResult.RELEASED.also { terminalCloseResult = it }
            }
        }
    }

    private fun acknowledgeSurfaceRequest(
        request: SurfaceLeaseStateMachine.ApplyRequest<Surface>,
    ) {
        try {
            onSurfaceRequestApplied(request)
        } catch (error: Throwable) {
            Log.e(TAG, "surface retirement acknowledgement failed request=$request", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_STALL_NS = 2_000_000_000L
        const val KEY_FRAME_REQUEST_INTERVAL_NS = 2_000_000_000L
        const val MAX_SUBMITTED_FRAME_TIMINGS = 64
        const val DEFAULT_CLOSE_TIMEOUT_MS = 2_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }

    private data class SubmittedFrameTiming(
        val arrivalNanos: Long,
        val targetNanos: Long?,
        val authenticatedFrameCounter: Long?,
        val presentationTimeNtp64Bits: Long?,
        val timelineIdRawBits: Long?,
    )
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    private val stream: Int,
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val mainMediaAudioBufferDurationMs: Int,
    mediaMetricsMonitor: MediaMetricsMonitor?,
    private val mediaTap: MediaTap?,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private var trackBytesPerFrame = 0
    private var totalBytesWritten = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadRaw = 0L
    private var lastPlaybackHeadFrames = 0L
    private val audioTimestamp = AudioTimestamp()
    private var lastAudioTimestampQueryNs = 0L
    private var timestampHeadWraps = 0L
    private var lastTimestampHeadRaw = 0L
    private var lastTimestampHeadFrames = 0L
    private var latestOutputLatencyMs: Float? = null
    @Volatile private var mediaMetricsMonitor = mediaMetricsMonitor
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
            }
        }
    }

    fun setMediaMetricsMonitor(monitor: MediaMetricsMonitor?) {
        mediaMetricsMonitor = monitor
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            while (running) handle(queue.take())
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) Log.e(TAG, "audio renderer worker failed", error)
        } finally {
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(mediaFormat, null, null, 0)
                it.start()
                Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val trackChannelCount = if (format.channels >= 2) 2 else 1
        val channelMask = if (trackChannelCount == 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val configuredMainMediaBuffer = MainMediaAudioBuffer.isMainMedia(
            audioType = format.audioType,
            payloadType = format.payloadType,
        )
        val bufferBytes = if (configuredMainMediaBuffer) {
            MainMediaAudioBuffer.bufferSizeBytes(
                durationMs = mainMediaAudioBufferDurationMs,
                sampleRate = format.sampleRate,
                channelCount = trackChannelCount,
                minBufferBytes = minBuffer,
            )
        } else {
            maxOf(minBuffer * 4, MIN_TRACK_BUFFER_BYTES)
        }
        startThresholdBytes = if (format.audioType == "telephony" || format.audioType == "speechrecognition") {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        } else {
            maxOf(minBuffer, MIN_START_BUFFER_BYTES)
        }
        track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // AudioTrack consumes 16-bit PCM, so frame size follows the channel mask configured above.
        trackBytesPerFrame = trackChannelCount * BYTES_PER_PCM_16_SAMPLE
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "bufferBytes=$bufferBytes" +
                if (configuredMainMediaBuffer) {
                    " configuredDurationMs=${MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)}"
                } else {
                    ""
                },
        )
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun audioAttributes(): AudioAttributes {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        val usage = usageFor(selection.channel)
        val contentType = contentTypeFor(selection.contentType)
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
            .also {
                Log.i(
                    TAG,
                    "audio route type=${format.payloadType} audioType=${format.audioType} " +
                        "mode=$mode channel=${selection.channel} " +
                        "usage=$usage contentType=$contentType",
                )
            }
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (mediaTap?.takesAudio() == true) {
            forwardPcm(data, offset, length)
            return
        }
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            if (count <= 0) break
            written += count
            totalBytesWritten += count
            val monitor = mediaMetricsMonitor
            if (monitor != null) {
                val nowNs = System.nanoTime()
                val totalFramesWritten = totalBytesWritten / trackBytesPerFrame
                monitor.recordAudioBuffer(
                    totalFramesWritten = totalFramesWritten,
                    playbackHeadFrames = playbackHeadFrames(track),
                    sampleRate = format.sampleRate,
                    nowNs = nowNs,
                    outputLatencyMs = outputLatencyMs(track, totalFramesWritten, nowNs),
                )
            }
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    track.play()
                    playbackStarted = true
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    /** Remote playback silences the local track; returning to it starts over with prebuffering. */
    private fun forwardPcm(data: ByteArray, offset: Int, length: Int) {
        val tap = mediaTap ?: return
        val track = track
        if (track != null && (playbackStarted || prebufferBytes > 0)) {
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            playbackStarted = false
            prebufferBytes = 0
            fadeApplied = false
            Log.i(TAG, "audio forwarded remotely type=${format.payloadType}")
        }
        tap.onAudioPcm(stream, format.sampleRate, if (format.channels >= 2) 2 else 1, data, offset, length)
    }

    private fun playbackHeadFrames(track: AudioTrack): Long {
        val raw = track.playbackHeadPosition.toLong() and UINT32_MASK
        if (raw < lastPlaybackHeadRaw && lastPlaybackHeadRaw - raw > UINT32_HALF_RANGE) {
            playbackHeadWraps++
        }
        lastPlaybackHeadRaw = raw
        val unwrapped = playbackHeadWraps * UINT32_MODULUS + raw
        lastPlaybackHeadFrames = maxOf(lastPlaybackHeadFrames, unwrapped)
        return lastPlaybackHeadFrames
    }

    private fun outputLatencyMs(
        track: AudioTrack,
        totalFramesWritten: Long,
        nowNs: Long,
    ): Float? {
        if (!playbackStarted) return null
        if (nowNs - lastAudioTimestampQueryNs < AUDIO_TIMESTAMP_INTERVAL_NS) {
            return latestOutputLatencyMs
        }
        lastAudioTimestampQueryNs = nowNs
        if (!track.getTimestamp(audioTimestamp)) {
            latestOutputLatencyMs = null
            return null
        }
        val raw = audioTimestamp.framePosition and UINT32_MASK
        if (raw < lastTimestampHeadRaw && lastTimestampHeadRaw - raw > UINT32_HALF_RANGE) {
            timestampHeadWraps++
        }
        lastTimestampHeadRaw = raw
        val unwrapped = timestampHeadWraps * UINT32_MODULUS + raw
        lastTimestampHeadFrames = maxOf(lastTimestampHeadFrames, unwrapped)
        val elapsedNs = (nowNs - audioTimestamp.nanoTime).coerceAtLeast(0L)
        val elapsedFrames = (elapsedNs.toDouble() * format.sampleRate / NANOS_PER_SECOND).toLong()
        val presentedFrames = minOf(totalFramesWritten, lastTimestampHeadFrames + elapsedFrames)
        return MediaMetricsMonitor.audioLatencyMs(
            totalFramesWritten,
            presentedFrames,
            format.sampleRate,
        ).also { latestOutputLatencyMs = it }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_QUEUED_PACKETS = 64
        const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
        const val MIN_START_BUFFER_BYTES = 4 * 1024
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val AUDIO_TIMESTAMP_INTERVAL_NS = 200_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_PCM_16_SAMPLE = 2
        const val UINT32_MASK = 0xffff_ffffL
        const val UINT32_HALF_RANGE = 0x8000_0000L
        const val UINT32_MODULUS = 0x1_0000_0000L
    }
}
