package com.shilapi.xcertplay.ultra.instrument

import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentCafCharacteristicPin
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSource
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentStateCode
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentThemeSignalAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneComponentAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneZoneAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.Display
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentSceneCompilerTest {
    private val compiler = InstrumentSceneCompiler()

    @Test fun exactCircularSpeedCompilesOnlyAsMarkedReference() {
        val spec = speedSpec()
        val result = compiler.compileDeclared(assignment(spec), 0, display(),
            DisplayDestination.CLUSTER, PixelSize(1600, 960)) as InstrumentSceneCompilation.Ready
        assertEquals(InstrumentReferenceVisual.SPEED_KMH, result.plan.visual)
        assertEquals(0, result.plan.slotPixels.xPixels)
        assertEquals(0, result.plan.slotPixels.yPixels)
        assertEquals(400, result.plan.slotPixels.widthPixels)
        assertEquals(400, result.plan.slotPixels.heightPixels)
        assertEquals("BENCH / NOT VEHICLE SAFE", result.plan.safetyMarker)
    }

    @Test fun displayDensityIsNeverAppliedAsAndroidDpi() {
        val spec = speedSpec()
        val lowDensity = ready(assignment(spec), display(density = 1.0),
            PixelSize(1600, 960))
        val highDensity = ready(assignment(spec), display(density = 3.0),
            PixelSize(1600, 960))
        assertEquals(lowDensity.slotPixels, highDensity.slotPixels)
    }

    @Test fun debugReferencePixelBudgetRejectsOversizedCluster() {
        val spec = speedSpec()
        assertEquals(InstrumentSceneUnsupportedReason.INVALID_PIXEL_GEOMETRY,
            (compiler.compileDeclared(assignment(spec), 0, display(),
                DisplayDestination.CLUSTER, PixelSize(1920, 1080)) as
                InstrumentSceneCompilation.Unsupported).reason)
    }

    @Test fun nativeProfileSpeedRangeDoesNotProveKmhOrMphTextRange() {
        val spec = speedSpec() // PROFILE_SPEED has signed m/s semantics.
        val result = ready(assignment(spec), display(nativeRangeMaximum = 70.0),
            PixelSize(800, 480))
        assertEquals(70.0, result.gaugeMaximum!!, 0.0)
        assertEquals(InstrumentReferenceVisual.SPEED_KMH, result.visual)
    }

    @Test fun displayLayoutZoneComponentAndSlotMustMatchExactSignedAddress() {
        val spec = speedSpec()
        val signed = assignment(spec)
        val attempts = listOf(
            compiler.compileDeclared(signed, 1, display(), DisplayDestination.CLUSTER,
                PixelSize(800, 480)),
            compiler.compileDeclared(signed, 0, display(id = "other"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)),
            compiler.compileDeclared(signed, 0, display(layoutId = "other"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)),
            compiler.compileDeclared(signed, 0, display(zoneId = "other"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)),
            compiler.compileDeclared(signed, 0, display(componentId = "other"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)),
            compiler.compileDeclared(signed, 0, display(positionId = "other"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)),
            compiler.compileDeclared(signed, 0, display(identifier = "rpm"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)),
        )
        assertTrue(attempts.all { it is InstrumentSceneCompilation.Unsupported })
    }

    @Test fun nonClusterAndUnknownNativeStyleAreUnsupported() {
        val spec = speedSpec()
        val signed = assignment(spec)
        assertEquals(InstrumentSceneUnsupportedReason.NOT_DEDICATED_CLUSTER,
            (compiler.compileDeclared(signed, 0, display(), DisplayDestination.MAIN,
                PixelSize(800, 480)) as InstrumentSceneCompilation.Unsupported).reason)
        assertEquals(InstrumentSceneUnsupportedReason.NOT_DEDICATED_CLUSTER,
            (compiler.compileDeclared(signed, 0, display(type = "centerConsole"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)) as
                InstrumentSceneCompilation.Unsupported).reason)
        assertTrue(compiler.compileDeclared(signed, 0, display(style = "spinner"),
            DisplayDestination.CLUSTER, PixelSize(800, 480)) is
            InstrumentSceneCompilation.Unsupported)
    }

    @Test fun speedUnitMustBeExplicitAndExact() {
        val invalidUnit = speedSpec(kmhUnit = "KM/H")
        assertEquals(InstrumentSceneUnsupportedReason.UNSUPPORTED_SIGNAL_SEMANTICS,
            (compiler.compileDeclared(assignment(invalidUnit), 0, display(),
                DisplayDestination.CLUSTER, PixelSize(800, 480)) as
                InstrumentSceneCompilation.Unsupported).reason)
        assertTrue(compiler.compileDeclared(assignment(speedSpec()), 0, display(),
            DisplayDestination.CLUSTER, PixelSize(800, 480), InstrumentReferenceVisual.RPM) is
            InstrumentSceneCompilation.Unsupported)
        assertEquals(InstrumentReferenceVisual.SPEED_MPH,
            ready(assignment(speedSpec()), display(), PixelSize(800, 480),
                InstrumentReferenceVisual.SPEED_MPH).visual)
    }

    @Test fun opaqueClipAndFractionalPixelEdgesRejectInsteadOfGuessing() {
        val spec = speedSpec()
        assertEquals(InstrumentSceneUnsupportedReason.OPAQUE_NATIVE_SEMANTICS,
            (compiler.compileDeclared(assignment(spec), 0, display(clipsToShape = null),
                DisplayDestination.CLUSTER, PixelSize(800, 480)) as
                InstrumentSceneCompilation.Unsupported).reason)
        assertEquals(InstrumentSceneUnsupportedReason.INVALID_PIXEL_GEOMETRY,
            (compiler.compileDeclared(assignment(spec), 0, display(slotWidth = 201),
                DisplayDestination.CLUSTER, PixelSize(801, 480)) as
                InstrumentSceneCompilation.Unsupported).reason)
    }

    @Test fun secondSlotCannotBeSilentlyPaintedInAnArbitraryOverlayOrder() {
        val spec = speedSpec()
        assertEquals(InstrumentSceneUnsupportedReason.OPAQUE_NATIVE_SEMANTICS,
            (compiler.compileDeclared(assignment(spec), 0, display(extraSlot = true),
                DisplayDestination.CLUSTER, PixelSize(800, 480)) as
                InstrumentSceneCompilation.Unsupported).reason)
    }

    @Test fun narrowCriticalIndicatorCanOnlyBecomeReferenceStateCard() {
        val spec = indicatorSpec()
        val result = ready(assignment(spec), display(identifier = "warning",
            kind = "indicator", slotStyle = "rectangular"), PixelSize(800, 480))
        assertEquals(InstrumentReferenceVisual.INDICATOR_STATE, result.visual)
        assertTrue(compiler.compileDeclared(assignment(spec), 0,
            display(identifier = "warning", kind = "indicator", indicatorStyle = "selector",
                slotStyle = "rectangular"), DisplayDestination.CLUSTER,
            PixelSize(800, 480)) is InstrumentSceneCompilation.Unsupported)
    }

    @Test fun rpmReferenceRequiresExplicitSignedRunningState() {
        val running = rpmSpec(includeRunning = true)
        assertEquals(InstrumentReferenceVisual.RPM,
            ready(assignment(running), display(identifier = "rpm"),
                PixelSize(800, 480)).visual)
        val noRunning = rpmSpec(includeRunning = false)
        assertEquals(InstrumentSceneUnsupportedReason.UNSUPPORTED_SIGNAL_SEMANTICS,
            (compiler.compileDeclared(assignment(noRunning), 0, display(identifier = "rpm"),
                DisplayDestination.CLUSTER, PixelSize(800, 480)) as
                InstrumentSceneCompilation.Unsupported).reason)
    }

    private fun ready(
        signed: CompiledInstrumentSignalDisplayAssignment,
        display: Display,
        pixels: PixelSize,
        speedUnit: InstrumentReferenceVisual = InstrumentReferenceVisual.SPEED_KMH,
    ) = (compiler.compileDeclared(signed, 0, display, DisplayDestination.CLUSTER,
        pixels, speedUnit) as InstrumentSceneCompilation.Ready).plan

    private fun assignment(spec: InstrumentSignalBundleSpec) =
        CompiledInstrumentSignalDisplayAssignment(0, "cluster-display", spec)

    private fun address(identifier: String) = InstrumentThemeSignalAddress(
        identifier, ZoneRegion.ZONE_1,
        CarPlayThemeSceneSlotAddress(
            CarPlayThemeSceneComponentAddress(
                CarPlayThemeSceneZoneAddress(CarPlayThemeSceneLayoutAddress(0, "main"),
                    0, "cluster-zone"),
                0, "primary",
            ),
            0, "dial",
        ),
    )

    private fun speedSpec(kmhUnit: String = "km/h") = InstrumentSignalBundleSpec(
        address("speed"), InstrumentSignalBundleShape.SPEED_TRIPLET,
        listOf(
            numeric(InstrumentSignalRole.SPEED, 100, InstrumentNumericMeaning.PROFILE_SPEED,
                "m/s"),
            numeric(InstrumentSignalRole.SPEED_KMH, 101,
                InstrumentNumericMeaning.KILOMETRES_PER_HOUR, kmhUnit),
            numeric(InstrumentSignalRole.SPEED_MPH, 102,
                InstrumentNumericMeaning.MILES_PER_HOUR, "mph"),
        ),
        1_000,
    )

    private fun indicatorSpec() = InstrumentSignalBundleSpec(
        address("warning"), InstrumentSignalBundleShape.INDICATOR_STATE,
        listOf(InstrumentSignalSource(
            InstrumentSignalRole.INDICATOR_STATE,
            InstrumentCafCharacteristicPin(2, 200, 103, 1003, CafCharacteristicFormat.UINT8),
            InstrumentSignalSemantics.State(listOf(InstrumentStateCode(0L, "off"),
                InstrumentStateCode(1L, "on"))),
        )),
        1_000,
    )

    private fun rpmSpec(includeRunning: Boolean) = InstrumentSignalBundleSpec(
        address("rpm"), InstrumentSignalBundleShape.RPM_VALUE_AND_STATE,
        listOf(
            numeric(InstrumentSignalRole.RPM_VALUE, 103,
                InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE, "rpm"),
            InstrumentSignalSource(
                InstrumentSignalRole.RPM_STATE,
                InstrumentCafCharacteristicPin(2, 200, 104, 1004,
                    CafCharacteristicFormat.UINT8),
                InstrumentSignalSemantics.State(listOf(InstrumentStateCode(0L, "stopped")) +
                    if (includeRunning) listOf(InstrumentStateCode(1L, "running"))
                    else emptyList()),
            ),
        ),
        1_000,
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

    private fun display(
        id: String = "cluster-display",
        type: String = "cluster",
        density: Double = 1.0,
        layoutId: String = "main",
        zoneId: String = "cluster-zone",
        componentId: String = "primary",
        positionId: String = "dial",
        identifier: String = "speed",
        style: String = "circular",
        kind: String = "gauge",
        indicatorStyle: String = "critical",
        slotStyle: String = "circular",
        clipsToShape: Boolean? = false,
        slotWidth: Int = 200,
        extraSlot: Boolean = false,
        nativeRangeMaximum: Double = 300.0,
    ): Display {
        val gaugeStyle = if (style == "circular") mapOf("circular" to mapOf("config" to mapOf(
            "dataIdentifier" to identifier,
            "range" to listOf(0.0, nativeRangeMaximum),
            "iconName" to "dial", "minLabel" to "0", "minLabelColor" to "white",
            "maxLabel" to "300", "maxLabelColor" to "white",
            "tintColors" to emptyList<Any>(),
            "backgroundTintColors" to emptyList<Any>(),
        ))) else mapOf(style to emptyMap<String, Any>())
        val kindPayload = if (kind == "gauge") mapOf("configuration" to mapOf(
            "style" to gaugeStyle, "rendersLocally" to true,
            "visibilityConfiguration" to emptyMap<String, Any>(),
        )) else mapOf("configuration" to mapOf(
            "dataIdentifier" to identifier,
            "style" to mapOf(indicatorStyle to if (indicatorStyle == "message")
                emptyMap<String, Any>() else mapOf("configuration" to emptyMap<String, Any>())),
            "presentationEdge" to "bottom", "containerInsets" to emptyMap<String, Any>(),
        ))
        val instrument = linkedMapOf<String, Any?>(
            "kind" to mapOf(kind to kindPayload), "backgroundColor" to "black",
        ).also { if (clipsToShape != null) it["clipsToShape"] = clipsToShape }
        val slots = listOf(mapOf(
            "style" to mapOf(slotStyle to emptyMap<String, Any>()),
            "frame" to listOf(listOf(0, 0), listOf(slotWidth, 200)),
            "zIndex" to 0, "positionId" to positionId,
            "instrument" to instrument,
        )) + if (extraSlot) listOf(mapOf(
            "style" to mapOf("rectangular" to emptyMap<String, Any>()),
            "frame" to listOf(listOf(200, 0), listOf(100, 100)),
            "zIndex" to 1, "positionId" to "overlay",
        )) else emptyList()
        return CarPlayThemeSchema.parseDisplay(mapOf(
            "id" to id, "type" to type, "size" to listOf(800, 480), "density" to density,
            "allowManufacturerAppIcon" to false, "requiresDarkAppearance" to false,
            "layouts" to listOf(mapOf(
                "id" to layoutId, "layoutType" to "primary",
                "backgroundColor" to "black", "safeAreaInsets" to emptyMap<String, Any>(),
                "driveModeThemeConfigurations" to emptyMap<String, Any>(),
                "zones" to listOf(mapOf(
                    "id" to zoneId, "frame" to listOf(listOf(0, 0), listOf(800, 480)),
                    "type" to mapOf("single" to emptyMap<String, Any>()),
                    "zoneRegion" to "zone1",
                    "components" to listOf(mapOf(
                        "id" to componentId, "type" to "primary", "size" to listOf(800, 480),
                        "slots" to slots,
                    )),
                )),
            )),
        ))
    }
}
