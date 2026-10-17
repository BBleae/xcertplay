package com.shilapi.xcertplay.ultra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraCompositorTest {
    @Test fun singleIngressFansOutToMultipleOutputsWithStablePixelLayerOrder() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val ingress = compositor.acquireRemoteIngress()
        val cluster = FakeSurfaceTarget("cluster", 1920, 480)
        val main = FakeSurfaceTarget("main", 1920, 720)
        compositor.attachOutput(DisplayDestination.CLUSTER, cluster)
        compositor.attachOutput(DisplayDestination.MAIN, main)

        val callerLayers = mutableListOf(
            UltraRenderLayer(
                UltraLayerId("overlay"),
                UltraLayerSource.Overlay(UltraContentId("vehicle-speed")),
                UltraPixelRect(10, 20, 300, 80),
                clipPixels = UltraPixelRect(0, 0, 1920, 720),
                zIndex = 20,
                opacity = 0.75,
            ),
            UltraRenderLayer(
                UltraLayerId("remote"),
                UltraLayerSource.Remote,
                UltraPixelRect(0, 0, 1920, 720),
                zIndex = 0,
            ),
            UltraRenderLayer(
                UltraLayerId("local"),
                UltraLayerSource.Local(UltraContentId("oem-background")),
                UltraPixelRect(0, 0, 1920, 720),
                zIndex = 0,
            ),
        )
        val render = UltraRenderSnapshot(7, callerLayers)
        callerLayers.clear()
        compositor.submit(render)

        val transaction = backend.commits.last { it.kind == UltraCompositorTransactionKind.RENDER }
        assertEquals(listOf(1L, 2L, 3L, 4L), backend.commits.map { it.order })
        assertEquals(7L, transaction.render?.revision)
        assertSame(ingress.token, transaction.remoteIngressToken)
        assertEquals(
            listOf(DisplayDestination.MAIN, DisplayDestination.CLUSTER),
            transaction.outputTokens.map { it.destination },
        )
        assertEquals(
            listOf("remote", "local", "overlay"),
            transaction.render?.layers?.map { it.id.value },
        )
        assertEquals(
            listOf(UltraLayerType.REMOTE, UltraLayerType.LOCAL, UltraLayerType.OVERLAY),
            transaction.render?.layers?.map(UltraRenderLayer::type),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (transaction.render!!.layers as MutableList<UltraRenderLayer>).clear()
        }
    }

    @Test fun hotSwapMakesOldLeasesHarmlessAndNextRevisionUsesOnlyNewBindings() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val oldIngress = compositor.acquireRemoteIngress()
        val oldMain = FakeSurfaceTarget("old-main")
        val oldOutputLease = compositor.attachOutput(DisplayDestination.MAIN, oldMain)

        val newIngress = compositor.acquireRemoteIngress()
        val newMain = FakeSurfaceTarget("new-main")
        val newOutputLease = compositor.attachOutput(DisplayDestination.MAIN, newMain)
        assertEquals(1, (oldIngress.target as FakeSurfaceTarget).closeCount)
        assertEquals(1, oldMain.closeCount)

        oldIngress.close()
        oldOutputLease.close()
        assertTrue(newIngress.isCurrent)
        assertTrue(newOutputLease.isCurrent)
        assertEquals(0, (newIngress.target as FakeSurfaceTarget).closeCount)
        assertEquals(0, newMain.closeCount)

        compositor.submit(
            UltraRenderSnapshot(
                10,
                listOf(
                    UltraRenderLayer(
                        UltraLayerId("remote"),
                        UltraLayerSource.Remote,
                        UltraPixelRect(0, 0, 1920, 720),
                    ),
                    UltraRenderLayer(
                        UltraLayerId("hole"),
                        UltraLayerSource.PunchThrough,
                        UltraPixelRect(1400, 0, 520, 720),
                        zIndex = 100,
                    ),
                ),
            ),
        )
        val transaction = backend.commits.last { it.kind == UltraCompositorTransactionKind.RENDER }
        assertSame(newIngress.token, transaction.remoteIngressToken)
        assertSame(newOutputLease.token, transaction.outputTokens.single())
        assertEquals(UltraLayerType.PUNCH_THROUGH, transaction.render?.layers?.last()?.type)
    }

    @Test fun renderRevisionsAreStrictlyIncreasingAndRemoteLayersRequireIngress() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        compositor.attachOutput(DisplayDestination.MAIN, FakeSurfaceTarget("main"))

        assertThrows(IllegalArgumentException::class.java) {
            compositor.submit(
                UltraRenderSnapshot(
                    1,
                    listOf(
                        UltraRenderLayer(
                            UltraLayerId("remote"),
                            UltraLayerSource.Remote,
                            UltraPixelRect(0, 0, 1, 1),
                        ),
                    ),
                ),
            )
        }
        assertTrue(backend.commits.none { it.kind == UltraCompositorTransactionKind.RENDER })

        compositor.submit(UltraRenderSnapshot(1, emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            compositor.submit(UltraRenderSnapshot(1, emptyList()))
        }
        assertEquals(1, backend.commits.count { it.kind == UltraCompositorTransactionKind.RENDER })
    }

    @Test fun closeReleasesIngressOutputsAndBackendExactlyOnce() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val ingress = compositor.acquireRemoteIngress()
        val main = FakeSurfaceTarget("main")
        val cluster = FakeSurfaceTarget("cluster")
        val mainLease = compositor.attachOutput(DisplayDestination.MAIN, main)
        compositor.attachOutput(DisplayDestination.CLUSTER, cluster)

        compositor.close()
        compositor.close()
        ingress.close()
        mainLease.close()

        assertFalse(ingress.isCurrent)
        assertFalse(mainLease.isCurrent)
        assertEquals(1, (ingress.target as FakeSurfaceTarget).closeCount)
        assertEquals(1, main.closeCount)
        assertEquals(1, cluster.closeCount)
        assertEquals(1, backend.closeCount)
        assertThrows(IllegalStateException::class.java) {
            compositor.attachOutput(DisplayDestination.MAIN, FakeSurfaceTarget("late"))
        }
        assertThrows(IllegalStateException::class.java) {
            compositor.submit(UltraRenderSnapshot(99, emptyList()))
        }
    }

    @Test fun blockingRetirementFencePinsOldOutputAndDoesNotHoldStateLock() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val old = FakeSurfaceTarget("old")
        compositor.attachOutput(DisplayDestination.MAIN, old)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.commitFactory = { BlockingCommit(entered, release) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val replacement = FakeSurfaceTarget("replacement")
            val future = executor.submit<UltraCompositorOutputLease> {
                compositor.attachOutput(DisplayDestination.MAIN, replacement)
            }
            assertTrue("backend fence was not reached", entered.await(2, TimeUnit.SECONDS))
            assertEquals(0, old.closeCount)
            assertTrue(compositor.diagnostics().operationInFlight)
            assertThrows(IllegalStateException::class.java) {
                compositor.submit(UltraRenderSnapshot(50, emptyList()))
            }

            release.countDown()
            val newLease = future.get(2, TimeUnit.SECONDS)
            assertTrue(newLease.isCurrent)
            assertEquals(1, old.closeCount)
            compositor.close()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun backendReentryIsLockFreeAndNestedMutationFailsFast() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        var openReentered = false
        var commitReentered = false
        backend.openProvider = {
            compositor.diagnostics()
            assertThrows(IllegalStateException::class.java) {
                compositor.submit(UltraRenderSnapshot(1, emptyList()))
            }
            openReentered = true
            FakeSurfaceTarget("reentrant-ingress")
        }
        backend.commitFactory = {
            compositor.diagnostics()
            assertThrows(IllegalStateException::class.java) {
                compositor.submit(UltraRenderSnapshot(2, emptyList()))
            }
            commitReentered = true
            ImmediateUltraCompositorCommit
        }

        compositor.acquireRemoteIngress()
        assertTrue(openReentered)
        assertTrue(commitReentered)
        compositor.close()
    }

    @Test fun sameIngressTargetCanReceiveANewGenerationWithoutPrematureClose() {
        val shared = FakeSurfaceTarget("shared-ingress")
        val backend = FakeCompositorBackend().apply { openProvider = { shared } }
        val compositor = TransactionalUltraCompositor(backend)
        val oldLease = compositor.acquireRemoteIngress()
        val newLease = compositor.acquireRemoteIngress()

        assertFalse(oldLease.isCurrent)
        assertTrue(newLease.isCurrent)
        assertEquals(0, shared.closeCount)
        oldLease.close()
        assertTrue(newLease.isCurrent)
        assertEquals(0, shared.closeCount)

        compositor.close()
        assertEquals(1, shared.closeCount)
    }

    @Test fun ingressReplacementCloseFailureIsRetainedWithoutLosingNewLease() {
        val failing = FailOnceSurfaceTarget("old-ingress")
        val replacement = FakeSurfaceTarget("new-ingress")
        val ingressTargets = ArrayDeque<DisplaySurfaceTarget>().apply {
            add(failing)
            add(replacement)
        }
        val backend = FakeCompositorBackend().apply {
            openProvider = { ingressTargets.removeFirst() }
        }
        val compositor = TransactionalUltraCompositor(backend)
        val oldLease = compositor.acquireRemoteIngress()

        val newLease = compositor.acquireRemoteIngress()

        assertFalse(oldLease.isCurrent)
        assertTrue(newLease.isCurrent)
        assertEquals(1, failing.closeAttempts)
        assertEquals(1, compositor.diagnostics().cleanupFailureCount)
        assertEquals(1, compositor.diagnostics().retiredTargetCount)
        compositor.close()
        assertEquals(2, failing.closeAttempts)
        assertEquals(1, replacement.closeCount)
    }

    @Test fun explicitBorrowBridgePreventsRouterCompositorDoubleOwnershipAndDoubleClose() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val router = DisplaySurfaceRouter()
        val physical = FakeSurfaceTarget("physical")
        compositor.attachOutput(DisplayDestination.MAIN, physical)

        assertThrows(IllegalStateException::class.java) {
            router.attach(DisplayDestination.MAIN, DisplayStreamPath.COMPOSITED, physical)
        }
        val routeLease = router.attach(
            DisplayDestination.MAIN,
            DisplayStreamPath.COMPOSITED,
            physical.borrowForRouting(),
        )
        assertTrue(routeLease.isCurrent)

        router.close()
        assertEquals(0, physical.closeCount)
        compositor.close()
        assertEquals(1, physical.closeCount)
    }

    @Test fun outputLeaseCloseCommitsDetachBeforeReleasingTarget() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val target = FakeSurfaceTarget("output")
        val lease = compositor.attachOutput(DisplayDestination.MAIN, target)
        val commitsBeforeDetach = backend.commits.size

        lease.close()

        assertEquals(commitsBeforeDetach + 1, backend.commits.size)
        assertTrue(backend.commits.last().outputTokens.isEmpty())
        assertEquals(1, target.closeCount)
    }

    @Test fun retirementFailureForcesTerminalBackendCloseBeforeSurfaceCleanup() {
        val backend = FakeCompositorBackend().apply {
            commitFactory = { FailingRetirementCommit() }
        }
        val compositor = TransactionalUltraCompositor(backend)
        val target = FakeSurfaceTarget("uncertain-topology")

        assertThrows(IllegalStateException::class.java) {
            compositor.attachOutput(DisplayDestination.MAIN, target)
        }

        assertTrue(compositor.diagnostics().closed)
        assertTrue(compositor.diagnostics().backendClosed)
        assertEquals(1, backend.closeCount)
        assertEquals(1, target.closeCount)
    }

    @Test fun backendCreatedIngressIsClosedWhenTargetProbeThrows() {
        val target = ProbeFailingSurfaceTarget()
        val backend = FakeCompositorBackend().apply { openProvider = { target } }
        val compositor = TransactionalUltraCompositor(backend)

        assertThrows(IllegalStateException::class.java) {
            compositor.acquireRemoteIngress()
        }

        assertEquals(1, target.closeCount)
        assertEquals(0, compositor.diagnostics().retiredTargetCount)
    }

    @Test fun invalidGeometryOpacityAndDuplicateLayerIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UltraPixelRect(0, 0, -1, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraRenderLayer(
                UltraLayerId("bad-opacity"),
                UltraLayerSource.PunchThrough,
                UltraPixelRect(0, 0, 1, 1),
                opacity = Double.NaN,
            )
        }
        val layer = UltraRenderLayer(
            UltraLayerId("duplicate"),
            UltraLayerSource.PunchThrough,
            UltraPixelRect(0, 0, 1, 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            UltraRenderSnapshot(1, listOf(layer, layer.copy()))
        }
    }

    @Test fun renderLayerCopyIgnoresDeclaredSizeAndStopsAfterLimitPlusOneElements() {
        val accepted = (0 until MAXIMUM_ULTRA_RENDER_LAYERS).map { index ->
            UltraRenderLayer(
                UltraLayerId("accepted-layer-$index"),
                UltraLayerSource.PunchThrough,
                UltraPixelRect(0, 0, 1, 1),
            )
        }
        assertEquals(
            MAXIMUM_ULTRA_RENDER_LAYERS,
            UltraRenderSnapshot(1, accepted).layers.size,
        )

        val reads = intArrayOf(0)
        val unbounded = object : List<UltraRenderLayer> by emptyList() {
            override val size: Int
                get() = 0 // Deliberately lies; production must not trust this value.

            override fun iterator(): Iterator<UltraRenderLayer> = object : Iterator<UltraRenderLayer> {
                override fun hasNext(): Boolean = true

                override fun next(): UltraRenderLayer {
                    val index = reads[0]++
                    return UltraRenderLayer(
                        UltraLayerId("layer-$index"),
                        UltraLayerSource.PunchThrough,
                        UltraPixelRect(0, 0, 1, 1),
                    )
                }
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            UltraRenderSnapshot(1, unbounded)
        }
        assertEquals(MAXIMUM_ULTRA_RENDER_LAYERS + 1, reads[0])
    }

    @Test fun transactionOutputCopyIgnoresDeclaredSizeAndStopsAfterLimitPlusOneElements() {
        val reads = intArrayOf(0)
        val unbounded = object : List<UltraCompositorOutputBinding> by emptyList() {
            override val size: Int
                get() = Int.MIN_VALUE // Deliberately impossible; production must not trust it.

            override fun iterator(): Iterator<UltraCompositorOutputBinding> =
                object : Iterator<UltraCompositorOutputBinding> {
                    override fun hasNext(): Boolean = true

                    override fun next(): UltraCompositorOutputBinding {
                        val index = reads[0]++
                        val destination = DisplayDestination(
                            DisplayDestinationRole.AUXILIARY,
                            DisplayDestinationId("hostile-output-$index"),
                        )
                        return UltraCompositorOutputBinding(
                            UltraCompositorOutputToken(destination, index.toLong()),
                            FakeSurfaceTarget("hostile-target-$index"),
                        )
                    }
                }
        }

        assertThrows(IllegalArgumentException::class.java) {
            UltraCompositorTransaction(
                order = 1,
                kind = UltraCompositorTransactionKind.TOPOLOGY,
                render = null,
                remoteIngress = null,
                outputs = unbounded,
            )
        }
        assertEquals(MAXIMUM_ACTIVE_DISPLAY_SURFACES + 1, reads[0])
    }

    @Test fun sixtyFifthOutputIsRejectedBeforeTargetProbeAndWithoutBackendCommit() {
        val backend = FakeCompositorBackend()
        val compositor = TransactionalUltraCompositor(backend)
        val destinations = (0 until MAXIMUM_ACTIVE_DISPLAY_SURFACES).map { index ->
            DisplayDestination(
                DisplayDestinationRole.AUXILIARY,
                DisplayDestinationId("output-$index"),
            )
        }
        val leases = destinations.mapIndexed { index, destination ->
            compositor.attachOutput(destination, FakeSurfaceTarget("output-surface-$index"))
        }
        val commitsBeforeRejection = backend.commits.size
        val rejected = TrackingSurfaceTarget("rejected-output")

        assertThrows(IllegalArgumentException::class.java) {
            compositor.attachOutput(
                DisplayDestination(
                    DisplayDestinationRole.AUXILIARY,
                    DisplayDestinationId("output-overflow"),
                ),
                rejected,
            )
        }

        assertEquals(commitsBeforeRejection, backend.commits.size)
        assertTrue(leases.all(UltraCompositorOutputLease::isCurrent))
        assertEquals(MAXIMUM_ACTIVE_DISPLAY_SURFACES, backend.commits.last().outputTokens.size)
        assertEquals(0, rejected.descriptorReads)
        assertEquals(0, rejected.ownershipReads)
        assertEquals(0, rejected.validityReads)
        assertEquals(0, rejected.closeCount)

        val replacement = compositor.attachOutput(destinations.first(), rejected)
        assertTrue(replacement.isCurrent)
        assertEquals(MAXIMUM_ACTIVE_DISPLAY_SURFACES, backend.commits.last().outputTokens.size)
        compositor.close()
        assertEquals(1, rejected.closeCount)
    }
}

