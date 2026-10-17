package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsCatalogReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import java.util.Collections

object AirPlayFeatureCatalog {
    val all: List<AirPlayFeature> = AirPlayFeature.entries.toList()

    fun requiredRcsClientTypes(feature: AirPlayFeature): Set<RcsClientType> = when (feature) {
        AirPlayFeature.VEHICLE_STATE_PROTOCOL -> setOf(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
        )
        AirPlayFeature.UI_SYNC -> setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
        AirPlayFeature.FILE_TRANSFER -> setOf(RcsClientTypes.CAR_PLAY_UPDATE_DATA)
        else -> emptySet()
    }
}

/**
 * Immutable local implementation snapshot. Transport and the phone's proposal are intentionally
 * absent; both are intersected later without re-probing mutable factories.
 */
class AirPlayLocalCapabilitySnapshot internal constructor(
    readyFeatures: Collection<AirPlayFeature>,
    infoSidecars: Map<AirPlayFeature, Map<String, Any?>>,
    unavailableReasons: Map<AirPlayFeature, String>,
    internal val mainDdpUiSyncReady: Boolean,
    private val uiSyncDescriptor: AirPlayUiSyncReadinessDescriptor?,
    private val physicalPanels: AirPlayPhysicalDisplayPanels?,
    private val physicalPanelsLiveness: AirPlayPhysicalDisplayPanelsLiveness?,
) {
    val readyFeatureSet: Set<AirPlayFeature> =
        Collections.unmodifiableSet(LinkedHashSet(readyFeatures))
    private val infoSidecars: Map<AirPlayFeature, Map<String, Any?>> =
        Collections.unmodifiableMap(
            infoSidecars.entries.associateTo(linkedMapOf()) { (feature, sidecar) ->
                feature to freezeWireMap(sidecar)
            },
        )
    val unavailableReasons: Map<AirPlayFeature, String> =
        Collections.unmodifiableMap(LinkedHashMap(unavailableReasons))

    fun isReady(feature: AirPlayFeature): Boolean = feature in readyFeatureSet

    private fun uiSyncStillReady(): Boolean =
        uiSyncDescriptor?.isComplete == true

    private fun mainDdpUiSyncStillReady(): Boolean =
        mainDdpUiSyncReady && uiSyncStillReady() &&
            physicalPanels?.let { physicalPanelsLiveness?.isCurrentFor(it) } == true &&
            physicalPanels?.let {
                uiSyncDescriptor?.renderer?.hasCurrentMainOutputFor(it)
            } == true

    /** Recheck the exact live physical preflight without re-probing mutable RCS factories. */
    internal fun normalizeSelectedFeatures(features: MutableSet<AirPlayFeature>) {
        if (!uiSyncStillReady()) features -= AirPlayFeature.UI_SYNC
        normalizeDependencies(
            features,
            mainDdpUiSyncStillReady(),
            uiSyncDescriptor?.renderer?.requiresAlternateScreenOutput == true,
        )
    }

    internal fun infoScope(features: Collection<AirPlayFeature>): AirPlayInfoFeatureScope {
        val selected = features.filterTo(linkedSetOf()) { it in readyFeatureSet }
        normalizeSelectedFeatures(selected)
        val selectedSidecars = infoSidecars.filterKeys { it in selected }
        return AirPlayInfoFeatureScope(selected, selectedSidecars)
    }
}

/** Ready local implementations after applying this SETUP's transport prerequisites. */
class AirPlayFeatureCapabilities internal constructor(
    val local: AirPlayLocalCapabilitySnapshot,
    val transport: AirPlayTransportReadiness,
    supportedFeatures: Collection<AirPlayFeature>,
) {
    val supportedFeatureSet: Set<AirPlayFeature> =
        Collections.unmodifiableSet(LinkedHashSet(supportedFeatures))
    val supportedFeatures: List<String>
        get() = supportedFeatureSet.map(AirPlayFeature::wireName)

    fun supports(feature: AirPlayFeature): Boolean = feature in supportedFeatureSet
    fun supports(wireName: String): Boolean =
        AirPlayFeature.fromWireName(wireName)?.let(::supports) == true
}

