package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafErrorPolicy
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.airplay.rcs.caf.CafMutationReplayPolicy
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.ProtocolCafConfigTreeDecoderFactory
import com.shilapi.xcertplay.airplay.rcs.caf.VehicleStateCafPluginHandler
import com.shilapi.xcertplay.vehicle.ultra.VehicleControlResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateRepository
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import java.io.Closeable
import java.math.BigInteger

/** These permissions are package-digest-bound in the user's detached P-256 bench signature. */
internal data class BenchSyntheticWriteBinding(
    val iid: Long,
    val allowedValues: Set<CafFormatValue>,
)

internal data class BenchSyntheticControlBinding(
    val iid: Long,
    val targetIid: Long,
    val requestField: String,
    val responseField: String?,
    val allowedValues: Set<CafFormatValue>,
)

internal data class BenchSyntheticMutationProfile(
    val configTree: CafConfigTree,
    val pluginId: Long,
    val protocolVersion: String,
    val pluginConfig: Map<String, Any?>,
    val errors: CafErrorPolicy,
    val initialValues: Map<Long, Any?>,
    val writes: Map<Long, BenchSyntheticWriteBinding>,
    val controls: Map<Long, BenchSyntheticControlBinding>,
    val maxTransactionsPerChannel: Int,
)

/**
 * Debug-only in-memory source. It has no Android Car backend reference, property IDs, or command
 * transport. A new instance is created for each signed AA05-bound profile acquisition.
 */
internal class BenchSyntheticMutableVehicleStateRepository(
    profile: BenchSyntheticMutationProfile,
) : VehicleStateRepository, Closeable {
    override val configTree: CafConfigTree = CafConfigTree.decode(profile.configTree.root)
    private val lock = Any()
    private val errors = profile.errors
    private val writes = profile.writes.toMap()
    private val controls = profile.controls.toMap()
    private val stateValues = LinkedHashMap<Long, Any?>()
    private val subscriptions = LinkedHashSet<Subscription>()
    private var closed = false
    private var mutationCount = 0

    val isOpen: Boolean get() = synchronized(lock) { !closed }
    val appliedMutationCount: Int get() = synchronized(lock) { mutationCount }

    init {
        require(profile.initialValues.keys == configTree.characteristicsByIid.keys)
        require(profile.initialValues.size in 1..128 && writes.size <= 128 && controls.size <= 64)
        profile.initialValues.forEach { (iid, raw) ->
            stateValues[iid] = configTree.validateCharacteristicValue(iid, raw).asWireValue()
        }
    }

    override fun read(iids: Set<Long>): VehicleStateResult = synchronized(lock) {
        val found = LinkedHashMap<Long, Any?>()
        val failures = LinkedHashMap<Long, Long>()
        iids.forEach { iid ->
            when {
                closed -> failures[iid] = errors.operationFailed
                iid !in stateValues -> failures[iid] = errors.unknownIdentifier
                else -> found[iid] = stateValues[iid]
            }
        }
        VehicleStateResult(found, failures)
    }

    override fun subscribe(
        iids: Set<Long>,
        listener: VehicleStateListener,
    ): VehicleStateSubscription = synchronized(lock) {
        check(!closed) { "Synthetic CAF repository is retired" }
        require(iids.size <= 128 && subscriptions.size < 16)
        Subscription(iids.toSet(), listener).also { subscriptions += it }
    }

    override fun write(values: Map<Long, Any?>): VehicleStateResult = synchronized(lock) {
        val accepted = LinkedHashMap<Long, Any?>()
        val failures = LinkedHashMap<Long, Long>()
        values.forEach { (iid, raw) ->
            val binding = writes[iid]
            when {
                closed -> failures[iid] = errors.operationFailed
                binding == null -> failures[iid] = errors.invalidValue
                else -> {
                    val decoded = runCatching { configTree.validateCharacteristicValue(iid, raw) }
                        .getOrNull()
                    if (decoded == null || decoded !in binding.allowedValues) {
                        failures[iid] = errors.invalidValue
                    } else accepted[iid] = decoded.asWireValue()
                }
            }
        }
        // Construct complete coverage before any state change. The protocol handler's replay
        // cache then records the exact response before attempting transport publication.
        val result = VehicleStateResult(errors = failures, succeededIids = accepted.keys)
        if (accepted.isNotEmpty()) {
            stateValues.putAll(accepted)
            mutationCount += accepted.size
            emitLocked(accepted)
        }
        result
    }

    override fun control(values: Map<Long, Any?>): VehicleControlResult = synchronized(lock) {
        val accepted = LinkedHashMap<Long, Any?>()
        val responses = LinkedHashMap<Long, Any?>()
        val failures = LinkedHashMap<Long, Long>()
        val duplicateTargets = values.keys.mapNotNull { controls[it]?.targetIid }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        values.forEach { (iid, raw) ->
            val binding = controls[iid]
            when {
                closed -> failures[iid] = errors.operationFailed
                binding == null -> failures[iid] = errors.unknownIdentifier
                binding.targetIid in duplicateTargets -> failures[iid] = errors.invalidValue
                else -> {
                    val payload = raw as? Map<*, *>
                    val decoded = if (payload?.keys == setOf(binding.requestField)) {
                        runCatching {
                            configTree.validateCharacteristicValue(
                                binding.targetIid, payload[binding.requestField],
                            )
                        }.getOrNull()
                    } else null
                    if (decoded == null || decoded !in binding.allowedValues) {
                        failures[iid] = errors.invalidValue
                    } else {
                        val value = decoded.asWireValue()
                        accepted[binding.targetIid] = value
                        binding.responseField?.let { responses[iid] = mapOf(it to value) }
                    }
                }
            }
        }
        val succeeded = values.keys - failures.keys
        val result = VehicleControlResult(responses, failures, succeeded)
        if (accepted.isNotEmpty()) {
            stateValues.putAll(accepted)
            mutationCount += succeeded.size
            emitLocked(accepted)
        }
        result
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stateValues.clear()
            subscriptions.toList().forEach { subscription ->
                subscription.closed = true
                runCatching { subscription.listener.onUnavailable(subscription.iids) }
            }
            subscriptions.clear()
        }
    }

    private fun emitLocked(changed: Map<Long, Any?>) {
        subscriptions.toList().forEach { subscription ->
            if (subscription.closed) return@forEach
            val matching = changed.filterKeys { it in subscription.iids }
            if (matching.isNotEmpty()) {
                runCatching { subscription.listener.onUpdate(VehicleStateUpdate(matching)) }
            }
        }
    }

    private inner class Subscription(
        val iids: Set<Long>,
        val listener: VehicleStateListener,
    ) : VehicleStateSubscription {
        var closed = false
        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    subscriptions.remove(this)
                }
            }
        }
    }
}

