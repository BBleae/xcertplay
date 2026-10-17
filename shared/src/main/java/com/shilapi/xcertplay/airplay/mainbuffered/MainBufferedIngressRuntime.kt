package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opaque identity for one detached main-buffered ingress generation.
 *
 * Equality is deliberately referential. [generation] is only a diagnostic correlation value and
 * is never used as an authorization token. Claiming is permanently one-shot: closing a runtime
 * never makes the same identity valid for a later generation.
 */
internal class MainBufferedIngressIdentity private constructor(
    val generation: Long,
    @Suppress("unused") private val nonce: UUID,
) {
    private val claimed = AtomicBoolean(false)

    init {
        require(generation > 0L) { "main-buffered generation must be positive" }
    }

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)

    override fun toString(): String =
        "MainBufferedIngressIdentity(generation=$generation, identity=<redacted>)"

    companion object {
        fun create(generation: Long): MainBufferedIngressIdentity =
            MainBufferedIngressIdentity(generation, UUID.randomUUID())
    }
}

/** Runtime limits independent of AirPlay feature negotiation or SETUP. */
internal data class MainBufferedIngressLimits(
    /** Inclusive BufferedAPAP frame size, including the four-byte big-endian prefix. */
    val maximumFrameBytes: Int = BufferedApapTcpCodec.DEFAULT_MAX_FRAME_BYTES,
    val receiveChunkBytes: Int = 64 * 1024,
    val receiveTimeoutMillis: Long = 250L,
    val maximumQueuedPackets: Int = 32,
    val maximumQueuedBytes: Int = 8 * 1024 * 1024,
    val closeJoinMillis: Long = 1_000L,
    val packetLimits: ApapCodecLimits = ApapCodecLimits(
        maxPacketBytes = BufferedApapTcpCodec.DEFAULT_MAX_FRAME_BYTES -
            BufferedApapTcpCodec.PREFIX_BYTES - EncryptedApapPacketCodec.OVERHEAD_BYTES,
    ),
) {
    init {
        val minimumFrameBytes = BufferedApapTcpCodec.PREFIX_BYTES +
            EncryptedApapPacketCodec.MIN_ENCRYPTED_BYTES
        require(maximumFrameBytes in minimumFrameBytes..HARD_MAXIMUM_FRAME_BYTES) {
            "maximumFrameBytes must be in $minimumFrameBytes..$HARD_MAXIMUM_FRAME_BYTES"
        }
        require(receiveChunkBytes in 1..minOf(maximumFrameBytes, HARD_MAXIMUM_CHUNK_BYTES)) {
            "receiveChunkBytes is outside the supported bounds"
        }
        require(receiveTimeoutMillis in 1L..HARD_MAXIMUM_WAIT_MILLIS) {
            "receiveTimeoutMillis is outside the supported bounds"
        }
        require(maximumQueuedPackets in 1..HARD_MAXIMUM_QUEUED_PACKETS) {
            "maximumQueuedPackets is outside the supported bounds"
        }
        require(maximumQueuedBytes in maximumFrameBytes..HARD_MAXIMUM_QUEUED_BYTES) {
            "maximumQueuedBytes must hold at least one maximum-sized frame"
        }
        require(closeJoinMillis in 1L..HARD_MAXIMUM_WAIT_MILLIS) {
            "closeJoinMillis is outside the supported bounds"
        }
        val maximumPlaintextBytes = maximumFrameBytes.toLong() -
            BufferedApapTcpCodec.PREFIX_BYTES - EncryptedApapPacketCodec.OVERHEAD_BYTES
        require(packetLimits.maxPacketBytes.toLong() <= maximumPlaintextBytes) {
            "packetLimits.maxPacketBytes exceeds the encrypted frame budget"
        }
    }

    private companion object {
        const val HARD_MAXIMUM_FRAME_BYTES = 16 * 1024 * 1024 +
            BufferedApapTcpCodec.PREFIX_BYTES + EncryptedApapPacketCodec.OVERHEAD_BYTES
        const val HARD_MAXIMUM_CHUNK_BYTES = 1024 * 1024
        const val HARD_MAXIMUM_QUEUED_PACKETS = 1_024
        const val HARD_MAXIMUM_QUEUED_BYTES = 64 * 1024 * 1024
        const val HARD_MAXIMUM_WAIT_MILLIS = 5 * 60 * 1_000L
    }
}

internal enum class MainBufferedIngressState {
    NEW,
    RUNNING,
    CLEAN_EOF,
    FAILED,
    CLOSING,
    CLOSED,
}

