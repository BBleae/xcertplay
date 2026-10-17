package com.shilapi.xcertplay.vehicle.ultra.theme

import java.util.Collections

/**
 * Operating mode for the evidence-only theme-to-scene reducer.
 *
 * The default is deliberately [DISABLED]. [DIAGNOSTIC_ONLY] creates an abstract plan for tests and
 * future renderer work; it is not evidence that a renderer, surface binding, or output backend is
 * available.
 */
enum class CarPlayThemeSceneReducerMode {
    DISABLED,
    DIAGNOSTIC_ONLY,
}

/** The reducer never converts theme geometry to Android dp, pixels, or density-scaled units. */
enum class CarPlayThemeSceneCoordinateSpace {
    /** Legacy/raw reference whose field-specific parent has not been selected. */
    THEME_LOGICAL_OPAQUE,

    /** Scene nodes carry explicit display/zone/component parent-domain metadata. */
    THEME_LOGICAL_PARENT_DOMAINS_RESOLVED,
}

/** Explicit parent coordinate domain for geometry that the reducer has resolved. */
enum class CarPlayThemeSceneGeometryDomain {
    DISPLAY_LOGICAL,
    ZONE_LOCAL_LOGICAL,
    COMPONENT_LOCAL_LOGICAL,
    /** Custom-mask CGRects are confirmed, but their parent coordinate domain is not. */
    CUSTOM_MASK_UNKNOWN_LOGICAL,
}

/** Confirmed high-level ordering from the CarPlay asset renderer. */
enum class CarPlayThemeSceneStage {
    LAYOUT_BACKGROUND,
    ZONE_PLATTER,
    ACTIVE_PRIMARY_COMPONENT,
    ACTIVE_PRIMARY_SLOT_Z_ORDER,
    ZONE_OVERLAY_COMPONENT,
    TRACKING_OVERLAY,
    APPEARANCE_OVERLAY,
}

/** Stable ordinal address; identifiers are descriptive and are not assumed to be unique. */
data class CarPlayThemeSceneLayoutAddress(
    val layoutIndex: Int,
    val layoutId: String,
)

data class CarPlayThemeSceneZoneAddress(
    val layout: CarPlayThemeSceneLayoutAddress,
    val zoneIndex: Int,
    val zoneId: String,
)

data class CarPlayThemeSceneComponentAddress(
    val zone: CarPlayThemeSceneZoneAddress,
    val componentIndex: Int,
    val componentId: String,
)

data class CarPlayThemeSceneSlotAddress(
    val component: CarPlayThemeSceneComponentAddress,
    /** Original package order, retained even when the plan is stably sorted by z-index. */
    val slotIndex: Int,
    val positionId: String,
)

/**
 * An opaque value already frozen by [CarPlayThemeSchema]. No shape is inferred by this reducer.
 * Each read is a defensive deep copy supplied by [CarPlayThemeOpaqueValue].
 */
class CarPlayThemeSceneOpaqueReference internal constructor(
    private val source: CarPlayThemeOpaqueValue,
) {
    val value: Any?
        get() = source.value

    override fun toString(): String = "CarPlayThemeSceneOpaqueReference($source)"
}

/** Opaque frame/size reference in package logical coordinates. */
class CarPlayThemeSceneLogicalGeometryReference internal constructor(
    private val source: CarPlayThemeOpaqueValue,
    val domain: CarPlayThemeSceneGeometryDomain? = null,
) {
    val coordinateSpace: CarPlayThemeSceneCoordinateSpace
        get() = CarPlayThemeSceneCoordinateSpace.THEME_LOGICAL_OPAQUE

    val value: Any?
        get() = source.value

    /** Strict evidence-backed views; callers select the confirmed model field's actual type. */
    fun asPoint(label: String = "logical CGPoint"): CarPlayLogicalPoint =
        CarPlayThemeGeometry.point(source, label)

    fun asSize(label: String = "logical CGSize"): CarPlayLogicalSize =
        CarPlayThemeGeometry.size(source, label)

    fun asRect(label: String = "logical CGRect"): CarPlayLogicalRect =
        CarPlayThemeGeometry.rect(source, label)

    fun asEdgeInsets(label: String = "logical EdgeInsets"): CarPlayLogicalEdgeInsets =
        CarPlayThemeGeometry.edgeInsets(source, label)

    override fun toString(): String =
        "CarPlayThemeSceneLogicalGeometryReference(coordinateSpace=$coordinateSpace)"
}

/** Explicit caller selection; the reducer never guesses which primary component is active. */
data class CarPlayThemePrimarySelection(
    val layoutIndex: Int,
    val zoneIndex: Int,
    val componentIndex: Int,
) {
    init {
        require(layoutIndex >= 0) { "layoutIndex must not be negative" }
        require(zoneIndex >= 0) { "zoneIndex must not be negative" }
        require(componentIndex >= 0) { "componentIndex must not be negative" }
    }
}

/**
 * Dynamic inputs whose detailed payloads have not been established. Tracking and appearance are
 * represented only by phase markers, never by invented geometry or behavior.
 */
class CarPlayThemeSceneState(
    activePrimarySelections: List<CarPlayThemePrimarySelection> = emptyList(),
    val trackingOverlayPresent: Boolean = false,
    val appearanceOverlayPresent: Boolean = false,
    /** Selects ClippingBehavior.transitioning when true, notTransitioning otherwise. */
    val transitionActive: Boolean = false,
) {
    val activePrimarySelections: List<CarPlayThemePrimarySelection> =
        Collections.unmodifiableList(ArrayList(activePrimarySelections))
}

class CarPlayThemeSceneReductionException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

sealed interface CarPlayThemeSceneReduction {
    data object Disabled : CarPlayThemeSceneReduction

