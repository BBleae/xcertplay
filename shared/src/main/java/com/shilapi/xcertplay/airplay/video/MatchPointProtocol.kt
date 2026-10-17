package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.BplistDecodeLimits
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.caf.CafUInt64
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.math.BigInteger
import java.net.URI
import java.text.Normalizer
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap

class MatchPointProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** Hard resource limits for untrusted MatchPoint binary plists and retained opaque values. */
data class MatchPointLimits(
    val maximumMessageBytes: Int = 512 * 1024,
    val maximumCarrierBytes: Int = 1024 * 1024,
    val maximumValueBytes: Int = 384 * 1024,
    val maximumArtworkBytes: Int = 256 * 1024,
    val maximumStringChars: Int = 8 * 1024,
    val maximumIdentifierChars: Int = 512,
    val maximumCollectionEntries: Int = 2048,
    val maximumValueNodes: Int = 8192,
    val maximumNestingDepth: Int = 32,
) {
    init {
        require(maximumMessageBytes in 1..ABSOLUTE_MAXIMUM_MESSAGE_BYTES)
        require(maximumCarrierBytes in maximumMessageBytes..ABSOLUTE_MAXIMUM_CARRIER_BYTES)
        require(maximumValueBytes in 1..maximumMessageBytes)
        require(maximumArtworkBytes in 1..maximumValueBytes)
        require(maximumStringChars in 1..ABSOLUTE_MAXIMUM_STRING_CHARS)
        require(maximumIdentifierChars in 1..minOf(maximumStringChars, ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS))
        require(maximumCollectionEntries in 1..ABSOLUTE_MAXIMUM_COLLECTION_ENTRIES)
        require(maximumValueNodes in maximumCollectionEntries..ABSOLUTE_MAXIMUM_VALUE_NODES)
        require(maximumNestingDepth in 1..ABSOLUTE_MAXIMUM_NESTING_DEPTH)
    }

    internal fun messageDecodeLimits(): BplistDecodeLimits = BplistDecodeLimits(
        maxInputBytes = maximumMessageBytes,
        maxObjects = maximumValueNodes + 64,
        maxObjectBytes = maximumMessageBytes,
        maxNestingDepth = maximumNestingDepth,
        maxContainerItems = maximumCollectionEntries,
        maxDataBytes = maximumValueBytes,
        maxStringBytes = maximumStringChars * 4,
        maxDecodedBytes = maximumMessageBytes.toLong() * 2L,
    )

    internal fun carrierDecodeLimits(): BplistDecodeLimits = BplistDecodeLimits(
        maxInputBytes = maximumCarrierBytes,
        maxObjects = 16,
        maxObjectBytes = maximumCarrierBytes,
        maxNestingDepth = 4,
        maxContainerItems = 4,
        maxDataBytes = maximumMessageBytes,
        maxStringBytes = 128,
        maxDecodedBytes = maximumCarrierBytes.toLong() + maximumMessageBytes,
    )

    private companion object {
        const val ABSOLUTE_MAXIMUM_MESSAGE_BYTES = 4 * 1024 * 1024
        const val ABSOLUTE_MAXIMUM_CARRIER_BYTES = 8 * 1024 * 1024
        const val ABSOLUTE_MAXIMUM_STRING_CHARS = 64 * 1024
        const val ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS = 8 * 1024
        const val ABSOLUTE_MAXIMUM_COLLECTION_ENTRIES = 16 * 1024
        const val ABSOLUTE_MAXIMUM_VALUE_NODES = 64 * 1024
        const val ABSOLUTE_MAXIMUM_NESTING_DEPTH = 64
    }
}

object MatchPointTypes {
    const val AUTHORIZE_ITEM = "authorizeItem"
    const val INSERT_PLAY_QUEUE_ITEM = "insertPlayQueueItem"
    const val REMOVE_PLAY_QUEUE_ITEM = "removePlayQueueItem"
    const val STOP = "stop"
    const val PLAYBACK_INFO = "playbackInfo"
    const val SEEK = "seek"
    const val SET_RATE = "setRate"
    const val PROPERTY = "property"
    const val SET_PROPERTY = "setProperty"
    const val STREAMING_KEY = "streamingKey"
    const val UNHANDLED_URL = "unhandledURL"
    const val PLAYBACK_COORDINATION_MEDIUM = "playbackCoordinationMedium"
    const val PLAYBACK_STATE = "playbackState"
    const val NOTIFICATION = "notification"
}

enum class MatchPointMessageKind(val wireValue: String?) {
    REQUEST("request"),
    RESPONSE("response"),
    ONE_WAY(null),
}

/** Exact CMTime dictionary used by MatchPoint seek and clock-coordinated rate messages. */
data class MatchPointCmTime(
    val value: Long,
    val timescale: Int,
    val flags: Long = FLAG_VALID,
    val epoch: Long = 0,
) {
    init {
        // The observed playback fields are numeric media times, not invalid/infinite CMTime values.
        require(value >= 0) { "MatchPoint CMTime value must be non-negative" }
        require(timescale > 0) { "MatchPoint CMTime timescale must be positive" }
        require(flags == FLAG_VALID || flags == FLAG_VALID_WITH_ROUNDING) {
            "MatchPoint CMTime flags must describe a finite valid time"
        }
        require(epoch >= 0) { "MatchPoint CMTime epoch must be non-negative" }
    }

    internal fun toWireMap(): Map<String, Any> = linkedMapOf(
        "value" to value,
        "timescale" to timescale,
        "flags" to flags,
        "epoch" to epoch,
    )

    companion object {
        const val FLAG_VALID = 1L
        const val FLAG_VALID_WITH_ROUNDING = 3L
        private val KEYS = setOf("value", "timescale", "flags", "epoch")

        internal fun fromWire(value: Any?, label: String): MatchPointCmTime {
            val map = stringMap(value, label)
            requireExactKeys(map, KEYS, label)
            val rawTimescale = signedLong(map["timescale"], "$label.timescale")
            if (rawTimescale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw MatchPointProtocolException("$label.timescale is outside Int32")
            }
            return try {
                MatchPointCmTime(
                    value = signedLong(map["value"], "$label.value"),
                    timescale = rawTimescale.toInt(),
                    flags = unsignedLongBits(map["flags"], "$label.flags").also {
                        if (java.lang.Long.compareUnsigned(it, 0xffff_ffffL) > 0) {
                            throw MatchPointProtocolException("$label.flags is outside UInt32")
                        }
                    },
                    epoch = signedLong(map["epoch"], "$label.epoch"),
                )
            } catch (error: IllegalArgumentException) {
                if (error is MatchPointProtocolException) throw error
                throw MatchPointProtocolException("Invalid $label: ${error.message}", error)
            }
        }
    }
}

/** A finite CF-style absolute date value, kept distinct from an arbitrary floating-point field. */
data class MatchPointDate(val secondsSinceReferenceDate: Double) {
    init {
        require(secondsSinceReferenceDate.isFinite()) { "MatchPoint date must be finite" }
    }
}

data class MatchPointItemReference(val uuid: String) {
    init {
        validateIdentifier(uuid, "item.uuid", MatchPointLimits().maximumIdentifierChars)
    }

    internal fun toWireMap(): Map<String, Any> = linkedMapOf("uuid" to uuid)

    companion object {
        private val KEYS = setOf("uuid")

        internal fun fromWire(value: Any?, limits: MatchPointLimits, label: String): MatchPointItemReference {
            val map = stringMap(value, label)
            requireExactKeys(map, KEYS, label)
            val uuid = requiredString(map, "uuid", label, limits.maximumIdentifierChars)
            return MatchPointItemReference(uuid)
        }
    }
}

/**
 * Confirmed iOS 27 queue-item fields plus bounded, uninterpreted sender extensions. The sender
 * copies the original item dictionary before adding the known fields, so rejecting every extra
 * key would reject otherwise valid items. Extensions are never playback or transport authority.
 */
