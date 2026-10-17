package com.shilapi.xcertplay.airplay.mainbuffered

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MainBufferedPcm16ControlledOutputTest {
    @Test
    fun stopAnchorStartRequiresExactClockProofAndFirstQueuedSample() {
        val native = FakeNativeTrack()
        val clock = AtomicLong(100L)
        val output = opened(native, clock)
        try {
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED, output.playbackState)
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            assertEquals(1_000L, output.firstQueuedMediaTimeValue)
            assertFalse(native.written.await(80L, TimeUnit.MILLISECONDS))
            assertEquals(0, native.plays.get())

            assertNull(verifiedAnchor(output.outputInstance, output.playbackEpoch, 1_000L,
                100L, 200L, mapping = null))
            val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 200L))
            val started = AtomicReference<Boolean>()
            val starter = Thread { started.set(output.startPlayback(proof)) }
            starter.start()
            assertTrue(native.written.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertEquals(0, native.plays.get())
            clock.set(200L)
            starter.join(TIMEOUT_MILLIS)
            assertFalse(starter.isAlive)
            assertEquals(true, started.get())
            await { native.plays.get() == 1 }
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.PLAYING, output.playbackState)
        } finally {
            output.close()
        }
        assertEquals(1, native.releases.get())
    }

    @Test
    fun flushRevokesAnArmedReceiptAndRetiresQueuedAndNativeSamples() {
        val native = FakeNativeTrack()
        val clock = AtomicLong(100L)
        val output = opened(native, clock)
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val old = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 200L))
            val firstResult = AtomicReference<Boolean>()
            val firstStarter = Thread { firstResult.set(output.startPlayback(old)) }
            firstStarter.start()
            assertTrue(native.written.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            output.flush()
            firstStarter.join(TIMEOUT_MILLIS)
            assertFalse(firstStarter.isAlive)
            assertEquals(false, firstResult.get())
            assertEquals(1L, output.playbackEpoch)
            assertEquals(0, output.queuedPackets)
            assertNull(output.firstQueuedMediaTimeValue)
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED, output.playbackState)
            clock.set(200L)
            assertFalse(output.startPlayback(old))
            assertEquals(0, native.plays.get())
            assertTrue(native.pauses.get() >= 1)
            assertTrue(native.flushes.get() >= 1)

            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 3_000L))
            val next = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                3_000L, 200L, 300L))
            val nextResult = AtomicReference<Boolean>()
            val nextStarter = Thread { nextResult.set(output.startPlayback(next)) }
            nextStarter.start()
            clock.set(300L)
            nextStarter.join(TIMEOUT_MILLIS)
            assertFalse(nextStarter.isAlive)
            assertEquals(true, nextResult.get())
            await { native.plays.get() == 1 }
        } finally {
            output.close()
        }
    }

    @Test
    fun pauseStopsNativePlaybackAndRequiresFreshEpochProofToDriveAgain() {
        val native = FakeNativeTrack()
        val clock = AtomicLong(100L)
        val output = opened(native, clock)
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val first = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 100L))
            assertTrue(output.startPlayback(first))
            await { native.plays.get() == 1 }
            output.pausePlayback()
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED, output.playbackState)
            assertTrue(native.pauses.get() >= 1)
            assertTrue(native.flushes.get() >= 1)
            assertFalse(output.startPlayback(first))

            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 3_000L))
            val next = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                3_000L, 100L, 100L))
            assertTrue(output.startPlayback(next))
            await { native.plays.get() == 2 }
        } finally {
            output.close()
        }
    }

    @Test
    fun staleLateOrMismatchedProofNeverStartsNativePlayback() {
        val native = FakeNativeTrack()
        val clock = AtomicLong(100L)
        val output = opened(native, clock)
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val wrongEpoch = checkNotNull(verifiedAnchor(output.outputInstance, 99L,
                1_000L, 100L, 200L))
            assertFalse(output.startPlayback(wrongEpoch))
            val foreignOutput = checkNotNull(verifiedAnchor(Any(), 0L, 1_000L, 100L, 200L))
            assertFalse(output.startPlayback(foreignOutput))
            val skippedHead = checkNotNull(verifiedAnchor(output.outputInstance, 0L,
                900L, 100L, 200L))
            assertFalse(output.startPlayback(skippedHead))
            val expired = checkNotNull(verifiedAnchor(output.outputInstance, 0L,
                1_000L, 100L, 200L))
            clock.set(1_000_000_000L)
            assertFalse(output.startPlayback(expired))
            assertEquals(0, native.plays.get())
            assertEquals(0, native.writes.get())
        } finally {
            output.close()
        }
    }

    @Test
    fun silentQueueHasBoundedBackpressureAndCloseReleaseCanBeRetried() {
        val native = FakeNativeTrack().apply { failNextRelease = true }
        val clock = AtomicLong(100L)
        val closed = AtomicInteger()
        val output = MainBufferedPcm16ControlledOutput(native, { closed.incrementAndGet() }, clock::get)
        output.start()
        repeat(32) { index ->
            assertTrue(output.offerPcmLittleEndian(ByteArray(4), index.toLong()))
        }
        assertFalse(output.offerPcmLittleEndian(ByteArray(4), 32L))
        assertEquals(32, output.queuedPackets)
        assertEquals(0, native.writes.get())
        try {
            output.close()
            fail("first native release must remain retryable")
        } catch (_: IOException) {
            assertEquals(0, closed.get())
        }
        output.close()
        output.close()
        assertEquals(1, closed.get())
        assertEquals(1, native.releases.get())
        assertFalse(output.offerPcmLittleEndian(ByteArray(4), 33L))
    }

    @Test
    fun failedNativePauseStillFlushesAndRetiresTrackBeforeReportingFailure() {
        val native = FakeNativeTrack().apply { failNextPause = true }
        val output = opened(native, AtomicLong(100L))
        try {
            output.pausePlayback()
            fail("failed native pause must not be acknowledged")
        } catch (_: IOException) {
            assertFalse(output.isReady)
            assertTrue(native.flushes.get() >= 1)
            assertEquals(1, native.releases.get())
        }
        output.close()
        assertEquals(1, native.releases.get())
    }

    @Test
    fun idleWorkerWriteFailureReleasesAudibleTrackEvenWhenPauseAndFlushFail() {
        val native = FakeNativeTrack()
        val clock = AtomicLong(100L)
        val closed = AtomicInteger()
        val closedLatch = CountDownLatch(1)
        val output = MainBufferedPcm16ControlledOutput(native, {
            closed.incrementAndGet()
            closedLatch.countDown()
        }, clock::get)
        output.start()
        assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
        val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
            1_000L, 100L, 100L))
        assertTrue(output.startPlayback(proof))
        await { native.plays.get() == 1 }
        await { native.audible.get() }

        native.failNextWrite = true
        native.failNextPause = true
        native.failNextFlush = true
        native.failNextRelease = true
        assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 3_048L))

        // No later offer or teardown drives cleanup: the terminal worker owns it.
        assertTrue(closedLatch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals(1, closed.get())
        assertEquals(1, native.releases.get())
        assertFalse(native.audible.get())
        assertFalse(output.isReady)
        assertFalse(output.offerPcmLittleEndian(ByteArray(4), 5_096L))
        output.close()
        assertEquals(1, native.releases.get())
        assertEquals(1, closed.get())
    }

    @Test
    fun sustainedZeroNativeWritesCannotAcknowledgeOrKeepAnIdleTrackForever() {
        val native = FakeNativeTrack().apply { alwaysZeroWrite = true }
        val closed = AtomicInteger()
        val closedLatch = CountDownLatch(1)
        val output = MainBufferedPcm16ControlledOutput(native, {
            closed.incrementAndGet()
            closedLatch.countDown()
        }, AtomicLong(100L)::get)
        output.start()
        assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
        val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
            1_000L, 100L, 100L))

        assertFalse(output.startPlayback(proof))
        // No follow-on producer or teardown is needed to release the native owner.
        assertTrue(closedLatch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
        assertEquals(1, closed.get())
        assertEquals(1, native.releases.get())
        assertEquals(0, native.plays.get())
        assertFalse(native.audible.get())
        assertFalse(output.isReady)
        assertFalse(output.offerPcmLittleEndian(ByteArray(4), 3_048L))
        output.close()
    }

    @Test
    fun clockReceiptRejectsMissingNetworkTimeScaleAndUnboundedCalibration() {
        val outputInstance = Any()
        val networkTime = MainBufferedNetworkTime(1, 2L, 3L, 0L)
        val point = MainBufferedTimelinePoint(4L, 1_000L, 48_000L)
        val audible = MainBufferedTimelinePoint(4L, 1_000L, 48_000L)
        val anchor = MainBufferedPlaybackAnchor(point, networkTime, audible)
        assertNull(MainBufferedPcm16VerifiedAnchor.verify(anchor, outputInstance, 0L, null, 100L))
        assertNull(MainBufferedPcm16VerifiedAnchor.verify(anchor.copy(networkTime = null),
            outputInstance, 0L, MainBufferedPcm16AudioClockMapping { instance, _ ->
                MainBufferedPcm16CalibratedPlayCall(instance, networkTime, 200L, 300L, 0L)
            }, 100L))
        assertNull(MainBufferedPcm16VerifiedAnchor.verify(anchor.copy(
            firstAudiblePoint = audible.copy(mediaTimeScale = 44_100L),
        ), outputInstance, 0L, MainBufferedPcm16AudioClockMapping { instance, _ ->
            MainBufferedPcm16CalibratedPlayCall(instance, networkTime, 200L, 300L, 0L)
        }, 100L))
        assertNull(MainBufferedPcm16VerifiedAnchor.verify(anchor, outputInstance, 0L,
            MainBufferedPcm16AudioClockMapping { instance, _ ->
                MainBufferedPcm16CalibratedPlayCall(instance, networkTime, 200L, 300L, 5_000_001L)
            }, 100L))
        assertNull(MainBufferedPcm16VerifiedAnchor.verify(anchor, outputInstance, 0L,
            MainBufferedPcm16AudioClockMapping { _, _ ->
                MainBufferedPcm16CalibratedPlayCall(Any(), networkTime, 200L, 300L, 0L)
            }, 100L))
        assertNotNull(verifiedAnchor(outputInstance, 0L, 1_000L, 100L, 200L))
    }

    @Test
    fun selected44100OutputRejectsBorrowed48000ClockProof() {
        val clock = AtomicLong(100L)
        val native = FakeNativeTrack()
        val output = MainBufferedPcm16ControlledOutput(native, {}, clock::get,
            sampleRateHz = 44_100).also { it.start() }
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 44_100L))
            val wrongRate = checkNotNull(verifiedAnchor(output.outputInstance,
                output.playbackEpoch, 44_100L, 100L, 100L))
            assertFalse(output.startPlayback(wrongRate))
            assertEquals(0, native.writes.get())
            assertEquals(0, native.plays.get())

            val networkTime = MainBufferedNetworkTime(1, 2L, 3L, 0L)
            val anchor = MainBufferedPlaybackAnchor(
                point = MainBufferedTimelinePoint(7L, 44_100L, 44_100L),
                networkTime = networkTime,
                firstAudiblePoint = MainBufferedTimelinePoint(7L, 44_100L, 44_100L),
            )
            val mapping = MainBufferedPcm16AudioClockMapping { instance, _ ->
                MainBufferedPcm16CalibratedPlayCall(instance, networkTime, 100L,
                    5_000_100L, 5_000_000L)
            }
            assertNull(MainBufferedPcm16VerifiedAnchor.verify(anchor, output.outputInstance,
                output.playbackEpoch, mapping, 100L))
            val correct = checkNotNull(MainBufferedPcm16VerifiedAnchor.verify(anchor,
                output.outputInstance, output.playbackEpoch, mapping, 100L,
                sampleRateHz = 44_100))
            assertEquals(44_100, correct.sampleRateHz)
            assertTrue(output.startPlayback(correct))
            await { native.plays.get() == 1 }
        } finally {
            output.close()
        }
    }

    @Test
    fun blockedNativePlayTimesOutWithoutHoldingAdmissionAndLateReturnIsStoppedBeforeRetryRelease() {
        val releasePlay = CountDownLatch(1)
        val native = FakeNativeTrack().apply { blockPlayUntil = releasePlay }
        val closed = AtomicInteger()
        val output = MainBufferedPcm16ControlledOutput(native, { closed.incrementAndGet() },
            AtomicLong(100L)::get, startAckWaitMillis = 100L, closeJoinMillis = 50L)
        output.start()
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 100L))
            val startFailure = AtomicReference<Throwable?>()
            val startDone = CountDownLatch(1)
            val starter = Thread {
                try {
                    output.startPlayback(proof)
                    startFailure.set(AssertionError("blocked native play must not be acknowledged"))
                } catch (failure: Throwable) {
                    startFailure.set(failure)
                } finally {
                    startDone.countDown()
                }
            }
            starter.start()
            assertTrue(native.playEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 3_048L))
            val writesBeforeRevoke = native.writes.get()
            assertTrue("start timeout must revoke without waiting for native play",
                startDone.await(2L, TimeUnit.SECONDS))
            assertTrue(startFailure.get() is IOException)
            assertFalse(output.isReady)
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED,
                output.playbackState)
            assertEquals(0, output.queuedPackets)
            assertFalse(output.offerPcmLittleEndian(ByteArray(4), 5_096L))
            assertEquals(0, closed.get())
            assertEquals(0, native.releases.get())
            // Repeated close cannot claim retirement while the native call still owns the track.
            org.junit.Assert.assertThrows(IOException::class.java) { output.close() }
            assertEquals(0, native.releases.get())

            releasePlay.countDown()
            await { native.pauses.get() > 0 && native.flushes.get() > 0 }
            assertFalse(native.audible.get())
            retryClose(output)
            assertEquals(writesBeforeRevoke, native.writes.get())
            assertEquals(1, native.plays.get())
            assertEquals(1, native.releases.get())
            assertEquals(1, closed.get())
            output.close()
            assertEquals(1, native.releases.get())
            assertEquals(1, closed.get())
        } finally {
            releasePlay.countDown()
            retryClose(output)
        }
    }

    @Test
    fun closeRevokesBlockedNativePlayBeforeJoinAndNeverAcknowledgesLatePlay() {
        val releasePlay = CountDownLatch(1)
        val native = FakeNativeTrack().apply { blockPlayUntil = releasePlay }
        val closed = AtomicInteger()
        val output = MainBufferedPcm16ControlledOutput(native, { closed.incrementAndGet() },
            AtomicLong(100L)::get, startAckWaitMillis = 1_000L, closeJoinMillis = 50L)
        output.start()
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 100L))
            val started = AtomicReference<Boolean?>()
            val starter = Thread { started.set(output.startPlayback(proof)) }
            starter.start()
            assertTrue(native.playEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            val closeFailure = AtomicReference<Throwable?>()
            val closeDone = CountDownLatch(1)
            val closer = Thread {
                try { output.close() } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally { closeDone.countDown() }
            }
            closer.start()
            assertTrue("close must publish revocation without waiting for native play",
                closeDone.await(1L, TimeUnit.SECONDS))
            starter.join(1_000L)
            assertFalse(starter.isAlive)
            assertEquals(false, started.get())
            assertTrue(closeFailure.get() is IOException)
            assertFalse(output.offerPcmLittleEndian(ByteArray(4), 3_048L))
            assertEquals(0, native.releases.get())
            assertEquals(0, closed.get())

            releasePlay.countDown()
            await { native.pauses.get() > 0 && native.flushes.get() > 0 }
            assertFalse(native.audible.get())
            retryClose(output)
            assertEquals(1, native.releases.get())
            assertEquals(1, closed.get())
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED,
                output.playbackState)
        } finally {
            releasePlay.countDown()
            retryClose(output)
        }
    }

    @Test
    fun flushDuringBlockedNativePlayRevokesItsEpochButCannotAcknowledgeNativeRetirement() {
        val releasePlay = CountDownLatch(1)
        val native = FakeNativeTrack().apply { blockPlayUntil = releasePlay }
        val output = MainBufferedPcm16ControlledOutput(native, {}, AtomicLong(100L)::get,
            startAckWaitMillis = 1_000L, closeJoinMillis = 50L)
        output.start()
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 100L))
            val started = AtomicReference<Boolean?>()
            val starter = Thread { started.set(output.startPlayback(proof)) }
            starter.start()
            assertTrue(native.playEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            val failure = org.junit.Assert.assertThrows(IOException::class.java) {
                output.flush()
            }
            assertTrue(failure.message?.contains("still in flight") == true)
            starter.join(1_000L)
            assertFalse(starter.isAlive)
            assertEquals(false, started.get())
            assertEquals(1L, output.playbackEpoch)
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED,
                output.playbackState)
            assertEquals(0, native.releases.get())
            assertFalse(output.offerPcmLittleEndian(ByteArray(4), 3_048L))

            releasePlay.countDown()
            await { native.pauses.get() > 0 && native.flushes.get() > 0 }
            assertFalse(native.audible.get())
            retryClose(output)
            assertEquals(1, native.releases.get())
        } finally {
            releasePlay.countDown()
            retryClose(output)
        }
    }

    @Test
    fun blockedNativeWriteCannotHoldAdmissionOrPermitReleaseBeforeLateCompensation() {
        val releaseWrite = CountDownLatch(1)
        val native = FakeNativeTrack().apply { blockWriteUntil = releaseWrite }
        val closed = AtomicInteger()
        val output = MainBufferedPcm16ControlledOutput(native, { closed.incrementAndGet() },
            AtomicLong(100L)::get, startAckWaitMillis = 1_000L, closeJoinMillis = 50L)
        output.start()
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 100L))
            val started = AtomicReference<Boolean?>()
            val starter = Thread { started.set(output.startPlayback(proof)) }
            starter.start()
            assertTrue(native.writeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))

            val closeFailure = AtomicReference<Throwable?>()
            val closeDone = CountDownLatch(1)
            Thread {
                try { output.close() } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally { closeDone.countDown() }
            }.start()
            assertTrue("blocked write must not hold the admission monitor",
                closeDone.await(1L, TimeUnit.SECONDS))
            starter.join(1_000L)
            assertFalse(starter.isAlive)
            assertEquals(false, started.get())
            assertTrue(closeFailure.get() is IOException)
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED,
                output.playbackState)
            assertFalse(output.offerPcmLittleEndian(ByteArray(4), 3_048L))
            assertEquals(0, native.plays.get())
            assertEquals(0, native.releases.get())
            assertEquals(0, closed.get())

            releaseWrite.countDown()
            await { native.pauses.get() > 0 && native.flushes.get() > 0 }
            retryClose(output)
            assertEquals(1, native.writes.get())
            assertEquals(0, native.plays.get())
            assertEquals(1, native.releases.get())
            assertEquals(1, closed.get())
        } finally {
            releaseWrite.countDown()
            retryClose(output)
        }
    }

    @Test
    fun blockedNativeWriteCannotExtendStartAckTimeoutOrBecomePlayingAfterLateReturn() {
        val releaseWrite = CountDownLatch(1)
        val native = FakeNativeTrack().apply { blockWriteUntil = releaseWrite }
        val output = MainBufferedPcm16ControlledOutput(native, {}, AtomicLong(100L)::get,
            startAckWaitMillis = 100L, closeJoinMillis = 50L)
        output.start()
        try {
            assertTrue(output.offerPcmLittleEndian(ByteArray(PREBUFFER_BYTES), 1_000L))
            val proof = checkNotNull(verifiedAnchor(output.outputInstance, output.playbackEpoch,
                1_000L, 100L, 100L))
            val failure = AtomicReference<Throwable?>()
            val done = CountDownLatch(1)
            Thread {
                try { output.startPlayback(proof) } catch (problem: Throwable) {
                    failure.set(problem)
                } finally { done.countDown() }
            }.start()
            assertTrue(native.writeEntered.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS))
            assertTrue("start deadline must revoke despite blocked JNI write",
                done.await(2L, TimeUnit.SECONDS))
            assertTrue(failure.get() is IOException)
            assertFalse(output.isReady)
            assertEquals(MainBufferedPcm16OutputAdmission.PlaybackState.STOPPED,
                output.playbackState)
            assertEquals(0, native.plays.get())
            assertEquals(0, native.releases.get())

            releaseWrite.countDown()
            await { native.pauses.get() > 0 && native.flushes.get() > 0 }
            retryClose(output)
            assertEquals(0, native.plays.get())
            assertEquals(1, native.releases.get())
        } finally {
            releaseWrite.countDown()
            retryClose(output)
        }
    }

    private fun retryClose(output: MainBufferedPcm16ControlledOutput) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (true) {
            try {
                output.close()
                return
            } catch (failure: IOException) {
                if (System.nanoTime() >= deadline) throw failure
                Thread.sleep(5L)
            }
        }
    }

    private fun opened(native: FakeNativeTrack, clock: AtomicLong): MainBufferedPcm16ControlledOutput =
        MainBufferedPcm16ControlledOutput(native, {}, clock::get).also { it.start() }

    private fun verifiedAnchor(
        outputInstance: Any,
        epoch: Long,
        firstMediaTime: Long,
        now: Long,
        startAt: Long,
        mapping: MainBufferedPcm16AudioClockMapping? = MainBufferedPcm16AudioClockMapping { instance, anchor ->
            MainBufferedPcm16CalibratedPlayCall(
                outputInstance = instance,
                networkTime = checkNotNull(anchor.networkTime),
                playCallAtMonotonicNanos = startAt,
                validUntilMonotonicNanos = startAt + 5_000_000L,
                maximumErrorNanos = 5_000_000L,
            )
        },
    ): MainBufferedPcm16VerifiedAnchor? = MainBufferedPcm16VerifiedAnchor.verify(
        MainBufferedPlaybackAnchor(
            point = MainBufferedTimelinePoint(7L, firstMediaTime, 48_000L),
            networkTime = MainBufferedNetworkTime(1, 2L, 3L, 0L),
            firstAudiblePoint = MainBufferedTimelinePoint(7L, firstMediaTime, 48_000L),
        ),
        outputInstance,
        epoch,
        mapping,
        now,
    )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5L)
        assertTrue("timed out waiting for native output event", condition())
    }

    private class FakeNativeTrack : MainBufferedPcm16NativeTrack {
        val writes = AtomicInteger()
        val plays = AtomicInteger()
        val pauses = AtomicInteger()
        val flushes = AtomicInteger()
        val releases = AtomicInteger()
        val audible = AtomicBoolean()
        val written = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        val playEntered = CountDownLatch(1)
        @Volatile var blockWriteUntil: CountDownLatch? = null
        @Volatile var blockPlayUntil: CountDownLatch? = null
        @Volatile var alwaysZeroWrite = false
        @Volatile var failNextWrite = false
        @Volatile var failNextPause = false
        @Volatile var failNextFlush = false
        @Volatile var failNextRelease = false

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            writes.incrementAndGet()
            written.countDown()
            writeEntered.countDown()
            blockWriteUntil?.let { gate ->
                while (gate.count > 0L) {
                    try { gate.await(10L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                        // Simulate a JNI write that does not respond to Java interruption.
                    }
                }
            }
            if (failNextWrite) {
                failNextWrite = false
                throw IOException("injected native write failure")
            }
            if (alwaysZeroWrite) return 0
            return length
        }

        override fun play() {
            plays.incrementAndGet()
            playEntered.countDown()
            blockPlayUntil?.let { gate ->
                while (gate.count > 0L) {
                    try { gate.await(10L, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
                        // Simulate a platform call that cannot be canceled by Java interruption.
                    }
                }
            }
            audible.set(true)
        }
        override fun pause() {
            if (failNextPause) {
                failNextPause = false
                throw IOException("injected pause failure")
            }
            pauses.incrementAndGet()
            audible.set(false)
        }
        override fun flush() {
            if (failNextFlush) {
                failNextFlush = false
                throw IOException("injected flush failure")
            }
            flushes.incrementAndGet()
        }
        override fun release() {
            if (failNextRelease) {
                failNextRelease = false
                throw IOException("injected release failure")
            }
            releases.incrementAndGet()
            audible.set(false)
        }
    }

    private companion object {
        const val PREBUFFER_BYTES = 8 * 1024
        const val TIMEOUT_MILLIS = 3_000L
    }
}
