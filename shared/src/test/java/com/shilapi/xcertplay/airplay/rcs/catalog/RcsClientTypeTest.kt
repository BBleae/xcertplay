package com.shilapi.xcertplay.airplay.rcs.catalog

import com.shilapi.xcertplay.airplay.AirPlayFeature
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RcsClientTypeTest {
    @Test
    fun catalogUuidsMatchObservedProtocolIdentifiers() {
        val expected = mapOf(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA to
                "3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E",
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2 to
                "FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634",
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL to
                "07D9F906-8D64-4B54-A808-A20BCA2C51C2",
            RcsClientTypes.CAR_PLAY_UPDATE_DATA to
                "09A8AA2D-8932-4048-9492-6B658D42C2A3",
            RcsClientTypes.CAR_PLAY_LOGGING_DATA to
                "75AD9926-4777-42B2-A7D8-823EBEECF7AA",
            RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI to
                "E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654",
            RcsClientTypes.CAR_PLAY_VIDEO_SETTINGS to
                "BB493F61-A6B8-4769-8D74-80C23A9F71C4",
            RcsClientTypes.CAR_PLAY_MATCH_POINT to
                "A6B27562-B43A-4F2D-B75F-82391E250194",
            RcsClientTypes.IAP_CHANNEL to
                "E9459FD0-BCAD-4C45-820F-1E72447EF2F2",
        )

        assertEquals(RcsClientTypes.ALL.toSet(), expected.keys)
        expected.forEach { (clientType, uuid) ->
            assertEquals(UUID.fromString(uuid), clientType.uuid)
        }
    }

    @Test
    fun uuidFactoryResolvesEveryCataloguedClientType() {
        RcsClientTypes.ALL.forEach { expected ->
            val actual = RcsClientTypes.findByUuid(expected.uuid.toString())
            assertEquals(expected, actual)
        }
        assertNull(RcsClientTypes.findByUuid("00000000-0000-0000-0000-000000000000"))
        assertThrows(IllegalArgumentException::class.java) {
            RcsClientTypes.requireByUuid("not-a-uuid")
        }
    }

    @Test
    fun clientTypesCarrySemanticFeatureAndTransportMetadata() {
        val data = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA
        assertEquals(AirPlayFeature.VEHICLE_STATE_PROTOCOL, data.feature)
        assertEquals(0, data.priority)
        assertEquals(0, data.qualityOfService)
        assertEquals(0, data.streamPriority)
        assertEquals(RcsPayloadStyle.CAF_BINARY_PLIST_OPACK, data.payloadStyle)
        assertTrue(data.hasConfirmedSchema)

        val data2 = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2
        assertEquals(1, data2.priority)
        assertEquals(12, data2.qualityOfService)
        assertEquals(33, data2.streamPriority)

        assertEquals(AirPlayFeature.UI_SYNC, RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.feature)
        assertEquals(
            RcsPayloadStyle.UI_SYNC_PLIST,
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.payloadStyle,
        )
        assertEquals(RcsPayloadStyle.RAW_IAP2, RcsClientTypes.IAP_CHANNEL.payloadStyle)
        assertEquals(AirPlayFeature.FILE_TRANSFER, RcsClientTypes.CAR_PLAY_UPDATE_DATA.feature)
        assertEquals(
            RcsPayloadStyle.UPDATE_DATA_OPACK,
            RcsClientTypes.CAR_PLAY_UPDATE_DATA.payloadStyle,
        )
        assertTrue(RcsClientTypes.CAR_PLAY_UPDATE_DATA.hasConfirmedSchema)
    }

    @Test
    fun metadataOnlyTypesCannotBeMistakenForConfirmedSchemas() {
        val metadataOnly = listOf(
            RcsClientTypes.CAR_PLAY_LOGGING_DATA,
            RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI,
            RcsClientTypes.CAR_PLAY_VIDEO_SETTINGS,
        )
        metadataOnly.forEach { clientType ->
            assertEquals(RcsCatalogReadiness.METADATA_ONLY, clientType.catalogReadiness)
            assertEquals(RcsPayloadStyle.UNCONFIRMED_OPAQUE, clientType.payloadStyle)
            assertFalse(clientType.hasConfirmedSchema)
        }
        assertEquals(
            AirPlayFeature.VIDEO_PLAYBACK,
            RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI.feature,
        )
        assertEquals(
            AirPlayFeature.VIDEO_PLAYBACK,
            RcsClientTypes.CAR_PLAY_VIDEO_SETTINGS.feature,
        )
        assertEquals(RcsCatalogReadiness.METADATA_ONLY, RcsClientTypes.CAR_PLAY_MATCH_POINT.catalogReadiness)
        assertEquals(RcsPayloadStyle.MATCH_POINT_BINARY_PLIST, RcsClientTypes.CAR_PLAY_MATCH_POINT.payloadStyle)
        assertEquals(AirPlayFeature.VIDEO_PLAYBACK, RcsClientTypes.CAR_PLAY_MATCH_POINT.feature)
        assertFalse(RcsClientTypes.CAR_PLAY_MATCH_POINT.hasConfirmedSchema)
    }

    @Test
    fun unknownUuidIsPreservedAsOpaqueNonReadyMetadata() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-000000000123")
        val unknown = RcsClientTypes.metadata(uuid, " Future\u0000Client ")

        assertEquals(uuid, unknown.uuid)
        assertEquals("FutureClient", unknown.name)
        assertNull(unknown.feature)
        assertEquals(RcsPayloadStyle.UNCONFIRMED_OPAQUE, unknown.payloadStyle)
        assertEquals(RcsCatalogReadiness.UNKNOWN, unknown.catalogReadiness)
        assertFalse(unknown.hasConfirmedSchema)

        assertEquals(
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            RcsClientTypes.metadata(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA.uuid, "spoofed"),
        )
        assertNull(RcsClientTypes.metadataByUuid("not-a-uuid"))
    }
}
