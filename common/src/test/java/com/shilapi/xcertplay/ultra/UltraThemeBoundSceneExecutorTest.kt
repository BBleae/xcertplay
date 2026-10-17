package com.shilapi.xcertplay.ultra

import android.view.Surface
import com.shilapi.xcertplay.AuthorizedUltraDisplayLeaseTestFixture
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledUiSyncLocalCanvasAssignment
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.CompiledUiSyncThemeProgramSelection
import com.shilapi.xcertplay.CompiledUiSyncThemeVariant
import com.shilapi.xcertplay.CompiledUiSyncSlotIngressAssignment
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSubscription
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.media.AndroidVideoSurfaceRetirementResult
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendRetirementResult
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedSubmission
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
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
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeResolvedStyleWitness
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneStage
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralSource
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralFingerprint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class UltraThemeBoundSceneExecutorTest {
    @Test
    fun signedInertLayoutStyleExecutesOnlyItsExactProfilePaletteAndPhoneFiles() {
        val json = styledPackageJson().replace(
            "\"type\":{\"single\":{}}", "\"type\":{\"mapsMedia\":{}}",
        )
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray =
                "signed-style-executor-certificate".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        })
        val profile = profile(json.encodeToByteArray(), identity,
            signedUiSyncCatalog = dualPaletteStyleCatalog(),
            resolvedThemeJson = dualPaletteResolvedStyleJson())
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val displays = CarPlayThemeSchema.parseArcPackageJson(json.encodeToByteArray()).displays
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val witness = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0)
        val compiler = CarPlayThemeProgramCompiler()
        val mainProof = requireNotNull(compiler.assessExecutableStaticSubset(
            witness.display, 0, 0, state, resolvedStyleWitness = witness,
        ).proof)
        assertEquals("bench-style", witness.styleID)
        assertEquals("palette-a", witness.defaultPaletteID)
        assertEquals("palette-b", CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
            profile, 0, 0, "palette-b",
        ).selectedPaletteID)
        assertEquals("palette-a", requireInitialStyledPaletteWitness(
            profile, 0, 0, packageDigest, "bench-style", "layout-main",
            CompiledUiSyncThemeIntent("layout-main", mapOf("zone" to "primary"),
                "palette-a"),
        ).selectedPaletteID)
        assertThrows(IllegalArgumentException::class.java) {
            requireInitialStyledPaletteWitness(profile, 0, 0, packageDigest,
                "bench-style", "layout-main",
                CompiledUiSyncThemeIntent("layout-main", mapOf("zone" to "primary"),
                    "palette-b"))
        }
        val complexDefaultProfile = profile(json.encodeToByteArray(), identity,
            signedUiSyncCatalog = dualPaletteStyleCatalog(),
            resolvedThemeJson = dualPaletteResolvedStyleJson(
                paletteColors = """{"primary":"#ffffff"}""",
            ))
        assertEquals("palette-b", CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
            complexDefaultProfile, 0, 0, "palette-b",
        ).selectedPaletteID)
        assertThrows(IllegalArgumentException::class.java) {
            requireInitialStyledPaletteWitness(complexDefaultProfile, 0, 0, packageDigest,
                "bench-style", "layout-main",
                CompiledUiSyncThemeIntent("layout-main", mapOf("zone" to "primary"),
                    "palette-b"))
        }
        val mainProgram = compiler.assessExecutableStaticSubset(
            witness.display, 0, 0, state, resolvedStyleWitness = witness,
        ).structuralProgram
        val clusterProgram = compiler.compile(displays[1], state).structuralProgram
        assertEquals(mainProgram.structuralFingerprint,
            mainProof.structuralFingerprint)
        val mainSlot = mainProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>().single()
        val clusterSlot = clusterProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>().single()
        val mainSpec = UiSyncRendererIngressSpec(
            UltraIngressId("main-ingress"), UltraAirPlayStreamIdentity(110, null, null),
            AirPlayInfoPlist.MAIN_UUID,
        )
        val clusterSpec = UiSyncRendererIngressSpec(
            UltraIngressId("cluster-ingress"), UltraAirPlayStreamIdentity(111, null, null),
            AirPlayInfoPlist.ALT_UUID,
        )
        val fixtureOutputs = listOf(output("main", 1), output("cluster", 2))
        val mainSelection = CompiledUiSyncThemeProgramSelection(0, state,
            mainProgram.structuralFingerprint)
        val mainIngressAssignment = CompiledUiSyncSlotIngressAssignment(
            mainSlot.address, "main-video", mainSpec,
        )
        val assignments = listOf(
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "main", DisplayType.CENTER_CONSOLE,
                DisplayDestination.MAIN, fixtureOutputs[0].topologyDisplayId,
                fixtureOutputs[0].outputId, requireNotNull(fixtureOutputs[0].uiSyncDisplayId),
                mainSelection, listOf(mainIngressAssignment),
                primaryIntent = CompiledUiSyncThemeIntent("layout-main",
                    mapOf("zone" to "primary"),
                    "palette-a"),
                additionalVariants = listOf(CompiledUiSyncThemeVariant(
                    CompiledUiSyncThemeIntent("layout-main",
                        mapOf("zone" to "primary"), "palette-b"),
                    mainSelection, listOf(mainIngressAssignment),
                )),
            ),
            CompiledUiSyncDisplayAssignment(
                packageDigest, 1, "cluster", DisplayType.CLUSTER,
                DisplayDestination.CLUSTER, fixtureOutputs[1].topologyDisplayId,
                fixtureOutputs[1].outputId,
                requireNotNull(fixtureOutputs[1].uiSyncDisplayId),
                CompiledUiSyncThemeProgramSelection(0, state,
                    clusterProgram.structuralFingerprint),
                listOf(CompiledUiSyncSlotIngressAssignment(clusterSlot.address, "cluster-video",
                    clusterSpec)),
                primaryIntent = CompiledUiSyncThemeIntent("layout-cluster",
                    mapOf("zone" to "primary"),
                    "palette-a"),
            ),
        )
        val lease = AuthorizedUltraDisplayLeaseTestFixture.open(
            profile, identity, fixtureOutputs, assignments,
        )
        val harness = harnessWithJson(json, profileOverride = profile,
            schemaFingerprint = lease.schemaFingerprint,
            bindingFingerprint = lease.bindingFingerprint,
            mainStreamOverride = syntheticHandle(AirPlayStreamIdentity(110, "main", null),
                AirPlayInfoPlist.MAIN_UUID),
            mainIngressSpecOverride = mainSpec,
            clusterStreamOverride = syntheticHandle(AirPlayStreamIdentity(111, "cluster", null),
                AirPlayInfoPlist.ALT_UUID),
            clusterIngressSpecOverride = clusterSpec)
        try {
            fun source(index: Int) = harness.source(index).copy(
                resourcePath = UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                resourceDigest = packageDigest,
                state = state,
                expectedProgramFingerprint = if (index == 0)
                    mainProgram.structuralFingerprint else clusterProgram.structuralFingerprint,
            )
            val mainSource = source(0)
            val main = harness.binding(mainSource, DisplayDestination.MAIN, 0,
                listOf(binding(mainSlot.address, "main-video", harness.mainIngress,
                    harness.mainStream)))
            val cluster = harness.binding(source(1), DisplayDestination.CLUSTER, 1,
                listOf(binding(clusterSlot.address, "cluster-video", harness.clusterIngress,
                    harness.clusterStream)))
            fun authorized(binding: UltraThemeOutputSceneBinding) =
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(), listOf(binding, cluster),
                )
            assertEquals(UltraEglRenderDisposition.PRESENTED,
                authorized(main).submit(1L).awaitPresented().disposition)
            val alternate = main.copy(source = mainSource.copy(
                signedIntent = UltraThemeSignedLayoutIntent("layout-main",
                    mapOf("zone" to "primary"), "palette-b"),
            ))
            assertEquals(UltraEglRenderDisposition.PRESENTED,
                authorized(alternate).submit(2L).awaitPresented().disposition)
            assertEquals(2, harness.backend.submissionCount)

            // Possession of the same verified files alone is not signed scene authority.
            assertThrows(IllegalArgumentException::class.java) {
                harness.executor(listOf(main))
            }
            assertThrows(IllegalArgumentException::class.java) {
                authorized(main.copy(source = mainSource.copy(
                    signedIntent = UltraThemeSignedLayoutIntent("layout-main",
                        mapOf("zone" to "primary"), "palette-other"),
                )))
            }
            assertThrows(IllegalArgumentException::class.java) {
                authorized(main.copy(source = mainSource.copy(resourceDigest =
                    profile.candidate.entries.single {
                        it.name == UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON
                    }.digest)))
            }
            assertEquals(2, harness.backend.submissionCount)
        } finally {
            harness.close()
            lease.close()
        }

        // The issuer and the executor share the same fail-closed witness policy. An override
        // or gradient in the signed phone theme is not silently treated as an inert style.
        listOf(
            dualPaletteResolvedStyleJson(paletteColors = """{"primary":"#ffffff"}"""),
            dualPaletteResolvedStyleJson(colorSet =
                """{"partner.bench":{"single":{"value":{"stops":{"0":"#ff0000"}}}}}"""),
        ).forEach { opaqueTheme ->
            val opaqueProfile = profile(json.encodeToByteArray(), identity,
                signedUiSyncCatalog = dualPaletteStyleCatalog(),
                resolvedThemeJson = opaqueTheme)
            assertThrows(IllegalArgumentException::class.java) {
                CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(opaqueProfile, 0, 0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AuthorizedUltraDisplayLeaseTestFixture.open(
                    opaqueProfile, identity, fixtureOutputs, assignments,
                )
            }
        }
    }

    @Test
    fun signedStyledLocalCanvasRasterUsesTheSameSelectedPaletteWitness() {
        val json = styledPackageJson(packageJson(
            overlayComponents = "," + emptyOverlayComponent(),
        ))
        val profile = profile(json.encodeToByteArray(),
            resolvedThemeJson = inertResolvedStyleJson())
        val witness = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0)
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val program = CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
            witness.display, 0, 0, state, resolvedStyleWitness = witness,
        ).structuralProgram
        val localSlot = program.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
            .single { it.source == CarPlayThemeStructuralSource.Empty }
        val issued = CompiledUiSyncLocalCanvasAssignment(localSlot.address, "styled.canvas")
        val output = output("main", 1)
        fun assignment(intent: CompiledUiSyncThemeIntent?) = CompiledUiSyncDisplayAssignment(
            profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest,
            0, "main", DisplayType.CENTER_CONSOLE, DisplayDestination.MAIN,
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeProgramSelection(0, state, program.structuralFingerprint),
            emptyList(),
            primaryIntent = intent,
            localCanvasAssignments = listOf(issued),
        )
        val assignment = assignment(CompiledUiSyncThemeIntent(
            "layout-main", emptyMap(), "palette-a",
        ))
        assertEquals(listOf(issued to PixelSize(16, 18)),
            resolveAuthorizedLocalCanvasRasters(profile, assignment,
                assignment.programSelection, assignment.localCanvasAssignments,
                mapper(2.0), PixelSize(800, 400),
                selectedIntent = assignment.primaryIntent))
        assertThrows(IllegalArgumentException::class.java) {
            resolveAuthorizedLocalCanvasRasters(profile, assignment,
                assignment.programSelection, assignment.localCanvasAssignments,
                mapper(2.0), PixelSize(800, 400),
                selectedIntent = CompiledUiSyncThemeIntent("layout-main", emptyMap(),
                    "palette-other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAuthorizedLocalCanvasRasters(profile, assignment(
                CompiledUiSyncThemeIntent("layout-main", emptyMap(), "palette-other")),
                assignment.programSelection,
                assignment.localCanvasAssignments, mapper(2.0), PixelSize(800, 400))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAuthorizedLocalCanvasRasters(profile, assignment(null),
                assignment.programSelection, assignment.localCanvasAssignments,
                mapper(2.0), PixelSize(800, 400))
        }
    }

    @Test
    fun graphReplacementBeforeThemeConstructionSnapshotOrCoordinatorResolveIsTypedPreSubmit() {
        val harness = harness()
        try {
            val mainSource = harness.source(0)
            val clusterSource = harness.source(1)
            val bindings = listOf(
                harness.binding(mainSource, DisplayDestination.MAIN, 0,
                    listOf(binding(slot(mainSource), "main-video", harness.mainIngress,
                        harness.mainStream))),
                harness.binding(clusterSource, DisplayDestination.CLUSTER, 1,
                    listOf(binding(slot(clusterSource), "cluster-video", harness.clusterIngress,
                        harness.clusterStream))),
            )
            val staleGraph = harness.coordinator.graphSnapshot()
            val executor = UltraThemeBoundSceneExecutor(
                harness.coordinator, harness.profile, harness.coordinator.schemaFingerprint,
                harness.coordinator.bindingFingerprint, staleGraph, bindings,
            )
            val alreadyPlanned = executor.snapshot(1L)
            harness.mainCandidate.close() // replaces the immutable graph before EGL enqueue
            assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
                UltraThemeBoundSceneExecutor(harness.coordinator, harness.profile,
                    harness.coordinator.schemaFingerprint,
                    harness.coordinator.bindingFingerprint, staleGraph, bindings)
            }
            assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
                executor.snapshot(2L)
            }
            assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
                harness.coordinator.resolveAndSubmit(alreadyPlanned, mapOf(
                    DisplayDestination.MAIN to harness.mainOutput.outputId,
                    DisplayDestination.CLUSTER to harness.clusterOutput.outputId,
                ))
            }
            assertEquals(null, harness.backend.lastPlan)
            assertEquals(0, harness.backend.submissionCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun graphReplacementDuringSnapshotStateReadIsTypedBeforeAnyEglEnqueue() {
        val harness = harness()
        try {
            val source = harness.source(0)
            val executor = harness.executor(listOf(harness.binding(
                source, DisplayDestination.MAIN, 0,
                listOf(binding(slot(source), "main-video", harness.mainIngress,
                    harness.mainStream)),
            )))
            // snapshot() asks phase for isLive, graphSnapshot, then stateSnapshot. Retire the
            // ingress during the third read, after the first exact-graph comparison.
            val retireAt = harness.backend.phaseCalls + 3
            harness.backend.onPhaseCall = { call ->
                if (call == retireAt) {
                    harness.backend.onPhaseCall = null
                    harness.mainCandidate.close()
                }
            }
            assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
                executor.snapshot(1L)
            }
            assertEquals(0, harness.backend.submissionCount)
            assertEquals(null, harness.backend.lastPlan)
        } finally {
            harness.close()
        }
    }

    @Test
    fun authorizedUuidSelectorAcceptsDynamicIdButRejectsMissingAndForeignHandles() {
        val json = packageJson()
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray =
                "dynamic-uuid-scene-certificate".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        })
        val profile = profile(json.encodeToByteArray(), identity)
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val displays = CarPlayThemeSchema.parseArcPackageJson(json.encodeToByteArray()).displays
        val outputs = listOf(output("main", 1), output("cluster", 2))
        val mainSpec = UiSyncRendererIngressSpec(
            UltraIngressId("main-ingress"), UltraAirPlayStreamIdentity(110, null, null),
            AirPlayInfoPlist.MAIN_UUID,
        )
        val clusterSpec = UiSyncRendererIngressSpec(
            UltraIngressId("cluster-ingress"), UltraAirPlayStreamIdentity(111, "cluster", null),
        )
        val dynamicIdentity = AirPlayStreamIdentity(110, "phone-chosen-connection", null)
        val dynamicHandle = syntheticHandle(dynamicIdentity, AirPlayInfoPlist.MAIN_UUID)
        val exactLegacy = UiSyncRendererIngressSpec(
            mainSpec.id, UltraAirPlayStreamIdentity(110, "phone-chosen-connection", null),
        )
        assertTrue(mainSpec.matches(dynamicHandle))
        assertTrue(exactLegacy.matches(dynamicHandle))
        assertFalse(mainSpec.matches(syntheticHandle(dynamicIdentity)))
        assertFalse(UiSyncRendererIngressSpec(mainSpec.id,
            UltraAirPlayStreamIdentity(110, "different-connection", null),
        ).matches(dynamicHandle))
        val wrongUuid = assertThrows(InvocationTargetException::class.java) {
            syntheticHandle(dynamicIdentity, AirPlayInfoPlist.ALT_UUID)
        }
        assertTrue(wrongUuid.cause is IllegalArgumentException)
        fun selection(index: Int): CompiledUiSyncThemeProgramSelection {
            val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
            val program = CarPlayThemeProgramCompiler().compile(displays[index], state)
                .structuralProgram
            return CompiledUiSyncThemeProgramSelection(0, state, program.structuralFingerprint)
        }
        fun slotAssignments(index: Int, spec: UiSyncRendererIngressSpec):
            List<CompiledUiSyncSlotIngressAssignment> {
            val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
            return CarPlayThemeProgramCompiler().compile(displays[index], state)
                .structuralProgram.instructions
                .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .filter { it.address.component.zone.layout.layoutIndex == 0 }
                .map { slot -> CompiledUiSyncSlotIngressAssignment(
                    slot.address,
                    (slot.source as CarPlayThemeStructuralSource.InputStream).streamIdentifier,
                    spec,
                ) }
        }
        val assignments = listOf(
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "main", DisplayType.CENTER_CONSOLE,
                DisplayDestination.MAIN, outputs[0].topologyDisplayId, outputs[0].outputId,
                requireNotNull(outputs[0].uiSyncDisplayId), selection(0),
                slotAssignments(0, mainSpec),
            ),
            CompiledUiSyncDisplayAssignment(
                packageDigest, 1, "cluster", DisplayType.CLUSTER,
                DisplayDestination.CLUSTER, outputs[1].topologyDisplayId, outputs[1].outputId,
                requireNotNull(outputs[1].uiSyncDisplayId), selection(1),
                slotAssignments(1, clusterSpec),
            ),
        )
        val lease = AuthorizedUltraDisplayLeaseTestFixture.open(
            profile, identity, outputs, assignments,
        )
        val harness = harnessWithJson(
            json, profileOverride = profile,
            schemaFingerprint = lease.schemaFingerprint,
            bindingFingerprint = lease.bindingFingerprint,
            mainStreamOverride = dynamicHandle,
            mainIngressSpecOverride = mainSpec,
            clusterStreamOverride = syntheticHandle(AirPlayStreamIdentity(111, "cluster", null)),
            clusterIngressSpecOverride = clusterSpec,
        )
        try {
            fun packageSource(index: Int) = harness.source(index).copy(
                resourcePath = UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                resourceDigest = packageDigest,
            )
            val mainSource = packageSource(0)
            val clusterSource = packageSource(1)
            val mainBinding = harness.binding(mainSource, DisplayDestination.MAIN, 0,
                listOf(binding(slot(mainSource), "main-video", harness.mainIngress,
                    dynamicHandle)))
            val clusterBinding = harness.binding(clusterSource, DisplayDestination.CLUSTER, 1,
                listOf(binding(slot(clusterSource), "cluster-video", harness.clusterIngress,
                    harness.clusterStream)))
            fun executor(main: UltraThemeOutputSceneBinding) =
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(), listOf(main, clusterBinding),
                )
            assertEquals(UltraEglRenderDisposition.PRESENTED,
                executor(mainBinding).submit(1L).awaitPresented().disposition)

            val equalButForeignProfile = profile(json.encodeToByteArray(), identity)
            assertThrows(IllegalArgumentException::class.java) {
                executor(mainBinding.copy(source = mainSource.copy(
                    profile = equalButForeignProfile,
                )))
            }
            assertThrows(IllegalArgumentException::class.java) {
                executor(mainBinding.copy(source = mainSource.copy(
                    expectedProgramFingerprint = CarPlayThemeStructuralFingerprint(
                        ByteArray(32) { 0x66 },
                    ),
                )))
            }

            val missingUuid = syntheticHandle(dynamicIdentity)
            assertThrows(IllegalArgumentException::class.java) {
                executor(mainBinding.copy(slotIngresses = listOf(binding(
                    slot(mainSource), "main-video", harness.mainIngress, missingUuid,
                ))))
            }
            val foreignSession = syntheticHandle(dynamicIdentity, AirPlayInfoPlist.MAIN_UUID)
            assertThrows(IllegalArgumentException::class.java) {
                executor(mainBinding.copy(slotIngresses = listOf(binding(
                    slot(mainSource), "main-video", harness.mainIngress, foreignSession,
                )))).snapshot(2L)
            }
            lease.revoke()
            assertThrows(IllegalStateException::class.java) { executor(mainBinding) }
        } finally {
            harness.close()
            lease.close()
        }
    }

    @Test
    fun authorizedSameRoleDisplaysSubmitOnlyExactProfileRowsAndStopAfterRevocation() {
        val json = packageJson(
            secondDisplayType = "centerConsole",
            extraMainLayout = "," + """{"id":"unapproved-second-layout",
                "layoutType":"primary","backgroundColor":"#00ff00",
                "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{},
                "zones":[]}""",
        )
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray =
                "scene-assignment-test-certificate".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        })
        val profile = profile(json.encodeToByteArray(), identity)
        val second = DisplayDestination(
            DisplayDestinationRole.MAIN, DisplayDestinationId("second-center-console"),
        )
        val graphOutputs = listOf(output("main", 1), output("cluster", 2))
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val parsedDisplays = CarPlayThemeSchema.parseArcPackageJson(json.encodeToByteArray()).displays
        val secondLayoutProgram = CarPlayThemeProgramCompiler().compile(
            parsedDisplays[0], CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
        )
        assertTrue(secondLayoutProgram.structuralProgram.instructions.any {
            it is CarPlayThemeProgramInstruction.FillLayoutBackground &&
                it.address.layoutIndex == 1
        })
        fun programSelection(index: Int): CompiledUiSyncThemeProgramSelection {
            val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
            return CompiledUiSyncThemeProgramSelection(
                0, state,
                CarPlayThemeProgramCompiler().compile(parsedDisplays[index], state)
                    .structuralProgram.structuralFingerprint,
            )
        }
        val ingressSpecs = listOf(
            UiSyncRendererIngressSpec(
                UltraIngressId("main-ingress"), UltraAirPlayStreamIdentity(110, "main", null),
            ),
            UiSyncRendererIngressSpec(
                UltraIngressId("cluster-ingress"), UltraAirPlayStreamIdentity(110, "cluster", null),
            ),
        )
        fun slotAssignments(index: Int): List<CompiledUiSyncSlotIngressAssignment> {
            val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
            return CarPlayThemeProgramCompiler().compile(parsedDisplays[index], state)
                .structuralProgram.instructions
                .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .filter { it.address.component.zone.layout.layoutIndex == 0 }
                .map { slot ->
                    CompiledUiSyncSlotIngressAssignment(
                        slot.address,
                        (slot.source as CarPlayThemeStructuralSource.InputStream).streamIdentifier,
                        ingressSpecs[index],
                    )
                }
        }
        val assignments = listOf(
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "main", DisplayType.CENTER_CONSOLE,
                DisplayDestination.MAIN, graphOutputs[0].topologyDisplayId,
                graphOutputs[0].outputId, requireNotNull(graphOutputs[0].uiSyncDisplayId),
                programSelection(0),
                slotAssignments(0),
            ),
            CompiledUiSyncDisplayAssignment(
                packageDigest, 1, "cluster", DisplayType.CENTER_CONSOLE,
                second, graphOutputs[1].topologyDisplayId,
                graphOutputs[1].outputId, requireNotNull(graphOutputs[1].uiSyncDisplayId),
                programSelection(1),
                slotAssignments(1),
            ),
        )
        val lease = AuthorizedUltraDisplayLeaseTestFixture.open(
            profile, identity, graphOutputs, assignments,
        )
        val harness = harnessWithJson(
            json, second, profile, lease.schemaFingerprint, lease.bindingFingerprint,
        )
        try {
            fun packageSource(index: Int) = harness.source(index).copy(
                resourcePath = UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                resourceDigest = packageDigest,
            )
            val main = packageSource(0)
            val other = packageSource(1)
            val mainBinding = harness.binding(main, DisplayDestination.MAIN, 0,
                listOf(binding(slot(main), "main-video", harness.mainIngress,
                    harness.mainStream)))
            val otherBinding = harness.binding(other, second, 1,
                listOf(binding(slot(other), "cluster-video", harness.clusterIngress,
                    harness.clusterStream)))
            val executor = UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                harness.coordinator, lease.runtimeLease, profile,
                harness.coordinator.graphSnapshot(), listOf(mainBinding, otherBinding),
            )
            assertEquals(UltraEglRenderDisposition.PRESENTED,
                executor.submit(1L).awaitPresented().disposition)
            assertSame(harness.mainOutput,
                harness.backend.lastPlan?.outputByDestination?.get(DisplayDestination.MAIN))
            assertSame(harness.clusterOutput,
                harness.backend.lastPlan?.outputByDestination?.get(second))
            assertThrows(IllegalArgumentException::class.java) {
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(),
                    listOf(mainBinding.copy(outputId = otherBinding.outputId),
                        otherBinding.copy(outputId = mainBinding.outputId)),
                )
            }
            val unapprovedSecondLayout = main.copy(layoutIndex = 1)
            assertThrows(IllegalArgumentException::class.java) {
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(),
                    listOf(mainBinding.copy(source = unapprovedSecondLayout,
                        slotIngresses = emptyList()), otherBinding),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(),
                    listOf(mainBinding.copy(source = other), otherBinding.copy(source = main)),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(),
                    listOf(mainBinding.copy(slotIngresses = listOf(binding(
                        slot(main), "main-video", harness.clusterIngress, harness.clusterStream,
                    ))), otherBinding),
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                    harness.coordinator, lease.runtimeLease, profile,
                    harness.coordinator.graphSnapshot(),
                    listOf(mainBinding.copy(explicitVideoLayers = listOf(
                        UltraThemeExplicitVideoLayer(
                            UltraLayerId("unissued-overlay"), harness.clusterIngress,
                            harness.clusterStream, UltraPixelRect(0, 0, 800, 400),
                            CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT, 100,
                        ),
                    )), otherBinding),
                )
            }
            val enteredBackendResolve = CountDownLatch(1)
            val releaseBackendResolve = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            harness.backend.onResolve = {
                enteredBackendResolve.countDown()
                check(releaseBackendResolve.await(15, TimeUnit.SECONDS))
            }
            try {
                // The authorized scene has passed both assignment checks but is still before
                // the backend queue handoff. Revocation must close new admission and wait for it.
                val submitted = pool.submit<UltraThemeBoundSceneSubmission> { executor.submit(2L) }
                assertTrue(enteredBackendResolve.await(5, TimeUnit.SECONDS))
                val revoked = pool.submit { lease.revoke() }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!lease.runtimeLease.revoked && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertTrue("Revocation did not close scene admission", lease.runtimeLease.revoked)
                assertFalse("Revocation retired before its admitted scene left the backend queue",
                    revoked.isDone)
                assertThrows(IllegalStateException::class.java) {
                    lease.runtimeLease.withSceneSubmissionUse { Unit }
                }
                releaseBackendResolve.countDown()
                assertEquals(2L, submitted.get(5, TimeUnit.SECONDS).ticket.revision)
                revoked.get(5, TimeUnit.SECONDS)
                val submittedPlan = harness.backend.lastPlan
                assertEquals(2, harness.backend.submissionCount)
                assertThrows(IllegalStateException::class.java) { executor.submit(3L) }
                assertSame(submittedPlan, harness.backend.lastPlan)
                assertEquals(2, harness.backend.submissionCount)
            } finally {
                releaseBackendResolve.countDown()
                pool.shutdownNow()
            }
        } finally {
            harness.close()
            lease.close()
        }
    }

    @Test
    fun dualOutputProfileScenesSubmitDistinctBackgroundsAndVideoAtExactPixels() {
        val harness = harness()
        val mainSource = harness.source(0)
        val clusterSource = harness.source(1)
        val mainSlot = slot(mainSource)
        val clusterSlot = slot(clusterSource)
        val executor = harness.executor(listOf(
            harness.binding(mainSource, DisplayDestination.MAIN, 0,
                listOf(binding(mainSlot, "main-video", harness.mainIngress, harness.mainStream)),
                explicit = listOf(
                    UltraThemeExplicitVideoLayer(
                        UltraLayerId("pre-video"), harness.mainIngress, harness.mainStream,
                        UltraPixelRect(0, 0, 8, 8),
                        CarPlayThemeSceneStage.ACTIVE_PRIMARY_SLOT_Z_ORDER, -10,
                        UltraThemeExplicitLayerPlacement.BEFORE_THEME_STAGE,
                    ),
                    UltraThemeExplicitVideoLayer(
                        UltraLayerId("custom-overlay"), harness.mainIngress, harness.mainStream,
                        UltraPixelRect(100, 50, 120, 80),
                        CarPlayThemeSceneStage.ZONE_OVERLAY_COMPONENT, -100,
                    ),
                ),
            ),
            harness.binding(clusterSource, DisplayDestination.CLUSTER, 1,
                listOf(binding(clusterSlot, "cluster-video", harness.clusterIngress,
                    harness.clusterStream)),
            ),
        ))

        val submission = executor.submit(7L)
        val plan = checkNotNull(harness.backend.lastPlan)
        assertEquals(7L, plan.renderRevision)
        assertEquals(harness.coordinator.graphSnapshot().revision, plan.graphRevision)
        assertSame(harness.mainOutput, plan.outputByDestination[DisplayDestination.MAIN])
        assertSame(harness.clusterOutput, plan.outputByDestination[DisplayDestination.CLUSTER])
        val mainScene = checkNotNull(submission.snapshot.scene(DisplayDestination.MAIN))
        val clusterScene = checkNotNull(submission.snapshot.scene(DisplayDestination.CLUSTER))
        assertEquals(listOf("pre-video", "theme-0-0-0-0", "custom-overlay"),
            mainScene.layers.map { it.id.value })
        assertEquals(UltraPixelRect(26, 48, 20, 22),
            mainScene.layers.single { it.id.value == "theme-0-0-0-0" }.framePixels)
        assertEquals(null,
            mainScene.layers.single { it.id.value == "theme-0-0-0-0" }.clipPixels)
        assertEquals(UltraPixelRect(39, 72, 30, 33), clusterScene.layers.single().framePixels)
        assertEquals(null, clusterScene.layers.single().clipPixels)
        assertSame(harness.mainStream,
            plan.scene(DisplayDestination.MAIN)?.layers?.first()?.remoteIngress?.stream)
        assertSame(harness.clusterStream,
            plan.scene(DisplayDestination.CLUSTER)?.layers?.first()?.remoteIngress?.stream)
        assertEquals(1.0,
            (mainScene.background as UltraSceneBackground.OpaqueSrgb).color.red, 0.0)
        assertEquals(1.0,
            (clusterScene.background as UltraSceneBackground.OpaqueSrgb).color.blue, 0.0)
        assertEquals(UltraEglRenderDisposition.PRESENTED, submission.awaitPresented().disposition)
        harness.close()
    }

    @Test
    fun profileDigestSchemaMapperAndUnresolvedThemeContentRejectBeforeSubmit() {
        val harness = harness()
        val source = harness.source(0)
        val slot = slot(source)
        val base = harness.binding(source, DisplayDestination.MAIN, 0,
            listOf(binding(slot, "main-video", harness.mainIngress, harness.mainStream)))
        assertThrows(IllegalArgumentException::class.java) {
            UltraThemeBoundSceneExecutor(harness.coordinator, harness.profile,
                harness.coordinator.schemaFingerprint, harness.coordinator.bindingFingerprint,
                harness.coordinator.graphSnapshot(), listOf(base))
        }
        val clusterSource = harness.source(1)
        val secondMainRole = harness.binding(clusterSource,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("other")), 1,
            listOf(binding(slot(clusterSource), "cluster-video", harness.clusterIngress,
                harness.clusterStream)))
        assertThrows(IllegalArgumentException::class.java) {
            UltraThemeBoundSceneExecutor(harness.coordinator, harness.profile,
                harness.coordinator.schemaFingerprint, harness.coordinator.bindingFingerprint,
                harness.coordinator.graphSnapshot(), listOf(base, secondMainRole))
        }
        val sourceWrongDigest = source.copy(resourceDigest = harness.profile.candidate.entries
            .single { it.name == UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON }.digest)
        assertThrows(IllegalArgumentException::class.java) {
            harness.executor(listOf(base.copy(source = sourceWrongDigest)))
        }
        val reverifiedProfile = profile(harness.json)
        assertThrows(IllegalArgumentException::class.java) {
            harness.executor(listOf(base.copy(source = source.copy(profile = reverifiedProfile))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraThemeBoundSceneExecutor(harness.coordinator, harness.profile, fingerprint(99),
                harness.coordinator.bindingFingerprint, harness.coordinator.graphSnapshot(),
                listOf(base))
        }
        assertThrows(IllegalArgumentException::class.java) {
            harness.executor(listOf(base.copy(pixelMapper = mapper(1.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            harness.executor(listOf(base.copy(slotIngresses = emptyList())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            harness.executor(listOf(base.copy(slotIngresses = listOf(binding(slot,
                "wrong-stream-identifier", harness.mainIngress, harness.mainStream))))).snapshot(1L)
        }
        val unknownInstrument = harnessWithJson(packageJson(instrumentExtra =
            "\"mask\":{\"future\":true},"))
        assertThrows(IllegalArgumentException::class.java) {
            unknownInstrument.executor(listOf(unknownInstrument.binding(
                unknownInstrument.source(0), DisplayDestination.MAIN, 0,
                listOf(binding(slot(unknownInstrument.source(0)), "main-video",
                    unknownInstrument.mainIngress, unknownInstrument.mainStream)),
            )))
        }
        unknownInstrument.close()
        val shape = harnessWithJson(packageJson(zoneShape =
            ",\"zoneShape\":{\"circular\":{}}"))
        assertThrows(IllegalArgumentException::class.java) {
            shape.executor(listOf(shape.binding(shape.source(0), DisplayDestination.MAIN, 0,
                listOf(binding(slot(shape.source(0)), "main-video",
                    shape.mainIngress, shape.mainStream)))))
        }
        shape.close()
        val dynamic = harnessWithJson(packageJson(mainBackground =
            "{\"dynamic\":{\"value\":{\"lightMode\":\"#ffffff\",\"darkMode\":\"#000000\"}}}"))
        assertThrows(IllegalArgumentException::class.java) {
            dynamic.executor(listOf(dynamic.binding(dynamic.source(0), DisplayDestination.MAIN,
                0, listOf(binding(slot(dynamic.source(0)), "main-video",
                    dynamic.mainIngress, dynamic.mainStream)))))
        }
        dynamic.close()
        val masked = harnessWithJson(packageJson(presentationExtra =
            ",\"_2\":{\"clipToBounds\":false,\"mask\":{\"image\":\"x\"}}"))
        assertThrows(IllegalArgumentException::class.java) {
            masked.executor(listOf(masked.binding(masked.source(0), DisplayDestination.MAIN,
                0, listOf(binding(slot(masked.source(0)), "main-video",
                    masked.mainIngress, masked.mainStream)))))
        }
        masked.close()
        val transformed = harnessWithJson(packageJson(presentationExtra =
            ",\"_2\":{\"clipToBounds\":false,\"transform\":{\"scaleToFit\":{\"alignment\":2}}}"))
        assertThrows(IllegalArgumentException::class.java) {
            transformed.executor(listOf(transformed.binding(transformed.source(0),
                DisplayDestination.MAIN, 0,
                listOf(binding(slot(transformed.source(0)), "main-video",
                    transformed.mainIngress, transformed.mainStream)))))
        }
        transformed.close()
        val ambiguousDisplays = harnessWithJson(packageJson(extraDisplay = "," + displayJson(
            "cluster", "cluster-duplicate", "\"#0033ff\"", "cluster-video", "", "", "", "",
        )))
        assertThrows(IllegalArgumentException::class.java) {
            val ambiguousMain = ambiguousDisplays.source(0)
            ambiguousDisplays.executor(listOf(ambiguousDisplays.binding(
                ambiguousMain, DisplayDestination.MAIN, 0,
                listOf(binding(slot(ambiguousMain), "main-video",
                    ambiguousDisplays.mainIngress, ambiguousDisplays.mainStream)),
            )))
        }
        ambiguousDisplays.close()
        harness.close()
    }

    @Test
    fun staleOrWrongSessionStreamAndFailedTicketNeverCountAsPresented() {
        val harness = harness()
        val source = harness.source(0)
        val slot = slot(source)
        val correct = harness.binding(source, DisplayDestination.MAIN, 0,
            listOf(binding(slot, "main-video", harness.mainIngress, harness.mainStream)))
        val executor = harness.executor(listOf(correct))
        val anotherSessionStream = syntheticHandle(AirPlayStreamIdentity(110, "main", null))
        val wrongStream = harness.executor(listOf(correct.copy(slotIngresses = listOf(
            binding(slot, "main-video", harness.mainIngress, anotherSessionStream),
        ))))
        assertThrows(IllegalArgumentException::class.java) { wrongStream.snapshot(1L) }
        val missingIngress = harness.executor(listOf(correct.copy(slotIngresses = listOf(
            binding(slot, "main-video", UltraIngressId("not-published"), harness.mainStream),
        ))))
        assertThrows(IllegalArgumentException::class.java) { missingIngress.snapshot(1L) }

        harness.backend.nextDisposition = UltraEglRenderDisposition.FAILED
        val failed = executor.submit(2L)
        assertThrows(IllegalStateException::class.java) { failed.awaitPresented() }
        harness.mainOutputLease.close()
        assertThrows(StaleThemeGraphBeforeSubmissionException::class.java) {
            executor.snapshot(3L)
        }
        harness.close()
    }

    @Test
    fun overlayComponentsKeepProgramOrderEvenWhenTheirSlotZIndicesCross() {
        val overlayComponents = "," + overlayComponent("first", 100) +
            "," + overlayComponent("second", -100)
        val harness = harnessWithJson(packageJson(overlayComponents = overlayComponents))
        val main = harness.source(0)
        val slotBindings = slots(main).map { address ->
            binding(address, "main-video", harness.mainIngress, harness.mainStream)
        }
        val executor = harness.executor(listOf(harness.binding(
            main, DisplayDestination.MAIN, 0, slotBindings,
        )))

        assertEquals(
            listOf("theme-0-0-0-0", "theme-0-0-1-0", "theme-0-0-2-0"),
            executor.snapshot(1L).scene(DisplayDestination.MAIN)!!.layers.map { it.id.value },
        )
        harness.close()
    }

    @Test
    fun rectangularZoneShapeClipBindsEachVideoSlotToItsOwnSelectedZone() {
        val json = packageJson(
            mainComponentClip = zoneShapeClipJson(),
            mainSlotFrame = "[[96,4],[10,11]]",
            extraMainZone = secondClippedZone(),
        )
        val harness = harnessWithJson(json)
        try {
            val main = harness.source(0)
            val slotBindings = slots(main).map { address ->
                binding(address, "main-video", harness.mainIngress, harness.mainStream)
            }
            val scene = harness.executor(listOf(harness.binding(
                main, DisplayDestination.MAIN, 0, slotBindings,
            ))).snapshot(1L).scene(DisplayDestination.MAIN)!!
            assertEquals(2, scene.layers.size)
            val first = scene.layers.single { it.id.value == "theme-0-0-0-0" }
            val second = scene.layers.single { it.id.value == "theme-0-1-0-0" }
            assertEquals(UltraPixelRect(212, 48, 20, 22), first.framePixels)
            assertEquals(UltraPixelRect(20, 40, 200, 120), first.clipPixels)
            assertEquals(UltraPixelRect(488, 90, 20, 22), second.framePixels)
            assertEquals(UltraPixelRect(480, 80, 200, 120), second.clipPixels)
            assertEquals(0, harness.backend.submissionCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun zoneShapeClipRejectsFractionalPixelEdgeEvenWithRoundingClipMapper() {
        val harness = harnessWithJson(packageJson(
            mainComponentClip = zoneShapeClipJson(),
            mainZoneFrame = "[[10,20],[100.25,60]]",
        ))
        try {
            val main = harness.source(0)
            val ordinary = harness.binding(main, DisplayDestination.MAIN, 0,
                listOf(binding(slot(main), "main-video", harness.mainIngress,
                    harness.mainStream)))
            val roundingClipMapper = CarPlayLogicalPixelMapper(
                AxisAlignedPixelAffine(2.0, 2.0, 0.0, 0.0),
                NegativeExtentPolicy.REJECT,
                PixelEdgeQuantization.REQUIRE_INTEGRAL,
                PixelEdgeQuantization.ENCLOSE,
            )
            assertThrows(IllegalArgumentException::class.java) {
                harness.executor(listOf(ordinary.copy(pixelMapper = roundingClipMapper)))
            }
            assertEquals(0, harness.backend.submissionCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun issuedLocalCanvasZoneClipRetainsSlotFrameIntersectionAndNoneVideo() {
        val json = packageJson(overlayComponents = "," + emptyOverlayComponent(
            frame = "[[96,1],[8,9]]", componentClip = zoneShapeClipJson(),
        ))
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray =
                "issued-local-zone-clip-certificate".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        })
        val profile = profile(json.encodeToByteArray(), identity,
            signedLocalClipCatalog())
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val parsed = CarPlayThemeSchema.parseArcPackageJson(json.encodeToByteArray())
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val mainProgram = CarPlayThemeProgramCompiler().compile(parsed.displays[0], state)
            .structuralProgram
        val clusterProgram = CarPlayThemeProgramCompiler().compile(parsed.displays[1], state)
            .structuralProgram
        val mainSlots = mainProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
        val video = mainSlots.single {
            it.source is CarPlayThemeStructuralSource.InputStream
        }
        val local = mainSlots.single { it.source == CarPlayThemeStructuralSource.Empty }
        val clusterVideo = clusterProgram.instructions
            .filterIsInstance<CarPlayThemeProgramInstruction.Slot>().single()
        val mainSpec = UiSyncRendererIngressSpec(
            UltraIngressId("main-ingress"), UltraAirPlayStreamIdentity(110, null, null),
            AirPlayInfoPlist.MAIN_UUID,
        )
        val clusterSpec = UiSyncRendererIngressSpec(
            UltraIngressId("cluster-ingress"), UltraAirPlayStreamIdentity(111, null, null),
            AirPlayInfoPlist.ALT_UUID,
        )
        val issuedLocal = CompiledUiSyncLocalCanvasAssignment(local.address, "canvas.main.clip")
        val fixtureOutputs = listOf(output("main", 1), output("cluster", 2))
        val assignments = listOf(
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "main", DisplayType.CENTER_CONSOLE,
                DisplayDestination.MAIN, fixtureOutputs[0].topologyDisplayId,
                fixtureOutputs[0].outputId, requireNotNull(fixtureOutputs[0].uiSyncDisplayId),
                CompiledUiSyncThemeProgramSelection(0, state,
                    mainProgram.structuralFingerprint),
                listOf(CompiledUiSyncSlotIngressAssignment(video.address, "main-video",
                    mainSpec)),
                primaryIntent = CompiledUiSyncThemeIntent("layout-main", emptyMap(),
                    "palette-a"),
                localCanvasAssignments = listOf(issuedLocal),
            ),
            CompiledUiSyncDisplayAssignment(
                packageDigest, 1, "cluster", DisplayType.CLUSTER,
                DisplayDestination.CLUSTER, fixtureOutputs[1].topologyDisplayId,
                fixtureOutputs[1].outputId,
                requireNotNull(fixtureOutputs[1].uiSyncDisplayId),
                CompiledUiSyncThemeProgramSelection(0, state,
                    clusterProgram.structuralFingerprint),
                listOf(CompiledUiSyncSlotIngressAssignment(clusterVideo.address,
                    "cluster-video", clusterSpec)),
                primaryIntent = CompiledUiSyncThemeIntent("layout-cluster", emptyMap(),
                    "palette-a"),
            ),
        )
        val lease = AuthorizedUltraDisplayLeaseTestFixture.open(
            profile, identity, fixtureOutputs, assignments,
        )
        val harness = harnessWithJson(
            json, profileOverride = profile,
            schemaFingerprint = lease.schemaFingerprint,
            bindingFingerprint = lease.bindingFingerprint,
            mainStreamOverride = syntheticHandle(AirPlayStreamIdentity(110, "main", null),
                AirPlayInfoPlist.MAIN_UUID),
            mainIngressSpecOverride = mainSpec,
            clusterStreamOverride = syntheticHandle(
                AirPlayStreamIdentity(111, "cluster", null), AirPlayInfoPlist.ALT_UUID),
            clusterIngressSpecOverride = clusterSpec,
        )
        val localIngress = harness.coordinator.openLocalIngress(
            UltraContentId(issuedLocal.contentId), PixelSize(16, 18),
        )
        try {
            fun issuedSource(index: Int) = harness.source(index).copy(
                resourcePath = UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                resourceDigest = packageDigest,
            )
            val mainBinding = harness.binding(issuedSource(0), DisplayDestination.MAIN, 0,
                listOf(binding(video.address, "main-video", harness.mainIngress,
                    harness.mainStream))).copy(localCanvasSlots = listOf(
                UltraThemeLocalCanvasSlotBinding(local.address,
                    UltraContentId(issuedLocal.contentId), localIngress, PixelSize(16, 18)),
            ))
            val clusterBinding = harness.binding(issuedSource(1), DisplayDestination.CLUSTER, 1,
                listOf(binding(clusterVideo.address, "cluster-video", harness.clusterIngress,
                    harness.clusterStream)))
            val executor = UltraThemeBoundSceneExecutor.fromAuthorizedProvisioning(
                harness.coordinator, lease.runtimeLease, profile,
                harness.coordinator.graphSnapshot(), listOf(mainBinding, clusterBinding),
            )
            val submission = executor.submit(1L)
            val layers = submission.snapshot.scene(DisplayDestination.MAIN)!!.layers
            assertEquals(2, layers.size)
            assertEquals(null, layers.single { it.id.value == "theme-0-0-0-0" }.clipPixels)
            val localLayer = layers.single { it.id.value == "theme-0-0-1-0" }
            assertEquals(UltraSceneSource.Local(UltraContentId(issuedLocal.contentId)),
                localLayer.source)
            assertEquals(UltraPixelRect(212, 42, 16, 18), localLayer.framePixels)
            assertEquals(UltraPixelRect(20, 40, 200, 120), localLayer.clipPixels)
            assertEquals(UltraEglRenderDisposition.PRESENTED,
                submission.awaitPresented().disposition)
        } finally {
            localIngress.close()
            harness.close()
            lease.close()
        }
    }

    @Test
    fun localCanvasRasterPlanningRequiresIssuedEmptySlotAndExactViewport() {
        val harness = harnessWithJson(packageJson(
            overlayComponents = "," + emptyOverlayComponent(),
        ))
        try {
            val source = harness.source(0)
            val display = CarPlayThemeSchema.parseArcPackageJson(harness.json).displays[0]
            val program = CarPlayThemeProgramCompiler().compile(display, source.state)
                .structuralProgram
            val emptySlot = program.instructions.filterIsInstance<
                CarPlayThemeProgramInstruction.Slot
            >().single { it.address.component.zone.layout.layoutIndex == 0 &&
                it.source == CarPlayThemeStructuralSource.Empty }
            val videoSlot = program.instructions.filterIsInstance<
                CarPlayThemeProgramInstruction.Slot
            >().single { it.address.component.zone.layout.layoutIndex == 0 &&
                it.source is CarPlayThemeStructuralSource.InputStream }
            val issued = CompiledUiSyncLocalCanvasAssignment(emptySlot.address, "canvas.main")
            val packageDigest = harness.profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest
            val assignment = CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "main", DisplayType.CENTER_CONSOLE,
                DisplayDestination.MAIN, harness.mainOutput.topologyDisplayId,
                harness.mainOutput.outputId, requireNotNull(harness.mainOutput.uiSyncDisplayId),
                CompiledUiSyncThemeProgramSelection(0, source.state,
                    program.structuralFingerprint), emptyList(),
                localCanvasAssignments = listOf(issued),
            )
            assertEquals(listOf(issued to PixelSize(16, 18)),
                resolveAuthorizedLocalCanvasRasters(harness.profile, assignment,
                    assignment.programSelection, assignment.localCanvasAssignments,
                    mapper(2.0), PixelSize(800, 400)))
            val localLease = harness.coordinator.openLocalIngress(
                UltraContentId(issued.contentId), PixelSize(16, 18))
            try {
                val binding = harness.binding(source, DisplayDestination.MAIN, 0,
                    listOf(binding(videoSlot.address, "main-video", harness.mainIngress,
                        harness.mainStream))).copy(localCanvasSlots = listOf(
                    UltraThemeLocalCanvasSlotBinding(emptySlot.address,
                        UltraContentId(issued.contentId), localLease, PixelSize(16, 18)),
                ))
                assertThrows(IllegalArgumentException::class.java) {
                    harness.executor(listOf(binding))
                }
            } finally {
                localLease.close()
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolveAuthorizedLocalCanvasRasters(harness.profile, assignment,
                    assignment.programSelection,
                    listOf(CompiledUiSyncLocalCanvasAssignment(videoSlot.address,
                        "canvas.main")), mapper(2.0), PixelSize(800, 400))
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolveAuthorizedLocalCanvasRasters(harness.profile, assignment,
                    assignment.programSelection, assignment.localCanvasAssignments,
                    mapper(2.0), PixelSize(799, 400))
            }
            assertThrows(IllegalArgumentException::class.java) {
                resolveAuthorizedLocalCanvasRasters(harness.profile, assignment,
                    assignment.programSelection, assignment.localCanvasAssignments,
                    mapper(1.0), PixelSize(800, 400))
            }
        } finally {
            harness.close()
        }
    }

    private fun harness(): Harness = harnessWithJson(packageJson())

    private fun harnessWithJson(
        json: String,
        secondDestination: DisplayDestination = DisplayDestination.CLUSTER,
        profileOverride: VerifiedUltraProfile? = null,
        schemaFingerprint: UiSyncSchemaFingerprint = fingerprint(1),
        bindingFingerprint: UiSyncBindingFingerprint = fingerprint(2),
        mainStreamOverride: AirPlayVideoStreamHandle? = null,
        mainIngressSpecOverride: UiSyncRendererIngressSpec? = null,
        clusterStreamOverride: AirPlayVideoStreamHandle? = null,
        clusterIngressSpecOverride: UiSyncRendererIngressSpec? = null,
    ): Harness {
        val profile = profileOverride ?: profile(json.encodeToByteArray())
        val mainStream = mainStreamOverride ?: syntheticHandle(AirPlayStreamIdentity(110, "main", null))
        val clusterStream = clusterStreamOverride ?:
            syntheticHandle(AirPlayStreamIdentity(110, "cluster", null))
        val mainIngress = UltraIngressId("main-ingress")
        val clusterIngress = UltraIngressId("cluster-ingress")
        val mainOutput = output("main", 1)
        val clusterOutput = output("cluster", 2)
        val backend = FakeBackend()
        val plan = UiSyncRendererStaticPlan(listOf(mainOutput, clusterOutput), listOf(
            mainIngressSpecOverride ?: UiSyncRendererIngressSpec(mainIngress,
                UltraAirPlayStreamIdentity(110, "main", null)),
            clusterIngressSpecOverride ?: UiSyncRendererIngressSpec(clusterIngress,
                UltraAirPlayStreamIdentity(110, "cluster", null)),
        ))
        val coordinator = AndroidUltraRendererSessionCoordinator(
            sessionContext(plan, schemaFingerprint, bindingFingerprint), backend,
        )
        val mainCandidate = checkNotNull(coordinator.prepareScreen(
            screenContext(mainStream, mainIngress)))
        val clusterCandidate = checkNotNull(coordinator.prepareScreen(
            screenContext(clusterStream, clusterIngress)))
        assertTrue(mainCandidate.publish())
        assertTrue(clusterCandidate.publish())
        val mainOutputLease = coordinator.attachOutput(
            DisplayDestination.MAIN, mainOutput, target("main", 800, 400))
        val clusterOutputLease = coordinator.attachOutput(
            secondDestination, clusterOutput, target("cluster", 1200, 600))
        return Harness(profile, json.encodeToByteArray(), coordinator, backend, mainStream,
            clusterStream, mainIngress, clusterIngress, mainOutput, clusterOutput,
            mainCandidate, clusterCandidate, mainOutputLease, clusterOutputLease)
    }

    private inner class Harness(
        val profile: VerifiedUltraProfile,
        val json: ByteArray,
        val coordinator: AndroidUltraRendererSessionCoordinator,
        val backend: FakeBackend,
        val mainStream: AirPlayVideoStreamHandle,
        val clusterStream: AirPlayVideoStreamHandle,
        val mainIngress: UltraIngressId,
        val clusterIngress: UltraIngressId,
        val mainOutput: UltraDisplayGraphOutput,
        val clusterOutput: UltraDisplayGraphOutput,
        val mainCandidate: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate,
        val clusterCandidate: com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate,
        val mainOutputLease: AndroidUltraOutputAttachment,
        val clusterOutputLease: AndroidUltraOutputAttachment,
    ) {
        fun source(index: Int): UltraThemeVerifiedProgramSource {
            val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
            val display = CarPlayThemeSchema.parseArcPackageJson(json).displays[index]
            val fingerprint = CarPlayThemeProgramCompiler().compile(display, state)
                .structuralProgram.structuralFingerprint
            val path = "CarPlayPhone.assets/selected-program.json"
            return UltraThemeVerifiedProgramSource(
                profile, path,
                profile.candidate.entries.single { it.name == path }.digest,
                fingerprint, index, 0, state,
            )
        }

        fun binding(
            source: UltraThemeVerifiedProgramSource,
            destination: DisplayDestination,
            index: Int,
            slots: List<UltraThemeSlotIngressBinding>,
            explicit: List<UltraThemeExplicitVideoLayer> = emptyList(),
        ) = UltraThemeOutputSceneBinding(
            source, destination, if (index == 0) mainOutput.outputId else clusterOutput.outputId,
            if (index == 0) PixelSize(800, 400) else PixelSize(1200, 600),
            mapper(if (index == 0) 2.0 else 3.0), slots, explicit,
        )

        fun executor(bindings: List<UltraThemeOutputSceneBinding>) =
            UltraThemeBoundSceneExecutor(coordinator, profile, coordinator.schemaFingerprint,
                coordinator.bindingFingerprint, coordinator.graphSnapshot(),
                bindings + if (bindings.none { it.destination == DisplayDestination.CLUSTER }) {
                    val clusterSource = source(1)
                    listOf(binding(clusterSource, DisplayDestination.CLUSTER, 1,
                        listOf(binding(slot(clusterSource), "cluster-video", clusterIngress,
                            clusterStream))))
                } else emptyList())

        fun close() {
            mainCandidate.close()
            clusterCandidate.close()
            mainOutputLease.close()
            clusterOutputLease.close()
            coordinator.close()
        }
    }

    private fun slot(source: UltraThemeVerifiedProgramSource): CarPlayThemeSceneSlotAddress =
        slots(source).single()

    private fun slots(source: UltraThemeVerifiedProgramSource): List<CarPlayThemeSceneSlotAddress> =
        CarPlayThemeProgramCompiler().compile(
            CarPlayThemeSchema.parseArcPackageJson(
                source.profile.candidate.content(source.resourcePath)!!,
            ).displays[source.displayIndex], source.state,
        ).structuralProgram.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
            .map { it.address }

    private fun binding(
        slot: CarPlayThemeSceneSlotAddress,
        streamIdentifier: String,
        ingressId: UltraIngressId,
        stream: AirPlayVideoStreamHandle,
    ) = UltraThemeSlotIngressBinding(slot, streamIdentifier, ingressId, stream)

    private fun mapper(scale: Double) = CarPlayLogicalPixelMapper(
        AxisAlignedPixelAffine(scale, scale, 0.0, 0.0),
        NegativeExtentPolicy.REJECT,
        PixelEdgeQuantization.REQUIRE_INTEGRAL,
        PixelEdgeQuantization.REQUIRE_INTEGRAL,
    )

    private fun packageJson(
        mainBackground: String = "\"#ff0000\"",
        zoneShape: String = "",
        instrumentExtra: String = "",
        presentationExtra: String = "",
        overlayComponents: String = "",
        extraDisplay: String = "",
        secondDisplayType: String = "cluster",
        extraMainLayout: String = "",
        mainComponentClip: String = "",
        mainSlotFrame: String = "[[3,4],[10,11]]",
        mainZoneFrame: String = "[[10,20],[100,60]]",
        extraMainZone: String = "",
    ): String = """
        {
          "identifier":"test-package","version":1,"minimumSDKVersion":"1",
          "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
          "displays":[
            ${displayJson("centerConsole", "main", mainBackground, "main-video", zoneShape,
                instrumentExtra, presentationExtra, overlayComponents, extraMainLayout,
                mainComponentClip, mainSlotFrame, mainZoneFrame, extraMainZone)},
            ${displayJson(secondDisplayType, "cluster", "\"#0000ff\"", "cluster-video", "", "", "", "")}$extraDisplay
          ]
        }
    """.trimIndent()

    private fun styledPackageJson(packageSource: String = packageJson()): String =
        packageSource.replace(
        "\"id\":\"layout-main\",\"layoutType\":\"primary\"",
        "\"id\":\"layout-main\",\"layoutType\":\"primary\",\"layoutStyleID\":\"bench-style\"",
    ).also { require("\"layoutStyleID\":\"bench-style\"" in it) }

    private fun inertResolvedStyleJson(
        paletteColors: String = "{}",
        colorSet: String = "{}",
    ): String = """{
      "palettes":{"palette-a":{"id":"palette-a","displayName":"Bench",
        "colors":$paletteColors,"representedColorScheme":{"single":{"color":"#ff0000"}},
        "sortIndex":0,"prominent":false}},
      "layoutStyles":{"bench-style":{"palette-a":$colorSet}},
      "defaultPaletteIDs":{"bench-style":"palette-a"},
      "defaultWallpaperIDs":{}
    }""".trimIndent()

    private fun dualPaletteResolvedStyleJson(
        paletteColors: String = "{}",
        colorSet: String = "{}",
    ): String = """{
      "palettes":{
        "palette-a":{"id":"palette-a","displayName":"Bench A",
          "colors":%COLORS%,"representedColorScheme":{"single":{"color":"#ff0000"}},
          "sortIndex":0,"prominent":false},
        "palette-b":{"id":"palette-b","displayName":"Bench B",
          "colors":{},"representedColorScheme":{"single":{"color":"#ff0000"}},
          "sortIndex":1,"prominent":false}},
      "layoutStyles":{"bench-style":{"palette-a":%COLOR_SET%,"palette-b":{}}},
      "defaultPaletteIDs":{"bench-style":"palette-a"},
      "defaultWallpaperIDs":{}
    }""".trimIndent().replace("%COLORS%", paletteColors)
        .replace("%COLOR_SET%", colorSet)

    private fun dualPaletteStyleCatalog(): String = """{
      "format":"xcertplay-debug-uisync-v3","displays":[
        {"profileDisplayIndex":0,"profileDisplayId":"main",
         "advertisedScreenId":"%MAIN_UUID%",
         "destinationRole":"MAIN","destinationInstanceId":"default",
         "topologyDisplayId":"00000000-0000-0000-0000-000000000001",
         "outputId":{"kind":"android","value":"main"},"uiSyncDisplayId":"main",
         "layouts":[
           {"layoutIndex":0,"layoutID":"layout-main","paletteID":"palette-a",
            "activeComponents":{"zone":"primary"},
            "activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
            "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
              "streamIdentifier":"main-video","ingressId":"main-ingress"}],
            "localCanvasSlots":[]},
           {"layoutIndex":0,"layoutID":"layout-main","paletteID":"palette-b",
            "activeComponents":{"zone":"primary"},
            "activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
            "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
              "streamIdentifier":"main-video","ingressId":"main-ingress"}],
            "localCanvasSlots":[]}]},
        {"profileDisplayIndex":1,"profileDisplayId":"cluster",
         "advertisedScreenId":"%ALT_UUID%",
         "destinationRole":"CLUSTER","destinationInstanceId":"default",
         "topologyDisplayId":"00000000-0000-0000-0000-000000000002",
         "outputId":{"kind":"android","value":"cluster"},"uiSyncDisplayId":"cluster",
         "layouts":[{"layoutIndex":0,"layoutID":"layout-cluster","paletteID":"palette-a",
           "activeComponents":{"zone":"primary"},
           "activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
           "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
             "streamIdentifier":"cluster-video","ingressId":"cluster-ingress"}],
           "localCanvasSlots":[]}]}
      ]
    }""".trimIndent().replace("%MAIN_UUID%", AirPlayInfoPlist.MAIN_UUID)
        .replace("%ALT_UUID%", AirPlayInfoPlist.ALT_UUID)

    private fun displayJson(
        type: String,
        id: String,
        background: String,
        stream: String,
        shape: String,
        instrumentExtra: String,
        presentationExtra: String,
        overlayComponents: String,
        extraLayout: String = "",
        componentClip: String = "",
        slotFrame: String = "[[3,4],[10,11]]",
        zoneFrame: String = "[[10,20],[100,60]]",
        extraZone: String = "",
    ): String = """
      {"id":"$id","type":"$type","size":[400,200],"density":2,
       "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
       "layouts":[{"id":"layout-$id","layoutType":"primary",
         "backgroundColor":$background,"safeAreaInsets":[0,0,0,0],
         "driveModeThemeConfigurations":{},
         "zones":[{"id":"zone","frame":$zoneFrame,
           "type":{"single":{}}$shape,
           "components":[{"id":"primary","type":"primary","size":[100,60],
             $componentClip
             "slots":[{"style":{"rectangular":{}},"frame":$slotFrame,
               "zIndex":5,"positionId":"video",
               "instrument":{"kind":{"inputStream":{"_0":"$stream"$presentationExtra}},
                 ${instrumentExtra}"backgroundColor":"#00000000","clipsToShape":false}}
             ]}$overlayComponents]
         }$extraZone]
       }$extraLayout]
      }
    """.trimIndent()

    private fun overlayComponent(id: String, z: Int): String = """
        {"id":"$id","type":"overlay","size":[100,60],
         "slots":[{"style":{"rectangular":{}},"frame":[[1,1],[4,4]],
           "zIndex":$z,"positionId":"$id-video",
           "instrument":{"kind":{"inputStream":{"_0":"main-video"}},
             "backgroundColor":"#00000000","clipsToShape":false}}]}
    """.trimIndent()

    private fun emptyOverlayComponent(frame: String = "[[1,1],[8,9]]",
        componentClip: String = ""): String = """
        {"id":"local","type":"overlay","size":[100,60],
         $componentClip
         "slots":[{"style":{"rectangular":{}},"frame":$frame,
           "zIndex":6,"positionId":"local-canvas"}]}
    """.trimIndent()

    private fun zoneShapeClipJson(): String = """
        "clippingBehavior":{"transitioning":{"none":{}},
          "notTransitioning":{"zoneShape":{}}},
    """.trimIndent()

    private fun secondClippedZone(): String = """
        ,{"id":"zone-two","frame":[[240,40],[100,60]],"type":{"single":{}},
          "components":[{"id":"secondary","type":"overlay","size":[100,60],
            ${zoneShapeClipJson()}
            "slots":[{"style":{"rectangular":{}},"frame":[[4,5],[10,11]],
              "zIndex":6,"positionId":"second-video",
              "instrument":{"kind":{"inputStream":{"_0":"main-video"}},
                "backgroundColor":"#00000000","clipsToShape":false}}]}]}
    """.trimIndent()

    private fun signedLocalClipCatalog(): String = """{
      "format":"xcertplay-debug-uisync-v2","displays":[
        {"profileDisplayIndex":0,"profileDisplayId":"main",
         "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}",
         "destinationRole":"MAIN","destinationInstanceId":"default",
         "topologyDisplayId":"00000000-0000-0000-0000-000000000001",
         "outputId":{"kind":"android","value":"main"},"uiSyncDisplayId":"main",
         "layouts":[{"layoutIndex":0,"layoutID":"layout-main","paletteID":"palette-a",
           "activeComponents":{},"activePrimarySelections":[{"zoneIndex":0,
             "componentIndex":0}],
           "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
             "streamIdentifier":"main-video","ingressId":"main-ingress"}],
           "localCanvasSlots":[{"zoneIndex":0,"componentIndex":1,"slotIndex":0,
             "contentId":"canvas.main.clip"}]}]},
        {"profileDisplayIndex":1,"profileDisplayId":"cluster",
         "advertisedScreenId":"${AirPlayInfoPlist.ALT_UUID}",
         "destinationRole":"CLUSTER","destinationInstanceId":"default",
         "topologyDisplayId":"00000000-0000-0000-0000-000000000002",
         "outputId":{"kind":"android","value":"cluster"},"uiSyncDisplayId":"cluster",
         "layouts":[{"layoutIndex":0,"layoutID":"layout-cluster","paletteID":"palette-a",
           "activeComponents":{},"activePrimarySelections":[{"zoneIndex":0,
             "componentIndex":0}],
           "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
             "streamIdentifier":"cluster-video","ingressId":"cluster-ingress"}],
           "localCanvasSlots":[]}]}]
    }""".trimIndent()

    private fun profile(
        programJson: ByteArray,
        authenticationIdentity: MfiAuthenticationIdentity? = null,
        signedUiSyncCatalog: String? = null,
        resolvedThemeJson: String = "{}",
    ): VerifiedUltraProfile {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                val files = listOf(
                    UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                    UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to programJson,
                    UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to
                        resolvedThemeJson.encodeToByteArray(),
                    UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                        BplistCodec.encode(mapOf("Bundle" to "Phone")),
                    "CarPlayPhone.assets/selected-program.json" to programJson,
                    UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(1),
                    UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(2),
                    UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(3),
                    UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(4),
                    UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to "1")),
                ) + listOfNotNull(signedUiSyncCatalog?.let {
                    (UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
                        if (it.contains("\"format\":\"xcertplay-debug-uisync-v3\"")) {
                            "xcertplay-bench-uisync-v3.json"
                        } else {
                            "xcertplay-bench-uisync-v2.json"
                        }) to it.encodeToByteArray()
                })
                files.forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val inspector = UltraProfilePackageInspector()
        val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
            UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding("vehicle", authenticationIdentity?.let {
            "xcertplay-mfi-authentication-v1:${it.certificateType.name}:${it.sha256Hex()}"
        } ?: "certificate", "asset", "ios",
            "accessory", candidate.contentDigest)
        return (inspector.verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, byteArrayOf(1)))
        } as UltraProfileVerificationResult.Verified).profile
    }

    private class FakeBackend : AndroidUltraRendererBackendPort {
        var phaseCalls = 0
        var onPhaseCall: ((Int) -> Unit)? = null
        var nextDisposition = UltraEglRenderDisposition.PRESENTED
        var lastPlan: UltraResolvedMultiOutputRenderPlan? = null
        @Volatile var onResolve: (() -> Unit)? = null
        @Volatile var submissionCount = 0
        override fun openRemoteIngress(ingress: UltraDisplayGraphIngress) =
            AndroidUltraBackendIngress(UltraEglIngressKey.Remote(ingress.id),
                allocateWithoutConstructor(Surface::class.java)) {
                AndroidVideoSurfaceRetirementResult.RETIRED
            }
        override fun openLocalIngress(contentId: UltraContentId, sizePixels: PixelSize) =
            AndroidUltraBackendIngress(UltraEglIngressKey.Local(contentId),
                allocateWithoutConstructor(Surface::class.java)) {
                AndroidVideoSurfaceRetirementResult.RETIRED
            }
        override fun openOverlayIngress(contentId: UltraContentId, sizePixels: PixelSize) =
            error("not used")
        override fun attachOutput(destination: DisplayDestination, output: UltraDisplayGraphOutput,
            target: AndroidDisplaySurfaceTarget) = AndroidUltraBackendOutput(destination) {}
        override fun resolve(plan: UltraResolvedMultiOutputRenderPlan): UltraEglResolvedSubmission {
            lastPlan = plan
            onResolve?.invoke()
            return UltraEglResolvedSubmission(
                UltraMultiOutputRenderSnapshot(plan.renderRevision, plan.scenes.map { it.scene }),
                plan.graphRevision, emptyMap(), emptyMap(),
            )
        }
        override fun submit(resolved: UltraEglResolvedSubmission): UltraEglRenderTicket =
            UltraEglRenderTicket(resolved.revision).also {
                submissionCount += 1
                it.complete(UltraEglRenderResult(resolved.revision, nextDisposition))
            }
        override fun phase(): UltraEglBackendPhase {
            phaseCalls++
            onPhaseCall?.invoke(phaseCalls)
            return UltraEglBackendPhase.OPEN
        }
        override fun retire(timeoutMillis: Long) = UltraEglBackendRetirementResult.RETIRED
    }

    private fun sessionContext(
        plan: UiSyncRendererStaticPlan,
        schemaFingerprint: UiSyncSchemaFingerprint = fingerprint(1),
        bindingFingerprint: UiSyncBindingFingerprint = fingerprint(2),
    ): UiSyncRendererSessionBindingContext {
        val hub = UltraPresentationHub(Runnable::run)
        val presentation = object : UiSyncPresentationSource {
            override fun subscribeWithReplay(displayId: String, observer: UltraPresentationObserver):
                UiSyncPresentationSubscription = UiSyncPresentationSubscription(
                hub.snapshot(displayId), Closeable {},
            )
            override fun snapshot(displayId: String): UltraPresentationSnapshot = hub.snapshot(displayId)
        }
        val constructor = UiSyncRendererSessionBindingContext::class.java.declaredConstructors
            .single { it.parameterCount == 5 }.apply { isAccessible = true }
        return constructor.newInstance(presentation, plan, schemaFingerprint,
            bindingFingerprint, setOf("main", "cluster")) as
            UiSyncRendererSessionBindingContext
    }

    private inline fun <reified T : Any> fingerprint(fill: Int): T =
        T::class.java.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }.newInstance(ByteArray(32) { fill.toByte() }) as T

    private fun screenContext(stream: AirPlayVideoStreamHandle, id: UltraIngressId):
        UiSyncRendererScreenContext = UiSyncRendererScreenContext::class.java.declaredConstructors
        .single { it.parameterCount == 2 }.apply { isAccessible = true }
        .newInstance(stream, UltraDisplayGraphIngress(id, stream)) as UiSyncRendererScreenContext

    private fun syntheticHandle(
        identity: AirPlayStreamIdentity,
        validatedDisplayUuid: String? = null,
    ): AirPlayVideoStreamHandle {
        val epoch = Class.forName("com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        return AirPlayVideoStreamHandle::class.java.declaredConstructors
            .single { it.parameterCount == 3 }.apply { isAccessible = true }
            .newInstance(identity, epoch, validatedDisplayUuid) as AirPlayVideoStreamHandle
    }

    private fun output(id: String, suffix: Int) = UltraDisplayGraphOutput(
        UltraTopologyDisplayId(UUID.fromString(
            "00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}")),
        UltraDisplayOutputId.Android(id), UltraUiSyncDisplayId(id),
    )

    private fun target(id: String, width: Int, height: Int) = AndroidDisplaySurfaceTarget(
        allocateWithoutConstructor(Surface::class.java),
        DisplaySurfaceDescriptor(DisplaySurfaceId(id), PixelSize(width, height)),
    )

    private companion object {
        fun <T> allocateWithoutConstructor(type: Class<T>): T {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null) as Unsafe
            @Suppress("UNCHECKED_CAST")
            return unsafe.allocateInstance(type) as T
        }
    }
}
