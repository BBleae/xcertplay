package com.shilapi.xcertplay.vehicle.ultra

import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A UI Sync display identifier. The value is deliberately opaque and case-sensitive: it is not a
 * UUID and must never be joined to another identifier namespace by comparing their text.
 */
data class UltraUiSyncDisplayId(val value: String) {
    init {
        requireOpaqueBindingIdentifier(value, "UI Sync displayId")
    }
}

/** A physical display UUID from the independently decoded CarPlay display-topology document. */
data class UltraTopologyDisplayId(val value: UUID)

/**
 * An explicitly observed AirPlay stream identity. [type] remains part of the identity even when a
 * connection ID or client UUID is present; none of these fields is inferred from a display ID.
 */
data class UltraAirPlayStreamIdentity(
    val type: Int,
    val streamConnectionId: String? = null,
    val clientTypeUuid: String? = null,
) {
    init {
        require(type >= 0) { "AirPlay stream type must not be negative" }
        streamConnectionId?.let {
            requireOpaqueBindingIdentifier(it, "AirPlay streamConnectionId")
        }
        clientTypeUuid?.let {
            requireOpaqueBindingIdentifier(it, "AirPlay clientTypeUuid")
        }
    }
}

/**
 * A concrete presentation destination. Android and custom renderer identifiers are separate
 * namespaces even if their opaque strings happen to be equal.
 */
sealed interface UltraDisplayOutputId {
    val value: String

    data class Android(override val value: String) : UltraDisplayOutputId {
        init {
            requireOpaqueBindingIdentifier(value, "Android output ID")
        }
    }

    data class Custom(override val value: String) : UltraDisplayOutputId {
        init {
            requireOpaqueBindingIdentifier(value, "custom output ID")
        }
    }
}

/** One fully explicit, one-to-one route across the four independent identifier namespaces. */
data class UltraDisplayBinding(
    val uiSyncDisplayId: UltraUiSyncDisplayId,
    val topologyDisplayId: UltraTopologyDisplayId,
    val airPlayStreamIdentity: UltraAirPlayStreamIdentity,
    val outputId: UltraDisplayOutputId,
)

/**
 * The exact resources that one replacement transaction is required to bind.
 *
 * This is intentionally not a loose "known resources" inventory. Every resource supplied here
 * must occur exactly once in the replacement, so a partially wired renderer cannot accidentally
 * become the published state.
 */
class UltraDisplayBindingResources(
    uiSyncDisplayIds: Collection<UltraUiSyncDisplayId>,
    topologyDisplayIds: Collection<UltraTopologyDisplayId>,
    airPlayStreamIdentities: Collection<UltraAirPlayStreamIdentity>,
    outputIds: Collection<UltraDisplayOutputId>,
) {
    val uiSyncDisplayIds: Set<UltraUiSyncDisplayId> = immutableUniqueSet(
        uiSyncDisplayIds,
        "UI Sync display IDs",
    )
    val topologyDisplayIds: Set<UltraTopologyDisplayId> = immutableUniqueSet(
        topologyDisplayIds,
        "topology display IDs",
    )
    val airPlayStreamIdentities: Set<UltraAirPlayStreamIdentity> = immutableUniqueSet(
        airPlayStreamIdentities,
        "AirPlay stream identities",
    )
    val outputIds: Set<UltraDisplayOutputId> = immutableUniqueSet(outputIds, "output IDs")

    init {
        val sizes = setOf(
            this.uiSyncDisplayIds.size,
            this.topologyDisplayIds.size,
            this.airPlayStreamIdentities.size,
            this.outputIds.size,
        )
        require(sizes.size == 1) {
            "Display binding resource namespaces must have equal cardinality"
        }
        require(this.uiSyncDisplayIds.size <= MAXIMUM_DISPLAY_BINDINGS) {
            "Display binding resources exceed $MAXIMUM_DISPLAY_BINDINGS entries"
        }
    }

    internal fun detached(): UltraDisplayBindingResources = UltraDisplayBindingResources(
        uiSyncDisplayIds,
        topologyDisplayIds,
        airPlayStreamIdentities,
        outputIds,
    )

    companion object {
        internal val EMPTY = UltraDisplayBindingResources(
            emptySet(),
            emptySet(),
            emptySet(),
            emptySet(),
        )
    }
}

/** An immutable, point-in-time view of an [UltraDisplayBindingRegistry]. */
class UltraDisplayBindingSnapshot internal constructor(
    val revision: Long,
    resources: UltraDisplayBindingResources,
    bindings: List<UltraDisplayBinding>,
) {
    val resources: UltraDisplayBindingResources = resources.detached()
    val bindings: List<UltraDisplayBinding> = Collections.unmodifiableList(bindings.toList())

    private val byUiSyncDisplay = this.bindings.associateBy(UltraDisplayBinding::uiSyncDisplayId)
    private val byTopologyDisplay = this.bindings.associateBy(UltraDisplayBinding::topologyDisplayId)
    private val byAirPlayStream = this.bindings.associateBy(UltraDisplayBinding::airPlayStreamIdentity)
    private val byOutput = this.bindings.associateBy(UltraDisplayBinding::outputId)

    fun bindingFor(id: UltraUiSyncDisplayId): UltraDisplayBinding? = byUiSyncDisplay[id]

    fun bindingFor(id: UltraTopologyDisplayId): UltraDisplayBinding? = byTopologyDisplay[id]

    fun bindingFor(id: UltraAirPlayStreamIdentity): UltraDisplayBinding? = byAirPlayStream[id]

    fun bindingFor(id: UltraDisplayOutputId): UltraDisplayBinding? = byOutput[id]
}

