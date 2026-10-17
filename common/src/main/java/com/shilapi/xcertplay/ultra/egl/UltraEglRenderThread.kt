package com.shilapi.xcertplay.ultra.egl

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Owns the only thread allowed to touch EGL, GLES, SurfaceTexture, or driver resources. */
internal class UltraEglRenderThread(
    driver: UltraEglDriver,
    commandCapacity: Int = BoundedEglCommandQueue.DEFAULT_CAPACITY,
    threadName: String = "CarPlay-Ultra-EGL",
    private val defaultControlTimeoutMillis: Long = DEFAULT_CONTROL_TIMEOUT_MILLIS,
    private val beforeRunningTimeoutPoisonForTest: (() -> Unit)? = null,
    private val beforeTimeoutOutcomeReadForTest: (() -> Unit)? = null,
    private val afterActionCompletedBeforeDispositionForTest: (() -> Unit)? = null,
) : Closeable {
    private val queue = BoundedEglCommandQueue(commandCapacity)
    private val startupLatch = CountDownLatch(1)
    private val startupFailure = AtomicReference<Throwable?>()
    private val stopped = CountDownLatch(1)
    private val stateLock = Any()
    private val clusterReplayOutbox = UltraEglClusterReplayOutbox()
    private var uncertainControl: ControlInvocation<*>? = null
    private var startupTimedOut = false
    private var retirementOnly = false
    private var shutdownRequested = false
    @Volatile private var remoteAcquisitionObserver: ((UltraEglAcquiredRemoteFrame) -> Unit)? = null
    private val thread: Thread
    private val core = UltraEglRenderCore(
        driver = driver,
        signalFrame = { token ->
            if (!hasRetirementUncertainty()) queue.offerFrame(token) else false
        },
        observeRemoteAcquisition = { acquired ->
            remoteAcquisitionObserver?.invoke(acquired)
        },
    )

    init {
        require(defaultControlTimeoutMillis > 0L) {
            "defaultControlTimeoutMillis must be positive"
        }
        thread = Thread({ runLoop() }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    fun <T> call(
        timeoutMillis: Long = defaultControlTimeoutMillis,
        allowStartupFailure: Boolean = false,
        action: (UltraEglRenderCore) -> T,
    ): T = callUntil(deadlineAfterMillis(timeoutMillis), allowStartupFailure, action)

    fun <T> callUntil(
        deadlineNanos: Long,
        allowStartupFailure: Boolean = false,
        action: (UltraEglRenderCore) -> T,
    ): T {
        check(Thread.currentThread() !== thread) {
            "Synchronous EGL control calls are not reentrant"
        }
        if (!awaitStartup(deadlineNanos)) {
            synchronized(stateLock) {
                startupTimedOut = true
                retirementOnly = true
            }
            throw UltraEglStartupTimeoutException(
                "Timed out starting the Ultra EGL render thread; resources remain tracked",
            )
        }
        if (!allowStartupFailure) {
            startupFailure.get()?.let { throw IllegalStateException("Ultra EGL failed to start", it) }
        }

        val invocation = ControlInvocation(
            callerDeadlineNanos = deadlineNanos,
            afterActionCompletedBeforeDisposition = afterActionCompletedBeforeDispositionForTest,
        ) { action(core) }
        val offered = synchronized(stateLock) {
            check(!shutdownRequested) { "Ultra EGL render thread is shutting down" }
            check(allowStartupFailure || !retirementOnly) {
                "Ultra EGL state is retirement-only"
            }
            check(uncertainControl == null) {
                "Ultra EGL control state is retirement-uncertain"
            }
            queue.offerControl(
                UltraEglQueuedCommand.Control(
                    action = {
                        try {
                            val timedOut = invocation.executeAndAwaitCallerDisposition()
                            if (timedOut) enterRetirementUncertainty(invocation)
                        } finally {
                            invocation.markExecutionSettled()
                        }
                    },
                    onAbandoned = invocation::abandon,
                ),
            )
        }
        check(offered) { "Ultra EGL command queue is closed or full" }
        return when (
            val outcome = invocation.awaitUntil(
                deadlineNanos,
                cancelIfPending = true,
                beforeTimeoutOutcomeRead = beforeTimeoutOutcomeReadForTest,
            )
        ) {
            is ControlAwaitOutcome.Completed -> {
                if (!invocation.permitNextCommand()) {
                    enterRetirementUncertainty(invocation)
                    throw UltraEglRunningControlTimeoutException(
                        "Ultra EGL control completed after its caller deadline; retirement only",
                    )
                }
                outcome.result.getOrThrow()
            }
            ControlAwaitOutcome.CancelledBeforeStart -> throw UltraEglPendingControlTimeoutException(
                "Timed out before Ultra EGL control command started; command was cancelled",
            )
            ControlAwaitOutcome.TimedOutRunning -> {
                beforeRunningTimeoutPoisonForTest?.invoke()
                invocation.poisonNextCommand()
                enterRetirementUncertainty(invocation)
                throw UltraEglRunningControlTimeoutException(
                    "Ultra EGL control command exceeded its bound; retirement is uncertain",
                )
            }
        }
    }

    /** Waits for the same timed-out RUNNING operation; it never schedules duplicate cleanup. */
    fun awaitUncertainControl(deadlineNanos: Long): Boolean {
        val invocation = synchronized(stateLock) { uncertainControl } ?: return true
        val completed = when (invocation.awaitUntil(deadlineNanos, cancelIfPending = false)) {
            is ControlAwaitOutcome.Completed,
            ControlAwaitOutcome.CancelledBeforeStart,
            -> true
            ControlAwaitOutcome.TimedOutRunning -> false
        }
        if (!completed || !invocation.awaitExecutionSettled(deadlineNanos)) return false
        synchronized(stateLock) {
            if (uncertainControl === invocation) uncertainControl = null
        }
        return true
    }

    /** Startup timeout is tracked by this owner; close can observe the same initialization. */
    fun awaitStartupForRetirement(deadlineNanos: Long): Boolean = awaitStartup(deadlineNanos)

    fun hasRetirementUncertainty(): Boolean = synchronized(stateLock) {
        retirementOnly || startupTimedOut || uncertainControl != null
    }

    fun submit(
        submission: UltraEglResolvedSubmission,
        timeoutMillis: Long = defaultControlTimeoutMillis,
    ): UltraEglRenderTicket {
        val deadline = deadlineAfterMillis(timeoutMillis)
        val ticket = UltraEglRenderTicket(submission.revision)
        if (!awaitStartup(deadline)) {
            synchronized(stateLock) {
                startupTimedOut = true
                retirementOnly = true
            }
            ticket.complete(
                UltraEglRenderResult(
                    submission.revision,
                    UltraEglRenderDisposition.FAILED,
                    UltraEglStartupTimeoutException("Ultra EGL startup is still in flight"),
                ),
            )
            return ticket
        }
        val unavailable = synchronized(stateLock) {
            shutdownRequested || uncertainControl != null || retirementOnly || startupTimedOut
        }
        if (unavailable || startupFailure.get() != null) {
            ticket.complete(
                UltraEglRenderResult(
                    submission.revision,
                    UltraEglRenderDisposition.FAILED,
                    IllegalStateException("Ultra EGL render thread is unavailable"),
                ),
            )
            return ticket
        }
        val command = UltraEglQueuedCommand.Scene(
            revision = submission.revision,
            action = {
                try {
                    val receipt = core.submit(submission)
                    ticket.complete(
                        UltraEglRenderResult(
                            submission.revision,
                            UltraEglRenderDisposition.PRESENTED,
                            producerTimestampNanosByDestination =
                                receipt.producerTimestampNanosByDestination,
                            outputGenerationsByDestination =
                                receipt.outputGenerationsByDestination,
                            outputBatchGeneration = receipt.outputBatchGeneration,
                            graphRevision = receipt.graphRevision,
                        ),
                    )
                    receipt.clusterReplay?.let(clusterReplayOutbox::publish)
                } catch (problem: Throwable) {
                    ticket.complete(
                        UltraEglRenderResult(
                            submission.revision,
                            UltraEglRenderDisposition.FAILED,
                            problem,
                        ),
                    )
                }
            },
            onCoalesced = {
                ticket.complete(
                    UltraEglRenderResult(
                        submission.revision,
                        UltraEglRenderDisposition.COALESCED,
                    ),
                )
            },
            onAbandoned = {
                ticket.complete(
                    UltraEglRenderResult(
                        submission.revision,
                        UltraEglRenderDisposition.FAILED,
                        IllegalStateException("Ultra EGL render was abandoned before presentation"),
                    ),
                )
            },
        )
        if (!queue.offerScene(command)) command.onAbandoned()
        return ticket
    }

    fun queueSnapshot(): UltraEglQueueSnapshot = queue.snapshot()

    /** Auxiliary evidence only: observer absence/failure never gates ordinary frame replay. */
    internal fun setRemoteAcquisitionObserver(
        observer: ((UltraEglAcquiredRemoteFrame) -> Unit)?,
    ) {
        remoteAcquisitionObserver = observer
    }

    internal fun subscribeClusterReplay(
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
        onSuperseded: (UltraEglClusterReplayReceipt) -> Unit = {},
    ): UltraEglClusterReplaySubscription =
        clusterReplayOutbox.subscribe(onReceipt, onRevoked, onSuperseded)

    /** Publication is O(1) and never calls the observer on the EGL owner thread. */
    internal fun publishClusterReplay(receipt: UltraEglClusterReplayReceipt?) {
        receipt?.let(clusterReplayOutbox::publish)
    }

    internal fun revokeClusterReplay() = clusterReplayOutbox.revoke()

    /** Bounded stop after core/driver retirement has already been proven. */
    fun shutdown(deadlineNanos: Long): Boolean {
        synchronized(stateLock) { shutdownRequested = true }
        val observerRetired = clusterReplayOutbox.retire(
            minOf(
                UltraEglClusterReplayOutbox.MAXIMUM_RETIRE_TIMEOUT_MILLIS,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadlineNanos)),
            ),
        )
        queue.close()
        val remaining = remainingNanos(deadlineNanos)
        if (remaining <= 0L) return observerRetired && stopped.count == 0L
        return try {
            observerRetired && stopped.await(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun close() {
        val deadline = deadlineAfterMillis(defaultControlTimeoutMillis)
        check(shutdown(deadline)) { "Timed out stopping the Ultra EGL render thread" }
    }

    private fun awaitStartup(deadlineNanos: Long): Boolean {
        if (startupLatch.count == 0L) return true
        val remaining = remainingNanos(deadlineNanos)
        if (remaining <= 0L) return false
        return try {
            startupLatch.await(remaining, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runLoop() {
        try {
            try {
                core.initialize()
            } catch (problem: Throwable) {
                startupFailure.set(problem)
            } finally {
                startupLatch.countDown()
            }

            while (true) {
                val command = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } ?: break
                when (command) {
                    is UltraEglQueuedCommand.Control -> command.action()
                    is UltraEglQueuedCommand.Frame -> {
                        if (!hasRetirementUncertainty()) {
                            try {
                                publishClusterReplay(core.frameAvailable(command.token))
                            } catch (_: Throwable) {
                                // The core records a terminal failure; diagnostics expose it.
                            }
                        }
                    }
                    is UltraEglQueuedCommand.Scene -> {
                        if (hasRetirementUncertainty()) command.onAbandoned() else command.action()
                    }
                }
            }
        } finally {
            startupLatch.countDown()
            stopped.countDown()
        }
    }

    private fun enterRetirementUncertainty(invocation: ControlInvocation<*>) {
        synchronized(stateLock) {
            if (uncertainControl == null) uncertainControl = invocation
            retirementOnly = true
        }
        queue.abandonQueuedAfterUncertainty()
    }

    private class ControlInvocation<T>(
        private val callerDeadlineNanos: Long,
        private val afterActionCompletedBeforeDisposition: (() -> Unit)?,
        private val action: () -> T,
    ) {
        private val state = AtomicInteger(PENDING)
        private val latch = CountDownLatch(1)
        private val callerDisposition = AtomicInteger(DISPOSITION_UNDECIDED)
        private val dispositionLatch = CountDownLatch(1)
        private val executionSettled = CountDownLatch(1)
        private val result = AtomicReference<Result<T>?>()
        private val completedAtNanos = AtomicLong(Long.MIN_VALUE)

        fun executeAndAwaitCallerDisposition(): Boolean {
            if (!state.compareAndSet(PENDING, RUNNING)) return false
            val completed = runCatching(action)
            completedAtNanos.set(System.nanoTime())
            result.set(completed)
            state.set(COMPLETED)
            latch.countDown()
            afterActionCompletedBeforeDisposition?.invoke()
            val remaining = remainingNanos(callerDeadlineNanos)
            if (remaining > 0L) {
                try {
                    dispositionLatch.await(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (callerDisposition.compareAndSet(DISPOSITION_UNDECIDED, DISPOSITION_POISON)) {
                dispositionLatch.countDown()
            }
            return callerDisposition.get() == DISPOSITION_POISON
        }

        fun abandon() {
            if (state.compareAndSet(PENDING, ABANDONED)) latch.countDown()
        }

        fun permitNextCommand(): Boolean {
            val completedAt = completedAtNanos.get()
            if (
                callerDeadlineNanos != Long.MAX_VALUE &&
                completedAt != Long.MIN_VALUE &&
                completedAt - callerDeadlineNanos >= 0L
            ) {
                poisonNextCommand()
                return false
            }
            if (callerDisposition.compareAndSet(DISPOSITION_UNDECIDED, DISPOSITION_CONTINUE)) {
                dispositionLatch.countDown()
                return true
            }
            return callerDisposition.get() == DISPOSITION_CONTINUE
        }

        fun poisonNextCommand() {
            if (callerDisposition.compareAndSet(DISPOSITION_UNDECIDED, DISPOSITION_POISON)) {
                dispositionLatch.countDown()
            }
        }

        fun markExecutionSettled() = executionSettled.countDown()

        fun awaitExecutionSettled(deadlineNanos: Long): Boolean {
            if (executionSettled.count == 0L) return true
            val remaining = remainingNanos(deadlineNanos)
            if (remaining <= 0L) return false
            return try {
                executionSettled.await(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun awaitUntil(
            deadlineNanos: Long,
            cancelIfPending: Boolean,
            beforeTimeoutOutcomeRead: (() -> Unit)? = null,
        ): ControlAwaitOutcome<T> {
            while (true) {
                when (state.get()) {
                    COMPLETED -> return ControlAwaitOutcome.Completed(checkNotNull(result.get()))
                    ABANDONED -> return ControlAwaitOutcome.CancelledBeforeStart
                }
                val remaining = remainingNanos(deadlineNanos)
                if (remaining <= 0L) break
                try {
                    if (latch.await(remaining, TimeUnit.NANOSECONDS)) continue
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                break
            }
            beforeTimeoutOutcomeRead?.invoke()
            if (cancelIfPending && state.compareAndSet(PENDING, ABANDONED)) {
                latch.countDown()
                return ControlAwaitOutcome.CancelledBeforeStart
            }
            return when (state.get()) {
                COMPLETED -> ControlAwaitOutcome.Completed(checkNotNull(result.get()))
                ABANDONED -> ControlAwaitOutcome.CancelledBeforeStart
                else -> ControlAwaitOutcome.TimedOutRunning
            }
        }

        private companion object {
            const val PENDING = 0
            const val RUNNING = 1
            const val COMPLETED = 2
            const val ABANDONED = 3
            const val DISPOSITION_UNDECIDED = 0
            const val DISPOSITION_CONTINUE = 1
            const val DISPOSITION_POISON = 2
        }
    }

    private sealed interface ControlAwaitOutcome<out T> {
        data class Completed<T>(val result: Result<T>) : ControlAwaitOutcome<T>
        data object CancelledBeforeStart : ControlAwaitOutcome<Nothing>
        data object TimedOutRunning : ControlAwaitOutcome<Nothing>
    }

    companion object {
        const val DEFAULT_CONTROL_TIMEOUT_MILLIS = 2_000L
    }
}

internal fun deadlineAfterMillis(timeoutMillis: Long): Long {
    require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
    if (timeoutMillis == Long.MAX_VALUE) return Long.MAX_VALUE
    val now = System.nanoTime()
    val delta = if (timeoutMillis > Long.MAX_VALUE / 1_000_000L) {
        Long.MAX_VALUE
    } else {
        timeoutMillis * 1_000_000L
    }
    return try {
        Math.addExact(now, delta)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal fun remainingNanos(deadlineNanos: Long): Long = when {
    deadlineNanos == Long.MAX_VALUE -> Long.MAX_VALUE
    else -> (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
}
