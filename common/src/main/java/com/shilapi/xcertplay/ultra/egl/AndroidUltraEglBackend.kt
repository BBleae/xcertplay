package com.shilapi.xcertplay.ultra.egl

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.media.AndroidVideoSurfaceLease
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.media.AndroidVideoFrameReleaseReceipt
import com.shilapi.xcertplay.ultra.AndroidDisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraContentId
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraResolvedMultiOutputRenderPlan
import com.shilapi.xcertplay.ultra.UltraSceneSource
import com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Exact adapter for AndroidMediaSink.acquireSurfaceLease(handle, surface). A null return must mean
 * the Surface was never installed. A thrown exception is treated as an ambiguous attachment and
 * causes the backend to retain the producer Surface fail-closed.
 */
fun interface AndroidVideoSurfaceLeaseAcquirer {
    fun acquire(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
    ): AndroidVideoSurfaceLease?
}

/** One exact Android output in a complete, generation-bound replacement. */
data class AndroidUltraEglOutputBatchRequest(
    val destination: DisplayDestination,
    val output: UltraDisplayGraphOutput,
    val target: AndroidDisplaySurfaceTarget,
)

/** A batch lease only retires its own generation; a failed close may be retried. */
abstract class UltraEglOutputBatchLease internal constructor() : Closeable {
    abstract val generation: Long
    abstract val isCurrent: Boolean
}

/** The caller must retire the entire backend before releasing any candidate or old target. */
class UltraEglBatchCommitUncertainException(cause: Throwable) : IllegalStateException(
    "EGL batch commit outcome or native cleanup is uncertain; full backend retirement is required",
    cause,
)

/** Opaque, referential capability for one binder's exclusive batch mutation authority. */
internal class UltraEglBatchOwnerToken

/**
 * API 28 multi-output EGL compositor. Output swaps are serialized on one thread, but are not
 * claimed to be atomic across physical displays. This class is not connected to product capability
 * negotiation; callers must explicitly bind exact graph resources.
 */
