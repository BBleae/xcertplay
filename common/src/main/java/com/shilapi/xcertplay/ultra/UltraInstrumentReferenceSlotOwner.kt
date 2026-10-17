package com.shilapi.xcertplay.ultra

import android.os.SystemClock
import com.shilapi.xcertplay.AuthorizedInstrumentSignalSession
import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.UltraProfileSceneUseAttempt
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncInstrumentReferenceAssignment
import com.shilapi.xcertplay.sameInstrumentSignalSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.ultra.instrument.InstrumentRasterOutcome
import com.shilapi.xcertplay.ultra.instrument.InstrumentRasterOwner
import com.shilapi.xcertplay.ultra.instrument.InstrumentCommittedContent
import com.shilapi.xcertplay.ultra.instrument.InstrumentReferenceScenePlan
import com.shilapi.xcertplay.ultra.instrument.InstrumentSceneCompilation
import com.shilapi.xcertplay.ultra.instrument.InstrumentSceneCompiler
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.egl.UltraEglClusterReplayReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleState
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One child-owned Debug CLUSTER reference. It never accepts a caller-provided reading, view,
 * Surface, or timestamp. A fresh signed selection is checked by the theme executor before any
 * scene can use its lease. This owner only supplies a full-frame raster and source lifecycle.
 */
