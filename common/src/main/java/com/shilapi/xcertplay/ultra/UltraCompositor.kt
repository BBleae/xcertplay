package com.shilapi.xcertplay.ultra

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

internal const val MAXIMUM_ULTRA_RENDER_LAYERS = 4_096

/** Rectangle in physical output pixels. No dp, density, or display scaling is applied. */
data class UltraPixelRect(
    val xPixels: Int,
    val yPixels: Int,
    val widthPixels: Int,
    val heightPixels: Int,
) {
    init {
        require(widthPixels >= 0) { "Pixel width must not be negative" }
        require(heightPixels >= 0) { "Pixel height must not be negative" }
    }
}

enum class UltraLayerType { REMOTE, LOCAL, OVERLAY, PUNCH_THROUGH }

@JvmInline
value class UltraLayerId(val value: String) {
    init {
        require(value.isNotBlank()) { "Layer id must not be blank" }
        require(value.length <= 128) { "Layer id is too long" }
        require(value.none(Char::isISOControl)) { "Layer id contains a control character" }
    }
}

@JvmInline
value class UltraContentId(val value: String) {
    init {
        require(value.isNotBlank()) { "Content id must not be blank" }
        require(value.length <= 256) { "Content id is too long" }
        require(value.none(Char::isISOControl)) { "Content id contains a control character" }
    }
}

sealed interface UltraLayerSource {
    val type: UltraLayerType

    data object Remote : UltraLayerSource { override val type = UltraLayerType.REMOTE }
    data class Local(val contentId: UltraContentId) : UltraLayerSource {
        override val type = UltraLayerType.LOCAL
    }
    data class Overlay(val contentId: UltraContentId) : UltraLayerSource {
        override val type = UltraLayerType.OVERLAY
    }
    data object PunchThrough : UltraLayerSource {
        override val type = UltraLayerType.PUNCH_THROUGH
    }
}

/** All geometry is in the output's physical pixel coordinate system. */
data class UltraRenderLayer(
    val id: UltraLayerId,
    val source: UltraLayerSource,
    val framePixels: UltraPixelRect,
    val clipPixels: UltraPixelRect? = null,
    val zIndex: Int = 0,
    val opacity: Double = 1.0,
) {
    val type: UltraLayerType get() = source.type

    init {
        require(opacity.isFinite() && opacity in 0.0..1.0) {
            "Layer opacity must be finite and between 0.0 and 1.0"
        }
    }
}

/** Defensive render input, normalized by z while preserving caller order for ties. */
class UltraRenderSnapshot(
    val revision: Long,
    layers: List<UltraRenderLayer>,
) {
    val layers: List<UltraRenderLayer>

    init {
        require(revision >= 0L) { "Render revision must not be negative" }
        val copiedLayers = boundedListSnapshot(
            layers,
            MAXIMUM_ULTRA_RENDER_LAYERS,
            "Render layers",
        )
        require(copiedLayers.map(UltraRenderLayer::id).toSet().size == copiedLayers.size) {
            "Render layer ids must be unique"
        }
        val ordered = copiedLayers.withIndex()
            .sortedWith(compareBy<IndexedValue<UltraRenderLayer>>({ it.value.zIndex }, { it.index }))
            .map { it.value }
        this.layers = Collections.unmodifiableList(ArrayList(ordered))
    }

    override fun equals(other: Any?): Boolean =
        other is UltraRenderSnapshot && revision == other.revision && layers == other.layers

    override fun hashCode(): Int = 31 * revision.hashCode() + layers.hashCode()
    override fun toString(): String = "UltraRenderSnapshot(revision=$revision, layers=$layers)"
}

class UltraRemoteIngressToken internal constructor(val generation: Long)

class UltraCompositorOutputToken internal constructor(
    val destination: DisplayDestination,
    val generation: Long,
)

data class UltraRemoteIngressBinding(
    val token: UltraRemoteIngressToken,
    val target: DisplaySurfaceTarget,
)

data class UltraCompositorOutputBinding(
    val token: UltraCompositorOutputToken,
    val target: DisplaySurfaceTarget,
)

enum class UltraCompositorTransactionKind { TOPOLOGY, RENDER }

/**
 * A full binding topology plus an optional new render snapshot. A topology transaction with null
 * [render] retains the backend's prior scene while atomically replacing ingress/output bindings.
 */
