package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsCatalogReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAvailability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A snapshot value, never a live `supports()` claim. */
enum class AirPlayReadiness {
    READY,
    UNAVAILABLE,
    DIAGNOSTIC_ONLY,
}

/**
 * Immutable evidence that one concrete RCS handler can carry and consume its declared schema.
 *
 * The implementation identifier is diagnostic and must identify a real factory/endpoint. A
 * catalog entry by itself is never a handler descriptor.
 */
data class AirPlayRcsHandlerReadinessDescriptor(
    val clientType: RcsClientType,
    val implementationId: String,
    val handler: AirPlayReadiness,
    val carrier: AirPlayReadiness,
    val consumer: AirPlayReadiness,
) {
    val isComplete: Boolean
        get() = implementationId.isNotBlank() &&
            clientType.catalogReadiness == RcsCatalogReadiness.CONFIRMED_SCHEMA &&
            handler == AirPlayReadiness.READY &&
            carrier == AirPlayReadiness.READY &&
            consumer == AirPlayReadiness.READY

    companion object {
        /**
         * Probes a concrete WP-B handler descriptor exactly once and captures the result for the
         * lifetime of this AirPlay session. Later factory mutations cannot change negotiation.
         */
        fun capture(
            descriptor: RcsDataStreamHandlerDescriptor,
            carrier: AirPlayReadiness = AirPlayReadiness.READY,
            consumer: AirPlayReadiness = AirPlayReadiness.READY,
        ): AirPlayRcsHandlerReadinessDescriptor = AirPlayRcsHandlerReadinessDescriptor(
            clientType = descriptor.clientType,
            implementationId = descriptor.implementationId,
            handler = if (descriptor.probe().isReady) {
                AirPlayReadiness.READY
            } else {
                AirPlayReadiness.UNAVAILABLE
            },
            carrier = carrier,
            consumer = consumer,
        )
    }
}

/**
 * Opaque registration-bound evidence that a CarPlayUpdateData handler has a bounded quarantine
 * consumer. Only the caller owning that consumer may mint this after checking current root,
 * quota, durable sink, and terminal-ACK readiness. This is not asset trust evidence.
 *
 * The exact handler descriptor instance is part of the proof. A wrapper/copy of a probed
 * descriptor must be re-provisioned by its owner; equal UUID or implementation ID is insufficient.
 */
class AirPlayUpdateDataConsumerReadinessDescriptor private constructor(
    private val registration: RcsDataStreamHandlerDescriptor,
    val boundedQuarantineConsumer: AirPlayReadiness,
    private val capturedHandler: AirPlayRcsHandlerReadinessDescriptor? = null,
) {
    /** One capacity reservation may justify at most one frozen AirPlay configuration. */
    private val boundOnce = AtomicBoolean(false)

    internal fun boundTo(
        exactRegistration: RcsDataStreamHandlerDescriptor,
        captured: AirPlayRcsHandlerReadinessDescriptor,
    ): AirPlayUpdateDataConsumerReadinessDescriptor? =
        if (registration === exactRegistration &&
            exactRegistration.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA &&
            captured.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA &&
            capturedHandler == null &&
            boundOnce.compareAndSet(false, true)
        ) {
            AirPlayUpdateDataConsumerReadinessDescriptor(
                registration,
                boundedQuarantineConsumer,
                captured,
            )
        } else {
            null
        }

    internal fun matches(handler: AirPlayRcsHandlerReadinessDescriptor): Boolean =
        boundedQuarantineConsumer == AirPlayReadiness.READY &&
            capturedHandler === handler &&
            handler.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA &&
            handler.isComplete

    companion object {
        internal fun capture(
            handlerDescriptor: RcsDataStreamHandlerDescriptor,
            boundedQuarantineConsumer: AirPlayReadiness,
        ): AirPlayUpdateDataConsumerReadinessDescriptor {
            require(handlerDescriptor.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA) {
                "UpdateData consumer proof requires the exact CarPlayUpdateData registration"
            }
            return AirPlayUpdateDataConsumerReadinessDescriptor(
                handlerDescriptor,
                boundedQuarantineConsumer,
            )
        }
    }
}

