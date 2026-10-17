package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.BplistDecodeLimits
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackLimits
import java.math.BigInteger
import java.util.UUID

class CarPlayUiSyncProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * The three identifiers used by UI Sync have different scopes and must not be interchanged.
 *
 * [clusterControlRcsUuid] selects the encrypted RCS stream. [uiSyncChannelUuid] identifies the
 * protocol carried inside that stream. [displayId] is the opaque, case-sensitive identifier of
 * the vehicle display. [vehicleId] is the stable vehicle identity carried in reset messages.
 */
data class CarPlayUiSyncChannelDescriptor(
    val displayId: String,
    val vehicleId: String,
) {
    init {
        validateIdentifier(displayId, "displayId", ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS)
        validateIdentifier(vehicleId, "vehicleId", ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS)
    }

    /** Compatibility entry point for the original UUID-only integration. */
    @Deprecated("UI Sync display IDs are opaque strings; use displayId and vehicleId")
    constructor(displayUuid: UUID, id: String) : this(
        displayId = displayUuid.toString().uppercase(),
        vehicleId = id,
    )

    val clusterControlRcsUuid: UUID
        get() = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid

    val uiSyncChannelUuid: UUID
        get() = UI_SYNC_CHANNEL_UUID

    /** Compatibility view for callers that still constrain their display ID to a UUID. */
    @Deprecated("UI Sync display IDs are opaque strings; use displayId")
    val displayUuid: UUID
        get() = try {
            UUID.fromString(displayId)
        } catch (error: IllegalArgumentException) {
            throw CarPlayUiSyncProtocolException("UI Sync displayId is not a UUID", error)
        }

    @Deprecated("Use vehicleId")
    val id: String
        get() = vehicleId

    companion object {
        val UI_SYNC_CHANNEL_UUID: UUID =
            UUID.fromString("7DED4F02-38A6-4CBA-8731-4B9BA40CB044")
    }
}

enum class CarPlayUiSyncMessageType(val wireValue: Int) {
    RESET(0),
    RESET_COMPLETE(1),
    COMMAND(2),
    COMMAND_ACK(3),
    ;

    companion object {
        fun fromWireValue(value: Long): CarPlayUiSyncMessageType =
            entries.firstOrNull { it.wireValue.toLong() == value }
                ?: throw CarPlayUiSyncProtocolException(
                    "Unknown UI Sync message type $value",
                )
    }
}

enum class CarPlayUiSyncProtocolVersion(val wireValue: Int) {
    V1(1),
    V2(2),
    V3(3),
    ;

    companion object {
        fun fromWireValue(value: Long): CarPlayUiSyncProtocolVersion =
            fromWireValueOrNull(value)
                ?: throw CarPlayUiSyncProtocolException(
                    "Unsupported UI Sync protocol version $value",
                )

        fun fromWireValueOrNull(value: Long): CarPlayUiSyncProtocolVersion? =
            entries.firstOrNull { it.wireValue.toLong() == value }

        /** The newest version this implementation can speak. */
        val HIGHEST: CarPlayUiSyncProtocolVersion
            get() = entries.last()

        /**
         * Version field of a Reset. A Reset declares the sender's *maximum* version and the
         * receiver answers with the minimum of both maxima (`umin` at `0x25c7bb970`), so a value
         * newer than any version known here is not an error: it means the peer speaks at least
         * [HIGHEST]. A future iPhone that offers 4 must still get our Reset; rejecting it would
         * leave the phone waiting in state 1 forever, because it has no timer. Zero and negative
         * values are malformed and stay rejected.
         */
        fun fromResetWireValue(value: Long): CarPlayUiSyncProtocolVersion =
            fromWireValueOrNull(value)
                ?: if (value > HIGHEST.wireValue) HIGHEST else fromWireValue(value)

        fun negotiate(
            local: CarPlayUiSyncProtocolVersion,
            remote: CarPlayUiSyncProtocolVersion,
        ): CarPlayUiSyncProtocolVersion =
            if (local.wireValue <= remote.wireValue) local else remote
    }
}