    class DiagnosticPlan internal constructor(
        val scene: CarPlayThemeScene,
    ) : CarPlayThemeSceneReduction
}

enum class CarPlayThemeSceneExecutionStatus {
    /** Symbolic diagnostic output only; it is never renderer readiness. */
    DIAGNOSTIC_ONLY,

    /** At least one operation has unknown composition semantics and must not be rendered. */
    UNSUPPORTED_FAIL_CLOSED,
}

enum class CarPlayThemeSceneUnsupportedReason {
    /** The order/composition of an input-stream mask and clipToBounds is not established. */
    INPUT_STREAM_MASK_WITH_CLIP_TO_BOUNDS,
}

data class CarPlayThemeSceneUnsupportedDiagnostic(
    val slot: CarPlayThemeSceneSlotAddress,
    val reason: CarPlayThemeSceneUnsupportedReason,
)

class CarPlayThemeScene internal constructor(
    val displayType: DisplayType,
    val displayId: String?,
    layouts: List<CarPlayThemeLayoutScenePlan>,
    unsupportedDiagnostics: List<CarPlayThemeSceneUnsupportedDiagnostic>,
) {
    val coordinateSpace: CarPlayThemeSceneCoordinateSpace =
        CarPlayThemeSceneCoordinateSpace.THEME_LOGICAL_PARENT_DOMAINS_RESOLVED
    val layouts: List<CarPlayThemeLayoutScenePlan> =
        Collections.unmodifiableList(ArrayList(layouts))
    val unsupportedDiagnostics: List<CarPlayThemeSceneUnsupportedDiagnostic> =
        Collections.unmodifiableList(ArrayList(unsupportedDiagnostics))
    val executionStatus: CarPlayThemeSceneExecutionStatus =
        if (this.unsupportedDiagnostics.isEmpty()) {
            CarPlayThemeSceneExecutionStatus.DIAGNOSTIC_ONLY
        } else {
            CarPlayThemeSceneExecutionStatus.UNSUPPORTED_FAIL_CLOSED
        }
}

class CarPlayThemeLayoutScenePlan internal constructor(
    val address: CarPlayThemeSceneLayoutAddress,
    val layoutType: LayoutType,
    stages: List<CarPlayThemeSceneStagePlan>,
) {
    val stages: List<CarPlayThemeSceneStagePlan> =
        Collections.unmodifiableList(ArrayList(stages))

    init {
        require(this.stages.map(CarPlayThemeSceneStagePlan::stage) == CarPlayThemeSceneStage.entries) {
            "Theme scene stages must use the confirmed order exactly once"
        }
    }

    fun stage(stage: CarPlayThemeSceneStage): CarPlayThemeSceneStagePlan = stages[stage.ordinal]
}

class CarPlayThemeSceneStagePlan internal constructor(
    val stage: CarPlayThemeSceneStage,
    nodes: List<CarPlayThemeSceneNode>,
) {
    val nodes: List<CarPlayThemeSceneNode> = Collections.unmodifiableList(ArrayList(nodes))

    init {
        require(this.nodes.all { it.stage == stage }) {
            "Theme scene node was placed in the wrong stage"
        }
    }
}

sealed interface CarPlayThemeSceneNode {
    val stage: CarPlayThemeSceneStage
}

/** Typed, resolved zone shape used only as a symbolic clip plan. */
class CarPlayThemeResolvedZoneShape internal constructor(
    val case: ZoneShapeCase,
    val cornerRadius: Double?,
    /** True only for the observed rectangular/zero-radius fallback of an absent zoneShape. */
    val defaulted: Boolean,
) {
    init {
        require(
            (case == ZoneShapeCase.CIRCULAR && cornerRadius == null) ||
                (case == ZoneShapeCase.RECTANGULAR && cornerRadius != null),
        ) { "Resolved zone shape does not match its associated value" }
    }
}

enum class CarPlayThemeCustomMaskFrameDomain {
    /** Reverse evidence confirms CGRect values but not their parent coordinate domain. */
    UNKNOWN,
}

class CarPlayThemeCustomMaskPlan internal constructor(mask: CustomMaskImage) {
    val imageConfiguration = CarPlayThemeSceneOpaqueReference(mask.imageConfiguration)
    val startingFrame = CarPlayThemeSceneLogicalGeometryReference(
        mask.startingFrame,
        CarPlayThemeSceneGeometryDomain.CUSTOM_MASK_UNKNOWN_LOGICAL,
    )
    val endingFrame = CarPlayThemeSceneLogicalGeometryReference(
        mask.endingFrame,
        CarPlayThemeSceneGeometryDomain.CUSTOM_MASK_UNKNOWN_LOGICAL,
    )
    val startingLogicalRect: CarPlayLogicalRect = startingFrame.asRect("CustomMask.startingFrame")
    val endingLogicalRect: CarPlayLogicalRect = endingFrame.asRect("CustomMask.endingFrame")
    val animation = CarPlayThemeSceneOpaqueReference(mask.animation)
    val frameDomain = CarPlayThemeCustomMaskFrameDomain.UNKNOWN
}

/** Component-level clip selected from ClippingBehavior for the current transition state. */
sealed interface CarPlayThemeComponentClipPlan {
    val styleCase: ClippingStyleCase

    data object None : CarPlayThemeComponentClipPlan {
        override val styleCase = ClippingStyleCase.NONE
    }

    class ZoneShape internal constructor(
        val shape: CarPlayThemeResolvedZoneShape,
        val zoneFrameInDisplayLogicalSpace: CarPlayLogicalRect,
    ) : CarPlayThemeComponentClipPlan {
        override val styleCase = ClippingStyleCase.ZONE_SHAPE
    }

