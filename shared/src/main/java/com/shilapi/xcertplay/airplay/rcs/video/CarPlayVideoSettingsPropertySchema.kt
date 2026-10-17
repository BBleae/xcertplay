package com.shilapi.xcertplay.airplay.rcs.video

import java.math.BigInteger
import java.util.Collections

/** A read-only interpretation of the property values observed in iOS 27 CarPlayVideoSettings. */
object CarPlayVideoSettingsPropertySchema {
    const val DND_BUTTON_STATE = "property_key_dndButtonState"
    const val CLOSED_CAPTIONS_ENABLED = "property_key_ClosedCaptionsAndSDH_Enabled"
    const val SELECTED_CAPTION_STYLE = "property_key_captionstyles_selectedstyle"
    const val CUSTOM_CAPTION_STYLES = "property_key_captionstyles_customstyles"

    /** Only non-nil values for these two keys appeared in initial relayChannelProperties. */
    val initialRelayKnownPropertyNames: Set<String> = Collections.unmodifiableSet(
        linkedSetOf(CLOSED_CAPTIONS_ENABLED, SELECTED_CAPTION_STYLE),
    )

    fun project(response: CarPlayVideoSettingsMessage.PropertiesResponse):
        CarPlayVideoSettingsPropertySnapshot = CarPlayVideoSettingsPropertySnapshot(response.values)

    /** Uses the codec's bounded plist snapshot rules before interpreting an untrusted map. */
    fun project(
        values: Map<String, Any?>,
        limits: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
    ): CarPlayVideoSettingsPropertySnapshot = project(
        CarPlayVideoSettingsMessage.PropertiesResponse(0L, values, limits),
    )

    fun project(request: CarPlayVideoSettingsMessage.SetProperty): CarPlayVideoSettingsPropertyValue =
        interpret(request.property, request.value)

    internal fun interpret(property: String, value: Any): CarPlayVideoSettingsPropertyValue =
        when (property) {
            DND_BUTTON_STATE -> number(value)?.let {
                CarPlayVideoSettingsPropertyValue.DndButtonState(it)
            } ?: CarPlayVideoSettingsPropertyValue.Opaque(value)
            CLOSED_CAPTIONS_ENABLED -> number(value)?.let {
                CarPlayVideoSettingsPropertyValue.ClosedCaptionsEnabled(it)
            } ?: CarPlayVideoSettingsPropertyValue.Opaque(value)
            SELECTED_CAPTION_STYLE -> (value as? String)?.let {
                CarPlayVideoSettingsPropertyValue.SelectedCaptionStyle(it)
            } ?: CarPlayVideoSettingsPropertyValue.Opaque(value)
            CUSTOM_CAPTION_STYLES -> customStyles(value)
                ?: CarPlayVideoSettingsPropertyValue.Opaque(value)
            else -> CarPlayVideoSettingsPropertyValue.Opaque(value)
        }

    private fun customStyles(value: Any): CarPlayVideoSettingsPropertyValue.CustomCaptionStyles? {
        val array = value as? List<*> ?: return null
        val styles = ArrayList<CarPlayVideoCustomCaptionStyle>(array.size)
        for (entry in array) {
            val dictionary = entry as? Map<*, *> ?: return null
            if (dictionary.keys.any { it !is String } || dictionary.values.any { it == null }) {
                return null
            }
            @Suppress("UNCHECKED_CAST")
            styles += CarPlayVideoCustomCaptionStyle(dictionary as Map<String, Any>)
        }
        return CarPlayVideoSettingsPropertyValue.CustomCaptionStyles(styles)
    }

    internal fun number(value: Any?): CarPlayVideoSettingsNumber? = when (value) {
        is Boolean, is Byte, is Short, is Int, is Long, is BigInteger, is Float, is Double ->
            CarPlayVideoSettingsNumber(value)
        else -> null
    }
}

/** A detached property map. Unknown names and mismatched known values remain opaque. */
class CarPlayVideoSettingsPropertySnapshot internal constructor(values: Map<String, Any?>) {
    val values: Map<String, CarPlayVideoSettingsPropertyValue> = Collections.unmodifiableMap(
        LinkedHashMap<String, CarPlayVideoSettingsPropertyValue>(values.size).also { typed ->
            values.forEach { (name, value) ->
                typed[name] = CarPlayVideoSettingsPropertySchema.interpret(
                    name,
                    value ?: throw CarPlayVideoSettingsProtocolException("Null plist value"),
                )
            }
        },
    )

    operator fun get(property: String): CarPlayVideoSettingsPropertyValue? = values[property]
}

/** bplist booleans and numeric values both represent Foundation NSNumber instances. */
class CarPlayVideoSettingsNumber internal constructor(val raw: Any) {
    init {
        require(raw is Boolean || raw is Number)
    }

    /** Mirrors NSNumber boolValue's zero/nonzero conversion without limiting numeric ranges. */
    val boolValue: Boolean get() = when (raw) {
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0
        else -> error("Unreachable NSNumber value")
    }
}

