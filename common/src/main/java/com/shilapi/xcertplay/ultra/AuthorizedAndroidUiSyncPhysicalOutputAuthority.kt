package com.shilapi.xcertplay.ultra

import android.view.Surface
import com.shilapi.xcertplay.airplay.UltraRevealEvent
import com.shilapi.xcertplay.airplay.UltraRevealTrace
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifest
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.CarPlayWebDisplayFactory
import com.shilapi.xcertplay.HostDisplaySurfaceKind
import com.shilapi.xcertplay.HostDisplaySurfaceVersion
import com.shilapi.xcertplay.HostPhysicalDisplaySurfaceRegistry
import com.shilapi.xcertplay.HostPhysicalTargetInspection
import com.shilapi.xcertplay.MainPhysicalUiPermitStatus
import com.shilapi.xcertplay.UltraProfileSessionToken
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationProof
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationStatus
import com.shilapi.xcertplay.UltraProfileSceneUseAttempt
import com.shilapi.xcertplay.sameInstrumentSignalSpec
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncClusterPhoneEpochCoverResult
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncClusterPhoneEpochCoverTicket
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalActivationResult
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAvailability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBackendLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputSessionContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalLayerDraw
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayReceipt
import com.shilapi.xcertplay.media.AndroidLegacySurfaceHandoffResult
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AndroidVideoSurfaceExternalUseToken
import com.shilapi.xcertplay.media.AndroidVideoSurfaceHandoffReservation
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** A reservation is kept even while an EGL commit or retirement has an uncertain result. */
internal interface PhysicalLegacySurfaceReservation : Closeable {
    val isHeld: Boolean
    /** Present only for the exact type-111 route transferred from a frozen Host target. */
    val transferredExternalRoute: AndroidMediaSink.PhysicalExternalLegacyRouteLease? get() = null
    fun beginExternalUse(): Any
    fun confirmExternalUseRetired(token: Any)
}

internal sealed interface PhysicalLegacyHandoffResult {
    class Reserved(val reservation: PhysicalLegacySurfaceReservation) : PhysicalLegacyHandoffResult
    data object Denied : PhysicalLegacyHandoffResult
    class Uncertain(
        val cause: Throwable,
        val reservation: PhysicalLegacySurfaceReservation? = null,
    ) : PhysicalLegacyHandoffResult
}

/** The product adapter below is the only sink/WebDisplay retirement path supplied to the root. */
internal fun interface PhysicalLegacySurfaceHandoffPort {
    fun retireAndReserve(type: Int, surface: Surface): PhysicalLegacyHandoffResult

    /** Product implementations may require the exact Host version before retiring a route. */
    fun retireAndReserveExact(
        type: Int,
        surface: Surface,
        version: HostDisplaySurfaceVersion,
    ): PhysicalLegacyHandoffResult = retireAndReserve(type, surface)
}

/** No Activity is retained. A currently selected WebDisplay preview is retired synchronously. */
internal class AndroidMediaSinkPhysicalLegacyHandoffPort(
    private val sink: AndroidMediaSink,
    private val frozenExternalRoute: (
        HostDisplaySurfaceVersion,
        Surface,
    ) -> AndroidMediaSink.PhysicalExternalLegacyRouteLease? = { _, _ -> null },
) : PhysicalLegacySurfaceHandoffPort {
    override fun retireAndReserveExact(
        type: Int,
        surface: Surface,
        version: HostDisplaySurfaceVersion,
    ): PhysicalLegacyHandoffResult {
        val transferredRoute = when {
            type == 110 && version.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN -> null
            type == 111 && version.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ->
                frozenExternalRoute(version, surface)?.takeIf {
                    it.type == type && it.surface === surface && it.isCurrent
                } ?: return PhysicalLegacyHandoffResult.Denied
            else -> return PhysicalLegacyHandoffResult.Denied
        }
        return retireAndReserveRoute(type, surface, transferredRoute)
    }

    // Retained only as a deterministic legacy/test seam. The physical authority always calls
    // retireAndReserveExact, so a type/surface pair cannot transfer an external route claim.
    override fun retireAndReserve(type: Int, surface: Surface): PhysicalLegacyHandoffResult =
        retireAndReserveRoute(type, surface, null)

    private fun retireAndReserveRoute(
        type: Int,
        surface: Surface,
        transferredRoute: AndroidMediaSink.PhysicalExternalLegacyRouteLease?,
    ): PhysicalLegacyHandoffResult {
        // Legacy rollback is deliberately disabled: if Host withdraws a target, restoring its
        // decoder after releasing the borrow could target a native Surface being destroyed.
        val gate = (if (transferredRoute == null) sink.beginPhysicalSurfaceHandoff(type, surface)
            else sink.beginPhysicalSurfaceHandoff(transferredRoute))
            ?: return PhysicalLegacyHandoffResult.Denied
        var held: SinkReservation? = null
        try {
            // A previously attached WebDisplay may still remember its *old* type-111 preview
            // after the physical route replaced the sink binding. That stale metadata does not
            // own this Surface. The typed sink gate excludes all preview attaches until transfer
            // retires; do not compare the old preview against the new physical Surface.
            val webSession = if (transferredRoute == null) CarPlayWebDisplayFactory.find(sink)
                else null
            if (webSession?.owns(type) == true &&
                !webSession.suspendPreviewForPhysicalHandoff(type, surface)
            ) {
                gate.close()
                return PhysicalLegacyHandoffResult.Denied
            }
            val captured = sink.captureLegacySurfaceLease(type, surface)
            val reservation =
            when (val retired = captured?.retireAndReserveForHandoff()) {
                is AndroidLegacySurfaceHandoffResult.Reserved -> retired.reservation
                is AndroidLegacySurfaceHandoffResult.Uncertain -> error(
                    "Legacy decoder retirement is unproven: ${retired.reason}",
                )
                is AndroidLegacySurfaceHandoffResult.Failed ->
                    throw retired.cause
                AndroidLegacySurfaceHandoffResult.NoMatchingBinding -> {
                    gate.close()
                    return PhysicalLegacyHandoffResult.Denied
                }
                null -> sink.tryReserveUnboundSurfaceForHandoff(type, surface)
            }
            if (reservation == null) {
                gate.close()
                return PhysicalLegacyHandoffResult.Denied
            }
            held = SinkReservation(reservation, gate, transferredRoute)
            check(reservation.matches(sink, type, surface) && reservation.isHeld) {
                "Exact legacy Surface reservation changed during handoff"
            }
            check(transferredRoute == null || transferredRoute.isCurrent) {
                "External route changed during its typed Surface handoff"
            }
            gate.markReserved()
            // clearPreview is identity-checked by the WebDisplay session. Its local decoder route
            // was already retired by the sink above; the process reservation blocks reattachment.
            webSession?.retirePreviewForPhysicalHandoff(type, surface)
            check(held.isHeld) { "Legacy Surface reservation or preview gate was released" }
            return PhysicalLegacyHandoffResult.Reserved(held)
        } catch (failure: Throwable) {
            if (held == null) runCatching { gate.close() }.exceptionOrNull()?.let {
                if (it !== failure) failure.addSuppressed(it)
            }
            return PhysicalLegacyHandoffResult.Uncertain(failure, held)
        }
    }

    private class SinkReservation(
        private val delegate: AndroidVideoSurfaceHandoffReservation,
        private val gate: AndroidMediaSink.PreviewPhysicalHandoffGate,
        override val transferredExternalRoute: AndroidMediaSink.PhysicalExternalLegacyRouteLease?,
    ) : PhysicalLegacySurfaceReservation {
        override val isHeld: Boolean get() = delegate.isHeld && gate.isHeld &&
            (transferredExternalRoute?.isCurrent != false)
        override fun beginExternalUse(): Any = delegate.beginExternalUse()
        override fun confirmExternalUseRetired(token: Any) = delegate.confirmExternalUseRetired(
            token as AndroidVideoSurfaceExternalUseToken,
        )
        override fun close() {
            delegate.close()
            gate.close()
        }
    }
}

/**
 * Opaque, exact-frame UI-cover capability. A same-module caller can construct a lookalike but
 * cannot obtain the Authority instance's private issuer nonce or turn data into a frame proof.
 */
internal class AuthorizedExternalClusterUiPermit internal constructor(
    internal val owner: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
    internal val issuerNonce: Any,
    val version: HostDisplaySurfaceVersion,
    val surface: Surface,
    val producerTimestampNanos: Long,
    val outputBatchGeneration: Long,
    val outputGeneration: Long,
    val clearBeforeReveal: Boolean,
    private val stillAuthorized: () -> Boolean,
) {
    fun isCurrentFor(candidateVersion: HostDisplaySurfaceVersion, candidateSurface: Surface): Boolean =
        version == candidateVersion && surface === candidateSurface &&
            producerTimestampNanos > 0L && outputBatchGeneration > 0L &&
            outputGeneration > 0L && owner.authenticatesExternalCoverPermit(this) &&
            runCatching(stillAuthorized).getOrDefault(false)
}

/** One exact signed MAIN scene, EGL producer swap, and Activity-owned UI-cover generation. */
internal class AuthorizedMainPhysicalUiPermit internal constructor(
    internal val owner: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
    internal val issuerNonce: Any,
    val version: HostDisplaySurfaceVersion,
    val surface: Surface,
    val activityGeneration: Any,
    val producerTimestampNanos: Long,
    val outputBatchGeneration: Long,
    val outputGeneration: Long,
    val signedSceneRevision: Long,
    private val authorizationStatusProbe: (() -> MainPhysicalUiPermitStatus)? = null,
    private val onMissedFrame: (observedNanos: Long) -> Unit = {},
    private val stillAuthorized: () -> Boolean,
) {
    internal fun onMissedProducerFrame(observedNanos: Long) {
        if (observedNanos > producerTimestampNanos &&
            owner.authenticatesMainCoverPermit(this)
        ) onMissedFrame(observedNanos)
    }
    fun authorizationFor(
        candidateVersion: HostDisplaySurfaceVersion,
        candidateSurface: Surface,
        candidateActivityGeneration: Any,
    ): MainPhysicalUiPermitStatus {
        if (version != candidateVersion || surface !== candidateSurface ||
            activityGeneration !== candidateActivityGeneration ||
            version.kind != HostDisplaySurfaceKind.PHYSICAL_MAIN ||
            producerTimestampNanos <= 0L || outputBatchGeneration <= 0L ||
            outputGeneration <= 0L || signedSceneRevision <= 0L ||
            !owner.authenticatesMainCoverPermit(this)
        ) return MainPhysicalUiPermitStatus.REVOKED
        return runCatching {
            authorizationStatusProbe?.invoke() ?: if (stillAuthorized()) {
                MainPhysicalUiPermitStatus.CURRENT
            } else MainPhysicalUiPermitStatus.REVOKED
        }.getOrDefault(MainPhysicalUiPermitStatus.REVOKED)
    }

    fun isCurrentFor(
        candidateVersion: HostDisplaySurfaceVersion,
        candidateSurface: Surface,
        candidateActivityGeneration: Any,
    ): Boolean = authorizationFor(
        candidateVersion, candidateSurface, candidateActivityGeneration,
    ) == MainPhysicalUiPermitStatus.CURRENT
}

/** Pure validation of the immutable EGL receipt; only the Authority can mint the UI permit. */
internal data class MainPhysicalPresentedFrameSeal(
    val signedSceneRevision: Long,
    val producerTimestampNanos: Long,
    val outputBatchGeneration: Long,
    val outputGeneration: Long,
)

/** Phase 1 revokes permits synchronously; only phase 2 may block on the Host UI cover ACK. */
internal class MainSemanticPhysicalCoverTicket internal constructor(
    private val completeCover: () -> Boolean,
) {
    fun coverExactMainUi(): Boolean = completeCover()
}

/** A re-cover invalidates every old MAIN permit until a later scene and producer frame exist. */
internal class MainNonterminalCoverGeneration {
    private var epoch = 0L
    private var minimumSceneRevision = 0L
    private var minimumProducerTimestamp = 0L
    private var lastPermitSceneRevision = 0L
    private var lastPermitProducerTimestamp = 0L

    @Synchronized
    fun coverAfterSubmittedScene(submittedSceneRevision: Long): Long {
        minimumSceneRevision = maxOf(
            minimumSceneRevision, lastPermitSceneRevision, submittedSceneRevision,
        )
        minimumProducerTimestamp = maxOf(
            minimumProducerTimestamp, lastPermitProducerTimestamp,
        )
        epoch = Math.addExact(epoch, 1L)
        return epoch
    }

    @Synchronized
    fun admitPresented(sceneRevision: Long, producerTimestamp: Long): Long? {
        if (sceneRevision <= minimumSceneRevision ||
            producerTimestamp <= minimumProducerTimestamp
        ) return null
        lastPermitSceneRevision = maxOf(lastPermitSceneRevision, sceneRevision)
        lastPermitProducerTimestamp = maxOf(lastPermitProducerTimestamp, producerTimestamp)
        return epoch
    }

    @Synchronized
    fun isCurrent(candidateEpoch: Long): Boolean = epoch == candidateEpoch
}

/** Submission budget belongs to the unchanged signed scene, not each producer permit. */
internal class MainSignedSceneReproofBudget(
    private val maximumAttempts: Int = 2,
) {
    private var attempts = 0

    @Synchronized
    fun isExhausted(): Boolean = attempts >= maximumAttempts

    @Synchronized
    fun reserve(): Boolean {
        if (isExhausted()) return false
        attempts++
        return true
    }
}

internal fun mainPhysicalPresentedFrameSealOrNull(
    destination: DisplayDestination,
    signedSceneRevision: Long,
    graphRevision: Long,
    physicalBatchGeneration: Long,
    result: UltraEglRenderResult,
    requiredLocalDraws: Set<UltraEglLocalLayerDraw>,
    localFirstFrameReceipt: UltraEglLocalFirstFrameReceipt?,
): MainPhysicalPresentedFrameSeal? {
    if (destination.role != DisplayDestinationRole.MAIN || signedSceneRevision <= 0L ||
        graphRevision <= 0L || physicalBatchGeneration <= 0L ||
        result.revision != signedSceneRevision ||
        result.disposition != UltraEglRenderDisposition.PRESENTED ||
        result.graphRevision != graphRevision ||
        result.outputBatchGeneration != physicalBatchGeneration
    ) return null
    val outputGeneration = result.outputGenerationsByDestination[destination]
        ?.takeIf { it > 0L } ?: return null
    val timestamp = if (requiredLocalDraws.isEmpty()) {
        result.producerTimestampNanosByDestination[destination]
    } else {
        val receipt = localFirstFrameReceipt ?: return null
        if (receipt.revision != signedSceneRevision ||
            receipt.graphRevision != graphRevision ||
            receipt.physicalBatchGeneration != physicalBatchGeneration ||
            receipt.outputBatchGeneration != physicalBatchGeneration ||
            receipt.outputGenerationsByDestination[destination] != outputGeneration ||
            receipt.requiredDraws.filterTo(LinkedHashSet()) {
                it.destination == destination
            } != requiredLocalDraws
        ) return null
        receipt.producerTimestampNanosByDestination[destination]
    }?.takeIf { it > 0L } ?: return null
    return MainPhysicalPresentedFrameSeal(
        signedSceneRevision, timestamp, physicalBatchGeneration, outputGeneration,
    )
}

