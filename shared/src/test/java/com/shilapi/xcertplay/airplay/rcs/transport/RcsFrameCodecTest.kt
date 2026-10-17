package com.shilapi.xcertplay.airplay.rcs.transport

import com.shilapi.xcertplay.airplay.ControlCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RcsFrameCodecTest {
    @Test
    fun encryptedFrameUsesLengthHeaderAndDirectionalCounters() {
        val serverReadKey = ByteArray(32) { 1 }
        val serverWriteKey = ByteArray(32) { 2 }
        val sender = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        val receiver = RcsFrameCodec.duplex(serverReadKey, serverWriteKey)
        val plaintext = byteArrayOf(1, 2, 3, 4)

        val first = sender.encrypt(plaintext)
        assertEquals(plaintext.size + 18, first.size)
        assertEquals(
            plaintext.size,
            (first[0].toInt() and 0xff) or ((first[1].toInt() and 0xff) shl 8),
        )
        assertArrayEquals(plaintext, receiver.decrypt(first).data)

        val second = sender.encrypt(byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), receiver.decrypt(second).data)
    }

    @Test
    fun rejectsTamperingWithoutAdvancingReceiveCounter() {
        val readKey = ByteArray(32) { 3 }
        val writeKey = ByteArray(32) { 4 }
        val sender = RcsFrameCodec.duplex(writeKey, readKey)
        val receiver = RcsFrameCodec.duplex(readKey, writeKey)
        val encoded = sender.encrypt(byteArrayOf(7, 8, 9))
        val tampered = encoded.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertThrows(Exception::class.java) {
            receiver.decrypt(tampered)
        }
        assertArrayEquals(byteArrayOf(7, 8, 9), receiver.decrypt(encoded).data)
    }

    @Test
    fun authenticatesLengthHeaderAndAllowsRetryAfterRejection() {
        val readKey = ByteArray(32) { 11 }
        val writeKey = ByteArray(32) { 12 }
        val sender = RcsFrameCodec.duplex(writeKey, readKey)
        val receiver = RcsFrameCodec.duplex(readKey, writeKey)
        val encoded = sender.encrypt(byteArrayOf(1, 2, 3))
        val tamperedHeader = encoded.copyOf().also { it[0] = 2 }

        assertThrows(Exception::class.java) {
            receiver.decrypt(tamperedHeader)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), receiver.decrypt(encoded).data)
    }

    @Test
    fun copiesKeysAtConstruction() {
        val readKey = ByteArray(32) { 5 }
        val writeKey = ByteArray(32) { 6 }
        val sender = RcsFrameCodec.duplex(writeKey, readKey)
        val receiver = RcsFrameCodec.duplex(readKey, writeKey)

        readKey.fill(99)
        writeKey.fill(88)

        val plaintext = byteArrayOf(10, 11)
        assertArrayEquals(plaintext, receiver.decrypt(sender.encrypt(plaintext)).data)
    }

    @Test
    fun closeWipesBothDirectionalCiphersWithoutMutatingCallerKeys() {
        val readKey = ByteArray(32) { 5 }
        val writeKey = ByteArray(32) { 6 }
        val codec = RcsFrameCodec.duplex(readKey, writeKey)
        val inbound = privateCipher(codec, "inbound")
        val outbound = privateCipher(codec, "outbound")

        codec.close()
        codec.close()

        listOf(inbound, outbound).forEach { cipher ->
            assertTrue(privateKey(cipher, "readKey").all { it == 0.toByte() })
            assertTrue(privateKey(cipher, "writeKey").all { it == 0.toByte() })
        }
        assertTrue(readKey.all { it == 5.toByte() })
        assertTrue(writeKey.all { it == 6.toByte() })
        assertThrows(IllegalStateException::class.java) { codec.encrypt(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { codec.decrypt(ByteArray(0)) }
    }

    private fun privateCipher(codec: RcsFrameCodec, name: String): ControlCipher =
        RcsFrameCodec::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(codec) as ControlCipher
        }

    private fun privateKey(cipher: ControlCipher, name: String): ByteArray =
        ControlCipher::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(cipher) as ByteArray
        }

    @Test
    fun validatesKeysWriteModeAndWireBufferLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            RcsFrameCodec.duplex(ByteArray(31), ByteArray(32))
        }

        val reader = RcsFrameCodec.reader(ByteArray(32))
        assertThrows(IllegalArgumentException::class.java) {
            reader.encrypt(byteArrayOf(1))
        }

        val bounded = RcsFrameCodec.duplex(
            ByteArray(32),
            ByteArray(32),
            maxBufferedBytes = 18,
        )
        assertEquals(18, bounded.encrypt(ByteArray(0)).size)
        assertThrows(IllegalArgumentException::class.java) {
            bounded.encrypt(byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            bounded.decrypt(ByteArray(19))
        }

        val sender = RcsFrameCodec.duplex(ByteArray(32), ByteArray(32))
        val frameBounded = RcsFrameCodec.duplex(
            ByteArray(32),
            ByteArray(32),
            maxFramesPerCall = 1,
        )
        val twoFrames = sender.encrypt(ByteArray(0)) + sender.encrypt(ByteArray(0))
        assertThrows(IllegalArgumentException::class.java) {
            frameBounded.decrypt(twoFrames)
        }
    }
}
