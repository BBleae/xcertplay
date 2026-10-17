package com.shilapi.xcertplay.ultra.instrument

import com.shilapi.xcertplay.AuthorizedUltraProfileProvisioning
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.ultra.AxisAlignedPixelAffine
import com.shilapi.xcertplay.ultra.CarPlayLogicalPixelMapper
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.NegativeExtentPolicy
import com.shilapi.xcertplay.ultra.PixelEdgeQuantization
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalPoint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CircularGaugeStylePayload
import com.shilapi.xcertplay.vehicle.ultra.theme.ComponentType
import com.shilapi.xcertplay.vehicle.ultra.theme.Display
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import com.shilapi.xcertplay.vehicle.ultra.theme.GaugeInstrumentKindPayload
import com.shilapi.xcertplay.vehicle.ultra.theme.GaugeStyleCase
import com.shilapi.xcertplay.vehicle.ultra.theme.IndicatorInstrumentKindPayload
import com.shilapi.xcertplay.vehicle.ultra.theme.IndicatorStyleCase
import com.shilapi.xcertplay.vehicle.ultra.theme.LayoutType
import com.shilapi.xcertplay.vehicle.ultra.theme.SlotStyleCase
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneTypeCase
import com.shilapi.xcertplay.vehicle.ultra.theme.logicalFrame
import com.shilapi.xcertplay.vehicle.ultra.theme.logicalSize

/** A diagnostic raster is not the native CarPlayAssetUI Gauge/Indicator renderer. */
internal enum class InstrumentReferenceVisual {
    SPEED_KMH,
    SPEED_MPH,
    RPM,
    INDICATOR_STATE,
}

internal class InstrumentReferenceScenePlan internal constructor(
    val destination: DisplayDestination,
    val viewportPixels: PixelSize,
    val slotPixels: UltraPixelRect,
    val spec: InstrumentSignalBundleSpec,
    val visual: InstrumentReferenceVisual,
    /** Native package range has no proved unit affinity to km/h, mph, or RPM readings. */
    val gaugeMinimum: Double?,
    val gaugeMaximum: Double?,
) {
    /** Always visible on the physical reference frame, including unavailable frames. */
    val safetyMarker: String = "BENCH / NOT VEHICLE SAFE"

    init {
        require(destination.role == DisplayDestinationRole.CLUSTER)
        require(viewportPixels.widthPixels in 240..4096 &&
            viewportPixels.heightPixels in 80..2160 &&
            viewportPixels.widthPixels.toLong() * viewportPixels.heightPixels <= 1_600_000L)
        require(slotPixels.widthPixels >= 80 && slotPixels.heightPixels >= 80 &&
            slotPixels.xPixels >= 0 && slotPixels.yPixels >= 0 &&
            slotPixels.xPixels.toLong() + slotPixels.widthPixels <= viewportPixels.widthPixels &&
            slotPixels.yPixels.toLong() + slotPixels.heightPixels <= viewportPixels.heightPixels)
        require((visual == InstrumentReferenceVisual.INDICATOR_STATE) ==
            (gaugeMinimum == null && gaugeMaximum == null))
        if (gaugeMinimum != null && gaugeMaximum != null) {
            require(gaugeMinimum.isFinite() && gaugeMaximum.isFinite() &&
                gaugeMinimum < gaugeMaximum)
        }
    }
}

internal enum class InstrumentSceneUnsupportedReason {
    UNAUTHORIZED_PROFILE,
    NO_SIGNED_ASSIGNMENT,
    WRONG_DISPLAY_OR_SLOT,
    NOT_DEDICATED_CLUSTER,
    OPAQUE_NATIVE_SEMANTICS,
    UNKNOWN_NATIVE_STYLE,
    UNSUPPORTED_SIGNAL_SEMANTICS,
    INVALID_PIXEL_GEOMETRY,
}

internal sealed interface InstrumentSceneCompilation {
    class Ready(val plan: InstrumentReferenceScenePlan) : InstrumentSceneCompilation
    data class Unsupported(val reason: InstrumentSceneUnsupportedReason) :
        InstrumentSceneCompilation
}

/**
 * Debug reference compiler. A live issuer-backed provisioning lease and exact verified Profile
 * select the assignment. No unsigned caller-supplied Display, layout, or slot is accepted by
 * [compileSigned]. This deliberately does not claim OEM asset fidelity or instrument readiness.
 */
