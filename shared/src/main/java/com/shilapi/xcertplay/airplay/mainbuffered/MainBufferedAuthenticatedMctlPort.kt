package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.ControlCipher
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/** Limits for one independently authenticated MediaDataControl connection. */
internal data class MainBufferedMctlTcpLimits(
    val maximumCandidates: Int = 4,
    val acceptDeadlineMillis: Long = 5_000,
    val firstPackageDeadlineMillis: Long = 5_000,
    val frameDeadlineMillis: Long = 30_000,
    val acceptPollMillis: Int = 250,
    val readPollMillis: Int = 250,
    val closeJoinMillis: Long = 1_000,
    val maximumFramesBeforeFirstPackage: Int = 64,
    val maximumFramesPerConnection: Int = 65_536,
    val maximumPackagesPerConnection: Int = 65_536,
) {
    init {
        require(maximumCandidates in 1..16)
        require(acceptDeadlineMillis in 1..300_000)
        require(firstPackageDeadlineMillis in 1..300_000)
        require(frameDeadlineMillis in 1..300_000)
        require(acceptPollMillis in 1..1_000)
        require(readPollMillis in 1..1_000)
        require(closeJoinMillis in 1..60_000)
        require(maximumFramesBeforeFirstPackage in 1..64)
        require(maximumFramesPerConnection in maximumFramesBeforeFirstPackage..65_536)
        require(maximumPackagesPerConnection in 1..65_536)
    }
}

internal enum class MainBufferedMctlTcpState {
    PREPARED, COMMITTED, ADMITTED, FAILED, CLOSING, CLOSED,
}

internal data class MainBufferedMctlTcpSnapshot(
    val state: MainBufferedMctlTcpState,
    val candidateCount: Int,
    val authenticatedPackages: Int,
    val serverClosed: Boolean,
    val workerAlive: Boolean,
    val failure: Throwable?,
)

internal class MainBufferedMctlTcpHooks(
    val workerStarter: (Thread) -> Unit = { it.start() },
    val beforeDispatchLivenessCheck: () -> Unit = {},
    /** Deterministic race seam immediately before the shared PairVerify execution lease. */
    val beforeExecutionLeaseAcquire: () -> Unit = {},
    val beforeDeferredCommit: () -> Unit = {},
)

/**
 * Exact PairVerify-generation side-effect barrier. The monitor is held only while claiming or
 * retiring permits, never during an external liveness callback or control handler: either may
 * enter the SETUP owner's lock. Revocation marks the lease invalid before awaiting already
 * claimed actions, so later actions cannot begin after the revocation boundary.
 */
internal class MainBufferedPairVerifyExecutionLease(val generation: Long) {
    private val lock = Object()
    @Volatile private var revoked = false
    private var activeActions = 0
    private val activeThreads = IdentityHashMap<Thread, Int>()

    init { require(generation > 0L) }

    fun isLive(requestedGeneration: Long, isSessionCurrent: (Long) -> Boolean): Boolean =
        requestedGeneration == generation && !revoked && isSessionCurrent(generation) && !revoked

    val isRevoked: Boolean get() = revoked

    fun <T> execute(
        requestedGeneration: Long,
        isSessionCurrent: (Long) -> Boolean,
        action: () -> T,
    ): T {
        check(isLive(requestedGeneration, isSessionCurrent) && claimAction()) {
            "mctl PairVerify execution lease is revoked or belongs to another generation"
        }
        try {
            check(!revoked && isSessionCurrent(generation) && !revoked) {
                "mctl PairVerify execution lease retired before control side effect"
            }
            return action()
        } finally {
            releaseAction()
        }
    }

    fun <T> executeIfCurrent(
        requestedGeneration: Long,
        isSessionCurrent: (Long) -> Boolean,
        action: () -> T,
    ): T? {
        if (!isLive(requestedGeneration, isSessionCurrent) || !claimAction()) return null
        try {
            if (revoked || !isSessionCurrent(generation) || revoked) return null
            return action()
        } finally {
            releaseAction()
        }
    }

    private fun claimAction(): Boolean = synchronized(lock) {
        if (revoked) return@synchronized false
        activeActions++
        val thread = Thread.currentThread()
        activeThreads[thread] = (activeThreads[thread] ?: 0) + 1
        true
    }

