package com.shilapi.xcertplay.airplay.video

import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Transport selection is explicit: a Web-PKI HTTPS source cannot satisfy Pair-TLS-PSK. */
internal enum class MatchPointHttpTransport { HTTP, WEB_PKI_HTTPS, PAIR_TLS_PSK }

/**
 * One exact, numeric, session peer. DNS names, system proxies, alternate ports and redirects are
 * deliberately not authority. Relative HLS segment/key URLs are resolved by Media3 before open.
 */
internal class MatchPointPeerUrlPolicy private constructor(
    val transport: MatchPointHttpTransport,
    private val peerAddress: InetAddress,
    private val port: Int,
    private val pinnedHost: String? = null,
    private val rawPathPrefix: String = "/",
) {
    fun requireAllowed(uri: URI) {
        val expectedScheme = if (transport == MatchPointHttpTransport.HTTP) "http" else "https"
        val actualAddress = numericAddress(uri.host)
        val actualPort = if (uri.port >= 0) uri.port else if (expectedScheme == "https") 443 else 80
        val hostMatches = if (pinnedHost != null) {
            uri.host?.equals(pinnedHost, ignoreCase = true) == true
        } else actualAddress != null && actualAddress.address.contentEquals(peerAddress.address)
        if (!uri.isAbsolute || !uri.scheme.equals(expectedScheme, ignoreCase = true) ||
            actualPort != port || uri.rawUserInfo != null || uri.rawFragment != null ||
            !hostMatches || !pathAllowed(uri)
        ) {
            throw IOException("MatchPoint URL is outside the exact session peer route")
        }
    }

    private fun pathAllowed(uri: URI): Boolean {
        val path = uri.rawPath ?: return false
        val lowercase = path.lowercase()
        if (!path.startsWith('/') || '\\' in path || "%2e" in lowercase ||
            "%2f" in lowercase || "%5c" in lowercase ||
            path.split('/').any { it == "." || it == ".." } ||
            uri.normalize().rawPath != path
        ) return false
        return if (rawPathPrefix.endsWith('/')) path.startsWith(rawPathPrefix)
        else path == rawPathPrefix || path.startsWith("$rawPathPrefix/")
    }

    /** The transport must connect to this socket address, never resolve the URL host via DNS. */
    fun socketAddress(): InetSocketAddress = InetSocketAddress(peerAddress, port)

    companion object {
        fun forNumericPeer(
            transport: MatchPointHttpTransport,
            peerLiteral: String,
            port: Int,
            rawPathPrefix: String = if (transport == MatchPointHttpTransport.PAIR_TLS_PSK) "/1/" else "/",
        ): MatchPointPeerUrlPolicy {
            require(port in 1..65535) { "MatchPoint peer port must be explicit" }
            requireValidPathPrefix(rawPathPrefix)
            val address = numericAddress(peerLiteral)
                ?: throw IllegalArgumentException("MatchPoint peer must be a numeric IP literal")
            return MatchPointPeerUrlPolicy(transport, address, port,
                rawPathPrefix = rawPathPrefix)
        }

        /** A named Pair-TLS-PSK URL host can be accepted only with an out-of-band numeric peer pin. */
        fun forPinnedPairTlsHost(
            host: String,
            peerLiteral: String,
            port: Int = 443,
            rawPathPrefix: String = "/1/",
        ): MatchPointPeerUrlPolicy {
            require(host.isNotEmpty() && host.length <= 253 && host.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-'
            } && !host.startsWith('.') && !host.endsWith('.') && ".." !in host) {
                "MatchPoint pinned host must be a bounded ASCII DNS label sequence"
            }
            require(port in 1..65535)
            requireValidPathPrefix(rawPathPrefix)
            val address = numericAddress(peerLiteral)
                ?: throw IllegalArgumentException("MatchPoint peer must be a numeric IP literal")
            return MatchPointPeerUrlPolicy(MatchPointHttpTransport.PAIR_TLS_PSK, address, port,
                host, rawPathPrefix)
        }

        private fun requireValidPathPrefix(prefix: String) {
            require(prefix.startsWith('/') && prefix.length <= 512 && '\\' !in prefix &&
                '%' !in prefix && prefix.split('/').none { it == "." || it == ".." }) {
                "MatchPoint route prefix must be a canonical raw path"
            }
        }

        private fun numericAddress(host: String?): InetAddress? {
            if (host.isNullOrEmpty()) return null
            val plain = host.removePrefix("[").removeSuffix("]")
            if ('%' in plain) return null // No scoped IPv6 or DNS escape syntax.
            val ipv4 = plain.split('.')
            if (ipv4.size == 4 && ipv4.all { part ->
                    part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                        (part.length == 1 || part[0] != '0') &&
                        (part.toIntOrNull()?.let { it in 0..255 } == true)
                }
            ) {
                return InetAddress.getByAddress(ipv4.map(String::toInt).map(Int::toByte).toByteArray())
            }
            if (':' !in plain || !plain.all { it == ':' || it == '.' || it in '0'..'9' ||
                    it in 'a'..'f' || it in 'A'..'F' }) return null
            // A colon-bearing literal cannot be a DNS name; getByName only parses numeric IPv6.
            return try { InetAddress.getByName(plain) } catch (_: Exception) { null }
        }
    }
}

