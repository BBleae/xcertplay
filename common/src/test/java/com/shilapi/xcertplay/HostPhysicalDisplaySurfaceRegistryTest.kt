package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import sun.misc.Unsafe

class HostPhysicalDisplaySurfaceRegistryTest {
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture = unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture
    private fun routedExternalTrust(
        registry: HostPhysicalDisplaySurfaceRegistry,
        calibration: ExternalPhysicalDisplayCalibration = externalCalibrationForTest(),
    ): ExternalPhysicalTargetTrustToken =
        registry.newExternalPhysicalTargetTrustToken(calibration).also { it.bindRouteProof { true } }

    private class Fixture {
        val releasedSurfaces = mutableListOf<Surface>()
        val releasedTextures = mutableListOf<SurfaceTexture>()
        lateinit var registry: HostPhysicalDisplaySurfaceRegistry
        init {
            registry = HostPhysicalDisplaySurfaceRegistry(
                releaseSurface = releasedSurfaces::add,
                releaseTexture = releasedTextures::add,
                requestRetirement = { registry.retryPendingRetirements() },
            )
        }
    }

    private class RoutePort(var webOwns: Boolean) : HostDisplaySurfaceRoutePort {
        val calls = mutableListOf<String>()
        val previews = WebDisplayPreviewRoutes()
        override fun webDisplayOwns(type: Int): Boolean = webOwns
        override fun preview(type: Int, surface: Surface?) {
            previews.replace(
                type, surface,
                attach = { calls += "web-set:$type" },
                detach = { calls += "web-clear:$type" },
            )
        }
        override fun clearPreview(type: Int, expected: Surface) {
            previews.clearIfCurrent(type, expected) { calls += "web-clear:$type" }
        }
        override fun setDecoderSurface(type: Int, surface: Surface) {
            calls += "decoder-set:$type"
        }
        override fun clearDecoderSurface(type: Int, surface: Surface) {
            calls += "decoder-clear:$type"
        }
    }

