package com.shilapi.xcertplay.ultra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DisplaySurfaceRouterTest {
    @Test fun replacingRouteInvalidatesOldLeaseAndStaleDestroyCannotClearNewTarget() {
        val router = DisplaySurfaceRouter()
        val legacy = FakeSurfaceTarget("legacy")
        val composed = FakeSurfaceTarget("composed")

        val oldLease = router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.LEGACY_REMOTE,
            legacy,
        )
        val newLease = router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.COMPOSITED,
            composed,
        )

        assertFalse(oldLease.isCurrent)
        assertTrue(newLease.isCurrent)
        assertEquals(1, legacy.closeCount)
        oldLease.close() // A late surfaceDestroyed callback for the legacy surface.
        assertTrue(newLease.isCurrent)
        assertEquals(DisplayStreamPath.COMPOSITED, router.snapshot().routes.single().streamPath)
        assertEquals(DisplaySurfaceId("composed"), router.snapshot().routes.single().surface.id)
        assertEquals(0, composed.closeCount)

        newLease.close()
        assertEquals(1, composed.closeCount)
        assertTrue(router.snapshot().routes.isEmpty())
    }

    @Test fun snapshotsAreDefensiveStableAndDeterministicallyOrdered() {
        val router = DisplaySurfaceRouter()
        router.attach(
            DisplayDestination.CLUSTER,
            DisplayStreamPath.COMPOSITED,
            FakeSurfaceTarget("cluster"),
        )
        router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.LEGACY_REMOTE,
            FakeSurfaceTarget("main"),
        )

        val before = router.snapshot()
        assertEquals(listOf(DisplayDestination.MAIN, DisplayDestination.CLUSTER), before.routes.map { it.destination })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (before.routes as MutableList<DisplaySurfaceRouteSnapshot>).clear()
        }

        router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.COMPOSITED,
            FakeSurfaceTarget("replacement"),
        )
        assertEquals(DisplaySurfaceId("main"), before.routes.first().surface.id)
        assertEquals(DisplaySurfaceId("replacement"), router.snapshot().routes.first().surface.id)
    }

    @Test fun closeReleasesEveryCurrentTargetExactlyOnceAndRejectsFutureAttach() {
        val router = DisplaySurfaceRouter()
        val main = FakeSurfaceTarget("main")
        val cluster = FakeSurfaceTarget("cluster")
        router.attach(DisplayDestination.MAIN, DisplayStreamPath.COMPOSITED, main)
        router.attach(DisplayDestination.CLUSTER, DisplayStreamPath.COMPOSITED, cluster)

        router.close()
        router.close()

        assertEquals(1, main.closeCount)
        assertEquals(1, cluster.closeCount)
        assertTrue(router.snapshot().closed)
        assertTrue(router.snapshot().routes.isEmpty())
        val rejected = FakeSurfaceTarget("rejected")
        assertThrows(IllegalStateException::class.java) {
            router.attach(DisplayDestination.MAIN, DisplayStreamPath.LEGACY_REMOTE, rejected)
        }
        assertEquals("Ownership transfers only after a successful attach", 0, rejected.closeCount)
    }

    @Test fun sameTargetCanChangePathWithoutBeingPrematurelyClosed() {
        val router = DisplaySurfaceRouter()
        val target = FakeSurfaceTarget("shared")
        val oldLease = router.attach(DisplayDestination.MAIN, DisplayStreamPath.LEGACY_REMOTE, target)
        val newLease = router.attach(DisplayDestination.MAIN, DisplayStreamPath.COMPOSITED, target)

        assertEquals(0, target.closeCount)
        oldLease.close()
        assertTrue(newLease.isCurrent)
        assertEquals(0, target.closeCount)
        newLease.close()
        assertEquals(1, target.closeCount)
    }

    @Test fun legacyRoutesRejectDistinctTargetsSharingOneOwnershipGuard() {
        val router = DisplaySurfaceRouter()
        val sharedGuard = DisplaySurfaceOwnership()
        val first = SharedGuardLegacyTarget("first", sharedGuard)
        val second = SharedGuardLegacyTarget("second", sharedGuard)
        val old = router.attach(DisplayDestination.MAIN, DisplayStreamPath.LEGACY_REMOTE, first)
        val before = router.snapshot()

        assertThrows(IllegalArgumentException::class.java) {
            router.attach(DisplayDestination.CLUSTER, DisplayStreamPath.COMPOSITED, second)
        }

        assertTrue(old.isCurrent)
        assertEquals(before.routes, router.snapshot().routes)
        assertEquals(before.revision, router.snapshot().revision)
        assertEquals(0, second.closeCount)
        assertThrows(IllegalStateException::class.java) { sharedGuard.claim(Any()) }
        old.close()
        assertEquals(1, first.closeCount)
        val probe = Any()
        assertTrue(sharedGuard.claim(probe))
        sharedGuard.release(probe)
    }

    @Test fun targetGettersMayReenterWithoutPublishingIntoClosedStateOrCorruptingSnapshot() {
        val closedByGetter = DisplaySurfaceRouter()
        val closingTarget = ReentrantSurfaceTarget("closing")
        closingTarget.onDescriptor = { closedByGetter.close() }
        assertThrows(IllegalStateException::class.java) {
            closedByGetter.attach(
                DisplayDestination.MAIN,
                DisplayStreamPath.LEGACY_REMOTE,
                closingTarget,
            )
        }
        assertTrue(closedByGetter.snapshot().closed)
        assertTrue(closedByGetter.snapshot().routes.isEmpty())

        val router = DisplaySurfaceRouter()
        val main = ReentrantSurfaceTarget("main")
        router.attach(DisplayDestination.MAIN, DisplayStreamPath.COMPOSITED, main)
        router.attach(
            DisplayDestination.CLUSTER,
            DisplayStreamPath.COMPOSITED,
            FakeSurfaceTarget("cluster-before"),
        )
        var validityReads = 0
        main.onValidity = {
            validityReads += 1
            if (validityReads == 1) {
                router.attach(
                    DisplayDestination.CLUSTER,
                    DisplayStreamPath.COMPOSITED,
                    FakeSurfaceTarget("cluster-after"),
                )
            }
        }

        val copied = router.snapshot()
        assertEquals(2, copied.routes.size)
        assertEquals(
            DisplaySurfaceId("cluster-before"),
            copied.routes.single { it.destination == DisplayDestination.CLUSTER }.surface.id,
        )
        assertEquals(
            DisplaySurfaceId("cluster-after"),
            router.snapshot().routes.single { it.destination == DisplayDestination.CLUSTER }.surface.id,
        )
    }

    @Test fun replacementCleanupFailureDoesNotHideNewLeaseAndIsRetriedOnClose() {
        val router = DisplaySurfaceRouter()
        val failing = FailOnceSurfaceTarget("fail-once")
        val oldLease = router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.LEGACY_REMOTE,
            failing,
        )
        val replacement = FakeSurfaceTarget("replacement")

        val newLease = router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.COMPOSITED,
            replacement,
        )

        assertFalse(oldLease.isCurrent)
        assertTrue(newLease.isCurrent)
        assertEquals(1, failing.closeAttempts)
        assertEquals(1, router.snapshot().cleanupFailureCount)
        assertEquals(1, router.snapshot().retiredTargetCount)

        router.close()
        assertEquals(2, failing.closeAttempts)
        assertEquals(1, replacement.closeCount)
        assertEquals(0, router.snapshot().retiredTargetCount)
    }

    @Test fun sixtyFifthRouteIsRejectedBeforeTargetProbeAndLeavesStateUnchanged() {
        val router = DisplaySurfaceRouter()
        val destinations = (0 until MAXIMUM_ACTIVE_DISPLAY_SURFACES).map { index ->
            DisplayDestination(
                DisplayDestinationRole.AUXILIARY,
                DisplayDestinationId("route-$index"),
            )
        }
        destinations.forEachIndexed { index, destination ->
            router.attach(
                destination,
                DisplayStreamPath.COMPOSITED,
                FakeSurfaceTarget("surface-$index"),
            )
        }
        val before = router.snapshot()
        val rejected = TrackingSurfaceTarget("rejected")

        assertThrows(IllegalArgumentException::class.java) {
            router.attach(
                DisplayDestination(
                    DisplayDestinationRole.AUXILIARY,
                    DisplayDestinationId("route-overflow"),
                ),
                DisplayStreamPath.COMPOSITED,
                rejected,
            )
        }

        val after = router.snapshot()
        assertEquals(before.revision, after.revision)
        assertEquals(before.routes, after.routes)
        assertEquals(0, rejected.descriptorReads)
        assertEquals(0, rejected.ownershipReads)
        assertEquals(0, rejected.validityReads)
        assertEquals(0, rejected.closeCount)

        val replacement = router.attach(
            destinations.first(),
            DisplayStreamPath.COMPOSITED,
            rejected,
        )
        assertTrue(replacement.isCurrent)
        assertEquals(MAXIMUM_ACTIVE_DISPLAY_SURFACES, router.snapshot().routes.size)
        router.close()
        assertEquals(1, rejected.closeCount)
    }

    @Test fun completeCompositedBatchPublishesOneGenerationAndDefersOldViewCleanup() {
        val router = DisplaySurfaceRouter()
        val oldSource = FakeSurfaceTarget("old")
        val oldView = oldSource.borrowForRouting()
        val old = router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.MAIN, oldView)),
        )
        val mainSource = FakeSurfaceTarget("main")
        val clusterSource = FakeSurfaceTarget("cluster")
        val mainView = mainSource.borrowForRouting()
        val clusterView = clusterSource.borrowForRouting()

        val current = router.replaceCompositedBatch(
            listOf(
                CompositedDisplayRoute(DisplayDestination.CLUSTER, clusterView),
                CompositedDisplayRoute(DisplayDestination.MAIN, mainView),
            ),
        )

        assertFalse(old.isCurrent)
        assertTrue(current.isCurrent)
        assertTrue(oldView.isValid)
        assertEquals(0, oldSource.closeCount)
        assertEquals(1, router.snapshot().retiredTargetCount)
        assertEquals(
            listOf(DisplayDestination.MAIN, DisplayDestination.CLUSTER),
            router.snapshot().routes.map { it.destination },
        )
        assertEquals(listOf(current.generation, current.generation),
            router.snapshot().routes.map { it.generation })
        assertTrue(router.snapshot().routes.all { it.streamPath == DisplayStreamPath.COMPOSITED })

        old.close() // Called only after the upstream EGL/GPU fence.
        assertFalse(oldView.isValid)
        assertTrue(current.isCurrent)
        assertEquals(0, router.snapshot().retiredTargetCount)
        assertEquals(0, oldSource.closeCount) // A borrowed route never owns its source.
        current.close()
        assertTrue(router.snapshot().routes.isEmpty())
        assertFalse(mainView.isValid)
        assertFalse(clusterView.isValid)
        assertEquals(0, mainSource.closeCount)
        assertEquals(0, clusterSource.closeCount)
    }

    @Test fun provenUpstreamRetirementSweepsBatchViewsEvenWhenTheirLeaseWasLost() {
        val router = DisplaySurfaceRouter()
        val oldSource = FakeSurfaceTarget("proof-old")
        val currentSource = FakeSurfaceTarget("proof-current")
        val oldView = oldSource.borrowForRouting()
        val currentView = currentSource.borrowForRouting()
        router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.MAIN, oldView)),
        ) // Deliberately discard the returned lease, as in an uncertain publication.
        router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.MAIN, currentView)),
        )

        assertThrows(IllegalStateException::class.java) { oldView.ownership.claim(Any()) }
        assertThrows(IllegalStateException::class.java) { currentView.ownership.claim(Any()) }
        router.close() // No upstream proof: batch views and guards are deliberately retained.
        assertTrue(oldView.isValid)
        assertTrue(currentView.isValid)

        router.retireAllCompositedBatchesAfterUpstreamProof()
        router.retireAllCompositedBatchesAfterUpstreamProof()

        assertFalse(oldView.isValid)
        assertFalse(currentView.isValid)
        listOf(oldView, currentView).forEach { view ->
            val owner = Any()
            assertTrue(view.ownership.claim(owner))
            view.ownership.release(owner)
        }
        assertEquals(0, oldSource.closeCount)
        assertEquals(0, currentSource.closeCount)
        assertEquals(0, router.snapshot().retiredTargetCount)
    }

    @Test fun failedSecondTargetClaimPreservesOldGenerationAndReleasesFirstNewClaim() {
        val router = DisplaySurfaceRouter()
        val oldView = FakeSurfaceTarget("old").borrowForRouting()
        val old = router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.MAIN, oldView)),
        )
        val before = router.snapshot()
        val firstNew = FakeSurfaceTarget("first-new").borrowForRouting()
        val blocked = FakeSurfaceTarget("blocked").borrowForRouting()
        val other = DisplaySurfaceRouter()
        other.attach(DisplayDestination.HUD, DisplayStreamPath.COMPOSITED, blocked)

        assertThrows(IllegalStateException::class.java) {
            router.replaceCompositedBatch(
                listOf(
                    CompositedDisplayRoute(DisplayDestination.MAIN, firstNew),
                    CompositedDisplayRoute(DisplayDestination.CLUSTER, blocked),
                ),
            )
        }

        assertTrue(old.isCurrent)
        assertEquals(before.revision, router.snapshot().revision)
        assertEquals(before.routes, router.snapshot().routes)
        assertTrue(firstNew.isValid)
        val probe = DisplaySurfaceRouter()
        probe.attach(DisplayDestination.MAIN, DisplayStreamPath.COMPOSITED, firstNew)
        probe.close()
        assertFalse(firstNew.isValid)
        other.close()
        old.close()
    }

    @Test fun batchValidationRejectsDuplicateIdentityAndLegacyMixWithoutMutatingState() {
        val router = DisplaySurfaceRouter()
        val source = FakeSurfaceTarget("same-source")
        val first = source.borrowForRouting()
        val second = source.borrowForRouting()
        assertThrows(IllegalArgumentException::class.java) {
            router.replaceCompositedBatch(
                listOf(
                    CompositedDisplayRoute(DisplayDestination.MAIN, first),
                    CompositedDisplayRoute(DisplayDestination.CLUSTER, second),
                ),
            )
        }
        assertTrue(router.snapshot().routes.isEmpty())
        assertTrue(first.isValid)
        assertTrue(second.isValid)

        val legacy = router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.LEGACY_REMOTE,
            FakeSurfaceTarget("legacy"),
        )
        assertThrows(IllegalStateException::class.java) {
            router.replaceCompositedBatch(
                listOf(CompositedDisplayRoute(DisplayDestination.CLUSTER, first)),
            )
        }
        legacy.close()
        val batch = router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.CLUSTER, first)),
        )
        assertThrows(IllegalStateException::class.java) {
            router.attach(
                DisplayDestination.MAIN,
                DisplayStreamPath.LEGACY_REMOTE,
                FakeSurfaceTarget("not-legacy-again"),
            )
        }
        batch.close()
    }

    @Test fun failedStaleBatchCloseIsRetryableAndCannotDetachNewGeneration() {
        val router = DisplaySurfaceRouter()
        val oldSource = FakeSurfaceTarget("old")
        val oldView = FailOnceBorrowedSurfaceTarget(oldSource)
        val old = router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.MAIN, oldView)),
        )
        val new = router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(
                DisplayDestination.MAIN,
                FakeSurfaceTarget("new").borrowForRouting(),
            )),
        )

        assertThrows(IllegalStateException::class.java) { old.close() }
        assertEquals(1, oldView.closeAttempts)
        assertEquals(1, router.snapshot().retiredTargetCount)
        assertEquals(1, router.snapshot().cleanupFailureCount)
        assertTrue(new.isCurrent)

        old.close()
        old.close()
        assertEquals(2, oldView.closeAttempts)
        assertEquals(0, router.snapshot().retiredTargetCount)
        assertTrue(new.isCurrent)
        assertEquals(DisplaySurfaceId("new"), router.snapshot().routes.single().surface.id)
        new.close()
    }

    @Test fun routerCloseLeavesBatchViewsForTheirFenceBoundLeases() {
        val router = DisplaySurfaceRouter()
        val view = FakeSurfaceTarget("batch").borrowForRouting()
        val lease = router.replaceCompositedBatch(
            listOf(CompositedDisplayRoute(DisplayDestination.MAIN, view)),
        )

        router.close()
        assertTrue(view.isValid)
        assertFalse(lease.isCurrent)
        assertEquals(1, router.snapshot().retiredTargetCount)
        lease.close()
        assertFalse(view.isValid)
        assertEquals(0, router.snapshot().retiredTargetCount)
    }

    @Test fun batchRejectsSixtyFifthRouteBeforeProbingAnyTarget() {
        val router = DisplaySurfaceRouter()
        val tracked = TrackingSurfaceTarget("tracked")
        val trackedView = tracked.borrowForRouting()
        val validityReadsBefore = tracked.validityReads
        val requested = (0 until MAXIMUM_ACTIVE_DISPLAY_SURFACES + 1).map { index ->
            CompositedDisplayRoute(
                DisplayDestination(DisplayDestinationRole.AUXILIARY, DisplayDestinationId("batch-$index")),
                if (index == 0) trackedView else FakeSurfaceTarget("batch-$index").borrowForRouting(),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            router.replaceCompositedBatch(requested)
        }
        assertEquals(validityReadsBefore, tracked.validityReads)
        assertTrue(router.snapshot().routes.isEmpty())
        assertTrue(trackedView.isValid)
    }

    @Test fun batchRejectsSharedOwnershipIdentityBeforeClaimingEitherView() {
        val router = DisplaySurfaceRouter()
        val sharedOwnership = DisplaySurfaceOwnership()
        val first = SharedOwnershipBorrowedSurfaceTarget(FakeSurfaceTarget("first"), sharedOwnership)
        val second = SharedOwnershipBorrowedSurfaceTarget(FakeSurfaceTarget("second"), sharedOwnership)

        assertThrows(IllegalArgumentException::class.java) {
            router.replaceCompositedBatch(
                listOf(
                    CompositedDisplayRoute(DisplayDestination.MAIN, first),
                    CompositedDisplayRoute(DisplayDestination.CLUSTER, second),
                ),
            )
        }
        val testOwner = Any()
        assertTrue(sharedOwnership.claim(testOwner))
        sharedOwnership.release(testOwner)
        assertTrue(router.snapshot().routes.isEmpty())
    }

    @Test fun batchProbeAndCleanupCallbacksCanReadRouterFromAnotherThread() {
        val router = DisplaySurfaceRouter()
        val callbackWorker = Executors.newSingleThreadExecutor()
        try {
            val old = ReentrantCloseBorrowedSurfaceTarget(FakeSurfaceTarget("old")) {
                callbackWorker.submit<Long> { router.snapshot().revision }.get(2, TimeUnit.SECONDS)
            }
            val oldLease = router.replaceCompositedBatch(
                listOf(CompositedDisplayRoute(DisplayDestination.MAIN, old)),
            )
            val source = ReentrantSurfaceTarget("new")
            source.onValidity = {
                callbackWorker.submit<Long> { router.snapshot().revision }.get(2, TimeUnit.SECONDS)
            }
            val newLease = router.replaceCompositedBatch(
                listOf(CompositedDisplayRoute(DisplayDestination.MAIN, source.borrowForRouting())),
            )
            source.onValidity = {}
            oldLease.close()
            assertTrue(newLease.isCurrent)
            newLease.close()
        } finally {
            callbackWorker.shutdownNow()
        }
    }
}

