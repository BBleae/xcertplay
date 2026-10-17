package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterPresentationCoordinatorTest {
    private fun known(
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
    ) = CarPlayUiSyncPresentationEvent.KnownCommand(command, data)

    private fun WebDisplayViewer.drain(): List<Any> = generateSequence { next(0) }.toList()

    private class Recorder : WebDisplayViewer.Transport {
        override fun send(message: ByteArray) = Unit
        override fun send(message: String) = Unit
        override fun close() = Unit
    }

    @Test fun observersReplayCurrentStateAndDetachWithoutOwningTheCoordinator() {
        val coordinator = ClusterPresentationCoordinator(
            ClusterPresentationLimits(maximumRetainedCommands = 2),
        )
        val first = mutableListOf<ClusterPresentationSnapshot>()
        val subscription = coordinator.observe(first::add)
        assertEquals(ClusterPresentationSnapshot(0, emptyList()), first.single())

        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, mapOf("layout" to 1L)))
        coordinator.onPresentationEvent(CarPlayUiSyncPresentationEvent.UnknownCommand(2048, "future"))
        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, mapOf("layout" to 2L)))
        assertEquals(3L, first.last().revision)
        assertEquals(listOf(2048, CarPlayUiSyncCommandType.LAYOUT_CHANGE.wireValue), first.last().commands.map { it.type })
        assertEquals("layoutChange", first.last().commands.last().name)

        subscription.close()
        subscription.close()
        val receivedBeforeDetach = first.size
        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE, "sport"))
        assertEquals(receivedBeforeDetach, first.size)

        val replayed = mutableListOf<ClusterPresentationSnapshot>()
        coordinator.observe(replayed::add).use {
            assertEquals(4L, replayed.single().revision)
            assertEquals(
                listOf(CarPlayUiSyncCommandType.LAYOUT_CHANGE.wireValue, CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE.wireValue),
                replayed.single().commands.map { command -> command.type },
            )
        }
    }

    @Test fun checkedReplayFailureDoesNotLeaveObserverRegistered() {
        val coordinator = ClusterPresentationCoordinator()
        var invocations = 0
        val observer = ClusterPresentationObserver {
            invocations += 1
            throw IOException("observer unavailable")
        }

        assertThrows(IOException::class.java) { coordinator.observe(observer) }
        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.REQUEST_LAYOUT))

        assertEquals(1, invocations)
    }

    @Test fun unknownCommandsAndPayloadsAreEscapedAndBounded() {
        val coordinator = ClusterPresentationCoordinator(
            ClusterPresentationLimits(
                maximumCollectionEntries = 2,
                maximumStringChars = 12,
                maximumBinaryBytes = 3,
                maximumJsonPayloadChars = 1024,
            ),
        )
        coordinator.onPresentationEvent(
            CarPlayUiSyncPresentationEvent.UnknownCommand(
                4096,
                linkedMapOf(
                    "quote\"\n" to "line\\break\n\u2028overflow-text",
                    "bytes" to byteArrayOf(0, 1, 2, 3, 4),
                    "omitted-third" to true,
                ),
            ),
        )

        val snapshot = coordinator.snapshot()
        val command = snapshot.commands.single()
        assertEquals(4096, command.type)
        assertEquals(null, command.name)
        assertTrue(command.truncated)
        val json = ClusterPresentationJson.encode(snapshot)
        assertTrue(json.contains("\"known\":false"))
        assertTrue(json.contains("quote\\\"\\n"))
        assertTrue(json.contains("line\\\\break\\n\\u2028"))
        assertTrue(json.contains("\"base64\":\"AAEC\""))
        assertTrue(json.contains("\"byteLength\":5"))
        assertFalse(json.contains("omitted-third"))
        assertTrue("bounded snapshot JSON", json.length < 2048)
    }

    @Test fun oversizedAndCyclicValuesBecomeExplicitOmissions() {
        val sizeBounded = ClusterPresentationCoordinator(
            ClusterPresentationLimits(
                maximumStringChars = 256,
                maximumJsonPayloadChars = 48,
            ),
        )
        sizeBounded.onPresentationEvent(
            known(CarPlayUiSyncCommandType.METADATA_TRANSFER, mapOf("metadata" to "x".repeat(200))),
        )
        val sizeJson = ClusterPresentationJson.encode(sizeBounded.snapshot())
        assertTrue(sizeJson.contains("\"truncated\":true"))
        assertTrue(sizeJson.contains("\"omitted\":\"encodedSizeLimit\""))
        assertFalse(sizeJson.contains("x".repeat(64)))

        val cyclic = mutableListOf<Any?>()
        cyclic.add(cyclic)
        val cycleBounded = ClusterPresentationCoordinator()
        cycleBounded.onPresentationEvent(CarPlayUiSyncPresentationEvent.UnknownCommand(6000, cyclic))
        assertTrue(ClusterPresentationJson.encode(cycleBounded.snapshot()).contains("\"omitted\":\"cycle\""))
    }

    @Test fun webDisplayAdapterDeliversAndReplaysOnlyOnTheClusterChannel() {
        val coordinator = ClusterPresentationCoordinator()
        val main = WebDisplayChannel(CarPlayWebDisplayFactory.MAIN, 1280, 720)
        val cluster = WebDisplayChannel(CarPlayWebDisplayFactory.CLUSTER, 1920, 480)
        val mainViewer = WebDisplayViewer(Recorder(), start = false)
        val clusterViewer = WebDisplayViewer(Recorder(), start = false)
        main.attach(mainViewer)
        cluster.attach(clusterViewer)
        mainViewer.drain()
        clusterViewer.drain()

        val adapter = ClusterPresentationWebDisplayAdapter(
            coordinator,
            cluster.acquirePresentationLease(),
        )
        assertTrue(mainViewer.drain().isEmpty())
        assertTrue((clusterViewer.drain().single() as String).contains("\"revision\":0"))

        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_START, "first"))
        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_DATA, "second"))
        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_DATA, "third"))
        assertTrue(mainViewer.drain().isEmpty())
        val coalesced = clusterViewer.drain()
        assertEquals("complete snapshots supersede queued presentation messages", 1, coalesced.size)
        val latest = coalesced.single() as String
        assertTrue(latest.contains("\"revision\":3"))
        assertEquals(2, "\"type\":2".toRegex().findAll(latest).count())

        val lateViewer = WebDisplayViewer(Recorder(), start = false)
        cluster.attach(lateViewer)
        val replay = lateViewer.drain()
        assertTrue((replay[0] as String).contains("\"type\":\"state\""))
        assertTrue((replay[1] as String).contains("\"type\":\"clusterPresentation\""))
        assertTrue((replay[1] as String).contains("\"revision\":3"))

        adapter.close()
        coordinator.onPresentationEvent(known(CarPlayUiSyncCommandType.TRANSITION_END))
        assertEquals(WebDisplayChannel.PRESENTATION_RESET, clusterViewer.drain().single())
    }

    @Test fun legacyEventSinkAlsoHonorsTheCompleteWebMessageBound() {
        val maximumBytes = 512
        val coordinator = ClusterPresentationCoordinator(
            ClusterPresentationLimits(
                maximumRetainedCommands = 4,
                maximumStringChars = 1024,
                maximumJsonPayloadChars = 4096,
                maximumSnapshotJsonBytes = maximumBytes,
            ),
        )
        val cluster = WebDisplayChannel(CarPlayWebDisplayFactory.CLUSTER, 1920, 480)
        val viewer = WebDisplayViewer(Recorder(), start = false)
        cluster.attach(viewer)
        viewer.drain()
        val adapter = ClusterPresentationWebDisplayAdapter(
            coordinator,
            cluster.acquirePresentationLease(),
        )
        viewer.drain()

        repeat(4) { index ->
            coordinator.onPresentationEvent(
                CarPlayUiSyncPresentationEvent.UnknownCommand(
                    5_000 + index,
                    mapOf("value" to "界".repeat(300)),
                ),
            )
        }
        val json = viewer.drain().single() as String
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= maximumBytes)
        assertTrue(json.contains("\"revision\":4"))
        assertTrue(json.contains("\"adapterDroppedEventCount\":"))

        adapter.close()
    }

    @Test fun bundledPageExposesAStableCustomPresentationHookWithoutChangingVideoHandling() {
        val candidates = listOf(
            File("common/src/main/assets/carplay-display.html"),
            File("src/main/assets/carplay-display.html"),
        )
        val html = candidates.first { it.isFile }.readText()
        assertTrue(html.contains("window.carPlayClusterPresentation={version:1,revision:0,commands:[]}"))
        assertTrue(html.contains("new CustomEvent('carplay-cluster-presentation'"))
        assertTrue(html.contains("new CustomEvent('carplay-cluster-presentation-reset'"))
        assertTrue(html.contains("case 1:videoOut.configure"))
        assertTrue(html.contains("case 2:videoOut.decode"))
        assertTrue(html.contains("case 3:audio.play"))
        assertTrue(html.contains("case 4:audio.stop"))
    }
}
