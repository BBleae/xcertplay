package com.shilapi.xcertplay.media

import android.content.ContextWrapper
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidMediaSinkPreviewHandoffTest {
    @Test
    fun admittedSuccessorCannotCommitAfterPhysicalHandoffGateBegins() {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        val old = unsafe.allocateInstance(Surface::class.java) as Surface
        val successor = unsafe.allocateInstance(Surface::class.java) as Surface
        val sink = AndroidMediaSink(context)
        sink.setSurface(111, old)

        val reserved = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val outcome = AtomicReference<Boolean?>()
        val failure = AtomicReference<Throwable?>()
        sink.previewAfterReserveTestHook = {
            reserved.countDown()
            check(resume.await(5, TimeUnit.SECONDS)) { "preview reservation pause timed out" }
        }
        val worker = Thread {
            try {
                outcome.set(sink.trySetSurfaceForPreview(111, successor))
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(reserved.await(5, TimeUnit.SECONDS))
            val gate = requireNotNull(sink.beginPhysicalSurfaceHandoff(111, old))
            try {
                resume.countDown()
                worker.join(5_000)
                assertFalse(worker.isAlive)
                assertNull(failure.get())
                assertEquals(false, outcome.get())
                assertEquals(AndroidSurfaceReservationState.FREE,
                    AndroidMediaSink.surfaceReservationState(successor))
                assertEquals(AndroidSurfaceReservationState.RESERVED,
                    AndroidMediaSink.surfaceReservationState(old))
            } finally {
                gate.close() // no sink route was retired, so the old registration remains
            }
        } finally {
            resume.countDown()
            worker.join(5_000)
            sink.previewAfterReserveTestHook = null
            sink.clearSurface(111, old)
        }
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(old))
    }
}
