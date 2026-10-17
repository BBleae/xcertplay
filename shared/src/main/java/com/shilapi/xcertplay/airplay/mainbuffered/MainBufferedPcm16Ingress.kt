package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.Closeable

/** The byte order needs sender-wire evidence; the PCM format bit alone does not prove it. */
internal enum class MainBufferedPcm16ByteOrder {
    BIG_ENDIAN,
    LITTLE_ENDIAN,
}

/**
 * A prepared non-blocking output. [offerPcmLittleEndian] must copy accepted bytes before returning;
 * false means the bounded output queue cannot admit the entire packet. A successful [close] must
 * retire its worker and native track; an unsuccessful close remains retryable.
 */
internal interface MainBufferedPcm16Output : Closeable {
    val isReady: Boolean

    /** Changes whenever native/queued audio is retired; a start proof must match this epoch. */
    val playbackEpoch: Long

    /** Opaque exact AudioTrack-instance challenge used by calibrated native-clock proofs. */
    val outputInstance: Any

    /** First not-yet-retired PCM sample, if an entire authenticated packet is queued. */
    val firstQueuedMediaTimeValue: Long?

    fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean

    /** Rate zero: silence native output and discard every previously queued sample. */
    fun pausePlayback()

    /** No output may start unless this proof matches the current native epoch and queue head. */
    fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean

    fun flush()
}

internal fun interface MainBufferedPcm16OutputFactory {
    fun open(): MainBufferedPcm16Output
}

internal enum class MainBufferedPcm16IngressState {
    READY,
    FAILED,
    CLOSING,
    CLOSED,
}

internal data class MainBufferedPcm16IngressSnapshot(
    val generation: Long,
    val streamId: Long,
    val state: MainBufferedPcm16IngressState,
    val flushGeneration: Long,
    val admittedPackets: Long,
    val admittedPcmBytes: Long,
    val outputReady: Boolean,
    val outputPlaybackEpoch: Long,
    val outputInstance: Any,
    val firstQueuedMediaTimeValue: Long?,
)

/**
 * Detached APAP-to-PCM ingress for the one explicitly supported PCM16/48 kHz/stereo format.
 * This does not register a product SETUP handler, derive keys, advertise mainBuffered, or infer a
 * control-plane playback anchor. It consumes only packets authenticated by the exact generation's
 * [MainBufferedIngressRuntime], never an RTP packet or a guessed format.
 *
 * A control-plane flush can retire already queued output through [flush]. A later product binding
 * must additionally serialize mctl flush boundaries with ingress admission so that an older APAP
 * packet still queued on the data port cannot be mislabelled as a new playback generation.
 */