/**
 * UI Sync-specific readiness in addition to the generic ClusterControl RCS handler.
 *
 * [displayIds] identifies the concrete type-111 endpoints served by the shared ClusterControl
 * stream. It is an immutable snapshot, not another display geometry model.
 */
class AirPlayUiSyncReadinessDescriptor private constructor(
    displayIds: Collection<String>,
    val clusterControl: AirPlayRcsHandlerReadinessDescriptor,
    val endpointCount: Int,
    val outerCarrier: AirPlayReadiness,
    val stateMachineConsumer: AirPlayReadiness,
    val renderer: AirPlayUiSyncRendererReadinessDescriptor =
        AirPlayUiSyncRendererReadinessDescriptor.diagnosticOnly(),
    val schemaFingerprint: UiSyncSchemaFingerprint? = null,
    val bindingFingerprint: UiSyncBindingFingerprint? = null,
    private val provisioningEvidence: AirPlayUiSyncProvisioningEvidence?,
) {
    constructor(
        displayIds: Collection<String>,
        clusterControl: AirPlayRcsHandlerReadinessDescriptor,
        endpointCount: Int,
        outerCarrier: AirPlayReadiness,
        stateMachineConsumer: AirPlayReadiness,
        renderer: AirPlayUiSyncRendererReadinessDescriptor =
            AirPlayUiSyncRendererReadinessDescriptor.diagnosticOnly(),
        schemaFingerprint: UiSyncSchemaFingerprint? = null,
        bindingFingerprint: UiSyncBindingFingerprint? = null,
    ) : this(
        displayIds = displayIds,
        clusterControl = clusterControl,
        endpointCount = endpointCount,
        outerCarrier = outerCarrier,
        stateMachineConsumer = stateMachineConsumer,
        renderer = renderer,
        schemaFingerprint = schemaFingerprint,
        bindingFingerprint = bindingFingerprint,
        provisioningEvidence = null,
    )

    val displayIds: Set<String> = boundedUiSyncDisplayIds(
        displayIds,
        "UI Sync readiness display IDs",
    )

    init {
        require(endpointCount in 0..MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS) {
            "endpointCount must be between 0 and $MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS"
        }
    }

    /** Pre-session offer readiness; actual ClusterControl admission has a separate ACTIVE gate. */
    val isComplete: Boolean
        get() = displayIds.isNotEmpty() &&
            endpointCount == displayIds.size &&
            clusterControl.clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL &&
            clusterControl.clientType.payloadStyle == RcsPayloadStyle.UI_SYNC_PLIST &&
            clusterControl.isComplete &&
            outerCarrier == AirPlayReadiness.READY &&
            stateMachineConsumer == AirPlayReadiness.READY &&
            renderer.isComplete &&
            (renderer.callbackParallelism ?: 0) >= endpointCount &&
            schemaFingerprint != null &&
            bindingFingerprint != null &&
            provisioningEvidence?.matches(
                displayIds = displayIds,
                endpointCount = endpointCount,
                schemaFingerprint = schemaFingerprint,
                bindingFingerprint = bindingFingerprint,
            ) == true

    internal fun matchesProvisionedSidecar(info: AirPlayUiSyncInfo): Boolean {
        val evidence = provisioningEvidence ?: return false
        return evidence.identity === info.provisioningIdentity &&
            info.declaredDisplayIds == displayIds &&
            info.provisioningSchemaFingerprint == schemaFingerprint &&
            info.provisioningBindingFingerprint == bindingFingerprint
    }

    override fun equals(other: Any?): Boolean =
        other is AirPlayUiSyncReadinessDescriptor &&
            displayIds == other.displayIds &&
            clusterControl == other.clusterControl &&
            endpointCount == other.endpointCount &&
            outerCarrier == other.outerCarrier &&
            stateMachineConsumer == other.stateMachineConsumer &&
            renderer == other.renderer &&
            schemaFingerprint == other.schemaFingerprint &&
            bindingFingerprint == other.bindingFingerprint &&
            (provisioningEvidence != null) == (other.provisioningEvidence != null)

    override fun hashCode(): Int {
        var result = displayIds.hashCode()
        result = 31 * result + clusterControl.hashCode()
        result = 31 * result + endpointCount
        result = 31 * result + outerCarrier.hashCode()
        result = 31 * result + stateMachineConsumer.hashCode()
        result = 31 * result + renderer.hashCode()
        result = 31 * result + (schemaFingerprint?.hashCode() ?: 0)
        result = 31 * result + (bindingFingerprint?.hashCode() ?: 0)
        result = 31 * result + (provisioningEvidence != null).hashCode()
        return result
    }

    override fun toString(): String =
        "AirPlayUiSyncReadinessDescriptor(" +
            "displayIds=$displayIds, " +
            "clusterControl=$clusterControl, " +
            "endpointCount=$endpointCount, " +
            "outerCarrier=$outerCarrier, " +
            "stateMachineConsumer=$stateMachineConsumer, " +
            "renderer=$renderer, " +
            "schemaFingerprint=$schemaFingerprint, " +
            "bindingFingerprint=$bindingFingerprint, " +
            "provisioned=${provisioningEvidence != null})"

    /** Compatibility constructor for the original single UUID display integration. */
    @Deprecated("Use the immutable displayIds registry snapshot")
    constructor(
        displayUuid: UUID,
        clusterControl: AirPlayRcsHandlerReadinessDescriptor,
        endpointId: String,
        outerCarrier: AirPlayReadiness,
        stateMachineConsumer: AirPlayReadiness,
    ) : this(
        displayIds = setOf(displayUuid.toString()),
        clusterControl = clusterControl,
        endpointCount = if (endpointId.isNotBlank()) 1 else 0,
        outerCarrier = outerCarrier,
        stateMachineConsumer = stateMachineConsumer,
        renderer = AirPlayUiSyncRendererReadinessDescriptor.diagnosticOnly(),
        schemaFingerprint = null,
        bindingFingerprint = null,
    )

    companion object {
        /** Captures the concrete per-display UI Sync codec/state-machine endpoints. */
        fun capture(
            registry: CarPlayUiSyncEndpointRegistry,
            clusterControl: AirPlayRcsHandlerReadinessDescriptor,
            renderer: AirPlayUiSyncRendererReadinessDescriptor =
                AirPlayUiSyncRendererReadinessDescriptor.diagnosticOnly(),
            schemaFingerprint: UiSyncSchemaFingerprint? = null,
            bindingFingerprint: UiSyncBindingFingerprint? = null,
        ): AirPlayUiSyncReadinessDescriptor = AirPlayUiSyncReadinessDescriptor(
            displayIds = registry.displayIds,
            clusterControl = clusterControl,
            endpointCount = registry.endpoints.size,
            outerCarrier = if (registry.isProtocolRuntimeComplete) {
                AirPlayReadiness.READY
            } else {
                AirPlayReadiness.UNAVAILABLE
            },
            stateMachineConsumer = if (registry.isProtocolRuntimeComplete) {
                AirPlayReadiness.READY
            } else {
                AirPlayReadiness.UNAVAILABLE
            },
            renderer = renderer,
            schemaFingerprint = schemaFingerprint,
            bindingFingerprint = bindingFingerprint,
            provisioningEvidence = null,
        )

        /**
         * Captures the only feature-negotiation-capable UI Sync readiness descriptor.
         *
         * The identity is shared with the derived [AirPlayUiSyncInfo], while the renderer receipt
         * is challenge-bound by `UiSyncRuntimeProvisioning`. A legacy endpoint/renderer descriptor
         * can still be inspected, but cannot manufacture this provenance.
         */
        internal fun captureProvisioned(
            registry: CarPlayUiSyncEndpointRegistry,
            clusterControl: AirPlayRcsHandlerReadinessDescriptor,
            renderer: AirPlayUiSyncRendererReadinessDescriptor,
            schemaFingerprint: UiSyncSchemaFingerprint,
            bindingFingerprint: UiSyncBindingFingerprint,
            rendererReceipt: RendererReadinessReceipt?,
            provisioningIdentity: Any,
        ): AirPlayUiSyncReadinessDescriptor = AirPlayUiSyncReadinessDescriptor(
            displayIds = registry.displayIds,
            clusterControl = clusterControl,
            endpointCount = registry.endpoints.size,
            outerCarrier = if (registry.isProtocolRuntimeComplete) {
                AirPlayReadiness.READY
            } else {
                AirPlayReadiness.UNAVAILABLE
            },
            stateMachineConsumer = if (registry.isProtocolRuntimeComplete) {
                AirPlayReadiness.READY
            } else {
                AirPlayReadiness.UNAVAILABLE
            },
            renderer = renderer,
            schemaFingerprint = schemaFingerprint,
            bindingFingerprint = bindingFingerprint,
            provisioningEvidence = rendererReceipt?.let {
                AirPlayUiSyncProvisioningEvidence(provisioningIdentity, it)
            },
        )

        /** Source-compatible single-endpoint capture. */
        fun capture(
            endpoint: CarPlayUiSyncEndpoint,
            clusterControl: AirPlayRcsHandlerReadinessDescriptor,
        ): AirPlayUiSyncReadinessDescriptor = capture(
            CarPlayUiSyncEndpointRegistry(listOf(endpoint)),
            clusterControl,
        )
    }
}

