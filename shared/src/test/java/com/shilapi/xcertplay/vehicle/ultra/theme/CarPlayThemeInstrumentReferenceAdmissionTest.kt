package com.shilapi.xcertplay.vehicle.ultra.theme

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentCafCharacteristicPin
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSource
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentStateCode
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentThemeSignalAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayThemeInstrumentReferenceAdmissionTest {
    private val compiler = CarPlayThemeProgramCompiler()
    private val selected = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))

    @Test fun onlyExactSignedClusterGaugeIsAdmittedAndPlainCompileRemainsOpaque() {
        val display = nativeDisplay()
        val spec = speedSpec()
        val plain = compiler.compile(display, selected)
        assertTrue(plain.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_INSTRUMENT_CONTENT
        })
        assertNull(compiler.assessExecutableStaticSubset(display, 0, 0, selected).proof)

        val assessed = compiler.assessExecutableStaticSubset(display, 0, 0, selected,
            instrumentReferences = listOf(spec))
        val proof = requireNotNull(assessed.proof)
        assertTrue(assessed.issues.isEmpty())
        assertTrue(proof.videoSlots.isEmpty())
        val native = proof.instrumentReferenceSlots.single()
        assertEquals(spec.address.slot, native.address)
        assertEquals(InstrumentKindCase.GAUGE, native.kind)
        assertEquals("speed", native.dataIdentifier)
        assertEquals(CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER, native.stage)
        @Suppress("UNCHECKED_CAST")
        try {
            (proof.instrumentReferenceSlots as MutableList<CarPlayThemeExecutableInstrumentReferenceSlot>)
                .clear()
            throw AssertionError("Reference proof list must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun foreignCrosswalkUnknownNativeStyleOrExtraSlotCannotBecomeStaticProof() {
        val mismatched = speedSpec("foreign-speed")
        val cases = listOf(
            nativeDisplay() to listOf(mismatched),
            nativeDisplay(type = "centerConsole") to listOf(speedSpec()),
            nativeDisplay(style = "spinner") to listOf(speedSpec()),
            nativeDisplay(clipsToShape = null) to listOf(speedSpec()),
            nativeDisplay(extraSlot = true) to listOf(speedSpec()),
            nativeDisplay(identifier = "foreign-speed") to listOf(speedSpec()),
            nativeDisplay() to listOf(speedSpec(), speedSpec()),
        )
        cases.forEach { (display, specs) ->
            val assessed = compiler.assessExecutableStaticSubset(display, 0, 0, selected,
                instrumentReferences = specs)
            assertNull(assessed.proof)
            assertFalse(assessed.issues.isEmpty())
        }
    }

    @Test fun speedReferencePreservesLegacyNativeAliasButRequiresExactConvertedRoleAliases() {
        val display = nativeDisplay()
        // PROFILE_SPEED remains a Profile-defined native unit; the theme compiler must not
        // silently force the legacy string-only declaration to m/s.
        assertNotNull(compiler.assessExecutableStaticSubset(display, 0, 0, selected,
            instrumentReferences = listOf(speedSpec(units = listOf("ft/s", "km/h", "mph")))).proof)
        listOf(
            listOf("m/s", "mph", "mph"),
            listOf("m/s", "km/h", "km/h"),
        ).forEach { units ->
            assertNull(compiler.assessExecutableStaticSubset(display, 0, 0, selected,
                instrumentReferences = listOf(speedSpec(units = units))).proof)
        }
    }

    @Test fun onlyExactCriticalIndicatorReferenceAdmitted() {
        val display = nativeDisplay(kind = "indicator", identifier = "warning",
            slotStyle = "rectangular")
        val spec = indicatorSpec()
        val proof = requireNotNull(compiler.assessExecutableStaticSubset(display, 0, 0, selected,
            instrumentReferences = listOf(spec)).proof)
        assertEquals(InstrumentKindCase.INDICATOR,
            proof.instrumentReferenceSlots.single().kind)
        assertNull(compiler.assessExecutableStaticSubset(nativeDisplay(kind = "indicator",
            identifier = "warning", slotStyle = "rectangular", indicatorStyle = "selector"),
            0, 0, selected, instrumentReferences = listOf(spec)).proof)
    }

    private fun slotAddress() = CarPlayThemeSceneSlotAddress(
        CarPlayThemeSceneComponentAddress(
            CarPlayThemeSceneZoneAddress(CarPlayThemeSceneLayoutAddress(0, "main"),
                0, "cluster-zone"),
            0, "primary",
        ),
        0, "dial",
    )

    private fun speedSpec(
        identifier: String = "speed",
        units: List<String> = listOf("m/s", "km/h", "mph"),
    ) = InstrumentSignalBundleSpec(
        InstrumentThemeSignalAddress(identifier, ZoneRegion.ZONE_1, slotAddress()),
        InstrumentSignalBundleShape.SPEED_TRIPLET,
        listOf(
            numeric(InstrumentSignalRole.SPEED, 100, InstrumentNumericMeaning.PROFILE_SPEED,
                units[0]),
            numeric(InstrumentSignalRole.SPEED_KMH, 101,
                InstrumentNumericMeaning.KILOMETRES_PER_HOUR, units[1]),
            numeric(InstrumentSignalRole.SPEED_MPH, 102,
                InstrumentNumericMeaning.MILES_PER_HOUR, units[2]),
        ), 1000,
    )

    private fun indicatorSpec() = InstrumentSignalBundleSpec(
        InstrumentThemeSignalAddress("warning", ZoneRegion.ZONE_1, slotAddress()),
        InstrumentSignalBundleShape.INDICATOR_STATE,
        listOf(InstrumentSignalSource(InstrumentSignalRole.INDICATOR_STATE,
            InstrumentCafCharacteristicPin(2, 200, 103, 1003, CafCharacteristicFormat.UINT8),
            InstrumentSignalSemantics.State(listOf(
                InstrumentStateCode(0L, "off"), InstrumentStateCode(1L, "on"),
            )))), 1000,
    )

    private fun numeric(
        role: InstrumentSignalRole,
        iid: Long,
        meaning: InstrumentNumericMeaning,
        units: String,
    ) = InstrumentSignalSource(role,
        InstrumentCafCharacteristicPin(2, 200, iid, iid + 900,
            CafCharacteristicFormat.FLOAT),
        InstrumentSignalSemantics.Numeric(meaning, units))

    private fun nativeDisplay(
        type: String = "cluster",
        style: String = "circular",
        kind: String = "gauge",
        indicatorStyle: String = "critical",
        slotStyle: String = "circular",
        identifier: String = "speed",
        clipsToShape: Boolean? = false,
        extraSlot: Boolean = false,
    ): Display {
        val gaugeStyle = if (style == "circular") mapOf("circular" to mapOf("config" to mapOf(
            "dataIdentifier" to identifier, "range" to listOf(0.0, 300.0),
            "iconName" to "dial", "minLabel" to "0", "minLabelColor" to "white",
            "maxLabel" to "300", "maxLabelColor" to "white",
            "tintColors" to emptyList<Any>(), "backgroundTintColors" to emptyList<Any>(),
        ))) else mapOf(style to emptyMap<String, Any>())
        val payload = if (kind == "gauge") mapOf("configuration" to mapOf(
            "style" to gaugeStyle, "rendersLocally" to true,
            "visibilityConfiguration" to emptyMap<String, Any>(),
        )) else mapOf("configuration" to mapOf(
            "dataIdentifier" to identifier,
            "style" to mapOf(indicatorStyle to mapOf("configuration" to emptyMap<String, Any>())),
            "presentationEdge" to "bottom", "containerInsets" to emptyMap<String, Any>(),
        ))
        val instrument = linkedMapOf<String, Any?>(
            "kind" to mapOf(kind to payload), "backgroundColor" to "#000000",
        ).also { if (clipsToShape != null) it["clipsToShape"] = clipsToShape }
        val slots = listOf(mapOf(
            "style" to mapOf(slotStyle to emptyMap<String, Any>()),
            "frame" to listOf(listOf(0, 0), listOf(200, 200)),
            "zIndex" to 0, "positionId" to "dial", "instrument" to instrument,
        )) + if (extraSlot) listOf(mapOf(
            "style" to mapOf("rectangular" to emptyMap<String, Any>()),
            "frame" to listOf(listOf(200, 0), listOf(100, 100)),
            "zIndex" to 1, "positionId" to "overlay",
        )) else emptyList()
        return CarPlayThemeSchema.parseDisplay(mapOf(
            "id" to "cluster-display", "type" to type, "size" to listOf(800, 480),
            "density" to 1, "allowManufacturerAppIcon" to false,
            "requiresDarkAppearance" to false,
            "layouts" to listOf(mapOf(
                "id" to "main", "layoutType" to "primary", "backgroundColor" to "#000000",
                "safeAreaInsets" to listOf(0, 0, 0, 0),
                "driveModeThemeConfigurations" to emptyMap<String, Any>(),
                "zones" to listOf(mapOf(
                    "id" to "cluster-zone", "frame" to listOf(listOf(0, 0), listOf(800, 480)),
                    "type" to mapOf("single" to emptyMap<String, Any>()),
                    "zoneRegion" to "zone1",
                    "components" to listOf(mapOf(
                        "id" to "primary", "type" to "primary", "size" to listOf(800, 480),
                        "slots" to slots,
                    )),
                )),
            )),
        ))
    }
}