    private fun releaseAction() = synchronized(lock) {
        val thread = Thread.currentThread()
        val count = checkNotNull(activeThreads[thread])
        if (count == 1) activeThreads.remove(thread) else activeThreads[thread] = count - 1
        activeActions--
        lock.notifyAll()
    }

    /** Nonblocking first half of retirement, suitable before canceling a pending native start. */
    fun requestRevoke() = synchronized(lock) {
        revoked = true
        lock.notifyAll()
    }

    fun awaitQuiescent() = synchronized(lock) {
        // A control callback may itself request retirement. Do not wait for one's own action.
        val ownActions = activeThreads[Thread.currentThread()] ?: 0
        while (activeActions > ownActions) lock.wait()
    }

    fun revoke() {
        requestRevoke()
        awaitQuiescent()
    }

    /** Callback runs only after permit retirement and never under the lease monitor. */
    fun revokeAndRetire(retireSessionState: () -> Unit) {
        revoke()
        retireSessionState()
    }
}

/** Separate opt-in handler for cryptor rotation; ordinary control handlers cannot imply it. */
internal fun interface MainBufferedMctlCryptorRotationHandler {
    fun rotate(request: MainBufferedControlEnvelope): MainBufferedControlDispatchReply
}

/**
 * One-shot source of an `mctl` port for an exact, still-live PairVerify generation.
 *
 * [borrowVerifiedSharedSecret] must transfer a closeable scope containing the raw 32-byte X25519
 * PairVerify result for this generation, not an APAP `shk`, MFi certificate, or derived key.
 * It is called only during the first prepare attempt, and its scope is always returned in finally.
 * The preparer does not retain a PairVerify secret while awaiting the sender's SETUP. The
 * liveness callback is checked before publication and every admission and dispatch; a matching
 * IP address alone never authenticates a control message.
 */
