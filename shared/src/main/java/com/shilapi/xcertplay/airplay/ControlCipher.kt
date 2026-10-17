package com.shilapi.xcertplay.airplay

import java.io.Closeable

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
/** Keeps private key copies; [close] zeroes them after all users have retired. */
class ControlCipher(readKey: ByteArray, writeKey: ByteArray) : Closeable {
    private val readKey = readKey.copyOf()
    private val writeKey = writeKey.copyOf()
    data class Decrypted(val data: ByteArray, val rest: ByteArray)
    data class DecryptedFrame(val data: ByteArray, val consumedBytes: Int)

    private var readCounter = 0L
    private var writeCounter = 0L
    private var closed = false

    @Synchronized
    fun decrypt(buffer: ByteArray): Decrypted {
        check(!closed) { "Control cipher is closed" }
        val output = ArrayList<ByteArray>()
        try {
            var offset = 0
            while (true) {
                val frame = decryptFrame(buffer, offset) ?: break
                try {
                    output.add(frame.data)
                } catch (failure: Throwable) {
                    frame.data.fill(0)
                    throw failure
                }
                offset += frame.consumedBytes
            }
            return Decrypted(
                concatBytes(*output.toTypedArray()),
                buffer.copyOfRange(offset, buffer.size),
            )
        } finally {
            // concatBytes transfers into a distinct returned array. The individual decrypted
            // frame buffers remain private to this call, including on a later-frame failure.
            output.forEach { it.fill(0) }
        }
    }

    /**
     * Authenticates and consumes at most one complete frame.
     *
     * Keeping this boundary observable is security-sensitive: callers must be able to remember
     * that nonce N was already authenticated even when a later frame from the same TCP read is
     * malformed. Returning null means only that the next frame is incomplete; it never advances
     * the receive counter.
     */
    @Synchronized
    fun decryptFrame(buffer: ByteArray, offset: Int = 0): DecryptedFrame? {
        check(!closed) { "Control cipher is closed" }
        require(offset in 0..buffer.size) { "offset is outside the encrypted buffer" }
        if (buffer.size - offset < HEADER_SIZE) return null
        val length = readU16Le(buffer, offset)
        require(length <= MAX_PAYLOAD) {
            "Encrypted control frame declares $length bytes, above $MAX_PAYLOAD"
        }
        val frameBytes = HEADER_SIZE + length + TAG_SIZE
        if (buffer.size - offset < frameBytes) return null
        val frameEnd = offset + frameBytes
        val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
        val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
        val plaintext = AirPlayCrypto.chachaOpen(
            readKey,
            AirPlayCrypto.nonce64(readCounter),
            ciphertextAndTag,
            aad,
        )
        readCounter++
        return DecryptedFrame(plaintext, frameBytes)
    }

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        check(!closed) { "Control cipher is closed" }
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        readKey.fill(0)
        writeKey.fill(0)
        readCounter = 0
        writeCounter = 0
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
