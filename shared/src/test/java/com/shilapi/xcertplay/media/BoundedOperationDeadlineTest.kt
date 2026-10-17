package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedOperationDeadlineTest {
    @Test
    fun everyPhaseConsumesTheSameFakeClockBudget() {
        val now = AtomicLong(1_000_000_000L)
        val deadline = BoundedOperationDeadline(100, now::get)

        assertEquals(100L, deadline.remainingMillis())
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(35))
        assertEquals(65L, deadline.remainingMillis())
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(80))
        assertEquals(0L, deadline.remainingMillis())
    }

    @Test
    fun twoLatchPhasesCannotEachConsumeTheOriginalTimeout() {
        val deadline = BoundedOperationDeadline(60)
        val never = CountDownLatch(1)
        val started = System.nanoTime()

        never.await(minOf(25L, deadline.remainingMillis()), TimeUnit.MILLISECONDS)
        never.await(deadline.remainingMillis(), TimeUnit.MILLISECONDS)

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("elapsed=$elapsedMillis", elapsedMillis < 180L)
    }

    @Test
    fun overflowingCallerBudgetIsRejectedBeforeMillisToNanosConversion() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedOperationDeadline(Long.MAX_VALUE)
        }
    }

    @Test
    fun operationLockWaitIsChargedToTheSameDeadline() {
        val lock = ReentrantLock()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = Thread {
            lock.lock()
            try {
                held.countDown()
                release.await()
            } finally {
                lock.unlock()
            }
        }
        owner.start()
        assertTrue(held.await(1, TimeUnit.SECONDS))
        val deadline = BoundedOperationDeadline(30)
        val started = System.nanoTime()

        assertEquals(BoundedLockResult.TIMED_OUT, tryLockBeforeDeadline(lock, deadline))
        assertEquals(0L, deadline.remainingMillis())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200)

        release.countDown()
        owner.join(1_000)
    }

    @Test
    fun multipleDecoderWaitsReceiveOnlyTheDecreasingGlobalRemainder() {
        val now = AtomicLong(0L)
        val deadline = BoundedOperationDeadline(100, now::get)
        val budgets = mutableListOf<Long>()

        forEachWithinDeadline(listOf("a", "b", "c"), deadline) { _, remaining ->
            budgets += remaining
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(30))
        }

        assertEquals(listOf(100L, 70L, 40L), budgets)
    }
}
