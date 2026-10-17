package com.shilapi.xcertplay

import java.util.ArrayDeque

/**
 * Generation-neutral operations retained by the process until their resources retire cleanly.
 * Identity fields deliberately keep the exact controller and sink alive without retaining an
 * Activity or an Activity callback.
 */
internal class HostTeardownActions(
    internal val controllerIdentity: Any?,
    internal val sinkIdentity: Any?,
    internal val requestControllerClose: (() -> Unit)?,
    internal val awaitControllerClosed: (() -> Boolean)?,
    internal val closeMediaSink: (() -> Unit)?,
) {
    init {
        require((requestControllerClose == null) == (awaitControllerClosed == null)) {
            "Controller close and await operations must be supplied together"
        }
        require((controllerIdentity == null) == (requestControllerClose == null)) {
            "Controller identity must match its teardown operations"
        }
        require((sinkIdentity == null) == (closeMediaSink == null)) {
            "Sink identity must match its teardown operation"
        }
    }

    internal val isEmpty: Boolean
        get() = controllerIdentity == null && sinkIdentity == null

    internal fun ownsSameResources(other: HostTeardownActions): Boolean =
        controllerIdentity === other.controllerIdentity && sinkIdentity === other.sinkIdentity
}

internal enum class HostTeardownBarrierState {
    CLEAR,
    AVAILABLE,
    IN_FLIGHT,
}

internal sealed interface HostTeardownClaimResult {
    data object Clear : HostTeardownClaimResult
    data object InFlight : HostTeardownClaimResult
    data class Claimed(val claim: HostTeardownClaim) : HostTeardownClaimResult
}

internal enum class HostTeardownWorkKind {
    RESOURCE_TEARDOWN,
    DEFERRED_RELEASE,
}

/** Opaque, identity-checked claim for exactly one process-owned teardown record. */
internal class HostTeardownClaim internal constructor(
    internal val recordId: Long,
    internal val kind: HostTeardownWorkKind,
) {
    override fun toString(): String = "HostTeardownClaim(<opaque>)"
}

/**
 * Exact old-session retirement receipt. A clear process barrier cannot manufacture this receipt:
 * only the matching ticket's completed controller, sink, and borrow proof can do so.
 */
internal class HostTeardownRetirementReceipt internal constructor(
    private val ticket: HostTeardownTicket,
) {
    fun matchesExact(
        controllerIdentity: Any?,
        sinkIdentity: Any?,
        receipt: HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    ): Boolean = ticket.retirementReceiptFor(
        controllerIdentity,
        sinkIdentity,
        receipt,
    ) === this

    override fun toString(): String = "HostTeardownRetirementReceipt(<opaque>)"
}

/** Kept private to this file so no Activity can construct or complete a ticket state. */
private class HostTeardownTicketState(
    val controllerIdentity: Any?,
    val sinkIdentity: Any?,
    val oldMainBorrowRetirement:
        HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
) {
    @Volatile var completedReceipt: HostTeardownRetirementReceipt? = null
        private set

    fun complete(ticket: HostTeardownTicket) {
        check(completedReceipt == null) { "Host teardown ticket was already retired" }
        completedReceipt = HostTeardownRetirementReceipt(ticket)
    }
}

/** A one-way token for the exact resources and registry-issued old MAIN borrow receipt. */
internal class HostTeardownTicket internal constructor(opaqueState: Any) {
    private val state = opaqueState as? HostTeardownTicketState
        ?: throw IllegalArgumentException("Host teardown ticket requires coordinator state")

    fun retirementReceiptFor(
        controllerIdentity: Any?,
        sinkIdentity: Any?,
        receipt: HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    ): HostTeardownRetirementReceipt? =
        if (state.controllerIdentity === controllerIdentity &&
            state.sinkIdentity === sinkIdentity &&
            state.oldMainBorrowRetirement === receipt
        ) state.completedReceipt else null

    fun isRetiredFor(
        controllerIdentity: Any?,
        sinkIdentity: Any?,
        receipt: HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    ): Boolean = retirementReceiptFor(
        controllerIdentity,
        sinkIdentity,
        receipt,
    ) != null

    override fun toString(): String = "HostTeardownTicket(<opaque>)"
}

/**
 * Process-lifetime fail-closed barrier shared by every Activity instance.
 *
 * Only the queue bookkeeping is synchronized. Controller waits and sink retirement always run
 * outside [lock]. A failed classified attempt keeps the record and its resources strongly held;
 * its claim is released so another Activity can retry it. A successful attempt is the only path
 * that removes a record.
 */
