package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.vehicle.ultra.VehicleControlResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateRepository
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** OEM-supplied signed error values. The generic engine deliberately does not assign numbers. */
data class CafErrorPolicy(
    val unknownIdentifier: Long,
    val invalidValue: Long,
    val operationFailed: Long,
    val generalFailure: Long,
)

data class CafUpdatePolicy(
    val coalesceMillis: Long = 50,
    val maxSubscribedIids: Int = 1024,
    val maxIidsPerNotification: Int = 128,
) {
    init {
        require(coalesceMillis in 1..5_000)
        require(maxSubscribedIids > 0)
        require(maxIidsPerNotification in 1..maxSubscribedIids)
    }
}

/** Opt-in protection for a mutation-capable CAF repository; a full cache denies new mutations. */
data class CafMutationReplayPolicy(val maxTransactions: Int = 32) {
    init {
        require(maxTransactions in 1..64)
    }
}

private data class VehicleHandlerInitialization(
    val config: CafConfigTree,
    val routedCharacteristics: Map<Long, CafCharacteristicMetadata>,
    val routedControls: Map<Long, CafControlMetadata>,
)

/**
 * Per-channel vehicle-state CAF handler. The repository may be shared by Data/Data2, but this
 * instance's subscriptions, queue, response ordering, and lifecycle are isolated to its channel.
 */
