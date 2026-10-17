package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayViewAreaControlTest {
    private fun config() = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
        main = AirPlayDisplayConfig(1280, 1280, viewAreas = listOf(
            AirPlayViewArea(1280, 720), AirPlayViewArea(720, 1280),
        )),
    )

    @Test fun declaresInitialIndexAndAdjacentAreas() {
        val display = (AirPlayInfoPlist.build(config().copy(main = config().main.copy(initialViewArea = 1)))["displays"] as List<*>).first() as Map<*, *>
        assertEquals(1, display["initialViewArea"])
        assertEquals(listOf(0), display["adjacentViewAreas"])
        assertEquals(2, (display["viewAreas"] as List<*>).size)
        val params = AirPlayInfoPlist.viewAreaCommand(1, 2)["params"] as Map<*, *>
        assertEquals(300, params["animationDurationMillis"])
        assertEquals(listOf(0), params["adjacentViewAreas"])
        assertEquals(AirPlayInfoPlist.MAIN_UUID, params["uuid"])
    }

    @Test fun refusesUnnegotiatedSwitchAndCoalescesPendingCommands() {
        val sent = mutableListOf<Map<String, Any?>>()
        var writable = false
        val control = AirPlayDisplayControlFactory.create(config(), { if (writable) { sent += it; true } else false }, {})
        assertFalse(control.showViewArea(1))
        control.negotiate(listOf("viewAreas"), true)
        assertTrue(control.acceptsViewAreas())
        assertFalse(control.showViewArea(2))
        assertTrue(control.showViewArea(1))
        assertTrue(control.showViewArea(0))
        writable = true
        control.responseStarted()
        control.flush()
        assertTrue(sent.isEmpty())
        control.responseSent()
        assertEquals(1, sent.size)
        assertEquals(0, (sent.single()["params"] as Map<*, *>)["viewAreaIndex"])
    }

    @Test fun ordinaryCustomAlternateUiSurvivesInfoBeforeSetupAndResponseFencing() {
        val sent = mutableListOf<Map<String, Any?>>()
        val withCluster = config().copy(
            cluster = AirPlayDisplayConfig(900, 300, initialUrl = "xcertplay://cluster/default"),
        )
        val control = AirPlayDisplayControlFactory.create(withCluster, { sent += it; true }, {})

        val initialInfo = control.info(
            mapOf(
                "altScreenURLs" to listOf(
                    "xcertplay://cluster/navigation",
                    "xcertplay://cluster/media",
                ),
            ),
        )
        assertEquals(2, (initialInfo["displays"] as List<*>).size)
        assertTrue(initialInfo.containsKey("uiContextNowOnDisplayURLs"))
        assertFalse(initialInfo.containsKey("vehicleStateProtocolInfo"))

        assertEquals(
            listOf("uiContext", "viewAreas", "altScreen", "iAPChannel"),
            control.negotiate(
                listOf("uiContext", "viewAreas", "altScreen", "iAPChannel"),
                eventPortAvailable = true,
            ),
        )
        control.screenReady(111)
        control.flush()
        assertEquals("showUI", sent.single()["type"])
        assertEquals(
            "xcertplay://cluster/navigation",
            (sent.single()["params"] as Map<*, *>)["url"],
        )

        control.responseStarted()
        control.receive(
            "changeUIContext",
            mapOf(
                "uuid" to AirPlayInfoPlist.ALT_UUID,
                "url" to "xcertplay://cluster/media",
            ),
        )
        control.flush()
        assertEquals(1, sent.size)
        control.responseSent()
        assertEquals(2, sent.size)
        assertEquals("showUI", sent.last()["type"])
        assertEquals(
            "xcertplay://cluster/media",
            (sent.last()["params"] as Map<*, *>)["url"],
        )

        val repeatedInfo = control.info(emptyMap())
        assertEquals(2, (repeatedInfo["displays"] as List<*>).size)
        assertEquals(
            listOf("xcertplay://cluster/navigation"),
            repeatedInfo["uiContextLastOnDisplayURLs"],
        )
    }
}
