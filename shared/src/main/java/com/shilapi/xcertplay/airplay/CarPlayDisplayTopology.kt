package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Resource limits for untrusted display-topology property-list trees. */
data class CarPlayDisplayTopologyLimits(
    val maxDepth: Int = 12,
    val maxNodes: Int = 4_096,
    val maxCollectionEntries: Int = 2_048,
    val maxStringChars: Long = 128 * 1_024,
    val maxDataBytes: Long = 4 * 1_024 * 1_024,
    val maxDisplays: Int = 32,
    val maxPanels: Int = 256,
) {
    init {
        require(maxDepth in 1..64)
        require(maxNodes > 0)
        require(maxCollectionEntries > 0)
        require(maxStringChars > 0)
        require(maxDataBytes > 0)
        require(maxDisplays > 0)
        require(maxPanels > 0)
    }

    companion object {
        val DEFAULT = CarPlayDisplayTopologyLimits()
    }
}

/**
 * An observed display-panel identity. `ScreenID` and `UID` are deliberately different types:
 * CarKit consumes them on different paths and there is no evidence that their namespaces alias.
 */
sealed class CarPlayDisplayPanelIdentity private constructor() {
    abstract val value: String
    abstract val wireKey: String

    class ScreenId internal constructor(override val value: String) : CarPlayDisplayPanelIdentity() {
        override val wireKey: String = SCREEN_ID
        override fun equals(other: Any?): Boolean = other is ScreenId && value == other.value
        override fun hashCode(): Int = 31 * wireKey.hashCode() + value.hashCode()
        override fun toString(): String = "$wireKey($value)"
    }

    class Uid internal constructor(override val value: String) : CarPlayDisplayPanelIdentity() {
        override val wireKey: String = UID
        override fun equals(other: Any?): Boolean = other is Uid && value == other.value
        override fun hashCode(): Int = 31 * wireKey.hashCode() + value.hashCode()
        override fun toString(): String = "$wireKey($value)"
    }

    fun toWireMap(): Map<String, Any?> = mapOf(wireKey to value)

    internal fun detached(): CarPlayDisplayPanelIdentity = when (this) {
        is ScreenId -> ScreenId(value)
        is Uid -> Uid(value)
    }

    companion object {
        internal const val SCREEN_ID = "ScreenID"
        internal const val UID = "UID"

        internal fun parse(values: Map<String, Any?>, path: String): CarPlayDisplayPanelIdentity {
            val hasScreenId = values.containsKey(SCREEN_ID)
            val hasUid = values.containsKey(UID)
            require(hasScreenId.xor(hasUid)) {
                "$path must contain exactly one of ScreenID or UID"
            }
            val key = if (hasScreenId) SCREEN_ID else UID
            val value = values[key] as? String
                ?: throw IllegalArgumentException("$path.$key must be a string")
            require(value.isNotBlank() && value.length <= MAX_ID_CHARS && value.none(Char::isISOControl)) {
                "$path.$key is not a valid opaque panel identifier"
            }
            return if (hasScreenId) ScreenId(value) else Uid(value)
        }

        private const val MAX_ID_CHARS = 256
    }
}

