package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraPresentationHubTest {
    @Test
    fun boundedStateReplaysLatestEventsAndRetainsUnknownCommands() {
        val hub = UltraPresentationHub(
            DIRECT_EXECUTOR,
            UltraPresentationHubLimits(maximumRetainedEventsPerDisplay = 2),
        )
        val sink = hub.sink("cluster-A")
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_START, "first"))
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_DATA, "second"))
        sink.onPresentationEvent(CarPlayUiSyncPresentationEvent.UnknownCommand(60_000, "opaque"))

        val delivered = mutableListOf<UltraPresentationSnapshot>()
        val lease = hub.observe("cluster-A") { delivered += it }

        assertEquals(1, delivered.size)
        assertEquals(3L, delivered.single().revision)
        assertEquals(1L, delivered.single().droppedEventCount)
        assertEquals(listOf(2, 60_000), delivered.single().events.map { it.type })
        assertNull(delivered.single().events.last().knownCommand)
        val replayedUnknown = delivered.single().events.last().toPresentationEvent()
        assertTrue(replayedUnknown is CarPlayUiSyncPresentationEvent.UnknownCommand)
        assertEquals("opaque", replayedUnknown.data)

        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_END, "fourth"))
        assertEquals(listOf(3L, 4L), delivered.map { it.revision })
        assertEquals(listOf(60_000, 4), delivered.last().events.map { it.type })

        lease.close()
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.REQUEST_LAYOUT, null))
        assertEquals(2, delivered.size)
    }

    @Test
    fun coalescedAndReplayedSnapshotsKeepOriginalAcceptedCommandClockSamples() {
        val queued = mutableListOf<Runnable>()
        val hub = UltraPresentationHub(Executor { queued += it })
        val delivered = mutableListOf<UltraPresentationSnapshot>()
        hub.observe("cluster") { delivered += it }
        queued.removeAt(0).run() // Deliver the initial, empty replay.

        val sink = hub.sink("cluster")
        sink.onPresentationEvent(
            CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.TRANSITION_DATA,
                "first",
                acceptedAtMonotonicNanos = 11L,
            ),
        )
        sink.onPresentationEvent(
            CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.TRANSITION_END,
                "second",
                acceptedAtMonotonicNanos = 22L,
            ),
        )
        sink.onPresentationEvent(
            CarPlayUiSyncPresentationEvent.UnknownCommand(
                60_000,
                "third",
                acceptedAtMonotonicNanos = 33L,
            ),
        )
        assertEquals(1, queued.size)
        queued.removeAt(0).run()

        assertEquals(listOf(0L, 3L), delivered.map { it.revision })
        assertEquals(
            listOf(11L, 22L, 33L),
            delivered.last().events.map { it.acceptedAtMonotonicNanos },
        )
        assertEquals(2L, hub.deliveryDiagnostics("cluster").coalescedSnapshotCount)
        val replay = mutableListOf<UltraPresentationSnapshot>()
        hub.observe("cluster") { replay += it }
        queued.removeAt(0).run()
        assertEquals(
            listOf(11L, 22L, 33L),
            replay.single().events.map { it.toPresentationEvent().acceptedAtMonotonicNanos },
        )
    }

    @Test
    fun freezesInputAndDefensivelyCopiesEveryPublicValueTree() {
        val hub = directHub()
        val bytes = byteArrayOf(1, 2, 3)
        val items = mutableListOf<Any?>(bytes)
        val data = linkedMapOf<String, Any?>("items" to items)

        hub.sink("cluster").onPresentationEvent(
            known(CarPlayUiSyncCommandType.METADATA_TRANSFER, data),
        )
        bytes.fill(9)
        items.clear()
        data.clear()

        fun exposedBytes(): ByteArray {
            val exposedMap = hub.snapshot("cluster").events.single().data as Map<*, *>
            val exposedList = exposedMap["items"] as List<*>
            return exposedList.single() as ByteArray
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), exposedBytes())
        exposedBytes()[0] = 99
        assertArrayEquals(byteArrayOf(1, 2, 3), exposedBytes())
        val exposed = hub.snapshot("cluster").events.single().data as Map<*, *>
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (exposed as MutableMap<String, Any?>)["new"] = true
        }
    }

    @Test
    fun rejectsCyclesAndBudgetViolationsWithoutPublishingPartialState() {
        val hub = UltraPresentationHub(
            DIRECT_EXECUTOR,
            UltraPresentationHubLimits(
                maximumNodesPerEvent = 8,
                maximumCollectionEntriesPerEvent = 8,
                maximumDataBytesPerEvent = 4,
            ),
        )
        val sink = hub.sink("cluster")
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)

        assertThrows(IllegalArgumentException::class.java) {
            sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_DATA, cycle))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sink.onPresentationEvent(
                known(CarPlayUiSyncCommandType.TRANSITION_DATA, byteArrayOf(1, 2, 3, 4, 5)),
            )
        }
        assertEquals(0L, hub.snapshot("cluster").revision)
        assertTrue(hub.snapshot("cluster").events.isEmpty())

        // A shared acyclic object graph is not a cycle and is copied once internally.
        val shared = listOf("shared")
        sink.onPresentationEvent(
            known(CarPlayUiSyncCommandType.TRANSITION_DATA, listOf(shared, shared)),
        )
        assertEquals(1L, hub.snapshot("cluster").revision)
    }

    @Test
    fun retainedByteBudgetEvictsOldEventsIndependentlyOfEventCount() {
        val hub = UltraPresentationHub(
            DIRECT_EXECUTOR,
            UltraPresentationHubLimits(
                maximumRetainedEventsPerDisplay = 10,
                maximumRetainedBytesPerDisplay = 100,
            ),
        )
        val sink = hub.sink("cluster")
        sink.onPresentationEvent(
            known(CarPlayUiSyncCommandType.TRANSITION_DATA, "a".repeat(20)),
        )
        sink.onPresentationEvent(
            known(CarPlayUiSyncCommandType.TRANSITION_DATA, "b".repeat(20)),
        )

        val snapshot = hub.snapshot("cluster")
        assertEquals(2L, snapshot.revision)
        assertEquals(1L, snapshot.droppedEventCount)
        assertEquals(listOf("b".repeat(20)), snapshot.events.map { it.data })
    }

    @Test
    fun staleObserverLeaseCannotRemoveASecondRegistration() {
        val hub = directHub()
        val revisions = mutableListOf<Long>()
        val observer = UltraPresentationObserver { revisions += it.revision }
        val first = hub.observe("cluster", observer)
        val second = hub.observe("cluster", observer)
        assertEquals(listOf(0L, 0L), revisions)

        first.close()
        first.close()
        hub.sink("cluster").onPresentationEvent(
            known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, null),
        )
        assertEquals(listOf(0L, 0L, 1L), revisions)

        second.close()
        hub.sink("cluster").onPresentationEvent(
            known(CarPlayUiSyncCommandType.TRANSITION_END, null),
        )
        assertEquals(listOf(0L, 0L, 1L), revisions)
    }

    @Test
    fun blockedObserverOnOneDisplayDoesNotBlockAnotherDisplay() {
        val callbackExecutor = Executors.newFixedThreadPool(2)
        val hub = UltraPresentationHub(callbackExecutor)
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val fastDelivered = CountDownLatch(1)
        hub.observe("slow") { snapshot ->
            if (snapshot.revision > 0L) {
                observerEntered.countDown()
                assertTrue(releaseObserver.await(5, TimeUnit.SECONDS))
            }
        }
        hub.observe("fast") { snapshot ->
            if (snapshot.revision > 0L) fastDelivered.countDown()
        }
        val slowSink = hub.sink("slow")
        val fastSink = hub.sink("fast")
        try {
            slowSink.onPresentationEvent(
                known(CarPlayUiSyncCommandType.TRANSITION_START, "slow"),
            )
            assertTrue(observerEntered.await(2, TimeUnit.SECONDS))

            fastSink.onPresentationEvent(
                CarPlayUiSyncPresentationEvent.UnknownCommand(61_000, "fast"),
            )
            assertTrue(fastDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, hub.snapshot("fast").revision)

            releaseObserver.countDown()
        } finally {
            releaseObserver.countDown()
            hub.close()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun closeDoesNotWaitForBlockedObserverAndQueuesOneTerminalSnapshot() {
        val callbackExecutor = Executors.newFixedThreadPool(2)
        val hub = UltraPresentationHub(callbackExecutor)
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val terminalDelivered = CountDownLatch(1)
        val revisions = Collections.synchronizedList(mutableListOf<Long>())
        hub.observe("cluster") { snapshot ->
            revisions += snapshot.revision
            if (snapshot.revision == 1L) {
                observerEntered.countDown()
                releaseObserver.await(5, TimeUnit.SECONDS)
            }
            if (snapshot.closed) terminalDelivered.countDown()
        }
        hub.sink("cluster").onPresentationEvent(
            known(CarPlayUiSyncCommandType.TRANSITION_START, null),
        )
        assertTrue(observerEntered.await(2, TimeUnit.SECONDS))

        val closer = Executors.newSingleThreadExecutor()
        try {
            closer.submit { hub.close() }.get(2, TimeUnit.SECONDS)
            assertTrue(hub.snapshot("cluster").closed)
            assertFalse(terminalDelivered.await(100, TimeUnit.MILLISECONDS))

            releaseObserver.countDown()
            assertTrue(terminalDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(2L, revisions.last())
        } finally {
            releaseObserver.countDown()
            closer.shutdownNow()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun reentrantPublicationUsesMailboxInsteadOfRecursiveCallbacks() {
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val hub = UltraPresentationHub(callbackExecutor)
        val targetRevision = 2_000L
        val completed = CountDownLatch(1)
        val depth = AtomicInteger()
        val maximumDepth = AtomicInteger()
        lateinit var sink: com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
        hub.observe("cluster") { snapshot ->
            val currentDepth = depth.incrementAndGet()
            maximumDepth.accumulateAndGet(currentDepth) { previous, next ->
                maxOf(previous, next)
            }
            try {
                when {
                    snapshot.closed -> Unit
                    snapshot.revision in 1 until targetRevision -> sink.onPresentationEvent(
                        known(CarPlayUiSyncCommandType.TRANSITION_DATA, snapshot.revision),
                    )
                    snapshot.revision == targetRevision -> completed.countDown()
                }
            } finally {
                depth.decrementAndGet()
            }
        }
        sink = hub.sink("cluster")
        try {
            sink.onPresentationEvent(
                known(CarPlayUiSyncCommandType.TRANSITION_START, null),
            )
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(targetRevision, hub.snapshot("cluster").revision)
            assertEquals(1, maximumDepth.get())
        } finally {
            hub.close()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun observerCanSynchronouslyCloseHubWithoutDeadlockOrRecursiveTerminalCallback() {
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val hub = UltraPresentationHub(callbackExecutor)
        val closeReturned = CountDownLatch(1)
        val terminalDelivered = CountDownLatch(1)
        val depth = AtomicInteger()
        val maximumDepth = AtomicInteger()
        hub.observe("cluster") { snapshot ->
            val currentDepth = depth.incrementAndGet()
            maximumDepth.accumulateAndGet(currentDepth) { previous, next ->
                maxOf(previous, next)
            }
            try {
                if (snapshot.revision == 1L && !snapshot.closed) {
                    hub.close()
                    closeReturned.countDown()
                }
                if (snapshot.closed) terminalDelivered.countDown()
            } finally {
                depth.decrementAndGet()
            }
        }
        try {
            hub.sink("cluster").onPresentationEvent(
                known(CarPlayUiSyncCommandType.TRANSITION_START, null),
            )
            assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
            assertTrue(terminalDelivered.await(2, TimeUnit.SECONDS))
            assertTrue(hub.snapshot("cluster").closed)
            assertEquals(1, maximumDepth.get())
        } finally {
            hub.close()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun observerErrorIsIsolatedAndDeactivatesEvenDuringInitialReplay() {
        val hub = directHub()
        val calls = AtomicInteger()

        hub.observe("cluster") {
            calls.incrementAndGet()
            throw AssertionError("renderer failure")
        }
        hub.sink("cluster").onPresentationEvent(
            known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, null),
        )

        assertEquals(1, calls.get())
        assertEquals(1L, hub.deliveryDiagnostics("cluster").observerFailureCount)
    }

    @Test
    fun fullPendingSlotCoalescesToLatestReplayableSnapshotAndRecordsDiagnostic() {
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val hub = UltraPresentationHub(callbackExecutor)
        val replayDelivered = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestDelivered = CountDownLatch(1)
        val revisions = Collections.synchronizedList(mutableListOf<Long>())
        hub.observe("cluster") { snapshot ->
            revisions += snapshot.revision
            when (snapshot.revision) {
                0L -> replayDelivered.countDown()
                1L -> {
                    blocked.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                3L -> latestDelivered.countDown()
            }
        }
        assertTrue(replayDelivered.await(2, TimeUnit.SECONDS))
        val sink = hub.sink("cluster")
        try {
            sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_START, null))
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_DATA, 2L))
            sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_END, 3L))
            release.countDown()

            assertTrue(latestDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 1L, 3L), revisions.toList())
            assertEquals(1L, hub.deliveryDiagnostics("cluster").coalescedSnapshotCount)
        } finally {
            release.countDown()
            hub.close()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun executorRejectionDropsBoundedPendingSnapshotAndReportsIt() {
        val hub = UltraPresentationHub(Executor { throw RejectedExecutionException("closed") })

        hub.observe("cluster", UltraPresentationObserver { })

        val diagnostics = hub.deliveryDiagnostics("cluster")
        assertEquals(1L, diagnostics.executorRejectionCount)
        assertEquals(1L, diagnostics.droppedSnapshotCount)
    }

    @Test
    fun closePublishesTerminalSnapshotAndRejectsEveryFutureRegistrationOrEvent() {
        val hub = directHub()
        val snapshots = Collections.synchronizedList(mutableListOf<UltraPresentationSnapshot>())
        val lease = hub.observe("cluster") { snapshots += it }
        val sink = hub.sink("cluster")
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.GIVE_FOCUS, null))

        hub.close()
        hub.close()

        assertTrue(snapshots.last().closed)
        assertEquals(2L, snapshots.last().revision)
        assertTrue(hub.snapshot("cluster").closed)
        assertThrows(IllegalStateException::class.java) {
            sink.onPresentationEvent(known(CarPlayUiSyncCommandType.REQUEST_FOCUS, null))
        }
        assertThrows(IllegalStateException::class.java) { hub.sink("new-display") }
        assertThrows(IllegalStateException::class.java) {
            hub.observe("cluster", UltraPresentationObserver { })
        }
        lease.close()
    }

    private fun known(
        command: CarPlayUiSyncCommandType,
        data: Any?,
    ): CarPlayUiSyncPresentationEvent =
        CarPlayUiSyncPresentationEvent.KnownCommand(command, data)

    private fun directHub(
        limits: UltraPresentationHubLimits = UltraPresentationHubLimits(),
    ): UltraPresentationHub = UltraPresentationHub(DIRECT_EXECUTOR, limits)

    private companion object {
        val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
