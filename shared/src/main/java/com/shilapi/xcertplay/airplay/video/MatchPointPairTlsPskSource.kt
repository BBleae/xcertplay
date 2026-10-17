package com.shilapi.xcertplay.airplay.video

import android.net.Network
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.shilapi.xcertplay.airplay.AirPlayPairTlsPskLease
import com.shilapi.xcertplay.airplay.AirPlaySession
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsPSKIdentity
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

/**
 * The socket authority for one MatchPoint generation. Product callers must supply an authority
 * issued from the current AirPlay [Network], not a process-default Socket or DNS resolver. The
 * interface permits an explicit loopback fake in tests; it is not a fallback transport.
 */
internal interface MatchPointSessionNetworkSocketAuthority {
    val identity: MatchPointSessionIdentity
    fun isCurrent(): Boolean
    fun createSocket(): Socket
}

/** Android sockets created by this adapter are bound to the supplied exact Network. */
internal class MatchPointAndroidNetworkSocketAuthority(
    override val identity: MatchPointSessionIdentity,
    private val network: Network,
    private val networkIsCurrent: (Network, MatchPointSessionIdentity) -> Boolean,
) : MatchPointSessionNetworkSocketAuthority {
    override fun isCurrent(): Boolean =
        try { networkIsCurrent(network, identity) } catch (_: Exception) { false }

    override fun createSocket(): Socket {
        if (!isCurrent()) throw IOException("MatchPoint AirPlay network was revoked")
        val socket = network.socketFactory.createSocket()
        if (!isCurrent()) {
            socket.close()
            throw IOException("MatchPoint AirPlay network changed while creating a socket")
        }
        return socket
    }
}

/** Validates ownership on both sides of explicit, network-bound socket creation. */
internal object MatchPointPskBoundSocketOpening {
    fun create(
        authority: MatchPointSessionNetworkSocketAuthority,
        identity: MatchPointSessionIdentity,
        stillCurrent: () -> Boolean,
    ): Socket {
        if (authority.identity !== identity || !stillCurrent()) {
            throw IOException("MatchPoint AirPlay network authority is not current")
        }
        val socket = authority.createSocket()
        try {
            if (socket.isClosed || socket.isConnected || authority.identity !== identity ||
                !stillCurrent()
            ) throw IOException("MatchPoint AirPlay network changed during socket creation")
            return socket
        } catch (failure: Throwable) {
            try { socket.close() } catch (_: IOException) { }
            throw failure
        }
    }
}

