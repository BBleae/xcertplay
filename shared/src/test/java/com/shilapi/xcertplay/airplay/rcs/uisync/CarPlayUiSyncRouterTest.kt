package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUiSyncRouterTest {
    @Test
    fun registrySnapshotsEndpointsAndRejectsDuplicateOpaqueDisplayIds() {
        val source = mutableListOf(endpoint(DISPLAY_A), endpoint(DISPLAY_B))
        val registry = CarPlayUiSyncEndpointRegistry(source)
        source.clear()

        assertEquals(listOf(DISPLAY_A, DISPLAY_B), registry.endpoints.map {
            it.descriptor.displayId
        })
        assertEquals(setOf(DISPLAY_A, DISPLAY_B), registry.displayIds)
        assertTrue(registry.isProtocolRuntimeComplete)
        assertFalse(
            CarPlayUiSyncEndpointRegistry(
                listOf(
                    endpoint(
                        DISPLAY_A,
                        limits = CarPlayUiSyncLimits(maximumIdentifierChars = 4),
                    ),
                ),
            ).isProtocolRuntimeComplete,
        )
        val tooSmallForReset = CarPlayUiSyncLimits(
            maximumCarrierBytes = 36,
            maximumInnerMessageBytes = 36,
            maximumValueBytes = 36,
            maximumStringChars = 36,
            maximumIdentifierChars = 36,
        )
        assertFalse(
            CarPlayUiSyncEndpointRegistry(
                listOf(endpoint(DISPLAY_A, limits = tooSmallForReset)),
            ).isProtocolRuntimeComplete,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncEndpointRegistry(listOf(endpoint(DISPLAY_A), endpoint(DISPLAY_A)))
        }
    }

    @Test
    fun registryBoundsDishonestAndUnendingCollectionsWithoutTrustingDeclaredSize() {
        val dishonestSize = object : AbstractCollection<CarPlayUiSyncEndpoint>() {
            override val size: Int = Int.MAX_VALUE
            override fun iterator(): Iterator<CarPlayUiSyncEndpoint> =
                listOf(endpoint(DISPLAY_A)).iterator()
        }
        assertEquals(setOf(DISPLAY_A), CarPlayUiSyncEndpointRegistry(dishonestSize).displayIds)

        var sequence = 0
        val unending = object : AbstractCollection<CarPlayUiSyncEndpoint>() {
            override val size: Int = 0
            override fun iterator(): Iterator<CarPlayUiSyncEndpoint> =
                object : Iterator<CarPlayUiSyncEndpoint> {
                    override fun hasNext(): Boolean = true
                    override fun next(): CarPlayUiSyncEndpoint = endpoint("generated-${sequence++}")
                }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayUiSyncEndpointRegistry(unending)
        }
        assertEquals(MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS, sequence)
    }

    @Test
    fun openAggregatesResetsAndEachDisplayCompletesAnIndependentHandshake() {
        val router = CarPlayUiSyncEndpointRegistry(
            listOf(endpoint(DISPLAY_A), endpoint(DISPLAY_B)),
        ).openRouter()

        // Passive: nothing is written until each display's phone Reset arrives.
        assertTrue(router.open().outboundCarriers.isEmpty())

        ready(router, DISPLAY_A)
        assertEquals(CarPlayUiSyncSessionState.READY, router.snapshot(DISPLAY_A)?.state)
        assertEquals(CarPlayUiSyncSessionState.INITIALIZED, router.snapshot(DISPLAY_B)?.state)

        ready(router, DISPLAY_B)
        assertEquals(CarPlayUiSyncSessionState.READY, router.snapshot(DISPLAY_A)?.state)
        assertEquals(CarPlayUiSyncSessionState.READY, router.snapshot(DISPLAY_B)?.state)

        router.close()
        assertFalse(requireNotNull(router.snapshot(DISPLAY_A)).open)
        assertFalse(requireNotNull(router.snapshot(DISPLAY_B)).open)
    }

    @Test
    fun unknownAndBrokenDisplayTrafficIsDroppedWithoutMutatingOtherDisplays() {
        val events = linkedMapOf(
            DISPLAY_A to mutableListOf<CarPlayUiSyncPresentationEvent>(),
            DISPLAY_B to mutableListOf<CarPlayUiSyncPresentationEvent>(),
        )
        val router = CarPlayUiSyncEndpointRegistry(
            listOf(
                endpoint(DISPLAY_A, CarPlayUiSyncPresentationEventSink(events[DISPLAY_A]!!::add)),
                endpoint(DISPLAY_B, CarPlayUiSyncPresentationEventSink(events[DISPLAY_B]!!::add)),
            ),
        ).openRouter()
        assertTrue(router.open().outboundCarriers.isEmpty())
        ready(router, DISPLAY_A)
        ready(router, DISPLAY_B)

        val beforeA = router.snapshot(DISPLAY_A)
        val beforeB = router.snapshot(DISPLAY_B)
        val wrongSession = router.receive(
            command(DISPLAY_A, packetSequenceNumber = 7, sessionSequenceNumber = 9),
        )
        assertEquals(
            listOf(CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR),
            wrongSession.diagnostics.map { it.kind },
        )
        assertTrue(wrongSession.outboundCarriers.isEmpty())
        assertEquals(beforeA, router.snapshot(DISPLAY_A))
        assertEquals(beforeB, router.snapshot(DISPLAY_B))

        val validB = router.receive(command(DISPLAY_B, packetSequenceNumber = 2))
        assertTrue(validB.diagnostics.isEmpty())
        assertEquals(1, validB.presentationEvents.size)
        assertTrue(events[DISPLAY_A]!!.isEmpty())
        assertEquals(1, events[DISPLAY_B]!!.size)
        assertEquals(beforeA, router.snapshot(DISPLAY_A))
        assertEquals(2L, router.snapshot(DISPLAY_B)?.lastReceivedPacketSequenceNumber)

        val afterValidA = router.snapshot(DISPLAY_A)
        val afterValidB = router.snapshot(DISPLAY_B)
        val unknown = router.receive(reset("unknown-but-valid-display"))
        assertEquals(
            CarPlayUiSyncRoutingDiagnostic(
                CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY,
                "unknown-but-valid-display",
            ),
            unknown.diagnostics.single(),
        )
        val malformed = router.receive(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(
            CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER,
            malformed.diagnostics.single().kind,
        )
        assertEquals(afterValidA, router.snapshot(DISPLAY_A))
        assertEquals(afterValidB, router.snapshot(DISPLAY_B))

        // A skipped psn on A (a phone packet A never applied) resynchronizes only A.
        val skipped = router.receive(command(DISPLAY_A, packetSequenceNumber = 7))
        assertTrue(skipped.diagnostics.isEmpty())
        assertTrue(skipped.presentationEvents.isEmpty())
        assertEquals(DISPLAY_A, CarPlayUiSyncCodec.peekDisplayId(skipped.outboundCarriers.single()))
        assertEquals(
            CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED,
            router.snapshot(DISPLAY_A)?.state,
        )
        assertEquals(1L, router.snapshot(DISPLAY_A)?.accessoryResetCount)
        assertEquals(afterValidB, router.snapshot(DISPLAY_B))
        assertTrue(events[DISPLAY_A]!!.isEmpty())
    }

    @Test
    fun longUnknownDisplayIdProducesOnlyABoundedDiagnostic() {
        val limits = CarPlayUiSyncLimits(maximumIdentifierChars = 256)
        val router = CarPlayUiSyncEndpointRegistry(
            listOf(endpoint(DISPLAY_A, limits = limits)),
        ).openRouter()
        router.open()
        val unknownId = "u".repeat(200)

        val result = router.receive(reset(unknownId, limits))

        assertEquals(
            CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY,
            result.diagnostics.single().kind,
        )
        assertEquals(128, result.diagnostics.single().displayId?.length)
        assertTrue(result.outboundCarriers.isEmpty())
    }

    private fun ready(router: CarPlayUiSyncRouter, displayId: String) {
        CarPlayUiSyncPhoneRoleSimulator(displayId).handshake { carrier ->
            router.receive(carrier).also { assertTrue(it.diagnostics.isEmpty()) }.outboundCarriers
        }
    }

    private fun endpoint(
        displayId: String,
        sink: CarPlayUiSyncPresentationEventSink = CarPlayUiSyncPresentationEventSink { },
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    ) = CarPlayUiSyncEndpoint(
        descriptor = CarPlayUiSyncChannelDescriptor(displayId, VEHICLE_ID),
        mode = CarPlayUiSyncMode.PASSIVE,
        presentationEventSink = sink,
        limits = limits,
    )

    private fun reset(
        displayId: String,
        limits: CarPlayUiSyncLimits = CarPlayUiSyncLimits(),
    ): ByteArray = CarPlayUiSyncCodec.encode(
        CarPlayUiSyncCarrier(
            displayId = displayId,
            serializationVersion = CarPlayUiSyncProtocolVersion.V3,
            message = CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.RESET,
                sessionSequenceNumber = 1,
                packetSequenceNumber = 1,
                acknowledgementSequenceNumber = 0,
                vehicleId = VEHICLE_ID,
                version = CarPlayUiSyncProtocolVersion.V3,
            ),
        ),
        limits,
    )

    private fun command(
        displayId: String,
        packetSequenceNumber: Long,
        sessionSequenceNumber: Long = 1,
    ): ByteArray =
        CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                displayId = displayId,
                serializationVersion = CarPlayUiSyncProtocolVersion.V3,
                message = CarPlayUiSyncMessage(
                    type = CarPlayUiSyncMessageType.COMMAND,
                    sessionSequenceNumber = sessionSequenceNumber,
                    packetSequenceNumber = packetSequenceNumber,
                    acknowledgementSequenceNumber = 1,
                    payload = CarPlayUiSyncCommandEnvelope(
                        CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue,
                    ),
                    noAck = true,
                ),
            ),
        )

    private companion object {
        const val DISPLAY_A = "instrument-cluster"
        const val DISPLAY_B = "secondary-cluster"
        const val VEHICLE_ID = "vehicle-test"
    }
}
