package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.DisplayDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sun.misc.Unsafe

class HostTeardownCoordinatorTest {
    @Test
    fun activityFailureSurvivesRecreationAndTheNextActivityCanRetryIt() {
        val coordinator = HostTeardownCoordinator()
        val controller = Any()
        val sink = Any()
        var sinkAttempts = 0
        coordinator.submit(
            actions(
                controller = controller,
                sink = sink,
                await = { true },
                closeSink = {
                    sinkAttempts++
                    if (sinkAttempts == 1) throw IllegalStateException("retirement uncertain")
                },
            ),
        )

        // Activity A owns the first claim, then fails and can be destroyed without owning state.
        val first = drainHostTeardowns(coordinator)
        assertEquals(
            HostTeardownStage.MEDIA_SINK_CLOSE,
            (first as HostTeardownDrainResult.Failed).outcome.stage,
        )
        assertEquals(1, coordinator.pendingCount())

        // Activity B must not start while the process barrier remains populated.
        assertFalse(coordinator.state() == HostTeardownBarrierState.CLEAR)
        val oldActivitySurface = Any()
        var oldActivitySurfaceReleased = false
        assertTrue(
            coordinator.deferReleaseWhilePending(oldActivitySurface) {
                oldActivitySurfaceReleased = true
            },
        )
        assertFalse(oldActivitySurfaceReleased)

        // B claims the exact same process-owned record. Success is the only removal path.
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(2, sinkAttempts)
        assertTrue(oldActivitySurfaceReleased)
        assertEquals(0, coordinator.pendingCount())
        assertTrue(coordinator.state() == HostTeardownBarrierState.CLEAR)
    }

    @Test
    fun aSecondActivityWaitsForAnInflightOwnerThenObservesItsCleanCompletion() {
        val coordinator = HostTeardownCoordinator()
        coordinator.submit(actions())

        val activityA = (coordinator.claim() as HostTeardownClaimResult.Claimed).claim
        assertSame(HostTeardownClaimResult.InFlight, coordinator.claim())
        assertEquals(HostTeardownBarrierState.IN_FLIGHT, coordinator.state())

        assertSame(HostTeardownOutcome.Clean, coordinator.execute(activityA))
        assertEquals(HostTeardownBarrierState.CLEAR, coordinator.state())
        assertSame(HostTeardownClaimResult.Clear, coordinator.claim())
    }

