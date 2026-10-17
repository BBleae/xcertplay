package com.shilapi.xcertplay.airplay.rcs.video

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.BplistDecodeLimits
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackException
import com.shilapi.xcertplay.airplay.rcs.opack.OpackLimits
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

class CarPlayVideoSettingsProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** Limits for one detached VideoSettings application message. */
data class CarPlayVideoSettingsLimits(
    val maxRcsBodyBytes: Int = 64 * 1024,
    val maxPlistBytes: Int = 48 * 1024,
    val maxValueBytes: Int = 16 * 1024,
    val maxCollectionEntries: Int = 128,
    val maxObjects: Int = 512,
    val maxDepth: Int = 12,
) {
    init {
        require(maxRcsBodyBytes in 256..(1024 * 1024))
        require(maxPlistBytes in 128..maxRcsBodyBytes)
        require(maxValueBytes in 1..maxPlistBytes)
        require(maxCollectionEntries in 1..1024)
        require(maxObjects in 1..4096)
        require(maxDepth in 2..32)
    }
}

/**
 * The verified iOS 27 subset for CarPlayVideoSettings
 * (BB493F61-A6B8-4769-8D74-80C23A9F71C4, CARSessionChannel). Types 1-4 are phone-originated.
 * Type 5 carries a binary plist. Accessory-originated requests have only `params.data` in their
 * OPACK envelope. This model has no session, handler, registration or settings UI.
 */
sealed interface CarPlayVideoSettingsMessage {
    sealed interface FromPhone : CarPlayVideoSettingsMessage
    sealed interface ToPhone : CarPlayVideoSettingsMessage

    /** The subtitle dictionary's individual keys have not been established. */
    class SubtitleSettings(
        settings: Map<String, Any?>,
        bounds: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
    ) : FromPhone {
        private val owned = VideoSettingsPlistValues.snapshot(settings, bounds)
        val settings: Map<String, Any?> get() = VideoSettingsPlistValues.copyMap(owned)
    }

    data class Diagnostics(val enabled: Boolean) : FromPhone
    data object LicensesRequest : FromPhone
    data object AnalyticsRequest : FromPhone

    class PropertiesResponse(
        messageId: Number,
        values: Map<String, Any?>,
        bounds: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
    ) : FromPhone {
        val messageId: Number = VideoSettingsPlistValues.checkedMessageId(messageId)
        private val owned = VideoSettingsPlistValues.snapshot(values, bounds)
        val values: Map<String, Any?> get() = VideoSettingsPlistValues.copyMap(owned)
    }

    /** Identified control messages whose remaining fields have no verified schema. */
    class OpaquePropertyControl internal constructor(
        val controlMessage: String,
        fields: Map<String, Any?>,
        bounds: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
    ) : FromPhone {
        private val owned = VideoSettingsPlistValues.snapshot(fields, bounds)
        init {
            if ((owned as Map<*, *>)["controlMessage"] != controlMessage) {
                reject("Opaque control name does not match its fields")
            }
        }
        val fields: Map<String, Any?> get() = VideoSettingsPlistValues.copyMap(owned)
    }

    class PropertiesRequest(
        messageId: Number,
        properties: List<String>,
        bounds: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
    ) : ToPhone {
        val messageId: Number = VideoSettingsPlistValues.checkedMessageId(messageId)
        val properties: List<String> = boundedPropertiesCopy(properties, bounds.maxCollectionEntries)
    }

    class SetProperty(
        val property: String,
        value: Any,
        bounds: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
    ) : ToPhone {
        private val owned = VideoSettingsPlistValues.snapshot(value, bounds)
        val value: Any get() = VideoSettingsPlistValues.copy(owned)
    }

    data class ShowSettings(val page: String) : ToPhone
}

