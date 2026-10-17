package com.shilapi.xcertplay.airplay.rcs.caf

import java.math.BigInteger
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

@JvmInline
value class CafTypeId(val value: Long) {
    val unsignedValue: BigInteger get() = CafUInt64.toBigInteger(value)
    fun hex(): String = "0x${unsignedValue.toString(16).padStart(16, '0')}"
}

@JvmInline
value class CafIid(val value: Long) {
    val unsignedValue: BigInteger get() = CafUInt64.toBigInteger(value)
    override fun toString(): String = CafUInt64.toString(value)
}

/** The framework derives a stable UUID from BE64(pluginID) || BE64(instanceID). */
object CafEntityIdentifier {
    fun uuid(pluginId: Long, instanceId: Long): UUID {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(pluginId)
            .putLong(instanceId)
            .array()
        val reader = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return UUID(reader.long, reader.long)
    }
}

enum class CafCharacteristicFormat(val wireValue: Long) {
    BOOL(0),
    UINT8(1),
    UINT16(2),
    UINT32(3),
    UINT64(4),
    INT8(5),
    INT16(6),
    INT32(7),
    INT64(8),
    FLOAT(9),
    STRING(10),
    DATA(11),
    DICTIONARY(12),
    ARRAY(13),
    ;

    companion object {
        fun fromWireValue(value: Any?): CafCharacteristicFormat {
            val raw = CafNumbers.toUInt64(value, "CAF characteristic format")
            if (raw !in 0L..13L) {
                throw CafProtocolException(
                    "CAF characteristic format ${CafUInt64.toString(raw)} is outside 0..13",
                )
            }
            return entries[raw.toInt()]
        }
    }
}

sealed interface CafFormatValue {
    data object Invalid : CafFormatValue
    data class Bool(val value: Boolean) : CafFormatValue
    data class Unsigned(val value: BigInteger, val bits: Int) : CafFormatValue
    data class Signed(val value: BigInteger, val bits: Int) : CafFormatValue
    data class Floating(val value: Double) : CafFormatValue
    data class Text(val value: String) : CafFormatValue
    class Binary(val value: ByteArray) : CafFormatValue {
        override fun equals(other: Any?): Boolean = other is Binary && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): String = "Binary(${value.size} bytes)"
    }
    data class Dictionary(val value: Map<*, *>) : CafFormatValue
    data class ArrayValue(val value: List<*>) : CafFormatValue

    companion object {
        fun decode(
            value: Any?,
            format: CafCharacteristicFormat,
            label: String,
            supportsInvalid: Boolean = false,
        ): CafFormatValue {
            if (value == null) {
                if (supportsInvalid) return Invalid
                throw CafProtocolException("$label must not be null")
            }
            return when (format) {
                CafCharacteristicFormat.BOOL -> decodeBool(value, label)
                CafCharacteristicFormat.UINT8 -> decodeUnsigned(value, 8, label)
                CafCharacteristicFormat.UINT16 -> decodeUnsigned(value, 16, label)
                CafCharacteristicFormat.UINT32 -> decodeUnsigned(value, 32, label)
                CafCharacteristicFormat.UINT64 -> decodeUnsigned(value, 64, label)
                CafCharacteristicFormat.INT8 -> decodeSigned(value, 8, label)
                CafCharacteristicFormat.INT16 -> decodeSigned(value, 16, label)
                CafCharacteristicFormat.INT32 -> decodeSigned(value, 32, label)
                CafCharacteristicFormat.INT64 -> decodeSigned(value, 64, label)
                CafCharacteristicFormat.FLOAT -> {
                    val number = (value as? Number)?.toDouble()
                        ?: throw CafProtocolException("$label must be numeric")
                    if (!number.isFinite()) throw CafProtocolException("$label must be finite")
                    Floating(number)
                }
                CafCharacteristicFormat.STRING -> Text(
                    value as? String ?: throw CafProtocolException("$label must be a string"),
                )
                CafCharacteristicFormat.DATA -> Binary(
                    (value as? ByteArray)?.copyOf()
                        ?: throw CafProtocolException("$label must be NSData"),
                )
                CafCharacteristicFormat.DICTIONARY -> Dictionary(
                    value as? Map<*, *> ?: throw CafProtocolException("$label must be a dictionary"),
                )
                CafCharacteristicFormat.ARRAY -> ArrayValue(
                    value as? List<*> ?: throw CafProtocolException("$label must be an array"),
                )
            }
        }

        private fun decodeBool(value: Any?, label: String): Bool = when (value) {
            is Boolean -> Bool(value)
            is Byte, is Short, is Int, is Long, is BigInteger -> when (
                CafNumbers.toSignedLong(value, label)
            ) {
                0L -> Bool(false)
                1L -> Bool(true)
                else -> throw CafProtocolException("$label must be 0 or 1")
            }
            else -> throw CafProtocolException("$label must be a boolean")
        }

        private fun decodeUnsigned(value: Any?, bits: Int, label: String): Unsigned {
            val integer = when (value) {
                is Byte -> BigInteger.valueOf(value.toLong())
                is Short -> BigInteger.valueOf(value.toLong())
                is Int -> BigInteger.valueOf(value.toLong())
                is Long -> if (bits == 64) CafUInt64.toBigInteger(value) else BigInteger.valueOf(value)
                is BigInteger -> value
                else -> throw CafProtocolException("$label must be an integer")
            }
            val maximum = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            if (integer.signum() < 0 || integer > maximum) {
                throw CafProtocolException("$label is outside the unsigned $bits-bit range")
            }
            return Unsigned(integer, bits)
        }

        private fun decodeSigned(value: Any?, bits: Int, label: String): Signed {
            val integer = when (value) {
                is Byte -> BigInteger.valueOf(value.toLong())
                is Short -> BigInteger.valueOf(value.toLong())
                is Int -> BigInteger.valueOf(value.toLong())
                is Long -> BigInteger.valueOf(value)
                is BigInteger -> value
                else -> throw CafProtocolException("$label must be an integer")
            }
            val limit = BigInteger.ONE.shiftLeft(bits - 1)
            if (integer < limit.negate() || integer >= limit) {
                throw CafProtocolException("$label is outside the signed $bits-bit range")
            }
            return Signed(integer, bits)
        }
    }
}

