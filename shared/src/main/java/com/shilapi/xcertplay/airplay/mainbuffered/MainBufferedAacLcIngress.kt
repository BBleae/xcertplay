package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.Closeable

/**
 * Explicit, experimental packetization contract for a *single raw AAC access unit per APAP*.
 * The v27 phone has not supplied a captured packetization vector. A type-103 format bit and
 * `spf=1024` do not establish that an APAP packet is one AU, and this token is not product
 * readiness evidence. A future product binder must replace the local fixture with observed wire
 * evidence before it may select this ingress.
 */
internal class AacLcOneAuPerApapContract private constructor(val fixtureRateHz: Int) {
    companion object {
        fun fromExplicitLocalFixture(packet: ApapPacket, rawAccessUnit: ByteArray):
            AacLcOneAuPerApapContract {
            require(rawAccessUnit.isNotEmpty() && rawAccessUnit.size <= MAX_AAC_LC_ACCESS_UNIT_BYTES)
            require(packet.extensions.isEmpty())
            require(packet.mediaPayload.contentEquals(rawAccessUnit)) {
                "AAC-LC packetization fixture must contain exactly one unframed raw AU"
            }
            require(!hasAdtsHeader(rawAccessUnit)) {
                "AAC-LC packetization fixture is ADTS, not one raw AU"
            }
            require(packet.header.mediaTimeScale == 44_100L ||
                packet.header.mediaTimeScale == 48_000L) {
                "AAC-LC packetization fixture has an unsupported media-time scale"
            }
            return AacLcOneAuPerApapContract(packet.header.mediaTimeScale.toInt())
        }
    }
}

/** A defensive immutable decoder configuration for one owner AU epoch. */
internal class AacLcDecoderConfig(
    val sampleRateHz: Int,
    val channels: Int,
    val samplesPerFrame: Int,
    val epoch: Long,
    audioSpecificConfig: ByteArray,
) {
    private val storedAudioSpecificConfig = audioSpecificConfig.copyOf()
    val audioSpecificConfig: ByteArray get() = storedAudioSpecificConfig.copyOf()

    init {
        require(sampleRateHz == 44_100 || sampleRateHz == 48_000)
        require(channels == 2 && samplesPerFrame == AAC_LC_SAMPLES_PER_FRAME)
        require(epoch > 0L)
        require(parseTwoByteAacLcAsc(storedAudioSpecificConfig) == sampleRateHz)
    }
}

/** Decoder input for exactly one unframed AAC-LC access unit. */
internal class AacLcAccessUnit(
    val sequence: Int,
    val mediaTimeValue: Long,
    val mediaTimeScale: Long,
    val epoch: Long,
    payload: ByteArray,
) {
    private val payloadLock = Any()
    private val storedPayload = payload.copyOf()
    private var consumed = false
    val payload: ByteArray get() = synchronized(payloadLock) {
        check(!consumed) { "AAC-LC access unit was already consumed" }
        storedPayload.copyOf()
    }

    /** One-shot decoder handoff: no authenticated media copy remains in this AU after return. */
    internal fun consumePayload(): ByteArray = synchronized(payloadLock) {
        check(!consumed) { "AAC-LC access unit was already consumed" }
        consumed = true
        storedPayload.copyOf().also { storedPayload.fill(0) }
    }

    init {
        require(sequence in 0..MAX_AAC_LC_SEQUENCE)
        require(mediaTimeScale == 44_100L || mediaTimeScale == 48_000L)
        require(epoch > 0L)
        require(storedPayload.isNotEmpty() && storedPayload.size <= MAX_AAC_LC_ACCESS_UNIT_BYTES)
        require(!hasAdtsHeader(storedPayload))
    }
}

/** Input acceptance is not output readiness, and neither is native playback proof. */
internal data class AacLcDecodeReceipt(
    val accepted: Boolean,
    val queuedPcmFrames: Int,
    val firstQueuedMediaTimeValue: Long?,
) {
    init { require(queuedPcmFrames >= 0) }
}

/**
 * Injectable bounded AAC decoder and native output. [decode] must copy an accepted AU before it
 * returns. [flush], [pausePlayback] and [configure] must retire old decoded/native audio; a
 * successful [startPlayback] must be an exact native play receipt, not a codec-input receipt.
 * It must reject a stale output epoch both before and at the native play gate, and a false result
 * must leave the output silent. This is essential when fshb/rate-zero races an in-flight start.
 */
