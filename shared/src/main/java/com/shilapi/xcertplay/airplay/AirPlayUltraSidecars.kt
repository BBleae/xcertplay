package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.caf.CafVehicleStateProtocolInfo
import com.shilapi.xcertplay.airplay.rcs.caf.CafUInt64
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/** A validated `vehicleStateProtocolInfo.pluginConfigs` entry. */
data class AirPlayVehicleStateProtocolPlugin(
    val pluginId: Long,
    val fields: Map<String, Any?> = emptyMap(),
) {
    private val wireFields = immutableStringMap(fields)

    init {
        fields[PLUGIN_ID]?.let { supplied ->
            require(wireUInt64(supplied, "vehicleStateProtocol pluginID") == pluginId) {
                "vehicleStateProtocol pluginID contradicts the typed plugin ID"
            }
        }
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        PLUGIN_ID to CafUInt64.plistNumber(pluginId),
    ).apply {
        wireFields.forEach { (name, value) ->
            if (name != PLUGIN_ID) put(name, immutableWireValue(value))
        }
    }

    companion object {
        private const val PLUGIN_ID = "pluginID"

        fun fromWireMap(values: Map<String, Any?>): AirPlayVehicleStateProtocolPlugin {
            val pluginId = values[PLUGIN_ID]
                ?: throw IllegalArgumentException("vehicleStateProtocol pluginID is required")
            return AirPlayVehicleStateProtocolPlugin(
                pluginId = wireUInt64(pluginId, "vehicleStateProtocol pluginID"),
                fields = values,
            )
        }
    }
}

/**
 * Typed, immutable Vehicle State Protocol `/info` sidecar.
 *
 * A non-empty plugin list is intentional. Merely knowing the sidecar key, or emitting an empty
 * dictionary, is not evidence that a CAF implementation is usable.
 */
