package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

/**
 * Timing discipline: every wire packet is built before its socket connects (the first ChaCha seal
 * loads BouncyCastle and can take longer than a short candidate window on a loaded JVM), limits
 * that are not under test are generous, accept deadlines under test are driven by an injected
 * clock, and a close that must succeed after a deliberately short join retries as the production
 * retirement contract allows.
 */
class ScreenStreamSecurityTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun clearConfigIsHeldUntilTheFirstFrameAuthenticates() {
        val callbacks = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<ByteArray>()
        val timings = CopyOnWriteArrayList<ScreenFrameTiming>()
        val frameReady = CountDownLatch(1)
        val stream = newStream()
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) {
                    callbacks += "codec:$codec"
                }

                override fun onConfig(codecData: ByteArray) {
                    callbacks += "config:${codecData.size}"
                }

                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    callbacks += "frame"
                    frames += naluBytes
                    timings += timing
                    frameReady.countDown()
                }
            },
        )

        val config = clearConfigPacket()
        val frame = authenticatedFramePacket(
            presentationTime = 0x1234_5678_9abc_def0L,
            timelineId = Long.MIN_VALUE,
        )
        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(config)
                flush()
            }
            assertFalse(frameReady.await(75, TimeUnit.MILLISECONDS))
            assertTrue(callbacks.isEmpty())

            client.getOutputStream().apply {
                write(frame)
                flush()
            }
            assertTrue(frameReady.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(listOf("codec:H264", "config:5", "frame"), callbacks)
            assertArrayEquals(
                byteArrayOf(0, 0, 0, 1, 0x65, 0x01),
                frames.single(),
            )
            assertEquals(0x1234_5678_9abc_def0L, timings.single().presentationTimeNtp64Bits)
            assertEquals(Long.MIN_VALUE, timings.single().timelineIdRawBits)
            assertEquals(Long.MIN_VALUE,
                timings.single().authenticatedFrameIdentity?.timelineIdRawBits)
            assertEquals(0L, timings.single().authenticatedFrameCounter)
        }
        stream.close()
    }

    @Test
    fun successiveAuthenticatedFramesPreserveTheirExactNonceCounters() {
        val timings = CopyOnWriteArrayList<ScreenFrameTiming>()
        val received = CountDownLatch(2)
        val stream = newStream()
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    timings += timing
                    received.countDown()
                }
            },
        )

        val first = authenticatedFramePacket(
            presentationTime = 0x1234L,
            sequence = 0,
            timelineId = 0x5678L,
        )
        val second = authenticatedFramePacket(
            presentationTime = Long.MIN_VALUE,
            sequence = 1,
            timelineId = 0x7fff_ffff_ffff_ffffL,
        )
        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(first)
                write(second)
                flush()
            }
            assertTrue(received.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
        assertEquals(listOf(0L, 1L), timings.map { it.authenticatedFrameCounter })
        assertEquals(listOf(0x1234L, Long.MIN_VALUE),
            timings.map { it.presentationTimeNtp64Bits })
        assertEquals(listOf(0x5678L, 0x7fff_ffff_ffff_ffffL),
            timings.map { it.timelineIdRawBits })
        assertEquals(timings.map { it.timelineIdRawBits },
            timings.map { it.authenticatedFrameIdentity?.timelineIdRawBits })
        stream.close()
    }

    @Test
    fun shortFrameIsNeverPlaintextAndAValidRetryCanClaimTheListener() {
        val frameReady = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<ByteArray>()
        val packets = CopyOnWriteArrayList<ByteArray>()
        val stream = newStream()
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onPacket(
                    sequence: Long,
                    header: ByteArray,
                    wire: ByteArray,
                    plain: ByteArray?,
                ) {
                    if (plain != null) packets += plain
                }

                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    frames += naluBytes
                    frameReady.countDown()
                }
            },
        )

        val validFrame = authenticatedFramePacket()
        Socket(loopback, port).use { invalid ->
            invalid.getOutputStream().apply {
                write(messagePacket(opcode = 0, body = ByteArray(ScreenCodec.TAG_SIZE - 1)))
                flush()
            }
            awaitServerClose(invalid)
        }
        assertTrue(frames.isEmpty())
        assertTrue(packets.isEmpty())

        Socket(loopback, port).use { invalidTag ->
            invalidTag.getOutputStream().apply {
                write(messagePacket(opcode = 0, body = ByteArray(ScreenCodec.TAG_SIZE)))
                flush()
            }
            awaitServerClose(invalidTag)
        }
        assertTrue(frames.isEmpty())
        assertTrue(packets.isEmpty())

        Socket(loopback, port).use { valid ->
            valid.getOutputStream().apply {
                write(validFrame)
                flush()
            }
            assertTrue(frameReady.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
        assertEquals(1, frames.size)
        assertEquals(1, packets.size)
        stream.close()
    }

    @Test
    fun unexpectedPeerIsRejectedWithoutConsumingTheValidRetry() {
        val frameReady = CountDownLatch(1)
        val stream = newStream()
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    frameReady.countDown()
                }
            },
        )

        val validFrame = authenticatedFramePacket()
        val otherLoopback = InetAddress.getByName("127.0.0.2")
        val wrong = Socket()
        try {
            wrong.bind(InetSocketAddress(otherLoopback, 0))
            wrong.connect(InetSocketAddress(loopback, port), 1_000)
        } catch (error: Exception) {
            wrong.close()
            stream.close()
            assumeNoException(error)
            return
        }
        wrong.use {
            awaitServerClose(it)
        }
        assertEquals(1L, frameReady.count)

        Socket(loopback, port).use { valid ->
            valid.getOutputStream().apply {
                write(validFrame)
                flush()
            }
            assertTrue(frameReady.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
        stream.close()
    }

    @Test
    fun acceptAndAuthenticatedReadHaveFiniteTimeouts() {
        val acceptClosed = CountDownLatch(1)
        val acceptFailure = AtomicReference<Throwable?>()
        val acceptTimeoutStream = newStream(
            ScreenStreamLimits(
                acceptDeadlineMillis = 100,
                acceptPollMillis = 20,
                candidateAuthTimeoutMillis = 50,
                activeReadTimeoutMillis = 100,
            ),
        )
        acceptTimeoutStream.listen(
            object : ScreenStream.Listener {
                override fun onClosed(cause: Throwable?) {
                    acceptFailure.set(cause)
                    acceptClosed.countDown()
                }
            },
        )
        assertTrue(acceptClosed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(acceptFailure.get() is SocketTimeoutException)

        val activeFrame = CountDownLatch(1)
        val activeClosed = CountDownLatch(1)
        val activeFailure = AtomicReference<Throwable?>()
        // Only the active read timeout is under test here; the candidate window stays generous.
        val activeTimeoutStream = newStream(
            ScreenStreamLimits(
                acceptDeadlineMillis = GENEROUS_MILLIS,
                acceptPollMillis = 20,
                candidateAuthTimeoutMillis = GENEROUS_MILLIS,
                activeReadTimeoutMillis = 100,
            ),
        )
        val activePacket = authenticatedFramePacket()
        val activePort = activeTimeoutStream.listen(
            object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    activeFrame.countDown()
                }

                override fun onClosed(cause: Throwable?) {
                    activeFailure.set(cause)
                    activeClosed.countDown()
                }
            },
        )
        Socket(loopback, activePort).use { client ->
            client.getOutputStream().apply {
                write(activePacket)
                flush()
            }
            assertTrue(activeFrame.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(activeClosed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(activeFailure.get() is SocketTimeoutException)
        }
        activeTimeoutStream.close()
    }

    @Test
    fun candidateReadTimeoutAllowsAnotherBoundedAttempt() {
        val frameReady = CountDownLatch(1)
        val stalledAccepted = CountDownLatch(1)
        val diagnostics = CopyOnWriteArrayList<ScreenCandidateDiagnostic>()
        val stream = newStream(
            ScreenStreamLimits(
                acceptDeadlineMillis = GENEROUS_MILLIS,
                acceptPollMillis = 20,
                candidateAuthTimeoutMillis = STALL_WINDOW_MILLIS,
                activeReadTimeoutMillis = GENEROUS_MILLIS,
            ),
            diagnosticObserver = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic.stage == ScreenCandidateStage.EXACT_PEER) stalledAccepted.countDown()
            },
        )
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    frameReady.countDown()
                }
            },
        )
        val retryFrame = authenticatedFramePacket()

        Socket(loopback, port).use { stalled ->
            // The stalled candidate owns the single authentication slot before the retry exists.
            assertTrue(stalledAccepted.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            Socket(loopback, port).use { retry ->
                // Normally queued in the accept backlog while the stalled read is pending, so the
                // retry's first read never waits on this client thread being scheduled.
                retry.getOutputStream().apply {
                    write(retryFrame)
                    flush()
                }
                awaitServerClose(stalled)
                assertTrue(frameReady.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            }
        }
        stream.close()

        assertEquals(1, diagnostics.count { it.stage == ScreenCandidateStage.PREAUTH_TIMEOUT })
        val authenticated = diagnostics.single { it.stage == ScreenCandidateStage.AUTHENTICATED }
        assertEquals(2, authenticated.acceptedCandidates)
        assertEquals(2, authenticated.exactPeers)
        assertEquals(1, authenticated.preAuthTimeouts)
        assertEquals(1, authenticated.authenticatedCandidates)
    }

    @Test
    fun closeRetainsABlockedWorkerUntilAnExactRetryProvesRetirement() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val stream = newStream(retirementJoinTimeoutMillis = 50)
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    callbackEntered.countDown()
                    awaitUninterruptibly(releaseCallback)
                }
            },
        )
        val frame = authenticatedFramePacket()

        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(frame)
                flush()
            }
            assertTrue(callbackEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS))

            assertTrue(closeFailure(stream) is IOException)
            assertTrue(closeFailure(stream) is IOException)

            releaseCallback.countDown()
            // The 50 ms join is deliberately shorter than a loaded worker's unwind; an exact
            // retry is the production contract for proving retirement.
            closeEventually(stream)
            stream.close()
        }
    }

    @Test
    fun workerCannotClaimItsOwnRetirement() {
        val selfCloseFailure = AtomicReference<Throwable?>()
        val callbackDone = CountDownLatch(1)
        val stream = newStream(retirementJoinTimeoutMillis = 100)
        val port = stream.listen(
            object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                    selfCloseFailure.set(closeFailure(stream))
                    callbackDone.countDown()
                }
            },
        )

        val frame = authenticatedFramePacket()
        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(frame)
                flush()
            }
            assertTrue(callbackDone.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
        assertTrue(selfCloseFailure.get() is IOException)
        closeEventually(stream)
    }

    @Test
    fun diagnosticsIdentifyAnExpiredListenerWithZeroTcpCandidates() {
        val diagnostics = CopyOnWriteArrayList<ScreenCandidateDiagnostic>()
        val closed = CountDownLatch(1)
        val stream = newStream(
            limits = ScreenStreamLimits(acceptDeadlineMillis = 100, acceptPollMillis = 20),
            screenType = 111,
            diagnosticObserver = diagnostics::add,
        )
        stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) {
                closed.countDown()
            }
        })

        assertTrue(closed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        val summary = diagnostics.single { it.stage == ScreenCandidateStage.TERMINAL_DEADLINE }
        assertEquals(111, summary.screenType)
        assertEquals(0, summary.acceptedCandidates)
        assertEquals(0, summary.exactPeers)
        assertEquals(0, summary.authenticatedCandidates)
        assertFalse(diagnostics.any { it.stage == ScreenCandidateStage.CANDIDATE_ACCEPTED })
        stream.close()
    }

    @Test
    fun diagnosticsCountWrongPeerWithoutExposingItsAddress() {
        val diagnostics = CopyOnWriteArrayList<ScreenCandidateDiagnostic>()
        val wrongPeer = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val clock = ManualClock()
        val stream = newStream(
            limits = ScreenStreamLimits(acceptDeadlineMillis = 250, acceptPollMillis = 20),
            expectedPeerAddress = InetAddress.getByName("127.0.0.2"),
            monotonicNanos = clock::nanos,
            diagnosticObserver = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic.stage == ScreenCandidateStage.WRONG_PEER) wrongPeer.countDown()
            },
        )
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) {
                closed.countDown()
            }
        })
        try {
            Socket(loopback, port).use { awaitServerClose(it) }
        } catch (_: SocketException) {
            // The server resets a wrong peer immediately; on a loaded host that RST can reach
            // the client before connect() returns. The server-side diagnostic below is the proof.
        }
        assertTrue(wrongPeer.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1L, closed.count)

        clock.advanceMillis(251)
        assertTrue(closed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        val summary = diagnostics.single { it.stage == ScreenCandidateStage.TERMINAL_DEADLINE }
        assertEquals(1, summary.acceptedCandidates)
        assertEquals(1, summary.wrongPeers)
        assertEquals(0, summary.exactPeers)
        assertEquals(0, summary.authenticatedCandidates)
        val emitted = mutableListOf<String>()
        UltraDebugStatusMarkers.emitScreenCandidate(emitted::add, summary)
        if (UltraDebugStatusMarkers.enabled) {
            assertEquals(1, emitted.size)
            assertFalse(emitted.single().contains("127.0.0."))
            assertFalse(emitted.single().contains("port="))
            assertFalse(emitted.single().contains("connectionID"))
        } else {
            assertTrue(emitted.isEmpty())
        }
        stream.close()
    }

    @Test
    fun diagnosticsSeparateExactPeerPreauthStallFromZeroCandidates() {
        val diagnostics = CopyOnWriteArrayList<ScreenCandidateDiagnostic>()
        val preAuthTimeout = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val clock = ManualClock()
        val stream = newStream(
            limits = ScreenStreamLimits(
                acceptDeadlineMillis = GENEROUS_MILLIS,
                acceptPollMillis = 20,
                // The stall itself is a real socket read timeout; it only has to outlast the
                // client's write of an already-built config packet.
                candidateAuthTimeoutMillis = STALL_WINDOW_MILLIS,
            ),
            monotonicNanos = clock::nanos,
            diagnosticObserver = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic.stage == ScreenCandidateStage.PREAUTH_TIMEOUT) {
                    preAuthTimeout.countDown()
                }
            },
        )
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) {
                closed.countDown()
            }
        })
        val config = clearConfigPacket()
        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(config)
                flush()
            }
            awaitServerClose(client)
        }
        assertTrue(preAuthTimeout.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1L, closed.count)

        clock.advanceMillis(GENEROUS_MILLIS + 1L)
        assertTrue(closed.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        val summary = diagnostics.single { it.stage == ScreenCandidateStage.TERMINAL_DEADLINE }
        assertEquals(1, summary.acceptedCandidates)
        assertEquals(1, summary.exactPeers)
        assertEquals(1, summary.preAuthConfigs)
        assertEquals(1, summary.preAuthTimeouts)
        assertEquals(0, summary.authenticatedCandidates)
        stream.close()
    }

    @Test
    fun diagnosticsReportFirstAuthenticatedFrameAndAUniqueTerminalSummary() {
        val diagnostics = CopyOnWriteArrayList<ScreenCandidateDiagnostic>()
        val frameReady = CountDownLatch(1)
        val stream = newStream(screenType = 111, diagnosticObserver = diagnostics::add)
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                frameReady.countDown()
            }
        })
        val frame = authenticatedFramePacket()
        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(frame)
                flush()
            }
            assertTrue(frameReady.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
        stream.close()

        val authenticated = diagnostics.single { it.stage == ScreenCandidateStage.AUTHENTICATED }
        assertEquals(111, authenticated.screenType)
        assertEquals(1, authenticated.acceptedCandidates)
        assertEquals(1, authenticated.exactPeers)
        assertEquals(1, authenticated.authenticatedCandidates)
        assertEquals(1, diagnostics.count { it.stage.isTerminal })
    }

    @Test
    fun ownerCloseRacingTheWorkersOwnFinishDoesNotWaitOutTheJoinDeadline() {
        // The worker wins finish()'s closed CAS, then (before closing its endpoints) the owner's
        // close() takes the retirement lock and joins the worker. The worker must not need that
        // lock, or every such close stalls for the whole join deadline and then fails.
        val workerFinishing = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val frameReady = CountDownLatch(1)
        val stream = newStream(diagnosticObserver = { diagnostic ->
            if (diagnostic.stage.isTerminal &&
                diagnostic.stage != ScreenCandidateStage.TERMINAL_CLOSED
            ) {
                workerFinishing.countDown()
                awaitUninterruptibly(releaseWorker)
            }
        })
        val port = stream.listen(object : ScreenStream.Listener {
            override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                frameReady.countDown()
            }
        })
        val frame = authenticatedFramePacket()
        Socket(loopback, port).use { client ->
            client.getOutputStream().apply {
                write(frame)
                flush()
            }
            assertTrue(frameReady.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        }
        assertTrue(workerFinishing.await(AWAIT_SECONDS, TimeUnit.SECONDS))
        val closeFailure = AtomicReference<Throwable?>()
        val closer = Thread { closeFailure.set(closeFailure(stream)) }
        closer.start()
        // Wait until the owner's close() is parked in its bounded worker join.
        val parkedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (closer.state != Thread.State.TIMED_WAITING &&
            System.nanoTime() - parkedDeadline < 0L
        ) Thread.onSpinWait()
        assertEquals(Thread.State.TIMED_WAITING, closer.state)
        releaseWorker.countDown()
        closer.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS))
        assertFalse("close() waited out the join deadline", closer.isAlive)
        assertNull(closeFailure.get())
    }

    @Test
    fun diagnosticsStopAfterCloseEvenWhenAProvisionalCandidateReturnsLate() {
        val diagnostics = CopyOnWriteArrayList<ScreenCandidateDiagnostic>()
        val exactPeer = CountDownLatch(1)
        val stream = newStream(diagnosticObserver = { diagnostic ->
            diagnostics += diagnostic
            if (diagnostic.stage == ScreenCandidateStage.EXACT_PEER) exactPeer.countDown()
        })
        val port = stream.listen(object : ScreenStream.Listener {})
        Socket(loopback, port).use { client ->
            assertTrue(exactPeer.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            stream.close()
            stream.close()
            awaitServerClose(client)
        }

        assertEquals(1, diagnostics.count { it.stage.isTerminal })
        assertEquals(ScreenCandidateStage.TERMINAL_CLOSED, diagnostics.last().stage)
        assertEquals(1, diagnostics.last().acceptedCandidates)
        assertEquals(1, diagnostics.last().exactPeers)
        assertEquals(0, diagnostics.last().authenticatedCandidates)
    }

    private fun newStream(
        limits: ScreenStreamLimits = ScreenStreamLimits(
            acceptDeadlineMillis = GENEROUS_MILLIS,
            acceptPollMillis = 25,
            candidateAuthTimeoutMillis = GENEROUS_MILLIS,
            activeReadTimeoutMillis = GENEROUS_MILLIS,
        ),
        retirementJoinTimeoutMillis: Long = GENEROUS_MILLIS.toLong(),
        expectedPeerAddress: InetAddress = loopback,
        screenType: Int = 110,
        monotonicNanos: () -> Long = System::nanoTime,
        diagnosticObserver: ((ScreenCandidateDiagnostic) -> Unit)? = null,
    ): ScreenStream = ScreenStream(
        key = key,
        bindAddress = loopback,
        expectedPeerAddress = expectedPeerAddress,
        screenType = screenType,
        limits = limits,
        monotonicNanos = monotonicNanos,
        retirementJoinTimeoutMillis = retirementJoinTimeoutMillis,
        diagnosticObserver = diagnosticObserver,
    )

    private fun closeFailure(stream: ScreenStream): Throwable? = try {
        stream.close()
        null
    } catch (failure: Throwable) {
        failure
    }

    /** Retries only the retained-worker failure, as a production owner may, within a bound. */
    private fun closeEventually(stream: ScreenStream) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (true) {
            val failure = closeFailure(stream) ?: return
            if (failure !is IOException || System.nanoTime() - deadline >= 0L) throw failure
        }
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (latch.count != 0L) {
            try {
                latch.await()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun authenticatedFramePacket(
        presentationTime: Long = 0L,
        sequence: Long = 0L,
        timelineId: Long = 0L,
    ): ByteArray {
        val plain = byteArrayOf(0, 0, 0, 2, 0x65, 0x01)
        val header = screenHeader(
            opcode = 0,
            bodySize = plain.size + ScreenCodec.TAG_SIZE,
            presentationTime = presentationTime,
            timelineId = timelineId,
        )
        val sealed = AirPlayCrypto.chachaSeal(
            key,
            AirPlayCrypto.nonce64(sequence),
            plain,
            header,
        )
        return header + sealed
    }

    private fun clearConfigPacket(): ByteArray {
        val codecData = byteArrayOf(1, 0, 0, 0, 0xff.toByte())
        val body = ByteArray(4) + "avcC".toByteArray(Charsets.US_ASCII) + codecData
        return messagePacket(opcode = 1, body = body)
    }

    private fun messagePacket(opcode: Int, body: ByteArray): ByteArray =
        screenHeader(opcode, body.size) + body

    private fun screenHeader(
        opcode: Int,
        bodySize: Int,
        presentationTime: Long = 0L,
        timelineId: Long = 0L,
    ): ByteArray = ByteArray(128).also { header ->
        var size = bodySize
        repeat(4) { index ->
            header[index] = size.toByte()
            size = size ushr 8
        }
        header[4] = opcode.toByte()
        var timestamp = presentationTime
        repeat(8) { index ->
            header[8 + index] = timestamp.toByte()
            timestamp = timestamp ushr 8
        }
        var rawTimelineId = timelineId
        repeat(8) { index ->
            header[0x28 + index] = rawTimelineId.toByte()
            rawTimelineId = rawTimelineId ushr 8
        }
    }

    private fun awaitServerClose(socket: Socket) {
        socket.soTimeout = TimeUnit.SECONDS.toMillis(AWAIT_SECONDS).toInt()
        try {
            while (socket.getInputStream().read() >= 0) {
                // The server sends no bytes; reaching here only handles an unusual half-close.
            }
        } catch (_: SocketException) {
            // SO_LINGER(0) commonly reports a reset instead of EOF.
        }
    }

    /** Frozen monotonic time; only an explicit advance can expire a deadline under test. */
    private class ManualClock {
        private val now = AtomicLong(0L)

        fun nanos(): Long = now.get()

        fun advanceMillis(millis: Long) {
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis))
        }
    }

    private companion object {
        /** Upper bound for awaits; a passing test returns as soon as its latch opens. */
        const val AWAIT_SECONDS = 10L
        /** For limits that are not under test, so scheduling delay cannot trip them. */
        const val GENEROUS_MILLIS = 30_000
        /** A real read-timeout stall that must outlast one write of a pre-built packet. */
        const val STALL_WINDOW_MILLIS = 1_000
    }
}
