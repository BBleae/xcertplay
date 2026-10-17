package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.caf.CafProvisionedSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioningOwner
import com.shilapi.xcertplay.airplay.rcs.caf.CafVehicleStateProtocolInfo
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandDirection
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnostic
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnosticKind
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRouterActions
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSessionSnapshot
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSessionState
import com.shilapi.xcertplay.airplay.rcs.uisync.DeferredCarPlayUiSyncRouterActions
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCarrierShape
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCarrierShapeDescriber
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestOutbox
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundUseStatus
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioningOwner
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncProvisionedSession
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPreparedScreenBinding
import com.shilapi.xcertplay.airplay.rcs.uisync.UI_SYNC_RCS_IMPLEMENTATION_ID
import com.shilapi.xcertplay.airplay.rcs.uisync.confirmedDirection
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.io.Closeable
import java.util.UUID

/** Construction failed after provisioning ownership transfer and cleanup still needs a retry. */
class AirPlayUltraRuntimeConstructionException internal constructor(
    cause: Throwable,
    private val cleanup: Closeable,
) : IllegalStateException("AirPlay Ultra runtime construction cleanup is pending", cause), Closeable {
    override fun close() = cleanup.close()
}

/** An exact child from a failed session open still needs retirement. [close] retries it. */
class AirPlayUltraSessionOpenCleanupException internal constructor(
    cause: Throwable,
    private val cleanup: Closeable,
) : IllegalStateException("AirPlay Ultra session-open cleanup is pending", cause), Closeable {
    override fun close() = cleanup.close()
}

/** Deterministic construction seam; production uses the exact default constructor below. */
internal fun interface AirPlayUltraSessionRuntimeFactory {
    fun create(
        baseDescriptors: Map<UUID, RcsDataStreamHandlerDescriptor>,
        legacyUiSyncRegistry: CarPlayUiSyncEndpointRegistry,
        cafSession: CafProvisionedSession?,
        uiSyncSession: UiSyncProvisionedSession?,
        onClosed: (AirPlayUltraSessionRuntime) -> Unit,
    ): AirPlayUltraSessionRuntime
}

private val DEFAULT_ULTRA_SESSION_RUNTIME_FACTORY = AirPlayUltraSessionRuntimeFactory {
        descriptors,
        registry,
        cafSession,
        uiSyncSession,
        onClosed,
    ->
    AirPlayUltraSessionRuntime(
        descriptors,
        registry,
        cafSession,
        uiSyncSession,
        onClosed,
    )
}

/**
 * Immutable provisioning for CarPlay Ultra data channels.
 *
 * The object may be shared by the host. It captures one immutable, successfully probed descriptor
 * table at construction and uses that exact table for both capability evidence and every control
 * session. This prevents configuration sidecars from describing one mutable factory state while a
 * later handler comes from another. Every session still owns a fresh runtime, and every descriptor
 * creates a fresh CAF/UI Sync handler for each type-130 channel, so reconnects never inherit
 * protocol sequence or subscription state. A supplied UI Sync provisioning transfers ownership
 * to this runtime and is closed by [close].
 */