enum class CarPlayUiSyncCommandType(
    val wireValue: Int,
    val diagnosticName: String,
) {
    /** Request-content or dynamic-content transition metadata, never the type-3 layout triple. */
    TRANSITION_START(1, "transitionStart"),
    TRANSITION_DATA(2, "transitionData"),
    /** Exact layoutID / activeComponents / paletteID intent; not a renderer-completion signal. */
    LAYOUT_CHANGE(3, "layoutChange"),
    TRANSITION_END(4, "transitionEnd"),
    REQUEST_LAYOUT(5, "requestLayout"),
    /** Timed regions/NTP form or bounded echo of request-layout data. */
    END_LAYOUT_CHANGE(6, "endLayoutChange"),
    GIVE_FOCUS(7, "giveFocus"),
    REQUEST_FOCUS(8, "requestFocus"),
    METADATA_TRANSFER(9, "metadataTransfer"),
    PALETTE_CHANGE(16, "paletteChange"),
    ALLOW_TRANSITIONS(17, "allowTransitions"),
    TARGET_APPEARANCE_CHANGE(18, "targetAppearanceChange"),
    APPEARANCE_PREFERENCE_CHANGE(19, "appearancePreferenceChange"),
    DRIVE_MODE_CHANGE(20, "driveModeChange"),
    APPEARANCE_CHANGE_START(21, "appearanceChangeStart"),
    APPEARANCE_CHANGE_END(22, "appearanceChangeEnd"),
    UI_CONFIGURATION_CHANGE(23, "uiConfigurationChange"),
    DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE(
        24,
        "driveModeThemeConfigurationOverrideChange",
    ),
    DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE(25, "driveModeDynamicAssignmentChange"),
    ;

    companion object {
        fun fromWireValue(value: Int): CarPlayUiSyncCommandType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Confirmed command direction relative to this accessory implementation. */
enum class CarPlayUiSyncCommandDirection {
    IPHONE_TO_ACCESSORY,
    ACCESSORY_TO_IPHONE,
    BIDIRECTIONAL,
}

/**
 * iOS 27 direction evidence. Keeping this separate from enum ordinals prevents confusing the
 * private Swift-case ordinal table with the stable wire command number.
 */
val CarPlayUiSyncCommandType.confirmedDirection: CarPlayUiSyncCommandDirection
    get() = when (this) {
        CarPlayUiSyncCommandType.TRANSITION_START,
        CarPlayUiSyncCommandType.TRANSITION_DATA,
        CarPlayUiSyncCommandType.LAYOUT_CHANGE,
        CarPlayUiSyncCommandType.TRANSITION_END,
        CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
        CarPlayUiSyncCommandType.METADATA_TRANSFER,
        CarPlayUiSyncCommandType.PALETTE_CHANGE,
        CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
        CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
        CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
        CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
        -> CarPlayUiSyncCommandDirection.IPHONE_TO_ACCESSORY

        CarPlayUiSyncCommandType.REQUEST_LAYOUT,
        CarPlayUiSyncCommandType.REQUEST_FOCUS,
        CarPlayUiSyncCommandType.ALLOW_TRANSITIONS,
        CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE,
        CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE,
        CarPlayUiSyncCommandType.UI_CONFIGURATION_CHANGE,
        CarPlayUiSyncCommandType.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE,
        -> CarPlayUiSyncCommandDirection.ACCESSORY_TO_IPHONE

        CarPlayUiSyncCommandType.GIVE_FOCUS -> CarPlayUiSyncCommandDirection.BIDIRECTIONAL
    }

sealed interface CarPlayUiSyncMessagePayload

/** Typed command payload encoded under the message's wire `data` key. */
data class CarPlayUiSyncCommandEnvelope(
    val type: Int,
    val data: Any? = null,
    val extensions: Map<String, Any> = emptyMap(),
) : CarPlayUiSyncMessagePayload {
    init {
        if (type !in 0..MAXIMUM_COMMAND_TYPE) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync command type must be between 0 and $MAXIMUM_COMMAND_TYPE",
            )
        }
        validateExtensionKeys(extensions, COMMAND_WIRE_KEYS, "command")
    }

    val knownType: CarPlayUiSyncCommandType?
        get() = CarPlayUiSyncCommandType.fromWireValue(type)

    internal fun toWireMap(): Map<String, Any> = linkedMapOf<String, Any>(
        "type" to type,
    ).apply {
        data?.let { put("data", it) }
        putAll(extensions)
    }

    internal companion object {
        const val MAXIMUM_COMMAND_TYPE = 65_535
        val COMMAND_WIRE_KEYS = setOf("type", "data")
    }
}

/**
 * Optional reset-complete payload. The iOS 27 wire shape is
 * `message.data = { data = { config = <DisplayAssetConfig> } }`.
 */
data class CarPlayUiSyncResetCompletePayload(
    val config: Any,
    val extensions: Map<String, Any> = emptyMap(),
    val dataExtensions: Map<String, Any> = emptyMap(),
) : CarPlayUiSyncMessagePayload {
    init {
        validateExtensionKeys(extensions, RESET_COMPLETE_WIRE_KEYS, "resetComplete")
        validateExtensionKeys(dataExtensions, RESET_COMPLETE_DATA_WIRE_KEYS, "resetComplete data")
    }

    internal fun toWireMap(): Map<String, Any> = linkedMapOf<String, Any>(
        "data" to linkedMapOf<String, Any>(
            "config" to config,
        ).apply {
            putAll(dataExtensions)
        },
    ).apply {
        putAll(extensions)
    }

    private companion object {
        val RESET_COMPLETE_WIRE_KEYS = setOf("data")
        val RESET_COMPLETE_DATA_WIRE_KEYS = setOf("config")
    }
}

