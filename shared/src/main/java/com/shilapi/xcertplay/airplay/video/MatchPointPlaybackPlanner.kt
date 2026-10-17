package com.shilapi.xcertplay.airplay.video

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Limits retained by one local MatchPoint playback planner, not by the wire decoder. */
data class MatchPointPlaybackPlannerLimits(
    val maximumQueuedItems: Int = 128,
    val maximumObservedItemIds: Int = 4_096,
    val maximumObservedRequestIds: Int = 4_096,
    val maximumPendingActions: Int = 64,
) {
    init {
        require(maximumQueuedItems in 1..512)
        require(maximumObservedItemIds in maximumQueuedItems..8_192)
        require(maximumObservedRequestIds in 1..65_536)
        require(maximumPendingActions in 1..256)
    }
}

enum class MatchPointPlaybackPlannerState {
    OPEN,
    CLOSING,
    FAILED,
    CLOSED,
}

enum class MatchPointPlaybackPlanFailure {
    WRONG_SESSION,
    TERMINAL,
    UNSUPPORTED_COMMAND,
    DUPLICATE_ITEM,
    DUPLICATE_REQUEST,
    UNKNOWN_ITEM,
    QUEUE_LIMIT,
    OBSERVED_ID_LIMIT,
    REQUEST_ID_LIMIT,
    PENDING_ACTION_LIMIT,
    PLAYER_PORT_FAILED,
    WORKER_START_FAILED,
}