/**
 * A network factory owned by the exact MatchPoint generation. Implementations must reject each
 * redirect BEFORE following it and pin any socket to the policy's numeric peer. The adapter also
 * rejects a changed effective URI after open, but that check alone cannot undo a redirected hop.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal interface MatchPointSessionDataSourceFactory : DataSource.Factory, Closeable {
    val identity: MatchPointSessionIdentity
    val policy: MatchPointPeerUrlPolicy
    fun isCurrent(): Boolean
}

/** Only this dedicated, externally authenticated factory may fetch IsTLSEnabled=true content. */
internal interface MatchPointPairTlsPskDataSourceFactory : MatchPointSessionDataSourceFactory

internal fun requireUnredirectedMatchPointLoad(requested: URI, effective: URI?) {
    if (effective != requested) throw IOException("MatchPoint HLS source redirected")
}

/** Item-scoped `/1/<server-route>` bound; another server on the same peer is not its asset. */
internal fun matchPointPairTlsRouteRoot(uri: URI): String {
    val path = uri.rawPath ?: throw IOException("MatchPoint server route is absent")
    if (!path.startsWith("/1/")) throw IOException("MatchPoint server route is absent")
    val token = path.removePrefix("/1/").substringBefore('/')
    if (token.isEmpty() || token.length > 128 || !token.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
        }
    ) throw IOException("MatchPoint server route is not canonical")
    return "/1/$token"
}

internal fun requireMatchPointRouteRoot(uri: URI, root: String) {
    val path = uri.rawPath ?: throw IOException("MatchPoint URL path is absent")
    if (path != root && !path.startsWith("$root/")) {
        throw IOException("MatchPoint HLS request left its authorized item route")
    }
}

internal fun requireNarrowMatchPointRouteRoot(root: String) {
    if (root.length !in 2..512 || !root.startsWith('/') || root == "/" ||
        '%' in root || '\\' in root || root.split('/').any { it == "." || it == ".." }
    ) throw IOException("MatchPoint item route grant is not narrow and canonical")
}