data class CarPlayUiSyncMessage(
    val type: CarPlayUiSyncMessageType,
    val sessionSequenceNumber: Long,
    val packetSequenceNumber: Long,
    val acknowledgementSequenceNumber: Long,
    val payload: CarPlayUiSyncMessagePayload? = null,
    val noAck: Boolean = false,
    val vehicleId: String? = null,
    val version: CarPlayUiSyncProtocolVersion? = null,
    val extensions: Map<String, Any> = emptyMap(),
) {
    init {
        requireSequence("ssn", sessionSequenceNumber)
        requireSequence("psn", packetSequenceNumber)
        requireSequence("asn", acknowledgementSequenceNumber)
        when (type) {
            CarPlayUiSyncMessageType.RESET -> requireNoPayload()
            CarPlayUiSyncMessageType.RESET_COMPLETE -> {
                if (payload != null && payload !is CarPlayUiSyncResetCompletePayload) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync resetComplete requires a reset-complete payload",
                    )
                }
            }
            CarPlayUiSyncMessageType.COMMAND -> {
                if (payload !is CarPlayUiSyncCommandEnvelope) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync command message requires a command payload",
                    )
                }
            }
            CarPlayUiSyncMessageType.COMMAND_ACK -> requireNoPayload()
        }
        vehicleId?.let {
            validateIdentifier(it, "vehicleId", ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS)
        }
        validateExtensionKeys(extensions, INNER_WIRE_KEYS, "message")
    }

    /** Compatibility view of the former command-only `data` model. */
    @Deprecated("Use payload")
    val data: CarPlayUiSyncCommandEnvelope?
        get() = payload as? CarPlayUiSyncCommandEnvelope

    @Deprecated("Use vehicleId")
    val id: String?
        get() = vehicleId

    internal fun toWireMap(payloadWireValue: Any?): Map<String, Any> =
        linkedMapOf<String, Any>(
            "type" to type.wireValue,
            "ssn" to sessionSequenceNumber,
            "psn" to packetSequenceNumber,
            "asn" to acknowledgementSequenceNumber,
        ).apply {
            payloadWireValue?.let { put("data", it) }
            if (noAck) put("noAck", true)
            vehicleId?.let { put("id", it) }
            version?.let { put("version", it.wireValue) }
            putAll(extensions)
        }

    private fun requireNoPayload() {
        if (payload != null) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync ${type.name.lowercase()} message must not contain a payload",
            )
        }
    }

    private fun requireSequence(name: String, value: Long) {
        if (value < 0) {
            throw CarPlayUiSyncProtocolException("UI Sync '$name' must be non-negative")
        }
    }

    private companion object {
        val INNER_WIRE_KEYS = setOf(
            "type",
            "ssn",
            "psn",
            "asn",
            "data",
            "noAck",
            "id",
            "version",
        )
    }
}

/**
 * How the UI Sync message sits under the OPACK carrier's `data` key.
 *
 * iOS 27 DashBoard chooses this from the sender's *negotiated* version, not from the message's
 * `version` field: before negotiation and for v1/v2 it sends binary-plist Data
 * (`-[DBUISyncChannel sendPayload:forDisplayID:description:]`); after negotiating v3 it sends a
 * nested dictionary (`sendPacket:forDisplayID:description:`). Its self-initiated Reset therefore
 * arrives as plist Data while declaring `version = 3`.
 */
enum class CarPlayUiSyncCarrierRepresentation {
    SERIALIZED_PLIST,
    DICTIONARY,
    ;

    companion object {
        fun forNegotiatedVersion(
            version: CarPlayUiSyncProtocolVersion,
        ): CarPlayUiSyncCarrierRepresentation = when (version) {
            CarPlayUiSyncProtocolVersion.V1,
            CarPlayUiSyncProtocolVersion.V2,
            -> SERIALIZED_PLIST
            CarPlayUiSyncProtocolVersion.V3 -> DICTIONARY
        }
    }
}

data class CarPlayUiSyncCarrier(
    val displayId: String,
    val serializationVersion: CarPlayUiSyncProtocolVersion,
    val message: CarPlayUiSyncMessage,
    val representation: CarPlayUiSyncCarrierRepresentation =
        CarPlayUiSyncCarrierRepresentation.forNegotiatedVersion(serializationVersion),
) {
    init {
        validateIdentifier(displayId, "displayId", ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS)
        if (representation == CarPlayUiSyncCarrierRepresentation.DICTIONARY &&
            serializationVersion != CarPlayUiSyncProtocolVersion.V3
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync dictionary carriers require protocol version 3",
            )
        }
    }

    /** Compatibility entry point for the original UUID-only integration. */
    @Deprecated("UI Sync display IDs are opaque strings; use displayId")
    constructor(
        displayUuid: UUID,
        serializationVersion: CarPlayUiSyncProtocolVersion,
        message: CarPlayUiSyncMessage,
    ) : this(displayUuid.toString().uppercase(), serializationVersion, message)

    @Deprecated("UI Sync display IDs are opaque strings; use displayId")
    val displayUuid: UUID
        get() = try {
            UUID.fromString(displayId)
        } catch (error: IllegalArgumentException) {
            throw CarPlayUiSyncProtocolException("UI Sync displayId is not a UUID", error)
        }
}