class MatchPointPlaybackPlanException(
    val reason: MatchPointPlaybackPlanFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Read-only completion of one local-player operation. There are deliberately no synchronous
 * continuations, public completion/cancellation methods, or unbounded `join`: executing a user
 * continuation on the sole player drain worker would deadlock if it waited for another action.
 */
interface MatchPointPlaybackResult<out T> {
    val isDone: Boolean

    /** A bounded wait. Callers may poll again after timeout, outside player callbacks. */
    @Throws(TimeoutException::class, InterruptedException::class)
    fun await(timeoutMillis: Long): T
}

/**
 * A resolved local-player action. The planner never opens a URL or supplies PIC/Pair-TLS-PSK
 * authorization. A future player must independently establish transport and content readiness.
 */
sealed interface MatchPointPlaybackAction {
    data class Insert(
        val item: MatchPointQueueItem,
        val itemAfter: MatchPointItemReference?,
        val metadata: MatchPointMetadata?,
        val forInterstitial: Boolean?,
    ) : MatchPointPlaybackAction

    data class Remove(val item: MatchPointItemReference) : MatchPointPlaybackAction
    data object Stop : MatchPointPlaybackAction

    data class Seek(
        val item: MatchPointItemReference,
        val position: MatchPointSeekPosition,
        val forInterstitial: Boolean?,
    ) : MatchPointPlaybackAction

    data class SetRate(
        val rate: Double,
        val networkClockTime: MatchPointCmTime?,
        val wallClockTimeDiff: Double?,
        val forInterstitial: Boolean?,
    ) : MatchPointPlaybackAction
}

/** A session-owned player seam. Calls are serialized and never made under the planner state lock. */
interface MatchPointPlaybackPort {
    fun dispatch(action: MatchPointPlaybackAction)
    fun close() {}
}

/** Counts only: item identifiers and content locations must not enter diagnostics. */
data class MatchPointPlaybackPlannerSnapshot(
    val generation: Long,
    val state: MatchPointPlaybackPlannerState,
    val queuedItems: Int,
    val plannedItems: Int,
    val pendingActions: Int,
    val observedItemIds: Int,
    val observedRequestIds: Int,
    val dispatchedActions: Long,
    val playerPortCloseComplete: Boolean,
)

/**
 * Session-bound, bounded queue/command planner for already-decoded MatchPoint messages.
 *
 * This is not a MatchPoint RCS handler or a VideoPlayback readiness claim. The caller must supply
 * the exact [MatchPointSessionIdentity] from its authenticated runtime on every command. A null
 * `itemAfter` appends; a non-null `itemAfter` inserts directly behind a queued item. `stop` does
 * not invent a queue-deletion rule: only `removePlayQueueItem` removes a queued item.
 *
 * [apply] and [close] are non-blocking. They return read-only completion results, allowing a player
 * callback to call either method, [snapshot], or [queueOrder] from another thread without a lock
 * cycle. Waiting for a result created from inside the currently running player callback would still
 * be a causal cycle, but [MatchPointPlaybackResult.await] has a mandatory finite timeout. A callback failure is
 * terminal because its external side effect may already have happened.
 */
class MatchPointPlaybackPlanner(
    val identity: MatchPointSessionIdentity,
    private val port: MatchPointPlaybackPort,
    private val limits: MatchPointPlaybackPlannerLimits = MatchPointPlaybackPlannerLimits(),
) {
    private val lock = Any()
    private val committedQueue = ArrayList<String>()
    private val plannedQueue = ArrayList<String>()
    private val pending = ArrayDeque<PendingAction>()
    private val observedItemIds = HashSet<String>()
    private val observedRequestIds = HashSet<Long>()
    private var state = MatchPointPlaybackPlannerState.OPEN
    private var draining = false
    private var dispatchedActions = 0L
    private var playerPortCloseComplete = false
    private var closeFuture: Completion<Unit>? = null

    /** Completes successfully only after the local player accepted the action. */
    fun apply(
        session: MatchPointSessionIdentity,
        message: MatchPointMessage,
    ): MatchPointPlaybackResult<MatchPointPlaybackAction> {
        val completion = Completion<MatchPointPlaybackAction>()
        val worker = synchronized(lock) {
            if (session !== identity) reject(MatchPointPlaybackPlanFailure.WRONG_SESSION)
            if (state != MatchPointPlaybackPlannerState.OPEN) {
                reject(MatchPointPlaybackPlanFailure.TERMINAL)
            }
            if (message.kind != MatchPointMessageKind.REQUEST &&
                message.kind != MatchPointMessageKind.ONE_WAY
            ) {
                reject(MatchPointPlaybackPlanFailure.UNSUPPORTED_COMMAND)
            }
            if (pending.size >= limits.maximumPendingActions) {
                reject(MatchPointPlaybackPlanFailure.PENDING_ACTION_LIMIT)
            }
            val action = planActionLocked(message)
            if (message.kind == MatchPointMessageKind.REQUEST) {
                val requestId = requireNotNull(message.messageId)
                if (requestId in observedRequestIds) {
                    reject(MatchPointPlaybackPlanFailure.DUPLICATE_REQUEST)
                }
                if (observedRequestIds.size >= limits.maximumObservedRequestIds) {
                    reject(MatchPointPlaybackPlanFailure.REQUEST_ID_LIMIT)
                }
                observedRequestIds.add(requestId)
            }
            advanceQueue(plannedQueue, action)
            if (action is MatchPointPlaybackAction.Insert) {
                observedItemIds.add(action.item.uuid)
            }
            pending.addLast(PendingAction(action, completion))
            newWorkerLocked()
        }
        startWorker(worker)
        return completion.view
    }

    private fun planActionLocked(message: MatchPointMessage): MatchPointPlaybackAction =
        when (val payload = message.payload) {
            is MatchPointInsertPlayQueueItem -> {
                val id = payload.item.uuid
                if (id in observedItemIds) reject(MatchPointPlaybackPlanFailure.DUPLICATE_ITEM)
                if (plannedQueue.size >= limits.maximumQueuedItems) {
                    reject(MatchPointPlaybackPlanFailure.QUEUE_LIMIT)
                }
                if (observedItemIds.size >= limits.maximumObservedItemIds) {
                    reject(MatchPointPlaybackPlanFailure.OBSERVED_ID_LIMIT)
                }
                payload.itemAfter?.let { after ->
                    if (after.uuid !in plannedQueue) {
                        reject(MatchPointPlaybackPlanFailure.UNKNOWN_ITEM)
                    }
                }
                MatchPointPlaybackAction.Insert(
                    payload.item,
                    payload.itemAfter,
                    payload.metadata,
                    payload.forInterstitial,
                )
            }
            is MatchPointRemovePlayQueueItem -> {
                if (payload.item.uuid !in plannedQueue) {
                    reject(MatchPointPlaybackPlanFailure.UNKNOWN_ITEM)
                }
                MatchPointPlaybackAction.Remove(payload.item)
            }
            MatchPointStop -> MatchPointPlaybackAction.Stop
            is MatchPointSeek -> {
                if (payload.item.uuid !in plannedQueue) {
                    reject(MatchPointPlaybackPlanFailure.UNKNOWN_ITEM)
                }
                MatchPointPlaybackAction.Seek(payload.item, payload.position, payload.forInterstitial)
            }
            is MatchPointSetRate -> MatchPointPlaybackAction.SetRate(
                payload.rate,
                payload.networkClockTime,
                payload.wallClockTimeDiff,
                payload.forInterstitial,
            )
            else -> reject(MatchPointPlaybackPlanFailure.UNSUPPORTED_COMMAND)
        }

    private fun advanceQueue(queue: MutableList<String>, action: MatchPointPlaybackAction) {
        when (action) {
            is MatchPointPlaybackAction.Insert -> {
                val after = action.itemAfter?.uuid
                val insertAt = if (after == null) queue.size else queue.indexOf(after) + 1
                queue.add(insertAt, action.item.uuid)
            }
            is MatchPointPlaybackAction.Remove -> queue.remove(action.item.uuid)
            MatchPointPlaybackAction.Stop,
            is MatchPointPlaybackAction.Seek,
            is MatchPointPlaybackAction.SetRate,
            -> Unit
        }
    }

    fun snapshot(): MatchPointPlaybackPlannerSnapshot = synchronized(lock) {
        MatchPointPlaybackPlannerSnapshot(
            generation = identity.generation,
            state = state,
            queuedItems = committedQueue.size,
            plannedItems = plannedQueue.size,
            pendingActions = pending.size,
            observedItemIds = observedItemIds.size,
            observedRequestIds = observedRequestIds.size,
            dispatchedActions = dispatchedActions,
            playerPortCloseComplete = playerPortCloseComplete,
        )
    }

    /** Local queue order for a player adapter; never include these identifiers in diagnostics. */
    fun queueOrder(): List<MatchPointItemReference> = synchronized(lock) {
        committedQueue.map(::MatchPointItemReference)
    }

    /** Publishes terminal state immediately and completes after the player port actually closes. */
    fun close(): MatchPointPlaybackResult<Unit> {
        val canceled: List<PendingAction>
        val completion: Completion<Unit>
        val worker: Thread?
        val alreadyClosed: Boolean
        synchronized(lock) {
            if (state == MatchPointPlaybackPlannerState.CLOSED && playerPortCloseComplete) {
                return Completion<Unit>().also { it.succeed(Unit) }.view
            }
            closeFuture?.let { return it.view }
            state = MatchPointPlaybackPlannerState.CLOSING
            canceled = pending.toList()
            pending.clear()
            committedQueue.clear()
            plannedQueue.clear()
            observedItemIds.clear()
            observedRequestIds.clear()
            completion = Completion()
            closeFuture = completion
            alreadyClosed = playerPortCloseComplete
            if (alreadyClosed) {
                state = MatchPointPlaybackPlannerState.CLOSED
                closeFuture = null
                worker = null
            } else {
                worker = newWorkerLocked()
            }
        }
        canceled.forEach { it.completion.fail(terminalFailure()) }
        if (alreadyClosed) completion.succeed(Unit)
        startWorker(worker)
        return completion.view
    }

    private fun newWorkerLocked(): Thread? {
        if (draining) return null
        draining = true
        return Thread(::drain, "matchpoint-playback-g${identity.generation}").apply {
            isDaemon = true
        }
    }

    private fun startWorker(worker: Thread?) {
        if (worker == null) return
        try {
            worker.start()
        } catch (failure: Throwable) {
            val canceled: List<PendingAction>
            val closing: Completion<Unit>?
            synchronized(lock) {
                draining = false
                state = MatchPointPlaybackPlannerState.FAILED
                canceled = pending.toList()
                pending.clear()
                committedQueue.clear()
                plannedQueue.clear()
                observedItemIds.clear()
                observedRequestIds.clear()
                closing = closeFuture
                closeFuture = null
            }
            val terminal = MatchPointPlaybackPlanException(
                MatchPointPlaybackPlanFailure.WORKER_START_FAILED,
                "MatchPoint local player worker could not start",
                failure,
            )
            canceled.forEach { it.completion.fail(terminal) }
            closing?.fail(terminal)
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                when {
                    state == MatchPointPlaybackPlannerState.OPEN && pending.isNotEmpty() ->
                        Work.Dispatch(pending.removeFirst())
                    !playerPortCloseComplete &&
                        (state == MatchPointPlaybackPlannerState.CLOSING ||
                            state == MatchPointPlaybackPlannerState.FAILED) -> Work.Close
                    else -> {
                        draining = false
                        Work.Done
                    }
                }
            }
            when (next) {
                is Work.Dispatch -> dispatch(next.pending)
                Work.Close -> {
                    closePort()
                    return
                }
                Work.Done -> return
            }
        }
    }

    private fun dispatch(next: PendingAction) {
        val failure = runCatching {
            port.dispatch(next.action)
            synchronized(lock) {
                if (state == MatchPointPlaybackPlannerState.OPEN) {
                    advanceQueue(committedQueue, next.action)
                }
                if (dispatchedActions < Long.MAX_VALUE) dispatchedActions++
            }
        }.exceptionOrNull()
        if (failure == null) {
            next.completion.succeed(next.action)
            return
        }
        val canceled: List<PendingAction>
        synchronized(lock) {
            state = MatchPointPlaybackPlannerState.FAILED
            canceled = pending.toList()
            pending.clear()
            committedQueue.clear()
            plannedQueue.clear()
            observedItemIds.clear()
            observedRequestIds.clear()
        }
        val terminal = MatchPointPlaybackPlanException(
            MatchPointPlaybackPlanFailure.PLAYER_PORT_FAILED,
            "MatchPoint local player action failed",
            failure,
        )
        next.completion.fail(terminal)
        canceled.forEach { it.completion.fail(terminal) }
    }

    private fun closePort() {
        val failure = runCatching { port.close() }.exceptionOrNull()
        val closing: Completion<Unit>?
        synchronized(lock) {
            playerPortCloseComplete = failure == null
            closing = closeFuture
            state = if (failure == null && closing != null) {
                MatchPointPlaybackPlannerState.CLOSED
            } else {
                MatchPointPlaybackPlannerState.FAILED
            }
            closeFuture = null
            draining = false
        }
        if (failure == null) {
            closing?.succeed(Unit)
        } else {
            closing?.fail(
                MatchPointPlaybackPlanException(
                    MatchPointPlaybackPlanFailure.PLAYER_PORT_FAILED,
                    "MatchPoint local player close failed",
                    failure,
                ),
            )
        }
    }

    private fun terminalFailure() = MatchPointPlaybackPlanException(
        MatchPointPlaybackPlanFailure.TERMINAL,
        "MatchPoint playback command canceled by session close",
    )

    private fun reject(reason: MatchPointPlaybackPlanFailure): Nothing =
        throw MatchPointPlaybackPlanException(reason, "MatchPoint playback command rejected: $reason")

    private data class PendingAction(
        val action: MatchPointPlaybackAction,
        val completion: Completion<MatchPointPlaybackAction>,
    )

    /** The mutable sink never escapes; only [view] is returned to callers. */
    private class Completion<T> {
        private val done = CountDownLatch(1)
        @Volatile private var outcome: Outcome<T>? = null

        val view: MatchPointPlaybackResult<T> = object : MatchPointPlaybackResult<T> {
            override val isDone: Boolean get() = done.count == 0L

            override fun await(timeoutMillis: Long): T {
                require(timeoutMillis in 1..MAXIMUM_AWAIT_MILLIS) {
                    "MatchPoint result wait must be finite and at most $MAXIMUM_AWAIT_MILLIS ms"
                }
                if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw TimeoutException("MatchPoint playback result was not ready before the wait limit")
                }
                return when (val value = requireNotNull(outcome)) {
                    is Outcome.Success -> value.value
                    is Outcome.Failure -> throw value.error
                }
            }
        }

        fun succeed(value: T) = publish(Outcome.Success(value))
        fun fail(error: Throwable) = publish(Outcome.Failure(error))

        private fun publish(value: Outcome<T>) {
            synchronized(this) {
                if (outcome != null) return
                outcome = value
                done.countDown()
            }
        }

        private sealed interface Outcome<out T> {
            data class Success<T>(val value: T) : Outcome<T>
            data class Failure(val error: Throwable) : Outcome<Nothing>
        }

        private companion object {
            const val MAXIMUM_AWAIT_MILLIS = 30_000L
        }
    }

    private sealed interface Work {
        data class Dispatch(val pending: PendingAction) : Work
        data object Close : Work
        data object Done : Work
    }
}
