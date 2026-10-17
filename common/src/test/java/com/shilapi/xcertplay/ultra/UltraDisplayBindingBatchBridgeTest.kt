package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBinding
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingResources
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraDisplayBindingBatchBridgeTest {
    @Test fun v2LocalOnlyOutputRequiresNoRemoteIngressOrUiSyncIdentity() {
        val local = graphOutput("local", withUiSync = false)
        val graph = UltraDisplayGraphRegistry().replace(listOf(local), emptyList())
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val target = FakeSurfaceTarget("local")

        val lease = bridge.replaceOutputs(
            graph,
            mapOf(local.outputId to DisplayDestination.HUD),
            mapOf(local.outputId to target),
        )

        assertTrue(lease.isCurrent)
        assertTrue(graph.ingresses.isEmpty())
        assertTrue(binder.plans.single().routes.isEmpty())
        assertSame(local, binder.plans.single().outputRoutes.single().output)
        assertEquals(graph.revision, binder.plans.single().graphRevision)
        assertSame(graph, binder.plans.single().graphSnapshot)
        assertEquals(null, bridge.snapshot().bindingRevision)
        assertEquals(graph.revision, bridge.snapshot().graphRevision)
        assertEquals(null, bridge.snapshot().routes.single().uiSyncDisplayId)
        assertEquals(local.topologyDisplayId, bridge.snapshot().routes.single().topologyDisplayId)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (binder.plans.single().outputRoutes as MutableList<UltraDisplayBatchOutputRoute>).clear()
        }
        bridge.close()
        assertEquals(1, target.closeCount)
    }

    @Test fun v2OneRemoteIngressCanBackTwoOutputsWithoutAOneToOneBinding() {
        val main = graphOutput("main")
        val cluster = graphOutput("cluster", withUiSync = false)
        val ingress = UltraDisplayGraphIngress(
            UltraIngressId("shared-remote"),
            syntheticHandle(AirPlayStreamIdentity(100, "remote", null)),
        )
        val graph = UltraDisplayGraphRegistry().replace(listOf(main, cluster), listOf(ingress))
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)

        bridge.replaceOutputs(
            graph,
            mapOf(main.outputId to DisplayDestination.MAIN, cluster.outputId to DisplayDestination.CLUSTER),
            mapOf(main.outputId to FakeSurfaceTarget("main"), cluster.outputId to FakeSurfaceTarget("cluster")),
        )

        assertEquals(1, graph.ingresses.size)
        assertEquals(2, binder.plans.single().outputRoutes.size)
        assertEquals(
            setOf(main.outputId, cluster.outputId),
            bridge.snapshot().routes.map { it.outputId }.toSet(),
        )
        assertTrue(binder.plans.single().outputRoutes.all {
            it.routerStreamPath == DisplayStreamPath.COMPOSITED
        })
        bridge.close()
    }

    @Test fun v2RejectsMissingExtraAndDuplicateOutputAssignmentsBeforePreparation() {
        val first = graphOutput("first")
        val second = graphOutput("second", withUiSync = false)
        val graph = UltraDisplayGraphRegistry().replace(listOf(first, second), emptyList())
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val destinations = mapOf(
            first.outputId to DisplayDestination.MAIN,
            second.outputId to DisplayDestination.CLUSTER,
        )
        val targets = mapOf<UltraDisplayOutputId, DisplaySurfaceTarget>(
            first.outputId to FakeSurfaceTarget("first"),
            second.outputId to FakeSurfaceTarget("second"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(graph, destinations - second.outputId, targets)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(graph, destinations, targets - first.outputId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(
                graph,
                destinations + (UltraDisplayOutputId.Custom("extra") to DisplayDestination.HUD),
                targets,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(
                graph,
                mapOf(first.outputId to DisplayDestination.MAIN, second.outputId to DisplayDestination.MAIN),
                targets,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraDisplayGraphRegistry().replace(listOf(first, first.copy()), emptyList())
        }
        assertEquals(0, binder.prepareCount)
        assertTrue(bridge.snapshot().routes.isEmpty())
    }

    @Test fun v2FailedCandidateRollsBackAndStaleGraphCannotReplaceNewerGraph() {
        val registry = UltraDisplayGraphRegistry()
        val output = graphOutput("rollback")
        val original = registry.replace(listOf(output), emptyList())
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val destination = mapOf(output.outputId to DisplayDestination.MAIN)
        val oldTarget = FakeSurfaceTarget("old")
        val old = bridge.replaceOutputs(original, destination, mapOf(output.outputId to oldTarget))
        val newer = registry.replace(listOf(output), emptyList())
        val candidate = FakeSurfaceTarget("candidate")

        binder.failPrepare = true
        assertThrows(IllegalStateException::class.java) {
            bridge.replaceOutputs(newer, destination, mapOf(output.outputId to candidate))
        }
        binder.failPrepare = false
        binder.failCommit = true
        assertThrows(IllegalStateException::class.java) {
            bridge.replaceOutputs(newer, destination, mapOf(output.outputId to candidate))
        }
        binder.failCommit = false
        assertTrue(old.isCurrent)
        assertEquals(original.revision, bridge.snapshot().graphRevision)
        assertEquals(0, oldTarget.closeCount)
        assertEquals(0, candidate.closeCount)

        val current = bridge.replaceOutputs(newer, destination, mapOf(output.outputId to candidate))
        assertFalse(old.isCurrent)
        assertTrue(current.isCurrent)
        assertEquals(1, oldTarget.closeCount)
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(original, destination, mapOf(output.outputId to FakeSurfaceTarget("stale")))
        }
        val unrelatedRegistry = UltraDisplayGraphRegistry()
        unrelatedRegistry.replace(listOf(output), emptyList())
        val sameRevisionOtherGraph = unrelatedRegistry.replace(listOf(output), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(
                sameRevisionOtherGraph,
                destination,
                mapOf(output.outputId to FakeSurfaceTarget("unrelated")),
            )
        }
        assertEquals(newer.revision, bridge.snapshot().graphRevision)
        assertEquals(1, oldTarget.closeCount)
        bridge.close()
        assertEquals(1, candidate.closeCount)
    }

    @Test fun v2LeaseCloseIsRetryableAndDoesNotClaimRetirementBeforeFenceProof() {
        val output = graphOutput("retirement", withUiSync = false)
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        val fence = FailOnceRetirementFence()
        val binder = AtomicFakeBatchBinder().apply { nextFence = fence }
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val target = FakeSurfaceTarget("retirement")
        val lease = bridge.replaceOutputs(
            graph,
            mapOf(output.outputId to DisplayDestination.HUD),
            mapOf(output.outputId to target),
        )

        assertThrows(IllegalStateException::class.java) { lease.close() }
        assertFalse(lease.isCurrent)
        assertEquals(0, target.closeCount)
        assertEquals(1, bridge.snapshot().retiredInstallationCount)

        lease.close()
        assertEquals(1, target.closeCount)
        assertEquals(0, bridge.snapshot().retiredInstallationCount)
        assertEquals(2, fence.awaitCount.get())
        bridge.close()
    }

    @Test fun backendPoisonMakesBridgeLeaseAndRouteSnapshotFailClosed() {
        val output = graphOutput("poison")
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val lease = bridge.replaceOutputs(
            graph,
            mapOf(output.outputId to DisplayDestination.MAIN),
            mapOf(output.outputId to FakeSurfaceTarget("poison")),
        )
        assertTrue(lease.isCurrent)
        assertTrue(bridge.snapshot().backendCurrent)

        checkNotNull(binder.current).poisoned = true

        assertFalse(lease.isCurrent)
        assertFalse(bridge.snapshot().backendCurrent)
        assertEquals(graph.revision, bridge.snapshot().graphRevision)
        assertTrue(bridge.snapshot().routes.isEmpty())
        bridge.close()
    }

    @Test fun typedOutputMappingIsExplicitAndCommittedAsOneCompleteBatch() {
        val android = UltraDisplayOutputId.Android("same-text")
        val custom = UltraDisplayOutputId.Custom("same-text")
        val snapshot = snapshot(listOf(android, custom))
        val targets = linkedMapOf<UltraDisplayOutputId, DisplaySurfaceTarget>(
            android to FakeSurfaceTarget("android"),
            custom to FakeSurfaceTarget("custom"),
        )
        val destinations = linkedMapOf(
            android to DisplayDestination.MAIN,
            custom to DisplayDestination.CLUSTER,
        )
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)

        val lease = bridge.replace(snapshot, destinations, targets)

        assertTrue(lease.isCurrent)
        assertEquals(1, binder.prepareCount)
        assertEquals(1, binder.commitCount)
        val plan = binder.plans.single()
        assertEquals(2, plan.routes.size)
        assertTrue(plan.routes.all { it.routerStreamPath == DisplayStreamPath.COMPOSITED })
        assertTrue(plan.routes.any { it.binding.outputId == android && it.destination == DisplayDestination.MAIN })
        assertTrue(plan.routes.any { it.binding.outputId == custom && it.destination == DisplayDestination.CLUSTER })
        assertSame(targets.getValue(android), plan.routes.single { it.binding.outputId == android }.outputTarget)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (plan.routes as MutableList<UltraDisplayBatchRoute>).clear()
        }
        assertEquals(
            setOf(android, custom),
            bridge.snapshot().routes.map(UltraDisplayBridgeRouteSnapshot::outputId).toSet(),
        )
    }

    @Test fun validationPreparationAndCommitFailuresKeepOldGenerationCurrent() {
        val registry = UltraDisplayBindingRegistry()
        val first = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "old")
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val oldLease = bridge.replace(first.snapshot, first.destinations, first.targets)
        val oldTarget = first.targets.values.single() as FakeSurfaceTarget
        val published = bridge.snapshot()

        val missingDestination = emptyMap<UltraDisplayOutputId, DisplayDestination>()
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replace(first.snapshot, missingDestination, first.targets)
        }
        assertEquals(1, binder.prepareCount)
        assertTrue(oldLease.isCurrent)
        assertEquals(published.generation, bridge.snapshot().generation)

        val second = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "new")
        binder.failPrepare = true
        assertThrows(IllegalStateException::class.java) {
            bridge.replace(second.snapshot, second.destinations, second.targets)
        }
        binder.failPrepare = false
        assertTrue(oldLease.isCurrent)
        assertSame(binder.leases.single(), binder.current)
        assertEquals(0, oldTarget.closeCount)
        assertEquals(published.generation, bridge.snapshot().generation)

        binder.failCommit = true
        assertThrows(IllegalStateException::class.java) {
            bridge.replace(second.snapshot, second.destinations, second.targets)
        }
        binder.failCommit = false
        assertTrue(oldLease.isCurrent)
        assertSame(binder.leases.single(), binder.current)
        assertEquals(0, oldTarget.closeCount)
        assertEquals(published.generation, bridge.snapshot().generation)

        val newLease = bridge.replace(second.snapshot, second.destinations, second.targets)
        assertFalse(oldLease.isCurrent)
        assertTrue(newLease.isCurrent)
        assertEquals(4L, newLease.generation.value)
        assertEquals(1, oldTarget.closeCount)
    }

    @Test fun nativeUncertainCommitFailsClosedInsteadOfAdvertisingOldRoutes() {
        val registry = UltraDisplayBindingRegistry()
        val first = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "old")
        val second = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "new")
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val oldLease = bridge.replace(first.snapshot, first.destinations, first.targets)
        val oldTarget = first.targets.values.single() as FakeSurfaceTarget
        val candidate = second.targets.values.single() as FakeSurfaceTarget
        binder.failCommitUncertain = true

        assertThrows(UltraDisplayBatchCommitUncertainException::class.java) {
            bridge.replace(second.snapshot, second.destinations, second.targets)
        }

        assertTrue(bridge.snapshot().closed)
        assertTrue(bridge.snapshot().routes.isEmpty())
        assertFalse(oldLease.isCurrent)
        assertEquals(1, oldTarget.closeCount)
        assertEquals(0, candidate.closeCount) // Candidate remains caller-owned on this fake path.
        bridge.close()
    }

    @Test fun replacementPublishesNewGenerationBeforeRetiringOldThroughFence() {
        val registry = UltraDisplayBindingRegistry()
        val first = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "old")
        val second = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "new")
        val fence = BlockingRetirementFence()
        val binder = AtomicFakeBatchBinder().apply { nextFence = fence }
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val oldLease = bridge.replace(first.snapshot, first.destinations, first.targets)
        binder.nextFence = ImmediateRetirementFence()
        val oldTarget = first.targets.values.single() as FakeSurfaceTarget
        val executor = Executors.newSingleThreadExecutor()

        try {
            val replacement = executor.submit<UltraDisplayBindingBridgeLease> {
                bridge.replace(second.snapshot, second.destinations, second.targets)
            }
            assertTrue("retirement fence was not reached", fence.entered.await(2, TimeUnit.SECONDS))

            val duringFence = bridge.snapshot()
            assertEquals(2L, duringFence.generation)
            assertEquals(second.snapshot.revision, duringFence.bindingRevision)
            assertTrue(duringFence.operationInFlight)
            assertFalse(oldLease.isCurrent)
            assertEquals(0, oldTarget.closeCount)

            fence.release.countDown()
            val newLease = replacement.get(2, TimeUnit.SECONDS)
            assertTrue(newLease.isCurrent)
            assertEquals(1, fence.awaitCount.get())
            assertEquals(1, fence.closeCount.get())
            assertEquals(1, oldTarget.closeCount)
        } finally {
            fence.release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun staleCallerLeaseCannotRetireReplacementAndCurrentCloseIsFenceSafe() {
        val registry = UltraDisplayBindingRegistry()
        val first = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "old")
        val second = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "new")
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val oldLease = bridge.replace(first.snapshot, first.destinations, first.targets)
        val newLease = bridge.replace(second.snapshot, second.destinations, second.targets)
        val installed = binder.current

        oldLease.close()

        assertTrue(newLease.isCurrent)
        assertSame(installed, binder.current)
        assertEquals(0, checkNotNull(installed).retirementCount.get())

        newLease.close()
        assertFalse(newLease.isCurrent)
        assertEquals(1, installed.retirementCount.get())
        assertEquals(1, installed.closeCount.get())
        assertTrue(bridge.snapshot().routes.isEmpty())
    }

    @Test fun retirementFailureIsRetainedForRetryWithoutRollingBackNewState() {
        val registry = UltraDisplayBindingRegistry()
        val first = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "old")
        val second = fixture(registry, listOf(UltraDisplayOutputId.Android("output")), "new")
        val failOnce = FailOnceRetirementFence()
        val binder = AtomicFakeBatchBinder().apply { nextFence = failOnce }
        val bridge = UltraDisplayBindingBatchBridge(binder)
        bridge.replace(first.snapshot, first.destinations, first.targets)
        binder.nextFence = ImmediateRetirementFence()
        val oldTarget = first.targets.values.single() as FakeSurfaceTarget

        val newLease = bridge.replace(second.snapshot, second.destinations, second.targets)

        assertTrue(newLease.isCurrent)
        assertEquals(2L, bridge.snapshot().generation)
        assertEquals(1, bridge.snapshot().retiredInstallationCount)
        assertEquals(1, bridge.snapshot().cleanupFailureCount)
        assertEquals(0, oldTarget.closeCount)

        bridge.close()
        assertFalse(newLease.isCurrent)
        assertEquals(0, bridge.snapshot().retiredInstallationCount)
        assertEquals(2, failOnce.awaitCount.get())
        assertEquals(1, oldTarget.closeCount)
        assertTrue(bridge.snapshot().closed)
    }

    @Test fun closeDoesNotReleaseBinderAuthorityUntilRetirementCanComplete() {
        val output = UltraDisplayOutputId.Android("output")
        val fixture = fixture(UltraDisplayBindingRegistry(), listOf(output), "close-retry")
        val fence = FailOnceRetirementFence()
        val binder = AtomicFakeBatchBinder().apply { nextFence = fence }
        val bridge = UltraDisplayBindingBatchBridge(binder)
        bridge.replace(fixture.snapshot, fixture.destinations, fixture.targets)
        val target = fixture.targets.getValue(output) as FakeSurfaceTarget

        assertThrows(IllegalStateException::class.java) { bridge.close() }
        assertTrue(bridge.snapshot().closed)
        assertEquals(1, bridge.snapshot().retiredInstallationCount)
        assertEquals(0, target.closeCount)

        bridge.close()
        assertEquals(0, bridge.snapshot().retiredInstallationCount)
        assertEquals(1, target.closeCount)
        assertEquals(2, fence.awaitCount.get())
    }

    @Test fun everyDisplayAndCallerResourceCollectionHasAHardLimitOf64() {
        val outputs: List<UltraDisplayOutputId> =
            (0 until 64).map { UltraDisplayOutputId.Custom("output-$it") }
        val snapshot = snapshot(outputs)
        val destinations = outputs.associateWith { output ->
            DisplayDestination(
                DisplayDestinationRole.AUXILIARY,
                DisplayDestinationId("destination-${output.value.substringAfter('-')}"),
            )
        }
        val targets = outputs.associateWith { output ->
            FakeSurfaceTarget("surface-${output.value.substringAfter('-')}") as DisplaySurfaceTarget
        }
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)

        bridge.replace(snapshot, destinations, targets)
        assertEquals(64, binder.plans.single().routes.size)

        val extra = UltraDisplayOutputId.Custom("extra")
        val oversizedDestinations = LinkedHashMap(destinations).apply {
            put(
                extra,
                DisplayDestination(DisplayDestinationRole.AUXILIARY, DisplayDestinationId("extra")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replace(snapshot, oversizedDestinations, targets)
        }
        val oversizedTargets = LinkedHashMap(targets).apply {
            put(extra, FakeSurfaceTarget("extra"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replace(snapshot, destinations, oversizedTargets)
        }
        val iteratorReads = AtomicInteger()
        val overIteratingDestinations = overIteratingMap(
            destinations,
            extra to DisplayDestination(
                DisplayDestinationRole.AUXILIARY,
                DisplayDestinationId("iterator-extra"),
            ),
            iteratorReads,
        )
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replace(snapshot, overIteratingDestinations, targets)
        }
        assertEquals(64, iteratorReads.get())
        assertEquals(1, binder.prepareCount)
    }

    @Test fun duplicateDestinationOrSurfaceOwnershipIsRejectedBeforePreparation() {
        val first = UltraDisplayOutputId.Android("one")
        val second = UltraDisplayOutputId.Custom("two")
        val snapshot = snapshot(listOf(first, second))
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val shared = FakeSurfaceTarget("shared")

        assertThrows(IllegalArgumentException::class.java) {
            bridge.replace(
                snapshot,
                mapOf(first to DisplayDestination.MAIN, second to DisplayDestination.MAIN),
                mapOf(first to FakeSurfaceTarget("one"), second to FakeSurfaceTarget("two")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replace(
                snapshot,
                mapOf(first to DisplayDestination.MAIN, second to DisplayDestination.CLUSTER),
                mapOf(first to shared, second to shared),
            )
        }
        assertEquals(0, binder.prepareCount)
        assertEquals(0, shared.closeCount)
    }

    @Test fun crossThreadCallbackCloseFailsFastWithoutBlockingOrClosingBinderEarly() {
        val output = UltraDisplayOutputId.Android("output")
        val fixture = fixture(UltraDisplayBindingRegistry(), listOf(output), "callback")
        val binder = AtomicFakeBatchBinder()
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val callbackExecutor = Executors.newSingleThreadExecutor()
        binder.onPrepare = {
            callbackExecutor.submit {
                assertThrows(IllegalStateException::class.java) { bridge.close() }
            }.get(2, TimeUnit.SECONDS)
        }

        try {
            val lease = bridge.replace(fixture.snapshot, fixture.destinations, fixture.targets)
            assertTrue(lease.isCurrent)
            assertFalse(bridge.snapshot().closed)
            assertEquals(1, bridge.snapshot().routes.size)
            assertEquals(0, (fixture.targets.getValue(output) as FakeSurfaceTarget).closeCount)

            bridge.close()

            assertTrue(bridge.snapshot().closed)
            assertEquals(1, (fixture.targets.getValue(output) as FakeSurfaceTarget).closeCount)
            assertEquals(0, bridge.snapshot().retiredInstallationCount)
        } finally {
            callbackExecutor.shutdownNow()
        }
    }
}

private fun graphOutput(id: String, withUiSync: Boolean = true) = UltraDisplayGraphOutput(
    topologyDisplayId = UltraTopologyDisplayId(UUID.nameUUIDFromBytes(id.toByteArray())),
    outputId = UltraDisplayOutputId.Custom("output-$id"),
    uiSyncDisplayId = UltraUiSyncDisplayId("ui-$id").takeIf { withUiSync },
)

/** AirPlay stream epochs are intentionally opaque outside shared; test reflection never reaches production. */
private fun syntheticHandle(identity: AirPlayStreamIdentity): AirPlayVideoStreamHandle {
    val epochClass = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
    val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    val constructor = AirPlayVideoStreamHandle::class.java.declaredConstructors
        .single { it.parameterCount == 3 }
        .apply { isAccessible = true }
    return constructor.newInstance(identity, epoch, null) as AirPlayVideoStreamHandle
}

private data class BridgeFixture(
    val snapshot: UltraDisplayBindingSnapshot,
    val destinations: Map<UltraDisplayOutputId, DisplayDestination>,
    val targets: Map<UltraDisplayOutputId, DisplaySurfaceTarget>,
)

private fun fixture(
    registry: UltraDisplayBindingRegistry,
    outputs: List<UltraDisplayOutputId>,
    surfacePrefix: String,
): BridgeFixture {
    val bindings = bindings(outputs)
    val snapshot = registry.replace(resources(bindings), bindings)
    val destinations = outputs.mapIndexed { index, output ->
        output to DisplayDestination(
            if (index == 0) DisplayDestinationRole.MAIN else DisplayDestinationRole.AUXILIARY,
            DisplayDestinationId("destination-$index"),
        )
    }.toMap()
    val targets = outputs.mapIndexed { index, output ->
        output to FakeSurfaceTarget("$surfacePrefix-$index") as DisplaySurfaceTarget
    }.toMap()
    return BridgeFixture(snapshot, destinations, targets)
}

private fun snapshot(outputs: List<UltraDisplayOutputId>): UltraDisplayBindingSnapshot {
    val values = bindings(outputs)
    return UltraDisplayBindingRegistry().replace(resources(values), values)
}

private fun bindings(outputs: List<UltraDisplayOutputId>): List<UltraDisplayBinding> =
    outputs.mapIndexed { index, output ->
        UltraDisplayBinding(
            uiSyncDisplayId = UltraUiSyncDisplayId("ui-$index"),
            topologyDisplayId = UltraTopologyDisplayId(UUID(0L, index.toLong() + 1L)),
            airPlayStreamIdentity = UltraAirPlayStreamIdentity(
                type = 100 + index,
                streamConnectionId = "stream-$index",
            ),
            outputId = output,
        )
    }

private fun resources(bindings: List<UltraDisplayBinding>) = UltraDisplayBindingResources(
    uiSyncDisplayIds = bindings.map(UltraDisplayBinding::uiSyncDisplayId),
    topologyDisplayIds = bindings.map(UltraDisplayBinding::topologyDisplayId),
    airPlayStreamIdentities = bindings.map(UltraDisplayBinding::airPlayStreamIdentity),
    outputIds = bindings.map(UltraDisplayBinding::outputId),
)

private fun <K, V> overIteratingMap(
    declared: Map<K, V>,
    extra: Pair<K, V>,
    reads: AtomicInteger,
): Map<K, V> = object : AbstractMap<K, V>() {
    override val entries: Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
        override val size: Int = declared.size

        override fun iterator(): Iterator<Map.Entry<K, V>> {
            val entries = declared.entries.map { SimpleImmutableEntry(it.key, it.value) } +
                SimpleImmutableEntry(extra.first, extra.second)
            val delegate = entries.iterator()
            return object : Iterator<Map.Entry<K, V>> {
                override fun hasNext(): Boolean = delegate.hasNext()

                override fun next(): Map.Entry<K, V> {
                    reads.incrementAndGet()
                    return delegate.next()
                }
            }
        }
    }
}

private class AtomicFakeBatchBinder : UltraDisplayBatchBinder {
    val plans = mutableListOf<UltraDisplayBatchPlan>()
    val leases = mutableListOf<FakeBatchLease>()
    var prepareCount = 0
    var commitCount = 0
    var failPrepare = false
    var failCommit = false
    var failCommitUncertain = false
    var onPrepare: (() -> Unit)? = null
    var nextFence: RetryableRetirementFence = ImmediateRetirementFence()
    @Volatile var current: FakeBatchLease? = null

    override fun prepare(plan: UltraDisplayBatchPlan): PreparedUltraDisplayBatch {
        prepareCount += 1
        if (failPrepare) throw IllegalStateException("synthetic prepare failure")
        onPrepare?.invoke()
        plans += plan
        val fence = nextFence
        return object : PreparedUltraDisplayBatch {
            override val generation = plan.generation
            private var closed = false
            private var committed = false

            override fun commit(): UltraDisplayBatchLease {
                check(!closed)
                check(!committed)
                committed = true
                commitCount += 1
                if (failCommit) throw IllegalStateException("synthetic commit failure")
                if (failCommitUncertain) {
                    throw UltraDisplayBatchCommitUncertainException(
                        IllegalStateException("synthetic native uncertainty"),
                    )
                }
                val lease = FakeBatchLease(this@AtomicFakeBatchBinder, plan, fence)
                current?.current = false
                current = lease
                leases += lease
                return lease
            }

            override fun close() {
                closed = true
            }
        }
    }
}

private class FakeBatchLease(
    private val binder: AtomicFakeBatchBinder,
    private val plan: UltraDisplayBatchPlan,
    private val fence: RetryableRetirementFence,
) : UltraDisplayBatchLease {
    @Volatile var current = true
    @Volatile var poisoned = false
    override val isOperational: Boolean get() = current && !poisoned
    val retirementCount = AtomicInteger()
    val closeCount = AtomicInteger()

    override fun beginRetirement(): UltraDisplayRetirementFence {
        retirementCount.incrementAndGet()
        if (binder.current === this) {
            current = false
            binder.current = null
        }
        return fence
    }

    override fun close() {
        check(fence.retired) { "lease closed before retirement fence" }
        if (closeCount.getAndIncrement() == 0) {
            plan.routes.forEach { it.outputTarget.close() }
            plan.outputRoutes.forEach { it.outputTarget.close() }
        }
    }
}

private interface RetryableRetirementFence : UltraDisplayRetirementFence {
    val retired: Boolean
}

private class ImmediateRetirementFence : RetryableRetirementFence {
    @Volatile override var retired = false
        private set

    override fun awaitRetirement() {
        retired = true
    }

    override fun close() = Unit
}

private class BlockingRetirementFence : RetryableRetirementFence {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val awaitCount = AtomicInteger()
    val closeCount = AtomicInteger()
    @Volatile override var retired = false
        private set

    override fun awaitRetirement() {
        awaitCount.incrementAndGet()
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "retirement fence timed out" }
        retired = true
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class FailOnceRetirementFence : RetryableRetirementFence {
    val awaitCount = AtomicInteger()
    @Volatile override var retired = false
        private set

    override fun awaitRetirement() {
        if (awaitCount.incrementAndGet() == 1) {
            throw IllegalStateException("synthetic retirement failure")
        }
        retired = true
    }

    override fun close() = Unit
}
