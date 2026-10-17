package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Application callback for one authenticated type-130 RCS stream. */
interface RcsDataStreamHandler : Closeable {
    fun onStreamOpened(stream: RcsDataStream) {}
    fun onMessage(stream: RcsDataStream, message: RcsMessage) {}
    fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {}
    override fun close() {}
}

enum class RcsDataStreamHandlerReadiness {
    READY,
    NOT_READY,
}

/** A bounded, non-sensitive result from a live handler readiness probe. */
data class RcsDataStreamHandlerProbe(
    val readiness: RcsDataStreamHandlerReadiness,
    val reasonCode: String? = null,
) {
    init {
        require(reasonCode == null || SAFE_REASON.matches(reasonCode)) {
            "reasonCode must be a short machine-readable identifier"
        }
    }

    val isReady: Boolean
        get() = readiness == RcsDataStreamHandlerReadiness.READY

    companion object {
        fun ready(): RcsDataStreamHandlerProbe =
            RcsDataStreamHandlerProbe(RcsDataStreamHandlerReadiness.READY)

        fun notReady(reasonCode: String): RcsDataStreamHandlerProbe =
            RcsDataStreamHandlerProbe(RcsDataStreamHandlerReadiness.NOT_READY, reasonCode)

        private val SAFE_REASON = Regex("[a-z0-9][a-z0-9_.-]{0,63}")
    }
}

/**
 * Immutable registration for one exact RCS client type.
 *
 * Static catalog knowledge is deliberately insufficient. Callers must obtain a descriptor and a
 * successful live [probe] before advertising or opening the stream. A fresh handler is then made
 * for each authenticated transport.
 */
class RcsDataStreamHandlerDescriptor(
    val clientType: RcsClientType,
    val implementationId: String,
    private val readinessProbe: () -> RcsDataStreamHandlerProbe,
    private val handlerFactory: (RcsDataStream) -> RcsDataStreamHandler,
) {
    init {
        require(SAFE_IMPLEMENTATION_ID.matches(implementationId)) {
            "implementationId must be a short machine-readable identifier"
        }
    }

    fun probe(): RcsDataStreamHandlerProbe = try {
        readinessProbe()
    } catch (_: Throwable) {
        RcsDataStreamHandlerProbe.notReady("probe_failed")
    }

    internal fun create(stream: RcsDataStream): RcsDataStreamHandler {
        require(stream.clientType == clientType) {
            "RCS descriptor '${clientType.name}' cannot create '${stream.clientType.name}'"
        }
        return handlerFactory(stream)
    }

    companion object {
        fun ready(
            clientType: RcsClientType,
            implementationId: String,
            handlerFactory: (RcsDataStream) -> RcsDataStreamHandler,
        ): RcsDataStreamHandlerDescriptor = RcsDataStreamHandlerDescriptor(
            clientType = clientType,
            implementationId = implementationId,
            readinessProbe = RcsDataStreamHandlerProbe::ready,
            handlerFactory = handlerFactory,
        )

        private val SAFE_IMPLEMENTATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
    }
}

/** Resolves a concrete handler registration for an exact negotiated client type. */
fun interface RcsDataStreamHandlerFactory {
    fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor?
}