internal enum class MainBufferedIngressTerminationReason {
    CLEAN_EOF,
    LOCAL_CLOSE,
    AUTHENTICATION_FAILURE,
    NONCE_SEQUENCE_FAILURE,
    NONCE_EXHAUSTED,
    PROTOCOL_FAILURE,
    TRANSPORT_FAILURE,
    CALLBACK_FAILURE,
    ADMISSION_REJECTED,
    START_FAILURE,
}

/** A logical terminal state does not prove that the owned transport and workers retired. */
internal enum class MainBufferedIngressCloseState {
    NOT_REQUESTED,
    PENDING,
    FAILED,
    COMPLETE,
}

internal data class MainBufferedIngressSnapshot(
    val generation: Long,
    val state: MainBufferedIngressState,
    val terminationReason: MainBufferedIngressTerminationReason?,
    val closeState: MainBufferedIngressCloseState,
    val queuedPackets: Int,
    val queuedBytes: Int,
    val inputCompletionPending: Boolean,
    val ingressWorkerAlive: Boolean,
    val deliveryWorkerAlive: Boolean,
    /** True only when the runtime's private reader key copy is observably all zeroes. */
    val keyZeroized: Boolean,
)

internal class MainBufferedIngressCloseException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Restricted session view passed to arbitrary packet callbacks. */
internal interface MainBufferedIngressSession : Closeable {
    val identity: MainBufferedIngressIdentity

    fun snapshot(): MainBufferedIngressSnapshot

    fun awaitTerminal(timeoutMillis: Long): Boolean

    fun awaitClosed(timeoutMillis: Long): Boolean
}

/** Callback delivery is serialized on one bounded-runtime worker and may re-enter [session]. */
internal fun interface MainBufferedIngressPacketConsumer {
    fun onPacket(session: MainBufferedIngressSession, packet: ApapPacket)
}

/**
 * Optional exact-packet playback epoch contract. The stamp is made only after APAP decryption
 * and admission, before its bounded delivery queue. A later control-plane flush can advance its
 * own epoch while older packets remain in that queue; the eventual callback must reject stamps
 * from the retired epoch before sending any PCM to the output.
 *
 * [stampAuthenticatedPacket] is deliberately not a readiness proof or a replacement for the
 * independently authenticated `mctl` channel. Implementations must not block on delivery here.
 */
internal interface MainBufferedStampedPacketConsumer : MainBufferedIngressPacketConsumer {
    fun stampAuthenticatedPacket(
        identity: MainBufferedIngressIdentity,
        packet: ApapPacket,
    ): Any

    fun onStampedPacket(
        session: MainBufferedIngressSession,
        packet: ApapPacket,
        stamp: Any,
    )

    override fun onPacket(session: MainBufferedIngressSession, packet: ApapPacket) {
        throw IllegalStateException("stamped APAP consumer requires an authenticated packet stamp")
    }
}

/**
 * Called on the ingress worker after exact APAP authentication/decoding and before queueing.
 * Only the monotonic authentication-completion time is exposed, never the mutable packet bytes.
 * Returning false rejects the packet before any business callback can observe it.
 */
internal fun interface MainBufferedAuthenticatedPacketAdmission {
    fun admit(authenticatedAtNanos: Long): Boolean
}

private class MainBufferedAdmissionRejectedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Detached receiver for an already-established main-buffered byte stream.
 *
 * The runtime owns exactly [transport], two daemon workers, and the private key copy held by its
 * [EncryptedApapReader]. It does not open a listener, create a SETUP response, register an AirPlay
 * handler, alter feature negotiation, or provide readiness evidence. The caller must supply both
 * an already-authorized exact transport and an exact 32-byte key.
 *
 * Wire processing is intentionally layered: incremental BufferedAPAP TCP framing, strict exact
 * frame decoding, authenticated APAP decryption with monotonic nonces, then [ApapPacketCodec].
 * A zero-length receive is clean EOF only at a frame boundary. EOF inside a prefix or body is a
 * terminal protocol failure. Clean EOF and successful consumption of UInt64.MAX_VALUE are exposed
 * only after every previously admitted packet callback completed.
 *
 * [close] is success-only. It returns normally only after the exact transport closed, both workers
 * exited, the reader key was zeroized, and the transport claim was released. A callback cannot
 * prove its own worker retirement, so reentrant self-close schedules a separate retirement attempt
 * and throws [MainBufferedIngressCloseException] instead of reporting false success.
 */