internal class InstrumentSceneCompiler {
    fun compileSigned(
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
        profileDisplayIndex: Int,
        slotAddress: com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress,
        destination: DisplayDestination,
        viewportPixels: PixelSize,
        speedUnit: InstrumentReferenceVisual = InstrumentReferenceVisual.SPEED_KMH,
    ): InstrumentSceneCompilation {
        if (!provisioning.isLive) {
            return unsupported(InstrumentSceneUnsupportedReason.UNAUTHORIZED_PROFILE)
        }
        val assignments = runCatching { provisioning.instrumentAssignmentsFor(profile) }
            .getOrNull() ?: return unsupported(InstrumentSceneUnsupportedReason.UNAUTHORIZED_PROFILE)
        val assignment = assignments.singleOrNull {
            it.profileDisplayIndex == profileDisplayIndex && it.spec.address.slot == slotAddress
        } ?: return unsupported(InstrumentSceneUnsupportedReason.NO_SIGNED_ASSIGNMENT)
        val display = runCatching {
            val bytes = requireNotNull(profile.candidate.content(
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
            ))
            CarPlayThemeSchema.parseArcPackageJson(bytes).displays.getOrNull(profileDisplayIndex)
        }.getOrNull() ?: return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        if (!provisioning.isLive) {
            return unsupported(InstrumentSceneUnsupportedReason.UNAUTHORIZED_PROFILE)
        }
        return compileDeclared(assignment, profileDisplayIndex, display, destination,
            viewportPixels, speedUnit)
    }

