package com.shilapi.xcertplay.airplay.mainbuffered

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApapPacketCodecTest {
    @Test
    fun uintVUsesCanonicalUnsignedBigEndianBase128() {
        assertArrayEquals(hex("00"), ApapUIntVCodec.encode(BigInteger.ZERO))
        assertArrayEquals(hex("01"), ApapUIntVCodec.encode(1L))
        assertArrayEquals(hex("7f"), ApapUIntVCodec.encode(127L))
        assertArrayEquals(hex("8100"), ApapUIntVCodec.encode(128L))
        assertArrayEquals(hex("817f"), ApapUIntVCodec.encode(255L))
        assertArrayEquals(hex("818000"), ApapUIntVCodec.encode(16_384L))

        val uint64Max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val encodedMaximum = ApapUIntVCodec.encode(uint64Max)
        assertArrayEquals(hex("81ffffffffffffffff7f"), encodedMaximum)
        assertEquals(uint64Max, ApapUIntVCodec.decode(encodedMaximum).value)
        assertEquals(encodedMaximum.size, ApapUIntVCodec.decode(encodedMaximum).nextOffset)

        val embedded = byteArrayOf(99) + ApapUIntVCodec.encode(16_384L) + byteArrayOf(88)
        val decoded = ApapUIntVCodec.decode(embedded, offset = 1, limit = embedded.lastIndex)
        assertEquals(BigInteger.valueOf(16_384L), decoded.value)
        assertEquals(4, decoded.nextOffset)
    }

    @Test
    fun uintVRejectsNonCanonicalTruncatedAndOverflowValues() {
        assertFailureContains("shortest-form") {
            ApapUIntVCodec.decode(hex("8000"))
        }
        assertFailureContains("truncated") {
            ApapUIntVCodec.decode(hex("81"))
        }
        assertFailureContains("UInt64") {
            ApapUIntVCodec.decode(hex("82ffffffffffffffff7f"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApapUIntVCodec.encode(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApapUIntVCodec.encode(BigInteger.ONE.shiftLeft(64))
        }
        assertThrows(ApapProtocolException::class.java) {
            ApapUIntVCodec.decode(byteArrayOf(1), offset = -1)
        }
    }

    @Test
    fun packetHasExactHeaderExtensionsTerminatorAndMediaLayout() {
        val packet = ApapPacket(
            header = ApapMediaHeader(
                mediaTimeValue = -2L,
                mediaTimeScale = 0x0102_0304L,
                sequence = 0x050607,
            ),
            extensions = listOf(
                ApapExtension(1L, hex("aa")),
                ApapExtension(128L, hex("bbcc")),
            ),
            mediaPayload = hex("1020"),
        )

        val encoded = ApapPacketCodec.encode(packet)
        assertArrayEquals(
            hex("fffffffffffffffe010203040506070101aa810002bbcc001020"),
            encoded,
        )

        val decoded = ApapPacketCodec.decode(encoded)
        assertEquals(-2L, decoded.header.mediaTimeValue)
        assertEquals(0x0102_0304L, decoded.header.mediaTimeScale)
        assertEquals(0x050607, decoded.header.sequence)
        assertEquals(2, decoded.extensions.size)
        assertEquals(BigInteger.ONE, decoded.extensions[0].id)
        assertArrayEquals(hex("aa"), decoded.extensions[0].data)
        assertEquals(BigInteger.valueOf(128L), decoded.extensions[1].id)
        assertArrayEquals(hex("bbcc"), decoded.extensions[1].data)
        assertArrayEquals(hex("1020"), decoded.mediaPayload)
        assertArrayEquals(encoded, ApapPacketCodec.encode(decoded))
    }

    @Test
    fun packetAndExtensionsDefensivelyCopyMutableBytes() {
        val extensionBytes = byteArrayOf(1, 2)
        val mediaBytes = byteArrayOf(3, 4)
        val extension = ApapExtension(7L, extensionBytes)
        val packet = ApapPacket(
            ApapMediaHeader(1L, 2L, 3),
            listOf(extension),
            mediaBytes,
        )
        extensionBytes.fill(99)
        mediaBytes.fill(99)

        assertArrayEquals(byteArrayOf(1, 2), packet.extensions.single().data)
        assertArrayEquals(byteArrayOf(3, 4), packet.mediaPayload)
        packet.extensions.single().data.fill(88)
        packet.mediaPayload.fill(88)
        assertArrayEquals(byteArrayOf(1, 2), packet.extensions.single().data)
        assertArrayEquals(byteArrayOf(3, 4), packet.mediaPayload)
    }

    @Test
    fun packetDecoderRejectsMissingTerminatorMalformedExtensionsAndBounds() {
        val header = ByteArray(ApapPacketCodec.HEADER_BYTES)
        assertFailureContains("truncated UIntV") {
            ApapPacketCodec.decode(header + hex("01"))
        }
        assertFailureContains("shortest-form") {
            ApapPacketCodec.decode(header + hex("8000"))
        }
        assertFailureContains("truncated APAP extension") {
            ApapPacketCodec.decode(header + hex("01030102"))
        }
        assertFailureContains("extension count") {
            ApapPacketCodec.decode(
                header + hex("0100020000"),
                ApapCodecLimits(
                    maxPacketBytes = 64,
                    maxExtensions = 1,
                    maxExtensionBytes = 64,
                    maxTotalExtensionBytes = 64,
                ),
            )
        }
        assertFailureContains("extension data") {
            ApapPacketCodec.decode(
                header + hex("0102010200"),
                ApapCodecLimits(
                    maxPacketBytes = 64,
                    maxExtensionBytes = 1,
                    maxTotalExtensionBytes = 1,
                ),
            )
        }
    }

    @Test
    fun packetConstructionBoundsAHostileEndlessIterableWithoutReadingSize() {
        val endless = Iterable {
            object : Iterator<ApapExtension> {
                override fun hasNext(): Boolean = true
                override fun next(): ApapExtension = ApapExtension(1L, ByteArray(0))
            }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            ApapPacket(ApapMediaHeader(0L, 0L, 0), endless, ByteArray(0))
        }
        assertTrue(failure.message!!.contains("hard limit"))
    }

    @Test
    fun tcpPrefixIncludesItselfAndRequiresOneExactFrame() {
        val payload = hex("010203")
        val framed = BufferedApapTcpCodec.encode(payload)
        assertArrayEquals(hex("00000007010203"), framed)
        assertArrayEquals(payload, BufferedApapTcpCodec.decodeExact(framed))

        assertFailureContains("does not exactly match") {
            BufferedApapTcpCodec.decodeExact(hex("00000008010203"))
        }
        assertFailureContains("does not exactly match") {
            BufferedApapTcpCodec.decodeExact(hex("00000006010203"))
        }
        assertFailureContains("smaller") {
            BufferedApapTcpCodec.decodeExact(hex("00000003"))
        }
        assertFailureContains("exceeds 64") {
            BufferedApapTcpCodec.decodeExact(hex("ffffffff"), maxFrameBytes = 64)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BufferedApapTcpCodec.encode(ByteArray(61), maxFrameBytes = 64)
        }
    }

    private fun assertFailureContains(text: String, block: () -> Unit) {
        val failure = assertThrows(ApapProtocolException::class.java, block)
        assertTrue("${failure.message} does not contain $text", failure.message!!.contains(text))
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
