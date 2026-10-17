package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPhysicalDisplayPanelsTest {
    private val mainStream = AirPlayPhysicalVideoStream(
        uuid = AirPlayInfoPlist.MAIN_UUID,
        zIndex = 0,
        originXPixels = 0,
        originYPixels = 0,
    )
    private val clusterStream = AirPlayPhysicalVideoStream(
        uuid = AirPlayInfoPlist.ALT_UUID,
        zIndex = 1,
        originXPixels = 12,
        originYPixels = 4,
    )

    @Test
    fun defaultInfoDoesNotInferPhysicalPanelsFromLegacyDisplays() {
        val config = config().copy(cluster = AirPlayDisplayConfig(960, 480))
        val baseline = AirPlayInfoPlist.build(config)

        assertEquals(2, (baseline["displays"] as List<*>).size)
        assertFalse(baseline.containsKey("displayPanels"))
        assertThrows(IllegalArgumentException::class.java) {
            config.copy(displayPanels = panels())
        }
    }

    @Test
    fun optInWritesOnlyConfirmedPhysicalPanelAndPluginFields() {
        val config = config()
        val baseline = AirPlayInfoPlist.build(config)
        val withPanel = AirPlayInfoPlist.build(
            config.copy(displayPanels = panels(panel(mainStream, "main-panel-plugin"))),
        )
        val decoded = BplistCodec.decode(BplistCodec.encode(withPanel)) as Map<*, *>

        assertEquals(baseline["displays"], withPanel["displays"])
        assertTrue(BplistCodec.encode(baseline).contentEquals(BplistCodec.encode(withPanel - "displayPanels")))
        assertTrue(BplistCodec.encode(baseline["displays"]).contentEquals(BplistCodec.encode(decoded["displays"])))
        val physical = (decoded["displayPanels"] as List<*>).single() as Map<*, *>
        assertEquals(
            setOf("widthPixels", "heightPixels", "widthPhysical", "heightPhysical", "initialVideoStreams", "displayPlugins"),
            physical.keys,
        )
        assertEquals(1280L, physical["widthPixels"])
        assertEquals(720L, physical["heightPixels"])
        assertEquals(200.0, physical["widthPhysical"])
        assertEquals(113.0, physical["heightPhysical"])
        val initial = (physical["initialVideoStreams"] as List<*>).single() as Map<*, *>
        assertEquals(
            mapOf("uuid" to AirPlayInfoPlist.MAIN_UUID, "zIndex" to 0L, "originXPixels" to 0L, "originYPixels" to 0L),
            initial,
        )
        val plugin = (physical["displayPlugins"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("uid", "managedVideoStreams"), plugin.keys)
        assertEquals("main-panel-plugin", plugin["uid"])
        assertEquals(listOf(initial), plugin["managedVideoStreams"])
    }

    @Test
    fun explicitPhysicalScreenPropertiesSurviveBinaryPlistRoundTrip() {
        val withPanel = AirPlayInfoPlist.build(config().copy(displayPanels = panels(
            panel(mainStream, "main-panel-plugin").copy(
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
            ),
        )))
        val decoded = BplistCodec.decode(BplistCodec.encode(withPanel)) as Map<*, *>
        val physical = (decoded["displayPanels"] as List<*>).single() as Map<*, *>

        assertEquals(listOf("CarPlay"), physical["properties"])
    }

    @Test
    fun ddpOnlyMainPanelCannotQualifyAsCarPlayMainForUiSync() {
        val main = config().main
        val ddpOnly = panels(panel(mainStream, "main-panel-plugin"))
        val carPlayMain = panels(panel(mainStream, "main-panel-plugin").copy(
            properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
        ))

        assertFalse(ddpOnly.hasStandaloneMainDdpPanel(main, null))
        assertTrue(carPlayMain.hasStandaloneMainDdpPanel(main, null))
    }

    @Test
    fun standalonePanelCannotDisagreeWithLegacyPixelOrPhysicalGeometry() {
        val baseline = config()
        val standalone = panel(mainStream, "main-panel-plugin")
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(baseline.copy(displayPanels = panels(
                standalone.copy(widthPhysical = 312.0),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(baseline.copy(displayPanels = panels(
                standalone.copy(widthPixels = 1200),
            )))
        }
    }

    @Test
    fun mainAndClusterPanelsBindTheExactLegacyDisplayUuids() {
        val config = config().copy(
            cluster = AirPlayDisplayConfig(960, 480),
            displayPanels = panels(
                panel(mainStream, "main-panel-plugin"),
                panel(clusterStream, "cluster-panel-plugin").copy(
                    widthPixels = 960,
                    heightPixels = 480,
                    widthPhysical = 200.0,
                    heightPhysical = 100.0,
                ),
            ),
        )
        val info = AirPlayInfoPlist.build(config, setOf("altScreen", "uiContext"))
        val displays = info["displays"] as List<*>
        val panels = info["displayPanels"] as List<*>

        assertEquals(2, displays.size)
        assertEquals(2, panels.size)
        for (index in 0..1) {
            val display = displays[index] as Map<*, *>
            val panel = panels[index] as Map<*, *>
            val initial = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
            val plugin = (panel["displayPlugins"] as List<*>).single() as Map<*, *>
            val managed = (plugin["managedVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(display["uuid"], initial["uuid"])
            assertEquals(display["uuid"], managed["uuid"])
            assertFalse(initial["uuid"] == display["type"].toString())
        }
        assertEquals("main-panel-plugin", config.displayPanels?.pluginUidManagingLegacyStream(
            AirPlayInfoPlist.MAIN_UUID,
        ))
        assertEquals("cluster-panel-plugin", config.displayPanels?.pluginUidManagingLegacyStream(
            AirPlayInfoPlist.ALT_UUID,
        ))
        assertEquals(null, config.displayPanels?.pluginUidManagingLegacyStream("unknown"))
    }

    @Test
    fun malformedPanelAndPluginMetadataIsRejected() {
        val config = config()
        fun build(panel: AirPlayPhysicalDisplayPanel) {
            AirPlayInfoPlist.build(config.copy(displayPanels = panels(panel)))
        }

        assertThrows(IllegalArgumentException::class.java) {
            build(panel(mainStream, "plugin").copy(displayPlugins = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            build(panel(mainStream, "plugin").copy(initialVideoStreams = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) { build(panel(mainStream, "\n")) }
        assertThrows(IllegalArgumentException::class.java) {
            build(panel(mainStream, "plugin").copy(widthPhysical = Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            build(panel(mainStream, "plugin").copy(widthPixels = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            build(panel(mainStream.copy(originXPixels = Int.MAX_VALUE), "plugin"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            build(panel(mainStream, "plugin").copy(
                displayPlugins = listOf(
                    AirPlayPhysicalDisplayPlugin("plugin", listOf(mainStream)),
                    AirPlayPhysicalDisplayPlugin("plugin", listOf(mainStream)),
                ),
            ))
        }
        for (invalid in listOf(
            listOf("carplay"),
            listOf("CarPlay\n"),
            listOf("CarPlay", "CarPlay"),
            listOf("CarPlay", "Map", "Instrument Cluster", "Other"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                build(panel(mainStream, "plugin").copy(properties = invalid))
            }
        }
    }

    @Test
    fun streamReferencesMustMatchAdvertisedLegacyDisplaysAndPanelInitialStreams() {
        val invalidUuid = mainStream.copy(uuid = "00000000-0000-0000-0000-000000000110")
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(config().copy(displayPanels = panels(panel(invalidUuid, "plugin"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(config().copy(displayPanels = panels(panel(clusterStream, "plugin"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(config().copy(
                cluster = AirPlayDisplayConfig(960, 480),
                displayPanels = panels(
                    panel(mainStream, "plugin").copy(
                        displayPlugins = listOf(AirPlayPhysicalDisplayPlugin("plugin", listOf(clusterStream))),
                    ),
                ),
            ))
        }
        val duplicate = listOf(panel(mainStream, "plugin-a"), panel(mainStream, "plugin-b"))
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayInfoPlist.build(config().copy(displayPanels = AirPlayPhysicalDisplayPanels(duplicate)))
        }
        val scopedCluster = config().copy(
            cluster = AirPlayDisplayConfig(960, 480),
            displayPanels = panels(panel(clusterStream, "cluster")),
        )
        assertFalse(AirPlayInfoPlist.build(scopedCluster, emptySet()).containsKey("displayPanels"))
        assertTrue(AirPlayInfoPlist.build(scopedCluster, setOf("altScreen", "uiContext")).containsKey("displayPanels"))
    }

    @Test
    fun negotiatedMainOnlyScopeProjectsOutClusterPanelAndStreams() {
        val mainAndCluster = config().copy(
            cluster = AirPlayDisplayConfig(960, 480),
            displayPanels = panels(
                panel(mainStream, "main"),
                panel(clusterStream, "cluster"),
            ),
        )
        val mainOnlyInfo = AirPlayInfoPlist.build(mainAndCluster, emptySet())
        val legacy = (mainOnlyInfo["displays"] as List<*>).single() as Map<*, *>
        val physical = (mainOnlyInfo["displayPanels"] as List<*>).single() as Map<*, *>
        val initial = (physical["initialVideoStreams"] as List<*>).single() as Map<*, *>
        val plugin = (physical["displayPlugins"] as List<*>).single() as Map<*, *>
        val managed = (plugin["managedVideoStreams"] as List<*>).single() as Map<*, *>

        assertEquals(AirPlayInfoPlist.MAIN_UUID, legacy["uuid"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, initial["uuid"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, managed["uuid"])
        assertEquals("main", plugin["uid"])

        val mixedPanel = panel(mainStream, "main").copy(
            // A composite physical panel is not constrained to either single stream's scale.
            widthPhysical = 312.0,
            heightPhysical = 175.5,
            initialVideoStreams = listOf(mainStream, clusterStream),
            displayPlugins = listOf(
                AirPlayPhysicalDisplayPlugin("main", listOf(mainStream)),
                AirPlayPhysicalDisplayPlugin("cluster", listOf(clusterStream)),
            ),
        )
        val projected = AirPlayInfoPlist.build(mainAndCluster.copy(displayPanels = panels(mixedPanel)), emptySet())
        val projectedPanel = (projected["displayPanels"] as List<*>).single() as Map<*, *>
        assertEquals(1, (projectedPanel["initialVideoStreams"] as List<*>).size)
        assertEquals(1, (projectedPanel["displayPlugins"] as List<*>).size)
    }

    @Test
    fun callerMutationsCannotChangeFrozenRepeatedInfoOrCopiedConfig() {
        val initial = mutableListOf(mainStream)
        val managed = mutableListOf(mainStream)
        val properties = mutableListOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY)
        val plugins = mutableListOf(AirPlayPhysicalDisplayPlugin("main", managed))
        val outer = mutableListOf(panel(mainStream, "ignored").copy(
            initialVideoStreams = initial,
            displayPlugins = plugins,
            properties = properties,
        ))
        val config = config().copy(displayPanels = AirPlayPhysicalDisplayPanels(outer))
        val before = BplistCodec.encode(AirPlayInfoPlist.build(config))

        initial.clear()
        managed.clear()
        properties.clear()
        plugins.clear()
        outer.clear()

        assertTrue(before.contentEquals(BplistCodec.encode(AirPlayInfoPlist.build(config))))
        assertTrue(before.contentEquals(BplistCodec.encode(AirPlayInfoPlist.build(config.copy()))))
    }

    private fun config() = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(1280, 720),
    )

    private fun panel(stream: AirPlayPhysicalVideoStream, uid: String) = AirPlayPhysicalDisplayPanel(
        widthPixels = 1280,
        heightPixels = 720,
        widthPhysical = 200.0,
        heightPhysical = 113.0,
        initialVideoStreams = listOf(stream),
        displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(uid, listOf(stream))),
    )

    private fun panels(vararg items: AirPlayPhysicalDisplayPanel) = AirPlayPhysicalDisplayPanels(items.toList())
}
