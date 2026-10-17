package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnosticKind
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCarrierShapeDescriber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraDebugStatusMarkersReleaseTest {
    @Test
    fun releaseMarkerIsAlwaysNoOpEvenWhenCalledWithAnAlias() {
        val messages = ArrayList<String>()
        UltraDebugStatusMarkers.emit(messages::add, UltraDebugStatusEvent.UI_READY, "d0")
        UltraDebugStatusMarkers.emit(messages::add, UltraDebugStatusEvent.UI_MALFORMED_CARRIER)
        UltraDebugStatusMarkers.emit(messages::add, UltraDebugStatusEvent.UI_UNKNOWN_DISPLAY)
        UltraDebugStatusMarkers.emitCarrierShape(
            messages::add,
            UiSyncCarrierShapeDescriber.describe(
                CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER,
                byteArrayOf(1, 2, 3),
                IllegalStateException("x"),
            ),
        )
        assertFalse(UltraDebugStatusMarkers.enabled)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun releaseOutboundNeverComputesOrDeliversCarrierShapes() {
        val shapes = ArrayList<Any>()
        val outbound = SerializedUiSyncOutbound(
            registry = com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpointRegistry(
                listOf(
                    com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint(
                        descriptor = com.shilapi.xcertplay.airplay.rcs.uisync
                            .CarPlayUiSyncChannelDescriptor("display", "vehicle"),
                        mode = com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode.PASSIVE,
                        presentationEventSink =
                            com.shilapi.xcertplay.airplay.rcs.uisync
                                .CarPlayUiSyncPresentationEventSink { },
                    ),
                ),
            ),
            debugStatus = { _, _ -> },
            debugCarrierShape = { shapes += it },
            sendCarrier = { },
        )
        try {
            outbound.open()
            val actions = outbound.receive(byteArrayOf(1, 2, 3))
            assertTrue(actions.diagnostics.isNotEmpty())
            assertTrue(shapes.isEmpty())
        } finally {
            outbound.close()
        }
    }
}
