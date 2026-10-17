package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/**
 * A one-shot admission barrier shared by APAP and an independently prepared `mctl` transport.
 * Both transports may start workers before [open], but neither may accept or dispatch a peer
 * until the barrier opens. Closing it wakes workers without admitting a connection.
 */
internal class MainBufferedAdmissionGate : Closeable {
    private val lock = Object()
    private var openedAtNanos: Long? = null
    private var closed = false
    private val ready = IdentityHashMap<Any, Unit>()

    fun open() = synchronized(lock) {
        check(!closed) { "main-buffered admission gate has retired" }
        check(openedAtNanos == null) { "main-buffered admission gate is already open" }
        openedAtNanos = System.nanoTime()
        lock.notifyAll()
    }

    fun awaitOpen(): Boolean = awaitOpeningTimeNanos() != null

    /** A worker announces readiness only at its last pre-admission instruction. */
    fun arriveAndAwaitOpeningTimeNanos(participant: Any): Long? = synchronized(lock) {
        if (closed) return@synchronized null
        // The detached single-port `commit()` path may open before its worker is scheduled.
        // Dual-port openWhenReady cannot do so and still requires both exact arrivals.
        openedAtNanos?.let {
            ready[participant] = Unit
            lock.notifyAll()
            return@synchronized it
        }
        check(!ready.containsKey(participant)) { "main-buffered worker arrived twice" }
        ready[participant] = Unit
        lock.notifyAll()
        while (openedAtNanos == null && !closed) lock.wait()
        if (closed) null else openedAtNanos
    }

    /** Exact readiness while the gate is still closed; not an admission receipt. */
    fun awaitReady(participant: Any, timeoutMillis: Long): Boolean = synchronized(lock) {
        require(timeoutMillis in 0L..300_000L)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!ready.containsKey(participant) && !closed && openedAtNanos == null) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            lock.wait(maxOf(1L, minOf(remaining / 1_000_000L, 100L)))
        }
        ready.containsKey(participant) && !closed && openedAtNanos == null
    }

    fun isReady(participant: Any): Boolean = synchronized(lock) {
        ready.containsKey(participant) && !closed && openedAtNanos == null
    }

    /** Compatibility for a detached single-port worker whose gate opened before scheduling. */
    fun awaitArrival(participant: Any, timeoutMillis: Long): Boolean = synchronized(lock) {
        require(timeoutMillis in 0L..300_000L)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!ready.containsKey(participant) && !closed) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            lock.wait(maxOf(1L, minOf(remaining / 1_000_000L, 100L)))
        }
        ready.containsKey(participant) && !closed
    }

    fun isArrived(participant: Any): Boolean = synchronized(lock) {
        ready.containsKey(participant) && !closed
    }

    /** The only dual-port release; a departed worker invalidates its readiness. */
    fun openWhenReady(first: Any, second: Any) = synchronized(lock) {
        check(!closed && openedAtNanos == null && first !== second &&
            ready.containsKey(first) && ready.containsKey(second)
        ) { "main-buffered dual-port readiness is not current" }
        openedAtNanos = System.nanoTime()
        lock.notifyAll()
    }

    fun depart(participant: Any) = synchronized(lock) {
        ready.remove(participant)
        lock.notifyAll()
    }

    /** The admission deadline is anchored to gate opening, not worker scheduling. */
    fun awaitOpeningTimeNanos(): Long? = synchronized(lock) {
        while (openedAtNanos == null && !closed) lock.wait()
        if (closed) null else openedAtNanos
    }

    override fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}

/** The exact UInt64 seed is retained as raw bits; it is not itself a derived transport key. */
internal class MainBufferedMctlPortRequest(
    val generation: Long,
    val bindAddress: InetAddress,
    val expectedPeer: InetAddress,
    val streamConnectionIdBits: Long,
    val encryptionSeedBits: Long,
)

/**
 * Injection point for a future independently authenticated APTransport `mctl` listener.
 * Implementations own cleanup of partially allocated resources if [prepare] throws.
 */
internal fun interface MainBufferedMctlPortPreparer {
    fun prepare(request: MainBufferedMctlPortRequest): MainBufferedPreparedMctlPort
}

/** Proof that the exact prepared worker passed the *same* shared gate and is still listening. */
internal data class MainBufferedMctlAdmissionReceipt(
    val generation: Long,
    val localAddress: InetSocketAddress,
    val expectedPeer: InetAddress,
    val gate: MainBufferedAdmissionGate,
    val workerAlive: Boolean,
)

/**
 * An already bound, peer-pinned `mctl` port. [commitDeferred] must not accept or dispatch data;
 * [activateWhen] may start a worker, but that worker must wait for [gate] before admission.
 * A failed [commitDeferred] must leave the lease rollback-capable. [rollback] and [close] are
 * distinct, retryable retirement modes: rollback remains valid after its own partial failure.
 */
internal interface MainBufferedPreparedMctlPort : Closeable {
    val localAddress: InetSocketAddress
    val generation: Long
    val expectedPeer: InetAddress

    fun commitDeferred()

    fun activateWhen(gate: MainBufferedAdmissionGate)

    /** Null when the worker died, the gate was closed, or readiness was not reached in time. */
    fun awaitAdmissionReceipt(timeoutMillis: Long): MainBufferedMctlAdmissionReceipt?

    /**
     * Atomically recheck the *current* worker state and run a short commit assignment under the
     * same port lock used by terminal failure. A prior receipt by itself is not a live lease.
     * The callback must not block or call back into the port.
     */
    fun commitIfAdmissionCurrent(
        receipt: MainBufferedMctlAdmissionReceipt,
        markCommitted: () -> Unit,
    ): Boolean

    /** A terminal authenticated-transport failure must revoke the paired APAP owner as well. */
    fun onTerminalFailure(listener: (Throwable) -> Unit)

    fun rollback()
}
