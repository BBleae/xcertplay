package com.shilapi.xcertplay.airplay.rcs.logging

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * An already-authorized opaque archive. [sizeBytes] is authoritative: the sender consumes exactly
 * that many bytes and never probes EOF, because a valid live InputStream may block indefinitely.
 * Neither archive format nor provenance is inferred here.
 */
interface CarPlayLoggingArchiveSource {
    val sizeBytes: Long
    fun openStream(): InputStream
}

class CarPlayLoggingArchiveBytes(bytes: ByteArray) : CarPlayLoggingArchiveSource {
    private val ownedBytes = bytes.copyOf()
    override val sizeBytes: Long get() = ownedBytes.size.toLong()
    override fun openStream(): InputStream = ByteArrayInputStream(ownedBytes)
}

enum class CarPlayLoggingArchiveState {
    IDLE,
    SENDING,
    AWAITING_ACK,
    COMPLETED,
    SAVE_FAILED,
    FAILED,
    CLOSED,
}

sealed interface CarPlayLoggingDataSessionEvent {
    data object ArchiveSent : CarPlayLoggingDataSessionEvent
    data class StatisticsIntervalRequested(val interval: ULong) : CarPlayLoggingDataSessionEvent
    data object ArchiveCompleted : CarPlayLoggingDataSessionEvent
    data object ArchiveSaveFailed : CarPlayLoggingDataSessionEvent
}

/** Product policy, separate from the validity of a 64-bit NSUInteger on the OPACK wire. */
data class CarPlayLoggingDataSessionPolicy(
    val maxStatisticsInterval: ULong = ULong.MAX_VALUE,
)

/**
 * One LoggingData stream's accessory-side archive transfer. [acceptFromPhoneBlocking] performs
 * source I/O and RCS sends; a future RCS handler must call it on a serial worker, not its receive
 * callback. The transport owner must enforce its own bounded send timeout/close: this class's
 * [close] does not interrupt a send already admitted to the injected callback. No transport or
 * capability is registered by this class.
 *
 * An ACK has no sessionID on the wire, so it is meaningful only while this exact stream awaits
 * the one archive that this instance sent. A repeated archive request can never reopen the source.
 */
