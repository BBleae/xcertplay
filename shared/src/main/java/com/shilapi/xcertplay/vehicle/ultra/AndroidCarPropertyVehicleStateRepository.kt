package com.shilapi.xcertplay.vehicle.ultra

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafErrorPolicy
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.vehicle.AndroidCarPropertyBackend
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A deployment/profile-provided CAF IID bound to one exact Android Car property and area. */
data class AndroidCarPropertyCafBinding(
    val iid: Long,
    val propertyId: Int,
    val areaId: Int,
    val speedProjection: SpeedUnitProjection? = null,
)

data class AndroidCarPropertyCafPollingPolicy(
    val intervalMillis: Long = 200,
    val maxSubscriptions: Int = 16,
    val maxIidsPerSubscription: Int = 128,
    val maxDistinctSubscribedIids: Int = 256,
) {
    init {
        require(intervalMillis in 20..5_000)
        require(maxSubscriptions in 1..64)
        require(maxIidsPerSubscription in 1..1_024)
        require(maxDistinctSubscribedIids in maxIidsPerSubscription..1_024)
    }
}

/**
 * Read-only CAF adapter for Android Car property data.
 *
 * The caller must supply an authorized profile's CAF tree, exact IID/property/area bindings, and
 * signed CAF error values. Nothing in this adapter infers property IDs, areas, units, CAF IIDs, or
 * error numbers. Constructing it does not authorize or advertise CAF; the product must separately prove
 * the profile, vehicle identity, and protocol/renderer gates before exposing a CAF channel.
 *
 * [start] initiates the asynchronous Android Car connection. Until [ReadOnlyCarPropertyBackend]
 * reports connected, every mapped read fails with [CafErrorPolicy.operationFailed]. Subscriptions
 * poll on a single bounded background worker, never inline with registration.
 */
