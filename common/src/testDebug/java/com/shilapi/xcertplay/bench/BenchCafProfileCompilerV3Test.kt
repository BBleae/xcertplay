package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.TrustedUltraVehicleIdentityResult
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraProfileDeploymentResult
import com.shilapi.xcertplay.UltraProfileVerificationIdentity
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfileBridge
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfile
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafCommand
import com.shilapi.xcertplay.airplay.rcs.caf.CafFirmwareConfigTreeDecoder
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessageReader
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.airplay.rcs.caf.ProtocolCafConfigTreeDecoderFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot
import com.shilapi.xcertplay.vehicle.ultra.SpeedProvenance
import com.shilapi.xcertplay.vehicle.ultra.SpeedUnit
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyCafPollingPolicy
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchCafProfileCompilerV3Test {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val compiler = BenchCafProfileCompiler()

    @Test fun signedTwoAndThreeOutputSpeedProfilesKeepEveryTargetExplicit() {
        listOf(TWO_SPEED_OUTPUTS, THREE_SPEED_OUTPUTS).forEach { outputs ->
            val spec = speedSpec(outputs)
            val verified = profile(BenchCafProfileCompiler.SPEC_PATH_V3 to spec)
            assertArrayEquals(spec.encodeToByteArray(),
                verified.candidate.content(BenchCafProfileCompiler.SPEC_PATH_V3))
            val compiled = compiler.compile(verified)
            assertEquals(BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR, compiled.source)
            assertTrue(compiled.requiresCarSpeedPermission)
            assertTrue(compiled.syntheticValues.isEmpty())
            assertEquals(null, compiled.syntheticMutationProfile)
            val deployed = compiled.profile
            assertEquals(7L, deployed.pluginId)
            assertEquals("1.0", deployed.protocolVersion)
            assertEquals(outputs.map(SpeedOutput::iid).toSet(),
                deployed.configTree.characteristicsByIid.keys)
            assertEquals(outputs.map(SpeedOutput::iid).toSet(),
                deployed.bindings.map { it.iid }.toSet())
            assertEquals(setOf(0L, 1L),
                deployed.configTree.characteristicsByIid.values.map { it.priority }.toSet())
            assertTrue(deployed.configTree.controlsByIid.isEmpty())
            deployed.bindings.forEach { binding ->
                val output = outputs.single { it.iid == binding.iid }
                assertEquals(VehicleTelemetrySnapshot.STANDARD_SPEED, binding.propertyId)
                assertEquals(0, binding.areaId)
                val projection = requireNotNull(binding.speedProjection)
                assertEquals(SpeedProvenance.STANDARD_ANDROID_VHAL_FLOAT, projection.provenance)
                assertEquals(SpeedUnit.METERS_PER_SECOND, projection.sourceUnit)
                assertEquals(output.unit, projection.targetUnit)
                val characteristic = deployed.configTree.characteristicsByIid.getValue(binding.iid)
                assertEquals(output.type, characteristic.type.value)
                assertEquals(CafCharacteristicFormat.FLOAT, characteristic.format)
                assertEquals(output.unit.cafUnits, characteristic.units)
                assertFalse(characteristic.writable == true || characteristic.oemWritable == true)
                assertEquals(null, characteristic.initialValue)
            }
        }
    }

    @Test fun unsignedOrAmbiguousSpecSelectionCannotCompile() {
        val v3 = BenchCafProfileCompiler.SPEC_PATH_V3 to speedSpec(TWO_SPEED_OUTPUTS)
        val v1 = BenchCafProfileCompiler.SPEC_PATH to LEGACY_V1_SPEC
        val v2 = BenchCafProfileCompiler.SPEC_PATH_V2 to LEGACY_V2_SPEC
        listOf(
            emptyList(), listOf(v1, v3), listOf(v2, v3), listOf(v1, v2, v3),
        ).forEach { specs ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(*specs.toTypedArray()))
            }
        }
    }

    @Test fun legacyV1AndV2SignedBytesAndCompilerPathsRemainUnchanged() {
        val v1 = profile(BenchCafProfileCompiler.SPEC_PATH to LEGACY_V1_SPEC)
        assertArrayEquals(LEGACY_V1_SPEC.encodeToByteArray(),
            v1.candidate.content(BenchCafProfileCompiler.SPEC_PATH))
        val v1Compiled = compiler.compile(v1)
        assertEquals(BenchCafProfileCompiler.Compiled.Source.SYNTHETIC, v1Compiled.source)
        assertFalse(v1Compiled.requiresCarSpeedPermission)
        assertEquals(mapOf(100 to 0 to 21.5, 101 to 0 to 1L), v1Compiled.syntheticValues)
        assertEquals(null, v1Compiled.syntheticMutationProfile)
        assertEquals(2, v1Compiled.profile.bindings.size)
        assertTrue(v1Compiled.profile.bindings.all { it.speedProjection == null })

        val v2 = profile(BenchCafProfileCompiler.SPEC_PATH_V2 to LEGACY_V2_SPEC)
        assertArrayEquals(LEGACY_V2_SPEC.encodeToByteArray(),
            v2.candidate.content(BenchCafProfileCompiler.SPEC_PATH_V2))
        val v2Compiled = compiler.compile(v2)
        assertEquals(BenchCafProfileCompiler.Compiled.Source.SYNTHETIC, v2Compiled.source)
        assertFalse(v2Compiled.requiresCarSpeedPermission)
        assertTrue(v2Compiled.syntheticValues.isEmpty())
        assertNotNull(v2Compiled.syntheticMutationProfile)
        assertEquals(setOf(3L, 4L), v2Compiled.syntheticMutationProfile!!.initialValues.keys)
    }

    @Test fun legacyAndroidCarCannotRouteRealOrAdvertisedSpeedButSyntheticFixturesRemainFake() {
        val base = LEGACY_V1_SPEC
            .replace("\"source\":\"synthetic\"", "\"source\":\"androidCar\"")
            .replace(Regex(",\\s*\"syntheticValues\"\\s*:\\s*\\[[^]]*]"), "")
        assertFalse(base.contains("syntheticValues"))
        assertEquals(BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR,
            compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH to base)).source)

        listOf(VehicleTelemetrySnapshot.STANDARD_SPEED, 0x11600208).forEach { speedId ->
            val realSpeed = base.replace("\"propertyId\":100", "\"propertyId\":$speedId")
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH to realSpeed))
            }
        }
        val advertisedSpeed = base.replace(
            "\"type\":3,\"iid\":3,\"format\":9",
            "\"type\":805306438,\"iid\":3,\"format\":9,\"units\":1032",
        )
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH to advertisedSpeed))
        }

        // A synthetic fixture may reuse that numeric property ID, but cannot read Android Car.
        val fakeSpeed = LEGACY_V1_SPEC.replace("\"propertyId\":100",
            "\"propertyId\":${VehicleTelemetrySnapshot.STANDARD_SPEED}")
        val compiledFake = compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH to fakeSpeed))
        assertEquals(BenchCafProfileCompiler.Compiled.Source.SYNTHETIC, compiledFake.source)
        assertFalse(compiledFake.requiresCarSpeedPermission)
        assertEquals(21.5, compiledFake.syntheticBackend().run {
            try {
                assertTrue(connect())
                read(VehicleTelemetrySnapshot.STANDARD_SPEED)?.value
            } finally {
                close()
            }
        })
        val fakeAdvertisedSpeed = LEGACY_V1_SPEC.replace(
            "\"type\":3,\"iid\":3,\"format\":9",
            "\"type\":805306438,\"iid\":3,\"format\":9,\"units\":1032",
        )
        assertEquals(BenchCafProfileCompiler.Compiled.Source.SYNTHETIC,
            compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH to fakeAdvertisedSpeed)).source)
    }

    @Test fun sourceUnitTargetMetadataAndSignedBindingMutationsFailClosed() {
        val good = speedSpec(TWO_SPEED_OUTPUTS)
        val changes = listOf(
            "\"format\":\"xcertplay-debug-caf-v3\"" to "\"format\":\"other\"",
            "\"source\":\"androidCar\"" to "\"source\":\"synthetic\"",
            "\"protocolVersion\":\"1.0\"" to "\"protocolVersion\":\"2.0\"",
            "\"bench\":true" to "\"bench\":false",
            "\"type\":150994945" to "\"type\":150994946", // VehicleMotion
            "\"type\":436207625" to "\"type\":436207626", // DisplayedSpeed
            "\"type\":805306438" to "\"type\":805306439", // SpeedKMH
            "\"format\":9" to "\"format\":8",
            "\"units\":1032" to "\"units\":1033",
            "\"propertyId\":${VehicleTelemetrySnapshot.STANDARD_SPEED}" to "\"propertyId\":123",
            "\"areaId\":0" to "\"areaId\":1",
            "\"sourceUnit\":1031" to "\"sourceUnit\":1032",
            "\"targetUnit\":1032" to "\"targetUnit\":1033",
            "\"priority\":1" to "\"priority\":0",
            "\"unknownIdentifier\":-101" to "\"unknownIdentifier\":-103",
            "\"operationFailed\":-103" to "\"operationFailed\":\"-103\"",
            "\"controls\":[]" to "\"controls\":[{\"type\":1,\"iid\":20}]",
            "\"units\":1032" to "\"units\":\"km/h\"",
            "\"targetUnit\":1032" to "\"targetUnit\":\"km/h\"",
        )
        changes.forEach { (from, to) ->
            assertTrue("Test mutation target was absent: $from", from in good)
            assertThrows("Should reject $to", IllegalArgumentException::class.java) {
                compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH_V3 to
                    good.replaceFirst(from, to)))
            }
        }
    }

    @Test fun unknownFieldsSyntheticValuesAndDuplicateOutputsFailClosed() {
        val good = speedSpec(TWO_SPEED_OUTPUTS)
        val changes = listOf(
            "\"format\":" to "\"unknown\":true,\"format\":",
            "\"pluginID\":7,\"bench\":true" to
                "\"pluginID\":7,\"bench\":true,\"unknown\":1",
            "\"accessories\":[" to "\"unknown\":true,\"accessories\":[",
            "\"version\":\"bench-v3\"" to
                "\"version\":\"bench-v3\",\"unknown\":1",
            "\"controls\":[]" to "\"controls\":[],\"unknown\":1",
            "\"format\":9,\"priority\":0" to
                "\"format\":9,\"writable\":true,\"priority\":0",
            "\"format\":9,\"priority\":0" to
                "\"format\":9,\"initialValue\":0.0,\"priority\":0",
            "\"sourceUnit\":1031" to "\"syntheticValue\":0,\"sourceUnit\":1031",
            "\"bindings\":[" to "\"syntheticValues\":[],\"bindings\":[",
            "\"iid\":4,\"propertyId\"" to "\"iid\":3,\"propertyId\"",
            "\"type\":805306439,\"iid\":4" to
                "\"type\":805306438,\"iid\":4",
        )
        changes.forEach { (from, to) ->
            assertTrue("Test mutation target was absent: $from", from in good)
            assertThrows("Should reject $to", IllegalArgumentException::class.java) {
                compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH_V3 to
                    good.replaceFirst(from, to)))
            }
        }
        val missingBinding = good.replaceFirst(
            ",${binding(TWO_SPEED_OUTPUTS[1])}", "")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH_V3 to missingBinding))
        }
    }

    @Test fun changingSignedSpeedBytesCannotReuseTheOriginalVerifiedIdentity() {
        val inspector = UltraProfilePackageInspector()
        val original = candidate(BenchCafProfileCompiler.SPEC_PATH_V3 to speedSpec(TWO_SPEED_OUTPUTS))
        val changed = candidate(BenchCafProfileCompiler.SPEC_PATH_V3 to speedSpec(TWO_SPEED_OUTPUTS)
            .replaceFirst("\"targetUnit\":1032", "\"targetUnit\":1033"))
        assertFalse(original.contentDigest == changed.contentDigest)
        val originalBinding = binding(original)
        val result = inspector.verify(changed, originalBinding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(
                originalBinding, byteArrayOf(1, 2, 3),
            ))
        }
        assertTrue(result is UltraProfileVerificationResult.Quarantined)
    }

    @Test fun detachedP256SignatureAndExactMfiIdentityGateRealV3Deployment() {
        val root = temporaryFolder.newFolder("signed-speed-v3").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(
            root.resolve(BenchUltraProfileImportStore.DIRECTORY),
        )
        val original = speedSpec(TWO_SPEED_OUTPUTS)
        imports.stageBase(archive(BenchCafProfileCompiler.SPEC_PATH_V3 to original).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(2, 4, 6))
        val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
            MessageDigest.getInstance("SHA-256").digest("debug-v3-policy".toByteArray()),
        )
        val speedPermissionGranted = AtomicBoolean(false)
        val bridgeAllocations = AtomicInteger()
        val provider = BenchUltraProfileDeploymentProvider(
            root.resolve(BenchUltraProfileImportStore.DIRECTORY),
            root.resolve("ultra-profile-v1"),
            issuer,
            carSpeedPermissionGate = BenchCarSpeedPermissionGate {
                speedPermissionGranted.get()
            },
        ) { compiled ->
            bridgeAllocations.incrementAndGet()
            assertEquals(BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR, compiled.source)
            AndroidCarPropertyCafProfileBridge.fromBackend(compiled.profile,
                object : ReadOnlyCarPropertyBackend {
                    private var open = false
                    override val isConnected: Boolean get() = open
                    override fun connect(): Boolean { open = true; return true }
                    override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, 0)
                    override fun read(propertyId: Int, areaId: Int): CarPropertyValue? =
                        if (open && propertyId == VehicleTelemetrySnapshot.STANDARD_SPEED &&
                            areaId == 0) CarPropertyValue(10.0f, 1L) else null
                    override fun close() { open = false }
                },
            )
        }
        try {
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
            val requestBytes = requireNotNull(imports.signingRequest())
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(requestBytes)
                sign()
            }
            imports.stageDetachedSignature(signature.inputStream())
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
            assertEquals(0, bridgeAllocations.get())
            speedPermissionGranted.set(true)
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
            val request = UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
            )
            speedPermissionGranted.set(false)
            assertTrue(provider.acquire(request) is UltraProfileDeploymentResult.Unavailable)
            assertEquals(0, bridgeAllocations.get())
            speedPermissionGranted.set(true)
            val prepared = provider.acquire(request) as UltraProfileDeploymentResult.Prepared
            try {
                val compiled = compiler.compile(prepared.preparation.verifiedProfile)
                assertEquals(TWO_SPEED_OUTPUTS.map(SpeedOutput::iid).toSet(),
                    compiled.profile.bindings.map { it.iid }.toSet())
                assertEquals(prepared.preparation.verifiedProfile.binding.contentDigest,
                    prepared.preparation.verifiedProfile.candidate.contentDigest)
            } finally {
                prepared.preparation.close()
            }

            // The changed CAF tree is still well formed, but the same signature cannot authorize
            // a different signed generation. The Store publishes no unsigned speed deployment.
            val changed = original.replaceFirst("\"version\":\"bench-v3\"",
                "\"version\":\"bench-v3-revision\"")
            assertEquals(2, compiler.compile(profile(BenchCafProfileCompiler.SPEC_PATH_V3 to
                changed)).profile.bindings.size)
            imports.stageBase(archive(BenchCafProfileCompiler.SPEC_PATH_V3 to changed)
                .inputStream(), key.public.encoded.inputStream(), VEHICLE_ID)
            imports.stageDetachedSignature(signature.inputStream())
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
        } finally {
            provider.close()
        }
    }

    @Test fun signedV3SpeedProfileCommitsAndAnswersBothAuthenticatedCafChannels() {
        val root = temporaryFolder.newFolder("signed-v3-protocol").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(
            root.resolve(BenchUltraProfileImportStore.DIRECTORY),
        )
        imports.stageBase(archive(BenchCafProfileCompiler.SPEC_PATH_V3 to
            speedSpec(THREE_SPEED_OUTPUTS)).inputStream(),
            key.public.encoded.inputStream(), VEHICLE_ID)
        val identity = identity(byteArrayOf(7, 8, 9))
        val backend = object : ReadOnlyCarPropertyBackend {
            var connected = false
            private val managerGeneration = Any()
            val reads = AtomicInteger()
            val closes = AtomicInteger()
            override val isConnected: Boolean get() = connected
            override val sourceIdentity: Any? get() = managerGeneration.takeIf { connected }
            override fun connect(): Boolean { connected = true; return true }
            override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, 0)
            override fun read(propertyId: Int, areaId: Int): CarPropertyValue? {
                reads.incrementAndGet()
                return if (connected && propertyId == VehicleTelemetrySnapshot.STANDARD_SPEED &&
                    areaId == 0) CarPropertyValue(10.0f, 41_900L) else null
            }
            override fun close() { connected = false; closes.incrementAndGet() }
        }
        var bridge: AndroidCarPropertyCafProfileBridge? = null
        val speedPermissionGranted = AtomicBoolean(true)
        val provider = BenchUltraProfileDeploymentProvider(
            root.resolve(BenchUltraProfileImportStore.DIRECTORY),
            root.resolve("ultra-profile-v1"),
            AuthorizedUltraCompiledRuntimeManifestIssuer(
                MessageDigest.getInstance("SHA-256").digest("v3-protocol-policy".toByteArray()),
            ),
            carSpeedPermissionGate = BenchCarSpeedPermissionGate {
                speedPermissionGranted.get()
            },
        ) { compiled ->
            assertEquals(BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR, compiled.source)
            bridgeWithTestClock(compiled.profile, backend, 42_000L).also { bridge = it }
        }
        try {
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(requireNotNull(imports.signingRequest()))
                sign()
            }
            imports.stageDetachedSignature(signature.inputStream())
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Resolved)
            val request = UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(VEHICLE_ID, identity), identity,
            )
            val prepared = provider.acquire(request) as UltraProfileDeploymentResult.Prepared
            val preparation = prepared.preparation
            try {
                assertNotNull(preparation.compiledManifest.caf)
                assertEquals(null, preparation.compiledManifest.uiSync)
                val verification = UltraProfileVerificationIdentity.from(
                    request.runtimeIdentity, identity, preparation.verifiedProfile,
                )
                val lease = preparation.commit(verification)
                try {
                    val provisioning = lease.openProvisioning()
                    try {
                        val caf = requireNotNull(provisioning.cafProvisioning)
                        val data = openEncrypted(caf,
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, 0x31)
                        val data2 = openEncrypted(caf,
                            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, 0x61)
                        try {
                            val expectedTree = compiler.compile(preparation.verifiedProfile)
                                .profile.configTree.root
                            listOf(data, data2).forEachIndexed { index, channel ->
                                val config = channel.exchange(CarAccessoryMessages.configRequest(
                                    7L, index.toLong() + 1L,
                                ))
                                assertEquals(CafCommand.CONFIG_RESPONSE, config.command)
                                assertEquals(expectedTree,
                                    config.configTree(CafFirmwareConfigTreeDecoder).root)
                            }

                            // One signed VHAL speed acquisition produces all three known iOS
                            // measurement types, with one shared source time and no unit guessing.
                            val before = backend.reads.get()
                            val source = requireNotNull(bridge).readOnlyInstrumentSource
                                .read(setOf(3L, 4L, 5L))
                            assertEquals(before + 1, backend.reads.get())
                            assertEquals(mapOf(3L to 100L, 4L to 100L, 5L to 100L),
                                source.sourceAgeMillisByIid)
                            assertEquals(10.0, source.values.getValue(3L) as Double, 0.000001)
                            assertEquals(36.0, source.values.getValue(4L) as Double, 0.000001)
                            assertEquals(10.0 * 3.6 / 1.609344,
                                source.values.getValue(5L) as Double, 0.000001)

                            assertEquals(10.0, data.exchange(CarAccessoryMessages.readRequest(
                                7L, 10L, listOf(3L),
                            )).valuesMap().getValue(3L) as Double, 0.000001)
                            val converted = data2.exchange(CarAccessoryMessages.readRequest(
                                7L, 11L, listOf(4L, 5L),
                            )).valuesMap()
                            assertEquals(36.0, converted.getValue(4L) as Double, 0.000001)
                            assertEquals(10.0 * 3.6 / 1.609344,
                                converted.getValue(5L) as Double, 0.000001)
                            assertEquals(setOf(3L), data.exchange(
                                CarAccessoryMessages.registerRequest(7L, 12L),
                            ).valuesMap().keys)
                            assertEquals(setOf(4L, 5L), data2.exchange(
                                CarAccessoryMessages.registerRequest(7L, 13L),
                            ).valuesMap().keys)
                            assertEquals(-101L, data.exchange(CarAccessoryMessages.readRequest(
                                7L, 14L, listOf(4L),
                            )).errors()[4L])
                            assertEquals(-101L, data2.exchange(CarAccessoryMessages.readRequest(
                                7L, 15L, listOf(3L),
                            )).errors()[3L])

                            // Leave both registered channels idle: only the Debug monitor may
                            // observe this OS-grant analogue and retire their transports.
                            speedPermissionGranted.set(false)
                            assertTrue(data.awaitEof())
                            assertTrue(data2.awaitEof())
                            assertFalse(requireNotNull(bridge).readOnlyInstrumentSource.isCurrent)
                        } finally {
                            data.close()
                            data2.close()
                        }
                    } finally {
                        closeAfterChannelRetirement(provisioning)
                    }
                } finally {
                    lease.close()
                }
            } finally {
                preparation.close()
            }
            assertEquals(1, backend.closes.get())
        } finally {
            provider.close()
        }
    }

    private fun bridgeWithTestClock(
        profile: AndroidCarPropertyCafProfile,
        backend: ReadOnlyCarPropertyBackend,
        elapsedRealtimeMillis: Long,
    ): AndroidCarPropertyCafProfileBridge {
        // The internal clock seam belongs to shared. This cross-module Debug test reaches it
        // reflectively; the signed Profile and production runtime cannot select a clock.
        val method = AndroidCarPropertyCafProfileBridge.Companion::class.java.declaredMethods
            .single { it.name.startsWith("fromBackendWithClock") && it.parameterCount == 5 }
            .apply { isAccessible = true }
        return method.invoke(AndroidCarPropertyCafProfileBridge.Companion,
            profile, backend, ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1,
            AndroidCarPropertyCafPollingPolicy(), { elapsedRealtimeMillis },
        ) as AndroidCarPropertyCafProfileBridge
    }

    private fun openEncrypted(
        provisioning: CafRuntimeProvisioning,
        type: RcsClientType,
        keySeed: Int,
    ): EncryptedBenchChannel {
        val getter = provisioning.javaClass.declaredMethods.single {
            it.name.startsWith("getHandlerFactory") && it.parameterCount == 0
        }.apply { isAccessible = true }
        val factory = getter.invoke(provisioning) as RcsDataStreamHandlerFactory
        val readKey = ByteArray(32) { (keySeed + it).toByte() }
        val writeKey = ByteArray(32) { (keySeed + it + 64).toByte() }
        val address = InetAddress.getLoopbackAddress()
        val stream = RcsDataStream.open(type, readKey, writeKey, address, address)
        try {
            val descriptor = requireNotNull(factory.descriptor(type))
            assertTrue(descriptor.probe().isReady)
            val create = descriptor.javaClass.declaredMethods.single {
                it.name.startsWith("create") && it.parameterCount == 1
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
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try {
                provisioning.close()
                return
            } catch (busy: IllegalStateException) {
                if (busy.message != "CAF channel close is already in flight" ||
                    System.nanoTime() >= deadline) throw busy
                Thread.sleep(10)
            }
        }
    }

    private class EncryptedBenchChannel(
        private val stream: RcsDataStream,
        private val peer: RcsChannel,
    ) : Closeable {
        fun exchange(frame: CafRcsFrame): CafMessageReader {
            peer.send(frame.asRcsMessage())
            repeat(8) {
                val response = CarAccessoryMessages.reader(requireNotNull(peer.receiveNext(2_000)) {
                    "Timed out waiting for CAF response"
                }.body)
                if (response.command != CafCommand.UPDATE_NOTIFY &&
                    response.transactionId == frame.envelope.message.transactionId) {
                    return response
                }
            }
            error("CAF response did not arrive")
        }

        fun awaitEof(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
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

    private fun speedSpec(outputs: List<SpeedOutput>): String {
        val characteristics = outputs.joinToString(",") { output ->
            """{"type":${output.type},"iid":${output.iid},"format":9,"priority":${output.priority},"units":${output.unit.cafUnits}}"""
        }
        val bindings = outputs.joinToString(",", transform = ::binding)
        return """{"format":"xcertplay-debug-caf-v3","source":"androidCar","pluginID":7,"protocolVersion":"1.0","pluginConfig":{"pluginID":7,"bench":true},"configTree":{"accessories":[{"type":150994945,"iid":1,"version":"bench-v3","services":[{"type":436207625,"iid":2,"characteristics":[$characteristics],"controls":[]}]}]},"bindings":[$bindings],"errors":{"unknownIdentifier":-101,"invalidValue":-102,"operationFailed":-103,"generalFailure":-104}}"""
    }

    private fun binding(output: SpeedOutput): String =
        """{"iid":${output.iid},"propertyId":${VehicleTelemetrySnapshot.STANDARD_SPEED},"areaId":0,"sourceUnit":1031,"targetUnit":${output.unit.cafUnits}}"""

    private fun profile(vararg specs: Pair<String, String>): VerifiedUltraProfile {
        val candidate = candidate(*specs)
        val binding = binding(candidate)
        val result = UltraProfilePackageInspector().verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(
                binding, byteArrayOf(1, 2, 3),
            ))
        }
        return (result as UltraProfileVerificationResult.Verified).profile
    }

    private fun binding(candidate: UltraProfileCandidate) = UltraProfileTrustBinding(
        "debug-bench", "debug-bench-mfi", "debug-bench-assets", "1", "1",
        candidate.contentDigest,
    )

    private fun candidate(vararg specs: Pair<String, String>): UltraProfileCandidate {
        val inspected = UltraProfilePackageInspector().inspect(ByteArrayInputStream(archive(*specs)))
        return (inspected as UltraProfileInspectionResult.Candidate).profile
    }

    private fun archive(vararg specs: Pair<String, String>): ByteArray {
        val accessoryAssets = byteArrayOf(1)
        val accessorySha = MessageDigest.getInstance("SHA-256").digest(accessoryAssets)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val files = listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to byteArrayOf(),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to
                """{"identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug","partnerID":"user-bench","displays":[]}""".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "DEBUG.BENCH")),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to accessoryAssets,
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(2),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to accessorySha.encodeToByteArray(),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(4),
            UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf(
                "AssetIdentifier" to "bench.speed.v3",
                "iOSContentVersion" to "debug-ios-1",
                "AccessoryContentVersion" to "debug-accessory-1",
            )),
        ) + specs.map { it.first to it.second.encodeToByteArray() }
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun identity(certificate: ByteArray): MfiAuthenticationIdentity =
        MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray = certificate
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        })

    private data class SpeedOutput(
        val iid: Long,
        val type: Long,
        val unit: SpeedUnit,
        val priority: Int,
    )

    private companion object {
        const val VEHICLE_ID = "user-bench-vehicle"
        val TWO_SPEED_OUTPUTS = listOf(
            SpeedOutput(3, 0x30000046L, SpeedUnit.KILOMETERS_PER_HOUR, 0),
            SpeedOutput(4, 0x30000047L, SpeedUnit.MILES_PER_HOUR, 1),
        )
        val THREE_SPEED_OUTPUTS = listOf(
            SpeedOutput(3, 0x30000021L, SpeedUnit.METERS_PER_SECOND, 0),
            SpeedOutput(4, 0x30000046L, SpeedUnit.KILOMETERS_PER_HOUR, 1),
            SpeedOutput(5, 0x30000047L, SpeedUnit.MILES_PER_HOUR, 1),
        )
        const val LEGACY_V1_SPEC = """
            {"format":"xcertplay-debug-caf-v1","source":"synthetic","pluginID":7,
            "protocolVersion":"1.0","pluginConfig":{"pluginID":7,"bench":true},
            "configTree":{"accessories":[{"type":1,"iid":1,"version":"bench",
            "services":[{"type":2,"iid":2,"characteristics":[
            {"type":3,"iid":3,"format":9,"priority":0,"initialValue":21.5},
            {"type":4,"iid":4,"format":8,"priority":1,"initialValue":1}],"controls":[]}]}]},
            "bindings":[{"iid":3,"propertyId":100,"areaId":0},
            {"iid":4,"propertyId":101,"areaId":0}],
            "errors":{"unknownIdentifier":-101,"invalidValue":-102,
            "operationFailed":-103,"generalFailure":-104},
            "syntheticValues":[{"iid":3,"value":21.5},{"iid":4,"value":1}]}
        """
        const val LEGACY_V2_SPEC = """
            {"format":"xcertplay-debug-caf-v2","source":"synthetic","pluginID":7,
            "protocolVersion":"1.0","pluginConfig":{"pluginID":7,"bench":true},
            "configTree":{"accessories":[{"type":1,"iid":1,"version":"bench-v2",
            "services":[{"type":2,"iid":2,"characteristics":[
            {"type":3,"iid":3,"format":9,"priority":0,"writable":true,"initialValue":21.5},
            {"type":4,"iid":4,"format":8,"priority":1,"initialValue":1}],
            "controls":[{"type":5,"iid":5,"sender":"device","hasResponse":true,
            "priority":0,"requestParameters":[{"name":"value","format":9}],
            "responseParameters":[{"name":"value","format":9}]}]}]}]},
            "errors":{"unknownIdentifier":-101,"invalidValue":-102,
            "operationFailed":-103,"generalFailure":-104},
            "syntheticValues":[{"iid":3,"value":21.5},{"iid":4,"value":1}],
            "mutationPolicy":{"enabled":true,"maxTransactionsPerChannel":32},
            "writeBindings":[{"iid":3,"allowedValues":[21.5]}],
            "controlBindings":[{"iid":5,"targetIid":3,"requestField":"value",
            "allowedValues":[21.5]}]}
        """
    }
}
