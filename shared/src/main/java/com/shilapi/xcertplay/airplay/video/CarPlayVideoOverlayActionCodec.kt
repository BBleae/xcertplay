package com.shilapi.xcertplay.airplay.video

import java.math.BigInteger
import java.util.Collections

/** The twelve iOS 27 RenderActionProtocol string values seen in AirPlayKit. */
enum class CarPlayVideoOverlayWireAction(val wireValue: String) {
    CREATE_LAYER("createLayer"),
    UPDATE_LAYER("updateLayer"),
    INSERT_LAYER("insertLayer"),
    REMOVE_LAYER("removeLayer"),
    USER_ACTION("userAction"),
    CREATE_ASSET("createAsset"),
    REMOVE_ASSET("removeAsset"),
    TRANSACTION("transaction"),
    STYLE("style"),
    CREATE_ANIMATION("createAnimation"),
    ANIMATE("animate"),
    INFO("info"),
    ;

    companion object {
        fun fromWireValue(value: String): CarPlayVideoOverlayWireAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * Diagnostic classification of a received render action. It does not draw, acknowledge, or
 * activate VideoOverlayUI. [rawMessage] retains every bounded plist field and returns copies.
 */
sealed class CarPlayVideoOverlayAction(
    val rawMessage: CarPlayVideoOverlayMessage,
) {
    class CreateRing internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val radius: Double,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class CreateText internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val text: String,
    ) : CarPlayVideoOverlayAction(rawMessage)

    /** A canonical ImageContent asset identifier, without a renderability claim. */
    class CreateImageAsset internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val assetIdentifier: String,
    ) : CarPlayVideoOverlayAction(rawMessage)

    /** Radius is the observed field shape; updateLayer does not carry a layer subtype. */
    class UpdateRadiusCandidate internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val radius: Double,
    ) : CarPlayVideoOverlayAction(rawMessage)

    /** Text is the observed field shape; updateLayer does not carry a layer subtype. */
    class UpdateTextCandidate internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val text: String,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class InsertLayer internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val subLayerUuid: String,
        val index: Int,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class RemoveLayer internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
    ) : CarPlayVideoOverlayAction(rawMessage)

    /** Style values retain source geometry; no physical-display coordinate conversion is made. */
    class SetOrigin internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val origin: CarPlayVideoOverlayGeometrySchema.Point,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class SetSize internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val size: CarPlayVideoOverlayGeometrySchema.Size,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class SetCenterAnchor internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val centerAnchor: CarPlayVideoOverlayGeometrySchema.CenterAnchor,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class SetTransform internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val transform: CarPlayVideoOverlayGeometrySchema.Transform,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class SetOpacity internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val objectUuid: String,
        val opacity: Double,
    ) : CarPlayVideoOverlayAction(rawMessage)

    class CreateAsset internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val name: String,
        data: ByteArray,
        val mimeType: String,
        val sprite: Sprite?,
    ) : CarPlayVideoOverlayAction(rawMessage) {
        private val ownedData = data.copyOf()
        val data: ByteArray get() = ownedData.copyOf()
    }

    class RemoveAsset internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val name: String,
    ) : CarPlayVideoOverlayAction(rawMessage)

    /** An exact iOS 27 function bundle. Its children are classified, never executed here. */
    class Transaction internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        children: List<CarPlayVideoOverlayAction>,
    ) : CarPlayVideoOverlayAction(rawMessage) {
        val children: List<CarPlayVideoOverlayAction> =
            Collections.unmodifiableList(ArrayList(children))
    }

    data class Sprite(
        val width: Double,
        val height: Double,
        val totalFrames: Int,
        val initialX: Double,
        val initialY: Double,
    )

    /** Unknown actions and all unproven, malformed, or extended shapes remain fully opaque. */
    class Opaque internal constructor(
        rawMessage: CarPlayVideoOverlayMessage,
        val knownAction: CarPlayVideoOverlayWireAction?,
    ) : CarPlayVideoOverlayAction(rawMessage)

    // The raw plist can contain identifiers, text, and image bytes. Never print its contents.
    final override fun toString(): String = "CarPlayVideoOverlayAction.${javaClass.simpleName}"
}

/**
 * Read-only typed subset on top of [CarPlayVideoOverlayCodec]'s complete and bounded bplist
 * parser. Exact known shapes are classified; every other shape retains the opaque raw message.
 */
