package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.Closeable

/** Opaque composition receipt: the mctl preparer must use this exact dispatcher and owner. */
internal class MainBufferedPcm16MctlBinding internal constructor(
    private val ownerToken: Any,
    val dispatcher: MainBufferedApTransportControlDispatcher,
    val executionLease: MainBufferedPairVerifyExecutionLease,
) {
    internal fun isFor(owner: MainBufferedPcm16PlaybackEpochOwner): Boolean =
        owner.matchesControlBinding(ownerToken, executionLease)
}

/**
 * One exact APAP PCM playback owner bound to a SETUP ingress identity, stream, and verified
 * PairVerify session generation. This is a detached owner, not a product feature advertisement.
 * The independently authenticated `mctl` dispatcher must be bound to this very instance and
 * must supply the current PairVerify generation before calling its control methods.
 *
 * APAP authentication stamps each packet *before* the runtime delivery queue. A flush or pause
 * advances [epoch] under the same lock that admits a stamped packet into the PCM output. Thus an
 * already queued old packet cannot be relabelled as new merely because it was delivered late;
 * a callback already in flight linearizes before the flush, whose native-output flush removes
 * those samples. The flush-until media time additionally suppresses old packets that complete
 * authentication only after the control command. No RTP-to-APAP sequence equivalence is guessed.
 */
