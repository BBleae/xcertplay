package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBinding
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.ultra.egl.AndroidUltraEglBackend
import com.shilapi.xcertplay.ultra.egl.AndroidUltraEglOutputBatchRequest
import com.shilapi.xcertplay.ultra.egl.UltraEglBatchCommitUncertainException
import com.shilapi.xcertplay.ultra.egl.UltraEglOutputBatchLease
import com.shilapi.xcertplay.ultra.egl.UltraEglBatchOwnerToken
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Opaque authority for one attempted bridge installation. Identity, not [value], binds a prepared
 * batch, its installed lease, and the caller-facing lease to the same operation.
 */
class UltraDisplayBridgeGeneration internal constructor(val value: Long) {
    override fun toString(): String = "UltraDisplayBridgeGeneration(value=$value)"
}

/** One explicit route. No identifier namespace is joined by comparing or parsing its text. */
data class UltraDisplayBatchRoute(
    val binding: UltraDisplayBinding,
    val destination: DisplayDestination,
    val outputTarget: DisplaySurfaceTarget,
    val outputDescriptor: DisplaySurfaceDescriptor,
) {
    val routerStreamPath: DisplayStreamPath = DisplayStreamPath.COMPOSITED
}

/** One exact V2 graph output; it may have no UI Sync ID and needs no remote video ingress. */
data class UltraDisplayBatchOutputRoute(
    val output: UltraDisplayGraphOutput,
    val destination: DisplayDestination,
    val outputTarget: DisplaySurfaceTarget,
    val outputDescriptor: DisplaySurfaceDescriptor,
) {
    val routerStreamPath: DisplayStreamPath = DisplayStreamPath.COMPOSITED
}

/**
 * A complete, immutable replacement topology for one bridge generation.
 *
 * Every route is intended to become both a [TransactionalUltraCompositor] output and a
 * [DisplaySurfaceRouter] `COMPOSITED` route. The compositor owns each route's output target;
 * the router must receive a non-owning [borrowForRouting] view whose lifetime is bounded by the same
 * batch lease. The eventual platform implementation must use a real batch primitive beneath both
 * owners; this contract intentionally supplies no adapter that loops over their single-destination
 * `attach` methods.
 */
class UltraDisplayBatchPlan internal constructor(
    val generation: UltraDisplayBridgeGeneration,
    val bindingRevision: Long?,
    val graphRevision: Long?,
    internal val graphSnapshot: UltraDisplayGraphSnapshot?,
    routes: List<UltraDisplayBatchRoute>,
    outputRoutes: List<UltraDisplayBatchOutputRoute>,
) {
    val routes: List<UltraDisplayBatchRoute> =
        Collections.unmodifiableList(ArrayList(routes))
    val outputRoutes: List<UltraDisplayBatchOutputRoute> =
        Collections.unmodifiableList(ArrayList(outputRoutes))

    init {
        require((bindingRevision != null) != (graphRevision != null)) {
            "A batch plan must select exactly one typed resource revision"
        }
        require((graphSnapshot == null) == (graphRevision == null)) {
            "A V2 batch plan must retain its exact graph snapshot"
        }
        require(graphSnapshot == null || graphSnapshot.revision == graphRevision) {
            "A V2 batch plan changed its graph revision"
        }
        require(if (graphSnapshot == null) outputRoutes.isEmpty() else routes.isEmpty()) {
            "Legacy bindings and V2 graph outputs cannot be mixed in one batch"
        }
    }
}

/** A retirement fence whose await and close operations must be idempotent and retry-safe. */
interface UltraDisplayRetirementFence : Closeable {
    /** Returns only when the backend/GPU can no longer access this generation's targets. */
    fun awaitRetirement()
}

/**
 * Ownership of one atomically installed batch.
 *
 * [beginRetirement] must be generation-safe: retiring a stale lease must never detach a newer
 * generation. It returns the same logical, retryable fence on repeated calls. [close] is idempotent
 * and retry-safe, releases only this generation's resources, never changes the installed topology,
 * and is called by the bridge only after that fence has completed and closed successfully. The
 * lease returned by [PreparedUltraDisplayBatch.commit] is implicitly bound to that prepared batch's
 * exact [UltraDisplayBridgeGeneration] object.
 */
interface UltraDisplayBatchLease : Closeable {
    /** False when the installed backend generation has become unusable, even before retirement. */
    val isOperational: Boolean get() = true

    fun beginRetirement(): UltraDisplayRetirementFence
}

/**
 * Side-effect-free preparation of a full replacement. Closing it is idempotent and retry-safe and
 * never affects an installed lease. [commit] is called at most once by the bridge.
 */
interface PreparedUltraDisplayBatch : Closeable {
    val generation: UltraDisplayBridgeGeneration

    /**
     * Atomically installs the entire plan in one batch operation. A clean failure leaves the old
     * batch current and transfers no target ownership. Native timeout or failed EGL cleanup is
     * reported as [UltraDisplayBatchCommitUncertainException] instead: the bridge fails closed,
     * and callers must retain targets until its retryable close proves full driver retirement.
     * On success, the lease owns every target and non-owning router view in the plan.
     */
    fun commit(): UltraDisplayBatchLease
}

