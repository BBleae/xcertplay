package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntimeConstructionException
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.ultra.AuthorizedAndroidUiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleListener
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import java.io.Closeable
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashSet

/** Stable, non-sensitive reasons why an Ultra profile was not activated. */
internal enum class UltraProfileActivationDenialCode {
    INVALID_REQUEST,
    STALE_RECEIPT,
    STALE_GENERATION,
    BUSY,
    ALREADY_PREPARED,
    ALREADY_ACTIVE,
    PROVIDER_UNAVAILABLE,
    PROVIDER_FAILED,
    PROFILE_BINDING_MISMATCH,
    RUNTIME_MANIFEST_MISMATCH,
    RUNTIME_CONSTRUCTION_FAILED,
    COMMIT_FAILED,
    LEASE_NOT_LIVE,
    NO_PROVISIONING,
    REVOCATION_FAILED,
    CLOSED,
}

/** Atomic scene admission; BUSY never means an otherwise-live child was revoked. */
internal sealed interface UltraProfileSceneUseAttempt<out T> {
    data class Admitted<T>(val value: T) : UltraProfileSceneUseAttempt<T>
    data object Busy : UltraProfileSceneUseAttempt<Nothing>
    data object Revoked : UltraProfileSceneUseAttempt<Nothing>
}

/** Opaque generation authority for one exact AA05 receipt. */
internal class UltraProfileSessionToken private constructor(
    internal val owner: Any,
    internal val generation: Long,
) {
    override fun toString(): String = "UltraProfileSessionToken(<redacted>)"

    internal companion object {
        fun create(owner: Any, generation: Long) = UltraProfileSessionToken(owner, generation)
    }
}

private val PHYSICAL_GENERATION_PROOF_ISSUER = Any()

/**
 * Read-only snapshot of one exact live coordinator generation. The opaque lineage is the
 * coordinator-owned session record, not an MFi receipt capability. A same-session AA05 handoff
 * may replace its receipt while retaining this lineage; a reconnect cannot.
 *
 * The snapshot is not a use reservation. Callers must obtain a fresh proof when sealing or using
 * a physical binding and must not treat an old proof as liveness after revocation.
 */
internal class UltraProfilePhysicalGenerationProof private constructor(
    val runtimeIdentity: UltraRuntimeIdentity,
    val authenticationIdentity: MfiAuthenticationIdentity,
    private val token: UltraProfileSessionToken,
    private val lineage: Any,
) {
    fun matchesToken(candidate: UltraProfileSessionToken): Boolean = token === candidate

    fun sameLineage(other: UltraProfilePhysicalGenerationProof): Boolean =
        token === other.token && lineage === other.lineage &&
            runtimeIdentity == other.runtimeIdentity &&
            authenticationIdentity == other.authenticationIdentity

    override fun toString(): String = "UltraProfilePhysicalGenerationProof(<redacted>)"

    internal companion object {
        fun issue(
            issuer: Any,
            runtimeIdentity: UltraRuntimeIdentity,
            authenticationIdentity: MfiAuthenticationIdentity,
            token: UltraProfileSessionToken,
            lineage: Any,
        ): UltraProfilePhysicalGenerationProof {
            require(issuer === PHYSICAL_GENERATION_PROOF_ISSUER) {
                "Physical generation proof issuer mismatch"
            }
            return UltraProfilePhysicalGenerationProof(
                runtimeIdentity, authenticationIdentity, token, lineage,
            )
        }
    }
}

/** A live generation may be temporarily reserved without having lost its AA05 authority. */
internal sealed interface UltraProfilePhysicalGenerationStatus {
    class Current(val proof: UltraProfilePhysicalGenerationProof) :
        UltraProfilePhysicalGenerationStatus {
        override fun toString(): String = "UltraProfilePhysicalGenerationStatus.Current(<redacted>)"
    }

    data object Busy : UltraProfilePhysicalGenerationStatus
    data object Revoked : UltraProfilePhysicalGenerationStatus
}

internal sealed interface UltraProfileSessionStartResult {
    data class Started(val token: UltraProfileSessionToken) : UltraProfileSessionStartResult
    data class Denied(val code: UltraProfileActivationDenialCode) : UltraProfileSessionStartResult
}

internal sealed interface UltraProfilePrepareResult {
    data class Prepared(val activation: PreparedUltraProfileActivation) : UltraProfilePrepareResult
    data class Denied(val code: UltraProfileActivationDenialCode) : UltraProfilePrepareResult
}

internal sealed interface UltraProfileCommitResult {
    data class Activated(val lease: AuthorizedUltraProfileLease) : UltraProfileCommitResult
    data class Denied(val code: UltraProfileActivationDenialCode) : UltraProfileCommitResult
}

internal sealed interface UltraProfileProvisioningOpenResult {
    data class Opened(
        val runtimeLease: AuthorizedUltraRuntimeLease,
    ) : UltraProfileProvisioningOpenResult

    data class Denied(
        val code: UltraProfileActivationDenialCode,
    ) : UltraProfileProvisioningOpenResult
}

/**
 * Digest of one verifier-produced profile, its complete trust binding/proof, and the exact MFi
 * identity authenticated by AA05. Stable identifiers are never rendered in diagnostics.
 */
internal class UltraProfileVerificationIdentity private constructor(bytes: ByteArray) {
    private val digest = bytes.copyOf()

    init {
        require(digest.size == SHA256_BYTES)
    }

    fun bytes(): ByteArray = digest.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UltraProfileVerificationIdentity && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = "UltraProfileVerificationIdentity(<redacted>)"

    internal companion object {
        private const val SHA256_BYTES = 32
        private val DOMAIN =
            "xcertplay-authorized-ultra-profile-v1\u0000".toByteArray(StandardCharsets.US_ASCII)

        fun from(
            runtimeIdentity: UltraRuntimeIdentity,
            authenticationIdentity: MfiAuthenticationIdentity,
            profile: VerifiedUltraProfile,
        ): UltraProfileVerificationIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN)
            update(digest, runtimeIdentity.vehicleId.toByteArray(StandardCharsets.UTF_8))
            update(digest, runtimeIdentity.authenticationKind.name.toByteArray(StandardCharsets.US_ASCII))
            update(digest, runtimeIdentity.authenticationIdentitySha256)
            update(digest, authenticationIdentity.certificateType.name.toByteArray(StandardCharsets.US_ASCII))
            update(digest, authenticationIdentity.sha256Bytes())

            val binding = profile.binding
            update(digest, binding.vehicleId.toByteArray(StandardCharsets.UTF_8))
            update(digest, binding.mfiCertificateIdentity.toByteArray(StandardCharsets.UTF_8))
            update(digest, binding.assetIdentifier.toByteArray(StandardCharsets.UTF_8))
            update(digest, binding.iosContentVersion.toByteArray(StandardCharsets.UTF_8))
            update(digest, binding.accessoryContentVersion.toByteArray(StandardCharsets.UTF_8))
            update(digest, binding.contentDigest.bytes())
            update(digest, profile.trustProofBytes())
            return UltraProfileVerificationIdentity(digest.digest())
        }

        private fun update(digest: MessageDigest, value: ByteArray) {
            val size = value.size
            digest.update(
                byteArrayOf(
                    (size ushr 24).toByte(),
                    (size ushr 16).toByte(),
                    (size ushr 8).toByte(),
                    size.toByte(),
                ),
            )
            digest.update(value)
        }
    }
}

/**
 * Frozen input offered to the fixed product provider after the coordinator validates the current
 * receipt. It deliberately contains no receipt capability and no caller-selected verifier.
 */
internal class UltraProfileActivationRequest internal constructor(
    val runtimeIdentity: UltraRuntimeIdentity,
    val authenticationIdentity: MfiAuthenticationIdentity,
) {
    /** Exact opaque value that the OEM verifier must attest in the profile trust binding. */
    val profileMfiCertificateIdentity: String =
        canonicalProfileMfiCertificateIdentity(authenticationIdentity)

    init {
        require(
            UltraRuntimeIdentity.from(runtimeIdentity.vehicleId, authenticationIdentity) ==
                runtimeIdentity,
        ) { "Activation request MFi identity mismatch" }
    }

    override fun toString(): String = "UltraProfileActivationRequest(<redacted>)"
}

/**
 * Backend result for the isolated coordinator machinery. `Prepared` is not reachable from the
 * product constructor in this source tree; [VerifiedUltraProfile] alone is structural evidence,
 * not an OEM authorization capability.
 */
internal sealed interface UltraProfileBackendPrepareResult {
    data class Prepared(
        val preparation: AuthorizedUltraProfilePreparation,
    ) : UltraProfileBackendPrepareResult

    object Unavailable : UltraProfileBackendPrepareResult
}

/**
 * Isolated deployment seam used to exercise the transaction machinery. The production
 * constructor cannot accept an implementation of this interface and adapts only its statically
 * selected [UltraProfileDeploymentProvider], which is `Disabled`. A future product source set must
 * replace that fixed composition with a pinned-root deployment authority; a caller-supplied
 * [com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustVerifier] is insufficient.
 */
internal fun interface UltraProfileActivationBackend {
    fun prepare(request: UltraProfileActivationRequest): UltraProfileBackendPrepareResult
}

/**
 * One prepared, still-revocable profile compilation. [verifiedProfile] proves only that the
 * inspector and one verifier callback accepted the binding. The fixed deployment backend remains
 * responsible for real OEM signature/trust-root authorization before implementing this contract.
 */
internal interface AuthorizedUltraProfilePreparation : Closeable {
    val requestIdentity: UltraRuntimeIdentity
    val authenticationIdentity: MfiAuthenticationIdentity
    val verifiedProfile: VerifiedUltraProfile
    val compiledManifest: AuthorizedUltraCompiledRuntimeManifest

    /** Atomically transfers prepared resources into one live, identity-bound backend lease. */
    fun commit(
        verificationIdentity: UltraProfileVerificationIdentity,
    ): AuthorizedUltraProfileBackendLease
}

/**
 * Backend-owned live capability. A future authorized profile compiler may use this seam to create
 * matching UI Sync and CAF provisioning without exposing its compiler or accepting raw profiles.
 */
internal interface AuthorizedUltraProfileBackendLease : Closeable {
    val verificationIdentity: UltraProfileVerificationIdentity
    val compiledManifest: AuthorizedUltraCompiledRuntimeManifest
    val isLive: Boolean

    fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning
}

/**
 * One child use of the compiled profile. Closing it must invalidate/close every owned provisioning
 * object; the coordinator also closes all children on disconnect, reconnect, or shutdown.
 */
internal interface AuthorizedUltraProfileBackendProvisioning : Closeable {
    val verificationIdentity: UltraProfileVerificationIdentity
    val compiledManifest: AuthorizedUltraCompiledRuntimeManifest
    val isLive: Boolean
    val uiSyncProvisioning: UiSyncRuntimeProvisioning?
    val cafProvisioning: CafRuntimeProvisioning?

    /** Optional real, read-only CAF source; synthetic protocol backends leave this null. */
    val instrumentReadSource: VehicleStateReadSource? get() = null

    /** Fixed candidate-owned bridge; a UI Sync product child may not publish without sealing. */
    val physicalOutputSealer: AuthorizedUltraPhysicalOutputSealer? get() = null
}

/** The authority is the exact object offered to the shared provisioning's physical preflight. */
internal interface AuthorizedUltraPhysicalOutputSealer {
    val authority: UiSyncPhysicalOutputAuthority

    /** Exact issued batch/child/Profile join, not merely equivalent route metadata. */
    fun authorizesInstrumentSource(
        batch: UiSyncPhysicalOutputBatch,
        provisioning: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
    ): Boolean = false

    fun seal(
        manifest: AuthorizedUltraCompiledRuntimeManifest,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
        token: UltraProfileSessionToken,
        proof: UltraProfilePhysicalGenerationProof,
        provisioning: AuthorizedUltraProfileProvisioning,
    )
}

/**
 * Product-selected provider receives only a fresh proof reader for its exact coordinator.
 * Installation must be atomic: a throwing bind must retain no callback. The returned lease
 * clears only that exact binding, and is retired after all coordinator resources have closed.
 * This is not a caller-selected MFi verifier or an alternate product construction path.
 */
internal interface UltraProfilePhysicalGenerationProofConsumer {
    fun bindPhysicalGenerationProof(
        source: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationProof?,
    ): Closeable

    /** Existing providers can still consume nullable proof; new ones preserve transient BUSY. */
    fun bindPhysicalGenerationStatus(
        source: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationStatus,
    ): Closeable = bindPhysicalGenerationProof { token ->
        (source(token) as? UltraProfilePhysicalGenerationStatus.Current)?.proof
    }
}