data class AirPlayVehicleStateProtocolInfo(
    val protocolVersion: String = SUPPORTED_PROTOCOL_VERSION,
    val pluginConfigs: List<AirPlayVehicleStateProtocolPlugin>,
    val pluginMapping: Map<String, Long> = emptyMap(),
) {
    private val plugins = pluginConfigs.toList()
    private val mapping = LinkedHashMap(pluginMapping)

    init {
        require(protocolVersion == SUPPORTED_PROTOCOL_VERSION) {
            "vehicleStateProtocol protocolVersion must be '$SUPPORTED_PROTOCOL_VERSION'"
        }
        require(plugins.isNotEmpty()) {
            "vehicleStateProtocol pluginConfigs must contain at least one plugin"
        }
        val ids = plugins.mapTo(linkedSetOf(), AirPlayVehicleStateProtocolPlugin::pluginId)
        require(ids.size == plugins.size) { "vehicleStateProtocol pluginIDs must be unique" }
        mapping.forEach { (name, pluginId) ->
            require(name.isNotBlank()) { "vehicleStateProtocol pluginMapping contains a blank name" }
            require(pluginId in ids) {
                "vehicleStateProtocol pluginMapping '$name' refers to unknown pluginID $pluginId"
            }
        }
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf(
        "protocolVersion" to protocolVersion,
        "pluginCount" to plugins.size,
        "pluginConfigs" to plugins.map(AirPlayVehicleStateProtocolPlugin::toWireMap),
        "pluginMapping" to mapping.entries.associateTo(linkedMapOf()) { (name, pluginId) ->
            name to CafUInt64.plistNumber(pluginId)
        },
    )

    companion object {
        private const val SUPPORTED_PROTOCOL_VERSION = "1.0"

        /** Converts WP-C's validated CAF metadata without retaining its mutable runtime. */
        fun from(info: CafVehicleStateProtocolInfo): AirPlayVehicleStateProtocolInfo =
            fromWireMap(info.asWireMap())

        /** Adapter seam for a validated wire sidecar supplied by another CAF implementation. */
        fun fromWireMap(values: Map<String, Any?>): AirPlayVehicleStateProtocolInfo {
            val version = values["protocolVersion"] as? String
                ?: throw IllegalArgumentException("vehicleStateProtocol protocolVersion is required")
            val rawPlugins = values["pluginConfigs"] as? List<*>
                ?: throw IllegalArgumentException("vehicleStateProtocol pluginConfigs is required")
            val declaredCount = wireInteger(
                values["pluginCount"] ?: rawPlugins.size,
                "vehicleStateProtocol pluginCount",
            )
            require(declaredCount == rawPlugins.size.toLong()) {
                "vehicleStateProtocol pluginCount does not match pluginConfigs"
            }
            val plugins = rawPlugins.mapIndexed { index, value ->
                val map = value as? Map<*, *>
                    ?: throw IllegalArgumentException(
                        "vehicleStateProtocol pluginConfigs[$index] must be a dictionary",
                    )
                AirPlayVehicleStateProtocolPlugin.fromWireMap(stringMap(map, "pluginConfigs[$index]"))
            }
            val rawMapping = values["pluginMapping"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val mapping = linkedMapOf<String, Long>()
            rawMapping.forEach { (name, pluginId) ->
                val key = name as? String
                    ?: throw IllegalArgumentException("vehicleStateProtocol pluginMapping keys must be strings")
                mapping[key] = wireUInt64(
                    pluginId,
                    "vehicleStateProtocol pluginMapping '$key'",
                )
            }
            return AirPlayVehicleStateProtocolInfo(version, plugins, mapping)
        }
    }
}

/**
 * UI Sync `/info` data.
 *
 * [declaredDisplayIds] is local provenance attached only by the atomic provisioning path. It is
 * deliberately not serialized into [toWireMap]: no display-list key has been confirmed for the
 * otherwise opaque Apple sidecar. A caller-created or legacy sidecar therefore has an empty
 * declaration and can be used for diagnostics, but it cannot by itself make UI Sync READY.
 */
class AirPlayUiSyncInfo private constructor(
    values: Map<String, Any?>,
    declaredDisplayIds: Collection<String>,
    internal val provisioningIdentity: Any?,
    internal val provisioningSchemaFingerprint: UiSyncSchemaFingerprint?,
    internal val provisioningBindingFingerprint: UiSyncBindingFingerprint?,
) {
    constructor(values: Map<String, Any?> = emptyMap()) : this(
        values,
        emptyList(),
        null,
        null,
        null,
    )

    private val wireValues = UiSyncInfoValueCopier().copyMap(values)

    val declaredDisplayIds: Set<String> = boundedUiSyncDisplayIds(
        declaredDisplayIds,
        "uiSyncInfo display declarations",
    )

    val values: Map<String, Any?>
        get() = UiSyncInfoValueCopier().copyMap(wireValues)

    fun toWireMap(): Map<String, Any?> = UiSyncInfoValueCopier().copyMap(wireValues)

    internal fun withProvisionedDisplays(
        displayIds: Collection<String>,
        identity: Any,
        schemaFingerprint: UiSyncSchemaFingerprint,
        bindingFingerprint: UiSyncBindingFingerprint,
    ): AirPlayUiSyncInfo = AirPlayUiSyncInfo(
        wireValues,
        displayIds,
        identity,
        schemaFingerprint,
        bindingFingerprint,
    )
}

internal const val MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS = 64
private const val MAXIMUM_UI_SYNC_DISPLAY_IDENTIFIER_CHARS = 8 * 1024

internal fun boundedUiSyncDisplayIds(
    values: Collection<String>,
    label: String,
): Set<String> {
    val result = LinkedHashSet<String>()
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(result.size < MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS) {
            "$label exceeds $MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS entries"
        }
        val value = iterator.next()
        require(
            value.isNotBlank() &&
                value.length <= MAXIMUM_UI_SYNC_DISPLAY_IDENTIFIER_CHARS &&
                value.none(Char::isISOControl),
        ) {
            "$label must contain only non-blank printable identifiers of at most " +
                "$MAXIMUM_UI_SYNC_DISPLAY_IDENTIFIER_CHARS characters"
        }
        require(result.add(value)) { "$label contains duplicate display ID '$value'" }
    }
    return Collections.unmodifiableSet(result)
}

/** Bounded defensive copy for the otherwise opaque UI Sync sidecar. */
private class UiSyncInfoValueCopier {
    private val active = IdentityHashMap<Any, Unit>()
    private var nodes = 0
    private var entries = 0
    private var stringChars = 0L
    private var dataBytes = 0L

    fun copyMap(values: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return copy(values, depth = 0) as Map<String, Any?>
    }

    private fun copy(value: Any?, depth: Int): Any {
        require(value != null) { "uiSyncInfo values must not be null" }
        require(depth <= MAXIMUM_DEPTH) {
            "uiSyncInfo exceeds nesting depth $MAXIMUM_DEPTH"
        }
        nodes++
        require(nodes <= MAXIMUM_NODES) { "uiSyncInfo exceeds $MAXIMUM_NODES nodes" }
        return when (value) {
            is Boolean, is Byte, is Short, is Int, is Long -> value
            is Float -> value.also {
                require(it.isFinite()) { "uiSyncInfo floating-point values must be finite" }
            }
            is Double -> value.also {
                require(it.isFinite()) { "uiSyncInfo floating-point values must be finite" }
            }
            is BigInteger -> value.also {
                require(it.signum() >= 0 && it.bitLength() <= 64) {
                    "uiSyncInfo integers must fit an unsigned 64-bit property-list integer"
                }
            }
            is String -> value.also(::consumeString)
            is ByteArray -> value.copyOf().also { consumeData(it.size) }
            is List<*> -> copyComposite(value) {
                consumeEntries(value.size)
                value.map { copy(it, depth + 1) }
            }
            is Map<*, *> -> copyComposite(value) {
                consumeEntries(value.size)
                value.entries.associateTo(linkedMapOf()) { (rawKey, item) ->
                    val key = rawKey as? String
                        ?: throw IllegalArgumentException("uiSyncInfo keys must be strings")
                    consumeString(key)
                    key to copy(item, depth + 1)
                }
            }
            else -> throw IllegalArgumentException(
                "Unsupported uiSyncInfo value ${value::class.java.simpleName}",
            )
        }
    }

    private inline fun copyComposite(source: Any, create: () -> Any): Any {
        require(active.put(source, Unit) == null) { "uiSyncInfo contains a cycle" }
        return try {
            create()
        } finally {
            active.remove(source)
        }
    }

    private fun consumeEntries(count: Int) {
        entries += count
        require(entries <= MAXIMUM_ENTRIES) {
            "uiSyncInfo exceeds $MAXIMUM_ENTRIES collection entries"
        }
    }

    private fun consumeString(value: String) {
        stringChars += value.length.toLong()
        require(stringChars <= MAXIMUM_STRING_CHARS) {
            "uiSyncInfo exceeds $MAXIMUM_STRING_CHARS string characters"
        }
    }

    private fun consumeData(count: Int) {
        dataBytes += count.toLong()
        require(dataBytes <= MAXIMUM_DATA_BYTES) {
            "uiSyncInfo exceeds $MAXIMUM_DATA_BYTES data bytes"
        }
    }

    private companion object {
        const val MAXIMUM_DEPTH = 32
        const val MAXIMUM_NODES = 4_096
        const val MAXIMUM_ENTRIES = 4_096
        const val MAXIMUM_STRING_CHARS = 64L * 1_024L
        const val MAXIMUM_DATA_BYTES = 1024L * 1_024L
    }
}

/**
 * Typed, immutable `sessionManagementInfo` sidecar for the built-in stop-session command.
 *
 * iOS advertises the command as a non-zero integer and limits `disconnectReason` to the values
 * listed in [stopSessionReasons]. Keeping the list typed prevents metadata-only advertisement of
 * command shapes the session cannot validate and consume.
 */
class AirPlaySessionManagementInfo(
    val stopSession: Long,
    stopSessionReasons: Collection<Long>,
) {
    val stopSessionReasons: Set<Long> = Collections.unmodifiableSet(
        LinkedHashSet(stopSessionReasons),
    )

    init {
        require(stopSession != 0L) { "sessionManagement stopSession must be a non-zero integer" }
        require(this.stopSessionReasons.isNotEmpty()) {
            "sessionManagement stopSessionReasons must not be empty"
        }
        require(this.stopSessionReasons.all { it in SUPPORTED_STOP_SESSION_REASONS }) {
            "sessionManagement stopSessionReasons may contain only 1 or 2"
        }
    }

    fun toWireMap(): Map<String, Any?> = linkedMapOf(
        "stopSession" to stopSession,
        "stopSessionReasons" to stopSessionReasons.toList(),
    )

    companion object {
        val SUPPORTED_STOP_SESSION_REASONS: Set<Long> = setOf(1L, 2L)

        fun fromWireMap(values: Map<String, Any?>): AirPlaySessionManagementInfo {
            require(values.keys == setOf("stopSession", "stopSessionReasons")) {
                "sessionManagementInfo must contain only stopSession and stopSessionReasons"
            }
            val stopSession = values["stopSession"]
                ?: throw IllegalArgumentException("sessionManagement stopSession is required")
            val reasons = values["stopSessionReasons"] as? List<*>
                ?: throw IllegalArgumentException(
                    "sessionManagement stopSessionReasons must be an array",
                )
            return AirPlaySessionManagementInfo(
                stopSession = wireInteger(stopSession, "sessionManagement stopSession"),
                stopSessionReasons = reasons.mapIndexed { index, value ->
                    wireInteger(
                        value,
                        "sessionManagement stopSessionReasons[$index]",
                    )
                },
            )
        }
    }
}

/**
 * Confirmed `mainBufferedInfo` presence metadata.
 *
 * `audioFormats` is a top-level AirPlay `/info` array; `audioOutputFormatsExtended` belongs inside
 * matching entries of that array. Neither is a `mainBufferedInfo` field, so accepting them here
 * would turn observed metadata into an invented sidecar schema.
 */
data class AirPlayMainBufferedInfo(
    val dcxEnabled: Boolean,
) {
    fun toWireMap(): Map<String, Any?> = linkedMapOf(DCX_ENABLED to dcxEnabled)

    companion object {
        private const val DCX_ENABLED = "DCXEnabled"

        fun fromWireMap(values: Map<String, Any?>): AirPlayMainBufferedInfo {
            require(values.keys == setOf(DCX_ENABLED)) {
                "mainBufferedInfo must contain only DCXEnabled"
            }
            val enabled = values[DCX_ENABLED] as? Boolean
                ?: throw IllegalArgumentException("mainBufferedInfo DCXEnabled must be a Boolean")
            return AirPlayMainBufferedInfo(enabled)
        }
    }
}

/** Confirmed `videoPlaybackInfo` metadata with its required Boolean state. */
data class AirPlayVideoPlaybackInfo(
    val videoPlaybackAllowed: Boolean,
) {
    fun toWireMap(): Map<String, Any?> = linkedMapOf(
        VIDEO_PLAYBACK_ALLOWED to videoPlaybackAllowed,
    )

    companion object {
        private const val VIDEO_PLAYBACK_ALLOWED = "videoPlaybackAllowed"

        fun fromWireMap(values: Map<String, Any?>): AirPlayVideoPlaybackInfo {
            require(values.keys == setOf(VIDEO_PLAYBACK_ALLOWED)) {
                "videoPlaybackInfo must contain only videoPlaybackAllowed"
            }
            val allowed = values[VIDEO_PLAYBACK_ALLOWED] as? Boolean
                ?: throw IllegalArgumentException(
                    "videoPlaybackInfo videoPlaybackAllowed must be a Boolean",
                )
            return AirPlayVideoPlaybackInfo(allowed)
        }
    }
}

internal fun immutableStringMap(values: Map<String, Any?>): Map<String, Any?> =
    values.entries.associateTo(linkedMapOf()) { (name, value) -> name to immutableWireValue(value) }

private fun immutableWireValue(value: Any?): Any? = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> value.map(::immutableWireValue)
    is Map<*, *> -> stringMap(value, "sidecar").entries.associateTo(linkedMapOf()) {
        (name, nested) -> name to immutableWireValue(nested)
    }
    else -> value
}

private fun stringMap(values: Map<*, *>, path: String): Map<String, Any?> =
    values.entries.associateTo(linkedMapOf()) { (key, value) ->
        val name = key as? String
            ?: throw IllegalArgumentException("$path keys must be strings")
        name to value
    }

private fun wireInteger(value: Any?, path: String): Long = when (value) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("$path must be an integer")
}

private fun wireUInt64(value: Any?, path: String): Long = try {
    CafUInt64.parse(value, path)
} catch (error: IllegalArgumentException) {
    throw IllegalArgumentException(error.message ?: "$path must be a UInt64 integer", error)
}
