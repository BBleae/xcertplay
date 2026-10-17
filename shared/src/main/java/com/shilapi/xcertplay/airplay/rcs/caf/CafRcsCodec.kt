package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackException
import com.shilapi.xcertplay.airplay.rcs.opack.OpackLimits
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec

/** Safe, payload-free stage marker for a rejected live CAF carrier. */
internal enum class CafDecodeStage {
    CARRIER,
    PARAMS,
    OUTER_OPACK,
    PLUGIN,
    INNER_OPACK,
    COMMAND,
    MESSAGE_SHAPE,
}

internal class CafDecodeException(
    val stage: CafDecodeStage,
    cause: CafProtocolException,
) : CafProtocolException(cause.message ?: "CAF decode rejected", cause)

internal enum class CafCarrierStyle {
    BINARY_PLIST_OPACK,
    DIRECT_OPACK,
}

internal data class CafDecodedCarrier(
    val envelope: CafEnvelope,
    val style: CafCarrierStyle,
)

/**
 * CAF carrier used by the AirPlay/RCS integration. The inner and outer OPACK envelopes are shared
 * by both observed styles. iOS 27 can send the outer OPACK directly; older donor traces used a
 * `bplist({"params":{"data": outerOPACK}})` wrapper. A session replies in the style it receives.
 */
class CafRcsCodec(private val limits: CafLimits = CafLimits.DEFAULT) {
    private val outerOpackLimits = OpackLimits(
        maxEncodedBytes = limits.maxOuterOpackBytes,
        maxValueBytes = limits.maxPluginDataBytes,
        maxCollectionEntries = limits.maxIidsPerMessage + 16,
        maxObjectTableEntries = limits.maxIidsPerMessage * 4 + 64,
        maxDepth = 32,
    )
    private val innerOpackLimits = outerOpackLimits.copy(
        maxEncodedBytes = limits.maxPluginDataBytes,
        maxValueBytes = limits.maxPluginDataBytes,
    )

    fun encodeEnvelope(envelope: CafEnvelope): CafRcsFrame =
        encodeEnvelope(envelope, CafCarrierStyle.BINARY_PLIST_OPACK)

    internal fun encodeEnvelope(envelope: CafEnvelope, style: CafCarrierStyle): CafRcsFrame {
        envelope.message.validateWireShape(limits)
        rejectKnownExtensionKeys(envelope.extensions, OUTER_KEYS, "CAF envelope extensions")

        val inner = LinkedHashMap<String, Any?>()
        inner.putAll(envelope.message.extensions)
        inner["command"] = envelope.message.command.wireName
        envelope.message.transactionId?.let { inner["transactionID"] = it }
        if (envelope.message.command.valueRule != CafValueRule.NONE) {
            inner["values"] = envelope.message.values
        }
        envelope.message.errors?.let { inner["errors"] = it }
        envelope.message.error?.let { inner["error"] = it }

        val pluginData = encodeOpack(inner, innerOpackLimits, "CAF inner OPACK")
        val outer = LinkedHashMap<String, Any?>()
        outer.putAll(envelope.extensions)
        outer["pluginID"] = envelope.pluginId
        outer["pluginData"] = pluginData
        val outerData = encodeOpack(outer, outerOpackLimits, "CAF outer OPACK")
        val body = when (style) {
            CafCarrierStyle.DIRECT_OPACK -> outerData
            CafCarrierStyle.BINARY_PLIST_OPACK -> try {
                BplistCodec.encode(
                    linkedMapOf("params" to linkedMapOf("data" to outerData)),
                )
            } catch (error: IllegalArgumentException) {
                throw CafProtocolException("Unable to encode CAF binary-plist carrier", error)
            }
        }
        if (body.size > limits.maxRcsBodyBytes) {
            throw CafProtocolException(
                "CAF RCS body size ${body.size} exceeds ${limits.maxRcsBodyBytes}",
            )
        }
        return CafRcsFrame(envelope, body)
    }

    fun decodeEnvelope(rcsBody: ByteArray): CafEnvelope = decodeCarrier(rcsBody).envelope

