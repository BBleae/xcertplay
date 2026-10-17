package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS
import com.shilapi.xcertplay.airplay.SerializedUiSyncOutbound
import com.shilapi.xcertplay.airplay.UltraDebugStatusEvent
import com.shilapi.xcertplay.airplay.UltraDebugStatusMarkers
import java.io.Closeable
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A renderer may offer an explicitly selected layout for its own provisioned AirPlay session.
 * Obtaining this port does not select a layout or claim that any scene is in the foreground.
 */
interface UiSyncForegroundRequestPort {
    /** Binds one physical scene/revision/batch to this exact session and display. */
    fun guardScene(
        displayId: String,
        source: UiSyncForegroundSceneUseSource,
    ): UiSyncForegroundSceneGuard?

    fun requestLayout(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand.RequestLayout,
        scene: UiSyncForegroundSceneGuard,
    ): UiSyncForegroundRequestReceipt

    /** Opaque exact-session/READY epoch, or null if this display is not currently READY. */
    fun currentReadyGeneration(displayId: String): UiSyncForegroundReadyGeneration? = null
}

/** Identity token, not a counter: epochs from different sessions can never compare equal. */
class UiSyncForegroundReadyGeneration internal constructor() {
    override fun toString(): String = "UiSyncForegroundReadyGeneration(<opaque>)"
}

/**
 * Implemented by the renderer's exact published scene, not by a global "foreground" flag. A
 * replacement scene, revision, or presentation batch must revoke the old source permanently.
 * Revocation must invoke [onRevoked] even if a transport write is currently blocked.
 */
fun interface UiSyncForegroundSceneUseSource {
    fun acquireUse(onRevoked: () -> Unit): UiSyncForegroundSceneUse?
}

/** A short-lived lease on the same scene/revision/batch from which a request was selected. */
interface UiSyncForegroundSceneUse : Closeable {
    fun isCurrent(): Boolean
}

/** An opaque capability minted by one exact session port, never transferable to another. */
class UiSyncForegroundSceneGuard internal constructor(
    internal val owner: UiSyncForegroundRequestPort,
    val displayId: String,
    internal val source: UiSyncForegroundSceneUseSource,
)

/** A transport write is not an iPhone acknowledgement or a foreground-presentation proof. */
enum class UiSyncForegroundRequestState {
    QUEUED,
    SENT_UNCONFIRMED,
    WRITE_UNCERTAIN,
    REJECTED,
    CANCELLED,
}

class UiSyncForegroundRequestReceipt internal constructor(
    initialState: UiSyncForegroundRequestState,
) {
    @Volatile
    var state: UiSyncForegroundRequestState = initialState
        internal set

    /** Non-null only after this exact request's carrier was written without local uncertainty. */
    @Volatile
    var sentGeneration: UiSyncForegroundReadyGeneration? = null
        internal set
}

/** Implemented by the exact provisioned session; no global active-session lookup is permitted. */
internal enum class UiSyncForegroundUseStatus { CURRENT, BUSY, REVOKED }

internal interface UiSyncForegroundRequestSessionAccess {
    fun acquireUse(): Closeable?
    fun isLive(): Boolean
    /** The default preserves the legacy Boolean behavior; BUSY must be asserted explicitly. */
    fun foregroundUseStatus(): UiSyncForegroundUseStatus =
        if (isLive()) UiSyncForegroundUseStatus.CURRENT else UiSyncForegroundUseStatus.REVOKED
}

/**
 * Exact-session, bounded RequestLayout outbox. The only channel authority accepted here is the
 * session's serialized ClusterControl sender. Its router snapshot, not a renderer hint or an ACK,
 * determines which display is READY. Pending requests never cross a channel generation.
 *
 * The renderer must supply its exact published scene/revision lease before enqueueing a request.
 */
