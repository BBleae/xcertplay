package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer

/**
 * Fields that a user-owned, detached bench signature covers for one inspected profile candidate.
 *
 * The MFi identity is the type and SHA-256 digest captured from the exact certificate material by
 * [MfiAuthenticationIdentity]. The caller must obtain it from the current authenticator, and must
 * obtain [candidateContentDigest] from the inspected candidate, not from a filename or manifest.
 * Identical-context reuse is expected for a stored asset. Fresh session proof belongs to the
 * separate, live AA05 receipt and activation coordinator, never to this offline signature. This
 * is not an Apple/OEM profile authorization or evidence of a successful MFi challenge.
 */
internal class BenchUltraProfileSignatureBinding(
    val vehicleId: String,
    authenticationIdentity: MfiAuthenticationIdentity,
    val assetIdentifier: String,
    val iosContentVersion: String,
    val accessoryContentVersion: String,
    candidateContentDigest: UltraProfileDigest,
) {
    private val authenticationKind = authenticationIdentity.certificateType.name
    private val authenticationDigest = authenticationIdentity.sha256Bytes()
    private val contentDigest = candidateContentDigest.bytes()

    init {
        requireCanonicalText(vehicleId, MAXIMUM_VEHICLE_BYTES)
        requireCanonicalText(assetIdentifier, MAXIMUM_ASSET_BYTES)
        requireCanonicalText(iosContentVersion, MAXIMUM_VERSION_BYTES)
        requireCanonicalText(accessoryContentVersion, MAXIMUM_VERSION_BYTES)
        require(authenticationKind == "MFI" || authenticationKind == "BAA")
        require(authenticationDigest.size == SHA256_BYTES)
        require(contentDigest.size == SHA256_BYTES)
    }

    /** Fixed-order, length-delimited, domain-separated bytes to sign with SHA256withECDSA. */
    fun canonicalSignedPayload(): ByteArray = ByteArrayOutputStream().use { output ->
        output.write(SIGNED_PAYLOAD_DOMAIN)
        output.write(FORMAT_VERSION)
        output.writeLengthPrefixed(vehicleId.toByteArray(StandardCharsets.UTF_8))
        output.writeLengthPrefixed(authenticationKind.toByteArray(StandardCharsets.US_ASCII))
        output.writeLengthPrefixed(authenticationDigest)
        output.writeLengthPrefixed(assetIdentifier.toByteArray(StandardCharsets.UTF_8))
        output.writeLengthPrefixed(iosContentVersion.toByteArray(StandardCharsets.UTF_8))
        output.writeLengthPrefixed(accessoryContentVersion.toByteArray(StandardCharsets.UTF_8))
        output.writeLengthPrefixed(contentDigest)
        output.toByteArray().also { require(it.size <= MAXIMUM_PAYLOAD_BYTES) }
    }

    override fun toString(): String = "BenchUltraProfileSignatureBinding(<redacted>)"
}

/** A key match here says only that this user-owned bench pin signed this exact binding. */
internal sealed interface BenchUltraProfileSignatureResult {
    object MatchesUserPinnedKeyOnly : BenchUltraProfileSignatureResult

    data class Rejected(val code: RejectionCode) : BenchUltraProfileSignatureResult

    enum class RejectionCode {
        MALFORMED_ENVELOPE,
        BINDING_MISMATCH,
        INVALID_SIGNATURE,
    }
}

/**
 * Strict, bounded verifier for a detached user-owned bench signature.
 *
 * Envelope: ASCII magic, one-byte version, two-byte big-endian payload length, exact canonical
 * payload, one-byte DER signature length, strict DER ECDSA signature, and no trailing bytes. The
 * payload repeats its own independent domain/version so it cannot be transplanted to another
 * protocol that merely wraps the same bytes. There is intentionally no product trust root here.
 */
internal class BenchUltraProfileSignatureVerifier(pinnedP256SubjectPublicKeyInfo: ByteArray) {
    private val pinnedKey: ECPublicKey = decodePinnedKey(pinnedP256SubjectPublicKeyInfo)