private class SharedGuardLegacyTarget(
    id: String,
    override val ownership: DisplaySurfaceOwnership,
) : DisplaySurfaceTarget {
    override val descriptor = DisplaySurfaceDescriptor(DisplaySurfaceId(id), PixelSize(800, 480))
    override val isValid = true
    var closeCount = 0
        private set

    override fun close() {
        closeCount++
    }
}

private class FailOnceBorrowedSurfaceTarget(source: DisplaySurfaceTarget) :
    BorrowedDisplaySurfaceTarget(source, source.descriptor) {
    var closeAttempts = 0
        private set

    override fun close() {
        closeAttempts += 1
        if (closeAttempts == 1) throw IllegalStateException("synthetic batch close failure")
        super.close()
    }
}

private class SharedOwnershipBorrowedSurfaceTarget(
    source: DisplaySurfaceTarget,
    override val ownership: DisplaySurfaceOwnership,
) : BorrowedDisplaySurfaceTarget(source, source.descriptor)

private class ReentrantCloseBorrowedSurfaceTarget(
    source: DisplaySurfaceTarget,
    private val onClose: () -> Unit,
) : BorrowedDisplaySurfaceTarget(source, source.descriptor) {
    override fun close() {
        onClose()
        super.close()
    }
}

internal class FakeSurfaceTarget(
    id: String,
    widthPixels: Int = 1920,
    heightPixels: Int = 720,
) : DisplaySurfaceTarget {
    override val descriptor = DisplaySurfaceDescriptor(
        DisplaySurfaceId(id),
        PixelSize(widthPixels, heightPixels),
    )
    override val ownership = DisplaySurfaceOwnership()
    var closeCount = 0
        private set
    private var open = true

    override val isValid: Boolean
        get() = open

    override fun close() {
        if (open) {
            open = false
            closeCount += 1
        }
    }
}

