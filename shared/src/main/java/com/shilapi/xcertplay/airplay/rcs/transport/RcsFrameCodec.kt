package com.shilapi.xcertplay.airplay.rcs.transport

import com.shilapi.xcertplay.airplay.ControlCipher
import java.io.Closeable

/**
 * Stateful ChaCha20-Poly1305 stream framing used by CarPlay RCS.
 *
 * Each encoded frame is `[u16-le ciphertext length][ciphertext][16-byte tag]`. The two-byte header
 * is the AEAD associated data and each direction uses its own 64-bit little-endian counter.
 */
class RcsFrameCodec private constructor(
    readKey: ByteArray,
    writeKey: ByteArray?,
    private val maxBufferedBytes: Int,
    private val maxFramesPerCall: Int,
) : Closeable {
    // ControlCipher copies each key. Retaining another copy here would leave secret material
    // unreachable by close() even after the owning channel has retired.
    private val inbound = validatedKey(readKey, "readKey").let { ControlCipher(it, it) }
    private val outbound = writeKey?.let {
        validatedKey(it, "writeKey").let { key -> ControlCipher(key, key) }
    }

    init {
        require(maxBufferedBytes >= MIN_ENCRYPTED_FRAME_BYTES) {
            "maxBufferedBytes must be at least $MIN_ENCRYPTED_FRAME_BYTES"
        }
        require(maxFramesPerCall > 0) { "maxFramesPerCall must be positive" }
    }

    fun decrypt(bytes: ByteArray): ControlCipher.Decrypted {
        require(bytes.size <= maxBufferedBytes) {
            "RCS encrypted buffer size ${bytes.size} exceeds $maxBufferedBytes"
        }
        require(countCompleteFrames(bytes) <= maxFramesPerCall) {
            "RCS encrypted buffer contains more than $maxFramesPerCall complete frames"
        }
        return inbound.decrypt(bytes)
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val frameCount = maxOf(1L, (plaintext.size.toLong() + MAX_FRAME_PAYLOAD - 1) / MAX_FRAME_PAYLOAD)
        val encodedSize = plaintext.size.toLong() + frameCount * FRAME_OVERHEAD_BYTES
        require(frameCount <= maxFramesPerCall) {
            "RCS plaintext requires $frameCount frames, exceeding $maxFramesPerCall"
        }
        require(encodedSize <= maxBufferedBytes) {
            "RCS encrypted buffer size $encodedSize exceeds $maxBufferedBytes"
        }
        return requireNotNull(outbound) { "This RCS frame codec is receive-only" }
            .encrypt(plaintext)
    }

    override fun close() {
        inbound.close()
        outbound?.close()
    }

    companion object {
        const val KEY_BYTES = 32
        const val DEFAULT_MAX_BUFFERED_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_FRAMES_PER_CALL = 1024
        private const val MAX_FRAME_PAYLOAD = 0x4000L
        private const val FRAME_OVERHEAD_BYTES = 18L
        private const val FRAME_HEADER_BYTES = 2
        private const val FRAME_TAG_BYTES = 16
        private const val MIN_ENCRYPTED_FRAME_BYTES = 18

        fun duplex(
            readKey: ByteArray,
            writeKey: ByteArray,
            maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
            maxFramesPerCall: Int = DEFAULT_MAX_FRAMES_PER_CALL,
        ): RcsFrameCodec = RcsFrameCodec(
            readKey,
            writeKey,
            maxBufferedBytes,
            maxFramesPerCall,
        )

        /** Reuses the same framing state and key path for the receive-only iAP tunnel. */
        fun reader(
            readKey: ByteArray,
            maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
            maxFramesPerCall: Int = DEFAULT_MAX_FRAMES_PER_CALL,
        ): RcsFrameCodec = RcsFrameCodec(
            readKey,
            null,
            maxBufferedBytes,
            maxFramesPerCall,
        )

        private fun validatedKey(key: ByteArray, name: String): ByteArray {
            require(key.size == KEY_BYTES) { "$name must be $KEY_BYTES bytes" }
            return key
        }
    }

    private fun countCompleteFrames(bytes: ByteArray): Int {
        var offset = 0
        var count = 0
        while (bytes.size - offset >= FRAME_HEADER_BYTES) {
            val payloadBytes = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)
            val frameBytes = FRAME_HEADER_BYTES + payloadBytes + FRAME_TAG_BYTES
            if (bytes.size - offset < frameBytes) break
            count++
            if (count > maxFramesPerCall) return count
            offset += frameBytes
        }
        return count
    }
}
