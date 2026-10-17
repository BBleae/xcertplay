package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest

/**
 * Caller-owned quarantine destination. `begin` must persist the bounded header metadata alongside
 * the archive. `append` must not expose its bytes as an installed/trusted theme. `abort` invalidates
 * all partial state and should be idempotent. `commit` returns true only after the caller-owned
 * implementation has durably committed both archive and metadata to quarantine (for example,
 * synced file contents and directory entry). A mere buffered write must return false.
 */
interface CarPlayUpdateDataQuarantineSink {
    @Throws(IOException::class)
    fun begin(header: CarPlayUpdateDataMessage.Header)

    @Throws(IOException::class)
    fun append(bytes: ByteArray)

    @Throws(IOException::class)
    fun commit(receipt: UntrustedCarPlayUpdateDataReceipt): Boolean

    @Throws(IOException::class)
    fun abort()
}

data class CarPlayUpdateDataMetadataDigest(
    val byteCount: Int,
    val sha256: String,
)

/** Whole-archive checksum was verified, but no certificate/signature/profile trust was evaluated. */
class UntrustedCarPlayUpdateDataReceipt internal constructor(
    val sessionId: String,
    val version: Long,
    val chunkCount: Int,
    val archiveBytes: Long,
    val archiveSha256: String,
    val certificate: CarPlayUpdateDataMetadataDigest?,
    val signature: CarPlayUpdateDataMetadataDigest?,
    val additions: CarPlayUpdateDataMetadataDigest?,
)

sealed interface CarPlayUpdateDataReceiveResult {
    data class Receiving(
        val nextChunkIndex: Int,
        val archiveBytes: Long,
    ) : CarPlayUpdateDataReceiveResult

    /** No success ACK may be sent from this state. */
    data class AwaitingDurableCommit(
        val receipt: UntrustedCarPlayUpdateDataReceipt,
    ) : CarPlayUpdateDataReceiveResult

    /** Caller may encode and send the nonzero ACK; partial quarantine data was invalidated. */
    data class Rejected(
        val reason: String,
        val cleanupFailed: Boolean,
        val ack: CarPlayUpdateDataMessage.Ack = CarPlayUpdateDataMessage.Ack(1),
    ) : CarPlayUpdateDataReceiveResult
}

/**
 * One strictly ordered UpdateData transfer. Only the current bounded chunk and SHA-256 state are
 * held in memory; archive bytes go directly to the caller-owned quarantine sink. No UI Sync, CAF,
 * fileTransfer capability, or profile trust state is activated by this receiver.
 */