internal class MainBufferedIngressRuntime(
    override val identity: MainBufferedIngressIdentity,
    transport: BlockingDuplexByteStream,
    key: ByteArray,
    private val packetConsumer: MainBufferedIngressPacketConsumer,
    private val limits: MainBufferedIngressLimits = MainBufferedIngressLimits(),
    initialNonceCounter: Long = 0L,
    cryptor: ApapAeadCryptor = BouncyCastleApapAeadCryptor,
    private val workerFactory: (String, () -> Unit) -> Thread = { name, action ->
        Thread(action, name)
    },
    private val workerStarter: (Thread) -> Unit = { it.start() },
    private val authenticatedPacketAdmission: MainBufferedAuthenticatedPacketAdmission =
        MainBufferedAuthenticatedPacketAdmission { true },
    private val authenticationClockNanos: () -> Long = System::nanoTime,
    private val onTerminal: ((MainBufferedIngressTerminationReason, Throwable?) -> Unit)? = null,
) : MainBufferedIngressSession {
    private val lifecycleLock = Object()
    private val closeAttemptLock = Any()
    private val authenticationBoundaryLock = Any()
    private val queueLock = Object()
    private val stopRequested = AtomicBoolean(false)
    private val queue = ArrayDeque<QueuedPacket>()

    private var state = MainBufferedIngressState.NEW
    private var terminationReason: MainBufferedIngressTerminationReason? = null
    private var terminalFailure: Throwable? = null
    private var closeState = MainBufferedIngressCloseState.NOT_REQUESTED
    private var queuedBytes = 0
    private var inputCompletion: InputCompletion? = null

    private var transportOwner: BlockingDuplexByteStream? = null
    private var transportCloseSucceeded = false
    private var readerOwner: EncryptedApapReader? = null
    private var keyRetired = false
    private var ingressWorker: Thread? = null
    private var deliveryWorker: Thread? = null
    private var deferredCloseWorker: Thread? = null

    init {
        require(key.size == EncryptedApapPacketCodec.KEY_BYTES) {
            "APAP key must be ${EncryptedApapPacketCodec.KEY_BYTES} bytes"
        }
        require(identity.claim()) {
            "main-buffered session identity is already bound to another runtime"
        }
        require(MainBufferedTransportClaims.claim(transport, identity)) {
            "main-buffered transport is already owned by another runtime"
        }
        try {
            readerOwner = EncryptedApapReader(
                key = key,
                initialNonceCounter = initialNonceCounter,
                cryptor = cryptor,
                maxEncryptedBytes = limits.maximumFrameBytes - BufferedApapTcpCodec.PREFIX_BYTES,
            )
            transportOwner = transport
        } catch (failure: Throwable) {
            MainBufferedTransportClaims.release(transport, identity)
            throw failure
        }
    }

    /** Starts exactly one ingress generation. Construction alone performs no I/O. */
    fun start() {
        // The worker references, RUNNING publication, and both Thread.start() calls are one
        // transaction with close. close() must never retire a still-NEW Thread that this method
        // could subsequently start from a saved local reference.
        synchronized(closeAttemptLock) {
            var transactionClaimed = false
            try {
                val workers = synchronized(lifecycleLock) {
                    check(state == MainBufferedIngressState.NEW) {
                        "main-buffered ingress can be started exactly once"
                    }
                    transactionClaimed = true
                    val delivery = workerFactory(
                        "main-buffered-delivery-g${identity.generation}",
                        ::runDelivery,
                    ).apply { isDaemon = true }
                    val ingress = workerFactory(
                        "main-buffered-ingress-g${identity.generation}",
                        ::runIngress,
                    ).apply { isDaemon = true }
                    require(delivery !== ingress) {
                        "main-buffered worker factory returned the same Thread twice"
                    }
                    require(delivery.state == Thread.State.NEW && ingress.state == Thread.State.NEW) {
                        "main-buffered worker factory must return unstarted Threads"
                    }
                    deliveryWorker = delivery
                    ingressWorker = ingress
                    state = MainBufferedIngressState.RUNNING
                    lifecycleLock.notifyAll()
                    delivery to ingress
                }
                workerStarter(workers.first)
                workerStarter(workers.second)
            } catch (failure: Throwable) {
                if (transactionClaimed) {
                    terminate(
                        MainBufferedIngressTerminationReason.START_FAILURE,
                        failure,
                        discardQueuedPackets = true,
                    )
                }
                throw failure
            }
        }
    }

    override fun snapshot(): MainBufferedIngressSnapshot {
        val queueSnapshot = synchronized(queueLock) {
            Triple(queue.size, queuedBytes, inputCompletion != null)
        }
        return synchronized(lifecycleLock) {
            val keyIsZeroized = keyRetired || readerOwner?.let {
                it.isTerminal || it.isClosed
            } == true
            MainBufferedIngressSnapshot(
                generation = identity.generation,
                state = state,
                terminationReason = terminationReason,
                closeState = closeState,
                queuedPackets = queueSnapshot.first,
                queuedBytes = queueSnapshot.second,
                inputCompletionPending = queueSnapshot.third,
                ingressWorkerAlive = ingressWorker?.isAlive == true,
                deliveryWorkerAlive = deliveryWorker?.isAlive == true,
                keyZeroized = keyIsZeroized,
            )
        }
    }

    override fun awaitTerminal(timeoutMillis: Long): Boolean = waitFor(timeoutMillis) {
        terminationReason != null
    }

    override fun awaitClosed(timeoutMillis: Long): Boolean = waitFor(timeoutMillis) {
        state == MainBufferedIngressState.CLOSED &&
            closeState == MainBufferedIngressCloseState.COMPLETE
    }

    /**
     * Serializes an external admission timeout with any exact frame currently being authenticated.
     * The action must not close this runtime or wait for its workers while holding this boundary.
     */
    internal fun <T> withAuthenticationBoundary(action: () -> T): T =
        synchronized(authenticationBoundaryLock) { action() }

    @Throws(IOException::class)
    override fun close() {
        requestLocalCloseIfNeeded()
        requestStop(discardQueuedPackets = true)

        var failure: IOException? = null
        var completed = false
        var pendingWorkerRetirement = false
        synchronized(closeAttemptLock) {
            if (synchronized(lifecycleLock) {
                    state == MainBufferedIngressState.CLOSED &&
                        closeState == MainBufferedIngressCloseState.COMPLETE
                }
            ) {
                return
            }
            synchronized(lifecycleLock) {
                closeState = MainBufferedIngressCloseState.PENDING
                lifecycleLock.notifyAll()
            }

            val ownedTransport = synchronized(lifecycleLock) { transportOwner }
            if (ownedTransport != null && !synchronized(lifecycleLock) {
                    transportCloseSucceeded
                }
            ) {
                try {
                    ownedTransport.close()
                    synchronized(lifecycleLock) { transportCloseSucceeded = true }
                } catch (problem: Throwable) {
                    if (problem is Error) {
                        recordFatalCloseFailure(problem)
                        throw problem
                    }
                    failure = appendCloseFailure(
                        failure,
                        "main-buffered transport close failed",
                        problem,
                    )
                }
            }

            val caller = Thread.currentThread()
            val workers = synchronized(lifecycleLock) {
                listOfNotNull(ingressWorker, deliveryWorker)
            }
            for (worker in workers) {
                if (worker !== caller && worker.isAlive) {
                    try {
                        worker.interrupt()
                    } catch (problem: Throwable) {
                        if (problem is Error) {
                            recordFatalCloseFailure(problem)
                            throw problem
                        }
                        failure = appendCloseFailure(
                            failure,
                            "unable to interrupt ${worker.name}",
                            problem,
                        )
                    }
                }
            }

            val deadlineNanos = saturatingDeadline(limits.closeJoinMillis)
            for (worker in workers) {
                if (worker === caller && worker.isAlive) {
                    pendingWorkerRetirement = true
                    failure = appendCloseFailure(
                        failure,
                        "main-buffered worker ${worker.name} cannot prove its own retirement",
                    )
                    continue
                }
                if (worker.isAlive) {
                    try {
                        val remainingMillis = remainingMillis(deadlineNanos)
                        if (remainingMillis > 0L) worker.join(remainingMillis)
                    } catch (problem: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failure = appendCloseFailure(
                            failure,
                            "interrupted while retiring ${worker.name}",
                            problem,
                        )
                    }
                }
                if (worker.isAlive) {
                    pendingWorkerRetirement = true
                    failure = appendCloseFailure(
                        failure,
                        "main-buffered worker ${worker.name} did not stop before the deadline",
                    )
                }
            }

            var ingressRetired = false
            var deliveryRetired = false
            synchronized(lifecycleLock) {
                ingressWorker?.let { worker ->
                    if (!worker.isAlive) ingressWorker = null
                }
                deliveryWorker?.let { worker ->
                    if (!worker.isAlive) deliveryWorker = null
                }
                ingressRetired = ingressWorker == null
                deliveryRetired = deliveryWorker == null
            }

            if (ingressRetired) {
                val reader = synchronized(lifecycleLock) { readerOwner }
                if (reader != null) {
                    reader.close()
                    synchronized(lifecycleLock) {
                        if (readerOwner === reader) readerOwner = null
                        keyRetired = true
                    }
                }
            }

            val transportClosed = synchronized(lifecycleLock) { transportCloseSucceeded }
            val readerRetired = synchronized(lifecycleLock) { readerOwner == null && keyRetired }
            if (transportClosed && ingressRetired && deliveryRetired && readerRetired) {
                clearQueue()
                val exactTransport = synchronized(lifecycleLock) { transportOwner }
                if (exactTransport == null ||
                    !MainBufferedTransportClaims.release(exactTransport, identity)
                ) {
                    failure = appendCloseFailure(
                        failure,
                        "main-buffered transport ownership claim could not be released",
                    )
                } else {
                    synchronized(lifecycleLock) {
                        if (transportOwner === exactTransport) transportOwner = null
                        closeState = MainBufferedIngressCloseState.COMPLETE
                        state = MainBufferedIngressState.CLOSED
                        lifecycleLock.notifyAll()
                    }
                    completed = true
                }
            }

            if (!completed) {
                synchronized(lifecycleLock) {
                    closeState = if (failure == null) {
                        MainBufferedIngressCloseState.PENDING
                    } else {
                        MainBufferedIngressCloseState.FAILED
                    }
                    lifecycleLock.notifyAll()
                }
            }
        }

        if (!completed) {
            if (pendingWorkerRetirement) scheduleDeferredClose()
            throw failure ?: MainBufferedIngressCloseException(
                "main-buffered ingress retirement is pending",
            )
        }
    }

    private fun runIngress() {
        val accumulator = IncrementalBufferedApapFramer(limits.maximumFrameBytes)
        try {
            while (!stopRequested.get()) {
                val transport = synchronized(lifecycleLock) { transportOwner }
                    ?: return
                val chunk = transport.recv(limits.receiveChunkBytes, limits.receiveTimeoutMillis)
                    ?: continue
                if (chunk.isEmpty()) {
                    accumulator.requireFrameBoundaryAtEof()
                    markInputCompletion(MainBufferedIngressTerminationReason.CLEAN_EOF, null)
                    return
                }
                if (chunk.size > limits.receiveChunkBytes) {
                    chunk.fill(0)
                    throw ApapProtocolException(
                        "main-buffered transport returned ${chunk.size} bytes for a " +
                            "${limits.receiveChunkBytes}-byte receive",
                    )
                }
                val shouldContinue = try {
                    accumulator.feed(chunk, ::acceptExactFrame)
                } finally {
                    chunk.fill(0)
                }
                if (!shouldContinue) return
            }
        } catch (problem: InterruptedException) {
            if (!stopRequested.get()) {
                terminate(
                    MainBufferedIngressTerminationReason.TRANSPORT_FAILURE,
                    problem,
                    discardQueuedPackets = true,
                )
            }
        } catch (problem: Throwable) {
            if (!stopRequested.get()) {
                terminate(
                    classifyIngressFailure(problem),
                    problem,
                    discardQueuedPackets = true,
                )
            }
            if (problem is Error) throw problem
        } finally {
            accumulator.clear()
            workerExited()
        }
    }

    /** Returns false when consuming the final UInt64 nonce intentionally stops ingress. */
    private fun acceptExactFrame(frame: ByteArray): Boolean {
        var encrypted: ByteArray? = null
        try {
            encrypted = BufferedApapTcpCodec.decodeExact(frame, limits.maximumFrameBytes)
            val reader = synchronized(lifecycleLock) { readerOwner }
                ?: throw IllegalStateException("main-buffered reader owner is unavailable")
            val stamped = withAuthenticationBoundary {
                val decoded = reader.decryptPacket(requireNotNull(encrypted), limits.packetLimits)
                val authenticatedAtNanos = authenticationClockNanos()
                val admitted = try {
                    authenticatedPacketAdmission.admit(authenticatedAtNanos)
                } catch (problem: Throwable) {
                    if (problem is Error) throw problem
                    throw MainBufferedAdmissionRejectedException(
                        "main-buffered authenticated packet admission hook failed",
                        problem,
                    )
                }
                if (!admitted) {
                    throw MainBufferedAdmissionRejectedException(
                        "main-buffered authenticated packet was rejected before queueing",
                    )
                }
                val stamp = (packetConsumer as? MainBufferedStampedPacketConsumer)?.let { consumer ->
                    try {
                        consumer.stampAuthenticatedPacket(identity, decoded)
                    } catch (problem: Throwable) {
                        if (problem is Error) throw problem
                        throw MainBufferedAdmissionRejectedException(
                            "main-buffered authenticated packet stamp was rejected",
                            problem,
                        )
                    }
                }
                QueuedPacket(decoded, frame.size, stamp)
            }
            if (!enqueue(stamped)) return false
            if (reader.isTerminal) {
                markInputCompletion(
                    MainBufferedIngressTerminationReason.NONCE_EXHAUSTED,
                    ApapNonceExhaustedException(),
                )
                return false
            }
            return true
        } finally {
            encrypted?.fill(0)
        }
    }

    private fun enqueue(queued: QueuedPacket): Boolean = synchronized(queueLock) {
        while (!stopRequested.get() &&
            (queue.size >= limits.maximumQueuedPackets ||
                queuedBytes.toLong() + queued.byteCount > limits.maximumQueuedBytes)
        ) {
            queueLock.wait()
        }
        if (stopRequested.get()) return@synchronized false
        queue.addLast(queued)
        queuedBytes += queued.byteCount
        queueLock.notifyAll()
        true
    }

    private fun runDelivery() {
        try {
            while (true) {
                val next = synchronized(queueLock) {
                    while (queue.isEmpty() && inputCompletion == null && !stopRequested.get()) {
                        queueLock.wait()
                    }
                    when {
                        stopRequested.get() -> DeliveryStep.Stop
                        queue.isNotEmpty() -> {
                            val queued = queue.removeFirst()
                            queuedBytes -= queued.byteCount
                            queueLock.notifyAll()
                            DeliveryStep.Packet(queued.packet, queued.stamp)
                        }
                        else -> DeliveryStep.Complete(requireNotNull(inputCompletion))
                    }
                }
                when (next) {
                    DeliveryStep.Stop -> return
                    is DeliveryStep.Packet -> {
                        val consumer = packetConsumer as? MainBufferedStampedPacketConsumer
                        if (consumer == null) {
                            packetConsumer.onPacket(this, next.packet)
                        } else {
                            consumer.onStampedPacket(
                                this,
                                next.packet,
                                requireNotNull(next.stamp) {
                                    "authenticated APAP packet has no playback epoch stamp"
                                },
                            )
                        }
                    }
                    is DeliveryStep.Complete -> {
                        terminate(
                            next.completion.reason,
                            next.completion.failure,
                            discardQueuedPackets = false,
                        )
                        return
                    }
                }
            }
        } catch (problem: InterruptedException) {
            if (!stopRequested.get()) {
                terminate(
                    MainBufferedIngressTerminationReason.CALLBACK_FAILURE,
                    problem,
                    discardQueuedPackets = true,
                )
            }
        } catch (problem: Throwable) {
            if (!stopRequested.get()) {
                terminate(
                    MainBufferedIngressTerminationReason.CALLBACK_FAILURE,
                    problem,
                    discardQueuedPackets = true,
                )
            }
            if (problem is Error) throw problem
        } finally {
            workerExited()
        }
    }

    private fun markInputCompletion(
        reason: MainBufferedIngressTerminationReason,
        failure: Throwable?,
    ) {
        synchronized(queueLock) {
            if (stopRequested.get()) return
            check(inputCompletion == null) { "main-buffered input completed more than once" }
            inputCompletion = InputCompletion(reason, failure)
            queueLock.notifyAll()
        }
    }

    private fun terminate(
        reason: MainBufferedIngressTerminationReason,
        failure: Throwable?,
        discardQueuedPackets: Boolean,
    ) {
        var won = false
        synchronized(lifecycleLock) {
            if (terminationReason == null) {
                terminationReason = reason
                terminalFailure = failure
                state = when (reason) {
                    MainBufferedIngressTerminationReason.CLEAN_EOF ->
                        MainBufferedIngressState.CLEAN_EOF
                    MainBufferedIngressTerminationReason.LOCAL_CLOSE ->
                        MainBufferedIngressState.CLOSING
                    else -> MainBufferedIngressState.FAILED
                }
                won = true
                lifecycleLock.notifyAll()
            }
        }
        if (!won) return
        requestStop(discardQueuedPackets)
        try {
            onTerminal?.invoke(reason, failure)
        } catch (callbackFailure: Throwable) {
            synchronized(lifecycleLock) {
                val prior = terminalFailure
                if (prior == null) terminalFailure = callbackFailure
                else if (prior !== callbackFailure) prior.addSuppressed(callbackFailure)
                lifecycleLock.notifyAll()
            }
            if (callbackFailure is Error) throw callbackFailure
        } finally {
            scheduleDeferredClose()
        }
    }

    private fun requestLocalCloseIfNeeded() {
        synchronized(lifecycleLock) {
            if (terminationReason == null) {
                terminationReason = MainBufferedIngressTerminationReason.LOCAL_CLOSE
                state = MainBufferedIngressState.CLOSING
                lifecycleLock.notifyAll()
            }
        }
    }

    private fun recordFatalCloseFailure(problem: Error) {
        synchronized(lifecycleLock) {
            closeState = MainBufferedIngressCloseState.FAILED
            val existing = terminalFailure
            when {
                existing == null -> terminalFailure = problem
                existing !== problem -> existing.addSuppressed(problem)
            }
            lifecycleLock.notifyAll()
        }
    }

    private fun requestStop(discardQueuedPackets: Boolean) {
        stopRequested.set(true)
        synchronized(queueLock) {
            inputCompletion = null
            if (discardQueuedPackets) {
                queue.clear()
                queuedBytes = 0
            }
            queueLock.notifyAll()
        }
    }

    private fun clearQueue() {
        synchronized(queueLock) {
            queue.clear()
            queuedBytes = 0
            inputCompletion = null
            queueLock.notifyAll()
        }
    }

    private fun scheduleDeferredClose() {
        var coordinator: Thread? = null
        try {
            coordinator = synchronized(lifecycleLock) {
                if (state == MainBufferedIngressState.CLOSED || deferredCloseWorker != null) return
                Thread(
                    {
                        try {
                            close()
                        } catch (_: IOException) {
                            // Exact failed owners remain attached for an explicit retry.
                        } finally {
                            synchronized(lifecycleLock) {
                                if (deferredCloseWorker === Thread.currentThread()) {
                                    deferredCloseWorker = null
                                }
                                lifecycleLock.notifyAll()
                            }
                        }
                    },
                    "main-buffered-retire-g${identity.generation}",
                ).apply { isDaemon = true }.also { deferredCloseWorker = it }
            }
            requireNotNull(coordinator).start()
        } catch (problem: Throwable) {
            synchronized(lifecycleLock) {
                if (deferredCloseWorker === coordinator) deferredCloseWorker = null
                closeState = MainBufferedIngressCloseState.FAILED
                val existing = terminalFailure
                when {
                    existing == null -> terminalFailure = problem
                    existing !== problem -> existing.addSuppressed(problem)
                }
                lifecycleLock.notifyAll()
            }
            if (problem is Error) throw problem
        }
    }

    private fun workerExited() {
        synchronized(lifecycleLock) { lifecycleLock.notifyAll() }
        synchronized(queueLock) { queueLock.notifyAll() }
    }

    private fun classifyIngressFailure(problem: Throwable): MainBufferedIngressTerminationReason =
        when (problem) {
            is MainBufferedAdmissionRejectedException ->
                MainBufferedIngressTerminationReason.ADMISSION_REJECTED
            is ApapAuthenticationException ->
                MainBufferedIngressTerminationReason.AUTHENTICATION_FAILURE
            is ApapNonceSequenceException ->
                MainBufferedIngressTerminationReason.NONCE_SEQUENCE_FAILURE
            is ApapNonceExhaustedException ->
                MainBufferedIngressTerminationReason.NONCE_EXHAUSTED
            is ApapProtocolException,
            is IllegalArgumentException,
            -> MainBufferedIngressTerminationReason.PROTOCOL_FAILURE
            else -> MainBufferedIngressTerminationReason.TRANSPORT_FAILURE
        }

    private fun waitFor(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        val deadline = saturatingDeadline(timeoutMillis)
        synchronized(lifecycleLock) {
            while (!predicate()) {
                val remaining = remainingMillis(deadline)
                if (remaining <= 0L) return false
                lifecycleLock.wait(remaining)
            }
            return true
        }
    }

    internal fun terminalFailureForTest(): Throwable? = synchronized(lifecycleLock) {
        terminalFailure
    }

    private data class QueuedPacket(
        val packet: ApapPacket,
        val byteCount: Int,
        val stamp: Any?,
    )

    private data class InputCompletion(
        val reason: MainBufferedIngressTerminationReason,
        val failure: Throwable?,
    )

    private sealed interface DeliveryStep {
        data object Stop : DeliveryStep
        data class Packet(val packet: ApapPacket, val stamp: Any?) : DeliveryStep
        data class Complete(val completion: InputCompletion) : DeliveryStep
    }
}

