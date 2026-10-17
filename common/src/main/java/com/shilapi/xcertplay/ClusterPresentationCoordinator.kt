package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import java.io.Closeable
import java.util.Base64
import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Limits the memory and browser-message cost of presentation data supplied by the phone. */
data class ClusterPresentationLimits(
    val maximumRetainedCommands: Int = 24,
    val maximumNestingDepth: Int = 8,
    val maximumCollectionEntries: Int = 128,
    val maximumNodesPerCommand: Int = 512,
    val maximumStringChars: Int = 2 * 1024,
    val maximumBinaryBytes: Int = 4 * 1024,
    val maximumJsonPayloadChars: Int = 16 * 1024,
    /** UTF-8 ceiling for one complete snapshot sent to a browser. */
    val maximumSnapshotJsonBytes: Int = 128 * 1024,
) {
    init {
        require(maximumRetainedCommands in 1..256)
        require(maximumNestingDepth in 1..32)
        require(maximumCollectionEntries in 1..1024)
        require(maximumNodesPerCommand in 1..4096)
        require(maximumStringChars in 1..16 * 1024)
        require(maximumBinaryBytes in 1..64 * 1024)
        require(maximumJsonPayloadChars in 32..256 * 1024)
        require(maximumSnapshotJsonBytes in 256..1024 * 1024)
    }
}

/** An immutable and JSON-safe value retained by [ClusterPresentationCoordinator]. */
sealed interface ClusterPresentationValue {
    data object Null : ClusterPresentationValue
    data class BooleanValue(val value: Boolean) : ClusterPresentationValue
    data class IntegerValue(val value: Long) : ClusterPresentationValue
    data class DecimalValue(val value: Double) : ClusterPresentationValue
    data class StringValue(val value: String) : ClusterPresentationValue
    data class BinaryValue(
        val base64: String,
        val byteLength: Int,
        val truncated: Boolean,
    ) : ClusterPresentationValue
    data class ArrayValue(val values: List<ClusterPresentationValue>) : ClusterPresentationValue
    data class ObjectValue(val entries: List<Entry>) : ClusterPresentationValue {
        data class Entry(val key: String, val value: ClusterPresentationValue)
    }
    data class Omitted(val reason: String) : ClusterPresentationValue
}

data class ClusterPresentationCommand(
    val revision: Long,
    val type: Int,
    /** Stable diagnostic name for known commands; null intentionally preserves an unknown type. */
    val name: String?,
    val data: ClusterPresentationValue,
    val truncated: Boolean,
)

/**
 * Current presentation metadata, not a replacement renderer for the phone's type-111 video.
 * [commands] is one complete replacement state. The legacy coordinator combines latest durable
 * values with bounded transient history; the UI Sync source adapter preserves its upstream replay
 * list instead. Consumers can de-duplicate by [ClusterPresentationCommand.revision].
 */
data class ClusterPresentationSnapshot(
    val revision: Long,
    val commands: List<ClusterPresentationCommand>,
    /** Events already evicted by the upstream replay source. */
    val droppedEventCount: Long = 0L,
    /** Events omitted locally to satisfy the WebDisplay bounds. */
    val adapterDroppedEventCount: Int = 0,
    /** Terminal snapshots remain replayable to browsers that attach after source shutdown. */
    val closed: Boolean = false,
)

fun interface ClusterPresentationObserver {
    fun onClusterPresentationChanged(snapshot: ClusterPresentationSnapshot)
}

/**
 * Controller-lifetime bridge between a validated UI Sync channel and replaceable UI observers.
 *
 * The protocol owns neither an Activity nor a browser connection. Observers get an immediate replay
 * and can detach independently, while the bounded current snapshot remains available to a later UI.
 */
