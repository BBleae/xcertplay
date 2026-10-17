package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.IOException
import java.util.concurrent.locks.LockSupport

/** Narrow native seam: a reserved play owner outlives a blocked native play call. */
internal interface MainBufferedPcm16NativeTrack {
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun release()
}

/**
 * Bounded PCM output whose default state is silent. A transport-ready sink is not playback-ready:
 * only an exact, unexpired hardware-clock receipt can arm native playback for its current epoch.
 * This software gate does not prove hardware silence while an already-entered native play call
 * is blocked: nonzero PCM is prebuffered. Enabling this output requires an independent mute
 * guarantee; timeout/STOPPING retain the owner rather than claiming a hard stop deadline.
 */
internal class MainBufferedPcm16ControlledOutput(
    private val track: MainBufferedPcm16NativeTrack,
    private val onClosed: () -> Unit,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val workerStarter: (Thread) -> Unit = Thread::start,
    private val sampleRateHz: Int = MainBufferedPcm16Ingress.SAMPLE_RATE,
    private val startAckWaitMillis: Long = START_ACK_WAIT_MILLIS,
    private val closeJoinMillis: Long = CLOSE_JOIN_MILLIS,
) {
    private val closeLock = Any()
    private val admission = MainBufferedPcm16OutputAdmission(MAX_QUEUED_PACKETS)
    private val worker = Thread(::run, "carplay-apap-pcm16").apply { isDaemon = true }
    @Volatile private var workerFailure: Throwable? = null
    @Volatile private var workerStarted = false
    private var trackReleased = false
    private var ownerReleased = false
    private var closed = false

    init {
        require(sampleRateHz == 44_100 || sampleRateHz == MainBufferedPcm16Ingress.SAMPLE_RATE) {
            "APAP PCM output supports only 44.1 or 48 kHz"
        }
        require(startAckWaitMillis in 1L..START_ACK_WAIT_MILLIS)
        require(closeJoinMillis in 1L..CLOSE_JOIN_MILLIS)
    }

    val isReady: Boolean
        get() = workerStarted && !admission.isClosing && workerFailure == null && worker.isAlive

    val outputInstance: Any get() = admission.outputInstance
    val playbackEpoch: Long get() = admission.playbackEpoch
    val playbackState: MainBufferedPcm16OutputAdmission.PlaybackState get() = admission.state
    val firstQueuedMediaTimeValue: Long? get() = admission.firstQueuedMediaTimeValue
    val queuedPackets: Int get() = admission.queuedPackets

    fun start() {
        check(!workerStarted && worker.state == Thread.State.NEW)
        workerStarter(worker)
        check(worker.isAlive) { "APAP PCM output worker did not start" }
        workerStarted = true
    }

    fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean {
        if (pcm.isEmpty() || pcm.size > MAX_PACKET_BYTES || pcm.size % BYTES_PER_FRAME != 0) {
            return false
        }
        return admission.offer(pcm, mediaTimeValue) { isReady }
    }

    /** A missing or stale receipt never changes the native track's silent state. */
    fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean {
        if (anchor.sampleRateHz != sampleRateHz) return false
        val now = monotonicNanos()
        if (now > anchor.validUntilMonotonicNanos ||
            now - anchor.playCallAtMonotonicNanos > anchor.maximumErrorNanos
        ) return false
        if (!admission.arm(anchor) { isReady }) return false
        if (admission.awaitNativePlay(anchor, startAckWaitMillis)) return true
        return when (admission.retireTimedOutStart(anchor)) {
            MainBufferedPcm16OutputAdmission.StartOutcome.PLAYING -> true
            MainBufferedPcm16OutputAdmission.StartOutcome.RETIRED -> false
            MainBufferedPcm16OutputAdmission.StartOutcome.TIMED_OUT -> {
                workerFailure = IOException("APAP PCM native playback did not start before deadline")
                // Admission is already revoked. A native play still in flight cannot be
                // force-retired by this timeout; close preserves its exact owner for retry.
                close()
                false
            }
        }
    }

    fun pausePlayback() {
        try {
            admission.pause(ready = { isReady }, retireNativeOutput = ::pauseAndFlushNative)
        } catch (failure: Throwable) {
            workerFailure = failure
            try { close() } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    fun flush() {
        try {
            admission.flush(ready = { isReady }, retireNativeOutput = ::pauseAndFlushNative)
        } catch (failure: Throwable) {
            workerFailure = failure
            try { close() } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    /** A failed worker join or native release leaves this owner retryable. */
    fun close() {
        if (worker === Thread.currentThread()) {
            throw IOException("APAP PCM output worker cannot join itself")
        }
        synchronized(closeLock) {
            if (!closed) {
                admission.closeAdmission()
                admission.withNativeGateIfIdle {
                    try { pauseAndFlushNative() } catch (_: Throwable) {
                        // Native release below is still required if pause or flush failed.
                    }
                }
                worker.interrupt()
            }
        }
        // Do not hold closeLock across join: a failed worker owns its final native release.
        worker.join(closeJoinMillis)
        if (worker.isAlive) throw IOException("APAP PCM output worker did not retire")
        retireNativeAndOwner()
    }

    /** Called both after an external join and from the worker's own terminal failure path. */
    private fun retireNativeAndOwner() = synchronized(closeLock) {
        if (closed) return@synchronized
        admission.closeAdmission()
        if (!admission.withNativeGateIfIdle {
            try { pauseAndFlushNative() } catch (_: Throwable) {
                // A failed pause/flush cannot prevent release of a potentially audible track.
            }
            if (!trackReleased) {
                track.release()
                trackReleased = true
            }
        }) throw IOException("APAP PCM native operation owner has not retired")
        if (!ownerReleased) {
            onClosed()
            ownerReleased = true
        }
        closed = true
    }

    /** A transient release failure is retried without waiting for another offer or teardown. */
    private fun retireFromWorker(failure: Throwable) {
        repeat(WORKER_RETIRE_ATTEMPTS) { attempt ->
            try {
                retireNativeAndOwner()
                return
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
                if (attempt + 1 < WORKER_RETIRE_ATTEMPTS) {
                    LockSupport.parkNanos(WORKER_RETIRE_RETRY_NANOS)
                }
            }
        }
        // Persistent native failure remains attached to its owner for an explicit close retry.
    }

    private fun pauseAndFlushNative() {
        var failure: Throwable? = null
        try { track.pause() } catch (problem: Throwable) { failure = problem }
        try { track.flush() } catch (problem: Throwable) {
            if (failure == null) failure = problem else failure.addSuppressed(problem)
        }
        failure?.let { throw it }
    }

    private fun run() {
        var activeAnchor: MainBufferedPcm16VerifiedAnchor? = null
        var expectedMediaTime: Long? = null
        var prebufferBytes = 0
        var consecutiveZeroWrites = 0
        var firstZeroWriteNanos = 0L
        try {
            while (!admission.isClosing) {
                val (item, anchor) = admission.awaitPlayable(100L) ?: continue
                try {
                    if (activeAnchor !== anchor) {
                        activeAnchor = anchor
                        expectedMediaTime = anchor.firstMediaTimeValue
                        prebufferBytes = 0
                        consecutiveZeroWrites = 0
                    }
                    if (item.mediaTimeValue != expectedMediaTime) {
                        throw IOException("APAP PCM output media time skipped the calibrated first sample")
                    }
                    var offset = 0
                    while (offset < item.bytes.size && !admission.isClosing) {
                        val written = admission.writeIfPlayable(item, anchor, write = {
                            track.write(item.bytes, offset, item.bytes.size - offset)
                        }, retireLateWrite = ::pauseAndFlushNative) ?: break
                        when {
                            written < 0 -> throw IOException("APAP PCM AudioTrack.write returned $written")
                            written > item.bytes.size - offset -> throw IOException(
                                "APAP PCM AudioTrack wrote beyond the submitted packet",
                            )
                            written % BYTES_PER_FRAME != 0 -> throw IOException(
                                "APAP PCM AudioTrack wrote a partial sample frame",
                            )
                            written == 0 -> {
                                val now = System.nanoTime()
                                if (consecutiveZeroWrites == 0) firstZeroWriteNanos = now
                                consecutiveZeroWrites++
                                if (consecutiveZeroWrites >= MAX_CONSECUTIVE_ZERO_WRITES ||
                                    now - firstZeroWriteNanos >= MAX_ZERO_WRITE_STALL_NANOS
                                ) throw IOException("APAP PCM native output made no write progress")
                                Thread.sleep(WRITE_RETRY_MILLIS)
                            }
                            else -> {
                                consecutiveZeroWrites = 0
                                offset += written
                                if (admission.state == MainBufferedPcm16OutputAdmission.PlaybackState.ARMED) {
                                    prebufferBytes += written
                                    if (prebufferBytes >= START_THRESHOLD_BYTES) {
                                        awaitAndStartNative(anchor)
                                    }
                                }
                            }
                        }
                    }
                    if (offset == item.bytes.size && admission.isCurrent(anchor)) {
                        expectedMediaTime = Math.addExact(
                            item.mediaTimeValue, (item.bytes.size / BYTES_PER_FRAME).toLong(),
                        )
                    }
                } finally {
                    item.bytes.fill(0)
                }
            }
        } catch (_: InterruptedException) {
            if (!admission.isClosing) {
                val failure = IOException("APAP PCM worker interrupted")
                workerFailure = failure
                admission.closeAdmission()
                retireFromWorker(failure)
            }
        } catch (failure: Throwable) {
            workerFailure = failure
            admission.closeAdmission()
            admission.withNativeGateIfIdle {
                try { pauseAndFlushNative() } catch (stopFailure: Throwable) {
                    failure.addSuppressed(stopFailure)
                }
            }
            retireFromWorker(failure)
        }
    }

    private fun awaitAndStartNative(anchor: MainBufferedPcm16VerifiedAnchor) {
        while (admission.isCurrent(anchor)) {
            val now = monotonicNanos()
            if (now > anchor.validUntilMonotonicNanos ||
                now - anchor.playCallAtMonotonicNanos > anchor.maximumErrorNanos
            ) throw IOException("APAP PCM playback clock receipt expired before native start")
            val waitNanos = anchor.playCallAtMonotonicNanos - now
            if (waitNanos > 0) {
                LockSupport.parkNanos(minOf(waitNanos, MAX_START_WAIT_SLICE_NANOS))
                if (Thread.interrupted()) throw InterruptedException()
                continue
            }
            admission.playIfCurrent(anchor, play = {
                val callTime = monotonicNanos()
                if (callTime > anchor.validUntilMonotonicNanos ||
                    callTime - anchor.playCallAtMonotonicNanos > anchor.maximumErrorNanos
                ) throw IOException("APAP PCM playback clock receipt expired at native start")
                track.play()
            }, retireLatePlay = ::pauseAndFlushNative)
            return
        }
    }

    private companion object {
        const val BYTES_PER_FRAME = MainBufferedPcm16Ingress.BYTES_PER_FRAME
        const val MAX_PACKET_BYTES = MainBufferedPcm16Ingress.MAX_SAMPLES_PER_PACKET * BYTES_PER_FRAME
        const val MAX_QUEUED_PACKETS = 32
        const val START_THRESHOLD_BYTES = 8 * 1024
        const val CLOSE_JOIN_MILLIS = 1_000L
        const val WRITE_RETRY_MILLIS = 5L
        const val MAX_CONSECUTIVE_ZERO_WRITES = 20
        const val MAX_ZERO_WRITE_STALL_NANOS = 250_000_000L
        const val START_ACK_WAIT_MILLIS = 5_500L
        const val MAX_START_WAIT_SLICE_NANOS = 1_000_000L
        const val WORKER_RETIRE_ATTEMPTS = 3
        const val WORKER_RETIRE_RETRY_NANOS = 10_000_000L
    }
}
