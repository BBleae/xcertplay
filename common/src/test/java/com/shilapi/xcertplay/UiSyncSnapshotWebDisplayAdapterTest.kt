package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSyncSnapshotWebDisplayAdapterTest {
    private class Recorder : WebDisplayViewer.Transport {
        override fun send(message: ByteArray) = Unit
        override fun send(message: String) = Unit
        override fun close() = Unit
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(tasks) { tasks.addLast(command) }
        }

        fun drain() {
            while (true) {
                val next = synchronized(tasks) {
                    if (tasks.isEmpty()) null else tasks.removeFirst()
                } ?: return
                next.run()
            }
        }
    }

    private class HubSource(private val hub: UltraPresentationHub) : UiSyncPresentationSource {
        override fun subscribeWithReplay(
            displayId: String,
            observer: UltraPresentationObserver,
        ): UiSyncPresentationSubscription {
            val lease = hub.observe(displayId, observer)
            return try {
                UiSyncPresentationSubscription(hub.snapshot(displayId), lease)
            } catch (failure: Throwable) {
                lease.close()
                throw failure
            }
        }

        override fun snapshot(displayId: String): UltraPresentationSnapshot =
            hub.snapshot(displayId)
    }

    private fun known(type: CarPlayUiSyncCommandType, data: Any?): CarPlayUiSyncPresentationEvent =
        CarPlayUiSyncPresentationEvent.KnownCommand(type, data)

    private fun layout(id: String): Map<String, Any> = mapOf(
        "layoutID" to id,
        "activeComponents" to mapOf("map" to "center"),
        "paletteID" to "day",
    )

    private fun WebDisplayViewer.drain(): List<Any> = generateSequence { next(0) }.toList()

    private fun clusterFixture(): Pair<WebDisplayChannel, WebDisplayViewer> {
        val channel = WebDisplayChannel(CarPlayWebDisplayFactory.CLUSTER, 1920, 480)
        val viewer = WebDisplayViewer(Recorder(), start = false)
        channel.attach(viewer)
        viewer.drain()
        return channel to viewer
    }

    private fun sessionFixture(channel: WebDisplayChannel): CarPlayWebDisplayFactory.Session {
        val channels = mapOf(CarPlayWebDisplayFactory.CLUSTER to channel)
        val server = WebDisplayServer(
            page = ByteArray(0),
            channels = channels,
            key = "test",
            sendTouch = { true },
            port = 0,
            tls = SSLContext.getDefault(),
        )
        return CarPlayWebDisplayFactory.Session(
            configs = mapOf(
                CarPlayWebDisplayFactory.CLUSTER to
                    CarPlayWebDisplayConfig(true, 1920, 480, preview = false),
            ),
            displays = channels,
            server = server,
        )
    }

    @Test fun completeUpstreamSnapshotsReplaceInsteadOfReappendingTheirHistory() {
        val executor = QueuedExecutor()
        val hub = UltraPresentationHub(executor)
        val sink = hub.sink("cluster-A")
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("one")))
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_DATA, "secret-two"))
        val (channel, viewer) = clusterFixture()

        val adapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(hub),
            "cluster-A",
            channel.acquirePresentationLease(),
        )
        val immediate = viewer.drain().single() as String
        assertTrue(immediate.contains("\"revision\":2"))
        assertEquals(2, "\"known\":".toRegex().findAll(immediate).count())
        assertTrue(immediate.contains("\"layoutID\":\"one\""))
        assertFalse(immediate.contains("secret-two"))

        sink.onPresentationEvent(CarPlayUiSyncPresentationEvent.UnknownCommand(4096, "future-secret"))
        executor.drain()
        val replaced = viewer.drain().single() as String
        assertTrue(replaced.contains("\"revision\":3"))
        assertTrue(replaced.contains("\"type\":4096,\"known\":false"))
        assertFalse(replaced.contains("future-secret"))
        assertEquals(
            "the two replayed events are not appended a second time",
            3,
            "\"known\":".toRegex().findAll(replaced).count(),
        )
        assertEquals(1, "\"revision\":1,\"type\":".toRegex().findAll(replaced).count())
        assertEquals(1, "\"revision\":2,\"type\":".toRegex().findAll(replaced).count())
        assertEquals(1, "\"revision\":3,\"type\":".toRegex().findAll(replaced).count())

        adapter.close()
        assertEquals(WebDisplayChannel.PRESENTATION_RESET, viewer.drain().single())
        val afterClose = WebDisplayViewer(Recorder(), start = false)
        channel.attach(afterClose)
        assertEquals(1, afterClose.drain().size)
        hub.close()
    }

    @Test fun aClosedSourcePublishesOneReplayableTerminalSnapshot() {
        val executor = QueuedExecutor()
        val hub = UltraPresentationHub(executor)
        hub.sink("cluster-A").onPresentationEvent(
            known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, mapOf("layout" to "wide")),
        )
        val (channel, viewer) = clusterFixture()
        val adapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(hub),
            "cluster-A",
            channel.acquirePresentationLease(),
        )
        viewer.drain()

        hub.close()
        executor.drain()
        val terminal = viewer.drain().single() as String
        assertTrue(terminal.contains("\"revision\":2"))
        assertTrue(terminal.contains("\"closed\":true"))

        val lateViewer = WebDisplayViewer(Recorder(), start = false)
        channel.attach(lateViewer)
        val replay = lateViewer.drain()
        assertTrue((replay[0] as String).contains("\"type\":\"state\""))
        assertEquals(terminal, replay[1])

        adapter.close()
    }

    @Test fun anOldObserverLeaseCannotOverwriteItsReplacementOrRevokeIt() {
        val oldExecutor = QueuedExecutor()
        val newExecutor = QueuedExecutor()
        val oldHub = UltraPresentationHub(oldExecutor)
        val newHub = UltraPresentationHub(newExecutor)
        val oldSink = oldHub.sink("old")
        val newSink = newHub.sink("new")
        oldSink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("old-1")))
        newSink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("new-1")))
        val (channel, viewer) = clusterFixture()
        val oldAdapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(oldHub),
            "old",
            channel.acquirePresentationLease(),
        )
        viewer.drain()

        oldSink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("old-late")))
        val newAdapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(newHub),
            "new",
            channel.acquirePresentationLease(),
        )
        val replacement = viewer.drain().single() as String
        assertTrue(replacement.contains("new-1"))

        oldExecutor.drain()
        assertTrue("the stale callback has lost channel ownership", viewer.drain().isEmpty())
        oldAdapter.close()

        newSink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("new-2")))
        newExecutor.drain()
        val latest = viewer.drain().single() as String
        assertTrue(latest.contains("new-2"))
        assertFalse(latest.contains("old-late"))

        newAdapter.close()
        oldHub.close()
        newHub.close()
    }

    @Test fun completeJsonIsUtf8BoundedWhileKeepingTheNewestState() {
        val hub = UltraPresentationHub(Executor(Runnable::run))
        val sink = hub.sink("cluster-A")
        repeat(8) { index ->
            sink.onPresentationEvent(known(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                layout("界".repeat(300) + index),
            ))
        }
        val (channel, viewer) = clusterFixture()
        val maximumBytes = 512
        val adapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(hub),
            "cluster-A",
            channel.acquirePresentationLease(),
            ClusterPresentationLimits(
                maximumRetainedCommands = 8,
                maximumStringChars = 1024,
                maximumJsonPayloadChars = 4096,
                maximumSnapshotJsonBytes = maximumBytes,
            ),
        )

        val json = viewer.drain().single() as String
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= maximumBytes)
        assertTrue(json.contains("\"revision\":8"))
        assertTrue(json.contains("\"adapterDroppedEventCount\":"))
        assertTrue(json.contains("snapshotEncodedSizeLimit") || json.contains("\"adapterDroppedEventCount\":7"))

        adapter.close()
        hub.close()
    }

    @Test fun onlyStrictLayoutAndPaletteStructureIsProjected() {
        val hub = UltraPresentationHub(Executor(Runnable::run))
        val sink = hub.sink("cluster-A")
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("wide")))
        sink.onPresentationEvent(known(
            CarPlayUiSyncCommandType.PALETTE_CHANGE,
            mapOf("layoutIdentifier" to "wide", "paletteIdentifier" to "night"),
        ))
        sink.onPresentationEvent(known(
            CarPlayUiSyncCommandType.LAYOUT_CHANGE,
            layout("bad") + ("privateToken" to "must-not-leak"),
        ))
        sink.onPresentationEvent(known(
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            mapOf("vehicleSpeed" to 123, "opaque" to byteArrayOf(9, 8, 7)),
        ))
        sink.onPresentationEvent(CarPlayUiSyncPresentationEvent.UnknownCommand(
            0x7fff, "unknown-private-data",
        ))
        val (channel, viewer) = clusterFixture()
        val adapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(hub), "cluster-A", channel.acquirePresentationLease(),
        )
        val json = viewer.drain().single() as String
        assertTrue(json.contains("\"layoutID\":\"wide\""))
        assertTrue(json.contains("\"paletteIdentifier\":\"night\""))
        assertFalse(json.contains("must-not-leak"))
        assertFalse(json.contains("vehicleSpeed"))
        assertFalse(json.contains("unknown-private-data"))
        assertTrue(json.contains("notWebDisplayAllowlisted"))
        adapter.close()
        hub.close()
    }

    @Test fun exactDisplaySubscriptionIgnoresAnotherDisplayAndMissingWebDisplayIsNoOp() {
        val executor = QueuedExecutor()
        val hub = UltraPresentationHub(executor)
        val (channel, viewer) = clusterFixture()
        val adapter = UiSyncSnapshotWebDisplayAdapter(
            HubSource(hub), "cluster-A", channel.acquirePresentationLease(),
        )
        viewer.drain()
        hub.sink("cluster-B").onPresentationEvent(
            known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("foreign")),
        )
        executor.drain()
        assertTrue(viewer.drain().isEmpty())
        adapter.close()

        val noWebDisplay = CarPlayWebDisplayFactory.Session(
            configs = emptyMap(), displays = emptyMap(),
            server = WebDisplayServer(
                page = ByteArray(0), channels = emptyMap(), key = "test",
                sendTouch = { true }, port = 0, tls = SSLContext.getDefault(),
            ),
        )
        val source = object : UiSyncPresentationSource {
            override fun subscribeWithReplay(displayId: String, observer: UltraPresentationObserver):
                UiSyncPresentationSubscription = error("missing WebDisplay subscribed")
            override fun snapshot(displayId: String): UltraPresentationSnapshot =
                error("missing WebDisplay read a snapshot")
        }
        noWebDisplay.attachClusterPresentation(source, "cluster-A").close()
        noWebDisplay.close()
        hub.close()
    }

    @Test fun failedDetachRetainsLeaseForRetryButRevokesPublicationImmediately() {
        val executor = QueuedExecutor()
        val hub = UltraPresentationHub(executor)
        val delegate = HubSource(hub)
        val closeCalls = java.util.concurrent.atomic.AtomicInteger()
        val source = object : UiSyncPresentationSource {
            override fun subscribeWithReplay(
                displayId: String, observer: UltraPresentationObserver,
            ): UiSyncPresentationSubscription {
                val created = delegate.subscribeWithReplay(displayId, observer)
                return UiSyncPresentationSubscription(created.initialSnapshot, java.io.Closeable {
                    if (closeCalls.incrementAndGet() == 1) {
                        throw IllegalStateException("detach failed once")
                    }
                    created.close()
                })
            }
            override fun snapshot(displayId: String): UltraPresentationSnapshot =
                delegate.snapshot(displayId)
        }
        val (channel, viewer) = clusterFixture()
        val adapter = UiSyncSnapshotWebDisplayAdapter(
            source, "cluster-A", channel.acquirePresentationLease(),
        )
        viewer.drain()
        assertThrows(IllegalStateException::class.java) { adapter.close() }
        assertEquals(WebDisplayChannel.PRESENTATION_RESET, viewer.drain().single())
        hub.sink("cluster-A").onPresentationEvent(
            known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layout("late")),
        )
        executor.drain()
        assertTrue(viewer.drain().isEmpty())
        adapter.close()
        assertEquals(2, closeCalls.get())
        hub.close()
    }

    @Test fun reentrantSessionCloseRejectsAndReclaimsTheInFlightCandidate() {
        val executor = QueuedExecutor()
        val hub = UltraPresentationHub(executor)
        val delegate = HubSource(hub)
        val sink = hub.sink("cluster-A")
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, "initial"))
        val (channel, viewer) = clusterFixture()
        val session = sessionFixture(channel)
        val detached = AtomicBoolean(false)
        val source = object : UiSyncPresentationSource {
            override fun subscribeWithReplay(
                displayId: String,
                observer: UltraPresentationObserver,
            ): UiSyncPresentationSubscription {
                session.close()
                val actual = delegate.subscribeWithReplay(displayId, observer)
                return UiSyncPresentationSubscription(
                    actual.initialSnapshot,
                    java.io.Closeable {
                        actual.close()
                        detached.set(true)
                    },
                )
            }

            override fun snapshot(displayId: String): UltraPresentationSnapshot =
                delegate.snapshot(displayId)
        }

        val binding = session.attachClusterPresentation(source, "cluster-A")

        assertTrue(detached.get())
        assertEquals(WebDisplayChannel.PRESENTATION_RESET, viewer.drain().single())
        sink.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, "late"))
        executor.drain()
        assertTrue(viewer.drain().isEmpty())
        binding.close()
        hub.close()
    }
}