/** Strict subset of one phone proposal, preserving the phone's order. */
class AirPlayFeatureProposal internal constructor(
    requestedFeatures: Collection<String>,
    val capabilities: AirPlayFeatureCapabilities,
    enabledFeatures: Collection<AirPlayFeature>,
) {
    val requestedFeatures: List<String> =
        Collections.unmodifiableList(ArrayList(requestedFeatures))
    val enabledFeatureSet: Set<AirPlayFeature> =
        Collections.unmodifiableSet(LinkedHashSet(enabledFeatures))
    val enabledFeatures: List<String> = requestedFeatures
        .mapNotNull(AirPlayFeature::fromWireName)
        .filter { it in enabledFeatureSet }
        .map(AirPlayFeature::wireName)
}

/** Feature scope frozen for `/info`; sidecars can never outlive their ready feature. */
class AirPlayInfoFeatureScope internal constructor(
    features: Collection<AirPlayFeature>,
    sidecars: Map<AirPlayFeature, Map<String, Any?>>,
) {
    val featureSet: Set<AirPlayFeature> =
        Collections.unmodifiableSet(LinkedHashSet(features))
    private val infoSidecars: Map<AirPlayFeature, Map<String, Any?>> =
        Collections.unmodifiableMap(
            sidecars.entries.associateTo(linkedMapOf()) { (feature, sidecar) ->
                feature to freezeWireMap(sidecar)
            },
        )

    fun includes(feature: AirPlayFeature): Boolean = feature in featureSet
    fun sidecar(feature: AirPlayFeature): Map<String, Any?>? =
        infoSidecars[feature]?.let(::copyWireMap)
}

/** A SETUP-success feature scope can no longer be backed by its exact physical output. */
internal class AirPlayFeatureScopeRevokedException : IllegalStateException(
    "AirPlay physical display proof or accepted feature scope is no longer current",
)

/**
 * Order-independent state for one control session.
 *
 * If `/info` arrives first it freezes an ordinary-only scope. No future phone feature proposal
 * is known yet, and iOS rejects an Ultra sidecar present for a feature it did not negotiate.
 * If SETUP arrives first, it freezes the negotiated subset. Repeated `/info` calls always use
 * the same scope, so display and sidecar declarations cannot change midway through a handshake.
 */