class UltraCompositorTransaction internal constructor(
    val order: Long,
    val kind: UltraCompositorTransactionKind,
    val render: UltraRenderSnapshot?,
    val remoteIngress: UltraRemoteIngressBinding?,
    outputs: List<UltraCompositorOutputBinding>,
) {
    val outputs: List<UltraCompositorOutputBinding> =
        Collections.unmodifiableList(
            boundedListSnapshot(
                outputs,
                MAXIMUM_ACTIVE_DISPLAY_SURFACES,
                "Compositor outputs",
            ),
        )
}

/**
 * Retirement acknowledgement returned by a backend commit. [awaitRetirement] returns only after
 * every target omitted/replaced by that transaction is no longer used by the backend or its GPU.
 */
interface UltraCompositorCommit : Closeable {
    fun awaitRetirement()
}

object ImmediateUltraCompositorCommit : UltraCompositorCommit {
    override fun awaitRetirement() = Unit
    override fun close() = Unit
}

/**
 * Platform renderer seam; no GLES/Metal implementation is claimed here. A failed commit must leave
 * the previous topology active. A successful commit atomically installs the supplied full topology.
 * [close] must stop using every target before it returns successfully.
 */
interface UltraCompositorBackend : Closeable {
    fun openRemoteIngress(token: UltraRemoteIngressToken): DisplaySurfaceTarget
    fun commit(transaction: UltraCompositorTransaction): UltraCompositorCommit
}

interface UltraCompositor : Closeable {
    fun acquireRemoteIngress(): UltraRemoteIngressLease
    fun attachOutput(
        destination: DisplayDestination,
        target: DisplaySurfaceTarget,
    ): UltraCompositorOutputLease
    fun submit(snapshot: UltraRenderSnapshot)
}

data class UltraCompositorDiagnostics(
    val closed: Boolean,
    val operationInFlight: Boolean,
    val cleanupFailureCount: Int,
    val retiredTargetCount: Int,
    val backendPinnedTargetCount: Int,
    val backendClosed: Boolean,
)

/**
 * Serial transaction coordinator. No target getter, backend call, fence wait, or target close occurs
 * under [lock]. Binding changes themselves are topology commits, so lease close cannot release a
 * Surface before the backend/GPU acknowledges retirement. Reentrant operations fail immediately.
 */