class AirPlayUltraRuntime private constructor(
    cafFactory: RcsDataStreamHandlerFactory? = null,
    uiSyncEndpoint: CarPlayUiSyncEndpoint? = null,
    uiSyncEndpoints: Collection<CarPlayUiSyncEndpoint> = emptyList(),
    cafProvisioning: CafRuntimeProvisioning? = null,
    uiSyncProvisioning: UiSyncRuntimeProvisioning? = null,
    private val sessionRuntimeFactory: AirPlayUltraSessionRuntimeFactory,
) : Closeable {
    constructor(
        cafFactory: RcsDataStreamHandlerFactory? = null,
        uiSyncEndpoint: CarPlayUiSyncEndpoint? = null,
        uiSyncEndpoints: Collection<CarPlayUiSyncEndpoint> = emptyList(),
        cafProvisioning: CafRuntimeProvisioning? = null,
        uiSyncProvisioning: UiSyncRuntimeProvisioning? = null,
    ) : this(
        cafFactory = cafFactory,
        uiSyncEndpoint = uiSyncEndpoint,
        uiSyncEndpoints = uiSyncEndpoints,
        cafProvisioning = cafProvisioning,
        uiSyncProvisioning = uiSyncProvisioning,
        sessionRuntimeFactory = DEFAULT_ULTRA_SESSION_RUNTIME_FACTORY,
    )

    private val sessionLock = Any()
    private val sessions = IdentityHashMap<AirPlayUltraSessionRuntime, Unit>()
    private val failedSessionOpenCleanups =
        IdentityHashMap<AirPlayUltraSessionOpenCleanup, Unit>()
    private var closeRequested = false
    private var closeInFlight = false
    private var resourcesClosed = false
    private var sessionOpensInFlight = 0
    private var cafOwnerClosed = false
    private var uiSyncOwnerClosed = false
    private val legacyUiSyncEndpoints = boundedLegacyUiSyncEndpoints(
        uiSyncEndpoint,
        uiSyncEndpoints,
    )
    private val cafOwner: CafRuntimeProvisioningOwner?
    private val uiSyncOwner: UiSyncRuntimeProvisioningOwner?
    private val uiSyncRegistry: CarPlayUiSyncEndpointRegistry
    private val handlerFactory: RcsDataStreamHandlerFactory
    private val descriptorSnapshot: Map<UUID, RcsDataStreamHandlerDescriptor>
    val uiSyncEndpoints: List<CarPlayUiSyncEndpoint>
        get() = uiSyncRegistry.endpoints

    /** Compatibility view for the original one-display runtime. */
    val uiSyncEndpoint: CarPlayUiSyncEndpoint?
        get() = uiSyncRegistry.endpoints.singleOrNull()

    /** Side-effect-free parent-liveness probe used by the authorized profile revocation tree. */
    fun isLive(): Boolean {
        val caf = cafOwner
        val uiSync = uiSyncOwner
        return isOpen() &&
            (caf == null || runCatching { caf.isLive() }.getOrDefault(false)) &&
            (uiSync == null || runCatching { uiSync.isLive() }.getOrDefault(false))
    }

    init {
        var acquiredCafOwner: CafRuntimeProvisioningOwner? = null
        var acquiredUiSyncOwner: UiSyncRuntimeProvisioningOwner? = null
        try {
            require(cafFactory == null || cafProvisioning == null) {
                "Supply either legacy cafFactory or one atomic cafProvisioning, not both"
            }
            require(uiSyncProvisioning == null || legacyUiSyncEndpoints.isEmpty()) {
                "Supply either legacy UI Sync endpoints or one atomic uiSyncProvisioning, not both"
            }
            acquiredCafOwner = cafProvisioning?.transferToRuntime()
            acquiredUiSyncOwner = uiSyncProvisioning?.transferToRuntime()
            uiSyncRegistry = acquiredUiSyncOwner?.endpointTemplates()
                ?: CarPlayUiSyncEndpointRegistry(legacyUiSyncEndpoints)
            val resolvedCafFactory = acquiredCafOwner?.handlerFactory ?: cafFactory
            val uiSyncFactory = if (uiSyncRegistry.isEmpty) {
                null
            } else {
                acquiredUiSyncOwner?.let(::ProvisionedUiSyncDescriptorFactory)
                    ?: CarPlayUiSyncRcsAdapter(uiSyncRegistry)
            }
            if (resolvedCafFactory != null && uiSyncFactory != null) {
                require(
                    resolvedCafFactory.descriptor(
                        RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
                    ) == null,
                ) {
                    "The CAF factory must not also register CarPlayClusterControl"
                }
            }
            handlerFactory = CompositeRcsHandlerFactory(
                listOfNotNull(resolvedCafFactory, uiSyncFactory),
            )
            descriptorSnapshot = snapshotReadyDescriptors(handlerFactory)
            cafOwner = acquiredCafOwner
            uiSyncOwner = acquiredUiSyncOwner
            cafOwnerClosed = acquiredCafOwner == null
            uiSyncOwnerClosed = acquiredUiSyncOwner == null
        } catch (failure: Throwable) {
            val cleanup = AirPlayUltraRuntimeConstructionCleanup(
                acquiredCafOwner,
                acquiredUiSyncOwner,
            )
            val cleanupFailure = runCatching { cleanup.close() }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                throw AirPlayUltraRuntimeConstructionException(failure, cleanup)
            }
            throw failure
        }
    }

    /**
     * Builds configuration and readiness evidence from the exact factories used at runtime.
     * Calling this rather than hand-constructing readiness prevents metadata-only advertisement.
     */
    fun configuration(
        configuredFeatures: Set<AirPlayFeature>,
        vehicleStateProtocolInfo: AirPlayVehicleStateProtocolInfo? = null,
        uiSyncInfo: AirPlayUiSyncInfo? = null,
        sessionManagementInfo: AirPlaySessionManagementInfo? = null,
    ): AirPlayUltraConfiguration {
        check(isOpen()) { "AirPlay Ultra runtime is closed" }
        check(cafOwner == null || cafOwner.isLive()) {
            "CAF provisioning is not live"
        }
        check(uiSyncOwner == null || uiSyncOwner.isLive()) {
            "UI Sync renderer provisioning is not live"
        }
        val provisionedInfo = cafOwner?.vehicleStateProtocolInfo()?.let {
            AirPlayVehicleStateProtocolInfo.from(it)
        }
        require(provisionedInfo == null || vehicleStateProtocolInfo == null) {
            "CAF provisioning derives vehicleStateProtocolInfo; a separate sidecar is forbidden"
        }
        val advertisedVehicleInfo = provisionedInfo ?: vehicleStateProtocolInfo
        advertisedVehicleInfo?.let { info ->
            CafVehicleStateProtocolInfo.decode(info.toWireMap())
                .requireIndexedPluginIdsForAdvertisement()
        }
        val provisionedUiSyncInfo = uiSyncOwner?.uiSyncInfo()
        require(provisionedUiSyncInfo == null || uiSyncInfo == null) {
            "UI Sync provisioning derives uiSyncInfo; a separate sidecar is forbidden"
        }
        val frozenFactory = RcsDataStreamHandlerFactory { clientType ->
            descriptorSnapshot[clientType.uuid]?.takeIf { it.clientType == clientType }
        }
        return uiSyncOwner?.let { owner ->
            AirPlayUltraConfiguration.fromRuntimeProvisioning(
                configuredFeatures = configuredFeatures,
                vehicleStateProtocolInfo = advertisedVehicleInfo,
                uiSyncInfo = provisionedUiSyncInfo,
                sessionManagementInfo = sessionManagementInfo,
                rcsFactory = frozenFactory,
                uiSyncEndpoints = uiSyncRegistry.endpoints,
                uiSyncRendererReadiness = owner.rendererReadiness,
                uiSyncSchemaFingerprint = owner.schemaFingerprint,
                uiSyncBindingFingerprint = owner.bindingFingerprint,
                uiSyncProvisionedDisplayIds = owner.displayIds,
                uiSyncRendererReceipt = owner.rendererReadinessReceipt,
            )
        } ?: AirPlayUltraConfiguration.fromRuntimeDescriptors(
            configuredFeatures = configuredFeatures,
            vehicleStateProtocolInfo = advertisedVehicleInfo,
            uiSyncInfo = uiSyncInfo,
            sessionManagementInfo = sessionManagementInfo,
            rcsFactory = frozenFactory,
            uiSyncEndpoints = uiSyncRegistry.endpoints,
        )
    }

    /** Retain the zero-argument JVM entry for fixed product/bench runtime consumers. */
    internal fun openSession(): AirPlayUltraSessionRuntime = openSession({}, {})

    internal fun openSession(
        beforePhysicalBind: (UiSyncProvisionedSession) -> Unit,
        onPhysicalCandidateRetired: (UiSyncProvisionedSession) -> Unit,
    ): AirPlayUltraSessionRuntime {
        synchronized(sessionLock) {
            check(!closeRequested && !resourcesClosed) { "AirPlay Ultra runtime is closed" }
            check(sessionOpensInFlight < MAXIMUM_SESSION_OPENS) {
                "AirPlay Ultra runtime has too many session opens in flight"
            }
            sessionOpensInFlight = Math.addExact(sessionOpensInFlight, 1)
        }
        var cafSession: CafProvisionedSession? = null
        var uiSyncSession: UiSyncProvisionedSession? = null
        val candidate = try {
            cafSession = cafOwner?.openSession()
            uiSyncSession = uiSyncOwner?.openSession(
                onAcquired = { acquired -> uiSyncSession = acquired },
                beforePhysicalBind = beforePhysicalBind,
                onPhysicalCandidateRetired = onPhysicalCandidateRetired,
            )
            sessionRuntimeFactory.create(
                descriptorSnapshot,
                uiSyncRegistry,
                cafSession,
                uiSyncSession,
                onClosed = { session ->
                    synchronized(sessionLock) { sessions.remove(session) }
                },
            )
        } catch (failure: Throwable) {
            val cleanup = if (cafSession != null || uiSyncSession != null) {
                lateinit var retained: AirPlayUltraSessionOpenCleanup
                retained = AirPlayUltraSessionOpenCleanup(cafSession, uiSyncSession) { retired ->
                    synchronized(sessionLock) { failedSessionOpenCleanups.remove(retired) }
                }
                synchronized(sessionLock) { failedSessionOpenCleanups[retained] = Unit }
                retained
            } else {
                null
            }
            val cleanupFailure = cleanup?.let {
                runCatching { it.close() }.exceptionOrNull()
            }
            synchronized(sessionLock) {
                sessionOpensInFlight -= 1
                if (cleanupFailure != null) closeRequested = true
            }
            if (cleanupFailure != null) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                throw AirPlayUltraSessionOpenCleanupException(failure, checkNotNull(cleanup))
            }
            throw failure
        }
        val accepted = synchronized(sessionLock) {
            sessionOpensInFlight -= 1
            // Retain before admission: a failed rejected close must remain reachable by retry.
            sessions[candidate] = Unit
            !closeRequested && !resourcesClosed
        }
        if (!accepted) {
            try {
                candidate.close()
            } catch (cleanup: Throwable) {
                throw IllegalStateException("AirPlay Ultra runtime closed during session creation", cleanup)
            }
            throw IllegalStateException("AirPlay Ultra runtime is closed")
        }
        return candidate
    }

    /** Retry-safe owner close; failed children remain registered for the next call. */
    override fun close() {
        val owned = synchronized(sessionLock) {
            closeRequested = true
            if (resourcesClosed) return
            check(!closeInFlight) { "AirPlay Ultra runtime close is already in flight" }
            check(sessionOpensInFlight == 0) {
                "AirPlay Ultra session creation is still in flight"
            }
            closeInFlight = true
            sessions.keys.toList() to failedSessionOpenCleanups.keys.toList()
        }
        var failure: Throwable? = null
        owned.first.forEach { session ->
            runCatching { session.close() }.exceptionOrNull()?.let { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        owned.second.forEach { cleanup ->
            runCatching { cleanup.close() }.exceptionOrNull()?.let { error ->
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        if (synchronized(sessionLock) {
                sessions.isEmpty() && failedSessionOpenCleanups.isEmpty()
            }
        ) {
            if (!cafOwnerClosed) {
                runCatching { cafOwner?.close() }.fold(
                    onSuccess = { synchronized(sessionLock) { cafOwnerClosed = true } },
                    onFailure = { error ->
                        failure?.addSuppressed(error) ?: run { failure = error }
                    },
                )
            }
            if (!uiSyncOwnerClosed) {
                runCatching { uiSyncOwner?.close() }.fold(
                    onSuccess = { synchronized(sessionLock) { uiSyncOwnerClosed = true } },
                    onFailure = { error ->
                        failure?.addSuppressed(error) ?: run { failure = error }
                    },
                )
            }
        }
        synchronized(sessionLock) {
            closeInFlight = false
            if (failure == null && sessions.isEmpty() && failedSessionOpenCleanups.isEmpty() &&
                cafOwnerClosed && uiSyncOwnerClosed
            ) {
                resourcesClosed = true
            }
        }
        failure?.let { throw it }
    }

    private fun isOpen(): Boolean = synchronized(sessionLock) {
        !closeRequested && !resourcesClosed
    }

    companion object {
        private const val MAXIMUM_SESSION_OPENS = 1_024

        internal fun forTest(
            sessionRuntimeFactory: AirPlayUltraSessionRuntimeFactory,
            cafFactory: RcsDataStreamHandlerFactory? = null,
            uiSyncEndpoint: CarPlayUiSyncEndpoint? = null,
            uiSyncEndpoints: Collection<CarPlayUiSyncEndpoint> = emptyList(),
            cafProvisioning: CafRuntimeProvisioning? = null,
            uiSyncProvisioning: UiSyncRuntimeProvisioning? = null,
        ): AirPlayUltraRuntime = AirPlayUltraRuntime(
            cafFactory = cafFactory,
            uiSyncEndpoint = uiSyncEndpoint,
            uiSyncEndpoints = uiSyncEndpoints,
            cafProvisioning = cafProvisioning,
            uiSyncProvisioning = uiSyncProvisioning,
            sessionRuntimeFactory = sessionRuntimeFactory,
        )
    }
}

private fun boundedLegacyUiSyncEndpoints(
    single: CarPlayUiSyncEndpoint?,
    additional: Collection<CarPlayUiSyncEndpoint>,
): List<CarPlayUiSyncEndpoint> {
    val snapshot = ArrayList<CarPlayUiSyncEndpoint>(MAXIMUM_LEGACY_UI_SYNC_ENDPOINTS)
    single?.let(snapshot::add)
    val iterator = additional.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < MAXIMUM_LEGACY_UI_SYNC_ENDPOINTS) {
            "Legacy UI Sync endpoints exceed $MAXIMUM_LEGACY_UI_SYNC_ENDPOINTS entries"
        }
        snapshot += iterator.next()
    }
    return java.util.Collections.unmodifiableList(snapshot)
}

private const val MAXIMUM_LEGACY_UI_SYNC_ENDPOINTS = 64

/** Retry owner for exact children acquired by one failed [AirPlayUltraRuntime.openSession]. */
internal class AirPlayUltraSessionOpenCleanup(
    private val cafChild: Closeable?,
    private val uiSyncChild: Closeable?,
    private val onClosed: (AirPlayUltraSessionOpenCleanup) -> Unit = {},
) : Closeable {
    private val lock = Any()
    private var closeInFlight = false
    private var cafClosed = cafChild == null
    private var uiSyncClosed = uiSyncChild == null
    private var completionNotified = false

    override fun close() {
        val alreadyComplete = synchronized(lock) {
            if (cafClosed && uiSyncClosed) {
                if (completionNotified) return
                completionNotified = true
                true
            } else {
                false
            }
        }
        if (alreadyComplete) {
            onClosed(this)
            return
        }
        synchronized(lock) {
            check(!closeInFlight) { "AirPlay Ultra session-open cleanup is already in flight" }
            closeInFlight = true
        }
        var failure: Throwable? = null
        if (!synchronized(lock) { cafClosed }) {
            try {
                checkNotNull(cafChild).close()
                synchronized(lock) { cafClosed = true }
            } catch (problem: Throwable) {
                failure = combineRuntimeFailures(failure, problem)
            }
        }
        if (!synchronized(lock) { uiSyncClosed }) {
            try {
                checkNotNull(uiSyncChild).close()
                synchronized(lock) { uiSyncClosed = true }
            } catch (problem: Throwable) {
                failure = combineRuntimeFailures(failure, problem)
            }
        }
        val completed = synchronized(lock) {
            closeInFlight = false
            if (failure == null && cafClosed && uiSyncClosed && !completionNotified) {
                completionNotified = true
                true
            } else {
                false
            }
        }
        if (completed) onClosed(this)
        failure?.let { throw it }
    }
}

/** Retains the exact provisioning owners acquired before constructor publication. */
private class AirPlayUltraRuntimeConstructionCleanup(
    private val cafOwner: CafRuntimeProvisioningOwner?,
    private val uiSyncOwner: UiSyncRuntimeProvisioningOwner?,
) : Closeable {
    private val lock = Any()
    private var closeInFlight = false
    private var cafClosed = cafOwner == null
    private var uiSyncClosed = uiSyncOwner == null

    override fun close() {
        synchronized(lock) {
            if (cafClosed && uiSyncClosed) return
            check(!closeInFlight) { "AirPlay Ultra construction cleanup is already in flight" }
            closeInFlight = true
        }
        var failure: Throwable? = null
        if (!uiSyncClosed) {
            try {
                checkNotNull(uiSyncOwner).close()
                synchronized(lock) { uiSyncClosed = true }
            } catch (problem: Throwable) {
                failure = failure?.also { if (it !== problem) it.addSuppressed(problem) } ?: problem
            }
        }
        if (!cafClosed) {
            try {
                checkNotNull(cafOwner).close()
                synchronized(lock) { cafClosed = true }
            } catch (problem: Throwable) {
                failure = failure?.also { if (it !== problem) it.addSuppressed(problem) } ?: problem
            }
        }
        synchronized(lock) { closeInFlight = false }
        failure?.let { throw it }
    }
}

private fun snapshotReadyDescriptors(
    factory: RcsDataStreamHandlerFactory,
): Map<UUID, RcsDataStreamHandlerDescriptor> = buildMap {
    RcsClientTypes.ALL.forEach { clientType ->
        val descriptor = factory.descriptor(clientType) ?: return@forEach
        if (descriptor.clientType == clientType && descriptor.probe().isReady) {
            put(clientType.uuid, descriptor.withFrozenReadyProbe())
        }
    }
}.toMap()

private fun RcsDataStreamHandlerDescriptor.withFrozenReadyProbe() =
    RcsDataStreamHandlerDescriptor(
        clientType = clientType,
        implementationId = implementationId,
        readinessProbe = RcsDataStreamHandlerProbe::ready,
        handlerFactory = ::create,
    )

/** One immutable exact-UUID descriptor snapshot owned by one AirPlay control session. */
internal class AirPlayUltraSessionRuntime(
    baseDescriptors: Map<UUID, RcsDataStreamHandlerDescriptor>,
    legacyUiSyncRegistry: CarPlayUiSyncEndpointRegistry,
    private val cafSession: CafProvisionedSession?,
    private val uiSyncSession: UiSyncProvisionedSession?,
    private val onClosed: (AirPlayUltraSessionRuntime) -> Unit,
) : Closeable {
    private val lifecycleLock = Any()
    private var closeRequested = false
    private var closeInFlight = false
    private var resourcesClosed = false
    private var handlerCreationsInFlight = 0
    private var cafSessionClosed = cafSession == null
    private var uiSyncSessionClosed = uiSyncSession == null
    private val ownedHandlers = IdentityHashMap<SessionOwnedRcsHandler, Unit>()
    private val uiSyncControl = AirPlayUiSyncSessionControl()
    private val uiSyncRegistry = uiSyncSession?.endpointRegistry ?: legacyUiSyncRegistry
    private val debugDisplayAliases = uiSyncRegistry.displayIds.withIndex()
        .associate { (index, id) -> id to "d$index" }
    @Volatile private var debugStatusSink: ((String) -> Unit)? = null
    @Volatile private var debugClusterAuthenticated = false

    internal fun bindDebugStatusSink(sink: (String) -> Unit) {
        if (!UltraDebugStatusMarkers.enabled) return
        synchronized(lifecycleLock) {
            check(debugStatusSink == null && !closeRequested && !resourcesClosed)
            debugStatusSink = sink
        }
        uiSyncSession?.foregroundRequestOutbox?.setDebugStatusObserver(::debugStatus)
    }

    internal fun debugStatus(event: UltraDebugStatusEvent, displayId: String? = null) {
        if (!UltraDebugStatusMarkers.enabled) return
        if (event == UltraDebugStatusEvent.CLUSTER_AUTHENTICATED) {
            debugClusterAuthenticated = true
        }
        val sink = debugStatusSink ?: return
        UltraDebugStatusMarkers.emit(sink, event, debugDisplayAliases[displayId])
    }

    internal fun debugSessionStatus(event: UltraDebugStatusEvent) {
        if (debugClusterAuthenticated) debugStatus(event)
    }

    internal fun debugCarrierShape(shape: UiSyncCarrierShape) {
        if (!UltraDebugStatusMarkers.enabled) return
        val sink = debugStatusSink ?: return
        UltraDebugStatusMarkers.emitCarrierShape(sink, shape)
    }

    /** Debug-only: wire type of an accepted phone UI Sync command (no data). */
    internal fun debugCommand(type: Int, knownName: String?, shape: String) {
        if (!UltraDebugStatusMarkers.enabled) return
        val sink = debugStatusSink ?: return
        UltraDebugStatusMarkers.emitCommand(sink, type, knownName, shape)
    }
    private val descriptors = baseDescriptors.toMutableMap().apply {
        if (cafSession != null) {
            CAF_CLIENT_TYPES.forEach { clientType ->
                val sessionDescriptor = cafSession.handlerFactory.descriptor(clientType)
                if (sessionDescriptor != null && sessionDescriptor.probe().isReady) {
                    put(clientType.uuid, sessionDescriptor.withFrozenReadyProbe())
                } else {
                    remove(clientType.uuid)
                }
            }
        }
        if (!uiSyncRegistry.isEmpty && containsKey(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid)) {
            val sessionDescriptor = CarPlayUiSyncRcsAdapter(
                uiSyncRegistry,
                uiSyncControl,
                ::uiSyncCarrierLive,
                ::uiSyncAcquireUse,
                uiSyncSession?.foregroundRequestOutbox,
                ::uiSyncForegroundReady,
                ::debugStatus,
                ::debugCarrierShape,
                { displayId -> uiSyncSession?.invalidatePhoneEpoch(displayId) ?: true },
                { uiSyncSession?.beginTerminalUiSyncChannelStop() ?: true },
                ::debugCommand,
            )
                .descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
            if (sessionDescriptor != null && sessionDescriptor.probe().isReady) {
                put(
                    RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid,
                    sessionDescriptor.withFrozenReadyProbe(),
                )
            } else {
                remove(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid)
            }
        }
    }.toMap()

    val handlerFactory = RcsDataStreamHandlerFactory { clientType ->
        if (!componentLive(clientType)) {
            null
        } else {
            descriptors[clientType.uuid]
                ?.takeIf { it.clientType == clientType }
                ?.boundToSession(clientType)
        }
    }

    private fun RcsDataStreamHandlerDescriptor.boundToSession(
        clientType: RcsClientType,
    ): RcsDataStreamHandlerDescriptor = RcsDataStreamHandlerDescriptor(
        clientType = clientType,
        implementationId = implementationId,
        readinessProbe = {
            if (!componentLive(clientType)) {
                RcsDataStreamHandlerProbe.notReady("session_closed")
            } else {
                probe()
            }
        },
        handlerFactory = { stream ->
            createOwnedHandler(clientType, this, stream)
        },
    )

    fun supports(clientType: RcsClientType): Boolean = componentLive(clientType) &&
        descriptors[clientType.uuid]?.clientType == clientType

    fun supportsAll(clientTypes: Collection<RcsClientType>): Boolean =
        clientTypes.all(::supports)

    fun sendUiSyncCommand(
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): Boolean = command.confirmedDirection !=
        CarPlayUiSyncCommandDirection.IPHONE_TO_ACCESSORY &&
        uiSyncForegroundReady() && uiSyncControl.sendCommand(displayId, command, data, noAck)

    fun sendUiSyncCommand(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand,
        noAck: Boolean = false,
    ): Boolean = sendUiSyncCommand(displayId, command.type, command.wireData(), noAck)

    fun drainUiSyncDiagnostics(): List<CarPlayUiSyncRoutingDiagnostic> =
        uiSyncControl.drainDiagnostics()

    fun prepareScreen(handle: AirPlayVideoStreamHandle): UiSyncPreparedScreenBinding? =
        uiSyncSession?.prepareScreen(handle)

    /** Cover the exact physical batch at STOPPING, before RCS or renderer retirement. */
    internal fun coverForSessionStop(): Boolean =
        uiSyncSession?.coverForSessionStop() ?: true

    /** Keep the batch and other destinations live when type-110 alone is torn down. */
    internal fun coverMainForExactScreenTeardown(
        handle: AirPlayVideoStreamHandle,
    ): Boolean = uiSyncSession?.coverMainForExactScreenTeardown(handle) ?: true

    internal fun finishMainScreenTeardown(handle: AirPlayVideoStreamHandle) {
        uiSyncSession?.finishMainScreenTeardown(handle)
    }

    internal fun presentationSnapshot(displayId: String) =
        uiSyncSession?.snapshot(displayId)

    override fun close() {
        // Revocation is a liveness gate, not retirement: handlers still close first below, while
        // any concurrent/stale CAF operation fails closed as soon as teardown begins.
        cafSession?.revoke()
        val handlers = synchronized(lifecycleLock) {
            closeRequested = true
            if (resourcesClosed) return
            check(!closeInFlight) { "AirPlay Ultra session close is already in flight" }
            check(handlerCreationsInFlight == 0) {
                "AirPlay Ultra handler creation is still in flight"
            }
            closeInFlight = true
            ownedHandlers.keys.toList()
        }
        var failure: Throwable? = null
        handlers.forEach { handler ->
            runCatching { handler.close() }.exceptionOrNull()?.let { problem ->
                failure = failure?.also { if (it !== problem) it.addSuppressed(problem) } ?: problem
            }
        }
        val handlersRetired = failure == null
        if (handlersRetired) uiSyncControl.close()
        if (handlersRetired && !cafSessionClosed) {
            runCatching { cafSession?.close() }.fold(
                onSuccess = { synchronized(lifecycleLock) { cafSessionClosed = true } },
                onFailure = { problem -> failure = combineRuntimeFailures(failure, problem) },
            )
        }
        if (handlersRetired && !uiSyncSessionClosed) {
            runCatching { uiSyncSession?.close() }.fold(
                onSuccess = { synchronized(lifecycleLock) { uiSyncSessionClosed = true } },
                onFailure = { problem -> failure = combineRuntimeFailures(failure, problem) },
            )
        }
        val completed = synchronized(lifecycleLock) {
            closeInFlight = false
            if (failure == null && ownedHandlers.isEmpty() && cafSessionClosed &&
                uiSyncSessionClosed
            ) {
                resourcesClosed = true
                true
            } else {
                false
            }
        }
        if (completed) onClosed(this)
        failure?.let { throw it }
    }

    private fun createOwnedHandler(
        clientType: RcsClientType,
        descriptor: RcsDataStreamHandlerDescriptor,
        stream: RcsDataStream,
    ): RcsDataStreamHandler {
        check(componentLive(clientType)) { "RCS component provisioning is not live" }
        synchronized(lifecycleLock) {
            check(!closeRequested && !resourcesClosed) {
                "AirPlay Ultra session runtime is closed"
            }
            handlerCreationsInFlight = Math.addExact(handlerCreationsInFlight, 1)
        }

        val delegate = try {
            descriptor.create(stream)
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) { handlerCreationsInFlight -= 1 }
            throw failure
        }
        val onRetired: (SessionOwnedRcsHandler) -> Unit = { retired ->
            synchronized(lifecycleLock) { ownedHandlers.remove(retired) }
        }
        val owned = if (
            clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA &&
            delegate is CarPlayUpdateDataTerminalAckEvidence
        ) {
            SessionOwnedUpdateDataRcsHandler(
                delegate = delegate,
                evidence = delegate,
                exactStream = stream,
                ownerLive = { componentLive(clientType) },
                onRetired = onRetired,
            )
        } else {
            SessionOwnedRcsHandler(
                delegate = delegate,
                exactStream = stream,
                ownerLive = { componentLive(clientType) },
                onRetired = onRetired,
            )
        }
        val componentStillLive = componentLive(clientType)
        val accepted = synchronized(lifecycleLock) {
            handlerCreationsInFlight -= 1
            // Ownership is registered before testing the generation. A failed rejected-close is
            // therefore retained for the next session close attempt.
            ownedHandlers[owned] = Unit
            !closeRequested && !resourcesClosed && componentStillLive
        }
        if (!accepted) {
            val cleanup = runCatching { owned.close() }.exceptionOrNull()
            throw IllegalStateException(
                "AirPlay Ultra session runtime closed during handler creation",
                cleanup,
            )
        }
        return owned
    }

    private open class SessionOwnedRcsHandler(
        private val delegate: RcsDataStreamHandler,
        private val exactStream: RcsDataStream,
        private val ownerLive: () -> Boolean,
        private val onRetired: (SessionOwnedRcsHandler) -> Unit,
    ) : RcsDataStreamHandler {
        private val lock = Any()
        private var activeCallbacks = 0
        private var terminalDelivered = false
        private var revoked = false
        private var closeInFlight = false
        private var closed = false

        override fun onStreamOpened(stream: RcsDataStream) {
            withLiveExactCallback(stream) { delegate.onStreamOpened(stream) }
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            withLiveExactCallback(stream) { delegate.onMessage(stream, message) }
        }

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            if (stream !== exactStream) return
            val deliver = synchronized(lock) {
                if (revoked || closed || closeInFlight || terminalDelivered) {
                    false
                } else {
                    terminalDelivered = true
                    revoked = true
                    activeCallbacks = Math.addExact(activeCallbacks, 1)
                    true
                }
            }
            if (!deliver) return
            try {
                val live = try {
                    ownerLive()
                } catch (_: Throwable) {
                    false
                }
                if (live) delegate.onStreamClosed(stream, cause)
            } finally {
                synchronized(lock) {
                    check(activeCallbacks > 0) { "Session-owned RCS callback count underflow" }
                    activeCallbacks -= 1
                }
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                revoked = true
                check(!closeInFlight) { "Session-owned RCS handler close is already in flight" }
                check(activeCallbacks == 0) {
                    "Session-owned RCS handler still has callbacks in flight"
                }
                closeInFlight = true
            }
            val result = runCatching { delegate.close() }
            val retired = synchronized(lock) {
                closeInFlight = false
                if (result.isSuccess) {
                    closed = true
                    true
                } else {
                    false
                }
            }
            if (retired) onRetired(this)
            result.getOrThrow()
        }

        private inline fun withLiveExactCallback(
            stream: RcsDataStream,
            callback: () -> Unit,
        ) {
            if (stream !== exactStream) return
            synchronized(lock) {
                if (revoked || closed) return
                activeCallbacks = Math.addExact(activeCallbacks, 1)
            }
            try {
                val live = try {
                    ownerLive()
                } catch (_: Throwable) {
                    false
                }
                if (!live) {
                    synchronized(lock) { revoked = true }
                    return
                }
                callback()
            } finally {
                synchronized(lock) {
                    check(activeCallbacks > 0) { "Session-owned RCS callback count underflow" }
                    activeCallbacks -= 1
                }
            }
        }
    }

    /** Preserve the delegate's send-path evidence across the session-ownership wrapper. */
    private class SessionOwnedUpdateDataRcsHandler(
        delegate: RcsDataStreamHandler,
        private val evidence: CarPlayUpdateDataTerminalAckEvidence,
        exactStream: RcsDataStream,
        ownerLive: () -> Boolean,
        onRetired: (SessionOwnedRcsHandler) -> Unit,
    ) : SessionOwnedRcsHandler(delegate, exactStream, ownerLive, onRetired),
        CarPlayUpdateDataTerminalAckEvidence {
        override val terminalAckSent: Boolean
            get() = evidence.terminalAckSent
    }

    private fun isClosed(): Boolean = synchronized(lifecycleLock) {
        closeRequested || resourcesClosed
    }

    private fun uiSyncCarrierLive(): Boolean =
        !isClosed() && (uiSyncSession?.isCarrierLive() ?: true)

    private fun uiSyncForegroundReady(): Boolean =
        !isClosed() && (uiSyncSession?.let {
            it.foregroundUseStatus() == UiSyncForegroundUseStatus.CURRENT
        } ?: true)

    private fun cafLive(): Boolean = !isClosed() && (cafSession?.isLive() ?: true)

    private fun componentLive(clientType: RcsClientType): Boolean = when (clientType) {
        RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL -> uiSyncCarrierLive()
        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
        -> cafLive()
        else -> !isClosed()
    }

    private fun uiSyncAcquireUse(): Closeable? = if (isClosed()) {
        null
    } else if (uiSyncSession == null) {
        Closeable { }
    } else {
        uiSyncSession.acquireUse()
    }

    private companion object {
        val CAF_CLIENT_TYPES = listOf(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
        )
    }
}

