package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import java.io.Closeable
import org.bouncycastle.crypto.InvalidCipherTextException

/** Authentication failed for an encrypted APAP packet. */
class ApapAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/** The explicit APAP nonce trailer did not match the next expected UInt64 counter. */
class ApapNonceSequenceException(message: String) : SecurityException(message)

/** The final UInt64 nonce was consumed successfully; this direction cannot safely continue. */
class ApapNonceExhaustedException : SecurityException("APAP nonce space is exhausted")

/**
 * Provider boundary for APAP ChaCha20-Poly1305.
 *
 * Implementations must behave atomically: returning means the operation succeeded; throwing means
 * no output escaped. Implementations must not retain the key, nonce, AAD, or payload arrays.
 */
interface ApapAeadCryptor {
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    @Throws(ApapAuthenticationException::class)
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray
}

/** BouncyCastle-backed cryptor already available on every supported project target. */
object BouncyCastleApapAeadCryptor : ApapAeadCryptor {
    override fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)

    override fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray = try {
        AirPlayCrypto.chachaOpen(key, nonce, ciphertextAndTag, aad)
    } catch (failure: InvalidCipherTextException) {
        throw ApapAuthenticationException("APAP authentication failed", failure)
    }
}

/**
 * Stateless layout operations shared by the stateful directional session codecs below.
 *
 * This codec accepts an already-authorized 32-byte key. It intentionally does not derive a key,
 * open a socket, advertise `mainBuffered`, or constitute product-readiness evidence.
 */
object EncryptedApapPacketCodec {
    const val KEY_BYTES = 32
    const val CLEAR_HEADER_BYTES = 15
    const val AAD_BYTES = 12
    const val TAG_BYTES = 16
    const val NONCE_TRAILER_BYTES = 8
    const val OVERHEAD_BYTES = TAG_BYTES + NONCE_TRAILER_BYTES
    const val MIN_PLAINTEXT_BYTES = ApapPacketCodec.MIN_PACKET_BYTES
    const val MIN_ENCRYPTED_BYTES = MIN_PLAINTEXT_BYTES + OVERHEAD_BYTES
    const val DEFAULT_MAX_ENCRYPTED_BYTES = 4 * 1024 * 1024

    internal fun seal(
        key: ByteArray,
        nonceCounter: Long,
        plaintext: ByteArray,
        cryptor: ApapAeadCryptor,
        maxEncryptedBytes: Int,
    ): ByteArray {
        requireKey(key)
        requireMaximum(maxEncryptedBytes)
        if (plaintext.size < MIN_PLAINTEXT_BYTES) {
            throw ApapProtocolException(
                "APAP plaintext is shorter than $MIN_PLAINTEXT_BYTES bytes",
            )
        }
        val outputBytes = plaintext.size.toLong() + OVERHEAD_BYTES
        if (outputBytes > maxEncryptedBytes) {
            throw ApapProtocolException("encrypted APAP packet exceeds $maxEncryptedBytes bytes")
        }

        val nonce = nonce12(nonceCounter)
        val aad = plaintext.copyOfRange(0, AAD_BYTES)
        val plaintextTail = plaintext.copyOfRange(CLEAR_HEADER_BYTES, plaintext.size)
        var sealed: ByteArray? = null
        try {
            sealed = cryptor.seal(key, nonce, plaintextTail, aad)
            val sealedBytes = requireNotNull(sealed)
            val expectedSealedBytes = plaintextTail.size + TAG_BYTES
            if (sealedBytes.size != expectedSealedBytes) {
                throw IllegalStateException(
                    "APAP AEAD returned ${sealedBytes.size} bytes, expected $expectedSealedBytes",
                )
            }
            val output = ByteArray(outputBytes.toInt())
            plaintext.copyInto(output, 0, 0, CLEAR_HEADER_BYTES)
            sealedBytes.copyInto(output, CLEAR_HEADER_BYTES)
            putU64Le(output, output.size - NONCE_TRAILER_BYTES, nonceCounter)
            return output
        } finally {
            nonce.fill(0)
            aad.fill(0)
            plaintextTail.fill(0)
            sealed?.fill(0)
        }
    }