data class CafControlParameterMetadata(
    val name: String,
    val format: CafCharacteristicFormat,
    val supportsInvalid: Boolean,
    val extensions: Map<String, Any?>,
)

enum class CafControlSender(val wireName: String) {
    DEVICE("device"),
    ACCESSORY("accessory"),
    ;

    companion object {
        fun fromWireName(value: String): CafControlSender =
            entries.firstOrNull { it.wireName == value }
                ?: throw CafProtocolException("Unknown CAF control sender '$value'")
    }
}

enum class CafStateChannelKind {
    ERROR,
    DISABLED,
    NOTIFIER,
    RESTRICTED,
    HIDDEN,
}

data class CafStateChannels(
    val error: CafIid? = null,
    val disabled: CafIid? = null,
    val notifier: CafIid? = null,
    val restricted: CafIid? = null,
    val hidden: CafIid? = null,
) {
    fun asMap(): Map<CafStateChannelKind, CafIid> = buildMap {
        error?.let { put(CafStateChannelKind.ERROR, it) }
        disabled?.let { put(CafStateChannelKind.DISABLED, it) }
        notifier?.let { put(CafStateChannelKind.NOTIFIER, it) }
        restricted?.let { put(CafStateChannelKind.RESTRICTED, it) }
        hidden?.let { put(CafStateChannelKind.HIDDEN, it) }
    }
}

data class CafControlMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val sender: CafControlSender,
    val hasResponse: Boolean,
    val priority: Long?,
    val requestParameters: List<CafControlParameterMetadata>,
    val responseParameters: List<CafControlParameterMetadata>,
    val stateChannels: CafStateChannels,
    val extensions: Map<String, Any?>,
) {
    val iidError: CafIid? get() = stateChannels.error
    val iidDisabled: CafIid? get() = stateChannels.disabled
    val iidRestricted: CafIid? get() = stateChannels.restricted
    val iidHidden: CafIid? get() = stateChannels.hidden

    fun validateRequestPayload(value: Any?) {
        validateParameterPayload(value, requestParameters, "CAF control request ${iid}")
    }

    fun validateResponsePayload(value: Any?) {
        if (!hasResponse) {
            if (value != null) {
                throw CafProtocolException("CAF control ${iid} does not declare a response")
            }
            return
        }
        validateParameterPayload(value, responseParameters, "CAF control response ${iid}")
    }
}

private fun validateParameterPayload(
    value: Any?,
    parameters: List<CafControlParameterMetadata>,
    label: String,
) {
    if (parameters.isEmpty()) {
        if (value != null) throw CafProtocolException("$label must be null")
        return
    }
    val source = value as? Map<*, *>
        ?: throw CafProtocolException("$label must be a dictionary")
    val values = LinkedHashMap<String, Any?>(source.size)
    source.forEach { (key, item) ->
        val name = key as? String ?: throw CafProtocolException("$label field names must be strings")
        if (values.containsKey(name)) throw CafProtocolException("$label contains duplicate field '$name'")
        values[name] = item
    }
    val expectedNames = parameters.map(CafControlParameterMetadata::name).toSet()
    if (values.keys != expectedNames || values.size != parameters.size) {
        throw CafProtocolException("$label fields must exactly match $expectedNames")
    }
    parameters.forEach { parameter ->
        CafFormatValue.decode(
            value = values[parameter.name],
            format = parameter.format,
            label = "$label.${parameter.name}",
            supportsInvalid = parameter.supportsInvalid,
        )
    }
}