/** Immutable, validated physical-display description. */
class CarPlayPhysicalDisplay internal constructor(
    val uuid: UUID,
    val widthPixels: Int,
    val heightPixels: Int,
    val originXPixels: Int,
    val originYPixels: Int,
    val widthPhysical: Double,
    val heightPhysical: Double,
    properties: FrozenTopologyValue.Dictionary,
    initialVideoStreams: FrozenTopologyValue.Array,
    videoStreams: FrozenTopologyValue.Array,
    managedVideoStreams: FrozenTopologyValue.Array,
    val zIndex: Int,
    displayPanels: List<CarPlayDisplayPanelIdentity>,
    displayPanelPayloads: FrozenTopologyValue.Array,
    displayPlugins: FrozenTopologyValue,
    automakerInputStreams: FrozenTopologyValue.Array,
) {
    private val frozenProperties = properties
    private val frozenInitialVideoStreams = initialVideoStreams
    private val frozenVideoStreams = videoStreams
    private val frozenManagedVideoStreams = managedVideoStreams
    private val frozenDisplayPanels = displayPanels.map(CarPlayDisplayPanelIdentity::detached)
    private val frozenDisplayPanelPayloads = displayPanelPayloads
    private val frozenDisplayPlugins = displayPlugins
    private val frozenAutomakerInputStreams = automakerInputStreams

    val properties: Map<String, Any?> get() = frozenProperties.toPublicMap()
    val initialVideoStreams: List<Any?> get() = frozenInitialVideoStreams.toPublicList()
    val videoStreams: List<Any?> get() = frozenVideoStreams.toPublicList()
    val managedVideoStreams: List<Any?> get() = frozenManagedVideoStreams.toPublicList()
    val displayPanels: List<CarPlayDisplayPanelIdentity>
        get() = frozenDisplayPanels.map(CarPlayDisplayPanelIdentity::detached)
    val displayPanelPayloads: List<Any?> get() = frozenDisplayPanelPayloads.toPublicList()
    val displayPlugins: Any? get() = frozenDisplayPlugins.toPublic()
    val automakerInputStreams: List<Any?> get() = frozenAutomakerInputStreams.toPublicList()

    internal fun panelIdentities(): List<CarPlayDisplayPanelIdentity> = frozenDisplayPanels
    internal fun detached(): CarPlayPhysicalDisplay = CarPlayPhysicalDisplay(
        uuid,
        widthPixels,
        heightPixels,
        originXPixels,
        originYPixels,
        widthPhysical,
        heightPhysical,
        frozenProperties,
        frozenInitialVideoStreams,
        frozenVideoStreams,
        frozenManagedVideoStreams,
        zIndex,
        frozenDisplayPanels,
        frozenDisplayPanelPayloads,
        frozenDisplayPlugins,
        frozenAutomakerInputStreams,
    )
}

/**
 * Strict, evidence-backed topology snapshot. It is intentionally not wired into `/info` or feature
 * negotiation: knowing these field names does not establish an enablement contract.
 */
class CarPlayDisplayTopology private constructor(displays: List<CarPlayPhysicalDisplay>) {
    private val frozenDisplays = displays.map(CarPlayPhysicalDisplay::detached)
    private val panelsByIdentity: Map<CarPlayDisplayPanelIdentity, UUID> = buildMap {
        frozenDisplays.forEach { display ->
            display.panelIdentities().forEach { identity -> put(identity, display.uuid) }
        }
    }

    val displays: List<CarPlayPhysicalDisplay> get() = frozenDisplays.map(CarPlayPhysicalDisplay::detached)

    fun containsDisplay(uuid: UUID): Boolean = frozenDisplays.any { it.uuid == uuid }

    fun containsPanel(identity: CarPlayDisplayPanelIdentity): Boolean = identity in panelsByIdentity

    internal fun ownerOf(identity: CarPlayDisplayPanelIdentity): UUID? = panelsByIdentity[identity]

    internal fun detached(): CarPlayDisplayTopology = CarPlayDisplayTopology(frozenDisplays)

