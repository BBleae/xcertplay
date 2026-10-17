package com.shilapi.xcertplay.airplay.rcs.uisync

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

private class UiSyncExecutorCloseTestHooks(
    val beforeShutdownAttempt: () -> Unit,
)

/**
 * Fixed-width, strictly bounded callback executor for a UI Sync presentation pipeline.
 *
 * The queue bound applies only to callbacks waiting for a worker; at most [parallelism]
 * additional callbacks can be running. Admission never falls back to the submitting thread.
 * Closing interrupts running callbacks and prevents queued callbacks from starting. Plain
 * [FutureTask] instances are also cancelled; arbitrary [java.util.concurrent.Future.cancel]
 * implementations are not called because they may block indefinitely. A successful close
 * proves that no callback remains queued or running. Each close attempt has a bounded wait; if a
 * callback ignores interruption, close throws and the same executor must be retained and retried
 * before its presentation resources are released.
 */
class BoundedUiSyncPresentationExecutor private constructor(
    override val parallelism: Int,
    override val maximumPendingCallbacks: Int,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
    private val closeTestHooks: UiSyncExecutorCloseTestHooks?,
) : UiSyncBoundedPresentationExecutor, Closeable {
    constructor(
        parallelism: Int,
        maximumPendingCallbacks: Int,
        closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
    ) : this(
        parallelism = parallelism,
        maximumPendingCallbacks = maximumPendingCallbacks,
        closeTimeoutMillis = closeTimeoutMillis,
        closeTestHooks = null,
    )

    /** Deterministic test seam for the state-publication/shutdown-attempt race. */
    internal constructor(
        parallelism: Int,
        maximumPendingCallbacks: Int,
        closeTimeoutMillis: Long,
        beforeShutdownAttempt: () -> Unit,
    ) : this(
        parallelism = parallelism,
        maximumPendingCallbacks = maximumPendingCallbacks,
        closeTimeoutMillis = closeTimeoutMillis,
        closeTestHooks = UiSyncExecutorCloseTestHooks(beforeShutdownAttempt),
    )

    init {
        require(parallelism in MIN_PARALLELISM..MAX_PARALLELISM) {
            "parallelism must be in $MIN_PARALLELISM..$MAX_PARALLELISM"
        }
        require(maximumPendingCallbacks in MIN_PENDING_CALLBACKS..MAX_PENDING_CALLBACKS) {
            "maximumPendingCallbacks must be in " +
                "$MIN_PENDING_CALLBACKS..$MAX_PENDING_CALLBACKS"
        }
        require(closeTimeoutMillis in MIN_CLOSE_TIMEOUT_MILLIS..MAX_CLOSE_TIMEOUT_MILLIS) {
            "closeTimeoutMillis must be in " +
                "$MIN_CLOSE_TIMEOUT_MILLIS..$MAX_CLOSE_TIMEOUT_MILLIS"
        }
    }

    override val mayExecuteInline: Boolean = false

    private val executorId = NEXT_EXECUTOR_ID.getAndIncrement()
    private val nextWorkerId = AtomicInteger()
    private val workerMarker = ThreadLocal<Boolean>()
    private val shutdown = AtomicBoolean(false)

    private val workerFactory = ThreadFactory { target ->
        Thread(
            {
                workerMarker.set(true)
                try {
                    target.run()
                } finally {
                    workerMarker.remove()
                }
            },
            "$THREAD_NAME_PREFIX-$executorId-${nextWorkerId.incrementAndGet()}",
        ).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val executor = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maximumPendingCallbacks),
        workerFactory,
        ThreadPoolExecutor.AbortPolicy(),
    )

    override fun execute(command: Runnable) {
        if (shutdown.get()) {
            throw rejectedClosed()
        }

        try {
            executor.execute(CallbackTask(command))
        } catch (rejected: RejectedExecutionException) {
            if (shutdown.get() || executor.isShutdown) {
                throw rejectedClosed(rejected)
            }
            throw RejectedExecutionException(
                "UI Sync presentation callback queue is full " +
                    "(capacity=$maximumPendingCallbacks, parallelism=$parallelism)",
                rejected,
            )
        }
    }

    override fun close() {
        // A failed attempt must not consume the next attempt's waiting budget. In particular,
        // the owner must be able to retry after a non-cooperative callback finally exits.
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
        shutdown.set(true)

        // Every closer helps drive the idempotent ThreadPoolExecutor transition. In particular,
        // a closer that stalls immediately after publishing the shutdown flag cannot leave later
        // closers merely awaiting an executor that nobody has actually shut down.
        try {
            closeTestHooks?.beforeShutdownAttempt?.invoke()
        } catch (failure: Throwable) {
            reportShutdownAttemptHookFailure(failure)
        }
        executor.shutdownNow().forEach { queued ->
            try {
                (queued as? CallbackTask)?.cancelBeforeStart()
            } catch (failure: Throwable) {
                reportCancellationFailure(failure)
            }
        }

        // A worker cannot prove its own termination. Reporting success here would allow the
        // caller to release EGL resources while its callback can still present a frame.
        if (workerMarker.get() == true) {
            throw IllegalStateException(
                "UI Sync presentation callback cannot retire its own executor; " +
                    "retry close() from an external owner",
            )
        }

        var interrupted = false
        try {
            while (!executor.isTerminated) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) break
                try {
                    if (executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (!executor.isTerminated) {
            throw IllegalStateException(
                "UI Sync presentation callbacks did not retire within ${closeTimeoutMillis}ms; " +
                    "retain the presentation owner and retry close()",
            )
        }
    }

    private fun rejectedClosed(cause: RejectedExecutionException? = null) =
        RejectedExecutionException("UI Sync presentation executor is closed", cause)

    private inner class CallbackTask(
        private val command: Runnable,
    ) : Runnable {
        private val cancelled = AtomicBoolean(false)

        override fun run() {
            if (cancelled.get()) return
            try {
                command.run()
            } catch (failure: Throwable) {
                reportCallbackFailure(failure)
            }
        }

        fun cancelBeforeStart() {
            if (!cancelled.compareAndSet(false, true)) return
            // Future.cancel() is untrusted user code. FutureTask itself has a non-blocking
            // cancellation path; subclasses may override cancel()/done(), so exclude them.
            if (command.javaClass == FutureTask::class.java) {
                (command as FutureTask<*>).cancel(false)
            }
        }
    }

    companion object {
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 256
        const val MIN_PENDING_CALLBACKS = 1
        const val MAX_PENDING_CALLBACKS = 65_536
        const val MIN_CLOSE_TIMEOUT_MILLIS = 1L
        const val MAX_CLOSE_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 2_000L

        private const val THREAD_NAME_PREFIX = "xcertplay-ui-sync"
        private val NEXT_EXECUTOR_ID = AtomicLong(1L)
        private val LOGGER = Logger.getLogger(BoundedUiSyncPresentationExecutor::class.java.name)

        private fun reportCallbackFailure(failure: Throwable) {
            try {
                LOGGER.log(Level.SEVERE, "UI Sync presentation callback failed", failure)
            } catch (_: Throwable) {
                // Failure reporting must not take a callback lane down.
            }
        }

        private fun reportCancellationFailure(failure: Throwable) {
            try {
                LOGGER.log(Level.WARNING, "Queued UI Sync callback cancellation failed", failure)
            } catch (_: Throwable) {
                // One hostile Future must not skip cancellation of the rest or bounded waiting.
            }
        }

        private fun reportShutdownAttemptHookFailure(failure: Throwable) {
            try {
                LOGGER.log(Level.WARNING, "UI Sync shutdown-attempt test hook failed", failure)
            } catch (_: Throwable) {
                // A test observer must never prevent the real shutdown attempt.
            }
        }
    }
}