class TransactionalUltraCompositor(
    private val backend: UltraCompositorBackend,
) : UltraCompositor {
    private data class Ingress(
        val token: UltraRemoteIngressToken,
        val target: DisplaySurfaceTarget,
        val ownership: DisplaySurfaceOwnership,
    )

    private data class Output(
        val token: UltraCompositorOutputToken,
        val target: DisplaySurfaceTarget,
        val ownership: DisplaySurfaceOwnership,
    )

    private data class Operation(val identity: Any, val order: Long)

    private val lock = Any()
    private val ownerIdentity = Any()
    private val outputs = LinkedHashMap<DisplayDestination, Output>()
    private val outputOwners = IdentityHashMap<DisplaySurfaceTarget, DisplayDestination>()
    private val retiredTargets = IdentityHashMap<DisplaySurfaceTarget, DisplaySurfaceOwnership>()
    private val unownedRetiredTargets = IdentityHashMap<DisplaySurfaceTarget, Unit>()
    private val cleanupInFlight = IdentityHashMap<DisplaySurfaceTarget, Unit>()
    private val backendPinnedTargets = IdentityHashMap<DisplaySurfaceTarget, Unit>()
    private var ingress: Ingress? = null
    private var inFlightOperation: Operation? = null
    private var generation = 0L
    private var transactionOrder = 0L
    private var lastRenderRevision = -1L
    private var cleanupFailureCount = 0
    private var backendCloseInFlight = false
    private var backendClosed = false
    private var closed = false

    override fun acquireRemoteIngress(): UltraRemoteIngressLease {
        val token: UltraRemoteIngressToken
        val openOperation: Operation
        synchronized(lock) {
            checkCanStartOperationLocked()
            val nextGeneration = compositorIncrement(generation, "compositor generation")
            token = UltraRemoteIngressToken(nextGeneration)
            generation = nextGeneration
            openOperation = reserveOperationLocked()
        }

        var opened: DisplaySurfaceTarget? = null
        var probe: ProbedDisplaySurfaceTarget? = null
        var attemptedTransaction: UltraCompositorTransaction? = null
        var claimed = false
        try {
            val openedTarget = backend.openRemoteIngress(token)
            opened = openedTarget
            val targetProbe = openedTarget.probe()
            probe = targetProbe
            require(targetProbe.isValid) { "Backend returned an invalid remote ingress surface" }
            val transaction: UltraCompositorTransaction
            synchronized(lock) {
                checkOperationLocked(openOperation)
                check(!closed) { "Ultra compositor closed while opening remote ingress" }
                require(outputOwners[openedTarget] == null) {
                    "Backend returned a target already attached as an output"
                }
                require(!retiredTargets.containsKey(openedTarget)) {
                    "Backend returned a target pending cleanup"
                }
                claimed = targetProbe.ownership.claim(ownerIdentity)
                transaction = transactionLocked(
                    openOperation.order,
                    UltraCompositorTransactionKind.TOPOLOGY,
                    null,
                    Ingress(token, openedTarget, targetProbe.ownership),
                    outputs,
                )
                attemptedTransaction = transaction
            }
            executeBackendCommit(transaction)

            var closedDuringCommit = false
            synchronized(lock) {
                checkOperationLocked(openOperation)
                val previous = ingress
                if (closed) {
                    closedDuringCommit = true
                    if (previous?.target !== openedTarget) {
                        retiredTargets[openedTarget] = targetProbe.ownership
                    }
                } else {
                    ingress = Ingress(token, openedTarget, targetProbe.ownership)
                    if (previous?.target !== openedTarget) {
                        previous?.let { retiredTargets[it.target] = it.ownership }
                    }
                }
                transactionOrder = openOperation.order
                replaceBackendPinsLocked(transaction)
                inFlightOperation = null
            }
            finishDeferredCleanup()
            check(!closedDuringCommit) { "Ultra compositor closed while opening remote ingress" }
            return UltraRemoteIngressLease(this, token, openedTarget)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (inFlightOperation?.identity === openOperation.identity) {
                    if (failure is InstalledTopologyRetirementFailure) {
                        enterTerminalStateAfterInstalledCommitLocked(
                            checkNotNull(attemptedTransaction),
                        )
                    }
                    inFlightOperation = null
                    val created = opened
                    if (claimed && created != null && ingress?.target !== created) {
                        retiredTargets[created] = checkNotNull(probe).ownership
                    }
                }
            }
            if (!claimed) opened?.let { retireBackendCreatedTarget(it, probe) }
            finishDeferredCleanup()
            throw failure
        }
    }

    override fun attachOutput(
        destination: DisplayDestination,
        target: DisplaySurfaceTarget,
    ): UltraCompositorOutputLease {
        // Reject a 65th destination before touching or claiming the caller-owned target. Repeat
        // after probing because a target getter can re-enter the compositor.
        synchronized(lock) {
            checkCanStartOperationLocked()
            requireOutputCapacityLocked(destination)
        }
        val probe = target.probe()
        require(probe.isValid) { "Cannot attach an invalid compositor output" }
        val token: UltraCompositorOutputToken
        val operation: Operation
        val transaction: UltraCompositorTransaction
        var newClaim = false
        synchronized(lock) {
            checkCanStartOperationLocked()
            requireOutputCapacityLocked(destination)
            require(ingress?.target !== target) {
                "The remote ingress target cannot also be an output"
            }
            val existingOwner = outputOwners[target]
            require(existingOwner == null || existingOwner == destination) {
                "An output target cannot be attached to multiple destinations"
            }
            require(!retiredTargets.containsKey(target)) {
                "An output target pending cleanup cannot be reattached"
            }
            val nextGeneration = compositorIncrement(generation, "compositor generation")
            token = UltraCompositorOutputToken(destination, nextGeneration)
            operation = reserveOperationLocked()
            try {
                newClaim = probe.ownership.claim(ownerIdentity)
                generation = nextGeneration
                val proposed = LinkedHashMap(outputs)
                proposed[destination] = Output(token, target, probe.ownership)
                transaction = transactionLocked(
                    operation.order,
                    UltraCompositorTransactionKind.TOPOLOGY,
                    null,
                    ingress,
                    proposed,
                )
            } catch (failure: Throwable) {
                inFlightOperation = null
                if (newClaim) probe.ownership.release(ownerIdentity)
                throw failure
            }
        }
        try {
            executeBackendCommit(transaction)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (inFlightOperation?.identity === operation.identity) {
                    if (failure is InstalledTopologyRetirementFailure) {
                        enterTerminalStateAfterInstalledCommitLocked(transaction)
                        retiredTargets[target] = probe.ownership
                    } else if (newClaim) {
                        probe.ownership.release(ownerIdentity)
                    }
                    inFlightOperation = null
                }
            }
            finishDeferredCleanup()
            throw failure
        }

        var closedDuringCommit = false
        synchronized(lock) {
            checkOperationLocked(operation)
            val previous = outputs[destination]
            if (closed) {
                closedDuringCommit = true
                if (previous?.target !== target) retiredTargets[target] = probe.ownership
            } else {
                outputs[destination] = Output(token, target, probe.ownership)
                if (previous?.target !== target) {
                    previous?.let {
                        outputOwners.remove(it.target)
                        retiredTargets[it.target] = it.ownership
                    }
                    outputOwners[target] = destination
                }
            }
            transactionOrder = operation.order
            replaceBackendPinsLocked(transaction)
            inFlightOperation = null
        }
        finishDeferredCleanup()
        check(!closedDuringCommit) { "Ultra compositor closed while attaching output" }
        return UltraCompositorOutputLease(this, token)
    }

    /** Caller holds [lock]. Replacing an existing destination does not consume capacity. */
    private fun requireOutputCapacityLocked(destination: DisplayDestination) {
        require(outputs.containsKey(destination) || outputs.size < MAXIMUM_ACTIVE_DISPLAY_SURFACES) {
            "Compositor outputs exceed $MAXIMUM_ACTIVE_DISPLAY_SURFACES entries"
        }
    }

    override fun submit(snapshot: UltraRenderSnapshot) {
        val operation: Operation
        val transaction: UltraCompositorTransaction
        synchronized(lock) {
            checkCanStartOperationLocked()
            require(snapshot.revision > lastRenderRevision) {
                "Render revision ${snapshot.revision} is not newer than $lastRenderRevision"
            }
            require(snapshot.layers.none { it.type == UltraLayerType.REMOTE } || ingress != null) {
                "A REMOTE layer requires an active remote ingress"
            }
            operation = reserveOperationLocked()
            transaction = transactionLocked(
                operation.order,
                UltraCompositorTransactionKind.RENDER,
                snapshot,
                ingress,
                outputs,
            )
        }
        try {
            executeBackendCommit(transaction)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (inFlightOperation?.identity === operation.identity) {
                    if (failure is InstalledTopologyRetirementFailure) {
                        enterTerminalStateAfterInstalledCommitLocked(transaction)
                    }
                    inFlightOperation = null
                }
            }
            finishDeferredCleanup()
            throw failure
        }
        synchronized(lock) {
            checkOperationLocked(operation)
            transactionOrder = operation.order
            lastRenderRevision = snapshot.revision
            replaceBackendPinsLocked(transaction)
            inFlightOperation = null
        }
        finishDeferredCleanup()
    }

    fun diagnostics(): UltraCompositorDiagnostics = synchronized(lock) {
        UltraCompositorDiagnostics(
            closed = closed,
            operationInFlight = inFlightOperation != null,
            cleanupFailureCount = cleanupFailureCount,
            retiredTargetCount = retiredTargets.size + unownedRetiredTargets.size,
            backendPinnedTargetCount = backendPinnedTargets.size,
            backendClosed = backendClosed,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                ingress?.let { retiredTargets[it.target] = it.ownership }
                outputs.values.forEach { retiredTargets[it.target] = it.ownership }
                ingress = null
                outputs.clear()
                outputOwners.clear()
            }
        }
        finishDeferredCleanup()
    }

    internal fun isCurrent(token: UltraRemoteIngressToken): Boolean = synchronized(lock) {
        ingress?.token === token
    }

    internal fun isCurrent(token: UltraCompositorOutputToken): Boolean = synchronized(lock) {
        outputs[token.destination]?.token === token
    }

    internal fun detach(token: UltraRemoteIngressToken) {
        val operation: Operation
        val current: Ingress
        val transaction: UltraCompositorTransaction
        synchronized(lock) {
            current = ingress?.takeIf { it.token === token } ?: return
            checkCanStartOperationLocked()
            operation = reserveOperationLocked()
            transaction = transactionLocked(
                operation.order,
                UltraCompositorTransactionKind.TOPOLOGY,
                null,
                null,
                outputs,
            )
        }
        executeTopologyDetach(operation, transaction) {
            if (ingress?.token === token) {
                ingress = null
                retiredTargets[current.target] = current.ownership
            }
        }
    }

    internal fun detach(token: UltraCompositorOutputToken) {
        val operation: Operation
        val current: Output
        val proposed: LinkedHashMap<DisplayDestination, Output>
        val transaction: UltraCompositorTransaction
        synchronized(lock) {
            current = outputs[token.destination]?.takeIf { it.token === token } ?: return
            checkCanStartOperationLocked()
            operation = reserveOperationLocked()
            proposed = LinkedHashMap(outputs)
            proposed.remove(token.destination)
            transaction = transactionLocked(
                operation.order,
                UltraCompositorTransactionKind.TOPOLOGY,
                null,
                ingress,
                proposed,
            )
        }
        executeTopologyDetach(operation, transaction) {
            if (outputs[token.destination]?.token === token) {
                outputs.remove(token.destination)
                outputOwners.remove(current.target)
                retiredTargets[current.target] = current.ownership
            }
        }
    }

    private inline fun executeTopologyDetach(
        operation: Operation,
        transaction: UltraCompositorTransaction,
        applyLocked: TransactionalUltraCompositor.() -> Unit,
    ) {
        try {
            executeBackendCommit(transaction)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (inFlightOperation?.identity === operation.identity) {
                    if (failure is InstalledTopologyRetirementFailure) {
                        enterTerminalStateAfterInstalledCommitLocked(transaction)
                    }
                    inFlightOperation = null
                }
            }
            finishDeferredCleanup()
            throw failure
        }
        synchronized(lock) {
            checkOperationLocked(operation)
            if (!closed) applyLocked()
            transactionOrder = operation.order
            replaceBackendPinsLocked(transaction)
            inFlightOperation = null
        }
        finishDeferredCleanup()
    }

    private fun reserveOperationLocked(): Operation {
        check(inFlightOperation == null) { "A compositor operation is already in progress" }
        val order = compositorIncrement(transactionOrder, "compositor transaction order")
        return Operation(Any(), order).also { inFlightOperation = it }
    }

    private fun checkCanStartOperationLocked() {
        check(!closed) { "Ultra compositor is closed" }
        check(inFlightOperation == null) { "A compositor operation is already in progress" }
    }

    private fun checkOperationLocked(operation: Operation) {
        check(inFlightOperation?.identity === operation.identity) {
            "Compositor operation reservation was lost"
        }
    }

    private fun transactionLocked(
        order: Long,
        kind: UltraCompositorTransactionKind,
        render: UltraRenderSnapshot?,
        proposedIngress: Ingress?,
        proposedOutputs: Map<DisplayDestination, Output>,
    ) = UltraCompositorTransaction(
        order = order,
        kind = kind,
        render = render,
        remoteIngress = proposedIngress?.let {
            UltraRemoteIngressBinding(it.token, it.target)
        },
        outputs = proposedOutputs.values
            .map { UltraCompositorOutputBinding(it.token, it.target) }
            .sortedWith(OUTPUT_ORDER),
    )

    private fun executeBackendCommit(transaction: UltraCompositorTransaction) {
        val acknowledgement = backend.commit(transaction)
        var failure: Throwable? = null
        try {
            acknowledgement.awaitRetirement()
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try {
                acknowledgement.close()
            } catch (caught: Throwable) {
                failure?.addSuppressed(caught) ?: run { failure = caught }
            }
        }
        failure?.let { throw InstalledTopologyRetirementFailure(it) }
    }

    /** Caller holds [lock]. Keeps both old and possibly-new bindings pinned until backend.close. */
    private fun enterTerminalStateAfterInstalledCommitLocked(
        transaction: UltraCompositorTransaction,
    ) {
        closed = true
        ingress?.let { retiredTargets[it.target] = it.ownership }
        outputs.values.forEach { retiredTargets[it.target] = it.ownership }
        ingress = null
        outputs.clear()
        outputOwners.clear()
        transaction.remoteIngress?.target?.let { backendPinnedTargets[it] = Unit }
        transaction.outputs.forEach { backendPinnedTargets[it.target] = Unit }
    }

    private fun replaceBackendPinsLocked(transaction: UltraCompositorTransaction) {
        backendPinnedTargets.clear()
        transaction.remoteIngress?.target?.let { backendPinnedTargets[it] = Unit }
        transaction.outputs.forEach { backendPinnedTargets[it.target] = Unit }
    }

    private fun retireBackendCreatedTarget(
        target: DisplaySurfaceTarget,
        probe: ProbedDisplaySurfaceTarget?,
    ) {
        synchronized(lock) {
            if (ingress?.target === target || outputOwners.containsKey(target)) return
            if (retiredTargets.containsKey(target) || unownedRetiredTargets.containsKey(target)) return
            if (probe != null && runCatching { probe.ownership.claim(ownerIdentity) }.isSuccess) {
                retiredTargets[target] = probe.ownership
            } else if (probe == null) {
                // openRemoteIngress transfers temporary ownership even if a hostile getter fails.
                unownedRetiredTargets[target] = Unit
            }
        }
    }

    private fun finishDeferredCleanup() {
        closeBackendIfReady()
        retryRetired()
    }

    private fun retryRetired() {
        val attempts = synchronized(lock) {
            if (inFlightOperation != null) return
            val owned = retiredTargets.entries
                .filter { (target) ->
                    !backendPinnedTargets.containsKey(target) &&
                        !cleanupInFlight.containsKey(target)
                }
                .map { (target, ownership) ->
                    cleanupInFlight[target] = Unit
                    RetiredCleanup(target, ownership)
                }
            val unowned = unownedRetiredTargets.keys
                .filter { target ->
                    !backendPinnedTargets.containsKey(target) &&
                        !cleanupInFlight.containsKey(target)
                }
                .map { target ->
                    cleanupInFlight[target] = Unit
                    RetiredCleanup(target, null)
                }
            owned + unowned
        }
        attempts.forEach { attempt ->
            val target = attempt.target
            val ownership = attempt.ownership
            val succeeded = runCatching { target.close() }.isSuccess
            synchronized(lock) {
                cleanupInFlight.remove(target)
                if (succeeded && ownership != null && retiredTargets[target] === ownership) {
                    retiredTargets.remove(target)
                    ownership.release(ownerIdentity)
                } else if (succeeded && ownership == null) {
                    unownedRetiredTargets.remove(target)
                } else if (!succeeded) {
                    cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
                }
            }
        }
    }

    private fun closeBackendIfReady() {
        val shouldClose = synchronized(lock) {
            if (
                closed && inFlightOperation == null && !backendClosed && !backendCloseInFlight
            ) {
                backendCloseInFlight = true
                true
            } else {
                false
            }
        }
        if (!shouldClose) return
        val succeeded = runCatching { backend.close() }.isSuccess
        synchronized(lock) {
            backendCloseInFlight = false
            if (succeeded) {
                backendClosed = true
                backendPinnedTargets.clear()
            } else {
                cleanupFailureCount = saturatingIncrement(cleanupFailureCount)
            }
        }
    }

    private companion object {
        val OUTPUT_ORDER = compareBy<UltraCompositorOutputBinding>(
            { it.token.destination.role.ordinal },
            { it.token.destination.instanceId.value },
        )
    }

    private data class RetiredCleanup(
        val target: DisplaySurfaceTarget,
        val ownership: DisplaySurfaceOwnership?,
    )
}