/** Caller handle for a preparation owned by [UltraProfileActivationCoordinator]. */
internal class PreparedUltraProfileActivation internal constructor(
    private val coordinator: UltraProfileActivationCoordinator,
    internal val owner: Any,
    internal val sessionToken: UltraProfileSessionToken,
    val verificationIdentity: UltraProfileVerificationIdentity,
    val manifestDigest: UltraCompiledRuntimeManifestDigest,
) : Closeable {
    @Volatile
    internal var revoked = false

    override fun close() {
        coordinator.cancelPreparation(this)
    }

    override fun toString(): String = "PreparedUltraProfileActivation(<redacted>)"
}

/**
 * Live authorization lease. It exposes only profile-compiled provisioning children; it cannot
 * change the verified profile, MFi identity, vehicle ID, or generation after commit.
 */
internal class AuthorizedUltraProfileLease internal constructor(
    private val coordinator: UltraProfileActivationCoordinator,
    internal val owner: Any,
    internal val sessionToken: UltraProfileSessionToken,
    val verificationIdentity: UltraProfileVerificationIdentity,
    val manifestDigest: UltraCompiledRuntimeManifestDigest,
) : Closeable {
    @Volatile
    internal var revoked = false

    val isLive: Boolean
        get() = coordinator.isLeaseLive(this)

    fun openRuntime(): UltraProfileProvisioningOpenResult =
        coordinator.openProvisioning(this)

    override fun close() {
        coordinator.releaseLease(this)
    }

    override fun toString(): String = "AuthorizedUltraProfileLease(<redacted>)"
}

/**
 * A coordinator-owned runtime child. Raw UI Sync/CAF provisioning never escapes this boundary;
 * the coordinator keeps the exact [runtime] in its revocation tree after ownership transfer.
 */
internal class AuthorizedUltraProfileProvisioning internal constructor(
    private val coordinator: UltraProfileActivationCoordinator,
    internal val owner: Any,
    internal val parent: AuthorizedUltraProfileLease,
    val verificationIdentity: UltraProfileVerificationIdentity,
    val manifestDigest: UltraCompiledRuntimeManifestDigest,
    private val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
    private val runtimeOwner: AirPlayUltraRuntime,
    authorizedFeatures: Set<AirPlayFeature>,
) : Closeable {
    private val compiledFeatures = Collections.unmodifiableSet(LinkedHashSet(authorizedFeatures))
    @Volatile
    internal var revoked = false
    @Volatile
    internal var physicalOutputSealed = false

    val isLive: Boolean
        get() = coordinator.isProvisioningLive(this)

    /** A read-only, exact-child comparison; never grants an asset-installation claim. */
    internal fun allowsClusterAssetAcquisition(
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean = coordinator.allowsClusterAssetAcquisition(this, assetId, authenticationIdentity)

    internal fun displayAssignmentsFor(
        profile: VerifiedUltraProfile,
    ): List<CompiledUiSyncDisplayAssignment> {
        check(isLive) { "Authorized Ultra display assignment lease is not live" }
        check(compiledManifest.manifestDigest == manifestDigest) {
            "Authorized Ultra display assignment manifest changed"
        }
        return compiledManifest.displayAssignmentsFor(profile)
    }

    internal fun instrumentAssignmentsFor(
        profile: VerifiedUltraProfile,
    ): List<CompiledInstrumentSignalDisplayAssignment> = withSceneSubmissionUse {
        check(compiledManifest.manifestDigest == manifestDigest) {
            "Authorized instrument manifest changed"
        }
        compiledManifest.instrumentAssignmentsFor(profile)
    }

    /** A read-only child of this exact AA05 runtime and physical output generation. */
    internal fun openInstrumentSignalSession(
        profile: VerifiedUltraProfile,
        assignment: CompiledInstrumentSignalDisplayAssignment,
        physicalBatch: UiSyncPhysicalOutputBatch,
        listener: InstrumentSignalBundleListener,
    ): AuthorizedInstrumentSignalSession = coordinator.openInstrumentSignalSession(
        this, profile, assignment, physicalBatch, listener,
    )

    internal fun requireSceneDisplayAssignments(
        profile: VerifiedUltraProfile,
        schemaFingerprint: UiSyncSchemaFingerprint,
        bindingFingerprint: UiSyncBindingFingerprint,
        graph: UltraDisplayGraphSnapshot,
    ): List<CompiledUiSyncDisplayAssignment> {
        val assignments = displayAssignmentsFor(profile)
        val contract = requireNotNull(compiledManifest.uiSync)
        require(contract.schemaFingerprint == schemaFingerprint &&
            contract.bindingFingerprint == bindingFingerprint &&
            contract.outputs.toSet() == graph.outputs.toSet() &&
            contract.outputs.size == graph.outputs.size
        ) { "Scene renderer schema, binding or graph differs from the issued manifest" }
        return assignments
    }

    /** A type-3 request selects only a still-live, exact-Profile signed static catalog entry. */
    internal fun selectThemeVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        command: CarPlayUiSyncSemanticCommand.LayoutChange,
    ): CompiledUiSyncSelectedThemeVariant? {
        check(isLive) { "Authorized Ultra theme selection lease is not live" }
        check(compiledManifest.manifestDigest == manifestDigest) {
            "Authorized Ultra theme selection manifest changed"
        }
        return compiledManifest.selectThemeVariantFor(profile, displayId, command)
    }

    /** Lease-gated: the exact current variant iff it renders identically for any appearance. */
    internal fun selectAppearanceInvariantVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        currentIntent: CompiledUiSyncThemeIntent,
    ): CompiledUiSyncSelectedThemeVariant? {
        check(isLive) { "Authorized Ultra appearance selection lease is not live" }
        check(compiledManifest.manifestDigest == manifestDigest) {
            "Authorized Ultra appearance selection manifest changed"
        }
        return compiledManifest.selectAppearanceInvariantVariantFor(
            profile, displayId, currentIntent,
        )
    }

    /** An exact, lease-gated v3 palette choice; the phone did not send a type-3 layout change. */
    internal fun selectPaletteVariantFor(
        profile: VerifiedUltraProfile,
        displayId: UltraUiSyncDisplayId,
        currentIntent: CompiledUiSyncThemeIntent,
        command: CarPlayUiSyncSemanticCommand.PaletteChange,
    ): CompiledUiSyncSelectedThemeVariant? {
        check(isLive) { "Authorized Ultra palette selection lease is not live" }
        check(compiledManifest.manifestDigest == manifestDigest) {
            "Authorized Ultra palette selection manifest changed"
        }
        return compiledManifest.selectPaletteVariantFor(profile, displayId, currentIntent, command)
    }

    /**
     * Admission for one authorized scene, from planning through the EGL queue handoff. A
     * revocation closes admission under the same coordinator lock, then waits for admitted uses
     * before retiring this provisioning. The ticket is not a presentation/retirement fence.
     */
    internal fun <T> withSceneSubmissionUse(block: () -> T): T =
        coordinator.withSceneSubmissionUse(this, block)

    internal fun <T> tryWithSceneSubmissionUse(block: () -> T): UltraProfileSceneUseAttempt<T> =
        coordinator.tryWithSceneSubmissionUse(this, block)

    /** Bounded startup wait only; admission must still be attempted atomically afterward. */
    internal fun awaitSceneOperationChange(timeoutMillis: Long) =
        coordinator.awaitSceneOperationChange(timeoutMillis)

    internal fun runtimeForMediaEngine(): AirPlayUltraRuntime {
        check(isLive) { "Authorized Ultra runtime lease is not live" }
        return runtimeOwner
    }

    internal fun runtimeIsLive(): Boolean = runtimeOwner.isLive()

    /** Product admission point, after the open operation cleared but before configuration. */
    internal fun sealPhysicalOutputAuthority(): UltraProfileActivationDenialCode? =
        coordinator.sealPhysicalOutputAuthority(this)

    internal fun configuration(
        sessionManagementInfo: AirPlaySessionManagementInfo? = null,
    ): AirPlayUltraConfiguration {
        check(isLive) { "Authorized Ultra runtime lease is not live" }
        val features = LinkedHashSet(compiledFeatures)
        if (sessionManagementInfo != null) features += AirPlayFeature.SESSION_MANAGEMENT
        return runtimeOwner.configuration(
            configuredFeatures = Collections.unmodifiableSet(features),
            sessionManagementInfo = sessionManagementInfo,
        )
    }

    override fun close() {
        coordinator.releaseProvisioning(this)
    }

    override fun toString(): String = "AuthorizedUltraProfileProvisioning(<redacted>)"
}

/** Product-facing name for the coordinator-owned, manifest-bound runtime child. */
internal typealias AuthorizedUltraRuntimeLease = AuthorizedUltraProfileProvisioning

/** Exact provisional AA05 lineage owned by [UltraProfileActivationCoordinator]. */
internal class PreparedUltraProfileSessionRenewal internal constructor(
    private val coordinator: UltraProfileActivationCoordinator,
    internal val owner: Any,
    internal val sessionToken: UltraProfileSessionToken,
    val predecessor: MfiAuthenticationReceipt,
    val successor: MfiAuthenticationReceipt,
) : Closeable {
    @Volatile
    internal var committed = false
    @Volatile
    internal var rolledBack = false
    @Volatile
    internal var requiresLifecycleClose = false

    fun commit() = coordinator.commitSessionRenewal(this)

    override fun close() = coordinator.rollbackSessionRenewal(this)

    override fun toString(): String = "PreparedUltraProfileSessionRenewal(<redacted>)"
}

/**
 * Serializes AA05 receipt admission, profile preparation, commit, and revocation.
 *
 * Receipt object identity is an in-process capability: a digest-equivalent receipt from an older
 * or different connection is rejected. Every accepted receipt is single-use for this coordinator.
 * Every provider/lease/close call runs after releasing the state lock. A lock-only reservation is
 * revalidated before publication, so close can revoke a generation immediately and every late
 * provider result is closed rather than becoming visible.
 */
