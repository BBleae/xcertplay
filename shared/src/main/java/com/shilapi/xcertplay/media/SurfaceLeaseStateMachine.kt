package com.shilapi.xcertplay.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Per-output mutable metadata; callers serialize [source]/[token] with their routing lock. */
internal class SurfaceLeaseOutput<S : Any> {
    val state = SurfaceLeaseStateMachine<S>()
    var source: Any? = null
    var token: SurfaceLeaseStateMachine.LeaseToken? = null
}

/**
 * Exact-key registry for independent decoder outputs. It deliberately relies on [K]'s equality
 * contract; session-bound video handles use referential equality, so equal protocol identities in
 * different sessions cannot share a Surface generation or retirement watermark.
 */
internal class SurfaceLeaseOutputRegistry<K : Any, S : Any> {
    private val outputs = ConcurrentHashMap<K, SurfaceLeaseOutput<S>>()

    operator fun get(key: K): SurfaceLeaseOutput<S>? = outputs[key]

    fun getOrCreate(key: K): SurfaceLeaseOutput<S> =
        outputs.computeIfAbsent(key) { SurfaceLeaseOutput() }

    fun entriesSnapshot(): List<Pair<K, SurfaceLeaseOutput<S>>> =
        outputs.entries.map { it.key to it.value }

    fun valuesSnapshot(): List<SurfaceLeaseOutput<S>> = outputs.values.toList()

    fun remove(key: K, output: SurfaceLeaseOutput<S>): Boolean = outputs.remove(key, output)

    fun removeIf(predicate: (K, SurfaceLeaseOutput<S>) -> Boolean) {
        outputs.entries.removeAll { predicate(it.key, it.value) }
    }
}

/**
 * Pure state machine for handing one decoder output between generation-bound surfaces.
 *
 * Installing or detaching a surface creates an [ApplyRequest]. A lease is retired only after the
 * decoder worker acknowledges a later request, which proves that the worker either switched away
 * from the leased surface or released the decoder that referenced it. No caller-owned operation is
 * invoked while [lock] is held.
 */
internal class SurfaceLeaseStateMachine<S : Any> {
    class LeaseToken internal constructor(
        internal val owner: Any,
        val generation: Long,
    ) {
        override fun toString(): String = "SurfaceLeaseToken(generation=$generation)"
    }

    class ApplyRequest<S : Any> internal constructor(
        internal val owner: Any,
        val generation: Long,
        val surface: S?,
    ) {
        override fun toString(): String =
            "SurfaceApplyRequest(generation=$generation, attached=${surface != null})"
    }

    class Attachment<S : Any> internal constructor(
        val token: LeaseToken,
        val request: ApplyRequest<S>,
    )

    class Retirement<S : Any> internal constructor(
        val request: ApplyRequest<S>?,
        val alreadyRetired: Boolean,
    )

    enum class AwaitResult {
        RETIRED,
        TIMED_OUT,
        INTERRUPTED,
    }

    private val lock = Object()
    private val owner = Any()
    private var nextGeneration = 0L
    private var acknowledgedGeneration = 0L
    private var currentToken: LeaseToken? = null
    private var requested = ApplyRequest<S>(owner, generation = 0L, surface = null)
    private var shuttingDown = false
    private var terminated = false

    fun attach(surface: S): Attachment<S> = synchronized(lock) {
        check(!shuttingDown) { "Surface output is shutting down" }
        require(currentToken == null) {
            "A Surface is already attached; retire its existing lease before replacement"
        }
        check(requested.generation == 0L || acknowledgedGeneration >= requested.generation) {
            "Previous Surface retirement is not acknowledged"
        }
        val generation = allocateGenerationLocked()
        val token = LeaseToken(owner, generation)
        val request = ApplyRequest(owner, generation, surface)
        currentToken = token
        requested = request
        Attachment(token, request)
    }

    /**
     * Retiring a stale token never changes the current request. Retiring the current token emits a
     * generation-bound detach request; all callers may then wait on the same retirement watermark.
     */
    fun retire(token: LeaseToken): Retirement<S> = synchronized(lock) {
        requireOwned(token)
        if (isRetiredLocked(token)) return@synchronized Retirement(null, alreadyRetired = true)
        if (currentToken !== token) return@synchronized Retirement(null, alreadyRetired = false)

        val generation = allocateGenerationLocked()
        val request = ApplyRequest<S>(owner, generation, surface = null)
        currentToken = null
        requested = request
        Retirement(request, alreadyRetired = false)
    }

    /** Prevents new attachments and creates a final detach request. */
    fun beginShutdown(): ApplyRequest<S>? = synchronized(lock) {
        if (shuttingDown) return@synchronized null
        shuttingDown = true
        if (terminated) return@synchronized null
        val generation = allocateGenerationLocked()
        ApplyRequest<S>(owner, generation, surface = null).also {
            currentToken = null
            requested = it
        }
    }

    /** Latest request to capture as the safe acknowledgement watermark before closing a decoder. */
    fun currentRequest(): ApplyRequest<S> = synchronized(lock) { requested }

    fun isCurrent(token: LeaseToken): Boolean = synchronized(lock) {
        requireOwned(token)
        !shuttingDown && currentToken === token
    }

    fun isCurrent(request: ApplyRequest<S>): Boolean = synchronized(lock) {
        requireOwned(request)
        !terminated && requested === request
    }

    /**
     * Called after the worker applied [request], or after it released its decoder while processing
     * [request]. Older leases are now safe to retire. Stale acknowledgements cannot overtake newer
     * requests because only the acknowledged request's generation is used.
     */
    fun acknowledge(request: ApplyRequest<S>) {
        synchronized(lock) {
            requireOwned(request)
            if (request.generation > acknowledgedGeneration) {
                acknowledgedGeneration = request.generation
                lock.notifyAll()
            }
        }
    }

    /** Completes every lease after the decoder worker has released all resources and exited. */
    fun acknowledgeTermination() {
        synchronized(lock) {
            terminated = true
            shuttingDown = true
            currentToken = null
            acknowledgedGeneration = Long.MAX_VALUE
            lock.notifyAll()
        }
    }

    fun awaitRetirement(token: LeaseToken, timeoutMillis: Long): AwaitResult {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        val deadline = saturatedAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
        synchronized(lock) {
            requireOwned(token)
            while (!isRetiredLocked(token)) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return AwaitResult.TIMED_OUT
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return AwaitResult.INTERRUPTED
                }
            }
            return AwaitResult.RETIRED
        }
    }

    private fun isRetiredLocked(token: LeaseToken): Boolean =
        terminated || token.generation < acknowledgedGeneration

    private fun allocateGenerationLocked(): Long {
        check(nextGeneration < Long.MAX_VALUE) { "Surface generation exhausted" }
        nextGeneration += 1L
        return nextGeneration
    }

    private fun requireOwned(token: LeaseToken) {
        require(token.owner === owner) { "Surface lease belongs to another output" }
    }

    private fun requireOwned(request: ApplyRequest<S>) {
        require(request.owner === owner) { "Surface request belongs to another output" }
    }

    private companion object {
        fun saturatedAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