class MatchPointQueueItem(
    val uuid: String,
    val startPosition: Double? = null,
    val contentLocation: String? = null,
    val offlineHlsContentLocation: String? = null,
    val isTlsEnabled: Boolean? = null,
    val audioMode: String? = null,
    extensionFields: Map<String, Any> = emptyMap(),
    limits: MatchPointLimits = MatchPointLimits(),
) {
    private val storedExtensionFields = MatchPointValueSnapshotter(limits).freezeMap(
        extensionFields,
        "queue item extensions",
    )

    /** Defensive copy; unknown fields remain opaque and cannot change readiness. */
    val extensionFields: Map<String, Any>
        get() = MatchPointValueSnapshotter.copyOutMap(storedExtensionFields)

    init {
        validateIdentifier(uuid, "item.uuid", limits.maximumIdentifierChars)
        startPosition?.let {
            require(it.isFinite() && it >= 0.0) { "Start-Position must be finite and non-negative" }
        }
        contentLocation?.let { validateUrl(it, "Content-Location", limits.maximumStringChars) }
        offlineHlsContentLocation?.let {
            validateUrl(it, "Offline-HLS-Content-Location", limits.maximumStringChars)
        }
        audioMode?.let { validateIdentifier(it, "audioMode", limits.maximumIdentifierChars) }
        if (storedExtensionFields.keys.any { it in KEYS }) {
            throw MatchPointProtocolException("queue item extensions must not replace known fields")
        }
    }

    internal fun toWireMap(): Map<String, Any> = linkedMapOf<String, Any>("uuid" to uuid).apply {
        startPosition?.let { put("Start-Position", it) }
        contentLocation?.let { put("Content-Location", it) }
        offlineHlsContentLocation?.let { put("Offline-HLS-Content-Location", it) }
        isTlsEnabled?.let { put("IsTLSEnabled", it) }
        audioMode?.let { put("audioMode", it) }
        putAll(storedExtensionFields)
    }

    /** Readiness is local-backend readiness only; it never makes VideoPlayback negotiable. */
    fun playbackReadiness(capabilities: MatchPointPlaybackCapabilities): MatchPointItemReadiness {
        val locations = listOfNotNull(contentLocation, offlineHlsContentLocation)
        if (locations.isEmpty()) return MatchPointItemReadiness.MISSING_LOCATION
        for (location in locations) {
            val uri = URI(location)
            if (isTlsEnabled == true) {
                // iOS 27 uses this flag for the Pair-TLS-PSK local server. A DNS-looking host
                // does not turn the same item into ordinary Web-PKI HTTPS, and HTTP is invalid.
                if (!uri.scheme.equals("https", ignoreCase = true)) {
                    return MatchPointItemReadiness.UNSUPPORTED_TRANSPORT
                }
                if (!capabilities.supportsExternalPairingPskTls) {
                    return MatchPointItemReadiness.PAIRING_PSK_TLS_REQUIRED
                }
                continue
            }
            when (uri.scheme.lowercase()) {
                "http" -> if (!capabilities.supportsHttp) {
                    return MatchPointItemReadiness.UNSUPPORTED_TRANSPORT
                }
                "https" -> if (!capabilities.supportsHttps) {
                    return MatchPointItemReadiness.UNSUPPORTED_TRANSPORT
                }
                else -> return MatchPointItemReadiness.UNSUPPORTED_TRANSPORT
            }
        }
        return MatchPointItemReadiness.READY
    }

    companion object {
        private val KEYS = setOf(
            "uuid",
            "Start-Position",
            "Content-Location",
            "Offline-HLS-Content-Location",
            "IsTLSEnabled",
            "audioMode",
        )

        internal fun fromWire(value: Any?, limits: MatchPointLimits, label: String): MatchPointQueueItem {
            val map = stringMap(value, label)
            if ("uuid" !in map) throw MatchPointProtocolException("$label requires uuid")
            return try {
                MatchPointQueueItem(
                    uuid = requiredString(map, "uuid", label, limits.maximumIdentifierChars),
                    startPosition = map["Start-Position"]?.let {
                        finiteDouble(it, "$label.Start-Position")
                    },
                    contentLocation = optionalString(
                        map,
                        "Content-Location",
                        label,
                        limits.maximumStringChars,
                    ),
                    offlineHlsContentLocation = optionalString(
                        map,
                        "Offline-HLS-Content-Location",
                        label,
                        limits.maximumStringChars,
                    ),
                    isTlsEnabled = optionalBoolean(map, "IsTLSEnabled", label),
                    audioMode = optionalString(map, "audioMode", label, limits.maximumIdentifierChars),
                    extensionFields = nonNullFields(map.filterKeys { it !in KEYS }, label),
                    limits = limits,
                )
            } catch (error: IllegalArgumentException) {
                if (error is MatchPointProtocolException) throw error
                throw MatchPointProtocolException("Invalid $label: ${error.message}", error)
            }
        }
    }
}

data class MatchPointPlaybackCapabilities(
    val supportsHttp: Boolean,
    val supportsHttps: Boolean,
    /**
     * Snapshot from an externally established, session-owned Pair-TLS-PSK transport, not Web PKI.
     * This Boolean is not a credential lease or live authorization proof; a player must verify
     * exact transport ownership again when it actually opens the content URL.
     */
    val supportsExternalPairingPskTls: Boolean,
)

enum class MatchPointItemReadiness {
    READY,
    MISSING_LOCATION,
    UNSUPPORTED_TRANSPORT,
    PAIRING_PSK_TLS_REQUIRED,
}

/** Extensible metadata whose standard scalar fields are exposed without discarding OEM fields. */
class MatchPointMetadata(
    values: Map<String, Any>,
    limits: MatchPointLimits = MatchPointLimits(),
) {
    private val storedValues: Map<String, Any>

    init {
        val frozen = MatchPointValueSnapshotter(limits).freezeMap(values, "metadata")
        val artworkBytes = frozen["artwork"]?.let(::totalDataBytes) ?: 0L
        if (artworkBytes > limits.maximumArtworkBytes) {
            throw MatchPointProtocolException(
                "metadata.artwork exceeds ${limits.maximumArtworkBytes} bytes",
            )
        }
        listOf("title", "artist", "album").forEach { key ->
            frozen[key]?.let {
                if (it !is String) throw MatchPointProtocolException("metadata.$key must be a string")
            }
        }
        frozen["duration"]?.let { finiteDouble(it, "metadata.duration") }
        storedValues = frozen
    }

    val values: Map<String, Any>
        get() = MatchPointValueSnapshotter.copyOutMap(storedValues)

    val title: String? get() = storedValues["title"] as? String
    val artist: String? get() = storedValues["artist"] as? String
    val album: String? get() = storedValues["album"] as? String
    val duration: Double? get() = storedValues["duration"]?.let { (it as Number).toDouble() }
    val artwork: Any? get() = storedValues["artwork"]?.let(MatchPointValueSnapshotter::copyOut)

    internal fun wireValue(): Map<String, Any> = storedValues
}

/** Immutable holder for a bounded, otherwise uninterpreted plist value. */
class MatchPointOpaqueValue(
    value: Any,
    limits: MatchPointLimits = MatchPointLimits(),
    label: String = "opaque value",
) {
    private val stored = MatchPointValueSnapshotter(limits).freeze(value, label)

    val value: Any
        get() = MatchPointValueSnapshotter.copyOut(stored)

    internal fun wireValue(): Any = stored
}

sealed class MatchPointPayload(val type: String) {
    internal abstract val allowedKinds: Set<MatchPointMessageKind>
    internal abstract fun wireFields(limits: MatchPointLimits): Map<String, Any>
    open val hasKnownSchema: Boolean get() = true
}

class MatchPointAuthorizeItem(
    val item: MatchPointItemReference,
    picRequest: ByteArray,
) : MatchPointPayload(MatchPointTypes.AUTHORIZE_ITEM) {
    private val storedPicRequest = picRequest.copyOf()
    val picRequest: ByteArray get() = storedPicRequest.copyOf()
    override val allowedKinds = setOf(MatchPointMessageKind.REQUEST)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> {
        if (storedPicRequest.size > limits.maximumValueBytes) {
            throw MatchPointProtocolException("PIC-Request exceeds ${limits.maximumValueBytes} bytes")
        }
        return linkedMapOf("item" to item.toWireMap(), "PIC-Request" to storedPicRequest.copyOf())
    }
}

/**
 * Successful authorization response. Reverse evidence only establishes these optional top-level
 * fields, so `item` and `playerGUID` remain bounded opaque plist values rather than invented
 * schemas.
 */
