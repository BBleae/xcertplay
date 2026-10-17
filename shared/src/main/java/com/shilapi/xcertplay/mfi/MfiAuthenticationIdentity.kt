package com.shilapi.xcertplay.mfi

import java.security.MessageDigest

/**
 * Stable, non-secret identity of the exact authenticator certificate material used by a session.
 *
 * This is binding input for a deployment trust policy, not proof that a profile is authorized.
 * Certificate bytes never escape this capture operation and diagnostics are always redacted.
 */
class MfiAuthenticationIdentity private constructor(
    val certificateType: MfiCertificateType,
    digestBytes: ByteArray,
) {
    private val digest = digestBytes.copyOf()

    init {
        require(digest.size == SHA256_BYTES)
    }

    fun sha256Bytes(): ByteArray = digest.copyOf()

    fun sha256Hex(): String = buildString(SHA256_BYTES * 2) {
        digest.forEach { byte ->
            append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }

    override fun equals(other: Any?): Boolean =
        other is MfiAuthenticationIdentity &&
            certificateType == other.certificateType &&
            MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = 31 * certificateType.hashCode() + digest.contentHashCode()

    override fun toString(): String =
        "MfiAuthenticationIdentity(type=$certificateType, sha256=<redacted>)"

    companion object {
        private const val SHA256_BYTES = 32
        private const val MAXIMUM_CERTIFICATE_BYTES =
            MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH
        private val DOMAIN = "xcertplay-ultra-auth-identity-v1\u0000".toByteArray(Charsets.US_ASCII)

        /** Reads and hashes the live authenticator once, before any Ultra runtime is activated. */
        fun capture(authenticator: MfiAuthenticator): MfiAuthenticationIdentity {
            val certificateType = authenticator.certificateType
            return when (certificateType) {
                MfiCertificateType.MFI -> {
                    val certificate = authenticator.readCertificate(MAXIMUM_CERTIFICATE_BYTES)
                    fromCaptured(certificateType, certificate, null)
                }
                MfiCertificateType.BAA -> {
                    val pair = authenticator.baaCertificates()
                    fromCaptured(certificateType, ByteArray(0), pair)
                }
            }
        }

        internal fun fromCaptured(
            certificateType: MfiCertificateType,
            certificate: ByteArray,
            baaCertificates: BaaCertificatePair?,
        ): MfiAuthenticationIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DOMAIN)
            updateLengthPrefixed(digest, certificateType.name.toByteArray(Charsets.US_ASCII))
            when (certificateType) {
                MfiCertificateType.MFI -> {
                    require(baaCertificates == null) { "MFi identity cannot include BAA certificates" }
                    requireCertificate(certificate, "MFi")
                    updateLengthPrefixed(digest, certificate)
                }
                MfiCertificateType.BAA -> {
                    val pair = requireNotNull(baaCertificates) {
                        "BAA identity requires leaf and intermediate certificates"
                    }
                    requireCertificate(pair.leaf, "BAA leaf")
                    requireCertificate(pair.intermediate, "BAA intermediate")
                    updateLengthPrefixed(digest, pair.leaf)
                    updateLengthPrefixed(digest, pair.intermediate)
                }
            }
            return MfiAuthenticationIdentity(certificateType, digest.digest())
        }

        private fun requireCertificate(value: ByteArray, label: String) {
            require(value.isNotEmpty() && value.size <= MAXIMUM_CERTIFICATE_BYTES) {
                "$label certificate length is outside 1..$MAXIMUM_CERTIFICATE_BYTES"
            }
        }

        private fun updateLengthPrefixed(digest: MessageDigest, value: ByteArray) {
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