/** Strict, direction-aware application codec. It does not advertise or open an RCS channel. */
class CarPlayVideoSettingsCodec(
    val limits: CarPlayVideoSettingsLimits = CarPlayVideoSettingsLimits(),
) {
    private val opackLimits = OpackLimits(
        maxEncodedBytes = limits.maxRcsBodyBytes,
        maxValueBytes = limits.maxPlistBytes,
        maxCollectionEntries = limits.maxCollectionEntries,
        maxObjectTableEntries = limits.maxObjects,
        maxDepth = limits.maxDepth,
    )
    private val plistLimits = BplistDecodeLimits(
        maxInputBytes = limits.maxPlistBytes,
        maxObjects = limits.maxObjects,
        maxObjectBytes = limits.maxPlistBytes,
        maxNestingDepth = limits.maxDepth,
        maxContainerItems = limits.maxCollectionEntries,
        maxDataBytes = limits.maxValueBytes,
        maxStringBytes = limits.maxValueBytes,
        maxDecodedBytes = limits.maxPlistBytes.toLong() * 4L,
    )

    fun encodeFromPhone(message: CarPlayVideoSettingsMessage.FromPhone): RcsMessage {
        val outer: Map<String, Any?> = when (message) {
            is CarPlayVideoSettingsMessage.SubtitleSettings -> linkedMapOf(
                MESSAGE_TYPE to SUBTITLE_SETTINGS,
                DATA to VideoSettingsPlistValues.snapshot(message.settings, limits),
            )
            is CarPlayVideoSettingsMessage.Diagnostics -> linkedMapOf(
                MESSAGE_TYPE to DIAGNOSTICS,
                DATA to message.enabled,
            )
            CarPlayVideoSettingsMessage.LicensesRequest -> mapOf(MESSAGE_TYPE to LICENSES_REQUEST)
            CarPlayVideoSettingsMessage.AnalyticsRequest -> mapOf(MESSAGE_TYPE to ANALYTICS_REQUEST)
            is CarPlayVideoSettingsMessage.PropertiesResponse -> linkedMapOf(
                MESSAGE_TYPE to PROPERTY_MESSAGE,
                DATA to encodePlist(
                    linkedMapOf(
                        CONTROL_MESSAGE to PROPERTIES,
                        KIND to RESPONSE,
                        VALUES to message.values,
                        MESSAGE_ID to message.messageId,
                    ),
                ),
            )
            is CarPlayVideoSettingsMessage.OpaquePropertyControl -> {
                val fields = message.fields
                validateOpaquePhoneControl(fields)
                linkedMapOf(MESSAGE_TYPE to PROPERTY_MESSAGE, DATA to encodePlist(fields))
            }
        }
        return encodeOpack(outer)
    }

    /** Fixture encoder for the accessory direction; it never sends or registers a handler. */
    fun encodeToPhone(message: CarPlayVideoSettingsMessage.ToPhone): RcsMessage {
        val inner: Map<String, Any?> = when (message) {
            is CarPlayVideoSettingsMessage.PropertiesRequest -> {
                val properties = message.properties
                if (properties.size > limits.maxCollectionEntries) reject("Too many properties")
                properties.forEach { requiredName(it, "property name") }
                linkedMapOf(
                    CONTROL_MESSAGE to PROPERTIES,
                    MESSAGE_ID to message.messageId,
                    PROPERTIES to properties,
                )
            }
            is CarPlayVideoSettingsMessage.SetProperty -> linkedMapOf(
                CONTROL_MESSAGE to SET_PROPERTY,
                PROPERTY to requiredName(message.property, "property"),
                VALUE to message.value,
            )
            is CarPlayVideoSettingsMessage.ShowSettings -> linkedMapOf(
                CONTROL_MESSAGE to SHOW_SETTINGS,
                PARAMS to linkedMapOf(PAGE to requiredName(message.page, "page")),
            )
        }
        return encodeOpack(linkedMapOf(PARAMS to linkedMapOf(DATA to encodePlist(inner))))
    }

    fun decodeFromPhone(message: RcsMessage): CarPlayVideoSettingsMessage.FromPhone {
        val outer = decodeOpack(message)
        val type = outer[MESSAGE_TYPE] as? Long ?: reject("Phone messageType must be an integer")
        return when (type) {
            SUBTITLE_SETTINGS.toLong() -> {
                requireKeys(outer, setOf(MESSAGE_TYPE, DATA), "subtitle settings")
                val data = stringMap(outer[DATA], "subtitle settings data")
                CarPlayVideoSettingsMessage.SubtitleSettings(
                    VideoSettingsPlistValues.copyMap(VideoSettingsPlistValues.snapshot(data, limits)),
                    limits,
                )
            }
            DIAGNOSTICS.toLong() -> {
                requireKeys(outer, setOf(MESSAGE_TYPE, DATA), "diagnostics")
                CarPlayVideoSettingsMessage.Diagnostics(
                    outer[DATA] as? Boolean ?: reject("Diagnostics data must be Boolean"),
                )
            }
            LICENSES_REQUEST.toLong() -> {
                requireKeys(outer, setOf(MESSAGE_TYPE), "licenses request")
                CarPlayVideoSettingsMessage.LicensesRequest
            }
            ANALYTICS_REQUEST.toLong() -> {
                requireKeys(outer, setOf(MESSAGE_TYPE), "analytics request")
                CarPlayVideoSettingsMessage.AnalyticsRequest
            }
            PROPERTY_MESSAGE.toLong() -> {
                requireKeys(outer, setOf(MESSAGE_TYPE, DATA), "property message")
                val inner = decodePlist(outer[DATA])
                when (inner[CONTROL_MESSAGE]) {
                    PROPERTIES -> {
                        requireKeys(
                            inner,
                            setOf(CONTROL_MESSAGE, KIND, VALUES, MESSAGE_ID),
                            "properties response",
                        )
                        if (inner[KIND] != RESPONSE) reject("Properties message must be a response")
                        val id = VideoSettingsPlistValues.checkedMessageId(inner[MESSAGE_ID])
                        val values = stringMap(inner[VALUES], "properties response values")
                        CarPlayVideoSettingsMessage.PropertiesResponse(
                            id,
                            VideoSettingsPlistValues.copyMap(
                                VideoSettingsPlistValues.snapshot(values, limits),
                            ),
                            limits,
                        )
                    }
                    SET_PROPERTY, PROPERTY_CHANGED -> {
                        validateOpaquePhoneControl(inner)
                        CarPlayVideoSettingsMessage.OpaquePropertyControl(
                            inner[CONTROL_MESSAGE] as String,
                            inner,
                            limits,
                        )
                    }
                    else -> reject("Unknown phone property control message")
                }
            }
            else -> reject("Unknown phone VideoSettings messageType")
        }
    }

    fun decodeToPhone(message: RcsMessage): CarPlayVideoSettingsMessage.ToPhone {
        val outer = decodeOpack(message)
        requireKeys(outer, setOf(PARAMS), "accessory envelope")
        val params = stringMap(outer[PARAMS], "accessory params")
        requireKeys(params, setOf(DATA), "accessory params")
        val inner = decodePlist(params[DATA])
        return when (inner[CONTROL_MESSAGE]) {
            PROPERTIES -> {
                requireKeys(
                    inner,
                    setOf(CONTROL_MESSAGE, MESSAGE_ID, PROPERTIES),
                    "properties request",
                )
                val id = VideoSettingsPlistValues.checkedMessageId(inner[MESSAGE_ID])
                val names = inner[PROPERTIES] as? List<*>
                    ?: reject("Properties request requires an array")
                if (names.size > limits.maxCollectionEntries) reject("Too many properties")
                CarPlayVideoSettingsMessage.PropertiesRequest(
                    id,
                    names.map { requiredName(it, "property name") },
                    limits,
                )
            }
            SET_PROPERTY -> {
                requireKeys(inner, setOf(CONTROL_MESSAGE, PROPERTY, VALUE), "setProperty request")
                val property = requiredName(inner[PROPERTY], "property")
                val value = VideoSettingsPlistValues.snapshot(inner[VALUE], limits)
                CarPlayVideoSettingsMessage.SetProperty(property, value, limits)
            }
            SHOW_SETTINGS -> {
                requireKeys(inner, setOf(CONTROL_MESSAGE, PARAMS), "showSettings request")
                val innerParams = stringMap(inner[PARAMS], "showSettings params")
                requireKeys(innerParams, setOf(PAGE), "showSettings params")
                CarPlayVideoSettingsMessage.ShowSettings(requiredName(innerParams[PAGE], "page"))
            }
            else -> reject("Unknown accessory control message")
        }
    }

    private fun decodeOpack(message: RcsMessage): Map<String, Any?> {
        if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            reject("VideoSettings requires RCS COMM")
        }
        if (message.body.size > limits.maxRcsBodyBytes) reject("VideoSettings body is too large")
        val decoded = try {
            OpackCodec.decode(message.body, opackLimits)
        } catch (error: OpackException) {
            throw CarPlayVideoSettingsProtocolException("Invalid VideoSettings OPACK", error)
        }
        return stringMap(decoded, "VideoSettings envelope")
    }

    private fun encodeOpack(fields: Map<String, Any?>): RcsMessage = try {
        RcsMessage.comm(OpackCodec.encode(fields, opackLimits))
    } catch (error: OpackException) {
        throw CarPlayVideoSettingsProtocolException("Cannot encode VideoSettings OPACK", error)
    }

    private fun decodePlist(value: Any?): Map<String, Any?> {
        val data = value as? ByteArray ?: reject("Property data must be NSData")
        if (data.size > limits.maxPlistBytes) reject("Property plist is too large")
        ExactVideoSettingsBplistLayout.requireComplete(data, limits)
        val decoded = try {
            BplistCodec.decode(data, plistLimits)
        } catch (error: IllegalArgumentException) {
            throw CarPlayVideoSettingsProtocolException("Invalid VideoSettings bplist", error)
        }
        return stringMap(decoded, "VideoSettings bplist")
    }

    /** BplistCodec.encode has no limit argument: bound the entire graph before calling it. */
    private fun encodePlist(value: Map<String, Any?>): ByteArray {
        val bounded = VideoSettingsPlistValues.snapshot(value, limits)
        val encoded = try {
            BplistCodec.encode(bounded)
        } catch (error: IllegalArgumentException) {
            throw CarPlayVideoSettingsProtocolException("Cannot encode VideoSettings bplist", error)
        }
        if (encoded.size > limits.maxPlistBytes) reject("Property plist is too large")
        return encoded
    }

    private fun validateOpaquePhoneControl(fields: Map<String, Any?>) {
        when (fields[CONTROL_MESSAGE]) {
            SET_PROPERTY -> if (fields[KIND] != RESPONSE) {
                reject("setProperty phone message must be a response")
            }
            PROPERTY_CHANGED -> Unit
            else -> reject("Unknown opaque phone property control message")
        }
        VideoSettingsPlistValues.snapshot(fields, limits)
    }

    private fun stringMap(value: Any?, label: String): Map<String, Any?> {
        val map = value as? Map<*, *> ?: reject("$label must be a dictionary")
        if (map.size > limits.maxCollectionEntries) reject("$label has too many entries")
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, item) ->
            result[key as? String ?: reject("$label keys must be strings")] = item
        }
        return result
    }

    private fun requireKeys(value: Map<String, Any?>, expected: Set<String>, label: String) {
        if (value.keys != expected) reject("$label has missing or unexpected fields")
    }

    private fun requiredName(value: Any?, label: String): String {
        val name = value as? String ?: reject("$label must be a string")
        if (name.isEmpty() || name.toByteArray(Charsets.UTF_8).size > limits.maxValueBytes) {
            reject("$label is empty or too long")
        }
        return name
    }

    private companion object {
        const val MESSAGE_TYPE = "messageType"
        const val DATA = "data"
        const val PARAMS = "params"
        const val CONTROL_MESSAGE = "controlMessage"
        const val MESSAGE_ID = "messageID"
        const val PROPERTIES = "properties"
        const val PROPERTY = "property"
        const val VALUE = "value"
        const val PAGE = "page"
        const val KIND = "kind"
        const val RESPONSE = "response"
        const val VALUES = "values"
        const val SET_PROPERTY = "setProperty"
        const val SHOW_SETTINGS = "showSettings"
        const val PROPERTY_CHANGED = "propertyChanged"
        const val SUBTITLE_SETTINGS = 1
        const val DIAGNOSTICS = 2
        const val LICENSES_REQUEST = 3
        const val ANALYTICS_REQUEST = 4
        const val PROPERTY_MESSAGE = 5
    }
}