private data class RecordedTransaction(
    val order: Long,
    val kind: UltraCompositorTransactionKind,
    val render: UltraRenderSnapshot?,
    val remoteIngressToken: UltraRemoteIngressToken?,
    val outputTokens: List<UltraCompositorOutputToken>,
)

private class FakeCompositorBackend : UltraCompositorBackend {
    val commits = mutableListOf<RecordedTransaction>()
    var closeCount = 0
        private set
    private var ingressSequence = 0
    var openProvider: ((UltraRemoteIngressToken) -> DisplaySurfaceTarget)? = null
    @Volatile var commitFactory: ((UltraCompositorTransaction) -> UltraCompositorCommit)? = null

    override fun openRemoteIngress(token: UltraRemoteIngressToken): DisplaySurfaceTarget {
        openProvider?.let { return it(token) }
        ingressSequence += 1
        return FakeSurfaceTarget("ingress-$ingressSequence")
    }

    override fun commit(transaction: UltraCompositorTransaction): UltraCompositorCommit {
        commits += RecordedTransaction(
            transaction.order,
            transaction.kind,
            transaction.render,
            transaction.remoteIngress?.token,
            transaction.outputs.map { it.token },
        )
        return commitFactory?.invoke(transaction) ?: ImmediateUltraCompositorCommit
    }

    override fun close() {
        closeCount += 1
    }
}

private class BlockingCommit(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : UltraCompositorCommit {
    override fun awaitRetirement() {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "retirement fence timed out" }
    }

    override fun close() = Unit
}

private class FailingRetirementCommit : UltraCompositorCommit {
    override fun awaitRetirement(): Unit = throw IllegalStateException("synthetic fence failure")
    override fun close() = Unit
}

private class ProbeFailingSurfaceTarget : DisplaySurfaceTarget {
    override val descriptor: DisplaySurfaceDescriptor
        get() = throw IllegalStateException("synthetic descriptor failure")
    override val ownership = DisplaySurfaceOwnership()
    override val isValid = true
    var closeCount = 0
        private set

    override fun close() {
        closeCount += 1
    }
}
