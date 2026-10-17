package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.CompiledCafRuntimeContract
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalRuntimeContract
import com.shilapi.xcertplay.CompiledUiSyncLocalCanvasAssignment
import com.shilapi.xcertplay.CompiledUiSyncInstrumentReferenceAssignment
import com.shilapi.xcertplay.CompiledUiSyncRuntimeContract
import com.shilapi.xcertplay.CompiledUiSyncSlotIngressAssignment
import com.shilapi.xcertplay.CompiledUiSyncThemeIntent
import com.shilapi.xcertplay.CompiledUiSyncThemeVariant
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.deriveVerifiedUiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
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
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramIssueCode
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeResolvedStyleWitness
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayResolvedThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.logicalFrame
import com.shilapi.xcertplay.vehicle.ultra.theme.logicalSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchUiSyncProfileCompilerTest {
    private val compiler = BenchUiSyncProfileCompiler()

    @Test fun generatedV3CarouselCanvasCompilesThreeExactSameLayoutVariants() {
        val script = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }
            .map { File(it, "scripts/create_debug_bench_profile.py") }
            .firstOrNull(File::isFile)
        assertTrue("Debug bench generator is absent", script != null)
        val directory = Files.createTempDirectory("xcertplay-carousel-main-v3-")
        val output = directory.resolve("profile.zip")
        try {
            val process = ProcessBuilder(
                "python3", requireNotNull(script).absolutePath,
                "--main-carousel-canvas", output.toString(),
            ).redirectErrorStream(true).start()
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(diagnostics, 0, process.waitFor())
            val archive = Files.readAllBytes(output)
            val profile = verifiedProfile(archive)
            assertTrue(BenchUiSyncProfileCompiler.hasCatalog(profile))
            assertTrue(profile.candidate.content(BenchUiSyncProfileCompiler.SPEC_PATH) == null)
            assertTrue(profile.candidate.content(BenchUiSyncProfileCompiler.SPEC_PATH_V2) == null)
            assertTrue(profile.candidate.content(BenchUiSyncProfileCompiler.SPEC_PATH_V3) != null)
            assertTrue(compiler.declaredIngresses(profile).isEmpty())
            val main = MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                uiSyncDisplayId = UltraUiSyncDisplayId("xcertplay-main")))
            val compiled = compiler.compile(profile, listOf(main), emptyList())
            val assignment = compiled.assignments.single()
            val variants = listOf(
                Triple(requireNotNull(assignment.primaryIntent), assignment.programSelection,
                    assignment.localCanvasAssignments),
            ) + assignment.additionalVariants.map {
                Triple(it.intent, it.programSelection, it.localCanvasAssignments)
            }
            assertEquals(3, variants.size)
            assertEquals(listOf(0, 0, 0), variants.map { it.second.layoutIndex })
            assertEquals(listOf("main-carousel-canvas", "main-carousel-canvas",
                "main-carousel-canvas"), variants.map { it.first.layoutID })
            // The issuer keeps the signed primary row, then canonical-sorts additional
            // variants by the complete intent rather than preserving JSON row order.
            assertEquals(listOf("map", "map", "media"), variants.map {
                it.first.activeComponents["main-carousel-zone"]
            })
            assertEquals(listOf("palette-a", "palette-b", "palette-a"), variants.map {
                it.first.paletteID
            })
            assertEquals(listOf(0, 0, 1), variants.map {
                it.second.activePrimarySelections.single().componentIndex
            })
            assertEquals(listOf("canvas.main.bench", "canvas.main.carousel.map.alt",
                "canvas.main.carousel.media"), variants.map {
                it.third.single().contentId
            })
            assertEquals(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
                compiled.assignments), compiled.semanticThemeProgramFingerprint)

            val packageJson = requireNotNull(profile.candidate.content(
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON)).decodeToString()
            val spec = requireNotNull(profile.candidate.content(
                BenchUiSyncProfileCompiler.SPEC_PATH_V3)).decodeToString()
            val firstMap = "\"activeComponents\":{\"main-carousel-zone\":\"map\"}"
            assertTrue(spec.contains(firstMap))
            val malformed = listOf(
                spec.replaceFirst(firstMap, "\"activeComponents\":{}"),
                spec.replaceFirst(firstMap,
                    "\"activeComponents\":{\"foreign-zone\":\"map\"}"),
                spec.replaceFirst(firstMap,
                    "\"activeComponents\":{\"main-carousel-zone\":\"foreign\"}"),
                spec.replaceFirst("\"paletteID\":\"palette-b\"",
                    "\"paletteID\":\"palette-a\""),
                spec.replaceFirst("\"componentIndex\":0", "\"componentIndex\":1"),
                spec.replaceFirst("\"format\":\"xcertplay-debug-uisync-v3\"",
                    "\"format\":\"xcertplay-debug-uisync-v2\""),
            )
            malformed.forEach { changed ->
                assertNotEquals(spec, changed)
                assertThrows(IllegalArgumentException::class.java) {
                    compiler.compile(profile(packageJson, changed,
                        BenchUiSyncProfileCompiler.SPEC_PATH_V3), listOf(main), emptyList())
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(packageJson, spec,
                    BenchUiSyncProfileCompiler.SPEC_PATH_V2), listOf(main), emptyList())
            }
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(packageJson, spec,
                    BenchUiSyncProfileCompiler.SPEC_PATH_V3,
                    BenchUiSyncProfileCompiler.SPEC_PATH_V2 to
                        spec.replace("xcertplay-debug-uisync-v3", "xcertplay-debug-uisync-v2")),
                    listOf(main), emptyList())
            }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun v3CanonicalizesEquivalentMultiZonePrimarySelectionOrder() {
        val maps = """{
          "id":"maps","frame":[[0,0],[400,480]],"type":{"mapsMedia":{}},
          "components":[{"id":"map","type":"primary","size":[400,480],"slots":[]}]
        }""".trimIndent()
        val media = """{
          "id":"media","frame":[[400,0],[400,480]],"type":{"dca":{}},
          "components":[{"id":"tune","type":"primary","size":[400,480],"slots":[]}]
        }""".trimIndent()
        val packageJson = """{
          "identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug",
          "partnerID":"debug-bench","driveModeLinkedByDefault":false,"displays":[{
            "id":"main","type":"centerConsole","size":[800,480],"density":1,
            "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
            "layouts":[${layout("main-a", "#111111", "$maps,$media")}]
          }]
        }""".trimIndent()
        val forward = """[{"zoneIndex":0,"componentIndex":0},
          {"zoneIndex":1,"componentIndex":0}]""".trimIndent()
        val reversed = """[{"zoneIndex":1,"componentIndex":0},
          {"zoneIndex":0,"componentIndex":0}]""".trimIndent()
        fun catalog(selections: String) = """{
          "format":"xcertplay-debug-uisync-v3","displays":[{
            "profileDisplayIndex":0,"profileDisplayId":"main",
            "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}","destinationRole":"MAIN",
            "destinationInstanceId":"default","topologyDisplayId":"$MAIN_TOPOLOGY",
            "outputId":{"kind":"android","value":"host.main"},"uiSyncDisplayId":"ui.main",
            "layouts":[{"layoutIndex":0,"layoutID":"main-a","paletteID":"palette-a",
              "activeComponents":{"maps":"map","media":"tune"},
              "activePrimarySelections":$selections,
              "slotIngresses":[],"localCanvasSlots":[]}]
          }]
        }""".trimIndent()
        val first = compiler.compile(profile(packageJson, catalog(forward),
            BenchUiSyncProfileCompiler.SPEC_PATH_V3), listOf(MAIN_HOST), emptyList())
            .assignments.single()
        val second = compiler.compile(profile(packageJson, catalog(reversed),
            BenchUiSyncProfileCompiler.SPEC_PATH_V3), listOf(MAIN_HOST), emptyList())
            .assignments.single()
        assertEquals(first.primaryIntent, second.primaryIntent)
        assertEquals(first.programSelection.activePrimarySelections,
            second.programSelection.activePrimarySelections)
        assertEquals(listOf(0, 1), first.programSelection.activePrimarySelections.map {
            it.zoneIndex
        })
        assertEquals(first.programSelection.structuralFingerprint,
            second.programSelection.structuralFingerprint)
        val duplicate = """[{"zoneIndex":0,"componentIndex":0},
          {"zoneIndex":0,"componentIndex":0}]""".trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(packageJson, catalog(duplicate),
                BenchUiSyncProfileCompiler.SPEC_PATH_V3), listOf(MAIN_HOST), emptyList())
        }
    }

    @Test fun generatedLocalMainV2ZipCompilesWithTestOnlyProfileTrust() {
        val script = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }
            .map { File(it, "scripts/create_debug_bench_profile.py") }
            .firstOrNull(File::isFile)
        assertTrue("Debug bench generator is absent", script != null)
        val directory = Files.createTempDirectory("xcertplay-local-main-v2-")
        val output = directory.resolve("profile.zip")
        try {
            val process = ProcessBuilder(
                "python3", requireNotNull(script).absolutePath,
                "--main-local-canvas", output.toString(),
            ).redirectErrorStream(true).start()
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(diagnostics, 0, process.waitFor())
            val profile = verifiedProfile(Files.readAllBytes(output))
            assertTrue(profile.candidate.content(BenchUiSyncProfileCompiler.SPEC_PATH) == null)
            assertTrue(profile.candidate.content(BenchUiSyncProfileCompiler.SPEC_PATH_V2) != null)
            assertTrue(compiler.declaredIngresses(profile).isEmpty())
            val main = MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                uiSyncDisplayId = UltraUiSyncDisplayId("xcertplay-main")))
            val compiled = compiler.compile(profile, listOf(main), emptyList())
            val selected = compiled.assignments.single()
            assertEquals("main-local-canvas", selected.primaryIntent?.layoutID)
            assertTrue(selected.slotIngressAssignments.isEmpty())
            assertEquals("canvas.main.bench",
                selected.localCanvasAssignments.single().contentId)
            assertTrue(BenchLocalCanvasPainter.selectedBy(compiled.assignments))
            assertFalse(BenchLocalCanvasPainter.selectedBy(emptyList()))
            assertEquals(0, selected.localCanvasAssignments.single().slot.slotIndex)
            assertEquals(main.output, compiled.outputs.single())
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun generatedPhoneOverrideMetadataCandidateCompilesOnlyWithExactInertStyleWitness() {
        val script = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }.map { File(it, "scripts/create_debug_bench_profile.py") }
            .firstOrNull(File::isFile)
        assertTrue("Debug bench generator is absent", script != null)
        val directory = Files.createTempDirectory("xcertplay-phone-style-witness-")
        val output = directory.resolve("profile.zip")
        try {
            val process = ProcessBuilder(
                "python3", requireNotNull(script).absolutePath,
                "--main-local-canvas", "--phone-override-metadata-probe", output.toString(),
            ).redirectErrorStream(true).start()
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(diagnostics, 0, process.waitFor())
            val generated = verifiedProfile(Files.readAllBytes(output))
            val packageJson = requireNotNull(generated.candidate.content(
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON)).decodeToString()
            val themeJson = requireNotNull(generated.candidate.content(
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON)).decodeToString()
            val spec = requireNotNull(generated.candidate.content(
                BenchUiSyncProfileCompiler.SPEC_PATH_V2)).decodeToString()
            val main = MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                uiSyncDisplayId = UltraUiSyncDisplayId("xcertplay-main")))
            val compiled = compiler.compile(generated, listOf(main), emptyList())
            assertEquals("main-local-canvas", compiled.assignments.single().primaryIntent?.layoutID)
            assertEquals("canvas.main.bench",
                compiled.assignments.single().localCanvasAssignments.single().contentId)

            val witness = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(generated, 0, 0)
            val packageEntry = generated.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }
            val themeEntry = generated.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON
            }
            assertEquals(generated.candidate.contentDigest, witness.profileContentDigest)
            assertEquals(packageEntry.digest, witness.phonePackageDigest)
            assertEquals(themeEntry.digest, witness.phoneThemeDigest)
            assertEquals("palette-a", witness.defaultPaletteID)
            val untrustedSameBytes = CarPlayThemeSchema.parseArcPackageJson(
                packageJson.encodeToByteArray()).displays.single()
            val noWitness = CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
                untrustedSameBytes, 0, 0)
            assertTrue(noWitness.proof == null)
            assertTrue(noWitness.issues.any { it.code ==
                CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_FIELD })
            val foreignDisplay = CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
                untrustedSameBytes, 0, 0, resolvedStyleWitness = witness)
            assertTrue(foreignDisplay.proof == null)
            assertTrue(foreignDisplay.issues.any { it.code ==
                CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_FIELD })
            val exactDisplay = CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
                witness.display, 0, 0, resolvedStyleWitness = witness)
            assertTrue(exactDisplay.issues.toString(), exactDisplay.proof != null)

            val unknownStyle = packageJson.replaceFirst(
                "\"layoutStyleID\":\"bench-style\"",
                "\"layoutStyleID\":\"missing-style\"",
            )
            assertNotEquals(packageJson, unknownStyle)
            val nonemptyStyle = themeJson.replaceFirst(
                "\"bench-style\":{\"palette-a\":{}}",
                "\"bench-style\":{\"palette-a\":{\"paint\":{\"single\":{\"value\":{\"stops\":{\"0\":\"#101820\"}}}}}}",
            )
            assertNotEquals(themeJson, nonemptyStyle)
            assertTrue(CarPlayResolvedThemeSchema.parseJson(
                nonemptyStyle.encodeToByteArray()).layoutStyles.getValue("bench-style")
                .getValue("palette-a").isNotEmpty())
            val wrongPalette = themeJson.replaceFirst("\"id\":\"palette-a\"",
                "\"id\":\"foreign-palette\"")
            assertNotEquals(themeJson, wrongPalette)
            val extraLayoutDrawing = packageJson.replaceFirst(
                "\"layoutStyleID\":\"bench-style\"",
                "\"layoutStyleID\":\"bench-style\",\"preview\":{}",
            )
            assertNotEquals(packageJson, extraLayoutDrawing)
            val wrongSignedPalette = spec.replaceFirst(
                "\"paletteID\":\"palette-a\"",
                "\"paletteID\":\"foreign-palette\"",
            )
            assertNotEquals(spec, wrongSignedPalette)
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(packageJson, wrongSignedPalette,
                    BenchUiSyncProfileCompiler.SPEC_PATH_V2, themeJson = themeJson),
                    listOf(main), emptyList())
            }
            listOf(
                unknownStyle to themeJson,
                packageJson to nonemptyStyle,
                packageJson to wrongPalette,
                extraLayoutDrawing to themeJson,
            ).forEach { (changedPackage, changedTheme) ->
                val tampered = profile(changedPackage, spec,
                    BenchUiSyncProfileCompiler.SPEC_PATH_V2, themeJson = changedTheme)
                assertThrows(IllegalArgumentException::class.java) {
                    compiler.compile(tampered, listOf(main), emptyList())
                }
                assertThrows(IllegalArgumentException::class.java) {
                    deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
                        tampered, compiled.assignments,
                    )
                }
            }
            val changedBackground = packageJson.replaceFirst(
                "\"backgroundColor\":\"#101820\"",
                "\"backgroundColor\":\"#202020\"",
            )
            assertNotEquals(packageJson, changedBackground)
            val altered = profile(changedBackground, spec,
                BenchUiSyncProfileCompiler.SPEC_PATH_V2, themeJson = themeJson)
            assertNotEquals(witness.phonePackageDigest, altered.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest)
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(altered, listOf(main), emptyList())
            }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun generatedNonDefaultLocalMainV2DimensionsCompileWithoutDensityInference() {
        val script = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile
        }
            .map { File(it, "scripts/create_debug_bench_profile.py") }
            .firstOrNull(File::isFile)
        assertTrue("Debug bench generator is absent", script != null)
        val directory = Files.createTempDirectory("xcertplay-sized-local-main-v2-")
        val output = directory.resolve("profile.zip")
        try {
            val process = ProcessBuilder(
                "python3", requireNotNull(script).absolutePath,
                "--main-local-canvas", "--main-width", "1920", "--main-height", "720",
                output.toString(),
            ).redirectErrorStream(true).start()
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(diagnostics, 0, process.waitFor())
            val profile = verifiedProfile(Files.readAllBytes(output))
            val packageBytes = requireNotNull(profile.candidate.content(
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON))
            val display = CarPlayThemeSchema.parseArcPackageJson(packageBytes).displays.single()
            assertEquals(1920.0, display.logicalSize().width, 0.0)
            assertEquals(720.0, display.logicalSize().height, 0.0)
            assertEquals(1.0, display.density, 0.0)
            val zone = display.layouts.single().zones.single()
            assertEquals(display.logicalSize(), zone.logicalFrame().size)
            assertEquals(0.0, zone.logicalFrame().origin.x, 0.0)
            assertEquals(0.0, zone.logicalFrame().origin.y, 0.0)
            val component = zone.components.single()
            assertEquals(display.logicalSize(), component.logicalSize())
            val slot = component.slots.single()
            assertEquals(display.logicalSize(), slot.logicalFrame().size)
            assertEquals(0.0, slot.logicalFrame().origin.x, 0.0)
            assertEquals(0.0, slot.logicalFrame().origin.y, 0.0)

            val main = MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                uiSyncDisplayId = UltraUiSyncDisplayId("xcertplay-main")))
            val compiled = compiler.compile(profile, listOf(main), emptyList())
            assertEquals("main-local-canvas", compiled.assignments.single().primaryIntent?.layoutID)
            assertTrue(compiled.assignments.single().slotIngressAssignments.isEmpty())
            assertEquals("canvas.main.bench",
                compiled.assignments.single().localCanvasAssignments.single().contentId)
            assertEquals(main.output, compiled.outputs.single())
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun exactSignedNativeClusterReferenceCompilesButMissingOrForeignCrosswalkDoesNot() {
        val instrument = BenchInstrumentSignalProfileCompilerTest
        val clusterPackage = instrument.THEME_PACKAGE.replace(
            "\"safeAreaInsets\":{}", "\"safeAreaInsets\":[0,0,0,0]",
        ).replace(
            "\"backgroundColor\":\"black\"", "\"backgroundColor\":\"#000000\"",
        ).replace(
            "\"backgroundColor\":\"#000000\"}}",
            "\"backgroundColor\":\"#000000\",\"clipsToShape\":false}}",
        )
        val catalog = """{
          "format":"xcertplay-debug-uisync-v1","displays":[
            ${displaySpec(0, "cluster-display", AirPlayInfoPlist.ALT_UUID, "CLUSTER",
                CLUSTER_TOPOLOGY, "host.cluster", "ui.cluster", "main", null,
                """[{"zoneIndex":0,"componentIndex":0}]""")}
          ]
        }""".trimIndent()
        fun signed(spec: String? = instrument.SPEED_SPEC, packageJson: String = clusterPackage) =
            profile(packageJson, catalog,
                extraSpec = spec?.let { BenchInstrumentSignalProfileCompiler.SPEC_PATH to it },
                cafSpec = instrument.CAF_SPEC)
        val host = CLUSTER_HOST.copy(profileDisplayIndex = 0)
        val valid = signed()
        val compiled = compiler.compile(valid, listOf(host), emptyList())
        val reference = compiled.assignments.single().instrumentReferenceAssignments.single()
        assertEquals("dial", reference.slot.positionId)
        assertEquals("speed", reference.spec.address.dataIdentifier)
        assertTrue(compiled.assignments.single().slotIngressAssignments.isEmpty())
        assertTrue(compiled.assignments.single().localCanvasAssignments.isEmpty())
        assertEquals(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(valid,
            compiled.assignments), compiled.semanticThemeProgramFingerprint)

        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(3, 4, 5)
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        })
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(valid.binding.vehicleId, identity), identity,
        )
        val cafFingerprint = fingerprint(CafSchemaFingerprint::class.java, 0x31)
        val caf = CompiledCafRuntimeContract(cafFingerprint, "test.caf.decoder",
            mapOf(7L to "test.caf.handler"))
        val signedCrosswalk = requireNotNull(BenchInstrumentSignalProfileCompiler().compile(valid))
        val instrumentContract = CompiledInstrumentSignalRuntimeContract(
            valid,
            valid.candidate.entries.single {
                it.name == BenchInstrumentSignalProfileCompiler.SPEC_PATH
            }.digest,
            signedCrosswalk.phonePackageDigest,
            cafFingerprint,
            signedCrosswalk.bindings.map {
                CompiledInstrumentSignalDisplayAssignment(
                    it.profileDisplayIndex, it.profileDisplayId, it.spec,
                )
            },
        )
        val schema = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21)
        val binding = fingerprint(UiSyncBindingFingerprint::class.java, 0x22)
        fun uiContract(assignments: List<CompiledUiSyncDisplayAssignment>) =
            CompiledUiSyncRuntimeContract(schema, binding,
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(valid, assignments),
                "test.native.reference.renderer", setOf("ui.cluster"), compiled.outputs,
                emptyList(), assignments)
        val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 9 })
        val manifest = issuer.issue(request, valid, uiContract(compiled.assignments), caf,
            instrumentContract)
        val selected = requireNotNull(manifest.selectThemeVariantFor(valid,
            compiled.assignments.single().uiSyncDisplayId,
            requireNotNull(compiled.assignments.single().primaryIntent)))
        assertEquals(reference, selected.instrumentReferenceAssignments.single())
        assertTrue(selected.instrumentSignalAssignments.single() ===
            instrumentContract.assignments.single())
        assertTrue(manifest.instrumentAssignmentsFor(valid).single() ===
            selected.instrumentSignalAssignments.single())

        // A valid static Theme proof and a self-consistent forged UI Sync fingerprint cannot
        // turn a different signal freshness contract into an issuer-backed reference.
        val forgedSpec = InstrumentSignalBundleSpec(reference.spec.address,
            reference.spec.shape, reference.spec.sources,
            reference.spec.maximumReadAgeMillis + 1)
        val original = compiled.assignments.single()
        val forged = CompiledUiSyncDisplayAssignment(
            original.phonePackageDigest, original.profileDisplayIndex, original.profileDisplayId,
            original.profileDisplayType, original.destination, original.topologyDisplayId,
            original.outputId, original.uiSyncDisplayId, original.programSelection,
            original.slotIngressAssignments, original.primaryIntent, original.additionalVariants,
            original.localCanvasAssignments,
            listOf(CompiledUiSyncInstrumentReferenceAssignment(reference.slot, forgedSpec)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            issuer.issue(request, valid, uiContract(listOf(forged)), caf, instrumentContract)
        }
        assertThrows(IllegalArgumentException::class.java) {
            issuer.issue(request, valid, uiContract(compiled.assignments), caf, null)
        }
        val foreignCaf = CompiledCafRuntimeContract(
            fingerprint(CafSchemaFingerprint::class.java, 0x32),
            "test.caf.decoder", mapOf(7L to "test.caf.handler"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            issuer.issue(request, valid, uiContract(compiled.assignments), foreignCaf,
                instrumentContract)
        }

        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(signed(spec = null), listOf(host), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(signed(spec = instrument.SPEED_SPEC.replace(
                "\"dataIdentifier\":\"speed\"", "\"dataIdentifier\":\"foreign\"")),
                listOf(host), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(signed(packageJson = clusterPackage.replace(
                "\"clipsToShape\":false", "\"clipsToShape\":true")),
                listOf(host), emptyList())
        }
    }

    @Test fun compilesTwoSignedOutputsAndMultipleStaticLayoutsWithoutInferringPhysicalMetrics() {
        val profile = profile(twoDisplayPackage(), twoDisplaySpec())
        val declared = compiler.declaredDisplays(profile)
        assertEquals(listOf(0, 1), declared.map { it.profileDisplayIndex })
        assertEquals(listOf(MAIN_HOST.advertisedScreenId, CLUSTER_HOST.advertisedScreenId),
            declared.map { it.advertisedScreenId })
        assertEquals(listOf(MAIN_HOST.destination, CLUSTER_HOST.destination),
            declared.map { it.destination })
        assertEquals(listOf(MAIN_HOST.output, CLUSTER_HOST.output),
            declared.map { it.output })
        assertTrue(compiler.declaredIngresses(profile).isEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (declared as MutableList<*>).clear()
        }
        val result = compiler.compile(profile, listOf(CLUSTER_HOST, MAIN_HOST), emptyList())

        assertEquals(listOf(0, 1), result.assignments.map { it.profileDisplayIndex })
        assertEquals(listOf(MAIN_HOST.output, CLUSTER_HOST.output), result.outputs)
        assertEquals(listOf(1, 1), result.assignments.map { it.additionalVariants.size })
        assertEquals("main-a", result.assignments[0].primaryIntent?.layoutID)
        assertEquals("main-b", result.assignments[0].additionalVariants.single().intent.layoutID)
        assertEquals("cluster-b", result.assignments[1].additionalVariants.single().intent.layoutID)
        assertEquals(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
            result.assignments), result.semanticThemeProgramFingerprint)
        assertFalse(result.assignments.any { it.slotIngressAssignments.isNotEmpty() })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result.assignments as MutableList<*>).clear()
        }
    }

    @Test fun exactSignedAndHostOutputIdentitiesCannotBeRemappedOrDuplicated() {
        val profile = profile(twoDisplayPackage(), twoDisplaySpec())
        val replacements = listOf(
            MAIN_HOST.copy(advertisedScreenId = AirPlayInfoPlist.ALT_UUID),
            MAIN_HOST.copy(destination = DisplayDestination.CLUSTER),
            MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                topologyDisplayId = UltraTopologyDisplayId(UUID.fromString(CLUSTER_TOPOLOGY)))),
            MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                outputId = UltraDisplayOutputId.Android("other.main"))),
            MAIN_HOST.copy(output = MAIN_HOST.output.copy(
                uiSyncDisplayId = UltraUiSyncDisplayId("other.ui"))),
        )
        replacements.forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile, listOf(changed, CLUSTER_HOST), emptyList())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile, listOf(MAIN_HOST, MAIN_HOST), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile, listOf(MAIN_HOST), emptyList())
        }
        val tampered = twoDisplaySpec().replace("\"destinationInstanceId\":\"default\"",
            "\"destinationInstanceId\":\"second\"")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(twoDisplayPackage(), tampered),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
    }

    @Test fun rejectsForeignDuplicateAndAmbiguousSignedProfileIds() {
        val base = twoDisplaySpec()
        val badSpecs = listOf(
            base.replace("\"profileDisplayIndex\":1", "\"profileDisplayIndex\":2"),
            base.replace("\"uiSyncDisplayId\":\"ui.cluster\"",
                "\"uiSyncDisplayId\":\"ui.main\""),
            base.replace("\"layoutIndex\":1", "\"layoutIndex\":0"),
            base.replace("\"layoutID\":\"main-b\"", "\"layoutID\":\"foreign\""),
            base.replace("\"topologyDisplayId\":\"$MAIN_TOPOLOGY\"",
                "\"topologyDisplayId\":\"$CLUSTER_TOPOLOGY\""),
            base.replace("\"outputId\":{\"kind\":\"android\"",
                "\"outputId\":{\"kind\":\"custom\""),
        )
        badSpecs.forEach { spec ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(twoDisplayPackage(), spec),
                    listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
            }
        }
        val repeatedProfileId = twoDisplayPackage().replace("\"id\":\"cluster\"",
            "\"id\":\"main\"")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(repeatedProfileId, base),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
    }

    @Test fun onlyConfirmedThemeDisplayToDestinationRolesAreExecutable() {
        val wrongSignedRole = twoDisplaySpec().replaceFirst(
            "\"destinationRole\":\"MAIN\"", "\"destinationRole\":\"CLUSTER\"")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(twoDisplayPackage(), wrongSignedRole),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
        val passenger = twoDisplayPackage().replace("\"type\":\"centerConsole\"",
            "\"type\":\"passenger\"")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.declaredDisplays(profile(passenger, twoDisplaySpec()))
        }
    }

    @Test fun compilesExactSignedVideoSlotToHostIngressAndRejectsForeignOrUncoveredIngress() {
        val profile = profile(videoPackage(), videoSpec())
        assertEquals(listOf(BenchUiSyncDeclaredIngress(UltraIngressId("ingress.main"),
            AirPlayInfoPlist.MAIN_UUID, 110)), compiler.declaredIngresses(profile))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (compiler.declaredIngresses(profile) as MutableList<*>).clear()
        }
        val ingress = UiSyncRendererIngressSpec(
            UltraIngressId("ingress.main"),
            UltraAirPlayStreamIdentity(type = 110),
            setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID,
        )
        val result = compiler.compile(profile, listOf(MAIN_HOST), listOf(ingress))
        assertEquals(1, result.assignments.single().slotIngressAssignments.size)
        assertEquals("video.main", result.assignments.single()
            .slotIngressAssignments.single().streamIdentifier)
        assertEquals(ingress, result.assignments.single()
            .slotIngressAssignments.single().ingressSpec)
        assertEquals(1, result.ingressSpecs.size)

        val badSpecs = listOf(
            videoSpec().replace("\"streamIdentifier\":\"video.main\"",
                "\"streamIdentifier\":\"foreign.stream\""),
            videoSpec().replace("\"ingressId\":\"ingress.main\"",
                "\"ingressId\":\"foreign.ingress\""),
            videoSpec().replace("\"slotIndex\":0", "\"slotIndex\":1"),
            videoSpec().replace("\"slotIngresses\":[", "\"slotIngresses\":[] , \"extra\":["),
        )
        badSpecs.forEach { spec ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(videoPackage(), spec), listOf(MAIN_HOST), listOf(ingress))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile, listOf(MAIN_HOST), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile, listOf(MAIN_HOST), listOf(UiSyncRendererIngressSpec(
                UltraIngressId("ingress.main"),
                UltraAirPlayStreamIdentity(type = 110, streamConnectionId = "video.main"),
                setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID,
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile, listOf(MAIN_HOST), listOf(UiSyncRendererIngressSpec(
                UltraIngressId("ingress.main"), UltraAirPlayStreamIdentity(type = 111),
                setupDisplayUuid = AirPlayInfoPlist.ALT_UUID,
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile, listOf(MAIN_HOST), listOf(ingress,
                UiSyncRendererIngressSpec(UltraIngressId("unused"),
                    UltraAirPlayStreamIdentity(111),
                    setupDisplayUuid = AirPlayInfoPlist.ALT_UUID)))
        }
    }

    @Test fun unsupportedActiveDrawingAndWireSemanticsFailClosed() {
        val opaque = twoDisplayPackage().replace("\"backgroundColor\":\"#111111\"",
            "\"backgroundColor\":\"#11111180\"")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(opaque, twoDisplaySpec()),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
        val activeComponents = twoDisplaySpec().replaceFirst(
            "\"activeComponents\":{}", "\"activeComponents\":{\"zone\":\"component\"}")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(twoDisplayPackage(), activeComponents),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
    }

    @Test fun oneSignedIngressCannotAmbiguouslySelectMainAndClusterScreens() {
        val packageJson = twoDisplayPackage()
            .replace("\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"main-a\",\"layoutType\":\"primary\",\"zones\":[$VIDEO_ZONE]")
            .replace("\"id\":\"cluster-a\",\"layoutType\":\"primary\",\"zones\":[]",
                "\"id\":\"cluster-a\",\"layoutType\":\"primary\",\"zones\":[${
                    VIDEO_ZONE.replace("video.main", "video.cluster")
                }]")
        val selection = "[{\"zoneIndex\":0,\"componentIndex\":0}]"
        fun slot(stream: String, ingress: String = "ingress.shared") =
            "[{\"zoneIndex\":0,\"componentIndex\":0," +
            "\"slotIndex\":0,\"streamIdentifier\":\"$stream\"," +
            "\"ingressId\":\"$ingress\"}]"
        val spec = """{"format":"xcertplay-debug-uisync-v1","displays":[
          ${displaySpec(0, "main", AirPlayInfoPlist.MAIN_UUID, "MAIN", MAIN_TOPOLOGY,
              "host.main", "ui.main", "main-a", null, selection, slot("video.main"))},
          ${displaySpec(1, "cluster", AirPlayInfoPlist.ALT_UUID, "CLUSTER", CLUSTER_TOPOLOGY,
              "host.cluster", "ui.cluster", "cluster-a", null, selection, slot("video.cluster"))}
        ]}"""
        val failure = assertThrows(IllegalArgumentException::class.java) {
            compiler.declaredIngresses(profile(packageJson, spec))
        }
        assertTrue(failure.message?.contains("different Host screens") == true)

        // A stream name is scoped to its Profile display. Distinct MAIN/ALT ingresses may use
        // the same signed InputStream identifier without being conflated across outputs.
        val separateSpec = """{"format":"xcertplay-debug-uisync-v1","displays":[
          ${displaySpec(0, "main", AirPlayInfoPlist.MAIN_UUID, "MAIN", MAIN_TOPOLOGY,
              "host.main", "ui.main", "main-a", null, selection,
              slot("video.main", "ingress.main"))},
          ${displaySpec(1, "cluster", AirPlayInfoPlist.ALT_UUID, "CLUSTER", CLUSTER_TOPOLOGY,
              "host.cluster", "ui.cluster", "cluster-a", null, selection,
              slot("video.main", "ingress.cluster"))}
        ]}"""
        val separateProfile = profile(packageJson.replace("video.cluster", "video.main"),
            separateSpec)
        assertEquals(2, compiler.declaredIngresses(separateProfile).size)
        val compiled = compiler.compile(separateProfile, listOf(MAIN_HOST, CLUSTER_HOST), listOf(
            UiSyncRendererIngressSpec(UltraIngressId("ingress.main"),
                UltraAirPlayStreamIdentity(110), setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID),
            UiSyncRendererIngressSpec(UltraIngressId("ingress.cluster"),
                UltraAirPlayStreamIdentity(111), setupDisplayUuid = AirPlayInfoPlist.ALT_UUID),
        ))
        assertEquals(2, compiled.assignments.size)
    }

    @Test fun strictBoundedSchemaRejectsUnknownDuplicateAndFractionalFields() {
        val specs = listOf(
            twoDisplaySpec().replaceFirst("\"format\":",
                "\"unknown\":true,\"format\":"),
            twoDisplaySpec().replaceFirst("\"format\":",
                "\"format\":\"xcertplay-debug-uisync-v1\",\"format\":"),
            twoDisplaySpec().replaceFirst("\"profileDisplayIndex\":0",
                "\"profileDisplayIndex\":0.0"),
            twoDisplaySpec().replaceFirst("\"profileDisplayIndex\":0",
                "\"profileDisplayIndex\":18446744073709551616"),
            twoDisplaySpec().replaceFirst("\"activePrimarySelections\":[]",
                "\"activePrimarySelections\":{},\"extra\":[]"),
            twoDisplaySpec().replaceFirst("\"activeComponents\":{}",
                "\"activeComponents\":{\"zone\":\"component\"}"),
        )
        specs.forEach { spec ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.declaredDisplays(profile(twoDisplayPackage(), spec))
            }
        }
        // Incompressible padding stays inside the package inspector's ZIP-ratio guard, so this
        // test reaches the compiler's own encoded-JSON byte limit.
        val tooLarge = twoDisplaySpec() + buildString {
            val random = java.util.Random(1L)
            repeat(256 * 1024) { append(('a'.code + random.nextInt(26)).toChar()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(twoDisplayPackage(), tooLarge),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
    }

    @Test fun signedCatalogOrThemeChangesSemanticFingerprint() {
        val first = compiler.compile(profile(twoDisplayPackage(), twoDisplaySpec()),
            listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        val second = compiler.compile(profile(
            twoDisplayPackage().replace("#111111", "#171717"), twoDisplaySpec()),
            listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        val third = compiler.compile(profile(twoDisplayPackage(),
            twoDisplaySpec().replaceFirst("\"paletteID\":\"palette-a\"",
                "\"paletteID\":\"palette-other\"")),
            listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        assertNotEquals(first.semanticThemeProgramFingerprint,
            second.semanticThemeProgramFingerprint)
        assertNotEquals(first.semanticThemeProgramFingerprint,
            third.semanticThemeProgramFingerprint)
    }

    @Test fun v2CompilesOnlySignedActiveEmptyRectanglesAndKeepsVariantsDistinct() {
        val profile = profile(localCanvasPackage(), localCanvasSpec(),
            BenchUiSyncProfileCompiler.SPEC_PATH_V2)
        assertTrue(compiler.declaredIngresses(profile).isEmpty())
        val compiled = compiler.compile(profile, listOf(MAIN_HOST), emptyList())
        val display = compiled.assignments.single()
        assertEquals("canvas.main.a", display.localCanvasAssignments.single().contentId)
        assertEquals(0, display.localCanvasAssignments.single().slot.slotIndex)
        assertEquals("canvas.main.b", display.additionalVariants.single()
            .localCanvasAssignments.single().contentId)
        assertEquals(deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
            compiled.assignments), compiled.semanticThemeProgramFingerprint)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (display.localCanvasAssignments as MutableList<*>).clear()
        }

        val forgedContent = copyWithLocalCanvas(display, listOf(
            CompiledUiSyncLocalCanvasAssignment(display.localCanvasAssignments.single().slot,
                "forged.canvas")))
        val deletedContent = copyWithLocalCanvas(display, emptyList())
        listOf(forgedContent, deletedContent).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) {
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(changed))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(
                copyWithLocalCanvas(display, display.localCanvasAssignments,
                    intent = CompiledUiSyncThemeIntent("main-a", emptyMap(), "forged-palette"))))
        }
        val forgedVariant = CompiledUiSyncThemeVariant(display.additionalVariants.single().intent,
            display.additionalVariants.single().programSelection, emptyList(), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(
                copyWithLocalCanvas(display, display.localCanvasAssignments,
                    listOf(forgedVariant))))
        }
    }

    @Test fun v2RejectsWrongMissingDuplicateAndAmbiguousLocalCanvasRows() {
        val base = localCanvasSpec()
        val wrongSpecs = listOf(
            base.replaceFirst("\"slotIndex\":0", "\"slotIndex\":1"),
            base.replaceFirst("\"zoneIndex\":0", "\"zoneIndex\":1"),
            base.replaceFirst("\"layoutIndex\":0", "\"layoutIndex\":7"),
            base.replaceFirst("\"layoutID\":\"main-a\"",
                "\"layoutID\":\"wrong-layout\""),
            base.replaceFirst("\"contentId\":\"canvas.main.b\"",
                "\"contentId\":\"canvas.main.a\""),
            base.replaceFirst("\"localCanvasSlots\":[",
                "\"localCanvasSlots\":[],\"extra\":["),
            base.replaceFirst("\"localCanvasSlots\":[", "\"notLocalCanvasSlots\":["),
            base.replaceFirst("\"format\":\"xcertplay-debug-uisync-v2\"",
                "\"format\":\"xcertplay-debug-uisync-v1\""),
        )
        wrongSpecs.forEach { spec ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(localCanvasPackage(), spec,
                    BenchUiSyncProfileCompiler.SPEC_PATH_V2),
                    listOf(MAIN_HOST), emptyList())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(localCanvasPackage(), base,
                BenchUiSyncProfileCompiler.SPEC_PATH_V2,
                BenchUiSyncProfileCompiler.SPEC_PATH to twoDisplaySpec()),
                listOf(MAIN_HOST), emptyList())
        }
        val v1WithLocalRows = twoDisplaySpec().replaceFirst("\"slotIngresses\":[]",
            "\"slotIngresses\":[],\"localCanvasSlots\":[]")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(twoDisplayPackage(), v1WithLocalRows),
                listOf(MAIN_HOST, CLUSTER_HOST), emptyList())
        }
    }

    @Test fun v2RejectsCrossComponentLocalCanvasOverlapWithUnprovedPaintOrder() {
        val secondZone = EMPTY_ZONE.replace("canvas-zone", "second-zone")
            .replace("\"positionId\":\"canvas\"", "\"positionId\":\"second-canvas\"")
        val packageJson = localCanvasPackage("$EMPTY_ZONE,$secondZone")
        val spec = localCanvasSpec()
            .replaceFirst("\"activePrimarySelections\":[{\"zoneIndex\":0,\"componentIndex\":0}]",
                "\"activePrimarySelections\":[{\"zoneIndex\":0,\"componentIndex\":0}," +
                    "{\"zoneIndex\":1,\"componentIndex\":0}]")
            .replaceFirst("\"slotIndex\":0,\"contentId\":\"canvas.main.a\"}",
                "\"slotIndex\":0,\"contentId\":\"canvas.main.a\"}," +
                    "{\"zoneIndex\":1,\"componentIndex\":0,\"slotIndex\":0," +
                    "\"contentId\":\"canvas.main.c\"}")
        val failure = assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(packageJson, spec,
                BenchUiSyncProfileCompiler.SPEC_PATH_V2), listOf(MAIN_HOST), emptyList())
        }
        assertTrue(failure.message.orEmpty(),
            failure.message?.contains("crosses an active drawable") == true)
    }

    @Test fun v2IssuerRevalidationRejectsForgedRouteAndIngressSelector() {
        val profile = profile(localVideoPackage(), localVideoSpec(),
            BenchUiSyncProfileCompiler.SPEC_PATH_V2)
        val exactIngress = UiSyncRendererIngressSpec(UltraIngressId("ingress.main"),
            UltraAirPlayStreamIdentity(110), setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID)
        val assignment = compiler.compile(profile, listOf(MAIN_HOST), listOf(exactIngress))
            .assignments.single()
        assertEquals(1, assignment.localCanvasAssignments.size)
        assertEquals(1, assignment.slotIngressAssignments.size)
        val forgedRoutes = listOf(
            copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
                destination = DisplayDestination(assignment.destination.role,
                    DisplayDestinationId("forged-instance"))),
            copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
                topologyDisplayId = UltraTopologyDisplayId(UUID(0L, 9L))),
            copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
                outputId = UltraDisplayOutputId.Custom("host.main")),
            copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
                uiSyncDisplayId = UltraUiSyncDisplayId("forged.ui")),
        )
        forgedRoutes.forEach { changed ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(changed))
            }
            assertTrue(failure.message.orEmpty(),
                failure.message?.contains("route, ingress") == true)
        }
        val roleFailure = assertThrows(IllegalArgumentException::class.java) {
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(
                copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
                    destination = DisplayDestination.CLUSTER)))
        }
        assertTrue(roleFailure.message.orEmpty(),
            roleFailure.message?.contains("Profile display type") == true)
        val originalSlot = assignment.slotIngressAssignments.single()
        val forgedSlot = CompiledUiSyncSlotIngressAssignment(originalSlot.slot,
            originalSlot.streamIdentifier, UiSyncRendererIngressSpec(
                UltraIngressId("forged.ingress"), UltraAirPlayStreamIdentity(110),
                setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID))
        val forgedIngress = copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
            slotIngressAssignments = listOf(forgedSlot))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(forgedIngress))
        }
        assertTrue(failure.message.orEmpty(),
            failure.message?.contains("route, ingress") == true)

        val foreignSelector = copyWithLocalCanvas(assignment, assignment.localCanvasAssignments,
            slotIngressAssignments = listOf(CompiledUiSyncSlotIngressAssignment(
                originalSlot.slot, originalSlot.streamIdentifier,
                UiSyncRendererIngressSpec(originalSlot.ingressSpec.id,
                    UltraAirPlayStreamIdentity(111),
                    setupDisplayUuid = AirPlayInfoPlist.ALT_UUID))))
        val selectorFailure = assertThrows(IllegalArgumentException::class.java) {
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
                listOf(foreignSelector))
        }
        assertTrue(selectorFailure.message.orEmpty(),
            selectorFailure.message?.contains("fixed Host ScreenID") == true)
    }

    private fun copyWithLocalCanvas(
        source: CompiledUiSyncDisplayAssignment,
        local: List<CompiledUiSyncLocalCanvasAssignment>,
        variants: List<CompiledUiSyncThemeVariant> = source.additionalVariants,
        intent: CompiledUiSyncThemeIntent? = source.primaryIntent,
        destination: DisplayDestination = source.destination,
        topologyDisplayId: UltraTopologyDisplayId = source.topologyDisplayId,
        outputId: UltraDisplayOutputId = source.outputId,
        uiSyncDisplayId: UltraUiSyncDisplayId = source.uiSyncDisplayId,
        slotIngressAssignments: List<CompiledUiSyncSlotIngressAssignment> =
            source.slotIngressAssignments,
    ) = CompiledUiSyncDisplayAssignment(
        phonePackageDigest = source.phonePackageDigest,
        profileDisplayIndex = source.profileDisplayIndex,
        profileDisplayId = source.profileDisplayId,
        profileDisplayType = source.profileDisplayType,
        destination = destination,
        topologyDisplayId = topologyDisplayId,
        outputId = outputId,
        uiSyncDisplayId = uiSyncDisplayId,
        programSelection = source.programSelection,
        slotIngressAssignments = slotIngressAssignments,
        primaryIntent = intent,
        additionalVariants = variants,
        localCanvasAssignments = local,
    )

    private fun profile(
        packageJson: String,
        spec: String,
        specPath: String = BenchUiSyncProfileCompiler.SPEC_PATH,
        extraSpec: Pair<String, String>? = null,
        cafSpec: String? = null,
        themeJson: String = "{}",
    ): VerifiedUltraProfile {
        val files = listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageJson.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to themeJson.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "DEBUG.BENCH")),
            specPath to spec.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(1),
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(2),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(3),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(4),
            UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to "1")),
        ) + listOfNotNull(
            extraSpec?.let { it.first to it.second.encodeToByteArray() },
            cafSpec?.let { BenchCafProfileCompiler.SPEC_PATH to it.encodeToByteArray() },
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
        return verifiedProfile(archive)
    }

    private fun verifiedProfile(archive: ByteArray): VerifiedUltraProfile {
        val inspector = UltraProfilePackageInspector()
        val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
            UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding(
            "debug-bench", "debug-bench-mfi", "debug-bench-assets", "1", "1",
            candidate.contentDigest,
        )
        return (inspector.verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(
                binding, byteArrayOf(1, 2, 3),
            ))
        } as UltraProfileVerificationResult.Verified).profile
    }

    private fun <T : Any> fingerprint(type: Class<T>, seed: Int): T =
        type.getDeclaredConstructor(ByteArray::class.java).run {
            isAccessible = true
            newInstance(ByteArray(32) { seed.toByte() })
        }

    private fun twoDisplayPackage(): String = """{
      "identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug",
      "partnerID":"debug-bench","driveModeLinkedByDefault":false,"displays":[
        {"id":"main","type":"centerConsole","size":[800,480],"density":1,
         "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,"layouts":[
          ${layout("main-a", "#111111")},${layout("main-b", "#222222")}]},
        {"id":"cluster","type":"cluster","size":[800,480],"density":1,
         "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,"layouts":[
          ${layout("cluster-a", "#333333")},${layout("cluster-b", "#444444")}]}
      ]
    }""".trimIndent()

    private fun videoPackage(): String = """{
      "identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug",
      "partnerID":"debug-bench","driveModeLinkedByDefault":false,"displays":[
        {"id":"main","type":"centerConsole","size":[800,480],"density":1,
         "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,"layouts":[
          ${layout("main-a", "#111111", VIDEO_ZONE)}]}
      ]
    }""".trimIndent()

    private fun localCanvasPackage(zones: String = EMPTY_ZONE): String = """{
      "identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug",
      "partnerID":"debug-bench","driveModeLinkedByDefault":false,"displays":[
        {"id":"main","type":"centerConsole","size":[800,480],"density":1,
         "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,"layouts":[
          ${layout("main-a", "#111111", zones)},
          ${layout("main-b", "#222222", zones)}]}
      ]
    }""".trimIndent()

    private fun localVideoPackage(): String = """{
      "identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug",
      "partnerID":"debug-bench","driveModeLinkedByDefault":false,"displays":[
        {"id":"main","type":"centerConsole","size":[800,480],"density":1,
         "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,"layouts":[
          ${layout("main-a", "#111111", LOCAL_VIDEO_ZONE)}]}
      ]
    }""".trimIndent()

    private fun localVideoSpec(): String = """{
      "format":"xcertplay-debug-uisync-v2","displays":[{
        "profileDisplayIndex":0,"profileDisplayId":"main",
        "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}","destinationRole":"MAIN",
        "destinationInstanceId":"default","topologyDisplayId":"$MAIN_TOPOLOGY",
        "outputId":{"kind":"android","value":"host.main"},"uiSyncDisplayId":"ui.main",
        "layouts":[{
          "layoutIndex":0,"layoutID":"main-a","paletteID":"palette-a",
          "activeComponents":{},
          "activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
          "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":1,
            "streamIdentifier":"video.main","ingressId":"ingress.main"}],
          "localCanvasSlots":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
            "contentId":"canvas.main.a"}]
        }]
      }]
    }""".trimIndent()

    private fun localCanvasSpec(): String = """{
      "format":"xcertplay-debug-uisync-v2","displays":[{
        "profileDisplayIndex":0,"profileDisplayId":"main",
        "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}","destinationRole":"MAIN",
        "destinationInstanceId":"default","topologyDisplayId":"$MAIN_TOPOLOGY",
        "outputId":{"kind":"android","value":"host.main"},"uiSyncDisplayId":"ui.main",
        "layouts":[${localCanvasLayoutSpec(0, "main-a", "canvas.main.a")},
          ${localCanvasLayoutSpec(1, "main-b", "canvas.main.b")}]
      }]
    }""".trimIndent()

    private fun localCanvasLayoutSpec(index: Int, id: String, contentId: String): String = """{
      "layoutIndex":$index,"layoutID":"$id","paletteID":"palette-a",
      "activeComponents":{},
      "activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
      "slotIngresses":[],
      "localCanvasSlots":[{"zoneIndex":0,"componentIndex":0,
        "slotIndex":0,"contentId":"$contentId"}]
    }""".trimIndent()

    private fun layout(id: String, color: String, zones: String = ""): String = """
      {"id":"$id","layoutType":"primary","zones":[$zones],
       "backgroundColor":"$color","safeAreaInsets":[0,0,0,0],
       "driveModeThemeConfigurations":{}}
    """.trimIndent()

    private fun twoDisplaySpec(): String = """{
      "format":"xcertplay-debug-uisync-v1","displays":[
        ${displaySpec(0, "main", AirPlayInfoPlist.MAIN_UUID, "MAIN", MAIN_TOPOLOGY,
            "host.main", "ui.main", "main-a", "main-b")},
        ${displaySpec(1, "cluster", AirPlayInfoPlist.ALT_UUID, "CLUSTER", CLUSTER_TOPOLOGY,
            "host.cluster", "ui.cluster", "cluster-a", "cluster-b")}
      ]
    }""".trimIndent()

    private fun videoSpec(): String = """{
      "format":"xcertplay-debug-uisync-v1","displays":[
        ${displaySpec(0, "main", AirPlayInfoPlist.MAIN_UUID, "MAIN", MAIN_TOPOLOGY,
            "host.main", "ui.main", "main-a", null,
            "[{\"zoneIndex\":0,\"componentIndex\":0}]",
            "[{\"zoneIndex\":0,\"componentIndex\":0,\"slotIndex\":0," +
                "\"streamIdentifier\":\"video.main\",\"ingressId\":\"ingress.main\"}]")}
      ]
    }""".trimIndent()

    private fun displaySpec(
        index: Int,
        id: String,
        screen: String,
        role: String,
        topology: String,
        output: String,
        uiSync: String,
        firstLayout: String,
        secondLayout: String?,
        selections: String = "[]",
        slots: String = "[]",
    ): String = """{
      "profileDisplayIndex":$index,"profileDisplayId":"$id",
      "advertisedScreenId":"$screen","destinationRole":"$role",
      "destinationInstanceId":"default","topologyDisplayId":"$topology",
      "outputId":{"kind":"android","value":"$output"},"uiSyncDisplayId":"$uiSync",
      "layouts":[${layoutSpec(0, firstLayout, selections, slots)}${
        secondLayout?.let { ",${layoutSpec(1, it)}" }.orEmpty()
      }]
    }""".trimIndent()

    private fun layoutSpec(index: Int, id: String, selections: String = "[]",
        slots: String = "[]"): String = """{
      "layoutIndex":$index,"layoutID":"$id","paletteID":"palette-a",
      "activeComponents":{},"activePrimarySelections":$selections,"slotIngresses":$slots
    }""".trimIndent()

    private companion object {
        const val MAIN_TOPOLOGY = AirPlayInfoPlist.MAIN_UUID
        const val CLUSTER_TOPOLOGY = AirPlayInfoPlist.ALT_UUID
        val MAIN_HOST = BenchUiSyncHostOutput(
            0, AirPlayInfoPlist.MAIN_UUID, DisplayDestination.MAIN,
            UltraDisplayGraphOutput(UltraTopologyDisplayId(UUID.fromString(MAIN_TOPOLOGY)),
                UltraDisplayOutputId.Android("host.main"), UltraUiSyncDisplayId("ui.main")),
        )
        val CLUSTER_HOST = BenchUiSyncHostOutput(
            1, AirPlayInfoPlist.ALT_UUID, DisplayDestination.CLUSTER,
            UltraDisplayGraphOutput(UltraTopologyDisplayId(UUID.fromString(CLUSTER_TOPOLOGY)),
                UltraDisplayOutputId.Android("host.cluster"), UltraUiSyncDisplayId("ui.cluster")),
        )
        const val VIDEO_ZONE = """{
          "id":"video-zone","frame":[[0,0],[800,480]],"type":{"single":{}},
          "components":[{"id":"primary","type":"primary","size":[800,480],
            "slots":[{"style":{"rectangular":{}},"frame":[[10,10],[100,60]],
              "zIndex":0,"positionId":"video","instrument":{
                "kind":{"inputStream":{"_0":"video.main"}},
                "backgroundColor":"#00000000","clipsToShape":false}}]}]
        }"""
        const val EMPTY_ZONE = """{
          "id":"canvas-zone","frame":[[0,0],[800,480]],"type":{"single":{}},
          "components":[{"id":"primary","type":"primary","size":[800,480],
            "slots":[{"style":{"rectangular":{}},"frame":[[10,10],[100,60]],
              "zIndex":0,"positionId":"canvas"}]}]
        }"""
        const val LOCAL_VIDEO_ZONE = """{
          "id":"canvas-zone","frame":[[0,0],[800,480]],"type":{"single":{}},
          "components":[{"id":"primary","type":"primary","size":[800,480],
            "slots":[{"style":{"rectangular":{}},"frame":[[10,10],[100,60]],
              "zIndex":0,"positionId":"canvas"},
              {"style":{"rectangular":{}},"frame":[[150,10],[100,60]],
              "zIndex":1,"positionId":"video","instrument":{
                "kind":{"inputStream":{"_0":"video.main"}},
                "backgroundColor":"#00000000","clipsToShape":false}}]}]
        }"""
    }
}
