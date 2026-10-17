package com.shilapi.xcertplay.airplay.mainbuffered

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A small synchronous seam: decoded audio and native AudioTrack remain owned by one ingress. */
internal interface MainBufferedAacLcCodecDriver : Closeable {
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun inputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, size: Int, presentationTimeUs: Long)
    fun dequeueOutputBuffer(timeoutUs: Long): MainBufferedAacLcCodecEvent
    fun outputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int)
    fun flush()
}

internal sealed interface MainBufferedAacLcCodecEvent {
    data object TryAgain : MainBufferedAacLcCodecEvent
    data object BuffersChanged : MainBufferedAacLcCodecEvent
    data class Format(val sampleRateHz: Int, val channels: Int, val pcmEncoding: Int) :
        MainBufferedAacLcCodecEvent
    data class Buffer(
        val index: Int,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int,
    ) : MainBufferedAacLcCodecEvent
}

/**
 * Decode complete raw AAC-LC APAP access units. There is no RTP header and no ADTS wrapper here.
 * MediaCodec input admission is not a playback receipt: only decoded, timestamp-validated PCM is
 * offered to an AudioTrack which remains STOPPED until an independently calibrated native-play
 * proof for this exact output instance and epoch succeeds.
 */
internal class AndroidMainBufferedAacLcDecoder internal constructor(
    private val codecFactory: (AacLcDecoderConfig) -> MainBufferedAacLcCodecDriver,
    private val outputFactory: (Int) -> MainBufferedPcm16Output,
) : AacLcDecodePort {
    private data class PendingInput(var nextMediaTimeValue: Long, var remainingFrames: Int)

    private val lock = Any()
    private val pending = ArrayDeque<PendingInput>()
    private var config: AacLcDecoderConfig? = null
    private var codec: MainBufferedAacLcCodecDriver? = null
    private var output: MainBufferedPcm16Output? = null
    private var closed = false
    private var failed = false
    private var outputFormatValidated = false
    private var previousInputSequence: Int? = null
    private var nextInputMediaTimeValue: Long? = null
    private var nextOutputMediaTimeValue: Long? = null

    override val isReady: Boolean get() = synchronized(lock) {
        !closed && !failed && config != null && codec != null && output?.isReady == true
    }

    override val outputInstance: Any get() = synchronized(lock) {
        checkReadyOutput().outputInstance
    }

    override val playbackEpoch: Long get() = synchronized(lock) {
        output?.playbackEpoch ?: 0L
    }

    override val firstQueuedMediaTimeValue: Long? get() = synchronized(lock) {
        output?.firstQueuedMediaTimeValue
    }

    override fun configure(config: AacLcDecoderConfig): Boolean = synchronized(lock) {
        if (closed || failed) return@synchronized false
        try {
            check(validConfig(config)) { "AAC-LC decoder configuration is not the selected ASC" }
            val current = this.config
            if (current != null) {
                if (config.epoch <= current.epoch ||
                    config.sampleRateHz != current.sampleRateHz ||
                    config.channels != current.channels ||
                    config.samplesPerFrame != current.samplesPerFrame ||
                    !config.audioSpecificConfig.contentEquals(current.audioSpecificConfig)
                ) {
                    // A changed codec configuration needs a new, separately identified stream.
                    throw IOException("AAC-LC configuration changed within one APAP stream")
                }
                checkReadyOutput().flush()
                checkNotNull(codec).flush()
            } else {
                val nativeOutput = outputFactory(config.sampleRateHz)
                output = nativeOutput
                check(nativeOutput.isReady) { "AAC-LC PCM output did not initialize" }
                codec = codecFactory(config)
                outputFormatValidated = false
            }
            this.config = config
            resetTimeline()
            true
        } catch (failure: Throwable) {
            failClosed(failure)
        }
    }

    override fun decode(accessUnit: AacLcAccessUnit): AacLcDecodeReceipt = synchronized(lock) {
        val selected = config ?: throw IllegalStateException("AAC-LC decoder is not configured")
        check(!closed && !failed) { "AAC-LC decoder is retired" }
        val bytes = try {
            accessUnit.consumePayload()
        } catch (failure: Throwable) {
            failClosed(failure)
        }
        try {
            check(accessUnit.epoch == selected.epoch &&
                accessUnit.mediaTimeScale == selected.sampleRateHz.toLong()
            ) { "AAC-LC access unit belongs to another epoch or media-time scale" }
            check(accessUnit.sequence in 0..MAX_SEQUENCE &&
                bytes.size in 1..MAX_ACCESS_UNIT_BYTES
            ) { "AAC-LC access unit is outside bounded raw payload range" }
            previousInputSequence?.let { previous ->
                check(accessUnit.sequence == ((previous + 1) and MAX_SEQUENCE)) {
                    "AAC-LC sequence is not contiguous"
                }
            }
            nextInputMediaTimeValue?.let { next ->
                check(accessUnit.mediaTimeValue == next) {
                    "AAC-LC media time is not contiguous"
                }
            }
            val nextTime = Math.addExact(accessUnit.mediaTimeValue,
                selected.samplesPerFrame.toLong())
            val ptsUs = mediaTimeToMicros(accessUnit.mediaTimeValue, selected.sampleRateHz)
            val selectedCodec = checkNotNull(codec)
            var queuedFrames = drainOutput(selectedCodec, selected)
            check(pending.size < MAX_PENDING_ACCESS_UNITS) {
                "AAC-LC decoder retained too many undecoded access units"
            }
            val inputIndex = selectedCodec.dequeueInputBuffer(INPUT_WAIT_US)
            check(inputIndex >= 0) { "AAC-LC codec input was not available within deadline" }
            val input = selectedCodec.inputBuffer(inputIndex)
                ?: throw IOException("AAC-LC codec returned no input buffer")
            input.clear()
            check(bytes.size <= input.remaining()) {
                "AAC-LC access unit exceeds codec input buffer"
            }
            input.put(bytes)
            selectedCodec.queueInputBuffer(inputIndex, bytes.size, ptsUs)
            pending.addLast(PendingInput(accessUnit.mediaTimeValue, selected.samplesPerFrame))
            previousInputSequence = accessUnit.sequence
            nextInputMediaTimeValue = nextTime
            queuedFrames = Math.addExact(queuedFrames, drainOutput(selectedCodec, selected))
            AacLcDecodeReceipt(
                accepted = true,
                queuedPcmFrames = queuedFrames,
                firstQueuedMediaTimeValue = checkReadyOutput().firstQueuedMediaTimeValue,
            )
        } catch (failure: Throwable) {
            failClosed(failure)
        } finally {
            bytes.fill(0)
        }
    }

    override fun pausePlayback() = synchronized(lock) {
        check(!closed && !failed) { "AAC-LC decoder is retired" }
        // Before the authenticated magic cookie there is no native output to silence.
        if (config == null) return@synchronized
        try {
            checkReadyOutput().pausePlayback()
            checkNotNull(codec).flush()
            resetTimeline()
        } catch (failure: Throwable) {
            failClosed(failure)
        }
    }

    override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean {
        val selected = synchronized(lock) {
            val selected = config ?: return false
            if (closed || failed || anchor.sampleRateHz != selected.sampleRateHz ||
                output?.isReady != true ||
                output?.outputInstance !== anchor.outputInstance ||
                output?.playbackEpoch != anchor.outputEpoch ||
                output?.firstQueuedMediaTimeValue != anchor.firstMediaTimeValue
            ) return false
            selected to checkNotNull(output)
        }
        // Do not hold the decoder lock while the AudioTrack worker awaits native play:
        // authenticated APAP and mctl flush must still make progress on the ingress owner.
        val started = selected.second.startPlayback(anchor)
        return synchronized(lock) {
            started && !closed && !failed && config === selected.first &&
                output === selected.second && selected.second.isReady &&
                selected.second.outputInstance === anchor.outputInstance &&
                selected.second.playbackEpoch == anchor.outputEpoch
        }
    }

    override fun flush() = synchronized(lock) {
        check(!closed && !failed && config != null) { "AAC-LC decoder is not ready to flush" }
        try {
            checkReadyOutput().flush()
            checkNotNull(codec).flush()
            resetTimeline()
        } catch (failure: Throwable) {
            failClosed(failure)
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        failed = true
        var failure: Throwable? = null
        output?.let { nativeOutput ->
            try {
                nativeOutput.close()
                output = null
            } catch (problem: Throwable) {
                failure = problem
            }
        }
        codec?.let { selectedCodec ->
            try {
                selectedCodec.close()
                codec = null
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        clearPending()
        config = null
        if (failure != null) throw IOException("AAC-LC decoder retirement incomplete", failure)
        closed = true
    }

    private fun drainOutput(
        selectedCodec: MainBufferedAacLcCodecDriver,
        selected: AacLcDecoderConfig,
    ): Int {
        var queuedFrames = 0
        repeat(MAX_DRAIN_EVENTS_PER_ACCESS_UNIT) {
            when (val event = selectedCodec.dequeueOutputBuffer(OUTPUT_POLL_US)) {
                MainBufferedAacLcCodecEvent.TryAgain -> return queuedFrames
                MainBufferedAacLcCodecEvent.BuffersChanged -> Unit
                is MainBufferedAacLcCodecEvent.Format -> {
                    check(event.sampleRateHz == selected.sampleRateHz &&
                        event.channels == selected.channels &&
                        event.pcmEncoding == AudioFormat.ENCODING_PCM_16BIT
                    ) { "AAC-LC codec output format changed away from selected stereo PCM16" }
                    outputFormatValidated = true
                }
                is MainBufferedAacLcCodecEvent.Buffer -> {
                    try {
                        check(event.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                            "AAC-LC codec emitted unexpected end of stream"
                        }
                        if (event.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            check(event.size in 0..MAX_CODEC_CONFIG_BYTES) {
                                "AAC-LC codec emitted unbounded configuration output"
                            }
                            return@repeat
                        }
                        check(outputFormatValidated) {
                            "AAC-LC codec emitted PCM before its exact output format was validated"
                        }
                        if (event.size > 0) {
                            queuedFrames = Math.addExact(queuedFrames,
                                queueDecodedPcm(selectedCodec, event, selected))
                        }
                    } finally {
                        selectedCodec.releaseOutputBuffer(event.index)
                    }
                }
            }
        }
        // A decoder that never reaches TRY_AGAIN_LATER could monopolize the ingress callback.
        throw IOException("AAC-LC codec exceeded the bounded output-drain budget")
    }

    private fun queueDecodedPcm(
        selectedCodec: MainBufferedAacLcCodecDriver,
        event: MainBufferedAacLcCodecEvent.Buffer,
        selected: AacLcDecoderConfig,
    ): Int {
        check(event.offset >= 0 && event.size in 1..MAX_DECODED_PCM_BYTES &&
            event.size % BYTES_PER_STEREO_FRAME == 0
        ) { "AAC-LC codec emitted malformed or unbounded PCM16" }
        val buffer = selectedCodec.outputBuffer(event.index)
            ?: throw IOException("AAC-LC codec returned no output buffer")
        check(event.offset <= buffer.limit() && event.size <= buffer.limit() - event.offset) {
            "AAC-LC codec output range exceeds its buffer"
        }
        val firstPending = pending.firstOrNull()
            ?: throw IOException("AAC-LC codec emitted PCM without an authenticated access unit")
        val nextTime = nextOutputMediaTimeValue ?: firstPending.nextMediaTimeValue
        val expectedPtsUs = mediaTimeToMicros(nextTime, selected.sampleRateHz)
        check(event.presentationTimeUs in (expectedPtsUs - 1L)..(expectedPtsUs + 1L)) {
            "AAC-LC codec output timestamp differs from authenticated APAP media time"
        }
        val frames = event.size / BYTES_PER_STEREO_FRAME
        check(frames <= pending.sumOf { it.remainingFrames }) {
            "AAC-LC decoded more PCM than authenticated access units"
        }
        val pcm = ByteArray(event.size)
        try {
            buffer.duplicate().apply {
                position(event.offset)
                limit(event.offset + event.size)
            }.get(pcm)
            var remaining = frames
            var mediaTime = nextTime
            var offset = 0
            while (remaining > 0) {
                val count = minOf(remaining, MAX_PCM_FRAMES_PER_OFFER)
                val chunkBytes = count * BYTES_PER_STEREO_FRAME
                val chunk = pcm.copyOfRange(offset, offset + chunkBytes)
                try {
                    check(checkReadyOutput().offerPcmLittleEndian(chunk, mediaTime)) {
                        "AAC-LC decoded PCM queue was full or retired"
                    }
                } finally {
                    chunk.fill(0)
                }
                offset += chunkBytes
                remaining -= count
                mediaTime = Math.addExact(mediaTime, count.toLong())
            }
            consumePendingFrames(frames)
            nextOutputMediaTimeValue = mediaTime
            return frames
        } finally {
            pcm.fill(0)
        }
    }

    private fun consumePendingFrames(frames: Int) {
        var remaining = frames
        while (remaining > 0) {
            val first = pending.firstOrNull()
                ?: throw IOException("AAC-LC decoded more PCM than authenticated access units")
            val consumed = minOf(remaining, first.remainingFrames)
            first.remainingFrames -= consumed
            first.nextMediaTimeValue = Math.addExact(first.nextMediaTimeValue,
                consumed.toLong())
            remaining -= consumed
            if (first.remainingFrames == 0) pending.removeFirst()
        }
    }

    private fun resetTimeline() {
        clearPending()
        previousInputSequence = null
        nextInputMediaTimeValue = null
        nextOutputMediaTimeValue = null
    }

    private fun clearPending() = pending.clear()

    private fun checkReadyOutput(): MainBufferedPcm16Output {
        check(!closed && !failed) { "AAC-LC decoder is retired" }
        return output?.takeIf { it.isReady }
            ?: throw IllegalStateException("AAC-LC native PCM output is not ready")
    }

    private fun failClosed(failure: Throwable): Nothing {
        failed = true
        try {
            output?.close()
            output = null
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        try {
            codec?.close()
            codec = null
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        clearPending()
        config = null
        throw failure
    }

    private fun validConfig(selected: AacLcDecoderConfig): Boolean {
        if (selected.sampleRateHz != 44_100 && selected.sampleRateHz != 48_000 ||
            selected.channels != 2 || selected.samplesPerFrame != 1_024 || selected.epoch <= 0
        ) return false
        val asc = selected.audioSpecificConfig
        if (asc.size != 2) return false
        val bits = ((asc[0].toInt() and 0xff) shl 8) or (asc[1].toInt() and 0xff)
        val objectType = bits ushr 11
        val frequencyIndex = (bits ushr 7) and 0x0f
        val channels = (bits ushr 3) and 0x0f
        return objectType == 2 &&
            frequencyIndex == (if (selected.sampleRateHz == 44_100) 4 else 3) &&
            channels == 2 && bits and 0x07 == 0
    }

    private fun mediaTimeToMicros(mediaTimeValue: Long, sampleRateHz: Int): Long {
        require(mediaTimeValue >= 0) { "AAC-LC media time is negative" }
        return Math.multiplyExact(mediaTimeValue, 1_000_000L) / sampleRateHz
    }

    companion object {
        private const val MAX_SEQUENCE = 0x00ff_ffff
        private const val MAX_ACCESS_UNIT_BYTES = MainBufferedAacLcIngress.MAX_ACCESS_UNIT_BYTES
        private const val MAX_DECODED_PCM_BYTES = 64 * 1024
        private const val MAX_CODEC_CONFIG_BYTES = 64
        private const val MAX_PCM_FRAMES_PER_OFFER = 4_096
        private const val BYTES_PER_STEREO_FRAME = 4
        private const val MAX_PENDING_ACCESS_UNITS = 32
        private const val MAX_DRAIN_EVENTS_PER_ACCESS_UNIT = 32
        private const val INPUT_WAIT_US = 5_000L
        private const val OUTPUT_POLL_US = 0L

        fun open(): AndroidMainBufferedAacLcDecoder = AndroidMainBufferedAacLcDecoder(
            codecFactory = ::openAndroidCodec,
            outputFactory = { sampleRateHz ->
                AndroidMainBufferedPcm16Output.open(sampleRateHz) { }
            },
        )

        private fun openAndroidCodec(config: AacLcDecoderConfig): MainBufferedAacLcCodecDriver {
            check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                "AAC-LC PCM16 native byte order is not little-endian"
            }
            val format = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, config.sampleRateHz)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, config.channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_BYTES)
                setInteger(MediaFormat.KEY_IS_ADTS, 0)
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                setByteBuffer("csd-0", ByteBuffer.wrap(config.audioSpecificConfig))
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return AndroidCodecDriver(codec)
            } catch (failure: Throwable) {
                try { codec.release() } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }
}

private class AndroidCodecDriver(private val codec: MediaCodec) : MainBufferedAacLcCodecDriver {
    private val info = MediaCodec.BufferInfo()
    private var closed = false

    override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
    override fun inputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
    override fun queueInputBuffer(index: Int, size: Int, presentationTimeUs: Long) =
        codec.queueInputBuffer(index, 0, size, presentationTimeUs, 0)

    override fun dequeueOutputBuffer(timeoutUs: Long): MainBufferedAacLcCodecEvent {
        val index = codec.dequeueOutputBuffer(info, timeoutUs)
        return when (index) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> MainBufferedAacLcCodecEvent.TryAgain
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> MainBufferedAacLcCodecEvent.BuffersChanged
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val format = codec.outputFormat
                MainBufferedAacLcCodecEvent.Format(
                    sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    },
                )
            }
            else -> {
                check(index >= 0) { "AAC-LC codec returned unexpected output index $index" }
                MainBufferedAacLcCodecEvent.Buffer(index, info.offset, info.size,
                    info.presentationTimeUs, info.flags)
            }
        }
    }

    override fun outputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
    override fun releaseOutputBuffer(index: Int) = codec.releaseOutputBuffer(index, false)
    override fun flush() = codec.flush()

    override fun close() {
        if (closed) return
        try {
            codec.stop()
        } finally {
            codec.release()
            closed = true
        }
    }
}
