package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamLimits
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsPayloadStyle
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** CAF may connect both priority sockets before sending the first authenticated frame on one. */
internal fun rcsLimitsFor(clientType: RcsClientType): RcsDataStreamLimits =
    if (clientType.payloadStyle == RcsPayloadStyle.CAF_BINARY_PLIST_OPACK) {
        RcsDataStreamLimits(candidateAuthenticationTimeoutMillis = 30_000)
    } else {
        RcsDataStreamLimits()
    }

internal data class CarPlayDataStreamHandlers(
    val iap: ((BlockingDuplexByteStream) -> Boolean)?,
    val rcs: RcsDataStreamHandlerFactory?,
    val onIapFallback: (ByteArray) -> Unit,
)

/** Session-independent connection inputs captured when SETUP is prepared. */
internal class CarPlayDataStreamConnectionContext(
    sharedSecret: ByteArray,
    val bindAddress: InetAddress,
    val expectedPeerAddress: InetAddress,
    val iapBridgeFactory: CarPlayIapBridgeFactory? = null,
    private val debugLog: (String) -> Unit = {},
    private val traceLog: (String) -> Unit = {},
    val onTransportFailure: (Throwable?) -> Unit = {},
) {
    private val secret = sharedSecret.copyOf()

    init {
        require(secret.isNotEmpty()) { "AirPlay shared secret must not be empty" }
    }

    fun deriveKey(seed: String, label: String): ByteArray = AirPlayCrypto.hkdfSha512(
        secret,
        "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
        label.toByteArray(Charsets.US_ASCII),
        32,
    )

    fun debug(message: String) = runCatching { debugLog(sanitizeLog(message)) }.let { Unit }

    fun trace(message: String) = runCatching { traceLog(sanitizeLog(message)) }.let { Unit }

    private fun sanitizeLog(message: String): String =
        message.take(MAX_LOG_CHARS).filterNot(Char::isISOControl)

    private companion object {
        const val MAX_LOG_CHARS = 512
    }
}

internal fun interface CarPlayIapBridgeFactory {
    fun open(tunnel: IapTunnel): OpenedCarPlayIapBridge
}

internal data class OpenedCarPlayIapBridge(
    val port: Int,
    val stream: BlockingDuplexByteStream,
)

internal enum class CarPlayDataStreamKind {
    IAP_TUNNEL,
    RCS,
}

/**
 * Opt-in evidence for a one-shot CarPlayUpdateData handler's expected peer EOF.
 *
 * This must become true only after a terminal UpdateData ACK has actually been sent on the
 * authenticated stream and [RcsDataStream.send] has returned successfully. A complete download,
 * durable quarantine commit, queued ACK, failed send, or local close alone is not enough. The
 * value must be monotonic; the transport may read it from a different thread. No handler is
 * enrolled merely because its RCS client type is CarPlayUpdateData.
 */
internal interface CarPlayUpdateDataTerminalAckEvidence {
    val terminalAckSent: Boolean
}

/**
 * A prepared data stream owned by one AirPlay SETUP transaction.
 *
 * [commit] attaches the application handler only after the response is accepted for emission.
 * [rollback] closes every prepared resource when the response is rejected or superseded. Both are
 * idempotent, which lets later CSeq fencing call either hook safely from cleanup paths.
 */
internal class OpenedCarPlayDataStream(
    val port: Int,
    val closeable: Closeable,
    private val commitHook: () -> Boolean,
    private val rollbackHook: () -> Unit = closeable::close,
) {
    private enum class State {
        PREPARED,
        COMMITTED,
        ROLLBACK_REQUESTED,
        ROLLED_BACK,
    }

    private var state = State.PREPARED

    @Synchronized
    fun commit(): Boolean = when (state) {
        State.COMMITTED -> true
        State.ROLLBACK_REQUESTED -> false
        State.ROLLED_BACK -> false
        State.PREPARED -> {
            val committed = runCatching(commitHook).getOrDefault(false)
            if (committed) {
                state = State.COMMITTED
                true
            } else {
                state = State.ROLLBACK_REQUESTED
                if (runCatching(rollbackHook).isSuccess) state = State.ROLLED_BACK
                false
            }
        }
    }

    @Synchronized
    fun rollback() {
        if (state == State.ROLLED_BACK) return
        state = State.ROLLBACK_REQUESTED
        val result = runCatching(rollbackHook)
        if (result.isSuccess) state = State.ROLLED_BACK
        result.getOrThrow()
    }

    /** Compatibility alias for the pre-commit integration point on the donor branch. */
    val attach: () -> Boolean = ::commit
}

