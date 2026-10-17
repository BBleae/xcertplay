package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.UltraDebugStatusMarkers
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackage
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

data class RcsMessage(
    val messageType: Int,
    val body: ByteArray,
) {
    fun encoded(maxPackageBytes: Int = ApTransportPackageCodec.DEFAULT_MAX_PACKAGE_BYTES): ByteArray =
        ApTransportPackageCodec.create(
            messageType = messageType,
            body = body,
            maxPackageBytes = maxPackageBytes,
        ).encoded()

    companion object {
        fun comm(body: ByteArray): RcsMessage =
            RcsMessage(ApTransportPackageCodec.MESSAGE_TYPE_COMM, body)
    }
}

class RcsProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class RcsPeerRejectedException(message: String) : IOException(message)

class RcsCandidateLimitException(message: String) : IOException(message)

/** Resource limits applied before allocating or retaining peer-controlled data. */
data class RcsChannelLimits(
    val maxEncryptedRemainderBytes: Int = RcsFrameCodec.DEFAULT_MAX_BUFFERED_BYTES,
    val maxPlaintextRemainderBytes: Int =
        ApTransportPackageCodec.DEFAULT_MAX_PACKAGE_BYTES + 16 * 1024,
    val maxPackageBytes: Int = ApTransportPackageCodec.DEFAULT_MAX_PACKAGE_BYTES,
    val maxPendingPackages: Int = 256,
    val maxPendingBodyBytes: Int = ApTransportPackageCodec.DEFAULT_MAX_PACKAGE_BYTES,
    val maxTimeoutMillis: Long = 5 * 60 * 1_000L,
) {
    init {
        require(maxEncryptedRemainderBytes >= MIN_ENCRYPTED_FRAME_BYTES)
        require(maxPlaintextRemainderBytes >= ApTransportPackageCodec.HEADER_SIZE)
        require(maxPackageBytes >= ApTransportPackageCodec.HEADER_SIZE)
        require(maxPlaintextRemainderBytes >= maxPackageBytes) {
            "maxPlaintextRemainderBytes must accommodate maxPackageBytes"
        }
        require(maxPendingPackages > 0)
        require(maxPendingBodyBytes >= 0)
        require(maxTimeoutMillis in 1..Int.MAX_VALUE.toLong())
    }

    private companion object {
        const val MIN_ENCRYPTED_FRAME_BYTES = 18
    }
}

/**
 * One connected RCS stream.
 *
 * Every socket owns its own directional cipher state. In particular, a rejected connection can
 * never advance the counter later used by an authenticated connection.
 */
