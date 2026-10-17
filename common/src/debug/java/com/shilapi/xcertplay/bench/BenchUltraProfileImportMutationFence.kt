package com.shilapi.xcertplay.bench

import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

/**
 * Process-local single-writer publication fence shared by StageActivity and provider Store
 * instances. `beforePublication` runs without a Store monitor before the atomic active change.
 * A refused cover ACK leaves a retryable pending revision, but never publishes the new pointer.
 */
internal object BenchUltraProfileImportMutationFence {
    data class State(val revision: Long, val pendingMutations: Int)

    interface Observer {
        fun beforePublication(state: State): Boolean
        fun afterPublication(state: State)
    }

    private class Entry {
        var revision = 0L
        var pending = false
        val publicationLock = ReentrantLock()
        val observers = ArrayList<Observer>()
    }

    private val lock = Any()
    private val entries = HashMap<Path, Entry>()

    fun state(root: Path): State = synchronized(lock) {
        entries[normalized(root)]?.snapshot() ?: State(0L, 0)
    }

    fun observe(root: Path, observer: Observer): Closeable {
        val exactRoot = normalized(root)
        synchronized(lock) { entries.getOrPut(exactRoot, ::Entry).observers += observer }
        return Closeable {
            synchronized(lock) { entries[exactRoot]?.observers?.remove(observer) }
        }
    }

    /** Caller must close only after its atomic active pointer/signature write has finished. */
    fun begin(root: Path): Closeable {
        val exactRoot = normalized(root)
        val entry = synchronized(lock) { entries.getOrPut(exactRoot, ::Entry) }
        check(!entry.publicationLock.isHeldByCurrentThread) {
            "Recursive Debug Profile publication is forbidden"
        }
        entry.publicationLock.lock()
        val before = try {
            synchronized(lock) {
                entry.revision = Math.addExact(entry.revision, 1L)
                entry.pending = true
                entry.snapshot() to entry.observers.toList()
            }
        } catch (failure: Throwable) {
            entry.publicationLock.unlock()
            throw failure
        }
        var accepted = true
        before.second.forEach { observer ->
            if (!(try { observer.beforePublication(before.first) } catch (_: Exception) { false })) {
                accepted = false
            }
        }
        if (!accepted) {
            notifyAfter(before)
            entry.publicationLock.unlock()
            throw IllegalStateException("Old Ultra physical output cover was not acknowledged")
        }
        var finished = false
        return Closeable {
            check(entry.publicationLock.isHeldByCurrentThread) {
                "Debug Profile publication ticket closed by a different writer"
            }
            if (finished) return@Closeable
            finished = true
            val after = synchronized(lock) {
                entry.pending = false
                entry.revision = Math.addExact(entry.revision, 1L)
                entry.snapshot() to entry.observers.toList()
            }
            try { notifyAfter(after) } finally { entry.publicationLock.unlock() }
        }
    }

    private fun notifyAfter(notification: Pair<State, List<Observer>>) {
        notification.second.forEach { observer ->
            try { observer.afterPublication(notification.first) } catch (_: Exception) { Unit }
        }
    }

    private fun Entry.snapshot() = State(revision, if (pending) 1 else 0)

    private fun normalized(root: Path): Path = root.toAbsolutePath().normalize()
}