/** Creates type-130 data streams without coupling the transport implementation to a session. */
internal class CarPlayDataStreamFactory {
    fun open(
        context: CarPlayDataStreamConnectionContext,
        clientType: RcsClientType,
        seed: String,
        handlers: CarPlayDataStreamHandlers,
    ): OpenedCarPlayDataStream? {
        require(seed.length <= MAX_SEED_CHARS) { "DataStream seed is too long" }
        val outputKey = context.deriveKey(seed, DATASTREAM_OUTPUT_KEY)
        val inputKey = context.deriveKey(seed, DATASTREAM_INPUT_KEY)
        return when (kindFor(clientType)) {
            CarPlayDataStreamKind.IAP_TUNNEL -> openIap(context, outputKey, handlers)
            CarPlayDataStreamKind.RCS ->
                openRcs(context, clientType, outputKey, inputKey, handlers.rcs)
        }
    }

    /** Transitional adapter; new integrations should capture and pass the neutral context. */
    fun open(
        session: AirPlaySession,
        clientType: RcsClientType,
        seed: String,
        handlers: CarPlayDataStreamHandlers,
    ): OpenedCarPlayDataStream? {
        val shared = session.sharedSecret ?: return null
        val expectedPeer = session.remoteAddress ?: return null
        val local = session.localAddress?.takeUnless { it.isAnyLocalAddress } ?: return null
        return open(
            context = CarPlayDataStreamConnectionContext(
                sharedSecret = shared,
                bindAddress = local,
                expectedPeerAddress = expectedPeer,
                iapBridgeFactory = CarPlayIapBridgeFactory { tunnel ->
                    val bridge = AirPlayIapTunnelStream(session, tunnel)
                    OpenedCarPlayIapBridge(bridge.listen(), bridge)
                },
                debugLog = session::logDebug,
                traceLog = session::logTrace,
                onTransportFailure = { cause -> if (cause != null) session.close() },
            ),
            clientType = clientType,
            seed = seed,
            handlers = handlers,
        )
    }

    internal fun kindFor(clientType: RcsClientType): CarPlayDataStreamKind =
        when (clientType.payloadStyle) {
            RcsPayloadStyle.RAW_IAP2 -> CarPlayDataStreamKind.IAP_TUNNEL
            RcsPayloadStyle.CAF_BINARY_PLIST_OPACK,
            RcsPayloadStyle.UI_SYNC_PLIST,
            RcsPayloadStyle.UPDATE_DATA_OPACK,
            RcsPayloadStyle.MATCH_POINT_BINARY_PLIST,
            RcsPayloadStyle.UNCONFIRMED_OPAQUE,
            -> CarPlayDataStreamKind.RCS
        }

