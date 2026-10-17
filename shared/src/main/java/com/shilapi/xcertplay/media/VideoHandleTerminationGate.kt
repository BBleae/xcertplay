package com.shilapi.xcertplay.media

import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/** Result of a bounded terminal-cleanup attempt. */
internal enum class VideoHandleTerminationResult {
    CLEANED,
    ALREADY_CLEANED,
    TIMED_OUT,
    INTERRUPTED,
}

/**
 * Linearizes callback admission and terminal cleanup for opaque stream handles.
 *
 * External callbacks execute only while counted as in-flight; no gate monitor is held around
 * caller code. Termination first publishes a permanent tombstone, then waits only for the caller's
 * bounded interval before attempting cleanup. Weak keys keep tombstones authoritative exactly as
 * long as a late callback, Surface lease, or owner can still reference the handle.
 */
internal class VideoHandleTerminationGate<K : Any> {
    private class Entry {
        val lock = Object()
        var terminal = false
        var inFlight = 0
        var cleanupInProgress = false
        var cleanupComplete = false
    }

    private val registryLock = Any()
    private val entries = WeakHashMap<K, Entry>()

    fun <T> withLive(key: K, rejected: T, action: () -> T): T {
        val entry = entry(key)
        synchronized(entry.lock) {
            if (entry.terminal) return rejected
            entry.inFlight++
        }
        return try {
            action()
        } finally {
            synchronized(entry.lock) {
                entry.inFlight--
                entry.lock.notifyAll()
            }
        }
    }

    /** Decoder-worker admission is non-blocking because caller code never holds the monitor. */
    fun <T> tryWithLive(key: K, rejected: T, action: () -> T): T =
        withLive(key, rejected, action)

    fun terminate(
        key: K,
        timeoutMillis: Long,
        cleanup: () -> Unit,
    ): VideoHandleTerminationResult {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        val entry = entry(key)
        val deadline = saturatedAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
        synchronized(entry.lock) {
            entry.terminal = true
            while (entry.inFlight > 0 || entry.cleanupInProgress) {
                if (entry.cleanupComplete) return VideoHandleTerminationResult.ALREADY_CLEANED
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return VideoHandleTerminationResult.TIMED_OUT
                try {
                    TimeUnit.NANOSECONDS.timedWait(entry.lock, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return VideoHandleTerminationResult.INTERRUPTED
                }
            }
            if (entry.cleanupComplete) return VideoHandleTerminationResult.ALREADY_CLEANED
            entry.cleanupInProgress = true
        }

        try {
            cleanup()
        } catch (error: Throwable) {
            synchronized(entry.lock) {
                entry.cleanupInProgress = false
                entry.lock.notifyAll()
            }
            throw error
        }
        synchronized(entry.lock) {
            entry.cleanupInProgress = false
            entry.cleanupComplete = true
            entry.lock.notifyAll()
        }
        return VideoHandleTerminationResult.CLEANED
    }

    fun isTerminal(key: K): Boolean {
        val entry = entry(key)
        return synchronized(entry.lock) { entry.terminal }
    }

    fun cleanupComplete(key: K): Boolean {
        val entry = entry(key)
        return synchronized(entry.lock) { entry.cleanupComplete }
    }

    /** Tombstones every handle and waits only for admitted internal work, never caller callbacks. */
    fun markAllTerminalAndAwait(timeoutMillis: Long): VideoHandleTerminationResult {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        val snapshot = synchronized(registryLock) { entries.values.toSet() }
        snapshot.forEach { entry -> synchronized(entry.lock) { entry.terminal = true } }
        val deadline = saturatedAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
        snapshot.forEach { entry ->
            synchronized(entry.lock) {
                while (entry.inFlight > 0 || entry.cleanupInProgress) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return VideoHandleTerminationResult.TIMED_OUT
                    try {
                        TimeUnit.NANOSECONDS.timedWait(entry.lock, remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return VideoHandleTerminationResult.INTERRUPTED
                    }
                }
            }
        }
        return VideoHandleTerminationResult.CLEANED
    }

    fun clear() = synchronized(registryLock) { entries.clear() }

    private fun entry(key: K): Entry = synchronized(registryLock) {
        entries.getOrPut(key, ::Entry)
    }

    private companion object {
        fun saturatedAdd(left: Long, right: Long): Long =
            if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }
}