/** Resource limits applied before decoded, untrusted presentation data leaves the codec. */
data class CarPlayUiSyncLimits(
    val maximumCarrierBytes: Int = 1024 * 1024,
    val maximumInnerMessageBytes: Int = 512 * 1024,
    val maximumValueBytes: Int = 256 * 1024,
    val maximumStringChars: Int = 8 * 1024,
    val maximumIdentifierChars: Int = 128,
    val maximumCollectionEntries: Int = 1024,
    val maximumNestingDepth: Int = 32,
    val maximumPendingAcknowledgements: Int = 128,
    val maximumRememberedFrames: Int = 128,
    val maximumRememberedFrameBytes: Long = 4L * 1024L * 1024L + 64L,
) {
    init {
        require(maximumCarrierBytes in 1..ABSOLUTE_MAXIMUM_CARRIER_BYTES)
        require(maximumInnerMessageBytes in 1..maximumCarrierBytes)
        require(maximumValueBytes in 1..maximumInnerMessageBytes)
        require(maximumStringChars in 1..maximumValueBytes)
        require(maximumIdentifierChars in 1..minOf(
            maximumStringChars,
            ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS,
        ))
        require(maximumCollectionEntries in 1..ABSOLUTE_MAXIMUM_COLLECTION_ENTRIES)
        require(maximumNestingDepth in 1..ABSOLUTE_MAXIMUM_NESTING_DEPTH)
        require(maximumPendingAcknowledgements in 1..ABSOLUTE_MAXIMUM_PENDING_ACKNOWLEDGEMENTS)
        require(maximumRememberedFrames in 1..ABSOLUTE_MAXIMUM_REMEMBERED_FRAMES)
        require(maximumRememberedFrameBytes in
            (maximumCarrierBytes.toLong() + SHA256_BYTES)..ABSOLUTE_MAXIMUM_REMEMBERED_FRAME_BYTES)
    }

    /** OPACK bounds for the DBUISyncChannel outer carrier and any nested v3 dictionaries. */
    internal fun carrierOpackLimits(): OpackLimits = OpackLimits(
        maxEncodedBytes = maximumCarrierBytes,
        maxValueBytes = maximumInnerMessageBytes,
        maxCollectionEntries = maximumCollectionEntries,
        maxObjectTableEntries = maximumCollectionEntries * 4 + 32,
        maxDepth = maximumNestingDepth,
    )

    internal fun innerDecodeLimits(): BplistDecodeLimits = BplistDecodeLimits(
        maxInputBytes = maximumInnerMessageBytes,
        maxObjects = maximumCollectionEntries * 4 + 32,
        maxObjectBytes = maximumInnerMessageBytes,
        maxNestingDepth = maximumNestingDepth,
        maxContainerItems = maximumCollectionEntries,
        maxDataBytes = maximumValueBytes,
        maxStringBytes = maximumStringChars * 4,
        maxDecodedBytes = maximumInnerMessageBytes.toLong() * 2L,
    )

    internal fun payloadDecodeLimits(): BplistDecodeLimits = BplistDecodeLimits(
        maxInputBytes = maximumValueBytes,
        maxObjects = maximumCollectionEntries * 4 + 32,
        maxObjectBytes = maximumValueBytes,
        maxNestingDepth = maximumNestingDepth,
        maxContainerItems = maximumCollectionEntries,
        maxDataBytes = maximumValueBytes,
        maxStringBytes = maximumStringChars * 4,
        maxDecodedBytes = maximumValueBytes.toLong() * 2L,
    )

    private companion object {
        const val ABSOLUTE_MAXIMUM_CARRIER_BYTES = 4 * 1024 * 1024
        const val ABSOLUTE_MAXIMUM_COLLECTION_ENTRIES = 16 * 1024
        const val ABSOLUTE_MAXIMUM_NESTING_DEPTH = 64
        const val ABSOLUTE_MAXIMUM_PENDING_ACKNOWLEDGEMENTS = 4096
        const val ABSOLUTE_MAXIMUM_REMEMBERED_FRAMES = 4096
        const val ABSOLUTE_MAXIMUM_REMEMBERED_FRAME_BYTES = 64L * 1024L * 1024L
        const val SHA256_BYTES = 32L
    }
}

/**
 * OPACK carrier for the UI Sync protocol.
 *
 * iOS 27 `DBUISyncChannel` encodes `{displayUUID, data}` with `OPACKEncoderCreateData` and decodes
 * received bodies with `OPACKDecodeData`; the ClusterControl RCS channel is created with
 * `SendMessageAsIs`, so the APTransport `comm` body is exactly these OPACK bytes, with no
 * `{params:{data}}` wrapper. Under `data`, the serialized-plist representation carries the
 * message (and every non-null payload) as binary-plist Data; the dictionary representation (v3)
 * nests both levels as OPACK dictionaries. `displayUUID` is a historical name and its value must
 * be treated as opaque text.
 */
object CarPlayUiSyncCodec {
    fun encode(
        carrier: CarPlayUiSyncCarrier,
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    ): ByteArray {
        validateIdentifier(carrier.displayId, "displayId", limits.maximumIdentifierChars)
        carrier.message.vehicleId?.let {
            validateIdentifier(it, "vehicleId", limits.maximumIdentifierChars)
        }
        if (carrier.message.version != null &&
            carrier.message.version != carrier.serializationVersion
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync message version ${carrier.message.version.wireValue} does not match " +
                    "carrier version ${carrier.serializationVersion.wireValue}",
            )
        }

