package com.shilapi.xcertplay.ultra

import android.view.Surface
import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Keep renderer surface cardinality aligned with UltraDisplayBindingRegistry. */
internal const val MAXIMUM_ACTIVE_DISPLAY_SURFACES = 64

/** Stable product-level identity for one physical or logical vehicle display. */
@JvmInline
value class DisplayDestinationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Display destination id must not be blank" }
        require(value.length <= 128) { "Display destination id is too long" }
        require(value.none(Char::isISOControl)) { "Display destination id contains a control character" }
    }
}

enum class DisplayDestinationRole {
    MAIN,
    CLUSTER,
    HUD,
    AUXILIARY,
}

/** A typed routing destination. [instanceId] distinguishes displays with the same role. */
data class DisplayDestination(
    val role: DisplayDestinationRole,
    val instanceId: DisplayDestinationId = DisplayDestinationId("default"),
) {
    companion object {
        val MAIN = DisplayDestination(DisplayDestinationRole.MAIN)
        val CLUSTER = DisplayDestination(DisplayDestinationRole.CLUSTER)
        val HUD = DisplayDestination(DisplayDestinationRole.HUD)
    }
}

/** Selects whether a destination receives the legacy remote stream or a composed Ultra scene. */
enum class DisplayStreamPath {
    LEGACY_REMOTE,
    COMPOSITED,
}

data class PixelSize(
    val widthPixels: Int,
    val heightPixels: Int,
) {
    init {
        require(widthPixels > 0) { "Surface width must be positive" }
        require(heightPixels > 0) { "Surface height must be positive" }
    }
}

@JvmInline
value class DisplaySurfaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Surface id must not be blank" }
        require(value.length <= 128) { "Surface id is too long" }
        require(value.none(Char::isISOControl)) { "Surface id contains a control character" }
    }
}

data class DisplaySurfaceDescriptor(
    val id: DisplaySurfaceId,
    val sizePixels: PixelSize,
)

/**
 * Cross-component exclusive-ownership guard. A target creates exactly one guard and returns that
 * same instance for its entire lifetime.
 */
class DisplaySurfaceOwnership {
    private val lock = Any()
    private var owner: Any? = null

    internal fun claim(requester: Any): Boolean = synchronized(lock) {
        when {
            owner == null -> {
                owner = requester
                true
            }
            owner === requester -> false
            else -> throw IllegalStateException("Display surface already has an exclusive owner")
        }
    }

    internal fun release(requester: Any) {
        synchronized(lock) {
            check(owner === requester) { "Display surface ownership release does not match owner" }
            owner = null
        }
    }
}

/**
 * A destination surface whose ownership transfers after a successful attach. Implementations must
 * make [close] idempotent and return one stable [ownership] object.
 */
interface DisplaySurfaceTarget : Closeable {
    val descriptor: DisplaySurfaceDescriptor
    val ownership: DisplaySurfaceOwnership
    val isValid: Boolean
}

/** Whether [AndroidDisplaySurfaceTarget] releases the wrapped [Surface] when detached. */
enum class AndroidSurfaceOwnership {
    BORROWED,
    OWNED,
}

/** Thin Android adapter with no Activity, View, or lifecycle-owner reference. */
class AndroidDisplaySurfaceTarget(
    val surface: Surface,
    override val descriptor: DisplaySurfaceDescriptor,
    private val surfaceOwnership: AndroidSurfaceOwnership = AndroidSurfaceOwnership.BORROWED,
) : DisplaySurfaceTarget {
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()
    override val ownership = DisplaySurfaceOwnership()

    override val isValid: Boolean
        get() = !closed.get() && surface.isValid

    override fun close() = synchronized(closeLock) {
        if (!closed.get()) {
            // A failed native release is not proof of closure. Keep this target retryable while
            // its owner retains the exclusive ownership guard and any upstream retirement fence.
            if (surfaceOwnership == AndroidSurfaceOwnership.OWNED) surface.release()
            closed.set(true)
        }
    }
}