/**
 * Narrow seam for a future true batch transaction across compositor outputs and router routes.
 *
 * [prepare] must not claim or close output targets and must not change the currently installed
 * topology. A conforming implementation performs exactly one all-or-nothing topology commit from
 * [PreparedUltraDisplayBatch.commit]. Sequential calls to `TransactionalUltraCompositor.attachOutput`
 * or `DisplaySurfaceRouter.attach` are not an implementation of this contract because they expose
 * partial topology and cannot preserve the old generation after a later route fails.
 *
 * The binder remains caller-owned. The caller must keep it alive until the bridge's [Closeable.close]
 * returns normally; a concurrent-operation or incomplete-retirement exception means close must be
 * retried before the binder/backend can be disposed.
 */
interface UltraDisplayBatchBinder {
    fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch
}

/** Native batch publication could not be proven successful or rolled back. */
class UltraDisplayBatchCommitUncertainException(cause: Throwable) : IllegalStateException(
    "Ultra display batch commit is uncertain; retain targets and retry bridge retirement",
    cause,
)

data class UltraDisplayBridgeRouteSnapshot(
    val outputId: UltraDisplayOutputId,
    val topologyDisplayId: UltraTopologyDisplayId,
    val uiSyncDisplayId: UltraUiSyncDisplayId?,
    val destination: DisplayDestination,
    val outputDescriptor: DisplaySurfaceDescriptor,
)

/** Immutable bridge state; target-controlled getters are never invoked while taking a snapshot. */
class UltraDisplayBindingBridgeSnapshot internal constructor(
    val revision: Long,
    val generation: Long?,
    val bindingRevision: Long?,
    val graphRevision: Long?,
    val backendCurrent: Boolean,
    val closed: Boolean,
    val operationInFlight: Boolean,
    val cleanupFailureCount: Int,
    val retiredInstallationCount: Int,
    val pendingPreparationCleanupCount: Int,
    routes: List<UltraDisplayBridgeRouteSnapshot>,
) {
    val routes: List<UltraDisplayBridgeRouteSnapshot> =
        Collections.unmodifiableList(ArrayList(routes))
}

/**
 * Atomically publishes a validated shared binding snapshot as one compositor/router generation.
 *
 * Mutations use fail-fast operation-token admission. No monitor or blocking operation lock is held
 * across a binder, target, lease, or fence callback, so cross-thread callback re-entry cannot
 * deadlock the bridge. Readers see either the complete old bridge generation or the complete new
 * one. Failed validation, preparation, or commit leaves the old bridge state and lease current.
 * Once a new generation is published, the old lease is retired only through its generation-safe
 * fence.
 */