    companion object {
        private val DISPLAY_KEYS = setOf(
            "uuid",
            "widthPixels",
            "heightPixels",
            "originXPixels",
            "originYPixels",
            "widthPhysical",
            "heightPhysical",
            "properties",
            "initialVideoStreams",
            "videoStreams",
            "managedVideoStreams",
            "zIndex",
            "displayPanels",
            "displayPlugins",
            "automakerInputStreams",
        )
        private val UUID_PATTERN = Regex(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-" +
                "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
        )
        private const val MAX_GEOMETRY = 1_000_000

        fun parse(
            rawDisplays: Any?,
            limits: CarPlayDisplayTopologyLimits = CarPlayDisplayTopologyLimits.DEFAULT,
        ): CarPlayDisplayTopology {
            val freezer = TopologyValueFreezer(limits)
            val frozen = freezer.freeze(rawDisplays, "displays") as? FrozenTopologyValue.Array
                ?: throw IllegalArgumentException("displays must be an array")
            require(frozen.values.isNotEmpty()) { "displays must not be empty" }
            require(frozen.values.size <= limits.maxDisplays) {
                "displays exceeds ${limits.maxDisplays} entries"
            }
            val displays = frozen.values.mapIndexed { index, item ->
                parseDisplay(item, "displays[$index]", limits)
            }
            require(displays.map(CarPlayPhysicalDisplay::uuid).toSet().size == displays.size) {
                "display UUIDs must be unique"
            }
            val allPanels = displays.flatMap(CarPlayPhysicalDisplay::panelIdentities)
            require(allPanels.size <= limits.maxPanels) {
                "displayPanels exceeds ${limits.maxPanels} entries"
            }
            require(allPanels.toSet().size == allPanels.size) {
                "display panel identities must be unique, including their ScreenID/UID namespace"
            }
            return CarPlayDisplayTopology(displays)
        }

        private fun parseDisplay(
            item: FrozenTopologyValue,
            path: String,
            limits: CarPlayDisplayTopologyLimits,
        ): CarPlayPhysicalDisplay {
            val map = (item as? FrozenTopologyValue.Dictionary)?.values
                ?: throw IllegalArgumentException("$path must be a dictionary")
            require(map.keys.all { it in DISPLAY_KEYS }) {
                "$path contains unsupported keys ${map.keys - DISPLAY_KEYS}"
            }
            val uuidText = map.string("uuid", path)
            require(UUID_PATTERN.matches(uuidText)) { "$path.uuid must be a canonical UUID string" }
            val uuid = try {
                UUID.fromString(uuidText)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("$path.uuid must be a UUID", error)
            }
            val panelPayloads = map.optionalArray("displayPanels", path)
            val panelIdentities = parsePanelIdentities(panelPayloads, "$path.displayPanels", limits)
            val initialStreams = map.optionalArray("initialVideoStreams", path)
            val streams = map.optionalArray("videoStreams", path)
            val managedStreams = map.optionalArray("managedVideoStreams", path)
            val automakerStreams = map.optionalArray("automakerInputStreams", path)
            validateOptionalStreamUuids(initialStreams, "$path.initialVideoStreams")
            validateOptionalStreamUuids(streams, "$path.videoStreams")
            validateOptionalStreamUuids(managedStreams, "$path.managedVideoStreams")
            return CarPlayPhysicalDisplay(
                uuid = uuid,
                widthPixels = map.positiveInt("widthPixels", path),
                heightPixels = map.positiveInt("heightPixels", path),
                originXPixels = map.boundedInt("originXPixels", path, default = 0),
                originYPixels = map.boundedInt("originYPixels", path, default = 0),
                widthPhysical = map.positiveFinite("widthPhysical", path),
                heightPhysical = map.positiveFinite("heightPhysical", path),
                properties = map.optionalDictionary("properties", path),
                initialVideoStreams = initialStreams,
                videoStreams = streams,
                managedVideoStreams = managedStreams,
                zIndex = map.boundedInt("zIndex", path, default = 0),
                displayPanels = panelIdentities,
                displayPanelPayloads = panelPayloads,
                displayPlugins = map.optionalContainer("displayPlugins", path),
                automakerInputStreams = automakerStreams,
            )
        }

        private fun Map<String, FrozenTopologyValue>.string(key: String, path: String): String =
            (this[key] as? FrozenTopologyValue.Text)?.value
                ?: throw IllegalArgumentException("$path.$key must be a string")

        private fun Map<String, FrozenTopologyValue>.positiveInt(key: String, path: String): Int {
            val value = integer(key, path)
            require(value in 1..MAX_GEOMETRY.toLong()) { "$path.$key must be positive and bounded" }
            return value.toInt()
        }

        private fun Map<String, FrozenTopologyValue>.boundedInt(
            key: String,
            path: String,
            default: Int,
        ): Int {
            if (key !in this) return default
            val value = integer(key, path)
            require(value in -MAX_GEOMETRY.toLong()..MAX_GEOMETRY.toLong()) {
                "$path.$key is outside the supported coordinate range"
            }
            return value.toInt()
        }

        private fun Map<String, FrozenTopologyValue>.integer(key: String, path: String): Long =
            when (val value = this[key]) {
                is FrozenTopologyValue.Integer -> value.value
                is FrozenTopologyValue.BigInt -> value.value.longValueExactOrNull()
                    ?: throw IllegalArgumentException("$path.$key is outside the signed 64-bit range")
                else -> throw IllegalArgumentException("$path.$key must be an integer")
            }

        private fun Map<String, FrozenTopologyValue>.positiveFinite(key: String, path: String): Double {
            val value = when (val item = this[key]) {
                is FrozenTopologyValue.Integer -> item.value.toDouble()
                is FrozenTopologyValue.BigInt -> item.value.toDouble()
                is FrozenTopologyValue.Real -> item.value
                else -> throw IllegalArgumentException("$path.$key must be numeric")
            }
            require(value.isFinite() && value > 0.0 && value <= MAX_GEOMETRY) {
                "$path.$key must be positive, finite, and bounded"
            }
            return value
        }

        private fun Map<String, FrozenTopologyValue>.optionalDictionary(
            key: String,
            path: String,
        ): FrozenTopologyValue.Dictionary = when (val value = this[key]) {
            null -> FrozenTopologyValue.Dictionary(emptyMap())
            is FrozenTopologyValue.Dictionary -> value
            else -> throw IllegalArgumentException("$path.$key must be a dictionary")
        }

        private fun parsePanelIdentities(
            array: FrozenTopologyValue.Array,
            path: String,
            limits: CarPlayDisplayTopologyLimits,
        ): List<CarPlayDisplayPanelIdentity> {
            require(array.values.size <= limits.maxPanels) { "$path exceeds ${limits.maxPanels} entries" }
            return array.values.mapIndexed { index, item ->
                val dictionary = (item as? FrozenTopologyValue.Dictionary)?.toPublicMap()
                    ?: throw IllegalArgumentException("$path[$index] must be a dictionary")
                CarPlayDisplayPanelIdentity.parse(dictionary, "$path[$index]")
            }
        }

        private fun Map<String, FrozenTopologyValue>.optionalArray(
            key: String,
            path: String,
        ): FrozenTopologyValue.Array = when (val value = this[key]) {
            null -> FrozenTopologyValue.Array(emptyList())
            is FrozenTopologyValue.Array -> value
            else -> throw IllegalArgumentException("$path.$key must be an array")
        }

        private fun Map<String, FrozenTopologyValue>.optionalContainer(
            key: String,
            path: String,
        ): FrozenTopologyValue = when (val value = this[key]) {
            null -> FrozenTopologyValue.Array(emptyList())
            is FrozenTopologyValue.Array, is FrozenTopologyValue.Dictionary -> value
            else -> throw IllegalArgumentException("$path.$key must be an array or dictionary")
        }

        private fun validateOptionalStreamUuids(array: FrozenTopologyValue.Array, path: String) {
            array.values.forEachIndexed { index, item ->
                val dictionary = item as? FrozenTopologyValue.Dictionary ?: return@forEachIndexed
                val uuid = dictionary.values["uuid"] ?: return@forEachIndexed
                val text = (uuid as? FrozenTopologyValue.Text)?.value
                    ?: throw IllegalArgumentException("$path[$index].uuid must be a string")
                require(UUID_PATTERN.matches(text)) { "$path[$index].uuid must be a canonical UUID" }
            }
        }

        private fun BigInteger.longValueExactOrNull(): Long? =
            takeIf {
                it >= BigInteger.valueOf(Long.MIN_VALUE) &&
                    it <= BigInteger.valueOf(Long.MAX_VALUE)
            }?.toLong()
    }
}

