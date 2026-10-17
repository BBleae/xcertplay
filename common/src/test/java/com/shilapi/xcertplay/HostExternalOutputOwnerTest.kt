package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HostExternalOutputOwnerTest {
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

    private class ManualScheduler : HostExternalOutputScheduler {
        private class Scheduled(val action: () -> Unit) {
            var cancelled = false
        }
        private val tasks = ArrayDeque<Scheduled>()
        override fun schedule(delayMillis: Long, action: () -> Unit): Closeable {
            val task = Scheduled(action)
            tasks.addLast(task)
            return Closeable { task.cancelled = true }
        }
        fun fireNext(evenIfCancelled: Boolean = false) {
            val task = tasks.removeFirst()
            if (!task.cancelled || evenIfCancelled) task.action()
        }
        fun hasTasks() = tasks.isNotEmpty()
    }

    private class FakePresentation : HostExternalOutputPresentation {
        var callback: ((DisplayDestination) -> Unit)? = null
        var subscriptionCloses = 0
        var closeAttempts = 0
        var failCloses = 0
        var callbackPresentAtClose = false
        var synchronousReplay: DisplayDestination? = null
        override fun subscribeReady(onReady: (DisplayDestination) -> Unit): Closeable {
            callback = onReady
            synchronousReplay?.let(onReady)
            return Closeable {
                subscriptionCloses++
                if (callback === onReady) callback = null
            }
        }
        fun emit(destination: DisplayDestination) = callback?.invoke(destination)
        override fun close() {
            closeAttempts++
            if (callback != null) callbackPresentAtClose = true
            if (failCloses-- > 0) error("injected Presentation close failure")
            callback = null
        }
    }

    private class Fixture(test: HostExternalOutputOwnerTest) {
        val sink = test.sink()
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val routeFactory = AndroidMediaSinkExternalClusterRouteFactory(sink, registry)
        val presentation = FakePresentation()
        val deadline = ManualScheduler()
        val retirementScheduler = ManualScheduler()
        val retirement = HostExternalOutputRetirementQueue(retirementScheduler)
        val destination = DisplayDestination(
            DisplayDestinationRole.CLUSTER, DisplayDestinationId("hdmi-1"),
        )
        val pixels = PixelSize(800, 480)
        val owner = HostExternalOutputOwner(
            sink, registry, routeFactory, presentation,
            deadline, Executor { it.run() }, retirement,
        )
        private val baseline = AirPlayConfig(
            "bench", "id", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(800, 480), AirPlayDisplayConfig(800, 480),
        )
        val staged = HostUltraTwoPhaseConfig(baseline, 17)
        val ticket = staged.issueTicket(17)!!
        var route: ExternalClusterLegacyRoute? = null
        var trust: ExternalPhysicalTargetTrustToken? = null

        fun publish(test: HostExternalOutputOwnerTest) {
            val surface = test.surface()
            val texture = test.texture()
            registry.publish(destination, HostDisplaySurfaceKind.PREVIEW_ONLY,
                surface, texture, pixels.widthPixels, pixels.heightPixels)
            route = requireNotNull(routeFactory.attach(destination, surface))
            trust = registry.newExternalPhysicalTargetTrustToken(
                externalCalibrationForTest(destination, pixels),
            ).also { it.bindRouteProof { route?.isCurrent == true } }
            assertNotNull(registry.publish(destination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                surface, texture, pixels.widthPixels, pixels.heightPixels, trust))
        }

        fun retire() {
            owner.close()
            while (retirementScheduler.hasTasks() && retirement.pendingCount() > 0) {
                retirementScheduler.fireNext()
            }
            route?.retire()
            sink.close()
        }
    }

    @Test fun readyRequiresExactLivePhysicalTargetAndMatchingSinkRoute() {
        val f = Fixture(this)
        try {
            f.publish(this)
            val completion = f.owner.awaitReady(f.staged, f.ticket)
            f.presentation.emit(f.destination)
            val ready = completion.getNow(null) as HostExternalOutputOwner.Outcome.Ready
            assertTrue(f.owner.isCurrent(ready.target))
            f.retirementScheduler.fireNext() // one-shot callback retirement
            assertEquals(1, f.presentation.subscriptionCloses)
            f.deadline.fireNext(evenIfCancelled = true) // intentionally late timeout
            assertTrue(completion.getNow(null) is HostExternalOutputOwner.Outcome.Ready)
            f.trust!!.invalidate()
            assertFalse(f.owner.isCurrent(ready.target))
        } finally {
            f.retire()
        }
    }

    @Test fun wrongFactoryForSameSinkCannotBorrowAnotherFactorysPhysicalRoute() {
        val f = Fixture(this)
        try {
            f.publish(this)
            val otherFactory = AndroidMediaSinkExternalClusterRouteFactory(f.sink, f.registry)
            val otherOwner = HostExternalOutputOwner(
                f.sink, f.registry, otherFactory, f.presentation,
                f.deadline, Executor { it.run() }, f.retirement,
            )
            val completion = otherOwner.awaitReady(f.staged, f.ticket)
            f.presentation.emit(f.destination)
            val unavailable = completion.getNow(null) as HostExternalOutputOwner.Outcome.Unavailable
            assertEquals(HostExternalOutputOwner.Reason.WRONG_SINK_ROUTE, unavailable.reason)
            assertTrue(f.retirement.pendingCount() >= 1) // exact owner retained for close
        } finally {
            f.retire()
        }
    }

    @Test fun foreignSinkOrRegistryFactoryIsRejectedBeforeSubscription() {
        val f = Fixture(this)
        val foreignSink = sink()
        try {
            val foreign = AndroidMediaSinkExternalClusterRouteFactory(foreignSink, f.registry)
            assertThrows(IllegalArgumentException::class.java) {
                HostExternalOutputOwner(
                    f.sink, f.registry, foreign, f.presentation,
                    f.deadline, Executor { it.run() }, f.retirement,
                )
            }
            assertEquals(null, f.presentation.callback)
        } finally {
            foreignSink.close()
            f.retire()
        }
    }

    @Test fun timedOutWaitIgnoresLateReadyAndRetiresPresentation() {
        val f = Fixture(this)
        try {
            val completion = f.owner.awaitReady(f.staged, f.ticket)
            val lateCallback = requireNotNull(f.presentation.callback)
            f.deadline.fireNext()
            val unavailable = completion.getNow(null) as HostExternalOutputOwner.Outcome.Unavailable
            assertEquals(HostExternalOutputOwner.Reason.TIMED_OUT, unavailable.reason)
            lateCallback(f.destination)
            assertEquals(unavailable, completion.getNow(null))
            f.retirementScheduler.fireNext()
            assertEquals(0, f.retirement.pendingCount())
            assertEquals(1, f.presentation.closeAttempts)
            assertEquals(1, f.presentation.subscriptionCloses)
            assertFalse(f.presentation.callbackPresentAtClose)
        } finally {
            f.retire()
        }
    }

    @Test fun withdrawnProofAndCancelledTicketDoNotReportReady() {
        val withdrawn = Fixture(this)
        try {
            withdrawn.publish(this)
            val completion = withdrawn.owner.awaitReady(withdrawn.staged, withdrawn.ticket)
            withdrawn.trust!!.invalidate()
            withdrawn.presentation.emit(withdrawn.destination)
            assertEquals(HostExternalOutputOwner.Reason.WITHDRAWN,
                (completion.getNow(null) as HostExternalOutputOwner.Outcome.Unavailable).reason)
        } finally {
            withdrawn.retire()
        }

        val cancelled = Fixture(this)
        try {
            val completion = cancelled.owner.awaitReady(cancelled.staged, cancelled.ticket)
            cancelled.staged.cancel()
            cancelled.presentation.emit(cancelled.destination)
            assertEquals(HostExternalOutputOwner.Reason.CANCELLED,
                (completion.getNow(null) as HostExternalOutputOwner.Outcome.Unavailable).reason)
        } finally {
            cancelled.retire()
        }
    }

    @Test fun failedOwnerCloseStaysInProcessRetryQueueUntilExactRetrySucceeds() {
        val f = Fixture(this)
        try {
            f.presentation.failCloses = 1
            assertThrows(IllegalStateException::class.java) {
                f.owner.closeAndRequireRetired()
            }
            assertFalse(f.owner.retirementReceipt().isDone)
            assertTrue(HostExternalOutputOwner.hasPendingProcessRetirement())
            assertEquals(1, f.retirement.pendingCount())
            f.retirementScheduler.fireNext()
            assertEquals(1, f.presentation.closeAttempts)
            assertEquals(1, f.retirement.pendingCount())
            f.retirementScheduler.fireNext()
            assertEquals(2, f.presentation.closeAttempts)
            assertEquals(0, f.retirement.pendingCount())
            assertTrue(f.owner.retirementReceipt().isDone)
            f.owner.closeAndRequireRetired() // retryable sink onClosed callback can now succeed
            assertFalse(HostExternalOutputOwner.hasPendingProcessRetirement())
        } finally {
            f.retire()
        }
    }

    @Test fun closingReadyOwnerImmediatelyInvalidatesProofBeforeAsyncPresentationClose() {
        val f = Fixture(this)
        try {
            f.publish(this)
            val completion = f.owner.awaitReady(f.staged, f.ticket)
            f.presentation.emit(f.destination)
            val target = (completion.getNow(null) as HostExternalOutputOwner.Outcome.Ready).target
            assertTrue(f.owner.isCurrent(target))
            f.owner.close()
            assertFalse(f.owner.isCurrent(target))
            assertTrue(HostExternalOutputOwner.hasPendingProcessRetirement())
            assertFalse(f.owner.retirementReceipt().isDone)
            f.retirementScheduler.fireNext()
            assertTrue(f.owner.retirementReceipt().isDone)
            assertFalse(HostExternalOutputOwner.hasPendingProcessRetirement())
            assertFalse(f.presentation.callbackPresentAtClose)
        } finally {
            f.retire()
        }
    }

    @Test fun synchronousStaleReadyClosesLateSubscriptionBeforePresentation() {
        val f = Fixture(this)
        try {
            f.presentation.synchronousReplay = f.destination
            val completion = f.owner.awaitReady(f.staged, f.ticket)
            assertEquals(HostExternalOutputOwner.Reason.WITHDRAWN,
                (completion.getNow(null) as HostExternalOutputOwner.Outcome.Unavailable).reason)
            f.retirementScheduler.fireNext()
            assertEquals(1, f.presentation.subscriptionCloses)
            assertEquals(1, f.presentation.closeAttempts)
            assertFalse(f.presentation.callbackPresentAtClose)
        } finally {
            f.retire()
        }
    }
}
