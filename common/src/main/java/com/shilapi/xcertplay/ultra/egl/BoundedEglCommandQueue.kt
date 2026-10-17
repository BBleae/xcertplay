package com.shilapi.xcertplay.ultra.egl

import java.io.Closeable
import java.util.ArrayDeque

/** Commands are deliberately small; all Android/EGL objects remain owned by the render core. */
internal sealed interface UltraEglQueuedCommand {
    class Control(
        val action: () -> Unit,
        val onAbandoned: () -> Unit,
    ) : UltraEglQueuedCommand
    class Frame(val token: UltraEglFrameToken) : UltraEglQueuedCommand
    class Scene(
        val revision: Long,
        val action: () -> Unit,
        val onCoalesced: () -> Unit,
        val onAbandoned: () -> Unit = onCoalesced,
    ) : UltraEglQueuedCommand
}

internal data class UltraEglQueueSnapshot(
    val size: Int,
    val closed: Boolean,
    val coalescedSceneCount: Long,
    val coalescedFrameCount: Long,
    /** Distinct ingress keys awaiting one queue slot, bounded independently of command capacity. */
    val deferredFrameCount: Int = 0,
)

/**
 * Bounded MPSC queue. Frame signals are coalesced by exact ingress key. Scene replacement is only
 * permitted in the tail segment after the latest control command, preserving topology barriers.
 */
internal class BoundedEglCommandQueue(
    private val capacity: Int = DEFAULT_CAPACITY,
) : Closeable {
    private val lock = Object()
    private val commands = ArrayDeque<UltraEglQueuedCommand>()
    private val pendingFrames = LinkedHashSet<UltraEglFrameToken>()
    /** A full queue must not erase a one-shot SurfaceTexture callback. */
    private val deferredFrames = LinkedHashMap<UltraEglIngressKey, UltraEglFrameToken>()
    private var coalescibleScene: UltraEglQueuedCommand.Scene? = null
    private var closed = false
    private var coalescedSceneCount = 0L
    private var coalescedFrameCount = 0L

    init {
        require(capacity in 1..MAXIMUM_CAPACITY) {
            "EGL command capacity must be between 1 and $MAXIMUM_CAPACITY"
        }
    }

    fun offerControl(command: UltraEglQueuedCommand.Control): Boolean = synchronized(lock) {
        if (closed || commands.size == capacity) return false
        commands.addLast(command)
        // A control command is an ordering barrier. Never move a later scene ahead of it.
        coalescibleScene = null
        lock.notifyAll()
        true
    }

    fun offerFrame(token: UltraEglFrameToken): Boolean = synchronized(lock) {
        if (closed) return false
        if (token in pendingFrames) {
            coalescedFrameCount = incrementCounter(coalescedFrameCount)
            return true
        }
        val deferred = deferredFrames[token.key]
        if (deferred != null) {
            // An old-generation callback may arrive after its successor. Never roll a deferred
            // ingress back to the stale generation; the render core checks the resource too.
            if (token.generation > deferred.generation) deferredFrames[token.key] = token
            coalescedFrameCount = incrementCounter(coalescedFrameCount)
            return true
        }
        if (commands.size == capacity) {
            if (deferredFrames.size == MAXIMUM_DEFERRED_INGRESSES) return false
            deferredFrames[token.key] = token
            return true
        }
        pendingFrames.add(token)
        commands.addLast(UltraEglQueuedCommand.Frame(token))
        lock.notifyAll()
        true
    }

    fun offerScene(command: UltraEglQueuedCommand.Scene): Boolean {
        var replaced: UltraEglQueuedCommand.Scene? = null
        synchronized(lock) {
            if (closed) return false
            val existing = coalescibleScene
            if (existing != null && commands.remove(existing)) {
                commands.addLast(command)
                coalescibleScene = command
                coalescedSceneCount = incrementCounter(coalescedSceneCount)
                replaced = existing
                lock.notifyAll()
            } else {
                if (commands.size == capacity) return false
                commands.addLast(command)
                coalescibleScene = command
                lock.notifyAll()
            }
        }
        // Ticket callbacks are external from the queue and never run under its monitor.
        replaced?.onCoalesced?.invoke()
        return true
    }

    @Throws(InterruptedException::class)
    fun take(): UltraEglQueuedCommand? = synchronized(lock) {
        promoteDeferredFramesLocked()
        while (commands.isEmpty() && !closed) lock.wait()
        val command = commands.pollFirst() ?: return null
        when (command) {
            is UltraEglQueuedCommand.Frame -> pendingFrames.remove(command.token)
            is UltraEglQueuedCommand.Scene -> if (coalescibleScene === command) {
                coalescibleScene = null
            }
            is UltraEglQueuedCommand.Control -> Unit
        }
        promoteDeferredFramesLocked()
        command
    }

    private fun promoteDeferredFramesLocked() {
        while (!closed && commands.size < capacity && deferredFrames.isNotEmpty()) {
            val iterator = deferredFrames.entries.iterator()
            val entry = iterator.next()
            val token = entry.value
            iterator.remove()
            if (pendingFrames.add(token)) commands.addLast(UltraEglQueuedCommand.Frame(token))
        }
    }

    fun snapshot(): UltraEglQueueSnapshot = synchronized(lock) {
        UltraEglQueueSnapshot(
            size = commands.size,
            closed = closed,
            coalescedSceneCount = coalescedSceneCount,
            coalescedFrameCount = coalescedFrameCount,
            deferredFrameCount = deferredFrames.size,
        )
    }

    /** Cancels every command still queued after a RUNNING command becomes ambiguous. */
    fun abandonQueuedAfterUncertainty() {
        val abandoned = ArrayList<UltraEglQueuedCommand.Scene>()
        val abandonedControls = ArrayList<UltraEglQueuedCommand.Control>()
        synchronized(lock) {
            commands.forEach { command ->
                when (command) {
                    is UltraEglQueuedCommand.Control -> abandonedControls += command
                    is UltraEglQueuedCommand.Scene -> abandoned += command
                    is UltraEglQueuedCommand.Frame -> Unit
                }
            }
            commands.clear()
            pendingFrames.clear()
            deferredFrames.clear()
            coalescibleScene = null
        }
        abandonedControls.forEach { it.onAbandoned() }
        abandoned.forEach { it.onAbandoned() }
    }

    override fun close() {
        val abandoned = ArrayList<UltraEglQueuedCommand.Scene>()
        val abandonedControls = ArrayList<UltraEglQueuedCommand.Control>()
        synchronized(lock) {
            if (closed) return
            closed = true
            commands.forEach { if (it is UltraEglQueuedCommand.Scene) abandoned += it }
            commands.forEach { if (it is UltraEglQueuedCommand.Control) abandonedControls += it }
            commands.clear()
            pendingFrames.clear()
            deferredFrames.clear()
            coalescibleScene = null
            lock.notifyAll()
        }
        abandonedControls.forEach { it.onAbandoned() }
        abandoned.forEach { it.onAbandoned() }
    }

    private fun incrementCounter(value: Long): Long =
        if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAXIMUM_CAPACITY = 1_024
        /** Backend admits at most 256 live ingresses, so every live key has a dirty slot. */
        const val MAXIMUM_DEFERRED_INGRESSES = 256
    }
}
