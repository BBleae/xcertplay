package com.shilapi.xcertplay.ultra.egl

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * One exact-session, bounded, serialized bridge from the EGL owner to a non-EGL observer.
 * Overflow revokes the observer. A delayed older control completion is reported as superseded,
 * while a newer receipt remains eligible. The observer must still validate the
 * receipt against its own current authorization, output and source generations.
 */
internal class UltraEglClusterReplayOutbox(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private sealed interface Delivery {
        class Presented(val receipt: UltraEglClusterReplayReceipt) : Delivery
        class Superseded(val receipt: UltraEglClusterReplayReceipt) : Delivery
    }

    private class Subscriber(
        val onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        val onRevoked: () -> Unit,
        val onSuperseded: (UltraEglClusterReplayReceipt) -> Unit,
    ) {
        val queue = ArrayDeque<Delivery>()
        val requeuedPending = HashSet<Long>()
        lateinit var thread: Thread
        var retired = false
        var revoked = false
        var stopped = false
    }

    private val lock = Object()
    private var subscriber: Subscriber? = null
    private var closed = false
    private var latest: UltraEglClusterReplayReceipt? = null

    init {
        require(capacity in 1..MAXIMUM_CAPACITY)
    }

    /** O(1), callback-free publication from the EGL owner thread or a completed control caller. */
    fun publish(receipt: UltraEglClusterReplayReceipt) {
        synchronized(lock) {
            if (closed) return
            val prior = latest
            if (prior != null && receipt.replaySequence <= prior.replaySequence) {
                subscriber?.takeIf { !it.retired && !it.revoked }?.let { target ->
                    if (target.queue.size >= capacity) revokeLocked(target)
                    else {
                        target.queue.addLast(Delivery.Superseded(receipt))
                        lock.notifyAll()
                    }
                }
                return
            }
            latest = receipt
            val target = subscriber ?: return
            if (target.retired || target.revoked) return
            if (target.queue.size >= capacity) {
                revokeLocked(target)
            } else {
                target.queue.addLast(Delivery.Presented(receipt))
                lock.notifyAll()
            }
        }
    }

    /** Output/ingress generation changed; the old subscriber must cover and recreate its child. */
    fun revoke() {
        synchronized(lock) {
            latest = null
            subscriber?.let(::revokeLocked)
        }
    }

    fun subscribe(
        onReceipt: (UltraEglClusterReplayReceipt) -> Unit,
        onRevoked: () -> Unit,
        onSuperseded: (UltraEglClusterReplayReceipt) -> Unit = {},
    ): UltraEglClusterReplaySubscription {
        val record = synchronized(lock) {
            check(!closed && (subscriber == null || subscriber?.stopped == true)) {
                "CLUSTER replay observer is unavailable"
            }
            Subscriber(onReceipt, onRevoked, onSuperseded).also { next ->
                subscriber = next
                latest?.let { next.queue.addLast(Delivery.Presented(it)) }
                next.thread = Thread({ dispatch(next) }, "CarPlay-Ultra-CLUSTER-replay").apply {
                    isDaemon = true
                    start()
                }
            }
        }
        return UltraEglClusterReplaySubscription(
            retirement = { timeoutMillis -> retireSubscriber(record, timeoutMillis) },
            requeueAction = { receipt -> requeue(record, receipt) },
            revokeAction = { revokeSubscriber(record) },
        )
    }

    private fun revokeSubscriber(record: Subscriber): Boolean = synchronized(lock) {
        if (closed || subscriber !== record || record.retired) return false
        revokeLocked(record)
        true
    }

    /** One bounded continuation after a coordinator operation commits; never a caller callback. */
    private fun requeue(record: Subscriber, receipt: UltraEglClusterReplayReceipt): Boolean =
        synchronized(lock) {
            if (closed || subscriber !== record || record.retired || record.revoked) return false
            if (!record.requeuedPending.add(receipt.replaySequence)) return true
            if (record.queue.size >= capacity) {
                record.requeuedPending.remove(receipt.replaySequence)
                revokeLocked(record)
                return false
            }
            record.queue.addLast(Delivery.Presented(receipt))
            lock.notifyAll()
            true
        }

    /** No observer callback may begin after a successful return. Retry when a callback is stuck. */
    fun retire(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 0L..MAXIMUM_RETIRE_TIMEOUT_MILLIS)
        val record = synchronized(lock) {
            closed = true
            subscriber?.also {
                it.retired = true
                it.queue.clear()
                it.requeuedPending.clear()
                lock.notifyAll()
            }
        } ?: return true
        val stopped = awaitStopped(record, timeoutMillis)
        if (stopped) synchronized(lock) {
            if (subscriber === record) {
                subscriber = null
                latest = null
            }
        }
        return stopped
    }

    private fun retireSubscriber(record: Subscriber, timeoutMillis: Long): Boolean {
        require(timeoutMillis in 0L..MAXIMUM_RETIRE_TIMEOUT_MILLIS)
        synchronized(lock) {
            if (subscriber !== record) return true
            record.retired = true
            record.queue.clear()
            record.requeuedPending.clear()
            lock.notifyAll()
        }
        val stopped = awaitStopped(record, timeoutMillis)
        if (stopped) synchronized(lock) {
            if (subscriber === record) {
                subscriber = null
                latest = null
            }
        }
        return stopped
    }

    private fun awaitStopped(record: Subscriber, timeoutMillis: Long): Boolean {
        if (Thread.currentThread() === record.thread) return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (!record.stopped) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    private fun revokeLocked(record: Subscriber) {
        record.revoked = true
        record.queue.clear()
        record.requeuedPending.clear()
        lock.notifyAll()
    }

    private fun dispatch(record: Subscriber) {
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (!record.retired && !record.revoked && record.queue.isEmpty()) {
                        lock.wait()
                    }
                    if (record.retired || record.revoked) null else
                        record.queue.removeFirst().also { delivery ->
                            if (delivery is Delivery.Presented) {
                                record.requeuedPending.remove(delivery.receipt.replaySequence)
                            }
                        }
                }
                if (next == null) {
                    if (synchronized(lock) { record.revoked && !record.retired }) {
                        runCatching(record.onRevoked)
                    }
                    return
                }
                try {
                    when (next) {
                        is Delivery.Presented -> record.onReceipt(next.receipt)
                        is Delivery.Superseded -> record.onSuperseded(next.receipt)
                    }
                } catch (_: Throwable) {
                    synchronized(lock) { revokeLocked(record) }
                }
            }
        } catch (_: InterruptedException) {
            synchronized(lock) { if (!record.retired) revokeLocked(record) }
            if (synchronized(lock) { record.revoked && !record.retired }) {
                runCatching(record.onRevoked)
            }
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) {
                if (record.revoked && subscriber === record) latest = null
                record.stopped = true
                lock.notifyAll()
            }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val MAXIMUM_CAPACITY = 256
        const val DEFAULT_RETIRE_TIMEOUT_MILLIS = 1_000L
        const val MAXIMUM_RETIRE_TIMEOUT_MILLIS = 60_000L
    }

}

internal class UltraEglClusterReplaySubscription internal constructor(
    private val retirement: (Long) -> Boolean,
    private val requeueAction: (UltraEglClusterReplayReceipt) -> Boolean,
    private val revokeAction: () -> Boolean,
) : Closeable {
    internal constructor(retirement: (Long) -> Boolean) : this(retirement, { false }, { false })
    internal constructor(
        retirement: (Long) -> Boolean,
        requeueAction: (UltraEglClusterReplayReceipt) -> Boolean,
    ) : this(retirement, requeueAction, { false })

    fun retire(timeoutMillis: Long = UltraEglClusterReplayOutbox.DEFAULT_RETIRE_TIMEOUT_MILLIS):
        Boolean = retirement(timeoutMillis)

    fun requeue(receipt: UltraEglClusterReplayReceipt): Boolean = requeueAction(receipt)

    fun revoke(): Boolean = revokeAction()

    override fun close() {
        check(retire()) { "CLUSTER replay callback is still in flight" }
    }
}
