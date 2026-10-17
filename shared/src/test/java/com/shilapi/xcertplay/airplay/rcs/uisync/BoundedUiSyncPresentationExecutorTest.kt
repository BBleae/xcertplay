package com.shilapi.xcertplay.airplay.rcs.uisync

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedUiSyncPresentationExecutorTest {
    @Test
    fun executeUsesNamedDaemonWorkerAndNeverSubmittingThread() {
        val executor = executor()
        val submittingThread = Thread.currentThread()
        val callbackThread = AtomicReference<Thread>()
        val completed = CountDownLatch(1)
        try {
            executor.execute {
                callbackThread.set(Thread.currentThread())
                completed.countDown()
            }

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            val actual = callbackThread.get()
            assertNotSame(submittingThread, actual)
            assertTrue(actual.isDaemon)
            assertTrue(actual.name.startsWith("xcertplay-ui-sync-"))
            assertFalse(executor.mayExecuteInline)
        } finally {
            executor.close()
        }
    }

    @Test
    fun fixedParallelismRunsIndependentLanes() {
        val executor = executor(parallelism = 3, maximumPendingCallbacks = 3)
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val workerNames = Collections.synchronizedSet(mutableSetOf<String>())
        try {
            repeat(3) {
                executor.execute {
                    workerNames += Thread.currentThread().name
                    entered.countDown()
                    release.await()
                }
            }

            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(3, workerNames.size)
            assertEquals(3, executor.parallelism)
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun saturationRejectsInsteadOfRunningOnCaller() {
        val executor = executor(parallelism = 1, maximumPendingCallbacks = 1)
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedCompleted = CountDownLatch(1)
        try {
            executor.execute {
                running.countDown()
                release.await()
            }
            assertTrue(running.await(2, TimeUnit.SECONDS))
            executor.execute { queuedCompleted.countDown() }

            val rejected = assertThrows(RejectedExecutionException::class.java) {
                executor.execute { throw AssertionError("must never run on the caller") }
            }
            assertTrue(rejected.message.orEmpty().contains("queue is full"))

            release.countDown()
            assertTrue(queuedCompleted.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun timedOutCloseCancelsQueuedFutureRejectsNewCallbacksAndCanBeRetried() {
        val executor = executor(closeTimeoutMillis = 40L, maximumPendingCallbacks = 2)
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val release = AtomicBoolean(false)
        val queuedRuns = AtomicInteger()
        executor.execute {
            started.countDown()
            while (!release.get()) {
                Thread.interrupted()
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L))
            }
            finished.countDown()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val queued = FutureTask { queuedRuns.incrementAndGet() }
        executor.execute(queued)

        val startedClose = System.nanoTime()
        val failure = assertThrows(IllegalStateException::class.java) { executor.close() }
        val closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedClose)

        assertTrue("close took ${closeMillis}ms", closeMillis < 1_000L)
        assertTrue(failure.message.orEmpty().contains("retry close()"))
        assertEquals(1L, finished.count)
        assertTrue(queued.isCancelled)
        assertEquals(0, queuedRuns.get())
        val rejected = assertThrows(RejectedExecutionException::class.java) {
            executor.execute {}
        }
        assertTrue(rejected.message.orEmpty().contains("closed"))

        release.set(true)
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        // A later close gets a fresh deadline and proves that the worker actually retired.
        val repeatedClose = System.nanoTime()
        executor.close()
        assertTrue(
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - repeatedClose) < 500L,
        )
    }

    @Test
    fun closeRetiresEveryLaneAndDropsQueuedFramesBeforeReportingSuccess() {
        val executor = executor(
            parallelism = 2,
            maximumPendingCallbacks = 2,
            closeTimeoutMillis = 40L,
        )
        val entered = CountDownLatch(2)
        val finished = CountDownLatch(2)
        val release = AtomicBoolean(false)
        val queuedRuns = AtomicInteger()
        try {
            repeat(2) {
                executor.execute {
                    entered.countDown()
                    while (!release.get()) {
                        Thread.interrupted()
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L))
                    }
                    finished.countDown()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(2) { executor.execute { queuedRuns.incrementAndGet() } }

            assertThrows(IllegalStateException::class.java) { executor.close() }
            assertEquals(2L, finished.count)
            assertEquals(0, queuedRuns.get())
        } finally {
            release.set(true)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            executor.close()
        }
        assertEquals(0, queuedRuns.get())
    }

    @Test
    fun closeRestoresInterruptStatus() {
        val executor = executor(closeTimeoutMillis = 20L)
        val started = CountDownLatch(1)
        val release = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        executor.execute {
            started.countDown()
            while (!release.get()) {
                Thread.interrupted()
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L))
            }
            finished.countDown()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        try {
            Thread.currentThread().interrupt()
            assertThrows(IllegalStateException::class.java) { executor.close() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.set(true)
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun callbackFailureDoesNotConsumeWorkerCapacity() {
        val executor = executor()
        val afterFailure = CountDownLatch(1)
        try {
            executor.execute { throw AssertionError("expected test failure") }
            executor.execute { afterFailure.countDown() }

            assertTrue(afterFailure.await(2, TimeUnit.SECONDS))
        } finally {
            executor.close()
        }
    }

    @Test
    fun laterCloserCompletesShutdownWhenFirstCloserStallsAfterPublishingState() {
        val firstAttemptEntered = CountDownLatch(1)
        val releaseFirstAttempt = CountDownLatch(1)
        val shutdownAttempts = AtomicInteger()
        val executor = BoundedUiSyncPresentationExecutor(
            parallelism = 1,
            maximumPendingCallbacks = 1,
            closeTimeoutMillis = 500L,
            beforeShutdownAttempt = {
                if (shutdownAttempts.incrementAndGet() == 1) {
                    firstAttemptEntered.countDown()
                    releaseFirstAttempt.await()
                }
            },
        )
        val workerStarted = CountDownLatch(1)
        val workerInterrupted = CountDownLatch(1)
        executor.execute {
            workerStarted.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                workerInterrupted.countDown()
            }
        }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))

        val firstCloser = Thread(executor::close, "first-ui-sync-closer").apply { start() }
        assertTrue(firstAttemptEntered.await(2, TimeUnit.SECONDS))
        val secondCloser = Thread(executor::close, "second-ui-sync-closer").apply { start() }

        assertTrue(workerInterrupted.await(2, TimeUnit.SECONDS))
        secondCloser.join(2_000L)
        assertFalse("helper closer did not finish", secondCloser.isAlive)
        assertThrows(RejectedExecutionException::class.java) { executor.execute {} }

        releaseFirstAttempt.countDown()
        firstCloser.join(2_000L)
        assertFalse("first closer did not finish", firstCloser.isAlive)
        assertTrue(shutdownAttempts.get() >= 2)
    }

    @Test
    fun arbitraryFutureCancellationCannotBlockShutdownOrSkipRemainingQueue() {
        val executor = executor(maximumPendingCallbacks = 3)
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        executor.execute {
            workerStarted.countDown()
            releaseWorker.await()
        }
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS))

        val throwing = ThrowingCancellationFuture()
        val following = FutureTask<Unit>({}, Unit)
        executor.execute(throwing)
        executor.execute(following)

        executor.close()

        assertEquals(0, throwing.cancelAttempts.get())
        assertTrue(following.isCancelled)
        releaseWorker.countDown()
    }

    @Test
    fun workerCannotClaimItsOwnExecutorHasRetired() {
        val executor = executor(closeTimeoutMillis = 500L)
        val returnedFromClose = CountDownLatch(1)
        val failedClose = AtomicReference<IllegalStateException>()
        executor.execute {
            failedClose.set(
                assertThrows(IllegalStateException::class.java) { executor.close() },
            )
            returnedFromClose.countDown()
        }

        assertTrue(returnedFromClose.await(2, TimeUnit.SECONDS))
        assertTrue(failedClose.get().message.orEmpty().contains("external owner"))
        assertThrows(RejectedExecutionException::class.java) { executor.execute {} }
        executor.close()
    }

    @Test
    fun cooperativeWorkerExitsAfterCloseInterrupt() {
        val executor = executor(closeTimeoutMillis = 500L)
        val worker = AtomicReference<Thread>()
        val entered = CountDownLatch(1)
        executor.execute {
            worker.set(Thread.currentThread())
            entered.countDown()
            CountDownLatch(1).await()
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        executor.close()

        val workerThread = worker.get()
        workerThread.join(2_000L)
        assertFalse("cooperative worker remained alive", workerThread.isAlive)
    }

    @Test
    fun constructorEnforcesEveryBound() {
        assertThrows(IllegalArgumentException::class.java) {
            executor(parallelism = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor(parallelism = BoundedUiSyncPresentationExecutor.MAX_PARALLELISM + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor(maximumPendingCallbacks = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor(
                maximumPendingCallbacks =
                    BoundedUiSyncPresentationExecutor.MAX_PENDING_CALLBACKS + 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor(closeTimeoutMillis = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            executor(
                closeTimeoutMillis =
                    BoundedUiSyncPresentationExecutor.MAX_CLOSE_TIMEOUT_MILLIS + 1L,
            )
        }

        BoundedUiSyncPresentationExecutor(
            parallelism = BoundedUiSyncPresentationExecutor.MAX_PARALLELISM,
            maximumPendingCallbacks = BoundedUiSyncPresentationExecutor.MAX_PENDING_CALLBACKS,
            closeTimeoutMillis = BoundedUiSyncPresentationExecutor.MAX_CLOSE_TIMEOUT_MILLIS,
        ).close()
    }

    private fun executor(
        parallelism: Int = 1,
        maximumPendingCallbacks: Int = 4,
        closeTimeoutMillis: Long = 200L,
    ) = BoundedUiSyncPresentationExecutor(
        parallelism = parallelism,
        maximumPendingCallbacks = maximumPendingCallbacks,
        closeTimeoutMillis = closeTimeoutMillis,
    )

    private class ThrowingCancellationFuture : java.util.concurrent.Future<Unit>, Runnable {
        val cancelAttempts = AtomicInteger()

        override fun run() = Unit

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelAttempts.incrementAndGet()
            throw AssertionError("hostile Future.cancel")
        }

        override fun isCancelled() = false

        override fun isDone() = false

        override fun get(): Unit = throw ExecutionException(AssertionError("not completed"))

        override fun get(timeout: Long, unit: TimeUnit): Unit = throw TimeoutException()
    }
}