class RcsChannel private constructor(
    private val socket: Socket,
    private val frameCodec: RcsFrameCodec,
    private val limits: RcsChannelLimits,
    private val onTrace: (String) -> Unit,
) : Closeable {
    private val sendLock = Object()
    private val receiveLock = Object()
    private val pendingPackages = ArrayDeque<ApTransportPackage>()
    private val closed = AtomicBoolean(false)
    private var pendingBodyBytes = 0
    private val incomingCiphertext =
        BoundedByteAccumulator(limits.maxEncryptedRemainderBytes)
    private val incomingPlaintext =
        BoundedByteAccumulator(limits.maxPlaintextRemainderBytes)
    private var authenticatedFrame = false

    @Volatile
    private var peerEnded = false

    val isClosed: Boolean
        get() = closed.get()

    val isPeerEnded: Boolean
        get() = peerEnded

    val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address

    val localAddress: InetAddress?
        get() = (socket.localSocketAddress as? InetSocketAddress)?.address

    /**
     * Authenticates exactly the first complete encrypted frame on this candidate socket.
     *
     * The successfully advanced codec is retained by this channel. A caller may therefore promote
     * this candidate without replaying or losing the first frame. Failed candidates are simply
     * discarded with their private codec state.
     */
    fun authenticateFirstFrame(timeoutMillis: Long): Boolean {
        requireTimeout(timeoutMillis)
        synchronized(receiveLock) {
            ensureOpen()
            if (authenticatedFrame) return true
            val deadline = RcsReceiveDeadline(timeoutMillis)
            val plaintext = readOneAuthenticatedFrame(deadline) ?: return false
            authenticatedFrame = true
            bufferPlaintext(plaintext)
            trace("candidate authenticated peer=${safeAddress(socket.remoteSocketAddress)}")
            return true
        }
    }

    fun send(message: RcsMessage) {
        synchronized(sendLock) {
            ensureOpen()
            try {
                // Our `comm` payloads travel to the phone as 'cmnd' (see MESSAGE_TYPE_CMND).
                val plaintext = message.copy(messageType = wireTypeForSend(message.messageType))
                    .encoded(limits.maxPackageBytes)
                val encoded = frameCodec.encrypt(plaintext)
                socket.getOutputStream().apply {
                    write(encoded)
                    flush()
                }
                trace(
                    "TX type=0x${message.messageType.toUInt().toString(16)} " +
                        "bodyBytes=${message.body.size}" +
                        // Header words are a Debug bench diagnostic only.
                        if (UltraDebugStatusMarkers.enabled) " hdr=" +
                            plaintext.copyOfRange(4, ApTransportPackageCodec.HEADER_SIZE)
                                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        else "",
                )
            } catch (failure: Throwable) {
                trace(
                    "TX failed type=0x${message.messageType.toUInt().toString(16)} " +
                        "bodyBytes=${message.body.size} cause=${safeFailure(failure)}",
                )
                close()
                throw failure
            }
        }
    }

    fun sendComm(body: ByteArray) {
        send(RcsMessage.comm(body))
    }

    /** Returns null on timeout or clean peer EOF. */
    fun receiveNext(timeoutMillis: Long): RcsMessage? {
        requireTimeout(timeoutMillis)
        synchronized(receiveLock) {
            ensureOpen()
            try {
                decodePlaintextPackages()
            } catch (failure: Throwable) {
                throw closeForProtocolFailure(failure)
            }
            takePending()?.let { return it }
            ensureOpen()
            if (peerEnded) return null
            val deadline = RcsReceiveDeadline(timeoutMillis)

            while (true) {
                val plaintext = try {
                    readOneAuthenticatedFrame(deadline)
                } catch (failure: Throwable) {
                    close()
                    if (failure is RcsProtocolException) throw failure
                    throw RcsProtocolException("Invalid RCS encrypted frame", failure)
                } ?: return null
                authenticatedFrame = true
                try {
                    bufferPlaintext(plaintext)
                    decodePlaintextPackages()
                } catch (failure: Throwable) {
                    throw closeForProtocolFailure(failure)
                }
                takePending()?.let { return it }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
        synchronized(receiveLock) {
            pendingPackages.clear()
            pendingBodyBytes = 0
            incomingCiphertext.clear()
            incomingPlaintext.clear()
            frameCodec.close()
        }
        trace("channel closed")
    }

    private fun readOneAuthenticatedFrame(deadline: RcsReceiveDeadline): ByteArray? {
        val input = socket.getInputStream()
        val readBuffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            if (incomingCiphertext.size >= FRAME_HEADER_BYTES) {
                val payloadBytes = readU16Le(incomingCiphertext, 0)
                if (payloadBytes > MAX_FRAME_PLAINTEXT_BYTES) {
                    throw RcsProtocolException("RCS encrypted frame length exceeds the wire maximum")
                }
                val frameBytes = FRAME_HEADER_BYTES + payloadBytes + FRAME_TAG_BYTES
                if (incomingCiphertext.size >= frameBytes) {
                    val frame = incomingCiphertext.removePrefix(frameBytes)
                    val decrypted = try {
                        frameCodec.decrypt(frame)
                    } catch (failure: Throwable) {
                        throw RcsProtocolException("RCS frame authentication failed", failure)
                    }
                    if (decrypted.rest.isNotEmpty()) {
                        throw RcsProtocolException("RCS frame decoder retained complete input")
                    }
                    return decrypted.data
                }
            }

            val remainingMillis = deadline.remainingMillis()
            if (remainingMillis == 0L) return null
            socket.soTimeout = min(remainingMillis, Int.MAX_VALUE.toLong()).toInt()
            val read = try {
                input.read(readBuffer)
            } catch (_: SocketTimeoutException) {
                return null
            } catch (failure: SocketException) {
                if (closed.get()) return null
                throw failure
            }
            if (read < 0) {
                if (incomingCiphertext.size > 0) {
                    throw RcsProtocolException(
                        "RCS peer ended with an incomplete encrypted frame",
                    )
                }
                if (incomingPlaintext.size > 0) {
                    throw RcsProtocolException(
                        "RCS peer ended with an incomplete APTransport package",
                    )
                }
                peerEnded = true
                trace("peer closed stream")
                return null
            }
            if (read == 0) continue
            try {
                incomingCiphertext.append(readBuffer, read)
            } catch (failure: IllegalArgumentException) {
                throw RcsProtocolException(
                    "RCS encrypted remainder exceeds ${limits.maxEncryptedRemainderBytes} bytes",
                    failure,
                )
            }
        }
    }

    private fun bufferPlaintext(plaintext: ByteArray) {
        try {
            incomingPlaintext.append(plaintext)
        } catch (failure: IllegalArgumentException) {
            throw RcsProtocolException(
                "RCS plaintext remainder exceeds ${limits.maxPlaintextRemainderBytes} bytes",
                failure,
            )
        }
    }

    private fun decodePlaintextPackages() {
        var decodedCount = 0
        while (incomingPlaintext.size >= ApTransportPackageCodec.HEADER_SIZE) {
            if (decodedCount >= limits.maxPendingPackages) {
                throw RcsProtocolException(
                    "RCS decoded package count exceeds ${limits.maxPendingPackages}",
                )
            }
            val declaredSize = readU32Be(incomingPlaintext, 0)
            if (declaredSize < ApTransportPackageCodec.HEADER_SIZE) {
                throw RcsProtocolException(
                    "APTransport package size $declaredSize is smaller than header " +
                        ApTransportPackageCodec.HEADER_SIZE,
                )
            }
            if (declaredSize > limits.maxPackageBytes) {
                throw RcsProtocolException(
                    "APTransport package size $declaredSize exceeds ${limits.maxPackageBytes}",
                )
            }
            if (incomingPlaintext.size < declaredSize) return
            val encodedPackage = incomingPlaintext.removePrefix(declaredSize)
            val packageValue = try {
                ApTransportPackageCodec.decode(
                    encodedPackage,
                    maxPackageBytes = limits.maxPackageBytes,
                )
            } catch (failure: Throwable) {
                throw RcsProtocolException("Invalid APTransport package", failure)
            }
            if (pendingPackages.size >= limits.maxPendingPackages) {
                throw RcsProtocolException(
                    "RCS pending package count exceeds ${limits.maxPendingPackages}",
                )
            }
            val bodyBytes = packageValue.body.size
            if (pendingBodyBytes.toLong() + bodyBytes > limits.maxPendingBodyBytes) {
                throw RcsProtocolException(
                    "RCS pending body data exceeds ${limits.maxPendingBodyBytes} bytes",
                )
            }
            pendingPackages.addLast(packageValue)
            pendingBodyBytes += bodyBytes
            decodedCount++
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IOException("RCS channel is closed")
    }

    private fun closeForProtocolFailure(failure: Throwable): RcsProtocolException {
        close()
        return if (failure is RcsProtocolException) {
            failure
        } else {
            RcsProtocolException("Invalid RCS payload", failure)
        }
    }

    private fun takePending(): RcsMessage? {
        ensureOpen()
        val packageValue = pendingPackages.pollFirst() ?: return null
        val body = packageValue.body
        pendingBodyBytes -= body.size
        trace(
            "RX type=0x${packageValue.messageType.toUInt().toString(16)} " +
                "bodyBytes=${body.size}" +
                if (UltraDebugStatusMarkers.enabled) " hdr=${packageValue.metadataHex}" else "",
        )
        return RcsMessage(normalizedReceivedType(packageValue.messageType), body)
    }

    private fun wireTypeForSend(messageType: Int): Int =
        if (messageType == ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
            ApTransportPackageCodec.MESSAGE_TYPE_CMND
        } else {
            messageType
        }

    /** A peer that is itself an accessory-side channel (tests, loopback) sends 'cmnd'. */
    private fun normalizedReceivedType(messageType: Int): Int =
        if (messageType == ApTransportPackageCodec.MESSAGE_TYPE_CMND) {
            ApTransportPackageCodec.MESSAGE_TYPE_COMM
        } else {
            messageType
        }

    private fun trace(message: String) {
        runCatching { onTrace(message.take(MAX_TRACE_CHARS)) }
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1..limits.maxTimeoutMillis) {
            "timeoutMillis must be in 1..${limits.maxTimeoutMillis}"
        }
    }

    companion object {
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val FRAME_HEADER_BYTES = 2
        private const val FRAME_TAG_BYTES = 16
        private const val MAX_FRAME_PLAINTEXT_BYTES = 0x4000
        private const val MAX_CONNECT_TIMEOUT_MILLIS = 60_000
        private const val MAX_BACKLOG = 32
        private const val MAX_TRACE_CHARS = 512

        /** Wraps an already accepted socket, taking ownership of it. */
        fun open(
            socket: Socket,
            readKey: ByteArray,
            writeKey: ByteArray,
            limits: RcsChannelLimits = RcsChannelLimits(),
            onTrace: (String) -> Unit = {},
        ): RcsChannel {
            var codec: RcsFrameCodec? = null
            return try {
                require(readKey.size == RcsFrameCodec.KEY_BYTES &&
                    writeKey.size == RcsFrameCodec.KEY_BYTES) {
                    "RCS channel requires two ${RcsFrameCodec.KEY_BYTES}-byte keys"
                }
                socket.tcpNoDelay = true
                val createdCodec = RcsFrameCodec.duplex(
                    readKey = readKey,
                    writeKey = writeKey,
                    maxBufferedBytes = limits.maxEncryptedRemainderBytes,
                )
                codec = createdCodec
                RcsChannel(socket, createdCodec, limits, onTrace)
            } catch (failure: Throwable) {
                codec?.close()
                runCatching { socket.close() }
                throw failure
            }
        }

        fun connect(
            remote: InetSocketAddress,
            readKey: ByteArray,
            writeKey: ByteArray,
            connectTimeoutMillis: Int = 5_000,
            limits: RcsChannelLimits = RcsChannelLimits(),
            onTrace: (String) -> Unit = {},
        ): RcsChannel {
            require(connectTimeoutMillis in 1..MAX_CONNECT_TIMEOUT_MILLIS) {
                "connectTimeoutMillis must be in 1..$MAX_CONNECT_TIMEOUT_MILLIS"
            }
            val socket = Socket()
            try {
                socket.connect(remote, connectTimeoutMillis)
                return open(socket, readKey, writeKey, limits, onTrace)
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }

        /** Binds exactly [bindAddress]; no wildcard or second-family listener is synthesized. */
        fun listen(
            readKey: ByteArray,
            writeKey: ByteArray,
            bindAddress: InetAddress,
            expectedPeerAddress: InetAddress? = null,
            port: Int = 0,
            backlog: Int = 4,
            maxAcceptedSockets: Int = 8,
            limits: RcsChannelLimits = RcsChannelLimits(),
            onTrace: (String) -> Unit = {},
        ): RcsListener {
            require(readKey.size == RcsFrameCodec.KEY_BYTES &&
                writeKey.size == RcsFrameCodec.KEY_BYTES) {
                "RCS listener requires two ${RcsFrameCodec.KEY_BYTES}-byte keys"
            }
            require(port in 0..0xffff) { "port must be in 0..65535" }
            require(backlog in 1..MAX_BACKLOG) { "backlog must be in 1..$MAX_BACKLOG" }
            require(maxAcceptedSockets in 1..MAX_ACCEPTED_SOCKETS) {
                "maxAcceptedSockets must be in 1..$MAX_ACCEPTED_SOCKETS"
            }
            val server = ServerSocket()
            return try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(bindAddress, port), backlog)
                RcsListener(
                    serverSocket = server,
                    readKey = readKey.copyOf(),
                    writeKey = writeKey.copyOf(),
                    expectedPeerAddress = expectedPeerAddress,
                    maxAcceptedSockets = maxAcceptedSockets,
                    limits = limits,
                    onTrace = onTrace,
                )
            } catch (failure: Throwable) {
                runCatching { server.close() }
                throw failure
            }
        }

        private fun readU16Le(bytes: BoundedByteAccumulator, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun readU32Be(bytes: BoundedByteAccumulator, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)

        private fun safeAddress(address: Any?): String =
            address?.toString()?.take(128)?.filterNot(Char::isISOControl) ?: "unknown"

        private fun safeFailure(failure: Throwable): String {
            return failure.javaClass.simpleName.take(64).ifEmpty { "Throwable" }
        }

        private const val MAX_ACCEPTED_SOCKETS = 64
    }
}

/** Elapsed-time budget: `nanoTime()` has an arbitrary signed origin and may wrap. */
internal class RcsReceiveDeadline(
    timeoutMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val startedAtNanos = nanoTime()
    private val budgetNanos: Long

    init {
        require(timeoutMillis in 1..300_000L)
        budgetNanos = timeoutMillis * NANOS_PER_MILLISECOND
    }

    fun remainingMillis(): Long {
        // Modular subtraction is valid for a bounded operation shorter than half the UInt64
        // clock range, including a negative origin or one wrap. A backwards clock fails closed.
        val elapsed = nanoTime() - startedAtNanos
        if (elapsed < 0) return 0
        val remaining = budgetNanos - elapsed
        return if (remaining <= 0) 0
        else (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** Compacting bounded byte queue; fragmented input is copied once instead of on every read. */
private class BoundedByteAccumulator(private val maxBytes: Int) {
    private var storage = ByteArray(minOf(INITIAL_CAPACITY, maxBytes))
    private var start = 0
    private var end = 0

    val size: Int
        get() = end - start

    fun clear() {
        storage.fill(0)
        start = 0
        end = 0
    }

    operator fun get(index: Int): Byte {
        require(index in 0 until size) { "Accumulator index $index is outside $size bytes" }
        return storage[start + index]
    }

    fun append(source: ByteArray, length: Int = source.size) {
        require(length in 0..source.size) { "Invalid append length $length" }
        require(size.toLong() + length <= maxBytes) {
            "Accumulator would exceed $maxBytes bytes"
        }
        ensureTailCapacity(length)
        source.copyInto(storage, destinationOffset = end, startIndex = 0, endIndex = length)
        end += length
    }

    fun removePrefix(length: Int): ByteArray {
        require(length in 0..size) { "Invalid prefix length $length for $size bytes" }
        val output = storage.copyOfRange(start, start + length)
        start += length
        if (start == end) {
            start = 0
            end = 0
        }
        return output
    }

    private fun ensureTailCapacity(additional: Int) {
        if (storage.size - end >= additional) return
        if (start > 0) {
            storage.copyInto(storage, destinationOffset = 0, startIndex = start, endIndex = end)
            end -= start
            start = 0
            if (storage.size - end >= additional) return
        }
        var target = maxOf(storage.size, 1)
        val required = end + additional
        while (target < required) {
            target = minOf(maxBytes, maxOf(target * 2, required))
        }
        storage = storage.copyOf(target)
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}

/** Receive-side socket factory returned by [RcsChannel.listen]. */
class RcsListener internal constructor(
    private val serverSocket: ServerSocket,
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val expectedPeerAddress: InetAddress?,
    private val maxAcceptedSockets: Int,
    private val limits: RcsChannelLimits,
    private val onTrace: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var acceptedSockets = 0

    val port: Int
        get() = serverSocket.localPort

    val localAddress: InetAddress
        get() = serverSocket.inetAddress

    @Synchronized
    fun accept(timeoutMillis: Long): RcsChannel? {
        require(timeoutMillis in 1..limits.maxTimeoutMillis) {
            "timeoutMillis must be in 1..${limits.maxTimeoutMillis}"
        }
        if (closed.get()) throw IOException("RCS listener is closed")
        synchronized(this) {
            if (acceptedSockets >= maxAcceptedSockets) {
                throw RcsCandidateLimitException(
                    "RCS listener accepted-socket limit $maxAcceptedSockets reached",
                )
            }
        }
        serverSocket.soTimeout = min(timeoutMillis, Int.MAX_VALUE.toLong()).toInt()
        val accepted = try {
            serverSocket.accept()
        } catch (_: SocketTimeoutException) {
            return null
        } catch (failure: SocketException) {
            if (closed.get()) return null
            throw failure
        }
        if (closed.get()) {
            runCatching { accepted.close() }
            return null
        }
        synchronized(this) { acceptedSockets++ }
        val remote = (accepted.remoteSocketAddress as? InetSocketAddress)?.address
        if (expectedPeerAddress != null && remote != expectedPeerAddress) {
            runCatching { accepted.close() }
            trace(
                "rejected unexpected peer=${remote?.hostAddress?.take(128) ?: "unknown"}",
            )
            throw RcsPeerRejectedException("RCS peer address did not match the negotiated peer")
        }
        trace("accepted peer=${remote?.hostAddress?.take(128) ?: "unknown"}")
        return RcsChannel.open(accepted, readKey, writeKey, limits, onTrace)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        // accept() owns this monitor while copying keys into a candidate. Closing the socket
        // first unblocks that accept, then this barrier prevents a zero-key candidate race.
        synchronized(this) {
            readKey.fill(0)
            writeKey.fill(0)
        }
        trace("listener closed")
    }

    private fun trace(message: String) {
        runCatching { onTrace(message.take(MAX_TRACE_CHARS)) }
    }

    private companion object {
        const val MAX_TRACE_CHARS = 512
    }
}
