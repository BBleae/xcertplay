package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconHash
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32

internal data class Iap2TransferredArtwork(
    val fileTransferId: Int,
    val bytes: ByteArray,
)

/** A request-scoped identity. The owning control session must use a fresh, increasing epoch/nonce. */
internal class Iap2AppLinksIconRequestToken(
    val sessionIdentity: Any,
    val subscriptionEpoch: Long,
    val requestNonce: Long,
    val bundleIdentifier: String,
) {
    init {
        require(subscriptionEpoch > 0 && requestNonce > 0 && bundleIdentifier.isNotBlank())
    }

    @Volatile internal var revoked: Boolean = false

    override fun toString(): String = "Iap2AppLinksIconRequestToken(<redacted>)"
}

/** Memory-only, verified FileTransfer type 8 bytes; never routed to NowPlaying or MediaBridge. */
internal data class Iap2TransferredAppLinksIcon(
    val token: Iap2AppLinksIconRequestToken,
    val fileTransferId: Int,
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    internal val isCurrent: () -> Boolean,
)

/** Receives iAP2 session-12 transfers and acknowledges supported Now Playing artwork. */
internal class Iap2FileTransferReceiver private constructor(
    private val receiveFileTransfer: (Long) -> ByteArray?,
    private val sendFileTransfer: (ByteArray, Long) -> Unit,
    private val isSessionClosed: () -> Boolean,
    private val isArtworkCached: (Int) -> Boolean,
    private val onArtwork: (Iap2TransferredArtwork) -> Unit,
    private val onIcon: (Iap2TransferredAppLinksIcon) -> Unit,
    private val onLog: (String) -> Unit = {},
    sessionIdentity: Any,
) : AutoCloseable {
    constructor(
        session: Iap2Session,
        isArtworkCached: (Int) -> Boolean,
        onArtwork: (Iap2TransferredArtwork) -> Unit,
        onLog: (String) -> Unit = {},
        onIcon: (Iap2TransferredAppLinksIcon) -> Unit = {},
    ) : this(
        receiveFileTransfer = session::recvFileTransfer,
        sendFileTransfer = session::sendFileTransfer,
        isSessionClosed = { session.isClosed },
        isArtworkCached = isArtworkCached,
        onArtwork = onArtwork,
        onIcon = onIcon,
        onLog = onLog,
        sessionIdentity = session,
    )

    internal constructor(
        receiveFileTransfer: (Long) -> ByteArray?,
        sendFileTransfer: (ByteArray, Long) -> Unit,
        isSessionClosed: () -> Boolean,
        onLog: (String) -> Unit = {},
        sessionIdentity: Any = Any(),
        onIcon: (Iap2TransferredAppLinksIcon) -> Unit = {},
    ) : this(
        receiveFileTransfer = receiveFileTransfer,
        sendFileTransfer = sendFileTransfer,
        isSessionClosed = isSessionClosed,
        isArtworkCached = { false },
        onArtwork = {},
        onIcon = onIcon,
        onLog = onLog,
        sessionIdentity = sessionIdentity,
    )

    private val handler = Iap2FileTransferHandler(sessionIdentity = sessionIdentity)
    private val lifecycleLock = Any()
    private val closeAttemptLock = Any()
    /** Serializes START on session 12 with data/SUCCESS and delayed revocation FAIL. */
    private val fileTransferOrderLock = Any()

    @Volatile private var closed = false
    private var started = false
    @Volatile private var retired = false
    private var retirementCoordinator: Thread? = null
    private val worker = Thread(::run, "xcertplay-iap2-file-transfer").apply {
        isDaemon = true
    }

    fun start() {
        synchronized(lifecycleLock) {
            check(!closed) { "iAP2 file-transfer receiver is closed" }
            check(!started) { "iAP2 file-transfer receiver has already started" }
            started = true
            worker.start()
        }
    }

    /** Call before queuing AD03; this does not authorize any FileTransfer START. */
    fun prepareIconRequest(token: Iap2AppLinksIconRequestToken): Boolean =
        !closed && !isSessionClosed() && handler.prepareIconRequest(token)

    /** Only a verified status-1 AD04 can authorize its exact id/hash. */
    fun grantIconTransfer(
        token: Iap2AppLinksIconRequestToken,
        response: Iap2AppLinksIconResponse,
        isCurrent: () -> Boolean,
    ): Boolean {
        if (closed || isSessionClosed()) return false
        return synchronized(fileTransferOrderLock) {
            val decision = handler.grantIconTransfer(token, response, isCurrent)
            val startsTransfer = decision.response?.getOrNull(1)?.toInt() == DATAGRAM_START
            if (startsTransfer && (closed || isSessionClosed() || token.revoked)) {
                handler.revokeIconRequest(token)
                return@synchronized false
            }
            try {
                decision.response?.let(::send)
            } catch (failure: Throwable) {
                if (startsTransfer) handler.revokeIconRequest(token)
                throw failure
            }
            decision.accepted && !closed && !token.revoked
        }
    }

    /** Stop, uncertain Stop, request cancellation and teardown must call this before reuse. */
    fun revokeIconRequest(token: Iap2AppLinksIconRequestToken) {
        // Revoke local authorization immediately. A concurrent START may already be in its send;
        // queue FAIL after that send rather than emitting a contradictory FAIL -> START sequence.
        val failure = handler.revokeIconRequest(token) ?: return
        synchronized(fileTransferOrderLock) {
            if (!closed && !isSessionClosed()) send(failure)
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            closed = true
            handler.close()
            if (retired) return
            worker.interrupt()
        }
        if (Thread.currentThread() === worker) {
            scheduleDeferredRetirement()
            throw IOException(
                "iAP2 file-transfer retirement was deferred until its worker exits",
            )
        }
        synchronized(closeAttemptLock) closeAttempt@{
            if (synchronized(lifecycleLock) { retired }) return@closeAttempt
            try {
                if (worker.state != Thread.State.NEW) worker.join(CLOSE_JOIN_MILLIS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while retiring the iAP2 file-transfer worker", failure)
            }
            if (worker.isAlive) {
                throw IOException("iAP2 file-transfer worker did not stop before the deadline")
            }
            synchronized(lifecycleLock) { retired = true }
        }
    }

    private fun scheduleDeferredRetirement() {
        val coordinator = synchronized(lifecycleLock) {
            retirementCoordinator
                ?.takeUnless { it.state == Thread.State.TERMINATED }
                ?.let { return }
            Thread(
                {
                    var delayMillis = DEFERRED_RETRY_INITIAL_MILLIS
                    try {
                        while (true) {
                            try {
                                close()
                                return@Thread
                            } catch (_: Exception) {
                                // Ownership stays here; retry the same worker after a bounded wait.
                            }
                            try {
                                Thread.sleep(delayMillis)
                            } catch (_: InterruptedException) {
                                // Interruption accelerates the next retry.
                            }
                            delayMillis = (delayMillis * 2).coerceAtMost(
                                DEFERRED_RETRY_MAX_MILLIS,
                            )
                        }
                    } finally {
                        synchronized(lifecycleLock) {
                            if (retirementCoordinator === Thread.currentThread()) {
                                retirementCoordinator = null
                            }
                        }
                    }
                },
                "xcertplay-iap2-file-transfer-retirement",
            ).apply { isDaemon = true }.also { retirementCoordinator = it }
        }
        try {
            coordinator.start()
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                if (retirementCoordinator === coordinator) retirementCoordinator = null
            }
            throw failure
        }
    }

    private fun run() {
        try {
            while (!closed && !isSessionClosed()) {
                receiveFileTransfer(RECEIVE_POLL_MILLIS)?.let(::handle)
                val expired = synchronized(fileTransferOrderLock) {
                    handler.expire().also { ids ->
                        ids.forEach { id ->
                            send(byteArrayOf(id.toByte(), DATAGRAM_FAILURE.toByte()))
                        }
                    }
                }
                expired.forEach { transferId ->
                    onLog("iap2 file transfer id=$transferId timed out")
                }
            }
        } catch (failure: Throwable) {
            if (failure is Error) throw failure
            if (!closed && !isSessionClosed()) {
                onLog("iap2 file transfer stopped: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    private fun handle(datagram: ByteArray) {
        val outcome = synchronized(fileTransferOrderLock) {
            handler.handle(datagram, isArtworkCached).also { it.response?.let(::send) }
        }
        outcome.artwork?.let { artwork ->
            onArtwork(artwork)
            onLog(
                "iap2 artwork received id=${artwork.fileTransferId} bytes=${artwork.bytes.size}",
            )
        }
        outcome.icon?.let { icon ->
            if (!closed && !isSessionClosed() && !icon.token.revoked && icon.isCurrent()) {
                onIcon(icon)
                onLog("iap2 AppLinks icon received id=${icon.fileTransferId} bytes=${icon.bytes.size}")
            }
        }
    }

    private fun send(datagram: ByteArray) {
        sendFileTransfer(datagram, SEND_TIMEOUT_MILLIS)
    }

    private companion object {
        const val DATAGRAM_START = 0x01
        const val DATAGRAM_FAILURE = 0x06
        const val RECEIVE_POLL_MILLIS = 500L
        const val SEND_TIMEOUT_MILLIS = 5_000L
        const val CLOSE_JOIN_MILLIS = 1_000L
        const val DEFERRED_RETRY_INITIAL_MILLIS = 25L
        const val DEFERRED_RETRY_MAX_MILLIS = 1_000L
    }
}

internal data class Iap2FileTransferOutcome(
    val response: ByteArray? = null,
    val artwork: Iap2TransferredArtwork? = null,
    val icon: Iap2TransferredAppLinksIcon? = null,
)

internal data class Iap2AppLinksIconGrantDecision(
    val accepted: Boolean,
    val response: ByteArray? = null,
)

/** Stateful protocol core kept independent from threads and Media3. */
internal class Iap2FileTransferHandler(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sessionIdentity: Any = Any(),
    private val maxAggregateInFlightBytes: Long = MAX_AGGREGATE_IN_FLIGHT_BYTES,
) {
    init { require(maxAggregateInFlightBytes > 0) }
    private enum class Kind { ARTWORK, APP_LINKS_ICON }

    private data class IconRequest(
        val token: Iap2AppLinksIconRequestToken,
        val deadlineMillis: Long,
        var grant: IconGrant? = null,
    )

    private data class IconGrant(
        val transferId: Int,
        val hash: Iap2AppLinksIconHash,
        val isCurrent: () -> Boolean,
    )

    private data class PendingIconSetup(
        val transferId: Int,
        val expectedBytes: Int,
        val deadlineMillis: Long,
    )

    private data class Transfer(
        val kind: Kind,
        val expectedBytes: Int,
        val startedMillis: Long,
        val data: ByteArrayOutputStream,
        val iconRequest: IconRequest? = null,
        var receiving: Boolean = false,
    )

    private val transfers = HashMap<Int, Transfer>()
    private var iconRequest: IconRequest? = null
    private var pendingIconSetup: PendingIconSetup? = null
    private var lastSubscriptionEpoch = 0L
    private var lastRequestNonce = 0L
    private var completedIconToken: Iap2AppLinksIconRequestToken? = null
    private var closed = false

    @Synchronized
    fun prepareIconRequest(token: Iap2AppLinksIconRequestToken): Boolean {
        if (closed || token.sessionIdentity !== sessionIdentity || token.revoked || iconRequest != null ||
            token.subscriptionEpoch < lastSubscriptionEpoch ||
            (token.subscriptionEpoch == lastSubscriptionEpoch && token.requestNonce <= lastRequestNonce)
        ) return false
        val now = nowMillis()
        iconRequest = IconRequest(token, now + ICON_REQUEST_TIMEOUT_MILLIS)
        lastSubscriptionEpoch = token.subscriptionEpoch
        lastRequestNonce = token.requestNonce
        return true
    }

    @Synchronized
    fun grantIconTransfer(
        token: Iap2AppLinksIconRequestToken,
        response: Iap2AppLinksIconResponse,
        isCurrent: () -> Boolean,
    ): Iap2AppLinksIconGrantDecision {
        val request = iconRequest ?: return Iap2AppLinksIconGrantDecision(false)
        if (request.token !== token || token.sessionIdentity !== sessionIdentity) {
            return Iap2AppLinksIconGrantDecision(false)
        }
        if (closed || token.revoked || nowMillis() >= request.deadlineMillis ||
            !safeCurrent(isCurrent)
        ) {
            return Iap2AppLinksIconGrantDecision(false, retireIconRequest())
        }
        if (response.status != 1 || response.bundleIdentifier != token.bundleIdentifier ||
            response.fileTransferBufferId == null || response.fileTransferBufferId !in 0..255 ||
            response.iconHash == null ||
            request.grant != null
        ) {
            // A status-0 AD04 is never a grant. An invalid reply retires this one-shot request.
            return Iap2AppLinksIconGrantDecision(false, retireIconRequest())
        }
        val grant = IconGrant(response.fileTransferBufferId!!, response.iconHash, isCurrent)
        request.grant = grant
        val pending = pendingIconSetup ?: return Iap2AppLinksIconGrantDecision(true)
        pendingIconSetup = null
        if (nowMillis() >= pending.deadlineMillis || pending.transferId != grant.transferId) {
            return Iap2AppLinksIconGrantDecision(
                true,
                command(pending.transferId, DATAGRAM_FAILURE),
            )
        }
        startIconTransfer(request, pending.transferId, pending.expectedBytes)
        return Iap2AppLinksIconGrantDecision(true, command(pending.transferId, DATAGRAM_START))
    }

    /** Returns a best-effort FAIL for a pending/active type-8 transfer. */
    @Synchronized
    fun revokeIconRequest(token: Iap2AppLinksIconRequestToken): ByteArray? {
        if (token.sessionIdentity !== sessionIdentity) return null
        if (completedIconToken === token) {
            token.revoked = true
            completedIconToken = null
        }
        if (iconRequest?.token !== token) return null
        return retireIconRequest()
    }

    @Synchronized
    fun close() {
        closed = true
        iconRequest?.token?.revoked = true
        completedIconToken?.revoked = true
        iconRequest = null
        completedIconToken = null
        pendingIconSetup = null
        transfers.clear()
    }

    @Synchronized
    fun handle(
        datagram: ByteArray,
        isArtworkCached: (Int) -> Boolean = { false },
    ): Iap2FileTransferOutcome {
        if (closed || datagram.size < 2) return Iap2FileTransferOutcome()
        val transferId = datagram[0].toInt() and 0xff
        if (datagram.size > MAX_ARTWORK_BYTES + 2) return failure(transferId)
        if (transfers[transferId]?.kind == Kind.APP_LINKS_ICON &&
            datagram.size > MAX_ICON_BYTES + 2
        ) return failure(transferId)
        return when (datagram[1].toInt() and 0xff) {
            DATAGRAM_SETUP -> setup(transferId, datagram, isArtworkCached)
            DATAGRAM_FIRST_DATA -> first(transferId, datagram.copyOfRange(2, datagram.size))
            DATAGRAM_DATA -> append(transferId, datagram.copyOfRange(2, datagram.size), last = false)
            DATAGRAM_LAST_DATA -> append(transferId, datagram.copyOfRange(2, datagram.size), last = true)
            DATAGRAM_FIRST_AND_ONLY -> firstAndOnly(
                transferId,
                datagram.copyOfRange(2, datagram.size),
            )
            DATAGRAM_CANCEL -> {
                val transfer = transfers.remove(transferId)
                if (transfer?.kind == Kind.APP_LINKS_ICON || pendingIconSetup?.transferId == transferId) {
                    retireIconRequest()
                }
                Iap2FileTransferOutcome()
            }
            DATAGRAM_PAUSE -> Iap2FileTransferOutcome()
            else -> Iap2FileTransferOutcome()
        }
    }

    @Synchronized
    fun expire(): List<Int> {
        val now = nowMillis()
        val expired = transfers.filterValues { now - it.startedMillis >= TRANSFER_TIMEOUT_MILLIS }.keys
        var ids = expired.toList()
        expired.forEach { id ->
            if (transfers.remove(id)?.kind == Kind.APP_LINKS_ICON) retireIconRequest()
        }
        pendingIconSetup?.takeIf { now >= it.deadlineMillis }?.let { pending ->
            ids = ids + pending.transferId
            retireIconRequest()
        }
        if (iconRequest?.let { now >= it.deadlineMillis } == true) {
            retireIconRequest()?.let { response ->
                val id = response[0].toInt() and 0xff
                if (id !in ids) ids = ids + id
            }
        }
        return ids
    }

    private fun setup(
        transferId: Int,
        datagram: ByteArray,
        isArtworkCached: (Int) -> Boolean,
    ): Iap2FileTransferOutcome {
        if (transferId in transfers || pendingIconSetup?.transferId == transferId) {
            return failure(transferId)
        }
        if (datagram.size < SETUP_BYTES) return failure(transferId)
        val size = readU64(datagram, 2)
        val fileType = readU16(datagram, 10)
        if (fileType == FILE_TYPE_APP_LINKS_ICON) {
            return setupIcon(transferId, datagram.size, size)
        }
        if (fileType != FILE_TYPE_NOW_PLAYING_ARTWORK || size > MAX_ARTWORK_BYTES.toULong() ||
            transfers.size >= MAX_CONCURRENT_TRANSFERS
        ) return failure(transferId)
        if (isArtworkCached(transferId)) return response(transferId, DATAGRAM_SUCCESS)

        val expectedBytes = size.toInt()
        transfers[transferId] = Transfer(
            kind = Kind.ARTWORK,
            expectedBytes = expectedBytes,
            startedMillis = nowMillis(),
            data = ByteArrayOutputStream(expectedBytes.coerceAtMost(INITIAL_CAPACITY_BYTES)),
        )
        return response(transferId, DATAGRAM_START)
    }

    private fun setupIcon(transferId: Int, datagramBytes: Int, size: ULong): Iap2FileTransferOutcome {
        val request = iconRequest ?: return failure(transferId)
        if (datagramBytes != SETUP_BYTES || size == 0uL || size > MAX_ICON_BYTES.toULong() ||
            nowMillis() >= request.deadlineMillis || request.token.revoked ||
            transfers.size >= MAX_CONCURRENT_TRANSFERS
        ) return failure(transferId)
        val grant = request.grant
        if (grant == null) {
            if (pendingIconSetup != null) return failure(transferId)
            pendingIconSetup = PendingIconSetup(
                transferId,
                size.toInt(),
                minOf(request.deadlineMillis, nowMillis() + EARLY_SETUP_TIMEOUT_MILLIS),
            )
            return Iap2FileTransferOutcome() // No START or image allocation before AD04.
        }
        if (grant.transferId != transferId || !safeCurrent(grant.isCurrent)) {
            retireIconRequest()
            return failure(transferId)
        }
        startIconTransfer(request, transferId, size.toInt())
        return response(transferId, DATAGRAM_START)
    }

    private fun startIconTransfer(request: IconRequest, id: Int, size: Int) {
        transfers[id] = Transfer(
            kind = Kind.APP_LINKS_ICON,
            expectedBytes = size,
            startedMillis = nowMillis(),
            data = ByteArrayOutputStream(size.coerceAtMost(INITIAL_CAPACITY_BYTES)),
            iconRequest = request,
        )
    }

    private fun first(transferId: Int, bytes: ByteArray): Iap2FileTransferOutcome {
        if (pendingIconSetup?.transferId == transferId) return failure(transferId)
        val transfer = transfers[transferId] ?: return Iap2FileTransferOutcome()
        if (transfer.receiving) return failure(transferId)
        transfer.receiving = true
        return write(transferId, transfer, bytes, last = false)
    }

    private fun append(
        transferId: Int,
        bytes: ByteArray,
        last: Boolean,
    ): Iap2FileTransferOutcome {
        if (pendingIconSetup?.transferId == transferId) return failure(transferId)
        val transfer = transfers[transferId] ?: return Iap2FileTransferOutcome()
        if (!transfer.receiving) return failure(transferId)
        return write(transferId, transfer, bytes, last)
    }

    private fun firstAndOnly(transferId: Int, bytes: ByteArray): Iap2FileTransferOutcome {
        if (pendingIconSetup?.transferId == transferId) return failure(transferId)
        val transfer = transfers[transferId] ?: return Iap2FileTransferOutcome()
        if (transfer.receiving) return failure(transferId)
        return write(transferId, transfer, bytes, last = true)
    }

    private fun write(
        transferId: Int,
        transfer: Transfer,
        bytes: ByteArray,
        last: Boolean,
    ): Iap2FileTransferOutcome {
        val receivedBytes = transfer.data.size() + bytes.size
        val maxBytes = if (transfer.kind == Kind.APP_LINKS_ICON) MAX_ICON_BYTES else MAX_ARTWORK_BYTES
        if (receivedBytes > maxBytes ||
            transfers.values.sumOf { it.data.size().toLong() } + bytes.size > maxAggregateInFlightBytes ||
            transfer.expectedBytes > 0 && receivedBytes > transfer.expectedBytes
        ) {
            return failure(transferId)
        }
        transfer.data.write(bytes)
        if (!last) return Iap2FileTransferOutcome()
        if (transfer.expectedBytes > 0 && transfer.data.size() != transfer.expectedBytes) {
            return failure(transferId)
        }

        transfers.remove(transferId)
        if (transfer.kind == Kind.APP_LINKS_ICON) {
            val request = transfer.iconRequest ?: return failure(transferId)
            val grant = request.grant ?: return failure(transferId)
            val data = transfer.data.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(data).copyOf(16)
            val geometry = Iap2AppLinksIconImageInspector.inspect(data)
            if (iconRequest !== request || request.token.revoked ||
                nowMillis() >= request.deadlineMillis || !safeCurrent(grant.isCurrent) ||
                !MessageDigest.isEqual(digest, grant.hash.bytes()) || geometry == null
            ) {
                retireIconRequest()
                return failure(transferId)
            }
            iconRequest = null
            completedIconToken = request.token
            return Iap2FileTransferOutcome(
                response = command(transferId, DATAGRAM_SUCCESS),
                icon = Iap2TransferredAppLinksIcon(
                    request.token, transferId, data, geometry.mimeType,
                    geometry.width, geometry.height, grant.isCurrent,
                ),
            )
        }
        return Iap2FileTransferOutcome(
            response = command(transferId, DATAGRAM_SUCCESS),
            artwork = Iap2TransferredArtwork(transferId, transfer.data.toByteArray()),
        )
    }

    private fun failure(transferId: Int): Iap2FileTransferOutcome {
        val transfer = transfers.remove(transferId)
        if (transfer?.kind == Kind.APP_LINKS_ICON || pendingIconSetup?.transferId == transferId) {
            retireIconRequest()
        }
        return response(transferId, DATAGRAM_FAILURE)
    }

    private fun retireIconRequest(): ByteArray? {
        iconRequest?.token?.revoked = true
        iconRequest = null
        val id = pendingIconSetup?.transferId
            ?: transfers.entries.firstOrNull { it.value.kind == Kind.APP_LINKS_ICON }?.key
        pendingIconSetup = null
        transfers.entries.removeAll { it.value.kind == Kind.APP_LINKS_ICON }
        return id?.let { command(it, DATAGRAM_FAILURE) }
    }

    private fun safeCurrent(test: () -> Boolean): Boolean =
        try { test() } catch (_: RuntimeException) { false }

    private fun response(transferId: Int, command: Int): Iap2FileTransferOutcome =
        Iap2FileTransferOutcome(response = command(transferId, command))

    private fun command(transferId: Int, command: Int): ByteArray =
        byteArrayOf(transferId.toByte(), command.toByte())

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readU64(bytes: ByteArray, offset: Int): ULong {
        var value = 0uL
        repeat(8) { index ->
            value = (value shl 8) or (bytes[offset + index].toInt() and 0xff).toULong()
        }
        return value
    }

    private companion object {
        const val DATAGRAM_SETUP = 0x04
        const val DATAGRAM_START = 0x01
        const val DATAGRAM_FIRST_DATA = 0x80
        const val DATAGRAM_DATA = 0x00
        const val DATAGRAM_LAST_DATA = 0x40
        const val DATAGRAM_FIRST_AND_ONLY = 0xc0
        const val DATAGRAM_CANCEL = 0x02
        const val DATAGRAM_PAUSE = 0x03
        const val DATAGRAM_SUCCESS = 0x05
        const val DATAGRAM_FAILURE = 0x06
        const val FILE_TYPE_NOW_PLAYING_ARTWORK = 0x0002
        const val FILE_TYPE_APP_LINKS_ICON = 0x0008
        const val SETUP_BYTES = 12
        const val MAX_ARTWORK_BYTES = 16 * 1_048_576
        const val MAX_ICON_BYTES = 2 * 1_048_576
        const val MAX_AGGREGATE_IN_FLIGHT_BYTES = 20L * 1_048_576L
        const val MAX_CONCURRENT_TRANSFERS = 8
        const val INITIAL_CAPACITY_BYTES = 4 * 1_024
        const val TRANSFER_TIMEOUT_MILLIS = 30_000L
        const val ICON_REQUEST_TIMEOUT_MILLIS = 30_000L
        const val EARLY_SETUP_TIMEOUT_MILLIS = 1_000L
    }
}

/** Header-only allowlist. This is deliberately not a decoder or a guarantee that pixels are safe to render. */
internal object Iap2AppLinksIconImageInspector {
    data class Geometry(val mimeType: String, val width: Int, val height: Int)

    fun inspect(bytes: ByteArray): Geometry? =
        png(bytes) ?: jpeg(bytes) ?: webp(bytes)

    private fun png(bytes: ByteArray): Geometry? {
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 57 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
        var offset = 8
        var chunks = 0
        var geometry: Geometry? = null
        var sawImageData = false
        while (offset + 12 <= bytes.size && ++chunks <= MAX_PNG_CHUNKS) {
            val size = u32be(bytes, offset)
            if (size > bytes.size.toLong() - offset - 12L) return null
            val payloadSize = size.toInt()
            val crc = CRC32().apply { update(bytes, offset + 4, payloadSize + 4) }
            if (crc.value != u32be(bytes, offset + payloadSize + 8)) return null
            when {
                ascii(bytes, offset + 4, "IHDR") -> {
                    if (chunks != 1 || size != 13L) return null
                    geometry = checked("image/png", u32be(bytes, offset + 8), u32be(bytes, offset + 12))
                    if (geometry == null) return null
                }
                ascii(bytes, offset + 4, "IDAT") -> {
                    if (geometry == null || size == 0L) return null
                    sawImageData = true
                }
                ascii(bytes, offset + 4, "IEND") ->
                    return geometry.takeIf {
                        sawImageData && size == 0L && offset + 12 == bytes.size
                    }
                chunks == 1 -> return null
            }
            offset += payloadSize + 12
        }
        return null
    }

    private fun jpeg(bytes: ByteArray): Geometry? {
        if (bytes.size < 10 || u8(bytes, 0) != 0xff || u8(bytes, 1) != 0xd8 ||
            u8(bytes, bytes.size - 2) != 0xff || u8(bytes, bytes.size - 1) != 0xd9
        ) return null
        var position = 2
        while (position + 3 < bytes.size - 2) {
            if (u8(bytes, position++) != 0xff) return null
            while (position < bytes.size && u8(bytes, position) == 0xff) position++
            if (position >= bytes.size) return null
            val marker = u8(bytes, position++)
            if (marker == 0xda || marker == 0xd9) return null
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            if (position + 1 >= bytes.size) return null
            val length = u16be(bytes, position)
            if (length < 2 || position + length > bytes.size) return null
            if (marker in SOF_MARKERS) {
                if (length < 7) return null
                return checked(
                    "image/jpeg",
                    u16be(bytes, position + 5).toLong(),
                    u16be(bytes, position + 3).toLong(),
                )
            }
            position += length
        }
        return null
    }

    private fun webp(bytes: ByteArray): Geometry? {
        if (bytes.size < 30 || !ascii(bytes, 0, "RIFF") ||
            !ascii(bytes, 8, "WEBP") || u32le(bytes, 4) != bytes.size.toLong() - 8L
        ) return null
        val chunkSize = u32le(bytes, 16)
        if (chunkSize > bytes.size.toLong() - 20L) return null
        return when {
            ascii(bytes, 12, "VP8X") && chunkSize >= 10L -> {
                if ((u8(bytes, 20) and 0x02) != 0) return null // No animated icons.
                checked(
                    "image/webp",
                    1L + u8(bytes, 24) + (u8(bytes, 25) shl 8) + (u8(bytes, 26) shl 16),
                    1L + u8(bytes, 27) + (u8(bytes, 28) shl 8) + (u8(bytes, 29) shl 16),
                )
            }
            ascii(bytes, 12, "VP8L") && chunkSize >= 5L && u8(bytes, 20) == 0x2f ->
                checked(
                    "image/webp",
                    1L + (u8(bytes, 21) or ((u8(bytes, 22) and 0x3f) shl 8)),
                    1L + ((u8(bytes, 22) ushr 6) or (u8(bytes, 23) shl 2) or
                        ((u8(bytes, 24) and 0x0f) shl 10)),
                )
            ascii(bytes, 12, "VP8 ") && chunkSize >= 10L &&
                u8(bytes, 23) == 0x9d && u8(bytes, 24) == 0x01 && u8(bytes, 25) == 0x2a ->
                checked(
                    "image/webp",
                    (u16le(bytes, 26) and 0x3fff).toLong(),
                    (u16le(bytes, 28) and 0x3fff).toLong(),
                )
            else -> null
        }
    }

    private fun checked(mime: String, width: Long, height: Long): Geometry? =
        if (width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION &&
            width * height <= MAX_PIXELS
        ) Geometry(mime, width.toInt(), height.toInt()) else null

    private fun ascii(bytes: ByteArray, offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= bytes.size &&
            value.indices.all { bytes[offset + it].toInt() == value[it].code }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    private fun u32be(bytes: ByteArray, offset: Int): Long =
        (u8(bytes, offset).toLong() shl 24) or
            (u8(bytes, offset + 1).toLong() shl 16) or
            (u8(bytes, offset + 2).toLong() shl 8) or u8(bytes, offset + 3).toLong()

    private fun u32le(bytes: ByteArray, offset: Int): Long =
        u8(bytes, offset).toLong() or
            (u8(bytes, offset + 1).toLong() shl 8) or
            (u8(bytes, offset + 2).toLong() shl 16) or
            (u8(bytes, offset + 3).toLong() shl 24)

    private val SOF_MARKERS = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
    private const val MAX_PNG_CHUNKS = 128
    private const val MAX_DIMENSION = 2_048L
    private const val MAX_PIXELS = 4_194_304L
}