    fun verify(
        expected: BenchUltraProfileSignatureBinding,
        envelope: ByteArray,
    ): BenchUltraProfileSignatureResult {
        val parsed = parseEnvelope(envelope)
            ?: return rejected(BenchUltraProfileSignatureResult.RejectionCode.MALFORMED_ENVELOPE)
        if (!MessageDigest.isEqual(parsed.payload, expected.canonicalSignedPayload())) {
            return rejected(BenchUltraProfileSignatureResult.RejectionCode.BINDING_MISMATCH)
        }
        val accepted = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pinnedKey)
                update(parsed.payload)
                verify(parsed.signature)
            }
        } catch (_: GeneralSecurityException) {
            false
        }
        return if (accepted) {
            BenchUltraProfileSignatureResult.MatchesUserPinnedKeyOnly
        } else {
            rejected(BenchUltraProfileSignatureResult.RejectionCode.INVALID_SIGNATURE)
        }
    }

    companion object {
        /** For a separate bench signer; private keys and signing are deliberately absent here. */
        fun envelopeForDetachedSignature(
            binding: BenchUltraProfileSignatureBinding,
            derSignature: ByteArray,
        ): ByteArray {
            require(isStrictP256DerSignature(derSignature)) { "Noncanonical ECDSA signature" }
            val payload = binding.canonicalSignedPayload()
            return ByteArrayOutputStream().use { output ->
                output.write(ENVELOPE_MAGIC)
                output.write(FORMAT_VERSION)
                output.write((payload.size ushr 8) and 0xff)
                output.write(payload.size and 0xff)
                output.write(payload)
                output.write(derSignature.size)
                output.write(derSignature)
                output.toByteArray()
            }
        }
    }
}

private data class ParsedBenchEnvelope(val payload: ByteArray, val signature: ByteArray)

private fun parseEnvelope(envelope: ByteArray): ParsedBenchEnvelope? {
    if (envelope.size !in MINIMUM_ENVELOPE_BYTES..MAXIMUM_ENVELOPE_BYTES) return null
    var cursor = 0
    for (marker in ENVELOPE_MAGIC) {
        if (envelope[cursor++] != marker) return null
    }
    if ((envelope[cursor++].toInt() and 0xff) != FORMAT_VERSION) return null
    val payloadLength = ((envelope[cursor++].toInt() and 0xff) shl 8) or
        (envelope[cursor++].toInt() and 0xff)
    if (payloadLength !in 1..MAXIMUM_PAYLOAD_BYTES) return null
    if (cursor + payloadLength + 1 > envelope.size) return null
    val payload = envelope.copyOfRange(cursor, cursor + payloadLength)
    cursor += payloadLength
    val signatureLength = envelope[cursor++].toInt() and 0xff
    if (signatureLength !in MINIMUM_SIGNATURE_BYTES..MAXIMUM_SIGNATURE_BYTES) return null
    if (cursor + signatureLength != envelope.size) return null
    val signature = envelope.copyOfRange(cursor, envelope.size)
    if (!isStrictP256DerSignature(signature)) return null
    return ParsedBenchEnvelope(payload, signature)
}

private fun decodePinnedKey(encoded: ByteArray): ECPublicKey {
    require(encoded.size in 1..MAXIMUM_PUBLIC_KEY_BYTES) { "Invalid P-256 pin length" }
    val key = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded.copyOf()))
    } catch (error: GeneralSecurityException) {
        throw IllegalArgumentException("Invalid P-256 public key", error)
    }
    require(key is ECPublicKey && hasP256Parameters(key.params) && isValidP256Point(key)) {
        "Bench pin must be an ECDSA P-256 public key"
    }
    require(MessageDigest.isEqual(encoded, key.encoded)) {
        "Bench P-256 public key encoding must be canonical"
    }
    return key
}

private fun hasP256Parameters(params: ECParameterSpec): Boolean {
    val field = params.curve.field as? ECFieldFp ?: return false
    return field.p == P256_P &&
        params.curve.a == P256_A &&
        params.curve.b == P256_B &&
        params.generator.affineX == P256_GX &&
        params.generator.affineY == P256_GY &&
        params.order == P256_N &&
        params.cofactor == 1
}

