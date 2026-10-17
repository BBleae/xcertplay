package com.shilapi.xcertplay.ultra.egl

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.media.AndroidVideoSurfaceLease
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.media.AndroidVideoFrameReleaseReceipt
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraContentId
import com.shilapi.xcertplay.ultra.instrument.OwnedOpaqueArgbFrame
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidUltraEglBackendTest {
    @Test
    fun decoderReleaseIdentityIsBoundToExactActiveEglAndSurfaceLease() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(SurfaceDriver(surface), defaultControlTimeoutMillis = 500L),
        )
        val handle = syntheticHandle(AirPlayStreamIdentity(111, "frame-identity", null))
        val ingress = backend.openRemoteIngress(
            UltraDisplayGraphIngress(UltraIngressId("frame-identity"), handle),
        ) { exactHandle, exactSurface ->
            syntheticLease(exactHandle, exactSurface) {
                AndroidVideoSurfaceRetirementResult.RETIRED
            }
        }
        assertEquals(RemoteFrameIdentityDisposition.PENDING,
            backend.acceptVideoFrameReleaseReceipt(frameReleaseReceipt(handle, surface, 1L)))
        assertTrue(backend.drainMatchedVideoFrames().isEmpty())
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            backend.acceptVideoFrameReleaseReceipt(frameReleaseReceipt(handle, surface, 2L)))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            backend.acceptVideoFrameReleaseReceipt(frameReleaseReceipt(
                syntheticHandle(AirPlayStreamIdentity(111, "frame-identity", null)),
                surface, 1L,
            )))
        assertEquals(AndroidVideoSurfaceRetirementResult.RETIRED, ingress.retire(500L))
        assertEquals(RemoteFrameIdentityDisposition.UNMATCHED,
            backend.acceptVideoFrameReleaseReceipt(frameReleaseReceipt(handle, surface, 1L)))
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
    }

    @Test
    fun runningUploadTimeoutRetainsExactOwnedSnapshotUntilEglActionSettles() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val uploadStarted = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        val driver = SurfaceDriver(surface).apply {
            instrumentUploadStarted = uploadStarted
            instrumentUploadGate = releaseUpload
        }
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 100L),
        )
        val lease = backend.openInstrumentRaster(DisplayDestination.CLUSTER, PixelSize(320, 200))
        val caller = IntArray(320 * 200) { 0xff102030.toInt() }
        val frame = requireNotNull(OwnedOpaqueArgbFrame.capture(caller))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val uploading = executor.submit<UltraEglInstrumentUploadReceipt> {
                lease.replaceOwnedCompleteOpaqueFrame(frame)
            }
            assertTrue(uploadStarted.await(1, TimeUnit.SECONDS))
            caller.fill(0xffeeeeee.toInt())
            assertEquals(UltraEglInstrumentUploadReceipt.COMPLETION_UNCERTAIN,
                uploading.get(1, TimeUnit.SECONDS))
            assertEquals(UltraEglInstrumentUploadReceipt.REJECTED_NO_COMMIT,
                lease.replaceOwnedCompleteOpaqueFrame(frame))
            releaseUpload.countDown()
            assertTrue(driver.instrumentUploadFinished.await(1, TimeUnit.SECONDS))
            assertEquals(0xff102030.toInt(), driver.instrumentPixelAfterGate)
            assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
        } finally {
            releaseUpload.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun exactCustomProducerSizesReachDriverAndRemoteKeepsDecoderOwnedSize() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val driver = SurfaceDriver(surface)
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L),
        )
        val localSize = PixelSize(1920, 720)
        val overlaySize = PixelSize(477, 319)
        val local = backend.openLocalIngress(UltraContentId("local"), localSize)
        val overlay = backend.openOverlayIngress(UltraContentId("overlay"), overlaySize)
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "remote-size", null))
        backend.openRemoteIngress(
            UltraDisplayGraphIngress(UltraIngressId("remote-size"), handle),
        ) { exactHandle, exactSurface ->
            syntheticLease(exactHandle, exactSurface) {
                AndroidVideoSurfaceRetirementResult.RETIRED
            }
        }

        assertEquals(
            listOf(
                UltraEglIngressKey.Local(UltraContentId("local")) to localSize,
                UltraEglIngressKey.Overlay(UltraContentId("overlay")) to overlaySize,
                UltraEglIngressKey.Remote(UltraIngressId("remote-size")) to null,
            ),
            driver.createdIngresses,
        )
        assertNull(local.retire(500L))
        assertNull(overlay.retire(500L))
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
    }

    @Test
    fun exactDecoderLeaseConsumesOnlyRemainingBackendCloseBudget() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val driver = SurfaceDriver(surface)
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L),
        )
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "budget", null))
        var observedTimeout = Long.MAX_VALUE
        backend.openRemoteIngress(
            UltraDisplayGraphIngress(UltraIngressId("budget"), handle),
        ) { exactHandle, exactSurface ->
            syntheticLease(exactHandle, exactSurface) { timeoutMillis ->
                observedTimeout = timeoutMillis
                AndroidVideoSurfaceRetirementResult.RETIRED
            }
        }

        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
        assertTrue(observedTimeout in 0L..1_000L)
    }

    @Test
    fun mismatchedDecoderLeaseIsRetiredAndProducerRemainsFailClosed() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val driver = SurfaceDriver(surface)
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L),
        )
        val expected = syntheticHandle(AirPlayStreamIdentity(110, "expected", null))
        val wrong = syntheticHandle(AirPlayStreamIdentity(110, "wrong", null))
        var retireCalls = 0
        val wrongLease = syntheticLease(wrong, surface) {
            retireCalls++
            AndroidVideoSurfaceRetirementResult.RETIRED
        }

        assertThrows(IllegalStateException::class.java) {
            backend.openRemoteIngress(
                UltraDisplayGraphIngress(UltraIngressId("remote"), expected),
            ) { _, _ -> wrongLease }
        }

        assertEquals(1, retireCalls)
        assertEquals(
            UltraEglBackendRetirementResult.DECODER_RETIREMENT_UNCERTAIN,
            backend.retire(100L),
        )
    }

    @Test
    fun closeTimeoutObservesSameInFlightRetirementWithoutDuplicateCleanup() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val closeGate = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val driver = SurfaceDriver(surface, closeGate, closeStarted)
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 50L),
        )

        val startedAt = System.nanoTime()
        assertEquals(UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT, backend.retire(50L))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("close exceeded its global bound", elapsedMillis < 500L)
        assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
        assertEquals(1, driver.closeCalls)

        assertEquals(UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT, backend.retire(20L))
        assertEquals(1, driver.closeCalls)

        closeGate.countDown()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
        assertEquals(1, driver.closeCalls)
    }

    @Test
    fun openingHandleReservationRejectsAliasAndReentrantClosePreventsPublication() {
        val surface = allocateWithoutConstructor(Surface::class.java)
        val driver = SurfaceDriver(surface)
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L)
        val backend = AndroidUltraEglBackend(renderThread)
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "same-session-stream", null))
        val first = UltraDisplayGraphIngress(UltraIngressId("first"), handle)
        val alias = UltraDisplayGraphIngress(UltraIngressId("alias"), handle)
        val acquirerEntered = CountDownLatch(1)
        val releaseAcquirer = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val opening = executor.submit<Throwable?> {
                runCatching {
                    backend.openRemoteIngress(first) { _, _ ->
                        acquirerEntered.countDown()
                        releaseAcquirer.await(2, TimeUnit.SECONDS)
                        null
                    }
                }.exceptionOrNull()
            }
            assertTrue(acquirerEntered.await(1, TimeUnit.SECONDS))

            assertThrows(IllegalArgumentException::class.java) {
                backend.openRemoteIngress(alias) { _, _ -> null }
            }
            assertEquals(
                UltraEglBackendRetirementResult.EXTERNAL_ATTACHMENT_IN_FLIGHT,
                backend.retire(100L),
            )

            releaseAcquirer.countDown()
            assertTrue(opening.get(1, TimeUnit.SECONDS) is IllegalStateException)
            assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
            assertEquals(1, driver.closeCalls)
        } finally {
            releaseAcquirer.countDown()
            executor.shutdownNow()
        }
    }

    private class SurfaceDriver(
        private val surface: Surface,
        private val closeGate: CountDownLatch? = null,
        private val closeStarted: CountDownLatch? = null,
    ) : UltraEglDriver {
        var closeCalls = 0
        val createdIngresses = mutableListOf<Pair<UltraEglIngressKey, PixelSize?>>()
        var instrumentUploadStarted: CountDownLatch? = null
        var instrumentUploadGate: CountDownLatch? = null
        val instrumentUploadFinished = CountDownLatch(1)
        @Volatile var instrumentPixelAfterGate: Int? = null

        private class Ingress(
            override val producerEndpoint: Any,
        ) : UltraEglDriverIngress {
            override val hasUsableFrame = false
        }

        override fun initialize() = Unit
        override fun createExternalIngress(
            key: UltraEglIngressKey,
            sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit,
        ): UltraEglDriverIngress = Ingress(surface).also {
            createdIngresses += key to sizePixels
        }

        override fun updateIngressFrame(ingress: UltraEglDriverIngress) = Unit
        override fun createInstrumentIngress(
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ): UltraEglDriverIngress = Ingress(Any())

        override fun uploadInstrumentFrame(
            ingress: UltraEglDriverIngress,
            argbPixels: IntArray,
        ) {
            instrumentUploadStarted?.countDown()
            instrumentUploadGate?.await(2, TimeUnit.SECONDS)
            instrumentPixelAfterGate = argbPixels[0]
            instrumentUploadFinished.countDown()
        }
        override fun createWindowOutput(
            destination: DisplayDestination,
            nativeWindow: Any,
            sizePixels: PixelSize,
        ): UltraEglDriverOutput = object : UltraEglDriverOutput {}

        override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) = Unit
        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) = Unit
        override fun clear(
            output: UltraEglDriverOutput,
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ) = Unit

        override fun finish() = Unit
        override fun destroyIngress(ingress: UltraEglDriverIngress) = Unit
        override fun destroyOutput(output: UltraEglDriverOutput) = Unit
        override fun closeWithProof(deadlineNanos: Long): UltraEglDriverRetirementProof {
            closeCalls++
            closeStarted?.countDown()
            closeGate?.await(2, TimeUnit.SECONDS)
            return UltraEglDriverRetirementProof(retired = true)
        }
    }

    private fun syntheticHandle(identity: AirPlayStreamIdentity): AirPlayVideoStreamHandle {
        val epochClass = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
        val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val constructor = AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
        return constructor.newInstance(identity, epoch) as AirPlayVideoStreamHandle
    }

    private fun syntheticLease(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
        retire: (Long) -> AndroidVideoSurfaceRetirementResult,
    ): AndroidVideoSurfaceLease {
        val permitClass = Class.forName(
            "com.shilapi.xcertplay.media.AndroidVideoSurfaceLease\$AttachmentPermit",
        )
        val permit = permitClass.getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
        val proofClass = Class.forName("com.shilapi.xcertplay.media.IdentityAttachmentProof")
        val proof = proofClass.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
            .newInstance(handle, surface)
        val constructor = AndroidVideoSurfaceLease::class.java.declaredConstructors
            .single { it.parameterCount == 5 }
            .apply { isAccessible = true }
        return constructor.newInstance(proof, permit, 1L, null, retire) as
            AndroidVideoSurfaceLease
    }

    private fun frameReleaseReceipt(
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
        generation: Long,
    ): AndroidVideoFrameReleaseReceipt {
        val constructor = AndroidVideoFrameReleaseReceipt::class.java.declaredConstructors
            .single { it.parameterCount == 12 }.apply { isAccessible = true }
        return constructor.newInstance(handle, surface, generation, 0L, 0L, null,
            100L, 100L, 1_000L, 1_001L, null, 100_000L) as
            AndroidVideoFrameReleaseReceipt
    }

    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(type) as T
    }
}