    class Custom internal constructor(
        val mask: CarPlayThemeCustomMaskPlan,
    ) : CarPlayThemeComponentClipPlan {
        override val styleCase = ClippingStyleCase.CUSTOM
    }
}

/** Instrument-level outer clip, intentionally distinct from component and content-internal clips. */
sealed interface CarPlayThemeInstrumentOuterClipPlan {
    data object None : CarPlayThemeInstrumentOuterClipPlan

    /** Clip the instrument as a whole to the enclosing slot's typed style. */
    class SlotShape internal constructor(
        val slotStyle: CarPlayThemeSlotStyleSnapshot,
    ) : CarPlayThemeInstrumentOuterClipPlan

    /**
     * The confirmed field belongs to Instrument.Configuration, whose wire schema is still opaque.
     * Absence of the legacy flat projection must not be interpreted as the binary's constructor
     * default.
     */
    class UnknownFromInstrumentConfiguration internal constructor(
        val opaqueConfiguration: CarPlayThemeSceneOpaqueReference?,
        val legacyFlatProjection: CarPlayThemeSceneOpaqueReference?,
    ) : CarPlayThemeInstrumentOuterClipPlan
}

enum class CarPlayThemeInputStreamCompositionOrder {
    /** Transform, mask, and clipToBounds composition order remains unestablished. */
    OPAQUE,
}

/**
 * InputStream presentation operations remain an independent internal plan. They are never merged
 * with the component clip or the instrument's slot-shape clip.
 */
class CarPlayThemeInputStreamPresentationPlan internal constructor(
    val transform: InputStreamTransform?,
    mask: CarPlayThemeOpaqueValue?,
    val clipToBounds: Boolean,
) {
    val mask = mask?.let(::CarPlayThemeSceneOpaqueReference)
    val compositionOrder = CarPlayThemeInputStreamCompositionOrder.OPAQUE
    val supported: Boolean = mask == null || !clipToBounds
}

private val ZERO_POINT = CarPlayLogicalPoint(0.0, 0.0)

class CarPlayThemeLayoutBackgroundNode internal constructor(
    val layout: CarPlayThemeSceneLayoutAddress,
    val backgroundColor: CarPlayThemeSceneOpaqueReference,
) : CarPlayThemeSceneNode {
    override val stage = CarPlayThemeSceneStage.LAYOUT_BACKGROUND
}

/** A marker remains present even when the package did not provide platter configuration. */
class CarPlayThemeZonePlatterNode internal constructor(
    val zone: CarPlayThemeSceneZoneAddress,
    val logicalFrame: CarPlayThemeSceneLogicalGeometryReference,
    val frameInDisplayLogicalSpace: CarPlayLogicalRect,
    val zoneShape: CarPlayThemeResolvedZoneShape,
    val platterConfiguration: CarPlayThemeSceneOpaqueReference?,
) : CarPlayThemeSceneNode {
    override val stage = CarPlayThemeSceneStage.ZONE_PLATTER
}

class CarPlayThemePrimaryComponentNode internal constructor(
    val component: CarPlayThemeSceneComponentAddress,
    val logicalSize: CarPlayThemeSceneLogicalGeometryReference,
    /** Components have CGSize only and are aligned at (0, 0) in their zone. */
    val originInZoneLogicalSpace: CarPlayLogicalPoint,
    val frameInDisplayLogicalSpace: CarPlayLogicalRect,
    val componentClip: CarPlayThemeComponentClipPlan,
) : CarPlayThemeSceneNode {
    override val stage = CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT
}

class CarPlayThemeOverlayComponentNode internal constructor(
    val component: CarPlayThemeSceneComponentAddress,
    val logicalSize: CarPlayThemeSceneLogicalGeometryReference,
    /** Components have CGSize only and are aligned at (0, 0) in their zone. */
    val originInZoneLogicalSpace: CarPlayLogicalPoint,
    val frameInDisplayLogicalSpace: CarPlayLogicalRect,
    val componentClip: CarPlayThemeComponentClipPlan,
) : CarPlayThemeSceneNode {
    override val stage = CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT
}

enum class CarPlayThemeSceneSlotRole {
    ACTIVE_PRIMARY,
    ZONE_OVERLAY,
}

class CarPlayThemeSlotStyleSnapshot internal constructor(style: SlotStyle) {
    val case: SlotStyleCase = style.case
    val payload = CarPlayThemeSceneOpaqueReference(style.payload)
}

/**
 * Immutable snapshot of all confirmed instrument metadata. Every unconfirmed field and associated
 * kind payload remains opaque.
 */
class CarPlayThemeInstrumentSnapshot internal constructor(instrument: Instrument) {
    val id = instrument.id
    val kind: InstrumentKindCase = instrument.kind.case
    /** Null for kinds whose native/phone routing has not been established. */
    val knownOutputAffinity: CarPlayThemeInstrumentOutputAffinity? =
        instrument.knownOutputAffinity
    val kindPayload = CarPlayThemeSceneOpaqueReference(instrument.kind.payload)
    val backgroundColor = CarPlayThemeSceneOpaqueReference(instrument.backgroundColor)
    val verifierIdentifier: String? = instrument.verifierIdentifier
    val shadowID: String? = instrument.shadowID
    val persistent: Boolean? = instrument.persistent
    val blendMode = instrument.blendMode?.let(::CarPlayThemeSceneOpaqueReference)
    val layoutConfiguration = instrument.layoutConfiguration?.let(::CarPlayThemeSceneOpaqueReference)
    val configuration = instrument.configuration?.let(::CarPlayThemeSceneOpaqueReference)
    val backgroundContent = instrument.backgroundContent?.let(::CarPlayThemeSceneOpaqueReference)
    val legacyFlatClipsToShape = instrument.clipsToShape?.let(::CarPlayThemeSceneOpaqueReference)
    val extensions: Map<String, CarPlayThemeSceneOpaqueReference> = Collections.unmodifiableMap(
        instrument.extensions.mapValuesTo(LinkedHashMap()) { (_, value) ->
            CarPlayThemeSceneOpaqueReference(value)
        },
    )
}