/**
 * A deliberately unwired MatchPoint media source. Construction requires the exact authenticated
 * AirPlay session's Pair Verify key lease and an explicitly supplied TLS PSK identity. The iOS
 * server's NULL identity hint does not establish what bytes its client sends; no identity is
 * guessed here, including the zero-length identity. A caller may explicitly pass an empty array
 * only after independently accepting that unverified interop hypothesis.
 *
 * The session owns this factory through [AirPlayPairTlsPskLease.openOwnedTransportFor]. Closing
 * either the session or the factory closes all sockets and erases its sole retained key copy.
 * No system TLS, Web-PKI, proxy, DNS, redirect, or cleartext fallback is possible in this source.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MatchPointPairTlsPskSource private constructor(
    private val lease: AirPlayPairTlsPskLease,
    private val session: AirPlaySession,
    override val identity: MatchPointSessionIdentity,
    override val policy: MatchPointPeerUrlPolicy,
    private val socketAuthority: MatchPointSessionNetworkSocketAuthority,
    private val routeIsAuthorized: (MatchPointSessionIdentity, URI) -> Boolean,
    private val sessionIsCurrent: () -> Boolean,
    private val responseDeadlineMillis: Long,
    borrowedPsk: ByteArray,
    suppliedIdentity: ByteArray,
) : MatchPointPairTlsPskDataSourceFactory {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val key = borrowedPsk.copyOf()
    private val clientIdentity = suppliedIdentity.copyOf()
    private val sources = LinkedHashSet<Source>()

    init {
        require(policy.transport == MatchPointHttpTransport.PAIR_TLS_PSK)
        require(socketAuthority.identity === identity) {
            "MatchPoint socket authority belongs to another session"
        }
        require(key.size == 16) { "Pair-TLS-PSK must be 16 bytes" }
        require(clientIdentity.size <= MAX_IDENTITY_BYTES) { "Pair-TLS-PSK identity is too long" }
        require(responseDeadlineMillis in 1_000..MAX_RESPONSE_DEADLINE_MILLIS) {
            "MatchPoint response deadline must be bounded"
        }
    }

    override fun isCurrent(): Boolean =
        !closed.get() && !lease.retired && socketAuthority.identity === identity &&
            peerIsSessionRemote() && try {
            sessionIsCurrent() && socketAuthority.isCurrent()
        } catch (_: Exception) { false }

    private fun peerIsSessionRemote(): Boolean {
        val remote = session.remoteAddress ?: return false
        val pinned = policy.socketAddress().address ?: return false
        return pinned.address.contentEquals(remote.address)
    }

    private fun routeAllowed(target: URI): Boolean = try {
        routeIsAuthorized(identity, target)
    } catch (_: Exception) { false }

    override fun createDataSource(): DataSource = Source(this)

    private fun register(source: Source): Boolean = synchronized(lock) {
        if (closed.get() || sources.size >= MAX_ACTIVE_SOURCES) false else sources.add(source)
    }

    private fun unregister(source: Source) = synchronized(lock) { sources.remove(source) }

    private fun keyForHandshake(): Pair<ByteArray, ByteArray> = synchronized(lock) {
        if (closed.get()) throw IOException("MatchPoint Pair-TLS-PSK owner is closed")
        key.copyOf() to clientIdentity.copyOf()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = synchronized(lock) {
            val snapshot = sources.toList()
            sources.clear()
            key.fill(0)
            clientIdentity.fill(0)
            snapshot
        }
        try {
            active.forEach(Source::revoke)
        } finally {
            // Session retirement may itself invoke close() on this exact owner. The lease's
            // retirement fence explicitly handles that reentrancy.
            lease.close()
        }
    }

    private class Source(private val owner: MatchPointPairTlsPskSource) : DataSource {
        private val lock = Any()
        private var revoked = false
        private var socket: Socket? = null
        private var protocol: TlsClientProtocol? = null
        private var input: InputStream? = null
        private var exposedUri: Uri? = null
        private var authorizedTarget: URI? = null
        private var responseHeaders: Map<String, List<String>> = emptyMap()
        private var remaining = 0L
        private var responseDeadline: MatchPointPskSocketDeadline? = null

        override fun open(dataSpec: DataSpec): Long {
            close()
            if (!owner.isCurrent() || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
                dataSpec.httpBody != null || dataSpec.httpRequestHeaders.isNotEmpty()
            ) throw IOException("MatchPoint Pair-TLS-PSK request is not permitted")
            val target = try { URI(dataSpec.uri.toString()) } catch (_: Exception) {
                throw IOException("MatchPoint Pair-TLS-PSK URL is invalid")
            }
            owner.policy.requireAllowed(target)
            val request = MatchPointPskHttpFraming.request(target, dataSpec.position, dataSpec.length)
            if (!owner.routeAllowed(target)) throw IOException("MatchPoint Pair-TLS-PSK route is not authorized")
            if (!owner.register(this)) throw IOException("MatchPoint Pair-TLS-PSK owner is closed")
            var openDeadline: MatchPointPskSocketDeadline? = null
            var wholeResponseDeadline: MatchPointPskSocketDeadline? = null
            try {
                if (!owner.isCurrent() || !owner.routeAllowed(target)) {
                    throw IOException("MatchPoint Pair-TLS-PSK route or session was revoked")
                }
                // The authority is issued from the current AirPlay Network; a default Socket
                // could silently choose cellular or another Wi-Fi network for the same IP.
                val connection = MatchPointPskBoundSocketOpening.create(
                    owner.socketAuthority, owner.identity, owner::isCurrent,
                )
                synchronized(lock) {
                    if (revoked) {
                        connection.close()
                        throw IOException("MatchPoint Pair-TLS-PSK source was revoked")
                    }
                    socket = connection
                }
                if (!owner.isCurrent()) throw IOException("MatchPoint AirPlay socket is not current")
                // A separate absolute limit continues through the body. A peer may not keep
                // one of the finite source slots busy indefinitely by sending one byte before
                // every per-read timeout.
                wholeResponseDeadline = MatchPointPskSocketDeadline(
                    connection, owner.responseDeadlineMillis,
                )
                synchronized(lock) {
                    if (revoked) {
                        wholeResponseDeadline.close()
                        throw IOException("MatchPoint Pair-TLS-PSK source was revoked")
                    }
                    responseDeadline = wholeResponseDeadline
                }
                // soTimeout bounds one read only. This independent monotonic watchdog closes
                // the socket after a total 10 s so a byte-at-a-time HTTP header cannot extend
                // opening forever, including while BC's handshake itself is blocking.
                openDeadline = MatchPointPskSocketDeadline(connection, OPEN_DEADLINE_MILLIS)
                connection.tcpNoDelay = true
                connection.soTimeout = SOCKET_TIMEOUT_MILLIS
                connection.connect(owner.policy.socketAddress(), SOCKET_TIMEOUT_MILLIS)
                if (!owner.isCurrent() || !owner.routeAllowed(target)) {
                    throw IOException("MatchPoint Pair-TLS-PSK route or session was revoked")
                }

                val (psk, pskIdentity) = owner.keyForHandshake()
                val tls = MatchPointPskTlsHandshake.connect(connection, pskIdentity, psk)
                synchronized(lock) {
                    if (revoked) throw IOException("MatchPoint Pair-TLS-PSK source was revoked")
                    protocol = tls
                }
                if (!owner.isCurrent() || !owner.routeAllowed(target)) {
                    throw IOException("MatchPoint Pair-TLS-PSK route or session was revoked")
                }
                tls.outputStream.write(request.bytes)
                tls.outputStream.flush()
                val response = MatchPointPskHttpFraming.response(
                    tls.inputStream, request.position, request.endInclusive, request.rangeSent,
                    dataSpec.length,
                )
                openDeadline.requireAlive()
                wholeResponseDeadline.requireAlive()
                if (!owner.isCurrent() || !owner.routeAllowed(target)) {
                    throw IOException("MatchPoint Pair-TLS-PSK route or session was revoked")
                }
                synchronized(lock) {
                    if (revoked) throw IOException("MatchPoint Pair-TLS-PSK source was revoked")
                    input = tls.inputStream
                    exposedUri = dataSpec.uri
                    authorizedTarget = target
                    responseHeaders = response.headers
                    remaining = response.bodyLength
                }
                if (response.bodyLength == 0L) {
                    synchronized(lock) {
                        if (responseDeadline === wholeResponseDeadline) responseDeadline = null
                    }
                    wholeResponseDeadline.close()
                }
                return response.bodyLength
            } catch (failure: Throwable) {
                val timedOut = openDeadline?.timedOut == true ||
                    wholeResponseDeadline?.timedOut == true
                close()
                if (timedOut) {
                    throw IOException("MatchPoint Pair-TLS-PSK response deadline expired", failure)
                }
                if (failure is IOException) throw failure
                throw IOException("MatchPoint Pair-TLS-PSK connection failed", failure)
            } finally {
                openDeadline?.close()
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException()
            }
            return try {
                readAuthorized(buffer, offset, length)
            } catch (failure: IOException) {
                val timedOut = synchronized(lock) { responseDeadline?.timedOut == true }
                close()
                if (timedOut) {
                    throw IOException("MatchPoint Pair-TLS-PSK response deadline expired", failure)
                }
                throw failure
            }
        }

        private fun readAuthorized(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!owner.isCurrent()) throw IOException("MatchPoint Pair-TLS-PSK session was revoked")
            if (length == 0) return 0
            val stream: InputStream
            val target: URI
            val deadline: MatchPointPskSocketDeadline
            val countLimit: Int
            synchronized(lock) {
                if (revoked) throw IOException("MatchPoint Pair-TLS-PSK source was revoked")
                stream = input ?: throw IOException("MatchPoint Pair-TLS-PSK source is not open")
                target = authorizedTarget ?: throw IOException("MatchPoint Pair-TLS-PSK route is not open")
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                deadline = responseDeadline
                    ?: throw IOException("MatchPoint Pair-TLS-PSK response deadline is absent")
                countLimit = minOf(length.toLong(), remaining).toInt()
            }
            deadline.requireAlive()
            if (!owner.routeAllowed(target)) throw IOException("MatchPoint Pair-TLS-PSK route was revoked")
            val count = stream.read(buffer, offset, countLimit)
            if (count < 0) throw EOFException("MatchPoint Pair-TLS-PSK media body ended early")
            if (count == 0) throw IOException("MatchPoint Pair-TLS-PSK media body made no progress")
            if (!owner.isCurrent() || !owner.routeAllowed(target)) {
                throw IOException("MatchPoint Pair-TLS-PSK route or session was revoked")
            }
            deadline.requireAlive()
            val completedDeadline = synchronized(lock) {
                if (revoked) throw IOException("MatchPoint Pair-TLS-PSK source was revoked")
                remaining -= count
                if (remaining == 0L && responseDeadline === deadline) {
                    responseDeadline = null
                    deadline
                } else null
            }
            completedDeadline?.close()
            return count
        }

        override fun getUri(): Uri? = synchronized(lock) { exposedUri }

        override fun getResponseHeaders(): Map<String, List<String>> =
            synchronized(lock) { responseHeaders }

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun close() {
            val oldSocket: Socket?
            val oldProtocol: TlsClientProtocol?
            val oldDeadline: MatchPointPskSocketDeadline?
            synchronized(lock) {
                oldSocket = socket
                socket = null
                oldProtocol = protocol
                protocol = null
                input = null
                exposedUri = null
                authorizedTarget = null
                responseHeaders = emptyMap()
                remaining = 0L
                oldDeadline = responseDeadline
                responseDeadline = null
            }
            // Socket first: closing it aborts an in-flight blocking TLS handshake/read. A TLS
            // close_notify is not required when the authenticated session itself was revoked.
            try { oldSocket?.close() } catch (_: IOException) { }
            oldDeadline?.close()
            try { oldProtocol?.close() } catch (_: IOException) { }
            owner.unregister(this)
        }

        fun revoke() {
            synchronized(lock) { revoked = true }
            close()
        }
    }

    companion object {
        private const val MAX_IDENTITY_BYTES = 256
        private const val MAX_ACTIVE_SOURCES = 16
        private const val SOCKET_TIMEOUT_MILLIS = 2_000
        private const val OPEN_DEADLINE_MILLIS = 10_000L
        private const val MAX_RESPONSE_DEADLINE_MILLIS = 120_000L

        /** Returns null if the supplied credential lease no longer belongs to [session]. */
        fun openOwned(
            lease: AirPlayPairTlsPskLease,
            session: AirPlaySession,
            identity: MatchPointSessionIdentity,
            policy: MatchPointPeerUrlPolicy,
            socketAuthority: MatchPointSessionNetworkSocketAuthority,
            pskIdentity: ByteArray,
            routeIsAuthorized: (MatchPointSessionIdentity, URI) -> Boolean,
            sessionIsCurrent: () -> Boolean,
            responseDeadlineMillis: Long,
        ): MatchPointPairTlsPskSource? {
            require(policy.transport == MatchPointHttpTransport.PAIR_TLS_PSK)
            require(socketAuthority.identity === identity)
            val remote = session.remoteAddress
            require(remote != null && policy.socketAddress().address?.address
                ?.contentEquals(remote.address) == true) {
                "MatchPoint Pair-TLS-PSK peer must be the exact AirPlay session remote"
            }
            require(pskIdentity.size <= MAX_IDENTITY_BYTES)
            require(responseDeadlineMillis in 1_000..MAX_RESPONSE_DEADLINE_MILLIS)
            return lease.openOwnedTransportFor(session) { borrowed ->
                MatchPointPairTlsPskSource(
                    lease, session, identity, policy, socketAuthority, routeIsAuthorized,
                    sessionIsCurrent, responseDeadlineMillis, borrowed, pskIdentity,
                )
            }
        }
    }
}