        val copier = BoundedValueCopier(limits)
        val payloadMap = carrier.message.payload?.let { payload ->
            @Suppress("UNCHECKED_CAST")
            copier.copy(payload.toWireMap()) as Map<String, Any>
        }
        val payloadWireValue = payloadMap?.let {
            encodePayload(it, carrier.representation, limits)
        }
        val safeExtensions = copier.copyExtensions(
            carrier.message.extensions,
            MESSAGE_WIRE_KEYS,
            "message",
        )
        val safeMessage = carrier.message.copy(extensions = safeExtensions)
        val inner = safeMessage.toWireMap(payloadWireValue)
        val outerData: Any = when (carrier.representation) {
            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST ->
                BplistCodec.encode(inner).also { encoded ->
                    if (encoded.size > limits.maximumInnerMessageBytes) {
                        throw CarPlayUiSyncProtocolException(
                            "UI Sync inner message exceeds ${limits.maximumInnerMessageBytes} bytes",
                        )
                    }
                }
            CarPlayUiSyncCarrierRepresentation.DICTIONARY -> inner
        }
        val encoded = try {
            OpackCodec.encode(
                linkedMapOf(
                    "displayUUID" to carrier.displayId,
                    "data" to outerData,
                ),
                limits.carrierOpackLimits(),
            )
        } catch (error: CarPlayUiSyncProtocolException) {
            throw error
        } catch (error: Exception) {
            throw CarPlayUiSyncProtocolException("UI Sync carrier cannot be OPACK-encoded", error)
        }
        if (encoded.size > limits.maximumCarrierBytes) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync carrier exceeds ${limits.maximumCarrierBytes} bytes",
            )
        }
        return encoded
    }

    fun decode(
        bytes: ByteArray,
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
        expectedVersion: CarPlayUiSyncProtocolVersion? = null,
    ): CarPlayUiSyncCarrier {
        val outer = decodeOuter(bytes, limits)
        val displayId = displayId(outer, limits)
        val wireMessage: Map<String, Any?>
        val representation: CarPlayUiSyncCarrierRepresentation
        when (val data = outer["data"]) {
            is ByteArray -> {
                if (data.size > limits.maximumInnerMessageBytes) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync inner message exceeds ${limits.maximumInnerMessageBytes} bytes",
                    )
                }
                wireMessage = decodePlistMap(
                    bytes = data,
                    limits = limits.innerDecodeLimits(),
                    label = "UI Sync serialized inner message",
                )
                representation = CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST
            }
            is Map<*, *> -> {
                wireMessage = stringMap(data, "UI Sync v3 inner message")
                requireNoNullValues(wireMessage, "UI Sync v3 inner message")
                representation = CarPlayUiSyncCarrierRepresentation.DICTIONARY
            }
            else -> throw CarPlayUiSyncProtocolException(
                "UI Sync carrier 'data' must be a dictionary or serialized binary plist",
            )
        }

        val declaredVersion = wireMessage["version"]?.let {
            decodeVersion(integer(wireMessage, "version"), isReset = isResetMessage(wireMessage))
        }
        // A dictionary is only ever sent after negotiating v3. Serialized plist Data is used before
        // negotiation (including the phone's own Reset, which declares version 3) and for v1/v2,
        // so its version comes from the message or from the caller's negotiated version.
        val carrierVersion = when (representation) {
            CarPlayUiSyncCarrierRepresentation.DICTIONARY -> CarPlayUiSyncProtocolVersion.V3
            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST ->
                declaredVersion ?: expectedVersion
                    ?: throw CarPlayUiSyncProtocolException(
                        "UI Sync serialized carrier without a message version is ambiguous",
                    )
        }
        if (expectedVersion != null && carrierVersion != expectedVersion) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync carrier version ${carrierVersion.wireValue} does not match expected " +
                    expectedVersion.wireValue,
            )
        }
        if (declaredVersion != null && declaredVersion != carrierVersion) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync message version ${declaredVersion.wireValue} does not match carrier " +
                    carrierVersion.wireValue,
            )
        }

        val message = decodeMessage(wireMessage, representation, limits)
        return CarPlayUiSyncCarrier(displayId, carrierVersion, message, representation)
    }

    /**
     * Bounded outer-envelope inspection for a future per-display router. The exact opaque value is
     * returned without attempting to parse it as a UUID or decode the nested UI Sync message.
     */
    fun peekDisplayId(
        bytes: ByteArray,
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    ): String = displayId(decodeOuter(bytes, limits), limits)

    private fun decodeMessage(
        values: Map<String, Any?>,
        representation: CarPlayUiSyncCarrierRepresentation,
        limits: CarPlayUiSyncLimits,
    ): CarPlayUiSyncMessage {
        if (!values.keys.containsAll(REQUIRED_INNER_KEYS)) {
            val missing = REQUIRED_INNER_KEYS - values.keys
            throw CarPlayUiSyncProtocolException(
                "UI Sync inner message is missing ${missing.sorted().joinToString()}",
            )
        }
        val type = CarPlayUiSyncMessageType.fromWireValue(integer(values, "type"))
        val copier = BoundedValueCopier(limits)
        val payload = values["data"]?.let { raw ->
            val payloadMap = decodePayloadMap(raw, representation, limits)
            when (type) {
                CarPlayUiSyncMessageType.COMMAND -> decodeCommandEnvelope(payloadMap, copier)
                CarPlayUiSyncMessageType.RESET_COMPLETE ->
                    decodeResetCompletePayload(payloadMap, copier)
                CarPlayUiSyncMessageType.RESET,
                CarPlayUiSyncMessageType.COMMAND_ACK,
                -> throw CarPlayUiSyncProtocolException(
                    "UI Sync ${type.name.lowercase()} message must not contain a payload",
                )
            }
        }
        if (type == CarPlayUiSyncMessageType.COMMAND && payload == null) {
            throw CarPlayUiSyncProtocolException("UI Sync command message requires a payload")
        }
        val vehicleId = values["id"]?.let { rawId ->
            rawId as? String
                ?: throw CarPlayUiSyncProtocolException("UI Sync message 'id' must be a string")
        }
        vehicleId?.let {
            validateIdentifier(it, "vehicleId", limits.maximumIdentifierChars)
        }
        val extensions = copier.copyUnknownEntries(values, MESSAGE_WIRE_KEYS, "message")
        return CarPlayUiSyncMessage(
            type = type,
            sessionSequenceNumber = integer(values, "ssn"),
            packetSequenceNumber = integer(values, "psn"),
            acknowledgementSequenceNumber = integer(values, "asn"),
            payload = payload,
            noAck = values["noAck"]?.let { noAck ->
                noAck as? Boolean
                    ?: throw CarPlayUiSyncProtocolException(
                        "UI Sync message 'noAck' must be a boolean",
                    )
            } ?: false,
            vehicleId = vehicleId,
            version = values["version"]?.let {
                decodeVersion(
                    integer(values, "version"),
                    isReset = type == CarPlayUiSyncMessageType.RESET,
                )
            },
            extensions = extensions,
        )
    }

    /** Only a Reset tolerates a newer version (see `fromResetWireValue`). */
    private fun decodeVersion(value: Long, isReset: Boolean): CarPlayUiSyncProtocolVersion =
        if (isReset) {
            CarPlayUiSyncProtocolVersion.fromResetWireValue(value)
        } else {
            CarPlayUiSyncProtocolVersion.fromWireValue(value)
        }

    private fun isResetMessage(values: Map<String, Any?>): Boolean =
        when (val type = values["type"]) {
            is Byte, is Short, is Int, is Long ->
                (type as Number).toLong() == CarPlayUiSyncMessageType.RESET.wireValue.toLong()
            else -> false
        }

    private fun decodeCommandEnvelope(
        values: Map<String, Any?>,
        copier: BoundedValueCopier,
    ): CarPlayUiSyncCommandEnvelope {
        if ("type" !in values) {
            throw CarPlayUiSyncProtocolException("UI Sync command payload requires 'type'")
        }
        val type = integer(values, "type")
        if (type !in 0..CarPlayUiSyncCommandEnvelope.MAXIMUM_COMMAND_TYPE.toLong()) {
            throw CarPlayUiSyncProtocolException("UI Sync command type $type is out of range")
        }
        return CarPlayUiSyncCommandEnvelope(
            type = type.toInt(),
            data = values["data"]?.let(copier::copy),
            extensions = copier.copyUnknownEntries(
                values,
                CarPlayUiSyncCommandEnvelope.COMMAND_WIRE_KEYS,
                "command",
            ),
        )
    }

    private fun decodeResetCompletePayload(
        values: Map<String, Any?>,
        copier: BoundedValueCopier,
    ): CarPlayUiSyncResetCompletePayload {
        val data = values["data"]?.let {
            stringMap(it, "UI Sync resetComplete payload data")
        } ?: throw CarPlayUiSyncProtocolException(
            "UI Sync resetComplete payload requires 'data'",
        )
        val config = data["config"]
            ?: throw CarPlayUiSyncProtocolException(
                "UI Sync resetComplete payload data requires 'config'",
            )
        return CarPlayUiSyncResetCompletePayload(
            config = copier.copy(config),
            extensions = copier.copyUnknownEntries(
                values,
                RESET_COMPLETE_PAYLOAD_KEYS,
                "resetComplete",
            ),
            dataExtensions = copier.copyUnknownEntries(
                data,
                RESET_COMPLETE_DATA_KEYS,
                "resetComplete data",
            ),
        )
    }

    private fun encodePayload(
        payload: Map<String, Any>,
        representation: CarPlayUiSyncCarrierRepresentation,
        limits: CarPlayUiSyncLimits,
    ): Any = when (representation) {
        CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
        -> BplistCodec.encode(payload).also { encoded ->
            if (encoded.size > limits.maximumValueBytes) {
                throw CarPlayUiSyncProtocolException(
                    "UI Sync serialized payload exceeds ${limits.maximumValueBytes} bytes",
                )
            }
        }
        CarPlayUiSyncCarrierRepresentation.DICTIONARY -> payload
    }

    private fun decodePayloadMap(
        value: Any,
        representation: CarPlayUiSyncCarrierRepresentation,
        limits: CarPlayUiSyncLimits,
    ): Map<String, Any?> = when (representation) {
        CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST -> {
            val bytes = value as? ByteArray
                ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync serialized message payload must be a serialized binary plist",
                )
            if (bytes.size > limits.maximumValueBytes) {
                throw CarPlayUiSyncProtocolException(
                    "UI Sync serialized payload exceeds ${limits.maximumValueBytes} bytes",
                )
            }
            decodePlistMap(bytes, limits.payloadDecodeLimits(), "UI Sync serialized payload")
        }
        CarPlayUiSyncCarrierRepresentation.DICTIONARY -> {
            if (value is ByteArray) {
                throw CarPlayUiSyncProtocolException(
                    "UI Sync v3 message payload must be a dictionary",
                )
            }
            stringMap(value, "UI Sync v3 payload").also {
                requireNoNullValues(it, "UI Sync v3 payload")
            }
        }
    }

    private fun decodeOuter(
        bytes: ByteArray,
        limits: CarPlayUiSyncLimits,
    ): Map<String, Any?> {
        if (bytes.size > limits.maximumCarrierBytes) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync carrier exceeds ${limits.maximumCarrierBytes} bytes",
            )
        }
        // DBUISyncChannel builds exactly {displayUUID, data}; key order is not significant.
        val outer = decodeOpackMap(bytes, limits.carrierOpackLimits(), "UI Sync carrier")
        requireExactKeys(outer, OUTER_KEYS, "UI Sync carrier")
        return outer
    }

    private fun displayId(
        outer: Map<String, Any?>,
        limits: CarPlayUiSyncLimits,
    ): String {
        val displayId = outer["displayUUID"] as? String
            ?: throw CarPlayUiSyncProtocolException(
                "UI Sync 'displayUUID' must be a string",
            )
        validateIdentifier(displayId, "displayId", limits.maximumIdentifierChars)
        return displayId
    }

    private val OUTER_KEYS = setOf("displayUUID", "data")
    private val REQUIRED_INNER_KEYS = setOf("type", "ssn", "psn", "asn")
    private val MESSAGE_WIRE_KEYS = REQUIRED_INNER_KEYS + setOf("data", "noAck", "id", "version")
    private val RESET_COMPLETE_PAYLOAD_KEYS = setOf("data")
    private val RESET_COMPLETE_DATA_KEYS = setOf("config")
}