class MatchPointAuthorizeItemResponse(
    item: Any? = null,
    picData: ByteArray? = null,
    playerGuid: Any? = null,
    limits: MatchPointLimits = MatchPointLimits(),
) : MatchPointPayload(MatchPointTypes.AUTHORIZE_ITEM) {
    private val storedItem = item?.let { MatchPointOpaqueValue(it, limits, "authorizeItem.item") }
    private val storedPicData = picData?.copyOf()
    private val storedPlayerGuid = playerGuid?.let {
        MatchPointOpaqueValue(it, limits, "authorizeItem.playerGUID")
    }

    val item: Any? get() = storedItem?.value
    val picData: ByteArray? get() = storedPicData?.copyOf()
    val playerGuid: Any? get() = storedPlayerGuid?.value

    init {
        if ((storedPicData?.size ?: 0) > limits.maximumValueBytes) {
            throw MatchPointProtocolException(
                "authorizeItem.PIC-Data exceeds ${limits.maximumValueBytes} bytes",
            )
        }
    }

    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)

    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf<String, Any>().apply {
            storedItem?.let { put("item", it.wireValue()) }
            storedPicData?.let {
                if (it.size > limits.maximumValueBytes) {
                    throw MatchPointProtocolException(
                        "authorizeItem.PIC-Data exceeds ${limits.maximumValueBytes} bytes",
                    )
                }
                put("PIC-Data", it.copyOf())
            }
            storedPlayerGuid?.let { put("playerGUID", it.wireValue()) }
        }
}

class MatchPointInsertPlayQueueItem(
    val item: MatchPointQueueItem,
    val itemAfter: MatchPointItemReference? = null,
    val metadata: MatchPointMetadata? = null,
    val forInterstitial: Boolean? = null,
) : MatchPointPayload(MatchPointTypes.INSERT_PLAY_QUEUE_ITEM) {
    override val allowedKinds = COMMAND_KINDS
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf<String, Any>("item" to item.toWireMap()).apply {
            itemAfter?.let { put("itemAfter", it.toWireMap()) }
            metadata?.let { put("metadata", it.wireValue()) }
            forInterstitial?.let { put("forInterstitial", it) }
        }
}

class MatchPointRemovePlayQueueItem(
    val item: MatchPointItemReference,
) : MatchPointPayload(MatchPointTypes.REMOVE_PLAY_QUEUE_ITEM) {
    override val allowedKinds = COMMAND_KINDS
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf("item" to item.toWireMap())
}

object MatchPointStop : MatchPointPayload(MatchPointTypes.STOP) {
    override val allowedKinds = COMMAND_KINDS
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = emptyMap()
}

object MatchPointPlaybackInfoRequest : MatchPointPayload(MatchPointTypes.PLAYBACK_INFO) {
    override val allowedKinds = setOf(MatchPointMessageKind.REQUEST)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = emptyMap()
}

class MatchPointPlaybackInfoResponse(info: Any) : MatchPointPayload(MatchPointTypes.PLAYBACK_INFO) {
    private val storedInfo = MatchPointOpaqueValue(info, label = "playbackInfo.info")
    val info: Any get() = storedInfo.value
    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf("info" to storedInfo.wireValue())
}

sealed class MatchPointSeekPosition {
    data class Time(val time: MatchPointCmTime) : MatchPointSeekPosition()
    data class Date(val date: MatchPointDate) : MatchPointSeekPosition()
}

class MatchPointSeek(
    val item: MatchPointItemReference,
    val position: MatchPointSeekPosition,
    val forInterstitial: Boolean? = null,
) : MatchPointPayload(MatchPointTypes.SEEK) {
    override val allowedKinds = COMMAND_KINDS
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf<String, Any>("item" to item.toWireMap()).apply {
            when (val target = position) {
                is MatchPointSeekPosition.Time -> put("time", target.time.toWireMap())
                is MatchPointSeekPosition.Date -> put("date", target.date.secondsSinceReferenceDate)
            }
            forInterstitial?.let { put("forInterstitial", it) }
        }
}

class MatchPointSetRate(
    val rate: Double,
    val networkClockTime: MatchPointCmTime? = null,
    val wallClockTimeDiff: Double? = null,
    val forInterstitial: Boolean? = null,
) : MatchPointPayload(MatchPointTypes.SET_RATE) {
    init {
        require(rate.isFinite() && rate >= 0.0) { "MatchPoint rate must be finite and non-negative" }
        require(wallClockTimeDiff == null || wallClockTimeDiff.isFinite()) {
            "wallClockTimeDiff must be finite"
        }
    }

    override val allowedKinds = COMMAND_KINDS
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf<String, Any>("rate" to rate).apply {
            networkClockTime?.let { put("networkClockTime", it.toWireMap()) }
            wallClockTimeDiff?.let { put("wallClockTimeDiff", it) }
            forInterstitial?.let { put("forInterstitial", it) }
        }
}

class MatchPointPropertyRequest(
    val property: String,
    val item: MatchPointItemReference? = null,
    val trackId: String? = null,
    val forInterstitial: Boolean? = null,
) : MatchPointPayload(MatchPointTypes.PROPERTY) {
    init {
        validateIdentifier(property, "property", MatchPointLimits().maximumIdentifierChars)
        trackId?.let { validateIdentifier(it, "trackID", MatchPointLimits().maximumIdentifierChars) }
    }

    override val allowedKinds = setOf(MatchPointMessageKind.REQUEST)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = propertyFields(
        property,
        item,
        trackId,
        forInterstitial,
    )
}

class MatchPointPropertyResponse(value: Any) : MatchPointPayload(MatchPointTypes.PROPERTY) {
    private val storedValue = MatchPointOpaqueValue(value, label = "property.value")
    val value: Any get() = storedValue.value
    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf("value" to storedValue.wireValue())
}

class MatchPointSetProperty(
    val property: String,
    value: Any,
    val item: MatchPointItemReference? = null,
    val trackId: String? = null,
    val forInterstitial: Boolean? = null,
) : MatchPointPayload(MatchPointTypes.SET_PROPERTY) {
    private val storedValue = MatchPointOpaqueValue(value, label = "setProperty.value")
    val value: Any get() = storedValue.value

    init {
        validateIdentifier(property, "property", MatchPointLimits().maximumIdentifierChars)
        trackId?.let { validateIdentifier(it, "trackID", MatchPointLimits().maximumIdentifierChars) }
    }

    override val allowedKinds = COMMAND_KINDS
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = propertyFields(
        property,
        item,
        trackId,
        forInterstitial,
    ).toMutableMap().apply { put("value", storedValue.wireValue()) }
}

sealed class MatchPointStreamingKey : MatchPointPayload(MatchPointTypes.STREAMING_KEY)

class MatchPointStreamingKeyRequest(
    params: Map<String, Any>? = null,
) : MatchPointStreamingKey() {
    private val storedParams = params?.let {
        MatchPointValueSnapshotter(MatchPointLimits()).freezeMap(it, "streamingKey.params")
    }
    val params: Map<String, Any>? get() = storedParams?.let(MatchPointValueSnapshotter::copyOutMap)
    override val allowedKinds = setOf(MatchPointMessageKind.REQUEST)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        storedParams?.let { linkedMapOf("params" to it) } ?: emptyMap()
}

/** The two top-level `key` value shapes observed in streaming-key responses. */
sealed class MatchPointStreamingKeyValue {
    internal abstract fun wireValue(): Any

    class Data(data: ByteArray) : MatchPointStreamingKeyValue() {
        private val storedData = data.copyOf()
        val data: ByteArray get() = storedData.copyOf()
        internal override fun wireValue(): Any = storedData.copyOf()
    }

    /**
     * A wire string is preserved verbatim. [decodedData] is present only when the string was valid
     * standard Base64; a non-Base64 string remains text and is never reinterpreted as key bytes.
     */
    class Text internal constructor(
        val text: String,
        decodedData: ByteArray?,
    ) : MatchPointStreamingKeyValue() {
        private val storedDecodedData = decodedData?.copyOf()
        val decodedData: ByteArray? get() = storedDecodedData?.copyOf()
        internal override fun wireValue(): Any = text
    }
}

