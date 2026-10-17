package com.shilapi.xcertplay.airplay.video

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounds the local completion drain; these are not MatchPoint wire limits. */
data class MatchPointPlannerPlayerBackendLimits(
    val maximumPendingOutcomes: Int = 64,
    val actionCompletionTimeoutMillis: Long = 10_000,
    val completionPollMillis: Long = 100,
) {
    init {
        require(maximumPendingOutcomes in 1..256)
        require(actionCompletionTimeoutMillis in 1..30_000)
        require(completionPollMillis in 1..actionCompletionTimeoutMillis)
    }
}

enum class MatchPointPlannerPlayerBackendState {
    NEW,
    OPEN,
    CLOSING,
    FAILED,
    CLOSED,
}

enum class MatchPointPlannerPlayerBackendFailure {
    WRONG_SESSION,
    TERMINAL,
    UNSUPPORTED_COMMAND,
    PENDING_OUTCOME_LIMIT,
    PLAYER_ACTION_FAILED,
    PLAYER_ACTION_TIMEOUT,
    RESPONSE_FAILED,
    WORKER_START_FAILED,
}

class MatchPointPlannerPlayerBackendException(
    val reason: MatchPointPlannerPlayerBackendFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Payload-free diagnostics; identifiers and content locations never enter snapshots. */
data class MatchPointPlannerPlayerBackendSnapshot(
    val generation: Long,
    val state: MatchPointPlannerPlayerBackendState,
    val pendingOutcomes: Int,
    val outcomeWorkerRunning: Boolean,
    val completedActions: Long,
    val successfulResponses: Long,
    val planner: MatchPointPlaybackPlannerSnapshot,
)

/**
 * A deliberately unregistered MatchPoint player adapter for the five confirmed queue/playback
 * commands. It does not implement PIC authorization, content transport, playback-info/property or
 * streaming-key replies, and constructing it does not make VideoPlayback negotiable.
 *
 * The injected [MatchPointPlaybackPort] must perform its own authenticated content acquisition and
 * bound each synchronous [MatchPointPlaybackPort.dispatch] call. The planner serializes dispatch;
 * this adapter separately drains completions in that same order. Its finite deadline prevents a
 * pending REQUEST from receiving speculative success if a player call stalls. A stalled player call
 * can still delay physical port close, which remains visible through [close] and [snapshot].
 *
 * All player and session callbacks occur outside this adapter's state lock. [close] is non-blocking;
 * its result covers both player-port close and completion-drain retirement. Retry [close] if the
 * port's close failed. A failed action never sends an invented failure payload or success response:
 * a future fully wired runtime owner must terminate the transport on this terminal condition.
 */
class MatchPointPlannerPlayerBackend(
    val identity: MatchPointSessionIdentity,
    port: MatchPointPlaybackPort,
    plannerLimits: MatchPointPlaybackPlannerLimits = MatchPointPlaybackPlannerLimits(),
    private val limits: MatchPointPlannerPlayerBackendLimits = MatchPointPlannerPlayerBackendLimits(),
    private val nowNanos: () -> Long = System::nanoTime,
) : MatchPointPlayerBackend {
    private val lock = Any()
    private val planner = MatchPointPlaybackPlanner(identity, port, plannerLimits)
    private val pending = ArrayDeque<PendingOutcome>()
    private val drainedAfterClose = CountDownLatch(1)
    private var state = MatchPointPlannerPlayerBackendState.NEW
    private var boundSession: MatchPointPlayerSession? = null
    private var outcomeWorker: Thread? = null
    private var completedActions = 0L
    private var successfulResponses = 0L

    override fun onOpened(session: MatchPointPlayerSession) {
        synchronized(lock) {
            if (session.identity !== identity) reject(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION)
            if (state != MatchPointPlannerPlayerBackendState.NEW) {
                reject(MatchPointPlannerPlayerBackendFailure.TERMINAL)
            }
            boundSession = session
            state = MatchPointPlannerPlayerBackendState.OPEN
        }
    }

    override fun onRequest(session: MatchPointPlayerSession, request: MatchPointInboundRequest) {
        if (request.identity !== identity || request.message.kind != MatchPointMessageKind.REQUEST) {
            reject(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION)
        }
        accept(session, request.message, request)
    }

    override fun onCommand(session: MatchPointPlayerSession, message: MatchPointMessage) {
        if (message.kind != MatchPointMessageKind.ONE_WAY) {
            reject(MatchPointPlannerPlayerBackendFailure.UNSUPPORTED_COMMAND)
        }
        accept(session, message, request = null)
    }

    override fun onClosed(identity: MatchPointSessionIdentity, reason: MatchPointRuntimeCloseReason) {
        if (identity !== this.identity) reject(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION)
        close()
    }

    /** Retires this generation immediately; the returned finite wait includes physical port close. */
    fun close(): MatchPointPlaybackResult<Unit> {
        synchronized(lock) {
            if (state != MatchPointPlannerPlayerBackendState.CLOSED) {
                state = MatchPointPlannerPlayerBackendState.CLOSING
                boundSession = null
                pending.clear()
            }
            if (outcomeWorker == null) drainedAfterClose.countDown()
        }
        val playerClose = planner.close()
        return CloseResult(playerClose)
    }

    fun snapshot(): MatchPointPlannerPlayerBackendSnapshot {
        val plannerSnapshot = planner.snapshot()
        return synchronized(lock) {
            val effectiveState = when {
                state == MatchPointPlannerPlayerBackendState.CLOSING &&
                    plannerSnapshot.playerPortCloseComplete && drainedAfterClose.count == 0L ->
                    MatchPointPlannerPlayerBackendState.CLOSED
                state == MatchPointPlannerPlayerBackendState.CLOSING &&
                    plannerSnapshot.state == MatchPointPlaybackPlannerState.FAILED ->
                    MatchPointPlannerPlayerBackendState.FAILED
                else -> state
            }
            MatchPointPlannerPlayerBackendSnapshot(
                generation = identity.generation,
                state = effectiveState,
                pendingOutcomes = pending.size,
                outcomeWorkerRunning = outcomeWorker != null,
                completedActions = completedActions,
                successfulResponses = successfulResponses,
                planner = plannerSnapshot,
            )
        }
    }

    private fun accept(
        session: MatchPointPlayerSession,
        message: MatchPointMessage,
        request: MatchPointInboundRequest?,
    ) {
        val worker = synchronized(lock) {
            if (session.identity !== identity) {
                reject(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION)
            }
            if (state != MatchPointPlannerPlayerBackendState.OPEN) {
                reject(MatchPointPlannerPlayerBackendFailure.TERMINAL)
            }
            if (session !== boundSession) {
                reject(MatchPointPlannerPlayerBackendFailure.WRONG_SESSION)
            }
            if (!message.hasKnownSchema || !isConfirmedPlaybackCommand(message.payload)) {
                reject(MatchPointPlannerPlayerBackendFailure.UNSUPPORTED_COMMAND)
            }
            if (pending.size >= limits.maximumPendingOutcomes) {
                reject(MatchPointPlannerPlayerBackendFailure.PENDING_OUTCOME_LIMIT)
            }
            val result = planner.apply(identity, message)
            val deadline = saturatingAdd(
                nowNanos(),
                TimeUnit.MILLISECONDS.toNanos(limits.actionCompletionTimeoutMillis),
            )
            pending.addLast(
                PendingOutcome(
                    result = result,
                    session = session,
                    request = request,
                    deadlineNanos = if (request == null) deadline else minOf(deadline, request.deadlineNanos),
                ),
            )
            if (outcomeWorker != null) null else newOutcomeWorkerLocked()
        }
        startOutcomeWorker(worker)
    }

    private fun newOutcomeWorkerLocked(): Thread =
        Thread(::drain, "matchpoint-player-outcomes-g${identity.generation}").apply {
            isDaemon = true
            outcomeWorker = this
        }

    private fun startOutcomeWorker(worker: Thread?) {
        if (worker == null) return
        try {
            worker.start()
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (outcomeWorker === worker) outcomeWorker = null
                drainedAfterClose.countDown()
            }
            fail(MatchPointPlannerPlayerBackendFailure.WORKER_START_FAILED)
            throw MatchPointPlannerPlayerBackendException(
                MatchPointPlannerPlayerBackendFailure.WORKER_START_FAILED,
                "MatchPoint player outcome worker could not start",
                failure,
            )
        }
    }

    private fun drain() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    if (state != MatchPointPlannerPlayerBackendState.OPEN) {
                        pending.clear()
                        return
                    }
                    pending.pollFirst() ?: return
                }
                if (!awaitSuccess(next)) return
                val live = synchronized(lock) {
                    if (state != MatchPointPlannerPlayerBackendState.OPEN ||
                        boundSession !== next.session
                    ) {
                        false
                    } else {
                        if (completedActions < Long.MAX_VALUE) completedActions++
                        true
                    }
                }
                if (!live) return
                val request = next.request ?: continue
                try {
                    // Runtime rechecks request token, deadline, and transport generation.
                    next.session.respondSuccess(request, MatchPointEmptyResponse(request.message.type))
                } catch (_: Throwable) {
                    fail(MatchPointPlannerPlayerBackendFailure.RESPONSE_FAILED)
                    return
                }
                synchronized(lock) {
                    if (successfulResponses < Long.MAX_VALUE) successfulResponses++
                }
            }
        } finally {
            val replacement = synchronized(lock) {
                if (outcomeWorker !== Thread.currentThread()) return@synchronized null
                outcomeWorker = null
                if (state != MatchPointPlannerPlayerBackendState.OPEN) {
                    drainedAfterClose.countDown()
                    null
                } else if (pending.isNotEmpty()) {
                    newOutcomeWorkerLocked()
                } else {
                    null
                }
            }
            startOutcomeWorker(replacement)
        }
    }

    private fun awaitSuccess(next: PendingOutcome): Boolean {
        while (true) {
            if (synchronized(lock) { state != MatchPointPlannerPlayerBackendState.OPEN }) return false
            val remainingNanos = next.deadlineNanos - nowNanos()
            if (remainingNanos <= 0L) {
                fail(MatchPointPlannerPlayerBackendFailure.PLAYER_ACTION_TIMEOUT)
                return false
            }
            val waitMillis = minOf(
                limits.completionPollMillis,
                maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
            )
            try {
                next.result.await(waitMillis)
                if (next.deadlineNanos - nowNanos() <= 0L) {
                    fail(MatchPointPlannerPlayerBackendFailure.PLAYER_ACTION_TIMEOUT)
                    return false
                }
                return true
            } catch (_: TimeoutException) {
                // Poll again under a finite overall deadline; never wait on a player callback.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(MatchPointPlannerPlayerBackendFailure.PLAYER_ACTION_FAILED)
                return false
            } catch (_: Throwable) {
                fail(MatchPointPlannerPlayerBackendFailure.PLAYER_ACTION_FAILED)
                return false
            }
        }
    }

    private fun fail(reason: MatchPointPlannerPlayerBackendFailure) {
        val shouldClose = synchronized(lock) {
            if (state != MatchPointPlannerPlayerBackendState.OPEN) false else {
                state = MatchPointPlannerPlayerBackendState.FAILED
                boundSession = null
                pending.clear()
                true
            }
        }
        if (shouldClose &&
            (reason != MatchPointPlannerPlayerBackendFailure.PLAYER_ACTION_FAILED ||
                planner.snapshot().state == MatchPointPlaybackPlannerState.OPEN)
        ) {
            // A failed dispatch already drives the planner's own close attempt. Calling close()
            // again after that attempt races a deliberate, owner-controlled retry.
            planner.close()
        }
    }

    private inner class CloseResult(
        private val playerClose: MatchPointPlaybackResult<Unit>,
    ) : MatchPointPlaybackResult<Unit> {
        override val isDone: Boolean
            get() = playerClose.isDone && drainedAfterClose.count == 0L

        override fun await(timeoutMillis: Long) {
            require(timeoutMillis in 1..30_000) { "MatchPoint close wait must be finite" }
            val deadline = saturatingAdd(nowNanos(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
            playerClose.await(timeoutMillis)
            val remainingNanos = deadline - nowNanos()
            if (remainingNanos <= 0L || !drainedAfterClose.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw TimeoutException("MatchPoint outcome drain did not retire before close wait limit")
            }
            synchronized(lock) { state = MatchPointPlannerPlayerBackendState.CLOSED }
        }
    }

    private data class PendingOutcome(
        val result: MatchPointPlaybackResult<MatchPointPlaybackAction>,
        val session: MatchPointPlayerSession,
        val request: MatchPointInboundRequest?,
        val deadlineNanos: Long,
    )

    private companion object {
        fun isConfirmedPlaybackCommand(payload: MatchPointPayload): Boolean = when (payload) {
            is MatchPointInsertPlayQueueItem,
            is MatchPointRemovePlayQueueItem,
            MatchPointStop,
            is MatchPointSeek,
            is MatchPointSetRate,
            -> true
            else -> false
        }

        fun saturatingAdd(value: Long, positiveDelta: Long): Long =
            if (value > Long.MAX_VALUE - positiveDelta) Long.MAX_VALUE else value + positiveDelta

        fun reject(reason: MatchPointPlannerPlayerBackendFailure): Nothing =
            throw MatchPointPlannerPlayerBackendException(
                reason,
                "MatchPoint player backend rejected operation: $reason",
            )
    }
}
