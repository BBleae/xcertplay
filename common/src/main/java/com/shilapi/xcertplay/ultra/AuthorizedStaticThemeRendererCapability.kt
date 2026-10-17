package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifest
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncRuntimeContract
import com.shilapi.xcertplay.HostVideoFrameReleaseChannel
import com.shilapi.xcertplay.deriveVerifiedUiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncAuthorizedStaticInputs
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererCapability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererFactoryLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhoneEpochObserver
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.ultra.egl.AndroidUltraEglBackend
import com.shilapi.xcertplay.ultra.egl.RemoteFrameIdentityDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.media.AndroidVideoFrameReleaseReceipt
import java.io.Closeable
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

internal enum class VideoFrameIdentityAdmission { CURRENT, BUSY_RETRY, REVOKED }

/** A one-batch diagnostic gate; replacement requires a new signed renderer child. */
internal class AuthorizedVideoFrameReleaseGate<Batch : Any, Receipt : Any>(
    private val generationStatus: (Batch) -> VideoFrameIdentityAdmission,
    private val forward: (Receipt) -> Unit,
    private val queueCapacity: Int = 32,
) : Closeable {
    private class Pending<Batch : Any, Receipt : Any>(
        val batch: Batch,
        val receipt: Receipt,
    )

    private val lock = Object()
    private val queue = ArrayDeque<Pending<Batch, Receipt>>()
    private var batch: Batch? = null
    private var worker: Thread? = null
    private var closed = false
    private var busyRetry = false

    init { require(queueCapacity in 1..128) }

    /** Called only after the caller admitted this exact child/batch; BUSY may still arise here. */
    fun bind(candidate: Batch) {
        synchronized(lock) {
            if (closed) return
            check(batch == null || batch === candidate) {
                "A renderer child cannot replace its physical output batch"
            }
            if (batch == null) {
                batch = candidate
                worker = Thread(::drain, "xcertplay-ultra-frame-identity").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun offer(receipt: Receipt) {
        // Decoder-thread path: one bounded enqueue/drop under a private monitor, with no
        // authority, SurfaceTexture, EGL, file, or lifecycle operation.
        synchronized(lock) {
            if (closed) return
            val current = batch ?: return
            if (queue.size == queueCapacity) queue.removeFirst()
            queue.addLast(Pending(current, receipt))
            if (!busyRetry) lock.notifyAll()
        }
    }

    private fun drain() {
        try {
            var deferred: Pending<Batch, Receipt>? = null
            while (true) {
                val pending = synchronized(lock) {
                    while (!closed && deferred == null && queue.isEmpty()) {
                        try {
                            lock.wait()
                        } catch (_: InterruptedException) {
                            if (closed) return
                        }
                    }
                    if (closed) return
                    if (deferred != null && queue.isNotEmpty()) {
                        deferred = null
                        queue.removeLast().also { queue.clear() }
                    } else deferred?.also { deferred = null } ?: queue.removeFirst()
                }
                when (runCatching { generationStatus(pending.batch) }
                        .getOrDefault(VideoFrameIdentityAdmission.BUSY_RETRY)) {
                    VideoFrameIdentityAdmission.BUSY_RETRY -> {
                        // While AA05/physical output is temporarily busy, preserve only the
                        // latest exact-session receipt. Never spin on the decoder thread.
                        synchronized(lock) {
                            if (closed || batch !== pending.batch) return
                            busyRetry = true
                            deferred = if (queue.isEmpty()) pending else queue.removeLast().also {
                                queue.clear()
                            }
                            try {
                                lock.wait(BUSY_RETRY_MILLIS)
                            } catch (_: InterruptedException) {
                                if (closed) return
                            } finally {
                                busyRetry = false
                            }
                            if (queue.isNotEmpty()) {
                                deferred = queue.removeLast()
                                queue.clear()
                            }
                        }
                    }
                    VideoFrameIdentityAdmission.REVOKED -> {
                        // True generation retirement is terminal, not a retryable BUSY.
                        synchronized(lock) {
                            if (batch === pending.batch) {
                                closed = true
                                batch = null
                                queue.clear()
                                lock.notifyAll()
                            }
                        }
                        return
                    }
                    VideoFrameIdentityAdmission.CURRENT -> {
                        if (synchronized(lock) { !closed && batch === pending.batch }) {
                            // The coordinator/backend perform exact stream, Surface, and EGL
                            // generation checks; this never changes media/UI Sync readiness.
                            runCatching { forward(pending.receipt) }
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                closed = true
                batch = null
                queue.clear()
                lock.notifyAll()
            }
        }
    }

    override fun close() {
        val running = synchronized(lock) {
            closed = true
            batch = null
            queue.clear()
            lock.notifyAll()
            worker
        }
        // Do not join here: the authority may be inside an EGL timeout. The generation check
        // above discards it on return, while interruption helps the read-only probe retire.
        running?.interrupt()
    }

    internal fun awaitWorkerRetired(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val running = synchronized(lock) { worker } ?: return true
        if (running === Thread.currentThread()) return false
        running.join(timeoutMillis)
        return !running.isAlive
    }

    private companion object { const val BUSY_RETRY_MILLIS = 75L }
}

/**
 * Static executable theme compiler and Android EGL implementation for one verified Profile.
 * The pre-manifest constructor accepts only the already-frozen UI Sync inputs; it derives the
 * program fingerprint from every selected Profile variant rather than accepting an arbitrary
 * receipt value. The later manifest issuer independently revalidates that exact fingerprint,
 * assignments, and physical plan before sealing the authority. These receipts prove an
 * implementation, not a physical Surface bind, submitted scene, or frame on a display.
 */
internal class AuthorizedStaticThemeRendererCapability private constructor(
    private val profile: VerifiedUltraProfile,
    private val contract: StaticThemeRendererContract,
    private val authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority?,
    private val diagnosticFallbackEnabled: Boolean,
    private val videoFrameReleaseChannel: HostVideoFrameReleaseChannel?,
    private val openFactoryBackend: () -> StaticThemeFactoryBackendLease,
    private val openCoordinator: (UiSyncRendererSessionBindingContext) ->
        AndroidUltraRendererSessionCoordinator,
) : UiSyncCompleteRendererCapability {
    constructor(
        profile: VerifiedUltraProfile,
        manifest: AuthorizedUltraCompiledRuntimeManifest,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
        authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
        videoFrameReleaseChannel: HostVideoFrameReleaseChannel? = null,
        openCoordinator: (UiSyncRendererSessionBindingContext) ->
            AndroidUltraRendererSessionCoordinator,
    ) : this(profile, issuedContract(profile, manifest, issuer), authority, false,
        videoFrameReleaseChannel, ::openAndroidFactoryBackend, openCoordinator)

    /** Construction is possible before the manifest, but never from caller-chosen fingerprints. */
    constructor(
        profile: VerifiedUltraProfile,
        assignments: Collection<CompiledUiSyncDisplayAssignment>,
        inputs: UiSyncAuthorizedStaticInputs,
        authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
        videoFrameReleaseChannel: HostVideoFrameReleaseChannel? = null,
        openCoordinator: (UiSyncRendererSessionBindingContext) ->
            AndroidUltraRendererSessionCoordinator,
    ) : this(profile, preManifestContract(profile, assignments, inputs), authority,
        false, videoFrameReleaseChannel,
        ::openAndroidFactoryBackend, openCoordinator)

    /** Only an explicitly selected diagnostic composition may paint a fallback label. */
    constructor(
        profile: VerifiedUltraProfile,
        assignments: Collection<CompiledUiSyncDisplayAssignment>,
        inputs: UiSyncAuthorizedStaticInputs,
        authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
        diagnosticFallbackEnabled: Boolean,
        videoFrameReleaseChannel: HostVideoFrameReleaseChannel? = null,
        openCoordinator: (UiSyncRendererSessionBindingContext) ->
            AndroidUltraRendererSessionCoordinator,
    ) : this(profile, preManifestContract(profile, assignments, inputs), authority,
        diagnosticFallbackEnabled, videoFrameReleaseChannel,
        ::openAndroidFactoryBackend, openCoordinator)

    /** Deterministic JVM test seam; the product constructor above always probes real EGL. */
    internal constructor(
        profile: VerifiedUltraProfile,
        assignments: Collection<CompiledUiSyncDisplayAssignment>,
        inputs: UiSyncAuthorizedStaticInputs,
        authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority?,
        openFactoryBackend: () -> StaticThemeFactoryBackendLease,
        openCoordinator: (UiSyncRendererSessionBindingContext) ->
            AndroidUltraRendererSessionCoordinator,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit,
        diagnosticFallbackEnabled: Boolean = false,
        videoFrameReleaseChannel: HostVideoFrameReleaseChannel? = null,
    ) : this(profile, preManifestContract(profile, assignments, inputs), authority,
        diagnosticFallbackEnabled, videoFrameReleaseChannel,
        openFactoryBackend, openCoordinator)

    override fun acquire(context: UiSyncCompleteRendererBindingContext):
        UiSyncCompleteRendererFactoryLease {
        val plan = context.staticPlan
        require((contract.exactFrozenPlan == null || plan === contract.exactFrozenPlan) &&
            context.schemaFingerprint == contract.schemaFingerprint &&
            context.bindingFingerprint == contract.bindingFingerprint &&
            context.displayIds == contract.displayIds &&
            sameExactOutputInstances(plan.outputs, contract.outputs) &&
            sameIngressSpecs(plan.ingressSpecs, contract.ingressSpecs)
        ) { "Renderer factory does not match its frozen verified static plan" }
        require(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
            contract.assignments) == contract.semanticThemeProgramFingerprint
        ) { "Verified theme program changed before factory acquisition" }
        val backend = openFactoryBackend()
        try {
            return StaticThemeRendererFactory(
                context, contract.semanticThemeProgramFingerprint,
                AndroidUltraRendererSessionCoordinator.IMPLEMENTATION_ID,
                authority, contract, backend, diagnosticFallbackEnabled,
                videoFrameReleaseChannel, openCoordinator,
            )
        } catch (cause: Throwable) {
            runCatching { backend.close() }.exceptionOrNull()?.let(cause::addSuppressed)
            throw cause
        }
    }
}

/** The issuer canonically sorts outputs; the renderer plan preserves its original wire order. */
internal fun sameExactOutputInstances(
    actual: List<UltraDisplayGraphOutput>,
    expected: List<UltraDisplayGraphOutput>,
): Boolean = actual.size == expected.size && actual.all { output ->
    expected.any { it === output }
}

/** The issuer also sorts ingress specs, while duplicate IDs are forbidden by both owners. */
internal fun sameIngressSpecs(
    actual: List<UiSyncRendererIngressSpec>,
    expected: List<UiSyncRendererIngressSpec>,
): Boolean = actual.size == expected.size && actual.toSet() == expected.toSet()

/** An independently live EGL resource makes the factory's backend claim observable and owned. */
internal interface StaticThemeFactoryBackendLease : Closeable {
    val isLive: Boolean
}

private class AndroidStaticThemeFactoryBackendLease : StaticThemeFactoryBackendLease {
    private val backend = AndroidUltraEglBackend()
    override val isLive: Boolean
        get() = runCatching {
            backend.diagnostics().phase == UltraEglBackendPhase.OPEN
        }.getOrDefault(false)
    override fun close() = backend.close()
}

private fun openAndroidFactoryBackend(): StaticThemeFactoryBackendLease =
    AndroidStaticThemeFactoryBackendLease()

/** All selected variants have passed the verified package's executable-static compiler. */
internal class StaticThemeRendererContract(
    val exactFrozenPlan: UiSyncRendererStaticPlan?,
    val schemaFingerprint: UiSyncSchemaFingerprint,
    val bindingFingerprint: UiSyncBindingFingerprint,
    val semanticThemeProgramFingerprint: UiSyncSemanticThemeProgramFingerprint,
    val displayIds: Set<String>,
    val outputs: List<UltraDisplayGraphOutput>,
    val ingressSpecs: List<UiSyncRendererIngressSpec>,
    val assignments: List<CompiledUiSyncDisplayAssignment>,
)

private fun preManifestContract(
    profile: VerifiedUltraProfile,
    assignments: Collection<CompiledUiSyncDisplayAssignment>,
    inputs: UiSyncAuthorizedStaticInputs,
): StaticThemeRendererContract {
    val rows = frozenAssignments(assignments)
    val plan = inputs.staticPlan
    val displayIds = plan.outputs.mapNotNull { it.uiSyncDisplayId?.value }.toSet()
    require(rows.size == plan.outputs.size &&
        rows.map { it.graphOutput() }.toSet() == plan.outputs.toSet() &&
        displayIds.size == plan.outputs.size &&
        rows.map { it.uiSyncDisplayId.value }.toSet() == displayIds &&
        rows.flatMap { row ->
            row.slotIngressAssignments + row.additionalVariants.flatMap {
                it.slotIngressAssignments
            }
        }.all { it.ingressSpec in plan.ingressSpecs }
    ) { "Verified theme assignments differ from frozen renderer outputs or ingress selectors" }
    val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, rows)
    return StaticThemeRendererContract(
        plan, inputs.schemaFingerprint, inputs.bindingFingerprint, program,
        displayIds, plan.outputs, plan.ingressSpecs, rows,
    )
}

private fun issuedContract(
    profile: VerifiedUltraProfile,
    manifest: AuthorizedUltraCompiledRuntimeManifest,
    issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
): StaticThemeRendererContract {
    require(manifest.issuedBy(issuer)) { "Renderer manifest is not issued by the product issuer" }
    val compiled: CompiledUiSyncRuntimeContract = requireNotNull(manifest.uiSync) {
        "An authorized static renderer requires a compiled UI Sync contract"
    }
    require(compiled.physicalOutputRequirement != null &&
        compiled.rendererImplementationId == AndroidUltraRendererSessionCoordinator.IMPLEMENTATION_ID
    ) { "Issued renderer contract has no physical authority or different implementation" }
    val rows = manifest.displayAssignmentsFor(profile)
    require(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, rows) ==
        compiled.semanticThemeProgramFingerprint
    ) { "Issued theme program does not match the exact verified Profile" }
    return StaticThemeRendererContract(
        null, compiled.schemaFingerprint, compiled.bindingFingerprint,
        compiled.semanticThemeProgramFingerprint, compiled.displayIds,
        compiled.outputs, compiled.ingressSpecs, rows,
    )
}

private fun frozenAssignments(
    assignments: Collection<CompiledUiSyncDisplayAssignment>,
): List<CompiledUiSyncDisplayAssignment> {
    val rows = ArrayList<CompiledUiSyncDisplayAssignment>()
    assignments.forEach {
        require(rows.size < 64) { "Verified theme assignments exceed the UI Sync display limit" }
        rows += it
    }
    return Collections.unmodifiableList(rows.sortedBy { it.profileDisplayIndex })
}

/** Kept internal for direct lifecycle tests; product acquisition above supplies issuer data. */
internal class StaticThemeRendererFactory(
    private val context: UiSyncCompleteRendererBindingContext,
    programFingerprint: UiSyncSemanticThemeProgramFingerprint,
    override val implementationId: String,
    private val authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority?,
    private val contract: StaticThemeRendererContract? = null,
    private val backend: StaticThemeFactoryBackendLease? = null,
    private val diagnosticFallbackEnabled: Boolean = false,
    private val videoFrameReleaseChannel: HostVideoFrameReleaseChannel? = null,
    private val openCoordinator: (UiSyncRendererSessionBindingContext) ->
        AndroidUltraRendererSessionCoordinator,
) : UiSyncCompleteRendererFactoryLease {
    private val lock = Any()
    private val children = Collections.newSetFromMap(
        IdentityHashMap<StaticThemeRendererSession, Boolean>(),
    )
    private var closeRequested = false
    private var closed = false
    private var closeInFlight = false
    private var backendClosed = false

    override val schemaFingerprint: UiSyncSchemaFingerprint = context.schemaFingerprint
    override val bindingFingerprint: UiSyncBindingFingerprint = context.bindingFingerprint
    override val displayIds: Set<String> = context.displayIds
    private val plan: UiSyncRendererStaticPlan = context.staticPlan
    private val fingerprint = programFingerprint

    // Factory readiness requires both complete verified compilation and a *live owned* EGL
    // backend probe. Shared then constructs a separate real session probe before authorization.
    override val readinessReceipt: RendererReadinessReceipt = context.issueReadinessReceipt(
        fingerprint, emptySet(), emptySet(),
        semanticCompilerActive = true,
        themeCompilerActive = contract != null,
        backendLive = contract != null && backend?.isLive == true,
    )

    override val isLive: Boolean get() = synchronized(lock) {
        !closeRequested && !closed && (contract == null ||
            (readinessReceipt.themeCompilerActive && readinessReceipt.backendLive &&
                backend?.isLive == true))
    }

    override fun openSession(context: UiSyncCompleteRendererSessionBindingContext):
        UiSyncCompleteRendererSessionLease {
        return synchronized(lock) {
            check(!closeRequested && !closed) { "Static theme renderer factory is closed" }
            check(contract == null || (readinessReceipt.backendLive &&
                backend?.isLive == true)) {
                "Verified static theme factory EGL backend is unavailable"
            }
            require(context.staticPlan === plan &&
                context.schemaFingerprint == schemaFingerprint &&
                context.bindingFingerprint == bindingFingerprint &&
                context.displayIds == displayIds
            ) { "Renderer child received a different static provisioning" }

            // Serialize construction against factory retirement. ARM can call the presentation
            // source, but neither it nor the authority reenters this factory.
            val coordinator = openCoordinator(context.rendererContext)
            val child = StaticThemeRendererSession(
                context, coordinator, fingerprint, implementationId, authority, contract,
                diagnosticFallbackEnabled, videoFrameReleaseChannel,
            ) { retired -> synchronized(lock) { children.remove(retired) } }
            children.add(child)
            try {
                // The construction probe receives a fresh child and subscribes to actual replay
                // sources, but authority tombstones it before the manifest is sealed. No output is
                // touched by ARM; the real AirPlay child is registered only after issuer seal.
                child.armAndRegister()
                child
            } catch (cause: Throwable) {
                runCatching { child.close() }.exceptionOrNull()?.let(cause::addSuppressed)
                throw cause
            }
        }
    }

    override fun close() {
        val owned = synchronized(lock) {
            closeRequested = true
            if (closed) return
            check(!closeInFlight) { "Static theme renderer factory close is in flight" }
            closeInFlight = true
            children.toList()
        }
        var failure: Throwable? = null
        try {
            owned.asReversed().forEach { child ->
                runCatching { child.close() }.exceptionOrNull()?.let { problem ->
                    if (failure == null) failure = problem
                    else if (failure !== problem) failure?.addSuppressed(problem)
                }
            }
            if (failure == null && synchronized(lock) { children.isEmpty() } &&
                !backendClosed
            ) {
                try {
                    backend?.close()
                    synchronized(lock) { backendClosed = true }
                } catch (problem: Throwable) {
                    failure = problem
                }
            }
            synchronized(lock) {
                if (children.isEmpty() && backendClosed) closed = true
            }
        } finally {
            synchronized(lock) { closeInFlight = false }
        }
        failure?.let { throw it }
        check(synchronized(lock) { closed }) {
            "Static theme renderer factory still owns a renderer child"
        }
    }
}

/** Exact coordinator ownership asserted by the fixed renderer factory, not a matching string. */
internal interface AuthorizedCoordinatorBackedRendererChild : UiSyncCompleteRendererSessionLease {
    val exactCoordinator: AndroidUltraRendererSessionCoordinator
}

/** One exact child. Its coordinator remains the sole owner of EGL and decoder Surface leases. */
internal class StaticThemeRendererSession(
    private val context: UiSyncCompleteRendererSessionBindingContext,
    val coordinator: AndroidUltraRendererSessionCoordinator,
    programFingerprint: UiSyncSemanticThemeProgramFingerprint,
    override val implementationId: String,
    private val authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority?,
    private val contract: StaticThemeRendererContract?,
    private val diagnosticFallbackEnabled: Boolean,
    private val videoFrameReleaseChannel: HostVideoFrameReleaseChannel?,
    private val onClosed: (StaticThemeRendererSession) -> Unit,
) : AuthorizedCoordinatorBackedRendererChild, SignedLocalCanvasChild,
    UiSyncPhoneEpochObserver {
    override val exactCoordinator: AndroidUltraRendererSessionCoordinator get() = coordinator
    private val localCanvasOwner = UltraLocalCanvasSlotOwner(coordinator)
    private val instrumentOwner = UltraInstrumentReferenceSlotOwner(coordinator)
    private val lock = Any()
    /** Serializes post-seal attachment publication against child retirement. */
    private val attachmentLifecycle = Any()
    private var controller: UltraThemeLayoutIntentController? = null
    private var registration: Closeable? = null
    private var customCanvasAttachment: Closeable? = null
    private var videoFrameReleaseAttachment: Closeable? = null
    private var activatedPhysicalBatch: UiSyncPhysicalOutputBatch? = null
    private var diagnosticVideoObserver: ((AndroidVideoFrameReleaseReceipt) -> Unit)? = null
    private var diagnosticVideoObserverToken: Any? = null
    private val videoFrameGate = AuthorizedVideoFrameReleaseGate<
        UiSyncPhysicalOutputBatch, AndroidVideoFrameReleaseReceipt>(
        ::videoFrameIdentityAdmission,
        ::forwardVideoFrameRelease,
    )
    private var closeRequested = false
    private var closeInFlight = false
    private var customCanvasDetached = false
    private var videoFrameReleaseDetached = false
    private var localCanvasRevoked = false
    private var controllerClosed = false
    private var localCanvasClosed = false
    private var instrumentOwnerClosed = false
    private var coordinatorClosed = false
    private var registrationClosed = false
    private var closed = false

    override val schemaFingerprint: UiSyncSchemaFingerprint = context.schemaFingerprint
    override val bindingFingerprint: UiSyncBindingFingerprint = context.bindingFingerprint
    override val displayIds: Set<String> = context.displayIds
    private val fingerprint = programFingerprint
    private var issuedReceipt: RendererReadinessReceipt? = null
    override val readinessReceipt: RendererReadinessReceipt?
        get() = synchronized(lock) { issuedReceipt }

    override fun invalidatePhoneEpoch(
        displayId: String,
        presentationRevisionFloor: Long,
    ): Boolean {
        if (displayId !in displayIds) return false
        val exact = synchronized(lock) {
            if (closeRequested || closed) null else controller
        } ?: return false
        return exact.invalidatePhoneEpoch(
            UltraUiSyncDisplayId(displayId), presentationRevisionFloor,
        )
    }

    /** Read-only, exact-child CLUSTER recovery witness for Authority-issued frame permits. */
    internal fun clusterRecoveryWitness(
        displayId: UltraUiSyncDisplayId,
        sceneRevision: Long,
    ): Boolean {
        if (displayId.value !in displayIds) return false
        val exact = synchronized(lock) {
            if (closeRequested || closed) null else controller
        } ?: return false
        return exact.clusterRecoveryWitness(displayId, sceneRevision)
    }

    internal fun armAndRegister() {
        require(coordinator.implementationId == implementationId &&
            coordinator.schemaFingerprint == schemaFingerprint &&
            coordinator.bindingFingerprint == bindingFingerprint &&
            coordinator.displayIds == displayIds
        ) { "EGL coordinator differs from the signed renderer implementation" }
        instrumentOwner.bindPrivateReplayCallback { proof, replay ->
            authority?.onInstrumentClusterReplayPresented(this, proof, replay)
                ?: InstrumentReplayPermitDecision.REVOKED
        }
        val armed = UltraThemeLayoutIntentController.arm(
            coordinator, displayIds.map(::UltraUiSyncDisplayId), localCanvasOwner,
            instrumentOwner = instrumentOwner,
            localCanvasPainter = { contentId ->
                CarPlayUltraCustomCanvas.painterFor(this, contentId)
            },
            diagnosticFallbackEnabled = diagnosticFallbackEnabled,
            foregroundRequests = context.foregroundRequests,
            onClusterPresented = { proof ->
                // An Instrument(CLUSTER) scene is permitted only through the dynamic content
                // receipt path, including its first SCENE_SUBMISSION replay. The generic static
                // ticket has no source freshness or texture-content proof.
                if (proof.scene.layers.none { it.source is UltraSceneSource.Instrument })
                    authority?.onClusterScenePresented(this, proof)
            },
            onMainPresented = { proof ->
                // Only the signed renderer scene ticket can nominate MAIN for its Host gate.
                // Decoder frames and TextureView callbacks are not scene presentation proof.
                authority?.onMainScenePresented(this, proof)
            },
            beginMainSemanticCover = { batch, displayId, revision ->
                authority?.beginMainSemanticCover(this, batch, displayId, revision)
            },
            resumeMainAfterSignedPhoneVisualChoice = { batch, displayId, revision ->
                authority?.resumeMainAfterSignedPhoneVisualChoice(this, batch, displayId, revision)
                    ?: false
            },
        )
        synchronized(lock) {
            check(!closeRequested && controller == null)
            controller = armed
        }
        val snapshot = coordinator.stateSnapshot()
        val subscribed = snapshot.presentationSubscriptionCount == displayIds.size &&
            !armed.stateSnapshot().failed && !armed.stateSnapshot().closed
        val backendLive = coordinator.isLive && snapshot.phase !=
            AndroidUltraRendererSessionPhase.RETIREMENT_UNCERTAIN
        if (contract != null) {
            check(subscribed && backendLive &&
                contract.semanticThemeProgramFingerprint == fingerprint &&
                contract.displayIds == displayIds
            ) { "Verified renderer session lacks a live EGL backend or complete replay" }
        }
        synchronized(lock) {
            issuedReceipt = context.issueReadinessReceipt(
                fingerprint, emptySet(), emptySet(),
                semanticCompilerActive = true,
                themeCompilerActive = contract != null && subscribed,
                backendLive = backendLive,
            )
        }
        // The authority independently records exact child/coordinator identity. Before issuer
        // seal it merely tombstones the probe; after seal it retains a revocable registration.
        synchronized(attachmentLifecycle) {
            check(!synchronized(lock) { closeRequested }) {
                "Renderer child closed before Canvas attachment"
            }
            val registered = authority?.registerRendererSessionIfSealed(this, coordinator)
            var attached: Closeable? = null
            var frameAttached: Closeable? = null
            try {
                // A pre-seal construction probe has no registration and must never become a
                // public painter target. Publication is atomic with respect to child close.
                check(!synchronized(lock) { closeRequested }) {
                    "Renderer child closed during authority registration"
                }
                attached = registered?.let { CarPlayUltraCustomCanvas.attach(this) }
                // The disposable pre-manifest probe has no registration. Only the one sealed
                // AirPlay child may subscribe to its exact Host sink's diagnostic release path.
                frameAttached = registered?.let {
                    videoFrameReleaseChannel?.attach(this, ::observeVideoFrameRelease)
                }
                synchronized(lock) {
                    check(!closeRequested) { "Renderer child closed during Canvas attachment" }
                    registration = registered
                    customCanvasAttachment = attached
                    videoFrameReleaseAttachment = frameAttached
                }
            } catch (failure: Throwable) {
                runCatching { frameAttached?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { attached?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { registered?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    override val isLive: Boolean get() = synchronized(lock) {
        !closeRequested && !closed && controller != null &&
            controller?.stateSnapshot()?.failed == false && coordinator.isLive
    }

    /** Authority-only issuer check; CustomCanvas and WebDisplay cannot mint this proof. */
    internal fun acceptsInstrumentContentProof(proof: SignedInstrumentContentProof): Boolean =
        instrumentOwner.issuedContentProof(proof)

    /** Decoder release is diagnostic only: it never arms UI Sync or schedules a scene. */
    private fun observeVideoFrameRelease(receipt: AndroidVideoFrameReleaseReceipt) =
        videoFrameGate.offer(receipt)

    /**
     * An optional, uncounted observer of a decoder release that this exact child and coordinator
     * have already accepted. It cannot observe a pre-seal probe or attach to a route-equivalent
     * batch. All callbacks run on the independent frame-identity worker, never MediaCodec.
     */
    internal fun attachReadOnlyVideoReleaseObserver(
        batch: UiSyncPhysicalOutputBatch,
        observer: (AndroidVideoFrameReleaseReceipt) -> Unit,
    ): Closeable? {
        if (videoFrameIdentityAdmission(batch) != VideoFrameIdentityAdmission.CURRENT) return null
        val token = Any()
        synchronized(lock) {
            if (closeRequested || closed || activatedPhysicalBatch !== batch ||
                diagnosticVideoObserver != null
            ) return null
            diagnosticVideoObserver = observer
            diagnosticVideoObserverToken = token
        }
        return Closeable {
            synchronized(lock) {
                if (diagnosticVideoObserverToken === token) {
                    diagnosticVideoObserver = null
                    diagnosticVideoObserverToken = null
                }
            }
        }
    }

    private fun forwardVideoFrameRelease(receipt: AndroidVideoFrameReleaseReceipt) {
        if (coordinator.acceptVideoFrameReleaseReceipt(receipt) ==
            RemoteFrameIdentityDisposition.UNMATCHED
        ) return
        val selected = synchronized(lock) {
            val batch = activatedPhysicalBatch
            if (closeRequested || batch == null) null else
                diagnosticVideoObserver?.let { batch to it }
        } ?: return
        if (videoFrameIdentityAdmission(selected.first) == VideoFrameIdentityAdmission.CURRENT) {
            // A diagnostic failure cannot perturb the exact decoder/EGL identity path.
            runCatching { selected.second(receipt) }
        }
    }

    private fun videoFrameIdentityAdmission(
        batch: UiSyncPhysicalOutputBatch,
    ): VideoFrameIdentityAdmission {
        val owner = authority ?: return VideoFrameIdentityAdmission.REVOKED
        // The batch carries the existing typed AA05 + target + backend ledger. A Boolean
        // ownsBoundBatch miss by itself cannot distinguish an in-flight BUSY from retirement.
        when (runCatching { batch.generationStatus }
                .getOrDefault(UiSyncPhysicalGenerationStatus.REVOKED)) {
            UiSyncPhysicalGenerationStatus.REVOKED -> return VideoFrameIdentityAdmission.REVOKED
            UiSyncPhysicalGenerationStatus.BUSY -> return VideoFrameIdentityAdmission.BUSY_RETRY
            UiSyncPhysicalGenerationStatus.CURRENT -> Unit
        }
        if (runCatching { owner.ownsBoundBatch(this, batch) }.getOrDefault(false)) {
            return VideoFrameIdentityAdmission.CURRENT
        }
        return when (runCatching { batch.generationStatus }
                .getOrDefault(UiSyncPhysicalGenerationStatus.REVOKED)) {
            UiSyncPhysicalGenerationStatus.REVOKED -> VideoFrameIdentityAdmission.REVOKED
            UiSyncPhysicalGenerationStatus.BUSY,
            UiSyncPhysicalGenerationStatus.CURRENT -> VideoFrameIdentityAdmission.BUSY_RETRY
        }
    }

    override fun prepareScreen(context: UiSyncRendererScreenContext): UiSyncRendererScreenCandidate? {
        check(isLive) { "Static theme renderer child is closed" }
        val candidate = coordinator.prepareScreen(context) ?: return null
        // A construction probe never prepares a screen. The real child's one exact candidate
        // keeps its stream/ingress identity and retirement owner while adding only the pending
        // scene retry after the SETUP-flush publication commits a new graph revision.
        return if (contract == null || authority == null) candidate else
            PendingThemeRetryScreenCandidate(
                coordinator::graphSnapshot,
                coordinator::sealSemanticRendererFailure,
                context.ingress, candidate,
            ) { authority.retryPendingSceneActivation(this) }
    }

    override fun retireScreens(): Boolean = coordinator.retireScreens()

    /**
     * Custom pixels are admitted only through a signed, child-owned local slot handle.
     * Canvas callbacks are always asynchronous on the owner's producer thread. This does not
     * certify gauge semantics, grant OEM trust, or synthesize a UI Sync completion.
     */
    internal fun drawSignedLocalCanvas(
        handle: UltraLocalCanvasSlotOwner.Handle,
        painter: (Canvas, PixelSize) -> Unit,
    ): CompletableFuture<Unit> {
        check(isLive) { "Static theme renderer child is closed" }
        return localCanvasOwner.draw(handle, painter)
    }

    /** Product customization entry point: resolve only the currently selected signed slot. */
    override fun drawSignedLocalCanvas(
        contentId: UltraContentId,
        painter: (Canvas, PixelSize) -> Unit,
    ): CompletableFuture<Unit> {
        check(isLive) { "Static theme renderer child is closed" }
        val selected = checkNotNull(synchronized(lock) { controller }) {
            "Static theme renderer has no signed layout controller"
        }.currentSignedLocalCanvasHandle(contentId)
        checkNotNull(selected) { "Local Canvas content is not the active signed slot" }
        return localCanvasOwner.draw(selected, painter)
    }

    /**
     * Post-bind hook used by the fixed physical authority only after shared installs this
     * exact child-bound batch. A route-equivalent batch is insufficient. A submitted scene is
     * still neither a phone ACK nor proof that all pixels reached a physical panel.
     */
    internal fun activateAfterPhysicalBind(
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
        graph: UltraDisplayGraphSnapshot,
        outputBindings: Collection<UltraThemeOutputSceneBinding>,
        batch: UiSyncPhysicalOutputBatch,
    ): UltraThemeLayoutActivationResult {
        check(isLive && authority?.authorizesSceneActivation(
            this, batch, provisioning, profile,
        ) == true) {
            "Static theme activation requires this child’s exact current AA05 physical batch"
        }
        val result = checkNotNull(synchronized(lock) { controller }).activate(
            provisioning, profile, graph, outputBindings, batch,
        )
        videoFrameGate.bind(batch)
        synchronized(lock) {
            check(!closeRequested && (activatedPhysicalBatch == null ||
                activatedPhysicalBatch === batch)) {
                "Static theme child changed its exact physical batch"
            }
            activatedPhysicalBatch = batch
        }
        return result
    }

    /** Authority-only resend of the current signed scene after an acknowledged MAIN miss. */
    internal fun reproveMainSceneAfterMiss(
        graph: UltraDisplayGraphSnapshot,
        batch: UiSyncPhysicalOutputBatch,
        expectedRevision: Long,
    ): UltraThemeMainReproofResult {
        val current = synchronized(lock) {
            if (closeRequested || closed || activatedPhysicalBatch !== batch) null else controller
        } ?: return UltraThemeMainReproofResult.REVOKED
        return current.reproveMainSceneAfterMiss(graph, batch, expectedRevision)
    }

    override fun close() {
        synchronized(attachmentLifecycle) {
            synchronized(lock) {
                closeRequested = true
                diagnosticVideoObserver = null
                diagnosticVideoObserverToken = null
                activatedPhysicalBatch = null
                if (closed) return
                check(!closeInFlight) { "Static theme renderer child close is in flight" }
                closeInFlight = true
            }
            videoFrameGate.close()
            try {
                if (!videoFrameReleaseDetached) {
                    videoFrameReleaseAttachment?.close()
                    synchronized(lock) {
                        videoFrameReleaseDetached = true
                    }
                }
                if (!customCanvasDetached) {
                    customCanvasAttachment?.close()
                    synchronized(lock) { customCanvasDetached = true }
                }
            } catch (failure: Throwable) {
                synchronized(lock) { closeInFlight = false }
                throw failure
            }
        }
        try {
            if (!localCanvasRevoked) {
                localCanvasOwner.revoke()
                synchronized(lock) { localCanvasRevoked = true }
            }
            if (!controllerClosed) {
                controller?.close()
                synchronized(lock) { controllerClosed = true }
            }
            if (!instrumentOwnerClosed) {
                instrumentOwner.close()
                synchronized(lock) { instrumentOwnerClosed = true }
            }
            if (!localCanvasClosed) {
                localCanvasOwner.close()
                synchronized(lock) { localCanvasClosed = true }
            }
            if (!coordinatorClosed) {
                coordinator.close()
                synchronized(lock) { coordinatorClosed = true }
            }
            if (!registrationClosed) {
                registration?.close()
                synchronized(lock) { registrationClosed = true }
            }
            synchronized(lock) { closed = true }
            onClosed(this)
        } finally {
            synchronized(lock) { closeInFlight = false }
        }
    }
}

internal class PendingThemeRetryScreenCandidate(
    private val graphSnapshot: () -> UltraDisplayGraphSnapshot,
    private val sealFailure: (Throwable) -> Unit,
    private val expectedIngress: UltraDisplayGraphIngress,
    private val delegate: UiSyncRendererScreenCandidate,
    private val retryPending: () -> Unit,
) : UiSyncRendererScreenCandidate {
    override val stream get() = delegate.stream
    override val ingressId get() = delegate.ingressId

    override fun publish(): Boolean {
        val before = graphSnapshot()
        if (!delegate.publish()) return false
        val after = graphSnapshot()
        if (after.ingress(expectedIngress.id) !== expectedIngress) {
            val failure = IllegalStateException(
                "Published video ingress is not the exact renderer graph candidate",
            )
            sealFailure(failure)
            throw failure
        }
        if (before.ingress(expectedIngress.id) !== expectedIngress) {
            // Authority checks its still-pending exact child/batch/AA05 and rereads the current
            // graph. An exception revokes authority; shared then retires this same candidate.
            retryPending()
        }
        return true
    }

    override fun close() = delegate.close()
}