/**
 * Non-owning bridge intended for a COMPOSITED route when the compositor owns [source]. Closing this
 * view never closes [source]. It must not outlive the compositor output lease owning [source].
 */
open class BorrowedDisplaySurfaceTarget internal constructor(
    val source: DisplaySurfaceTarget,
    override val descriptor: DisplaySurfaceDescriptor,
) : DisplaySurfaceTarget {
    private val closed = AtomicBoolean(false)
    override val ownership = DisplaySurfaceOwnership()

    override val isValid: Boolean
        get() = !closed.get() && source.isValid

    override fun close() {
        closed.set(true)
    }
}

/** Creates an explicit non-owning router bridge; descriptor access happens before any router lock. */
fun DisplaySurfaceTarget.borrowForRouting(): BorrowedDisplaySurfaceTarget {
    val value = descriptor
    val frozen = DisplaySurfaceDescriptor(
        id = value.id,
        sizePixels = PixelSize(value.sizePixels.widthPixels, value.sizePixels.heightPixels),
    )
    return BorrowedDisplaySurfaceTarget(this, frozen)
}

/** Opaque, generation-bearing authority to detach exactly one installed route. */
class DisplaySurfaceLeaseToken internal constructor(
    val destination: DisplayDestination,
    val streamPath: DisplayStreamPath,
    val generation: Long,
) {
    override fun toString(): String =
        "DisplaySurfaceLeaseToken(destination=$destination, streamPath=$streamPath, generation=$generation)"
}

data class DisplaySurfaceRouteSnapshot(
    val destination: DisplayDestination,
    val streamPath: DisplayStreamPath,
    val generation: Long,
    val surface: DisplaySurfaceDescriptor,
    val surfaceIsValid: Boolean,
)

data class DisplaySurfaceRouterSnapshot(
    val revision: Long,
    val closed: Boolean,
    val cleanupFailureCount: Int,
    val retiredTargetCount: Int,
    val routes: List<DisplaySurfaceRouteSnapshot>,
)

/** One non-owning compositor output view in a complete COMPOSITED route-set replacement. */
data class CompositedDisplayRoute(
    val destination: DisplayDestination,
    val target: BorrowedDisplaySurfaceTarget,
)

/** Identity-bearing authority for exactly one atomically installed route generation. */
internal class DisplaySurfaceBatchToken(val generation: Long)

/**
 * Linearizable owner of one current target per [DisplayDestination]. Target callbacks and cleanup
 * are never invoked under the state lock. Token identity makes stale destroy callbacks harmless.
 */
class DisplaySurfaceRouter : Closeable {
    private data class Binding(
        val token: DisplaySurfaceLeaseToken,
        val target: DisplaySurfaceTarget,
        val descriptor: DisplaySurfaceDescriptor,
        val ownership: DisplaySurfaceOwnership,
    )

    private data class SnapshotState(
        val revision: Long,
        val closed: Boolean,
        val cleanupFailureCount: Int,
        val retiredTargetCount: Int,
        val bindings: List<Binding>,
    )

    private class BatchState(
        val token: DisplaySurfaceBatchToken,
        val bindings: List<Binding>,
    ) {
        val pending = IdentityHashMap<DisplaySurfaceTarget, DisplaySurfaceOwnership>().apply {
            bindings.forEach { binding -> put(binding.target, binding.ownership) }
        }
        var cleanupInFlight = false
    }

    private val lock = Any()
    private val ownerIdentity = Any()
    private var routes = LinkedHashMap<DisplayDestination, Binding>()
    private val targetOwners = IdentityHashMap<DisplaySurfaceTarget, DisplayDestination>()
    private val legacyOwnershipOwners = IdentityHashMap<DisplaySurfaceOwnership, DisplaySurfaceTarget>()
    private val retiredTargets = IdentityHashMap<DisplaySurfaceTarget, DisplaySurfaceOwnership>()
    private val cleanupInFlight = IdentityHashMap<DisplaySurfaceTarget, Unit>()
    private val batchStates = IdentityHashMap<DisplaySurfaceBatchToken, BatchState>()
    private val batchTargetOwners = IdentityHashMap<DisplaySurfaceTarget, DisplaySurfaceBatchToken>()
    private val batchOwnershipOwners = IdentityHashMap<DisplaySurfaceOwnership, DisplaySurfaceBatchToken>()
    private var activeBatch: BatchState? = null
    private var batchMode = false
    private var generation = 0L
    private var revision = 0L
    private var cleanupFailureCount = 0
    private var closed = false