data class CafCharacteristicMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val format: CafCharacteristicFormat,
    val writable: Boolean?,
    val mutable: Boolean?,
    val initialValue: CafFormatValue?,
    val priority: Long?,
    val largePayload: Boolean?,
    val supportsInvalid: Boolean,
    val stateChannels: CafStateChannels,
    val minimumValue: CafFormatValue?,
    val maximumValue: CafFormatValue?,
    val maximumLength: Int?,
    val stepValue: CafFormatValue?,
    val validValues: List<CafFormatValue>,
    val units: Any?,
    val oemWritable: Boolean?,
    val extensions: Map<String, Any?>,
) {
    val iidError: CafIid? get() = stateChannels.error
    val iidDisabled: CafIid? get() = stateChannels.disabled
    val iidNotifier: CafIid? get() = stateChannels.notifier
    val iidRestricted: CafIid? get() = stateChannels.restricted
    val iidHidden: CafIid? get() = stateChannels.hidden

    init {
        validateConstraintDefinition()
    }

    fun validateValue(value: Any?): CafFormatValue {
        val decoded = CafFormatValue.decode(
            value = value,
            format = format,
            label = "CAF IID ${iid}",
            supportsInvalid = supportsInvalid,
        )
        validateDecodedValue(decoded, enforceValidValues = true)
        return decoded
    }

    private fun validateConstraintDefinition() {
        initialValue?.requireFormat(format, supportsInvalid, "initialValue")
        minimumValue?.requireFormat(format, allowInvalid = false, field = "minimumValue")
        maximumValue?.requireFormat(format, allowInvalid = false, field = "maximumValue")
        stepValue?.requireFormat(format, allowInvalid = false, field = "stepValue")
        validValues.forEach { it.requireFormat(format, supportsInvalid, "validValues") }
        maximumLength?.let { maximum ->
            if (maximum < 0) throw CafProtocolException("CAF IID $iid maximumLength is negative")
            if (format !in LENGTH_FORMATS) {
                throw CafProtocolException("CAF IID $iid uses maximumLength with $format")
            }
        }
        if ((minimumValue != null || maximumValue != null || stepValue != null) &&
            format !in NUMERIC_FORMATS
        ) {
            throw CafProtocolException("CAF IID $iid uses numeric constraints with $format")
        }
        if (minimumValue != null && maximumValue != null &&
            compareNumeric(minimumValue, maximumValue) > 0
        ) {
            throw CafProtocolException("CAF IID $iid minimumValue exceeds maximumValue")
        }
        stepValue?.let { step ->
            if (!isPositiveNumeric(step)) {
                throw CafProtocolException("CAF IID $iid stepValue must be positive")
            }
        }
        initialValue?.let { validateDecodedValue(it, enforceValidValues = true) }
        validValues.forEach { validateDecodedValue(it, enforceValidValues = false) }
    }

    private fun validateDecodedValue(
        value: CafFormatValue,
        enforceValidValues: Boolean,
    ) {
        if (value == CafFormatValue.Invalid) {
            if (!supportsInvalid) throw CafProtocolException("CAF IID $iid does not support invalid")
            return
        }
        minimumValue?.let { minimum ->
            if (compareNumeric(value, minimum) < 0) {
                throw CafProtocolException("CAF IID $iid is below minimumValue")
            }
        }
        maximumValue?.let { maximum ->
            if (compareNumeric(value, maximum) > 0) {
                throw CafProtocolException("CAF IID $iid exceeds maximumValue")
            }
        }
        maximumLength?.let { maximum ->
            val length = value.collectionLength()
                ?: throw CafProtocolException("CAF IID $iid value has no length")
            if (length > maximum) {
                throw CafProtocolException("CAF IID $iid exceeds maximumLength")
            }
        }
        stepValue?.let { step ->
            val base = minimumValue ?: zeroFor(format)
            if (!isOnStep(value, base, step)) {
                throw CafProtocolException("CAF IID $iid is not aligned to stepValue")
            }
        }
        if (enforceValidValues && validValues.isNotEmpty() &&
            validValues.none { allowed -> cafValueEquals(value, allowed) }
        ) {
            throw CafProtocolException("CAF IID $iid is not in validValues")
        }
    }

    private companion object {
        val NUMERIC_FORMATS = setOf(
            CafCharacteristicFormat.BOOL,
            CafCharacteristicFormat.UINT8,
            CafCharacteristicFormat.UINT16,
            CafCharacteristicFormat.UINT32,
            CafCharacteristicFormat.UINT64,
            CafCharacteristicFormat.INT8,
            CafCharacteristicFormat.INT16,
            CafCharacteristicFormat.INT32,
            CafCharacteristicFormat.INT64,
            CafCharacteristicFormat.FLOAT,
        )
        val LENGTH_FORMATS = setOf(
            CafCharacteristicFormat.STRING,
            CafCharacteristicFormat.DATA,
            CafCharacteristicFormat.DICTIONARY,
            CafCharacteristicFormat.ARRAY,
        )
    }
}

private fun CafFormatValue.requireFormat(
    format: CafCharacteristicFormat,
    allowInvalid: Boolean,
    field: String,
) {
    val matches = when (format) {
        CafCharacteristicFormat.BOOL -> this is CafFormatValue.Bool
        CafCharacteristicFormat.UINT8 -> this is CafFormatValue.Unsigned && bits == 8
        CafCharacteristicFormat.UINT16 -> this is CafFormatValue.Unsigned && bits == 16
        CafCharacteristicFormat.UINT32 -> this is CafFormatValue.Unsigned && bits == 32
        CafCharacteristicFormat.UINT64 -> this is CafFormatValue.Unsigned && bits == 64
        CafCharacteristicFormat.INT8 -> this is CafFormatValue.Signed && bits == 8
        CafCharacteristicFormat.INT16 -> this is CafFormatValue.Signed && bits == 16
        CafCharacteristicFormat.INT32 -> this is CafFormatValue.Signed && bits == 32
        CafCharacteristicFormat.INT64 -> this is CafFormatValue.Signed && bits == 64
        CafCharacteristicFormat.FLOAT -> this is CafFormatValue.Floating
        CafCharacteristicFormat.STRING -> this is CafFormatValue.Text
        CafCharacteristicFormat.DATA -> this is CafFormatValue.Binary
        CafCharacteristicFormat.DICTIONARY -> this is CafFormatValue.Dictionary
        CafCharacteristicFormat.ARRAY -> this is CafFormatValue.ArrayValue
    }
    if (!matches && !(this == CafFormatValue.Invalid && allowInvalid)) {
        throw CafProtocolException("CAF $field does not match $format")
    }
}

private fun compareNumeric(left: CafFormatValue, right: CafFormatValue): Int = when {
    left is CafFormatValue.Bool && right is CafFormatValue.Bool ->
        left.value.compareTo(right.value)
    left is CafFormatValue.Unsigned && right is CafFormatValue.Unsigned ->
        left.value.compareTo(right.value)
    left is CafFormatValue.Signed && right is CafFormatValue.Signed ->
        left.value.compareTo(right.value)
    left is CafFormatValue.Floating && right is CafFormatValue.Floating ->
        left.value.compareTo(right.value)
    else -> throw CafProtocolException("CAF numeric constraint formats do not match")
}

