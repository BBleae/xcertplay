package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import java.io.Closeable
import java.security.MessageDigest

/**
 * Freezes the certificate material used by one controller generation.
 *
 * Ultra profile binding and the later iAP2 authentication exchange must see the same bytes even
 * when the underlying remote service or document changes mid-session. Challenge signing remains
 * delegated to the live authenticator.
 */
internal class SessionBoundMfiAuthenticator private constructor(
    private val delegate: MfiAuthenticator,
    override val certificateType: MfiCertificateType,
    certificate: ByteArray,
    baaLeaf: ByteArray?,
    baaIntermediate: ByteArray?,
) : MfiAuthenticator, Closeable {
    private val lock = Any()
    private var closed = false
    private val certificate = certificate.copyOf()
    private val baaLeaf = baaLeaf?.copyOf()
    private val baaIntermediate = baaIntermediate?.copyOf()

    override fun protocolMajor(): Int = synchronized(lock) {
        requireOpenLocked()
        delegate.protocolMajor()
    }

    override fun readCertificate(maximumOutputLength: Int): ByteArray = synchronized(lock) {
        requireOpenLocked()
        require(maximumOutputLength > 0) { "maximumOutputLength must be positive" }
        if (certificate.size > maximumOutputLength) {
            throw MfiInvalidDataException(
                "Session certificate length ${certificate.size} exceeds $maximumOutputLength",
            )
        }
        certificate.copyOf()
    }

    override fun signChallenge(challenge: ByteArray): ByteArray = synchronized(lock) {
        requireOpenLocked()
        delegate.signChallenge(challenge.copyOf()).copyOf()
    }

    override fun baaCertificates(): BaaCertificatePair = synchronized(lock) {
        requireOpenLocked()
        if (certificateType != MfiCertificateType.BAA) {
            return super.baaCertificates()
        }
        BaaCertificatePair(requireNotNull(baaLeaf), requireNotNull(baaIntermediate))
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        certificate.fill(0)
        baaLeaf?.fill(0)
        baaIntermediate?.fill(0)
    }

    private fun requireOpenLocked() {
        check(!closed) { "MFi session certificate identity is closed" }
    }

    companion object {
        fun capture(source: MfiAuthenticator): SessionBoundMfiAuthenticator {
            val type = source.certificateType
            val certificate = source.readCertificate(
                MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
            )
            require(certificate.isNotEmpty()) { "MFi certificate must not be empty" }
            val pair = if (type == MfiCertificateType.BAA) source.baaCertificates() else null
            if (pair != null) {
                val expected = Iap2AuthenticationMessages.baaCertificatePackage(
                    pair.leaf,
                    pair.intermediate,
                ).payload
                if (!MessageDigest.isEqual(certificate, expected)) {
                    throw MfiInvalidDataException(
                        "BAA certificate package changed while the session identity was captured",
                    )
                }
            }
            return SessionBoundMfiAuthenticator(
                delegate = source,
                certificateType = type,
                certificate = certificate,
                baaLeaf = pair?.leaf,
                baaIntermediate = pair?.intermediate,
            )
        }
    }
}
