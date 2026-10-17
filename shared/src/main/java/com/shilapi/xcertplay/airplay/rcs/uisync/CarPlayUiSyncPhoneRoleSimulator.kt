package com.shilapi.xcertplay.airplay.rcs.uisync

import java.util.UUID

/**
 * Executable model of the iPhone side of one UI Sync display session, transcribed from iOS 27.0.1
 * (24A446) `DashBoard` `DBUISyncSession`: self Reset `sub_25c7bb254`, message handler
 * `sub_25c7bc020`, accessory Reset `sub_25c7bb45c`, ResetComplete `sub_25c7bdb4c`, send
 * `sub_25c7b9528`, and the `DBUISyncChannel` packet/payload routing.
 *
 * It exists so the accessory state machine can be exercised against the phone's rules: the
 * router's readiness self-test and unit tests. It is not a transport, owns no socket and is never
 * attached to a real ClusterControl stream. Phone rejections are silent drops (the phone only
 * logs), reported here through [lastDropReason]. Not modelled: the DisplayAssetConfig payload
 * that the phone adds when our Reset omits `id` (this simulator behaves as if that file is
 * absent), delegate/asset callbacks, and the phone's own command producers.
 */
class CarPlayUiSyncPhoneRoleSimulator(
    val displayId: String,
    /** The phone-generated CarPlay identifier sent as `id`; random per instance by default. */
    val phoneVehicleId: String = UUID.randomUUID().toString().uppercase(),
    private val maximumVersion: CarPlayUiSyncProtocolVersion = CarPlayUiSyncProtocolVersion.V3,
    private val limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
) {
    var state: CarPlayUiSyncSessionState = CarPlayUiSyncSessionState.INITIALIZED
        private set
    var sessionSequenceNumber: Long = 0L
        private set
    var nextTransmitPacketSequenceNumber: Long = 0L
        private set
    var lastReceivedPacketSequenceNumber: Long = 0L
        private set
    var lastAcknowledgedPacketSequenceNumber: Long = 0L
        private set
    var negotiatedVersion: CarPlayUiSyncProtocolVersion? = null
        private set

    /** Why the last [receive] produced no state change, or null if it was accepted. */
    var lastDropReason: String? = null
        private set

    /** Number of `didReset` callbacks (an accessory Reset while READY). */
    var resetsFromReady: Int = 0
        private set

    private val received = ArrayList<CarPlayUiSyncCommandEnvelope>()

    /** Accessory commands accepted in order. */
    val receivedCommands: List<CarPlayUiSyncCommandEnvelope>
        get() = received.toList()

    /** Session opened: seed state 1 and send the self Reset as plist Data (`sub_25c7bb254`). */
    fun open(): ByteArray {
        state = CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
        sessionSequenceNumber = 1L
        nextTransmitPacketSequenceNumber = 1L
        lastReceivedPacketSequenceNumber = 0L
        lastAcknowledgedPacketSequenceNumber = 0L
        negotiatedVersion = null
        return send(
            type = CarPlayUiSyncMessageType.RESET,
            vehicleId = phoneVehicleId,
            version = maximumVersion,
        )
    }

    /** Applies one accessory carrier and returns what the phone would send in reply. */
    fun receive(carrierBytes: ByteArray): List<ByteArray> {
        lastDropReason = null
        val carrier = decode(carrierBytes) ?: return drop("Invalid message")
        if (carrier.displayId != displayId) return drop("unknown display")
        if (carrier.representation == CarPlayUiSyncCarrierRepresentation.DICTIONARY) {
            val version = negotiatedVersion
                ?: return drop("Received packet but no version negotiated")
            if (version != CarPlayUiSyncProtocolVersion.V3) {
                return drop("Received packet with invalid version")
            }
        }
        if (state == CarPlayUiSyncSessionState.INITIALIZED) {
            return drop("Ignoring message while in `initialized` state")
        }
        val message = carrier.message
        if (message.type == CarPlayUiSyncMessageType.RESET) return receiveAccessoryReset(message)
        if (message.sessionSequenceNumber != sessionSequenceNumber) return drop("Invalid ssn")
        return when (message.type) {
            CarPlayUiSyncMessageType.RESET -> error("unreachable")
            CarPlayUiSyncMessageType.RESET_COMPLETE -> receiveResetComplete(message)
            CarPlayUiSyncMessageType.COMMAND,
            CarPlayUiSyncMessageType.COMMAND_ACK,
            -> receiveReadyMessage(message)
        }
    }

    /**
     * Runs the complete exchange against an accessory. [deliver] hands one phone carrier to the
     * accessory and returns the carriers it wrote back. Throws unless both sides reach READY.
     */
    fun handshake(deliver: (ByteArray) -> List<ByteArray>) {
        val accessoryReset = deliver(open()).singleOrNull()
            ?: error("accessory did not answer the phone reset with exactly one carrier")
        val phoneResetComplete = receive(accessoryReset).singleOrNull()
            ?: error("phone dropped the accessory reset: $lastDropReason")
        val accessoryResetComplete = deliver(phoneResetComplete).singleOrNull()
            ?: error("accessory did not answer the phone resetComplete with exactly one carrier")
        check(receive(accessoryResetComplete).isEmpty() && lastDropReason == null) {
            "phone dropped the accessory resetComplete: $lastDropReason"
        }
        check(state == CarPlayUiSyncSessionState.READY) { "phone is not ready" }
    }

    /** `sendCommand` (`sub_25c7b9208`): READY only, no id, no version. */
    fun sendCommand(
        command: CarPlayUiSyncCommandEnvelope,
        noAck: Boolean = false,
    ): ByteArray {
        check(state == CarPlayUiSyncSessionState.READY) {
            "Attempting to send command while not in `ready` state"
        }
        return send(
            type = CarPlayUiSyncMessageType.COMMAND,
            payload = command,
            noAck = noAck,
        )
    }

    fun sendCommand(
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): ByteArray = sendCommand(CarPlayUiSyncCommandEnvelope(command.wireValue, data), noAck)

    private fun receiveAccessoryReset(message: CarPlayUiSyncMessage): List<ByteArray> {
        val id = message.vehicleId
        if (id != null && id != phoneVehicleId) {
            return drop("Received invalid reset vehicle id")
        }
        val previous = state
        state = CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED
        if (previous == CarPlayUiSyncSessionState.READY) resetsFromReady++
        sessionSequenceNumber = message.sessionSequenceNumber
        nextTransmitPacketSequenceNumber = 1L
        lastReceivedPacketSequenceNumber = message.packetSequenceNumber
        lastAcknowledgedPacketSequenceNumber = 0L
        val requested = message.version ?: CarPlayUiSyncProtocolVersion.V1
        val version = CarPlayUiSyncProtocolVersion.negotiate(maximumVersion, requested)
        negotiatedVersion = version
        if (sessionSequenceNumber < 1L) {
            // send() throws sessionNotInitialized; state is already 2 but nothing is sent.
            return drop("Error handling message")
        }
        return listOf(
            send(
                type = CarPlayUiSyncMessageType.RESET_COMPLETE,
                vehicleId = phoneVehicleId.takeIf { version != CarPlayUiSyncProtocolVersion.V1 },
                version = version,
            ),
        )
    }

    private fun receiveResetComplete(message: CarPlayUiSyncMessage): List<ByteArray> {
        if (state != CarPlayUiSyncSessionState.AWAITING_RESET_COMPLETE_REMOTE_INITIATED) {
            return drop("Discarding `reset complete` message while not in state")
        }
        val negotiated = negotiatedVersion ?: return drop("fault: no negotiated version")
        if ((message.version ?: negotiated) != negotiated) {
            return drop("Received invalid reset complete protocol version")
        }
        if (negotiated != CarPlayUiSyncProtocolVersion.V1 && message.vehicleId != phoneVehicleId) {
            return drop("Received invalid reset complete vehicle id")
        }
        lastReceivedPacketSequenceNumber = message.packetSequenceNumber
        lastAcknowledgedPacketSequenceNumber = message.acknowledgementSequenceNumber
        state = CarPlayUiSyncSessionState.READY
        return emptyList()
    }

    private fun receiveReadyMessage(message: CarPlayUiSyncMessage): List<ByteArray> {
        if (state != CarPlayUiSyncSessionState.READY) {
            return drop("Discarding message while in state")
        }
        if (message.packetSequenceNumber != lastReceivedPacketSequenceNumber + 1L) {
            return drop("Invalid psn")
        }
        if (message.acknowledgementSequenceNumber < lastAcknowledgedPacketSequenceNumber ||
            message.acknowledgementSequenceNumber >= nextTransmitPacketSequenceNumber
        ) {
            return drop("Invalid ack sn")
        }
        lastReceivedPacketSequenceNumber = message.packetSequenceNumber
        lastAcknowledgedPacketSequenceNumber = message.acknowledgementSequenceNumber
        if (message.type != CarPlayUiSyncMessageType.COMMAND) return emptyList()
        val outbound = if (message.noAck) {
            emptyList()
        } else {
            listOf(send(type = CarPlayUiSyncMessageType.COMMAND_ACK))
        }
        received += message.payload as CarPlayUiSyncCommandEnvelope
        return outbound
    }

    /** `sub_25c7b9528`: stamp ssn/psn/asn, choose the representation from the negotiated version. */
    private fun send(
        type: CarPlayUiSyncMessageType,
        payload: CarPlayUiSyncMessagePayload? = null,
        noAck: Boolean = false,
        vehicleId: String? = null,
        version: CarPlayUiSyncProtocolVersion? = null,
    ): ByteArray {
        check(sessionSequenceNumber >= 1L && nextTransmitPacketSequenceNumber >= 1L) {
            "sessionNotInitialized"
        }
        val negotiated = negotiatedVersion
        val representation = if (negotiated == CarPlayUiSyncProtocolVersion.V3) {
            CarPlayUiSyncCarrierRepresentation.DICTIONARY
        } else {
            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST
        }
        val message = CarPlayUiSyncMessage(
            type = type,
            sessionSequenceNumber = sessionSequenceNumber,
            packetSequenceNumber = nextTransmitPacketSequenceNumber,
            acknowledgementSequenceNumber = lastReceivedPacketSequenceNumber,
            payload = payload,
            noAck = noAck,
            vehicleId = vehicleId,
            version = version,
        )
        val encoded = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                displayId = displayId,
                serializationVersion = version ?: negotiated ?: maximumVersion,
                message = message,
                representation = representation,
            ),
            limits,
        )
        nextTransmitPacketSequenceNumber++
        return encoded
    }

    /** Parser `sub_25c6f52b8`: a missing version defaults to the negotiated one (or v1). */
    private fun decode(bytes: ByteArray): CarPlayUiSyncCarrier? = try {
        CarPlayUiSyncCodec.decode(bytes, limits)
    } catch (_: CarPlayUiSyncProtocolException) {
        try {
            CarPlayUiSyncCodec.decode(
                bytes,
                limits,
                expectedVersion = negotiatedVersion ?: CarPlayUiSyncProtocolVersion.V1,
            )
        } catch (_: CarPlayUiSyncProtocolException) {
            null
        }
    }

    private fun drop(reason: String): List<ByteArray> {
        lastDropReason = reason
        return emptyList()
    }
}
