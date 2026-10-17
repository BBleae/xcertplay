package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accessory state machine against the iPhone's iOS 27.0.1 DBUISyncSession rules. "Phone" bytes are
 * either hand-built to the exact wire shape or produced by [CarPlayUiSyncPhoneRoleSimulator].
 */
class CarPlayUiSyncSessionTest {
    @Test
    fun stateValuesMatchIos27StateMachine() {
        assertEquals(listOf(0, 1, 2, 3), CarPlayUiSyncSessionState.entries.map { it.wireValue })
        assertEquals(listOf(CarPlayUiSyncMode.PASSIVE), CarPlayUiSyncMode.entries)
    }

    @Test
    fun phoneExactResetYieldsPlistResetThenPlistResetCompleteWithEchoedPhoneId() {
        val phoneId = UUID.randomUUID().toString().uppercase()
        val channel = endpoint(displayId = PHONE_DISPLAY_ID).openChannel()
        assertTrue(channel.open().outboundCarriers.isEmpty())

        // Live phone Reset: OPACK carrier, binary-plist message, 137-byte plist on the device.
        val phoneReset = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to PHONE_DISPLAY_ID,
                "data" to BplistCodec.encode(
                    linkedMapOf(
                        "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0,
                        "id" to phoneId, "version" to 3,
                    ),
                ),
            ),
        )
        val ourReset = channel.receive(phoneReset).outboundCarriers.single()
        assertEquals(
            mapOf(
                "type" to 0L, "ssn" to 1L, "psn" to 1L, "asn" to 1L,
                "id" to phoneId, "version" to 3L,
            ),
            plistInner(ourReset, PHONE_DISPLAY_ID),
        )
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, channel.snapshot().state)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, channel.snapshot().negotiatedVersion)

        // Phone ResetComplete after it negotiated v3: a nested OPACK dictionary, psn restarts at 1.
        val phoneResetComplete = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to PHONE_DISPLAY_ID,
                "data" to linkedMapOf(
                    "type" to 1, "ssn" to 1, "psn" to 1, "asn" to 1,
                    "id" to phoneId, "version" to 3,
                ),
            ),
        )
        val ourResetComplete = channel.receive(phoneResetComplete).outboundCarriers.single()
        assertEquals(
            mapOf(
                "type" to 1L, "ssn" to 1L, "psn" to 2L, "asn" to 1L,
                "id" to phoneId, "version" to 3L,
            ),
            plistInner(ourResetComplete, PHONE_DISPLAY_ID),
        )
        val snapshot = channel.snapshot()
        assertEquals(CarPlayUiSyncSessionState.READY, snapshot.state)
        assertEquals(3L, snapshot.nextTransmitPacketSequenceNumber)
        assertEquals(1L, snapshot.lastReceivedPacketSequenceNumber)
        assertEquals(1L, snapshot.lastAcknowledgedPacketSequenceNumber)

        // The same bytes satisfy the executable phone model, which also reproduces the
        // hand-built ResetComplete exactly.
        val phone = CarPlayUiSyncPhoneRoleSimulator(PHONE_DISPLAY_ID, phoneId)
        assertArrayEquals(phoneReset, phone.open())
        assertArrayEquals(phoneResetComplete, phone.receive(ourReset).single())
        assertTrue(phone.receive(ourResetComplete).isEmpty())
        assertNull(phone.lastDropReason)
        assertEquals(CarPlayUiSyncSessionState.READY, phone.state)
        assertEquals(2L, phone.lastReceivedPacketSequenceNumber)
        assertEquals(1L, phone.lastAcknowledgedPacketSequenceNumber)
        assertEquals(2L, phone.nextTransmitPacketSequenceNumber)
    }

    @Test
    fun phoneModelDropsDictionaryResetAndResetCompleteToItsOwnReset() {
        val phone = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID)
        phone.open()
        val dictionaryReset = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                DISPLAY_ID,
                CarPlayUiSyncProtocolVersion.V3,
                CarPlayUiSyncMessage(
                    type = CarPlayUiSyncMessageType.RESET,
                    sessionSequenceNumber = 1,
                    packetSequenceNumber = 1,
                    acknowledgementSequenceNumber = 1,
                    vehicleId = phone.phoneVehicleId,
                    version = CarPlayUiSyncProtocolVersion.V3,
                ),
                CarPlayUiSyncCarrierRepresentation.DICTIONARY,
            ),
        )
        assertTrue(phone.receive(dictionaryReset).isEmpty())
        assertEquals("Received packet but no version negotiated", phone.lastDropReason)

        val resetCompleteToSelfReset = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                DISPLAY_ID,
                CarPlayUiSyncProtocolVersion.V3,
                CarPlayUiSyncMessage(
                    type = CarPlayUiSyncMessageType.RESET_COMPLETE,
                    sessionSequenceNumber = 1,
                    packetSequenceNumber = 1,
                    acknowledgementSequenceNumber = 1,
                    vehicleId = phone.phoneVehicleId,
                    version = CarPlayUiSyncProtocolVersion.V3,
                ),
                CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
            ),
        )
        assertTrue(phone.receive(resetCompleteToSelfReset).isEmpty())
        assertEquals(
            CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED,
            phone.state,
        )
    }

    @Test
    fun postHandshakeCommandsAndAcksFollowPhoneSequenceRules() {
        val pair = readyPair()

        // Phone giveFocus: psn=nextTxSn=2, asn=lastRxSn=2, no id/version, dictionary.
        val giveFocus = pair.phone.sendCommand(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            mapOf("tokenID" to 7),
        )
        assertEquals(
            setOf("type", "ssn", "psn", "asn", "data"),
            (outer(giveFocus)["data"] as Map<*, *>).keys,
        )
        val received = pair.channel.receive(giveFocus)
        assertEquals(CarPlayUiSyncCommandType.GIVE_FOCUS, pair.events.single().command)
        val ack = received.outboundCarriers.single()
        assertEquals(
            mapOf("type" to 3L, "ssn" to 1L, "psn" to 3L, "asn" to 2L),
            outer(ack)["data"],
        )
        assertTrue(pair.phone.receive(ack).isEmpty())
        assertNull(pair.phone.lastDropReason)
        assertEquals(3L, pair.phone.lastReceivedPacketSequenceNumber)

        // Accessory RequestLayout: psn counts our own ACK, asn is the phone's last psn.
        val requestLayout = pair.channel.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            mapOf("layoutID" to "main"),
        ).outboundCarriers.single()
        val requestInner = outer(requestLayout)["data"] as Map<*, *>
        assertEquals(4L, requestInner["psn"])
        assertEquals(2L, requestInner["asn"])
        assertFalse(requestInner.containsKey("id"))
        assertEquals(setOf(4L), pair.channel.snapshot().pendingAcknowledgements)
        val phoneAck = pair.phone.receive(requestLayout).single()
        assertNull(pair.phone.lastDropReason)
        assertEquals(
            mapOf("type" to 3L, "ssn" to 1L, "psn" to 3L, "asn" to 4L),
            outer(phoneAck)["data"],
        )
        pair.channel.receive(phoneAck)
        assertTrue(pair.channel.snapshot().pendingAcknowledgements.isEmpty())
        assertEquals(4L, pair.channel.snapshot().lastAcknowledgedPacketSequenceNumber)

        // TransitionData is noAck=true on the phone: no ACK, no psn consumed by us.
        val transition = pair.phone.sendCommand(
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            mapOf("regions" to emptyList<Any>()),
            noAck = true,
        )
        assertTrue(pair.channel.receive(transition).outboundCarriers.isEmpty())
        assertEquals(5L, pair.channel.snapshot().nextTransmitPacketSequenceNumber)

        // Our next command still satisfies the phone's psn/asn window.
        val next = pair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_FOCUS)
            .outboundCarriers.single()
        assertEquals(1, pair.phone.receive(next).size)
        assertNull(pair.phone.lastDropReason)
        assertEquals(2, pair.phone.receivedCommands.size)
    }

    @Test
    fun boundPhoneIdIsRequiredForTheRestOfTheChannelLifetime() {
        val pair = readyPair()
        val before = pair.channel.snapshot()

        val otherPhoneReset = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID).open()
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(otherPhoneReset)
        }
        assertEquals(before, pair.channel.snapshot())

        val foreignIdCommand = encode(
            phoneCommand(noAck = true).message.copy(vehicleId = "ANOTHER-PHONE"),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(foreignIdCommand)
        }
        assertEquals(before, pair.channel.snapshot())

        val boundIdCommand = encode(
            phoneCommand(noAck = true).message.copy(vehicleId = pair.phone.phoneVehicleId),
        )
        pair.channel.receive(boundIdCommand)
        assertEquals(2L, pair.channel.snapshot().lastReceivedPacketSequenceNumber)

        // A new channel lifetime may learn a different phone id.
        pair.channel.close()
        pair.channel.open()
        assertEquals(1, pair.channel.receive(otherPhoneReset).outboundCarriers.size)
    }

    @Test
    fun resetCompleteWithMissingOrWrongIdIsRejectedWithoutMutation() {
        listOf<String?>(null, "ANOTHER-PHONE").forEach { id ->
            val channel = endpoint().openChannel()
            channel.open()
            val phone = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID)
            channel.receive(phone.open())
            val before = channel.snapshot()
            val resetComplete = CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.RESET_COMPLETE,
                sessionSequenceNumber = 1,
                packetSequenceNumber = 1,
                acknowledgementSequenceNumber = 1,
                vehicleId = id,
                version = CarPlayUiSyncProtocolVersion.V3,
            )
            assertThrows(CarPlayUiSyncProtocolException::class.java) {
                channel.receive(encode(resetComplete))
            }
            assertEquals(before, channel.snapshot())
        }
    }

    @Test
    fun dictionaryBeforeNegotiationAndDictionaryResetAreRejected() {
        val channel = endpoint().openChannel()
        channel.open()
        val initial = channel.snapshot()
        val phoneId = UUID.randomUUID().toString()

        val dictionaryReset = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                DISPLAY_ID,
                CarPlayUiSyncProtocolVersion.V3,
                resetMessage(phoneId),
                CarPlayUiSyncCarrierRepresentation.DICTIONARY,
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            channel.receive(dictionaryReset)
        }
        assertEquals(initial, channel.snapshot())

        val dictionaryResetComplete = encode(
            CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.RESET_COMPLETE,
                sessionSequenceNumber = 1,
                packetSequenceNumber = 1,
                acknowledgementSequenceNumber = 1,
                vehicleId = phoneId,
                version = CarPlayUiSyncProtocolVersion.V3,
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            channel.receive(dictionaryResetComplete)
        }
        assertEquals(initial, channel.snapshot())

        // After v3 negotiation the phone's ResetComplete is a dictionary; plist Data is not.
        channel.receive(
            CarPlayUiSyncCodec.encode(
                CarPlayUiSyncCarrier(
                    DISPLAY_ID,
                    CarPlayUiSyncProtocolVersion.V3,
                    resetMessage(phoneId),
                    CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
                ),
            ),
        )
        val awaiting = channel.snapshot()
        val plistResetComplete = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                DISPLAY_ID,
                CarPlayUiSyncProtocolVersion.V3,
                CarPlayUiSyncCodec.decode(dictionaryResetComplete).message,
                CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            channel.receive(plistResetComplete)
        }
        assertEquals(awaiting, channel.snapshot())
        channel.receive(dictionaryResetComplete)
        assertEquals(CarPlayUiSyncSessionState.READY, channel.snapshot().state)
    }

    @Test
    fun phoneReopenRestartsTheExchangeFromReady() {
        val pair = readyPair()
        pair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_LAYOUT)
        assertFalse(pair.channel.snapshot().pendingAcknowledgements.isEmpty())

        val ourReset = pair.channel.receive(pair.phone.open()).outboundCarriers.single()
        val restarted = pair.channel.snapshot()
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, restarted.state)
        assertTrue(restarted.pendingAcknowledgements.isEmpty())
        assertEquals(0, restarted.rememberedFrameCount)

        val ourResetComplete = pair.channel.receive(pair.phone.receive(ourReset).single())
            .outboundCarriers.single()
        assertTrue(pair.phone.receive(ourResetComplete).isEmpty())
        assertNull(pair.phone.lastDropReason)
        assertEquals(CarPlayUiSyncSessionState.READY, pair.phone.state)
        assertEquals(CarPlayUiSyncSessionState.READY, pair.channel.snapshot().state)
    }

    @Test
    fun negotiatesLowerPhoneVersionAndUsesPlistForEveryMessage() {
        val pair = readyPair(phoneMaximumVersion = CarPlayUiSyncProtocolVersion.V2)
        assertReady(pair.channel, CarPlayUiSyncProtocolVersion.V2)
        assertEquals(CarPlayUiSyncProtocolVersion.V2, pair.phone.negotiatedVersion)

        val command = pair.channel.sendCommand(
            CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE,
            mapOf("driveMode" to "sport"),
            noAck = true,
        ).outboundCarriers.single()
        assertTrue(outer(command)["data"] is ByteArray)
        assertTrue(pair.phone.receive(command).isEmpty())
        assertNull(pair.phone.lastDropReason)

        val phoneCommand = pair.phone.sendCommand(CarPlayUiSyncCommandType.METADATA_TRANSFER)
        assertTrue(outer(phoneCommand)["data"] is ByteArray)
        val ack = pair.channel.receive(phoneCommand).outboundCarriers.single()
        assertTrue(outer(ack)["data"] is ByteArray)
        assertTrue(pair.phone.receive(ack).isEmpty())
        assertNull(pair.phone.lastDropReason)
    }

    @Test
    fun acceptedCommandTimestampIsSampledBeforeDeferredDeliveryAndRawTypeTwoTimesRemainBits() {
        var nowNanos = 41L
        var clockSamples = 0
        val hub = UltraPresentationHub(Executor { it.run() })
        val pair = readyPair(
            eventSink = hub.sink(DISPLAY_ID),
            acceptedCommandClockNanos = {
                clockSamples++
                nowNanos
            },
        )
        val command = pair.phone.sendCommand(
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            mapOf(
                "regions" to emptyList<Any>(),
                "presentation_host_time" to Long.MIN_VALUE,
                "presentation_ntp_time" to -1L,
                "presentation_ntp_time_original" to 9L,
                "current_ntp_time" to 10L,
            ),
            noAck = true,
        )

        val accepted = pair.channel.receiveWithoutPresentationDelivery(command)
        assertEquals(1, clockSamples)
        assertEquals(41L, accepted.presentationEvents.single().acceptedAtMonotonicNanos)
        assertEquals(0L, hub.snapshot(DISPLAY_ID).revision)
        nowNanos = 900L
        pair.channel.deliverPresentationEvents(accepted)

        val retained = hub.snapshot(DISPLAY_ID).events.single()
        assertEquals(41L, retained.acceptedAtMonotonicNanos)
        assertEquals(41L, retained.toPresentationEvent().acceptedAtMonotonicNanos)
        val timing = (CarPlayUiSyncSemanticDecoder().decode(retained) as
            CarPlayUiSyncSemanticDecodeResult.Decoded).command as
            CarPlayUiSyncSemanticCommand.TransitionData
        assertEquals(Long.MIN_VALUE, timing.presentationHostTimeBits)
        assertEquals(-1L, timing.presentationNtpTimeBits)
        assertEquals(9L, timing.presentationNtpTimeOriginalBits)
        assertEquals(10L, timing.currentNtpTimeBits)
        assertEquals(1, clockSamples)
    }

    @Test
    fun rejectedAndDuplicatePacketsDoNotPublishOrSampleAcceptedCommandTime() {
        var nowNanos = 10L
        var clockSamples = 0
        val events = mutableListOf<CarPlayUiSyncPresentationEvent>()
        val pair = readyPair(
            eventSink = CarPlayUiSyncPresentationEventSink(events::add),
            acceptedCommandClockNanos = {
                clockSamples++
                nowNanos
            },
        )
        val first = pair.phone.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            noAck = true,
        )
        val wrongDisplay = CarPlayUiSyncCodec.decode(first).copy(displayId = "wrong-display")
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(CarPlayUiSyncCodec.encode(wrongDisplay))
        }
        assertEquals(0, clockSamples)
        assertTrue(events.isEmpty())

        pair.channel.receive(first)
        assertEquals(10L, events.single().acceptedAtMonotonicNanos)
        assertEquals(1, clockSamples)
        pair.channel.receive(first)
        assertEquals(1, clockSamples)
        assertEquals(1, events.size)

        val second = pair.phone.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            noAck = true,
        )
        val acceptedSnapshot = pair.channel.snapshot()
        nowNanos = 9L
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(second)
        }
        assertEquals(acceptedSnapshot, pair.channel.snapshot())
        assertEquals(1, events.size)
        assertEquals(2, clockSamples)

        nowNanos = 11L
        pair.channel.receive(second)
        assertEquals(listOf(10L, 11L), events.map { it.acceptedAtMonotonicNanos })
    }

    @Test
    fun commandAckRejectsLegacyNoAckTrueWithoutMutatingState() {
        val pair = readyPair()
        val command = pair.channel.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
        ).outboundCarriers.single()
        val validAckBytes = pair.phone.receive(command).single()
        val invalidAck = CarPlayUiSyncCodec.decode(validAckBytes).message.copy(noAck = true)
        val before = pair.channel.snapshot()

        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(encode(invalidAck))
        }
        assertEquals(before, pair.channel.snapshot())

        pair.channel.receive(validAckBytes)
        assertTrue(pair.channel.snapshot().pendingAcknowledgements.isEmpty())
    }

    @Test
    fun outboundResetConfigIsDefensivelySnapshottedAndNestedCorrectly() {
        val nested = linkedMapOf<String, Any>("name" to "original")
        val config = linkedMapOf<String, Any>("theme" to nested)
        val channel = endpoint(outboundResetConfig = config).openChannel()
        channel.open()
        nested["name"] = "mutated"
        config["late"] = true

        val phone = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID)
        val ourReset = channel.receive(phone.open()).outboundCarriers.single()
        val resetComplete = channel.receive(phone.receive(ourReset).single())
            .outboundCarriers.single()
        val payload = CarPlayUiSyncCodec.decode(resetComplete).message.payload as
            CarPlayUiSyncResetCompletePayload
        assertEquals(mapOf("theme" to mapOf("name" to "original")), payload.config)
        // The phone ignores a ResetComplete payload.
        assertTrue(phone.receive(resetComplete).isEmpty())
        assertEquals(CarPlayUiSyncSessionState.READY, phone.state)
    }

    @Test
    fun unknownCommandIsBoundedAndDeliveredAsUnknownEvent() {
        val events = mutableListOf<CarPlayUiSyncPresentationEvent>()
        val pair = readyPair(eventSink = CarPlayUiSyncPresentationEventSink(events::add))
        val command = pair.phone.sendCommand(
            CarPlayUiSyncCommandEnvelope(2048, byteArrayOf(4, 5, 6)),
            noAck = true,
        )

        pair.channel.receive(command)
        val unknown = events.single() as CarPlayUiSyncPresentationEvent.UnknownCommand
        assertEquals(2048, unknown.type)
        assertTrue((unknown.data as ByteArray).contentEquals(byteArrayOf(4, 5, 6)))
    }

    @Test
    fun checkedPresentationConsumerFailureDoesNotSuppressProtocolAck() {
        val pair = readyPair(
            eventSink = CarPlayUiSyncPresentationEventSink {
                throw IOException("custom UI failed")
            },
        )
        val command = pair.phone.sendCommand(CarPlayUiSyncCommandType.GIVE_FOCUS)

        val received = pair.channel.receive(command)
        assertEquals(1, received.presentationEvents.size)
        assertEquals(1, received.outboundCarriers.size)
        assertTrue(pair.phone.receive(received.outboundCarriers.single()).isEmpty())
        assertNull(pair.phone.lastDropReason)
    }

    @Test
    fun rejectsWrongSessionAndInvalidAcknowledgementWithoutMutation() {
        listOf<(CarPlayUiSyncMessage) -> CarPlayUiSyncMessage>(
            { it.copy(sessionSequenceNumber = 2) },
            // A skipped psn under the wrong ssn is not proof of a lost phone packet.
            { it.copy(sessionSequenceNumber = 2, packetSequenceNumber = 3) },
            { it.copy(acknowledgementSequenceNumber = 0) },
            { it.copy(acknowledgementSequenceNumber = 3) },
        ).forEach { mutation ->
            val pair = readyPair()
            val before = pair.channel.snapshot()
            val malformed = encode(mutation(phoneCommand(noAck = true).message))

            assertThrows(CarPlayUiSyncProtocolException::class.java) {
                pair.channel.receive(malformed)
            }
            assertEquals(before, pair.channel.snapshot())
            assertTrue(pair.events.isEmpty())
        }
    }

    /**
     * Every phone input that the bounded codec cannot represent, after the phone already counted
     * its psn: the next phone packet skips a psn, and we recover with our own Reset instead of
     * rejecting every later phone packet ("psn 3 expected 2") until the phone reopens its channel.
     */
    @Test
    fun skippedPhonePsnAfterUnrepresentablePacketRecoversWithAccessoryReset() {
        val tightLimits = CarPlayUiSyncLimits(
            maximumCarrierBytes = 64 * 1024,
            maximumInnerMessageBytes = 32 * 1024,
            maximumValueBytes = 1024,
            maximumStringChars = 256,
        )
        val cases = listOf<Pair<String, (CarPlayUiSyncPhoneRoleSimulator) -> ByteArray>>(
            "OPACK UUID" to { phone -> phoneCommandWithPatchedValue(phone, 16, 0x05) },
            "OPACK date" to { phone -> phoneCommandWithPatchedValue(phone, 8, 0x06) },
            "OPACK null" to { phone ->
                // `true` (0x01) patched to null (0x04) under the command data.
                val bytes = phone.sendCommand(
                    CarPlayUiSyncCommandType.GIVE_FOCUS,
                    mapOf("zzzz" to true),
                )
                val key = byteArrayOf(0x44) + "zzzz".toByteArray(Charsets.US_ASCII)
                val index = indexOf(bytes, key + byteArrayOf(0x01)) + key.size
                bytes.copyOf().also { it[index] = 0x04 }
            },
            "value over limit" to { phone ->
                phone.sendCommand(
                    CarPlayUiSyncCommandType.METADATA_TRANSFER,
                    mapOf("blob" to ByteArray(2048)),
                )
            },
            "more entries than the limit" to { phone ->
                phone.sendCommand(
                    CarPlayUiSyncCommandType.METADATA_TRANSFER,
                    mapOf("items" to List(1100) { it }),
                )
            },
        )
        cases.forEach { (name, unrepresentable) ->
            // The phone encodes with generous limits; this accessory decodes with tight ones.
            val channel = endpoint(limits = tightLimits).openChannel()
            channel.open()
            val phone = CarPlayUiSyncPhoneRoleSimulator(
                displayId = DISPLAY_ID,
                limits = CarPlayUiSyncLimits(maximumCollectionEntries = 4096),
            )
            phone.handshake { channel.receive(it).outboundCarriers }

            // Our command is pending; the phone's ACK for it will be in the lost sequence space.
            val ours = channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_LAYOUT)
                .outboundCarriers.single()
            assertEquals(1, phone.receive(ours).size)
            assertEquals(1, channel.snapshot().pendingAcknowledgements.size)

            assertThrows(name, CarPlayUiSyncProtocolException::class.java) {
                channel.receive(unrepresentable(phone))
            }
            assertEquals(name, 1L, channel.snapshot().lastReceivedPacketSequenceNumber)

            // The phone's next valid command skips a psn: no event, no ACK, our own Reset.
            val next = phone.sendCommand(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 1))
            val recovery = channel.receive(next)
            assertTrue(name, recovery.presentationEvents.isEmpty())
            val reset = recovery.outboundCarriers.single()
            assertEquals(
                name,
                mapOf(
                    "type" to 0L, "ssn" to 2L, "psn" to 1L, "asn" to 1L,
                    "id" to phone.phoneVehicleId, "version" to 3L,
                ),
                plistInner(reset, DISPLAY_ID),
            )
            val resetting = channel.snapshot()
            assertEquals(name, CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, resetting.state)
            assertEquals(name, 1L, resetting.accessoryResetCount)
            assertTrue(name, resetting.pendingAcknowledgements.isEmpty())
            assertEquals(name, 2L, resetting.sessionSequenceNumber)

            // Phone side (`sub_25c7bb45c` from READY): didReset, then a ResetComplete under ssn 2.
            val phoneResetComplete = phone.receive(reset).single()
            assertNull(name, phone.lastDropReason)
            assertEquals(name, 1, phone.resetsFromReady)
            assertEquals(name, 2L, phone.sessionSequenceNumber)
            val ourResetComplete = channel.receive(phoneResetComplete).outboundCarriers.single()
            assertTrue(name, phone.receive(ourResetComplete).isEmpty())
            assertNull(name, phone.lastDropReason)
            assertEquals(name, CarPlayUiSyncSessionState.READY, phone.state)
            assertEquals(name, CarPlayUiSyncSessionState.READY, channel.snapshot().state)

            // Both directions work again.
            val command = phone.sendCommand(CarPlayUiSyncCommandType.GIVE_FOCUS, mapOf("tokenID" to 2))
            val received = channel.receive(command)
            assertEquals(name, 1, received.presentationEvents.size)
            assertTrue(name, phone.receive(received.outboundCarriers.single()).isEmpty())
            assertNull(name, phone.lastDropReason)
            val request = channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_FOCUS)
                .outboundCarriers.single()
            channel.receive(phone.receive(request).single())
            assertNull(name, phone.lastDropReason)
            assertTrue(name, channel.snapshot().pendingAcknowledgements.isEmpty())
            assertEquals(name, 1L, channel.snapshot().accessoryResetCount)
        }
    }

    @Test
    fun lostPhoneAckIsRecoveredInsteadOfFillingThePendingWindow() {
        val pair = readyPair(limits = CarPlayUiSyncLimits(maximumPendingAcknowledgements = 2))
        val first = pair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_LAYOUT)
            .outboundCarriers.single()
        pair.phone.receive(first).single() // ACK never applied by us.
        val second = pair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_FOCUS)
            .outboundCarriers.single()
        val secondAck = pair.phone.receive(second).single()
        assertNull(pair.phone.lastDropReason)

        val reset = pair.channel.receive(secondAck).outboundCarriers.single()
        assertEquals(CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED, pair.channel.snapshot().state)
        assertTrue(pair.channel.snapshot().pendingAcknowledgements.isEmpty())
        val ourResetComplete = pair.channel.receive(pair.phone.receive(reset).single())
            .outboundCarriers.single()
        assertTrue(pair.phone.receive(ourResetComplete).isEmpty())
        repeat(3) {
            val command = pair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_FOCUS)
                .outboundCarriers.single()
            pair.channel.receive(pair.phone.receive(command).single())
            assertNull(pair.phone.lastDropReason)
        }
        assertTrue(pair.channel.snapshot().pendingAcknowledgements.isEmpty())
    }

    @Test
    fun phoneResetWithNewerUnknownVersionStillGetsOurReset() {
        listOf(
            CarPlayUiSyncProtocolVersion.V3 to 3L,
            CarPlayUiSyncProtocolVersion.V2 to 2L,
        ).forEach { (localMaximum, expectedWire) ->
            val channel = CarPlayUiSyncEndpoint(
                descriptor = CarPlayUiSyncChannelDescriptor(DISPLAY_ID, SIGNED_PROFILE_VEHICLE_ID),
                mode = CarPlayUiSyncMode.PASSIVE,
                presentationEventSink = CarPlayUiSyncPresentationEventSink { },
                maximumVersion = localMaximum,
            ).openChannel()
            channel.open()
            val phoneId = UUID.randomUUID().toString().uppercase()
            val ourReset = channel.receive(phonePlistReset(phoneId, version = 4))
                .outboundCarriers.single()
            assertEquals(expectedWire, plistInner(ourReset, DISPLAY_ID)["version"])
            assertEquals(localMaximum, channel.snapshot().negotiatedVersion)
        }

        // Zero is malformed, not newer; and only a Reset declares a maximum.
        val channel = endpoint().openChannel()
        channel.open()
        val before = channel.snapshot()
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            channel.receive(phonePlistReset("PHONE", version = 0))
        }
        assertEquals(before, channel.snapshot())
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(
                OpackCodec.encode(
                    linkedMapOf(
                        "displayUUID" to DISPLAY_ID,
                        "data" to BplistCodec.encode(
                            linkedMapOf(
                                "type" to 1, "ssn" to 1, "psn" to 1, "asn" to 1, "version" to 4,
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun duplicateCommandIsIdempotentAndResendsCachedAckWithoutAdvancingPsn() {
        val pair = readyPair()
        val command = pair.phone.sendCommand(
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            mapOf("tokenID" to 1),
        )
        val first = pair.channel.receive(command)
        val afterFirst = pair.channel.snapshot()

        val duplicate = pair.channel.receive(command)

        assertEquals(afterFirst, pair.channel.snapshot())
        assertEquals(1, pair.events.size)
        assertTrue(duplicate.presentationEvents.isEmpty())
        assertArrayEquals(first.outboundCarriers.single(), duplicate.outboundCarriers.single())

        val conflicting = phoneCommand(noAck = false).message.copy(
            payload = CarPlayUiSyncCommandEnvelope(6),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(encode(conflicting))
        }
        assertEquals(afterFirst, pair.channel.snapshot())
        assertEquals(1, pair.events.size)
    }

    @Test
    fun duplicateNoAckCommandIsSilentlyIgnored() {
        val pair = readyPair()
        val command = encode(phoneCommand(noAck = true).message)
        pair.channel.receive(command)
        val afterFirst = pair.channel.snapshot()

        val duplicate = pair.channel.receive(command)

        assertEquals(afterFirst, pair.channel.snapshot())
        assertTrue(duplicate.outboundCarriers.isEmpty())
        assertTrue(duplicate.presentationEvents.isEmpty())
        assertEquals(1, pair.events.size)
    }

    @Test
    fun duplicateCommandAckIsSilentlyIgnored() {
        val pair = readyPair()
        val command = pair.channel.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
        ).outboundCarriers.single()
        val acknowledgement = pair.phone.receive(command).single()
        pair.channel.receive(acknowledgement)
        val afterFirst = pair.channel.snapshot()

        val duplicate = pair.channel.receive(acknowledgement)

        assertEquals(afterFirst, pair.channel.snapshot())
        assertTrue(duplicate.outboundCarriers.isEmpty())
        assertTrue(duplicate.presentationEvents.isEmpty())
    }

    @Test
    fun staleDuplicatePathStillChecksCarrierRepresentation() {
        val limits = CarPlayUiSyncLimits(maximumRememberedFrames = 1)
        val pair = readyPair(limits = limits)
        val noAck = phoneCommand(noAck = true).message
        pair.channel.receive(encode(noAck))
        // Evict the exact-bytes cache entry so only the stale-duplicate path can match.
        pair.channel.receive(
            encode(noAck.copy(packetSequenceNumber = 3, payload = CarPlayUiSyncCommandEnvelope(9))),
        )
        val before = protocolState(pair.channel.snapshot())
        assertEquals(1, pair.channel.snapshot().rememberedFrameCount)

        // Same already-applied command as dictionary: silently discarded.
        val staleDictionary = encode(noAck.copy(payload = CarPlayUiSyncCommandEnvelope(8)))
        assertTrue(pair.channel.receive(staleDictionary).outboundCarriers.isEmpty())
        assertEquals(before, protocolState(pair.channel.snapshot()))

        // Same command as plist Data after v3: a representation error, not a silent discard.
        val plistStale = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                DISPLAY_ID,
                CarPlayUiSyncProtocolVersion.V3,
                noAck,
                CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(plistStale)
        }

        // A stale phone ResetComplete is only discarded in the negotiated representation.
        val resetComplete = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.RESET_COMPLETE,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 1,
            acknowledgementSequenceNumber = 1,
            vehicleId = pair.phone.phoneVehicleId,
            version = CarPlayUiSyncProtocolVersion.V3,
        )
        assertTrue(pair.channel.receive(encode(resetComplete)).outboundCarriers.isEmpty())
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(
                CarPlayUiSyncCodec.encode(
                    CarPlayUiSyncCarrier(
                        DISPLAY_ID,
                        CarPlayUiSyncProtocolVersion.V3,
                        resetComplete,
                        CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
                    ),
                ),
            )
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(encode(resetComplete.copy(vehicleId = "ANOTHER-PHONE")))
        }
        assertEquals(before, protocolState(pair.channel.snapshot()))
    }

    private fun phonePlistReset(phoneId: String, version: Int): ByteArray = OpackCodec.encode(
        linkedMapOf(
            "displayUUID" to DISPLAY_ID,
            "data" to BplistCodec.encode(
                linkedMapOf(
                    "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0,
                    "id" to phoneId, "version" to version,
                ),
            ),
        ),
    )

    /**
     * A phone command (psn consumed by [phone]) whose `data` holds an OPACK value of a type the
     * codec cannot represent: an [size]-byte Data value (tag `0x70 + size`) is re-tagged [tag],
     * which has the same payload width (UUID: 16 bytes, absolute time: 8-byte float64).
     */
    private fun phoneCommandWithPatchedValue(
        phone: CarPlayUiSyncPhoneRoleSimulator,
        size: Int,
        tag: Int,
    ): ByteArray {
        val marker = ByteArray(size) { (0xA0 + it).toByte() }
        val bytes = phone.sendCommand(CarPlayUiSyncCommandType.METADATA_TRANSFER, mapOf("v" to marker))
        val index = indexOf(bytes, byteArrayOf((0x70 + size).toByte()) + marker)
        return bytes.copyOf().also { it[index] = tag.toByte() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        }
        throw AssertionError("pattern not found")
    }

    /** Sequencing state only; the replay cache legitimately remembers discarded duplicates. */
    private fun protocolState(snapshot: CarPlayUiSyncSessionSnapshot) =
        snapshot.copy(rememberedFrameCount = 0, rememberedFrameBytes = 0L)

    @Test
    fun rejectsWrongDisplayIdAndHandshakeOrderWithoutChangingState() {
        val channel = endpoint().openChannel()
        channel.open()
        val before = channel.snapshot()
        val phone = CarPlayUiSyncPhoneRoleSimulator("another-display")
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            channel.receive(phone.open())
        }
        assertEquals(before, channel.snapshot())

        val command = encode(phoneCommand(noAck = true).message)
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            channel.receive(command)
        }
        assertEquals(before, channel.snapshot())
    }

    @Test
    fun rejectsInvalidPhoneResetFields() {
        listOf<(CarPlayUiSyncMessage) -> CarPlayUiSyncMessage>(
            { it.copy(sessionSequenceNumber = 0) },
            { it.copy(sessionSequenceNumber = 2) },
            { it.copy(packetSequenceNumber = 2) },
            { it.copy(acknowledgementSequenceNumber = 1) },
            { it.copy(noAck = true) },
            { it.copy(vehicleId = null) },
            { it.copy(version = null) },
        ).forEach { mutation ->
            val channel = endpoint().openChannel()
            channel.open()
            val before = channel.snapshot()
            val reset = mutation(resetMessage(UUID.randomUUID().toString()))
            assertThrows(CarPlayUiSyncProtocolException::class.java) {
                channel.receive(
                    CarPlayUiSyncCodec.encode(
                        CarPlayUiSyncCarrier(
                            DISPLAY_ID,
                            CarPlayUiSyncProtocolVersion.V3,
                            reset,
                            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
                        ),
                    ),
                )
            }
            assertEquals(before, channel.snapshot())
        }
    }

    @Test
    fun repeatedPhoneResetIsAnsweredAgainWithTheSameReset() {
        val channel = endpoint().openChannel()
        channel.open()
        val reset = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID).open()
        val first = channel.receive(reset)
        val afterFirst = channel.snapshot()

        val repeated = channel.receive(reset)

        assertEquals(afterFirst, channel.snapshot())
        assertArrayEquals(first.outboundCarriers.single(), repeated.outboundCarriers.single())
    }

    @Test
    fun rejectsAckWithoutPendingCommandAndBoundsPendingCommands() {
        val pair = readyPair()
        val unsolicitedAck = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.COMMAND_ACK,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 2,
            acknowledgementSequenceNumber = 2,
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(encode(unsolicitedAck))
        }

        val boundedPair = readyPair(
            limits = CarPlayUiSyncLimits(maximumPendingAcknowledgements = 2),
        )
        boundedPair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_LAYOUT)
        boundedPair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_FOCUS)
        assertEquals(setOf(3L, 4L), boundedPair.channel.snapshot().pendingAcknowledgements)
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            boundedPair.channel.sendCommand(CarPlayUiSyncCommandType.ALLOW_TRANSITIONS)
        }
    }

    @Test
    fun rememberedReplayFramesStayWithinAggregateByteBudget() {
        val limits = CarPlayUiSyncLimits(
            maximumCarrierBytes = 4096,
            maximumInnerMessageBytes = 2048,
            maximumValueBytes = 1024,
            maximumStringChars = 256,
            maximumCollectionEntries = 256,
            maximumNestingDepth = 16,
            maximumRememberedFrames = 128,
            maximumRememberedFrameBytes = 4096L + 32L,
        )
        val pair = readyPair(limits = limits)

        repeat(64) {
            val command = pair.phone.sendCommand(
                CarPlayUiSyncCommandType.GIVE_FOCUS,
                mapOf("tokenID" to it),
            )
            val acknowledgement = pair.channel.receive(command).outboundCarriers.single()
            assertTrue(pair.phone.receive(acknowledgement).isEmpty())
            assertNull(pair.phone.lastDropReason)
        }

        val snapshot = pair.channel.snapshot()
        assertTrue(snapshot.rememberedFrameBytes <= limits.maximumRememberedFrameBytes)
        assertTrue(snapshot.rememberedFrameCount < 128)
    }

    @Test
    fun closeAndReopenDropsAllChannelStateAndWaitsForThePhone() {
        val pair = readyPair()
        pair.channel.sendCommand(CarPlayUiSyncCommandType.REQUEST_LAYOUT)
        assertFalse(pair.channel.snapshot().pendingAcknowledgements.isEmpty())

        pair.channel.close()
        val closed = pair.channel.snapshot()
        assertFalse(closed.open)
        assertEquals(CarPlayUiSyncSessionState.INITIALIZED, closed.state)
        assertTrue(closed.pendingAcknowledgements.isEmpty())
        assertEquals(null, closed.negotiatedVersion)
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            pair.channel.receive(ByteArray(0))
        }

        assertTrue(pair.channel.open().outboundCarriers.isEmpty())
        assertEquals(CarPlayUiSyncSessionState.INITIALIZED, pair.channel.snapshot().state)
    }

    @Test
    fun exactRequestLayoutEchoIsAttestedOnlyAfterItsAckAndOnlyOnce() {
        val pair = readyPair()
        val request = CarPlayUiSyncAccessoryCommand.RequestLayout(
            layoutID = "main-local-canvas", appearanceMode = 1,
        )
        val ack = pair.phone.receive(pair.channel.sendCommand(request).outboundCarriers.single())
            .single()
        assertTrue(pair.channel.receive(ack).outboundCarriers.isEmpty())
        assertTrue(pair.channel.snapshot().pendingAcknowledgements.isEmpty())
        // The phone echoes the decoded dictionary it received (its Int became a wire integer).
        val echoed = pair.phone.receivedCommands.single().data
        assertTrue(pair.channel.receive(pair.phone.sendCommand(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, echoed)).outboundCarriers.size == 1)
        val event = pair.events.last()
        assertEquals(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, event.command)
        assertTrue(event.echoesAcknowledgedRequestLayout)

        // That request's single echo opportunity is spent; neither a repeat nor type 19 counts.
        pair.channel.receive(pair.phone.sendCommand(
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, echoed))
        assertFalse(pair.events.last().echoesAcknowledgedRequestLayout)
        pair.channel.receive(pair.phone.sendCommand(
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE, mapOf("preference" to 1)))
        assertFalse(pair.events.last().echoesAcknowledgedRequestLayout)
    }

    @Test
    fun requestLayoutEchoWithoutPriorAckMismatchOrStaleRequestIsNotAttested() {
        val request = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "main-local-canvas")
        fun attested(mutate: (Map<String, Any?>) -> Any?): Boolean {
            val pair = readyPair()
            pair.channel.receive(pair.phone.receive(pair.channel.sendCommand(request)
                .outboundCarriers.single()).single())
            @Suppress("UNCHECKED_CAST")
            val sent = pair.phone.receivedCommands.last().data as Map<String, Any?>
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, mutate(sent)))
            return pair.events.last().echoesAcknowledgedRequestLayout
        }

        assertTrue(attested({ it }))
        // The echo's own asn acknowledges the request, but a separate ACK must come first.
        run {
            val pair = readyPair()
            pair.channel.sendCommand(request)
            val requestPsn = pair.channel.snapshot().nextTransmitPacketSequenceNumber - 1L
            pair.channel.receive(encode(CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.COMMAND,
                sessionSequenceNumber = 1,
                packetSequenceNumber = pair.phone.nextTransmitPacketSequenceNumber,
                acknowledgementSequenceNumber = requestPsn,
                payload = CarPlayUiSyncCommandEnvelope(
                    CarPlayUiSyncCommandType.END_LAYOUT_CHANGE.wireValue, request.wireData()),
            )))
            assertTrue(pair.channel.snapshot().pendingAcknowledgements.isEmpty())
            assertEquals(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, pair.events.last().command)
            assertFalse("echo before ACK", pair.events.last().echoesAcknowledgedRequestLayout)
        }
        assertFalse("changed value", attested({ LinkedHashMap(it).apply {
            put("fadeInNewLayout", false) } }))
        assertFalse("extra key", attested({ LinkedHashMap(it).apply {
            put("paletteID", "palette-a") } }))
        assertFalse("missing key", attested({ LinkedHashMap(it).apply {
            remove("activeComponents") } }))
        assertFalse("timed form", attested({
            mapOf("regions" to emptyList<Any>(), "current_ntp_time" to 3L) }))
        // A newer request supersedes: an echo of the older, acknowledged one never counts.
        run {
            val pair = readyPair()
            pair.channel.receive(pair.phone.receive(pair.channel.sendCommand(request)
                .outboundCarriers.single()).single())
            val first = pair.phone.receivedCommands.single().data
            pair.channel.receive(pair.phone.receive(pair.channel.sendCommand(
                CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "other"))
                .outboundCarriers.single()).single())
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, first))
            // Overlapping requests: the older acknowledged one is still answerable, once.
            assertTrue("older request", pair.events.last().echoesAcknowledgedRequestLayout)
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, first))
            assertFalse("spent request", pair.events.last().echoesAcknowledgedRequestLayout)
        }
        // R1 ACKed, R2 sent but not yet ACKed: E1 is attested, an early E2 is not (and is spent).
        run {
            val pair = readyPair()
            pair.channel.receive(pair.phone.receive(pair.channel.sendCommand(request)
                .outboundCarriers.single()).single())
            val first = pair.phone.receivedCommands.single().data
            val second = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "other")
            pair.channel.sendCommand(second)
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, first))
            assertTrue("overlapping first", pair.events.last().echoesAcknowledgedRequestLayout)
        }
        // A noAck request can never be acknowledged and therefore never attested.
        run {
            val pair = readyPair()
            pair.phone.receive(pair.channel.sendCommand(request, noAck = true)
                .outboundCarriers.single())
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
                pair.phone.receivedCommands.single().data))
            assertFalse("noAck request", pair.events.last().echoesAcknowledgedRequestLayout)
        }
        // A phone Reset (new epoch) ends the request even though its ACK had arrived.
        run {
            val pair = readyPair()
            pair.channel.receive(pair.phone.receive(pair.channel.sendCommand(request)
                .outboundCarriers.single()).single())
            val stale = pair.phone.receivedCommands.single().data
            val ourReset = pair.channel.receive(pair.phone.open()).outboundCarriers.single()
            val phoneResetComplete = pair.phone.receive(ourReset).single()
            assertTrue(pair.phone.receive(pair.channel.receive(phoneResetComplete)
                .outboundCarriers.single()).isEmpty())
            assertEquals(CarPlayUiSyncSessionState.READY, pair.channel.snapshot().state)
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, stale))
            assertFalse("stale epoch", pair.events.last().echoesAcknowledgedRequestLayout)
        }
        // No request at all.
        run {
            val pair = readyPair()
            pair.channel.receive(pair.phone.sendCommand(
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, request.wireData()))
            assertFalse("no request", pair.events.last().echoesAcknowledgedRequestLayout)
        }
    }

    private fun readyPair(
        phoneMaximumVersion: CarPlayUiSyncProtocolVersion = CarPlayUiSyncProtocolVersion.V3,
        eventSink: CarPlayUiSyncPresentationEventSink? = null,
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
        acceptedCommandClockNanos: () -> Long = System::nanoTime,
    ): ReadyPair {
        val events = mutableListOf<CarPlayUiSyncPresentationEvent.KnownCommand>()
        val channel = endpoint(
            eventSink = eventSink ?: CarPlayUiSyncPresentationEventSink { event ->
                if (event is CarPlayUiSyncPresentationEvent.KnownCommand) events += event
            },
            limits = limits,
            acceptedCommandClockNanos = acceptedCommandClockNanos,
        ).openChannel()
        val phone = CarPlayUiSyncPhoneRoleSimulator(
            displayId = DISPLAY_ID,
            maximumVersion = phoneMaximumVersion,
            limits = limits,
        )
        assertTrue(channel.open().outboundCarriers.isEmpty())
        val ourReset = channel.receive(phone.open()).outboundCarriers.single()
        val phoneResetComplete = phone.receive(ourReset).single()
        val ourResetComplete = channel.receive(phoneResetComplete).outboundCarriers.single()
        assertTrue(phone.receive(ourResetComplete).isEmpty())
        assertNull(phone.lastDropReason)
        assertEquals(CarPlayUiSyncSessionState.READY, phone.state)
        assertEquals(CarPlayUiSyncSessionState.READY, channel.snapshot().state)
        return ReadyPair(phone, channel, events)
    }

    private fun endpoint(
        displayId: String = DISPLAY_ID,
        eventSink: CarPlayUiSyncPresentationEventSink = CarPlayUiSyncPresentationEventSink { },
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
        outboundResetConfig: Any? = null,
        acceptedCommandClockNanos: () -> Long = System::nanoTime,
    ) = CarPlayUiSyncEndpoint(
        descriptor = CarPlayUiSyncChannelDescriptor(
            displayId = displayId,
            vehicleId = SIGNED_PROFILE_VEHICLE_ID,
        ),
        mode = CarPlayUiSyncMode.PASSIVE,
        presentationEventSink = eventSink,
        limits = limits,
        outboundResetConfig = outboundResetConfig,
        acceptedCommandClockNanos = acceptedCommandClockNanos,
    )

    private fun assertReady(
        channel: CarPlayUiSyncChannel,
        version: CarPlayUiSyncProtocolVersion,
    ) {
        val snapshot = channel.snapshot()
        assertTrue(snapshot.open)
        assertEquals(CarPlayUiSyncSessionState.READY, snapshot.state)
        assertEquals(version, snapshot.negotiatedVersion)
    }

    private fun resetMessage(phoneId: String) = CarPlayUiSyncMessage(
        type = CarPlayUiSyncMessageType.RESET,
        sessionSequenceNumber = 1,
        packetSequenceNumber = 1,
        acknowledgementSequenceNumber = 0,
        vehicleId = phoneId,
        version = CarPlayUiSyncProtocolVersion.V3,
    )

    /** The phone's first command after READY: psn=2, asn=2, no id/version. */
    private fun phoneCommand(noAck: Boolean) = CarPlayUiSyncCarrier(
        displayId = DISPLAY_ID,
        serializationVersion = CarPlayUiSyncProtocolVersion.V3,
        message = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.COMMAND,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 2,
            acknowledgementSequenceNumber = 2,
            payload = CarPlayUiSyncCommandEnvelope(7),
            noAck = noAck,
        ),
    )

    /** v3 dictionary carrier, the phone's representation after negotiation. */
    private fun encode(message: CarPlayUiSyncMessage): ByteArray = CarPlayUiSyncCodec.encode(
        CarPlayUiSyncCarrier(DISPLAY_ID, CarPlayUiSyncProtocolVersion.V3, message),
    )

    private fun outer(bytes: ByteArray): Map<*, *> = OpackCodec.decode(bytes) as Map<*, *>

    /** Asserts OPACK `{displayUUID, data: bplist Data}` and returns the plist message. */
    private fun plistInner(bytes: ByteArray, displayId: String): Map<*, *> {
        val outer = outer(bytes)
        assertEquals(setOf("displayUUID", "data"), outer.keys)
        assertEquals(displayId, outer["displayUUID"])
        val data = outer["data"] as ByteArray
        assertEquals("bplist00", String(data, 0, 8, Charsets.US_ASCII))
        return BplistCodec.decode(data) as Map<*, *>
    }

    private data class ReadyPair(
        val phone: CarPlayUiSyncPhoneRoleSimulator,
        val channel: CarPlayUiSyncChannel,
        val events: MutableList<CarPlayUiSyncPresentationEvent.KnownCommand>,
    )

    private companion object {
        /** Signed bench binding: authorizes the Profile, never compared with the phone id. */
        const val SIGNED_PROFILE_VEHICLE_ID = "signed-bench-vehicle"
        const val DISPLAY_ID = "instrument-cluster"
        const val PHONE_DISPLAY_ID = "xcertplay-main"
    }
}