/**
 * Ordinary HTTP/Web-PKI-HTTPS DataSource requiring the exact AirPlay Android Network and the
 * authenticated session's peer address bytes, supplied independently of any item URL. It uses no
 * proxy or DNS, disables redirects, and bounds headers, response bytes and elapsed read time.
 * No Network means no request. This is intentionally NOT a TLS-PSK provider.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MatchPointDirectPeerHttpDataSourceFactory(
    override val identity: MatchPointSessionIdentity,
    override val policy: MatchPointPeerUrlPolicy,
    private val sessionIsCurrent: () -> Boolean,
    verifiedSessionPeer: InetAddress?,
    private val network: Network? = null,
) : MatchPointSessionDataSourceFactory {
    private val closed = AtomicBoolean(false)
    private val sourcesLock = Any()
    private val sources = LinkedHashSet<DirectPeerDataSource>()

    init {
        require(policy.transport != MatchPointHttpTransport.PAIR_TLS_PSK)
        require(verifiedSessionPeer != null &&
            policy.socketAddress().address.address.contentEquals(verifiedSessionPeer.address)) {
            "MatchPoint URL peer must equal the verified AirPlay session peer"
        }
    }

    override fun isCurrent(): Boolean = !closed.get() && network != null && sessionIsCurrent()

    override fun createDataSource(): DataSource = DirectPeerDataSource(this)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = synchronized(sourcesLock) { sources.toList().also { sources.clear() } }
        active.forEach { try { it.revoke() } catch (_: IOException) { } }
    }

    private fun register(source: DirectPeerDataSource): Boolean = synchronized(sourcesLock) {
        if (closed.get()) false else sources.add(source)
    }

    private fun unregister(source: DirectPeerDataSource) = synchronized(sourcesLock) {
        sources.remove(source)
    }

    private class DirectPeerDataSource(
        private val owner: MatchPointDirectPeerHttpDataSourceFactory,
    ) : DataSource {
        private data class Response(
            val status: Int,
            val contentLength: Long,
            val contentRange: String?,
            val body: InputStream,
            val headers: Map<String, List<String>>,
        )

        private val lock = Any()
        private var revoked = false
        private var connection: HttpURLConnection? = null
        private var input: InputStream? = null
        private var uri: Uri? = null
        private var responseHeaders: Map<String, List<String>> = emptyMap()
        private var remaining = 0L
        private var openedAtNanos = 0L

        override fun open(dataSpec: DataSpec): Long {
            close()
            if (!owner.isCurrent() || dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET ||
                dataSpec.httpBody != null || dataSpec.httpRequestHeaders.isNotEmpty()
            ) throw IOException("MatchPoint network request is not permitted")
            val target = try { URI(dataSpec.uri.toString()) } catch (_: Exception) {
                throw IOException("MatchPoint URL is invalid")
            }
            owner.policy.requireAllowed(target)
            val exactNetwork = owner.network ?: throw IOException("MatchPoint session Network is missing")
            if (dataSpec.position < 0 || dataSpec.length > MAX_RESPONSE_BYTES) {
                throw IOException("MatchPoint byte range exceeds the response limit")
            }
            if (!owner.register(this)) throw IOException("MatchPoint session transport is closed")
            // Network.openConnection pins this socket to the exact AirPlay network; a numeric
            // host alone would still be ambiguous across overlapping VPN/private routes.
            val request = try {
                exactNetwork.openConnection(URL(target.toString()), Proxy.NO_PROXY) as HttpURLConnection
            } catch (failure: Throwable) {
                owner.unregister(this)
                throw failure
            }
            try {
                request.instanceFollowRedirects = false
                request.connectTimeout = 2_000
                request.readTimeout = 2_000
                request.requestMethod = "GET"
                request.setRequestProperty("Accept-Encoding", "identity")
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else {
                        val last = dataSpec.position + dataSpec.length - 1
                        if (dataSpec.length <= 0 || last < dataSpec.position) {
                            throw IOException("MatchPoint byte range is invalid")
                        }
                        last.toString()
                    }
                    request.setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
                if (!owner.isCurrent()) throw IOException("MatchPoint transport was revoked")
                synchronized(lock) {
                    if (revoked) throw IOException("MatchPoint transport was revoked")
                    connection = request // Publish before connecting so revoke can disconnect it.
                }
                val response = readHeadersWithDeadline(request)
                if (response.status != HttpURLConnection.HTTP_OK &&
                    response.status != HttpURLConnection.HTTP_PARTIAL
                ) {
                    throw IOException("MatchPoint HTTP response was not a permitted media range")
                }
                if (dataSpec.position > 0 && response.status != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("MatchPoint server ignored a byte range")
                }
                if (response.status == HttpURLConnection.HTTP_PARTIAL) {
                    val range = RANGE_PATTERN.matchEntire(response.contentRange ?: "")
                    val first = range?.groupValues?.get(1)?.toLongOrNull()
                    val last = range?.groupValues?.get(2)?.toLongOrNull()
                    if (first != dataSpec.position || last == null || last < first ||
                        last - first + 1 != response.contentLength
                    ) {
                        throw IOException("MatchPoint server returned a different byte range")
                    }
                }
                if (response.contentLength !in 0..MAX_RESPONSE_BYTES ||
                    (dataSpec.length != C.LENGTH_UNSET.toLong() &&
                        response.contentLength != dataSpec.length)
                ) throw IOException("MatchPoint response has an invalid bounded length")
                if (!owner.isCurrent()) throw IOException("MatchPoint transport was revoked")
                synchronized(lock) {
                    if (revoked) throw IOException("MatchPoint transport was revoked")
                    input = response.body
                    uri = dataSpec.uri
                    responseHeaders = response.headers
                    remaining = response.contentLength
                    openedAtNanos = System.nanoTime()
                }
                return response.contentLength
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        private fun readHeadersWithDeadline(request: HttpURLConnection): Response {
            val work = try {
                HEADERS_EXECUTOR.submit<Response> {
                    val status = request.responseCode
                    val contentLength = request.contentLengthLong
                    val range = request.getHeaderField("Content-Range")
                    val headers = request.headerFields.filterKeys { it != null }
                        .mapKeys { it.key!! }
                    val body = if (status == HttpURLConnection.HTTP_OK ||
                        status == HttpURLConnection.HTTP_PARTIAL) request.inputStream
                        else throw IOException("MatchPoint HTTP status is not media content")
                    if (synchronized(lock) { revoked } || !owner.isCurrent()) {
                        body.close()
                        throw IOException("MatchPoint HTTP source was revoked during headers")
                    }
                    Response(status, contentLength, range, body, headers)
                }
            } catch (_: RejectedExecutionException) {
                throw IOException("MatchPoint HTTP header worker capacity is exhausted")
            }
            return try { work.get(5_000, TimeUnit.MILLISECONDS) }
            catch (timeout: TimeoutException) {
                revoke()
                work.cancel(true)
                throw IOException("MatchPoint HTTP headers exceeded the absolute deadline", timeout)
            } catch (interrupted: InterruptedException) {
                revoke()
                work.cancel(true)
                Thread.currentThread().interrupt()
                throw IOException("MatchPoint HTTP header wait was interrupted", interrupted)
            } catch (failed: ExecutionException) {
                throw IOException("MatchPoint HTTP headers failed", failed.cause ?: failed)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException()
            }
            if (!owner.isCurrent()) throw IOException("MatchPoint transport was revoked")
            if (length == 0) return 0
            val stream: InputStream
            val amount: Int
            synchronized(lock) {
                if (revoked || isExpiredLocked()) throw IOException("MatchPoint response deadline expired")
                if (remaining == 0L) return C.RESULT_END_OF_INPUT
                stream = input ?: throw IOException("MatchPoint source is not open")
                amount = minOf(length.toLong(), remaining).toInt()
            }
            val count = stream.read(buffer, offset, amount)
            if (!owner.isCurrent()) throw IOException("MatchPoint transport was revoked during read")
            synchronized(lock) {
                if (revoked || isExpiredLocked()) {
                    throw IOException("MatchPoint response was revoked during read")
                }
                if (count <= 0) throw IOException("MatchPoint response ended before its bounded length")
                remaining -= count
            }
            return count
        }

        private fun isExpiredLocked(): Boolean =
            openedAtNanos == 0L || System.nanoTime() - openedAtNanos >
                TimeUnit.SECONDS.toNanos(MAX_RESPONSE_SECONDS)

        override fun getUri(): Uri? = synchronized(lock) { uri }

        override fun getResponseHeaders(): Map<String, List<String>> =
            synchronized(lock) { responseHeaders }

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun close() {
            val currentInput: InputStream?
            val currentConnection: HttpURLConnection?
            synchronized(lock) {
                currentInput = input
                input = null
                uri = null
                responseHeaders = emptyMap()
                remaining = 0L
                openedAtNanos = 0L
                currentConnection = connection
                connection = null
            }
            try { currentConnection?.disconnect() } finally {
                try { currentInput?.close() } finally { owner.unregister(this) }
            }
        }

        fun revoke() {
            synchronized(lock) { revoked = true }
            close()
        }

        private companion object {
            const val MAX_RESPONSE_BYTES = 64L * 1024L * 1024L
            const val MAX_RESPONSE_SECONDS = 60L
            val RANGE_PATTERN = Regex("bytes ([0-9]+)-([0-9]+)/(?:[0-9]+|\\*)")
            val HEADERS_EXECUTOR = ThreadPoolExecutor(
                0, 4, 60L, TimeUnit.SECONDS, SynchronousQueue(),
                { work -> Thread(work, "matchpoint-http-headers").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )
        }
    }
}

/** Separate external authorities; constructing the adapter with either absent remains DENY. */
internal interface MatchPointItemContentAuthority {
    val identity: MatchPointSessionIdentity
    fun isAuthorized(item: MatchPointQueueItem): Boolean
    /** Exact path root covered by the item's PIC/stream-key decision, never peer-wide `/`. */
    fun authorizedRawRouteRoot(item: MatchPointQueueItem): String?
    fun onRevoked(closePlayer: () -> Unit): Closeable
}