data class RcsDataStreamLimits(
    val acceptPollMillis: Long = 250,
    val candidateAuthenticationTimeoutMillis: Long = 3_000,
    val receivePollMillis: Long = 1_000,
    val peerConnectTimeoutMillis: Long = 15_000,
    val maxConcurrentCandidates: Int = 4,
    val maxCandidateAttempts: Int = 8,
    val listenerBacklog: Int = 4,
    val maxPendingMessages: Int = 256,
    val maxPendingBodyBytes: Int = 1_048_576,
) {
    init {
        require(acceptPollMillis in 1..MAX_TIMEOUT_MILLIS)
        require(candidateAuthenticationTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(receivePollMillis in 1..MAX_TIMEOUT_MILLIS)
        require(peerConnectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS)
        require(maxConcurrentCandidates in 1..MAX_CANDIDATES)
        require(maxCandidateAttempts in maxConcurrentCandidates..MAX_CANDIDATE_ATTEMPTS)
        require(listenerBacklog in 1..MAX_LISTENER_BACKLOG)
        require(maxPendingMessages in 1..MAX_PENDING_MESSAGES)
        require(maxPendingBodyBytes >= 0)
    }

    private companion object {
        const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val MAX_CANDIDATES = 16
        const val MAX_CANDIDATE_ATTEMPTS = 64
        const val MAX_LISTENER_BACKLOG = 32
        const val MAX_PENDING_MESSAGES = 4096
    }
}

/**
 * Server-side RCS transport for a single AirPlay SETUP transaction.
 *
 * Candidate sockets race only until one proves possession of the negotiated read key by sending a
 * valid AEAD frame. Promotion, listener shutdown, and alternate-candidate shutdown are one atomic
 * state transition.
 */
class RcsDataStream private constructor(
    val clientType: RcsClientType,
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val bindAddress: InetAddress,
    private val expectedPeerAddress: InetAddress?,
    private val limits: RcsDataStreamLimits,
    private val channelLimits: RcsChannelLimits,
    private val onTrace: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val peerConnected = CountDownLatch(1)
    private val stateLock = Object()
    private val callbackLock = Object()
    private val candidates = linkedSetOf<RcsChannel>()
    private val candidateThreads = linkedSetOf<Thread>()
    private val pending = ArrayDeque<RcsMessage>()
    private var pendingBytes = 0
    private var candidateAttempts = 0
    private var listeningPort: Int? = null
    private var opened = false
    private var openCallbackHandler: RcsDataStreamHandler? = null
    private var handlerDispatchPaused = false
    private var closedCallbackSent = false
    private var acceptingEnded = false
    private var handler: RcsDataStreamHandler? = null
    private var listener: RcsListener? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var winner: RcsChannel? = null

    val port: Int
        get() = synchronized(stateLock) {
            listeningPort ?: throw IllegalStateException("RCS data stream has not been opened")
        }

    val localAddress: InetAddress
        get() = bindAddress

    fun listen(handler: RcsDataStreamHandler? = null): Int {
        synchronized(stateLock) {
            check(listener == null && listeningPort == null) {
                "RCS data stream is already listening"
            }
            if (closed.get()) throw IOException("RCS data stream is closed")
            this.handler = handler
        }

        val bound = RcsChannel.listen(
            readKey = readKey,
            writeKey = writeKey,
            bindAddress = bindAddress,
            expectedPeerAddress = expectedPeerAddress,
            backlog = limits.listenerBacklog,
            maxAcceptedSockets = limits.maxCandidateAttempts,
            limits = channelLimits,
            onTrace = ::trace,
        )
        val thread = Thread(
            { acceptLoop(bound) },
            "carplay-rcs-accept-${safeThreadName(clientType.name)}-${bound.port}",
        ).apply { isDaemon = true }
        synchronized(stateLock) {
            if (closed.get()) {
                bound.close()
                throw IOException("RCS data stream was closed while binding")
            }
            listener = bound
            listeningPort = bound.port
            acceptThread = thread
        }
        thread.start()
        trace(
            "listening address=${bindAddress.hostAddress?.take(128)} port=${bound.port} " +
                "expectedPeer=${expectedPeerAddress?.hostAddress?.take(128) ?: "any"}",
        )
        return bound.port
    }

    /** Attaches the payload handler only after the corresponding SETUP response is committed. */
    fun attach(handler: RcsDataStreamHandler) {
        var callbacks = synchronized(stateLock) {
            if (closed.get()) throw IOException("RCS data stream is closed")
            if (this.handler === handler) return
            check(this.handler == null || this.handler === handler) {
                "A different RCS handler is already attached"
            }
            this.handler = handler
            handlerDispatchPaused = true
            buildList {
                if (opened && openCallbackHandler !== handler) {
                    openCallbackHandler = handler
                    add { handler.onStreamOpened(this@RcsDataStream) }
                }
                drainPendingCallbacksLocked(handler, this)
            }
        }
        try {
            while (true) {
                invokeCallbacks(callbacks)
                callbacks = synchronized(stateLock) {
                    buildList { drainPendingCallbacksLocked(handler, this) }
                        .also { if (it.isEmpty()) handlerDispatchPaused = false }
                }
                if (callbacks.isEmpty()) return
            }
        } finally {
            synchronized(stateLock) { handlerDispatchPaused = false }
        }
    }

    fun awaitPeerConnection(timeoutMillis: Long = limits.peerConnectTimeoutMillis): Boolean {
        require(timeoutMillis in 1..MAX_PUBLIC_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_PUBLIC_TIMEOUT_MILLIS"
        }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS) && winner != null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun send(message: RcsMessage) {
        if (!awaitPeerConnection()) {
            throw IOException("RCS peer did not authenticate for ${clientType.name}")
        }
        val active = winner ?: throw IOException("RCS channel is not connected")
        active.send(message)
    }

    internal fun traceProtocol(message: String) {
        trace("protocol ${sanitize(message)}")
    }

    override fun close() {
        terminate(null)
    }

    private fun acceptLoop(bound: RcsListener) {
        while (!closed.get() && winner == null) {
            if (stopAcceptingIfExhausted(bound)) return
            val accepted = try {
                bound.accept(limits.acceptPollMillis)
            } catch (rejected: RcsPeerRejectedException) {
                recordRejectedAttempt()
                trace("candidate rejected cause=${rejected.javaClass.simpleName}")
                continue
            } catch (limit: RcsCandidateLimitException) {
                endAccepting(bound, limit)
                return
            } catch (failure: Throwable) {
                if (!closed.get() && winner == null) terminate(failure)
                return
            } ?: continue

            val thread = synchronized(stateLock) {
                if (closed.get() || winner != null) {
                    accepted.close()
                    null
                } else if (candidateAttempts >= limits.maxCandidateAttempts) {
                    accepted.close()
                    null
                } else {
                    candidateAttempts++
                    if (candidates.size >= limits.maxConcurrentCandidates) {
                        accepted.close()
                        trace("candidate rejected cause=concurrency_limit")
                        null
                    } else {
                        candidates += accepted
                        Thread(
                            { authenticateCandidate(accepted) },
                            "carplay-rcs-auth-${safeThreadName(clientType.name)}-$candidateAttempts",
                        ).apply {
                            isDaemon = true
                            candidateThreads += this
                        }
                    }
                }
            }
            if (thread == null) continue
            thread.start()
        }
    }

    private fun recordRejectedAttempt() {
        synchronized(stateLock) {
            if (candidateAttempts < limits.maxCandidateAttempts) candidateAttempts++
        }
    }

    private fun stopAcceptingIfExhausted(bound: RcsListener): Boolean {
        val exhausted = synchronized(stateLock) {
            candidateAttempts >= limits.maxCandidateAttempts && winner == null && !closed.get()
        }
        if (!exhausted) return false
        endAccepting(bound, IOException("RCS candidate-attempt limit exceeded"))
        return true
    }

    private fun endAccepting(bound: RcsListener, cause: Throwable) {
        val terminateNow = synchronized(stateLock) {
            acceptingEnded = true
            if (listener === bound) listener = null
            candidates.isEmpty() && winner == null && !closed.get()
        }
        bound.close()
        if (terminateNow) terminate(cause)
    }

    private fun authenticateCandidate(candidate: RcsChannel) {
        var promoted = false
        var failure: Throwable? = null
        try {
            if (!candidate.authenticateFirstFrame(limits.candidateAuthenticationTimeoutMillis)) {
                trace("candidate timed out before authentication")
                return
            }
            promoted = promote(candidate)
            if (!promoted) return
            receiveWinner(candidate)
        } catch (error: Throwable) {
            failure = error
            trace("candidate failed cause=${safeFailure(error)}")
        } finally {
            val exhausted = synchronized(stateLock) {
                candidates.remove(candidate)
                candidateThreads.remove(Thread.currentThread())
                acceptingEnded && candidates.isEmpty() && winner == null && !closed.get()
            }
            if (!promoted) {
                candidate.close()
                if (exhausted) {
                    terminate(IOException("RCS candidate-attempt limit exceeded", failure))
                }
            } else if (!closed.get()) {
                terminate(failure)
            }
        }
    }

    private fun promote(candidate: RcsChannel): Boolean {
        val bound: RcsListener?
        val alternates: List<RcsChannel>
        synchronized(stateLock) {
            if (closed.get() || winner != null || candidate !in candidates) return false
            winner = candidate
            opened = true
            bound = listener
            listener = null
            alternates = candidates.filter { it !== candidate }
            candidates.removeAll(alternates.toSet())
        }
        bound?.close()
        alternates.forEach(RcsChannel::close)
        peerConnected.countDown()
        trace("authenticated peer selected")
        notifyOpened()
        return !closed.get()
    }

    private fun receiveWinner(channel: RcsChannel) {
        while (!closed.get()) {
            val message = channel.receiveNext(limits.receivePollMillis)
            if (message != null) {
                dispatch(message)
                continue
            }
            if (channel.isPeerEnded || channel.isClosed) return
        }
    }

    private fun notifyOpened() {
        val callback = synchronized(stateLock) {
            val target = handler
            if (target == null || openCallbackHandler === target) {
                null
            } else {
                openCallbackHandler = target
                target
            }
        }
        if (callback == null) return
        invokeCallbacks(listOf { callback.onStreamOpened(this) })
    }

    private fun dispatch(message: RcsMessage) {
        val callback = synchronized(stateLock) {
            if (closed.get()) return
            val target = handler
            if (target == null || handlerDispatchPaused) {
                if (pending.size >= limits.maxPendingMessages ||
                    pendingBytes.toLong() + message.body.size > limits.maxPendingBodyBytes
                ) {
                    throw IOException("RCS pending-message limit exceeded for ${clientType.name}")
                }
                pending.addLast(message)
                pendingBytes += message.body.size
                null
            } else {
                target
            }
        }
        if (callback != null) {
            invokeCallbacks(listOf { callback.onMessage(this, message) })
        }
    }

    private fun drainPendingCallbacksLocked(
        handler: RcsDataStreamHandler,
        destination: MutableList<() -> Unit>,
    ) {
        while (pending.isNotEmpty()) {
            val message = pending.removeFirst()
            pendingBytes -= message.body.size
            destination += { handler.onMessage(this, message) }
        }
    }

    private fun invokeCallbacks(callbacks: List<() -> Unit>) {
        var failure: Throwable? = null
        synchronized(callbackLock) {
            if (synchronized(stateLock) { closedCallbackSent || closed.get() }) return
            callbacks.forEach { callback ->
                if (failure == null && !closed.get()) {
                    runCatching(callback).onFailure { failure = it }
                }
            }
        }
        failure?.let {
            trace("handler callback failed cause=${safeFailure(it)}")
            terminate(it)
            throw IOException("RCS handler callback failed", it)
        }
    }

    private fun terminate(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        val bound: RcsListener?
        val channels: List<RcsChannel>
        val threads: List<Thread>
        synchronized(stateLock) {
            bound = listener
            listener = null
            val activeWinner = winner
            winner = null
            channels = (candidates + listOfNotNull(activeWinner)).distinct()
            candidates.clear()
            pending.clear()
            pendingBytes = 0
            threads = candidateThreads.toList() + listOfNotNull(acceptThread)
            candidateThreads.clear()
            acceptThread = null
        }
        bound?.close()
        channels.forEach(RcsChannel::close)
        readKey.fill(0)
        writeKey.fill(0)
        threads.filter { it !== Thread.currentThread() }.forEach(Thread::interrupt)
        peerConnected.countDown()
        notifyClosed(cause)
    }

    private fun notifyClosed(cause: Throwable?) {
        val callback = synchronized(stateLock) {
            if (closedCallbackSent) return
            closedCallbackSent = true
            handler
        }
        synchronized(callbackLock) {
            if (callback != null) {
                runCatching { callback.onStreamClosed(this, cause) }
                    .onFailure { trace("close callback failed cause=${safeFailure(it)}") }
                runCatching { callback.close() }
                    .onFailure { trace("handler close failed cause=${safeFailure(it)}") }
            }
        }
    }

    private fun trace(message: String) {
        runCatching {
            onTrace("RCS [${sanitize(clientType.name)}] ${sanitize(message)}".take(MAX_TRACE_CHARS))
        }
    }

    companion object {
        private const val MAX_PUBLIC_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val MAX_TRACE_CHARS = 512

        fun open(
            clientType: RcsClientType,
            readKey: ByteArray,
            writeKey: ByteArray,
            bindAddress: InetAddress,
            expectedPeerAddress: InetAddress? = null,
            limits: RcsDataStreamLimits = RcsDataStreamLimits(),
            channelLimits: RcsChannelLimits = RcsChannelLimits(),
            onTrace: (String) -> Unit = {},
        ): RcsDataStream {
            require(readKey.size == RcsFrameCodec.KEY_BYTES) {
                "RCS read key must be ${RcsFrameCodec.KEY_BYTES} bytes"
            }
            require(writeKey.size == RcsFrameCodec.KEY_BYTES) {
                "RCS write key must be ${RcsFrameCodec.KEY_BYTES} bytes"
            }
            return RcsDataStream(
                clientType = clientType,
                readKey = readKey.copyOf(),
                writeKey = writeKey.copyOf(),
                bindAddress = bindAddress,
                expectedPeerAddress = expectedPeerAddress,
                limits = limits,
                channelLimits = channelLimits,
                onTrace = onTrace,
            )
        }

        private fun sanitize(value: String): String =
            value.take(MAX_TRACE_CHARS).filterNot(Char::isISOControl)

        private fun safeFailure(failure: Throwable): String {
            return failure.javaClass.simpleName.take(64).ifEmpty { "Throwable" }
        }

        private fun safeThreadName(value: String): String =
            value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)
                .ifEmpty { "unknown" }
    }
}