class UltraDisplayBindingBatchBridge(
    private val binder: UltraDisplayBatchBinder,
) : Closeable {
    private val binderOwner = Any()
    private data class Installed(
        val generation: UltraDisplayBridgeGeneration,
        val plan: UltraDisplayBatchPlan,
        val batchLease: UltraDisplayBatchLease,
    )

    private data class RetiredInstallation(
        val generation: UltraDisplayBridgeGeneration,
        val lease: UltraDisplayBatchLease,
        var fence: UltraDisplayRetirementFence? = null,
        var retirementComplete: Boolean = false,
        var fenceClosed: Boolean = false,
    )

    private val stateLock = Any()
    private val retiredInstallations = IdentityHashMap<UltraDisplayBatchLease, RetiredInstallation>()
    private val pendingPreparationCleanup = IdentityHashMap<PreparedUltraDisplayBatch, Unit>()
    private var current: Installed? = null
    private var lastGraphSnapshot: UltraDisplayGraphSnapshot? = null
    private var nextGeneration = 0L
    private var revision = 0L
    private var cleanupFailureCount = 0
    private var operationToken: Any? = null
    private var binderOwnerReleased = false
    private var closed = false

    init {
        (binder as? AndroidUltraEglBatchBinder)?.claimBridgeOwner(binderOwner)
    }

    /**
     * Installs [bindings] using exact caller-provided typed maps. Both maps must contain precisely
     * the snapshot's output IDs; destinations, targets, and ownership guards must be one-to-one.
     */
    fun replace(
        bindings: UltraDisplayBindingSnapshot,
        destinationByOutput: Map<UltraDisplayOutputId, DisplayDestination>,
        targetByOutput: Map<UltraDisplayOutputId, DisplaySurfaceTarget>,
    ): UltraDisplayBindingBridgeLease = withOperation {
        synchronized(stateLock) {
            check(!closed) { "Ultra display binding bridge is closed" }
            current?.plan?.bindingRevision?.let { installedRevision ->
                require(bindings.revision >= installedRevision) {
                    "Cannot install stale binding revision ${bindings.revision} over $installedRevision"
                }
            }
        }
        val validated = validateReplacement(bindings, destinationByOutput, targetByOutput)
        publishValidatedPlan(bindings.revision, null, validated, emptyList())
    }

    /**
     * Publishes every output of one immutable V2 graph, independently of its ingress count.
     * The exact typed output IDs, topology IDs, and optional UI Sync IDs come from [graph].
     * Neither destination nor target identity is guessed from any identifier text.
     */
    fun replaceOutputs(
        graph: UltraDisplayGraphSnapshot,
        destinationByOutput: Map<UltraDisplayOutputId, DisplayDestination>,
        targetByOutput: Map<UltraDisplayOutputId, DisplaySurfaceTarget>,
        beforeCommit: () -> Unit = {},
    ): UltraDisplayBindingBridgeLease = withOperation {
        synchronized(stateLock) {
            check(!closed) { "Ultra display binding bridge is closed" }
            lastGraphSnapshot?.let { previous ->
                require(graph.revision >= previous.revision) {
                    "Cannot install stale graph revision ${graph.revision} over ${previous.revision}"
                }
                require(graph.revision != previous.revision || graph === previous) {
                    "Equal graph revisions must refer to the exact same graph snapshot"
                }
            }
        }
        val validated = validateOutputReplacement(graph, destinationByOutput, targetByOutput)
        publishValidatedPlan(null, graph, emptyList(), validated, beforeCommit)
    }

    /** Called only while the bridge operation token is held. */
    private fun publishValidatedPlan(
        bindingRevision: Long?,
        graph: UltraDisplayGraphSnapshot?,
        routes: List<UltraDisplayBatchRoute>,
        outputRoutes: List<UltraDisplayBatchOutputRoute>,
        beforeCommit: () -> Unit = {},
    ): UltraDisplayBindingBridgeLease {
        val generation = synchronized(stateLock) {
            check(!closed) { "Ultra display binding bridge closed during validation" }
            nextGeneration = bridgeIncrement(nextGeneration, "bridge generation")
            UltraDisplayBridgeGeneration(nextGeneration)
        }
        val plan = UltraDisplayBatchPlan(
            generation, bindingRevision, graph?.revision, graph, routes, outputRoutes,
        )

        val prepared = try {
            binder.prepare(plan)
        } catch (failure: Throwable) {
            retryDeferredCleanup()
            throw failure
        }
        val preparationValidationFailure = runCatching {
            check(prepared.generation === generation) {
                "Prepared batch returned the wrong bridge generation"
            }
        }.exceptionOrNull()
        if (preparationValidationFailure != null) {
            val failure = preparationValidationFailure
            closePreparation(prepared)?.let(failure::addSuppressed)
            retryDeferredCleanup()
            throw failure
        }

        val candidate = try {
            // This is the first point at which the prepared binder may touch native outputs.
            // A physical Surface owner can irreversibly mark all handoffs just-in-time; a
            // validation/prepare failure never invokes the callback and can restore legacy.
            beforeCommit()
            prepared.commit()
        } catch (failure: Throwable) {
            if (failure is UltraDisplayBatchCommitUncertainException) {
                synchronized(stateLock) {
                    closed = true
                    revision = bridgeIncrement(revision, "bridge revision")
                    current?.let { retireLocked(it.generation, it.batchLease) }
                    current = null
                }
            }
            closePreparation(prepared)?.let(failure::addSuppressed)
            retryDeferredCleanup()
            throw failure
        }

        val publicationFailure = runCatching {
            synchronized(stateLock) {
                check(!closed) { "Ultra display binding bridge closed during batch commit" }
                val nextRevision = bridgeIncrement(revision, "bridge revision")
                val previous = current
                current = Installed(generation, plan, candidate)
                if (graph != null) lastGraphSnapshot = graph
                revision = nextRevision
                previous?.let { retireLocked(it.generation, it.batchLease) }
            }
        }.exceptionOrNull()
        closePreparation(prepared)
        if (publicationFailure != null) {
            synchronized(stateLock) { retireLocked(generation, candidate) }
            retryDeferredCleanup()
            throw publicationFailure
        }
        retryDeferredCleanup()
        return UltraDisplayBindingBridgeLease(this, generation)
    }

    fun snapshot(): UltraDisplayBindingBridgeSnapshot {
        while (true) {
            val observed = synchronized(stateLock) { current }
            // EGL/backend liveness may take its own lock. Never invoke it under stateLock.
            val operational = observed?.batchLease?.let { lease ->
                runCatching { lease.isOperational }.getOrDefault(false)
            } == true
            val captured = synchronized(stateLock) {
                if (current !== observed) null else {
                    val backendCurrent = operational && !closed && operationToken == null
                    val usable = observed.takeIf { backendCurrent }
                    UltraDisplayBindingBridgeSnapshot(
                        revision = revision,
                        generation = observed?.generation?.value,
                        bindingRevision = observed?.plan?.bindingRevision,
                        graphRevision = observed?.plan?.graphRevision,
                        backendCurrent = backendCurrent,
                        closed = closed,
                        operationInFlight = operationToken != null,
                        cleanupFailureCount = cleanupFailureCount,
                        retiredInstallationCount = retiredInstallations.size,
                        pendingPreparationCleanupCount = pendingPreparationCleanup.size,
                        routes = usable?.plan?.routes.orEmpty().map { route ->
                            UltraDisplayBridgeRouteSnapshot(
                                outputId = route.binding.outputId,
                                topologyDisplayId = route.binding.topologyDisplayId,
                                uiSyncDisplayId = route.binding.uiSyncDisplayId,
                                destination = route.destination,
                                outputDescriptor = route.outputDescriptor,
                            )
                        } + usable?.plan?.outputRoutes.orEmpty().map { route ->
                            UltraDisplayBridgeRouteSnapshot(
                                outputId = route.output.outputId,
                                topologyDisplayId = route.output.topologyDisplayId,
                                uiSyncDisplayId = route.output.uiSyncDisplayId,
                                destination = route.destination,
                                outputDescriptor = route.outputDescriptor,
                            )
                        },
                    )
                }
            }
            if (captured != null) return captured
        }
    }

    override fun close() {
        val closeToken = synchronized(stateLock) {
            check(operationToken == null) {
                "Cannot close Ultra display binding bridge during an active operation"
            }
            val newlyClosed = !closed
            if (newlyClosed) {
                val nextRevision = bridgeIncrement(revision, "bridge revision")
                closed = true
                revision = nextRevision
            }
            Any().also { token ->
                operationToken = token
                current?.let { retireLocked(it.generation, it.batchLease) }
                current = null
            }
        }
        try {
            retryDeferredCleanup()
            val cleanupIncomplete = synchronized(stateLock) {
                retiredInstallations.isNotEmpty() || pendingPreparationCleanup.isNotEmpty()
            }
            check(!cleanupIncomplete) {
                "Ultra display binding bridge retirement is incomplete; keep binder alive and retry close"
            }
            if (!binderOwnerReleased) {
                (binder as? AndroidUltraEglBatchBinder)?.releaseBridgeOwner(binderOwner)
                binderOwnerReleased = true
            }
        } finally {
            synchronized(stateLock) {
                if (operationToken === closeToken) operationToken = null
            }
        }
    }

    internal fun isCurrent(generation: UltraDisplayBridgeGeneration): Boolean {
        val observed = synchronized(stateLock) {
            current?.takeIf { !closed && operationToken == null && it.generation === generation }
        } ?: return false
        val operational = runCatching { observed.batchLease.isOperational }.getOrDefault(false)
        return synchronized(stateLock) {
            operational && !closed && operationToken == null && current === observed
        }
    }

    internal fun detach(generation: UltraDisplayBridgeGeneration) {
        withOperation {
            synchronized(stateLock) {
                current?.takeIf { it.generation === generation }?.let { installed ->
                    val nextRevision = bridgeIncrement(revision, "bridge revision")
                    current = null
                    revision = nextRevision
                    retireLocked(installed.generation, installed.batchLease)
                }
            }
            retryDeferredCleanup()
            val cleanupIncomplete = synchronized(stateLock) {
                retiredInstallations.values.any { it.generation === generation } ||
                    pendingPreparationCleanup.isNotEmpty()
            }
            check(!cleanupIncomplete) {
                "Ultra display generation retirement is incomplete; retain its lease and retry close"
            }
        }
    }

    private fun validateReplacement(
        snapshot: UltraDisplayBindingSnapshot,
        destinationByOutput: Map<UltraDisplayOutputId, DisplayDestination>,
        targetByOutput: Map<UltraDisplayOutputId, DisplaySurfaceTarget>,
    ): List<UltraDisplayBatchRoute> {
        require(snapshot.revision >= 0L) { "Binding revision must not be negative" }
        val bindings = boundedCollection(snapshot.bindings, "display bindings")
        val uiSyncIds = boundedCollection(snapshot.resources.uiSyncDisplayIds, "UI Sync resources")
        val topologyIds = boundedCollection(snapshot.resources.topologyDisplayIds, "topology resources")
        val streamIds = boundedCollection(snapshot.resources.airPlayStreamIdentities, "AirPlay resources")
        val outputIds = boundedCollection(snapshot.resources.outputIds, "output resources")

        requireExactResources(bindings.map(UltraDisplayBinding::uiSyncDisplayId), uiSyncIds, "UI Sync")
        requireExactResources(
            bindings.map(UltraDisplayBinding::topologyDisplayId),
            topologyIds,
            "topology",
        )
        requireExactResources(
            bindings.map(UltraDisplayBinding::airPlayStreamIdentity),
            streamIds,
            "AirPlay",
        )
        requireExactResources(bindings.map(UltraDisplayBinding::outputId), outputIds, "output")

        val destinations = boundedMap(destinationByOutput, "output-to-destination map")
        val targets = boundedMap(targetByOutput, "output target map")
        val expectedOutputs = outputIds.toSet()
        require(destinations.keys == expectedOutputs) {
            "Output-to-destination map must explicitly cover every output ID exactly once"
        }
        require(targets.keys == expectedOutputs) {
            "Output target map must explicitly cover every output ID exactly once"
        }
        require(destinations.values.toSet().size == destinations.size) {
            "Each display destination may be mapped from at most one output ID"
        }

        val targetIdentities = IdentityHashMap<DisplaySurfaceTarget, Unit>()
        val ownershipIdentities = IdentityHashMap<DisplaySurfaceOwnership, Unit>()
        val routes = bindings.map { binding ->
            val destination = checkNotNull(destinations[binding.outputId])
            val target = checkNotNull(targets[binding.outputId])
            require(targetIdentities.put(target, Unit) == null) {
                "Each output ID must have a distinct display target"
            }
            val probe = target.probe()
            require(probe.isValid) { "Cannot prepare an invalid display target" }
            require(ownershipIdentities.put(probe.ownership, Unit) == null) {
                "Each output ID must have a distinct display surface ownership guard"
            }
            UltraDisplayBatchRoute(binding, destination, target, probe.descriptor)
        }.sortedWith(
            compareBy<UltraDisplayBatchRoute>(
                { it.destination.role.ordinal },
                { it.destination.instanceId.value },
            ),
        )
        return Collections.unmodifiableList(ArrayList(routes))
    }

    private fun validateOutputReplacement(
        graph: UltraDisplayGraphSnapshot,
        destinationByOutput: Map<UltraDisplayOutputId, DisplayDestination>,
        targetByOutput: Map<UltraDisplayOutputId, DisplaySurfaceTarget>,
    ): List<UltraDisplayBatchOutputRoute> {
        require(graph.revision >= 0L) { "Graph revision must not be negative" }
        val outputs = boundedCollection(graph.outputs, "V2 graph outputs")
        require(outputs.map { it.outputId }.toSet().size == outputs.size) {
            "V2 graph output IDs must be unique"
        }
        require(outputs.map { it.topologyDisplayId }.toSet().size == outputs.size) {
            "V2 graph topology display IDs must be unique"
        }
        val uiSyncIds = outputs.mapNotNull { it.uiSyncDisplayId }
        require(uiSyncIds.toSet().size == uiSyncIds.size) {
            "V2 graph UI Sync display IDs must be unique"
        }
        require(outputs.all { output ->
            val uiSyncId = output.uiSyncDisplayId
            graph.output(output.outputId) === output &&
                graph.output(output.topologyDisplayId) === output &&
                (uiSyncId == null || graph.output(uiSyncId) === output)
        }) { "V2 graph output index does not retain exact typed output identities" }

        val destinations = boundedMap(destinationByOutput, "V2 output-to-destination map")
        val targets = boundedMap(targetByOutput, "V2 output target map")
        val expectedIds = outputs.map { it.outputId }.toSet()
        require(destinations.keys == expectedIds) {
            "V2 destination map must explicitly cover every graph output exactly once"
        }
        require(targets.keys == expectedIds) {
            "V2 target map must explicitly cover every graph output exactly once"
        }
        require(destinations.values.toSet().size == destinations.size) {
            "Each V2 display destination may be mapped from at most one output"
        }

        val targetIdentities = IdentityHashMap<DisplaySurfaceTarget, Unit>()
        val ownershipIdentities = IdentityHashMap<DisplaySurfaceOwnership, Unit>()
        val routes = outputs.map { output ->
            val destination = checkNotNull(destinations[output.outputId])
            val target = checkNotNull(targets[output.outputId])
            require(targetIdentities.put(target, Unit) == null) {
                "Each V2 output must have a distinct display target"
            }
            val probe = target.probe()
            require(probe.isValid) { "Cannot prepare an invalid V2 display target" }
            require(ownershipIdentities.put(probe.ownership, Unit) == null) {
                "Each V2 output must have a distinct display surface ownership guard"
            }
            UltraDisplayBatchOutputRoute(output, destination, target, probe.descriptor)
        }.sortedWith(
            compareBy<UltraDisplayBatchOutputRoute>(
                { it.destination.role.ordinal },
                { it.destination.instanceId.value },
            ),
        )
        return Collections.unmodifiableList(ArrayList(routes))
    }

    private fun closePreparation(prepared: PreparedUltraDisplayBatch): Throwable? {
        val failure = runCatching { prepared.close() }.exceptionOrNull()
        synchronized(stateLock) {
            if (failure == null) {
                pendingPreparationCleanup.remove(prepared)
            } else {
                pendingPreparationCleanup[prepared] = Unit
                cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
            }
        }
        return failure
    }

    private fun retireLocked(
        generation: UltraDisplayBridgeGeneration,
        lease: UltraDisplayBatchLease,
    ) {
        if (!retiredInstallations.containsKey(lease)) {
            retiredInstallations[lease] = RetiredInstallation(generation, lease)
        }
    }

    private fun retryDeferredCleanup() {
        retryPreparationCleanup()
        retryInstallationCleanup()
    }

    private fun retryPreparationCleanup() {
        val pending = synchronized(stateLock) { pendingPreparationCleanup.keys.toList() }
        pending.forEach { prepared ->
            val succeeded = runCatching { prepared.close() }.isSuccess
            synchronized(stateLock) {
                if (succeeded) {
                    pendingPreparationCleanup.remove(prepared)
                } else if (pendingPreparationCleanup.containsKey(prepared)) {
                    cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
                }
            }
        }
    }

    private fun retryInstallationCleanup() {
        val pending = synchronized(stateLock) { retiredInstallations.values.toList() }
        pending.forEach { retired ->
            val failure = runCatching {
                var fence = synchronized(stateLock) { retired.fence }
                if (fence == null) {
                    fence = retired.lease.beginRetirement()
                    synchronized(stateLock) {
                        if (retiredInstallations[retired.lease] === retired) retired.fence = fence
                    }
                }
                if (!synchronized(stateLock) { retired.retirementComplete }) {
                    checkNotNull(fence).awaitRetirement()
                    synchronized(stateLock) { retired.retirementComplete = true }
                }
                if (!synchronized(stateLock) { retired.fenceClosed }) {
                    checkNotNull(fence).close()
                    synchronized(stateLock) { retired.fenceClosed = true }
                }
                retired.lease.close()
            }.exceptionOrNull()
            synchronized(stateLock) {
                if (failure == null) {
                    if (retiredInstallations[retired.lease] === retired) {
                        retiredInstallations.remove(retired.lease)
                    }
                } else if (retiredInstallations[retired.lease] === retired) {
                    cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
                }
            }
        }
    }

    private fun <T> withOperation(block: () -> T): T {
        val token = Any()
        synchronized(stateLock) {
            check(operationToken == null) { "Concurrent or reentrant Ultra display bridge mutation" }
            operationToken = token
        }
        try {
            return block()
        } finally {
            synchronized(stateLock) {
                check(operationToken === token) { "Ultra display bridge operation token was lost" }
                operationToken = null
            }
        }
    }
}