/** A detached point-in-time view of [CarPlayDisplayTopologyHolder]. */
class CarPlayDisplayTopologySnapshot internal constructor(
    val revision: Long,
    topology: CarPlayDisplayTopology,
    panelUpdates: Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary>,
    pluginUpdatesByDisplay: Map<UUID, Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary>>,
) {
    private val frozenTopology = topology.detached()
    private val frozenPanelUpdates = panelUpdates.mapKeys { it.key.detached() }
    private val frozenPluginUpdates = pluginUpdatesByDisplay.mapValues { (_, panels) ->
        panels.mapKeys { it.key.detached() }
    }

    val topology: CarPlayDisplayTopology get() = frozenTopology.detached()
    val panelUpdates: Map<CarPlayDisplayPanelIdentity, Map<String, Any?>>
        get() = frozenPanelUpdates.entries.associateTo(LinkedHashMap()) {
            it.key.detached() to it.value.toPublicMap()
        }
    val pluginUpdatesByDisplay: Map<UUID, Map<CarPlayDisplayPanelIdentity, Map<String, Any?>>>
        get() = frozenPluginUpdates.mapValues { (_, panels) ->
            panels.entries.associateTo(LinkedHashMap()) {
                it.key.detached() to it.value.toPublicMap()
            }
        }
}

