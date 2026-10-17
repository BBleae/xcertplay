package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayDataStreamFactoryTest {
    private val factory = CarPlayDataStreamFactory()
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun clientTypeCatalogSelectsIapAndSharedRcsStrategies() {
        assertEquals(
            CarPlayDataStreamKind.IAP_TUNNEL,
            factory.kindFor(RcsClientTypes.IAP_CHANNEL),
        )
        assertEquals(
            CarPlayDataStreamKind.RCS,
            factory.kindFor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA),
        )
        assertEquals(
            CarPlayDataStreamKind.RCS,
            factory.kindFor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2),
        )
        assertEquals(
            CarPlayDataStreamKind.RCS,
            factory.kindFor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        )
    }

    @Test
    fun idleCafPrioritySocketGetsBoundedAuthenticationGraceWithoutEarlyPromotion() {
        assertEquals(30_000L, rcsLimitsFor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA)
            .candidateAuthenticationTimeoutMillis)
        assertEquals(30_000L, rcsLimitsFor(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2)
            .candidateAuthenticationTimeoutMillis)
        assertEquals(3_000L, rcsLimitsFor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
            .candidateAuthenticationTimeoutMillis)
    }

    @Test
    fun descriptorMustExistAndItsLiveProbeMustBeReady() {
        assertNull(openRcs(handlerFactory = null))

        val notReady = RcsDataStreamHandlerFactory { clientType ->
            RcsDataStreamHandlerDescriptor(
                clientType = clientType,
                implementationId = "not-ready",
                readinessProbe = {
                    RcsDataStreamHandlerProbe.notReady("missing_validated_config")
                },
                handlerFactory = { object : RcsDataStreamHandler {} },
            )
        }
        assertNull(openRcs(notReady))
    }

    @Test
    fun descriptorForAnotherClientTypeIsRejectedBeforeBinding() {
        val mismatch = RcsDataStreamHandlerFactory {
            RcsDataStreamHandlerDescriptor.ready(
                clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                implementationId = "wrong-type",
                handlerFactory = { object : RcsDataStreamHandler {} },
            )
        }
        assertNull(openRcs(mismatch))
    }

    @Test
    fun preparedStreamCommitAndRollbackHooksAreIdempotent() {
        val closeCount = AtomicInteger()
        val commitCount = AtomicInteger()
        val closeable = Closeable { closeCount.incrementAndGet() }
        val opened = OpenedCarPlayDataStream(
            port = 1234,
            closeable = closeable,
            commitHook = {
                commitCount.incrementAndGet()
                true
            },
        )

        assertTrue(opened.commit())
        assertTrue(opened.commit())
        assertEquals(1, commitCount.get())
        assertEquals(0, closeCount.get())
        opened.rollback()
        opened.rollback()
        assertEquals(1, closeCount.get())
        assertFalse(opened.commit())
    }

    @Test
    fun failedCommitAutomaticallyRollsBackOnlyOnce() {
        val closeCount = AtomicInteger()
        val opened = OpenedCarPlayDataStream(
            port = 1234,
            closeable = Closeable { closeCount.incrementAndGet() },
            commitHook = { false },
        )
        assertFalse(opened.commit())
        assertFalse(opened.commit())
        opened.rollback()
        assertEquals(1, closeCount.get())
    }

    @Test
    fun preparedRollbackMarksCompleteOnlyAfterCloseSucceeds() {
        val closeCount = AtomicInteger()
        val opened = OpenedCarPlayDataStream(
            port = 1234,
            closeable = Closeable {
                if (closeCount.incrementAndGet() == 1) throw IllegalStateException("retry")
            },
            commitHook = { true },
        )

        assertThrows(IllegalStateException::class.java) { opened.rollback() }
        opened.rollback()
        opened.rollback()
        assertEquals(2, closeCount.get())
    }

    @Test
    fun factoryIapPathUsesExactBindAndExpectedPeerFiltering() {
        val expectedPeer = InetAddress.getByName("192.0.2.1")
        val opened = factory.open(
            context = CarPlayDataStreamConnectionContext(
                sharedSecret = ByteArray(32) { 9 },
                bindAddress = loopback,
                expectedPeerAddress = expectedPeer,
            ),
            clientType = RcsClientTypes.IAP_CHANNEL,
            seed = "iap-seed",
            handlers = CarPlayDataStreamHandlers(
                iap = null,
                rcs = null,
                onIapFallback = {},
            ),
        )!!
        val tunnel = opened.closeable as IapTunnel
        try {
            Socket().use { wrong ->
                wrong.bind(InetSocketAddress(loopback, 0))
                wrong.connect(InetSocketAddress(loopback, opened.port), 2_000)
            }
            assertFalse(tunnel.awaitPeerConnection(100))
        } finally {
            opened.rollback()
        }
    }

    @Test
    fun rejectedAndCommittedRcsSetupsCloseCreatedHandlersExactlyOnce() {
        listOf(false, true).forEach { commitBeforeRollback ->
            val closeCount = AtomicInteger()
            val handlers = RcsDataStreamHandlerFactory { clientType ->
                RcsDataStreamHandlerDescriptor.ready(
                    clientType = clientType,
                    implementationId = "close-tracked",
                    handlerFactory = {
                        object : RcsDataStreamHandler {
                            override fun close() {
                                closeCount.incrementAndGet()
                            }
                        }
                    },
                )
            }
            val opened = openRcs(handlers)!!
            if (commitBeforeRollback) assertTrue(opened.commit())
            opened.rollback()
            opened.rollback()
            assertEquals(1, closeCount.get())
        }
    }

    @Test
    fun rcsResourceRetriesTheSameHandlerAfterCloseFailure() {
        val closeCalls = AtomicInteger()
        val opened = openRcs(
            RcsDataStreamHandlerFactory { clientType ->
                RcsDataStreamHandlerDescriptor.ready(
                    clientType,
                    "retry-close-handler",
                ) {
                    object : RcsDataStreamHandler {
                        override fun close() {
                            if (closeCalls.incrementAndGet() == 1) {
                                throw IllegalStateException("retry")
                            }
                        }
                    }
                }
            },
        )!!

        assertThrows(IllegalStateException::class.java) { opened.closeable.close() }
        opened.closeable.close()
        assertEquals(2, closeCalls.get())
    }

    @Test
    fun unexpectedAuthenticatedPeerEofSignalsTransportFailureExactlyOnce() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failureSignal = CountDownLatch(1)
        val messageReceived = CountDownLatch(1)
        val seed = "unexpected-eof"
        val sharedSecret = ByteArray(32) { 17 }
        val opened = openTrackedRcs(
            seed = seed,
            sharedSecret = sharedSecret,
            messageReceived = messageReceived,
            onTransportFailure = {
                failures += it
                failureSignal.countDown()
            },
        )
        assertTrue(opened.commit())
        val client = connectFactoryClient(opened.port, sharedSecret, seed)
        client.sendComm(byteArrayOf(1))
        assertTrue(messageReceived.await(2, TimeUnit.SECONDS))
        client.close()

        assertTrue(failureSignal.await(2, TimeUnit.SECONDS))
        opened.rollback()
        assertEquals(1, failures.size)
        assertTrue(failures.single() is java.io.EOFException)
    }

    @Test
    fun authenticatedFrameTamperSignalsTransportFailureExactlyOnce() {
        val failureCount = AtomicInteger()
        val failureSignal = CountDownLatch(1)
        val messageReceived = CountDownLatch(1)
        val seed = "tampered-frame"
        val sharedSecret = ByteArray(32) { 18 }
        val opened = openTrackedRcs(
            seed = seed,
            sharedSecret = sharedSecret,
            messageReceived = messageReceived,
            onTransportFailure = {
                failureCount.incrementAndGet()
                failureSignal.countDown()
            },
        )
        assertTrue(opened.commit())

        val outputKey = deriveDataStreamKey(sharedSecret, seed, DATASTREAM_OUTPUT_KEY)
        val inputKey = deriveDataStreamKey(sharedSecret, seed, DATASTREAM_INPUT_KEY)
        val codec = RcsFrameCodec.duplex(inputKey, outputKey)
        val socket = Socket(loopback, opened.port)
        try {
            socket.getOutputStream().apply {
                write(codec.encrypt(RcsMessage.comm(byteArrayOf(1)).encoded()))
                flush()
            }
            assertTrue(messageReceived.await(2, TimeUnit.SECONDS))
            val tampered = codec.encrypt(RcsMessage.comm(byteArrayOf(2)).encoded())
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            socket.getOutputStream().apply {
                write(tampered)
                flush()
            }
            assertTrue(failureSignal.await(2, TimeUnit.SECONDS))
        } finally {
            socket.close()
            opened.rollback()
        }
        assertEquals(1, failureCount.get())
    }

    @Test
    fun expectedRollbackNeverSignalsTransportFailure() {
        val failureCount = AtomicInteger()
        val opened = openTrackedRcs(
            seed = "expected-close",
            sharedSecret = ByteArray(32) { 19 },
            messageReceived = CountDownLatch(1),
            onTransportFailure = { failureCount.incrementAndGet() },
        )
        assertTrue(opened.commit())
        opened.rollback()
        opened.rollback()
        assertEquals(0, failureCount.get())
    }

    @Test
    fun updateDataCleanPeerEofAfterSentTerminalAckDoesNotEndAirPlay() {
        val failureCount = AtomicInteger()
        val handler = TerminalAckEvidenceHandler(sendTerminalAck = true)
        val seed = "update-data-terminal-ack"
        val secret = ByteArray(32) { 25 }
        val opened = openTerminalEvidenceRcs(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            sharedSecret = secret,
            handler = handler,
            onTransportFailure = { failureCount.incrementAndGet() },
        )
        assertTrue(opened.commit())
        val client = connectFactoryClient(opened.port, secret, seed)
        try {
            client.sendComm(byteArrayOf(1))
            assertTrue(handler.messageReceived.await(2, TimeUnit.SECONDS))
            assertEquals(5, client.receiveNext(2_000)?.body?.single()?.toInt())
            client.close()
            assertTrue(handler.streamClosed.await(2, TimeUnit.SECONDS))
            assertTrue(handler.terminalAckSent)
            assertEquals(0, failureCount.get())
        } finally {
            client.close()
            opened.rollback()
        }
        assertEquals(0, failureCount.get())
    }

    @Test
    fun updateDataPeerEofBeforeTerminalAckStillEndsAirPlay() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failed = CountDownLatch(1)
        val handler = TerminalAckEvidenceHandler(sendTerminalAck = false)
        val seed = "update-data-premature-eof"
        val secret = ByteArray(32) { 26 }
        val opened = openTerminalEvidenceRcs(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            sharedSecret = secret,
            handler = handler,
            onTransportFailure = { failures += it; failed.countDown() },
        )
        assertTrue(opened.commit())
        val client = connectFactoryClient(opened.port, secret, seed)
        try {
            client.sendComm(byteArrayOf(1))
            assertTrue(handler.messageReceived.await(2, TimeUnit.SECONDS))
            client.close()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(failures.single() is java.io.EOFException)
        } finally {
            client.close()
            opened.rollback()
        }
    }

    @Test
    fun updateDataTypeWithoutTerminalAckEvidenceStillEndsAirPlay() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failed = CountDownLatch(1)
        val messageReceived = CountDownLatch(1)
        val seed = "update-data-no-evidence"
        val secret = ByteArray(32) { 30 }
        val opened = openTerminalEvidenceRcs(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            sharedSecret = secret,
            handler = object : RcsDataStreamHandler {
                override fun onMessage(
                    stream: com.shilapi.xcertplay.airplay.rcs.RcsDataStream,
                    message: RcsMessage,
                ) {
                    messageReceived.countDown()
                }
            },
            onTransportFailure = { failures += it; failed.countDown() },
        )
        assertTrue(opened.commit())
        val client = connectFactoryClient(opened.port, secret, seed)
        try {
            client.sendComm(byteArrayOf(1))
            assertTrue(messageReceived.await(2, TimeUnit.SECONDS))
            client.close()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(failures.single() is java.io.EOFException)
        } finally {
            client.close()
            opened.rollback()
        }
    }

    @Test
    fun terminalAckEvidenceCannotSuppressOtherRcsClientTypes() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failed = CountDownLatch(1)
        val handler = TerminalAckEvidenceHandler(sendTerminalAck = true)
        val seed = "non-update-terminal-ack"
        val secret = ByteArray(32) { 27 }
        val opened = openTerminalEvidenceRcs(
            clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            seed = seed,
            sharedSecret = secret,
            handler = handler,
            onTransportFailure = { failures += it; failed.countDown() },
        )
        assertTrue(opened.commit())
        val client = connectFactoryClient(opened.port, secret, seed)
        try {
            client.sendComm(byteArrayOf(1))
            assertTrue(handler.messageReceived.await(2, TimeUnit.SECONDS))
            assertEquals(5, client.receiveNext(2_000)?.body?.single()?.toInt())
            client.close()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(failures.single() is java.io.EOFException)
        } finally {
            client.close()
            opened.rollback()
        }
    }

    @Test
    fun terminalAckEvidenceCannotSuppressAuthenticationFailureAfterAck() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failed = CountDownLatch(1)
        val handler = TerminalAckEvidenceHandler(sendTerminalAck = true)
        val seed = "update-data-tamper-after-ack"
        val secret = ByteArray(32) { 28 }
        val opened = openTerminalEvidenceRcs(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            sharedSecret = secret,
            handler = handler,
            onTransportFailure = { failures += it; failed.countDown() },
        )
        assertTrue(opened.commit())
        val codec = RcsFrameCodec.duplex(
            deriveDataStreamKey(secret, seed, DATASTREAM_INPUT_KEY),
            deriveDataStreamKey(secret, seed, DATASTREAM_OUTPUT_KEY),
        )
        val socket = Socket(loopback, opened.port)
        try {
            socket.getOutputStream().apply {
                write(codec.encrypt(RcsMessage.comm(byteArrayOf(1)).encoded()))
                flush()
            }
            assertTrue(handler.messageReceived.await(2, TimeUnit.SECONDS))
            assertTrue(handler.terminalAckSent)
            val tampered = codec.encrypt(RcsMessage.comm(byteArrayOf(2)).encoded())
            tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
            socket.getOutputStream().apply {
                write(tampered)
                flush()
            }
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(failures.single() is com.shilapi.xcertplay.airplay.rcs.RcsProtocolException)
        } finally {
            socket.close()
            opened.rollback()
        }
    }

    @Test
    fun terminalAckEvidenceCannotSuppressDelegateCloseFailure() {
        val failures = CopyOnWriteArrayList<Throwable?>()
        val failed = CountDownLatch(1)
        val handler = TerminalAckEvidenceHandler(sendTerminalAck = true, failOnStreamClosed = true)
        val seed = "update-data-close-failed"
        val secret = ByteArray(32) { 29 }
        val opened = openTerminalEvidenceRcs(
            clientType = RcsClientTypes.CAR_PLAY_UPDATE_DATA,
            seed = seed,
            sharedSecret = secret,
            handler = handler,
            onTransportFailure = { failures += it; failed.countDown() },
        )
        assertTrue(opened.commit())
        val client = connectFactoryClient(opened.port, secret, seed)
        try {
            client.sendComm(byteArrayOf(1))
            assertTrue(handler.messageReceived.await(2, TimeUnit.SECONDS))
            assertEquals(5, client.receiveNext(2_000)?.body?.single()?.toInt())
            client.close()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(failures.single() is java.io.EOFException)
        } finally {
            client.close()
            opened.rollback()
        }
    }

    private fun openTerminalEvidenceRcs(
        clientType: RcsClientType,
        seed: String,
        sharedSecret: ByteArray,
        handler: RcsDataStreamHandler,
        onTransportFailure: (Throwable?) -> Unit,
    ): OpenedCarPlayDataStream = factory.open(
        context = CarPlayDataStreamConnectionContext(
            sharedSecret = sharedSecret,
            bindAddress = loopback,
            expectedPeerAddress = loopback,
            onTransportFailure = onTransportFailure,
        ),
        clientType = clientType,
        seed = seed,
        handlers = CarPlayDataStreamHandlers(
            iap = null,
            rcs = RcsDataStreamHandlerFactory { requested ->
                if (requested != clientType) null else RcsDataStreamHandlerDescriptor.ready(
                    clientType = clientType,
                    implementationId = "terminal-ack-test",
                    handlerFactory = { handler },
                )
            },
            onIapFallback = {},
        ),
    )!!

    private class TerminalAckEvidenceHandler(
        private val sendTerminalAck: Boolean,
        private val failOnStreamClosed: Boolean = false,
    ) : RcsDataStreamHandler, CarPlayUpdateDataTerminalAckEvidence {
        val messageReceived = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)

        @Volatile
        override var terminalAckSent = false
            private set

        override fun onMessage(
            stream: com.shilapi.xcertplay.airplay.rcs.RcsDataStream,
            message: RcsMessage,
        ) {
            if (sendTerminalAck) {
                stream.send(RcsMessage.comm(byteArrayOf(5)))
                terminalAckSent = true
            }
            messageReceived.countDown()
        }

        override fun onStreamClosed(
            stream: com.shilapi.xcertplay.airplay.rcs.RcsDataStream,
            cause: Throwable?,
        ) {
            streamClosed.countDown()
            if (failOnStreamClosed) throw IllegalStateException("handler close failed")
        }
    }

    private fun openRcs(
        handlerFactory: RcsDataStreamHandlerFactory?,
    ): OpenedCarPlayDataStream? = factory.open(
        context = CarPlayDataStreamConnectionContext(
            sharedSecret = ByteArray(32) { 9 },
            bindAddress = loopback,
            expectedPeerAddress = loopback,
        ),
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        seed = "test-seed",
        handlers = CarPlayDataStreamHandlers(
            iap = null,
            rcs = handlerFactory,
            onIapFallback = {},
        ),
    )

    private fun openTrackedRcs(
        seed: String,
        sharedSecret: ByteArray,
        messageReceived: CountDownLatch,
        onTransportFailure: (Throwable?) -> Unit,
    ): OpenedCarPlayDataStream = factory.open(
        context = CarPlayDataStreamConnectionContext(
            sharedSecret = sharedSecret,
            bindAddress = loopback,
            expectedPeerAddress = loopback,
            onTransportFailure = onTransportFailure,
        ),
        clientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
        seed = seed,
        handlers = CarPlayDataStreamHandlers(
            iap = null,
            rcs = RcsDataStreamHandlerFactory { clientType ->
                RcsDataStreamHandlerDescriptor.ready(
                    clientType = clientType,
                    implementationId = "tracked-handler",
                    handlerFactory = {
                        object : RcsDataStreamHandler {
                            override fun onMessage(
                                stream: com.shilapi.xcertplay.airplay.rcs.RcsDataStream,
                                message: RcsMessage,
                            ) {
                                messageReceived.countDown()
                            }
                        }
                    },
                )
            },
            onIapFallback = {},
        ),
    )!!

    private fun connectFactoryClient(
        port: Int,
        sharedSecret: ByteArray,
        seed: String,
    ): com.shilapi.xcertplay.airplay.rcs.RcsChannel =
        com.shilapi.xcertplay.airplay.rcs.RcsChannel.connect(
            remote = InetSocketAddress(loopback, port),
            readKey = deriveDataStreamKey(sharedSecret, seed, DATASTREAM_INPUT_KEY),
            writeKey = deriveDataStreamKey(sharedSecret, seed, DATASTREAM_OUTPUT_KEY),
        )

    private fun deriveDataStreamKey(sharedSecret: ByteArray, seed: String, label: String): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )

    private companion object {
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
    }
}