class VehicleStateCafPluginHandler(
    private val session: CafProtocolSession,
    private val pluginId: Long,
    private val repository: VehicleStateRepository,
    private val errorPolicy: CafErrorPolicy,
    private val updatePolicy: CafUpdatePolicy = CafUpdatePolicy(),
    private val mutationReplayPolicy: CafMutationReplayPolicy? = null,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "caf-updates-${session.clientType.name}").apply { isDaemon = true }
    },
    override val implementationId: String = "",
) : CafSchemaBoundPluginHandler {
    private val lock = Any()
    private val mutationReplayLock = Any()
    private val mutationResponses = LinkedHashMap<Long, CachedMutationResponse>()
    private val initialization = try {
        val decoded = CafConfigTree.decode(repository.configTree.root)
        val characteristics = decoded.characteristicsByIid.filterValues {
            it.priority == session.clientType.priority?.toLong()
        }
        val controls = decoded.controlsByIid.filterValues {
            it.priority == session.clientType.priority?.toLong()
        }
        require(characteristics.isNotEmpty() || controls.isNotEmpty()) {
            "CAF plugin ${CafUInt64.toString(pluginId)} has no values for ${session.clientType.name}"
        }
        VehicleHandlerInitialization(decoded, characteristics, controls)
    } catch (failure: Throwable) {
        scheduler.shutdownNow()
        throw failure
    }
    private val config = initialization.config
    override fun schemaSnapshot(): CafConfigTree = CafConfigTree.decode(config.root)
    private val routedCharacteristics = initialization.routedCharacteristics
    private val routedControls = initialization.routedControls
    private var subscribedIids: Set<Long> = emptySet()
    private var subscription: VehicleStateSubscription? = null
    private val publishedSubscribedIids = LinkedHashSet<Long>()
    private val pendingUpdates = LinkedHashMap<Long, Any?>()
    private var flushScheduled = false
    private var notificationsEnabled = true
    private var sourceRetirementScheduled = false
    @Volatile private var closed = false

    override fun onConfigRequest(session: CafProtocolSession, message: CafMessageReader) {
        requireOwnSession(session, message)
        session.send(
            CarAccessoryMessages.configResponse(pluginId, message.requireTransactionId(), config.root),
        )
    }

    override fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader) {
        requireOwnSession(session, message)
        val requested = when (val registration = message.registration()) {
            CafRegistration.Wildcard -> routedCharacteristics.keys
            is CafRegistration.Identifiers -> registration.values.toSet()
        }
        val (accepted, unknownErrors) = partitionCharacteristics(requested)
        val current = synchronized(lock) { subscribedIids }
        val combined = current + accepted
        if (combined.size > updatePolicy.maxSubscribedIids) {
            session.send(
                CarAccessoryMessages.registerResponse(
                    pluginId,
                    message.requireTransactionId(),
                    values = emptyMap(),
                    errors = requested.associateWith { errorPolicy.operationFailed },
                ),
            )
            return
        }

        val subscriptionFailure = runCatching {
            synchronized(lock) { notificationsEnabled = false }
            replaceSubscription(combined)
        }.exceptionOrNull()
        if (subscriptionFailure != null) {
            synchronized(lock) { notificationsEnabled = true }
            session.send(
                CarAccessoryMessages.registerResponse(
                    pluginId,
                    message.requireTransactionId(),
                    values = emptyMap(),
                    errors = unknownErrors + accepted.associateWith { errorPolicy.operationFailed },
                ),
            )
            return
        }

        val result = safeRead(accepted)
        if (!admitPublication(result.values)) return
        session.send(
            CarAccessoryMessages.registerResponse(
                pluginId,
                message.requireTransactionId(),
                values = result.values,
                errors = unknownErrors + result.errors,
            ),
        )
        synchronized(lock) {
            notificationsEnabled = true
            scheduleFlushLocked()
        }
    }

    override fun onUnregisterRequest(session: CafProtocolSession, message: CafMessageReader) {
        requireOwnSession(session, message)
        val removed = message.valuesList().toSet()
        val remaining = synchronized(lock) { subscribedIids - removed }
        runCatching { replaceSubscription(remaining) }
            .getOrElse {
                session.send(
                    CarAccessoryMessages.generalError(
                        pluginId,
                        errorPolicy.generalFailure,
                        message.requireTransactionId(),
                    ),
                )
                return
            }
        session.send(
            CarAccessoryMessages.unregisterResponse(pluginId, message.requireTransactionId()),
        )
    }

    override fun onReadRequest(session: CafProtocolSession, message: CafMessageReader) {
        requireOwnSession(session, message)
        val requested = message.valuesList().toSet()
        val (accepted, unknownErrors) = partitionCharacteristics(requested)
        val result = safeRead(accepted)
        if (!admitPublication(result.values)) return
        session.send(
            CarAccessoryMessages.readResponse(
                pluginId,
                message.requireTransactionId(),
                result.values,
                unknownErrors + result.errors,
            ),
        )
    }

    override fun onWriteRequest(session: CafProtocolSession, message: CafMessageReader) {
        requireOwnSession(session, message)
        val response = mutationResponse(message) { writeResponse(message) }
        session.send(response)
    }

    private fun writeResponse(message: CafMessageReader): CafRcsFrame {
        val requested = message.valuesMap()
        val valid = LinkedHashMap<Long, Any?>()
        val errors = LinkedHashMap<Long, Long>()
        requested.forEach { (iid, value) ->
            val metadata = routedCharacteristics[iid]
            when {
                metadata == null -> errors[iid] = errorPolicy.unknownIdentifier
                metadata.writable != true && metadata.oemWritable != true ->
                    errors[iid] = errorPolicy.invalidValue
                runCatching { config.validateCharacteristicValue(iid, value) }.isFailure ->
                    errors[iid] = errorPolicy.invalidValue
                else -> valid[iid] = value
            }
        }
        val requestSnapshot = snapshotRequestValues(valid)
        val requestedIids = snapshotIids(requestSnapshot.keys)
        var result = if (requestSnapshot.isEmpty()) VehicleStateResult() else try {
            repository.write(snapshotRequestValues(requestSnapshot))
        } catch (_: Throwable) {
            VehicleStateResult(errors = requestedIids.associateWith { errorPolicy.operationFailed })
        }
        if (!isWriteResultValid(requestedIids, result)) {
            result = VehicleStateResult(
                errors = requestedIids.associateWith { errorPolicy.operationFailed },
            )
        }
        return CarAccessoryMessages.writeResponse(
            pluginId,
            message.requireTransactionId(),
            errors + result.errors,
        )
    }

    override fun onControlRequest(session: CafProtocolSession, message: CafMessageReader) {
        requireOwnSession(session, message)
        val response = mutationResponse(message) { controlResponse(message) }
        session.send(response)
    }

    private fun controlResponse(message: CafMessageReader): CafRcsFrame {
        val requested = message.valuesMap()
        val valid = LinkedHashMap<Long, Any?>()
        val errors = LinkedHashMap<Long, Long>()
        requested.forEach { (iid, payload) ->
            val metadata = routedControls[iid]
            when {
                metadata == null -> errors[iid] = errorPolicy.unknownIdentifier
                metadata.sender != CafControlSender.DEVICE ->
                    errors[iid] = errorPolicy.invalidValue
                runCatching { metadata.validateRequestPayload(payload) }.isFailure ->
                    errors[iid] = errorPolicy.invalidValue
                else -> valid[iid] = payload
            }
        }
        val requestSnapshot = snapshotRequestValues(valid)
        val requestedIids = snapshotIids(requestSnapshot.keys)
        var result = if (requestSnapshot.isEmpty()) VehicleControlResult() else try {
            repository.control(snapshotRequestValues(requestSnapshot))
        } catch (_: Throwable) {
            VehicleControlResult(errors = requestedIids.associateWith { errorPolicy.operationFailed })
        }
        var repositoryValues = result.values
        if (!isControlResultValid(requestedIids, result, repositoryValues)) {
            result = VehicleControlResult(
                errors = requestedIids.associateWith { errorPolicy.operationFailed },
            )
            repositoryValues = emptyMap()
        }
        val responseValues = LinkedHashMap<Long, Any?>()
        val responseErrors = LinkedHashMap<Long, Long>()
        responseErrors.putAll(result.errors)
        requestSnapshot.forEach { (iid, _) ->
            if (iid in responseErrors) return@forEach
            val metadata = routedControls.getValue(iid)
            if (!metadata.hasResponse) {
                return@forEach
            }
            val responsePayload = repositoryValues[iid]
            if (runCatching { metadata.validateResponsePayload(responsePayload) }.isFailure) {
                responseErrors[iid] = errorPolicy.invalidValue
            } else {
                responseValues[iid] = responsePayload
            }
        }
        return CarAccessoryMessages.controlResponse(
            pluginId,
            message.requireTransactionId(),
            responseValues,
            errors + responseErrors,
        )
    }

    private fun mutationResponse(
        message: CafMessageReader,
        operation: () -> CafRcsFrame,
    ): CafRcsFrame {
        if (mutationReplayPolicy == null) return operation()
        val transactionId = message.requireTransactionId()
        // Include the entire parsed envelope, not just validated IIDs: an unknown extension or a
        // changed invalid field is still a different request for this transaction ID.
        val requestDigest = MessageDigest.getInstance("SHA-256")
            .digest(CafRcsCodec.encode(message.envelope).rcsBody)
        synchronized(mutationReplayLock) {
            if (closed) {
                return CarAccessoryMessages.generalError(
                    pluginId, errorPolicy.operationFailed, transactionId,
                )
            }
            mutationResponses[transactionId]?.let { previous ->
                return if (MessageDigest.isEqual(previous.requestDigest, requestDigest)) {
                    previous.response.copy(rcsBody = previous.response.rcsBody.copyOf())
                } else {
                    CarAccessoryMessages.generalError(
                        pluginId, errorPolicy.invalidValue, transactionId,
                    )
                }
            }
            if (mutationResponses.size >= mutationReplayPolicy.maxTransactions) {
                return CarAccessoryMessages.generalError(
                    pluginId, errorPolicy.operationFailed, transactionId,
                )
            }
            // Record even an unexpected post-mutation failure. Replaying an unknown outcome must
            // never invoke a non-idempotent repository operation a second time.
            val response = runCatching { operation() }.getOrElse {
                CarAccessoryMessages.generalError(pluginId, errorPolicy.operationFailed, transactionId)
            }
            mutationResponses[transactionId] = CachedMutationResponse(
                requestDigest, response.copy(rcsBody = response.rcsBody.copyOf()),
            )
            return response
        }
    }

    private data class CachedMutationResponse(
        val requestDigest: ByteArray,
        val response: CafRcsFrame,
    )

    // Responses belong to transactions originated by this channel. Correlation is handled by the
    // session; an OEM-specific client can layer behavior by providing a different handler factory.
    override fun onConfigResponse(session: CafProtocolSession, message: CafMessageReader) = Unit
    override fun onRegisterResponse(session: CafProtocolSession, message: CafMessageReader) = Unit
    override fun onUnregisterResponse(session: CafProtocolSession, message: CafMessageReader) = Unit
    override fun onReadResponse(session: CafProtocolSession, message: CafMessageReader) = Unit
    override fun onWriteResponse(session: CafProtocolSession, message: CafMessageReader) = Unit
    override fun onControlResponse(session: CafProtocolSession, message: CafMessageReader) = Unit
    override fun onGeneralError(session: CafProtocolSession, message: CafMessageReader) = Unit

    override fun close() {
        val oldSubscription = synchronized(lock) {
            if (closed) return
            closed = true
            subscribedIids = emptySet()
            publishedSubscribedIids.clear()
            pendingUpdates.clear()
            flushScheduled = false
            subscription.also { subscription = null }
        }
        runCatching { oldSubscription?.close() }
        scheduler.shutdownNow()
    }

    private fun safeRead(iids: Set<Long>): VehicleStateResult {
        val requestedIids = snapshotIids(iids)
        if (requestedIids.isEmpty()) return VehicleStateResult()
        val result = try {
            repository.read(snapshotIids(requestedIids))
        } catch (_: Throwable) {
            return VehicleStateResult(
                errors = requestedIids.associateWith { errorPolicy.operationFailed },
            )
        }
        if (!isReadResultValid(requestedIids, result)) {
            return VehicleStateResult(
                errors = requestedIids.associateWith { errorPolicy.operationFailed },
            )
        }
        val validValues = LinkedHashMap<Long, Any?>()
        val errors = LinkedHashMap<Long, Long>()
        errors.putAll(result.errors)
        result.values.forEach { (iid, value) ->
            if (runCatching { config.validateCharacteristicValue(iid, value) }.isSuccess) {
                validValues[iid] = value
            } else {
                errors[iid] = errorPolicy.invalidValue
            }
        }
        (requestedIids - validValues.keys - errors.keys).forEach { iid ->
            errors[iid] = errorPolicy.operationFailed
        }
        return VehicleStateResult(validValues, errors)
    }

    private fun replaceSubscription(newIids: Set<Long>) {
        val committedIids = snapshotIids(newIids)
        val repositoryIids = snapshotIids(committedIids)
        val newSubscription = if (repositoryIids.isEmpty()) {
            null
        } else {
            repository.subscribe(repositoryIids, object : VehicleStateListener {
                override fun onUpdate(update: VehicleStateUpdate) = enqueueUpdate(update)

                override fun onUnavailable(iids: Set<Long>) = enqueueUnavailable(iids)
            })
        }
        val old = try {
            synchronized(lock) {
                check(!closed) { "CAF vehicle handler is closed" }
                subscribedIids = committedIids
                publishedSubscribedIids.retainAll(committedIids)
                pendingUpdates.keys.retainAll(committedIids)
                subscription.also { subscription = newSubscription }
            }
        } catch (failure: Throwable) {
            runCatching { newSubscription?.close() }
            throw failure
        }
        runCatching { old?.close() }
    }

    private fun enqueueUpdate(update: VehicleStateUpdate) {
        synchronized(lock) {
            if (closed || sourceRetirementScheduled) return
            update.values.forEach { (iid, value) ->
                if (iid !in subscribedIids || iid !in routedCharacteristics) return@forEach
                if (runCatching { config.validateCharacteristicValue(iid, value) }.isFailure) {
                    return@forEach
                }
                pendingUpdates[iid] = value
            }
            if (notificationsEnabled) scheduleFlushLocked()
        }
    }

    /**
     * Mark a subscribed value before sending it, then re-read from the source. A source shutdown
     * between the first read and this mark either precedes the second read (which fails) or follows
     * the mark (whose unavailability callback retires the stream). The second read also prevents a
     * queued UPDATE_NOTIFY from publishing a value after the source already disappeared.
     */
    private fun admitPublication(values: Map<Long, Any?>): Boolean {
        val admitted = synchronized(lock) {
            if (closed || sourceRetirementScheduled) return false
            values.keys.filterTo(LinkedHashSet()) { it in subscribedIids }.also {
                publishedSubscribedIids += it
            }
        }
        if (admitted.isNotEmpty()) {
            val current = safeRead(admitted)
            val unavailable = admitted - current.values.keys
            if (unavailable.isNotEmpty()) enqueueUnavailable(unavailable)
        }
        return synchronized(lock) { !closed && !sourceRetirementScheduled }
    }

    private fun enqueueUnavailable(iids: Set<Long>) {
        val retire = synchronized(lock) {
            if (closed) return
            iids.forEach(pendingUpdates::remove)
            if (sourceRetirementScheduled || publishedSubscribedIids.none { it in iids }) {
                false
            } else {
                sourceRetirementScheduled = true
                notificationsEnabled = false
                pendingUpdates.clear()
                true
            }
        }
        if (!retire) return

        // The repository invokes this callback from its polling worker while holding its own
        // per-subscription callback lock. Closing the channel there would re-enter subscription
        // cleanup and deadlock. The handler's distinct worker closes the authenticated RCS stream;
        // the peer observes EOF without any invented CAF invalid-value wire representation.
        val closeStream = Runnable { runCatching { session.stream.close() } }
        try {
            scheduler.execute(closeStream)
        } catch (_: RuntimeException) {
            if (!synchronized(lock) { closed }) {
                Thread(closeStream, "caf-source-retire-${session.clientType.name}").apply {
                    isDaemon = true
                }.start()
            }
        }
    }

    private fun scheduleFlushLocked() {
        if (closed || sourceRetirementScheduled || flushScheduled ||
            pendingUpdates.isEmpty() || !notificationsEnabled
        ) return
        flushScheduled = true
        scheduler.schedule(::flushUpdates, updatePolicy.coalesceMillis, TimeUnit.MILLISECONDS)
    }

    private fun flushUpdates() {
        val batches = synchronized(lock) {
            flushScheduled = false
            if (closed || sourceRetirementScheduled || !notificationsEnabled ||
                pendingUpdates.isEmpty()
            ) return
            val snapshot = pendingUpdates.toList()
            pendingUpdates.clear()
            snapshot.chunked(updatePolicy.maxIidsPerNotification).map { LinkedHashMap(it.toMap()) }
        }
        batches.forEach { values ->
            if (values.isNotEmpty() && admitPublication(values)) {
                session.send(CarAccessoryMessages.updateNotify(pluginId, values))
            }
        }
        synchronized(lock) { scheduleFlushLocked() }
    }

    private fun partitionCharacteristics(requested: Set<Long>): Pair<Set<Long>, Map<Long, Long>> {
        val accepted = requested.filterTo(LinkedHashSet()) { it in routedCharacteristics }
        val errors = requested.filter { it !in routedCharacteristics }
            .associateWith { errorPolicy.unknownIdentifier }
        return accepted to errors
    }

    private fun isReadResultValid(requested: Set<Long>, result: VehicleStateResult): Boolean {
        if (result.succeededIids.isNotEmpty()) return false
        val returned = result.values.keys + result.errors.keys
        return requested.containsAll(returned)
    }

    private fun isWriteResultValid(requested: Set<Long>, result: VehicleStateResult): Boolean {
        if (result.values.isNotEmpty()) return false
        return result.succeededIids + result.errors.keys == requested &&
            result.succeededIids.intersect(result.errors.keys).isEmpty()
    }

    private fun isControlResultValid(
        requested: Set<Long>,
        result: VehicleControlResult,
        responseValues: Map<Long, Any?>,
    ): Boolean {
        if (result.succeededIids + result.errors.keys != requested) return false
        if (result.succeededIids.intersect(result.errors.keys).isNotEmpty()) return false
        val responseIids = result.succeededIids.filterTo(LinkedHashSet()) { iid ->
            routedControls[iid]?.hasResponse == true
        }
        return responseValues.keys == responseIids
    }

    private fun requireOwnSession(session: CafProtocolSession, message: CafMessageReader) {
        if (session !== this.session || message.pluginId != pluginId) {
            throw CafProtocolException("CAF vehicle handler received a message for another channel/plugin")
        }
    }

    private fun snapshotIids(iids: Collection<Long>): Set<Long> =
        Collections.unmodifiableSet(LinkedHashSet(iids))

    private fun snapshotRequestValues(values: Map<Long, Any?>): Map<Long, Any?> =
        CafRepositoryRequestSnapshotter().copyRoot(values)
}