class CarPlayUpdateDataReceiver(
    private val sink: CarPlayUpdateDataQuarantineSink,
    private val limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
) : Closeable {
    private val codec = CarPlayUpdateDataCodec(limits)
    private val archiveDigest = MessageDigest.getInstance("SHA-256")
    private var state = State.EXPECTING_HEADER
    private var sessionId: String? = null
    private var version = 0L
    private var chunkCount = 0
    private var nextChunkIndex = 0
    private var archiveBytes = 0L
    private var certificateDigest: CarPlayUpdateDataMetadataDigest? = null
    private var signatureDigest: CarPlayUpdateDataMetadataDigest? = null
    private var additionsDigest: CarPlayUpdateDataMetadataDigest? = null
    private var pendingReceipt: UntrustedCarPlayUpdateDataReceipt? = null
    private var abortPending = false

    @Synchronized
    fun receive(message: RcsMessage): CarPlayUpdateDataReceiveResult {
        check(state != State.CLOSED && state != State.COMMITTED && state != State.REJECTED) {
            "UpdateData receiver is terminal"
        }
        if (state == State.AWAITING_DURABLE_COMMIT) {
            return reject("UpdateData message arrived after final chunk")
        }
        val decoded = try {
            codec.decode(message)
        } catch (_: CarPlayUpdateDataProtocolException) {
            return reject("Invalid UpdateData message")
        }
        return try {
            when (decoded) {
                is CarPlayUpdateDataMessage.Header -> receiveHeader(decoded)
                is CarPlayUpdateDataMessage.Chunk -> receiveChunk(decoded)
                is CarPlayUpdateDataMessage.Ack -> reject("Unexpected UpdateData ACK from sender")
            }
        } catch (_: IOException) {
            reject("UpdateData quarantine write failed")
        } catch (_: RuntimeException) {
            // A caller-supplied sink may throw unchecked exceptions too. Never acknowledge it.
            reject("UpdateData quarantine write failed")
        }
    }

    /**
     * Ask the caller-owned sink to durably commit the exact quarantined bytes and metadata. ACK 0
     * is returned only if that sink confirms success. A failed commit invalidates the transfer and
     * returns a nonzero ACK. This never validates the certificate, signature, or profile.
     */
    @Synchronized
    fun confirmDurableQuarantineCommit(
        receipt: UntrustedCarPlayUpdateDataReceipt,
    ): CarPlayUpdateDataMessage.Ack {
        check(state == State.AWAITING_DURABLE_COMMIT && receipt === pendingReceipt) {
            "No matching UpdateData quarantine commit is pending"
        }
        val committed = try {
            sink.commit(receipt)
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        if (!committed) return reject("UpdateData quarantine commit failed").ack
        pendingReceipt = null
        state = State.COMMITTED
        return CarPlayUpdateDataMessage.Ack(0)
    }

    @Synchronized
    fun cancel(): CarPlayUpdateDataReceiveResult.Rejected {
        check(state != State.CLOSED && state != State.COMMITTED && state != State.REJECTED) {
            "UpdateData receiver is terminal"
        }
        return reject("UpdateData transfer cancelled")
    }

    @Synchronized
    override fun close() {
        if (state == State.CLOSED && !abortPending) return
        if (state != State.COMMITTED && state != State.REJECTED) abortPending = true
        pendingReceipt = null
        // Keep the state terminal even if the caller-owned sink's abort throws. A later close()
        // retries invalidating its partial quarantine; no route back to ACK 0 exists.
        state = State.CLOSED
        if (abortPending) {
            sink.abort()
            abortPending = false
        }
    }

    private fun receiveHeader(
        header: CarPlayUpdateDataMessage.Header,
    ): CarPlayUpdateDataReceiveResult {
        if (state != State.EXPECTING_HEADER) return reject("Duplicate UpdateData header")
        if (header.chunkCount.toLong() > limits.maxArchiveBytes) {
            return reject("UpdateData chunk count exceeds archive byte budget")
        }
        sink.begin(header)
        sessionId = header.sessionId
        version = header.version
        chunkCount = header.chunkCount
        certificateDigest = digestMetadata(header.certificate)
        signatureDigest = digestMetadata(header.signature)
        additionsDigest = digestMetadata(header.additions)
        state = State.RECEIVING
        return CarPlayUpdateDataReceiveResult.Receiving(0, 0)
    }

    private fun receiveChunk(
        chunk: CarPlayUpdateDataMessage.Chunk,
    ): CarPlayUpdateDataReceiveResult {
        if (state != State.RECEIVING) return reject("UpdateData chunk arrived before header")
        if (chunk.sessionId != sessionId) return reject("UpdateData session ID changed")
        if (chunk.chunkIndex != nextChunkIndex) return reject("UpdateData chunk order changed")
        val finalChunk = nextChunkIndex == chunkCount - 1
        if (finalChunk != (chunk.checksum != null)) {
            return reject("UpdateData checksum must appear only on final chunk")
        }
        val bytes = chunk.data
        if (bytes.size.toLong() > limits.maxArchiveBytes - archiveBytes) {
            return reject("UpdateData archive exceeds byte budget")
        }
        sink.append(bytes)
        archiveDigest.update(bytes)
        archiveBytes += bytes.size
        nextChunkIndex++
        if (!finalChunk) {
            return CarPlayUpdateDataReceiveResult.Receiving(nextChunkIndex, archiveBytes)
        }

        val digest = archiveDigest.digest()
        val checksum = requireNotNull(chunk.checksum)
        if (!MessageDigest.isEqual(digest, decodeHex(checksum))) {
            return reject("UpdateData archive checksum mismatch")
        }
        val receipt = UntrustedCarPlayUpdateDataReceipt(
            sessionId = requireNotNull(sessionId),
            version = version,
            chunkCount = chunkCount,
            archiveBytes = archiveBytes,
            archiveSha256 = digest.toLowerHex(),
            certificate = certificateDigest,
            signature = signatureDigest,
            additions = additionsDigest,
        )
        pendingReceipt = receipt
        state = State.AWAITING_DURABLE_COMMIT
        return CarPlayUpdateDataReceiveResult.AwaitingDurableCommit(receipt)
    }

    private fun reject(reason: String): CarPlayUpdateDataReceiveResult.Rejected {
        state = State.REJECTED
        pendingReceipt = null
        abortPending = true
        val cleanupFailed = try {
            sink.abort()
            abortPending = false
            false
        } catch (_: Exception) {
            true
        }
        return CarPlayUpdateDataReceiveResult.Rejected(reason, cleanupFailed)
    }

    private enum class State {
        EXPECTING_HEADER,
        RECEIVING,
        AWAITING_DURABLE_COMMIT,
        COMMITTED,
        REJECTED,
        CLOSED,
    }
}

private fun digestMetadata(bytes: ByteArray?): CarPlayUpdateDataMetadataDigest? = bytes?.let {
    CarPlayUpdateDataMetadataDigest(it.size, MessageDigest.getInstance("SHA-256").digest(it).toLowerHex())
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    for (byte in this@toLowerHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private fun decodeHex(text: String): ByteArray = ByteArray(text.length / 2) { index ->
    ((HEX_DIGITS.indexOf(text[index * 2]) shl 4) or
        HEX_DIGITS.indexOf(text[index * 2 + 1])).toByte()
}

private const val HEX_DIGITS = "0123456789abcdef"
