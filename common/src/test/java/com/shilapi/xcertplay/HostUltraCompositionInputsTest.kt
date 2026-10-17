package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HostUltraCompositionInputsTest {
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe

    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture =
        unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture

    @Test fun videoReleaseChannelIsIssuedOnlyForTheCapturedLiveSink() {
        val sink = unsafe.allocateInstance(AndroidMediaSink::class.java) as AndroidMediaSink
        val foreign = unsafe.allocateInstance(AndroidMediaSink::class.java) as AndroidMediaSink
        val relay = HostVideoFrameReleaseRelay()
        relay.bindOwner(sink)
        val config = AirPlayConfig("bench", "id", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(800, 480))
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {},
        )
        val inputs = HostUltraCompositionInputs(sink, registry, config,
            videoFrameReleaseRelay = relay)
        assertNotNull(inputs.currentVideoFrameReleaseChannel(sink))
        assertNull(inputs.currentVideoFrameReleaseChannel(foreign))
        assertThrows(IllegalArgumentException::class.java) {
            HostUltraCompositionInputs(foreign, registry, config,
                videoFrameReleaseRelay = relay)
        }
        relay.close()
        assertThrows(IllegalStateException::class.java) {
            requireNotNull(inputs.currentVideoFrameReleaseChannel(sink))
                .attach(Any()) { }
        }
    }

    @Test fun aNewTrustedConnectorCannotReplaceTheExternalTargetFrozenForOneController() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {},
        )
        val pixels = PixelSize(1920, 480)
        val first = DisplayDestination(DisplayDestinationRole.CLUSTER, DisplayDestinationId("hdmi-1"))
        val firstSurface = surface()
        registry.publish(first, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            firstSurface, texture(), pixels.widthPixels, pixels.heightPixels,
            externalTrustToken = registry.newExternalPhysicalTargetTrustToken(
                externalCalibrationForTest(first, pixels),
            ).also { it.bindRouteProof { true } })
        val frozen = requireNotNull(registry.inspectPhysicalTarget(first))
        val sink = unsafe.allocateInstance(AndroidMediaSink::class.java) as AndroidMediaSink
        val inputs = HostUltraCompositionInputs(
            sink, registry,
            AirPlayConfig("bench", "id", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
            frozen,
        )
        assertTrue(inputs.matchesFrozenExternalCluster(frozen))

        val second = DisplayDestination(DisplayDestinationRole.CLUSTER, DisplayDestinationId("hdmi-2"))
        registry.publish(second, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            surface(), texture(), pixels.widthPixels, pixels.heightPixels,
            externalTrustToken = registry.newExternalPhysicalTargetTrustToken(
                externalCalibrationForTest(second, pixels, displayId = 5, epoch = 102L),
            ).also { it.bindRouteProof { true } })
        assertFalse(inputs.matchesFrozenExternalCluster(requireNotNull(
            registry.inspectPhysicalTarget(first),
        )))
        assertFalse(inputs.matchesFrozenExternalCluster(requireNotNull(
            registry.inspectPhysicalTarget(second),
        )))
    }
}