    @Test
    fun attachFailureTimeoutRetainsSinkAndNeverClosesItBeforeControllerRetires() {
        val coordinator = HostTeardownCoordinator()
        var controllerRetired = false
        var sinkCloseCalls = 0
        coordinator.submit(
            actions(
                await = { controllerRetired },
                closeSink = { sinkCloseCalls++ },
            ),
        )

        val timeout = drainHostTeardowns(coordinator)
        assertEquals(
            HostTeardownStage.CONTROLLER_AWAIT,
            (timeout as HostTeardownDrainResult.Failed).outcome.stage,
        )
        assertEquals(0, sinkCloseCalls)
        assertEquals(1, coordinator.pendingCount())

        controllerRetired = true
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(1, sinkCloseCalls)
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun duplicateSubmissionDoesNotCreateASecondOwnerForTheSameResources() {
        val coordinator = HostTeardownCoordinator()
        val controller = Any()
        val sink = Any()
        assertTrue(coordinator.submit(actions(controller, sink)))
        assertFalse(coordinator.submit(actions(controller, sink)))
        assertEquals(1, coordinator.pendingCount())
    }

    @Test
    fun exactTicketWaitsForControllerSinkAndOldBorrowProofWithoutReclosingResources() {
        val coordinator = HostTeardownCoordinator()
        val controller = Any()
        val sink = Any()
        val oldMain = oldMainBorrow()
        var closeRequests = 0
        var awaits = 0
        var sinkCloses = 0
        val ticket = coordinator.submitTicket(
            HostTeardownActions(
                controllerIdentity = controller,
                sinkIdentity = sink,
                requestControllerClose = { closeRequests++ },
                awaitControllerClosed = { awaits++; true },
                closeMediaSink = { sinkCloses++ },
            ),
            oldMain.receipt,
        )

        assertFalse(oldMain.receipt.isRetired)
        assertFalse(ticket.isRetiredFor(controller, sink, oldMain.receipt))
        val first = drainHostTeardowns(coordinator) as HostTeardownDrainResult.Failed
        assertEquals(HostTeardownStage.DEFERRED_RESOURCE_RELEASE, first.outcome.stage)
        assertEquals(HostTeardownBarrierState.AVAILABLE, coordinator.state())
        assertEquals(1, coordinator.pendingCount())
        assertEquals(1, closeRequests)
        assertEquals(1, awaits)
        assertEquals(1, sinkCloses)
        assertFalse(ticket.isRetiredFor(controller, sink, oldMain.receipt))

        oldMain.borrow.retireAfterEglProof()
        assertTrue(oldMain.receipt.isRetired)
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(1, closeRequests)
        assertEquals(1, awaits)
        assertEquals(1, sinkCloses)
        assertEquals(HostTeardownBarrierState.CLEAR, coordinator.state())
        val receipt = ticket.retirementReceiptFor(controller, sink, oldMain.receipt)
        assertTrue(receipt != null)
        assertTrue(receipt!!.matchesExact(controller, sink, oldMain.receipt))
    }

    @Test
    fun retiredOldBorrowAloneCannotSkipControllerAwaitOrSinkClose() {
        val coordinator = HostTeardownCoordinator()
        val controller = Any()
        val sink = Any()
        val oldMain = oldMainBorrow()
        oldMain.borrow.retireAfterEglProof()
        var controllerClosed = false
        var sinkCloses = 0
        var sinkFails = true
        val ticket = coordinator.submitTicket(
            actions(
                controller = controller,
                sink = sink,
                await = { controllerClosed },
                closeSink = {
                    sinkCloses++
                    if (sinkFails) throw IllegalStateException("sink still owns old route")
                },
            ),
            oldMain.receipt,
        )

        val awaiting = drainHostTeardowns(coordinator) as HostTeardownDrainResult.Failed
        assertEquals(HostTeardownStage.CONTROLLER_AWAIT, awaiting.outcome.stage)
        assertEquals(0, sinkCloses)
        assertFalse(ticket.isRetiredFor(controller, sink, oldMain.receipt))

        controllerClosed = true
        val sinkFailed = drainHostTeardowns(coordinator) as HostTeardownDrainResult.Failed
        assertEquals(HostTeardownStage.MEDIA_SINK_CLOSE, sinkFailed.outcome.stage)
        assertEquals(1, sinkCloses)
        assertFalse(ticket.isRetiredFor(controller, sink, oldMain.receipt))

        sinkFails = false
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(2, sinkCloses)
        assertTrue(ticket.isRetiredFor(controller, sink, oldMain.receipt))
    }

    @Test
    fun ticketReceiptRequiresExactControllerSinkAndCapturedMainBorrow() {
        val coordinator = HostTeardownCoordinator()
        val controller = Any()
        val sink = Any()
        val oldMain = oldMainBorrow()
        val otherMain = oldMainBorrow()
        val ticket = coordinator.submitTicket(
            actions(controller, sink),
            oldMain.receipt,
        )
        oldMain.borrow.retireAfterEglProof()
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(HostTeardownBarrierState.CLEAR, coordinator.state())

        assertTrue(ticket.isRetiredFor(controller, sink, oldMain.receipt))
        assertFalse(ticket.isRetiredFor(Any(), sink, oldMain.receipt))
        assertFalse(ticket.isRetiredFor(controller, Any(), oldMain.receipt))
        assertFalse(ticket.isRetiredFor(controller, sink, otherMain.receipt))

        val receipt = ticket.retirementReceiptFor(controller, sink, oldMain.receipt)!!
        assertFalse(receipt.matchesExact(Any(), sink, oldMain.receipt))
        assertFalse(receipt.matchesExact(controller, sink, otherMain.receipt))
        assertFalse(
            HostTeardownRetirementReceipt(ticket).matchesExact(
                controller, sink, oldMain.receipt,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { HostTeardownTicket(Any()) }
    }

    @Test
    fun aClearBarrierDoesNotRetireAnInflightTicketOrOneWithAnActiveBorrow() {
        val clearCoordinator = HostTeardownCoordinator()
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(clearCoordinator))

        val coordinator = HostTeardownCoordinator()
        val controller = Any()
        val sink = Any()
        val oldMain = oldMainBorrow()
        val ticket = coordinator.submitTicket(
            actions(controller, sink),
            oldMain.receipt,
        )
        val claim = (coordinator.claim() as HostTeardownClaimResult.Claimed).claim
        assertFalse(ticket.isRetiredFor(controller, sink, oldMain.receipt))
        val first = coordinator.execute(claim) as HostTeardownOutcome.Failed
        assertEquals(HostTeardownStage.DEFERRED_RESOURCE_RELEASE, first.stage)
        assertFalse(ticket.isRetiredFor(controller, sink, oldMain.receipt))
        oldMain.borrow.retireAfterEglProof()
        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertTrue(ticket.isRetiredFor(controller, sink, oldMain.receipt))
    }

    @Test
    fun aFailedDeferredReleaseRetainsItAndEveryLaterReleaseForExactRetry() {
        val coordinator = HostTeardownCoordinator()
        var firstAttempts = 0
        var secondAttempts = 0
        coordinator.submit(actions())
        assertTrue(
            coordinator.deferReleaseWhilePending(Any()) {
                firstAttempts++
                if (firstAttempts == 1) throw IllegalStateException("release failed")
            },
        )
        assertTrue(
            coordinator.deferReleaseWhilePending(Any()) { secondAttempts++ },
        )

        val failed = drainHostTeardowns(coordinator)
        assertEquals(
            HostTeardownStage.DEFERRED_RESOURCE_RELEASE,
            (failed as HostTeardownDrainResult.Failed).outcome.stage,
        )
        assertEquals(1, firstAttempts)
        assertEquals(0, secondAttempts)
        assertEquals(2, coordinator.pendingCount())
        assertEquals(HostTeardownBarrierState.AVAILABLE, coordinator.state())

        assertSame(HostTeardownDrainResult.Clear, drainHostTeardowns(coordinator))
        assertEquals(2, firstAttempts)
        assertEquals(1, secondAttempts)
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun anInflightDeferredReleaseKeepsTheBarrierClaimed() {
        val coordinator = HostTeardownCoordinator()
        coordinator.submit(actions())
        coordinator.deferReleaseWhilePending(Any()) {}
        val teardown = (coordinator.claim() as HostTeardownClaimResult.Claimed).claim
        assertSame(HostTeardownOutcome.Clean, coordinator.execute(teardown))
        val release = (coordinator.claim() as HostTeardownClaimResult.Claimed).claim

        assertEquals(HostTeardownWorkKind.DEFERRED_RELEASE, release.kind)
        assertEquals(HostTeardownBarrierState.IN_FLIGHT, coordinator.state())
        assertSame(HostTeardownClaimResult.InFlight, coordinator.claim())
    }

    @Test
    fun errorsLeaveResourceAndDeferredClaimsInflight() {
        val resourceCoordinator = HostTeardownCoordinator()
        resourceCoordinator.submit(
            actions(closeSink = { throw AssertionError("fatal resource close") }),
        )
        val resourceClaim =
            (resourceCoordinator.claim() as HostTeardownClaimResult.Claimed).claim
        assertError { resourceCoordinator.execute(resourceClaim) }
        assertEquals(HostTeardownBarrierState.IN_FLIGHT, resourceCoordinator.state())

        val releaseCoordinator = HostTeardownCoordinator()
        releaseCoordinator.submit(actions())
        releaseCoordinator.deferReleaseWhilePending(Any()) {
            throw AssertionError("fatal release")
        }
        val teardownClaim =
            (releaseCoordinator.claim() as HostTeardownClaimResult.Claimed).claim
        assertSame(HostTeardownOutcome.Clean, releaseCoordinator.execute(teardownClaim))
        val releaseClaim =
            (releaseCoordinator.claim() as HostTeardownClaimResult.Claimed).claim
        assertError { releaseCoordinator.execute(releaseClaim) }
        assertEquals(HostTeardownBarrierState.IN_FLIGHT, releaseCoordinator.state())
    }

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private class OldMainBorrow(
        val borrow: HostPhysicalDisplaySurfaceRegistry.BorrowedTargetLease,
        val receipt: HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    )

    private fun oldMainBorrow(): OldMainBorrow {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe
        val surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
        val texture = unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val version = registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface,
            texture,
            1280,
            720,
        )!!
        val borrow = registry.acquirePhysicalTarget(version)!!
        val receipt = registry.captureExactMainBorrowRetirement(version, surface)!!
        return OldMainBorrow(borrow, receipt)
    }

    private fun assertError(block: () -> Unit) {
        var thrown: AssertionError? = null
        try {
            block()
        } catch (error: AssertionError) {
            thrown = error
        }
        if (thrown == null) fail("Expected Error")
    }

    private fun actions(
        controller: Any = Any(),
        sink: Any = Any(),
        await: () -> Boolean = { true },
        closeSink: () -> Unit = {},
    ): HostTeardownActions = HostTeardownActions(
        controllerIdentity = controller,
        sinkIdentity = sink,
        requestControllerClose = {},
        awaitControllerClosed = await,
        closeMediaSink = closeSink,
    )
}
