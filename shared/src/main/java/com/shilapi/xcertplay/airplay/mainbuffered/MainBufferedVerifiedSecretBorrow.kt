package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.Closeable

/**
 * One transferred raw PairVerify-secret copy. The issuer must surrender this array; the borrower
 * may use it only while this scope is open, and [close] deterministically erases the same bytes.
 * A derived Pair-TLS PSK or APAP `shk` is not interchangeable with this 32-byte ECDH result.
 */
internal class MainBufferedVerifiedSecretBorrow private constructor(
    val generation: Long,
    private val rawSecret: ByteArray,
    private val onReturned: () -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized
    fun <T> withRawSecret(consume: (ByteArray) -> T): T {
        check(!closed) { "PairVerify secret borrow has been returned" }
        return consume(rawSecret)
    }

    override fun close() {
        val returned = synchronized(this) {
            if (closed) false else {
                rawSecret.fill(0)
                closed = true
                true
            }
        }
        // PairVerify's borrow registry is notified only after the transferred array is wiped.
        // It is never called under this scope's monitor or a caller's secret-use callback.
        if (returned) onReturned()
    }

    @Synchronized
    fun isReturnedAndZeroized(): Boolean = closed && rawSecret.all { it == 0.toByte() }

    companion object {
        /** Transfers ownership of an already-disposable raw copy, without making another copy. */
        fun takeOwnership(
            generation: Long,
            disposableRawSecret: ByteArray,
            onReturned: () -> Unit = {},
        ):
            MainBufferedVerifiedSecretBorrow {
            if (generation <= 0L || disposableRawSecret.size != 32) {
                disposableRawSecret.fill(0)
                throw IllegalArgumentException(
                    "mctl requires a positive generation and raw 32-byte PairVerify secret",
                )
            }
            return MainBufferedVerifiedSecretBorrow(generation, disposableRawSecret, onReturned)
        }
    }
}
