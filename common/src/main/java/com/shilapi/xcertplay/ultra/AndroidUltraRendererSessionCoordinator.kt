package com.shilapi.xcertplay.ultra

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AndroidVideoFrameReleaseReceipt
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.ultra.egl.AndroidUltraEglBackend
import com.shilapi.xcertplay.ultra.egl.AndroidVideoSurfaceLeaseAcquirer
import com.shilapi.xcertplay.ultra.egl.MatchedRemoteFrameConsumerReceipt
import com.shilapi.xcertplay.ultra.egl.RemoteFrameIdentityDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentRasterLease
import com.shilapi.xcertplay.ultra.egl.UltraEglInstrumentUploadReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplaySubscription
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameExpectation
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalFirstFrameReceipt
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Session state is deliberately not named READY. This coordinator is a resource/lifetime bridge;
 * it is not evidence that a complete theme/semantic compiler exists or that UI Sync may be
 * advertised by product negotiation.
 */
enum class AndroidUltraRendererSessionPhase {
    PREPARING,
    ACTIVE,
    RETIREMENT_UNCERTAIN,
    CLOSED,
}

data class AndroidUltraRemoteIngressSnapshot(
    val ingressId: UltraIngressId,
    val stream: AirPlayVideoStreamHandle,
    val generation: Long,
    val phase: AndroidUltraRendererSessionPhase,
    val published: Boolean,
)

data class AndroidUltraOwnedIngressSnapshot(
    val key: UltraEglIngressKey,
    val generation: Long,
    val phase: AndroidUltraRendererSessionPhase,
)

data class AndroidUltraOutputSnapshot(
    val destination: DisplayDestination,
    val output: UltraDisplayGraphOutput,
    val generation: Long,
    val phase: AndroidUltraRendererSessionPhase,
)

data class AndroidUltraRendererSessionSnapshot(
    val phase: AndroidUltraRendererSessionPhase,
    val graphRevision: Long,
    val lastSubmittedRenderRevision: Long,
    val remoteIngresses: List<AndroidUltraRemoteIngressSnapshot>,
    val ownedIngresses: List<AndroidUltraOwnedIngressSnapshot>,
    val outputs: List<AndroidUltraOutputSnapshot>,
    val presentationSubscriptionCount: Int,
    val operationCount: Int,
    val closeRequested: Boolean,
    /** EGL/router route publication, not simultaneous physical frame swaps. */
    val crossOutputAttachmentIsAtomic: Boolean = false,
    /** Diagnostic route generation only; this is not a physical frame-swap fence. */
    val outputBatchGeneration: Long? = null,
    val lastFailure: Throwable?,
)

data class AndroidUltraOutputAttachmentRequest(
    val destination: DisplayDestination,
    val output: UltraDisplayGraphOutput,
    val target: AndroidDisplaySurfaceTarget,
)

/** A pre-enqueue local Surface generation fence for a theme-bound scene. */
internal data class AndroidUltraLocalIngressExpectation(
    val lease: AndroidUltraCustomIngressLease,
    val contentId: UltraContentId,
    val sizePixels: PixelSize,
)

internal class AndroidUltraBatchBinderResource(
    val binder: UltraDisplayBatchBinder,
    val closer: Closeable,
)

/**
 * A batch reports the exact successful prefix and the exact failed request; it never rolls back.
 * When [failure] is [AndroidUltraOutputAttachmentFailure], that object retains the failed target
 * until its close succeeds or the whole coordinator is successfully closed.
 */
data class AndroidUltraOutputAttachmentBatchResult(
    val attached: List<AndroidUltraOutputAttachment>,
    val failedRequest: AndroidUltraOutputAttachmentRequest?,
    val failure: Throwable?,
    val state: AndroidUltraRendererSessionSnapshot,
) {
    val complete: Boolean
        get() = failure == null
}

/**
 * Small deterministic seam around [AndroidUltraEglBackend]. The production adapter below is the
 * only place which translates coordinator calls into EGL calls; tests can exercise lifecycle and
 * reentrancy without creating native EGL resources.
 */
internal interface AndroidUltraRendererBackendPort {
    fun openRemoteIngress(ingress: UltraDisplayGraphIngress): AndroidUltraBackendIngress
    fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraBackendIngress
    fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraBackendIngress
    fun openInstrumentRaster(
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): AndroidUltraBackendInstrument = throw UnsupportedOperationException(
        "Backend has no dedicated instrument raster",
    )
    /**
     * Attaches an exact static-plan output. Validation failures happen before target ownership can
     * transfer. Once the backend call starts, an ambiguous failure is reported as
     * [AndroidUltraOutputAttachmentFailure]; callers must successfully close that failure (or this
     * coordinator) before closing an OWNED [target].
     */
    fun attachOutput(
        destination: DisplayDestination,
        output: UltraDisplayGraphOutput,
        target: AndroidDisplaySurfaceTarget,
    ): AndroidUltraBackendOutput

    fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission
    fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket
    /** A fake backend without a real EGL thread cannot manufacture first-frame evidence. */
    fun prepareLocalFirstFrame(
        revision: Long,
        outputBatchGeneration: Long,
        localKeys: Set<UltraEglIngressKey.Local>,
        carriedFrom: UltraEglLocalFirstFrameReceipt?,
        carriedKeys: Set<UltraEglIngressKey.Local>,
    ): UltraEglLocalFirstFrameExpectation? = null
    fun tryPresentLocalFirstFrame(
        expectation: UltraEglLocalFirstFrameExpectation,
        postSequences: Map<UltraEglIngressKey.Local, Long>,
    ): UltraEglLocalFirstFrameReceipt? = null
    fun subscribeClusterReplay(
        instrument: AndroidUltraBackendInstrument,
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
    ): UltraEglClusterReplaySubscription = throw UnsupportedOperationException(
        "Backend has no exact CLUSTER replay subscription",
    )
    fun subscribeClusterReplay(
        instrument: AndroidUltraBackendInstrument,
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
        onSuperseded: (UltraEglClusterReplayReceipt) -> Unit,
    ): UltraEglClusterReplaySubscription =
        subscribeClusterReplay(instrument, onReceipt, onRevoked)
    fun phase(): UltraEglBackendPhase
    /** Optional read-only decoder/EGL correlation; fake ports grant no frame identity. */
    fun acceptVideoFrameReleaseReceipt(
        receipt: AndroidVideoFrameReleaseReceipt,
    ): RemoteFrameIdentityDisposition = RemoteFrameIdentityDisposition.UNMATCHED
    fun drainMatchedVideoFrames(): List<MatchedRemoteFrameConsumerReceipt> = emptyList()
    fun retire(timeoutMillis: Long): UltraEglBackendRetirementResult
}

internal class AndroidUltraBackendIngress(
    val key: UltraEglIngressKey,
    val producerSurface: Surface,
    private val retirement: (Long) -> AndroidVideoSurfaceRetirementResult?,
) {
    fun retire(timeoutMillis: Long): AndroidVideoSurfaceRetirementResult? =
        retirement(timeoutMillis)
}

internal class AndroidUltraBackendInstrument(
    val key: UltraEglIngressKey.Instrument,
    val sizePixels: PixelSize,
    private val replace: (IntArray) -> UltraEglInstrumentUploadReceipt,
    private val retirement: (Long) -> Unit,
    private val replaceOwned: (OwnedOpaqueArgbFrame) -> UltraEglInstrumentUploadReceipt =
        { frame -> frame.take()?.let(replace)
            ?: UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT },
    val generation: Long = 0L,
) {
    fun replaceCompleteOpaqueFrame(pixels: IntArray): UltraEglInstrumentUploadReceipt {
        if (pixels.size.toLong() != sizePixels.widthPixels.toLong() *
            sizePixels.heightPixels) return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
        return OwnedOpaqueArgbFrame.capture(pixels)?.let(replaceOwned)
            ?: UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
    }
    fun replaceOwnedCompleteOpaqueFrame(frame: OwnedOpaqueArgbFrame):
        UltraEglInstrumentUploadReceipt = replaceOwned(frame)
    fun retire(timeoutMillis: Long) = retirement(timeoutMillis)
}

internal class AndroidUltraBackendOutput(
    val destination: DisplayDestination,
    private val retirement: () -> Unit,
) {
    fun retire() = retirement()
}

private class AndroidUltraEglBackendPort(
    private val backend: AndroidUltraEglBackend,
    private val acquireDecoderSurface: AndroidVideoSurfaceLeaseAcquirer,
) : AndroidUltraRendererBackendPort {
    override fun openRemoteIngress(
        ingress: UltraDisplayGraphIngress,
    ): AndroidUltraBackendIngress {
        val lease = backend.openRemoteIngress(ingress, acquireDecoderSurface)
        return AndroidUltraBackendIngress(lease.key, lease.producerSurface, lease::retire)
    }

    override fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraBackendIngress {
        val lease = backend.openLocalIngress(contentId, sizePixels)
        return AndroidUltraBackendIngress(lease.key, lease.producerSurface, lease::retire)
    }

    override fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraBackendIngress {
        val lease = backend.openOverlayIngress(contentId, sizePixels)
        return AndroidUltraBackendIngress(lease.key, lease.producerSurface, lease::retire)
    }

    override fun openInstrumentRaster(
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): AndroidUltraBackendInstrument {
        val lease: UltraEglInstrumentRasterLease = backend.openInstrumentRaster(
            destination, sizePixels)
        return AndroidUltraBackendInstrument(lease.key, lease.sizePixels,
            lease::replaceCompleteOpaqueFrame, { lease.close() },
            lease::replaceOwnedCompleteOpaqueFrame, lease.generation)
    }

    override fun attachOutput(
        destination: DisplayDestination,
        output: UltraDisplayGraphOutput,
        target: AndroidDisplaySurfaceTarget,
    ): AndroidUltraBackendOutput {
        val lease = backend.attachOutput(destination, output, target)
        return AndroidUltraBackendOutput(lease.destination, lease::close)
    }

    override fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission =
        backend.resolve(plan)

    override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket =
        backend.submit(resolved)

    override fun prepareLocalFirstFrame(
        revision: Long,
        outputBatchGeneration: Long,
        localKeys: Set<UltraEglIngressKey.Local>,
        carriedFrom: UltraEglLocalFirstFrameReceipt?,
        carriedKeys: Set<UltraEglIngressKey.Local>,
    ): UltraEglLocalFirstFrameExpectation? = backend.prepareLocalFirstFrame(
        revision, outputBatchGeneration, localKeys, carriedFrom, carriedKeys,
    )

    override fun tryPresentLocalFirstFrame(
        expectation: UltraEglLocalFirstFrameExpectation,
        postSequences: Map<UltraEglIngressKey.Local, Long>,
    ): UltraEglLocalFirstFrameReceipt? = backend.tryPresentLocalFirstFrame(
        expectation, postSequences,
    )

    override fun subscribeClusterReplay(
        instrument: AndroidUltraBackendInstrument,
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
    ): UltraEglClusterReplaySubscription = backend.subscribeClusterReplay(
        instrument.key.destination, instrument.generation, onReceipt, onRevoked, {},
    )

    override fun subscribeClusterReplay(
        instrument: AndroidUltraBackendInstrument,
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
        onSuperseded: (UltraEglClusterReplayReceipt) -> Unit,
    ): UltraEglClusterReplaySubscription = backend.subscribeClusterReplay(
        instrument.key.destination, instrument.generation, onReceipt, onRevoked, onSuperseded,
    )

    override fun phase(): UltraEglBackendPhase = backend.diagnostics().phase

    override fun acceptVideoFrameReleaseReceipt(
        receipt: AndroidVideoFrameReleaseReceipt,
    ): RemoteFrameIdentityDisposition = backend.acceptVideoFrameReleaseReceipt(receipt)

    override fun drainMatchedVideoFrames(): List<MatchedRemoteFrameConsumerReceipt> =
        backend.drainMatchedVideoFrames()

    override fun retire(timeoutMillis: Long): UltraEglBackendRetirementResult =
        backend.retire(timeoutMillis)
}

/** Type-111 may enter EGL only through the transferred exact external route, never ordinary sink routing. */
internal fun physicalUltraDecoderSurfaceAcquirer(
    ordinary: AndroidVideoSurfaceLeaseAcquirer,
    transferredExternal: AndroidVideoSurfaceLeaseAcquirer,
): AndroidVideoSurfaceLeaseAcquirer = AndroidVideoSurfaceLeaseAcquirer { handle, surface ->
    if (handle.type == 111) transferredExternal.acquire(handle, surface)
    else ordinary.acquire(handle, surface)
}

/**
 * Resource-level compositor child for exactly one AirPlay control-session generation.
 *
 * It intentionally does not implement or register `UiSyncRendererCapability`: presentation data is
 * only exposed to an upper semantic/theme compiler through [subscribePresentation], and no UI Sync
 * event is guessed into a scene here.
 */