class CarPlayVideoOverlayActionCodec(
    private val plistCodec: CarPlayVideoOverlayCodec = CarPlayVideoOverlayCodec(),
    val maximumTypedTransactionChildren: Int = 128,
) {
    init {
        require(maximumTypedTransactionChildren in 1..plistCodec.limits.maximumContainerItems)
    }

    val limits: CarPlayVideoOverlayLimits get() = plistCodec.limits

    fun decodeFromPhone(rcsBody: ByteArray): CarPlayVideoOverlayAction =
        classify(plistCodec.decodeFromPhone(rcsBody))

    // Keep this entry private: a message decoded by a *different*, wider carrier codec must not
    // bypass this instance's byte/depth limits. Only decodeFromPhone may classify incoming data.
    private fun classify(message: CarPlayVideoOverlayMessage): CarPlayVideoOverlayAction {
        val fields = message.fields
        val wireAction = (fields["action"] as? String)?.let(CarPlayVideoOverlayWireAction::fromWireValue)
        if (fields["type"] != "function") return CarPlayVideoOverlayAction.Opaque(message, wireAction)
        val parsed = when (wireAction) {
            CarPlayVideoOverlayWireAction.CREATE_LAYER -> createLayer(message, fields)
            CarPlayVideoOverlayWireAction.UPDATE_LAYER -> updateLayer(message, fields)
            CarPlayVideoOverlayWireAction.INSERT_LAYER -> insertLayer(message, fields)
            CarPlayVideoOverlayWireAction.REMOVE_LAYER -> removeLayer(message, fields)
            CarPlayVideoOverlayWireAction.STYLE -> style(message, fields)
            CarPlayVideoOverlayWireAction.CREATE_ASSET -> createAsset(message, fields)
            CarPlayVideoOverlayWireAction.REMOVE_ASSET -> removeAsset(message, fields)
            CarPlayVideoOverlayWireAction.TRANSACTION -> transaction(message, fields)
            else -> null
        }
        return parsed ?: CarPlayVideoOverlayAction.Opaque(message, wireAction)
    }

    private fun createLayer(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        val uuid = layerUuid(fields, hasArguments = true) ?: return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        if (!arguments.hasExactly("type", "createArguments")) return null
        val createArguments = arguments["createArguments"] as? Map<*, *> ?: return null
        return when (arguments["type"]) {
            "ring" -> {
                if (!createArguments.hasExactly("radius")) return null
                val radius = plistReal(createArguments["radius"]) ?: return null
                CarPlayVideoOverlayAction.CreateRing(message, uuid, radius)
            }
            "text" -> {
                if (!createArguments.hasExactly("text")) return null
                val text = createArguments["text"] as? String ?: return null
                CarPlayVideoOverlayAction.CreateText(message, uuid, text)
            }
            "image" -> {
                val asset = CarPlayVideoOverlayGeometrySchema.assetReference(createArguments)
                    ?: return null
                CarPlayVideoOverlayAction.CreateImageAsset(message, uuid, asset.identifier)
            }
            else -> null
        }
    }

    private fun updateLayer(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        val uuid = layerUuid(fields, hasArguments = true) ?: return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        return when {
            arguments.hasExactly("radius") -> {
                val radius = plistReal(arguments["radius"]) ?: return null
                CarPlayVideoOverlayAction.UpdateRadiusCandidate(message, uuid, radius)
            }
            arguments.hasExactly("text") -> {
                val text = arguments["text"] as? String ?: return null
                CarPlayVideoOverlayAction.UpdateTextCandidate(message, uuid, text)
            }
            else -> null
        }
    }

    private fun insertLayer(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        val uuid = layerUuid(fields, hasArguments = true) ?: return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        if (!arguments.hasExactly("uuidOfSubLayer", "index")) return null
        val subLayerUuid = (arguments["uuidOfSubLayer"] as? String)?.takeIf(::isUuid) ?: return null
        val index = plistInt(arguments["index"]) ?: return null
        return CarPlayVideoOverlayAction.InsertLayer(message, uuid, subLayerUuid, index)
    }

    private fun removeLayer(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        val uuid = layerUuid(fields, hasArguments = false) ?: return null
        return CarPlayVideoOverlayAction.RemoveLayer(message, uuid)
    }

    private fun style(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        val uuid = layerUuid(fields, hasArguments = true) ?: return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        if (!arguments.hasExactly("key", "value")) return null
        return when (arguments["key"]) {
            "origin" -> CarPlayVideoOverlayGeometrySchema.point(arguments["value"])
                ?.let { CarPlayVideoOverlayAction.SetOrigin(message, uuid, it) }
            "size" -> CarPlayVideoOverlayGeometrySchema.size(arguments["value"])
                ?.let { CarPlayVideoOverlayAction.SetSize(message, uuid, it) }
            "centerAnchor" -> CarPlayVideoOverlayGeometrySchema.centerAnchor(arguments["value"])
                ?.let { CarPlayVideoOverlayAction.SetCenterAnchor(message, uuid, it) }
            "transform" -> CarPlayVideoOverlayGeometrySchema.transform(arguments["value"])
                ?.let { CarPlayVideoOverlayAction.SetTransform(message, uuid, it) }
            "opacity" -> plistReal(arguments["value"])
                ?.let { CarPlayVideoOverlayAction.SetOpacity(message, uuid, it) }
            else -> null
        }
    }

    private fun createAsset(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        if (!fields.hasExactly("type", "action", "arguments")) return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        if (!arguments.hasExactly("name", "data", "manifest")) return null
        val name = arguments["name"] as? String ?: return null
        val data = arguments["data"] as? ByteArray ?: return null
        val manifest = arguments["manifest"] as? Map<*, *> ?: return null
        if (!manifest.hasExactly("mimeType") && !manifest.hasExactly("mimeType", "sprite")) return null
        val mimeType = (manifest["mimeType"] as? String)
            ?.takeIf { it == "image/png" || it == "video/JPEG" } ?: return null
        val sprite = if (manifest.containsKey("sprite")) {
            val value = manifest["sprite"] as? Map<*, *> ?: return null
            if (!value.hasExactly("width", "height", "totalFrames", "initialX", "initialY")) return null
            CarPlayVideoOverlayAction.Sprite(
                width = plistReal(value["width"]) ?: return null,
                height = plistReal(value["height"]) ?: return null,
                totalFrames = plistInt(value["totalFrames"]) ?: return null,
                initialX = plistReal(value["initialX"]) ?: return null,
                initialY = plistReal(value["initialY"]) ?: return null,
            )
        } else {
            null
        }
        return CarPlayVideoOverlayAction.CreateAsset(message, name, data, mimeType, sprite)
    }

    private fun removeAsset(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        if (!fields.hasExactly("type", "action", "arguments")) return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        if (!arguments.hasExactly("name")) return null
        val name = arguments["name"] as? String ?: return null
        return CarPlayVideoOverlayAction.RemoveAsset(message, name)
    }

    private fun transaction(
        message: CarPlayVideoOverlayMessage,
        fields: Map<String, Any?>,
    ): CarPlayVideoOverlayAction? {
        // The verified RenderTransactionAction serializer emits no outer objectUUID/messageID.
        // Unknown or extended children make the *whole* bundle opaque: partial execution would
        // violate its atomicity. Recursion is explicitly forbidden, regardless of plist depth.
        if (!fields.hasExactly("type", "action", "arguments")) return null
        val arguments = fields["arguments"] as? Map<*, *> ?: return null
        if (!arguments.hasExactly("functions")) return null
        val functions = arguments["functions"] as? List<*> ?: return null
        if (functions.size > maximumTypedTransactionChildren) return null
        val children = ArrayList<CarPlayVideoOverlayAction>(functions.size)
        for (function in functions) {
            val childFields = function as? Map<*, *> ?: return null
            if (childFields["action"] == CarPlayVideoOverlayWireAction.TRANSACTION.wireValue) {
                return null
            }
            val childMessage = CarPlayVideoOverlayMessage.fromDecoded(childFields, limits)
            val child = classify(childMessage)
            if (child is CarPlayVideoOverlayAction.Opaque ||
                child is CarPlayVideoOverlayAction.Transaction
            ) return null
            children += child
        }
        return CarPlayVideoOverlayAction.Transaction(message, children)
    }

    private fun layerUuid(fields: Map<String, Any?>, hasArguments: Boolean): String? {
        val exact = if (hasArguments) {
            fields.hasExactly("type", "action", "objectUUID", "arguments")
        } else {
            fields.hasExactly("type", "action", "objectUUID")
        }
        if (!exact) return null
        return (fields["objectUUID"] as? String)?.takeIf(::isUuid)
    }

    private fun Map<*, *>.hasExactly(vararg keys: String): Boolean =
        size == keys.size && keys.all(::containsKey)

    private fun isUuid(value: String): Boolean = UUID_PATTERN.matches(value)

    // A binary plist may encode an NSNumber real as Float32 or Float64. Float32 -> Double is
    // exact; retaining Float64 avoids rounding or underflow in this diagnostic view.
    private fun plistReal(value: Any?): Double? = when (value) {
        is Float -> value.takeIf(Float::isFinite)?.toDouble()
        is Double -> value.takeIf(Double::isFinite)
        else -> null
    }

    private fun plistInt(value: Any?): Int? = when (value) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        is BigInteger -> value.takeIf {
            it >= INT_MIN && it <= INT_MAX
        }?.toInt()
        else -> null
    }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
    }
}