/** Symbolic content source only; none of these nodes is directly renderable. */
sealed interface CarPlayThemeSlotSource {
    data object Empty : CarPlayThemeSlotSource

    class InputStream internal constructor(
        val instrument: CarPlayThemeInstrumentSnapshot,
        payload: InputStreamInstrumentKindPayload,
    ) : CarPlayThemeSlotSource {
        val streamIdentifier: String = payload.streamIdentifier
        val visibilityConfiguration = payload.visibilityConfiguration?.let(
            ::CarPlayThemeSceneOpaqueReference,
        )
        val presentation: InputStreamPresentation? = payload.presentation
    }

    sealed class PunchThrough(
        val instrument: CarPlayThemeInstrumentSnapshot,
        val variant: InstrumentKindCase,
    ) : CarPlayThemeSlotSource {
        class Reference internal constructor(
            instrument: CarPlayThemeInstrumentSnapshot,
            payload: PunchThroughInstrumentKindPayload,
        ) : PunchThrough(instrument, InstrumentKindCase.PUNCH_THROUGH) {
            val reference: String = payload.reference
            val instrumentDataIdentifier: String = payload.instrumentDataIdentifier
        }

        class Typed internal constructor(
            instrument: CarPlayThemeInstrumentSnapshot,
            payload: PunchThroughTypeInstrumentKindPayload,
        ) : PunchThrough(instrument, InstrumentKindCase.PUNCH_THROUGH_TYPE) {
            val punchThroughType: String = payload.punchThroughType
            val instrumentDataIdentifier: String? = payload.instrumentDataIdentifier
            val presentation: InputStreamPresentation? = payload.presentation
        }

        init {
            require(this.instrument.kind == variant) {
                "Punch-through source does not match its instrument kind"
            }
        }
    }

    /**
     * Opaque placeholder for confirmed native Gauge/Indicator or kinds with unknown affinity.
     * The name is historical: it grants no Android drawing semantics to either category.
     */
    class LocalOpaqueInstrument internal constructor(
        val instrument: CarPlayThemeInstrumentSnapshot,
    ) : CarPlayThemeSlotSource {
        val kind: InstrumentKindCase = instrument.kind
    }

    /**
     * A confirmed phone-output Gauge/Indicator whose painting and data path are still unknown.
     * This is neither an InputStream nor an executable video layer.
     */
    class NonNativeUnresolvedInstrument internal constructor(
        val instrument: CarPlayThemeInstrumentSnapshot,
    ) : CarPlayThemeSlotSource {
        val kind: InstrumentKindCase = instrument.kind

        init {
            require(instrument.knownOutputAffinity == CarPlayThemeInstrumentOutputAffinity.PHONE) {
                "Non-native unresolved source requires confirmed phone affinity"
            }
        }
    }
}

class CarPlayThemeSlotNode internal constructor(
    val slot: CarPlayThemeSceneSlotAddress,
    val role: CarPlayThemeSceneSlotRole,
    val zIndex: Int,
    val logicalFrame: CarPlayThemeSceneLogicalGeometryReference,
    val frameInComponentLogicalSpace: CarPlayLogicalRect,
    /** Equal to the component-local frame because Component is anchored at zone-local (0, 0). */
    val frameInZoneLogicalSpace: CarPlayLogicalRect,
    val frameInDisplayLogicalSpace: CarPlayLogicalRect,
    val safeAreaInsets: CarPlayThemeSceneOpaqueReference?,
    val style: CarPlayThemeSlotStyleSnapshot,
    val source: CarPlayThemeSlotSource,
    /** Component clipping, selected independently for transitionActive. */
    val componentClip: CarPlayThemeComponentClipPlan,
    /** Instrument clipsToShape represented as an outer clip to this slot's shape. */
    val instrumentOuterClip: CarPlayThemeInstrumentOuterClipPlan,
    /** Input-stream transform/mask/clip operations remain internal and independently opaque. */
    val inputStreamPresentation: CarPlayThemeInputStreamPresentationPlan?,
    val unsupportedReason: CarPlayThemeSceneUnsupportedReason?,
    override val stage: CarPlayThemeSceneStage,
) : CarPlayThemeSceneNode {
    val instrumentClipsToSlotShape: Boolean? = when (instrumentOuterClip) {
        CarPlayThemeInstrumentOuterClipPlan.None -> false
        is CarPlayThemeInstrumentOuterClipPlan.SlotShape -> true
        is CarPlayThemeInstrumentOuterClipPlan.UnknownFromInstrumentConfiguration -> null
    }

    init {
        require(
            (role == CarPlayThemeSceneSlotRole.ACTIVE_PRIMARY &&
                stage == CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER) ||
                (role == CarPlayThemeSceneSlotRole.ZONE_OVERLAY &&
                    stage == CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT),
        ) { "Slot role does not match its scene stage" }
        require((unsupportedReason == null) == (inputStreamPresentation?.supported != false)) {
            "Unsupported slot diagnostic does not match its input-stream presentation"
        }
    }
}

class CarPlayThemeTrackingOverlayNode internal constructor(
    val layout: CarPlayThemeSceneLayoutAddress,
) : CarPlayThemeSceneNode {
    override val stage = CarPlayThemeSceneStage.TRACKING_OVERLAY
}

