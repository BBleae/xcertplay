package com.shilapi.xcertplay.vehicle.ultra.theme

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Resource limits applied before any semantic theme parsing takes place.
 *
 * This parser accepts an already-decoded property-list/JSON-shaped value tree. It deliberately
 * does not decode bytes, load assets, or execute strings as scripts. The complete input tree is
 * snapshotted under these limits before a model can observe it, closing mutation and cycle races.
 */
data class CarPlayThemeSchemaLimits(
    val maximumNestingDepth: Int = 32,
    val maximumNodeCount: Int = 20_000,
    val maximumCollectionEntries: Int = 20_000,
    val maximumStringBytes: Long = 2L * 1024L * 1024L,
    val maximumDataBytes: Long = 16L * 1024L * 1024L,
    val maximumNumberBytes: Long = 64L * 1024L,
) {
    init {
        require(maximumNestingDepth in 1..HARD_MAXIMUM_NESTING_DEPTH)
        require(maximumNodeCount in 1..HARD_MAXIMUM_NODE_COUNT)
        require(maximumCollectionEntries in 1..HARD_MAXIMUM_COLLECTION_ENTRIES)
        require(maximumStringBytes in 1..HARD_MAXIMUM_STRING_BYTES)
        require(maximumDataBytes in 1..HARD_MAXIMUM_DATA_BYTES)
        require(maximumNumberBytes in 1..HARD_MAXIMUM_NUMBER_BYTES)
    }

    private companion object {
        const val HARD_MAXIMUM_NESTING_DEPTH = 64
        const val HARD_MAXIMUM_NODE_COUNT = 100_000
        const val HARD_MAXIMUM_COLLECTION_ENTRIES = 100_000
        const val HARD_MAXIMUM_STRING_BYTES = 16L * 1024L * 1024L
        const val HARD_MAXIMUM_DATA_BYTES = 64L * 1024L * 1024L
        const val HARD_MAXIMUM_NUMBER_BYTES = 1024L * 1024L
    }
}

open class CarPlayThemeSchemaException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * A deeply frozen value whose wire shape has not been established strongly enough to model.
 * Every read returns a defensive copy; maps and lists in that copy are read-only.
 */
class CarPlayThemeOpaqueValue internal constructor(private val storedValue: Any?) {
    val value: Any?
        get() = copyFrozenValue(storedValue)

    override fun toString(): String = when (storedValue) {
        null -> "CarPlayThemeOpaqueValue(null)"
        is ByteArray -> "CarPlayThemeOpaqueValue(data=${storedValue.size} bytes)"
        is Map<*, *> -> "CarPlayThemeOpaqueValue(dictionary=${storedValue.size} entries)"
        is List<*> -> "CarPlayThemeOpaqueValue(array=${storedValue.size} entries)"
        else -> "CarPlayThemeOpaqueValue(${storedValue.javaClass.simpleName})"
    }
}

enum class ArcBuildType(val wireValue: String) {
    DEBUG("debug"),
    RELEASE("release"),
}

