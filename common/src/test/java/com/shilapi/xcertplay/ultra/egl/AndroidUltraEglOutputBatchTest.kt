package com.shilapi.xcertplay.ultra.egl

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.AndroidDisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.AndroidSurfaceOwnership
import com.shilapi.xcertplay.ultra.AndroidUltraEglBatchBinder
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.DisplaySurfaceDescriptor
import com.shilapi.xcertplay.ultra.DisplaySurfaceId
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraDisplayBatchOutputRoute
import com.shilapi.xcertplay.ultra.UltraDisplayBatchPlan
import com.shilapi.xcertplay.ultra.UltraDisplayBindingBatchBridge
import com.shilapi.xcertplay.ultra.UltraDisplayBridgeGeneration
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBinding
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingResources
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class AndroidUltraEglOutputBatchTest {
    private val size = PixelSize(800, 480)

    @Test fun productionV2BridgeAcceptsLocalOnlyOutputsAndRollsBackSecondSurfaceFailure() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val outputs = listOf(
            UltraDisplayGraphOutput(
                UltraTopologyDisplayId(UUID(0L, 201L)),
                UltraDisplayOutputId.Android("v2-main"),
                UltraUiSyncDisplayId("v2-ui"),
            ),
            UltraDisplayGraphOutput(
                UltraTopologyDisplayId(UUID(0L, 202L)),
                UltraDisplayOutputId.Custom("v2-local-hud"),
                null,
            ),
        )
        val graph = UltraDisplayGraphRegistry().replace(outputs, emptyList())
        val binder = AndroidUltraEglBatchBinder(backend, graph)
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val destinations = mapOf(
            outputs[0].outputId to DisplayDestination.MAIN,
            outputs[1].outputId to DisplayDestination.HUD,
        )
        fun targets(prefix: String): Map<UltraDisplayOutputId, DisplaySurfaceTarget> =
            outputs.mapIndexed { index, output ->
                output.outputId to AndroidDisplaySurfaceTarget(
                    validSurface(),
                    DisplaySurfaceDescriptor(DisplaySurfaceId("$prefix-$index"), size),
                ) as DisplaySurfaceTarget
            }.toMap()

        val otherSnapshot = UltraDisplayGraphRegistry().replace(outputs, emptyList())
        val rejectedTargets = targets("wrong-graph")
        assertThrows(IllegalArgumentException::class.java) {
            bridge.replaceOutputs(otherSnapshot, destinations, rejectedTargets)
        }
        assertEquals(0, driver.createCalls)
        rejectedTargets.values.forEach { assertUnowned(it as AndroidDisplaySurfaceTarget) }

        val oldTargets = targets("v2-old")
        val old = bridge.replaceOutputs(graph, destinations, oldTargets)
        assertTrue(old.isCurrent)
        assertEquals(2, binder.routeSnapshot().routes.size)
        assertEquals(graph.revision, bridge.snapshot().graphRevision)
        assertEquals(null, bridge.snapshot().bindingRevision)
        assertEquals(null, bridge.snapshot().routes.single { it.outputId == outputs[1].outputId }.uiSyncDisplayId)

        val candidates = targets("v2-new")
        driver.failCreateAt = 4
        assertThrows(IllegalStateException::class.java) {
            bridge.replaceOutputs(graph, destinations, candidates)
        }
        assertTrue(old.isCurrent)
        assertEquals(2, binder.routeSnapshot().routes.size)
        oldTargets.values.forEach { assertTrue(it.isValid) }
        candidates.values.forEach { assertUnowned(it as AndroidDisplaySurfaceTarget) }

        driver.failCreateAt = null
        val createCallsBeforeTransfer = driver.createCalls
        val current = bridge.replaceOutputs(graph, destinations, oldTargets)
        assertFalse(old.isCurrent)
        assertTrue(current.isCurrent)
        assertEquals(createCallsBeforeTransfer, driver.createCalls)
        old.close() // A stale V2 lease must not close a target transferred to the new generation.
        oldTargets.values.forEach { assertTrue(it.isValid) }
        assertEquals(2, binder.routeSnapshot().routes.size)

        bridge.close()
        oldTargets.values.forEach { assertFalse(it.isValid) }
        binder.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun v2BinderRejectsEqualValueOutputObjectNotPresentInExactGraph() {
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(BatchDriver()))
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 203L)),
            UltraDisplayOutputId.Custom("v2-exact-object"),
        )
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        val binder = AndroidUltraEglBatchBinder(backend, graph)
        val target = AndroidDisplaySurfaceTarget(
            validSurface(),
            DisplaySurfaceDescriptor(DisplaySurfaceId("v2-spoof"), size),
        )
        val forgedPlan = UltraDisplayBatchPlan(
            UltraDisplayBridgeGeneration(1L),
            null,
            graph.revision,
            graph,
            emptyList(),
            listOf(UltraDisplayBatchOutputRoute(
                output.copy(),
                DisplayDestination.MAIN,
                target,
                target.descriptor,
            )),
        )

        assertThrows(IllegalArgumentException::class.java) { binder.prepare(forgedPlan) }
        assertUnowned(target)
        binder.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun nativeOldBatchRetirementFailureDoesNotLeaveV2BridgeAdvertisingCurrentRoutes() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 204L)),
            UltraDisplayOutputId.Custom("v2-poisoned"),
        )
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        val binder = AndroidUltraEglBatchBinder(backend, graph)
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val destinations = mapOf(output.outputId to DisplayDestination.MAIN)
        fun target(id: String) = AndroidDisplaySurfaceTarget(
            validSurface(), DisplaySurfaceDescriptor(DisplaySurfaceId(id), size),
        )
        val old = target("v2-poison-old")
        val oldLease = bridge.replaceOutputs(graph, destinations, mapOf(output.outputId to old))
        driver.failNextDestroy = true

        val replacement = target("v2-poison-new")
        val newLease = bridge.replaceOutputs(graph, destinations, mapOf(output.outputId to replacement))

        assertFalse(oldLease.isCurrent)
        assertFalse(newLease.isCurrent)
        assertFalse(bridge.snapshot().backendCurrent)
        assertTrue(bridge.snapshot().routes.isEmpty())
        assertEquals(1, bridge.snapshot().retiredInstallationCount)
        assertThrows(IllegalStateException::class.java) { binder.routeSnapshot() }
        assertTrue(old.isValid)
        assertTrue(replacement.isValid)

        bridge.close()
        assertFalse(old.isValid)
        assertFalse(replacement.isValid)
        binder.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun oneAndroidBinderCannotBeOwnedByTwoConcurrentBridges() {
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(BatchDriver()))
        val graph = UltraDisplayGraphRegistry().replace(emptyList(), emptyList())
        val binder = AndroidUltraEglBatchBinder(backend, graph)
        val first = UltraDisplayBindingBatchBridge(binder)

        assertThrows(IllegalStateException::class.java) {
            UltraDisplayBindingBatchBridge(binder)
        }
        assertThrows(IllegalStateException::class.java) { binder.close() }
        first.close()

        val second = UltraDisplayBindingBatchBridge(binder)
        second.close()
        binder.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun ownedAndroidTargetRetriesFailedNativeRelease() {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        val surface = unsafe.allocateInstance(RetryReleaseSurface::class.java) as RetryReleaseSurface
        val target = AndroidDisplaySurfaceTarget(
            surface,
            DisplaySurfaceDescriptor(DisplaySurfaceId("owned-retry"), size),
            AndroidSurfaceOwnership.OWNED,
        )

        assertThrows(IllegalStateException::class.java) { target.close() }
        assertTrue(target.isValid)
        target.close()
        assertFalse(target.isValid)
        assertEquals(2, surface.releaseCalls)
        target.close()
        assertEquals(2, surface.releaseCalls)
    }

    @Test fun secondCandidateFailureLeavesEntireOldGenerationAndOwnershipCurrent() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val old = requests("old")
        val oldLease = backend.replaceOutputs(old)
        val candidates = requests("candidate")
        driver.failCreateAt = 4 // First replacement Surface is staged; the second fails.

        assertThrows(IllegalStateException::class.java) {
            backend.replaceOutputs(candidates)
        }

        assertTrue(oldLease.isCurrent)
        assertEquals(2, backend.diagnostics().outputCount)
        assertEquals(UltraEglBackendPhase.OPEN, backend.diagnostics().phase)
        assertEquals(old.map { it.target.surface }.toSet(), driver.live.map { it.window }.toSet())
        old.forEach { assertOwned(it.target) }
        candidates.forEach { assertUnowned(it.target) }
        oldLease.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun replacementRetiresOnlyStaleLeaseAndKeepsNewRoutesCurrent() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val old = requests("old")
        val replacement = requests("new")
        val stale = backend.replaceOutputs(old)
        val current = backend.replaceOutputs(replacement)

        assertFalse(stale.isCurrent)
        assertTrue(current.isCurrent)
        assertEquals(4, driver.live.size) // Old Surfaces are retained until their fence completes.
        stale.close()
        stale.close()
        assertTrue(current.isCurrent)
        assertEquals(replacement.map { it.target.surface }.toSet(), driver.live.map { it.window }.toSet())
        old.forEach { assertUnowned(it.target) }
        replacement.forEach { assertOwned(it.target) }

        current.close()
        assertFalse(current.isCurrent)
        assertEquals(0, backend.diagnostics().outputCount)
        assertEquals(0, driver.live.size)
        replacement.forEach { assertUnowned(it.target) }
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun exactPersistentTargetsTransferNativeOutputsAcrossGenerations() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val sameTargets = requests("persistent")
        val stale = backend.replaceOutputs(sameTargets)
        val current = backend.replaceOutputs(sameTargets)

        assertFalse(stale.isCurrent)
        assertTrue(current.isCurrent)
        assertEquals(2, driver.createCalls)
        assertEquals(2, driver.live.size)
        stale.close()
        assertEquals(2, driver.live.size)
        sameTargets.forEach { assertOwned(it.target) }
        current.close()
        assertEquals(0, driver.live.size)
        sameTargets.forEach { assertUnowned(it.target) }
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun rewrappedSameSurfaceCannotMasqueradeAsExactTransfer() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val original = requests("persistent").take(1)
        val old = backend.replaceOutputs(original)
        val prior = original.single()
        val alias = AndroidDisplaySurfaceTarget(prior.target.surface, prior.target.descriptor)

        assertThrows(IllegalArgumentException::class.java) {
            backend.replaceOutputs(listOf(prior.copy(target = alias)))
        }

        assertTrue(old.isCurrent)
        assertEquals(1, driver.createCalls)
        assertOwned(prior.target)
        assertUnowned(alias)
        old.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun failedNewSecondOutputDoesNotDestroyTransferredFirstOldOutput() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val old = requests("old")
        val oldLease = backend.replaceOutputs(old)
        val newSecond = requests("candidate")[1]
        driver.failCreateAt = 3

        assertThrows(IllegalStateException::class.java) {
            backend.replaceOutputs(listOf(old[0], newSecond))
        }

        assertTrue(oldLease.isCurrent)
        assertEquals(old.map { it.target.surface }.toSet(), driver.live.map { it.window }.toSet())
        old.forEach { assertOwned(it.target) }
        assertUnowned(newSecond.target)
        oldLease.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun orphanedNativeCandidateRetainsAllClaimsUntilFullDriverProof() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val old = requests("old")
        backend.replaceOutputs(old)
        val candidates = requests("orphan")
        driver.failRetainedCreateAt = 4

        assertThrows(UltraEglBatchCommitUncertainException::class.java) {
            backend.replaceOutputs(candidates)
        }

        assertEquals(UltraEglBackendPhase.RETIREMENT_UNCERTAIN, backend.diagnostics().phase)
        old.forEach { assertOwned(it.target) }
        candidates.forEach { assertOwned(it.target) }
        assertTrue(driver.live.size >= 3)
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
        old.forEach { assertUnowned(it.target) }
        candidates.forEach { assertUnowned(it.target) }
        assertTrue(driver.live.isEmpty())
    }

    @Test fun failedRetirementKeepsExactOldTargetsClaimedUntilRetryProof() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val old = requests("old")
        val new = requests("new")
        val oldLease = backend.replaceOutputs(old)
        val newLease = backend.replaceOutputs(new)
        driver.failNextDestroy = true

        assertThrows(IllegalStateException::class.java) { oldLease.close() }
        old.forEach { assertOwned(it.target) }
        assertEquals(4, driver.live.size)
        oldLease.close()
        old.forEach { assertUnowned(it.target) }
        assertEquals(new.map { it.target.surface }.toSet(), driver.live.map { it.window }.toSet())
        // A native retirement failure makes the renderer retirement-only, not a false success.
        assertFalse(newLease.isCurrent)
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
        new.forEach { assertUnowned(it.target) }
    }

    @Test fun productionBridgePublishesOnlyCompleteEglAndRouterGenerations() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val bindings = listOf(DisplayDestination.MAIN, DisplayDestination.CLUSTER).mapIndexed {
            index, _ ->
            UltraDisplayBinding(
                uiSyncDisplayId = UltraUiSyncDisplayId("ui-$index"),
                topologyDisplayId = UltraTopologyDisplayId(UUID(0L, index.toLong() + 1L)),
                airPlayStreamIdentity = UltraAirPlayStreamIdentity(
                    type = 100 + index,
                    streamConnectionId = "stream-$index",
                ),
                outputId = UltraDisplayOutputId.Android("output-$index"),
            )
        }
        val resources = UltraDisplayBindingResources(
            uiSyncDisplayIds = bindings.map { it.uiSyncDisplayId },
            topologyDisplayIds = bindings.map { it.topologyDisplayId },
            airPlayStreamIdentities = bindings.map { it.airPlayStreamIdentity },
            outputIds = bindings.map { it.outputId },
        )
        val registry = UltraDisplayBindingRegistry()
        val graph = UltraDisplayGraphRegistry().replace(
            bindings.map {
                UltraDisplayGraphOutput(it.topologyDisplayId, it.outputId, it.uiSyncDisplayId)
            },
            emptyList(),
        )
        val binder = AndroidUltraEglBatchBinder(backend, graph)
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val destinations = bindings.mapIndexed { index, binding ->
            binding.outputId to if (index == 0) DisplayDestination.MAIN else DisplayDestination.CLUSTER
        }.toMap()
        fun targets(prefix: String): Map<UltraDisplayOutputId, DisplaySurfaceTarget> =
            bindings.mapIndexed { index, binding ->
                binding.outputId to AndroidDisplaySurfaceTarget(
                    validSurface(),
                    DisplaySurfaceDescriptor(DisplaySurfaceId("$prefix-$index"), size),
                ) as DisplaySurfaceTarget
            }.toMap()

        val oldTargets = targets("old")
        val old = bridge.replace(registry.replace(resources, bindings), destinations, oldTargets)
        assertTrue(old.isCurrent)
        assertEquals(2, binder.routeSnapshot().routes.size)
        assertEquals(1, binder.routeSnapshot().routes.map { it.generation }.toSet().size)

        val newSnapshot = registry.replace(resources, bindings)
        val candidateTargets = targets("candidate")
        driver.failCreateAt = 4
        assertThrows(IllegalStateException::class.java) {
            bridge.replace(newSnapshot, destinations, candidateTargets)
        }
        assertTrue(old.isCurrent)
        assertEquals(old.generation.value, bridge.snapshot().generation)
        assertEquals(2, binder.routeSnapshot().routes.size)
        oldTargets.values.forEach { assertTrue(it.isValid) }
        candidateTargets.values.forEach { assertUnowned(it as AndroidDisplaySurfaceTarget) }

        driver.failCreateAt = null
        val current = bridge.replace(newSnapshot, destinations, candidateTargets)
        assertFalse(old.isCurrent)
        assertTrue(current.isCurrent)
        assertEquals(2, binder.routeSnapshot().routes.size)
        oldTargets.values.forEach { assertFalse(it.isValid) }
        candidateTargets.values.forEach { assertTrue(it.isValid) }
        old.close() // Stale bridge lease cannot detach the replacement.
        assertTrue(current.isCurrent)
        bridge.close()
        candidateTargets.values.forEach { assertFalse(it.isValid) }
        binder.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    @Test fun productionBridgeReusesSamePhysicalTargetsWithoutStaleLeaseClosingThem() {
        val driver = BatchDriver()
        val backend = AndroidUltraEglBackend(UltraEglRenderThread(driver))
        val binding = UltraDisplayBinding(
            uiSyncDisplayId = UltraUiSyncDisplayId("persistent-ui"),
            topologyDisplayId = UltraTopologyDisplayId(UUID(0L, 17L)),
            airPlayStreamIdentity = UltraAirPlayStreamIdentity(100, "persistent-stream"),
            outputId = UltraDisplayOutputId.Android("persistent-output"),
        )
        val resources = UltraDisplayBindingResources(
            uiSyncDisplayIds = listOf(binding.uiSyncDisplayId),
            topologyDisplayIds = listOf(binding.topologyDisplayId),
            airPlayStreamIdentities = listOf(binding.airPlayStreamIdentity),
            outputIds = listOf(binding.outputId),
        )
        val registry = UltraDisplayBindingRegistry()
        val graph = UltraDisplayGraphRegistry().replace(
            listOf(UltraDisplayGraphOutput(
                binding.topologyDisplayId, binding.outputId, binding.uiSyncDisplayId,
            )),
            emptyList(),
        )
        val binder = AndroidUltraEglBatchBinder(backend, graph)
        val bridge = UltraDisplayBindingBatchBridge(binder)
        val target = AndroidDisplaySurfaceTarget(
            validSurface(),
            DisplaySurfaceDescriptor(DisplaySurfaceId("persistent-surface"), size),
        )
        val exactGraphOutput = checkNotNull(graph.output(binding.outputId))
        val directRequest = AndroidUltraEglOutputBatchRequest(
            DisplayDestination.MAIN,
            exactGraphOutput,
            target,
        )
        assertThrows(IllegalStateException::class.java) {
            backend.replaceOutputs(listOf(directRequest))
        }
        assertThrows(IllegalStateException::class.java) {
            backend.attachOutput(DisplayDestination.MAIN, exactGraphOutput, target)
        }
        assertThrows(IllegalStateException::class.java) {
            AndroidUltraEglBatchBinder(backend, graph)
        }
        val destinations = mapOf(binding.outputId to DisplayDestination.MAIN)
        val targets = mapOf<UltraDisplayOutputId, DisplaySurfaceTarget>(binding.outputId to target)
        val stale = bridge.replace(registry.replace(resources, listOf(binding)), destinations, targets)
        val current = bridge.replace(registry.replace(resources, listOf(binding)), destinations, targets)

        assertFalse(stale.isCurrent)
        assertTrue(current.isCurrent)
        assertTrue(target.isValid)
        assertEquals(1, driver.createCalls)
        assertEquals(1, driver.live.size)
        assertThrows(IllegalStateException::class.java) {
            backend.replaceOutputs(listOf(directRequest))
        }
        stale.close()
        assertTrue(target.isValid)
        assertEquals(1, binder.routeSnapshot().routes.size)
        bridge.close()
        assertFalse(target.isValid)
        binder.close()
        binder.close() // Ownership release is idempotent.
        val externalTarget = AndroidDisplaySurfaceTarget(
            validSurface(),
            DisplaySurfaceDescriptor(DisplaySurfaceId("after-binder"), size),
        )
        val external = backend.replaceOutputs(listOf(directRequest.copy(target = externalTarget)))
        assertTrue(external.isCurrent)
        external.close()
        assertEquals(UltraEglBackendRetirementResult.RETIRED, backend.retire())
    }

    private fun requests(prefix: String): List<AndroidUltraEglOutputBatchRequest> =
        listOf(DisplayDestination.MAIN, DisplayDestination.CLUSTER).mapIndexed { index, destination ->
            AndroidUltraEglOutputBatchRequest(
                destination,
                UltraDisplayGraphOutput(
                    UltraTopologyDisplayId(UUID(0L, index.toLong() + 1L)),
                    UltraDisplayOutputId.Android("$prefix-$index"),
                ),
                AndroidDisplaySurfaceTarget(
                    validSurface(),
                    DisplaySurfaceDescriptor(DisplaySurfaceId("$prefix-surface-$index"), size),
                ),
            )
        }

    private fun assertOwned(target: AndroidDisplaySurfaceTarget) {
        assertThrows(IllegalStateException::class.java) { target.ownership.claim(Any()) }
    }

    private fun assertUnowned(target: AndroidDisplaySurfaceTarget) {
        val probe = Any()
        assertTrue(target.ownership.claim(probe))
        target.ownership.release(probe)
    }

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private class RetryReleaseSurface : Surface(null as SurfaceTexture?) {
        var releaseCalls = 0
        override fun isValid() = true
        override fun release() {
            releaseCalls++
            if (releaseCalls == 1) throw IllegalStateException("native release failed")
        }
    }

    private fun validSurface(): Surface {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(ValidSurface::class.java) as Surface
    }

    private class BatchDriver : UltraEglDriver {
        class Output(val window: Any) : UltraEglDriverOutput

        val live = LinkedHashSet<Output>()
        var createCalls = 0
        var failCreateAt: Int? = null
        var failRetainedCreateAt: Int? = null
        var failNextDestroy = false

        override fun initialize() = Unit
        override fun createExternalIngress(
            key: UltraEglIngressKey,
            sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit,
        ): UltraEglDriverIngress = error("No ingress in batch test")
        override fun updateIngressFrame(ingress: UltraEglDriverIngress) = Unit
        override fun createWindowOutput(
            destination: DisplayDestination,
            nativeWindow: Any,
            sizePixels: PixelSize,
        ): UltraEglDriverOutput {
            createCalls++
            if (createCalls == failCreateAt) throw IllegalStateException("second Surface failed")
            if (createCalls == failRetainedCreateAt) {
                live += Output(nativeWindow)
                throw UltraEglCandidateResourceRetainedException(
                    IllegalStateException("candidate creation failed"),
                    IllegalStateException("eglDestroySurface failed"),
                )
            }
            return Output(nativeWindow).also(live::add)
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
                throw IllegalStateException("native retirement failed")
            }
            live.remove(output)
        }
        override fun closeWithProof(deadlineNanos: Long): UltraEglDriverRetirementProof {
            live.clear()
            return UltraEglDriverRetirementProof(retired = true)
        }
    }
}
