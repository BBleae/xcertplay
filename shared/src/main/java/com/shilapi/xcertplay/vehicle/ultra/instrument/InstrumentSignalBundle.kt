package com.shilapi.xcertplay.vehicle.ultra.instrument

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicMetadata
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.vehicle.ultra.SpeedUnit
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CircularGaugeStylePayload
import com.shilapi.xcertplay.vehicle.ultra.theme.Display
import com.shilapi.xcertplay.vehicle.ultra.theme.GaugeInstrumentKindPayload
import com.shilapi.xcertplay.vehicle.ultra.theme.ImageGroupGaugeStylePayload
import com.shilapi.xcertplay.vehicle.ultra.theme.IndicatorInstrumentKindPayload
import com.shilapi.xcertplay.vehicle.ultra.theme.InstrumentKindCase
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneRegion
import java.math.BigInteger
import java.util.Collections
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exact address within one selected, parsed Profile display. Names AND ordinals must agree. */
data class InstrumentThemeSignalAddress(
    val dataIdentifier: String,
    val zoneRegion: ZoneRegion?,
    val slot: CarPlayThemeSceneSlotAddress,
) {
    init {
        require(dataIdentifier.isNotBlank() && dataIdentifier.length <= 256)
        require(slot.component.zone.layout.layoutIndex >= 0)
        require(slot.component.zone.zoneIndex >= 0)
        require(slot.component.componentIndex >= 0)
        require(slot.slotIndex >= 0)
    }
}

/** A bundle is complete only when every role in its shape has an independently bound source. */
enum class InstrumentSignalBundleShape(roles: Set<InstrumentSignalRole>) {
    SPEED_TRIPLET(setOf(
        InstrumentSignalRole.SPEED,
        InstrumentSignalRole.SPEED_KMH,
        InstrumentSignalRole.SPEED_MPH,
    )),
    RPM_VALUE_AND_STATE(setOf(InstrumentSignalRole.RPM_VALUE, InstrumentSignalRole.RPM_STATE)),
    INDICATOR_STATE(setOf(InstrumentSignalRole.INDICATOR_STATE)),

    ;

    val roles: Set<InstrumentSignalRole> =
        Collections.unmodifiableSet(LinkedHashSet(roles))
}

enum class InstrumentSignalRole {
    SPEED,
    SPEED_KMH,
    SPEED_MPH,
    RPM_VALUE,
    RPM_STATE,
    INDICATOR_STATE,
}

/** No conversion or display-formatting inference is made from this declaration. */
enum class InstrumentNumericMeaning {
    /** The Profile must state the native speed unit; this is not automatically m/s or km/h. */
    PROFILE_SPEED,
    KILOMETRES_PER_HOUR,
    MILES_PER_HOUR,
    REVOLUTIONS_PER_MINUTE,
}

sealed interface InstrumentSignalSemantics {
    /** Exact legacy CAF `units` alias plus a separate, explicit Profile semantic assertion. */
    data class Numeric(
        val meaning: InstrumentNumericMeaning,
        val exactCafUnits: String,
    ) : InstrumentSignalSemantics {
        init {
            require(exactCafUnits.isNotBlank() && exactCafUnits.length <= 128)
        }
    }

    /** Unknown wire states make the entire bundle unavailable, never a default state. */
    class State(codes: List<InstrumentStateCode>) : InstrumentSignalSemantics {
        val codes: List<InstrumentStateCode> =
            Collections.unmodifiableList(ArrayList(codes))

        init {
            require(this.codes.isNotEmpty() && this.codes.size <= 32)
            require(this.codes.map(InstrumentStateCode::name).toSet().size == this.codes.size)
        }
    }
}

/** Boolean, integer, or string state code with an explicit Profile-defined meaning. */
data class InstrumentStateCode(val raw: Any, val name: String) {
    init {
        require(raw is Boolean || raw is Byte || raw is Short || raw is Int ||
            raw is Long || raw is BigInteger || raw is String)
        require(name.length in 1..64 && name.all {
            it.isLetterOrDigit() || it == '_' || it == '-' || it == '.'
        })
        if (raw is String) require(raw.length <= 128)
    }
}

