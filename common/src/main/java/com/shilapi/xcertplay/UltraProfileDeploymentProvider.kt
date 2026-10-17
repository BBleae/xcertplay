package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiCertificateType
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Arrays

/**
 * The selected authenticator certificate kind to which an authorized deployment may bind.
 *
 * This type records certificate identity evidence; it neither opens an MFi transport nor proves
 * that a later iAP2 challenge exchange succeeded. New evidence kinds require a source change
 * so deployment selection cannot be redirected by preferences or external input.
 */
internal enum class UltraRuntimeAuthenticationKind {
    MFI_ACCESSORY_AUTHENTICATION,
    BAA_ACCESSORY_AUTHENTICATION,
}

/**
 * Immutable, log-safe vehicle and selected-authenticator identity for one controller generation.
 *
 * [authenticationIdentitySha256] is a digest of deployment-defined authentication identity
 * material, not the material itself. The byte array is copied both on input and output.
 */
internal class UltraRuntimeIdentity(
    val vehicleId: String,
    val authenticationKind: UltraRuntimeAuthenticationKind,
    authenticationIdentitySha256: ByteArray,
) {
    init {
        require(vehicleId.isNotBlank()) { "Ultra runtime vehicle ID must not be blank" }
        require(vehicleId.length <= MAXIMUM_VEHICLE_ID_BYTES) {
            "Ultra runtime vehicle ID is too long"
        }
        require(StandardCharsets.UTF_8.newEncoder().canEncode(vehicleId)) {
            "Ultra runtime vehicle ID is not valid Unicode"
        }
        require(vehicleId == Normalizer.normalize(vehicleId, Normalizer.Form.NFC)) {
            "Ultra runtime vehicle ID must use NFC normalization"
        }
        require(vehicleId.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_VEHICLE_ID_BYTES) {
            "Ultra runtime vehicle ID is too long"
        }
        require(vehicleId.none { it == '\u0000' || Character.isISOControl(it) }) {
            "Ultra runtime vehicle ID contains a control character"
        }
        require(authenticationIdentitySha256.size == SHA256_BYTES) {
            "Ultra runtime authentication identity must be a SHA-256 digest"
        }
    }

    private val storedAuthenticationIdentitySha256 = authenticationIdentitySha256.copyOf()

    val authenticationIdentitySha256: ByteArray
        get() = storedAuthenticationIdentitySha256.copyOf()

    override fun equals(other: Any?): Boolean =
        other is UltraRuntimeIdentity &&
            vehicleId == other.vehicleId &&
            authenticationKind == other.authenticationKind &&
            MessageDigest.isEqual(
                storedAuthenticationIdentitySha256,
                other.storedAuthenticationIdentitySha256,
            )

    override fun hashCode(): Int {
        var result = vehicleId.hashCode()
        result = 31 * result + authenticationKind.hashCode()
        result = 31 * result + Arrays.hashCode(storedAuthenticationIdentitySha256)
        return result
    }

    /** Vehicle IDs, authentication mechanisms, and stable digests must not enter generic logs. */
    override fun toString(): String = "UltraRuntimeIdentity(<redacted>)"

    companion object {
        /** Binds the product identity to the exact certificate material captured by MFi startup. */
        fun from(
            vehicleId: String,
            authenticationIdentity: MfiAuthenticationIdentity,
        ): UltraRuntimeIdentity = UltraRuntimeIdentity(
            vehicleId = vehicleId,
            authenticationKind = when (authenticationIdentity.certificateType) {
                MfiCertificateType.MFI ->
                    UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION
                MfiCertificateType.BAA ->
                    UltraRuntimeAuthenticationKind.BAA_ACCESSORY_AUTHENTICATION
            },
            authenticationIdentitySha256 = authenticationIdentity.sha256Bytes(),
        )

        const val SHA256_BYTES = 32
        private const val MAXIMUM_VEHICLE_ID_BYTES = 512
    }
}

/** Stable, deliberately non-sensitive deployment acquisition result. */
internal sealed interface UltraProfileDeploymentResult {
    /** Positive result is constructible only by product code in this fixed Android module. */
    class Prepared internal constructor(
        val preparation: AuthorizedUltraProfilePreparation,
    ) : UltraProfileDeploymentResult {
        override fun toString(): String = "UltraProfileDeploymentResult.Prepared(<redacted>)"
    }

    object Unavailable : UltraProfileDeploymentResult {
        const val CODE = "authorized-ultra-profile-provider-unavailable"

        override fun toString(): String =
            "UltraProfileDeploymentResult.Unavailable(code=$CODE)"
    }
}

/**
 * Product-level Ultra profile deployment boundary.
 *
 * The caller can neither supply a trust verifier, trust root, store path, nor implementation name.
 * A future authorized implementation must own those fixed dependencies inside its composition
 * root. The positive result accepts only one already-owning preparation whose compiled manifest is
 * later checked against the composition-root issuer; callers cannot assemble a profile, runtime,
 * and sidecar independently at this boundary.
 */
internal interface UltraProfileDeploymentProvider {
    fun acquire(request: UltraProfileActivationRequest): UltraProfileDeploymentResult

    fun stage(
        request: UltraProfileActivationRequest,
        candidateArchive: InputStream,
    ): UltraProfileDeploymentResult
}

/**
 * Default and release provider. It has no Store or verifier dependency, never consumes caller
 * bytes, and cannot create a sidecar or runtime. Debug can explicitly select a separate,
 * CAF-only user-signed bench provider; this object remains the fail-closed fallback.
 */
internal object DisabledUltraProfileDeploymentProvider : UltraProfileDeploymentProvider {
    override fun acquire(request: UltraProfileActivationRequest): UltraProfileDeploymentResult =
        UltraProfileDeploymentResult.Unavailable

    override fun stage(
        request: UltraProfileActivationRequest,
        candidateArchive: InputStream,
    ): UltraProfileDeploymentResult = UltraProfileDeploymentResult.Unavailable
}
