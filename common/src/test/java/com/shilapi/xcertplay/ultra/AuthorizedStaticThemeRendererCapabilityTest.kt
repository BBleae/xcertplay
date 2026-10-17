package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.CompiledUiSyncThemeVariant
import com.shilapi.xcertplay.deriveVerifiedUiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncAuthorizedStaticInputs
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedStaticThemeRendererCapabilityTest {
    private val schema = fingerprint<UiSyncSchemaFingerprint>(1)
    private val binding = fingerprint<UiSyncBindingFingerprint>(2)
    private val program = UiSyncSemanticThemeProgramFingerprint(ByteArray(32) { 3 })
    private val output = UltraDisplayGraphOutput(
        UltraTopologyDisplayId(UUID(0, 71)), UltraDisplayOutputId.Android("main"),
        UltraUiSyncDisplayId("main-display"),
    )
    private val plan = UiSyncRendererStaticPlan(listOf(output), emptyList())
    private val displayIds = setOf("main-display")

    @Test
    fun preManifestCapabilityCompilesEverySelectedVariantAndProvesLiveFactoryAndChild() {
        val profile = verifiedProfile(staticPackage())
        val assignments = listOf(assignment(profile))
        val inputs = inputs()
        val lease = FactoryBackend()
        val sources = ArrayList<Source>()
        val capability = AuthorizedStaticThemeRendererCapability(
            profile, assignments, inputs, null, { lease },
            { context -> AndroidUltraRendererSessionCoordinator(context, Backend()) }, Unit,
        )
        val factory = capability.acquire(factoryContext(inputs))
        val source = Source().also(sources::add)
        val child = factory.openSession(sessionContext(source, inputs)) as
            StaticThemeRendererSession
        try {
            assertEquals(AndroidUltraRendererSessionCoordinator.IMPLEMENTATION_ID,
                factory.implementationId)
            assertEquals(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
                profile, assignments), factory.readinessReceipt?.semanticThemeProgramFingerprint)
            assertEquals(RendererReadinessReceipt.Phase.STATIC_FACTORY,
                factory.readinessReceipt?.phase)
            assertTrue(factory.readinessReceipt?.semanticCompilerActive == true)
            assertTrue(factory.readinessReceipt?.themeCompilerActive == true)
            assertTrue(factory.readinessReceipt?.backendLive == true)
            assertEquals(RendererReadinessReceipt.Phase.SESSION_IMPLEMENTATION,
                child.readinessReceipt?.phase)
            assertTrue(child.readinessReceipt?.themeCompilerActive == true)
            assertTrue(child.readinessReceipt?.backendLive == true)
            assertEquals(1, source.subscriptions)
            assertEquals(1, child.coordinator
                .stateSnapshot().presentationSubscriptionCount)
            assertEquals(0, child.coordinator.stateSnapshot().outputs.size)
        } finally {
            factory.close()
            sources.forEach(Source::close)
        }
        assertEquals(1, lease.closeCount)
        assertFalse(lease.isLive)
    }

    @Test
    fun preManifestCapabilityRejectsUnsupportedAlternateEvenWhenPrimaryIsExecutable() {
        val profile = verifiedProfile(staticPackage(unsupportedSecond = true))
        val inputs = inputs()
        val backend = FactoryBackend()
        assertThrows(IllegalArgumentException::class.java) {
            AuthorizedStaticThemeRendererCapability(
                profile, listOf(assignment(profile)), inputs, null, { backend },
                { context -> AndroidUltraRendererSessionCoordinator(context, Backend()) }, Unit,
            )
        }
        assertEquals(0, backend.closeCount)
    }

    @Test
    fun preManifestCapabilityRejectsEquivalentButNonidenticalFrozenPlanBeforeBackendOpen() {
        val profile = verifiedProfile(staticPackage())
        val inputs = inputs()
        val backend = FactoryBackend()
        val capability = AuthorizedStaticThemeRendererCapability(
            profile, listOf(assignment(profile)), inputs, null, { backend },
            { context -> AndroidUltraRendererSessionCoordinator(context, Backend()) }, Unit,
        )
        val foreignPlan = UiSyncRendererStaticPlan(plan.outputs, plan.ingressSpecs)
        assertThrows(IllegalArgumentException::class.java) {
            capability.acquire(factoryContext(inputs, foreignPlan))
        }
        assertEquals(0, backend.closeCount)
    }

    @Test
    fun canonicalManifestOrderingStillRequiresSameExactOutputObjects() {
        val second = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0, 72)), UltraDisplayOutputId.Android("cluster"),
            UltraUiSyncDisplayId("cluster-display"),
        )
        assertTrue(sameExactOutputInstances(listOf(output, second), listOf(second, output)))
        val equivalentButForeign = UltraDisplayGraphOutput(
            output.topologyDisplayId, output.outputId, output.uiSyncDisplayId,
        )
        assertFalse(sameExactOutputInstances(
            listOf(output, second), listOf(second, equivalentButForeign),
        ))
        val mainIngress = UiSyncRendererIngressSpec(
            UltraIngressId("main-video"), UltraAirPlayStreamIdentity(110, "1"),
        )
        val clusterIngress = UiSyncRendererIngressSpec(
            UltraIngressId("cluster-video"), UltraAirPlayStreamIdentity(111, "2"),
        )
        assertTrue(sameIngressSpecs(
            listOf(mainIngress, clusterIngress), listOf(clusterIngress, mainIngress),
        ))
        assertFalse(sameIngressSpecs(
            listOf(mainIngress, clusterIngress), listOf(clusterIngress),
        ))
    }

    @Test
    fun unavailableFactoryBackendNeverIssuesACompleteReceiptOrOpensChild() {
        val profile = verifiedProfile(staticPackage())
        val inputs = inputs()
        val backend = FactoryBackend().apply { live = false }
        val capability = AuthorizedStaticThemeRendererCapability(
            profile, listOf(assignment(profile)), inputs, null, { backend },
            { context -> AndroidUltraRendererSessionCoordinator(context, Backend()) }, Unit,
        )
        val factory = capability.acquire(factoryContext(inputs))
        val source = Source()
        try {
            assertFalse(factory.isLive)
            assertFalse(factory.readinessReceipt?.backendLive == true)
            assertThrows(IllegalStateException::class.java) {
                factory.openSession(sessionContext(source, inputs))
            }
            assertEquals(0, source.subscriptions)
        } finally {
            factory.close()
            source.close()
        }
        assertEquals(1, backend.closeCount)
    }

    @Test
    fun factoryOwnsFailedEglProbeRetirementForRetryAndNeverReopens() {
        val profile = verifiedProfile(staticPackage())
        val inputs = inputs()
        val backend = FactoryBackend().apply { closeFailures = 1 }
        val capability = AuthorizedStaticThemeRendererCapability(
            profile, listOf(assignment(profile)), inputs, null, { backend },
            { context -> AndroidUltraRendererSessionCoordinator(context, Backend()) }, Unit,
        )
        val factory = capability.acquire(factoryContext(inputs))
        assertThrows(IllegalStateException::class.java) { factory.close() }
        assertFalse(factory.isLive)
        assertEquals(1, backend.closeCount)
        factory.close()
        assertEquals(2, backend.closeCount)
        assertFalse(backend.isLive)
    }

    @Test
    fun publishedExactVideoGraphRetriesPendingThemeOnceAndKeepsCandidateIdentity() {
        val handle = syntheticHandle()
        val ingress = UltraDisplayGraphIngress(UltraIngressId("video"), handle)
        val graphRegistry = UltraDisplayGraphRegistry()
        var graph = graphRegistry.replace(listOf(output), emptyList())
        var retries = 0
        val delegate = object : UiSyncRendererScreenCandidate {
            override val stream = handle
            override val ingressId = ingress.id
            override fun publish(): Boolean {
                graph = graphRegistry.replace(listOf(output), listOf(ingress))
                return true
            }
            override fun close() = Unit
        }
        val candidate = PendingThemeRetryScreenCandidate(
            { graph }, { throw AssertionError("Valid graph must not poison renderer", it) },
            ingress, delegate,
        ) { retries++ }
        assertSame(handle, candidate.stream)
        assertEquals(ingress.id, candidate.ingressId)
        assertTrue(candidate.publish())
        assertEquals(1, retries)
        assertTrue(candidate.publish())
        assertEquals(1, retries)
    }

    @Test
    fun failedVideoPublicationNeverRetriesAndCloseFailureRetainsSameDelegate() {
        val handle = syntheticHandle()
        val ingress = UltraDisplayGraphIngress(UltraIngressId("video"), handle)
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        var retries = 0
        var closeAttempts = 0
        val delegate = object : UiSyncRendererScreenCandidate {
            override val stream = handle
            override val ingressId = ingress.id
            override fun publish(): Boolean = false
            override fun close() {
                closeAttempts++
                if (closeAttempts == 1) throw IllegalStateException("retirement uncertain")
            }
        }
        val candidate = PendingThemeRetryScreenCandidate({ graph }, {}, ingress, delegate) {
            retries++
        }
        assertFalse(candidate.publish())
        assertEquals(0, retries)
        assertThrows(IllegalStateException::class.java) { candidate.close() }
        candidate.close()
        assertEquals(2, closeAttempts)
    }

    @Test
    fun publishClaimWithoutExactGraphPoisonsRendererBeforeAnyThemeRetry() {
        val handle = syntheticHandle()
        val ingress = UltraDisplayGraphIngress(UltraIngressId("video"), handle)
        val graph = UltraDisplayGraphRegistry().replace(listOf(output), emptyList())
        var retries = 0
        var failures = 0
        val delegate = object : UiSyncRendererScreenCandidate {
            override val stream = handle
            override val ingressId = ingress.id
            override fun publish(): Boolean = true
            override fun close() = Unit
        }
        val candidate = PendingThemeRetryScreenCandidate(
            { graph }, { failures++ }, ingress, delegate,
        ) { retries++ }
        assertThrows(IllegalStateException::class.java) { candidate.publish() }
        assertEquals(1, failures)
        assertEquals(0, retries)
    }

    @Test
    fun retryFailurePropagatesAfterExactPublishAndSameCandidateStillRetires() {
        val handle = syntheticHandle()
        val ingress = UltraDisplayGraphIngress(UltraIngressId("video"), handle)
        val registry = UltraDisplayGraphRegistry()
        var graph = registry.replace(listOf(output), emptyList())
        val retryFailure = IllegalStateException("AA05 activation revoked")
        var retirementCount = 0
        val delegate = object : UiSyncRendererScreenCandidate {
            override val stream = handle
            override val ingressId = ingress.id
            override fun publish(): Boolean {
                graph = registry.replace(listOf(output), listOf(ingress))
                return true
            }
            override fun close() { retirementCount++ }
        }
        val candidate = PendingThemeRetryScreenCandidate(
            { graph }, { throw AssertionError("Exact graph must not poison renderer", it) },
            ingress, delegate,
        ) { throw retryFailure }
        assertSame(retryFailure, assertThrows(IllegalStateException::class.java) {
            candidate.publish()
        })
        candidate.close()
        assertEquals(1, retirementCount)
    }

    @Test
    fun freshProbeChildrenSubscribeRealReplayWithoutClaimingOutputOrReady() {
        val backends = ArrayList<Backend>()
        val factory = StaticThemeRendererFactory(
            factoryContext(), program, IMPLEMENTATION, null,
        ) { context ->
            val backend = Backend()
            backends += backend
            AndroidUltraRendererSessionCoordinator(context, backend)
        }
        val firstSource = Source()
        val secondSource = Source()
        val first = factory.openSession(sessionContext(firstSource)) as StaticThemeRendererSession
        val second = factory.openSession(sessionContext(secondSource)) as StaticThemeRendererSession
        try {
            assertNotSame(first, second)
            assertNotSame(first.coordinator, second.coordinator)
            assertEquals(1, firstSource.subscriptions)
            assertEquals(1, secondSource.subscriptions)
            assertEquals(0, first.coordinator.stateSnapshot().outputs.size)
            assertEquals(0, second.coordinator.stateSnapshot().outputs.size)
            assertSame(factory.readinessReceipt, factory.readinessReceipt)
            assertSame(first.readinessReceipt, first.readinessReceipt)
            assertEquals(RendererReadinessReceipt.Phase.STATIC_FACTORY,
                factory.readinessReceipt?.phase)
            assertEquals(RendererReadinessReceipt.Phase.SESSION_IMPLEMENTATION,
                first.readinessReceipt?.phase)
            assertFalse(factory.readinessReceipt?.themeCompilerActive == true)
            assertFalse(first.readinessReceipt?.themeCompilerActive == true)
            assertTrue(first.isLive)
            assertTrue(second.isLive)
        } finally {
            factory.close()
            firstSource.close()
            secondSource.close()
        }
        assertFalse(first.isLive)
        assertFalse(second.isLive)
        assertEquals(1, firstSource.retirements)
        assertEquals(1, secondSource.retirements)
        assertEquals(listOf(1, 1), backends.map { it.retirements })
    }

    @Test
    fun childCloseRetainsExactCoordinatorForRetryAfterUnprovenEglRetirement() {
        val backend = Backend().apply { retireResult = UltraEglBackendRetirementResult.CONTROL_IN_FLIGHT }
        val source = Source()
        val factory = StaticThemeRendererFactory(factoryContext(), program, IMPLEMENTATION, null) {
            AndroidUltraRendererSessionCoordinator(it, backend)
        }
        val child = factory.openSession(sessionContext(source)) as StaticThemeRendererSession
        assertThrows(IllegalStateException::class.java) { child.close() }
        assertFalse(child.isLive)
        assertEquals(1, backend.retirements)
        assertEquals(1, source.retirements)
        backend.retireResult = UltraEglBackendRetirementResult.RETIRED
        child.close()
        factory.close()
        source.close()
        assertEquals(2, backend.retirements)
        assertEquals(1, source.retirements)
    }

    @Test
    fun foreignStaticContextCannotOpenChildOrAcquireOutput() {
        val factory = StaticThemeRendererFactory(factoryContext(), program, IMPLEMENTATION, null) {
            AndroidUltraRendererSessionCoordinator(it, Backend())
        }
        val source = Source()
        try {
            val foreignPlan = UiSyncRendererStaticPlan(listOf(output), emptyList())
            assertThrows(IllegalArgumentException::class.java) {
                factory.openSession(sessionContext(source, foreignPlan))
            }
            assertEquals(0, source.subscriptions)
        } finally {
            factory.close()
            source.close()
        }
    }

    private fun factoryContext(): UiSyncCompleteRendererBindingContext {
        return factoryContext(plan, schema, binding, displayIds)
    }

    private fun factoryContext(
        inputs: UiSyncAuthorizedStaticInputs,
        staticPlan: UiSyncRendererStaticPlan = inputs.staticPlan,
    ): UiSyncCompleteRendererBindingContext = factoryContext(
        staticPlan, inputs.schemaFingerprint, inputs.bindingFingerprint, displayIds,
    )

    private fun factoryContext(
        staticPlan: UiSyncRendererStaticPlan,
        schema: UiSyncSchemaFingerprint,
        binding: UiSyncBindingFingerprint,
        displayIds: Set<String>,
    ): UiSyncCompleteRendererBindingContext {
        val base = UiSyncRendererBindingContext::class.java.declaredConstructors
            .single { it.parameterCount == 4 }.apply { isAccessible = true }
            .newInstance(staticPlan, schema, binding, displayIds) as UiSyncRendererBindingContext
        return RendererReadinessReceipt.CompleteBindingContext::class.java.declaredConstructors
            .single { it.parameterCount == 1 }.apply { isAccessible = true }
            .newInstance(base) as UiSyncCompleteRendererBindingContext
    }

    private fun sessionContext(
        source: UiSyncPresentationSource,
        staticPlan: UiSyncRendererStaticPlan = plan,
        schema: UiSyncSchemaFingerprint = this.schema,
        binding: UiSyncBindingFingerprint = this.binding,
        displayIds: Set<String> = this.displayIds,
    ): UiSyncCompleteRendererSessionBindingContext {
        val base = UiSyncRendererSessionBindingContext::class.java.declaredConstructors
            .single { it.parameterCount == 5 }.apply { isAccessible = true }
            .newInstance(source, staticPlan, schema, binding, displayIds) as
            UiSyncRendererSessionBindingContext
        return RendererReadinessReceipt.CompleteSessionBindingContext::class.java
            .declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }
            .newInstance(base) as UiSyncCompleteRendererSessionBindingContext
    }

    private fun sessionContext(
        source: UiSyncPresentationSource,
        inputs: UiSyncAuthorizedStaticInputs,
    ): UiSyncCompleteRendererSessionBindingContext = sessionContext(
        source, inputs.staticPlan, inputs.schemaFingerprint, inputs.bindingFingerprint,
        displayIds,
    )

    private fun inputs(): UiSyncAuthorizedStaticInputs =
        UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(
            AirPlayUiSyncInfo(mapOf("renderer" to "test")),
            listOf(UiSyncDisplayRuntimeSpec(
                CarPlayUiSyncChannelDescriptor("main-display", "vehicle"),
                CarPlayUiSyncMode.PASSIVE,
            )), plan,
        )

    private fun assignment(profile: VerifiedUltraProfile): CompiledUiSyncDisplayAssignment {
        val packageEntry = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }
        val display = CarPlayThemeSchema.parseArcPackageJson(requireNotNull(
            profile.candidate.content(UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON),
        )).displays.single()
        val compiler = CarPlayThemeProgramCompiler()
        fun selection(index: Int) = CompiledUiSyncThemeProgramSelection(
            index, CarPlayThemeSceneState(),
            compiler.compile(display).structuralProgram.structuralFingerprint,
        )
        // The structural compilation above represents the same static state for both layouts;
        // the executable-subset validation in deriveVerified... evaluates each selected index.
        return CompiledUiSyncDisplayAssignment(
            packageEntry.digest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("main")),
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            selection(0), emptyList(),
            CompiledUiSyncThemeIntent("first", emptyMap(), "opaque-a"),
            listOf(CompiledUiSyncThemeVariant(
                CompiledUiSyncThemeIntent("second", emptyMap(), "opaque-b"),
                selection(1), emptyList(),
            )),
        )
    }

    private fun staticPackage(unsupportedSecond: Boolean = false): ByteArray = """{
        "identifier":"static-factory","version":1,"minimumSDKVersion":"1",
        "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
        "displays":[{"id":"profile-0","type":"centerConsole","size":[800,480],
          "density":1,"allowManufacturerAppIcon":false,
          "requiresDarkAppearance":false,"layouts":[
            {"id":"first","layoutType":"primary","zones":[],
             "backgroundColor":"#111111","safeAreaInsets":[0,0,0,0],
             "driveModeThemeConfigurations":{}},
            {"id":"second","layoutType":"primary","zones":[],
             "backgroundColor":"#222222","safeAreaInsets":${if (unsupportedSecond)
                 "[1,0,0,0]" else "[0,0,0,0]"},
             "driveModeThemeConfigurations":{}}
          ]}]
    }""".trimIndent().encodeToByteArray()

    private fun verifiedProfile(packageBytes: ByteArray): VerifiedUltraProfile {
        val files = listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageBytes,
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "Phone")),
            "CarPlayPhone.assets/layout.bin" to "layout".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to
                "accessory-assets".encodeToByteArray(),
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to
                "certificate-chain".encodeToByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to
                "opaque-sha256".encodeToByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to
                "accessory-additions".encodeToByteArray(),
            UltraProfilePackageFiles.INFO_PLIST to
                BplistCodec.encode(mapOf("Version" to "1")),
        )
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val candidate = (UltraProfilePackageInspector().inspect(
            ByteArrayInputStream(archive),
        ) as UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding(
            "vehicle", "synthetic-mfi", "asset", "ios", "accessory",
            candidate.contentDigest,
        )
        return (UltraProfilePackageInspector().verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(
                binding, byteArrayOf(1, 2, 3),
            ))
        } as UltraProfileVerificationResult.Verified).profile
    }

    private fun syntheticHandle(): AirPlayVideoStreamHandle {
        val epochClass = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
        val epoch = epochClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        return AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(AirPlayStreamIdentity(110, "1", null), epoch, null) as
            AirPlayVideoStreamHandle
    }

    private class FactoryBackend : StaticThemeFactoryBackendLease {
        var live = true
        var closeCount = 0
        var closeFailures = 0
        override val isLive: Boolean get() = live
        override fun close() {
            closeCount++
            if (closeFailures > 0) {
                closeFailures--
                throw IllegalStateException("EGL retirement unproven")
            }
            live = false
        }
    }

    private inline fun <reified T : Any> fingerprint(fill: Int): T =
        T::class.java.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }
            .newInstance(ByteArray(32) { fill.toByte() }) as T

    private class Source : UiSyncPresentationSource, Closeable {
        private val hub = UltraPresentationHub(Runnable::run)
        var subscriptions = 0
        var retirements = 0
        override fun subscribeWithReplay(
            displayId: String,
            observer: UltraPresentationObserver,
        ): UiSyncPresentationSubscription {
            subscriptions++
            val lease = hub.observe(displayId, observer)
            return UiSyncPresentationSubscription(hub.snapshot(displayId), Closeable {
                retirements++
                lease.close()
            })
        }
        override fun snapshot(displayId: String): UltraPresentationSnapshot = hub.snapshot(displayId)
        override fun close() = hub.close()
    }

    private class Backend : AndroidUltraRendererBackendPort {
        var retirements = 0
        var retireResult = UltraEglBackendRetirementResult.RETIRED
        override fun openRemoteIngress(ingress: UltraDisplayGraphIngress): AndroidUltraBackendIngress =
            error("No video ingress is expected before SETUP")
        override fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize):
            AndroidUltraBackendIngress = error("No local ingress is expected")
        override fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize):
            AndroidUltraBackendIngress = error("No overlay ingress is expected")
        override fun attachOutput(
            destination: DisplayDestination,
            output: UltraDisplayGraphOutput,
            target: AndroidDisplaySurfaceTarget,
        ): AndroidUltraBackendOutput = error("ARM cannot attach a physical output")
        override fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission =
            error("ARM cannot resolve a scene")
        override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket =
            error("ARM cannot submit a scene")
        override fun phase(): UltraEglBackendPhase = UltraEglBackendPhase.OPEN
        override fun retire(timeoutMillis: Long): UltraEglBackendRetirementResult {
            retirements++
            return retireResult
        }
    }

    private companion object {
        const val IMPLEMENTATION = "android-ultra-egl-session-coordinator-v1"
    }
}
