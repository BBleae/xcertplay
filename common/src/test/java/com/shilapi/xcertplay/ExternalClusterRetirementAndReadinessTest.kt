package com.shilapi.xcertplay

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalClusterRetirementAndReadinessTest {
    private val destination = DisplayDestination(
        DisplayDestinationRole.CLUSTER, DisplayDestinationId("exact-panel"),
    )

    @Test fun failedExactCloseIsRetainedAndNeverAliasesReplacement() {
        val scheduled = ArrayDeque<() -> Unit>()
        val queue = ExternalClusterCloseRetirementQueue { retry -> scheduled.addLast(retry) }
        var oldCanClose = false
        var oldAttempts = 0
        var replacementAttempts = 0
        val old = ExactExternalClusterClose(Closeable {
            oldAttempts++
            if (!oldCanClose) error("transient old-window close failure")
        })
        val replacement = ExactExternalClusterClose(Closeable { replacementAttempts++ })

        assertFalse(queue.retire(old))
        assertTrue(queue.retire(replacement))
        assertFalse(old.isClosed)
        assertTrue(replacement.isClosed)
        assertEquals(1, replacementAttempts)

        scheduled.removeFirst().invoke()
        assertEquals(2, oldAttempts)
        assertEquals(1, replacementAttempts)
        assertFalse(scheduled.isEmpty())

        oldCanClose = true
        scheduled.removeFirst().invoke()
        assertTrue(old.isClosed)
        assertEquals(1, replacementAttempts)
        assertTrue(scheduled.isEmpty())
        assertTrue(queue.retire(old))
        assertEquals(3, oldAttempts)
    }

    @Test fun displayListenerUnregisterFailureKeepsExactRegistrationForRetry() {
        var canUnregister = false
        var attempts = 0
        val registration = RetryableExternalDisplayListenerRegistration {
            attempts++
            if (!canUnregister) error("DisplayManager unregister failed")
        }
        registration.markRegistered()
        assertTrue(runCatching { registration.close() }.isFailure)
        assertTrue(registration.registered)
        canUnregister = true
        registration.close()
        assertFalse(registration.registered)
        registration.close()
        assertEquals(2, attempts)
    }

    @Test fun readinessIsPostedOneShotAndRejectsReplacedOrWithdrawnPublication() {
        val posted = ArrayDeque<Runnable>()
        var current: ExternalClusterReadySnapshot? = null
        val signals = ExternalClusterReadySignals(
            dispatch = { task -> posted.addLast(task); true },
            snapshot = { current },
        )
        val events = ArrayList<DisplayDestination>()
        val subscription = signals.subscribe { events += it }
        val old = Any()
        current = ExternalClusterReadySnapshot(old, destination)
        signals.offer(old)
        assertTrue(events.isEmpty()) // never inline with controller/registry publication
        current = null
        posted.removeFirst().run()
        assertTrue(events.isEmpty())

        val replacement = Any()
        current = ExternalClusterReadySnapshot(replacement, destination)
        signals.offer(old)
        posted.removeFirst().run()
        assertTrue(events.isEmpty()) // equal destination is not an exact-generation proof
        signals.offer(replacement)
        posted.removeFirst().run()
        assertEquals(listOf(destination), events)
        signals.offer(replacement)
        assertTrue(posted.isEmpty()) // subscription was consumed
        subscription.close()
    }

    @Test fun readinessReplayAndCloseNeverDeliverLateCallback() {
        val posted = ArrayDeque<Runnable>()
        val publication = Any()
        var current: ExternalClusterReadySnapshot? =
            ExternalClusterReadySnapshot(publication, destination)
        val signals = ExternalClusterReadySignals(
            dispatch = { task -> posted.addLast(task); true },
            snapshot = { current },
        )
        val received = ArrayList<DisplayDestination>()
        signals.subscribe { received += it }
        assertTrue(received.isEmpty())
        posted.removeFirst().run()
        assertEquals(listOf(destination), received)

        val cancelled = signals.subscribe { received += it }
        cancelled.close()
        posted.removeFirst().run()
        assertEquals(1, received.size)

        signals.subscribe { received += it }
        signals.close()
        current = null
        posted.removeFirst().run()
        assertEquals(1, received.size)
        signals.subscribe { received += it }
        assertTrue(posted.isEmpty())
    }

    @Test fun readinessCloseWaitsForAnAlreadyInflightCallback() {
        val posted = ArrayDeque<Runnable>()
        val publication = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val signals = ExternalClusterReadySignals(
            dispatch = { task -> posted.addLast(task); true },
            snapshot = { ExternalClusterReadySnapshot(publication, destination) },
        )
        signals.subscribe {
            entered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
        }
        val delivery = Thread { posted.removeFirst().run() }
        delivery.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = Thread { signals.close(); closed.countDown() }
        closer.start()
        assertFalse(closed.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        delivery.join(2_000L)
        closer.join(2_000L)
        assertFalse(delivery.isAlive)
        assertFalse(closer.isAlive)
    }
}
