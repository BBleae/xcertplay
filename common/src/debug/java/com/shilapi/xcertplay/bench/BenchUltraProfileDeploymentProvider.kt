package com.shilapi.xcertplay.bench

import android.content.Context
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifest
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedUltraProfileBackendLease
import com.shilapi.xcertplay.AuthorizedUltraProfileBackendProvisioning
import com.shilapi.xcertplay.AuthorizedUltraProfilePreparation
import com.shilapi.xcertplay.CompiledCafRuntimeContract
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalRuntimeContract
import com.shilapi.xcertplay.HostUltraCompositionInputs
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationProofConsumer
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationProof
import com.shilapi.xcertplay.UltraProfilePhysicalGenerationStatus
import com.shilapi.xcertplay.UltraProfileSessionToken
import com.shilapi.xcertplay.TrustedUltraVehicleIdentityAuthority
import com.shilapi.xcertplay.TrustedUltraVehicleIdentityResult
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraProfileDeploymentProvider
import com.shilapi.xcertplay.UltraProfileDeploymentResult
import com.shilapi.xcertplay.UltraProfileInstallResult
import com.shilapi.xcertplay.UltraProfileLoadResult
import com.shilapi.xcertplay.UltraProfileStore
import com.shilapi.xcertplay.UltraProfileVerificationIdentity
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfileBridge
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafBridgeConstructionException
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustVerifier
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import java.io.InputStream
import java.io.Closeable
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Debug bench only. An external user-owned P-256 signature is a local test permission, never
 * Apple/OEM profile authorization. The actual AA05 receipt is consumed by the unchanged Controller
 * lifecycle after this authority's preflight says that a fully staged import can be acquired.
 */
