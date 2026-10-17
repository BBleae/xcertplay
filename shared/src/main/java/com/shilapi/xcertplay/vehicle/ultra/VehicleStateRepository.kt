package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

class VehicleStateResult(
    values: Map<Long, Any?> = emptyMap(),
    /** Error values are signed and opaque to the generic protocol engine. */
    errors: Map<Long, Long> = emptyMap(),
    /**
     * IIDs whose mutation completed successfully.
     *
     * Reads continue to report success through [values]. Writes must use this set so that an
     * empty result can never be mistaken for a successful mutation.
     */
    succeededIids: Set<Long> = emptySet(),
    /**
     * Optional per-IID source acquisition age in milliseconds at completion of [VehicleStateRepository.read].
     * This is provenance for local consumers, never a CAF wire value. An absent entry means that
     * source acquisition time is unknown; it must not be treated as age zero.
     */
    sourceAgeMillisByIid: Map<Long, Long> = emptyMap(),
) {
    private val storedValues: Map<Long, Any?> = snapshotVehicleValues(values)
    val values: Map<Long, Any?>
        get() = snapshotVehicleValues(storedValues)
    val errors: Map<Long, Long> = Collections.unmodifiableMap(LinkedHashMap(errors))
    val succeededIids: Set<Long> = Collections.unmodifiableSet(LinkedHashSet(succeededIids))
    val sourceAgeMillisByIid: Map<Long, Long> =
        Collections.unmodifiableMap(LinkedHashMap(sourceAgeMillisByIid))

    init {
        require(storedValues.keys.intersect(this.errors.keys).isEmpty()) {
            "A vehicle IID cannot have both a value and an error"
        }
        require(this.succeededIids.intersect(this.errors.keys).isEmpty()) {
            "A vehicle IID cannot be both successful and erroneous"
        }
        require(storedValues.keys.containsAll(this.sourceAgeMillisByIid.keys)) {
            "Source acquisition ages must belong to returned value IIDs"
        }
    }
}

class VehicleControlResult(
    values: Map<Long, Any?> = emptyMap(),
    errors: Map<Long, Long> = emptyMap(),
    /** Every successful control, including controls that do not declare a response payload. */
    succeededIids: Set<Long> = emptySet(),
) {
    private val storedValues: Map<Long, Any?> = snapshotVehicleValues(values)
    val values: Map<Long, Any?>
        get() = snapshotVehicleValues(storedValues)
    val errors: Map<Long, Long> = Collections.unmodifiableMap(LinkedHashMap(errors))
    val succeededIids: Set<Long> = Collections.unmodifiableSet(LinkedHashSet(succeededIids))

    init {
        require(storedValues.keys.intersect(this.errors.keys).isEmpty()) {
            "A control IID cannot have both a value and an error"
        }
        require(this.succeededIids.intersect(this.errors.keys).isEmpty()) {
            "A control IID cannot be both successful and erroneous"
        }
        require(this.succeededIids.containsAll(storedValues.keys)) {
            "Every control response value must belong to an explicitly successful IID"
        }
    }
}

class VehicleStateUpdate(values: Map<Long, Any?>) {
    private val storedValues: Map<Long, Any?> = snapshotVehicleValues(values)
    val values: Map<Long, Any?>
        get() = snapshotVehicleValues(storedValues)
}

fun interface VehicleStateListener {
    fun onUpdate(update: VehicleStateUpdate)

    /**
     * A previously requestable subscribed IID cannot currently be read from its source. This is
     * an internal liveness signal, not a CAF wire error or an instruction to send an unverified
     * invalid-value notification. Listeners that have published it must retire the channel.
     */
    fun onUnavailable(iids: Set<Long>) = Unit
}

fun interface VehicleStateSubscription : AutoCloseable {
    override fun close()
}

/**
 * Shared, controller-owned source behind both CAF priority channels.
 *
 * Implementations must expose only an OEM-supplied, already validated config tree. This boundary
 * intentionally contains no guessed production IID/type mapping. Each RCS channel obtains its own
 * subscription and handler; only this repository may be shared.
 */
interface VehicleStateReadSource {
    val configTree: CafConfigTree

    /** A closed bridge must revoke local readers even if its frozen schema is still present. */
    val isCurrent: Boolean get() = true

    /** Returned value and error IIDs must be subsets of [iids]. Missing reads fail closed. */
    fun read(iids: Set<Long>): VehicleStateResult

    fun subscribe(iids: Set<Long>, listener: VehicleStateListener): VehicleStateSubscription
}

/** CAF's mutable protocol port is deliberately not exposed to a local instrument session. */
interface VehicleStateRepository : VehicleStateReadSource {