internal class UltraInstrumentReferenceSlotOwner(
    private val coordinator: AndroidUltraRendererSessionCoordinator,
    /** Monotonic clock for post-RESET recovery deadlines; injectable only for tests. */
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * Boot-relative clock shared with Android source acquisition ages. Product keeps the platform
     * clock (null = fail closed, never numeric). JVM tests may supply the bundle's own fallback.
     */
    private val bootClockNanos: () -> Long? = {
        runCatching { SystemClock.elapsedRealtimeNanos() }.getOrNull()
    },
) : Closeable {
    private val lock = Any()
    private val worker = ScheduledThreadPoolExecutor(1) { command ->
        Thread(command, "ultra-reference-instrument").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val sourceWorker = Executors.newSingleThreadExecutor { command ->
        Thread(command, "ultra-reference-source-open").apply { isDaemon = true }
    }
    @Volatile private var active: Handle? = null
    private var latestProof: SignedInstrumentContentProof? = null
    private var privateReplayCallback:
        ((SignedInstrumentContentProof, UltraEglClusterReplayReceipt) ->
            InstrumentReplayPermitDecision)? = null
    @Volatile private var closed = false

    /** EGL swap/finish only; never optical scan-out, phone foreground, or OEM gauge readiness. */
    internal data class FirstFrameReceipt(
        val renderRevision: Long,
        val destination: DisplayDestination,
        val viewportPixels: PixelSize,
        val physicalBatchGeneration: Long,
    )

    internal class Handle(
        val provisioning: AuthorizedUltraProfileProvisioning,
        val profile: VerifiedUltraProfile,
        val assignment: CompiledInstrumentSignalDisplayAssignment,
        val reference: CompiledUiSyncInstrumentReferenceAssignment,
        val batch: UiSyncPhysicalOutputBatch,
        val plan: InstrumentReferenceScenePlan,
        val lease: AndroidUltraInstrumentRasterLease,
        val port: InstrumentEglRasterPort,
        val raster: InstrumentRasterOwner,
    ) : Closeable {
        @Volatile var session: AuthorizedInstrumentSignalSession? = null
        @Volatile var replaySubscription: Closeable? = null
        @Volatile var closeRequested = false
        val pendingPresent = AtomicBoolean(false)
        val sourceOpenStarted = AtomicBoolean(false)
        val pendingReplay = AtomicReference<UltraEglClusterReplayReceipt?>(null)
        val replayDrainScheduled = AtomicBoolean(false)
        @Volatile var supersededThroughReplaySequence: Long = 0L
        var lastDisplayed: InstrumentSignalBundleState? = null
        var expiryTask: ScheduledFuture<*>? = null
        @Volatile var firstFrameReceipt: FirstFrameReceipt? = null
        @Volatile var latestSceneRevision: Long = -1L
        @Volatile var initialSafetyClearSequence: Long = 0L
        /** A phone reset keeps this ingress, but invalidates its old numeric lineage. */
        @Volatile var phoneEpoch = 0L
        var postResetSceneRevisionFloor = 0L
        var postResetFrameSequenceFloor = 0L
        var postResetSafetyClearSequence = 0L
        @Volatile var awaitingPostResetSafetyClearArm = false
        /** The post-RESET safety clear must commit before this, or the batch goes terminal. */
        var postResetClearDeadlineNanos = 0L
        var sourceRetryDelayMillis = 100L
        val binding: UltraThemeInstrumentReferenceBinding
            get() = UltraThemeInstrumentReferenceBinding(
                reference.slot, lease, plan, assignment,
            )

        override fun close() {
            closeRequested = true
            expiryTask?.cancel(false)
            var failure: Throwable? = null
            runCatching { replaySubscription?.close() }.exceptionOrNull()?.let { failure = it }
            runCatching { session?.close() }.exceptionOrNull()?.let { problem ->
                if (failure == null) failure = problem else failure?.addSuppressed(problem)
            }
            val beforeClear = port.committedFrameCount
            runCatching { raster.close() }.exceptionOrNull()?.let { problem ->
                if (failure == null) failure = problem else failure?.addSuppressed(problem)
            }
            if (port.committedFrameCount > beforeClear &&
                port.lastCommitResult == com.shilapi.xcertplay.ultra.instrument
                    .InstrumentFrameCommitResult.COMMITTED_CURRENT
            ) port.noteSafetyClear()
            runCatching { port.close() }.exceptionOrNull()?.let { problem ->
                if (failure == null) failure = problem else failure?.addSuppressed(problem)
            }
            failure?.let { throw it }
        }
    }

    fun ensure(
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
        signal: CompiledInstrumentSignalDisplayAssignment,
        reference: CompiledUiSyncInstrumentReferenceAssignment,
        batch: UiSyncPhysicalOutputBatch,
        destination: DisplayDestination,
        viewportPixels: PixelSize,
    ): Handle = synchronized(lock) {
        check(!closed && provisioning.isLive && batch.isCurrent && coordinator.isLive)
        require(destination.role == DisplayDestinationRole.CLUSTER &&
            signal.spec.address.slot == reference.slot &&
            sameInstrumentSignalSpec(signal.spec, reference.spec) &&
            provisioning.instrumentAssignmentsFor(profile).any { it === signal }
        ) { "Reference instrument is not this selected signed CLUSTER crosswalk" }
        val compiled = InstrumentSceneCompiler().compileSigned(
            provisioning, profile, signal.profileDisplayIndex, reference.slot,
            destination, viewportPixels,
        )
        val plan = (compiled as? InstrumentSceneCompilation.Ready)?.plan
            ?: throw IllegalArgumentException("Signed reference instrument is not executable: $compiled")
        require(plan.spec === signal.spec && plan.destination == destination &&
            plan.viewportPixels == viewportPixels
        ) { "Reference plan differs from the exact issuer signal assignment or viewport" }

        active?.takeIf {
            !it.closeRequested && it.provisioning === provisioning && it.profile === profile &&
                it.assignment === signal && it.reference == reference && it.batch === batch &&
                it.plan.viewportPixels == viewportPixels &&
                coordinator.isCurrentInstrumentRaster(it.lease, destination, viewportPixels)
        }?.let { return@synchronized it }

        // A different reference cannot share the same GL texture key. Clear and retire the old
        // child before opening its replacement; failure leaves no new numeric pixels authorized.
        active?.close()
        active = null
        val lease = coordinator.openInstrumentRaster(destination, viewportPixels)
        val port = InstrumentEglRasterPort(coordinator, lease, batch)
        val raster = try { InstrumentRasterOwner(plan, port) } catch (failure: Throwable) {
            runCatching { port.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val handle = Handle(provisioning, profile, signal, reference, batch, plan, lease,
            port, raster)
        try {
            check(raster.presentUnavailable() == InstrumentRasterOutcome.UNAVAILABLE_CLEARED) {
                "Initial BENCH-marked unavailable instrument frame did not upload"
            }
            port.noteSafetyClear()
            handle.initialSafetyClearSequence =
                (raster.committedContentSnapshot() as?
                    InstrumentCommittedContent.SafetyClear)?.frameSequence ?: 0L
            check(handle.initialSafetyClearSequence > 0L) {
                "Initial safety clear has no exact instrument upload sequence"
            }
            active = handle
            handle.replaySubscription = coordinator.subscribeClusterReplay(
                lease,
                { receipt -> enqueueClusterReplay(handle, receipt) },
                { enqueueReplayRevocation(handle) },
                { receipt -> enqueueSupersededReplay(handle, receipt) },
            )
            // Keep the first actual CLUSTER scene an unavailable BENCH frame. The CAF source
            // is opened off-lock only after a sealed replay proves that clear was swapped and
            // finished; an upload alone does not establish a physical output frame.
            handle
        } catch (failure: Throwable) {
            // A failed upload or retryable EGL retirement must retain this exact handle.
            // Dropping it here would allow a later `ensure` to collide with an orphaned key.
            active = handle
            val released = runCatching { handle.close() }
            if (released.isSuccess) active = null
            else released.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun launchSourceOpen(handle: Handle) {
        runCatching { sourceWorker.execute { openSource(handle) } }
    }

    private fun openSource(handle: Handle) {
        if (synchronized(lock) { closed || active !== handle || handle.closeRequested }) return
        val opened = runCatching {
            handle.provisioning.openInstrumentSignalSession(
                handle.profile, handle.assignment, handle.batch,
            ) { _ -> schedulePresent(handle) }
        }
        val session = opened.getOrNull()
        if (session != null) {
            val retained = synchronized(lock) {
                if (closed || active !== handle || handle.closeRequested) false else {
                    handle.session = session
                    true
                }
            }
            if (retained) schedulePresent(handle) else session.close()
            return
        }
        // A temporary coordinator BUSY or unavailable read source leaves only the already
        // uploaded DATA UNAVAILABLE frame. Retry off the controller/render thread while exact
        // AA05 and physical generations remain current; never invent a reading.
        val delay = synchronized(lock) {
            if (closed || active !== handle || handle.closeRequested ||
                authorityRevoked(handle)
            ) return
            handle.sourceRetryDelayMillis.also {
                handle.sourceRetryDelayMillis = (it * 2L).coerceAtMost(1_000L)
            }
        }
        runCatching { worker.schedule({ launchSourceOpen(handle) }, delay,
            TimeUnit.MILLISECONDS) }
    }

    /** A source can age out without a callback; poll only metadata and redraw on state change. */
    private fun schedulePresent(handle: Handle) {
        if (!handle.pendingPresent.compareAndSet(false, true)) return
        try {
            worker.execute {
                handle.pendingPresent.set(false)
                presentIfChanged(handle, force = true)
            }
        } catch (_: RejectedExecutionException) {
            handle.pendingPresent.set(false)
        }
    }

    private fun presentIfChanged(handle: Handle, force: Boolean) {
        val (session, epoch) = synchronized(lock) {
            if (closed || active !== handle || handle.closeRequested ||
                handle.awaitingPostResetSafetyClearArm
            ) return
            (handle.session ?: return) to handle.phoneEpoch
        }
        // A phone RESET on the RCS thread may land between this check and the raster submit.
        // The raster re-checks this exact epoch before and after its numeric commit, so a racing
        // frame becomes a SOURCE REVOKED/DATA UNAVAILABLE clear instead of a numeric value.
        // Lock-free on purpose: the raster calls this while holding its own monitor, and
        // ensure() closes a replaced raster while holding [lock]. All fields are written under
        // [lock] and are @Volatile, so a completed RESET is always observed (fail closed).
        val epochCurrent = {
            !closed && active === handle && !handle.closeRequested &&
                handle.phoneEpoch == epoch && !handle.awaitingPostResetSafetyClearArm
        }
        val state = session.snapshot()
        if (!epochCurrent()) return
        if (!force && sameState(handle.lastDisplayed, state) &&
            (handle.port.hasSafetyClear ||
                (handle.port.lastCommitResult == com.shilapi.xcertplay.ultra.instrument
                    .InstrumentFrameCommitResult.COMMITTED_CURRENT && handle.port.isCurrent))
        ) return
        // An AVAILABLE attempt may commit before its generation changes to BUSY. Do not let
        // the earlier unavailable clear certify that possibly visible numeric frame.
        if (state is InstrumentSignalBundleState.Available) handle.port.noteAvailable()
        val outcome = handle.raster.present(session, epochCurrent, bootClockNanos)
        if (outcome != InstrumentRasterOutcome.COALESCED_AVAILABLE) synchronized(lock) {
            if (active === handle) latestProof = null
        }
        // The queued post-RESET safety clear owns lastDisplayed/expiry for the new epoch.
        if (!epochCurrent()) {
            if (outcome == InstrumentRasterOutcome.RETIREMENT_PENDING)
                handle.port.requestPhysicalRetirement()
            return
        }
        when (outcome) {
            InstrumentRasterOutcome.PORT_REJECTED,
            InstrumentRasterOutcome.COALESCED_AVAILABLE -> Unit // next bounded tick
            InstrumentRasterOutcome.COMMIT_BUSY -> {
                // A stale/unavailable clear cannot wait indefinitely behind BUSY while the
                // former numeric frame remains on glass. Retire the whole old output now.
                if (state is InstrumentSignalBundleState.Unavailable &&
                    !handle.port.hasSafetyClear)
                    handle.port.requestPhysicalRetirement()
            }
            InstrumentRasterOutcome.RETIREMENT_PENDING ->
                handle.port.requestPhysicalRetirement()
            InstrumentRasterOutcome.AVAILABLE_REFERENCE -> {
                handle.port.noteAvailable()
                handle.lastDisplayed = state
                scheduleExactExpiry(handle, state)
            }
            InstrumentRasterOutcome.UNAVAILABLE_CLEARED -> {
                handle.port.noteSafetyClear()
                handle.lastDisplayed = state
                scheduleExactExpiry(handle, state)
            }
            InstrumentRasterOutcome.REVOKED_CLEARED -> {
                if (handle.port.lastCommitResult == com.shilapi.xcertplay.ultra.instrument
                    .InstrumentFrameCommitResult.OUTPUT_RETIRED
                ) {
                    val released = runCatching { handle.close() }.isSuccess
                    if (released) synchronized(lock) {
                        if (active === handle) active = null
                    }
                } else {
                    handle.port.noteSafetyClear()
                    handle.lastDisplayed = state
                }
            }
        }
    }

    private fun scheduleExactExpiry(handle: Handle, state: InstrumentSignalBundleState) {
        handle.expiryTask?.cancel(false)
        handle.expiryTask = null
        val available = state as? InstrumentSignalBundleState.Available ?: return
        val now = runCatching(bootClockNanos).getOrNull() ?: return
        val oldestSourceAge = available.sourceAgeMillisByRole.values.maxOrNull() ?: return
        val remainingMillis = handle.plan.spec.maximumReadAgeMillis - oldestSourceAge
        val deadline = available.readAtNanos + TimeUnit.MILLISECONDS.toNanos(remainingMillis)
        val delayMillis = TimeUnit.NANOSECONDS.toMillis((deadline - now).coerceAtLeast(0L)) + 1L
        handle.expiryTask = worker.schedule({ presentIfChanged(handle, force = true) },
            delayMillis.coerceAtMost(handle.plan.spec.maximumReadAgeMillis + 1L),
            TimeUnit.MILLISECONDS)
    }

    private fun sameState(
        left: InstrumentSignalBundleState?,
        right: InstrumentSignalBundleState,
    ): Boolean = if (left is InstrumentSignalBundleState.Available &&
        right is InstrumentSignalBundleState.Available
    ) left === right else left == right

    private fun enqueueClusterReplay(handle: Handle, receipt: UltraEglClusterReplayReceipt) {
        if (handle.closeRequested) return
        handle.pendingReplay.updateAndGet { prior ->
            if (prior == null || receipt.replaySequence > prior.replaySequence) receipt else prior
        }
        if (!handle.replayDrainScheduled.compareAndSet(false, true)) return
        runCatching { worker.execute { drainClusterReplay(handle) } }.onFailure {
            handle.replayDrainScheduled.set(false)
        }
    }

    private fun drainClusterReplay(handle: Handle) {
        try {
            while (!handle.closeRequested) {
                val next = handle.pendingReplay.getAndSet(null) ?: return
                if (synchronized(lock) { !closed && active === handle })
                    acceptClusterReplay(next)
            }
        } finally {
            handle.replayDrainScheduled.set(false)
            if (handle.pendingReplay.get() != null && !handle.closeRequested &&
                handle.replayDrainScheduled.compareAndSet(false, true)
            ) runCatching { worker.execute { drainClusterReplay(handle) } }.onFailure {
                handle.replayDrainScheduled.set(false)
            }
        }
    }

    private fun enqueueReplayRevocation(handle: Handle) {
        runCatching { worker.execute {
            synchronized(lock) { if (active === handle) latestProof = null }
        } }
    }

    /** Dropped old replay is nonterminal: invalidate its cover proof, retain this subscription. */
    private fun enqueueSupersededReplay(
        handle: Handle,
        receipt: UltraEglClusterReplayReceipt,
    ) {
        runCatching { worker.execute {
            synchronized(lock) {
                if (closed || active !== handle || handle.closeRequested) return@synchronized
                handle.supersededThroughReplaySequence = maxOf(
                    handle.supersededThroughReplaySequence, receipt.replaySequence)
                if (latestProof?.let { it.replaySequence <= receipt.replaySequence } == true)
                    latestProof = null
            }
            handle.pendingReplay.updateAndGet { pending ->
                pending?.takeIf { it.replaySequence > receipt.replaySequence }
            }
        } }
    }

    /** The renderer child alone installs this private path after its authority registration. */
    internal fun bindPrivateReplayCallback(
        callback: (SignedInstrumentContentProof, UltraEglClusterReplayReceipt) ->
            InstrumentReplayPermitDecision,
    ) = synchronized(lock) {
        check(!closed && privateReplayCallback == null)
        privateReplayCallback = callback
    }

    internal fun issuedContentProof(proof: SignedInstrumentContentProof): Boolean =
        proof.issuer === this && synchronized(lock) {
            val handle = proof.handle
            !closed && active === handle && !handle.closeRequested &&
                postResetProofAdmissibleLocked(handle, proof)
        }

    /** Fence old numeric/clear uploads immediately; a new opaque clear is uploaded off-thread. */
    internal fun invalidatePhoneEpoch(
        destination: DisplayDestination,
        sceneRevisionFloor: Long,
    ): Boolean {
        if (destination.role != DisplayDestinationRole.CLUSTER || sceneRevisionFloor < 0L)
            return false
        val pending = synchronized(lock) {
            if (closed) return false
            val handle = active?.takeIf { it.plan.destination == destination }
                ?: return true // This CLUSTER has no selected instrument reference.
            handle.phoneEpoch = Math.addExact(handle.phoneEpoch, 1L)
            handle.postResetSceneRevisionFloor = maxOf(
                handle.postResetSceneRevisionFloor, sceneRevisionFloor)
            handle.postResetFrameSequenceFloor = maxOf(
                handle.postResetFrameSequenceFloor, handle.port.committedFrameCount)
            handle.postResetSafetyClearSequence = 0L
            handle.awaitingPostResetSafetyClearArm = true
            handle.postResetClearDeadlineNanos =
                nanoTime() + POST_RESET_SAFETY_CLEAR_COMMIT_WINDOW_NANOS
            handle.initialSafetyClearSequence = 0L
            handle.firstFrameReceipt = null
            handle.pendingReplay.set(null)
            handle.expiryTask?.cancel(false)
            handle.expiryTask = null
            handle.lastDisplayed = null
            latestProof = null
            handle to handle.phoneEpoch
        }
        return schedulePostResetSafetyClear(pending.first, pending.second)
    }

    private fun schedulePostResetSafetyClear(handle: Handle, epoch: Long): Boolean {
        val scheduled = runCatching {
            worker.execute { commitPostResetSafetyClear(handle, epoch) }
        }.isSuccess
        if (!scheduled) runCatching { handle.port.requestPhysicalRetirement() }
        return scheduled
    }

    private fun commitPostResetSafetyClear(handle: Handle, epoch: Long) {
        if (synchronized(lock) {
                closed || active !== handle || handle.closeRequested ||
                    handle.phoneEpoch != epoch || !handle.awaitingPostResetSafetyClearArm
            }) return
        if (runCatching { handle.batch.generationStatus }.getOrNull() ==
            UiSyncPhysicalGenerationStatus.REVOKED
        ) {
            handle.port.requestPhysicalRetirement()
            return
        }
        // A transient BUSY generation is not proof that the old ingress is revoked. The Host
        // cover stays in place while we wait for a safe complete-frame upload.
        val outcome = if (runCatching { handle.port.isCurrent }.getOrDefault(false))
            handle.raster.presentUnavailable() else null
        val committed = if (outcome == InstrumentRasterOutcome.UNAVAILABLE_CLEARED) {
            val sequence = (handle.raster.committedContentSnapshot() as?
                InstrumentCommittedContent.SafetyClear)?.frameSequence ?: 0L
            synchronized(lock) {
                if (closed || active !== handle || handle.phoneEpoch != epoch ||
                    sequence <= handle.postResetFrameSequenceFloor
                ) false else {
                    handle.port.noteSafetyClear()
                    handle.initialSafetyClearSequence = sequence
                    handle.postResetSafetyClearSequence = sequence
                    true
                }
            }
        } else false
        if (committed) return
        if (outcome == InstrumentRasterOutcome.RETIREMENT_PENDING ||
            handle.port.lastCommitResult == com.shilapi.xcertplay.ultra.instrument
                .InstrumentFrameCommitResult.OUTPUT_RETIRED
        ) {
            handle.port.requestPhysicalRetirement()
            return
        }
        val retry = synchronized(lock) {
            !closed && active === handle && !handle.closeRequested &&
                handle.phoneEpoch == epoch && handle.awaitingPostResetSafetyClearArm
        }
        if (!retry) return
        // PORT_REJECTED, persistent BUSY, or a non-current port cannot keep the CLUSTER covered
        // forever while the channel looks recoverable. Bounded, then terminal protection.
        val expired = nanoTime() - handle.postResetClearDeadlineNanos >= 0L
        if (expired || runCatching {
                worker.schedule({ commitPostResetSafetyClear(handle, epoch) }, 200L,
                    TimeUnit.MILLISECONDS)
            }.isFailure
        ) handle.port.requestPhysicalRetirement()
    }

    /** Caller holds [lock]. A pre-reset clear or numeric upload never crosses the new epoch. */
    private fun postResetProofAdmissibleLocked(
        handle: Handle,
        proof: SignedInstrumentContentProof,
    ): Boolean {
        if (handle.phoneEpoch == 0L) return true
        if (proof.sceneRevision <= handle.postResetSceneRevisionFloor ||
            proof.instrumentFrameSequence <= handle.postResetFrameSequenceFloor ||
            handle.postResetSafetyClearSequence <= handle.postResetFrameSequenceFloor ||
            proof.instrumentFrameSequence < handle.postResetSafetyClearSequence
        ) return false
        return !handle.awaitingPostResetSafetyClearArm ||
            (proof.contentKind == SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR &&
                proof.instrumentFrameSequence == handle.postResetSafetyClearSequence)
    }

    private fun acceptClusterReplay(
        receipt: UltraEglClusterReplayReceipt,
        deadlineNanos: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
    ) {
        if (synchronized(lock) {
                active?.supersededThroughReplaySequence?.let {
                    receipt.replaySequence <= it
                } == true ||
                    latestProof?.let { it.replaySequence >= receipt.replaySequence } == true
            }) return
        val selected = synchronized(lock) { active }
        if (selected != null) when (safeReplayAdmissionStatus(selected, receipt)) {
            UiSyncPhysicalGenerationStatus.BUSY -> {
                retryClusterReplayWhileBusy(receipt, deadlineNanos)
                return
            }
            UiSyncPhysicalGenerationStatus.REVOKED -> {
                synchronized(lock) { if (active === selected) latestProof = null }
                return
            }
            UiSyncPhysicalGenerationStatus.CURRENT -> Unit
        }
        val candidate = synchronized(lock) {
            val handle = active ?: return@synchronized null
            if (latestProof?.let { it.replaySequence >= receipt.replaySequence } == true)
                return@synchronized null
            val content = handle.raster.committedContentSnapshot()
            val sequence = when (content) {
                is InstrumentCommittedContent.SafetyClear -> content.frameSequence
                is InstrumentCommittedContent.Numeric -> content.frameSequence
                InstrumentCommittedContent.Unknown -> return@synchronized null
            }
            SignedInstrumentContentProof(this, handle, receipt, content,
                handle.initialSafetyClearSequence > 0L &&
                    handle.initialSafetyClearSequence <= sequence)
        }
        val handoff = candidate?.takeIf {
            runCatching { isCurrentContentProof(it, receipt, requireLatest = false) }
                .getOrDefault(false)
        }
        if (handoff == null) {
            if (selected != null && System.nanoTime() < deadlineNanos &&
                safeReplayAdmissionStatus(selected, receipt) ==
                    UiSyncPhysicalGenerationStatus.BUSY
            ) {
                retryClusterReplayWhileBusy(receipt, deadlineNanos)
                return
            }
            synchronized(lock) {
                if (latestProof?.let { it.replaySequence < receipt.replaySequence } != false)
                    latestProof = null
            }
            return
        }
        val callback = synchronized(lock) {
            if (closed || active !== handoff.handle) null else privateReplayCallback
        } ?: return
        // A coordinator/AA05 operation can enter BUSY between the first admissibility check
        // and publication. Keep the same sealed receipt pending; never cache an unconfirmed
        // proof as latest, because that would suppress its only retry via replaySequence.
        if (!runCatching { isCurrentContentProof(handoff, receipt, requireLatest = false) }
                .getOrDefault(false)) {
            if (safeReplayAdmissionStatus(handoff.handle, receipt) ==
                UiSyncPhysicalGenerationStatus.BUSY
            ) retryClusterReplayWhileBusy(receipt, deadlineNanos)
            return
        }
        val published = synchronized(lock) {
            if (closed || active !== handoff.handle ||
                latestProof?.let { it.replaySequence >= receipt.replaySequence } == true
            ) false else {
                latestProof = handoff
                true
            }
        }
        if (!published) return
        if (!handoff.isCurrentFor(receipt)) {
            synchronized(lock) { if (latestProof === handoff) latestProof = null }
            if (safeReplayAdmissionStatus(handoff.handle, receipt) ==
                UiSyncPhysicalGenerationStatus.BUSY
            ) retryClusterReplayWhileBusy(receipt, deadlineNanos)
            return
        }
        when (runCatching { callback(handoff, receipt) }
            .getOrDefault(InstrumentReplayPermitDecision.REVOKED)) {
            InstrumentReplayPermitDecision.ARMED -> {
                val postResetClearArmed = synchronized(lock) {
                    if (active !== handoff.handle ||
                        !handoff.handle.awaitingPostResetSafetyClearArm ||
                        handoff.contentKind != SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR ||
                        handoff.instrumentFrameSequence !=
                            handoff.handle.postResetSafetyClearSequence ||
                        handoff.sceneRevision <= handoff.handle.postResetSceneRevisionFloor
                    ) false else {
                        handoff.handle.awaitingPostResetSafetyClearArm = false
                        true
                    }
                }
                if (handoff.contentKind == SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR &&
                    handoff.handle.sourceOpenStarted.compareAndSet(false, true)
                ) launchSourceOpen(handoff.handle)
                else if (postResetClearArmed) schedulePresent(handoff.handle)
            }
            InstrumentReplayPermitDecision.BUSY_RETRY -> {
                synchronized(lock) { if (latestProof === handoff) latestProof = null }
                if (handoff.handle.supersededThroughReplaySequence < receipt.replaySequence)
                    retryClusterReplayWhileBusy(receipt, deadlineNanos)
            }
            InstrumentReplayPermitDecision.REVOKED -> {
                synchronized(lock) { if (latestProof === handoff) latestProof = null }
                // Authority owns Host target retirement. A stale scene callback may be
                // REVOKED while a newer scene already uses this child; never retire that
                // current EGL batch from an older replay result.
            }
        }
    }

    private fun retryClusterReplayWhileBusy(
        receipt: UltraEglClusterReplayReceipt,
        deadlineNanos: Long,
    ) {
        val scheduled = System.nanoTime() < deadlineNanos && runCatching {
            worker.schedule({ acceptClusterReplay(receipt, deadlineNanos) }, 25L,
                TimeUnit.MILLISECONDS)
        }.isSuccess
        if (scheduled) return
        // The sole post-RESET safety-clear replay (e.g. still waiting for the exact Host cover
        // ACK) ran out of its bounded window. No later replay will re-arm this CLUSTER, so take
        // the terminal protection path instead of leaving a silently covered, "live" channel.
        val stranded = synchronized(lock) {
            val handle = active ?: return@synchronized null
            handle.takeIf {
                !closed && !it.closeRequested && it.awaitingPostResetSafetyClearArm &&
                    it.postResetSafetyClearSequence > 0L &&
                    receipt.instrumentFrameSequence == it.postResetSafetyClearSequence &&
                    receipt.sceneRevision > it.postResetSceneRevisionFloor &&
                    receipt.replaySequence > it.supersededThroughReplaySequence &&
                    it.pendingReplay.get() == null &&
                    latestProof?.let { proof ->
                        proof.replaySequence > receipt.replaySequence
                    } != true
            }
        }
        stranded?.port?.requestPhysicalRetirement()
    }

    private fun replayAdmissionStatus(
        handle: Handle,
        receipt: UltraEglClusterReplayReceipt,
    ): UiSyncPhysicalGenerationStatus {
        val physical = handle.batch.generationStatus
        val raster = coordinator.instrumentRasterGenerationStatus(handle.lease,
            handle.plan.destination, handle.plan.viewportPixels)
        if (physical == UiSyncPhysicalGenerationStatus.REVOKED ||
            raster == UiSyncPhysicalGenerationStatus.REVOKED
        ) return UiSyncPhysicalGenerationStatus.REVOKED
        val sceneUse = handle.provisioning.tryWithSceneSubmissionUse { Unit }
        if (sceneUse == UltraProfileSceneUseAttempt.Revoked)
            return UiSyncPhysicalGenerationStatus.REVOKED
        if (physical == UiSyncPhysicalGenerationStatus.BUSY ||
            raster == UiSyncPhysicalGenerationStatus.BUSY ||
            sceneUse == UltraProfileSceneUseAttempt.Busy
        ) return UiSyncPhysicalGenerationStatus.BUSY
        val scene = runCatching { coordinator.stateSnapshot() }.getOrNull()
            ?: return UiSyncPhysicalGenerationStatus.BUSY
        if (scene.lastSubmittedRenderRevision != receipt.sceneRevision) {
            return if (scene.phase == AndroidUltraRendererSessionPhase.PREPARING &&
                scene.lastSubmittedRenderRevision < receipt.sceneRevision
            ) UiSyncPhysicalGenerationStatus.BUSY
            else UiSyncPhysicalGenerationStatus.REVOKED
        }
        return UiSyncPhysicalGenerationStatus.CURRENT
    }

    private fun safeReplayAdmissionStatus(
        handle: Handle,
        receipt: UltraEglClusterReplayReceipt,
    ): UiSyncPhysicalGenerationStatus = runCatching {
        replayAdmissionStatus(handle, receipt)
    }.getOrDefault(UiSyncPhysicalGenerationStatus.BUSY)

    internal fun isCurrentContentProof(
        proof: SignedInstrumentContentProof,
        receipt: UltraEglClusterReplayReceipt,
        requireLatest: Boolean = true,
    ): Boolean {
        val handle = proof.handle
        val current = synchronized(lock) {
            !closed && active === handle && !handle.closeRequested &&
                (!requireLatest || latestProof === proof) &&
                handle.latestSceneRevision == receipt.sceneRevision &&
                handle.initialSafetyClearSequence > 0L &&
                postResetProofAdmissibleLocked(handle, proof)
        }
        if (!current || proof.issuer !== this || proof.receipt !== receipt ||
            receipt.destination != handle.plan.destination ||
            receipt.sceneRevision != proof.sceneRevision ||
            receipt.graphRevision == null || receipt.graphRevision != proof.graphRevision ||
            receipt.nativeOutputBatchGeneration != proof.nativeOutputBatchGeneration ||
            receipt.nativeOutputGeneration != proof.nativeOutputGeneration ||
            receipt.instrumentIngressGeneration != proof.instrumentIngressGeneration ||
            receipt.instrumentFrameSequence != proof.instrumentFrameSequence ||
            receipt.producerTimestampNanos != proof.producerTimestampNanos ||
            receipt.replaySequence != proof.replaySequence ||
            receipt.replaySequence <= 0L || receipt.producerTimestampNanos <= 0L ||
            receipt.nativeOutputBatchGeneration <= 0L ||
            receipt.nativeOutputGeneration <= 0L ||
            receipt.instrumentIngressGeneration <= 0L ||
            !receipt.instrumentOnlyClusterScene ||
            !proof.initialSafetyClearEstablished || proof.batch !== handle.batch ||
            proof.profile !== handle.profile || proof.provisioning !== handle.provisioning ||
            handle.batch.generationStatus != UiSyncPhysicalGenerationStatus.CURRENT ||
            coordinator.instrumentRasterGenerationStatus(handle.lease,
                handle.plan.destination, handle.plan.viewportPixels) !=
                    UiSyncPhysicalGenerationStatus.CURRENT ||
            coordinator.graphSnapshot().revision != receipt.graphRevision ||
            coordinator.stateSnapshot().lastSubmittedRenderRevision != receipt.sceneRevision ||
            // The coordinator's reservation generation and the backend EGL ingress
            // generation are distinct namespaces; the sealed replay names EGL's one.
            handle.lease.recordFor(coordinator)?.backendInstrument?.generation !=
                receipt.instrumentIngressGeneration ||
            handle.provisioning.tryWithSceneSubmissionUse { Unit } !is
                UltraProfileSceneUseAttempt.Admitted
        ) return false
        val content = handle.raster.committedContentSnapshot()
        if (content !== proof.content ||
            receipt.instrumentFrameSequence != when (content) {
                is InstrumentCommittedContent.SafetyClear -> content.frameSequence
                is InstrumentCommittedContent.Numeric -> content.frameSequence
                InstrumentCommittedContent.Unknown -> return false
            }
        ) return false
        return when (content) {
            is InstrumentCommittedContent.SafetyClear ->
                proof.contentKind == SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR
            is InstrumentCommittedContent.Numeric -> {
                if (proof.contentKind != SignedInstrumentContentKind.NUMERIC_REFERENCE ||
                    handle.session !== content.session || !content.session.isLive ||
                    content.session.snapshot() !== content.sample
                ) return false
                val now = runCatching(bootClockNanos).getOrNull()
                    ?: return false
                now <= content.expiresAtNanos
            }
            InstrumentCommittedContent.Unknown -> false
        }
    }

    /** Invalidate an older observer before a newer scene can coalesce its ticket inline. */
    fun markSceneReplacing(revision: Long) {
        synchronized(lock) {
            active?.let { handle ->
                handle.latestSceneRevision = revision
                handle.firstFrameReceipt = null
            }
        }
    }

    /** Observe one queued scene without blocking the controller, callbacks, or AA05 teardown. */
    fun observeFirstFrame(handle: Handle, ticket: UltraEglRenderTicket) {
        synchronized(lock) {
            if (closed || active !== handle || handle.closeRequested) return
            handle.latestSceneRevision = ticket.revision
            handle.firstFrameReceipt = null
        }
        InstrumentReferenceFirstFramePoller(worker).observe(
            ticket = ticket,
            stillOwned = { synchronized(lock) { !closed && active === handle &&
                !handle.closeRequested && handle.latestSceneRevision == ticket.revision } },
            revoked = { authorityRevoked(handle) },
            ready = { handle.port.isCurrent },
            onPresented = {
                synchronized(lock) {
                    if (!closed && active === handle && !handle.closeRequested &&
                        handle.latestSceneRevision == ticket.revision
                    ) handle.firstFrameReceipt = FirstFrameReceipt(
                        ticket.revision, handle.plan.destination, handle.plan.viewportPixels,
                        handle.batch.generation,
                    )
                }
            },
            onRejected = { retireFailedFrame(handle, ticket.revision, it) },
        )
    }

    /** A parent probe may temporarily report BUSY; only typed REVOCATION retires pixels. */
    private fun authorityRevoked(handle: Handle): Boolean =
        instrumentSourceShouldRetire(
            handle.batch.generationStatus,
            handle.provisioning.tryWithSceneSubmissionUse { Unit },
        )

    private fun retireFailedFrame(
        handle: Handle,
        expectedRevision: Long,
        failure: Throwable?,
    ) {
        val owned = synchronized(lock) {
            active === handle && handle.latestSceneRevision == expectedRevision
        }
        if (!owned) return
        val releaseFailure = runCatching { handle.close() }.exceptionOrNull()
        if (releaseFailure == null) synchronized(lock) {
            if (active === handle) active = null
        }
        if (failure != null) {
            releaseFailure?.let(failure::addSuppressed)
            coordinator.sealSemanticRendererFailure(failure)
        }
    }

    /** Keep old numeric pixels owned until the replacement scene has actually swapped. */
    fun retireExceptAfterPresented(retained: Handle?, ticket: UltraEglRenderTicket) {
        val retiring = synchronized(lock) { active?.takeIf { it !== retained } } ?: return
        synchronized(lock) {
            if (active !== retiring) return
            retiring.latestSceneRevision = ticket.revision
            retiring.firstFrameReceipt = null
        }
        InstrumentReferenceFirstFramePoller(worker).observe(ticket,
            stillOwned = { synchronized(lock) { !closed && active === retiring &&
                retiring.latestSceneRevision == ticket.revision } },
            revoked = { false }, ready = { true },
            onPresented = {
                val released = runCatching { retiring.close() }.isSuccess
                if (released) synchronized(lock) {
                    if (active === retiring) active = null
                }
            },
            onRejected = { /* failed replacement never authorizes retiring old source */ },
        )
    }

    init {
        worker.scheduleWithFixedDelay({
            val handle = synchronized(lock) { active }
            if (handle != null) runCatching { presentIfChanged(handle, force = false) }
        }, STALE_POLL_MILLIS, STALE_POLL_MILLIS, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        val old = synchronized(lock) {
            closed = true
            active
        }
        worker.shutdownNow()
        sourceWorker.shutdownNow()
        old?.close()
        synchronized(lock) { if (active === old) active = null }
    }

    private companion object {
        const val STALE_POLL_MILLIS = 50L
    }
}

/** Non-public signed child handoff; only its exact owner can keep this proof current. */
internal enum class SignedInstrumentContentKind {
    COMPLETE_SAFETY_CLEAR,
    NUMERIC_REFERENCE,
}

/** Only Authority can declare a private replay permit armed; BUSY keeps the sealed receipt. */
private val POST_RESET_SAFETY_CLEAR_COMMIT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5)

internal enum class InstrumentReplayPermitDecision {
    ARMED,
    BUSY_RETRY,
    REVOKED,
}

internal class SignedInstrumentContentProof internal constructor(
    internal val issuer: UltraInstrumentReferenceSlotOwner,
    internal val handle: UltraInstrumentReferenceSlotOwner.Handle,
    internal val receipt: UltraEglClusterReplayReceipt,
    internal val content: InstrumentCommittedContent,
    val initialSafetyClearEstablished: Boolean,
) {
    val batch: UiSyncPhysicalOutputBatch get() = handle.batch
    val profile: VerifiedUltraProfile get() = handle.profile
    val provisioning: AuthorizedUltraProfileProvisioning get() = handle.provisioning
    val destination: DisplayDestination get() = receipt.destination
    val viewportPixels: PixelSize get() = handle.plan.viewportPixels
    val sceneRevision: Long get() = receipt.sceneRevision
    val graphRevision: Long? get() = receipt.graphRevision
    val nativeOutputBatchGeneration: Long get() = receipt.nativeOutputBatchGeneration
    val nativeOutputGeneration: Long get() = receipt.nativeOutputGeneration
    val instrumentIngressGeneration: Long get() = receipt.instrumentIngressGeneration
    val instrumentFrameSequence: Long get() = receipt.instrumentFrameSequence
    val producerTimestampNanos: Long get() = receipt.producerTimestampNanos
    val replaySequence: Long get() = receipt.replaySequence
    val contentKind: SignedInstrumentContentKind = when (content) {
        is InstrumentCommittedContent.SafetyClear ->
            SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR
        is InstrumentCommittedContent.Numeric ->
            SignedInstrumentContentKind.NUMERIC_REFERENCE
        InstrumentCommittedContent.Unknown -> error("Unknown raster cannot issue content proof")
    }
    val clearBeforeReveal: Boolean =
        contentKind == SignedInstrumentContentKind.COMPLETE_SAFETY_CLEAR

    fun isCurrentFor(replay: UltraEglClusterReplayReceipt): Boolean =
        runCatching { issuer.isCurrentContentProof(this, replay) }.getOrDefault(false)
}

/** Explicit BUSY/REVOCATION separation for the asynchronous CAF opener and ticket observer. */
internal fun instrumentSourceShouldRetire(
    physical: UiSyncPhysicalGenerationStatus,
    sceneUse: UltraProfileSceneUseAttempt<*>,
): Boolean = physical == UiSyncPhysicalGenerationStatus.REVOKED ||
    sceneUse == UltraProfileSceneUseAttempt.Revoked

/** Small nonblocking EGL-ticket observer, also exercised with delayed/failed JVM tickets. */
internal class InstrumentReferenceFirstFramePoller(
    private val executor: ScheduledExecutorService,
    private val pollMillis: Long = 25L,
    private val timeoutNanos: Long = TimeUnit.SECONDS.toNanos(5),
    private val clock: () -> Long = System::nanoTime,
) {
    fun observe(
        ticket: UltraEglRenderTicket,
        stillOwned: () -> Boolean,
        revoked: () -> Boolean,
        ready: () -> Boolean,
        onPresented: () -> Unit,
        onRejected: (Throwable?) -> Unit,
    ) {
        require(pollMillis > 0L && timeoutNanos > 0L)
        val deadline = clock() + timeoutNanos
        fun attempt() {
            if (!stillOwned()) return
            if (revoked()) {
                onRejected(null)
                return
            }
            val result = runCatching { ticket.await(0L) }.getOrNull()
            when {
                result != null && result.revision != ticket.revision -> onRejected(
                    IllegalStateException("Reference instrument EGL ticket revision changed"),
                )
                result?.disposition == UltraEglRenderDisposition.PRESENTED && ready() -> {
                    when {
                        !stillOwned() -> Unit
                        revoked() -> onRejected(null)
                        ready() -> onPresented()
                        clock() >= deadline -> onRejected(IllegalStateException(
                            "Reference instrument output was not current at first-frame fence",
                        ))
                        else -> runCatching { executor.schedule(::attempt, pollMillis,
                            TimeUnit.MILLISECONDS) }
                    }
                }
                result != null && result.disposition != UltraEglRenderDisposition.PRESENTED ->
                    onRejected(IllegalStateException(
                        "Reference instrument EGL first scene was ${result.disposition}",
                        result.failure,
                    ))
                clock() >= deadline -> onRejected(IllegalStateException(
                    "Reference instrument first EGL swap/finish was not proven before deadline",
                ))
                else -> runCatching { executor.schedule(::attempt, pollMillis,
                    TimeUnit.MILLISECONDS) }
            }
        }
        runCatching { executor.execute(::attempt) }
    }
}
