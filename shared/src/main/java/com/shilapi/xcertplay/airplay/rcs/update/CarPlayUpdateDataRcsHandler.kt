package com.shilapi.xcertplay.airplay.rcs.update

import com.shilapi.xcertplay.airplay.CarPlayUpdateDataTerminalAckEvidence
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The application half of exactly one UpdateData transfer. This can be driven without a socket.
 * It only commits an untrusted quarantine record; it does not install or authorize a theme.
 * [accept] is serialized internally, but callers must keep it off the RCS callback thread because
 * quarantine I/O may be slow.
 */
class CarPlayUpdateDataSessionPump(
    sink: CarPlayUpdateDataQuarantineSink,
    private val send: (RcsMessage) -> Unit,
    limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
    private val sealForTerminalAck: () -> Boolean = { true },
) : Closeable {
    private val receiver = CarPlayUpdateDataReceiver(sink, limits)
    private val codec = CarPlayUpdateDataCodec(limits)
    private val closeRequested = AtomicBoolean(false)
    private val rejectionRequested = AtomicBoolean(false)
    private val operationLock = Object()
    private val ackGate = Object()
    private var finished = false

    /** A close signal that never waits for sink I/O or an already admitted ACK send. */
    fun requestClose() {
        // Serializes with ACK admission. A send admitted before close may still finish later;
        // the worker and its owner reservation remain live until that send and cleanup finish.
        synchronized(ackGate) { closeRequested.set(true) }
    }

    /** An over-capacity signal. The serial worker sends ACK 1 after cleanup. */
    fun requestReject() {
        // The RCS callback must not wait for a possibly blocked ACK send. The serial session
        // linearizes this signal against ACK admission under its queue lock instead.
        rejectionRequested.set(true)
    }

    /** Returns true once this stream is terminal. */
    fun accept(message: RcsMessage): Boolean = synchronized(operationLock) {
        if (finished || closeRequested.get()) return@synchronized true
        if (rejectionRequested.get()) return@synchronized rejectLocked()

        val result = try {
            receiver.receive(message)
        } catch (_: Exception) {
            return@synchronized rejectLocked()
        }
        when (result) {
            is CarPlayUpdateDataReceiveResult.Receiving -> {
                if (rejectionRequested.get()) rejectLocked() else closeRequested.get()
            }

            is CarPlayUpdateDataReceiveResult.Rejected -> finishLocked(result.ack)

            is CarPlayUpdateDataReceiveResult.AwaitingDurableCommit -> {
                if (closeRequested.get()) return@synchronized true
                if (rejectionRequested.get()) return@synchronized rejectLocked()
                val ack = try {
                    receiver.confirmDurableQuarantineCommit(result.receipt)
                } catch (_: Exception) {
                    return@synchronized rejectLocked()
                }
                // An over-capacity or close signal may arrive while the sink is committing.
                // A durable untrusted record may remain, but it must never be ACKed as accepted.
                if (rejectionRequested.get()) finishLocked(CarPlayUpdateDataMessage.Ack(1))
                else finishLocked(ack)
            }
        }
    }

    /** Serial-worker entry point for a bounded-queue overflow. */
    fun rejectOverload(): Boolean = synchronized(operationLock) {
        if (finished || closeRequested.get()) true else rejectLocked()
    }

    private fun rejectLocked(): Boolean {
        try {
            receiver.cancel()
        } catch (_: Exception) {
            // The receiver can already be terminal if an unexpected sink failure occurred.
            // Closing still retries any pending quarantine cleanup.
            runCatching { receiver.close() }
        }
        return finishLocked(CarPlayUpdateDataMessage.Ack(1))
    }

    private fun finishLocked(ack: CarPlayUpdateDataMessage.Ack): Boolean {
        finished = true
        val outgoing = synchronized(ackGate) {
            if (closeRequested.get()) {
                null
            } else {
                // Seal input at the ACK decision. Messages admitted before the seal invalidate
                // success; messages arriving afterwards are a stream protocol error, not silently
                // dropped. The only success ACK originates in a durable quarantine commit.
                val cleanInput = sealForTerminalAck()
                val finalAck = if (ack.accepted == 0 && (!cleanInput || rejectionRequested.get())) {
                    CarPlayUpdateDataMessage.Ack(1)
                } else {
                    ack
                }
                codec.encode(finalAck)
            }
        }
        outgoing?.let(send)
        return true
    }

    override fun close() {
        requestClose()
        synchronized(operationLock) {
            finished = true
            receiver.close()
        }
    }
}