internal fun copyCarPlayUiSyncBoundedValue(
    value: Any,
    limits: CarPlayUiSyncLimits,
): Any = BoundedValueCopier(limits).copy(value)

/**
 * Exact UI Sync wire-value equality: dictionaries need the same key set and pairwise-equal values,
 * arrays the same order, data the same bytes. Integer widths are not carried by OPACK/plist, so
 * Byte/Short/Int/Long/in-range BigInteger compare by value; every other type (Boolean, String,
 * Float, Double) must match exactly. A Boolean never equals an integer. Bounded by [maximumDepth].
 */
internal fun carPlayUiSyncWireValueEquals(
    left: Any?,
    right: Any?,
    maximumDepth: Int = 64,
): Boolean {
    if (maximumDepth < 0) return false
    val leftInteger = wireIntegerValue(left)
    val rightInteger = wireIntegerValue(right)
    if (leftInteger != null || rightInteger != null) return leftInteger == rightInteger
    return when {
        left == null || right == null -> left == null && right == null
        left is ByteArray || right is ByteArray ->
            left is ByteArray && right is ByteArray && left.contentEquals(right)
        left is Map<*, *> || right is Map<*, *> ->
            left is Map<*, *> && right is Map<*, *> && left.size == right.size &&
                left.keys.all { it is String } && left.keys == right.keys &&
                left.entries.all { (key, value) ->
                    carPlayUiSyncWireValueEquals(value, right[key], maximumDepth - 1)
                }
        left is List<*> || right is List<*> ->
            left is List<*> && right is List<*> && left.size == right.size &&
                left.indices.all { index ->
                    carPlayUiSyncWireValueEquals(left[index], right[index], maximumDepth - 1)
                }
        left is Boolean || left is String || left is Float || left is Double ->
            left.javaClass == right.javaClass && left == right
        else -> false
    }
}

