package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.math.BigInteger

open class CafProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * IDs on the CAF wire are UInt64 values. Kotlin [Long] stores their unmodified 64 wire bits; use
 * these helpers whenever a human-readable unsigned value is required. This avoids narrowing OEM
 * identifiers while remaining compatible with OPACK's signed-integer JVM representation.
 */
object CafUInt64 {
    private val MAX_VALUE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    fun parse(value: Any?, label: String): Long = when (value) {
        is Byte -> requireNonNegative(value.toLong(), label)
        is Short -> requireNonNegative(value.toLong(), label)
        is Int -> requireNonNegative(value.toLong(), label)
        is Long -> value // negative values are valid UInt64 bit patterns after OPACK decoding
        is BigInteger -> {
            if (value.signum() < 0 || value > MAX_VALUE) {
                throw CafProtocolException("$label is outside the UInt64 range")
            }
            value.toLong()
        }
        else -> throw CafProtocolException("$label must be an integer")
    }

    fun toBigInteger(bits: Long): BigInteger =
        if (bits >= 0) BigInteger.valueOf(bits)
        else BigInteger.valueOf(bits and Long.MAX_VALUE).setBit(63)

    fun toString(bits: Long): String = java.lang.Long.toUnsignedString(bits)

    /** NSNumber-compatible representation that preserves the upper half of UInt64 in bplist. */
    fun plistNumber(bits: Long): Any = if (bits >= 0) bits else toBigInteger(bits)

    private fun requireNonNegative(value: Long, label: String): Long {
        if (value < 0) throw CafProtocolException("$label is outside the UInt64 range")
        return value
    }
}

data class CafMessage(
    val command: CafCommand,
    val transactionId: Long? = null,
    val values: Any? = null,
    /** Per-IID errors are opaque signed values; the protocol layer must not reinterpret them. */
    val errors: Map<Long, Long>? = null,
    /** General error is an opaque signed OSStatus-style value. */
    val error: Long? = null,
    val extensions: Map<String, Any?> = emptyMap(),
)

data class CafEnvelope(
    /** Raw UInt64 wire bits. */
    val pluginId: Long,
    val message: CafMessage,
    val extensions: Map<String, Any?> = emptyMap(),
)

data class CafRcsFrame(
    val envelope: CafEnvelope,
    val rcsBody: ByteArray,
) {
    fun asRcsMessage(): RcsMessage = RcsMessage.comm(rcsBody.copyOf())
}

class CafMessageReader internal constructor(val envelope: CafEnvelope) {
    val pluginId: Long get() = envelope.pluginId
    val message: CafMessage get() = envelope.message
    val command: CafCommand get() = message.command
    val transactionId: Long? get() = message.transactionId

    fun requireTransactionId(): Long =
        transactionId ?: throw CafProtocolException("${command.wireName} has no transactionID")

    fun values(): Any? = message.values

    fun valuesMap(): Map<Long, Any?> =
        CafNumbers.toUInt64KeyedMap(values(), "${command.wireName}.values")

    fun configTree(decoder: CafConfigTreeDecoder): CafConfigTree = decoder.decode(values())

    fun valuesList(): List<Long> = CafNumbers.toUInt64List(
        values(),
        "${command.wireName}.values",
    )

    fun registration(): CafRegistration = when (val value = values()) {
        "*" -> CafRegistration.Wildcard
        else -> CafRegistration.Identifiers(CafNumbers.toUInt64List(value, "${command.wireName}.values"))
    }

    fun errors(): Map<Long, Long> =
        message.errors ?: throw CafProtocolException("${command.wireName} has no errors map")

    fun requireError(): Long =
        message.error ?: throw CafProtocolException("${command.wireName} has no error")
}

internal object CafNumbers {
    fun toUInt64(value: Any?, label: String): Long = CafUInt64.parse(value, label)