private fun combineRuntimeFailures(current: Throwable?, next: Throwable): Throwable =
    current?.also { if (next !== it) it.addSuppressed(next) } ?: next

internal class AirPlayUiSyncSessionControl : Closeable {
    private val lock = Any()
    private val diagnostics = ArrayDeque<CarPlayUiSyncRoutingDiagnostic>()
    private var sender: UiSyncCommandSender? = null
    private var generation = 0L
    private var closed = false

    fun sendCommand(
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): Boolean {
        val target = synchronized(lock) {
            if (closed) return false
            val current = sender ?: return false
            current to generation
        }
        val current = target.first
        val token = target.second
        val actions = try {
            current.send(displayId, command, data, noAck)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            synchronized(lock) {
                if (!closed && generation == token && sender === current) {
                    recordLocked(listOf(CarPlayUiSyncRoutingDiagnostic(
                        CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                    )))
                }
            }
            return false
        }
        return synchronized(lock) {
            if (closed || generation != token || sender !== current) return@synchronized false
            recordLocked(actions.diagnostics)
            actions.diagnostics.isEmpty() && actions.outboundCarriers.isNotEmpty()
        }
    }

    fun drainDiagnostics(): List<CarPlayUiSyncRoutingDiagnostic> = synchronized(lock) {
        buildList(diagnostics.size) {
            while (diagnostics.isNotEmpty()) add(diagnostics.removeFirst())
        }
    }

