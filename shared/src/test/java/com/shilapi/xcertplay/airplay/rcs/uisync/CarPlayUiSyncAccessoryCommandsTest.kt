package com.shilapi.xcertplay.airplay.rcs.uisync

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUiSyncAccessoryCommandsTest {
    @Test
    fun confirmedDirectionsMatchIphoneAndAccessoryCallSites() {
        val iphoneToAccessory = setOf(
            CarPlayUiSyncCommandType.TRANSITION_START,
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            CarPlayUiSyncCommandType.LAYOUT_CHANGE,
            CarPlayUiSyncCommandType.TRANSITION_END,
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            CarPlayUiSyncCommandType.METADATA_TRANSFER,
            CarPlayUiSyncCommandType.PALETTE_CHANGE,
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
            CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
        )
        val accessoryToIphone = setOf(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            CarPlayUiSyncCommandType.REQUEST_FOCUS,
            CarPlayUiSyncCommandType.ALLOW_TRANSITIONS,
            CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE,
            CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE,
            CarPlayUiSyncCommandType.UI_CONFIGURATION_CHANGE,
            CarPlayUiSyncCommandType.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
        )
        assertEquals(
            CarPlayUiSyncCommandType.entries.toSet(),
            iphoneToAccessory + accessoryToIphone + CarPlayUiSyncCommandType.GIVE_FOCUS,
        )
        iphoneToAccessory.forEach {
            assertEquals(CarPlayUiSyncCommandDirection.IPHONE_TO_ACCESSORY, it.confirmedDirection)
        }
        accessoryToIphone.forEach {
            assertEquals(CarPlayUiSyncCommandDirection.ACCESSORY_TO_IPHONE, it.confirmedDirection)
        }
        assertEquals(
            CarPlayUiSyncCommandDirection.BIDIRECTIONAL,
            CarPlayUiSyncCommandType.GIVE_FOCUS.confirmedDirection,
        )
    }

    @Test
    fun typedCommandsUseExactObservedKeysAndDefensiveMaps() {
        val active = linkedMapOf("zone" to "component")
        val uiConfiguration = linkedMapOf("density" to "compact")
        val request = CarPlayUiSyncAccessoryCommand.RequestLayout(
            layoutID = "layout-a",
            fadeInNewLayout = false,
            activeComponents = active,
            appearanceMode = -1,
            driveMode = "sport",
            uiConfiguration = uiConfiguration,
        )
        active.clear()
        uiConfiguration.clear()
        assertEquals(
            mapOf(
                "fadeInNewLayout" to false,
                "fadeOutOldLayout" to true,
                "activeComponents" to mapOf("zone" to "component"),
                "layoutID" to "layout-a",
                "appearanceMode" to -1,
                "driveMode" to "sport",
                "uiConfiguration" to mapOf("density" to "compact"),
            ),
            request.wireData(),
        )

        assertEquals(mapOf("tokenID" to 7L), CarPlayUiSyncAccessoryCommand.GiveFocus(7).wireData())
        assertEquals(mapOf("tokenID" to 8L), CarPlayUiSyncAccessoryCommand.RequestFocus(8).wireData())
        assertEquals(mapOf("allow" to false), CarPlayUiSyncAccessoryCommand.AllowTransitions(false).wireData())
        assertEquals(
            mapOf("appearanceMode" to 1),
            CarPlayUiSyncAccessoryCommand.TargetAppearanceChange(1).wireData(),
        )
        assertEquals(
            mapOf("driveMode" to "comfort"),
            CarPlayUiSyncAccessoryCommand.DriveModeChange("comfort").wireData(),
        )
        assertEquals(
            mapOf("uiConfiguration" to mapOf("style" to "minimal")),
            CarPlayUiSyncAccessoryCommand.UiConfigurationChange(
                mapOf("style" to "minimal"),
            ).wireData(),
        )
        assertEquals(
            mapOf("driveModeToLayoutIdAssignments" to mapOf("sport" to "layout-a")),
            CarPlayUiSyncAccessoryCommand.DriveModeDynamicAssignmentChange(
                mapOf("sport" to "layout-a"),
            ).wireData(),
        )
    }

    @Test
    fun typedCommandsRejectInvalidEnumsEmptyAssignmentsAndUnboundedMaps() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncAccessoryCommand.RequestLayout(appearanceMode = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncAccessoryCommand.TargetAppearanceChange(-2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncAccessoryCommand.DriveModeDynamicAssignmentChange(mapOf("" to "layout"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncAccessoryCommand.DriveModeDynamicAssignmentChange(mapOf("sport" to ""))
        }

        val endless = EndlessStringMap()
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncAccessoryCommand.UiConfigurationChange(endless)
        }
        assertEquals(1_024, endless.nextCalls.get())
    }

    @Test
    fun envelopesCannotBeMintedForIphoneOnlyTypes() {
        val typed = CarPlayUiSyncAccessoryCommand.AllowTransitions(true)
        val envelope = typed.toEnvelope()
        assertEquals(CarPlayUiSyncCommandType.ALLOW_TRANSITIONS.wireValue, envelope.type)
        assertEquals(mapOf("allow" to true), envelope.data)
        assertTrue(
            CarPlayUiSyncCommandType.GIVE_FOCUS.confirmedDirection ==
                CarPlayUiSyncCommandDirection.BIDIRECTIONAL,
        )
        assertFalse(
            CarPlayUiSyncCommandType.PALETTE_CHANGE.confirmedDirection ==
                CarPlayUiSyncCommandDirection.ACCESSORY_TO_IPHONE,
        )
    }

    private class EndlessStringMap : AbstractMap<String, String>() {
        val nextCalls = AtomicInteger()
        override val entries: Set<Map.Entry<String, String>> =
            object : AbstractSet<Map.Entry<String, String>>() {
                override val size: Int = Int.MAX_VALUE
                override fun iterator(): Iterator<Map.Entry<String, String>> =
                    object : Iterator<Map.Entry<String, String>> {
                        override fun hasNext(): Boolean = true
                        override fun next(): Map.Entry<String, String> {
                            val index = nextCalls.getAndIncrement()
                            return object : Map.Entry<String, String> {
                                override val key = "key$index"
                                override val value = "value$index"
                            }
                        }
                    }
            }
    }
}