/**
 * Detaches a request twice at the repository boundary: one snapshot remains private to the
 * handler's coverage checks, while a separate read-only snapshot is handed to repository code.
 * Byte arrays are copied because the JVM has no read-only array type.
 */
private class CafRepositoryRequestSnapshotter {
    private val activeCollections = IdentityHashMap<Any, Unit>()
    private var nodes = 0
    private var entries = 0
    private var dataBytes = 0L
    private var stringChars = 0L

    fun copyRoot(values: Map<Long, Any?>): Map<Long, Any?> {
        consumeEntries(values.size)
        val result = LinkedHashMap<Long, Any?>(values.size)
        values.forEach { (iid, value) -> result[iid] = copy(value, depth = 0) }
        return Collections.unmodifiableMap(result)
    }

    private fun copy(value: Any?, depth: Int): Any? {
        require(depth <= MAX_DEPTH) { "CAF repository request exceeds maximum nesting depth" }
        require(++nodes <= MAX_NODES) { "CAF repository request exceeds maximum node count" }
        return when (value) {
            null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double,
            is BigInteger,
            -> value
            is String -> {
                stringChars = Math.addExact(stringChars, value.length.toLong())
                require(value.length <= MAX_STRING_CHARS && stringChars <= MAX_TOTAL_STRING_CHARS) {
                    "CAF repository request string limit exceeded"
                }
                value
            }
            is ByteArray -> {
                dataBytes = Math.addExact(dataBytes, value.size.toLong())
                require(value.size <= MAX_DATA_BYTES && dataBytes <= MAX_TOTAL_DATA_BYTES) {
                    "CAF repository request data limit exceeded"
                }
                value.copyOf()
            }
            is List<*> -> whileCollectionActive(value) {
                consumeEntries(value.size)
                Collections.unmodifiableList(value.map { copy(it, depth + 1) })
            }
            is Map<*, *> -> whileCollectionActive(value) {
                consumeEntries(value.size)
                val result = LinkedHashMap<Any?, Any?>(value.size)
                value.forEach { (key, item) ->
                    require(key is String || key is Number || key is Boolean) {
                        "CAF repository request dictionary key type is unsupported"
                    }
                    result[key] = copy(item, depth + 1)
                }
                Collections.unmodifiableMap(result)
            }
            else -> throw IllegalArgumentException(
                "CAF repository request value ${value::class.java.simpleName} is unsupported",
            )
        }
    }

    private fun consumeEntries(count: Int) {
        require(count <= MAX_COLLECTION_ENTRIES) { "CAF repository request collection is too large" }
        entries = Math.addExact(entries, count)
        require(entries <= MAX_TOTAL_COLLECTION_ENTRIES) {
            "CAF repository request aggregate collection limit exceeded"
        }
    }

    private inline fun <T> whileCollectionActive(value: Any, block: () -> T): T {
        require(activeCollections.put(value, Unit) == null) {
            "CAF repository request contains a cycle"
        }
        return try {
            block()
        } finally {
            activeCollections.remove(value)
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_NODES = 16_384
        const val MAX_COLLECTION_ENTRIES = 4_096
        const val MAX_TOTAL_COLLECTION_ENTRIES = 16_384
        const val MAX_STRING_CHARS = 16 * 1024
        const val MAX_TOTAL_STRING_CHARS = 256L * 1024L
        const val MAX_DATA_BYTES = 256 * 1024
        const val MAX_TOTAL_DATA_BYTES = 1024L * 1024L
    }
}