class AirPlayFeatureExchange internal constructor(
    private val local: AirPlayLocalCapabilitySnapshot,
) {
    private var proposal: AirPlayFeatureProposal? = null
    private var frozenInfoScope: AirPlayInfoFeatureScope? = null
    private var scopeRevoked = false

    @Synchronized
    fun negotiate(value: Any?, transport: AirPlayTransportReadiness): AirPlayFeatureProposal {
        if (scopeRevoked) throw AirPlayFeatureScopeRevokedException()
        proposal?.let { accepted ->
            // A later feature SETUP may legitimately repeat the same declaration. It must not
            // silently reuse an earlier, wider answer for a different phone proposal, however:
            // type-130 admission is keyed to this frozen result for the rest of the session.
            val repeated = AirPlayFeatureNegotiation.requestedFeatures(value).toSet()
            if (repeated != accepted.requestedFeatures.toSet() ||
                !AirPlayFeatureNegotiation.capabilities(local, transport)
                    .supportedFeatureSet.containsAll(accepted.enabledFeatureSet)
            ) {
                scopeRevoked = true
                throw AirPlayFeatureScopeRevokedException()
            }
            return accepted
        }
        val candidate = AirPlayFeatureNegotiation.propose(local, value, transport)
        val advertised = frozenInfoScope
        val resolved = if (advertised == null) {
            candidate
        } else {
            // `/info` already went out. Enabling a sidecar-backed feature absent from that
            // immutable response would make the handshake incomplete. Still intersect the later
            // proposal with its *actual* transport readiness, which may have changed.
            val supported = candidate.capabilities.supportedFeatureSet.filterTo(linkedSetOf()) {
                !it.requiresInfoResponseSidecar || advertised.sidecar(it) != null
            }
            local.normalizeSelectedFeatures(supported)
            val capabilities = AirPlayFeatureCapabilities(local, transport, supported)
            val enabled = candidate.enabledFeatureSet.filterTo(linkedSetOf()) {
                capabilities.supports(it)
            }
            local.normalizeSelectedFeatures(enabled)
            AirPlayFeatureProposal(candidate.requestedFeatures, capabilities, enabled)
        }
        proposal = resolved
        return resolved
    }

    @Synchronized
    fun infoScope(
        @Suppress("UNUSED_PARAMETER") transport: AirPlayTransportReadiness? = null,
    ): AirPlayInfoFeatureScope {
        if (scopeRevoked) throw AirPlayFeatureScopeRevokedException()
        frozenInfoScope?.let { frozen ->
            val stillReady = frozen.featureSet.toMutableSet()
            local.normalizeSelectedFeatures(stillReady)
            if (stillReady != frozen.featureSet) {
                scopeRevoked = true
                throw AirPlayFeatureScopeRevokedException()
            }
            return frozen
        }
        val scope = proposal?.let { accepted ->
            val current = local.infoScope(accepted.enabledFeatureSet)
            if (current.featureSet != accepted.enabledFeatureSet) {
                // Never send a successful /info that contradicts the already flushed SETUP.
                // The caller must retire this control session after reporting the failure.
                scopeRevoked = true
                throw AirPlayFeatureScopeRevokedException()
            }
            current
        } ?: run {
            // Even an authenticated transport cannot predict which Ultra features the phone
            // will request. An info-first Ultra sidecar would contradict a later strict-subset
            // SETUP, so this ordering can establish only an ordinary CarPlay session.
            val ordinary = local.readyFeatureSet.filterTo(linkedSetOf()) {
                !it.requiresInfoResponseSidecar || it == AirPlayFeature.HEVC
            }
            local.infoScope(ordinary)
        }
        frozenInfoScope = scope
        return scope
    }

    @Synchronized
    fun negotiatedFeatureSet(): Set<AirPlayFeature> = proposal?.enabledFeatureSet.orEmpty()
}

/** Pure capability and feature-negotiation functions. */
object AirPlayFeatureNegotiation {
    private val permanentlyDisabled = setOf(
        AirPlayFeature.LOG_TRANSFER,
        AirPlayFeature.VIDEO_PLAYBACK,
        AirPlayFeature.MAIN_BUFFERED,
    )

    fun exchange(config: AirPlayConfig): AirPlayFeatureExchange =
        AirPlayFeatureExchange(snapshot(config))

    fun requestedFeatures(value: Any?): List<String> = (value as? List<*>)
        .orEmpty()
        .asSequence()
        .filterIsInstance<String>()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