    internal fun attach(next: UiSyncCommandSender): Closeable {
        return synchronized(lock) {
            if (closed) return@synchronized NO_OP_ATTACHMENT
            check(sender == null) { "UI Sync control is already attached" }
            generation += 1
            sender = next
            val token = generation
            Closeable {
                synchronized(lock) {
                    if (generation == token) sender = null
                }
            }
        }
    }

    internal fun record(values: Collection<CarPlayUiSyncRoutingDiagnostic>) {
        if (values.isEmpty()) return
        synchronized(lock) {
            if (!closed) recordLocked(values)
        }
    }

    private fun recordLocked(values: Collection<CarPlayUiSyncRoutingDiagnostic>) {
        values.forEach { diagnostic ->
            if (diagnostics.size == MAX_DIAGNOSTICS) diagnostics.removeFirst()
            diagnostics.addLast(diagnostic)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sender = null
            diagnostics.clear()
            generation += 1
        }
    }

    internal fun interface UiSyncCommandSender {
        fun send(
            displayId: String,
            command: CarPlayUiSyncCommandType,
            data: Any?,
            noAck: Boolean,
        ): CarPlayUiSyncRouterActions
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 64
        val NO_OP_ATTACHMENT = Closeable { }
    }
}

/** Exact-type composition; ambiguous registrations are rejected instead of selected by order. */
private class CompositeRcsHandlerFactory(
    private val delegates: List<RcsDataStreamHandlerFactory>,
) : RcsDataStreamHandlerFactory {
    override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? {
        val matches = delegates.mapNotNull { it.descriptor(clientType) }
            .filter { it.clientType == clientType }
        require(matches.size <= 1) {
            "Multiple RCS factories registered ${clientType.name}"
        }
        return matches.singleOrNull()
    }
}

