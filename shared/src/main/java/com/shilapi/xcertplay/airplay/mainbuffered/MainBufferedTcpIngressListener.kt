package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Bounded network admission limits; these do not constitute AirPlay feature readiness. */
internal data class MainBufferedTcpIngressLimits(
    val maximumCandidates: Int = 4,
    val acceptDeadlineMillis: Long = 5_000L,
    /** Absolute limit from socket acceptance to the first authenticated APAP packet. */
    val firstPacketDeadlineMillis: Long = 5_000L,
    val acceptPollMillis: Int = 250,
    val closeJoinMillis: Long = 1_000L,
) {
    init {
        require(maximumCandidates in 1..16)
        require(acceptDeadlineMillis in 1L..300_000L)
        require(firstPacketDeadlineMillis in 1L..300_000L)
        require(acceptPollMillis in 1..1_000)
        require(closeJoinMillis in 1L..60_000L)
    }
}

internal enum class MainBufferedTcpIngressState {
    PREPARED,
    COMMITTED,
    RUNNING,
    FAILED,
    CLOSING,
    CLOSED,
}

internal data class MainBufferedTcpIngressSnapshot(
    val state: MainBufferedTcpIngressState,
    val candidateCount: Int,
    val serverClosed: Boolean,
    val acceptWorkerAlive: Boolean,
    val pendingKeyZeroized: Boolean,
    val ingress: MainBufferedIngressSnapshot?,
    val failure: Throwable?,
)

/** Deterministic clock/scheduling seams for the detached admission tests; production uses defaults. */
internal class MainBufferedTcpIngressHooks(
    val monotonicNanos: () -> Long = System::nanoTime,
    val beforeBind: () -> Unit = {},
    val acceptWorkerStarter: (Thread) -> Unit = { it.start() },
    val beforeAdmissionGateWait: () -> Unit = {},
    val runtimeWorkerStarter: (Thread) -> Unit = { it.start() },
    val afterRuntimeStartedBeforeMonitor: () -> Unit = {},
)

/**
 * Detached, one-shot TCP admission seam for a *future* authorized type-103 SETUP response.
 *
 * [prepare] binds an explicit local interface to a system-assigned port. It does not accept or
 * decode anything until [commit] starts the accept worker. The caller must already have an exact
 * authorized session key, expected TCP peer, and ingress identity; neither the key source nor the
 * iOS SETUP response is inferred here. A peer-address match is only a routing check, not proof of
 * identity: the bounded [MainBufferedIngressRuntime] authenticates every APAP packet.
 *
 * The first matching candidate transfers its socket to one runtime. Rejected sockets are closed,
 * and the listener is closed to further admissions. [rollback] is for an unsent/uncommitted SETUP;
 * [close] retires committed ownership as well. Neither method enables `mainBuffered` negotiation.
 */