    internal fun open(
        key: ByteArray,
        expectedNonceCounter: Long,
        encrypted: ByteArray,
        cryptor: ApapAeadCryptor,
        maxEncryptedBytes: Int,
    ): ByteArray {
        requireKey(key)
        requireMaximum(maxEncryptedBytes)
        if (encrypted.size < MIN_ENCRYPTED_BYTES) {
            throw ApapProtocolException(
                "encrypted APAP packet is shorter than $MIN_ENCRYPTED_BYTES bytes",
            )
        }
        if (encrypted.size > maxEncryptedBytes) {
            throw ApapProtocolException("encrypted APAP packet exceeds $maxEncryptedBytes bytes")
        }
        val nonceOffset = encrypted.size - NONCE_TRAILER_BYTES
        val actualNonceCounter = readU64Le(encrypted, nonceOffset)
        if (actualNonceCounter != expectedNonceCounter) {
            throw ApapNonceSequenceException(
                "APAP nonce is not the next expected UInt64 counter",
            )
        }

        val nonce = nonce12(actualNonceCounter)
        val aad = encrypted.copyOfRange(0, AAD_BYTES)
        val sealed = encrypted.copyOfRange(CLEAR_HEADER_BYTES, nonceOffset)
        val plaintextTail = try {
            cryptor.open(key, nonce, sealed, aad)
        } finally {
            nonce.fill(0)
            aad.fill(0)
            sealed.fill(0)
        }
        try {
            val expectedTailBytes = encrypted.size - CLEAR_HEADER_BYTES - OVERHEAD_BYTES
            if (plaintextTail.size != expectedTailBytes) {
                throw IllegalStateException(
                    "APAP AEAD returned ${plaintextTail.size} bytes, expected $expectedTailBytes",
                )
            }
            val output = ByteArray(CLEAR_HEADER_BYTES + plaintextTail.size)
            encrypted.copyInto(output, 0, 0, CLEAR_HEADER_BYTES)
            plaintextTail.copyInto(output, CLEAR_HEADER_BYTES)
            return output
        } finally {
            plaintextTail.fill(0)
        }
    }

    internal fun trailerNonce(encrypted: ByteArray): Long {
        if (encrypted.size < NONCE_TRAILER_BYTES) {
            throw ApapProtocolException("truncated APAP nonce trailer")
        }
        return readU64Le(encrypted, encrypted.size - NONCE_TRAILER_BYTES)
    }

    /** Apple carries only the UInt64 LE counter; IETF ChaCha20-Poly1305 prepends four zero bytes. */
    private fun nonce12(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        putU64Le(nonce, 4, counter)
        return nonce
    }

    private fun readU64Le(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        }
        return value
    }

    private fun putU64Le(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun requireKey(key: ByteArray) {
        require(key.size == KEY_BYTES) { "APAP key must be $KEY_BYTES bytes" }
    }

    private fun requireMaximum(maxEncryptedBytes: Int) {
        require(maxEncryptedBytes >= MIN_ENCRYPTED_BYTES) {
            "maxEncryptedBytes must be at least $MIN_ENCRYPTED_BYTES"
        }
    }
}

/**
 * One outbound APAP encryption direction.
 *
 * The counter advances only after the complete encrypted packet has been produced. Any provider
 * or packet-construction failure makes this writer terminal and zeroizes its key, so a retry can
 * neither create a receiver-visible counter gap nor reuse a nonce whose outcome is uncertain. The
 * successful UInt64.MAX_VALUE packet is returned, then this direction becomes terminal and its key
 * is zeroized rather than wrapping to zero.
 */