/**
 * Readiness-only descriptor for an armed per-session renderer factory. The handler constructor is
 * deliberately unusable: [AirPlayUltraSessionRuntime] replaces it with a descriptor bound to the
 * freshly opened session registry before any DataStream can be created.
 */
private class ProvisionedUiSyncDescriptorFactory(
    private val owner: UiSyncRuntimeProvisioningOwner,
) : RcsDataStreamHandlerFactory {
    override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? {
        if (clientType != RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL ||
            owner.endpointTemplates().isEmpty
        ) {
            return null
        }
        return RcsDataStreamHandlerDescriptor(
            clientType = clientType,
            implementationId = UI_SYNC_RCS_IMPLEMENTATION_ID,
            readinessProbe = {
                if (owner.isLive()) RcsDataStreamHandlerProbe.ready()
                else RcsDataStreamHandlerProbe.notReady("ui_sync_factory_unavailable")
            },
            handlerFactory = {
                throw IllegalStateException(
                    "UI Sync handler creation requires a per-session renderer child",
                )
            },
        )
    }
}

/** Bridges one fresh UI Sync state machine to one fresh ClusterControl RCS channel. */
private class CarPlayUiSyncRcsAdapter(
    private val registry: CarPlayUiSyncEndpointRegistry,
    private val control: AirPlayUiSyncSessionControl? = null,
    private val isLive: () -> Boolean = { true },
    private val acquireUse: () -> Closeable? = { Closeable { } },
    private val foregroundRequestOutbox: UiSyncForegroundRequestOutbox? = null,
    private val canSendForeground: () -> Boolean = { true },
    private val debugStatus: (UltraDebugStatusEvent, String?) -> Unit = { _, _ -> },
    private val debugCarrierShape: (UiSyncCarrierShape) -> Unit = { },
    private val onDisplayEpochInvalidated: (String) -> Boolean = { true },
    private val onTerminalChannelClose: () -> Boolean = { true },
    private val debugCommand: (Int, String?, String) -> Unit = { _, _, _ -> },
) : RcsDataStreamHandlerFactory {
    override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? {
        if (clientType != RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL || registry.isEmpty) return null
        return RcsDataStreamHandlerDescriptor(
            clientType = clientType,
            implementationId = UI_SYNC_RCS_IMPLEMENTATION_ID,
            readinessProbe = {
                if (isLive() && registry.isProtocolRuntimeComplete) {
                    RcsDataStreamHandlerProbe.ready()
                } else {
                    RcsDataStreamHandlerProbe.notReady("ui_sync_runtime_unavailable")
                }
            },
            handlerFactory = { stream ->
                val use = acquireUse()
                    ?: throw IllegalStateException("UI Sync renderer provisioning is not live")
                try {
                    UiSyncRcsHandler(
                        stream, registry, control, isLive, acquireUse,
                        foregroundRequestOutbox, canSendForeground, debugStatus,
                        debugCarrierShape, onDisplayEpochInvalidated,
                        onTerminalChannelClose, debugCommand,
                    )
                } finally {
                    use.close()
                }
            },
        )
    }
}