internal class BenchUltraProfileDeploymentProvider internal constructor(
    importRoot: Path,
    profileStoreRoot: Path,
    private val issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
    private val hostInputs: HostUltraCompositionInputs? = null,
    private val allowUiSyncBench: Boolean = false,
    private val carSpeedPermissionGate: BenchCarSpeedPermissionGate =
        BenchCarSpeedPermissionGate.unavailable(),
    private val bridgeFactory: (BenchCafProfileCompiler.Compiled) -> AndroidCarPropertyCafProfileBridge,
) : UltraProfileDeploymentProvider, TrustedUltraVehicleIdentityAuthority,
    UltraProfilePhysicalGenerationProofConsumer, Closeable {
    constructor(
        context: Context,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
        hostInputs: HostUltraCompositionInputs? = null,
        allowUiSyncBench: Boolean = false,
    ) : this(
        importRoot = context.applicationContext.noBackupFilesDir.toPath()
            .resolve(BenchUltraProfileImportStore.DIRECTORY),
        profileStoreRoot = context.applicationContext.noBackupFilesDir.toPath()
            .resolve("ultra-profile-v1"),
        issuer = issuer,
        hostInputs = hostInputs,
        allowUiSyncBench = allowUiSyncBench,
        carSpeedPermissionGate = BenchCarSpeedPermissionGate.forAndroid(context.applicationContext),
        bridgeFactory = { compiled ->
            when (compiled.source) {
                BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR ->
                    AndroidCarPropertyCafProfileBridge.forAndroid(
                        context.applicationContext, compiled.profile,
                    )
                BenchCafProfileCompiler.Compiled.Source.SYNTHETIC ->
                    AndroidCarPropertyCafProfileBridge.fromBackend(
                        compiled.profile, compiled.syntheticBackend(),
                    )
            }
        },
    )

    private val imports = BenchUltraProfileImportStore(importRoot)
    private val store = UltraProfileStore(profileStoreRoot)
    private val inspector = UltraProfilePackageInspector()
    private val compiler = BenchCafProfileCompiler()
    private val uiSyncCompiler = BenchUiSyncProfileCompiler()
    private val instrumentCompiler = BenchInstrumentSignalProfileCompiler()
    private val pendingCleanup = ArrayList<Closeable>()
    private class ProofBinding(
        val source: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationStatus,
    )
    @Volatile private var proofBinding: ProofBinding? = null
    @Volatile private var retired = false

    /** One product-selected coordinator only; an old binding cannot clear its successor. */
    override fun bindPhysicalGenerationProof(
        source: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationProof?,
    ): Closeable = bindPhysicalGenerationStatus { token ->
        source(token)?.let { UltraProfilePhysicalGenerationStatus.Current(it) }
            ?: UltraProfilePhysicalGenerationStatus.Revoked
    }

    @Synchronized
    override fun bindPhysicalGenerationStatus(
        source: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationStatus,
    ): Closeable {
        check(!retired && proofBinding == null) {
            "Debug Profile proof source is retired or already bound"
        }
        val binding = ProofBinding(source)
        proofBinding = binding
        return Closeable {
            synchronized(this) {
                if (proofBinding === binding) proofBinding = null
            }
        }
    }

    private fun currentPhysicalGenerationStatus(
        token: UltraProfileSessionToken,
    ): UltraProfilePhysicalGenerationStatus {
        val binding = proofBinding?.takeUnless { retired }
            ?: return UltraProfilePhysicalGenerationStatus.Revoked
        // The coordinator callback acquires its own state lock. Never acquire the provider
        // monitor here: resource retirement can wait for a callback while holding that monitor.
        val status = binding.source(token)
        return status.takeIf { !retired && proofBinding === binding }
            ?: UltraProfilePhysicalGenerationStatus.Revoked
    }

    private fun currentPhysicalGenerationProof(
        token: UltraProfileSessionToken,
    ): UltraProfilePhysicalGenerationProof? =
        (currentPhysicalGenerationStatus(token) as?
            UltraProfilePhysicalGenerationStatus.Current)?.proof

    @Synchronized
    override fun resolve(authenticationIdentity: MfiAuthenticationIdentity): TrustedUltraVehicleIdentityResult {
        if (retired || !drainPendingCleanup()) return TrustedUltraVehicleIdentityResult.Unavailable
        val snapshot = runCatching { imports.loadActive() }.getOrNull()
            ?: return TrustedUltraVehicleIdentityResult.Unavailable
        val verified = verify(snapshot, authenticationIdentity)
            ?: return TrustedUltraVehicleIdentityResult.Unavailable
        if (runCatching {
            val compiled = compiler.compile(verified)
            require(carSpeedPermissionGate.allows(compiled)) {
                "Signed real speed CAF lacks Android Automotive CAR_SPEED permission"
            }
            instrumentCompiler.compile(verified)?.requireSameProfile(verified)
            if (BenchUiSyncProfileCompiler.hasCatalog(verified)) {
                require(allowUiSyncBench) { "Signed UI Sync bench has no separate opt-in" }
                uiSyncCompiler.declaredDisplays(verified)
                uiSyncCompiler.declaredIngresses(verified)
                requireNotNull(hostInputs) { "The signed UI Sync bench has no exact Host inputs" }
            }
        }.isFailure) {
            return TrustedUltraVehicleIdentityResult.Unavailable
        }
        val installed = runCatching { store.install(verified) }.getOrNull()
        if (installed !is UltraProfileInstallResult.Installed &&
            installed !is UltraProfileInstallResult.CommittedButDurabilityUncertain) {
            return TrustedUltraVehicleIdentityResult.Unavailable
        }
        val loaded = runCatching {
            store.loadActive(verified.binding, verifier(snapshot, authenticationIdentity, verified.binding))
        }.getOrNull()
        if (loaded !is UltraProfileLoadResult.Loaded || !sameActiveImport(snapshot)) {
            return TrustedUltraVehicleIdentityResult.Unavailable
        }
        return TrustedUltraVehicleIdentityResult.Resolved(snapshot.vehicleId)
    }

    @Synchronized
    override fun acquire(request: UltraProfileActivationRequest): UltraProfileDeploymentResult {
        if (retired || !drainPendingCleanup()) return UltraProfileDeploymentResult.Unavailable
        val snapshot = runCatching { imports.loadActive() }.getOrNull()
            ?: return UltraProfileDeploymentResult.Unavailable
        if (snapshot.vehicleId != request.runtimeIdentity.vehicleId ||
            UltraRuntimeIdentity.from(snapshot.vehicleId, request.authenticationIdentity) !=
                request.runtimeIdentity) {
            return UltraProfileDeploymentResult.Unavailable
        }
        val verified = verify(snapshot, request.authenticationIdentity)
            ?: return UltraProfileDeploymentResult.Unavailable
        if (verified.binding.mfiCertificateIdentity != request.profileMfiCertificateIdentity) {
            return UltraProfileDeploymentResult.Unavailable
        }
        val loaded = runCatching {
            store.loadActive(verified.binding, verifier(snapshot, request.authenticationIdentity, verified.binding))
        }.getOrNull() as? UltraProfileLoadResult.Loaded
            ?: return UltraProfileDeploymentResult.Unavailable
        if (!sameActiveImport(snapshot)) return UltraProfileDeploymentResult.Unavailable

        val compiled = runCatching { compiler.compile(loaded.profile) }.getOrNull()
            ?: return UltraProfileDeploymentResult.Unavailable
        if (!carSpeedPermissionGate.allows(compiled)) {
            return UltraProfileDeploymentResult.Unavailable
        }
        // CAF, UI Sync, and the optional crosswalk must all be compiled from this one loaded
        // VerifiedUltraProfile instance. A malformed optional declaration is never ignored.
        val instrument = runCatching {
            instrumentCompiler.compile(loaded.profile)?.also {
                it.requireSameProfile(loaded.profile)
            }
        }.getOrElse { return UltraProfileDeploymentResult.Unavailable }
        val uiSync = if (BenchUiSyncProfileCompiler.hasCatalog(loaded.profile)) {
            if (!allowUiSyncBench) return UltraProfileDeploymentResult.Unavailable
            val host = hostInputs ?: return UltraProfileDeploymentResult.Unavailable
            try {
                BenchAuthorizedUiSyncOwner.open(loaded.profile, host,
                    ::currentPhysicalGenerationProof, ::currentPhysicalGenerationStatus)
            } catch (failure: BenchUiSyncConstructionException) {
                pendingCleanup += failure.pendingCleanup
                drainPendingCleanup()
                return UltraProfileDeploymentResult.Unavailable
            } catch (_: Exception) {
                return UltraProfileDeploymentResult.Unavailable
            }
        } else null
        val bridge = try {
            val mutableProfile = compiled.syntheticMutationProfile
            if (mutableProfile != null) {
                BenchSyntheticMutableCafBridge.open(mutableProfile).let { opened ->
                    BenchCafBridgeOwner(opened.provisioning, opened, null)
                }
            } else {
                val opened = bridgeFactory(compiled)
                try {
                    opened.bindRuntimeAuthorization { carSpeedPermissionGate.allows(compiled) }
                    val profileTree = compiled.profile.configTree
                    val sourceTree = opened.readOnlyInstrumentSource.configTree
                    require(CarAccessoryMessages.configResponse(
                        compiled.profile.pluginId, 1L, profileTree.root,
                    ).rcsBody.contentEquals(CarAccessoryMessages.configResponse(
                        compiled.profile.pluginId, 1L, sourceTree.root,
                    ).rcsBody)) {
                        "Debug CAF bridge source differs from the signed Profile tree"
                    }
                    BenchCafBridgeOwner(
                        opened.provisioning, opened,
                        if (instrument != null &&
                            compiled.source == BenchCafProfileCompiler.Compiled.Source.ANDROID_CAR)
                            opened.readOnlyInstrumentSource else null,
                        if (compiled.requiresCarSpeedPermission)
                            BenchCarSpeedPermissionMonitor(opened::observeRuntimeAuthorization)
                        else null,
                    )
                } catch (failure: Throwable) {
                    runCatching { opened.close() }.exceptionOrNull()?.let { cleanupFailure ->
                        pendingCleanup += opened
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        } catch (failure: AndroidCarPropertyCafBridgeConstructionException) {
            pendingCleanup += failure
            uiSync?.let { if (runCatching { it.close() }.isFailure) pendingCleanup += it }
            drainPendingCleanup()
            return UltraProfileDeploymentResult.Unavailable
        } catch (_: Exception) {
            uiSync?.let { if (runCatching { it.close() }.isFailure) pendingCleanup += it }
            return UltraProfileDeploymentResult.Unavailable
        }
        val resources = BenchRuntimeResources(bridge, uiSync)
        try {
            val cafContract = CompiledCafRuntimeContract.from(
                requireNotNull(bridge.provisioning.authorizationSnapshot()),
            )
            val instrumentContract = instrument?.let { crosswalk ->
                CompiledInstrumentSignalRuntimeContract(
                    exactVerifiedProfile = loaded.profile,
                    crosswalkDigest = requireNotNull(loaded.profile.candidate.entries.singleOrNull {
                        it.name == BenchInstrumentSignalProfileCompiler.SPEC_PATH
                    }).digest,
                    phonePackageDigest = crosswalk.phonePackageDigest,
                    cafSchemaFingerprint = cafContract.schemaFingerprint,
                    assignments = crosswalk.bindings.map { binding ->
                        CompiledInstrumentSignalDisplayAssignment(
                            binding.profileDisplayIndex, binding.profileDisplayId, binding.spec,
                        )
                    },
                )
            }
            require(carSpeedPermissionGate.allows(compiled)) {
                "Signed real speed CAF lost Android Automotive CAR_SPEED permission"
            }
            val manifest = issuer.issue(request, loaded.profile,
                uiSync = uiSync?.contract, caf = cafContract,
                instrumentCrosswalk = instrumentContract)
            if (!sameActiveImport(snapshot) || !carSpeedPermissionGate.allows(compiled)) {
                resources.close()
                return UltraProfileDeploymentResult.Unavailable
            }
            return UltraProfileDeploymentResult.Prepared(
                BenchCafPreparation(request, loaded.profile, manifest, resources) {
                    carSpeedPermissionGate.allows(compiled)
                },
            )
        } catch (failure: Throwable) {
            if (runCatching { resources.close() }.isFailure) pendingCleanup += resources
            if (failure is IllegalArgumentException) return UltraProfileDeploymentResult.Unavailable
            throw failure
        }
    }

    override fun stage(
        request: UltraProfileActivationRequest,
        candidateArchive: InputStream,
    ): UltraProfileDeploymentResult = UltraProfileDeploymentResult.Unavailable

    /** Retries a failed first close without losing the exact bridge/construction owner. */
    @Synchronized
    override fun close() {
        retired = true
        if (!drainPendingCleanup()) {
            throw IllegalStateException("Debug CAF bench cleanup remains pending")
        }
    }

    private fun drainPendingCleanup(): Boolean {
        val iterator = pendingCleanup.iterator()
        while (iterator.hasNext()) {
            if (runCatching { iterator.next().close() }.isSuccess) iterator.remove()
        }
        return pendingCleanup.isEmpty()
    }

    private fun sameActiveImport(snapshot: BenchUltraProfileImportStore.Snapshot): Boolean {
        val current = runCatching { imports.loadActive() }.getOrNull() ?: return false
        return current.generation == snapshot.generation &&
            MessageDigest.isEqual(current.archive, snapshot.archive) &&
            MessageDigest.isEqual(current.pin, snapshot.pin) &&
            MessageDigest.isEqual(current.detachedSignature ?: byteArrayOf(),
                snapshot.detachedSignature ?: byteArrayOf()) &&
            current.vehicleId == snapshot.vehicleId
    }

    private fun verify(
        snapshot: BenchUltraProfileImportStore.Snapshot,
        identity: MfiAuthenticationIdentity,
    ): VerifiedUltraProfile? {
        val candidate = when (val result = runCatching {
            inspector.inspect(snapshot.archive.inputStream())
        }.getOrNull()) {
            is UltraProfileInspectionResult.Candidate -> result.profile
            else -> return null
        }
        val binding = runCatching { trustBinding(snapshot.vehicleId, identity, candidate) }.getOrNull()
            ?: return null
        val signed = BenchUltraProfileSignatureBinding(
            vehicleId = snapshot.vehicleId,
            authenticationIdentity = identity,
            assetIdentifier = binding.assetIdentifier,
            iosContentVersion = binding.iosContentVersion,
            accessoryContentVersion = binding.accessoryContentVersion,
            candidateContentDigest = candidate.contentDigest,
        )
        runCatching { imports.recordSigningRequest(snapshot.generation, signed.canonicalSignedPayload()) }
        val signature = snapshot.detachedSignature ?: return null
        val envelope = runCatching {
            BenchUltraProfileSignatureVerifier.envelopeForDetachedSignature(signed, signature)
        }.getOrNull() ?: return null
        val accepted = runCatching {
            BenchUltraProfileSignatureVerifier(snapshot.pin).verify(signed, envelope)
        }.getOrNull()
        if (accepted !== BenchUltraProfileSignatureResult.MatchesUserPinnedKeyOnly) return null
        return when (val result = inspector.verify(candidate, binding,
            UltraProfileTrustVerifier { trustRequest ->
                if (trustRequest.expectedBinding == binding &&
                    trustRequest.candidate.contentDigest == binding.contentDigest) {
                    UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, envelope))
                } else UltraProfileTrustResult.Rejected()
            })) {
            is UltraProfileVerificationResult.Verified -> result.profile
            else -> null
        }
    }

    private fun verifier(
        snapshot: BenchUltraProfileImportStore.Snapshot,
        identity: MfiAuthenticationIdentity,
        binding: UltraProfileTrustBinding,
    ): UltraProfileTrustVerifier = UltraProfileTrustVerifier { trustRequest ->
        val signature = snapshot.detachedSignature
        if (signature == null || trustRequest.expectedBinding != binding ||
            trustRequest.candidate.contentDigest != binding.contentDigest ||
            trustBinding(snapshot.vehicleId, identity, trustRequest.candidate) != binding) {
            UltraProfileTrustResult.Rejected()
        } else {
            val signed = BenchUltraProfileSignatureBinding(
                snapshot.vehicleId, identity, binding.assetIdentifier,
                binding.iosContentVersion, binding.accessoryContentVersion,
                trustRequest.candidate.contentDigest,
            )
            val envelope = BenchUltraProfileSignatureVerifier.envelopeForDetachedSignature(signed, signature)
            if (BenchUltraProfileSignatureVerifier(snapshot.pin).verify(signed, envelope) ===
                BenchUltraProfileSignatureResult.MatchesUserPinnedKeyOnly) {
                UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, envelope))
            } else UltraProfileTrustResult.Rejected()
        }
    }

    private fun trustBinding(
        vehicleId: String,
        identity: MfiAuthenticationIdentity,
        candidate: UltraProfileCandidate,
    ): UltraProfileTrustBinding {
        val manifest = candidate.manifest.values
        fun field(name: String): String = requireNotNull(manifest[name] as? String) {
            "Signed bench Profile Info.plist has no $name"
        }
        fun accessoryContentVersion(): String {
            val value = manifest["AccessoryContentVersion"]
            if (value is String) return value
            // The optional phone developer-override metadata probe uses a numeric version.
            // Retain the exact integer text in this Debug-only trust binding; do not coerce a
            // floating-point value or loosen the Release Profile verifier.
            val digits = value?.toString()
            require(value is Number && digits != null && digits.length in 1..20 &&
                digits.all(Char::isDigit) && (digits == "0" || !digits.startsWith('0'))
            ) { "Signed bench Profile has no integral AccessoryContentVersion" }
            return digits
        }
        return UltraProfileTrustBinding(
            vehicleId = vehicleId,
            mfiCertificateIdentity = "xcertplay-mfi-authentication-v1:" +
                identity.certificateType.name + ":" + identity.sha256Hex(),
            assetIdentifier = field("AssetIdentifier"),
            iosContentVersion = field("iOSContentVersion"),
            accessoryContentVersion = accessoryContentVersion(),
            contentDigest = candidate.contentDigest,
        )
    }
}

