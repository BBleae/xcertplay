package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsCatalogReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayVideoOverlayCodecTest {
    private val codec = CarPlayVideoOverlayCodec()

    @Test
    fun oneDictionaryUsesTheBplist00Carrier() {
        val golden = hex(
            "62706c6973743030" +
                "d1010251611001" +
                "080b0d" +
                "0000000000000101" +
                "0000000000000003" +
                "0000000000000000" +
                "000000000000000f",
        )

        assertArrayEquals(golden, codec.encodeToPhone(linkedMapOf("a" to 1)))
        assertEquals(1L, codec.decodeFromPhone(golden).field("a"))
    }

    @Test
    fun unknownActionsAndNestedFieldsStayOpaque() {
        val rawIdentifier = "private-object-uuid"
        val input = linkedMapOf<String, Any?>(
            "type" to 9_999L,
            "objectUUID" to rawIdentifier,
            "action" to "future-action",
            "messageID" to 41L,
            "arguments" to linkedMapOf(
                "payload" to linkedMapOf(
                    "uuid" to "private-action-uuid",
                    "params" to linkedMapOf("type" to "future-user-action"),
                ),
                "opaque" to byteArrayOf(0x01, 0x02, 0x03),
            ),
        )

        val received = codec.decodeFromPhone(BplistCodec.encode(input))
        assertEquals("future-action", received.field("action"))
        assertEquals(9_999L, received.field("type"))
        assertEquals(rawIdentifier, received.field("objectUUID"))
        assertFalse(received.toString().contains(rawIdentifier))
        assertFalse(received.toString().contains("future-action"))

        val emitted = BplistCodec.decode(codec.encodeToPhone(received.fields)) as Map<*, *>
        assertEquals("future-action", emitted["action"])
        assertEquals(41L, emitted["messageID"])
        val arguments = emitted["arguments"] as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), arguments["opaque"] as ByteArray)
        val payload = arguments["payload"] as Map<*, *>
        assertEquals("future-user-action", (payload["params"] as Map<*, *>)["type"])
    }

    @Test
    fun nestedDataAndCollectionsAreDefensivelyCopied() {
        val sourceData = byteArrayOf(0x11, 0x22)
        val source = linkedMapOf<String, Any?>(
            "arguments" to linkedMapOf("assets" to listOf(sourceData)),
        )
        val wire = BplistCodec.encode(source)
        val received = codec.decodeFromPhone(wire)
        sourceData[0] = 0x33
        wire.fill(0)

        val first = received.fields
        val firstData = nestedData(first)
        firstData[0] = 0x44
        assertArrayEquals(byteArrayOf(0x11, 0x22), nestedData(received.fields))
        assertArrayEquals(byteArrayOf(0x11, 0x22), nestedData(
            mapOf("arguments" to received.field("arguments")),
        ))
        assertThrows(UnsupportedOperationException::class.java) {
            (first as MutableMap<String, Any?>)["other"] = true
        }
        assertThrows(UnsupportedOperationException::class.java) {
            ((first["arguments"] as Map<*, *>)["assets"] as MutableList<*>).clear()
        }

        val outboundData = byteArrayOf(0x55)
        val emitted = codec.encodeToPhone(mapOf("asset" to outboundData))
        outboundData[0] = 0x66
        assertArrayEquals(byteArrayOf(0x55), (BplistCodec.decode(emitted) as Map<*, *>)["asset"] as ByteArray)
    }

    @Test
    fun rejectsNonDictionaryTrailingMalformedAndOversizedBodies() {
        assertProtocolFailure { codec.decodeFromPhone(BplistCodec.encode(listOf("action"))) }
        assertProtocolFailure { codec.decodeFromPhone(BplistCodec.encode(mapOf("a" to 1)) + byteArrayOf(0)) }
        assertProtocolFailure { codec.decodeFromPhone(byteArrayOf(0x01, 0x02)) }
        assertProtocolFailure { codec.decodeFromPhone(ByteArray(codec.limits.maximumMessageBytes + 1)) }

        // A valid trailer and offset table can otherwise conceal an extra byte inside the final
        // object's slice. This is one plist with an unconsumed suffix, not a complete message.
        val compact = BplistCodec.encode(linkedMapOf("a" to 1))
        val table = readUnsigned(compact, compact.size - 8).toInt()
        val padded = ByteArray(compact.size + 1)
        compact.copyInto(padded, endIndex = table)
        compact.copyInto(padded, destinationOffset = table + 1, startIndex = table)
        writeUnsigned(padded, padded.size - 8, (table + 1).toLong())
        assertEquals(1L, (BplistCodec.decode(padded) as Map<*, *>)["a"])
        assertProtocolFailure { codec.decodeFromPhone(padded) }

        val small = CarPlayVideoOverlayCodec(
            CarPlayVideoOverlayLimits(
                maximumMessageBytes = 80,
                maximumDataBytes = 80,
                maximumStringBytes = 80,
                maximumDecodedBytes = 160,
            ),
        )
        assertProtocolFailure { small.encodeToPhone(mapOf("data" to ByteArray(60))) }
        assertProtocolFailure { small.decodeFromPhone(BplistCodec.encode(mapOf("data" to ByteArray(60)))) }
        assertProtocolFailure { codec.encodeToPhone(mapOf(1 to "not a string key")) }
    }

    @Test
    fun rejectsObjectDepthAndCycleBudgets() {
        val manyObjects = CarPlayVideoOverlayCodec(
            CarPlayVideoOverlayLimits(maximumObjects = 3),
        )
        val fields = linkedMapOf("a" to 1, "b" to 2)
        assertProtocolFailure { manyObjects.encodeToPhone(fields) }
        assertProtocolFailure { manyObjects.decodeFromPhone(BplistCodec.encode(fields)) }

        val shallow = CarPlayVideoOverlayCodec(
            CarPlayVideoOverlayLimits(maximumNestingDepth = 1),
        )
        val nested = mapOf("a" to listOf(listOf(1)))
        assertProtocolFailure { shallow.encodeToPhone(nested) }
        assertProtocolFailure { shallow.decodeFromPhone(BplistCodec.encode(nested)) }

        val cyclic = linkedMapOf<String, Any?>()
        cyclic["self"] = cyclic
        assertProtocolFailure { codec.encodeToPhone(cyclic) }
    }

    @Test
    fun outboundIntegerPreflightRejectsAmbiguousUnsigned64ButKeepsInt128() {
        val firstAmbiguous = BigInteger.ONE.shiftLeft(63)
        val lastAmbiguous = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        assertProtocolFailure { codec.encodeToPhone(mapOf("value" to firstAmbiguous)) }
        assertProtocolFailure {
            codec.encodeToPhone(mapOf("arguments" to listOf(lastAmbiguous)))
        }

        val signed64Maximum = BigInteger.valueOf(Long.MAX_VALUE)
        val afterUnsigned64 = BigInteger.ONE.shiftLeft(64)
        val belowSigned64 = BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)
        assertEquals(
            Long.MAX_VALUE,
            (BplistCodec.decode(codec.encodeToPhone(mapOf("value" to signed64Maximum))) as Map<*, *>)["value"],
        )
        listOf(afterUnsigned64, belowSigned64).forEach { value ->
            val decoded = BplistCodec.decode(codec.encodeToPhone(mapOf("value" to value))) as Map<*, *>
            assertEquals(value, decoded["value"])
        }
    }

    @Test
    fun codecConstructionDoesNotPromoteCatalogCapability() {
        CarPlayVideoOverlayCodec()
        assertEquals(
            RcsCatalogReadiness.METADATA_ONLY,
            RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI.catalogReadiness,
        )
        assertEquals(
            RcsPayloadStyle.UNCONFIRMED_OPAQUE,
            RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI.payloadStyle,
        )
        assertFalse(RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI.hasConfirmedSchema)
    }

    private fun nestedData(fields: Map<String, Any?>): ByteArray {
        val arguments = fields["arguments"] as Map<*, *>
        return (arguments["assets"] as List<*>)[0] as ByteArray
    }

    private fun assertProtocolFailure(block: () -> Unit) {
        assertThrows(CarPlayVideoOverlayProtocolException::class.java) { block() }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun readUnsigned(bytes: ByteArray, start: Int): Long {
        var value = 0L
        repeat(8) { index -> value = (value shl 8) or (bytes[start + index].toLong() and 0xffL) }
        return value
    }

    private fun writeUnsigned(bytes: ByteArray, start: Int, value: Long) {
        repeat(8) { index -> bytes[start + index] = (value ushr ((7 - index) * 8)).toByte() }
    }
}
