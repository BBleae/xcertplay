package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.util.Collections

/**
 * Immutable display-to-endpoint table for one AirPlay control session.
 *
 * A ClusterControl RCS stream is shared by every advertised display, but each display keeps an
 * independent UI Sync sequence space and reset state. Duplicate opaque display IDs are rejected
 * instead of being resolved by registration order.
 */
class CarPlayUiSyncEndpointRegistry(
    endpoints: Collection<CarPlayUiSyncEndpoint>,
) {
    val endpoints: List<CarPlayUiSyncEndpoint>
    val displayIds: Set<String>

    init {
        val snapshot = ArrayList<CarPlayUiSyncEndpoint>()
        val iterator = endpoints.iterator()
        while (iterator.hasNext()) {
            require(snapshot.size < MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS) {
                "UI Sync endpoint registry exceeds " +
                    "$MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS entries"
            }
            snapshot += iterator.next()
        }
        val ids = LinkedHashSet<String>(snapshot.size)
        snapshot.forEach { endpoint ->
            require(ids.add(endpoint.descriptor.displayId)) {
                "Duplicate UI Sync display ID '${endpoint.descriptor.displayId}'"
            }
        }
        this.endpoints = Collections.unmodifiableList(snapshot)
        displayIds = Collections.unmodifiableSet(ids)
    }

    val isEmpty: Boolean
        get() = endpoints.isEmpty()

    val isProtocolRuntimeComplete: Boolean = endpoints.isNotEmpty() && endpoints.all {
        it.probeProtocolRuntime()
    }

    /** Creates fresh per-display state machines for one physical ClusterControl channel. */
    fun openRouter(): CarPlayUiSyncRouter = CarPlayUiSyncRouter(endpoints)
}

private fun CarPlayUiSyncEndpoint.probeProtocolRuntime(): Boolean {
    if (!isProtocolRuntimeComplete ||
        descriptor.clusterControlRcsUuid != RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid ||
        descriptor.displayId.length > limits.maximumIdentifierChars ||
        descriptor.vehicleId.length > limits.maximumIdentifierChars
    ) {
        return false
    }

    // Self-test against an executable model of the iPhone's DBUISyncSession rules (phone Reset
    // first, our plist Reset, phone ResetComplete, our ResetComplete, then one acked command).
    val local = openChannel()
    val phone = CarPlayUiSyncPhoneRoleSimulator(
        displayId = descriptor.displayId,
        maximumVersion = maximumVersion,
        limits = limits,
    )
    return try {
        if (local.open().outboundCarriers.isNotEmpty()) {
            throw CarPlayUiSyncProtocolException("UI Sync endpoint spoke before the phone reset")
        }
        val ourReset = local.receive(phone.open()).outboundCarriers.single()
        val phoneResetComplete = phone.receive(ourReset).single()
        val ourResetComplete = local.receive(phoneResetComplete).outboundCarriers.single()
        if (phone.receive(ourResetComplete).isNotEmpty() || phone.lastDropReason != null ||
            local.snapshot().state != CarPlayUiSyncSessionState.READY ||
            phone.state != CarPlayUiSyncSessionState.READY
        ) {
            throw CarPlayUiSyncProtocolException("UI Sync readiness probe did not reach ready")
        }
        val command = local.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            mapOf("layoutID" to "readiness-probe"),
        ).outboundCarriers.single()
        val acknowledgement = phone.receive(command).singleOrNull()
            ?: throw CarPlayUiSyncProtocolException("UI Sync readiness probe command was dropped")
        local.receive(acknowledgement)
        local.snapshot().pendingAcknowledgements.isEmpty() && phone.receivedCommands.size == 1
    } catch (_: Exception) {
        false
    } finally {
        local.close()
    }
}

enum class CarPlayUiSyncRoutingDiagnosticKind {
    MALFORMED_CARRIER,
    UNKNOWN_DISPLAY,
    DISPLAY_PROTOCOL_ERROR,
}

/** A bounded diagnostic that never retains the rejected carrier or exception. */
data class CarPlayUiSyncRoutingDiagnostic(
    val kind: CarPlayUiSyncRoutingDiagnosticKind,
    val displayId: String? = null,
) {
    init {
        require(displayId == null || displayId.length <= MAXIMUM_DIAGNOSTIC_ID_CHARS)
    }

    private companion object {
        const val MAXIMUM_DIAGNOSTIC_ID_CHARS = 128
    }
}

data class CarPlayUiSyncRouterActions(
    val outboundCarriers: List<ByteArray> = emptyList(),
    val presentationEvents: List<CarPlayUiSyncPresentationEvent> = emptyList(),
    val diagnostics: List<CarPlayUiSyncRoutingDiagnostic> = emptyList(),
)

/**
 * Routes one shared ClusterControl stream to independent per-display UI Sync state machines.
 *
 * Malformed input is contained to the selected display. It is reported as bounded metadata and
 * dropped; it never closes the physical RCS stream and is never offered to another display.
 */
