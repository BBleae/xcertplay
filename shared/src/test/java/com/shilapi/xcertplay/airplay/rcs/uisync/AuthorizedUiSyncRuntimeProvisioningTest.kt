package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedUiSyncRuntimeProvisioningTest {
    @Test
    fun preparedInputsPreserveLegacyFingerprintsAndExactPlanIdentity() {
        val info = AirPlayUiSyncInfo(mapOf("renderer" to "authorized-complete"))
        val displays = listOf(displaySpec())
        val plan = staticPlan()
        val inputs = UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(info, displays, plan)
        val old = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            uiSyncInfo = info,
            displays = displays,
            staticPlan = plan,
            rendererCapability = TestCompleteCapability(),
            callbackExecutor = TestBoundedExecutor(),
            authorizationExpectation = expectation(),
        )
        val capability = TestCompleteCapability()
        val prepared = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            inputs = inputs,
            rendererCapability = capability,
            callbackExecutor = TestBoundedExecutor(),
            authorizationExpectation = expectation(),
        )

        assertEquals(old.schemaFingerprint, inputs.schemaFingerprint)
        assertEquals(old.bindingFingerprint, inputs.bindingFingerprint)
        assertEquals(old.schemaFingerprint, prepared.schemaFingerprint)
        assertEquals(old.bindingFingerprint, prepared.bindingFingerprint)
        assertSame(inputs.schemaFingerprint, prepared.schemaFingerprint)
        assertSame(inputs.bindingFingerprint, prepared.bindingFingerprint)
        assertSame(plan, inputs.staticPlan)
        assertSame(plan, prepared.staticPlan)
        assertSame(plan, capability.lastContext?.staticPlan)
        assertFalse(prepared.rendererReadiness.isComplete)

        prepared.close()
        old.close()
    }

    @Test
    fun preparedInputsDetachMutableSourceAndReturnedSnapshots() {
        val infoBytes = byteArrayOf(1, 2, 3)
        val resetBytes = byteArrayOf(4, 5, 6)
        val infoMap = mutableMapOf<String, Any?>("opaque" to infoBytes)
        val displays = mutableListOf(
            UiSyncDisplayRuntimeSpec(
                descriptor = CarPlayUiSyncChannelDescriptor(DISPLAY_ID, "authorized-vehicle"),
                mode = CarPlayUiSyncMode.PASSIVE,
                outboundResetConfig = mutableMapOf("payload" to resetBytes),
            ),
        )
        val plan = staticPlan()
        val inputs = UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(
            AirPlayUiSyncInfo(infoMap), displays, plan,
        )
        val fingerprint = inputs.schemaFingerprint.bytes()

        infoBytes[0] = 99
        resetBytes[0] = 99
        infoMap["late"] = true
        displays += displaySpec("late-display")
        val returnedInfo = inputs.uiSyncInfo().toWireMap()["opaque"] as ByteArray
        returnedInfo[1] = 99
        val returnedReset = inputs.displaySpecs().single().outboundResetConfig() as Map<*, *>
        (returnedReset["payload"] as ByteArray)[1] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3),
            inputs.uiSyncInfo().toWireMap()["opaque"] as ByteArray)
        assertArrayEquals(byteArrayOf(4, 5, 6),
            (inputs.displaySpecs().single().outboundResetConfig() as Map<*, *>)["payload"] as ByteArray)
        assertEquals(listOf(DISPLAY_ID), inputs.displaySpecs().map { it.descriptor.displayId })
        assertArrayEquals(fingerprint, inputs.schemaFingerprint.bytes())
        assertSame(plan, inputs.staticPlan)
    }

    @Test
    fun preparedInputsRejectForeignPhysicalAssignmentsBeforePreflightOrRendererAcquire() {
        val plan = staticPlan()
        val foreignPlan = staticPlan()
        val inputs = UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(
            AirPlayUiSyncInfo(), listOf(displaySpec()), plan,
        )
        val capability = TestCompleteCapability()
        var preflights = 0
        val authority = object : UiSyncPhysicalOutputAuthority {
            override fun preflight(context: UiSyncPhysicalOutputPreflightContext):
                UiSyncPhysicalOutputAvailability? {
                preflights++
                return null
            }

            override fun bindSession(context: UiSyncPhysicalOutputSessionContext):
                UiSyncPhysicalOutputBatch? = null
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
                inputs = inputs,
                rendererCapability = capability,
                callbackExecutor = TestBoundedExecutor(),
                authorizationExpectation = expectation(physicalRequirement(foreignPlan)),
                physicalOutputAuthority = authority,
            )
        }
        assertEquals(0, preflights)
        assertEquals(0, capability.acquireCount)
    }

    @Test
    fun preparedInputsReachPreflightExactlyAndAbsentOfferFailsClosed() {
        val plan = staticPlan()
        val inputs = UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(
            AirPlayUiSyncInfo(mapOf("renderer" to "authorized-complete")),
            listOf(displaySpec()), plan,
        )
        val requirement = physicalRequirement(plan)
        var observed: UiSyncPhysicalOutputPreflightContext? = null
        val authority = object : UiSyncPhysicalOutputAuthority {
            override fun preflight(context: UiSyncPhysicalOutputPreflightContext):
                UiSyncPhysicalOutputAvailability? {
                observed = context
                return null
            }

            override fun bindSession(context: UiSyncPhysicalOutputSessionContext):
                UiSyncPhysicalOutputBatch? = null
        }
        val provisioning = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            inputs = inputs,
            rendererCapability = TestCompleteCapability(),
            callbackExecutor = TestBoundedExecutor(),
            authorizationExpectation = expectation(requirement),
            physicalOutputAuthority = authority,
        )

        assertSame(requirement, observed?.requirement)
        assertSame(plan, observed?.staticPlan)
        assertSame(inputs.schemaFingerprint, observed?.schemaFingerprint)
        assertSame(inputs.bindingFingerprint, observed?.bindingFingerprint)
        assertFalse(provisioning.rendererReadiness.isComplete)
        assertTrue(provisioning.authorizationSnapshot() != null)
        provisioning.close()
        assertNull(provisioning.authorizationSnapshot())
        assertThrows(IllegalStateException::class.java) { provisioning.uiSyncInfo() }
    }

    @Test
    fun completeDiagnosticBuilderDoesNotProduceAuthorizationEvidence() {
        val capability = TestCompleteCapability()
        val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "diagnostic-complete")),
            displays = listOf(displaySpec()),
            staticPlan = staticPlan(),
            rendererCapability = capability,
            callbackExecutor = TestBoundedExecutor(),
        )

        assertTrue(!provisioning.rendererReadiness.isComplete)
        assertNull(provisioning.authorizationSnapshot())

        provisioning.close()
    }

    @Test
    fun authorizedBuilderRequiresExactRendererImplementationAndProgram() {
        assertThrows(IllegalArgumentException::class.java) {
            authorizedProvisioning(
                capability = TestCompleteCapability(implementationId = "test.renderer.other"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            authorizedProvisioning(
                capability = TestCompleteCapability(programFingerprint = DIFFERENT_PROGRAM),
            )
        }
    }

    @Test
    fun authorizationSnapshotIsExactImmutableAndInvalidatedByReceiptChange() {
        val outputs = mutableListOf(output("cluster", DISPLAY_ID, 1))
        val ingresses = mutableListOf(ingress("cluster-video"))
        val plan = UiSyncRendererStaticPlan(outputs, ingresses)
        val displays = mutableListOf(displaySpec())
        val capability = TestCompleteCapability()
        val provisioning = authorizedProvisioning(capability, displays, plan)

        outputs += output("late-output", null, 2)
        ingresses += ingress("late-video")
        displays += displaySpec("late-display")

        val snapshot = requireNotNull(provisioning.authorizationSnapshot())
        assertEquals(setOf(DISPLAY_ID), snapshot.displayIds)
        assertEquals(listOf(UltraDisplayOutputId.Custom("cluster")), snapshot.outputs.map { it.outputId })
        assertEquals(listOf(UltraIngressId("cluster-video")), snapshot.ingressSpecs.map { it.id })
        assertEquals(EXPECTED_IMPLEMENTATION, snapshot.rendererImplementationId)
        assertEquals(EXPECTED_PROGRAM, snapshot.semanticThemeProgramFingerprint)

        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.displayIds as MutableSet<String>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.outputs as MutableList<UltraDisplayGraphOutput>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.ingressSpecs as MutableList<UiSyncRendererIngressSpec>).clear()
        }

        capability.invalidateFactoryReceipt()
        assertNull(provisioning.authorizationSnapshot())
        provisioning.close()
    }

    @Test
    fun authorizationSnapshotIsRevokedByOwnershipTransfer() {
        val provisioning = authorizedProvisioning(TestCompleteCapability())
        assertTrue(provisioning.authorizationSnapshot() != null)

        val owner = provisioning.transferToRuntime()

        assertNull(provisioning.authorizationSnapshot())
        owner.close()
    }

    @Test
    fun authorizationSnapshotDoesNotTrustCollectionSize() {
        fun <T> hostileSize(value: T, label: String): Collection<T> =
            object : kotlin.collections.AbstractCollection<T>() {
                override val size: Int
                    get() = throw AssertionError("authorization must not read $label size")

                override fun iterator(): Iterator<T> = listOf(value).iterator()
            }

        val output = output("cluster", DISPLAY_ID, 1)
        val ingress = ingress("cluster-video")
        val snapshot = UiSyncRuntimeAuthorizationSnapshot(
            schemaFingerprint = UiSyncSchemaFingerprint(ByteArray(32) { 1 }),
            bindingFingerprint = UiSyncBindingFingerprint(ByteArray(32) { 2 }),
            semanticThemeProgramFingerprint = EXPECTED_PROGRAM,
            rendererImplementationId = EXPECTED_IMPLEMENTATION,
            displayIds = hostileSize(DISPLAY_ID, "display IDs"),
            outputs = hostileSize(output, "outputs"),
            ingressSpecs = hostileSize(ingress, "ingresses"),
        )

        assertEquals(setOf(DISPLAY_ID), snapshot.displayIds)
        assertEquals(listOf(output), snapshot.outputs)
        assertEquals(listOf(ingress), snapshot.ingressSpecs)
    }

    private fun authorizedProvisioning(
        capability: TestCompleteCapability,
        displays: Collection<UiSyncDisplayRuntimeSpec> = listOf(displaySpec()),
        staticPlan: UiSyncRendererStaticPlan = staticPlan(),
    ): UiSyncRuntimeProvisioning = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
        uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "authorized-complete")),
        displays = displays,
        staticPlan = staticPlan,
        rendererCapability = capability,
        callbackExecutor = TestBoundedExecutor(),
        authorizationExpectation = expectation(),
    )

    private fun expectation(
        requirement: UiSyncPhysicalOutputRequirement? = null,
    ) = UiSyncRuntimeAuthorizationExpectation(
        semanticThemeProgramFingerprint = EXPECTED_PROGRAM,
        rendererImplementationId = EXPECTED_IMPLEMENTATION,
        physicalOutputRequirement = requirement,
    )

    private fun physicalRequirement(plan: UiSyncRendererStaticPlan) =
        UiSyncPhysicalOutputRequirement(
            verifiedProfileDigest = UltraProfileDigest(ByteArray(32) { 0x5a }),
            assignments = plan.outputs.mapIndexed { index, graphOutput ->
                UiSyncPhysicalOutputAssignment(
                    graphOutput,
                    UiSyncPhysicalDestination(
                        UiSyncPhysicalDestinationRole.AUXILIARY,
                        "prepared-physical-$index",
                    ),
                )
            },
        )

    private class TestCompleteCapability(
        private val implementationId: String = EXPECTED_IMPLEMENTATION,
        private val programFingerprint: UiSyncSemanticThemeProgramFingerprint = EXPECTED_PROGRAM,
    ) : UiSyncCompleteRendererCapability {
        private var factory: TestFactory? = null
        var acquireCount = 0
            private set
        var lastContext: UiSyncCompleteRendererBindingContext? = null
            private set

        fun invalidateFactoryReceipt() {
            checkNotNull(factory).receiptChanged = true
        }

        override fun acquire(
            context: UiSyncCompleteRendererBindingContext,
        ): UiSyncCompleteRendererFactoryLease = TestFactory(
            context = context,
            implementationId = implementationId,
            programFingerprint = programFingerprint,
        ).also {
            factory = it
            acquireCount++
            lastContext = context
        }
    }

    private class TestFactory(
        private val context: UiSyncCompleteRendererBindingContext,
        override val implementationId: String,
        private val programFingerprint: UiSyncSemanticThemeProgramFingerprint,
    ) : UiSyncCompleteRendererFactoryLease {
        private var closed = false
        var receiptChanged = false
        private val originalReceipt = receipt(context, programFingerprint, backendLive = true)
        private val changedReceipt = receipt(context, programFingerprint, backendLive = false)

        override val isLive: Boolean
            get() = !closed
        override val schemaFingerprint: UiSyncSchemaFingerprint = context.schemaFingerprint
        override val bindingFingerprint: UiSyncBindingFingerprint = context.bindingFingerprint
        override val displayIds: Set<String> = context.displayIds
        override val readinessReceipt: RendererReadinessReceipt
            get() = if (receiptChanged) changedReceipt else originalReceipt

        override fun openSession(
            context: UiSyncCompleteRendererSessionBindingContext,
        ): UiSyncCompleteRendererSessionLease = TestSession(
            context = context,
            implementationId = implementationId,
            programFingerprint = programFingerprint,
        )

        override fun close() {
            closed = true
        }
    }

    private class TestSession(
        private val context: UiSyncCompleteRendererSessionBindingContext,
        override val implementationId: String,
        programFingerprint: UiSyncSemanticThemeProgramFingerprint,
    ) : UiSyncCompleteRendererSessionLease {
        private var closed = false
        override val isLive: Boolean
            get() = !closed
        override val schemaFingerprint: UiSyncSchemaFingerprint = context.schemaFingerprint
        override val bindingFingerprint: UiSyncBindingFingerprint = context.bindingFingerprint
        override val displayIds: Set<String> = context.displayIds
        override val readinessReceipt: RendererReadinessReceipt =
            receipt(context, programFingerprint, backendLive = true)

        override fun prepareScreen(
            context: UiSyncRendererScreenContext,
        ): UiSyncRendererScreenCandidate? = null

        override fun retireScreens(): Boolean = true

        override fun close() {
            closed = true
        }
    }

    private class TestBoundedExecutor : UiSyncBoundedPresentationExecutor {
        private val pending = ArrayDeque<Runnable>()
        override val maximumPendingCallbacks: Int = 64
        override val parallelism: Int = 1
        override val mayExecuteInline: Boolean = false

        override fun execute(command: Runnable) {
            if (pending.size >= maximumPendingCallbacks) {
                throw RejectedExecutionException("test callback queue is full")
            }
            pending.addLast(command)
        }
    }

    private companion object {
        const val DISPLAY_ID = "authorized.cluster"
        const val EXPECTED_IMPLEMENTATION = "test.renderer.authorized.v1"
        val EXPECTED_PROGRAM = UiSyncSemanticThemeProgramFingerprint(
            ByteArray(32) { index -> (index + 1).toByte() },
        )
        val DIFFERENT_PROGRAM = UiSyncSemanticThemeProgramFingerprint(
            ByteArray(32) { index -> (0x7f - index).toByte() },
        )

        fun displaySpec(displayId: String = DISPLAY_ID) = UiSyncDisplayRuntimeSpec(
            descriptor = CarPlayUiSyncChannelDescriptor(displayId, "authorized-vehicle"),
            mode = CarPlayUiSyncMode.PASSIVE,
        )

        fun staticPlan() = UiSyncRendererStaticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = listOf(ingress("cluster-video")),
        )

        fun output(
            outputId: String,
            displayId: String?,
            uuidTail: Int,
        ) = UltraDisplayGraphOutput(
            topologyDisplayId = UltraTopologyDisplayId(
                UUID.fromString("00000000-0000-0000-0000-%012d".format(uuidTail)),
            ),
            outputId = UltraDisplayOutputId.Custom(outputId),
            uiSyncDisplayId = displayId?.let(::UltraUiSyncDisplayId),
        )

        fun ingress(id: String) = UiSyncRendererIngressSpec(
            id = UltraIngressId(id),
            airPlayStreamIdentity = UltraAirPlayStreamIdentity(
                type = 111,
                streamConnectionId = "$id-connection",
            ),
        )

        fun receipt(
            context: UiSyncCompleteRendererBindingContext,
            programFingerprint: UiSyncSemanticThemeProgramFingerprint,
            backendLive: Boolean,
        ): RendererReadinessReceipt = context.issueReadinessReceipt(
            semanticThemeProgramFingerprint = programFingerprint,
            subscribedDisplayIds = context.displayIds,
            boundOutputIds = context.staticPlan.outputs.map { it.outputId },
            semanticCompilerActive = true,
            themeCompilerActive = true,
            backendLive = backendLive,
        )

        fun receipt(
            context: UiSyncCompleteRendererSessionBindingContext,
            programFingerprint: UiSyncSemanticThemeProgramFingerprint,
            backendLive: Boolean,
        ): RendererReadinessReceipt = context.issueReadinessReceipt(
            semanticThemeProgramFingerprint = programFingerprint,
            subscribedDisplayIds = context.displayIds,
            boundOutputIds = context.staticPlan.outputs.map { it.outputId },
            semanticCompilerActive = true,
            themeCompilerActive = true,
            backendLive = backendLive,
        )
    }
}