private fun CafFormatValue.asWireValue(): Any = when (this) {
    is CafFormatValue.Bool -> value
    is CafFormatValue.Unsigned -> if (value <= BigInteger.valueOf(Long.MAX_VALUE)) {
        value.toLong()
    } else value
    is CafFormatValue.Signed -> value.toLong()
    is CafFormatValue.Floating -> value
    else -> throw IllegalArgumentException("Synthetic CAF only supports numeric values")
}

/** Owns one synthetic repository and a CAF factory bound to the exact signed v2 schema. */
internal class BenchSyntheticMutableCafBridge private constructor(
    private val repository: BenchSyntheticMutableVehicleStateRepository,
    val provisioning: CafRuntimeProvisioning,
) : Closeable {
    private var provisioningClosed = false
    private var repositoryClosed = false

    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        if (!provisioningClosed) {
            try {
                provisioning.close()
                provisioningClosed = true
            } catch (problem: Throwable) {
                failure = problem
            }
        }
        if (!repositoryClosed) {
            try {
                repository.close()
                repositoryClosed = true
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        fun open(profile: BenchSyntheticMutationProfile): BenchSyntheticMutableCafBridge {
            val repository = BenchSyntheticMutableVehicleStateRepository(profile)
            try {
                val tree = repository.configTree
                val handlerId = BenchCafProfileCompiler.MUTABLE_HANDLER_ID
                val expectation = CafRuntimeAuthorizationExpectation(
                    ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1.implementationId,
                    mapOf(profile.pluginId to handlerId),
                )
                val registry = CafPluginRegistry.from(listOf(CafPluginRegistration(
                    pluginId = profile.pluginId,
                    protocolVersion = profile.protocolVersion,
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handlerFactory = object : CafPluginHandlerFactory {
                        override val implementationId: String = handlerId
                        override fun create(session: CafProtocolSession): CafPluginHandler =
                            VehicleStateCafPluginHandler(
                                session, profile.pluginId, repository, profile.errors,
                                mutationReplayPolicy = CafMutationReplayPolicy(
                                    profile.maxTransactionsPerChannel,
                                ),
                                implementationId = handlerId,
                            )
                    },
                    pluginName = "xcertplay DEBUG synthetic mutable CAF — NOT OEM AUTHORIZED",
                    pluginConfig = profile.pluginConfig,
                    schemaProvider = { CafConfigTree.decode(tree.root) },
                    runtimeReady = { repository.isOpen },
                )))
                return BenchSyntheticMutableCafBridge(
                    repository,
                    CafRuntimeProvisioning.fromAuthorizedProfile(registry, expectation),
                )
            } catch (failure: Throwable) {
                repository.close()
                throw failure
            }
        }
    }
}
