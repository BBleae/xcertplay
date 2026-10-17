package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import java.io.Closeable
import java.math.BigInteger
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Resource ceilings for presentation state retained beyond the UI Sync decoder. */
data class UltraPresentationHubLimits(
    val maximumDisplays: Int = 32,
    val maximumRetainedEventsPerDisplay: Int = 128,
    val maximumRetainedBytesPerDisplay: Long = 8L * 1024L * 1024L,
    val maximumObserversPerDisplay: Int = 64,
    val maximumNestingDepth: Int = 32,
    val maximumNodesPerEvent: Int = 4_096,
    val maximumCollectionEntriesPerEvent: Int = 4_096,
    val maximumStringCharsPerEvent: Long = 64L * 1_024L,
    val maximumDataBytesPerEvent: Long = 1024L * 1_024L,
) {
    init {
        require(maximumDisplays in 1..256)
        require(maximumRetainedEventsPerDisplay in 1..4_096)
        require(maximumRetainedBytesPerDisplay in 1L..(64L * 1024L * 1024L))
        require(maximumObserversPerDisplay in 1..256)
        require(maximumNestingDepth in 1..64)
        require(maximumNodesPerEvent in 1..65_536)
        require(maximumCollectionEntriesPerEvent in 1..65_536)
        require(maximumStringCharsPerEvent in 1L..(4L * 1024L * 1024L))
        require(maximumDataBytesPerEvent in 1L..(16L * 1024L * 1024L))
    }
}

/** One immutable retained UI Sync event. Every [data] read returns a detached value tree. */
class UltraPresentationEventSnapshot internal constructor(
    val revision: Long,
    val type: Int,
    val knownCommand: CarPlayUiSyncCommandType?,
    val acceptedAtMonotonicNanos: Long?,
    private val frozenData: FrozenPresentationValue,
    internal val retainedBytes: Long,
    /**
     * Retained channel attestation of [CarPlayUiSyncPresentationEvent.KnownCommand
     * .echoesAcknowledgedRequestLayout]; always false for any other command type.
     */
    val echoesAcknowledgedRequestLayout: Boolean = false,
) {
    init {
        require(!echoesAcknowledgedRequestLayout ||
            knownCommand == CarPlayUiSyncCommandType.END_LAYOUT_CHANGE
        ) { "Only a retained type-6 command can echo an acknowledged RequestLayout" }
    }

    val data: Any?
        get() = frozenData.toPublicValue()

    /**
     * A detached public projection. It never carries the channel's echo attestation (only
     * [echoesAcknowledgedRequestLayout] on this retained snapshot does), so a replayed event
     * cannot be fed back into a sink as an attested echo.
     */
    fun toPresentationEvent(): CarPlayUiSyncPresentationEvent = knownCommand?.let {
        CarPlayUiSyncPresentationEvent.KnownCommand(it, data, acceptedAtMonotonicNanos)
    } ?: CarPlayUiSyncPresentationEvent.UnknownCommand(type, data, acceptedAtMonotonicNanos)
}

/** A bounded, replayable state snapshot for exactly one opaque UI Sync display ID. */
class UltraPresentationSnapshot internal constructor(
    val displayId: UltraUiSyncDisplayId,
    val revision: Long,
    val droppedEventCount: Long,
    val closed: Boolean,
    events: List<UltraPresentationEventSnapshot>,
) {
    val events: List<UltraPresentationEventSnapshot> =
        Collections.unmodifiableList(events.toList())

    val firstRetainedRevision: Long?
        get() = events.firstOrNull()?.revision
}

fun interface UltraPresentationObserver {
    fun onPresentationSnapshot(snapshot: UltraPresentationSnapshot)
}

/** Delivery-side losses and failures. Counters saturate rather than wrapping. */
data class UltraPresentationDeliveryDiagnostics(
    /** Pending full snapshots replaced by a newer full snapshot for the same observer lease. */
    val coalescedSnapshotCount: Long = 0L,
    /** Pending snapshots discarded because executor submission or mailbox admission failed. */
    val droppedSnapshotCount: Long = 0L,
    /** Observer callbacks that threw any [Throwable] and were consequently deactivated. */
    val observerFailureCount: Long = 0L,
    /** Executor submissions that threw instead of accepting the per-display drainer. */
    val executorRejectionCount: Long = 0L,
    /**
     * Submissions run inline by the injected executor. Inline executors are supported for tests,
     * but cannot provide non-blocking publication or cross-display isolation.
     */
    val inlineExecutorDispatchCount: Long = 0L,
)