/** Accessory response; typed outbound construction intentionally emits plist Data by default. */
class MatchPointStreamingKeyResponse private constructor(
    val key: MatchPointStreamingKeyValue,
) : MatchPointStreamingKey() {
    constructor(keyData: ByteArray) : this(MatchPointStreamingKeyValue.Data(keyData))

    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)

    override fun wireFields(limits: MatchPointLimits): Map<String, Any> {
        when (val value = key) {
            is MatchPointStreamingKeyValue.Data -> {
                if (value.data.size > limits.maximumValueBytes) {
                    throw MatchPointProtocolException(
                        "streamingKey.key exceeds ${limits.maximumValueBytes} bytes",
                    )
                }
            }
            is MatchPointStreamingKeyValue.Text -> {
                validateText(value.text, "streamingKey.key", limits.maximumStringChars)
                if ((value.decodedData?.size ?: 0) > limits.maximumValueBytes) {
                    throw MatchPointProtocolException(
                        "streamingKey.key Base64 data exceeds ${limits.maximumValueBytes} bytes",
                    )
                }
            }
        }
        return linkedMapOf("key" to key.wireValue())
    }

    companion object {
        internal fun fromWire(value: Any?, limits: MatchPointLimits): MatchPointStreamingKeyResponse =
            when (value) {
                is ByteArray -> {
                    if (value.size > limits.maximumValueBytes) {
                        throw MatchPointProtocolException("streamingKey.key is too large")
                    }
                    MatchPointStreamingKeyResponse(MatchPointStreamingKeyValue.Data(value))
                }
                is String -> {
                    validateText(value, "streamingKey.key", limits.maximumStringChars)
                    val decoded = try {
                        Base64.getDecoder().decode(value)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if ((decoded?.size ?: 0) > limits.maximumValueBytes) {
                        throw MatchPointProtocolException("streamingKey.key Base64 data is too large")
                    }
                    MatchPointStreamingKeyResponse(MatchPointStreamingKeyValue.Text(value, decoded))
                }
                else -> throw MatchPointProtocolException(
                    "streamingKey.key must be Data or a string",
                )
            }
    }
}

object MatchPointUnhandledUrlResponse : MatchPointPayload(MatchPointTypes.UNHANDLED_URL) {
    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = emptyMap()
}

class MatchPointPlaybackCoordinationMedium(
    val name: String,
    val state: String,
) : MatchPointPayload(MatchPointTypes.PLAYBACK_COORDINATION_MEDIUM) {
    init {
        validateIdentifier(name, "playbackCoordinationMedium.name", MatchPointLimits().maximumIdentifierChars)
        validateIdentifier(state, "playbackCoordinationMedium.state", MatchPointLimits().maximumIdentifierChars)
    }

    override val allowedKinds = setOf(MatchPointMessageKind.ONE_WAY)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = linkedMapOf(
        "name" to name,
        "state" to state,
    )
}

enum class MatchPointPlaybackStateName(val wireValue: String) {
    STOPPED("stopped"),
    PAUSED("paused"),
    LOADING("loading"),
    PLAYING("playing"),
    ;

    companion object {
        internal fun fromWire(value: String): MatchPointPlaybackStateName =
            entries.firstOrNull { it.wireValue == value }
                ?: throw MatchPointProtocolException("Unknown playbackState name '$value'")
    }
}

class MatchPointPlaybackState(
    val name: MatchPointPlaybackStateName,
    val item: MatchPointItemReference? = null,
    val itemCurrent: MatchPointItemReference? = null,
) : MatchPointPayload(MatchPointTypes.PLAYBACK_STATE) {
    init {
        require(item == null || itemCurrent == null) {
            "playbackState cannot contain both item and itemCurrent"
        }
    }

    override val allowedKinds = setOf(MatchPointMessageKind.ONE_WAY)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> =
        linkedMapOf<String, Any>("name" to name.wireValue).apply {
            item?.let { put("item", it.toWireMap()) }
            itemCurrent?.let { put("itemCurrent", it.toWireMap()) }
        }
}

object MatchPointNotification : MatchPointPayload(MatchPointTypes.NOTIFICATION) {
    override val allowedKinds = setOf(MatchPointMessageKind.ONE_WAY)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = emptyMap()
}

/** Successful empty response to an operation whose response has no confirmed result fields. */
class MatchPointEmptyResponse(val requestType: String) : MatchPointPayload(requestType) {
    init {
        require(requestType in EMPTY_RESPONSE_TYPES) {
            "MatchPoint type '$requestType' has a typed response body"
        }
    }

    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = emptyMap()
}

/** Failed response: typed success fields are intentionally absent when `error` is present. */
class MatchPointFailureResponse internal constructor(responseType: String) :
    MatchPointPayload(responseType) {
    override val allowedKinds = setOf(MatchPointMessageKind.RESPONSE)
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = emptyMap()
}

/** Bounded forward-compatible payload. It is diagnostic data and never schema readiness. */
class MatchPointUnknownPayload internal constructor(
    type: String,
    kind: MatchPointMessageKind,
    fields: Map<String, Any>,
    limits: MatchPointLimits,
) : MatchPointPayload(type) {
    private val storedFields = MatchPointValueSnapshotter(limits).freezeMap(fields, "unknown '$type'")
    val fields: Map<String, Any> get() = MatchPointValueSnapshotter.copyOutMap(storedFields)
    override val allowedKinds = setOf(kind)
    override val hasKnownSchema: Boolean get() = false
    override fun wireFields(limits: MatchPointLimits): Map<String, Any> = storedFields
}

/** One inner MatchPoint bplist message. This class does not imply a live player or RCS handler. */
class MatchPointMessage private constructor(
    val kind: MatchPointMessageKind,
    val messageId: Long?,
    val payload: MatchPointPayload,
    error: Any?,
    limits: MatchPointLimits,
) {
    private val storedError = error?.let { MatchPointOpaqueValue(it, limits, "response.error") }

    init {
        if (kind !in payload.allowedKinds) {
            throw MatchPointProtocolException("${payload.type} is not valid as ${kind.name.lowercase()}")
        }
        if (kind == MatchPointMessageKind.ONE_WAY) {
            if (messageId != null || error != null) {
                throw MatchPointProtocolException("One-way MatchPoint messages have no kind, messageID, or error")
            }
        } else if (messageId == null || messageId == 0L) {
            throw MatchPointProtocolException("MatchPoint ${kind.name.lowercase()} requires non-zero messageID")
        }
        if (error != null && kind != MatchPointMessageKind.RESPONSE) {
            throw MatchPointProtocolException("Only MatchPoint responses may contain error")
        }
        if (payload is MatchPointFailureResponse && error == null) {
            throw MatchPointProtocolException("Failure response requires error")
        }
        if (error != null &&
            payload !is MatchPointFailureResponse &&
            payload !is MatchPointUnknownPayload
        ) {
            throw MatchPointProtocolException(
                "Use a failure response when MatchPoint error is present",
            )
        }
    }

    val type: String get() = payload.type
    val error: Any? get() = storedError?.value
    val hasKnownSchema: Boolean get() = payload.hasKnownSchema

    /** Static protocol knowledge is never sufficient to claim runtime/negotiation readiness. */
    val isRuntimeReady: Boolean get() = false

    internal fun errorWireValue(): Any? = storedError?.wireValue()

    companion object {
        fun request(
            messageId: Long,
            payload: MatchPointPayload,
            limits: MatchPointLimits = MatchPointLimits(),
        ): MatchPointMessage = MatchPointMessage(
            MatchPointMessageKind.REQUEST,
            messageId,
            payload,
            error = null,
            limits,
        )

        fun response(
            messageId: Long,
            payload: MatchPointPayload,
            error: Any? = null,
            limits: MatchPointLimits = MatchPointLimits(),
        ): MatchPointMessage = MatchPointMessage(
            MatchPointMessageKind.RESPONSE,
            messageId,
            payload,
            error,
            limits,
        )

        fun failure(
            messageId: Long,
            responseType: String,
            error: Any,
            limits: MatchPointLimits = MatchPointLimits(),
        ): MatchPointMessage {
            if (responseType !in RESPONSE_TYPES) {
                throw MatchPointProtocolException(
                    "Type '$responseType' has no confirmed MatchPoint response",
                )
            }
            return MatchPointMessage(
                MatchPointMessageKind.RESPONSE,
                messageId,
                MatchPointFailureResponse(responseType),
                error,
                limits,
            )
        }

        fun oneWay(
            payload: MatchPointPayload,
            limits: MatchPointLimits = MatchPointLimits(),
        ): MatchPointMessage = MatchPointMessage(
            MatchPointMessageKind.ONE_WAY,
            messageId = null,
            payload,
            error = null,
            limits,
        )

        internal fun decoded(
            kind: MatchPointMessageKind,
            messageId: Long?,
            payload: MatchPointPayload,
            error: Any?,
            limits: MatchPointLimits,
        ): MatchPointMessage = MatchPointMessage(kind, messageId, payload, error, limits)
    }
}

