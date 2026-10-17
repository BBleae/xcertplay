package com.shilapi.xcertplay.airplay.mainbuffered

import java.net.InetAddress
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedTcpIngressListenerTest {
    @Test
    fun authenticatedTcpListenerPreservesPacketStampBeforeBusinessDelivery() {
        val identity = MainBufferedIngressIdentity.create(909L)
        val exactIdentity = identity
        val key = ByteArray(32) { (it + 17).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val stamped = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val consumer = object : MainBufferedStampedPacketConsumer {
            override fun stampAuthenticatedPacket(
                identity: MainBufferedIngressIdentity,
                packet: ApapPacket,
            ): Any {
                assertTrue(identity === exactIdentity)
                stamped.countDown()
                return packet.header.sequence
            }

            override fun onStampedPacket(
                session: MainBufferedIngressSession,
                packet: ApapPacket,
                stamp: Any,
            ) {
                assertTrue(session.identity === exactIdentity)
                assertEquals(packet.header.sequence, stamp)
                delivered.countDown()
            }
        }
        val listener = MainBufferedTcpIngressListener.prepare(
            bindAddress = LOOPBACK,
            expectedPeer = LOOPBACK,
            identity = identity,
            sessionKey = key,
            packetConsumer = consumer,
            ingressLimits = testIngressLimits(),
        )
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                sender.getOutputStream().write(frame(writer, 19))
                sender.getOutputStream().flush()
                assertTrue(stamped.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                sender.shutdownOutput()
            }
        } finally {
            listener.close()
            writer.close()
        }
    }

    @Test
    fun preparedPortNeverDecodesUntilCommitThenDeliversAuthenticatedFrame() {
        val key = ByteArray(32) { (it + 7).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val delivered = CountDownLatch(1)
        val sequence = AtomicInteger(-1)
        val listener = prepare(key) { _, packet ->
            sequence.set(packet.header.sequence)
            delivered.countDown()
        }
        key.fill(0x66)
        Socket(LOOPBACK, listener.localAddress.port).use { sender ->
            sender.getOutputStream().write(frame(writer, 17))
            sender.getOutputStream().flush()
            assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))
            assertEquals(MainBufferedTcpIngressState.PREPARED, listener.snapshot().state)
            assertEquals(0, listener.snapshot().candidateCount)

            listener.commit()
            val session = listener.awaitSession(TEST_TIMEOUT_MILLIS)
            assertNotNull(session)
            sender.shutdownOutput()
            assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(session!!.awaitClosed(TEST_TIMEOUT_MILLIS))
            assertEquals(17, sequence.get())
            assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF, session.snapshot().terminationReason)
        }
        listener.close()
        writer.close()
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
        assertTrue(listener.snapshot().pendingKeyZeroized)
        assertTrue(listener.snapshot().serverClosed)
        assertFalse(listener.snapshot().acceptWorkerAlive)
    }

    @Test
    fun deferredCommitAndActivationDoNotAcceptBeforeSharedGateOpens() {
        val key = ByteArray(32) { 0x51 }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val delivered = CountDownLatch(1)
        val listener = prepare(key) { _, _ -> delivered.countDown() }
        val gate = MainBufferedAdmissionGate()
        try {
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                sender.getOutputStream().write(frame(writer, 31))
                sender.getOutputStream().flush()
                listener.commitDeferred()
                listener.activateWhen(gate)
                assertEquals(MainBufferedTcpIngressState.COMMITTED, listener.snapshot().state)
                assertTrue(listener.snapshot().acceptWorkerAlive)
                assertEquals(0, listener.snapshot().candidateCount)
                assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))

                gate.open()
                assertNotNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
                assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                sender.shutdownOutput()
            }
        } finally {
            listener.close()
            writer.close()
        }
    }

    @Test
    fun closingDeferredListenerWakesWorkerWithoutAcceptingPendingPeer() {
        val delivered = AtomicInteger()
        val listener = prepare(ByteArray(32) { 0x52 }) { _, _ ->
            delivered.incrementAndGet()
        }
        val gate = MainBufferedAdmissionGate()
        val port = listener.localAddress.port
        Socket(LOOPBACK, port).use {
            listener.commitDeferred()
            listener.activateWhen(gate)
            assertEquals(0, listener.snapshot().candidateCount)
            listener.close()
        }
        assertFalse(gate.awaitOpen())
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
        assertEquals(0, listener.snapshot().candidateCount)
        assertEquals(0, delivered.get())
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, port).close() }
    }

    @Test
    fun interruptedAdmissionGateWaitFailsClosedAndZeroizesPendingKey() {
        val workerAtGate = CountDownLatch(1)
        val acceptWorker = AtomicReference<Thread>()
        val delivered = AtomicInteger()
        val listener = prepare(
            ByteArray(32) { 0x53 },
            hooks = MainBufferedTcpIngressHooks(
                acceptWorkerStarter = { worker ->
                    acceptWorker.set(worker)
                    worker.start()
                },
                beforeAdmissionGateWait = { workerAtGate.countDown() },
            ),
        ) { _, _ -> delivered.incrementAndGet() }
        val gate = MainBufferedAdmissionGate()
        val port = listener.localAddress.port
        try {
            Socket(LOOPBACK, port).use {
                listener.commitDeferred()
                listener.activateWhen(gate)
                assertTrue(workerAtGate.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                checkNotNull(acceptWorker.get()).interrupt()
                assertTrue(waitUntil {
                    listener.snapshot().state == MainBufferedTcpIngressState.FAILED
                })
                assertTrue(listener.snapshot().pendingKeyZeroized)
                assertTrue(listener.snapshot().serverClosed)
                assertTrue(listener.snapshot().failure?.message?.contains("gate interrupted") == true)
                assertEquals(0, listener.snapshot().candidateCount)
                assertEquals(0, delivered.get())
                gate.open()
                assertNull(listener.awaitSession(0))
            }
        } finally {
            listener.close()
        }
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, port).close() }
    }

    @Test
    fun wrongPeerConsumesBoundedCandidateWithoutStartingIngress() {
        val accepted = AtomicInteger()
        val listener = MainBufferedTcpIngressListener.prepare(
            bindAddress = LOOPBACK,
            expectedPeer = InetAddress.getByName("127.0.0.2"),
            identity = MainBufferedIngressIdentity.create(2),
            sessionKey = ByteArray(32),
            packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> accepted.incrementAndGet() },
            ingressLimits = testIngressLimits(),
            tcpLimits = MainBufferedTcpIngressLimits(maximumCandidates = 1),
        )
        listener.commit()
        Socket(LOOPBACK, listener.localAddress.port).close()

        assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
        assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
        assertEquals(1, listener.snapshot().candidateCount)
        assertTrue(listener.snapshot().pendingKeyZeroized)
        assertEquals(0, accepted.get())
        listener.close()
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
    }

    @Test
    fun acceptDeadlineExpiresWithoutPeerAndRetiresPortAndPendingKey() {
        val listener = prepare(
            ByteArray(32),
            tcpLimits = MainBufferedTcpIngressLimits(acceptDeadlineMillis = 60, acceptPollMillis = 10),
        ) { _, _ -> throw AssertionError("no peer should be admitted") }
        listener.commit()

        assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
        assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
        assertTrue(listener.snapshot().serverClosed)
        assertTrue(listener.snapshot().pendingKeyZeroized)
        assertEquals(0, listener.snapshot().candidateCount)
        listener.close()
    }

    @Test
    fun acceptedSilentPeerIsRetiredByFirstAuthenticatedPacketDeadline() {
        val listener = prepare(
            ByteArray(32) { 0x19 },
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 400),
        ) { _, _ -> throw AssertionError("silent peer must not deliver") }
        listener.commit()
        Socket(LOOPBACK, listener.localAddress.port).use {
            assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
            assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
            assertTrue(waitUntil {
                listener.snapshot().ingress?.closeState == MainBufferedIngressCloseState.COMPLETE
            })
            assertEquals(
                MainBufferedIngressTerminationReason.LOCAL_CLOSE,
                listener.snapshot().ingress?.terminationReason,
            )
            assertTrue(listener.snapshot().pendingKeyZeroized)
            assertTrue(listener.snapshot().serverClosed)
        }
        listener.close()
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
    }

    @Test
    fun firstAuthenticatedPacketAtInclusiveDeadlineIsAdmitted() {
        val clock = AtomicLong(0L)
        val monitorEntered = CountDownLatch(1)
        val releaseMonitor = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val key = ByteArray(32) { 0x27 }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val listener = prepare(
            key,
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 500),
            hooks = MainBufferedTcpIngressHooks(
                monotonicNanos = clock::get,
                afterRuntimeStartedBeforeMonitor = {
                    monitorEntered.countDown()
                    assertTrue(releaseMonitor.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                },
            ),
        ) { _, _ -> delivered.countDown() }
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                assertTrue(monitorEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                clock.set(TimeUnit.MILLISECONDS.toNanos(500))
                sender.getOutputStream().write(frame(writer, 20))
                sender.getOutputStream().flush()
                assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertNotNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
                releaseMonitor.countDown()
                sender.shutdownOutput()
            }
        } finally {
            releaseMonitor.countDown()
            listener.close()
            writer.close()
        }
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
    }

    @Test
    fun latePacketCannotPublishSessionWhileDeadlineMonitorIsUnschedulable() {
        val clock = AtomicLong(0L)
        val monitorEntered = CountDownLatch(1)
        val releaseMonitor = CountDownLatch(1)
        val delivered = AtomicInteger()
        val key = ByteArray(32) { 0x28 }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val listener = prepare(
            key,
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 500),
            hooks = MainBufferedTcpIngressHooks(
                monotonicNanos = clock::get,
                afterRuntimeStartedBeforeMonitor = {
                    monitorEntered.countDown()
                    assertTrue(releaseMonitor.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                },
            ),
        ) { _, _ -> delivered.incrementAndGet() }
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                assertTrue(monitorEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                clock.set(TimeUnit.MILLISECONDS.toNanos(500) + 1L)
                sender.getOutputStream().write(frame(writer, 21))
                sender.getOutputStream().flush()
                assertTrue(waitUntil {
                    listener.snapshot().ingress?.terminationReason ==
                        MainBufferedIngressTerminationReason.ADMISSION_REJECTED
                })
                assertEquals(0, delivered.get())
                assertNull(listener.awaitSession(0))
                releaseMonitor.countDown()
                assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
                assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
                assertTrue(listener.snapshot().failure?.message?.contains("arrived late") == true)
                assertTrue(waitUntil {
                    listener.snapshot().ingress?.closeState == MainBufferedIngressCloseState.COMPLETE
                })
                assertTrue(listener.snapshot().ingress?.keyZeroized == true)
            }
        } finally {
            releaseMonitor.countDown()
            listener.close()
            writer.close()
        }
    }

    @Test
    fun authenticationBeforeDeadlineSurvivesDelayedDeliveryPastDeadline() {
        val clock = AtomicLong(0L)
        val deferredDelivery = AtomicReference<Thread>()
        val delivered = CountDownLatch(1)
        val key = ByteArray(32) { 0x2a }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val listener = prepare(
            key,
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 500),
            hooks = MainBufferedTcpIngressHooks(
                monotonicNanos = clock::get,
                runtimeWorkerStarter = { worker ->
                    if (worker.name.contains("delivery")) {
                        deferredDelivery.set(worker)
                    } else {
                        worker.start()
                    }
                },
            ),
        ) { _, _ -> delivered.countDown() }
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                assertTrue(waitUntil { listener.snapshot().state == MainBufferedTcpIngressState.RUNNING })
                clock.set(TimeUnit.MILLISECONDS.toNanos(500) - 1L)
                sender.getOutputStream().write(frame(writer, 23))
                sender.getOutputStream().flush()
                val session = listener.awaitSession(TEST_TIMEOUT_MILLIS)!!
                assertEquals(1, session.snapshot().queuedPackets)
                assertEquals(1L, delivered.count)

                clock.set(TimeUnit.MILLISECONDS.toNanos(500) + 1L)
                deferredDelivery.get()!!.start()
                assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                sender.shutdownOutput()
                assertTrue(session.awaitClosed(TEST_TIMEOUT_MILLIS))
                assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF, session.snapshot().terminationReason)
            }
        } finally {
            listener.close()
            writer.close()
        }
    }

    @Test
    fun timeoutCannotOvertakeAuthenticationAlreadyInsideIngressBoundary() {
        val clock = AtomicLong(0L)
        val timestampCaptured = CountDownLatch(1)
        val releaseTimestamp = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val key = ByteArray(32) { 0x2c }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val listener = prepare(
            key,
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 500),
            hooks = MainBufferedTcpIngressHooks(
                monotonicNanos = {
                    if (Thread.currentThread().name.contains("main-buffered-ingress")) {
                        val observed = clock.get()
                        timestampCaptured.countDown()
                        assertTrue(releaseTimestamp.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                        observed
                    } else {
                        clock.get()
                    }
                },
            ),
        ) { _, _ -> delivered.countDown() }
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                assertTrue(waitUntil { listener.snapshot().state == MainBufferedTcpIngressState.RUNNING })
                clock.set(TimeUnit.MILLISECONDS.toNanos(500) - 1L)
                sender.getOutputStream().write(frame(writer, 25))
                sender.getOutputStream().flush()
                assertTrue(timestampCaptured.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

                clock.set(TimeUnit.MILLISECONDS.toNanos(500) + 1L)
                Thread.sleep(200)
                assertEquals(MainBufferedTcpIngressState.RUNNING, listener.snapshot().state)
                releaseTimestamp.countDown()
                val session = listener.awaitSession(TEST_TIMEOUT_MILLIS)!!
                assertTrue(delivered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                sender.shutdownOutput()
                assertTrue(session.awaitClosed(TEST_TIMEOUT_MILLIS))
            }
        } finally {
            releaseTimestamp.countDown()
            listener.close()
            writer.close()
        }
    }

    @Test
    fun authenticationAfterDeadlineIsRejectedBeforeDelayedDeliveryQueue() {
        val clock = AtomicLong(0L)
        val deferredDelivery = AtomicReference<Thread>()
        val monitorEntered = CountDownLatch(1)
        val releaseMonitor = CountDownLatch(1)
        val delivered = AtomicInteger()
        val key = ByteArray(32) { 0x2b }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val listener = prepare(
            key,
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 500),
            hooks = MainBufferedTcpIngressHooks(
                monotonicNanos = clock::get,
                runtimeWorkerStarter = { worker ->
                    if (worker.name.contains("delivery")) {
                        deferredDelivery.set(worker)
                    } else {
                        worker.start()
                    }
                },
                afterRuntimeStartedBeforeMonitor = {
                    monitorEntered.countDown()
                    assertTrue(releaseMonitor.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                },
            ),
        ) { _, _ -> delivered.incrementAndGet() }
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                assertTrue(monitorEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                assertNotNull(deferredDelivery.get())
                clock.set(TimeUnit.MILLISECONDS.toNanos(500) + 1L)
                sender.getOutputStream().write(frame(writer, 24))
                sender.getOutputStream().flush()
                assertTrue(waitUntil {
                    listener.snapshot().ingress?.terminationReason ==
                        MainBufferedIngressTerminationReason.ADMISSION_REJECTED
                })
                assertEquals(0, listener.snapshot().ingress?.queuedPackets)
                assertEquals(0, delivered.get())
                releaseMonitor.countDown()
                assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
                assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
            }
        } finally {
            releaseMonitor.countDown()
            listener.close()
            writer.close()
        }
    }

    @Test
    fun delayedRuntimeStartupDoesNotRestartAcceptanceDeadline() {
        val clock = AtomicLong(0L)
        val starterEntered = CountDownLatch(1)
        val releaseStarter = CountDownLatch(1)
        val starts = AtomicInteger()
        val delivered = AtomicInteger()
        val key = ByteArray(32) { 0x29 }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val listener = prepare(
            key,
            tcpLimits = MainBufferedTcpIngressLimits(firstPacketDeadlineMillis = 500),
            hooks = MainBufferedTcpIngressHooks(
                monotonicNanos = clock::get,
                runtimeWorkerStarter = { thread ->
                    if (starts.incrementAndGet() == 1) {
                        starterEntered.countDown()
                        assertTrue(releaseStarter.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                    }
                    thread.start()
                },
            ),
        ) { _, _ -> delivered.incrementAndGet() }
        try {
            listener.commit()
            Socket(LOOPBACK, listener.localAddress.port).use { sender ->
                assertTrue(starterEntered.await(TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
                clock.set(TimeUnit.MILLISECONDS.toNanos(500) + 1L)
                sender.getOutputStream().write(frame(writer, 22))
                sender.getOutputStream().flush()
                releaseStarter.countDown()
                assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
                assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
                assertEquals(0, delivered.get())
                assertTrue(waitUntil {
                    listener.snapshot().ingress?.closeState == MainBufferedIngressCloseState.COMPLETE
                })
                assertTrue(listener.snapshot().ingress?.keyZeroized == true)
            }
        } finally {
            releaseStarter.countDown()
            listener.close()
            writer.close()
        }
    }

    @Test
    fun rollbackBeforeCommitClosesPortAndZeroizesOnlyOwnedKeyCopy() {
        val supplied = ByteArray(32) { 0x31 }
        val listener = prepare(supplied) { _, _ -> throw AssertionError("rolled back") }
        val port = listener.localAddress.port
        assertFalse(listener.snapshot().pendingKeyZeroized)
        listener.rollback()

        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
        assertTrue(listener.snapshot().pendingKeyZeroized)
        assertTrue(supplied.all { it == 0x31.toByte() })
        assertThrows(IllegalStateException::class.java) { listener.commit() }
        assertThrows(ConnectException::class.java) { Socket(LOOPBACK, port).close() }
        listener.close()
    }

    @Test
    fun wrongAuthenticationTagIsRejectedAndOwnedRuntimeRetires() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val delivered = AtomicInteger()
        val listener = prepare(key) { _, _ -> delivered.incrementAndGet() }
        val corrupt = frame(writer, 1).also {
            val offset = BufferedApapTcpCodec.PREFIX_BYTES +
                EncryptedApapPacketCodec.CLEAR_HEADER_BYTES
            it[offset] = (it[offset].toInt() xor 1).toByte()
        }
        listener.commit()
        Socket(LOOPBACK, listener.localAddress.port).use { sender ->
            sender.getOutputStream().write(corrupt)
            sender.getOutputStream().flush()
            assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
            assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
            assertTrue(waitUntil {
                listener.snapshot().ingress?.closeState == MainBufferedIngressCloseState.COMPLETE
            })
            assertEquals(
                MainBufferedIngressTerminationReason.AUTHENTICATION_FAILURE,
                listener.snapshot().ingress?.terminationReason,
            )
            assertTrue(listener.snapshot().ingress?.keyZeroized == true)
        }
        assertEquals(0, delivered.get())
        listener.close()
        writer.close()
    }

    @Test
    fun eofBeforeFirstAuthenticatedPacketFailsListenerAndRetiresRuntime() {
        val listener = prepare(ByteArray(32) { 0x25 }) { _, _ ->
            throw AssertionError("EOF must not deliver a packet")
        }
        listener.commit()
        Socket(LOOPBACK, listener.localAddress.port).use { sender ->
            sender.shutdownOutput()
            assertNull(listener.awaitSession(TEST_TIMEOUT_MILLIS))
            assertEquals(MainBufferedTcpIngressState.FAILED, listener.snapshot().state)
            assertTrue(waitUntil {
                listener.snapshot().ingress?.closeState == MainBufferedIngressCloseState.COMPLETE
            })
            assertEquals(
                MainBufferedIngressTerminationReason.CLEAN_EOF,
                listener.snapshot().ingress?.terminationReason,
            )
        }
        listener.close()
        listener.close()
        assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
    }

    @Test
    fun replayedFrameCannotReachCallbackTwice() {
        val key = ByteArray(32) { (it + 9).toByte() }
        val writer = EncryptedApapWriter(key, maxEncryptedBytes = MAX_FRAME_BYTES - 4)
        val delivered = AtomicInteger()
        val listener = prepare(key) { _, _ -> delivered.incrementAndGet() }
        val repeated = frame(writer, 3)
        listener.commit()
        Socket(LOOPBACK, listener.localAddress.port).use { sender ->
            sender.getOutputStream().write(repeated)
            sender.getOutputStream().flush()
            val session = listener.awaitSession(TEST_TIMEOUT_MILLIS)!!
            sender.getOutputStream().write(repeated)
            sender.getOutputStream().flush()
            assertTrue(session.awaitClosed(TEST_TIMEOUT_MILLIS))
            assertEquals(
                MainBufferedIngressTerminationReason.NONCE_SEQUENCE_FAILURE,
                session.snapshot().terminationReason,
            )
        }
        assertTrue(delivered.get() <= 1)
        listener.close()
        writer.close()
    }

    @Test
    fun closeOwnsListeningPortAndAcceptedStreamWithoutMutatingCallerKey() {
        val supplied = ByteArray(32) { (it + 1).toByte() }
        val original = supplied.copyOf()
        val listener = prepare(supplied) { _, _ -> }
        supplied.fill(0x55)
        listener.commit()
        val sender = Socket(LOOPBACK, listener.localAddress.port)
        try {
            assertTrue(waitUntil { listener.snapshot().state == MainBufferedTcpIngressState.RUNNING })
            assertNull(listener.awaitSession(0))
            listener.close()
            val ingress = listener.snapshot().ingress!!
            assertEquals(MainBufferedIngressCloseState.COMPLETE, ingress.closeState)
            assertEquals(MainBufferedIngressTerminationReason.LOCAL_CLOSE, ingress.terminationReason)
            assertEquals(MainBufferedTcpIngressState.CLOSED, listener.snapshot().state)
            assertFalse(listener.snapshot().acceptWorkerAlive)
            assertTrue(listener.snapshot().serverClosed)
            assertTrue(listener.snapshot().pendingKeyZeroized)
            assertTrue(ingress.keyZeroized)
            assertArrayEquals(ByteArray(32) { 0x55 }, supplied)
            assertFalse(original.contentEquals(supplied))
        } finally {
            sender.close()
            listener.close()
        }
    }

    @Test
    fun invalidKeyAndWildcardBindingNeverPublishAListener() {
        assertThrows(IllegalArgumentException::class.java) {
            prepare(ByteArray(31)) { _, _ -> }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedTcpIngressListener.prepare(
                bindAddress = InetAddress.getByName("0.0.0.0"),
                expectedPeer = LOOPBACK,
                identity = MainBufferedIngressIdentity.create(8),
                sessionKey = ByteArray(32),
                packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> },
            )
        }
    }

    private fun prepare(
        key: ByteArray,
        tcpLimits: MainBufferedTcpIngressLimits = MainBufferedTcpIngressLimits(),
        hooks: MainBufferedTcpIngressHooks = MainBufferedTcpIngressHooks(),
        consumer: MainBufferedIngressPacketConsumer,
    ): MainBufferedTcpIngressListener = MainBufferedTcpIngressListener.prepare(
        bindAddress = LOOPBACK,
        expectedPeer = LOOPBACK,
        identity = MainBufferedIngressIdentity.create(nextGeneration.getAndIncrement().toLong()),
        sessionKey = key,
        packetConsumer = consumer,
        ingressLimits = testIngressLimits(),
        tcpLimits = tcpLimits,
        hooks = hooks,
    )

    private fun frame(writer: EncryptedApapWriter, sequence: Int): ByteArray =
        BufferedApapTcpCodec.encode(
            writer.encrypt(
                ApapPacket(
                    header = ApapMediaHeader(sequence.toLong(), 48_000L, sequence),
                    extensions = emptyList(),
                    mediaPayload = byteArrayOf(sequence.toByte()),
                ),
                testIngressLimits().packetLimits,
            ),
            MAX_FRAME_BYTES,
        )

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val nextGeneration = AtomicInteger(100)
        private const val MAX_FRAME_BYTES = 1024
        private const val TEST_TIMEOUT_MILLIS = 5_000L

        private fun testIngressLimits(): MainBufferedIngressLimits = MainBufferedIngressLimits(
            maximumFrameBytes = MAX_FRAME_BYTES,
            receiveChunkBytes = MAX_FRAME_BYTES,
            receiveTimeoutMillis = 25,
            maximumQueuedPackets = 4,
            maximumQueuedBytes = MAX_FRAME_BYTES * 2,
            closeJoinMillis = 1_000,
            packetLimits = ApapCodecLimits(
                maxPacketBytes = MAX_FRAME_BYTES -
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
                Thread.sleep(5)
            }
            return predicate()
        }
    }
}