internal class UiSyncRcsHandler(
    private val ownStream: RcsDataStream,
    registry: CarPlayUiSyncEndpointRegistry,
    private val control: AirPlayUiSyncSessionControl?,
    private val isLive: () -> Boolean,
    private val acquireUse: () -> Closeable?,
    private val foregroundRequestOutbox: UiSyncForegroundRequestOutbox?,
    private val canSendForeground: () -> Boolean,
    private val debugStatus: (UltraDebugStatusEvent, String?) -> Unit,
    debugCarrierShape: (UiSyncCarrierShape) -> Unit,
    private val onDisplayEpochInvalidated: (String) -> Boolean,
    private val onTerminalChannelClose: () -> Boolean,
    debugCommand: (Int, String?, String) -> Unit = { _, _, _ -> },
) : RcsDataStreamHandler, Closeable {
    private val displayIds = registry.displayIds
    private val outbound = SerializedUiSyncOutbound(
        registry = registry,
        debugStatus = debugStatus,
        debugCarrierShape = debugCarrierShape,
        debugCommand = debugCommand,
        onDisplayEpochInvalidated = { displayId ->
            check(onDisplayEpochInvalidated(displayId)) {
                "Exact UI Sync renderer did not revoke its old display epoch"
            }
        },
        sendCarrier = { carrier -> ownStream.send(RcsMessage.comm(carrier)) },
    )
    private var controlAttachment: Closeable? = null
    private var foregroundAttachment: UiSyncForegroundRequestOutbox.Attachment? = null
    @Volatile private var authenticatedOpened = false

    override fun onStreamOpened(stream: RcsDataStream) {
        requireOwnStream(stream)
        // RcsDataStream invokes this callback only after AEAD peer authentication. Attachment
        // failures after this point still require terminal physical batch cover.
        authenticatedOpened = true
        withLiveUse {
            debugStatus(UltraDebugStatusEvent.CLUSTER_AUTHENTICATED, null)
            outbound.open()
            try {
                foregroundAttachment = foregroundRequestOutbox?.attach(outbound) {
                    // Closing the exact stream shuts down the winning socket before callback
                    // retirement, unblocking an in-flight synchronous RCS write/flush.
                    ownStream.close()
                }
                controlAttachment = control?.attach { displayId, command, data, noAck ->
                    withLiveUse {
                        if (canSendForeground()) {
                            outbound.sendCommand(displayId, command, data, noAck)
                        } else CarPlayUiSyncRouterActions()
                    }
                }
            } catch (failure: Throwable) {
                runCatching { foregroundAttachment?.close() }.exceptionOrNull()?.let {
                    if (it !== failure) failure.addSuppressed(it)
                }
                foregroundAttachment = null
                runCatching { outbound.close() }.exceptionOrNull()?.let {
                    if (it !== failure) failure.addSuppressed(it)
                }
                throw failure
            }
        }
    }

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        requireOwnStream(stream)
        withLiveUse {
            // A foreign APTransport message is not a reason to destroy every display's UI Sync state.
            if (message.messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
                val actions = outbound.receive(message.body)
                foregroundAttachment?.refresh()
                control?.record(actions.diagnostics)
            }
        }
    }

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        if (stream === ownStream) {
            if (!authenticatedOpened) {
                close()
                return
            }
            try {
                // A shared ClusterControl socket loss ends the physical UI Sync child, including
                // CLUSTER. Same-channel phone Reset above is the recoverable, per-display case.
                // Revoke exact-batch permits BEFORE any controller callback can wait on EGL.
                check(onTerminalChannelClose()) {
                    "UI Sync terminal physical batch cover could not be scheduled"
                }
                displayIds.forEach { displayId ->
                    // A physical child is now STOPPING, so this may return false; its exact
                    // Authority-wide phase-1 revocation has already invalidated all permits.
                    runCatching { onDisplayEpochInvalidated(displayId) }
                }
            } finally {
                debugStatus(UltraDebugStatusEvent.CLUSTER_CLOSED, null)
                close()
            }
        }
    }

    override fun close() {
        foregroundAttachment?.close()
        foregroundAttachment = null
        controlAttachment?.close()
        controlAttachment = null
        outbound.close()
    }

    private fun requireOwnStream(stream: RcsDataStream) {
        require(stream === ownStream) { "UI Sync handler received another RCS channel" }
    }

    private fun <T> withLiveUse(action: () -> T): T {
        val use = acquireUse()
            ?: throw IllegalStateException("UI Sync renderer provisioning is not live")
        return try {
            check(isLive()) { "UI Sync renderer provisioning is not live" }
            action()
        } finally {
            use.close()
        }
    }
}