/**
 * Atomic holder for the two update shapes observed beside the physical-display description.
 * Updates are fully frozen, parsed, and reference-checked before the CAS publishes a new revision.
 */
class CarPlayDisplayTopologyHolder(
    topology: CarPlayDisplayTopology,
    private val limits: CarPlayDisplayTopologyLimits = CarPlayDisplayTopologyLimits.DEFAULT,
) {
    private data class State(
        val revision: Long,
        val topology: CarPlayDisplayTopology,
        val panelUpdates: Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary>,
        val pluginUpdates: Map<UUID, Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary>>,
    )

    private val state = AtomicReference(State(0, topology.detached(), emptyMap(), emptyMap()))

    fun snapshot(): CarPlayDisplayTopologySnapshot = state.get().toSnapshot()

    fun updateDisplayPanels(raw: Any?): CarPlayDisplayTopologySnapshot {
        val parsed = parsePanelUpdates(raw, "updateDisplayPanels")
        while (true) {
            val current = state.get()
            parsed.keys.forEach { identity ->
                require(current.topology.containsPanel(identity)) {
                    "updateDisplayPanels refers to unknown panel $identity"
                }
            }
            val next = current.copy(
                revision = Math.addExact(current.revision, 1),
                panelUpdates = parsed,
            )
            if (state.compareAndSet(current, next)) return next.toSnapshot()
        }
    }

    fun updateDisplayPlugins(displayUuid: String, raw: Any?): CarPlayDisplayTopologySnapshot {
        val uuid = parseStrictUuid(displayUuid, "updateDisplayPlugins display UUID")
        val parsed = parsePluginUpdates(raw)
        while (true) {
            val current = state.get()
            require(current.topology.containsDisplay(uuid)) {
                "updateDisplayPlugins refers to unknown display $uuid"
            }
            parsed.keys.forEach { identity ->
                require(current.topology.ownerOf(identity) == uuid) {
                    "updateDisplayPlugins refers to panel $identity outside display $uuid"
                }
            }
            val plugins = LinkedHashMap(current.pluginUpdates)
            plugins[uuid] = parsed
            val next = current.copy(
                revision = Math.addExact(current.revision, 1),
                pluginUpdates = Collections.unmodifiableMap(plugins),
            )
            if (state.compareAndSet(current, next)) return next.toSnapshot()
        }
    }

    private fun parsePanelUpdates(
        raw: Any?,
        path: String,
    ): Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary> = parseUpdates(raw, path)

    private fun parsePluginUpdates(raw: Any?): Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary> =
        parseUpdates(raw, "updateDisplayPlugins")

    private fun parseUpdates(
        raw: Any?,
        path: String,
    ): Map<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary> {
        val frozen = TopologyValueFreezer(limits).freeze(raw, path) as? FrozenTopologyValue.Dictionary
            ?: throw IllegalArgumentException("$path must be a dictionary")
        require(frozen.values.keys == setOf("displayPanels")) {
            "$path must contain only displayPanels"
        }
        val panels = frozen.values["displayPanels"] as? FrozenTopologyValue.Array
            ?: throw IllegalArgumentException("$path.displayPanels must be an array")
        require(panels.values.size <= limits.maxPanels) {
            "$path.displayPanels exceeds ${limits.maxPanels} entries"
        }
        val result = LinkedHashMap<CarPlayDisplayPanelIdentity, FrozenTopologyValue.Dictionary>()
        panels.values.forEachIndexed { index, item ->
            val dictionary = item as? FrozenTopologyValue.Dictionary
                ?: throw IllegalArgumentException("$path.displayPanels[$index] must be a dictionary")
            val public = dictionary.toPublicMap()
            val identity = CarPlayDisplayPanelIdentity.parse(public, "$path.displayPanels[$index]")
            (dictionary.values["videoStreams"] as? FrozenTopologyValue.Array)?.let {
                validateOptionalStreamUuids(it, "$path.displayPanels[$index].videoStreams")
            }
            require(result.put(identity, dictionary) == null) {
                "$path contains duplicate panel $identity"
            }
        }
        return Collections.unmodifiableMap(result)
    }

    private fun validateOptionalStreamUuids(array: FrozenTopologyValue.Array, path: String) {
        array.values.forEachIndexed { index, item ->
            val values = (item as? FrozenTopologyValue.Dictionary)?.values ?: return@forEachIndexed
            val uuid = values["uuid"] ?: return@forEachIndexed
            val text = (uuid as? FrozenTopologyValue.Text)?.value
                ?: throw IllegalArgumentException("$path[$index].uuid must be a string")
            require(STRICT_UUID.matches(text)) { "$path[$index].uuid must be a canonical UUID" }
        }
    }

    private fun State.toSnapshot(): CarPlayDisplayTopologySnapshot =
        CarPlayDisplayTopologySnapshot(revision, topology, panelUpdates, pluginUpdates)

    private fun parseStrictUuid(value: String, path: String): UUID {
        require(STRICT_UUID.matches(value)) { "$path must be a canonical UUID string" }
        return UUID.fromString(value)
    }

    companion object {
        private val STRICT_UUID = Regex(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-" +
                "[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
        )
    }
}

