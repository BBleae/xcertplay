package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AndroidSurfaceReservationState
import com.shilapi.xcertplay.ultra.AndroidMediaSinkPhysicalLegacyHandoffPort
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PhysicalLegacyHandoffResult
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.egl.AndroidVideoSurfaceLeaseAcquirer
import com.shilapi.xcertplay.ultra.physicalUltraDecoderSurfaceAcquirer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class PhysicalExternalHandoffIntegrationTest {
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture =
        unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture
    private fun sink(): AndroidMediaSink = AndroidMediaSink(
        unsafe.allocateInstance(ContextWrapper::class.java) as ContextWrapper,
        videoSizes = mapOf(111 to (800 to 480)),
    )
    private fun handle(type: Int): AirPlayVideoStreamHandle {
        val epochClass = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
        val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val constructor = AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 3 }.apply { isAccessible = true }
        return constructor.newInstance(
            AirPlayStreamIdentity(type, "physical-$type", null), epoch, null,
        ) as AirPlayVideoStreamHandle
    }

    @Test fun frozenPreSetupRouteTransfersToType111IngressWithoutPreviewOrVersionGap() {
        val sink = sink()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {},
        )
        val factory = AndroidMediaSinkExternalClusterRouteFactory(sink, registry)
        val destination = DisplayDestination(
            DisplayDestinationRole.CLUSTER, DisplayDestinationId("hdmi-1"),
        )
        val physical = surface()
        val physicalTexture = texture()
        val rival = surface()
        val producer = surface()
        val pixels = PixelSize(800, 480)
        val previewVersion = requireNotNull(registry.publish(
            destination, HostDisplaySurfaceKind.PREVIEW_ONLY,
            physical, physicalTexture, pixels.widthPixels, pixels.heightPixels,
        ))
        val route = requireNotNull(factory.attach(destination, physical))
        val trust = registry.newExternalPhysicalTargetTrustToken(
            externalCalibrationForTest(destination, pixels),
        ).also { it.bindRouteProof { route.isCurrent } }
        val physicalVersion = requireNotNull(registry.publish(
            destination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            physical, physicalTexture, pixels.widthPixels, pixels.heightPixels, trust,
        ))
        val frozen = requireNotNull(registry.inspectPhysicalTarget(destination))
        val inputs = HostUltraCompositionInputs(
            sink, registry,
            AirPlayConfig("bench", "id", "02:00:00:00:00:01", "1",
                AirPlayDisplayConfig(800, 480), AirPlayDisplayConfig(800, 480)),
            frozen, factory,
        )
        val port = AndroidMediaSinkPhysicalLegacyHandoffPort(
            sink, inputs::frozenExternalClusterRoute,
        )
        try {
            assertTrue(route.isCurrent) // configured provisional type-111 route, before SETUP
            assertNull(inputs.frozenExternalClusterRoute(previewVersion, physical))
            assertNull(inputs.frozenExternalClusterRoute(
                physicalVersion.copy(generation = physicalVersion.generation + 1), physical,
            ))
            assertNull(inputs.frozenExternalClusterRoute(physicalVersion, rival))
            assertTrue(port.retireAndReserveExact(111, physical, previewVersion)
                is PhysicalLegacyHandoffResult.Denied)
            assertTrue(port.retireAndReserveExact(
                111, physical, physicalVersion.copy(generation = physicalVersion.generation + 1),
            ) is PhysicalLegacyHandoffResult.Denied)
            assertTrue(port.retireAndReserveExact(111, rival, physicalVersion)
                is PhysicalLegacyHandoffResult.Denied)
            val exactLease = requireNotNull(inputs.frozenExternalClusterRoute(
                physicalVersion, physical,
            ))
            val reservation = (port.retireAndReserveExact(111, physical, physicalVersion)
                as PhysicalLegacyHandoffResult.Reserved).reservation
            try {
                assertSame(exactLease, reservation.transferredExternalRoute)
                assertTrue(reservation.isHeld)
                assertTrue(exactLease.isCurrent)
                assertFalse(sink.trySetSurfaceForPreview(111, rival))
                var ordinaryCalls = 0
                var transferredCalls = 0
                val acquirer = physicalUltraDecoderSurfaceAcquirer(
                    AndroidVideoSurfaceLeaseAcquirer { _, _ -> ordinaryCalls++; null },
                    AndroidVideoSurfaceLeaseAcquirer { stream, surface ->
                        transferredCalls++
                        exactLease.acquireTransferredIngressSurfaceLease(stream, surface)
                    },
                )
                assertNull(acquirer.acquire(handle(110), rival))
                val externalUse = reservation.beginExternalUse()
                val ingress = requireNotNull(acquirer.acquire(handle(111), producer))
                try {
                    assertEquals(1, ordinaryCalls)
                    assertEquals(1, transferredCalls)
                    assertTrue(exactLease.isCurrent)
                    assertEquals(AndroidSurfaceReservationState.RESERVED,
                        AndroidMediaSink.surfaceReservationState(producer))
                } finally {
                    ingress.close()
                }
                reservation.confirmExternalUseRetired(externalUse)
            } finally {
                reservation.close()
            }
            assertFalse(exactLease.isCurrent)
            assertEquals(AndroidSurfaceReservationState.FREE,
                AndroidMediaSink.surfaceReservationState(producer))
        } finally {
            route.retire()
            sink.close()
        }
    }

    @Test fun type111NeverFallsBackToOrdinaryDecoderAcquirerWhenTransferIsAbsent() {
        var ordinaryCalls = 0
        val acquirer = physicalUltraDecoderSurfaceAcquirer(
            AndroidVideoSurfaceLeaseAcquirer { _, _ -> ordinaryCalls++; null },
            AndroidVideoSurfaceLeaseAcquirer { _, _ -> null },
        )
        assertNull(acquirer.acquire(handle(111), surface()))
        assertEquals(0, ordinaryCalls)
    }
}