class CarPlayUiSyncRouter internal constructor(
    endpoints: List<CarPlayUiSyncEndpoint>,
) : AutoCloseable {
    private val lock = Any()
    private val channels = endpoints.associateTo(linkedMapOf()) { endpoint ->
        endpoint.descriptor.displayId to endpoint.openChannel()
    }
    private val routingLimits = aggregateRoutingLimits(endpoints)
    private var open = false

    val displayIds: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(channels.keys),
    )

    fun open(): CarPlayUiSyncRouterActions = synchronized(lock) {
        if (open) throw CarPlayUiSyncProtocolException("UI Sync router is already open")
        val outbound = ArrayList<ByteArray>(channels.size)
        try {
            channels.values.forEach { channel ->
                outbound += channel.open().outboundCarriers
            }
            open = true
            CarPlayUiSyncRouterActions(outboundCarriers = outbound)
        } catch (failure: Throwable) {
            channels.values.forEach { runCatching { it.close() } }
            throw failure
        }
    }

    fun receive(carrierBytes: ByteArray): CarPlayUiSyncRouterActions {
        val deferred = receiveWithoutPresentationDelivery(carrierBytes)
        deferred.deliverPresentationEvents()
        return deferred.actions
    }

    /** Routes and mutates state now, but leaves the arbitrary presentation callback deferred. */
    internal fun receiveWithoutPresentationDelivery(
        carrierBytes: ByteArray,
    ): DeferredCarPlayUiSyncRouterActions {
        val displayId = try {
            CarPlayUiSyncCodec.peekDisplayId(carrierBytes, routingLimits)
        } catch (failure: Exception) {
            return DeferredCarPlayUiSyncRouterActions(
                diagnostic(CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER),
                rejection = failure,
            )
        }
        val channel = synchronized(lock) {
            requireOpen()
            channels[displayId]
        } ?: return DeferredCarPlayUiSyncRouterActions(
            diagnostic(CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY, displayId),
        )
        return try {
            val actions = channel.receiveWithoutPresentationDelivery(carrierBytes)
            DeferredCarPlayUiSyncRouterActions(actions.toRouterActions()) {
                channel.deliverPresentationEvents(actions)
            }
        } catch (failure: Exception) {
            DeferredCarPlayUiSyncRouterActions(
                diagnostic(
                    CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                    displayId,
                ),
                rejection = failure,
            )
        }
    }

    fun sendCommand(
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): CarPlayUiSyncRouterActions {
        val channel = synchronized(lock) {
            requireOpen()
            channels[displayId]
        } ?: return diagnostic(
            CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY,
            displayId,
        )
        return try {
            channel.sendCommand(command, data, noAck).toRouterActions()
        } catch (_: Exception) {
            diagnostic(
                CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                displayId,
            )
        }
    }

    fun sendCommand(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand,
        noAck: Boolean = false,
    ): CarPlayUiSyncRouterActions {
        val channel = synchronized(lock) {
            requireOpen()
            channels[displayId]
        } ?: return diagnostic(
            CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY,
            displayId,
        )
        return try {
            channel.sendCommand(command, noAck).toRouterActions()
        } catch (_: Exception) {
            diagnostic(
                CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                displayId,
            )
        }
    }

    fun snapshot(displayId: String): CarPlayUiSyncSessionSnapshot? =
        synchronized(lock) { channels[displayId] }?.snapshot()

    override fun close() {
        synchronized(lock) {
            channels.values.forEach { runCatching { it.close() } }
            open = false
        }
    }

    private fun requireOpen() {
        if (!open) throw CarPlayUiSyncProtocolException("UI Sync router is not open")
    }

    private companion object {
        const val MAXIMUM_ROUTED_ID_CHARS = 128

        fun diagnostic(
            kind: CarPlayUiSyncRoutingDiagnosticKind,
            displayId: String? = null,
        ) = CarPlayUiSyncRouterActions(
            diagnostics = listOf(
                CarPlayUiSyncRoutingDiagnostic(
                    kind,
                    displayId
                        ?.take(MAXIMUM_ROUTED_ID_CHARS)
                        ?.filterNot(Char::isISOControl),
                ),
            ),
        )
    }
}

/**
 * Protocol actions plus presentation work that must run outside transport serialization.
 *
 * [rejection] is the transient cause of a MALFORMED_CARRIER or DISPLAY_PROTOCOL_ERROR result. It
 * exists only so the Debug-only carrier-shape diagnostic can report a redacted class/message; it is
 * never copied into [CarPlayUiSyncRoutingDiagnostic] or retained after this receive.
 */
internal class DeferredCarPlayUiSyncRouterActions(
    val actions: CarPlayUiSyncRouterActions,
    val rejection: Throwable? = null,
    private val delivery: () -> Unit = {},
) {
    fun deliverPresentationEvents() = delivery()
}

private fun CarPlayUiSyncActions.toRouterActions() = CarPlayUiSyncRouterActions(
    outboundCarriers = outboundCarriers,
    presentationEvents = presentationEvents,
)

private fun aggregateRoutingLimits(
    endpoints: List<CarPlayUiSyncEndpoint>,
): CarPlayUiSyncLimits {
    if (endpoints.isEmpty()) return CarPlayUiSyncLimits()
    val limits = endpoints.map(CarPlayUiSyncEndpoint::limits)
    return CarPlayUiSyncLimits(
        maximumCarrierBytes = limits.maxOf(CarPlayUiSyncLimits::maximumCarrierBytes),
        maximumInnerMessageBytes = limits.maxOf(CarPlayUiSyncLimits::maximumInnerMessageBytes),
        maximumValueBytes = limits.maxOf(CarPlayUiSyncLimits::maximumValueBytes),
        maximumStringChars = limits.maxOf(CarPlayUiSyncLimits::maximumStringChars),
        maximumIdentifierChars = limits.maxOf(CarPlayUiSyncLimits::maximumIdentifierChars),
        maximumCollectionEntries = limits.maxOf(CarPlayUiSyncLimits::maximumCollectionEntries),
        maximumNestingDepth = limits.maxOf(CarPlayUiSyncLimits::maximumNestingDepth),
        maximumPendingAcknowledgements =
            limits.maxOf(CarPlayUiSyncLimits::maximumPendingAcknowledgements),
        maximumRememberedFrames = limits.maxOf(CarPlayUiSyncLimits::maximumRememberedFrames),
        maximumRememberedFrameBytes =
            limits.maxOf(CarPlayUiSyncLimits::maximumRememberedFrameBytes),
    )
}