internal class UiSyncForegroundRequestOutbox(
    displayIds: Collection<String>,
) : UiSyncForegroundRequestPort, Closeable {
    private class Pending(
        val displayId: String,
        val command: CarPlayUiSyncAccessoryCommand.RequestLayout,
        val scene: UiSyncForegroundSceneGuard,
        val receipt: UiSyncForegroundRequestReceipt,
    ) {
        // Protected by the outbox monitor. The queued use keeps an exact scene revocation
        // callback registered until this request is sent, cancelled, or rejected.
        var queueActive = true
        var sceneRevoked = false
        var queuedSceneUse: UiSyncForegroundSceneUse? = null
    }

    private data class Candidate(
        val sender: SerializedUiSyncOutbound,
        val generation: Long,
        val readyGeneration: UiSyncForegroundReadyGeneration,
        val session: UiSyncForegroundRequestSessionAccess,
        val pending: Pending,
        val abortTransport: () -> Unit,
    ) {
        // Accessed only while holding the outbox monitor.
        var sendStarted = false
        var sceneRevoked = false
        /** The exact carrier write returned normally; nothing later can make it partial. */
        var writeReturned = false
        /** The scene was revoked only after [writeReturned] (e.g. by its own phone echo). */
        var revokedAfterWrite = false
    }

    private val lock = Any()
    private val displayIds = boundedDisplayIds(displayIds)
    private val pending = ArrayDeque<Pending>()
    private val retiredSceneUses = ArrayDeque<UiSyncForegroundSceneUse>()
    private var session: UiSyncForegroundRequestSessionAccess? = null
    private var channel: SerializedUiSyncOutbound? = null
    private var channelAbort: (() -> Unit)? = null
    private var inFlight: Candidate? = null
    private var readyDisplayIds: Set<String> = emptySet()
    private val readyGenerations = HashMap<String, UiSyncForegroundReadyGeneration>()
    private var generation = 0L
    private var writeFailed = false
    private var draining = false
    private var retryScheduled = false
    private var retryFuture: ScheduledFuture<*>? = null
    private var closed = false
    @Volatile private var debugStatus: (UltraDebugStatusEvent, String?) -> Unit = { _, _ -> }

    init {
        require(this.displayIds.isNotEmpty()) { "UI Sync foreground outbox has no displays" }
    }

    internal fun setDebugStatusObserver(observer: (UltraDebugStatusEvent, String?) -> Unit) {
        if (UltraDebugStatusMarkers.enabled) debugStatus = observer
    }

    internal fun bindSession(exactSession: UiSyncForegroundRequestSessionAccess) =
        synchronized(lock) {
            check(!closed && session == null) {
                "UI Sync foreground outbox already has a session or was closed"
            }
            session = exactSession
        }

    override fun currentReadyGeneration(displayId: String): UiSyncForegroundReadyGeneration? {
        require(displayId in displayIds) { "UI Sync foreground generation names another display" }
        return synchronized(lock) {
            if (closed || writeFailed || channel == null) null
            else readyGenerations[displayId]
        }
    }

    override fun guardScene(
        displayId: String,
        source: UiSyncForegroundSceneUseSource,
    ): UiSyncForegroundSceneGuard? {
        require(displayId in displayIds) { "UI Sync foreground scene names another display" }
        val exactSession = synchronized(lock) { session } ?: return null
        val attempt = acquireSessionUse(exactSession)
        val use = attempt.use ?: run {
            if (attempt.status == UiSyncForegroundUseStatus.REVOKED) {
                cancelIfRevoked(exactSession)
            }
            return null
        }
        return try {
            synchronized(lock) {
                if (closed || session !== exactSession) null
                else UiSyncForegroundSceneGuard(this, displayId, source)
            }
        } finally {
            use.close()
        }
    }

    override fun requestLayout(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand.RequestLayout,
        scene: UiSyncForegroundSceneGuard,
    ): UiSyncForegroundRequestReceipt {
        require(displayId in displayIds) { "UI Sync foreground request names another display" }
        if (scene.owner !== this || scene.displayId != displayId) {
            return UiSyncForegroundRequestReceipt(UiSyncForegroundRequestState.REJECTED)
        }
        val exactSession = synchronized(lock) { session }
            ?: return UiSyncForegroundRequestReceipt(UiSyncForegroundRequestState.REJECTED)
        val attempt = acquireSessionUse(exactSession)
        val use = attempt.use ?: run {
            if (attempt.status == UiSyncForegroundUseStatus.REVOKED) {
                cancelIfRevoked(exactSession)
            }
            return UiSyncForegroundRequestReceipt(UiSyncForegroundRequestState.REJECTED)
        }
        val receipt = try {
            // Scene code is never called while holding the outbox monitor. A scene use is a
            // revocable subscription, not a one-time boolean supplied by the renderer. Register
            // before enqueue so a revoke racing acquisition cannot leave a stale queued row.
            val queued = Pending(
                displayId, command, scene,
                UiSyncForegroundRequestReceipt(UiSyncForegroundRequestState.QUEUED),
            )
            val sceneUse = acquireCurrentSceneUse(scene) { cancelPendingScene(queued) }
            if (sceneUse == null) {
                synchronized(lock) {
                    queued.queueActive = false
                    queued.receipt.state = if (queued.sceneRevoked) {
                        UiSyncForegroundRequestState.CANCELLED
                    } else {
                        UiSyncForegroundRequestState.REJECTED
                    }
                }
                queued.receipt
            } else {
                synchronized(lock) {
                    if (queued.sceneRevoked) {
                        queued.queueActive = false
                        queued.receipt.state = UiSyncForegroundRequestState.CANCELLED
                        retiredSceneUses.addLast(sceneUse)
                    } else if (closed || session !== exactSession ||
                        pending.size >= MAXIMUM_PENDING_REQUESTS
                    ) {
                        queued.queueActive = false
                        queued.receipt.state = UiSyncForegroundRequestState.REJECTED
                        retiredSceneUses.addLast(sceneUse)
                    } else {
                        queued.queuedSceneUse = sceneUse
                        pending.addLast(queued)
                    }
                }
                queued.receipt
            }
        } finally {
            use.close()
        }
        closeRetiredSceneUses()
        if (receipt.state == UiSyncForegroundRequestState.QUEUED) {
            debugStatus(UltraDebugStatusEvent.RAW5_QUEUED, displayId)
            drainReady()
        }
        return receipt
    }

    /** Binds exactly one physical ClusterControl generation after its RCS open succeeded. */
    internal fun attach(
        outbound: SerializedUiSyncOutbound,
        abortTransport: () -> Unit = { },
    ): Attachment {
        val attachment = synchronized(lock) {
            check(!closed && channel == null && session != null) {
                "UI Sync foreground outbox has no session, already has a channel, or was closed"
            }
            generation = Math.addExact(generation, 1)
            cancelBusyRetryLocked()
            val token = generation
            channel = outbound
            channelAbort = abortTransport
            setReadyDisplaysLocked(emptySet())
            writeFailed = false
            Attachment(token)
        }
        attachment.refresh()
        return attachment
    }

    inner class Attachment internal constructor(private val token: Long) : Closeable {
        /** Re-reads the exact router after an inbound carrier changed its per-display state. */
        fun refresh() {
            val sender = synchronized(lock) {
                if (closed || generation != token) null else channel
            } ?: return
            val ready = try {
                sender.readyUiSyncDisplayIds().intersect(displayIds)
            } catch (failure: Throwable) {
                synchronized(lock) {
                    if (!closed && generation == token && channel === sender) {
                        writeFailed = true
                        setReadyDisplaysLocked(emptySet())
                        cancelPendingLocked()
                    }
                }
                closeRetiredSceneUses()
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                return
            }
            synchronized(lock) {
                if (!closed && generation == token && channel === sender && !writeFailed) {
                    setReadyDisplaysLocked(ready)
                }
            }
            drainReady()
        }

        override fun close() {
            val abort = synchronized(lock) {
                if (closed || generation != token) return@synchronized null
                val abort = inFlight?.takeIf { it.generation == token }?.abortTransport
                generation = Math.addExact(generation, 1)
                cancelBusyRetryLocked()
                channel = null
                channelAbort = null
                setReadyDisplaysLocked(emptySet())
                writeFailed = false
                cancelPendingLocked()
                abort
            }
            closeRetiredSceneUses()
            // Closing the RCS socket interrupts a blocked write without waiting under our
            // monitor; a later write result remains explicitly unconfirmed/uncertain.
            abort?.invoke()
        }
    }

    override fun close() {
        val abort = synchronized(lock) {
            if (closed) return@synchronized null
            val abort = inFlight?.abortTransport
            closed = true
            generation = Math.addExact(generation, 1)
            cancelBusyRetryLocked()
            channel = null
            channelAbort = null
            session = null
            setReadyDisplaysLocked(emptySet())
            writeFailed = false
            cancelPendingLocked()
            abort
        }
        closeRetiredSceneUses()
        abort?.invoke()
    }

    private fun drainReady() {
        synchronized(lock) {
            if (closed || writeFailed || draining) return
            draining = true
        }
        var deferredByBusy = false
        try {
            while (true) {
                val candidate = synchronized(lock) {
                    if (closed || writeFailed) return
                    val sender = channel ?: return
                    val owner = session ?: return
                    // An unready display cannot block another display on the shared RCS stream;
                    // scanning from the head preserves each display's request order.
                    val next = pending.firstOrNull { it.displayId in readyDisplayIds } ?: return
                    val readyGeneration = readyGenerations[next.displayId] ?: return
                    Candidate(
                        sender, generation, readyGeneration, owner, next,
                        channelAbort ?: { },
                    )
                }
                // Never call renderer/physical-output liveness while holding our monitor. Session
                // close takes the outbox monitor first, then retires the renderer and outputs.
                val attempt = acquireSessionUse(candidate.session)
                val use = attempt.use ?: run {
                    if (attempt.status == UiSyncForegroundUseStatus.BUSY) deferredByBusy = true
                    else cancelIfRevoked(candidate.session)
                    return
                }
                try {
                    if (attempt.status == UiSyncForegroundUseStatus.BUSY) {
                        deferredByBusy = true
                        return
                    }
                    val sceneUse = acquireCurrentSceneUse(candidate.pending.scene) {
                        revokeInFlightScene(candidate)
                    }
                    if (sceneUse == null) {
                        cancelPendingScene(candidate.pending)
                        continue
                    }
                    try {
                    val reserved = synchronized(lock) {
                        if (closed || writeFailed || session !== candidate.session ||
                            channel !== candidate.sender || generation != candidate.generation ||
                            candidate.pending !in pending ||
                            readyGenerations[candidate.pending.displayId] !==
                                candidate.readyGeneration
                        ) {
                            false
                        } else {
                            pending.remove(candidate.pending)
                            // Retain the original scene watcher until a write actually starts.
                            // A BUSY result before that point restores this exact queued row.
                            inFlight = candidate
                            true
                        }
                    }
                    if (!reserved) continue
                    closeRetiredSceneUses()
                    when (sessionUseStatus(candidate.session)) {
                        UiSyncForegroundUseStatus.BUSY -> {
                            restoreReservedOnBusy(candidate)
                            deferredByBusy = true
                            return
                        }
                        UiSyncForegroundUseStatus.REVOKED -> {
                            cancelReservedSession(candidate)
                            cancelIfRevoked(candidate.session)
                            return
                        }
                        UiSyncForegroundUseStatus.CURRENT -> Unit
                    }
                    if (!isSceneCurrent(sceneUse)) {
                        cancelReservedScene(candidate)
                        continue
                    }
                    // Detach can win after reservation. This final exact-generation check is
                    // adjacent to, but not held across, the physical socket write. Scene
                    // revocation and detach both abort that socket if the write races them.
                    val current = synchronized(lock) {
                        !closed && !writeFailed && inFlight === candidate &&
                            session === candidate.session && channel === candidate.sender &&
                            generation == candidate.generation &&
                            readyGenerations[candidate.pending.displayId] ===
                                candidate.readyGeneration
                    }
                    if (!current) {
                        restoreReservedOnBusy(candidate)
                        continue
                    }
                    if (!isSceneCurrent(sceneUse)) {
                        cancelReservedScene(candidate)
                        continue
                    }
                    // An EGL/route operation can begin after reservation. Recheck before the
                    // irreversible socket write; BUSY is not a revocation or a replay grant.
                    when (sessionUseStatus(candidate.session)) {
                        UiSyncForegroundUseStatus.BUSY -> {
                            restoreReservedOnBusy(candidate)
                            deferredByBusy = true
                            return
                        }
                        UiSyncForegroundUseStatus.REVOKED -> {
                            cancelReservedSession(candidate)
                            cancelIfRevoked(candidate.session)
                            return
                        }
                        UiSyncForegroundUseStatus.CURRENT -> Unit
                    }
                    val mayWrite = synchronized(lock) {
                        if (closed || writeFailed || inFlight !== candidate ||
                            session !== candidate.session || channel !== candidate.sender ||
                            generation != candidate.generation || candidate.sceneRevoked ||
                            readyGenerations[candidate.pending.displayId] !==
                                candidate.readyGeneration
                        ) {
                            false
                        } else {
                            // From this point a racing revocation cannot prove that no byte
                            // escaped. Its callback aborts transport and marks uncertainty.
                            candidate.sendStarted = true
                            true
                        }
                    }
                    if (!mayWrite) {
                        restoreReservedOnBusy(candidate)
                        continue
                    }
                    val result = runCatching {
                        candidate.sender.sendCommand(
                            candidate.pending.displayId,
                            candidate.pending.command,
                        )
                    }
                    // Mark completion before probing the scene: a revocation that arrives after
                    // this point (the phone's own echo may revoke the raw5 scene) cannot have
                    // interrupted the write, so it must not abort the transport or mark it
                    // uncertain. A revocation during the write still does both.
                    if (result.isSuccess) synchronized(lock) { candidate.writeReturned = true }
                    val sceneCurrentAfterWrite = isSceneCurrent(sceneUse)
                    val (abort, terminalState) = synchronized(lock) {
                        if (inFlight === candidate) inFlight = null
                        candidate.pending.queueActive = false
                        retireQueuedSceneUseLocked(candidate.pending)
                        val exactTransportStillAttached = !closed && !writeFailed &&
                            generation == candidate.generation && channel === candidate.sender
                        val revokedOnlyAfterWrite = candidate.revokedAfterWrite
                        val terminalState = if (!exactTransportStillAttached ||
                            (candidate.sceneRevoked && !revokedOnlyAfterWrite) ||
                            (!sceneCurrentAfterWrite && !revokedOnlyAfterWrite)
                        ) {
                            UiSyncForegroundRequestState.WRITE_UNCERTAIN
                        } else {
                            result.fold(
                                onSuccess = { actions ->
                                    when {
                                        actions.outboundCarriers.isNotEmpty() &&
                                            actions.diagnostics.isEmpty() ->
                                            UiSyncForegroundRequestState.SENT_UNCONFIRMED
                                        actions.outboundCarriers.isEmpty() ->
                                            UiSyncForegroundRequestState.REJECTED
                                        else -> UiSyncForegroundRequestState.WRITE_UNCERTAIN
                                    }
                                },
                                onFailure = { UiSyncForegroundRequestState.WRITE_UNCERTAIN },
                            )
                        }
                        candidate.pending.receipt.sentGeneration = if (terminalState ==
                            UiSyncForegroundRequestState.SENT_UNCONFIRMED
                        ) candidate.readyGeneration else null
                        candidate.pending.receipt.state = terminalState
                        if (candidate.pending.receipt.state ==
                            UiSyncForegroundRequestState.WRITE_UNCERTAIN &&
                            generation == candidate.generation &&
                            channel === candidate.sender
                        ) {
                            writeFailed = true
                            setReadyDisplaysLocked(emptySet())
                            cancelPendingLocked()
                        }
                        val abort = if (!sceneCurrentAfterWrite && !candidate.sceneRevoked &&
                            generation == candidate.generation && channel === candidate.sender
                        ) candidate.abortTransport else null
                        abort to terminalState
                    }
                    when (terminalState) {
                        UiSyncForegroundRequestState.REJECTED ->
                            debugStatus(UltraDebugStatusEvent.RAW5_REJECTED,
                                candidate.pending.displayId)
                        UiSyncForegroundRequestState.WRITE_UNCERTAIN ->
                            debugStatus(UltraDebugStatusEvent.RAW5_WRITE_UNCERTAIN,
                                candidate.pending.displayId)
                        else -> Unit
                    }
                    abort?.invoke()
                    closeRetiredSceneUses()
                    result.exceptionOrNull()?.let { failure ->
                        if (failure is InterruptedException) Thread.currentThread().interrupt()
                        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                    }
                    } finally {
                        sceneUse.close()
                    }
                } finally {
                    use.close()
                }
                val stillAttached = synchronized(lock) {
                    !closed && !writeFailed && generation == candidate.generation &&
                        channel === candidate.sender
                }
                if (!stillAttached) continue
                // The router can reset or reject a display while sending; do not trust a stale
                // readiness cache for the next queued request. A failed read cannot undo the
                // already-completed transport write or upgrade it to an ACK.
                val ready = try {
                    candidate.sender.readyUiSyncDisplayIds().intersect(displayIds)
                } catch (failure: Throwable) {
                    synchronized(lock) {
                        if (generation == candidate.generation && channel === candidate.sender) {
                            writeFailed = true
                            setReadyDisplaysLocked(emptySet())
                            cancelPendingLocked()
                        }
                    }
                    closeRetiredSceneUses()
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                    return
                }
                synchronized(lock) {
                    if (generation == candidate.generation && channel === candidate.sender &&
                        !writeFailed
                    ) {
                        setReadyDisplaysLocked(ready)
                    }
                }
            }
        } finally {
            val restart = synchronized(lock) {
                draining = false
                !deferredByBusy && !closed && !writeFailed && channel != null && session != null &&
                    pending.any { it.displayId in readyDisplayIds }
            }
            closeRetiredSceneUses()
            if (deferredByBusy) scheduleBusyRetry()
            else if (restart) drainReady()
        }
    }

    private data class SessionUseAttempt(
        val use: Closeable?,
        val status: UiSyncForegroundUseStatus,
    )

    private fun acquireSessionUse(owner: UiSyncForegroundRequestSessionAccess): SessionUseAttempt {
        val use = try {
            owner.acquireUse()
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            return SessionUseAttempt(null, UiSyncForegroundUseStatus.REVOKED)
        }
        val status = sessionUseStatus(owner)
        if (use == null) {
            // A claimed CURRENT session that cannot issue its use lease is not usable.
            return SessionUseAttempt(null, if (status == UiSyncForegroundUseStatus.CURRENT) {
                UiSyncForegroundUseStatus.REVOKED
            } else status)
        }
        if (status == UiSyncForegroundUseStatus.REVOKED) {
            use.close()
            return SessionUseAttempt(null, status)
        }
        return SessionUseAttempt(use, status)
    }

    private fun sessionUseStatus(
        owner: UiSyncForegroundRequestSessionAccess,
    ): UiSyncForegroundUseStatus = try {
        owner.foregroundUseStatus()
    } catch (failure: Throwable) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        UiSyncForegroundUseStatus.REVOKED
    }

    private fun acquireCurrentSceneUse(
        scene: UiSyncForegroundSceneGuard,
        onRevoked: () -> Unit,
    ): UiSyncForegroundSceneUse? {
        val use = try {
            scene.source.acquireUse(onRevoked)
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            null
        } ?: return null
        if (!isSceneCurrent(use)) {
            use.close()
            return null
        }
        return use
    }

    private fun isSceneCurrent(use: UiSyncForegroundSceneUse): Boolean = try {
        use.isCurrent()
    } catch (failure: Throwable) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
        false
    }

    private fun cancelPendingScene(request: Pending) {
        val abort = synchronized(lock) {
            if (!request.queueActive) return@synchronized null
            request.sceneRevoked = true
            if (pending.remove(request)) {
                request.queueActive = false
                request.receipt.state = UiSyncForegroundRequestState.CANCELLED
                retireQueuedSceneUseLocked(request)
                null
            } else {
                val candidate = inFlight?.takeIf { it.pending === request }
                    ?: return@synchronized null
                candidate.sceneRevoked = true
                if (candidate.writeReturned) {
                    // The complete write already returned; the write path settles the receipt.
                    candidate.revokedAfterWrite = true
                    return@synchronized null
                }
                request.queueActive = false
                retireQueuedSceneUseLocked(request)
                if (candidate.sendStarted) {
                    request.receipt.state = UiSyncForegroundRequestState.WRITE_UNCERTAIN
                    if (generation == candidate.generation && channel === candidate.sender) {
                        writeFailed = true
                        setReadyDisplaysLocked(emptySet())
                        cancelPendingLocked()
                        candidate.abortTransport
                    } else null
                } else {
                    inFlight = null
                    request.receipt.state = UiSyncForegroundRequestState.CANCELLED
                    null
                }
            }
        }
        abort?.invoke()
        closeRetiredSceneUses()
    }

    private fun cancelReservedScene(candidate: Candidate) = synchronized(lock) {
        if (inFlight === candidate && !candidate.sendStarted) {
            inFlight = null
            candidate.sceneRevoked = true
            candidate.pending.queueActive = false
            retireQueuedSceneUseLocked(candidate.pending)
            candidate.pending.receipt.state = UiSyncForegroundRequestState.CANCELLED
        }
    }

    private fun cancelReservedSession(candidate: Candidate) = synchronized(lock) {
        if (inFlight === candidate && !candidate.sendStarted) {
            inFlight = null
            candidate.pending.queueActive = false
            retireQueuedSceneUseLocked(candidate.pending)
            candidate.pending.receipt.state = UiSyncForegroundRequestState.CANCELLED
        }
    }

    private fun restoreReservedOnBusy(candidate: Candidate) = synchronized(lock) {
        if (inFlight !== candidate || candidate.sendStarted) return@synchronized
        inFlight = null
        if (!closed && !writeFailed && !candidate.sceneRevoked &&
            session === candidate.session && channel === candidate.sender &&
            generation == candidate.generation
        ) {
            pending.addFirst(candidate.pending)
        } else {
            candidate.pending.queueActive = false
            retireQueuedSceneUseLocked(candidate.pending)
            candidate.pending.receipt.state = UiSyncForegroundRequestState.CANCELLED
        }
    }

    private fun revokeInFlightScene(candidate: Candidate) {
        val abort = synchronized(lock) {
            if (inFlight !== candidate || candidate.sceneRevoked) return@synchronized null
            candidate.sceneRevoked = true
            if (candidate.writeReturned) {
                // The complete write already returned; the write path settles the receipt.
                candidate.revokedAfterWrite = true
                return@synchronized null
            }
            if (candidate.sendStarted) {
                candidate.pending.receipt.state = UiSyncForegroundRequestState.WRITE_UNCERTAIN
                if (generation == candidate.generation && channel === candidate.sender) {
                    writeFailed = true
                    setReadyDisplaysLocked(emptySet())
                    cancelPendingLocked()
                    candidate.abortTransport
                } else null
            } else {
                inFlight = null
                candidate.pending.queueActive = false
                retireQueuedSceneUseLocked(candidate.pending)
                candidate.pending.receipt.state = UiSyncForegroundRequestState.CANCELLED
                null
            }
        }
        // Never call transport or renderer while holding the outbox monitor.
        abort?.invoke()
        closeRetiredSceneUses()
    }

    private fun cancelIfRevoked(owner: UiSyncForegroundRequestSessionAccess) {
        synchronized(lock) {
            if (!closed && session === owner) {
                if (channel != null) writeFailed = true
                setReadyDisplaysLocked(emptySet())
                cancelPendingLocked()
            }
        }
        closeRetiredSceneUses()
    }

    /** One deferred probe per exact channel generation; no renderer/backend call under [lock]. */
    private fun scheduleBusyRetry() {
        val token = synchronized(lock) {
            if (closed || writeFailed || retryScheduled || channel == null || session == null ||
                pending.none { it.displayId in readyDisplayIds }
            ) return
            retryScheduled = true
            generation
        }
        val task = try {
            BUSY_RETRY_EXECUTOR.schedule({
                val shouldRetry = synchronized(lock) {
                    if (!retryScheduled || closed || generation != token) false
                    else {
                        retryScheduled = false
                        retryFuture = null
                        true
                    }
                }
                if (shouldRetry) drainReady()
            }, BUSY_RETRY_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (generation == token) retryScheduled = false
            }
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            return
        }
        synchronized(lock) {
            if (!closed && retryScheduled && generation == token) retryFuture = task
            else task.cancel(false)
        }
    }

    private fun cancelBusyRetryLocked() {
        retryScheduled = false
        retryFuture?.cancel(false)
        retryFuture = null
    }

    /** Caller holds [lock]; a READY transition mints an epoch local to this exact outbox. */
    private fun setReadyDisplaysLocked(ready: Set<String>) {
        readyGenerations.keys.retainAll(ready)
        ready.forEach { displayId ->
            if (displayId !in readyGenerations) {
                readyGenerations[displayId] = UiSyncForegroundReadyGeneration()
            }
        }
        readyDisplayIds = ready
    }

    private fun cancelPendingLocked() {
        while (pending.isNotEmpty()) {
            val request = pending.removeFirst()
            request.queueActive = false
            request.receipt.state = UiSyncForegroundRequestState.CANCELLED
            retireQueuedSceneUseLocked(request)
        }
    }

    private fun retireQueuedSceneUseLocked(request: Pending) {
        request.queuedSceneUse?.let(retiredSceneUses::addLast)
        request.queuedSceneUse = null
    }

    /** Scene-source close may take renderer locks; never call it while holding [lock]. */
    private fun closeRetiredSceneUses() {
        val retired = synchronized(lock) {
            ArrayList<UiSyncForegroundSceneUse>(retiredSceneUses.size).also { snapshot ->
                while (retiredSceneUses.isNotEmpty()) snapshot += retiredSceneUses.removeFirst()
            }
        }
        retired.forEach { use ->
            try {
                use.close()
            } catch (failure: Throwable) {
                synchronized(lock) { retiredSceneUses.addLast(use) }
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            }
        }
    }

    private companion object {
        const val MAXIMUM_PENDING_REQUESTS = 64
        const val BUSY_RETRY_MILLIS = 75L
        val BUSY_RETRY_EXECUTOR = ScheduledThreadPoolExecutor(1) { action ->
            Thread(action, "ui-sync-foreground-busy-retry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        fun boundedDisplayIds(values: Collection<String>): Set<String> {
            val result = LinkedHashSet<String>()
            val iterator = values.iterator()
            while (iterator.hasNext()) {
                require(result.size < MAXIMUM_UI_SYNC_DISPLAY_DECLARATIONS) {
                    "UI Sync foreground outbox has too many displays"
                }
                require(result.add(iterator.next())) {
                    "UI Sync foreground outbox has duplicate display IDs"
                }
            }
            return Collections.unmodifiableSet(result)
        }
    }
}
