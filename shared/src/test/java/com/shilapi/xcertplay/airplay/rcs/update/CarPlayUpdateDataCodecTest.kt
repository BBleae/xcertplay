package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUpdateDataCodecTest {
    private val codec = CarPlayUpdateDataCodec()

    @Test
    fun headerIsDirectOpackWithIntegerVersionAndOptionalData() {
        val certificate = byteArrayOf(1, 2, 3)
        val signature = byteArrayOf(4, 5)
        val additions = byteArrayOf(6)
        val header = CarPlayUpdateDataMessage.Header(
            sessionId = SESSION_ID,
            version = 27,
            chunkCount = 2,
            certificate = certificate,
            signature = signature,
            additions = additions,
        )
        certificate[0] = 99
        signature[0] = 99
        additions[0] = 99

        val encoded = codec.encode(header)
        assertEquals(RcsMessage.comm(byteArrayOf()).messageType, encoded.messageType)
        val raw = OpackCodec.decode(encoded.body) as Map<*, *>
        assertEquals(
            setOf(
                "messageType", "sessionID", "payloadType", "version", "chunkCount",
                "certificate", "signature", "additions",
            ),
            raw.keys,
        )
        assertEquals(1L, raw["messageType"])
        assertEquals(1L, raw["payloadType"])
        assertEquals(27L, raw["version"])
        assertEquals(2L, raw["chunkCount"])
        assertArrayEquals(byteArrayOf(1, 2, 3), raw["certificate"] as ByteArray)
        assertArrayEquals(byteArrayOf(4, 5), raw["signature"] as ByteArray)
        assertArrayEquals(byteArrayOf(6), raw["additions"] as ByteArray)

        val decoded = codec.decode(encoded) as CarPlayUpdateDataMessage.Header
        assertEquals(SESSION_ID, decoded.sessionId)
        assertEquals(27L, decoded.version)
        assertEquals(2, decoded.chunkCount)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.certificate)
        decoded.certificate!![0] = 55
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.certificate)
    }

    @Test
    fun chunkAndAckUseOnlyConfirmedKeys() {
        val chunk = CarPlayUpdateDataMessage.Chunk(
            SESSION_ID,
            0,
            byteArrayOf(10, 11),
            CHECKSUM,
        )
        val chunkRaw = OpackCodec.decode(codec.encode(chunk).body) as Map<*, *>
        assertEquals(
            setOf("messageType", "sessionID", "chunkIndex", "data", "checksum"),
            chunkRaw.keys,
        )
        assertEquals(2L, chunkRaw["messageType"])
        assertEquals(0L, chunkRaw["chunkIndex"])
        assertEquals(CHECKSUM, chunkRaw["checksum"])
        val decoded = codec.decode(codec.encode(chunk)) as CarPlayUpdateDataMessage.Chunk
        assertArrayEquals(byteArrayOf(10, 11), decoded.data)
        assertEquals(CHECKSUM, decoded.checksum)

        listOf(0, 1, -7).forEach { accepted ->
            val ack = CarPlayUpdateDataMessage.Ack(accepted)
            val raw = OpackCodec.decode(codec.encode(ack).body) as Map<*, *>
            assertEquals(setOf("messageType", "accepted"), raw.keys)
            assertEquals(5L, raw["messageType"])
            assertEquals(accepted.toLong(), raw["accepted"])
            assertEquals(ack, codec.decode(codec.encode(ack)))
        }
    }

    @Test
    fun rejectsInvalidHeaderNumbersAndUnknownOrEmptyFields() {
        val valid = linkedMapOf<String, Any>(
            "messageType" to 1,
            "sessionID" to SESSION_ID,
            "payloadType" to 1,
            "version" to 27,
            "chunkCount" to 2,
        )
        listOf("27", 27.0, true, -1L).forEach { invalid ->
            assertRejected(valid + ("version" to invalid))
        }
        listOf(0, -1, Int.MAX_VALUE.toLong() + 1).forEach { invalid ->
            assertRejected(valid + ("chunkCount" to invalid))
        }
        assertRejected(valid + ("payloadType" to 2))
        assertRejected(valid + ("sessionID" to "not-a-uuid"))
        assertRejected(valid + ("certificate" to byteArrayOf()))
        assertRejected(valid + ("certificate" to "not Data"))
        assertRejected(valid + ("future" to 1))
        assertRejected(valid - "chunkCount")
        assertRejected(emptyMap<String, Any>())
        assertRejected(mapOf("messageType" to 5, "accepted" to 0, "sessionID" to SESSION_ID))
        assertRejected(mapOf("messageType" to 3))
        assertRejected(mapOf(1 to "non-string key"))
        assertThrows(CarPlayUpdateDataProtocolException::class.java) {
            codec.decode(RcsMessage(0, OpackCodec.encode(valid)))
        }
    }

    @Test
    fun rejectsInvalidChunksAndHonorsCodecLimits() {
        val valid = linkedMapOf<String, Any>(
            "messageType" to 2,
            "sessionID" to SESSION_ID,
            "chunkIndex" to 0,
            "data" to byteArrayOf(1),
        )
        assertRejected(valid + ("data" to byteArrayOf()))
        assertRejected(valid + ("data" to "not Data"))
        assertRejected(valid + ("chunkIndex" to -1))
        assertRejected(valid + ("chunkIndex" to 0.0))
        assertRejected(valid + ("checksum" to CHECKSUM.uppercase()))
        assertRejected(valid + ("checksum" to "abc"))
        assertRejected(valid + ("checksum" to null))
        assertRejected(valid + ("unknown" to true))

        val tight = CarPlayUpdateDataCodec(
            CarPlayUpdateDataLimits(maxRcsBodyBytes = 1024, maxChunkBytes = 4, maxMetadataBytes = 4),
        )
        assertThrows(CarPlayUpdateDataProtocolException::class.java) {
            tight.decodeBody(OpackCodec.encode(valid + ("data" to ByteArray(5))))
        }
        assertThrows(CarPlayUpdateDataProtocolException::class.java) {
            tight.encode(CarPlayUpdateDataMessage.Chunk(SESSION_ID, 0, ByteArray(5)))
        }
        assertThrows(CarPlayUpdateDataProtocolException::class.java) {
            tight.decodeBody(ByteArray(1025))
        }
        assertFalse(
            (codec.decode(codec.encode(CarPlayUpdateDataMessage.Chunk(SESSION_ID, 0, byteArrayOf(1))))
                as CarPlayUpdateDataMessage.Chunk).data.isEmpty(),
        )
        assertTrue(codec.encode(CarPlayUpdateDataMessage.Ack(1)).body.isNotEmpty())
    }

    private fun assertRejected(fields: Map<*, *>) {
        assertThrows(CarPlayUpdateDataProtocolException::class.java) {
            codec.decodeBody(OpackCodec.encode(fields))
        }
    }

    companion object {
        private const val SESSION_ID = "C0DE1234-5678-4ABC-9DEF-0123456789AB"
        private const val CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
