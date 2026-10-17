package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.ScreenFrameTiming
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId

/** Absolute bounds for one detached type-2 / authenticated type-111 frame comparison. */
data class UiSyncType2FrameCoordinationLimits(
    val maximumTransitionAgeNanos: Long = 250_000_000L,
    val maximumFrameAgeNanos: Long = 250_000_000L,
    val maximumCalibrationAgeNanos: Long = 100_000_000L,
    val maximumClockErrorNanos: Long = 5_000_000L,
    val maximumProjectionSeparationNanos: Long = 16_000_000L,
    val maximumTargetDistanceFromNowNanos: Long = 500_000_000L,
) {
    init {
        require(maximumTransitionAgeNanos in 1L..1_000_000_000L)
        require(maximumFrameAgeNanos in 1L..1_000_000_000L)
        require(maximumCalibrationAgeNanos in 1L..1_000_000_000L)
        require(maximumClockErrorNanos in 0L..50_000_000L)
        require(maximumProjectionSeparationNanos in 1L..100_000_000L)
        require(maximumTargetDistanceFromNowNanos in 1L..1_000_000_000L)
    }
}

/**
 * Explicit, live evidence that *both* raw NTP64 fields have been calibrated into the same local
 * monotonic domain. A plain [com.shilapi.xcertplay.airplay.NtpClock] media projection is not this
 * evidence: its relationship to the UI Sync type-2 NTP/host fields has not been established.
 *
 * The future integration must issue one session/stream/display-bound lease only after validating
 * that relationship, its clock era and its error/drift bound. No default implementation exists.
 * Its methods must be non-blocking and return null whenever either projection is not calibrated.
 */
interface UiSyncType2SharedClockCalibration {
    val sessionEpoch: Any
    val displayId: UltraUiSyncDisplayId
    val videoStream: AirPlayVideoStreamHandle
    /** Referential generation token; changing calibration requires a different token. */
    val calibrationEpoch: Any
    val sampledAtMonotonicNanos: Long
    val maximumErrorNanos: Long
    val maximumDriftNanos: Long
    val isLive: Boolean

    fun projectType2NtpToMonotonicNanos(rawNtp64Bits: Long): Long?
    fun projectAuthenticatedFrameNtpToMonotonicNanos(rawNtp64Bits: Long): Long?
}

/**
 * A separate, positive interpretation of the *entire* type-2 region and its transform in the
 * output's coordinate space for this exact authenticated frame. Type-2 dictionaries alone do not
 * establish bounds, clipping, coordinate space, or opaque pixel content. The future renderer must
 * independently substantiate these claims; there is intentionally no default proof.
 */
interface UiSyncType2OpaqueRegionTransformProof {
    val sessionEpoch: Any
    val displayId: UltraUiSyncDisplayId
    val videoStream: AirPlayVideoStreamHandle
    val transition: CarPlayUiSyncSemanticCommand.TransitionData
    val transitionRevision: Long
    val frameCounter: Long
    /** Referential output-coordinate-space token supplied by the same renderer binding. */
    val outputCoordinateSpace: Any
    val regionIdentifier: String
    val transform2D: List<Double>
    /** Covers the complete frame region, including any otherwise unmodeled clipping/occlusion. */
    val isFullyOpaqueAndComplete: Boolean
    val isLive: Boolean
}

enum class UiSyncType2FrameCoordinationFailure {
    WRONG_SCOPE,
    STALE_OR_OUT_OF_ORDER,
    UNPROVEN_TRANSITION,
    UNAUTHENTICATED_OR_REPLAYED_FRAME,
    UNCALIBRATED_CLOCK,
    UNPROVEN_OPAQUE_TRANSFORM,
    OUTSIDE_BOUNDED_WINDOW,
    RETIRED,
}