class UltraRemoteIngressLease internal constructor(
    private var owner: TransactionalUltraCompositor?,
    val token: UltraRemoteIngressToken,
    val target: DisplaySurfaceTarget,
) : Closeable {
    val isCurrent: Boolean get() = synchronized(this) { owner }?.isCurrent(token) == true

    override fun close() {
        val compositor = synchronized(this) { owner } ?: return
        compositor.detach(token)
        synchronized(this) { if (owner === compositor) owner = null }
    }
}

class UltraCompositorOutputLease internal constructor(
    private var owner: TransactionalUltraCompositor?,
    val token: UltraCompositorOutputToken,
) : Closeable {
    val isCurrent: Boolean get() = synchronized(this) { owner }?.isCurrent(token) == true

    override fun close() {
        val compositor = synchronized(this) { owner } ?: return
        compositor.detach(token)
        synchronized(this) { if (owner === compositor) owner = null }
    }
}

private fun compositorIncrement(value: Long, name: String): Long = routerIncrement(value, name)

/** Copies at most [limit] items, consuming one sentinel item to prove that input is oversized. */
private fun <T> boundedListSnapshot(values: List<T>, limit: Int, label: String): ArrayList<T> {
    val snapshot = ArrayList<T>(minOf(limit, 16))
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        if (snapshot.size == limit) {
            iterator.next()
            throw IllegalArgumentException("$label exceeds $limit entries")
        }
        snapshot += iterator.next()
    }
    return snapshot
}

private class InstalledTopologyRetirementFailure(cause: Throwable) :
    IllegalStateException("Backend installed topology but retirement acknowledgement failed", cause)
