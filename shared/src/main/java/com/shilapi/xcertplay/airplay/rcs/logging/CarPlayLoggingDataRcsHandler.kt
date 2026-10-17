package com.shilapi.xcertplay.airplay.rcs.logging

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Supplies an app-authorized archive for an already authenticated LoggingData request. An
 * implementation must not return arbitrary paths or phone-supplied content. This callback is
 * invoked by the serial worker only after the RCS peer has authenticated and requested an archive.
 * Acquisition must be bounded, and the returned stream must permit prompt abort by [java.io.InputStream.close].
 */
fun interface CarPlayLoggingArchiveProvider {
    fun acquireForAuthenticatedRequest(): CarPlayLoggingArchiveSource
}

/**
 * A registration seam, not a claim that a product log archive or the complete feature exists.
 * The gate is closed by default and is rechecked at handler creation, ingress and every send.
 * AirPlay feature negotiation independently keeps `logTransfer` disabled until its remaining
 * sidecar, statistics and archive-production requirements are implemented.
 */
class CarPlayLoggingDataRcsHandlerFactory(
    private val archiveProvider: CarPlayLoggingArchiveProvider,
    private val capabilityGate: () -> Boolean = { false },
    private val codec: CarPlayLoggingDataCodec = CarPlayLoggingDataCodec(),
    private val policy: CarPlayLoggingDataSessionPolicy = CarPlayLoggingDataSessionPolicy(),
    private val onEvent: (CarPlayLoggingDataRcsHandler, CarPlayLoggingDataSessionEvent) -> Unit =
        { _, _ -> },
) : RcsDataStreamHandlerFactory {
    private val registration = RcsDataStreamHandlerDescriptor(
        clientType = RcsClientTypes.CAR_PLAY_LOGGING_DATA,
        implementationId = "carplay-logging-data-authenticated-v1",
        readinessProbe = ::probe,
        handlerFactory = ::open,
    )

    override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? =
        registration.takeIf { clientType == RcsClientTypes.CAR_PLAY_LOGGING_DATA }

    private fun probe(): RcsDataStreamHandlerProbe =
        if (gateOpen()) RcsDataStreamHandlerProbe.ready()
        else RcsDataStreamHandlerProbe.notReady("log_transfer_disabled")

    private fun open(stream: RcsDataStream): RcsDataStreamHandler {
        if (!gateOpen()) throw IOException("LoggingData capability is unavailable")
        return CarPlayLoggingDataRcsHandler(
            stream = stream,
            archiveProvider = archiveProvider,
            capabilityGate = ::gateOpen,
            codec = codec,
            policy = policy,
            onEvent = onEvent,
        )
    }

    private fun gateOpen(): Boolean = try {
        capabilityGate()
    } catch (_: Throwable) {
        false
    }
}

/** Exactly one authenticated CarPlayLoggingData RCS stream. */
class CarPlayLoggingDataRcsHandler internal constructor(
    private val stream: RcsDataStream,
    archiveProvider: CarPlayLoggingArchiveProvider,
    capabilityGate: () -> Boolean,
    codec: CarPlayLoggingDataCodec,
    policy: CarPlayLoggingDataSessionPolicy,
    onEvent: (CarPlayLoggingDataRcsHandler, CarPlayLoggingDataSessionEvent) -> Unit,
) : RcsDataStreamHandler {
    init {
        require(stream.clientType == RcsClientTypes.CAR_PLAY_LOGGING_DATA) {
            "LoggingData handler requires the exact CarPlayLoggingData client type"
        }
    }

    private val serial = CarPlayLoggingDataSerialSession(
        sessionId = UUID.randomUUID().toString(),
        archiveProvider = archiveProvider,
        send = stream::send,
        capabilityGate = capabilityGate,
        codec = codec,
        policy = policy,
        onEvent = { onEvent(this, it) },
        onTerminalFailure = { stream.close() },
    )

    val archiveState: CarPlayLoggingArchiveState
        get() = serial.archiveState

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        check(stream === this.stream) { "LoggingData handler received a different RCS stream" }
        serial.onMessage(message)
    }

    /** The producer chooses the meaning and cadence of the raw requested interval. */
    fun publishStatisticsBlocking(data: Map<*, *>) = serial.publishStatisticsBlocking(data)

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        check(stream === this.stream) { "LoggingData handler closed by a different RCS stream" }
        close()
    }

    override fun close() = serial.close()
}

/**
 * Bounded, single-worker LoggingData dispatch. The RCS callback only copies and queues bytes;
 * archive source I/O and RCS sends never run on that callback. Any malformed input, source/send
 * failure or overload is terminal for this stream. The transport owner must close its RCS stream
 * in [onTerminalFailure] so an unauthenticated replacement cannot reuse this sender's ACK state.
 */