/**
 * Bounded, single-worker serialization around the potentially fsync-heavy session pump. Exactly
 * one transfer is admitted per authenticated RCS stream. Neither OPACK decode nor disk I/O runs on
 * the RCS receive callback. On overflow, queued input is discarded and the worker rejects the
 * transfer. Input queued before the terminal ACK seal downgrades ACK 0 to rejection; later input
 * throws so the RCS dispatcher closes the stream instead of silently dropping another transfer.
 * There is no unbounded executor backlog.
 */
enum class CarPlayUpdateDataCleanupState {
    RUNNING,
    CLEANING,
    FAILED,
    COMPLETE,
}

class CarPlayUpdateDataSerialSession(
    sink: CarPlayUpdateDataQuarantineSink,
    send: (RcsMessage) -> Unit,
    limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
    private val maxQueuedMessages: Int = 4,
    private val maxQueuedBodyBytes: Long = 4L * limits.maxRcsBodyBytes,
    private val onFinished: () -> Unit = {},
) : Closeable {
    init {
        require(maxQueuedMessages > 0)
        require(maxQueuedBodyBytes > 0)
    }

    private val pump = CarPlayUpdateDataSessionPump(sink, send, limits, ::sealForTerminalAck)
    private val queueLock = Object()
    private val queue = ArrayDeque<RcsMessage>()
    private val maxMessageBodyBytes = limits.maxRcsBodyBytes
    private var queuedBodyBytes = 0L
    private var closing = false
    private var overloaded = false
    private var terminalSealed = false
    private var terminated = false
    private var cleanupState = CarPlayUpdateDataCleanupState.RUNNING
    private val worker = Thread(::runWorker, "carplay-update-data-quarantine").apply {
        isDaemon = true
        start()
    }

    /** Copies caller-owned bytes and returns promptly; no disk access happens here. */
    fun onMessage(message: RcsMessage) {
        synchronized(queueLock) { requireOpenInputLocked() }
        val bodyBytes = message.body.size
        if (bodyBytes > maxMessageBodyBytes || bodyBytes.toLong() > maxQueuedBodyBytes) {
            synchronized(queueLock) {
                requireOpenInputLocked()
                overloadLocked()
            }
            return
        }
        val owned = RcsMessage(message.messageType, message.body.copyOf())
        synchronized(queueLock) {
            requireOpenInputLocked()
            if (queue.size >= maxQueuedMessages ||
                owned.body.size.toLong() > maxQueuedBodyBytes - queuedBodyBytes
            ) {
                overloadLocked()
                return
            }
            queue.addLast(owned)
            queuedBodyBytes += owned.body.size
            queueLock.notifyAll()
        }
    }

    private fun requireOpenInputLocked() {
        if (closing || overloaded || terminalSealed || terminated) {
            throw IOException("UpdateData permits one transfer per RCS stream")
        }
    }

    private fun overloadLocked() {
        if (closing || overloaded || terminalSealed || terminated) return
        overloaded = true
        // This store happens while holding queueLock. Terminal sealing takes the same lock, so
        // overflow recorded before the seal cannot race through as an ACK 0.
        pump.requestReject()
        queue.clear()
        queuedBodyBytes = 0
        queueLock.notifyAll()
    }

    private fun sealForTerminalAck(): Boolean = synchronized(queueLock) {
        terminalSealed = true
        val cleanInput = !closing && !overloaded && queue.isEmpty()
        queue.clear()
        queuedBodyBytes = 0
        cleanInput
    }

    private fun runWorker() {
        var interrupted = false
        try {
            while (true) {
                val next = synchronized(queueLock) {
                    while (queue.isEmpty() && !closing && !overloaded) queueLock.wait()
                    when {
                        closing -> null
                        overloaded -> null
                        else -> queue.removeFirst().also { queuedBodyBytes -= it.body.size }
                    }
                }
                if (next == null) {
                    if (synchronized(queueLock) { overloaded && !closing }) pump.rejectOverload()
                    break
                }
                if (pump.accept(next)) break
            }
        } catch (_: InterruptedException) {
            interrupted = true
        } catch (_: Exception) {
            // A failed send or sink is terminal. The stream owner controls transport teardown.
        } finally {
            synchronized(queueLock) {
                terminated = true
                cleanupState = CarPlayUpdateDataCleanupState.CLEANING
                queue.clear()
                queuedBodyBytes = 0
                queueLock.notifyAll()
            }
            // NIO abort can fail with ClosedByInterruptException while the worker's interrupt
            // flag is set. Clear it for quarantine cleanup, then restore it on thread exit.
            interrupted = Thread.interrupted() || interrupted
            var cleanup = runCatching { pump.close() }
            if (cleanup.isFailure) {
                interrupted = Thread.interrupted() || interrupted
                cleanup = runCatching { pump.close() }
            }
            completeCleanup(cleanup.isSuccess)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** A failed abort keeps the owner reservation live until an explicit retry succeeds. */
    val cleanupStateSnapshot: CarPlayUpdateDataCleanupState
        get() = synchronized(queueLock) { cleanupState }

    /** Waits for a definitive drain result; true means sink cleanup and the callback completed. */
    fun awaitCleanup(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(queueLock) {
            while (cleanupState == CarPlayUpdateDataCleanupState.RUNNING ||
                cleanupState == CarPlayUpdateDataCleanupState.CLEANING
            ) {
                val remaining = budget - (System.nanoTime() - started)
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(queueLock, remaining)
            }
            return cleanupState == CarPlayUpdateDataCleanupState.COMPLETE
        }
    }

    /** Retry only after the serial worker is terminal. This may perform blocking disk I/O. */
    fun retryFailedCleanup(): Boolean {
        synchronized(queueLock) {
            if (cleanupState == CarPlayUpdateDataCleanupState.COMPLETE) return true
            if (cleanupState != CarPlayUpdateDataCleanupState.FAILED) return false
            cleanupState = CarPlayUpdateDataCleanupState.CLEANING
        }
        val cleaned = runCatching { pump.close() }.isSuccess
        completeCleanup(cleaned)
        return synchronized(queueLock) { cleanupState == CarPlayUpdateDataCleanupState.COMPLETE }
    }

    private fun completeCleanup(cleaned: Boolean) {
        // An owner may release its reserved bytes only after pump.close() has completed. If the
        // callback itself fails, retaining the reservation is safer than claiming completion.
        val finished = cleaned && runCatching(onFinished).isSuccess
        synchronized(queueLock) {
            cleanupState = if (finished) CarPlayUpdateDataCleanupState.COMPLETE
                else CarPlayUpdateDataCleanupState.FAILED
            queueLock.notifyAll()
        }
    }

    override fun close() {
        pump.requestClose()
        synchronized(queueLock) {
            if (closing) return
            closing = true
            queue.clear()
            queuedBodyBytes = 0
            // Do this before releasing queueLock, so the worker cannot enter its cleanup and
            // then receive a delayed first close interrupt. Repeated close never reinterrupts.
            if (!terminated && Thread.currentThread() !== worker) worker.interrupt()
            queueLock.notifyAll()
        }
    }
}

/** One fresh instance per authenticated CarPlayUpdateData RCS stream. */
class CarPlayUpdateDataRcsHandler(
    private val stream: RcsDataStream,
    sink: CarPlayUpdateDataQuarantineSink,
    limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
    onFinished: () -> Unit = {},
) : RcsDataStreamHandler, CarPlayUpdateDataTerminalAckEvidence {
    init {
        require(stream.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA) {
            "UpdateData handler requires the CarPlayUpdateData client type"
        }
    }

    private val ackSender = CarPlayUpdateDataTerminalAckSender(stream::send, limits)

    override val terminalAckSent: Boolean
        get() = ackSender.terminalAckSent

    private val session = CarPlayUpdateDataSerialSession(
        sink = sink,
        send = ackSender::send,
        limits = limits,
        onFinished = onFinished,
    )

    val cleanupStateSnapshot: CarPlayUpdateDataCleanupState
        get() = session.cleanupStateSnapshot

    fun awaitCleanup(timeoutMillis: Long): Boolean = session.awaitCleanup(timeoutMillis)

    fun retryFailedCleanup(): Boolean = session.retryFailedCleanup()

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        check(stream === this.stream) { "UpdateData handler received a different stream" }
        session.onMessage(message)
    }

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        session.close()
    }

    override fun close() {
        session.close()
    }
}

/** Evidence is published only after the terminal ACK reached the authenticated RCS send path. */
internal class CarPlayUpdateDataTerminalAckSender(
    private val downstream: (RcsMessage) -> Unit,
    limits: CarPlayUpdateDataLimits = CarPlayUpdateDataLimits(),
) : CarPlayUpdateDataTerminalAckEvidence {
    private val codec = CarPlayUpdateDataCodec(limits)
    private val sent = AtomicBoolean(false)

    override val terminalAckSent: Boolean
        get() = sent.get()

    fun send(message: RcsMessage) {
        require(codec.decode(message) is CarPlayUpdateDataMessage.Ack) {
            "UpdateData terminal sender accepts only ACK messages"
        }
        downstream(message)
        sent.set(true)
    }
}