/** Exact service and characteristic provenance; an IID is never derived from a Theme identifier. */
data class InstrumentCafCharacteristicPin(
    val serviceIid: Long,
    val serviceType: Long,
    val characteristicIid: Long,
    val characteristicType: Long,
    val characteristicFormat: CafCharacteristicFormat,
)

data class InstrumentSignalSource(
    val role: InstrumentSignalRole,
    val pin: InstrumentCafCharacteristicPin,
    val semantics: InstrumentSignalSemantics,
)

/**
 * Theme/CAF content alone is NOT sufficient authority to activate this crosswalk. The product
 * caller must bind this exact declaration to the verified Profile digest/issuer and control
 * session before constructing a bundle. This class only validates structural evidence.
 */
class InstrumentSignalBundleSpec(
    val address: InstrumentThemeSignalAddress,
    val shape: InstrumentSignalBundleShape,
    sources: List<InstrumentSignalSource>,
    val maximumReadAgeMillis: Long,
) {
    val sources: List<InstrumentSignalSource> = Collections.unmodifiableList(ArrayList(sources))

    init {
        require(maximumReadAgeMillis in 100..30_000)
        require(this.sources.size == shape.roles.size && this.sources.size <= 8)
        require(this.sources.map(InstrumentSignalSource::role).toSet() == shape.roles)
        require(this.sources.map { it.pin.characteristicIid }.toSet().size == this.sources.size)
    }
}

sealed interface InstrumentSignalReading {
    data class Numeric(val value: CafFormatValue, val meaning: InstrumentNumericMeaning) :
        InstrumentSignalReading

    data class State(val raw: CafFormatValue, val name: String) : InstrumentSignalReading
}

enum class InstrumentSignalUnavailableReason {
    NOT_STARTED,
    REPOSITORY_UNAVAILABLE,
    CONFIG_CHANGED,
    INCOMPLETE_READ,
    INVALID_VALUE,
    UNKNOWN_STATE,
    STALE,
    CLOSED,
}

sealed interface InstrumentSignalBundleState {
    class Available(
        values: Map<InstrumentSignalRole, InstrumentSignalReading>,
        val readAtNanos: Long,
        sourceAgeMillisByRole: Map<InstrumentSignalRole, Long>,
    ) :
        InstrumentSignalBundleState {
        val values: Map<InstrumentSignalRole, InstrumentSignalReading> =
            Collections.unmodifiableMap(LinkedHashMap(values))
        /** Source ages at repository read completion; never synthesized for missing samples. */
        val sourceAgeMillisByRole: Map<InstrumentSignalRole, Long> =
            Collections.unmodifiableMap(LinkedHashMap(sourceAgeMillisByRole))

        init {
            require(this.sourceAgeMillisByRole.keys == this.values.keys)
        }
    }

    data class Unavailable(val reason: InstrumentSignalUnavailableReason) :
        InstrumentSignalBundleState
}

fun interface InstrumentSignalClock {
    fun nowNanos(): Long
}

fun interface InstrumentSignalBundleListener {
    fun onState(state: InstrumentSignalBundleState)
}

