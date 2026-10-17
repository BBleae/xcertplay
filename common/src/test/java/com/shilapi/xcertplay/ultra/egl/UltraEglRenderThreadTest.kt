package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraOutputScene
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UltraEglRenderThreadTest {
    @Test
    fun oneRemoteFrameDuringFullQueueEventuallyUpdatesIngress() {
        val driver = MinimalDriver()
        val renderThread = UltraEglRenderThread(driver, commandCapacity = 1,
            defaultControlTimeoutMillis = 5_000L)
        val key = UltraEglIngressKey.Remote(UltraIngressId("one-frame-full-queue"))
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            renderThread.call { it.openIngress(key, 1L, null) }
            val first = pool.submit {
                renderThread.call(timeoutMillis = 5_000L) {
                    running.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            }
            assertTrue(running.await(1, TimeUnit.SECONDS))
            val blocker = pool.submit {
                renderThread.call(timeoutMillis = 5_000L) { Unit }
            }
            awaitQueueSize(renderThread, 1)
            driver.signalFrame()
            assertEquals(1, renderThread.queueSnapshot().deferredFrameCount)
            release.countDown()
            first.get(1, TimeUnit.SECONDS)
            blocker.get(1, TimeUnit.SECONDS)
            assertTrue(driver.updated.await(1, TimeUnit.SECONDS))
            assertEquals(1, driver.updatedCount.get())
            assertEquals(0, renderThread.queueSnapshot().deferredFrameCount)
        } finally {
            release.countDown()
            pool.shutdownNow()
            runCatching { renderThread.call(allowStartupFailure = true) {
                it.closeDriver(deadlineAfterMillis(1_000L))
            } }
            renderThread.shutdown(deadlineAfterMillis(1_000L))
        }
    }

    @Test
    fun onlyFinishedPresentedTicketCarriesExactTimestampAndCoalescedOrFailedTicketsDoNot() {
        val driver = MinimalDriver(providePresentationTimestamps = true)
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 5_000L)
        val size = PixelSize(800, 480)
        val main = DisplayDestination.MAIN
        val cluster = DisplayDestination.CLUSTER
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            renderThread.call { core ->
                core.replaceOutputBatch(7L, listOf(main, cluster).map { destination ->
                    UltraEglOutputBatchCandidate(destination, 7L, Any(), size) { true }
                })
            }
            fun scene(revision: Long) = UltraEglResolvedSubmission(
                UltraMultiOutputRenderSnapshot(revision, listOf(
                    UltraOutputScene(main, size, emptyList()),
                    UltraOutputScene(cluster, size, emptyList()),
                )), 21L, emptyMap(), mapOf(main to 7L, cluster to 7L),
            )

            val control = pool.submit {
                renderThread.call(timeoutMillis = 5_000L) {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val old = renderThread.submit(scene(1L))
            val current = renderThread.submit(scene(2L))
            val coalesced = old.await(1_000L)
            assertEquals(UltraEglRenderDisposition.COALESCED, coalesced.disposition)
            assertTrue(coalesced.producerTimestampNanosByDestination.isEmpty())
            assertTrue(coalesced.outputGenerationsByDestination.isEmpty())
            release.countDown()
            control.get(1, TimeUnit.SECONDS)

            val presented = current.await(1_000L)
            assertEquals(UltraEglRenderDisposition.PRESENTED, presented.disposition)
            assertEquals(2L, presented.revision)
            assertEquals(21L, presented.graphRevision)
            assertEquals(7L, presented.outputBatchGeneration)
            assertEquals(mapOf(main to 7L, cluster to 7L),
                presented.outputGenerationsByDestination)
            assertEquals(mapOf(main to 101L, cluster to 102L),
                presented.producerTimestampNanosByDestination)

            driver.failDestination = cluster
            val failed = renderThread.submit(scene(3L)).await(1_000L)
            assertEquals(UltraEglRenderDisposition.FAILED, failed.disposition)
            assertTrue(failed.producerTimestampNanosByDestination.isEmpty())
            assertTrue(failed.outputGenerationsByDestination.isEmpty())
        } finally {
            release.countDown()
            pool.shutdownNow()
            runCatching { renderThread.call(allowStartupFailure = true) {
                it.closeDriver(deadlineAfterMillis(1_000L))
            } }
            renderThread.shutdown(deadlineAfterMillis(1_000L))
        }
    }

    @Test
    fun completionAtTimeoutCannotStartQueuedControlBeforePoisonIsApplied() {
        val releaseAction = CountDownLatch(1)
        val completionPublished = CountDownLatch(1)
        val allowWorkerDisposition = CountDownLatch(1)
        val driver = MinimalDriver()
        val renderThread = UltraEglRenderThread(
            driver,
            defaultControlTimeoutMillis = 50L,
            beforeTimeoutOutcomeReadForTest = {
                releaseAction.countDown()
                assertTrue(completionPublished.await(1, TimeUnit.SECONDS))
            },
            afterActionCompletedBeforeDispositionForTest = {
                completionPublished.countDown()
                allowWorkerDisposition.await(1, TimeUnit.SECONDS)
            },
        )
        val actionStarted = CountDownLatch(1)
        val secondExecutions = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Throwable?> {
                runCatching {
                    renderThread.call(timeoutMillis = 50L) {
                        actionStarted.countDown()
                        releaseAction.await(1, TimeUnit.SECONDS)
                    }
                }.exceptionOrNull()
            }
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
            val second = pool.submit<Throwable?> {
                runCatching {
                    renderThread.call(timeoutMillis = 1_000L) {
                        secondExecutions.incrementAndGet()
                    }
                }.exceptionOrNull()
            }
            awaitQueueSize(renderThread, 1)

            assertTrue(first.get(1, TimeUnit.SECONDS) is UltraEglRunningControlTimeoutException)
            assertTrue(second.get(1, TimeUnit.SECONDS) is UltraEglPendingControlTimeoutException)
            assertEquals(0, secondExecutions.get())
            allowWorkerDisposition.countDown()
            assertTrue(renderThread.awaitUncertainControl(deadlineAfterMillis(1_000L)))
            val proof = renderThread.call(allowStartupFailure = true) {
                it.closeDriver(deadlineAfterMillis(1_000L))
            }
            assertTrue(proof.retired)
            assertTrue(renderThread.shutdown(deadlineAfterMillis(1_000L)))
        } finally {
            releaseAction.countDown()
            allowWorkerDisposition.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun runningTimeoutPoisonsAndCancelsEveryQueuedControl() {
        val driver = MinimalDriver()
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 200L)
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val secondExecutions = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Throwable?> {
                runCatching {
                    renderThread.call(timeoutMillis = 200L) {
                        actionStarted.countDown()
                        releaseAction.await(2, TimeUnit.SECONDS)
                    }
                }.exceptionOrNull()
            }
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
            val second = pool.submit<Throwable?> {
                runCatching {
                    renderThread.call(timeoutMillis = 2_000L) {
                        secondExecutions.incrementAndGet()
                    }
                }.exceptionOrNull()
            }
            awaitQueueSize(renderThread, 1)

            assertTrue(first.get(1, TimeUnit.SECONDS) is UltraEglRunningControlTimeoutException)
            assertTrue(second.get(1, TimeUnit.SECONDS) is UltraEglPendingControlTimeoutException)
            assertEquals(0, secondExecutions.get())
            assertTrue(renderThread.hasRetirementUncertainty())
            assertFalse(renderThread.awaitUncertainControl(deadlineAfterMillis(5L)))

            releaseAction.countDown()
            assertTrue(renderThread.awaitUncertainControl(deadlineAfterMillis(1_000L)))
            val proof = renderThread.call(allowStartupFailure = true) {
                it.closeDriver(deadlineAfterMillis(1_000L))
            }
            assertTrue(proof.retired)
            assertTrue(renderThread.shutdown(deadlineAfterMillis(1_000L)))
        } finally {
            releaseAction.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun pendingTimeoutCancelsWithoutPoisoning() {
        val driver = MinimalDriver()
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 1_000L)
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit {
                renderThread.call(timeoutMillis = 1_000L) {
                    actionStarted.countDown()
                    releaseAction.await(1, TimeUnit.SECONDS)
                }
            }
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
            expectFailure<UltraEglPendingControlTimeoutException> {
                renderThread.call(timeoutMillis = 10L) { fail("cancelled action executed") }
            }
            assertFalse(renderThread.hasRetirementUncertainty())
            releaseAction.countDown()
            first.get(1, TimeUnit.SECONDS)
            val proof = renderThread.call { it.closeDriver(deadlineAfterMillis(1_000L)) }
            assertTrue(proof.retired)
            assertTrue(renderThread.shutdown(deadlineAfterMillis(1_000L)))
        } finally {
            releaseAction.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun startupTimeoutLeavesTrackedOwnerForLaterBoundedRetirement() {
        val initializeGate = CountDownLatch(1)
        val driver = MinimalDriver(initializeGate)
        val renderThread = UltraEglRenderThread(driver, defaultControlTimeoutMillis = 20L)

        expectFailure<UltraEglStartupTimeoutException> {
            renderThread.call(timeoutMillis = 20L) { Unit }
        }
        assertTrue(renderThread.hasRetirementUncertainty())
        initializeGate.countDown()
        assertTrue(renderThread.awaitStartupForRetirement(deadlineAfterMillis(1_000L)))
        val proof = renderThread.call(
            timeoutMillis = 1_000L,
            allowStartupFailure = true,
        ) { it.closeDriver(deadlineAfterMillis(1_000L)) }
        assertTrue(proof.retired)
        assertEquals(1, driver.closeCalls)
        assertTrue(renderThread.shutdown(deadlineAfterMillis(1_000L)))
    }

    private fun awaitQueueSize(renderThread: UltraEglRenderThread, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (renderThread.queueSnapshot().size != expected && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(expected, renderThread.queueSnapshot().size)
    }

    private class MinimalDriver(
        private val initializeGate: CountDownLatch? = null,
        private val providePresentationTimestamps: Boolean = false,
    ) : UltraEglDriver {
        var closeCalls = 0
        @Volatile var failDestination: DisplayDestination? = null
        @Volatile private var frameSignal: (() -> Unit)? = null
        val updated = CountDownLatch(1)
        val updatedCount = AtomicInteger()
        private var nextTimestamp = 100L

        override fun initialize() {
            initializeGate?.await(2, TimeUnit.SECONDS)
        }

        override fun createExternalIngress(
            key: UltraEglIngressKey,
            sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit,
        ): UltraEglDriverIngress = object : UltraEglDriverIngress {
            override val producerEndpoint = Any()
            override val hasUsableFrame = false
        }.also { ingress -> frameSignal = { onFrameAvailable(ingress) } }

        override fun updateIngressFrame(ingress: UltraEglDriverIngress) {
            updatedCount.incrementAndGet()
            updated.countDown()
        }

        fun signalFrame() = checkNotNull(frameSignal).invoke()
        override fun createWindowOutput(
            destination: DisplayDestination,
            nativeWindow: Any,
            sizePixels: PixelSize,
        ): UltraEglDriverOutput = object : UltraEglDriverOutput {}

        override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) = Unit
        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) {
            if (scene.destination == failDestination) {
                throw UltraEglSurfaceLostException(scene.destination, "synthetic swap failure")
            }
        }
        override fun renderWithPresentationReceipt(
            output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene,
        ): Long? {
            render(output, scene)
            return if (providePresentationTimestamps) ++nextTimestamp else null
        }
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
            return UltraEglDriverRetirementProof(retired = true)
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (problem: Throwable) {
            if (problem !is T) throw problem
            return problem
        }
        error("unreachable")
    }
}
