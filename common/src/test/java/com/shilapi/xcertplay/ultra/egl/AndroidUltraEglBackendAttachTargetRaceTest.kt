package com.shilapi.xcertplay.ultra.egl

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.AndroidDisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplaySurfaceDescriptor
import com.shilapi.xcertplay.ultra.DisplaySurfaceId
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidUltraEglBackendAttachTargetRaceTest {
    @Test
    fun borrowedTargetClosedWhileAttachQueuedIsNotPublishedAndCanRetry() {
        val surface = validSurface()
        val driver = OutputDriver()
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 2_000L)
        val backend = AndroidUltraEglBackend(renderThread)
        val descriptor = DisplaySurfaceDescriptor(DisplaySurfaceId("main"), PixelSize(800, 480))
        val target = AndroidDisplaySurfaceTarget(surface, descriptor)
        val output = graphOutput()
        val controlStarted = CountDownLatch(1)
        val releaseControl = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val blocker = executor.submit {
                renderThread.call(timeoutMillis = 2_000L) {
                    controlStarted.countDown()
                    check(releaseControl.await(1, TimeUnit.SECONDS))
                }
            }
            assertTrue(controlStarted.await(1, TimeUnit.SECONDS))
            val attaching = executor.submit<Throwable?> {
                runCatching {
                    backend.attachOutput(DisplayDestination.MAIN, output, target)
                }.exceptionOrNull()
            }
            assertTrue("attach control was not queued", awaitQueuedControl(renderThread))

            target.close() // BORROWED: native Surface is still valid while logical target is not.
            assertTrue(surface.isValid)
            releaseControl.countDown()
            blocker.get(1, TimeUnit.SECONDS)

            val rejection = attaching.get(2, TimeUnit.SECONDS)
            assertTrue(rejection is UltraEglOutputTargetUnavailableException)
            assertEquals(1, driver.createdOutputs)
            assertEquals(1, driver.destroyedOutputs)
            assertEquals(0, backend.diagnostics().outputCount)
            assertEquals(UltraEglBackendPhase.OPEN, backend.diagnostics().phase)
            val probe = Any()
            assertTrue(target.ownership.claim(probe))
            target.ownership.release(probe)

            val replacement = backend.attachOutput(
                DisplayDestination.MAIN,
                output,
                AndroidDisplaySurfaceTarget(surface, descriptor),
            )
            assertEquals(1, backend.diagnostics().outputCount)
            replacement.close()
            assertEquals(2, driver.destroyedOutputs)
            assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
        } finally {
            releaseControl.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedCandidateCleanupRetainsOwnershipUntilDriverRetirementProof() {
        val surface = validSurface()
        val driver = OutputDriver().apply { failNextDestroy = true }
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 2_000L),
        )
        val target = AndroidDisplaySurfaceTarget(
            surface,
            DisplaySurfaceDescriptor(DisplaySurfaceId("main"), PixelSize(800, 480)),
        )
        driver.afterCreate = target::close

        val problem = runCatching {
            backend.attachOutput(DisplayDestination.MAIN, graphOutput(), target)
        }.exceptionOrNull()
        assertTrue(problem is UltraEglCandidateResourceRetainedException)
        assertTrue(problem?.cause is UltraEglOutputTargetUnavailableException)
        assertEquals(UltraEglBackendPhase.RETIREMENT_UNCERTAIN, backend.diagnostics().phase)
        assertThrows(IllegalStateException::class.java) {
            target.ownership.claim(Any())
        }

        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
        val probe = Any()
        assertTrue(target.ownership.claim(probe))
        target.ownership.release(probe)
    }

    @Test
    fun invalidReplacementReleasesBothProvenRetiredTargets() {
        val firstSurface = validSurface()
        val replacementSurface = validSurface()
        val driver = OutputDriver()
        val backend = AndroidUltraEglBackend(
            UltraEglRenderThread(driver, defaultControlTimeoutMillis = 2_000L),
        )
        val descriptor = DisplaySurfaceDescriptor(DisplaySurfaceId("main"), PixelSize(800, 480))
        val output = graphOutput()
        val firstTarget = AndroidDisplaySurfaceTarget(firstSurface, descriptor)
        val firstLease = backend.attachOutput(DisplayDestination.MAIN, output, firstTarget)
        val replacementTarget = AndroidDisplaySurfaceTarget(replacementSurface, descriptor)
        driver.afterCreate = replacementTarget::close

        val problem = runCatching {
            backend.attachOutput(DisplayDestination.MAIN, output, replacementTarget)
        }.exceptionOrNull()
        assertTrue(problem is UltraEglOutputTargetUnavailableException)
        assertEquals(2, driver.destroyedOutputs)
        assertEquals(0, backend.diagnostics().outputCount)
        assertEquals(UltraEglBackendPhase.OPEN, backend.diagnostics().phase)
        firstLease.close() // Its resource was already retired by the replacement attach.
        listOf(firstTarget, replacementTarget).forEach { retired ->
            val probe = Any()
            assertTrue(retired.ownership.claim(probe))
            retired.ownership.release(probe)
        }

        driver.afterCreate = null
        val retry = backend.attachOutput(
            DisplayDestination.MAIN,
            output,
            AndroidDisplaySurfaceTarget(replacementSurface, descriptor),
        )
        retry.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire(1_000L))
    }

    private fun awaitQueuedControl(thread: UltraEglRenderThread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            if (thread.queueSnapshot().size > 0) return true
            Thread.yield()
        }
        return false
    }

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid(): Boolean = true
    }

    private fun validSurface(): Surface {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(ValidSurface::class.java) as Surface
    }

    private fun graphOutput() = UltraDisplayGraphOutput(
        UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000013")),
        UltraDisplayOutputId.Android("main"),
    )

    private class OutputDriver : UltraEglDriver {
        var createdOutputs = 0
        var destroyedOutputs = 0
        var failNextDestroy = false
        var afterCreate: (() -> Unit)? = null

        override fun initialize() = Unit

        override fun createExternalIngress(
            key: UltraEglIngressKey,
            sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit,
        ): UltraEglDriverIngress = error("No ingress is used in this test")

        override fun updateIngressFrame(ingress: UltraEglDriverIngress) = Unit

        override fun createWindowOutput(
            destination: DisplayDestination,
            nativeWindow: Any,
            sizePixels: PixelSize,
        ): UltraEglDriverOutput {
            createdOutputs++
            afterCreate?.invoke()
            return object : UltraEglDriverOutput {}
        }

        override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) = Unit
        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) = Unit
        override fun clear(
            output: UltraEglDriverOutput,
            destination: DisplayDestination,
            sizePixels: PixelSize,
        ) = Unit

        override fun finish() = Unit
        override fun destroyIngress(ingress: UltraEglDriverIngress) = Unit
        override fun destroyOutput(output: UltraEglDriverOutput) {
            if (failNextDestroy) {
                failNextDestroy = false
                throw IllegalStateException("synthetic output cleanup failure")
            }
            destroyedOutputs++
        }

        override fun closeWithProof(deadlineNanos: Long) = UltraEglDriverRetirementProof(true)
    }
}