/** Non-serializable, immutable proof joining the derived sidecar to one exact provisioning. */
private class AirPlayUiSyncProvisioningEvidence(
    val identity: Any,
    private val rendererReceipt: RendererReadinessReceipt,
) {
    fun matches(
        displayIds: Set<String>,
        endpointCount: Int,
        schemaFingerprint: UiSyncSchemaFingerprint,
        bindingFingerprint: UiSyncBindingFingerprint,
    ): Boolean = rendererReceipt.phase == RendererReadinessReceipt.Phase.STATIC_FACTORY &&
        rendererReceipt.schemaFingerprint == schemaFingerprint &&
        rendererReceipt.bindingFingerprint == bindingFingerprint &&
        rendererReceipt.displayIds == displayIds &&
        rendererReceipt.subscribedDisplayIds.isEmpty() &&
        rendererReceipt.displayIds.size == endpointCount &&
        rendererReceipt.boundOutputIds.isEmpty() &&
        rendererReceipt.semanticCompilerActive &&
        rendererReceipt.themeCompilerActive &&
        rendererReceipt.backendLive
}

/**
 * Pre-session evidence of a renderer implementation and live Host physical-output preflight.
 *
 * The generic protocol endpoint intentionally captures [DIAGNOSTIC_ONLY][AirPlayReadiness.DIAGNOSTIC_ONLY]
 * for all four renderer stages and its callback dispatcher. Protocol decoding and event retention
 * are useful diagnostics, but they are not a CarPlay Ultra renderer. A complete descriptor can
 * only be captured by the atomic UI Sync provisioning seam after it has checked the exact display
 * static bindings and bounded, non-inline callback-dispatch evidence. This is not proof that a
 * session has attached physical outputs: observed replay subscriptions and the exact current
 * Host/EGL batch lease are required before ClusterControl or render work may start.
 */