/** Keeps UI Sync sequence allocation and physical carrier writes in the same total order. */
internal class SerializedUiSyncOutbound(
    registry: CarPlayUiSyncEndpointRegistry,
    private val debugStatus: (UltraDebugStatusEvent, String?) -> Unit = { _, _ -> },
    /** Debug-only, value-free shape of the first rejected carrier of each diagnostic kind. */
    private val debugCarrierShape: (UiSyncCarrierShape) -> Unit = { },
    /** Debug-only: wire type (and enum name when known) of each accepted phone command. */
    private val debugCommand: (Int, String?, String) -> Unit = { _, _, _ -> },
    private val onDisplayEpochInvalidated: (String) -> Unit = { },
    private val sendCarrier: (ByteArray) -> Unit,
) : Closeable {
    constructor(
        registry: CarPlayUiSyncEndpointRegistry,
        sendCarrier: (ByteArray) -> Unit,
    ) : this(registry, { _, _ -> }, { }, { _, _, _ -> }, { }, sendCarrier)

    private val lock = Any()
    private val router = registry.openRouter()
    private val raw5PendingAcks = HashMap<String, MutableSet<Long>>()
    private val debugOwnLayoutIds = LinkedHashSet<String>()
    private val localDisplayIds = registry.endpoints.map { it.descriptor.displayId }
    private val localVehicleIds = registry.endpoints.map { it.descriptor.vehicleId }
    private val describedRejectionKinds = HashSet<CarPlayUiSyncRoutingDiagnosticKind>()
    /** A Reset reply cannot escape until the old physical scene's permit is revoked. */
    private var epochInvalidationPending = false
    private var open = false
    private var lifecycleGeneration = 0L

    /** Opens every display passively: nothing is written until the phone's own Reset arrives. */
    fun open(): CarPlayUiSyncRouterActions = synchronized(lock) {
        check(!open) { "UI Sync outbound is already open" }
        router.open().also { actions ->
            actions.outboundCarriers.forEach(sendCarrier)
            lifecycleGeneration = Math.addExact(lifecycleGeneration, 1L)
            open = true
        }
    }

    fun receive(carrier: ByteArray): CarPlayUiSyncRouterActions {
        val (turn, receivedGeneration) = synchronized(lock) {
            check(open) { "UI Sync outbound is closed" }
            check(!epochInvalidationPending) {
                "UI Sync display epoch invalidation is still in progress"
            }
            val priorStates = router.displayIds.associateWith(router::snapshot)
            val before = if (UltraDebugStatusMarkers.enabled) snapshots() else emptyMap()
            val deferred = router.receiveWithoutPresentationDelivery(carrier)
            val invalidated = router.displayIds.filter { id ->
                priorStates[id]?.state == CarPlayUiSyncSessionState.READY &&
                    router.snapshot(id)?.state ==
                        CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
            }
            val transitions = if (UltraDebugStatusMarkers.enabled) {
                receiveTransitions(before, snapshots(), deferred.actions) to
                    rejectedCarrierShapes(carrier, deferred)
            } else emptyList<Pair<UltraDebugStatusEvent, String?>>() to emptyList()
            if (invalidated.isEmpty()) {
                deferred.actions.outboundCarriers.forEach(sendCarrier)
            } else {
                epochInvalidationPending = true
            }
            Triple(deferred, transitions, invalidated) to lifecycleGeneration
        }
        val (deferred, transitions, invalidated) = turn
        // RcsDataStream serializes inbound callbacks. Revoke permits outside the sequencer lock
        // BEFORE writing the Reset reply; a blocked socket flush cannot prolong old scene use.
        // While the callback is off-lock, only another inbound callback is rejected. Other
        // displays keep independent READY/sequence spaces and may send on the shared stream.
        if (invalidated.isNotEmpty()) {
            try {
                invalidated.forEach(onDisplayEpochInvalidated)
                synchronized(lock) {
                    check(open && lifecycleGeneration == receivedGeneration) {
                        "UI Sync outbound generation changed before Reset response"
                    }
                    deferred.actions.outboundCarriers.forEach(sendCarrier)
                }
            } finally {
                synchronized(lock) { epochInvalidationPending = false }
            }
        }
        transitions.first.forEach { (event, id) -> debugStatus(event, id) }
        transitions.second.forEach { shape -> runCatching { debugCarrierShape(shape) } }
        if (UltraDebugStatusMarkers.enabled) {
            deferred.actions.presentationEvents.forEach { event ->
                val known = event as? CarPlayUiSyncPresentationEvent.KnownCommand
                // Debug-only: whether the channel attested an ACK-first exact raw-5 echo.
                val attested = if (known?.echoesAcknowledgedRequestLayout == true)
                    " ackedRequestEcho" else ""
                // Debug-only: the small type-19 enum value is needed to confirm its wire domain.
                val preference = if (known?.command ==
                    CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE
                ) ((event.data as? Map<*, *>)?.get("preference") as? Number)?.toLong()
                    ?.takeIf { it in -16L..16L }?.let { " preference=$it" }.orEmpty() else ""
                runCatching {
                    debugCommand(event.type, known?.command?.name,
                        valueFreeShape(event.data, ownLayoutIdsSnapshot()) + attested +
                            preference)
                }
            }
        }
        deferred.deliverPresentationEvents()
        return deferred.actions
    }

    /** At most one shape per rejection kind for this channel; computed while bytes are held. */
    private fun rejectedCarrierShapes(
        carrier: ByteArray,
        deferred: DeferredCarPlayUiSyncRouterActions,
    ): List<UiSyncCarrierShape> {
        if (!UltraDebugStatusMarkers.enabled) return emptyList()
        return deferred.actions.diagnostics.mapNotNull { diagnostic ->
            if (!describedRejectionKinds.add(diagnostic.kind)) return@mapNotNull null
            runCatching {
                UiSyncCarrierShapeDescriber.describe(
                    kind = diagnostic.kind,
                    carrier = carrier,
                    rejection = deferred.rejection,
                    localDisplayIds = localDisplayIds,
                    localVehicleIds = localVehicleIds,
                )
            }.getOrNull()
        }
    }

    fun sendCommand(
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): CarPlayUiSyncRouterActions = sendObserved(
        displayId,
        isRaw5 = command == CarPlayUiSyncCommandType.REQUEST_LAYOUT,
        expectsAck = !noAck,
    ) {
        router.sendCommand(displayId, command, data, noAck)
    }

    internal fun sendCommand(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand.RequestLayout,
    ): CarPlayUiSyncRouterActions {
        if (UltraDebugStatusMarkers.enabled) command.layoutID?.let { id ->
            synchronized(lock) {
                if (debugOwnLayoutIds.size >= MAXIMUM_DEBUG_OWN_LAYOUT_IDS) {
                    debugOwnLayoutIds.remove(debugOwnLayoutIds.first())
                }
                debugOwnLayoutIds += id
            }
        }
        return sendObserved(displayId, true, true) {
            router.sendCommand(displayId, command)
        }
    }

    /** Debug-only: layout IDs this Host requested, the only strings UI_COMMAND may print. */
    private fun ownLayoutIdsSnapshot(): Set<String> =
        synchronized(lock) { LinkedHashSet(debugOwnLayoutIds) }

    /** The exact router, not a transport ACK, determines each display's protocol readiness. */
    internal fun readyUiSyncDisplayIds(): Set<String> = synchronized(lock) {
        if (!open) return@synchronized emptySet()
        router.displayIds.filterTo(linkedSetOf()) { displayId ->
            router.snapshot(displayId)?.let { snapshot ->
                snapshot.open && snapshot.state == CarPlayUiSyncSessionState.READY
            } ?: false
        }
    }

    private fun sendObserved(
        displayId: String,
        isRaw5: Boolean,
        expectsAck: Boolean,
        action: () -> CarPlayUiSyncRouterActions,
    ): CarPlayUiSyncRouterActions {
        val (actions, sent) = synchronized(lock) {
            check(open) { "UI Sync outbound is closed" }
            val before = if (UltraDebugStatusMarkers.enabled) router.snapshot(displayId) else null
            val result = action().also { actions ->
                actions.outboundCarriers.forEach(sendCarrier)
            }
            val after = if (UltraDebugStatusMarkers.enabled) router.snapshot(displayId) else null
            val accepted = result.outboundCarriers.isNotEmpty() && result.diagnostics.isEmpty()
            if (accepted && isRaw5 && expectsAck && before != null && after != null) {
                val sequence = after.nextTransmitPacketSequenceNumber - 1L
                if (sequence >= before.nextTransmitPacketSequenceNumber &&
                    sequence in after.pendingAcknowledgements
                ) raw5PendingAcks.getOrPut(displayId) { linkedSetOf() }.add(sequence)
            }
            result to accepted
        }
        if (UltraDebugStatusMarkers.enabled && isRaw5) {
            debugStatus(if (sent) UltraDebugStatusEvent.RAW5_WRITE_RETURNED
                else UltraDebugStatusEvent.RAW5_REJECTED, displayId)
        }
        return actions
    }

    private fun snapshots() = router.displayIds.associateWith(router::snapshot)

    private fun receiveTransitions(
        before: Map<String, CarPlayUiSyncSessionSnapshot?>,
        after: Map<String, CarPlayUiSyncSessionSnapshot?>,
        actions: CarPlayUiSyncRouterActions,
    ): List<Pair<UltraDebugStatusEvent, String?>> {
        val events = ArrayList<Pair<UltraDebugStatusEvent, String?>>()
        actions.diagnostics.forEach { diagnostic ->
            when (diagnostic.kind) {
                CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER ->
                    events += UltraDebugStatusEvent.UI_MALFORMED_CARRIER to null
                CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY ->
                    events += UltraDebugStatusEvent.UI_UNKNOWN_DISPLAY to null
                CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR -> {
                    if (diagnostic.displayId in router.displayIds) {
                        events += UltraDebugStatusEvent.UI_PROTOCOL_REJECTED to diagnostic.displayId
                    }
                }
            }
        }
        router.displayIds.forEach { id ->
            val old = before[id] ?: return@forEach
            val current = after[id] ?: return@forEach
            if (old.state != current.state) {
                when (current.state) {
                    // Phone Reset received, our plist Reset sent in reply. A re-Reset restarts the
                    // sequence space, so earlier RequestLayout sequence numbers can no longer be
                    // acknowledged.
                    CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED -> {
                        raw5PendingAcks.remove(id)
                        // A resynchronizing Reset of our own (skipped phone psn) answers nothing.
                        if (current.accessoryResetCount == old.accessoryResetCount) {
                            events += UltraDebugStatusEvent.UI_RESET_RECEIVED to id
                        }
                        events += UltraDebugStatusEvent.UI_RESET_SENT to id
                    }
                    // Phone ResetComplete received, ours sent in reply.
                    CarPlayUiSyncSessionState.READY -> {
                        events += UltraDebugStatusEvent.UI_RESET_COMPLETE_RECEIVED to id
                        events += UltraDebugStatusEvent.UI_RESET_COMPLETE_SENT to id
                        events += UltraDebugStatusEvent.UI_READY to id
                    }
                    else -> Unit
                }
            }
            raw5PendingAcks[id]?.let { pending ->
                val acknowledged = pending.filter { sequence ->
                    sequence <= current.lastAcknowledgedPacketSequenceNumber &&
                        sequence !in current.pendingAcknowledgements
                }
                acknowledged.forEach { sequence ->
                    pending.remove(sequence)
                    events += UltraDebugStatusEvent.RAW5_ACK to id
                }
                if (pending.isEmpty()) raw5PendingAcks.remove(id)
            }
        }
        return events
    }

    override fun close() {
        synchronized(lock) {
            open = false
            lifecycleGeneration = Math.addExact(lifecycleGeneration, 1L)
            router.close()
            raw5PendingAcks.clear()
        }
    }
}

