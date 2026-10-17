package com.shilapi.xcertplay.vehicle.ultra

import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference

/** Explicit scene-addressable ingress identity; it is never inferred from another namespace. */
@JvmInline
value class UltraIngressId(val value: String) {
    init {
        requireOpaqueBindingIdentifier(value, "Ultra ingress ID")
    }
}

/** One session-bound remote stream resource. It is not assigned to an output until a scene uses it. */
data class UltraDisplayGraphIngress(
    val id: UltraIngressId,
    val stream: AirPlayVideoStreamHandle,
)

/**
 * One physical/custom output resource. UI Sync is optional for local-only OEM output surfaces;
 * topology and output identifiers remain separate typed namespaces.
 */
data class UltraDisplayGraphOutput(
    val topologyDisplayId: UltraTopologyDisplayId,
    val outputId: UltraDisplayOutputId,
    val uiSyncDisplayId: UltraUiSyncDisplayId? = null,
)

/**
 * Immutable resource graph for a multi-stream, multi-output renderer.
 *
 * Unlike [UltraDisplayBindingSnapshot], cardinalities are independent: scenes may reference zero,
 * one, or several ingresses per output, and the same ingress may be referenced by several output
 * scenes. No relationship is created by matching opaque text.
 */
class UltraDisplayGraphSnapshot internal constructor(
    val revision: Long,
    outputs: List<UltraDisplayGraphOutput>,
    ingresses: List<UltraDisplayGraphIngress>,
) {
    val outputs: List<UltraDisplayGraphOutput> =
        Collections.unmodifiableList(ArrayList(outputs))
    val ingresses: List<UltraDisplayGraphIngress> =
        Collections.unmodifiableList(ArrayList(ingresses))

    private val outputById = this.outputs.associateBy(UltraDisplayGraphOutput::outputId)
    private val outputByTopology =
        this.outputs.associateBy(UltraDisplayGraphOutput::topologyDisplayId)
    private val outputByUiSync = this.outputs.mapNotNull { output ->
        output.uiSyncDisplayId?.let { it to output }
    }.toMap()
    private val ingressById = this.ingresses.associateBy(UltraDisplayGraphIngress::id)

    fun output(id: UltraDisplayOutputId): UltraDisplayGraphOutput? = outputById[id]
    fun output(id: UltraTopologyDisplayId): UltraDisplayGraphOutput? = outputByTopology[id]
    fun output(id: UltraUiSyncDisplayId): UltraDisplayGraphOutput? = outputByUiSync[id]
    fun ingress(id: UltraIngressId): UltraDisplayGraphIngress? = ingressById[id]
}

/** Atomic, complete replacement of the resources available to a future scene/compositor. */
class UltraDisplayGraphRegistry {
    private data class State(val snapshot: UltraDisplayGraphSnapshot)

    private val state = AtomicReference(
        State(UltraDisplayGraphSnapshot(0L, emptyList(), emptyList())),
    )

    fun snapshot(): UltraDisplayGraphSnapshot = state.get().snapshot

    fun replace(
        outputs: Collection<UltraDisplayGraphOutput>,
        ingresses: Collection<UltraDisplayGraphIngress>,
    ): UltraDisplayGraphSnapshot {
        val frozenOutputs = boundedSnapshot(
            outputs,
            MAXIMUM_GRAPH_OUTPUTS,
            "Ultra display graph outputs",
        )
        val frozenIngresses = boundedSnapshot(
            ingresses,
            MAXIMUM_GRAPH_INGRESSES,
            "Ultra display graph ingresses",
        )
        validateOutputs(frozenOutputs)
        validateIngresses(frozenIngresses)

        while (true) {
            val current = state.get()
            check(current.snapshot.revision < Long.MAX_VALUE) {
                "Ultra display graph revision exhausted"
            }
            val replacement = UltraDisplayGraphSnapshot(
                revision = current.snapshot.revision + 1L,
                outputs = frozenOutputs,
                ingresses = frozenIngresses,
            )
            if (state.compareAndSet(current, State(replacement))) return replacement
        }
    }

    private fun validateOutputs(outputs: List<UltraDisplayGraphOutput>) {
        require(outputs.map(UltraDisplayGraphOutput::outputId).toSet().size == outputs.size) {
            "Ultra display graph contains duplicate output IDs"
        }
        require(outputs.map(UltraDisplayGraphOutput::topologyDisplayId).toSet().size == outputs.size) {
            "Ultra display graph contains duplicate topology display IDs"
        }
        val uiSyncIds = outputs.mapNotNull(UltraDisplayGraphOutput::uiSyncDisplayId)
        require(uiSyncIds.toSet().size == uiSyncIds.size) {
            "Ultra display graph contains duplicate UI Sync display IDs"
        }
    }

    private fun validateIngresses(ingresses: List<UltraDisplayGraphIngress>) {
        require(ingresses.map(UltraDisplayGraphIngress::id).toSet().size == ingresses.size) {
            "Ultra display graph contains duplicate ingress IDs"
        }
        val handles = IdentityHashMap<AirPlayVideoStreamHandle, Unit>()
        require(ingresses.all { handles.put(it.stream, Unit) == null }) {
            "One session-bound stream handle cannot back multiple ingress IDs"
        }
    }

    private fun <T> boundedSnapshot(
        values: Collection<T>,
        limit: Int,
        label: String,
    ): List<T> {
        val result = ArrayList<T>(minOf(limit, 16))
        val iterator = values.iterator()
        while (iterator.hasNext()) {
            if (result.size == limit) {
                iterator.next()
                throw IllegalArgumentException("$label exceed $limit entries")
            }
            result += iterator.next()
        }
        return Collections.unmodifiableList(result)
    }

    private companion object {
        const val MAXIMUM_GRAPH_OUTPUTS = 64
        const val MAXIMUM_GRAPH_INGRESSES = 128
    }
}