private fun isPositiveNumeric(value: CafFormatValue): Boolean = when (value) {
    is CafFormatValue.Bool -> value.value
    is CafFormatValue.Unsigned -> value.value.signum() > 0
    is CafFormatValue.Signed -> value.value.signum() > 0
    is CafFormatValue.Floating -> value.value > 0.0 && value.value.isFinite()
    else -> false
}

private fun zeroFor(format: CafCharacteristicFormat): CafFormatValue = when (format) {
    CafCharacteristicFormat.BOOL -> CafFormatValue.Bool(false)
    CafCharacteristicFormat.UINT8 -> CafFormatValue.Unsigned(BigInteger.ZERO, 8)
    CafCharacteristicFormat.UINT16 -> CafFormatValue.Unsigned(BigInteger.ZERO, 16)
    CafCharacteristicFormat.UINT32 -> CafFormatValue.Unsigned(BigInteger.ZERO, 32)
    CafCharacteristicFormat.UINT64 -> CafFormatValue.Unsigned(BigInteger.ZERO, 64)
    CafCharacteristicFormat.INT8 -> CafFormatValue.Signed(BigInteger.ZERO, 8)
    CafCharacteristicFormat.INT16 -> CafFormatValue.Signed(BigInteger.ZERO, 16)
    CafCharacteristicFormat.INT32 -> CafFormatValue.Signed(BigInteger.ZERO, 32)
    CafCharacteristicFormat.INT64 -> CafFormatValue.Signed(BigInteger.ZERO, 64)
    CafCharacteristicFormat.FLOAT -> CafFormatValue.Floating(0.0)
    else -> throw CafProtocolException("CAF $format does not support stepValue")
}

private fun isOnStep(
    value: CafFormatValue,
    base: CafFormatValue,
    step: CafFormatValue,
): Boolean = when {
    value is CafFormatValue.Bool && base is CafFormatValue.Bool && step is CafFormatValue.Bool -> {
        val current = if (value.value) BigInteger.ONE else BigInteger.ZERO
        val origin = if (base.value) BigInteger.ONE else BigInteger.ZERO
        val increment = if (step.value) BigInteger.ONE else BigInteger.ZERO
        increment.signum() > 0 && current.subtract(origin).mod(increment) == BigInteger.ZERO
    }
    value is CafFormatValue.Unsigned && base is CafFormatValue.Unsigned &&
        step is CafFormatValue.Unsigned -> {
        val delta = value.value.subtract(base.value)
        delta.signum() >= 0 && delta.mod(step.value) == BigInteger.ZERO
    }
    value is CafFormatValue.Signed && base is CafFormatValue.Signed &&
        step is CafFormatValue.Signed -> {
        val delta = value.value.subtract(base.value)
        delta.remainder(step.value) == BigInteger.ZERO
    }
    value is CafFormatValue.Floating && base is CafFormatValue.Floating &&
        step is CafFormatValue.Floating -> floatIsOnStep(value.value, base.value, step.value)
    else -> throw CafProtocolException("CAF stepValue format does not match characteristic")
}

private fun floatIsOnStep(value: Double, base: Double, step: Double): Boolean {
    if (!value.isFinite() || !base.isFinite() || !step.isFinite() || step <= 0.0) return false
    val quotient = (value - base) / step
    if (!quotient.isFinite()) return false
    val nearest = Math.rint(quotient)
    val reconstructed = base + nearest * step
    val tolerance = maxOf(
        1e-12,
        kotlin.math.abs(step) * 1e-7,
        Math.ulp(value) * 4.0,
        Math.ulp(reconstructed) * 4.0,
    )
    return kotlin.math.abs(value - reconstructed) <= tolerance
}

private fun CafFormatValue.collectionLength(): Int? = when (this) {
    is CafFormatValue.Text -> value.length
    is CafFormatValue.Binary -> value.size
    is CafFormatValue.Dictionary -> value.size
    is CafFormatValue.ArrayValue -> value.size
    else -> null
}

private fun cafValueEquals(left: CafFormatValue, right: CafFormatValue): Boolean = when {
    left is CafFormatValue.Bool && right is CafFormatValue.Bool -> left.value == right.value
    left is CafFormatValue.Unsigned && right is CafFormatValue.Unsigned ->
        left.bits == right.bits && left.value == right.value
    left is CafFormatValue.Signed && right is CafFormatValue.Signed ->
        left.bits == right.bits && left.value == right.value
    left is CafFormatValue.Floating && right is CafFormatValue.Floating ->
        floatingEquals(left.value, right.value)
    left is CafFormatValue.Text && right is CafFormatValue.Text -> left.value == right.value
    left is CafFormatValue.Binary && right is CafFormatValue.Binary ->
        left.value.contentEquals(right.value)
    left is CafFormatValue.Dictionary && right is CafFormatValue.Dictionary ->
        deepWireEquals(left.value, right.value)
    left is CafFormatValue.ArrayValue && right is CafFormatValue.ArrayValue ->
        deepWireEquals(left.value, right.value)
    left == CafFormatValue.Invalid && right == CafFormatValue.Invalid -> true
    else -> false
}

private fun floatingEquals(left: Double, right: Double): Boolean {
    if (left == right) return true
    val tolerance = maxOf(
        1e-12,
        maxOf(kotlin.math.abs(left), kotlin.math.abs(right)) * 1e-12,
        Math.ulp(left) * 4.0,
        Math.ulp(right) * 4.0,
    )
    return kotlin.math.abs(left - right) <= tolerance
}

