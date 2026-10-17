package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.BoundedOperationDeadline
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val retirementJoinTimeoutMillis: Long = DEFAULT_RETIREMENT_JOIN_TIMEOUT_MILLIS,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    /** Stop was requested; [retired] alone proves that every worker exited. */
    private val closed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private val listenStarted = AtomicBoolean(false)
    private val receivedPackets = AtomicInteger()
    private val decryptedPackets = AtomicInteger()
    private val authenticationFailures = AtomicInteger()
    private val stateLock = Any()
    private val retirementAttemptLock = Any()
    private val ownedEndpoints = IdentityHashMap<Closeable, Unit>()
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var dataThread: Thread? = null
    private var controlThread: Thread? = null
    private var playbackStarted = false

    init {
        require(
            retirementJoinTimeoutMillis in 1L..BoundedOperationDeadline.MAX_TIMEOUT_MILLIS,
        ) {
            "retirementJoinTimeoutMillis must be between 1 and " +
                BoundedOperationDeadline.MAX_TIMEOUT_MILLIS
        }
    }

    fun listen(listener: Listener): Pair<Int, Int> {
        try {
            return synchronized(stateLock) {
                check(!closed.get()) { "audio stream is closed" }
                check(listenStarted.compareAndSet(false, true)) {
                    "audio stream is already listening"
                }
                val data = bindOwnedPortLocked()
                val control = bindOwnedPortLocked()
                dataSocket = data
                controlSocket = control
                val dataWorker = Thread(
                    { runData(data, listener) },
                    "airplay-audio-rx",
                ).apply { isDaemon = true }
                val controlWorker = Thread(
                    { runControl(control) },
                    "airplay-rtcp-rx",
                ).apply { isDaemon = true }
                dataThread = dataWorker
                controlThread = controlWorker
                dataWorker.start()
                controlWorker.start()
                data.localPort to control.localPort
            }
        } catch (failure: Throwable) {
            synchronized(stateLock) { closed.set(true) }
            try {
                close()
            } catch (cleanupError: Error) {
                if (cleanupError !== failure) cleanupError.addSuppressedBounded(failure)
                throw cleanupError
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressedBounded(cleanupFailure)
            }
            throw failure
        }
    }

    override fun close() {
        synchronized(stateLock) { closed.set(true) }
        if (retired.get()) return
        synchronized(retirementAttemptLock) {
            if (retired.get()) return@synchronized
            val deadline = BoundedOperationDeadline(retirementJoinTimeoutMillis)
            val attemptedEndpoints = IdentityHashMap<Closeable, Unit>()
            var failure = closeOwnedEndpoints(attemptedEndpoints)
            val workers = synchronized(stateLock) {
                listOfNotNull(dataThread, controlThread)
            }
            val interruptSucceeded = IdentityHashMap<Thread, Boolean>()
            workers.forEach { worker ->
                try {
                    worker.interrupt()
                    interruptSucceeded[worker] = true
                } catch (problem: Exception) {
                    interruptSucceeded[worker] = false
                    failure = combineRetirementFailure(failure, problem)
                }
            }
            workers.forEach { worker ->
                failure = if (worker === Thread.currentThread()) {
                    combineRetirementFailure(
                        failure,
                        IOException("audio stream worker retirement is pending"),
                    )
                } else {
                    joinWorker(
                        worker,
                        deadline,
                        failure,
                        interruptSucceeded[worker] == true,
                    )
                }
            }
            failure = combineRetirementFailure(
                failure,
                closeOwnedEndpoints(attemptedEndpoints),
            )
            val complete = synchronized(stateLock) {
                dataThread == null && controlThread == null && ownedEndpoints.isEmpty()
            }
            if (failure == null && complete) retired.set(true)
            failure?.let { throw it }
            check(complete) { "audio stream retirement remains unproven" }
        }
    }

    private fun runData(socket: DatagramSocket, listener: Listener) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (closed.get()) return else continue
            }
            val wire = packet.data.copyOf(packet.length)
            val packetNumber = receivedPackets.incrementAndGet()
            if (wire.size < RTP_HEADER_LEN + TAIL_LEN) {
                if (packetNumber == 1) {
                    logWarning(
                        TAG,
                        "audio stream type=$streamType short packet bytes=${wire.size}",
                    )
                }
                listener.onPacket(
                    wire,
                    null,
                    null,
                    IOException("audio packet shorter than RTP header plus tail"),
                )
                continue
            }

            val aad = wire.copyOfRange(4, RTP_HEADER_LEN)
            val sealedEnd = wire.size - NONCE_LEN
            val sealed = wire.copyOfRange(RTP_HEADER_LEN, sealedEnd)
            val shortNonce = wire.copyOfRange(sealedEnd, wire.size)
            val nonce = ByteArray(12).also { shortNonce.copyInto(it, 4) }
            val sample = readU32Be(wire, 4)

            val payload = try {
                AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
            } catch (error: Exception) {
                val failureNumber = authenticationFailures.incrementAndGet()
                if (failureNumber == 1) {
                    logWarning(
                        TAG,
                        "audio stream type=$streamType first decrypt failure " +
                            "wireBytes=${wire.size}",
                        error,
                    )
                }
                listener.onPacket(wire, null, sample, error)
                continue
            }
            val rtp = wire.copyOf(RTP_HEADER_LEN) + payload
            val decryptedNumber = decryptedPackets.incrementAndGet()
            if (decryptedNumber <= FIRST_PACKET_LOG_COUNT) {
                logInfo(
                    TAG,
                    "audio stream type=$streamType packet=$decryptedNumber sample=$sample " +
                        "wireBytes=${wire.size} payloadBytes=${payload.size}",
                )
            } else if (decryptedNumber % PACKET_LOG_INTERVAL == 0) {
                logInfo(
                    TAG,
                    "audio stream type=$streamType decrypted=$decryptedNumber " +
                        "authFailures=${authenticationFailures.get()}",
                )
            }
            listener.onPacket(wire, rtp, sample, null)
            if (!playbackStarted) {
                playbackStarted = true
                listener.onStarted(sample)
            }
            listener.onRtp(rtp, sample)
        }
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindOwnedPortLocked(): DatagramSocket {
        val socket = DatagramSocket(null)
        ownedEndpoints[socket] = Unit
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private fun closeOwnedEndpoints(
        attempted: IdentityHashMap<Closeable, Unit>,
    ): Exception? {
        val endpoints = synchronized(stateLock) { ownedEndpoints.keys.toList() }
        var failure: Exception? = null
        endpoints.forEach { endpoint ->
            if (attempted.put(endpoint, Unit) != null) return@forEach
            try {
                endpoint.close()
                synchronized(stateLock) {
                    ownedEndpoints.remove(endpoint)
                    if (dataSocket === endpoint) dataSocket = null
                    if (controlSocket === endpoint) controlSocket = null
                }
            } catch (problem: Exception) {
                failure = combineRetirementFailure(failure, problem)
            }
        }
        return failure
    }

    private fun joinWorker(
        worker: Thread,
        deadline: BoundedOperationDeadline,
        priorFailure: Exception?,
        mayAcknowledge: Boolean,
    ): Exception? {
        var failure = priorFailure
        val remainingMillis = deadline.remainingMillis()
        if (remainingMillis > 0L) {
            try {
                worker.join(remainingMillis)
            } catch (problem: InterruptedException) {
                Thread.currentThread().interrupt()
                return combineRetirementFailure(
                    failure,
                    IOException("interrupted while retiring an audio stream worker", problem),
                )
            }
        }
        if (worker.isAlive) {
            return combineRetirementFailure(
                failure,
                IOException("audio stream worker did not stop before the deadline"),
            )
        }
        if (mayAcknowledge) {
            synchronized(stateLock) {
                if (dataThread === worker) dataThread = null
                if (controlThread === worker) controlThread = null
            }
        }
        return failure
    }

    private fun combineRetirementFailure(first: Exception?, next: Exception?): Exception? {
        if (next == null) return first
        if (first == null) return next
        if (first !== next) first.addSuppressedBounded(next)
        return first
    }

    private fun Throwable.addSuppressedBounded(problem: Throwable) {
        if (problem !== this && suppressed.size < MAX_SUPPRESSED_RETIREMENT_FAILURES) {
            addSuppressed(problem)
        }
    }

    private fun logInfo(tag: String, message: String) {
        runCatching { android.util.Log.i(tag, message) }
    }

    private fun logWarning(tag: String, message: String, failure: Throwable? = null) {
        runCatching {
            if (failure == null) {
                android.util.Log.w(tag, message)
            } else {
                android.util.Log.w(tag, message, failure)
            }
        }
    }

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val RTP_HEADER_LEN = 12
        const val TAG_LEN = 16
        const val NONCE_LEN = 8
        const val TAIL_LEN = TAG_LEN + NONCE_LEN
        const val FIRST_PACKET_LOG_COUNT = 3
        const val PACKET_LOG_INTERVAL = 100
        const val DEFAULT_RETIREMENT_JOIN_TIMEOUT_MILLIS = 2_000L
        const val MAX_SUPPRESSED_RETIREMENT_FAILURES = 8
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    /** Opus input uses the selected rate; its RTP timestamp clock remains 48 kHz. */
    fun opusCaptureRate(bits: Long): Int = when {
        bits and 0x40000000L != 0L -> 48_000
        bits and 0x20000000L != 0L -> 24_000
        bits and 0x10000000L != 0L -> 16_000
        else -> 48_000
    }

    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        val isAacLc = (bits and (AAC_LC_44K_STEREO or AAC_LC_48K_STEREO)) != 0L
        val isOpus = (bits and OPUS_MONO) != 0L
        val pcm = PCM_FORMAT[bits]
        return when {
            isOpus -> AudioFormat(AudioCodecKind.OPUS, 48_000, 1, payloadType, audioType)
            isAacLc -> AudioFormat(
                AudioCodecKind.AAC_LC,
                if ((bits and AAC_LC_48K_STEREO) != 0L) 48_000 else 44_100,
                2,
                payloadType,
                audioType,
            )
            pcm != null -> AudioFormat(AudioCodecKind.LPCM, pcm.first, pcm.second, payloadType, audioType)
            else -> AudioFormat(AudioCodecKind.LPCM, 44_100, 2, payloadType, audioType)
        }
    }

    private const val AAC_LC_44K_STEREO = 0x400000L
    private const val AAC_LC_48K_STEREO = 0x800000L
    private const val OPUS_MONO = 0x10000000L or 0x20000000L or 0x40000000L

    private val PCM_FORMAT = mapOf(
        0x4L to (8_000 to 1),
        0x8L to (8_000 to 2),
        0x10L to (16_000 to 1),
        0x20L to (16_000 to 2),
        0x40L to (24_000 to 1),
        0x80L to (24_000 to 2),
        0x100L to (32_000 to 1),
        0x200L to (32_000 to 2),
        0x400L to (44_100 to 1),
        0x800L to (44_100 to 2),
        0x4000L to (48_000 to 1),
        0x8000L to (48_000 to 2),
    )
}
