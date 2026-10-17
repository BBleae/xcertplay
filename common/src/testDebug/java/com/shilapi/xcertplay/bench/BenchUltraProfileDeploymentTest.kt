package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedInstrumentSignalSession
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalRuntimeContract
import com.shilapi.xcertplay.UltraCompiledRuntimeManifestDigest
import com.shilapi.xcertplay.UltraProfileActivationBackend
import com.shilapi.xcertplay.UltraProfileActivationCoordinator
import com.shilapi.xcertplay.UltraProfileBackendPrepareResult
import com.shilapi.xcertplay.UltraProfileCommitResult
import com.shilapi.xcertplay.UltraProfilePrepareResult
import com.shilapi.xcertplay.UltraProfileProvisioningOpenResult
import com.shilapi.xcertplay.TrustedUltraVehicleIdentityResult
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraProfileDeploymentResult
import com.shilapi.xcertplay.UltraProfileSessionStartResult
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationStatus
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfileBridge
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfile
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafCommand
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessageReader
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleState
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalUnavailableReason
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchUltraProfileDeploymentTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun physicalProofBindingIsExactSingleOwnerAndRetiresWithProvider() {
        val provider = provider(temporaryFolder.newFolder("proof-owner").toPath(), issuer())
        val first = provider.bindPhysicalGenerationProof { null }
        assertTrue(runCatching { provider.bindPhysicalGenerationProof { null } }.isFailure)
        assertTrue(runCatching {
            provider.bindPhysicalGenerationStatus { UltraProfilePhysicalGenerationStatus.Busy }
        }.isFailure)
        first.close()
        val successor = provider.bindPhysicalGenerationStatus {
            UltraProfilePhysicalGenerationStatus.Busy
        }
        first.close() // A stale lease cannot clear its successor.
        assertTrue(runCatching { provider.bindPhysicalGenerationProof { null } }.isFailure)
        assertTrue(runCatching {
            provider.bindPhysicalGenerationStatus { UltraProfilePhysicalGenerationStatus.Revoked }
        }.isFailure)
        successor.close()
        provider.close()
        assertTrue(runCatching { provider.bindPhysicalGenerationProof { null } }.isFailure)
    }

    @Test fun firstRealIdentityPreflightRequestsExternalSignatureThenCafOnlyAcquires() {
        val root = temporaryFolder.newFolder("no-backup").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val archive = archive(CAF_SPEC)
        imports.stageBase(archive.inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(1, 2, 3))
        val issuer = issuer()
        val provider = provider(root, issuer)

        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        val requestBytes = requireNotNull(imports.signingRequest())
        assertTrue(requestBytes.size > 32)
        imports.stageDetachedSignature(sign(requestBytes, key.private).inputStream())

        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
        val request = UltraProfileActivationRequest(UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity)
        val prepared = provider.acquire(request)
        assertTrue(prepared is UltraProfileDeploymentResult.Prepared)
        val exact = (prepared as UltraProfileDeploymentResult.Prepared).preparation
        assertEquals(null, exact.compiledManifest.uiSync)
        assertEquals(null, exact.compiledManifest.instrumentCrosswalk)
        assertTrue(exact.compiledManifest.caf != null)
        val verification = com.shilapi.xcertplay.UltraProfileVerificationIdentity.from(
            request.runtimeIdentity, identity, exact.verifiedProfile,
        )
        val lease = exact.commit(verification)
        val provisioning = lease.openProvisioning()
        assertTrue(provisioning.cafProvisioning != null)
        assertEquals(null, provisioning.uiSyncProvisioning)
        assertEquals(null, provisioning.instrumentReadSource)
        provisioning.close()
        lease.close()
        exact.close()

        val differentIdentity = identity(byteArrayOf(9, 8, 7))
        assertTrue(provider.resolve(differentIdentity) is TrustedUltraVehicleIdentityResult.Unavailable)
        assertTrue(provider.acquire(UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(VEHICLE_ID, differentIdentity), differentIdentity,
        )) is UltraProfileDeploymentResult.Unavailable)
    }

    @Test fun signedDebugPhoneMetadataProbeAcceptsIntegralAccessoryVersion() {
        val root = temporaryFolder.newFolder("numeric-accessory-version").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(CAF_SPEC, accessoryContentVersion = 1).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(6, 7, 8))
        val provider = provider(root, issuer())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        imports.stageDetachedSignature(
            sign(requireNotNull(imports.signingRequest()), key.private).inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
        )
        val prepared = provider.acquire(request) as UltraProfileDeploymentResult.Prepared
        assertEquals(1L, prepared.preparation.verifiedProfile.candidate.manifest.values[
            "AccessoryContentVersion"])
        prepared.preparation.close()
        provider.close()
    }

    @Test fun signedOptionalInstrumentCrosswalkIsManifestBoundButDoesNotEnableGauge() {
        val root = temporaryFolder.newFolder("instrument-manifest").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val fixture = BenchInstrumentSignalProfileCompilerTest
        imports.stageBase(archive(
            fixture.CAF_SPEC,
            packageJson = fixture.THEME_PACKAGE,
            instrumentCrosswalk = fixture.SPEED_SPEC,
        ).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(4, 5, 6))
        val issuer = issuer()
        val provider = provider(root, issuer)
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        imports.stageDetachedSignature(
            sign(requireNotNull(imports.signingRequest()), key.private).inputStream(),
        )
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
        )
        val first = (provider.acquire(request) as UltraProfileDeploymentResult.Prepared).preparation
        try {
            val manifest = first.compiledManifest
            val contract = requireNotNull(manifest.instrumentCrosswalk)
            assertEquals(1, contract.assignments.size)
            assertEquals("cluster-display", contract.assignments.single().profileDisplayId)
            assertEquals("dial", contract.assignments.single().spec.address.slot.positionId)
            assertEquals(manifest.caf?.schemaFingerprint, contract.cafSchemaFingerprint)
            assertEquals(setOf(AirPlayFeature.VEHICLE_STATE_PROTOCOL),
                manifest.authorizedFeatures)
            assertEquals(null, manifest.uiSync)
            val verification = com.shilapi.xcertplay.UltraProfileVerificationIdentity.from(
                request.runtimeIdentity, identity, first.verifiedProfile,
            )
            assertTrue(manifest.matchesProfile(request, first.verifiedProfile, verification))
            assertFalse(manifest.issuedBy(issuer()))
            assertThrows(IllegalArgumentException::class.java) {
                issuer.issue(request, first.verifiedProfile, null, manifest.caf)
            }
            val signed = contract.assignments.single()
            assertTrue(manifest.instrumentAssignmentsFor(first.verifiedProfile).single() === signed)
            val display = CarPlayThemeSchema.parseArcPackageJson(requireNotNull(
                first.verifiedProfile.candidate.content(
                    UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                ),
            )).displays.single()
            var current = true
            var closedSessions = 0
            val source = object : VehicleStateReadSource {
                override val configTree = BenchCafProfileCompiler().compile(
                    first.verifiedProfile,
                ).profile.configTree
                override val isCurrent get() = current
                override fun read(iids: Set<Long>) = VehicleStateResult(
                    values = mapOf(100L to 1.0, 101L to 3.6, 102L to 2.24),
                    sourceAgeMillisByIid = iids.associateWith { 0L },
                )
                override fun subscribe(iids: Set<Long>, listener: VehicleStateListener) =
                    VehicleStateSubscription { }
            }
            val localSession = AuthorizedInstrumentSignalSession.open(
                signed.spec, display, source, { current }, { closedSessions++ },
            )
            try {
                assertTrue(localSession.isLive)
                // subscribe() queues its own asynchronous first read. Verify this synchronous
                // read before starting that worker, so a concurrent newer read cannot invalidate
                // its publication and briefly leave the snapshot NOT_STARTED.
                assertTrue(localSession.refreshNow() is InstrumentSignalBundleState.Available)
                localSession.start { }
                current = false
                assertEquals(InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE,
                    (localSession.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
            } finally {
                localSession.close()
                localSession.close()
            }
            assertEquals(1, closedSessions)
            assertEquals(InstrumentSignalUnavailableReason.CLOSED,
                (localSession.snapshot() as InstrumentSignalBundleState.Unavailable).reason)
            fun forged(spec: InstrumentSignalBundleSpec) =
                CompiledInstrumentSignalRuntimeContract(
                    first.verifiedProfile, contract.crosswalkDigest,
                    contract.phonePackageDigest, contract.cafSchemaFingerprint,
                    listOf(CompiledInstrumentSignalDisplayAssignment(
                        signed.profileDisplayIndex, signed.profileDisplayId, spec,
                    )),
                )
            val originalSpec = signed.spec
            assertThrows(IllegalArgumentException::class.java) {
                forged(InstrumentSignalBundleSpec(
                    originalSpec.address.copy(slot = originalSpec.address.slot.copy(
                        positionId = "other-slot",
                    )), originalSpec.shape, originalSpec.sources,
                    originalSpec.maximumReadAgeMillis,
                ))
            }
            assertThrows(IllegalArgumentException::class.java) {
                forged(InstrumentSignalBundleSpec(
                    originalSpec.address, originalSpec.shape,
                    listOf(originalSpec.sources[0].copy(
                        role = originalSpec.sources[1].role,
                    ), originalSpec.sources[1].copy(
                        role = originalSpec.sources[0].role,
                    )) + originalSpec.sources.drop(2),
                    originalSpec.maximumReadAgeMillis,
                ))
            }
            assertThrows(IllegalArgumentException::class.java) {
                forged(InstrumentSignalBundleSpec(
                    originalSpec.address, originalSpec.shape,
                    listOf(originalSpec.sources[0].copy(
                        pin = originalSpec.sources[0].pin.copy(characteristicIid = 105L),
                    )) + originalSpec.sources.drop(1),
                    originalSpec.maximumReadAgeMillis,
                ))
            }
            val second = (provider.acquire(request) as UltraProfileDeploymentResult.Prepared)
                .preparation
            try {
                assertEquals(manifest.manifestDigest, second.compiledManifest.manifestDigest)
                assertFalse(manifest.matchesProfile(request, second.verifiedProfile, verification))
                assertThrows(IllegalArgumentException::class.java) {
                    manifest.instrumentAssignmentsFor(second.verifiedProfile)
                }
            } finally {
                second.close()
            }
            val syntheticLease = first.commit(verification)
            try {
                val syntheticChild = syntheticLease.openProvisioning()
                try {
                    assertEquals(null, syntheticChild.instrumentReadSource)
                } finally {
                    syntheticChild.close()
                }
            } finally {
                syntheticLease.close()
            }
            val digestField = manifest.javaClass.getDeclaredField("manifestDigest").apply {
                isAccessible = true
            }
            digestField.set(manifest, UltraCompiledRuntimeManifestDigest(ByteArray(32)))
            assertFalse(manifest.matchesProfile(request, first.verifiedProfile, verification))
        } finally {
            first.close()
            provider.close()
        }
    }

    @Test fun foreignBridgeCafTreeIsRejectedAndClosedBeforeManifestIssuance() {
        val root = temporaryFolder.newFolder("foreign-source-tree").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(CAF_SPEC).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(7, 8, 9))
        val issuer = issuer()
        val opened = AtomicReference<AndroidCarPropertyCafProfileBridge?>()
        val provider = BenchUltraProfileDeploymentProvider(
            root.resolve(BenchUltraProfileImportStore.DIRECTORY),
            root.resolve("ultra-profile-v1"), issuer,
        ) { compiled ->
            val signed = compiled.profile
            val foreignTree = CafConfigTree.decode(mapOf(
                "accessories" to listOf(mapOf(
                    "type" to 1L, "iid" to 1L, "version" to "foreign",
                    "services" to listOf(mapOf(
                        "type" to 2L, "iid" to 2L,
                        "characteristics" to listOf(
                            mapOf("type" to 3L, "iid" to 3L, "format" to 9L,
                                "priority" to 0L, "initialValue" to 21.5),
                            mapOf("type" to 4L, "iid" to 4L, "format" to 8L,
                                "priority" to 1L, "initialValue" to 1L),
                        ),
                        "controls" to emptyList<Any>(),
                    )),
                )),
            ))
            val foreign = AndroidCarPropertyCafProfile(
                foreignTree, signed.pluginId, signed.protocolVersion,
                signed.wirePluginConfig, signed.bindings, signed.errorPolicy,
                signed.decoderImplementationId, signed.handlerImplementationId,
                signed.pluginName,
            )
            AndroidCarPropertyCafProfileBridge.fromBackend(
                foreign, compiled.syntheticBackend(),
            ).also(opened::set)
        }
        try {
            provider.resolve(identity)
            imports.stageDetachedSignature(sign(
                requireNotNull(imports.signingRequest()), key.private,
            ).inputStream())
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
            val request = UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
            )
            assertTrue(provider.acquire(request) is UltraProfileDeploymentResult.Unavailable)
            assertFalse(requireNotNull(opened.get()).readOnlyInstrumentSource.isCurrent)
        } finally {
            provider.close()
        }
    }

    @Test fun invalidSignedOptionalCrosswalkFailsPreflightInsteadOfBeingIgnored() {
        val root = temporaryFolder.newFolder("invalid-instrument-manifest").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val fixture = BenchInstrumentSignalProfileCompilerTest
        imports.stageBase(archive(
            fixture.CAF_SPEC,
            packageJson = fixture.THEME_PACKAGE,
            instrumentCrosswalk = fixture.SPEED_SPEC.replaceFirst(
                "\"positionId\":\"dial\"", "\"positionId\":\"not-this-slot\"",
            ),
        ).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(7, 8, 9))
        val provider = provider(root, issuer())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        imports.stageDetachedSignature(
            sign(requireNotNull(imports.signingRequest()), key.private).inputStream(),
        )
        try {
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
            assertTrue(provider.acquire(UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
            )) is UltraProfileDeploymentResult.Unavailable)
        } finally {
            provider.close()
        }
    }

    @Test fun signedSinglePluginBenchAcceptsIPhonePluginZero() {
        val root = temporaryFolder.newFolder("plugin-zero").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val zeroSpec = CAF_SPEC.replace("\"pluginID\":7", "\"pluginID\":0")
        imports.stageBase(archive(zeroSpec).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(2, 4, 6))
        val provider = provider(root, issuer())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        imports.stageDetachedSignature(
            sign(requireNotNull(imports.signingRequest()), key.private).inputStream(),
        )
        try {
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
            val request = UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
            )
            val prepared = provider.acquire(request) as UltraProfileDeploymentResult.Prepared
            assertEquals(0L, BenchCafProfileCompiler().compile(prepared.preparation.verifiedProfile)
                .profile.pluginId)
            prepared.preparation.close()
        } finally {
            provider.close()
        }
    }

    @Test fun signedV2SyntheticWritesAndControlsUseBothEncryptedPriorityChannels() {
        val root = temporaryFolder.newFolder("mutable-bench").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(V2_SPEC, BenchCafProfileCompiler.SPEC_PATH_V2).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(5, 6, 7))
        val issuer = issuer()
        val provider = provider(root, issuer)
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        imports.stageDetachedSignature(sign(requireNotNull(imports.signingRequest()), key.private).inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
        val request = UltraProfileActivationRequest(UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity)
        val prepared = provider.acquire(request) as UltraProfileDeploymentResult.Prepared
        val exact = prepared.preparation
        val compiled = BenchCafProfileCompiler().compile(exact.verifiedProfile)
        assertTrue(compiled.syntheticMutationProfile != null)
        val program = requireNotNull(compiled.syntheticMutationProfile)
        val oneSession = BenchSyntheticMutableVehicleStateRepository(program)
        assertEquals(setOf(3L), oneSession.write(mapOf(3L to 23.5)).succeededIids)
        oneSession.close()
        assertEquals(-103L, oneSession.write(mapOf(3L to 21.5)).errors[3L])
        assertEquals(-103L, oneSession.control(mapOf(5L to mapOf("value" to 21.5)))
            .errors[5L])
        assertEquals(1, oneSession.appliedMutationCount)
        BenchSyntheticMutableVehicleStateRepository(program).use { reconnect ->
            assertEquals(21.5, reconnect.read(setOf(3L)).values[3L])
        }
        val duplicateTargetProgram = program.copy(controls = program.controls + (
            6L to program.controls.getValue(6L).copy(
                targetIid = 3L,
                allowedValues = program.writes.getValue(3L).allowedValues,
            )
        ))
        BenchSyntheticMutableVehicleStateRepository(duplicateTargetProgram).use { repository ->
            val ambiguous = repository.control(mapOf(
                5L to mapOf("value" to 23.5),
                6L to mapOf("value" to 23.5),
            ))
            assertEquals(mapOf(5L to -102L, 6L to -102L), ambiguous.errors)
            assertEquals(21.5, repository.read(setOf(3L)).values[3L])
            assertEquals(0, repository.appliedMutationCount)
        }
        val concurrentRepository = BenchSyntheticMutableVehicleStateRepository(program)
        val updateEntered = CountDownLatch(1)
        val releaseUpdate = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        concurrentRepository.subscribe(setOf(3L),
            com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener {
                updateEntered.countDown()
                assertTrue(releaseUpdate.await(2, TimeUnit.SECONDS))
            })
        val writer = Thread { concurrentRepository.write(mapOf(3L to 23.5)) }
        val closer = Thread {
            closeStarted.countDown()
            concurrentRepository.close()
        }
        try {
            writer.start()
            assertTrue(updateEntered.await(2, TimeUnit.SECONDS))
            closer.start()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            releaseUpdate.countDown()
            writer.join(2_000)
            closer.join(2_000)
            assertFalse(writer.isAlive)
            assertFalse(closer.isAlive)
            assertEquals(1, concurrentRepository.appliedMutationCount)
            assertEquals(-103L, concurrentRepository.write(mapOf(3L to 21.5)).errors[3L])
        } finally {
            releaseUpdate.countDown()
            writer.join(2_000)
            closer.join(2_000)
            concurrentRepository.close()
        }
        assertEquals(null, exact.compiledManifest.uiSync)
        val verification = com.shilapi.xcertplay.UltraProfileVerificationIdentity.from(
            request.runtimeIdentity, identity, exact.verifiedProfile,
        )
        val lease = exact.commit(verification)
        val provisioning = lease.openProvisioning()
        val caf = requireNotNull(provisioning.cafProvisioning)
        val channel0 = openEncrypted(caf, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 0x31)
        val channel1 = openEncrypted(caf, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 0x61)
        try {
            assertEquals(21.5, channel0.exchange(CarAccessoryMessages.readRequest(
                7L, 1L, listOf(3L),
            )).valuesMap()[3L])
            assertEquals(1L, channel1.exchange(CarAccessoryMessages.readRequest(
                7L, 1L, listOf(4L),
            )).valuesMap()[4L])

            assertTrue(channel0.exchange(CarAccessoryMessages.registerRequest(
                7L, 2L,
            )).valuesMap().containsKey(3L))
            assertTrue(channel1.exchange(CarAccessoryMessages.registerRequest(
                7L, 2L,
            )).valuesMap().containsKey(4L))

            val write0 = CarAccessoryMessages.writeRequest(7L, 10L, mapOf(3L to 23.5))
            val write1 = CarAccessoryMessages.writeRequest(7L, 10L, mapOf(4L to 2L))
            assertTrue(channel0.exchange(write0).errors().isEmpty())
            assertTrue(channel1.exchange(write1).errors().isEmpty())
            assertEquals(23.5, channel0.awaitUpdate(3L))
            assertEquals(2L, channel1.awaitUpdate(4L))

            val control0 = CarAccessoryMessages.controlRequest(
                7L, 11L, mapOf(5L to mapOf("value" to 21.5)),
            )
            val control1 = CarAccessoryMessages.controlRequest(
                7L, 11L, mapOf(6L to mapOf("value" to 1L)),
            )
            assertEquals(mapOf("value" to 21.5), channel0.exchange(control0).valuesMap()[5L])
            assertEquals(mapOf("value" to 1L), channel1.exchange(control1).valuesMap()[6L])
            assertEquals(21.5, channel0.awaitUpdate(3L))
            assertEquals(1L, channel1.awaitUpdate(4L))

            // Replaying the old write must not undo the intervening control mutation.
            assertTrue(channel0.exchange(write0).errors().isEmpty())
            assertTrue(channel1.exchange(write1).errors().isEmpty())
            assertEquals(21.5, channel0.exchange(CarAccessoryMessages.readRequest(
                7L, 12L, listOf(3L),
            )).valuesMap()[3L])
            assertEquals(1L, channel1.exchange(CarAccessoryMessages.readRequest(
                7L, 12L, listOf(4L),
            )).valuesMap()[4L])

            val conflict = channel0.exchange(CarAccessoryMessages.writeRequest(
                7L, 10L, mapOf(3L to 21.5),
            ))
            assertEquals(CafCommand.GENERAL_ERROR, conflict.command)
            assertEquals(-102L, conflict.requireError())
            val denied = channel1.exchange(CarAccessoryMessages.writeRequest(
                7L, 13L, mapOf(4L to 3L),
            ))
            assertEquals(-102L, denied.errors()[4L])
            assertEquals(-102L, channel0.exchange(CarAccessoryMessages.controlRequest(
                7L, 14L, mapOf(5L to mapOf("value" to 22.5)),
            )).errors()[5L])
            assertEquals(-102L, channel1.exchange(CarAccessoryMessages.controlRequest(
                7L, 14L, mapOf(6L to mapOf("value" to 3L)),
            )).errors()[6L])
            assertEquals(-101L, channel0.exchange(CarAccessoryMessages.readRequest(
                7L, 15L, listOf(4L),
            )).errors()[4L])
            assertEquals(-101L, channel1.exchange(CarAccessoryMessages.readRequest(
                7L, 15L, listOf(3L),
            )).errors()[3L])

            provisioning.close()
            assertTrue(channel0.awaitEof())
            assertTrue(channel1.awaitEof())
            lease.close()
            val reopened = provider.acquire(request) as UltraProfileDeploymentResult.Prepared
            val nextPreparation = reopened.preparation
            val nextVerification = com.shilapi.xcertplay.UltraProfileVerificationIdentity.from(
                request.runtimeIdentity, identity, nextPreparation.verifiedProfile,
            )
            val nextLease = nextPreparation.commit(nextVerification)
            val nextProvisioning = nextLease.openProvisioning()
            val nextChannel = openEncrypted(
                requireNotNull(nextProvisioning.cafProvisioning),
                RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 0x51,
            )
            try {
                assertEquals(21.5, nextChannel.exchange(CarAccessoryMessages.readRequest(
                    7L, 1L, listOf(3L),
                )).valuesMap()[3L])
            } finally {
                nextChannel.close()
                closeAfterChannelRetirement(nextProvisioning)
                nextLease.close()
                nextPreparation.close()
            }
        } finally {
            channel0.close()
            channel1.close()
            provisioning.close()
            lease.close()
            exact.close()
            provider.close()
        }
    }

    @Test fun v2CannotReuseSignatureAfterChangingMutationPermission() {
        val root = temporaryFolder.newFolder("mutable-signature").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val identity = identity(byteArrayOf(1, 3, 5))
        val provider = provider(root, issuer())
        imports.stageBase(archive(V2_SPEC, BenchCafProfileCompiler.SPEC_PATH_V2).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        provider.resolve(identity)
        val signature = sign(requireNotNull(imports.signingRequest()), key.private)
        imports.stageDetachedSignature(signature.inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)

        imports.stageBase(archive(V2_SPEC.replace("23.5", "24.5"),
            BenchCafProfileCompiler.SPEC_PATH_V2).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        imports.stageDetachedSignature(signature.inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
    }

    @Test fun signedV2SyntheticRuntimeIsRevokedWithAa05AndReconnectOpensFreshGeneration() {
        val root = temporaryFolder.newFolder("mutable-lifecycle").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(V2_SPEC.replace("\"pluginID\":7", "\"pluginID\":0"),
            BenchCafProfileCompiler.SPEC_PATH_V2).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(7, 8, 9))
        val issuer = issuer()
        val provider = provider(root, issuer)
        provider.resolve(identity)
        imports.stageDetachedSignature(sign(requireNotNull(imports.signingRequest()), key.private).inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)

        val backend = UltraProfileActivationBackend { request ->
            when (val acquired = provider.acquire(request)) {
                is UltraProfileDeploymentResult.Prepared ->
                    UltraProfileBackendPrepareResult.Prepared(acquired.preparation)
                UltraProfileDeploymentResult.Unavailable -> UltraProfileBackendPrepareResult.Unavailable
            }
        }
        val coordinator = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
            UltraProfileActivationBackend::class.java,
            AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
        ).apply { isAccessible = true }.newInstance(backend, issuer)
        val firstReceipt = receipt(identity)
        val secondReceipt = receipt(identity)
        fun open(current: MfiAuthenticationReceipt): UltraProfileProvisioningOpenResult.Opened {
            val started = coordinator.beginSession(VEHICLE_ID, current)
                as UltraProfileSessionStartResult.Started
            val prepared = coordinator.prepare(started.token, current)
                as UltraProfilePrepareResult.Prepared
            val committed = coordinator.commit(prepared.activation, current)
                as UltraProfileCommitResult.Activated
            return committed.lease.openRuntime() as UltraProfileProvisioningOpenResult.Opened
        }
        try {
            val first = open(firstReceipt.receipt).runtimeLease
            assertTrue(first.isLive)
            assertTrue(first.configuration().configuredFeatures.any {
                it.wireName == "vehicleStateProtocol"
            })
            firstReceipt.revoke()
            assertFalse(first.isLive)
            val second = open(secondReceipt.receipt).runtimeLease
            assertTrue(second.isLive)
            assertFalse(first === second)
            secondReceipt.revoke()
            assertFalse(second.isLive)
        } finally {
            coordinator.close()
            firstReceipt.authority.close()
            secondReceipt.authority.close()
            provider.close()
        }
    }

    @Test fun signedV2RequiresSyntheticSourceAndExplicitEnabledMutationPolicy() {
        val root = temporaryFolder.newFolder("mutable-denied").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val identity = identity(byteArrayOf(6, 5, 4))
        val provider = provider(root, issuer())
        listOf(
            V2_SPEC.replace("\"enabled\":true", "\"enabled\":false"),
            V2_SPEC.replace("\"source\":\"synthetic\"", "\"source\":\"androidCar\""),
            V2_SPEC.replace("\"allowedValues\":[21.5,23.5]", "\"allowedValues\":[]"),
        ).forEach { invalid ->
            imports.stageBase(archive(invalid, BenchCafProfileCompiler.SPEC_PATH_V2).inputStream(),
                key.public.encoded.inputStream(), VEHICLE_ID)
            provider.resolve(identity)
            imports.stageDetachedSignature(sign(requireNotNull(imports.signingRequest()), key.private)
                .inputStream())
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        }
        provider.close()
    }

    @Test fun signedV2BooleanSyntheticValuesRemainValidNumericCafFormat() {
        val root = temporaryFolder.newFolder("mutable-boolean").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val booleanSpec = V2_SPEC
            .replace("\"format\":8", "\"format\":0")
            .replace("\"initialValue\":1}", "\"initialValue\":true}")
            .replace("\"iid\":4,\"value\":1", "\"iid\":4,\"value\":true")
            .replace("\"allowedValues\":[1,2]", "\"allowedValues\":[false,true]")
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(booleanSpec, BenchCafProfileCompiler.SPEC_PATH_V2).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(2, 4, 6))
        val provider = provider(root, issuer())
        try {
            provider.resolve(identity)
            imports.stageDetachedSignature(sign(requireNotNull(imports.signingRequest()), key.private)
                .inputStream())
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
            val prepared = provider.acquire(UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
            )) as UltraProfileDeploymentResult.Prepared
            val program = requireNotNull(BenchCafProfileCompiler().compile(
                prepared.preparation.verifiedProfile,
            ).syntheticMutationProfile)
            BenchSyntheticMutableVehicleStateRepository(program).use { repository ->
                assertEquals(true, repository.read(setOf(4L)).values[4L])
                assertEquals(setOf(4L), repository.write(mapOf(4L to false)).succeededIids)
                assertEquals(false, repository.read(setOf(4L)).values[4L])
            }
            prepared.preparation.close()
        } finally {
            provider.close()
        }
    }

    @Test fun signedCafSpecOrProfileByteChangeCannotReuseDetachedSignature() {
        val root = temporaryFolder.newFolder("no-backup").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(CAF_SPEC).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(1, 2, 3))
        val provider = provider(root, issuer())
        provider.resolve(identity)
        val signature = sign(requireNotNull(imports.signingRequest()), key.private)
        imports.stageDetachedSignature(signature.inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)

        val altered = CAF_SPEC.replace("21.5", "21.6")
        imports.stageBase(archive(altered).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        imports.stageDetachedSignature(signature.inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        assertTrue(provider.acquire(UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
        )) is UltraProfileDeploymentResult.Unavailable)
    }

    @Test fun malformedZipAndNonP256PinCannotBePublished() {
        val root = temporaryFolder.newFolder("no-backup").toPath()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        val p256 = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val p384 = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        assertTrue(runCatching {
            imports.stageBase(byteArrayOf(1, 2, 3).inputStream(), p256.public.encoded.inputStream(), VEHICLE_ID)
        }.isFailure)
        assertTrue(runCatching {
            imports.stageBase(archive(CAF_SPEC).inputStream(), p384.public.encoded.inputStream(), VEHICLE_ID)
        }.isFailure)
        assertEquals(null, imports.loadActive())
    }

    @Test fun currentReceiptCanOpenCafOnlyAndRevocationRetiresItsRuntime() {
        val root = temporaryFolder.newFolder("no-backup").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(CAF_SPEC.replace("\"pluginID\":7", "\"pluginID\":0"))
            .inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(1, 2, 3))
        val issuer = issuer()
        val provider = provider(root, issuer)
        provider.resolve(identity)
        imports.stageDetachedSignature(sign(requireNotNull(imports.signingRequest()), key.private).inputStream())
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)

        val receiptFixture = receipt(identity)
        val backend = UltraProfileActivationBackend { request ->
            when (val result = provider.acquire(request)) {
                is UltraProfileDeploymentResult.Prepared ->
                    UltraProfileBackendPrepareResult.Prepared(result.preparation)
                UltraProfileDeploymentResult.Unavailable -> UltraProfileBackendPrepareResult.Unavailable
            }
        }
        val constructor = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
            UltraProfileActivationBackend::class.java,
            AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
        ).apply { isAccessible = true }
        val coordinator = constructor.newInstance(backend, issuer)
        try {
            val started = coordinator.beginSession(VEHICLE_ID, receiptFixture.receipt)
            assertTrue(started is UltraProfileSessionStartResult.Started)
            val token = (started as UltraProfileSessionStartResult.Started).token
            val prepared = coordinator.prepare(token, receiptFixture.receipt)
            assertTrue(prepared is UltraProfilePrepareResult.Prepared)
            val activation = (prepared as UltraProfilePrepareResult.Prepared).activation
            val committed = coordinator.commit(activation, receiptFixture.receipt)
            assertTrue(committed is UltraProfileCommitResult.Activated)
            val lease = (committed as UltraProfileCommitResult.Activated).lease
            val opened = lease.openRuntime()
            assertTrue(opened is UltraProfileProvisioningOpenResult.Opened)
            val runtime = (opened as UltraProfileProvisioningOpenResult.Opened).runtimeLease
            assertTrue(runtime.isLive)
            assertTrue(runtime.configuration().configuredFeatures.any { it.wireName == "vehicleStateProtocol" })
            assertFalse(runtime.configuration().configuredFeatures.any { it.wireName == "uiSync" })
            receiptFixture.revoke()
            assertFalse(runtime.isLive)
            assertFalse(lease.isLive)
        } finally {
            coordinator.close()
            receiptFixture.authority.close()
        }
    }

    @Test fun receiptRevokedDuringCafAcquisitionCannotPublishLateRuntime() {
        val root = temporaryFolder.newFolder("no-backup").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(BenchUltraProfileImportStore.DIRECTORY))
        imports.stageBase(archive(CAF_SPEC).inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(1, 2, 3))
        val issuer = issuer()
        provider(root, issuer).resolve(identity)
        imports.stageDetachedSignature(sign(requireNotNull(imports.signingRequest()), key.private).inputStream())
        val receiptFixture = receipt(identity)
        val createdBridge = AtomicReference<AndroidCarPropertyCafProfileBridge?>()
        val provider = BenchUltraProfileDeploymentProvider(
            root.resolve(BenchUltraProfileImportStore.DIRECTORY),
            root.resolve("ultra-profile-v1"),
            issuer,
        ) { compiled ->
            AndroidCarPropertyCafProfileBridge.fromBackend(
                compiled.profile, compiled.syntheticBackend(),
            ).also {
                createdBridge.set(it)
                // The authority can report a deferred callback failure to its caller, but the
                // receipt must already be revoked. Return the owned bridge to the coordinator
                // so this test exercises its late-result retirement instead of leaking a fixture.
                runCatching { receiptFixture.revoke() }
                assertFalse(receiptFixture.receipt.isLiveForUltraActivation())
            }
        }
        assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
        val backend = UltraProfileActivationBackend { request ->
            when (val acquired = provider.acquire(request)) {
                is UltraProfileDeploymentResult.Prepared ->
                    UltraProfileBackendPrepareResult.Prepared(acquired.preparation)
                UltraProfileDeploymentResult.Unavailable -> UltraProfileBackendPrepareResult.Unavailable
            }
        }
        val coordinator = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
            UltraProfileActivationBackend::class.java,
            AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
        ).apply { isAccessible = true }.newInstance(backend, issuer)
        try {
            val started = coordinator.beginSession(VEHICLE_ID, receiptFixture.receipt)
            assertTrue(started is UltraProfileSessionStartResult.Started)
            val result = coordinator.prepare(
                (started as UltraProfileSessionStartResult.Started).token,
                receiptFixture.receipt,
            )
            assertTrue(result is UltraProfilePrepareResult.Denied)
            assertFalse(requireNotNull(createdBridge.get()).isAndroidCarServiceConnected)
        } finally {
            coordinator.close()
            receiptFixture.authority.close()
        }
    }

    private fun provider(
        root: java.nio.file.Path,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
    ) = BenchUltraProfileDeploymentProvider(
        root.resolve(BenchUltraProfileImportStore.DIRECTORY),
        root.resolve("ultra-profile-v1"),
        issuer,
    ) { compiled ->
        assertEquals(BenchCafProfileCompiler.Compiled.Source.SYNTHETIC, compiled.source)
        AndroidCarPropertyCafProfileBridge.fromBackend(compiled.profile, compiled.syntheticBackend())
    }

    private fun issuer() = AuthorizedUltraCompiledRuntimeManifestIssuer(
        MessageDigest.getInstance("SHA-256").digest("debug-test-policy".toByteArray()),
    )

    private fun identity(certificate: ByteArray): MfiAuthenticationIdentity =
        MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray = certificate
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        })

    private fun receipt(identity: MfiAuthenticationIdentity): ReceiptFixture {
        val authorityClass = Class.forName("com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority")
        val authority = authorityClass.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }.newInstance(identity)
        val begin = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("begin") && !method.name.startsWith("beginHandoff") &&
                method.parameterCount == 2
        }.apply { isAccessible = true }
        val channel = Any()
        val attempt = begin.invoke(authority, channel, 1L)
        val issue = attempt.javaClass.declaredMethods.single { method ->
            method.name.startsWith("issue") && method.parameterCount == 2
        }.apply { isAccessible = true }
        val receipt = issue.invoke(attempt, channel, identity) as MfiAuthenticationReceipt
        return ReceiptFixture(receipt, authority as Closeable, {
            begin.invoke(authority, Any(), 2L)
        })
    }

    private data class ReceiptFixture(
        val receipt: MfiAuthenticationReceipt,
        val authority: Closeable,
        val revoke: () -> Unit,
    )

    private fun sign(payload: ByteArray, key: java.security.PrivateKey): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(payload)
            sign()
        }

    private fun archive(
        spec: String,
        specPath: String = BenchCafProfileCompiler.SPEC_PATH,
        packageJson: String? = null,
        instrumentCrosswalk: String? = null,
        accessoryContentVersion: Any = "debug-accessory-1",
    ): ByteArray {
        val accessory = zip(listOf("BenchAccessory/theme.json" to "{}".toByteArray()))
        val accessorySha = MessageDigest.getInstance("SHA-256").digest(accessory)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return zip(listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to byteArrayOf(),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to
                (packageJson ?: """{"identifier":"bench","version":1,"minimumSDKVersion":"1",
                    "buildType":"debug","partnerID":"user-bench","driveModeLinkedByDefault":false,
                    "displays":[{"id":"bench-cluster","type":"cluster","size":[800,480],
                    "density":1,"allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
                    "layouts":[{"id":"bench-layout","layoutType":"primary","zones":[],
                    "backgroundColor":"#101820","safeAreaInsets":[0,0,0,0],
                    "driveModeThemeConfigurations":{}}]}]}""").toByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".toByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "DEBUG.BENCH", "Version" to "1")),
            specPath to spec.toByteArray(),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to accessory,
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to "DEBUG BENCH ONLY".toByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to accessorySha.toByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to BplistCodec.encode(mapOf("Bench" to true)),
            UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf(
                "AssetIdentifier" to "bench.asset",
                "iOSContentVersion" to "debug-ios-1",
                "AccessoryContentVersion" to accessoryContentVersion,
            )),
        ) + listOfNotNull(instrumentCrosswalk?.let {
            BenchInstrumentSignalProfileCompiler.SPEC_PATH to it.toByteArray()
        }))
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun openEncrypted(
        provisioning: CafRuntimeProvisioning,
        type: RcsClientType,
        keySeed: Int,
    ): EncryptedBenchChannel {
        // Diagnostic factory access is test-only. Production transfers the provisioning to an
        // AirPlay runtime, which opens a fresh child per authenticated control session.
        val factoryGetter = provisioning.javaClass.declaredMethods.single { method ->
            method.name.startsWith("getHandlerFactory") && method.parameterCount == 0
        }.apply { isAccessible = true }
        val factory = factoryGetter.invoke(provisioning) as RcsDataStreamHandlerFactory
        val readKey = ByteArray(32) { (keySeed + it).toByte() }
        val writeKey = ByteArray(32) { (keySeed + it + 64).toByte() }
        val address = InetAddress.getLoopbackAddress()
        val stream = RcsDataStream.open(type, readKey, writeKey, address, address)
        try {
            val descriptor = requireNotNull(factory.descriptor(type))
            assertTrue(descriptor.probe().isReady)
            val create = descriptor.javaClass.declaredMethods.single { method ->
                method.name.startsWith("create") && method.parameterCount == 1
            }.apply { isAccessible = true }
            val handler = create.invoke(descriptor, stream) as CafProtocolSession
            val port = stream.listen(handler)
            val peer = RcsChannel.connect(InetSocketAddress(address, port), writeKey, readKey)
            return EncryptedBenchChannel(stream, peer)
        } catch (failure: Throwable) {
            stream.close()
            throw failure
        }
    }

    private fun closeAfterChannelRetirement(provisioning: Closeable) {
        // Closing the test peer can start the server's stream-close callback on another thread.
        // Retry only that exact concurrent-retirement state; all other failures remain visible.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try {
                provisioning.close()
                return
            } catch (busy: IllegalStateException) {
                if (busy.message != "CAF channel close is already in flight" ||
                    System.nanoTime() >= deadline
                ) throw busy
                Thread.sleep(10)
            }
        }
    }

    private class EncryptedBenchChannel(
        private val stream: RcsDataStream,
        private val peer: RcsChannel,
    ) : Closeable {
        private val pendingUpdates = ArrayDeque<CafMessageReader>()

        fun exchange(frame: CafRcsFrame): CafMessageReader {
            peer.send(frame.asRcsMessage())
            repeat(8) {
                val reader = receive()
                if (reader.command == CafCommand.UPDATE_NOTIFY) {
                    pendingUpdates += reader
                } else if (reader.transactionId == frame.envelope.message.transactionId) {
                    return reader
                } else error("Unexpected CAF response ${reader.command}")
            }
            error("CAF response did not arrive")
        }

        fun awaitUpdate(iid: Long): Any? {
            repeat(8) {
                if (pendingUpdates.isNotEmpty()) {
                    val next = pendingUpdates.removeFirst()
                    if (iid in next.valuesMap()) return next.valuesMap()[iid]
                } else {
                    val next = receive()
                    if (next.command == CafCommand.UPDATE_NOTIFY && iid in next.valuesMap()) {
                        return next.valuesMap()[iid]
                    }
                }
            }
            error("CAF update for $iid did not arrive")
        }

        private fun receive(): CafMessageReader = CarAccessoryMessages.reader(
            requireNotNull(peer.receiveNext(2_000)) { "Timed out waiting for CAF frame" }.body,
        )

        fun awaitEof(): Boolean {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                peer.receiveNext(100)
                if (peer.isPeerEnded) return true
            }
            return peer.isPeerEnded
        }

        override fun close() {
            peer.close()
            stream.close()
        }
    }

    private companion object {
        const val VEHICLE_ID = "user-bench-vehicle"
        const val CAF_SPEC = """
            {"format":"xcertplay-debug-caf-v1","source":"synthetic","pluginID":7,
            "protocolVersion":"1.0",
            "pluginConfig":{"pluginID":7,"bench":true},
            "configTree":{"accessories":[{"type":1,"iid":1,"version":"bench",
                "services":[{"type":2,"iid":2,"characteristics":[
                    {"type":3,"iid":3,"format":9,"priority":0,"initialValue":21.5},
                    {"type":4,"iid":4,"format":8,"priority":1,"initialValue":1}],
                    "controls":[]}]}]},
            "bindings":[{"iid":3,"propertyId":100,"areaId":0},
                        {"iid":4,"propertyId":101,"areaId":0}],
            "errors":{"unknownIdentifier":-101,"invalidValue":-102,
                "operationFailed":-103,"generalFailure":-104},
            "syntheticValues":[{"iid":3,"value":21.5},{"iid":4,"value":1}]}
        """
        const val V2_SPEC = """
            {"format":"xcertplay-debug-caf-v2","source":"synthetic","pluginID":7,
            "protocolVersion":"1.0",
            "pluginConfig":{"pluginID":7,"bench":true},
            "configTree":{"accessories":[{"type":1,"iid":1,"version":"bench-v2",
                "services":[{"type":2,"iid":2,"characteristics":[
                    {"type":3,"iid":3,"format":9,"priority":0,"writable":true,"initialValue":21.5},
                    {"type":4,"iid":4,"format":8,"priority":1,"writable":true,"initialValue":1}],
                    "controls":[
                    {"type":5,"iid":5,"sender":"device","hasResponse":true,"priority":0,
                     "requestParameters":[{"name":"value","format":9,"supportsInvalid":false}],
                     "responseParameters":[{"name":"value","format":9,"supportsInvalid":false}]},
                    {"type":6,"iid":6,"sender":"device","hasResponse":true,"priority":1,
                     "requestParameters":[{"name":"value","format":8,"supportsInvalid":false}],
                     "responseParameters":[{"name":"value","format":8,"supportsInvalid":false}]}]}]}]},
            "errors":{"unknownIdentifier":-101,"invalidValue":-102,
                "operationFailed":-103,"generalFailure":-104},
            "syntheticValues":[{"iid":3,"value":21.5},{"iid":4,"value":1}],
            "mutationPolicy":{"enabled":true,"maxTransactionsPerChannel":32},
            "writeBindings":[{"iid":3,"allowedValues":[21.5,23.5]},
                             {"iid":4,"allowedValues":[1,2]}],
            "controlBindings":[{"iid":5,"targetIid":3,"requestField":"value",
                                "allowedValues":[21.5,23.5]},
                               {"iid":6,"targetIid":4,"requestField":"value",
                                "allowedValues":[1,2]}]}
        """
    }
}