    private fun openIap(
        context: CarPlayDataStreamConnectionContext,
        outputKey: ByteArray,
        handlers: CarPlayDataStreamHandlers,
    ): OpenedCarPlayDataStream? {
        val tunnel = IapTunnel(
            readKey = outputKey,
            bindAddress = context.bindAddress,
            exactBind = true,
            expectedPeerAddress = context.expectedPeerAddress,
        )
        val applicationHandler = handlers.iap
        if (applicationHandler == null) {
            val port = try {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = handlers.onIapFallback(bytes)

                        override fun onDebug(message: String) = context.trace(message)

                        override fun onClosed(cause: Throwable?) {
                            context.trace(
                                "AirPlay iAP tunnel ended cause=" +
                                    (cause?.javaClass?.simpleName ?: "peer_eof"),
                            )
                            context.onTransportFailure(cause)
                        }
                    },
                )
            } catch (failure: Throwable) {
                tunnel.close()
                throw failure
            }
            return OpenedCarPlayDataStream(port, tunnel, commitHook = { true })
        }

        val bridgeFactory = context.iapBridgeFactory
        if (bridgeFactory == null) {
            tunnel.close()
            context.debug("AirPlay iAP SETUP rejected: no full-duplex bridge")
            return null
        }
        val bridge = try {
            bridgeFactory.open(tunnel)
        } catch (failure: Throwable) {
            tunnel.close()
            throw failure
        }
        return OpenedCarPlayDataStream(
            port = bridge.port,
            closeable = bridge.stream,
            commitHook = {
                try {
                    applicationHandler(bridge.stream)
                } catch (failure: Throwable) {
                    context.debug(
                        "AirPlay iAP relay attachment failed cause=${safeFailure(failure)}",
                    )
                    false
                }
            },
        )
    }

    private fun openRcs(
        context: CarPlayDataStreamConnectionContext,
        clientType: RcsClientType,
        outputKey: ByteArray,
        inputKey: ByteArray,
        handlerFactory: RcsDataStreamHandlerFactory?,
    ): OpenedCarPlayDataStream? {
        val descriptor = handlerFactory?.descriptor(clientType)
        if (descriptor == null) {
            context.debug(
                "AirPlay RCS SETUP rejected clientType=${safeName(clientType.name)} " +
                    "reason=no_descriptor",
            )
            return null
        }
        if (descriptor.clientType != clientType) {
            context.debug(
                "AirPlay RCS SETUP rejected clientType=${safeName(clientType.name)} " +
                    "reason=descriptor_mismatch",
            )
            return null
        }
        val probe = descriptor.probe()
        if (!probe.isReady) {
            context.debug(
                "AirPlay RCS SETUP rejected clientType=${safeName(clientType.name)} " +
                    "reason=${probe.reasonCode ?: "not_ready"}",
            )
            return null
        }

        val stream = RcsDataStream.open(
            clientType = clientType,
            readKey = outputKey,
            writeKey = inputKey,
            bindAddress = context.bindAddress,
            expectedPeerAddress = context.expectedPeerAddress,
            limits = rcsLimitsFor(clientType),
            onTrace = context::trace,
        )
        val handler = try {
            descriptor.create(stream)
        } catch (failure: Throwable) {
            stream.close()
            context.debug(
                "AirPlay RCS SETUP rejected clientType=${safeName(clientType.name)} " +
                    "reason=create_failed cause=${safeFailure(failure)}",
            )
            return null
        }
        val ownedHandler = CloseOnceRcsDataStreamHandler(
            delegate = handler,
            expectedStream = stream,
            onUnexpectedClose = { cause ->
                context.onTransportFailure(
                    cause ?: EOFException("RCS peer ended the authenticated stream"),
                )
            },
        )
        val port = try {
            stream.listen()
        } catch (failure: Throwable) {
            stream.close()
            ownedHandler.close()
            throw failure
        }
        val resource = RetryableRcsResource(stream, ownedHandler)
        return OpenedCarPlayDataStream(
            port = port,
            closeable = resource,
            commitHook = {
                try {
                    stream.attach(ownedHandler)
                    true
                } catch (failure: Throwable) {
                    context.debug(
                        "AirPlay RCS handler attachment failed " +
                            "clientType=${safeName(clientType.name)} " +
                            "cause=${safeFailure(failure)}",
                    )
                    false
                }
            },
        )
    }

    private class CloseOnceRcsDataStreamHandler(
        private val delegate: RcsDataStreamHandler,
        private val expectedStream: RcsDataStream,
        private val onUnexpectedClose: (Throwable?) -> Unit,
    ) : RcsDataStreamHandler {
        private val closeLock = Any()
        private var closeInFlight = false
        private var closed = false
        private val expectedClose = AtomicBoolean(false)
        private val failureSignaled = AtomicBoolean(false)

        fun expectClose() {
            expectedClose.set(true)
        }

        override fun onStreamOpened(stream: RcsDataStream) = delegate.onStreamOpened(stream)

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) =
            delegate.onMessage(stream, message)

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            var delegateClosedNormally = false
            try {
                delegate.onStreamClosed(stream, cause)
                delegateClosedNormally = true
            } finally {
                val expectedPeerEof = delegateClosedNormally &&
                    stream === expectedStream &&
                    stream.clientType == RcsClientTypes.CAR_PLAY_UPDATE_DATA &&
                    cause == null &&
                    runCatching {
                        (delegate as? CarPlayUpdateDataTerminalAckEvidence)
                            ?.terminalAckSent == true
                    }.getOrDefault(false)
                if (!expectedClose.get() && !expectedPeerEof &&
                    failureSignaled.compareAndSet(false, true)
                ) {
                    runCatching { onUnexpectedClose(cause) }
                }
            }
        }

        override fun close() {
            synchronized(closeLock) {
                if (closed) return
                check(!closeInFlight) { "RCS handler close is already in flight" }
                closeInFlight = true
            }
            val result = runCatching { delegate.close() }
            synchronized(closeLock) {
                closeInFlight = false
                if (result.isSuccess) closed = true
            }
            result.getOrThrow()
        }
    }

    /** Transport and handler retirement are independently remembered and retried in order. */
    private class RetryableRcsResource(
        private val stream: RcsDataStream,
        private val handler: CloseOnceRcsDataStreamHandler,
    ) : Closeable {
        private val lock = Any()
        private var closeInFlight = false
        private var streamClosed = false
        private var handlerClosed = false

        override fun close() {
            synchronized(lock) {
                if (streamClosed && handlerClosed) return
                check(!closeInFlight) { "RCS resource close is already in flight" }
                closeInFlight = true
            }
            var failure: Throwable? = null
            handler.expectClose()
            if (!synchronized(lock) { streamClosed }) {
                try {
                    stream.close()
                    synchronized(lock) { streamClosed = true }
                } catch (problem: Throwable) {
                    failure = problem
                }
            }
            if (failure == null && !synchronized(lock) { handlerClosed }) {
                try {
                    handler.close()
                    synchronized(lock) { handlerClosed = true }
                } catch (problem: Throwable) {
                    failure = problem
                }
            }
            synchronized(lock) { closeInFlight = false }
            failure?.let { throw it }
        }
    }

    private fun safeName(value: String): String =
        value.take(64).filter { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun safeFailure(failure: Throwable): String {
        return failure.javaClass.simpleName.take(64).ifEmpty { "Throwable" }
    }

    private companion object {
        const val MAX_SEED_CHARS = 256
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
    }
}