class AirPlayUiSyncRendererReadinessDescriptor internal constructor(
    val implementationId: String,
    val payloadDecoder: AirPlayReadiness,
    val themeReducer: AirPlayReadiness,
    val outputBindings: AirPlayReadiness,
    val outputBackend: AirPlayReadiness,
    val callbackDispatcher: AirPlayReadiness,
    val maximumPendingCallbacks: Int?,
    val callbackParallelism: Int?,
    val mayExecuteInline: Boolean,
    private val physicalOfferPreflight: UiSyncPhysicalOutputAvailability? = null,
) {
    init {
        require(SAFE_IMPLEMENTATION_ID.matches(implementationId)) {
            "UI Sync renderer implementationId must be a short machine-readable identifier"
        }
        require(maximumPendingCallbacks == null || maximumPendingCallbacks in 1..65_536) {
            "UI Sync renderer maximumPendingCallbacks is out of range"
        }
        require(callbackParallelism == null || callbackParallelism in 1..256) {
            "UI Sync renderer callbackParallelism is out of range"
        }
    }

    val isComplete: Boolean
        get() = implementationId != DIAGNOSTIC_IMPLEMENTATION_ID &&
            payloadDecoder == AirPlayReadiness.READY &&
            themeReducer == AirPlayReadiness.READY &&
            outputBindings == AirPlayReadiness.READY &&
            outputBackend == AirPlayReadiness.READY &&
            callbackDispatcher == AirPlayReadiness.READY &&
            (maximumPendingCallbacks ?: 0) > 0 &&
            (callbackParallelism ?: 0) > 0 &&
            !mayExecuteInline &&
            physicalOfferPreflight?.isCurrent == true

    /**
     * A MAIN DDP offer may share its signed physical batch with CLUSTER or other outputs. The
     * MAIN route must be unique, target the MAIN panel's actual plug-in, and be covered by the
     * *whole* current preflight. A CLUSTER route must likewise target its own advertised plug-in;
     * an ALT preview or a borrowed MAIN target cannot justify a physical cluster assignment.
     * Host surface kind, ownership and target-version liveness are checked by the product
     * physical-output authority behind [UiSyncPhysicalOutputAvailability.isCurrent].
     */
    internal fun hasCurrentMainOutputFor(panels: AirPlayPhysicalDisplayPanels): Boolean {
        val preflight = physicalOfferPreflight ?: return false
        val assignments = preflight.requirement.assignments
        val main = assignments.singleOrNull {
            it.destination.role == UiSyncPhysicalDestinationRole.MAIN
        } ?: return false
        if (main.destination.instanceId != "default" ||
            main.output.uiSyncDisplayId?.value !=
                panels.pluginUidManagingLegacyStream(AirPlayInfoPlist.MAIN_UUID)
        ) return false
        val clusterPlugin = panels.pluginUidManagingLegacyStream(AirPlayInfoPlist.ALT_UUID)
        if (assignments.any { row ->
                row.destination.role == UiSyncPhysicalDestinationRole.CLUSTER &&
                    (clusterPlugin == null || row.output.uiSyncDisplayId?.value != clusterPlugin)
            }
        ) return false
        return preflight.requirement.matchesRoutes(preflight.routes) && preflight.isCurrent
    }

    /** A signed physical CLUSTER output cannot be served if ALT was not accepted for this SETUP. */
    internal val requiresAlternateScreenOutput: Boolean
        get() = physicalOfferPreflight?.requirement?.assignments?.any {
            it.destination.role == UiSyncPhysicalDestinationRole.CLUSTER
        } == true

    override fun equals(other: Any?): Boolean =
        other is AirPlayUiSyncRendererReadinessDescriptor &&
            implementationId == other.implementationId &&
            payloadDecoder == other.payloadDecoder &&
            themeReducer == other.themeReducer &&
            outputBindings == other.outputBindings &&
            outputBackend == other.outputBackend &&
            callbackDispatcher == other.callbackDispatcher &&
            maximumPendingCallbacks == other.maximumPendingCallbacks &&
            callbackParallelism == other.callbackParallelism &&
            mayExecuteInline == other.mayExecuteInline &&
            physicalOfferPreflight === other.physicalOfferPreflight

    override fun hashCode(): Int {
        var result = implementationId.hashCode()
        result = 31 * result + payloadDecoder.hashCode()
        result = 31 * result + themeReducer.hashCode()
        result = 31 * result + outputBindings.hashCode()
        result = 31 * result + outputBackend.hashCode()
        result = 31 * result + callbackDispatcher.hashCode()
        result = 31 * result + (maximumPendingCallbacks ?: 0)
        result = 31 * result + (callbackParallelism ?: 0)
        result = 31 * result + mayExecuteInline.hashCode()
        result = 31 * result + System.identityHashCode(physicalOfferPreflight)
        return result
    }

    override fun toString(): String =
        "AirPlayUiSyncRendererReadinessDescriptor(" +
            "implementationId='$implementationId', " +
            "payloadDecoder=$payloadDecoder, " +
            "themeReducer=$themeReducer, " +
            "outputBindings=$outputBindings, " +
            "outputBackend=$outputBackend, " +
            "callbackDispatcher=$callbackDispatcher, " +
            "maximumPendingCallbacks=$maximumPendingCallbacks, " +
            "callbackParallelism=$callbackParallelism, " +
            "mayExecuteInline=$mayExecuteInline)"

    companion object {
        internal const val DIAGNOSTIC_IMPLEMENTATION_ID =
            "xcertplay.ui-sync.presentation-diagnostics.v1"
        private val SAFE_IMPLEMENTATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")

        internal fun diagnosticOnly(): AirPlayUiSyncRendererReadinessDescriptor =
            AirPlayUiSyncRendererReadinessDescriptor(
                implementationId = DIAGNOSTIC_IMPLEMENTATION_ID,
                payloadDecoder = AirPlayReadiness.DIAGNOSTIC_ONLY,
                themeReducer = AirPlayReadiness.DIAGNOSTIC_ONLY,
                outputBindings = AirPlayReadiness.DIAGNOSTIC_ONLY,
                outputBackend = AirPlayReadiness.DIAGNOSTIC_ONLY,
                callbackDispatcher = AirPlayReadiness.DIAGNOSTIC_ONLY,
                maximumPendingCallbacks = null,
                callbackParallelism = null,
                mayExecuteInline = true,
            )

        /** Test seam: callers must still provide explicit physical availability evidence. */
        internal fun ready(
            implementationId: String,
            physicalOfferPreflight: UiSyncPhysicalOutputAvailability,
        ): AirPlayUiSyncRendererReadinessDescriptor {
            return complete(
                implementationId = implementationId,
                callbackDispatcher = AirPlayReadiness.READY,
                maximumPendingCallbacks = 64,
                callbackParallelism = 64,
                mayExecuteInline = false,
                physicalOfferPreflight = physicalOfferPreflight,
            )
        }

        internal fun complete(
            implementationId: String,
            callbackDispatcher: AirPlayReadiness,
            maximumPendingCallbacks: Int?,
            callbackParallelism: Int?,
            mayExecuteInline: Boolean,
            physicalOfferPreflight: UiSyncPhysicalOutputAvailability? = null,
        ): AirPlayUiSyncRendererReadinessDescriptor =
            AirPlayUiSyncRendererReadinessDescriptor(
                implementationId = implementationId,
                payloadDecoder = AirPlayReadiness.READY,
                themeReducer = AirPlayReadiness.READY,
                outputBindings = AirPlayReadiness.READY,
                outputBackend = AirPlayReadiness.READY,
                callbackDispatcher = callbackDispatcher,
                maximumPendingCallbacks = maximumPendingCallbacks,
                callbackParallelism = callbackParallelism,
                mayExecuteInline = mayExecuteInline,
                physicalOfferPreflight = physicalOfferPreflight,
            )
    }
}

