package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Opaque identity for exactly one MatchPoint transport generation.
 *
 * Equality is intentionally referential. The random identity is never exposed or included in
 * diagnostics; [generation] is the only printable correlation value.
 */
class MatchPointSessionIdentity private constructor(
    val generation: Long,
    @Suppress("unused") private val nonce: UUID,
) {
    private val claimed = AtomicBoolean(false)

    init {
        require(generation > 0) { "MatchPoint session generation must be positive" }
    }

    override fun toString(): String =
        "MatchPointSessionIdentity(generation=$generation, identity=<redacted>)"

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    companion object {
        fun create(generation: Long): MatchPointSessionIdentity =
            MatchPointSessionIdentity(generation, UUID.randomUUID())
    }
}

/** Runtime-only bounds, independent of the binary-plist limits in [MatchPointLimits]. */
data class MatchPointRuntimeLimits(
    val maximumInboundQueueMessages: Int = 128,
    val maximumInboundQueueBytes: Int = 2 * 1024 * 1024,
    val maximumOutboundQueueMessages: Int = 128,
    val maximumOutboundQueueBytes: Int = 2 * 1024 * 1024,
    val maximumPendingInboundRequests: Int = 64,
    val maximumInboundRequestIds: Int = 4_096,
    val inboundRequestTimeoutMillis: Long = 15_000,
    val timeoutPollMillis: Long = 250,
    val terminalCallbackWaitMillis: Long = 1_000,
    val maximumDiagnostics: Int = 128,
) {
    init {
        require(maximumInboundQueueMessages in 1..MAXIMUM_QUEUE_MESSAGES)
        require(maximumInboundQueueBytes in 1..MAXIMUM_QUEUE_BYTES)
        require(maximumOutboundQueueMessages in 1..MAXIMUM_QUEUE_MESSAGES)
        require(maximumOutboundQueueBytes in 1..MAXIMUM_QUEUE_BYTES)
        require(maximumPendingInboundRequests in 1..MAXIMUM_PENDING_REQUESTS)
        require(maximumInboundRequestIds in maximumPendingInboundRequests..MAXIMUM_REQUEST_IDS)
        require(inboundRequestTimeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS)
        require(timeoutPollMillis in 1..MAXIMUM_TIMEOUT_MILLIS)
        require(terminalCallbackWaitMillis in 1..MAXIMUM_TIMEOUT_MILLIS)
        require(maximumDiagnostics in 1..MAXIMUM_DIAGNOSTICS)
    }

    private companion object {
        const val MAXIMUM_QUEUE_MESSAGES = 4_096
        const val MAXIMUM_QUEUE_BYTES = 16 * 1024 * 1024
        const val MAXIMUM_PENDING_REQUESTS = 4_096
        const val MAXIMUM_REQUEST_IDS = 65_536
        const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val MAXIMUM_DIAGNOSTICS = 4_096
    }
}

enum class MatchPointRuntimeState {
    NEW,
    OPEN,
    FAILED,
    CLOSED,
}

/** Logical runtime termination is not, by itself, proof that the RCS transport finished closing. */
enum class MatchPointTransportCloseState {
    NOT_REQUESTED,
    PENDING,
    COMPLETE,
    FAILED,
}

enum class MatchPointRuntimeCloseReason {
    LOCAL_CLOSE,
    TRANSPORT_CLOSED,
    TRANSPORT_FAILED,
    WRONG_STREAM,
    OPEN_TWICE,
    MESSAGE_BEFORE_OPEN,
    INVALID_CARRIER,
    UNKNOWN_MESSAGE,
    UNSUPPORTED_AUTHORIZATION,
    WRONG_DIRECTION,
    DUPLICATE_REQUEST,
    INBOUND_QUEUE_LIMIT,
    OUTBOUND_QUEUE_LIMIT,
    INBOUND_REQUEST_LIMIT,
    INBOUND_REQUEST_ID_LIMIT,
    INBOUND_REQUEST_TIMEOUT,
    BACKEND_FAILED,
    SEND_FAILED,
}

enum class MatchPointDiagnosticDirection {
    LIFECYCLE,
    INBOUND,
    OUTBOUND,
}

/** Payload-free diagnostic record. It never contains a URL, item ID, message ID, or session ID. */
data class MatchPointRuntimeDiagnostic(
    val sequence: Long,
    val code: String,
    val direction: MatchPointDiagnosticDirection,
    val kind: MatchPointMessageKind? = null,
    val knownType: String? = null,
    val byteCount: Int = 0,
)