private fun isValidP256Point(key: ECPublicKey): Boolean {
    val x = key.w.affineX ?: return false
    val y = key.w.affineY ?: return false
    if (x.signum() < 0 || y.signum() < 0 || x >= P256_P || y >= P256_P) return false
    return y.modPow(BigInteger.valueOf(2), P256_P) ==
        x.modPow(BigInteger.valueOf(3), P256_P)
            .add(P256_A.multiply(x))
            .add(P256_B)
            .mod(P256_P)
}

/** DER SEQUENCE of exactly two minimally encoded positive P-256 INTEGERs. */
private fun isStrictP256DerSignature(signature: ByteArray): Boolean {
    if (signature.size !in MINIMUM_SIGNATURE_BYTES..MAXIMUM_SIGNATURE_BYTES) return false
    if (signature[0] != 0x30.toByte() ||
        (signature[1].toInt() and 0xff) != signature.size - 2
    ) return false
    var cursor = 2
    repeat(2) {
        if (cursor + 2 > signature.size || signature[cursor++] != 0x02.toByte()) return false
        val length = signature[cursor++].toInt() and 0xff
        if (length !in 1..33 || cursor + length > signature.size) return false
        val first = signature[cursor].toInt() and 0xff
        if (first and 0x80 != 0) return false
        if (first == 0 && length > 1 && signature[cursor + 1].toInt() and 0x80 == 0) return false
        val value = BigInteger(1, signature.copyOfRange(cursor, cursor + length))
        if (value.signum() <= 0 || value >= P256_N) return false
        cursor += length
    }
    return cursor == signature.size
}

private fun ByteArrayOutputStream.writeLengthPrefixed(bytes: ByteArray) {
    require(bytes.size <= 0xffff)
    write((bytes.size ushr 8) and 0xff)
    write(bytes.size and 0xff)
    write(bytes)
}

private fun requireCanonicalText(value: String, maximumUtf8Bytes: Int) {
    require(value.isNotBlank() && value == Normalizer.normalize(value, Normalizer.Form.NFC)) {
        "Bench binding text must be nonblank NFC"
    }
    require(value.none { it == '\u0000' || it.isISOControl() }) {
        "Bench binding text contains control data"
    }
    val encoded = try {
        val buffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Bench binding text is not valid UTF-8", error)
    }
    require(encoded.size in 1..maximumUtf8Bytes) { "Bench binding text length is invalid" }
}

private fun rejected(code: BenchUltraProfileSignatureResult.RejectionCode) =
    BenchUltraProfileSignatureResult.Rejected(code)

private const val FORMAT_VERSION = 1
private const val SHA256_BYTES = 32
private const val MAXIMUM_VEHICLE_BYTES = 512
private const val MAXIMUM_ASSET_BYTES = 512
private const val MAXIMUM_VERSION_BYTES = 128
private const val MAXIMUM_PAYLOAD_BYTES = 2048
private const val MAXIMUM_ENVELOPE_BYTES = 4096
private const val MAXIMUM_PUBLIC_KEY_BYTES = 256
private const val MINIMUM_SIGNATURE_BYTES = 8
private const val MAXIMUM_SIGNATURE_BYTES = 72
private val SIGNED_PAYLOAD_DOMAIN =
    "xcertplay-user-bench-ultra-profile-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
private val ENVELOPE_MAGIC = "XCP-BENCH-ULTRA-SIG\u0000".toByteArray(StandardCharsets.US_ASCII)
private val MINIMUM_ENVELOPE_BYTES = ENVELOPE_MAGIC.size + 1 + 2 + 1 + MINIMUM_SIGNATURE_BYTES

private fun p256Hex(hex: String) = BigInteger(hex, 16)
private val P256_P = p256Hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
private val P256_A = P256_P.subtract(BigInteger.valueOf(3))
private val P256_B = p256Hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
private val P256_GX = p256Hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
private val P256_GY = p256Hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")
private val P256_N = p256Hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