private fun deepWireEquals(left: Any?, right: Any?): Boolean = when {
    left === right -> true
    left is ByteArray && right is ByteArray -> left.contentEquals(right)
    left is Number && right is Number -> numericWireEquals(left, right)
    left is List<*> && right is List<*> ->
        left.size == right.size && left.indices.all { deepWireEquals(left[it], right[it]) }
    left is Map<*, *> && right is Map<*, *> -> {
        if (left.size != right.size) {
            false
        } else {
            val unmatched = right.entries.toMutableList()
            left.entries.all { leftEntry ->
                val index = unmatched.indexOfFirst { rightEntry ->
                    deepWireEquals(leftEntry.key, rightEntry.key) &&
                        deepWireEquals(leftEntry.value, rightEntry.value)
                }
                if (index < 0) false else {
                    unmatched.removeAt(index)
                    true
                }
            }
        }
    }
    else -> left == right
}

private fun numericWireEquals(left: Number, right: Number): Boolean = try {
    if (left is Float || left is Double || right is Float || right is Double) {
        floatingEquals(left.toDouble(), right.toDouble())
    } else {
        BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
    }
} catch (_: NumberFormatException) {
    false
}

data class CafServiceMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val characteristics: List<CafCharacteristicMetadata>,
    val controls: List<CafControlMetadata>,
    val multipleInstances: Boolean?,
    val indexBy: Any?,
    val sortBy: Any?,
    val extensions: Map<String, Any?>,
)

data class CafAccessoryMetadata(
    val type: CafTypeId,
    val iid: CafIid,
    val version: String?,
    val services: List<CafServiceMetadata>,
    val extensions: Map<String, Any?>,
)

data class CafConfigTree(
    val root: Map<String, Any?>,
    val accessories: List<CafAccessoryMetadata>,
    val extensions: Map<String, Any?>,
) {
    val characteristicsByIid: Map<Long, CafCharacteristicMetadata> = accessories
        .flatMap(CafAccessoryMetadata::services)
        .flatMap(CafServiceMetadata::characteristics)
        .associateBy { it.iid.value }
    val controlsByIid: Map<Long, CafControlMetadata> = accessories
        .flatMap(CafAccessoryMetadata::services)
        .flatMap(CafServiceMetadata::controls)
        .associateBy { it.iid.value }

    fun valueIidsForPriority(priority: Int?): Set<Long> = characteristicsByIid.values
        .asSequence()
        .filter { metadata ->
            priority == null || metadata.priority == priority.toLong()
        }
        .map { it.iid.value }
        .toSet()

    fun validateCharacteristicValue(iid: Long, value: Any?): CafFormatValue {
        val metadata = characteristicsByIid[iid]
            ?: throw CafProtocolException("Unknown CAF characteristic IID ${CafUInt64.toString(iid)}")
        return metadata.validateValue(value)
    }

    companion object {
        fun decode(raw: Any?): CafConfigTree = CafFirmwareConfigTreeDecoder.decode(raw)
    }
}

fun interface CafConfigTreeDecoder {
    fun decode(raw: Any?): CafConfigTree
}

fun interface CafConfigTreeDecoderFactory {
    fun create(protocolVersion: String, pluginConfig: Any?): CafConfigTreeDecoder

    /** Empty means diagnostic/unidentified and is never accepted by an authorized manifest. */
    val implementationId: String
        get() = ""
}

class ProtocolCafConfigTreeDecoderFactory(
    private val protocolDecoders: Map<String, CafConfigTreeDecoder> =
        mapOf("1.0" to CafFirmwareConfigTreeDecoder),
    private val pluginDecoderSelector: (String, Any?) -> CafConfigTreeDecoder? = { _, _ -> null },
) : CafConfigTreeDecoderFactory {
    override val implementationId: String = "xcertplay.caf.decoder.firmware-v1"

    override fun create(protocolVersion: String, pluginConfig: Any?): CafConfigTreeDecoder {
        pluginDecoderSelector(protocolVersion, pluginConfig)?.let { return it }
        return protocolDecoders[protocolVersion]
            ?: throw CafProtocolException("No CAF config decoder for protocol '$protocolVersion'")
    }

    companion object {
        val FIRMWARE_V1: CafConfigTreeDecoderFactory = ProtocolCafConfigTreeDecoderFactory()
    }
}

data class CafConfigLimits(
    val maxAccessories: Int = 128,
    val maxServices: Int = 1024,
    val maxCharacteristics: Int = 8192,
    val maxControls: Int = 4096,
    val maxParameters: Int = 256,
    val maxStringChars: Int = 16 * 1024,
    val maxDepth: Int = 32,
    val maxNodes: Int = 512 * 1024,
    val maxCollectionEntries: Int = 256 * 1024,
    val maxTotalStringChars: Int = 4 * 1024 * 1024,
    val maxDataBytes: Int = 4 * 1024 * 1024,
    val maxTotalDataBytes: Long = 16L * 1024 * 1024,
) {
    init {
        require(maxAccessories > 0)
        require(maxServices > 0)
        require(maxCharacteristics > 0)
        require(maxControls > 0)
        require(maxParameters > 0)
        require(maxStringChars > 0)
        require(maxDepth > 0)
        require(maxNodes > 0)
        require(maxCollectionEntries > 0)
        require(maxTotalStringChars >= maxStringChars)
        require(maxDataBytes >= 0)
        require(maxTotalDataBytes >= maxDataBytes.toLong())
    }

    companion object {
        val DEFAULT = CafConfigLimits()
    }
}

object CafFirmwareConfigTreeDecoder : CafConfigTreeDecoder {
    override fun decode(raw: Any?): CafConfigTree = Decoder(CafConfigLimits.DEFAULT).decode(raw)

    internal fun decode(raw: Any?, limits: CafConfigLimits): CafConfigTree =
        Decoder(limits).decode(raw)