/** Strict codec for the inner binary-plist message. */
object MatchPointCodec {
    fun encode(
        message: MatchPointMessage,
        limits: MatchPointLimits = MatchPointLimits(),
    ): ByteArray {
        val fields = message.payload.wireFields(limits)
        // Re-validate caller-built typed values against the limits supplied for this encoding.
        // Model constructors use safe defaults, while a session may deliberately choose less.
        decodePayload(
            message.type,
            message.kind,
            fields,
            message.errorWireValue() != null,
            limits,
        )
        val map = linkedMapOf<String, Any>()
        when (message.kind) {
            MatchPointMessageKind.REQUEST -> {
                map["kind"] = "request"
                map["messageID"] = CafUInt64.plistNumber(requireNotNull(message.messageId))
                map["type"] = message.type
            }
            MatchPointMessageKind.RESPONSE -> {
                map["kind"] = "response"
                map["type"] = message.type
                map["messageID"] = CafUInt64.plistNumber(requireNotNull(message.messageId))
                message.errorWireValue()?.let { map["error"] = it }
            }
            MatchPointMessageKind.ONE_WAY -> map["type"] = message.type
        }
        fields.forEach { (key, value) ->
            if (key in HEADER_KEYS) {
                throw MatchPointProtocolException("MatchPoint payload cannot replace header '$key'")
            }
            map[key] = value
        }
        val safe = MatchPointValueSnapshotter(limits).freezeMap(map, "MatchPoint message")
        val encoded = try {
            BplistCodec.encode(safe)
        } catch (error: IllegalArgumentException) {
            throw MatchPointProtocolException("Unable to encode MatchPoint message", error)
        }
        if (encoded.size > limits.maximumMessageBytes) {
            throw MatchPointProtocolException(
                "MatchPoint message exceeds ${limits.maximumMessageBytes} bytes",
            )
        }
        return encoded
    }

    fun decode(
        bytes: ByteArray,
        limits: MatchPointLimits = MatchPointLimits(),
    ): MatchPointMessage {
        if (bytes.size > limits.maximumMessageBytes) {
            throw MatchPointProtocolException(
                "MatchPoint message exceeds ${limits.maximumMessageBytes} bytes",
            )
        }
        val root = try {
            stringMap(BplistCodec.decode(bytes, limits.messageDecodeLimits()), "MatchPoint message")
        } catch (error: IllegalArgumentException) {
            if (error is MatchPointProtocolException) throw error
            throw MatchPointProtocolException("Invalid MatchPoint binary plist", error)
        }
        val type = requiredString(root, "type", "MatchPoint message", limits.maximumIdentifierChars)
        val kind = when (val rawKind = root["kind"]) {
            null -> MatchPointMessageKind.ONE_WAY
            "request" -> MatchPointMessageKind.REQUEST
            "response" -> MatchPointMessageKind.RESPONSE
            else -> throw MatchPointProtocolException("Unknown MatchPoint kind '$rawKind'")
        }
        val messageId = if (kind == MatchPointMessageKind.ONE_WAY) {
            if ("messageID" in root || "error" in root) {
                throw MatchPointProtocolException("One-way MatchPoint message has request/response fields")
            }
            null
        } else {
            if ("messageID" !in root) throw MatchPointProtocolException("MatchPoint message requires messageID")
            unsignedLongBits(root["messageID"], "messageID").also {
                if (it == 0L) throw MatchPointProtocolException("messageID must be non-zero")
            }
        }
        if (kind != MatchPointMessageKind.RESPONSE && "error" in root) {
            throw MatchPointProtocolException("Only MatchPoint responses may contain error")
        }
        val error = root["error"]?.let {
            MatchPointValueSnapshotter(limits).freeze(it, "response.error")
        }
        val fields = root.filterKeys { it !in HEADER_KEYS }
        val payload = decodePayload(type, kind, fields, error != null, limits)
        return MatchPointMessage.decoded(kind, messageId, payload, error, limits)
    }

    private fun decodePayload(
        type: String,
        kind: MatchPointMessageKind,
        fields: Map<String, Any?>,
        hasError: Boolean,
        limits: MatchPointLimits,
    ): MatchPointPayload {
        if (hasError) {
            if (kind != MatchPointMessageKind.RESPONSE) {
                throw MatchPointProtocolException("Type '$type' has no confirmed error response")
            }
            if (type !in KNOWN_TYPES) {
                return MatchPointUnknownPayload(
                    type,
                    kind,
                    nonNullFields(fields, "unknown '$type'"),
                    limits,
                )
            }
            if (type !in RESPONSE_TYPES) {
                throw MatchPointProtocolException("Type '$type' has no confirmed error response")
            }
            requireExactKeys(fields, emptySet(), "$type error response")
            return MatchPointFailureResponse(type)
        }
        val decoded = when (kind) {
            MatchPointMessageKind.REQUEST -> decodeRequest(type, fields, limits)
            MatchPointMessageKind.RESPONSE -> decodeResponse(type, fields, limits)
            MatchPointMessageKind.ONE_WAY -> decodeOneWay(type, fields, limits)
        }
        if (decoded != null) return decoded
        if (type in KNOWN_TYPES) {
            throw MatchPointProtocolException(
                "Known MatchPoint type '$type' is not valid as ${kind.name.lowercase()}",
            )
        }
        return MatchPointUnknownPayload(
            type,
            kind,
            nonNullFields(fields, "unknown '$type'"),
            limits,
        )
    }