/** Handler evidence captured once while constructing an AirPlay control session. */
class AirPlayUltraReadiness(
    rcsHandlers: List<AirPlayRcsHandlerReadinessDescriptor> = emptyList(),
    val uiSync: AirPlayUiSyncReadinessDescriptor? = null,
    val sessionManagement: AirPlaySessionManagementReadinessDescriptor? = null,
    val updateDataConsumer: AirPlayUpdateDataConsumerReadinessDescriptor? = null,
) {
    val rcsHandlers: List<AirPlayRcsHandlerReadinessDescriptor> =
        Collections.unmodifiableList(
            boundedReadinessHandlers(rcsHandlers),
        )

    init {
        require(
            this.rcsHandlers.map { it.clientType.uuid }.distinct().size ==
                this.rcsHandlers.size,
        ) {
            "Only one readiness descriptor may be supplied for each RCS client UUID"
        }
    }

    fun handler(clientType: RcsClientType): AirPlayRcsHandlerReadinessDescriptor? =
        rcsHandlers.firstOrNull { it.clientType.uuid == clientType.uuid }

    fun copy(
        rcsHandlers: List<AirPlayRcsHandlerReadinessDescriptor> = this.rcsHandlers,
        uiSync: AirPlayUiSyncReadinessDescriptor? = this.uiSync,
        sessionManagement: AirPlaySessionManagementReadinessDescriptor? = this.sessionManagement,
        updateDataConsumer: AirPlayUpdateDataConsumerReadinessDescriptor? = this.updateDataConsumer,
    ): AirPlayUltraReadiness = AirPlayUltraReadiness(
        rcsHandlers = rcsHandlers,
        uiSync = uiSync,
        sessionManagement = sessionManagement,
        updateDataConsumer = updateDataConsumer,
    )

    override fun equals(other: Any?): Boolean =
        other is AirPlayUltraReadiness &&
            rcsHandlers == other.rcsHandlers &&
            uiSync == other.uiSync &&
            sessionManagement == other.sessionManagement &&
            updateDataConsumer == other.updateDataConsumer

    override fun hashCode(): Int {
        var result = rcsHandlers.hashCode()
        result = 31 * result + (uiSync?.hashCode() ?: 0)
        result = 31 * result + (sessionManagement?.hashCode() ?: 0)
        result = 31 * result + (updateDataConsumer?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AirPlayUltraReadiness(" +
            "rcsHandlers=$rcsHandlers, " +
            "uiSync=$uiSync, " +
            "sessionManagement=$sessionManagement, " +
            "updateDataConsumer=$updateDataConsumer)"

    private companion object {
        const val MAXIMUM_RCS_HANDLER_DESCRIPTORS = 64

        fun boundedReadinessHandlers(
            values: Collection<AirPlayRcsHandlerReadinessDescriptor>,
        ): ArrayList<AirPlayRcsHandlerReadinessDescriptor> {
            val result = ArrayList<AirPlayRcsHandlerReadinessDescriptor>()
            val iterator = values.iterator()
            while (iterator.hasNext()) {
                require(result.size < MAXIMUM_RCS_HANDLER_DESCRIPTORS) {
                    "RCS readiness exceeds $MAXIMUM_RCS_HANDLER_DESCRIPTORS handlers"
                }
                result += iterator.next()
            }
            return result
        }
    }
}

/** Immutable evidence for the command consumer implemented directly by [AirPlaySession]. */
class AirPlaySessionManagementReadinessDescriptor internal constructor(
    val implementationId: String,
    val commandConsumer: AirPlayReadiness,
) {
    val isComplete: Boolean
        get() = implementationId == BUILT_IN_IMPLEMENTATION_ID &&
            commandConsumer == AirPlayReadiness.READY

    companion object {
        const val BUILT_IN_IMPLEMENTATION_ID = "xcertplay.airplay.stop-session.v1"

        internal fun captureBuiltIn(): AirPlaySessionManagementReadinessDescriptor =
            AirPlaySessionManagementReadinessDescriptor(
                implementationId = BUILT_IN_IMPLEMENTATION_ID,
                commandConsumer = AirPlayReadiness.READY,
            )
    }
}

/** Actual transport prerequisites for the current SETUP request. */
class AirPlayTransportReadiness(
    val secureControlChannel: Boolean,
    val eventChannel: Boolean,
    val alternateScreenSink: Boolean,
    readyRcsClientTypes: Collection<RcsClientType> = emptySet(),
    val wirelessSession: Boolean = false,
) {
    private val readyRcsSnapshot = readyRcsClientTypes.toList()
    private val readyRcsByUuid = readyRcsSnapshot.associateBy(RcsClientType::uuid)

    init {
        require(readyRcsByUuid.size == readyRcsSnapshot.size) {
            "Only one transport-readiness entry may be supplied for each RCS client UUID"
        }
    }

    val readyRcsClientTypes: Set<RcsClientType> = Collections.unmodifiableSet(
        LinkedHashSet(readyRcsByUuid.values),
    )

    val readyRcsClientTypeUuids: Set<UUID> = Collections.unmodifiableSet(
        LinkedHashSet(readyRcsByUuid.keys),
    )

    fun supportsRcsClient(clientType: RcsClientType): Boolean =
        readyRcsByUuid[clientType.uuid] == clientType

    fun supportsRcsClients(clientTypes: Collection<RcsClientType>): Boolean =
        clientTypes.isNotEmpty() && clientTypes.all(::supportsRcsClient)

    fun supportsRcsFeature(feature: AirPlayFeature): Boolean = supportsRcsClients(
        AirPlayFeatureCatalog.requiredRcsClientTypes(feature),
    )

    fun copy(
        secureControlChannel: Boolean = this.secureControlChannel,
        eventChannel: Boolean = this.eventChannel,
        alternateScreenSink: Boolean = this.alternateScreenSink,
        readyRcsClientTypes: Collection<RcsClientType> = this.readyRcsClientTypes,
        wirelessSession: Boolean = this.wirelessSession,
    ) = AirPlayTransportReadiness(
        secureControlChannel = secureControlChannel,
        eventChannel = eventChannel,
        alternateScreenSink = alternateScreenSink,
        readyRcsClientTypes = readyRcsClientTypes,
        wirelessSession = wirelessSession,
    )

    override fun equals(other: Any?): Boolean =
        other is AirPlayTransportReadiness &&
            secureControlChannel == other.secureControlChannel &&
            eventChannel == other.eventChannel &&
            alternateScreenSink == other.alternateScreenSink &&
            readyRcsClientTypes == other.readyRcsClientTypes &&
            wirelessSession == other.wirelessSession

    override fun hashCode(): Int {
        var result = secureControlChannel.hashCode()
        result = 31 * result + eventChannel.hashCode()
        result = 31 * result + alternateScreenSink.hashCode()
        result = 31 * result + readyRcsClientTypes.hashCode()
        result = 31 * result + wirelessSession.hashCode()
        return result
    }

    override fun toString(): String =
        "AirPlayTransportReadiness(" +
            "secureControlChannel=$secureControlChannel, " +
            "eventChannel=$eventChannel, " +
            "alternateScreenSink=$alternateScreenSink, " +
            "readyRcsClientTypes=$readyRcsClientTypes, " +
            "wirelessSession=$wirelessSession)"

    companion object {
        /** Compatibility path used by the existing display-only session integration. */
        fun displayOnly(config: AirPlayConfig, eventChannelAvailable: Boolean) =
            AirPlayTransportReadiness(
                secureControlChannel = true,
                eventChannel = eventChannelAvailable,
                alternateScreenSink = config.cluster != null,
                // This compatibility overload does not carry authenticated transport identity.
                // It must never be sufficient evidence for wireless-only Ultra capabilities.
                wirelessSession = false,
            )
    }
}

/** Immutable input used to calculate one session's feature capabilities. */
data class AirPlaySessionCapabilityInput(
    val config: AirPlayConfig,
    val transport: AirPlayTransportReadiness,
)
