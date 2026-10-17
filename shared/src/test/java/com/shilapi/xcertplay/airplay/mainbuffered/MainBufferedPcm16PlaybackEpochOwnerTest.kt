package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedPcm16PlaybackEpochOwnerTest {
    @Test
    fun authenticatedQueueStampCannotBecomeNewEpochAfterFlushAndLateOldMediaTimeIsRejected() {
        val identity = MainBufferedIngressIdentity.create(391)
        val output = FakeOutput()
        val owner = owner(identity, output)
        val session = Session(identity)
        val old = packet(4, 100)
        val oldStamp = owner.stampAuthenticatedPacket(identity, old)

        owner.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation, flush(104))
        owner.onStampedPacket(session, old, oldStamp)
        owner.onStampedPacket(session, packet(5, 104),
            owner.stampAuthenticatedPacket(identity, packet(5, 104)))
        assertTrue(output.mediaTimes.isEmpty())
        assertEquals(2L, owner.snapshot().stalePackets)
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.STOPPED, owner.snapshot().state)

        val next = packet(6, 106)
        owner.onStampedPacket(session, next, owner.stampAuthenticatedPacket(identity, next))
        assertEquals(listOf(106L), output.mediaTimes)
        assertEquals(null, owner.snapshot().mediaTimeWatermark)
        assertEquals(1L, owner.snapshot().pcm.admittedPackets)
        owner.close()
    }

    @Test
    fun wrongIdentityStreamOrPairVerifyGenerationCannotFlushOrStopOutput() {
        val identity = MainBufferedIngressIdentity.create(392)
        val output = FakeOutput()
        val owner = owner(identity, output)
        val other = MainBufferedIngressIdentity.create(identity.generation)
        val command = flush(20)
        assertThrows(IllegalStateException::class.java) {
            owner.flushFromAuthenticatedControl(other, STREAM_ID, identity.generation, command)
        }
        assertThrows(IllegalStateException::class.java) {
            owner.flushFromAuthenticatedControl(identity, STREAM_ID + 1, identity.generation,
                command)
        }
        assertThrows(IllegalStateException::class.java) {
            owner.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation + 1,
                command)
        }
        assertThrows(IllegalStateException::class.java) {
            owner.stampAuthenticatedPacket(other, packet(1, 10))
        }
        assertThrows(IllegalStateException::class.java) {
            owner.onPacket(Session(identity), packet(1, 10))
        }
        assertEquals(0L, owner.snapshot().epoch)
        assertEquals(0, output.flushes)
        owner.close()
    }

    @Test
    fun rateOneWithoutCalibratedNativeClockNeverStartsAndRateZeroRetiresOldAudio() {
        val identity = MainBufferedIngressIdentity.create(395)
        val output = FakeOutput()
        val owner = owner(identity, output)
        val packet = packet(1, 100)
        owner.onStampedPacket(Session(identity), packet,
            owner.stampAuthenticatedPacket(identity, packet))
        val anchor = MainBufferedPlaybackAnchor(
            point = MainBufferedTimelinePoint(1L, 100L, 48_000L),
            networkTime = MainBufferedNetworkTime(1, 2L, 3L, 0L),
            firstAudiblePoint = MainBufferedTimelinePoint(1L, 100L, 48_000L),
        )
        assertFalse(owner.startFromAuthenticatedControl(
            identity, STREAM_ID, identity.generation,
            MainBufferedSetRateAndAnchor(STREAM_ID, 1, anchor),
        ))
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.STOPPED, owner.snapshot().state)
        assertEquals(0, output.startCalls)
        assertEquals(listOf(100L), output.mediaTimes)

        owner.pauseFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
            MainBufferedSetRateAndAnchor(STREAM_ID, 0, null))
        assertTrue(output.mediaTimes.isEmpty())
        assertEquals(1L, owner.snapshot().epoch)
        assertEquals(2L, output.playbackEpoch) // initial stop plus authenticated rate zero
        owner.close()
    }

    @Test
    fun nativeStartWaitDoesNotBlockApapPrebufferAndConcurrentFlushCancelsReservation() {
        val identity = MainBufferedIngressIdentity.create(403)
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val output = FakeOutput(
            startEntered = startEntered,
            releaseStart = releaseStart,
            fakeNativeStart = true,
        )
        val playback = owner(identity, output)
        val session = Session(identity)
        val first = packet(1, 100)
        playback.onStampedPacket(session, first, playback.stampAuthenticatedPacket(identity, first))
        val control = MainBufferedSetRateAndAnchor(STREAM_ID, 1, MainBufferedPlaybackAnchor(
            point = MainBufferedTimelinePoint(1L, 100L, 48_000L),
            networkTime = MainBufferedNetworkTime(1, 2L, 3L, 0L),
            firstAudiblePoint = MainBufferedTimelinePoint(1L, 100L, 48_000L),
        ))
        val now = 1_000_000_000L
        val mapping = MainBufferedPcm16AudioClockMapping { outputInstance, anchor ->
            MainBufferedPcm16CalibratedPlayCall(
                outputInstance,
                checkNotNull(anchor.networkTime),
                now + 1_000_000L,
                now + 1_000_000_000L,
                5_000_000L,
            )
        }
        val started = AtomicReference<Boolean?>()
        val startThread = Thread {
            started.set(playback.startFromAuthenticatedControl(
                identity, STREAM_ID, identity.generation, control, mapping, now,
            ))
        }
        startThread.start()
        assertTrue(startEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        val secondDelivered = CountDownLatch(1)
        val second = packet(2, 102)
        val delivery = Thread {
            playback.onStampedPacket(session, second,
                playback.stampAuthenticatedPacket(identity, second))
            secondDelivered.countDown()
        }
        delivery.start()
        assertTrue("APAP must fill the prebuffer while native start waits",
            secondDelivered.await(1, TimeUnit.SECONDS))
        assertEquals(listOf(100L, 102L), output.mediaTimes)
        releaseStart.countDown()
        startThread.join(TIMEOUT_MILLIS)
        delivery.join(TIMEOUT_MILLIS)
        assertEquals(true, started.get())
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.PLAYING, playback.snapshot().state)
        playback.close()

        val cancelIdentity = MainBufferedIngressIdentity.create(404)
        val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1)
        val cancelOutput = FakeOutput(
            startEntered = cancelEntered,
            releaseStart = cancelRelease,
            fakeNativeStart = true,
        )
        val cancelOwner = owner(cancelIdentity, cancelOutput)
        val initial = packet(1, 100)
        cancelOwner.onStampedPacket(Session(cancelIdentity), initial,
            cancelOwner.stampAuthenticatedPacket(cancelIdentity, initial))
        val canceledResult = AtomicReference<Boolean?>()
        val waitingStart = Thread {
            canceledResult.set(cancelOwner.startFromAuthenticatedControl(
                cancelIdentity, STREAM_ID, cancelIdentity.generation, control, mapping, now,
            ))
        }
        waitingStart.start()
        assertTrue(cancelEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        cancelOwner.flushFromAuthenticatedControl(
            cancelIdentity, STREAM_ID, cancelIdentity.generation, flush(100),
        )
        cancelRelease.countDown()
        waitingStart.join(TIMEOUT_MILLIS)
        assertEquals(false, canceledResult.get())
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.STOPPED,
            cancelOwner.snapshot().state)
        cancelOwner.close()
    }

    @Test
    fun incomparableFlushScaleCannotAckOrChangeThePlaybackEpoch() {
        val identity = MainBufferedIngressIdentity.create(396)
        val output = FakeOutput()
        val owner = owner(identity, output)
        assertThrows(IllegalStateException::class.java) {
            owner.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
                MainBufferedFlush(STREAM_ID, null,
                    MainBufferedFlushPoint(0L, 0L, 100L, 44_100L)))
        }
        assertEquals(0L, owner.snapshot().epoch)
        assertEquals(0, output.flushes)
        owner.close()
    }

    @Test
    fun exactMctlDispatcherRoutesFlushAndStopButDoesNotAcknowledgeUncalibratedStart() {
        val identity = MainBufferedIngressIdentity.create(397)
        val output = FakeOutput()
        val owner = owner(identity, output)
        assertThrows(IllegalStateException::class.java) {
            owner.authenticatedMctlDispatcher(identity, identity.generation + 1)
        }
        val dispatcher = owner.authenticatedMctlDispatcher(identity, identity.generation)
        val flushReply = dispatcher.dispatchAuthenticatedPackage(syncRequest("fshb", mapOf(
            "streamID" to STREAM_ID,
            "flushUntilSeq" to 1L,
            "flushUntilTS" to 1L,
            "flushUntilMediaTimeValue" to 100L,
            "flushUntilMediaTimeScale" to 48_000L,
        )))
        assertEquals(emptyMap<String, Any>(), BplistCodec.decode(flushReply.body))
        assertEquals(1L, owner.snapshot().epoch)
        assertEquals(1, output.flushes)

        val stopReply = dispatcher.dispatchAuthenticatedPackage(syncRequest("srat", mapOf(
            "streamID" to STREAM_ID, "rate" to 0L,
        )))
        assertEquals(0L, (BplistCodec.decode(stopReply.body) as Map<*, *>)["status"])
        assertEquals(2L, owner.snapshot().epoch)

        val startReply = dispatcher.dispatchAuthenticatedPackage(syncRequest("srat", mapOf(
            "streamID" to STREAM_ID,
            "rate" to 1L,
            "rtpTime" to 1L,
            "mediaTimeValue" to 102L,
            "mediaTimeScale" to 48_000L,
            "firstAudibleRTPTime" to 1L,
            "firstAudibleMediaTimeValue" to 102L,
            "firstAudibleMediaTimeScale" to 48_000L,
            "networkTimeSecs" to 1L,
            "networkTimeFrac" to 2L,
            "networkTimeTimelineID" to 3L,
            "networkTimeFlags" to 0L,
        )))
        assertEquals(-1, MainBufferedApTransportControlHeader.view(startReply).replyStatus)
        assertEquals(-1L, (BplistCodec.decode(startReply.body) as Map<*, *>)["status"])
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.STOPPED, owner.snapshot().state)
        assertEquals(0, output.startCalls)
        owner.close()
    }

    @Test
    fun setupRollbackRetiresExactPcmOwnerAndMismatchedOwnerIsNeverBorrowed() {
        val identity = MainBufferedIngressIdentity.create(398)
        val output = FakeOutput()
        val playback = owner(identity, output)
        assertThrows(IllegalArgumentException::class.java) {
            setup(MainBufferedIngressIdentity.create(identity.generation), playback)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedV27SetupSession.create(
                generation = identity.generation,
                bindAddress = InetAddress.getByName("127.0.0.1"),
                expectedPeer = InetAddress.getByName("127.0.0.1"),
                allocatedStreamId = STREAM_ID,
                acceptedFormat = format(),
                audioBufferSize = 4_096L,
                portMode = MainBufferedV27PortMode.DATA_PORT,
                packetConsumer = MainBufferedIngressPacketConsumer { _, _ -> },
                ingressIdentity = identity,
                playbackOwner = playback,
            )
        }
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.STOPPED, playback.snapshot().state)
        val setup = setup(identity, playback)
        val transaction = setup.prepare(setupRequest())
        transaction.rollback()
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
        assertEquals(1, output.closes)
        setup.close()
        assertEquals(1, output.closes)
    }

    @Test
    fun setupCloseRetriesFailedPcmRetirementWithoutReopeningPorts() {
        val identity = MainBufferedIngressIdentity.create(399)
        val output = FakeOutput(closeFailures = 1)
        val playback = owner(identity, output)
        val setup = setup(identity, playback)
        val transaction = setup.prepare(setupRequest())
        assertThrows(IOException::class.java) { setup.close() }
        assertEquals(MainBufferedV27SetupState.RETIRING, transaction.state())
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSING,
            playback.snapshot().state)
        assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
        setup.close()
        assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
        assertEquals(2, output.closes)
    }

    @Test
    fun apapListenerBindFailureRetiresAlreadyOwnedPcmOutput() {
        val identity = MainBufferedIngressIdentity.create(401)
        val output = FakeOutput()
        val playback = owner(identity, output)
        val setup = setup(identity, playback, bindAddress = InetAddress.getByName("0.0.0.0"))
        assertThrows(IllegalArgumentException::class.java) { setup.prepare(setupRequest()) }
        assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
        assertEquals(1, output.closes)
        setup.close()
        assertEquals(1, output.closes)
    }

    @Test
    fun setupRejectsMctlDispatcherBoundToAnotherPcmOwnerEvenWithSameGenerationAndPeer() {
        val identityA = MainBufferedIngressIdentity.create(402)
        val identityB = MainBufferedIngressIdentity.create(402)
        val playbackA = owner(identityA, FakeOutput())
        val playbackB = owner(identityB, FakeOutput())
        val local = InetAddress.getByName("127.0.0.1")
        val bindingB = playbackB.authenticatedMctlBinding(identityB, 402)
        val wrongPreparer = MainBufferedAuthenticatedMctlPortPreparer(
            sessionGeneration = 402,
            bindAddress = local,
            expectedPeer = local,
            borrowVerifiedSharedSecret = {
                MainBufferedVerifiedSecretBorrow.takeOwnership(402, ByteArray(32))
            },
            isSessionCurrent = { true },
            dispatcher = bindingB.dispatcher,
            playbackControlBinding = bindingB,
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                setup(identityA, playbackA, mctlPortPreparer = wrongPreparer)
            }
            assertThrows(IllegalArgumentException::class.java) {
                setup(identityA, playbackA, mctlPortPreparer =
                    MainBufferedMctlPortPreparer { error("unbound mctl must not prepare") })
            }
            val bindingA = playbackA.authenticatedMctlBinding(identityA, 402)
            val exactPreparer = MainBufferedAuthenticatedMctlPortPreparer(
                sessionGeneration = 402,
                bindAddress = local,
                expectedPeer = local,
                borrowVerifiedSharedSecret = {
                    MainBufferedVerifiedSecretBorrow.takeOwnership(402, ByteArray(32))
                },
                isSessionCurrent = { true },
                dispatcher = bindingA.dispatcher,
                playbackControlBinding = bindingA,
            )
            setup(identityA, playbackA, mctlPortPreparer = exactPreparer).close()
            exactPreparer.close()
        } finally {
            wrongPreparer.close()
            playbackA.close()
            playbackB.close()
        }
    }

    @Test
    fun authenticatedApapEofRetiresPcmAndPairedSetupWithoutLeavingQueuedAudio() {
        val identity = MainBufferedIngressIdentity.create(400)
        val output = FakeOutput()
        val playback = owner(identity, output)
        val setup = setup(identity, playback)
        val transaction = setup.prepare(setupRequest())
        val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
        val port = (stream["dataPort"] as Long).toInt()
        val key = ByteArray(32) { (it + 1).toByte() }
        val limits = MainBufferedPcm16Ingress.limitsFor(format())
        val writer = EncryptedApapWriter(
            key,
            maxEncryptedBytes = limits.maximumFrameBytes - BufferedApapTcpCodec.PREFIX_BYTES,
        )
        try {
            transaction.commitAfterResponseFlushed()
            Socket(InetAddress.getByName("127.0.0.1"), port).use { sender ->
                sender.getOutputStream().write(BufferedApapTcpCodec.encode(
                    writer.encrypt(packet(1, 100), limits.packetLimits),
                    limits.maximumFrameBytes,
                ))
                sender.getOutputStream().flush()
                sender.shutdownOutput()
            }
            assertTrue(waitUntil {
                transaction.state() == MainBufferedV27SetupState.CLOSED &&
                    playback.snapshot().state == MainBufferedPcm16PlaybackEpochOwner.State.CLOSED
            })
            assertTrue(output.mediaTimes.isEmpty())
            assertEquals(1, output.closes)
            assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF,
                transaction.tcpSnapshot().ingress?.terminationReason)
        } finally {
            setup.close()
            writer.close()
        }
    }

    @Test
    fun wrongPeerBeforeApapAuthenticationRetiresPairedMctlAndPcmExactlyOnce() {
        val identity = MainBufferedIngressIdentity.create(405)
        val output = FakeOutput()
        val playback = owner(identity, output)
        val (setup, preparer) = pairedSetup(
            identity, playback,
            expectedPeer = InetAddress.getByName("127.0.0.2"),
            tcpLimits = MainBufferedTcpIngressLimits(maximumCandidates = 1),
        )
        try {
            val transaction = setup.prepare(setupRequestWithMctl())
            val (apapPort, mctlPort) = pairedPorts(transaction)
            transaction.commitAfterResponseFlushed()
            Socket(InetAddress.getByName("127.0.0.1"), apapPort).close()
            assertTrue(waitUntil { transaction.state() == MainBufferedV27SetupState.CLOSED })
            assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
            assertEquals(1, output.closes)
            assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
            assertThrows(ConnectException::class.java) {
                Socket(InetAddress.getByName("127.0.0.1"), mctlPort).close()
            }
        } finally {
            setup.close()
            preparer.close()
        }
    }

    @Test
    fun apapAcceptTimeoutRetiresPairedMctlAndPcmExactlyOnce() {
        val identity = MainBufferedIngressIdentity.create(406)
        val output = FakeOutput()
        val playback = owner(identity, output)
        val (setup, preparer) = pairedSetup(
            identity, playback,
            tcpLimits = MainBufferedTcpIngressLimits(
                acceptDeadlineMillis = 100L, acceptPollMillis = 20,
            ),
        )
        try {
            val transaction = setup.prepare(setupRequestWithMctl())
            val (_, mctlPort) = pairedPorts(transaction)
            transaction.commitAfterResponseFlushed()
            assertTrue(waitUntil { transaction.state() == MainBufferedV27SetupState.CLOSED })
            assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
            assertEquals(1, output.closes)
            assertThrows(ConnectException::class.java) {
                Socket(InetAddress.getByName("127.0.0.1"), mctlPort).close()
            }
        } finally {
            setup.close()
            preparer.close()
        }
    }

    @Test
    fun silentMatchedPeerBeforeFirstPacketRetiresPairedMctlAndPcmExactlyOnce() {
        val identity = MainBufferedIngressIdentity.create(407)
        val output = FakeOutput()
        val playback = owner(identity, output)
        val (setup, preparer) = pairedSetup(
            identity, playback,
            tcpLimits = MainBufferedTcpIngressLimits(
                firstPacketDeadlineMillis = 120L, acceptPollMillis = 20,
            ),
        )
        try {
            val transaction = setup.prepare(setupRequestWithMctl())
            val (apapPort, mctlPort) = pairedPorts(transaction)
            transaction.commitAfterResponseFlushed()
            Socket(InetAddress.getByName("127.0.0.1"), apapPort).use {
                assertTrue(waitUntil { transaction.state() == MainBufferedV27SetupState.CLOSED })
            }
            assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
            assertEquals(1, output.closes)
            assertEquals(MainBufferedTcpIngressState.CLOSED, transaction.tcpSnapshot().state)
            assertThrows(ConnectException::class.java) {
                Socket(InetAddress.getByName("127.0.0.1"), mctlPort).close()
            }
        } finally {
            setup.close()
            preparer.close()
        }
    }

    @Test
    fun pairVerifyRetireCallingSetupCloseCannotDeadlockConcurrentSetupCommit() {
        val identity = MainBufferedIngressIdentity.create(408)
        val output = FakeOutput()
        val playback = owner(identity, output)
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val transactionRef = AtomicReference<MainBufferedV27SetupTransaction?>()
        val (setup, preparer) = pairedSetup(
            identity, playback,
            hooks = MainBufferedMctlTcpHooks(beforeDeferredCommit = {
                commitEntered.countDown()
                check(releaseCommit.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            }),
            isSessionCurrent = { generation ->
                generation == identity.generation &&
                    transactionRef.get()?.state() != MainBufferedV27SetupState.CLOSED
            },
        )
        val transaction = setup.prepare(setupRequestWithMctl())
        transactionRef.set(transaction)
        val commitFailure = AtomicReference<Throwable?>()
        val retirementFailure = AtomicReference<Throwable?>()
        val retired = CountDownLatch(1)
        val commit = Thread {
            try { transaction.commitAfterResponseFlushed() }
            catch (failure: Throwable) { commitFailure.set(failure) }
        }
        val retire = Thread {
            try {
                preparer.retirePairVerifyGeneration { setup.close() }
            } catch (failure: Throwable) {
                retirementFailure.set(failure)
            } finally {
                retired.countDown()
            }
        }
        try {
            commit.start()
            assertTrue(commitEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            retire.start()
            // The callback may wait on SETUP owner.lock, but it must not hold the lease lock
            // needed by the commit worker. Release the held commit only after revocation began.
            assertTrue(waitUntil { preparer.isPairVerifyRevoked })
            assertFalse(retired.await(50, TimeUnit.MILLISECONDS))
            releaseCommit.countDown()
            commit.join(TIMEOUT_MILLIS)
            retire.join(TIMEOUT_MILLIS)
            assertFalse("SETUP commit must not deadlock", commit.isAlive)
            assertFalse("PairVerify retirement must not deadlock", retire.isAlive)
            assertEquals(null, retirementFailure.get())
            assertTrue(commitFailure.get() is IllegalStateException)
            assertEquals(MainBufferedV27SetupState.CLOSED, transaction.state())
            assertEquals(MainBufferedPcm16PlaybackEpochOwner.State.CLOSED, playback.snapshot().state)
            assertEquals(1, output.closes)
        } finally {
            releaseCommit.countDown()
            setup.close()
            preparer.close()
        }
    }

    @Test
    fun inFlightPcmOfferLinearizesBeforeFlushAndIsRetiredFromNativeQueue() {
        val identity = MainBufferedIngressIdentity.create(393)
        val offerEntered = CountDownLatch(1)
        val finishOffer = CountDownLatch(1)
        val output = FakeOutput(offerEntered = offerEntered, finishOffer = finishOffer)
        val owner = owner(identity, output)
        val packet = packet(1, 100)
        val stamp = owner.stampAuthenticatedPacket(identity, packet)
        val deliveryFailure = AtomicReference<Throwable?>()
        val delivery = Thread {
            try { owner.onStampedPacket(Session(identity), packet, stamp) }
            catch (failure: Throwable) { deliveryFailure.set(failure) }
        }
        val flushed = CountDownLatch(1)
        val control = Thread {
            owner.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation,
                flush(100))
            flushed.countDown()
        }
        delivery.start()
        assertTrue(offerEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        control.start()
        assertFalse(flushed.await(50, TimeUnit.MILLISECONDS))
        finishOffer.countDown()
        delivery.join(TIMEOUT_MILLIS)
        control.join(TIMEOUT_MILLIS)
        assertFalse(delivery.isAlive)
        assertFalse(control.isAlive)
        assertEquals(null, deliveryFailure.get())
        assertTrue(flushed.count == 0L)
        assertTrue(output.mediaTimes.isEmpty())
        assertEquals(1, output.flushes)
        owner.close()
    }

    @Test
    fun runtimeStampsAtAuthenticationNotDelayedBusinessDelivery() {
        val identity = MainBufferedIngressIdentity.create(394)
        val output = FakeOutput()
        val owner = owner(identity, output)
        val key = ByteArray(32) { (it + 3).toByte() }
        val limits = MainBufferedPcm16Ingress.limitsFor(format())
        val writer = EncryptedApapWriter(
            key,
            maxEncryptedBytes = limits.maximumFrameBytes - BufferedApapTcpCodec.PREFIX_BYTES,
        )
        val old = packet(1, 100)
        val stream = ScriptedStream().apply {
            enqueue(BufferedApapTcpCodec.encode(
                writer.encrypt(old, limits.packetLimits), limits.maximumFrameBytes))
            enqueueEof()
        }
        val delayedDelivery = AtomicReference<Thread?>()
        val runtime = MainBufferedIngressRuntime(
            identity, stream, key, owner, limits,
            workerStarter = { worker ->
                if (worker.name.contains("delivery")) delayedDelivery.set(worker)
                else worker.start()
            },
        )
        runtime.start()
        assertTrue(waitUntil { runtime.snapshot().queuedPackets == 1 })
        owner.flushFromAuthenticatedControl(identity, STREAM_ID, identity.generation, flush(100))
        delayedDelivery.get()!!.start()
        assertTrue(runtime.awaitClosed(TIMEOUT_MILLIS))
        assertEquals(MainBufferedIngressTerminationReason.CLEAN_EOF,
            runtime.snapshot().terminationReason)
        assertTrue(output.mediaTimes.isEmpty())
        assertEquals(1L, owner.snapshot().stalePackets)
        owner.close()
        writer.close()
    }

    private fun owner(
        identity: MainBufferedIngressIdentity,
        output: FakeOutput,
    ): MainBufferedPcm16PlaybackEpochOwner = MainBufferedPcm16PlaybackEpochOwner.create(
        identity,
        STREAM_ID,
        identity.generation,
        MainBufferedPcm16Ingress.prepare(
            identity,
            STREAM_ID,
            format(),
            MainBufferedPcm16ByteOrder.LITTLE_ENDIAN,
            MainBufferedPcm16OutputFactory { output },
        ),
    )

    private fun format() = MainBufferedV27AcceptedFormat(
        MainBufferedPcm16Ingress.LPCM_CONTENT_TYPE,
        2L,
        MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
    )

    private fun packet(sequence: Int, mediaTime: Long) = ApapPacket(
        ApapMediaHeader(mediaTime, 48_000L, sequence),
        emptyList(),
        ByteArray(8) { it.toByte() },
    )

    private fun flush(mediaTime: Long) = MainBufferedFlush(
        STREAM_ID,
        null,
        MainBufferedFlushPoint(0L, 0L, mediaTime, 48_000L),
    )

    private fun syncRequest(tag: String, body: Map<String, Any?>) = ApTransportPackageCodec.create(
        messageType = tag.toByteArray(Charsets.US_ASCII).fold(0) { value, byte ->
            value shl 8 or (byte.toInt() and 0xff)
        },
        body = BplistCodec.encode(body),
        uninterpretedHeader = ByteArray(32).also { header ->
            val type = MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC
            for (index in 0 until 4) {
                header[4 + index] = (type ushr (24 - index * 8)).toByte()
            }
        },
    )

    private fun setup(
        identity: MainBufferedIngressIdentity,
        playback: MainBufferedPcm16PlaybackEpochOwner,
        bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
        mctlPortPreparer: MainBufferedMctlPortPreparer? = null,
    ) = MainBufferedV27SetupSession.create(
        generation = identity.generation,
        bindAddress = bindAddress,
        expectedPeer = InetAddress.getByName("127.0.0.1"),
        allocatedStreamId = STREAM_ID,
        acceptedFormat = format(),
        audioBufferSize = 4_096L,
        portMode = MainBufferedV27PortMode.DATA_PORT,
        packetConsumer = playback,
        ingressLimits = MainBufferedPcm16Ingress.limitsFor(format()),
        ingressIdentity = identity,
        playbackOwner = playback,
        mctlPortPreparer = mctlPortPreparer,
    )

    private fun setupRequest(): Map<String, Any?> = linkedMapOf(
        "type" to 103L,
        "streamConnectionID" to 1L,
        "supportsDynamicStreamID" to true,
        "ct" to "lpcm",
        "spf" to 2L,
        "audioFormat" to MainBufferedAudioFormatBits.PCM_48000_16_STEREO,
        "audioFormatIndex" to 15L,
        "isMedia" to true,
        "audioType" to "media",
        "packetFormatAPAP" to true,
        "shk" to ByteArray(32) { (it + 1).toByte() },
    )

    private fun setupRequestWithMctl(): Map<String, Any?> = setupRequest() + mapOf(
        "streamConnections" to mapOf(
            "streamConnectionTypeAPAP" to emptyMap<String, Any>(),
            "streamConnectionTypeMediaDataControl" to mapOf(
                "streamConnectionKeyEncryptionSeed" to 17L,
            ),
        ),
    )

    private fun pairedSetup(
        identity: MainBufferedIngressIdentity,
        playback: MainBufferedPcm16PlaybackEpochOwner,
        expectedPeer: InetAddress = InetAddress.getByName("127.0.0.1"),
        tcpLimits: MainBufferedTcpIngressLimits = MainBufferedTcpIngressLimits(),
        hooks: MainBufferedMctlTcpHooks = MainBufferedMctlTcpHooks(),
        isSessionCurrent: (Long) -> Boolean = { it == identity.generation },
    ): Pair<MainBufferedV27SetupSession, MainBufferedAuthenticatedMctlPortPreparer> {
        val local = InetAddress.getByName("127.0.0.1")
        val binding = playback.authenticatedMctlBinding(identity, identity.generation)
        val preparer = MainBufferedAuthenticatedMctlPortPreparer(
            sessionGeneration = identity.generation,
            bindAddress = local,
            expectedPeer = expectedPeer,
            borrowVerifiedSharedSecret = {
                MainBufferedVerifiedSecretBorrow.takeOwnership(identity.generation,
                    ByteArray(32) { (it + 1).toByte() })
            },
            isSessionCurrent = isSessionCurrent,
            dispatcher = binding.dispatcher,
            playbackControlBinding = binding,
            hooks = hooks,
        )
        val setup = MainBufferedV27SetupSession.create(
            generation = identity.generation,
            bindAddress = local,
            expectedPeer = expectedPeer,
            allocatedStreamId = STREAM_ID,
            acceptedFormat = format(),
            audioBufferSize = 4_096L,
            portMode = MainBufferedV27PortMode.STREAM_CONNECTIONS_APAP,
            packetConsumer = playback,
            ingressLimits = MainBufferedPcm16Ingress.limitsFor(format()),
            tcpLimits = tcpLimits,
            mctlPortPreparer = preparer,
            ingressIdentity = identity,
            playbackOwner = playback,
        )
        return setup to preparer
    }

    private fun pairedPorts(transaction: MainBufferedV27SetupTransaction): Pair<Int, Int> {
        val stream = (transaction.responseBody["streams"] as List<*>).single() as Map<*, *>
        val connections = stream["streamConnections"] as Map<*, *>
        val apap = connections["streamConnectionTypeAPAP"] as Map<*, *>
        val mctl = connections["streamConnectionTypeMediaDataControl"] as Map<*, *>
        return (apap["streamConnectionKeyPort"] as Long).toInt() to
            (mctl["streamConnectionKeyPort"] as Long).toInt()
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(5)
        }
        return predicate()
    }

    private class FakeOutput(
        private val offerEntered: CountDownLatch? = null,
        private val finishOffer: CountDownLatch? = null,
        private val startEntered: CountDownLatch? = null,
        private val releaseStart: CountDownLatch? = null,
        private val fakeNativeStart: Boolean = false,
        private var closeFailures: Int = 0,
    ) : MainBufferedPcm16Output {
        override var isReady = true
            private set
        override var playbackEpoch = 0L
            private set
        override val outputInstance: Any = Any()
        override val firstQueuedMediaTimeValue: Long?
            get() = mediaTimes.firstOrNull()
        val mediaTimes = ArrayList<Long>()
        var flushes = 0
        var startCalls = 0
        var closes = 0

        override fun offerPcmLittleEndian(pcm: ByteArray, mediaTimeValue: Long): Boolean {
            offerEntered?.countDown()
            if (finishOffer != null) check(finishOffer.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            check(isReady)
            mediaTimes += mediaTimeValue
            return true
        }

        override fun pausePlayback() {
            check(isReady)
            mediaTimes.clear()
            playbackEpoch++
        }

        override fun startPlayback(anchor: MainBufferedPcm16VerifiedAnchor): Boolean {
            startCalls++
            val expectedEpoch = playbackEpoch
            startEntered?.countDown()
            if (releaseStart != null) {
                check(releaseStart.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            }
            return fakeNativeStart && isReady && playbackEpoch == expectedEpoch
        }

        override fun flush() {
            check(isReady)
            mediaTimes.clear()
            flushes++
            playbackEpoch++
        }

        override fun close() {
            closes++
            mediaTimes.clear()
            if (closeFailures-- > 0) throw IOException("injected PCM close failure")
            isReady = false
        }
    }

    private class Session(override val identity: MainBufferedIngressIdentity) :
        MainBufferedIngressSession {
        override fun snapshot(): MainBufferedIngressSnapshot = error("not needed")
        override fun awaitTerminal(timeoutMillis: Long): Boolean = error("not needed")
        override fun awaitClosed(timeoutMillis: Long): Boolean = error("not needed")
        override fun close() = Unit
    }

    private class ScriptedStream : BlockingDuplexByteStream {
        private val queue = LinkedBlockingQueue<ByteArray>()
        fun enqueue(bytes: ByteArray) { queue.put(bytes.copyOf()) }
        fun enqueueEof() { queue.put(ByteArray(0)) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun send(data: ByteArray) = error("APAP ingress must not send")
        override fun close() { queue.offer(ByteArray(0)) }
    }

    private companion object {
        const val STREAM_ID = 77L
        const val TIMEOUT_MILLIS = 5_000L
    }
}