    @Test fun legacyMainAndClusterPreviewRoutingStillFallsBackToMediaSink() {
        val port = RoutePort(webOwns = false)
        val main = surface()
        val cluster = surface()
        attachHostDisplaySurface(port, CarPlayWebDisplayFactory.MAIN, main)
        attachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, cluster)
        detachHostDisplaySurface(port, CarPlayWebDisplayFactory.MAIN, main)
        detachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, cluster)
        assertEquals(
            listOf("decoder-set:110", "decoder-set:111", "decoder-clear:110", "decoder-clear:111"),
            port.calls,
        )

        port.calls.clear()
        port.webOwns = true
        attachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, cluster)
        detachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, cluster)
        assertEquals(listOf("web-set:111", "web-clear:111"), port.calls)
    }

    @Test fun oldActivityDetachCannotClearSuccessorWebDisplayPreview() {
        val port = RoutePort(webOwns = true)
        val old = surface()
        val successor = surface()
        attachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, old)
        attachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, successor)
        detachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, old)
        assertEquals(listOf("web-set:111", "web-clear:111", "web-set:111"), port.calls)
        detachHostDisplaySurface(port, CarPlayWebDisplayFactory.CLUSTER, successor)
        assertEquals(
            listOf("web-set:111", "web-clear:111", "web-set:111", "web-clear:111"),
            port.calls,
        )
    }

    @Test fun failedPreviewClearRetainsExactSurfaceForRetry() {
        val routes = WebDisplayPreviewRoutes()
        val old = surface()
        var detachCalls = 0
        routes.replace(111, old, attach = {}, detach = {})
        assertThrows(IllegalStateException::class.java) {
            routes.clearIfCurrent(111, old) {
                detachCalls++
                error("decoder detach failed")
            }
        }
        assertTrue(routes.clearIfCurrent(111, old) { detachCalls++ })
        assertEquals(2, detachCalls)
    }

    @Test fun mainHasExactCallbackPixelsButClusterOverlayIsNeverPhysicalOutput() {
        val fixture = Fixture()
        val mainSurface = surface()
        val mainTexture = texture()
        val previewSurface = surface()
        val previewTexture = texture()
        val main = fixture.registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            mainSurface,
            mainTexture,
            1560,
            720,
        )!!
        val cluster = fixture.registry.publish(
            DisplayDestination.CLUSTER,
            HostDisplaySurfaceKind.PREVIEW_ONLY,
            previewSurface,
            previewTexture,
            540,
            240,
        )!!
        assertEquals(PixelSize(1560, 720), main.sizePixels)
        assertEquals(PixelSize(540, 240), cluster.sizePixels)
        assertNull(fixture.registry.acquirePhysicalTarget(cluster))
        val borrowed = fixture.registry.acquirePhysicalTarget(main)!!
        assertSame(mainSurface, borrowed.target.surface)
        assertEquals(main.sizePixels, borrowed.target.descriptor.sizePixels)
        assertFalse(borrowed.isRevoked)
        borrowed.retireAfterEglProof()
        assertTrue(fixture.releasedSurfaces.isEmpty())
        assertTrue(fixture.releasedTextures.isEmpty())
    }

    @Test fun externalPublicationRequiresLiveTrustMintedByTheSameRegistry() {
        val fixture = Fixture()
        val clusterSurface = surface()
        val clusterTexture = texture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                clusterSurface, clusterTexture, 1920, 480,
            )
        }
        val foreignTrust = routedExternalTrust(HostPhysicalDisplaySurfaceRegistry(),
            externalCalibrationForTest(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                clusterSurface, clusterTexture, 1920, 480, foreignTrust,
            )
        }
        val staleTrust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(),
        )
        staleTrust.invalidate()
        assertThrows(IllegalStateException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                clusterSurface, clusterTexture, 1920, 480, staleTrust,
            )
        }
        val preview = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
            clusterSurface, clusterTexture, 1920, 480,
        )!!
        assertNull(fixture.registry.acquirePhysicalTarget(preview))
        val trust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(),
        )
        val physical = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface, clusterTexture, 1920, 480, trust,
        )!!
        assertNotNull(fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER))
        assertNotNull(fixture.registry.acquirePhysicalTarget(physical))
    }

    @Test fun liveConnectorTrustWithoutAnExactRouteProofCannotPublishPhysicalExternal() {
        val fixture = Fixture()
        val trust = fixture.registry.newExternalPhysicalTargetTrustToken(externalCalibrationForTest())
        assertTrue(trust.isCurrent)
        assertFalse(trust.hasBoundRouteProof)

        val failure = assertThrows(IllegalStateException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface(), texture(), 1920, 480, trust,
            )
        }
        assertEquals("External physical trust has no exact sink route proof", failure.message)
        assertNull(fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER))
    }

    @Test fun routeProofFailureSynchronouslyHidesPreviouslyPublishedPhysicalExternalTarget() {
        val fixture = Fixture()
        var routeCurrent = true
        val trust = fixture.registry.newExternalPhysicalTargetTrustToken(
            externalCalibrationForTest(),
        ).also { it.bindRouteProof { routeCurrent } }
        val externalSurface = surface()
        val version = requireNotNull(fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            externalSurface, texture(), 1920, 480, trust,
        ))
        assertNotNull(fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER))
        assertTrue(fixture.registry.isSameLivePhysicalTarget(version, externalSurface))

        routeCurrent = false
        assertFalse(trust.isCurrent)
        assertNull(fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER))
        assertNull(fixture.registry.snapshot(DisplayDestination.CLUSTER))
        assertFalse(fixture.registry.isSameLivePhysicalTarget(version, externalSurface))
        assertNull(fixture.registry.acquirePhysicalTarget(version))
    }

    @Test fun calibratedTrustCannotBeReboundToAnotherConnectorDestinationOrPixelGeometry() {
        val fixture = Fixture()
        val destination = DisplayDestination(
            DisplayDestinationRole.CLUSTER, DisplayDestinationId("hdmi-panel-7"),
        )
        val calibration = externalCalibrationForTest(
            destination = destination, pixels = PixelSize(1280, 480),
            widthMm = 239, heightMm = 89, displayId = 7, epoch = 502L,
        )
        val trust = routedExternalTrust(fixture.registry, calibration)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface(), texture(), 1280, 480, trust,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.registry.publish(
                destination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface(), texture(), 1281, 480, trust,
            )
        }
        val verifiedSurface = surface()
        val version = requireNotNull(fixture.registry.publish(
            destination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            verifiedSurface, texture(), 1280, 480, trust,
        ))
        assertEquals(calibration, version.externalCalibration)
        assertEquals(calibration, fixture.registry.inspectPhysicalTarget(destination)?.externalCalibration)
        val borrowed = requireNotNull(fixture.registry.acquirePhysicalTarget(version))
        assertFalse(borrowed.isRevoked)
        trust.invalidate()
        assertTrue(borrowed.isRevoked)
        assertNull(fixture.registry.inspectPhysicalTarget(destination))
        borrowed.retireAfterEglProof()
    }

    @Test fun destinationCannotMasqueradeAsPhysicalMainToBypassExternalTrust() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_MAIN,
                surface(), texture(), 1920, 480,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.registry.publish(
                DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface(), texture(), 1920, 480,
                routedExternalTrust(fixture.registry),
            )
        }
        assertNull(fixture.registry.snapshot(DisplayDestination.CLUSTER))
        assertNull(fixture.registry.snapshot(DisplayDestination.MAIN))
    }

    @Test fun externalTrustInvalidationRevokesBorrowAndNotifiesWithoutHoldingRegistryLock() {
        val fixture = Fixture()
        val trust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(),
        )
        val clusterSurface = surface()
        val clusterTexture = texture()
        val physical = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface, clusterTexture, 1920, 480, trust,
        )!!
        val borrowed = fixture.registry.acquirePhysicalTarget(physical)!!
        var callbacks = 0
        val callbackCanAcquireLock = AtomicReference(false)
        fixture.registry.subscribePhysicalTargetInvalidation(physical, clusterSurface) {
            callbacks++
            val acquired = CountDownLatch(1)
            Thread {
                fixture.registry.snapshot(DisplayDestination.CLUSTER)
                acquired.countDown()
            }.start()
            callbackCanAcquireLock.set(acquired.await(2, TimeUnit.SECONDS))
        }
        trust.invalidate()
        assertFalse(trust.isCurrent)
        assertTrue(callbackCanAcquireLock.get())
        assertEquals(1, callbacks)
        assertTrue(borrowed.isRevoked)
        assertFalse(borrowed.target.isValid)
        assertNull(fixture.registry.snapshot(DisplayDestination.CLUSTER))
        assertNull(fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER))
        assertFalse(fixture.registry.isSameLivePhysicalTarget(physical, clusterSurface))
        assertNull(fixture.registry.acquirePhysicalTarget(physical))
        trust.invalidate()
        assertEquals(1, callbacks)
        borrowed.retireAfterEglProof()
    }

    @Test fun staleExternalTargetCannotBeBorrowedWhileRevocationObserverIsBlocked() {
        val fixture = Fixture()
        val trust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(),
        )
        val clusterSurface = surface()
        val clusterTexture = texture()
        val physical = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface, clusterTexture, 1920, 480, trust,
        )!!
        val borrowed = fixture.registry.acquirePhysicalTarget(physical)!!
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        fixture.registry.subscribePhysicalTargetInvalidation(physical, clusterSurface) {
            callbackEntered.countDown()
            check(releaseCallback.await(3, TimeUnit.SECONDS))
        }
        val invalidator = Thread(trust::invalidate)
        invalidator.start()
        try {
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            assertFalse(trust.isCurrent)
            assertTrue(borrowed.isRevoked)
            assertFalse(borrowed.target.isValid)
            assertNull(fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER))
            assertNull(fixture.registry.acquirePhysicalTarget(physical))
            assertFalse(fixture.registry.isSameLivePhysicalTarget(physical, clusterSurface))
        } finally {
            releaseCallback.countDown()
            invalidator.join(3_000)
        }
        assertFalse(invalidator.isAlive)
        borrowed.retireAfterEglProof()
    }

    @Test fun replacingExternalTrustInvalidatesOldVersionButOldTokenCannotRevokeSuccessor() {
        val fixture = Fixture()
        val firstTrust = routedExternalTrust(fixture.registry)
        val nextTrust = routedExternalTrust(fixture.registry)
        val clusterSurface = surface()
        val clusterTexture = texture()
        val first = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface, clusterTexture, 1920, 480, firstTrust,
        )!!
        val oldBorrow = fixture.registry.acquirePhysicalTarget(first)!!
        val next = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface, clusterTexture, 1920, 480, nextTrust,
        )!!
        assertTrue(next.generation > first.generation)
        assertTrue(oldBorrow.isRevoked)
        oldBorrow.retireAfterEglProof()
        val nextBorrow = fixture.registry.acquirePhysicalTarget(next)!!
        firstTrust.invalidate()
        assertFalse(nextBorrow.isRevoked)
        assertTrue(fixture.registry.isSameLivePhysicalTarget(next, clusterSurface))
        nextTrust.invalidate()
        assertTrue(nextBorrow.isRevoked)
        nextBorrow.retireAfterEglProof()
    }

    @Test fun reusingBoundExternalTrustCannotDisplaceAnotherLiveRecord() {
        val fixture = Fixture()
        val firstTrust = routedExternalTrust(fixture.registry)
        val currentTrust = routedExternalTrust(fixture.registry)
        fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            surface(), texture(), 1920, 480, firstTrust,
        )!!
        val currentSurface = surface()
        val current = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            currentSurface, texture(), 1920, 480, currentTrust,
        )!!
        assertThrows(IllegalStateException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface(), texture(), 1920, 480, firstTrust,
            )
        }
        assertEquals(current, fixture.registry.snapshot(DisplayDestination.CLUSTER))
        assertTrue(fixture.registry.isSameLivePhysicalTarget(current, currentSurface))
    }

    @Test fun withdrawnSurfaceCannotBeRepublishedAsAnAmbiguousSuccessorRecord() {
        val fixture = Fixture()
        val clusterSurface = surface()
        val clusterTexture = texture()
        fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
            clusterSurface, clusterTexture, 1920, 480,
        )!!
        val retirement = fixture.registry.withdraw(
            DisplayDestination.CLUSTER, clusterSurface, clusterTexture, textureDestroyed = false,
        )!!
        assertThrows(IllegalStateException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
                clusterSurface, clusterTexture, 1920, 480,
            )
        }
        assertNull(fixture.registry.snapshot(DisplayDestination.CLUSTER))
        retirement.completeAfterLegacyOwnerRetired()
        assertEquals(listOf(clusterSurface), fixture.releasedSurfaces)
    }

    @Test fun sameSurfaceKindDowngradeRevokesPhysicalBorrowEvenWithoutResize() {
        val fixture = Fixture()
        val trust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(),
        )
        val clusterSurface = surface()
        val clusterTexture = texture()
        val physical = fixture.registry.publish(
            DisplayDestination.CLUSTER,
            HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface,
            clusterTexture,
            1920,
            480,
            trust,
        )!!
        val borrowed = fixture.registry.acquirePhysicalTarget(physical)!!
        assertEquals(
            physical,
            fixture.registry.publish(
                DisplayDestination.CLUSTER,
                HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                clusterSurface,
                clusterTexture,
                1920,
                480,
                trust,
            ),
        )

        val preview = fixture.registry.publish(
            DisplayDestination.CLUSTER,
            HostDisplaySurfaceKind.PREVIEW_ONLY,
            clusterSurface,
            clusterTexture,
            1920,
            480,
        )!!
        assertTrue(preview.generation > physical.generation)
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, preview.kind)
        assertTrue(borrowed.isRevoked)
        assertFalse(borrowed.target.isValid)
        assertTrue(fixture.registry.hasPendingRetirement())
        assertNull(fixture.registry.acquirePhysicalTarget(physical))
        assertNull(fixture.registry.acquirePhysicalTarget(preview))

        borrowed.retireAfterEglProof()
        assertFalse(fixture.registry.hasPendingRetirement())
        assertNull(fixture.registry.acquirePhysicalTarget(preview))
        val resizedPreview = fixture.registry.resize(
            DisplayDestination.CLUSTER, clusterSurface, clusterTexture, 1280, 480,
        )!!
        assertTrue(resizedPreview.generation > preview.generation)
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, resizedPreview.kind)
        assertNull(fixture.registry.acquirePhysicalTarget(resizedPreview))
        assertTrue(fixture.releasedSurfaces.isEmpty())
        assertTrue(fixture.releasedTextures.isEmpty())
    }

    @Test fun sameTextureRepublishedForAnotherDestinationCannotKeepOldPhysicalAuthority() {
        val fixture = Fixture()
        val trust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(),
        )
        val sharedSurface = surface()
        val sharedTexture = texture()
        val physical = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            sharedSurface, sharedTexture, 1920, 480, trust,
        )!!
        val borrower = fixture.registry.acquirePhysicalTarget(physical)!!
        val preview = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PREVIEW_ONLY,
            sharedSurface, sharedTexture, 1920, 480,
        )!!
        assertNull(fixture.registry.snapshot(DisplayDestination.CLUSTER))
        assertEquals(HostDisplaySurfaceKind.PREVIEW_ONLY, preview.kind)
        assertTrue(borrower.isRevoked)
        assertFalse(borrower.target.isValid)
        assertNull(fixture.registry.acquirePhysicalTarget(physical))
        assertNull(fixture.registry.acquirePhysicalTarget(preview))
        borrower.retireAfterEglProof()
        val oldRetirement = fixture.registry.withdraw(
            DisplayDestination.CLUSTER, sharedSurface, sharedTexture, textureDestroyed = false,
        )!!
        val previewRetirement = fixture.registry.withdraw(
            DisplayDestination.MAIN, sharedSurface, sharedTexture, textureDestroyed = true,
        )!!
        oldRetirement.completeAfterLegacyOwnerRetired()
        assertTrue(fixture.releasedSurfaces.isEmpty())
        previewRetirement.completeAfterLegacyOwnerRetired()
        assertEquals(listOf(sharedSurface), fixture.releasedSurfaces)
        assertEquals(listOf(sharedTexture), fixture.releasedTextures)
    }

    @Test fun releaseClaimRejectsAliasPublicationWhileNativeReleaseIsInFlight() {
        val surface = surface()
        val texture = texture()
        val releaseEntered = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val releasedTextures = mutableListOf<SurfaceTexture>()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {
                releaseEntered.countDown()
                check(allowRelease.await(3, TimeUnit.SECONDS)) { "Release test timed out" }
            },
            releaseTexture = releasedTextures::add,
        )
        registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 800, 480,
        )
        registry.withdraw(
            DisplayDestination.MAIN, surface, texture, textureDestroyed = true,
        )!!.completeAfterLegacyOwnerRetired()
        val worker = Thread {
            try {
                registry.retryPendingRetirements()
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        worker.start()
        try {
            assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertThrows(IllegalStateException::class.java) {
                registry.publish(
                    DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
                    surface, texture, 800, 480,
                )
            }
            assertTrue("publish must not wait for the native release", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))
        } finally {
            allowRelease.countDown()
            worker.join(3_000)
        }
        assertFalse(worker.isAlive)
        assertNull(workerFailure.get())
        assertEquals(listOf(texture), releasedTextures)
    }

    @Test fun completedSurfaceReleaseCannotBeRepublishedWhileTextureStillAttached() {
        val fixture = Fixture()
        val surface = surface()
        val texture = texture()
        fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 800, 480,
        )
        fixture.registry.withdraw(
            DisplayDestination.MAIN, surface, texture, textureDestroyed = false,
        )!!.completeAfterLegacyOwnerRetired()
        assertEquals(listOf(surface), fixture.releasedSurfaces)
        assertTrue(fixture.releasedTextures.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            fixture.registry.publish(
                DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PREVIEW_ONLY,
                surface, texture, 800, 480,
            )
        }
        assertFalse(fixture.registry.onTextureDestroyed(texture))
        assertEquals(listOf(texture), fixture.releasedTextures)
    }

    @Test fun resizeRevokesOldGenerationAndWaitsForItsEglBorrower() {
        val fixture = Fixture()
        val surface = surface()
        val texture = texture()
        val old = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 1280, 720,
        )!!
        val oldBorrow = fixture.registry.acquirePhysicalTarget(old)!!
        val resized = fixture.registry.resize(DisplayDestination.MAIN, surface, texture, 1600, 900)!!
        assertEquals(PixelSize(1600, 900), resized.sizePixels)
        assertTrue(resized.generation > old.generation)
        assertTrue(oldBorrow.isRevoked)
        assertFalse(oldBorrow.target.isValid)
        assertTrue(fixture.registry.hasPendingRetirement())
        assertNull(fixture.registry.acquirePhysicalTarget(old))
        assertNull(fixture.registry.acquirePhysicalTarget(resized))
        assertTrue(fixture.releasedSurfaces.isEmpty())

        oldBorrow.retireAfterEglProof()
        val current = fixture.registry.acquirePhysicalTarget(resized)!!
        assertFalse(current.isRevoked)
        val retirement = fixture.registry.withdraw(
            DisplayDestination.MAIN, surface, texture, textureDestroyed = true,
        )!!
        retirement.completeAfterLegacyOwnerRetired()
        assertTrue(current.isRevoked)
        assertFalse(current.target.isValid)
        assertTrue(fixture.releasedSurfaces.isEmpty())
        assertTrue(fixture.releasedTextures.isEmpty())
        current.retireAfterEglProof()
        assertFalse(fixture.registry.hasPendingRetirement())
        assertEquals(listOf(surface), fixture.releasedSurfaces)
        assertEquals(listOf(texture), fixture.releasedTextures)
        assertFalse(fixture.registry.onTextureDestroyed(texture))
    }

    @Test fun replacementCannotBeWithdrawnByOldActivityOrLateDestroyedCallback() {
        val fixture = Fixture()
        val oldSurface = surface()
        val oldTexture = texture()
        val old = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            oldSurface, oldTexture, 1000, 600,
        )!!
        val oldBorrow = fixture.registry.acquirePhysicalTarget(old)!!
        val newSurface = surface()
        val newTexture = texture()
        val current = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            newSurface, newTexture, 1920, 1080,
        )!!
        assertTrue(oldBorrow.isRevoked)
        assertFalse(oldBorrow.target.isValid)
        assertEquals(current, fixture.registry.snapshot(DisplayDestination.MAIN))
        val oldRetirement = fixture.registry.withdraw(
            DisplayDestination.MAIN, oldSurface, oldTexture, textureDestroyed = false,
        )!!
        oldRetirement.completeAfterLegacyOwnerRetired()
        assertEquals(current, fixture.registry.snapshot(DisplayDestination.MAIN))
        assertFalse(fixture.registry.onTextureDestroyed(oldTexture))
        assertEquals(current, fixture.registry.snapshot(DisplayDestination.MAIN))
        assertTrue(fixture.releasedSurfaces.isEmpty())
        oldBorrow.retireAfterEglProof()
        assertFalse(fixture.registry.hasPendingRetirement())
        assertEquals(listOf(oldSurface), fixture.releasedSurfaces)
        assertEquals(listOf(oldTexture), fixture.releasedTextures)
        assertNotNull(fixture.registry.acquirePhysicalTarget(current))
    }

    @Test fun hostTeardownBarrierAndBorrowerBothHoldSurfaceUntilRetirement() {
        val fixture = Fixture()
        val surface = surface()
        val texture = texture()
        val version = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 1024, 600,
        )!!
        val borrow = fixture.registry.acquirePhysicalTarget(version)!!
        val retirement = fixture.registry.withdraw(
            DisplayDestination.MAIN, surface, texture, textureDestroyed = true,
        )!!
        val barrier = HostTeardownCoordinator()
        val controller = Any()
        barrier.submit(HostTeardownActions(controller, null, {}, { true }, null))
        assertTrue(barrier.deferReleaseWhilePending(surface, retirement::completeAfterLegacyOwnerRetired))
        assertEquals(HostTeardownDrainResult.Clear, drainHostTeardowns(barrier))
        assertTrue(fixture.releasedSurfaces.isEmpty())
        borrow.retireAfterEglProof()
        assertEquals(listOf(surface), fixture.releasedSurfaces)
        assertEquals(listOf(texture), fixture.releasedTextures)
    }

    @Test fun failedNativeReleaseAndFailedLegacyDetachRemainRetryable() {
        val surface = surface()
        val texture = texture()
        var surfaceAttempts = 0
        var detachAttempts = 0
        val releasedTextures = mutableListOf<SurfaceTexture>()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {
                surfaceAttempts++
                if (surfaceAttempts == 1) error("release temporarily failed")
            },
            releaseTexture = releasedTextures::add,
        )
        registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 800, 480,
        )
        val retirement = registry.withdraw(
            DisplayDestination.MAIN, surface, texture, textureDestroyed = true,
        )!!
        retirement.retainRetryDetacher {
            detachAttempts++
            if (detachAttempts == 1) error("sink detach temporarily failed")
        }
        assertThrows(IllegalStateException::class.java) { registry.retryPendingRetirements() }
        assertTrue(registry.hasPendingRetirement())
        assertEquals(0, surfaceAttempts)
        assertThrows(IllegalStateException::class.java) { registry.retryPendingRetirements() }
        assertEquals(1, surfaceAttempts)
        assertTrue(releasedTextures.isEmpty())
        registry.retryPendingRetirements()
        assertFalse(registry.hasPendingRetirement())
        assertEquals(2, detachAttempts)
        assertEquals(2, surfaceAttempts)
        assertEquals(listOf(texture), releasedTextures)
    }

    @Test fun pendingAdmissionRemainsClosedUntilDispatchedNativeCleanupActuallyFinishes() {
        val surface = surface()
        val texture = texture()
        var retirementRequests = 0
        var nativeReleases = 0
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = { nativeReleases++ },
            releaseTexture = { nativeReleases++ },
            requestRetirement = { retirementRequests++ },
        )
        registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 800, 480,
        )
        val retirement = registry.withdraw(
            DisplayDestination.MAIN, surface, texture, textureDestroyed = true,
        )!!
        retirement.completeAfterLegacyOwnerRetired()
        assertTrue(retirementRequests >= 2)
        assertTrue(registry.hasPendingRetirement())
        assertEquals(0, nativeReleases)
        registry.retryPendingRetirements()
        assertEquals(2, nativeReleases)
        assertFalse(registry.hasPendingRetirement())
    }

    @Test fun zeroSizedCallbackDoesNotProduceBorrowableOutput() {
        val fixture = Fixture()
        val surface = surface()
        val texture = texture()
        assertNull(fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 0, 0,
        ))
        assertNull(fixture.registry.snapshot(DisplayDestination.MAIN))
        val sized = fixture.registry.resize(DisplayDestination.MAIN, surface, texture, 640, 480)!!
        assertNotNull(fixture.registry.acquirePhysicalTarget(sized))
    }

    @Test fun exactInvalidationFiresOnceForResizeKindDowngradeAndReplacement() {
        val fixture = Fixture()
        val firstSurface = surface()
        val firstTexture = texture()
        val first = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            firstSurface, firstTexture, 1280, 720,
        )!!
        val calls = mutableListOf<String>()
        val firstSubscription = fixture.registry.subscribePhysicalTargetInvalidation(
            first, firstSurface,
        ) { calls += "resize" }
        fixture.registry.resize(DisplayDestination.MAIN, firstSurface, firstTexture, 1280, 720)
        assertTrue(calls.isEmpty())
        val resized = fixture.registry.resize(
            DisplayDestination.MAIN, firstSurface, firstTexture, 1920, 720,
        )!!
        assertEquals(listOf("resize"), calls)
        firstSubscription.close()

        fixture.registry.subscribePhysicalTargetInvalidation(resized, firstSurface) {
            calls += "downgrade"
        }
        fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PREVIEW_ONLY,
            firstSurface, firstTexture, 1920, 720,
        )
        assertEquals(listOf("resize", "downgrade"), calls)

        val physicalAgain = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            firstSurface, firstTexture, 1920, 720,
        )!!
        fixture.registry.subscribePhysicalTargetInvalidation(physicalAgain, firstSurface) {
            calls += "replacement"
        }
        fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface(), texture(), 1920, 720,
        )
        assertEquals(listOf("resize", "downgrade", "replacement"), calls)
        fixture.registry.subscribePhysicalTargetInvalidation(physicalAgain, firstSurface) {
            calls += "stale-at-registration"
        }
        assertEquals(
            listOf("resize", "downgrade", "replacement", "stale-at-registration"),
            calls,
        )
    }

    @Test fun invalidationOnWithdrawAndTextureDestructionRunsOutsideRegistryLock() {
        val fixture = Fixture()
        val firstSurface = surface()
        val firstTexture = texture()
        val first = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            firstSurface, firstTexture, 1024, 600,
        )!!
        val callbackCanAcquireLock = AtomicReference(false)
        fixture.registry.subscribePhysicalTargetInvalidation(first, firstSurface) {
            val acquired = CountDownLatch(1)
            Thread {
                fixture.registry.snapshot(DisplayDestination.MAIN)
                acquired.countDown()
            }.start()
            callbackCanAcquireLock.set(acquired.await(2, TimeUnit.SECONDS))
        }
        fixture.registry.withdraw(
            DisplayDestination.MAIN, firstSurface, firstTexture, textureDestroyed = false,
        )!!.completeAfterLegacyOwnerRetired()
        assertTrue(callbackCanAcquireLock.get())

        val secondSurface = surface()
        val secondTexture = texture()
        val second = fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            secondSurface, secondTexture, 1024, 600,
        )!!
        var destroyedCallbacks = 0
        fixture.registry.subscribePhysicalTargetInvalidation(second, secondSurface) {
            destroyedCallbacks++
        }
        assertFalse(fixture.registry.onTextureDestroyed(secondTexture))
        assertEquals(1, destroyedCallbacks)
    }

    @Test fun cancelledOrOlderSubscriptionNeverInvalidatesNewerGeneration() {
        val fixture = Fixture()
        val oldTrust = routedExternalTrust(fixture.registry,
            externalCalibrationForTest(pixels = PixelSize(1280, 480)),
        )
        val sharedSurface = surface()
        val sharedTexture = texture()
        val old = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            sharedSurface, sharedTexture, 1280, 480, oldTrust,
        )!!
        var cancelledCalls = 0
        fixture.registry.subscribePhysicalTargetInvalidation(old, sharedSurface) {
            cancelledCalls++
        }.close()
        var oldCalls = 0
        fixture.registry.subscribePhysicalTargetInvalidation(old, sharedSurface) {
            oldCalls++
        }
        fixture.registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PREVIEW_ONLY,
            sharedSurface, sharedTexture, 1280, 480,
        )
        assertEquals(0, cancelledCalls)
        assertEquals(1, oldCalls)

        val successor = fixture.registry.publish(
            DisplayDestination.CLUSTER, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            surface(), texture(), 1280, 480,
            routedExternalTrust(fixture.registry,
                externalCalibrationForTest(pixels = PixelSize(1280, 480)),
            ),
        )!!
        var successorCalls = 0
        val successorSurface = fixture.registry.inspectPhysicalTarget(DisplayDestination.CLUSTER)!!.surface
        fixture.registry.subscribePhysicalTargetInvalidation(successor, successorSurface) {
            successorCalls++
        }
        fixture.registry.withdraw(
            DisplayDestination.CLUSTER, sharedSurface, sharedTexture, textureDestroyed = true,
        )!!.completeAfterLegacyOwnerRetired()
        assertEquals(0, successorCalls)
    }
}