    fun snapshot(config: AirPlayConfig): AirPlayLocalCapabilitySnapshot {
        val ready = linkedSetOf<AirPlayFeature>()
        val sidecars = linkedMapOf<AirPlayFeature, Map<String, Any?>>()
        val unavailable = linkedMapOf<AirPlayFeature, String>()

        if (config.main.widthPixels > 0 && config.main.heightPixels > 0) {
            ready += AirPlayFeature.VIEW_AREAS
        } else {
            unavailable[AirPlayFeature.VIEW_AREAS] = "main display geometry is invalid"
        }
        if (config.hevc) {
            ready += AirPlayFeature.HEVC
            sidecars[AirPlayFeature.HEVC] = emptyMap()
        }
        // The event listener is an existing implementation; actual availability is a SETUP gate.
        ready += AirPlayFeature.IAP_CHANNEL

        val cluster = config.cluster
        if (cluster != null && cluster.widthPixels > 0 && cluster.heightPixels > 0) {
            ready += AirPlayFeature.ALT_SCREEN
            ready += AirPlayFeature.UI_CONTEXT
        } else if (cluster != null) {
            unavailable[AirPlayFeature.ALT_SCREEN] = "cluster display geometry is invalid"
            unavailable[AirPlayFeature.UI_CONTEXT] = "cluster display geometry is invalid"
        }

        val ultra = config.ultra
        // This is only a physical-panel declaration. It cannot make UI Sync ready without the
        // exact provisioned sidecar, handler, renderer receipt and current physical preflight.
        val mainDdpPanel = config.hasCurrentPhysicalDisplayPanels() &&
            config.displayPanels?.hasStandaloneMainDdpPanel(config.main, config.cluster) == true
        var mainDdpUiSyncReady = false
        if (ultra?.isConfigured(AirPlayFeature.VEHICLE_STATE_PROTOCOL) == true) {
            val problem = vehicleStateProblem(ultra)
            if (problem == null) {
                ready += AirPlayFeature.VEHICLE_STATE_PROTOCOL
                sidecars[AirPlayFeature.VEHICLE_STATE_PROTOCOL] =
                    requireNotNull(ultra.vehicleStateProtocolInfo).toWireMap()
            } else {
                unavailable[AirPlayFeature.VEHICLE_STATE_PROTOCOL] = problem
            }
        }
        if (ultra?.isConfigured(AirPlayFeature.UI_SYNC) == true) {
            val mainDdpCandidate = mainDdpPanel &&
                config.displayPanels?.let {
                    ultra.readiness.uiSync?.renderer?.hasCurrentMainOutputFor(it)
                } == true
            val problem = uiSyncProblem(ultra, ready, mainDdpCandidate)
            if (problem == null) {
                mainDdpUiSyncReady = mainDdpCandidate
                if (mainDdpUiSyncReady) ready += AirPlayFeature.UI_CONTEXT
                ready += AirPlayFeature.UI_SYNC
                sidecars[AirPlayFeature.UI_SYNC] = requireNotNull(ultra.uiSyncInfo).toWireMap()
            } else {
                unavailable[AirPlayFeature.UI_SYNC] = problem
            }
        }
        if (ultra?.isConfigured(AirPlayFeature.SESSION_MANAGEMENT) == true) {
            val problem = sessionManagementProblem(ultra)
            if (problem == null) {
                ready += AirPlayFeature.SESSION_MANAGEMENT
                sidecars[AirPlayFeature.SESSION_MANAGEMENT] =
                    requireNotNull(ultra.sessionManagementInfo).toWireMap()
            } else {
                unavailable[AirPlayFeature.SESSION_MANAGEMENT] = problem
            }
        }
        if (ultra?.isConfigured(AirPlayFeature.FILE_TRANSFER) == true) {
            val problem = fileTransferProblem(ultra)
            if (problem == null) {
                ready += AirPlayFeature.FILE_TRANSFER
                sidecars[AirPlayFeature.FILE_TRANSFER] =
                    requireNotNull(ultra.fileTransferInfo).toWireMap()
            } else {
                unavailable[AirPlayFeature.FILE_TRANSFER] = problem
            }
        }

        permanentlyDisabled.forEach { feature ->
            unavailable.putIfAbsent(feature, "complete schema and lifecycle handler are unavailable")
        }
        return AirPlayLocalCapabilitySnapshot(
            ready, sidecars, unavailable, mainDdpUiSyncReady,
            ultra?.readiness?.uiSync?.takeIf { AirPlayFeature.UI_SYNC in ready },
            config.displayPanels, config.displayPanelsLiveness,
        )
    }

    fun capabilities(input: AirPlaySessionCapabilityInput): AirPlayFeatureCapabilities =
        capabilities(snapshot(input.config), input.transport)

    fun capabilities(
        local: AirPlayLocalCapabilitySnapshot,
        transport: AirPlayTransportReadiness,
    ): AirPlayFeatureCapabilities {
        val supported = local.readyFeatureSet.filterTo(linkedSetOf()) { feature ->
            feature !in permanentlyDisabled &&
                transportReady(feature, transport)
        }
        local.normalizeSelectedFeatures(supported)
        return AirPlayFeatureCapabilities(local, transport, supported)
    }

    /** Compatibility overload for existing display-only callers. Ultra RCS remains gated off. */
    fun capabilities(config: AirPlayConfig, eventPortAvailable: Boolean): AirPlayFeatureCapabilities =
        capabilities(
            AirPlaySessionCapabilityInput(
                config,
                AirPlayTransportReadiness.displayOnly(config, eventPortAvailable),
            ),
        )