internal class MainBufferedAuthenticatedMctlPortPreparer(
    private val sessionGeneration: Long,
    private val bindAddress: InetAddress,
    private val expectedPeer: InetAddress,
    private val borrowVerifiedSharedSecret: (Long) -> MainBufferedVerifiedSecretBorrow?,
    private val isSessionCurrent: (Long) -> Boolean,
    private val dispatcher: MainBufferedApTransportControlDispatcher,
    private val cryptorRotationHandler: MainBufferedMctlCryptorRotationHandler? = null,
    private val limits: MainBufferedMctlTcpLimits = MainBufferedMctlTcpLimits(),
    private val hooks: MainBufferedMctlTcpHooks = MainBufferedMctlTcpHooks(),
    private val playbackControlBinding: MainBufferedPcm16MctlBinding? = null,
) : MainBufferedMctlPortPreparer, Closeable {
    private val lock = Object()
    private val executionLease = playbackControlBinding?.executionLease
        ?: MainBufferedPairVerifyExecutionLease(sessionGeneration)
    private var used = false

    init {
        require(sessionGeneration > 0)
        requireUnicast(bindAddress, "local")
        requireUnicast(expectedPeer, "peer")
        require(playbackControlBinding == null ||
            (playbackControlBinding.dispatcher === dispatcher &&
                playbackControlBinding.executionLease.generation == sessionGeneration)
        ) { "mctl playback dispatcher must carry its exact PairVerify execution lease" }
    }

    internal fun isExactPlaybackControlFor(
        owner: MainBufferedPcm16PlaybackEpochOwner,
        generation: Long,
        localAddress: InetAddress,
        peer: InetAddress,
    ): Boolean {
        val binding = playbackControlBinding ?: return false
        return binding.isFor(owner) && binding.dispatcher === dispatcher &&
            binding.executionLease === executionLease && sessionGeneration == generation &&
            bindAddress == localAddress && expectedPeer == peer
    }

    /** Call before any independently stored PairVerify liveness state is marked retired. */
    fun revokePairVerifyGeneration() = executionLease.revoke()

    internal val isPairVerifyRevoked: Boolean get() = executionLease.isRevoked

    /** Preferred: revoke and change the authoritative session state in one execution boundary. */
    fun retirePairVerifyGeneration(retireSessionState: () -> Unit) =
        executionLease.revokeAndRetire(retireSessionState)

    override fun prepare(request: MainBufferedMctlPortRequest): MainBufferedPreparedMctlPort {
        synchronized(lock) {
            check(!used) { "mctl PairVerify secret is one-shot" }
            used = true
        }
        var secretBorrow: MainBufferedVerifiedSecretBorrow? = null
        var readKey: ByteArray? = null
        var writeKey: ByteArray? = null
        try {
            require(request.generation == sessionGeneration &&
                request.bindAddress == bindAddress && request.expectedPeer == expectedPeer
            ) { "mctl request does not match the verified session binding" }
            check(executionLease.isLive(sessionGeneration, isSessionCurrent)) {
                "mctl session generation retired"
            }
            secretBorrow = borrowVerifiedSharedSecret(sessionGeneration)
                ?: throw IllegalStateException("mctl verified PairVerify secret unavailable")
            require(checkNotNull(secretBorrow).generation == sessionGeneration) {
                "mctl secret borrow belongs to another PairVerify generation"
            }
            // `%llu` in the sender is unsigned decimal ASCII, even for the high-bit UInt64 range.
            val salt = ("DataStream-Salt" + java.lang.Long.toUnsignedString(
                request.encryptionSeedBits,
            )).toByteArray(Charsets.US_ASCII)
            // iPhone sends with Output; accessory receives with Output and replies with Input.
            checkNotNull(secretBorrow).withRawSecret { raw ->
                readKey = AirPlayCrypto.hkdfSha512(
                    raw, salt, OUTPUT_KEY.toByteArray(Charsets.US_ASCII), 32,
                )
                writeKey = AirPlayCrypto.hkdfSha512(
                    raw, salt, INPUT_KEY.toByteArray(Charsets.US_ASCII), 32,
                )
            }
            check(executionLease.isLive(sessionGeneration, isSessionCurrent)) {
                "mctl session generation retired"
            }
            return MainBufferedAuthenticatedMctlPort.prepare(
                bindAddress, expectedPeer, sessionGeneration,
                checkNotNull(readKey), checkNotNull(writeKey),
                isSessionCurrent, dispatcher, cryptorRotationHandler, limits, hooks,
                executionLease,
            )
        } finally {
            secretBorrow?.close()
            readKey?.fill(0)
            writeKey?.fill(0)
        }
    }

    override fun close() {
        executionLease.revoke()
        synchronized(lock) { used = true }
    }

    private companion object {
        const val OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val INPUT_KEY = "DataStream-Input-Encryption-Key"
    }
}

