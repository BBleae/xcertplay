package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.IOException

/**
 * One linearization gate for PCM admission, playback authorization, and retirement.
 * The worker cannot consume queued bytes until an exact playback-clock receipt arms this epoch.
 * Native write/play have in-flight owners reserved here, but potentially unbounded platform
 * calls run outside this monitor so STOPPING and start timeout can publish revocation.
 */
internal class MainBufferedPcm16OutputAdmission(
    private val maxQueuedPackets: Int,
    val outputInstance: Any = Any(),
    private val afterQueuedUnderLock: (() -> Unit)? = null,
    private val afterPlayReservedOutsideLock: (() -> Unit)? = null,
    private val afterWriteReservedOutsideLock: (() -> Unit)? = null,
) {
    data class QueuedPcm(val generation: Long, val mediaTimeValue: Long, val bytes: ByteArray)

    enum class PlaybackState { STOPPED, ARMED, PLAYING }
    enum class StartOutcome { PLAYING, RETIRED, TIMED_OUT }

    private val lock = Object()
    private val queue = ArrayDeque<QueuedPcm>()
    private var generation = 0L
    private var closing = false
    private var playbackState = PlaybackState.STOPPED
    private var proof: MainBufferedPcm16VerifiedAnchor? = null
    private var nativePlayInFlight: MainBufferedPcm16VerifiedAnchor? = null
    private var nativeWriteInFlight: QueuedPcm? = null

    init {
        require(maxQueuedPackets in 1..1_024) { "APAP PCM queue capacity is outside the bounded range" }
    }

    val isClosing: Boolean get() = synchronized(lock) { closing }
    val queuedPackets: Int get() = synchronized(lock) { queue.size }
    val firstQueuedMediaTimeValue: Long? get() = synchronized(lock) {
        queue.firstOrNull()?.mediaTimeValue
    }
    val playbackEpoch: Long get() = synchronized(lock) { generation }
    val state: PlaybackState get() = synchronized(lock) { playbackState }

    fun isCurrent(anchor: MainBufferedPcm16VerifiedAnchor): Boolean = synchronized(lock) {
        !closing && proof === anchor && anchor.outputInstance === outputInstance &&
            anchor.outputEpoch == generation &&
            playbackState != PlaybackState.STOPPED
    }

    fun offer(
        pcm: ByteArray,
        mediaTimeValue: Long = 0L,
        ready: () -> Boolean,
    ): Boolean = synchronized(lock) {
        if (closing || !ready() || queue.size >= maxQueuedPackets ||
            proof?.let { mediaTimeValue < it.firstMediaTimeValue } == true
        ) return@synchronized false
        queue.addLast(QueuedPcm(generation, mediaTimeValue, pcm.copyOf()))
        afterQueuedUnderLock?.invoke()
        lock.notifyAll()
        true
    }

    /** An old receipt or a queue that has already skipped the first audible frame cannot arm. */
    fun arm(anchor: MainBufferedPcm16VerifiedAnchor, ready: () -> Boolean): Boolean =
        synchronized(lock) {
            if (closing || !ready() || playbackState != PlaybackState.STOPPED ||
                nativePlayInFlight != null || nativeWriteInFlight != null ||
                anchor.outputEpoch != generation || anchor.outputInstance !== outputInstance
            ) return@synchronized false
            while (queue.isNotEmpty() && queue.first().mediaTimeValue < anchor.firstMediaTimeValue) {
                queue.removeFirst().bytes.fill(0)
            }
            if (queue.isEmpty() || queue.first().mediaTimeValue != anchor.firstMediaTimeValue) {
                return@synchronized false
            }
            proof = anchor
            playbackState = PlaybackState.ARMED
            lock.notifyAll()
            true
        }

    /** Rate zero conservatively discards queued/native samples; resumption needs fresh proof. */
    fun pause(ready: () -> Boolean, retireNativeOutput: () -> Unit) = synchronized(lock) {
        check(!closing && ready()) { "APAP PCM output is not ready for pause" }
        retirePlayback(retireNativeOutput)
    }

    fun flush(ready: () -> Boolean, retireNativeOutput: () -> Unit) = synchronized(lock) {
        check(!closing && ready()) { "APAP PCM output is not ready for flush" }
        retirePlayback(retireNativeOutput)
    }

    private fun retirePlayback(retireNativeOutput: () -> Unit) {
        generation = Math.addExact(generation, 1L)
        clearQueuedPcm()
        proof = null
        playbackState = PlaybackState.STOPPED
        // A native operation already entered by this owner may finish after a concurrent
        // pause/flush. Revoke immediately, but never acknowledge retirement until its worker
        // has compensated the late write/play and the in-flight token is cleared.
        if (nativePlayInFlight != null || nativeWriteInFlight != null) {
            closing = true
            lock.notifyAll()
            throw IOException("APAP PCM native operation is still in flight")
        }
        try { retireNativeOutput() } catch (failure: Throwable) {
            closing = true
            lock.notifyAll()
            throw failure
        }
        lock.notifyAll()
    }

    fun closeAdmission() = synchronized(lock) {
        closing = true
        clearQueuedPcm()
        proof = null
        playbackState = PlaybackState.STOPPED
        lock.notifyAll()
    }

    /** Test seam for a frame already removed from the queue when a flush races its write. */
    fun poll(timeoutMillis: Long): QueuedPcm? = synchronized(lock) {
        if (timeoutMillis > 0 && queue.isEmpty() && !closing) lock.wait(timeoutMillis)
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    /** Unlike [poll], the production worker never removes bytes while stopped. */
    fun awaitPlayable(timeoutMillis: Long): Pair<QueuedPcm, MainBufferedPcm16VerifiedAnchor>? =
        synchronized(lock) {
            if (timeoutMillis > 0 && !closing &&
                (playbackState == PlaybackState.STOPPED || queue.isEmpty())
            ) lock.wait(timeoutMillis)
            val currentProof = proof ?: return@synchronized null
            if (closing || playbackState == PlaybackState.STOPPED || queue.isEmpty()) {
                return@synchronized null
            }
            queue.removeFirst() to currentProof
        }

    /** Null means this frame was retired before it could enter AudioTrack. */
    fun writeIfCurrent(frame: QueuedPcm, write: () -> Int): Int? = synchronized(lock) {
        if (closing || frame.generation != generation) null else write()
    }

    /** A late native write is compensated before its token can be released. */
    fun writeIfPlayable(
        frame: QueuedPcm,
        anchor: MainBufferedPcm16VerifiedAnchor,
        write: () -> Int,
        retireLateWrite: () -> Unit,
    ): Int? {
        synchronized(lock) {
            if (!isFrameCurrent(frame, anchor)) return null
            check(nativeWriteInFlight == null && nativePlayInFlight == null) {
                "APAP PCM native operation already has an owner"
            }
            nativeWriteInFlight = frame
        }
        try {
            afterWriteReservedOutsideLock?.invoke()
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (!closing) revokeFailedNativeOperation()
                nativeWriteInFlight = null
                lock.notifyAll()
            }
            throw failure
        }
        synchronized(lock) {
            if (!isFrameCurrent(frame, anchor) || nativeWriteInFlight !== frame) {
                nativeWriteInFlight = null
                lock.notifyAll()
                return null
            }
        }
        // As with play, the final check cannot close the tiny check→JNI-call race without
        // holding this monitor across unbounded native work; hardware mute remains required.
        var writeFailure: Throwable? = null
        var written = 0
        try { written = write() } catch (failure: Throwable) { writeFailure = failure }
        val accepted = synchronized(lock) {
            if (writeFailure == null && nativeWriteInFlight === frame &&
                isFrameCurrent(frame, anchor)
            ) {
                nativeWriteInFlight = null
                lock.notifyAll()
                true
            } else {
                if (writeFailure != null && !closing) revokeFailedNativeOperation()
                false
            }
        }
        if (!accepted) {
            try { retireLateWrite() } catch (stopFailure: Throwable) {
                if (writeFailure == null) writeFailure = stopFailure
                else writeFailure!!.addSuppressed(stopFailure)
            } finally {
                synchronized(lock) {
                    nativeWriteInFlight = null
                    lock.notifyAll()
                }
            }
        }
        writeFailure?.let { throw it }
        return if (accepted) written else null
    }

    fun playIfCurrent(
        anchor: MainBufferedPcm16VerifiedAnchor,
        play: () -> Unit,
        retireLatePlay: () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (closing || playbackState != PlaybackState.ARMED || proof !== anchor ||
                anchor.outputInstance !== outputInstance ||
                anchor.outputEpoch != generation
            ) return false
            check(nativePlayInFlight == null && nativeWriteInFlight == null) {
                "APAP PCM native operation already has an owner"
            }
            nativePlayInFlight = anchor
        }
        try {
            afterPlayReservedOutsideLock?.invoke()
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (!closing) revokeFailedNativeOperation()
                nativePlayInFlight = null
                lock.notifyAll()
            }
            throw failure
        }
        // This closes the externally observable reserved→call window. There remains an
        // unavoidable instruction-level race after this check, so a stronger hardware mute
        // precondition is required before enabling this output for production playback.
        synchronized(lock) {
            if (!isArmedCurrent(anchor) || nativePlayInFlight !== anchor) {
                nativePlayInFlight = null
                lock.notifyAll()
                return false
            }
        }
        // The native call has no documented latency bound. The admission monitor must remain
        // available to closeAdmission() and retireTimedOutStart() for the entire call.
        var playFailure: Throwable? = null
        try { play() } catch (failure: Throwable) { playFailure = failure }
        val accepted = synchronized(lock) {
            if (playFailure == null && nativePlayInFlight === anchor && isArmedCurrent(anchor)
            ) {
                playbackState = PlaybackState.PLAYING
                nativePlayInFlight = null
                lock.notifyAll()
                true
            } else {
                // Keep the in-flight owner reserved until compensation completes. A concurrent
                // closer must not release a track that may have started after revocation.
                if (playFailure != null && !closing) revokeFailedNativeOperation()
                false
            }
        }
        if (!accepted) {
            try { retireLatePlay() } catch (stopFailure: Throwable) {
                if (playFailure == null) playFailure = stopFailure
                else playFailure!!.addSuppressed(stopFailure)
            } finally {
                synchronized(lock) {
                    nativePlayInFlight = null
                    lock.notifyAll()
                }
            }
        }
        playFailure?.let { throw it }
        return accepted
    }

    /** Wait releases the native/admission gate so the worker can prebuffer and invoke play. */
    fun awaitNativePlay(
        anchor: MainBufferedPcm16VerifiedAnchor,
        timeoutMillis: Long,
    ): Boolean = synchronized(lock) {
        require(timeoutMillis in 1L..10_000L)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!closing && proof === anchor && playbackState == PlaybackState.ARMED) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) break
            lock.wait(maxOf(1L, minOf(remainingNanos / 1_000_000L, 100L)))
        }
        !closing && proof === anchor && playbackState == PlaybackState.PLAYING
    }

    /** Atomically decides whether play won the timeout race or the receipt must be retired. */
    fun retireTimedOutStart(anchor: MainBufferedPcm16VerifiedAnchor): StartOutcome =
        synchronized(lock) {
            if (!closing && proof === anchor && playbackState == PlaybackState.PLAYING) {
                return@synchronized StartOutcome.PLAYING
            }
            if (closing || proof !== anchor || playbackState != PlaybackState.ARMED) {
                return@synchronized StartOutcome.RETIRED
            }
            closing = true
            clearQueuedPcm()
            proof = null
            playbackState = PlaybackState.STOPPED
            lock.notifyAll()
            StartOutcome.TIMED_OUT
        }

    /** False means a write/play call still owns the native track; retain the exact owner. */
    fun withNativeGateIfIdle(action: () -> Unit): Boolean = synchronized(lock) {
        if (nativePlayInFlight != null || nativeWriteInFlight != null) false else {
            action()
            true
        }
    }

    private fun isArmedCurrent(anchor: MainBufferedPcm16VerifiedAnchor): Boolean =
        !closing && playbackState == PlaybackState.ARMED && proof === anchor &&
            anchor.outputInstance === outputInstance && anchor.outputEpoch == generation

    private fun isFrameCurrent(
        frame: QueuedPcm,
        anchor: MainBufferedPcm16VerifiedAnchor,
    ): Boolean = !closing && frame.generation == generation && proof === anchor &&
        anchor.outputInstance === outputInstance && anchor.outputEpoch == generation &&
        playbackState != PlaybackState.STOPPED

    private fun revokeFailedNativeOperation() {
        closing = true
        clearQueuedPcm()
        proof = null
        playbackState = PlaybackState.STOPPED
        lock.notifyAll()
    }

    private fun clearQueuedPcm() {
        while (queue.isNotEmpty()) queue.removeFirst().bytes.fill(0)
    }
}
