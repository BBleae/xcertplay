package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val exactBind: Boolean = false,
    private val expectedPeerAddress: InetAddress? = null,
    private val maxRejectedPeers: Int = DEFAULT_MAX_REJECTED_PEERS,
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val readKey = readKey.copyOf()
    private val closed = AtomicBoolean(false)
    private val rejectedPeers = AtomicInteger(0)
    private val readCounter = AtomicLong(0)
    private val bindAddress = if (!exactBind && bindAddress is Inet4Address) {
        InetAddress.getByName("0.0.0.0")
    } else {
        bindAddress
    }
    private val socketLock = Object()
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    init {
        require(this.readKey.size == 32) { "iAP tunnel read key must be 32 bytes" }
        require(maxRejectedPeers in 1..MAX_REJECTED_PEERS) {
            "maxRejectedPeers must be in 1..$MAX_REJECTED_PEERS"
        }
    }

    fun listen(listener: Listener): Int {
        synchronized(socketLock) {
            check(servers.isEmpty()) { "AirPlay iAP tunnel is already listening" }
            check(!closed.get()) { "AirPlay iAP tunnel is closed" }
            this.listener = listener
        }
        val bound = bindAny()
        synchronized(socketLock) { servers += bound }
        debug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        val secondaryAddress = when {
            exactBind -> null
            bindAddress is java.net.Inet6Address && bindAddress.isAnyLocalAddress -> null
            bindAddress is java.net.Inet6Address -> InetAddress.getByName("0.0.0.0")
            else -> InetAddress.getByName("::")
        }
        secondaryAddress?.let { address ->
            val secondary = ServerSocket()
            runCatching {
                secondary.apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, bound.localPort))
                }
            }.onSuccess { secondary ->
                synchronized(socketLock) { servers += secondary }
                debug(
                    "AirPlay iAP tunnel secondary listener bound=" +
                        "${secondary.localSocketAddress}",
                )
            }.onFailure { error ->
                safeClose(secondary)
                debug(
                    "AirPlay iAP tunnel secondary listener failed address=" +
                        "$address port=${bound.localPort} cause=${safeFailure(error)}",
                )
            }
        }
        synchronized(socketLock) { servers.toList() }.forEach { server ->
            val thread = Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
            synchronized(socketLock) { threads += thread }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active: Socket?
        val boundServers: List<ServerSocket>
        val activeThreads: List<Thread>
        synchronized(socketLock) {
            active = socket
            socket = null
            boundServers = servers.toList()
            servers.clear()
            activeThreads = threads.toList()
            threads.clear()
        }
        safeClose(active)
        boundServers.forEach(::safeClose)
        activeThreads.filter { it !== Thread.currentThread() }.forEach(Thread::interrupt)
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        debug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            val remoteAddress =
                (accepted.remoteSocketAddress as? InetSocketAddress)?.address
            if (expectedPeerAddress != null && remoteAddress != expectedPeerAddress) {
                safeClose(accepted)
                val rejected = rejectedPeers.incrementAndGet()
                debug(
                    "AirPlay iAP tunnel rejected unexpected peer=" +
                        "${remoteAddress?.hostAddress?.take(MAX_ADDRESS_CHARS) ?: "unknown"}",
                )
                if (rejected >= maxRejectedPeers) {
                    val failure = IOException("AirPlay iAP tunnel peer-rejection limit reached")
                    listener.onClosed(failure)
                    close()
                    return
                }
                continue
            }
            accepted.setSoLinger(true, 0)
            val claimed = synchronized(socketLock) {
                if (socket == null && !closed.get()) {
                    socket = accepted
                    true
                } else {
                    false
                }
            }
            if (!claimed) {
                safeClose(accepted)
                debug("AirPlay iAP tunnel rejected an alternate active connection")
                continue
            }
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
        }
    }

    private fun run(sock: Socket) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                ciphertext += buffer.copyOf(read)
                val decrypted = decryptFrames(ciphertext)
                plaintext += decrypted.first
                ciphertext = decrypted.second
                plaintext = parsePackages(plaintext)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            synchronized(socketLock) {
                if (socket === sock) socket = null
            }
            safeClose(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= FRAME_HEADER_LEN) {
            val length = readU16Le(buffer, offset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            if (buffer.size - offset < frameLength) break
            val aad = buffer.copyOfRange(offset, offset + FRAME_HEADER_LEN)
            val sealed = buffer.copyOfRange(offset + FRAME_HEADER_LEN, offset + frameLength)
            val plain = AirPlayCrypto.chachaOpen(
                readKey, AirPlayCrypto.nonce64(readCounter.get()), sealed, aad,
            )
            readCounter.incrementAndGet()
            output.add(plain)
            offset += frameLength
        }
        return concatBytes(*output.toTypedArray()) to buffer.copyOfRange(offset, buffer.size)
    }

    private fun parsePackages(buffer: ByteArray): ByteArray {
        var offset = 0
        while (buffer.size - offset >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer, offset)
            if (size < PACKAGE_HEADER_LEN || size > MAX_PACKAGE) break
            if (buffer.size - offset < size) break
            val messageType = readU32Be(buffer, offset + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                listener.onDebug(
                    "AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}",
                )
                listener.onIap(buffer.copyOfRange(offset + PACKAGE_HEADER_LEN, offset + size))
            }
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun debug(message: String) {
        runCatching { listener.onDebug(message.take(MAX_LOG_CHARS).filterNot(Char::isISOControl)) }
    }

    private fun safeFailure(failure: Throwable): String =
        failure.javaClass.simpleName.take(64).ifEmpty { "Throwable" }

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
        const val DEFAULT_MAX_REJECTED_PEERS = 8
        const val MAX_REJECTED_PEERS = 64
        const val MAX_ADDRESS_CHARS = 128
        const val MAX_LOG_CHARS = 512
    }
}
