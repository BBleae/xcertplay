package com.shilapi.xcertplay.airplay.rcs.uisync

import java.util.LinkedHashMap
import java.util.TreeSet
import java.security.MessageDigest

/**
 * Accessory reset policy.
 *
 * iOS 27.0.1 DashBoard (`DBUISyncSession`) has exactly one policy that reaches READY with a phone:
 * the phone sends its own Reset when the channel opens, enters state 1 and discards every
 * ResetComplete there (`sub_25c7bdb4c` gate at `0x25c7bdef8`). Only an accessory Reset moves it
 * to state 2, after which it answers with ResetComplete and waits for ours. A Reset sent before
 * the phone's own Reset is ignored in state 0 (`0x25c7bc1ac`) and the session has no timer or
 * retransmission, so an accessory never speaks first. The former symmetric/"active" policy is
 * not supported by the binary and was removed.
 */
enum class CarPlayUiSyncMode {
    /** Wait for the phone's Reset, answer with our Reset, then complete the phone's ResetComplete. */
    PASSIVE,
}

enum class CarPlayUiSyncSessionState(val wireValue: Int) {
    INITIALIZED(0),
    AWAITING_RESET_SELF_INITIATED(1),
    AWAITING_RESET_COMPLETE_REMOTE_INITIATED(2),
    READY(3),
}

/** Immutable, capability-gating configuration. Each RCS open must call [openChannel]. */
class CarPlayUiSyncEndpoint(
    val descriptor: CarPlayUiSyncChannelDescriptor,
    val mode: CarPlayUiSyncMode,
    val presentationEventSink: CarPlayUiSyncPresentationEventSink,
    val maximumVersion: CarPlayUiSyncProtocolVersion = CarPlayUiSyncProtocolVersion.V3,
    val limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    outboundResetConfig: Any? = null,
    /** Receiver-local monotonic clock, sampled only for a newly accepted command. */
    private val acceptedCommandClockNanos: () -> Long = System::nanoTime,
) {
    // Copy once at the API boundary. The caller cannot mutate a later ResetComplete by retaining a
    // Map/List/ByteArray reference, and channels never expose this private snapshot.
    private val immutableOutboundResetConfig: Any? = outboundResetConfig?.let {
        copyCarPlayUiSyncBoundedValue(it, limits)
    }

    /**
     * True by construction: a descriptor, bounded codec, state machine, and presentation consumer
     * are all present. Transport readiness remains the responsibility of the RCS handler probe.
     */
    val isProtocolRuntimeComplete: Boolean
        get() = true

    fun openChannel(): CarPlayUiSyncChannel = CarPlayUiSyncChannel(
        descriptor = descriptor,
        mode = mode,
        eventSink = presentationEventSink,
        maximumVersion = maximumVersion,
        limits = limits,
        outboundResetConfig = immutableOutboundResetConfig,
        acceptedCommandClockNanos = acceptedCommandClockNanos,
    )
}

data class CarPlayUiSyncActions(
    val outboundCarriers: List<ByteArray> = emptyList(),
    val presentationEvents: List<CarPlayUiSyncPresentationEvent> = emptyList(),
)

data class CarPlayUiSyncSessionSnapshot(
    val open: Boolean,
    val state: CarPlayUiSyncSessionState,
    val sessionSequenceNumber: Long,
    val nextTransmitPacketSequenceNumber: Long,
    val lastReceivedPacketSequenceNumber: Long,
    val lastAcknowledgedPacketSequenceNumber: Long,
    val pendingAcknowledgements: Set<Long>,
    val negotiatedVersion: CarPlayUiSyncProtocolVersion?,
    val rememberedFrameCount: Int,
    val rememberedFrameBytes: Long,
    /**
     * Resets this accessory sent on its own to resynchronize a READY session after a phone packet
     * was lost (see `CarPlayUiSyncChannel` "Recovery"). Answers to a phone Reset are not counted.
     */
    val accessoryResetCount: Long = 0L,
)

