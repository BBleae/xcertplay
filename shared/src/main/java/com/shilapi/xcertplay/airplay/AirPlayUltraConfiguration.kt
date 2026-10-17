package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import java.util.Collections

/**
 * Explicit Ultra opt-in for one AirPlay control session.
 *
 * Vehicle State Protocol, UI Sync, UpdateData file reception, and the built-in
 * session-management consumer are configurable. File reception still requires a bounded,
 * durable quarantine consumer; it does not imply asset authentication or activation. Known
 * names for log transfer, video playback, and main buffering remain unavailable.
 */
class AirPlayUltraConfiguration(
    configuredFeatures: Set<AirPlayFeature> = emptySet(),
    val vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
    val uiSyncInfo: AirPlayUiSyncInfo? = null,
    val readiness: AirPlayUltraReadiness = AirPlayUltraReadiness(),
    val sessionManagementInfo: AirPlaySessionManagementInfo? = null,
    val fileTransferInfo: AirPlayFileTransferInfo? = null,
) {
    val configuredFeatures: Set<AirPlayFeature> = Collections.unmodifiableSet(
        snapshotConfiguredFeatures(configuredFeatures),
    )
    private val configured = this.configuredFeatures

    init {
        val unsupported = configured - CONFIGURABLE_FEATURES
        require(unsupported.isEmpty()) {
            "Ultra features are not configurable without complete implementations: " +
                unsupported.joinToString { it.wireName }
        }
    }

    fun isConfigured(feature: AirPlayFeature): Boolean = feature in configured

    fun copy(
        configuredFeatures: Set<AirPlayFeature> = this.configuredFeatures,
        vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = this.vehicleStateProtocolInfo,
        uiSyncInfo: AirPlayUiSyncInfo? = this.uiSyncInfo,
        readiness: AirPlayUltraReadiness = this.readiness,
        sessionManagementInfo: AirPlaySessionManagementInfo? = this.sessionManagementInfo,
        fileTransferInfo: AirPlayFileTransferInfo? = this.fileTransferInfo,
    ): AirPlayUltraConfiguration = AirPlayUltraConfiguration(
        configuredFeatures = configuredFeatures,
        vehicleStateProtocolInfo = vehicleStateProtocolInfo,
        uiSyncInfo = uiSyncInfo,
        readiness = readiness,
        sessionManagementInfo = sessionManagementInfo,
        fileTransferInfo = fileTransferInfo,
    )

    override fun equals(other: Any?): Boolean =
        other is AirPlayUltraConfiguration &&
            configuredFeatures == other.configuredFeatures &&
            vehicleStateProtocolInfo == other.vehicleStateProtocolInfo &&
            uiSyncInfo == other.uiSyncInfo &&
            readiness == other.readiness &&
            sessionManagementInfo == other.sessionManagementInfo &&
            fileTransferInfo == other.fileTransferInfo

    override fun hashCode(): Int {
        var result = configuredFeatures.hashCode()
        result = 31 * result + (vehicleStateProtocolInfo?.hashCode() ?: 0)
        result = 31 * result + (uiSyncInfo?.hashCode() ?: 0)
        result = 31 * result + readiness.hashCode()
        result = 31 * result + (sessionManagementInfo?.hashCode() ?: 0)
        result = 31 * result + (fileTransferInfo?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AirPlayUltraConfiguration(" +
            "configuredFeatures=$configuredFeatures, " +
            "vehicleStateProtocolInfo=$vehicleStateProtocolInfo, " +
            "uiSyncInfo=$uiSyncInfo, " +
            "readiness=$readiness, " +
            "sessionManagementInfo=$sessionManagementInfo, " +
            "fileTransferInfo=$fileTransferInfo)"

    companion object {
        val CONFIGURABLE_FEATURES: Set<AirPlayFeature> = setOf(
            AirPlayFeature.VEHICLE_STATE_PROTOCOL,
            AirPlayFeature.UI_SYNC,
            AirPlayFeature.SESSION_MANAGEMENT,
            AirPlayFeature.FILE_TRANSFER,
        )

        /**
         * Captures WP-B/WP-D runtime descriptors once while constructing the control session.
         * Missing, mismatched, or failed probes stay absent and therefore keep the feature off.
         */
        fun fromRuntimeDescriptors(
            configuredFeatures: Set<AirPlayFeature>,
            vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
            uiSyncInfo: AirPlayUiSyncInfo? = null,
            rcsFactory: RcsDataStreamHandlerFactory? = null,
            uiSyncEndpoint: CarPlayUiSyncEndpoint? = null,
            uiSyncEndpoints: Collection<CarPlayUiSyncEndpoint> = emptyList(),
            sessionManagementInfo: AirPlaySessionManagementInfo? = null,
            fileTransferInfo: AirPlayFileTransferInfo? = null,
            updateDataConsumerReadiness: AirPlayUpdateDataConsumerReadinessDescriptor? = null,
        ): AirPlayUltraConfiguration = fromRuntimeDescriptorsInternal(
            configuredFeatures = configuredFeatures,
            vehicleStateProtocolInfo = vehicleStateProtocolInfo,
            uiSyncInfo = uiSyncInfo,
            rcsFactory = rcsFactory,
            uiSyncEndpoint = uiSyncEndpoint,
            uiSyncEndpoints = uiSyncEndpoints,
            sessionManagementInfo = sessionManagementInfo,
            fileTransferInfo = fileTransferInfo,
            updateDataConsumerReadiness = updateDataConsumerReadiness,
            uiSyncRendererReadiness =
                AirPlayUiSyncRendererReadinessDescriptor.diagnosticOnly(),
            uiSyncSchemaFingerprint = null,
            uiSyncBindingFingerprint = null,
            uiSyncProvisionedDisplayIds = null,
            uiSyncRendererReceipt = null,
        )

        /** Atomic provisioning path; legacy endpoint injection remains diagnostic-only. */
        internal fun fromRuntimeProvisioning(
            configuredFeatures: Set<AirPlayFeature>,
            vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
            uiSyncInfo: AirPlayUiSyncInfo? = null,
            rcsFactory: RcsDataStreamHandlerFactory? = null,
            uiSyncEndpoints: Collection<CarPlayUiSyncEndpoint> = emptyList(),
            sessionManagementInfo: AirPlaySessionManagementInfo? = null,
            fileTransferInfo: AirPlayFileTransferInfo? = null,
            updateDataConsumerReadiness: AirPlayUpdateDataConsumerReadinessDescriptor? = null,
            uiSyncRendererReadiness: AirPlayUiSyncRendererReadinessDescriptor,
            uiSyncSchemaFingerprint: UiSyncSchemaFingerprint,
            uiSyncBindingFingerprint: UiSyncBindingFingerprint,
            uiSyncProvisionedDisplayIds: Collection<String>,
            uiSyncRendererReceipt: RendererReadinessReceipt?,
        ): AirPlayUltraConfiguration = fromRuntimeDescriptorsInternal(
            configuredFeatures = configuredFeatures,
            vehicleStateProtocolInfo = vehicleStateProtocolInfo,
            uiSyncInfo = uiSyncInfo,
            rcsFactory = rcsFactory,
            uiSyncEndpoints = uiSyncEndpoints,
            sessionManagementInfo = sessionManagementInfo,
            fileTransferInfo = fileTransferInfo,
            updateDataConsumerReadiness = updateDataConsumerReadiness,
            uiSyncRendererReadiness = uiSyncRendererReadiness,
            uiSyncSchemaFingerprint = uiSyncSchemaFingerprint,
            uiSyncBindingFingerprint = uiSyncBindingFingerprint,
            uiSyncProvisionedDisplayIds = uiSyncProvisionedDisplayIds,
            uiSyncRendererReceipt = uiSyncRendererReceipt,
        )

        private fun fromRuntimeDescriptorsInternal(
            configuredFeatures: Set<AirPlayFeature>,
            vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo?,
            uiSyncInfo: AirPlayUiSyncInfo?,
            rcsFactory: RcsDataStreamHandlerFactory?,
            uiSyncEndpoint: CarPlayUiSyncEndpoint? = null,
            uiSyncEndpoints: Collection<CarPlayUiSyncEndpoint>,
            sessionManagementInfo: AirPlaySessionManagementInfo?,
            fileTransferInfo: AirPlayFileTransferInfo?,
            updateDataConsumerReadiness: AirPlayUpdateDataConsumerReadinessDescriptor?,
            uiSyncRendererReadiness: AirPlayUiSyncRendererReadinessDescriptor,
            uiSyncSchemaFingerprint: UiSyncSchemaFingerprint?,
            uiSyncBindingFingerprint: UiSyncBindingFingerprint?,
            uiSyncProvisionedDisplayIds: Collection<String>?,
            uiSyncRendererReceipt: RendererReadinessReceipt?,
        ): AirPlayUltraConfiguration {
            val frozenConfiguredFeatures = Collections.unmodifiableSet(
                snapshotConfiguredFeatures(configuredFeatures),
            )
            val endpointRegistry = CarPlayUiSyncEndpointRegistry(
                boundedUiSyncEndpoints(uiSyncEndpoint, uiSyncEndpoints),
            )
            val provisioningIdentity = uiSyncProvisionedDisplayIds?.let { Any() }
            val provisionedDisplayIds = uiSyncProvisionedDisplayIds?.let {
                boundedUiSyncDisplayIds(it, "provisioned UI Sync display IDs")
            }
            require(provisionedDisplayIds == null || provisionedDisplayIds == endpointRegistry.displayIds) {
                "Provisioned UI Sync sidecar and endpoint display IDs diverged"
            }
            val capturedUiSyncInfo = if (provisioningIdentity != null && uiSyncInfo != null) {
                uiSyncInfo.withProvisionedDisplays(
                    checkNotNull(provisionedDisplayIds),
                    provisioningIdentity,
                    checkNotNull(uiSyncSchemaFingerprint),
                    checkNotNull(uiSyncBindingFingerprint),
                )
            } else {
                uiSyncInfo
            }
            val requiredTypes = frozenConfiguredFeatures
                .flatMapTo(linkedSetOf(), AirPlayFeatureCatalog::requiredRcsClientTypes)
            val registrations = linkedMapOf<RcsClientType, RcsDataStreamHandlerDescriptor>()
            val captured = requiredTypes.mapNotNull { clientType ->
                val descriptor = rcsFactory?.descriptor(clientType)
                    ?.takeIf { it.clientType == clientType }
                    ?: return@mapNotNull null
                registrations[clientType] = descriptor
                AirPlayRcsHandlerReadinessDescriptor.capture(descriptor)
            }
            val capturedUpdateData = captured.firstOrNull {
                it.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA
            }
            val boundUpdateDataConsumer = if (capturedUpdateData != null) {
                updateDataConsumerReadiness?.boundTo(
                    registrations.getValue(RcsClientTypes.CAR_PLAY_UPDATE_DATA),
                    capturedUpdateData,
                )
            } else {
                null
            }
            val clusterControl = captured.firstOrNull {
                it.clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
            }
            val uiReadiness = if (!endpointRegistry.isEmpty && clusterControl != null) {
                if (provisioningIdentity != null &&
                    uiSyncSchemaFingerprint != null &&
                    uiSyncBindingFingerprint != null
                ) {
                    AirPlayUiSyncReadinessDescriptor.captureProvisioned(
                        registry = endpointRegistry,
                        clusterControl = clusterControl,
                        renderer = uiSyncRendererReadiness,
                        schemaFingerprint = uiSyncSchemaFingerprint,
                        bindingFingerprint = uiSyncBindingFingerprint,
                        rendererReceipt = uiSyncRendererReceipt,
                        provisioningIdentity = provisioningIdentity,
                    )
                } else {
                    AirPlayUiSyncReadinessDescriptor.capture(
                        endpointRegistry,
                        clusterControl,
                        uiSyncRendererReadiness,
                        uiSyncSchemaFingerprint,
                        uiSyncBindingFingerprint,
                    )
                }
            } else {
                null
            }
            return AirPlayUltraConfiguration(
                configuredFeatures = frozenConfiguredFeatures,
                vehicleStateProtocolInfo = vehicleStateProtocolInfo,
                uiSyncInfo = capturedUiSyncInfo,
                sessionManagementInfo = sessionManagementInfo,
                fileTransferInfo = fileTransferInfo,
                readiness = AirPlayUltraReadiness(
                    rcsHandlers = captured,
                    updateDataConsumer = boundUpdateDataConsumer,
                    uiSync = uiReadiness,
                    sessionManagement = if (
                        AirPlayFeature.SESSION_MANAGEMENT in frozenConfiguredFeatures
                    ) {
                        AirPlaySessionManagementReadinessDescriptor.captureBuiltIn()
                    } else {
                        null
                    },
                ),
            )
        }
    }
}

private fun boundedUiSyncEndpoints(
    compatibilityEndpoint: CarPlayUiSyncEndpoint?,
    endpoints: Collection<CarPlayUiSyncEndpoint>,
): List<CarPlayUiSyncEndpoint> {
    val snapshot = ArrayList<CarPlayUiSyncEndpoint>()
    compatibilityEndpoint?.let(snapshot::add)
    val iterator = endpoints.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS) {
            "UI Sync endpoints exceed $MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS entries"
        }
        snapshot += iterator.next()
    }
    return snapshot
}

private fun snapshotConfiguredFeatures(
    features: Collection<AirPlayFeature>,
): LinkedHashSet<AirPlayFeature> {
    val snapshot = LinkedHashSet<AirPlayFeature>()
    val iterator = features.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < AirPlayFeature.entries.size) {
            "Configured AirPlay features exceed the known feature catalog"
        }
        val feature = iterator.next()
        require(snapshot.add(feature)) { "Configured AirPlay features contain a duplicate" }
    }
    return snapshot
}

/** Source-compatible short name for code that builds per-session Ultra provisioning. */
typealias AirPlayUltraConfig = AirPlayUltraConfiguration

/**
 * iOS 27 carkitd checks the presence of `fileTransferInfo`, but its dictionary fields have not
 * been established. An empty dictionary is therefore the only currently defensible encoding.
 * It authorizes no archive and is emitted only after FILE_TRANSFER passes readiness/transport
 * gates and the phone actually negotiates the feature.
 */
class AirPlayFileTransferInfo {
    fun toWireMap(): Map<String, Any?> = emptyMap()

    override fun equals(other: Any?): Boolean = other is AirPlayFileTransferInfo

    override fun hashCode(): Int = 0

    override fun toString(): String = "AirPlayFileTransferInfo()"
}