/** Exact receipt retry ledger. A failed Host/UI arm never consumes its producer timestamp. */
internal class ExternalClusterReplayArmLedger(
    private val nowNanos: () -> Long = System::nanoTime,
    private val retryWindowNanos: Long = TimeUnit.SECONDS.toNanos(2L),
) {
    internal class Attempt internal constructor(
        val receipt: Any,
        val timestampNanos: Long,
        val deadlineNanos: Long,
    ) {
        var inFlight = false
    }

    internal sealed interface Begin {
        data class Ready(val attempt: Attempt) : Begin
        data object Busy : Begin
        data object AlreadyArmed : Begin
        data object Rejected : Begin
    }

    private var pending: Attempt? = null
    private var lastArmedReceipt: Any? = null
    private var committedTimestampNanos = 0L

    @Synchronized
    fun begin(receipt: Any, timestampNanos: Long): Begin {
        if (timestampNanos <= 0L) return Begin.Rejected
        if (timestampNanos <= committedTimestampNanos) return if (
            receipt === lastArmedReceipt && timestampNanos == committedTimestampNanos
        ) Begin.AlreadyArmed else Begin.Rejected
        val now = nowNanos()
        val previous = pending
        if (previous != null && previous.receipt !== receipt) {
            if (previous.inFlight) return Begin.Busy
            if (timestampNanos <= previous.timestampNanos) return Begin.Rejected
            pending = null // a strictly newer sealed replay supersedes the failed one
        }
        val attempt = pending ?: Attempt(
            receipt, timestampNanos, now + retryWindowNanos,
        ).also { pending = it }
        if (attempt.timestampNanos != timestampNanos || now >= attempt.deadlineNanos) {
            if (pending === attempt) pending = null
            return Begin.Rejected
        }
        if (attempt.inFlight) return Begin.Busy
        attempt.inFlight = true
        return Begin.Ready(attempt)
    }

    @Synchronized
    fun finish(attempt: Attempt, armed: Boolean): InstrumentReplayPermitDecision {
        if (pending !== attempt) return InstrumentReplayPermitDecision.REVOKED
        attempt.inFlight = false
        if (armed) {
            committedTimestampNanos = attempt.timestampNanos
            lastArmedReceipt = attempt.receipt
            pending = null
            return InstrumentReplayPermitDecision.ARMED
        }
        if (nowNanos() >= attempt.deadlineNanos) {
            pending = null
            return InstrumentReplayPermitDecision.REVOKED
        }
        return InstrumentReplayPermitDecision.BUSY_RETRY
    }

    @Synchronized
    fun commitOtherPresentedTimestamp(timestampNanos: Long) {
        if (timestampNanos > committedTimestampNanos) {
            committedTimestampNanos = timestampNanos
            if (pending?.timestampNanos?.let { it <= timestampNanos } == true) pending = null
        }
    }

    @Synchronized
    fun invalidate() {
        pending = null
        // Keep the producer timestamp floor, but never treat a pre-RESET replay receipt as
        // already armed in the next phone epoch.
        lastArmedReceipt = null
    }
}

/** Result of a post-RESET CLUSTER admission probe against one exact phone epoch gate. */
internal sealed interface ClusterRecoveryAdmission {
    data class Admitted(val epoch: Long) : ClusterRecoveryAdmission
    /** Above the floors, but the exact Host cover ACK for [epoch] has not landed yet. */
    data class AwaitingAck(val epoch: Long) : ClusterRecoveryAdmission
    data object Refused : ClusterRecoveryAdmission
}

/**
 * Shared epoch half of the instrument replay decision. A pre-ACK replay is temporary: the
 * owner's bounded BUSY retry re-offers the same sealed receipt after the ACK. Without the
 * controller's fresh type-3 witness it is final. Null means "admitted, continue checks".
 */
internal fun clusterRecoveryReplayEpochDecision(
    admission: ClusterRecoveryAdmission,
    recoveryWitness: () -> Boolean,
): InstrumentReplayPermitDecision? = when (admission) {
    is ClusterRecoveryAdmission.Admitted -> null
    is ClusterRecoveryAdmission.AwaitingAck ->
        if (recoveryWitness()) InstrumentReplayPermitDecision.BUSY_RETRY
        else InstrumentReplayPermitDecision.REVOKED
    ClusterRecoveryAdmission.Refused -> InstrumentReplayPermitDecision.REVOKED
}

/** Per-exact-target nonterminal phone epoch. It never mints a new producer or scene proof. */
internal class ExternalClusterPhoneEpochGate {
    val replayLedger = ExternalClusterReplayArmLedger()
    private var epoch = 0L
    private var acknowledgedEpoch = 0L
    private var minimumSceneRevision = 0L
    private var minimumProducerTimestamp = 0L
    private var lastArmedSceneRevision = 0L
    private var lastArmedProducerTimestamp = 0L
    /**
     * One fresh, post-floor presented scene that reached the Authority while this exact epoch
     * was still waiting for its Host cover ACK. It is only a nomination: the ACK owner re-runs
     * every scene/batch/witness predicate before it can mint a permit.
     */
    private var parkedPresentation: Any? = null
    private var parkedEpoch = 0L

    @Synchronized
    fun beginCover(): Long {
        minimumSceneRevision = maxOf(minimumSceneRevision, lastArmedSceneRevision)
        minimumProducerTimestamp = maxOf(
            minimumProducerTimestamp, lastArmedProducerTimestamp,
        )
        epoch = Math.addExact(epoch, 1L)
        parkedPresentation = null
        parkedEpoch = 0L
        return epoch
    }

    @Synchronized
    fun acknowledge(candidateEpoch: Long): Boolean {
        if (candidateEpoch != epoch) return false
        acknowledgedEpoch = candidateEpoch
        return true
    }

    /**
     * Separates "this exact epoch has not been ACKed yet" (temporary) from "superseded or not
     * above the post-RESET floors" (final). Only the former may be retried.
     */
    @Synchronized
    fun recoveryAdmission(sceneRevision: Long, producerTimestamp: Long): ClusterRecoveryAdmission {
        if (sceneRevision <= minimumSceneRevision ||
            producerTimestamp <= minimumProducerTimestamp
        ) return ClusterRecoveryAdmission.Refused
        return if (acknowledgedEpoch == epoch) ClusterRecoveryAdmission.Admitted(epoch)
        else ClusterRecoveryAdmission.AwaitingAck(epoch)
    }

    /**
     * Atomically parks [presentation] for the current un-ACKed epoch. Returns false when the
     * epoch was ACKed meanwhile (the caller re-checks admission) or the frame is below floors.
     */
    @Synchronized
    fun parkUntilAcknowledged(
        sceneRevision: Long,
        producerTimestamp: Long,
        presentation: Any,
    ): Boolean {
        if (recoveryAdmission(sceneRevision, producerTimestamp) !is
                ClusterRecoveryAdmission.AwaitingAck
        ) return false
        parkedPresentation = presentation
        parkedEpoch = epoch
        return true
    }

    /** After [acknowledge]: the nomination parked for exactly [acknowledgedEpochCandidate]. */
    @Synchronized
    fun takeParkedPresentation(acknowledgedEpochCandidate: Long): Any? {
        // A stale caller (older ticket) neither receives nor discards the current nomination.
        if (acknowledgedEpochCandidate != epoch || acknowledgedEpoch != epoch) return null
        val parked = parkedPresentation?.takeIf { parkedEpoch == epoch }
        parkedPresentation = null
        parkedEpoch = 0L
        return parked
    }

    @Synchronized
    fun hasParkedPresentationForTest(): Boolean = parkedPresentation != null

    @Synchronized
    fun isCurrent(candidateEpoch: Long): Boolean =
        candidateEpoch == epoch && acknowledgedEpoch == epoch

    @Synchronized
    fun isSuperseded(candidateEpoch: Long): Boolean = candidateEpoch != epoch

    @Synchronized
    fun admissionEpoch(sceneRevision: Long, producerTimestamp: Long): Long? =
        epoch.takeIf {
            acknowledgedEpoch == epoch && sceneRevision > minimumSceneRevision &&
                producerTimestamp > minimumProducerTimestamp
        }

    @Synchronized
    fun commitArmed(candidateEpoch: Long, sceneRevision: Long, producerTimestamp: Long): Boolean {
        if (!isCurrent(candidateEpoch) || sceneRevision <= minimumSceneRevision ||
            producerTimestamp <= minimumProducerTimestamp
        ) return false
        lastArmedSceneRevision = maxOf(lastArmedSceneRevision, sceneRevision)
        lastArmedProducerTimestamp = maxOf(lastArmedProducerTimestamp, producerTimestamp)
        return true
    }
}

/**
 * Fixed, per-provisioning bridge from an issuer-verified Profile to physical Android Surfaces.
 * Preflight is intentionally possible before the final manifest is issued. Only [seal] can make
 * a later renderer session bindable, and [registerRendererSession] records the independently
 * created exact child/coordinator pair. No preference, receipt, or caller-built context selects a
 * Surface. Product composition must construct this with the process-owned Host registry and the
 * fixed [AndroidMediaSinkPhysicalLegacyHandoffPort]; tests may inject a deterministic port.
 * This authority admits one control-session bind: a reconnect must construct fresh provisioning
 * until shared exposes proof that its old process-wide batch identity claim has been released.
 */