    /** Installs [target] and transfers its ownership if and only if the attach succeeds. */
    fun attach(
        destination: DisplayDestination,
        streamPath: DisplayStreamPath,
        target: DisplaySurfaceTarget,
    ): DisplaySurfaceLease {
        // Reject a 65th destination without invoking target-controlled code or taking ownership.
        // The check is repeated after probing because a getter may re-enter this router.
        synchronized(lock) {
            check(!closed) { "Display surface router is closed" }
            check(!batchMode) { "Legacy route attach cannot be mixed with composited batches" }
            requireRouteCapacityLocked(destination)
        }
        // All target-controlled getters are deliberately outside the router state lock.
        val probed = target.probe()
        require(probed.isValid) { "Cannot attach an invalid display surface" }
        val previous: Binding?
        val token: DisplaySurfaceLeaseToken
        var newClaim = false
        synchronized(lock) {
            check(!closed) { "Display surface router is closed" }
            check(!batchMode) { "Legacy route attach cannot be mixed with composited batches" }
            requireRouteCapacityLocked(destination)
            val existingOwner = targetOwners[target]
            require(existingOwner == null || existingOwner == destination) {
                "A surface target cannot be attached to multiple destinations"
            }
            require(!retiredTargets.containsKey(target)) {
                "A surface target pending cleanup cannot be reattached"
            }
            val existingGuardOwner = legacyOwnershipOwners[probed.ownership]
            require(existingGuardOwner == null || existingGuardOwner === target) {
                "One display ownership guard cannot back multiple surface targets"
            }

            val nextGeneration = routerIncrement(generation, "surface generation")
            val nextRevision = routerIncrement(revision, "router revision")
            newClaim = probed.ownership.claim(ownerIdentity)
            try {
                token = DisplaySurfaceLeaseToken(destination, streamPath, nextGeneration)
                previous = routes.put(
                    destination,
                    Binding(token, target, probed.descriptor, probed.ownership),
                )
                if (previous?.target !== target) {
                    previous?.let {
                        targetOwners.remove(it.target)
                        retiredTargets[it.target] = it.ownership
                    }
                    targetOwners[target] = destination
                }
                legacyOwnershipOwners[probed.ownership] = target
                generation = nextGeneration
                revision = nextRevision
            } catch (failure: Throwable) {
                if (newClaim) probed.ownership.release(ownerIdentity)
                throw failure
            }
        }

        if (previous != null && previous.target !== target) retryRetired(previous.target)
        return DisplaySurfaceLease(this, token)
    }

