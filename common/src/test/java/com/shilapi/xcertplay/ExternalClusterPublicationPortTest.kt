package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class ExternalClusterPublicationPortTest {
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture = unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture
    private val destination = DisplayDestination(
        DisplayDestinationRole.CLUSTER,
        DisplayDestinationId("verified-cluster-7"),
    )
    private val pixels = PixelSize(1280, 480)
    private fun gate(registry: HostPhysicalDisplaySurfaceRegistry) =
        RegistryExternalClusterTrustGate(registry.newExternalPhysicalTargetTrustToken(
            externalCalibrationForTest(destination = destination, pixels = pixels),
        ))

    @Test fun exactCapturedRouteIsRetriedByProcessRegistryNotReplacementOwner() {
        val releasedSurfaces = ArrayList<Surface>()
        val releasedTextures = ArrayList<SurfaceTexture>()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = releasedSurfaces::add,
            releaseTexture = releasedTextures::add,
        )
        val surface = surface()
        val texture = texture()
        var firstRouteAttempts = 0
        var replacementRouteAttempts = 0
        var kindDuringRouteAttach: HostDisplaySurfaceKind? = null
        var attachedRoute: ExternalClusterLegacyRoute = ExternalClusterLegacyRoute {
            firstRouteAttempts++
            if (firstRouteAttempts == 1) error("decoder still releasing")
        }
        val port = RegistryExternalClusterPublicationPort(
            registry,
            ExternalClusterLegacyRouteFactory { _, _ ->
                kindDuringRouteAttach = registry.snapshot(destination)?.kind
                attachedRoute
            },
        )
        val publication = port.publish(destination, surface, texture, pixels, gate(registry)) { true }
        assertTrue(publication.usable)
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, kindDuringRouteAttach)
        assertEquals(HostDisplaySurfaceKind.PHYSICAL_EXTERNAL, registry.snapshot(destination)?.kind)
        attachedRoute = ExternalClusterLegacyRoute { replacementRouteAttempts++ }
        assertFalse(publication.withdraw(textureDestroyed = false))
        assertEquals(null, registry.snapshot(destination))
        assertTrue(registry.hasActionableRetirement())
        try {
            registry.retryPendingRetirements()
            throw AssertionError("first exact detach must fail")
        } catch (expected: IllegalStateException) {
            assertEquals("decoder still releasing", expected.message)
        }
        assertEquals(1, firstRouteAttempts)
        assertEquals(0, replacementRouteAttempts)
        assertTrue(releasedSurfaces.isEmpty())
        registry.retryPendingRetirements()
        assertEquals(2, firstRouteAttempts)
        assertEquals(0, replacementRouteAttempts)
        assertEquals(listOf(surface), releasedSurfaces)
        assertTrue(releasedTextures.isEmpty())
        assertFalse(publication.withdraw(textureDestroyed = true))
        registry.retryPendingRetirements()
        assertEquals(listOf(texture), releasedTextures)
    }

    @Test fun failedAtomicRouteAttachLeavesRegistryOwnershipForSafeRetirement() {
        val releasedSurfaces = ArrayList<Surface>()
        val releasedTextures = ArrayList<SurfaceTexture>()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = releasedSurfaces::add,
            releaseTexture = releasedTextures::add,
        )
        val surface = surface()
        val texture = texture()
        val port = RegistryExternalClusterPublicationPort(
            registry,
            ExternalClusterLegacyRouteFactory { _, _ -> error("attach denied") },
        )
        val publication = port.publish(destination, surface, texture, pixels, gate(registry)) { true }
        assertFalse(publication.usable)
        assertFalse(publication.withdraw(textureDestroyed = true))
        registry.retryPendingRetirements()
        assertEquals(listOf(surface), releasedSurfaces)
        assertEquals(listOf(texture), releasedTextures)
    }

    @Test fun noExactLegacyRouteNeverPromotesToPhysicalExternal() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val surface = surface()
        val texture = texture()
        val port = RegistryExternalClusterPublicationPort(
            registry, ExternalClusterLegacyRouteFactory.NONE,
        )
        val publication = port.publish(destination, surface, texture, pixels, gate(registry)) { true }
        assertFalse(publication.usable)
        assertEquals(null, registry.snapshot(DisplayDestination.CLUSTER))
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, registry.snapshot(destination)?.kind)
        publication.withdraw(textureDestroyed = true)
        registry.retryPendingRetirements()
        assertFalse(registry.hasPendingRetirement())
    }

    @Test fun sinkRoutePixelMismatchCannotPromoteExternalPanel() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {},
        )
        var retired = false
        val route = object : ExternalClusterLegacyRoute {
            override val configuredPixels = PixelSize(1024, 480)
            override fun retire() { retired = true }
        }
        val publication = RegistryExternalClusterPublicationPort(
            registry, ExternalClusterLegacyRouteFactory { _, _ -> route },
        ).publish(destination, surface(), texture(), pixels, gate(registry)) { true }
        assertFalse(publication.usable)
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, registry.snapshot(destination)?.kind)
        publication.withdraw(textureDestroyed = true)
        registry.retryPendingRetirements()
        assertTrue(retired)
    }

    @Test fun secondProducerCannotReplaceTheActiveExternalClusterDestination() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val port = RegistryExternalClusterPublicationPort(
            registry, ExternalClusterLegacyRouteFactory { _, _ -> ExternalClusterLegacyRoute { } },
        )
        val firstSurface = surface()
        val firstTexture = texture()
        val first = port.publish(destination, firstSurface, firstTexture, pixels, gate(registry)) { true }
        assertThrows(IllegalStateException::class.java) {
            port.publish(destination, surface(), texture(), pixels, gate(registry)) { true }
        }
        assertTrue(first.usable)
        assertTrue(registry.isSameLivePhysicalTarget(
            requireNotNull(registry.snapshot(destination)), firstSurface,
        ))
        first.withdraw(textureDestroyed = true)
        registry.retryPendingRetirements()
    }

    @Test fun synchronousRevocationDuringRouteAttachCannotPromoteStagingToPhysical() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        var authorized = true
        val port = RegistryExternalClusterPublicationPort(
            registry,
            ExternalClusterLegacyRouteFactory { _, _ ->
                assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, registry.snapshot(destination)?.kind)
                authorized = false
                ExternalClusterLegacyRoute { }
            },
        )
        val publication = port.publish(destination, surface(), texture(), pixels, gate(registry)) { authorized }
        assertFalse(publication.usable)
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, registry.snapshot(destination)?.kind)
        publication.withdraw(textureDestroyed = true)
        registry.retryPendingRetirements()
        assertEquals(null, registry.snapshot(destination))
    }

    @Test fun transientPublicationWithdrawFailureRetainsExactProcessOwnedRetry() {
        val retried = CountDownLatch(1)
        var attempts = 0
        val publication = object : ExternalClusterPublication {
            override val texture: Any = Any()
            override fun resize(pixels: PixelSize) = true
            override fun withdraw(textureDestroyed: Boolean): Boolean {
                attempts++
                if (attempts == 1) error("native detach not finished")
                assertTrue(textureDestroyed)
                retried.countDown()
                return false
            }
        }
        ProcessOwnedExternalClusterPublicationRetirer.retire(publication, true)
        assertTrue("exact publication must remain retryable", retried.await(3, TimeUnit.SECONDS))
        assertTrue(attempts >= 2)
    }

    @Test fun crossThreadTrustInvalidationRejectsPhysicalPromotionEvenIfCallbackIsDelayed() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val trust = gate(registry)
        val port = RegistryExternalClusterPublicationPort(
            registry,
            ExternalClusterLegacyRouteFactory { _, _ ->
                val invalidator = Thread { trust.invalidate() }
                invalidator.start()
                invalidator.join(2_000L)
                assertFalse(invalidator.isAlive)
                ExternalClusterLegacyRoute { }
            },
        )
        val publication = port.publish(destination, surface(), texture(), pixels, trust) { true }
        assertFalse(publication.usable)
        assertFalse(trust.isCurrent)
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, registry.snapshot(destination)?.kind)
        assertEquals(null, registry.inspectPhysicalTarget(destination))
        publication.withdraw(textureDestroyed = true)
        registry.retryPendingRetirements()
    }

    @Test fun decoderRouteRevocationImmediatelyRemovesPhysicalProof() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val surface = surface()
        val texture = texture()
        val trust = gate(registry)
        var current = true
        var revocation: (() -> Unit)? = null
        var retired = false
        val route = object : ExternalClusterLegacyRoute {
            override val isCurrent: Boolean get() = current
            override fun subscribeRevocation(onRevoked: () -> Unit): Closeable {
                revocation = onRevoked
                return Closeable { if (revocation === onRevoked) revocation = null }
            }
            override fun retire() { retired = true }
        }
        val publication = RegistryExternalClusterPublicationPort(
            registry, ExternalClusterLegacyRouteFactory { _, _ -> route },
        ).publish(destination, surface, texture, pixels, trust) { true }
        val version = requireNotNull(registry.snapshot(destination))
        assertTrue(publication.usable)
        assertTrue(registry.isSameLivePhysicalTarget(version, surface))

        current = false // Synchronous liveness check closes the callback-delivery gap.
        assertFalse(publication.usable)
        assertFalse(registry.isSameLivePhysicalTarget(version, surface))
        assertEquals(null, registry.inspectPhysicalTarget(destination))
        revocation?.invoke() // Asynchronous sink callback also revokes the topology token.
        assertFalse(trust.isCurrent)
        publication.withdraw(textureDestroyed = true)
        registry.retryPendingRetirements()
        assertTrue(retired)
        assertEquals(null, revocation)
    }
}
