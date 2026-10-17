package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedVerifiedSecretBorrow
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CarPlay pair-verify responder (accessory/server side).
 *
 * Uses ephemeral X25519 plus Ed25519 signatures against the controller's stored long-term key,
 * then derives the ChaCha20-Poly1305 control-channel keys. The fresh Pair-TLS-PSK is a
 * separate 16-byte HKDF output and is available only after the controller signature verifies.
 */
class PairVerify(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
) {
    data class ControlKeys(val readKey: ByteArray, val writeKey: ByteArray)

    private var ephemeralPublicKey: ByteArray? = null
    private var clientEphemeralPublicKey: ByteArray? = null
    private var sharedSecret: ByteArray? = null
    private var encryptionKey: ByteArray? = null
    private var keys: ControlKeys? = null
    private var verified = false
    private var controllerId: String? = null
    /** Referential token, replaced before each M1 and on every failed/revoked exchange. */
    private var generation: Any = Any()
    private var pskBorrow: PairTlsPskBorrow? = null
    private var pskBorrowThread: Thread? = null
    private var pskBorrowCompletion: CountDownLatch? = null
    private var rawSecretBorrow: MainBufferedVerifiedSecretBorrow? = null
    private var rawSecretBorrowThread: Thread? = null
    private var rawSecretBorrowCompletion: CountDownLatch? = null
    private var rawSecretRevocationPending = false
    private var exchangeInFlight = false
    /** Set before waiting for a borrowed key so a concurrent M1 cannot restart after teardown. */
    private var sessionSecretsRetired = false

    /** A temporary PSK copy. Only its exact Pair Verify generation may retire it. */
    internal class PairTlsPskBorrow private constructor(
        private val owner: PairVerify,
        private val material: ByteArray,
    ) {
        internal fun <T> use(consume: (ByteArray) -> T): T = consume(material)

        internal fun retire() = owner.retirePskBorrow(this)

        companion object {
            internal fun create(owner: PairVerify, material: ByteArray) =
                PairTlsPskBorrow(owner, material)
        }

        internal fun wipe() = material.fill(0)
    }

    val isVerified: Boolean get() = synchronized(this) { verified && !sessionSecretsRetired }
    val verifiedControllerId: String? get() = synchronized(this) {
        controllerId.takeIf { verified && !sessionSecretsRetired }
    }
    internal val controlKeys: ControlKeys? get() = synchronized(this) {
        if (!verified || sessionSecretsRetired) null else {
            keys?.let { ControlKeys(it.readKey.copyOf(), it.writeKey.copyOf()) }
        }
    }
    internal val shared: ByteArray? get() = synchronized(this) {
        if (!verified || sessionSecretsRetired) null else sharedSecret?.copyOf()
    }
    internal val verifiedGeneration: Any? get() = synchronized(this) {
        generation.takeIf { verified && !sessionSecretsRetired }
    }

    /**
     * Internal derivation primitive retained for protocol tests. Production transport owners
     * must claim the session-owned lease instead of exporting an untracked array. This is not a
     * resumed-session key, a Control-Salt key, or evidence of a video player or TLS transport.
     */
    @Synchronized
    internal fun deriveFreshPairTlsPsk(): ByteArray? {
        if (!verified || sessionSecretsRetired || pskBorrow != null) return null
        val secret = sharedSecret?.copyOf() ?: return null
        val salt = PAIR_TLS_PSK_SALT.asciiBytes()
        var derived: ByteArray? = null
        return try {
            derived = AirPlayCrypto.hkdfSha512(secret, salt, ByteArray(0), PAIR_TLS_PSK_BYTES)
            derived.copyOf()
        } finally {
            derived?.fill(0)
            secret.fill(0)
            salt.fill(0)
        }
    }

    /** Reserves one temporary copy before the session calls an external TLS factory. */
    @Synchronized
    internal fun beginFreshPairTlsPskBorrow(expectedGeneration: Any): PairTlsPskBorrow? {
        if (!verified || sessionSecretsRetired || exchangeInFlight ||
            generation !== expectedGeneration) return null
        if (pskBorrow != null) return null
        val psk = deriveFreshPairTlsPsk() ?: return null
        return PairTlsPskBorrow.create(this, psk).also {
            pskBorrow = it
            pskBorrowThread = Thread.currentThread()
            pskBorrowCompletion = CountDownLatch(1)
        }
    }

    /** Transfer one tracked raw 32-byte ECDH copy, never a Pair-TLS PSK or APAP shk. */
    @Synchronized
    internal fun beginVerifiedSharedSecretBorrow(
        expectedGeneration: Any,
        numericGeneration: Long,
    ): MainBufferedVerifiedSecretBorrow? {
        if (!verified || sessionSecretsRetired || exchangeInFlight ||
            generation !== expectedGeneration || numericGeneration <= 0L ||
            rawSecretBorrow != null) return null
        val copied = sharedSecret?.takeIf { it.size == 32 }?.copyOf() ?: return null
        val completion = CountDownLatch(1)
        var borrow: MainBufferedVerifiedSecretBorrow? = null
        return try {
            borrow = MainBufferedVerifiedSecretBorrow.takeOwnership(numericGeneration, copied) {
                retireRawSecretBorrow(checkNotNull(borrow))
            }
            rawSecretBorrow = borrow
            rawSecretBorrowThread = Thread.currentThread()
            rawSecretBorrowCompletion = completion
            borrow
        } catch (failure: Throwable) {
            copied.fill(0)
            throw failure
        }
    }

    @Synchronized
    private fun retireRawSecretBorrow(borrow: MainBufferedVerifiedSecretBorrow) {
        if (rawSecretBorrow !== borrow) return
        rawSecretBorrow = null
        rawSecretBorrowThread = null
        rawSecretBorrowCompletion?.countDown()
        rawSecretBorrowCompletion = null
        if ((sessionSecretsRetired || rawSecretRevocationPending) && pskBorrow == null) revoke()
    }

    @Synchronized
    private fun retirePskBorrow(borrow: PairTlsPskBorrow) {
        if (pskBorrow !== borrow) return
        borrow.wipe()
        pskBorrow = null
        pskBorrowThread = null
        pskBorrowCompletion?.countDown()
        pskBorrowCompletion = null
        // A same-thread clear attempt marks the owner terminal but cannot wait for itself.
        // Complete that deferred wipe when its borrow's finally block returns.
        if ((sessionSecretsRetired || rawSecretRevocationPending) && rawSecretBorrow == null) revoke()
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
    }

    /** Terminal intent is nonblocking; existing borrowed copies remain owned until returned. */
    @Synchronized
    internal fun requestSessionSecretsRetirement() {
        sessionSecretsRetired = true
    }

    private data class BorrowWait(val thread: Thread?, val completion: CountDownLatch?)

    private fun awaitBorrow(wait: BorrowWait, deadlineNanos: Long, label: String) {
        val completion = wait.completion ?: return
        check(wait.thread !== Thread.currentThread()) { "$label cannot retire its own borrow" }
        val remaining = deadlineNanos - System.nanoTime()
        val completed = if (remaining <= 0L) false else try {
            completion.await(remaining, TimeUnit.NANOSECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        }
        if (!completed) {
            throw IOException("Timed out retiring $label borrow")
        }
    }

    /** No PairVerify monitor is held while an exact borrowed secret owner is joined. */
    internal fun awaitSecretBorrows(timeoutMillis: Long) {
        require(timeoutMillis > 0L)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val waits = synchronized(this) {
            BorrowWait(pskBorrowThread, pskBorrowCompletion) to
                BorrowWait(rawSecretBorrowThread, rawSecretBorrowCompletion)
        }
        awaitBorrow(waits.first, deadline, "Pair-TLS-PSK")
        awaitBorrow(waits.second, deadline, "MainBuffered PairVerify secret")
    }

    @Synchronized
    internal fun finishSessionSecretsRetirement() {
        check(sessionSecretsRetired && pskBorrow == null && rawSecretBorrow == null) {
            "PairVerify secret borrow has not quiesced"
        }
        revoke()
    }

    /** Direct callers get the same bounded, lock-free join as AirPlaySession teardown. */
    internal fun clearSessionSecrets(timeoutMillis: Long = 5_000L) {
        requestSessionSecretsRetirement()
        awaitSecretBorrows(timeoutMillis)
        finishSessionSecretsRetirement()
    }

    fun handle(body: ByteArray): ByteArray {
        val waits = synchronized(this) {
            check(!sessionSecretsRetired) { "Pair Verify owner has retired" }
            check(!exchangeInFlight) { "Pair Verify exchange is already in flight" }
            exchangeInFlight = true
            if (rawSecretBorrow != null || pskBorrow != null) {
                // Any new exchange invalidates the old generation before it waits for tracked
                // secret copies. No old lease can start another side effect after this point.
                generation = Any()
                verified = false
                controllerId = null
                rawSecretRevocationPending = true
            }
            BorrowWait(pskBorrowThread, pskBorrowCompletion) to
                BorrowWait(rawSecretBorrowThread, rawSecretBorrowCompletion)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_BORROW_TIMEOUT_MILLIS)
            awaitBorrow(waits.first, deadline, "Pair-TLS-PSK")
            awaitBorrow(waits.second, deadline, "MainBuffered PairVerify secret")
            return synchronized(this) {
                check(!sessionSecretsRetired) { "Pair Verify owner has retired" }
                var state = 0
                try {
                    val tlv = Tlv8Codec.decode(body)
                    state = tlv[TYPE_STATE]?.takeIf { it.size == 1 }?.first()?.toInt()?.and(0xff) ?: 0
                    when (state) {
                        1 -> {
                            revoke()
                            m2(tlv)
                        }
                        3 -> {
                            if (verified) {
                                revoke()
                                err(4)
                            } else {
                                val response = m4(tlv)
                                if (!verified) revoke()
                                response
                            }
                        }
                        else -> {
                            revoke()
                            err(state)
                        }
                    }
                } catch (_: Exception) {
                    revoke()
                    err(when (state) { 1 -> 2; 3 -> 4; else -> state })
                }
            }
        } finally {
            synchronized(this) { exchangeInFlight = false }
        }
    }

    private fun m2(tlv: Map<Int, ByteArray>): ByteArray {
        val clientEphemeralPublicKey = tlv[TYPE_PUBLIC_KEY] ?: return err(2)
        if (clientEphemeralPublicKey.size != X25519_KEY_BYTES) return err(2)
        val pair = AirPlayCrypto.x25519Generate()
        val shared = try {
            AirPlayCrypto.x25519Shared(pair.privateKey, clientEphemeralPublicKey)
        } finally {
            pair.privateKey.fill(0)
        }
        ephemeralPublicKey = pair.publicKey.copyOf()
        this.clientEphemeralPublicKey = clientEphemeralPublicKey.copyOf()
        sharedSecret = shared
        val encryptionKey = AirPlayCrypto.hkdfSha512(shared, VERIFY_ENCRYPT_SALT.asciiBytes(), VERIFY_ENCRYPT_INFO.asciiBytes())
        this.encryptionKey = encryptionKey

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            identity.privateKey,
            concatBytes(pair.publicKey, accessoryId, clientEphemeralPublicKey),
        )
        val sub = Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_IDENTIFIER, accessoryId),
                Tlv8Item(TYPE_SIGNATURE, signature),
            ),
        )
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel(MSG02), sub)
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_STATE, byteArrayOf(2)),
                Tlv8Item(TYPE_PUBLIC_KEY, pair.publicKey),
                Tlv8Item(TYPE_ENCRYPTED_DATA, sealed),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[TYPE_ENCRYPTED_DATA]
        val encryptionKey = this.encryptionKey
        val shared = sharedSecret
        val ownEphemeralPublicKey = ephemeralPublicKey
        val clientEphemeralPublicKey = this.clientEphemeralPublicKey
        if (
            encryptionKey == null || shared == null || ownEphemeralPublicKey == null ||
            clientEphemeralPublicKey == null || encrypted == null
        ) {
            return err(4)
        }

        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel(MSG03), encrypted))
        val controllerId = sub[TYPE_IDENTIFIER]
        val controllerSignature = sub[TYPE_SIGNATURE]
        if (controllerId == null || controllerSignature == null) return err(4)

        val controllerLtpk = pairings.get(controllerId.toString(Charsets.UTF_8)) ?: return err(4)
        val signatureData = concatBytes(clientEphemeralPublicKey, controllerId, ownEphemeralPublicKey)
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, signatureData, controllerSignature)) return err(4)

        var readKey: ByteArray? = null
        var writeKey: ByteArray? = null
        var committed = false
        try {
            readKey = AirPlayCrypto.hkdfSha512(shared, CONTROL_SALT.asciiBytes(), CONTROL_WRITE_KEY_INFO.asciiBytes())
            writeKey = AirPlayCrypto.hkdfSha512(shared, CONTROL_SALT.asciiBytes(), CONTROL_READ_KEY_INFO.asciiBytes())
            val response = Tlv8Codec.encode(listOf(Tlv8Item(TYPE_STATE, byteArrayOf(4))))
            keys = ControlKeys(readKey, writeKey)
            this.controllerId = controllerId.toString(Charsets.UTF_8)
            verified = true
            committed = true
            clearHandshakeMaterial()
            return response
        } finally {
            if (!committed) {
                readKey?.fill(0)
                writeKey?.fill(0)
            }
        }
    }

    private fun clearHandshakeMaterial() {
        ephemeralPublicKey?.fill(0)
        ephemeralPublicKey = null
        clientEphemeralPublicKey?.fill(0)
        clientEphemeralPublicKey = null
        encryptionKey?.fill(0)
        encryptionKey = null
    }

    private fun revoke() {
        rawSecretRevocationPending = false
        generation = Any()
        verified = false
        controllerId = null
        clearHandshakeMaterial()
        sharedSecret?.fill(0)
        sharedSecret = null
        keys?.readKey?.fill(0)
        keys?.writeKey?.fill(0)
        keys = null
    }

    private fun err(state: Int): ByteArray = Tlv8Codec.encode(
        listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(state.toByte())),
            Tlv8Item(TYPE_ERROR, byteArrayOf(ERROR_AUTHENTICATION.toByte())),
        ),
    )

    private companion object {
        const val TYPE_IDENTIFIER = 0x01
        const val TYPE_PUBLIC_KEY = 0x03
        const val TYPE_ENCRYPTED_DATA = 0x05
        const val TYPE_STATE = 0x06
        const val TYPE_ERROR = 0x07
        const val TYPE_SIGNATURE = 0x0a
        const val ERROR_AUTHENTICATION = 2
        const val VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
        const val VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"
        const val CONTROL_SALT = "Control-Salt"
        const val CONTROL_READ_KEY_INFO = "Control-Read-Encryption-Key"
        const val CONTROL_WRITE_KEY_INFO = "Control-Write-Encryption-Key"
        const val PAIR_TLS_PSK_SALT = "Pair-TLS-PSK"
        const val PAIR_TLS_PSK_BYTES = 16
        const val X25519_KEY_BYTES = 32
        const val HANDSHAKE_BORROW_TIMEOUT_MILLIS = 5_000L
        const val MSG02 = "PV-Msg02"
        const val MSG03 = "PV-Msg03"
    }
}