internal class MainBufferedPcm16PlaybackEpochOwner private constructor(
    private val identity: MainBufferedIngressIdentity,
    private val streamId: Long,
    private val pairVerifyGeneration: Long,
    private val pcmIngress: MainBufferedPcm16Ingress,
) : MainBufferedStampedPacketConsumer, Closeable {
    internal enum class State { STOPPED, PLAYING, FAILED, CLOSING, CLOSED }

    internal data class Snapshot(
        val generation: Long,
        val streamId: Long,
        val pairVerifyGeneration: Long,
        val state: State,
        val epoch: Long,
        val stalePackets: Long,
        val mediaTimeWatermark: Long?,
        val pcm: MainBufferedPcm16IngressSnapshot,
    )

    private class Stamp(val owner: MainBufferedPcm16PlaybackEpochOwner, val epoch: Long)
    private class StartReservation(
        val epoch: Long,
        val outputInstance: Any,
        val outputEpoch: Long,
    )

    private val lock = Any()
    private val controlBindingToken = Any()
    private val controlLease = MainBufferedPairVerifyExecutionLease(pairVerifyGeneration)
    private var state = State.STOPPED
    private var epoch = 0L
    private var stalePackets = 0L
    private var mediaTimeWatermark: Long? = null
    private var pendingStart: StartReservation? = null

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            identity.generation,
            streamId,
            pairVerifyGeneration,
            state,
            epoch,
            stalePackets,
            mediaTimeWatermark,
            pcmIngress.snapshot(),
        )
    }

    /**
     * Build the exact dispatcher to inject into [MainBufferedAuthenticatedMctlPortPreparer].
     * Only that port decrypts and validates APTransport before calling it. The caller must bind
     * that preparer to the same live PairVerify generation; an arbitrary direct dispatch is not
     * authentication. Unsupported commands fail closed instead of synthesizing an ACK or anchor.
     */
    fun authenticatedMctlDispatcher(
        sessionIdentity: MainBufferedIngressIdentity,
        verifiedPairGeneration: Long,
        mapping: MainBufferedPcm16AudioClockMapping? = null,
        monotonicNanos: () -> Long = System::nanoTime,
    ): MainBufferedApTransportControlDispatcher {
        requireExactControlOwner(sessionIdentity, streamId, verifiedPairGeneration, null)
        return MainBufferedApTransportControlDispatcher(MainBufferedControlHandler { envelope ->
            when (val command = envelope.command) {
                is MainBufferedFlush -> {
                    flushFromAuthenticatedControl(identity, streamId, pairVerifyGeneration, command)
                    MainBufferedControlDispatchReply(emptyMap())
                }
                is MainBufferedSetRateAndAnchor -> {
                    if (command.rate == 0) {
                        pauseFromAuthenticatedControl(identity, streamId, pairVerifyGeneration, command)
                        MainBufferedControlDispatchReply(mapOf("status" to 0L))
                    } else if (startFromAuthenticatedControl(
                            identity, streamId, pairVerifyGeneration, command, mapping,
                            monotonicNanos(),
                        )
                    ) {
                        MainBufferedControlDispatchReply(mapOf("status" to 0L))
                    } else {
                        MainBufferedControlDispatchReply(
                            mapOf("status" to UNSUPPORTED_STATUS),
                            replyStatus = UNSUPPORTED_STATUS,
                        )
                    }
                }
                is MainBufferedSetRateReturnAnchor -> {
                    if (command.rate == 0) {
                        pauseFromAuthenticatedControl(identity, streamId, pairVerifyGeneration, command)
                    }
                    // `strt` success requires a complete current anchor. This output cannot
                    // prove one from a naked RTP/media-time point, even after an actual stop.
                    MainBufferedControlDispatchReply(
                        mapOf("status" to UNSUPPORTED_STATUS),
                        replyStatus = UNSUPPORTED_STATUS,
                    )
                }
                is MainBufferedGetAnchor -> MainBufferedControlDispatchReply(
                    mapOf("status" to UNSUPPORTED_STATUS),
                    replyStatus = UNSUPPORTED_STATUS,
                )
                else -> throw UnsupportedOperationException(
                    "${command.tag.wireValue} has no verified PCM playback operation",
                )
            }
        })
    }

    /** Composition token for an exact [MainBufferedAuthenticatedMctlPortPreparer]. */
    fun authenticatedMctlBinding(
        sessionIdentity: MainBufferedIngressIdentity,
        verifiedPairGeneration: Long,
        mapping: MainBufferedPcm16AudioClockMapping? = null,
        monotonicNanos: () -> Long = System::nanoTime,
    ): MainBufferedPcm16MctlBinding = MainBufferedPcm16MctlBinding(
        controlBindingToken,
        authenticatedMctlDispatcher(
            sessionIdentity, verifiedPairGeneration, mapping, monotonicNanos,
        ),
        controlLease,
    )

    override fun stampAuthenticatedPacket(
        identity: MainBufferedIngressIdentity,
        packet: ApapPacket,
    ): Any = synchronized(lock) {
        check(identity === this.identity && state != State.CLOSING && state != State.CLOSED &&
            state != State.FAILED
        ) { "APAP authentication belongs to a retired playback owner" }
        // The packet is authenticated already; its contents are validated by the PCM ingress
        // before output. Reading the header here cannot make an unverified format ready.
        Stamp(this, epoch)
    }

    override fun onStampedPacket(
        session: MainBufferedIngressSession,
        packet: ApapPacket,
        stamp: Any,
    ) {
        synchronized(lock) {
            check(session.identity === identity) {
                "APAP playback packet belongs to another ingress generation"
            }
            val exactStamp = stamp as? Stamp
            check(exactStamp?.owner === this) {
                "APAP playback packet does not carry this owner's authentication stamp"
            }
            if (exactStamp.epoch != epoch || state == State.CLOSING || state == State.CLOSED) {
                stalePackets = Math.addExact(stalePackets, 1L)
                return
            }
            check(state != State.FAILED) { "APAP playback owner has failed" }
            mediaTimeWatermark?.let { until ->
                if (packet.header.mediaTimeScale == MainBufferedPcm16Ingress.SAMPLE_RATE.toLong() &&
                    packet.header.mediaTimeValue <= until
                ) {
                    stalePackets = Math.addExact(stalePackets, 1L)
                    return
                }
                // Clear the watermark only after the first higher, same-scale PCM media time.
                // A different scale is passed to the strict PCM ingress and fails closed.
                if (packet.header.mediaTimeScale == MainBufferedPcm16Ingress.SAMPLE_RATE.toLong()) {
                    mediaTimeWatermark = null
                }
            }
            try {
                pcmIngress.onPacket(session, packet)
            } catch (failure: Throwable) {
                state = State.FAILED
                throw failure
            }
        }
    }

    /**
     * The caller must be the exact authenticated mctl owner. A null wire streamID is accepted
     * only because this SETUP owner can have exactly one stream; a different explicit ID fails.
     */
    fun flushFromAuthenticatedControl(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        command: MainBufferedFlush,
    ) = synchronized(lock) {
        requireExactControlOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
            command.streamId)
        check(command.until.mediaTimeScale == MainBufferedPcm16Ingress.SAMPLE_RATE.toLong()) {
            "fshb media-time scale cannot be compared with PCM16/48 kHz APAP"
        }
        check(state != State.FAILED && state != State.CLOSING && state != State.CLOSED) {
            "main-buffered playback owner is not live"
        }
        try {
            pendingStart = null
            pcmIngress.flush(identity, streamId)
            epoch = Math.addExact(epoch, 1L)
            mediaTimeWatermark = command.until.mediaTimeValue
            state = State.STOPPED
        } catch (failure: Throwable) {
            state = State.FAILED
            throw failure
        }
    }

    /** Rate zero is a real stop boundary, not an acknowledgment of a playable clock anchor. */
    fun pauseFromAuthenticatedControl(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        command: MainBufferedControlCommand,
    ) = synchronized(lock) {
        requireExactControlOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
            command.streamId)
        check((command is MainBufferedSetRateAndAnchor && command.rate == 0) ||
            (command is MainBufferedSetRateReturnAnchor && command.rate == 0)
        ) { "only authenticated rate zero may stop PCM playback" }
        check(state != State.FAILED && state != State.CLOSING && state != State.CLOSED) {
            "main-buffered playback owner is not live"
        }
        try {
            pendingStart = null
            pcmIngress.pausePlayback(identity, streamId)
            epoch = Math.addExact(epoch, 1L)
            mediaTimeWatermark = null
            state = State.STOPPED
        } catch (failure: Throwable) {
            state = State.FAILED
            throw failure
        }
    }

    /**
     * Rate one is accepted only with an independently calibrated native audio-clock receipt.
     * The current product has no such mapping provider, so its null mapping remains STOPPED and
     * its caller must report an unsupported/error response rather than acknowledge playback.
     */
    fun startFromAuthenticatedControl(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        command: MainBufferedControlCommand,
        mapping: MainBufferedPcm16AudioClockMapping? = null,
        nowMonotonicNanos: Long = System.nanoTime(),
    ): Boolean {
        val anchor = (command as? MainBufferedSetRateAndAnchor)
            ?.takeIf { it.rate == 1 }?.anchor ?: return false
        val audible = anchor.firstAudiblePoint ?: return false
        val prepared = synchronized(lock) {
            requireExactControlOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
                command.streamId)
            check(state != State.FAILED && state != State.CLOSING && state != State.CLOSED) {
                "main-buffered playback owner is not live"
            }
            if (state != State.STOPPED || pendingStart != null ||
                mediaTimeWatermark?.let { audible.mediaTimeValue <= it } == true
            ) return false
            val output = pcmIngress.snapshot()
            // No ACK before authenticated PCM exists in this exact output queue.
            if (!output.outputReady || output.firstQueuedMediaTimeValue == null) return false
            epoch to output
        }
        // Calibration may query external clocks; do not hold the APAP/flush owner lock.
        val proof = MainBufferedPcm16VerifiedAnchor.verify(
            anchor,
            prepared.second.outputInstance,
            prepared.second.outputPlaybackEpoch,
            mapping,
            nowMonotonicNanos,
        ) ?: return false
        val reservation = synchronized(lock) {
            if (state != State.STOPPED || pendingStart != null || epoch != prepared.first ||
                mediaTimeWatermark?.let { audible.mediaTimeValue <= it } == true
            ) return false
            val current = pcmIngress.snapshot()
            if (!current.outputReady || current.outputInstance !== proof.outputInstance ||
                current.outputPlaybackEpoch != proof.outputEpoch
            ) return false
            StartReservation(epoch, proof.outputInstance, proof.outputEpoch).also {
                pendingStart = it
            }
        }
        val started = try {
            // This may wait for native track.play(); APAP delivery must remain able to enqueue
            // additional authenticated PCM, and flush/close must be able to cancel the wait.
            pcmIngress.startPlayback(identity, streamId, proof)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (pendingStart === reservation) pendingStart = null
                if (state != State.CLOSED && state != State.CLOSING) state = State.FAILED
            }
            throw failure
        }
        return synchronized(lock) {
            val stillReserved = pendingStart === reservation && state == State.STOPPED &&
                epoch == reservation.epoch
            if (pendingStart === reservation) pendingStart = null
            val output = pcmIngress.snapshot()
            if (started && stillReserved && output.outputReady &&
                output.outputInstance === reservation.outputInstance &&
                output.outputPlaybackEpoch == reservation.outputEpoch
            ) {
                state = State.PLAYING
                true
            } else {
                if (!output.outputReady && state == State.STOPPED) state = State.FAILED
                false
            }
        }
    }

    private fun requireExactControlOwner(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        wireStreamId: Long?,
    ) {
        check(sessionIdentity === identity && selectedStreamId == streamId &&
            verifiedPairGeneration == pairVerifyGeneration &&
            (wireStreamId == null || wireStreamId == streamId)
        ) { "authenticated main-buffered control belongs to another session, stream, or PairVerify generation" }
    }

    internal fun isOwnedBy(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
    ): Boolean = sessionIdentity === identity && selectedStreamId == streamId &&
        verifiedPairGeneration == pairVerifyGeneration

    internal fun matchesControlBinding(token: Any, lease: MainBufferedPairVerifyExecutionLease): Boolean =
        token === controlBindingToken && lease === controlLease

    override fun close() {
        // Mark revoked without waiting for a possibly pending native start; closing the PCM
        // output below cancels that wait. Then prove all claimed control actions have retired.
        controlLease.requestRevoke()
        try {
            synchronized(lock) {
                pendingStart = null
                if (state != State.CLOSED) {
                    state = State.CLOSING
                    pcmIngress.close()
                    state = State.CLOSED
                }
            }
        } finally {
            controlLease.awaitQuiescent()
        }
    }

    companion object {
        private const val UNSUPPORTED_STATUS = -1

        fun create(
            identity: MainBufferedIngressIdentity,
            streamId: Long,
            pairVerifyGeneration: Long,
            pcmIngress: MainBufferedPcm16Ingress,
        ): MainBufferedPcm16PlaybackEpochOwner {
            require(pairVerifyGeneration > 0L && pairVerifyGeneration == identity.generation) {
                "playback owner PairVerify generation must match the exact ingress session"
            }
            require(pcmIngress.isOwnedBy(identity, streamId)) {
                "PCM output does not belong to the exact ingress identity and stream"
            }
            // Both the owner and native output begin silent, even if callers have already
            // prepared a detached PCM sink. A failed stop cannot be used as readiness evidence.
            pcmIngress.pausePlayback(identity, streamId)
            return MainBufferedPcm16PlaybackEpochOwner(identity, streamId, pairVerifyGeneration,
                pcmIngress)
        }
    }
}