internal sealed interface FrozenTopologyValue {
    data object Null : FrozenTopologyValue
    class Bool(val value: Boolean) : FrozenTopologyValue
    class Integer(val value: Long) : FrozenTopologyValue
    class BigInt(val value: BigInteger) : FrozenTopologyValue
    class Real(val value: Double) : FrozenTopologyValue
    class Text(val value: String) : FrozenTopologyValue
    class Data(bytes: ByteArray) : FrozenTopologyValue {
        private val bytes = bytes.copyOf()
        fun copyBytes(): ByteArray = bytes.copyOf()
    }
    class Array(val values: List<FrozenTopologyValue>) : FrozenTopologyValue {
        fun toPublicList(): List<Any?> = values.map(FrozenTopologyValue::toPublic)
    }
    class Dictionary(val values: Map<String, FrozenTopologyValue>) : FrozenTopologyValue {
        fun toPublicMap(): Map<String, Any?> = values.entries.associateTo(LinkedHashMap()) {
            it.key to it.value.toPublic()
        }
    }

    fun toPublic(): Any? = when (this) {
        Null -> null
        is Bool -> value
        is Integer -> value
        is BigInt -> value
        is Real -> value
        is Text -> value
        is Data -> copyBytes()
        is Array -> values.map(FrozenTopologyValue::toPublic)
        is Dictionary -> toPublicMap()
    }
}