    /** Structural test seam; production activation must call [compileSigned]. */
    internal fun compileDeclared(
        assignment: CompiledInstrumentSignalDisplayAssignment,
        profileDisplayIndex: Int,
        display: Display,
        destination: DisplayDestination,
        viewportPixels: PixelSize,
        speedUnit: InstrumentReferenceVisual = InstrumentReferenceVisual.SPEED_KMH,
    ): InstrumentSceneCompilation = runCatching {
        if (display.type != DisplayType.CLUSTER &&
            display.type != DisplayType.SECONDARY_CLUSTER ||
            destination.role != DisplayDestinationRole.CLUSTER
        ) return unsupported(InstrumentSceneUnsupportedReason.NOT_DEDICATED_CLUSTER)
        if (assignment.profileDisplayIndex != profileDisplayIndex ||
            assignment.profileDisplayId != display.id
        ) return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        val spec = assignment.spec
        val address = spec.address.slot
        val layout = display.layouts.getOrNull(address.component.zone.layout.layoutIndex)
            ?: return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        val zone = layout.zones.getOrNull(address.component.zone.zoneIndex)
            ?: return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        val component = zone.components.getOrNull(address.component.componentIndex)
            ?: return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        val slot = component.slots.getOrNull(address.slotIndex)
            ?: return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        if (layout.id != address.component.zone.layout.layoutId ||
            zone.id != address.component.zone.zoneId ||
            zone.zoneRegion != spec.address.zoneRegion ||
            component.id != address.component.componentId ||
            slot.positionId != address.positionId
        ) return unsupported(InstrumentSceneUnsupportedReason.WRONG_DISPLAY_OR_SLOT)
        if (layout.layoutType != LayoutType.PRIMARY || layout.zones.size != 1 ||
            zone.zoneType.case != ZoneTypeCase.SINGLE || zone.components.size != 1 ||
            component.slots.size != 1 || slot.zIndex != 0 ||
            component.type != ComponentType.PRIMARY ||
            slot.style.payload.value != emptyMap<String, Any?>() ||
            component.clippingBehavior != null ||
            slot.safeAreaInsets != null || slot.requirement != null ||
            slot.overrides != null || slot.extensions.isNotEmpty() ||
            slot.instrument?.let { instrument ->
                instrument.blendMode != null || instrument.layoutConfiguration != null ||
                    instrument.configuration != null || instrument.backgroundContent != null ||
                    instrument.extensions.isNotEmpty() || instrument.clipsToShape?.value != false
            } != false
        ) return unsupported(InstrumentSceneUnsupportedReason.OPAQUE_NATIVE_SEMANTICS)

        val typed = requireNotNull(slot.instrument).kind.typedPayload
        val visual = when (typed) {
            is GaugeInstrumentKindPayload -> {
                if (typed.configuration.style.case != GaugeStyleCase.CIRCULAR) {
                    return unsupported(InstrumentSceneUnsupportedReason.UNKNOWN_NATIVE_STYLE)
                }
                compileGauge(spec, typed, slot.style.case, speedUnit)
            }
            is IndicatorInstrumentKindPayload -> {
                if (typed.configuration.style.case != IndicatorStyleCase.CRITICAL) {
                    return unsupported(InstrumentSceneUnsupportedReason.UNKNOWN_NATIVE_STYLE)
                }
                compileIndicator(spec, typed, slot.style.case)
            }
            else -> return unsupported(InstrumentSceneUnsupportedReason.UNKNOWN_NATIVE_STYLE)
        } ?: return unsupported(InstrumentSceneUnsupportedReason.UNSUPPORTED_SIGNAL_SEMANTICS)
        if (visual == InstrumentReferenceVisual.SPEED_KMH ||
            visual == InstrumentReferenceVisual.SPEED_MPH
        ) {
            if (speedUnit != visual) return unsupported(
                InstrumentSceneUnsupportedReason.UNSUPPORTED_SIGNAL_SEMANTICS)
        }
        val gauge = (typed as? GaugeInstrumentKindPayload)?.configuration?.style
            ?.typedPayload as? CircularGaugeStylePayload
        val logicalDisplay = display.logicalSize()
        val zoneFrame = zone.logicalFrame()
        val componentSize = component.logicalSize()
        val slotFrame = slot.logicalFrame()
        if (logicalDisplay.width <= 0.0 || logicalDisplay.height <= 0.0 ||
            zoneFrame.origin.x < 0.0 || zoneFrame.origin.y < 0.0 ||
            zoneFrame.size.width <= 0.0 || zoneFrame.size.height <= 0.0 ||
            zoneFrame.origin.x + zoneFrame.size.width > logicalDisplay.width ||
            zoneFrame.origin.y + zoneFrame.size.height > logicalDisplay.height ||
            componentSize.width <= 0.0 || componentSize.height <= 0.0 ||
            componentSize.width > zoneFrame.size.width ||
            componentSize.height > zoneFrame.size.height ||
            slotFrame.origin.x < 0.0 || slotFrame.origin.y < 0.0 ||
            slotFrame.size.width <= 0.0 || slotFrame.size.height <= 0.0 ||
            slotFrame.origin.x + slotFrame.size.width > componentSize.width ||
            slotFrame.origin.y + slotFrame.size.height > componentSize.height
        ) return unsupported(InstrumentSceneUnsupportedReason.INVALID_PIXEL_GEOMETRY)
        if (viewportPixels.widthPixels !in 240..4096 ||
            viewportPixels.heightPixels !in 80..2160 ||
            viewportPixels.widthPixels.toLong() * viewportPixels.heightPixels > 1_600_000L
        ) return unsupported(InstrumentSceneUnsupportedReason.INVALID_PIXEL_GEOMETRY)
        val mapper = CarPlayLogicalPixelMapper(
            AxisAlignedPixelAffine(
                viewportPixels.widthPixels / logicalDisplay.width,
                viewportPixels.heightPixels / logicalDisplay.height,
                0.0, 0.0,
            ),
            NegativeExtentPolicy.REJECT,
            PixelEdgeQuantization.REQUIRE_INTEGRAL,
            PixelEdgeQuantization.REQUIRE_INTEGRAL,
        )
        val frame = mapper.mapFrame(CarPlayLogicalRect(
            CarPlayLogicalPoint(
                zoneFrame.origin.x + slotFrame.origin.x,
                zoneFrame.origin.y + slotFrame.origin.y,
            ),
            slotFrame.size,
        ))
        if (frame.widthPixels < 80 || frame.heightPixels < 80 ||
            frame.xPixels < 0 || frame.yPixels < 0 ||
            frame.xPixels.toLong() + frame.widthPixels > viewportPixels.widthPixels ||
            frame.yPixels.toLong() + frame.heightPixels > viewportPixels.heightPixels
        ) return unsupported(InstrumentSceneUnsupportedReason.INVALID_PIXEL_GEOMETRY)
        InstrumentSceneCompilation.Ready(InstrumentReferenceScenePlan(
            destination, viewportPixels, frame, spec, visual,
            gauge?.config?.range?.lowerBound, gauge?.config?.range?.upperBound,
        ))
    }.getOrElse { unsupported(InstrumentSceneUnsupportedReason.INVALID_PIXEL_GEOMETRY) }