    internal fun decodeCarrier(rcsBody: ByteArray): CafDecodedCarrier {
        var stage = CafDecodeStage.CARRIER
        try {
            if (rcsBody.size > limits.maxRcsBodyBytes) {
                throw CafProtocolException(
                    "CAF RCS body size ${rcsBody.size} exceeds ${limits.maxRcsBodyBytes}",
                )
            }
            val style = if (hasBplistMagic(rcsBody)) {
                CafCarrierStyle.BINARY_PLIST_OPACK
            } else {
                CafCarrierStyle.DIRECT_OPACK
            }
            val outerBytes = when (style) {
                CafCarrierStyle.DIRECT_OPACK -> rcsBody
                CafCarrierStyle.BINARY_PLIST_OPACK -> {
                    val wrapper = requireStringMap(
                        try {
                            BplistCodec.decode(rcsBody)
                        } catch (error: IllegalArgumentException) {
                            throw CafProtocolException("Invalid CAF binary-plist carrier", error)
                        },
                        "CAF RCS body",
                    )
                    stage = CafDecodeStage.PARAMS
                    val params = requireStringMap(wrapper["params"], "CAF RCS body params")
                    params["data"] as? ByteArray
                        ?: throw CafProtocolException("CAF RCS body params.data must be NSData")
                }
            }
            if (outerBytes.size > limits.maxOuterOpackBytes) {
                throw CafProtocolException("CAF outer OPACK exceeds ${limits.maxOuterOpackBytes} bytes")
            }
            stage = CafDecodeStage.OUTER_OPACK
            val outer = requireStringMap(
                decodeOpack(outerBytes, outerOpackLimits, "CAF outer OPACK"),
                "CAF outer OPACK",
            )
            stage = CafDecodeStage.PLUGIN
            val pluginId = CafNumbers.toUInt64(outer["pluginID"], "CAF pluginID")
            val innerBytes = outer["pluginData"] as? ByteArray
                ?: throw CafProtocolException("CAF outer pluginData must be NSData")
            if (innerBytes.size > limits.maxPluginDataBytes) {
                throw CafProtocolException("CAF pluginData exceeds ${limits.maxPluginDataBytes} bytes")
            }
            stage = CafDecodeStage.INNER_OPACK
            val inner = requireStringMap(
                decodeOpack(innerBytes, innerOpackLimits, "CAF inner OPACK"),
                "CAF inner OPACK",
            )
            stage = CafDecodeStage.COMMAND
            val commandName = inner["command"] as? String
                ?: throw CafProtocolException("CAF command must be a string")
            val command = CafCommand.find(commandName)
                ?: throw CafProtocolException("Unknown CAF command '$commandName'")
            val transactionId = inner["transactionID"]?.let {
                CafNumbers.toUInt64(it, "CAF transactionID")
            }
            val errors = inner["errors"]?.let {
                CafNumbers.toSignedErrorMap(it, "CAF errors")
            }
            val error = inner["error"]?.let {
                CafNumbers.toSignedLong(it, "CAF error")
            }
            val message = CafMessage(
                command = command,
                transactionId = transactionId,
                values = inner["values"],
                errors = errors,
                error = error,
                extensions = inner.filterKeys { it !in INNER_KEYS },
            )
            stage = CafDecodeStage.MESSAGE_SHAPE
            message.validateWireShape(limits)
            return CafDecodedCarrier(
                envelope = CafEnvelope(
                    pluginId = pluginId,
                    message = message,
                    extensions = outer.filterKeys { it !in OUTER_KEYS },
                ),
                style = style,
            )
        } catch (error: CafProtocolException) {
            throw CafDecodeException(stage, error)
        }
    }

    private fun hasBplistMagic(bytes: ByteArray): Boolean =
        bytes.size >= BPLIST_MAGIC.size && BPLIST_MAGIC.indices.all { bytes[it] == BPLIST_MAGIC[it] }

    private fun encodeOpack(value: Any?, limits: OpackLimits, label: String): ByteArray = try {
        OpackCodec.encode(value, limits)
    } catch (error: OpackException) {
        throw CafProtocolException("Unable to encode $label", error)
    }

    private fun decodeOpack(bytes: ByteArray, limits: OpackLimits, label: String): Any? = try {
        OpackCodec.decode(bytes, limits)
    } catch (error: OpackException) {
        throw CafProtocolException("Invalid $label", error)
    }

    private fun requireStringMap(value: Any?, label: String): Map<String, Any?> {
        val source = value as? Map<*, *>
            ?: throw CafProtocolException("$label must be a dictionary")
        val result = LinkedHashMap<String, Any?>(source.size)
        source.forEach { (key, item) ->
            val stringKey = key as? String
                ?: throw CafProtocolException("$label keys must be strings")
            result[stringKey] = item
        }
        return result
    }

    companion object {
        private val DEFAULT = CafRcsCodec()
        private val BPLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

        fun encode(envelope: CafEnvelope): CafRcsFrame = DEFAULT.encodeEnvelope(envelope)

        fun decode(rcsBody: ByteArray): CafEnvelope = DEFAULT.decodeEnvelope(rcsBody)
    }
}

/** iAPChannel is a raw iAP2 carrier, not CAF. */
object IapChannelPayload {
    fun fromDevice(iap2Frame: ByteArray): RcsMessage = RcsMessage.comm(iap2Frame.copyOf())

    fun toDevice(message: RcsMessage): ByteArray {
        if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw CafProtocolException("iAPChannel payload must use comm messageType")
        }
        return message.body.copyOf()
    }

    fun asRcsMessage(iap2Frame: ByteArray): RcsMessage = fromDevice(iap2Frame)

    fun from(message: RcsMessage): ByteArray = toDevice(message)
}