internal class MainBufferedPcm16Ingress private constructor(
    private val identity: MainBufferedIngressIdentity,
    private val streamId: Long,
    private val samplesPerPacket: Int,
    private val sourceByteOrder: MainBufferedPcm16ByteOrder,
    private val output: MainBufferedPcm16Output,
) : MainBufferedIngressPacketConsumer, Closeable {
    private val lock = Any()
    private var state = MainBufferedPcm16IngressState.READY
    private var flushGeneration = 0L
    private var admittedPackets = 0L
    private var admittedPcmBytes = 0L
    private var previousSequence: Int? = null
    private var previousMediaTime: Long? = null

    fun snapshot(): MainBufferedPcm16IngressSnapshot = synchronized(lock) {
        MainBufferedPcm16IngressSnapshot(
            generation = identity.generation,
            streamId = streamId,
            state = state,
            flushGeneration = flushGeneration,
            admittedPackets = admittedPackets,
            admittedPcmBytes = admittedPcmBytes,
            outputReady = state == MainBufferedPcm16IngressState.READY && output.isReady,
            outputPlaybackEpoch = output.playbackEpoch,
            outputInstance = output.outputInstance,
            firstQueuedMediaTimeValue = output.firstQueuedMediaTimeValue,
        )
    }

    override fun onPacket(session: MainBufferedIngressSession, packet: ApapPacket) {
        synchronized(lock) {
            check(session.identity === identity) { "APAP packet belongs to another ingress generation" }
            check(state == MainBufferedPcm16IngressState.READY) { "main-buffered PCM ingress is not ready" }
            try {
                check(output.isReady) { "main-buffered PCM output is not ready" }
                validateHeaderAndPayload(packet)
                val pcm = packet.mediaPayload
                try {
                    if (sourceByteOrder == MainBufferedPcm16ByteOrder.BIG_ENDIAN) {
                        for (offset in pcm.indices step 2) {
                            val mostSignificant = pcm[offset]
                            pcm[offset] = pcm[offset + 1]
                            pcm[offset + 1] = mostSignificant
                        }
                    }
                    val nextPackets = Math.addExact(admittedPackets, 1L)
                    val nextBytes = Math.addExact(admittedPcmBytes, pcm.size.toLong())
                    if (!output.offerPcmLittleEndian(pcm, packet.header.mediaTimeValue)) {
                        throw IllegalStateException("main-buffered PCM output queue is full or retired")
                    }
                    admittedPackets = nextPackets
                    admittedPcmBytes = nextBytes
                    previousSequence = packet.header.sequence
                    previousMediaTime = packet.header.mediaTimeValue
                } finally {
                    pcm.fill(0)
                }
            } catch (failure: Throwable) {
                failClosed(failure)
            }
        }
    }

    /** Only the exact session owner may flush; no old output buffer is valid after this returns. */
    fun flush(sessionIdentity: MainBufferedIngressIdentity, selectedStreamId: Long) {
        synchronized(lock) {
            check(sessionIdentity === identity && selectedStreamId == streamId) {
                "main-buffered flush belongs to another generation or stream"
            }
            check(state == MainBufferedPcm16IngressState.READY) { "main-buffered PCM ingress is not ready" }
            try {
                check(output.isReady) { "main-buffered PCM output is not ready" }
                output.flush()
                flushGeneration = Math.addExact(flushGeneration, 1L)
                previousSequence = null
                previousMediaTime = null
            } catch (failure: Throwable) {
                failClosed(failure)
            }
        }
    }

    /** The bound control owner must have authenticated rate zero before invoking this. */
    fun pausePlayback(sessionIdentity: MainBufferedIngressIdentity, selectedStreamId: Long) {
        synchronized(lock) {
            requireExactOwner(sessionIdentity, selectedStreamId)
            check(state == MainBufferedPcm16IngressState.READY) { "main-buffered PCM ingress is not ready" }
            try {
                check(output.isReady) { "main-buffered PCM output is not ready" }
                output.pausePlayback()
            } catch (failure: Throwable) {
                failClosed(failure)
            }
        }
    }

    /** A clock proof cannot be borrowed from another output epoch or media queue head. */
    fun startPlayback(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        anchor: MainBufferedPcm16VerifiedAnchor,
    ): Boolean {
        val expectedOutputEpoch = synchronized(lock) {
            requireExactOwner(sessionIdentity, selectedStreamId)
            check(state == MainBufferedPcm16IngressState.READY) {
                "main-buffered PCM ingress is not ready"
            }
            check(output.isReady) { "main-buffered PCM output is not ready" }
            if (anchor.outputInstance !== output.outputInstance ||
                anchor.outputEpoch != output.playbackEpoch
            ) return false
            output.playbackEpoch
        }
        // startPlayback waits for the *native* play receipt. Keep the PCM ingress lock free so
        // authenticated APAP packets can fill its bounded prebuffer, and so flush/close can
        // cancel the wait. The output itself rechecks proof/epoch under its native gate.
        val started = try {
            output.startPlayback(anchor)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (state == MainBufferedPcm16IngressState.READY) failClosed(failure)
            }
            throw failure
        }
        return synchronized(lock) {
            started && state == MainBufferedPcm16IngressState.READY && output.isReady &&
                output.outputInstance === anchor.outputInstance &&
                output.playbackEpoch == expectedOutputEpoch
        }
    }

    internal fun isOwnedBy(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
    ): Boolean = sessionIdentity === identity && selectedStreamId == streamId

    private fun requireExactOwner(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
    ) {
        check(isOwnedBy(sessionIdentity, selectedStreamId)) {
            "main-buffered control belongs to another generation or stream"
        }
    }

    /** Success-only: a failed output retirement leaves the same owner available for close retry. */
    override fun close() {
        synchronized(lock) {
            if (state == MainBufferedPcm16IngressState.CLOSED) return
            state = MainBufferedPcm16IngressState.CLOSING
            output.close()
            state = MainBufferedPcm16IngressState.CLOSED
        }
    }

    /** Stop queued and native playback even when malformed APAP arrived after valid audio. */
    private fun failClosed(failure: Throwable): Nothing {
        state = MainBufferedPcm16IngressState.FAILED
        try {
            output.flush()
        } catch (flushFailure: Throwable) {
            failure.addSuppressed(flushFailure)
        }
        try {
            output.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }

    private fun validateHeaderAndPayload(packet: ApapPacket) {
        if (packet.extensions.isNotEmpty()) {
            throw ApapProtocolException("PCM16 APAP extensions have no verified media meaning")
        }
        if (packet.header.mediaTimeScale != SAMPLE_RATE.toLong()) {
            throw ApapProtocolException("PCM16 APAP media-time scale is not $SAMPLE_RATE")
        }
        val expectedBytes = samplesPerPacket * BYTES_PER_FRAME
        if (packet.mediaPayloadSize() != expectedBytes) {
            throw ApapProtocolException("PCM16 APAP payload is not exactly $expectedBytes bytes")
        }
        previousSequence?.let { previous ->
            if (packet.header.sequence != ((previous + 1) and MAX_SEQUENCE)) {
                throw ApapProtocolException("PCM16 APAP sequence is not contiguous")
            }
        }
        previousMediaTime?.let { previous ->
            val expected = try {
                Math.addExact(previous, samplesPerPacket.toLong())
            } catch (_: ArithmeticException) {
                throw ApapProtocolException("PCM16 APAP media time overflows")
            }
            if (packet.header.mediaTimeValue != expected) {
                throw ApapProtocolException("PCM16 APAP media time is not contiguous")
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_FRAME = CHANNELS * 2
        const val MAX_SAMPLES_PER_PACKET = 4_096
        const val LPCM_CONTENT_TYPE = 0x6c70636dL
        private const val MAX_SEQUENCE = 0x00ff_ffff

        fun prepare(
            identity: MainBufferedIngressIdentity,
            streamId: Long,
            format: MainBufferedV27AcceptedFormat,
            sourceByteOrder: MainBufferedPcm16ByteOrder,
            outputFactory: MainBufferedPcm16OutputFactory,
        ): MainBufferedPcm16Ingress {
            require(streamId in 1L..0xffff_ffffL) { "PCM16 streamID must be a positive UInt32" }
            require(format.audioFormat == MainBufferedAudioFormatBits.PCM_48000_16_STEREO) {
                "only PCM16/48 kHz/stereo APAP is supported by this sink"
            }
            require(format.contentType == LPCM_CONTENT_TYPE) {
                "PCM16 APAP content type must be 'lpcm'"
            }
            require(format.samplesPerFrame in 1L..MAX_SAMPLES_PER_PACKET.toLong()) {
                "PCM16 APAP samples per packet exceed the bounded sink"
            }
            val output = outputFactory.open()
            try {
                check(output.isReady) { "PCM16 output did not initialize" }
                return MainBufferedPcm16Ingress(
                    identity,
                    streamId,
                    format.samplesPerFrame.toInt(),
                    sourceByteOrder,
                    output,
                )
            } catch (failure: Throwable) {
                try {
                    output.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        /** Pass to the detached APAP receiver; its default 4 MiB frame budget is not appropriate. */
        fun limitsFor(format: MainBufferedV27AcceptedFormat): MainBufferedIngressLimits {
            require(format.audioFormat == MainBufferedAudioFormatBits.PCM_48000_16_STEREO)
            require(format.contentType == LPCM_CONTENT_TYPE)
            require(format.samplesPerFrame in 1L..MAX_SAMPLES_PER_PACKET.toLong())
            val packetBytes = ApapPacketCodec.MIN_PACKET_BYTES +
                format.samplesPerFrame.toInt() * BYTES_PER_FRAME
            val frameBytes = BufferedApapTcpCodec.PREFIX_BYTES +
                EncryptedApapPacketCodec.OVERHEAD_BYTES + packetBytes
            return MainBufferedIngressLimits(
                maximumFrameBytes = frameBytes,
                receiveChunkBytes = frameBytes,
                maximumQueuedPackets = 8,
                maximumQueuedBytes = frameBytes * 8,
                packetLimits = ApapCodecLimits(
                    maxPacketBytes = packetBytes,
                    maxExtensions = 0,
                    maxExtensionBytes = 0,
                    maxTotalExtensionBytes = 0,
                ),
            )
        }
    }
}