class CarPlayThemeAppearanceOverlayNode internal constructor(
    val layout: CarPlayThemeSceneLayoutAddress,
) : CarPlayThemeSceneNode {
    override val stage = CarPlayThemeSceneStage.APPEARANCE_OVERLAY
}

/**
 * Pure, evidence-bounded theme-to-scene reducer. It performs no I/O, code execution, rendering,
 * density conversion, surface allocation, or product activation.
 *
 * It also does not clamp, standardize, or reject negative/off-parent CGRect geometry. A physical
 * renderer must select an explicit negative-extent and clipping policy after this logical plan.
 */
class CarPlayThemeSceneReducer(
    private val mode: CarPlayThemeSceneReducerMode = CarPlayThemeSceneReducerMode.DISABLED,
    private val schemaLimits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    private val maximumSceneNodes: Int = schemaLimits.maximumNodeCount,
) {
    init {
        require(maximumSceneNodes in 1..schemaLimits.maximumNodeCount) {
            "maximumSceneNodes must fit inside the schema node budget"
        }
    }

    fun reduce(
        display: Display,
        state: CarPlayThemeSceneState = CarPlayThemeSceneState(),
    ): CarPlayThemeSceneReduction {
        if (mode == CarPlayThemeSceneReducerMode.DISABLED) {
            return CarPlayThemeSceneReduction.Disabled
        }

        val budget = ReductionBudget(schemaLimits, maximumSceneNodes)
        budget.consumeInputNode("display")
        budget.consumeString(display.id, "display id")
        budget.consumeInputCollection(display.layouts.size, "display layouts")
        budget.consumeInputCollection(
            state.activePrimarySelections.size,
            "active primary selections",
        )

        val selected = validateSelections(display, state.activePrimarySelections)
        decodeGeometry("Display.size") { display.logicalSize() }
        val plans = ArrayList<CarPlayThemeLayoutScenePlan>(display.layouts.size)
        val unsupportedDiagnostics = ArrayList<CarPlayThemeSceneUnsupportedDiagnostic>()
        display.layouts.forEachIndexed { layoutIndex, layout ->
            budget.consumeInputNode("layout")
            budget.consumeString(layout.id, "layout id")
            budget.consumeInputCollection(layout.zones.size, "layout zones")
            plans += reduceLayout(
                layoutIndex = layoutIndex,
                layout = layout,
                selected = selected,
                state = state,
                unsupportedDiagnostics = unsupportedDiagnostics,
                budget = budget,
            )
        }

        return CarPlayThemeSceneReduction.DiagnosticPlan(
            CarPlayThemeScene(
                displayType = display.type,
                displayId = display.id,
                layouts = plans,
                unsupportedDiagnostics = unsupportedDiagnostics,
            ),
        )
    }

    private fun reduceLayout(
        layoutIndex: Int,
        layout: Layout,
        selected: Map<ZoneOrdinal, Int>,
        state: CarPlayThemeSceneState,
        unsupportedDiagnostics: MutableList<CarPlayThemeSceneUnsupportedDiagnostic>,
        budget: ReductionBudget,
    ): CarPlayThemeLayoutScenePlan {
        val layoutAddress = CarPlayThemeSceneLayoutAddress(layoutIndex, layout.id)
        val nodes = CarPlayThemeSceneStage.entries.associateWith {
            ArrayList<CarPlayThemeSceneNode>()
        }

        nodes.getValue(CarPlayThemeSceneStage.LAYOUT_BACKGROUND) += budget.emit(
            CarPlayThemeLayoutBackgroundNode(
                layoutAddress,
                CarPlayThemeSceneOpaqueReference(layout.backgroundColor),
            ),
        )

        layout.zones.forEachIndexed { zoneIndex, zone ->
            budget.consumeInputNode("zone")
            budget.consumeString(zone.id, "zone id")
            budget.consumeInputCollection(zone.components.size, "zone components")
            val zoneAddress = CarPlayThemeSceneZoneAddress(layoutAddress, zoneIndex, zone.id)
            val zoneFrame = decodeGeometry("Zone.frame") { zone.logicalFrame() }
            val zoneShape = resolvedZoneShape(zone)
            nodes.getValue(CarPlayThemeSceneStage.ZONE_PLATTER) += budget.emit(
                CarPlayThemeZonePlatterNode(
                    zoneAddress,
                    CarPlayThemeSceneLogicalGeometryReference(
                        zone.frame,
                        CarPlayThemeSceneGeometryDomain.DISPLAY_LOGICAL,
                    ),
                    zoneFrame,
                    zoneShape,
                    zone.platterConfiguration?.let(::CarPlayThemeSceneOpaqueReference),
                ),
            )

            val selectedComponentIndex = selected[ZoneOrdinal(layoutIndex, zoneIndex)]
            zone.components.forEachIndexed { componentIndex, component ->
                budget.consumeInputNode("component")
                budget.consumeString(component.id, "component id")
                budget.consumeInputCollection(component.slots.size, "component slots")
                component.slots.forEach { slot -> consumeSlotInput(slot, budget) }
                val componentSize = decodeGeometry("Component.size") {
                    component.logicalSize()
                }
                val componentFrameInDisplay = CarPlayLogicalRect(zoneFrame.origin, componentSize)
                val componentClip = resolveComponentClip(
                    component = component,
                    zoneFrame = zoneFrame,
                    zoneShape = zoneShape,
                    transitionActive = state.transitionActive,
                )
                val resolvedSlots = component.slots.mapIndexed { slotIndex, slot ->
                    resolveSlotGeometry(
                        slotIndex = slotIndex,
                        slot = slot,
                        zoneFrame = zoneFrame,
                    )
                }

                val componentAddress = CarPlayThemeSceneComponentAddress(
                    zoneAddress,
                    componentIndex,
                    component.id,
                )
                when {
                    componentIndex == selectedComponentIndex -> {
                        nodes.getValue(CarPlayThemeSceneStage.ACTIVE_PRIMARY_COMPONENT) +=
                            budget.emit(
                                CarPlayThemePrimaryComponentNode(
                                    componentAddress,
                                    CarPlayThemeSceneLogicalGeometryReference(
                                        component.size,
                                        CarPlayThemeSceneGeometryDomain.ZONE_LOCAL_LOGICAL,
                                    ),
                                    ZERO_POINT,
                                    componentFrameInDisplay,
                                    componentClip,
                                ),
                            )
                        appendSlots(
                            target = nodes.getValue(
                                CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER,
                            ),
                            resolvedSlots = resolvedSlots,
                            componentAddress = componentAddress,
                            componentClip = componentClip,
                            role = CarPlayThemeSceneSlotRole.ACTIVE_PRIMARY,
                            stage = CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER,
                            unsupportedDiagnostics = unsupportedDiagnostics,
                            budget = budget,
                        )
                    }
                    component.type == ComponentType.OVERLAY -> {
                        val target = nodes.getValue(CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT)
                        target += budget.emit(
                            CarPlayThemeOverlayComponentNode(
                                componentAddress,
                                CarPlayThemeSceneLogicalGeometryReference(
                                    component.size,
                                    CarPlayThemeSceneGeometryDomain.ZONE_LOCAL_LOGICAL,
                                ),
                                ZERO_POINT,
                                componentFrameInDisplay,
                                componentClip,
                            ),
                        )
                        appendSlots(
                            target = target,
                            resolvedSlots = resolvedSlots,
                            componentAddress = componentAddress,
                            componentClip = componentClip,
                            role = CarPlayThemeSceneSlotRole.ZONE_OVERLAY,
                            stage = CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT,
                            unsupportedDiagnostics = unsupportedDiagnostics,
                            budget = budget,
                        )
                    }
                }
            }
        }

        if (state.trackingOverlayPresent) {
            nodes.getValue(CarPlayThemeSceneStage.TRACKING_OVERLAY) +=
                budget.emit(CarPlayThemeTrackingOverlayNode(layoutAddress))
        }
        if (state.appearanceOverlayPresent) {
            nodes.getValue(CarPlayThemeSceneStage.APPEARANCE_OVERLAY) +=
                budget.emit(CarPlayThemeAppearanceOverlayNode(layoutAddress))
        }

        return CarPlayThemeLayoutScenePlan(
            address = layoutAddress,
            layoutType = layout.layoutType,
            stages = CarPlayThemeSceneStage.entries.map { stage ->
                CarPlayThemeSceneStagePlan(stage, nodes.getValue(stage))
            },
        )
    }

    private fun appendSlots(
        target: MutableList<CarPlayThemeSceneNode>,
        resolvedSlots: List<ResolvedSlotGeometry>,
        componentAddress: CarPlayThemeSceneComponentAddress,
        componentClip: CarPlayThemeComponentClipPlan,
        role: CarPlayThemeSceneSlotRole,
        stage: CarPlayThemeSceneStage,
        unsupportedDiagnostics: MutableList<CarPlayThemeSceneUnsupportedDiagnostic>,
        budget: ReductionBudget,
    ) {
        resolvedSlots
            .sortedWith(compareBy<ResolvedSlotGeometry>({ it.slot.zIndex }, { it.slotIndex }))
            .forEach { resolved ->
                val slot = resolved.slot
                val slotAddress = CarPlayThemeSceneSlotAddress(
                    componentAddress,
                    resolved.slotIndex,
                    slot.positionId,
                )
                val inputStreamPresentation = inputStreamPresentationPlan(slot.instrument)
                val unsupportedReason = if (inputStreamPresentation?.supported == false) {
                    CarPlayThemeSceneUnsupportedReason.INPUT_STREAM_MASK_WITH_CLIP_TO_BOUNDS
                } else {
                    null
                }
                if (unsupportedReason != null) {
                    unsupportedDiagnostics += CarPlayThemeSceneUnsupportedDiagnostic(
                        slotAddress,
                        unsupportedReason,
                    )
                }
                val slotStyle = CarPlayThemeSlotStyleSnapshot(slot.style)
                val instrumentOuterClip = resolveInstrumentOuterClip(slot.instrument, slotStyle)
                target += budget.emit(
                    CarPlayThemeSlotNode(
                        slot = slotAddress,
                        role = role,
                        zIndex = slot.zIndex,
                        logicalFrame = CarPlayThemeSceneLogicalGeometryReference(
                            slot.frame,
                            CarPlayThemeSceneGeometryDomain.COMPONENT_LOCAL_LOGICAL,
                        ),
                        frameInComponentLogicalSpace = resolved.frameInComponentLogicalSpace,
                        frameInZoneLogicalSpace = resolved.frameInComponentLogicalSpace,
                        frameInDisplayLogicalSpace = resolved.frameInDisplayLogicalSpace,
                        safeAreaInsets = slot.safeAreaInsets?.let(
                            ::CarPlayThemeSceneOpaqueReference,
                        ),
                        style = slotStyle,
                        source = symbolicSource(slot.instrument),
                        componentClip = componentClip,
                        instrumentOuterClip = instrumentOuterClip,
                        inputStreamPresentation = inputStreamPresentation,
                        unsupportedReason = unsupportedReason,
                        stage = stage,
                    ),
                )
            }
    }

    private fun resolvedZoneShape(zone: Zone): CarPlayThemeResolvedZoneShape {
        val shape = zone.zoneShape
        return if (shape == null) {
            CarPlayThemeResolvedZoneShape(
                case = ZoneShapeCase.RECTANGULAR,
                cornerRadius = 0.0,
                defaulted = true,
            )
        } else {
            CarPlayThemeResolvedZoneShape(
                case = shape.case,
                cornerRadius = shape.cornerRadius,
                defaulted = false,
            )
        }
    }

    private fun resolveInstrumentOuterClip(
        instrument: Instrument?,
        slotStyle: CarPlayThemeSlotStyleSnapshot,
    ): CarPlayThemeInstrumentOuterClipPlan {
        instrument ?: return CarPlayThemeInstrumentOuterClipPlan.None
        val legacyFlatValue = instrument.clipsToShape?.value
        return when (legacyFlatValue) {
            true -> CarPlayThemeInstrumentOuterClipPlan.SlotShape(slotStyle)
            false -> CarPlayThemeInstrumentOuterClipPlan.None
            else -> CarPlayThemeInstrumentOuterClipPlan.UnknownFromInstrumentConfiguration(
                instrument.configuration?.let(::CarPlayThemeSceneOpaqueReference),
                instrument.clipsToShape?.let(::CarPlayThemeSceneOpaqueReference),
            )
        }
    }

    private fun resolveComponentClip(
        component: Component,
        zoneFrame: CarPlayLogicalRect,
        zoneShape: CarPlayThemeResolvedZoneShape,
        transitionActive: Boolean,
    ): CarPlayThemeComponentClipPlan {
        val behavior = component.clippingBehavior ?: return CarPlayThemeComponentClipPlan.None
        val style = if (transitionActive) behavior.transitioning else behavior.notTransitioning
        return when (style.case) {
            ClippingStyleCase.NONE -> CarPlayThemeComponentClipPlan.None
            ClippingStyleCase.ZONE_SHAPE -> CarPlayThemeComponentClipPlan.ZoneShape(
                shape = zoneShape,
                zoneFrameInDisplayLogicalSpace = zoneFrame,
            )
            ClippingStyleCase.CUSTOM -> {
                val customMask = style.customMask ?: throw CarPlayThemeSceneReductionException(
                    "Custom clipping style is missing its typed mask",
                )
                val plan = decodeGeometry("CustomMask.image") {
                    CarPlayThemeCustomMaskPlan(customMask.image)
                }
                CarPlayThemeComponentClipPlan.Custom(plan)
            }
        }
    }

    private fun resolveSlotGeometry(
        slotIndex: Int,
        slot: Slot,
        zoneFrame: CarPlayLogicalRect,
    ): ResolvedSlotGeometry {
        val componentFrame = decodeGeometry("Slot.frame") { slot.logicalFrame() }
        val displayOrigin = CarPlayLogicalPoint(
            safeSum(zoneFrame.origin.x, componentFrame.origin.x, "Slot.frame display x"),
            safeSum(zoneFrame.origin.y, componentFrame.origin.y, "Slot.frame display y"),
        )
        return ResolvedSlotGeometry(
            slotIndex = slotIndex,
            slot = slot,
            frameInComponentLogicalSpace = componentFrame,
            frameInDisplayLogicalSpace = CarPlayLogicalRect(displayOrigin, componentFrame.size),
        )
    }

    private fun inputStreamPresentationPlan(
        instrument: Instrument?,
    ): CarPlayThemeInputStreamPresentationPlan? {
        val presentation = when (val payload = instrument?.kind?.typedPayload) {
            is InputStreamInstrumentKindPayload -> payload.presentation
            is PunchThroughTypeInstrumentKindPayload -> payload.presentation
            else -> null
        } ?: return null
        return CarPlayThemeInputStreamPresentationPlan(
            transform = presentation.transform,
            mask = presentation.mask,
            clipToBounds = presentation.clipToBounds,
        )
    }

    private fun safeSum(first: Double, second: Double, label: String): Double {
        val result = first + second
        if (!result.isFinite()) {
            throw CarPlayThemeSceneReductionException("$label is not finite")
        }
        return result
    }

    private inline fun <T> decodeGeometry(label: String, block: () -> T): T = try {
        block()
    } catch (error: CarPlayThemeSchemaException) {
        throw CarPlayThemeSceneReductionException("$label is invalid", error)
    }

    private data class ResolvedSlotGeometry(
        val slotIndex: Int,
        val slot: Slot,
        val frameInComponentLogicalSpace: CarPlayLogicalRect,
        val frameInDisplayLogicalSpace: CarPlayLogicalRect,
    )

    private fun consumeSlotInput(slot: Slot, budget: ReductionBudget) {
        budget.consumeInputNode("slot")
        budget.consumeString(slot.positionId, "slot position id")
        val instrument = slot.instrument ?: return
        budget.consumeInputNode("instrument")
        budget.consumeString(instrument.verifierIdentifier, "instrument verifier identifier")
        budget.consumeString(instrument.shadowID, "instrument shadow id")
        budget.consumeInputCollection(instrument.extensions.size, "instrument extensions")
        instrument.extensions.keys.forEach { budget.consumeString(it, "instrument extension key") }
        when (val payload = instrument.kind.typedPayload) {
            is InputStreamInstrumentKindPayload -> {
                budget.consumeString(payload.streamIdentifier, "input-stream identifier")
                budget.consumeString(
                    payload.presentation?.visibleAreaVerifierIdentifierSuffix,
                    "input-stream presentation verifier suffix",
                )
            }
            is PunchThroughInstrumentKindPayload -> {
                budget.consumeString(payload.reference, "punch-through reference")
                budget.consumeString(
                    payload.instrumentDataIdentifier,
                    "punch-through data identifier",
                )
            }
            is PunchThroughTypeInstrumentKindPayload -> {
                budget.consumeString(payload.punchThroughType, "punch-through type")
                budget.consumeString(
                    payload.instrumentDataIdentifier,
                    "punch-through data identifier",
                )
                budget.consumeString(
                    payload.presentation?.visibleAreaVerifierIdentifierSuffix,
                    "punch-through presentation verifier suffix",
                )
            }
            is GaugeInstrumentKindPayload -> Unit
            is IndicatorInstrumentKindPayload -> Unit
            null -> Unit
        }
    }

    private fun symbolicSource(instrument: Instrument?): CarPlayThemeSlotSource {
        instrument ?: return CarPlayThemeSlotSource.Empty
        val snapshot = CarPlayThemeInstrumentSnapshot(instrument)
        return when (instrument.kind.case) {
            InstrumentKindCase.INPUT_STREAM -> CarPlayThemeSlotSource.InputStream(
                snapshot,
                instrument.kind.typedPayload as InputStreamInstrumentKindPayload,
            )
            InstrumentKindCase.PUNCH_THROUGH -> CarPlayThemeSlotSource.PunchThrough.Reference(
                snapshot,
                instrument.kind.typedPayload as PunchThroughInstrumentKindPayload,
            )
            InstrumentKindCase.PUNCH_THROUGH_TYPE -> CarPlayThemeSlotSource.PunchThrough.Typed(
                snapshot,
                instrument.kind.typedPayload as PunchThroughTypeInstrumentKindPayload,
            )
            InstrumentKindCase.GAUGE, InstrumentKindCase.INDICATOR ->
                when (snapshot.knownOutputAffinity) {
                    CarPlayThemeInstrumentOutputAffinity.PHONE ->
                        CarPlayThemeSlotSource.NonNativeUnresolvedInstrument(snapshot)
                    CarPlayThemeInstrumentOutputAffinity.NATIVE ->
                        CarPlayThemeSlotSource.LocalOpaqueInstrument(snapshot)
                    null -> throw CarPlayThemeSceneReductionException(
                        "Parsed Gauge/Indicator has no confirmed output affinity",
                    )
                }
            else -> CarPlayThemeSlotSource.LocalOpaqueInstrument(snapshot)
        }
    }

    private fun validateSelections(
        display: Display,
        selections: List<CarPlayThemePrimarySelection>,
    ): Map<ZoneOrdinal, Int> {
        val result = LinkedHashMap<ZoneOrdinal, Int>(selections.size)
        selections.forEach { selection ->
            val layout = display.layouts.getOrNull(selection.layoutIndex)
                ?: throw CarPlayThemeSceneReductionException(
                    "Primary selection refers to an unknown layout index",
                )
            val zone = layout.zones.getOrNull(selection.zoneIndex)
                ?: throw CarPlayThemeSceneReductionException(
                    "Primary selection refers to an unknown zone index",
                )
            val component = zone.components.getOrNull(selection.componentIndex)
                ?: throw CarPlayThemeSceneReductionException(
                    "Primary selection refers to an unknown component index",
                )
            if (component.type != ComponentType.PRIMARY) {
                throw CarPlayThemeSceneReductionException(
                    "Primary selection must refer to a primary component",
                )
            }
            val key = ZoneOrdinal(selection.layoutIndex, selection.zoneIndex)
            if (result.put(key, selection.componentIndex) != null) {
                throw CarPlayThemeSceneReductionException(
                    "Only one active primary component may be selected per zone",
                )
            }
        }
        return result
    }

    private data class ZoneOrdinal(val layoutIndex: Int, val zoneIndex: Int)
}