/**
 * Shared, renderer-neutral UI Sync presentation state.
 *
 * The hub is deliberately not a renderer and performs no feature negotiation. Callers explicitly
 * install [sink] on a UI Sync endpoint. Each display has an independent lock and revision space;
 * observer callbacks run outside those locks on the explicitly supplied [callbackExecutor]. A
 * multi-thread executor permits independent displays to make progress when one renderer blocks;
 * the hub intentionally creates no implicit or unbounded thread pool. Unknown commands are
 * retained exactly as unknown events. Observer failures, including [Error], never escape into the
 * UI Sync receive path: that lease is deactivated and the failure is counted in
 * [deliveryDiagnostics]. Closing queues one final `closed=true` snapshot per active lease.
 */
class UltraPresentationHub(
    private val callbackExecutor: Executor,
    private val limits: UltraPresentationHubLimits = UltraPresentationHubLimits(),
) : Closeable {
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val displays = LinkedHashMap<UltraUiSyncDisplayId, DisplayState>()

    fun sink(displayId: String): CarPlayUiSyncPresentationEventSink =
        sink(UltraUiSyncDisplayId(displayId))

    fun sink(displayId: UltraUiSyncDisplayId): CarPlayUiSyncPresentationEventSink {
        val state = stateFor(displayId)
        return CarPlayUiSyncPresentationEventSink { event ->
            check(!closed.get()) { "Ultra presentation hub is closed" }
            val frozen = freezeEvent(event)
            state.publish(frozen)
        }
    }

    fun observe(
        displayId: String,
        observer: UltraPresentationObserver,
    ): Closeable = observe(UltraUiSyncDisplayId(displayId), observer)

    fun observe(
        displayId: UltraUiSyncDisplayId,
        observer: UltraPresentationObserver,
    ): Closeable {
        val state = stateFor(displayId)
        return state.register(observer)
    }

    fun snapshot(displayId: String): UltraPresentationSnapshot =
        snapshot(UltraUiSyncDisplayId(displayId))

    fun snapshot(displayId: UltraUiSyncDisplayId): UltraPresentationSnapshot {
        val state = synchronized(lifecycleLock) { displays[displayId] }
        return state?.snapshot() ?: UltraPresentationSnapshot(
            displayId = displayId,
            revision = 0L,
            droppedEventCount = 0L,
            closed = closed.get(),
            events = emptyList(),
        )
    }

    fun deliveryDiagnostics(displayId: String): UltraPresentationDeliveryDiagnostics =
        deliveryDiagnostics(UltraUiSyncDisplayId(displayId))

    fun deliveryDiagnostics(
        displayId: UltraUiSyncDisplayId,
    ): UltraPresentationDeliveryDiagnostics {
        val state = synchronized(lifecycleLock) { displays[displayId] }
        return state?.deliveryDiagnostics() ?: UltraPresentationDeliveryDiagnostics()
    }

    override fun close() {
        val states = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            displays.values.toList()
        }
        var firstFailure: Throwable? = null
        states.forEach { state ->
            try {
                state.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure?.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
    }

    private fun stateFor(displayId: UltraUiSyncDisplayId): DisplayState =
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Ultra presentation hub is closed" }
            displays[displayId] ?: run {
                require(displays.size < limits.maximumDisplays) {
                    "Ultra presentation hub exceeds ${limits.maximumDisplays} displays"
                }
                DisplayState(
                    displayId,
                    limits.maximumRetainedEventsPerDisplay,
                    limits.maximumRetainedBytesPerDisplay,
                    limits.maximumObserversPerDisplay,
                    callbackExecutor,
                ).also {
                    displays[displayId] = it
                }
            }
        }

    private fun freezeEvent(event: CarPlayUiSyncPresentationEvent): PendingEvent {
        require(event.type in 0..MAXIMUM_UI_SYNC_COMMAND_TYPE) {
            "UI Sync presentation event type is out of range"
        }
        val knownEvent = event as? CarPlayUiSyncPresentationEvent.KnownCommand
        val known = knownEvent?.command
        val freezer = PresentationValueFreezer(limits)
        val data = freezer.freeze(event.data)
        return PendingEvent(
            type = event.type,
            knownCommand = known,
            acceptedAtMonotonicNanos = event.acceptedAtMonotonicNanos,
            data = data,
            retainedBytes = freezer.estimatedRetainedBytes(),
            echoesAcknowledgedRequestLayout = knownEvent?.echoesAcknowledgedRequestLayout == true &&
                known == CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
        )
    }

    private class DisplayState(
        private val displayId: UltraUiSyncDisplayId,
        private val maximumRetainedEvents: Int,
        private val maximumRetainedBytes: Long,
        private val maximumObservers: Int,
        private val callbackExecutor: Executor,
    ) {
        private val lock = Any()
        private val events = ArrayDeque<UltraPresentationEventSnapshot>()
        private val observers = LinkedHashMap<Long, ObserverRegistration>()
        private val readyObservers = LinkedHashSet<ObserverRegistration>()
        private var revision = 0L
        private var droppedEventCount = 0L
        private var retainedBytes = 0L
        private var nextObserverId = 1L
        private var drainScheduled = false
        private var coalescedSnapshotCount = 0L
        private var droppedSnapshotCount = 0L
        private var observerFailureCount = 0L
        private var executorRejectionCount = 0L
        private var inlineExecutorDispatchCount = 0L
        private var closed = false

        fun publish(event: PendingEvent) {
            val delivery: Pair<UltraPresentationSnapshot, List<ObserverRegistration>> =
                synchronized(lock) {
                    check(!closed) { "Ultra presentation display is closed" }
                    require(event.retainedBytes <= maximumRetainedBytes) {
                        "Presentation event exceeds the per-display retained-byte limit"
                    }
                    revision = nextRevision(revision)
                    while (
                        events.isNotEmpty() &&
                        (
                            events.size >= maximumRetainedEvents ||
                                retainedBytes > maximumRetainedBytes - event.retainedBytes
                            )
                    ) {
                        val removed = events.removeFirst()
                        retainedBytes -= removed.retainedBytes
                        droppedEventCount = Math.addExact(droppedEventCount, 1L)
                    }
                    val snapshot = UltraPresentationEventSnapshot(
                        revision = revision,
                        type = event.type,
                        knownCommand = event.knownCommand,
                        acceptedAtMonotonicNanos = event.acceptedAtMonotonicNanos,
                        frozenData = event.data,
                        retainedBytes = event.retainedBytes,
                        echoesAcknowledgedRequestLayout = event.echoesAcknowledgedRequestLayout,
                    )
                    events.addLast(snapshot)
                    retainedBytes = Math.addExact(retainedBytes, event.retainedBytes)
                    snapshotLocked() to observers.values.toList()
                }
            dispatch(delivery.first, delivery.second, terminal = false)
        }

        fun register(observer: UltraPresentationObserver): ObserverRegistration {
            val registrationAndReplay = synchronized(lock) {
                check(!closed) { "Ultra presentation display is closed" }
                require(observers.size < maximumObservers) {
                    "Ultra presentation display exceeds $maximumObservers observers"
                }
                check(nextObserverId != Long.MAX_VALUE) {
                    "Ultra presentation observer lease IDs exhausted"
                }
                val id = nextObserverId++
                val registration = ObserverRegistration(
                    owner = this,
                    id = id,
                    observer = observer,
                )
                observers[id] = registration
                registration to snapshotLocked()
            }
            dispatch(
                registrationAndReplay.second,
                listOf(registrationAndReplay.first),
                terminal = false,
            )
            return registrationAndReplay.first
        }

        fun snapshot(): UltraPresentationSnapshot = synchronized(lock) { snapshotLocked() }

        fun deliveryDiagnostics(): UltraPresentationDeliveryDiagnostics = synchronized(lock) {
            UltraPresentationDeliveryDiagnostics(
                coalescedSnapshotCount = coalescedSnapshotCount,
                droppedSnapshotCount = droppedSnapshotCount,
                observerFailureCount = observerFailureCount,
                executorRejectionCount = executorRejectionCount,
                inlineExecutorDispatchCount = inlineExecutorDispatchCount,
            )
        }

        fun close() {
            val terminalDelivery: Pair<UltraPresentationSnapshot, List<ObserverRegistration>> =
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    if (revision != Long.MAX_VALUE) revision++
                    val activeRegistrations = observers.values.toList()
                    observers.clear()
                    snapshotLocked() to activeRegistrations
                }
            // Terminal delivery is queued, never invoked while holding a hub/display/lease lock.
            // With an asynchronous executor close returns without waiting for external code.
            dispatch(terminalDelivery.first, terminalDelivery.second, terminal = true)
        }

        fun remove(id: Long, registration: ObserverRegistration) {
            synchronized(lock) {
                if (observers[id] === registration) observers.remove(id)
                readyObservers.remove(registration)
            }
        }

        fun recordObserverFailure() {
            synchronized(lock) {
                observerFailureCount = saturatedIncrement(observerFailureCount)
            }
        }

        private fun dispatch(
            snapshot: UltraPresentationSnapshot,
            registrations: List<ObserverRegistration>,
            terminal: Boolean,
        ) {
            val newlyReady = ArrayList<ObserverRegistration>(registrations.size)
            registrations.forEach { registration ->
                val offer = try {
                    registration.offer(snapshot, terminal)
                } catch (_: Throwable) {
                    try {
                        registration.failLease()
                    } catch (_: Throwable) {
                        // Continue closing/delivering the remaining leases.
                    }
                    synchronized(lock) {
                        droppedSnapshotCount = saturatedIncrement(droppedSnapshotCount)
                    }
                    OfferResult.IGNORED
                }
                when (offer) {
                    OfferResult.READY -> newlyReady += registration
                    OfferResult.COALESCED -> synchronized(lock) {
                        coalescedSnapshotCount = saturatedIncrement(coalescedSnapshotCount)
                    }
                    OfferResult.IGNORED -> Unit
                }
            }

            val dropped = ArrayList<ObserverRegistration>()
            val shouldSchedule = synchronized(lock) {
                newlyReady.forEach { registration ->
                    if (readyObservers.size >= maximumObservers) {
                        dropped += registration
                        droppedSnapshotCount = saturatedIncrement(droppedSnapshotCount)
                    } else {
                        readyObservers += registration
                    }
                }
                if (readyObservers.isNotEmpty() && !drainScheduled) {
                    drainScheduled = true
                    true
                } else {
                    false
                }
            }
            dropped.forEach { registration -> registration.dropPending() }
            if (shouldSchedule) submitDrainer()
        }

        private fun submitDrainer() {
            val caller = Thread.currentThread()
            val executeReturned = AtomicBoolean(false)
            try {
                callbackExecutor.execute {
                    if (!executeReturned.get() && Thread.currentThread() === caller) {
                        synchronized(lock) {
                            inlineExecutorDispatchCount =
                                saturatedIncrement(inlineExecutorDispatchCount)
                        }
                    }
                    drainMailbox()
                }
            } catch (_: Throwable) {
                val rejected = synchronized(lock) {
                    executorRejectionCount = saturatedIncrement(executorRejectionCount)
                    drainScheduled = false
                    readyObservers.toList().also { readyObservers.clear() }
                }
                rejected.forEach { registration ->
                    if (registration.dropPending()) {
                        synchronized(lock) {
                            droppedSnapshotCount = saturatedIncrement(droppedSnapshotCount)
                        }
                    }
                }
            } finally {
                executeReturned.set(true)
            }
        }

        private fun drainMailbox() {
            while (true) {
                val registration = synchronized(lock) {
                    val iterator = readyObservers.iterator()
                    if (!iterator.hasNext()) {
                        drainScheduled = false
                        return
                    }
                    val next = iterator.next()
                    iterator.remove()
                    next
                }
                try {
                    registration.deliverOne()
                } catch (_: Throwable) {
                    // Registration normally contains all observer failures. This final boundary
                    // prevents one corrupt lease from stranding the display mailbox.
                    registration.failLease()
                }
            }
        }

        private fun snapshotLocked(): UltraPresentationSnapshot = UltraPresentationSnapshot(
            displayId = displayId,
            revision = revision,
            droppedEventCount = droppedEventCount,
            closed = closed,
            events = events.toList(),
        )

        private fun nextRevision(current: Long): Long {
            check(current != Long.MAX_VALUE) { "Ultra presentation revision exhausted" }
            return current + 1L
        }

        private fun saturatedIncrement(value: Long): Long =
            if (value == Long.MAX_VALUE) value else value + 1L
    }

    private class ObserverRegistration(
        private val owner: DisplayState,
        private val id: Long,
        private val observer: UltraPresentationObserver,
    ) : Closeable {
        private val leaseLock = Any()
        private var pending: UltraPresentationSnapshot? = null
        private var active = true
        private var terminalQueued = false
        private var lastDeliveredRevision = -1L

        override fun close() {
            val remove = synchronized(leaseLock) {
                if (!active) false else {
                    active = false
                    pending = null
                    true
                }
            }
            if (remove) owner.remove(id, this)
        }

        /**
         * Snapshots are complete replay states, so a single pending latest-value slot is a bounded
         * queue. Terminal state replaces any pending live state and rejects all later live offers.
         */
        fun offer(snapshot: UltraPresentationSnapshot, terminal: Boolean): OfferResult =
            synchronized(leaseLock) {
                if (!active || (terminalQueued && !terminal)) return OfferResult.IGNORED
                if (!terminal && snapshot.revision <= lastDeliveredRevision) {
                    return OfferResult.IGNORED
                }
                if (!terminal && pending?.let { snapshot.revision <= it.revision } == true) {
                    return OfferResult.IGNORED
                }
                if (terminal) terminalQueued = true
                val result = if (pending == null) OfferResult.READY else OfferResult.COALESCED
                pending = snapshot
                result
            }

        fun dropPending(): Boolean = synchronized(leaseLock) {
            val hadPending = pending != null
            pending = null
            if (terminalQueued) active = false
            hadPending
        }

        fun deliverOne() {
            val snapshot = synchronized(leaseLock) {
                if (!active) {
                    pending = null
                    return
                }
                val next = pending ?: return
                pending = null
                lastDeliveredRevision = maxOf(lastDeliveredRevision, next.revision)
                next
            }
            try {
                observer.onPresentationSnapshot(snapshot)
            } catch (_: Throwable) {
                owner.recordObserverFailure()
                failLease()
                return
            }
            if (snapshot.closed) {
                failLease()
            }
        }

        fun failLease() {
            val remove = synchronized(leaseLock) {
                if (!active) false else {
                    active = false
                    pending = null
                    true
                }
            }
            if (remove) owner.remove(id, this)
        }
    }

    private enum class OfferResult { READY, COALESCED, IGNORED }

    private data class PendingEvent(
        val type: Int,
        val knownCommand: CarPlayUiSyncCommandType?,
        val acceptedAtMonotonicNanos: Long?,
        val data: FrozenPresentationValue,
        val retainedBytes: Long,
        val echoesAcknowledgedRequestLayout: Boolean,
    )

    private companion object {
        const val MAXIMUM_UI_SYNC_COMMAND_TYPE = 65_535
    }
}