    /**
     * Replaces the entire COMPOSITED route set at one linearization point. Every target must be a
     * distinct non-owning [BorrowedDisplaySurfaceTarget]. Replaced views remain exclusively owned
     * by their old batch lease until that lease is closed after the caller's GPU retirement fence.
     * A failed probe, validation, or claim leaves the previous generation unchanged and does not
     * close any caller-provided target.
     */
    fun replaceCompositedBatch(routes: List<CompositedDisplayRoute>): DisplaySurfaceBatchLease {
        require(routes.size <= MAXIMUM_ACTIVE_DISPLAY_SURFACES) {
            "Display surface routes exceed $MAXIMUM_ACTIVE_DISPLAY_SURFACES entries"
        }
        val requested = ArrayList(routes)
        val destinations = HashSet<DisplayDestination>()
        val targetIdentities = IdentityHashMap<DisplaySurfaceTarget, Unit>()
        val sourceIdentities = IdentityHashMap<DisplaySurfaceTarget, Unit>()
        val ownershipIdentities = IdentityHashMap<DisplaySurfaceOwnership, Unit>()
        val probed = requested.map { route ->
            require(destinations.add(route.destination)) { "Duplicate display destination in batch" }
            require(targetIdentities.put(route.target, Unit) == null) {
                "Each batch destination requires a distinct borrowed target"
            }
            require(sourceIdentities.put(route.target.source, Unit) == null) {
                "One compositor output cannot be routed to multiple destinations"
            }
            val target = route.target.probe()
            require(target.isValid) { "Cannot install an invalid display surface" }
            require(ownershipIdentities.put(target.ownership, Unit) == null) {
                "Each batch target requires a distinct ownership guard"
            }
            route.destination to target
        }

        val token: DisplaySurfaceBatchToken
        val batchLease: DisplaySurfaceBatchLease
        synchronized(lock) {
            check(!closed) { "Display surface router is closed" }
            check(batchMode || (this.routes.isEmpty() && retiredTargets.isEmpty())) {
                "Legacy routes must be fully retired before composited batch routing"
            }
            probed.forEach { (_, target) ->
                require(!batchTargetOwners.containsKey(target.target)) {
                    "Batch target is still owned by an earlier generation"
                }
                require(!batchOwnershipOwners.containsKey(target.ownership)) {
                    "Batch ownership guard is still owned by an earlier generation"
                }
            }

            val nextGeneration = routerIncrement(generation, "surface generation")
            val nextRevision = routerIncrement(revision, "router revision")
            token = DisplaySurfaceBatchToken(nextGeneration)
            val nextBindings = probed.map { (destination, target) ->
                Binding(
                    DisplaySurfaceLeaseToken(destination, DisplayStreamPath.COMPOSITED, nextGeneration),
                    target.target,
                    target.descriptor,
                    target.ownership,
                )
            }
            val nextRoutes = LinkedHashMap<DisplayDestination, Binding>()
            nextBindings.forEach { binding -> nextRoutes[binding.token.destination] = binding }
            val nextBatch = BatchState(token, nextBindings)
            // Allocate the caller's exact-generation lease before any claims or publication.
            // A failed allocation must never leave a published batch without its retirement key.
            batchLease = DisplaySurfaceBatchLease(this, token)

            val claimed = ArrayList<DisplaySurfaceOwnership>(nextBindings.size)
            try {
                nextBindings.forEach { binding ->
                    check(binding.ownership.claim(ownerIdentity)) {
                        "Batch ownership guard is already claimed by this router"
                    }
                    claimed += binding.ownership
                }
                nextBindings.forEach { binding ->
                    batchTargetOwners[binding.target] = token
                    batchOwnershipOwners[binding.ownership] = token
                }
                batchStates[token] = nextBatch
            } catch (failure: Throwable) {
                nextBindings.forEach { binding ->
                    if (batchTargetOwners[binding.target] === token) batchTargetOwners.remove(binding.target)
                    if (batchOwnershipOwners[binding.ownership] === token) {
                        batchOwnershipOwners.remove(binding.ownership)
                    }
                }
                batchStates.remove(token)
                claimed.asReversed().forEach { ownership -> ownership.release(ownerIdentity) }
                throw failure
            }

            // All potentially failing preparation and claims are complete before publication.
            this.routes = nextRoutes
            activeBatch = nextBatch
            batchMode = true
            generation = nextGeneration
            revision = nextRevision
        }
        return batchLease
    }

    /** Caller holds [lock]. Replacing an existing destination does not consume capacity. */
    private fun requireRouteCapacityLocked(destination: DisplayDestination) {
        require(routes.containsKey(destination) || routes.size < MAXIMUM_ACTIVE_DISPLAY_SURFACES) {
            "Display surface routes exceed $MAXIMUM_ACTIVE_DISPLAY_SURFACES entries"
        }
    }