internal class CarPlayLoggingDataSerialSession(
    sessionId: String,
    archiveProvider: CarPlayLoggingArchiveProvider,
    send: (RcsMessage) -> Unit,
    private val capabilityGate: () -> Boolean,
    private val codec: CarPlayLoggingDataCodec = CarPlayLoggingDataCodec(),
    policy: CarPlayLoggingDataSessionPolicy = CarPlayLoggingDataSessionPolicy(),
    private val maxQueuedMessages: Int = 8,
    private val maxQueuedBodyBytes: Long = 2L * codec.limits.maxRcsBodyBytes,
    private val onEvent: (CarPlayLoggingDataSessionEvent) -> Unit = {},
    private val onTerminalFailure: (Throwable) -> Unit = {},
) : Closeable {
    init {
        require(maxQueuedMessages in 1..64)
        require(maxQueuedBodyBytes in 1..(64L * 1024 * 1024))
    }

    private val queueLock = Object()
    private val queue = ArrayDeque<RcsMessage>()
    private var queuedBodyBytes = 0L
    private var closed = false
    private var failed = false
    private var stopped = false
    private val sender = CarPlayLoggingDataSessionSender(
        sessionId = sessionId,
        archiveSource = DeferredArchiveSource(archiveProvider, ::ensureActive),
        send = { message ->
            ensureActive()
            send(message)
        },
        codec = codec,
        policy = policy,
    )
    private val worker = Thread(::runWorker, "carplay-logging-data").apply {
        isDaemon = true
        start()
    }

    val archiveState: CarPlayLoggingArchiveState
        get() = sender.archiveState

    fun onMessage(message: RcsMessage) {
        ensureEnabled()
        val bodySize = message.body.size
        synchronized(queueLock) {
            if (closed || failed) throw IOException("LoggingData stream is no longer receiving")
            if (bodySize > codec.limits.maxRcsBodyBytes ||
                queue.size >= maxQueuedMessages ||
                bodySize.toLong() > maxQueuedBodyBytes - queuedBodyBytes
            ) {
                failed = true
                queue.clear()
                queuedBodyBytes = 0
                queueLock.notifyAll()
                throw IOException("LoggingData receive queue exceeded its bounds")
            }
            queue.addLast(RcsMessage(message.messageType, message.body.copyOf()))
            queuedBodyBytes += bodySize
            queueLock.notifyAll()
        }
    }

    /** Called by an explicitly provisioned producer, never automatically on a timer. */
    fun publishStatisticsBlocking(data: Map<*, *>) {
        ensureEnabled()
        synchronized(queueLock) {
            if (closed || failed) throw IOException("LoggingData stream is no longer sending")
        }
        sender.publishStatistics(data)
    }

    private fun runWorker() {
        try {
            while (true) {
                val next = synchronized(queueLock) {
                    while (queue.isEmpty() && !closed && !failed) queueLock.wait()
                    if (closed || failed) null
                    else queue.removeFirst().also { queuedBodyBytes -= it.body.size }
                } ?: break
                ensureActive()
                val event = sender.acceptFromPhoneBlocking(next)
                ensureActive()
                onEvent(event)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            synchronized(queueLock) {
                if (!closed) failed = true
                queue.clear()
                queuedBodyBytes = 0
                queueLock.notifyAll()
            }
            runCatching { onTerminalFailure(failure) }
        } finally {
            runCatching { sender.close() }
            synchronized(queueLock) {
                stopped = true
                queueLock.notifyAll()
            }
        }
    }

    /** Intended for lifecycle verification; never performs source I/O. */
    fun awaitStopped(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val started = System.nanoTime()
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(queueLock) {
            while (!stopped) {
                val remaining = budget - (System.nanoTime() - started)
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(queueLock, remaining)
            }
            return true
        }
    }

    private fun ensureEnabled() {
        if (!capabilityGate()) throw IOException("LoggingData capability was withdrawn")
    }

    private fun ensureActive() {
        ensureEnabled()
        synchronized(queueLock) {
            if (closed || failed) throw IOException("LoggingData stream is no longer active")
        }
    }

    override fun close() {
        synchronized(queueLock) {
            if (!closed) {
                closed = true
                queue.clear()
                queuedBodyBytes = 0
                if (!stopped && Thread.currentThread() !== worker) worker.interrupt()
                queueLock.notifyAll()
            }
        }
        // Closing the active archive InputStream is the only portable way to unblock a source
        // that does not honor thread interruption. The injected source must support close.
        sender.close()
    }

    private class DeferredArchiveSource(
        private val provider: CarPlayLoggingArchiveProvider,
        private val ensureActive: () -> Unit,
    ) : CarPlayLoggingArchiveSource {
        private var source: CarPlayLoggingArchiveSource? = null

        private fun acquired(): CarPlayLoggingArchiveSource = synchronized(this) {
            ensureActive()
            source ?: provider.acquireForAuthenticatedRequest().also { source = it }
        }

        override val sizeBytes: Long get() = acquired().sizeBytes

        override fun openStream() = acquired().openStream()
    }
}