    private fun decodeRequest(
        type: String,
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointPayload? = when (type) {
        MatchPointTypes.AUTHORIZE_ITEM -> {
            requireExactKeys(fields, setOf("item", "PIC-Request"), type)
            val data = fields["PIC-Request"] as? ByteArray
                ?: throw MatchPointProtocolException("authorizeItem.PIC-Request must be Data")
            if (data.size > limits.maximumValueBytes) {
                throw MatchPointProtocolException("authorizeItem.PIC-Request is too large")
            }
            MatchPointAuthorizeItem(
                MatchPointItemReference.fromWire(fields["item"], limits, "authorizeItem.item"),
                data,
            )
        }
        MatchPointTypes.PLAYBACK_INFO -> {
            requireExactKeys(fields, emptySet(), type)
            MatchPointPlaybackInfoRequest
        }
        MatchPointTypes.PROPERTY -> decodePropertyRequest(fields, limits)
        MatchPointTypes.STREAMING_KEY -> decodeStreamingKeyRequest(fields, limits)
        in COMMAND_TYPES -> decodeCommand(type, fields, limits)
        else -> null
    }

    private fun decodeResponse(
        type: String,
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointPayload? = when (type) {
        MatchPointTypes.AUTHORIZE_ITEM -> {
            requireOnlyKeys(fields, setOf("item", "PIC-Data", "playerGUID"), "$type response")
            val picData = if ("PIC-Data" in fields) {
                (fields["PIC-Data"] as? ByteArray)
                    ?: throw MatchPointProtocolException("authorizeItem.PIC-Data must be Data")
            } else {
                null
            }
            MatchPointAuthorizeItemResponse(
                item = fields["item"],
                picData = picData,
                playerGuid = fields["playerGUID"],
                limits = limits,
            )
        }
        MatchPointTypes.PLAYBACK_INFO -> {
            requireExactKeys(fields, setOf("info"), "$type response")
            MatchPointPlaybackInfoResponse(requireNotNull(fields["info"]))
        }
        MatchPointTypes.PROPERTY -> {
            requireExactKeys(fields, setOf("value"), "$type response")
            MatchPointPropertyResponse(requireNotNull(fields["value"]))
        }
        MatchPointTypes.STREAMING_KEY -> decodeStreamingKeyResponse(fields, limits)
        MatchPointTypes.UNHANDLED_URL -> {
            requireExactKeys(fields, emptySet(), "$type response")
            MatchPointUnhandledUrlResponse
        }
        in EMPTY_RESPONSE_TYPES -> {
            requireExactKeys(fields, emptySet(), "$type response")
            MatchPointEmptyResponse(type)
        }
        else -> null
    }

    private fun decodeOneWay(
        type: String,
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointPayload? = when (type) {
        in COMMAND_TYPES -> decodeCommand(type, fields, limits)
        MatchPointTypes.PLAYBACK_COORDINATION_MEDIUM -> {
            requireExactKeys(fields, setOf("name", "state"), type)
            MatchPointPlaybackCoordinationMedium(
                requiredString(fields, "name", type, limits.maximumIdentifierChars),
                requiredString(fields, "state", type, limits.maximumIdentifierChars),
            )
        }
        MatchPointTypes.PLAYBACK_STATE -> {
            requireOnlyKeys(fields, setOf("name", "item", "itemCurrent"), type)
            if ("name" !in fields) throw MatchPointProtocolException("playbackState requires name")
            MatchPointPlaybackState(
                MatchPointPlaybackStateName.fromWire(
                    requiredString(fields, "name", type, limits.maximumIdentifierChars),
                ),
                fields["item"]?.let {
                    MatchPointItemReference.fromWire(it, limits, "playbackState.item")
                },
                fields["itemCurrent"]?.let {
                    MatchPointItemReference.fromWire(it, limits, "playbackState.itemCurrent")
                },
            )
        }
        MatchPointTypes.NOTIFICATION -> {
            requireExactKeys(fields, emptySet(), type)
            MatchPointNotification
        }
        else -> null
    }

    private fun decodeCommand(
        type: String,
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointPayload = when (type) {
        MatchPointTypes.INSERT_PLAY_QUEUE_ITEM -> {
            requireOnlyKeys(fields, setOf("item", "itemAfter", "metadata", "forInterstitial"), type)
            if ("item" !in fields) throw MatchPointProtocolException("$type requires item")
            MatchPointInsertPlayQueueItem(
                item = MatchPointQueueItem.fromWire(fields["item"], limits, "$type.item"),
                itemAfter = fields["itemAfter"]?.let {
                    MatchPointItemReference.fromWire(it, limits, "$type.itemAfter")
                },
                metadata = fields["metadata"]?.let {
                    MatchPointMetadata(nonNullFields(stringMap(it, "$type.metadata"), "$type.metadata"), limits)
                },
                forInterstitial = optionalBoolean(fields, "forInterstitial", type),
            )
        }
        MatchPointTypes.REMOVE_PLAY_QUEUE_ITEM -> {
            requireExactKeys(fields, setOf("item"), type)
            MatchPointRemovePlayQueueItem(
                MatchPointItemReference.fromWire(fields["item"], limits, "$type.item"),
            )
        }
        MatchPointTypes.STOP -> {
            requireExactKeys(fields, emptySet(), type)
            MatchPointStop
        }
        MatchPointTypes.SEEK -> decodeSeek(fields, limits)
        MatchPointTypes.SET_RATE -> decodeSetRate(fields, limits)
        MatchPointTypes.SET_PROPERTY -> decodeSetProperty(fields, limits)
        else -> throw MatchPointProtocolException("Unsupported MatchPoint command '$type'")
    }

    private fun decodeSeek(fields: Map<String, Any?>, limits: MatchPointLimits): MatchPointSeek {
        val type = MatchPointTypes.SEEK
        requireOnlyKeys(fields, setOf("item", "time", "date", "forInterstitial"), type)
        if ("item" !in fields) throw MatchPointProtocolException("seek requires item")
        if (("time" in fields) == ("date" in fields)) {
            throw MatchPointProtocolException("seek requires exactly one of time or date")
        }
        val position = fields["time"]?.let {
            MatchPointSeekPosition.Time(MatchPointCmTime.fromWire(it, "seek.time"))
        } ?: MatchPointSeekPosition.Date(
            MatchPointDate(finiteDouble(fields["date"], "seek.date")),
        )
        return MatchPointSeek(
            MatchPointItemReference.fromWire(fields["item"], limits, "seek.item"),
            position,
            optionalBoolean(fields, "forInterstitial", type),
        )
    }

    private fun decodeSetRate(fields: Map<String, Any?>, limits: MatchPointLimits): MatchPointSetRate {
        val type = MatchPointTypes.SET_RATE
        requireOnlyKeys(
            fields,
            setOf("rate", "networkClockTime", "wallClockTimeDiff", "forInterstitial"),
            type,
        )
        if ("rate" !in fields) throw MatchPointProtocolException("setRate requires rate")
        return try {
            MatchPointSetRate(
                rate = finiteDouble(fields["rate"], "setRate.rate"),
                networkClockTime = fields["networkClockTime"]?.let {
                    MatchPointCmTime.fromWire(it, "setRate.networkClockTime")
                },
                wallClockTimeDiff = fields["wallClockTimeDiff"]?.let {
                    finiteDouble(it, "setRate.wallClockTimeDiff")
                },
                forInterstitial = optionalBoolean(fields, "forInterstitial", type),
            )
        } catch (error: IllegalArgumentException) {
            if (error is MatchPointProtocolException) throw error
            throw MatchPointProtocolException("Invalid setRate: ${error.message}", error)
        }
    }

    private fun decodePropertyRequest(
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointPropertyRequest {
        requireOnlyKeys(fields, PROPERTY_KEYS, MatchPointTypes.PROPERTY)
        if ("property" !in fields) throw MatchPointProtocolException("property requires property")
        return MatchPointPropertyRequest(
            property = requiredString(
                fields,
                "property",
                MatchPointTypes.PROPERTY,
                limits.maximumIdentifierChars,
            ),
            item = fields["item"]?.let {
                MatchPointItemReference.fromWire(it, limits, "property.item")
            },
            trackId = optionalString(
                fields,
                "trackID",
                MatchPointTypes.PROPERTY,
                limits.maximumIdentifierChars,
            ),
            forInterstitial = optionalBoolean(fields, "forInterstitial", MatchPointTypes.PROPERTY),
        )
    }

    private fun decodeSetProperty(
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointSetProperty {
        requireOnlyKeys(fields, PROPERTY_KEYS + "value", MatchPointTypes.SET_PROPERTY)
        if ("property" !in fields || "value" !in fields) {
            throw MatchPointProtocolException("setProperty requires property and value")
        }
        return MatchPointSetProperty(
            property = requiredString(
                fields,
                "property",
                MatchPointTypes.SET_PROPERTY,
                limits.maximumIdentifierChars,
            ),
            value = requireNotNull(fields["value"]),
            item = fields["item"]?.let {
                MatchPointItemReference.fromWire(it, limits, "setProperty.item")
            },
            trackId = optionalString(
                fields,
                "trackID",
                MatchPointTypes.SET_PROPERTY,
                limits.maximumIdentifierChars,
            ),
            forInterstitial = optionalBoolean(
                fields,
                "forInterstitial",
                MatchPointTypes.SET_PROPERTY,
            ),
        )
    }

    private fun decodeStreamingKeyRequest(
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointStreamingKeyRequest {
        requireOnlyKeys(fields, setOf("params"), MatchPointTypes.STREAMING_KEY)
        val params = fields["params"]?.let {
            nonNullFields(stringMap(it, "streamingKey.params"), "streamingKey.params")
        }
        return MatchPointStreamingKeyRequest(params)
    }

    private fun decodeStreamingKeyResponse(
        fields: Map<String, Any?>,
        limits: MatchPointLimits,
    ): MatchPointStreamingKeyResponse {
        requireExactKeys(fields, setOf("key"), "${MatchPointTypes.STREAMING_KEY} response")
        return MatchPointStreamingKeyResponse.fromWire(fields["key"], limits)
    }

    private val HEADER_KEYS = setOf("kind", "messageID", "type", "error")
}

/**
 * Confirmed outer wrapper and `comm` package. This is deliberately only a value codec; it is not
 * an [com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler] and cannot advertise the feature.
 */
class MatchPointCarrier private constructor(
    val message: MatchPointMessage,
    innerData: ByteArray,
    rcsBody: ByteArray,
) {
    private val storedInnerData = innerData.copyOf()
    private val storedRcsBody = rcsBody.copyOf()

    val innerData: ByteArray get() = storedInnerData.copyOf()
    val rcsBody: ByteArray get() = storedRcsBody.copyOf()

    fun asRcsMessage(): RcsMessage = RcsMessage.comm(storedRcsBody.copyOf())

    companion object {
        internal fun create(
            message: MatchPointMessage,
            innerData: ByteArray,
            rcsBody: ByteArray,
        ): MatchPointCarrier = MatchPointCarrier(message, innerData, rcsBody)
    }
}

object MatchPointCarrierCodec {
    fun encode(
        message: MatchPointMessage,
        limits: MatchPointLimits = MatchPointLimits(),
    ): MatchPointCarrier {
        val inner = MatchPointCodec.encode(message, limits)
        val body = try {
            BplistCodec.encode(linkedMapOf("params" to linkedMapOf("data" to inner)))
        } catch (error: IllegalArgumentException) {
            throw MatchPointProtocolException("Unable to encode MatchPoint carrier", error)
        }
        if (body.size > limits.maximumCarrierBytes) {
            throw MatchPointProtocolException(
                "MatchPoint carrier exceeds ${limits.maximumCarrierBytes} bytes",
            )
        }
        return MatchPointCarrier.create(message, inner, body)
    }

    fun decode(
        rcsMessage: RcsMessage,
        limits: MatchPointLimits = MatchPointLimits(),
    ): MatchPointCarrier {
        if (rcsMessage.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            throw MatchPointProtocolException("MatchPoint carrier must use comm messageType")
        }
        val body = rcsMessage.body.copyOf()
        if (body.size > limits.maximumCarrierBytes) {
            throw MatchPointProtocolException(
                "MatchPoint carrier exceeds ${limits.maximumCarrierBytes} bytes",
            )
        }
        val outer = try {
            stringMap(BplistCodec.decode(body, limits.carrierDecodeLimits()), "MatchPoint carrier")
        } catch (error: IllegalArgumentException) {
            if (error is MatchPointProtocolException) throw error
            throw MatchPointProtocolException("Invalid MatchPoint carrier", error)
        }
        requireExactKeys(outer, setOf("params"), "MatchPoint carrier")
        val params = stringMap(outer["params"], "MatchPoint carrier.params")
        requireExactKeys(params, setOf("data"), "MatchPoint carrier.params")
        val inner = params["data"] as? ByteArray
            ?: throw MatchPointProtocolException("MatchPoint carrier.params.data must be Data")
        if (inner.size > limits.maximumMessageBytes) {
            throw MatchPointProtocolException("MatchPoint inner message is too large")
        }
        return MatchPointCarrier.create(MatchPointCodec.decode(inner, limits), inner, body)
    }
}

data class MatchPointPendingRequest(
    /** Raw UInt64 bits. */
    val messageId: Long,
    val type: String,
    val startedAtNanos: Long,
    val deadlineNanos: Long,
)

/** Bounded, expiring request correlation state for one future MatchPoint channel. */
class MatchPointRequestTracker(
    firstMessageId: Long = 1L,
    private val maximumPending: Int = 128,
    private val timeoutMillis: Long = 15_000L,
    private val nowNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val pending = LinkedHashMap<Long, MatchPointPendingRequest>()
    private var nextMessageId = firstMessageId
    private var closed = false

    init {
        require(firstMessageId != 0L) { "MatchPoint message IDs must start above UInt64 zero" }
        require(maximumPending in 1..4096) { "maximumPending is out of range" }
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) { "timeoutMillis is out of range" }
    }

    @Synchronized
    fun begin(payload: MatchPointPayload): Pair<MatchPointMessage, MatchPointPendingRequest> {
        check(!closed) { "MatchPoint request tracker is closed" }
        if (MatchPointMessageKind.REQUEST !in payload.allowedKinds) {
            throw MatchPointProtocolException("${payload.type} is not a MatchPoint request")
        }
        expireLocked(nowNanos())
        if (pending.size >= maximumPending) {
            throw MatchPointProtocolException("MatchPoint pending request limit $maximumPending reached")
        }
        repeat(maximumPending + 1) {
            val id = nextMessageId
            nextMessageId = incrementUInt64(id)
            if (id !in pending) {
                val started = nowNanos()
                val timeoutNanos = timeoutMillis * NANOS_PER_MILLISECOND
                val deadline = saturatingAdd(started, timeoutNanos)
                val tracked = MatchPointPendingRequest(id, payload.type, started, deadline)
                pending[id] = tracked
                return MatchPointMessage.request(id, payload) to tracked
            }
        }
        throw MatchPointProtocolException("Unable to allocate MatchPoint messageID")
    }

    @Synchronized
    fun accept(response: MatchPointMessage): MatchPointPendingRequest {
        check(!closed) { "MatchPoint request tracker is closed" }
        if (response.kind != MatchPointMessageKind.RESPONSE) {
            throw MatchPointProtocolException("Expected a MatchPoint response")
        }
        expireLocked(nowNanos())
        val id = requireNotNull(response.messageId)
        val request = pending[id]
            ?: throw MatchPointProtocolException(
                "Unknown MatchPoint response messageID ${CafUInt64.toString(id)}",
            )
        if (request.type != response.type) {
            throw MatchPointProtocolException(
                "MatchPoint response ${CafUInt64.toString(id)} expected ${request.type}, " +
                    "got ${response.type}",
            )
        }
        pending.remove(id)
        return request
    }

    @Synchronized
    fun expire(): List<MatchPointPendingRequest> =
        if (closed) emptyList() else expireLocked(nowNanos())

    @Synchronized
    fun cancel(request: MatchPointPendingRequest): Boolean =
        pending.remove(request.messageId, request)

    @Synchronized
    fun size(): Int = pending.size

    @Synchronized
    override fun close() {
        closed = true
        pending.clear()
    }

    private fun expireLocked(now: Long): List<MatchPointPendingRequest> {
        val expired = pending.values.filter { now - it.deadlineNanos >= 0 }
        expired.forEach { pending.remove(it.messageId) }
        return expired
    }

    private fun incrementUInt64(value: Long): Long = if (value == -1L) 1L else value + 1L

    private fun saturatingAdd(value: Long, positiveDelta: Long): Long =
        if (value > Long.MAX_VALUE - positiveDelta) Long.MAX_VALUE else value + positiveDelta

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
    }
}

private val COMMAND_KINDS = setOf(MatchPointMessageKind.REQUEST, MatchPointMessageKind.ONE_WAY)
private val COMMAND_TYPES = setOf(
    MatchPointTypes.INSERT_PLAY_QUEUE_ITEM,
    MatchPointTypes.REMOVE_PLAY_QUEUE_ITEM,
    MatchPointTypes.STOP,
    MatchPointTypes.SEEK,
    MatchPointTypes.SET_RATE,
    MatchPointTypes.SET_PROPERTY,
)
private val EMPTY_RESPONSE_TYPES = setOf(
    MatchPointTypes.INSERT_PLAY_QUEUE_ITEM,
    MatchPointTypes.REMOVE_PLAY_QUEUE_ITEM,
    MatchPointTypes.STOP,
    MatchPointTypes.SEEK,
    MatchPointTypes.SET_RATE,
    MatchPointTypes.SET_PROPERTY,
)
private val RESPONSE_TYPES = EMPTY_RESPONSE_TYPES + setOf(
    MatchPointTypes.AUTHORIZE_ITEM,
    MatchPointTypes.PLAYBACK_INFO,
    MatchPointTypes.PROPERTY,
    MatchPointTypes.STREAMING_KEY,
    MatchPointTypes.UNHANDLED_URL,
)
private val KNOWN_TYPES = RESPONSE_TYPES + COMMAND_TYPES + setOf(
    MatchPointTypes.PLAYBACK_COORDINATION_MEDIUM,
    MatchPointTypes.PLAYBACK_STATE,
    MatchPointTypes.NOTIFICATION,
)
private val PROPERTY_KEYS = setOf("property", "item", "trackID", "forInterstitial")
private val HEADER_KEYS = setOf("kind", "messageID", "type", "error")

private fun propertyFields(
    property: String,
    item: MatchPointItemReference?,
    trackId: String?,
    forInterstitial: Boolean?,
): MutableMap<String, Any> = linkedMapOf<String, Any>("property" to property).apply {
    item?.let { put("item", it.toWireMap()) }
    trackId?.let { put("trackID", it) }
    forInterstitial?.let { put("forInterstitial", it) }
}

private fun stringMap(value: Any?, label: String): Map<String, Any?> {
    val source = value as? Map<*, *> ?: throw MatchPointProtocolException("$label must be a dictionary")
    val result = LinkedHashMap<String, Any?>(source.size)
    source.forEach { (key, item) ->
        val text = key as? String
            ?: throw MatchPointProtocolException("$label keys must be strings")
        if (result.put(text, item) != null) {
            throw MatchPointProtocolException("$label contains duplicate key '$text'")
        }
    }
    return result
}

private fun nonNullFields(value: Map<String, Any?>, label: String): Map<String, Any> {
    val result = LinkedHashMap<String, Any>(value.size)
    value.forEach { (key, item) ->
        result[key] = item ?: throw MatchPointProtocolException("$label.$key must not be null")
    }
    return result
}

private fun requireExactKeys(values: Map<String, *>, expected: Set<String>, label: String) {
    if (values.keys != expected) {
        val missing = expected - values.keys
        val extra = values.keys - expected
        throw MatchPointProtocolException(
            buildString {
                append("$label has invalid fields")
                if (missing.isNotEmpty()) append("; missing ${missing.sorted().joinToString()}")
                if (extra.isNotEmpty()) append("; extra ${extra.sorted().joinToString()}")
            },
        )
    }
}

private fun requireOnlyKeys(values: Map<String, *>, allowed: Set<String>, label: String) {
    val extra = values.keys - allowed
    if (extra.isNotEmpty()) {
        throw MatchPointProtocolException("$label has extra fields ${extra.sorted().joinToString()}")
    }
}

private fun requiredString(
    values: Map<String, *>,
    key: String,
    label: String,
    maximumChars: Int,
): String {
    val value = values[key] as? String
        ?: throw MatchPointProtocolException("$label.$key must be a string")
    validateIdentifier(value, "$label.$key", maximumChars)
    return value
}

private fun optionalString(
    values: Map<String, *>,
    key: String,
    label: String,
    maximumChars: Int,
): String? = if (key in values) requiredString(values, key, label, maximumChars) else null

private fun optionalBoolean(values: Map<String, *>, key: String, label: String): Boolean? {
    if (key !in values) return null
    return values[key] as? Boolean
        ?: throw MatchPointProtocolException("$label.$key must be a boolean")
}

private fun signedLong(value: Any?, label: String): Long = when (value) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is BigInteger -> if (value < SIGNED_LONG_MIN || value > SIGNED_LONG_MAX) {
        throw MatchPointProtocolException("$label is outside Int64")
    } else {
        value.toLong()
    }
    else -> throw MatchPointProtocolException("$label must be an exact integer")
}

private fun unsignedLongBits(value: Any?, label: String): Long = try {
    CafUInt64.parse(value, label)
} catch (error: IllegalArgumentException) {
    throw MatchPointProtocolException(error.message ?: "$label must be UInt64", error)
}

private fun finiteDouble(value: Any?, label: String): Double {
    val number = value as? Number ?: throw MatchPointProtocolException("$label must be numeric")
    val result = number.toDouble()
    if (!result.isFinite()) throw MatchPointProtocolException("$label must be finite")
    return result
}

private fun validateIdentifier(value: String, label: String, maximumChars: Int) {
    if (value.isEmpty()) {
        throw MatchPointProtocolException("$label must not be empty")
    }
    validateText(value, label, maximumChars)
}

private fun validateText(value: String, label: String, maximumChars: Int) {
    if (value.length > maximumChars ||
        value.any { it == '\u0000' || it.isISOControl() } ||
        !Normalizer.isNormalized(value, Normalizer.Form.NFC)
    ) {
        throw MatchPointProtocolException(
            "$label must be bounded NFC text without control characters",
        )
    }
}

private fun validateUrl(value: String, label: String, maximumChars: Int) {
    validateIdentifier(value, label, maximumChars)
    val uri = try {
        URI(value)
    } catch (error: Exception) {
        throw MatchPointProtocolException("$label must be a valid absolute URL", error)
    }
    if (!uri.isAbsolute || uri.host.isNullOrEmpty() || uri.scheme.lowercase() !in setOf("http", "https")) {
        throw MatchPointProtocolException("$label must be an absolute HTTP(S) URL")
    }
    if (uri.userInfo != null || uri.fragment != null) {
        throw MatchPointProtocolException("$label must not contain credentials or a fragment")
    }
}

private fun totalDataBytes(value: Any): Long {
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    fun count(current: Any): Long {
        if (current is ByteArray) return if (visited.add(current)) current.size.toLong() else 0L
        if (current !is List<*> && current !is Map<*, *>) return 0L
        if (!visited.add(current)) return 0L
        val children = if (current is List<*>) current else (current as Map<*, *>).values
        var total = 0L
        children.forEach { child ->
            val amount = count(requireNotNull(child))
            if (total > Long.MAX_VALUE - amount) {
                throw MatchPointProtocolException("artwork data byte count overflows")
            }
            total += amount
        }
        return total
    }

    return count(value)
}

/** Identity-aware copier prevents cycles and bounds shared-DAG expansion by aggregate budgets. */
private class MatchPointValueSnapshotter(private val limits: MatchPointLimits) {
    private val active = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var nodes = 0
    private var entries = 0
    private var valueBytes = 0L

    fun freeze(value: Any, label: String): Any = copy(value, label, depth = 0)

    fun freezeMap(values: Map<String, Any>, label: String): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return freeze(values, label) as Map<String, Any>
    }

    private fun copy(value: Any, label: String, depth: Int): Any {
        if (depth > limits.maximumNestingDepth) {
            throw MatchPointProtocolException("$label exceeds nesting depth ${limits.maximumNestingDepth}")
        }
        if (++nodes > limits.maximumValueNodes) {
            throw MatchPointProtocolException("$label exceeds value-node budget ${limits.maximumValueNodes}")
        }
        when (value) {
            is Boolean, is Byte, is Short, is Int, is Long -> return value
            is BigInteger -> {
                if (value.signum() < 0 || value.bitLength() > 64) {
                    throw MatchPointProtocolException("$label integer is outside UInt64")
                }
                return value
            }
            is Float -> {
                if (!value.isFinite()) throw MatchPointProtocolException("$label must be finite")
                return value
            }
            is Double -> {
                if (!value.isFinite()) throw MatchPointProtocolException("$label must be finite")
                return value
            }
            is String -> {
                validateText(value, label, limits.maximumStringChars)
                consumeBytes(value.length.toLong() * 4L, label)
                return value
            }
            is ByteArray -> {
                consumeBytes(value.size.toLong(), label)
                return value.copyOf()
            }
        }
        if (!active.add(value)) throw MatchPointProtocolException("$label contains a cycle")
        try {
            return when (value) {
                is List<*> -> {
                    consumeEntries(value.size, label)
                    val mutable = ArrayList<Any>(value.size)
                    // Repeated DAG edges are expanded only while the aggregate node/entry budgets permit.
                    value.forEachIndexed { index, item ->
                        mutable += copy(
                            item ?: throw MatchPointProtocolException("$label[$index] must not be null"),
                            "$label[$index]",
                            depth + 1,
                        )
                    }
                    Collections.unmodifiableList(mutable)
                }
                is Map<*, *> -> {
                    consumeEntries(value.size, label)
                    val mutable = LinkedHashMap<String, Any>(value.size)
                    value.forEach { (rawKey, item) ->
                        val key = rawKey as? String
                            ?: throw MatchPointProtocolException("$label keys must be strings")
                        validateText(key, "$label key", limits.maximumStringChars)
                        if (mutable.containsKey(key)) {
                            throw MatchPointProtocolException("$label has duplicate key '$key'")
                        }
                        mutable[key] = copy(
                            item ?: throw MatchPointProtocolException("$label.$key must not be null"),
                            "$label.$key",
                            depth + 1,
                        )
                    }
                    Collections.unmodifiableMap(mutable)
                }
                else -> throw MatchPointProtocolException(
                    "$label contains unsupported ${value.javaClass.simpleName}",
                )
            }
        } finally {
            active.remove(value)
        }
    }

    private fun consumeEntries(count: Int, label: String) {
        if (count < 0 || entries > limits.maximumCollectionEntries - count) {
            throw MatchPointProtocolException(
                "$label exceeds collection-entry budget ${limits.maximumCollectionEntries}",
            )
        }
        entries += count
    }

    private fun consumeBytes(count: Long, label: String) {
        if (count < 0 || valueBytes > limits.maximumValueBytes.toLong() - count) {
            throw MatchPointProtocolException("$label exceeds value-byte budget ${limits.maximumValueBytes}")
        }
        valueBytes += count
    }

    companion object {
        fun copyOut(value: Any): Any = when (value) {
            is ByteArray -> value.copyOf()
            is List<*> -> Collections.unmodifiableList(value.map { copyOut(requireNotNull(it)) })
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any>(value.size)
                value.forEach { (key, item) -> result[key as String] = copyOut(requireNotNull(item)) }
                Collections.unmodifiableMap(result)
            }
            else -> value
        }

        @Suppress("UNCHECKED_CAST")
        fun copyOutMap(value: Map<String, Any>): Map<String, Any> =
            copyOut(value) as Map<String, Any>
    }
}

private val SIGNED_LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
private val SIGNED_LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
