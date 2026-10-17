package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AndroidSurfaceReservationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HostSurfaceAdmissionTest {
    @Test fun poisonedDirectDecoderRouteReportsUnavailableWithoutThrowingFromTextureCallback() {
        val (sink, surface) = sinkAndSurface()
        poison(sink, 110)
        val failures = ArrayList<Throwable?>()
        val route = CurrentHostDisplaySurfaceRoute(null, sink) { type, failure ->
            assertEquals(110, type)
            failures += failure
        }
        route.setDecoderSurface(110, surface)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(surface))
    }

    @Test fun poisonedWebPreviewReportsUnavailableWithoutClaimingSurface() {
        val (sink, surface) = sinkAndSurface()
        poison(sink, 111)
        val web = CarPlayWebDisplayFactory.Session(
            configs = mapOf(111 to CarPlayWebDisplayConfig(true, 800, 480)),
            displays = mapOf(111 to WebDisplayChannel(111, 800, 480)),
            stopServer = {},
        )
        web.bind(sink)
        val failures = ArrayList<Throwable?>()
        val route = CurrentHostDisplaySurfaceRoute(web, sink) { type, failure ->
            assertEquals(111, type)
            failures += failure
        }
        route.preview(111, surface)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(surface))
        web.close()
    }

    private fun sinkAndSurface(): Pair<AndroidMediaSink, Surface> {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        val surface = unsafe.allocateInstance(Surface::class.java) as Surface
        return AndroidMediaSink(context) to surface
    }

    @Suppress("UNCHECKED_CAST")
    private fun poison(sink: AndroidMediaSink, type: Int) {
        val field = AndroidMediaSink::class.java.getDeclaredField("poisonedVideoTypes")
            .apply { isAccessible = true }
        (field.get(sink) as MutableSet<Int>).add(type)
    }
}
