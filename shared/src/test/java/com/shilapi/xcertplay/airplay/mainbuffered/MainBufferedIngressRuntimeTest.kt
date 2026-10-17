package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedIngressRuntimeTest {
    @Test
    fun fragmentedAndCoalescedFramesTraverseTheExactCodecPipelineInOrder() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val first = framed(writer, sequence = 10, payload = byteArrayOf(1, 2))
        val second = framed(writer, sequence = 11, payload = byteArrayOf(3, 4, 5))
        val wire = first + second
        val stream = ScriptedStream().apply {
            enqueue(wire.copyOfRange(0, 1))
            enqueue(wire.copyOfRange(1, 3))
            enqueue(wire.copyOfRange(3, first.size + 7))
            enqueue(wire.copyOfRange(first.size + 7, wire.size))
            enqueueEof()
        }
        val packets = Collections.synchronizedList(ArrayList<ApapPacket>())
        val callbackIdentities = Collections.synchronizedList(
            ArrayList<MainBufferedIngressIdentity>(),
        )
        val identity = MainBufferedIngressIdentity.create(41)
        val runtime = runtime(identity, stream, key) { session, packet ->
            callbackIdentities += session.identity
            packets += packet
        }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(listOf(10, 11), packets.map { it.header.sequence })
        assertArrayEquals(byteArrayOf(1, 2), packets[0].mediaPayload)
        assertArrayEquals(byteArrayOf(3, 4, 5), packets[1].mediaPayload)
        assertTrue(callbackIdentities.all { it === identity })
        assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF, runtime.snapshot().terminationReason)
        assertEquals(MainBufferedIngressCloseState.COMPLETE, runtime.snapshot().closeState)
        writer.close()
    }

    @Test
    fun cleanEofIsReportedOnlyAfterQueuedCallbacksDrain() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = ScriptedStream().apply {
            enqueue(framed(writer, sequence = 1))
            enqueueEof()
        }
        val runtime = runtime(MainBufferedIngressIdentity.create(1), stream, key) { _, _ ->
            entered.countDown()
            assertTrue(release.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        }

        runtime.start()
        assertTrue(entered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertFalse(runtime.awaitTerminal(50))
        assertEquals(MainBufferedIngressState.RUNNING, runtime.snapshot().state)

        release.countDown()
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF, runtime.snapshot().terminationReason)
        writer.close()
    }

    @Test
    fun oversizedLengthPrefixIsTerminalWithoutAllocatingTheDeclaredBody() {
        val key = ByteArray(32)
        val stream = ScriptedStream().apply {
            enqueue(byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        }
        val runtime = runtime(MainBufferedIngressIdentity.create(2), stream, key) { _, _ ->
            throw AssertionError("oversized frame must not be delivered")
        }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        val snapshot = runtime.snapshot()
        assertEquals(MainBufferedIngressTerminationReason.PROTOCOL_FAILURE, snapshot.terminationReason)
        assertTrue(runtime.terminalFailureForTest()!!.message!!.contains("exceeds"))
        assertTrue(snapshot.keyZeroized)
    }

    @Test
    fun eofInsidePrefixAndEofInsideBodyAreTerminalTruncation() {
        val key = ByteArray(32)
        val prefixOnly = ScriptedStream().apply {
            enqueue(byteArrayOf(0, 0, 0))
            enqueueEof()
        }
        val bodyPartial = ScriptedStream().apply {
            enqueue(byteArrayOf(0, 0, 0, 44, 1, 2, 3))
            enqueueEof()
        }

        listOf(prefixOnly, bodyPartial).forEachIndexed { index, stream ->
            val runtime = runtime(
                MainBufferedIngressIdentity.create(10L + index),
                stream,
                key,
            ) { _, _ -> throw AssertionError("truncated frame must not be delivered") }
            runtime.start()
            assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
            assertEquals(
                MainBufferedIngressTerminationReason.PROTOCOL_FAILURE,
                runtime.snapshot().terminationReason,
            )
            assertTrue(runtime.terminalFailureForTest()!!.message!!.contains("truncated"))
        }
    }

    @Test
    fun authenticationFailureIsTerminalAndRetiresThePrivateKeyCopy() {
        val key = ByteArray(32) { it.toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val corrupted = framed(writer, sequence = 5).also { frame ->
            val ciphertextOffset = BufferedApapTcpCodec.PREFIX_BYTES +
                EncryptedApapPacketCodec.CLEAR_HEADER_BYTES
            frame[ciphertextOffset] = (frame[ciphertextOffset].toInt() xor 1).toByte()
        }
        val stream = ScriptedStream().apply { enqueue(corrupted) }
        val runtime = runtime(MainBufferedIngressIdentity.create(20), stream, key) { _, _ ->
            throw AssertionError("unauthenticated frame must not be delivered")
        }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(
            MainBufferedIngressTerminationReason.AUTHENTICATION_FAILURE,
            runtime.snapshot().terminationReason,
        )
        assertTrue(runtime.snapshot().keyZeroized)
        writer.close()
    }

    @Test
    fun authenticatedAdmissionObservesIngressTimeBeforeDelayedDelivery() {
        val key = ByteArray(32) { (it + 12).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val stream = ScriptedStream().apply {
            enqueue(framed(writer, sequence = 6))
            enqueueEof()
        }
        val deliveryThread = AtomicReference<Thread>()
        val observedAt = Collections.synchronizedList(ArrayList<Long>())
        val delivered = AtomicInteger()
        val runtime = runtime(
            MainBufferedIngressIdentity.create(101),
            stream,
            key,
            workerStarter = { worker ->
                if (worker.name.contains("delivery")) deliveryThread.set(worker)
                else worker.start()
            },
            authenticatedPacketAdmission = MainBufferedAuthenticatedPacketAdmission {
                observedAt += it
                true
            },
            authenticationClockNanos = { 123_456_789L },
        ) { _, _ -> delivered.incrementAndGet() }

        runtime.start()
        assertTrue(waitUntil { observedAt.size == 1 && runtime.snapshot().queuedPackets == 1 })
        assertEquals(listOf(123_456_789L), observedAt.toList())
        assertEquals(0, delivered.get())

        deliveryThread.get()!!.start()
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(1, delivered.get())
        assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF, runtime.snapshot().terminationReason)
        writer.close()
    }

    @Test
    fun rejectedAuthenticatedPacketNeverEntersBusinessQueue() {
        val key = ByteArray(32) { (it + 13).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val stream = ScriptedStream().apply { enqueue(framed(writer, sequence = 8)) }
        val delivered = AtomicInteger()
        val observedAt = AtomicReference<Long>()
        val runtime = runtime(
            MainBufferedIngressIdentity.create(102),
            stream,
            key,
            authenticatedPacketAdmission = MainBufferedAuthenticatedPacketAdmission {
                observedAt.set(it)
                false
            },
            authenticationClockNanos = { 987_654_321L },
        ) { _, _ -> delivered.incrementAndGet() }

        runtime.start()
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(987_654_321L, observedAt.get())
        assertEquals(0, delivered.get())
        assertEquals(0, runtime.snapshot().queuedPackets)
        assertEquals(
            MainBufferedIngressTerminationReason.ADMISSION_REJECTED,
            runtime.snapshot().terminationReason,
        )
        assertTrue(runtime.snapshot().keyZeroized)
        writer.close()
    }

    @Test
    fun replayedNonceIsTerminalAndNeverDeliveredTwice() {
        val key = ByteArray(32) { (31 - it).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val frame = framed(writer, sequence = 7)
        val delivered = AtomicInteger()
        val stream = ScriptedStream().apply { enqueue(frame + frame) }
        val runtime = runtime(MainBufferedIngressIdentity.create(21), stream, key) { _, _ ->
            delivered.incrementAndGet()
        }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(
            MainBufferedIngressTerminationReason.NONCE_SEQUENCE_FAILURE,
            runtime.snapshot().terminationReason,
        )
        assertTrue(delivered.get() <= 1)
        writer.close()
    }

    @Test
    fun maximumNoncePacketIsDeliveredThenTerminatesWithoutWrapping() {
        val key = ByteArray(32) { (it + 17).toByte() }
        val writer = EncryptedApapWriter(
            key,
            initialNonceCounter = -1L,
            maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4,
        )
        val delivered = ArrayList<Int>()
        val stream = ScriptedStream().apply { enqueue(framed(writer, sequence = 99)) }
        val runtime = runtime(
            identity = MainBufferedIngressIdentity.create(22),
            stream = stream,
            key = key,
            initialNonceCounter = -1L,
        ) { _, packet -> delivered += packet.header.sequence }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(listOf(99), delivered)
        assertEquals(
            MainBufferedIngressTerminationReason.NONCE_EXHAUSTED,
            runtime.snapshot().terminationReason,
        )
        writer.close()
    }

    @Test
    fun boundedQueueBackpressuresIngressWhileCallbackIsBlocked() {
        val key = ByteArray(32) { (it * 5).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val enteredFirst = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val delivered = Collections.synchronizedList(ArrayList<Int>())
        val stream = ScriptedStream().apply {
            enqueue(
                framed(writer, sequence = 1) +
                    framed(writer, sequence = 2) +
                    framed(writer, sequence = 3),
            )
            enqueueEof()
        }
        val runtime = runtime(
            MainBufferedIngressIdentity.create(30),
            stream,
            key,
            limits = testLimits(maximumQueuedPackets = 1),
        ) { _, packet ->
            delivered += packet.header.sequence
            if (packet.header.sequence == 1) {
                enteredFirst.countDown()
                assertTrue(releaseFirst.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            }
        }

        runtime.start()
        assertTrue(enteredFirst.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertTrue(waitUntil { runtime.snapshot().queuedPackets == 1 })
        assertEquals(1, runtime.snapshot().queuedPackets)
        assertEquals(listOf(1), delivered.toList())

        releaseFirst.countDown()
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(listOf(1, 2, 3), delivered.toList())
        writer.close()
    }

    @Test
    fun reentrantSelfCloseIsDeferredAndDoesNotReportFalseSuccess() {
        val key = ByteArray(32) { (it + 2).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val stream = ScriptedStream().apply { enqueue(framed(writer, sequence = 1)) }
        val closeFailure = arrayOfNulls<Throwable>(1)
        val runtime = runtime(MainBufferedIngressIdentity.create(31), stream, key) { session, _ ->
            closeFailure[0] = assertThrows(MainBufferedIngressCloseException::class.java) {
                session.close()
            }
        }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertTrue(closeFailure[0]!!.message!!.contains("cannot prove its own retirement"))
        assertEquals(MainBufferedIngressTerminationReason.LOCAL_CLOSE, runtime.snapshot().terminationReason)
        assertEquals(1, stream.successfulCloses.get())
        writer.close()
    }

    @Test
    fun failedTransportCloseRetainsTheExactOwnerForRetry() {
        val key = ByteArray(32)
        val stream = ScriptedStream(closeFailures = 1).apply { enqueueEof() }
        val runtime = runtime(MainBufferedIngressIdentity.create(32), stream, key) { _, _ -> }

        runtime.start()

        assertTrue(runtime.awaitTerminal(TEST_TIMEOUT_MILLIS))
        assertTrue(waitUntil {
            runtime.snapshot().closeState == MainBufferedIngressCloseState.FAILED
        })
        assertFalse(runtime.awaitClosed(20))
        assertEquals(1, stream.closeAttempts.get())

        runtime.close()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(2, stream.closeAttempts.get())
        assertEquals(1, stream.successfulCloses.get())
        assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF, runtime.snapshot().terminationReason)
    }

    @Test
    fun fatalTransportCloseIsRecordedAsFailedThenRethrownAndRetryable() {
        val key = ByteArray(32)
        val stream = ScriptedStream(fatalCloseFailures = 1)
        val runtime = runtime(MainBufferedIngressIdentity.create(36), stream, key) { _, _ -> }

        val fatal = assertThrows(AssertionError::class.java) { runtime.close() }

        assertSame(stream.fatalCloseError, fatal)
        assertEquals(MainBufferedIngressCloseState.FAILED, runtime.snapshot().closeState)
        assertSame(fatal, runtime.terminalFailureForTest())
        assertFalse(runtime.awaitClosed(20))

        runtime.close()
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(2, stream.closeAttempts.get())
        assertEquals(MainBufferedIngressTerminationReason.LOCAL_CLOSE, runtime.snapshot().terminationReason)
    }

    @Test
    fun startAndCloseAreOneSerializedWorkerOwnershipTransaction() {
        val key = ByteArray(32)
        val stream = ScriptedStream()
        val starterEntered = CountDownLatch(1)
        val releaseStarter = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val starts = AtomicInteger()
        val startFailure = arrayOfNulls<Throwable>(1)
        val closeFailure = arrayOfNulls<Throwable>(1)
        val runtime = runtime(
            MainBufferedIngressIdentity.create(37),
            stream,
            key,
            workerStarter = { worker ->
                if (starts.getAndIncrement() == 0) {
                    starterEntered.countDown()
                    assertTrue(
                        releaseStarter.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
                    )
                }
                worker.start()
            },
        ) { _, _ -> }
        val starting = Thread {
            try {
                runtime.start()
            } catch (problem: Throwable) {
                startFailure[0] = problem
            }
        }
        val closing = Thread {
            try {
                runtime.close()
            } catch (problem: Throwable) {
                closeFailure[0] = problem
            } finally {
                closeReturned.countDown()
            }
        }

        starting.start()
        assertTrue(starterEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        closing.start()
        assertTrue(waitUntil {
            runtime.snapshot().terminationReason == MainBufferedIngressTerminationReason.LOCAL_CLOSE
        })
        assertFalse(closeReturned.await(50, TimeUnit.MILLISECONDS))

        releaseStarter.countDown()
        starting.join(TEST_TIMEOUT_MILLIS)
        closing.join(TEST_TIMEOUT_MILLIS)

        assertFalse(starting.isAlive)
        assertFalse(closing.isAlive)
        assertEquals(2, starts.get())
        assertTrue(startFailure[0] == null)
        assertTrue(closeFailure[0] == null)
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertFalse(runtime.snapshot().ingressWorkerAlive)
        assertFalse(runtime.snapshot().deliveryWorkerAlive)
    }

    @Test
    fun workerConstructionFailureBecomesStartFailureAndRetiresOwners() {
        val key = ByteArray(32)
        val stream = ScriptedStream()
        val constructionCalls = AtomicInteger()
        val injected = AssertionError("injected second worker construction failure")
        val runtime = runtime(
            MainBufferedIngressIdentity.create(38),
            stream,
            key,
            workerFactory = { name, action ->
                if (constructionCalls.incrementAndGet() == 2) throw injected
                Thread(action, name)
            },
        ) { _, _ -> }

        assertSame(injected, assertThrows(AssertionError::class.java) { runtime.start() })
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(
            MainBufferedIngressTerminationReason.START_FAILURE,
            runtime.snapshot().terminationReason,
        )
        assertTrue(runtime.snapshot().keyZeroized)
        assertEquals(1, stream.successfulCloses.get())
    }

    @Test
    fun boundedJoinDoesNotClaimSuccessWhileCallbackWorkerIsStillAlive() {
        val key = ByteArray(32) { (it + 6).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = ScriptedStream().apply { enqueue(framed(writer, sequence = 4)) }
        val runtime = runtime(
            MainBufferedIngressIdentity.create(35),
            stream,
            key,
            limits = testLimits(closeJoinMillis = 50),
        ) { _, _ ->
            entered.countDown()
            while (true) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) break
                } catch (_: InterruptedException) {
                    // Exercise an arbitrary callback that does not cooperate with interruption.
                }
            }
        }
        runtime.start()
        assertTrue(entered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

        val startedAt = System.nanoTime()
        val failure = assertThrows(MainBufferedIngressCloseException::class.java) {
            runtime.close()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(failure.message!!.contains("did not stop before the deadline"))
        assertTrue("close took $elapsedMillis ms", elapsedMillis < 1_000)
        assertFalse(runtime.awaitClosed(20))
        assertTrue(runtime.snapshot().deliveryWorkerAlive)

        release.countDown()
        if (!runtime.awaitClosed(TEST_TIMEOUT_MILLIS)) runtime.close()
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(MainBufferedIngressTerminationReason.LOCAL_CLOSE, runtime.snapshot().terminationReason)
        writer.close()
    }

    @Test
    fun callerKeyIsCopiedAndOwnedCopyIsZeroizedOnClose() {
        val callerKey = ByteArray(32) { (it + 8).toByte() }
        val stableKey = callerKey.copyOf()
        val writer = EncryptedApapWriter(stableKey, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val stream = ScriptedStream()
        val delivered = CountDownLatch(1)
        val runtime = runtime(MainBufferedIngressIdentity.create(33), stream, callerKey) { _, _ ->
            delivered.countDown()
        }
        callerKey.fill(0x55)
        stream.enqueue(framed(writer, sequence = 1))
        stream.enqueueEof()

        runtime.start()

        assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertTrue(runtime.snapshot().keyZeroized)
        assertTrue(callerKey.all { it == 0x55.toByte() })
        writer.close()
    }

    @Test
    fun callbackFailureIsTerminalAndClosesTheTransport() {
        val key = ByteArray(32)
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = TEST_MAX_FRAME_BYTES - 4)
        val stream = ScriptedStream().apply { enqueue(framed(writer, sequence = 1)) }
        val runtime = runtime(MainBufferedIngressIdentity.create(34), stream, key) { _, _ ->
            throw IOException("injected callback failure")
        }

        runtime.start()

        assertTrue(runtime.awaitClosed(TEST_TIMEOUT_MILLIS))
        assertEquals(
            MainBufferedIngressTerminationReason.CALLBACK_FAILURE,
            runtime.snapshot().terminationReason,
        )
        assertEquals(1, stream.successfulCloses.get())
        writer.close()
    }

    @Test
    fun exactIdentityAndTransportCanEachBelongToOnlyOneLiveGeneration() {
        val key = ByteArray(32)
        val identity = MainBufferedIngressIdentity.create(50)
        val firstStream = ScriptedStream()
        val first = runtime(identity, firstStream, key) { _, _ -> }

        assertThrows(IllegalArgumentException::class.java) {
            runtime(identity, ScriptedStream(), key) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime(MainBufferedIngressIdentity.create(51), firstStream, key) { _, _ -> }
        }
        assertTrue(identity.toString().contains("generation=50"))
        assertTrue(identity.toString().contains("identity=<redacted>"))

        first.close()
        assertTrue(first.awaitClosed(TEST_TIMEOUT_MILLIS))
    }

    @Test
    fun detachedRuntimeDoesNotEnableOrExposeMainBufferedInProductNegotiation() {
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(1280, 720),
        )

        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config).isReady(AirPlayFeature.MAIN_BUFFERED),
        )
        assertFalse(
            AirPlayFeatureNegotiation.propose(
                config,
                listOf(AirPlayFeature.MAIN_BUFFERED.wireName),
                eventPortAvailable = true,
            ).enabledFeatures.contains(AirPlayFeature.MAIN_BUFFERED.wireName),
        )
        val forbiddenProductTypes = setOf(
            "com.shilapi.xcertplay.airplay.AirPlaySession",
            "com.shilapi.xcertplay.airplay.CarPlayMediaEngine",
            "com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation",
        )
        val constructorTypes = MainBufferedIngressRuntime::class.java.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.map { it.name } }
        assertTrue(constructorTypes.none(forbiddenProductTypes::contains))
    }

    private fun runtime(
        identity: MainBufferedIngressIdentity,
        stream: ScriptedStream,
        key: ByteArray,
        limits: MainBufferedIngressLimits = testLimits(),
        initialNonceCounter: Long = 0L,
        workerFactory: ((String, () -> Unit) -> Thread)? = null,
        workerStarter: ((Thread) -> Unit)? = null,
        authenticatedPacketAdmission: MainBufferedAuthenticatedPacketAdmission =
            MainBufferedAuthenticatedPacketAdmission { true },
        authenticationClockNanos: () -> Long = System::nanoTime,
        consumer: MainBufferedIngressPacketConsumer,
    ): MainBufferedIngressRuntime = MainBufferedIngressRuntime(
        identity = identity,
        transport = stream,
        key = key,
        packetConsumer = consumer,
        limits = limits,
        initialNonceCounter = initialNonceCounter,
        workerFactory = workerFactory ?: { name, action -> Thread(action, name) },
        workerStarter = workerStarter ?: { it.start() },
        authenticatedPacketAdmission = authenticatedPacketAdmission,
        authenticationClockNanos = authenticationClockNanos,
    )

    private fun framed(
        writer: EncryptedApapWriter,
        sequence: Int,
        payload: ByteArray = byteArrayOf(sequence.toByte()),
    ): ByteArray = BufferedApapTcpCodec.encode(
        writer.encrypt(
            ApapPacket(
                header = ApapMediaHeader(sequence.toLong(), 48_000L, sequence),
                extensions = emptyList(),
                mediaPayload = payload,
            ),
            testLimits().packetLimits,
        ),
        TEST_MAX_FRAME_BYTES,
    )

    private class ScriptedStream(
        closeFailures: Int = 0,
        fatalCloseFailures: Int = 0,
    ) : BlockingDuplexByteStream {
        private val receiveQueue = LinkedBlockingQueue<ByteArray>()
        private val closeLock = Any()
        private var remainingCloseFailures = closeFailures
        private var remainingFatalCloseFailures = fatalCloseFailures
        val fatalCloseError = AssertionError("injected fatal transport close failure")
        val closeAttempts = AtomicInteger()
        val successfulCloses = AtomicInteger()

        fun enqueue(bytes: ByteArray) {
            receiveQueue.put(bytes.copyOf())
        }

        fun enqueueEof() {
            receiveQueue.put(ByteArray(0))
        }

        override fun send(data: ByteArray) {
            throw AssertionError("detached ingress must never send")
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            receiveQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        override fun close() {
            closeAttempts.incrementAndGet()
            synchronized(closeLock) {
                if (remainingFatalCloseFailures > 0) {
                    remainingFatalCloseFailures--
                    throw fatalCloseError
                }
                if (remainingCloseFailures > 0) {
                    remainingCloseFailures--
                    throw IOException("injected transport close failure")
                }
                successfulCloses.incrementAndGet()
                receiveQueue.offer(ByteArray(0))
            }
        }
    }

    companion object {
        private const val TEST_MAX_FRAME_BYTES = 1024
        private const val TEST_TIMEOUT_MILLIS = 5_000L

        private fun testLimits(
            maximumQueuedPackets: Int = 4,
            closeJoinMillis: Long = 1_000,
        ): MainBufferedIngressLimits = MainBufferedIngressLimits(
            maximumFrameBytes = TEST_MAX_FRAME_BYTES,
            receiveChunkBytes = TEST_MAX_FRAME_BYTES,
            receiveTimeoutMillis = 25,
            maximumQueuedPackets = maximumQueuedPackets,
            maximumQueuedBytes = TEST_MAX_FRAME_BYTES * 2,
            closeJoinMillis = closeJoinMillis,
            packetLimits = ApapCodecLimits(
                maxPacketBytes = TEST_MAX_FRAME_BYTES -
                    BufferedApapTcpCodec.PREFIX_BYTES - EncryptedApapPacketCodec.OVERHEAD_BYTES,
                maxExtensions = 16,
                maxExtensionBytes = 512,
                maxTotalExtensionBytes = 512,
            ),
        )

        private fun waitUntil(predicate: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TEST_TIMEOUT_MILLIS)
            while (System.nanoTime() < deadline) {
                if (predicate()) return true
                Thread.yield()
            }
            return predicate()
        }
    }
}