/**
 * Accessory side of one UI Sync display session on one ClusterControl RCS channel.
 *
 * Wire model (iOS 27.0.1 24A446 DashBoard, see docs/carplay-ultra.md "UI Sync handshake"):
 *
 * 1. phone -> us: Reset `{type 0, ssn 1, psn 1, asn 0, id <phone id>, version 3}` as plist Data.
 * 2. us -> phone: Reset `{type 0, ssn 1, psn 1, asn 1, id <phone id>, version}` as plist Data
 *    (the phone has no negotiated version yet, so its packet path drops dictionaries).
 * 3. phone -> us: ResetComplete `{type 1, ssn 1, psn 1, asn 1, id, version}`; a dictionary once
 *    the phone negotiated v3, plist Data for v1/v2.
 * 4. us -> phone: ResetComplete `{type 1, ssn 1, psn 2, asn 1, id, version}` as plist Data
 *    (the phone's payload path is never version-gated).
 *
 * Recovery: the phone counts a packet as sent once it writes it and has no timer, retransmission
 * or re-Reset (`send` has four call sites; the self Reset runs only on channel open). If we cannot
 * apply a phone packet that it did send (its OPACK uses a type this codec does not model, a value
 * exceeds [CarPlayUiSyncLimits], a command ACK arrives for nothing pending, ...), the phone's next
 * packet arrives with a psn beyond `lastRx + 1` and could never be accepted again: both directions
 * would stall until the phone reopens its channel. Instead, a READY channel that sees a phone
 * command or commandAck with the current ssn and a skipped psn sends its own plist Reset
 * `{type 0, ssn S+1, psn 1, asn, id, version}` (accepted by the phone in state 3, which reseeds its
 * counters and answers with a ResetComplete) and resumes at step 3. The packet that revealed the
 * gap is not applied, since the phone abandons that sequence space on our Reset.
 *
 * The `id` is a phone-generated identifier. It is learned from the first phone Reset on this
 * channel and every later message that carries an id must repeat it. It is deliberately not the
 * signed Profile's [CarPlayUiSyncChannelDescriptor.vehicleId]: the phone never sees that value,
 * and authentication of the peer comes from the AA05/RCS pairing that carries this channel.
 *
 * The instance can be closed and opened again, but no state survives that boundary. Callers should
 * normally create a fresh instance per accepted RCS connection through [CarPlayUiSyncEndpoint].
 */
