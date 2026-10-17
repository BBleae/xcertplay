package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSyncCarrierShapeTest {
    @Test
    fun describesHypothesizedPhoneFirstResetWithoutValues() {
        val carrier = phoneReset(PHONE_DISPLAY_ID, PHONE_VEHICLE_ID)

        val shape = UiSyncCarrierShapeDescriber.describe(
            kind = CarPlayUiSyncRoutingDiagnosticKind.UNKNOWN_DISPLAY,
            carrier = carrier,
            rejection = null,
            localDisplayIds = listOf(LOCAL_DISPLAY_ID),
            localVehicleIds = listOf(LOCAL_VEHICLE_ID),
        )

        assertEquals(
            "kind=UNKNOWN_DISPLAY fmt=opack-dict size=${carrier.size} " +
                "shape={data:data(bplist00){asn:int,id:str,psn:int,ssn:int,type:int," +
                "version:int},displayUUID:str(uuid)} err=none",
            shape.render(),
        )
        assertNoSecrets(shape.render())
    }

    @Test
    fun describesLegacyPlistParamsWrapperAndMarksLocalIdentifiersByRoleOnly() {
        val wrapped = BplistCodec.encode(
            linkedMapOf(
                "params" to linkedMapOf(
                    "data" to OpackCodec.encode(
                        linkedMapOf(
                            "displayUUID" to LOCAL_DISPLAY_ID,
                            "data" to linkedMapOf(
                                "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0,
                                "id" to LOCAL_VEHICLE_ID, "version" to 3,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val failure = runCatching { CarPlayUiSyncCodec.peekDisplayId(wrapped) }.exceptionOrNull()

        val rendered = UiSyncCarrierShapeDescriber.describe(
            CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER,
            wrapped,
            failure,
            localDisplayIds = listOf(LOCAL_DISPLAY_ID),
            localVehicleIds = listOf(LOCAL_VEHICLE_ID),
        ).render()

        assertTrue(rendered, rendered.startsWith(
            "kind=MALFORMED_CARRIER fmt=bplist00 size=${wrapped.size} " +
                "shape={<key#1>:{data:data(opack-dict){data:dict(6)," +
                "displayUUID:str(local-display)}}} " +
                "err=CarPlayUiSyncProtocolException:\"UI Sync carrier is not a valid OPACK value\" " +
                "cause=",
        ))
        assertNoSecrets(rendered)
    }

    @Test
    fun neverRendersValuesUuidsHexOrLocalIdentifiersEvenInKeyNamesAndMessages() {
        val hostile = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to "SECRET-DISPLAY-VALUE",
                "data" to linkedMapOf(
                    "type" to 987654321,
                    PHONE_DISPLAY_ID to "SECRET-STRING-VALUE",
                    "deadbeefcafebabe" to 1,
                    "key-$LOCAL_DISPLAY_ID" to true,
                    "weird key\u0001\"" to 1.5,
                    "blob" to byteArrayOf(0x53, 0x45, 0x43, 0x52),
                    "nested" to linkedMapOf(
                        "level3" to linkedMapOf("level4" to linkedMapOf("x" to "SECRET-DEEP")),
                    ),
                    "items" to listOf("SECRET-ITEM", 4242424242L),
                ),
            ),
        )
        val rejection = IllegalStateException(
            "bad 'SECRET-QUOTED' $PHONE_DISPLAY_ID 0xDEADBEEF $LOCAL_DISPLAY_ID " +
                "cafebabe12345678 'version' tag 0x62\n\"x\"",
            IllegalArgumentException("$LOCAL_VEHICLE_ID ${PHONE_DISPLAY_ID.lowercase()}"),
        )

        val rendered = UiSyncCarrierShapeDescriber.describe(
            CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
            hostile,
            rejection,
            localDisplayIds = listOf(LOCAL_DISPLAY_ID),
            localVehicleIds = listOf(LOCAL_VEHICLE_ID),
        ).render()

        assertEquals(
            "kind=DISPLAY_PROTOCOL_ERROR fmt=opack-dict size=${hostile.size} " +
                "shape={data:{<key#1>:str,<key#2>:int,<key#3>:bool,<key#4>:real," +
                "<key#5>:data,<key#6>:{<key#7>:dict(1)},<key#8>:arr(2),type:int}," +
                "displayUUID:str} " +
                "err=IllegalStateException:\"bad '<?>' <uuid> <hex> <id> <hex> 'version' " +
                "tag <hex> <?>\" cause=IllegalArgumentException:\"<id> <uuid>\"",
            rendered,
        )
        assertNoSecrets(rendered)
        assertFalse(rendered.contains("987654321"))
        assertFalse(rendered.contains("4242424242"))
        assertFalse(rendered.contains("deadbeef", ignoreCase = true))
        assertFalse(rendered.contains("SECR"))
    }

    @Test
    fun onlySchemaAndKnownCommandKeysAreRenderedVerbatim() {
        val carrier = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to LOCAL_DISPLAY_ID,
                "data" to linkedMapOf(
                    "type" to 2, "ssn" to 1, "psn" to 2, "asn" to 2, "noAck" to true,
                    "data" to linkedMapOf(
                        "type" to 7,
                        "data" to linkedMapOf("tokenID" to 1, "privateKeyName" to 2),
                    ),
                    "homeAddress" to "x",
                ),
            ),
        )
        val rendered = UiSyncCarrierShapeDescriber.describe(
            CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
            carrier,
            null,
            localDisplayIds = listOf(LOCAL_DISPLAY_ID),
        ).render()

        assertEquals(
            "kind=DISPLAY_PROTOCOL_ERROR fmt=opack-dict size=${carrier.size} " +
                "shape={data:{<key#1>:str,asn:int,data:{data:dict(2),type:int},noAck:bool," +
                "psn:int,ssn:int,type:int},displayUUID:str(local-display)} err=none",
            rendered,
        )
        assertFalse(rendered.contains("homeAddress"))
        assertFalse(rendered.contains("privateKeyName"))
    }

    @Test
    fun realCodecAndSessionErrorsWithLargeNumbersAreReduced() {
        val hostileNumbers = listOf(987_654_321L, 4_242_424_242L, 123_456L)
        val rejections = listOf(
            // Unknown message type: the decoded peer value appears in the codec message.
            opackMessage(
                linkedMapOf("type" to 987_654_321L, "ssn" to 1, "psn" to 1, "asn" to 0),
            ),
            // Unsupported version: likewise. (Only a Reset treats a newer version as its maximum.)
            opackMessage(
                linkedMapOf(
                    "type" to 1, "ssn" to 1, "psn" to 1, "asn" to 0, "version" to 4_242_424_242L,
                ),
            ),
        ).map { carrier ->
            carrier to requireNotNull(runCatching { CarPlayUiSyncCodec.decode(carrier) }
                .exceptionOrNull())
        } + run {
            // Session ssn error: the peer's ssn and ours appear in the message. (A skipped psn
            // under the current ssn is no longer an error: it triggers a resynchronizing Reset.)
            val channel = CarPlayUiSyncEndpoint(
                descriptor = CarPlayUiSyncChannelDescriptor(LOCAL_DISPLAY_ID, LOCAL_VEHICLE_ID),
                mode = CarPlayUiSyncMode.PASSIVE,
                presentationEventSink = CarPlayUiSyncPresentationEventSink { },
            ).openChannel()
            channel.open()
            CarPlayUiSyncPhoneRoleSimulator(LOCAL_DISPLAY_ID, PHONE_VEHICLE_ID).handshake {
                channel.receive(it).outboundCarriers
            }
            val carrier = opackMessage(
                linkedMapOf(
                    "type" to 2, "ssn" to 123_456L, "psn" to 2, "asn" to 2,
                    "data" to linkedMapOf("type" to 7),
                ),
                displayId = LOCAL_DISPLAY_ID,
            )
            listOf(carrier to requireNotNull(runCatching { channel.receive(carrier) }
                .exceptionOrNull()))
        } + run {
            // Size limit error from the real codec with a large configured limit.
            val limits = CarPlayUiSyncLimits(
                maximumCarrierBytes = 65_536,
                maximumInnerMessageBytes = 32_768,
                maximumValueBytes = 16_384,
                maximumStringChars = 8_192,
            )
            val carrier = opackMessage(
                linkedMapOf("type" to 0, "pad" to ByteArray(70_000)),
            )
            listOf(carrier to requireNotNull(
                runCatching { CarPlayUiSyncCodec.decode(carrier, limits) }.exceptionOrNull(),
            ))
        }

        rejections.forEach { (carrier, rejection) ->
            assertTrue(rejection is CarPlayUiSyncProtocolException)
            val raw = rejection.message.orEmpty()
            assertTrue(raw, Regex("[0-9]{4,}").containsMatchIn(raw))
            val rendered = UiSyncCarrierShapeDescriber.describe(
                CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                carrier,
                rejection,
                localDisplayIds = listOf(LOCAL_DISPLAY_ID),
                localVehicleIds = listOf(LOCAL_VEHICLE_ID),
            ).render()
            val err = rendered.substringAfter(" err=")
            assertFalse(rendered, Regex("[0-9]{4,}").containsMatchIn(err))
            assertTrue(rendered, err.contains("<n>"))
            (hostileNumbers + listOf(65_536L, 70_000L)).forEach { number ->
                assertFalse(rendered, err.contains(number.toString()))
            }
            assertNoSecrets(rendered)
        }
        assertEquals(
            "CarPlayUiSyncProtocolException:\"Unknown UI Sync message type <n>\"",
            UiSyncCarrierShapeDescriber.describe(
                CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                rejections[0].first,
                rejections[0].second,
            ).render().substringAfter(" err="),
        )
        assertEquals(
            "CarPlayUiSyncProtocolException:\"UI Sync message ssn <n> does not match 1\"",
            UiSyncCarrierShapeDescriber.describe(
                CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
                rejections[2].first,
                rejections[2].second,
            ).render().substringAfter(" err="),
        )
    }

    @Test
    fun messageWordsOutsideTheFixedVocabularyAreNeverRendered() {
        val rendered = UiSyncCarrierShapeDescriber.describe(
            CarPlayUiSyncRoutingDiagnosticKind.DISPLAY_PROTOCOL_ERROR,
            ByteArray(0),
            CarPlayUiSyncProtocolException(
                "UI Sync key homeAddress has value Tokyo-123 and 12 parts; wifi psk hunter2",
            ),
        ).render()
        assertEquals(
            "kind=DISPLAY_PROTOCOL_ERROR fmt=empty size=0 shape=undecodable " +
                "err=CarPlayUiSyncProtocolException:\"UI Sync key <?> has value <?>-123 and " +
                "12 <?>\"",
            rendered,
        )
    }

    private fun opackMessage(
        message: Map<String, Any>,
        displayId: String = PHONE_DISPLAY_ID,
    ): ByteArray = OpackCodec.encode(
        linkedMapOf("displayUUID" to displayId, "data" to message),
    )

    @Test
    fun boundsUndecodableAndOversizedInputs() {
        val shape = UiSyncCarrierShapeDescriber.describe(
            CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER,
            byteArrayOf(0x01, 0x02, 0x03),
            CarPlayUiSyncProtocolException("UI Sync carrier is not a valid OPACK value"),
        )
        assertEquals(
            "kind=MALFORMED_CARRIER fmt=other size=3 shape=undecodable " +
                "err=CarPlayUiSyncProtocolException:\"UI Sync carrier is not a valid OPACK value\"",
            shape.render(),
        )

        val wide = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to PHONE_DISPLAY_ID,
                "data" to (0 until 200).associate { "k${"x".repeat(60)}$it" to it },
            ),
        )
        val bounded = UiSyncCarrierShapeDescriber.describe(
            CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER,
            wide,
            null,
        ).render()
        assertTrue(bounded.length <= UiSyncCarrierShapeDescriber.MAXIMUM_RENDERED_CHARS)
        assertTrue(bounded.contains(",+184}"))
    }

    @Test
    fun routerRejectsTheLegacyPlistCarrierButRoutesThePhoneShapedOpackReset() {
        val endpoint = CarPlayUiSyncEndpoint(
            descriptor = CarPlayUiSyncChannelDescriptor(PHONE_DISPLAY_ID, PHONE_VEHICLE_ID),
            mode = CarPlayUiSyncMode.PASSIVE,
            presentationEventSink = CarPlayUiSyncPresentationEventSink { },
        )
        val router = CarPlayUiSyncEndpointRegistry(listOf(endpoint)).openRouter()
        try {
            router.open()
            val legacy = BplistCodec.encode(
                linkedMapOf(
                    "displayUUID" to PHONE_DISPLAY_ID,
                    "data" to BplistCodec.encode(
                        linkedMapOf(
                            "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0,
                            "id" to PHONE_VEHICLE_ID, "version" to 3,
                        ),
                    ),
                ),
            )
            val rejected = router.receiveWithoutPresentationDelivery(legacy)
            assertEquals(
                listOf(CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER),
                rejected.actions.diagnostics.map { it.kind },
            )
            assertTrue(rejected.rejection is CarPlayUiSyncProtocolException)

            val accepted = router.receive(phoneReset(PHONE_DISPLAY_ID, PHONE_VEHICLE_ID))
            assertTrue(accepted.diagnostics.isEmpty())
            val ourReset = CarPlayUiSyncCodec.decode(accepted.outboundCarriers.single())
            assertEquals(CarPlayUiSyncMessageType.RESET, ourReset.message.type)
            assertEquals(
                CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
                ourReset.representation,
            )
            assertEquals(PHONE_VEHICLE_ID, ourReset.message.vehicleId)
            assertEquals(
                CarPlayUiSyncSessionState.AWAITING_RESET_SELF_INITIATED,
                router.snapshot(PHONE_DISPLAY_ID)?.state,
            )
        } finally {
            router.close()
        }
    }

    private fun assertNoSecrets(rendered: String) {
        listOf(
            "SECRET", PHONE_DISPLAY_ID, PHONE_DISPLAY_ID.lowercase(), PHONE_VEHICLE_ID,
            LOCAL_DISPLAY_ID, LOCAL_VEHICLE_ID, "private",
        ).forEach { secret ->
            assertFalse("leaked $secret in $rendered", rendered.contains(secret))
        }
        assertFalse(rendered, UUID_ANYWHERE.containsMatchIn(rendered))
        assertTrue(rendered.all { it in ' '..'~' })
    }

    private fun phoneReset(displayId: String, vehicleId: String): ByteArray = OpackCodec.encode(
        linkedMapOf(
            "displayUUID" to displayId,
            "data" to BplistCodec.encode(
                linkedMapOf(
                    "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0,
                    "id" to vehicleId, "version" to 3,
                ),
            ),
        ),
    )

    private companion object {
        const val PHONE_DISPLAY_ID = "B7E6C5A0-1111-4000-8000-000000000001"
        const val PHONE_VEHICLE_ID = "VEH-0001234"
        const val LOCAL_DISPLAY_ID = "private-display-id"
        const val LOCAL_VEHICLE_ID = "private-vehicle"
        val UUID_ANYWHERE = Regex("[0-9A-Fa-f]{8}-?[0-9A-Fa-f]{4}-?[0-9A-Fa-f]{4}")
    }
}
