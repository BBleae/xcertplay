package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.caf.CafCommand
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafMessageReader
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaBoundPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.airplay.rcs.caf.ProtocolCafConfigTreeDecoderFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBoundedPresentationExecutor
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererCapability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererFactoryLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustVerifier
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs only from [SyntheticUltraProfileDebugActivity] in a debug variant. */
internal class SyntheticUltraProfileDeviceHarness(
    private val context: Context,
    private val report: (String) -> Unit,
) {
    fun run() {
        report("MODE  DEBUG SYNTHETIC — NOT OEM AUTHORIZED")
        report("GUARD production deploymentProvider remains Disabled")

        var authority: Closeable? = null
        var coordinator: UltraProfileActivationCoordinator? = null
        var callbackExecutor: SyntheticBoundedExecutor? = null
        var sessionRuntime: Closeable? = null
        var workspace: Path? = null
        try {
            val identity = syntheticMfiIdentity()
            val receiptFixture = syntheticReceipt(identity)
            authority = receiptFixture.authority
            pass("AA05", "synthetic exact receipt issued")

            workspace = Files.createTempDirectory(context.cacheDir.toPath(), "debug-ultra-")
            val profile = buildAndPersistSyntheticProfile(workspace, identity)
            pass("PROFILE", "generated, written, inspected, verified")

            val storeProfile = installAndReloadIfSupported(workspace, profile)

            callbackExecutor = SyntheticBoundedExecutor()
            val uiSyncCatalog = ProvisioningFixtures.syntheticUiSyncCatalog(storeProfile)
            val renderer = SyntheticRendererCapability(uiSyncCatalog.programFingerprint)
            val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
                MessageDigest.getInstance("SHA-256")
                    .digest("DEBUG-SYNTHETIC-TRUST-POLICY-v1".encodeToByteArray()),
            )
            val backend = SyntheticActivationBackend(
                profile = storeProfile,
                issuer = issuer,
                renderer = renderer,
                callbackExecutor = callbackExecutor,
                uiSyncCatalog = uiSyncCatalog,
            )
            coordinator = coordinator(backend, issuer)

            val token = when (val started = coordinator.beginSession(VEHICLE_ID, receiptFixture.receipt)) {
                is UltraProfileSessionStartResult.Started -> started.token
                is UltraProfileSessionStartResult.Denied -> error("begin denied: ${started.code}")
            }
            pass("BEGIN", "receipt consumed; generation admitted")

            val prepared = when (val result = coordinator.prepare(token, receiptFixture.receipt)) {
                is UltraProfilePrepareResult.Prepared -> result.activation
                is UltraProfilePrepareResult.Denied -> error("prepare denied: ${result.code}")
            }
            pass("PREPARE", "synthetic compiler manifest accepted")

            val lease = when (val result = coordinator.commit(prepared, receiptFixture.receipt)) {
                is UltraProfileCommitResult.Activated -> result.lease
                is UltraProfileCommitResult.Denied -> error("commit denied: ${result.code}")
            }
            pass("COMMIT", "exact manifest/backend lease published")

            val runtimeLease = when (val result = lease.openRuntime()) {
                is UltraProfileProvisioningOpenResult.Opened -> result.runtimeLease
                is UltraProfileProvisioningOpenResult.Denied -> error("openRuntime denied: ${result.code}")
            }
            val configuration = runtimeLease.configuration()
            check(configuration.isConfigured(AirPlayFeature.UI_SYNC))
            check(configuration.isConfigured(AirPlayFeature.VEHICLE_STATE_PROTOCOL))
            // This debug fixture has no Host physical display batch. Static Profile bytes and
            // renderer IDs cannot be promoted into an active UI Sync capability.
            check(configuration.readiness.uiSync?.isComplete != true)
            check(
                configuration.readiness.handler(
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                )?.isComplete == true,
            )
            check(
                configuration.readiness.handler(
                    RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
                )?.isComplete == true,
            )
            pass(
                "CONFIG",
                configuration.configuredFeatures.joinToString { it.wireName } +
                    "; UI Sync static-only",
            )

            val runtime = runtimeLease.runtimeForMediaEngine()
            sessionRuntime = openSession(runtime)
            pass("SESSION", "fresh per-control-session runtime opened")

            assertUiSyncUnavailable(sessionRuntime)
            pass("UI_SYNC_GUARD", "no physical output batch; ClusterControl withheld")

            exerciseCaf(sessionRuntime)
            pass("CAF", "encrypted RCS read response + update notify observed")

            receiptFixture.revoke()
            check(!runtimeLease.isLive)
            check(!runtime.isLive())
            check(renderer.liveSessionCount.get() == 0)
            pass("REVOCATION", "receipt revoke retired session/runtime/backend tree")

            coordinator.close()
            check(backend.leaseClosed.get() == 1)
            check(backend.provisioningClosed.get() == 1)
            pass("CLOSE", "retry-safe close retired provisioning + backend lease exactly once")
            report("\nRESULT PASS — DEBUG SYNTHETIC CAF/PROFILE; UI SYNC NOT READY")
        } catch (failure: Throwable) {
            val root = unwrap(failure)
            report("\nRESULT FAIL — ${root.javaClass.simpleName}: ${root.message.orEmpty()}")
            android.util.Log.e("SyntheticUltraProfile", "Synthetic harness failed", root)
        } finally {
            runCatching { sessionRuntime?.close() }
            runCatching { coordinator?.close() }
            runCatching { authority?.close() }
            callbackExecutor?.close()
            workspace?.let(::deleteTree)
        }
    }

    private fun buildAndPersistSyntheticProfile(
        workspace: Path,
        identity: MfiAuthenticationIdentity,
    ): VerifiedUltraProfile {
        val accessoryZip = zip(
            listOf(
                "SyntheticAccessory/" to ByteArray(0),
                "SyntheticAccessory/theme.json" to
                    "{\"debugSynthetic\":true}".encodeToByteArray(),
            ),
        )
        val accessoryDigest = sha256Hex(accessoryZip).encodeToByteArray()
        val archive = zip(
            listOf(
                UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to
                    """{"identifier":"xcertplay-debug-synthetic","version":1,
                        "minimumSDKVersion":"1","buildType":"debug","partnerID":"test",
                        "driveModeLinkedByDefault":false,"displays":[{"id":"synthetic-profile-cluster",
                        "type":"cluster","size":[800,480],"density":1,
                        "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
                        "layouts":[{"id":"synthetic-layout","layoutType":"primary",
                        "zones":[],"backgroundColor":"#101820",
                        "safeAreaInsets":[0,0,0,0],
                        "driveModeThemeConfigurations":{}}]}]}""".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to
                    "{\"name\":\"debug-synthetic\",\"background\":\"#101820\"}".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to BplistCodec.encode(
                    mapOf("Bundle" to "DEBUG.SYNTHETIC.CarPlayPhone", "Version" to "1"),
                ),
                "CarPlayPhone.assets/layout.bin" to
                    "DEBUG-SYNTHETIC-LAYOUT-v1".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to accessoryZip,
                UltraProfilePackageFiles.CERTIFICATE_CHAIN to
                    "DEBUG SYNTHETIC — NO CERTIFICATE CHAIN".encodeToByteArray(),
                UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to accessoryDigest,
                UltraProfilePackageFiles.ACCESSORY_ADDITIONS to BplistCodec.encode(
                    mapOf("DebugSynthetic" to true),
                ),
                UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(
                    mapOf(
                        "AssetIdentifier" to "debug.synthetic.asset",
                        "iOSContentVersion" to "debug-ios-1",
                        "AccessoryContentVersion" to "debug-accessory-1",
                    ),
                ),
            ),
        )
        val archivePath = workspace.resolve("synthetic-profile.zip")
        Files.write(archivePath, archive)
        val candidate = Files.newInputStream(archivePath).use { input ->
            when (val result = UltraProfilePackageInspector().inspect(input)) {
                is UltraProfileInspectionResult.Candidate -> result.profile
                is UltraProfileInspectionResult.Quarantined ->
                    error("profile quarantined: ${result.quarantine.code}")
            }
        }
        val binding = UltraProfileTrustBinding(
            vehicleId = VEHICLE_ID,
            mfiCertificateIdentity = canonicalMfiBinding(identity),
            assetIdentifier = "debug.synthetic.asset",
            iosContentVersion = "debug-ios-1",
            accessoryContentVersion = "debug-accessory-1",
            contentDigest = candidate.contentDigest,
        )
        return verify(candidate, binding)
    }

    private fun installAndReloadIfSupported(
        workspace: Path,
        profile: VerifiedUltraProfile,
    ): VerifiedUltraProfile {
        val store = UltraProfileStore(workspace.resolve("store"))
        return when (val install = store.install(profile)) {
            is UltraProfileInstallResult.Installed,
            is UltraProfileInstallResult.CommittedButDurabilityUncertain,
            -> {
                val loaded = store.loadActive(profile.binding, verifier(profile.binding))
                when (loaded) {
                    is UltraProfileLoadResult.Loaded -> {
                        pass("STORE", "canonical archive installed and reverified on device")
                        loaded.profile
                    }
                    else -> error("store reload failed: $loaded")
                }
            }
            is UltraProfileInstallResult.Failed -> {
                report("STORE SKIP — platform storage primitive unavailable: ${install.code}")
                profile
            }
        }
    }

    private fun verify(
        candidate: UltraProfileCandidate,
        binding: UltraProfileTrustBinding,
    ): VerifiedUltraProfile = when (
        val result = UltraProfilePackageInspector().verify(candidate, binding, verifier(binding))
    ) {
        is UltraProfileVerificationResult.Verified -> result.profile
        is UltraProfileVerificationResult.Quarantined ->
            error("synthetic verifier rejected: ${result.quarantine.code}")
    }

    private fun verifier(binding: UltraProfileTrustBinding) = UltraProfileTrustVerifier { request ->
        check(request.expectedBinding == binding)
        check(request.candidate.contentDigest == binding.contentDigest)
        UltraProfileTrustResult.Trusted(
            UltraProfileTrustAttestation(
                binding,
                "DEBUG-SYNTHETIC-LOCAL-ATTESTATION-v1".encodeToByteArray(),
            ),
        )
    }

    private fun assertUiSyncUnavailable(sessionRuntime: Closeable) {
        val factoryGetter = sessionRuntime.javaClass.declaredMethods.single { method ->
            method.name.startsWith("getHandlerFactory") && method.parameterCount == 0
        }.apply { isAccessible = true }
        val factory = factoryGetter.invoke(sessionRuntime) as RcsDataStreamHandlerFactory
        check(factory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)?.probe()?.isReady != true) {
            "synthetic static Profile offered ClusterControl without a physical output batch"
        }
    }

    private fun exerciseCaf(sessionRuntime: Closeable) {
        val channel = openOwnedChannel(
            sessionRuntime,
            RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
            keySeed = 0x41,
        )
        try {
            channel.client.send(
                CarAccessoryMessages.readRequest(
                    SYNTHETIC_PLUGIN_ID,
                    transactionId = 10L,
                    identifiers = listOf(TEMPERATURE_IID),
                ).asRcsMessage(),
            )
            val read = CarAccessoryMessages.reader(channel.client.receiveRequired().body)
            check(read.command == CafCommand.READ_RESPONSE)
            check(read.valuesMap()[TEMPERATURE_IID] == 21.5)

            channel.client.send(
                CarAccessoryMessages.registerRequest(
                    SYNTHETIC_PLUGIN_ID,
                    transactionId = 11L,
                ).asRcsMessage(),
            )
            val register = CarAccessoryMessages.reader(channel.client.receiveRequired().body)
            val update = CarAccessoryMessages.reader(channel.client.receiveRequired().body)
            check(register.command == CafCommand.REGISTER_RESPONSE)
            check(update.command == CafCommand.UPDATE_NOTIFY)
            check(update.valuesMap()[TEMPERATURE_IID] == 22.75)
        } finally {
            channel.close()
        }
    }

    private fun openOwnedChannel(
        sessionRuntime: Closeable,
        clientType: RcsClientType,
        keySeed: Int,
    ): OwnedChannel {
        val factoryGetter = sessionRuntime.javaClass.declaredMethods.single { method ->
            method.name.startsWith("getHandlerFactory") && method.parameterCount == 0
        }.apply { isAccessible = true }
        val factory = factoryGetter.invoke(sessionRuntime) as RcsDataStreamHandlerFactory
        val descriptor = requireNotNull(factory.descriptor(clientType)) {
            "per-session runtime does not support ${clientType.name}"
        }
        check(descriptor.probe().isReady)

        val serverRead = ByteArray(32) { (keySeed + it).toByte() }
        val serverWrite = ByteArray(32) { (keySeed + 0x40 + it).toByte() }
        val stream = RcsDataStream.open(
            clientType = clientType,
            readKey = serverRead,
            writeKey = serverWrite,
            bindAddress = InetAddress.getLoopbackAddress(),
        )
        val handler = createHandler(descriptor, stream)
        val port = stream.listen(handler)
        val client = RcsChannel.connect(
            remote = InetSocketAddress(InetAddress.getLoopbackAddress(), port),
            readKey = serverWrite,
            writeKey = serverRead,
        )
        return OwnedChannel(stream, client)
    }

    private fun createHandler(
        descriptor: RcsDataStreamHandlerDescriptor,
        stream: RcsDataStream,
    ): RcsDataStreamHandler {
        val create = descriptor.javaClass.declaredMethods.single { method ->
            method.name.startsWith("create") && method.parameterTypes.contentEquals(
                arrayOf(RcsDataStream::class.java),
            )
        }.apply { isAccessible = true }
        return create.invoke(descriptor, stream) as RcsDataStreamHandler
    }

    private fun openSession(runtime: AirPlayUltraRuntime): Closeable {
        val method = runtime.javaClass.declaredMethods.single { candidate ->
            candidate.name.startsWith("openSession") && candidate.parameterCount == 0
        }.apply { isAccessible = true }
        return method.invoke(runtime) as Closeable
    }

    private fun coordinator(
        backend: UltraProfileActivationBackend,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
    ): UltraProfileActivationCoordinator {
        val constructor = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
            UltraProfileActivationBackend::class.java,
            AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(backend, issuer)
    }

    private fun syntheticMfiIdentity(): MfiAuthenticationIdentity =
        MfiAuthenticationIdentity.capture(
            object : MfiAuthenticator {
                override val certificateType = MfiCertificateType.MFI
                override fun protocolMajor(): Int = 1
                override fun readCertificate(maximumOutputLength: Int): ByteArray =
                    "DEBUG-SYNTHETIC-MFI-CERTIFICATE-v1".encodeToByteArray()
                override fun signChallenge(challenge: ByteArray): ByteArray =
                    MessageDigest.getInstance("SHA-256").digest(challenge)
            },
        )

    private fun syntheticReceipt(identity: MfiAuthenticationIdentity): SyntheticReceiptFixture {
        val authorityClass = Class.forName(
            "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
        )
        val authority = authorityClass.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }
            .newInstance(identity)
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
        return SyntheticReceiptFixture(
            receipt = receipt,
            authority = authority as Closeable,
            revoke = { begin.invoke(authority, Any(), 2L) },
        )
    }

    private fun pass(stage: String, detail: String) = report("$stage PASS — $detail")

    private data class SyntheticReceiptFixture(
        val receipt: MfiAuthenticationReceipt,
        val authority: Closeable,
        val revoke: () -> Unit,
    )

    private data class OwnedChannel(
        val stream: RcsDataStream,
        val client: RcsChannel,
    ) : Closeable {
        override fun close() {
            runCatching { client.close() }
            stream.close()
        }
    }

    private fun RcsChannel.receiveRequired(): RcsMessage =
        requireNotNull(receiveNext(5_000)) { "timed out waiting for synthetic RCS response" }

    private fun canonicalMfiBinding(identity: MfiAuthenticationIdentity): String =
        "xcertplay-mfi-authentication-v1:${identity.certificateType.name}:${identity.sha256Hex()}"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
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

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while (current is java.lang.reflect.InvocationTargetException && current.cause != null) {
            current = current.cause!!
        }
        return current
    }

    private companion object {
        const val VEHICLE_ID = "debug-synthetic-vehicle"
        const val DISPLAY_ID = "debug.synthetic.cluster"
        const val SYNTHETIC_PLUGIN_ID = 7L
        const val TEMPERATURE_IID = 3L
        const val HIGH_PRIORITY_IID = 4L
        const val RENDERER_IMPLEMENTATION = "debug.synthetic.renderer.v1"
        const val CAF_HANDLER_IMPLEMENTATION = "debug.synthetic.caf.handler.v1"
    }

    private class SyntheticActivationBackend(
        private val profile: VerifiedUltraProfile,
        private val issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
        private val renderer: SyntheticRendererCapability,
        private val callbackExecutor: SyntheticBoundedExecutor,
        private val uiSyncCatalog: SyntheticUiSyncCatalog,
    ) : UltraProfileActivationBackend {
        val leaseClosed = AtomicInteger()
        val provisioningClosed = AtomicInteger()

        override fun prepare(request: UltraProfileActivationRequest): UltraProfileBackendPrepareResult {
            check(request.runtimeIdentity.vehicleId == VEHICLE_ID)
            check(profile.binding.mfiCertificateIdentity == request.profileMfiCertificateIdentity)

            val uiSync = ProvisioningFixtures.syntheticUiSyncProvisioning(
                renderer,
                callbackExecutor,
                uiSyncCatalog,
            )
            var caf: CafRuntimeProvisioning? = null
            try {
                val cafProvisioning = ProvisioningFixtures.syntheticCafProvisioning()
                caf = cafProvisioning
                val uiSnapshot = requireNotNull(uiSync.authorizationSnapshot())
                check(uiSnapshot.outputs.single() == uiSyncCatalog.output)
                val uiContract = CompiledUiSyncRuntimeContract.from(
                    uiSnapshot, listOf(uiSyncCatalog.assignment),
                )
                val cafContract = CompiledCafRuntimeContract.from(
                    requireNotNull(cafProvisioning.authorizationSnapshot()),
                )
                val manifest = issuer.issue(request, profile, uiContract, cafContract)
                return UltraProfileBackendPrepareResult.Prepared(
                    SyntheticPreparation(
                        request,
                        profile,
                        manifest,
                        uiSync,
                        cafProvisioning,
                        leaseClosed,
                        provisioningClosed,
                    ),
                )
            } catch (failure: Throwable) {
                runCatching { uiSync.close() }
                runCatching { caf?.close() }
                throw failure
            }
        }
    }

    private class SyntheticPreparation(
        request: UltraProfileActivationRequest,
        override val verifiedProfile: VerifiedUltraProfile,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val uiSync: UiSyncRuntimeProvisioning,
        private val caf: CafRuntimeProvisioning,
        private val leaseClosed: AtomicInteger,
        private val provisioningClosed: AtomicInteger,
    ) : AuthorizedUltraProfilePreparation {
        override val requestIdentity = request.runtimeIdentity
        override val authenticationIdentity = request.authenticationIdentity
        private var transferred = false
        private var closed = false

        override fun commit(
            verificationIdentity: UltraProfileVerificationIdentity,
        ): AuthorizedUltraProfileBackendLease {
            check(!closed && !transferred)
            check(verificationIdentity == compiledManifest.verificationIdentity)
            transferred = true
            return SyntheticLease(
                verificationIdentity,
                compiledManifest,
                uiSync,
                caf,
                leaseClosed,
                provisioningClosed,
            )
        }

        override fun close() {
            if (closed) return
            closed = true
            if (!transferred) {
                runCatching { uiSync.close() }
                caf.close()
            }
        }
    }

    private class SyntheticLease(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val uiSync: UiSyncRuntimeProvisioning,
        private val caf: CafRuntimeProvisioning,
        private val closeCount: AtomicInteger,
        private val provisioningCloseCount: AtomicInteger,
    ) : AuthorizedUltraProfileBackendLease {
        private var live = true
        private var provisioningOpened = false
        override val isLive: Boolean get() = live

        override fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning {
            check(live && !provisioningOpened)
            provisioningOpened = true
            return SyntheticProvisioning(
                verificationIdentity,
                compiledManifest,
                uiSync,
                caf,
                provisioningCloseCount,
            )
        }

        override fun close() {
            if (!live) return
            live = false
            runCatching { uiSync.close() }
            caf.close()
            closeCount.incrementAndGet()
        }
    }

    private class SyntheticProvisioning(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        override val uiSyncProvisioning: UiSyncRuntimeProvisioning,
        override val cafProvisioning: CafRuntimeProvisioning,
        private val closeCount: AtomicInteger,
    ) : AuthorizedUltraProfileBackendProvisioning {
        private var live = true
        override val isLive: Boolean get() = live

        override fun close() {
            if (!live) return
            live = false
            // Ownership was transferred into AirPlayUltraRuntime. These calls are intentional
            // no-ops after transfer and prove there is no second resource owner.
            uiSyncProvisioning.close()
            cafProvisioning.close()
            closeCount.incrementAndGet()
        }
    }

    private class SyntheticBoundedExecutor : UiSyncBoundedPresentationExecutor, Closeable {
        private val delegate = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(64),
            { runnable -> Thread(runnable, "debug-synthetic-ultra-renderer").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        override val maximumPendingCallbacks: Int = 64
        override val parallelism: Int = 1
        override val mayExecuteInline: Boolean = false
        override fun execute(command: Runnable) = delegate.execute(command)
        override fun close() {
            delegate.shutdown()
            delegate.awaitTermination(3, TimeUnit.SECONDS)
            if (!delegate.isTerminated) delegate.shutdownNow()
        }
    }

    private class SyntheticRendererCapability(
        val programFingerprint: UiSyncSemanticThemeProgramFingerprint,
    ) : UiSyncCompleteRendererCapability {
        val liveSessionCount = AtomicInteger()

        override fun acquire(
            context: UiSyncCompleteRendererBindingContext,
        ): UiSyncCompleteRendererFactoryLease = SyntheticRendererFactory(context, this)
    }

    private class SyntheticRendererFactory(
        private val context: UiSyncCompleteRendererBindingContext,
        private val owner: SyntheticRendererCapability,
    ) : UiSyncCompleteRendererFactoryLease {
        private var live = true
        private val receipt = context.issueReadinessReceipt(
            owner.programFingerprint,
            emptySet(),
            emptySet(),
            semanticCompilerActive = true,
            themeCompilerActive = true,
            backendLive = true,
        )
        override val isLive: Boolean get() = live
        override val schemaFingerprint = context.schemaFingerprint
        override val bindingFingerprint = context.bindingFingerprint
        override val displayIds = context.displayIds
        override val implementationId = RENDERER_IMPLEMENTATION
        override val readinessReceipt: RendererReadinessReceipt get() = receipt

        override fun openSession(
            context: UiSyncCompleteRendererSessionBindingContext,
        ): UiSyncCompleteRendererSessionLease {
            check(live)
            return SyntheticRendererSession(context, owner)
        }

        override fun close() {
            live = false
        }
    }

    private class SyntheticRendererSession(
        private val context: UiSyncCompleteRendererSessionBindingContext,
        private val owner: SyntheticRendererCapability,
    ) : UiSyncCompleteRendererSessionLease {
        private var live = true
        private val subscription = context.presentationSource.subscribeWithReplay(
            DISPLAY_ID,
            UltraPresentationObserver { },
        )
        private val receipt = context.issueReadinessReceipt(
            owner.programFingerprint,
            emptySet(),
            emptySet(),
            semanticCompilerActive = true,
            themeCompilerActive = true,
            backendLive = true,
        )

        init {
            owner.liveSessionCount.incrementAndGet()
        }

        override val isLive: Boolean get() = live
        override val schemaFingerprint = context.schemaFingerprint
        override val bindingFingerprint = context.bindingFingerprint
        override val displayIds = context.displayIds
        override val implementationId = RENDERER_IMPLEMENTATION
        override val readinessReceipt: RendererReadinessReceipt get() = receipt
        override fun prepareScreen(context: UiSyncRendererScreenContext): UiSyncRendererScreenCandidate? = null
        override fun retireScreens(): Boolean = true
        override fun close() {
            if (!live) return
            live = false
            subscription.close()
            owner.liveSessionCount.decrementAndGet()
        }
    }

    private data class SyntheticUiSyncCatalog(
        val output: UltraDisplayGraphOutput,
        val assignment: CompiledUiSyncDisplayAssignment,
        val programFingerprint: UiSyncSemanticThemeProgramFingerprint,
    )

    private object ProvisioningFixtures {
        fun syntheticUiSyncCatalog(profile: VerifiedUltraProfile): SyntheticUiSyncCatalog {
            val output = UltraDisplayGraphOutput(
                topologyDisplayId = UltraTopologyDisplayId(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                ),
                outputId = UltraDisplayOutputId.Custom("debug.synthetic.output"),
                uiSyncDisplayId = UltraUiSyncDisplayId(DISPLAY_ID),
            )
            val themeState = com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState()
            val packageDisplay = com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
                .parseArcPackageJson(requireNotNull(profile.candidate.content(
                    UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
                ))).displays.single()
            val assignment = CompiledUiSyncDisplayAssignment(
                phonePackageDigest = profile.candidate.entries.single {
                    it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
                }.digest,
                profileDisplayIndex = 0,
                profileDisplayId = "synthetic-profile-cluster",
                profileDisplayType = com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType.CLUSTER,
                destination = com.shilapi.xcertplay.ultra.DisplayDestination.CLUSTER,
                topologyDisplayId = output.topologyDisplayId,
                outputId = output.outputId,
                uiSyncDisplayId = requireNotNull(output.uiSyncDisplayId),
                programSelection = CompiledUiSyncThemeProgramSelection(
                    layoutIndex = 0,
                    state = themeState,
                    structuralFingerprint =
                        com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler()
                            .compile(packageDisplay, themeState)
                            .structuralProgram.structuralFingerprint,
                ),
                slotIngressAssignments = emptyList(),
            )
            return SyntheticUiSyncCatalog(
                output, assignment,
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(assignment)),
            )
        }

        fun syntheticUiSyncProvisioning(
            renderer: SyntheticRendererCapability,
            callbackExecutor: SyntheticBoundedExecutor,
            catalog: SyntheticUiSyncCatalog,
        ): UiSyncRuntimeProvisioning {
            val plan = UiSyncRendererStaticPlan(
                outputs = listOf(catalog.output),
                ingressSpecs = listOf(
                    UiSyncRendererIngressSpec(
                        id = UltraIngressId("debug.synthetic.video"),
                        airPlayStreamIdentity = UltraAirPlayStreamIdentity(
                            type = 111,
                            streamConnectionId = "debug-synthetic-stream",
                        ),
                    ),
                ),
            )
            return UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
                uiSyncInfo = AirPlayUiSyncInfo(mapOf("mode" to "debug-synthetic")),
                displays = listOf(
                    UiSyncDisplayRuntimeSpec(
                        descriptor = CarPlayUiSyncChannelDescriptor(DISPLAY_ID, VEHICLE_ID),
                        mode = CarPlayUiSyncMode.PASSIVE,
                        outboundResetConfig = mapOf("theme" to "debug-synthetic"),
                    ),
                ),
                staticPlan = plan,
                rendererCapability = renderer,
                callbackExecutor = callbackExecutor,
                authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                    semanticThemeProgramFingerprint = catalog.programFingerprint,
                    rendererImplementationId = RENDERER_IMPLEMENTATION,
                ),
            )
        }

        fun syntheticCafProvisioning(): CafRuntimeProvisioning {
            val tree = syntheticCafTree()
            val registry = CafPluginRegistry.from(
                listOf(
                    CafPluginRegistration(
                        pluginId = SYNTHETIC_PLUGIN_ID,
                        protocolVersion = "1.0",
                        clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                        handlerFactory = object : CafPluginHandlerFactory {
                            override val implementationId = CAF_HANDLER_IMPLEMENTATION
                            override fun create(session: CafProtocolSession): CafPluginHandler =
                                SyntheticCafHandler(session, tree)
                        },
                        schemaProvider = { tree },
                        runtimeReady = { true },
                    ),
                ),
            )
            val decoder = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1
            return CafRuntimeProvisioning.fromAuthorizedProfile(
                source = registry,
                decoderFactory = decoder,
                authorizationExpectation = CafRuntimeAuthorizationExpectation(
                    decoderImplementationId = decoder.implementationId,
                    handlerImplementationIds = mapOf(
                        SYNTHETIC_PLUGIN_ID to CAF_HANDLER_IMPLEMENTATION,
                    ),
                ),
            )
        }

        fun syntheticCafTree(): CafConfigTree = CafConfigTree.decode(
            linkedMapOf(
                "accessories" to listOf(
                    linkedMapOf(
                        "type" to 0x0000000001000001L,
                        "iid" to 1L,
                        "version" to "debug-synthetic-1",
                        "services" to listOf(
                            linkedMapOf(
                                "type" to 0x0000000011000002L,
                                "iid" to 2L,
                                "characteristics" to listOf(
                                    linkedMapOf(
                                        "type" to 0x0000000031000019L,
                                        "iid" to TEMPERATURE_IID,
                                        "format" to 9L,
                                        "priority" to 0L,
                                        "initialValue" to 21.5,
                                    ),
                                    linkedMapOf(
                                        "type" to 0x00000000FF000002L,
                                        "iid" to HIGH_PRIORITY_IID,
                                        "format" to 1L,
                                        "priority" to 1L,
                                        "initialValue" to 1L,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    private class SyntheticCafHandler(
        private val ownSession: CafProtocolSession,
        private val tree: CafConfigTree,
    ) : CafSchemaBoundPluginHandler {
        override val implementationId = CAF_HANDLER_IMPLEMENTATION
        override fun schemaSnapshot(): CafConfigTree = CafConfigTree.decode(tree.root)

        override fun onReadRequest(session: CafProtocolSession, message: CafMessageReader) {
            check(session === ownSession)
            session.send(
                CarAccessoryMessages.readResponse(
                    SYNTHETIC_PLUGIN_ID,
                    message.requireTransactionId(),
                    values = mapOf(TEMPERATURE_IID to 21.5),
                    errors = emptyMap(),
                ),
            )
        }

        override fun onRegisterRequest(session: CafProtocolSession, message: CafMessageReader) {
            check(session === ownSession)
            session.send(
                CarAccessoryMessages.registerResponse(
                    SYNTHETIC_PLUGIN_ID,
                    message.requireTransactionId(),
                    values = mapOf(TEMPERATURE_IID to 21.5),
                    errors = emptyMap(),
                ),
            )
            session.send(
                CarAccessoryMessages.updateNotify(
                    SYNTHETIC_PLUGIN_ID,
                    values = mapOf(TEMPERATURE_IID to 22.75),
                ),
            )
        }
    }
}