/** Bound, encrypted APTransport control ingress; no connection is admitted before its gate opens. */
internal class MainBufferedAuthenticatedMctlPort private constructor(
    private val server: ServerSocket,
    override val generation: Long,
    override val expectedPeer: InetAddress,
    private val cipher: ControlCipher,
    private val isSessionCurrent: (Long) -> Boolean,
    private val dispatcher: MainBufferedApTransportControlDispatcher,
    cryptorRotationHandler: MainBufferedMctlCryptorRotationHandler?,
    private val limits: MainBufferedMctlTcpLimits,
    private val hooks: MainBufferedMctlTcpHooks,
    private val executionLease: MainBufferedPairVerifyExecutionLease,
) : MainBufferedPreparedMctlPort {
    override val localAddress: InetSocketAddress = server.localSocketAddress as InetSocketAddress
    private val rotationDispatcher = cryptorRotationHandler?.let { handler ->
        MainBufferedApTransportControlDispatcher(
            MainBufferedControlHandler(handler::rotate),
        )
    }

    private val lock = Object()
    private var state = MainBufferedMctlTcpState.PREPARED
    private var gate: MainBufferedAdmissionGate? = null
    private var worker: Thread? = null
    private var socket: Socket? = null
    private var candidates = 0
    private var authenticatedPackages = 0
    private var inFlightDispatch = false
    private var failure: Throwable? = null
    private var terminalFailureListener: ((Throwable) -> Unit)? = null

    override fun commitDeferred() {
        hooks.beforeDeferredCommit()
        executionLease.execute(generation, isSessionCurrent) {
            synchronized(lock) {
                check(state == MainBufferedMctlTcpState.PREPARED) {
                    "mctl deferred commit requires a prepared session"
                }
                state = MainBufferedMctlTcpState.COMMITTED
                lock.notifyAll()
            }
        }
    }

    override fun activateWhen(gate: MainBufferedAdmissionGate) {
        executionLease.execute(generation, isSessionCurrent) {
            synchronized(lock) {
                check(state == MainBufferedMctlTcpState.COMMITTED && worker == null) {
                    "mctl can activate only once for the current committed session"
                }
                this.gate = gate
                val newWorker = Thread(::runAdmission, "main-buffered-mctl-g$generation")
                    .apply { isDaemon = true }
                worker = newWorker
                try {
                    hooks.workerStarter(newWorker)
                    check(newWorker.isAlive) { "mctl admission worker did not start" }
                } catch (problem: Throwable) {
                    state = MainBufferedMctlTcpState.FAILED
                    failure = problem
                    cipher.close()
                    try { server.close() } catch (closeFailure: IOException) {
                        problem.addSuppressed(closeFailure)
                    }
                    throw problem
                }
            }
        }
    }

    override fun awaitAdmissionReceipt(timeoutMillis: Long): MainBufferedMctlAdmissionReceipt? {
        require(timeoutMillis in 0..300_000)
        val exactGate = synchronized(lock) { gate } ?: return null
        if (!exactGate.awaitArrival(this, timeoutMillis)) return null
        val firstReceipt = synchronized(lock) {
            if (gate === exactGate && exactGate.isArrived(this) &&
                (state == MainBufferedMctlTcpState.COMMITTED ||
                    state == MainBufferedMctlTcpState.ADMITTED) && worker?.isAlive == true
            ) MainBufferedMctlAdmissionReceipt(
                generation, localAddress, expectedPeer, exactGate, workerAlive = true,
            ) else null
        }
        if (firstReceipt == null || !executionLease.isLive(generation, isSessionCurrent)) return null
        return synchronized(lock) {
            if (gate === firstReceipt.gate && exactGate.isArrived(this) &&
                (state == MainBufferedMctlTcpState.COMMITTED ||
                    state == MainBufferedMctlTcpState.ADMITTED) && worker?.isAlive == true
            ) firstReceipt else null
        }
    }

    override fun commitIfAdmissionCurrent(
        receipt: MainBufferedMctlAdmissionReceipt,
        markCommitted: () -> Unit,
    ): Boolean = executionLease.executeIfCurrent(generation, isSessionCurrent) {
        synchronized(lock) {
            if (receipt.generation != generation || receipt.localAddress != localAddress ||
                receipt.expectedPeer != expectedPeer || receipt.gate !== gate ||
                !receipt.workerAlive || !receipt.gate.isArrived(this) || failure != null ||
                (state != MainBufferedMctlTcpState.COMMITTED &&
                    state != MainBufferedMctlTcpState.ADMITTED) || worker?.isAlive != true
            ) return@synchronized false
            // Port failure and PairVerify retirement both share the relevant execution gate.
            markCommitted()
            true
        }
    } ?: false

    override fun onTerminalFailure(listener: (Throwable) -> Unit) = synchronized(lock) {
        check(terminalFailureListener == null && state == MainBufferedMctlTcpState.PREPARED) {
            "mctl terminal failure listener must be installed once before commit"
        }
        terminalFailureListener = listener
    }

    override fun rollback() = close()

    fun snapshot(): MainBufferedMctlTcpSnapshot = synchronized(lock) {
        MainBufferedMctlTcpSnapshot(
            state, candidates, authenticatedPackages, server.isClosed,
            worker?.isAlive == true, failure,
        )
    }

    override fun close() {
        executionLease.revoke()
        val ownedWorker: Thread?
        val ownedSocket: Socket?
        synchronized(lock) {
            if (state == MainBufferedMctlTcpState.CLOSED) return
            state = MainBufferedMctlTcpState.CLOSING
            gate?.close()
            terminalFailureListener = null
            ownedWorker = worker
            ownedSocket = socket
            lock.notifyAll()
        }
        var closeFailure: IOException? = null
        try { server.close() } catch (problem: IOException) { closeFailure = problem }
        try { ownedSocket?.close() } catch (problem: IOException) {
            closeFailure = combine(closeFailure, problem)
        }
        if (ownedWorker === Thread.currentThread() && ownedWorker.isAlive) {
            closeFailure = combine(closeFailure, IOException("mctl worker cannot join itself"))
        } else if (ownedWorker?.isAlive == true) {
            try { ownedWorker.join(limits.closeJoinMillis) } catch (problem: InterruptedException) {
                Thread.currentThread().interrupt()
                closeFailure = combine(closeFailure, IOException("mctl retirement interrupted", problem))
            }
            if (ownedWorker.isAlive) {
                closeFailure = combine(closeFailure, IOException("mctl worker remains alive"))
            }
        }
        cipher.close()
        synchronized(lock) {
            if (closeFailure == null && server.isClosed && ownedWorker?.isAlive != true &&
                ownedSocket?.isClosed != false
            ) state = MainBufferedMctlTcpState.CLOSED
            else if (closeFailure == null) closeFailure = IOException("mctl retirement pending")
            lock.notifyAll()
        }
        closeFailure?.let { throw it }
    }

    private fun runAdmission() {
        try {
            val openedAt = try { gate?.arriveAndAwaitOpeningTimeNanos(this) } catch (problem: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("mctl admission gate interrupted", problem)
            } ?: return
            if (Thread.currentThread().isInterrupted) {
                throw IOException("mctl admission worker was interrupted")
            }
            requireCurrentSession()
            synchronized(lock) {
                check(state == MainBufferedMctlTcpState.COMMITTED && !server.isClosed) {
                    "mctl worker retired before opening its shared gate"
                }
                lock.notifyAll()
            }
            val deadline = openedAt + millisToNanos(limits.acceptDeadlineMillis)
            while (active()) {
                requireCurrentSession()
                val remaining = remainingMillis(deadline)
                if (remaining == 0L) throw IOException("mctl accept deadline expired")
                server.soTimeout = minOf(remaining, limits.acceptPollMillis.toLong()).toInt()
                val candidate = try { server.accept() } catch (_: SocketTimeoutException) {
                    continue
                }
                val acceptedAt = System.nanoTime()
                var admitted = false
                try {
                    val count = synchronized(lock) { ++candidates }
                    requireCurrentSession()
                    if (candidate.inetAddress == expectedPeer && active()) {
                        synchronized(lock) {
                            check(state == MainBufferedMctlTcpState.COMMITTED)
                            socket = candidate
                            state = MainBufferedMctlTcpState.ADMITTED
                        }
                        admitted = true
                        server.close()
                        runAuthenticated(candidate, acceptedAt)
                        return
                    }
                    if (count >= limits.maximumCandidates) {
                        throw IOException("mctl candidate limit exhausted")
                    }
                } finally {
                    if (!admitted) candidate.close()
                }
            }
        } catch (problem: Throwable) {
            fail(problem)
            if (problem is Error) throw problem
        } finally {
            gate?.depart(this)
            synchronized(lock) { lock.notifyAll() }
        }
    }

    private fun runAuthenticated(candidate: Socket, acceptedAt: Long) {
        val firstDeadline = acceptedAt + millisToNanos(limits.firstPackageDeadlineMillis)
        val framer = MainBufferedApTransportControlFramer()
        var framerTerminal = false
        var frames = 0
        var packages = 0
        try {
            while (active()) {
                requireCurrentSession()
                if (packages == 0 && remainingMillis(firstDeadline) == 0L) {
                    throw IOException("mctl first authenticated control package deadline expired")
                }
                // After the first command, `mctl` may be idle for the whole drive. Bound a
                // *partial* frame from its first byte, not the gap between commands.
                val frame = readEncryptedFrame(
                    candidate,
                    firstPackageDeadline = if (packages == 0) firstDeadline else null,
                ) ?: break
                try {
                    frames++
                    if (frames > limits.maximumFramesPerConnection ||
                        (packages == 0 && frames > limits.maximumFramesBeforeFirstPackage)
                    ) throw IOException("mctl encrypted frame limit exhausted")
                    val plaintext = cipher.decryptFrame(frame)?.data
                        ?: throw IOException("mctl complete frame was not decrypted")
                    try {
                        val envelopes = framer.acceptAuthenticatedBytes(plaintext)
                        for (envelope in envelopes) {
                            hooks.beforeDispatchLivenessCheck()
                            requireCurrentSession()
                            if (packages == 0 && remainingMillis(firstDeadline) == 0L) {
                                throw IOException("mctl first authenticated package arrived late")
                            }
                            packages++
                            if (packages > limits.maximumPackagesPerConnection) {
                                throw IOException("mctl control package limit exhausted")
                            }
                            // Dispatch only a fully authenticated, bounded and parsed package.
                            val reply = dispatchWhileAdmitted(envelope)
                            try {
                                requireCurrentSession()
                                if (!active()) throw IOException("mctl port retired before reply")
                                val encrypted = cipher.encrypt(reply)
                                try {
                                    candidate.getOutputStream().write(encrypted)
                                    candidate.getOutputStream().flush()
                                } finally { encrypted.fill(0) }
                            } finally { reply.fill(0) }
                            synchronized(lock) { authenticatedPackages = packages }
                        }
                    } finally { plaintext.fill(0) }
                } finally { frame.fill(0) }
            }
            framerTerminal = true
            framer.finish()
            throw EOFException("mctl peer closed authenticated control stream")
        } finally {
            // finish() poisons and clears any partial plaintext APTransport package even if a
            // handler or socket write failed before normal EOF processing.
            if (!framerTerminal) runCatching { framer.finish() }
            candidate.close()
        }
    }

    /** Returns null only for a clean EOF before a frame header; partial frames always fail. */
    private fun readEncryptedFrame(
        candidate: Socket,
        firstPackageDeadline: Long?,
    ): ByteArray? {
        val header = ByteArray(2)
        if (!readExactly(candidate, header, 0, 1, firstPackageDeadline, cleanEof = true)) return null
        val now = System.nanoTime()
        val frameBudget = millisToNanos(limits.frameDeadlineMillis)
        val frameDeadline = now + frameBudget
        // nanoTime is modular; comparing absolute deadlines with minOf breaks near wrap.
        val deadline = if (firstPackageDeadline != null &&
            firstPackageDeadline - now < frameBudget
        ) firstPackageDeadline else frameDeadline
        readExactly(candidate, header, 1, 1, deadline, cleanEof = false)
        val length = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
        require(length <= 0x4000) { "mctl encrypted frame exceeds 0x4000 bytes" }
        val frame = ByteArray(2 + length + 16)
        try {
            header.copyInto(frame)
            readExactly(candidate, frame, 2, length + 16, deadline, cleanEof = false)
            return frame
        } catch (problem: Throwable) {
            frame.fill(0)
            throw problem
        }
    }

    private fun readExactly(
        candidate: Socket,
        target: ByteArray,
        offset: Int,
        count: Int,
        deadline: Long?,
        cleanEof: Boolean,
    ): Boolean {
        var received = 0
        while (received < count) {
            requireCurrentSession()
            if (!active()) throw IOException("mctl port retired during read")
            val remaining = deadline?.let(::remainingMillis)
            if (remaining == 0L) throw IOException("mctl encrypted frame read deadline expired")
            candidate.soTimeout = minOf(
                remaining ?: limits.readPollMillis.toLong(),
                limits.readPollMillis.toLong(),
            ).toInt()
            val n = try {
                candidate.getInputStream().read(target, offset + received, count - received)
            } catch (_: SocketTimeoutException) { continue }
            if (n < 0) {
                if (cleanEof && received == 0) return false
                throw EOFException("mctl encrypted frame was truncated")
            }
            received += n
        }
        return true
    }

    private fun requireCurrentSession() {
        if (!executionLease.isLive(generation, isSessionCurrent)) {
            throw IOException("mctl session generation retired")
        }
    }

    /** Admission and close linearize at [lock]; a previously admitted handler is in-flight. */
    private fun dispatchWhileAdmitted(envelope: MainBufferedControlEnvelope): ByteArray {
        synchronized(lock) {
            check(state == MainBufferedMctlTcpState.ADMITTED && !inFlightDispatch) {
                "mctl control dispatch was retired"
            }
            inFlightDispatch = true
        }
        try {
            hooks.beforeExecutionLeaseAcquire()
            val selectedDispatcher = if (envelope.command.tag ==
                MainBufferedControlTag.UPDATE_CRYPTOR_KEY
            ) rotationDispatcher ?: throw IOException(
                "mctl cryptor rotation has no dedicated capable handler",
            ) else dispatcher
            return executionLease.execute(generation, isSessionCurrent) {
                synchronized(lock) {
                    check(state == MainBufferedMctlTcpState.ADMITTED && inFlightDispatch) {
                        "mctl control dispatch was retired before its execution lease"
                    }
                }
                selectedDispatcher.dispatch(envelope).encoded()
            }
        } finally {
            synchronized(lock) {
                inFlightDispatch = false
                lock.notifyAll()
            }
        }
    }

    private fun active(): Boolean = synchronized(lock) {
        state == MainBufferedMctlTcpState.COMMITTED || state == MainBufferedMctlTcpState.ADMITTED
    }

    private fun fail(problem: Throwable) {
        executionLease.revoke()
        val ownedSocket: Socket?
        val listener: ((Throwable) -> Unit)?
        synchronized(lock) {
            if (state == MainBufferedMctlTcpState.CLOSING || state == MainBufferedMctlTcpState.CLOSED)
                return
            state = MainBufferedMctlTcpState.FAILED
            failure = problem
            ownedSocket = socket
            listener = terminalFailureListener
            lock.notifyAll()
        }
        try { server.close() } catch (closeFailure: IOException) { problem.addSuppressed(closeFailure) }
        try { ownedSocket?.close() } catch (closeFailure: IOException) {
            problem.addSuppressed(closeFailure)
        }
        cipher.close()
        try {
            listener?.invoke(problem)
        } catch (callbackFailure: Throwable) {
            if (callbackFailure !== problem) problem.addSuppressed(callbackFailure)
        }
    }

    companion object {
        fun prepare(
            bindAddress: InetAddress,
            expectedPeer: InetAddress,
            generation: Long,
            readKey: ByteArray,
            writeKey: ByteArray,
            isSessionCurrent: (Long) -> Boolean,
            dispatcher: MainBufferedApTransportControlDispatcher,
            cryptorRotationHandler: MainBufferedMctlCryptorRotationHandler? = null,
            limits: MainBufferedMctlTcpLimits = MainBufferedMctlTcpLimits(),
            hooks: MainBufferedMctlTcpHooks = MainBufferedMctlTcpHooks(),
            executionLease: MainBufferedPairVerifyExecutionLease =
                MainBufferedPairVerifyExecutionLease(generation),
        ): MainBufferedAuthenticatedMctlPort {
            requireUnicast(bindAddress, "local")
            requireUnicast(expectedPeer, "peer")
            require(generation > 0)
            require(executionLease.generation == generation)
            require(readKey.size == 32 && writeKey.size == 32)
            check(executionLease.isLive(generation, isSessionCurrent)) {
                "mctl session generation retired"
            }
            val cipher = ControlCipher(readKey, writeKey)
            var server: ServerSocket? = null
            try {
                val bound = ServerSocket().also { server = it }
                bound.reuseAddress = false
                bound.bind(InetSocketAddress(bindAddress, 0), limits.maximumCandidates)
                check(executionLease.isLive(generation, isSessionCurrent)) {
                    "mctl session generation retired"
                }
                return MainBufferedAuthenticatedMctlPort(
                    bound, generation, expectedPeer, cipher, isSessionCurrent,
                    dispatcher, cryptorRotationHandler, limits, hooks, executionLease,
                )
            } catch (problem: Throwable) {
                cipher.close()
                try { server?.close() } catch (closeFailure: IOException) {
                    problem.addSuppressed(closeFailure)
                }
                throw problem
            }
        }
    }
}

private fun requireUnicast(address: InetAddress, role: String) {
    require(!address.isAnyLocalAddress && !address.isMulticastAddress) {
        "mctl $role address must be an explicit unicast address"
    }
}

private fun millisToNanos(millis: Long): Long = TimeUnit.MILLISECONDS.toNanos(millis)

private fun remainingMillis(deadline: Long): Long {
    val remaining = deadline - System.nanoTime()
    if (remaining <= 0L) return 0L
    return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)
}

private fun combine(current: IOException?, next: IOException): IOException {
    if (current == null) return next
    current.addSuppressed(next)
    return current
}
