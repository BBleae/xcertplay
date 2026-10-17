package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchUltraProfileSignatureTest {
    @Test fun validDetachedFixtureMatchesOnlyTheUserPinnedKey() {
        val key = p256Key()
        val binding = binding()
        val fixture = signedEnvelope(binding, key)
        val verifier = BenchUltraProfileSignatureVerifier(key.public.encoded)

        assertEquals(
            BenchUltraProfileSignatureResult.MatchesUserPinnedKeyOnly,
            verifier.verify(binding, fixture),
        )
        assertFalse(binding.toString().contains("vehicle-17"))
    }

    @Test fun signedEnvelopeCannotBeReboundToAnotherKeyOrSignature() {
        val key = p256Key()
        val binding = binding()
        val fixture = signedEnvelope(binding, key)
        val otherKey = p256Key()
        val differentSignature = detachedSignature(
            "not-the-profile-payload".toByteArray(),
            key,
        )

        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.INVALID_SIGNATURE,
            BenchUltraProfileSignatureVerifier(otherKey.public.encoded).verify(binding, fixture),
        )
        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.INVALID_SIGNATURE,
            BenchUltraProfileSignatureVerifier(key.public.encoded).verify(
                binding,
                BenchUltraProfileSignatureVerifier.envelopeForDetachedSignature(
                    binding,
                    differentSignature,
                ),
            ),
        )
    }

    @Test fun vehicleAndExactCapturedMfiCertificateIdentityAreBound() {
        val key = p256Key()
        val expected = binding()
        val fixture = signedEnvelope(expected, key)
        val verifier = BenchUltraProfileSignatureVerifier(key.public.encoded)

        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.BINDING_MISMATCH,
            verifier.verify(binding(vehicleId = "vehicle-18"), fixture),
        )
        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.BINDING_MISMATCH,
            verifier.verify(binding(identity = identity(byteArrayOf(9, 8, 7))), fixture),
        )
    }

    @Test fun certificateKindVersionsAssetAndCandidateContentDigestCannotBeReplayed() {
        val key = p256Key()
        val expected = binding()
        val fixture = signedEnvelope(expected, key)
        val verifier = BenchUltraProfileSignatureVerifier(key.public.encoded)
        val alternatives = listOf(
            binding(identity = identity(byteArrayOf(1, 2, 3), MfiCertificateType.BAA)),
            binding(assetId = "debug.synthetic.asset.v2"),
            binding(iosVersion = "ios-28"),
            binding(accessoryVersion = "accessory-2"),
            binding(content = digest("candidate-2")),
        )
        for (alternative in alternatives) {
            assertRejected(
                BenchUltraProfileSignatureResult.RejectionCode.BINDING_MISMATCH,
                verifier.verify(alternative, fixture),
            )
        }
    }

    @Test fun envelopeRejectsTrailingBytesBadLengthsBadVersionAndOversize() {
        val key = p256Key()
        val binding = binding()
        val fixture = signedEnvelope(binding, key)
        val verifier = BenchUltraProfileSignatureVerifier(key.public.encoded)
        val versionOffset = "XCP-BENCH-ULTRA-SIG\u0000".toByteArray().size
        val payloadLengthOffset = versionOffset + 1
        val payloadLength = ((fixture[payloadLengthOffset].toInt() and 0xff) shl 8) or
            (fixture[payloadLengthOffset + 1].toInt() and 0xff)
        val signatureLengthOffset = payloadLengthOffset + 2 + payloadLength
        val changedVersion = fixture.copyOf().also { it[versionOffset] = 2 }
        val tooLongPayload = fixture.copyOf().also {
            it[payloadLengthOffset] = 0x7f
            it[payloadLengthOffset + 1] = 0xff.toByte()
        }
        val nonMinimalDerLength = fixture.copyOf().also {
            it[signatureLengthOffset + 2] = 0x81.toByte()
        }
        val tamperedSignedPayload = fixture.copyOf().also {
            it[payloadLengthOffset + 2 + payloadLength - 1] =
                (it[payloadLengthOffset + 2 + payloadLength - 1].toInt() xor 1).toByte()
        }
        val tooLarge = ByteArray(4097)

        listOf(
            fixture + byteArrayOf(0),
            changedVersion,
            tooLongPayload,
            fixture.copyOf(fixture.size - 1),
            nonMinimalDerLength,
            tooLarge,
        ).forEach { envelope ->
            val result = verifier.verify(binding, envelope)
            assertFalse("Malformed envelope unexpectedly matched", result is
                BenchUltraProfileSignatureResult.MatchesUserPinnedKeyOnly)
        }
        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.MALFORMED_ENVELOPE,
            verifier.verify(binding, fixture + byteArrayOf(0)),
        )
        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.MALFORMED_ENVELOPE,
            verifier.verify(binding, nonMinimalDerLength),
        )
        assertRejected(
            BenchUltraProfileSignatureResult.RejectionCode.BINDING_MISMATCH,
            verifier.verify(binding, tamperedSignedPayload),
        )
    }

    @Test fun alternateDerAndNonCanonicalTextAreRejected() {
        val key = p256Key()
        val binding = binding()
        val signature = detachedSignature(binding.canonicalSignedPayload(), key)
        val nonMinimal = signature.copyOf().also {
            // Long-form length is not canonical for a P-256 ECDSA sequence.
            it[1] = 0x81.toByte()
        }
        assertThrows(IllegalArgumentException::class.java) {
            BenchUltraProfileSignatureVerifier.envelopeForDetachedSignature(binding, nonMinimal)
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(vehicleId = "e\u0301")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(vehicleId = "bad\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(assetId = "bad\u0000id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding(iosVersion = "x".repeat(129))
        }
    }

    @Test fun pinRejectsNonP256AndMalformedPublicKey() {
        val p384 = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        listOf(p384.public.encoded, rsa.public.encoded, byteArrayOf(1), ByteArray(257)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                BenchUltraProfileSignatureVerifier(it)
            }
        }
    }

    private fun signedEnvelope(binding: BenchUltraProfileSignatureBinding, key: KeyPair) =
        BenchUltraProfileSignatureVerifier.envelopeForDetachedSignature(
            binding,
            detachedSignature(binding.canonicalSignedPayload(), key),
        )

    private fun detachedSignature(payload: ByteArray, key: KeyPair): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(payload)
            sign()
        }

    private fun p256Key(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun binding(
        vehicleId: String = "vehicle-17",
        identity: MfiAuthenticationIdentity = identity(byteArrayOf(1, 2, 3)),
        assetId: String = "debug.synthetic.asset",
        iosVersion: String = "ios-27",
        accessoryVersion: String = "accessory-1",
        content: UltraProfileDigest = digest("candidate-1"),
    ) = BenchUltraProfileSignatureBinding(
        vehicleId,
        identity,
        assetId,
        iosVersion,
        accessoryVersion,
        content,
    )

    private fun identity(
        certificate: ByteArray,
        type: MfiCertificateType = MfiCertificateType.MFI,
    ): MfiAuthenticationIdentity = MfiAuthenticationIdentity.capture(
        object : MfiAuthenticator {
            override val certificateType = type
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) = certificate.copyOf()
            override fun baaCertificates() = com.shilapi.xcertplay.mfi.BaaCertificatePair(
                certificate.copyOf(),
                byteArrayOf(4, 5, 6),
            )
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        },
    )

    private fun digest(value: String) = UltraProfileDigest(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
    )

    private fun assertRejected(
        expected: BenchUltraProfileSignatureResult.RejectionCode,
        actual: BenchUltraProfileSignatureResult,
    ) {
        assertEquals(BenchUltraProfileSignatureResult.Rejected(expected), actual)
    }
}