private const val MAXIMUM_DEBUG_OWN_LAYOUT_IDS = 16

/** Evidenced UI Sync command-data keys (see CarPlayUiSyncSemanticDecoder); nothing else prints. */
private val DEBUG_SHAPE_KEYS = setOf(
    "layoutID", "activeComponents", "paletteID", "fadeInNewLayout", "fadeOutOldLayout",
    "appearanceMode", "driveMode", "uiConfiguration", "regions", "current_ntp_time",
    "presentation_host_time", "presentation_ntp_time", "presentation_ntp_time_original",
    "targetComponents", "tokenID", "layoutIdentifier", "paletteIdentifier", "isTransitioning",
    "preference", "override", "targetAppearance", "configurations", "linked", "colorPaletteId",
    "wallpaperId", "layoutId",
)

/** Key names (allow-listed charset) and value type tokens only; never values. Debug diagnostics. */
internal fun valueFreeShape(
    value: Any?,
    ownLayoutIds: Set<String> = emptySet(),
    depth: Int = 0,
): String = when {
    depth > 3 -> "…"
    value == null -> "null"
    value is Map<*, *> -> value.entries.take(16).joinToString(",", "{", "}") { (key, inner) ->
        // Only known UI Sync schema keys are printed; any phone-chosen key becomes <key>.
        val safeKey = (key as? String)?.takeIf { it in DEBUG_SHAPE_KEYS } ?: "<key>"
        "$safeKey:${valueFreeShape(inner, ownLayoutIds, depth + 1)}"
    }
    value is List<*> -> "[${value.size}]"
    value is ByteArray -> "bytes"
    // Debug bench only: a string is printed only when it equals a layout ID this Host itself
    // requested from its signed Profile; any other phone string stays a type token.
    value is String && value in ownLayoutIds &&
        Regex("[a-z][a-z0-9-]{0,63}").matches(value) -> "str($value)"
    value is String -> "str"
    value is Boolean -> "bool"
    value is Number -> "num"
    else -> value.javaClass.simpleName.take(24)
}