private class BenchCafPreparation(
    request: UltraProfileActivationRequest,
    override val verifiedProfile: VerifiedUltraProfile,
    override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
    private val resources: BenchRuntimeResources,
    private val speedPermissionCurrent: () -> Boolean,
) : AuthorizedUltraProfilePreparation {
    override val requestIdentity = request.runtimeIdentity
    override val authenticationIdentity = request.authenticationIdentity
    private var transferred = false
    private var closed = false

    override fun commit(verificationIdentity: UltraProfileVerificationIdentity): AuthorizedUltraProfileBackendLease {
        check(speedPermissionCurrent()) { "Signed real speed CAF permission was revoked before commit" }
        return synchronized(this) {
            check(!closed && !transferred &&
                verificationIdentity == compiledManifest.verificationIdentity)
            transferred = true
            BenchCafLease(verificationIdentity, compiledManifest, resources,
                speedPermissionCurrent)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        if (!transferred) resources.close()
        closed = true
    }
}

private class BenchCafLease(
    override val verificationIdentity: UltraProfileVerificationIdentity,
    override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
    private val resources: BenchRuntimeResources,
    private val speedPermissionCurrent: () -> Boolean,
) : AuthorizedUltraProfileBackendLease {
    private var closeRequested = false
    private var closed = false
    private var opened = false
    override val isLive: Boolean get() =
        synchronized(this) { !closeRequested && !closed } && speedPermissionCurrent()

    override fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning {
        check(speedPermissionCurrent()) { "Signed real speed CAF permission was revoked" }
        return synchronized(this) {
            check(!closeRequested && !opened)
            opened = true
            BenchCafProvisioning(verificationIdentity, compiledManifest, resources,
                speedPermissionCurrent)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closeRequested = true
        resources.close()
        closed = true
    }
}

private class BenchCafProvisioning(
    override val verificationIdentity: UltraProfileVerificationIdentity,
    override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
    private val resources: BenchRuntimeResources,
    private val speedPermissionCurrent: () -> Boolean,
) : AuthorizedUltraProfileBackendProvisioning {
    private var closeRequested = false
    private var closed = false
    override val isLive: Boolean get() =
        synchronized(this) { !closeRequested && !closed } && speedPermissionCurrent()
    override val uiSyncProvisioning = resources.uiSync?.provisioning
    override val cafProvisioning: CafRuntimeProvisioning = resources.bridge.provisioning
    override val instrumentReadSource: VehicleStateReadSource? =
        resources.bridge.instrumentReadSource
    override val physicalOutputSealer = resources.uiSync?.sealer

    @Synchronized
    override fun close() {
        if (closed) return
        closeRequested = true
        resources.close()
        closed = true
    }
}

private class BenchCafBridgeOwner(
    val provisioning: CafRuntimeProvisioning,
    private val delegate: Closeable,
    val instrumentReadSource: VehicleStateReadSource?,
    private val speedPermissionMonitor: Closeable? = null,
) : Closeable {
    override fun close() {
        speedPermissionMonitor?.close()
        delegate.close()
    }
}

/** The same close owner moves Preparation → Lease → Provisioning; failed stages remain retryable. */
private class BenchRuntimeResources(
    val bridge: BenchCafBridgeOwner,
    val uiSync: BenchAuthorizedUiSyncOwner?,
) : Closeable {
    private var uiSyncClosed = uiSync == null
    private var bridgeClosed = false

    @Synchronized
    override fun close() {
        if (!uiSyncClosed) {
            checkNotNull(uiSync).close()
            uiSyncClosed = true
        }
        if (!bridgeClosed) {
            bridge.close()
            bridgeClosed = true
        }
    }
}