    private class Decoder(private val limits: CafConfigLimits) {
        private var serviceCount = 0
        private var characteristicCount = 0
        private var controlCount = 0

        fun decode(raw: Any?): CafConfigTree {
            val root = freezeCafWireMap(raw, limits)
            val accessories = requireList(root["accessories"], "CAF config tree.accessories")
            if (accessories.isEmpty()) throw CafProtocolException("CAF config tree has no accessories")
            if (accessories.size > limits.maxAccessories) {
                throw CafProtocolException("CAF config tree has too many accessories")
            }
            val decoded = accessories.mapIndexed { index, value ->
                decodeAccessory(value, "CAF accessory[$index]")
            }
            val tree = CafConfigTree(
                root = root,
                accessories = decoded,
                extensions = root.unknownFields(ROOT_KEYS),
            )
            validateIdentifiers(tree)
            validateStateChannels(tree)
            return tree
        }

        private fun decodeAccessory(raw: Any?, label: String): CafAccessoryMetadata {
            val map = requireStringMap(raw, label)
            val services = map.optionalList("services", "$label.services").mapIndexed { index, item ->
                serviceCount++
                if (serviceCount > limits.maxServices) throw CafProtocolException("CAF config has too many services")
                decodeService(item, "$label.services[$index]")
            }
            return CafAccessoryMetadata(
                type = map.requiredType("type", label),
                iid = map.requiredIid("iid", label),
                version = map.optionalString("version", label),
                services = services,
                extensions = map.unknownFields(ACCESSORY_KEYS),
            )
        }

        private fun decodeService(raw: Any?, label: String): CafServiceMetadata {
            val map = requireStringMap(raw, label)
            val characteristics = map.optionalList("characteristics", "$label.characteristics")
                .mapIndexed { index, item ->
                    characteristicCount++
                    if (characteristicCount > limits.maxCharacteristics) {
                        throw CafProtocolException("CAF config has too many characteristics")
                    }
                    decodeCharacteristic(item, "$label.characteristics[$index]")
                }
            val controls = map.optionalList("controls", "$label.controls").mapIndexed { index, item ->
                controlCount++
                if (controlCount > limits.maxControls) throw CafProtocolException("CAF config has too many controls")
                decodeControl(item, "$label.controls[$index]")
            }
            return CafServiceMetadata(
                type = map.requiredType("type", label),
                iid = map.requiredIid("iid", label),
                characteristics = characteristics,
                controls = controls,
                multipleInstances = map.optionalBoolean("multipleInstances", label),
                indexBy = map["indexBy"],
                sortBy = map["sortBy"],
                extensions = map.unknownFields(SERVICE_KEYS),
            )
        }

        private fun decodeCharacteristic(raw: Any?, label: String): CafCharacteristicMetadata {
            val map = requireStringMap(raw, label)
            val format = CafCharacteristicFormat.fromWireValue(map.required("format", label))
            val supportsInvalid = map.optionalBoolean("supportsInvalid", label) ?: false
            fun optionalFormat(key: String): CafFormatValue? = if (map.containsKey(key)) {
                CafFormatValue.decode(map[key], format, "$label.$key", supportsInvalid)
            } else {
                null
            }
            val validValues = map.optionalList("validValues", "$label.validValues")
                .mapIndexed { index, item ->
                    CafFormatValue.decode(item, format, "$label.validValues[$index]", supportsInvalid)
                }
            return CafCharacteristicMetadata(
                type = map.requiredType("type", label),
                iid = map.requiredIid("iid", label),
                format = format,
                writable = map.optionalBoolean("writable", label),
                mutable = map.optionalBoolean("mutable", label),
                initialValue = optionalFormat("initialValue"),
                priority = map.optionalSignedLong("priority", label),
                largePayload = map.optionalBoolean("largePayload", label),
                supportsInvalid = supportsInvalid,
                stateChannels = map.stateChannels(),
                minimumValue = optionalFormat("minimumValue"),
                maximumValue = optionalFormat("maximumValue"),
                maximumLength = map.optionalUInt64("maximumLength", label)?.let {
                    if (it < 0 || it > Int.MAX_VALUE) {
                        throw CafProtocolException("$label.maximumLength exceeds JVM limits")
                    }
                    it.toInt()
                },
                stepValue = optionalFormat("stepValue"),
                validValues = validValues,
                units = map["units"],
                oemWritable = map.optionalBoolean("oemWritable", label),
                extensions = map.unknownFields(CHARACTERISTIC_KEYS),
            )
        }

        private fun decodeControl(raw: Any?, label: String): CafControlMetadata {
            val map = requireStringMap(raw, label)
            val requestParameters = decodeParameters(map, "requestParameters", label)
            val responseParameters = decodeParameters(map, "responseParameters", label)
            val hasResponse = map.requiredBoolean("hasResponse", label)
            if (!hasResponse && responseParameters.isNotEmpty()) {
                throw CafProtocolException("$label has responseParameters but hasResponse is false")
            }
            return CafControlMetadata(
                type = map.requiredType("type", label),
                iid = map.requiredIid("iid", label),
                sender = CafControlSender.fromWireName(map.requiredString("sender", label)),
                hasResponse = hasResponse,
                priority = map.optionalSignedLong("priority", label),
                requestParameters = requestParameters,
                responseParameters = responseParameters,
                stateChannels = map.stateChannels(includeNotifier = false),
                extensions = map.unknownFields(CONTROL_KEYS),
            )
        }

