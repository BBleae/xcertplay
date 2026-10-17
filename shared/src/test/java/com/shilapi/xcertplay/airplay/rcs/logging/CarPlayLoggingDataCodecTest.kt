package com.shilapi.xcertplay.airplay.rcs.logging

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayLoggingDataCodecTest {
    private val limits = CarPlayLoggingDataLimits(
        maxRcsBodyBytes = 1024,
        maxChunkBytes = 128,
        maxStatisticsDataBytes = 256,
        maxArchiveBytes = 512,
        maxChunkCount = 4,
    )
    private val codec = CarPlayLoggingDataCodec(limits)
    private val sessionId = "3a0c178c-d2ce-42cb-ac7d-81ee39c1ed42"

    @Test
    fun roundTripsConfirmedDirectionsAndWireFields() {
        assertEquals(
            linkedMapOf("messageType" to 3L, "request" to 1L),
            body(codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive)),
        )
        assertEquals(
            CarPlayLoggingDataMessage.RequestArchive,
            codec.decodeFromPhone(codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive)),
        )

        val interval = 0x1_0000_0000uL
        assertEquals(
            CarPlayLoggingDataMessage.RequestStatistics(interval),
            codec.decodeFromPhone(
                codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestStatistics(interval)),
            ),
        )
        assertEquals(
            linkedMapOf("messageType" to 5L, "accepted" to 5L),
            body(codec.encodeFromPhone(CarPlayLoggingDataMessage.Ack(5))),
        )

        val header = codec.decodeToPhone(codec.encodeToPhone(
            CarPlayLoggingDataMessage.ArchiveHeader(sessionId, 2),
        )) as CarPlayLoggingDataMessage.ArchiveHeader
        assertEquals(sessionId, header.sessionId)
        assertEquals(2, header.chunkCount)
        assertEquals(
            linkedMapOf(
                "messageType" to 1L,
                "payloadType" to 2L,
                "sessionID" to sessionId,
                "chunkCount" to 2L,
            ),
            body(codec.encodeToPhone(header)),
        )

        val bytes = byteArrayOf(1, 2, 3)
        val chunk = codec.decodeToPhone(codec.encodeToPhone(
            CarPlayLoggingDataMessage.ArchiveChunk(sessionId, 1, bytes),
        )) as CarPlayLoggingDataMessage.ArchiveChunk
        assertEquals(1, chunk.chunkIndex)
        assertArrayEquals(bytes, chunk.data)
        bytes[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), chunk.data)

        val opaque = linkedMapOf<Any?, Any?>(
            "unknownMetric" to listOf(1L, false, null),
            7L to byteArrayOf(4, 5),
        )
        val statistics = codec.decodeToPhone(codec.encodeToPhone(
            CarPlayLoggingDataMessage.Statistics(opaque),
        )) as CarPlayLoggingDataMessage.Statistics
        assertEquals(listOf(1L, false, null), statistics.data["unknownMetric"])
        assertArrayEquals(byteArrayOf(4, 5), statistics.data[7L] as ByteArray)
    }

    @Test
    fun preservesHighBitUnsignedIntervalAcrossSignedOpackCarrier() {
        val highBit = 0x8000_0000_0000_0000uL
        val encoded = codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestStatistics(highBit))
        assertEquals(Long.MIN_VALUE, body(encoded)["interval"])
        assertEquals(
            CarPlayLoggingDataMessage.RequestStatistics(highBit),
            codec.decodeFromPhone(encoded),
        )

        val maximum = ULong.MAX_VALUE
        assertEquals(
            CarPlayLoggingDataMessage.RequestStatistics(maximum),
            codec.decodeFromPhone(
                codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestStatistics(maximum)),
            ),
        )
        val eightByteMaximum = OpackCodec.encode(linkedMapOf(
            "messageType" to 3L,
            "request" to 2L,
            "interval" to Long.MIN_VALUE,
        ))
        assertEquals(0x33, eightByteMaximum[eightByteMaximum.size - 9].toInt() and 0xff)
        eightByteMaximum.fill(0xff.toByte(), eightByteMaximum.size - 8)
        assertEquals(
            CarPlayLoggingDataMessage.RequestStatistics(maximum),
            codec.decodeFromPhone(RcsMessage.comm(eightByteMaximum)),
        )
    }

    @Test
    fun rejectsWrongDirectionNonCommAndMalformedOpack() {
        val header = codec.encodeToPhone(CarPlayLoggingDataMessage.ArchiveHeader(sessionId, 1))
        val request = codec.encodeFromPhone(CarPlayLoggingDataMessage.RequestArchive)
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(header)
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeToPhone(request)
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(RcsMessage(999, request.body))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(RcsMessage.comm(request.body + 0x08))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(RcsMessage.comm(byteArrayOf()))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(RcsMessage.comm(OpackCodec.encode(listOf(3L, 1L))))
        }
    }

    @Test
    fun rejectsUnknownMissingAndWronglyTypedEnvelopeFields() {
        val malformed = listOf(
            mapOf("messageType" to 3, "request" to 1, "extra" to 1),
            mapOf("messageType" to 3, "request" to 2),
            mapOf("messageType" to 3, "request" to 1, "interval" to 0),
            mapOf("messageType" to 3, "request" to 2, "interval" to "1"),
            mapOf("messageType" to 3, "request" to 7),
            mapOf("messageType" to "3", "request" to 1),
            mapOf("messageType" to 5, "accepted" to 0, "sessionID" to sessionId),
            mapOf("messageType" to 5, "accepted" to 1),
            mapOf("messageType" to 1, "payloadType" to 1, "sessionID" to sessionId, "chunkCount" to 1),
            mapOf("messageType" to 4, "payloadType" to 3, "data" to byteArrayOf(1)),
            mapOf("messageType" to 4, "payloadType" to 2, "data" to emptyMap<String, Any>()),
        )
        malformed.forEach { fields ->
            assertThrows("fields=$fields", CarPlayLoggingDataProtocolException::class.java) {
                codec.decodeFromPhone(RcsMessage.comm(OpackCodec.encode(fields)))
            }
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(RcsMessage.comm(OpackCodec.encode(mapOf(1 to 3, "request" to 1))))
        }
    }

    @Test
    fun enforcesArchiveBoundsWithoutTruncatingUnsignedInterval() {
        assertEquals(
            CarPlayLoggingDataMessage.RequestStatistics(0x1_0000_0000uL),
            codec.decodeFromPhone(raw(
                "messageType" to 3, "request" to 2, "interval" to 0x1_0000_0000L,
            )),
        )
        assertEquals(
            CarPlayLoggingDataMessage.RequestStatistics(ULong.MAX_VALUE),
            codec.decodeFromPhone(raw("messageType" to 3, "request" to 2, "interval" to -1L)),
        )
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeFromPhone(CarPlayLoggingDataMessage.Ack(1))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.ArchiveHeader(sessionId, 5))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeToPhone(raw(
                "messageType" to 1, "payloadType" to 2, "sessionID" to sessionId,
                "chunkCount" to 5,
            ))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.ArchiveChunk(sessionId, 4, byteArrayOf(1)))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.ArchiveChunk(sessionId, 0, byteArrayOf()))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.ArchiveChunk(sessionId, 0, ByteArray(129)))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.ArchiveHeader("bad\u0000id", 1))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeToPhone(raw(
                "messageType" to 2, "sessionID" to "", "chunkIndex" to 0,
                "data" to byteArrayOf(1),
            ))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.decodeFromPhone(RcsMessage.comm(ByteArray(1025)))
        }
    }

    @Test
    fun statisticsDictionaryIsOpaqueButBounded() {
        val allowed = CarPlayLoggingDataMessage.Statistics(mapOf("metric" to mapOf("nested" to 1.5)))
        assertEquals(1.5, (codec.decodeToPhone(codec.encodeToPhone(allowed)) as
            CarPlayLoggingDataMessage.Statistics).data.let { (it["metric"] as Map<*, *>)["nested"] })

        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.Statistics(mapOf("huge" to ByteArray(257))))
        }
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.Statistics(mapOf("x" to Any())))
        }
        val cyclic = mutableMapOf<String, Any?>()
        cyclic["itself"] = cyclic
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.Statistics(cyclic))
        }
        var tooDeep: Any? = 1L
        repeat(20) { tooDeep = listOf(tooDeep) }
        assertTrue(tooDeep is List<*>)
        assertThrows(CarPlayLoggingDataProtocolException::class.java) {
            codec.encodeToPhone(CarPlayLoggingDataMessage.Statistics(mapOf("deep" to tooDeep)))
        }
    }

    private fun raw(vararg fields: Pair<String, Any?>): RcsMessage =
        RcsMessage.comm(OpackCodec.encode(linkedMapOf(*fields)))

    private fun body(message: RcsMessage): Map<*, *> = OpackCodec.decode(message.body) as Map<*, *>
}
