package com.shilapi.xcertplay.ultra.egl

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.media.AndroidVideoSurfaceLease
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.ultra.AndroidDisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplaySurfaceDescriptor
import com.shilapi.xcertplay.ultra.DisplaySurfaceId
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraContentId
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraOutputScene
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidUltraEglBackendRetirementTruthTest {
    @Test
    fun ingressRetirementWaitsForPriorUncertainControlWithoutEarlyRelease() {
        val driver = FakeDriver(validSurface())
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L)
        val backend = AndroidUltraEglBackend(renderThread)
        val executor = Executors.newFixedThreadPool(2)
        val controlStarted = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val retirementStarted = CountDownLatch(1)
        try {
            val ingress = backend.openLocalIngress(
                UltraContentId("uncertain-control"), PixelSize(16, 16),
            )
            val control = executor.submit<Throwable?> {
                runCatching {
                    renderThread.call(timeoutMillis = 30L) {
                        controlStarted.countDown()
                        releaseControl.await(2, TimeUnit.SECONDS)
                    }
                }.exceptionOrNull()
            }
            assertTrue(controlStarted.await(1, TimeUnit.SECONDS))
            assertTrue(control.get(1, TimeUnit.SECONDS) is UltraEglRunningControlTimeoutException)

            assertEquals(AndroidVideoSurfaceRetirementResult.INTERRUPTED, ingress.retire(0L))
            assertEquals(1, backend.diagnostics().ingressCount)
            assertEquals(0, driver.destroyCalls)

            val retirement = executor.submit<AndroidVideoSurfaceRetirementResult?> {
                retirementStarted.countDown()
                ingress.retire(1_000L)
            }
            assertTrue(retirementStarted.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) {
                retirement.get(100, TimeUnit.MILLISECONDS)
            }
            assertFalse(retirement.isDone)
            assertEquals(0, driver.destroyCalls)

            releaseControl.countDown()
            assertEquals(null, retirement.get(1, TimeUnit.SECONDS))
            assertEquals(1, driver.destroyCalls)
            assertEquals(0, backend.diagnostics().ingressCount)
        } finally {
            releaseControl.countDown()
            try {
                assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun retirementOnlyDiagnosticsKeepsOriginalControlTimeoutAndIngressReservation() {
        val createStarted = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val driver = FakeDriver(validSurface(), createStarted, releaseCreate)
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 50L)
        val backend = AndroidUltraEglBackend(renderThread)
        val executor = Executors.newSingleThreadExecutor()
        try {
            renderThread.call(timeoutMillis = 1_000L) { Unit } // Finish startup first.
            val opening = executor.submit<Throwable?> {
                runCatching {
                    backend.openLocalIngress(UltraContentId("diagnostic-timeout"), PixelSize(16, 16))
                }.exceptionOrNull()
            }
            assertTrue(createStarted.await(1, TimeUnit.SECONDS))
            val original = opening.get(1, TimeUnit.SECONDS)
            assertTrue(original is UltraEglRunningControlTimeoutException)

            repeat(2) {
                val snapshot = backend.diagnostics()
                assertEquals(UltraEglBackendPhase.RETIREMENT_UNCERTAIN, snapshot.phase)
                assertSame(original, snapshot.failure)
                assertEquals(1, snapshot.ingressCount)
                assertEquals(0, snapshot.outputCount)
            }
        } finally {
            releaseCreate.countDown()
            try {
                assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun decoderRetiredButDriverDestroyFailsKeepsIngressReservedUntilRetry() {
        val surface = validSurface()
        val destroyFailure = IllegalStateException("synthetic EGL destroy failure")
        val driver = FakeDriver(surface).apply { failNextDestroy = destroyFailure }
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L),
        )
        val handle = syntheticHandle(AirPlayStreamIdentity(110, "retirement-truth", null))
        var decoderRetireCalls = 0
        val ingress = backend.openRemoteIngress(
            UltraDisplayGraphIngress(UltraIngressId("remote"), handle),
        ) { exactHandle, exactSurface ->
            syntheticLease(exactHandle, exactSurface) {
                decoderRetireCalls++
                AndroidVideoSurfaceRetirementResult.RETIRED
            }
        }

        assertEquals(AndroidVideoSurfaceRetirementResult.INTERRUPTED, ingress.retire(500L))
        assertEquals(1, driver.destroyCalls)
        assertEquals(1, backend.diagnostics().ingressCount)
        assertSame(destroyFailure, backend.diagnostics().failure)
        assertEquals(UltraEglBackendPhase.RETIREMENT_UNCERTAIN, backend.diagnostics().phase)

        assertEquals(AndroidVideoSurfaceRetirementResult.RETIRED, ingress.retire(500L))
        assertEquals(2, driver.destroyCalls)
        assertEquals(0, backend.diagnostics().ingressCount)
        assertEquals(2, decoderRetireCalls)
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
    }

    @Test
    fun preResolvedSubmissionRejectsClosedBorrowedOutputTarget() {
        val surface = validSurface()
        val driver = FakeDriver(surface)
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 500L),
        )
        val size = PixelSize(800, 480)
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            UltraDisplayOutputId.Android("main"),
        )
        val target = AndroidDisplaySurfaceTarget(
            surface,
            DisplaySurfaceDescriptor(DisplaySurfaceId("main-surface"), size),
        )
        val outputLease = backend.attachOutput(DisplayDestination.MAIN, output, target)
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        val scene = UltraMultiOutputRenderSnapshot(
            1L,
            listOf(UltraOutputScene(DisplayDestination.MAIN, size, emptyList())),
        )
        val resolved = backend.resolve(
            scene.resolveAgainst(graph, mapOf(DisplayDestination.MAIN to output.outputId)),
        )

        target.close() // BORROWED: Surface remains valid, but the logical target does not.
        assertTrue(surface.isValid)
        assertThrows(IllegalArgumentException::class.java) { backend.submit(resolved) }
        assertEquals(0, driver.renderCalls)

        outputLease.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
    }

    @Test
    fun borrowedTargetClosedAfterEnqueueFailsQueuedSceneWithoutPoisoningBackend() {
        val surface = validSurface()
        val driver = FakeDriver(surface)
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 2_000L)
        val backend = AndroidUltraEglBackend(renderThread)
        val size = PixelSize(800, 480)
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            UltraDisplayOutputId.Android("queued-main"),
        )
        val descriptor = DisplaySurfaceDescriptor(DisplaySurfaceId("queued-main-surface"), size)
        val target = AndroidDisplaySurfaceTarget(surface, descriptor)
        val lease = backend.attachOutput(DisplayDestination.MAIN, output, target)
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        fun submission(revision: Long) = UltraMultiOutputRenderSnapshot(
            revision,
            listOf(UltraOutputScene(DisplayDestination.MAIN, size, emptyList())),
        ).resolveAgainst(graph, mapOf(DisplayDestination.MAIN to output.outputId))
        val resolved = backend.resolve(submission(1L))
        val baselineFinishCount = driver.finishCalls
        val controlStarted = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val control = executor.submit {
                renderThread.call(timeoutMillis = 2_000L) {
                    controlStarted.countDown()
                    check(releaseControl.await(1, TimeUnit.SECONDS))
                }
            }
            assertTrue(controlStarted.await(1, TimeUnit.SECONDS))
            val ticket = backend.submit(resolved)
            target.close() // BORROWED Surface stays valid; only the target lease goes stale.
            assertTrue(surface.isValid)
            releaseControl.countDown()
            control.get(1, TimeUnit.SECONDS)

            val result = ticket.await(1_000L)
            assertEquals(UltraEglRenderDisposition.FAILED, result.disposition)
            assertTrue(result.failure is UltraEglOutputTargetUnavailableException)
            assertEquals(0, driver.renderCalls)
            assertEquals(0, driver.clearCalls)
            assertEquals(baselineFinishCount, driver.finishCalls)
            assertEquals(UltraEglBackendPhase.OPEN, backend.diagnostics().phase)
            assertEquals(-1L, backend.diagnostics().lastPresentedRevision)

            lease.close()
            val replacement = backend.attachOutput(
                DisplayDestination.MAIN,
                output,
                AndroidDisplaySurfaceTarget(surface, descriptor),
            )
            assertEquals(
                UltraEglRenderDisposition.PRESENTED,
                backend.submit(submission(2L)).await(1_000L).disposition,
            )
            assertEquals(1, driver.renderCalls)
            replacement.close()
            assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
        } finally {
            releaseControl.countDown()
            executor.shutdownNow()
        }
    }

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid(): Boolean = true
    }

    private fun validSurface(): Surface = allocateWithoutConstructor(ValidSurface::class.java)

    private class FakeDriver(
        private val surface: Surface,
        private val createStarted: CountDownLatch? = null,
        private val releaseCreate: CountDownLatch? = null,
    ) : UltraEglDriver {
        var failNextDestroy: Throwable? = null
        @Volatile var destroyCalls = 0
        var renderCalls = 0
        var clearCalls = 0
        var finishCalls = 0

        override fun initialize() = Unit

        override fun createExternalIngress(
            key: UltraEglIngressKey,
            sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit,
        ): UltraEglDriverIngress {
            createStarted?.countDown()
            check(releaseCreate?.await(5, TimeUnit.SECONDS) != false)
            return object : UltraEglDriverIngress {
                override val producerEndpoint: Any = surface
                override val hasUsableFrame = false
            }
        }

        override fun updateIngressFrame(ingress: UltraEglDriverIngress) = Unit

        override fun createWindowOutput(
            destination: DisplayDestination,
            nativeWindow: Any,
            sizePixels: PixelSize,
        ): UltraEglDriverOutput = object : UltraEglDriverOutput {}

        override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) = Unit
        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) {
            renderCalls++
        }

        override fun clear(
            output: UltraEglDriverOutput,
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ) {
            clearCalls++
        }

        override fun finish() {
            finishCalls++
        }

        override fun destroyIngress(ingress: UltraEglDriverIngress) {
            destroyCalls++
            failNextDestroy?.let { failure ->
                failNextDestroy = null
                throw failure
            }
        }

        override fun destroyOutput(output: UltraEglDriverOutput) = Unit

        override fun closeWithProof(deadlineNanos: Long) = UltraEglDriverRetirementProof(true)
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

    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(type) as T
    }
}