private class ReentrantSurfaceTarget(id: String) : DisplaySurfaceTarget {
    private val frozenDescriptor = DisplaySurfaceDescriptor(
        DisplaySurfaceId(id),
        PixelSize(1920, 720),
    )
    override val ownership = DisplaySurfaceOwnership()
    var onDescriptor: () -> Unit = {}
    var onValidity: () -> Unit = {}

    override val descriptor: DisplaySurfaceDescriptor
        get() {
            onDescriptor()
            return frozenDescriptor
        }

    override val isValid: Boolean
        get() {
            onValidity()
            return true
        }

    override fun close() = Unit
}

internal class FailOnceSurfaceTarget(id: String) : DisplaySurfaceTarget {
    override val descriptor = DisplaySurfaceDescriptor(
        DisplaySurfaceId(id),
        PixelSize(1920, 720),
    )
    override val ownership = DisplaySurfaceOwnership()
    var closeAttempts = 0
        private set
    private var closed = false

    override val isValid: Boolean
        get() = !closed

    override fun close() {
        closeAttempts += 1
        if (closeAttempts == 1) throw IllegalStateException("synthetic close failure")
        closed = true
    }
}

internal class TrackingSurfaceTarget(id: String) : DisplaySurfaceTarget {
    private val stableDescriptor = DisplaySurfaceDescriptor(
        DisplaySurfaceId(id),
        PixelSize(1920, 720),
    )
    private val stableOwnership = DisplaySurfaceOwnership()
    var descriptorReads = 0
        private set
    var ownershipReads = 0
        private set
    var validityReads = 0
        private set
    var closeCount = 0
        private set
    private var open = true

    override val descriptor: DisplaySurfaceDescriptor
        get() {
            descriptorReads += 1
            return stableDescriptor
        }

    override val ownership: DisplaySurfaceOwnership
        get() {
            ownershipReads += 1
            return stableOwnership
        }

    override val isValid: Boolean
        get() {
            validityReads += 1
            return open
        }

    override fun close() {
        if (open) {
            open = false
            closeCount += 1
        }
    }
}