    fun propose(
        local: AirPlayLocalCapabilitySnapshot,
        requestedFeatures: Any?,
        transport: AirPlayTransportReadiness,
    ): AirPlayFeatureProposal {
        val requested = requestedFeatures(requestedFeatures)
        val capabilities = capabilities(local, transport)
        val requestedKnown = requested.mapNotNull(AirPlayFeature::fromWireName)
        val enabled = requestedKnown.filterTo(linkedSetOf()) { capabilities.supports(it) }
        local.normalizeSelectedFeatures(enabled)
        return AirPlayFeatureProposal(requested, capabilities, enabled)
    }

    fun propose(
        input: AirPlaySessionCapabilityInput,
        requestedFeatures: Any?,
    ): AirPlayFeatureProposal = propose(snapshot(input.config), requestedFeatures, input.transport)

    fun propose(
        config: AirPlayConfig,
        requestedFeatures: Any?,
        eventPortAvailable: Boolean,
    ): AirPlayFeatureProposal = propose(
        AirPlaySessionCapabilityInput(
            config,
            AirPlayTransportReadiness.displayOnly(config, eventPortAvailable),
        ),
        requestedFeatures,
    )

    private fun transportReady(
        feature: AirPlayFeature,
        transport: AirPlayTransportReadiness,
    ): Boolean {
        if (!transport.secureControlChannel) return false
        return when (feature) {
            AirPlayFeature.VIEW_AREAS,
            AirPlayFeature.HEVC -> true
            AirPlayFeature.IAP_CHANNEL,
            AirPlayFeature.UI_CONTEXT -> transport.eventChannel
            AirPlayFeature.ALT_SCREEN ->
                transport.eventChannel && transport.alternateScreenSink
            AirPlayFeature.VEHICLE_STATE_PROTOCOL ->
                transport.wirelessSession && transport.supportsRcsFeature(feature)
            AirPlayFeature.UI_SYNC ->
                transport.wirelessSession && transport.supportsRcsFeature(feature) &&
                    transport.eventChannel
            AirPlayFeature.SESSION_MANAGEMENT -> transport.wirelessSession
            AirPlayFeature.FILE_TRANSFER ->
                transport.supportsRcsFeature(feature)
            else -> false
        }
    }

    private fun fileTransferProblem(ultra: AirPlayUltraConfiguration): String? {
        if (ultra.fileTransferInfo == null) return "fileTransferInfo is missing"
        val clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA
        val handler = ultra.readiness.handler(clientType)
            ?: return "CarPlayUpdateData handler descriptor is missing"
        if (handler.clientType != clientType ||
            handler.clientType.payloadStyle != RcsPayloadStyle.UPDATE_DATA_OPACK ||
            !handler.isComplete
        ) return "CarPlayUpdateData handler descriptor is incomplete"
        val consumer = ultra.readiness.updateDataConsumer
            ?: return "bounded UpdateData quarantine consumer is missing"
        if (!consumer.matches(handler)) {
            return "bounded UpdateData quarantine consumer does not match its handler"
        }
        return null
    }

    private fun sessionManagementProblem(ultra: AirPlayUltraConfiguration): String? {
        if (ultra.sessionManagementInfo == null) {
            return "sessionManagementInfo is missing"
        }
        val descriptor = ultra.readiness.sessionManagement
            ?: return "built-in stopSession command consumer is missing"
        if (!descriptor.isComplete) {
            return "built-in stopSession command consumer is incomplete"
        }
        return null
    }