/** The package.json/PackageData root whose field types are confirmed by ArcUI symbols. */
class ArcPackage internal constructor(
    val identifier: String,
    val iOSContentVersion: String?,
    val version: UInt,
    val minimumSDKVersion: String,
    val buildType: ArcBuildType,
    val vehicleTypes: List<CarPlayThemeOpaqueValue>?,
    val partnerID: String,
    val displays: List<Display>,
    val driveModeDisplayNames: Map<String, String>?,
    val driveModeLinkedByDefault: Boolean,
    val driveModeDynamicAssignmentConfiguration: CarPlayThemeOpaqueValue?,
    val minimumCompatibilityVersion: Int,
    val vehicleType: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

enum class DisplayType(val wireValue: String) {
    CLUSTER("cluster"),
    CENTER_CONSOLE("centerConsole"),
    PASSENGER("passenger"),
    SECONDARY_CLUSTER("secondaryCluster"),
}

class Display internal constructor(
    val id: String?,
    val type: DisplayType,
    val layouts: List<Layout>,
    val size: CarPlayThemeOpaqueValue,
    val density: Double,
    val allowManufacturerAppIcon: Boolean,
    val requiresDarkAppearance: Boolean,
    val reversesDynamicContentDPadScrolling: Boolean?,
    val localNotifications: CarPlayThemeOpaqueValue?,
    val shadows: CarPlayThemeOpaqueValue?,
    val vectorFontManager: CarPlayThemeOpaqueValue?,
    val localizationManager: CarPlayThemeOpaqueValue?,
    val sendsButtonPressOnTouchDown: Boolean?,
    val iPhoneVideoPosition: CarPlayThemeOpaqueValue?,
    val iPhoneVideoMask: CarPlayThemeOpaqueValue?,
    val backgroundColor: CarPlayThemeOpaqueValue?,
    val contentCornerRadius: Double?,
    val contentCornerColor: CarPlayThemeOpaqueValue?,
    val pairingLogoAnimation: CarPlayThemeOpaqueValue?,
    val driveModeLayoutIDs: CarPlayThemeOpaqueValue?,
    val assetBundle: CarPlayThemeOpaqueValue?,
    val remoteUIAdditionalPhases: CarPlayThemeOpaqueValue?,
    val wallpaperOverlay: CarPlayThemeOpaqueValue?,
    val wallpaperGlassMask: CarPlayThemeOpaqueValue?,
    val background: CarPlayThemeOpaqueValue?,
    val shapeMask: CarPlayThemeOpaqueValue?,
    val interior: CarPlayThemeOpaqueValue?,
    val wheelOcclusion: CarPlayThemeOpaqueValue?,
    val manifestEntries: CarPlayThemeOpaqueValue?,
    val visibilityRules: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

enum class LayoutType(val wireValue: String) {
    PUNCHOUT("punchout"),
    UNDERLAY("underlay"),
    PRIMARY("primary"),
    OVERLAY("overlay"),
}

class Layout internal constructor(
    val id: String,
    val displayName: String?,
    val zones: List<Zone>,
    val layoutType: LayoutType,
    val systemUILayout: CarPlayThemeOpaqueValue?,
    val backgroundColor: CarPlayThemeOpaqueValue,
    val safeAreaInsets: CarPlayThemeOpaqueValue,
    val layoutStyleID: String?,
    val preview: CarPlayThemeOpaqueValue?,
    /** Absent in accessory-target exports even though the in-memory property is non-optional. */
    val wallpapers: List<CarPlayThemeOpaqueValue>?,
    val driveModeThemeConfigurations: CarPlayThemeOpaqueValue,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

enum class ZoneTypeCase(val wireValue: String) {
    MAPS_MEDIA("mapsMedia"),
    DCA("dca"),
    SINGLE("single"),
    DECK("deck"),
}

/** Strict one-key ZoneType enum; its associated payload remains opaque. */
class ZoneType internal constructor(
    val case: ZoneTypeCase,
    val payload: CarPlayThemeOpaqueValue,
)

enum class ZoneRegion(val wireValue: String) {
    ZONE_1("zone1"),
    ZONE_2("zone2"),
    ZONE_3("zone3"),
    ZONE_4("zone4"),
}

enum class ZoneShapeCase(val wireValue: String) {
    CIRCULAR("circular"),
    RECTANGULAR("rectangular"),
}

/**
 * Evidence-backed shape of a zone. The rectangular case carries the observed corner radius.
 *
 * A missing `Zone.zoneShape` is not rewritten by the schema parser; the scene reducer applies the
 * observed rectangular/zero-radius fallback and records that it was defaulted.
 */
class ZoneShape internal constructor(
    val case: ZoneShapeCase,
    val cornerRadius: Double?,
    val payload: CarPlayThemeOpaqueValue,
) {
    init {
        require(
            (case == ZoneShapeCase.CIRCULAR && cornerRadius == null) ||
                (case == ZoneShapeCase.RECTANGULAR && cornerRadius != null),
        ) { "Zone shape does not match its associated value" }
    }
}

enum class ClippingStyleCase(val wireValue: String) {
    CUSTOM("custom"),
    NONE("none"),
    ZONE_SHAPE("zoneShape"),
}

/** Strict one-key ClippingStyle enum with an evidence-backed custom-mask view. */
class ClippingStyle internal constructor(
    val case: ClippingStyleCase,
    val payload: CarPlayThemeOpaqueValue,
    val customMask: CustomMask?,
) {
    init {
        require((case == ClippingStyleCase.CUSTOM) == (customMask != null)) {
            "Clipping style does not match its typed payload"
        }
    }
}

/** Strict one-key CustomMask enum. Only the observed `image` case is accepted. */
class CustomMask internal constructor(
    val image: CustomMaskImage,
)

class CustomMaskImage internal constructor(
    val startingFrame: CarPlayThemeOpaqueValue,
    /** Confirmed ImageGroupConfiguration value; its nested schema remains bounded and opaque. */
    val imageConfiguration: CarPlayThemeOpaqueValue,
    val endingFrame: CarPlayThemeOpaqueValue,
    /** Confirmed VisibilityRule.Animation value; its nested schema remains bounded and opaque. */
    val animation: CarPlayThemeOpaqueValue,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
) {
    /** Compatibility alias for the encoded field name. */
    val image: CarPlayThemeOpaqueValue
        get() = imageConfiguration
}

class ClippingBehavior internal constructor(
    val transitioning: ClippingStyle,
    val notTransitioning: ClippingStyle,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
) {
    companion object {
        /** Observed static preset: clip both during and outside a transition. */
        @get:JvmStatic
        val clipToZone: ClippingBehavior
            get() = ClippingBehavior(
                transitioning = staticClippingStyle(ClippingStyleCase.ZONE_SHAPE),
                notTransitioning = staticClippingStyle(ClippingStyleCase.ZONE_SHAPE),
                extensions = emptyMap(),
            )

        /** Observed static preset: clip only while a transition is active. */
        @get:JvmStatic
        val clipToZoneDuringTransitions: ClippingBehavior
            get() = ClippingBehavior(
                transitioning = staticClippingStyle(ClippingStyleCase.ZONE_SHAPE),
                notTransitioning = staticClippingStyle(ClippingStyleCase.NONE),
                extensions = emptyMap(),
            )

        private fun staticClippingStyle(case: ClippingStyleCase): ClippingStyle = ClippingStyle(
            case = case,
            payload = CarPlayThemeOpaqueValue(emptyMap<String, Any?>()),
            customMask = null,
        )
    }
}

class Zone internal constructor(
    val id: String,
    val frame: CarPlayThemeOpaqueValue,
    val components: List<Component>,
    val zoneType: ZoneType,
    val zoneRegion: ZoneRegion?,
    val zoneShape: ZoneShape?,
    val platterConfiguration: CarPlayThemeOpaqueValue?,
    val verifierIdentifier: String?,
    val padding: CarPlayThemeOpaqueValue?,
    val visibilityConfiguration: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

enum class ComponentType(val wireValue: String) {
    PRIMARY("primary"),
    OVERLAY("overlay"),
}

class Component internal constructor(
    val id: String,
    val type: ComponentType,
    val size: CarPlayThemeOpaqueValue,
    val slots: List<Slot>,
    val trackable: Boolean?,
    val clippingBehavior: ClippingBehavior?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

enum class SlotStyleCase(val wireValue: String) {
    CIRCULAR("circular"),
    CORNER("corner"),
    RECTANGULAR("rectangular"),
}

/** Strict one-key Slot.Style enum. [corner] is populated only for the corner case. */
class SlotStyle internal constructor(
    val case: SlotStyleCase,
    val payload: CarPlayThemeOpaqueValue,
    val corner: CornerSlotStyle?,
)

class CornerSlotStyle internal constructor(
    val mask: CarPlayThemeOpaqueValue,
    val ringRadius: Double,
    val ringCenterPoint: CarPlayThemeOpaqueValue,
    val centralSlotPositionID: String,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

class Slot internal constructor(
    /** Skip-encoded by the package exporter and therefore normally absent. */
    val id: UUID?,
    val style: SlotStyle,
    val frame: CarPlayThemeOpaqueValue,
    val safeAreaInsets: CarPlayThemeOpaqueValue?,
    val zIndex: Int,
    val positionId: String,
    val instrument: Instrument?,
    val requirement: CarPlayThemeOpaqueValue?,
    val overrides: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

enum class InstrumentKindCase(val wireValue: String) {
    NAVIGATION("navigation"),
    INPUT_STREAM("inputStream"),
    PUNCH_THROUGH("punchThrough"),
    PUNCH_THROUGH_TYPE("punchThroughType"),
    WIDGET_POPOVER("widgetPopover"),
    APP("app"),
    MENU("menu"),
    GAUGE("gauge"),
    SPACER("spacer"),
    CONTAINER("container"),
    EMPTY_VIEW("emptyView"),
    INDICATOR("indicator"),
}

/**
 * iOS 27 CarPlayAssetUI LayoutOutput bits: background=1, phone=2, native=4, all=7.
 * Combinations are retained without treating background as an instrument destination.
 */
@JvmInline
value class CarPlayThemeLayoutOutput private constructor(val flags: Int) {
    init {
        require(flags in 0..7) { "Unknown LayoutOutput bits" }
    }

    internal fun contains(bit: Int): Boolean = flags and bit != 0

    companion object {
        val BACKGROUND = CarPlayThemeLayoutOutput(1)
        val PHONE = CarPlayThemeLayoutOutput(2)
        val NATIVE = CarPlayThemeLayoutOutput(4)
        val ALL = CarPlayThemeLayoutOutput(7)

        fun fromFlags(flags: Int): CarPlayThemeLayoutOutput = CarPlayThemeLayoutOutput(flags)
    }
}

/**
 * Confirmed Instrument.native routing, not a renderer, media-stream, or safety authorization.
 * Unknown Instrument kinds deliberately have no affinity inference.
 */
enum class CarPlayThemeInstrumentOutputAffinity(private val visibilityBit: Int) {
    PHONE(2),
    NATIVE(4);

    fun isVisible(output: CarPlayThemeLayoutOutput): Boolean = output.contains(visibilityBit)
}

/**
 * Evidence-backed typed views of confirmed Instrument.Kind associated-value tuples.
 *
 * Values whose nested wire schema has not been established stay bounded and opaque. The complete
 * associated-value dictionary is also retained by [InstrumentKind.payload].
 */
sealed interface InstrumentKindTypedPayload

/**
 * The confirmed `Instrument.Kind.gauge` configuration. This is package data, not a renderer or
 * permission to publish live vehicle values. Unrecognized fields remain available as diagnostics.
 */
class GaugeInstrumentKindPayload internal constructor(
    val configuration: GaugeConfiguration,
) : InstrumentKindTypedPayload

/** Package-only indicator description; it does not authorize or implement indicator painting. */
class IndicatorInstrumentKindPayload internal constructor(
    val configuration: IndicatorConfiguration,
) : InstrumentKindTypedPayload

/**
 * The five confirmed IndicatorConfiguration fields. The identifier, SwiftUI geometry, and nested
 * style configurations have no established executable Android semantics and remain opaque.
 */
class IndicatorConfiguration internal constructor(
    val dataIdentifier: CarPlayThemeOpaqueValue,
    val style: IndicatorStyle,
    val presentationEdge: CarPlayThemeOpaqueValue,
    val containerInsets: CarPlayThemeOpaqueValue,
    val vehicleLanguageIdentifier: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

/** The four cases confirmed by the iOS 27 CarPlayAssetUI IndicatorStyle enum metadata. */
enum class IndicatorStyleCase(val wireValue: String) {
    CRITICAL("critical"),
    ANIMATED_SYMBOL("animatedSymbol"),
    SELECTOR("selector"),
    MESSAGE("message"),
}

/**
 * Strict one-key style envelope. The first three cases contain a `configuration` dictionary;
 * `message` has no associated value. Native output affinity does not establish local pixels.
 */
class IndicatorStyle internal constructor(
    val case: IndicatorStyleCase,
    val payload: CarPlayThemeOpaqueValue,
    val associatedConfiguration: CarPlayThemeOpaqueValue?,
) {
    /** Observed IndicatorStyle.rendersLocally branch in iOS 27 CarPlayAssetUI. */
    val rendersLocally: Boolean
        get() = when (case) {
            IndicatorStyleCase.CRITICAL,
            IndicatorStyleCase.ANIMATED_SYMBOL,
            IndicatorStyleCase.SELECTOR -> true
            IndicatorStyleCase.MESSAGE -> false
        }

    val outputAffinity: CarPlayThemeInstrumentOutputAffinity
        get() = if (rendersLocally) CarPlayThemeInstrumentOutputAffinity.NATIVE
        else CarPlayThemeInstrumentOutputAffinity.PHONE

    init {
        require((case == IndicatorStyleCase.MESSAGE) == (associatedConfiguration == null)) {
            "Indicator style does not match its associated value"
        }
    }
}

class GaugeConfiguration internal constructor(
    val style: GaugeStyle,
    val rendersLocally: Boolean,
    val visibilityConfiguration: CarPlayThemeOpaqueValue,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
) {
    /** Uses the parsed required field exactly; a false value is not an InputStream. */
    val outputAffinity: CarPlayThemeInstrumentOutputAffinity
        get() = if (rendersLocally) CarPlayThemeInstrumentOutputAffinity.NATIVE
        else CarPlayThemeInstrumentOutputAffinity.PHONE
}

/** The 13 GaugeStyle cases confirmed by the iOS 27 CarPlayAssetUI enum metadata. */
enum class GaugeStyleCase(val wireValue: String) {
    CIRCULAR("circular"),
    IMAGE_GROUP("imageGroup"),
    SYMBOL("symbol"),
    TEXT_ELEMENT("textElement"),
    SHAPE_LAYER("shapeLayer"),
    MAPS_MEDIA_BLUR_BAR("mapsMediaBlurBar"),
    PROGRESS_BAR("progressBar"),
    STARTUP_ANIMATION("startupAnimation"),
    SPINNER("spinner"),
    IMAGE_STATE("imageState"),
    CLIMATE_PROGRESS("climateProgress"),
    COLOR("color"),
    AUTOMAKER_OVERLAY("automakerOverlay"),
}

/** Complete associated value retained separately from the deliberately partial typed view. */
class GaugeStyle internal constructor(
    val case: GaugeStyleCase,
    val payload: CarPlayThemeOpaqueValue,
    val typedPayload: GaugeStyleTypedPayload?,
) {
    init {
        val valid = when (case) {
            GaugeStyleCase.CIRCULAR -> typedPayload is CircularGaugeStylePayload
            GaugeStyleCase.TEXT_ELEMENT -> typedPayload is TextElementGaugeStylePayload
            GaugeStyleCase.SHAPE_LAYER -> typedPayload is ShapeLayerGaugeStylePayload
            GaugeStyleCase.IMAGE_GROUP -> typedPayload is ImageGroupGaugeStylePayload
            GaugeStyleCase.COLOR -> typedPayload is ColorGaugeStylePayload
            GaugeStyleCase.PROGRESS_BAR -> typedPayload is ProgressBarGaugeStylePayload
            else -> typedPayload == null
        }
        require(valid) { "Gauge style does not match its typed payload" }
    }
}

sealed interface GaugeStyleTypedPayload

class GaugeClosedDoubleRange internal constructor(
    val lowerBound: Double,
    val upperBound: Double,
)

/** The nine confirmed required CircularConfiguration fields; unknown nested types stay opaque. */
class GaugeCircularConfiguration internal constructor(
    val dataIdentifier: CarPlayThemeOpaqueValue,
    val range: GaugeClosedDoubleRange,
    val iconName: CarPlayThemeOpaqueValue,
    val minLabel: CarPlayThemeOpaqueValue,
    val minLabelColor: CarPlayThemeOpaqueValue,
    val maxLabel: CarPlayThemeOpaqueValue,
    val maxLabelColor: CarPlayThemeOpaqueValue,
    val tintColors: CarPlayThemeOpaqueValue,
    val backgroundTintColors: CarPlayThemeOpaqueValue,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

class CircularGaugeStylePayload internal constructor(
    val config: GaugeCircularConfiguration,
) : GaugeStyleTypedPayload

class TextElementGaugeStylePayload internal constructor(
    /** The nested TextElementConfiguration is not yet fully modeled. */
    val dataType: CarPlayThemeOpaqueValue,
    val configuration: CarPlayThemeOpaqueValue,
) : GaugeStyleTypedPayload

class ShapeLayerGaugeStylePayload internal constructor(
    /** Path command syntax is not inferred from the field's name. */
    val pathElements: CarPlayThemeOpaqueValue,
    val lineCap: CarPlayThemeOpaqueValue?,
    val fillColorIDs: CarPlayThemeOpaqueValue?,
    val strokeColorIDs: CarPlayThemeOpaqueValue?,
    val lineWidth: CarPlayThemeOpaqueValue?,
    val dataSource: CarPlayThemeOpaqueValue?,
    val configuration: CarPlayThemeOpaqueValue,
) : GaugeStyleTypedPayload

class ImageGroupGaugeStylePayload internal constructor(
    /** Image state semantics are not inferred from the field's name. */
    val states: CarPlayThemeOpaqueValue,
    val dataIdentifier: CarPlayThemeOpaqueValue?,
    val criticalStateDataIdentifier: CarPlayThemeOpaqueValue?,
    val defaultState: CarPlayThemeOpaqueValue?,
    val redactedState: CarPlayThemeOpaqueValue?,
    val dataTransformationType: CarPlayThemeOpaqueValue?,
    val configuration: CarPlayThemeOpaqueValue,
) : GaugeStyleTypedPayload

class ColorGaugeStylePayload internal constructor(
    /** The Dynamic envelope is decoded; the nested Theme.ColorID case remains opaque. */
    val colorID: CarPlayThemeDynamic<CarPlayThemeOpaqueValue>,
    val configuration: CarPlayThemeOpaqueValue,
) : GaugeStyleTypedPayload

class ProgressBarGaugeStylePayload internal constructor(
    /** ProgressBarType is a separate union; no case semantics are inferred here. */
    val type: CarPlayThemeOpaqueValue,
) : GaugeStyleTypedPayload

class InputStreamInstrumentKindPayload internal constructor(
    val streamIdentifier: String,
    val visibilityConfiguration: CarPlayThemeOpaqueValue?,
    val presentation: InputStreamPresentation?,
) : InstrumentKindTypedPayload

class PunchThroughInstrumentKindPayload internal constructor(
    val reference: String,
    /** Unknown cases are retained as raw strings; no enum meaning is inferred. */
    val instrumentDataIdentifier: String,
) : InstrumentKindTypedPayload

class PunchThroughTypeInstrumentKindPayload internal constructor(
    /** Unknown cases are retained as raw strings; no enum meaning is inferred. */
    val punchThroughType: String,
    val instrumentDataIdentifier: String?,
    val presentation: InputStreamPresentation?,
) : InstrumentKindTypedPayload

/** Strict one-key Instrument.Kind enum with evidence-backed associated-value views. */
class InstrumentKind internal constructor(
    val case: InstrumentKindCase,
    val payload: CarPlayThemeOpaqueValue,
    val typedPayload: InstrumentKindTypedPayload?,
) {
    /** Null when the examined native predicate has no confirmed branch for this kind. */
    val knownOutputAffinity: CarPlayThemeInstrumentOutputAffinity?
        get() = when (val typed = typedPayload) {
            is GaugeInstrumentKindPayload -> typed.configuration.outputAffinity
            is IndicatorInstrumentKindPayload -> typed.configuration.style.outputAffinity
            else -> null
        }

    /** Null preserves unknown visibility instead of guessing a phone/native destination. */
    fun isVisible(output: CarPlayThemeLayoutOutput): Boolean? =
        knownOutputAffinity?.isVisible(output)

    init {
        val valid = when (case) {
            InstrumentKindCase.INPUT_STREAM -> typedPayload is InputStreamInstrumentKindPayload
            InstrumentKindCase.PUNCH_THROUGH ->
                typedPayload is PunchThroughInstrumentKindPayload
            InstrumentKindCase.PUNCH_THROUGH_TYPE ->
                typedPayload is PunchThroughTypeInstrumentKindPayload
            InstrumentKindCase.GAUGE -> typedPayload is GaugeInstrumentKindPayload
            InstrumentKindCase.INDICATOR -> typedPayload is IndicatorInstrumentKindPayload
            else -> typedPayload == null
        }
        require(valid) { "Instrument kind does not match its typed payload" }
    }
}

class Instrument internal constructor(
    /** Skip-encoded for every package destination; present only in richer runtime snapshots. */
    val id: UUID?,
    val kind: InstrumentKind,
    val backgroundColor: CarPlayThemeOpaqueValue,
    val verifierIdentifier: String?,
    val shadowID: String?,
    val persistent: Boolean?,
    val blendMode: CarPlayThemeOpaqueValue?,
    val layoutConfiguration: CarPlayThemeOpaqueValue?,
    val configuration: CarPlayThemeOpaqueValue?,
    val backgroundContent: CarPlayThemeOpaqueValue?,
    /** Legacy flat projection only; the confirmed nested Configuration remains opaque. */
    val clipsToShape: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
) {
    val knownOutputAffinity: CarPlayThemeInstrumentOutputAffinity?
        get() = kind.knownOutputAffinity

    /** Null means the examined native predicate has not established this kind's output route. */
    fun isVisible(output: CarPlayThemeLayoutOutput): Boolean? = kind.isVisible(output)
}

enum class InputStreamTransformKind(val wireValue: String) {
    SCALE_TO_FIT("scaleToFit"),
    SCALE_TO_FILL("scaleToFill"),
}

enum class InputStreamAlignment(val wireValue: Int) {
    LEADING(1),
    CENTER(2),
    TRAILING(3),
    TOP(4),
    BOTTOM(5),
}

class InputStreamTransform internal constructor(
    val kind: InputStreamTransformKind,
    val alignment: InputStreamAlignment,
    val frame: CarPlayThemeOpaqueValue?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

class InputStreamPresentation internal constructor(
    val transform: InputStreamTransform?,
    val mask: CarPlayThemeOpaqueValue?,
    val visibleAreaVerifierIdentifierSuffix: String?,
    val clipToBounds: Boolean,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

/**
 * Strict semantic parser for confirmed CarPlay theme fields.
 *
 * Value-tree entry points neither decode bytes nor load or execute Lua. The explicitly named JSON
 * entry points first pass verified resource bytes through [decodeThemeJson]. [parseArcPackage]
 * follows confirmed direct relationships down through Display, Layout, Zone, Component, Slot, and
 * Instrument. Only fields whose wire meaning is unconfirmed remain opaque.
 */
object CarPlayThemeSchema {
    /** Strict byte-level entry point for verified `CarPlayPhone.assets/package.json`. */
    fun parseArcPackageJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ArcPackage = parseArcPackage(decodeThemeJson(bytes, limits), limits)

    fun parseArcPackage(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ArcPackage = parse(value, limits) { ThemeModelParser.parseArcPackage(it) }

    fun parseDisplay(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Display = parse(value, limits) { ThemeModelParser.parseDisplay(it, "Display") }

    fun parseLayout(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Layout = parse(value, limits) { ThemeModelParser.parseLayout(it, "Layout") }

    fun parseZone(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Zone = parse(value, limits) { ThemeModelParser.parseZone(it, "Zone") }

    fun parseZoneType(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ZoneType = parse(value, limits) { ThemeModelParser.parseZoneType(it, "ZoneType") }

    fun parseZoneShape(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ZoneShape = parse(value, limits) { ThemeModelParser.parseZoneShape(it, "Zone.Shape") }

    fun parseClippingStyle(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ClippingStyle = parse(value, limits) {
        ThemeModelParser.parseClippingStyle(it, "ClippingStyle")
    }

    fun parseClippingBehavior(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ClippingBehavior = parse(value, limits) {
        ThemeModelParser.parseClippingBehavior(it, "ClippingBehavior")
    }

    fun parseCustomMask(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): CustomMask = parse(value, limits) {
        ThemeModelParser.parseCustomMask(it, "CustomMask")
    }

    fun parseComponent(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Component = parse(value, limits) { ThemeModelParser.parseComponent(it, "Component") }

    fun parseSlot(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Slot = parse(value, limits) { ThemeModelParser.parseSlot(it, "Slot") }

    fun parseSlotStyle(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): SlotStyle = parse(value, limits) { ThemeModelParser.parseSlotStyle(it, "Slot.Style") }

    fun parseInstrument(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Instrument = parse(value, limits) { ThemeModelParser.parseInstrument(it) }

    fun parseInstrumentKind(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): InstrumentKind = parse(value, limits) {
        ThemeModelParser.parseInstrumentKind(it, "Instrument.Kind")
    }

    /** Parses only the confirmed GaugeConfiguration projection; no render readiness is implied. */
    fun parseGaugeConfiguration(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): GaugeConfiguration = parse(value, limits) {
        ThemeModelParser.parseGaugeConfiguration(it, "GaugeConfiguration")
    }

    /** Parses only the confirmed IndicatorConfiguration projection; no render readiness is implied. */
    fun parseIndicatorConfiguration(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): IndicatorConfiguration = parse(value, limits) {
        ThemeModelParser.parseIndicatorConfiguration(it, "IndicatorConfiguration")
    }

    fun parseInputStreamPresentation(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): InputStreamPresentation = parse(value, limits) {
        ThemeModelParser.parseInputStreamPresentation(it)
    }

    fun parseInputStreamTransform(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): InputStreamTransform = parse(value, limits) {
        ThemeModelParser.parseInputStreamTransform(it, "InputStreamTransform")
    }

    /** Parses the direct dictionary root of `asset_manifest.json`. */
    fun parseAssetManifest(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): AssetManifest = CarPlayAssetManifestSchema.parse(value, limits)

    /** Strict byte-level entry point for verified `asset_manifest.json`. */
    fun parseAssetManifestJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): AssetManifest = CarPlayAssetManifestSchema.parseJson(bytes, limits)

    /** Parses editable `theme.json`; no JSON byte decoding or behavior execution occurs here. */
    fun parseEditableTheme(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): EditableTheme = CarPlayEditableThemeSchema.parse(value, limits)

    /** Strict byte-level entry point for verified `theme.json`. */
    fun parseEditableThemeJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): EditableTheme = CarPlayEditableThemeSchema.parseJson(bytes, limits)

    /** Parses the resolved palette/layout projection. */
    fun parseResolvedTheme(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ResolvedTheme = CarPlayResolvedThemeSchema.parse(value, limits)

    /** Strict byte-level entry point for a verified resolved-theme JSON resource. */
    fun parseResolvedThemeJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ResolvedTheme = CarPlayResolvedThemeSchema.parseJson(bytes, limits)

    private inline fun <T> parse(
        value: Any?,
        limits: CarPlayThemeSchemaLimits,
        block: (Any?) -> T,
    ): T {
        val frozen = freezeCarPlayThemeInput(value, limits)
        return block(frozen)
    }
}

private object ThemeModelParser {
    fun parseArcPackage(value: Any?): ArcPackage {
        val fields = ThemeDictionary(value, "ArcPackage")
        return ArcPackage(
            identifier = fields.requiredString("identifier"),
            iOSContentVersion = fields.optionalString("iOSContentVersion"),
            version = parseUInt32(fields.required("version"), "ArcPackage.version"),
            minimumSDKVersion = fields.requiredString("minimumSDKVersion"),
            buildType = parseStringEnum(
                fields.requiredString("buildType"),
                ArcBuildType.entries,
                ArcBuildType::wireValue,
                "ArcPackage.buildType",
            ),
            vehicleTypes = fields.optional("vehicleTypes") { item ->
                opaqueList(item, "ArcPackage.vehicleTypes")
            },
            partnerID = fields.requiredString("partnerID"),
            displays = parseObjectList(fields.required("displays"), "ArcPackage.displays") {
                item, label -> parseDisplay(item, label)
            },
            driveModeDisplayNames = fields.optional("driveModeDisplayNames") { item ->
                parseStringMap(item, "ArcPackage.driveModeDisplayNames")
            },
            driveModeLinkedByDefault = fields.requiredBoolean("driveModeLinkedByDefault"),
            driveModeDynamicAssignmentConfiguration = fields.optionalOpaque(
                "driveModeDynamicAssignmentConfiguration",
            ),
            minimumCompatibilityVersion = fields.optional("minimumCompatibilityVersion") {
                parseInt32(it, "ArcPackage.minimumCompatibilityVersion")
            } ?: DEFAULT_MINIMUM_COMPATIBILITY_VERSION,
            vehicleType = fields.optionalOpaque("vehicleType"),
            extensions = fields.extensions(),
        )
    }

    fun parseDisplay(value: Any?, label: String): Display {
        val fields = ThemeDictionary(value, label)
        return Display(
            id = fields.optionalString("id"),
            type = parseStringEnum(
                fields.requiredString("type"),
                DisplayType.entries,
                DisplayType::wireValue,
                "$label.type",
            ),
            layouts = parseObjectList(fields.required("layouts"), "$label.layouts") {
                item, itemLabel -> parseLayout(item, itemLabel)
            },
            size = opaqueRequired(fields.required("size"), "$label.size"),
            density = parseFiniteNumber(fields.required("density"), "$label.density"),
            allowManufacturerAppIcon = fields.requiredBoolean("allowManufacturerAppIcon"),
            requiresDarkAppearance = fields.requiredBoolean("requiresDarkAppearance"),
            reversesDynamicContentDPadScrolling = fields.optionalBoolean(
                "reversesDynamicContentDPadScrolling",
            ),
            localNotifications = fields.optionalOpaque("localNotifications"),
            shadows = fields.optionalOpaque("shadows"),
            vectorFontManager = fields.optionalOpaque("vectorFontManager"),
            localizationManager = fields.optionalOpaque("localizationManager"),
            sendsButtonPressOnTouchDown = fields.optionalBoolean("sendsButtonPressOnTouchDown"),
            iPhoneVideoPosition = fields.optionalOpaque("iPhoneVideoPosition"),
            iPhoneVideoMask = fields.optionalOpaque("iPhoneVideoMask"),
            backgroundColor = fields.optionalOpaque("backgroundColor"),
            contentCornerRadius = fields.optional("contentCornerRadius") {
                parseFiniteNumber(it, "$label.contentCornerRadius")
            },
            contentCornerColor = fields.optionalOpaque("contentCornerColor"),
            pairingLogoAnimation = fields.optionalOpaque("pairingLogoAnimation"),
            driveModeLayoutIDs = fields.optionalOpaque("driveModeLayoutIDs"),
            assetBundle = fields.optionalOpaque("assetBundle"),
            remoteUIAdditionalPhases = fields.optionalOpaque("remoteUIAdditionalPhases"),
            wallpaperOverlay = fields.optionalOpaque("wallpaperOverlay"),
            wallpaperGlassMask = fields.optionalOpaque("wallpaperGlassMask"),
            background = fields.optionalOpaque("background"),
            shapeMask = fields.optionalOpaque("shapeMask"),
            interior = fields.optionalOpaque("interior"),
            wheelOcclusion = fields.optionalOpaque("wheelOcclusion"),
            manifestEntries = fields.optionalOpaque("manifestEntries"),
            visibilityRules = fields.optionalOpaque("visibilityRules"),
            extensions = fields.extensions(),
        )
    }

    fun parseLayout(value: Any?, label: String): Layout {
        val fields = ThemeDictionary(value, label)
        return Layout(
            id = fields.requiredString("id"),
            displayName = fields.optionalString("displayName"),
            zones = parseObjectList(fields.required("zones"), "$label.zones") {
                item, itemLabel -> parseZone(item, itemLabel)
            },
            layoutType = parseStringEnum(
                fields.requiredString("layoutType"),
                LayoutType.entries,
                LayoutType::wireValue,
                "$label.layoutType",
            ),
            systemUILayout = fields.optionalOpaque("systemUILayout"),
            backgroundColor = opaqueRequired(fields.required("backgroundColor"), "$label.backgroundColor"),
            safeAreaInsets = opaqueRequired(fields.required("safeAreaInsets"), "$label.safeAreaInsets"),
            layoutStyleID = fields.optionalString("layoutStyleID"),
            preview = fields.optionalOpaque("preview"),
            wallpapers = fields.optional("wallpapers") {
                opaqueList(it, "$label.wallpapers")
            },
            driveModeThemeConfigurations = opaqueRequired(
                fields.required("driveModeThemeConfigurations"),
                "$label.driveModeThemeConfigurations",
            ),
            extensions = fields.extensions(),
        )
    }

    fun parseZone(value: Any?, label: String): Zone {
        val fields = ThemeDictionary(value, label)
        return Zone(
            id = fields.requiredString("id"),
            frame = opaqueRequired(fields.required("frame"), "$label.frame"),
            components = parseObjectList(fields.required("components"), "$label.components") {
                item, itemLabel -> parseComponent(item, itemLabel)
            },
            zoneType = parseZoneType(fields.required("type"), "$label.type"),
            zoneRegion = fields.optional("zoneRegion") {
                parseStringEnum(
                    requireString(it, "$label.zoneRegion"),
                    ZoneRegion.entries,
                    ZoneRegion::wireValue,
                    "$label.zoneRegion",
                )
            },
            zoneShape = fields.optional("zoneShape") {
                parseZoneShape(it, "$label.zoneShape")
            },
            platterConfiguration = fields.optionalOpaque("platterConfiguration"),
            verifierIdentifier = fields.optionalString("verifierIdentifier"),
            padding = fields.optionalOpaque("padding"),
            visibilityConfiguration = fields.optionalOpaque("visibilityConfiguration"),
            extensions = fields.extensions(),
        )
    }

    fun parseZoneType(value: Any?, label: String): ZoneType {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        return ZoneType(
            case = parseStringEnum(
                caseName,
                ZoneTypeCase.entries,
                ZoneTypeCase::wireValue,
                label,
            ),
            payload = opaqueDictionary(payload, "$label.$caseName"),
        )
    }

    fun parseZoneShape(value: Any?, label: String): ZoneShape {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val shapeCase = parseStringEnum(
            caseName,
            ZoneShapeCase.entries,
            ZoneShapeCase::wireValue,
            label,
        )
        val fields = ThemeDictionary(payload, "$label.$caseName")
        val cornerRadius = when (shapeCase) {
            ZoneShapeCase.CIRCULAR -> {
                fields.rejectUnknown()
                null
            }
            ZoneShapeCase.RECTANGULAR -> {
                val radius = parseFiniteNumber(
                    fields.required("_0"),
                    "$label.$caseName._0",
                )
                fields.rejectUnknown()
                radius
            }
        }
        return ZoneShape(
            case = shapeCase,
            cornerRadius = cornerRadius,
            payload = opaqueDictionary(payload, "$label.$caseName"),
        )
    }

    fun parseClippingStyle(value: Any?, label: String): ClippingStyle {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val styleCase = parseStringEnum(
            caseName,
            ClippingStyleCase.entries,
            ClippingStyleCase::wireValue,
            label,
        )
        return ClippingStyle(
            case = styleCase,
            payload = opaqueDictionary(payload, "$label.$caseName"),
            customMask = if (styleCase == ClippingStyleCase.CUSTOM) {
                val fields = ThemeDictionary(payload, "$label.custom")
                val mask = parseCustomMask(fields.required("mask"), "$label.custom.mask")
                fields.extensions()
                mask
            } else {
                null
            },
        )
    }

    fun parseCustomMask(value: Any?, label: String): CustomMask {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        if (caseName != "image") {
            throw CarPlayThemeSchemaException("$label has an unknown enum value")
        }
        val fields = ThemeDictionary(payload, "$label.image")
        return CustomMask(
            image = CustomMaskImage(
                startingFrame = opaqueRequired(
                    fields.required("startingFrame"),
                    "$label.image.startingFrame",
                ),
                imageConfiguration = opaqueRequired(
                    fields.required("image"),
                    "$label.image.image",
                ),
                endingFrame = opaqueRequired(
                    fields.required("endingFrame"),
                    "$label.image.endingFrame",
                ),
                animation = opaqueRequired(
                    fields.required("animation"),
                    "$label.image.animation",
                ),
                extensions = fields.extensions(),
            ),
        )
    }

    fun parseClippingBehavior(value: Any?, label: String): ClippingBehavior {
        val fields = ThemeDictionary(value, label)
        return ClippingBehavior(
            transitioning = parseClippingStyle(
                fields.required("transitioning"),
                "$label.transitioning",
            ),
            notTransitioning = parseClippingStyle(
                fields.required("notTransitioning"),
                "$label.notTransitioning",
            ),
            extensions = fields.extensions(),
        )
    }

    fun parseComponent(value: Any?, label: String): Component {
        val fields = ThemeDictionary(value, label)
        return Component(
            id = fields.requiredString("id"),
            type = parseStringEnum(
                fields.requiredString("type"),
                ComponentType.entries,
                ComponentType::wireValue,
                "$label.type",
            ),
            size = opaqueRequired(fields.required("size"), "$label.size"),
            slots = parseObjectList(fields.required("slots"), "$label.slots") {
                item, itemLabel -> parseSlot(item, itemLabel)
            },
            trackable = fields.optionalBoolean("trackable"),
            clippingBehavior = fields.optional("clippingBehavior") {
                parseClippingBehavior(it, "$label.clippingBehavior")
            },
            extensions = fields.extensions(),
        )
    }

    fun parseSlot(value: Any?, label: String): Slot {
        val fields = ThemeDictionary(value, label)
        return Slot(
            id = fields.optional("id") {
                parseUuid(requireString(it, "$label.id"), "$label.id")
            },
            style = parseSlotStyle(fields.required("style"), "$label.style"),
            frame = opaqueRequired(fields.required("frame"), "$label.frame"),
            safeAreaInsets = fields.optionalOpaque("safeAreaInsets"),
            zIndex = parseInt32(fields.required("zIndex"), "$label.zIndex"),
            positionId = fields.requiredString("positionId"),
            instrument = fields.optional("instrument") {
                parseInstrument(it, "$label.instrument")
            },
            requirement = fields.optionalOpaque("requirement"),
            overrides = fields.optionalOpaque("overrides"),
            extensions = fields.extensions(),
        )
    }

    fun parseSlotStyle(value: Any?, label: String): SlotStyle {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val styleCase = parseStringEnum(
            caseName,
            SlotStyleCase.entries,
            SlotStyleCase::wireValue,
            label,
        )
        return SlotStyle(
            case = styleCase,
            payload = opaqueDictionary(payload, "$label.$caseName"),
            corner = if (styleCase == SlotStyleCase.CORNER) {
                val fields = ThemeDictionary(payload, "$label.corner")
                CornerSlotStyle(
                    mask = opaqueRequired(fields.required("mask"), "$label.corner.mask"),
                    ringRadius = parseFiniteNumber(
                        fields.required("ringRadius"),
                        "$label.corner.ringRadius",
                    ),
                    ringCenterPoint = opaqueRequired(
                        fields.required("ringCenterPoint"),
                        "$label.corner.ringCenterPoint",
                    ),
                    centralSlotPositionID = fields.requiredString("centralSlotPositionID"),
                    extensions = fields.extensions(),
                )
            } else {
                null
            },
        )
    }

    fun parseInstrument(value: Any?, label: String = "Instrument"): Instrument {
        val fields = ThemeDictionary(value, label)
        return Instrument(
            id = fields.optional("id") {
                parseUuid(requireString(it, "$label.id"), "$label.id")
            },
            kind = parseInstrumentKind(fields.required("kind"), "$label.kind"),
            backgroundColor = opaqueRequired(fields.required("backgroundColor"), "$label.backgroundColor"),
            verifierIdentifier = fields.optionalString("verifierIdentifier"),
            shadowID = fields.optionalString("shadowID"),
            persistent = fields.optionalBoolean("persistent"),
            blendMode = fields.optionalOpaque("blendMode"),
            layoutConfiguration = fields.optionalOpaque("layoutConfiguration"),
            configuration = fields.optionalOpaque("configuration"),
            backgroundContent = fields.optionalOpaque("backgroundContent"),
            clipsToShape = fields.optionalOpaque("clipsToShape"),
            extensions = fields.extensions(),
        )
    }

    fun parseInstrumentKind(value: Any?, label: String): InstrumentKind {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val kindCase = parseStringEnum(
            caseName,
            InstrumentKindCase.entries,
            InstrumentKindCase::wireValue,
            label,
        )
        return InstrumentKind(
            case = kindCase,
            payload = opaqueDictionary(payload, "$label.$caseName"),
            typedPayload = when (kindCase) {
                InstrumentKindCase.INPUT_STREAM -> parseInputStreamInstrumentKindPayload(
                    payload,
                    "$label.$caseName",
                )
                InstrumentKindCase.PUNCH_THROUGH -> parsePunchThroughInstrumentKindPayload(
                    payload,
                    "$label.$caseName",
                )
                InstrumentKindCase.PUNCH_THROUGH_TYPE ->
                    parsePunchThroughTypeInstrumentKindPayload(payload, "$label.$caseName")
                InstrumentKindCase.GAUGE -> GaugeInstrumentKindPayload(
                    parseGaugeConfiguration(
                        singleAssociatedValueField(payload, "configuration", "$label.gauge"),
                        "$label.gauge.configuration",
                    ),
                )
                InstrumentKindCase.INDICATOR -> IndicatorInstrumentKindPayload(
                    parseIndicatorConfiguration(
                        singleAssociatedValueField(payload, "configuration", "$label.indicator"),
                        "$label.indicator.configuration",
                    ),
                )
                else -> null
            },
        )
    }

    fun parseGaugeConfiguration(value: Any?, label: String): GaugeConfiguration {
        val fields = ThemeDictionary(value, label)
        return GaugeConfiguration(
            style = parseGaugeStyle(fields.required("style"), "$label.style"),
            rendersLocally = fields.requiredBoolean("rendersLocally"),
            visibilityConfiguration = opaqueRequired(
                fields.required("visibilityConfiguration"),
                "$label.visibilityConfiguration",
            ),
            extensions = fields.extensions(),
        )
    }

    fun parseIndicatorConfiguration(value: Any?, label: String): IndicatorConfiguration {
        val fields = ThemeDictionary(value, label)
        return IndicatorConfiguration(
            dataIdentifier = opaqueRequired(fields.required("dataIdentifier"), "$label.dataIdentifier"),
            style = parseIndicatorStyle(fields.required("style"), "$label.style"),
            presentationEdge = opaqueRequired(fields.required("presentationEdge"), "$label.presentationEdge"),
            containerInsets = opaqueRequired(fields.required("containerInsets"), "$label.containerInsets"),
            vehicleLanguageIdentifier = fields.optionalNullableOpaque("vehicleLanguageIdentifier"),
            extensions = fields.extensions(),
        )
    }

    private fun parseIndicatorStyle(value: Any?, label: String): IndicatorStyle {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val styleCase = parseStringEnum(
            caseName,
            IndicatorStyleCase.entries,
            IndicatorStyleCase::wireValue,
            label,
        )
        val fields = ThemeDictionary(payload, "$label.$caseName")
        val associatedConfiguration = when (styleCase) {
            IndicatorStyleCase.MESSAGE -> {
                fields.rejectUnknown()
                null
            }
            else -> {
                val configuration = opaqueDictionary(
                    fields.required("configuration"),
                    "$label.$caseName.configuration",
                )
                fields.rejectUnknown()
                configuration
            }
        }
        return IndicatorStyle(
            case = styleCase,
            payload = opaqueDictionary(payload, "$label.$caseName"),
            associatedConfiguration = associatedConfiguration,
        )
    }

    private fun parseGaugeStyle(value: Any?, label: String): GaugeStyle {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val styleCase = parseStringEnum(
            caseName,
            GaugeStyleCase.entries,
            GaugeStyleCase::wireValue,
            label,
        )
        return GaugeStyle(
            case = styleCase,
            payload = opaqueDictionary(payload, "$label.$caseName"),
            typedPayload = when (styleCase) {
                GaugeStyleCase.CIRCULAR -> CircularGaugeStylePayload(
                    parseGaugeCircularConfiguration(
                        singleAssociatedValueField(payload, "config", "$label.circular"),
                        "$label.circular.config",
                    ),
                )
                GaugeStyleCase.TEXT_ELEMENT -> {
                    val configuration = singleAssociatedValueField(
                        payload, "configuration", "$label.textElement",
                    )
                    val fields = ThemeDictionary(configuration, "$label.textElement.configuration")
                    TextElementGaugeStylePayload(
                        dataType = opaqueRequired(
                            fields.required("dataType"),
                            "$label.textElement.configuration.dataType",
                        ),
                        configuration = opaqueDictionary(
                            configuration,
                            "$label.textElement.configuration",
                        ),
                    )
                }
                GaugeStyleCase.SHAPE_LAYER -> {
                    val configuration = singleAssociatedValueField(
                        payload, "configuration", "$label.shapeLayer",
                    )
                    val fields = ThemeDictionary(configuration, "$label.shapeLayer.configuration")
                    ShapeLayerGaugeStylePayload(
                        pathElements = opaqueRequired(
                            fields.required("pathElements"),
                            "$label.shapeLayer.configuration.pathElements",
                        ),
                        lineCap = fields.optionalNullableOpaque("lineCap"),
                        fillColorIDs = fields.optionalNullableOpaque("fillColorIDs"),
                        strokeColorIDs = fields.optionalNullableOpaque("strokeColorIDs"),
                        lineWidth = fields.optionalNullableOpaque("lineWidth"),
                        dataSource = fields.optionalNullableOpaque("dataSource"),
                        configuration = opaqueDictionary(
                            configuration,
                            "$label.shapeLayer.configuration",
                        ),
                    )
                }
                GaugeStyleCase.IMAGE_GROUP -> {
                    val configuration = singleAssociatedValueField(
                        payload, "configuration", "$label.imageGroup",
                    )
                    val fields = ThemeDictionary(configuration, "$label.imageGroup.configuration")
                    ImageGroupGaugeStylePayload(
                        states = opaqueRequired(
                            fields.required("states"),
                            "$label.imageGroup.configuration.states",
                        ),
                        dataIdentifier = fields.optionalNullableOpaque("dataIdentifier"),
                        criticalStateDataIdentifier = fields.optionalNullableOpaque(
                            "criticalStateDataIdentifier",
                        ),
                        defaultState = fields.optionalNullableOpaque("defaultState"),
                        redactedState = fields.optionalNullableOpaque("redactedState"),
                        dataTransformationType = fields.optionalNullableOpaque(
                            "dataTransformationType",
                        ),
                        configuration = opaqueDictionary(
                            configuration,
                            "$label.imageGroup.configuration",
                        ),
                    )
                }
                GaugeStyleCase.COLOR -> {
                    val configuration = singleAssociatedValueField(
                        payload, "configuration", "$label.color",
                    )
                    val fields = ThemeDictionary(configuration, "$label.color.configuration")
                    val colorID = parseGaugeDynamicColorID(
                        fields.required("colorID"),
                        "$label.color.configuration.colorID",
                    )
                    fields.rejectUnknown()
                    ColorGaugeStylePayload(
                        colorID = colorID,
                        configuration = opaqueDictionary(
                            configuration,
                            "$label.color.configuration",
                        ),
                    )
                }
                GaugeStyleCase.PROGRESS_BAR -> ProgressBarGaugeStylePayload(
                    opaqueRequired(
                        singleAssociatedValueField(payload, "type", "$label.progressBar"),
                        "$label.progressBar.type",
                    ),
                )
                else -> null
            },
        )
    }

    private fun singleAssociatedValueField(value: Any?, key: String, label: String): Any? {
        val fields = ThemeDictionary(value, label)
        val result = fields.required(key)
        fields.rejectUnknown()
        return result
    }

    private fun parseGaugeCircularConfiguration(
        value: Any?,
        label: String,
    ): GaugeCircularConfiguration {
        val fields = ThemeDictionary(value, label)
        val rangeLabel = "$label.range"
        val rangeValues = fields.required("range") as? List<*>
            ?: throw CarPlayThemeSchemaException("$rangeLabel must be an array")
        if (rangeValues.size != 2) {
            throw CarPlayThemeSchemaException("$rangeLabel must have exactly two bounds")
        }
        val lowerBound = parseFiniteNumber(rangeValues[0], "$rangeLabel[0]")
        val upperBound = parseFiniteNumber(rangeValues[1], "$rangeLabel[1]")
        if (lowerBound > upperBound) {
            throw CarPlayThemeSchemaException("$rangeLabel lower bound exceeds upper bound")
        }
        return GaugeCircularConfiguration(
            dataIdentifier = opaqueRequired(fields.required("dataIdentifier"), "$label.dataIdentifier"),
            range = GaugeClosedDoubleRange(lowerBound, upperBound),
            iconName = opaqueRequired(fields.required("iconName"), "$label.iconName"),
            minLabel = opaqueRequired(fields.required("minLabel"), "$label.minLabel"),
            minLabelColor = opaqueRequired(fields.required("minLabelColor"), "$label.minLabelColor"),
            maxLabel = opaqueRequired(fields.required("maxLabel"), "$label.maxLabel"),
            maxLabelColor = opaqueRequired(fields.required("maxLabelColor"), "$label.maxLabelColor"),
            tintColors = opaqueRequired(fields.required("tintColors"), "$label.tintColors"),
            backgroundTintColors = opaqueRequired(
                fields.required("backgroundTintColors"),
                "$label.backgroundTintColors",
            ),
            extensions = fields.extensions(),
        )
    }

    private fun parseGaugeDynamicColorID(
        value: Any?,
        label: String,
    ): CarPlayThemeDynamic<CarPlayThemeOpaqueValue> {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val wrapped = singleAssociatedValueField(payload, "value", "$label.$caseName")
        return when (caseName) {
            "single" -> CarPlayThemeDynamic.Single(opaqueRequired(wrapped, "$label.single.value"))
            "dynamic" -> {
                val fields = ThemeDictionary(wrapped, "$label.dynamic.value")
                val lightMode = opaqueRequired(
                    fields.required("lightMode"),
                    "$label.dynamic.value.lightMode",
                )
                val darkMode = opaqueRequired(
                    fields.required("darkMode"),
                    "$label.dynamic.value.darkMode",
                )
                fields.rejectUnknown()
                CarPlayThemeDynamic.Appearance(lightMode, darkMode)
            }
            else -> throw CarPlayThemeSchemaException("$label has an unknown Dynamic case")
        }
    }

    private fun parseInputStreamInstrumentKindPayload(
        value: Any?,
        label: String,
    ): InputStreamInstrumentKindPayload {
        val fields = ThemeDictionary(value, label)
        val result = InputStreamInstrumentKindPayload(
            streamIdentifier = fields.requiredString("_0"),
            visibilityConfiguration = fields.optionalNullableOpaque("_1"),
            presentation = fields.optionalNullable("_2") {
                parseInputStreamPresentation(it, "$label._2")
            },
        )
        fields.rejectUnknown()
        return result
    }

    private fun parsePunchThroughInstrumentKindPayload(
        value: Any?,
        label: String,
    ): PunchThroughInstrumentKindPayload {
        val fields = ThemeDictionary(value, label)
        val result = PunchThroughInstrumentKindPayload(
            reference = fields.requiredString("_0"),
            instrumentDataIdentifier = fields.requiredString("_1"),
        )
        fields.rejectUnknown()
        return result
    }

    private fun parsePunchThroughTypeInstrumentKindPayload(
        value: Any?,
        label: String,
    ): PunchThroughTypeInstrumentKindPayload {
        val fields = ThemeDictionary(value, label)
        val result = PunchThroughTypeInstrumentKindPayload(
            punchThroughType = fields.requiredString("_0"),
            instrumentDataIdentifier = fields.optionalNullableString("_1"),
            presentation = fields.optionalNullable("_2") {
                parseInputStreamPresentation(it, "$label._2")
            },
        )
        fields.rejectUnknown()
        return result
    }

    fun parseInputStreamPresentation(
        value: Any?,
        label: String = "InputStreamPresentation",
    ): InputStreamPresentation {
        val fields = ThemeDictionary(value, label)
        return InputStreamPresentation(
            transform = fields.optional("transform") {
                parseInputStreamTransform(it, "$label.transform")
            },
            mask = fields.optionalOpaque("mask"),
            visibleAreaVerifierIdentifierSuffix = fields.optionalString(
                "visibleAreaVerifierIdentifierSuffix",
            ),
            clipToBounds = fields.requiredBoolean("clipToBounds"),
            extensions = fields.extensions(),
        )
    }

    fun parseInputStreamTransform(value: Any?, label: String): InputStreamTransform {
        val (caseName, payload) = parseOneKeyDictionary(value, label)
        val kind = parseStringEnum(
            caseName,
            InputStreamTransformKind.entries,
            InputStreamTransformKind::wireValue,
            label,
        )
        val fields = ThemeDictionary(payload, "$label.$caseName")
        return InputStreamTransform(
            kind = kind,
            alignment = parseIntEnum(
                fields.required("alignment"),
                InputStreamAlignment.entries,
                InputStreamAlignment::wireValue,
                "$label.$caseName.alignment",
            ),
            frame = fields.optionalOpaque("frame"),
            extensions = fields.extensions(),
        )
    }
}

private class ThemeDictionary(value: Any?, private val label: String) {
    private val remaining: LinkedHashMap<String, Any?>

    init {
        @Suppress("UNCHECKED_CAST")
        val source = value as? Map<String, Any?>
            ?: throw CarPlayThemeSchemaException("$label must be a dictionary")
        remaining = LinkedHashMap(source)
    }

    fun required(key: String): Any? {
        if (!remaining.containsKey(key)) {
            throw CarPlayThemeSchemaException("$label is missing required field '$key'")
        }
        return remaining.remove(key)
    }

    fun requiredString(key: String): String = requireString(required(key), "$label.$key")

    fun requiredBoolean(key: String): Boolean = requireBoolean(required(key), "$label.$key")

    fun optionalString(key: String): String? = optional(key) {
        requireString(it, "$label.$key")
    }

    fun optionalBoolean(key: String): Boolean? = optional(key) {
        requireBoolean(it, "$label.$key")
    }

    fun optionalNullableString(key: String): String? = optionalNullable(key) {
        requireString(it, "$label.$key")
    }

    fun optionalOpaque(key: String): CarPlayThemeOpaqueValue? = optional(key) {
        opaqueRequired(it, "$label.$key")
    }

    /** Matches Swift decodeIfPresent: both an absent key and an explicit null decode as null. */
    fun optionalNullableOpaque(key: String): CarPlayThemeOpaqueValue? = optionalNullable(key) {
        opaqueRequired(it, "$label.$key")
    }

    fun <T> optionalNullable(key: String, parser: (Any) -> T): T? {
        if (!remaining.containsKey(key)) return null
        val value = remaining.remove(key) ?: return null
        return parser(value)
    }

    fun <T> optional(key: String, parser: (Any?) -> T): T? {
        if (!remaining.containsKey(key)) return null
        return parser(remaining.remove(key))
    }

    fun extensions(): Map<String, CarPlayThemeOpaqueValue> {
        if (remaining.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, CarPlayThemeOpaqueValue>(remaining.size)
        remaining.forEach { (key, value) -> result[key] = CarPlayThemeOpaqueValue(value) }
        remaining.clear()
        return Collections.unmodifiableMap(result)
    }

    fun rejectUnknown() {
        if (remaining.isNotEmpty()) {
            throw CarPlayThemeSchemaException(
                "$label contains unknown field(s): ${remaining.keys.joinToString()}",
            )
        }
    }
}

private fun requireString(value: Any?, label: String): String =
    value as? String ?: throw CarPlayThemeSchemaException("$label must be a string")

private fun requireBoolean(value: Any?, label: String): Boolean =
    value as? Boolean ?: throw CarPlayThemeSchemaException("$label must be a boolean")

private fun parseFiniteNumber(value: Any?, label: String): Double {
    val number = value as? Number
        ?: throw CarPlayThemeSchemaException("$label must be numeric")
    if (number !is Byte && number !is Short && number !is Int && number !is Long &&
        number !is Float && number !is Double && number !is BigInteger && number !is BigDecimal
    ) {
        throw CarPlayThemeSchemaException("$label uses an unsupported numeric type")
    }
    val result = number.toDouble()
    if (!result.isFinite()) throw CarPlayThemeSchemaException("$label must be finite")
    return result
}

private fun parseUInt32(value: Any?, label: String): UInt {
    val integer = parseExactInteger(value, label)
    if (integer.signum() < 0 || integer > UINT32_MAX) {
        throw CarPlayThemeSchemaException("$label is outside the UInt range")
    }
    return integer.toLong().toUInt()
}

private fun parseInt32(value: Any?, label: String): Int {
    val integer = parseExactInteger(value, label)
    if (integer < INT32_MIN || integer > INT32_MAX) {
        throw CarPlayThemeSchemaException("$label is outside the Int range")
    }
    return integer.toInt()
}

private fun parseExactInteger(value: Any?, label: String): BigInteger = when (value) {
    is Byte -> BigInteger.valueOf(value.toLong())
    is Short -> BigInteger.valueOf(value.toLong())
    is Int -> BigInteger.valueOf(value.toLong())
    is Long -> BigInteger.valueOf(value)
    is BigInteger -> value
    else -> throw CarPlayThemeSchemaException("$label must be an integer")
}

private fun parseUuid(value: String, label: String): UUID {
    val parsed = try {
        UUID.fromString(value)
    } catch (error: IllegalArgumentException) {
        throw CarPlayThemeSchemaException("$label must be a UUID", error)
    }
    if (!parsed.toString().equals(value, ignoreCase = true)) {
        throw CarPlayThemeSchemaException("$label must use canonical UUID syntax")
    }
    return parsed
}

private fun <T> parseStringEnum(
    value: String,
    entries: Iterable<T>,
    wireValue: (T) -> String,
    label: String,
): T = entries.firstOrNull { wireValue(it) == value }
    ?: throw CarPlayThemeSchemaException("$label has an unknown enum value")

private fun <T> parseIntEnum(
    value: Any?,
    entries: Iterable<T>,
    wireValue: (T) -> Int,
    label: String,
): T {
    val raw = parseInt32(value, label)
    return entries.firstOrNull { wireValue(it) == raw }
        ?: throw CarPlayThemeSchemaException("$label has an unknown enum value")
}

private fun parseOneKeyDictionary(value: Any?, label: String): Pair<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val source = value as? Map<String, Any?>
        ?: throw CarPlayThemeSchemaException("$label must be a dictionary")
    if (source.size != 1) {
        throw CarPlayThemeSchemaException("$label must contain exactly one enum case")
    }
    val entry = source.entries.single()
    return entry.key to entry.value
}

private inline fun <T> parseObjectList(
    value: Any?,
    label: String,
    parser: (Any?, String) -> T,
): List<T> {
    val source = value as? List<*>
        ?: throw CarPlayThemeSchemaException("$label must be an array")
    val result = ArrayList<T>(source.size)
    source.forEachIndexed { index, item -> result += parser(item, "$label[$index]") }
    return Collections.unmodifiableList(result)
}

private fun opaqueList(value: Any?, label: String): List<CarPlayThemeOpaqueValue> {
    val source = value as? List<*>
        ?: throw CarPlayThemeSchemaException("$label must be an array")
    val result = source.mapIndexed { index, item -> opaqueRequired(item, "$label[$index]") }
    return Collections.unmodifiableList(result)
}

private fun parseStringMap(value: Any?, label: String): Map<String, String> {
    @Suppress("UNCHECKED_CAST")
    val source = value as? Map<String, Any?>
        ?: throw CarPlayThemeSchemaException("$label must be a dictionary")
    val result = LinkedHashMap<String, String>(source.size)
    source.forEach { (key, item) -> result[key] = requireString(item, "$label.$key") }
    return Collections.unmodifiableMap(result)
}

private fun opaqueRequired(value: Any?, label: String): CarPlayThemeOpaqueValue {
    if (value == null) throw CarPlayThemeSchemaException("$label must not be null")
    return CarPlayThemeOpaqueValue(value)
}

private fun opaqueDictionary(value: Any?, label: String): CarPlayThemeOpaqueValue {
    if (value !is Map<*, *>) throw CarPlayThemeSchemaException("$label must be a dictionary")
    return CarPlayThemeOpaqueValue(value)
}

/** Single bounded snapshot boundary shared by every parser in this package. */
internal fun freezeCarPlayThemeInput(
    value: Any?,
    limits: CarPlayThemeSchemaLimits,
): Any? = ThemeInputFreezer(limits).freezeRoot(value)

private class ThemeInputFreezer(private val limits: CarPlayThemeSchemaLimits) {
    private val active = IdentityHashMap<Any, Unit>()
    private val completed = IdentityHashMap<Any, Any>()
    private var nodes = 0
    private var collectionEntries = 0
    private var stringBytes = 0L
    private var dataBytes = 0L
    private var numberBytes = 0L

    fun freezeRoot(value: Any?): Any? = try {
        freeze(value, 0, "theme root")
    } catch (error: CarPlayThemeSchemaException) {
        throw error
    } catch (error: RuntimeException) {
        throw CarPlayThemeSchemaException("Theme input changed or failed while being snapshotted", error)
    }

    private fun freeze(value: Any?, depth: Int, label: String): Any? {
        consumeNode(depth)
        return when (value) {
            null,
            is Boolean,
            is Byte,
            is Short,
            is Int,
            is Long -> value

            is BigInteger -> {
                consumeNumber(bigIntegerBytes(value))
                value
            }

            is BigDecimal -> {
                if (value.scale() !in -MAXIMUM_DECIMAL_SCALE..MAXIMUM_DECIMAL_SCALE) {
                    throw CarPlayThemeSchemaException("$label decimal scale is outside the limit")
                }
                consumeNumber(bigIntegerBytes(value.unscaledValue()))
                value
            }

            is Float -> {
                if (!value.isFinite()) throw CarPlayThemeSchemaException("$label must be finite")
                value
            }

            is Double -> {
                if (!value.isFinite()) throw CarPlayThemeSchemaException("$label must be finite")
                value
            }

            is String -> {
                consumeString(value)
                value
            }

            is ByteArray -> {
                consumeData(value.size)
                value.copyOf()
            }

            is Map<*, *> -> freezeMap(value, depth, label)
            is List<*> -> freezeList(value, depth, label)
            else -> throw CarPlayThemeSchemaException(
                "$label has unsupported value type ${value.javaClass.name}",
            )
        }
    }

    private fun freezeMap(source: Map<*, *>, depth: Int, label: String): Map<String, Any?> {
        if (active.containsKey(source)) throw CarPlayThemeSchemaException("$label contains a cycle")
        @Suppress("UNCHECKED_CAST")
        completed[source]?.let { return it as Map<String, Any?> }

        ensureCollectionSize(source.size, label)
        active[source] = Unit
        try {
            val result = LinkedHashMap<String, Any?>(source.size)
            var actualSize = 0
            source.entries.forEach { entry ->
                actualSize += 1
                ensureCollectionSize(actualSize, label)
                consumeCollectionEntry()
                val key = entry.key as? String
                    ?: throw CarPlayThemeSchemaException("$label contains a non-string key")
                consumeString(key)
                if (result.containsKey(key)) {
                    throw CarPlayThemeSchemaException("$label contains a duplicate key")
                }
                result[key] = freeze(entry.value, depth + 1, "$label.$key")
            }
            val frozen = Collections.unmodifiableMap(result)
            completed[source] = frozen
            return frozen
        } finally {
            active.remove(source)
        }
    }

    private fun freezeList(source: List<*>, depth: Int, label: String): List<Any?> {
        if (active.containsKey(source)) throw CarPlayThemeSchemaException("$label contains a cycle")
        @Suppress("UNCHECKED_CAST")
        completed[source]?.let { return it as List<Any?> }

        ensureCollectionSize(source.size, label)
        active[source] = Unit
        try {
            val result = ArrayList<Any?>(source.size)
            source.forEachIndexed { index, item ->
                ensureCollectionSize(index + 1, label)
                consumeCollectionEntry()
                result += freeze(item, depth + 1, "$label[$index]")
            }
            val frozen = Collections.unmodifiableList(result)
            completed[source] = frozen
            return frozen
        } finally {
            active.remove(source)
        }
    }

    private fun consumeNode(depth: Int) {
        if (depth > limits.maximumNestingDepth) {
            throw CarPlayThemeSchemaException("Theme input exceeds its nesting-depth budget")
        }
        nodes += 1
        if (nodes > limits.maximumNodeCount) {
            throw CarPlayThemeSchemaException("Theme input exceeds its node budget")
        }
    }

    private fun ensureCollectionSize(size: Int, label: String) {
        if (size < 0 || size > limits.maximumCollectionEntries) {
            throw CarPlayThemeSchemaException("$label exceeds the collection-entry budget")
        }
    }

    private fun consumeCollectionEntry() {
        collectionEntries += 1
        if (collectionEntries > limits.maximumCollectionEntries) {
            throw CarPlayThemeSchemaException("Theme input exceeds its collection-entry budget")
        }
    }

    private fun consumeNumber(bytes: Long) {
        numberBytes = Math.addExact(numberBytes, bytes)
        if (numberBytes > limits.maximumNumberBytes) {
            throw CarPlayThemeSchemaException("Theme input exceeds its numeric-data budget")
        }
    }

    private fun bigIntegerBytes(value: BigInteger): Long =
        ((value.bitLength().toLong() + 8L) / 8L).coerceAtLeast(1L)

    private companion object {
        const val MAXIMUM_DECIMAL_SCALE = 1_000_000
    }

    private fun consumeString(value: String) {
        val remaining = limits.maximumStringBytes - stringBytes
        val length = utf8LengthAtMost(value, remaining)
            ?: throw CarPlayThemeSchemaException("Theme input exceeds its string-byte budget")
        stringBytes += length
    }

    private fun consumeData(size: Int) {
        if (size < 0 || size.toLong() > limits.maximumDataBytes - dataBytes) {
            throw CarPlayThemeSchemaException("Theme input exceeds its data-byte budget")
        }
        dataBytes += size
    }
}

private fun utf8LengthAtMost(value: String, maximum: Long): Long? {
    if (maximum < 0) return null
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val char = value[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            Character.isHighSurrogate(char) && index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1]) -> {
                index += 1
                4
            }
            else -> 3
        }
        if (bytes > maximum) return null
        index += 1
    }
    return bytes
}

private fun copyFrozenValue(value: Any?): Any? =
    FrozenValueCopier().copy(value)

private class FrozenValueCopier {
    private val completed = IdentityHashMap<Any, Any>()

    fun copy(value: Any?): Any? = when (value) {
        is ByteArray -> completed[value] ?: value.copyOf().also { completed[value] = it }
        is Map<*, *> -> copyMap(value)
        is List<*> -> copyList(value)
        else -> value
    }

    private fun copyMap(source: Map<*, *>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        completed[source]?.let { return it as Map<String, Any?> }
        val mutable = LinkedHashMap<String, Any?>(source.size)
        val result = Collections.unmodifiableMap(mutable)
        completed[source] = result
        source.forEach { (key, value) -> mutable[key as String] = copy(value) }
        return result
    }

    private fun copyList(source: List<*>): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        completed[source]?.let { return it as List<Any?> }
        val mutable = ArrayList<Any?>(source.size)
        val result = Collections.unmodifiableList(mutable)
        completed[source] = result
        source.forEach { mutable += copy(it) }
        return result
    }
}

private val UINT32_MAX = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)
private val INT32_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
private val INT32_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
private const val DEFAULT_MINIMUM_COMPATIBILITY_VERSION = 10
