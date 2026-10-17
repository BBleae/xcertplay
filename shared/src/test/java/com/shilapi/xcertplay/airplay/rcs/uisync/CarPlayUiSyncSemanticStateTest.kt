package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHubLimits
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.io.Closeable
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUiSyncSemanticStateTest {
    @Test
    fun transitionStartDecodesAllObservedVariantsAndDeeplyFreezesRegions() {
        val bytes = byteArrayOf(1, 2, 3)
        val nested = mutableListOf<Any?>("needle", bytes)
        val region = linkedMapOf<String, Any?>(
            "region" to "primary",
            "metadata" to nested,
        )
        val data = linkedMapOf<String, Any?>(
            "regions" to mutableListOf(region),
            "current_ntp_time" to Long.MIN_VALUE,
            "targetComponents" to linkedMapOf("zone" to "component"),
        )

        val command = decoded(CarPlayUiSyncCommandType.TRANSITION_START, data)
            as CarPlayUiSyncSemanticCommand.TransitionStart.DynamicContent
        region["region"] = "mutated"
        nested.clear()
        bytes[0] = 99
        data.clear()

        assertEquals(Long.MIN_VALUE, command.currentNtpTimeBits)
        assertEquals(mapOf("zone" to "component"), command.targetComponents)
        assertEquals(1, command.regions.size)
        @Suppress("UNCHECKED_CAST")
        val frozen = command.regions.single().value as Map<String, Any?>
        assertEquals("primary", frozen["region"])
        val frozenMetadata = frozen["metadata"] as List<*>
        assertEquals("needle", frozenMetadata[0])
        assertTrue((frozenMetadata[1] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        (frozenMetadata[1] as ByteArray)[0] = 55
        val reread = command.regions.single().value as Map<*, *>
        assertTrue(
            ((reread["metadata"] as List<*>)[1] as ByteArray)
                .contentEquals(byteArrayOf(1, 2, 3)),
        )
        assertFalse(command.toString().contains("primary"))

        val maximumUnsigned = decoded(
            CarPlayUiSyncCommandType.TRANSITION_START,
            mapOf(
                "regions" to emptyList<Any>(),
                "current_ntp_time" to BigInteger("18446744073709551615"),
                "targetComponents" to emptyMap<String, String>(),
            ),
        ) as CarPlayUiSyncSemanticCommand.TransitionStart.DynamicContent
        assertEquals(-1L, maximumUnsigned.currentNtpTimeBits)

        assertSame(
            CarPlayUiSyncSemanticCommand.TransitionStart.RequestContent,
            decoded(CarPlayUiSyncCommandType.TRANSITION_START, emptyMap<String, Any>()),
        )
    }

    @Test
    fun transitionStartLayoutIntentRetainsExactThreeFieldShapeWithoutSelectingLayout() {
        val selected = linkedMapOf("zone" to "component")
        val values = linkedMapOf<String, Any?>(
            "layoutID" to "layout-a",
            "activeComponents" to selected,
            "paletteID" to "palette-a",
        )
        val command = decoded(CarPlayUiSyncCommandType.TRANSITION_START, values)
            as CarPlayUiSyncSemanticCommand.TransitionStart.LayoutIntent
        selected["zone"] = "other"
        values.clear()
        assertEquals("layout-a", command.layoutID)
        assertEquals(mapOf("zone" to "component"), command.activeComponents)
        assertEquals("palette-a", command.paletteID)
        assertFalse(command.toString().contains("layout-a"))
        assertFalse(command.toString().contains("component"))
        assertEquals(CarPlayUiSyncCommandType.TRANSITION_START, command.type)
    }

    @Test
    fun transitionStartRequiresOneExactTypedShapeAndBoundsOpaqueRegions() {
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_START,
            layoutChangeData() + ("guessed" to true),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_START,
            mapOf("current_ntp_time" to 1L),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_START,
            mapOf(
                "regions" to emptyList<Any>(),
                "current_ntp_time" to 1L,
                "targetComponents" to emptyMap<String, String>(),
                "guessed" to true,
            ),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_START,
            mapOf(
                "regions" to emptyList<Any>(),
                "current_ntp_time" to -1,
                "targetComponents" to emptyMap<String, String>(),
            ),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_START,
            mapOf(
                "regions" to listOf("not-a-dictionary"),
                "current_ntp_time" to 1L,
                "targetComponents" to emptyMap<String, String>(),
            ),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
        )

        val bounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 16,
                maximumAggregateStringChars = 256,
                maximumOpaqueNodes = 3,
            ),
        )
        assertRejected(
            bounded.decode(
                CarPlayUiSyncCommandType.TRANSITION_START.wireValue,
                mapOf(
                    "regions" to listOf(
                        mapOf("nested" to listOf(1, 2, 3)),
                    ),
                    "current_ntp_time" to 1L,
                    "targetComponents" to emptyMap<String, String>(),
                ),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
    }

    @Test
    fun layoutChangeDecodesExactTypeThreeAndFreezesItsComponentMap() {
        val components = linkedMapOf("speed" to "main", "navigation" to "side")
        val data = linkedMapOf<String, Any>(
            "layoutID" to "layout-a",
            "activeComponents" to components,
            "paletteID" to "palette-a",
        )
        val command = decoded(CarPlayUiSyncCommandType.LAYOUT_CHANGE, data)
            as CarPlayUiSyncSemanticCommand.LayoutChange
        components.clear()
        data.clear()
        assertEquals("layout-a", command.layoutID)
        assertEquals("palette-a", command.paletteID)
        assertEquals(mapOf("speed" to "main", "navigation" to "side"), command.activeComponents)
        assertFalse(command.toString().contains("layout-a"))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (command.activeComponents as MutableMap<String, String>).clear()
        }

        listOf(
            emptyMap<String, Any>() to CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            (layoutChangeData() + ("extra" to true)) to
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            (layoutChangeData() + ("layoutID" to 4)) to
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            (layoutChangeData() + ("activeComponents" to mapOf("zone" to 4))) to
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            (layoutChangeData() + ("paletteID" to null)) to
                CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD,
        ).forEach { (invalid, failure) ->
            assertDecodeFailure(CarPlayUiSyncCommandType.LAYOUT_CHANGE, invalid, failure)
        }
    }

    @Test
    fun endLayoutChangeRequestEchoIsOpaqueBoundedAndCannotMaskMalformedTimedForm() {
        val bytes = byteArrayOf(2, 3)
        val echoed = linkedMapOf<String, Any?>(
            "layoutID" to "requested-layout",
            "unconfirmedOption" to listOf(mapOf("bytes" to bytes)),
        )
        val command = decoded(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, echoed)
            as CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho
        echoed.clear()
        bytes[0] = 9
        val frozen = command.requestData.value as Map<*, *>
        assertEquals("requested-layout", frozen["layoutID"])
        val frozenBytes = ((frozen["unconfirmedOption"] as List<*>).single() as Map<*, *>)["bytes"]
            as ByteArray
        assertTrue(frozenBytes.contentEquals(byteArrayOf(2, 3)))
        frozenBytes[0] = 7
        val reread = command.requestData.value as Map<*, *>
        val rereadBytes = ((reread["unconfirmedOption"] as List<*>).single() as Map<*, *>)["bytes"]
            as ByteArray
        assertTrue(rereadBytes.contentEquals(byteArrayOf(2, 3)))
        assertFalse(command.toString().contains("requested-layout"))

        val cycle = linkedMapOf<String, Any?>()
        cycle["loop"] = cycle
        assertDecodeFailure(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            cycle,
            CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
        )
        val bounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 16,
                maximumAggregateStringChars = 256,
                maximumOpaqueNodes = 3,
            ),
        )
        assertRejected(
            bounded.decode(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE.wireValue,
                mapOf("future" to listOf(mapOf("deep" to listOf(1, 2)))),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            mapOf("regions" to emptyList<Any>(), "extra" to true),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
    }

    @Test
    fun transitionStartRejectsHostileCyclesDepthDataNumbersAndEndlessRegionLists() {
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_START,
            mapOf(
                "regions" to listOf(mapOf("cycle" to cycle)),
                "current_ntp_time" to 1L,
                "targetComponents" to emptyMap<String, String>(),
            ),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
        )

        val tight = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 16,
                maximumAggregateStringChars = 256,
                maximumOpaqueNestingDepth = 2,
                maximumOpaqueDataBytes = 2,
            ),
        )
        listOf(
            mapOf("nested" to listOf(listOf(listOf("too-deep")))) to
                CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
            mapOf("data" to byteArrayOf(1, 2, 3)) to
                CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
            mapOf("number" to Double.NaN) to
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            mapOf("number" to BigInteger.ONE.shiftLeft(65)) to
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
        ).forEach { (component, failure) ->
            assertRejected(
                tight.decode(
                    CarPlayUiSyncCommandType.TRANSITION_START.wireValue,
                    mapOf(
                        "regions" to listOf(component),
                        "current_ntp_time" to 1L,
                        "targetComponents" to emptyMap<String, String>(),
                    ),
                ),
                failure,
            )
        }

        val endless = EndlessList()
        val listBounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 5,
                maximumAggregateStringChars = 256,
            ),
        )
        assertRejected(
            listBounded.decode(
                CarPlayUiSyncCommandType.TRANSITION_START.wireValue,
                mapOf(
                    "regions" to endless,
                    "current_ntp_time" to 1L,
                    "targetComponents" to emptyMap<String, String>(),
                ),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
        assertEquals(1, endless.nextCalls.get())
    }

    @Test
    fun remainingIphonePayloadsDecodeExactObservedShapesAndFreezeOpenRegions() {
        val regionBytes = byteArrayOf(7, 8)
        val region = linkedMapOf<String, Any?>(
            "futureRegionKey" to listOf("opaque", regionBytes),
        )
        val transitionData = decoded(
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            linkedMapOf(
                "regions" to listOf(region),
                "presentation_host_time" to Long.MIN_VALUE,
                "presentation_ntp_time" to BigInteger("18446744073709551615"),
                "presentation_ntp_time_original" to 9L,
                "current_ntp_time" to 10L,
            ),
        ) as CarPlayUiSyncSemanticCommand.TransitionData
        region.clear()
        regionBytes[0] = 99

        assertEquals(Long.MIN_VALUE, transitionData.presentationHostTimeBits)
        assertEquals(-1L, transitionData.presentationNtpTimeBits)
        assertEquals(9L, transitionData.presentationNtpTimeOriginalBits)
        assertEquals(10L, transitionData.currentNtpTimeBits)
        val frozenRegion = transitionData.regions.single().value as Map<*, *>
        val frozenRegionValues = frozenRegion["futureRegionKey"] as List<*>
        assertEquals("opaque", frozenRegionValues[0])
        assertTrue((frozenRegionValues[1] as ByteArray).contentEquals(byteArrayOf(7, 8)))

        val withoutOriginal = decoded(
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            mapOf(
                "regions" to emptyList<Any>(),
                "presentation_host_time" to 1L,
                "presentation_ntp_time" to 2L,
                "current_ntp_time" to 3L,
            ),
        ) as CarPlayUiSyncSemanticCommand.TransitionData
        assertNull(withoutOriginal.presentationNtpTimeOriginalBits)

        val transitionEnd = decoded(
            CarPlayUiSyncCommandType.TRANSITION_END,
            mapOf("regions" to emptyList<Any>(), "current_ntp_time" to Long.MIN_VALUE),
        ) as CarPlayUiSyncSemanticCommand.TransitionEnd
        assertEquals(Long.MIN_VALUE, transitionEnd.currentNtpTimeBits)
        val layoutEnd = decoded(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            mapOf("regions" to emptyList<Any>(), "current_ntp_time" to Long.MIN_VALUE),
        ) as CarPlayUiSyncSemanticCommand.EndLayoutChange.Timed
        assertEquals(Long.MIN_VALUE, layoutEnd.currentNtpTimeBits)
        assertTrue(layoutEnd.regions.isEmpty())
        val emptyEcho = decoded(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            emptyMap<String, Any>(),
        ) as CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho
        assertEquals(emptyMap<String, Any>(), emptyEcho.requestData.value)

        val metadata = decoded(
            CarPlayUiSyncCommandType.METADATA_TRANSFER,
            mapOf("isTransitioning" to true),
        ) as CarPlayUiSyncSemanticCommand.MetadataTransfer
        assertTrue(metadata.isTransitioning)

        val preference = decoded(
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
            mapOf("preference" to Int.MIN_VALUE, "override" to 2L),
        ) as CarPlayUiSyncSemanticCommand.AppearancePreferenceChange
        assertEquals(Int.MIN_VALUE, preference.preference)
        assertEquals(2, preference.appearanceOverride)
        val noOverride = decoded(
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
            mapOf("preference" to Int.MAX_VALUE.toLong()),
        ) as CarPlayUiSyncSemanticCommand.AppearancePreferenceChange
        assertNull(noOverride.appearanceOverride)

        val start = decoded(
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
            mapOf("targetAppearance" to 0),
        ) as CarPlayUiSyncSemanticCommand.AppearanceChangeStart
        val end = decoded(
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
            mapOf("targetAppearance" to 1L),
        ) as CarPlayUiSyncSemanticCommand.AppearanceChangeEnd
        assertEquals(0, start.targetAppearance)
        assertEquals(1, end.targetAppearance)

        val sportConfiguration = linkedMapOf<String, Any?>(
            "colorPaletteId" to "red",
            "wallpaperId" to "carbon",
            "layoutId" to "gauges",
        )
        val configurations = linkedMapOf<String, Any?>(
            "sport" to sportConfiguration,
            "eco" to mapOf("colorPaletteId" to "green"),
        )
        val overrideChange = decoded(
            CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
            mapOf("configurations" to configurations, "linked" to false),
        ) as CarPlayUiSyncSemanticCommand.DriveModeThemeConfigurationOverrideChange
        configurations.clear()
        sportConfiguration.clear()
        assertFalse(overrideChange.linked)
        assertEquals(setOf("sport", "eco"), overrideChange.configurations.keys)
        assertEquals("red", overrideChange.configurations.getValue("sport").colorPaletteId)
        assertEquals("carbon", overrideChange.configurations.getValue("sport").wallpaperId)
        assertEquals("gauges", overrideChange.configurations.getValue("sport").layoutId)
        assertNull(overrideChange.configurations.getValue("eco").wallpaperId)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (overrideChange.configurations as MutableMap<String, Any?>).clear()
        }
    }

    @Test
    fun remainingIphonePayloadsRejectMissingWrongExtraAndInvalidValues() {
        listOf(
            Triple(
                CarPlayUiSyncCommandType.TRANSITION_DATA,
                mapOf(
                    "regions" to emptyList<Any>(),
                    "presentation_host_time" to 0L,
                    "presentation_ntp_time" to 1L,
                    "current_ntp_time" to 1L,
                ),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            ),
            Triple(
                CarPlayUiSyncCommandType.TRANSITION_DATA,
                mapOf(
                    "regions" to emptyList<Any>(),
                    "presentation_host_time" to 1L,
                    "presentation_ntp_time" to 1L,
                    "current_ntp_time" to 1L,
                    "extra" to true,
                ),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            ),
            Triple(
                CarPlayUiSyncCommandType.TRANSITION_END,
                mapOf("regions" to listOf("not-a-dictionary"), "current_ntp_time" to 1L),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            ),
            Triple(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                mapOf("regions" to emptyList<Any>()),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            ),
            Triple(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                mapOf("regions" to listOf("not-a-dictionary"), "current_ntp_time" to 1L),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            ),
            Triple(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                mapOf("regions" to emptyList<Any>(), "current_ntp_time" to -1),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            ),
            Triple(
                CarPlayUiSyncCommandType.METADATA_TRANSFER,
                mapOf("isTransitioning" to 1),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            ),
            Triple(
                CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                mapOf("preference" to 0, "override" to 0),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_OVERRIDE_VALUE,
            ),
            Triple(
                CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                mapOf("preference" to 0, "override" to 1, "extra" to false),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            ),
            Triple(
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
                mapOf("targetAppearance" to -1),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_APPEARANCE_MODE,
            ),
            Triple(
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
                mapOf("targetAppearance" to 2),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_APPEARANCE_MODE,
            ),
            Triple(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
                mapOf(
                    "configurations" to mapOf(
                        "sport" to mapOf("colorPaletteId" to "red", "extra" to true),
                    ),
                    "linked" to true,
                ),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            ),
            Triple(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
                mapOf(
                    "configurations" to mapOf("sport" to emptyMap<String, Any>()),
                    "linked" to true,
                ),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            ),
            Triple(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
                mapOf(
                    "configurations" to mapOf(
                        "sport" to mapOf("colorPaletteId" to 7),
                    ),
                    "linked" to true,
                ),
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            ),
        ).forEach { (type, payload, failure) ->
            assertDecodeFailure(type, payload, failure)
        }

        assertDecodeFailure(
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            mapOf(
                "regions" to emptyList<Any>(),
                "presentation_host_time" to 1L,
                "presentation_ntp_time" to BigInteger.ONE.shiftLeft(64),
                "current_ntp_time" to 1L,
            ),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
        )
    }

    @Test
    fun requestLayoutDecodesObservedFieldsPreservesMinusOneAndCopiesMaps() {
        val active = linkedMapOf("speed" to "primary", "rpm" to "secondary")
        val configuration = linkedMapOf("density" to "compact")
        val data = linkedMapOf<String, Any>(
            "layoutID" to "sport-layout",
            "fadeInNewLayout" to false,
            "fadeOutOldLayout" to false,
            "activeComponents" to active,
            "appearanceMode" to -1L,
            "driveMode" to "sport",
            "uiConfiguration" to configuration,
            // REQUEST_LAYOUT is an observed dictionary initializer rather than an exact-key shape.
            "futureEvidence" to byteArrayOf(1, 2, 3),
        )

        val command = decoded(CarPlayUiSyncCommandType.REQUEST_LAYOUT, data)
            as CarPlayUiSyncSemanticCommand.RequestLayout
        active["speed"] = "mutated"
        configuration.clear()
        data["layoutID"] = "mutated"

        assertEquals("sport-layout", command.layoutID)
        assertFalse(command.fadeInNewLayout)
        assertFalse(command.fadeOutOldLayout)
        assertEquals(mapOf("speed" to "primary", "rpm" to "secondary"), command.activeComponents)
        assertEquals(-1, command.appearanceMode)
        assertEquals("sport", command.driveMode)
        assertEquals(mapOf("density" to "compact"), command.uiConfiguration)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (command.activeComponents as MutableMap<String, String>)["new"] = "value"
        }
    }

    @Test
    fun requestLayoutUsesOnlyEvidencedDefaultsForMissingOrWrongTypes() {
        val missing = decoded(CarPlayUiSyncCommandType.REQUEST_LAYOUT, emptyMap<String, Any>())
            as CarPlayUiSyncSemanticCommand.RequestLayout
        assertNull(missing.layoutID)
        assertTrue(missing.fadeInNewLayout)
        assertTrue(missing.fadeOutOldLayout)
        assertEquals(emptyMap<String, String>(), missing.activeComponents)
        assertNull(missing.appearanceMode)
        assertNull(missing.driveMode)
        assertNull(missing.uiConfiguration)

        val wrong = decoded(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            mapOf(
                "layoutID" to 7,
                "fadeInNewLayout" to "false",
                "fadeOutOldLayout" to 0,
                "activeComponents" to mapOf("speed" to 1),
                "appearanceMode" to 2,
                "driveMode" to true,
                "uiConfiguration" to listOf("compact"),
            ),
        ) as CarPlayUiSyncSemanticCommand.RequestLayout
        assertNull(wrong.layoutID)
        assertTrue(wrong.fadeInNewLayout)
        assertTrue(wrong.fadeOutOldLayout)
        assertTrue(wrong.activeComponents.isEmpty())
        assertNull(wrong.appearanceMode)
        assertNull(wrong.driveMode)
        assertNull(wrong.uiConfiguration)
    }

    @Test
    fun requestLayoutRequiresDictionaryAndDefaultsMalformedOptionalStringMaps() {
        assertDecodeFailure(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            listOf("not", "a", "dictionary"),
            CarPlayUiSyncSemanticDecodeFailure.DATA_NOT_DICTIONARY,
        )
        val command = decoded(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            mapOf(
                "activeComponents" to mapOf(1 to "primary"),
                "uiConfiguration" to mapOf("density" to 7),
            ),
        ) as CarPlayUiSyncSemanticCommand.RequestLayout
        assertTrue(command.activeComponents.isEmpty())
        assertNull(command.uiConfiguration)
    }

    @Test
    fun exactFocusPaletteAppearanceAndDrivePayloadsDecodeWithoutNameGuessing() {
        val focus = decoded(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            mapOf("tokenID" to 91L),
        ) as CarPlayUiSyncSemanticCommand.GiveFocus
        assertEquals(91L, focus.tokenID)
        val fullWidthFocus = decoded(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            mapOf("tokenID" to Long.MAX_VALUE),
        ) as CarPlayUiSyncSemanticCommand.GiveFocus
        assertEquals(Long.MAX_VALUE, fullWidthFocus.tokenID)

        val palette = decoded(
            CarPlayUiSyncCommandType.PALETTE_CHANGE,
            mapOf(
                "layoutIdentifier" to "cluster-main",
                "paletteIdentifier" to "night-blue",
            ),
        ) as CarPlayUiSyncSemanticCommand.PaletteChange
        assertEquals("cluster-main", palette.layoutIdentifier)
        assertEquals("night-blue", palette.paletteIdentifier)

        listOf(-1, 0, 1).forEach { raw ->
            val appearance = decoded(
                CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE,
                mapOf("appearanceMode" to raw.toLong()),
            ) as CarPlayUiSyncSemanticCommand.TargetAppearanceChange
            assertEquals(raw, appearance.appearanceMode)
        }

        val drive = decoded(
            CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE,
            mapOf("driveMode" to "comfort"),
        ) as CarPlayUiSyncSemanticCommand.DriveModeChange
        assertEquals("comfort", drive.driveMode)
    }

    @Test
    fun exactPayloadsRejectMissingExtraWrongAndOutOfRangeFields() {
        assertDecodeFailure(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            emptyMap<String, Any>(),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            mapOf("tokenID" to 1, "token" to 1),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            mapOf("tokenID" to 1.0),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.PALETTE_CHANGE,
            mapOf("layoutIdentifier" to "layout"),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.PALETTE_CHANGE,
            mapOf("layoutIdentifier" to 1, "paletteIdentifier" to "palette"),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE,
            mapOf("appearanceMode" to 2),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_APPEARANCE_MODE,
        )
        assertDecodeFailure(
            CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE,
            mapOf("driveMode" to "eco", "layoutID" to "guessed"),
            CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
        )
    }

    @Test
    fun decoderEnforcesPerStringMapAndAggregateBounds() {
        val stringBounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 32,
                maximumMapEntries = 8,
                maximumAggregateStringChars = 128,
            ),
        )
        assertRejected(
            stringBounded.decode(
                CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE.wireValue,
                mapOf("driveMode" to "x".repeat(33)),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )

        val mapBounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 2,
                maximumAggregateStringChars = 256,
            ),
        )
        assertRejected(
            mapBounded.decode(
                CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue,
                mapOf("activeComponents" to mapOf("speed" to "a", "rpm" to "b")),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )

        val aggregateBounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 32,
                maximumMapEntries = 8,
                maximumAggregateStringChars = 40,
            ),
        )
        assertRejected(
            aggregateBounded.decode(
                CarPlayUiSyncCommandType.PALETTE_CHANGE.wireValue,
                mapOf(
                    "layoutIdentifier" to "abcdefghij",
                    "paletteIdentifier" to "abcdefghij",
                ),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )

        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncSemanticLimits(maximumStringChars = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncSemanticLimits(maximumStringChars = 8, maximumAggregateStringChars = 7)
        }
    }

    @Test
    fun newPayloadsShareOpaqueAndHostileCollectionBudgets() {
        val bounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 32,
                maximumMapEntries = 16,
                maximumAggregateStringChars = 128,
                maximumOpaqueNestingDepth = 2,
                maximumOpaqueNodes = 4,
                maximumOpaqueDataBytes = 2,
            ),
        )
        assertRejected(
            bounded.decode(
                CarPlayUiSyncCommandType.TRANSITION_DATA.wireValue,
                mapOf(
                    "regions" to listOf(mapOf("data" to byteArrayOf(1, 2, 3))),
                    "presentation_host_time" to 1L,
                    "presentation_ntp_time" to 2L,
                    "current_ntp_time" to 3L,
                ),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )

        val endlessRegions = EndlessList()
        val listBounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 5,
                maximumAggregateStringChars = 256,
            ),
        )
        assertRejected(
            listBounded.decode(
                CarPlayUiSyncCommandType.TRANSITION_END.wireValue,
                mapOf("regions" to endlessRegions, "current_ntp_time" to 1L),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
        assertTrue(endlessRegions.nextCalls.get() <= 4)

        val endlessConfigurations = EndlessMap()
        val configurationBounded = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 4,
                maximumAggregateStringChars = 256,
            ),
        )
        assertRejected(
            configurationBounded.decode(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE.wireValue,
                mapOf("configurations" to endlessConfigurations, "linked" to true),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
        assertTrue(endlessConfigurations.nextCalls.get() <= 2)

        assertRejected(
            CarPlayUiSyncSemanticDecoder(
                CarPlayUiSyncSemanticLimits(
                    maximumStringChars = 16,
                    maximumMapEntries = 16,
                    maximumAggregateStringChars = 128,
                ),
            ).decode(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE.wireValue,
                mapOf(
                    "configurations" to mapOf(
                        "too-long-identifier" to mapOf("colorPaletteId" to "red"),
                    ),
                    "linked" to true,
                ),
            ),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
    }

    @Test
    fun decoderBoundsEndlessMapIterationWithoutTrustingReportedSize() {
        val endless = EndlessMap()
        val decoder = CarPlayUiSyncSemanticDecoder(
            CarPlayUiSyncSemanticLimits(
                maximumStringChars = 64,
                maximumMapEntries = 3,
                maximumAggregateStringChars = 256,
            ),
        )

        assertRejected(
            decoder.decode(CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue, endless),
            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
        )
        assertEquals(3, endless.nextCalls.get())
    }

    @Test
    fun decoderUsesSingleFrozenRootAndNestedMapSnapshot() {
        val nested = LookupMutatingMap(listOf("speed" to "primary"))
        val root = LookupMutatingMap(
            listOf(
                "layoutID" to "stable-layout",
                "activeComponents" to nested,
            ),
        )

        val command = decoded(CarPlayUiSyncCommandType.REQUEST_LAYOUT, root)
            as CarPlayUiSyncSemanticCommand.RequestLayout

        assertEquals("stable-layout", command.layoutID)
        assertEquals(mapOf("speed" to "primary"), command.activeComponents)
        assertEquals(0, root.lookupCount.get())
        assertEquals(0, nested.lookupCount.get())
    }

    @Test
    fun decoderRejectsUnknownAndKnownButUnconfirmedStateChangingCommands() {
        assertRejected(
            DECODER.decode(31337, emptyMap<String, Any>()),
            CarPlayUiSyncSemanticDecodeFailure.UNKNOWN_COMMAND,
        )
        listOf(
            CarPlayUiSyncCommandType.UI_CONFIGURATION_CHANGE,
            CarPlayUiSyncCommandType.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
        ).forEach { type ->
            assertRejected(
                DECODER.decode(type.wireValue, emptyMap<String, Any>()),
                CarPlayUiSyncSemanticDecodeFailure.UNSUPPORTED_COMMAND,
            )
        }
    }

    @Test
    fun reducerProjectsOnlyConfirmedIphoneToAccessoryState() {
        Fixture(DISPLAY).use { fixture ->
            fixture.known(
                CarPlayUiSyncCommandType.TRANSITION_START,
                mapOf(
                    "regions" to listOf(mapOf("region" to "primary")),
                    "current_ntp_time" to Long.MIN_VALUE,
                    "targetComponents" to mapOf("zone" to "component"),
                ),
            )
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 17))
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf(
                    "layoutIdentifier" to "layout-a",
                    "paletteIdentifier" to "palette-b",
                ),
            )
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)

            val result = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied

            assertEquals(3, result.appliedEventCount)
            val transition = result.state.transitionStart
                as CarPlayUiSyncSemanticCommand.TransitionStart.DynamicContent
            assertEquals(Long.MIN_VALUE, transition.currentNtpTimeBits)
            assertEquals(1, transition.regions.size)
            assertEquals(mapOf("zone" to "component"), transition.targetComponents)
            assertEquals(17L, result.state.focusTokenID)
            assertEquals("layout-a", result.state.palette?.layoutIdentifier)
            assertEquals("palette-b", result.state.palette?.paletteIdentifier)
        }
    }

    @Test
    fun layoutCommandsRecordDesiredIntentButNeverPromoteAnActiveLayout() {
        Fixture(DISPLAY).use { first ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            first.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, emptyMap<String, Any>())
            val beforeLayout = reducer.reduce(first.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertNull(beforeLayout.state.desiredLayoutChange)

            first.known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layoutChangeData())
            first.known(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                mapOf("layoutID" to "echoed-request", "fadeInNewLayout" to true),
            )
            val afterEcho = reducer.reduce(first.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertEquals("layout-a", afterEcho.state.desiredLayoutChange?.layoutID)
            assertEquals(2L, afterEcho.state.lastLayoutChangeCommandRevision)
            assertEquals(3L, afterEcho.state.lastEndLayoutChangeCommandRevision)
            assertTrue(
                afterEcho.state.lastEndLayoutChangeCommand is
                    CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho,
            )

            // Type 4 is not a prerequisite for type 3 -> 6, and a later type 3 is only intent.
            first.known(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                layoutChangeData() + ("layoutID" to "layout-b"),
            )
            first.known(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                mapOf("regions" to emptyList<Any>(), "current_ntp_time" to 5L),
            )
            val afterTimedEnd = reducer.reduce(first.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertEquals("layout-b", afterTimedEnd.state.desiredLayoutChange?.layoutID)
            assertEquals(4L, afterTimedEnd.state.lastLayoutChangeCommandRevision)
            assertEquals(5L, afterTimedEnd.state.lastEndLayoutChangeCommandRevision)
            assertTrue(
                afterTimedEnd.state.lastEndLayoutChangeCommand is
                    CarPlayUiSyncSemanticCommand.EndLayoutChange.Timed,
            )
            assertNull(afterTimedEnd.state.lastTransitionEndCommand)

            Fixture("other-display").use { second ->
                second.known(
                    CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                    layoutChangeData() + ("layoutID" to "independent"),
                )
                val other = CarPlayUiSyncSemanticReducer("other-display")
                    .reduce(second.snapshot()) as CarPlayUiSyncSemanticReduceResult.Applied
                assertEquals("independent", other.state.desiredLayoutChange?.layoutID)
                assertEquals("layout-b", reducer.state().desiredLayoutChange?.layoutID)
            }

            first.close()
            val closed = reducer.reduce(first.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertTrue(closed.state.closed)
            assertNull(closed.state.desiredLayoutChange)
            assertNull(CarPlayUiSyncSemanticReducer(DISPLAY).state().desiredLayoutChange)
        }
    }

    @Test
    fun malformedLayoutEndRollsBackEarlierLayoutIntentInSameSnapshot() {
        Fixture(DISPLAY).use { fixture ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            fixture.known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layoutChangeData())
            fixture.known(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                mapOf("regions" to emptyList<Any>(), "current_ntp_time" to -1),
            )

            val rejected = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.MALFORMED_PAYLOAD, rejected.failure)
            assertEquals(2L, rejected.eventRevision)
            assertEquals(0L, reducer.state().revision)
            assertNull(reducer.state().desiredLayoutChange)
            assertNull(reducer.state().lastEndLayoutChangeCommand)
        }
    }

    @Test
    fun reducerProjectsRemainingInboundPacketsWithoutClaimingPresentationCompletion() {
        Fixture(DISPLAY).use { fixture ->
            fixture.known(
                CarPlayUiSyncCommandType.TRANSITION_DATA,
                mapOf(
                    "regions" to emptyList<Any>(),
                    "presentation_host_time" to 1L,
                    "presentation_ntp_time" to 2L,
                    "current_ntp_time" to 3L,
                ),
            )
            fixture.known(
                CarPlayUiSyncCommandType.TRANSITION_END,
                mapOf("regions" to emptyList<Any>(), "current_ntp_time" to 4L),
            )
            fixture.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, emptyMap<String, Any>())
            fixture.known(
                CarPlayUiSyncCommandType.METADATA_TRANSFER,
                mapOf("isTransitioning" to false),
            )
            fixture.known(
                CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                mapOf("preference" to 7, "override" to 1),
            )
            fixture.known(
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
                mapOf("targetAppearance" to 0),
            )
            fixture.known(
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
                mapOf("targetAppearance" to 1),
            )
            fixture.known(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
                mapOf(
                    "configurations" to mapOf(
                        "sport" to mapOf("colorPaletteId" to "red"),
                    ),
                    "linked" to true,
                ),
            )
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)

            val result = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied

            assertEquals(8, result.appliedEventCount)
            assertEquals(3L, result.state.lastTransitionData?.currentNtpTimeBits)
            assertEquals(4L, result.state.lastTransitionEndCommand?.currentNtpTimeBits)
            assertEquals(3L, result.state.lastEndLayoutChangeCommandRevision)
            assertFalse(result.state.lastMetadataTransfer!!.isTransitioning)
            assertEquals(7, result.state.lastAppearancePreferenceChange?.preference)
            assertEquals(1, result.state.lastAppearancePreferenceChange?.appearanceOverride)
            assertEquals(
                0,
                result.state.lastAppearanceChangeStartCommand?.targetAppearance,
            )
            assertEquals(
                1,
                result.state.lastAppearanceChangeEndCommand?.targetAppearance,
            )
            assertTrue(result.state.lastDriveModeThemeConfigurationOverrideChange!!.linked)
            assertEquals(
                "red",
                result.state.lastDriveModeThemeConfigurationOverrideChange
                    ?.configurations?.get("sport")?.colorPaletteId,
            )
        }
    }

    @Test
    fun malformedNewInboundPacketRollsBackEveryEarlierCandidateUpdate() {
        Fixture(DISPLAY).use { fixture ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            fixture.known(
                CarPlayUiSyncCommandType.METADATA_TRANSFER,
                mapOf("isTransitioning" to false),
            )
            val baseline = (reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied).state

            fixture.known(
                CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                mapOf("preference" to 1, "override" to 2),
            )
            fixture.known(
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
                mapOf(
                    "configurations" to mapOf(
                        "sport" to mapOf("colorPaletteId" to "red"),
                    ),
                    "linked" to "not-a-bool",
                ),
            )

            val rejected = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.MALFORMED_PAYLOAD, rejected.failure)
            assertEquals(3L, rejected.eventRevision)
            assertSame(baseline, rejected.state)
            assertSame(baseline, reducer.state())
            assertNull(reducer.state().lastAppearancePreferenceChange)
            assertFalse(reducer.state().lastMetadataTransfer!!.isTransitioning)
        }
    }

    @Test
    fun coalescedSnapshotsApplyEveryUnseenRevisionExactlyOnce() {
        Fixture(DISPLAY).use { fixture ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to "layout", "paletteIdentifier" to "one"),
            )
            val firstSnapshot = fixture.snapshot()
            val first = reducer.reduce(firstSnapshot) as CarPlayUiSyncSemanticReduceResult.Applied
            assertEquals(1, first.appliedEventCount)

            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 2))
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to "layout", "paletteIdentifier" to "two"),
            )
            val coalesced = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertEquals(2, coalesced.appliedEventCount)
            assertEquals(3L, coalesced.state.revision)
            assertEquals(2L, coalesced.state.focusTokenID)
            assertEquals("two", coalesced.state.palette?.paletteIdentifier)

            val duplicate = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Unchanged
            assertSame(coalesced.state, duplicate.state)
            val stale = reducer.reduce(firstSnapshot)
                as CarPlayUiSyncSemanticReduceResult.Unchanged
            assertSame(coalesced.state, stale.state)
        }
    }

    @Test
    fun droppedHistoryIsAcceptedOnlyWhenEveryUnseenRevisionRemainsReplayable() {
        Fixture(DISPLAY, maximumRetainedEvents = 2).use { fixture ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to "layout", "paletteIdentifier" to "one"),
            )
            reducer.reduce(fixture.snapshot())
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to "layout", "paletteIdentifier" to "two"),
            )
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 3))

            val replayable = fixture.snapshot()
            assertEquals(1L, replayable.droppedEventCount)
            val accepted = reducer.reduce(replayable)
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertEquals(2, accepted.appliedEventCount)
            assertEquals("two", accepted.state.palette?.paletteIdentifier)

            Fixture("display-gap", maximumRetainedEvents = 2).use { gapFixture ->
                repeat(3) { index ->
                    gapFixture.known(
                        CarPlayUiSyncCommandType.GIVE_FOCUS,
                        mapOf("tokenID" to index),
                    )
                }
                val gapReducer = CarPlayUiSyncSemanticReducer("display-gap")
                val rejected = gapReducer.reduce(gapFixture.snapshot())
                    as CarPlayUiSyncSemanticReduceResult.Rejected
                assertEquals(CarPlayUiSyncSemanticReduceFailure.HISTORY_GAP, rejected.failure)
                assertEquals(0L, gapReducer.state().revision)
                assertNull(gapReducer.state().focusTokenID)
            }
        }
    }

    @Test
    fun malformedLaterEventRollsBackWholeSnapshotAtomically() {
        Fixture(DISPLAY).use { fixture ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 9))
            val baseline = (reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied).state
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf(
                    "layoutIdentifier" to "layout",
                    "paletteIdentifier" to "palette",
                ),
            )
            fixture.known(
                CarPlayUiSyncCommandType.PALETTE_CHANGE,
                mapOf("layoutIdentifier" to "layout", "paletteIdentifier" to 99),
            )

            val rejected = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.MALFORMED_PAYLOAD, rejected.failure)
            assertEquals(3L, rejected.eventRevision)
            assertSame(baseline, rejected.state)
            assertSame(baseline, reducer.state())
            assertNull(reducer.state().palette)
            assertEquals(9L, reducer.state().focusTokenID)
        }
    }

    @Test
    fun unknownAndUnconfirmedEventsFailClosedWithoutAdvancingState() {
        Fixture(DISPLAY).use { unknownFixture ->
            unknownFixture.unknown(400, mapOf("future" to true))
            val unknownReducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            val unknown = unknownReducer.reduce(unknownFixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.UNKNOWN_COMMAND, unknown.failure)
            assertEquals(0L, unknownReducer.state().revision)
        }

        Fixture(DISPLAY).use { wrongDirectionFixture ->
            wrongDirectionFixture.known(
                CarPlayUiSyncCommandType.UI_CONFIGURATION_CHANGE,
                mapOf("unconfirmed" to true),
            )
            val wrongDirectionReducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            val wrongDirection = wrongDirectionReducer.reduce(wrongDirectionFixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(
                CarPlayUiSyncSemanticReduceFailure.WRONG_DIRECTION,
                wrongDirection.failure,
            )
            assertEquals(0L, wrongDirectionReducer.state().revision)
        }

        Fixture(DISPLAY).use { malformedFixture ->
            malformedFixture.known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, emptyMap<String, Any>())
            val malformedReducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            val unsupported = malformedReducer.reduce(malformedFixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.MALFORMED_PAYLOAD, unsupported.failure)
            assertEquals(0L, malformedReducer.state().revision)
        }
    }

    @Test
    fun unopenedDisplayAcceptsExactEmptyClosedHubSnapshot() {
        val hub = UltraPresentationHub(Executor { it.run() })
        hub.close()
        val snapshot = hub.snapshot(DISPLAY)
        assertEquals(0L, snapshot.revision)
        assertTrue(snapshot.closed)
        assertTrue(snapshot.events.isEmpty())

        val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
        val terminal = reducer.reduce(snapshot) as CarPlayUiSyncSemanticReduceResult.Applied
        assertTrue(terminal.state.closed)
        assertEquals(0L, terminal.state.revision)
        assertNull(terminal.state.desiredLayoutChange)
        assertEquals(0, terminal.appliedEventCount)
        assertSame(
            terminal.state,
            (reducer.reduce(snapshot) as CarPlayUiSyncSemanticReduceResult.Unchanged).state,
        )
    }

    @Test
    fun closedSnapshotBecomesTerminalAndCannotBeReopenedOrAdvanced() {
        val fixture = Fixture(DISPLAY)
        fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 8))
        val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
        reducer.reduce(fixture.snapshot())
        fixture.close()

        val closed = fixture.snapshot()
        val terminal = reducer.reduce(closed) as CarPlayUiSyncSemanticReduceResult.Applied
        assertTrue(terminal.state.closed)
        assertEquals(2L, terminal.state.revision)
        assertEquals(0, terminal.appliedEventCount)
        val duplicate = reducer.reduce(closed) as CarPlayUiSyncSemanticReduceResult.Unchanged
        assertSame(terminal.state, duplicate.state)

        val impossibleLater = UltraPresentationSnapshot(
            displayId = closed.displayId,
            revision = closed.revision + 1L,
            droppedEventCount = closed.droppedEventCount,
            closed = true,
            events = closed.events,
        )
        val rejected = reducer.reduce(impossibleLater)
            as CarPlayUiSyncSemanticReduceResult.Rejected
        assertEquals(CarPlayUiSyncSemanticReduceFailure.TERMINAL_STATE, rejected.failure)
        assertSame(terminal.state, reducer.state())
    }

    @Test
    fun exactDisplayIdentityPreventsCrossDisplayMutation() {
        Fixture("Display-A").use { fixture ->
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 1))
            val reducer = CarPlayUiSyncSemanticReducer("display-a")

            val rejected = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.DISPLAY_MISMATCH, rejected.failure)
            assertEquals(UltraUiSyncDisplayId("display-a"), reducer.state().displayId)
            assertEquals(0L, reducer.state().revision)
            assertNull(reducer.state().focusTokenID)
        }
    }

    @Test
    fun inconsistentSameRevisionAndDecreasingDropCountersAreRejected() {
        Fixture(DISPLAY, maximumRetainedEvents = 1).use { fixture ->
            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 1))
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 2))
            val gap = fixture.snapshot()
            assertEquals(1L, gap.droppedEventCount)

            val fabricated = UltraPresentationSnapshot(
                displayId = gap.displayId,
                revision = 0L,
                droppedEventCount = 1L,
                closed = false,
                events = emptyList(),
            )
            val inconsistent = reducer.reduce(fabricated)
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT, inconsistent.failure)
            assertEquals(0L, reducer.state().revision)
        }
    }

    @Test
    fun reducerRejectsInconsistentDropCounterAndNonContiguousRetainedSuffix() {
        Fixture(DISPLAY).use { fixture ->
            repeat(4) { index ->
                fixture.known(
                    CarPlayUiSyncCommandType.GIVE_FOCUS,
                    mapOf("tokenID" to index),
                )
            }
            val valid = fixture.snapshot()

            val inconsistentCounter = UltraPresentationSnapshot(
                displayId = valid.displayId,
                revision = valid.revision,
                droppedEventCount = 1L,
                closed = false,
                events = valid.events,
            )
            val counterResult = CarPlayUiSyncSemanticReducer(DISPLAY)
                .reduce(inconsistentCounter) as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(
                CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT,
                counterResult.failure,
            )

            // dropped=1 plus three retained events is arithmetically consistent with revision=4,
            // but the supplied suffix skips revision 3 and repeats revision 4.
            val middleGap = UltraPresentationSnapshot(
                displayId = valid.displayId,
                revision = valid.revision,
                droppedEventCount = 1L,
                closed = false,
                events = listOf(valid.events[1], valid.events[3], valid.events[3]),
            )
            val gapResult = CarPlayUiSyncSemanticReducer(DISPLAY)
                .reduce(middleGap) as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT, gapResult.failure)
        }
    }

    @Test
    fun assetlessRequestEchoAuthorityNeedsAttestedExactEchoOfThisRequest() {
        val request = CarPlayUiSyncAccessoryCommand.RequestLayout(
            layoutID = "main-local-canvas", appearanceMode = 1,
        )
        val exact = request.wireData()
        val expected = decoded(CarPlayUiSyncCommandType.LAYOUT_CHANGE, mapOf(
            "layoutID" to "main-local-canvas",
            "activeComponents" to emptyMap<String, String>(),
            "paletteID" to "palette-signed",
        ))
        fun authorityFor(
            data: Any?,
            attested: Boolean = true,
            sent: CarPlayUiSyncAccessoryCommand.RequestLayout = request,
        ): CarPlayUiSyncRequestEchoLayoutAuthority? = Fixture(DISPLAY).use { fixture ->
            fixture.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, data, attested)
            CarPlayUiSyncRequestEchoLayoutAuthority.authorize(
                fixture.snapshot().events.single(), sent, "palette-signed",
            )
        }

        val authority = requireNotNull(authorityFor(exact))
        assertEquals(1L, authority.eventRevision)
        assertEquals(expected, authority.layoutChange)
        // OPACK/plist do not carry integer width; the echoed Long equals the sent Int.
        assertTrue(authorityFor(LinkedHashMap(exact).apply { put("appearanceMode", 1L) }) != null)

        assertNull("unattested", authorityFor(exact, attested = false))
        assertNull("changed value", authorityFor(LinkedHashMap(exact).apply {
            put("fadeInNewLayout", false)
        }))
        assertNull("boolean is not an integer", authorityFor(LinkedHashMap(exact).apply {
            put("fadeOutOldLayout", 1L)
        }))
        assertNull("extra key", authorityFor(LinkedHashMap(exact).apply {
            put("driveMode", "sport")
        }))
        assertNull("missing key", authorityFor(LinkedHashMap(exact).apply {
            remove("fadeOutOldLayout")
        }))
        assertNull("extra component", authorityFor(LinkedHashMap(exact).apply {
            put("activeComponents", mapOf("zone" to "component"))
        }))
        assertNull("timed form", authorityFor(mapOf(
            "regions" to emptyList<Any>(), "current_ntp_time" to 3L,
        )))
        assertNull("echo of another request", authorityFor(
            CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "other").wireData(),
        ))
        assertNull("layoutless request", authorityFor(
            CarPlayUiSyncAccessoryCommand.RequestLayout().wireData(),
            sent = CarPlayUiSyncAccessoryCommand.RequestLayout(),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncPresentationEvent.KnownCommand(
                CarPlayUiSyncCommandType.LAYOUT_CHANGE, exact,
                acknowledgedRequestEcho = CarPlayUiSyncAcknowledgedRequestEcho(exact),
            )
        }
    }

    @Test
    fun echoAttestationIsBoundToItsDataAndNeverReplayedByPublicProjection() {
        val data = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "main").wireData()
        val attested = CarPlayUiSyncPresentationEvent.KnownCommand(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, data,
            acknowledgedRequestEcho = CarPlayUiSyncAcknowledgedRequestEcho(data),
        )
        assertTrue(attested.echoesAcknowledgedRequestLayout)
        assertFalse(attested.copy(data = LinkedHashMap(data)).echoesAcknowledgedRequestLayout)
        Fixture(DISPLAY).use { fixture ->
            fixture.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, data, true)
            val retained = fixture.snapshot().events.single()
            assertTrue(retained.echoesAcknowledgedRequestLayout)
            val projected = retained.toPresentationEvent()
                as CarPlayUiSyncPresentationEvent.KnownCommand
            assertFalse(projected.echoesAcknowledgedRequestLayout)
        }
    }

    @Test
    fun reducerAppliesRequestEchoAuthorityOnlyToItsExactNewEcho() {
        val request = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "main-local-canvas")
        Fixture(DISPLAY).use { fixture ->
            fixture.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 8))
            fixture.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, request.wireData(), true)
            val snapshot = fixture.snapshot()
            val authority = requireNotNull(CarPlayUiSyncRequestEchoLayoutAuthority.authorize(
                snapshot.events.last(), request, "palette-signed",
            ))

            val plain = CarPlayUiSyncSemanticReducer(DISPLAY).reduce(snapshot)
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertNull("an echo alone is never a type 3", plain.state.desiredLayoutChange)
            assertTrue(plain.state.lastEndLayoutChangeCommand is
                CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho)

            val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
            val applied = reducer.reduce(snapshot, listOf(authority))
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertEquals(authority.layoutChange, applied.state.desiredLayoutChange)
            assertEquals(2L, applied.state.lastLayoutChangeCommandRevision)
            assertTrue(applied.state.desiredLayoutChangeFromRequestEcho)
            assertEquals(2L, applied.state.lastEndLayoutChangeCommandRevision)

            // Already applied: a replayed authority is a caller error, not a second choice.
            val replay = reducer.reduce(snapshot, listOf(authority))
                as CarPlayUiSyncSemanticReduceResult.Rejected
            assertEquals(CarPlayUiSyncSemanticReduceFailure.REQUEST_ECHO_AUTHORITY_MISMATCH,
                replay.failure)
            assertSame(applied.state, reducer.state())

            // A later received type 3 replaces it as an ordinary phone choice.
            fixture.known(CarPlayUiSyncCommandType.LAYOUT_CHANGE, layoutChangeData())
            val type3 = reducer.reduce(fixture.snapshot())
                as CarPlayUiSyncSemanticReduceResult.Applied
            assertFalse(type3.state.desiredLayoutChangeFromRequestEcho)
            assertEquals(3L, type3.state.lastLayoutChangeCommandRevision)
        }

        // The same authority cannot be applied to another history whose event 2 differs.
        Fixture(DISPLAY).use { source ->
            source.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 8))
            source.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, request.wireData(), true)
            val authority = requireNotNull(CarPlayUiSyncRequestEchoLayoutAuthority.authorize(
                source.snapshot().events.last(), request, "palette-signed",
            ))
            Fixture(DISPLAY).use { other ->
                other.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 8))
                other.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 9))
                val reducer = CarPlayUiSyncSemanticReducer(DISPLAY)
                val rejected = reducer.reduce(other.snapshot(), listOf(authority))
                    as CarPlayUiSyncSemanticReduceResult.Rejected
                assertEquals(CarPlayUiSyncSemanticReduceFailure.REQUEST_ECHO_AUTHORITY_MISMATCH,
                    rejected.failure)
                assertEquals(0L, reducer.state().revision)
            }
            Fixture(DISPLAY).use { unattested ->
                unattested.known(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 8))
                unattested.known(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, request.wireData())
                val rejected = CarPlayUiSyncSemanticReducer(DISPLAY)
                    .reduce(unattested.snapshot(), listOf(authority))
                    as CarPlayUiSyncSemanticReduceResult.Rejected
                assertEquals(CarPlayUiSyncSemanticReduceFailure.REQUEST_ECHO_AUTHORITY_MISMATCH,
                    rejected.failure)
            }
        }
    }

    private fun decoded(type: CarPlayUiSyncCommandType, data: Any?): CarPlayUiSyncSemanticCommand {
        val result = DECODER.decode(type.wireValue, data)
        assertTrue("expected decoded but was $result", result is CarPlayUiSyncSemanticDecodeResult.Decoded)
        return (result as CarPlayUiSyncSemanticDecodeResult.Decoded).command
    }

    private fun layoutChangeData(): Map<String, Any> = mapOf(
        "layoutID" to "layout-a",
        "activeComponents" to mapOf("zone" to "component"),
        "paletteID" to "palette-a",
    )

    private fun assertDecodeFailure(
        type: CarPlayUiSyncCommandType,
        data: Any?,
        expected: CarPlayUiSyncSemanticDecodeFailure,
    ) = assertRejected(DECODER.decode(type.wireValue, data), expected)

    private fun assertRejected(
        result: CarPlayUiSyncSemanticDecodeResult,
        expected: CarPlayUiSyncSemanticDecodeFailure,
    ) {
        assertTrue("expected rejected but was $result", result is CarPlayUiSyncSemanticDecodeResult.Rejected)
        assertEquals(expected, (result as CarPlayUiSyncSemanticDecodeResult.Rejected).failure)
    }

    private class Fixture(
        private val displayId: String,
        maximumRetainedEvents: Int = 128,
    ) : Closeable {
        private val hub = UltraPresentationHub(
            callbackExecutor = Executor(Runnable::run),
            limits = UltraPresentationHubLimits(
                maximumRetainedEventsPerDisplay = maximumRetainedEvents,
            ),
        )
        private val sink = hub.sink(displayId)

        fun known(
            type: CarPlayUiSyncCommandType,
            data: Any?,
            echoesAcknowledgedRequestLayout: Boolean = false,
        ) {
            sink.onPresentationEvent(
                CarPlayUiSyncPresentationEvent.KnownCommand(
                    type, data, acknowledgedRequestEcho = CarPlayUiSyncAcknowledgedRequestEcho(data)
                        .takeIf { echoesAcknowledgedRequestLayout },
                ),
            )
        }

        fun unknown(type: Int, data: Any?) {
            sink.onPresentationEvent(CarPlayUiSyncPresentationEvent.UnknownCommand(type, data))
        }

        fun snapshot(): UltraPresentationSnapshot = hub.snapshot(displayId)

        override fun close() {
            hub.close()
        }
    }

    private data class StableEntry(
        override val key: Any?,
        override val value: Any?,
    ) : Map.Entry<Any?, Any?>

    /** A hostile Map whose iterator never terminates and whose reported size is irrelevant. */
    private class EndlessMap : AbstractMap<Any?, Any?>() {
        val nextCalls = AtomicInteger()
        override val entries: Set<Map.Entry<Any?, Any?>> =
            object : AbstractSet<Map.Entry<Any?, Any?>>() {
                override val size: Int = Int.MAX_VALUE

                override fun iterator(): Iterator<Map.Entry<Any?, Any?>> =
                    object : Iterator<Map.Entry<Any?, Any?>> {
                        override fun hasNext(): Boolean = true

                        override fun next(): Map.Entry<Any?, Any?> {
                            val index = nextCalls.getAndIncrement()
                            return StableEntry("key$index", "value$index")
                        }
                    }
            }
    }

    /** A hostile List whose iterator never terminates and whose reported size is irrelevant. */
    private class EndlessList : AbstractList<Any?>() {
        val nextCalls = AtomicInteger()
        override val size: Int = Int.MAX_VALUE

        override fun get(index: Int): Any? {
            nextCalls.incrementAndGet()
            return mapOf("index" to index)
        }
    }

    /** Entry iteration is stable, but every keyed lookup observes a different live value. */
    private class LookupMutatingMap(values: List<Pair<Any?, Any?>>) : AbstractMap<Any?, Any?>() {
        val lookupCount = AtomicInteger()
        override val entries: Set<Map.Entry<Any?, Any?>> =
            values.mapTo(LinkedHashSet()) { (key, value) -> StableEntry(key, value) }

        override fun get(key: Any?): Any? {
            lookupCount.incrementAndGet()
            return "mutated-after-entry-snapshot"
        }
    }

    private companion object {
        const val DISPLAY = "cluster-primary"
        val DECODER = CarPlayUiSyncSemanticDecoder()
    }
}