/** Validated read-only crosswalk, bound to exactly one session-owned repository. */
class InstrumentSignalBundle private constructor(
    val spec: InstrumentSignalBundleSpec,
    private val repository: VehicleStateReadSource,
    private val configTree: CafConfigTree,
    private val metadataByRole: Map<InstrumentSignalRole, CafCharacteristicMetadata>,
    private val stateCodesByRole: Map<InstrumentSignalRole, Map<CafFormatValue, String>>,
    private val clock: InstrumentSignalClock,
) : AutoCloseable {
    private val lock = Any()
    private val callbackLock = Any()
    private val retirementLock = Any()
    private val requestedIids: Set<Long> = Collections.unmodifiableSet(
        spec.sources.mapTo(LinkedHashSet()) { it.pin.characteristicIid },
    )
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ultra-instrument-read").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    /** Independent of a potentially stalled repository read. */
    private val freshnessWorker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ultra-instrument-freshness").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val refreshQueued = AtomicBoolean(false)
    private var periodic: ScheduledFuture<*>? = null
    private var freshnessCheck: ScheduledFuture<*>? = null
    private var subscription: VehicleStateSubscription? = null
    private var listener: InstrumentSignalBundleListener? = null
    private var state: InstrumentSignalBundleState = InstrumentSignalBundleState.Unavailable(
        InstrumentSignalUnavailableReason.NOT_STARTED,
    )
    private var started = false
    private var closed = false
    private var registrationInProgress = false
    private var releaseInProgress = false
    /** Invalidates an in-flight read when a newer read or unavailable/close event starts. */
    private var readGeneration = 0L
    /** Changes for every fresh publication, including same-value acquisition renewal. */
    private var publishedStateEpoch = 0L

    /** True only after close and proven release of the exact repository subscription. */
    val isFullyRetired: Boolean
        get() = synchronized(lock) { closed && !registrationInProgress && subscription == null }

    /**
     * A complete read with acquisition evidence for every source within the declared age window.
     * Android source timestamps are first normalized to ages by the repository; the bundle adds
     * only durations measured by its own monotonic clock, never comparing unrelated epochs.
     * Never invokes repository.write or repository.control.
     */
    fun refreshNow(): InstrumentSignalBundleState {
        val readVersion = synchronized(lock) {
            if (closed) return InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.CLOSED,
            )
            ++readGeneration
        }
        val beganAt = clock.nowNanos()
        var readReturnedAt = beganAt
        var next = try {
            if (!isPinnedConfigCurrent()) {
                InstrumentSignalBundleState.Unavailable(
                    InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                )
            } else {
                val result = repository.read(requestedIids)
                readReturnedAt = clock.nowNanos()
                decode(result, readReturnedAt)
            }
        } catch (_: Exception) {
            InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE,
            )
        }
        val finishedAt = clock.nowNanos()
        val duration = finishedAt - beganAt
        if (duration < 0 || duration > TimeUnit.MILLISECONDS.toNanos(spec.maximumReadAgeMillis)) {
            next = InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.STALE,
            )
        }
        if (next is InstrumentSignalBundleState.Available && isStale(next, finishedAt)) {
            next = InstrumentSignalBundleState.Unavailable(InstrumentSignalUnavailableReason.STALE)
        }
        if (!isPinnedConfigCurrent()) {
            next = InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.CONFIG_CHANGED,
            )
        }
        var published = false
        var publishedState: InstrumentSignalBundleState? = null
        val callback = synchronized(lock) {
            if (closed || readVersion != readGeneration) {
                null
            } else {
                published = true
                // Pin the repository tree again at the publication fence; a replacement during
                // read must never publish values decoded against the previous Profile tree.
                val candidate = if (isPinnedConfigCurrent()) next else {
                    InstrumentSignalBundleState.Unavailable(
                        InstrumentSignalUnavailableReason.CONFIG_CHANGED,
                    )
                }
                publishedState = candidate
                val previous = state
                state = candidate
                if (sameContent(previous, candidate)) null else {
                    publishedStateEpoch++
                    listener?.let { it to publishedStateEpoch }
                }
            }
        }
        if (!published) return snapshot()
        val accepted = requireNotNull(publishedState)
        if (callback != null) notifyIfCurrent(callback.first, accepted, callback.second)
        // The callback can block while another worker expires this sample, or itself replace the
        // Profile tree/advance the clock. Do not return the pre-callback Available object.
        return snapshot()
    }

    /** A stalled read cannot keep old values live; this getter applies a monotonic read-age fence. */
    fun snapshot(): InstrumentSignalBundleState {
        return synchronized(lock) {
            if (closed) return@synchronized InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.CLOSED,
            )
            if (!isPinnedConfigCurrent()) return@synchronized InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.CONFIG_CHANGED,
            )
            when (val current = state) {
            is InstrumentSignalBundleState.Available -> {
                if (isStale(current, clock.nowNanos())) {
                    InstrumentSignalBundleState.Unavailable(InstrumentSignalUnavailableReason.STALE)
                } else current
            }
            is InstrumentSignalBundleState.Unavailable -> current
            }
        }
    }

    /** One consumer per bundle; updates trigger full reads, never mixed partial-value frames. */
    fun subscribe(listener: InstrumentSignalBundleListener) {
        synchronized(lock) {
            check(!closed && !started) { "Instrument bundle is closed or already subscribed" }
            started = true
            registrationInProgress = true
            this.listener = listener
        }
        val initialEpoch = synchronized(lock) { publishedStateEpoch }
        notifyIfCurrent(listener, snapshot(), initialEpoch)
        if (synchronized(lock) {
            if (closed) registrationInProgress = false
            closed
        }) return
        val received = try {
            repository.subscribe(requestedIids, object : VehicleStateListener {
                override fun onUpdate(update: VehicleStateUpdate) = queueRefresh()

                override fun onUnavailable(iids: Set<Long>) {
                    publishUnavailable(InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE)
                }
            })
        } catch (error: Exception) {
            synchronized(lock) { registrationInProgress = false }
            try {
                close()
            } catch (releaseError: Exception) {
                error.addSuppressed(releaseError)
            }
            throw error
        }
        val retire = try {
            synchronized(lock) {
                subscription = received
                registrationInProgress = false
                if (closed) true else {
                    val interval = (spec.maximumReadAgeMillis / 2).coerceAtLeast(50)
                    periodic = worker.scheduleWithFixedDelay(
                        { refreshNow() }, interval, interval, TimeUnit.MILLISECONDS,
                    )
                    freshnessCheck = freshnessWorker.scheduleWithFixedDelay(
                        ::expireIfStale, interval, interval, TimeUnit.MILLISECONDS,
                    )
                    false
                }
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (subscription == null) subscription = received
                registrationInProgress = false
            }
            try {
                close()
            } catch (releaseError: Exception) {
                error.addSuppressed(releaseError)
            }
            throw error
        }
        if (retire) {
            close()
            return
        }
        queueRefresh()
    }

    override fun close() {
        val notification = synchronized(lock) {
            if (closed) null else {
                closed = true
                readGeneration++
                publishedStateEpoch++
                state = InstrumentSignalBundleState.Unavailable(
                    InstrumentSignalUnavailableReason.CLOSED,
                )
                periodic?.cancel(true)
                periodic = null
                freshnessCheck?.cancel(true)
                freshnessCheck = null
                listener.also { listener = null }
            }
        }
        worker.shutdownNow()
        freshnessWorker.shutdownNow()
        try {
            retirePendingSubscription()
        } finally {
            synchronized(callbackLock) {
                notification?.safelyNotify(InstrumentSignalBundleState.Unavailable(
                    InstrumentSignalUnavailableReason.CLOSED,
                ))
            }
        }
    }

    private fun retirePendingSubscription() {
        synchronized(retirementLock) {
            val pending = synchronized(lock) {
                if (releaseInProgress) null else subscription?.also { releaseInProgress = true }
            } ?: return
            try {
                pending.close()
                synchronized(lock) {
                    if (subscription === pending) subscription = null
                }
            } finally {
                synchronized(lock) { releaseInProgress = false }
            }
        }
    }

    private fun queueRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        try {
            worker.execute {
                refreshQueued.set(false)
                if (!synchronized(lock) { closed }) refreshNow()
            }
        } catch (_: RuntimeException) {
            refreshQueued.set(false)
        }
    }

    private fun publishUnavailable(reason: InstrumentSignalUnavailableReason) {
        val notify = synchronized(lock) {
            if (closed) return
            readGeneration++
            val next = InstrumentSignalBundleState.Unavailable(reason)
            val changed = !sameContent(state, next)
            state = next
            if (changed) {
                publishedStateEpoch++
                listener?.let { it to publishedStateEpoch }
            } else null
        }
        if (notify != null) {
            try {
                freshnessWorker.execute {
                    notifyIfCurrent(
                        notify.first,
                        InstrumentSignalBundleState.Unavailable(reason),
                        notify.second,
                    )
                }
            } catch (_: RuntimeException) {
                // State was invalidated synchronously; close may have stopped the notifier.
            }
        }
    }

    private fun expireIfStale() {
        val notify = synchronized(lock) {
            if (closed) return
            val current = state as? InstrumentSignalBundleState.Available ?: return
            if (!isStale(current, clock.nowNanos())) {
                return
            }
            readGeneration++
            state = InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.STALE,
            )
            publishedStateEpoch++
            listener?.let { it to publishedStateEpoch }
        }
        if (notify != null) notifyIfCurrent(
            notify.first,
            InstrumentSignalBundleState.Unavailable(InstrumentSignalUnavailableReason.STALE),
            notify.second,
        )
    }

    private fun notifyIfCurrent(
        observer: InstrumentSignalBundleListener,
        candidate: InstrumentSignalBundleState,
        expectedEpoch: Long,
    ) = synchronized(callbackLock) {
        if (synchronized(lock) { !closed && publishedStateEpoch == expectedEpoch }) {
            if (candidate is InstrumentSignalBundleState.Available &&
                !isPinnedConfigCurrent()
            ) {
                publishUnavailable(InstrumentSignalUnavailableReason.CONFIG_CHANGED)
            } else if (candidate is InstrumentSignalBundleState.Available &&
                isStale(candidate, clock.nowNanos())
            ) {
                publishUnavailable(InstrumentSignalUnavailableReason.STALE)
            } else {
                observer.safelyNotify(candidate)
            }
        }
    }

    private fun isPinnedConfigCurrent(): Boolean = try {
        repository.isCurrent && repository.configTree === configTree && repository.isCurrent
    } catch (_: Exception) {
        false
    }

    private fun decode(result: VehicleStateResult, readReturnedAt: Long): InstrumentSignalBundleState {
        val values = result.values
        if (result.errors.isNotEmpty() || result.succeededIids.isNotEmpty() ||
            values.keys != requestedIids
        ) return InstrumentSignalBundleState.Unavailable(
            InstrumentSignalUnavailableReason.INCOMPLETE_READ,
        )
        val sourceAges = result.sourceAgeMillisByIid
        // A recent read of a cached CarPropertyValue is not a recent sensor acquisition. Every
        // role must have its own bounded age; missing, negative/future, and old ages fail closed.
        if (sourceAges.keys != requestedIids || sourceAges.values.any {
            it !in 0..spec.maximumReadAgeMillis
        }) return InstrumentSignalBundleState.Unavailable(
            InstrumentSignalUnavailableReason.STALE,
        )
        val resolved = LinkedHashMap<InstrumentSignalRole, InstrumentSignalReading>()
        val agesByRole = LinkedHashMap<InstrumentSignalRole, Long>()
        for (source in spec.sources) {
            val metadata = metadataByRole.getValue(source.role)
            val decoded = try {
                metadata.validateValue(values[source.pin.characteristicIid])
            } catch (_: Exception) {
                return InstrumentSignalBundleState.Unavailable(
                    InstrumentSignalUnavailableReason.INVALID_VALUE,
                )
            }
            if (decoded == CafFormatValue.Invalid) return InstrumentSignalBundleState.Unavailable(
                InstrumentSignalUnavailableReason.INVALID_VALUE,
            )
            resolved[source.role] = when (val semantics = source.semantics) {
                is InstrumentSignalSemantics.Numeric -> InstrumentSignalReading.Numeric(
                    decoded, semantics.meaning,
                )
                is InstrumentSignalSemantics.State -> {
                    val name = stateCodesByRole.getValue(source.role)[decoded]
                        ?: return InstrumentSignalBundleState.Unavailable(
                            InstrumentSignalUnavailableReason.UNKNOWN_STATE,
                        )
                    InstrumentSignalReading.State(decoded, name)
                }
            }
            agesByRole[source.role] = sourceAges.getValue(source.pin.characteristicIid)
        }
        return InstrumentSignalBundleState.Available(resolved, readReturnedAt, agesByRole)
    }

    private fun isStale(state: InstrumentSignalBundleState.Available, nowNanos: Long): Boolean {
        val elapsedNanos = nowNanos - state.readAtNanos
        val maximumNanos = TimeUnit.MILLISECONDS.toNanos(spec.maximumReadAgeMillis)
        if (elapsedNanos < 0 || elapsedNanos > maximumNanos) return true
        return state.sourceAgeMillisByRole.values.any { sourceAgeMillis ->
            sourceAgeMillis < 0 || sourceAgeMillis > spec.maximumReadAgeMillis ||
                TimeUnit.MILLISECONDS.toNanos(sourceAgeMillis) + elapsedNanos > maximumNanos
        }
    }

    private fun sameContent(
        before: InstrumentSignalBundleState,
        after: InstrumentSignalBundleState,
    ): Boolean = when {
        before is InstrumentSignalBundleState.Available &&
            after is InstrumentSignalBundleState.Available -> false
        before is InstrumentSignalBundleState.Unavailable &&
            after is InstrumentSignalBundleState.Unavailable -> before.reason == after.reason
        else -> false
    }

    companion object {
        /** Structural validation only; no repository, worker, or rendering authority is created. */
        fun validateDeclaration(
            display: Display,
            spec: InstrumentSignalBundleSpec,
            tree: CafConfigTree,
        ) {
            resolveDeclaration(display, spec, tree)
        }

        /** The same repository CAF tree is used for provenance and every runtime value check. */
        fun bind(
            display: Display,
            spec: InstrumentSignalBundleSpec,
            repository: VehicleStateReadSource,
            clock: InstrumentSignalClock = InstrumentSignalClock {
                // Android CarPropertyValue acquisition timestamps use this boot-relative clock.
                // JVM unit tests have an unimplemented Android stub, so use a local monotonic
                // clock there; neither clock's raw epoch is compared with a source timestamp.
                try { SystemClock.elapsedRealtimeNanos() } catch (_: RuntimeException) {
                    System.nanoTime()
                }
            },
        ): InstrumentSignalBundle {
            val tree = repository.configTree
            val (metadata, codes) = resolveDeclaration(display, spec, tree)
            return InstrumentSignalBundle(spec, repository, tree, metadata, codes, clock)
        }

        private fun resolveDeclaration(
            display: Display,
            spec: InstrumentSignalBundleSpec,
            tree: CafConfigTree,
        ): Pair<Map<InstrumentSignalRole, CafCharacteristicMetadata>,
            Map<InstrumentSignalRole, Map<CafFormatValue, String>>> {
            validateThemeAddress(display, spec)
            val metadata = LinkedHashMap<InstrumentSignalRole, CafCharacteristicMetadata>()
            val codes = LinkedHashMap<InstrumentSignalRole, Map<CafFormatValue, String>>()
            for (source in spec.sources) {
                val pin = source.pin
                val services = tree.accessories.flatMap { it.services }
                    .filter { it.iid.value == pin.serviceIid }
                require(services.size == 1 && services.single().type.value == pin.serviceType) {
                    "CAF parent service IID/type does not match the exact crosswalk"
                }
                val characteristic = services.single().characteristics
                    .singleOrNull { it.iid.value == pin.characteristicIid }
                require(characteristic != null &&
                    tree.accessories.flatMap { it.services }
                        .sumOf { service -> service.characteristics.count {
                            it.iid.value == pin.characteristicIid
                        } } == 1 &&
                    characteristic.type.value == pin.characteristicType &&
                    characteristic.format == pin.characteristicFormat
                ) { "CAF characteristic IID/type/format does not match the exact crosswalk" }
                require(characteristic.stateChannels.asMap().isEmpty()) {
                    "CAF source declares state channels without verified gauge semantics"
                }
                metadata[source.role] = characteristic.copy(
                    validValues = Collections.unmodifiableList(ArrayList(characteristic.validValues)),
                )
                when (val semantics = source.semantics) {
                    is InstrumentSignalSemantics.Numeric -> {
                        require(source.role.expectedNumericMeaning() == semantics.meaning &&
                            characteristic.format in NUMERIC_FORMATS &&
                            matchesExactNumericCafUnit(
                                source.role, semantics.exactCafUnits, characteristic.units,
                            )
                        ) { "CAF numeric role/format/unit claim does not match" }
                    }
                    is InstrumentSignalSemantics.State -> {
                        require(source.role.isStateRole() &&
                            characteristic.format in STATE_FORMATS
                        ) { "CAF state role/format does not match" }
                        val byValue = LinkedHashMap<CafFormatValue, String>()
                        semantics.codes.forEach { code ->
                            val value = characteristic.validateValue(code.raw)
                            require(value != CafFormatValue.Invalid &&
                                byValue.putIfAbsent(value, code.name) == null
                            ) { "CAF state codes must be unique valid values" }
                        }
                        require(characteristic.validValues.all { it in byValue }) {
                            "CAF state mapping omits declared valid values"
                        }
                        codes[source.role] = Collections.unmodifiableMap(byValue)
                    }
                }
            }
            return Pair(
                Collections.unmodifiableMap(metadata), Collections.unmodifiableMap(codes),
            )
        }

        private val NUMERIC_FORMATS = setOf(
            CafCharacteristicFormat.UINT8, CafCharacteristicFormat.UINT16,
            CafCharacteristicFormat.UINT32, CafCharacteristicFormat.UINT64,
            CafCharacteristicFormat.INT8, CafCharacteristicFormat.INT16,
            CafCharacteristicFormat.INT32, CafCharacteristicFormat.INT64,
            CafCharacteristicFormat.FLOAT,
        )
        private val STATE_FORMATS = setOf(
            CafCharacteristicFormat.BOOL, CafCharacteristicFormat.UINT8,
            CafCharacteristicFormat.UINT16, CafCharacteristicFormat.UINT32,
            CafCharacteristicFormat.UINT64, CafCharacteristicFormat.INT8,
            CafCharacteristicFormat.INT16, CafCharacteristicFormat.INT32,
            CafCharacteristicFormat.INT64, CafCharacteristicFormat.STRING,
        )
    }
}