        private fun decodeParameters(
            map: Map<String, Any?>,
            key: String,
            label: String,
        ): List<CafControlParameterMetadata> {
            val values = map.optionalList(key, "$label.$key")
            if (values.size > limits.maxParameters) throw CafProtocolException("$label.$key has too many parameters")
            val decoded = values.mapIndexed { index, item ->
                val parameterLabel = "$label.$key[$index]"
                val parameter = requireStringMap(item, parameterLabel)
                CafControlParameterMetadata(
                    name = parameter.requiredString("name", parameterLabel),
                    format = CafCharacteristicFormat.fromWireValue(
                        parameter.required("format", parameterLabel),
                    ),
                    supportsInvalid = parameter.optionalBoolean("supportsInvalid", parameterLabel) ?: false,
                    extensions = parameter.unknownFields(PARAMETER_KEYS),
                )
            }
            if (decoded.map(CafControlParameterMetadata::name).distinct().size != decoded.size) {
                throw CafProtocolException("$label.$key contains duplicate parameter names")
            }
            return decoded
        }

        private fun validateIdentifiers(tree: CafConfigTree) {
            val identifiers = HashSet<Long>()
            fun add(iid: CafIid, label: String) {
                if (!identifiers.add(iid.value)) {
                    throw CafProtocolException("Duplicate CAF IID ${iid} at $label")
                }
            }
            tree.accessories.forEachIndexed { accessoryIndex, accessory ->
                add(accessory.iid, "accessory[$accessoryIndex]")
                accessory.services.forEachIndexed { serviceIndex, service ->
                    add(service.iid, "accessory[$accessoryIndex].service[$serviceIndex]")
                    service.characteristics.forEach { add(it.iid, "characteristic") }
                    service.controls.forEach { add(it.iid, "control") }
                }
            }
        }

        private fun validateStateChannels(tree: CafConfigTree) {
            val characteristicIids = tree.characteristicsByIid.keys
            tree.characteristicsByIid.values.forEach { metadata ->
                metadata.stateChannels.asMap().forEach { (kind, iid) ->
                    if (iid.value !in characteristicIids) {
                        throw CafProtocolException(
                            "CAF ${kind.name.lowercase()} state IID $iid is not a characteristic",
                        )
                    }
                }
            }
            tree.controlsByIid.values.forEach { metadata ->
                metadata.stateChannels.asMap().forEach { (kind, iid) ->
                    if (iid.value !in characteristicIids) {
                        throw CafProtocolException(
                            "CAF control ${kind.name.lowercase()} state IID $iid is not a characteristic",
                        )
                    }
                }
            }
        }

        private fun Map<String, Any?>.required(key: String, label: String): Any =
            this[key] ?: throw CafProtocolException("$label.$key is required")

        private fun Map<String, Any?>.requiredType(key: String, label: String) =
            CafTypeId(CafNumbers.toUInt64(required(key, label), "$label.$key"))

        private fun Map<String, Any?>.requiredIid(key: String, label: String) =
            CafIid(CafNumbers.toUInt64(required(key, label), "$label.$key"))

        private fun Map<String, Any?>.optionalIid(key: String, label: String): CafIid? =
            if (containsKey(key)) CafIid(CafNumbers.toUInt64(this[key], "$label.$key")) else null

        private fun Map<String, Any?>.stateChannels(includeNotifier: Boolean = true) = CafStateChannels(
            error = optionalIid("iidError", "CAF state"),
            disabled = optionalIid("iidDisabled", "CAF state"),
            notifier = if (includeNotifier) optionalIid("iidNotifier", "CAF state") else null,
            restricted = optionalIid("iidRestricted", "CAF state"),
            hidden = optionalIid("iidHidden", "CAF state"),
        )

        private fun Map<String, Any?>.requiredString(key: String, label: String): String {
            val value = this[key] as? String ?: throw CafProtocolException("$label.$key must be a string")
            if (value.length > limits.maxStringChars) throw CafProtocolException("$label.$key is too long")
            return value
        }

        private fun Map<String, Any?>.optionalString(key: String, label: String): String? =
            if (containsKey(key)) requiredString(key, label) else null

        private fun Map<String, Any?>.requiredBoolean(key: String, label: String): Boolean =
            this[key] as? Boolean ?: throw CafProtocolException("$label.$key must be a boolean")

        private fun Map<String, Any?>.optionalBoolean(key: String, label: String): Boolean? =
            if (containsKey(key)) requiredBoolean(key, label) else null

        private fun Map<String, Any?>.optionalUInt64(key: String, label: String): Long? =
            if (containsKey(key)) CafNumbers.toUInt64(this[key], "$label.$key") else null

        private fun Map<String, Any?>.optionalSignedLong(key: String, label: String): Long? =
            if (containsKey(key)) CafNumbers.toSignedLong(this[key], "$label.$key") else null

        private fun Map<String, Any?>.optionalList(key: String, label: String): List<Any?> =
            if (containsKey(key)) requireList(this[key], label) else emptyList()

        private fun Map<String, Any?>.unknownFields(known: Set<String>): Map<String, Any?> =
            Collections.unmodifiableMap(
                entries
                    .filter { it.key !in known }
                    .associateTo(LinkedHashMap()) { it.key to it.value },
            )

        private fun requireStringMap(value: Any?, label: String): Map<String, Any?> {
            val source = value as? Map<*, *> ?: throw CafProtocolException("$label must be a dictionary")
            val result = LinkedHashMap<String, Any?>(source.size)
            source.forEach { (key, item) ->
                val stringKey = key as? String ?: throw CafProtocolException("$label keys must be strings")
                result[stringKey] = item
            }
            return result
        }

        private fun requireList(value: Any?, label: String): List<Any?> =
            (value as? List<*>)?.map { it }
                ?: throw CafProtocolException("$label must be an array")
    }