/** A monotonic socket deadline that aborts blocking TLS and HTTP reads by closing the FD. */
internal class MatchPointPskSocketDeadline(
    private val socket: Socket,
    timeoutMillis: Long,
) : Closeable {
    private val startedNanos = System.nanoTime()
    private val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    private val expired = AtomicBoolean(false)
    init { require(timeoutMillis in 1..120_000) }

    private val task = watchdog.schedule({
        expired.set(true)
        try { socket.close() } catch (_: IOException) { }
    }, timeoutMillis, TimeUnit.MILLISECONDS)

    val timedOut: Boolean get() = expired.get() || System.nanoTime() - startedNanos >= timeoutNanos

    fun requireAlive() {
        if (timedOut) {
            try { socket.close() } catch (_: IOException) { }
            throw IOException("MatchPoint Pair-TLS-PSK socket deadline expired")
        }
    }

    override fun close() { task.cancel(false) }

    private companion object {
        val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "matchpoint-psk-socket-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** The caller transfers ownership of [identityBytes] and [psk] here; both are wiped. */
internal object MatchPointPskTlsHandshake {
    fun connect(socket: Socket, identityBytes: ByteArray, psk: ByteArray): TlsClientProtocol {
        val tlsIdentity = EphemeralPskIdentity(identityBytes, psk)
        try {
            require(psk.size == 16)
            require(identityBytes.size <= 256)
            val tls = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            tls.connect(object : PSKTlsClient(BcTlsCrypto(SecureRandom()), tlsIdentity) {
                override fun getSupportedVersions(): Array<ProtocolVersion> =
                    arrayOf(ProtocolVersion.TLSv12)

                override fun getSupportedCipherSuites(): IntArray = intArrayOf(
                    CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256,
                )
            })
            return tls
        } finally {
            // BC 1.79 TlsPSKKeyExchange clears the returned PSK after deriving the premaster
            // secret, but can retain it on a failed handshake. Track and erase every array
            // returned to BC as well as the caller-owned arrays on both paths.
            tlsIdentity.clear()
        }
    }

    private class EphemeralPskIdentity(
        private val identity: ByteArray,
        private val psk: ByteArray,
    ) : TlsPSKIdentity {
        private val issued = ArrayList<ByteArray>(2)

        override fun skipIdentityHint() = Unit

        override fun notifyIdentityHint(pskIdentityHint: ByteArray?) {
            // The observed iOS server supplies a NULL hint. If a different server supplies
            // one, reject it rather than silently accepting a changed identity protocol.
            if (pskIdentityHint != null) throw IOException("Unexpected MatchPoint PSK identity hint")
        }

        override fun getPSKIdentity(): ByteArray = identity.copyOf().also(issued::add)

        override fun getPSK(): ByteArray = psk.copyOf().also(issued::add)

        fun clear() {
            issued.forEach { it.fill(0) }
            issued.clear()
            identity.fill(0)
            psk.fill(0)
        }
    }
}

/** Strict, bounded HTTP/1.1 framing over the already authenticated TLS stream. */
internal object MatchPointPskHttpFraming {
    private const val MAX_TARGET_BYTES = 4_096
    private const val MAX_LINE_BYTES = 4_096
    private const val MAX_HEADER_BYTES = 16_384
    private const val MAX_HEADER_LINES = 64
    private const val MAX_BODY_BYTES = 512L * 1024 * 1024
    private val contentRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
    private val statusLine = Regex("HTTP/1\\.[01] (200|206)(?: [\\x20-\\x7e]*)?")

    data class Request(
        val bytes: ByteArray,
        val position: Long,
        val endInclusive: Long?,
        val rangeSent: Boolean,
    )

    data class Response(val bodyLength: Long, val headers: Map<String, List<String>>)

    fun request(target: URI, position: Long, length: Long): Request {
        if (position < 0 || (length != C.LENGTH_UNSET.toLong() && length <= 0)) {
            throw IOException("MatchPoint HTTP byte range is invalid")
        }
        val path = target.rawPath ?: throw IOException("MatchPoint HTTP route is absent")
        val query = target.rawQuery?.let { "?$it" } ?: ""
        val pathAndQuery = path + query
        if (!path.startsWith("/1/") || path.length <= 3 || pathAndQuery.length > MAX_TARGET_BYTES ||
            pathAndQuery.any { it.code !in 0x21..0x7e || it == '\\' || it == '#' } ||
            path.split('/').any { it == "." || it == ".." } ||
            Regex("(?i)%2f|%5c|%2e").containsMatchIn(path)
        ) throw IOException("MatchPoint HTTP route is not permitted")
        val authority = target.rawAuthority ?: throw IOException("MatchPoint HTTP authority is absent")
        if (authority.length > 255 || authority.any { it.code !in 0x21..0x7e || it == '\\' }) {
            throw IOException("MatchPoint HTTP authority is invalid")
        }
        val rangeSent = position > 0 || length != C.LENGTH_UNSET.toLong()
        val end = if (length == C.LENGTH_UNSET.toLong()) null else try {
            Math.addExact(position, length - 1)
        } catch (_: ArithmeticException) { throw IOException("MatchPoint HTTP byte range overflowed") }
        val range = if (rangeSent) "Range: bytes=$position-${end ?: ""}\r\n" else ""
        val bytes = ("GET $pathAndQuery HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            "Accept-Encoding: identity\r\n" +
            "Connection: close\r\n" + range + "\r\n")
            .toByteArray(Charsets.US_ASCII)
        if (bytes.size > MAX_HEADER_BYTES) throw IOException("MatchPoint HTTP request is too large")
        return Request(bytes, position, end, rangeSent)
    }

    fun response(
        stream: InputStream,
        position: Long,
        requestedEndInclusive: Long?,
        rangeSent: Boolean,
        requestedLength: Long,
    ): Response {
        val budget = HeaderBudget()
        val status = readLine(stream, budget)
        val match = statusLine.matchEntire(status) ?: throw IOException("MatchPoint HTTP status is invalid")
        val code = match.groupValues[1].toInt()
        if (code == 206 && !rangeSent) throw IOException("MatchPoint HTTP sent an unsolicited range")
        if (code == 200 && position != 0L) throw IOException("MatchPoint HTTP ignored the byte range")
        val headers = linkedMapOf<String, MutableList<String>>()
        repeat(MAX_HEADER_LINES) {
            val line = readLine(stream, budget)
            if (line.isEmpty()) {
                val contentLengthValues = headers["content-length"].orEmpty()
                val rangeValues = headers["content-range"].orEmpty()
                if (contentLengthValues.size > 1 || rangeValues.size > 1 ||
                    headers.containsKey("transfer-encoding") ||
                    headers["content-encoding"]?.any { !it.equals("identity", true) } == true
                ) throw IOException("MatchPoint HTTP framing is ambiguous")
                val contentLength = contentLengthValues.singleOrNull()?.let(::parseUnsigned)
                val bodyLength = if (code == 206) {
                    val range = rangeValues.singleOrNull()?.let { contentRange.matchEntire(it) }
                        ?: throw IOException("MatchPoint HTTP content range is invalid")
                    val start = parseUnsigned(range.groupValues[1])
                    val end = parseUnsigned(range.groupValues[2])
                    if (start != position || end < start ||
                        (requestedEndInclusive != null && end > requestedEndInclusive)
                    ) throw IOException("MatchPoint HTTP content range is wrong")
                    val total = range.groupValues[3]
                    if (total != "*" && parseUnsigned(total) <= end) {
                        throw IOException("MatchPoint HTTP content range total is wrong")
                    }
                    val distance = end - start
                    if (distance >= MAX_BODY_BYTES) {
                        throw IOException("MatchPoint HTTP content range length is wrong")
                    }
                    val span = distance + 1
                    if (contentLength != null && contentLength != span) {
                        throw IOException("MatchPoint HTTP content range length is wrong")
                    }
                    span
                } else {
                    if (rangeValues.isNotEmpty()) throw IOException("MatchPoint HTTP range is unsolicited")
                    val known = contentLength ?: if (requestedLength != C.LENGTH_UNSET.toLong()) {
                        requestedLength
                    } else throw IOException("MatchPoint HTTP body length is unknown")
                    minOf(known, if (requestedLength == C.LENGTH_UNSET.toLong()) known else requestedLength)
                }
                if (bodyLength > MAX_BODY_BYTES || (contentLength ?: 0) > MAX_BODY_BYTES) {
                    throw IOException("MatchPoint HTTP media response is too large")
                }
                return Response(bodyLength, headers.mapValues { it.value.toList() })
            }
            if (line[0] == ' ' || line[0] == '\t') {
                throw IOException("MatchPoint HTTP folded header is not permitted")
            }
            val colon = line.indexOf(':')
            if (colon <= 0 || !line.substring(0, colon).all { it.isAsciiHeaderName() }) {
                throw IOException("MatchPoint HTTP header is invalid")
            }
            val name = line.substring(0, colon).lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { it.code !in 0x20..0x7e }) {
                throw IOException("MatchPoint HTTP header value is invalid")
            }
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }
        throw IOException("MatchPoint HTTP has too many headers")
    }

    private fun Char.isAsciiHeaderName(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this in "!#$%&'*+-.^_`|~"

    private fun parseUnsigned(raw: String): Long {
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) {
            throw IOException("MatchPoint HTTP integer is invalid")
        }
        return raw.toLongOrNull() ?: throw IOException("MatchPoint HTTP integer overflowed")
    }

    private class HeaderBudget(var consumed: Int = 0)

    private fun readLine(stream: InputStream, budget: HeaderBudget): String {
        val line = ByteArrayOutputStream(128)
        while (true) {
            val byte = stream.read()
            if (byte < 0) throw EOFException("MatchPoint HTTP headers ended early")
            budget.consumed++
            if (budget.consumed > MAX_HEADER_BYTES || line.size() > MAX_LINE_BYTES) {
                throw IOException("MatchPoint HTTP headers are too large")
            }
            if (byte == '\r'.code) {
                if (stream.read() != '\n'.code) throw IOException("MatchPoint HTTP line ending is invalid")
                budget.consumed++
                if (budget.consumed > MAX_HEADER_BYTES) {
                    throw IOException("MatchPoint HTTP headers are too large")
                }
                return line.toString(Charsets.US_ASCII.name())
            }
            if (byte !in 0x20..0x7e && byte != '\t'.code) {
                throw IOException("MatchPoint HTTP header contains a control byte")
            }
            line.write(byte)
        }
    }
}
