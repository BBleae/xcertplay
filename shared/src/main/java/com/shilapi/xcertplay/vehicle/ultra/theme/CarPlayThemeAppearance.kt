package com.shilapi.xcertplay.vehicle.ultra.theme

import java.math.BigDecimal
import java.util.Collections

/**
 * The confirmed `Dynamic<T, T>` JSON cases. This is an immutable decoded value, not a paint
 * instruction: resolving an appearance does not establish compositing, gradient, or layer order.
 */
sealed interface CarPlayThemeDynamic<out T> {
    /** CarPlayAssetUI chooses dark only for `interfaceStyle == 2`; all other values choose light. */
    fun resolve(interfaceStyle: Int): T

    class Single<T> internal constructor(val value: T) : CarPlayThemeDynamic<T> {
        override fun resolve(interfaceStyle: Int): T = value
    }

    class Appearance<T> internal constructor(
        val lightMode: T,
        val darkMode: T,
    ) : CarPlayThemeDynamic<T> {
        override fun resolve(interfaceStyle: Int): T =
            if (interfaceStyle == 2) darkMode else lightMode
    }
}

/** A numeric stop position with its exact, un-normalized encoded name. */
class CarPlayThemeGradientStop internal constructor(
    val encodedPosition: String,
    val position: Double,
    val color: CarPlayThemeColor,
) {
    override fun equals(other: Any?): Boolean = other is CarPlayThemeGradientStop &&
        encodedPosition == other.encodedPosition &&
        position == other.position &&
        color == other.color

    override fun hashCode(): Int {
        var result = encodedPosition.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + color.hashCode()
        return result
    }

    override fun toString(): String =
        "CarPlayThemeGradientStop($encodedPosition, $position, $color)"
}

/**
 * `GradientColor` only establishes a dictionary of stops. Stop order here is deterministic
 * (numeric position, then exact key), but interpolation, spatial direction, and out-of-range
 * treatment remain unknown and MUST NOT be inferred from this type.
 */
class CarPlayThemeGradientColor internal constructor(stops: List<CarPlayThemeGradientStop>) {
    val stops: List<CarPlayThemeGradientStop> =
        Collections.unmodifiableList(ArrayList(stops))
}

/** Strict, bounded decoders for confirmed CarPlayAssetUI appearance payloads. */
object CarPlayThemeAppearanceSchema {
    /** Local resource-policy caps; they are not asserted as limits imposed by Apple. */
    const val MAXIMUM_GRADIENT_STOPS = 512
    const val MAXIMUM_PALETTE_COLOR_IDS = 512

