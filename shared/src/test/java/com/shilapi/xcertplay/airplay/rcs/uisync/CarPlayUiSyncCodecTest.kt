package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUiSyncCodecTest {
    @Test
    fun keepsProtocolIdsDistinctAndTreatsDisplayIdAsOpaqueExactText() {
        val descriptor = CarPlayUiSyncChannelDescriptor(
            displayId = DISPLAY_ID,
            vehicleId = VEHICLE_ID,
        )

        assertEquals(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid, descriptor.clusterControlRcsUuid)
        assertEquals(
            UUID.fromString("07D9F906-8D64-4B54-A808-A20BCA2C51C2"),
            descriptor.clusterControlRcsUuid,
        )
        assertEquals(
            UUID.fromString("7DED4F02-38A6-4CBA-8731-4B9BA40CB044"),
            descriptor.uiSyncChannelUuid,
        )
        assertEquals(DISPLAY_ID, descriptor.displayId)
        assertEquals(VEHICLE_ID, descriptor.vehicleId)

        val encoded = CarPlayUiSyncCodec.encode(resetCarrier(CarPlayUiSyncProtocolVersion.V3))
        assertEquals(DISPLAY_ID, CarPlayUiSyncCodec.peekDisplayId(encoded))
        assertEquals(DISPLAY_ID, CarPlayUiSyncCodec.decode(encoded).displayId)
    }

    @Test
    fun v1AndV2SerializeMessageAndPayloadWhileV3UsesDictionaries() {
        CarPlayUiSyncProtocolVersion.entries.forEach { version ->
            val encoded = CarPlayUiSyncCodec.encode(commandCarrier(version))
            assertEquals(0xe2, encoded[0].toInt() and 0xff)
            val outer = OpackCodec.decode(encoded) as Map<*, *>
            val inner = if (version == CarPlayUiSyncProtocolVersion.V3) {
                assertTrue(outer["data"] is Map<*, *>)
                outer["data"] as Map<*, *>
            } else {
                assertTrue(outer["data"] is ByteArray)
                BplistCodec.decode(outer["data"] as ByteArray) as Map<*, *>
            }
            val command = if (version == CarPlayUiSyncProtocolVersion.V3) {
                assertTrue(inner["data"] is Map<*, *>)
                inner["data"] as Map<*, *>
            } else {
                assertTrue(inner["data"] is ByteArray)
                BplistCodec.decode(inner["data"] as ByteArray) as Map<*, *>
            }

            assertEquals(5L, (command["type"] as Number).toLong())
            assertEquals(mapOf("layout" to "default"), command["data"])
            val decoded = CarPlayUiSyncCodec.decode(
                encoded,
                expectedVersion = version.takeIf { it != CarPlayUiSyncProtocolVersion.V3 },
            )
            assertEquals(version, decoded.serializationVersion)
            assertEquals(DISPLAY_ID, decoded.displayId)
        }
    }

    @Test
    fun resetCompleteUsesIos27NestedConfigPayloadForEveryVersion() {
        CarPlayUiSyncProtocolVersion.entries.forEach { version ->
            val message = CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.RESET_COMPLETE,
                sessionSequenceNumber = 1,
                packetSequenceNumber = 1,
                acknowledgementSequenceNumber = 1,
                payload = CarPlayUiSyncResetCompletePayload(
                    config = linkedMapOf("test" to true),
                ),
                vehicleId = VEHICLE_ID,
                version = version,
            )
            val decoded = CarPlayUiSyncCodec.decode(
                CarPlayUiSyncCodec.encode(CarPlayUiSyncCarrier(DISPLAY_ID, version, message)),
            )
            val payload = decoded.message.payload as CarPlayUiSyncResetCompletePayload

            assertEquals(mapOf("test" to true), payload.config)
            assertEquals(VEHICLE_ID, decoded.message.vehicleId)
        }
    }

    @Test
    fun canonicalEncodingOmitsFalseAndAbsentOptionalFields() {
        val message = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.COMMAND,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 2,
            acknowledgementSequenceNumber = 1,
            payload = CarPlayUiSyncCommandEnvelope(
                CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue,
            ),
        )
        val encoded = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(DISPLAY_ID, CarPlayUiSyncProtocolVersion.V3, message),
        )
        val outer = OpackCodec.decode(encoded) as Map<*, *>
        val inner = outer["data"] as Map<*, *>
        val command = inner["data"] as Map<*, *>

        assertFalse(inner.containsKey("noAck"))
        assertFalse(inner.containsKey("id"))
        assertFalse(inner.containsKey("version"))
        assertEquals(setOf("type"), command.keys)

        val decoded = CarPlayUiSyncCodec.decode(encoded)
        assertFalse(decoded.message.noAck)
        assertNull(decoded.message.vehicleId)
        assertNull(decoded.message.version)
        assertNull((decoded.message.payload as CarPlayUiSyncCommandEnvelope).data)
    }

    @Test
    fun acceptsExplicitFalseNoAckOnCommandAck() {
        val encoded = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to linkedMapOf(
                    "type" to 3,
                    "ssn" to 1,
                    "psn" to 2,
                    "asn" to 2,
                    "noAck" to false,
                ),
            ),
        )

        val acknowledgement = CarPlayUiSyncCodec.decode(encoded).message
        assertEquals(CarPlayUiSyncMessageType.COMMAND_ACK, acknowledgement.type)
        assertFalse(acknowledgement.noAck)
    }

    @Test
    fun commandCatalogMatchesIos27Codes() {
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25),
            CarPlayUiSyncCommandType.entries.map { it.wireValue },
        )
        CarPlayUiSyncCommandType.entries.forEach { command ->
            assertEquals(command, CarPlayUiSyncCommandType.fromWireValue(command.wireValue))
        }
        assertNull(CarPlayUiSyncCommandType.fromWireValue(15))
        assertNull(CarPlayUiSyncCommandType.fromWireValue(26))
    }

    @Test
    fun roundTripsBoundedUnknownMessageAndPayloadExtensions() {
        val raw = byteArrayOf(1, 2, 3)
        val unknown = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.COMMAND,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 2,
            acknowledgementSequenceNumber = 1,
            payload = CarPlayUiSyncCommandEnvelope(
                type = 31337,
                data = linkedMapOf("blob" to raw, "items" to listOf("a", 7L)),
                extensions = mapOf("futureCommandFlag" to "kept"),
            ),
            extensions = mapOf("futureMessageFlag" to true),
        )
        val encoded = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(DISPLAY_ID, CarPlayUiSyncProtocolVersion.V3, unknown),
        )
        raw.fill(99)

        val decoded = CarPlayUiSyncCodec.decode(encoded).message
        val command = decoded.payload as CarPlayUiSyncCommandEnvelope
        assertEquals(31337, command.type)
        assertNull(command.knownType)
        assertEquals(true, decoded.extensions["futureMessageFlag"])
        assertEquals("kept", command.extensions["futureCommandFlag"])
        val values = command.data as Map<*, *>
        assertTrue((values["blob"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf("a", 7L), values["items"])
    }

    @Test
    fun decodesIndependentPlistlibConformanceVectorsAndCorrectedHashes() {
        val fixtures = fixtureProperties()
        listOf(
            Triple(
                "reset-v1",
                CarPlayUiSyncProtocolVersion.V1,
                "4dbc5a6cb25b25e4a167a416f647f602c6d906701912bd81facf6a0526a26ef3",
            ),
            Triple(
                "reset-v2",
                CarPlayUiSyncProtocolVersion.V2,
                "8a2ad6b55b31daeac0b6082dc72b6a91f3730e671ed03922d45774d51f78df18",
            ),
            Triple(
                "reset-v3",
                CarPlayUiSyncProtocolVersion.V3,
                "c76032a0e2f6f0535bfd69835452cf18d441f259aa95abf06243be57de2f18f6",
            ),
        ).forEach { (name, version, expectedSha256) ->
            val bytes = fixtureBytes(fixtures, name)
            assertEquals(expectedSha256, fixtures.getProperty("$name.sha256"))
            assertEquals(expectedSha256, sha256(bytes))
            val reset = decodeFixture(fixtures, name, version)
            assertEquals(CarPlayUiSyncMessageType.RESET, reset.message.type)
            assertEquals(version, reset.serializationVersion)
            assertEquals(VEHICLE_ID, reset.message.vehicleId)
            assertNull(reset.message.payload)
        }
        assertGoldenResetComplete(
            fixtures,
            name = "reset-complete-v2",
            version = CarPlayUiSyncProtocolVersion.V2,
            expectedSha256 = "31f0ac25f48e04e81f226eaf5d69f600c477cdcb60409e0673508f08b7716113",
        )
        assertGoldenResetComplete(
            fixtures,
            name = "reset-complete-v3",
            version = CarPlayUiSyncProtocolVersion.V3,
            expectedSha256 = "1b602dd2255dcb1d9fb4ec173bc035c40fbfe6363189d37f0ea13407edd80e48",
        )

        listOf(
            "command-v2" to CarPlayUiSyncProtocolVersion.V2,
            "command-v3" to CarPlayUiSyncProtocolVersion.V3,
        ).forEach { (name, version) ->
            val decoded = decodeFixture(fixtures, name, version)
            val command = decoded.message.payload as CarPlayUiSyncCommandEnvelope
            assertEquals(CarPlayUiSyncCommandType.REQUEST_LAYOUT, command.knownType)
            assertEquals(mapOf("layout" to "default"), command.data)
        }

        val acknowledgement = decodeFixture(
            fixtures,
            "ack-v3",
            CarPlayUiSyncProtocolVersion.V3,
        ).message
        assertEquals(CarPlayUiSyncMessageType.COMMAND_ACK, acknowledgement.type)
        assertFalse(acknowledgement.noAck)
        assertNull(acknowledgement.payload)

        val explicitFalseAcknowledgement = decodeFixture(
            fixtures,
            "ack-false-v3",
            CarPlayUiSyncProtocolVersion.V3,
        ).message
        assertEquals(CarPlayUiSyncMessageType.COMMAND_ACK, explicitFalseAcknowledgement.type)
        assertFalse(explicitFalseAcknowledgement.noAck)
        assertNull(explicitFalseAcknowledgement.payload)
    }

    @Test
    fun encoderMatchesIndependentPlistlibConformanceSemanticTrees() {
        val fixtures = fixtureProperties()
        val resetComplete = { version: CarPlayUiSyncProtocolVersion ->
            CarPlayUiSyncCarrier(
                DISPLAY_ID,
                version,
                CarPlayUiSyncMessage(
                    type = CarPlayUiSyncMessageType.RESET_COMPLETE,
                    sessionSequenceNumber = 1,
                    packetSequenceNumber = 1,
                    acknowledgementSequenceNumber = 1,
                    payload = CarPlayUiSyncResetCompletePayload(
                        config = linkedMapOf("test" to true),
                    ),
                    vehicleId = VEHICLE_ID,
                    version = version,
                ),
            )
        }
        val acknowledgement = CarPlayUiSyncCarrier(
            DISPLAY_ID,
            CarPlayUiSyncProtocolVersion.V3,
            CarPlayUiSyncMessage(
                type = CarPlayUiSyncMessageType.COMMAND_ACK,
                sessionSequenceNumber = 1,
                packetSequenceNumber = 2,
                acknowledgementSequenceNumber = 2,
            ),
        )
        val carriers = linkedMapOf(
            "reset-v1" to resetCarrier(CarPlayUiSyncProtocolVersion.V1),
            "reset-v2" to resetCarrier(CarPlayUiSyncProtocolVersion.V2),
            "reset-v3" to resetCarrier(CarPlayUiSyncProtocolVersion.V3),
            "reset-complete-v2" to resetComplete(CarPlayUiSyncProtocolVersion.V2),
            "reset-complete-v3" to resetComplete(CarPlayUiSyncProtocolVersion.V3),
            "command-v2" to commandCarrier(CarPlayUiSyncProtocolVersion.V2),
            "command-v3" to commandCarrier(CarPlayUiSyncProtocolVersion.V3),
            "ack-v3" to acknowledgement,
        )

        carriers.forEach { (name, carrier) ->
            assertSemanticWireValue(
                expected = BplistCodec.decode(fixtureBytes(fixtures, name)),
                actual = OpackCodec.decode(CarPlayUiSyncCodec.encode(carrier)),
                path = name,
            )
        }
    }

    @Test
    fun acceptsPreNegotiationSerializedV3ButRejectsV2DictionaryAndAmbiguousMessage() {
        // iOS 27 sends its self-initiated Reset before negotiation as plist Data declaring v3.
        val v3AsSerializedData = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to BplistCodec.encode(resetWireMap(version = 3)),
            ),
        )
        val preNegotiationReset = CarPlayUiSyncCodec.decode(v3AsSerializedData)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, preNegotiationReset.serializationVersion)
        assertEquals(
            CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST,
            preNegotiationReset.representation,
        )
        assertEquals(CarPlayUiSyncMessageType.RESET, preNegotiationReset.message.type)

        val v2AsDictionary = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to resetWireMap(version = 2),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(v2AsDictionary)
        }

        val commandPayload = linkedMapOf<String, Any>("type" to 5)
        val command = linkedMapOf<String, Any>(
            "type" to 2,
            "ssn" to 1,
            "psn" to 2,
            "asn" to 1,
            "data" to BplistCodec.encode(commandPayload),
        )
        val ambiguous = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to BplistCodec.encode(command),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(ambiguous)
        }
        assertEquals(
            CarPlayUiSyncProtocolVersion.V2,
            CarPlayUiSyncCodec.decode(
                ambiguous,
                expectedVersion = CarPlayUiSyncProtocolVersion.V2,
            ).serializationVersion,
        )
    }

    @Test
    fun enforcesCarrierIdentifierPayloadAndDecodedValueBounds() {
        val small = CarPlayUiSyncLimits(
            maximumCarrierBytes = 4096,
            maximumInnerMessageBytes = 2048,
            maximumValueBytes = 64,
            maximumStringChars = 32,
            maximumIdentifierChars = 16,
            maximumCollectionEntries = 8,
            maximumNestingDepth = 4,
            maximumPendingAcknowledgements = 2,
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.encode(commandCarrier(
                CarPlayUiSyncProtocolVersion.V3,
                data = ByteArray(65) { 7 },
            ), small)
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.encode(commandCarrier(
                CarPlayUiSyncProtocolVersion.V3,
                data = listOf(listOf(listOf(listOf(listOf("deep"))))),
            ), small)
        }
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.peekDisplayId(
                CarPlayUiSyncCodec.encode(resetCarrier(CarPlayUiSyncProtocolVersion.V3)),
                small,
            )
        }
    }

    @Test
    fun rejectsMalformedCarrierAndProtocolFields() {
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(byteArrayOf(1, 2, 3))
        }
        val missingDisplay = OpackCodec.encode(
            linkedMapOf("data" to BplistCodec.encode(resetWireMap(2))),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(missingDisplay)
        }
        val unknownMessageType = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to BplistCodec.encode(
                    resetWireMap(2).toMutableMap().apply { put("type", 99) },
                ),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(unknownMessageType)
        }
    }

    @Test
    fun decodesHypothesizedIos27PhoneFirstResetAsOpackCarrierWithBinaryPlistMessage() {
        // DashBoard didOpen -> reset: negotiated version is still nil, so the message is binary
        // plist Data (sendPayload:) while declaring version 3, wrapped by OPACKEncoderCreateData.
        val innerPlist = BplistCodec.encode(
            linkedMapOf(
                "type" to 0,
                "ssn" to 1,
                "psn" to 1,
                "asn" to 0,
                "id" to PHONE_VEHICLE_ID,
                "version" to 3,
            ),
        )
        assertTrue(innerPlist.copyOfRange(0, 8).contentEquals("bplist00".toByteArray()))
        val carrier = OpackCodec.encode(
            linkedMapOf("displayUUID" to PHONE_DISPLAY_ID, "data" to innerPlist),
        )
        // E2 | 4B "displayUUID" | 61 24 <36 chars> | 44 "data" | 91 NN <bplist00...>
        assertEquals(0xe2, carrier[0].toInt() and 0xff)
        assertEquals(0x4b, carrier[1].toInt() and 0xff)
        assertEquals("displayUUID", String(carrier, 2, 11, Charsets.US_ASCII))
        assertEquals(0x61, carrier[13].toInt() and 0xff)
        assertEquals(36, carrier[14].toInt() and 0xff)
        assertEquals(0x44, carrier[51].toInt() and 0xff)
        assertEquals("data", String(carrier, 52, 4, Charsets.US_ASCII))
        assertEquals(0x91, carrier[56].toInt() and 0xff)
        assertEquals(innerPlist.size, carrier[57].toInt() and 0xff)
        assertEquals(58 + innerPlist.size, carrier.size)

        assertEquals(PHONE_DISPLAY_ID, CarPlayUiSyncCodec.peekDisplayId(carrier))
        val decoded = CarPlayUiSyncCodec.decode(carrier)
        assertEquals(PHONE_DISPLAY_ID, decoded.displayId)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, decoded.serializationVersion)
        assertEquals(CarPlayUiSyncCarrierRepresentation.SERIALIZED_PLIST, decoded.representation)
        assertEquals(CarPlayUiSyncMessageType.RESET, decoded.message.type)
        assertEquals(1L, decoded.message.sessionSequenceNumber)
        assertEquals(1L, decoded.message.packetSequenceNumber)
        assertEquals(0L, decoded.message.acknowledgementSequenceNumber)
        assertEquals(PHONE_VEHICLE_ID, decoded.message.vehicleId)
        assertEquals(CarPlayUiSyncProtocolVersion.V3, decoded.message.version)
        assertFalse(decoded.message.noAck)
        assertNull(decoded.message.payload)

        // Dictionary key order from NSDictionary enumeration is unspecified.
        val reordered = OpackCodec.encode(
            linkedMapOf("data" to innerPlist, "displayUUID" to PHONE_DISPLAY_ID),
        )
        assertEquals(decoded, CarPlayUiSyncCodec.decode(reordered))
    }

    @Test
    fun encodesOpackCarrierMatchingDbUiSyncChannelLayout() {
        val encoded = CarPlayUiSyncCodec.encode(
            CarPlayUiSyncCarrier(
                PHONE_DISPLAY_ID,
                CarPlayUiSyncProtocolVersion.V2,
                CarPlayUiSyncMessage(
                    type = CarPlayUiSyncMessageType.RESET,
                    sessionSequenceNumber = 1,
                    packetSequenceNumber = 1,
                    acknowledgementSequenceNumber = 0,
                    vehicleId = PHONE_VEHICLE_ID,
                    version = CarPlayUiSyncProtocolVersion.V2,
                ),
            ),
        )
        assertEquals(0xe2, encoded[0].toInt() and 0xff)
        val outer = OpackCodec.decode(encoded) as Map<*, *>
        assertEquals(listOf("displayUUID", "data"), outer.keys.toList())
        assertEquals(PHONE_DISPLAY_ID, outer["displayUUID"])
        val inner = BplistCodec.decode(outer["data"] as ByteArray) as Map<*, *>
        assertEquals(setOf("type", "ssn", "psn", "asn", "id", "version"), inner.keys)
        assertEquals(2L, (inner["version"] as Number).toLong())
    }

    @Test
    fun rejectsLegacyPlistOuterExtraOuterKeysAndNullV3Values() {
        val legacyPlistOuter = BplistCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to BplistCodec.encode(resetWireMap(2)),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.peekDisplayId(legacyPlistOuter)
        }
        val paramsWrapper = OpackCodec.encode(
            linkedMapOf(
                "params" to linkedMapOf(
                    "data" to OpackCodec.encode(
                        linkedMapOf("displayUUID" to DISPLAY_ID, "data" to resetWireMap(3)),
                    ),
                ),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.peekDisplayId(paramsWrapper)
        }
        val extraKey = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to resetWireMap(3),
                "private-extra" to true,
            ),
        )
        val failure = assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.peekDisplayId(extraKey)
        }
        assertFalse(failure.message.orEmpty().contains("private"))
        val uuidTypedDisplay = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to UUID.fromString(PHONE_DISPLAY_ID),
                "data" to resetWireMap(3),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.peekDisplayId(uuidTypedDisplay)
        }
        val nullInV3 = OpackCodec.encode(
            linkedMapOf(
                "displayUUID" to DISPLAY_ID,
                "data" to linkedMapOf<String, Any?>(
                    "type" to 3, "ssn" to 1, "psn" to 2, "asn" to 2, "id" to null,
                ),
            ),
        )
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.decode(nullInV3)
        }
        val trailing = CarPlayUiSyncCodec.encode(resetCarrier(CarPlayUiSyncProtocolVersion.V3)) +
            byteArrayOf(0)
        assertThrows(CarPlayUiSyncProtocolException::class.java) {
            CarPlayUiSyncCodec.peekDisplayId(trailing)
        }
    }

    private fun assertGoldenResetComplete(
        fixtures: Properties,
        name: String,
        version: CarPlayUiSyncProtocolVersion,
        expectedSha256: String,
    ) {
        val bytes = fixtureBytes(fixtures, name)
        assertEquals(expectedSha256, fixtures.getProperty("$name.sha256"))
        assertEquals(expectedSha256, sha256(bytes))
        val decoded = CarPlayUiSyncCodec.decode(opackCarrier(bytes))
        assertEquals(version, decoded.serializationVersion)
        assertEquals(DISPLAY_ID, decoded.displayId)
        assertEquals(VEHICLE_ID, decoded.message.vehicleId)
        val payload = decoded.message.payload as CarPlayUiSyncResetCompletePayload
        assertEquals(mapOf("test" to true), payload.config)
    }

    private fun decodeFixture(
        fixtures: Properties,
        name: String,
        version: CarPlayUiSyncProtocolVersion,
    ): CarPlayUiSyncCarrier = CarPlayUiSyncCodec.decode(
        opackCarrier(fixtureBytes(fixtures, name)),
        expectedVersion = version.takeIf { it != CarPlayUiSyncProtocolVersion.V3 },
    )

    /**
     * The plistlib vectors pin the message layer. DBUISyncChannel carries `{displayUUID, data}`
     * as OPACK, so rewrap the fixture's outer dictionary (keeping inner Data bytes verbatim).
     */
    private fun opackCarrier(plistCarrier: ByteArray): ByteArray =
        OpackCodec.encode(BplistCodec.decode(plistCarrier))

    private fun fixtureBytes(fixtures: Properties, name: String): ByteArray =
        Base64.getDecoder().decode(fixtures.getProperty("$name.base64"))

    private fun fixtureProperties(): Properties = Properties().apply {
        val resource = requireNotNull(
            CarPlayUiSyncCodecTest::class.java.getResourceAsStream(
                "/uisync/ios27-conformance-vectors.properties",
            ),
        )
        resource.use { load(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun assertSemanticWireValue(expected: Any?, actual: Any?, path: String) {
        when {
            expected is ByteArray && actual is ByteArray -> {
                val expectedPlist = expected.startsWithBplistMagic()
                val actualPlist = actual.startsWithBplistMagic()
                assertEquals("$path serialized-plist representation", expectedPlist, actualPlist)
                if (expectedPlist) {
                    assertSemanticWireValue(
                        BplistCodec.decode(expected),
                        BplistCodec.decode(actual),
                        "$path.data",
                    )
                } else {
                    assertArrayEquals("$path bytes", expected, actual)
                }
            }
            expected is Map<*, *> && actual is Map<*, *> -> {
                assertEquals("$path keys", expected.keys, actual.keys)
                expected.forEach { (key, value) ->
                    assertSemanticWireValue(value, actual[key], "$path.$key")
                }
            }
            expected is List<*> && actual is List<*> -> {
                assertEquals("$path list size", expected.size, actual.size)
                expected.indices.forEach { index ->
                    assertSemanticWireValue(expected[index], actual[index], "$path[$index]")
                }
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    private fun ByteArray.startsWithBplistMagic(): Boolean =
        size >= 8 && copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))

    private fun resetCarrier(version: CarPlayUiSyncProtocolVersion) = CarPlayUiSyncCarrier(
        displayId = DISPLAY_ID,
        serializationVersion = version,
        message = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.RESET,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 1,
            acknowledgementSequenceNumber = 0,
            vehicleId = VEHICLE_ID,
            version = version,
        ),
    )

    private fun commandCarrier(
        version: CarPlayUiSyncProtocolVersion,
        data: Any = linkedMapOf("layout" to "default"),
    ) = CarPlayUiSyncCarrier(
        displayId = DISPLAY_ID,
        serializationVersion = version,
        message = CarPlayUiSyncMessage(
            type = CarPlayUiSyncMessageType.COMMAND,
            sessionSequenceNumber = 1,
            packetSequenceNumber = 2,
            acknowledgementSequenceNumber = 1,
            payload = CarPlayUiSyncCommandEnvelope(5, data),
        ),
    )

    private fun resetWireMap(version: Int): LinkedHashMap<String, Any> = linkedMapOf(
        "type" to 0,
        "ssn" to 1,
        "psn" to 1,
        "asn" to 0,
        "id" to VEHICLE_ID,
        "version" to version,
    )

    private companion object {
        const val DISPLAY_ID = "instrument-cluster"
        const val VEHICLE_ID = "vehicle-test"
        const val PHONE_DISPLAY_ID = "B7E6C5A0-1111-4000-8000-000000000001"
        const val PHONE_VEHICLE_ID = "VEH-0001234"
    }
}