private fun InstrumentSignalRole.expectedNumericMeaning(): InstrumentNumericMeaning? = when (this) {
    InstrumentSignalRole.SPEED -> InstrumentNumericMeaning.PROFILE_SPEED
    InstrumentSignalRole.SPEED_KMH -> InstrumentNumericMeaning.KILOMETRES_PER_HOUR
    InstrumentSignalRole.SPEED_MPH -> InstrumentNumericMeaning.MILES_PER_HOUR
    InstrumentSignalRole.RPM_VALUE -> InstrumentNumericMeaning.REVOLUTIONS_PER_MINUTE
    InstrumentSignalRole.RPM_STATE, InstrumentSignalRole.INDICATOR_STATE -> null
}

private fun InstrumentSignalRole.isStateRole(): Boolean =
    this == InstrumentSignalRole.RPM_STATE || this == InstrumentSignalRole.INDICATOR_STATE

/** Preserve exact legacy aliases; numeric CAF speed IDs are accepted only for their same role. */
private fun matchesExactNumericCafUnit(
    role: InstrumentSignalRole,
    declaredAlias: String,
    rawUnits: Any?,
): Boolean {
    if (rawUnits is String) return rawUnits == declaredAlias
    val numericSpeedUnit = when (role) {
        InstrumentSignalRole.SPEED -> "m/s" to SpeedUnit.METERS_PER_SECOND
        InstrumentSignalRole.SPEED_KMH -> "km/h" to SpeedUnit.KILOMETERS_PER_HOUR
        InstrumentSignalRole.SPEED_MPH -> "mph" to SpeedUnit.MILES_PER_HOUR
        else -> return false
    }
    if (declaredAlias != numericSpeedUnit.first) return false
    val expected = numericSpeedUnit.second.cafUnits
    return when (rawUnits) {
        is Byte, is Short, is Int, is Long -> (rawUnits as Number).toLong() == expected
        is BigInteger -> rawUnits == BigInteger.valueOf(expected)
        else -> false
    }
}

