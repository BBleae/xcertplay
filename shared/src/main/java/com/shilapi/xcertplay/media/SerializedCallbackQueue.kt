package com.shilapi.xcertplay.media

import java.util.ArrayDeque

/**
 * Reservation-ordered callback queue. Actions run serially without holding the queue lock.
 *
 * Reserving while another callback is active appends behind it. [Task.run] normally waits for its
 * own action, preserving backpressure. A reentrant call from the active callback returns and lets
 * the outer drain execute the nested task next, avoiding self-deadlock.
 */
internal class SerializedCallbackQueue {
    class Task internal constructor(
        private val owner: SerializedCallbackQueue,
        internal val action: () -> Unit,
    ) {
        @Volatile internal var completed = false
        internal var failure: Throwable? = null

        fun run() = owner.run(this)
    }

    private val lock = Object()
    private val pending = ArrayDeque<Task>()
    private var drainer: Thread? = null

    fun reserve(action: () -> Unit): Task {
        val task = Task(this, action)
        synchronized(lock) { pending.addLast(task) }
        return task
    }

    private fun run(task: Task) {
        var shouldDrain = false
        synchronized(lock) {
            if (!task.completed && drainer == null) {
                drainer = Thread.currentThread()
                shouldDrain = true
            } else if (!task.completed && drainer === Thread.currentThread()) {
                // Reentrant callback: the active outer drain owns this queued task.
                return
            }
        }
        if (shouldDrain) drain()

        var interrupted = false
        synchronized(lock) {
            while (!task.completed) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    // The callback was already reserved and must not be abandoned out of order.
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        task.failure?.let { throw it }
    }

    private fun drain() {
        while (true) {
            val task = synchronized(lock) {
                pending.peekFirst() ?: run {
                    drainer = null
                    lock.notifyAll()
                    return
                }
            }
            val failure = try {
                task.action()
                null
            } catch (error: Throwable) {
                error
            }
            synchronized(lock) {
                check(pending.removeFirst() === task)
                task.failure = failure
                task.completed = true
                lock.notifyAll()
            }
        }
    }
}

/** Selection/config/frame gate built on reservation order rather than callback-under-lock. */
internal class OrderedVideoCallbackGate<H : Any, C : Any> {
    private val callbacks = SerializedCallbackQueue()
    private var desired: H? = null
    private var selected: H? = null
    private var configured = false

    fun reserveSelection(
        handle: H?,
        config: C?,
        publishConfig: (C) -> Unit,
    ): SerializedCallbackQueue.Task = callbacks.reserve {
        // Fail closed until the new config callback completed. Later frames are queued behind this
        // task, while an exception leaves the gate unselected.
        desired = handle
        selected = null
        configured = false
        if (handle != null && config != null) {
            publishConfig(config)
            configured = true
        }
        selected = handle
    }

    fun reserveConfig(
        handle: H,
        config: C,
        publish: (C) -> Unit,
    ): SerializedCallbackQueue.Task = callbacks.reserve {
        if (selected === handle || desired === handle) {
            // A replacement config is not usable until its callback returns successfully. If the
            // callback throws, queued frames remain fail-closed instead of using the old config.
            selected = null
            configured = false
            publish(config)
            desired = handle
            selected = handle
            configured = true
        }
    }

    fun reserveFrame(handle: H, publish: () -> Unit): SerializedCallbackQueue.Task =
        callbacks.reserve {
            if (selected === handle && configured) publish()
        }
}