    fun toSignedLong(value: Any?, label: String): Long = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        is BigInteger -> if (value < SIGNED_LONG_MIN || value > SIGNED_LONG_MAX) {
            throw CafProtocolException("$label is outside the signed 64-bit range")
        } else {
            value.toLong()
        }
        else -> throw CafProtocolException("$label must be a signed integer")
    }

    fun toUInt64List(
        value: Any?,
        label: String,
        maxIids: Int = CafLimits.DEFAULT.maxIidsPerMessage,
    ): List<Long> {
        val source = value as? List<*>
            ?: throw CafProtocolException("$label must be an array")
        if (source.size > maxIids) {
            throw CafProtocolException(
                "$label contains ${source.size} IIDs, maximum is $maxIids",
            )
        }
        return source.mapIndexed { index, item -> toUInt64(item, "$label[$index]") }
    }

    fun toUInt64KeyedMap(
        value: Any?,
        label: String,
        maxIids: Int = CafLimits.DEFAULT.maxIidsPerMessage,
    ): Map<Long, Any?> {
        val source = value as? Map<*, *>
            ?: throw CafProtocolException("$label must be a dictionary")
        if (source.size > maxIids) {
            throw CafProtocolException(
                "$label contains ${source.size} IIDs, maximum is $maxIids",
            )
        }
        val result = LinkedHashMap<Long, Any?>(source.size)
        source.forEach { (key, item) ->
            val iid = toUInt64(key, "$label key")
            if (result.containsKey(iid)) {
                throw CafProtocolException("$label contains duplicate IID ${CafUInt64.toString(iid)}")
            }
            result[iid] = item
        }
        return result
    }

    fun toSignedErrorMap(value: Any?, label: String): Map<Long, Long> {
        val source = value as? Map<*, *>
            ?: throw CafProtocolException("$label must be a dictionary")
        if (source.size > CafLimits.DEFAULT.maxIidsPerMessage) {
            throw CafProtocolException("$label contains too many entries")
        }
        val result = LinkedHashMap<Long, Long>(source.size)
        source.forEach { (key, item) ->
            val iid = toUInt64(key, "$label key")
            if (result.containsKey(iid)) {
                throw CafProtocolException("$label contains duplicate IID ${CafUInt64.toString(iid)}")
            }
            result[iid] = toSignedLong(item, "$label value")
        }
        return result
    }

    private val SIGNED_LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val SIGNED_LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
}

data class CafLimits(
    val maxRcsBodyBytes: Int = 1024 * 1024,
    val maxOuterOpackBytes: Int = 768 * 1024,
    val maxPluginDataBytes: Int = 512 * 1024,
    val maxIidsPerMessage: Int = 4096,
) {
    init {
        require(maxRcsBodyBytes > 0)
        require(maxOuterOpackBytes in 1..maxRcsBodyBytes)
        require(maxPluginDataBytes in 1..maxOuterOpackBytes)
        require(maxIidsPerMessage > 0)
    }

    companion object {
        val DEFAULT = CafLimits()
    }
}

internal fun CafMessage.validateWireShape(limits: CafLimits = CafLimits.DEFAULT) {
    when (command.transactionRule) {
        CafTransactionRule.REQUIRED -> if (transactionId == null) {
            throw CafProtocolException("${command.wireName} requires transactionID")
        }
        CafTransactionRule.OPTIONAL -> Unit
        CafTransactionRule.FORBIDDEN -> if (transactionId != null) {
            throw CafProtocolException("${command.wireName} must not contain transactionID")
        }
    }

    transactionId?.let { CafUInt64.parse(it, "${command.wireName}.transactionID") }

    when (command.valueRule) {
        CafValueRule.NONE -> if (values != null) {
            throw CafProtocolException("${command.wireName} must not contain values")
        }
        CafValueRule.WILDCARD_OR_IID_LIST -> if (values != "*") {
            CafNumbers.toUInt64List(values, "${command.wireName}.values", limits.maxIidsPerMessage)
        }
        CafValueRule.IID_LIST -> CafNumbers.toUInt64List(
            values,
            "${command.wireName}.values",
            limits.maxIidsPerMessage,
        )
        CafValueRule.IID_VALUE_MAP -> {
            CafNumbers.toUInt64KeyedMap(values, "${command.wireName}.values", limits.maxIidsPerMessage)
        }
        CafValueRule.CONFIG_TREE -> if (values !is Map<*, *>) {
            throw CafProtocolException("${command.wireName}.values must be a dictionary")
        }
    }

    when (command.errorRule) {
        CafErrorRule.NONE -> {
            if (errors != null) throw CafProtocolException("${command.wireName} must not contain errors")
            if (error != null) throw CafProtocolException("${command.wireName} must not contain error")
        }
        CafErrorRule.IID_ERROR_MAP -> {
            if (errors == null) throw CafProtocolException("${command.wireName} requires errors")
            if (errors.size > limits.maxIidsPerMessage) throw CafProtocolException("too many CAF errors")
            if (error != null) throw CafProtocolException("${command.wireName} must not contain error")
        }
        CafErrorRule.OS_STATUS -> {
            if (error == null) throw CafProtocolException("${command.wireName} requires error")
            if (errors != null) throw CafProtocolException("${command.wireName} must not contain errors")
        }
    }

    rejectKnownExtensionKeys(extensions, INNER_KEYS, "CAF message extensions")
}

internal fun rejectKnownExtensionKeys(
    extensions: Map<String, Any?>,
    knownKeys: Set<String>,
    label: String,
) {
    extensions.keys.firstOrNull { it in knownKeys }?.let {
        throw CafProtocolException("$label must not override '$it'")
    }
}

internal val INNER_KEYS = setOf("command", "transactionID", "values", "errors", "error")
internal val OUTER_KEYS = setOf("pluginID", "pluginData")
