package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraSceneSource
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Complete, prevalidated replacement intent; one EGL control command owns every candidate. */
internal data class UltraEglOutputBatchCandidate(
    val destination: DisplayDestination,
    val generation: Long,
    val nativeWindow: Any,
    val sizePixels: PixelSize,
    val targetIsValid: () -> Boolean,
)

/**
 * Pure Kotlin renderer state machine. It deliberately knows no Android classes; JVM tests drive it
 * with a fake [UltraEglDriver], while production supplies EGL14/GLES20 resources.
 */
internal class UltraEglRenderCore(
    private val driver: UltraEglDriver,
    private val signalFrame: (UltraEglFrameToken) -> Unit,
    private val observeRemoteAcquisition: (UltraEglAcquiredRemoteFrame) -> Unit,
) {
    constructor(driver: UltraEglDriver, signalFrame: (UltraEglFrameToken) -> Unit) :
        this(driver, signalFrame, {})

    private enum class IngressPhase { ACTIVE, QUIESCED }

    private data class Ingress(
        val generation: Long,
        val sizePixels: PixelSize?,
        val resource: UltraEglDriverIngress,
        val frameSignals: AtomicLong,
        var frameUpdates: Long,
        var processedSignals: Long,
        var phase: IngressPhase,
    )

    private data class Output(
        val generation: Long,
        val nativeWindow: Any,
        val sizePixels: PixelSize,
        val resource: UltraEglDriverOutput,
        val targetIsValid: () -> Boolean,
    )

    private val ingresses = LinkedHashMap<UltraEglIngressKey, Ingress>()
    private val outputs = LinkedHashMap<DisplayDestination, Output>()
    private val retiredOutputBatches = LinkedHashMap<Long, MutableList<Output>>()
    private var activeOutputBatchGeneration: Long? = null
    private var phase = UltraEglBackendPhase.STARTING
    private var lastPresentedRevision = -1L
    /** Android EGL driver uses one timeline across all outputs and later scene replays. */
    private var lastProducerTimestampNanos = 0L
    private var lastClusterReplaySequence = 0L
    private var currentSubmission: UltraEglResolvedSubmission? = null
    /** Only the immediately preceding, successfully proven scene can supply carry pixels. */
    private var lastLocalReceipt: UltraEglLocalFirstFrameReceipt? = null
    private var carriablePriorReceipt: UltraEglLocalFirstFrameReceipt? = null
    private var failure: Throwable? = null

    fun initialize() {
        check(phase == UltraEglBackendPhase.STARTING) { "EGL core already initialized" }
        try {
            driver.initialize()
            phase = UltraEglBackendPhase.OPEN
        } catch (problem: Throwable) {
            fail(problem)
            throw problem
        }
    }

    fun openIngress(
        key: UltraEglIngressKey,
        generation: Long,
        sizePixels: PixelSize?,
    ): Any {
        checkOpen()
        require(generation > 0L) { "Ingress generation must be positive" }
        require(key !in ingresses) { "EGL ingress is already open: $key" }
        require((key is UltraEglIngressKey.Remote) == (sizePixels == null)) {
            "Remote EGL ingress size is decoder-owned; local/overlay require physical pixels"
        }
        val frameSignals = AtomicLong()
        val resource = driverCall {
            driver.createExternalIngress(key, sizePixels) { callbackIngress ->
                frameSignals.incrementAndGet()
                signalFrame(UltraEglFrameToken(key, generation, callbackIngress))
            }
        }
        ingresses[key] = Ingress(generation, sizePixels, resource, frameSignals, 0L, 0L,
            IngressPhase.ACTIVE)
        return resource.producerEndpoint
    }

    /** A dedicated CLUSTER texture; no producer Surface exists for this ingress. */
    fun openInstrumentIngress(
        destination: DisplayDestination,
        generation: Long,
        sizePixels: PixelSize,
    ) {
        checkOpen()
        require(destination.role == com.shilapi.xcertplay.ultra.DisplayDestinationRole.CLUSTER)
        require(generation > 0L)
        require(sizePixels.widthPixels.toLong() * sizePixels.heightPixels <= 1_600_000L)
        val key = UltraEglIngressKey.Instrument(destination)
        require(key !in ingresses) { "Instrument EGL ingress is already open" }
        val resource = driverCall { driver.createInstrumentIngress(destination, sizePixels) }
        ingresses[key] = Ingress(generation, sizePixels, resource, AtomicLong(), 0L, 0L,
            IngressPhase.ACTIVE)
    }

    /** Upload + any current-scene replay + every output swap/finish complete before return. */
    fun replaceInstrumentFrame(
        destination: DisplayDestination,
        generation: Long,
        sizePixels: PixelSize,
        argbPixels: IntArray,
    ): UltraEglClusterReplayReceipt? {
        checkOpen()
        val key = UltraEglIngressKey.Instrument(destination)
        val ingress = requireNotNull(ingresses[key]) { "Instrument EGL ingress is absent" }
        require(ingress.generation == generation && ingress.phase == IngressPhase.ACTIVE) {
            "Instrument EGL ingress generation is not active"
        }
        require(ingress.sizePixels == sizePixels) {
            "Instrument frame size differs from the opened GL texture"
        }
        val pixelCount = sizePixels.widthPixels.toLong() * sizePixels.heightPixels
        require(pixelCount <= 1_600_000L && argbPixels.size.toLong() == pixelCount &&
            argbPixels.all { it ushr 24 == 0xff }
        ) { "Instrument frame must be one bounded complete opaque ARGB viewport" }
        val replay = currentSubmission?.takeIf {
            it.ingressGenerations[key] == generation &&
                it.scene.scene(destination)?.viewportPixels == sizePixels
        }
        if (replay != null) preflightOutputTargets()
        driverCall { driver.uploadInstrumentFrame(ingress.resource, argbPixels) }
        ingress.frameUpdates++
        return replay?.let {
            renderSubmission(it, UltraEglClusterReplayCause.INSTRUMENT_UPLOAD).clusterReplay
        }
    }

    /**
     * Stops future sampling and waits for prior GPU reads. Only a scene which still references
     * this exact ingress generation is discarded: a replacement scene may already have been
     * submitted without it, and must keep replaying when its other ingresses update.
     * The producer endpoint remains alive until its separate retirement acknowledgement.
     */
    fun quiesceIngress(key: UltraEglIngressKey, generation: Long) {
        checkNotClosed()
        val ingress = ingresses[key] ?: return
        if (ingress.generation != generation || ingress.phase == IngressPhase.QUIESCED) return
        if (currentSubmission?.ingressGenerations?.get(key) == generation) {
            currentSubmission = null
        }
        carriablePriorReceipt = null
        driverCallAllowFailed { driver.finish() }
        ingress.phase = IngressPhase.QUIESCED
    }

    /** Releases the producer Surface/SurfaceTexture only after external producer retirement. */
    fun destroyIngress(key: UltraEglIngressKey, generation: Long) {
        checkNotClosed()
        val ingress = ingresses[key] ?: return
        if (ingress.generation != generation) return
        check(ingress.phase == IngressPhase.QUIESCED) {
            "EGL ingress must be quiesced before destruction"
        }
        driverCallAllowFailed { driver.destroyIngress(ingress.resource) }
        ingresses.remove(key)
    }

    /**
     * Creates the replacement before installing it. Once this method returns, glFinish has retired
     * the prior EGLSurface and the old borrowed native window may be released by its owner.
     */
    fun attachOutput(
        destination: DisplayDestination,
        generation: Long,
        nativeWindow: Any,
        sizePixels: PixelSize,
        targetIsValid: () -> Boolean,
    ) {
        checkOpen()
        check(activeOutputBatchGeneration == null) {
            "Single-output attach cannot mix with an installed atomic output batch"
        }
        require(generation > 0L) { "Output generation must be positive" }
        val replacement = driverCall {
            driver.createWindowOutput(destination, nativeWindow, sizePixels)
        }
        val previous = outputs[destination]
        try {
            driverCall { driver.finish() }
            if (previous != null) driverCall { driver.destroyOutput(previous.resource) }
        } catch (problem: Throwable) {
            // The caller still owns the replacement native window. Prove the failed candidate no
            // longer has an EGL reference before returning the failure.
            try {
                driver.destroyOutput(replacement)
            } catch (cleanup: Throwable) {
                throw UltraEglCandidateResourceRetainedException(problem, cleanup)
            }
            throw problem
        }
        outputs[destination] = Output(generation, nativeWindow, sizePixels, replacement, targetIsValid)
        currentSubmission = null
        carriablePriorReceipt = null
    }

    /**
     * Stages every EGLSurface on this owner thread, then publishes a complete routing generation.
     * A clean candidate failure leaves the old generation and its native windows untouched.
     * Publication is atomic for routing, not for the later per-display eglSwapBuffers calls.
     */
    fun replaceOutputBatch(
        generation: Long,
        candidates: List<UltraEglOutputBatchCandidate>,
    ) {
        checkOpen()
        check(outputs.isEmpty() || activeOutputBatchGeneration != null) {
            "Atomic output batches cannot replace individually attached outputs"
        }
        require(generation > 0L) { "Output batch generation must be positive" }
        require(candidates.size <= MAXIMUM_BATCH_OUTPUTS) { "Too many EGL batch outputs" }
        require(candidates.map { it.destination }.toSet().size == candidates.size) {
            "An EGL batch destination cannot appear twice"
        }
        require(candidates.all { it.generation == generation }) {
            "Every EGL batch output must have the same generation"
        }
        require(generation != activeOutputBatchGeneration && generation !in retiredOutputBatches) {
            "EGL batch generation has already been installed"
        }

        val staged = LinkedHashMap<DisplayDestination, Output>()
        val newlyCreated = ArrayList<Output>(candidates.size)
        val transferredDestinations = HashSet<DisplayDestination>()
        var finishFailed = false
        try {
            candidates.forEach { candidate ->
                check(candidate.targetIsValid()) {
                    "EGL batch target is invalid for ${candidate.destination}"
                }
                val previous = outputs[candidate.destination]
                val transfer = previous?.takeIf {
                    it.nativeWindow === candidate.nativeWindow &&
                        it.sizePixels == candidate.sizePixels
                }
                require(previous?.nativeWindow !== candidate.nativeWindow || transfer != null) {
                    "An EGL output window cannot be transferred across a physical size change"
                }
                val resource = transfer?.resource ?: driver.createWindowOutput(
                    candidate.destination,
                    candidate.nativeWindow,
                    candidate.sizePixels,
                )
                val stagedOutput = Output(
                    generation,
                    candidate.nativeWindow,
                    candidate.sizePixels,
                    resource,
                    candidate.targetIsValid,
                )
                staged[candidate.destination] = stagedOutput
                if (transfer == null) newlyCreated += stagedOutput
                else transferredDestinations += candidate.destination
                driver.validateOutputSize(resource, candidate.sizePixels)
            }
            // Retire earlier GPU reads before a future fence may release the old native windows.
            try {
                driver.finish()
            } catch (problem: Throwable) {
                finishFailed = true
                throw problem
            }
            staged.forEach { (destination, output) ->
                check(output.targetIsValid()) {
                    "EGL batch target closed during staging for $destination"
                }
                driver.validateOutputSize(output.resource, output.sizePixels)
            }
        } catch (problem: Throwable) {
            var cleanupFailure: Throwable? = null
            newlyCreated.asReversed().forEach { candidate ->
                try {
                    driver.destroyOutput(candidate.resource)
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure
                    else cleanupFailure?.addSuppressed(failure)
                }
            }
            if (cleanupFailure != null) {
                val retained = UltraEglCandidateResourceRetainedException(
                    problem,
                    checkNotNull(cleanupFailure),
                )
                fail(retained)
                throw retained
            }
            if (finishFailed || problem is UltraEglContextLostException ||
                problem is UltraEglCandidateResourceRetainedException
            ) {
                fail(problem)
                throw UltraEglBatchCommitUncertainException(problem)
            }
            throw problem
        }

        val previousGeneration = activeOutputBatchGeneration
        if (previousGeneration != null) {
            retiredOutputBatches[previousGeneration] = outputs
                .filterKeys { it !in transferredDestinations }
                .values.toMutableList()
        }
        outputs.clear()
        outputs.putAll(staged)
        activeOutputBatchGeneration = generation
        currentSubmission = null
        carriablePriorReceipt = null
    }

    /** Retryable GPU/EGL retirement of exactly this generation, never its replacement. */
    fun retireOutputBatch(generation: Long) {
        checkNotClosed()
        if (activeOutputBatchGeneration == generation) {
            currentSubmission = null
            carriablePriorReceipt = null
            retiredOutputBatches[generation] = outputs.values.toMutableList()
            outputs.clear()
            activeOutputBatchGeneration = null
        }
        val pending = retiredOutputBatches[generation] ?: return
        driverCallAllowFailed { driver.finish() }
        while (pending.isNotEmpty()) {
            val output = pending.first()
            driverCallAllowFailed { driver.destroyOutput(output.resource) }
            pending.removeAt(0)
        }
        retiredOutputBatches.remove(generation)
    }

    fun detachOutput(destination: DisplayDestination, generation: Long) {
        checkNotClosed()
        check(activeOutputBatchGeneration == null) {
            "Single-output detach cannot retire an atomic output batch"
        }
        val output = outputs[destination] ?: return
        if (output.generation != generation) return
        currentSubmission = null
        carriablePriorReceipt = null
        driverCallAllowFailed { driver.finish() }
        driverCallAllowFailed { driver.destroyOutput(output.resource) }
        outputs.remove(destination)
    }

    fun submit(submission: UltraEglResolvedSubmission): UltraEglSealedPresentationReceipt {
        checkOpen()
        require(submission.revision > lastPresentedRevision) {
            "Render revision ${submission.revision} is not newer than $lastPresentedRevision"
        }
        validateExactBindings(submission)
        val prior = currentSubmission
        val priorReceipt = lastLocalReceipt?.takeIf { it.revision == prior?.revision }
        val pass = renderSubmission(submission, UltraEglClusterReplayCause.SCENE_SUBMISSION)
        currentSubmission = submission
        carriablePriorReceipt = priorReceipt
        lastPresentedRevision = submission.revision
        return UltraEglSealedPresentationReceipt(
            submission.revision,
            submission.graphRevision,
            activeOutputBatchGeneration,
            Collections.unmodifiableMap(LinkedHashMap(submission.outputGenerations)),
            pass.producerTimestamps,
            pass.clusterReplay,
        )
    }

    fun frameAvailable(token: UltraEglFrameToken): UltraEglClusterReplayReceipt? {
        if (phase != UltraEglBackendPhase.OPEN) return null
        val ingress = ingresses[token.key] ?: return null
        if (
            ingress.phase != IngressPhase.ACTIVE ||
            ingress.generation != token.generation ||
            ingress.resource !== token.ingress
        ) return null
        val signalled = ingress.frameSignals.get()
        // A first-frame proof can consume the callback before its queued Frame command runs.
        // Never updateTexImage twice for one signal; an additional signal remains observable.
        if (ingress.processedSignals >= signalled) return null
        val replay = currentSubmission?.takeIf {
            it.ingressGenerations[token.key] == ingress.generation
        }
        if (replay != null) preflightOutputTargets()
        driverCall { driver.updateIngressFrame(ingress.resource) }
        ingress.frameUpdates++
        ingress.processedSignals = signalled
        if (token.key is UltraEglIngressKey.Remote) {
            ingress.resource.lastAcquiredTimestampNanos?.takeIf { it > 0L }?.let { timestamp ->
                // Identity correlation is strictly auxiliary. A missing/late decoder receipt or
                // observer failure must not break existing Remote playback or scene replay.
                try {
                    observeRemoteAcquisition(UltraEglAcquiredRemoteFrame(
                        token.key, ingress.generation, ingress.resource,
                        ingress.frameUpdates, timestamp,
                    ))
                } catch (_: RuntimeException) {
                    // Keep ordinary CarPlay frame scheduling independent of optional evidence.
                }
            }
        }
        if (replay != null) {
            return renderSubmission(replay,
                UltraEglClusterReplayCause.INGRESS_FRAME_REPLAY).clusterReplay
        }
        return null
    }

    /** Capture fresh-post baselines and immediately preceding carried-source identity. */
    fun prepareLocalFirstFrame(
        revision: Long,
        physicalBatchGeneration: Long,
        expectedLocalKeys: Set<UltraEglIngressKey.Local>,
        carriedFrom: UltraEglLocalFirstFrameReceipt? = null,
        carriedKeys: Set<UltraEglIngressKey.Local> = emptySet(),
    ): UltraEglLocalFirstFrameExpectation? {
        val nativeBatchGeneration = activeOutputBatchGeneration ?: return null
        if (phase != UltraEglBackendPhase.OPEN || expectedLocalKeys.isEmpty() ||
            physicalBatchGeneration <= 0L
        ) return null
        val submission = currentSubmission?.takeIf { it.revision == revision } ?: return null
        if (submission.scene.scenes.map { it.destination }.toSet() != outputs.keys) return null
        if (!expectedLocalKeys.containsAll(carriedKeys) ||
            (carriedKeys.isEmpty() != (carriedFrom == null))
        ) return null
        if (carriedFrom != null && (carriedFrom !== carriablePriorReceipt ||
            carriedFrom.graphRevision != submission.graphRevision ||
            carriedFrom.physicalBatchGeneration != physicalBatchGeneration ||
            carriedFrom.outputBatchGeneration != nativeBatchGeneration ||
            carriedFrom.ingressGenerations.keys.intersect(carriedKeys) != carriedKeys)
        ) return null
        val required = submission.scene.scenes.flatMap { scene ->
            scene.layers.mapIndexedNotNull { index, layer ->
                val key = (layer.source as? UltraSceneSource.Local)?.let {
                    UltraEglIngressKey.Local(it.contentId)
                } ?: return@mapIndexedNotNull null
                UltraEglLocalLayerDraw(scene.destination, key, index, layer)
            }
        }.toSet()
        if (required.map { it.key }.toSet() != expectedLocalKeys) return null
        if (submission.outputGenerations.any { (destination, generation) ->
                outputs[destination]?.generation != generation
            } || submission.ingressGenerations.any { (key, generation) ->
                ingresses[key]?.takeIf {
                    it.generation == generation && it.phase == IngressPhase.ACTIVE
                } == null
            }
        ) return null
        val baselines = LinkedHashMap<UltraEglIngressKey.Local, Long>()
        val carrySignals = LinkedHashMap<UltraEglIngressKey.Local, Long>()
        val carryUpdates = LinkedHashMap<UltraEglIngressKey.Local, Long>()
        for (key in expectedLocalKeys) {
            val ingress = ingresses[key] ?: return null
            if (key in carriedKeys) {
                val previous = checkNotNull(carriedFrom)
                if (ingress.generation != previous.ingressGenerations[key] ||
                    ingress.frameSignals.get() != previous.frameSignals[key] ||
                    ingress.frameUpdates != previous.frameUpdates[key] ||
                    !ingress.resource.hasUsableFrame ||
                    previous.postSequences[key] == null
                ) return null
                carrySignals[key] = ingress.frameSignals.get()
                carryUpdates[key] = ingress.frameUpdates
                continue
            }
            // A callback that was merely delayed from an older post is not evidence of this
            // revision's Canvas pixels. Only a never-used Local ingress may arm this fence.
            if (ingress.frameSignals.get() != 0L || ingress.frameUpdates != 0L ||
                ingress.resource.hasUsableFrame
            ) return null
            baselines[key] = 0L
        }
        return UltraEglLocalFirstFrameExpectation(
            submission,
            physicalBatchGeneration,
            nativeBatchGeneration,
            Collections.unmodifiableSet(required),
            Collections.unmodifiableMap(baselines),
            carriedFrom,
            Collections.unmodifiableSet(LinkedHashSet(carriedKeys)),
            Collections.unmodifiableMap(carrySignals),
            Collections.unmodifiableMap(carryUpdates),
        )
    }

    /**
     * A bounded caller invokes this on the EGL owner thread after every exact Canvas post. A
     * callback missing at this instant is retryable; stale scene, ingress, or output is not.
     */
    fun tryPresentLocalFirstFrame(
        expected: UltraEglLocalFirstFrameExpectation,
        postSequences: Map<UltraEglIngressKey.Local, Long>,
    ): UltraEglLocalFirstFrameReceipt? {
        if (phase != UltraEglBackendPhase.OPEN || currentSubmission !== expected.submission ||
            lastLocalReceipt?.revision == expected.submission.revision ||
            activeOutputBatchGeneration != expected.outputBatchGeneration ||
            postSequences.keys != expected.frameSignalsBeforePost.keys + expected.carriedKeys ||
            postSequences.values.any { it <= 0L } ||
            postSequences.values.toSet().size != postSequences.size ||
            (expected.carriedFrom != null && expected.carriedFrom !== carriablePriorReceipt) ||
            expected.carriedKeys.any { key ->
                postSequences[key] != expected.carriedFrom?.postSequences?.get(key)
            }
        ) return null
        val submission = expected.submission
        if (submission.scene.scenes.map { it.destination }.toSet() != outputs.keys ||
            submission.outputGenerations.any { (destination, generation) ->
                outputs[destination]?.generation != generation
            } || submission.ingressGenerations.any { (key, generation) ->
                ingresses[key]?.takeIf {
                    it.generation == generation && it.phase == IngressPhase.ACTIVE
                } == null
            }
        ) return null
        if (expected.frameSignalsBeforePost.any { (key, before) ->
                (ingresses[key]?.frameSignals?.get() ?: 0L) != before + 1L
            }
        ) return null
        if (expected.carriedKeys.any { key ->
                val ingress = ingresses[key] ?: return null
                ingress.frameSignals.get() != expected.carriedSignals[key] ||
                    ingress.frameUpdates != expected.carriedUpdates[key] ||
                    !ingress.resource.hasUsableFrame
            }
        ) return null
        // GLES would skip a transparent or empty-clipped local layer. Reject it before any swap.
        if (expected.requiredDraws.any { draw ->
                val scene = submission.scene.scenes.singleOrNull {
                    it.destination == draw.destination
                } ?: return null
                scene.layers.getOrNull(draw.layerIndex) != draw.layer ||
                    draw.layer.source != UltraSceneSource.Local(draw.key.contentId) ||
                    UltraEglLayerGeometry.project(draw.layer, scene.viewportPixels) == null
            }
        ) return null
        preflightOutputTargets()
        expected.frameSignalsBeforePost.keys.forEach { key ->
            val ingress = checkNotNull(ingresses[key])
            val signalled = ingress.frameSignals.get()
            if (ingress.processedSignals < signalled) {
                driverCall { driver.updateIngressFrame(ingress.resource) }
                ingress.frameUpdates++
                ingress.processedSignals = signalled
            }
            if (!ingress.resource.hasUsableFrame) return null
        }
        val byDestination = submission.scene.scenes.associateBy { it.destination }
        val issued = LinkedHashSet<UltraEglLocalLayerDraw>()
        val producerTimestamps = LinkedHashMap<DisplayDestination, Long>()
        outputs.forEach { (destination, output) ->
            val scene = byDestination[destination] ?: return null
            if (scene.viewportPixels != output.sizePixels) return null
            val resolved = UltraEglResolvedScene(
                destination, scene.viewportPixels, scene.background,
                scene.layers.map { layer ->
                    val key = layer.source.ingressKeyOrNull()
                    UltraEglResolvedLayer(layer, key?.let { ingresses[it]?.resource })
                },
            )
            val rendered = driverCall {
                driver.renderWithLocalDrawPresentationReceipt(output.resource, resolved)
            }
            issued += rendered.localDraws
            recordProducerTimestamp(destination, rendered.producerTimestampNanos,
                producerTimestamps)
        }
        driverCall { driver.finish() }
        if (issued != expected.requiredDraws || expected.frameSignalsBeforePost.any { (key, before) ->
                ingresses[key]?.frameSignals?.get() != before + 1L ||
                    ingresses[key]?.frameUpdates != 1L
            } || expected.carriedKeys.any { key ->
                ingresses[key]?.frameSignals?.get() != expected.carriedSignals[key] ||
                    ingresses[key]?.frameUpdates != expected.carriedUpdates[key]
            }
        ) return null
        val clusterReplay = sealClusterReplay(submission, producerTimestamps,
            UltraEglClusterReplayCause.LOCAL_FIRST_FRAME)
        return UltraEglLocalFirstFrameReceipt(
            submission.revision,
            submission.graphRevision,
            expected.physicalBatchGeneration,
            expected.outputBatchGeneration,
            Collections.unmodifiableSet(LinkedHashSet(expected.requiredDraws)),
            Collections.unmodifiableMap(LinkedHashMap(postSequences)),
            Collections.unmodifiableMap(postSequences.keys.associateWith { key ->
                checkNotNull(submission.ingressGenerations[key])
            }),
            Collections.unmodifiableMap(postSequences.keys.associateWith { key ->
                checkNotNull(ingresses[key]).frameSignals.get()
            }),
            Collections.unmodifiableMap(postSequences.keys.associateWith { key ->
                checkNotNull(ingresses[key]).frameUpdates
            }),
            Collections.unmodifiableMap(LinkedHashMap(producerTimestamps)),
            Collections.unmodifiableMap(LinkedHashMap(submission.outputGenerations)),
            clusterReplay,
        ).also { lastLocalReceipt = it }
    }

    /** Requires all decoder-backed ingresses to have been safely retired by the caller first. */
    fun closeDriver(deadlineNanos: Long): UltraEglDriverRetirementProof {
        if (phase == UltraEglBackendPhase.CLOSED) {
            return UltraEglDriverRetirementProof(retired = true)
        }
        check(ingresses.values.all { it.phase == IngressPhase.QUIESCED }) {
            "All EGL ingresses must be quiesced before driver close"
        }
        val proof = try {
            driver.closeWithProof(deadlineNanos)
        } catch (problem: Throwable) {
            UltraEglDriverRetirementProof(retired = false, failure = problem)
        }
        if (proof.retired) {
            outputs.clear()
            retiredOutputBatches.clear()
            activeOutputBatchGeneration = null
            ingresses.clear()
            currentSubmission = null
            phase = UltraEglBackendPhase.CLOSED
        } else {
            proof.failure?.let { if (failure == null) failure = it }
            phase = UltraEglBackendPhase.RETIREMENT_UNCERTAIN
        }
        return proof
    }

    fun snapshot(): UltraEglCoreSnapshot = UltraEglCoreSnapshot(
        phase = phase,
        ingressStates = ingresses.mapValues { it.value.phase.name },
        outputSizes = outputs.mapValues { it.value.sizePixels },
        lastPresentedRevision = lastPresentedRevision,
        failure = failure,
    )

    private data class RenderPass(
        val producerTimestamps: Map<DisplayDestination, Long>,
        val clusterReplay: UltraEglClusterReplayReceipt?,
    )

    private fun renderSubmission(
        submission: UltraEglResolvedSubmission,
        cause: UltraEglClusterReplayCause,
    ): RenderPass {
        preflightOutputTargets()
        val sceneByDestination = submission.scene.scenes.associateBy { it.destination }
        val producerTimestamps = LinkedHashMap<DisplayDestination, Long>()
        outputs.forEach { (destination, output) ->
            val scene = sceneByDestination[destination]
            if (scene == null) {
                // A cleared output is not a scene frame and must not supply cover evidence.
                val timestamp = driverCall {
                    driver.clearWithPresentationReceipt(
                        output.resource, destination, output.sizePixels,
                    )
                }
                // Clearing is a real producer swap, but cannot authorize a scene cover.
                recordProducerTimestamp(destination, timestamp, null)
            } else {
                require(scene.viewportPixels == output.sizePixels) {
                    "Scene viewport does not match the exact EGL output size for $destination"
                }
                val resolvedLayers = scene.layers.map { layer ->
                    val key = layer.source.ingressKeyOrNull()
                    val ingress = key?.let(ingresses::get)
                    UltraEglResolvedLayer(layer, ingress?.resource)
                }
                val timestamp = driverCall {
                    driver.renderWithPresentationReceipt(
                        output.resource,
                        UltraEglResolvedScene(
                            destination,
                            scene.viewportPixels,
                            scene.background,
                            resolvedLayers,
                        ),
                    )
                }
                recordProducerTimestamp(destination, timestamp, producerTimestamps)
            }
        }
        driverCall { driver.finish() }
        return RenderPass(
            Collections.unmodifiableMap(producerTimestamps),
            sealClusterReplay(submission, producerTimestamps, cause),
        )
    }

    private fun sealClusterReplay(
        submission: UltraEglResolvedSubmission,
        producerTimestamps: Map<DisplayDestination, Long>,
        cause: UltraEglClusterReplayCause,
    ): UltraEglClusterReplayReceipt? {
        val destination = DisplayDestination.CLUSTER
        val timestamp = producerTimestamps[destination] ?: return null
        val batchGeneration = activeOutputBatchGeneration ?: return null
        val outputGeneration = submission.outputGenerations[destination] ?: return null
        if (outputs[destination]?.generation != outputGeneration) return null
        val scene = submission.scene.scenes.singleOrNull {
            it.destination == destination
        } ?: return null
        val source = UltraSceneSource.Instrument(destination)
        val instrumentOnly = scene.layers.size == 1 && scene.layers.single().let { layer ->
            layer.source == source && layer.framePixels ==
                com.shilapi.xcertplay.ultra.UltraPixelRect(0, 0,
                    scene.viewportPixels.widthPixels, scene.viewportPixels.heightPixels) &&
                layer.clipPixels == null && layer.opacity == 1.0 &&
                layer.transformPixels.isIdentity
        }
        if (!instrumentOnly) return null
        val key = UltraEglIngressKey.Instrument(destination)
        val ingressGeneration = submission.ingressGenerations[key] ?: return null
        val ingress = ingresses[key]?.takeIf {
            it.generation == ingressGeneration && it.phase == IngressPhase.ACTIVE &&
                it.resource.hasUsableFrame && it.frameUpdates > 0L
        } ?: return null
        driverCall {
            check(lastClusterReplaySequence < Long.MAX_VALUE) {
                "CLUSTER replay sequence exhausted"
            }
        }
        val sequence = ++lastClusterReplaySequence
        return UltraEglClusterReplayReceipt(
            destination, submission.revision, submission.graphRevision,
            batchGeneration, outputGeneration, ingressGeneration,
            ingress.frameUpdates, timestamp, sequence, cause, instrumentOnly,
        )
    }

    private fun recordProducerTimestamp(
        destination: DisplayDestination,
        timestamp: Long?,
        receipt: MutableMap<DisplayDestination, Long>?,
    ) {
        if (timestamp == null) return
        driverCall {
            check(timestamp > lastProducerTimestampNanos) {
                "EGL producer timestamp was not positive and strictly increasing"
            }
        }
        lastProducerTimestampNanos = timestamp
        receipt?.set(destination, timestamp)
    }

    /**
     * All targets and physical EGLSurface dimensions are checked before the first output is
     * presented. This detects a closed or resized target while a scene was queued, but cannot pin
     * a borrowed native Surface against a concurrent later change; its owner must keep it alive
     * at the leased size until the output lease has closed.
     */
    private fun preflightOutputTargets() {
        outputs.forEach { (destination, output) ->
            val valid = try {
                output.targetIsValid()
            } catch (problem: Throwable) {
                currentSubmission = null
                throw UltraEglOutputTargetUnavailableException(destination, problem)
            }
            if (!valid) {
                currentSubmission = null
                throw UltraEglOutputTargetUnavailableException(destination)
            }
        }
        outputs.values.forEach { output ->
            driverCall { driver.validateOutputSize(output.resource, output.sizePixels) }
        }
    }

    private fun validateExactBindings(submission: UltraEglResolvedSubmission) {
        require(submission.outputGenerations.keys == submission.scene.scenes.map { it.destination }.toSet()) {
            "Resolved EGL submission does not exactly bind every output scene"
        }
        submission.outputGenerations.forEach { (destination, generation) ->
            val output = outputs[destination]
            require(output != null && output.generation == generation) {
                "Resolved EGL output lease is stale for $destination"
            }
        }

        val referencedKeys = submission.scene.scenes.asSequence()
            .flatMap { it.layers.asSequence() }
            .mapNotNull { it.source.ingressKeyOrNull() }
            .toSet()
        require(submission.ingressGenerations.keys == referencedKeys) {
            "Resolved EGL submission does not exactly bind every input layer"
        }
        submission.ingressGenerations.forEach { (key, generation) ->
            val ingress = ingresses[key]
            require(
                ingress != null &&
                    ingress.generation == generation &&
                    ingress.phase == IngressPhase.ACTIVE,
            ) { "Resolved EGL ingress lease is unavailable or stale: $key" }
        }
    }

    private fun UltraSceneSource.ingressKeyOrNull(): UltraEglIngressKey? = when (this) {
        is UltraSceneSource.Remote -> UltraEglIngressKey.Remote(ingressId)
        is UltraSceneSource.Local -> UltraEglIngressKey.Local(contentId)
        is UltraSceneSource.Overlay -> UltraEglIngressKey.Overlay(contentId)
        is UltraSceneSource.Instrument -> UltraEglIngressKey.Instrument(destination)
        UltraSceneSource.PunchThrough -> null
    }

    private fun checkOpen() {
        check(phase == UltraEglBackendPhase.OPEN) {
            "EGL core is not open (phase=$phase)"
        }
    }

    private fun checkNotClosed() {
        check(phase != UltraEglBackendPhase.CLOSED) { "EGL core is closed" }
    }

    private inline fun <T> driverCall(action: () -> T): T = try {
        action()
    } catch (problem: Throwable) {
        fail(problem)
        throw problem
    }

    private inline fun <T> driverCallAllowFailed(action: () -> T): T = try {
        action()
    } catch (problem: Throwable) {
        if (phase != UltraEglBackendPhase.FAILED) fail(problem)
        throw problem
    }

    private fun fail(problem: Throwable) {
        if (failure == null) failure = problem
        if (phase != UltraEglBackendPhase.CLOSED) phase = UltraEglBackendPhase.FAILED
        currentSubmission = null
        carriablePriorReceipt = null
    }

    private companion object {
        const val MAXIMUM_BATCH_OUTPUTS = 64
    }
}