    fun snapshot(): DisplaySurfaceRouterSnapshot {
        val state = synchronized(lock) {
            SnapshotState(
                revision = revision,
                closed = closed,
                cleanupFailureCount = cleanupFailureCount,
                retiredTargetCount = (
                    retiredTargets.size.toLong() + batchStates.values.sumOf { batch ->
                        if (batch === activeBatch) 0L else batch.pending.size.toLong()
                    }
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                bindings = routes.values.toList(),
            )
        }
        // isValid is target-controlled and may re-enter/mutate this router.
        val copiedRoutes = state.bindings
            .map { binding ->
                DisplaySurfaceRouteSnapshot(
                    destination = binding.token.destination,
                    streamPath = binding.token.streamPath,
                    generation = binding.token.generation,
                    surface = binding.descriptor,
                    surfaceIsValid = runCatching { binding.target.isValid }
                        .getOrDefault(false),
                )
            }
            .sortedWith(ROUTE_ORDER)
        return DisplaySurfaceRouterSnapshot(
            revision = state.revision,
            closed = state.closed,
            cleanupFailureCount = state.cleanupFailureCount,
            retiredTargetCount = state.retiredTargetCount,
            routes = Collections.unmodifiableList(ArrayList(copiedRoutes)),
        )
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                revision = routerIncrement(revision, "router revision")
                if (!batchMode) {
                    routes.values.forEach { binding ->
                        retiredTargets[binding.target] = binding.ownership
                    }
                }
                routes.clear()
                targetOwners.clear()
                // Batch views are retired by their exact leases, after the upstream GPU fence.
                activeBatch = null
            }
        }
        retryRetired()
    }

    /**
     * Fail-closed recovery for an upstream batch commit whose publication outcome is uncertain.
     * The caller must first prove that its compositor/GPU backend has fully retired; ordinary
     * [close] intentionally does not retire batch views before that proof. Every batch token is
     * retained for retry if a borrowed view or ownership release fails.
     */
    internal fun retireAllCompositedBatchesAfterUpstreamProof() {
        close()
        val tokens = synchronized(lock) { batchStates.keys.toList() }
        var failure: Throwable? = null
        tokens.forEach { token ->
            val problem = runCatching { closeBatch(token) }.exceptionOrNull()
            if (problem != null) {
                if (failure == null) failure = problem else failure?.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    internal fun isCurrentBatch(token: DisplaySurfaceBatchToken): Boolean = synchronized(lock) {
        activeBatch?.token === token && !closed
    }

    /** Retryable cleanup; never detaches or closes a newer generation. */
    internal fun closeBatch(token: DisplaySurfaceBatchToken) {
        val batch: BatchState
        val pending: List<Pair<DisplaySurfaceTarget, DisplaySurfaceOwnership>>
        synchronized(lock) {
            batch = batchStates[token] ?: return
            check(!batch.cleanupInFlight) { "Batch cleanup is already in progress" }
            if (activeBatch === batch) {
                revision = routerIncrement(revision, "router revision")
                routes = LinkedHashMap()
                activeBatch = null
            }
            batch.cleanupInFlight = true
            pending = batch.pending.entries.map { (target, ownership) -> target to ownership }
        }

        var firstFailure: Throwable? = null
        try {
            pending.forEach { (target, ownership) ->
                val closeFailure = runCatching { target.close() }.exceptionOrNull()
                synchronized(lock) {
                    if (closeFailure == null && batch.pending[target] === ownership) {
                        try {
                            ownership.release(ownerIdentity)
                            batch.pending.remove(target)
                            batchTargetOwners.remove(target)
                            batchOwnershipOwners.remove(ownership)
                        } catch (releaseFailure: Throwable) {
                            cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
                            if (firstFailure == null) firstFailure = releaseFailure
                            else firstFailure?.addSuppressed(releaseFailure)
                        }
                    } else if (closeFailure != null) {
                        cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
                        if (firstFailure == null) firstFailure = closeFailure
                        else firstFailure?.addSuppressed(closeFailure)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                batch.cleanupInFlight = false
                if (batch.pending.isEmpty()) batchStates.remove(token)
            }
        }
        firstFailure?.let { throw it }
    }

    internal fun isCurrent(token: DisplaySurfaceLeaseToken): Boolean = synchronized(lock) {
        routes[token.destination]?.token === token
    }

    internal fun detach(token: DisplaySurfaceLeaseToken) {
        val target: DisplaySurfaceTarget?
        synchronized(lock) {
            val current = routes[token.destination]
            if (current?.token !== token) return
            routes.remove(token.destination)
            targetOwners.remove(current.target)
            retiredTargets[current.target] = current.ownership
            revision = routerIncrement(revision, "router revision")
            target = current.target
        }
        target?.let(::retryRetired)
    }

    private fun retryRetired(only: DisplaySurfaceTarget? = null) {
        val attempts = synchronized(lock) {
            retiredTargets.entries
                .filter { (target) ->
                    (only == null || target === only) && !cleanupInFlight.containsKey(target)
                }
                .map { (target, ownership) ->
                    cleanupInFlight[target] = Unit
                    target to ownership
                }
        }
        attempts.forEach { (target, ownership) ->
            val succeeded = runCatching { target.close() }.isSuccess
            synchronized(lock) {
                cleanupInFlight.remove(target)
                if (succeeded && retiredTargets[target] === ownership) {
                    ownership.release(ownerIdentity)
                    retiredTargets.remove(target)
                    if (legacyOwnershipOwners[ownership] === target) {
                        legacyOwnershipOwners.remove(ownership)
                    }
                } else if (!succeeded) {
                    cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
                }
            }
        }
    }

    private companion object {
        val ROUTE_ORDER = compareBy<DisplaySurfaceRouteSnapshot>(
            { it.destination.role.ordinal },
            { it.destination.instanceId.value },
        )
    }
}

class DisplaySurfaceLease internal constructor(
    private var owner: DisplaySurfaceRouter?,
    val token: DisplaySurfaceLeaseToken,
) : Closeable {
    val isCurrent: Boolean
        get() = synchronized(this) { owner }?.isCurrent(token) == true

    override fun close() {
        val router = synchronized(this) { owner.also { owner = null } }
        router?.detach(token)
    }
}

/**
 * Ownership of one complete route generation. The caller must wait for its upstream compositor/GPU
 * retirement fence before closing this lease. Failed target cleanup leaves the lease retryable.
 */
class DisplaySurfaceBatchLease internal constructor(
    private var owner: DisplaySurfaceRouter?,
    private val token: DisplaySurfaceBatchToken,
) : Closeable {
    val generation: Long get() = token.generation

    val isCurrent: Boolean
        get() = synchronized(this) { owner }?.isCurrentBatch(token) == true

    override fun close() {
        val router = synchronized(this) { owner } ?: return
        router.closeBatch(token)
        synchronized(this) {
            if (owner === router) owner = null
        }
    }
}

internal data class ProbedDisplaySurfaceTarget(
    val target: DisplaySurfaceTarget,
    val descriptor: DisplaySurfaceDescriptor,
    val ownership: DisplaySurfaceOwnership,
    val isValid: Boolean,
)

/** Reads and freezes every target-controlled property without a router/compositor state lock. */
internal fun DisplaySurfaceTarget.probe(): ProbedDisplaySurfaceTarget {
    val value = descriptor
    val frozen = DisplaySurfaceDescriptor(
        id = value.id,
        sizePixels = PixelSize(value.sizePixels.widthPixels, value.sizePixels.heightPixels),
    )
    val stableOwnership = ownership
    val valid = isValid
    return ProbedDisplaySurfaceTarget(this, frozen, stableOwnership, valid)
}

internal fun routerIncrement(value: Long, name: String): Long {
    check(value < Long.MAX_VALUE) { "$name exhausted" }
    return value + 1L
}

internal fun saturatingIncrement(value: Int): Int =
    if (value == Int.MAX_VALUE) Int.MAX_VALUE else value + 1
