package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
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

class UiSyncObservedPresentationSourceTest {
    @Test
    fun closedReplayRejectionRetainsFailedDelegateAcrossSessionCloseRetries() {
        val firstCleanupFailure = IllegalStateException("first observer close failed")
        val secondCleanupFailure = IllegalStateException("second observer close failed")
        val calls = AtomicInteger()
        val closed = snapshot(closed = true)
        val source = UiSyncObservedPresentationSource(
            source(closed) {
                when (calls.incrementAndGet()) {
                    1 -> throw firstCleanupFailure
                    2 -> throw secondCleanupFailure
                }
            },
            setOf("cluster"),
        )

        val rejection = assertThrows(IllegalStateException::class.java) {
            source.subscribeWithReplay("cluster", UltraPresentationObserver { })
        }
        assertEquals("UI Sync renderer subscribed a closed presentation source", rejection.message)
        assertSame(firstCleanupFailure, rejection.suppressed.single())
        assertEquals(1, calls.get())
        assertEquals(
            "UI Sync rejected observer retirement is pending",
            assertThrows(IllegalStateException::class.java) {
                source.subscribeWithReplay("cluster", UltraPresentationObserver { })
            }.message,
        )
        assertEquals(1, calls.get())

        assertSame(secondCleanupFailure, assertThrows(IllegalStateException::class.java) {
            source.sealAndAwaitQuiescence()
        })
        assertEquals(2, calls.get())
        source.sealAndAwaitQuiescence()
        source.sealAndAwaitQuiescence()
        assertEquals(3, calls.get())
    }

    @Test
    fun sealingWaitsForSubscriptionConstructionAndRetriesRejectedDelegate() {
        val subscribeEntered = CountDownLatch(1)
        val releaseSubscribe = CountDownLatch(1)
        val firstCleanupFailure = IllegalStateException("observer close failed once")
        val calls = AtomicInteger()
        val open = snapshot(closed = false)
        val delegate = object : UiSyncPresentationSource {
            override fun subscribeWithReplay(
                displayId: String,
                observer: UltraPresentationObserver,
            ): UiSyncPresentationSubscription {
                subscribeEntered.countDown()
                check(releaseSubscribe.await(3, TimeUnit.SECONDS))
                return UiSyncPresentationSubscription(open, Closeable {
                    if (calls.incrementAndGet() == 1) throw firstCleanupFailure
                })
            }

            override fun snapshot(displayId: String): UltraPresentationSnapshot = open
        }
        val source = UiSyncObservedPresentationSource(delegate, setOf("cluster"))
        val subscribeFailure = AtomicReference<Throwable?>()
        val sealFailure = AtomicReference<Throwable?>()
        val subscribing = Thread({
            subscribeFailure.set(runCatching {
                source.subscribeWithReplay("cluster", UltraPresentationObserver { })
            }.exceptionOrNull())
        }, "ui-sync-rejected-subscribe-test")
        val sealing = Thread({
            sealFailure.set(runCatching { source.sealAndAwaitQuiescence() }.exceptionOrNull())
        }, "ui-sync-rejected-seal-test")

        subscribing.start()
        assertTrue(subscribeEntered.await(3, TimeUnit.SECONDS))
        sealing.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (sealing.state != Thread.State.TIMED_WAITING) {
                check(System.nanoTime() < deadline) { "seal did not wait for subscription construction" }
                Thread.yield()
            }
        } finally {
            releaseSubscribe.countDown()
            subscribing.join(3_000)
            sealing.join(3_000)
        }
        assertFalse(subscribing.isAlive)
        assertFalse(sealing.isAlive)
        val rejection = subscribeFailure.get() as IllegalStateException
        assertEquals("UI Sync presentation source was revoked", rejection.message)
        assertSame(firstCleanupFailure, rejection.suppressed.single())
        assertNull(sealFailure.get())
        assertEquals(2, calls.get())
        source.sealAndAwaitQuiescence()
        assertEquals(2, calls.get())
    }

    @Test
    fun snapshotFailureRetainsRawObserverWhenInitialCloseThrowsOnceOrTwice() {
        for (failedCloses in 1..2) {
            val snapshotFailure = IllegalStateException("initial snapshot failed")
            val closeFailures = List(failedCloses) { index ->
                IllegalStateException("raw observer close failed ${index + 1}")
            }
            val closeCalls = AtomicInteger()
            val observerLease = Closeable {
                val attempt = closeCalls.getAndIncrement()
                closeFailures.getOrNull(attempt)?.let { throw it }
            }
            val observed = UiSyncObservedPresentationSource(
                presentationSource(
                    observe = { _, _ -> observerLease },
                    snapshot = { throw snapshotFailure },
                ),
                setOf("cluster"),
            )

            val thrown = assertThrows(IllegalStateException::class.java) {
                observed.subscribeWithReplay("cluster", UltraPresentationObserver { })
            }
            assertSame(snapshotFailure, thrown)
            assertSame(closeFailures.first(), thrown.suppressed.single())
            assertFalse(observed.hasEveryLiveSubscription())
            if (failedCloses == 2) {
                assertSame(closeFailures[1], assertThrows(IllegalStateException::class.java) {
                    observed.sealAndAwaitQuiescence()
                })
            }
            observed.sealAndAwaitQuiescence()
            observed.sealAndAwaitQuiescence()
            assertEquals(failedCloses + 1, closeCalls.get())
        }
    }

    private fun source(snapshot: UltraPresentationSnapshot, close: () -> Unit) =
        object : UiSyncPresentationSource {
            override fun subscribeWithReplay(
                displayId: String,
                observer: UltraPresentationObserver,
            ) = UiSyncPresentationSubscription(snapshot, Closeable(close))

            override fun snapshot(displayId: String): UltraPresentationSnapshot = snapshot
        }

    private fun snapshot(closed: Boolean) = UltraPresentationSnapshot(
        displayId = UltraUiSyncDisplayId("cluster"),
        revision = 0,
        droppedEventCount = 0,
        closed = closed,
        events = emptyList(),
    )
}
