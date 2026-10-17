package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAcquisitionIntent
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiCertificateType
import java.security.MessageDigest

/**
 * An explicit, deployment-supplied pin for probing the phone's cluster-asset catalog.
 *
 * This does not install, authorize or advertise an asset. It only permits an acquisition intent
 * for an exact live MFi identity; the 0x4301 encoder checks the live receipt again before sending.
 */
class ClusterAssetCatalogProbe(
    val assetId: String,
    val sdkVersion: String,
    val expectedCertificateType: MfiCertificateType,
    expectedIdentitySha256: ByteArray,
) {
    private val pinnedIdentitySha256 = expectedIdentitySha256.copyOf()

    /** A defensive copy; callers cannot change the configured pin after construction. */
    val expectedIdentitySha256: ByteArray
        get() = pinnedIdentitySha256.copyOf()

    init {
        Iap2ClusterAssetAcquisitionIntent.requireValidCatalogField(assetId, "probe ID")
        Iap2ClusterAssetAcquisitionIntent.requireValidCatalogField(sdkVersion, "probe SDK version")
        require(pinnedIdentitySha256.size == SHA256_BYTES) {
            "cluster asset catalog probe identity digest must be 32 bytes"
        }
    }

    internal fun acquisitionIntentOrNull(
        liveIdentity: MfiAuthenticationIdentity,
    ): Iap2ClusterAssetAcquisitionIntent? {
        val digestMatches = MessageDigest.isEqual(
            pinnedIdentitySha256,
            liveIdentity.sha256Bytes(),
        )
        if (!digestMatches || expectedCertificateType != liveIdentity.certificateType) return null
        return Iap2ClusterAssetAcquisitionIntent(assetId, sdkVersion, liveIdentity)
    }

    override fun toString(): String = "ClusterAssetCatalogProbe(<redacted>)"

    companion object {
        const val MAX_FIELD_CHARACTERS = Iap2ClusterAssetAcquisitionIntent.MAX_FIELD_CHARACTERS
        const val MAX_FIELD_UTF8_BYTES = Iap2ClusterAssetAcquisitionIntent.MAX_FIELD_UTF8_BYTES
        private const val SHA256_BYTES = 32
    }
}