class ClusterPresentationCoordinator(
    private val limits: ClusterPresentationLimits = ClusterPresentationLimits(),
) : CarPlayUiSyncPresentationEventSink {
    private val lock = Any()
    private val durableCommands = LinkedHashMap<Int, ClusterPresentationCommand>()
    private val transientCommands = ArrayDeque<ClusterPresentationCommand>()
    private val observers = LinkedHashMap<Long, ClusterPresentationObserver>()
    private var nextObserverId = 1L
    private var revision = 0L

    fun snapshot(): ClusterPresentationSnapshot = synchronized(lock) { snapshotLocked() }

    internal fun encodeForWeb(snapshot: ClusterPresentationSnapshot): String =
        ClusterPresentationJson.encodeBounded(snapshot, limits.maximumSnapshotJsonBytes)

    /** Registers [observer] and synchronously replays the current (possibly empty) snapshot. */
    fun observe(observer: ClusterPresentationObserver): Closeable {
        val id: Long
        val replay: ClusterPresentationSnapshot
        synchronized(lock) {
            id = nextObserverId++
            observers[id] = observer
            replay = snapshotLocked()
        }
        try {
            observer.onClusterPresentationChanged(replay)
        } catch (failure: Exception) {
            synchronized(lock) { observers.remove(id) }
            throw failure
        }
        return ObserverSubscription(this, id)
    }

    override fun onPresentationEvent(event: CarPlayUiSyncPresentationEvent) {
        val snapshot: ClusterPresentationSnapshot
        val targets: List<ClusterPresentationObserver>
        synchronized(lock) {
            revision = if (revision == Long.MAX_VALUE) 1L else revision + 1L
            val sanitized = PresentationSanitizer(limits).sanitize(event.data)
            var data = sanitized.value
            var truncated = sanitized.truncated
            if (ClusterPresentationJson.encodeValue(data).length > limits.maximumJsonPayloadChars) {
                data = ClusterPresentationValue.Omitted("encodedSizeLimit")
                truncated = true
            }
            val name = (event as? CarPlayUiSyncPresentationEvent.KnownCommand)
                ?.command
                ?.diagnosticName
            val command = ClusterPresentationCommand(
                revision = revision,
                type = event.type,
                name = name,
                data = data,
                truncated = truncated,
            )
            if (isTransient(event)) {
                transientCommands.addLast(command)
            } else {
                durableCommands.remove(event.type)
                durableCommands[event.type] = command
            }
            while (durableCommands.size + transientCommands.size > limits.maximumRetainedCommands) {
                if (transientCommands.isNotEmpty()) {
                    transientCommands.removeFirst()
                } else {
                    durableCommands.remove(durableCommands.keys.first())
                }
            }
            snapshot = snapshotLocked()
            targets = observers.values.toList()
        }
        targets.forEach { observer ->
            // A detached or faulty presentation surface must not tear down the protocol channel.
            runCatching { observer.onClusterPresentationChanged(snapshot) }
        }
    }

    private fun snapshotLocked() = ClusterPresentationSnapshot(
        revision,
        (durableCommands.values + transientCommands).sortedBy(ClusterPresentationCommand::revision),
    )

    private fun isTransient(event: CarPlayUiSyncPresentationEvent): Boolean {
        val known = event as? CarPlayUiSyncPresentationEvent.KnownCommand ?: return true
        return known.command.wireValue in TRANSIENT_COMMAND_TYPES
    }

    private fun removeObserver(id: Long) {
        synchronized(lock) { observers.remove(id) }
    }

    private class ObserverSubscription(
        private var owner: ClusterPresentationCoordinator?,
        private val id: Long,
    ) : Closeable {
        override fun close() {
            val coordinator = synchronized(this) { owner.also { owner = null } }
            coordinator?.removeObserver(id)
        }
    }

    private companion object {
        val TRANSIENT_COMMAND_TYPES = setOf(1, 2, 3, 4, 5, 6, 7, 8, 21, 22)
    }
}

/** Adapts a replayable coordinator to the cluster WebDisplay and nothing else. */
internal class ClusterPresentationWebDisplayAdapter(
    coordinator: ClusterPresentationCoordinator,
    private val target: WebDisplayPresentationLease,
) : Closeable {
    private val subscription = try {
        coordinator.observe { snapshot ->
            target.replace(coordinator.encodeForWeb(snapshot))
        }
    } catch (failure: Throwable) {
        target.close()
        throw failure
    }

    override fun close() {
        // Invalidate publication before detaching. A callback copied by the coordinator just
        // before observer removal can therefore never overwrite a replacement binding.
        target.close()
        subscription.close()
    }
}