internal interface AacLcDecodePort : Closeable {
    val isReady: Boolean
    val outputInstance: Any
    val playbackEpoch: Long
    val firstQueuedMediaTimeValue: Long?

    fun configure(config: AacLcDecoderConfig): Boolean
    fun decode(accessUnit: AacLcAccessUnit): AacLcDecodeReceipt
    fun pausePlayback()
    fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean
    fun flush()
}

internal fun interface AacLcDecodePortFactory {
    fun open(): AacLcDecodePort
}

internal enum class MainBufferedAacLcState {
    AWAITING_COOKIE,
    STOPPED,
    PLAYING,
    REBIND_REQUIRED,
    FAILED,
    CLOSING,
    CLOSED,
}

internal data class MainBufferedAacLcSnapshot(
    val generation: Long,
    val streamId: Long,
    val pairVerifyGeneration: Long,
    val state: MainBufferedAacLcState,
    val epoch: Long,
    val configuredRateHz: Int?,
    val admittedAccessUnits: Long,
    val staleAccessUnits: Long,
    val decoderReady: Boolean,
    val outputPlaybackEpoch: Long,
    val firstQueuedMediaTimeValue: Long?,
)

/**
 * Detached AAC-LC APAP consumer for one selected type-103 stream. It is intentionally not wired
 * into product feature negotiation, SETUP, or `/info`. A local packetization fixture alone cannot
 * prove that actual iPhone APAP packets are one AU each. An already-authenticated APAP runtime
 * must invoke [stampAuthenticatedPacket] before its queue and [onStampedPacket] on delivery.
 * Only the independently authenticated mctl owner may call the control operations below.
 */
