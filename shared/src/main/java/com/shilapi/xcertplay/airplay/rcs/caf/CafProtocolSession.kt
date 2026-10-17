package com.shilapi.xcertplay.airplay.rcs.caf

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.io.Closeable
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A CAF channel constructor failed and at least one exact child still needs retirement. The
 * exception is the retained cleanup owner; callers may retry [close] without recreating anything.
 */
class CafProtocolSessionConstructionException internal constructor(
    cause: Throwable,
    private val cleanup: Closeable,
) : IllegalStateException("CAF channel construction cleanup is pending", cause), Closeable {
    override fun close() = cleanup.close()
}

/** A plugin factory failed after returning a handler whose close did not yet succeed. */
internal class CafPluginConstructionException(
    cause: Throwable,
    private val handler: CafPluginHandler,
) : IllegalStateException("CAF plugin construction cleanup is pending", cause), Closeable {
    override fun close() = handler.close()
}

/** One channel-scoped CAF engine. Only its immutable registry/repository dependencies are shared. */
class CafProtocolSession(
    val clientType: RcsClientType,
    val stream: RcsDataStream,
    private val plugins: CafPluginRegistry,
    private val decoderFactory: CafConfigTreeDecoderFactory,
    val transactions: CafTransactionTracker = CafTransactionTracker(),
    private val codec: CafRcsCodec = CafRcsCodec(),
    private val outboundSender: (RcsMessage) -> Unit = stream::send,
    private val timeoutWorker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "caf-timeouts-${clientType.name}").apply { isDaemon = true }
        },
    private val ownerUse: () -> Closeable? = { Closeable { } },
    private val onClosed: (CafProtocolSession) -> Unit = {},
) : RcsDataStreamHandler, Closeable {
    private val lifecycleLock = Any()
    private val sendLock = Any()
    private val handlers = LinkedHashMap<Long, CafPluginHandler>()
    private var operationsInFlight = 0
    private var closeRequested = false
    private var closeInFlight = false
    private var transactionsClosed = false
    private var workerClosed = false
    private var streamRetired = false
    private var closeStreamRequested = false
    private var resourcesClosed = false
    @Volatile private var inboundCarrierStyle: CafCarrierStyle? = null

    init {
        val createdHandlers = LinkedHashMap<Long, CafPluginHandler>()
        try {
            require(stream.clientType == clientType) { "CAF stream/client type mismatch" }
            require(clientType.payloadStyle == RcsPayloadStyle.CAF_BINARY_PLIST_OPACK) {
                "${clientType.name} is not a CAF channel"
            }
            plugins.validateFor(clientType, decoderFactory)
            plugins.registrationsFor(clientType).forEach { registration ->
                createdHandlers[registration.pluginId] = registration.handlerFactory.create(this)
            }
            handlers.putAll(createdHandlers)
            timeoutWorker.scheduleWithFixedDelay(
                ::expireTransactions,
                TRANSACTION_EXPIRY_POLL_MILLIS,
                TRANSACTION_EXPIRY_POLL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: Throwable) {
            synchronized(lifecycleLock) {
                closeRequested = true
                handlers.clear()
            }
            val primary = (failure as? CafPluginConstructionException)?.cause ?: failure
            val cleanup = CafSessionConstructionCleanup(
                children = buildList {
                    (failure as? CafPluginConstructionException)?.let(::add)
                    addAll(createdHandlers.values.toList().asReversed())
                },
                transactions = transactions,
                timeoutWorker = timeoutWorker,
            )
            val cleanupFailure = runCatching { cleanup.close() }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
                throw CafProtocolSessionConstructionException(primary, cleanup)
            }
            throw primary
        }
    }

    fun send(frame: CafRcsFrame) {
        withOperation {
            sendWithoutLease(frame)
        }
    }

    fun requestConfig(pluginId: Long): CafPendingTransaction = request(
        pluginId,
        CafCommand.CONFIG_REQUEST,
        setOf(CafCommand.CONFIG_RESPONSE, CafCommand.GENERAL_ERROR),
    ) { transactionId -> CarAccessoryMessages.configRequest(pluginId, transactionId) }

    fun register(
        pluginId: Long,
        registration: CafRegistration = CafRegistration.Wildcard,
    ): CafPendingTransaction = request(
        pluginId,
        CafCommand.REGISTER_REQUEST,
        setOf(CafCommand.REGISTER_RESPONSE, CafCommand.GENERAL_ERROR),
    ) { transactionId ->
        CarAccessoryMessages.registerRequest(pluginId, transactionId, registration)
    }

    fun unregister(pluginId: Long, identifiers: List<Long>): CafPendingTransaction = request(
        pluginId,
        CafCommand.UNREGISTER_REQUEST,
        setOf(CafCommand.UNREGISTER_RESPONSE, CafCommand.GENERAL_ERROR),
    ) { transactionId -> CarAccessoryMessages.unregisterRequest(pluginId, transactionId, identifiers) }

    fun read(pluginId: Long, identifiers: List<Long>): CafPendingTransaction = request(
        pluginId,
        CafCommand.READ_REQUEST,
        setOf(CafCommand.READ_RESPONSE, CafCommand.GENERAL_ERROR),
    ) { transactionId -> CarAccessoryMessages.readRequest(pluginId, transactionId, identifiers) }

    fun write(pluginId: Long, values: Map<Long, Any?>): CafPendingTransaction = request(
        pluginId,
        CafCommand.WRITE_REQUEST,
        setOf(CafCommand.WRITE_RESPONSE, CafCommand.GENERAL_ERROR),
    ) { transactionId -> CarAccessoryMessages.writeRequest(pluginId, transactionId, values) }

    fun control(pluginId: Long, values: Map<Long, Any?>): CafPendingTransaction = request(
        pluginId,
        CafCommand.CONTROL_REQUEST,
        setOf(CafCommand.CONTROL_RESPONSE, CafCommand.GENERAL_ERROR),
    ) { transactionId -> CarAccessoryMessages.controlRequest(pluginId, transactionId, values) }

    fun decoderFor(pluginId: Long): CafConfigTreeDecoder {
        return withOperation {
            val registration = plugins.requireRegistration(pluginId)
            registration.configTreeDecoder
                ?: decoderFactory.create(registration.protocolVersion, registration.pluginConfig)
        }
    }

    override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
        if (stream !== this.stream) return
        val operation = beginOperation(returnIfClosed = true) ?: return
        try {
            if (message.messageType != ApTransportPackageCodec.MESSAGE_TYPE_COMM) {
                throw CafProtocolException(
                    "CAF channel received messageType 0x${message.messageType.toString(16)}",
                )
            }
            val decoded = try {
                codec.decodeCarrier(message.body)
            } catch (error: CafDecodeException) {
                val bplistMagic = message.body.size >= 8 &&
                    message.body.copyOfRange(0, 8).contentEquals(BPLIST_MAGIC)
                stream.traceProtocol(
                    "CAF rx decode rejected stage=${error.stage.name.lowercase()} " +
                        "bplistMagic=$bplistMagic bytes=${message.body.size}",
                )
                throw error
            }
            val previousStyle = inboundCarrierStyle
            if (previousStyle != null && previousStyle != decoded.style) {
                throw CafProtocolException("CAF carrier style changed during the session")
            }
            inboundCarrierStyle = decoded.style
            val reader = CafMessageReader(decoded.envelope)
            traceFrame("rx", reader.envelope, message.body.size)
            val registration = plugins.requireRegistration(reader.pluginId)
            if (clientType !in registration.clientTypes) {
                throw CafProtocolException("CAF plugin is not registered for ${clientType.name}")
            }
            val pending = transactions.accept(reader)
            CafInboundDispatcher.dispatch(
                session = this,
                handler = synchronized(lifecycleLock) { handlers[reader.pluginId] }
                    ?: throw CafProtocolException("CAF channel has no per-session plugin handler"),
                message = reader,
                pending = pending,
            )
        } finally {
            operation.close()
        }
    }

    override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
        if (stream === this.stream) closeInternal(closeStream = false)
    }

    override fun close() {
        closeInternal(closeStream = true)
    }

    private fun request(
        pluginId: Long,
        command: CafCommand,
        responses: Set<CafCommand>,
        frame: (Long) -> CafRcsFrame,
    ): CafPendingTransaction {
        return withOperation {
            val registration = plugins.requireRegistration(pluginId)
            if (clientType !in registration.clientTypes) {
                throw CafProtocolException("CAF plugin is not routed to ${clientType.name}")
            }
            val transaction = transactions.begin(pluginId, command, responses)
            try {
                sendWithoutLease(frame(transaction.transactionId))
            } catch (failure: Throwable) {
                transactions.cancel(transaction)
                throw failure
            }
            transaction
        }
    }

    private fun closeInternal(closeStream: Boolean) {
        val children = synchronized(lifecycleLock) {
            closeRequested = true
            closeStreamRequested = closeStreamRequested || closeStream
            if (!closeStream) streamRetired = true
            if (resourcesClosed) return
            if (closeInFlight && !closeStream) return
            check(!closeInFlight) { "CAF channel close is already in flight" }
            check(operationsInFlight == 0) { "CAF channel still has operations in flight" }
            closeInFlight = true
            handlers.entries.toList().asReversed()
        }

        var failure: Throwable? = null
        children.forEach { (pluginId, handler) ->
            try {
                handler.close()
                synchronized(lifecycleLock) {
                    if (handlers[pluginId] === handler) handlers.remove(pluginId)
                }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        if (failure == null && !transactionsClosed) {
            try {
                transactions.close()
                synchronized(lifecycleLock) { transactionsClosed = true }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        if (failure == null && !workerClosed) {
            try {
                timeoutWorker.shutdownNow()
                synchronized(lifecycleLock) { workerClosed = true }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        val shouldCloseStream = synchronized(lifecycleLock) {
            failure == null && closeStreamRequested && !streamRetired
        }
        if (shouldCloseStream) {
            try {
                stream.close()
                synchronized(lifecycleLock) { streamRetired = true }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }

        val completed = synchronized(lifecycleLock) {
            closeInFlight = false
            if (failure == null && handlers.isEmpty() && transactionsClosed && workerClosed &&
                streamRetired
            ) {
                resourcesClosed = true
                true
            } else {
                false
            }
        }
        if (completed) onClosed(this)
        failure?.let { throw it }
    }

    private fun traceFrame(direction: String, envelope: CafEnvelope, byteCount: Int) {
        stream.traceProtocol(
            "CAF $direction plugin=${CafUInt64.toString(envelope.pluginId)} " +
                "command=${envelope.message.command.wireName} " +
                "transaction=${envelope.message.transactionId?.let(CafUInt64::toString) ?: "none"} " +
                "bytes=$byteCount",
        )
    }

    private fun expireTransactions() {
        val operation = beginOperation(
            returnIfClosed = true,
            throwIfOwnerDead = false,
        ) ?: return
        try {
            transactions.expire().forEach { transaction ->
                synchronized(lifecycleLock) { handlers[transaction.pluginId] }?.let { handler ->
                    runCatching { handler.onTransactionExpired(this, transaction) }
                }
                stream.traceProtocol(
                    "CAF transaction expired plugin=${CafUInt64.toString(transaction.pluginId)} " +
                        "transaction=${CafUInt64.toString(transaction.transactionId)} " +
                        "command=${transaction.requestCommand.wireName}",
                )
            }
        } finally {
            operation.close()
        }
    }

    private fun sendWithoutLease(frame: CafRcsFrame) {
        val registration = plugins.requireRegistration(frame.envelope.pluginId)
        if (clientType !in registration.clientTypes) {
            throw CafProtocolException("CAF plugin is not routed to ${clientType.name}")
        }
        val outgoing = if (inboundCarrierStyle == CafCarrierStyle.DIRECT_OPACK) {
            codec.encodeEnvelope(frame.envelope, CafCarrierStyle.DIRECT_OPACK)
        } else {
            frame
        }
        traceFrame("tx", outgoing.envelope, outgoing.rcsBody.size)
        synchronized(sendLock) { outboundSender(outgoing.asRcsMessage()) }
    }

    private inline fun <T> withOperation(block: () -> T): T {
        val operation = checkNotNull(beginOperation(returnIfClosed = false))
        try {
            return block()
        } finally {
            operation.close()
        }
    }

    private fun beginOperation(
        returnIfClosed: Boolean,
        throwIfOwnerDead: Boolean = true,
    ): Closeable? {
        synchronized(lifecycleLock) {
            if (closeRequested || resourcesClosed) {
                if (returnIfClosed) return null
                throw IllegalStateException("CAF session is closed")
            }
        }
        val ownerLease = try {
            ownerUse()
        } catch (_: Throwable) {
            null
        }
        if (ownerLease == null) {
            if (!throwIfOwnerDead || returnIfClosed) return null
            throw IllegalStateException("CAF session owner is not live")
        }
        val accepted = synchronized(lifecycleLock) {
            if (closeRequested || resourcesClosed) {
                false
            } else {
                operationsInFlight = Math.addExact(operationsInFlight, 1)
                true
            }
        }
        if (!accepted) {
            ownerLease.close()
            if (returnIfClosed) return null
            throw IllegalStateException("CAF session is closed")
        }
        val token = Any()
        var released = false
        return Closeable {
            synchronized(token) {
                if (!released) {
                    released = true
                    synchronized(lifecycleLock) {
                        check(operationsInFlight > 0) { "CAF operation count underflow" }
                        operationsInFlight -= 1
                    }
                    ownerLease.close()
                }
            }
        }
    }

    companion object {
        private const val TRANSACTION_EXPIRY_POLL_MILLIS = 1_000L
        private val BPLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

        fun handlerFactory(
            plugins: CafPluginRegistry,
            decoderFactory: CafConfigTreeDecoderFactory = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
        ): RcsDataStreamHandlerFactory = CafRcsHandlerFactory(plugins, decoderFactory)
    }
}

/** Exact retry owner used only while a [CafProtocolSession] constructor is unwinding. */
private class CafSessionConstructionCleanup(
    children: Collection<AutoCloseable>,
    private val transactions: CafTransactionTracker,
    private val timeoutWorker: ScheduledExecutorService,
) : Closeable {
    private val lock = Any()
    private val remaining = IdentityHashMap<AutoCloseable, Unit>().apply {
        children.forEach { put(it, Unit) }
    }
    private var closeInFlight = false
    private var transactionsClosed = false
    private var workerClosed = false
    private var resourcesClosed = false

    override fun close() {
        val children = synchronized(lock) {
            if (resourcesClosed) return
            check(!closeInFlight) { "CAF construction cleanup is already in flight" }
            closeInFlight = true
            remaining.keys.toList()
        }
        var failure: Throwable? = null
        children.forEach { child ->
            try {
                child.close()
                synchronized(lock) { remaining.remove(child) }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        if (failure == null && !transactionsClosed) {
            try {
                transactions.close()
                synchronized(lock) { transactionsClosed = true }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        if (failure == null && !workerClosed) {
            try {
                timeoutWorker.shutdownNow()
                synchronized(lock) { workerClosed = true }
            } catch (problem: Throwable) {
                failure = combineCafFailures(failure, problem)
            }
        }
        synchronized(lock) {
            closeInFlight = false
            if (failure == null && remaining.isEmpty() && transactionsClosed && workerClosed) {
                resourcesClosed = true
            }
        }
        failure?.let { throw it }
    }
}

private class CafRcsHandlerFactory(
    private val plugins: CafPluginRegistry,
    private val decoderFactory: CafConfigTreeDecoderFactory,
) : RcsDataStreamHandlerFactory {
    override fun descriptor(clientType: RcsClientType): RcsDataStreamHandlerDescriptor? {
        if (!plugins.supports(clientType)) return null
        return RcsDataStreamHandlerDescriptor(
            clientType = clientType,
            implementationId = "xcertplay.caf.v1",
            readinessProbe = {
                try {
                    plugins.validateFor(clientType, decoderFactory)
                    RcsDataStreamHandlerProbe.ready()
                } catch (_: Throwable) {
                    RcsDataStreamHandlerProbe.notReady("caf_configuration_invalid")
                }
            },
            handlerFactory = { stream ->
                CafProtocolSession(clientType, stream, plugins, decoderFactory)
            },
        )
    }
}

internal object CafInboundDispatcher {
    fun dispatch(
        session: CafProtocolSession,
        handler: CafPluginHandler,
        message: CafMessageReader,
        pending: CafPendingTransaction?,
    ) {
        pending?.let { handler.onTransactionCompleted(session, it, message) }
        when (message.command) {
            CafCommand.CONFIG_NOTIFY -> handler.onConfigNotify(session, message)
            CafCommand.CONFIG_REQUEST -> handler.onConfigRequest(session, message)
            CafCommand.CONFIG_RESPONSE -> handler.onConfigResponse(session, message)
            CafCommand.REGISTER_REQUEST -> handler.onRegisterRequest(session, message)
            CafCommand.REGISTER_RESPONSE -> handler.onRegisterResponse(session, message)
            CafCommand.UNREGISTER_REQUEST -> handler.onUnregisterRequest(session, message)
            CafCommand.UNREGISTER_RESPONSE -> handler.onUnregisterResponse(session, message)
            CafCommand.READ_REQUEST -> handler.onReadRequest(session, message)
            CafCommand.READ_RESPONSE -> handler.onReadResponse(session, message)
            CafCommand.WRITE_REQUEST -> handler.onWriteRequest(session, message)
            CafCommand.WRITE_RESPONSE -> handler.onWriteResponse(session, message)
            CafCommand.CONTROL_NOTIFY -> handler.onControlNotify(session, message)
            CafCommand.CONTROL_REQUEST -> handler.onControlRequest(session, message)
            CafCommand.CONTROL_RESPONSE -> handler.onControlResponse(session, message)
            CafCommand.UPDATE_NOTIFY -> handler.onUpdateNotify(session, message)
            CafCommand.GENERAL_ERROR -> handler.onGeneralError(session, message)
        }
    }
}