    private val ROOT_KEYS = setOf("accessories")
    private val ACCESSORY_KEYS = setOf("type", "iid", "version", "services")
    private val SERVICE_KEYS = setOf(
        "type", "iid", "characteristics", "controls", "multipleInstances", "indexBy", "sortBy",
    )
    private val CHARACTERISTIC_KEYS = setOf(
        "type", "iid", "format", "writable", "mutable", "initialValue", "priority",
        "largePayload", "supportsInvalid", "iidError", "iidDisabled", "iidNotifier",
        "iidRestricted", "iidHidden", "minimumValue", "maximumValue", "maximumLength",
        "stepValue", "validValues", "units", "oemWritable",
    )
    private val CONTROL_KEYS = setOf(
        "type", "iid", "sender", "hasResponse", "priority", "requestParameters",
        "responseParameters", "iidError", "iidDisabled", "iidRestricted", "iidHidden",
    )
    private val PARAMETER_KEYS = setOf("name", "format", "supportsInvalid")
}

/** Bounded detached wire snapshot shared by schema and provisioning boundaries. */
internal fun freezeCafWireMap(
    raw: Any?,
    limits: CafConfigLimits = CafConfigLimits.DEFAULT,
): Map<String, Any?> = CafWireConfigFreezer(limits).copyRoot(raw)

/**
 * Takes one bounded, detached snapshot before semantic decoding starts.
 *
 * The input can originate from a mutable plist implementation or an OEM callback. Copying once at
 * the decoder boundary prevents a caller from changing validated values before [CafConfigTree.root]
 * is emitted in a config response. Collections in the snapshot are unmodifiable; byte arrays are
 * detached copies because the JVM has no read-only byte-array type.
 */
private class CafWireConfigFreezer(
    private val limits: CafConfigLimits,
) {
    private var nodes = 0
    private var collectionEntries = 0
    private var stringChars = 0L
    private var dataBytes = 0L
    private val activeCollections = IdentityHashMap<Any, Unit>()

    fun copyRoot(raw: Any?): Map<String, Any?> {
        val copied = copy(raw, depth = 0, label = "CAF config tree")
        @Suppress("UNCHECKED_CAST")
        return copied as? Map<String, Any?>
            ?: throw CafProtocolException("CAF config tree must be a dictionary")
    }

    private fun copy(value: Any?, depth: Int, label: String): Any? {
        if (depth > limits.maxDepth) {
            throw CafProtocolException("$label exceeds CAF config nesting depth ${limits.maxDepth}")
        }
        consumeNode(label)
        return when (value) {
            null,
            is Boolean,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            is BigInteger,
            -> value
            is String -> copyString(value, label)
            is ByteArray -> copyData(value, label)
            is List<*> -> copyList(value, depth, label)
            is Map<*, *> -> copyMap(value, depth, label)
            else -> throw CafProtocolException(
                "$label contains unsupported wire value ${value::class.java.simpleName}",
            )
        }
    }

    private fun copyList(source: List<*>, depth: Int, label: String): List<Any?> =
        whileCollectionActive(source, label) {
            consumeCollectionEntries(source.size, label)
            val result = ArrayList<Any?>(source.size)
            source.forEachIndexed { index, item ->
                result += copy(item, depth + 1, "$label[$index]")
            }
            Collections.unmodifiableList(result)
        }

    private fun copyMap(source: Map<*, *>, depth: Int, label: String): Map<String, Any?> =
        whileCollectionActive(source, label) {
            consumeCollectionEntries(source.size, label)
            val result = LinkedHashMap<String, Any?>(source.size)
            source.forEach { (rawKey, item) ->
                consumeNode("$label key")
                val key = rawKey as? String
                    ?: throw CafProtocolException("$label keys must be strings")
                copyString(key, "$label key")
                if (result.containsKey(key)) {
                    throw CafProtocolException("$label contains duplicate key '$key'")
                }
                result[key] = copy(item, depth + 1, "$label.$key")
            }
            Collections.unmodifiableMap(result)
        }

    private fun copyString(value: String, label: String): String {
        if (value.length > limits.maxStringChars) {
            throw CafProtocolException("$label exceeds ${limits.maxStringChars} characters")
        }
        stringChars += value.length.toLong()
        if (stringChars > limits.maxTotalStringChars.toLong()) {
            throw CafProtocolException(
                "CAF config exceeds ${limits.maxTotalStringChars} total string characters",
            )
        }
        return value
    }

    private fun copyData(value: ByteArray, label: String): ByteArray {
        if (value.size > limits.maxDataBytes) {
            throw CafProtocolException("$label exceeds ${limits.maxDataBytes} data bytes")
        }
        dataBytes += value.size.toLong()
        if (dataBytes > limits.maxTotalDataBytes) {
            throw CafProtocolException(
                "CAF config exceeds ${limits.maxTotalDataBytes} total data bytes",
            )
        }
        return value.copyOf()
    }

    private fun consumeNode(label: String) {
        nodes++
        if (nodes > limits.maxNodes) {
            throw CafProtocolException("$label exceeds CAF config node limit ${limits.maxNodes}")
        }
    }

    private fun consumeCollectionEntries(count: Int, label: String) {
        collectionEntries += count
        if (collectionEntries > limits.maxCollectionEntries) {
            throw CafProtocolException(
                "$label exceeds CAF config collection-entry limit ${limits.maxCollectionEntries}",
            )
        }
    }

    private inline fun <T> whileCollectionActive(
        source: Any,
        label: String,
        block: () -> T,
    ): T {
        if (activeCollections.put(source, Unit) != null) {
            throw CafProtocolException("$label contains a cyclic collection")
        }
        return try {
            block()
        } finally {
            activeCollections.remove(source)
        }
    }
}