    private fun compileGauge(
        spec: InstrumentSignalBundleSpec,
        payload: GaugeInstrumentKindPayload,
        slotStyle: SlotStyleCase,
        speedUnit: InstrumentReferenceVisual,
    ): InstrumentReferenceVisual? {
        val gauge = payload.configuration
        if (!gauge.rendersLocally || gauge.extensions.isNotEmpty() ||
            gauge.visibilityConfiguration.value != emptyMap<String, Any?>() ||
            slotStyle != SlotStyleCase.CIRCULAR ||
            gauge.style.case != GaugeStyleCase.CIRCULAR
        ) return null
        val circular = gauge.style.typedPayload as? CircularGaugeStylePayload ?: return null
        val config = circular.config
        if (config.dataIdentifier.value != spec.address.dataIdentifier ||
            !config.range.lowerBound.isFinite() || !config.range.upperBound.isFinite() ||
            config.range.lowerBound >= config.range.upperBound ||
            config.extensions.isNotEmpty() ||
            config.iconName.value !is String || config.minLabel.value !is String ||
            config.maxLabel.value !is String ||
            config.minLabelColor.value !is String ||
            config.maxLabelColor.value !is String ||
            config.tintColors.value != emptyList<Any?>() ||
            config.backgroundTintColors.value != emptyList<Any?>()
        ) return null
        return when (spec.shape) {
            InstrumentSignalBundleShape.SPEED_TRIPLET -> {
                val native = spec.numericMeaning(InstrumentSignalRole.SPEED)
                val kmh = spec.numericMeaning(InstrumentSignalRole.SPEED_KMH)
                val mph = spec.numericMeaning(InstrumentSignalRole.SPEED_MPH)
                if (native?.first != InstrumentNumericMeaning.PROFILE_SPEED ||
                    kmh?.first != InstrumentNumericMeaning.KILOMETRES_PER_HOUR ||
                    kmh?.second != "km/h" ||
                    mph?.first != InstrumentNumericMeaning.MILES_PER_HOUR || mph?.second != "mph"
                ) null else when (speedUnit) {
                    InstrumentReferenceVisual.SPEED_KMH,
                    InstrumentReferenceVisual.SPEED_MPH -> speedUnit
                    else -> null
                }
            }
            InstrumentSignalBundleShape.RPM_VALUE_AND_STATE -> {
                val rpm = spec.numericMeaning(InstrumentSignalRole.RPM_VALUE)
                val states = spec.sources.singleOrNull {
                    it.role == InstrumentSignalRole.RPM_STATE
                }?.semantics as? InstrumentSignalSemantics.State
                if (rpm?.first == InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE &&
                    rpm?.second == "rpm" &&
                    states?.codes?.any { it.name == "running" } == true
                ) InstrumentReferenceVisual.RPM else null
            }
            else -> null
        }
    }

    private fun compileIndicator(
        spec: InstrumentSignalBundleSpec,
        payload: IndicatorInstrumentKindPayload,
        slotStyle: SlotStyleCase,
    ): InstrumentReferenceVisual? {
        val config = payload.configuration
        if (spec.shape != InstrumentSignalBundleShape.INDICATOR_STATE ||
            config.dataIdentifier.value != spec.address.dataIdentifier ||
            config.extensions.isNotEmpty() ||
            config.style.case != IndicatorStyleCase.CRITICAL ||
            config.style.associatedConfiguration?.value != emptyMap<String, Any?>() ||
            config.presentationEdge.value != "bottom" ||
            config.containerInsets.value != emptyMap<String, Any?>() ||
            slotStyle != SlotStyleCase.RECTANGULAR ||
            spec.sources.singleOrNull()?.semantics !is InstrumentSignalSemantics.State
        ) return null
        return InstrumentReferenceVisual.INDICATOR_STATE
    }

    private fun InstrumentSignalBundleSpec.numericMeaning(role: InstrumentSignalRole):
        Pair<InstrumentNumericMeaning, String>? =
        (sources.singleOrNull { it.role == role }?.semantics as?
            InstrumentSignalSemantics.Numeric)?.let { it.meaning to it.exactCafUnits }

    private fun unsupported(reason: InstrumentSceneUnsupportedReason) =
        InstrumentSceneCompilation.Unsupported(reason)
}