class AndroidUltraRendererSessionCoordinator private constructor(
    private val context: UiSyncRendererSessionBindingContext,
    private val backend: AndroidUltraRendererBackendPort,
    private val batchBinderFactory: ((UltraDisplayGraphSnapshot) -> AndroidUltraBatchBinderResource)?,
) : UiSyncRendererSessionLease {
    private data class RemoteRecord(
        val generation: Long,
        val ingress: UltraDisplayGraphIngress,
        var phase: AndroidUltraRendererSessionPhase,
        var backendIngress: AndroidUltraBackendIngress? = null,
        var published: Boolean = false,
        var closeRequested: Boolean = false,
        var operationInFlight: Boolean = true,
    )

    internal data class OwnedIngressRecord(
        val generation: Long,
        val key: UltraEglIngressKey,
        val sizePixels: PixelSize,
        var phase: AndroidUltraRendererSessionPhase,
        var backendIngress: AndroidUltraBackendIngress? = null,
        var backendInstrument: AndroidUltraBackendInstrument? = null,
        var instrumentFrameUploaded: Boolean = false,
        var instrumentUploadSequence: Long = 0L,
        var clusterReplaySubscription: AndroidUltraClusterReplaySubscription? = null,
        var replayRegistrationInFlight: Boolean = false,
        var closeRequested: Boolean = false,
        var operationInFlight: Boolean = true,
    )

    internal data class OutputRecord(
        val generation: Long,
        val destination: DisplayDestination,
        val output: UltraDisplayGraphOutput,
        val target: AndroidDisplaySurfaceTarget,
        var phase: AndroidUltraRendererSessionPhase,
        var backendOutput: AndroidUltraBackendOutput? = null,
        var closeRequested: Boolean = false,
        var operationInFlight: Boolean = true,
    )

    internal data class BatchOutputRecord(
        val generation: Long,
        val requests: List<AndroidUltraOutputAttachmentRequest>,
        val lease: UltraDisplayBindingBridgeLease,
        var phase: AndroidUltraRendererSessionPhase,
        var closeRequested: Boolean = false,
    )

    private data class BatchResources(
        val staticGraph: UltraDisplayGraphSnapshot,
        val bridge: UltraDisplayBindingBatchBridge,
        val binder: Closeable,
        var bridgeRetired: Boolean = false,
        var binderClosed: Boolean = false,
    )

    internal data class SubscriptionRecord(
        val generation: Long,
        val displayId: String,
        var delegate: UiSyncPresentationSubscription? = null,
        var phase: AndroidUltraRendererSessionPhase,
        var closeRequested: Boolean = false,
        var operationInFlight: Boolean = true,
        var activeCallbacks: Int = 0,
        var delegateDetached: Boolean = false,
    )

    private data class CloseWork(
        val subscriptions: List<SubscriptionRecord>,
        val hadOutstandingOperations: Boolean,
    )

    private data class RetirementOutcome(
        val completed: Boolean,
        val result: AndroidVideoSurfaceRetirementResult?,
    )

    private val lock = Object()
    private val graphRegistry = UltraDisplayGraphRegistry()
    private val exactStaticOutputs = Collections.newSetFromMap(
        IdentityHashMap<UltraDisplayGraphOutput, Boolean>(),
    ).apply { addAll(context.staticPlan.outputs) }
    private val remotesById = LinkedHashMap<UltraIngressId, RemoteRecord>()
    private val remotesByHandle = IdentityHashMap<AirPlayVideoStreamHandle, RemoteRecord>()
    private val ownedIngresses = LinkedHashMap<UltraEglIngressKey, OwnedIngressRecord>()
    private val outputs = LinkedHashMap<DisplayDestination, OutputRecord>()
    private val outputIds = LinkedHashMap<UltraDisplayOutputId, OutputRecord>()
    private var outputBatch: BatchOutputRecord? = null
    private var batchResources: BatchResources? = null
    private var batchOperationInFlight = false
    private val batchLivenessProbeInThread = ThreadLocal<Boolean>()
    private var outputTopologyMode = OutputTopologyMode.NONE
    private val subscriptions = IdentityHashMap<AndroidUltraPresentationSubscription, SubscriptionRecord>()
    private val subscriptionRecords = LinkedHashMap<Long, SubscriptionRecord>()
    private var generation = 0L
    private var operationCount = 0
    private var renderOperationGeneration: Long? = null
    private var renderOperationSceneRevision: Long? = null
    private var lastSubmittedRenderRevision = -1L
    private var closeRequested = false
    private var closeInFlight = false
    private var poisoned = false
    private var backendRetired = false
    private var closed = false
    private var lastFailure: Throwable? = null

    private enum class OutputTopologyMode { NONE, SEQUENTIAL, BATCH }

    /** Creates a fresh production EGL backend and binds it to this exact media sink instance. */
    constructor(
        context: UiSyncRendererSessionBindingContext,
        mediaSink: AndroidMediaSink,
        transferredExternalRoute: () -> AndroidMediaSink.PhysicalExternalLegacyRouteLease? = { null },
    ) : this(
        context,
        AndroidUltraEglBackend(),
        physicalUltraDecoderSurfaceAcquirer(
            AndroidVideoSurfaceLeaseAcquirer { handle, surface ->
                mediaSink.acquireSurfaceLease(handle, surface)
            },
            AndroidVideoSurfaceLeaseAcquirer { handle, surface ->
                transferredExternalRoute()?.acquireTransferredIngressSurfaceLease(handle, surface)
            },
        ),
    )

    /**
     * Explicit injection transfers exclusive control of [eglBackend] to this session. The caller
     * must not mutate, submit to, or close that backend after construction.
     */
    constructor(
        context: UiSyncRendererSessionBindingContext,
        eglBackend: AndroidUltraEglBackend,
        acquireDecoderSurface: AndroidVideoSurfaceLeaseAcquirer,
    ) : this(
        context,
        AndroidUltraEglBackendPort(eglBackend, acquireDecoderSurface),
        { graph ->
            AndroidUltraEglBatchBinder(eglBackend, graph).let { binder ->
                AndroidUltraBatchBinderResource(binder, binder)
            }
        },
    )

    internal constructor(
        context: UiSyncRendererSessionBindingContext,
        backendPort: AndroidUltraRendererBackendPort,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
    ) : this(context, backendPort, null)

    internal constructor(
        context: UiSyncRendererSessionBindingContext,
        backendPort: AndroidUltraRendererBackendPort,
        batchBinderFactory: (UltraDisplayGraphSnapshot) -> AndroidUltraBatchBinderResource,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit,
    ) : this(context, backendPort, batchBinderFactory)

    override val schemaFingerprint: UiSyncSchemaFingerprint = context.schemaFingerprint
    override val bindingFingerprint: UiSyncBindingFingerprint = context.bindingFingerprint
    override val displayIds: Set<String> = context.displayIds
    override val implementationId: String = IMPLEMENTATION_ID

    override val isLive: Boolean
        get() {
            invalidateUnusableBatch()
            synchronized(lock) {
                if (closed || closeRequested || poisoned) return false
            }
            val backendPhase = runCatching { backend.phase() }.getOrElse { failure ->
                synchronized(lock) { poisonLocked(failure) }
                return false
            }
            if (backendPhase.isTerminalOrUncertain()) {
                synchronized(lock) {
                    poisonLocked(IllegalStateException("EGL backend is not usable: $backendPhase"))
                }
                return false
            }
            return synchronized(lock) { !closed && !closeRequested && !poisoned }
        }

    /**
     * Terminal semantic/compiler failure stops this exact session from reporting renderer
     * liveness before subscription teardown completes. Resources remain owned by the existing
     * retryable close path; a failed observer detach must never look READY in the meantime.
     */
    internal fun sealSemanticRendererFailure(cause: Throwable) {
        synchronized(lock) {
            if (!closed && !poisoned) {
                poisoned = true
                lastFailure = cause
            }
        }
    }

    fun graphSnapshot(): UltraDisplayGraphSnapshot {
        stateSnapshot()
        return synchronized(lock) {
            graphRegistry.snapshot()
        }
    }

    fun stateSnapshot(): AndroidUltraRendererSessionSnapshot {
        invalidateUnusableBatch()
        synchronized(lock) {
            if (closed || closeRequested || poisoned) return snapshotLocked()
        }
        runCatching { backend.phase() }.fold(
            onSuccess = { phase ->
                if (phase.isTerminalOrUncertain()) synchronized(lock) {
                    poisonLocked(IllegalStateException("EGL backend is not usable: $phase"))
                }
            },
            onFailure = { failure -> synchronized(lock) { poisonLocked(failure) } },
        )
        return synchronized(lock) { snapshotLocked() }
    }

    /**
     * The exact published decoder route may contribute optional per-frame identity evidence.
     * UNMATCHED has no effect on existing video presentation or UI Sync static scene rendering.
     */
    internal fun acceptVideoFrameReleaseReceipt(
        receipt: AndroidVideoFrameReleaseReceipt,
    ): RemoteFrameIdentityDisposition {
        val accepted = synchronized(lock) {
            val record = remotesByHandle[receipt.handle]
            acceptingLocked() && receipt.handle.type == 111 &&
                record != null && isCurrentLocked(record) &&
                record.ingress.stream === receipt.handle &&
                record.backendIngress?.producerSurface === receipt.surface &&
                record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                record.published && !record.closeRequested && !record.operationInFlight
        }
        if (!accepted) return RemoteFrameIdentityDisposition.UNMATCHED
        val result = backend.acceptVideoFrameReleaseReceipt(receipt)
        return synchronized(lock) {
            val record = remotesByHandle[receipt.handle]
            if (!acceptingLocked() || record == null || !isCurrentLocked(record) ||
                record.phase != AndroidUltraRendererSessionPhase.ACTIVE ||
                !record.published || record.closeRequested || record.operationInFlight ||
                record.backendIngress?.producerSurface !== receipt.surface
            ) RemoteFrameIdentityDisposition.UNMATCHED else result
        }
    }

    /** Consumer evidence remains read-only and is filtered against today's exact routes. */
    internal fun drainMatchedVideoFrames(): List<MatchedRemoteFrameConsumerReceipt> {
        val candidates = backend.drainMatchedVideoFrames()
        return synchronized(lock) {
            if (!acceptingLocked()) return@synchronized emptyList()
            candidates.filter { receipt ->
                val record = remotesByHandle[receipt.handle]
                record != null && isCurrentLocked(record) &&
                    record.ingress.id == receipt.key.ingressId &&
                    record.ingress.stream === receipt.handle &&
                    record.backendIngress?.producerSurface === receipt.surface &&
                    record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                    record.published && !record.closeRequested && !record.operationInFlight
            }
        }
    }

    /**
     * Opens the private OES producer and decoder Surface now, but publication into the V2 graph is
     * deferred until the SETUP response-flush commit invokes the returned candidate's publish().
     */
    override fun prepareScreen(
        context: UiSyncRendererScreenContext,
    ): UiSyncRendererScreenCandidate? {
        require(context.ingress.stream === context.stream) {
            "UI Sync renderer screen context changed the session-bound stream handle"
        }
        val expected = this.context.staticPlan.ingressFor(context.stream)
            ?: return null
        require(expected.id == context.ingressId) {
            "UI Sync renderer screen context changed the explicitly selected ingress ID"
        }
        ensureBackendUsable()

        val record = synchronized(lock) {
            checkTopologyMutableLocked()
            require(!remotesById.containsKey(context.ingressId)) {
                "UI Sync renderer ingress ID is already reserved: ${context.ingressId}"
            }
            require(!remotesByHandle.containsKey(context.stream)) {
                "This exact stream handle already has a UI Sync renderer candidate"
            }
            val created = RemoteRecord(
                generation = nextGenerationLocked(),
                ingress = context.ingress,
                phase = AndroidUltraRendererSessionPhase.PREPARING,
            )
            remotesById[context.ingressId] = created
            remotesByHandle[context.stream] = created
            beginOperationLocked()
            created
        }

        val opened = runCatching { backend.openRemoteIngress(context.ingress) }
        val resource = opened.getOrNull()
        val keyMatches = resource?.key == UltraEglIngressKey.Remote(context.ingressId)
        if (resource != null && !keyMatches) {
            val mismatch = IllegalStateException("EGL backend returned a different remote ingress key")
            synchronized(lock) {
                endOperationLocked()
                record.operationInFlight = false
                record.backendIngress = resource
                record.closeRequested = true
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(mismatch)
            }
            runCatching { retireRemote(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS) }
                .exceptionOrNull()
                ?.let(mismatch::addSuppressed)
            throw mismatch
        }

        if (resource == null) {
            val failure = opened.exceptionOrNull()
                ?: IllegalStateException("EGL backend did not return a remote ingress")
            failRemoteOpening(record, failure)
            throw failure
        }

        val accepted = synchronized(lock) {
            endOperationLocked()
            record.operationInFlight = false
            record.backendIngress = resource
            if (isCurrentLocked(record) && !record.closeRequested && acceptingLocked()) {
                record.phase = AndroidUltraRendererSessionPhase.ACTIVE
                true
            } else {
                record.closeRequested = true
                false
            }
        }
        if (!accepted) {
            val retirement = retireRemote(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS)
            check(retirement.completed) {
                "Remote ingress opened after session close and could not be retired: ${retirement.result}"
            }
            throw IllegalStateException("UI Sync renderer session closed during screen preparation")
        }
        return RemoteCandidate(this, record)
    }

    fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraCustomIngressLease =
        openOwnedIngress(UltraEglIngressKey.Local(contentId), sizePixels) {
            backend.openLocalIngress(contentId, sizePixels)
        }

    fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize): AndroidUltraCustomIngressLease =
        openOwnedIngress(UltraEglIngressKey.Overlay(contentId), sizePixels) {
            backend.openOverlayIngress(contentId, sizePixels)
        }

    /** Exact CLUSTER-owned GL texture, not an Android Surface/Canvas ingress. */
    internal fun openInstrumentRaster(
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): AndroidUltraInstrumentRasterLease {
        require(destination.role == DisplayDestinationRole.CLUSTER)
        ensureBackendUsable()
        val key = UltraEglIngressKey.Instrument(destination)
        val record = synchronized(lock) {
            checkTopologyMutableLocked()
            require(!ownedIngresses.containsKey(key)) { "Instrument raster is already reserved" }
            OwnedIngressRecord(nextGenerationLocked(), key, sizePixels,
                AndroidUltraRendererSessionPhase.PREPARING).also {
                ownedIngresses[key] = it
                beginOperationLocked()
            }
        }
        val opened = runCatching { backend.openInstrumentRaster(destination, sizePixels) }
        val resource = opened.getOrNull()
        if (resource == null || resource.key != key || resource.sizePixels != sizePixels) {
            val failure = opened.exceptionOrNull()
                ?: IllegalStateException("EGL backend returned a different instrument raster")
            synchronized(lock) {
                endOperationLocked()
                record.operationInFlight = false
                record.backendInstrument = resource
                record.closeRequested = true
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
            }
            if (resource != null) runCatching {
                retireOwnedIngress(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val accepted = synchronized(lock) {
            endOperationLocked()
            record.operationInFlight = false
            record.backendInstrument = resource
            if (ownedIngresses[key] === record && !record.closeRequested && acceptingLocked()) {
                record.phase = AndroidUltraRendererSessionPhase.ACTIVE
                true
            } else {
                record.closeRequested = true
                false
            }
        }
        if (!accepted) {
            retireOwnedIngress(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS)
            throw IllegalStateException("Renderer retired while opening instrument raster")
        }
        return AndroidUltraInstrumentRasterLease(this, record)
    }

    internal fun isCurrentInstrumentRaster(
        lease: AndroidUltraInstrumentRasterLease,
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): Boolean = instrumentRasterGenerationStatus(lease, destination, sizePixels) ==
        UiSyncPhysicalGenerationStatus.CURRENT

    internal fun instrumentRasterGenerationStatus(
        lease: AndroidUltraInstrumentRasterLease,
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): UiSyncPhysicalGenerationStatus = synchronized(lock) {
        val record = lease.recordFor(this)
            ?: return@synchronized UiSyncPhysicalGenerationStatus.REVOKED
        if (record.key != UltraEglIngressKey.Instrument(destination) ||
            record.sizePixels != sizePixels || ownedIngresses[record.key] !== record ||
            record.phase != AndroidUltraRendererSessionPhase.ACTIVE ||
            record.closeRequested || record.backendInstrument?.key != record.key ||
            !acceptingLocked()
        ) return@synchronized UiSyncPhysicalGenerationStatus.REVOKED
        if (record.operationInFlight || renderOperationGeneration != null ||
            batchOperationInFlight || topologyOperationInFlightLocked()
        ) UiSyncPhysicalGenerationStatus.BUSY else UiSyncPhysicalGenerationStatus.CURRENT
    }

    /** One exact instrument-lease observer, installed before its first scene submission. */
    internal fun subscribeClusterReplay(
        instrumentLease: AndroidUltraInstrumentRasterLease,
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
        onSuperseded: (UltraEglClusterReplayReceipt) -> Unit = {},
    ): AndroidUltraClusterReplaySubscription {
        val record = requireNotNull(instrumentLease.recordFor(this)) {
            "CLUSTER replay lease belongs to another renderer session"
        }
        val subscription = AndroidUltraClusterReplaySubscription(this, record,
            onReceipt, onRevoked, onSuperseded)
        val backendInstrument = synchronized(lock) {
            check(acceptingLocked() && ownedIngresses[record.key] === record &&
                record.key is UltraEglIngressKey.Instrument &&
                record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                record.instrumentFrameUploaded && !record.closeRequested &&
                record.clusterReplaySubscription == null) {
                "CLUSTER replay requires one live, initialized instrument ingress"
            }
            record.clusterReplaySubscription = subscription
            record.replayRegistrationInFlight = true
            beginOperationLocked()
            checkNotNull(record.backendInstrument)
        }
        val opened = runCatching {
            backend.subscribeClusterReplay(backendInstrument,
                { receipt -> deliverClusterReplay(subscription, receipt) },
                { revokeClusterReplay(subscription) },
                { receipt -> notifySupersededClusterReplay(subscription, receipt) })
        }
        val delegate = opened.getOrNull()
        val accepted = synchronized(lock) {
            record.replayRegistrationInFlight = false
            endOperationLocked()
            subscription.delegate = delegate
            if (delegate != null && !subscription.closeRequested && !subscription.revoked &&
                acceptingLocked() && ownedIngresses[record.key] === record &&
                record.clusterReplaySubscription === subscription && !record.closeRequested
            ) {
                true
            } else false
        }
        if (delegate != null && !accepted) {
            check(delegate.retire()) { "Stale CLUSTER replay observer could not be drained" }
            synchronized(lock) {
                if (record.clusterReplaySubscription === subscription) {
                    record.clusterReplaySubscription = null
                }
            }
        }
        if (delegate == null) {
            synchronized(lock) {
                if (record.clusterReplaySubscription === subscription) {
                    record.clusterReplaySubscription = null
                }
            }
            throw opened.exceptionOrNull()
                ?: IllegalStateException("Backend returned no CLUSTER replay subscription")
        }
        check(accepted) { "Renderer changed while installing CLUSTER replay observer" }
        return subscription
    }

    private fun deliverClusterReplay(
        subscription: AndroidUltraClusterReplaySubscription,
        receipt: UltraEglClusterReplayReceipt,
    ) {
        val decision = synchronized(lock) {
            val record = subscription.record
            val batch = outputBatch
            val sameGeneration = !subscription.closeRequested && !subscription.revoked &&
                subscription.delegate != null && acceptingLocked() &&
                ownedIngresses[record.key] === record &&
                record.clusterReplaySubscription === subscription &&
                record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                record.instrumentFrameUploaded && !record.closeRequested &&
                !batchOperationInFlight &&
                record.backendInstrument?.generation == receipt.instrumentIngressGeneration &&
                receipt.instrumentOnlyClusterScene && receipt.graphRevision != null &&
                batch?.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                !batch.closeRequested &&
                graphRegistry.snapshot()?.revision == receipt.graphRevision
            when {
                !sameGeneration -> ClusterReplayDecision(ClusterReplayDelivery.REVOKE)
                receipt.instrumentFrameSequence < record.instrumentUploadSequence ||
                    receipt.sceneRevision < lastSubmittedRenderRevision ||
                    (renderOperationSceneRevision != null &&
                        receipt.sceneRevision < checkNotNull(renderOperationSceneRevision)) ->
                    ClusterReplayDecision(ClusterReplayDelivery.SUPERSEDED)
                record.operationInFlight ||
                    (renderOperationSceneRevision == receipt.sceneRevision &&
                        lastSubmittedRenderRevision != receipt.sceneRevision) -> {
                    val prior = subscription.deferredReplay
                    if (prior == null || receipt.replaySequence > prior.replaySequence) {
                        subscription.deferredReplay = receipt
                        ClusterReplayDecision(ClusterReplayDelivery.DEFERRED, prior)
                    } else if (receipt.replaySequence < prior.replaySequence) {
                        ClusterReplayDecision(ClusterReplayDelivery.SUPERSEDED)
                    } else ClusterReplayDecision(ClusterReplayDelivery.DEFERRED)
                }
                renderOperationSceneRevision != null ||
                    receipt.instrumentFrameSequence != record.instrumentUploadSequence ||
                    receipt.sceneRevision != lastSubmittedRenderRevision ->
                    ClusterReplayDecision(ClusterReplayDelivery.REVOKE)
                else -> ClusterReplayDecision(ClusterReplayDelivery.DELIVER)
            }
        }
        decision.replacedDeferred?.let(subscription.onSuperseded)
        when (decision.disposition) {
            ClusterReplayDelivery.DELIVER -> subscription.onReceipt(receipt)
            ClusterReplayDelivery.SUPERSEDED -> subscription.onSuperseded(receipt)
            ClusterReplayDelivery.REVOKE -> revokeClusterReplay(subscription)
            ClusterReplayDelivery.DEFERRED -> Unit
        }
    }

    private enum class ClusterReplayDelivery { DELIVER, SUPERSEDED, REVOKE, DEFERRED }
    private data class ClusterReplayDecision(
        val disposition: ClusterReplayDelivery,
        val replacedDeferred: UltraEglClusterReplayReceipt? = null,
    )

    private fun requeueDeferredClusterReplay(subscription: AndroidUltraClusterReplaySubscription?) {
        if (subscription == null) return
        val pending = synchronized(lock) {
            subscription.deferredReplay.also { subscription.deferredReplay = null }
        } ?: return
        if (subscription.delegate?.requeue(pending) != true &&
            subscription.delegate?.revoke() != true
        ) revokeClusterReplay(subscription)
    }

    private fun notifySupersededClusterReplay(
        subscription: AndroidUltraClusterReplaySubscription,
        receipt: UltraEglClusterReplayReceipt,
    ) {
        val current = synchronized(lock) {
            !subscription.closeRequested && !subscription.revoked &&
                subscription.delegate != null && acceptingLocked() &&
                ownedIngresses[subscription.record.key] === subscription.record &&
                subscription.record.clusterReplaySubscription === subscription &&
                subscription.record.backendInstrument?.generation ==
                    receipt.instrumentIngressGeneration &&
                outputBatch?.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                outputBatch?.closeRequested == false
        }
        if (current) subscription.onSuperseded(receipt)
        else revokeClusterReplay(subscription)
    }

    private fun revokeClusterReplay(subscription: AndroidUltraClusterReplaySubscription) {
        val notify = synchronized(lock) {
            if (subscription.closeRequested || subscription.revoked) false
            else {
                subscription.revoked = true
                true
            }
        }
        if (notify) subscription.onRevoked()
    }

    internal fun closeClusterReplaySubscription(subscription: AndroidUltraClusterReplaySubscription) {
        val delegate = synchronized(lock) {
            subscription.closeRequested = true
            subscription.deferredReplay = null
            lock.notifyAll()
            check(!subscription.record.replayRegistrationInFlight) {
                "CLUSTER replay registration is still in flight; retry retirement"
            }
            subscription.delegate
        } ?: throw IllegalStateException("CLUSTER replay observer has no retirement delegate")
        check(delegate.retire()) { "CLUSTER replay observer callback is still in flight" }
        synchronized(lock) {
            if (subscription.record.clusterReplaySubscription === subscription) {
                subscription.record.clusterReplaySubscription = null
            }
        }
    }

    internal fun replaceInstrumentRaster(
        record: OwnedIngressRecord,
        argbPixels: IntArray,
    ): UltraEglInstrumentUploadReceipt {
        val pixelCount = record.sizePixels.widthPixels.toLong() * record.sizePixels.heightPixels
        if (pixelCount > 1_600_000L || argbPixels.size.toLong() != pixelCount) {
            return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
        }
        return OwnedOpaqueArgbFrame.capture(argbPixels)?.let {
            replaceInstrumentRaster(record, it)
        } ?: UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
    }

    internal fun replaceInstrumentRaster(
        record: OwnedIngressRecord,
        frame: OwnedOpaqueArgbFrame,
    ): UltraEglInstrumentUploadReceipt {
        val owned = frame.transfer() ?: return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
        val backendRaster = synchronized(lock) {
            if (ownedIngresses[record.key] !== record ||
                record.phase != AndroidUltraRendererSessionPhase.ACTIVE ||
                record.closeRequested || record.operationInFlight || !acceptingLocked()
            ) return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
            val pixelCount = record.sizePixels.widthPixels.toLong() *
                record.sizePixels.heightPixels
            if (pixelCount > 1_600_000L || !owned.isCompleteFor(pixelCount)
            ) return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
            record.operationInFlight = true
            beginOperationLocked()
            checkNotNull(record.backendInstrument)
        }
        val result = runCatching { backendRaster.replaceOwnedCompleteOpaqueFrame(owned) }
        val settled = synchronized(lock) {
            record.operationInFlight = false
            endOperationLocked()
            val failure = result.exceptionOrNull()
            if (failure != null) poisonLocked(failure)
            val receipt = result.getOrDefault(
                UltraEglInstrumentUploadReceipt.COMPLETION_UNCERTAIN)
            if (receipt == UltraEglInstrumentUploadReceipt.COMMITTED &&
                ownedIngresses[record.key] === record
            ) {
                check(record.instrumentUploadSequence < Long.MAX_VALUE) {
                    "Instrument upload sequence exhausted"
                }
                record.instrumentUploadSequence++
                record.instrumentFrameUploaded = true
            }
            lock.notifyAll()
            receipt to record.clusterReplaySubscription
        }
        requeueDeferredClusterReplay(settled.second)
        return settled.first
    }

    /** Rejects equal-key leases from another session or an already retired/reopened generation. */
    internal fun isCurrentLocalIngress(
        lease: AndroidUltraCustomIngressLease,
        contentId: UltraContentId,
        sizePixels: PixelSize,
    ): Boolean = synchronized(lock) {
        val record = lease.recordFor(this) ?: return@synchronized false
        record.key == UltraEglIngressKey.Local(contentId) &&
            record.sizePixels == sizePixels &&
            ownedIngresses[record.key] === record &&
            record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
            !record.closeRequested && !record.operationInFlight &&
            record.backendIngress?.key == record.key && acceptingLocked()
    }

    fun attachOutput(
        destination: DisplayDestination,
        output: UltraDisplayGraphOutput,
        target: AndroidDisplaySurfaceTarget,
    ): AndroidUltraOutputAttachment {
        require(exactStaticOutputs.contains(output)) {
            "Output is not the exact resource from this session's static plan"
        }
        ensureBackendUsable()
        val record = synchronized(lock) {
            checkTopologyMutableLocked()
            check(outputTopologyMode != OutputTopologyMode.BATCH) {
                "Sequential output attachment cannot mix with an atomic output batch"
            }
            require(!outputs.containsKey(destination)) {
                "An output is already reserved for $destination"
            }
            require(!outputIds.containsKey(output.outputId)) {
                "This exact output ID is already reserved"
            }
            val created = OutputRecord(
                generation = nextGenerationLocked(),
                destination = destination,
                output = output,
                target = target,
                phase = AndroidUltraRendererSessionPhase.PREPARING,
            )
            outputs[destination] = created
            outputIds[output.outputId] = created
            outputTopologyMode = OutputTopologyMode.SEQUENTIAL
            beginOperationLocked()
            created
        }

        val opened = runCatching { backend.attachOutput(destination, output, target) }
        val resource = opened.getOrNull()
        if (resource != null && resource.destination != destination) {
            val mismatch = IllegalStateException(
                "EGL backend returned an output receipt for another destination",
            )
            synchronized(lock) {
                endOperationLocked()
                record.operationInFlight = false
                record.backendOutput = resource
                record.closeRequested = true
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(mismatch)
            }
            val cleanupFailure = runCatching { retireOutput(record) }.exceptionOrNull()
            cleanupFailure?.let(mismatch::addSuppressed)
            if (cleanupFailure != null && isOutputRetained(record)) {
                throw AndroidUltraOutputAttachmentFailure(this, record, mismatch)
            }
            throw mismatch
        }
        if (resource == null) {
            val cause = opened.exceptionOrNull()
                ?: IllegalStateException("EGL backend did not return an output lease")
            synchronized(lock) {
                endOperationLocked()
                record.operationInFlight = false
                record.closeRequested = true
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(cause)
            }
            throw AndroidUltraOutputAttachmentFailure(this, record, cause)
        }

        var graphFailure: Throwable? = null
        val accepted = synchronized(lock) {
            endOperationLocked()
            record.operationInFlight = false
            record.backendOutput = resource
            if (isCurrentLocked(record) && !record.closeRequested && acceptingLocked()) {
                record.phase = AndroidUltraRendererSessionPhase.ACTIVE
                try {
                    rebuildGraphLocked()
                    true
                } catch (failure: Throwable) {
                    record.closeRequested = true
                    record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                    poisonLocked(failure)
                    graphFailure = failure
                    false
                }
            } else {
                record.closeRequested = true
                false
            }
        }
        if (!accepted) {
            val retirementFailure = runCatching { retireOutput(record) }.exceptionOrNull()
            val failure = graphFailure
                ?: IllegalStateException("UI Sync renderer session closed while attaching output")
            retirementFailure?.let { problem ->
                if (problem !== failure) failure.addSuppressed(problem)
            }
            if (retirementFailure != null && isOutputRetained(record)) {
                throw AndroidUltraOutputAttachmentFailure(this, record, failure)
            }
            throw failure
        }
        return AndroidUltraOutputAttachment(this, record)
    }

    /**
     * Sequential by design: physical output swaps are not cross-display atomic. A failure leaves the
     * exact successful prefix active and reports it instead of pretending the old or new batch won.
     */
    fun attachOutputs(
        requests: Collection<AndroidUltraOutputAttachmentRequest>,
    ): AndroidUltraOutputAttachmentBatchResult {
        val frozen = boundedCopy(requests, MAXIMUM_OUTPUTS, "output attachment requests")
        require(frozen.map(AndroidUltraOutputAttachmentRequest::destination).toSet().size == frozen.size) {
            "Output attachment batch contains duplicate destinations"
        }
        val attached = ArrayList<AndroidUltraOutputAttachment>(frozen.size)
        frozen.forEach { request ->
            try {
                attached += attachOutput(request.destination, request.output, request.target)
            } catch (failure: Throwable) {
                return AndroidUltraOutputAttachmentBatchResult(
                    attached = Collections.unmodifiableList(attached),
                    failedRequest = request,
                    failure = failure,
                    state = stateSnapshot(),
                )
            }
        }
        return AndroidUltraOutputAttachmentBatchResult(
            attached = Collections.unmodifiableList(attached),
            failedRequest = null,
            failure = null,
            state = stateSnapshot(),
        )
    }

    /**
     * Replaces all static-plan outputs through one EGL/router logical publication. No renderer
     * READY receipt or physical cross-display frame-sync claim follows from this operation.
     * The returned lease may be closed after a later replacement without detaching that newer
     * generation. A clean failure preserves the previous graph and installed route generation.
     */
    fun replaceOutputBatch(
        requests: Collection<AndroidUltraOutputAttachmentRequest>,
        beforeCommit: () -> Unit = {},
    ): AndroidUltraOutputBatchLease {
        val frozen = validateOutputBatchRequests(requests)
        ensureBackendUsable()
        synchronized(lock) {
            if (outputBatch != null) ownedIngresses.values.mapNotNull {
                it.clusterReplaySubscription
            } else emptyList()
        }.forEach { it.close() }
        val previous = synchronized(lock) {
            checkTopologyMutableLocked()
            check(outputTopologyMode != OutputTopologyMode.SEQUENTIAL) {
                "Atomic output batch cannot mix with sequential output attachments"
            }
            check(!topologyOperationInFlightLocked()) {
                "Renderer topology operation is already in flight"
            }
            outputTopologyMode = OutputTopologyMode.BATCH
            batchOperationInFlight = true
            beginOperationLocked()
            outputBatch
        }

        try {
            frozen.forEach { request ->
                require(request.target.isValid) { "Output batch target is not valid" }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                batchOperationInFlight = false
                endOperationLocked()
            }
            throw failure
        }

        val resources = try {
            batchResources ?: createBatchResources().also { created ->
                synchronized(lock) { batchResources = created }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                batchOperationInFlight = false
                endOperationLocked()
                poisonLocked(failure)
            }
            throw failure
        }
        val canBegin = synchronized(lock) { acceptingLocked() }
        if (!canBegin) {
            synchronized(lock) {
                batchOperationInFlight = false
                endOperationLocked()
            }
            throw IllegalStateException("Renderer session closed during output batch preparation")
        }
        val destinationByOutput = frozen.associate { it.output.outputId to it.destination }
        val targetByOutput = frozen.associate { it.output.outputId to it.target }
        val opened = runCatching {
            resources.bridge.replaceOutputs(
                resources.staticGraph,
                destinationByOutput,
                targetByOutput,
                beforeCommit,
            )
        }
        val bridgeLease = opened.getOrNull()
        if (bridgeLease == null) {
            val failure = opened.exceptionOrNull()
                ?: IllegalStateException("Output batch bridge returned no lease")
            val bridgeState = runCatching { resources.bridge.snapshot() }.getOrNull()
            val oldGeneration = previous?.lease?.generation?.value
            val clean = bridgeState != null && !bridgeState.closed &&
                bridgeState.pendingPreparationCleanupCount == 0 &&
                bridgeState.retiredInstallationCount == 0 &&
                bridgeState.generation == oldGeneration &&
                (previous == null || previous.lease.isCurrent)
            synchronized(lock) {
                batchOperationInFlight = false
                endOperationLocked()
                if (!clean) {
                    outputBatch?.apply {
                        closeRequested = true
                        phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                    }
                    poisonLocked(failure)
                    rebuildGraphLocked()
                }
            }
            if (!clean) throw AndroidUltraOutputBatchCommitFailure(this, frozen, failure)
            throw failure
        }

        val bridgeState = runCatching { resources.bridge.snapshot() }
        val postCommitFailure = runCatching {
            val state = bridgeState.getOrThrow()
            check(state.backendCurrent && bridgeLease.isCurrent &&
                state.generation == bridgeLease.generation.value &&
                state.graphRevision == resources.staticGraph.revision
            ) {
                "Output batch route generation changed after commit"
            }
            require(state.routes.size == frozen.size) { "Output batch route coverage changed after commit" }
            val actual = state.routes.associateBy(UltraDisplayBridgeRouteSnapshot::outputId)
            frozen.forEach { request ->
                val route = actual[request.output.outputId]
                require(route != null &&
                    route.topologyDisplayId == request.output.topologyDisplayId &&
                    route.uiSyncDisplayId == request.output.uiSyncDisplayId &&
                    route.destination == request.destination &&
                    route.outputDescriptor == request.target.descriptor) {
                    "Output batch route changed its exact physical assignment"
                }
                require(request.target.isValid) {
                    "Output target became invalid while the batch was committing"
                }
            }
            synchronized(lock) {
                check(acceptingLocked()) { "Renderer session closed while output batch committed" }
                check(batchOperationInFlight) { "Output batch operation token was lost" }
                val committed = BatchOutputRecord(
                    generation = nextGenerationLocked(),
                    requests = frozen,
                    lease = bridgeLease,
                    phase = AndroidUltraRendererSessionPhase.ACTIVE,
                )
                outputBatch = committed
                rebuildGraphLocked()
                committed
            }
        }
        synchronized(lock) {
            batchOperationInFlight = false
            endOperationLocked()
        }
        val committed = postCommitFailure.getOrNull()
        if (committed != null) return AndroidUltraOutputBatchLease(this, committed)

        val failure = postCommitFailure.exceptionOrNull()
            ?: IllegalStateException("Output batch publication failed")
        synchronized(lock) {
            outputBatch?.apply {
                closeRequested = true
                phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
            }
            poisonLocked(failure)
            runCatching { rebuildGraphLocked() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        val retirementFailure = runCatching { bridgeLease.close() }.exceptionOrNull()
        retirementFailure?.let(failure::addSuppressed)
        val afterRetirement = runCatching { resources.bridge.snapshot() }.getOrNull()
        if (retirementFailure != null || afterRetirement == null ||
            afterRetirement.closed || afterRetirement.generation != null ||
            afterRetirement.pendingPreparationCleanupCount != 0 ||
            afterRetirement.retiredInstallationCount != 0
        ) {
            throw AndroidUltraOutputBatchCommitFailure(this, frozen, failure)
        }
        throw failure
    }

    private fun validateOutputBatchRequests(
        requests: Collection<AndroidUltraOutputAttachmentRequest>,
    ): List<AndroidUltraOutputAttachmentRequest> {
        val frozen = boundedCopy(requests, MAXIMUM_OUTPUTS, "output batch requests")
        require(frozen.isNotEmpty()) { "Output batch must contain at least one static-plan output" }
        require(frozen.size == context.staticPlan.outputs.size) {
            "Output batch must cover every static-plan output"
        }
        require(frozen.map { it.output.outputId }.toSet() ==
            context.staticPlan.outputs.map { it.outputId }.toSet()
        ) { "Output batch changed the exact static-plan output IDs" }
        require(frozen.map { it.destination }.toSet().size == frozen.size) {
            "Output batch contains duplicate physical destinations"
        }
        val targetIdentities = IdentityHashMap<AndroidDisplaySurfaceTarget, Unit>()
        val ownershipIdentities = IdentityHashMap<DisplaySurfaceOwnership, Unit>()
        val surfaceIdentities = IdentityHashMap<Surface, Unit>()
        frozen.forEach { request ->
            require(exactStaticOutputs.contains(request.output)) {
                "Output batch must use the exact object from this session's static plan"
            }
            require(targetIdentities.put(request.target, Unit) == null) {
                "Output batch reuses one target for multiple outputs"
            }
            require(ownershipIdentities.put(request.target.ownership, Unit) == null) {
                "Output batch reuses one physical ownership guard"
            }
            require(surfaceIdentities.put(request.target.surface, Unit) == null) {
                "Output batch reuses one bottom Surface"
            }
            // Read the immutable typed descriptor before any target can pass into EGL.
            request.target.descriptor
        }
        return Collections.unmodifiableList(ArrayList(frozen))
    }

    private fun createBatchResources(): BatchResources {
        val factory = checkNotNull(batchBinderFactory) {
            "This renderer backend does not support atomic output batches"
        }
        val staticGraph = UltraDisplayGraphRegistry().replace(
            context.staticPlan.outputs,
            emptyList(),
        )
        val binder = factory(staticGraph)
        return BatchResources(
            staticGraph,
            UltraDisplayBindingBatchBridge(binder.binder),
            binder.closer,
        )
    }

    internal fun closeOutputBatch(record: BatchOutputRecord) {
        synchronized(lock) {
            if (outputBatch === record) ownedIngresses.values.mapNotNull {
                it.clusterReplaySubscription
            } else emptyList()
        }.forEach { it.close() }
        val current = synchronized(lock) {
            if (record.phase == AndroidUltraRendererSessionPhase.CLOSED) return
            check(!closeInFlight && !batchOperationInFlight) {
                "Renderer output batch retirement is already in flight"
            }
            check(renderOperationGeneration == null) {
                "Output batch retirement raced a render submission; retry close"
            }
            val isCurrent = outputBatch === record
            if (isCurrent) {
                record.closeRequested = true
                rebuildGraphLocked()
            }
            batchOperationInFlight = true
            beginOperationLocked()
            isCurrent
        }
        val result = runCatching { record.lease.close() }
        synchronized(lock) {
            batchOperationInFlight = false
            endOperationLocked()
            if (result.isSuccess) {
                record.phase = AndroidUltraRendererSessionPhase.CLOSED
                if (current && outputBatch === record) outputBatch = null
            } else {
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(checkNotNull(result.exceptionOrNull()))
            }
        }
        result.getOrThrow()
    }

    internal fun isBatchRetired(): Boolean = synchronized(lock) { backendRetired }

    internal fun isOutputBatchCurrent(record: BatchOutputRecord): Boolean {
        stateSnapshot()
        val accepted = synchronized(lock) {
            outputBatch === record && record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                !record.closeRequested && acceptingLocked()
        }
        return accepted && record.lease.isCurrent
    }

    /**
     * Subscribes an upper semantic/theme compiler without interpreting presentation payloads here.
     * The exact subscription is session-owned and retried during close if its close callback fails.
     */
    fun subscribePresentation(
        displayId: String,
        observer: UltraPresentationObserver,
    ): AndroidUltraPresentationSubscription {
        require(displayId in displayIds) { "Presentation display ID is outside this session" }
        ensureBackendUsable()
        val record = synchronized(lock) {
            checkAcceptingLocked()
            require(subscriptionRecords.size < MAXIMUM_PRESENTATION_SUBSCRIPTIONS) {
                "Renderer session has too many presentation subscriptions"
            }
            val created = SubscriptionRecord(
                generation = nextGenerationLocked(),
                displayId = displayId,
                phase = AndroidUltraRendererSessionPhase.PREPARING,
            )
            subscriptionRecords[created.generation] = created
            beginOperationLocked()
            created
        }
        val opened = runCatching {
            context.presentationSource.subscribeWithReplay(
                displayId,
                UltraPresentationObserver { snapshot ->
                    deliverPresentation(record, observer, snapshot)
                },
            )
        }
        val delegate = opened.getOrNull()
        if (delegate == null) {
            val failure = opened.exceptionOrNull()
                ?: IllegalStateException("Presentation source did not return a subscription")
            synchronized(lock) {
                endOperationLocked()
                record.operationInFlight = false
                record.closeRequested = true
                // No delegate crossed the source boundary. The source remains responsible for
                // rolling back any internal registration before throwing; there is no external
                // resource for this coordinator to retain or retry. An already-admitted callback
                // remains an operation barrier and removes this receipt from its finally block.
                if (record.activeCallbacks == 0 && subscriptionRecords[record.generation] === record) {
                    subscriptionRecords.remove(record.generation)
                }
            }
            throw failure
        }

        lateinit var lease: AndroidUltraPresentationSubscription
        val accepted = synchronized(lock) {
            endOperationLocked()
            record.operationInFlight = false
            record.delegate = delegate
            if (acceptingLocked() && !record.closeRequested) {
                record.phase = AndroidUltraRendererSessionPhase.ACTIVE
                lease = AndroidUltraPresentationSubscription(
                    owner = this,
                    record = record,
                    initialSnapshot = delegate.initialSnapshot,
                )
                subscriptions[lease] = record
                true
            } else {
                record.closeRequested = true
                false
            }
        }
        if (!accepted) {
            val closedDelegate = runCatching { delegate.close() }
            synchronized(lock) {
                if (closedDelegate.isSuccess) {
                    if (subscriptionRecords[record.generation] === record) {
                        subscriptionRecords.remove(record.generation)
                    }
                } else {
                    val failure = checkNotNull(closedDelegate.exceptionOrNull())
                    record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                    poisonLocked(failure)
                }
            }
            closedDelegate.exceptionOrNull()?.let { throw it }
            throw IllegalStateException("UI Sync renderer session closed during subscription")
        }
        return lease
    }

    /** Pass-through snapshot for a compiler; no event or geometry is interpreted in this class. */
    fun presentationSnapshot(displayId: String): UltraPresentationSnapshot {
        require(displayId in displayIds) { "Presentation display ID is outside this session" }
        ensureBackendUsable()
        synchronized(lock) {
            checkAcceptingLocked()
            beginOperationLocked()
        }
        val result = runCatching { context.presentationSource.snapshot(displayId) }
        return synchronized(lock) {
            endOperationLocked()
            result.exceptionOrNull()?.let { failure ->
                poisonLocked(failure)
                throw failure
            }
            check(acceptingLocked()) {
                "Renderer session changed while reading presentation state"
            }
            checkNotNull(result.getOrNull())
        }
    }

    /**
     * Resolves once against the current immutable graph, then binds that exact plan to current EGL
     * generations. A graph change, stale resource, duplicate revision, or close race is rejected.
     */
    fun resolveAndSubmit(
        snapshot: UltraMultiOutputRenderSnapshot,
        outputByDestination: Map<DisplayDestination, UltraDisplayOutputId>,
    ): UltraEglRenderTicket = resolveAndSubmitWithLocalIngresses(
        snapshot, outputByDestination, emptyList(),
    )

    internal fun resolveAndSubmitWithLocalIngresses(
        snapshot: UltraMultiOutputRenderSnapshot,
        outputByDestination: Map<DisplayDestination, UltraDisplayOutputId>,
        localIngressExpectations: Collection<AndroidUltraLocalIngressExpectation>,
    ): UltraEglRenderTicket {
        ensureBackendUsable()
        val frozenMapping = boundedMapCopy(
            outputByDestination,
            MAXIMUM_OUTPUTS,
            "destination to output mapping",
        )
        val localGuards = ArrayList<AndroidUltraLocalIngressExpectation>()
        localIngressExpectations.forEach { guard ->
            require(localGuards.size < MAXIMUM_LOCAL_INGRESS_EXPECTATIONS) {
                "Too many local ingress expectations"
            }
            localGuards += guard
        }
        require(localGuards.map { it.contentId }.toSet().size == localGuards.size) {
            "Local ingress submission expectations must be unique"
        }
        val sceneLocalIds = snapshot.scenes.flatMap { scene -> scene.layers.mapNotNull {
            (it.source as? UltraSceneSource.Local)?.contentId
        } }.toSet()
        require(localGuards.all { it.contentId in sceneLocalIds }) {
            "Local ingress expectation is not present in the submitted scene"
        }
        val instrumentDestinations = snapshot.scenes.flatMap { scene ->
            scene.layers.mapNotNull { layer ->
                (layer.source as? UltraSceneSource.Instrument)?.destination
            }
        }
        val graph = synchronized(lock) {
            checkAcceptingLocked()
            require(renderOperationGeneration == null) { "A render submission is already in flight" }
            require(!topologyOperationInFlightLocked()) {
                "A renderer topology operation is already in flight"
            }
            require(snapshot.revision > lastSubmittedRenderRevision) {
                "Render revision ${snapshot.revision} is not newer than $lastSubmittedRenderRevision"
            }
            graphRegistry.snapshot()
        }
        if (snapshot.scenes.any { scene ->
            (scene.background as? UltraSceneBackground.OpaqueSrgb)?.graphSnapshot?.let {
                it !== graph
            } == true
        }) {
            throw StaleThemeGraphBeforeSubmissionException()
        }
        val plan = snapshot.resolveAgainst(graph, frozenMapping)
        val operationGeneration = synchronized(lock) {
            checkAcceptingLocked()
            require(renderOperationGeneration == null) { "A render submission is already in flight" }
            require(!topologyOperationInFlightLocked()) {
                "A renderer topology operation started while resolving the scene"
            }
            if (graphRegistry.snapshot() !== graph) {
                throw StaleThemeGraphBeforeSubmissionException()
            }
            require(localGuards.all { guard ->
                isCurrentLocalIngress(guard.lease, guard.contentId, guard.sizePixels)
            }) { "Local ingress generation changed before the render queue handoff" }
            require(instrumentDestinations.all { destination ->
                val scene = snapshot.scene(destination) ?: return@all false
                val key = UltraEglIngressKey.Instrument(destination)
                val record = ownedIngresses[key]
                record?.instrumentFrameUploaded == true &&
                    record.sizePixels == scene.viewportPixels &&
                    record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                    !record.closeRequested && !record.operationInFlight
            }) { "Instrument scene has no complete current opaque raster frame" }
            require(snapshot.revision > lastSubmittedRenderRevision) {
                "Render revision became stale before submission"
            }
            nextGenerationLocked().also {
                renderOperationGeneration = it
                renderOperationSceneRevision = snapshot.revision
                beginOperationLocked()
            }
        }

        val result = runCatching {
            val resolved = backend.resolve(plan)
            require(resolved.graphRevision == graph.revision) {
                "EGL backend changed the resolved graph revision"
            }
            backend.submit(resolved)
        }
        val completion = runCatching { synchronized(lock) {
            check(renderOperationGeneration == operationGeneration) {
                "Render operation generation was replaced"
            }
            renderOperationGeneration = null
            renderOperationSceneRevision = null
            endOperationLocked()
            lock.notifyAll()
            if (resourcesNeedGraphRemovalLocked()) {
                try {
                    rebuildGraphLocked()
                } catch (graphFailure: Throwable) {
                    poisonLocked(graphFailure)
                    throw graphFailure
                }
            }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                poisonLocked(failure)
                throw failure
            }
            val ticket = checkNotNull(result.getOrNull())
            lastSubmittedRenderRevision = snapshot.revision
            if (!acceptingLocked() || graphRegistry.snapshot() !== graph) {
                val changed = IllegalStateException(
                    "Renderer session changed while submitting the scene",
                )
                poisonLocked(changed)
                throw changed
            }
            ticket
        } }
        val replayObservers = synchronized(lock) {
            ownedIngresses.values.mapNotNull { it.clusterReplaySubscription }
        }
        if (completion.isSuccess) replayObservers.forEach(::requeueDeferredClusterReplay)
        else replayObservers.forEach { subscription ->
            synchronized(lock) { subscription.deferredReplay = null }
            if (subscription.delegate?.revoke() != true) revokeClusterReplay(subscription)
        }
        return completion.getOrThrow()
    }

    /**
     * Snapshot a local-frame callback baseline after scene submission and before Canvas posting.
     * This does not wait for, or claim, an Android Surface frame.
     */
    internal fun prepareLocalFirstFrameFence(
        revision: Long,
        graph: UltraDisplayGraphSnapshot,
        localGuards: Collection<AndroidUltraLocalIngressExpectation>,
        carriedFrom: UltraEglLocalFirstFrameReceipt? = null,
        carriedKeys: Set<UltraEglIngressKey.Local> = emptySet(),
    ): UltraEglLocalFirstFrameExpectation? {
        val guards = localGuards.toList()
        if (guards.isEmpty() || guards.map { it.contentId }.toSet().size != guards.size) return null
        val generation = synchronized(lock) {
            if (!acceptingLocked() || graphRegistry.snapshot() !== graph ||
                lastSubmittedRenderRevision != revision ||
                guards.any { !isCurrentLocalIngress(it.lease, it.contentId, it.sizePixels) }
            ) return@synchronized null
            outputBatch?.takeIf {
                it.phase == AndroidUltraRendererSessionPhase.ACTIVE && !it.closeRequested
            }?.lease?.generation?.value
        } ?: return null
        val keys = guards.mapTo(LinkedHashSet()) { UltraEglIngressKey.Local(it.contentId) }
        if (!keys.containsAll(carriedKeys) ||
            (carriedFrom == null) != carriedKeys.isEmpty() ||
            (carriedFrom != null && (carriedFrom.physicalBatchGeneration != generation ||
                carriedFrom.graphRevision != graph.revision))
        ) return null
        val prepared = backend.prepareLocalFirstFrame(
            revision, generation, keys, carriedFrom, carriedKeys,
        ) ?: return null
        return synchronized(lock) {
            if (acceptingLocked() && graphRegistry.snapshot() === graph &&
                lastSubmittedRenderRevision == revision &&
                outputBatch?.lease?.generation?.value == generation &&
                guards.all { isCurrentLocalIngress(it.lease, it.contentId, it.sizePixels) }
            ) prepared else null
        }
    }

    /** One bounded EGL control; stale graph, local ingress, or physical batch yields no receipt. */
    internal fun tryPresentLocalFirstFrame(
        prepared: UltraEglLocalFirstFrameExpectation,
        graph: UltraDisplayGraphSnapshot,
        localGuards: Collection<AndroidUltraLocalIngressExpectation>,
        postSequences: Map<UltraEglIngressKey.Local, Long>,
    ): UltraEglLocalFirstFrameReceipt? {
        val guards = localGuards.toList()
        val current = synchronized(lock) {
            acceptingLocked() && graphRegistry.snapshot() === graph &&
                prepared.submission.graphRevision == graph.revision &&
                lastSubmittedRenderRevision == prepared.submission.revision &&
                outputBatch?.takeIf {
                    it.phase == AndroidUltraRendererSessionPhase.ACTIVE && !it.closeRequested
                }?.lease?.generation?.value == prepared.physicalBatchGeneration &&
                guards.map { UltraEglIngressKey.Local(it.contentId) }.toSet() == postSequences.keys &&
                guards.all { isCurrentLocalIngress(it.lease, it.contentId, it.sizePixels) }
        }
        if (!current) return null
        val receipt = backend.tryPresentLocalFirstFrame(prepared, postSequences) ?: return null
        return synchronized(lock) {
            if (acceptingLocked() && graphRegistry.snapshot() === graph &&
                lastSubmittedRenderRevision == receipt.revision &&
                outputBatch?.lease?.generation?.value == receipt.physicalBatchGeneration &&
                guards.all { isCurrentLocalIngress(it.lease, it.contentId, it.sizePixels) }
            ) receipt else null
        }
    }

    /**
     * Performs one backend-wide, bounded proof for every screen candidate in this session. The EGL
     * backend necessarily retires local/overlay/output resources at the same time; candidate
     * wrappers may be consumed locally by the shared session only after this method returns true.
     * A false result retains the exact same records and backend instance for a later retry.
     */
    override fun retireScreens(): Boolean {
        val shouldRetire = synchronized(lock) {
            closeRequested = true
            if (closed || backendRetired) return true
            if (closeInFlight) return false
            closeInFlight = true
            remotesById.values.forEach { it.closeRequested = true }
            ownedIngresses.values.forEach { it.closeRequested = true }
            outputs.values.forEach { it.closeRequested = true }
            outputBatch?.closeRequested = true
            try {
                if (renderOperationGeneration == null) rebuildGraphLocked()
                if (operationCount == 0 && renderOperationGeneration == null) {
                    true
                } else {
                    closeInFlight = false
                    false
                }
            } catch (failure: Throwable) {
                closeInFlight = false
                poisonLocked(failure)
                false
            }
        }
        if (!shouldRetire) return false

        val replayDrained = synchronized(lock) {
            ownedIngresses.values.mapNotNull { it.clusterReplaySubscription }
        }.all { runCatching { it.close() }.isSuccess }
        if (!replayDrained) {
            synchronized(lock) { closeInFlight = false }
            return false
        }

        val batchFailure = retireBatchResourcesForClose()
        if (batchFailure != null) {
            synchronized(lock) {
                closeInFlight = false
                poisonLocked(batchFailure)
            }
            return false
        }

        val retirement = runCatching { backend.retire(BACKEND_RETIREMENT_TIMEOUT_MILLIS) }
        return synchronized(lock) {
            closeInFlight = false
            val result = retirement.getOrNull()
            if (result?.isRetired == true) {
                backendRetired = true
                remotesById.clear()
                remotesByHandle.clear()
                ownedIngresses.clear()
                outputs.clear()
                outputIds.clear()
                outputBatch = null
                true
            } else {
                val failure = retirement.exceptionOrNull()
                    ?: IllegalStateException("EGL backend retirement is not proven: $result")
                poisonLocked(failure)
                false
            }
        }
    }

    override fun close() {
        val work = synchronized(lock) {
            closeRequested = true
            if (closed) return
            check(!closeInFlight) { "Renderer session close is already in flight" }
            closeInFlight = true
            val hadOutstandingOperations = operationCount > 0
            val renderIsInFlight = renderOperationGeneration != null
            remotesById.values.forEach { it.closeRequested = true }
            ownedIngresses.values.forEach { it.closeRequested = true }
            outputs.values.forEach { it.closeRequested = true }
            outputBatch?.closeRequested = true
            subscriptionRecords.values.forEach { it.closeRequested = true }
            try {
                if (!renderIsInFlight) rebuildGraphLocked()
                CloseWork(
                    subscriptions = subscriptionRecords.values.filter {
                        reserveRetirementLocked(it)
                    },
                    hadOutstandingOperations = hadOutstandingOperations,
                )
            } catch (failure: Throwable) {
                closeInFlight = false
                poisonLocked(failure)
                throw failure
            }
        }

        var failure: Throwable? = if (work.hadOutstandingOperations) {
            IllegalStateException("Renderer session still has an operation in flight")
        } else {
            null
        }
        work.subscriptions.forEach { record ->
            val result = if (record.delegateDetached) {
                Result.success(Unit)
            } else {
                runCatching { checkNotNull(record.delegate).close() }
            }
            val completionFailure = finishSubscriptionRetirement(
                record,
                result.exceptionOrNull(),
            )
            failure = combineFailures(failure, completionFailure)
        }
        val replayFailure = synchronized(lock) {
            ownedIngresses.values.mapNotNull { it.clusterReplaySubscription }
        }.mapNotNull { runCatching { it.close() }.exceptionOrNull() }.firstOrNull()
        failure = combineFailures(failure, replayFailure)
        // The backend owns every candidate/input/output lease. Retiring it once under one deadline
        // proves all of those resources quiescent without multiplying a per-resource timeout or
        // racing an individual lease against whole-backend teardown.
        val batchFailure = if (synchronized(lock) { operationCount == 0 }) {
            retireBatchResourcesForClose()
        } else {
            null
        }
        failure = combineFailures(failure, batchFailure)
        batchFailure?.let { synchronized(lock) { poisonLocked(it) } }
        val mayRetireBackend = synchronized(lock) {
            replayFailure == null && operationCount == 0 &&
                batchResourcesReadyForBackendRetirementLocked()
        }
        val backendResult = if (mayRetireBackend && !backendRetired) {
            runCatching { backend.retire(BACKEND_RETIREMENT_TIMEOUT_MILLIS) }
        } else {
            null
        }
        backendResult?.exceptionOrNull()?.let { problem ->
            failure = combineFailures(failure, problem)
            synchronized(lock) { poisonLocked(problem) }
        }
        backendResult?.getOrNull()?.let { result ->
            if (result.isRetired) {
                synchronized(lock) {
                    backendRetired = true
                    remotesById.clear()
                    remotesByHandle.clear()
                    ownedIngresses.clear()
                    outputs.clear()
                    outputIds.clear()
                    outputBatch = null
                }
            } else {
                val problem = IllegalStateException("EGL backend retirement is not proven: $result")
                failure = combineFailures(failure, problem)
                synchronized(lock) { poisonLocked(problem) }
            }
        }

        val completed = synchronized(lock) {
            closeInFlight = false
            if (
                backendRetired && subscriptionRecords.isEmpty() && operationCount == 0 &&
                renderOperationGeneration == null
            ) {
                closed = true
                true
            } else {
                false
            }
        }
        if (completed) return
        throw failure ?: IllegalStateException("Renderer session retirement remains uncertain")
    }

    /** Bridge GPU/router fence first, exclusive binder release second, backend retirement last. */
    private fun retireBatchResourcesForClose(): Throwable? {
        val resources = synchronized(lock) { batchResources } ?: return null
        if (!resources.bridgeRetired) {
            val result = runCatching { resources.bridge.close() }
            if (result.isFailure) return result.exceptionOrNull()
            synchronized(lock) { resources.bridgeRetired = true }
        }
        if (!resources.binderClosed) {
            val result = runCatching { resources.binder.close() }
            if (result.isFailure) return result.exceptionOrNull()
            synchronized(lock) { resources.binderClosed = true }
        }
        return null
    }

    private fun batchResourcesReadyForBackendRetirementLocked(): Boolean =
        batchResources?.let { it.bridgeRetired && it.binderClosed } ?: true

    private fun openOwnedIngress(
        key: UltraEglIngressKey,
        sizePixels: PixelSize,
        opener: () -> AndroidUltraBackendIngress,
    ): AndroidUltraCustomIngressLease {
        ensureBackendUsable()
        val record = synchronized(lock) {
            checkTopologyMutableLocked()
            require(!ownedIngresses.containsKey(key)) { "EGL ingress is already reserved: $key" }
            val created = OwnedIngressRecord(
                generation = nextGenerationLocked(),
                key = key,
                sizePixels = sizePixels,
                phase = AndroidUltraRendererSessionPhase.PREPARING,
            )
            ownedIngresses[key] = created
            beginOperationLocked()
            created
        }
        val opened = runCatching(opener)
        val resource = opened.getOrNull()
        if (resource == null || resource.key != key) {
            val failure = opened.exceptionOrNull()
                ?: IllegalStateException("EGL backend returned a different owned ingress key")
            synchronized(lock) {
                endOperationLocked()
                record.operationInFlight = false
                record.backendIngress = resource
                record.closeRequested = true
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
            }
            if (resource != null) {
                runCatching { retireOwnedIngress(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
        val accepted = synchronized(lock) {
            endOperationLocked()
            record.operationInFlight = false
            record.backendIngress = resource
            if (ownedIngresses[key] === record && !record.closeRequested && acceptingLocked()) {
                record.phase = AndroidUltraRendererSessionPhase.ACTIVE
                true
            } else {
                record.closeRequested = true
                false
            }
        }
        if (!accepted) {
            retireOwnedIngress(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS)
            throw IllegalStateException("UI Sync renderer session closed while opening owned ingress")
        }
        return AndroidUltraCustomIngressLease(this, record, resource.producerSurface)
    }

    private fun failRemoteOpening(record: RemoteRecord, failure: Throwable) {
        synchronized(lock) {
            endOperationLocked()
            record.operationInFlight = false
            record.closeRequested = true
            record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
            poisonLocked(failure)
        }
    }

    private fun publishRemote(record: RemoteRecord): Boolean {
        synchronized(lock) {
            if (!isCurrentLocked(record) || record.closeRequested || !acceptingLocked()) return false
        }
        ensureBackendUsable()
        return synchronized(lock) {
            if (!isCurrentLocked(record) || record.closeRequested || !acceptingLocked()) return false
            if (record.published) return true
            check(renderOperationGeneration == null) {
                "Remote ingress publication raced a render submission"
            }
            check(!batchOperationInFlight) {
                "Remote ingress publication raced an output batch"
            }
            check(!record.operationInFlight) { "Remote ingress operation is already in flight" }
            val previous = record.published
            record.published = true
            try {
                rebuildGraphLocked()
                true
            } catch (failure: Throwable) {
                record.published = previous
                poisonLocked(failure)
                throw failure
            }
        }
    }

    private fun retireRemote(
        record: RemoteRecord,
        timeoutMillis: Long,
    ): RetirementOutcome {
        val resource = synchronized(lock) {
            if (!isCurrentLocked(record)) return RetirementOutcome(true, null)
            check(!closeInFlight) { "Renderer session retirement is already in flight" }
            check(renderOperationGeneration == null) {
                "Remote ingress retirement raced a render submission; retry close"
            }
            check(!batchOperationInFlight) {
                "Remote ingress retirement raced an output batch; retry close"
            }
            check(!record.operationInFlight) { "Remote ingress operation is already in flight" }
            record.closeRequested = true
            if (record.published) {
                record.published = false
                try {
                    rebuildGraphLocked()
                } catch (failure: Throwable) {
                    record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                    poisonLocked(failure)
                    throw failure
                }
            }
            record.operationInFlight = true
            beginOperationLocked()
            record.backendIngress ?: run {
                record.operationInFlight = false
                endOperationLocked()
                return RetirementOutcome(false, AndroidVideoSurfaceRetirementResult.INTERRUPTED)
            }
        }
        val result = runCatching { resource.retire(timeoutMillis) }
        val unsafe = result.exceptionOrNull() ?: result.getOrNull()
            ?.takeUnless(AndroidVideoSurfaceRetirementResult::isSafeToReleaseSurface)
            ?.let { IllegalStateException("Remote Surface retirement is not proven: $it") }
        finishRemoteRetirement(record, unsafe)
        result.exceptionOrNull()?.let { throw it }
        return RetirementOutcome(unsafe == null, result.getOrNull())
    }

    private fun finishRemoteRetirement(record: RemoteRecord, failure: Throwable?) {
        synchronized(lock) {
            if (!isCurrentLocked(record)) return
            if (record.operationInFlight) {
                record.operationInFlight = false
                endOperationLocked()
            }
            if (failure == null) {
                remotesById.remove(record.ingress.id)
                if (remotesByHandle[record.ingress.stream] === record) {
                    remotesByHandle.remove(record.ingress.stream)
                }
            } else {
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
            }
        }
    }

    private fun retireOwnedIngress(
        record: OwnedIngressRecord,
        timeoutMillis: Long,
    ): RetirementOutcome {
        val replay = synchronized(lock) { record.clusterReplaySubscription }
        if (replay != null) {
            val drained = runCatching { replay.close() }
            if (drained.isFailure) {
                return RetirementOutcome(false, AndroidVideoSurfaceRetirementResult.INTERRUPTED)
            }
        }
        val resource = synchronized(lock) {
            if (ownedIngresses[record.key] !== record) return RetirementOutcome(true, null)
            check(!closeInFlight) { "Renderer session retirement is already in flight" }
            check(renderOperationGeneration == null) {
                "Owned ingress retirement raced a render submission; retry close"
            }
            check(!batchOperationInFlight) {
                "Owned ingress retirement raced an output batch; retry close"
            }
            check(!record.operationInFlight) { "Owned ingress operation is already in flight" }
            record.closeRequested = true
            record.operationInFlight = true
            beginOperationLocked()
            (record.backendIngress ?: record.backendInstrument) ?: run {
                record.operationInFlight = false
                endOperationLocked()
                return RetirementOutcome(false, AndroidVideoSurfaceRetirementResult.INTERRUPTED)
            }
        }
        val result = runCatching { when (resource) {
            is AndroidUltraBackendIngress -> resource.retire(timeoutMillis)
            is AndroidUltraBackendInstrument -> {
                resource.retire(timeoutMillis)
                null
            }
            else -> error("Unknown owned renderer ingress")
        } }
        val unsafe = result.exceptionOrNull() ?: result.getOrNull()
            ?.takeUnless(AndroidVideoSurfaceRetirementResult::isSafeToReleaseSurface)
            ?.let { IllegalStateException("Owned Surface retirement is not proven: $it") }
        finishOwnedIngressRetirement(record, unsafe)
        result.exceptionOrNull()?.let { throw it }
        return RetirementOutcome(unsafe == null, result.getOrNull())
    }

    internal fun closeOwnedIngress(record: OwnedIngressRecord) {
        val result = retireOwnedIngress(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS)
        check(result.completed) { "Owned ingress retirement is not proven: ${result.result}" }
    }

    private fun finishOwnedIngressRetirement(record: OwnedIngressRecord, failure: Throwable?) {
        synchronized(lock) {
            if (ownedIngresses[record.key] !== record) return
            if (record.operationInFlight) {
                record.operationInFlight = false
                endOperationLocked()
            }
            if (failure == null) {
                ownedIngresses.remove(record.key)
            } else {
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
            }
        }
    }

    private fun retireOutput(record: OutputRecord) {
        val resource = synchronized(lock) {
            if (outputs[record.destination] !== record) return
            check(!closeInFlight) { "Renderer session retirement is already in flight" }
            check(renderOperationGeneration == null) {
                "Output retirement raced a render submission; retry close"
            }
            check(!record.operationInFlight) { "Output operation is already in flight" }
            record.closeRequested = true
            try {
                rebuildGraphLocked()
            } catch (failure: Throwable) {
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
                throw failure
            }
            record.operationInFlight = true
            beginOperationLocked()
            record.backendOutput ?: run {
                record.operationInFlight = false
                endOperationLocked()
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                return
            }
        }
        val result = runCatching { resource.retire() }
        finishOutputRetirement(record, result.exceptionOrNull())
        result.getOrThrow()
    }

    internal fun closeOutput(record: OutputRecord) = retireOutput(record)

    internal fun retireAmbiguousOutput(record: OutputRecord) {
        if (!isOutputRetained(record)) return
        check(retireScreens()) {
            "Ambiguous EGL output attachment retirement is not proven; retry close"
        }
    }

    internal fun isOutputRetained(record: OutputRecord): Boolean = synchronized(lock) {
        outputs[record.destination] === record && outputIds[record.output.outputId] === record
    }

    private fun finishOutputRetirement(record: OutputRecord, failure: Throwable?) {
        synchronized(lock) {
            if (outputs[record.destination] !== record) return
            if (record.operationInFlight) {
                record.operationInFlight = false
                endOperationLocked()
            }
            if (failure == null) {
                outputs.remove(record.destination)
                if (outputIds[record.output.outputId] === record) outputIds.remove(record.output.outputId)
            } else {
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
            }
        }
    }

    private fun retireSubscription(
        lease: AndroidUltraPresentationSubscription,
        record: SubscriptionRecord,
    ) {
        val delegate = synchronized(lock) {
            if (
                subscriptions[lease] !== record ||
                subscriptionRecords[record.generation] !== record
            ) return
            record.closeRequested = true
            if (record.delegateDetached) {
                check(record.activeCallbacks == 0) {
                    "Presentation callback is still in flight; retry subscription close"
                }
                subscriptions.remove(lease)
                subscriptionRecords.remove(record.generation)
                lease.markRetired()
                return
            }
            check(!record.operationInFlight) { "Presentation subscription operation is already in flight" }
            record.operationInFlight = true
            beginOperationLocked()
            checkNotNull(record.delegate)
        }
        val result = runCatching { delegate.close() }
        var callbackBarrierFailure: Throwable? = null
        synchronized(lock) {
            if (subscriptions[lease] === record) {
                record.operationInFlight = false
                endOperationLocked()
                if (result.isSuccess) {
                    record.delegateDetached = true
                    if (record.activeCallbacks == 0) {
                        subscriptions.remove(lease)
                        subscriptionRecords.remove(record.generation)
                        lease.markRetired()
                    } else {
                        record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                        callbackBarrierFailure = IllegalStateException(
                            "Presentation callback is still in flight; retry subscription close",
                        )
                    }
                } else {
                    record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                    poisonLocked(checkNotNull(result.exceptionOrNull()))
                }
            }
        }
        result.getOrThrow()
        callbackBarrierFailure?.let { throw it }
    }

    internal fun closePresentationSubscription(
        lease: AndroidUltraPresentationSubscription,
        record: SubscriptionRecord,
    ) = retireSubscription(lease, record)

    private fun finishSubscriptionRetirement(
        record: SubscriptionRecord,
        failure: Throwable?,
    ): Throwable? = synchronized(lock) {
            if (subscriptionRecords[record.generation] !== record) return@synchronized null
            val entry = subscriptions.entries.firstOrNull { it.value === record }
            if (record.operationInFlight) {
                record.operationInFlight = false
                endOperationLocked()
            }
            if (failure == null) {
                record.delegateDetached = true
                if (record.activeCallbacks == 0) {
                    subscriptionRecords.remove(record.generation)
                    entry?.key?.let { lease ->
                        subscriptions.remove(lease)
                        lease.markRetired()
                    }
                    null
                } else {
                    record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                    IllegalStateException(
                        "Presentation callback is still in flight; retry session close",
                    )
                }
            } else {
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                poisonLocked(failure)
                failure
            }
        }

    private fun deliverPresentation(
        record: SubscriptionRecord,
        observer: UltraPresentationObserver,
        snapshot: UltraPresentationSnapshot,
    ) {
        val admitted = synchronized(lock) {
            if (
                subscriptionRecords[record.generation] !== record ||
                record.closeRequested || closed || closeRequested || poisoned
            ) {
                false
            } else {
                record.activeCallbacks = Math.addExact(record.activeCallbacks, 1)
                beginOperationLocked()
                true
            }
        }
        if (!admitted) return
        try {
            observer.onPresentationSnapshot(snapshot)
        } finally {
            synchronized(lock) {
                check(record.activeCallbacks > 0) { "Presentation callback count underflow" }
                record.activeCallbacks -= 1
                endOperationLocked()
                if (
                    record.activeCallbacks == 0 && record.closeRequested &&
                    record.delegate == null && !record.operationInFlight &&
                    subscriptionRecords[record.generation] === record
                ) {
                    subscriptionRecords.remove(record.generation)
                }
            }
        }
    }

    private fun reserveRetirementLocked(record: SubscriptionRecord): Boolean {
        if (record.operationInFlight || record.delegate == null) return false
        record.operationInFlight = true
        beginOperationLocked()
        return true
    }

    private fun rebuildGraphLocked() {
        val sequentialOutputs = outputs.values.mapNotNull { record ->
            record.output.takeIf {
                record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                    !record.closeRequested && record.backendOutput != null
            }
        }
        val batchOutputs = outputBatch?.takeIf { record ->
            record.phase == AndroidUltraRendererSessionPhase.ACTIVE && !record.closeRequested
        }?.requests?.map(AndroidUltraOutputAttachmentRequest::output).orEmpty()
        val activeOutputs = sequentialOutputs + batchOutputs
        val activeIngresses = remotesById.values.mapNotNull { record ->
            record.ingress.takeIf {
                record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                    record.published && !record.closeRequested && record.backendIngress != null
            }
        }
        graphRegistry.replace(activeOutputs, activeIngresses)
    }

    private fun resourcesNeedGraphRemovalLocked(): Boolean =
        closeRequested || outputs.values.any(OutputRecord::closeRequested) ||
            remotesById.values.any(RemoteRecord::closeRequested) ||
            outputBatch?.closeRequested == true

    private fun snapshotLocked(): AndroidUltraRendererSessionSnapshot {
        val graph = graphRegistry.snapshot()
        val resourcePhases = buildList {
            addAll(remotesById.values.map(RemoteRecord::phase))
            addAll(ownedIngresses.values.map(OwnedIngressRecord::phase))
            addAll(outputs.values.map(OutputRecord::phase))
            outputBatch?.let { add(it.phase) }
            addAll(subscriptionRecords.values.map(SubscriptionRecord::phase))
        }
        val phase = when {
            closed -> AndroidUltraRendererSessionPhase.CLOSED
            closeRequested || poisoned ||
                resourcePhases.any { it == AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN } ->
                AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
            operationCount > 0 ||
                resourcePhases.any { it == AndroidUltraRendererSessionPhase.PREPARING } ->
                AndroidUltraRendererSessionPhase.PREPARING
            else -> AndroidUltraRendererSessionPhase.ACTIVE
        }
        return AndroidUltraRendererSessionSnapshot(
            phase = phase,
            graphRevision = graph.revision,
            lastSubmittedRenderRevision = lastSubmittedRenderRevision,
            remoteIngresses = Collections.unmodifiableList(
                remotesById.values.map { record ->
                    AndroidUltraRemoteIngressSnapshot(
                        record.ingress.id,
                        record.ingress.stream,
                        record.generation,
                        record.phase,
                        record.published,
                    )
                },
            ),
            ownedIngresses = Collections.unmodifiableList(
                ownedIngresses.values.map { record ->
                    AndroidUltraOwnedIngressSnapshot(record.key, record.generation, record.phase)
                },
            ),
            outputs = Collections.unmodifiableList(
                outputs.values.map { record ->
                    AndroidUltraOutputSnapshot(
                        record.destination,
                        record.output,
                        record.generation,
                        record.phase,
                    )
                } + outputBatch?.requests.orEmpty().map { request ->
                    AndroidUltraOutputSnapshot(
                        request.destination,
                        request.output,
                        checkNotNull(outputBatch).generation,
                        checkNotNull(outputBatch).phase,
                    )
                },
            ),
            presentationSubscriptionCount = subscriptionRecords.size,
            operationCount = operationCount,
            closeRequested = closeRequested,
            crossOutputAttachmentIsAtomic = outputBatch?.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                outputBatch?.closeRequested == false,
            outputBatchGeneration = outputBatch?.lease?.generation?.value,
            lastFailure = lastFailure,
        )
    }

    private fun isCurrentLocked(record: RemoteRecord): Boolean =
        remotesById[record.ingress.id] === record &&
            remotesByHandle[record.ingress.stream] === record

    private fun isCurrentLocked(record: OutputRecord): Boolean =
        outputs[record.destination] === record && outputIds[record.output.outputId] === record

    private fun checkAcceptingLocked() {
        check(acceptingLocked()) { "UI Sync renderer session is closed or retirement-uncertain" }
    }

    private fun checkTopologyMutableLocked() {
        checkAcceptingLocked()
        check(renderOperationGeneration == null) {
            "Renderer topology cannot change during a render submission"
        }
        check(!batchOperationInFlight) {
            "Renderer topology cannot change during an output batch operation"
        }
    }

    private fun topologyOperationInFlightLocked(): Boolean =
        remotesById.values.any(RemoteRecord::operationInFlight) ||
            ownedIngresses.values.any(OwnedIngressRecord::operationInFlight) ||
            outputs.values.any(OutputRecord::operationInFlight) || batchOperationInFlight

    private fun ensureBackendUsable() {
        invalidateUnusableBatch()
        val phase = runCatching { backend.phase() }.getOrElse { failure ->
            synchronized(lock) { poisonLocked(failure) }
            throw failure
        }
        if (phase.isTerminalOrUncertain()) {
            val failure = IllegalStateException("EGL backend is not usable: $phase")
            synchronized(lock) { poisonLocked(failure) }
            throw failure
        }
    }

    private fun acceptingLocked(): Boolean = !closed && !closeRequested && !poisoned

    private fun nextGenerationLocked(): Long {
        check(generation < Long.MAX_VALUE) { "Renderer session generation exhausted" }
        generation += 1L
        return generation
    }

    private fun beginOperationLocked() {
        operationCount = Math.addExact(operationCount, 1)
    }

    private fun endOperationLocked() {
        check(operationCount > 0) { "Renderer session operation count underflow" }
        operationCount -= 1
    }

    private fun poisonLocked(failure: Throwable) {
        poisoned = true
        lastFailure = failure
        outputBatch?.takeIf { it.phase == AndroidUltraRendererSessionPhase.ACTIVE && !it.closeRequested }
            ?.let { record ->
                record.closeRequested = true
                record.phase = AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
                runCatching { rebuildGraphLocked() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
    }

    /** A native/router generation can become unusable without a coordinator method being called. */
    private fun invalidateUnusableBatch() {
        val candidate = synchronized(lock) {
            outputBatch?.takeIf { record ->
                record.phase == AndroidUltraRendererSessionPhase.ACTIVE &&
                    !record.closeRequested && !batchOperationInFlight && !closeInFlight &&
                    !closed && !closeRequested
            }
        } ?: return
        if (batchLivenessProbeInThread.get() == true) {
            synchronized(lock) {
                if (outputBatch === candidate && !candidate.closeRequested) {
                    poisonLocked(IllegalStateException("Reentrant output batch liveness probe"))
                }
            }
            return
        }
        batchLivenessProbeInThread.set(true)
        val current = try {
            runCatching {
                candidate.requests.all { it.target.isValid } && candidate.lease.isCurrent
            }.getOrDefault(false)
        } finally {
            batchLivenessProbeInThread.remove()
        }
        if (current) return
        synchronized(lock) {
            if (outputBatch === candidate && !batchOperationInFlight && !candidate.closeRequested) {
                poisonLocked(IllegalStateException("Installed EGL/router output batch or physical target is no longer live"))
            }
        }
    }

    private class RemoteCandidate(
        private val owner: AndroidUltraRendererSessionCoordinator,
        private val record: RemoteRecord,
    ) : UiSyncRendererScreenCandidate {
        override val stream: AirPlayVideoStreamHandle
            get() = record.ingress.stream
        override val ingressId: UltraIngressId
            get() = record.ingress.id

        override fun publish(): Boolean = owner.publishRemote(record)

        override fun close() {
            val result = owner.retireRemote(record, RESOURCE_RETIREMENT_TIMEOUT_MILLIS)
            check(result.completed) { "Remote Surface retirement is not proven: ${result.result}" }
        }
    }

    companion object {
        internal const val IMPLEMENTATION_ID = "android-ultra-egl-session-coordinator-v1"
        private const val MAXIMUM_OUTPUTS = 64
        private const val MAXIMUM_LOCAL_INGRESS_EXPECTATIONS = 4_096
        private const val MAXIMUM_PRESENTATION_SUBSCRIPTIONS = 256
        private const val RESOURCE_RETIREMENT_TIMEOUT_MILLIS = 5_000L
        private const val BACKEND_RETIREMENT_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Producer endpoint for caller-rendered OEM UI or overlays. [producerSurface] is borrowed from the
 * session-private EGL backend: draw into it, but never release it directly. Successful [close] (or
 * successful whole-session close) is the proof that the backend no longer references it.
 */
class AndroidUltraCustomIngressLease internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    private val record: AndroidUltraRendererSessionCoordinator.OwnedIngressRecord,
    val producerSurface: Surface,
) : Closeable {
    val key: UltraEglIngressKey
        get() = record.key

    internal fun recordFor(expectedOwner: AndroidUltraRendererSessionCoordinator):
        AndroidUltraRendererSessionCoordinator.OwnedIngressRecord? =
        record.takeIf { owner === expectedOwner }

    override fun close() {
        owner.closeOwnedIngress(record)
    }
}

/** Child-owned opaque CLUSTER texture. No caller can lock a Surface or draw Canvas into it. */
internal class AndroidUltraInstrumentRasterLease internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    private val record: AndroidUltraRendererSessionCoordinator.OwnedIngressRecord,
) : Closeable {
    val destination: DisplayDestination
        get() = (record.key as UltraEglIngressKey.Instrument).destination
    val sizePixels: PixelSize get() = record.sizePixels

    internal fun recordFor(expectedOwner: AndroidUltraRendererSessionCoordinator):
        AndroidUltraRendererSessionCoordinator.OwnedIngressRecord? =
        record.takeIf { owner === expectedOwner }

    fun replaceCompleteOpaqueFrame(argbPixels: IntArray): UltraEglInstrumentUploadReceipt =
        owner.replaceInstrumentRaster(record, argbPixels)

    internal fun replaceOwnedCompleteOpaqueFrame(frame: OwnedOpaqueArgbFrame):
        UltraEglInstrumentUploadReceipt = owner.replaceInstrumentRaster(record, frame)

    override fun close() = owner.closeOwnedIngress(record)
}

/** Exact child observer; close drains the bounded EGL outbox before its ingress may retire. */
internal class AndroidUltraClusterReplaySubscription internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    internal val record: AndroidUltraRendererSessionCoordinator.OwnedIngressRecord,
    internal val onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
    internal val onRevoked: () -> Unit,
    internal val onSuperseded: (UltraEglClusterReplayReceipt) -> Unit,
) : Closeable {
    @Volatile internal var delegate: UltraEglClusterReplaySubscription? = null
    @Volatile internal var closeRequested = false
    @Volatile internal var revoked = false
    internal var deferredReplay: UltraEglClusterReplayReceipt? = null

    override fun close() = owner.closeClusterReplaySubscription(this)
}

/**
 * Ownership receipt for an output attach whose native completion was ambiguous. The referenced
 * [target] may still be owned by EGL even though attachOutput did not return a normal attachment.
 * Do not close an OWNED target until this exception's [close] returns successfully (or the whole
 * coordinator has closed successfully). Closing performs one session-wide backend retirement proof
 * and is retryable when that proof remains uncertain.
 */
class AndroidUltraOutputAttachmentFailure internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    private val record: AndroidUltraRendererSessionCoordinator.OutputRecord,
    cause: Throwable,
) : IllegalStateException("Android Ultra output attachment completion is ambiguous", cause), Closeable {
    val destination: DisplayDestination
        get() = record.destination
    val output: UltraDisplayGraphOutput
        get() = record.output
    val target: AndroidDisplaySurfaceTarget
        get() = record.target
    val isRetired: Boolean
        get() = !owner.isOutputRetained(record)

    override fun close() {
        owner.retireAmbiguousOutput(record)
    }
}

/**
 * Exclusive EGL attachment for [target]. The caller must not close the target until this lease (or
 * the whole coordinator) has retired successfully. A future DisplaySurfaceRouter bridge must use
 * `target.borrowForRouting()`, retire that route first, then retire this attachment, then close the
 * target. Closing this lease deliberately does not close the caller-owned target.
 */
class AndroidUltraOutputAttachment internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    private val record: AndroidUltraRendererSessionCoordinator.OutputRecord,
) : Closeable {
    val destination: DisplayDestination
        get() = record.destination
    val output: UltraDisplayGraphOutput
        get() = record.output
    val target: AndroidDisplaySurfaceTarget
        get() = record.target

    override fun close() {
        owner.closeOutput(record)
    }
}

/** A generation-safe logical-route lease, not a proof of simultaneous physical frame swaps. */
class AndroidUltraOutputBatchLease internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    private val record: AndroidUltraRendererSessionCoordinator.BatchOutputRecord,
) : Closeable {
    val generation: Long
        get() = record.lease.generation.value

    val isCurrent: Boolean
        get() = owner.isOutputBatchCurrent(record)

    override fun close() {
        owner.closeOutputBatch(record)
    }
}

/**
 * Native batch completion or cleanup could not be proven. The caller must retain every [targets]
 * until this retryable receipt closes successfully (or the whole coordinator does), even when no
 * graph output was published. Closing retires this exact session's backend and all its outputs.
 */
class AndroidUltraOutputBatchCommitFailure internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    requests: List<AndroidUltraOutputAttachmentRequest>,
    cause: Throwable,
) : IllegalStateException("Android Ultra output batch completion is uncertain", cause), Closeable {
    val targets: List<AndroidDisplaySurfaceTarget> =
        Collections.unmodifiableList(requests.map(AndroidUltraOutputAttachmentRequest::target))

    val isRetired: Boolean
        get() = owner.isBatchRetired()

    override fun close() {
        check(owner.retireScreens()) {
            "Uncertain output batch retirement is not proven; keep targets and retry close"
        }
    }
}

class AndroidUltraPresentationSubscription internal constructor(
    private val owner: AndroidUltraRendererSessionCoordinator,
    private val record: AndroidUltraRendererSessionCoordinator.SubscriptionRecord,
    val initialSnapshot: UltraPresentationSnapshot,
) : Closeable {
    @Volatile private var retired = false

    override fun close() {
        if (!retired) owner.closePresentationSubscription(this, record)
    }

    internal fun markRetired() {
        retired = true
    }
}

private fun UltraEglBackendPhase.isTerminalOrUncertain(): Boolean = when (this) {
    UltraEglBackendPhase.STARTING,
    UltraEglBackendPhase.OPEN,
    -> false
    UltraEglBackendPhase.FAILED,
    UltraEglBackendPhase.RETIREMENT_UNCERTAIN,
    UltraEglBackendPhase.CLOSED,
    -> true
}

private fun combineFailures(current: Throwable?, next: Throwable?): Throwable? {
    if (next == null) return current
    if (current == null) return next
    if (current !== next) current.addSuppressed(next)
    return current
}

private fun <T> boundedCopy(values: Collection<T>, limit: Int, label: String): List<T> {
    val copied = ArrayList<T>(minOf(limit, 16))
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(copied.size < limit) { "$label exceed $limit entries" }
        copied += iterator.next()
    }
    return copied
}

private fun <K, V> boundedMapCopy(values: Map<K, V>, limit: Int, label: String): Map<K, V> {
    val copied = LinkedHashMap<K, V>(minOf(limit, 16))
    val iterator = values.entries.iterator()
    while (iterator.hasNext()) {
        require(copied.size < limit) { "$label exceed $limit entries" }
        val entry = iterator.next()
        require(!copied.containsKey(entry.key)) { "$label contain a duplicate key" }
        copied[entry.key] = entry.value
    }
    return Collections.unmodifiableMap(copied)
}
