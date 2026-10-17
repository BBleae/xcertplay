package com.shilapi.xcertplay.airplay.mainbuffered

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Native adapter for the session-owned, initially silent APAP PCM output. */
internal class AndroidMainBufferedPcm16Output private constructor(
    track: AudioTrack,
    sampleRateHz: Int,
    onClosed: (AndroidMainBufferedPcm16Output) -> Unit,
) : MainBufferedPcm16Output {
    private val output = MainBufferedPcm16ControlledOutput(
        track = object : MainBufferedPcm16NativeTrack {
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
                track.write(bytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)

            override fun play() = track.play()
            override fun pause() = track.pause()
            override fun flush() = track.flush()
            override fun release() = track.release()
        },
        onClosed = { onClosed(this) },
        sampleRateHz = sampleRateHz,
    )

    override val isReady: Boolean get() = output.isReady
    override val outputInstance: Any get() = output.outputInstance
    override val playbackEpoch: Long get() = output.playbackEpoch
    override val firstQueuedMediaTimeValue: Long? get() = output.firstQueuedMediaTimeValue

    override fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean =
        output.offerPcmLittleEndian(pcm, mediaTimeValue)

    override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean =
        output.startPlayback(anchor)

    override fun pausePlayback() = output.pausePlayback()

    override fun flush() = output.flush()

    override fun close() = output.close()

    private fun start() = output.start()

    companion object {
        private const val MAX_TRACK_BUFFER_BYTES = 1024 * 1024
        private const val MIN_TRACK_BUFFER_BYTES = 64 * 1024

        fun open(
            sampleRateHz: Int = MainBufferedPcm16Ingress.SAMPLE_RATE,
            onClosed: (AndroidMainBufferedPcm16Output) -> Unit,
        ): AndroidMainBufferedPcm16Output {
            require(sampleRateHz == 44_100 || sampleRateHz == MainBufferedPcm16Ingress.SAMPLE_RATE) {
                "APAP PCM output supports only 44.1 or 48 kHz"
            }
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer in 1..(MAX_TRACK_BUFFER_BYTES / 4)) {
                "AudioTrack cannot provide a bounded APAP PCM buffer"
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minOf(MAX_TRACK_BUFFER_BYTES,
                    maxOf(minBuffer * 4, MIN_TRACK_BUFFER_BYTES)))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "APAP PCM AudioTrack was not initialized"
                }
                return AndroidMainBufferedPcm16Output(track, sampleRateHz, onClosed)
                    .also { it.start() }
            } catch (failure: Throwable) {
                try {
                    track.release()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }
}