class CarPlayLoggingDataSessionSender(
    private val sessionId: String,
    private val archiveSource: CarPlayLoggingArchiveSource,
    private val send: (RcsMessage) -> Unit,
    private val codec: CarPlayLoggingDataCodec = CarPlayLoggingDataCodec(),
    private val policy: CarPlayLoggingDataSessionPolicy = CarPlayLoggingDataSessionPolicy(),
) : Closeable {
    private val stateLock = Object()
    private val sendLock = Object()
    private val inputCloseLock = Object()
    private var state = CarPlayLoggingArchiveState.IDLE
    private var requestedStatisticsInterval: ULong? = null
    private var activeInput: InputStream? = null

    init {
        codec.checkSessionId(sessionId)
    }

    val archiveState: CarPlayLoggingArchiveState
        get() = synchronized(stateLock) { state }

    /** Raw arm64 NSUInteger interval; no time unit or scheduling policy is presumed. */
    val statisticsInterval: ULong?
        get() = synchronized(stateLock) { requestedStatisticsInterval }

    fun acceptFromPhoneBlocking(message: RcsMessage): CarPlayLoggingDataSessionEvent {
        // Decode the complete typed envelope before touching the source or changing stream state.
        return when (val incoming = codec.decodeFromPhone(message)) {
            CarPlayLoggingDataMessage.RequestArchive -> {
                sendArchiveBlocking()
                CarPlayLoggingDataSessionEvent.ArchiveSent
            }

            is CarPlayLoggingDataMessage.RequestStatistics -> synchronized(stateLock) {
                ensureOpenLocked()
                if (incoming.interval > policy.maxStatisticsInterval) {
                    throw CarPlayLoggingDataProtocolException(
                        "LoggingData interval exceeds session policy",
                    )
                }
                requestedStatisticsInterval = incoming.interval
                CarPlayLoggingDataSessionEvent.StatisticsIntervalRequested(incoming.interval)
            }

            is CarPlayLoggingDataMessage.Ack -> synchronized(stateLock) {
                ensureOpenLocked()
                if (state != CarPlayLoggingArchiveState.AWAITING_ACK) {
                    throw CarPlayLoggingDataProtocolException(
                        "LoggingData ACK does not match an awaiting archive",
                    )
                }
                if (incoming.accepted == 0) {
                    state = CarPlayLoggingArchiveState.COMPLETED
                    CarPlayLoggingDataSessionEvent.ArchiveCompleted
                } else {
                    state = CarPlayLoggingArchiveState.SAVE_FAILED
                    CarPlayLoggingDataSessionEvent.ArchiveSaveFailed
                }
            }
        }
    }

    /** An injected producer may publish only after a statistics request; cadence is its policy. */
    fun publishStatistics(data: Map<*, *>) {
        val encoded = codec.encodeToPhone(CarPlayLoggingDataMessage.Statistics(data))
        synchronized(sendLock) {
            synchronized(stateLock) {
                ensureOpenLocked()
                if (requestedStatisticsInterval == null) {
                    throw CarPlayLoggingDataProtocolException(
                        "LoggingData statistics were not requested",
                    )
                }
                if (state == CarPlayLoggingArchiveState.SENDING) {
                    throw CarPlayLoggingDataProtocolException(
                        "LoggingData statistics cannot interleave archive chunks",
                    )
                }
            }
            try {
                sendChecked(encoded)
            } catch (error: Throwable) {
                failUnlessClosed()
                throw error
            }
        }
    }

    private fun sendArchiveBlocking() {
        synchronized(stateLock) {
            ensureOpenLocked()
            if (state != CarPlayLoggingArchiveState.IDLE) {
                throw CarPlayLoggingDataProtocolException("LoggingData archive request was replayed")
            }
            state = CarPlayLoggingArchiveState.SENDING
        }

        try {
            val size = archiveSource.sizeBytes
            if (size < 0 || size > codec.limits.maxArchiveBytes) {
                throw CarPlayLoggingDataProtocolException("LoggingData archive size is out of bounds")
            }
            val count = if (size == 0L) 0L else 1L + (size - 1L) / codec.limits.maxChunkBytes
            if (count > codec.limits.maxChunkCount) {
                throw CarPlayLoggingDataProtocolException("LoggingData archive needs too many chunks")
            }
            synchronized(sendLock) {
                val input = archiveSource.openStream()
                val closedBeforeAdmission = synchronized(stateLock) {
                    activeInput = input
                    state == CarPlayLoggingArchiveState.CLOSED
                }
                if (closedBeforeAdmission) {
                    closeOwnedInput(input)
                    throw IOException("LoggingData stream closed before archive opened")
                }
                try {
                    sendChecked(codec.encodeToPhone(
                        CarPlayLoggingDataMessage.ArchiveHeader(sessionId, count.toInt()),
                    ))
                    var remaining = size
                    repeat(count.toInt()) { index ->
                        val length = minOf(remaining, codec.limits.maxChunkBytes.toLong()).toInt()
                        val bytes = ByteArray(length)
                        readExact(input, bytes)
                        sendChecked(codec.encodeToPhone(
                            CarPlayLoggingDataMessage.ArchiveChunk(sessionId, index, bytes),
                        ))
                        remaining -= length
                    }
                    check(remaining == 0L)
                    synchronized(stateLock) {
                        ensureOpenLocked()
                        state = CarPlayLoggingArchiveState.AWAITING_ACK
                    }
                } finally {
                    closeOwnedInput(input)
                }
            }
        } catch (error: Throwable) {
            failUnlessClosed()
            throw error
        }
    }

    private fun readExact(input: InputStream, bytes: ByteArray) {
        var offset = 0
        var zeroReads = 0
        while (offset < bytes.size) {
            synchronized(stateLock) { ensureOpenLocked() }
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw IOException("LoggingData archive ended before declared size")
            if (read == 0) {
                if (++zeroReads > MAX_ZERO_READS) {
                    throw IOException("LoggingData archive source made no progress")
                }
            } else {
                offset += read
                zeroReads = 0
            }
        }
    }

    private fun sendChecked(message: RcsMessage) {
        synchronized(stateLock) { ensureOpenLocked() }
        // A send admitted just before close() may finish afterward. Every next send rechecks the
        // state; close() itself never waits for a stalled transport callback.
        send(message)
    }

    private fun ensureOpenLocked() {
        if (state == CarPlayLoggingArchiveState.CLOSED) {
            throw IOException("LoggingData session is closed")
        }
        if (state == CarPlayLoggingArchiveState.FAILED) {
            throw IOException("LoggingData session has failed")
        }
    }

    private fun failUnlessClosed() {
        synchronized(stateLock) {
            if (state != CarPlayLoggingArchiveState.CLOSED) {
                state = CarPlayLoggingArchiveState.FAILED
            }
        }
    }

    override fun close() {
        val input = synchronized(stateLock) {
            state = CarPlayLoggingArchiveState.CLOSED
            activeInput
        }
        if (input != null) closeOwnedInput(input)
    }

    /** A failing close retains the stream so a second close can retry cleanup. */
    private fun closeOwnedInput(input: InputStream) = synchronized(inputCloseLock) {
        if (synchronized(stateLock) { activeInput !== input }) return@synchronized
        input.close()
        synchronized(stateLock) {
            if (activeInput === input) activeInput = null
        }
    }

    private companion object {
        const val MAX_ZERO_READS = 8
    }
}