class EncryptedApapWriter(
    key: ByteArray,
    initialNonceCounter: Long = 0L,
    private val cryptor: ApapAeadCryptor = BouncyCastleApapAeadCryptor,
    private val maxEncryptedBytes: Int = EncryptedApapPacketCodec.DEFAULT_MAX_ENCRYPTED_BYTES,
) : Closeable {
    private val lock = Any()
    private val sessionKey = validateAndCopyKey(key)
    private var nextNonceCounter = initialNonceCounter
    private var nonceSpaceExhausted = false
    private var closed = false
    private var terminalFailure: Throwable? = null

    init {
        require(maxEncryptedBytes >= EncryptedApapPacketCodec.MIN_ENCRYPTED_BYTES)
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        requirePlaintextBounds(plaintext)
        val plaintextSnapshot = plaintext.copyOf()
        return try {
            synchronized(lock) {
                ensureUsable()
                val nonce = currentNonce()
                try {
                    val encrypted = EncryptedApapPacketCodec.seal(
                        key = sessionKey,
                        nonceCounter = nonce,
                        plaintext = plaintextSnapshot,
                        cryptor = cryptor,
                        maxEncryptedBytes = maxEncryptedBytes,
                    )
                    advanceNonce()
                    encrypted
                } catch (failure: Throwable) {
                    terminate(failure)
                    throw failure
                }
            }
        } finally {
            plaintextSnapshot.fill(0)
        }
    }

    fun encrypt(packet: ApapPacket, limits: ApapCodecLimits = ApapCodecLimits()): ByteArray {
        val plaintext = ApapPacketCodec.encode(packet, limits)
        return try {
            encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    val isTerminal: Boolean
        get() = synchronized(lock) { terminalFailure != null }

    internal fun keyIsZeroizedForTest(): Boolean = synchronized(lock) {
        sessionKey.all { it == 0.toByte() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sessionKey.fill(0)
        }
    }

    private fun currentNonce(): Long {
        check(!nonceSpaceExhausted) { "APAP nonce space is exhausted" }
        return nextNonceCounter
    }

    private fun advanceNonce() {
        check(!nonceSpaceExhausted) { "APAP nonce space is exhausted" }
        if (nextNonceCounter == -1L) {
            nonceSpaceExhausted = true
            terminalFailure = ApapNonceExhaustedException()
            sessionKey.fill(0)
        } else {
            nextNonceCounter += 1L
        }
    }

    private fun requirePlaintextBounds(plaintext: ByteArray) {
        if (plaintext.size < EncryptedApapPacketCodec.MIN_PLAINTEXT_BYTES) {
            throw ApapProtocolException(
                "APAP plaintext is shorter than ${EncryptedApapPacketCodec.MIN_PLAINTEXT_BYTES} bytes",
            )
        }
        if (plaintext.size.toLong() + EncryptedApapPacketCodec.OVERHEAD_BYTES > maxEncryptedBytes) {
            throw ApapProtocolException("encrypted APAP packet exceeds $maxEncryptedBytes bytes")
        }
    }

    private fun terminate(failure: Throwable) {
        terminalFailure = failure
        sessionKey.fill(0)
    }

    private fun ensureUsable() {
        check(!closed) { "encrypted APAP writer is closed" }
        terminalFailure?.let { throw IllegalStateException("encrypted APAP writer is terminal", it) }
        check(!nonceSpaceExhausted) { "APAP nonce space is exhausted" }
    }
}

/**
 * One inbound APAP decryption direction with exact monotonic nonce admission.
 *
 * Authentication failure and nonce discontinuity are terminal: the key is zeroized immediately
 * and no subsequent packet may be attempted with this direction instance. Successfully consuming
 * UInt64.MAX_VALUE also terminalizes the direction and zeroizes its key after returning that packet.
 */
class EncryptedApapReader(
    key: ByteArray,
    initialNonceCounter: Long = 0L,
    private val cryptor: ApapAeadCryptor = BouncyCastleApapAeadCryptor,
    private val maxEncryptedBytes: Int = EncryptedApapPacketCodec.DEFAULT_MAX_ENCRYPTED_BYTES,
) : Closeable {
    private val lock = Any()
    private val sessionKey = validateAndCopyKey(key)
    private var expectedNonceCounter = initialNonceCounter
    private var nonceSpaceExhausted = false
    private var closed = false
    private var terminalFailure: Throwable? = null

    init {
        require(maxEncryptedBytes >= EncryptedApapPacketCodec.MIN_ENCRYPTED_BYTES)
    }

    fun decrypt(encrypted: ByteArray): ByteArray {
        val encryptedSnapshot = validatedEncryptedSnapshot(encrypted)
        return try {
            synchronized(lock) {
                ensureUsable()
                var plaintext: ByteArray? = null
                try {
                    plaintext = openLocked(encryptedSnapshot)
                    advanceNonce()
                    requireNotNull(plaintext).also { plaintext = null }
                } catch (failure: Throwable) {
                    plaintext?.fill(0)
                    terminate(failure)
                    throw failure
                }
            }
        } finally {
            encryptedSnapshot.fill(0)
        }
    }

    fun decryptPacket(
        encrypted: ByteArray,
        limits: ApapCodecLimits = ApapCodecLimits(),
    ): ApapPacket {
        val encryptedSnapshot = validatedEncryptedSnapshot(encrypted)
        return try {
            synchronized(lock) {
                ensureUsable()
                var plaintext: ByteArray? = null
                try {
                    plaintext = openLocked(encryptedSnapshot)
                    val packet = ApapPacketCodec.decode(requireNotNull(plaintext), limits)
                    advanceNonce()
                    packet
                } catch (failure: Throwable) {
                    terminate(failure)
                    throw failure
                } finally {
                    plaintext?.fill(0)
                }
            }
        } finally {
            encryptedSnapshot.fill(0)
        }
    }

    val isTerminal: Boolean
        get() = synchronized(lock) { terminalFailure != null }

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    internal fun keyIsZeroizedForTest(): Boolean = synchronized(lock) {
        sessionKey.all { it == 0.toByte() }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sessionKey.fill(0)
        }
    }

    private fun advanceNonce() {
        check(!nonceSpaceExhausted) { "APAP nonce space is exhausted" }
        if (expectedNonceCounter == -1L) {
            nonceSpaceExhausted = true
            terminalFailure = ApapNonceExhaustedException()
            sessionKey.fill(0)
        } else {
            expectedNonceCounter += 1L
        }
    }

    private fun openLocked(encrypted: ByteArray): ByteArray = EncryptedApapPacketCodec.open(
        key = sessionKey,
        expectedNonceCounter = expectedNonceCounter,
        encrypted = encrypted,
        cryptor = cryptor,
        maxEncryptedBytes = maxEncryptedBytes,
    )

    private fun validatedEncryptedSnapshot(encrypted: ByteArray): ByteArray {
        if (encrypted.size < EncryptedApapPacketCodec.MIN_ENCRYPTED_BYTES) {
            throw ApapProtocolException(
                "encrypted APAP packet is shorter than " +
                    "${EncryptedApapPacketCodec.MIN_ENCRYPTED_BYTES} bytes",
            )
        }
        if (encrypted.size > maxEncryptedBytes) {
            throw ApapProtocolException("encrypted APAP packet exceeds $maxEncryptedBytes bytes")
        }
        return encrypted.copyOf()
    }

    private fun terminate(failure: Throwable) {
        terminalFailure = failure
        sessionKey.fill(0)
    }

    private fun ensureUsable() {
        check(!closed) { "encrypted APAP reader is closed" }
        terminalFailure?.let { throw IllegalStateException("encrypted APAP reader is terminal", it) }
        check(!nonceSpaceExhausted) { "APAP nonce space is exhausted" }
    }
}

private fun validateAndCopyKey(key: ByteArray): ByteArray {
    require(key.size == EncryptedApapPacketCodec.KEY_BYTES) {
        "APAP key must be ${EncryptedApapPacketCodec.KEY_BYTES} bytes"
    }
    return key.copyOf()
}
