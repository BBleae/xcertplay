package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlaySession
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Latest stable session lifecycle state replayed across Activity detach/attach gaps. */
internal sealed interface ControllerUiSessionSnapshot {
    val revision: Long

    data class Idle(override val revision: Long) : ControllerUiSessionSnapshot

    data class Active(
        override val revision: Long,
        val session: AirPlaySession,
        val hostUiRequested: Boolean,
    ) : ControllerUiSessionSnapshot

    data class Ended(
        override val revision: Long,
        val session: AirPlaySession,
    ) : ControllerUiSessionSnapshot

    data class TransportError(
        override val revision: Long,
        val message: String,
    ) : ControllerUiSessionSnapshot
}

/**
 * Synchronized reducer only; listener callbacks are intentionally dispatched after leaving it.
 */
internal class ControllerUiSessionReplayState {
    private val lock = Any()
    private var current: ControllerUiSessionSnapshot = ControllerUiSessionSnapshot.Idle(0L)
    /** Latest accepted session remains authoritative after terminal state. */
    private var latestSession: AirPlaySession? = null
    // A delayed callback itself strongly holds its session, so weak identity membership remains
    // exact for as long as that callback can arrive without retaining every historical session.
    private val retiredSessions = Collections.newSetFromMap(
        WeakHashMap<AirPlaySession, Boolean>(),
    )

    fun snapshot(): ControllerUiSessionSnapshot = synchronized(lock) { current }

    fun onActive(session: AirPlaySession): ControllerUiSessionSnapshot? = synchronized(lock) {
        if (retiredSessions.any { it === session }) return@synchronized null
        if (latestSession === session) {
            return@synchronized (current as? ControllerUiSessionSnapshot.Active)
        }
        latestSession?.let(::retire)
        latestSession = session
        ControllerUiSessionSnapshot.Active(nextRevision(), session, hostUiRequested = false)
            .also { current = it }
    }

    fun onEnded(session: AirPlaySession): ControllerUiSessionSnapshot? = synchronized(lock) {
        if (latestSession != null && latestSession !== session) return@synchronized null
        if (latestSession == null) latestSession = session
        if (current is ControllerUiSessionSnapshot.Ended) return@synchronized null
        retire(session)
        ControllerUiSessionSnapshot.Ended(nextRevision(), session).also { current = it }
    }

    fun onTransportError(message: String): ControllerUiSessionSnapshot = synchronized(lock) {
        latestSession?.let(::retire)
        ControllerUiSessionSnapshot.TransportError(nextRevision(), message).also { current = it }
    }

    fun onHostUiRequested(session: AirPlaySession): ControllerUiSessionSnapshot? =
        synchronized(lock) {
            if (latestSession == null && current !is ControllerUiSessionSnapshot.Idle) {
                return@synchronized null
            }
            if (latestSession != null && latestSession !== session) return@synchronized null
            if (retiredSessions.any { it === session }) return@synchronized null
            val active = current as? ControllerUiSessionSnapshot.Active
            if (active == null && latestSession != null) return@synchronized null
            latestSession = session
            if (active?.session === session && active.hostUiRequested) return@synchronized active
            ControllerUiSessionSnapshot.Active(
                revision = nextRevision(),
                session = session,
                hostUiRequested = true,
            ).also { current = it }
        }

    private fun nextRevision(): Long = Math.addExact(current.revision, 1L)

    private fun retire(session: AirPlaySession) {
        retiredSessions += session
    }
}

/** Per-binding monotonic gate: live delivery and attach replay can race without duplicating state. */
internal class ControllerUiSessionReplayCursor {
    private val deliveredRevision = AtomicLong(-1L)

    /** Returns the previously delivered revision, or null when this snapshot is stale/duplicate. */
    fun acceptAfter(snapshot: ControllerUiSessionSnapshot): Long? {
        while (true) {
            val delivered = deliveredRevision.get()
            if (snapshot.revision <= delivered) return null
            if (deliveredRevision.compareAndSet(delivered, snapshot.revision)) return delivered
        }
    }

    fun accept(snapshot: ControllerUiSessionSnapshot): Boolean = acceptAfter(snapshot) != null
}

/** A skipped revision requires complete-state replay rather than a delta-only live callback. */
internal fun requiresCompleteUiSessionReplay(
    previousRevision: Long,
    snapshot: ControllerUiSessionSnapshot,
): Boolean = previousRevision < 0L || snapshot.revision != previousRevision + 1L

/** Dispatches one complete attach-time snapshot in lifecycle order. */
internal fun replayControllerUiSessionSnapshot(
    listener: com.shilapi.xcertplay.airplay.AirPlaySessionListener,
    snapshot: ControllerUiSessionSnapshot,
) {
    when (snapshot) {
        is ControllerUiSessionSnapshot.Idle -> Unit
        is ControllerUiSessionSnapshot.Active -> {
            listener.onSessionActive(snapshot.session)
            if (snapshot.hostUiRequested) listener.onHostUiRequested(snapshot.session)
        }
        is ControllerUiSessionSnapshot.Ended -> listener.onSessionEnded(snapshot.session)
        is ControllerUiSessionSnapshot.TransportError ->
            listener.onTransportError(snapshot.message)
    }
}