class AndroidCarPropertyVehicleStateRepository(
    profileConfigTree: CafConfigTree,
    bindings: List<AndroidCarPropertyCafBinding>,
    private val backend: ReadOnlyCarPropertyBackend,
    private val errorPolicy: CafErrorPolicy,
    private val pollingPolicy: AndroidCarPropertyCafPollingPolicy = AndroidCarPropertyCafPollingPolicy(),
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : ReadOnlyVehicleStateRepository(profileConfigTree.snapshotForRepository(), errorPolicy.invalidValue),
    AutoCloseable {
    private val lock = Any()
    private val backendReadLock = Any()
    private val backendCloseLock = Any()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "caf-car-property-read-only").apply { isDaemon = true }
    }
    private val propertyByIid: Map<Long, AndroidCarPropertyCafBinding> = freezeBindings(bindings)
    private val subscriptions = LinkedHashSet<PollingSubscription>()
    private var polling: ScheduledFuture<*>? = null
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var backendClosed = false
    @Volatile private var authorizationProbe: () -> Boolean = { true }
    private val authorizationRevoked = AtomicBoolean(false)
    private var authorizationBound = false

    /** CAF can answer requests, including signed operationFailed, after a bind was initiated. */
    val isProtocolResponseAvailable: Boolean get() = started && !closed

    /** A live permission may only revoke this source; it cannot revive the same deployment. */
    val isAuthorizationCurrent: Boolean get() {
        if (authorizationRevoked.get()) return false
        val allowed = runCatching(authorizationProbe).getOrDefault(false)
        if (!allowed) authorizationRevoked.set(true)
        return allowed && !authorizationRevoked.get()
    }

    /** One-way authorization binding, installed before a Debug deployment publishes its bridge. */
    fun bindCurrentAuthorization(predicate: () -> Boolean) {
        synchronized(lock) {
            check(!closed && !authorizationBound) { "Car property authorization is already bound" }
            authorizationBound = true
            authorizationProbe = predicate
        }
        isAuthorizationCurrent
    }

    /** Dynamic service connection, not proof that any individual property is readable. */
    val isAndroidCarServiceConnected: Boolean get() = serviceIsConnected()

    /** True means binding was initiated, not that the asynchronous car service is ready. */
    fun start(): Boolean {
        return synchronized(lock) {
            if (closed) return false
            val accepted = try {
                backend.connect()
            } catch (_: Exception) {
                false
            }
            started = accepted
            accepted
        }
    }

    override fun read(iids: Set<Long>): VehicleStateResult {
        val requested = LinkedHashSet(iids)
        if (requested.isEmpty()) return VehicleStateResult()
        val values = LinkedHashMap<Long, Any?>()
        val errors = LinkedHashMap<Long, Long>()
        val sourceEventMillisByIid = LinkedHashMap<Long, Long>()
        // A signed profile may map one exact standard VHAL Float speed sample to several CAF
        // measurement units. Cache the raw acquisition for this read/poll batch so all derived
        // IIDs share both the source value and the acquisition timestamp.
        val sourceReads = LinkedHashMap<Pair<Int, Int>, AcquiredSource>()
        requested.forEach { iid ->
            when {
                iid !in configTree.characteristicsByIid ->
                    errors[iid] = errorPolicy.unknownIdentifier
                iid !in propertyByIid ->
                    errors[iid] = errorPolicy.operationFailed
                !isReadable() ->
                    errors[iid] = errorPolicy.operationFailed
                else -> {
                    val binding = propertyByIid.getValue(iid)
                    val address = binding.propertyId to binding.areaId
                    val source = sourceReads.getOrPut(address) { acquireSource(binding) }
                    val value = source.value
                    if (value == null || !source.identityStableAfterRead || !isReadable()) {
                        errors[iid] = errorPolicy.operationFailed
                    } else {
                        try {
                            val projected = binding.speedProjection?.project(value.value) ?: value.value
                            values[iid] = validatedWireValue(iid, projected)
                            value.sourceEventMillis?.let { sourceEventMillisByIid[iid] = it }
                        } catch (_: Exception) {
                            errors[iid] = errorPolicy.invalidValue
                        }
                    }
                }
            }
        }
        // CarPropertyValue.getTimestamp() and SystemClock.elapsedRealtime() use the same Android
        // boot-relative clock. Compute an age here: an instrument consumer must never compare
        // that raw timestamp with its own System.nanoTime/clock epoch. A missing or invalid clock
        // leaves the age absent, so an instrument bundle fails closed without changing CAF reads.
        val now = try { elapsedRealtimeMillis() } catch (_: Exception) { -1L }
        val sourceAges = LinkedHashMap<Long, Long>()
        if (now >= 0) sourceEventMillisByIid.forEach { (iid, eventMillis) ->
            if (eventMillis > 0 && iid in values) {
                try {
                    sourceAges[iid] = Math.subtractExact(now, eventMillis)
                } catch (_: ArithmeticException) {
                    // Overflow or otherwise untrustworthy acquisition time stays unknown.
                }
            }
        }
        // A speed shown on an instrument is safety-relevant. Generic CAF bindings retain their
        // original missing/unknown-age behavior, but an explicit speed projection requires the
        // exact VHAL event time to be no more than two seconds old and never in the future.
        propertyByIid.forEach { (iid, binding) ->
            if (binding.speedProjection != null && iid in values &&
                sourceAges[iid]?.let { it in 0L..MAX_SPEED_SOURCE_AGE_MILLIS } != true) {
                values.remove(iid)
                sourceAges.remove(iid)
                errors[iid] = errorPolicy.invalidValue
            }
        }
        // A connected replacement CarPropertyManager is still a different source. Recheck each
        // acquired address after every potentially blocking read and clock call, then fail its
        // complete projected IID group before returning. Legacy sources with no identity retain
        // ordinary CAF reads; projected instrument speed requires a concrete manager generation.
        val readableAtPublication = isReadable()
        val staleAddresses = sourceReads.filter { (_, source) ->
            !readableAtPublication || !source.identityStableAfterRead ||
                !source.sameIdentityAs(observedSourceIdentity())
        }.keys
        if (!readableAtPublication || staleAddresses.isNotEmpty()) {
            requested.forEach { iid ->
                val binding = propertyByIid[iid] ?: return@forEach
                if (!readableAtPublication || (binding.propertyId to binding.areaId) in staleAddresses) {
                    values.remove(iid)
                    sourceAges.remove(iid)
                    errors[iid] = errorPolicy.operationFailed
                }
            }
        }
        return VehicleStateResult(
            values = values,
            errors = errors,
            sourceAgeMillisByIid = sourceAges,
        )
    }

    override fun subscribe(
        iids: Set<Long>,
        listener: VehicleStateListener,
    ): VehicleStateSubscription {
        check(isAuthorizationCurrent) { "Car property authorization was revoked" }
        val requested = Collections.unmodifiableSet(LinkedHashSet(iids))
        require(requested.size <= pollingPolicy.maxIidsPerSubscription) {
            "CAF Car property subscription exceeds per-subscription IID limit"
        }
        return synchronized(lock) {
            check(!closed) { "CAF Car property repository is closed" }
            require(subscriptions.size < pollingPolicy.maxSubscriptions) {
                "CAF Car property subscription limit exceeded"
            }
            val distinct = subscriptions.flatMapTo(LinkedHashSet()) { it.iids } + requested
            require(distinct.size <= pollingPolicy.maxDistinctSubscribedIids) {
                "CAF Car property total subscribed IID limit exceeded"
            }
            PollingSubscription(requested, listener).also {
                subscriptions += it
                if (requested.isNotEmpty()) ensurePollingLocked()
            }
        }
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (!closed) {
                closed = true
                started = false
                polling?.cancel(true)
                polling = null
                scheduler.shutdownNow()
            }
            subscriptions.toList().also { subscriptions.clear() }
        }
        // A bridge/root shutdown must also terminate any channel that already published a live
        // subscribed value. Otherwise stopping the poller would strand the peer's last value.
        retired.forEach(PollingSubscription::invalidateAndRetire)
        synchronized(backendCloseLock) {
            if (!backendClosed) {
                backend.close()
                backendClosed = true
            }
        }
    }

    private fun freezeBindings(
        bindings: List<AndroidCarPropertyCafBinding>,
    ): Map<Long, AndroidCarPropertyCafBinding> {
        require(bindings.isNotEmpty() && bindings.size <= 1_024) {
            "CAF Car property deployment needs 1..1024 explicit bindings"
        }
        val byIid = LinkedHashMap<Long, AndroidCarPropertyCafBinding>(bindings.size)
        val firstByAddress = HashMap<Pair<Int, Int>, AndroidCarPropertyCafBinding>()
        bindings.forEach { binding ->
            val characteristic = configTree.characteristicsByIid[binding.iid]
            require(characteristic != null) {
                "CAF Car property binding names a non-characteristic IID"
            }
            require(characteristic.format in NUMERIC_CAF_FORMATS) {
                "Android Car numeric property cannot supply this CAF characteristic format"
            }
            require(binding.propertyId > 0) { "Android Car property ID must be positive" }
            binding.speedProjection?.let { projection ->
                require(binding.propertyId == VehicleTelemetrySnapshot.STANDARD_SPEED && binding.areaId == 0) {
                    "Speed projection requires the standard global Android VHAL speed property"
                }
                require(projection.provenance == SpeedProvenance.STANDARD_ANDROID_VHAL_FLOAT &&
                    projection.sourceUnit == SpeedUnit.METERS_PER_SECOND) {
                    "Speed projection requires standard Android VHAL Float meters per second"
                }
                require(characteristic.format == CafCharacteristicFormat.FLOAT) {
                    "Speed projection requires a CAF FLOAT characteristic"
                }
                val expectedType = when (projection.targetUnit) {
                    SpeedUnit.METERS_PER_SECOND -> CAF_SPEED_MPS_TYPE
                    SpeedUnit.KILOMETERS_PER_HOUR -> CAF_SPEED_KMH_TYPE
                    SpeedUnit.MILES_PER_HOUR -> CAF_SPEED_MPH_TYPE
                }
                require(characteristic.type.value == expectedType) {
                    "Speed projection requires the exact CAF speed characteristic type"
                }
                require(matchesCafSpeedUnit(characteristic.units, projection.targetUnit.cafUnits)) {
                    "Speed projection requires matching CAF numeric units"
                }
            }
            require(byIid.putIfAbsent(binding.iid, binding) == null) {
                "Duplicate CAF IID in Android Car property bindings"
            }
            val previous = firstByAddress.putIfAbsent(binding.propertyId to binding.areaId, binding)
            require(previous == null ||
                (previous.speedProjection != null && binding.speedProjection != null)) {
                "Duplicate Android Car property/area address requires explicit speed projections"
            }
        }
        return Collections.unmodifiableMap(byIid)
    }

    private fun serviceIsConnected(): Boolean = started && !closed && try {
        backend.isConnected
    } catch (_: Exception) {
        false
    }

    private fun isReadable(): Boolean = isAuthorizationCurrent && serviceIsConnected()

    private data class SourceIdentity(val token: Any?)

    private data class AcquiredSource(
        val value: CarPropertyValue?,
        val sourceIdentity: SourceIdentity?,
        val identityStableAfterRead: Boolean,
    ) {
        fun sameIdentityAs(current: SourceIdentity?): Boolean =
            sourceIdentity != null && current != null && sourceIdentity.token === current.token
    }

    private fun observedSourceIdentity(): SourceIdentity? = try {
        SourceIdentity(backend.sourceIdentity)
    } catch (_: Exception) {
        null
    }

    private fun acquireSource(binding: AndroidCarPropertyCafBinding): AcquiredSource =
        synchronized(backendReadLock) {
            if (!isReadable()) return@synchronized AcquiredSource(null, null, false)
            val before = observedSourceIdentity()
            if (before == null || (binding.speedProjection != null && before.token == null)) {
                return@synchronized AcquiredSource(null, before, false)
            }
            val value = try {
                backend.read(binding.propertyId, binding.areaId)
            } catch (_: Exception) {
                null
            }
            val after = observedSourceIdentity()
            val stable = isReadable() && after != null && before.token === after.token
            AcquiredSource(value, before, stable)
        }

    private fun matchesCafSpeedUnit(raw: Any?, expected: Long): Boolean = when (raw) {
        is Byte, is Short, is Int, is Long -> (raw as Number).toLong() == expected
        is BigInteger -> raw == BigInteger.valueOf(expected)
        else -> false
    }

    private fun validatedWireValue(iid: Long, raw: Number): Any {
        require(raw is Byte || raw is Short || raw is Int || raw is Long ||
            raw is Float || raw is Double || raw is BigInteger) {
            "Unsupported Android Car property number class"
        }
        val format = configTree.characteristicsByIid.getValue(iid).format
        // CAF's UINT64 decoder treats a negative Long as an unsigned bit pattern. Android Car
        // properties have no generic unsigned-bit-pattern contract, so this needs an explicit
        // OEM-specific adapter rather than silently changing a negative vehicle reading.
        require(format != CafCharacteristicFormat.UINT64 || raw !is Long || raw >= 0) {
            "Ambiguous signed Android Car value for CAF UINT64"
        }
        if (format == CafCharacteristicFormat.FLOAT && raw is BigInteger) {
            require(raw.abs() <= MAX_EXACT_DOUBLE_INTEGER) {
                "Integer-to-float conversion would lose precision"
            }
        }
        if (format == CafCharacteristicFormat.FLOAT && raw is Long) {
            require(BigInteger.valueOf(raw).abs() <= MAX_EXACT_DOUBLE_INTEGER) {
                "Integer-to-float conversion would lose precision"
            }
        }
        return when (val decoded = configTree.validateCharacteristicValue(iid, raw)) {
            is CafFormatValue.Bool -> decoded.value
            // OPACK writes a signed 64-bit integer. For an explicitly positive UINT64 source,
            // retain its exact 64 wire bits; CAF's UINT64 decoder interprets negative Long bits
            // as the upper half of the unsigned range.
            is CafFormatValue.Unsigned -> decoded.value.toLong()
            is CafFormatValue.Signed -> {
                require(decoded.value >= MIN_SIGNED_LONG && decoded.value <= MAX_SIGNED_LONG) {
                    "Signed CAF value does not fit the 64-bit wire format"
                }
                decoded.value.toLong()
            }
            is CafFormatValue.Floating -> decoded.value
            else -> throw IllegalArgumentException("Android Car numeric property has nonnumeric CAF format")
        }
    }

    private fun ensurePollingLocked() {
        if (polling != null) return
        polling = scheduler.scheduleWithFixedDelay(
            ::pollSubscriptions,
            pollingPolicy.intervalMillis,
            pollingPolicy.intervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun pollSubscriptions() {
        val active = synchronized(lock) { if (closed) return else subscriptions.toList() }
        val requested = active.flatMapTo(LinkedHashSet()) { it.iids }
        if (requested.isEmpty()) return
        val values = try {
            read(requested).values
        } catch (_: Exception) {
            emptyMap()
        }
        active.forEach { it.deliver(values) }
    }

    private inner class PollingSubscription(
        val iids: Set<Long>,
        private val listener: VehicleStateListener,
    ) : VehicleStateSubscription {
        private val callbackLock = Any()
        private val previous = LinkedHashMap<Long, Any?>()
        private var retired = false

        fun deliver(values: Map<Long, Any?>) {
            synchronized(callbackLock) {
                if (retired || closed) return
                val authorized = isAuthorizationCurrent
                val availableValues = if (authorized) values else emptyMap()
                val unavailable = iids.filterTo(LinkedHashSet()) { it !in availableValues }
                unavailable.forEach(previous::remove)
                if (unavailable.isNotEmpty()) {
                    // A subscriber may have sent its initial REGISTER_RESPONSE before the first
                    // poll. Report every unavailable poll; the subscriber alone knows whether
                    // it published a valid value and must retire its protocol channel.
                    try {
                        listener.onUnavailable(Collections.unmodifiableSet(unavailable))
                    } catch (_: Exception) {
                        // Retry on the next bounded poll instead of making the poll worker die.
                    }
                }
                if (retired || closed) return
                val changed = LinkedHashMap<Long, Any?>()
                iids.forEach { iid ->
                    if (!availableValues.containsKey(iid)) {
                        return@forEach
                    } else {
                        val value = availableValues.getValue(iid)
                        if (!previous.containsKey(iid) || previous[iid] != value) {
                            previous[iid] = value
                            changed[iid] = value
                        }
                    }
                }
                if (changed.isNotEmpty()) {
                    if (!isAuthorizationCurrent) {
                        previous.clear()
                        runCatching { listener.onUnavailable(iids) }
                    } else try {
                        listener.onUpdate(VehicleStateUpdate(changed))
                    } catch (_: Exception) {
                        changed.keys.forEach(previous::remove)
                    }
                }
            }
        }

        fun retire() = synchronized(callbackLock) {
            retired = true
            previous.clear()
        }

        fun invalidateAndRetire() = synchronized(callbackLock) {
            if (retired) return
            try {
                listener.onUnavailable(iids)
            } catch (_: Exception) {
                // The repository still closes; a CAF handler schedules transport retirement.
            }
            retired = true
            previous.clear()
        }

        override fun close() {
            retire()
            synchronized(lock) {
                subscriptions.remove(this)
                if (subscriptions.none { it.iids.isNotEmpty() }) {
                    polling?.cancel(false)
                    polling = null
                }
            }
        }
    }

    companion object {
        private const val MAX_SPEED_SOURCE_AGE_MILLIS = 2_000L
        private const val CAF_SPEED_MPS_TYPE = 0x30000021L
        private const val CAF_SPEED_KMH_TYPE = 0x30000046L
        private const val CAF_SPEED_MPH_TYPE = 0x30000047L
        private val MAX_EXACT_DOUBLE_INTEGER = BigInteger.ONE.shiftLeft(53)
        private val MIN_SIGNED_LONG = BigInteger.valueOf(Long.MIN_VALUE)
        private val MAX_SIGNED_LONG = BigInteger.valueOf(Long.MAX_VALUE)
        private val NUMERIC_CAF_FORMATS = setOf(
            CafCharacteristicFormat.BOOL,
            CafCharacteristicFormat.UINT8,
            CafCharacteristicFormat.UINT16,
            CafCharacteristicFormat.UINT32,
            CafCharacteristicFormat.UINT64,
            CafCharacteristicFormat.INT8,
            CafCharacteristicFormat.INT16,
            CafCharacteristicFormat.INT32,
            CafCharacteristicFormat.INT64,
            CafCharacteristicFormat.FLOAT,
        )

        /** Constructs the real Android Car backend without enabling any CAF product feature. */
        fun forAndroid(
            context: Context,
            profileConfigTree: CafConfigTree,
            bindings: List<AndroidCarPropertyCafBinding>,
            errorPolicy: CafErrorPolicy,
            pollingPolicy: AndroidCarPropertyCafPollingPolicy = AndroidCarPropertyCafPollingPolicy(),
        ): AndroidCarPropertyVehicleStateRepository = AndroidCarPropertyVehicleStateRepository(
            profileConfigTree,
            bindings,
            AndroidCarPropertyBackend(context),
            errorPolicy,
            pollingPolicy,
        )
    }
}

private fun CafConfigTree.snapshotForRepository(): CafConfigTree = CafConfigTree.decode(root)