internal class AuthorizedAndroidUiSyncPhysicalOutputAuthority(
    private val verifiedProfile: VerifiedUltraProfile,
    private val requirement: UiSyncPhysicalOutputRequirement,
    private val staticPlan: UiSyncRendererStaticPlan,
    private val schemaFingerprint: UiSyncSchemaFingerprint,
    private val bindingFingerprint: UiSyncBindingFingerprint,
    private val surfaces: HostPhysicalDisplaySurfaceRegistry,
    private val legacyHandoff: PhysicalLegacySurfaceHandoffPort,
    /** Fixed coordinator-side token-to-receipt-lineage proof, never a user preference. */
    private val generationProof: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationProof?,
    routeTypes: Map<DisplayDestination, Int>,
    private val generationStatusSource: (UltraProfileSessionToken) ->
        UltraProfilePhysicalGenerationStatus = { token ->
            generationProof(token)?.let { UltraProfilePhysicalGenerationStatus.Current(it) }
                ?: UltraProfilePhysicalGenerationStatus.Revoked
        },
) : UiSyncPhysicalOutputAuthority {
    private class Seal(
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
        val token: UltraProfileSessionToken,
        val proof: UltraProfilePhysicalGenerationProof,
        val provisioning: AuthorizedUltraProfileProvisioning?,
    )

    private class Registration(
        val child: UiSyncCompleteRendererSessionLease,
        val coordinator: AndroidUltraRendererSessionCoordinator,
        val readinessReceipt: RendererReadinessReceipt?,
        val seal: Seal,
    ) {
        var bound = false
        var activationAttempted = false
        var activationInFlight = false
        var pendingSceneActivation = false
        var retryRequested = false
        var lastActivatedGraph: UltraDisplayGraphSnapshot? = null
        var sceneBindings: List<UltraThemeOutputSceneBinding>? = null
        var transferredExternalRoute: AndroidMediaSink.PhysicalExternalLegacyRouteLease? = null
        var transferredExternalTarget: Target? = null
        @Volatile
        var closed = false
    }

    private class Target(
        val route: UiSyncPhysicalOutputRoute,
        val hostVersion: HostDisplaySurfaceVersion,
        val destination: DisplayDestination,
        val type: Int,
        val surface: Surface,
    )

    /** One unchanged signed MAIN selection across producer-only redraw revisions. */
    private class MainReproofChain(
        val record: Registration,
        val child: StaticThemeRendererSession,
        val batch: UiSyncPhysicalOutputBatch,
        val target: Target,
        val graph: UltraDisplayGraphSnapshot,
        val activityGeneration: Any,
        val scene: UltraOutputScene,
        var latestRevision: Long,
    ) {
        val budget = MainSignedSceneReproofBudget()
    }

    private class MainReproofWork(
        val permit: AuthorizedMainPhysicalUiPermit,
        val chain: MainReproofChain,
        val deadlineNanos: Long,
    ) {
        var coverEpoch: Long? = null
        var retries = 0
        var retryDelayMillis = 20L
    }

    private class MainSemanticSuspension(
        val record: Registration,
        val child: StaticThemeRendererSession,
        val batch: UiSyncPhysicalOutputBatch,
        val target: Target,
        val displayId: UltraUiSyncDisplayId,
        val activityGeneration: Any,
        val previousSubmittedRevision: Long,
        val coverEpoch: Long,
    ) {
        var coverAcknowledged = false
    }

    private class Borrowed(
        val target: Target,
        val lease: HostPhysicalDisplaySurfaceRegistry.BorrowedTargetLease,
        private val surfaces: HostPhysicalDisplaySurfaceRegistry,
    ) : UiSyncPhysicalTargetLease {
        override val version: UiSyncPhysicalTargetVersion = target.route.target
        override val isCurrent: Boolean get() = !lease.isRevoked &&
            lease.target.surface === target.surface && lease.target.isValid &&
            surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)

        fun coverExactExternalUiForRetirement(): Boolean =
            lease.coverExactExternalUiForRetirement()

        fun coverExactMainUiForRetirement(): Boolean =
            lease.coverExactMainUiForRetirement()
    }

    /** Published under the Authority lock before a bind can enter native output use. */
    private class BindProgress(val targets: List<Target>) {
        val borrowed = ArrayList<Borrowed>()
        var nativeUseMayHaveStarted = false
    }

    /** One old owner and its frozen cover operation survive failed policy ACKs. */
    private class PolicyCoverTicket(
        val coverExactOldTargets: () -> Boolean,
        /** A preflight or pre-borrow bind has no Ultra pixels to hide. */
        requiresAcknowledgement: Boolean,
    ) {
        var acknowledged = !requiresAcknowledgement
        var inFlight = false
    }

    private class RetainedResources(
        private var eglRetirement: Closeable?,
        private val borrowed: List<Borrowed>,
        private val reservations: List<PhysicalLegacySurfaceReservation>,
        private val externalUses: List<Pair<PhysicalLegacySurfaceReservation, Any>>,
        private val releaseBeforeEgl: Boolean,
        private var cleanEglRetirementProven: Boolean,
    ) : Closeable {
        private val lock = Any()
        private val borrowedRetired = BooleanArray(borrowed.size)
        private val externalUseConfirmed = BooleanArray(externalUses.size)
        private val reservationReleased = BooleanArray(reservations.size)
        private var closed = false
        private var closeInFlight = false

        override fun close() {
            synchronized(lock) {
                if (closed) return
                check(!closeInFlight) { "Physical output retirement is already in flight" }
                closeInFlight = true
            }
            try {
                if (releaseBeforeEgl) {
                    check(externalUses.isEmpty() && eglRetirement == null) {
                        "Clean pre-EGL release is unavailable after external use"
                    }
                    borrowed.indices.reversed().forEach { index ->
                        if (!borrowedRetired[index]) {
                            borrowed[index].lease.retireAfterEglProof()
                            borrowedRetired[index] = true
                        }
                    }
                    reservations.indices.reversed().forEach { index ->
                        if (reservationReleased[index]) return@forEach
                        // A Host withdraw can race after borrow retirement. Never restore a
                        // decoder onto that potentially released Surface. The old preview stays
                        // unavailable until a fresh Host/Legacy bind; this is a safe blank output.
                        reservations[index].close()
                        reservationReleased[index] = true
                    }
                } else {
                    eglRetirement?.close() // EGL/router fence before any borrowed Surface is released.
                    if (eglRetirement != null) {
                        eglRetirement = null
                        cleanEglRetirementProven = true
                    }
                    check(externalUses.isEmpty() || cleanEglRetirementProven) {
                        "External Surface use has no EGL/router retirement proof"
                    }
                    borrowed.indices.reversed().forEach { index ->
                        if (!borrowedRetired[index]) {
                            borrowed[index].lease.retireAfterEglProof()
                            borrowedRetired[index] = true
                        }
                    }
                    externalUses.indices.forEach { index ->
                        if (!externalUseConfirmed[index]) {
                            val (reservation, token) = externalUses[index]
                            reservation.confirmExternalUseRetired(token)
                            externalUseConfirmed[index] = true
                        }
                    }
                    reservations.indices.forEach { index ->
                        if (!reservationReleased[index]) {
                            reservations[index].close()
                            reservationReleased[index] = true
                        }
                    }
                }
                synchronized(lock) { closed = true }
            } finally {
                synchronized(lock) { closeInFlight = false }
            }
        }

        val isClosed: Boolean get() = synchronized(lock) { closed }
        val reservationsHeld: Boolean get() = reservations.all { it.isHeld }
    }

    private class BackendLease(
        private val resources: RetainedResources,
        private val batch: AndroidUltraOutputBatchLease,
        private val borrowed: List<Borrowed>,
        private val registration: Registration,
        private val authorityGenerationStatus: () -> UiSyncPhysicalGenerationStatus,
        private val coverPhysicalBeforeRetirement: () -> Boolean,
        private val onClosed: (BackendLease) -> Unit,
    ) : UiSyncPhysicalOutputBackendLease {
        @Volatile
        private var closeRequested = false
        override val generation: Long = batch.generation
        override val generationStatus: UiSyncPhysicalGenerationStatus
            get() {
                // A dead renderer, target, batch or reservation is permanent even if the AA05
                // coordinator happens to be temporarily busy on a different operation.
                if (closeRequested || registration.closed ||
                    !registration.coordinator.isLive || !batch.isCurrent ||
                    !resources.reservationsHeld || !borrowed.all(Borrowed::isCurrent)
                ) return UiSyncPhysicalGenerationStatus.REVOKED
                return authorityGenerationStatus()
            }
        override val isCurrent: Boolean
            get() = generationStatus == UiSyncPhysicalGenerationStatus.CURRENT

        override fun close() {
            closeRequested = true
            check(coverPhysicalBeforeRetirement()) {
                "Every signed physical UI cover must ACK before output retirement"
            }
            resources.close()
            onClosed(this)
        }
    }

    private val lock = Any()
    private val externalCoverIssuerNonce = Any()
    private val mainCoverIssuerNonce = Any()
    private val mainCoverGeneration = MainNonterminalCoverGeneration()
    private var mainReproofChain: MainReproofChain? = null
    private var mainReproofWork: MainReproofWork? = null
    private var mainReproofScheduler: ScheduledThreadPoolExecutor? = null
    /** A type-2 mismatch revokes permits before a potentially blocking Host cover ACK. */
    private var mainSemanticSuspension: MainSemanticSuspension? = null
    private val routesByDestination: Map<DisplayDestination, Int> =
        Collections.unmodifiableMap(LinkedHashMap(routeTypes))
    private val registrations = IdentityHashMap<UiSyncCompleteRendererSessionLease, Registration>()
    private val usedRendererSessions = IdentityHashMap<UiSyncCompleteRendererSessionLease, Unit>()
    private val usedCoordinators = IdentityHashMap<AndroidUltraRendererSessionCoordinator, Unit>()
    private var preflightTargets: List<Target>? = null
    private var seal: Seal? = null
    private var binding = false
    private var bindProgress: BindProgress? = null
    /** Shared releases its identity claim after backend.close; a second bind cannot race it. */
    private var bindAttemptConsumed = false
    private var retiredAfterBind = false
    private var activeBackend: BackendLease? = null
    /** Exact shared-issued batch; equivalent route rows are never an activation capability. */
    private var activeIssuedBatch: UiSyncPhysicalOutputBatch? = null
    private var activeSessionContext: UiSyncPhysicalOutputSessionContext? = null
    private var activeBoundTargets: List<Target>? = null
    private var activeBorrowedTargets: List<Borrowed>? = null
    private var pending: RetainedResources? = null
    private var pendingCover: (() -> Boolean)? = null
    private var policyCoverTicket: PolicyCoverTicket? = null
    /** Test-only deterministic interleaving; never assigned by product composition. */
    @Volatile private var afterFailedBindCoverDecisionForTest: (() -> Unit)? = null
    private var revoked = false
    /** Identity-keyed: a new Host target can never inherit an old phone epoch or UI ACK. */
    private val clusterPhoneEpochs = IdentityHashMap<Target, ExternalClusterPhoneEpochGate>()

    private fun clusterPhoneEpochLocked(target: Target): ExternalClusterPhoneEpochGate =
        clusterPhoneEpochs[target] ?: ExternalClusterPhoneEpochGate().also {
            clusterPhoneEpochs[target] = it
        }

    internal fun authenticatesExternalCoverPermit(
        permit: AuthorizedExternalClusterUiPermit,
    ): Boolean = permit.owner === this && permit.issuerNonce === externalCoverIssuerNonce

    internal fun authenticatesMainCoverPermit(
        permit: AuthorizedMainPhysicalUiPermit,
    ): Boolean = permit.owner === this && permit.issuerNonce === mainCoverIssuerNonce

    init {
        require(requirement.verifiedProfileDigest == verifiedProfile.candidate.contentDigest &&
            requirement.assignments.size == staticPlan.outputs.size &&
            requirement.assignments.all { row ->
                staticPlan.outputs.any { it === row.output }
            }
        ) { "Physical output requirement differs from the verified Profile/static plan" }
        val destinations = requirement.assignments.map { destinationFor(it.destination.role, it.destination.instanceId) }
        require(destinations.toSet().size == destinations.size &&
            routesByDestination.keys == destinations.toSet() &&
            routesByDestination.values.toSet().size == routesByDestination.size
        ) { "Physical output route types do not exactly cover distinct signed destinations" }
    }

    /** Called only after the fixed issuer checked the complete Profile, policy and AA05 identity. */
    fun seal(
        manifest: AuthorizedUltraCompiledRuntimeManifest,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
        token: UltraProfileSessionToken,
        provisioning: AuthorizedUltraProfileProvisioning? = null,
    ) {
        val contract = requireNotNull(manifest.uiSync) { "Manifest has no UI Sync runtime" }
        require(manifest.issuedBy(issuer) &&
            manifest.profileContentDigest == verifiedProfile.candidate.contentDigest &&
            contract.physicalOutputRequirement === requirement &&
            contract.schemaFingerprint == schemaFingerprint &&
            contract.bindingFingerprint == bindingFingerprint &&
            contract.outputs.size == staticPlan.outputs.size &&
            contract.outputs.all { declared ->
                staticPlan.outputs.any { it === declared }
            } &&
            contract.ingressSpecs == staticPlan.ingressSpecs
        ) { "Physical output authority cannot seal a foreign compiled manifest" }
        manifest.displayAssignmentsFor(verifiedProfile)
        val proof = requireNotNull(generationProof(token)) { "AA05 generation is not live" }
        require(proof.matchesToken(token) && manifest.matchesGenerationIdentity(
            proof.runtimeIdentity, proof.authenticationIdentity,
        )) { "AA05 generation does not match the compiled manifest identity" }
        require(provisioning == null || (
            provisioning.parent.sessionToken === token &&
                provisioning.manifestDigest == manifest.manifestDigest
        )) { "Physical seal provisioning differs from the published AA05 child" }
        synchronized(lock) {
            check(!revoked && seal == null && registrations.isEmpty()) {
                "Physical output authority is already sealed or revoked"
            }
            seal = Seal(manifest, token, proof, provisioning)
        }
    }

    /** Product renderer factory records exactly the child it returned, not a fingerprint alias. */
    fun registerRendererSession(
        child: UiSyncCompleteRendererSessionLease,
        coordinator: AndroidUltraRendererSessionCoordinator,
        manifest: AuthorizedUltraCompiledRuntimeManifest,
        token: UltraProfileSessionToken,
    ): Closeable {
        val sealed = synchronized(lock) {
            check(!revoked && !binding && !bindAttemptConsumed && pending == null) {
                "Physical authority cannot register another control session"
            }
            checkNotNull(seal) { "Physical output authority is not manifest-sealed" }
        }
        val compiled = checkNotNull(sealed.manifest.uiSync)
        val receipt = child.readinessReceipt
        require(sealed.manifest === manifest && sealed.token === token &&
            sameLiveGeneration(sealed) &&
            child.isLive && coordinator.isLive &&
            child.schemaFingerprint == schemaFingerprint &&
            child.bindingFingerprint == bindingFingerprint &&
            coordinator.schemaFingerprint == schemaFingerprint &&
            coordinator.bindingFingerprint == bindingFingerprint &&
            child.displayIds == coordinator.displayIds &&
            child.displayIds == compiled.displayIds &&
            child is AuthorizedCoordinatorBackedRendererChild &&
            child.exactCoordinator === coordinator &&
            coordinator.implementationId == compiled.rendererImplementationId &&
            child.implementationId == compiled.rendererImplementationId &&
            receipt?.phase == RendererReadinessReceipt.Phase.SESSION_IMPLEMENTATION &&
            receipt.schemaFingerprint == schemaFingerprint &&
            receipt.bindingFingerprint == bindingFingerprint &&
            receipt.displayIds == compiled.displayIds &&
            receipt.semanticThemeProgramFingerprint == compiled.semanticThemeProgramFingerprint &&
            receipt.semanticCompilerActive && receipt.themeCompilerActive && receipt.backendLive
        ) { "Renderer child/coordinator do not belong to the sealed provisioning" }
        require(child.readinessReceipt === receipt) {
            "Renderer session readiness changed during registration"
        }
        val record = Registration(child, coordinator, receipt, sealed)
        synchronized(lock) {
            check(!revoked && seal === sealed && !usedRendererSessions.containsKey(child) &&
                !usedCoordinators.containsKey(coordinator)
            ) { "Renderer session or coordinator is already registered" }
            registrations[child] = record
            usedRendererSessions[child] = Unit
            usedCoordinators[coordinator] = Unit
        }
        return Closeable {
            synchronized(lock) {
                if (registrations[child] === record) {
                    // Shared UiSyncProvisionedSession closes the renderer child before its
                    // physical batch. Revoke liveness now, but retain this exact registration
                    // until the later batch fence (or a failed bind cleanup) has finished.
                    record.closed = true
                    retireClosedRegistrationLocked(record)
                }
            }
        }
    }

    /**
     * The shared provisioning constructor opens a disposable renderer probe before manifest
     * issuance. A factory may call this for every opened child: null leaves that probe diagnostic
     * and unregistered; after sealing, the actual AirPlay-session child is registered exactly.
     */
    fun registerRendererSessionIfSealed(
        child: UiSyncCompleteRendererSessionLease,
        coordinator: AndroidUltraRendererSessionCoordinator,
    ): Closeable? {
        val current = synchronized(lock) {
            check(!revoked) { "Physical output authority is revoked" }
            val candidate = seal
            if (candidate == null) {
                check(!usedRendererSessions.containsKey(child) &&
                    !usedCoordinators.containsKey(coordinator)) {
                    "Renderer construction probe child or coordinator was reused"
                }
                usedRendererSessions[child] = Unit // tombstone this exact disposable probe
                usedCoordinators[coordinator] = Unit
            }
            candidate
        } ?: return null
        return registerRendererSession(child, coordinator, current.manifest, current.token)
    }

    override fun preflight(context: UiSyncPhysicalOutputPreflightContext):
        UiSyncPhysicalOutputAvailability? {
        if (!matches(context.requirement, context.staticPlan, context.schemaFingerprint,
                context.bindingFingerprint)) return null
        val inspected = inspectTargets() ?: return null
        val baseline = synchronized(lock) {
            if (revoked || binding || bindAttemptConsumed || pending != null ||
                activeBackend != null) return null
            val previous = preflightTargets
            if (previous != null && !sameTargets(previous, inspected)) return null
            if (previous == null) preflightTargets = inspected
            checkNotNull(preflightTargets)
        }
        return context.issueAvailability(inspected.map(Target::route)) {
            val state = synchronized(lock) {
                (!revoked && !retiredAfterBind && preflightTargets === baseline) to seal
            }
            state.first && (state.second == null || sameLiveGeneration(checkNotNull(state.second))) &&
                inspected.all { target ->
                    surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)
                }
        }
    }

    override fun bindSession(context: UiSyncPhysicalOutputSessionContext): UiSyncPhysicalOutputBatch? {
        val registration: Registration
        val planned: List<Target>
        val progress: BindProgress
        val liveSeal = synchronized(lock) { seal }
        check(liveSeal != null && sameLiveGeneration(liveSeal)) {
            "Physical output AA05 generation is not live"
        }
        synchronized(lock) {
            check(!revoked && !binding && !bindAttemptConsumed && pending == null &&
                activeBackend == null) {
                "Physical output authority is busy or revoked"
            }
            check(matches(context.requirement, context.staticPlan,
                context.schemaFingerprint, context.bindingFingerprint)
            ) { "Physical output session differs from the signed provisioning" }
            registration = checkNotNull(registrations[context.rendererSession]) {
                "Renderer session has no exact provisioning registration"
            }
            check(registration.child === context.rendererSession &&
                registration.seal === seal && !registration.closed && !registration.bound
            ) { "Renderer session is foreign, reused, or stale" }
            planned = checkNotNull(preflightTargets) { "Physical preflight was not established" }
            registration.bound = true
            binding = true
            bindAttemptConsumed = true
            progress = BindProgress(planned).also { bindProgress = it }
        }

        val reservations = ArrayList<PhysicalLegacySurfaceReservation>(planned.size)
        val borrowed = ArrayList<Borrowed>(planned.size)
        val externalUses = ArrayList<Pair<PhysicalLegacySurfaceReservation, Any>>(planned.size)
        var eglRetirement: Closeable? = null
        var eglStarted = false
        var cleanEglFailure = false
        try {
            check(registration.child.isLive && registration.coordinator.isLive &&
                registration.child.readinessReceipt === registration.readinessReceipt &&
                sameLiveGeneration(registration.seal)
            ) { "Renderer or AA05 generation changed before physical handoff" }
            check(inspectTargets()?.let { sameTargets(planned, it) } == true) {
                "Physical target changed after preflight"
            }
            planned.forEach { target ->
                val covered = when (target.hostVersion.kind) {
                    HostDisplaySurfaceKind.PHYSICAL_MAIN ->
                        surfaces.coverExactMainPhysicalTarget(target.hostVersion, target.surface)
                    HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ->
                        surfaces.coverExternalForUltraHandoff(target.hostVersion, target.surface)
                    HostDisplaySurfaceKind.PREVIEW_ONLY -> false
                }
                check(covered) {
                    "Physical UI cover is not bound to the exact signed Host target"
                }
                check(synchronized(lock) { !revoked && bindProgress === progress }) {
                    "Physical authority was revoked during exact Host cover"
                }
            }
            planned.forEach { target ->
                when (val handoff = legacyHandoff.retireAndReserveExact(
                    target.type, target.surface, target.hostVersion,
                )) {
                    is PhysicalLegacyHandoffResult.Reserved -> {
                        check(handoff.reservation.isHeld) { "Surface reservation is not held" }
                        reservations += handoff.reservation
                    }
                    is PhysicalLegacyHandoffResult.Uncertain -> {
                        handoff.reservation?.let(reservations::add)
                        throw handoff.cause
                    }
                    PhysicalLegacyHandoffResult.Denied ->
                        error("A legacy or competing owner still holds the physical Surface")
                }
                check(synchronized(lock) { !revoked && bindProgress === progress }) {
                    "Physical authority was revoked during legacy handoff"
                }
            }
            check(inspectTargets()?.let { sameTargets(planned, it) } == true) {
                "Physical target changed during legacy retirement"
            }
            planned.forEach { target ->
                val lease = checkNotNull(surfaces.acquirePhysicalTarget(target.hostVersion)) {
                    "Physical target borrow changed during handoff"
                }
                val exact = Borrowed(target, lease, surfaces)
                borrowed += exact
                // A policy revoke racing acquire may have observed no borrow, but could not
                // admit native use. Publish every acquired lease before the next bind stage.
                check(synchronized(lock) {
                    if (bindProgress !== progress) false else {
                        progress.borrowed += exact
                        !revoked
                    }
                }) { "Physical authority was revoked during target borrow" }
                check(lease.target.surface === target.surface && !lease.isRevoked) {
                    "Borrowed Surface differs from the preflight version"
                }
            }
            check(planned.indices.all { index ->
                reservations[index].isHeld && borrowed[index].isCurrent
            } && registration.child.isLive &&
                sameLiveGeneration(registration.seal) &&
                registration.child.readinessReceipt === registration.readinessReceipt &&
                registration.coordinator.isLive
            ) { "Physical ownership changed before EGL commit" }
            val outputBatch = try {
                registration.coordinator.replaceOutputBatch(planned.indices.map { index ->
                    AndroidUltraOutputAttachmentRequest(
                        planned[index].destination,
                        planned[index].route.assignment.output,
                        borrowed[index].lease.target,
                    )
                }) {
                    // The bridge has prepared and validated every output. No native commit may
                    // begin until every exact legacy handoff has crossed this one-way gate.
                    check(synchronized(lock) {
                        !revoked && binding && seal === registration.seal &&
                            bindProgress === progress &&
                            progress.borrowed.size == planned.size &&
                            registrations[registration.child] === registration &&
                            !registration.closed && activeBackend == null && pending == null
                    } && registration.child === context.rendererSession &&
                        registration.child.isLive &&
                        registration.child.readinessReceipt === registration.readinessReceipt &&
                        registration.coordinator.isLive &&
                        sameLiveGeneration(registration.seal) &&
                        planned.indices.all { index ->
                            reservations[index].isHeld &&
                                borrowed[index].target === planned[index] &&
                                borrowed[index].isCurrent
                        }
                    ) { "AA05, renderer, or exact Host target changed during batch preparation" }
                    // Native/external use may begin after this point. A policy revoke takes
                    // the same lock and freezes these complete exact borrows before covering.
                    check(synchronized(lock) {
                        if (revoked || bindProgress !== progress ||
                            progress.borrowed.size != planned.size
                        ) false else {
                            progress.nativeUseMayHaveStarted = true
                            true
                        }
                    }) { "Physical authority was revoked before native use" }
                    reservations.forEach { reservation ->
                        externalUses += reservation to reservation.beginExternalUse()
                    }
                    check(synchronized(lock) {
                        !revoked && !registration.closed && binding &&
                            seal === registration.seal &&
                            registrations[registration.child] === registration
                    } && registration.child === context.rendererSession &&
                        registration.child.isLive &&
                        registration.child.readinessReceipt === registration.readinessReceipt &&
                        registration.coordinator.isLive &&
                        sameLiveGeneration(registration.seal) &&
                        planned.indices.all { index ->
                            reservations[index].isHeld && borrowed[index].isCurrent
                        }
                    ) { "Physical ownership changed before native batch commit" }
                    eglStarted = true
                }
            } catch (uncertain: AndroidUltraOutputBatchCommitFailure) {
                eglRetirement = uncertain
                throw uncertain
            } catch (clean: Throwable) {
                // The coordinator returns an ordinary failure only after its batch bridge proves
                // the attempted publication left no native target attached.
                cleanEglFailure = true
                throw clean
            }
            eglRetirement = outputBatch
            val resources = RetainedResources(outputBatch, borrowed, reservations,
                externalUses, releaseBeforeEgl = false, cleanEglRetirementProven = false)
            val backend = BackendLease(resources, outputBatch, borrowed, registration,
                {
                    if (synchronized(lock) { revoked }) UiSyncPhysicalGenerationStatus.REVOKED
                    else sameGenerationStatus(registration.seal)
                },
                { coverBorrowedPhysicalTargets(planned, borrowed) },
            ) { closed ->
                synchronized(lock) {
                    if (activeBackend === closed) {
                        clusterPhoneEpochs.values.forEach { it.replayLedger.invalidate() }
                        clusterPhoneEpochs.clear()
                        mainReproofWork = null
                        mainReproofChain = null
                        mainReproofScheduler?.shutdown()
                        mainReproofScheduler = null
                        activeBackend = null
                        activeIssuedBatch = null
                        activeSessionContext = null
                        activeBoundTargets = null
                        activeBorrowedTargets = null
                        registration.transferredExternalRoute = null
                        registration.transferredExternalTarget = null
                        retiredAfterBind = true
                        retireClosedRegistrationLocked(registration)
                    }
                }
            }
            val issued = context.issueActiveBatch(
                backend, planned.map(Target::route), borrowed,
            )
            // The shared batch now holds a process-wide identity claim. If publication fails,
            // only its close can release that claim after the backend fence.
            eglRetirement = issued
            check(issued.isCurrent) { "Physical batch became stale during activation" }
            check(registration.child.isLive &&
                registration.child.readinessReceipt === registration.readinessReceipt &&
                sameLiveGeneration(registration.seal)
            ) { "Renderer or AA05 generation changed after physical publication" }
            synchronized(lock) {
                check(!revoked && registrations[registration.child] === registration &&
                    !registration.closed && activeBackend == null && pending == null
                ) { "Physical authority changed while publishing its batch" }
                activeBackend = backend
                activeIssuedBatch = issued
                activeSessionContext = context
                activeBoundTargets = planned
                activeBorrowedTargets = borrowed
                val externalIndex = planned.indexOfFirst { it.type == 111 }
                if (externalIndex >= 0) {
                    registration.transferredExternalRoute =
                        reservations[externalIndex].transferredExternalRoute
                    registration.transferredExternalTarget = planned[externalIndex]
                }
                binding = false
                bindProgress = null
            }
            return issued
        } catch (failure: Throwable) {
            val nativeUseMayHaveStarted = progress.nativeUseMayHaveStarted
            val covered = coverPhysicalTargetsAfterFailedBind(
                planned, borrowed, nativeUseMayHaveStarted,
            )
            // Tests can insert policy phase one at the old cover-decision/cleanup boundary.
            // A probe failure must never skip retirement accounting.
            val probeFailure = runCatching {
                afterFailedBindCoverDecisionForTest?.invoke()
            }.exceptionOrNull()
            val cleanup = if (eglRetirement is UiSyncPhysicalOutputBatch) {
                // The issued shared batch owns the backend and identity claim. Its close performs
                // all inner retirement stages and releases the claim; do not confirm twice.
                RetainedResources(eglRetirement, emptyList(), emptyList(), emptyList(),
                    releaseBeforeEgl = false, cleanEglRetirementProven = false)
            } else RetainedResources(
                eglRetirement, borrowed, reservations, externalUses,
                releaseBeforeEgl = eglRetirement == null &&
                    !eglStarted && externalUses.isEmpty(),
                cleanEglRetirementProven = !eglStarted || cleanEglFailure,
            )
            // Release depends on an irreversible ACK from every *borrowed* old gate, not on
            // whether a policy ticket happened to exist at an earlier snapshot. A concurrent
            // phase-one revoke may capture these borrows immediately after this cover attempt.
            val cleanupFailure = if (!covered) {
                IllegalStateException("Exact physical UI cover did not ACK failed-bind retirement")
            } else runCatching { cleanup.close() }.exceptionOrNull()
            synchronized(lock) {
                binding = false
                if (bindProgress === progress) bindProgress = null
                retiredAfterBind = true
                if (cleanupFailure != null) {
                    pending = cleanup
                    pendingCover = if (!covered) {
                        // The frozen lease caches its ACK, so a policy ticket captured before
                        // or after this closure can still be retried without a successor.
                        { coverPhysicalTargetsAfterFailedBind(
                            planned, borrowed, nativeUseMayHaveStarted,
                        ) }
                    } else null
                }
                retireClosedRegistrationLocked(registration)
            }
            cleanupFailure?.let(failure::addSuppressed)
            probeFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Retry a failed EGL fence or reservation release on a process owner, never an Activity. */
    fun retryPendingRetirement(): Boolean {
        val (captured, cover) = synchronized(lock) {
            val current = pending ?: return true
            current to pendingCover
        }
        check(cover?.invoke() != false) {
            "Exact physical UI cover has not ACKed pending retirement"
        }
        captured.close()
        synchronized(lock) {
            if (pending === captured) {
                pending = null
                pendingCover = null
            }
            registrations.values.toList().forEach(::retireClosedRegistrationLocked)
        }
        return true
    }

    fun hasPendingRetirement(): Boolean = synchronized(lock) { pending != null }

    /**
     * Decoder-acquirer capability for the registered type-111 child only. The ordinary sink
     * acquirer is never a fallback when a physical external route owns that video type.
     */
    internal fun currentTransferredExternalRouteLease(
        coordinator: AndroidUltraRendererSessionCoordinator,
    ):
        AndroidMediaSink.PhysicalExternalLegacyRouteLease? {
        val current = synchronized(lock) {
            if (revoked || binding) return null
            val batch = activeIssuedBatch ?: return null
            val backend = activeBackend ?: return null
            val target = activeBoundTargets?.singleOrNull { it.type == 111 } ?: return null
            val record = registrations.values.singleOrNull { registration ->
                registration.bound && !registration.closed &&
                    registration.coordinator === coordinator &&
                    registration.transferredExternalTarget === target
            } ?: return null
            val route = record.transferredExternalRoute ?: return null
            Triple(batch, backend, Triple(record, target, route))
        }
        val (batch, backend, tied) = current
        val (record, target, route) = tied
        if (!batch.isCurrent || !backend.isCurrent ||
            !record.child.isLive || !record.coordinator.isLive ||
            !sameLiveGeneration(record.seal) || !route.isCurrent ||
            !surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)
        ) return null
        return synchronized(lock) {
            route.takeIf {
                !revoked && !binding && activeIssuedBatch === batch &&
                    activeBackend === backend &&
                    activeBoundTargets?.any { it === target } == true &&
                    registrations[record.child] === record && !record.closed &&
                    record.coordinator === coordinator &&
                    record.transferredExternalRoute === route
            }
        }
    }

    /** Only this renderer child and the exact shared-issued live batch may activate a scene. */
    internal fun ownsBoundBatch(
        child: UiSyncCompleteRendererSessionLease,
        batch: UiSyncPhysicalOutputBatch,
    ): Boolean = boundBatchStatus(child, batch) == UiSyncPhysicalGenerationStatus.CURRENT

    /**
     * Tri-state form of [ownsBoundBatch]. BUSY is only the AA05 coordinator's or backend's
     * transient reservation (for example a concurrent read-only provisioning liveness probe
     * from the local Canvas producer or layout-intent worker); it is never a usable CURRENT
     * proof. Every structural loss, dead renderer, or revoked generation is REVOKED.
     */
    private fun boundBatchStatus(
        child: UiSyncCompleteRendererSessionLease,
        batch: UiSyncPhysicalOutputBatch,
    ): UiSyncPhysicalGenerationStatus {
        val current = synchronized(lock) {
            val record = registrations[child]
            if (revoked || binding || activeIssuedBatch !== batch ||
                record == null || record.child !== child || record.closed || !record.bound ||
                activeBackend == null || record.seal !== seal
            ) return UiSyncPhysicalGenerationStatus.REVOKED
            record
        }
        val batchStatus = batch.generationStatus
        val generationStatus = sameGenerationStatus(current.seal)
        if (batchStatus == UiSyncPhysicalGenerationStatus.REVOKED ||
            generationStatus == UiSyncPhysicalGenerationStatus.REVOKED ||
            !child.isLive || !current.coordinator.isLive ||
            child.readinessReceipt !== current.readinessReceipt
        ) return UiSyncPhysicalGenerationStatus.REVOKED
        val exact = synchronized(lock) {
            !revoked && !binding && activeIssuedBatch === batch &&
                registrations[child] === current && !current.closed &&
                activeBackend != null && seal === current.seal
        }
        return when {
            !exact -> UiSyncPhysicalGenerationStatus.REVOKED
            batchStatus == UiSyncPhysicalGenerationStatus.BUSY ||
                generationStatus == UiSyncPhysicalGenerationStatus.BUSY ->
                UiSyncPhysicalGenerationStatus.BUSY
            else -> UiSyncPhysicalGenerationStatus.CURRENT
        }
    }

    /**
     * Activation-only wait for a terminal ownership answer. A transient BUSY reservation must
     * not revoke a first activation, but it also never authorizes one: this returns true only
     * for a later CURRENT observation. REVOKED, interruption, or [deadlineNanos] fail closed.
     */
    private fun awaitBoundBatchOwnership(
        child: UiSyncCompleteRendererSessionLease,
        batch: UiSyncPhysicalOutputBatch,
        deadlineNanos: Long,
    ): Boolean {
        while (true) {
            when (boundBatchStatus(child, batch)) {
                UiSyncPhysicalGenerationStatus.CURRENT -> return true
                UiSyncPhysicalGenerationStatus.REVOKED -> return false
                UiSyncPhysicalGenerationStatus.BUSY -> Unit
            }
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
                return false
            }
            LockSupport.parkNanos(ACTIVATION_BUSY_POLL_NANOS)
        }
    }

    /** The local instrument source may use only this sealed AA05 child's issued physical batch. */
    internal fun authorizesInstrumentSource(
        batch: UiSyncPhysicalOutputBatch,
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
    ): Boolean {
        val current = synchronized(lock) {
            val currentSeal = seal
            if (revoked || binding || activeIssuedBatch !== batch || activeBackend == null ||
                currentSeal == null || currentSeal.provisioning !== provisioning ||
                provisioning.parent.sessionToken !== currentSeal.token ||
                provisioning.manifestDigest != currentSeal.manifest.manifestDigest
            ) return false
            val registration = registrations.values.singleOrNull { record ->
                record.seal === currentSeal && record.bound && !record.closed
            } ?: return false
            currentSeal to registration
        }
        val (currentSeal, registration) = current
        val signedInstrument = currentSeal.manifest.instrumentCrosswalk
        if (!batch.isCurrent || !sameLiveGeneration(currentSeal) ||
            !registration.child.isLive || !registration.coordinator.isLive ||
            registration.child.readinessReceipt !== registration.readinessReceipt ||
            !runCatching {
                signedInstrument?.matchesProfile(profile) == true &&
                    signedInstrument.matchesCaf(currentSeal.manifest.caf)
            }.getOrDefault(false)
        ) return false
        return synchronized(lock) {
            !revoked && !binding && activeIssuedBatch === batch &&
                activeBackend != null && seal === currentSeal &&
                registrations[registration.child] === registration && !registration.closed
        }
    }

    /** Join the exact renderer/batch with the AA05 provisioning and Profile used for a scene. */
    internal fun authorizesSceneActivation(
        child: UiSyncCompleteRendererSessionLease,
        batch: UiSyncPhysicalOutputBatch,
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
    ): Boolean {
        if (!ownsBoundBatch(child, batch) || !provisioning.isLive) return false
        val current = synchronized(lock) {
            registrations[child]?.seal?.takeIf {
                !revoked && activeIssuedBatch === batch &&
                    it.provisioning === provisioning &&
                    provisioning.parent.sessionToken === it.token &&
                    provisioning.manifestDigest == it.manifest.manifestDigest
            }
        } ?: return false
        return runCatching { current.manifest.displayAssignmentsFor(profile).isNotEmpty() }
            .getOrDefault(false) && ownsBoundBatch(child, batch)
    }

    /** The only post-bind entry: the shared child already owns this exact issued batch. */
    override fun activateBoundSession(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
    ): UiSyncPhysicalActivationResult {
        check(matches(context.requirement, context.staticPlan,
            context.schemaFingerprint, context.bindingFingerprint)
        ) { "Theme activation differs from the signed physical provisioning" }
        val child = context.rendererSession
        val activation = synchronized(lock) {
            val record = checkNotNull(registrations[child]) {
                "Theme activation has no exact renderer registration"
            }
            check(!revoked && !binding && !record.closed && record.bound &&
                !record.activationAttempted && record.child === child &&
                activeIssuedBatch === batch && activeSessionContext === context &&
                activeBackend != null &&
                seal === record.seal
            ) { "Theme activation has no exact current bound renderer and batch" }
            val targets = checkNotNull(activeBoundTargets) {
                "Theme activation has no retained Host target dimensions"
            }
            val provisioning = checkNotNull(record.seal.provisioning) {
                "The physical seal did not retain its published AA05 child"
            }
            record.activationAttempted = true
            record.activationInFlight = true
            Triple(record, provisioning, targets)
        }
        val (record, provisioning, targets) = activation
        return activateRegisteredScene(child, batch, record, provisioning, targets, first = true)
    }

    /**
     * Called only by this registered child after its exact screen candidate publishes a newer
     * graph. A concurrent first activation records a retry request; it never reenters itself.
     * Null means there was no pending or changed graph, not a renderer readiness receipt.
     */
    internal fun retryPendingSceneActivation(
        child: StaticThemeRendererSession,
    ): UiSyncPhysicalActivationResult? {
        val registered = synchronized(lock) {
            checkNotNull(registrations[child]) {
                "Published screen belongs to no registered static renderer"
            }.also { record ->
                check(record.child === child && record.coordinator === child.coordinator) {
                    "Published screen has a foreign renderer coordinator"
                }
            }
        }
        val graph = try {
            registered.coordinator.graphSnapshot()
        } catch (failure: Throwable) {
            revoke()
            throw failure
        }
        val activation = synchronized(lock) {
            check(registrations[child] === registered && !registered.closed &&
                registered.bound && registered.activationAttempted && !revoked && !binding &&
                seal === registered.seal && activeBackend != null
            ) { "Published screen has no current authorized physical session" }
            if (registered.activationInFlight) {
                registered.retryRequested = true
                return null
            }
            if (!registered.pendingSceneActivation &&
                registered.lastActivatedGraph === graph) return null
            val batch = checkNotNull(activeIssuedBatch)
            val targets = checkNotNull(activeBoundTargets)
            val provisioning = checkNotNull(registered.seal.provisioning)
            registered.activationInFlight = true
            RetrySceneActivation(registered, provisioning, batch, targets)
        }
        return activateRegisteredScene(child, activation.batch, activation.record,
            activation.provisioning, activation.targets, first = false)
    }

    private class RetrySceneActivation(
        val record: Registration,
        val provisioning: AuthorizedUltraProfileProvisioning,
        val batch: UiSyncPhysicalOutputBatch,
        val targets: List<Target>,
    )

    private fun activateRegisteredScene(
        child: UiSyncCompleteRendererSessionLease,
        batch: UiSyncPhysicalOutputBatch,
        record: Registration,
        provisioning: AuthorizedUltraProfileProvisioning,
        targets: List<Target>,
        first: Boolean,
    ): UiSyncPhysicalActivationResult {
        // One bounded window covers every transient AA05/backend BUSY wait in this activation.
        val retryDeadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(MAXIMUM_GRAPH_ACTIVATION_SECONDS)
        try {
            check(awaitBoundBatchOwnership(child, batch, retryDeadline)) {
                "Theme activation lost its exact AA05 renderer or physical batch"
            }
            check(authorizesSceneActivation(child, batch, provisioning, verifiedProfile) &&
                targets.size == batch.routes.size && targets.all { target ->
                    target.route in batch.routes &&
                        surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)
                }
            ) { "Theme activation lost its AA05 or exact Host physical batch" }
            val staticChild = child as? StaticThemeRendererSession
                ?: error("Only the registered static-theme renderer can activate a physical scene")
            val bindings = synchronized(lock) { record.sceneBindings } ?:
                buildAuthorizedSignedSceneBindings(
                    verifiedProfile,
                    record.seal.manifest.displayAssignmentsFor(verifiedProfile),
                    targets.map { it.destination to it.hostVersion.sizePixels },
                ).also { created -> synchronized(lock) {
                    check(!revoked && registrations[child] === record &&
                        activeIssuedBatch === batch && record.sceneBindings == null
                    ) { "Signed scene binding changed during activation" }
                    record.sceneBindings = created
                } }
            repeat(MAXIMUM_GRAPH_ACTIVATION_PASSES) { pass ->
                check(System.nanoTime() < retryDeadline) {
                    "Theme graph did not stabilize before the activation deadline"
                }
                check(awaitBoundBatchOwnership(child, batch, retryDeadline)) {
                    "Theme activation lost its exact AA05 physical batch"
                }
                val graph = record.coordinator.graphSnapshot()
                check(record.seal.manifest.uiSync?.outputs?.toSet() == graph.outputs.toSet()) {
                    "Theme activation graph differs from the signed manifest"
                }
                val result = try {
                    staticChild.activateAfterPhysicalBind(
                        provisioning, verifiedProfile, graph, bindings, batch,
                    )
                } catch (stale: StaleThemeGraphBeforeSubmissionException) {
                    // The controller's typed check runs before it stores this activation or
                    // touches the EGL queue. A concurrently published InputStream may replace
                    // G0 with G1 here; retry only while the *same* AA05/batch remains current.
                    check(ownsBoundBatch(child, batch) &&
                        authorizesSceneActivation(child, batch, provisioning, verifiedProfile) &&
                        record.coordinator.graphSnapshot() !== graph
                    ) { "Theme graph staleness was not a safe pre-submit retry" }
                    return@repeat
                }
                // The submitted scene is still covered; a concurrent liveness probe may hold the
                // AA05 reservation now. Only a later CURRENT answer may record this activation.
                check(awaitBoundBatchOwnership(child, batch, retryDeadline)) {
                    "Theme activation lost its exact AA05 physical batch during submission"
                }
                check(!first || pass != 0 ||
                    result != UltraThemeLayoutActivationResult.ALREADY_ACTIVE
                ) { "First post-bind activation unexpectedly reused a submitted theme" }
                val latestGraph = record.coordinator.graphSnapshot()
                val retry = synchronized(lock) {
                    check(!revoked && registrations[child] === record &&
                        activeIssuedBatch === batch && !record.closed
                    ) { "Theme activation was revoked while recording its graph" }
                    record.lastActivatedGraph = graph
                    record.pendingSceneActivation =
                        result == UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM ||
                            result == UltraThemeLayoutActivationResult.PENDING_LOCAL_CANVAS ||
                            result == UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE
                    val changed = record.retryRequested || latestGraph !== graph
                    record.retryRequested = false
                    if (!changed) record.activationInFlight = false
                    changed
                }
                if (!retry) return when (result) {
                    UltraThemeLayoutActivationResult.PENDING_INPUT_STREAM ->
                        UiSyncPhysicalActivationResult.PENDING_INPUT_STREAM
                    UltraThemeLayoutActivationResult.PENDING_LOCAL_CANVAS ->
                        UiSyncPhysicalActivationResult.PENDING_LOCAL_CANVAS
                    UltraThemeLayoutActivationResult.PENDING_SIGNED_PALETTE ->
                        // Shared has no semantic-palette pending value. This is a
                        // conservative non-submitted retry state, never readiness.
                        UiSyncPhysicalActivationResult.PENDING_INPUT_STREAM
                    UltraThemeLayoutActivationResult.SUBMITTED,
                    UltraThemeLayoutActivationResult.ALREADY_ACTIVE ->
                        UiSyncPhysicalActivationResult.SUBMITTED
                }
            }
            error("Theme graph changed through too many bounded activation retries")
        } catch (failure: Throwable) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_CTRL_ACTIVATE,
                "phase" to "AUTHORITY_REVOKED", "error" to failure)
            // Do not close the transferred batch here. Shared owns child-before-batch retirement.
            synchronized(lock) {
                revoked = true
                record.pendingSceneActivation = false
                record.retryRequested = false
                record.activationInFlight = false
            }
            throw failure
        }
    }

    /**
     * A phone RESET is nonterminal for one exact CLUSTER. Phase 1 touches only Authority state:
     * old permits and replay attempts lose their epoch before this method returns. The frozen
     * ticket performs the potentially blocking Host cover later, without holding this lock.
     */
    override fun beginClusterPhoneEpochCover(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
        displayId: String,
    ): UiSyncClusterPhoneEpochCoverTicket? {
        val frozen = synchronized(lock) {
            val record = registrations[context.rendererSession] ?: return null
            val backend = activeBackend ?: return null
            val targets = activeBoundTargets ?: return null
            val borrowed = activeBorrowedTargets ?: return null
            if (revoked || binding || displayId.isBlank() ||
                activeIssuedBatch !== batch || activeSessionContext !== context ||
                context.staticPlan !== staticPlan ||
                record.child !== context.rendererSession || !record.bound || record.closed ||
                record.seal !== seal || backend.generation != batch.generation ||
                targets.size != batch.routes.size || borrowed.size != targets.size ||
                targets.indices.any { index ->
                    targets[index].route !== batch.routes[index] ||
                        borrowed[index].target !== targets[index]
                }
            ) return null
            val index = targets.indices.singleOrNull { index ->
                val target = targets[index]
                target.type == 111 &&
                    target.destination.role == DisplayDestinationRole.CLUSTER &&
                    target.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                    target.route.assignment.output.uiSyncDisplayId?.value == displayId
            } ?: return null
            val target = targets[index]
            val gate = clusterPhoneEpochLocked(target)
            val epoch = gate.beginCover()
            gate.replayLedger.invalidate()
            ClusterPhoneEpochSuspension(
                context, batch, record, target, borrowed[index], gate, epoch,
            )
        }
        return object : UiSyncClusterPhoneEpochCoverTicket {
            override fun coverExactClusterUi(): UiSyncClusterPhoneEpochCoverResult {
                val first = synchronized(lock) { clusterPhoneEpochStatusLocked(frozen) }
                if (first != UiSyncClusterPhoneEpochCoverResult.RETRY) return first
                val target = frozen.target
                if (!surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface) ||
                    !frozen.borrowed.isCurrent
                ) return UiSyncClusterPhoneEpochCoverResult.REVOKED
                val acknowledged = runCatching {
                    surfaces.coverExactBorrowedExternalPhysicalTarget(
                        target.hostVersion, target.surface,
                    )
                }.getOrDefault(false)
                val after = synchronized(lock) {
                    val status = clusterPhoneEpochStatusLocked(frozen)
                    if (status != UiSyncClusterPhoneEpochCoverResult.RETRY) status
                    else if (acknowledged && frozen.gate.acknowledge(frozen.epoch)) {
                        UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED
                    } else UiSyncClusterPhoneEpochCoverResult.RETRY
                }
                if (after == UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED) {
                    // Off-lock: a fresh scene presented before this ACK is re-evaluated by the
                    // full predicate; a stale or superseded nomination is simply dropped.
                    (frozen.gate.takeParkedPresentation(frozen.epoch) as? ParkedClusterScene)
                        ?.let { parked ->
                            runCatching { onClusterScenePresented(parked.child, parked.proof) }
                        }
                }
                if (after != UiSyncClusterPhoneEpochCoverResult.RETRY) return after
                return if (surfaces.isSameLivePhysicalTarget(
                        target.hostVersion, target.surface,
                    ) && frozen.borrowed.isCurrent
                ) UiSyncClusterPhoneEpochCoverResult.RETRY
                else UiSyncClusterPhoneEpochCoverResult.REVOKED
            }
        }
    }

    private class ParkedClusterScene(
        val child: StaticThemeRendererSession,
        val proof: UltraThemeClusterPresentedFrame,
    )

    private class ClusterPhoneEpochSuspension(
        val context: UiSyncPhysicalOutputSessionContext,
        val batch: UiSyncPhysicalOutputBatch,
        val record: Registration,
        val target: Target,
        val borrowed: Borrowed,
        val gate: ExternalClusterPhoneEpochGate,
        val epoch: Long,
    )

    /** The returned RETRY means this same frozen epoch remains live, not an inferred Host ACK. */
    private fun clusterPhoneEpochStatusLocked(
        frozen: ClusterPhoneEpochSuspension,
    ): UiSyncClusterPhoneEpochCoverResult {
        if (frozen.gate.isSuperseded(frozen.epoch))
            return UiSyncClusterPhoneEpochCoverResult.SUPERSEDED
        val targets = activeBoundTargets
        val borrowed = activeBorrowedTargets
        if (revoked || binding || activeSessionContext !== frozen.context ||
            activeIssuedBatch !== frozen.batch ||
            registrations[frozen.record.child] !== frozen.record ||
            frozen.record.closed || !frozen.record.bound || frozen.record.seal !== seal ||
            activeBackend?.generation != frozen.batch.generation ||
            targets?.any { it === frozen.target } != true ||
            borrowed?.any { it === frozen.borrowed && it.target === frozen.target } != true
        ) return UiSyncClusterPhoneEpochCoverResult.REVOKED
        return if (frozen.gate.isCurrent(frozen.epoch))
            UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED
        else UiSyncClusterPhoneEpochCoverResult.RETRY
    }

    /** Revoke exact-batch MAIN/CLUSTER reveal permits before a terminal Host cover can wait. */
    override fun revokeBoundSessionForStop(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
    ): Boolean = synchronized(lock) {
        val record = registrations[context.rendererSession] ?: return@synchronized false
        val backend = activeBackend ?: return@synchronized false
        val bound = activeBoundTargets ?: return@synchronized false
        val borrowed = activeBorrowedTargets ?: return@synchronized false
        if (binding || activeIssuedBatch !== batch || activeSessionContext !== context ||
            record.child !== context.rendererSession || !record.bound || record.closed ||
            record.seal !== seal || backend.generation != batch.generation ||
            bound.size != batch.routes.size || borrowed.size != bound.size ||
            bound.indices.any { index ->
                bound[index].route !== batch.routes[index] ||
                    borrowed[index].target !== bound[index]
            }
        ) return@synchronized false
        // Pure lock-only phase: every old external and MAIN permit observes revoked before the
        // RCS callback returns. Host View covering remains a separate exact-batch operation.
        beginPolicyEpochRevocationLocked()
        true
    }

    /**
     * The acknowledged close request covers every signed physical target, including MAIN,
     * before renderer quiescence. This is not optical or vehicle hardware blanking proof.
     */
    override fun coverBoundSessionForClose(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
    ): Boolean {
        val (targets, borrowed) = synchronized(lock) {
            val record = registrations[context.rendererSession] ?: return false
            val backend = activeBackend ?: return false
            val bound = activeBoundTargets ?: return false
            val exactBorrowed = activeBorrowedTargets ?: return false
            if (binding || activeIssuedBatch !== batch || activeSessionContext !== context ||
                record.child !== context.rendererSession || !record.bound ||
                record.seal !== seal ||
                backend.generation != batch.generation || bound.size != batch.routes.size ||
                bound.indices.any { bound[it].route !== batch.routes[it] } ||
                exactBorrowed.size != bound.size ||
                bound.indices.any { exactBorrowed[it].target !== bound[it] }
            ) return false
            // Invalidate queued and future reveal permits before crossing into the Host UI.
            // Keep the exact batch registration for repeated cover/retirement attempts.
            revoked = true
            clusterPhoneEpochs.values.forEach { it.replayLedger.invalidate() }
            record.pendingSceneActivation = false
            record.retryRequested = false
            bound to exactBorrowed
        }
        // Each frozen borrow owns the old UI gate and its monotonic retiring-cover ACK. A Host
        // version mutation cannot select a successor; MAIN cannot succeed vacuously.
        return coverBorrowedPhysicalTargets(targets, borrowed)
    }

    override fun coverMainForExactScreenTeardown(
        context: UiSyncPhysicalOutputSessionContext,
        batch: UiSyncPhysicalOutputBatch,
        stream: AirPlayVideoStreamHandle,
    ): Boolean {
        val current = synchronized(lock) {
            val record = registrations[context.rendererSession] ?: return false
            val bound = activeBoundTargets ?: return false
            val borrowed = activeBorrowedTargets ?: return false
            if (revoked || binding || stream.type != 110 ||
                context.staticPlan !== staticPlan ||
                activeIssuedBatch !== batch || activeSessionContext !== context ||
                record.child !== context.rendererSession || !record.bound || record.closed ||
                record.seal !== seal || activeBackend?.generation != batch.generation ||
                bound.size != batch.routes.size || borrowed.size != bound.size ||
                bound.indices.any { bound[it].route !== batch.routes[it] ||
                    borrowed[it].target !== bound[it] }
            ) return false
            val exactMain = bound.singleOrNull {
                it.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN && it.type == 110
            } ?: return false
            record to exactMain
        }
        // Coordinator snapshot may consult EGL; never hold the Authority lock while doing it.
        val submitted = runCatching {
            current.first.coordinator.stateSnapshot().lastSubmittedRenderRevision
        }.getOrNull() ?: return false
        val target = synchronized(lock) {
            val (record, exactMain) = current
            if (revoked || binding || activeIssuedBatch !== batch ||
                activeSessionContext !== context || registrations[context.rendererSession] !== record ||
                record.closed || !record.bound || record.seal !== seal ||
                activeBackend?.generation != batch.generation ||
                activeBoundTargets?.any { it === exactMain } != true
            ) return false
            // This revision was already submitted before teardown; an asynchronous completion
            // of it cannot rearm MAIN after the nonterminal cover.
            mainCoverGeneration.coverAfterSubmittedScene(submitted)
            exactMain
        }
        // The nonretiring Host operation keeps the batch and CLUSTER route intact. A failed ACK
        // leaves the caller's active type-110 stream in place for an exact retry.
        return surfaces.coverExactMainPhysicalTarget(target.hostVersion, target.surface)
    }

    /**
     * Phase 1 of a standalone phone palette mismatch. This runs at the controller's semantic
     * linearization point: old permits become REVOKED before its lock is released. No Host/View
     * operation or EGL state probe is performed while that controller lock is held.
     */
    internal fun beginMainSemanticCover(
        child: StaticThemeRendererSession,
        batch: UiSyncPhysicalOutputBatch,
        displayId: UltraUiSyncDisplayId,
        submittedRevision: Long,
    ): MainSemanticPhysicalCoverTicket? {
        if (submittedRevision < 0L) return null
        val suspension = synchronized(lock) {
            val record = registrations[child] ?: return null
            val target = activeBoundTargets?.singleOrNull {
                it.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN &&
                    it.type == 110 && it.route.assignment.output.uiSyncDisplayId == displayId
            } ?: return null
            if (revoked || binding || record.closed || !record.bound ||
                record.child !== child || record.seal !== seal ||
                activeIssuedBatch !== batch ||
                activeSessionContext?.rendererSession !== child ||
                activeBackend?.generation != batch.generation ||
                record.sceneBindings?.any { it.destination == target.destination } != true
            ) return null
            val activityGeneration = surfaces.currentMainCoverActivityGeneration(
                target.hostVersion, target.surface,
            ) ?: return null
            val existing = mainSemanticSuspension
            if (existing != null && existing.record === record &&
                existing.child === child && existing.batch === batch &&
                existing.target === target && existing.displayId == displayId
            ) existing else {
                val epoch = mainCoverGeneration.coverAfterSubmittedScene(
                    maxOf(submittedRevision, mainReproofChain?.latestRevision ?: 0L),
                )
                mainReproofWork = null
                mainReproofChain = null
                MainSemanticSuspension(record, child, batch, target, displayId,
                    activityGeneration, submittedRevision, epoch,
                ).also { mainSemanticSuspension = it }
            }
        }
        return MainSemanticPhysicalCoverTicket {
            val target = synchronized(lock) {
                if (!sameLiveSemanticSuspensionLocked(suspension) ||
                    !mainCoverGeneration.isCurrent(suspension.coverEpoch)
                ) return@MainSemanticPhysicalCoverTicket false
                suspension.target
            }
            if (!surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface) ||
                surfaces.currentMainCoverActivityGeneration(
                    target.hostVersion, target.surface,
                ) !== suspension.activityGeneration ||
                !surfaces.coverExactMainPhysicalTarget(target.hostVersion, target.surface)
            ) return@MainSemanticPhysicalCoverTicket false
            synchronized(lock) {
                if (!sameLiveSemanticSuspensionLocked(suspension) ||
                    surfaces.currentMainCoverActivityGeneration(
                        target.hostVersion, target.surface,
                    ) !== suspension.activityGeneration
                ) false else {
                    suspension.coverAcknowledged = true
                    true
                }
            }
        }
    }

    /** A fresh issued phone visual choice can lift the guard only after exact Host cover ACK. */
    internal fun resumeMainAfterSignedPhoneVisualChoice(
        child: StaticThemeRendererSession,
        batch: UiSyncPhysicalOutputBatch,
        displayId: UltraUiSyncDisplayId,
        newRevision: Long,
    ): Boolean = synchronized(lock) {
        fun trace(granted: Boolean, reason: String) = UltraRevealTrace.mark(
            UltraRevealEvent.REVEAL_MAIN_RESUME, "granted" to granted, "reason" to reason,
            "revision" to newRevision)
        val suspension = mainSemanticSuspension ?: run {
            trace(true, "NO_SUSPENSION")
            return@synchronized true
        }
        if (!sameLiveSemanticSuspensionLocked(suspension) ||
            suspension.child !== child || suspension.batch !== batch ||
            suspension.displayId != displayId || !suspension.coverAcknowledged ||
            surfaces.currentMainCoverActivityGeneration(
                suspension.target.hostVersion, suspension.target.surface,
            ) !== suspension.activityGeneration ||
            newRevision <= suspension.previousSubmittedRevision
        ) {
            trace(false, when {
                !sameLiveSemanticSuspensionLocked(suspension) -> "SUSPENSION_NOT_LIVE"
                suspension.child !== child || suspension.batch !== batch ||
                    suspension.displayId != displayId -> "OTHER_SESSION_OR_BATCH"
                !suspension.coverAcknowledged -> "COVER_NOT_ACKNOWLEDGED"
                newRevision <= suspension.previousSubmittedRevision -> "STALE_REVISION"
                else -> "HOST_ACTIVITY_CHANGED"
            })
            return@synchronized false
        }
        mainSemanticSuspension = null
        trace(true, "LIFTED")
        true
    }

    private fun sameLiveSemanticSuspensionLocked(
        suspension: MainSemanticSuspension,
    ): Boolean = !revoked && !binding && mainSemanticSuspension === suspension &&
        activeIssuedBatch === suspension.batch &&
        activeSessionContext?.rendererSession === suspension.child &&
        registrations[suspension.child] === suspension.record &&
        !suspension.record.closed && suspension.record.bound &&
        suspension.record.seal === seal &&
        activeBackend?.generation == suspension.batch.generation &&
        activeBoundTargets?.any { it === suspension.target } == true

    private fun coverBorrowedPhysicalTargets(
        targets: List<Target>,
        borrowed: List<Borrowed>,
    ): Boolean {
        if (targets.size != borrowed.size || !coversSignedPhysicalDestinations(targets)) return false
        if (targets.indices.any { borrowed[it].target !== targets[it] }) return false
        return coverExactFrozenBorrowedTargets(borrowed)
    }

    /** A partial bind has no output on destinations it never borrowed. */
    private fun coverExactFrozenBorrowedTargets(borrowed: List<Borrowed>): Boolean {
        if (borrowed.isEmpty() || borrowed.map(Borrowed::target).toSet().size != borrowed.size)
            return false
        var acknowledged = true
        borrowed.forEach { exact ->
            val target = exact.target
            val covered = runCatching {
                when (target.hostVersion.kind) {
                    HostDisplaySurfaceKind.PHYSICAL_MAIN ->
                        exact.coverExactMainUiForRetirement()
                    HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ->
                        exact.coverExactExternalUiForRetirement()
                    HostDisplaySurfaceKind.PREVIEW_ONLY -> false
                }
            }.getOrDefault(false)
            if (!covered) acknowledged = false
        }
        return acknowledged
    }

    private fun coverPhysicalTargetsAfterFailedBind(
        targets: List<Target>,
        borrowed: List<Borrowed>,
        nativeUseMayHaveStarted: Boolean,
    ): Boolean {
        if (!coversSignedPhysicalDestinations(targets)) return false
        if (nativeUseMayHaveStarted && borrowed.size != targets.size) return false
        if (borrowed.any { exact -> targets.none { it === exact.target } }) return false
        return borrowed.isEmpty() || coverExactFrozenBorrowedTargets(borrowed)
    }

    private fun coversSignedPhysicalDestinations(targets: List<Target>): Boolean =
        targets.isNotEmpty() && targets.size == requirement.assignments.size &&
            targets.all { target ->
                target.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN ||
                    target.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL
            } && (requirement.assignments.none {
                it.destination.role == UiSyncPhysicalDestinationRole.MAIN
            } || targets.any {
                it.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN
            })

    /** Pure lock-only phase: deny every later bind/reveal before any Host ACK can block. */
    internal fun beginPolicyEpochRevocation() {
        synchronized(lock) { beginPolicyEpochRevocationLocked() }
    }

    /** Must be entered with [lock]; never invokes the Host cover callback. */
    private fun beginPolicyEpochRevocationLocked(): PolicyCoverTicket? {
        revoked = true
        clusterPhoneEpochs.values.forEach { it.replayLedger.invalidate() }
        mainReproofWork = null
        mainReproofChain = null
        mainReproofScheduler?.shutdown()
        mainReproofScheduler = null
        mainSemanticSuspension = null
        registrations.values.forEach { record ->
            record.pendingSceneActivation = false
            record.retryRequested = false
        }
        return policyCoverTicket ?: run {
            val bound = activeBoundTargets
            val activeBorrows = activeBorrowedTargets
            val inFlight = bindProgress
            when {
                bound != null && activeBorrows != null -> PolicyCoverTicket(
                    { coverBorrowedPhysicalTargets(bound, activeBorrows) },
                    requiresAcknowledgement = true,
                )
                inFlight != null -> {
                    val targets = inFlight.targets
                    val borrowed = inFlight.borrowed.toList()
                    // A raced acquire not yet published here cannot cross the native-use
                    // gate after revocation. Published borrows still ask their exact old UI
                    // owners for ACK, even if no EGL attach began.
                    val completeNativeSet = !inFlight.nativeUseMayHaveStarted ||
                        (borrowed.size == targets.size && targets.indices.all {
                            borrowed[it].target === targets[it]
                        })
                    PolicyCoverTicket(
                        {
                            completeNativeSet && (borrowed.isEmpty() ||
                                coverExactFrozenBorrowedTargets(borrowed))
                        },
                        requiresAcknowledgement = borrowed.isNotEmpty() ||
                            inFlight.nativeUseMayHaveStarted,
                    )
                }
                pendingCover != null -> PolicyCoverTicket(
                    checkNotNull(pendingCover),
                    requiresAcknowledgement = true,
                )
                else -> null // Preflight alone never touched native Ultra output.
            }.also { policyCoverTicket = it }
        }
    }

    /**
     * Phase two asks the frozen old Host owners to cover outside every Authority/coordinator/root
     * lock. A failed or timed-out ACK retains this exact ticket for retry; a newer Host Surface
     * or Activity can never acknowledge it. This is Android UI cover, not optical scanout proof.
     */
    internal fun revokeForPolicyEpoch(): Boolean {
        val ticket = synchronized(lock) {
            val captured = beginPolicyEpochRevocationLocked()
            if (captured == null || captured.acknowledged) return true
            if (captured.inFlight) return false
            captured.inFlight = true
            captured
        }
        val acknowledged = runCatching { ticket.coverExactOldTargets() }.getOrDefault(false)
        return synchronized(lock) {
            ticket.inFlight = false
            if (acknowledged) ticket.acknowledged = true
            ticket.acknowledged
        }
    }

    /** Existing local failure path also uses the exact, retryable cover request. */
    fun revoke() { revokeForPolicyEpoch() }

    /**
     * MAIN can leave its black Host cover only after this exact signed scene's EGL swap/finish.
     * Local layers require their later same-revision Canvas post/draw/swap/finish receipt.
     * The Host joins this producer timestamp with its own exact TextureView frame callback.
     */
    internal fun onMainScenePresented(
        child: StaticThemeRendererSession,
        proof: UltraThemeMainPresentedFrame,
    ) {
        fun deny(reason: String) = UltraRevealTrace.mark(UltraRevealEvent.REVEAL_AUTHORITY_MAIN,
            "decision" to "DENIED", "reason" to reason, "revision" to proof.revision)
        val current = synchronized(lock) {
            val record = registrations[child] ?: return deny("NOT_REGISTERED")
            val batch = activeIssuedBatch ?: return deny("NO_ACTIVE_BATCH")
            val target = activeBoundTargets?.singleOrNull {
                it.destination == proof.scene.destination &&
                    it.hostVersion.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN
            } ?: return deny("NO_MAIN_TARGET")
            if (mainSemanticSuspension != null) return deny("SEMANTIC_SUSPENSION")
            if (revoked || binding || record.closed || !record.bound ||
                record.child !== child || record.coordinator !== child.coordinator ||
                record.seal !== seal || activeBackend == null
            ) return deny("SESSION_NOT_CURRENT")
            if (proof.physicalBatch !== batch) return deny("BATCH_MISMATCH")
            if (proof.scene.destination.role != DisplayDestinationRole.MAIN ||
                proof.scene.viewportPixels != target.hostVersion.sizePixels ||
                record.sceneBindings?.any { it.destination == target.destination } != true
            ) return deny("SCENE_TARGET_MISMATCH")
            Triple(record, batch, target)
        }
        val (record, batch, target) = current
        // A plain decoder/video frame has no signed local UI content. It cannot expose MAIN.
        if (proof.scene.background !is UltraSceneBackground.OpaqueSrgb &&
            proof.scene.layers.none {
                it.source is UltraSceneSource.Local || it.source is UltraSceneSource.Overlay
            }
        ) return deny("NO_SIGNED_LOCAL_CONTENT")
        if (proof.ticket.revision != proof.revision) return deny("TICKET_REVISION_MISMATCH")
        val result = runCatching { proof.ticket.await(0L) }.getOrNull()
            ?: return deny("TICKET_NOT_COMPLETE")
        val localDraws = proof.scene.layers.mapIndexedNotNull { index, layer ->
            val source = layer.source as? UltraSceneSource.Local ?: return@mapIndexedNotNull null
            UltraEglLocalLayerDraw(
                target.destination, UltraEglIngressKey.Local(source.contentId), index, layer,
            )
        }.toSet()
        val seal = mainPhysicalPresentedFrameSealOrNull(
            target.destination, proof.revision, proof.graph.revision, batch.generation,
            result, localDraws, proof.localFirstFrameReceipt,
        ) ?: return deny("NO_PRESENTED_SEAL_${result.disposition.name}")
        val coverEpoch = synchronized(lock) {
            if (revoked || mainSemanticSuspension != null) return deny("SUSPENDED_AFTER_SEAL")
            mainCoverGeneration.admitPresented(
                seal.signedSceneRevision, seal.producerTimestampNanos,
            ) ?: return deny("COVER_GENERATION_REJECTED")
        }
        // The output generation is native EGL state for this immutable batch. A transient
        // AA05/backend BUSY state must retain this one-shot presented proof under the cover.
        val activityGeneration = surfaces.currentMainCoverActivityGeneration(
            target.hostVersion, target.surface,
        ) ?: return deny("NO_HOST_ACTIVITY_GENERATION")
        val chain = synchronized(lock) {
            val prior = mainReproofChain
            val selected = prior?.takeIf {
                it.record === record && it.child === child && it.batch === batch &&
                    it.target === target && it.graph === proof.graph &&
                    it.activityGeneration === activityGeneration &&
                    sameSignedMainScene(it.scene, proof.scene)
            } ?: MainReproofChain(
                record, child, batch, target, proof.graph, activityGeneration,
                proof.scene, proof.revision,
            )
            if (selected !== prior || selected.latestRevision != proof.revision)
                mainReproofWork = null
            selected.latestRevision = proof.revision
            mainReproofChain = selected
            selected
        }
        val statusProbe: () -> MainPhysicalUiPermitStatus = {
            mainPresentedPermitStatus(
                record, child, batch, target, proof.graph, proof.revision,
                activityGeneration, coverEpoch,
            )
        }
        lateinit var permit: AuthorizedMainPhysicalUiPermit
        permit = AuthorizedMainPhysicalUiPermit(
            this, mainCoverIssuerNonce, target.hostVersion, target.surface,
            activityGeneration, seal.producerTimestampNanos, seal.outputBatchGeneration,
            seal.outputGeneration, seal.signedSceneRevision,
            stillAuthorized = { statusProbe() == MainPhysicalUiPermitStatus.CURRENT },
            authorizationStatusProbe = statusProbe,
            onMissedFrame = { observed -> onMainProducerTimestampMissed(
                permit, observed, chain,
            ) },
        )
        if (statusProbe() == MainPhysicalUiPermitStatus.REVOKED) return deny("PERMIT_REVOKED")
        val armed = surfaces.armMainAfterPresentedScene(permit)
        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_AUTHORITY_MAIN,
            "decision" to if (armed) "ARMED" else "DENIED",
            "reason" to if (armed) "HOST_ARMED" else "HOST_ARM_REJECTED",
            "revision" to proof.revision)
        if (armed &&
            permit.authorizationFor(
                target.hostVersion, target.surface, activityGeneration,
            ) == MainPhysicalUiPermitStatus.REVOKED
        ) surfaces.coverExactMainPhysicalTarget(target.hostVersion, target.surface)
    }

    private fun sameSignedMainScene(a: UltraOutputScene, b: UltraOutputScene): Boolean {
        if (a.destination != b.destination || a.viewportPixels != b.viewportPixels ||
            a.layers != b.layers
        ) return false
        val first = a.background
        val second = b.background
        if (first is UltraSceneBackground.Transparent &&
            second is UltraSceneBackground.Transparent
        ) return true
        return first is UltraSceneBackground.OpaqueSrgb &&
            second is UltraSceneBackground.OpaqueSrgb &&
            first.layoutAddress == second.layoutAddress && first.color == second.color &&
            first.graphSnapshot === second.graphSnapshot &&
            first.graphOutput === second.graphOutput
    }

    /** Invoked only after the Host gate ACKs an exact opaque cover for U > permit T. */
    private fun onMainProducerTimestampMissed(
        permit: AuthorizedMainPhysicalUiPermit,
        observedNanos: Long,
        chain: MainReproofChain,
    ) {
        if (!authenticatesMainCoverPermit(permit) ||
            observedNanos <= permit.producerTimestampNanos ||
            permit.version != chain.target.hostVersion ||
            permit.surface !== chain.target.surface ||
            permit.activityGeneration !== chain.activityGeneration ||
            permit.signedSceneRevision != chain.latestRevision ||
            permit.outputBatchGeneration != chain.batch.generation
        ) return
        val work = synchronized(lock) {
            if (revoked || mainReproofChain !== chain ||
                chain.latestRevision != permit.signedSceneRevision ||
                mainReproofWork != null || chain.budget.isExhausted()
            ) return
            MainReproofWork(permit, chain,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAIN_REPROOF_WINDOW_MILLIS)
            ).also { mainReproofWork = it }
        }
        scheduleMainReproof(work, 0L)
    }

    private fun scheduleMainReproof(work: MainReproofWork, delayMillis: Long) {
        val scheduler = synchronized(lock) {
            if (revoked || mainReproofWork !== work) return
            mainReproofScheduler ?: ScheduledThreadPoolExecutor(1) { command ->
                Thread(command, "xcertplay-main-signed-reproof").apply { isDaemon = true }
            }.also {
                it.removeOnCancelPolicy = true
                mainReproofScheduler = it
            }
        }
        runCatching {
            scheduler.schedule({ runMainReproof(work) }, delayMillis, TimeUnit.MILLISECONDS)
        }.onFailure { synchronized(lock) {
            if (mainReproofWork === work) mainReproofWork = null
        } }
    }

    private fun runMainReproof(work: MainReproofWork) {
        val chain = work.chain
        val permit = work.permit
        if (System.nanoTime() >= work.deadlineNanos ||
            work.retries >= MAXIMUM_MAIN_REPROOF_BUSY_RETRIES
        ) {
            synchronized(lock) { if (mainReproofWork === work) mainReproofWork = null }
            return
        }
        val stale = synchronized(lock) {
                revoked || mainReproofWork !== work || mainReproofChain !== chain ||
                    chain.latestRevision != permit.signedSceneRevision ||
                    activeIssuedBatch !== chain.batch ||
                    registrations[chain.child] !== chain.record ||
                    activeBoundTargets?.any { it === chain.target } != true
            }
        if (stale) {
            synchronized(lock) { if (mainReproofWork === work) mainReproofWork = null }
            return
        }
        val epoch = work.coverEpoch
        val status = if (epoch == null) permit.authorizationFor(
            chain.target.hostVersion, chain.target.surface, chain.activityGeneration,
        ) else mainPresentedPermitStatus(
            chain.record, chain.child, chain.batch, chain.target, chain.graph,
            permit.signedSceneRevision, chain.activityGeneration, epoch,
        )
        when (status) {
            MainPhysicalUiPermitStatus.REVOKED -> {
                synchronized(lock) { if (mainReproofWork === work) mainReproofWork = null }
                return
            }
            MainPhysicalUiPermitStatus.BUSY -> {
                retryMainReproof(work)
                return
            }
            MainPhysicalUiPermitStatus.CURRENT -> Unit
        }
        if (epoch == null) {
            val reserved = synchronized(lock) {
                if (revoked || mainReproofWork !== work || mainReproofChain !== chain ||
                    chain.latestRevision != permit.signedSceneRevision ||
                    !chain.budget.reserve()
                ) false else {
                    work.coverEpoch = mainCoverGeneration.coverAfterSubmittedScene(
                        permit.signedSceneRevision,
                    )
                    true
                }
            }
            if (!reserved) {
                synchronized(lock) { if (mainReproofWork === work) mainReproofWork = null }
                return
            }
        }
        val result = runCatching {
            chain.child.reproveMainSceneAfterMiss(
                chain.graph, chain.batch, permit.signedSceneRevision,
            )
        }.getOrDefault(UltraThemeMainReproofResult.REVOKED)
        when (result) {
            UltraThemeMainReproofResult.SUBMITTED,
            UltraThemeMainReproofResult.REVOKED -> synchronized(lock) {
                if (mainReproofWork === work) mainReproofWork = null
            }
            UltraThemeMainReproofResult.BUSY_RETRY -> retryMainReproof(work)
        }
    }

    private fun retryMainReproof(work: MainReproofWork) {
        work.retries++
        val delay = work.retryDelayMillis
        work.retryDelayMillis = (delay * 2L).coerceAtMost(250L)
        scheduleMainReproof(work, delay)
    }

    private fun mainPresentedPermitStatus(
        record: Registration,
        child: StaticThemeRendererSession,
        batch: UiSyncPhysicalOutputBatch,
        target: Target,
        graph: UltraDisplayGraphSnapshot,
        revision: Long,
        activityGeneration: Any,
        coverEpoch: Long,
    ): MainPhysicalUiPermitStatus {
        val exact = synchronized(lock) {
            !revoked && !binding && mainSemanticSuspension == null &&
                mainCoverGeneration.isCurrent(coverEpoch) &&
                activeIssuedBatch === batch && activeSessionContext?.rendererSession === child &&
                registrations[child] === record && !record.closed && record.bound &&
                record.child === child && record.coordinator === child.coordinator &&
                record.seal === seal && activeBackend?.generation == batch.generation &&
                activeBoundTargets?.any { it === target } == true
        }
        if (!exact || !surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface) ||
            surfaces.currentMainCoverActivityGeneration(
                target.hostVersion, target.surface,
            ) !== activityGeneration
        ) return MainPhysicalUiPermitStatus.REVOKED
        val batchStatus = batch.generationStatus
        val generationStatus = sameGenerationStatus(record.seal)
        if (batchStatus == UiSyncPhysicalGenerationStatus.REVOKED ||
            generationStatus == UiSyncPhysicalGenerationStatus.REVOKED
        ) return MainPhysicalUiPermitStatus.REVOKED
        if (batchStatus == UiSyncPhysicalGenerationStatus.BUSY ||
            generationStatus == UiSyncPhysicalGenerationStatus.BUSY
        ) return MainPhysicalUiPermitStatus.BUSY
        return if (child.isLive && record.coordinator.isLive &&
            child.readinessReceipt === record.readinessReceipt &&
            record.coordinator.graphSnapshot() === graph &&
            record.coordinator.stateSnapshot().lastSubmittedRenderRevision == revision
        ) MainPhysicalUiPermitStatus.CURRENT else MainPhysicalUiPermitStatus.REVOKED
    }

    /**
     * Only the registered signed child can present this callback. SUBMITTED is insufficient:
     * the exact CLUSTER scene ticket must have PRESENTED after all EGL swaps/finish, and local
     * layers additionally require their same-revision first-frame receipt. TextureView's later
     * callback remains a separate UI-cover gate and is not an exact producer-buffer ID.
     */
    internal fun onClusterScenePresented(
        child: StaticThemeRendererSession,
        proof: UltraThemeClusterPresentedFrame,
    ) {
        val current = synchronized(lock) {
            val record = registrations[child] ?: return
            val batch = activeIssuedBatch ?: return
            val target = activeBoundTargets?.singleOrNull {
                it.destination == proof.scene.destination && it.type == 111
            } ?: return
            if (revoked || binding || record.closed || !record.bound ||
                record.child !== child || record.coordinator !== child.coordinator ||
                record.seal !== seal || activeBackend == null ||
                proof.physicalBatch !== batch ||
                proof.scene.destination.role != DisplayDestinationRole.CLUSTER
            ) return
            Triple(record, batch, target)
        }
        val (record, batch, target) = current
        // EGL/backend probes can block. They must not hold the lock needed by STOPPING cover.
        if (!batch.isCurrent || proof.graph !== record.coordinator.graphSnapshot() ||
            record.coordinator.stateSnapshot().lastSubmittedRenderRevision != proof.revision
        ) return
        // Instrument output needs its own signed content-state proof for every upload/replay.
        // A generic scene ticket must never bypass the initial safety clear or source age.
        if (proof.scene.layers.any { it.source is UltraSceneSource.Instrument }) return
        val result = runCatching { proof.ticket.await(0L) }.getOrNull() ?: return
        if (proof.ticket.revision != proof.revision || result.revision != proof.revision ||
            result.disposition != UltraEglRenderDisposition.PRESENTED ||
            result.graphRevision != proof.graph.revision ||
            result.outputBatchGeneration == null || result.outputBatchGeneration <= 0L
        ) return
        val sceneOutputGeneration = result.outputGenerationsByDestination[target.destination]
            ?.takeIf { it > 0L } ?: return
        val localDraws = proof.scene.layers.mapIndexedNotNull { index, layer ->
            val source = layer.source as? UltraSceneSource.Local ?: return@mapIndexedNotNull null
            UltraEglLocalLayerDraw(
                proof.scene.destination, UltraEglIngressKey.Local(source.contentId), index, layer,
            )
        }.toSet()
        if (localDraws.isNotEmpty()) {
            val receipt = proof.localFirstFrameReceipt ?: return
            if (receipt.revision != proof.revision ||
                receipt.graphRevision != proof.graph.revision ||
                receipt.physicalBatchGeneration != batch.generation ||
                receipt.outputBatchGeneration != result.outputBatchGeneration ||
                receipt.outputGenerationsByDestination[target.destination] !=
                    sceneOutputGeneration ||
                receipt.requiredDraws.filterTo(LinkedHashSet()) {
                    it.destination == target.destination
                } != localDraws
            ) return
        }
        val timestamp = (if (localDraws.isEmpty()) result.producerTimestampNanosByDestination
            else proof.localFirstFrameReceipt?.producerTimestampNanosByDestination)
            ?.get(target.destination)?.takeIf { it > 0L } ?: return
        val displayId = target.route.assignment.output.uiSyncDisplayId ?: return
        val epochGate = synchronized(lock) { clusterPhoneEpochLocked(target) }
        // A fresh scene can be presented before the exact Host cover ACK. Park it for that
        // epoch; the ACK owner re-runs this method, so the CLUSTER cannot stay dark forever.
        val phoneEpoch = epochGate.admissionEpoch(proof.revision, timestamp) ?: run {
            if (child.clusterRecoveryWitness(displayId, proof.revision) &&
                epochGate.parkUntilAcknowledged(
                    proof.revision, timestamp, ParkedClusterScene(child, proof),
                )
            ) return
            epochGate.admissionEpoch(proof.revision, timestamp)
        } ?: return
        if (!ownsBoundBatch(child, batch) || !sameLiveGeneration(record.seal) ||
            !surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface) ||
            !child.clusterRecoveryWitness(displayId, proof.revision)
        ) return
        if (synchronized(lock) { revoked }) return
        val permit = AuthorizedExternalClusterUiPermit(
            this, externalCoverIssuerNonce, target.hostVersion, target.surface,
            timestamp, result.outputBatchGeneration, sceneOutputGeneration,
            clearBeforeReveal = false,
        ) {
            epochGate.isCurrent(phoneEpoch) && ownsBoundBatch(child, batch) &&
                sameLiveGeneration(record.seal) &&
                child.clusterRecoveryWitness(displayId, proof.revision) &&
                record.coordinator.graphSnapshot() === proof.graph &&
                record.coordinator.stateSnapshot().lastSubmittedRenderRevision == proof.revision &&
                surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)
        }
        if (surfaces.armExternalAfterPresentedScene(permit)) {
            if (epochGate.commitArmed(phoneEpoch, proof.revision, timestamp) &&
                child.clusterRecoveryWitness(displayId, proof.revision) &&
                ownsBoundBatch(child, batch)
            ) epochGate.replayLedger.commitOtherPresentedTimestamp(timestamp)
            else coverStaleExternalArm(target, batch, epochGate, phoneEpoch)
        }
    }

    /** Each dynamic instrument swap needs a new owner-minted content proof and EGL timestamp. */
    internal fun onInstrumentClusterReplayPresented(
        child: StaticThemeRendererSession,
        proof: SignedInstrumentContentProof,
        replay: UltraEglClusterReplayReceipt,
    ): InstrumentReplayPermitDecision {
        if (!replay.instrumentOnlyClusterScene ||
            replay.destination.role != DisplayDestinationRole.CLUSTER ||
            replay.producerTimestampNanos <= 0L || replay.replaySequence <= 0L ||
            replay.nativeOutputBatchGeneration <= 0L ||
            replay.nativeOutputGeneration <= 0L ||
            replay.instrumentIngressGeneration <= 0L ||
            replay.instrumentFrameSequence <= 0L ||
            !proof.initialSafetyClearEstablished ||
            proof.clearBeforeReveal !=
                (proof.contentKind == SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR) ||
            proof.profile !== verifiedProfile ||
            !child.acceptsInstrumentContentProof(proof)
        ) return InstrumentReplayPermitDecision.REVOKED
        val current = synchronized(lock) {
            val record = registrations[child] ?: return InstrumentReplayPermitDecision.REVOKED
            val batch = activeIssuedBatch ?: return InstrumentReplayPermitDecision.REVOKED
            val target = activeBoundTargets?.singleOrNull {
                it.destination == replay.destination && it.type == 111
            } ?: return InstrumentReplayPermitDecision.REVOKED
            val signedInstrument = record.seal.manifest.instrumentCrosswalk ?:
                return InstrumentReplayPermitDecision.REVOKED
            val signedDisplay = runCatching {
                record.seal.manifest.displayAssignmentsFor(verifiedProfile).singleOrNull {
                    it.destination == target.destination &&
                        it.profileDisplayIndex == proof.handle.assignment.profileDisplayIndex &&
                        it.profileDisplayId == proof.handle.assignment.profileDisplayId
                }
            }.getOrNull() ?: return InstrumentReplayPermitDecision.REVOKED
            val signedReferences = signedDisplay.instrumentReferenceAssignments +
                signedDisplay.additionalVariants.flatMap { it.instrumentReferenceAssignments }
            val exactSignedReference = signedReferences.any {
                it.slot == proof.handle.reference.slot &&
                    sameInstrumentSignalSpec(it.spec, proof.handle.reference.spec)
            } && signedInstrument.assignments.any {
                it.profileDisplayIndex == proof.handle.assignment.profileDisplayIndex &&
                    it.profileDisplayId == proof.handle.assignment.profileDisplayId &&
                    it.spec.address.slot == proof.handle.reference.slot &&
                    sameInstrumentSignalSpec(it.spec, proof.handle.assignment.spec)
            }
            if (revoked || binding || record.closed || !record.bound ||
                record.child !== child || record.coordinator !== child.coordinator ||
                record.seal !== seal || activeBackend == null ||
                proof.batch !== batch ||
                proof.provisioning !== record.seal.provisioning ||
                proof.destination != target.destination ||
                proof.viewportPixels != target.hostVersion.sizePixels ||
                proof.sceneRevision != replay.sceneRevision ||
                proof.graphRevision != replay.graphRevision ||
                proof.nativeOutputBatchGeneration != replay.nativeOutputBatchGeneration ||
                proof.nativeOutputGeneration != replay.nativeOutputGeneration ||
                proof.instrumentIngressGeneration != replay.instrumentIngressGeneration ||
                proof.instrumentFrameSequence != replay.instrumentFrameSequence ||
                proof.producerTimestampNanos != replay.producerTimestampNanos ||
                proof.replaySequence != replay.replaySequence ||
                !exactSignedReference ||
                !signedInstrument.matchesProfile(verifiedProfile) ||
                !signedInstrument.matchesCaf(record.seal.manifest.caf)
            ) return InstrumentReplayPermitDecision.REVOKED
            Triple(record, batch, target)
        }
        val (record, batch, target) = current
        if (record.coordinator.graphSnapshot().revision != replay.graphRevision ||
            record.coordinator.stateSnapshot().lastSubmittedRenderRevision != replay.sceneRevision
        ) return InstrumentReplayPermitDecision.REVOKED
        instrumentReplayTemporaryDecision(record, batch, proof)?.let { return it }
        if (!proof.isCurrentFor(replay) || !ownsBoundBatch(child, batch) ||
            !sameLiveGeneration(record.seal)
        ) return instrumentReplayTemporaryDecision(record, batch, proof)
            ?: InstrumentReplayPermitDecision.REVOKED
        if (!surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface))
            return InstrumentReplayPermitDecision.REVOKED
        val displayId = target.route.assignment.output.uiSyncDisplayId ?:
            return InstrumentReplayPermitDecision.REVOKED
        val epochGate = synchronized(lock) { clusterPhoneEpochLocked(target) }
        val admission = epochGate.recoveryAdmission(
            replay.sceneRevision, replay.producerTimestampNanos,
        )
        clusterRecoveryReplayEpochDecision(admission) {
            child.clusterRecoveryWitness(displayId, replay.sceneRevision)
        }?.let { return it }
        val phoneEpoch = (admission as ClusterRecoveryAdmission.Admitted).epoch
        if (!child.clusterRecoveryWitness(displayId, replay.sceneRevision))
            return InstrumentReplayPermitDecision.REVOKED
        val attempt = when (val begin = epochGate.replayLedger.begin(
            replay, replay.producerTimestampNanos,
        )) {
            is ExternalClusterReplayArmLedger.Begin.Ready -> begin.attempt
            ExternalClusterReplayArmLedger.Begin.Busy ->
                return InstrumentReplayPermitDecision.BUSY_RETRY
            ExternalClusterReplayArmLedger.Begin.AlreadyArmed ->
                return if (epochGate.isCurrent(phoneEpoch) &&
                    child.clusterRecoveryWitness(displayId, replay.sceneRevision) &&
                    child.acceptsInstrumentContentProof(proof) && ownsBoundBatch(child, batch)
                ) InstrumentReplayPermitDecision.ARMED
                else InstrumentReplayPermitDecision.REVOKED
            ExternalClusterReplayArmLedger.Begin.Rejected ->
                return InstrumentReplayPermitDecision.REVOKED
        }
        val permit = AuthorizedExternalClusterUiPermit(
            this, externalCoverIssuerNonce, target.hostVersion, target.surface,
            replay.producerTimestampNanos, replay.nativeOutputBatchGeneration,
            replay.nativeOutputGeneration,
            clearBeforeReveal = proof.clearBeforeReveal,
        ) {
            epochGate.isCurrent(phoneEpoch) && ownsBoundBatch(child, batch) &&
                sameLiveGeneration(record.seal) &&
                child.clusterRecoveryWitness(displayId, replay.sceneRevision) &&
                child.acceptsInstrumentContentProof(proof) && proof.isCurrentFor(replay) &&
                record.coordinator.graphSnapshot().revision == replay.graphRevision &&
                record.coordinator.stateSnapshot().lastSubmittedRenderRevision ==
                    replay.sceneRevision &&
                surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)
        }
        val armed = runCatching { surfaces.armExternalAfterPresentedScene(permit) }
            .getOrDefault(false)
        val stillExact = synchronized(lock) {
            !revoked && activeIssuedBatch === batch &&
                registrations[child] === record && !record.closed
        } && surfaces.isSameLivePhysicalTarget(target.hostVersion, target.surface)
        if (!stillExact || instrumentReplayTemporaryDecision(record, batch, proof) ==
            InstrumentReplayPermitDecision.REVOKED ||
            !child.clusterRecoveryWitness(displayId, replay.sceneRevision) ||
            (armed && !epochGate.commitArmed(
                phoneEpoch, replay.sceneRevision, replay.producerTimestampNanos,
            )) || !epochGate.isCurrent(phoneEpoch)
        ) {
            epochGate.replayLedger.invalidate()
            if (armed) coverStaleExternalArm(target, batch, epochGate, phoneEpoch)
            return InstrumentReplayPermitDecision.REVOKED
        }
        return epochGate.replayLedger.finish(attempt, armed)
    }

    /** RESET preserves the borrowed route; only terminal/stale-target cleanup retires it. */
    private fun coverStaleExternalArm(
        target: Target,
        batch: UiSyncPhysicalOutputBatch,
        epochGate: ExternalClusterPhoneEpochGate,
        armedEpoch: Long,
    ) {
        val recoverablePhoneEpoch = synchronized(lock) {
            !revoked && activeIssuedBatch === batch &&
                activeBoundTargets?.any { it === target } == true &&
                epochGate.isSuperseded(armedEpoch)
        }
        if (recoverablePhoneEpoch && surfaces.isSameLivePhysicalTarget(
                target.hostVersion, target.surface,
            )
        ) surfaces.coverExactBorrowedExternalPhysicalTarget(target.hostVersion, target.surface)
        else surfaces.coverExactExternalPhysicalTarget(target.hostVersion, target.surface)
    }

    private fun instrumentReplayTemporaryDecision(
        record: Registration,
        batch: UiSyncPhysicalOutputBatch,
        proof: SignedInstrumentContentProof,
    ): InstrumentReplayPermitDecision? {
        val physical = runCatching { batch.generationStatus }.getOrNull()
            ?: return InstrumentReplayPermitDecision.REVOKED
        val aa05 = runCatching { sameGenerationStatus(record.seal) }.getOrNull()
            ?: return InstrumentReplayPermitDecision.REVOKED
        if (physical == UiSyncPhysicalGenerationStatus.REVOKED ||
            aa05 == UiSyncPhysicalGenerationStatus.REVOKED
        ) return InstrumentReplayPermitDecision.REVOKED
        if (physical == UiSyncPhysicalGenerationStatus.BUSY ||
            aa05 == UiSyncPhysicalGenerationStatus.BUSY
        ) return InstrumentReplayPermitDecision.BUSY_RETRY
        return when (runCatching {
            proof.provisioning.tryWithSceneSubmissionUse { Unit }
        }.getOrNull()) {
            UltraProfileSceneUseAttempt.Busy -> InstrumentReplayPermitDecision.BUSY_RETRY
            is UltraProfileSceneUseAttempt.Admitted -> null
            else -> InstrumentReplayPermitDecision.REVOKED
        }
    }

    private fun sameLiveGeneration(sealed: Seal): Boolean =
        sameGenerationStatus(sealed) == UiSyncPhysicalGenerationStatus.CURRENT

    private fun sameGenerationStatus(sealed: Seal): UiSyncPhysicalGenerationStatus =
        when (val status = generationStatusSource(sealed.token)) {
            is UltraProfilePhysicalGenerationStatus.Current -> {
                val current = status.proof
                if (current.matchesToken(sealed.token) && current.sameLineage(sealed.proof) &&
                    sealed.manifest.matchesGenerationIdentity(
                        current.runtimeIdentity, current.authenticationIdentity,
                    )
                ) UiSyncPhysicalGenerationStatus.CURRENT
                else UiSyncPhysicalGenerationStatus.REVOKED
            }
            UltraProfilePhysicalGenerationStatus.Busy -> UiSyncPhysicalGenerationStatus.BUSY
            UltraProfilePhysicalGenerationStatus.Revoked -> UiSyncPhysicalGenerationStatus.REVOKED
        }

    private fun retireClosedRegistrationLocked(record: Registration) {
        if (record.closed && !binding && activeBackend == null && pending == null &&
            registrations[record.child] === record
        ) registrations.remove(record.child)
    }

    private fun matches(
        candidate: UiSyncPhysicalOutputRequirement,
        plan: UiSyncRendererStaticPlan,
        schema: UiSyncSchemaFingerprint,
        binding: UiSyncBindingFingerprint,
    ): Boolean = candidate === requirement && plan === staticPlan &&
        schema == schemaFingerprint && binding == bindingFingerprint

    private fun inspectTargets(): List<Target>? {
        val result = ArrayList<Target>(requirement.assignments.size)
        for (assignment in requirement.assignments) {
            val destination = destinationFor(
                assignment.destination.role, assignment.destination.instanceId,
            )
            val inspection = surfaces.inspectPhysicalTarget(destination) ?: return null
            if (!kindMatches(destination.role, inspection.version.kind)) return null
            val type = routesByDestination[destination] ?: return null
            result += target(assignment, destination, type, inspection)
        }
        if (result.map(Target::surface).toIdentitySetSize() != result.size) return null
        return Collections.unmodifiableList(result)
    }

    private fun target(
        assignment: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment,
        destination: DisplayDestination,
        type: Int,
        inspection: HostPhysicalTargetInspection,
    ): Target = Target(
        route = UiSyncPhysicalOutputRoute(
            assignment,
            UiSyncPhysicalTargetVersion(
                targetId(destination), inspection.version.generation,
            ),
        ),
        hostVersion = inspection.version,
        destination = destination,
        type = type,
        surface = inspection.surface,
    )

    private fun sameTargets(a: List<Target>, b: List<Target>): Boolean =
        a.size == b.size && a.indices.all { index ->
            a[index].route == b[index].route &&
                a[index].hostVersion == b[index].hostVersion &&
                a[index].surface === b[index].surface &&
                a[index].type == b[index].type
        }

    private fun targetId(destination: DisplayDestination): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("xcertplay-host-physical-target-v1\u0000".toByteArray(StandardCharsets.US_ASCII))
        digest.update(destination.role.name.toByteArray(StandardCharsets.US_ASCII))
        digest.update(0)
        digest.update(destination.instanceId.value.toByteArray(StandardCharsets.UTF_8))
        return "host-" + digest.digest().take(16).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun kindMatches(role: DisplayDestinationRole, kind: HostDisplaySurfaceKind): Boolean =
        when (role) {
            DisplayDestinationRole.MAIN -> kind == HostDisplaySurfaceKind.PHYSICAL_MAIN
            else -> kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL
        }

    private fun destinationFor(role: UiSyncPhysicalDestinationRole, instance: String):
        DisplayDestination = DisplayDestination(
            when (role) {
                UiSyncPhysicalDestinationRole.MAIN -> DisplayDestinationRole.MAIN
                UiSyncPhysicalDestinationRole.CLUSTER -> DisplayDestinationRole.CLUSTER
                UiSyncPhysicalDestinationRole.HUD -> DisplayDestinationRole.HUD
                UiSyncPhysicalDestinationRole.AUXILIARY -> DisplayDestinationRole.AUXILIARY
            },
            DisplayDestinationId(instance),
        )

    private fun List<Surface>.toIdentitySetSize(): Int {
        val identities = IdentityHashMap<Surface, Unit>()
        forEach { identities[it] = Unit }
        return identities.size
    }

    private companion object {
        const val MAXIMUM_GRAPH_ACTIVATION_PASSES = 64
        const val MAXIMUM_GRAPH_ACTIVATION_SECONDS = 10L
        const val MAXIMUM_MAIN_REPROOF_BUSY_RETRIES = 12
        const val MAIN_REPROOF_WINDOW_MILLIS = 2_000L
        /** Poll period while an activation waits out a transient AA05/backend BUSY state. */
        val ACTIVATION_BUSY_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(1L)
    }
}