internal interface MatchPointParkPlaybackAuthority {
    val identity: MatchPointSessionIdentity
    fun isCurrent(): Boolean
    fun onRevoked(closePlayer: () -> Unit): Closeable
}

internal interface MatchPointVideoOutputRoute {
    val identity: MatchPointSessionIdentity
    fun isCurrent(): Boolean
    /** Synchronous, idempotent software cover; physical scanout proof remains external. */
    fun coverImmediately()
    fun onRevoked(closePlayer: () -> Unit): Closeable
}

/** The Surface remains owned by its route; ExoPlayer must detach before that owner may reuse it. */
internal interface MatchPointAndroidSurfaceRoute : MatchPointVideoOutputRoute {
    val surface: Surface
}

/** Accepting an action is not a report that HLS data, a decoded frame or scanout is ready. */
internal interface MatchPointLocalPlaybackEngine : Closeable {
    /** Called by the port once; checked again on the real player Looper before visible changes. */
    fun bindLiveAdmission(isCurrent: () -> Boolean, onLost: () -> Unit)
    /** Fence queued commands now; release is scheduled on the engine's own player thread. */
    fun abortPendingCommands()
    fun insert(index: Int, media: MatchPointLocalMedia)
    fun remove(index: Int)
    fun stop()
    fun seek(index: Int, positionMillis: Long)
    fun setRate(rate: Float)
}

internal data class MatchPointLocalMedia(
    val uri: URI,
    val dataSourceFactory: DataSource.Factory,
    val startPositionMillis: Long,
)