private fun wireIntegerValue(value: Any?): BigInteger? = when (value) {
    is Byte -> BigInteger.valueOf(value.toLong())
    is Short -> BigInteger.valueOf(value.toLong())
    is Int -> BigInteger.valueOf(value.toLong())
    is Long -> BigInteger.valueOf(value)
    is BigInteger -> value
    else -> null
}

private fun CarPlayUiSyncMessagePayload.toWireMap(): Map<String, Any> = when (this) {
    is CarPlayUiSyncCommandEnvelope -> toWireMap()
    is CarPlayUiSyncResetCompletePayload -> toWireMap()
}

private class BoundedValueCopier(
    private val limits: CarPlayUiSyncLimits,
) {
    private var entries = 0
    private var bytes = 0L

    fun copy(value: Any): Any = copy(value, depth = 0)

    fun copyExtensions(
        extensions: Map<String, Any>,
        reservedKeys: Set<String>,
        label: String,
    ): Map<String, Any> {
        validateExtensionKeys(extensions, reservedKeys, label)
        @Suppress("UNCHECKED_CAST")
        return copy(extensions) as Map<String, Any>
    }

    fun copyUnknownEntries(
        values: Map<String, Any?>,
        knownKeys: Set<String>,
        label: String,
    ): Map<String, Any> {
        val unknown = linkedMapOf<String, Any>()
        values.forEach { (key, value) ->
            if (key !in knownKeys) {
                unknown[key] = value ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync $label extension must not be null",
                )
            }
        }
        return copyExtensions(unknown, knownKeys, label)
    }

    private fun copy(value: Any, depth: Int): Any {
        if (depth > limits.maximumNestingDepth) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync value exceeds nesting depth ${limits.maximumNestingDepth}",
            )
        }
        consumeEntry()
        return when (value) {
            is Boolean,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            is BigInteger,
            -> value
            is String -> {
                if (value.length > limits.maximumStringChars) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync string exceeds ${limits.maximumStringChars} characters",
                    )
                }
                consumeBytes(value.toByteArray(Charsets.UTF_8).size)
                value
            }
            is ByteArray -> {
                if (value.size > limits.maximumValueBytes) {
                    throw CarPlayUiSyncProtocolException(
                        "UI Sync data exceeds ${limits.maximumValueBytes} bytes",
                    )
                }
                consumeBytes(value.size)
                value.copyOf()
            }
            is List<*> -> value.map { item ->
                item ?: throw CarPlayUiSyncProtocolException(
                    "UI Sync arrays must not contain null",
                )
                copy(item, depth + 1)
            }
            is Map<*, *> -> linkedMapOf<String, Any>().apply {
                value.forEach { (rawKey, rawValue) ->
                    val key = rawKey as? String
                        ?: throw CarPlayUiSyncProtocolException(
                            "UI Sync dictionary keys must be strings",
                        )
                    validateValueKey(key, limits.maximumStringChars)
                    val item = rawValue
                        ?: throw CarPlayUiSyncProtocolException(
                            "UI Sync dictionaries must not contain null",
                        )
                    consumeBytes(key.toByteArray(Charsets.UTF_8).size)
                    put(key, copy(item, depth + 1))
                }
            }
            else -> throw CarPlayUiSyncProtocolException(
                "Unsupported UI Sync value ${value::class.java.simpleName}",
            )
        }
    }

    private fun consumeEntry() {
        entries++
        if (entries > limits.maximumCollectionEntries) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync value exceeds ${limits.maximumCollectionEntries} values",
            )
        }
    }

    private fun consumeBytes(count: Int) {
        bytes += count.toLong()
        if (bytes > limits.maximumValueBytes) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync value exceeds ${limits.maximumValueBytes} decoded bytes",
            )
        }
    }
}