    private fun vehicleStateProblem(ultra: AirPlayUltraConfiguration): String? {
        if (ultra.vehicleStateProtocolInfo == null) {
            return "vehicleStateProtocolInfo is missing or empty"
        }
        for (clientType in AirPlayFeatureCatalog.requiredRcsClientTypes(
            AirPlayFeature.VEHICLE_STATE_PROTOCOL,
        )) {
            val descriptor = ultra.readiness.handler(clientType)
                ?: return "${clientType.name} handler descriptor is missing"
            if (descriptor.clientType != clientType ||
                descriptor.clientType.catalogReadiness != RcsCatalogReadiness.CONFIRMED_SCHEMA ||
                descriptor.clientType.payloadStyle != RcsPayloadStyle.CAF_BINARY_PLIST_OPACK ||
                !descriptor.isComplete
            ) {
                return "${clientType.name} handler descriptor is incomplete"
            }
        }
        return null
    }

    private fun uiSyncProblem(
        ultra: AirPlayUltraConfiguration,
        ready: Set<AirPlayFeature>,
        mainDdpCandidate: Boolean,
    ): String? {
        if (!mainDdpCandidate &&
            (AirPlayFeature.ALT_SCREEN !in ready || AirPlayFeature.UI_CONTEXT !in ready)
        ) {
            return "uiSync requires a preflight-bound main DDP output or the ordinary altScreen and uiContext implementations"
        }
        val info = ultra.uiSyncInfo ?: return "uiSyncInfo is missing"
        val descriptor = ultra.readiness.uiSync ?: return "UI Sync endpoint descriptor is missing"
        if (descriptor.renderer.requiresAlternateScreenOutput &&
            AirPlayFeature.ALT_SCREEN !in ready
        ) {
            return "signed physical CLUSTER output requires an advertised alternate screen"
        }
        if (!descriptor.isComplete) {
            return "UI Sync carrier/state-machine/renderer provisioning is incomplete"
        }
        if (!descriptor.matchesProvisionedSidecar(info)) {
            return "UI Sync sidecar, endpoints, and renderer receipt do not share exact provisioning"
        }
        val registered = ultra.readiness.handler(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
            ?: return "CarPlayClusterControl handler descriptor is missing"
        if (registered != descriptor.clusterControl) {
            return "UI Sync endpoint and CarPlayClusterControl descriptors do not match"
        }
        return null
    }
}

private fun normalizeDependencies(
    features: MutableSet<AirPlayFeature>,
    mainDdpUiSyncReady: Boolean,
    uiSyncRequiresAlternateScreen: Boolean,
) {
    val hasAlt = AirPlayFeature.ALT_SCREEN in features
    val hasContext = AirPlayFeature.UI_CONTEXT in features
    if (hasAlt && !hasContext) {
        features -= AirPlayFeature.ALT_SCREEN
    }
    if (hasContext && !hasAlt && !mainDdpUiSyncReady) {
        features -= AirPlayFeature.UI_CONTEXT
    }
    if ((uiSyncRequiresAlternateScreen && AirPlayFeature.ALT_SCREEN !in features) ||
        AirPlayFeature.UI_CONTEXT !in features ||
        (!mainDdpUiSyncReady && AirPlayFeature.ALT_SCREEN !in features)
    ) {
        features -= AirPlayFeature.UI_SYNC
    }
}

private fun freezeWireMap(values: Map<String, Any?>): Map<String, Any?> =
    Collections.unmodifiableMap(
        values.entries.associateTo(linkedMapOf()) { (name, value) ->
            name to freezeWireValue(value)
        },
    )

private fun freezeWireValue(value: Any?): Any? = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> Collections.unmodifiableList(value.map(::freezeWireValue))
    is Map<*, *> -> {
        val mapped = value.entries.associateTo(linkedMapOf<String, Any?>()) { (key, nested) ->
            val name = key as? String
                ?: throw IllegalArgumentException("AirPlay sidecar dictionary keys must be strings")
            name to freezeWireValue(nested)
        }
        Collections.unmodifiableMap(mapped)
    }
    else -> value
}

private fun copyWireMap(values: Map<String, Any?>): Map<String, Any?> =
    values.entries.associateTo(linkedMapOf()) { (name, value) -> name to copyWireValue(value) }

private fun copyWireValue(value: Any?): Any? = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> value.map(::copyWireValue)
    is Map<*, *> -> value.entries.associateTo(linkedMapOf<String, Any?>()) { (key, nested) ->
        (key as String) to copyWireValue(nested)
    }
    else -> value
}