/** Generation-bound authority to retire exactly the installation returned by [replace]. */
class UltraDisplayBindingBridgeLease internal constructor(
    private var owner: UltraDisplayBindingBatchBridge?,
    val generation: UltraDisplayBridgeGeneration,
) : Closeable {
    val isCurrent: Boolean
        get() = synchronized(this) { owner }?.isCurrent(generation) == true

    override fun close() {
        val bridge = synchronized(this) { owner } ?: return
        bridge.detach(generation)
        synchronized(this) { if (owner === bridge) owner = null }
    }
}

/**
 * Android implementation of the bridge batch primitive. Its private router is not exposed while
 * a commit is in flight. The EGL owner thread first stages all native windows; only then does the
 * router publish one complete COMPOSITED generation. Pixel presentation still uses sequential
 * eglSwapBuffers and is not physically atomic across displays.
 *
 * The backend is caller-owned. Keep both this binder and backend alive until the bridge's close
 * succeeds, then retire the backend separately to release its remaining ingress resources.
 */
class AndroidUltraEglBatchBinder(
    private val backend: AndroidUltraEglBackend,
    private val graph: UltraDisplayGraphSnapshot,
) : UltraDisplayBatchBinder, Closeable {
    private val router = DisplaySurfaceRouter()
    private val batchOwner = backend.claimExclusiveBatchOwner()
    private val lock = Any()
    private val liveLeases = IdentityHashMap<EglBatchLease, Unit>()
    private val targetCloseOwners = IdentityHashMap<AndroidDisplaySurfaceTarget, EglBatchLease>()
    private var active: EglBatchLease? = null
    private var operationInFlight = false
    private var uncertain = false
    private var closed = false
    private var ownerReleased = false
    private var bridgeOwner: Any? = null

    /** One bridge owns this binder until its exact installation and cleanup fully retire. */
    internal fun claimBridgeOwner(owner: Any) = synchronized(lock) {
        check(!closed && !uncertain && bridgeOwner == null) {
            "Android EGL batch binder already belongs to a bridge or is unavailable"
        }
        bridgeOwner = owner
    }

    internal fun releaseBridgeOwner(owner: Any) = synchronized(lock) {
        check(bridgeOwner === owner) { "Android EGL batch bridge ownership mismatch" }
        check(liveLeases.isEmpty()) { "Android EGL batch bridge still has live leases" }
        bridgeOwner = null
    }

    override fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch {
        synchronized(lock) {
            check(!closed && !uncertain) { "Android EGL batch binder is unavailable" }
        }
        val outputGraph = plan.graphSnapshot
        if (outputGraph != null) {
            require(outputGraph === graph && plan.graphRevision == graph.revision) {
                "V2 batch plan must use the exact graph snapshot owned by this EGL binder"
            }
            require(plan.outputRoutes.map { it.output.outputId }.toSet() ==
                graph.outputs.map { it.outputId }.toSet() &&
                plan.outputRoutes.size == graph.outputs.size
            ) { "V2 batch must contain every exact graph output once" }
        }
        val routeCount = plan.routes.size + plan.outputRoutes.size
        val requests = ArrayList<AndroidUltraEglOutputBatchRequest>(routeCount)
        val routerRoutes = ArrayList<CompositedDisplayRoute>(routeCount)
        try {
            fun addRoute(
                destination: DisplayDestination,
                output: UltraDisplayGraphOutput,
                outputTarget: DisplaySurfaceTarget,
                outputDescriptor: DisplaySurfaceDescriptor,
            ) {
                val target = outputTarget as? AndroidDisplaySurfaceTarget
                    ?: throw IllegalArgumentException(
                        "Android EGL batch requires AndroidDisplaySurfaceTarget",
                    )
                require(target.descriptor == outputDescriptor) {
                    "Android EGL target descriptor changed after bridge validation"
                }
                val uiSyncId = output.uiSyncDisplayId
                require(graph.output(output.outputId) === output &&
                    graph.output(output.topologyDisplayId) === output &&
                    (uiSyncId == null || graph.output(uiSyncId) === output)
                ) { "EGL graph does not retain the exact typed output object" }
                requests += AndroidUltraEglOutputBatchRequest(destination, output, target)
                routerRoutes += CompositedDisplayRoute(destination, target.borrowForRouting())
            }
            plan.routes.forEach { route ->
                val graphOutput = requireNotNull(graph.output(route.binding.outputId)) {
                    "EGL graph lacks the exact bound output ${route.binding.outputId}"
                }
                require(
                    graphOutput.topologyDisplayId == route.binding.topologyDisplayId &&
                        graphOutput.uiSyncDisplayId == route.binding.uiSyncDisplayId
                ) { "EGL graph output does not match the display binding's typed identities" }
                addRoute(
                    route.destination,
                    graphOutput,
                    route.outputTarget,
                    route.outputDescriptor,
                )
            }
            plan.outputRoutes.forEach { route ->
                addRoute(
                    route.destination,
                    route.output,
                    route.outputTarget,
                    route.outputDescriptor,
                )
            }
        } catch (problem: Throwable) {
            routerRoutes.forEach { it.target.close() }
            throw problem
        }
        return Prepared(plan.generation, requests, routerRoutes)
    }

    /** Only complete, committed route generations are observable through this binder. */
    fun routeSnapshot(): DisplaySurfaceRouterSnapshot {
        val current = synchronized(lock) {
            check(!operationInFlight && !uncertain && !closed) {
                "Android EGL route publication is in flight or unavailable"
            }
            active
        }
        val snapshot = router.snapshot()
        check(current?.isCurrentGeneration == true || snapshot.routes.isEmpty()) {
            "Android EGL route generation is retiring or unavailable"
        }
        return snapshot
    }

    override fun close() {
        synchronized(lock) {
            if (ownerReleased) return
            check(!operationInFlight) { "Android EGL batch commit is in flight" }
            check(bridgeOwner == null) { "Close the owning display bridge before its EGL binder" }
            check(liveLeases.isEmpty()) {
                "Android EGL batch leases must retire before binder close"
            }
            closed = true
        }
        router.close()
        backend.releaseExclusiveBatchOwner(batchOwner)
        synchronized(lock) { ownerReleased = true }
    }

    private inner class Prepared(
        override val generation: UltraDisplayBridgeGeneration,
        private val requests: List<AndroidUltraEglOutputBatchRequest>,
        private val routerRoutes: List<CompositedDisplayRoute>,
    ) : PreparedUltraDisplayBatch {
        private var committed = false
        private var closedPreparation = false
        private var uncertainCommit = false
        private var committedRouterLease: DisplaySurfaceBatchLease? = null

        override fun commit(): UltraDisplayBatchLease {
            synchronized(lock) {
                check(!closed && !uncertain && !operationInFlight) {
                    "Android EGL batch binder cannot commit now"
                }
                check(!closedPreparation && !committed) { "EGL batch preparation is no longer open" }
                operationInFlight = true
            }
            try {
                var routePublicationReturned = false
                val eglLease = try {
                    backend.replaceOutputsWithPublication(requests, batchOwner) {
                        committedRouterLease = router.replaceCompositedBatch(routerRoutes)
                        routePublicationReturned = true
                    }
                } catch (problem: Throwable) {
                    if (problem is UltraEglBatchCommitUncertainException || routePublicationReturned) {
                        uncertainCommit = true
                        synchronized(lock) { uncertain = true }
                        throw UltraDisplayBatchCommitUncertainException(problem)
                    }
                    throw problem
                }
                val publishedRouterLease = committedRouterLease ?: run {
                    uncertainCommit = true
                    synchronized(lock) { uncertain = true }
                    throw UltraDisplayBatchCommitUncertainException(
                        IllegalStateException("EGL batch route publisher returned no lease"),
                    )
                }
                val lease = EglBatchLease(
                    eglLease,
                    publishedRouterLease,
                    requests.map { it.target },
                )
                synchronized(lock) {
                    requests.forEach { targetCloseOwners[it.target] = lease }
                    active = lease
                    liveLeases[lease] = Unit
                }
                committed = true
                return lease
            } finally {
                synchronized(lock) { operationInFlight = false }
            }
        }

        override fun close() {
            if (closedPreparation) return
            if (uncertainCommit) {
                val retirement = backend.retire()
                check(retirement.isRetired) {
                    "Uncertain EGL batch still owns native windows: $retirement"
                }
                router.retireAllCompositedBatchesAfterUpstreamProof()
                committedRouterLease?.close()
            }
            if (!committed) routerRoutes.forEach { it.target.close() }
            closedPreparation = true
        }
    }

    private inner class EglBatchLease(
        private val eglLease: UltraEglOutputBatchLease,
        private val routerLease: DisplaySurfaceBatchLease,
        private val targets: List<AndroidDisplaySurfaceTarget>,
    ) : UltraDisplayBatchLease {
        private var fence: EglRetirementFence? = null
        private var nextTargetToClose = 0
        private var closedLease = false

        val isCurrentGeneration: Boolean
            get() = eglLease.isCurrent && routerLease.isCurrent

        override val isOperational: Boolean
            get() = isCurrentGeneration

        override fun beginRetirement(): UltraDisplayRetirementFence = synchronized(this) {
            if (fence == null) {
                synchronized(lock) { if (active === this) active = null }
                fence = EglRetirementFence(eglLease, routerLease)
            }
            checkNotNull(fence)
        }

        override fun close() {
            synchronized(this) {
                if (closedLease) return
                check(fence?.closedFence == true) {
                    "Android EGL batch target closure requires a completed retirement fence"
                }
                while (nextTargetToClose < targets.size) {
                    val target = targets[nextTargetToClose]
                    val ownsClose = synchronized(lock) { targetCloseOwners[target] === this }
                    if (ownsClose) {
                        target.close()
                        synchronized(lock) {
                            if (targetCloseOwners[target] === this) targetCloseOwners.remove(target)
                        }
                    }
                    nextTargetToClose++
                }
                closedLease = true
            }
            synchronized(lock) { liveLeases.remove(this) }
        }
    }

    private class EglRetirementFence(
        private val eglLease: UltraEglOutputBatchLease,
        private val routerLease: DisplaySurfaceBatchLease,
    ) : UltraDisplayRetirementFence {
        private var retired = false
        var closedFence = false
            private set

        @Synchronized override fun awaitRetirement() {
            if (retired) return
            eglLease.close()
            retired = true
        }

        @Synchronized override fun close() {
            if (closedFence) return
            check(retired) { "Cannot release router views before EGL retirement proof" }
            routerLease.close()
            closedFence = true
        }
    }
}