    /**
     * [VehicleStateResult.succeededIids] and [VehicleStateResult.errors] must be disjoint and
     * together cover exactly [values]. An empty or partial result is not implicit success.
     */
    fun write(values: Map<Long, Any?>): VehicleStateResult

    /**
     * [VehicleControlResult.succeededIids] and [VehicleControlResult.errors] must be disjoint and
     * together cover exactly [values]. Successful controls with response payloads also return a
     * value; successful controls without one still have to appear in `succeededIids`.
     */
    fun control(values: Map<Long, Any?>): VehicleControlResult
}

/**
 * Convenience base for a validated read-only OEM repository.
 *
 * The signed error value is supplied by the OEM profile because its numeric meaning has not been
 * verified generically. Consequently this class can never manufacture a false-success response.
 */
abstract class ReadOnlyVehicleStateRepository(
    final override val configTree: CafConfigTree,
    private val unsupportedMutationError: Long,
) : VehicleStateRepository {
    final override fun write(values: Map<Long, Any?>): VehicleStateResult =
        VehicleStateResult(errors = values.keys.associateWith { unsupportedMutationError })

    final override fun control(values: Map<Long, Any?>): VehicleControlResult =
        VehicleControlResult(errors = values.keys.associateWith { unsupportedMutationError })
}

private fun snapshotVehicleValues(values: Map<Long, Any?>): Map<Long, Any?> {
    val copier = VehicleValueSnapshotter()
    val result = LinkedHashMap<Long, Any?>(values.size)
    values.forEach { (iid, value) -> result[iid] = copier.copy(value, depth = 0) }
    return Collections.unmodifiableMap(result)
}

/** A repository is an external/concurrent boundary; detach values before schema validation. */
private class VehicleValueSnapshotter {
    private val active = IdentityHashMap<Any, Unit>()
    private var nodes = 0
    private var entries = 0
    private var stringChars = 0
    private var dataBytes = 0L

    fun copy(value: Any?, depth: Int): Any? {
        require(depth <= MAX_DEPTH) { "Vehicle value exceeds maximum nesting depth" }
        require(++nodes <= MAX_NODES) { "Vehicle value exceeds maximum node count" }
        return when (value) {
            null, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double,
            is BigInteger -> value
            is String -> {
                stringChars = Math.addExact(stringChars, value.length)
                require(value.length <= MAX_STRING_CHARS && stringChars <= MAX_TOTAL_STRING_CHARS) {
                    "Vehicle value string limit exceeded"
                }
                value
            }
            is ByteArray -> {
                dataBytes = Math.addExact(dataBytes, value.size.toLong())
                require(value.size <= MAX_DATA_BYTES && dataBytes <= MAX_TOTAL_DATA_BYTES) {
                    "Vehicle value data limit exceeded"
                }
                value.copyOf()
            }
            is List<*> -> guard(value) {
                consumeEntries(value.size)
                Collections.unmodifiableList(value.map { copy(it, depth + 1) })
            }
            is Map<*, *> -> guard(value) {
                consumeEntries(value.size)
                val result = LinkedHashMap<Any, Any?>(value.size)
                value.forEach { (key, item) ->
                    require(key is String || key is Number || key is Boolean) {
                        "Vehicle value dictionary key type is unsupported"
                    }
                    result[key] = copy(item, depth + 1)
                }
                Collections.unmodifiableMap(result)
            }
            else -> throw IllegalArgumentException(
                "Vehicle value type ${value::class.java.simpleName} is unsupported",
            )
        }
    }

    private fun consumeEntries(count: Int) {
        require(count <= MAX_COLLECTION_ENTRIES) { "Vehicle value collection is too large" }
        entries = Math.addExact(entries, count)
        require(entries <= MAX_TOTAL_COLLECTION_ENTRIES) {
            "Vehicle value aggregate collection limit exceeded"
        }
    }

    private inline fun <T> guard(value: Any, block: () -> T): T {
        require(active.put(value, Unit) == null) { "Vehicle value contains a cycle" }
        return try {
            block()
        } finally {
            active.remove(value)
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_NODES = 16_384
        const val MAX_COLLECTION_ENTRIES = 4_096
        const val MAX_TOTAL_COLLECTION_ENTRIES = 16_384
        const val MAX_STRING_CHARS = 16 * 1024
        const val MAX_TOTAL_STRING_CHARS = 256 * 1024
        const val MAX_DATA_BYTES = 256 * 1024
        const val MAX_TOTAL_DATA_BYTES = 1024L * 1024L
    }
}