sealed interface CarPlayVideoSettingsPropertyValue {
    class DndButtonState internal constructor(val number: CarPlayVideoSettingsNumber) :
        CarPlayVideoSettingsPropertyValue

    class ClosedCaptionsEnabled internal constructor(val number: CarPlayVideoSettingsNumber) :
        CarPlayVideoSettingsPropertyValue {
        val enabled: Boolean get() = number.boolValue
    }

    data class SelectedCaptionStyle(val style: String) : CarPlayVideoSettingsPropertyValue

    class CustomCaptionStyles internal constructor(styles: List<CarPlayVideoCustomCaptionStyle>) :
        CarPlayVideoSettingsPropertyValue {
        val styles: List<CarPlayVideoCustomCaptionStyle> =
            Collections.unmodifiableList(ArrayList(styles))
    }

    class Opaque internal constructor(private val owned: Any) : CarPlayVideoSettingsPropertyValue {
        val raw: Any get() = copyVideoSettingsValue(owned)
    }
}

/** All fields are optional in the observed custom-style dictionary representation. */
enum class CarPlayVideoCustomStyleField(val wireKey: String, val kind: Kind) {
    UNIQUE_ID("uniqueId", Kind.TEXT),
    DESCRIPTION("description", Kind.TEXT),
    FONT("font", Kind.NUMBER),
    SIZE("size", Kind.NUMBER),
    TEXT_COLOR("textColor", Kind.NUMBER),
    BACKGROUND_COLOR("backgroundColor", Kind.NUMBER),
    BACKGROUND_OPACITY("backgroundOpacity", Kind.NUMBER),
    TEXT_OPACITY("textOpacity", Kind.NUMBER),
    EDGE_STYLE("edgeStyle", Kind.NUMBER),
    HIGHLIGHT_COLOR("highlightColor", Kind.NUMBER),
    HIGHLIGHT_OPACITY("highlightOpacity", Kind.NUMBER),
    VIDEO_SHOULD_OVERRIDE_FONT("videoShouldOverrideFont", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_SIZE("videoShouldOverrideSize", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_TEXT_COLOR("videoShouldOverrideTextColor", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_BACKGROUND_COLOR("videoShouldOverrideBackgroundColor", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_BACKGROUND_OPACITY("videoShouldOverrideBackgroundOpacity", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_TEXT_OPACITY("videoShouldOverrideTextOpacity", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_EDGE_STYLE("videoShouldOverrideEdgeStyle", Kind.BOXED_BOOLEAN),
    VIDEO_SHOULD_OVERRIDE_TEXT_HIGHLIGHT("videoShouldOverrideTextHighlight", Kind.BOXED_BOOLEAN);

    enum class Kind { TEXT, NUMBER, BOXED_BOOLEAN }
}

sealed interface CarPlayVideoCustomStyleValue {
    data class Text(val value: String) : CarPlayVideoCustomStyleValue
    class Number(val number: CarPlayVideoSettingsNumber) : CarPlayVideoCustomStyleValue
    data class BoxedBoolean(val value: Boolean) : CarPlayVideoCustomStyleValue
}

class CarPlayVideoCustomCaptionStyle internal constructor(private val ownedFields: Map<String, Any>) {
    val knownFields: Map<CarPlayVideoCustomStyleField, CarPlayVideoCustomStyleValue> =
        Collections.unmodifiableMap(
            LinkedHashMap<CarPlayVideoCustomStyleField, CarPlayVideoCustomStyleValue>().also { typed ->
                CarPlayVideoCustomStyleField.entries.forEach { field ->
                    val raw = ownedFields[field.wireKey] ?: return@forEach
                    val value = when (field.kind) {
                        CarPlayVideoCustomStyleField.Kind.TEXT ->
                            (raw as? String)?.let(CarPlayVideoCustomStyleValue::Text)
                        CarPlayVideoCustomStyleField.Kind.NUMBER ->
                            (raw as? kotlin.Number)?.let {
                                CarPlayVideoCustomStyleValue.Number(CarPlayVideoSettingsNumber(it))
                            }
                        CarPlayVideoCustomStyleField.Kind.BOXED_BOOLEAN ->
                            (raw as? Boolean)?.let(CarPlayVideoCustomStyleValue::BoxedBoolean)
                    }
                    if (value != null) typed[field] = value
                }
            },
        )

    /** Includes unknown keys and mismatched known fields without exposing owned mutable values. */
    @Suppress("UNCHECKED_CAST")
    val rawFields: Map<String, Any> get() = copyVideoSettingsValue(ownedFields) as Map<String, Any>
}

private fun copyVideoSettingsValue(value: Any): Any = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> value.map { copyVideoSettingsValue(it ?: error("Null plist value")) }
    is Map<*, *> -> LinkedHashMap<String, Any>(value.size).also { result ->
        value.forEach { (key, item) ->
            result[key as String] = copyVideoSettingsValue(item ?: error("Null plist value"))
        }
    }
    else -> value
}