    fun parseDynamicColorJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): CarPlayThemeDynamic<CarPlayThemeColor> =
        parseDynamicColor(decodeThemeJson(bytes, limits), limits)

    fun parseDynamicColor(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): CarPlayThemeDynamic<CarPlayThemeColor> =
        parseDynamicColorFrozen(freezeCarPlayThemeInput(value, limits), "Dynamic<Color,Color>")

    fun parseGradientColor(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): CarPlayThemeGradientColor =
        parseGradientColorFrozen(freezeCarPlayThemeInput(value, limits), "GradientColor")

    fun parseDynamicGradientColor(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): CarPlayThemeDynamic<CarPlayThemeGradientColor> = parseDynamicFrozen(
        freezeCarPlayThemeInput(value, limits),
        "Dynamic<GradientColor,GradientColor>",
        ::parseGradientColorFrozen,
    )

    /** A ColorsEncoder dictionary maps each exact ColorID to one dynamic or legacy gradient. */
    fun parsePaletteColors(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor>> =
        parsePaletteColorsFrozen(freezeCarPlayThemeInput(value, limits), "Palette.colors")

    internal fun parsePaletteColorsIfSupported(
        value: Any?,
        label: String,
    ): Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor>>? {
        val source = value as? Map<*, *> ?: return null
        // A wholly unknown future ColorsEncoder format remains available through Palette.colors.
        // Once every value names a confirmed case, malformed confirmed data is rejected strictly.
        if (source.values.any { !hasConfirmedGradientCase(it) }) return null
        return parsePaletteColorsFrozen(value, label)
    }

    internal fun parseDynamicColorFrozen(
        value: Any?,
        label: String,
    ): CarPlayThemeDynamic<CarPlayThemeColor> =
        parseDynamicFrozen(value, label, ::parseFixedColor)

    private fun <T> parseDynamicFrozen(
        value: Any?,
        label: String,
        parseValue: (Any?, String) -> T,
    ): CarPlayThemeDynamic<T> {
        val cases = strictDictionary(value, label)
        if (cases.size != 1) fail(label, "must contain exactly one enum case")
        val (caseName, payload) = cases.entries.single()
        val wrapper = strictDictionary(payload, "$label.$caseName")
        requireKeys(wrapper, setOf("value"), "$label.$caseName")
        val wrapped = wrapper.getValue("value")
        return when (caseName) {
            "single" -> CarPlayThemeDynamic.Single(parseValue(wrapped, "$label.single.value"))
            "dynamic" -> {
                val appearances = strictDictionary(wrapped, "$label.dynamic.value")
                requireKeys(
                    appearances,
                    setOf("lightMode", "darkMode"),
                    "$label.dynamic.value",
                )
                CarPlayThemeDynamic.Appearance(
                    lightMode = parseValue(
                        appearances.getValue("lightMode"),
                        "$label.dynamic.value.lightMode",
                    ),
                    darkMode = parseValue(
                        appearances.getValue("darkMode"),
                        "$label.dynamic.value.darkMode",
                    ),
                )
            }
            else -> fail(label, "has an unknown Dynamic case")
        }
    }

    private fun parseGradientColorFrozen(value: Any?, label: String): CarPlayThemeGradientColor {
        val fields = strictDictionary(value, label)
        requireKeys(fields, setOf("stops"), label)
        val encodedStops = strictDictionary(fields.getValue("stops"), "$label.stops")
        if (encodedStops.size > MAXIMUM_GRADIENT_STOPS) {
            fail("$label.stops", "exceeds the local stop-count limit")
        }
        val stops = encodedStops.map { (encodedPosition, rawColor) ->
            if (encodedPosition.length > 128 || !STOP_NUMBER.matches(encodedPosition)) {
                fail("$label.stops", "contains an invalid numeric stop key")
            }
            val exactPosition = try {
                BigDecimal(encodedPosition)
            } catch (_: NumberFormatException) {
                fail("$label.stops", "contains an invalid numeric stop key")
            }
            val position = encodedPosition.toDoubleOrNull()
            if (position == null || !position.isFinite() ||
                (position == 0.0 && exactPosition.signum() != 0)
            ) {
                fail("$label.stops", "contains a stop key outside the finite Double domain")
            }
            val stop = CarPlayThemeGradientStop(
                encodedPosition = encodedPosition,
                position = if (position == 0.0) 0.0 else position,
                color = parseFixedColor(rawColor, "$label.stops[$encodedPosition]"),
            )
            exactPosition to stop
        }.sortedWith(compareBy<Pair<BigDecimal, CarPlayThemeGradientStop>>(
            { it.first }, { it.second.encodedPosition },
        ))
        return CarPlayThemeGradientColor(stops.map { it.second })
    }

    private fun parsePaletteColorsFrozen(
        value: Any?,
        label: String,
    ): Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor>> {
        val colors = strictDictionary(value, label)
        if (colors.size > MAXIMUM_PALETTE_COLOR_IDS) {
            fail(label, "exceeds the local ColorID-count limit")
        }
        val result = LinkedHashMap<String, CarPlayThemeDynamic<CarPlayThemeGradientColor>>(colors.size)
        colors.keys.sorted().forEach { colorId ->
            val raw = colors.getValue(colorId)
            val colorLabel = "$label[$colorId]"
            result[colorId] = if (hasLegacyGradientCase(raw)) {
                CarPlayThemeDynamic.Single(parseGradientColorFrozen(raw, colorLabel))
            } else {
                parseDynamicFrozen(raw, colorLabel, ::parseGradientColorFrozen)
            }
        }
        return Collections.unmodifiableMap(result)
    }

    private fun hasConfirmedGradientCase(value: Any?): Boolean =
        hasLegacyGradientCase(value) ||
            ((value as? Map<*, *>)?.keys?.any { it == "single" || it == "dynamic" } == true)

    private fun hasLegacyGradientCase(value: Any?): Boolean =
        (value as? Map<*, *>)?.containsKey("stops") == true

    private fun parseFixedColor(value: Any?, label: String): CarPlayThemeColor =
        CarPlayThemeColor.parse(value)
            ?: fail(label, "is not a supported fixed Color value")

    private fun strictDictionary(value: Any?, label: String): Map<String, Any?> {
        val source = value as? Map<*, *> ?: fail(label, "must be a dictionary")
        if (source.keys.any { it !is String }) fail(label, "must have string keys")
        @Suppress("UNCHECKED_CAST")
        return source as Map<String, Any?>
    }

    private fun requireKeys(value: Map<String, Any?>, keys: Set<String>, label: String) {
        if (value.keys != keys) fail(label, "has missing or unknown keys")
    }

    private fun fail(label: String, problem: String): Nothing =
        throw CarPlayThemeSchemaException("$label $problem")

    private val STOP_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