private fun reject(message: String): Nothing = throw CarPlayVideoSettingsProtocolException(message)

private fun boundedPropertiesCopy(source: List<String>, maximum: Int): List<String> {
    if (source.size > maximum) reject("Too many properties")
    val result = ArrayList<String>(source.size)
    for (name in source) {
        if (result.size >= maximum) reject("Too many properties")
        result += name
    }
    return Collections.unmodifiableList(result)
}

/** Copies only bplist-supported values and budgets the encoder's object graph before allocation. */
private object VideoSettingsPlistValues {
    fun snapshot(value: Any?, limits: CarPlayVideoSettingsLimits): Any {
        val budget = Budget(limits)
        return budget.copy(value, 0)
    }

    fun checkedMessageId(value: Any?): Number {
        val number = value as? Number ?: reject("messageID must be a number")
        when (number) {
            is Byte, is Short, is Int, is Long -> Unit
            is BigInteger -> checkBigInteger(number)
            is Float -> if (!number.isFinite()) reject("messageID must be finite")
            is Double -> if (!number.isFinite()) reject("messageID must be finite")
            else -> reject("messageID has an unsupported number type")
        }
        return number
    }

    @Suppress("UNCHECKED_CAST")
    fun copyMap(value: Any): Map<String, Any?> = copy(value) as Map<String, Any?>