/** Incremental inclusive-length framing that never trusts a peer length before bounding it. */
private class IncrementalBufferedApapFramer(
    private val maximumFrameBytes: Int,
) {
    private val prefix = ByteArray(BufferedApapTcpCodec.PREFIX_BYTES)
    private var prefixBytes = 0
    private var frame: ByteArray? = null
    private var frameBytes = 0

    /** Returns false when [consumer] intentionally stops after a complete frame. */
    fun feed(chunk: ByteArray, consumer: (ByteArray) -> Boolean): Boolean {
        var cursor = 0
        while (cursor < chunk.size) {
            if (frame == null) {
                val copied = minOf(prefix.size - prefixBytes, chunk.size - cursor)
                chunk.copyInto(prefix, prefixBytes, cursor, cursor + copied)
                prefixBytes += copied
                cursor += copied
                if (prefixBytes < prefix.size) continue

                val declared = readU32Be(prefix)
                if (declared < BufferedApapTcpCodec.PREFIX_BYTES) {
                    throw ApapProtocolException(
                        "BufferedAPAP total length $declared is smaller than " +
                            BufferedApapTcpCodec.PREFIX_BYTES,
                    )
                }
                if (declared > maximumFrameBytes.toLong()) {
                    throw ApapProtocolException(
                        "BufferedAPAP total length $declared exceeds $maximumFrameBytes",
                    )
                }
                frame = ByteArray(declared.toInt()).also {
                    prefix.copyInto(it, 0)
                }
                frameBytes = prefix.size
                prefix.fill(0)
                prefixBytes = 0
            }

            val exactFrame = requireNotNull(frame)
            val copied = minOf(exactFrame.size - frameBytes, chunk.size - cursor)
            if (copied > 0) {
                chunk.copyInto(exactFrame, frameBytes, cursor, cursor + copied)
                frameBytes += copied
                cursor += copied
            }
            if (frameBytes == exactFrame.size) {
                frame = null
                frameBytes = 0
                val keepGoing = try {
                    consumer(exactFrame)
                } finally {
                    exactFrame.fill(0)
                }
                if (!keepGoing) return false
            }
        }
        return true
    }

    fun requireFrameBoundaryAtEof() {
        if (prefixBytes != 0 || frame != null) {
            throw ApapProtocolException("truncated BufferedAPAP frame at EOF")
        }
    }

    fun clear() {
        prefix.fill(0)
        prefixBytes = 0
        frame?.fill(0)
        frame = null
        frameBytes = 0
    }

    private fun readU32Be(bytes: ByteArray): Long =
        ((bytes[0].toLong() and 0xffL) shl 24) or
            ((bytes[1].toLong() and 0xffL) shl 16) or
            ((bytes[2].toLong() and 0xffL) shl 8) or
            (bytes[3].toLong() and 0xffL)
}

