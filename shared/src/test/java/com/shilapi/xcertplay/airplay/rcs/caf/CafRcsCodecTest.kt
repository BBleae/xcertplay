package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CafRcsCodecTest {
    @Test
    fun carrierIsBinaryPlistAroundTwoOpackLayers() {
        val frame = CarAccessoryMessages.configRequest(7, 42)
        val wrapper = BplistCodec.decode(frame.rcsBody) as Map<*, *>
        val params = wrapper["params"] as Map<*, *>
        val outer = OpackCodec.decode(params["data"] as ByteArray) as Map<*, *>
        val inner = OpackCodec.decode(outer["pluginData"] as ByteArray) as Map<*, *>

        assertEquals(7L, outer["pluginID"])
        assertEquals("configRequest", inner["command"])
        assertEquals(42L, inner["transactionID"])
    }

    @Test
    fun uint64IdentifiersAndOpaqueSignedErrorsRoundTrip() {
        val maximumUInt64Bits = -1L
        val frame = CarAccessoryMessages.readResponse(
            pluginId = maximumUInt64Bits,
            transactionId = Long.MAX_VALUE,
            values = mapOf(maximumUInt64Bits to 12L),
            errors = mapOf(4L to Long.MIN_VALUE),
        )
        val reader = CarAccessoryMessages.reader(frame.rcsBody)

        assertEquals("18446744073709551615", CafUInt64.toString(reader.pluginId))
        assertEquals(12L, reader.valuesMap()[maximumUInt64Bits])
        assertEquals(Long.MIN_VALUE, reader.errors()[4L])
        assertEquals(
            BigInteger("18446744073709551615"),
            CafUInt64.toBigInteger(maximumUInt64Bits),
        )
    }

    @Test
    fun wildcardAndUnknownOemFieldsArePreserved() {
        val codec = CafRcsCodec()
        val envelope = CafEnvelope(
            pluginId = 9,
            extensions = mapOf("oemOuter" to 1L),
            message = CafMessage(
                command = CafCommand.REGISTER_REQUEST,
                transactionId = 2,
                values = "*",
                extensions = mapOf("oemInner" to "kept"),
            ),
        )
        val decoded = codec.decodeEnvelope(codec.encodeEnvelope(envelope).rcsBody)

        assertEquals("*", CafMessageReader(decoded).values())
        assertEquals(1L, decoded.extensions["oemOuter"])
        assertEquals("kept", decoded.message.extensions["oemInner"])
    }

    @Test
    fun directOpackCarrierRoundTripsWithoutBinaryPlistWrapper() {
        val codec = CafRcsCodec()
        val envelope = CafEnvelope(
            pluginId = 9,
            message = CafMessage(CafCommand.CONFIG_REQUEST, transactionId = 2),
        )
        val direct = codec.encodeEnvelope(envelope, CafCarrierStyle.DIRECT_OPACK)
        assertFalse(direct.rcsBody.copyOfRange(0, 8).contentEquals("bplist00".toByteArray()))

        val decoded = codec.decodeCarrier(direct.rcsBody)
        assertEquals(CafCarrierStyle.DIRECT_OPACK, decoded.style)
        assertEquals(envelope, decoded.envelope)
        assertEquals(
            CafCarrierStyle.BINARY_PLIST_OPACK,
            codec.decodeCarrier(codec.encodeEnvelope(envelope).rcsBody).style,
        )
    }

    @Test
    fun malformedShapesAndOversizedPayloadsAreRejected() {
        assertThrows(CafProtocolException::class.java) {
            CafRcsCodec.encode(
                CafEnvelope(
                    1,
                    CafMessage(CafCommand.WRITE_RESPONSE, transactionId = 2),
                ),
            )
        }

        val tiny = CafRcsCodec(
            CafLimits(
                maxRcsBodyBytes = 256,
                maxOuterOpackBytes = 128,
                maxPluginDataBytes = 64,
                maxIidsPerMessage = 8,
            ),
        )
        val failure = assertThrows(CafProtocolException::class.java) {
            tiny.encodeEnvelope(
                CafEnvelope(
                    1,
                    CafMessage(
                        CafCommand.CONTROL_NOTIFY,
                        values = mapOf(3L to "x".repeat(512)),
                    ),
                ),
            )
        }
        assertTrue(failure.message!!.contains("encode") || failure.message!!.contains("exceeds"))
    }

    @Test
    fun customIidLimitAppliesToRegistrationReadAndUnregisterLists() {
        val bounded = CafRcsCodec(CafLimits(maxIidsPerMessage = 2))
        val unrestricted = CafRcsCodec()
        val accepted = listOf(1L, -1L)
        val oversized = accepted + 3L

        for (command in listOf(
            CafCommand.REGISTER_REQUEST,
            CafCommand.READ_REQUEST,
            CafCommand.UNREGISTER_REQUEST,
        )) {
            val valid = CafEnvelope(1, CafMessage(command, transactionId = 2, values = accepted))
            assertEquals(accepted, CafMessageReader(bounded.decodeEnvelope(
                bounded.encodeEnvelope(valid).rcsBody,
            )).valuesList())

            val invalid = valid.copy(message = valid.message.copy(values = oversized))
            val encodeFailure = assertThrows(CafProtocolException::class.java) {
                bounded.encodeEnvelope(invalid)
            }
            assertTrue(encodeFailure.message!!.contains("maximum is 2"))

            val decodeFailure = assertThrows(CafDecodeException::class.java) {
                bounded.decodeEnvelope(unrestricted.encodeEnvelope(invalid).rcsBody)
            }
            assertEquals(CafDecodeStage.MESSAGE_SHAPE, decodeFailure.stage)
            assertTrue(decodeFailure.message!!.contains("maximum is 2"))
        }
    }

    @Test
    fun customIidLimitRejectsValueMapsBeforeScanningKeys() {
        val bounded = CafRcsCodec(CafLimits(maxIidsPerMessage = 2))
        val unrestricted = CafRcsCodec()
        val valid = CafEnvelope(
            1,
            CafMessage(
                CafCommand.WRITE_REQUEST,
                transactionId = 2,
                values = linkedMapOf(1L to "first", -1L to "last"),
            ),
        )
        assertEquals(
            mapOf(1L to "first", -1L to "last"),
            CafMessageReader(bounded.decodeEnvelope(bounded.encodeEnvelope(valid).rcsBody)).valuesMap(),
        )

        val oversized = valid.copy(
            message = valid.message.copy(values = linkedMapOf<Any, Any?>(
                "not an IID" to 1L,
                2L to 2L,
                3L to 3L,
            )),
        )
        val encodeFailure = assertThrows(CafProtocolException::class.java) {
            bounded.encodeEnvelope(oversized)
        }
        assertTrue(encodeFailure.message!!.contains("maximum is 2"))

        val decodableOversized = valid.copy(
            message = valid.message.copy(values = mapOf(1L to 1L, 2L to 2L, 3L to 3L)),
        )
        val decodeFailure = assertThrows(CafDecodeException::class.java) {
            bounded.decodeEnvelope(unrestricted.encodeEnvelope(decodableOversized).rcsBody)
        }
        assertEquals(CafDecodeStage.MESSAGE_SHAPE, decodeFailure.stage)
        assertTrue(decodeFailure.message!!.contains("maximum is 2"))
    }

    @Test
    fun rejectedCarrierReportsOnlyItsDecodeStage() {
        val codec = CafRcsCodec()
        val nonPlist = assertThrows(CafDecodeException::class.java) {
            codec.decodeEnvelope(ByteArray(70))
        }
        assertEquals(CafDecodeStage.OUTER_OPACK, nonPlist.stage)

        val wrongParams = BplistCodec.encode(
            linkedMapOf("params" to linkedMapOf("data" to "not NSData")),
        )
        val paramsFailure = assertThrows(CafDecodeException::class.java) {
            codec.decodeEnvelope(wrongParams)
        }
        assertEquals(CafDecodeStage.PARAMS, paramsFailure.stage)

        val wrongOpack = BplistCodec.encode(
            linkedMapOf("params" to linkedMapOf("data" to byteArrayOf(0))),
        )
        val opackFailure = assertThrows(CafDecodeException::class.java) {
            codec.decodeEnvelope(wrongOpack)
        }
        assertEquals(CafDecodeStage.OUTER_OPACK, opackFailure.stage)
    }
}