internal class HostTeardownCoordinator {
    private data class Record(
        val id: Long,
        val actions: HostTeardownActions,
        val ticket: HostTeardownTicket? = null,
        val ticketState: HostTeardownTicketState? = null,
        val oldMainBorrowRetirement:
            HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt? = null,
        var resourcesClosed: Boolean = false,
        var inFlight: HostTeardownClaim? = null,
    )

    private data class DeferredRelease(
        val id: Long,
        val identity: Any,
        val release: () -> Unit,
        var inFlight: HostTeardownClaim? = null,
    )

    private val lock = Any()
    private val records = ArrayDeque<Record>()
    private val deferredReleases = ArrayDeque<DeferredRelease>()
    private var nextWorkId = 1L

    fun submit(actions: HostTeardownActions): Boolean {
        if (actions.isEmpty) return false
        synchronized(lock) {
            if (records.any { it.actions.ownsSameResources(actions) }) return false
            check(records.size + deferredReleases.size < MAX_PENDING_WORK_ITEMS) {
                "Too many pending host teardowns"
            }
            records.addLast(Record(nextWorkId, actions))
            nextWorkId = Math.addExact(nextWorkId, 1L)
            return true
        }
    }

    /**
     * Submits one exact old session and its registry-issued MAIN borrow retirement receipt.
     * A still-active old borrow keeps the record retryable after successful controller and sink
     * closes; those closes are not replayed. The receipt binds the old version, Surface and
     * borrower under the registry lock, so a caller cannot substitute a newer clean borrow.
     */
    fun submitTicket(
        actions: HostTeardownActions,
        receipt: HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    ): HostTeardownTicket = synchronized(lock) {
        require(!actions.isEmpty) { "A host teardown ticket requires old resources" }
        check(records.none { it.actions.ownsSameResources(actions) }) {
            "The exact host resources already have a teardown owner"
        }
        check(records.size + deferredReleases.size < MAX_PENDING_WORK_ITEMS) {
            "Too many pending host teardowns"
        }
        val ticketState = HostTeardownTicketState(
            actions.controllerIdentity,
            actions.sinkIdentity,
            receipt,
        )
        val ticket = HostTeardownTicket(ticketState)
        records.addLast(Record(
            id = nextWorkId,
            actions = actions,
            ticket = ticket,
            ticketState = ticketState,
            oldMainBorrowRetirement = receipt,
        ))
        nextWorkId = Math.addExact(nextWorkId, 1L)
        ticket
    }

    fun state(): HostTeardownBarrierState = synchronized(lock) {
        when {
            records.isNotEmpty() && records.first().inFlight != null ->
                HostTeardownBarrierState.IN_FLIGHT
            records.isNotEmpty() -> HostTeardownBarrierState.AVAILABLE
            deferredReleases.isNotEmpty() && deferredReleases.first().inFlight != null ->
                HostTeardownBarrierState.IN_FLIGHT
            deferredReleases.isNotEmpty() -> HostTeardownBarrierState.AVAILABLE
            else -> HostTeardownBarrierState.CLEAR
        }
    }

    fun pendingCount(): Int = synchronized(lock) { records.size + deferredReleases.size }

    /**
     * Defers a host-owned resource release while any renderer retirement remains unproven.
     * The closure must capture only the resource, never an Activity or UI callback.
     *
     * @return true when the process barrier accepted ownership; false when release is safe now.
     */
    fun deferReleaseWhilePending(identity: Any, release: () -> Unit): Boolean =
        synchronized(lock) {
            if (records.isEmpty() && deferredReleases.isEmpty()) return@synchronized false
            if (deferredReleases.none { registered -> registered.identity === identity }) {
                check(records.size + deferredReleases.size < MAX_PENDING_WORK_ITEMS) {
                    "Too many pending host teardown resources"
                }
                deferredReleases.addLast(DeferredRelease(nextWorkId, identity, release))
                nextWorkId = Math.addExact(nextWorkId, 1L)
            }
            true
        }

    fun claim(): HostTeardownClaimResult = synchronized(lock) {
        records.firstOrNull()?.let { record ->
            if (record.inFlight != null) return@synchronized HostTeardownClaimResult.InFlight
            val claim = HostTeardownClaim(record.id, HostTeardownWorkKind.RESOURCE_TEARDOWN)
            record.inFlight = claim
            return@synchronized HostTeardownClaimResult.Claimed(claim)
        }
        deferredReleases.firstOrNull()?.let { deferred ->
            if (deferred.inFlight != null) return@synchronized HostTeardownClaimResult.InFlight
            val claim = HostTeardownClaim(deferred.id, HostTeardownWorkKind.DEFERRED_RELEASE)
            deferred.inFlight = claim
            return@synchronized HostTeardownClaimResult.Claimed(claim)
        }
        HostTeardownClaimResult.Clear
    }