class AndroidUltraEglBackend internal constructor(
    private val renderThread: UltraEglRenderThread,
) : Closeable {
    private enum class IngressLifecycle {
        OPENING_DRIVER,
        OPENING_ACQUIRER,
        ACTIVE,
        QUIESCED,
        RETIREMENT_UNCERTAIN,
    }

    private class IngressRecord(
        val generation: Long,
        val key: UltraEglIngressKey,
        val graphIngress: UltraDisplayGraphIngress?,
        val remoteHandle: AirPlayVideoStreamHandle?,
        val rasterSizePixels: PixelSize? = null,
    ) {
        var producerSurface: Surface? = null
        var decoderLease: AndroidVideoSurfaceLease? = null
        var decoderAttachmentUncertain = false
        var externalAcquirerInFlight = false
        var lifecycle = IngressLifecycle.OPENING_DRIVER
    }

    private enum class OutputLifecycle { OPENING, ACTIVE, RETIREMENT_UNCERTAIN }

    private class OutputRecord(
        val generation: Long,
        val destination: DisplayDestination,
        val graphOutput: UltraDisplayGraphOutput,
        val target: AndroidDisplaySurfaceTarget,
        val surface: Surface,
    ) {
        var lifecycle = OutputLifecycle.OPENING
    }

    private val operationLock = ReentrantLock()
    private val ownershipIdentity = Any()
    private val ingresses = LinkedHashMap<UltraEglIngressKey, IngressRecord>()
    private val remoteHandles =
        IdentityReservationIndex<AirPlayVideoStreamHandle, IngressRecord>()
    private val remoteFrameIdentity = BoundedRemoteFrameIdentityMatcher()
    private val outputs = LinkedHashMap<DisplayDestination, OutputRecord>()
    private val retiredOutputBatches = LinkedHashMap<Long, List<OutputRecord>>()
    private val outputSurfaces = IdentityReservationIndex<Surface, OutputRecord>()
    private var activeOutputBatchGeneration: Long? = null
    private var exclusiveBatchOwner: UltraEglBatchOwnerToken? = null
    private var generation = 0L
    private var lastAcceptedRevision = -1L
    @Volatile private var closeRequested = false
    private var driverRetired = false
    private var closed = false
    private var lastFailure: Throwable? = null

    init {
        // The observer only joins evidence. It must not gate updateTexImage or ordinary replay.
        renderThread.setRemoteAcquisitionObserver { frame ->
            remoteFrameIdentity.observeAcquired(frame)
        }
    }

    constructor(
        commandCapacity: Int = BoundedEglCommandQueue.DEFAULT_CAPACITY,
        controlTimeoutMillis: Long = UltraEglRenderThread.DEFAULT_CONTROL_TIMEOUT_MILLIS,
    ) : this(
        UltraEglRenderThread(
            driver = AndroidEgl14Driver(),
            commandCapacity = commandCapacity,
            defaultControlTimeoutMillis = controlTimeoutMillis,
        ),
    )

    /**
     * Reserves both the typed ingress ID and the session-bound handle before allocating EGL state.
     * The decoder acquirer runs outside [operationLock], so reentrant/cross-thread close can cancel
     * publication without deadlock.
     */
    fun openRemoteIngress(
        ingress: UltraDisplayGraphIngress,
        acquireDecoderSurface: AndroidVideoSurfaceLeaseAcquirer,
    ): UltraEglIngressLease {
        val record: IngressRecord
        operationLock.withLock {
            checkMutableLocked()
            val key = UltraEglIngressKey.Remote(ingress.id)
            require(key !in ingresses) { "EGL remote ingress is already reserved: ${ingress.id}" }
            require(!remoteHandles.contains(ingress.stream)) {
                "One session-bound AirPlay stream handle cannot back multiple EGL ingresses"
            }
            require(ingresses.size < MAXIMUM_TOTAL_INGRESSES) {
                "Ultra EGL backend exceeds $MAXIMUM_TOTAL_INGRESSES ingresses"
            }
            record = IngressRecord(nextGenerationLocked(), key, ingress, ingress.stream)
            ingresses[key] = record
            remoteHandles.reserve(ingress.stream, record)
            try {
                val endpoint = renderThread.call { core ->
                    core.openIngress(record.key, record.generation, sizePixels = null)
                }
                record.producerSurface = endpoint as? Surface ?: run {
                    record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                    closeRequested = true
                    throw IllegalStateException("Android EGL driver returned a non-Surface endpoint")
                }
                if (closeRequested) {
                    val retired = retireIngressLocked(
                        record,
                        deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS),
                    )
                    if (!retired) record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                    throw IllegalStateException("Ultra EGL backend closed while opening ingress")
                }
                record.lifecycle = IngressLifecycle.OPENING_ACQUIRER
                record.externalAcquirerInFlight = true
            } catch (problem: Throwable) {
                handleIngressOpenControlFailureLocked(record, problem)
                throw problem
            }
        }

        val surface = checkNotNull(record.producerSurface)
        val acquired: Result<AndroidVideoSurfaceLease?> = runCatching {
            acquireDecoderSurface.acquire(checkNotNull(record.remoteHandle), surface)
        }

        return operationLock.withLock {
            record.externalAcquirerInFlight = false
            val current = ingresses[record.key]
            if (current !== record) {
                acquired.getOrNull()?.retire(0L)
                throw IllegalStateException("EGL ingress reservation retired during acquisition")
            }
            val acquisitionFailure = acquired.exceptionOrNull()
            if (acquisitionFailure != null) {
                record.decoderAttachmentUncertain = true
                record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                closeRequested = true
                lastFailure = acquisitionFailure
                bestEffortQuiesceLocked(record, deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS))
                throw acquisitionFailure
            }

            val decoderLease = acquired.getOrNull()
            if (decoderLease == null) {
                val retired = retireIngressLocked(
                    record,
                    deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS),
                )
                if (!retired) closeRequested = true
                throw IllegalStateException("Media sink refused the exact stream Surface lease")
            }
            if (!decoderLease.matches(checkNotNull(record.remoteHandle), surface)) {
                val retirement = runCatching {
                    decoderLease.retire(DEFAULT_CLEANUP_TIMEOUT_MILLIS)
                }.getOrNull()
                record.decoderAttachmentUncertain = true
                closeRequested = true
                bestEffortQuiesceLocked(
                    record,
                    deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS),
                )
                record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                throw IllegalStateException(
                    "Media sink returned a Surface lease for a different handle/Surface" +
                        if (retirement == null) " and its retirement failed" else "",
                )
            }
            record.decoderLease = decoderLease
            val replacement = decoderLease.replacementRetirementResult
            if (replacement != null && !replacement.isSafeToReleaseSurface) {
                record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                closeRequested = true
                throw IllegalStateException(
                    "Prior decoder Surface replacement was not safely retired: $replacement",
                )
            }
            if (closeRequested) {
                val retired = retireIngressLocked(
                    record,
                    deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS),
                )
                if (!retired) record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                throw IllegalStateException("Ultra EGL backend closed during Surface acquisition")
            }
            record.lifecycle = IngressLifecycle.ACTIVE
            remoteFrameIdentity.bind(
                key = record.key as UltraEglIngressKey.Remote,
                handle = checkNotNull(record.remoteHandle),
                surface = surface,
                surfaceLeaseGeneration = decoderLease.generation,
                eglIngressGeneration = record.generation,
            )
            IngressLease(this, record)
        }
    }

    /**
     * Auxiliary, fail-closed one-shot join. UNMATCHED does not stop existing Remote rendering;
     * it only denies a new exact frame-identity receipt for later synchronization consumers.
     */
    internal fun acceptVideoFrameReleaseReceipt(
        receipt: AndroidVideoFrameReleaseReceipt,
    ): RemoteFrameIdentityDisposition {
        if (closeRequested || closed) return RemoteFrameIdentityDisposition.UNMATCHED
        return remoteFrameIdentity.acceptRelease(RemoteVideoReleaseSignal(
            handle = receipt.handle,
            surface = receipt.surface,
            surfaceLeaseGeneration = receipt.surfaceLeaseGeneration,
            authenticatedFrameCounter = receipt.authenticatedFrameCounter,
            presentationTimeNtp64Bits = receipt.presentationTimeNtp64Bits,
            codecOutputPresentationTimeUs = receipt.codecOutputPresentationTimeUs,
            releaseTargetMonotonicNanos = receipt.releaseTargetMonotonicNanos,
            expectedSurfaceTextureTimestampNanos =
                receipt.expectedSurfaceTextureTimestampNanos,
        ))
    }

    /** Consumes bounded diagnostic evidence; no scene or output readiness follows from it. */
    internal fun drainMatchedVideoFrames(): List<MatchedRemoteFrameConsumerReceipt> =
        remoteFrameIdentity.drainMatched()

    fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize): UltraEglIngressLease =
        openOwnedIngress(UltraEglIngressKey.Local(contentId), sizePixels)

    fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize): UltraEglIngressLease =
        openOwnedIngress(UltraEglIngressKey.Overlay(contentId), sizePixels)

    /** Separate GL_TEXTURE_2D source; it never allocates or exports a producer Surface. */
    internal fun openInstrumentRaster(
        destination: DisplayDestination,
        sizePixels: PixelSize,
    ): UltraEglInstrumentRasterLease = operationLock.withLock {
        checkMutableLocked()
        require(destination.role == com.shilapi.xcertplay.ultra.DisplayDestinationRole.CLUSTER)
        require(sizePixels.widthPixels.toLong() * sizePixels.heightPixels <= 1_600_000L)
        val key = UltraEglIngressKey.Instrument(destination)
        require(key !in ingresses && ingresses.size < MAXIMUM_TOTAL_INGRESSES) {
            "Dedicated instrument EGL ingress is already reserved or over capacity"
        }
        val record = IngressRecord(nextGenerationLocked(), key, null, null, sizePixels)
        ingresses[key] = record
        try {
            renderThread.call { core ->
                core.openInstrumentIngress(destination, record.generation, sizePixels)
            }
            if (closeRequested) {
                val retired = retireIngressLocked(
                    record, deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS),
                )
                if (!retired) record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                throw IllegalStateException("EGL backend closed while opening instrument ingress")
            }
            record.lifecycle = IngressLifecycle.ACTIVE
        } catch (problem: Throwable) {
            handleIngressOpenControlFailureLocked(record, problem)
            throw problem
        }
        InstrumentLease(this, record)
    }

    /** Arbitrary IntArray callers cannot retain a mutable alias into an EGL control command. */
    private fun replaceInstrumentFrame(record: IngressRecord, pixels: IntArray):
        UltraEglInstrumentUploadReceipt {
        val size = record.rasterSizePixels ?: return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
        if (pixels.size.toLong() != size.widthPixels.toLong() * size.heightPixels) {
            return UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
        }
        return OwnedOpaqueArgbFrame.capture(pixels)?.let { replaceInstrumentFrame(record, it) }
            ?: UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
    }

    private fun replaceInstrumentFrame(record: IngressRecord, frame: OwnedOpaqueArgbFrame):
        UltraEglInstrumentUploadReceipt {
        var replay: UltraEglClusterReplayReceipt? = null
        val result = operationLock.withLock {
            val owned = frame.transfer()
                ?: return@withLock UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
            if (closeRequested || closed || ingresses[record.key] !== record ||
                record.lifecycle != IngressLifecycle.ACTIVE
            ) return@withLock UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
            val size = checkNotNull(record.rasterSizePixels)
            if (!owned.isCompleteFor(size.widthPixels.toLong() * size.heightPixels)
            ) return@withLock UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
            // The command closure owns this array after take(). A timed-out RUNNING control can
            // continue reading it, so there is deliberately no pooling or caller-visible reuse.
            val commandPixels = owned.take()
                ?: return@withLock UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT
            try {
                replay = renderThread.call { core ->
                    core.replaceInstrumentFrame(
                        (record.key as UltraEglIngressKey.Instrument).destination,
                        record.generation, size, commandPixels,
                    )
                }
                UltraEglInstrumentUploadReceipt.COMMITTED
            } catch (failure: Throwable) {
                closeRequested = true
                lastFailure = failure
                UltraEglInstrumentUploadReceipt.COMPLETION_UNCERTAIN
            }
        }
        // Never invoke the observer from within the backend operation lock. The outbox also
        // rejects an older completion if an async frame replay overtook this control caller.
        if (result == UltraEglInstrumentUploadReceipt.COMMITTED) {
            renderThread.publishClusterReplay(replay)
        }
        return result
    }

    /** Claims target and bottom-Surface identity before issuing any EGL command. */
    fun attachOutput(
        destination: DisplayDestination,
        output: UltraDisplayGraphOutput,
        target: AndroidDisplaySurfaceTarget,
    ): UltraEglOutputLease = operationLock.withLock {
        checkMutableLocked()
        check(exclusiveBatchOwner == null) {
            "An atomic display binder owns this EGL backend's output topology"
        }
        check(activeOutputBatchGeneration == null && retiredOutputBatches.isEmpty()) {
            "Single-output attachment cannot mix with atomic EGL output batches"
        }
        require(target.isValid) { "Cannot attach an invalid Android output Surface" }
        require(outputs[destination]?.target !== target) {
            "The same Android output target is already attached to $destination"
        }
        require(destination in outputs || outputs.size < MAXIMUM_OUTPUTS) {
            "Ultra EGL backend exceeds $MAXIMUM_OUTPUTS outputs"
        }
        val surface = target.surface
        require(!outputSurfaces.contains(surface)) {
            "One bottom Surface reference cannot back multiple EGL output wrappers"
        }
        val ownership = target.ownership
        check(ownership.claim(ownershipIdentity)) {
            "Android output target is already claimed by this EGL backend"
        }
        val record = OutputRecord(
            nextGenerationLocked(),
            destination,
            output,
            target,
            surface,
        )
        outputSurfaces.reserve(surface, record)
        try {
            renderThread.call { core ->
                core.attachOutput(
                    destination,
                    record.generation,
                    surface,
                    target.descriptor.sizePixels,
                    targetIsValid = { target.isValid },
                )
            }
        } catch (problem: Throwable) {
            when (problem) {
                is UltraEglRunningControlTimeoutException,
                is UltraEglCandidateResourceRetainedException,
                -> {
                    record.lifecycle = OutputLifecycle.RETIREMENT_UNCERTAIN
                    closeRequested = true
                    lastFailure = problem
                }
                else -> {
                    outputSurfaces.release(surface, record)
                    ownership.release(ownershipIdentity)
                    if (problem !is UltraEglPendingControlTimeoutException) {
                        closeRequested = true
                        lastFailure = problem
                    }
                }
            }
            throw problem
        }
        // A borrowed target may be closed while the native attach is queued or running. A
        // successful EGL attach alone is not authority to publish an ACTIVE output lease.
        val validity = runCatching { target.isValid }
        val rejection = when {
            closeRequested -> IllegalStateException("Ultra EGL backend closed while attaching output")
            !validity.getOrDefault(false) ->
                UltraEglOutputTargetUnavailableException(destination, validity.exceptionOrNull())
            else -> null
        }
        if (rejection != null) {
            try {
                renderThread.call { core ->
                    core.detachOutput(destination, record.generation)
                }
            } catch (cleanup: Throwable) {
                // The candidate may still own the native window. Keep its reservation and
                // ownership until full driver retirement proves every EGL reference gone.
                val retained = UltraEglCandidateResourceRetainedException(rejection, cleanup)
                record.lifecycle = OutputLifecycle.RETIREMENT_UNCERTAIN
                closeRequested = true
                lastFailure = retained
                throw retained
            }
            // attachOutput already proved the previous EGL resource retired, and the candidate
            // has now been detached. Neither target has a native EGL reference any longer.
            outputs.remove(destination)?.let(::releaseOutputAfterProofLocked)
            releaseOutputAfterProofLocked(record)
            throw rejection
        }
        record.lifecycle = OutputLifecycle.ACTIVE
        val previous = outputs.put(destination, record)
        renderThread.revokeClusterReplay()
        if (previous != null) releaseOutputAfterProofLocked(previous)
        OutputLease(this, record)
    }

    /**
     * Replaces the entire composited route set in one owner-thread transaction. Candidate native
     * windows are reserved before the control command, but no route becomes visible until every
     * EGLSurface has been created and validated. This does not synchronize physical swaps.
     */
    fun replaceOutputs(
        requests: List<AndroidUltraEglOutputBatchRequest>,
    ): UltraEglOutputBatchLease = replaceOutputsInternal(requests, null) {}

    /** Claims exclusive output-topology mutation before a binder can prepare any installation. */
    internal fun claimExclusiveBatchOwner(): UltraEglBatchOwnerToken = operationLock.withLock {
        checkMutableLocked()
        check(exclusiveBatchOwner == null) { "EGL backend already has a batch owner" }
        check(outputs.isEmpty() && retiredOutputBatches.isEmpty()) {
            "Existing output leases must retire before an atomic binder claims this backend"
        }
        UltraEglBatchOwnerToken().also { exclusiveBatchOwner = it }
    }

    internal fun releaseExclusiveBatchOwner(owner: UltraEglBatchOwnerToken) {
        operationLock.withLock {
            check(exclusiveBatchOwner === owner) { "EGL batch owner token mismatch" }
            check(outputs.isEmpty() && retiredOutputBatches.isEmpty()) {
                "EGL batch outputs must retire before binder ownership is released"
            }
            exclusiveBatchOwner = null
        }
    }

    /** Route publication is invoked while the backend's resolution/submit gate remains locked. */
    internal fun replaceOutputsWithPublication(
        requests: List<AndroidUltraEglOutputBatchRequest>,
        owner: UltraEglBatchOwnerToken,
        publishRoutes: () -> Unit,
    ): UltraEglOutputBatchLease = replaceOutputsInternal(requests, owner, publishRoutes)

    private fun replaceOutputsInternal(
        requests: List<AndroidUltraEglOutputBatchRequest>,
        owner: UltraEglBatchOwnerToken?,
        publishRoutes: () -> Unit,
    ): UltraEglOutputBatchLease = operationLock.withLock {
        checkMutableLocked()
        check(exclusiveBatchOwner === owner) {
            "An atomic display binder exclusively owns this EGL backend's output topology"
        }
        check(outputs.isEmpty() || activeOutputBatchGeneration != null) {
            "Atomic EGL output batches cannot replace individually attached outputs"
        }
        val declaredSize = requests.size
        require(declaredSize <= MAXIMUM_OUTPUTS) {
            "Ultra EGL backend exceeds $MAXIMUM_OUTPUTS outputs"
        }
        val frozenRequests = ArrayList<AndroidUltraEglOutputBatchRequest>(declaredSize)
        requests.forEach { request ->
            require(frozenRequests.size < MAXIMUM_OUTPUTS) {
                "Ultra EGL backend exceeds $MAXIMUM_OUTPUTS outputs"
            }
            frozenRequests += request
        }
        require(frozenRequests.size == declaredSize) {
            "EGL batch requests changed while being snapshotted"
        }
        require(frozenRequests.map { it.destination }.toSet().size == frozenRequests.size) {
            "An EGL batch destination cannot appear twice"
        }
        require(frozenRequests.map { it.output.outputId }.toSet().size == frozenRequests.size) {
            "An EGL batch graph output cannot appear twice"
        }
        val targetIdentities = Collections.newSetFromMap(
            IdentityHashMap<AndroidDisplaySurfaceTarget, Boolean>(),
        )
        val surfaceIdentities = Collections.newSetFromMap(IdentityHashMap<Surface, Boolean>())
        val ownershipIdentities = Collections.newSetFromMap(
            IdentityHashMap<com.shilapi.xcertplay.ultra.DisplaySurfaceOwnership, Boolean>(),
        )
        val transferredOld = LinkedHashMap<DisplayDestination, OutputRecord>()
        frozenRequests.forEach { request ->
            require(targetIdentities.add(request.target)) { "EGL batch reuses an output target" }
            require(surfaceIdentities.add(request.target.surface)) {
                "One bottom Surface cannot back multiple EGL batch outputs"
            }
            require(ownershipIdentities.add(request.target.ownership)) {
                "EGL batch reuses an output ownership guard"
            }
            val prior = outputSurfaces[request.target.surface]
            if (prior != null) {
                require(
                    activeOutputBatchGeneration != null &&
                        outputs[request.destination] === prior &&
                        prior.target === request.target &&
                        prior.target.descriptor.sizePixels == request.target.descriptor.sizePixels
                ) {
                    "A live Surface may transfer only as the exact same target, destination, and size"
                }
                transferredOld[request.destination] = prior
            }
            require(request.target.isValid) {
                "Cannot attach an invalid Android output Surface"
            }
        }

        val batchGeneration = nextGenerationLocked()
        val candidates = ArrayList<OutputRecord>(frozenRequests.size)
        val newlyClaimed = ArrayList<OutputRecord>(frozenRequests.size)
        try {
            frozenRequests.forEach { request ->
                val record = OutputRecord(
                    batchGeneration,
                    request.destination,
                    request.output,
                    request.target,
                    request.target.surface,
                )
                candidates += record
                if (request.destination !in transferredOld) {
                    check(request.target.ownership.claim(ownershipIdentity)) {
                        "Android output target is already claimed by this EGL backend"
                    }
                    newlyClaimed += record
                    outputSurfaces.reserve(record.surface, record)
                }
            }
        } catch (problem: Throwable) {
            newlyClaimed.asReversed().forEach(::releaseOutputAfterProofLocked)
            throw problem
        }

        try {
            renderThread.call { core ->
                core.replaceOutputBatch(
                    batchGeneration,
                    candidates.map { record ->
                        UltraEglOutputBatchCandidate(
                            record.destination,
                            record.generation,
                            record.surface,
                            record.target.descriptor.sizePixels,
                        ) { record.target.isValid }
                    },
                )
            }
        } catch (problem: Throwable) {
            if (problem is UltraEglBatchCommitUncertainException ||
                problem is UltraEglRunningControlTimeoutException ||
                problem is UltraEglCandidateResourceRetainedException ||
                problem is UltraEglContextLostException
            ) {
                candidates.forEach { it.lifecycle = OutputLifecycle.RETIREMENT_UNCERTAIN }
                closeRequested = true
                lastFailure = problem
                // A timed-out command or failed native cleanup may still own these Surfaces.
                // Full driver retirement is the only safe release proof.
                throw if (problem is UltraEglBatchCommitUncertainException) problem
                else UltraEglBatchCommitUncertainException(problem)
            } else {
                newlyClaimed.asReversed().forEach(::releaseOutputAfterProofLocked)
            }
            throw problem
        }
        val oldGeneration = activeOutputBatchGeneration
        if (oldGeneration != null) {
            val transferred = transferredOld.values.toSet()
            retiredOutputBatches[oldGeneration] = outputs.values.filterNot { it in transferred }
        }
        candidates.forEach { candidate ->
            val prior = transferredOld[candidate.destination] ?: return@forEach
            check(outputSurfaces.release(prior.surface, prior)) {
                "Transferred EGL Surface reservation was lost"
            }
            outputSurfaces.reserve(candidate.surface, candidate)
        }
        outputs.clear()
        candidates.forEach { record ->
            record.lifecycle = OutputLifecycle.ACTIVE
            outputs[record.destination] = record
        }
        activeOutputBatchGeneration = batchGeneration
        renderThread.revokeClusterReplay()
        try {
            publishRoutes()
        } catch (problem: Throwable) {
            // Native EGL has already published the generation. A route publication failure can
            // only be handled by failing closed and retiring the entire backend with proof.
            closeRequested = true
            lastFailure = problem
            throw UltraEglBatchCommitUncertainException(problem)
        }
        OutputBatchLease(this, batchGeneration)
    }

    fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission =
        operationLock.withLock {
            checkMutableLocked()
            resolvePlanLocked(plan)
        }

    fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket =
        operationLock.withLock {
            checkMutableLocked()
            validateResolvedIsCurrentLocked(resolved)
            enqueueResolvedLocked(resolved)
        }

    fun submit(plan: UltraResolvedMultiOutputRenderPlan): UltraEglRenderTicket =
        operationLock.withLock {
            checkMutableLocked()
            enqueueResolvedLocked(resolvePlanLocked(plan))
        }

    /** Raw snapshots cannot carry a session-bound remote handle and are local/overlay-only. */
    fun submitLocalOnly(snapshot: UltraMultiOutputRenderSnapshot): UltraEglRenderTicket =
        operationLock.withLock {
            checkMutableLocked()
            require(snapshot.scenes.all { scene ->
                scene.layers.none { it.source is UltraSceneSource.Remote }
            }) { "Remote layers require UltraResolvedMultiOutputRenderPlan" }
            enqueueResolvedLocked(resolveLocalLocked(snapshot))
        }

    /** The baseline must be captured after scene enqueue and before its Canvas posts. */
    internal fun prepareLocalFirstFrame(
        revision: Long,
        outputBatchGeneration: Long,
        localKeys: Set<UltraEglIngressKey.Local>,
        carriedFrom: UltraEglLocalFirstFrameReceipt? = null,
        carriedKeys: Set<UltraEglIngressKey.Local> = emptySet(),
    ): UltraEglLocalFirstFrameExpectation? = operationLock.withLock {
        checkMutableLocked()
        val frozenKeys = Collections.unmodifiableSet(LinkedHashSet(localKeys))
        val frozenCarried = Collections.unmodifiableSet(LinkedHashSet(carriedKeys))
        if (activeOutputBatchGeneration == null || outputBatchGeneration <= 0L ||
            frozenKeys.isEmpty() || !frozenKeys.containsAll(frozenCarried) ||
            frozenKeys.any { key ->
                ingresses[key]?.lifecycle != IngressLifecycle.ACTIVE
            }
        ) return@withLock null
        renderThread.call { core ->
            core.prepareLocalFirstFrame(
                revision, outputBatchGeneration, frozenKeys, carriedFrom, frozenCarried,
            )
        }
    }

    /** A bounded EGL-thread replay and swap, not a physical scan-out or UI Sync ACK. */
    internal fun tryPresentLocalFirstFrame(
        expectation: UltraEglLocalFirstFrameExpectation,
        postSequences: Map<UltraEglIngressKey.Local, Long>,
    ): UltraEglLocalFirstFrameReceipt? {
        val receipt = operationLock.withLock {
            checkMutableLocked()
            if (activeOutputBatchGeneration != expectation.outputBatchGeneration) {
                return@withLock null
            }
            val frozenPosts = Collections.unmodifiableMap(LinkedHashMap(postSequences))
            renderThread.call { core ->
                core.tryPresentLocalFirstFrame(expectation, frozenPosts)
            }
        }
        renderThread.publishClusterReplay(receipt?.clusterReplay)
        return receipt
    }

    internal fun subscribeClusterReplay(
        destination: DisplayDestination,
        instrumentGeneration: Long,
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
        onSuperseded: (UltraEglClusterReplayReceipt) -> Unit,
    ): UltraEglClusterReplaySubscription {
        operationLock.withLock {
            checkMutableLocked()
            val record = ingresses[UltraEglIngressKey.Instrument(destination)]
            require(record != null && record.generation == instrumentGeneration &&
                record.lifecycle == IngressLifecycle.ACTIVE) {
                "CLUSTER replay ingress is not this live instrument generation"
            }
        }
        fun current(receipt: UltraEglClusterReplayReceipt): Boolean = operationLock.withLock {
                !closed && !closeRequested &&
                    receipt.destination == destination &&
                    receipt.instrumentIngressGeneration == instrumentGeneration &&
                    ingresses[UltraEglIngressKey.Instrument(destination)]?.let {
                        it.generation == instrumentGeneration &&
                            it.lifecycle == IngressLifecycle.ACTIVE
                    } == true &&
                    activeOutputBatchGeneration == receipt.nativeOutputBatchGeneration &&
                    outputs[destination]?.let {
                        it.generation == receipt.nativeOutputGeneration &&
                            it.lifecycle == OutputLifecycle.ACTIVE && it.target.isValid
                    } == true
        }
        return renderThread.subscribeClusterReplay({ receipt ->
            if (current(receipt)) onReceipt(receipt)
            else renderThread.revokeClusterReplay()
        }, onRevoked, { receipt ->
            if (current(receipt)) onSuperseded(receipt)
            else renderThread.revokeClusterReplay()
        })
    }

    fun diagnostics(): UltraEglBackendDiagnostics = operationLock.withLock {
        val core = if (closed) {
            UltraEglCoreSnapshot(
                UltraEglBackendPhase.CLOSED,
                emptyMap(),
                emptyMap(),
                lastAcceptedRevision,
                lastFailure,
            )
        } else if (renderThread.hasRetirementUncertainty()) {
            // A normal snapshot call is rejected in retirement-only mode and would replace the
            // failure which caused that mode with the less useful rejection exception.
            UltraEglCoreSnapshot(
                UltraEglBackendPhase.RETIREMENT_UNCERTAIN,
                emptyMap(),
                emptyMap(),
                -1L,
                lastFailure,
            )
        } else {
            try {
                renderThread.call { it.snapshot() }
            } catch (problem: Throwable) {
                // A concurrent timeout can turn the thread retirement-only between the check
                // above and this call. Keep the original backend failure if it was recorded.
                val failure = if (renderThread.hasRetirementUncertainty() && lastFailure != null) {
                    lastFailure
                } else {
                    problem.also { lastFailure = it }
                }
                if (
                    problem is UltraEglStartupTimeoutException ||
                    problem is UltraEglRunningControlTimeoutException
                ) closeRequested = true
                UltraEglCoreSnapshot(
                    UltraEglBackendPhase.RETIREMENT_UNCERTAIN,
                    emptyMap(),
                    emptyMap(),
                    -1L,
                    failure,
                )
            }
        }
        val queue = renderThread.queueSnapshot()
        UltraEglBackendDiagnostics(
            phase = if (
                !closed && (closeRequested || renderThread.hasRetirementUncertainty())
            ) UltraEglBackendPhase.RETIREMENT_UNCERTAIN else core.phase,
            ingressCount = ingresses.size,
            outputCount = outputs.size,
            lastPresentedRevision = core.lastPresentedRevision,
            coalescedSceneCount = queue.coalescedSceneCount,
            coalescedFrameCount = queue.coalescedFrameCount,
            failure = core.failure ?: lastFailure,
        )
    }

    /** One retryable close attempt using one global deadline for every retirement stage. */
    fun retire(
        timeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
    ): UltraEglBackendRetirementResult {
        require(timeoutMillis in 0L..MAXIMUM_CLOSE_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 0 and $MAXIMUM_CLOSE_TIMEOUT_MILLIS"
        }
        val deadline = deadlineAfterMillis(timeoutMillis)
        closeRequested = true
        val locked = try {
            operationLock.tryLock(remainingNanos(deadline), java.util.concurrent.TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) return UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
        return try {
            retireLocked(deadline)
        } finally {
            operationLock.unlock()
        }
    }

    private fun retireLocked(deadline: Long): UltraEglBackendRetirementResult {
        if (closed) return UltraEglBackendRetirementResult.RETIRED
        closeRequested = true
        remoteFrameIdentity.clear()
        if (ingresses.values.any(IngressRecord::externalAcquirerInFlight)) {
            return UltraEglBackendRetirementResult.EXTERNAL_ATTACHMENT_IN_FLIGHT
        }
        if (!renderThread.awaitStartupForRetirement(deadline)) {
            return UltraEglBackendRetirementResult.STARTUP_IN_FLIGHT
        }
        if (!renderThread.awaitUncertainControl(deadline)) {
            return UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
        }

        if (!driverRetired) {
            for (record in ingresses.values.toList()) {
                if (record.decoderAttachmentUncertain) {
                    return UltraEglBackendRetirementResult.DECODER_RETIREMENT_UNCERTAIN
                }
                if (!quiesceIngressLocked(record, deadline)) {
                    return UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
                }
            }
            for (record in ingresses.values) {
                val lease = record.decoderLease ?: continue
                val result = lease.retire(remainingMillis(deadline))
                if (!result.isSafeToReleaseSurface) {
                    lastFailure = IllegalStateException("Decoder Surface retirement: $result")
                    return UltraEglBackendRetirementResult.DECODER_RETIREMENT_UNCERTAIN
                }
            }
            val proof = try {
                renderThread.callUntil(
                    deadlineNanos = deadline,
                    allowStartupFailure = true,
                ) { core -> core.closeDriver(deadline) }
            } catch (problem: Throwable) {
                lastFailure = problem
                return when (problem) {
                    is UltraEglRunningControlTimeoutException ->
                        UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT
                    else -> UltraEglBackendRetirementResult.DRIVER_RETIREMENT_UNCERTAIN
                }
            }
            if (!proof.retired) {
                lastFailure = proof.failure
                return UltraEglBackendRetirementResult.DRIVER_RETIREMENT_UNCERTAIN
            }
            driverRetired = true
        }

        if (!renderThread.shutdown(deadline)) {
            return UltraEglBackendRetirementResult.RENDER_THREAD_STOP_UNCERTAIN
        }
        renderThread.setRemoteAcquisitionObserver(null)
        releaseAllOutputOwnershipAfterProofLocked()
        ingresses.clear()
        remoteHandles.clear()
        outputs.clear()
        retiredOutputBatches.clear()
        activeOutputBatchGeneration = null
        outputSurfaces.clear()
        closed = true
        return UltraEglBackendRetirementResult.RETIRED
    }

    override fun close() {
        val result = retire()
        check(result.isRetired) { "Ultra EGL retirement is not proven: $result" }
    }

    private fun openOwnedIngress(key: UltraEglIngressKey, sizePixels: PixelSize): UltraEglIngressLease =
        operationLock.withLock {
            checkMutableLocked()
            require(key !in ingresses) { "EGL content ingress is already reserved: $key" }
            require(ingresses.size < MAXIMUM_TOTAL_INGRESSES) {
                "Ultra EGL backend exceeds $MAXIMUM_TOTAL_INGRESSES ingresses"
            }
            val record = IngressRecord(nextGenerationLocked(), key, null, null)
            ingresses[key] = record
            try {
                val endpoint = renderThread.call { core ->
                    core.openIngress(record.key, record.generation, sizePixels)
                }
                record.producerSurface = endpoint as? Surface ?: run {
                    record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                    closeRequested = true
                    throw IllegalStateException("Android EGL driver returned a non-Surface endpoint")
                }
                if (closeRequested) {
                    val retired = retireIngressLocked(
                        record,
                        deadlineAfterMillis(DEFAULT_CLEANUP_TIMEOUT_MILLIS),
                    )
                    if (!retired) record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                    throw IllegalStateException("Ultra EGL backend closed while opening ingress")
                }
                record.lifecycle = IngressLifecycle.ACTIVE
            } catch (problem: Throwable) {
                handleIngressOpenControlFailureLocked(record, problem)
                throw problem
            }
            IngressLease(this, record)
        }

    private fun handleIngressOpenControlFailureLocked(
        record: IngressRecord,
        problem: Throwable,
    ) {
        if (record.lifecycle == IngressLifecycle.RETIREMENT_UNCERTAIN) {
            closeRequested = true
            lastFailure = problem
            return
        }
        when (problem) {
            is UltraEglRunningControlTimeoutException,
            is UltraEglCandidateResourceRetainedException,
            -> {
                record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
                closeRequested = true
                lastFailure = problem
            }
            else -> {
                removeIngressReservationLocked(record)
                if (problem !is UltraEglPendingControlTimeoutException) closeRequested = true
            }
        }
    }

    private fun bestEffortQuiesceLocked(record: IngressRecord, deadline: Long) {
        if (!quiesceIngressLocked(record, deadline)) {
            record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
        }
    }

    private fun quiesceIngressLocked(record: IngressRecord, deadline: Long): Boolean {
        if (record.lifecycle == IngressLifecycle.QUIESCED) return true
        if (record.key is UltraEglIngressKey.Remote) {
            remoteFrameIdentity.revoke(record.key, record.generation)
        }
        renderThread.revokeClusterReplay()
        return try {
            renderThread.callUntil(
                deadlineNanos = deadline,
                allowStartupFailure = true,
            ) { core -> core.quiesceIngress(record.key, record.generation) }
            record.lifecycle = IngressLifecycle.QUIESCED
            true
        } catch (problem: Throwable) {
            lastFailure = problem
            record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
            false
        }
    }

    private fun retireIngressLocked(record: IngressRecord, deadline: Long): Boolean {
        if (record.decoderAttachmentUncertain) return false
        if (!quiesceIngressLocked(record, deadline)) return false
        val decoderResult = record.decoderLease?.retire(remainingMillis(deadline))
        if (decoderResult != null && !decoderResult.isSafeToReleaseSurface) {
            record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
            return false
        }
        return try {
            renderThread.callUntil(
                deadlineNanos = deadline,
                allowStartupFailure = true,
            ) { core -> core.destroyIngress(record.key, record.generation) }
            removeIngressReservationLocked(record)
            true
        } catch (problem: Throwable) {
            lastFailure = problem
            record.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
            false
        }
    }

    private fun retireIngress(
        expected: IngressRecord,
        timeoutMillis: Long,
    ): AndroidVideoSurfaceRetirementResult? = operationLock.withLock {
        require(timeoutMillis in 0L..MAXIMUM_CLOSE_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 0 and $MAXIMUM_CLOSE_TIMEOUT_MILLIS"
        }
        val current = ingresses[expected.key]
        if (current !== expected) return expected.decoderLease?.retire(timeoutMillis)
        val deadline = deadlineAfterMillis(timeoutMillis)
        // A prior RUNNING control timeout remains tracked even after its caller returns. Wait
        // for that exact invocation to settle before another ingress cleanup command; otherwise
        // callUntil rejects every retry while the backend still owns the producer Surface.
        if (!renderThread.awaitStartupForRetirement(deadline) ||
            !renderThread.awaitUncertainControl(deadline)
        ) {
            current.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
            closeRequested = true
            return AndroidVideoSurfaceRetirementResult.INTERRUPTED
        }
        if (!quiesceIngressLocked(current, deadline)) {
            return AndroidVideoSurfaceRetirementResult.INTERRUPTED
        }
        val result = current.decoderLease?.retire(remainingMillis(deadline))
        if (result != null && !result.isSafeToReleaseSurface) return result
        val destroyed = try {
            renderThread.callUntil(
                deadlineNanos = deadline,
                allowStartupFailure = true,
            ) { core -> core.destroyIngress(current.key, current.generation) }
            true
        } catch (problem: Throwable) {
            lastFailure = problem
            false
        }
        if (!destroyed) {
            current.lifecycle = IngressLifecycle.RETIREMENT_UNCERTAIN
            closeRequested = true
            // A retired decoder is only half of the lease: EGL must also release its producer.
            // Keep the reservation and make the caller retry rather than publishing RETIRED.
            return AndroidVideoSurfaceRetirementResult.INTERRUPTED
        }
        removeIngressReservationLocked(current)
        result
    }

    private fun detachOutput(expected: OutputRecord) {
        operationLock.withLock {
            check(activeOutputBatchGeneration == null) {
                "A single-output lease cannot detach an atomic EGL output batch"
            }
            val current = outputs[expected.destination] ?: return@withLock
            if (current !== expected) return@withLock
            try {
                renderThread.call { core ->
                    core.detachOutput(expected.destination, expected.generation)
                }
            } catch (problem: Throwable) {
                lastFailure = problem
                if (problem !is UltraEglPendingControlTimeoutException) {
                    expected.lifecycle = OutputLifecycle.RETIREMENT_UNCERTAIN
                    closeRequested = true
                }
                throw problem
            }
            outputs.remove(expected.destination)
            renderThread.revokeClusterReplay()
            releaseOutputAfterProofLocked(expected)
        }
    }

    private fun isOutputBatchCurrent(expectedGeneration: Long): Boolean = operationLock.withLock {
        !closed && !closeRequested && activeOutputBatchGeneration == expectedGeneration
    }

    private fun retireOutputBatch(expectedGeneration: Long) {
        operationLock.withLock {
            if (closed) return
            val records = when {
                activeOutputBatchGeneration == expectedGeneration -> outputs.values.toList()
                else -> retiredOutputBatches[expectedGeneration] ?: return
            }
            val deadline = deadlineAfterMillis(DEFAULT_CLOSE_TIMEOUT_MILLIS)
            if (!renderThread.awaitUncertainControl(deadline)) {
                closeRequested = true
                throw IllegalStateException("EGL output batch control remains in flight")
            }
            try {
                renderThread.callUntil(deadline, allowStartupFailure = true) { core ->
                    core.retireOutputBatch(expectedGeneration)
                }
            } catch (problem: Throwable) {
                closeRequested = true
                lastFailure = problem
                throw problem
            }
            if (activeOutputBatchGeneration == expectedGeneration) {
                outputs.clear()
                activeOutputBatchGeneration = null
                renderThread.revokeClusterReplay()
            } else {
                retiredOutputBatches.remove(expectedGeneration)
            }
            records.forEach(::releaseOutputAfterProofLocked)
        }
    }

    private fun releaseOutputAfterProofLocked(record: OutputRecord) {
        outputSurfaces.release(record.surface, record)
        record.target.ownership.release(ownershipIdentity)
    }

    private fun releaseAllOutputOwnershipAfterProofLocked() {
        val targets = Collections.newSetFromMap(
            IdentityHashMap<AndroidDisplaySurfaceTarget, Boolean>(),
        )
        outputSurfaces.valuesSnapshot().forEach { targets += it.target }
        targets.forEach { target -> target.ownership.release(ownershipIdentity) }
    }

    private fun removeIngressReservationLocked(record: IngressRecord) {
        if (record.key is UltraEglIngressKey.Remote) {
            remoteFrameIdentity.revoke(record.key, record.generation)
        }
        if (ingresses[record.key] === record) ingresses.remove(record.key)
        record.remoteHandle?.let { handle ->
            remoteHandles.release(handle, record)
        }
    }

    private fun enqueueResolvedLocked(
        resolved: UltraEglResolvedSubmission,
    ): UltraEglRenderTicket {
        check(!closeRequested) { "Ultra EGL backend is closing" }
        require(resolved.revision > lastAcceptedRevision) {
            "Render revision ${resolved.revision} is not newer than $lastAcceptedRevision"
        }
        val ticket = renderThread.submit(resolved)
        lastAcceptedRevision = resolved.revision
        return ticket
    }

    private fun resolveLocalLocked(
        snapshot: UltraMultiOutputRenderSnapshot,
    ): UltraEglResolvedSubmission {
        val outputGenerations = LinkedHashMap<DisplayDestination, Long>()
        val ingressGenerations = LinkedHashMap<UltraEglIngressKey, Long>()
        snapshot.scenes.forEach { scene ->
            val output = outputs[scene.destination]
                ?: throw IllegalArgumentException("No exact EGL output for ${scene.destination}")
            require(output.lifecycle == OutputLifecycle.ACTIVE && output.target.isValid) {
                "EGL output is unavailable for ${scene.destination}"
            }
            require(scene.viewportPixels == output.target.descriptor.sizePixels) {
                "Scene viewport does not match attached physical output pixels"
            }
            outputGenerations[scene.destination] = output.generation
            scene.layers.forEach { layer ->
                val key = layer.source.toIngressKeyOrNull() ?: return@forEach
                val ingress = ingresses[key]
                    ?: throw IllegalArgumentException("No exact EGL ingress for $key")
                require(ingress.lifecycle == IngressLifecycle.ACTIVE) {
                    "EGL ingress is unavailable: $key"
                }
                ingressGenerations[key] = ingress.generation
            }
        }
        return UltraEglResolvedSubmission(
            scene = snapshot,
            graphRevision = null,
            ingressGenerations = ingressGenerations.toMap(),
            outputGenerations = outputGenerations.toMap(),
        )
    }

    private fun resolvePlanLocked(
        plan: UltraResolvedMultiOutputRenderPlan,
    ): UltraEglResolvedSubmission = resolveUltraEglPlan(
        plan = plan,
        outputs = outputs.mapNotNull { (destination, record) ->
            if (record.lifecycle != OutputLifecycle.ACTIVE) null else {
                destination to UltraEglBoundOutput(
                    generation = record.generation,
                    graphOutput = record.graphOutput,
                    sizePixels = record.target.descriptor.sizePixels,
                    isValid = record.target.isValid,
                )
            }
        }.toMap(),
        ingresses = ingresses.mapNotNull { (key, record) ->
            if (record.lifecycle != IngressLifecycle.ACTIVE) null else {
                key to UltraEglBoundIngress(
                    generation = record.generation,
                    graphIngress = record.graphIngress,
                    remoteHandle = record.remoteHandle,
                    active = true,
                )
            }
        }.toMap(),
    )

    private fun validateResolvedIsCurrentLocked(resolved: UltraEglResolvedSubmission) {
        resolved.outputGenerations.forEach { (destination, expectedGeneration) ->
            val output = outputs[destination]
            require(
                output?.generation == expectedGeneration &&
                    output.lifecycle == OutputLifecycle.ACTIVE &&
                    output.target.isValid,
            ) { "Resolved EGL output is stale for $destination" }
        }
        resolved.ingressGenerations.forEach { (key, expectedGeneration) ->
            val ingress = ingresses[key]
            require(
                ingress?.generation == expectedGeneration &&
                    ingress.lifecycle == IngressLifecycle.ACTIVE,
            ) { "Resolved EGL ingress is stale for $key" }
        }
    }

    private fun UltraSceneSource.toIngressKeyOrNull(): UltraEglIngressKey? = when (this) {
        is UltraSceneSource.Remote -> UltraEglIngressKey.Remote(ingressId)
        is UltraSceneSource.Local -> UltraEglIngressKey.Local(contentId)
        is UltraSceneSource.Overlay -> UltraEglIngressKey.Overlay(contentId)
        is UltraSceneSource.Instrument -> UltraEglIngressKey.Instrument(destination)
        UltraSceneSource.PunchThrough -> null
    }

    private class InstrumentLease(
        private val owner: AndroidUltraEglBackend,
        private val record: IngressRecord,
    ) : UltraEglInstrumentRasterLease() {
        override val key: UltraEglIngressKey.Instrument
            get() = record.key as UltraEglIngressKey.Instrument
        override val generation: Long get() = record.generation
        override val sizePixels: PixelSize
            get() = checkNotNull(record.rasterSizePixels)

        override fun replaceCompleteOpaqueFrame(argbPixels: IntArray):
            UltraEglInstrumentUploadReceipt =
            owner.replaceInstrumentFrame(record, argbPixels)

        override fun replaceOwnedCompleteOpaqueFrame(frame: OwnedOpaqueArgbFrame):
            UltraEglInstrumentUploadReceipt = owner.replaceInstrumentFrame(record, frame)

        override fun close() {
            val result = owner.retireIngress(record, DEFAULT_CLEANUP_TIMEOUT_MILLIS)
            check(result == null || result.isSafeToReleaseSurface) {
                "Instrument GL texture retirement is not proven: $result"
            }
        }
    }

    private fun checkMutableLocked() {
        check(!closed) { "Ultra EGL backend is closed" }
        check(!closeRequested) { "Ultra EGL backend is retirement-uncertain or closing" }
    }

    private fun nextGenerationLocked(): Long {
        check(generation < Long.MAX_VALUE) { "Ultra EGL lease generation exhausted" }
        generation += 1L
        return generation
    }

    private class IngressLease(
        private val owner: AndroidUltraEglBackend,
        private val record: IngressRecord,
    ) : UltraEglIngressLease() {
        override val key: UltraEglIngressKey
            get() = record.key
        override val producerSurface: Surface
            get() = checkNotNull(record.producerSurface)

        override fun retire(timeoutMillis: Long): AndroidVideoSurfaceRetirementResult? =
            owner.retireIngress(record, timeoutMillis)
    }

    private class OutputLease(
        private val owner: AndroidUltraEglBackend,
        private val record: OutputRecord,
    ) : UltraEglOutputLease() {
        override val destination: DisplayDestination
            get() = record.destination

        override fun close() = owner.detachOutput(record)
    }

    private class OutputBatchLease(
        private val owner: AndroidUltraEglBackend,
        override val generation: Long,
    ) : UltraEglOutputBatchLease() {
        override val isCurrent: Boolean
            get() = owner.isOutputBatchCurrent(generation)

        override fun close() = owner.retireOutputBatch(generation)
    }

    private companion object {
        const val MAXIMUM_OUTPUTS = 64
        const val MAXIMUM_TOTAL_INGRESSES = 256
        const val DEFAULT_CLEANUP_TIMEOUT_MILLIS = 2_000L
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 5_000L
        const val MAXIMUM_CLOSE_TIMEOUT_MILLIS = 60_000L
    }
}

private fun remainingMillis(deadlineNanos: Long): Long {
    val nanos = remainingNanos(deadlineNanos)
    if (nanos == Long.MAX_VALUE) return Long.MAX_VALUE
    return nanos / 1_000_000L
}
