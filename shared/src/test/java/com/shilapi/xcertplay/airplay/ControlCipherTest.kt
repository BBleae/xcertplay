package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCipherTest {
    @Test
    fun combinedSingleAndMultiFrameDecryptPreservesReturnedPlaintextAndPartialRemainder() {
        val client = ControlCipher(readKey = SERVER_WRITE_KEY, writeKey = SERVER_READ_KEY)
        val server = ControlCipher(readKey = SERVER_READ_KEY, writeKey = SERVER_WRITE_KEY)
        val first = client.encrypt("SETUP".toByteArray())
        val second = client.encrypt("COMMAND".toByteArray())
        val third = client.encrypt("tail".toByteArray())

        val one = server.decrypt(first)
        assertArrayEquals("SETUP".toByteArray(), one.data)
        assertTrue(one.rest.isEmpty())
        val combined = server.decrypt(second + third.copyOf(5))
        assertArrayEquals("COMMAND".toByteArray(), combined.data)
        assertArrayEquals(third.copyOf(5), combined.rest)
        assertArrayEquals("tail".toByteArray(), server.decrypt(combined.rest +
            third.copyOfRange(5, third.size)).data)
    }

    @Test
    fun laterFrameFailureDoesNotReissueConsumedNonceOrCorruptNextValidFrame() {
        val client = ControlCipher(readKey = SERVER_WRITE_KEY, writeKey = SERVER_READ_KEY)
        val server = ControlCipher(readKey = SERVER_READ_KEY, writeKey = SERVER_WRITE_KEY)
        val first = client.encrypt("first".toByteArray())
        val second = client.encrypt("second".toByteArray())
        val broken = second.copyOf().also { frame ->
            frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        }
        val third = client.encrypt("third".toByteArray())
        assertThrows(Exception::class.java) { server.decrypt(first + broken) }
        // The first frame was authenticated before the second failed; failure does not rewind
        // its nonce, and a valid frame at the next nonce remains usable by its owner.
        assertArrayEquals("secondthird".toByteArray(), server.decrypt(second + third).data)
    }

    @Test
    fun singleFrameDecryptPreservesAuthenticationBeforeLaterFrameFailure() {
        val client = ControlCipher(readKey = SERVER_WRITE_KEY, writeKey = SERVER_READ_KEY)
        val server = ControlCipher(readKey = SERVER_READ_KEY, writeKey = SERVER_WRITE_KEY)
        val first = client.encrypt("first".toByteArray())
        val tamperedSecond = client.encrypt("second".toByteArray()).also { frame ->
            frame[frame.lastIndex] = (frame.last().toInt() xor 0x01).toByte()
        }

        val authenticated = server.decryptFrame(first + tamperedSecond)
        assertNotNull(authenticated)
        val firstFrame = checkNotNull(authenticated)
        assertArrayEquals("first".toByteArray(), firstFrame.data)
        assertEquals(first.size, firstFrame.consumedBytes)
        assertThrows(Exception::class.java) {
            server.decryptFrame(first + tamperedSecond, firstFrame.consumedBytes)
        }
    }

    @Test
    fun incompleteFrameDoesNotAdvanceReceiveNonce() {
        val client = ControlCipher(readKey = SERVER_WRITE_KEY, writeKey = SERVER_READ_KEY)
        val server = ControlCipher(readKey = SERVER_READ_KEY, writeKey = SERVER_WRITE_KEY)
        val complete = client.encrypt("complete".toByteArray())

        assertNull(server.decryptFrame(complete.copyOf(complete.size - 1)))
        assertArrayEquals("complete".toByteArray(), server.decryptFrame(complete)!!.data)
    }

    @Test
    fun oversizedRecordLengthIsRejectedBeforeNonceOrBodyAdmission() {
        val client = ControlCipher(readKey = SERVER_WRITE_KEY, writeKey = SERVER_READ_KEY)
        val server = ControlCipher(readKey = SERVER_READ_KEY, writeKey = SERVER_WRITE_KEY)
        val valid = client.encrypt("valid".toByteArray())

        assertThrows(IllegalArgumentException::class.java) {
            server.decryptFrame(byteArrayOf(0x01, 0x40)) // 0x4001, even without its body.
        }
        assertArrayEquals("valid".toByteArray(), server.decryptFrame(valid)!!.data)
    }

    @Test
    fun closeWipesPrivateKeysIdempotentlyAndRejectsLaterUse() {
        val suppliedRead = ByteArray(32) { 0x31 }
        val suppliedWrite = ByteArray(32) { 0x52 }
        val cipher = ControlCipher(suppliedRead, suppliedWrite)
        val ownedRead = privateKey(cipher, "readKey")
        val ownedWrite = privateKey(cipher, "writeKey")
        assertFalse(ownedRead === suppliedRead)
        assertFalse(ownedWrite === suppliedWrite)
        cipher.encrypt("before close".toByteArray())

        cipher.close()
        cipher.close()

        assertTrue(ownedRead.all { it == 0.toByte() })
        assertTrue(ownedWrite.all { it == 0.toByte() })
        assertTrue(suppliedRead.any { it != 0.toByte() })
        assertTrue(suppliedWrite.any { it != 0.toByte() })
        assertThrows(IllegalStateException::class.java) { cipher.encrypt(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { cipher.decrypt(ByteArray(0)) }
        assertThrows(IllegalStateException::class.java) { cipher.decryptFrame(ByteArray(0)) }
    }

    private fun privateKey(cipher: ControlCipher, name: String): ByteArray =
        ControlCipher::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(cipher) as ByteArray
        }

    private companion object {
        val SERVER_READ_KEY = ByteArray(32) { 0x31 }
        val SERVER_WRITE_KEY = ByteArray(32) { 0x52 }
    }
}