internal class MainBufferedAacLcIngress private constructor(
    private val identity: MainBufferedIngressIdentity,
    private val streamId: Long,
    private val pairVerifyGeneration: Long,
    private val sampleRateHz: Int,
    private val decoder: AacLcDecodePort,
) : MainBufferedStampedPacketConsumer, Closeable {
    private class Stamp(val owner: MainBufferedAacLcIngress, val epoch: Long)
    private class StartReservation(val epoch: Long, val outputInstance: Any, val outputEpoch: Long)

    private val lock = Any()
    private var state = MainBufferedAacLcState.AWAITING_COOKIE
    private var epoch = 0L
    private var cookieId: Long? = null
    private var cookie: ByteArray? = null
    private var previousSequence: Int? = null
    private var previousMediaTime: Long? = null
    private var mediaTimeWatermark: Long? = null
    private var pendingStart: StartReservation? = null
    private var admittedAccessUnits = 0L
    private var staleAccessUnits = 0L

    fun snapshot(): MainBufferedAacLcSnapshot = synchronized(lock) {
        MainBufferedAacLcSnapshot(
            identity.generation, streamId, pairVerifyGeneration, state, epoch,
            if (cookie == null) null else sampleRateHz,
            admittedAccessUnits, staleAccessUnits,
            (state == MainBufferedAacLcState.STOPPED || state == MainBufferedAacLcState.PLAYING) &&
                decoder.isReady,
            decoder.playbackEpoch,
            decoder.firstQueuedMediaTimeValue,
        )
    }

    override fun stampAuthenticatedPacket(
        identity: MainBufferedIngressIdentity,
        packet: ApapPacket,
    ): Any = synchronized(lock) {
        check(identity === this.identity && isLive()) {
            "AAC-LC APAP authentication belongs to another or retired session"
        }
        Stamp(this, epoch)
    }

    override fun onStampedPacket(
        session: MainBufferedIngressSession,
        packet: ApapPacket,
        stamp: Any,
    ) = synchronized(lock) {
        check(session.identity === identity) { "AAC-LC APAP belongs to another session" }
        val exact = stamp as? Stamp
        check(exact?.owner === this) { "AAC-LC APAP is missing the exact authentication stamp" }
        if (exact.epoch != epoch || state == MainBufferedAacLcState.CLOSING ||
            state == MainBufferedAacLcState.CLOSED || state == MainBufferedAacLcState.REBIND_REQUIRED
        ) {
            staleAccessUnits = Math.addExact(staleAccessUnits, 1L)
            return@synchronized
        }
        if (state != MainBufferedAacLcState.STOPPED &&
            state != MainBufferedAacLcState.PLAYING
        ) {
            failClosed(IllegalStateException(
                "AAC-LC APAP arrived without a current decoder cookie",
            ))
        }
        mediaTimeWatermark?.let { until ->
            if (packet.header.mediaTimeScale == sampleRateHz.toLong() &&
                packet.header.mediaTimeValue <= until
            ) {
                staleAccessUnits = Math.addExact(staleAccessUnits, 1L)
                return@synchronized
            }
        }
        try {
            validatePacket(packet)
            val bytes = packet.mediaPayload
            try {
                val receipt = decoder.decode(AacLcAccessUnit(
                    packet.header.sequence, packet.header.mediaTimeValue,
                    packet.header.mediaTimeScale, epoch, bytes,
                ))
                check(receipt.accepted && decoder.isReady) {
                    "AAC-LC decoder did not admit the complete AU in the current epoch"
                }
                admittedAccessUnits = Math.addExact(admittedAccessUnits, 1L)
                previousSequence = packet.header.sequence
                previousMediaTime = packet.header.mediaTimeValue
                mediaTimeWatermark = null
            } finally {
                bytes.fill(0)
            }
        } catch (failure: Throwable) {
            failClosed(failure)
        }
    }

    /**
     * First `magc` configures the strict two-byte AAC-LC ASC shape. A changed cookie in the same
     * stream retires old audio and requires a new stream/SETUP: APAP carries no verified cookie ID
     * with which to distinguish an old late AU from a new AU. No success ACK may be sent for it.
     */
    fun configureFromAuthenticatedControl(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        command: MainBufferedMagicCookie,
    ): Boolean = synchronized(lock) {
        requireExactOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
            command.streamId)
        check(isLive()) { "AAC-LC stream is retired" }
        val asc = command.magicCookie
        val parsedRate = parseTwoByteAacLcAsc(asc)
        if (parsedRate != sampleRateHz) {
            asc.fill(0)
            val failure = ApapProtocolException("AAC-LC magc is not the selected two-byte ASC")
            failClosed(failure)
        }
        if (cookie != null) {
            if (cookieId == command.magicCookieId && cookie!!.contentEquals(asc)) {
                asc.fill(0)
                return@synchronized true
            }
            try {
                pendingStart = null
                state = MainBufferedAacLcState.REBIND_REQUIRED
                decoder.pausePlayback()
                decoder.flush()
                decoder.close()
                epoch = Math.addExact(epoch, 1L)
                cookie?.fill(0)
                cookie = null
                return@synchronized false
            } catch (failure: Throwable) {
                failClosed(failure)
            } finally {
                asc.fill(0)
            }
        }
        try {
            decoder.pausePlayback()
            epoch = Math.addExact(epoch, 1L)
            if (!decoder.configure(AacLcDecoderConfig(
                    sampleRateHz, AAC_LC_CHANNELS, AAC_LC_SAMPLES_PER_FRAME, epoch, asc,
                )) || !decoder.isReady
            ) throw IllegalStateException("AAC-LC decoder did not configure")
            cookieId = command.magicCookieId
            cookie = asc.copyOf()
            state = MainBufferedAacLcState.STOPPED
            true
        } catch (failure: Throwable) {
            failClosed(failure)
        } finally {
            asc.fill(0)
        }
    }

    fun flushFromAuthenticatedControl(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        command: MainBufferedFlush,
    ) = synchronized(lock) {
        requireExactOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
            command.streamId)
        check(command.until.mediaTimeScale == sampleRateHz.toLong()) {
            "AAC-LC fshb time scale differs from the selected format"
        }
        check(state == MainBufferedAacLcState.STOPPED || state == MainBufferedAacLcState.PLAYING) {
            "AAC-LC stream has no current decoder cookie"
        }
        try {
            retireAuEpoch()
            mediaTimeWatermark = command.until.mediaTimeValue
        } catch (failure: Throwable) {
            failClosed(failure)
        }
    }

    fun pauseFromAuthenticatedControl(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        command: MainBufferedControlCommand,
    ) = synchronized(lock) {
        requireExactOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
            command.streamId)
        check((command is MainBufferedSetRateAndAnchor && command.rate == 0) ||
            (command is MainBufferedSetRateReturnAnchor && command.rate == 0)
        ) { "only authenticated rate zero can stop AAC-LC playback" }
        check(state == MainBufferedAacLcState.STOPPED || state == MainBufferedAacLcState.PLAYING) {
            "AAC-LC stream has no current decoder cookie"
        }
        try {
            retireAuEpoch()
            mediaTimeWatermark = null
        } catch (failure: Throwable) {
            failClosed(failure)
        }
    }

    /**
     * Rate one cannot be acknowledged without queued decoded PCM, an independent sender/native
     * clock calibration, and an exact native play receipt. No product mapping is supplied here.
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
            requireExactOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration,
                command.streamId)
            check(state == MainBufferedAacLcState.STOPPED) {
                "AAC-LC stream cannot start from its current state"
            }
            if (pendingStart != null || !decoder.isReady ||
                decoder.firstQueuedMediaTimeValue != audible.mediaTimeValue ||
                mediaTimeWatermark?.let { audible.mediaTimeValue <= it } == true
            ) return false
            Triple(epoch, decoder.outputInstance, decoder.playbackEpoch)
        }
        val proof = MainBufferedPcm16VerifiedAnchor.verify(
            anchor, prepared.second, prepared.third, mapping, nowMonotonicNanos,
            sampleRateHz = sampleRateHz,
        ) ?: return false
        val reservation = synchronized(lock) {
            if (state != MainBufferedAacLcState.STOPPED || epoch != prepared.first ||
                pendingStart != null || !decoder.isReady ||
                decoder.outputInstance !== proof.outputInstance ||
                decoder.playbackEpoch != proof.outputEpoch ||
                decoder.firstQueuedMediaTimeValue != proof.firstMediaTimeValue
            ) return false
            StartReservation(epoch, proof.outputInstance, proof.outputEpoch).also {
                pendingStart = it
            }
        }
        val started = try {
            decoder.startPlayback(proof)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (pendingStart === reservation) pendingStart = null
                if (isLive()) failClosed(failure)
            }
            throw failure
        }
        return synchronized(lock) {
            val exact = pendingStart === reservation &&
                state == MainBufferedAacLcState.STOPPED && epoch == reservation.epoch &&
                decoder.isReady && decoder.outputInstance === reservation.outputInstance &&
                decoder.playbackEpoch == reservation.outputEpoch
            if (pendingStart === reservation) pendingStart = null
            if (started && !exact) {
                // An injected decoder that reports a late native start after an authenticated
                // epoch change must not leave audible output behind an unsupported mctl reply.
                // The real port additionally rejects stale epochs at its native AudioTrack gate.
                failClosed(IllegalStateException(
                    "AAC-LC native play receipt outlived its authenticated epoch",
                ))
            }
            if (started && exact) {
                state = MainBufferedAacLcState.PLAYING
                true
            } else false
        }
    }

    /** Positive support is deliberately absent without a product mctl/clock binding. */
    fun detachedControlDispatcher(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        mapping: MainBufferedPcm16AudioClockMapping? = null,
        monotonicNanos: () -> Long = System::nanoTime,
    ): MainBufferedApTransportControlDispatcher {
        requireExactOwner(sessionIdentity, selectedStreamId, verifiedPairGeneration, null)
        return MainBufferedApTransportControlDispatcher(MainBufferedControlHandler { envelope ->
            when (val command = envelope.command) {
                is MainBufferedMagicCookie -> {
                    if (configureFromAuthenticatedControl(identity, streamId, pairVerifyGeneration,
                            command)) MainBufferedControlDispatchReply(mapOf("status" to 0L))
                    else unsupportedReply()
                }
                is MainBufferedFlush -> {
                    flushFromAuthenticatedControl(identity, streamId, pairVerifyGeneration, command)
                    MainBufferedControlDispatchReply(emptyMap())
                }
                is MainBufferedSetRateAndAnchor -> {
                    if (command.rate == 0) {
                        pauseFromAuthenticatedControl(identity, streamId, pairVerifyGeneration,
                            command)
                        MainBufferedControlDispatchReply(mapOf("status" to 0L))
                    } else if (startFromAuthenticatedControl(identity, streamId,
                            pairVerifyGeneration, command, mapping, monotonicNanos())) {
                        MainBufferedControlDispatchReply(mapOf("status" to 0L))
                    } else unsupportedReply()
                }
                is MainBufferedSetRateReturnAnchor -> {
                    if (command.rate == 0) pauseFromAuthenticatedControl(identity, streamId,
                        pairVerifyGeneration, command)
                    unsupportedReply()
                }
                is MainBufferedGetAnchor -> unsupportedReply()
                else -> throw UnsupportedOperationException(
                    "${command.tag.wireValue} has no verified AAC-LC playback operation")
            }
        })
    }

    private fun retireAuEpoch() {
        pendingStart = null
        decoder.pausePlayback()
        decoder.flush()
        epoch = Math.addExact(epoch, 1L)
        val asc = checkNotNull(cookie)
        check(decoder.configure(AacLcDecoderConfig(
            sampleRateHz, AAC_LC_CHANNELS, AAC_LC_SAMPLES_PER_FRAME, epoch, asc,
        )) && decoder.isReady) { "AAC-LC decoder failed to reconfigure after epoch retirement" }
        previousSequence = null
        previousMediaTime = null
        state = MainBufferedAacLcState.STOPPED
    }

    private fun validatePacket(packet: ApapPacket) {
        if (packet.extensions.isNotEmpty()) {
            throw ApapProtocolException("AAC-LC APAP extensions have no verified AU meaning")
        }
        if (packet.header.mediaTimeScale != sampleRateHz.toLong()) {
            throw ApapProtocolException("AAC-LC APAP media-time scale is not $sampleRateHz")
        }
        val length = packet.mediaPayloadSize()
        if (length !in 1..MAX_AAC_LC_ACCESS_UNIT_BYTES) {
            throw ApapProtocolException("AAC-LC APAP is not one bounded raw access unit")
        }
        if (hasAdtsHeader(packet.mediaPayload)) {
            throw ApapProtocolException("AAC-LC APAP contains ADTS rather than a raw access unit")
        }
        previousSequence?.let { previous ->
            if (packet.header.sequence != ((previous + 1) and MAX_AAC_LC_SEQUENCE)) {
                throw ApapProtocolException("AAC-LC APAP sequence is not contiguous")
            }
        }
        previousMediaTime?.let { previous ->
            val expected = try {
                Math.addExact(previous, AAC_LC_SAMPLES_PER_FRAME.toLong())
            } catch (_: ArithmeticException) {
                throw ApapProtocolException("AAC-LC APAP media time overflows")
            }
            if (packet.header.mediaTimeValue != expected) {
                throw ApapProtocolException("AAC-LC APAP media time is not one AU apart")
            }
        }
    }

    private fun requireExactOwner(
        sessionIdentity: MainBufferedIngressIdentity,
        selectedStreamId: Long,
        verifiedPairGeneration: Long,
        wireStreamId: Long?,
    ) {
        check(sessionIdentity === identity && selectedStreamId == streamId &&
            verifiedPairGeneration == pairVerifyGeneration &&
            (wireStreamId == null || wireStreamId == streamId)
        ) { "AAC-LC control belongs to another session, stream or PairVerify generation" }
    }

    private fun isLive(): Boolean = state != MainBufferedAacLcState.REBIND_REQUIRED &&
        state != MainBufferedAacLcState.FAILED && state != MainBufferedAacLcState.CLOSING &&
        state != MainBufferedAacLcState.CLOSED

    /** Success-only decoder retirement. A failed close remains retryable. */
    override fun close() = synchronized(lock) {
        if (state == MainBufferedAacLcState.CLOSED) return@synchronized
        pendingStart = null
        state = MainBufferedAacLcState.CLOSING
        decoder.close()
        cookie?.fill(0)
        cookie = null
        state = MainBufferedAacLcState.CLOSED
    }

    private fun failClosed(failure: Throwable): Nothing {
        state = MainBufferedAacLcState.FAILED
        pendingStart = null
        try { decoder.pausePlayback() } catch (error: Throwable) { failure.addSuppressed(error) }
        try { decoder.flush() } catch (error: Throwable) { failure.addSuppressed(error) }
        try { decoder.close() } catch (error: Throwable) { failure.addSuppressed(error) }
        cookie?.fill(0)
        cookie = null
        throw failure
    }

    companion object {
        const val AAC_LC_CONTENT_TYPE = 0x61616320L // `aac `
        const val MAX_ACCESS_UNIT_BYTES = MAX_AAC_LC_ACCESS_UNIT_BYTES
        private const val UNSUPPORTED_STATUS = -1

        fun prepare(
            identity: MainBufferedIngressIdentity,
            streamId: Long,
            pairVerifyGeneration: Long,
            format: MainBufferedV27AcceptedFormat,
            packetization: AacLcOneAuPerApapContract,
            decoderFactory: AacLcDecodePortFactory,
        ): MainBufferedAacLcIngress {
            require(streamId in 1L..0xffff_ffffL)
            require(pairVerifyGeneration > 0L && pairVerifyGeneration == identity.generation)
            require(format.contentType == AAC_LC_CONTENT_TYPE)
            require(format.samplesPerFrame == AAC_LC_SAMPLES_PER_FRAME.toLong())
            val rate = when (format.audioFormat) {
                MainBufferedAudioFormatBits.AAC_LC_44100_STEREO -> 44_100
                MainBufferedAudioFormatBits.AAC_LC_48000_STEREO -> 48_000
                else -> throw IllegalArgumentException("only AAC-LC/44.1 or 48 kHz/stereo is supported")
            }
            // The explicit token must be passed by the caller; merely selecting a format bit
            // does not imply packetization. It conveys no phone-vector/product readiness.
            require(packetization.fixtureRateHz == rate) {
                "AAC-LC packetization fixture rate differs from selected format"
            }
            val decoder = decoderFactory.open()
            try {
                // A real decoder cannot be ready until the first valid authenticated `magc`.
                // It must nevertheless be constructed silent and accept a pre-cookie pause.
                decoder.pausePlayback()
                return MainBufferedAacLcIngress(identity, streamId, pairVerifyGeneration, rate,
                    decoder)
            } catch (failure: Throwable) {
                try { decoder.close() } catch (error: Throwable) { failure.addSuppressed(error) }
                throw failure
            }
        }

        fun limitsFor(format: MainBufferedV27AcceptedFormat): MainBufferedIngressLimits {
            require(format.contentType == AAC_LC_CONTENT_TYPE)
            require(format.samplesPerFrame == AAC_LC_SAMPLES_PER_FRAME.toLong())
            require(format.audioFormat == MainBufferedAudioFormatBits.AAC_LC_44100_STEREO ||
                format.audioFormat == MainBufferedAudioFormatBits.AAC_LC_48000_STEREO)
            val packetBytes = ApapPacketCodec.MIN_PACKET_BYTES + MAX_AAC_LC_ACCESS_UNIT_BYTES
            val frameBytes = BufferedApapTcpCodec.PREFIX_BYTES +
                EncryptedApapPacketCodec.OVERHEAD_BYTES + packetBytes
            return MainBufferedIngressLimits(
                maximumFrameBytes = frameBytes,
                receiveChunkBytes = frameBytes,
                maximumQueuedPackets = 8,
                maximumQueuedBytes = frameBytes * 8,
                packetLimits = ApapCodecLimits(packetBytes, 0, 0, 0),
            )
        }

        private fun unsupportedReply() = MainBufferedControlDispatchReply(
            mapOf("status" to UNSUPPORTED_STATUS.toLong()),
            replyStatus = UNSUPPORTED_STATUS,
        )
    }
}

/** The only supported cookie shape: AOT 2, explicit frequency index 3/4, channel config 2. */
internal fun parseTwoByteAacLcAsc(bytes: ByteArray): Int? {
    if (bytes.size != 2) return null
    val bits = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
    val audioObjectType = (bits ushr 11) and 0x1f
    val rateIndex = (bits ushr 7) and 0x0f
    val channels = (bits ushr 3) and 0x0f
    val unsupportedTailBits = bits and 0x07
    if (audioObjectType != 2 || channels != AAC_LC_CHANNELS || unsupportedTailBits != 0) return null
    return when (rateIndex) {
        3 -> 48_000
        4 -> 44_100
        else -> null
    }
}

private fun hasAdtsHeader(bytes: ByteArray): Boolean = bytes.size >= 2 &&
    (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xf0) == 0xf0

private const val AAC_LC_CHANNELS = 2
private const val AAC_LC_SAMPLES_PER_FRAME = 1024
private const val MAX_AAC_LC_ACCESS_UNIT_BYTES = 16 * 1024
private const val MAX_AAC_LC_SEQUENCE = 0x00ff_ffff
