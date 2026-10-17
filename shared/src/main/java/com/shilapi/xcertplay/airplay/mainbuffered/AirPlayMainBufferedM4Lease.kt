package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlaySession
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A credential-free child owner for the exact M4-flushed control generation. The session
 * keeps this object registered before a product preparer can open a socket or native output.
 */
internal class AirPlayMainBufferedM4Lease internal constructor(
    override val session: AirPlaySession,
    internal val pairVerifyGeneration: Any,
    override val generation: Long,
    override val bindAddress: InetAddress,
    override val expectedPeer: InetAddress,
    override val allocatedStreamId: Long,
    internal val requestMarker: Any? = null,
    internal val issuanceOrdinal: Long = 0L,
) : MainBufferedM4FlushedSessionLease {
    /** No byte order is inferred from an M4 response or from the APAP `shk` field. */
    override val sourceByteOrder: MainBufferedPcm16ByteOrder? = null

    @Volatile internal var revoked = false
        private set
    @Volatile internal var closed = false
        private set
    private val preparationThread = Thread.currentThread()
    private val preparationComplete = CountDownLatch(1)
    private val closeComplete = CountDownLatch(1)

    override fun isCurrent(requestedGeneration: Long): Boolean =
        session.isMainBufferedM4LeaseCurrent(this, requestedGeneration)

    override fun borrowVerifiedSharedSecret(requestedGeneration: Long):
        MainBufferedVerifiedSecretBorrow? =
        session.borrowMainBufferedVerifiedSharedSecret(this, requestedGeneration)

    override fun finishPreparation() {
        preparationComplete.countDown()
    }

    override fun close() {
        session.releaseMainBufferedM4Lease(this)
    }

    internal fun revoke() {
        revoked = true
    }

    internal fun markClosed() {
        revoked = true
        closed = true
        closeComplete.countDown()
    }

    internal fun awaitPreparation(timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (preparationComplete.count > 0L && preparationThread === Thread.currentThread()) {
            throw IOException("MainBuffered preparation cannot retire itself reentrantly")
        }
        await(preparationComplete, deadline, "preparation")
    }

    internal fun awaitClosed(timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        await(closeComplete, deadline, "lease")
    }

    private fun await(latch: CountDownLatch, deadline: Long, label: String) {
        val remaining = deadline - System.nanoTime()
        if (latch.count > 0L && (remaining <= 0L ||
                !latch.await(remaining, TimeUnit.NANOSECONDS))) {
            throw IOException("Timed out retiring MainBuffered $label")
        }
    }

    override fun toString(): String =
        "AirPlayMainBufferedM4Lease(generation=$generation, secret=<redacted>)"
}