internal class UltraProfileActivationCoordinator private constructor(
    private val backend: UltraProfileActivationBackend,
    private val manifestIssuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
) : Closeable {
    /** Product construction is permanently routed through the statically selected provider. */
    constructor() : this(
        UltraProfileCompositionRoot.deploymentProvider(),
        UltraProfileCompositionRoot.manifestIssuer(),
    )

    /** ProductSession uses only this captured selection; no second global-root lookup occurs. */
    private constructor(epoch: UltraProfilePolicyEpoch) : this(
        epoch.deploymentProvider,
        epoch.manifestIssuer,
    )

    private constructor(
        selectedProvider: UltraProfileDeploymentProvider,
        issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
    ) : this(DeploymentProviderActivationBackend(selectedProvider), issuer) {
        val weakCoordinator = WeakReference(this)
        proofBinding = (selectedProvider as? UltraProfilePhysicalGenerationProofConsumer)
            ?.bindPhysicalGenerationStatus { token ->
                weakCoordinator.get()?.physicalGenerationStatus(token)
                    ?: UltraProfilePhysicalGenerationStatus.Revoked
            }
    }

    private data class SessionRecord(
        val token: UltraProfileSessionToken,
        var receipt: MfiAuthenticationReceipt,
        val runtimeIdentity: UltraRuntimeIdentity,
    )

    private data class RenewalRecord(
        val handle: PreparedUltraProfileSessionRenewal,
        val session: SessionRecord,
        val predecessor: MfiAuthenticationReceipt,
        val successor: MfiAuthenticationReceipt,
        var predecessorRevoked: Boolean = false,
    )

    private data class PreparationRecord(
        val handle: PreparedUltraProfileActivation,
        val delegate: AuthorizedUltraProfilePreparation,
        val session: SessionRecord,
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
    )

    private data class ProvisioningRecord(
        val handle: AuthorizedUltraProfileProvisioning,
        val delegate: AuthorizedUltraProfileBackendProvisioning,
        val runtime: AirPlayUltraRuntime,
        /** Exact object installed into [runtime], not a later provider getter result. */
        val uiSyncProvisioning: UiSyncRuntimeProvisioning?,
        val physicalOutputSealer: AuthorizedUltraPhysicalOutputSealer?,
        val physicalOutputAuthority: UiSyncPhysicalOutputAuthority?,
        val instrumentReadSource: VehicleStateReadSource?,
        val instrumentSessions: IdentityHashMap<AuthorizedInstrumentSignalSession, Unit> =
            IdentityHashMap(),
    )

    private data class LeaseRecord(
        val handle: AuthorizedUltraProfileLease,
        val delegate: AuthorizedUltraProfileBackendLease,
        val session: SessionRecord,
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
        val children: IdentityHashMap<
            AuthorizedUltraProfileProvisioning,
            ProvisioningRecord,
        > = IdentityHashMap(),
    )

    private enum class OperationKind {
        SESSION_SWITCH,
        PREPARE,
        COMMIT,
        OPEN_PROVISIONING,
        PROBE_LEASE,
        PROBE_PROVISIONING,
        SEAL_PHYSICAL,
    }

    /** Reservation only. No external method is ever invoked while [stateLock] is held. */
    private class Operation(
        val kind: OperationKind,
        val session: SessionRecord?,
        val preparation: PreparationRecord? = null,
        val lease: LeaseRecord? = null,
        val ownerThread: Thread = Thread.currentThread(),
    ) {
        var cancelled = false
    }

    private data class ClosePlan(
        val preparations: MutableList<AuthorizedUltraProfilePreparation> = mutableListOf(),
        val instrumentSessions: MutableList<AuthorizedInstrumentSignalSession> = mutableListOf(),
        val runtimes: MutableList<Closeable> = mutableListOf(),
        val provisionings: MutableList<AuthorizedUltraProfileBackendProvisioning> = mutableListOf(),
        val leases: MutableList<AuthorizedUltraProfileBackendLease> = mutableListOf(),
    ) {
        val isEmpty: Boolean
            get() = preparations.isEmpty() && instrumentSessions.isEmpty() &&
                runtimes.isEmpty() &&
                provisionings.isEmpty() && leases.isEmpty()

        fun append(other: ClosePlan) {
            preparations += other.preparations
            instrumentSessions += other.instrumentSessions
            runtimes += other.runtimes
            provisionings += other.provisionings
            leases += other.leases
        }
    }

    private data class PreparationSnapshot(
        val requestIdentity: UltraRuntimeIdentity,
        val authenticationIdentity: MfiAuthenticationIdentity,
        val profile: VerifiedUltraProfile,
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
    )

    private data class LeaseSnapshot(
        val verificationIdentity: UltraProfileVerificationIdentity,
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
        val isLive: Boolean,
    )

    private data class ProvisioningSnapshot(
        val verificationIdentity: UltraProfileVerificationIdentity,
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
        val parentIsLive: Boolean,
        val isLive: Boolean,
        val uiSync: UiSyncRuntimeProvisioning?,
        val caf: CafRuntimeProvisioning?,
        val instrumentReadSource: VehicleStateReadSource?,
        val physicalOutputSealer: AuthorizedUltraPhysicalOutputSealer?,
        val physicalOutputAuthority: UiSyncPhysicalOutputAuthority?,
        val manifestMatchesProvisioning: Boolean,
    )

    private data class ExternalResult<T>(val value: T?, val failure: Throwable?)

    private class ReceiptRevocation(
        val receipt: MfiAuthenticationReceipt,
        val ownerThread: Thread = Thread.currentThread(),
    )

    private val stateLock = Object()
    /** Exact provider binding; retained on failed retirement so a new owner cannot replace it. */
    private var proofBinding: Closeable? = null
    private val owner = Any()
    private val usedReceipts = IdentityHashMap<MfiAuthenticationReceipt, Unit>()
    private var generation = 0L
    private var session: SessionRecord? = null
    private var renewal: RenewalRecord? = null
    private var preparation: PreparationRecord? = null
    private var lease: LeaseRecord? = null
    private var operation: Operation? = null
    private var pendingRetirement = ClosePlan()
    private var pendingRetirementFailure: Throwable? = null
    private var receiptRevocation: ReceiptRevocation? = null
    /** The exact callback retained by the MFi authority when retirement failed. */
    private var failedReceiptRevocation: MfiAuthenticationReceipt? = null
    /** Scene queue handoffs admitted by the exact current provisioning. */
    private var activeSceneSubmissionUses = 0
    private val sceneSubmissionOwners = IdentityHashMap<Thread, Int>()
    private val sceneSubmissionChildOwners = IdentityHashMap<Thread,
        IdentityHashMap<AuthorizedUltraProfileProvisioning, Int>>()
    /** Fail-closed receipt loss may only retire roots later, in Controller-ordered lifecycle close. */
    private var retirementDeferredUntilLifecycleClose = false
    private var closed = false
    private var revocationFailed = false
    private var policyRevoked = false
    /** Strong exact old-child owners, retained even if a failed close detaches the lease tree. */
    private val policyCoverAuthorities = IdentityHashMap<UiSyncPhysicalOutputAuthority, Unit>()

    /** Lock-only phase one; no Host cover callback runs while this coordinator lock is held. */
    internal fun beginPolicyEpochRevocation() {
        val authorities = synchronized(stateLock) {
            policyRevoked = true
            operation?.cancelled = true
            policyCoverAuthorities.keys.toList()
        }
        authorities.forEach { authority ->
            (authority as? AuthorizedAndroidUiSyncPhysicalOutputAuthority)
                ?.beginPolicyEpochRevocation()
        }
    }

    /** Exact old-child phase two; a false ACK retains the same authority objects for retry. */
    internal fun revokePhysicalOutputForPolicyEpoch(): Boolean {
        beginPolicyEpochRevocation()
        val authorities = synchronized(stateLock) { policyCoverAuthorities.keys.toList() }
        return authorities.all { authority ->
            (authority as? AuthorizedAndroidUiSyncPhysicalOutputAuthority)
                ?.revokeForPolicyEpoch() == true
        }
    }

    /** Distinguishes a live reservation from permanent loss of this exact AA05 generation. */
    internal fun physicalGenerationStatus(
        token: UltraProfileSessionToken,
    ): UltraProfilePhysicalGenerationStatus = synchronized(stateLock) {
        // A superseded token or dead receipt must not look merely busy, even while a provider
        // probe, seal, or close operation is still unwinding on another thread.
        if (closed || revocationFailed || policyRevoked || receiptRevocation != null ||
            retirementDeferredUntilLifecycleClose || token.owner !== owner
        ) return@synchronized UltraProfilePhysicalGenerationStatus.Revoked
        val current = session ?: return@synchronized UltraProfilePhysicalGenerationStatus.Revoked
        if (current.token !== token || current.token.generation != token.generation ||
            !sessionReceiptIsLiveLocked(current)
        ) return@synchronized UltraProfilePhysicalGenerationStatus.Revoked
        val currentReceipt = current.receipt.takeIf { it.isLiveForUltraActivation() }
            ?: renewal?.takeIf { it.session === current &&
                it.predecessor === current.receipt && it.successor.isLiveForUltraActivation()
            }?.successor ?: return@synchronized UltraProfilePhysicalGenerationStatus.Revoked
        val authenticationIdentity = currentReceipt.authenticationIdentity
        if (UltraRuntimeIdentity.from(current.runtimeIdentity.vehicleId,
            authenticationIdentity) != current.runtimeIdentity
        ) return@synchronized UltraProfilePhysicalGenerationStatus.Revoked
        val active = operation
        if (active != null && (active.kind != OperationKind.SEAL_PHYSICAL ||
                active.ownerThread !== Thread.currentThread())) {
            return@synchronized UltraProfilePhysicalGenerationStatus.Busy
        }
        UltraProfilePhysicalGenerationStatus.Current(
            UltraProfilePhysicalGenerationProof.issue(
                PHYSICAL_GENERATION_PROOF_ISSUER,
                current.runtimeIdentity, authenticationIdentity, token, current,
            ),
        )
    }

    /** Legacy nullable evidence; callers needing retry semantics should use the typed status. */
    internal fun currentPhysicalGenerationProof(
        token: UltraProfileSessionToken,
    ): UltraProfilePhysicalGenerationProof? =
        (physicalGenerationStatus(token) as? UltraProfilePhysicalGenerationStatus.Current)?.proof

    fun beginSession(
        vehicleId: String,
        receipt: MfiAuthenticationReceipt,
    ): UltraProfileSessionStartResult {
        synchronized(stateLock) {
            if (closed || revocationFailed || policyRevoked) {
                return UltraProfileSessionStartResult.Denied(
                    UltraProfileActivationDenialCode.CLOSED,
                )
            }
            if (receiptRevocation != null) {
                return UltraProfileSessionStartResult.Denied(UltraProfileActivationDenialCode.BUSY)
            }
            // A provider callback cannot synchronously supersede the operation invoking it.
            if (operation?.ownerThread === Thread.currentThread()) {
                return UltraProfileSessionStartResult.Denied(
                    UltraProfileActivationDenialCode.BUSY,
                )
            }
        }
        val authenticatedIdentity = receipt.consumeForUltraActivation {
            onAuthenticationReceiptRevoked(receipt)
        }
            ?: return UltraProfileSessionStartResult.Denied(
                UltraProfileActivationDenialCode.STALE_RECEIPT,
            )
        val runtimeIdentity = try {
            UltraRuntimeIdentity.from(vehicleId, authenticatedIdentity)
        } catch (_: IllegalArgumentException) {
            return UltraProfileSessionStartResult.Denied(
                UltraProfileActivationDenialCode.INVALID_REQUEST,
            )
        }

        while (true) {
            var interrupted: Operation? = null
            var exhausted = false
            lateinit var reserved: Operation
            lateinit var nextSession: SessionRecord
            val closePlan = synchronized(stateLock) {
                if (closed || revocationFailed || policyRevoked) {
                    return UltraProfileSessionStartResult.Denied(
                        UltraProfileActivationDenialCode.CLOSED,
                    )
                }
                if (receiptRevocation != null) {
                    return UltraProfileSessionStartResult.Denied(UltraProfileActivationDenialCode.BUSY)
                }
                operation?.let { active ->
                    if (active.ownerThread === Thread.currentThread()) {
                        return UltraProfileSessionStartResult.Denied(
                            UltraProfileActivationDenialCode.BUSY,
                        )
                    }
                    active.cancelled = true
                    interrupted = active
                    session = null
                    ClosePlan().also {
                        detachPreparationLocked(it)
                        detachLeaseLocked(it)
                    }
                } ?: run {
                    if (usedReceipts.containsKey(receipt)) {
                        return UltraProfileSessionStartResult.Denied(
                            UltraProfileActivationDenialCode.STALE_RECEIPT,
                        )
                    }
                    if (usedReceipts.size >= MAXIMUM_RECEIPT_GENERATIONS ||
                        generation == Long.MAX_VALUE
                    ) {
                        closed = true
                        session = null
                        exhausted = true
                        ClosePlan().also {
                            detachPreparationLocked(it)
                            detachLeaseLocked(it)
                        }
                    } else {
                        usedReceipts[receipt] = Unit
                        generation += 1L
                        val token = UltraProfileSessionToken.create(owner, generation)
                        nextSession = SessionRecord(token, receipt, runtimeIdentity)
                        reserved = Operation(OperationKind.SESSION_SWITCH, nextSession)
                        operation = reserved
                        session = null
                        ClosePlan().also {
                            detachPreparationLocked(it)
                            detachLeaseLocked(it)
                        }
                    }
                }
            }

            val cleanupFailure = executeClosePlan(closePlan)
            interrupted?.let { oldOperation ->
                if (cleanupFailure != null) {
                    poison(cleanupFailure)
                    return UltraProfileSessionStartResult.Denied(
                        UltraProfileActivationDenialCode.REVOCATION_FAILED,
                    )
                }
                val barrierFailure = awaitOperationRetirement(oldOperation)
                if (barrierFailure != null) {
                    // Keep tracking the owner operation. It alone can safely retire its delegate.
                    poison(barrierFailure)
                    return UltraProfileSessionStartResult.Denied(
                        UltraProfileActivationDenialCode.REVOCATION_FAILED,
                    )
                }
                continue
            }

            if (exhausted) {
                if (cleanupFailure != null) poison(cleanupFailure)
                return UltraProfileSessionStartResult.Denied(
                    if (cleanupFailure == null) {
                        UltraProfileActivationDenialCode.CLOSED
                    } else {
                        UltraProfileActivationDenialCode.REVOCATION_FAILED
                    },
                )
            }
            if (cleanupFailure != null) {
                poison(cleanupFailure, reserved)
                return UltraProfileSessionStartResult.Denied(
                    UltraProfileActivationDenialCode.REVOCATION_FAILED,
                )
            }

            val published = synchronized(stateLock) {
                clearOperationLocked(reserved)
                if (!closed && !reserved.cancelled && receipt.isLiveForUltraActivation()) {
                    session = nextSession
                    true
                } else {
                    false
                }
            }
            return if (published) {
                UltraProfileSessionStartResult.Started(nextSession.token)
            } else {
                UltraProfileSessionStartResult.Denied(
                    UltraProfileActivationDenialCode.CLOSED,
                )
            }
        }
    }

    /** Consumes and records a provisional successor inside the MFi handoff callback. */
    fun prepareSessionRenewal(
        token: UltraProfileSessionToken,
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): PreparedUltraProfileSessionRenewal {
        if (predecessor === successor) {
            throw UltraProfileSessionRenewalException(
                UltraProfileActivationDenialCode.INVALID_REQUEST,
            )
        }
        val authenticatedIdentity = successor.consumeForUltraHandoffRenewal(predecessor) {
            onAuthenticationReceiptRevoked(successor)
        } ?: throw UltraProfileSessionRenewalException(
            UltraProfileActivationDenialCode.STALE_RECEIPT,
        )

        val denial = synchronized(stateLock) {
            when {
                closed || revocationFailed || policyRevoked ->
                    UltraProfileActivationDenialCode.CLOSED
                receiptRevocation != null -> UltraProfileActivationDenialCode.BUSY
                operation != null -> UltraProfileActivationDenialCode.BUSY
                renewal != null -> UltraProfileActivationDenialCode.BUSY
                usedReceipts.containsKey(successor) ->
                    UltraProfileActivationDenialCode.STALE_RECEIPT
                usedReceipts.size >= MAXIMUM_RECEIPT_GENERATIONS ->
                    UltraProfileActivationDenialCode.CLOSED
                else -> {
                    val current = currentSessionLocked(token, predecessor)
                    when {
                        current == null -> staleCodeLocked(predecessor)
                        !predecessor.isLiveForUltraActivation() ->
                            UltraProfileActivationDenialCode.STALE_RECEIPT
                        authenticatedIdentity != predecessor.authenticationIdentity ->
                            UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH
                        UltraRuntimeIdentity.from(
                            current.runtimeIdentity.vehicleId,
                            authenticatedIdentity,
                        ) != current.runtimeIdentity ->
                            UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH
                        else -> {
                            usedReceipts[successor] = Unit
                            val handle = PreparedUltraProfileSessionRenewal(
                                coordinator = this,
                                owner = owner,
                                sessionToken = token,
                                predecessor = predecessor,
                                successor = successor,
                            )
                            renewal = RenewalRecord(
                                handle = handle,
                                session = current,
                                predecessor = predecessor,
                                successor = successor,
                            )
                            null
                        }
                    }
                }
            }
        }
        if (denial != null) throw UltraProfileSessionRenewalException(denial)
        return synchronized(stateLock) {
            val prepared = renewal?.handle
            if (prepared == null || prepared.sessionToken !== token ||
                prepared.predecessor !== predecessor || prepared.successor !== successor
            ) {
                throw UltraProfileSessionRenewalException(
                    UltraProfileActivationDenialCode.STALE_GENERATION,
                )
            }
            prepared
        }
    }

    /** Final publication after the outer MFi handoff returned successfully. */
    internal fun commitSessionRenewal(handle: PreparedUltraProfileSessionRenewal) {
        synchronized(stateLock) {
            if (policyRevoked) renewalDenied(UltraProfileActivationDenialCode.CLOSED)
            if (handle.owner !== owner) renewalDenied(UltraProfileActivationDenialCode.STALE_GENERATION)
            if (handle.committed) return
            if (handle.rolledBack) renewalDenied(UltraProfileActivationDenialCode.STALE_GENERATION)
            val current = renewal
            if (current?.handle !== handle || session !== current.session ||
                current.session.token !== handle.sessionToken ||
                current.session.receipt !== current.predecessor
            ) {
                renewalDenied(UltraProfileActivationDenialCode.STALE_GENERATION)
            }
            if (!current.predecessorRevoked || current.predecessor.isLiveForUltraActivation() ||
                !current.successor.isLiveForUltraActivation()
            ) {
                renewalDenied(UltraProfileActivationDenialCode.STALE_RECEIPT)
            }
            current.session.receipt = current.successor
            renewal = null
            handle.committed = true
        }
    }

    /**
     * Rolls back a failed outer handoff. If its predecessor cannot be restored, fail closed and
     * retain every exact retirement owner for lifecycle close retry.
     */
    internal fun rollbackSessionRenewal(handle: PreparedUltraProfileSessionRenewal) {
        if (handle.owner !== owner) renewalDenied(UltraProfileActivationDenialCode.STALE_GENERATION)
        if (handle.committed || handle.rolledBack && !handle.requiresLifecycleClose) return
        if (handle.requiresLifecycleClose) {
            return
        }
        var failClosed = false
        val closePlan = synchronized(stateLock) {
            if (handle.committed || handle.rolledBack && !handle.requiresLifecycleClose) {
                return@synchronized ClosePlan()
            }
            val current = renewal
            if (current == null) {
                handle.rolledBack = true
                return@synchronized ClosePlan()
            }
            if (current.handle !== handle) {
                renewalDenied(UltraProfileActivationDenialCode.STALE_GENERATION)
            }
            val predecessorLive = current.predecessor.isLiveForUltraActivation()
            val successorLive = current.successor.isLiveForUltraActivation()
            if (predecessorLive && successorLive) {
                renewalDenied(UltraProfileActivationDenialCode.BUSY)
            }
            renewal = null
            handle.rolledBack = true
            if (predecessorLive && session === current.session &&
                current.session.receipt === current.predecessor
            ) {
                ClosePlan()
            } else {
                failClosed = true
                handle.requiresLifecycleClose = true
                retirementDeferredUntilLifecycleClose = true
                closed = true
                session = null
                ClosePlan().also {
                    detachPreparationLocked(it)
                    detachLeaseLocked(it)
                }
            }
        }
        if (!failClosed) return
        val failure = executeClosePlan(closePlan)
        if (failure != null) {
            throw UltraProfileActivationRevocationException(failure)
        }
        // executeClosePlan only journals while the lifecycle-close gate is active. The token can
        // now finish rollback, but the lifecycle remains the sole exact owner of deferred roots.
    }

    @Deprecated("Use prepareSessionRenewal and commit the returned token after handoff success")
    fun renewSession(
        token: UltraProfileSessionToken,
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): Nothing = throw UnsupportedOperationException(
        "One-phase authorized Ultra session renewal is unavailable",
    )

    fun prepare(
        token: UltraProfileSessionToken,
        receipt: MfiAuthenticationReceipt,
    ): UltraProfilePrepareResult {
        lateinit var reserved: Operation
        val current = synchronized(stateLock) {
            unavailableForMutationLocked()?.let { return UltraProfilePrepareResult.Denied(it) }
            val selected = currentSessionLocked(token, receipt)
                ?: return UltraProfilePrepareResult.Denied(staleCodeLocked(receipt))
            if (!receipt.isLiveForUltraActivation()) {
                return UltraProfilePrepareResult.Denied(
                    UltraProfileActivationDenialCode.STALE_RECEIPT,
                )
            }
            if (lease != null) {
                return UltraProfilePrepareResult.Denied(
                    UltraProfileActivationDenialCode.ALREADY_ACTIVE,
                )
            }
            if (preparation != null) {
                return UltraProfilePrepareResult.Denied(
                    UltraProfileActivationDenialCode.ALREADY_PREPARED,
                )
            }
            reserved = Operation(OperationKind.PREPARE, selected)
            operation = reserved
            selected
        }

        val request = UltraProfileActivationRequest(
            runtimeIdentity = current.runtimeIdentity,
            authenticationIdentity = receipt.authenticationIdentity,
        )
        val providerResult = external { backend.prepare(request) }
        val providerFailure = providerResult.failure
        val backendResult = providerResult.value
        if (providerFailure != null || backendResult == null ||
            backendResult === UltraProfileBackendPrepareResult.Unavailable
        ) {
            val stale = finishOperationWithoutPublish(reserved)
            return UltraProfilePrepareResult.Denied(
                when {
                    stale -> UltraProfileActivationDenialCode.CLOSED
                    providerFailure != null -> UltraProfileActivationDenialCode.PROVIDER_FAILED
                    else -> UltraProfileActivationDenialCode.PROVIDER_UNAVAILABLE
                },
            )
        }

        val candidate = (backendResult as UltraProfileBackendPrepareResult.Prepared).preparation
        val snapshotResult = external {
            PreparationSnapshot(
                requestIdentity = candidate.requestIdentity,
                authenticationIdentity = candidate.authenticationIdentity,
                profile = candidate.verifiedProfile,
                manifest = candidate.compiledManifest,
            )
        }
        val snapshot = snapshotResult.value
        val expectedProfileMfiIdentity = request.profileMfiCertificateIdentity
        val bindingMatches = snapshotResult.failure == null && snapshot != null &&
            snapshot.requestIdentity == current.runtimeIdentity &&
            snapshot.authenticationIdentity == receipt.authenticationIdentity &&
            snapshot.profile.binding.vehicleId == current.runtimeIdentity.vehicleId &&
            MessageDigest.isEqual(
                snapshot.profile.binding.mfiCertificateIdentity.toByteArray(StandardCharsets.UTF_8),
                expectedProfileMfiIdentity.toByteArray(StandardCharsets.UTF_8),
            )
        val verificationIdentity = if (bindingMatches) {
            external {
                UltraProfileVerificationIdentity.from(
                    runtimeIdentity = current.runtimeIdentity,
                    authenticationIdentity = receipt.authenticationIdentity,
                    profile = requireNotNull(snapshot).profile,
                )
            }
        } else {
            ExternalResult<UltraProfileVerificationIdentity>(null, snapshotResult.failure)
        }
        val verifiedIdentity = verificationIdentity.value
        val manifestMatches = if (bindingMatches && verificationIdentity.failure == null &&
            verifiedIdentity != null
        ) {
            checkNotNull(snapshot).manifest.issuedBy(manifestIssuer) &&
                snapshot.manifest.matchesProfile(
                    request = request,
                    profile = snapshot.profile,
                    expectedVerificationIdentity = verifiedIdentity,
                )
        } else {
            false
        }

        var handle: PreparedUltraProfileActivation? = null
        val published = if (manifestMatches) {
            synchronized(stateLock) {
                val stillCurrent = operation === reserved && !reserved.cancelled && !closed &&
                    session === current && receipt.isLiveForUltraActivation() &&
                    preparation == null && lease == null
                if (stillCurrent) {
                    clearOperationLocked(reserved)
                    handle = PreparedUltraProfileActivation(
                        coordinator = this,
                        owner = owner,
                        sessionToken = current.token,
                        verificationIdentity = checkNotNull(verifiedIdentity),
                        manifestDigest = checkNotNull(snapshot).manifest.manifestDigest,
                    )
                    preparation = PreparationRecord(
                        requireNotNull(handle),
                        candidate,
                        current,
                        snapshot.manifest,
                    )
                    true
                } else {
                    false
                }
            }
        } else {
            false
        }

        if (published) return UltraProfilePrepareResult.Prepared(requireNotNull(handle))

        val cleanupFailure = closeOne(candidate)
        if (cleanupFailure != null) {
            poison(cleanupFailure, reserved)
            return UltraProfilePrepareResult.Denied(
                UltraProfileActivationDenialCode.REVOCATION_FAILED,
            )
        }
        val stale = finishOperationWithoutPublish(reserved)
        return UltraProfilePrepareResult.Denied(
            when {
                stale -> UltraProfileActivationDenialCode.CLOSED
                snapshotResult.failure != null || verificationIdentity.failure != null ->
                    UltraProfileActivationDenialCode.PROVIDER_FAILED
                bindingMatches && !manifestMatches ->
                    UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH
                else -> UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH
            },
        )
    }

    fun commit(
        prepared: PreparedUltraProfileActivation,
        receipt: MfiAuthenticationReceipt,
    ): UltraProfileCommitResult {
        lateinit var reserved: Operation
        val record = synchronized(stateLock) {
            unavailableForMutationLocked()?.let { return UltraProfileCommitResult.Denied(it) }
            val selected = preparation
            if (prepared.owner !== owner || selected?.handle !== prepared || prepared.revoked) {
                return UltraProfileCommitResult.Denied(
                    UltraProfileActivationDenialCode.STALE_GENERATION,
                )
            }
            if (selected.session.receipt !== receipt || session !== selected.session) {
                return UltraProfileCommitResult.Denied(staleCodeLocked(receipt))
            }
            if (!receipt.isLiveForUltraActivation()) {
                return UltraProfileCommitResult.Denied(
                    UltraProfileActivationDenialCode.STALE_RECEIPT,
                )
            }
            preparation = null
            prepared.revoked = true
            reserved = Operation(
                kind = OperationKind.COMMIT,
                session = selected.session,
                preparation = selected,
            )
            operation = reserved
            selected
        }

        val committed = external { record.delegate.commit(prepared.verificationIdentity) }
        val candidate = committed.value
        // commit transfers ownership first; only the post-preparation-close state is publishable.
        val preparationCloseFailure = closeOne(record.delegate)
        val candidateSnapshot = if (candidate != null) {
            external {
                LeaseSnapshot(
                    verificationIdentity = candidate.verificationIdentity,
                    manifest = candidate.compiledManifest,
                    isLive = candidate.isLive,
                )
            }
        } else {
            ExternalResult<LeaseSnapshot>(null, committed.failure)
        }
        val snapshot = candidateSnapshot.value
        val candidateValid = candidate != null && candidateSnapshot.failure == null &&
            snapshot != null && snapshot.verificationIdentity == prepared.verificationIdentity &&
            snapshot.manifest === record.manifest &&
            snapshot.manifest.manifestDigest == prepared.manifestDigest &&
            snapshot.isLive && preparationCloseFailure == null

        var active: AuthorizedUltraProfileLease? = null
        val published = if (candidateValid) {
            synchronized(stateLock) {
                val stillCurrent = operation === reserved && !reserved.cancelled && !closed &&
                    session === record.session && receipt.isLiveForUltraActivation() &&
                    lease == null
                if (stillCurrent) {
                    clearOperationLocked(reserved)
                    active = AuthorizedUltraProfileLease(
                        coordinator = this,
                        owner = owner,
                        sessionToken = record.session.token,
                        verificationIdentity = prepared.verificationIdentity,
                        manifestDigest = prepared.manifestDigest,
                    )
                    lease = LeaseRecord(
                        requireNotNull(active),
                        requireNotNull(candidate),
                        record.session,
                        record.manifest,
                    )
                    true
                } else {
                    false
                }
            }
        } else {
            false
        }

        if (published) return UltraProfileCommitResult.Activated(requireNotNull(active))

        val candidateCloseFailure = candidate?.let(::closeOne)
        val cleanupFailure = appendFailure(preparationCloseFailure, candidateCloseFailure)
        if (cleanupFailure != null) {
            poison(cleanupFailure, reserved)
            return UltraProfileCommitResult.Denied(
                UltraProfileActivationDenialCode.REVOCATION_FAILED,
            )
        }
        val stale = finishOperationWithoutPublish(reserved)
        return UltraProfileCommitResult.Denied(
            when {
                stale -> UltraProfileActivationDenialCode.CLOSED
                committed.failure != null || candidateSnapshot.failure != null ->
                    UltraProfileActivationDenialCode.COMMIT_FAILED
                snapshot?.verificationIdentity != prepared.verificationIdentity ->
                    UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH
                snapshot?.manifest !== record.manifest ->
                    UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH
                candidate == null -> UltraProfileActivationDenialCode.COMMIT_FAILED
                else -> UltraProfileActivationDenialCode.LEASE_NOT_LIVE
            },
        )
    }

    fun endSession(
        token: UltraProfileSessionToken,
        receipt: MfiAuthenticationReceipt,
    ): UltraProfileActivationDenialCode? {
        var interruptedOperation: Operation? = null
        val interruptionPlan = synchronized(stateLock) {
            if (closed || revocationFailed) return UltraProfileActivationDenialCode.CLOSED
            currentSessionLocked(token, receipt) ?: return staleCodeLocked(receipt)
            operation?.let { active ->
                active.cancelled = true
                interruptedOperation = active
                session = null
                ClosePlan().also {
                    detachPreparationLocked(it)
                    detachLeaseLocked(it)
                }
            } ?: ClosePlan()
        }
        interruptedOperation?.let { interrupted ->
            val cleanupFailure = executeClosePlan(interruptionPlan)
            if (cleanupFailure != null) {
                poison(cleanupFailure)
                return UltraProfileActivationDenialCode.REVOCATION_FAILED
            }
            if (interrupted.ownerThread === Thread.currentThread()) {
                return UltraProfileActivationDenialCode.BUSY
            }
            val barrierFailure = awaitOperationRetirement(interrupted)
            if (barrierFailure != null) {
                poison(barrierFailure)
                return UltraProfileActivationDenialCode.REVOCATION_FAILED
            }
            return null
        }

        lateinit var reserved: Operation
        val closePlan = synchronized(stateLock) {
            unavailableForMutationLocked()?.let { return it }
            val current = currentSessionLocked(token, receipt) ?: return staleCodeLocked(receipt)
            reserved = Operation(OperationKind.SESSION_SWITCH, current)
            operation = reserved
            session = null
            ClosePlan().also {
                detachPreparationLocked(it)
                detachLeaseLocked(it)
            }
        }
        val failure = executeClosePlan(closePlan)
        if (failure != null) {
            poison(failure, reserved)
            return UltraProfileActivationDenialCode.REVOCATION_FAILED
        }
        return if (finishOperationWithoutPublish(reserved)) {
            UltraProfileActivationDenialCode.CLOSED
        } else {
            null
        }
    }

    internal fun cancelPreparation(handle: PreparedUltraProfileActivation) {
        var delegate: AuthorizedUltraProfilePreparation? = null
        var reserved: Operation? = null
        var ownedOperation: Operation? = null
        synchronized(stateLock) {
            if (handle.owner !== owner || handle.revoked) return
            val currentOperation = operation
            if (currentOperation?.preparation?.handle === handle) {
                handle.revoked = true
                currentOperation.cancelled = true
                ownedOperation = currentOperation
            } else {
                if (currentOperation != null) return
                val record = preparation
                if (record?.handle !== handle) return
                preparation = null
                handle.revoked = true
                delegate = record.delegate
                reserved = Operation(
                    OperationKind.SESSION_SWITCH,
                    record.session,
                    preparation = record,
                ).also { operation = it }
            }
        }
        ownedOperation?.let { active ->
            if (active.ownerThread === Thread.currentThread()) return
            awaitOperationRetirement(active)?.let { failure ->
                poison(failure)
                throw UltraProfileActivationRevocationException(failure)
            }
            return
        }
        closeOne(delegate)?.let { failure ->
            poison(failure, reserved)
            throw UltraProfileActivationRevocationException(failure)
        }
        requireNotNull(reserved).let(::finishOperationWithoutPublish)
    }

    internal fun releaseLease(handle: AuthorizedUltraProfileLease) {
        var reserved: Operation? = null
        var ownedOperation: Operation? = null
        val closePlan = synchronized(stateLock) {
            if (handle.owner !== owner || handle.revoked) return
            val currentOperation = operation
            val record = lease
            if (currentOperation?.lease?.handle === handle) {
                currentOperation.cancelled = true
                if (record?.handle === handle) {
                    cancelRenewalLocked()
                    lease = null
                }
                handle.revoked = true
                record?.children?.keys?.forEach { it.revoked = true }
                ownedOperation = currentOperation
                ClosePlan()
            } else {
                if (currentOperation != null) return
                if (record?.handle !== handle) return
                cancelRenewalLocked()
                lease = null
                reserved = Operation(
                    OperationKind.SESSION_SWITCH,
                    record.session,
                    lease = record,
                ).also { operation = it }
                ClosePlan().also { appendLeaseLocked(record, it) }
            }
        }
        ownedOperation?.let { active ->
            if (active.ownerThread === Thread.currentThread()) return
            awaitOperationRetirement(active)?.let { failure ->
                poison(failure)
                throw UltraProfileActivationRevocationException(failure)
            }
            synchronized(stateLock) { pendingRetirementFailure }?.let { failure ->
                throw UltraProfileActivationRevocationException(failure)
            }
            return
        }
        executeClosePlan(closePlan)?.let { failure ->
            poison(failure, reserved)
            throw UltraProfileActivationRevocationException(failure)
        }
        requireNotNull(reserved).let(::finishOperationWithoutPublish)
    }

    internal fun releaseProvisioning(handle: AuthorizedUltraProfileProvisioning) {
        var childRecord: ProvisioningRecord? = null
        var reserved: Operation? = null
        var parentRecord: LeaseRecord? = null
        var ownedOperation: Operation? = null
        synchronized(stateLock) {
            if (handle.owner !== owner || handle.revoked) return
            val record = lease ?: return
            if (handle.parent !== record.handle) return
            val currentOperation = operation
            if (currentOperation?.lease === record) {
                currentOperation.cancelled = true
                cancelRenewalLocked()
                lease = null
                record.handle.revoked = true
                record.children.keys.forEach { it.revoked = true }
                ownedOperation = currentOperation
            } else {
                if (currentOperation != null) return
                childRecord = record.children.remove(handle) ?: return
                handle.revoked = true
                parentRecord = record
                reserved = Operation(
                    OperationKind.SESSION_SWITCH,
                    record.session,
                    lease = record,
                ).also { operation = it }
            }
        }
        ownedOperation?.let { active ->
            if (active.ownerThread === Thread.currentThread()) return
            awaitOperationRetirement(active)?.let { failure ->
                poison(failure)
                throw UltraProfileActivationRevocationException(failure)
            }
            synchronized(stateLock) { pendingRetirementFailure }?.let { failure ->
                throw UltraProfileActivationRevocationException(failure)
            }
            return
        }
        val childClosePlan = ClosePlan().also { plan ->
            childRecord?.let { child ->
                plan.instrumentSessions += child.instrumentSessions.keys
                child.instrumentSessions.clear()
                plan.runtimes += child.runtime
                plan.provisionings += child.delegate
            }
        }
        executeClosePlan(childClosePlan)?.let { failure ->
            poison(failure, reserved)
            throw UltraProfileActivationRevocationException(failure)
        }
        val stale = requireNotNull(reserved).let(::finishOperationWithoutPublish)
        if (stale) {
            val plan = ClosePlan().also { appendLease(requireNotNull(parentRecord), it) }
            executeClosePlan(plan)?.let { failure ->
                poison(failure)
                throw UltraProfileActivationRevocationException(failure)
            }
        }
    }

    internal fun isLeaseLive(handle: AuthorizedUltraProfileLease): Boolean {
        lateinit var reserved: Operation
        val record = synchronized(stateLock) {
            awaitForeignLivenessProbeLocked()
            if (closed || policyRevoked || operation != null || handle.revoked ||
                handle.owner !== owner) return false
            val selected = lease
            if (selected?.handle !== handle || session !== selected.session) return false
            if (!sessionReceiptIsLiveLocked(selected.session)) return false
            reserved = Operation(OperationKind.PROBE_LEASE, selected.session, lease = selected)
            operation = reserved
            selected
        }
        val live = external { record.delegate.isLive }
        val keep = synchronized(stateLock) {
            val current = operation === reserved && !reserved.cancelled && !closed &&
                !policyRevoked &&
                lease === record && session === record.session && live.failure == null &&
                live.value == true && sessionReceiptIsLiveLocked(record.session)
            if (current) clearOperationLocked(reserved)
            if (!current && lease === record) {
                cancelRenewalLocked()
                lease = null
            }
            if (!current) {
                record.handle.revoked = true
                record.children.keys.forEach { it.revoked = true }
            }
            current
        }
        if (keep) return true
        val failure = executeClosePlan(ClosePlan().also { appendLease(record, it) })
        if (failure != null) poison(failure, reserved) else finishOperationWithoutPublish(reserved)
        return false
    }

    internal fun isProvisioningLive(handle: AuthorizedUltraProfileProvisioning): Boolean {
        // An exact same-thread scene-use reservation already keeps this child and its parent
        // alive through the queue handoff. Do not start a second probe which can report false
        // merely because an unrelated coordinator operation briefly holds the reservation.
        val withinSceneUse = synchronized(stateLock) {
            sceneSubmissionChildOwners[Thread.currentThread()]?.containsKey(handle) == true
        }
        if (withinSceneUse) {
            val owned = synchronized(stateLock) {
                val current = lease
                val child = current?.children?.get(handle)
                if (closed || revocationFailed || policyRevoked || receiptRevocation != null ||
                    handle.revoked || handle.owner !== owner || current == null ||
                    current.handle.revoked || handle.parent !== current.handle ||
                    session !== current.session || !sessionReceiptIsLiveLocked(current.session) ||
                    child == null
                ) null else current to child
            } ?: return false
            return external {
                owned.first.delegate.isLive && owned.second.delegate.isLive &&
                    owned.second.runtime.isLive()
            }.let { it.failure == null && it.value == true }
        }
        lateinit var reserved: Operation
        lateinit var child: ProvisioningRecord
        val record = synchronized(stateLock) {
            awaitForeignLivenessProbeLocked()
            if (closed || policyRevoked || operation != null || handle.revoked ||
                handle.owner !== owner) return false
            val selected = lease ?: return false
            if (handle.parent !== selected.handle || session !== selected.session) return false
            if (!sessionReceiptIsLiveLocked(selected.session)) return false
            child = selected.children[handle] ?: return false
            reserved = Operation(
                OperationKind.PROBE_PROVISIONING,
                selected.session,
                lease = selected,
            )
            operation = reserved
            selected
        }
        val live = external {
            record.delegate.isLive && child.delegate.isLive && child.runtime.isLive()
        }
        val keep = synchronized(stateLock) {
            val current = operation === reserved && !reserved.cancelled && !closed &&
                !policyRevoked &&
                lease === record && record.children[handle] === child &&
                session === record.session && live.failure == null && live.value == true &&
                sessionReceiptIsLiveLocked(record.session)
            if (current) clearOperationLocked(reserved)
            if (!current && lease === record) {
                cancelRenewalLocked()
                lease = null
            }
            if (!current) {
                record.handle.revoked = true
                record.children.keys.forEach { it.revoked = true }
            }
            current
        }
        if (keep) return true
        val failure = executeClosePlan(ClosePlan().also { appendLease(record, it) })
        if (failure != null) poison(failure, reserved) else finishOperationWithoutPublish(reserved)
        return false
    }

    /**
     * Probe only the currently sealed UI Sync child and its issued signed Profile. Unlike a
     * liveness admission, this must not retire/close any owner on the iAP2 control callback.
     */
    internal fun allowsClusterAssetAcquisition(
        handle: AuthorizedUltraProfileProvisioning,
        assetId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ): Boolean {
        if (assetId.isBlank()) return false
        val selected = synchronized(stateLock) {
            val current = lease ?: return false
            val child = current.children[handle] ?: return false
            val currentReceipt = current.session.receipt
            if (closed || revocationFailed || policyRevoked || receiptRevocation != null ||
                operation != null || handle.owner !== owner || handle.revoked ||
                current.handle.revoked || handle.parent !== current.handle ||
                session !== current.session || !sessionReceiptIsLiveLocked(current.session) ||
                currentReceipt.authenticationIdentity != authenticationIdentity ||
                !handle.physicalOutputSealed || child.uiSyncProvisioning == null ||
                current.manifest.uiSync == null || !current.manifest.issuedBy(manifestIssuer) ||
                !current.manifest.matchesClusterAssetAcquisition(assetId, authenticationIdentity)
            ) return false
            Triple(current, child, currentReceipt)
        }
        val (current, child, receipt) = selected
        val runtimeLive = try {
            current.delegate.isLive && child.delegate.isLive && child.runtime.isLive()
        } catch (_: Exception) {
            false
        }
        if (!runtimeLive) return false
        return synchronized(stateLock) {
            !closed && !revocationFailed && !policyRevoked && receiptRevocation == null &&
                operation == null && !handle.revoked && !current.handle.revoked &&
                handle.physicalOutputSealed && lease === current &&
                session === current.session && current.session.receipt === receipt &&
                current.children[handle] === child && child.uiSyncProvisioning != null &&
                sessionReceiptIsLiveLocked(current.session) &&
                receipt.authenticationIdentity == authenticationIdentity
        }
    }

    internal fun <T> withSceneSubmissionUse(
        handle: AuthorizedUltraProfileProvisioning,
        block: () -> T,
    ): T = when (val attempt = tryWithSceneSubmissionUse(handle, block)) {
        is UltraProfileSceneUseAttempt.Admitted -> attempt.value
        UltraProfileSceneUseAttempt.Busy -> throw IllegalStateException(
            "Authorized Ultra scene submission coordinator is temporarily busy",
        )
        UltraProfileSceneUseAttempt.Revoked -> throw IllegalStateException(
            "Authorized Ultra scene submission lease is no longer live",
        )
    }

    internal fun <T> tryWithSceneSubmissionUse(
        handle: AuthorizedUltraProfileProvisioning,
        block: () -> T,
    ): UltraProfileSceneUseAttempt<T> {
        val submittingThread = Thread.currentThread()
        val admission = synchronized(stateLock) {
            val record = lease
            if (closed || revocationFailed || policyRevoked || receiptRevocation != null ||
                handle.owner !== owner || handle.revoked || record == null ||
                record.handle.revoked || handle.parent !== record.handle ||
                !record.children.containsKey(handle) || session !== record.session ||
                !sessionReceiptIsLiveLocked(record.session)
            ) return@synchronized UltraProfileSceneUseAttempt.Revoked
            val ownedChildren = sceneSubmissionChildOwners[submittingThread]
            if (operation != null && ownedChildren?.containsKey(handle) != true) {
                return@synchronized UltraProfileSceneUseAttempt.Busy
            }
            activeSceneSubmissionUses += 1
            sceneSubmissionOwners[submittingThread] =
                (sceneSubmissionOwners[submittingThread] ?: 0) + 1
            val children = ownedChildren ?: IdentityHashMap<AuthorizedUltraProfileProvisioning,
                Int>().also { sceneSubmissionChildOwners[submittingThread] = it }
            children[handle] = (children[handle] ?: 0) + 1
            null
        }
        if (admission != null) return admission
        try {
            return UltraProfileSceneUseAttempt.Admitted(block())
        } finally {
            synchronized(stateLock) {
                check(activeSceneSubmissionUses > 0)
                activeSceneSubmissionUses -= 1
                val owned = requireNotNull(sceneSubmissionOwners[submittingThread])
                if (owned == 1) sceneSubmissionOwners.remove(submittingThread)
                else sceneSubmissionOwners[submittingThread] = owned - 1
                val children = requireNotNull(sceneSubmissionChildOwners[submittingThread])
                val count = requireNotNull(children[handle])
                if (count == 1) children.remove(handle) else children[handle] = count - 1
                if (children.isEmpty()) sceneSubmissionChildOwners.remove(submittingThread)
                stateLock.notifyAll()
            }
        }
    }

    /** A signed, local data source is never opened from a CAF channel or a synthetic backend. */
    internal fun openInstrumentSignalSession(
        handle: AuthorizedUltraProfileProvisioning,
        profile: VerifiedUltraProfile,
        assignment: CompiledInstrumentSignalDisplayAssignment,
        physicalBatch: UiSyncPhysicalOutputBatch,
        listener: InstrumentSignalBundleListener,
    ): AuthorizedInstrumentSignalSession = when (val admitted = tryWithSceneSubmissionUse(handle) {
        val selected = synchronized(stateLock) {
            val current = lease ?: return@synchronized null
            val child = current.children[handle] ?: return@synchronized null
            if (closed || handle.revoked || current.handle.revoked ||
                session !== current.session
            ) null else child to current.manifest
        } ?: error("Authorized instrument child is not current")
        val (child, manifest) = selected
        val source = checkNotNull(child.instrumentReadSource) {
            "This CAF backend has no real read-only instrument source"
        }
        require(manifest.instrumentAssignmentsFor(profile).any { it === assignment }) {
            "Instrument assignment is not from this exact signed Profile"
        }
        check(instrumentSourceCurrent(handle, source, physicalBatch, profile)) {
            "Instrument source, AA05 child, or physical batch is not current"
        }
        val packageBytes = requireNotNull(profile.candidate.content(
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
        )) { "Verified Profile has no phone package" }
        val display = requireNotNull(
            CarPlayThemeSchema.parseArcPackageJson(packageBytes).displays
                .getOrNull(assignment.profileDisplayIndex),
        ) { "Signed instrument Profile display is absent" }
        require(display.id == assignment.profileDisplayId) {
            "Signed instrument Profile display changed"
        }
        val opened = AuthorizedInstrumentSignalSession.open(
            assignment.spec, display, source,
            { instrumentSourceCurrent(handle, source, physicalBatch, profile) },
            { retired -> synchronized(stateLock) {
                lease?.children?.get(handle)?.instrumentSessions?.remove(retired)
            } },
        )
        val retained = synchronized(stateLock) {
            val current = lease
            if (closed || handle.revoked || current == null || current.handle.revoked ||
                session !== current.session || current.children[handle] !== child
            ) false else {
                child.instrumentSessions[opened] = Unit
                true
            }
        }
        if (!retained) {
            opened.close()
            error("Authorized instrument child was retired during open")
        }
        try {
            opened.start(listener)
            check(opened.isLive) { "Instrument source retired during subscription" }
            opened
        } catch (failure: Throwable) {
            try {
                opened.close()
            } catch (releaseFailure: Throwable) {
                if (releaseFailure !== failure) failure.addSuppressed(releaseFailure)
            }
            throw failure
        }
    }) {
        is UltraProfileSceneUseAttempt.Admitted -> admitted.value
        UltraProfileSceneUseAttempt.Busy -> error("Authorized instrument coordinator is busy")
        UltraProfileSceneUseAttempt.Revoked -> error("Authorized instrument child was revoked")
    }

    private fun instrumentSourceCurrent(
        handle: AuthorizedUltraProfileProvisioning,
        source: VehicleStateReadSource,
        batch: UiSyncPhysicalOutputBatch,
        profile: VerifiedUltraProfile,
    ): Boolean {
        val child = synchronized(stateLock) {
            val current = lease
            if (closed || revocationFailed || receiptRevocation != null ||
                handle.owner !== owner || handle.revoked || current == null ||
                current.handle.revoked || handle.parent !== current.handle ||
                session !== current.session || !sessionReceiptIsLiveLocked(current.session)
            ) null else current.children[handle]?.takeIf {
                it.instrumentReadSource === source
            }
        } ?: return false
        val allowed = runCatching {
            source.isCurrent && child.delegate.isLive && child.runtime.isLive() &&
                child.physicalOutputSealer?.authorizesInstrumentSource(
                    batch, handle, profile,
                ) == true && source.isCurrent
        }.getOrDefault(false)
        if (!allowed) return false
        return synchronized(stateLock) {
            val current = lease
            !closed && !revocationFailed && receiptRevocation == null &&
                !handle.revoked && current != null && !current.handle.revoked &&
                session === current.session && current.children[handle] === child &&
                sessionReceiptIsLiveLocked(current.session)
        }
    }

    /** Never waits through an operation owned by the calling thread. */
    internal fun awaitSceneOperationChange(timeoutMillis: Long) {
        require(timeoutMillis in 1L..250L)
        synchronized(stateLock) {
            val observed = operation ?: return
            check(observed.ownerThread !== Thread.currentThread()) {
                "Cannot wait for an Ultra scene operation owned by the calling thread"
            }
            try {
                stateLock.wait(timeoutMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted awaiting Ultra scene admission", interrupted)
            }
        }
    }

    internal fun openProvisioning(
        handle: AuthorizedUltraProfileLease,
    ): UltraProfileProvisioningOpenResult {
        lateinit var reserved: Operation
        val record = synchronized(stateLock) {
            unavailableForMutationLocked()?.let {
                return UltraProfileProvisioningOpenResult.Denied(it)
            }
            val selected = lease
            if (handle.owner !== owner || selected?.handle !== handle || handle.revoked ||
                session !== selected.session
            ) {
                return UltraProfileProvisioningOpenResult.Denied(
                    UltraProfileActivationDenialCode.STALE_GENERATION,
                )
            }
            if (!selected.session.receipt.isLiveForUltraActivation()) {
                return UltraProfileProvisioningOpenResult.Denied(
                    UltraProfileActivationDenialCode.STALE_RECEIPT,
                )
            }
            reserved = Operation(
                OperationKind.OPEN_PROVISIONING,
                selected.session,
                lease = selected,
            )
            operation = reserved
            selected
        }

        val opened = external { record.delegate.openProvisioning() }
        val candidate = opened.value
        val snapshotResult = if (candidate != null) {
            external {
                val compiledManifest = candidate.compiledManifest
                val uiSyncProvisioning = candidate.uiSyncProvisioning
                val cafProvisioning = candidate.cafProvisioning
                val instrumentReadSource = candidate.instrumentReadSource
                val physicalSealer = candidate.physicalOutputSealer
                val physicalAuthority = physicalSealer?.authority
                ProvisioningSnapshot(
                    verificationIdentity = candidate.verificationIdentity,
                    manifest = compiledManifest,
                    parentIsLive = record.delegate.isLive,
                    isLive = candidate.isLive,
                    uiSync = uiSyncProvisioning,
                    caf = cafProvisioning,
                    instrumentReadSource = instrumentReadSource,
                    physicalOutputSealer = physicalSealer,
                    physicalOutputAuthority = physicalAuthority,
                    manifestMatchesProvisioning = compiledManifest.matchesProvisioning(
                        uiSyncProvisioning?.authorizationSnapshot(),
                        cafProvisioning?.authorizationSnapshot(),
                    ),
                )
            }
        } else {
            ExternalResult<ProvisioningSnapshot>(null, opened.failure)
        }
        val snapshot = snapshotResult.value
        val valid = candidate != null && snapshotResult.failure == null && snapshot != null &&
            snapshot.verificationIdentity == handle.verificationIdentity && snapshot.parentIsLive &&
            snapshot.isLive && snapshot.manifest === record.manifest &&
            snapshot.manifest.manifestDigest == handle.manifestDigest &&
            snapshot.manifestMatchesProvisioning &&
            (snapshot.instrumentReadSource == null ||
                (snapshot.caf != null && snapshot.manifest.instrumentCrosswalk != null)) &&
            (snapshot.uiSync != null || snapshot.caf != null)

        val runtimeResult = if (valid) {
            external {
                AirPlayUltraRuntime(
                    cafProvisioning = requireNotNull(snapshot).caf,
                    uiSyncProvisioning = snapshot.uiSync,
                )
            }
        } else {
            ExternalResult<AirPlayUltraRuntime>(null, null)
        }
        val runtime = runtimeResult.value
        val runtimeValidation = if (runtime != null) {
            external {
                runtime.isLive() && requireNotNull(candidate).isLive &&
                    record.delegate.isLive && candidate.compiledManifest === record.manifest
            }
        } else {
            ExternalResult<Boolean>(null, runtimeResult.failure)
        }

        var child: AuthorizedUltraProfileProvisioning? = null
        val published = if (valid && runtimeResult.failure == null && runtime != null &&
            runtimeValidation.failure == null && runtimeValidation.value == true
        ) {
            synchronized(stateLock) {
                val stillCurrent = operation === reserved && !reserved.cancelled && !closed &&
                    !policyRevoked &&
                    lease === record && session === record.session && !record.handle.revoked &&
                    record.session.receipt.isLiveForUltraActivation()
                if (stillCurrent) {
                    clearOperationLocked(reserved)
                    child = AuthorizedUltraProfileProvisioning(
                        coordinator = this,
                        owner = owner,
                        parent = handle,
                        verificationIdentity = handle.verificationIdentity,
                        manifestDigest = handle.manifestDigest,
                        compiledManifest = record.manifest,
                        runtimeOwner = runtime,
                        authorizedFeatures = record.manifest.authorizedFeatures,
                    )
                    record.children[requireNotNull(child)] = ProvisioningRecord(
                        handle = requireNotNull(child),
                        delegate = requireNotNull(candidate),
                        runtime = runtime,
                        uiSyncProvisioning = snapshot.uiSync,
                        physicalOutputSealer = snapshot.physicalOutputSealer,
                        physicalOutputAuthority = snapshot.physicalOutputAuthority,
                        instrumentReadSource = snapshot.instrumentReadSource,
                    )
                    snapshot.physicalOutputAuthority?.let {
                        policyCoverAuthorities[it] = Unit
                    }
                    true
                } else {
                    false
                }
            }
        } else {
            synchronized(stateLock) {
                if (lease === record) {
                    cancelRenewalLocked()
                    lease = null
                }
                record.handle.revoked = true
                record.children.keys.forEach { it.revoked = true }
            }
            false
        }

        if (published) {
            return UltraProfileProvisioningOpenResult.Opened(requireNotNull(child))
        }

        val closePlan = ClosePlan()
        runtime?.let { closePlan.runtimes += it }
        (runtimeResult.failure as? AirPlayUltraRuntimeConstructionException)?.let {
            closePlan.runtimes += it
        }
        candidate?.let { closePlan.provisionings += it }
        appendLease(record, closePlan)
        val cleanupFailure = executeClosePlan(closePlan)
        if (cleanupFailure != null) {
            poison(cleanupFailure, reserved)
            return UltraProfileProvisioningOpenResult.Denied(
                UltraProfileActivationDenialCode.REVOCATION_FAILED,
            )
        }
        val stale = finishOperationWithoutPublish(reserved)
        return UltraProfileProvisioningOpenResult.Denied(
            when {
                stale -> UltraProfileActivationDenialCode.CLOSED
                opened.failure != null || snapshotResult.failure != null ->
                    UltraProfileActivationDenialCode.PROVIDER_FAILED
                snapshot?.verificationIdentity != handle.verificationIdentity ->
                    UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH
                snapshot?.manifest !== record.manifest ||
                    snapshot?.manifestMatchesProvisioning != true ->
                    UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH
                snapshot?.parentIsLive != true || snapshot?.isLive != true ->
                    UltraProfileActivationDenialCode.LEASE_NOT_LIVE
                snapshot.uiSync == null && snapshot.caf == null ->
                    UltraProfileActivationDenialCode.NO_PROVISIONING
                runtimeResult.failure != null ->
                    UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED
                runtimeValidation.failure != null ->
                    UltraProfileActivationDenialCode.PROVIDER_FAILED
                runtimeValidation.value != true ->
                    UltraProfileActivationDenialCode.LEASE_NOT_LIVE
                else -> UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED
            },
        )
    }

    /**
     * A product UI Sync child is sealed only after OPEN_PROVISIONING has published and cleared
     * its reservation. This new reservation keeps close/revocation from overlooking an in-flight
     * external seal; failure retires the exact child/runtime/parent tree before returning.
     */
    internal fun sealPhysicalOutputAuthority(
        handle: AuthorizedUltraProfileProvisioning,
    ): UltraProfileActivationDenialCode? {
        lateinit var reserved: Operation
        lateinit var child: ProvisioningRecord
        lateinit var proof: UltraProfilePhysicalGenerationProof
        val record = synchronized(stateLock) {
            unavailableForMutationLocked()?.let { return it }
            val selected = lease ?: return UltraProfileActivationDenialCode.STALE_GENERATION
            if (handle.owner !== owner || handle.revoked ||
                handle.parent !== selected.handle || selected.handle.revoked ||
                session !== selected.session
            ) return UltraProfileActivationDenialCode.STALE_GENERATION
            child = selected.children[handle]
                ?: return UltraProfileActivationDenialCode.STALE_GENERATION
            if (selected.manifest.uiSync == null) return null // CAF-only has no physical seal.
            if (handle.physicalOutputSealed) {
                return UltraProfileActivationDenialCode.ALREADY_ACTIVE
            }
            proof = currentPhysicalGenerationProof(selected.session.token)
                ?: return UltraProfileActivationDenialCode.LEASE_NOT_LIVE
            if (!proof.matchesToken(selected.session.token) ||
                !selected.manifest.matchesGenerationIdentity(
                    proof.runtimeIdentity, proof.authenticationIdentity,
                )
            ) return UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH
            reserved = Operation(OperationKind.SEAL_PHYSICAL, selected.session,
                lease = selected)
            operation = reserved
            selected
        }

        val token = record.session.token
        val sealing = external {
            check(record.manifest.issuedBy(manifestIssuer)) {
                "Physical output manifest issuer changed"
            }
            check(record.manifest.uiSync?.physicalOutputRequirement != null) {
                "A product UI Sync runtime requires signed physical destinations"
            }
            val sealer = checkNotNull(child.physicalOutputSealer) {
                "The candidate did not own a physical output sealer"
            }
            val authority = checkNotNull(child.physicalOutputAuthority)
            check(sealer.authority === authority &&
                child.uiSyncProvisioning?.usesPhysicalOutputAuthorityExactly(authority) == true &&
                child.delegate.uiSyncProvisioning === child.uiSyncProvisioning
            ) { "The sealed authority differs from the preflight authority" }
            sealer.seal(record.manifest, manifestIssuer, token, proof, handle)
        }
        val postSealLive = if (sealing.failure == null) external {
            record.delegate.isLive && child.delegate.isLive && child.runtime.isLive() &&
                child.physicalOutputSealer?.authority === child.physicalOutputAuthority &&
                child.physicalOutputAuthority?.let { authority ->
                    child.uiSyncProvisioning?.usesPhysicalOutputAuthorityExactly(authority) == true &&
                        child.delegate.uiSyncProvisioning === child.uiSyncProvisioning
                } == true
        } else ExternalResult<Boolean>(null, sealing.failure)

        val accepted = synchronized(stateLock) {
            val freshProof = currentPhysicalGenerationProof(token)
            val current = operation === reserved && !reserved.cancelled && !closed &&
                !revocationFailed && lease === record && session === record.session &&
                record.children[handle] === child && !handle.revoked &&
                !record.handle.revoked && postSealLive.failure == null &&
                postSealLive.value == true && freshProof != null &&
                freshProof.sameLineage(proof) &&
                record.manifest.matchesGenerationIdentity(
                    freshProof.runtimeIdentity, freshProof.authenticationIdentity,
                )
            if (current) handle.physicalOutputSealed = true
            else if (lease === record) {
                cancelRenewalLocked()
                lease = null
            }
            if (current) clearOperationLocked(reserved)
            current
        }
        if (accepted) return null

        val cleanup = ClosePlan().also { appendLease(record, it) }
        val cleanupFailure = executeClosePlan(cleanup)
        if (cleanupFailure != null) {
            poison(cleanupFailure, reserved)
            return UltraProfileActivationDenialCode.REVOCATION_FAILED
        }
        finishOperationWithoutPublish(reserved)
        return if (sealing.failure != null || postSealLive.failure != null) {
            UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED
        } else {
            UltraProfileActivationDenialCode.LEASE_NOT_LIVE
        }
    }

    override fun close() {
        lateinit var observedOperation: Operation
        var hasObservedOperation = false
        var observedRevocation: ReceiptRevocation? = null
        val closePlan = synchronized(stateLock) {
            operation?.let {
                observedOperation = it
                hasObservedOperation = true
            }
            observedRevocation = receiptRevocation
            ClosePlan().also {
                it.append(pendingRetirement)
                pendingRetirement = ClosePlan()
                pendingRetirementFailure = null
                retirementDeferredUntilLifecycleClose = false
                usedReceipts.clear()
                if (!closed) {
                    closed = true
                    session = null
                    operation?.cancelled = true
                    detachPreparationLocked(it)
                    detachLeaseLocked(it)
                } else if (it.isEmpty && !hasObservedOperation && observedRevocation == null &&
                    proofBinding == null
                ) {
                    return
                }
            }
        }
        var failure = executeClosePlan(closePlan)
        if (hasObservedOperation && observedOperation.ownerThread !== Thread.currentThread()) {
            failure = appendFailure(failure, awaitOperationRetirement(observedOperation))
        }
        observedRevocation?.let {
            failure = appendFailure(
                failure,
                if (it.ownerThread === Thread.currentThread()) {
                    IllegalStateException(
                        "Authorized Ultra receipt revocation cannot retire during its own callback",
                    )
                } else {
                    awaitReceiptRevocationRetirement(it)
                },
            )
        }
        val deferredFailure = synchronized(stateLock) { pendingRetirementFailure }
        failure = appendFailure(failure, deferredFailure)
        if (failure == null) {
            val binding = synchronized(stateLock) { proofBinding }
            if (binding != null) {
                val bindingFailure = runCatching { binding.close() }.exceptionOrNull()
                if (bindingFailure == null) synchronized(stateLock) {
                    if (proofBinding === binding) proofBinding = null
                }
                failure = appendFailure(failure, bindingFailure)
            }
        }
        if (failure != null) {
            synchronized(stateLock) { revocationFailed = true }
            throw UltraProfileActivationRevocationException(failure)
        }
    }

    private fun awaitOperationRetirement(observed: Operation): Throwable? {
        val deadline = System.nanoTime() +
            MAXIMUM_CLOSE_BARRIER_MILLIS * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (operation === observed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    return IllegalStateException(
                        "Timed out waiting for authorized Ultra profile retirement",
                    )
                }
                val millis = remaining / NANOS_PER_MILLISECOND
                val nanos = (remaining % NANOS_PER_MILLISECOND).toInt()
                try {
                    stateLock.wait(millis, nanos)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return interrupted
                }
            }
        }
        return null
    }

    private fun awaitReceiptRevocationRetirement(observed: ReceiptRevocation): Throwable? {
        val deadline = System.nanoTime() +
            MAXIMUM_CLOSE_BARRIER_MILLIS * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (receiptRevocation === observed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    return IllegalStateException(
                        "Timed out waiting for authorized Ultra receipt revocation",
                    )
                }
                try {
                    stateLock.wait(
                        remaining / NANOS_PER_MILLISECOND,
                        (remaining % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return interrupted
                }
            }
        }
        return null
    }

    /** Clears a reservation and reports whether it was invalidated by close/cancellation. */
    private fun finishOperationWithoutPublish(reserved: Operation): Boolean =
        synchronized(stateLock) {
            clearOperationLocked(reserved)
            closed || reserved.cancelled
        }

    /**
     * Another thread's pure liveness probe is a short read-only reservation, not evidence that the
     * lease or child died. Wait for it (bounded, released by [clearOperationLocked]) instead of
     * reporting dead. Any other operation, an own-thread reservation, interruption or the bound
     * leaves the caller's existing fail-closed `operation != null` check in charge.
     */
    private fun awaitForeignLivenessProbeLocked() {
        val deadline = System.nanoTime() + FOREIGN_LIVENESS_PROBE_WAIT_NANOS
        while (true) {
            val active = operation ?: return
            if ((active.kind != OperationKind.PROBE_LEASE &&
                    active.kind != OperationKind.PROBE_PROVISIONING) ||
                active.ownerThread === Thread.currentThread() || closed || policyRevoked
            ) return
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return
            try {
                stateLock.wait((remaining / NANOS_PER_MILLISECOND).coerceAtLeast(1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun clearOperationLocked(expected: Operation) {
        if (operation === expected) {
            operation = null
            stateLock.notifyAll()
        }
    }

    private fun currentSessionLocked(
        token: UltraProfileSessionToken,
        receipt: MfiAuthenticationReceipt,
    ): SessionRecord? {
        val current = session ?: return null
        return current.takeIf {
            token.owner === owner && it.token === token &&
                it.token.generation == token.generation && it.receipt === receipt
        }
    }

    /** A provisional successor keeps the same runtime tree live while its predecessor retires. */
    private fun sessionReceiptIsLiveLocked(record: SessionRecord): Boolean {
        if (record.receipt.isLiveForUltraActivation()) return true
        val pending = renewal
        return pending?.session === record && pending.predecessor === record.receipt &&
            pending.successor.isLiveForUltraActivation()
    }

    private fun onAuthenticationReceiptRevoked(receipt: MfiAuthenticationReceipt) {
        if (synchronized(stateLock) { failedReceiptRevocation === receipt }) {
            // The first invocation detached the session before retirement failed. The authority
            // replays this exact callback, so retry the retained roots rather than treating the
            // now-null session as a successful no-op.
            if (synchronized(stateLock) { operation?.ownerThread === Thread.currentThread() }) {
                throw UltraProfileActivationRevocationException(
                    IllegalStateException(
                        "Authorized Ultra operation has not retired on callback reentry",
                    ),
                )
            }
            close()
            synchronized(stateLock) { failedReceiptRevocation = null }
            return
        }
        var interruptedOperation: Operation? = null
        var observedRevocation: ReceiptRevocation? = null
        val closePlan = synchronized(stateLock) {
            val pending = renewal
            if (pending != null && pending.predecessor === receipt) {
                pending.predecessorRevoked = true
                return
            }
            if (pending != null && pending.successor === receipt) {
                renewal = null
                pending.handle.rolledBack = true
                if (pending.predecessor.isLiveForUltraActivation() &&
                    session === pending.session &&
                    pending.session.receipt === pending.predecessor
                ) {
                    // The MFi authority restored the exact predecessor. Keep the complete runtime
                    // tree and let the prepared token observe this idempotent rollback.
                    return
                }
                pending.handle.requiresLifecycleClose = true
                retirementDeferredUntilLifecycleClose = true
                closed = true
            }
            val current = session
            if (current == null ||
                current.receipt !== receipt && pending?.successor !== receipt
            ) {
                return
            }
            check(receiptRevocation == null) {
                "Authorized Ultra receipt revocation is already in progress"
            }
            observedRevocation = ReceiptRevocation(receipt).also { receiptRevocation = it }
            session = null
            operation?.takeIf { it.session === current }?.let {
                it.cancelled = true
                interruptedOperation = it
            }
            ClosePlan().also {
                detachPreparationLocked(it)
                detachLeaseLocked(it)
            }
        }
        try {
            val failure = executeClosePlan(closePlan) ?: interruptedOperation?.let { interrupted ->
                if (interrupted.ownerThread === Thread.currentThread()) {
                    IllegalStateException(
                        "Authorized Ultra operation cannot retire during its own receipt callback",
                    )
                } else {
                    awaitOperationRetirement(interrupted)
                }
            }
            if (failure != null) {
                synchronized(stateLock) { failedReceiptRevocation = receipt }
                poison(failure)
                throw UltraProfileActivationRevocationException(failure)
            }
        } finally {
            synchronized(stateLock) {
                if (receiptRevocation === observedRevocation) {
                    receiptRevocation = null
                    stateLock.notifyAll()
                }
            }
        }
    }

    private fun staleCodeLocked(receipt: MfiAuthenticationReceipt): UltraProfileActivationDenialCode =
        if (usedReceipts.containsKey(receipt)) {
            UltraProfileActivationDenialCode.STALE_GENERATION
        } else {
            UltraProfileActivationDenialCode.STALE_RECEIPT
        }

    private fun unavailableForMutationLocked(): UltraProfileActivationDenialCode? = when {
        closed || revocationFailed || policyRevoked -> UltraProfileActivationDenialCode.CLOSED
        receiptRevocation != null || operation != null || renewal != null ->
            UltraProfileActivationDenialCode.BUSY
        else -> null
    }

    private fun renewalDenied(code: UltraProfileActivationDenialCode): Nothing =
        throw UltraProfileSessionRenewalException(code)

    private fun detachPreparationLocked(plan: ClosePlan) {
        val record = preparation ?: return
        preparation = null
        record.handle.revoked = true
        if (operation?.preparation !== record) plan.preparations += record.delegate
    }

    private fun detachLeaseLocked(plan: ClosePlan) {
        cancelRenewalLocked()
        val record = lease ?: return
        lease = null
        record.handle.revoked = true
        record.children.keys.forEach { it.revoked = true }
        if (operation?.lease !== record) appendLeaseLocked(record, plan)
    }

    private fun cancelRenewalLocked() {
        renewal?.let { pending ->
            pending.handle.rolledBack = true
            pending.handle.requiresLifecycleClose = true
            renewal = null
        }
    }

    private fun appendLeaseLocked(record: LeaseRecord, plan: ClosePlan) {
        appendLease(record, plan)
    }

    private fun appendLease(record: LeaseRecord, plan: ClosePlan) {
        record.handle.revoked = true
        record.children.entries.map { it.key to it.value }
            .also { record.children.clear() }
            .forEach { (handle, child) ->
            handle.revoked = true
            plan.instrumentSessions += child.instrumentSessions.keys
            child.instrumentSessions.clear()
            plan.runtimes += child.runtime
            plan.provisionings += child.delegate
            }
        plan.leases += record.delegate
    }

    /**
     * Runs after releasing [stateLock]. Each stage must retire completely before its parent stage
     * begins; otherwise the exact remaining objects are retained for a later retry.
     */
    private fun executeClosePlan(plan: ClosePlan): Throwable? {
        if (plan.isEmpty) return null
        val deferred = synchronized(stateLock) {
            if (retirementDeferredUntilLifecycleClose) {
                pendingRetirement.append(plan)
                true
            } else {
                false
            }
        }
        if (deferred) return null
        // Revocation admission was closed when its handle was detached under stateLock. A scene
        // already admitted may still be resolving or entering the EGL queue; never retire its
        // runtime/lease first. On timeout or same-thread reentry retain the exact close owners.
        val sceneFailure = awaitSceneSubmissionUses()
        if (sceneFailure != null) {
            synchronized(stateLock) {
                pendingRetirement.append(plan)
                pendingRetirementFailure = appendFailure(pendingRetirementFailure, sceneFailure)
            }
            return sceneFailure
        }
        fun <T : Closeable> retireStage(stage: MutableList<T>): Throwable? {
            var failure: Throwable? = null
            val iterator = stage.listIterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val closeFailure = closeOne(candidate, rememberFailure = false)
                if (closeFailure == null) {
                    iterator.remove()
                } else {
                    failure = appendFailure(failure, closeFailure)
                }
            }
            return failure
        }

        val failure = retireStage(plan.preparations)
            ?: retireStage(plan.instrumentSessions)
            ?: retireStage(plan.runtimes)
            ?: retireStage(plan.provisionings)
            ?: retireStage(plan.leases)
        if (failure != null) {
            synchronized(stateLock) {
                pendingRetirement.append(plan)
                pendingRetirementFailure = appendFailure(pendingRetirementFailure, failure)
            }
        }
        return failure
    }

    private fun awaitSceneSubmissionUses(): Throwable? {
        val deadline = System.nanoTime() +
            MAXIMUM_CLOSE_BARRIER_MILLIS * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (activeSceneSubmissionUses != 0) {
                if (sceneSubmissionOwners.containsKey(Thread.currentThread())) {
                    return IllegalStateException(
                        "Authorized Ultra scene cannot retire during its own submission callback",
                    )
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    return IllegalStateException(
                        "Timed out waiting for authorized Ultra scene submission handoff",
                    )
                }
                try {
                    stateLock.wait(
                        remaining / NANOS_PER_MILLISECOND,
                        (remaining % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return interrupted
                }
            }
        }
        return null
    }

    private fun closeOne(
        closeable: Closeable?,
        rememberFailure: Boolean = true,
    ): Throwable? {
        if (closeable == null) return null
        val failure = external { closeable.close() }.failure
        if (failure != null && rememberFailure) {
            synchronized(stateLock) {
                when (closeable) {
                    is AuthorizedUltraProfilePreparation ->
                        pendingRetirement.preparations += closeable
                    is AuthorizedUltraProfileBackendProvisioning ->
                        pendingRetirement.provisionings += closeable
                    is AuthorizedUltraProfileBackendLease ->
                        pendingRetirement.leases += closeable
                }
                pendingRetirementFailure = appendFailure(pendingRetirementFailure, failure)
            }
        }
        return failure
    }

    private fun <T> external(block: () -> T): ExternalResult<T> = try {
        ExternalResult(block(), null)
    } catch (failure: Throwable) {
        ExternalResult(null, failure)
    }

    private fun poison(initialFailure: Throwable, ownedOperation: Operation? = null) {
        val closePlan = synchronized(stateLock) {
            revocationFailed = true
            closed = true
            session = null
            operation?.cancelled = true
            if (ownedOperation != null) clearOperationLocked(ownedOperation)
            ClosePlan().also {
                it.append(pendingRetirement)
                pendingRetirement = ClosePlan()
                detachPreparationLocked(it)
                detachLeaseLocked(it)
            }
        }
        executeClosePlan(closePlan)?.let { cleanupFailure ->
            if (cleanupFailure !== initialFailure) initialFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun appendFailure(existing: Throwable?, next: Throwable?): Throwable? {
        if (next == null) return existing
        if (existing == null) return next
        if (next !== existing) existing.addSuppressed(next)
        return existing
    }

    internal companion object {
        /** Only the fixed ProductSession root may pass a captured policy selection here. */
        fun fromPolicyEpoch(epoch: UltraProfilePolicyEpoch): UltraProfileActivationCoordinator =
            UltraProfileActivationCoordinator(epoch)

        private const val MAXIMUM_RECEIPT_GENERATIONS = 4_096
        private const val MAXIMUM_CLOSE_BARRIER_MILLIS = 30_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** A concurrent read-only liveness probe is short; beyond this, keep failing closed. */
        private const val FOREIGN_LIVENESS_PROBE_WAIT_NANOS = 500L * NANOS_PER_MILLISECOND
    }
}

private fun canonicalProfileMfiCertificateIdentity(identity: MfiAuthenticationIdentity): String =
    buildString {
        append("xcertplay-mfi-authentication-v1:")
        append(identity.certificateType.name)
        append(':')
        append(identity.sha256Hex())
    }

/** No untrusted material is read by this adapter while the product provider is disabled. */
private class DeploymentProviderActivationBackend(
    private val provider: UltraProfileDeploymentProvider,
) : UltraProfileActivationBackend {
    override fun prepare(request: UltraProfileActivationRequest): UltraProfileBackendPrepareResult =
        when (val result = provider.acquire(request)) {
            is UltraProfileDeploymentResult.Prepared ->
                UltraProfileBackendPrepareResult.Prepared(result.preparation)
            UltraProfileDeploymentResult.Unavailable -> UltraProfileBackendPrepareResult.Unavailable
        }
}

internal class UltraProfileActivationRevocationException(cause: Throwable) :
    IllegalStateException("Authorized Ultra profile revocation failed", cause)

internal class UltraProfileSessionRenewalException(
    val code: UltraProfileActivationDenialCode,
) : IllegalStateException("Authorized Ultra AA05 session renewal was denied: ${code.name}")