private fun decodePlistMap(
    bytes: ByteArray,
    limits: BplistDecodeLimits,
    label: String,
): Map<String, Any?> = try {
    stringMap(BplistCodec.decode(bytes, limits), label)
} catch (error: CarPlayUiSyncProtocolException) {
    throw error
} catch (error: Exception) {
    throw CarPlayUiSyncProtocolException("$label is not a valid binary plist", error)
}

private fun decodeOpackMap(
    bytes: ByteArray,
    limits: OpackLimits,
    label: String,
): Map<String, Any?> = try {
    stringMap(OpackCodec.decode(bytes, limits), label)
} catch (error: CarPlayUiSyncProtocolException) {
    throw error
} catch (error: Exception) {
    throw CarPlayUiSyncProtocolException("$label is not a valid OPACK value", error)
}

private fun requireNoNullValues(values: Map<String, Any?>, label: String) {
    if (values.values.any { it == null }) {
        throw CarPlayUiSyncProtocolException("$label must not contain null values")
    }
}

private fun stringMap(value: Any?, label: String): Map<String, Any?> {
    val map = value as? Map<*, *>
        ?: throw CarPlayUiSyncProtocolException("$label must be a dictionary")
    return linkedMapOf<String, Any?>().apply {
        map.forEach { (key, item) ->
            val stringKey = key as? String
                ?: throw CarPlayUiSyncProtocolException("$label keys must be strings")
            put(stringKey, item)
        }
    }
}

private fun integer(values: Map<String, Any?>, key: String): Long {
    val value = values[key]
        ?: throw CarPlayUiSyncProtocolException("UI Sync message '$key' is required")
    return when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw CarPlayUiSyncProtocolException(
            "UI Sync message '$key' must be an integer",
        )
    }
}

private fun requireExactKeys(
    values: Map<String, Any?>,
    expected: Set<String>,
    label: String,
) {
    if (values.keys != expected) {
        val missing = expected - values.keys
        val unknown = values.keys - expected
        // Missing names come from our fixed schema; unknown peer keys are only counted so this
        // message can never carry peer-controlled text.
        val details = buildList {
            if (missing.isNotEmpty()) add("missing ${missing.sorted().joinToString()}")
            if (unknown.isNotEmpty()) add("${unknown.size} unknown")
        }.joinToString("; ")
        throw CarPlayUiSyncProtocolException("$label has invalid keys: $details")
    }
}

private fun validateExtensionKeys(
    extensions: Map<String, Any>,
    reservedKeys: Set<String>,
    label: String,
) {
    val reserved = extensions.keys.intersect(reservedKeys)
    if (reserved.isNotEmpty()) {
        throw CarPlayUiSyncProtocolException(
            "UI Sync $label extensions use reserved keys ${reserved.sorted().joinToString()}",
        )
    }
    extensions.keys.forEach { validateValueKey(it, ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS) }
}

private fun validateValueKey(key: String, maximumChars: Int) {
    if (key.isEmpty() || key.length > maximumChars || key.any(Char::isISOControl)) {
        throw CarPlayUiSyncProtocolException(
            "UI Sync dictionary key must be 1..$maximumChars printable characters",
        )
    }
}

private fun validateIdentifier(identifier: String, label: String, maximumChars: Int) {
    if (identifier.isBlank() ||
        identifier.length > maximumChars ||
        identifier.any(Char::isISOControl)
    ) {
        throw CarPlayUiSyncProtocolException(
            "UI Sync $label must be 1..$maximumChars printable characters",
        )
    }
}

private const val ABSOLUTE_MAXIMUM_IDENTIFIER_CHARS = 8 * 1024