    /** Executes a current claim outside the coordinator lock and commits its result atomically. */
    fun execute(claim: HostTeardownClaim): HostTeardownOutcome {
        val outcome = when (claim.kind) {
            HostTeardownWorkKind.RESOURCE_TEARDOWN -> {
                val record = synchronized(lock) {
                    val record = records.firstOrNull()
                    check(record?.id == claim.recordId && record.inFlight === claim) {
                        "Host teardown claim is stale"
                    }
                    record
                }
                val teardown = if (record.resourcesClosed) HostTeardownOutcome.Clean else {
                    runHostTeardown(
                        requestControllerClose = record.actions.requestControllerClose,
                        awaitControllerClosed = record.actions.awaitControllerClosed,
                        closeMediaSink = record.actions.closeMediaSink,
                    )
                }
                if (!teardown.isClean) teardown else {
                    // A later borrow-proof retry must not repeat a successful sink close.
                    synchronized(lock) { record.resourcesClosed = true }
                    val receipt = record.oldMainBorrowRetirement
                    if (receipt == null) HostTeardownOutcome.Clean else try {
                        if (receipt.isRetired) HostTeardownOutcome.Clean else HostTeardownOutcome.Failed(
                            HostTeardownStage.DEFERRED_RESOURCE_RELEASE,
                        )
                    } catch (error: Exception) {
                        HostTeardownOutcome.Failed(
                            HostTeardownStage.DEFERRED_RESOURCE_RELEASE,
                            error,
                        )
                    }
                }
            }
            HostTeardownWorkKind.DEFERRED_RELEASE -> {
                val release = synchronized(lock) {
                    val deferred = deferredReleases.firstOrNull()
                    check(deferred?.id == claim.recordId && deferred.inFlight === claim) {
                        "Deferred release claim is stale"
                    }
                    deferred.release
                }
                try {
                    release()
                    HostTeardownOutcome.Clean
                } catch (error: Exception) {
                    HostTeardownOutcome.Failed(
                        HostTeardownStage.DEFERRED_RESOURCE_RELEASE,
                        error,
                    )
                }
            }
        }
        synchronized(lock) {
            when (claim.kind) {
                HostTeardownWorkKind.RESOURCE_TEARDOWN -> {
                    val record = records.firstOrNull()
                    check(record?.id == claim.recordId && record.inFlight === claim) {
                        "Host teardown claim changed during execution"
                    }
                    if (outcome.isClean) {
                        record.ticket?.let { ticket ->
                            checkNotNull(record.ticketState).complete(ticket)
                        }
                        records.removeFirst()
                    } else record.inFlight = null
                }
                HostTeardownWorkKind.DEFERRED_RELEASE -> {
                    val deferred = deferredReleases.firstOrNull()
                    check(deferred?.id == claim.recordId && deferred.inFlight === claim) {
                        "Deferred release claim changed during execution"
                    }
                    if (outcome.isClean) deferredReleases.removeFirst()
                    else deferred.inFlight = null
                }
            }
        }
        return outcome
    }

    private companion object {
        // Covers the protocol's bounded multi-display graph plus repeated Activity recreation.
        const val MAX_PENDING_WORK_ITEMS = 128
    }
}

internal sealed interface HostTeardownDrainResult {
    data object Clear : HostTeardownDrainResult
    data object InFlight : HostTeardownDrainResult
    data class Failed(val outcome: HostTeardownOutcome.Failed) : HostTeardownDrainResult
}

/** Drains all currently available records; an attempt already owned by another Activity is busy. */
internal fun drainHostTeardowns(
    coordinator: HostTeardownCoordinator,
): HostTeardownDrainResult {
    while (true) {
        when (val claimed = coordinator.claim()) {
            HostTeardownClaimResult.Clear -> return HostTeardownDrainResult.Clear
            HostTeardownClaimResult.InFlight -> return HostTeardownDrainResult.InFlight
            is HostTeardownClaimResult.Claimed -> {
                when (val outcome = coordinator.execute(claimed.claim)) {
                    HostTeardownOutcome.Clean -> Unit
                    is HostTeardownOutcome.Failed ->
                        return HostTeardownDrainResult.Failed(outcome)
                }
            }
        }
    }
}

/** The process owner contains resources only; Activity callbacks remain Activity-owned. */
internal object HostTeardownProcessBarrier {
    val coordinator = HostTeardownCoordinator()
}