    fun copy(value: Any): Any = when (value) {
        is ByteArray -> value.copyOf()
        is List<*> -> value.map { copy(it ?: reject("Null plist value")) }
        is Map<*, *> -> LinkedHashMap<String, Any?>(value.size).also { result ->
            value.forEach { (key, item) ->
                result[key as String] = copy(item ?: reject("Null plist value"))
            }
        }
        else -> value
    }

    private class Budget(private val limits: CarPlayVideoSettingsLimits) {
        private val active = IdentityHashMap<Any, Unit>()
        private var objects = 0
        private var estimatedBytes = 40L

        fun copy(value: Any?, depth: Int): Any {
            if (value == null) reject("Null is not supported in VideoSettings bplist")
            if (depth > limits.maxDepth) reject("VideoSettings plist is too deep")
            if (++objects > limits.maxObjects) reject("VideoSettings plist has too many objects")
            addEstimate(32)
            return when (value) {
                is Boolean, is Byte, is Short, is Int, is Long -> value
                is BigInteger -> checkBigInteger(value)
                is Float, is Double -> value
                is String -> {
                    if (value.length > limits.maxValueBytes ||
                        value.toByteArray(Charsets.UTF_8).size > limits.maxValueBytes
                    ) reject("VideoSettings string is too long")
                    addEstimate(value.length.toLong() * 2L)
                    value
                }
                is ByteArray -> {
                    if (value.size > limits.maxValueBytes) reject("VideoSettings data is too long")
                    addEstimate(value.size.toLong())
                    value.copyOf()
                }
                is List<*> -> withCollection(value) {
                    if (value.size > limits.maxCollectionEntries) reject("VideoSettings array is too large")
                    addEstimate(value.size.toLong() * 16L)
                    value.map { copy(it, depth + 1) }
                }
                is Map<*, *> -> withCollection(value) {
                    if (value.size > limits.maxCollectionEntries) reject("VideoSettings dictionary is too large")
                    addEstimate(value.size.toLong() * 16L)
                    LinkedHashMap<String, Any?>(value.size).also { result ->
                        value.forEach { (key, item) ->
                            if (key !is String) reject("VideoSettings dictionary keys must be strings")
                            val ownedKey = copy(key, depth + 1) as String
                            result[ownedKey] = copy(item, depth + 1)
                        }
                    }
                }
                else -> reject("Unsupported VideoSettings plist value")
            }
        }

        private inline fun <T> withCollection(collection: Any, block: () -> T): T {
            if (active.put(collection, Unit) != null) reject("Cyclic VideoSettings plist")
            try {
                return block()
            } finally {
                active.remove(collection)
            }
        }

        private fun addEstimate(bytes: Long) {
            estimatedBytes += bytes
            if (estimatedBytes > limits.maxPlistBytes) {
                reject("VideoSettings plist exceeds preflight budget")
            }
        }
    }

    private fun checkBigInteger(value: BigInteger): BigInteger {
        if (value.bitLength() > 127 || (value.signum() >= 0 && value.bitLength() == 64)) {
            // BplistCodec currently decodes this unsigned 64-bit range as a signed Long.
            reject("VideoSettings integer cannot round-trip through bplist")
        }
        return value
    }
}
