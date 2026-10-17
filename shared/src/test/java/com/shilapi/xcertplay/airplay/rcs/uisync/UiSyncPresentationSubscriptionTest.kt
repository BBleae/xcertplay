package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSyncPresentationSubscriptionTest {
    @Test
    fun failedDelegateCloseRetainsExactOwnerForRetryAndPreservesOriginalFailure() {
        val firstFailure = IllegalStateException("observer retirement failed once")
        val closeCalls = AtomicInteger()
        val subscription = UiSyncPresentationSubscription(snapshot(), Closeable {
            if (closeCalls.incrementAndGet() == 1) throw firstFailure
        })

        assertSame(firstFailure, assertThrows(IllegalStateException::class.java) {
            subscription.close()
        })
        assertEquals(1, closeCalls.get())

        subscription.close()
        subscription.close()
        assertEquals(2, closeCalls.get())
    }

    @Test
    fun concurrentCloseIsRejectedWithoutConcurrentDelegateCallAndFailedFirstCanRetry() {
        val delegateEntered = CountDownLatch(1)
        val releaseDelegate = CountDownLatch(1)
        val firstFailure = IllegalStateException("observer retirement failed")
        val closeCalls = AtomicInteger()
        val firstResult = AtomicReference<Throwable?>()
        val subscription = UiSyncPresentationSubscription(snapshot(), Closeable {
            if (closeCalls.incrementAndGet() == 1) {
                delegateEntered.countDown()
                check(releaseDelegate.await(3, TimeUnit.SECONDS))
                throw firstFailure
            }
        })
        val worker = Thread({
            firstResult.set(runCatching { subscription.close() }.exceptionOrNull())
        }, "ui-sync-observer-close-test")

        worker.start()
        try {
            assertTrue(delegateEntered.await(3, TimeUnit.SECONDS))
            val concurrentFailure = assertThrows(IllegalStateException::class.java) {
                subscription.close()
            }
            assertEquals("UI Sync presentation subscription close is already in flight", concurrentFailure.message)
            assertEquals(1, closeCalls.get())
        } finally {
            releaseDelegate.countDown()
            worker.join(3_000)
        }
        assertFalse(worker.isAlive)
        assertSame(firstFailure, firstResult.get())

        subscription.close()
        subscription.close()
        assertNull(runCatching { subscription.close() }.exceptionOrNull())
        assertEquals(2, closeCalls.get())
    }

    private fun snapshot() = UltraPresentationSnapshot(
        displayId = UltraUiSyncDisplayId("cluster"),
        revision = 0,
        droppedEventCount = 0,
        closed = false,
        events = emptyList(),
    )
}