/** Versioned text-control extension understood by the bundled page and optional custom pages. */
internal object ClusterPresentationJson {
    const val EVENT_TYPE = "clusterPresentation"
    const val VERSION = 1

    fun encode(snapshot: ClusterPresentationSnapshot): String = buildString {
        append("{\"type\":\"")
        append(EVENT_TYPE)
        append("\",\"version\":")
        append(VERSION)
        append(",\"presentation\":{\"revision\":")
        append(snapshot.revision)
        append(",\"droppedEventCount\":")
        append(snapshot.droppedEventCount)
        append(",\"adapterDroppedEventCount\":")
        append(snapshot.adapterDroppedEventCount)
        append(",\"closed\":")
        append(snapshot.closed)
        append(",\"commands\":[")
        snapshot.commands.forEachIndexed { index, command ->
            if (index != 0) append(',')
            append("{\"revision\":")
            append(command.revision)
            append(",\"type\":")
            append(command.type)
            append(",\"known\":")
            append(command.name != null)
            command.name?.let {
                append(",\"name\":")
                appendQuoted(it)
            }
            append(",\"truncated\":")
            append(command.truncated)
            append(",\"data\":")
            appendValue(command.data)
            append('}')
        }
        append("]}}")
    }

    /** Keeps the newest commands and accounts for every locally omitted command. */
    fun encodeBounded(snapshot: ClusterPresentationSnapshot, maximumUtf8Bytes: Int): String {
        var commands = snapshot.commands
        var locallyDropped = snapshot.adapterDroppedEventCount
        while (true) {
            val candidate = snapshot.copy(
                commands = commands,
                adapterDroppedEventCount = locallyDropped,
            )
            val json = encode(candidate)
            if (json.toByteArray(Charsets.UTF_8).size <= maximumUtf8Bytes) return json

            if (commands.size > 1) {
                commands = commands.drop(1)
                locallyDropped++
                continue
            }
            val only = commands.singleOrNull()
            if (only != null && only.data !is ClusterPresentationValue.Omitted) {
                commands = listOf(
                    only.copy(
                        data = ClusterPresentationValue.Omitted("snapshotEncodedSizeLimit"),
                        truncated = true,
                    ),
                )
                continue
            }
            if (only != null) {
                commands = emptyList()
                locallyDropped++
                continue
            }
            check(false) { "Cluster presentation JSON envelope exceeds its configured bound" }
        }
    }

