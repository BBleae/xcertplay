package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.media.BoundedOperationDeadline
import com.shilapi.xcertplay.media.MediaCodecSupport
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 *
 * The listener is bound to the exact local address of the authenticated AirPlay control
 * connection. A connection from its exact peer remains provisional until its first video frame
 * authenticates; a clear VideoConfig may be parsed while provisional, but is not dispatched.
 */
class ScreenStream internal constructor(
    key: ByteArray,
    private val bindAddress: InetAddress,
    private val expectedPeerAddress: InetAddress,
    private val screenType: Int = 110,
    private val limits: ScreenStreamLimits = ScreenStreamLimits(),
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val retirementJoinTimeoutMillis: Long = DEFAULT_RETIREMENT_JOIN_TIMEOUT_MILLIS,
    private val diagnosticObserver: ((ScreenCandidateDiagnostic) -> Unit)? = null,
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
            onFrame(naluBytes)
        }
        /** Original header and wire body, plus decrypted length-prefixed data before conversion. */
        fun onPacket(sequence: Long, header: ByteArray, wire: ByteArray, plain: ByteArray?) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val key = key.copyOf()
    /** Stop was requested; unlike [retired], this does not prove that the worker exited. */
    private val closed = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private val stateLock = Any()
    private val retirementAttemptLock = Any()
    private val diagnosticLock = Any()
    private val ownedEndpoints = IdentityHashMap<Closeable, Unit>()
    private var diagnosticTerminal = false
    private var nonTerminalDiagnosticEvents = 0
    private var acceptedCandidates = 0
    private var wrongPeers = 0
    private var exactPeers = 0
    private var preAuthConfigs = 0
    private var preAuthTimeouts = 0
    private var preAuthFailures = 0
    private var authenticatedCandidates = 0
    private var server: ServerSocket? = null
    private var candidateSocket: Socket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}

    init {
        require(key.size == 32) { "screen stream key must be 32 bytes" }
        require(!bindAddress.isAnyLocalAddress) {
            "screen stream requires an exact local bind address"
        }
        require(!expectedPeerAddress.isAnyLocalAddress) {
            "screen stream requires an exact expected peer address"
        }
        require(screenType == 110 || screenType == 111) {
            "screen stream diagnostics require screen type 110 or 111"
        }
        require(
            retirementJoinTimeoutMillis in 1L..BoundedOperationDeadline.MAX_TIMEOUT_MILLIS,
        ) {
            "retirementJoinTimeoutMillis must be between 1 and " +
                BoundedOperationDeadline.MAX_TIMEOUT_MILLIS
        }
    }

    fun listen(listener: Listener): Int {
        synchronized(stateLock) {
            check(!closed.get()) { "screen stream is closed" }
            check(started.compareAndSet(false, true)) { "screen stream is already listening" }
            this.listener = listener
        }
        val bound = synchronized(stateLock) {
            check(!closed.get()) { "screen stream is closed" }
            ServerSocket().also { ownedEndpoints[it] = Unit }
        }
        try {
            bound.reuseAddress = true
            bound.bind(InetSocketAddress(bindAddress, 0), limits.maxCandidateAttempts)
            synchronized(stateLock) {
                check(!closed.get()) { "screen stream closed while binding" }
                server = bound
                Thread({ accept(bound) }, "airplay-screen").apply {
                    isDaemon = true
                    thread = this
                    recordDiagnostic(ScreenCandidateStage.LISTENING)
                    start()
                }
            }
            return bound.localPort
        } catch (failure: Throwable) {
            synchronized(stateLock) { closed.set(true) }
            recordDiagnostic(ScreenCandidateStage.TERMINAL_BIND_ERROR)
            try {
                close()
            } catch (cleanupError: Error) {
                if (cleanupError !== failure) cleanupError.addSuppressedBounded(failure)
                throw cleanupError
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun close() {
        val wasOpen = synchronized(stateLock) { !closed.getAndSet(true) }
        if (wasOpen) recordDiagnostic(ScreenCandidateStage.TERMINAL_CLOSED)
        if (retired.get()) return
        synchronized(retirementAttemptLock) {
            if (retired.get()) return@synchronized
            val deadline = BoundedOperationDeadline(retirementJoinTimeoutMillis)
            val attemptedEndpoints = IdentityHashMap<Closeable, Unit>()
            var failure = closeOwnedEndpoints(attemptedEndpoints)
            val worker = synchronized(stateLock) { thread }
            if (worker != null) {
                var interruptSucceeded = true
                try {
                    worker.interrupt()
                } catch (problem: Exception) {
                    interruptSucceeded = false
                    failure = combineRetirementFailure(failure, problem)
                }
                if (worker === Thread.currentThread()) {
                    failure = combineRetirementFailure(
                        failure,
                        IOException("screen stream worker retirement is pending"),
                    )
                } else {
                    failure = joinWorker(worker, deadline, failure, interruptSucceeded)
                }
            }
            failure = combineRetirementFailure(
                failure,
                closeOwnedEndpoints(attemptedEndpoints),
            )
            val complete = synchronized(stateLock) {
                thread == null && ownedEndpoints.isEmpty()
            }
            if (failure == null && complete) retired.set(true)
            failure?.let { throw it }
            check(complete) { "screen stream retirement remains unproven" }
        }
    }

    private fun accept(bound: ServerSocket) {
        val acceptStartedNanos = monotonicNanos()
        var attempts = 0
        var lastFailure: Throwable? = null
        while (!closed.get() && attempts < limits.maxCandidateAttempts) {
            val remainingAcceptMillis = remainingMillis(
                acceptStartedNanos,
                limits.acceptDeadlineMillis,
            )
            if (remainingAcceptMillis <= 0) break
            val accepted = try {
                bound.soTimeout = minOf(limits.acceptPollMillis, remainingAcceptMillis)
                bound.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (error: Exception) {
                if (!closed.get()) finish(error, ScreenCandidateStage.TERMINAL_ACCEPT_ERROR)
                return
            }
            attempts++
            recordDiagnostic(ScreenCandidateStage.CANDIDATE_ACCEPTED)
            synchronized(stateLock) { ownedEndpoints[accepted] = Unit }
            try {
                accepted.setSoLinger(true, 0)
                accepted.tcpNoDelay = true
            } catch (error: Exception) {
                lastFailure = IOException("screen stream candidate socket setup failed", error)
                recordDiagnostic(ScreenCandidateStage.CANDIDATE_SOCKET_ERROR)
                closeEndpointForWorker(accepted)?.let { lastFailure = it }
                continue
            }
            if (!isExpectedPeer(accepted)) {
                lastFailure = IOException("screen stream rejected an unexpected peer")
                recordDiagnostic(ScreenCandidateStage.WRONG_PEER)
                closeEndpointForWorker(accepted)?.let { lastFailure = it }
                continue
            }
            recordDiagnostic(ScreenCandidateStage.EXACT_PEER)
            synchronized(stateLock) {
                if (!closed.get()) candidateSocket = accepted
            }
            if (closed.get()) {
                closeEndpointForWorker(accepted)
                return
            }

            val result = try {
                authenticateCandidate(accepted, acceptStartedNanos)
            } catch (error: Exception) {
                CandidateResult.Rejected(error)
            }
            synchronized(stateLock) {
                if (candidateSocket === accepted) candidateSocket = null
            }
            when (result) {
                CandidateResult.Promoted -> return
                is CandidateResult.Rejected -> {
                    lastFailure = result.cause
                    recordDiagnostic(
                        if (result.cause is SocketTimeoutException) {
                            ScreenCandidateStage.PREAUTH_TIMEOUT
                        } else {
                            ScreenCandidateStage.PREAUTH_REJECTED
                        },
                    )
                    closeEndpointForWorker(accepted)?.let { lastFailure = it }
                }
            }
        }
        if (closed.get()) return
        val failure = if (attempts >= limits.maxCandidateAttempts) {
            IOException("screen stream candidate-attempt limit reached", lastFailure)
        } else {
            SocketTimeoutException("screen stream accept/authentication deadline expired")
                .also { if (lastFailure != null) it.initCause(lastFailure) }
        }
        finish(
            failure,
            if (attempts >= limits.maxCandidateAttempts) {
                ScreenCandidateStage.TERMINAL_ATTEMPT_LIMIT
            } else {
                ScreenCandidateStage.TERMINAL_DEADLINE
            },
        )
    }

    private fun authenticateCandidate(
        sock: Socket,
        acceptStartedNanos: Long,
    ): CandidateResult {
        val input = sock.getInputStream()
        val authStartedNanos = monotonicNanos()
        var candidateNalLengthSize = DEFAULT_NAL_LENGTH_SIZE
        var pendingConfig: PendingConfig? = null
        var preAuthMessages = 0
        frameCounter.set(0)
        while (!closed.get()) {
            if (++preAuthMessages > limits.maxPreAuthMessages) {
                return CandidateResult.Rejected(
                    IOException("screen stream pre-authentication message limit reached"),
                )
            }
            val remainingMillis = candidateRemainingMillis(acceptStartedNanos, authStartedNanos)
            if (remainingMillis <= 0) {
                return CandidateResult.Rejected(
                    SocketTimeoutException("screen stream candidate authentication timed out"),
                )
            }
            sock.soTimeout = remainingMillis
            val message = readMessage(input) {
                val beforeReadMillis = candidateRemainingMillis(
                    acceptStartedNanos,
                    authStartedNanos,
                )
                if (beforeReadMillis <= 0) {
                    throw SocketTimeoutException(
                        "screen stream candidate authentication timed out",
                    )
                }
                sock.soTimeout = beforeReadMillis
            } ?: return CandidateResult.Rejected(IOException("screen stream candidate EOF"))
            when (message.opcode) {
                OP_VIDEO_CONFIG -> {
                    pendingConfig = parseConfig(
                        sequence = frameCounter.get(),
                        header = message.header,
                        body = message.body,
                    )
                    candidateNalLengthSize = pendingConfig.nalLengthSize
                    recordDiagnostic(ScreenCandidateStage.PREAUTH_CONFIG)
                }
                OP_VIDEO_FRAME -> {
                    val sequence = frameCounter.get()
                    val payload = decryptVideoFrame(sequence, message.header, message.body)
                    frameCounter.incrementAndGet()
                    if (!promote(sock)) {
                        return CandidateResult.Rejected(
                            IOException("screen stream closed before promotion"),
                        )
                    }
                    recordDiagnostic(ScreenCandidateStage.AUTHENTICATED)
                    try {
                        sock.soTimeout = limits.activeReadTimeoutMillis
                        pendingConfig?.let(::dispatchConfig)
                        dispatchFrame(
                            sequence = sequence,
                            header = message.header,
                            wire = message.body,
                            payload = payload,
                            nalLengthSize = candidateNalLengthSize,
                            arrivalMonotonicNanos = message.arrivalMonotonicNanos,
                        )
                        runAuthenticated(input, candidateNalLengthSize)
                    } catch (error: Exception) {
                        if (!closed.get()) finish(error)
                    }
                    return CandidateResult.Promoted
                }
            }
        }
        return CandidateResult.Rejected(IOException("screen stream closed during authentication"))
    }

    private fun promote(candidate: Socket): Boolean {
        val bound = synchronized(stateLock) {
            if (closed.get() || candidateSocket !== candidate || socket != null) return false
            server
        }
        if (bound != null) {
            try {
                closeOwnedEndpoint(bound)
            } catch (failure: Exception) {
                throw IOException("screen stream listener retirement failed", failure)
            }
        }
        return synchronized(stateLock) {
            if (closed.get() || candidateSocket !== candidate || socket != null) {
                false
            } else {
                candidateSocket = null
                socket = candidate
                true
            }
        }
    }

    private fun runAuthenticated(input: InputStream, initialNalLengthSize: Int) {
        var failure: Throwable? = null
        var nalLengthSize = initialNalLengthSize
        try {
            while (!closed.get()) {
                val message = readMessage(input) ?: break
                when (message.opcode) {
                    OP_VIDEO_CONFIG -> {
                        val config = parseConfig(
                            sequence = frameCounter.get(),
                            header = message.header,
                            body = message.body,
                        )
                        nalLengthSize = config.nalLengthSize
                        dispatchConfig(config)
                    }
                    OP_VIDEO_FRAME -> {
                        val sequence = frameCounter.get()
                        val payload = decryptVideoFrame(sequence, message.header, message.body)
                        frameCounter.incrementAndGet()
                        dispatchFrame(
                            sequence,
                            message.header,
                            message.body,
                            payload,
                            nalLengthSize,
                            message.arrivalMonotonicNanos,
                        )
                    }
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (!closed.get()) finish(failure)
        }
    }

    private fun readMessage(
        input: InputStream,
        beforeRead: () -> Unit = {},
    ): WireMessage? {
        val header = readFully(input, HEADER_LEN, beforeRead) ?: return null
        val bodySize = readU32Le(header, 0)
        if (bodySize < 0 || bodySize > MAX_BODY) {
            throw IOException("invalid screen stream body size")
        }
        val body = readFully(input, bodySize, beforeRead) ?: return null
        val opcode = header[OPCODE_OFFSET].toInt() and 0xff
        return WireMessage(
            opcode = opcode,
            header = header,
            body = body,
            arrivalMonotonicNanos = if (opcode == OP_VIDEO_FRAME) monotonicNanos() else null,
        )
    }

    private fun parseConfig(sequence: Long, header: ByteArray, body: ByteArray): PendingConfig {
        val (codec, codecData) = ScreenCodec.detectConfig(body)
        val lengthOffset = if (codec == VideoCodec.H265) 21 else 4
        if (codecData.size <= lengthOffset) throw IOException("truncated video configuration")
        val nalLengthSize = (codecData[lengthOffset].toInt() and 3) + 1
        if (nalLengthSize == 3) throw IOException("reserved NAL length size")
        return PendingConfig(sequence, header, body, codec, codecData, nalLengthSize)
    }

    private fun dispatchConfig(config: PendingConfig) {
        logInfo(
            "video codec config codec=${config.codec} body=${config.wire.size} " +
                "data=${config.codecData.size}",
        )
        listener.onCodec(config.codec)
        listener.onPacket(config.sequence, config.header, config.wire, null)
        listener.onConfig(config.codecData)
    }

    private fun decryptVideoFrame(sequence: Long, header: ByteArray, body: ByteArray): ByteArray {
        if (body.size < ScreenCodec.TAG_SIZE) {
            throw IOException("screen video frame is shorter than its authentication tag")
        }
        return try {
            ScreenCodec.decryptFrame(key, sequence, header, body)
        } catch (error: Exception) {
            throw IOException("screen video frame authentication failed", error)
        }
    }

    private fun dispatchFrame(
        sequence: Long,
        header: ByteArray,
        wire: ByteArray,
        payload: ByteArray,
        nalLengthSize: Int,
        arrivalMonotonicNanos: Long?,
    ) {
        val timing = parseScreenFrameTiming(
            header,
            arrivalMonotonicNanos ?: monotonicNanos(),
            authenticatedFrameCounter = sequence,
        )
        if (firstFrameLogged.compareAndSet(false, true)) {
            logInfo(
                "video first authenticated frame sealed=${wire.size} plain=${payload.size}",
            )
        }
        listener.onPacket(sequence, header, wire, payload)
        listener.onFrame(
            ScreenCodec.lengthPrefixedToAnnexB(payload, nalLengthSize),
            timing,
        )
    }

    internal fun timingForFrame(header: ByteArray): ScreenFrameTiming =
        parseScreenFrameTiming(header, monotonicNanos())

    private fun readFully(
        input: InputStream,
        length: Int,
        beforeRead: () -> Unit,
    ): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            beforeRead()
            val read = input.read(output, offset, length - offset)
            if (read < 0) return null
            offset += read
        }
        return output
    }

    private fun isExpectedPeer(candidate: Socket): Boolean {
        val remote = (candidate.remoteSocketAddress as? InetSocketAddress)?.address ?: return false
        return remote.address.contentEquals(expectedPeerAddress.address)
    }

    private fun remainingMillis(startedNanos: Long, timeoutMillis: Int): Int {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())
        val elapsedNanos = (monotonicNanos() - startedNanos).coerceAtLeast(0L)
        val remainingNanos = timeoutNanos - elapsedNanos
        if (remainingNanos <= 0L) return 0
        return TimeUnit.NANOSECONDS.toMillis(remainingNanos)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun candidateRemainingMillis(
        acceptStartedNanos: Long,
        authStartedNanos: Long,
    ): Int = minOf(
        remainingMillis(acceptStartedNanos, limits.acceptDeadlineMillis),
        remainingMillis(authStartedNanos, limits.candidateAuthTimeoutMillis),
    )

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun recordDiagnostic(stage: ScreenCandidateStage) {
        if (!UltraDebugStatusMarkers.enabled && diagnosticObserver == null) return
        synchronized(diagnosticLock) {
            if (diagnosticTerminal) return
            when (stage) {
                ScreenCandidateStage.CANDIDATE_ACCEPTED -> acceptedCandidates++
                ScreenCandidateStage.WRONG_PEER -> wrongPeers++
                ScreenCandidateStage.EXACT_PEER -> exactPeers++
                ScreenCandidateStage.PREAUTH_CONFIG -> preAuthConfigs++
                ScreenCandidateStage.PREAUTH_TIMEOUT -> preAuthTimeouts++
                ScreenCandidateStage.CANDIDATE_SOCKET_ERROR,
                ScreenCandidateStage.PREAUTH_REJECTED -> preAuthFailures++
                ScreenCandidateStage.AUTHENTICATED -> authenticatedCandidates++
                else -> Unit
            }
            if (stage.isTerminal) diagnosticTerminal = true
            if (!stage.isTerminal) {
                if (nonTerminalDiagnosticEvents >= MAX_DIAGNOSTIC_STAGE_EVENTS) return
                nonTerminalDiagnosticEvents++
            }
            val diagnostic = ScreenCandidateDiagnostic(
                screenType = screenType,
                stage = stage,
                acceptedCandidates = acceptedCandidates.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
                wrongPeers = wrongPeers.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
                exactPeers = exactPeers.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
                preAuthConfigs = preAuthConfigs.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
                preAuthTimeouts = preAuthTimeouts.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
                preAuthFailures = preAuthFailures.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
                authenticatedCandidates = authenticatedCandidates.coerceAtMost(MAX_DIAGNOSTIC_COUNT),
            )
            UltraDebugStatusMarkers.emitScreenCandidate(::logInfo, diagnostic)
            diagnosticObserver?.let { observer -> runCatching { observer(diagnostic) } }
        }
    }

    private fun finish(
        cause: Throwable?,
        terminalStage: ScreenCandidateStage = ScreenCandidateStage.TERMINAL_STREAM_ENDED,
    ) {
        if (!closed.compareAndSet(false, true)) return
        recordDiagnostic(terminalStage)
        // Not under retirementAttemptLock: an owner close() holds that lock while it joins this
        // worker, so taking it here would stall that join for its whole deadline. Endpoint close
        // is idempotent and ownedEndpoints is guarded by stateLock.
        val endpointFailure = closeOwnedEndpoints(IdentityHashMap())
        val terminalCause = when {
            cause == null -> endpointFailure
            endpointFailure == null -> cause
            else -> cause.apply { addSuppressedBounded(endpointFailure) }
        }
        try {
            listener.onClosed(terminalCause)
        } catch (_: Exception) {
            // Listener failures do not erase the retained worker/endpoint retirement evidence.
        }
    }

    private fun closeOwnedEndpoints(
        attempted: IdentityHashMap<Closeable, Unit>,
    ): Exception? {
        val endpoints = synchronized(stateLock) { ownedEndpoints.keys.toList() }
        var failure: Exception? = null
        endpoints.forEach { endpoint ->
            if (attempted.put(endpoint, Unit) != null) return@forEach
            try {
                closeOwnedEndpoint(endpoint)
            } catch (problem: Exception) {
                failure = combineRetirementFailure(failure, problem)
            }
        }
        return failure
    }

    private fun closeOwnedEndpoint(endpoint: Closeable) {
        endpoint.close()
        synchronized(stateLock) {
            ownedEndpoints.remove(endpoint)
            if (server === endpoint) server = null
            if (candidateSocket === endpoint) candidateSocket = null
            if (socket === endpoint) socket = null
        }
    }

    private fun closeEndpointForWorker(endpoint: Closeable): Exception? = try {
        closeOwnedEndpoint(endpoint)
        null
    } catch (failure: Exception) {
        failure
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
                failure = combineRetirementFailure(
                    failure,
                    IOException("interrupted while retiring the screen stream worker", problem),
                )
                return failure
            }
        }
        if (worker.isAlive) {
            return combineRetirementFailure(
                failure,
                IOException("screen stream worker did not stop before the deadline"),
            )
        }
        if (mayAcknowledge) {
            synchronized(stateLock) {
                if (thread === worker) thread = null
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

    private data class WireMessage(
        val opcode: Int,
        val header: ByteArray,
        val body: ByteArray,
        val arrivalMonotonicNanos: Long?,
    )

    private data class PendingConfig(
        val sequence: Long,
        val header: ByteArray,
        val wire: ByteArray,
        val codec: VideoCodec,
        val codecData: ByteArray,
        val nalLengthSize: Int,
    )

    private sealed class CandidateResult {
        object Promoted : CandidateResult()
        data class Rejected(val cause: Throwable) : CandidateResult()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        const val DEFAULT_NAL_LENGTH_SIZE = 4
        const val DEFAULT_RETIREMENT_JOIN_TIMEOUT_MILLIS = 2_000L
        const val MAX_SUPPRESSED_RETIREMENT_FAILURES = 8
        const val MAX_DIAGNOSTIC_COUNT = 1_024
        const val MAX_DIAGNOSTIC_STAGE_EVENTS = 128
    }
}

/** Fixed vocabulary only: no socket address, port, wire identity, or media bytes enter diagnostics. */
internal enum class ScreenCandidateStage(val isTerminal: Boolean = false) {
    LISTENING,
    CANDIDATE_ACCEPTED,
    CANDIDATE_SOCKET_ERROR,
    WRONG_PEER,
    EXACT_PEER,
    PREAUTH_CONFIG,
    PREAUTH_TIMEOUT,
    PREAUTH_REJECTED,
    AUTHENTICATED,
    TERMINAL_BIND_ERROR(true),
    TERMINAL_ACCEPT_ERROR(true),
    TERMINAL_ATTEMPT_LIMIT(true),
    TERMINAL_DEADLINE(true),
    TERMINAL_STREAM_ENDED(true),
    TERMINAL_CLOSED(true),
}

internal data class ScreenCandidateDiagnostic(
    val screenType: Int,
    val stage: ScreenCandidateStage,
    val acceptedCandidates: Int,
    val wrongPeers: Int,
    val exactPeers: Int,
    val preAuthConfigs: Int,
    val preAuthTimeouts: Int,
    val preAuthFailures: Int,
    val authenticatedCandidates: Int,
)

internal data class ScreenStreamLimits(
    val maxCandidateAttempts: Int = 8,
    val acceptDeadlineMillis: Int = 15_000,
    val acceptPollMillis: Int = 250,
    val candidateAuthTimeoutMillis: Int = 5_000,
    val activeReadTimeoutMillis: Int = 300_000,
    val maxPreAuthMessages: Int = 16,
) {
    init {
        require(maxCandidateAttempts in 1..64)
        require(acceptDeadlineMillis > 0)
        require(acceptPollMillis > 0)
        require(candidateAuthTimeoutMillis > 0)
        require(activeReadTimeoutMillis > 0)
        require(maxPreAuthMessages in 1..64)
    }
}

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray {
        require(body.size >= TAG_SIZE) {
            "screen video frame is shorter than its authentication tag"
        }
        return AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)
    }

    /** The wire format is length-prefixed, even when its first length happens to be one. */
    fun lengthPrefixedToAnnexB(payload: ByteArray, lengthSize: Int = 4): ByteArray {
        val converted = MediaCodecSupport.toAnnexB(payload, lengthSize, allowAnnexB = false)
        if (converted.size == payload.size) {
            converted.copyInto(payload)
            return payload
        }
        return converted
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