sealed interface UiSyncType2FrameCoordinationResult {
    /** Diagnostic pairing only: never a renderer-completion, foreground, or pixel-authority permit. */
    data class Paired(
        val transitionRevision: Long,
        val authenticatedFrameCounter: Long,
        val frameTargetMonotonicNanos: Long,
    ) : UiSyncType2FrameCoordinationResult

    data class Rejected(val reason: UiSyncType2FrameCoordinationFailure) :
        UiSyncType2FrameCoordinationResult
}

/**
 * One-shot, fail-closed type-2 / type-111 frame coordination for an *already bound* renderer.
 * This never subscribes to UI Sync, changes video scheduling, draws pixels, or advertises Ultra.
 * The owner must present the exact reducer result and retained snapshot together; using only
 * `state.lastTransitionData` would silently reuse an older type-2 after a newer UI Sync event.
 */
class UiSyncType2FrameCoordinationGate(
    private val sessionEpoch: Any,
    private val displayId: UltraUiSyncDisplayId,
    private val videoStream: AirPlayVideoStreamHandle,
    private val outputCoordinateSpace: Any,
    private val semanticReducer: CarPlayUiSyncSemanticReducer,
    private val limits: UiSyncType2FrameCoordinationLimits =
        UiSyncType2FrameCoordinationLimits(),
) {
    init {
        require(semanticReducer.state().displayId == displayId) {
            "UI Sync reducer must belong to the exact display"
        }
        require(videoStream.type == 111 &&
            videoStream.validatedDisplayUuid == AirPlayInfoPlist.ALT_UUID
        ) { "A validated type-111 alternate-display stream is required" }
    }

    private data class PendingTransition(
        val state: CarPlayUiSyncSemanticState,
        val command: CarPlayUiSyncSemanticCommand.TransitionData,
        val acceptedAtMonotonicNanos: Long,
    )

    private val decoder = CarPlayUiSyncSemanticDecoder()
    private val lock = Any()
    private var retired = false
    private var highestTransitionRevision = 0L
    private var highestPairedFrameCounter = -1L
    private var pending: PendingTransition? = null

    fun observeTransition(
        ownerSessionEpoch: Any,
        reduced: CarPlayUiSyncSemanticReduceResult,
        snapshot: UltraPresentationSnapshot,
        nowMonotonicNanos: Long,
    ): UiSyncType2FrameCoordinationFailure? = synchronized(lock) {
        if (retired) return@synchronized UiSyncType2FrameCoordinationFailure.RETIRED
        pending = null
        if (ownerSessionEpoch !== sessionEpoch || snapshot.displayId != displayId) {
            return@synchronized UiSyncType2FrameCoordinationFailure.WRONG_SCOPE
        }
        val state = (reduced as? CarPlayUiSyncSemanticReduceResult.Applied)?.state
            ?: return@synchronized UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION
        if (state.displayId != displayId || state.closed || snapshot.closed ||
            snapshot.revision != state.revision ||
            snapshot.droppedEventCount != state.droppedEventCount ||
            snapshot.revision <= highestTransitionRevision ||
            semanticReducer.state() !== state
        ) {
            return@synchronized UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER
        }
        val last = snapshot.events.lastOrNull()
            ?: return@synchronized UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION
        if (last.revision != snapshot.revision ||
            last.type != CarPlayUiSyncCommandType.TRANSITION_DATA.wireValue
        ) {
            return@synchronized UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION
        }
        val decoded = decoder.decode(last) as? CarPlayUiSyncSemanticDecodeResult.Decoded
            ?: return@synchronized UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION
        val command = decoded.command as? CarPlayUiSyncSemanticCommand.TransitionData
            ?: return@synchronized UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION
        if (command != state.lastTransitionData) {
            return@synchronized UiSyncType2FrameCoordinationFailure.UNPROVEN_TRANSITION
        }
        val acceptedAt = last.acceptedAtMonotonicNanos
            ?: return@synchronized UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW
        if (!nonnegativeDeltaWithin(
                nowMonotonicNanos, acceptedAt, limits.maximumTransitionAgeNanos,
            )
        ) {
            return@synchronized UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW
        }
        highestTransitionRevision = state.revision
        pending = PendingTransition(state, state.lastTransitionData!!, acceptedAt)
        null
    }

    fun pairFrame(
        ownerSessionEpoch: Any,
        currentState: CarPlayUiSyncSemanticState,
        frameStream: AirPlayVideoStreamHandle,
        timing: ScreenFrameTiming,
        calibration: UiSyncType2SharedClockCalibration?,
        geometry: UiSyncType2OpaqueRegionTransformProof?,
        nowMonotonicNanos: Long,
    ): UiSyncType2FrameCoordinationResult = synchronized(lock) {
        if (retired) return@synchronized rejected(UiSyncType2FrameCoordinationFailure.RETIRED)
        // Every rejection consumes the candidate. A later, newly proved type-2 is required.
        val candidate = pending.also { pending = null }
            ?: return@synchronized rejected(
                UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER,
            )
        if (ownerSessionEpoch !== sessionEpoch || frameStream !== videoStream ||
            currentState.displayId != displayId
        ) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.WRONG_SCOPE)
        }
        if (currentState !== candidate.state || currentState.closed ||
            semanticReducer.state() !== candidate.state
        ) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER)
        }
        val frame = timing.authenticatedFrameIdentity
            ?: return@synchronized rejected(
                UiSyncType2FrameCoordinationFailure.UNAUTHENTICATED_OR_REPLAYED_FRAME,
            )
        val frameNtp = frame.presentationTimeNtp64Bits
        if (frameNtp == null || timing.presentationTimeNtp64Bits != frameNtp ||
            frame.frameCounter <= highestPairedFrameCounter
        ) {
            return@synchronized rejected(
                UiSyncType2FrameCoordinationFailure.UNAUTHENTICATED_OR_REPLAYED_FRAME,
            )
        }
        if (!nonnegativeDeltaWithin(
                nowMonotonicNanos, timing.arrivalMonotonicNanos,
                limits.maximumFrameAgeNanos,
            ) || !nonnegativeDeltaWithin(
                timing.arrivalMonotonicNanos, candidate.acceptedAtMonotonicNanos,
                limits.maximumTransitionAgeNanos,
            )
        ) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW)
        }
        if (!geometryIsProved(
                geometry, candidate.command, candidate.state.revision, frame.frameCounter,
            )
        ) {
            return@synchronized rejected(
                UiSyncType2FrameCoordinationFailure.UNPROVEN_OPAQUE_TRANSFORM,
            )
        }
        val clock = calibration
            ?: return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        if (clock.sessionEpoch !== sessionEpoch || clock.displayId != displayId ||
            clock.videoStream !== videoStream || !clock.isLive ||
            clock.maximumErrorNanos < 0L || clock.maximumDriftNanos < 0L ||
            !nonnegativeDeltaWithin(
                nowMonotonicNanos, clock.sampledAtMonotonicNanos,
                limits.maximumCalibrationAgeNanos,
            )
        ) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        }
        val totalError = checkedAdd(clock.maximumErrorNanos, clock.maximumDriftNanos)
            ?: return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        if (totalError > limits.maximumClockErrorNanos) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        }
        val calibrationEpoch = clock.calibrationEpoch
        val type2Target = try {
            clock.projectType2NtpToMonotonicNanos(candidate.command.presentationNtpTimeBits)
        } catch (_: RuntimeException) {
            null
        } ?: return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        val frameTarget = try {
            clock.projectAuthenticatedFrameNtpToMonotonicNanos(frameNtp)
        } catch (_: RuntimeException) {
            null
        } ?: return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        if (!clock.isLive || clock.calibrationEpoch !== calibrationEpoch) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.UNCALIBRATED_CLOCK)
        }
        if (!absoluteDeltaWithin(
                type2Target, frameTarget,
                limits.maximumProjectionSeparationNanos - totalError,
            ) || !absoluteDeltaWithin(
                nowMonotonicNanos, frameTarget,
                limits.maximumTargetDistanceFromNowNanos,
            )
        ) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.OUTSIDE_BOUNDED_WINDOW)
        }
        if (geometry?.isLive != true || semanticReducer.state() !== candidate.state) {
            return@synchronized rejected(UiSyncType2FrameCoordinationFailure.STALE_OR_OUT_OF_ORDER)
        }
        highestPairedFrameCounter = frame.frameCounter
        UiSyncType2FrameCoordinationResult.Paired(
            transitionRevision = candidate.state.revision,
            authenticatedFrameCounter = frame.frameCounter,
            frameTargetMonotonicNanos = frameTarget,
        )
    }

    fun retire(ownerSessionEpoch: Any): Boolean = synchronized(lock) {
        if (ownerSessionEpoch !== sessionEpoch) return@synchronized false
        pending = null
        retired = true
        true
    }

    private fun geometryIsProved(
        proof: UiSyncType2OpaqueRegionTransformProof?,
        transition: CarPlayUiSyncSemanticCommand.TransitionData,
        revision: Long,
        frameCounter: Long,
    ): Boolean {
        if (proof == null || !proof.isLive || !proof.isFullyOpaqueAndComplete ||
            proof.sessionEpoch !== sessionEpoch || proof.displayId != displayId ||
            proof.videoStream !== videoStream || proof.transition !== transition ||
            proof.transitionRevision != revision || proof.frameCounter != frameCounter ||
            proof.outputCoordinateSpace !== outputCoordinateSpace
        ) return false
        // Until the full region schema is known, even an attestation may cover only this narrow,
        // single-region, explicit-opacity affine shape. Unknown fields might encode clipping.
        if (transition.regions.size != 1) return false
        val raw = transition.regions.single().value as? Map<*, *> ?: return false
        if (raw.keys != setOf("identifier", "opacity", "transform2D")) return false
        val region = transition.regionDiagnosticViews().single()
        val identifier = (region.identifier as? CarPlayUiSyncRegionDiagnosticField.Available<*>)
            ?.value as? String ?: return false
        val opacity = (region.opacity as? CarPlayUiSyncRegionDiagnosticField.Available<*>)
            ?.value as? Double ?: return false
        val transform = (region.transform2D as? CarPlayUiSyncRegionDiagnosticField.Available<*>)
            ?.value as? List<*> ?: return false
        if (transform.any { it !is Double }) return false
        @Suppress("UNCHECKED_CAST")
        val affine = transform as List<Double>
        if (identifier.isEmpty() || opacity != 1.0 ||
            region.sourceTransform3D !== CarPlayUiSyncRegionDiagnosticField.Missing ||
            proof.regionIdentifier != identifier || proof.transform2D != affine ||
            !isInvertibleAffine(affine) || !proof.isLive
        ) return false
        return true
    }

    private fun isInvertibleAffine(values: List<Double>): Boolean {
        if (values.size != 6 || values.any { !it.isFinite() }) return false
        val determinant = values[0] * values[3] - values[1] * values[2]
        return determinant.isFinite() && determinant != 0.0
    }

    private fun rejected(reason: UiSyncType2FrameCoordinationFailure) =
        UiSyncType2FrameCoordinationResult.Rejected(reason)
}

private fun checkedAdd(first: Long, second: Long): Long? = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    null
}

private fun nonnegativeDeltaWithin(later: Long, earlier: Long, maximum: Long): Boolean = try {
    Math.subtractExact(later, earlier) in 0L..maximum
} catch (_: ArithmeticException) {
    false
}

private fun absoluteDeltaWithin(first: Long, second: Long, maximum: Long): Boolean =
    maximum >= 0L && try {
        val delta = Math.subtractExact(first, second)
        delta != Long.MIN_VALUE && kotlin.math.abs(delta) <= maximum
    } catch (_: ArithmeticException) {
        false
    }