    fun encodeValue(value: ClusterPresentationValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: ClusterPresentationValue) {
        when (value) {
            ClusterPresentationValue.Null -> append("null")
            is ClusterPresentationValue.BooleanValue -> append(value.value)
            is ClusterPresentationValue.IntegerValue -> append(value.value)
            is ClusterPresentationValue.DecimalValue -> append(value.value)
            is ClusterPresentationValue.StringValue -> appendQuoted(value.value)
            is ClusterPresentationValue.BinaryValue -> {
                append("{\"base64\":")
                appendQuoted(value.base64)
                append(",\"byteLength\":")
                append(value.byteLength)
                append(",\"truncated\":")
                append(value.truncated)
                append('}')
            }
            is ClusterPresentationValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index != 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is ClusterPresentationValue.ObjectValue -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index != 0) append(',')
                    appendQuoted(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is ClusterPresentationValue.Omitted -> {
                append("{\"omitted\":")
                appendQuoted(value.reason)
                append('}')
            }
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028', '\u2029' -> append("\\u${character.code.toString(16).padStart(4, '0')}")
                else -> if (character.code < 0x20) {
                    append("\\u${character.code.toString(16).padStart(4, '0')}")
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

internal class PresentationSanitizer(private val limits: ClusterPresentationLimits) {
    data class Result(val value: ClusterPresentationValue, val truncated: Boolean)

    private val visiting = IdentityHashMap<Any, Boolean>()
    private var remainingNodes = limits.maximumNodesPerCommand

    fun sanitize(value: Any?): Result = sanitize(value, depth = 0)

    private fun sanitize(value: Any?, depth: Int): Result {
        if (remainingNodes-- <= 0) return omitted("nodeLimit")
        if (depth > limits.maximumNestingDepth) return omitted("depthLimit")
        return when (value) {
            null -> Result(ClusterPresentationValue.Null, false)
            is Boolean -> Result(ClusterPresentationValue.BooleanValue(value), false)
            is Byte, is Short, is Int, is Long -> Result(
                ClusterPresentationValue.IntegerValue((value as Number).toLong()),
                false,
            )
            is Float, is Double -> {
                val number = (value as Number).toDouble()
                if (number.isFinite()) Result(ClusterPresentationValue.DecimalValue(number), false)
                else omitted("nonFiniteNumber")
            }
            is Number -> {
                val text = value.toString()
                if (text.matches(JSON_NUMBER)) {
                    val number = text.toDoubleOrNull()
                    if (number != null && number.isFinite()) {
                        Result(ClusterPresentationValue.DecimalValue(number), false)
                    } else {
                        omitted("unsupportedNumber")
                    }
                } else {
                    omitted("unsupportedNumber")
                }
            }
            is String -> {
                val truncated = value.length > limits.maximumStringChars
                Result(
                    ClusterPresentationValue.StringValue(
                        truncateWithoutSplittingSurrogate(value, limits.maximumStringChars),
                    ),
                    truncated,
                )
            }
            is ByteArray -> {
                val previewSize = minOf(value.size, limits.maximumBinaryBytes)
                val preview = value.copyOfRange(0, previewSize)
                Result(
                    ClusterPresentationValue.BinaryValue(
                        base64 = Base64.getEncoder().encodeToString(preview),
                        byteLength = value.size,
                        truncated = previewSize != value.size,
                    ),
                    previewSize != value.size,
                )
            }
            is List<*> -> sanitizeCollection(value, depth)
            is Array<*> -> sanitizeCollection(value.asList(), depth)
            is Map<*, *> -> sanitizeMap(value, depth)
            else -> omitted("unsupported:${value.javaClass.simpleName.take(64)}")
        }
    }

    private fun sanitizeCollection(value: List<*>, depth: Int): Result = guardCycle(value) {
        var truncated = value.size > limits.maximumCollectionEntries
        val values = value.take(limits.maximumCollectionEntries).map { item ->
            sanitize(item, depth + 1).also { truncated = truncated || it.truncated }.value
        }
        Result(ClusterPresentationValue.ArrayValue(values), truncated)
    }

    private fun sanitizeMap(value: Map<*, *>, depth: Int): Result = guardCycle(value) {
        var truncated = value.size > limits.maximumCollectionEntries
        val entries = value.entries.take(limits.maximumCollectionEntries).map { entry ->
            val key = entry.key as? String
            val safeKey = if (key == null) {
                truncated = true
                "unsupportedKey"
            } else {
                if (key.length > limits.maximumStringChars) truncated = true
                truncateWithoutSplittingSurrogate(key, limits.maximumStringChars)
            }
            val item = sanitize(entry.value, depth + 1)
            truncated = truncated || item.truncated
            ClusterPresentationValue.ObjectValue.Entry(safeKey, item.value)
        }
        Result(ClusterPresentationValue.ObjectValue(entries), truncated)
    }

    private inline fun guardCycle(container: Any, block: () -> Result): Result {
        if (visiting.put(container, true) != null) return omitted("cycle")
        return try {
            block()
        } finally {
            visiting.remove(container)
        }
    }

    private fun omitted(reason: String) = Result(ClusterPresentationValue.Omitted(reason), true)

    private fun truncateWithoutSplittingSurrogate(value: String, maximum: Int): String {
        if (value.length <= maximum) return value
        var end = maximum
        if (end > 0 && value[end - 1].isHighSurrogate()) end--
        return value.substring(0, end)
    }

    private companion object {
        val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