private fun InstrumentSignalBundleListener.safelyNotify(state: InstrumentSignalBundleState) {
    try {
        onState(state)
    } catch (_: Exception) {
        // One UI observer must not kill the single refresh/freshness worker or CAF source.
    }
}

private fun validateThemeAddress(display: Display, spec: InstrumentSignalBundleSpec) {
    val address = spec.address.slot
    val layout = requireNotNull(display.layouts.getOrNull(
        address.component.zone.layout.layoutIndex,
    )) { "Crosswalk layout index does not exist in the selected Theme display" }
    require(layout.id == address.component.zone.layout.layoutId) {
        "Crosswalk layout address does not match the selected Theme display"
    }
    val zone = requireNotNull(layout.zones.getOrNull(address.component.zone.zoneIndex)) {
        "Crosswalk zone index does not exist in Theme"
    }
    require(zone.id == address.component.zone.zoneId &&
        zone.zoneRegion == spec.address.zoneRegion
    ) { "Crosswalk zone address/region does not match Theme" }
    val component = requireNotNull(zone.components.getOrNull(address.component.componentIndex)) {
        "Crosswalk component index does not exist in Theme"
    }
    require(component.id == address.component.componentId) {
        "Crosswalk component address does not match Theme"
    }
    val slot = requireNotNull(component.slots.getOrNull(address.slotIndex)) {
        "Crosswalk slot index does not exist in Theme"
    }
    require(slot.positionId == address.positionId) {
        "Crosswalk slot address does not match Theme"
    }
    val instrument = requireNotNull(slot.instrument) { "Crosswalk slot has no Instrument" }
    val typed = instrument.kind.typedPayload
    val identifier = when (typed) {
        is GaugeInstrumentKindPayload -> {
            require(typed.configuration.rendersLocally &&
                spec.shape != InstrumentSignalBundleShape.INDICATOR_STATE
            ) { "Crosswalk requires a native Gauge" }
            when (val style = typed.configuration.style.typedPayload) {
                is CircularGaugeStylePayload -> style.config.dataIdentifier.value
                is ImageGroupGaugeStylePayload -> style.dataIdentifier?.value
                else -> null
            }
        }
        is IndicatorInstrumentKindPayload -> {
            require(typed.configuration.style.rendersLocally &&
                spec.shape == InstrumentSignalBundleShape.INDICATOR_STATE
            ) { "Crosswalk requires a native Indicator" }
            typed.configuration.dataIdentifier.value
        }
        else -> null
    }
    require(instrument.kind.case == InstrumentKindCase.GAUGE ||
        instrument.kind.case == InstrumentKindCase.INDICATOR)
    require(identifier is String && identifier == spec.address.dataIdentifier) {
        "Theme dataIdentifier is absent, opaque, or different from exact crosswalk"
    }
}