internal sealed interface FrozenPresentationValue {
    data object Null : FrozenPresentationValue
    class Scalar(val value: Any) : FrozenPresentationValue
    class Data(bytes: ByteArray) : FrozenPresentationValue {
        private val value = bytes.copyOf()
        fun copyBytes(): ByteArray = value.copyOf()
    }
    class Array(val values: List<FrozenPresentationValue>) : FrozenPresentationValue
    class Dictionary(val values: Map<String, FrozenPresentationValue>) : FrozenPresentationValue
}

private class PresentationValueFreezer(
    private val limits: UltraPresentationHubLimits,
) {
    private val active = IdentityHashMap<Any, Unit>()
    private val completed = IdentityHashMap<Any, FrozenPresentationValue>()
    private var nodes = 0
    private var collectionEntries = 0
    private var stringChars = 0L
    private var dataBytes = 0L

    fun freeze(value: Any?): FrozenPresentationValue = freeze(value, depth = 0)

    fun estimatedRetainedBytes(): Long {
        val nodeBytes = Math.multiplyExact(nodes.toLong(), ESTIMATED_NODE_BYTES)
        val entryBytes = Math.multiplyExact(
            collectionEntries.toLong(),
            ESTIMATED_COLLECTION_ENTRY_BYTES,
        )
        val stringBytes = Math.multiplyExact(stringChars, Character.BYTES.toLong())
        return Math.addExact(
            Math.addExact(nodeBytes, entryBytes),
            Math.addExact(stringBytes, dataBytes),
        ).coerceAtLeast(1L)
    }

    private fun freeze(value: Any?, depth: Int): FrozenPresentationValue {
        require(depth <= limits.maximumNestingDepth) {
            "Presentation value exceeds nesting depth ${limits.maximumNestingDepth}"
        }
        nodes = Math.addExact(nodes, 1)
        require(nodes <= limits.maximumNodesPerEvent) {
            "Presentation value exceeds ${limits.maximumNodesPerEvent} nodes"
        }
        return when (value) {
            null -> FrozenPresentationValue.Null
            is Boolean, is Byte, is Short, is Int, is Long ->
                FrozenPresentationValue.Scalar(value)
            is Float -> {
                require(value.isFinite()) { "Presentation floating-point value must be finite" }
                FrozenPresentationValue.Scalar(value)
            }
            is Double -> {
                require(value.isFinite()) { "Presentation floating-point value must be finite" }
                FrozenPresentationValue.Scalar(value)
            }
            is BigInteger -> {
                consumeData(value.toByteArray().size.toLong())
                FrozenPresentationValue.Scalar(value)
            }
            is String -> {
                consumeString(value)
                FrozenPresentationValue.Scalar(value)
            }
            is ByteArray -> {
                consumeData(value.size.toLong())
                FrozenPresentationValue.Data(value)
            }
            is List<*> -> freezeComposite(value) {
                consumeEntries(value.size)
                FrozenPresentationValue.Array(
                    Collections.unmodifiableList(value.map { freeze(it, depth + 1) }),
                )
            }
            is Map<*, *> -> freezeComposite(value) {
                consumeEntries(value.size)
                val result = LinkedHashMap<String, FrozenPresentationValue>(value.size)
                value.forEach { (rawKey, item) ->
                    val key = rawKey as? String
                        ?: throw IllegalArgumentException(
                            "Presentation dictionary keys must be strings",
                        )
                    consumeString(key)
                    result[key] = freeze(item, depth + 1)
                }
                FrozenPresentationValue.Dictionary(Collections.unmodifiableMap(result))
            }
            else -> throw IllegalArgumentException(
                "Unsupported presentation value ${value::class.java.simpleName}",
            )
        }
    }

    private inline fun freezeComposite(
        source: Any,
        create: () -> FrozenPresentationValue,
    ): FrozenPresentationValue {
        completed[source]?.let { return it }
        require(active.put(source, Unit) == null) { "Presentation value contains a cycle" }
        return try {
            create().also { completed[source] = it }
        } finally {
            active.remove(source)
        }
    }

    private fun consumeEntries(count: Int) {
        collectionEntries = Math.addExact(collectionEntries, count)
        require(collectionEntries <= limits.maximumCollectionEntriesPerEvent) {
            "Presentation value exceeds ${limits.maximumCollectionEntriesPerEvent} collection entries"
        }
    }

    private fun consumeString(value: String) {
        stringChars = Math.addExact(stringChars, value.length.toLong())
        require(stringChars <= limits.maximumStringCharsPerEvent) {
            "Presentation value exceeds ${limits.maximumStringCharsPerEvent} string characters"
        }
    }

    private fun consumeData(count: Long) {
        dataBytes = Math.addExact(dataBytes, count)
        require(dataBytes <= limits.maximumDataBytesPerEvent) {
            "Presentation value exceeds ${limits.maximumDataBytesPerEvent} data bytes"
        }
    }

    private companion object {
        const val ESTIMATED_NODE_BYTES = 32L
        const val ESTIMATED_COLLECTION_ENTRY_BYTES = 16L
    }
}

private fun FrozenPresentationValue.toPublicValue(): Any? =
    toPublicValue(IdentityHashMap())

private fun FrozenPresentationValue.toPublicValue(
    completed: IdentityHashMap<FrozenPresentationValue, Any>,
): Any? = when (this) {
    FrozenPresentationValue.Null -> null
    is FrozenPresentationValue.Scalar -> value
    is FrozenPresentationValue.Data -> copyBytes()
    is FrozenPresentationValue.Array -> completed[this] ?: run {
        val result = Collections.unmodifiableList(values.map { it.toPublicValue(completed) })
        completed[this] = result
        result
    }
    is FrozenPresentationValue.Dictionary -> completed[this] ?: run {
        val result = LinkedHashMap<String, Any?>(values.size)
        values.forEach { (key, value) -> result[key] = value.toPublicValue(completed) }
        Collections.unmodifiableMap(result).also { completed[this] = it }
    }
}