/** Exact-reference transport claims prevent two live generations from consuming one byte stream. */
private object MainBufferedTransportClaims {
    private val owners = IdentityHashMap<BlockingDuplexByteStream, MainBufferedIngressIdentity>()

    @Synchronized
    fun claim(
        transport: BlockingDuplexByteStream,
        identity: MainBufferedIngressIdentity,
    ): Boolean {
        if (owners.containsKey(transport)) return false
        owners[transport] = identity
        return true
    }

    @Synchronized
    fun release(
        transport: BlockingDuplexByteStream,
        identity: MainBufferedIngressIdentity,
    ): Boolean {
        if (owners[transport] !== identity) return false
        owners.remove(transport)
        return true
    }
}

private fun saturatingDeadline(timeoutMillis: Long): Long {
    val now = System.nanoTime()
    val delta = if (timeoutMillis >= Long.MAX_VALUE / 1_000_000L) {
        Long.MAX_VALUE
    } else {
        timeoutMillis * 1_000_000L
    }
    return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
}

private fun remainingMillis(deadlineNanos: Long): Long {
    val remaining = deadlineNanos - System.nanoTime()
    if (remaining <= 0L) return 0L
    return ((remaining + 999_999L) / 1_000_000L).coerceAtLeast(1L)
}

private fun appendCloseFailure(
    current: IOException?,
    message: String,
    cause: Throwable? = null,
): IOException {
    val next = MainBufferedIngressCloseException(message, cause)
    if (current == null) return next
    current.addSuppressed(next)
    return current
}