/**
 * Bounded logical-runtime snapshot. [transportCloseState] only reports whether the requested
 * `RcsDataStream.close()` call returned; it is not decoder, player, socket-kernel, or GPU retirement
 * proof.
 */
data class MatchPointRuntimeSnapshot(
    val generation: Long,
    val state: MatchPointRuntimeState,
    val terminalReason: MatchPointRuntimeCloseReason?,
    val transportCloseState: MatchPointTransportCloseState,
    val queuedInboundMessages: Int,
    val queuedInboundBytes: Int,
    val queuedOutboundMessages: Int,
    val queuedOutboundBytes: Int,
    val pendingInboundRequests: Int,
    val observedInboundRequestIds: Int,
    val diagnostics: List<MatchPointRuntimeDiagnostic>,
    val droppedDiagnostics: Long,
    val terminalTasksStarted: Int,
)

class MatchPointRuntimeException(
    val reason: MatchPointRuntimeCloseReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Exact, single-use capability for replying to one peer request in this runtime generation. */
class MatchPointInboundRequest internal constructor(
    val identity: MatchPointSessionIdentity,
    val message: MatchPointMessage,
    internal val deadlineNanos: Long,
    internal val token: Any,
)

/**
 * Local-player seam only. Implementations receive validated typed values and decide what local
 * state to change. The runtime itself never opens a URL, creates a network player, derives a PSK,
 * or handles PIC authorization.
 *
 * Every callback is invoked without the runtime state lock. Calls may safely re-enter [session].
 */
interface MatchPointPlayerBackend {
    fun onOpened(session: MatchPointPlayerSession) {}
    fun onRequest(session: MatchPointPlayerSession, request: MatchPointInboundRequest) {}
    fun onCommand(session: MatchPointPlayerSession, message: MatchPointMessage) {}
    fun onClosed(identity: MatchPointSessionIdentity, reason: MatchPointRuntimeCloseReason) {}
}

/**
 * An explicitly provisioned PIC issuer for one already-authenticated MatchPoint generation.
 *
 * The issuer must perform the real content-authorization operation; parsing a PIC request or
 * having a player backend is not authorization. Its call must be bounded by the owner. No issuer
 * is installed by default, and this interface does not register the MatchPoint RCS client.
 */
internal interface MatchPointPicAuthorizer {
    val identity: MatchPointSessionIdentity

    /**
     * Return only a successful, issuer-backed response; throw if authorization fails. The
     * [picRequest] array is borrowed for this synchronous call and wiped immediately afterward.
     */
    fun authorize(item: MatchPointItemReference, picRequest: ByteArray): MatchPointAuthorizeItemResponse
}

/** Restricted operations exposed to the backend. There is deliberately no URL-fetch operation. */
interface MatchPointPlayerSession {
    val identity: MatchPointSessionIdentity

    fun respondSuccess(request: MatchPointInboundRequest, payload: MatchPointPayload)
    fun respondFailure(request: MatchPointInboundRequest, error: Any)
    fun publishPlaybackState(state: MatchPointPlaybackState)
    fun publishNotification()
    fun snapshot(): MatchPointRuntimeSnapshot
}

/**
 * Strict receiver-side MatchPoint state machine for diagnostic and conformance work.
 *
 * This class intentionally exposes no registration descriptor or factory. Merely constructing it
 * cannot register `CarPlayMatchPoint`, change feature readiness, or advertise VideoPlayback.
 */
internal class MatchPointRuntime(
    override val identity: MatchPointSessionIdentity,
    val stream: RcsDataStream,
    private val backend: MatchPointPlayerBackend,
    private val picAuthorizer: MatchPointPicAuthorizer? = null,
    private val protocolLimits: MatchPointLimits = MatchPointLimits(),
    private val runtimeLimits: MatchPointRuntimeLimits = MatchPointRuntimeLimits(),
    private val nowNanos: () -> Long = System::nanoTime,
    private val outboundSender: (RcsMessage) -> Unit = stream::send,
    private val transportCloser: () -> Unit = stream::close,
) : RcsDataStreamHandler, MatchPointPlayerSession {
    private val lock = Any()
    private val backendCallbackLock = ReentrantLock()
    private val inboundQueue = ArrayDeque<InboundEnvelope>()
    private val outboundQueue = ArrayDeque<OutboundEnvelope>()
    private val pendingInboundRequests = LinkedHashMap<Long, MatchPointInboundRequest>()
    private val inboundRequestIds = LinkedHashSet<Long>()
    private val diagnostics = ArrayDeque<MatchPointRuntimeDiagnostic>()
    private val terminalTaskStarts = AtomicInteger(0)

    private var state = MatchPointRuntimeState.NEW
    private var terminalReason: MatchPointRuntimeCloseReason? = null
    private var transportCloseState = MatchPointTransportCloseState.NOT_REQUESTED
    private var inboundQueueBytes = 0
    private var outboundQueueBytes = 0
    private var inboundDraining = false
    private var outboundDraining = false
    private var backendWasOpened = false
    private var backendClosed = false
    private var timeoutWorker: ScheduledExecutorService? = null
    private var diagnosticSequence = 0L
    private var droppedDiagnostics = 0L

    init {
        require(stream.clientType === RcsClientTypes.CAR_PLAY_MATCH_POINT) {
            "MatchPoint runtime requires the exact CarPlayMatchPoint client type"
        }
        require(picAuthorizer == null || picAuthorizer.identity === identity) {
            "MatchPoint PIC issuer must bind the exact runtime session identity"
        }
        require(identity.claim()) {
            "MatchPoint session identity is already bound to another runtime"
        }
    }

    override fun onStreamOpened(stream: RcsDataStream) {
        requireExactStream(stream)
        val worker = try {
            synchronized(lock) {
                when (state) {
                    MatchPointRuntimeState.NEW -> Unit
                    MatchPointRuntimeState.OPEN -> throw fail(
                        MatchPointRuntimeCloseReason.OPEN_TWICE,
                        "MatchPoint stream opened more than once",
                    )
                    MatchPointRuntimeState.FAILED,
                    MatchPointRuntimeState.CLOSED,
                    -> throw MatchPointRuntimeException(
                        terminalReason ?: MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN,
                        "MatchPoint runtime is terminal",
                    )
                }
                state = MatchPointRuntimeState.OPEN
                addDiagnosticLocked("opened", MatchPointDiagnosticDirection.LIFECYCLE)
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(
                        runnable,
                        "matchpoint-timeouts-g${identity.generation}",
                    ).apply { isDaemon = true }
                }.also { timeoutWorker = it }
            }
        } catch (failure: MatchPointRuntimeException) {
            if (failure.reason == MatchPointRuntimeCloseReason.OPEN_TWICE) {
                throw fail(failure.reason, failure.message ?: "MatchPoint open failed", failure)
            }
            throw failure
        }
        try {
            worker.scheduleWithFixedDelay(
                ::pollTimeoutsSafely,
                runtimeLimits.timeoutPollMillis,
                runtimeLimits.timeoutPollMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: Throwable) {
            throw fail(
                MatchPointRuntimeCloseReason.BACKEND_FAILED,
                "Unable to start MatchPoint timeout worker",
                failure,
            )
        }
        invokeBackend("open", marksBackendOpened = true) { backend.onOpened(this) }
    }

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        requireExactStream(stream)
        val preflightFailure = synchronized(lock) {
            when (state) {
                MatchPointRuntimeState.OPEN -> null
                MatchPointRuntimeState.NEW -> MatchPointRuntimeException(
                    MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN,
                    "MatchPoint message arrived before open",
                )
                MatchPointRuntimeState.FAILED,
                MatchPointRuntimeState.CLOSED,
                -> MatchPointRuntimeException(
                    terminalReason ?: MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN,
                    "MatchPoint runtime is terminal",
                )
            }
        }
        if (preflightFailure != null) {
            if (preflightFailure.reason == MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN) {
                throw fail(
                    preflightFailure.reason,
                    preflightFailure.message ?: "MatchPoint receive failed",
                    preflightFailure,
                )
            }
            throw preflightFailure
        }
        val carrier = try {
            MatchPointCarrierCodec.decode(message, protocolLimits)
        } catch (failure: Throwable) {
            throw fail(
                MatchPointRuntimeCloseReason.INVALID_CARRIER,
                "Invalid MatchPoint comm carrier",
                failure,
            )
        }
        val decoded = carrier.message
        if (!decoded.hasKnownSchema) {
            throw fail(
                MatchPointRuntimeCloseReason.UNKNOWN_MESSAGE,
                "Unknown MatchPoint message is not executable",
            )
        }
        try {
            validateInboundDirection(decoded)
        } catch (failure: MatchPointRuntimeException) {
            throw fail(failure.reason, failure.message ?: "Invalid MatchPoint direction", failure)
        }

        val shouldDrain = try {
            synchronized(lock) {
                requireOpenLocked()
                val bytes = carrier.rcsBody.size
                if (inboundQueue.size >= runtimeLimits.maximumInboundQueueMessages ||
                    inboundQueueBytes.toLong() + bytes > runtimeLimits.maximumInboundQueueBytes
                ) {
                    throw fail(
                        MatchPointRuntimeCloseReason.INBOUND_QUEUE_LIMIT,
                        "MatchPoint inbound queue limit exceeded",
                    )
                }
                inboundQueue.addLast(InboundEnvelope(decoded, bytes))
                inboundQueueBytes += bytes
                addDiagnosticLocked(
                    "accepted",
                    MatchPointDiagnosticDirection.INBOUND,
                    decoded.kind,
                    decoded.type,
                    bytes,
                )
                if (inboundDraining) false else {
                    inboundDraining = true
                    true
                }
            }
        } catch (failure: MatchPointRuntimeException) {
            if (failure.reason == MatchPointRuntimeCloseReason.INBOUND_QUEUE_LIMIT ||
                failure.reason == MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN
            ) {
                throw fail(failure.reason, failure.message ?: "MatchPoint receive failed", failure)
            }
            throw failure
        }
        if (shouldDrain) drainInbound()
    }

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        if (stream !== this.stream) {
            fail(
                MatchPointRuntimeCloseReason.WRONG_STREAM,
                "MatchPoint callback used a different stream",
            )
            return
        }
        synchronized(lock) {
            transportCloseState = MatchPointTransportCloseState.COMPLETE
        }
        terminate(
            if (cause == null) {
                MatchPointRuntimeCloseReason.TRANSPORT_CLOSED
            } else {
                MatchPointRuntimeCloseReason.TRANSPORT_FAILED
            },
            failed = cause != null,
            closeStream = false,
        )
    }

    override fun close() {
        terminate(
            MatchPointRuntimeCloseReason.LOCAL_CLOSE,
            failed = false,
            closeStream = true,
        )
    }

    override fun respondSuccess(request: MatchPointInboundRequest, payload: MatchPointPayload) {
        val response = MatchPointMessage.response(
            messageId = requireNotNull(request.message.messageId),
            payload = payload,
            limits = protocolLimits,
        )
        respond(request, response)
    }

    override fun respondFailure(request: MatchPointInboundRequest, error: Any) {
        val response = MatchPointMessage.failure(
            messageId = requireNotNull(request.message.messageId),
            responseType = request.message.type,
            error = error,
            limits = protocolLimits,
        )
        respond(request, response)
    }

    override fun publishPlaybackState(state: MatchPointPlaybackState) {
        enqueueOutbound(MatchPointMessage.oneWay(state, protocolLimits))
    }

    override fun publishNotification() {
        enqueueOutbound(MatchPointMessage.oneWay(MatchPointNotification, protocolLimits))
    }

    /** Deterministic inbound-request timeout hook; the runtime also polls automatically. */
    fun expireTimedOutRequests() {
        synchronized(lock) {
            if (state != MatchPointRuntimeState.OPEN) return
            val now = nowNanos()
            if (pendingInboundRequests.values.any {
                deadlineReached(now, it.deadlineNanos)
            }) {
                throw fail(
                    MatchPointRuntimeCloseReason.INBOUND_REQUEST_TIMEOUT,
                    "MatchPoint inbound request timed out",
                )
            }
        }
    }

    override fun snapshot(): MatchPointRuntimeSnapshot = synchronized(lock) {
        MatchPointRuntimeSnapshot(
            generation = identity.generation,
            state = state,
            terminalReason = terminalReason,
            transportCloseState = transportCloseState,
            queuedInboundMessages = inboundQueue.size,
            queuedInboundBytes = inboundQueueBytes,
            queuedOutboundMessages = outboundQueue.size,
            queuedOutboundBytes = outboundQueueBytes,
            pendingInboundRequests = pendingInboundRequests.size,
            observedInboundRequestIds = inboundRequestIds.size,
            diagnostics = diagnostics.toList(),
            droppedDiagnostics = droppedDiagnostics,
            terminalTasksStarted = terminalTaskStarts.get(),
        )
    }

    private fun requireExactStream(candidate: RcsDataStream) {
        if (candidate === stream) return
        throw fail(
            MatchPointRuntimeCloseReason.WRONG_STREAM,
            "MatchPoint callback used a different stream",
        )
    }

    private fun validateInboundDirection(message: MatchPointMessage) {
        if (message.payload is MatchPointAuthorizeItem && picAuthorizer == null) {
            throw MatchPointRuntimeException(
                MatchPointRuntimeCloseReason.UNSUPPORTED_AUTHORIZATION,
                "MatchPoint PIC authorization is not implemented",
            )
        }
        val valid = when (message.kind) {
            MatchPointMessageKind.REQUEST -> message.payload is MatchPointAuthorizeItem ||
                message.payload is MatchPointPlaybackInfoRequest ||
                message.payload is MatchPointPropertyRequest ||
                message.payload is MatchPointStreamingKey ||
                message.payload is MatchPointInsertPlayQueueItem ||
                message.payload is MatchPointRemovePlayQueueItem ||
                message.payload is MatchPointStop ||
                message.payload is MatchPointSeek ||
                message.payload is MatchPointSetRate ||
                message.payload is MatchPointSetProperty

            MatchPointMessageKind.RESPONSE -> false
            MatchPointMessageKind.ONE_WAY -> message.payload is MatchPointInsertPlayQueueItem ||
                message.payload is MatchPointRemovePlayQueueItem ||
                message.payload is MatchPointStop ||
                message.payload is MatchPointSeek ||
                message.payload is MatchPointSetRate ||
                message.payload is MatchPointSetProperty ||
                message.payload is MatchPointPlaybackCoordinationMedium
        }
        if (!valid) {
            throw MatchPointRuntimeException(
                MatchPointRuntimeCloseReason.WRONG_DIRECTION,
                "MatchPoint message is not valid in the receiver direction",
            )
        }
    }

    private fun drainInbound() {
        try {
            while (true) {
                val envelope = synchronized(lock) {
                    if (state != MatchPointRuntimeState.OPEN) {
                        inboundDraining = false
                        return
                    }
                    val next = inboundQueue.pollFirst()
                    if (next == null) {
                        inboundDraining = false
                        return
                    }
                    inboundQueueBytes -= next.byteCount
                    next
                }
                processInbound(envelope.message)
            }
        } catch (failure: Throwable) {
            synchronized(lock) { inboundDraining = false }
            if (failure is MatchPointRuntimeException) {
                throw fail(
                    failure.reason,
                    failure.message ?: "MatchPoint inbound processing failed",
                    failure,
                )
            }
            throw fail(
                MatchPointRuntimeCloseReason.BACKEND_FAILED,
                "MatchPoint inbound processing failed",
                failure,
            )
        }
    }

    private fun processInbound(message: MatchPointMessage) {
        when (message.kind) {
            MatchPointMessageKind.REQUEST -> {
                val request = synchronized(lock) { registerInboundRequestLocked(message) }
                val authorization = message.payload as? MatchPointAuthorizeItem
                if (authorization == null) {
                    invokeBackend("request") { backend.onRequest(this, request) }
                } else {
                    val issuer = picAuthorizer ?: throw fail(
                        MatchPointRuntimeCloseReason.UNSUPPORTED_AUTHORIZATION,
                        "MatchPoint PIC issuer is unavailable",
                    )
                    invokeBackend("authorizeItem") {
                        check(issuer.identity === identity) {
                            "MatchPoint PIC issuer changed its bound session"
                        }
                        val requestBytes = authorization.picRequest
                        val response = try {
                            issuer.authorize(authorization.item, requestBytes)
                        } finally {
                            requestBytes.fill(0)
                        }
                        check(issuer.identity === identity) {
                            "MatchPoint PIC issuer changed its bound session during authorization"
                        }
                        respondSuccess(request, response)
                    }
                }
            }
            MatchPointMessageKind.ONE_WAY -> {
                invokeBackend("command") { backend.onCommand(this, message) }
            }
            MatchPointMessageKind.RESPONSE -> {
                throw fail(
                    MatchPointRuntimeCloseReason.WRONG_DIRECTION,
                    "Receiver-side MatchPoint has no confirmed outbound request schema",
                )
            }
        }
    }

    private fun registerInboundRequestLocked(message: MatchPointMessage): MatchPointInboundRequest {
        requireOpenLocked()
        val messageId = requireNotNull(message.messageId)
        if (messageId in inboundRequestIds) {
            throw fail(
                MatchPointRuntimeCloseReason.DUPLICATE_REQUEST,
                "Duplicate MatchPoint request",
            )
        }
        if (inboundRequestIds.size >= runtimeLimits.maximumInboundRequestIds) {
            throw fail(
                MatchPointRuntimeCloseReason.INBOUND_REQUEST_ID_LIMIT,
                "MatchPoint inbound request-ID budget exhausted",
            )
        }
        if (pendingInboundRequests.size >= runtimeLimits.maximumPendingInboundRequests) {
            throw fail(
                MatchPointRuntimeCloseReason.INBOUND_REQUEST_LIMIT,
                "MatchPoint pending inbound request limit exceeded",
            )
        }
        val request = MatchPointInboundRequest(
            identity = identity,
            message = message,
            deadlineNanos = saturatingAdd(
                nowNanos(),
                runtimeLimits.inboundRequestTimeoutMillis * NANOS_PER_MILLISECOND,
            ),
            token = Any(),
        )
        inboundRequestIds += messageId
        pendingInboundRequests[messageId] = request
        return request
    }

    private fun respond(request: MatchPointInboundRequest, response: MatchPointMessage) {
        try {
            synchronized(lock) {
                requireOpenLocked()
                if (request.identity !== identity) {
                    throw MatchPointRuntimeException(
                        MatchPointRuntimeCloseReason.WRONG_STREAM,
                        "Inbound request belongs to a different MatchPoint session",
                    )
                }
                val messageId = requireNotNull(request.message.messageId)
                val live = pendingInboundRequests[messageId]
                if (live !== request || live.token !== request.token) {
                    throw MatchPointRuntimeException(
                        MatchPointRuntimeCloseReason.DUPLICATE_REQUEST,
                        "Inbound request is no longer live",
                    )
                }
                if (deadlineReached(nowNanos(), request.deadlineNanos)) {
                    throw MatchPointRuntimeException(
                        MatchPointRuntimeCloseReason.INBOUND_REQUEST_TIMEOUT,
                        "MatchPoint response missed its request deadline",
                    )
                }
                if (response.kind != MatchPointMessageKind.RESPONSE ||
                    response.messageId != messageId ||
                    response.type != request.message.type
                ) {
                    throw MatchPointRuntimeException(
                        MatchPointRuntimeCloseReason.WRONG_DIRECTION,
                        "MatchPoint response does not match its inbound request",
                    )
                }
                pendingInboundRequests.remove(messageId)
            }
        } catch (failure: MatchPointRuntimeException) {
            if (synchronized(lock) { state == MatchPointRuntimeState.OPEN }) {
                throw fail(failure.reason, failure.message ?: "Invalid backend response", failure)
            }
            throw failure
        }
        enqueueOutbound(response)
    }

    private fun enqueueOutbound(message: MatchPointMessage) {
        val carrier = try {
            MatchPointCarrierCodec.encode(message, protocolLimits)
        } catch (failure: Throwable) {
            throw fail(
                MatchPointRuntimeCloseReason.SEND_FAILED,
                "Unable to encode MatchPoint outbound carrier",
                failure,
            )
        }
        val shouldDrain = try {
            synchronized(lock) {
                requireOpenLocked()
                val bytes = carrier.rcsBody.size
                if (outboundQueue.size >= runtimeLimits.maximumOutboundQueueMessages ||
                    outboundQueueBytes.toLong() + bytes > runtimeLimits.maximumOutboundQueueBytes
                ) {
                    throw fail(
                        MatchPointRuntimeCloseReason.OUTBOUND_QUEUE_LIMIT,
                        "MatchPoint outbound queue limit exceeded",
                    )
                }
                outboundQueue.addLast(OutboundEnvelope(carrier.asRcsMessage(), message, bytes))
                outboundQueueBytes += bytes
                if (outboundDraining) false else {
                    outboundDraining = true
                    true
                }
            }
        } catch (failure: MatchPointRuntimeException) {
            if (failure.reason == MatchPointRuntimeCloseReason.OUTBOUND_QUEUE_LIMIT) {
                throw fail(failure.reason, failure.message ?: "MatchPoint send failed", failure)
            }
            throw failure
        }
        if (shouldDrain) drainOutbound()
    }

    private fun drainOutbound() {
        try {
            while (true) {
                val envelope = synchronized(lock) {
                    if (state != MatchPointRuntimeState.OPEN) {
                        outboundDraining = false
                        return
                    }
                    val next = outboundQueue.pollFirst()
                    if (next == null) {
                        outboundDraining = false
                        return
                    }
                    outboundQueueBytes -= next.byteCount
                    next
                }
                try {
                    outboundSender(envelope.rcsMessage)
                } catch (failure: Throwable) {
                    throw fail(
                        MatchPointRuntimeCloseReason.SEND_FAILED,
                        "MatchPoint transport send failed",
                        failure,
                    )
                }
                addDiagnostic(
                    "sent",
                    MatchPointDiagnosticDirection.OUTBOUND,
                    envelope.message.kind,
                    envelope.message.type,
                    envelope.byteCount,
                )
            }
        } finally {
            synchronized(lock) { outboundDraining = false }
        }
    }

    private fun pollTimeoutsSafely() {
        if (synchronized(lock) { state != MatchPointRuntimeState.OPEN }) return
        try {
            expireTimedOutRequests()
        } catch (_: MatchPointRuntimeException) {
            // expireTimedOutRequests already moved the runtime to a terminal state.
        } catch (failure: Throwable) {
            fail(
                MatchPointRuntimeCloseReason.BACKEND_FAILED,
                "MatchPoint timeout processing failed",
                failure,
            )
        }
    }

    private fun invokeBackend(
        label: String,
        marksBackendOpened: Boolean = false,
        callback: () -> Unit,
    ) {
        val failure = runCatching {
            try {
                backendCallbackLock.lockInterruptibly()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            try {
                // A callback which was queued behind another backend call must not cross a
                // concurrently published terminal boundary.
                val mayInvoke = synchronized(lock) {
                    if (state != MatchPointRuntimeState.OPEN) {
                        false
                    } else {
                        if (marksBackendOpened) backendWasOpened = true
                        true
                    }
                }
                if (!mayInvoke) return@runCatching
                callback()
            } finally {
                backendCallbackLock.unlock()
            }
        }.exceptionOrNull() ?: return
        throw fail(
            MatchPointRuntimeCloseReason.BACKEND_FAILED,
            "MatchPoint backend callback '$label' failed",
            failure,
        )
    }

    private fun requireOpenLocked() {
        if (state == MatchPointRuntimeState.OPEN) return
        val reason = if (state == MatchPointRuntimeState.NEW) {
            MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN
        } else {
            terminalReason ?: MatchPointRuntimeCloseReason.MESSAGE_BEFORE_OPEN
        }
        throw MatchPointRuntimeException(reason, "MatchPoint runtime is not open")
    }

    private fun fail(
        reason: MatchPointRuntimeCloseReason,
        message: String,
        cause: Throwable? = null,
    ): MatchPointRuntimeException {
        terminate(reason, failed = true, closeStream = true)
        val effectiveReason = synchronized(lock) { terminalReason ?: reason }
        val failure = if (cause is MatchPointRuntimeException && cause.reason == effectiveReason) {
            cause
        } else {
            MatchPointRuntimeException(
                effectiveReason,
                if (effectiveReason == reason) message else "MatchPoint runtime is terminal",
                cause,
            )
        }
        return failure
    }

    private fun terminate(
        reason: MatchPointRuntimeCloseReason,
        failed: Boolean,
        closeStream: Boolean,
    ) {
        val transition = synchronized(lock) {
            if (state == MatchPointRuntimeState.FAILED || state == MatchPointRuntimeState.CLOSED) {
                return@synchronized null
            }
            state = if (failed) MatchPointRuntimeState.FAILED else MatchPointRuntimeState.CLOSED
            terminalReason = reason
            inboundQueue.clear()
            inboundQueueBytes = 0
            outboundQueue.clear()
            outboundQueueBytes = 0
            pendingInboundRequests.clear()
            inboundDraining = false
            outboundDraining = false
            if (closeStream) transportCloseState = MatchPointTransportCloseState.PENDING
            addDiagnosticLocked(
                if (failed) "failed" else "closed",
                MatchPointDiagnosticDirection.LIFECYCLE,
            )
            val shouldNotifyBackend = backendWasOpened && !backendClosed
            if (shouldNotifyBackend) backendClosed = true
            TerminalTransition(
                timeoutWorker = timeoutWorker.also { timeoutWorker = null },
                notifyBackend = shouldNotifyBackend,
            )
        } ?: return

        transition.timeoutWorker?.shutdownNow()
        if (transition.notifyBackend) {
            notifyBackendClosedWithoutBlocking(reason)
        }
        if (closeStream) {
            runTerminalTask("matchpoint-transport-close-g${identity.generation}") {
                closeTransportSafely()
            }
        }
    }

    /** A terminal transition must never wait for arbitrary backend code already in progress. */
    private fun notifyBackendClosedWithoutBlocking(reason: MatchPointRuntimeCloseReason) {
        runTerminalTask("matchpoint-close-g${identity.generation}") {
            // If the terminal transition was detected while re-entering the state lock, wait for
            // that outer critical section to unwind before invoking application code.
            synchronized(lock) { Unit }
            val acquired = try {
                backendCallbackLock.tryLock(
                    runtimeLimits.terminalCallbackWaitMillis,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (acquired) {
                try {
                    notifyBackendClosed(reason)
                } finally {
                    backendCallbackLock.unlock()
                }
            } else {
                addDiagnostic(
                    "backend_close_deferred_timeout",
                    MatchPointDiagnosticDirection.LIFECYCLE,
                )
            }
        }
    }

    private fun runTerminalTask(name: String, task: () -> Unit) {
        if (!reserveTerminalTask()) {
            addDiagnostic(
                "terminal_task_limit",
                MatchPointDiagnosticDirection.LIFECYCLE,
            )
            return
        }
        runCatching {
            Thread(
                {
                    runCatching(task).onFailure {
                        addDiagnostic(
                            "terminal_task_failed",
                            MatchPointDiagnosticDirection.LIFECYCLE,
                        )
                    }
                },
                name,
            ).apply {
                isDaemon = true
                start()
            }
        }.onFailure {
            terminalTaskStarts.decrementAndGet()
            addDiagnostic(
                "terminal_task_rejected",
                MatchPointDiagnosticDirection.LIFECYCLE,
            )
        }
    }

    private fun reserveTerminalTask(): Boolean {
        while (true) {
            val current = terminalTaskStarts.get()
            if (current >= MAXIMUM_TERMINAL_TASKS) return false
            if (terminalTaskStarts.compareAndSet(current, current + 1)) return true
        }
    }

    private fun closeTransportSafely() {
        try {
            transportCloser()
            synchronized(lock) {
                if (transportCloseState == MatchPointTransportCloseState.PENDING) {
                    transportCloseState = MatchPointTransportCloseState.COMPLETE
                }
            }
        } catch (_: Throwable) {
            synchronized(lock) {
                transportCloseState = MatchPointTransportCloseState.FAILED
                addDiagnosticLocked(
                    "transport_close_failed",
                    MatchPointDiagnosticDirection.LIFECYCLE,
                )
            }
        }
    }

    private fun notifyBackendClosed(reason: MatchPointRuntimeCloseReason) {
        runCatching { backend.onClosed(identity, reason) }
            .onFailure {
                addDiagnostic(
                    "backend_close_failed",
                    MatchPointDiagnosticDirection.LIFECYCLE,
                )
            }
    }

    private fun addDiagnostic(
        code: String,
        direction: MatchPointDiagnosticDirection,
        kind: MatchPointMessageKind? = null,
        type: String? = null,
        byteCount: Int = 0,
    ) {
        synchronized(lock) { addDiagnosticLocked(code, direction, kind, type, byteCount) }
    }

    private fun addDiagnosticLocked(
        code: String,
        direction: MatchPointDiagnosticDirection,
        kind: MatchPointMessageKind? = null,
        type: String? = null,
        byteCount: Int = 0,
    ) {
        if (diagnostics.size == runtimeLimits.maximumDiagnostics) {
            diagnostics.removeFirst()
            if (droppedDiagnostics != Long.MAX_VALUE) droppedDiagnostics++
        }
        if (diagnosticSequence != Long.MAX_VALUE) diagnosticSequence++
        diagnostics.addLast(
            MatchPointRuntimeDiagnostic(
                sequence = diagnosticSequence,
                code = safeCode(code),
                direction = direction,
                kind = kind,
                knownType = type?.takeIf { it in DIAGNOSTIC_SAFE_TYPES },
                byteCount = byteCount.coerceIn(0, runtimeLimits.maximumOutboundQueueBytes),
            ),
        )
    }

    private data class InboundEnvelope(
        val message: MatchPointMessage,
        val byteCount: Int,
    )

    private data class OutboundEnvelope(
        val rcsMessage: RcsMessage,
        val message: MatchPointMessage,
        val byteCount: Int,
    )

    private data class TerminalTransition(
        val timeoutWorker: ScheduledExecutorService?,
        val notifyBackend: Boolean,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAXIMUM_TERMINAL_TASKS = 2

        val DIAGNOSTIC_SAFE_TYPES = setOf(
            MatchPointTypes.AUTHORIZE_ITEM,
            MatchPointTypes.INSERT_PLAY_QUEUE_ITEM,
            MatchPointTypes.REMOVE_PLAY_QUEUE_ITEM,
            MatchPointTypes.STOP,
            MatchPointTypes.PLAYBACK_INFO,
            MatchPointTypes.SEEK,
            MatchPointTypes.SET_RATE,
            MatchPointTypes.PROPERTY,
            MatchPointTypes.SET_PROPERTY,
            MatchPointTypes.STREAMING_KEY,
            MatchPointTypes.UNHANDLED_URL,
            MatchPointTypes.PLAYBACK_COORDINATION_MEDIUM,
            MatchPointTypes.PLAYBACK_STATE,
            MatchPointTypes.NOTIFICATION,
        )

        fun deadlineReached(now: Long, deadline: Long): Boolean = now - deadline >= 0L

        fun saturatingAdd(value: Long, positiveDelta: Long): Long =
            if (value > Long.MAX_VALUE - positiveDelta) Long.MAX_VALUE else value + positiveDelta

        fun safeCode(value: String): String =
            value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(48)
                .ifEmpty { "event" }
    }
}