class CarPlayUiSyncChannel internal constructor(
    private val descriptor: CarPlayUiSyncChannelDescriptor,
    @Suppress("unused") private val mode: CarPlayUiSyncMode,
    private val eventSink: CarPlayUiSyncPresentationEventSink,
    private val maximumVersion: CarPlayUiSyncProtocolVersion,
    private val limits: CarPlayUiSyncLimits,
    private val outboundResetConfig: Any?,
    private val acceptedCommandClockNanos: () -> Long,
) : AutoCloseable {
    private val lock = Any()
    private val pendingAcknowledgements = TreeSet<Long>()
    private val receivedFrames = LinkedHashMap<ReceivedFrameKey, ReceivedFrame>()
    private var rememberedFrameBytes = 0L

    private var open = false
    private var state = CarPlayUiSyncSessionState.INITIALIZED
    private var sessionSequenceNumber = ACCESSORY_SESSION_SEQUENCE
    private var nextTransmitPacketSequenceNumber = INITIAL_PACKET_SEQUENCE
    private var lastReceivedPacketSequenceNumber = 0L
    private var lastAcknowledgedPacketSequenceNumber = 0L
    private var negotiatedVersion: CarPlayUiSyncProtocolVersion? = null
    private var lastAcceptedCommandMonotonicNanos: Long? = null
    private var accessoryResetCount = 0L

    /**
     * ACK-requesting RequestLayouts of the current sequence space still awaiting their echo,
     * oldest first and bounded. A type 6 answers the oldest entry with exactly its data; that
     * entry is spent whether or not it was already acknowledged. Either Reset and close clear
     * the queue, so an echo can only ever answer one request of this epoch, once.
     */
    private val outstandingRequestLayouts = java.util.ArrayDeque<OutstandingRequestLayout>()

    /** Phone-generated identifier learned from the first phone Reset of this channel lifetime. */
    private var boundPhoneVehicleId: String? = null

    fun open(): CarPlayUiSyncActions = synchronized(lock) {
        if (open) throw CarPlayUiSyncProtocolException("UI Sync channel is already open")
        resetClosedState()
        open = true
        // Never speak first: the phone ignores every message in state 0 and never retries.
        CarPlayUiSyncActions()
    }

    fun receive(carrierBytes: ByteArray): CarPlayUiSyncActions =
        receiveWithoutPresentationDelivery(carrierBytes).also(::deliverPresentationEvents)

    /** Advances protocol state without invoking arbitrary presentation code. */
    internal fun receiveWithoutPresentationDelivery(carrierBytes: ByteArray): CarPlayUiSyncActions =
        synchronized(lock) {
            requireOpen()
            val carrier = decodeCarrier(carrierBytes)
            if (carrier.displayId != descriptor.displayId) {
                throw CarPlayUiSyncProtocolException(
                    "UI Sync carrier display ID does not match the channel display ID",
                )
            }
            requireInboundRepresentation(carrier)
            if (carrier.message.type == CarPlayUiSyncMessageType.RESET) {
                // A Reset always starts a new exchange (the phone sends one only on channel open);
                // it is never served from, or stored in, the duplicate cache.
                return@synchronized receiveReset(carrier.message)
            }
            val bound = boundPhoneVehicleId
            if (carrier.message.vehicleId != null && carrier.message.vehicleId != bound) {
                throw CarPlayUiSyncProtocolException(
                    "UI Sync message vehicle ID does not match the identifier bound by the " +
                        "phone reset",
                )
            }
            duplicateActions(carrier.message, carrierBytes)
                ?: discardStaleDuplicate(carrier)
                ?: resynchronizeAfterSkippedPhonePacket(carrier)
                ?: when (carrier.message.type) {
                    CarPlayUiSyncMessageType.RESET -> error("unreachable")
                    CarPlayUiSyncMessageType.RESET_COMPLETE -> {
                        requireNegotiatedCarrier(carrier)
                        receiveResetComplete(carrier.message)
                    }
                    CarPlayUiSyncMessageType.COMMAND -> {
                        requireNegotiatedCarrier(carrier)
                        receiveCommand(carrier.message)
                    }
                    CarPlayUiSyncMessageType.COMMAND_ACK -> {
                        requireNegotiatedCarrier(carrier)
                        receiveCommandAck(carrier.message)
                    }
                }.also { processed ->
                    rememberReceived(carrier.message, carrierBytes, processed)
                }
        }

    /** Delivers already-validated events after transport-critical locks and writes are complete. */
    internal fun deliverPresentationEvents(actions: CarPlayUiSyncActions) {
        // A custom presentation consumer must not suppress a protocol ACK or corrupt sequencing.
        // The validated event is also returned in [actions] so an integration can diagnose or
        // replay a consumer-side failure without asking the peer to retransmit it.
        actions.presentationEvents.forEach { event ->
            try {
                eventSink.onPresentationEvent(event)
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                // Isolate presentation code from the per-channel protocol state machine.
            }
        }
    }

    fun sendCommand(
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): CarPlayUiSyncActions = sendCommand(
        CarPlayUiSyncCommandEnvelope(command.wireValue, data),
        noAck,
    )

    fun sendCommand(
        command: CarPlayUiSyncAccessoryCommand,
        noAck: Boolean = false,
    ): CarPlayUiSyncActions = sendCommand(command.toEnvelope(), noAck)

    fun sendCommand(
        command: CarPlayUiSyncCommandEnvelope,
        noAck: Boolean = false,
    ): CarPlayUiSyncActions = synchronized(lock) {
        requireReady()
        if (!noAck && pendingAcknowledgements.size >= limits.maximumPendingAcknowledgements) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync has ${pendingAcknowledgements.size} unacknowledged commands",
            )
        }
        val packetSequenceNumber = nextTransmitPacketSequenceNumber
        // Same shape as the phone's own commands (sendCommand passes id=nil, version=nil): the
        // phone parser defaults a missing version to its negotiated version.
        val message = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.COMMAND,
            sessionSequenceNumber = sessionSequenceNumber,
            packetSequenceNumber = packetSequenceNumber,
            acknowledgementSequenceNumber = lastReceivedPacketSequenceNumber,
            payload = command,
            noAck = noAck,
        )
        val encoded = encodeNegotiated(message)
        val requestLayout = command.type == CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue
        // Copied only after encoding accepted the value, so the snapshot is within limits and
        // cannot be changed by a caller that keeps a reference to a mutable map.
        val requestData = if (requestLayout && !noAck) {
            command.data?.let { copyCarPlayUiSyncBoundedValue(it, limits) }
        } else null
        nextTransmitPacketSequenceNumber = checkedIncrement(
            packetSequenceNumber,
            "transmit packet sequence",
        )
        if (!noAck) pendingAcknowledgements += packetSequenceNumber
        if (requestLayout && !noAck) {
            if (outstandingRequestLayouts.size >= MAXIMUM_OUTSTANDING_REQUEST_LAYOUTS) {
                outstandingRequestLayouts.removeFirst()
            }
            outstandingRequestLayouts.addLast(OutstandingRequestLayout(
                sessionSequenceNumber, packetSequenceNumber, requestData,
            ))
        }
        CarPlayUiSyncActions(outboundCarriers = listOf(encoded))
    }

    fun snapshot(): CarPlayUiSyncSessionSnapshot = synchronized(lock) {
        CarPlayUiSyncSessionSnapshot(
            open = open,
            state = state,
            sessionSequenceNumber = sessionSequenceNumber,
            nextTransmitPacketSequenceNumber = nextTransmitPacketSequenceNumber,
            lastReceivedPacketSequenceNumber = lastReceivedPacketSequenceNumber,
            lastAcknowledgedPacketSequenceNumber = lastAcknowledgedPacketSequenceNumber,
            pendingAcknowledgements = pendingAcknowledgements.toSet(),
            negotiatedVersion = negotiatedVersion,
            rememberedFrameCount = receivedFrames.size,
            rememberedFrameBytes = rememberedFrameBytes,
            accessoryResetCount = accessoryResetCount,
        )
    }

    override fun close() {
        synchronized(lock) {
            resetClosedState()
        }
    }

    /**
     * Phone Reset (`sub_25c7bb254` on the phone). Valid in every open state: a second Reset means
     * the phone reopened its session and the exchange restarts. Nothing is mutated on rejection.
     */
    private fun receiveReset(message: CarPlayUiSyncMessage): CarPlayUiSyncActions {
        if (message.sessionSequenceNumber != PHONE_RESET_SESSION_SEQUENCE ||
            message.packetSequenceNumber != INITIAL_PACKET_SEQUENCE ||
            message.acknowledgementSequenceNumber != 0L
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync phone reset requires ssn=1, psn=1, and asn=0",
            )
        }
        if (message.noAck) {
            throw CarPlayUiSyncProtocolException("UI Sync reset must not set noAck")
        }
        val phoneVersion = message.version
            ?: throw CarPlayUiSyncProtocolException("UI Sync phone reset requires a version")
        val phoneVehicleId = message.vehicleId
            ?: throw CarPlayUiSyncProtocolException("UI Sync phone reset requires an id")
        val bound = boundPhoneVehicleId
        if (bound != null && bound != phoneVehicleId) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync reset vehicle ID does not match the identifier bound by the first " +
                    "phone reset",
            )
        }
        val version = CarPlayUiSyncProtocolVersion.negotiate(maximumVersion, phoneVersion)
        // Our Reset: the phone (state 1, negotiated nil) only accepts plist Data here. Including
        // the id skips the phone's DisplayAssetConfig payload (`0x25c7bbb08`) and is checked
        // against its session id (`0x25c7bb7c0`). asn acknowledges the phone Reset; the phone
        // ignores asn on a Reset.
        val ourReset = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.RESET,
            sessionSequenceNumber = ACCESSORY_SESSION_SEQUENCE,
            packetSequenceNumber = ACCESSORY_RESET_PACKET_SEQUENCE,
            acknowledgementSequenceNumber = message.packetSequenceNumber,
            noAck = false,
            vehicleId = phoneVehicleId,
            version = version,
        )
        val encoded = encode(ourReset, CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST)

        boundPhoneVehicleId = phoneVehicleId
        receivedFrames.clear()
        rememberedFrameBytes = 0L
        pendingAcknowledgements.clear()
        outstandingRequestLayouts.clear()
        sessionSequenceNumber = ACCESSORY_SESSION_SEQUENCE
        nextTransmitPacketSequenceNumber = ACCESSORY_RESET_PACKET_SEQUENCE + 1
        lastReceivedPacketSequenceNumber = message.packetSequenceNumber
        lastAcknowledgedPacketSequenceNumber = 0L
        negotiatedVersion = version
        state = CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
        return CarPlayUiSyncActions(outboundCarriers = listOf(encoded))
    }

    /**
     * Phone ResetComplete answering our Reset (phone `sub_25c7bb45c`, send at `0x25c7bbdec`):
     * ssn is ours, psn restarts at 1, asn is our Reset psn, id is present unless v1.
     */
    private fun receiveResetComplete(message: CarPlayUiSyncMessage): CarPlayUiSyncActions {
        if (state != CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync resetComplete is not valid in state ${state.name}",
            )
        }
        val version = requireNotNull(negotiatedVersion)
        if (message.version != version) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync resetComplete version does not match the negotiated reset",
            )
        }
        requireResetCompleteVehicleId(message, version)
        if (message.sessionSequenceNumber != sessionSequenceNumber ||
            message.packetSequenceNumber != INITIAL_PACKET_SEQUENCE ||
            message.acknowledgementSequenceNumber != ACCESSORY_RESET_PACKET_SEQUENCE
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync resetComplete requires current ssn, psn=1, and asn=1",
            )
        }
        if (message.noAck) {
            throw CarPlayUiSyncProtocolException("UI Sync resetComplete must not set noAck")
        }
        // Our ResetComplete. The phone checks ssn, version and (v2/v3) id, then stores psn as its
        // lastRxSn and asn as its lastAckSn (`0x25c7be42c`/`0x25c7be454`); asn must stay below
        // its nextTxSn=2 for its first command's asn window to be satisfiable.
        val packetSequenceNumber = nextTransmitPacketSequenceNumber
        val ourResetComplete = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.RESET_COMPLETE,
            sessionSequenceNumber = sessionSequenceNumber,
            packetSequenceNumber = packetSequenceNumber,
            acknowledgementSequenceNumber = message.packetSequenceNumber,
            payload = outboundResetConfig?.let(::CarPlayUiSyncResetCompletePayload),
            noAck = false,
            vehicleId = boundPhoneVehicleId.takeIf { version != CarPlayUiSyncProtocolVersion.V1 },
            version = version,
        )
        val encoded = encode(
            ourResetComplete,
            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
        )
        nextTransmitPacketSequenceNumber = checkedIncrement(
            packetSequenceNumber,
            "transmit packet sequence",
        )
        lastReceivedPacketSequenceNumber = message.packetSequenceNumber
        lastAcknowledgedPacketSequenceNumber = message.acknowledgementSequenceNumber
        state = CarPlayUiSyncSessionState.READY
        return CarPlayUiSyncActions(outboundCarriers = listOf(encoded))
    }

    /**
     * Recovery policy step 7: a phone packet whose psn skips ahead proves that the phone consumed a
     * psn we never applied. The phone never retransmits, so the exact `lastRx + 1` check
     * (`0x25c7bca34`) can never pass again; only an accessory Reset restores both directions.
     * Returns null, leaving strict handling to reject the packet, for anything that is not a
     * READY-state command/commandAck in the negotiated carrier and the current ssn.
     */
    private fun resynchronizeAfterSkippedPhonePacket(
        carrier: CarPlayUiSyncCarrier,
    ): CarPlayUiSyncActions? {
        val message = carrier.message
        val version = negotiatedVersion ?: return null
        if (state != CarPlayUiSyncSessionState.READY ||
            (message.type != CarPlayUiSyncMessageType.COMMAND &&
                message.type != CarPlayUiSyncMessageType.COMMAND_ACK) ||
            carrier.serializationVersion != version ||
            carrier.representation !=
            CarPlayUiSyncCarrierRepresentation.forNegotiatedVersion(version) ||
            message.sessionSequenceNumber != sessionSequenceNumber ||
            lastReceivedPacketSequenceNumber == Long.MAX_VALUE ||
            message.packetSequenceNumber <= lastReceivedPacketSequenceNumber + 1
        ) {
            return null
        }
        return sendAccessoryReset(version)
    }

    /**
     * Accessory-initiated Reset from READY (phone `sub_25c7bb45c`: no ssn/psn/asn checks, state 3
     * allowed, id compared when present). It is plist Data because the phone's payload path is
     * never version-gated. A new ssn S+1 (never 0, `0x25c7b96a0`) separates the restarted sequence
     * space from phone packets still in flight under the old one; the phone adopts it and echoes
     * it in its ResetComplete, which [receiveResetComplete] then handles exactly as in the
     * initial exchange (psn 1, asn = our Reset psn).
     */
    private fun sendAccessoryReset(version: CarPlayUiSyncProtocolVersion): CarPlayUiSyncActions {
        val phoneVehicleId = requireNotNull(boundPhoneVehicleId)
        val nextSession = checkedIncrement(sessionSequenceNumber, "session sequence")
        val reset = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.RESET,
            sessionSequenceNumber = nextSession,
            packetSequenceNumber = ACCESSORY_RESET_PACKET_SEQUENCE,
            acknowledgementSequenceNumber = lastReceivedPacketSequenceNumber,
            noAck = false,
            vehicleId = phoneVehicleId,
            version = version,
        )
        val encoded = encode(reset, CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST)

        receivedFrames.clear()
        rememberedFrameBytes = 0L
        pendingAcknowledgements.clear()
        outstandingRequestLayouts.clear()
        sessionSequenceNumber = nextSession
        nextTransmitPacketSequenceNumber = ACCESSORY_RESET_PACKET_SEQUENCE + 1
        lastReceivedPacketSequenceNumber = 0L
        lastAcknowledgedPacketSequenceNumber = 0L
        state = CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED
        accessoryResetCount++
        return CarPlayUiSyncActions(outboundCarriers = listOf(encoded))
    }

    private fun requireResetCompleteVehicleId(
        message: CarPlayUiSyncMessage,
        version: CarPlayUiSyncProtocolVersion,
    ) {
        val bound = boundPhoneVehicleId
        val valid = when {
            message.vehicleId != null -> message.vehicleId == bound
            // The phone omits id only for v1 (`cbz w24` at 0x25c7bbd68).
            else -> version == CarPlayUiSyncProtocolVersion.V1
        }
        if (!valid) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync resetComplete vehicle ID does not match the identifier bound by the " +
                    "phone reset",
            )
        }
    }

    private fun receiveCommand(message: CarPlayUiSyncMessage): CarPlayUiSyncActions {
        validateReadyMessage(message)
        val command = message.payload as CarPlayUiSyncCommandEnvelope
        // Capture while still in the accepted receive path, never when an observer later runs.
        // `nanoTime` has an arbitrary signed origin; only ordering within this channel matters.
        val acceptedAtMonotonicNanos = acceptedCommandClockNanos()
        if (lastAcceptedCommandMonotonicNanos?.let { acceptedAtMonotonicNanos < it } == true) {
            throw CarPlayUiSyncProtocolException("UI Sync accepted-command monotonic clock regressed")
        }
        // Evaluated before this packet's own asn is applied: the phone's commandAck for our
        // RequestLayout must already have been accepted. DashBoard acknowledges the request in
        // its receive path before its assetless delegate echoes the data back as type 6.
        val endLayoutChange = command.type == CarPlayUiSyncCommandType.END_LAYOUT_CHANGE.wireValue
        val echoesAcknowledgedRequestLayout = endLayoutChange &&
            echoesAcknowledgedRequestLayout(command)
        val event = command.toPresentationEvent(
            acceptedAtMonotonicNanos,
            echoesAcknowledgedRequestLayout,
        )
        applyReadyMessage(message)

        val outbound = if (message.noAck) {
            emptyList()
        } else {
            // Phone commandAck shape (`0x25c7bcb84`): {type 3, ssn, psn, asn}; consumes a psn.
            val acknowledgement = CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.COMMAND_ACK,
                sessionSequenceNumber = sessionSequenceNumber,
                packetSequenceNumber = nextTransmitPacketSequenceNumber,
                acknowledgementSequenceNumber = lastReceivedPacketSequenceNumber,
                noAck = false,
            )
            val encoded = encodeNegotiated(acknowledgement)
            nextTransmitPacketSequenceNumber = checkedIncrement(
                nextTransmitPacketSequenceNumber,
                "transmit packet sequence",
            )
            listOf(encoded)
        }
        lastAcceptedCommandMonotonicNanos = acceptedAtMonotonicNanos
        return CarPlayUiSyncActions(
            outboundCarriers = outbound,
            presentationEvents = listOf(event),
        )
    }

    /**
     * Spends the oldest outstanding request whose data the type 6 equals in every key and value
     * ([carPlayUiSyncWireValueEquals]); true only if that request is of this ssn (no Reset
     * since) and its psn was already acknowledged by an earlier phone packet. A timed or
     * otherwise non-matching type 6 spends nothing.
     */
    private fun echoesAcknowledgedRequestLayout(command: CarPlayUiSyncCommandEnvelope): Boolean {
        val iterator = outstandingRequestLayouts.iterator()
        while (iterator.hasNext()) {
            val outstanding = iterator.next()
            if (outstanding.data !is Map<*, *> || !carPlayUiSyncWireValueEquals(
                    command.data, outstanding.data, limits.maximumNestingDepth,
                )
            ) continue
            iterator.remove()
            return outstanding.sessionSequenceNumber == sessionSequenceNumber &&
                outstanding.packetSequenceNumber <= lastAcknowledgedPacketSequenceNumber &&
                outstanding.packetSequenceNumber !in pendingAcknowledgements
        }
        return false
    }

    private fun receiveCommandAck(message: CarPlayUiSyncMessage): CarPlayUiSyncActions {
        validateReadyMessage(message)
        if (message.noAck) {
            throw CarPlayUiSyncProtocolException("UI Sync commandAck must not set noAck")
        }
        if (pendingAcknowledgements.isEmpty() ||
            pendingAcknowledgements.first() > message.acknowledgementSequenceNumber
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync commandAck does not acknowledge a pending command",
            )
        }
        applyReadyMessage(message)
        return CarPlayUiSyncActions()
    }

    /** Same checks as the phone (`0x25c7bc37c`, `0x25c7bca34`, `0x25c7bcab0`/`0x25c7bcae8`). */
    private fun validateReadyMessage(message: CarPlayUiSyncMessage) {
        requireReady()
        if (message.version != null && message.version != negotiatedVersion) {
            throw CarPlayUiSyncProtocolException("UI Sync message version changed after reset")
        }
        if (message.sessionSequenceNumber != sessionSequenceNumber) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync message ssn ${message.sessionSequenceNumber} does not match " +
                    sessionSequenceNumber,
            )
        }
        val expectedPacketSequence = checkedIncrement(
            lastReceivedPacketSequenceNumber,
            "receive packet sequence",
        )
        if (message.packetSequenceNumber != expectedPacketSequence) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync message psn ${message.packetSequenceNumber} expected " +
                    expectedPacketSequence,
            )
        }
        if (message.acknowledgementSequenceNumber < lastAcknowledgedPacketSequenceNumber ||
            message.acknowledgementSequenceNumber >= nextTransmitPacketSequenceNumber
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync message asn ${message.acknowledgementSequenceNumber} is outside " +
                    "$lastAcknowledgedPacketSequenceNumber until " +
                    "${nextTransmitPacketSequenceNumber - 1}",
            )
        }
    }

    private fun decodeCarrier(carrierBytes: ByteArray): CarPlayUiSyncCarrier = try {
        // Dictionary carriers and handshake messages are self-describing. Ordinary v1/v2 messages
        // may omit version, so retry those against the version frozen by the reset exchange.
        CarPlayUiSyncCodec.decode(
            bytes = carrierBytes,
            limits = limits,
        )
    } catch (first: CarPlayUiSyncProtocolException) {
        val version = negotiatedVersion ?: throw first
        CarPlayUiSyncCodec.decode(
            bytes = carrierBytes,
            limits = limits,
            expectedVersion = version,
        )
    }

    /**
     * The phone picks the representation from its own negotiated version (`0x25c7b9a0c`): its
     * Reset is sent while it has none, so it is always plist Data; everything else uses the
     * representation of the version negotiated by our Reset. A dictionary before negotiation is
     * therefore never a phone message.
     */
    private fun requireInboundRepresentation(carrier: CarPlayUiSyncCarrier) {
        val expected = if (carrier.message.type == CarPlayUiSyncMessageType.RESET) {
            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST
        } else {
            val version = negotiatedVersion ?: throw CarPlayUiSyncProtocolException(
                "UI Sync ${carrier.message.type.name} received before a reset negotiated a version",
            )
            CarPlayUiSyncCarrierRepresentation.forNegotiatedVersion(version)
        }
        if (carrier.representation != expected) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync ${carrier.message.type.name} carrier representation " +
                    "${carrier.representation.name} is not valid here",
            )
        }
    }

    private fun requireNegotiatedCarrier(carrier: CarPlayUiSyncCarrier) {
        val version = carrier.serializationVersion
        if (version != negotiatedVersion) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync carrier version ${version.wireValue} changed after reset",
            )
        }
        if (carrier.representation !=
            CarPlayUiSyncCarrierRepresentation.forNegotiatedVersion(version)
        ) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync carrier representation does not match negotiated version " +
                    version.wireValue,
            )
        }
    }

    private fun duplicateActions(
        message: CarPlayUiSyncMessage,
        carrierBytes: ByteArray,
    ): CarPlayUiSyncActions? {
        val key = ReceivedFrameKey(
            message.type,
            message.sessionSequenceNumber,
            message.packetSequenceNumber,
        )
        val received = receivedFrames[key] ?: return null
        if (!received.carrierDigest.contentEquals(sha256(carrierBytes))) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync received conflicting content for duplicate " +
                    "${message.type.name} psn=${message.packetSequenceNumber}",
            )
        }
        return CarPlayUiSyncActions(
            // Replaying the exact cached response is safe: it does not consume another local PSN.
            outboundCarriers = received.outboundCarriers.map(ByteArray::copyOf),
        )
    }

    /**
     * Silently drops an already-applied message whose exact bytes fell out of the cache. Every
     * field that a fresh message would have to satisfy, including its carrier representation and
     * serialization version, must still match; anything else falls through to strict handling.
     */
    private fun discardStaleDuplicate(
        carrier: CarPlayUiSyncCarrier,
    ): CarPlayUiSyncActions? {
        val message = carrier.message
        val version = negotiatedVersion ?: return null
        if (state != CarPlayUiSyncSessionState.READY ||
            message.sessionSequenceNumber != sessionSequenceNumber ||
            carrier.serializationVersion != version ||
            carrier.representation != CarPlayUiSyncCarrierRepresentation.forNegotiatedVersion(version)
        ) {
            return null
        }
        return when (message.type) {
            CarPlayUiSyncMessageType.COMMAND,
            CarPlayUiSyncMessageType.COMMAND_ACK,
            -> if (
                message.packetSequenceNumber <= lastReceivedPacketSequenceNumber &&
                (message.version == null || message.version == version)
            ) {
                CarPlayUiSyncActions()
            } else {
                null
            }

            CarPlayUiSyncMessageType.RESET_COMPLETE -> if (
                message.packetSequenceNumber == INITIAL_PACKET_SEQUENCE &&
                message.acknowledgementSequenceNumber == ACCESSORY_RESET_PACKET_SEQUENCE &&
                !message.noAck &&
                message.version == version &&
                if (message.vehicleId != null) {
                    message.vehicleId == boundPhoneVehicleId
                } else {
                    version == CarPlayUiSyncProtocolVersion.V1
                }
            ) {
                CarPlayUiSyncActions()
            } else {
                null
            }

            CarPlayUiSyncMessageType.RESET -> null
        }
    }

    private fun rememberReceived(
        message: CarPlayUiSyncMessage,
        carrierBytes: ByteArray,
        actions: CarPlayUiSyncActions,
    ) {
        val key = ReceivedFrameKey(
            message.type,
            message.sessionSequenceNumber,
            message.packetSequenceNumber,
        )
        val frame = ReceivedFrame(
            carrierDigest = sha256(carrierBytes),
            outboundCarriers = actions.outboundCarriers.map(ByteArray::copyOf),
        )
        receivedFrames.put(key, frame)?.let { rememberedFrameBytes -= it.retainedBytes }
        rememberedFrameBytes += frame.retainedBytes
        while (receivedFrames.size > limits.maximumRememberedFrames ||
            rememberedFrameBytes > limits.maximumRememberedFrameBytes
        ) {
            val oldest = receivedFrames.entries.iterator()
            val removed = oldest.next().value
            oldest.remove()
            rememberedFrameBytes -= removed.retainedBytes
        }
    }

    private fun applyReadyMessage(message: CarPlayUiSyncMessage) {
        lastReceivedPacketSequenceNumber = message.packetSequenceNumber
        lastAcknowledgedPacketSequenceNumber = message.acknowledgementSequenceNumber
        pendingAcknowledgements.headSet(
            message.acknowledgementSequenceNumber,
            true,
        ).clear()
    }

    /** Commands and ACKs after READY use the negotiated representation, as the phone does. */
    private fun encodeNegotiated(message: CarPlayUiSyncMessage): ByteArray {
        val version = requireNotNull(negotiatedVersion)
        return encode(message, CarPlayUiSyncCarrierRepresentation.forNegotiatedVersion(version))
    }

    private fun encode(
        message: CarPlayUiSyncMessage,
        representation: CarPlayUiSyncCarrierRepresentation,
    ): ByteArray {
        val serializationVersion = message.version ?: negotiatedVersion ?: maximumVersion
        return CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                displayId = descriptor.displayId,
                serializationVersion = serializationVersion,
                message = message,
                representation = representation,
            ),
            limits,
        )
    }

    private fun requireOpen() {
        if (!open) throw CarPlayUiSyncProtocolException("UI Sync channel is closed")
    }

    private fun requireReady() {
        requireOpen()
        if (state != CarPlayUiSyncSessionState.READY) {
            throw CarPlayUiSyncProtocolException(
                "UI Sync channel is not ready (state=${state.name})",
            )
        }
    }

    private fun resetClosedState() {
        open = false
        state = CarPlayUiSyncSessionState.INITIALIZED
        sessionSequenceNumber = ACCESSORY_SESSION_SEQUENCE
        nextTransmitPacketSequenceNumber = INITIAL_PACKET_SEQUENCE
        lastReceivedPacketSequenceNumber = 0
        lastAcknowledgedPacketSequenceNumber = 0
        pendingAcknowledgements.clear()
        outstandingRequestLayouts.clear()
        receivedFrames.clear()
        rememberedFrameBytes = 0L
        negotiatedVersion = null
        lastAcceptedCommandMonotonicNanos = null
        accessoryResetCount = 0L
        boundPhoneVehicleId = null
    }

    private fun checkedIncrement(value: Long, label: String): Long {
        if (value == Long.MAX_VALUE) {
            throw CarPlayUiSyncProtocolException("UI Sync $label exhausted")
        }
        return value + 1
    }

    private companion object {
        /** Our ssn S. The phone adopts it from our Reset; it must be at least 1 (`0x25c7b96a0`). */
        const val ACCESSORY_SESSION_SEQUENCE = 1L
        /** The phone's own Reset always carries ssn 1 (`sub_25c7bb254`). */
        const val PHONE_RESET_SESSION_SEQUENCE = 1L
        const val INITIAL_PACKET_SEQUENCE = 1L
        /** psn P of our Reset; the phone echoes it as the asn of its ResetComplete. */
        const val ACCESSORY_RESET_PACKET_SEQUENCE = 1L
        /** RequestLayouts awaiting their echo; the oldest is dropped (never attested) beyond. */
        const val MAXIMUM_OUTSTANDING_REQUEST_LAYOUTS = 4
    }

    private class OutstandingRequestLayout(
        val sessionSequenceNumber: Long,
        val packetSequenceNumber: Long,
        /** Detached, bounded copy of the sent `data`; never exposed outside this channel. */
        val data: Any?,
    )

    private data class ReceivedFrameKey(
        val type: CarPlayUiSyncMessageType,
        val sessionSequenceNumber: Long,
        val packetSequenceNumber: Long,
    )

    private data class ReceivedFrame(
        val carrierDigest: ByteArray,
        val outboundCarriers: List<ByteArray>,
    ) {
        val retainedBytes: Long = carrierDigest.size.toLong() +
            outboundCarriers.sumOf { it.size.toLong() }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