private class ReductionBudget(
    private val limits: CarPlayThemeSchemaLimits,
    private val maximumSceneNodes: Int,
) {
    private var inputNodes = 0
    private var inputCollectionEntries = 0
    private var inputStringBytes = 0L
    private var sceneNodes = 0

    fun consumeInputNode(label: String) {
        inputNodes = checkedIncrement(inputNodes, label)
        if (inputNodes > limits.maximumNodeCount) {
            fail("Theme scene input exceeds the schema node budget")
        }
    }

    fun consumeInputCollection(size: Int, label: String) {
        if (size < 0 || inputCollectionEntries > limits.maximumCollectionEntries - size) {
            fail("$label exceeds the schema collection-entry budget")
        }
        inputCollectionEntries += size
    }

    fun consumeString(value: String?, label: String) {
        if (value == null) return
        val bytes = utf8Length(value, label)
        if (inputStringBytes > limits.maximumStringBytes - bytes) {
            fail("Theme scene input exceeds the schema string budget")
        }
        inputStringBytes += bytes
    }

    fun <T : CarPlayThemeSceneNode> emit(node: T): T {
        sceneNodes = checkedIncrement(sceneNodes, "scene nodes")
        if (sceneNodes > maximumSceneNodes) {
            fail("Theme scene output exceeds its node budget")
        }
        return node
    }

    private fun checkedIncrement(value: Int, label: String): Int {
        if (value == Int.MAX_VALUE) fail("$label count overflowed")
        return value + 1
    }

    private fun utf8Length(value: String, label: String): Long {
        var result = 0L
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val bytes = when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        fail("$label contains an unpaired surrogate")
                    }
                    index += 1
                    4
                }
                Character.isLowSurrogate(current) -> fail("$label contains an unpaired surrogate")
                current.code <= 0x7f -> 1
                current.code <= 0x7ff -> 2
                else -> 3
            }
            result += bytes
            index += 1
        }
        return result
    }

    private fun fail(message: String): Nothing = throw CarPlayThemeSceneReductionException(message)
}