/**
 * Session-owned MatchPoint command to local-player adapter. No global player, feature toggle,
 * synthetic PIC success, streaming-key reply, or `videoPlaybackAllowed` is created here.
 *
 * The same exact-session content grant, independently certified park grant, output route and
 * transport remain necessary for every command that can present video and every HLS manifest,
 * segment or key open/read. A grant's revocation callback terminates the player immediately; its
 * output owner must independently black/cover the Surface while teardown is pending.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MatchPointAndroidPlaybackPort(
    private val identity: MatchPointSessionIdentity,
    private val engine: MatchPointLocalPlaybackEngine,
    private val route: MatchPointVideoOutputRoute,
    private val contentAuthority: MatchPointItemContentAuthority? = null,
    private val parkAuthority: MatchPointParkPlaybackAuthority? = null,
    transports: List<MatchPointSessionDataSourceFactory> = emptyList(),
    private val maximumItems: Int = 128,
) : MatchPointPlaybackPort {
    private data class BoundItem(
        val item: MatchPointQueueItem,
        val transport: MatchPointSessionDataSourceFactory,
        val routeRoot: String,
    )

    private val lock = Any()
    private val terminal = AtomicBoolean(false)
    private val items = ArrayList<MatchPointQueueItem>()
    @Volatile private var playerItems: List<BoundItem> = emptyList()
    private val transports = transports.toList()
    private val revocationSubscriptions = ArrayList<Closeable>()
    private val retirementScheduled = AtomicBoolean(false)
    private val coverState = AtomicInteger(0) // 0=not covered, 1=in progress, 2=covered
    @Volatile private var initComplete = false
    private var engineClosed = false
    private var resourcesClosed = false

    init {
        require(maximumItems in 1..512)
        require(route.identity === identity)
        require(contentAuthority == null || contentAuthority.identity === identity)
        require(parkAuthority == null || parkAuthority.identity === identity)
        require(this.transports.size <= 3 && this.transports.all { it.identity === identity })
        require(this.transports.map { it.policy.transport }.distinct().size == this.transports.size)
        require(this.transports.all {
            if (it.policy.transport == MatchPointHttpTransport.PAIR_TLS_PSK) {
                it is MatchPointPairTlsPskDataSourceFactory
            } else it is MatchPointDirectPeerHttpDataSourceFactory
        })
        val collected = ArrayList<Closeable>()
        val revoke = { revokeAsync() }
        try {
            engine.bindLiveAdmission(::liveAdmission, revoke)
            collected += route.onRevoked(revoke)
            contentAuthority?.let { collected += it.onRevoked(revoke) }
            parkAuthority?.let { collected += it.onRevoked(revoke) }
            synchronized(lock) {
                revocationSubscriptions += collected
                initComplete = true
            }
            // A subscription may invoke its callback synchronously. All registrations are now
            // published before retirement can close them; no callback can strand a late add.
            if (terminal.get()) retireAsync()
        } catch (failure: Throwable) {
            terminal.set(true)
            try { coverNow() } catch (error: Throwable) { failure.addSuppressed(error) }
            try { engine.abortPendingCommands() } catch (error: Throwable) { failure.addSuppressed(error) }
            (collected + transports).forEach { resource ->
                try { resource.close() } catch (error: Throwable) { failure.addSuppressed(error) }
            }
            try { engine.close() } catch (error: Throwable) { failure.addSuppressed(error) }
            throw failure
        }
    }

    override fun dispatch(action: MatchPointPlaybackAction) {
        var failed: Throwable? = null
        synchronized(lock) {
            if (terminal.get()) throw IOException("MatchPoint player generation is terminal")
            try {
                when (action) {
                    is MatchPointPlaybackAction.Insert -> insert(action)
                    is MatchPointPlaybackAction.Remove -> {
                        requireVideoAuthority() // Removing the current item can autoplay its successor.
                        val index = items.indexOfFirst { it.uuid == action.item.uuid }
                        if (index < 0) throw IOException("MatchPoint queue item is unknown")
                        engine.remove(index)
                        items.removeAt(index)
                        playerItems = playerItems.filterNot { it.item.uuid == action.item.uuid }
                    }
                    MatchPointPlaybackAction.Stop -> engine.stop()
                    is MatchPointPlaybackAction.Seek -> {
                        requireVideoAuthority()
                        if (action.forInterstitial == true) throw IOException("MatchPoint interstitial seek is unsupported")
                        val index = items.indexOfFirst { it.uuid == action.item.uuid }
                        if (index < 0) throw IOException("MatchPoint seek item is unknown")
                        val time = action.position as? MatchPointSeekPosition.Time
                            ?: throw IOException("MatchPoint date seek needs a verified clock anchor")
                        engine.seek(index, millis(time.time.value, time.time.timescale))
                    }
                    is MatchPointPlaybackAction.SetRate -> {
                        if (action.rate > 0.0) requireVideoAuthority()
                        if (action.rate > 0.0 && items.isEmpty()) {
                            throw IOException("MatchPoint cannot play an empty queue")
                        }
                        if (action.forInterstitial == true || action.networkClockTime != null ||
                            action.wallClockTimeDiff != null || action.rate !in 0.0..2.0
                        ) throw IOException("MatchPoint rate requires unsupported coordination")
                        engine.setRate(action.rate.toFloat())
                    }
                }
            } catch (failure: Throwable) {
                terminal.set(true)
                failed = failure
            }
        }
        failed?.let { failure ->
            // Cover synchronously, fence queued commands, then retire asynchronously. A timeout is NOT
            // release proof; the planner's close result remains retryable until engine.close ends.
            try { coverNow() } catch (error: Throwable) { failure.addSuppressed(error) }
            try { engine.abortPendingCommands() } catch (error: Throwable) { failure.addSuppressed(error) }
            retireAsync()
            throw failure
        }
    }

    private fun insert(action: MatchPointPlaybackAction.Insert) {
        requireVideoAuthority()
        val item = action.item
        if (items.size >= maximumItems || items.any { it.uuid == item.uuid } ||
            action.forInterstitial == true || contentAuthority?.isAuthorized(item) != true
        ) throw IOException("MatchPoint item lacks bounded playback authority")
        val location = item.contentLocation ?: item.offlineHlsContentLocation
            ?: throw IOException("MatchPoint item has no content location")
        val uri = try { URI(location) } catch (_: Exception) {
            throw IOException("MatchPoint item URL is invalid")
        }
        val mode = if (item.isTlsEnabled == true) MatchPointHttpTransport.PAIR_TLS_PSK
            else when (uri.scheme?.lowercase()) {
                "http" -> MatchPointHttpTransport.HTTP
                "https" -> MatchPointHttpTransport.WEB_PKI_HTTPS
                else -> throw IOException("MatchPoint URL scheme is unsupported")
            }
        val transport = transports.singleOrNull { it.policy.transport == mode }
            ?: throw IOException("MatchPoint session transport is unavailable")
        if (transport.identity !== identity || !transport.isCurrent()) {
            throw IOException("MatchPoint transport generation is stale")
        }
        transport.policy.requireAllowed(uri)
        val contentRoot = contentAuthority?.authorizedRawRouteRoot(item)
        val routeRoot = if (mode == MatchPointHttpTransport.PAIR_TLS_PSK) {
            matchPointPairTlsRouteRoot(uri).also { derived ->
                if (contentRoot != null && contentRoot != derived) {
                    throw IOException("MatchPoint PIC grant names a different server route")
                }
            }
        } else contentRoot ?: throw IOException("MatchPoint item route grant is absent")
        requireNarrowMatchPointRouteRoot(routeRoot)
        requireMatchPointRouteRoot(uri, routeRoot)
        val after = action.itemAfter?.uuid
        val insertIndex = if (after == null) items.size else items.indexOfFirst { it.uuid == after } + 1
        if (insertIndex < 0 || (after != null && insertIndex == 0)) {
            throw IOException("MatchPoint insertion anchor is unknown")
        }
        val position = item.startPosition?.let(::secondsToMillis) ?: 0L
        // The current Media3 playlist has no per-future-item start command. Do not claim one.
        if (position != 0L && insertIndex != 0) {
            throw IOException("MatchPoint future-item start position is unsupported")
        }
        val guarded = guardedFactory(transport, item, routeRoot)
        playerItems = playerItems.toMutableList().also {
            it.add(insertIndex, BoundItem(item, transport, routeRoot))
        }
        engine.insert(insertIndex, MatchPointLocalMedia(uri, guarded, position))
        items.add(insertIndex, item)
    }

    private fun requireVideoAuthority() {
        if (terminal.get() || !route.isCurrent() || parkAuthority?.isCurrent() != true) {
            throw IOException("MatchPoint video output or park grant is unavailable")
        }
        val content = contentAuthority ?: throw IOException("MatchPoint PIC grant is unavailable")
        val snapshot = playerItems
        if (snapshot.size != items.size || snapshot.any { !bindingCurrent(it, content) }) {
            throw IOException("MatchPoint queued content or transport grant was revoked")
        }
    }

    private fun liveAdmission(): Boolean {
        if (terminal.get() || !route.isCurrent() || parkAuthority?.isCurrent() != true) return false
        val content = contentAuthority ?: return false
        val snapshot = playerItems
        return snapshot.isNotEmpty() && snapshot.all { bindingCurrent(it, content) }
    }

    private fun bindingCurrent(
        binding: BoundItem,
        content: MatchPointItemContentAuthority,
    ): Boolean {
        val currentRoot = content.authorizedRawRouteRoot(binding.item)
        return content.isAuthorized(binding.item) &&
            binding.transport.identity === identity && binding.transport.isCurrent() &&
            (binding.transport.policy.transport == MatchPointHttpTransport.PAIR_TLS_PSK ||
                currentRoot == binding.routeRoot) &&
            (currentRoot == null || currentRoot == binding.routeRoot)
    }

    private fun guardedFactory(
        transport: MatchPointSessionDataSourceFactory,
        item: MatchPointQueueItem,
        routeRoot: String,
    ): DataSource.Factory = DataSource.Factory {
        val delegate = transport.createDataSource()
        object : DataSource {
            override fun open(dataSpec: DataSpec): Long {
                checkLive()
                val requested = try { URI(dataSpec.uri.toString()) } catch (_: Exception) {
                    throw IOException("MatchPoint HLS request URL is invalid")
                }
                transport.policy.requireAllowed(requested)
                requireMatchPointRouteRoot(requested, routeRoot)
                val length = delegate.open(dataSpec)
                try {
                    val effective = delegate.uri?.let { URI(it.toString()) }
                    requireUnredirectedMatchPointLoad(requested, effective)
                    requireMatchPointRouteRoot(effective!!, routeRoot)
                    checkLive()
                } catch (failure: Throwable) {
                    try { delegate.close() } catch (_: Exception) {}
                    throw failure
                }
                return length
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkLive()
                checkEffectiveRoute()
                val count = delegate.read(buffer, offset, length)
                try { checkLive(); checkEffectiveRoute() } catch (failure: Throwable) {
                    try { delegate.close() } catch (_: Exception) {}
                    throw failure
                }
                return count
            }

            private fun checkEffectiveRoute() {
                val effective = delegate.uri?.let { URI(it.toString()) }
                    ?: throw IOException("MatchPoint HLS source URI disappeared")
                transport.policy.requireAllowed(effective)
                requireMatchPointRouteRoot(effective, routeRoot)
            }

            private fun checkLive() {
                val content = contentAuthority ?: throw IOException("MatchPoint PIC grant is unavailable")
                val currentRoot = content.authorizedRawRouteRoot(item)
                if (terminal.get() || transport.identity !== identity || !transport.isCurrent() ||
                    !route.isCurrent() || parkAuthority?.isCurrent() != true ||
                    !content.isAuthorized(item) ||
                    (transport.policy.transport != MatchPointHttpTransport.PAIR_TLS_PSK &&
                        currentRoot != routeRoot) ||
                    (currentRoot != null && currentRoot != routeRoot)
                ) throw IOException("MatchPoint HLS source authority was revoked")
            }

            override fun getUri(): Uri? = delegate.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
            override fun addTransferListener(transferListener: TransferListener) =
                delegate.addTransferListener(transferListener)
            override fun close() = delegate.close()
        }
    }

    private fun revokeAsync() {
        if (!terminal.compareAndSet(false, true)) return
        try { coverNow() } catch (_: Throwable) { }
        try { engine.abortPendingCommands() } catch (_: Throwable) { }
        if (initComplete) retireAsync()
    }

    private fun coverNow() {
        when (coverState.get()) {
            2 -> return
            1 -> throw IOException("MatchPoint output cover is not yet confirmed")
        }
        if (!coverState.compareAndSet(0, 1)) {
            if (coverState.get() == 2) return
            throw IOException("MatchPoint output cover is not yet confirmed")
        }
        try {
            route.coverImmediately()
            coverState.set(2)
        } catch (failure: Throwable) {
            coverState.set(0) // A later close must retry if covering failed.
            throw failure
        }
    }

    private fun retireAsync() {
        if (!retirementScheduled.compareAndSet(false, true)) return
        Thread({ try { close() } catch (_: Exception) { /* exact owner must retry close */ } },
            "matchpoint-player-revoke-g${identity.generation}").apply { isDaemon = true; start() }
    }

    override fun close() {
        terminal.set(true)
        var preflightFailure: Throwable? = null
        try { coverNow() } catch (error: Throwable) { preflightFailure = error }
        try { engine.abortPendingCommands() } catch (error: Throwable) {
            if (preflightFailure == null) preflightFailure = error else preflightFailure!!.addSuppressed(error)
        }
        synchronized(lock) {
            var failure: Throwable? = preflightFailure
            if (!engineClosed) {
                try { engine.close(); engineClosed = true } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            if (!resourcesClosed) {
                var resourceFailed = false
                (revocationSubscriptions + transports).forEach { resource ->
                    try { resource.close() } catch (error: Throwable) {
                        resourceFailed = true
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
                resourcesClosed = !resourceFailed
            }
            if (engineClosed && resourcesClosed) {
                items.clear()
                playerItems = emptyList()
            }
            failure?.let { throw it }
        }
    }

    companion object {
        /** A real ExoPlayer+HLS instance, private to this exact session and Surface route. */
        fun createMedia3(
            context: Context,
            identity: MatchPointSessionIdentity,
            route: MatchPointAndroidSurfaceRoute,
            contentAuthority: MatchPointItemContentAuthority?,
            parkAuthority: MatchPointParkPlaybackAuthority?,
            transports: List<MatchPointSessionDataSourceFactory>,
        ): MatchPointAndroidPlaybackPort {
            require(route.identity === identity && route.isCurrent())
            require(contentAuthority?.identity === identity)
            require(parkAuthority?.identity === identity)
            require(transports.all { it.identity === identity })
            val player = try {
                Media3MatchPointEngine(context.applicationContext, route.surface, identity.generation)
            } catch (failure: Throwable) {
                try { route.coverImmediately() } catch (error: Throwable) { failure.addSuppressed(error) }
                throw failure
            }
            return try {
                MatchPointAndroidPlaybackPort(identity, player, route, contentAuthority,
                    parkAuthority, transports)
            } catch (failure: Throwable) {
                try { route.coverImmediately() } catch (error: Throwable) { failure.addSuppressed(error) }
                try { player.abortPendingCommands() } catch (error: Throwable) { failure.addSuppressed(error) }
                try { player.close() } catch (error: Throwable) { failure.addSuppressed(error) }
                throw failure
            }
        }

        private fun millis(value: Long, timescale: Int): Long {
            if (value > Long.MAX_VALUE / 1_000L) {
                throw IOException("MatchPoint position is outside player range")
            }
            return value * 1_000L / timescale
        }

        private fun secondsToMillis(seconds: Double): Long {
            if (!seconds.isFinite() || seconds < 0.0 || seconds > Long.MAX_VALUE / 1_000.0) {
                throw IOException("MatchPoint start position is outside player range")
            }
            return (seconds * 1_000.0).toLong()
        }
    }
}

/**
 * Testable command fence around a serialized owner-thread queue. A timed-out queued runnable
 * checks this fence when it eventually runs; an already-running operation must also check it
 * between individual player mutations. Cancellation does not assert physical player retirement.
 */
internal class MatchPointPlayerCommandQueue(
    private val post: (Runnable) -> Boolean,
    private val isOwnerThread: () -> Boolean,
    private val onTerminal: () -> Unit,
    private val timeoutMillis: Long = 3_000,
) {
    private val canceled = AtomicBoolean(false)

    init { require(timeoutMillis in 1..30_000) }

    fun cancel() { canceled.set(true) }

    fun isCanceled(): Boolean = canceled.get()

    fun requireOpen() {
        if (canceled.get()) throw IOException("MatchPoint player command generation is canceled")
    }

    fun call(block: () -> Unit) {
        requireOpen()
        if (isOwnerThread()) {
            try { requireOpen(); block(); requireOpen() } catch (failure: Throwable) {
                fail()
                throw failure
            }
            return
        }
        val complete = CountDownLatch(1)
        var failure: Throwable? = null
        val queued = Runnable {
            try { requireOpen(); block(); requireOpen() } catch (error: Throwable) {
                failure = error
            } finally { complete.countDown() }
        }
        if (!post(queued)) {
            fail()
            throw IOException("MatchPoint player Looper is unavailable")
        }
        val finished = try { complete.await(timeoutMillis, TimeUnit.MILLISECONDS) }
        catch (interrupted: InterruptedException) {
            fail()
            Thread.currentThread().interrupt()
            throw IOException("MatchPoint player command wait was interrupted", interrupted)
        }
        if (!finished) {
            fail()
            throw IOException("MatchPoint player operation exceeded its bounded deadline")
        }
        failure?.let { fail(); throw it }
        requireOpen()
    }

    private fun fail() {
        if (canceled.compareAndSet(false, true)) {
            try { onTerminal() } catch (_: Throwable) { /* caller still fails closed */ }
        }
    }
}

/** A release may report an earlier detach error after `release()` itself has succeeded. */
internal fun retireMatchPointOwnerLooper(
    release: () -> Unit,
    isReleased: () -> Boolean,
    quit: () -> Unit,
) {
    try { release() } finally { if (isReleased()) quit() }
}

/** An async decoder/HLS error may retire only its exact, still-active player instance. */
internal class MatchPointPlayerAsyncFailureGate(
    private val playerIdentity: Any,
    private val isTerminal: () -> Boolean,
    private val failClosed: () -> Unit,
) {
    private val delivered = AtomicBoolean(false)

    fun onError(sourceIdentity: Any) {
        if (sourceIdentity !== playerIdentity || isTerminal()) return
        if (delivered.compareAndSet(false, true)) failClosed()
    }
}

/** All ExoPlayer calls, including release, run on this session's dedicated player Looper. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class Media3MatchPointEngine(
    context: Context,
    surface: Surface,
    generation: Long,
) : MatchPointLocalPlaybackEngine {
    private class ReleaseAttempt {
        val done = CountDownLatch(1)
        @Volatile var failure: Throwable? = null
    }

    private val thread = HandlerThread("matchpoint-media3-g$generation").apply { start() }
    private val handler = Handler(thread.looper)
    private val releaseLock = Any()
    private var releaseAttempt: ReleaseAttempt? = null
    private val commands = MatchPointPlayerCommandQueue(
        post = { handler.post(it) },
        isOwnerThread = { Looper.myLooper() == handler.looper },
        onTerminal = { scheduleRelease() },
    )
    private var player: ExoPlayer? = null
    @Volatile private var released = false
    private val playerIdentity = Any()
    private val asyncFailures = MatchPointPlayerAsyncFailureGate(
        playerIdentity, { released || commands.isCanceled() }, { loseAdmission() },
    )
    private val admissionBound = AtomicBoolean(false)
    @Volatile private var liveAdmission: (() -> Boolean)? = null
    @Volatile private var admissionLost: (() -> Unit)? = null
    private var watchdogArmed = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (released || commands.isCanceled()) return
            val p = player ?: return
            if (p.mediaItemCount > 0 && !admissionCurrent()) {
                loseAdmission()
                return
            }
            handler.postDelayed(this, 50)
        }
    }

    init {
        try {
            commands.call {
                val built = ExoPlayer.Builder(context).build()
                player = built
                built.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (player === built) asyncFailures.onError(playerIdentity)
                    }
                })
                commands.requireOpen()
                built.setVideoSurface(surface)
                commands.requireOpen()
                built.volume = 0f // Product audio routing has no authorization here.
                built.playWhenReady = false
            }
        } catch (failure: Throwable) {
            abortPendingCommands()
            try { close() } catch (error: Throwable) { failure.addSuppressed(error) }
            throw failure
        }
    }

    override fun abortPendingCommands() {
        commands.cancel()
        scheduleRelease()
    }

    override fun bindLiveAdmission(isCurrent: () -> Boolean, onLost: () -> Unit) {
        if (!admissionBound.compareAndSet(false, true)) {
            throw IOException("MatchPoint player admission was already bound")
        }
        liveAdmission = isCurrent
        admissionLost = onLost
    }

    override fun insert(index: Int, media: MatchPointLocalMedia) = commands.call {
        val p = requirePlayer()
        val item = MediaItem.Builder().setUri(media.uri.toString())
            .setMimeType(MimeTypes.APPLICATION_M3U8).build()
        commands.requireOpen()
        requireLiveAdmission()
        p.addMediaSource(index, HlsMediaSource.Factory(media.dataSourceFactory).createMediaSource(item))
        commands.requireOpen()
        if (index == 0 && media.startPositionMillis > 0) p.seekTo(0, media.startPositionMillis)
        commands.requireOpen()
        if (p.playbackState == ExoPlayer.STATE_IDLE) p.prepare()
        if (!watchdogArmed) { watchdogArmed = true; handler.postDelayed(watchdog, 50) }
    }

    override fun remove(index: Int) = commands.call {
        commands.requireOpen(); requireLiveAdmission(); requirePlayer().removeMediaItem(index)
    }
    override fun stop() = commands.call { commands.requireOpen(); requirePlayer().stop() }
    override fun seek(index: Int, positionMillis: Long) = commands.call {
        commands.requireOpen(); requireLiveAdmission(); requirePlayer().seekTo(index, positionMillis)
    }
    override fun setRate(rate: Float) = commands.call {
        val p = requirePlayer()
        commands.requireOpen()
        if (rate == 0f) p.pause() else {
            requireLiveAdmission()
            p.playbackParameters = PlaybackParameters(rate)
            commands.requireOpen()
            requireLiveAdmission()
            if (p.playbackState == ExoPlayer.STATE_IDLE) p.prepare()
            commands.requireOpen()
            requireLiveAdmission()
            p.playWhenReady = true
        }
    }

    override fun close() {
        commands.cancel()
        if (released) return
        if (Looper.myLooper() == handler.looper) {
            retireMatchPointOwnerLooper(::releaseOnPlayerThread, { released },
                { thread.quitSafely() })
            return
        }
        val attempt = scheduleRelease()
        if (!attempt.done.await(3_000, TimeUnit.MILLISECONDS)) {
            throw IOException("MatchPoint player release is still pending")
        }
        attempt.failure?.let { throw it }
        if (!released) throw IOException("MatchPoint player release was not confirmed")
    }

    private fun scheduleRelease(): ReleaseAttempt = synchronized(releaseLock) {
        val existing = releaseAttempt
        if (existing != null && (existing.done.count != 0L || released)) return existing
        val attempt = ReleaseAttempt()
        releaseAttempt = attempt
        if (!handler.post {
                try { releaseOnPlayerThread() } catch (failure: Throwable) {
                    attempt.failure = failure
                } finally {
                    attempt.done.countDown()
                    if (released) thread.quitSafely()
                }
            }
        ) {
            attempt.failure = IOException("MatchPoint player release Looper is unavailable")
            attempt.done.countDown()
        }
        attempt
    }

    private fun releaseOnPlayerThread() {
        if (released) return
        val p = player
        if (p == null) { released = true; return }
        var failure: Throwable? = null
        fun attempt(step: () -> Unit) {
            try { step() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        attempt { p.playWhenReady = false }
        attempt { p.clearVideoSurface() }
        attempt { p.stop() }
        attempt { p.clearMediaItems() }
        attempt { p.release(); player = null; released = true }
        failure?.let { throw it }
    }

    private fun requirePlayer(): ExoPlayer = player ?: throw IOException("MatchPoint player was released")

    private fun admissionCurrent(): Boolean = try { liveAdmission?.invoke() == true }
    catch (_: Throwable) { false }

    private fun requireLiveAdmission() {
        if (!admissionCurrent()) {
            loseAdmission()
            throw IOException("MatchPoint player output authority was revoked")
        }
    }

    private fun loseAdmission() {
        try { admissionLost?.invoke() } catch (_: Throwable) { }
        abortPendingCommands()
    }
}