private const val MAXIMUM_BRIDGED_DISPLAYS = 64

private fun bridgeIncrement(value: Long, name: String): Long {
    check(value < Long.MAX_VALUE) { "$name exhausted" }
    return value + 1L
}

private fun <T> boundedCollection(values: Collection<T>, label: String): List<T> {
    val declaredSize = values.size
    require(declaredSize in 0..MAXIMUM_BRIDGED_DISPLAYS) {
        "$label exceeds $MAXIMUM_BRIDGED_DISPLAYS entries"
    }
    val snapshot = ArrayList<T>(declaredSize)
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < MAXIMUM_BRIDGED_DISPLAYS) {
            "$label exceeds $MAXIMUM_BRIDGED_DISPLAYS entries"
        }
        snapshot += iterator.next()
    }
    require(snapshot.size == declaredSize) { "$label changed while being snapshotted" }
    return snapshot
}

private fun <K, V> boundedMap(values: Map<K, V>, label: String): Map<K, V> {
    val declaredSize = values.size
    require(declaredSize in 0..MAXIMUM_BRIDGED_DISPLAYS) {
        "$label exceeds $MAXIMUM_BRIDGED_DISPLAYS entries"
    }
    val snapshot = LinkedHashMap<K, V>(declaredSize)
    val iterator = values.entries.iterator()
    var observed = 0
    while (iterator.hasNext()) {
        require(observed < MAXIMUM_BRIDGED_DISPLAYS) {
            "$label exceeds $MAXIMUM_BRIDGED_DISPLAYS entries"
        }
        val entry = iterator.next()
        require(!snapshot.containsKey(entry.key)) { "$label contains a duplicate key" }
        snapshot[entry.key] = entry.value
        observed += 1
    }
    require(observed == declaredSize) { "$label changed while being snapshotted" }
    return Collections.unmodifiableMap(snapshot)
}

private fun <T> requireExactResources(
    bindingValues: List<T>,
    resources: List<T>,
    label: String,
) {
    require(bindingValues.size == bindingValues.toSet().size) {
        "Each $label binding identity must be unique"
    }
    require(resources.size == resources.toSet().size) {
        "Each $label resource identity must be unique"
    }
    require(bindingValues.toSet() == resources.toSet()) {
        "$label bindings must cover every declared resource exactly once"
    }
}