internal class MainBufferedTcpIngressListener private constructor(
    private val server: ServerSocket,
    private val expectedPeer: InetAddress,
    private val identity: MainBufferedIngressIdentity,
    private val pendingKey: ByteArray,
    private val packetConsumer: MainBufferedIngressPacketConsumer,
    private val ingressLimits: MainBufferedIngressLimits,
    private val tcpLimits: MainBufferedTcpIngressLimits,
    private val hooks: MainBufferedTcpIngressHooks,
) : Closeable {
    val localAddress: InetSocketAddress = server.localSocketAddress as InetSocketAddress

    private val lock = Object()
    private var state = MainBufferedTcpIngressState.PREPARED
    private var candidateCount = 0
    private var acceptWorker: Thread? = null
    private var admissionGate: MainBufferedAdmissionGate? = null
    private var runtime: MainBufferedIngressRuntime? = null
    private var firstPacketObserved = false
    private var lateFirstPacketObserved = false
    private var failure: Throwable? = null
    private var terminalListener: ((MainBufferedIngressTerminationReason, Throwable?) -> Unit)? = null
    private var terminalNotified = false
    private val firstPacketWindowNanos = TimeUnit.MILLISECONDS.toNanos(
        tcpLimits.firstPacketDeadlineMillis,
    )

    /** Starts one bounded admission window. No worker or decode exists before this transition. */
    fun commit() {
        val gate = MainBufferedAdmissionGate()
        try {
            commitDeferred()
            activateWhen(gate)
            gate.open()
        } catch (problem: Throwable) {
            gate.close()
            throw problem
        }
    }

    /** Install exactly once before commit; a terminal authenticated APAP stream ends its SETUP. */
    fun onTerminal(listener: (MainBufferedIngressTerminationReason, Throwable?) -> Unit) =
        synchronized(lock) {
            check(state == MainBufferedTcpIngressState.PREPARED && terminalListener == null) {
                "main-buffered APAP terminal listener must be installed once before commit"
            }
            terminalListener = listener
        }

    /** Marks the port committed without starting the accept worker. */
    fun commitDeferred() {
        synchronized(lock) {
            check(state == MainBufferedTcpIngressState.PREPARED) {
                "main-buffered TCP admission can be committed exactly once"
            }
            state = MainBufferedTcpIngressState.COMMITTED
            lock.notifyAll()
        }
    }

    /** Start a worker that cannot accept until the shared admission barrier opens. */
    fun activateWhen(gate: MainBufferedAdmissionGate) {
        synchronized(lock) {
            check(state == MainBufferedTcpIngressState.COMMITTED && acceptWorker == null) {
                "main-buffered TCP admission must be deferred and activated exactly once"
            }
            admissionGate = gate
            val worker = Thread(::acceptOneAuthorizedPeer, "main-buffered-tcp-g${identity.generation}")
                .apply { isDaemon = true }
            acceptWorker = worker
            try {
                hooks.acceptWorkerStarter(worker)
            } catch (problem: Throwable) {
                state = MainBufferedTcpIngressState.FAILED
                failure = problem
                pendingKey.fill(0)
                try {
                    server.close()
                } catch (closeFailure: IOException) {
                    problem.addSuppressed(closeFailure)
                }
                lock.notifyAll()
                throw problem
            }
            lock.notifyAll()
        }
    }

    /** Worker has reached the closed exact gate, but cannot yet accept any APAP peer. */
    fun awaitReadiness(gate: MainBufferedAdmissionGate, timeoutMillis: Long): Boolean =
        gate.awaitReady(this, timeoutMillis) && hasCurrentReadiness(gate)

    fun hasCurrentReadiness(gate: MainBufferedAdmissionGate): Boolean = synchronized(lock) {
        admissionGate === gate && state == MainBufferedTcpIngressState.COMMITTED &&
            acceptWorker?.isAlive == true && gate.isReady(this)
    }

    /** Abandons an uncommitted port and key copy; it cannot race a successful commit. */
    fun rollback() = closeInternal(requirePrepared = true)

    /**
     * Returns a session only after its first APAP packet has passed authentication. A matching TCP
     * peer alone is insufficient; EOF, bad tags, and admission timeouts return null.
     */
    fun awaitSession(timeoutMillis: Long): MainBufferedIngressSession? {
        require(timeoutMillis in 0L..300_000L)
        val deadline = deadlineNanos(timeoutMillis)
        synchronized(lock) {
            while (state == MainBufferedTcpIngressState.PREPARED ||
                state == MainBufferedTcpIngressState.COMMITTED ||
                (state == MainBufferedTcpIngressState.RUNNING && !firstPacketObserved)
            ) {
                val remaining = remainingMillis(deadline)
                if (remaining <= 0L) return null
                lock.wait(remaining)
            }
            return if (state == MainBufferedTcpIngressState.RUNNING && firstPacketObserved) {
                runtime?.takeIf { it.snapshot().terminationReason == null }
            } else {
                null
            }
        }
    }

    fun snapshot(): MainBufferedTcpIngressSnapshot = synchronized(lock) {
        MainBufferedTcpIngressSnapshot(
            state = state,
            candidateCount = candidateCount,
            serverClosed = server.isClosed,
            acceptWorkerAlive = acceptWorker?.isAlive == true,
            pendingKeyZeroized = pendingKey.all { it == 0.toByte() },
            ingress = runtime?.snapshot(),
            failure = failure,
        )
    }

    @Throws(IOException::class)
    override fun close() = closeInternal(requirePrepared = false)

    private fun closeInternal(requirePrepared: Boolean) {
        val worker: Thread?
        val ownedRuntime: MainBufferedIngressRuntime?
        synchronized(lock) {
            if (state == MainBufferedTcpIngressState.CLOSED) return
            if (requirePrepared) {
                check(state == MainBufferedTcpIngressState.PREPARED) {
                    "rollback is valid only before main-buffered TCP admission is committed"
                }
            }
            state = MainBufferedTcpIngressState.CLOSING
            admissionGate?.close()
            pendingKey.fill(0)
            worker = acceptWorker
            ownedRuntime = runtime
            lock.notifyAll()
        }

        var closeFailure: IOException? = null
        try {
            server.close()
        } catch (problem: IOException) {
            closeFailure = IOException("main-buffered TCP listener close failed", problem)
        }
        if (worker === Thread.currentThread() && worker.isAlive) {
            closeFailure = appendFailure(
                closeFailure,
                IOException("main-buffered TCP accept worker cannot retire itself"),
            )
        } else if (worker?.isAlive == true) {
            try {
                worker.join(tcpLimits.closeJoinMillis)
            } catch (problem: InterruptedException) {
                Thread.currentThread().interrupt()
                closeFailure = appendFailure(
                    closeFailure,
                    IOException("interrupted while retiring main-buffered TCP accept worker", problem),
                )
            }
            if (worker.isAlive) {
                closeFailure = appendFailure(
                    closeFailure,
                    IOException("main-buffered TCP accept worker remains alive"),
                )
            }
        }
        try {
            ownedRuntime?.close()
        } catch (problem: IOException) {
            closeFailure = appendFailure(closeFailure, problem)
        }

        synchronized(lock) {
            if (closeFailure == null && server.isClosed && worker?.isAlive != true &&
                (runtime == null || runtime?.snapshot()?.closeState ==
                    MainBufferedIngressCloseState.COMPLETE)
            ) {
                state = MainBufferedTcpIngressState.CLOSED
            } else if (closeFailure == null) {
                closeFailure = IOException("main-buffered TCP ownership retirement is pending")
            }
            lock.notifyAll()
        }
        closeFailure?.let { throw it }
    }

    private fun acceptOneAuthorizedPeer() {
        try {
            val openedAtNanos = try {
                hooks.beforeAdmissionGateWait()
                admissionGate?.arriveAndAwaitOpeningTimeNanos(this)
            } catch (problem: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("main-buffered TCP admission gate interrupted", problem)
            }
            if (openedAtNanos == null) return
            val deadline = openedAtNanos +
                TimeUnit.MILLISECONDS.toNanos(tcpLimits.acceptDeadlineMillis)
            while (true) {
                if (!isCommitted()) return
                val remaining = remainingMillis(deadline)
                if (remaining <= 0L) {
                    fail(IOException("main-buffered TCP accept deadline expired"))
                    return
                }
                server.soTimeout = minOf(remaining, tcpLimits.acceptPollMillis.toLong()).toInt()
                val candidate = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                var transferred = false
                try {
                    // The absolute authentication window begins at Java socket acceptance, not
                    // when runtime workers happen to start or the monitor next gets scheduled.
                    val acceptedAtNanos = hooks.monotonicNanos()
                    val count = synchronized(lock) { ++candidateCount }
                    if (!isCommitted()) return
                    if (candidate.inetAddress != expectedPeer) {
                        if (count >= tcpLimits.maximumCandidates) {
                            fail(IOException("main-buffered TCP candidate limit exhausted"))
                            return
                        }
                        continue
                    }
                    val created: MainBufferedIngressRuntime
                    synchronized(lock) {
                        if (state != MainBufferedTcpIngressState.COMMITTED) return
                        candidate.tcpNoDelay = true
                        val stream = SocketBlockingDuplexByteStream(candidate)
                        val guardedConsumer = if (packetConsumer is MainBufferedStampedPacketConsumer) {
                            val stamped = packetConsumer
                            object : MainBufferedStampedPacketConsumer {
                                override fun stampAuthenticatedPacket(
                                    identity: MainBufferedIngressIdentity,
                                    packet: ApapPacket,
                                ): Any {
                                    requireActivePacketDelivery()
                                    return stamped.stampAuthenticatedPacket(identity, packet)
                                }

                                override fun onStampedPacket(
                                    session: MainBufferedIngressSession,
                                    packet: ApapPacket,
                                    stamp: Any,
                                ) {
                                    requireActivePacketDelivery()
                                    stamped.onStampedPacket(session, packet, stamp)
                                }
                            }
                        } else {
                            MainBufferedIngressPacketConsumer { session, packet ->
                                requireActivePacketDelivery()
                                packetConsumer.onPacket(session, packet)
                            }
                        }
                        created = MainBufferedIngressRuntime(
                            identity = identity,
                            transport = stream,
                            key = pendingKey,
                            packetConsumer = guardedConsumer,
                            limits = ingressLimits,
                            workerStarter = hooks.runtimeWorkerStarter,
                            authenticatedPacketAdmission = MainBufferedAuthenticatedPacketAdmission {
                                authenticatedAtNanos ->
                                synchronized(lock) {
                                    if (state != MainBufferedTcpIngressState.RUNNING ||
                                        lateFirstPacketObserved
                                    ) {
                                        false
                                    } else if (firstPacketObserved) {
                                        true
                                    } else {
                                        val elapsed = authenticatedAtNanos - acceptedAtNanos
                                        if (elapsed < 0L || elapsed > firstPacketWindowNanos) {
                                            lateFirstPacketObserved = true
                                            lock.notifyAll()
                                            false
                                        } else {
                                            firstPacketObserved = true
                                            lock.notifyAll()
                                            true
                                        }
                                    }
                                }
                            },
                            authenticationClockNanos = hooks.monotonicNanos,
                            onTerminal = { reason, problem ->
                                notifyTerminal(reason, problem)
                            },
                        )
                        runtime = created
                        pendingKey.fill(0)
                        transferred = true
                        created.start()
                        state = MainBufferedTcpIngressState.RUNNING
                        lock.notifyAll()
                    }
                    server.close()
                    hooks.afterRuntimeStartedBeforeMonitor()
                    awaitFirstAuthenticatedPacket(created, acceptedAtNanos)
                    return
                } finally {
                    if (!transferred) candidate.close()
                }
            }
        } catch (problem: Throwable) {
            if (isActive()) {
                fail(problem)
                try {
                    synchronized(lock) { runtime }?.close()
                } catch (closeFailure: IOException) {
                    problem.addSuppressed(closeFailure)
                }
            }
            if (problem is Error) throw problem
        } finally {
            admissionGate?.depart(this)
            synchronized(lock) { lock.notifyAll() }
        }
    }

    private fun requireActivePacketDelivery() {
        synchronized(lock) {
            if (state != MainBufferedTcpIngressState.RUNNING) {
                throw IOException("main-buffered TCP admission is not active")
            }
        }
    }

    private fun isCommitted(): Boolean = synchronized(lock) {
        state == MainBufferedTcpIngressState.COMMITTED
    }

    private fun isActive(): Boolean = synchronized(lock) {
        state == MainBufferedTcpIngressState.COMMITTED ||
            state == MainBufferedTcpIngressState.RUNNING
    }

    private fun awaitFirstAuthenticatedPacket(
        created: MainBufferedIngressRuntime,
        acceptedAtNanos: Long,
    ) {
        while (true) {
            synchronized(lock) {
                if (state != MainBufferedTcpIngressState.RUNNING || firstPacketObserved) return
                if (!lateFirstPacketObserved) {
                    val remaining = firstPacketRemainingMillis(acceptedAtNanos)
                    if (remaining > 0L) lock.wait(minOf(remaining, 100L))
                }
            }
            if (synchronized(lock) { lateFirstPacketObserved }) {
                failBeforeFirstAuthenticatedPacket(
                    created,
                    IOException("main-buffered TCP first authenticated packet arrived late"),
                )
                return
            }
            val termination = created.snapshot().terminationReason
            if (termination != null) {
                failBeforeFirstAuthenticatedPacket(
                    created,
                    IOException("main-buffered TCP ingress terminated before authentication: $termination"),
                )
                return
            }
            if (firstPacketRemainingMillis(acceptedAtNanos) <= 0L) break
        }
        synchronized(lock) {
            if (state != MainBufferedTcpIngressState.RUNNING || firstPacketObserved) return
        }
        val termination = created.snapshot().terminationReason
        val cause = when {
            synchronized(lock) { lateFirstPacketObserved } ->
                IOException("main-buffered TCP first authenticated packet arrived late")
            termination != null ->
                IOException("main-buffered TCP ingress terminated before authentication: $termination")
            else -> IOException("main-buffered TCP first authenticated packet deadline expired")
        }
        failBeforeFirstAuthenticatedPacket(created, cause)
    }

    private fun firstPacketRemainingMillis(acceptedAtNanos: Long): Long {
        val elapsed = hooks.monotonicNanos() - acceptedAtNanos
        if (elapsed < 0L || elapsed >= firstPacketWindowNanos) return 0L
        return TimeUnit.NANOSECONDS.toMillis(firstPacketWindowNanos - elapsed).coerceAtLeast(1L)
    }

    private fun failBeforeFirstAuthenticatedPacket(
        created: MainBufferedIngressRuntime,
        cause: IOException,
    ) {
        val needsRetirement = created.withAuthenticationBoundary {
            synchronized(lock) {
                if (state != MainBufferedTcpIngressState.RUNNING || firstPacketObserved) {
                    false
                } else {
                    state = MainBufferedTcpIngressState.FAILED
                    failure = cause
                    pendingKey.fill(0)
                    lock.notifyAll()
                    true
                }
            }
        }
        if (!needsRetirement) return
        try {
            server.close()
        } catch (closeFailure: IOException) {
            cause.addSuppressed(closeFailure)
        }
        try {
            created.close()
        } catch (closeFailure: IOException) {
            // The exact runtime remains owned for an explicit, retryable listener.close().
            cause.addSuppressed(closeFailure)
        }
        notifyTerminal(MainBufferedIngressTerminationReason.ADMISSION_REJECTED, cause)
    }

    private fun fail(problem: Throwable) {
        var changed = false
        synchronized(lock) {
            if (state == MainBufferedTcpIngressState.FAILED ||
                state == MainBufferedTcpIngressState.CLOSING ||
                state == MainBufferedTcpIngressState.CLOSED
            ) return
            state = MainBufferedTcpIngressState.FAILED
            failure = problem
            pendingKey.fill(0)
            lock.notifyAll()
            changed = true
        }
        try {
            server.close()
        } catch (closeFailure: IOException) {
            problem.addSuppressed(closeFailure)
        }
        if (changed) notifyTerminal(MainBufferedIngressTerminationReason.ADMISSION_REJECTED, problem)
    }

    /** Runtime, accept, and first-packet races share exactly one paired terminal notification. */
    private fun notifyTerminal(
        reason: MainBufferedIngressTerminationReason,
        problem: Throwable?,
    ) {
        val callback = synchronized(lock) {
            if (terminalNotified) null else {
                terminalNotified = true
                terminalListener
            }
        }
        try {
            callback?.invoke(reason, problem)
        } catch (callbackFailure: Throwable) {
            if (problem != null && callbackFailure !== problem) {
                problem.addSuppressed(callbackFailure)
            }
            if (callbackFailure is Error) throw callbackFailure
        }
    }

    companion object {
        /** Bind a fresh ephemeral port; no SETUP publication or feature offer occurs here. */
        fun prepare(
            bindAddress: InetAddress,
            expectedPeer: InetAddress,
            identity: MainBufferedIngressIdentity,
            sessionKey: ByteArray,
            packetConsumer: MainBufferedIngressPacketConsumer,
            ingressLimits: MainBufferedIngressLimits = MainBufferedIngressLimits(),
            tcpLimits: MainBufferedTcpIngressLimits = MainBufferedTcpIngressLimits(),
            hooks: MainBufferedTcpIngressHooks = MainBufferedTcpIngressHooks(),
        ): MainBufferedTcpIngressListener {
            require(!bindAddress.isAnyLocalAddress && !bindAddress.isMulticastAddress) {
                "main-buffered TCP requires an explicit local unicast address"
            }
            require(!expectedPeer.isAnyLocalAddress && !expectedPeer.isMulticastAddress) {
                "main-buffered TCP requires an explicit peer unicast address"
            }
            require(sessionKey.size == EncryptedApapPacketCodec.KEY_BYTES) {
                "APAP session key must be ${EncryptedApapPacketCodec.KEY_BYTES} bytes"
            }
            val ownedKey = sessionKey.copyOf()
            var server: ServerSocket? = null
            try {
                val bound = ServerSocket().also { server = it }
                bound.reuseAddress = false
                hooks.beforeBind()
                bound.bind(InetSocketAddress(bindAddress, 0), tcpLimits.maximumCandidates)
                return MainBufferedTcpIngressListener(
                    bound,
                    expectedPeer,
                    identity,
                    ownedKey,
                    packetConsumer,
                    ingressLimits,
                    tcpLimits,
                    hooks,
                )
            } catch (problem: Throwable) {
                ownedKey.fill(0)
                try {
                    server?.close()
                } catch (closeFailure: IOException) {
                    problem.addSuppressed(closeFailure)
                }
                throw problem
            }
        }
    }
}

