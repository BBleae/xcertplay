package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoHandleTerminationGateTest {
    @Test
    fun terminationWaitsBoundedlyForEnteredCallbackThenRejectsEveryLateCallback() {
        val gate = VideoHandleTerminationGate<Any>()
        val handle = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val cleanupRan = AtomicBoolean(false)
        val terminationResult = java.util.concurrent.atomic.AtomicReference<VideoHandleTerminationResult>()
        val callback = Thread {
            gate.withLive(handle, false) {
                entered.countDown()
                release.await()
                callbackFinished.countDown()
                true
            }
        }
        callback.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val termination = Thread {
            terminationResult.set(gate.terminate(handle, 1_000) { cleanupRan.set(true) })
        }
        termination.start()
        assertFalse(cleanupRan.get())
        release.countDown()
        termination.join(1_000)
        callback.join(1_000)

        assertTrue(callbackFinished.await(0, TimeUnit.MILLISECONDS))
        assertTrue(cleanupRan.get())
        assertEquals(VideoHandleTerminationResult.CLEANED, terminationResult.get())
        assertTrue(gate.isTerminal(handle))
        assertFalse(gate.withLive(handle, false) { true })
    }

    @Test
    fun failedTerminalCleanupCanRetryButHandleNeverReopens() {
        val gate = VideoHandleTerminationGate<Any>()
        val handle = Any()
        val attempts = AtomicInteger()

        assertThrows(IllegalStateException::class.java) {
            gate.terminate(handle, 0) {
                attempts.incrementAndGet()
                throw IllegalStateException("first cleanup failed")
            }
        }
        assertFalse(gate.withLive(handle, false) { true })

        assertEquals(
            VideoHandleTerminationResult.CLEANED,
            gate.terminate(handle, 0) { attempts.incrementAndGet() },
        )
        assertEquals(
            VideoHandleTerminationResult.ALREADY_CLEANED,
            gate.terminate(handle, 0) { attempts.incrementAndGet() },
        )

        assertEquals(2, attempts.get())
        assertTrue(gate.isTerminal(handle))
    }

    @Test
    fun reentrantTerminationTombstonesImmediatelyAndDefersCleanup() {
        val gate = VideoHandleTerminationGate<Any>()
        val handle = Any()
        val mutationsAfterTermination = AtomicInteger()
        val cleanups = AtomicInteger()

        val result = gate.withLive(handle, VideoHandleTerminationResult.INTERRUPTED) {
            val termination = gate.terminate(handle, 0) { cleanups.incrementAndGet() }
            if (!gate.isTerminal(handle)) mutationsAfterTermination.incrementAndGet()
            termination
        }

        assertEquals(VideoHandleTerminationResult.TIMED_OUT, result)
        assertEquals(0, cleanups.get())
        assertEquals(0, mutationsAfterTermination.get())
        assertFalse(gate.withLive(handle, false) { true })
        assertEquals(
            VideoHandleTerminationResult.CLEANED,
            gate.terminate(handle, 0) { cleanups.incrementAndGet() },
        )
        assertEquals(1, cleanups.get())
    }

    @Test
    fun blockingCallbackCannotMakeTerminationWaitPastItsDeadline() {
        val gate = VideoHandleTerminationGate<Any>()
        val handle = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callback = Thread {
            gate.withLive(handle, Unit) {
                entered.countDown()
                release.await()
            }
        }
        callback.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals(
            VideoHandleTerminationResult.TIMED_OUT,
            gate.terminate(handle, 20) { throw AssertionError("must not clean while in flight") },
        )
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        assertFalse(gate.tryWithLive(handle, false) { true })

        release.countDown()
        callback.join(1_000)
        assertEquals(
            VideoHandleTerminationResult.CLEANED,
            gate.terminate(handle, 0) {},
        )
    }

    @Test
    fun externalCallbackReservedAfterAdmissionDoesNotBlockGlobalCloseBarrier() {
        val gate = VideoHandleTerminationGate<Any>()
        val callbacks = SerializedCallbackQueue()
        val handle = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val task = gate.withLive(handle, null) {
            callbacks.reserve {
                entered.countDown()
                release.await()
            }
        }
        val callbackThread = Thread { checkNotNull(task).run() }
        callbackThread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals(
            VideoHandleTerminationResult.CLEANED,
            gate.markAllTerminalAndAwait(20),
        )
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)

        release.countDown()
        callbackThread.join(1_000)
    }
}