private class TopologyValueFreezer(private val limits: CarPlayDisplayTopologyLimits) {
    private var nodes = 0
    private var collectionEntries = 0
    private var stringChars = 0L
    private var dataBytes = 0L
    private val active = IdentityHashMap<Any, Unit>()

    fun freeze(value: Any?, path: String): FrozenTopologyValue = freeze(value, path, depth = 0)

    private fun freeze(value: Any?, path: String, depth: Int): FrozenTopologyValue {
        require(depth <= limits.maxDepth) { "$path exceeds nesting depth ${limits.maxDepth}" }
        require(++nodes <= limits.maxNodes) { "$path exceeds node budget ${limits.maxNodes}" }
        return when (value) {
            null -> FrozenTopologyValue.Null
            is Boolean -> FrozenTopologyValue.Bool(value)
            is Byte -> FrozenTopologyValue.Integer(value.toLong())
            is Short -> FrozenTopologyValue.Integer(value.toLong())
            is Int -> FrozenTopologyValue.Integer(value.toLong())
            is Long -> FrozenTopologyValue.Integer(value)
            is BigInteger -> FrozenTopologyValue.BigInt(value)
            is Float -> {
                require(value.isFinite()) { "$path must be finite" }
                FrozenTopologyValue.Real(value.toDouble())
            }
            is Double -> {
                require(value.isFinite()) { "$path must be finite" }
                FrozenTopologyValue.Real(value)
            }
            is String -> {
                stringChars = Math.addExact(stringChars, value.length.toLong())
                require(stringChars <= limits.maxStringChars) { "$path exceeds string budget" }
                FrozenTopologyValue.Text(value)
            }
            is ByteArray -> {
                dataBytes = Math.addExact(dataBytes, value.size.toLong())
                require(dataBytes <= limits.maxDataBytes) { "$path exceeds data budget" }
                FrozenTopologyValue.Data(value)
            }
            is List<*> -> withCollection(value, path) {
                consumeEntries(value.size, path)
                FrozenTopologyValue.Array(
                    Collections.unmodifiableList(
                        value.mapIndexed { index, item -> freeze(item, "$path[$index]", depth + 1) },
                    ),
                )
            }
            is Map<*, *> -> withCollection(value, path) {
                consumeEntries(value.size, path)
                val copied = LinkedHashMap<String, FrozenTopologyValue>(value.size)
                value.forEach { (key, item) ->
                    val name = key as? String
                        ?: throw IllegalArgumentException("$path keys must be strings")
                    require(name !in copied) { "$path contains duplicate key '$name'" }
                    stringChars = Math.addExact(stringChars, name.length.toLong())
                    require(stringChars <= limits.maxStringChars) { "$path exceeds string budget" }
                    copied[name] = freeze(item, "$path.$name", depth + 1)
                }
                FrozenTopologyValue.Dictionary(Collections.unmodifiableMap(copied))
            }
            else -> throw IllegalArgumentException(
                "$path contains unsupported wire value ${value::class.java.simpleName}",
            )
        }
    }

    private fun consumeEntries(count: Int, path: String) {
        collectionEntries = Math.addExact(collectionEntries, count)
        require(collectionEntries <= limits.maxCollectionEntries) {
            "$path exceeds collection-entry budget ${limits.maxCollectionEntries}"
        }
    }

    private inline fun <T> withCollection(source: Any, path: String, block: () -> T): T {
        require(active.put(source, Unit) == null) { "$path contains a collection cycle" }
        return try {
            block()
        } finally {
            active.remove(source)
        }
    }
}