/** Exactly one accepted socket, transferred to MainBufferedIngressRuntime on successful admission. */
private class SocketBlockingDuplexByteStream(private val socket: Socket) : BlockingDuplexByteStream {
    private val sendLock = Any()

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            socket.getOutputStream().write(data)
            socket.getOutputStream().flush()
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0)
        require(timeoutMillis in 1L..Int.MAX_VALUE.toLong())
        socket.soTimeout = timeoutMillis.toInt()
        val buffer = ByteArray(maxBytes)
        return try {
            val received = try {
                socket.getInputStream().read(buffer)
            } catch (_: SocketTimeoutException) {
                return null
            }
            if (received < 0) ByteArray(0) else buffer.copyOf(received)
        } finally {
            buffer.fill(0)
        }
    }

    override fun close() = socket.close()
}

private fun deadlineNanos(timeoutMillis: Long): Long {
    // Both callers bound timeoutMillis, so signed subtraction remains valid even if nanoTime wraps.
    return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
}

private fun remainingMillis(deadlineNanos: Long): Long {
    val nanos = deadlineNanos - System.nanoTime()
    if (nanos <= 0L) return 0L
    return TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1L)
}

private fun appendFailure(current: IOException?, next: IOException): IOException {
    if (current == null) return next
    current.addSuppressed(next)
    return current
}
