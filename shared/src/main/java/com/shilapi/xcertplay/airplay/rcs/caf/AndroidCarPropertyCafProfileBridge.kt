package com.shilapi.xcertplay.airplay.rcs.caf

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.vehicle.AndroidCarPropertyBackend
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyCafBinding
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyCafPollingPolicy
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyVehicleStateRepository
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import java.io.Closeable
import java.util.Collections

/**
 * Exact, externally verified profile inputs for the Android Car read-only CAF adapter.
 *
 * This value does not verify a signature, OEM identity, vehicle pairing, IID meaning, unit, or
 * Android property permission. Its caller must establish those facts before using the resulting
 * provisioning in a product session. No production mapping or error number is built in here.
 */
class AndroidCarPropertyCafProfile(
    profileConfigTree: CafConfigTree,
    val pluginId: Long,
    val protocolVersion: String,
    pluginConfig: Map<String, Any?>,
    bindings: List<AndroidCarPropertyCafBinding>,
    val errorPolicy: CafErrorPolicy,
    val decoderImplementationId: String,
    val handlerImplementationId: String,
    val pluginName: String? = null,
) {
    private val frozenTree = CafConfigTree.decode(freezeCafWireMap(profileConfigTree.root))
    private val frozenPluginConfig = freezeCafWireMap(pluginConfig)
    val bindings: List<AndroidCarPropertyCafBinding> =
        Collections.unmodifiableList(ArrayList(bindings))

    val configTree: CafConfigTree get() = CafConfigTree.decode(frozenTree.root)
    val wirePluginConfig: Map<String, Any?> get() = freezeCafWireMap(frozenPluginConfig)

    init {
        require(CafUInt64.parse(frozenPluginConfig["pluginID"], "CAF profile pluginID") == pluginId) {
            "CAF profile pluginConfig must carry its exact pluginID"
        }
        require(frozenTree.controlsByIid.isEmpty()) {
            "Android Car property bridge cannot advertise CAF controls"
        }
        require(frozenTree.characteristicsByIid.values.none {
            it.writable == true || it.oemWritable == true
        }) {
            "Android Car property bridge cannot advertise writable CAF characteristics"
        }
        require(frozenTree.characteristicsByIid.values.map { it.priority }.toSet() ==
            setOf(0L, 1L)
        ) {
            "Read-only CAF profile needs characteristic routes on both DATA priorities"
        }
        require(this.bindings.map { it.iid }.toSet() == frozenTree.characteristicsByIid.keys &&
            this.bindings.size == frozenTree.characteristicsByIid.size
        ) {
            "Every advertised CAF characteristic needs one explicit Android Car binding"
        }
        // Both IDs must be nonempty and bounded even before backend construction begins.
        CafRuntimeAuthorizationExpectation(
            decoderImplementationId,
            mapOf(pluginId to handlerImplementationId),
        )
    }
}

/** Failed construction retains the exact backend/repository if its first close also failed. */
class AndroidCarPropertyCafBridgeConstructionException internal constructor(
    cause: Throwable,
    private val cleanup: AutoCloseable,
    cleanupFailure: Throwable,
) : IllegalStateException("Android Car CAF bridge construction cleanup is pending", cause), Closeable {
    init { addSuppressed(cleanupFailure) }

    override fun close() = cleanup.close()
}

/**
 * Owns one real Android Car repository and one authorized-profile CAF provisioning.
 *
 * `provisioning` is protocol-response capable, **not** a claim that Android Car currently supplies
 * live values. An accepted asynchronous bind may still be waiting; every read then returns the
 * profile's signed `operationFailed` value. A later disconnect also invalidates reads immediately.
 * DATA and DATA2 get fresh handlers and subscriptions while sharing only the read-only repository.
 * Product code must still separately prove OEM/profile authority and vehicle identity.
 *
 * If `provisioning` transfers to an AirPlay runtime, that runtime owns its retirement. Closing this
 * bridge still revokes vehicle values by closing the repository; it cannot close the transferred
 * runtime owner. Call [close] again if an untransferred provisioning or backend close fails.
 */