/**
 * Atomic registry for explicit renderer routes.
 *
 * A replacement is validated in full before it can be published. Invalid cardinality, duplicate
 * identities, or a reference outside the supplied resource snapshot leaves the previous revision
 * untouched. There is intentionally no API that accepts a raw String or infers a route because a
 * UI Sync display ID happens to spell the same text as a topology UUID or output ID.
 */
class UltraDisplayBindingRegistry {
    private data class State(
        val revision: Long,
        val resources: UltraDisplayBindingResources,
        val bindings: List<UltraDisplayBinding>,
    )

    private val state = AtomicReference(
        State(
            revision = 0L,
            resources = UltraDisplayBindingResources.EMPTY,
            bindings = emptyList(),
        ),
    )

    fun snapshot(): UltraDisplayBindingSnapshot = state.get().toSnapshot()

    fun replace(
        resources: UltraDisplayBindingResources,
        bindings: Collection<UltraDisplayBinding>,
    ): UltraDisplayBindingSnapshot {
        val detachedResources = resources.detached()
        val detachedBindings = validateReplacement(detachedResources, bindings)

        while (true) {
            val current = state.get()
            check(current.revision != Long.MAX_VALUE) {
                "Display binding revision exhausted"
            }
            val replacement = State(
                revision = current.revision + 1L,
                resources = detachedResources,
                bindings = detachedBindings,
            )
            if (state.compareAndSet(current, replacement)) return replacement.toSnapshot()
        }
    }

    private fun State.toSnapshot(): UltraDisplayBindingSnapshot = UltraDisplayBindingSnapshot(
        revision,
        resources,
        bindings,
    )

    private companion object {
        fun validateReplacement(
            resources: UltraDisplayBindingResources,
            requestedBindings: Collection<UltraDisplayBinding>,
        ): List<UltraDisplayBinding> {
            val bindings = boundedBindingSnapshot(requestedBindings, "display bindings")
            require(bindings.toSet().size == bindings.size) {
                "Display bindings must not contain duplicate rows"
            }

            val uiSyncIds = bindings.map(UltraDisplayBinding::uiSyncDisplayId)
            val topologyIds = bindings.map(UltraDisplayBinding::topologyDisplayId)
            val streamIds = bindings.map(UltraDisplayBinding::airPlayStreamIdentity)
            val outputIds = bindings.map(UltraDisplayBinding::outputId)

            requireUnique(uiSyncIds, "UI Sync display ID")
            requireUnique(topologyIds, "topology display ID")
            requireUnique(streamIds, "AirPlay stream identity")
            requireUnique(outputIds, "output ID")

            require(uiSyncIds.toSet() == resources.uiSyncDisplayIds) {
                "Display bindings must reference every supplied UI Sync display ID exactly once"
            }
            require(topologyIds.toSet() == resources.topologyDisplayIds) {
                "Display bindings must reference every supplied topology display ID exactly once"
            }
            require(streamIds.toSet() == resources.airPlayStreamIdentities) {
                "Display bindings must reference every supplied AirPlay stream exactly once"
            }
            require(outputIds.toSet() == resources.outputIds) {
                "Display bindings must reference every supplied output exactly once"
            }
            return Collections.unmodifiableList(bindings)
        }

        fun <T> requireUnique(values: List<T>, label: String) {
            require(values.toSet().size == values.size) {
                "Each $label may appear in at most one display binding"
            }
        }
    }
}

private const val MAXIMUM_DISPLAY_BINDINGS = 64
// Match the UI Sync protocol's absolute identifier ceiling without imposing UUID/text semantics.
private const val MAXIMUM_BINDING_IDENTIFIER_CHARS = 8 * 1024

internal fun requireOpaqueBindingIdentifier(value: String, label: String) {
    require(value.isNotEmpty()) { "$label must not be empty" }
    require(value.length <= MAXIMUM_BINDING_IDENTIFIER_CHARS) {
        "$label exceeds $MAXIMUM_BINDING_IDENTIFIER_CHARS characters"
    }
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.none(Char::isISOControl)) { "$label contains a control character" }
}

private fun <T> immutableUniqueSet(values: Collection<T>, label: String): Set<T> {
    val snapshot = boundedBindingSnapshot(values, label)
    val unique = LinkedHashSet(snapshot)
    require(unique.size == snapshot.size) { "$label must not contain duplicates" }
    return Collections.unmodifiableSet(unique)
}

private fun <T> boundedBindingSnapshot(values: Collection<T>, label: String): List<T> {
    val declaredSize = values.size
    require(declaredSize in 0..MAXIMUM_DISPLAY_BINDINGS) {
        "$label exceeds $MAXIMUM_DISPLAY_BINDINGS entries"
    }
    val snapshot = ArrayList<T>(declaredSize)
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        require(snapshot.size < MAXIMUM_DISPLAY_BINDINGS) {
            "$label exceeds $MAXIMUM_DISPLAY_BINDINGS entries"
        }
        snapshot += iterator.next()
    }
    return snapshot
}
