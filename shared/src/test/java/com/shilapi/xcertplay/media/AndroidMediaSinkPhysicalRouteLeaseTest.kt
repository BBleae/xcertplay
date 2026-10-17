package com.shilapi.xcertplay.media

import android.content.ContextWrapper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidMediaSinkPhysicalRouteLeaseTest {
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe

    @Test
    fun preSetupClaimProvesConfiguredExclusiveRouteWithoutPretendingDecoderExists() {
        val sink = sink()
        val physical = surface()
        val rival = surface()
        val handle = handle("first")
        val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        try {
            assertTrue(lease.isCurrent)
            assertFalse(lease.decoderAttached)
            assertEquals(AndroidSurfaceReservationState.RESERVED,
                AndroidMediaSink.surfaceReservationState(physical))
            assertFalse(sink.trySetSurfaceForPreview(111, rival))
            assertThrows(IllegalStateException::class.java) { sink.setSurface(111, rival) }
            assertThrows(IllegalStateException::class.java) {
                sink.acquireSurfaceLease(handle, rival)
            }
            assertNull(sink.beginPhysicalSurfaceHandoff(111, physical))
            assertEquals(AndroidSurfaceReservationState.FREE,
                AndroidMediaSink.surfaceReservationState(rival))
        } finally {
            lease.close()
            sink.close()
        }
        assertFalse(lease.isCurrent)
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(physical))
    }

    @Test
    fun firstSelectedStreamBindsProvisionalSurfaceAndTerminalRetirementRevokesIt() {
        val sink = sink()
        val physical = surface()
        val handle = handle("first")
        val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        val revoked = CountDownLatch(1)
        val subscription = lease.subscribeRevocation { revoked.countDown() }
        try {
            assertTrue(lease.isCurrent)
            sink.onScreenStreamActive(handle, true)
            assertTrue("first selected stream must bind the claimed Surface", lease.isCurrent)
            assertFalse("logical binding is not decoder liveness", lease.decoderAttached)
            sink.onScreenStreamActive(handle, false)
            assertFalse(lease.isCurrent)
            assertTrue(revoked.await(2, TimeUnit.SECONDS))
        } finally {
            subscription.close()
            lease.close()
            sink.close()
        }
    }

    @Test
    fun previewAlreadyAdmittedCannotCommitAcrossPhysicalClaimInstallation() {
        val sink = sink()
        val physical = surface()
        val preview = surface()
        val reserved = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val previewResult = AtomicReference<Boolean?>()
        val failure = AtomicReference<Throwable?>()
        sink.previewAfterReserveTestHook = {
            reserved.countDown()
            check(resume.await(5, TimeUnit.SECONDS)) { "preview pause timed out" }
        }
        val worker = Thread {
            try {
                previewResult.set(sink.trySetSurfaceForPreview(111, preview))
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(reserved.await(5, TimeUnit.SECONDS))
            val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
            try {
                resume.countDown()
                worker.join(5_000)
                assertFalse(worker.isAlive)
                assertNull(failure.get())
                assertEquals(false, previewResult.get())
                assertTrue(lease.isCurrent)
                assertEquals(AndroidSurfaceReservationState.FREE,
                    AndroidMediaSink.surfaceReservationState(preview))
            } finally {
                lease.close()
            }
        } finally {
            resume.countDown()
            worker.join(5_000)
            sink.previewAfterReserveTestHook = null
            sink.close()
        }
    }

    @Test
    fun firstSelectedStreamWithOldExactOverrideRevokesProvisionalClaim() {
        val sink = sink()
        val physical = surface()
        val rival = surface()
        val handle = handle("preexisting-exact")
        val exact = requireNotNull(sink.acquireSurfaceLease(handle, rival))
        val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        val revoked = CountDownLatch(1)
        lease.subscribeRevocation { revoked.countDown() }.use {
            try {
                assertTrue(lease.isCurrent)
                sink.onScreenStreamActive(handle, true)
                assertFalse(lease.isCurrent)
                assertTrue(revoked.await(2, TimeUnit.SECONDS))
            } finally {
                lease.close()
                exact.close()
                sink.close()
            }
        }
    }

    @Test
    fun typedHandoffRetainsExclusionThroughExactLegacyToEglTransfer() {
        val sink = sink()
        val physical = surface()
        val rival = surface()
        val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        val revoked = CountDownLatch(1)
        val subscription = lease.subscribeRevocation { revoked.countDown() }
        val gate = requireNotNull(sink.beginPhysicalSurfaceHandoff(lease))
        try {
            assertTrue(gate.isHeld)
            val captured = requireNotNull(sink.captureLegacySurfaceLease(111, physical))
            val reserved = captured.retireAndReserveForHandoff()
                as AndroidLegacySurfaceHandoffResult.Reserved
            gate.markReserved()
            try {
                assertTrue("EGL transfer must not revoke the physical target", lease.isCurrent)
                assertTrue(reserved.reservation.isHeld)
                assertFalse(sink.trySetSurfaceForPreview(111, rival))
                assertThrows(IllegalStateException::class.java) { sink.setSurface(111, rival) }
                assertThrows(IllegalStateException::class.java) { lease.close() }
            } finally {
                reserved.reservation.close()
            }
            assertFalse(lease.isCurrent)
            assertTrue(revoked.await(2, TimeUnit.SECONDS))
        } finally {
            subscription.close()
            gate.close()
            lease.close()
            sink.close()
        }
        assertEquals(AndroidSurfaceReservationState.FREE,
            AndroidMediaSink.surfaceReservationState(physical))
    }

    @Test
    fun onlyTransferredLeaseCanOpenExactType111EglIngress() {
        val sink = sink()
        val physical = surface()
        val producer = surface()
        val rival = surface()
        val streamHandle = handle("eglingress")
        val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        val gate = requireNotNull(sink.beginPhysicalSurfaceHandoff(lease))
        try {
            assertThrows(IllegalStateException::class.java) {
                lease.acquireTransferredIngressSurfaceLease(streamHandle, producer)
            }
            val captured = requireNotNull(sink.captureLegacySurfaceLease(111, physical))
            val reservation = (captured.retireAndReserveForHandoff()
                as AndroidLegacySurfaceHandoffResult.Reserved).reservation
            gate.markReserved()
            try {
                val ingress = requireNotNull(
                    lease.acquireTransferredIngressSurfaceLease(streamHandle, producer),
                )
                try {
                    assertTrue(lease.isCurrent)
                    assertThrows(IllegalStateException::class.java) {
                        sink.acquireSurfaceLease(handle("competing"), rival)
                    }
                    assertEquals(AndroidSurfaceReservationState.RESERVED,
                        AndroidMediaSink.surfaceReservationState(producer))
                } finally {
                    ingress.close()
                }
                assertEquals(AndroidSurfaceReservationState.FREE,
                    AndroidMediaSink.surfaceReservationState(producer))
            } finally {
                reservation.close()
            }
        } finally {
            gate.close()
            lease.close()
            sink.close()
        }
    }

    @Test
    fun routeLeaseAbaCannotReviveOldOwnerAfterSameSurfaceIsReused() {
        val sink = sink()
        val physical = surface()
        val first = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        first.close()
        assertFalse(first.isCurrent)
        val second = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        try {
            assertTrue(second.isCurrent)
            assertFalse(first.isCurrent)
        } finally {
            second.close()
            sink.close()
        }
    }

    @Test
    fun sinkCloseRevokesProvisionalClaimAndReleasesItsExactReservation() {
        val sink = sink()
        val physical = surface()
        val lease = requireNotNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
        val revoked = CountDownLatch(1)
        lease.subscribeRevocation { revoked.countDown() }.use {
            sink.close()
            assertFalse(lease.isCurrent)
            assertTrue(revoked.await(2, TimeUnit.SECONDS))
            assertEquals(AndroidSurfaceReservationState.FREE,
                AndroidMediaSink.surfaceReservationState(physical))
            lease.close() // Sink close owns the already-complete retirement.
        }
    }

    @Test
    fun physicalPromotionRequiresDeclaredType111Geometry() {
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        val sink = AndroidMediaSink(context)
        val physical = surface()
        try {
            assertNull(sink.acquirePhysicalExternalLegacyRouteLease(111, physical))
            assertEquals(AndroidSurfaceReservationState.FREE,
                AndroidMediaSink.surfaceReservationState(physical))
            assertThrows(IllegalArgumentException::class.java) {
                sink.acquirePhysicalExternalLegacyRouteLease(110, physical)
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun repeatedObservationDoesNotInvalidateAnUnchangedSelectedRoute() {
        val routes = LegacyVideoHandleRoutes()
        val selected = handle("stable")
        routes.observe(selected)
        val firstGeneration = routes.generation(111)
        repeat(20) { routes.observe(selected) }
        routes.setActive(selected, true)
        assertEquals(firstGeneration, routes.generation(111))
        routes.setActive(selected, false)
        assertTrue(routes.generation(111) > firstGeneration)
        assertNull(routes.selected(111))
    }

    @Test
    fun selectedRouteAbaHasDifferentGenerationEvenWhenHandleReturns() {
        val routes = LegacyVideoHandleRoutes()
        val first = handle("a")
        val second = handle("b")
        routes.observe(first)
        routes.observe(second)
        routes.setActive(first, true)
        val firstGeneration = routes.generation(111)
        routes.setActive(first, false)
        routes.setActive(second, true)
        routes.observe(first)
        routes.setActive(first, true)
        routes.setActive(second, false)
        assertEquals(first, routes.selected(111))
        assertTrue(routes.generation(111) > firstGeneration)
    }

    private fun sink(): AndroidMediaSink {
        val context = unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper
        return AndroidMediaSink(context, videoSizes = mapOf(111 to (800 to 480)))
    }

    private fun surface(): Surface = unsafe.allocateInstance(Surface::class.java) as Surface

    private fun handle(streamId: String) = AirPlayVideoStreamHandle(
        AirPlayStreamIdentity(111, streamId, null), AirPlayVideoSessionEpoch(),
    )
}