class AndroidCarPropertyCafProfileBridge private constructor(
    private val repository: AndroidCarPropertyVehicleStateRepository,
    val provisioning: CafRuntimeProvisioning,
) : Closeable {
    private val closeLock = Any()
    private var closeRequested = false
    private var closeInFlight = false
    private var provisioningClosed = false
    private var repositoryClosed = false
    private var authorizationBound = false

    /** The local instrument consumer sees this same CAF repository, never its mutation port. */
    private val instrumentSource = object : VehicleStateReadSource {
        override val configTree: CafConfigTree get() = repository.configTree

        override val isCurrent: Boolean
            get() = synchronized(closeLock) { !closeRequested } &&
                repository.isProtocolResponseAvailable &&
                repository.isAuthorizationCurrent &&
                repository.isAndroidCarServiceConnected &&
                synchronized(closeLock) { !closeRequested }

        override fun read(iids: Set<Long>): VehicleStateResult {
            check(isCurrent) { "Android Car CAF source is retired" }
            val result = repository.read(iids)
            check(isCurrent) { "Android Car CAF source retired during read" }
            return result
        }

        override fun subscribe(
            iids: Set<Long>,
            listener: VehicleStateListener,
        ): VehicleStateSubscription {
            check(isCurrent) { "Android Car CAF source is retired" }
            val subscription = repository.subscribe(iids, object : VehicleStateListener {
                override fun onUpdate(update: VehicleStateUpdate) {
                    if (isCurrent) listener.onUpdate(update)
                    else listener.onUnavailable(iids)
                }

                override fun onUnavailable(iids: Set<Long>) =
                    listener.onUnavailable(iids)
            })
            if (!isCurrent) {
                subscription.close()
                throw IllegalStateException("Android Car CAF source retired during subscribe")
            }
            return subscription
        }
    }

    val readOnlyInstrumentSource: VehicleStateReadSource get() = instrumentSource

    /** Service connection only; property permissions and individual value availability can differ. */
    val isAndroidCarServiceConnected: Boolean
        get() = synchronized(closeLock) { !closeRequested } &&
            repository.isAndroidCarServiceConnected &&
            synchronized(closeLock) { !closeRequested }

    /** Tightens a constructed bridge once; a denied predicate permanently revokes this owner. */
    fun bindRuntimeAuthorization(current: () -> Boolean) {
        synchronized(closeLock) {
            check(!closeRequested && !authorizationBound) {
                "Android Car CAF runtime authorization is already bound or closed"
            }
            authorizationBound = true
        }
        repository.bindCurrentAuthorization(current)
    }

    /** Read-only liveness observation; a denied grant latches and retires both CAF streams. */
    fun observeRuntimeAuthorization(): Boolean {
        if (synchronized(closeLock) { closeRequested }) return false
        val sourceCurrent = repository.isAuthorizationCurrent
        val runtimeCurrent = provisioning.observeCurrentAuthorization()
        return sourceCurrent && runtimeCurrent &&
            synchronized(closeLock) { !closeRequested }
    }

    override fun close() {
        synchronized(closeLock) {
            if (provisioningClosed && repositoryClosed) return
            check(!closeInFlight) { "Android Car CAF bridge close is already in flight" }
            closeRequested = true
            closeInFlight = true
        }
        var failure: Throwable? = null
        try {
            if (!synchronized(closeLock) { provisioningClosed }) {
                try {
                    provisioning.close()
                    synchronized(closeLock) { provisioningClosed = true }
                } catch (problem: Throwable) {
                    failure = problem
                }
            }
            if (!synchronized(closeLock) { repositoryClosed }) {
                try {
                    repository.close()
                    synchronized(closeLock) { repositoryClosed = true }
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem
                    else if (failure !== problem) failure.addSuppressed(problem)
                }
            }
        } finally {
            synchronized(closeLock) { closeInFlight = false }
        }
        failure?.let { throw it }
    }

    companion object {
        /** Creates the actual reflection-backed Android Car source; never enables CAF by itself. */
        fun forAndroid(
            context: Context,
            profile: AndroidCarPropertyCafProfile,
            decoderFactory: CafConfigTreeDecoderFactory =
                ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            pollingPolicy: AndroidCarPropertyCafPollingPolicy = AndroidCarPropertyCafPollingPolicy(),
        ): AndroidCarPropertyCafProfileBridge = fromBackend(
            profile,
            AndroidCarPropertyBackend(context),
            decoderFactory,
            pollingPolicy,
        )

        /** Injectable backend seam for a validated vehicle provider and deterministic tests. */
        fun fromBackend(
            profile: AndroidCarPropertyCafProfile,
            backend: ReadOnlyCarPropertyBackend,
            decoderFactory: CafConfigTreeDecoderFactory =
                ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            pollingPolicy: AndroidCarPropertyCafPollingPolicy = AndroidCarPropertyCafPollingPolicy(),
        ): AndroidCarPropertyCafProfileBridge = fromBackendWithClock(
            profile, backend, decoderFactory, pollingPolicy, SystemClock::elapsedRealtime,
        )

        /** Internal clock seam for deterministic bridge tests; signed profiles never select it. */
        internal fun fromBackendWithClock(
            profile: AndroidCarPropertyCafProfile,
            backend: ReadOnlyCarPropertyBackend,
            decoderFactory: CafConfigTreeDecoderFactory,
            pollingPolicy: AndroidCarPropertyCafPollingPolicy,
            elapsedRealtimeMillis: () -> Long,
        ): AndroidCarPropertyCafProfileBridge {
            var cleanup: AutoCloseable = backend
            try {
                val tree = profile.configTree
                val pluginConfig = profile.wirePluginConfig
                val expectation = CafRuntimeAuthorizationExpectation(
                    profile.decoderImplementationId,
                    mapOf(profile.pluginId to profile.handlerImplementationId),
                )
                require(decoderFactory.implementationId == expectation.decoderImplementationId) {
                    "Android Car CAF decoder implementation does not match the verified profile"
                }
                val decoded = decoderFactory.create(profile.protocolVersion, pluginConfig)
                    .decode(tree.root)
                require(
                    CarAccessoryMessages.configResponse(profile.pluginId, 1L, tree.root)
                        .rcsBody.contentEquals(
                            CarAccessoryMessages.configResponse(profile.pluginId, 1L, decoded.root)
                                .rcsBody,
                        )
                ) {
                    "Android Car CAF decoder changes the verified profile schema"
                }

                val repository = AndroidCarPropertyVehicleStateRepository(
                    tree,
                    profile.bindings,
                    backend,
                    profile.errorPolicy,
                    pollingPolicy,
                    elapsedRealtimeMillis,
                )
                cleanup = repository
                check(repository.start()) {
                    "Android Car CAF property service could not begin connecting"
                }
                val factory = object : CafPluginHandlerFactory {
                    override val implementationId: String = profile.handlerImplementationId

                    override fun create(session: CafProtocolSession): CafPluginHandler =
                        VehicleStateCafPluginHandler(
                            session = session,
                            pluginId = profile.pluginId,
                            repository = repository,
                            errorPolicy = profile.errorPolicy,
                            implementationId = implementationId,
                        )
                }
                val registry = CafPluginRegistry.from(
                    listOf(
                        CafPluginRegistration(
                            pluginId = profile.pluginId,
                            protocolVersion = profile.protocolVersion,
                            clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                            handlerFactory = factory,
                            pluginName = profile.pluginName,
                            pluginConfig = pluginConfig,
                            schemaProvider = { CafConfigTree.decode(tree.root) },
                            // This says only that the protocol can answer with a real value or
                            // signed operationFailed. Android Car connection is separately live.
                            runtimeReady = { repository.isProtocolResponseAvailable },
                        ),
                    ),
                )
                val provisioning = CafRuntimeProvisioning.fromAuthorizedProfile(
                    registry,
                    expectation,
                    decoderFactory,
                    currentAuthorization = { repository.isAuthorizationCurrent },
                )
                return AndroidCarPropertyCafProfileBridge(repository, provisioning)
            } catch (failure: Throwable) {
                val cleanupFailure = runCatching { cleanup.close() }.exceptionOrNull()
                if (cleanupFailure != null) {
                    throw AndroidCarPropertyCafBridgeConstructionException(
                        failure,
                        cleanup,
                        cleanupFailure,
                    )
                }
                throw failure
            }
        }
    }
}
