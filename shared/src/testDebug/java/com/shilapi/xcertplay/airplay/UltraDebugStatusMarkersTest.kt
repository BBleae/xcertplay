package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPhoneRoleSimulator
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnosticKind
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestOutbox
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestSessionAccess
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundRequestState
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneUse
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneUseSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCarrierShape
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraDebugStatusMarkersTest {
    @Test
    fun authenticatedChannelResetReadyRaw5AndAckFollowTheExactStateOrder() {
        val endpoint = endpoint()
        val wire = ArrayList<ByteArray>()
        val observed = ArrayList<UltraDebugStatusEvent>()
        val outbound = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint)),
            debugStatus = { event, id ->
                assertEquals(DISPLAY_ID, id)
                observed += event
            },
            sendCarrier = { wire += it },
        )
        val peer = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID)
        try {
            assertTrue(outbound.open().outboundCarriers.isEmpty())
            peer.handshake { outbound.receive(it).outboundCarriers }
            outbound.sendCommand(
                DISPLAY_ID,
                CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "private-layout"),
            )
            val ack = peer.receive(wire.last()).single()
            outbound.receive(ack)
            assertEquals(listOf(
                UltraDebugStatusEvent.UI_RESET_RECEIVED,
                UltraDebugStatusEvent.UI_RESET_SENT,
                UltraDebugStatusEvent.UI_RESET_COMPLETE_RECEIVED,
                UltraDebugStatusEvent.UI_RESET_COMPLETE_SENT,
                UltraDebugStatusEvent.UI_READY,
                UltraDebugStatusEvent.RAW5_WRITE_RETURNED,
                UltraDebugStatusEvent.RAW5_ACK,
            ), observed)
        } finally {
            outbound.close()
        }
    }

    @Test
    fun resynchronizingResetAfterASkippedPhonePsnIsReportedAsSentOnly() {
        val wire = ArrayList<ByteArray>()
        val observed = ArrayList<UltraDebugStatusEvent>()
        val outbound = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint())),
            debugStatus = { event, _ -> observed += event },
            sendCarrier = { wire += it },
        )
        val peer = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID)
        try {
            outbound.open()
            peer.handshake { outbound.receive(it).outboundCarriers }
            observed.clear()
            peer.sendCommand(CarPlayUiSyncCommandType.GIVE_FOCUS, noAck = true) // lost
            outbound.receive(peer.sendCommand(CarPlayUiSyncCommandType.GIVE_FOCUS, noAck = true))
            assertEquals(listOf(UltraDebugStatusEvent.UI_RESET_SENT), observed)
            val phoneResetComplete = peer.receive(wire.last()).single()
            outbound.receive(phoneResetComplete)
            assertTrue(peer.receive(wire.last()).isEmpty())
            assertEquals(listOf(
                UltraDebugStatusEvent.UI_RESET_SENT,
                UltraDebugStatusEvent.UI_RESET_COMPLETE_RECEIVED,
                UltraDebugStatusEvent.UI_RESET_COMPLETE_SENT,
                UltraDebugStatusEvent.UI_READY,
            ), observed)
        } finally {
            outbound.close()
        }
    }

    @Test
    fun queuedRequestAndFormatterNeverExposeIdentifiersOrPayloads() {
        val outbox = UiSyncForegroundRequestOutbox(listOf(DISPLAY_ID))
        val events = ArrayList<Pair<UltraDebugStatusEvent, String?>>()
        outbox.setDebugStatusObserver { event, id -> events += event to id }
        outbox.bindSession(object : UiSyncForegroundRequestSessionAccess {
            override fun acquireUse(): Closeable = Closeable { }
            override fun isLive(): Boolean = true
        })
        val scene = outbox.guardScene(DISPLAY_ID, UiSyncForegroundSceneUseSource {
            object : UiSyncForegroundSceneUse {
                override fun isCurrent() = true
                override fun close() = Unit
            }
        })!!
        val receipt = outbox.requestLayout(
            DISPLAY_ID,
            CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = "private-layout"),
            scene,
        )
        assertEquals(UiSyncForegroundRequestState.QUEUED, receipt.state)
        assertEquals(listOf(UltraDebugStatusEvent.RAW5_QUEUED to DISPLAY_ID), events)
        outbox.close()

        val formatted = ArrayList<String>()
        UltraDebugStatusMarkers.emit(formatted::add, UltraDebugStatusEvent.RAW5_QUEUED,
            "private-display-identifier")
        UltraDebugStatusMarkers.emit(formatted::add, UltraDebugStatusEvent.RAW5_ACK, "d63")
        assertEquals(listOf(
            "ULTRA_STATUS event=RAW5_QUEUED display=none",
            "ULTRA_STATUS event=RAW5_ACK display=d63",
        ), formatted)
        assertTrue(formatted.all { it.length <= 80 })
        assertFalse(formatted.any { "private" in it })
    }

    @Test
    fun rejectedCarriersExposeOnlyFixedCategoriesAndNoUnknownDisplayIdentifier() {
        val observed = ArrayList<Pair<UltraDebugStatusEvent, String?>>()
        val outbound = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint())),
            debugStatus = { event, id -> observed += event to id },
            sendCarrier = { },
        )
        val unknownPeer = CarPlayUiSyncPhoneRoleSimulator("private-unknown-display")
        try {
            outbound.open()
            observed.clear()

            val malformed = outbound.receive(byteArrayOf(0x01, 0x02, 0x03))
            val unknown = outbound.receive(unknownPeer.open())

            assertEquals(listOf(CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER),
                malformed.diagnostics.map { it.kind })
            assertEquals(listOf(CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY),
                unknown.diagnostics.map { it.kind })
            assertEquals(listOf(
                UltraDebugStatusEvent.UI_MALFORMED_CARRIER to null,
                UltraDebugStatusEvent.UI_UNKNOWN_DISPLAY to null,
            ), observed)
            val formatted = ArrayList<String>()
            observed.forEach { (event, id) ->
                UltraDebugStatusMarkers.emit(formatted::add, event, id)
            }
            assertEquals(listOf(
                "ULTRA_STATUS event=UI_MALFORMED_CARRIER display=none",
                "ULTRA_STATUS event=UI_UNKNOWN_DISPLAY display=none",
            ), formatted)
            assertFalse(formatted.any { "private" in it })
        } finally {
            outbound.close()
        }
    }

    @Test
    fun rejectedCarrierShapesAreLoggedOncePerKindWithoutValuesOrIdentifiers() {
        val shapes = ArrayList<UiSyncCarrierShape>()
        val outbound = SerializedUiSyncOutbound(
            registry = CarPlayUiSyncEndpointRegistry(listOf(endpoint())),
            debugStatus = { _, _ -> },
            debugCarrierShape = { shapes += it },
            sendCarrier = { },
        )
        try {
            outbound.open()
            // Old accessory format (bplist outer): MALFORMED_CARRIER under the OPACK codec.
            val legacy = BplistCodec.encode(
                linkedMapOf("displayUUID" to DISPLAY_ID, "data" to phoneMessage("private-vehicle")),
            )
            val unknown = phoneReset(PHONE_DISPLAY_ID, "private-phone-vehicle")
            // The first phone Reset binds its id; a later Reset with another id is rejected.
            val firstPhoneReset = phoneReset(DISPLAY_ID, "private-phone-vehicle")
            val wrongVehicle = phoneReset(DISPLAY_ID, "private-other-vehicle")
            listOf(legacy, legacy, unknown, unknown, firstPhoneReset, wrongVehicle, wrongVehicle)
                .forEach { outbound.receive(it) }

            val formatted = ArrayList<String>()
            shapes.forEach { UltraDebugStatusMarkers.emitCarrierShape(formatted::add, it) }
            assertEquals(3, formatted.size)
            assertTrue(formatted[0], formatted[0].startsWith(
                "ULTRA_STATUS event=UI_CARRIER_SHAPE kind=MALFORMED_CARRIER fmt=bplist00 " +
                    "size=${legacy.size} shape={data:data(bplist00){asn:int,id:str(local-vehicle)," +
                    "psn:int,ssn:int,type:int,version:int},displayUUID:str(local-display)} " +
                    "err=CarPlayUiSyncProtocolException:\"UI Sync carrier is not a valid OPACK value\"",
            ))
            assertEquals(
                "ULTRA_STATUS event=UI_CARRIER_SHAPE kind=UNKNOWN_DISPLAY fmt=opack-dict " +
                    "size=${unknown.size} shape={data:data(bplist00){asn:int,id:str,psn:int," +
                    "ssn:int,type:int,version:int},displayUUID:str(uuid)} err=none",
                formatted[1],
            )
            assertEquals(
                "ULTRA_STATUS event=UI_CARRIER_SHAPE kind=DISPLAY_PROTOCOL_ERROR fmt=opack-dict " +
                    "size=${wrongVehicle.size} shape={data:data(bplist00){asn:int,id:str,psn:int," +
                    "ssn:int,type:int,version:int},displayUUID:str(local-display)} " +
                    "err=CarPlayUiSyncProtocolException:\"UI Sync reset vehicle ID does not " +
                    "match the identifier bound by the first phone reset\"",
                formatted[2],
            )
            formatted.forEach { line ->
                assertFalse(line, "private" in line)
                assertFalse(line, PHONE_DISPLAY_ID in line)
                assertFalse(line, PHONE_DISPLAY_ID.lowercase() in line)
                assertTrue(line, line.length <= 800)
            }
        } finally {
            outbound.close()
        }
    }

    private fun phoneMessage(vehicleId: String): ByteArray = BplistCodec.encode(
        linkedMapOf(
            "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0, "id" to vehicleId, "version" to 3,
        ),
    )

    private fun phoneReset(displayId: String, vehicleId: String): ByteArray = OpackCodec.encode(
        linkedMapOf("displayUUID" to displayId, "data" to phoneMessage(vehicleId)),
    )

    private fun endpoint() = CarPlayUiSyncEndpoint(
        descriptor = CarPlayUiSyncChannelDescriptor(DISPLAY_ID, "private-vehicle"),
        mode = CarPlayUiSyncMode.PASSIVE,
        presentationEventSink = CarPlayUiSyncPresentationEventSink { },
    )

    private companion object {
        const val DISPLAY_ID = "private-display-id"
        const val PHONE_DISPLAY_ID = "B7E6C5A0-1111-4000-8000-000000000001"
    }
}
