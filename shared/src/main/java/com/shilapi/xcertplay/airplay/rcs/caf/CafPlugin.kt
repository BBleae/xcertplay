package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import java.util.Collections

interface CafPluginHandler : AutoCloseable {
    fun onTransactionCompleted(
        session: CafProtocolSession,
        transaction: CafPendingTransaction,
        message: CafMessageReader,
    ) = Unit

    fun onTransactionExpired(
        session: CafProtocolSession,
        transaction: CafPendingTransaction,
    ) = Unit

    fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onConfigResponse(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onConfigNotify(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onRegisterResponse(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onUnregisterRequest(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onUnregisterResponse(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onReadRequest(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onReadResponse(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onWriteRequest(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onWriteResponse(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onControlRequest(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onControlResponse(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onControlNotify(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onUpdateNotify(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)
    fun onGeneralError(session: CafProtocolSession, message: CafMessageReader): Unit = unsupported(session, message)

    override fun close() = Unit

    private fun unsupported(session: CafProtocolSession, message: CafMessageReader): Nothing {
        throw CafProtocolException(
            "CAF plugin ${CafUInt64.toString(message.pluginId)} does not implement " +
                "${message.command.wireName} on ${session.clientType.name}",
        )
    }
}

/** A provisioned handler must expose the exact immutable schema it will serve on the channel. */
interface CafSchemaBoundPluginHandler : CafPluginHandler {
    /** Empty means diagnostic/unidentified and is rejected by authorized provisioning. */
    val implementationId: String
        get() = ""

    fun schemaSnapshot(): CafConfigTree
}

/** Creates a fresh handler for one RCS channel. Handler/subscription state is never shared. */
fun interface CafPluginHandlerFactory {
    fun create(session: CafProtocolSession): CafPluginHandler

    /** Must equal every created schema-bound handler's identity on the authorized path. */
    val implementationId: String
        get() = ""
}

data class CafPluginRegistration(
    /** Raw UInt64 wire bits. */
    val pluginId: Long,
    val protocolVersion: String,
    val clientTypes: Set<RcsClientType>,
    val handlerFactory: CafPluginHandlerFactory,
    val pluginName: String? = null,
    val pluginConfig: Map<String, Any?>? = null,
    val configTreeDecoder: CafConfigTreeDecoder? = null,
    /** Returns an already decoded, side-effect-free schema for readiness/routing validation. */
    val schemaProvider: () -> CafConfigTree? = { null },
    /** Live, side-effect-free runtime probe. Default-deny prevents metadata-only enablement. */
    val runtimeReady: () -> Boolean = { false },
) {
    init {
        require(protocolVersion.isNotBlank() && protocolVersion.length <= MAX_VERSION_CHARS) {
            "CAF protocol version is invalid"
        }
        require(clientTypes.isNotEmpty()) { "CAF plugin must declare a client type" }
        require(clientTypes.all { it.payloadStyle == RcsPayloadStyle.CAF_BINARY_PLIST_OPACK }) {
            "CAF plugin client types must use the CAF carrier"
        }
        pluginName?.let {
            require(it.isNotBlank() && it.length <= MAX_PLUGIN_NAME_CHARS) {
                "CAF plugin name is invalid"
            }
        }
        wirePluginConfig()
    }

    /** OEM fields are retained, but cannot override or contradict the registered pluginID. */
    fun wirePluginConfig(): Map<String, Any?> {
        val supplied = pluginConfig.orEmpty()
        supplied[PLUGIN_ID_KEY]?.let { value ->
            if (CafNumbers.toUInt64(value, "pluginConfig.pluginID") != pluginId) {
                throw CafProtocolException("pluginConfig.pluginID does not match its registration")
            }
        }
        return linkedMapOf<String, Any?>(PLUGIN_ID_KEY to CafUInt64.plistNumber(pluginId)).apply {
            supplied.forEach { (key, value) -> if (key != PLUGIN_ID_KEY) put(key, value) }
        }
    }

    private companion object {
        const val PLUGIN_ID_KEY = "pluginID"
        const val MAX_VERSION_CHARS = 64
        const val MAX_PLUGIN_NAME_CHARS = 256
    }
}

fun interface CafPluginRegistrationProvider {
    fun registrations(): Collection<CafPluginRegistration>
}

/** Validated AirPlay `vehicleStateProtocolInfo` sidecar. */
data class CafVehicleStateProtocolInfo(
    val protocolVersion: String,
    val pluginConfigs: List<Map<String, Any?>>,
    val extensions: Map<String, Any?> = emptyMap(),
) {
    val pluginCount: Int get() = pluginConfigs.size

    init {
        require(protocolVersion.isNotBlank()) { "CAF protocolVersion must not be blank" }
        require(pluginConfigs.isNotEmpty()) { "CAF pluginConfigs must not be empty" }
        require(pluginConfigs.size <= MAX_PLUGINS) { "CAF pluginConfigs exceeds $MAX_PLUGINS" }
        rejectKnownExtensionKeys(extensions, INFO_KEYS, "CAF protocol info extensions")
        val identifiers = HashSet<Long>()
        pluginConfigs.forEachIndexed { index, config ->
            val identifier = CafNumbers.toUInt64(
                config["pluginID"],
                "pluginConfigs[$index].pluginID",
            )
            require(identifiers.add(identifier)) {
                "Duplicate CAF pluginID ${CafUInt64.toString(identifier)}"
            }
        }
    }

    /**
     * The phone enumerates advertised plugins by requesting IDs `0` through `pluginCount - 1`.
     * Keep the general CAF model UInt64-capable for diagnostics/OEM decoding, but reject a
     * provisioned sidecar whose count would make the phone request an unregistered ID.
     */
    fun requireIndexedPluginIdsForAdvertisement(): CafVehicleStateProtocolInfo {
        val ids = pluginConfigs.mapIndexedTo(HashSet<Long>(pluginCount)) { index, config ->
            CafNumbers.toUInt64(config["pluginID"], "pluginConfigs[$index].pluginID")
        }
        repeat(pluginCount) { expected ->
            if (expected.toLong() !in ids) {
                throw CafProtocolException(
                    "CAF pluginCount=$pluginCount requires pluginID $expected for phone enumeration",
                )
            }
        }
        return this
    }

    fun asWireMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(extensions)
        put("protocolVersion", protocolVersion)
        put("pluginCount", pluginCount.toLong())
        put("pluginConfigs", pluginConfigs)
    }

    companion object {
        private const val MAX_PLUGINS = 256
        private val INFO_KEYS = setOf("protocolVersion", "pluginCount", "pluginConfigs")

        fun decode(raw: Any?): CafVehicleStateProtocolInfo {
            val source = raw as? Map<*, *>
                ?: throw CafProtocolException("vehicleStateProtocolInfo must be a dictionary")
            val map = LinkedHashMap<String, Any?>(source.size)
            source.forEach { (key, value) ->
                map[key as? String
                    ?: throw CafProtocolException("vehicleStateProtocolInfo keys must be strings")] = value
            }
            val version = map["protocolVersion"] as? String
                ?: throw CafProtocolException("vehicleStateProtocolInfo.protocolVersion must be a string")
            val declaredCount = CafNumbers.toUInt64(
                map["pluginCount"],
                "vehicleStateProtocolInfo.pluginCount",
            )
            if (declaredCount < 0 || declaredCount > MAX_PLUGINS) {
                throw CafProtocolException("vehicleStateProtocolInfo.pluginCount exceeds $MAX_PLUGINS")
            }
            val rawConfigs = map["pluginConfigs"] as? List<*>
                ?: throw CafProtocolException("vehicleStateProtocolInfo.pluginConfigs must be an array")
            if (rawConfigs.size.toLong() != declaredCount) {
                throw CafProtocolException(
                    "vehicleStateProtocolInfo pluginCount=$declaredCount but has ${rawConfigs.size} configs",
                )
            }
            val configs = rawConfigs.mapIndexed { index, rawConfig ->
                val config = rawConfig as? Map<*, *>
                    ?: throw CafProtocolException("pluginConfigs[$index] must be a dictionary")
                LinkedHashMap<String, Any?>(config.size).apply {
                    config.forEach { (key, value) ->
                        put(
                            key as? String
                                ?: throw CafProtocolException("pluginConfigs[$index] keys must be strings"),
                            value,
                        )
                    }
                }
            }
            return CafVehicleStateProtocolInfo(
                protocolVersion = version,
                pluginConfigs = configs,
                extensions = map.filterKeys { it !in INFO_KEYS },
            )
        }
    }
}

/** Immutable registry shared by channels; only registrations/factories are shared, never handlers. */
class CafPluginRegistry private constructor(
    private val registrations: Map<Long, CafPluginRegistration>,
) {
    fun requireRegistration(pluginId: Long): CafPluginRegistration =
        registrations[pluginId]
            ?: throw CafProtocolException(
                "No CAF registration for pluginID ${CafUInt64.toString(pluginId)}",
            )

    fun supports(clientType: RcsClientType): Boolean =
        registrations.values.any { clientType in it.clientTypes }

    fun registrationsFor(clientType: RcsClientType): List<CafPluginRegistration> =
        registrations.values.filter { clientType in it.clientTypes }

    fun allRegistrations(): List<CafPluginRegistration> = registrations.values.toList()

    fun vehicleStateProtocolInfo(): CafVehicleStateProtocolInfo {
        val versions = registrations.values.map(CafPluginRegistration::protocolVersion).distinct()
        if (versions.size != 1) {
            throw CafProtocolException("All CAF plugins must use one protocolVersion")
        }
        return CafVehicleStateProtocolInfo(
            protocolVersion = versions.single(),
            pluginConfigs = registrations.values.map(CafPluginRegistration::wirePluginConfig),
        )
    }

    fun validateFor(
        clientType: RcsClientType,
        decoderFactory: CafConfigTreeDecoderFactory,
    ) {
        val routed = registrationsFor(clientType)
        if (routed.isEmpty()) throw CafProtocolException("No CAF plugin routes to ${clientType.name}")
        vehicleStateProtocolInfo()
        routed.forEach { registration ->
            val ready = try {
                registration.runtimeReady()
            } catch (_: Throwable) {
                false
            }
            if (!ready) {
                throw CafProtocolException(
                    "CAF plugin ${CafUInt64.toString(registration.pluginId)} is not runtime-ready",
                )
            }
            registration.configTreeDecoder
                ?: decoderFactory.create(registration.protocolVersion, registration.pluginConfig)
            val schema = try {
                registration.schemaProvider()
            } catch (error: Throwable) {
                throw CafProtocolException(
                    "CAF plugin ${CafUInt64.toString(registration.pluginId)} schema probe failed",
                    error,
                )
            } ?: throw CafProtocolException(
                "CAF plugin ${CafUInt64.toString(registration.pluginId)} has no validated schema",
            )
            validatePriorityRoutes(registration, schema, clientType)
        }
    }

    private fun validatePriorityRoutes(
        registration: CafPluginRegistration,
        schema: CafConfigTree,
        requestedClientType: RcsClientType,
    ) {
        val clientPriorities = registration.clientTypes.map { clientType ->
            clientType.priority ?: throw CafProtocolException(
                "CAF client ${clientType.name} has no routing priority",
            )
        }.toSet()
        val requestedPriority = requestedClientType.priority
            ?: throw CafProtocolException("CAF client ${requestedClientType.name} has no routing priority")
        if (requestedPriority !in clientPriorities) {
            throw CafProtocolException("CAF client priority is not registered")
        }
        val routes = buildList<Long?> {
            schema.characteristicsByIid.values.forEach { add(it.priority) }
            schema.controlsByIid.values.forEach { add(it.priority) }
        }
        if (routes.isEmpty()) throw CafProtocolException("CAF schema has no routable values")
        if (routes.any { it == null }) {
            throw CafProtocolException("CAF schema contains a value without channel priority")
        }
        val routedPriorities = routes.filterNotNull().toSet()
        val clientPriorityValues = clientPriorities.map(Int::toLong).toSet()
        if (!clientPriorityValues.containsAll(routedPriorities)) {
            throw CafProtocolException("CAF schema contains an unregistered channel priority")
        }
        clientPriorities.forEach { priority ->
            if (priority.toLong() !in routedPriorities) {
                throw CafProtocolException("CAF schema has no route for priority $priority")
            }
        }
    }

    companion object {
        const val CAF_PROTOCOL_VERSION_1_0 = "1.0"
        private val confirmedDefaultClientTypes: Set<RcsClientType> =
            Collections.unmodifiableSet(
                linkedSetOf(
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                ),
            )

        /** Exact CAF client types confirmed by the current protocol evidence. */
        val DEFAULT_CLIENT_TYPES: Set<RcsClientType>
            get() = confirmedDefaultClientTypes

        fun from(registrations: Collection<CafPluginRegistration>): CafPluginRegistry {
            require(registrations.isNotEmpty()) { "CAF registry must not be empty" }
            val byId = LinkedHashMap<Long, CafPluginRegistration>(registrations.size)
            registrations.forEach { registration ->
                require(byId.put(registration.pluginId, registration) == null) {
                    "Duplicate CAF registration for pluginID ${CafUInt64.toString(registration.pluginId)}"
                }
            }
            return CafPluginRegistry(byId.toMap())
        }

        fun from(provider: CafPluginRegistrationProvider): CafPluginRegistry = from(provider.registrations())
    }
}
