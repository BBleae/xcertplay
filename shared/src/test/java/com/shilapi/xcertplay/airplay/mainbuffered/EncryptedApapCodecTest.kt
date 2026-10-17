package com.shilapi.xcertplay.airplay.mainbuffered

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedApapCodecTest {
    @Test
    fun matchesIndependentChaCha20Poly1305ApapVector() {
        val key = ByteArray(32) { it.toByte() }
        val plaintext = hex("010203040506070811223344a1b2c30101aa00010203")
        val writer = EncryptedApapWriter(
            key = key,
            initialNonceCounter = 0x0706_0504_0302_0100L,
        )

        val encrypted = writer.encrypt(plaintext)

        assertArrayEquals(
            hex(
                "010203040506070811223344a1b2c3" +
                    "3901219a27be36a03a90692b885a4d87e39a89bb528651" +
                    "0001020304050607",
            ),
            encrypted,
        )
        val reader = EncryptedApapReader(
            key = key,
            initialNonceCounter = 0x0706_0504_0302_0100L,
        )
        assertArrayEquals(plaintext, reader.decrypt(encrypted))
        writer.close()
        reader.close()
    }

    @Test
    fun clearHeaderIsFifteenBytesButOnlyFirstTwelveAreAad() {
        val plaintext = samplePlaintext()
        val writer = EncryptedApapWriter(ByteArray(32) { it.toByte() })
        val encrypted = writer.encrypt(plaintext)

        assertArrayEquals(
            plaintext.copyOfRange(0, EncryptedApapPacketCodec.CLEAR_HEADER_BYTES),
            encrypted.copyOfRange(0, EncryptedApapPacketCodec.CLEAR_HEADER_BYTES),
        )

        val sequenceChanged = encrypted.copyOf().also { it[12] = (it[12].toInt() xor 0x40).toByte() }
        val sequenceReader = EncryptedApapReader(ByteArray(32) { it.toByte() })
        val sequencePlaintext = sequenceReader.decrypt(sequenceChanged)
        assertEquals(
            (plaintext[12].toInt() and 0xff) xor 0x40,
            sequencePlaintext[12].toInt() and 0xff,
        )

        val aadChanged = encrypted.copyOf().also { it[11] = (it[11].toInt() xor 0x40).toByte() }
        val aadReader = EncryptedApapReader(ByteArray(32) { it.toByte() })
        assertThrows(ApapAuthenticationException::class.java) {
            aadReader.decrypt(aadChanged)
        }
        assertTrue(aadReader.isTerminal)
    }

    @Test
    fun nonceTrailerIsLittleEndianAndMonotonicallyIncremented() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val writer = EncryptedApapWriter(key)
        val first = writer.encrypt(samplePlaintext())
        val second = writer.encrypt(samplePlaintext())

        assertArrayEquals(ByteArray(8), first.copyOfRange(first.size - 8, first.size))
        assertArrayEquals(
            byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0),
            second.copyOfRange(second.size - 8, second.size),
        )

        val reader = EncryptedApapReader(key)
        assertArrayEquals(samplePlaintext(), reader.decrypt(first))
        assertArrayEquals(samplePlaintext(), reader.decrypt(second))
    }

    @Test
    fun providerFailureMakesWriterTerminalSoAnUncertainNonceIsNeverRetried() {
        val cryptor = FailsFirstSealCryptor()
        val writer = EncryptedApapWriter(ByteArray(32), cryptor = cryptor)
        assertThrows(IllegalStateException::class.java) {
            writer.encrypt(samplePlaintext())
        }
        assertTrue(writer.isTerminal)
        assertTrue(writer.keyIsZeroizedForTest())
        assertTrue(cryptor.nonce.all { it == 0.toByte() })
        assertTrue(cryptor.aad.all { it == 0.toByte() })
        assertTrue(cryptor.plaintext.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { writer.encrypt(samplePlaintext()) }
    }

    @Test
    fun authenticationFailureIsTerminalAndZeroizesReaderKey() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val writer = EncryptedApapWriter(key)
        val corrupted = writer.encrypt(samplePlaintext()).also {
            it[EncryptedApapPacketCodec.CLEAR_HEADER_BYTES] =
                (it[EncryptedApapPacketCodec.CLEAR_HEADER_BYTES].toInt() xor 1).toByte()
        }
        val reader = EncryptedApapReader(key)

        assertThrows(ApapAuthenticationException::class.java) {
            reader.decrypt(corrupted)
        }
        assertTrue(reader.isTerminal)
        assertTrue(reader.keyIsZeroizedForTest())
        assertThrows(IllegalStateException::class.java) {
            reader.decrypt(corrupted)
        }
    }

    @Test
    fun nonceMismatchIsTerminalAndPreventsReplayOrSkipping() {
        val key = ByteArray(32) { it.toByte() }
        val writer = EncryptedApapWriter(key, initialNonceCounter = 1L)
        val nonceOne = writer.encrypt(samplePlaintext())
        val reader = EncryptedApapReader(key, initialNonceCounter = 0L)

        assertThrows(ApapNonceSequenceException::class.java) {
            reader.decrypt(nonceOne)
        }
        assertTrue(reader.isTerminal)
        assertTrue(reader.keyIsZeroizedForTest())
    }

    @Test
    fun nonceMaximumCanBeUsedOnceAndNeverWraps() {
        val writer = EncryptedApapWriter(ByteArray(32), initialNonceCounter = -1L)
        val encrypted = writer.encrypt(samplePlaintext())
        assertArrayEquals(ByteArray(8) { 0xff.toByte() }, encrypted.takeLastBytes(8))
        assertTrue(writer.isTerminal)
        assertTrue(writer.keyIsZeroizedForTest())
        assertThrows(IllegalStateException::class.java) {
            writer.encrypt(samplePlaintext())
        }

        val reader = EncryptedApapReader(ByteArray(32), initialNonceCounter = -1L)
        assertArrayEquals(samplePlaintext(), reader.decrypt(encrypted))
        assertTrue(reader.isTerminal)
        assertTrue(reader.keyIsZeroizedForTest())
        assertThrows(IllegalStateException::class.java) {
            reader.decrypt(encrypted)
        }
    }

    @Test
    fun keyInputIsCopiedAndCloseZeroizesAndDisablesDirections() {
        val originalKey = ByteArray(32) { (it + 4).toByte() }
        val stableKey = originalKey.copyOf()
        val writer = EncryptedApapWriter(originalKey)
        val reader = EncryptedApapReader(stableKey)
        originalKey.fill(99)

        val encrypted = writer.encrypt(samplePlaintext())
        assertArrayEquals(samplePlaintext(), reader.decrypt(encrypted))

        writer.close()
        reader.close()
        writer.close()
        reader.close()
        assertTrue(writer.isClosed)
        assertTrue(reader.isClosed)
        assertTrue(writer.keyIsZeroizedForTest())
        assertTrue(reader.keyIsZeroizedForTest())
        assertThrows(IllegalStateException::class.java) { writer.encrypt(samplePlaintext()) }
        assertThrows(IllegalStateException::class.java) { reader.decrypt(encrypted) }
    }

    @Test
    fun validatesKeysAndEncryptedPacketBoundsBeforeCrypto() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedApapWriter(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedApapReader(ByteArray(33))
        }
        val writer = EncryptedApapWriter(ByteArray(32), maxEncryptedBytes = 64)
        assertThrows(ApapProtocolException::class.java) {
            writer.encrypt(ByteArray(41))
        }
        val reader = EncryptedApapReader(ByteArray(32), maxEncryptedBytes = 64)
        assertThrows(ApapProtocolException::class.java) {
            reader.decrypt(ByteArray(39))
        }
        assertFalse(reader.isTerminal)
    }

    @Test
    fun providerContractFailureIsTerminalAndZeroizesReaderKey() {
        val key = ByteArray(32) { it.toByte() }
        val encrypted = EncryptedApapWriter(key).encrypt(samplePlaintext())
        val reader = EncryptedApapReader(key, cryptor = WrongLengthOpenCryptor)

        assertThrows(IllegalStateException::class.java) {
            reader.decrypt(encrypted)
        }
        assertTrue(reader.isTerminal)
        assertTrue(reader.keyIsZeroizedForTest())
        assertThrows(IllegalStateException::class.java) {
            reader.decrypt(encrypted)
        }
    }

    @Test
    fun malformedPlaintextPacketIsTerminalWithinTheDirectionalLock() {
        val key = ByteArray(32) { (it + 9).toByte() }
        val malformedPlaintext = ByteArray(ApapPacketCodec.HEADER_BYTES) + byteArrayOf(0x81.toByte())
        val encrypted = EncryptedApapWriter(key).encrypt(malformedPlaintext)
        val reader = EncryptedApapReader(key)

        assertThrows(ApapProtocolException::class.java) {
            reader.decryptPacket(encrypted)
        }
        assertTrue(reader.isTerminal)
        assertTrue(reader.keyIsZeroizedForTest())
        assertThrows(IllegalStateException::class.java) {
            reader.decryptPacket(encrypted)
        }
    }

    @Test
    fun typedPacketsRoundTripThroughDirectionalCodecs() {
        val packet = ApapPacket(
            ApapMediaHeader(Long.MIN_VALUE, 48_000L, 0x00ff_fffe),
            listOf(ApapExtension(42L, byteArrayOf(9, 8, 7))),
            byteArrayOf(6, 5, 4),
        )
        val writer = EncryptedApapWriter(ByteArray(32) { (255 - it).toByte() })
        val reader = EncryptedApapReader(ByteArray(32) { (255 - it).toByte() })

        val decoded = reader.decryptPacket(writer.encrypt(packet))

        assertEquals(packet.header, decoded.header)
        assertEquals(BigIntegerValue.FORTY_TWO, decoded.extensions.single().id)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded.extensions.single().data)
        assertArrayEquals(byteArrayOf(6, 5, 4), decoded.mediaPayload)
    }

    private class FailsFirstSealCryptor : ApapAeadCryptor {
        private var shouldFail = true
        lateinit var nonce: ByteArray
        lateinit var plaintext: ByteArray
        lateinit var aad: ByteArray

        override fun seal(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray,
        ): ByteArray {
            this.nonce = nonce
            this.plaintext = plaintext
            this.aad = aad
            if (shouldFail) {
                shouldFail = false
                throw IllegalStateException("injected provider failure")
            }
            return BouncyCastleApapAeadCryptor.seal(key, nonce, plaintext, aad)
        }

        override fun open(
            key: ByteArray,
            nonce: ByteArray,
            ciphertextAndTag: ByteArray,
            aad: ByteArray,
        ): ByteArray = BouncyCastleApapAeadCryptor.open(key, nonce, ciphertextAndTag, aad)
    }

    private object WrongLengthOpenCryptor : ApapAeadCryptor {
        override fun seal(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray,
        ): ByteArray = BouncyCastleApapAeadCryptor.seal(key, nonce, plaintext, aad)

        override fun open(
            key: ByteArray,
            nonce: ByteArray,
            ciphertextAndTag: ByteArray,
            aad: ByteArray,
        ): ByteArray = ByteArray(0)
    }

    private object BigIntegerValue {
        val FORTY_TWO = java.math.BigInteger.valueOf(42L)
    }

    private fun samplePlaintext(): ByteArray =
        hex("010203040506070811223344a1b2c30101aa00010203")

    private fun ByteArray.takeLastBytes(count: Int): ByteArray =
        copyOfRange(size - count, size)

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
