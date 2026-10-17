package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDisplayTopologyTest {
    @Test
    fun parsesMultipleDisplaysAndKeepsScreenIdAndUidNamespacesDistinct() {
        val opaqueStream = linkedMapOf<String, Any?>(
            "uuid" to STREAM_UUID,
            "vendorCounter" to BigInteger.ONE.shiftLeft(80),
            "vendorData" to byteArrayOf(1, 2),
        )
        val topology = CarPlayDisplayTopology.parse(
            listOf(
                display(DISPLAY_1, mapOf("ScreenID" to "cluster"), streams = listOf(opaqueStream)),
                display(DISPLAY_2, mapOf("UID" to "cluster"), streams = listOf("opaque-stream")),
            ),
        )

        assertEquals(2, topology.displays.size)
        assertEquals(BigInteger.ONE.shiftLeft(80), (topology.displays[0].videoStreams[0] as Map<*, *>)["vendorCounter"])
        assertTrue(topology.containsPanel(CarPlayDisplayPanelIdentity.ScreenId("cluster")))
        assertTrue(topology.containsPanel(CarPlayDisplayPanelIdentity.Uid("cluster")))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(
                listOf(display(DISPLAY_1, mapOf("ScreenID" to "x", "UID" to "x"))),
            )
        }
    }

    @Test
    fun validatesOnlyConfirmedUuidShapeInsideOtherwiseOpaqueStreamPayload() {
        val topology = CarPlayDisplayTopology.parse(
            listOf(display(DISPLAY_1, mapOf("UID" to "panel"), streams = listOf(mapOf("vendor" to 7L)))),
        )
        assertEquals(7L, (topology.displays.single().videoStreams.single() as Map<*, *>)["vendor"])

        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(
                listOf(display(DISPLAY_1, mapOf("UID" to "panel"), streams = listOf(mapOf("uuid" to "bad")))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(listOf(display("1-1-1-1-1", mapOf("UID" to "panel"))))
        }
    }

    @Test
    fun rejectsGeometryOverflowWithoutAssigningOpaquePayloadSemantics() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(
                listOf(display(DISPLAY_1, mapOf("UID" to "panel")) + ("widthPixels" to 0)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(
                listOf(display(DISPLAY_1, mapOf("UID" to "panel")) + ("originXPixels" to 1_000_001)),
            )
        }

        val largeOpaqueInteger = BigInteger.ONE.shiftLeft(256)
        val topology = CarPlayDisplayTopology.parse(
            listOf(display(DISPLAY_1, mapOf("UID" to "panel"), streams = listOf(largeOpaqueInteger))),
        )
        assertEquals(largeOpaqueInteger, topology.displays.single().videoStreams.single())
    }

    @Test
    fun inputAndEveryPublicOpaqueSnapshotAreDefensivelyCopied() {
        val propertyBytes = byteArrayOf(1, 2, 3)
        val streamBytes = byteArrayOf(4, 5, 6)
        val pluginBytes = byteArrayOf(7, 8, 9)
        val raw = mutableListOf(
            display(
                DISPLAY_1,
                mapOf("UID" to "panel", "vendorPanel" to byteArrayOf(10, 11)),
                streams = listOf(mapOf("uuid" to STREAM_UUID, "data" to streamBytes)),
                properties = mapOf("blob" to propertyBytes),
                plugins = mapOf("vendorPlugin" to pluginBytes),
            ),
        )
        val topology = CarPlayDisplayTopology.parse(raw)

        propertyBytes[0] = 99
        streamBytes[0] = 99
        pluginBytes[0] = 99
        raw.clear()
        val first = topology.displays
        val second = topology.displays
        ((first.single().videoStreams.single() as Map<*, *>)["data"] as ByteArray)[1] = 99

        assertNotSame(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), second.single().properties["blob"] as ByteArray)
        assertArrayEquals(
            byteArrayOf(4, 5, 6),
            (second.single().videoStreams.single() as Map<*, *>)["data"] as ByteArray,
        )
        assertArrayEquals(
            byteArrayOf(7, 8, 9),
            (second.single().displayPlugins as Map<*, *>)["vendorPlugin"] as ByteArray,
        )
    }

    @Test
    fun failedPanelOrPluginUpdateKeepsOldOpaqueSnapshotAndRevision() {
        val holder = holder()
        val bytes = byteArrayOf(1, 2)
        val accepted = holder.updateDisplayPanels(
            mapOf(
                "displayPanels" to listOf(
                    mapOf("UID" to "panel", "vendorPayload" to mapOf("blob" to bytes)),
                ),
            ),
        )
        bytes[0] = 9
        assertEquals(1L, accepted.revision)

        assertThrows(IllegalArgumentException::class.java) {
            holder.updateDisplayPanels(
                mapOf(
                    "displayPanels" to listOf(
                        mapOf("UID" to "panel", "vendorPayload" to 8L),
                        mapOf("UID" to "missing", "vendorPayload" to 9L),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            holder.updateDisplayPlugins(
                DISPLAY_2,
                mapOf("displayPanels" to listOf(mapOf("UID" to "panel", "displayPlugins" to "opaque"))),
            )
        }

        val after = holder.snapshot()
        assertEquals(1L, after.revision)
        val payload = after.panelUpdates.values.single()["vendorPayload"] as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2), payload["blob"] as ByteArray)
        assertTrue(after.pluginUpdatesByDisplay.isEmpty())
    }

    @Test
    fun pluginUpdateRequiresKnownDisplayAndPanelButKeepsPluginPayloadOpaque() {
        val topology = CarPlayDisplayTopology.parse(
            listOf(
                display(DISPLAY_1, mapOf("UID" to "driver")),
                display(DISPLAY_2, mapOf("UID" to "passenger")),
            ),
        )
        val holder = CarPlayDisplayTopologyHolder(topology)

        assertThrows(IllegalArgumentException::class.java) {
            holder.updateDisplayPlugins(
                DISPLAY_1,
                mapOf("displayPanels" to listOf(mapOf("UID" to "passenger", "displayPlugins" to listOf("x")))),
            )
        }
        val accepted = holder.updateDisplayPlugins(
            DISPLAY_1,
            mapOf(
                "displayPanels" to listOf(
                    mapOf("UID" to "driver", "displayPlugins" to mapOf("arbitrary" to BigInteger.TEN)),
                ),
            ),
        )
        val panel = accepted.pluginUpdatesByDisplay.values.single().values.single()
        assertEquals(BigInteger.TEN, (panel["displayPlugins"] as Map<*, *>)["arbitrary"])
    }

    @Test
    fun concurrentSnapshotsNeverObservePartiallyPublishedOpaqueUpdate() {
        val holder = holder()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writers = (0 until 2).map { writer ->
            pool.submit {
                try {
                    start.await()
                    repeat(100) { iteration ->
                        holder.updateDisplayPanels(
                            mapOf(
                                "displayPanels" to listOf(
                                    mapOf("UID" to "panel", "opaqueGeneration" to listOf(writer, iteration)),
                                ),
                            ),
                        )
                    }
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }
        val reader = pool.submit {
            try {
                start.await()
                while (writers.any { !it.isDone }) {
                    val snapshot = holder.snapshot()
                    assertTrue(snapshot.revision in 0L..200L)
                    assertTrue(
                        snapshot.panelUpdates.isEmpty() ||
                            snapshot.panelUpdates.values.single().containsKey("opaqueGeneration"),
                    )
                }
            } catch (error: Throwable) {
                failures += error
            }
        }
        start.countDown()
        writers.forEach { it.get(5, TimeUnit.SECONDS) }
        reader.get(5, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(200L, holder.snapshot().revision)
    }

    @Test
    fun recursiveAndOverBudgetOpaqueValuesAreRejectedBeforePublication() {
        val cyclic = mutableListOf<Any?>()
        cyclic.add(cyclic)
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(
                listOf(display(DISPLAY_1, mapOf("UID" to "panel"), properties = mapOf("cycle" to cyclic))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayDisplayTopology.parse(
                listOf(display(DISPLAY_1, mapOf("UID" to "panel"), streams = listOf(ByteArray(5)))),
                CarPlayDisplayTopologyLimits(maxDataBytes = 4),
            )
        }
    }

    private fun holder(): CarPlayDisplayTopologyHolder = CarPlayDisplayTopologyHolder(
        CarPlayDisplayTopology.parse(listOf(display(DISPLAY_1, mapOf("UID" to "panel")))),
    )

    private fun display(
        uuid: String,
        panel: Map<String, Any?>,
        streams: List<Any?> = emptyList(),
        properties: Map<String, Any?> = emptyMap(),
        plugins: Any = emptyList<Any?>(),
    ): Map<String, Any?> = linkedMapOf(
        "uuid" to uuid,
        "widthPixels" to 1920,
        "heightPixels" to 720,
        "originXPixels" to 0,
        "originYPixels" to 0,
        "widthPhysical" to 310.5,
        "heightPhysical" to 116.0,
        "properties" to properties,
        "initialVideoStreams" to streams,
        "videoStreams" to streams,
        "managedVideoStreams" to streams,
        "zIndex" to 0,
        "displayPanels" to listOf(panel),
        "displayPlugins" to plugins,
        "automakerInputStreams" to listOf(mapOf("vendorInput" to BigInteger.ONE.shiftLeft(96))),
    )

    companion object {
        private const val DISPLAY_1 = "00000000-0000-4000-8000-000000000001"
        private const val DISPLAY_2 = "00000000-0000-4000-8000-000000000002"
        private const val STREAM_UUID = "00000000-0000-4000-8000-000000000101"
    }
}
