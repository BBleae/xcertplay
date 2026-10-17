package com.shilapi.xcertplay.airplay

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairTlsPskDerivationTest {
    @Test
    fun validFreshM1ToM4DerivesIndependentSixteenBytePairTlsPsk() {
        val fixture = fixture()
        val exchange = begin(fixture)
        assertFalse(fixture.responder.isVerified)
        assertNull(fixture.responder.deriveFreshPairTlsPsk())
        assertNull(fixture.responder.shared)

        assertSuccessM4(fixture.responder.handle(exchange.m3))
        assertTrue(fixture.responder.isVerified)
        assertEquals(CONTROLLER_ID, fixture.responder.verifiedControllerId)

        val expected = jceHkdfSha512(
            exchange.rawEcdh,
            "Pair-TLS-PSK".toByteArray(Charsets.US_ASCII),
            ByteArray(0),
            16,
        )
        val psk = checkNotNull(fixture.responder.deriveFreshPairTlsPsk())
        assertEquals(16, psk.size)
        assertArrayEquals(expected, psk)
        assertFalse(psk.contentEquals(fixture.responder.controlKeys!!.readKey.copyOf(16)))
        assertFalse(psk.contentEquals(fixture.responder.controlKeys!!.writeKey.copyOf(16)))
    }

    @Test
    fun wrongControllerSignatureRevokesPendingSecretAndCannotExportPsk() {
        val fixture = fixture()
        val invalid = begin(fixture, corruptControllerSignature = true)
        val pendingSecret = privateField<ByteArray>(fixture.responder, "sharedSecret")
        assertError(fixture.responder.handle(invalid.m3), 4)
        assertRevoked(fixture.responder)
        assertTrue(pendingSecret.all { it == 0.toByte() })

        val valid = begin(fixture)
        assertSuccessM4(fixture.responder.handle(valid.m3))
        assertArrayEquals(
            jceHkdfSha512(valid.rawEcdh, "Pair-TLS-PSK".toByteArray(), ByteArray(0), 16),
            fixture.responder.deriveFreshPairTlsPsk(),
        )
    }

    @Test
    fun exportedArraysCannotMutateSessionSecrets() {
        val fixture = fixture()
        val exchange = begin(fixture)
        assertSuccessM4(fixture.responder.handle(exchange.m3))

        val expectedShared = exchange.rawEcdh.copyOf()
        val expectedPsk = fixture.responder.deriveFreshPairTlsPsk()!!
        val expectedReadKey = fixture.responder.controlKeys!!.readKey.copyOf()
        val expectedWriteKey = fixture.responder.controlKeys!!.writeKey.copyOf()

        fixture.responder.shared!!.fill(0)
        fixture.responder.controlKeys!!.readKey.fill(0)
        fixture.responder.controlKeys!!.writeKey.fill(0)
        fixture.responder.deriveFreshPairTlsPsk()!!.fill(0)

        assertArrayEquals(expectedShared, fixture.responder.shared)
        assertArrayEquals(expectedReadKey, fixture.responder.controlKeys!!.readKey)
        assertArrayEquals(expectedWriteKey, fixture.responder.controlKeys!!.writeKey)
        assertArrayEquals(expectedPsk, fixture.responder.deriveFreshPairTlsPsk())
    }

    @Test
    fun newM1RevokesAndZeroesOldGenerationUntilNewM4() {
        val fixture = fixture()
        val first = begin(fixture)
        assertSuccessM4(fixture.responder.handle(first.m3))
        val firstPsk = fixture.responder.deriveFreshPairTlsPsk()!!
        val internalShared = privateField<ByteArray>(fixture.responder, "sharedSecret")
        val internalKeys = privateField<PairVerify.ControlKeys>(fixture.responder, "keys")

        val second = begin(fixture)
        assertRevoked(fixture.responder)
        assertTrue(internalShared.all { it == 0.toByte() })
        assertTrue(internalKeys.readKey.all { it == 0.toByte() })
        assertTrue(internalKeys.writeKey.all { it == 0.toByte() })

        assertSuccessM4(fixture.responder.handle(second.m3))
        val secondPsk = fixture.responder.deriveFreshPairTlsPsk()!!
        assertFalse(firstPsk.contentEquals(secondPsk))
        assertArrayEquals(
            jceHkdfSha512(second.rawEcdh, "Pair-TLS-PSK".toByteArray(), ByteArray(0), 16),
            secondPsk,
        )
    }

    @Test
    fun errorOrUnsupportedResumedStateClearsVerifiedGeneration() {
        val fixture = fixture()
        val first = begin(fixture)
        assertSuccessM4(fixture.responder.handle(first.m3))

        assertError(fixture.responder.handle(state(7)), 7)
        assertRevoked(fixture.responder)
        assertError(fixture.responder.handle(state(3)), 4)
        assertRevoked(fixture.responder)

        val second = begin(fixture)
        assertSuccessM4(fixture.responder.handle(second.m3))
        assertError(fixture.responder.handle(state(1)), 2)
        assertRevoked(fixture.responder)
    }

    @Test
    fun repeatedM3AfterCompletionCannotReuseFreshPsk() {
        val fixture = fixture()
        val exchange = begin(fixture)
        assertSuccessM4(fixture.responder.handle(exchange.m3))
        assertError(fixture.responder.handle(exchange.m3), 4)
        assertRevoked(fixture.responder)
    }

    private data class Fixture(val responder: PairVerify, val identity: AirPlayIdentity, val controllerKey: AirPlayCrypto.Ed25519KeyPair)

    private data class Exchange(val rawEcdh: ByteArray, val m3: ByteArray)

    private fun fixture(): Fixture {
        val identity = AirPlayIdentity.generate()
        val controllerKey = AirPlayCrypto.ed25519Generate()
        val store = PairingStore().apply { save(CONTROLLER_ID, controllerKey.publicKey) }
        return Fixture(PairVerify(identity, store), identity, controllerKey)
    }

    private fun begin(fixture: Fixture, corruptControllerSignature: Boolean = false): Exchange {
        val client = AirPlayCrypto.x25519Generate()
        val m1 = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(1)),
            Tlv8Item(TYPE_PUBLIC_KEY, client.publicKey),
        ))
        val m2 = Tlv8Codec.decode(fixture.responder.handle(m1))
        assertArrayEquals(byteArrayOf(2), m2[TYPE_STATE])
        val serverPublic = checkNotNull(m2[TYPE_PUBLIC_KEY])
        val rawEcdh = AirPlayCrypto.x25519Shared(client.privateKey, serverPublic)
        client.privateKey.fill(0)
        val verifyKey = AirPlayCrypto.hkdfSha512(
            rawEcdh,
            "Pair-Verify-Encrypt-Salt".toByteArray(),
            "Pair-Verify-Encrypt-Info".toByteArray(),
        )
        val m2Inner = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(
            verifyKey,
            AirPlayCrypto.nonceLabel("PV-Msg02"),
            checkNotNull(m2[TYPE_ENCRYPTED_DATA]),
        ))
        val accessoryId = checkNotNull(m2Inner[TYPE_IDENTIFIER])
        assertEquals(fixture.identity.pairingId, accessoryId.toString(Charsets.UTF_8))
        assertTrue(AirPlayCrypto.ed25519Verify(
            fixture.identity.publicKey,
            concatBytes(serverPublic, accessoryId, client.publicKey),
            checkNotNull(m2Inner[TYPE_SIGNATURE]),
        ))

        val controllerId = CONTROLLER_ID.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            fixture.controllerKey.privateKey,
            concatBytes(client.publicKey, controllerId, serverPublic),
        )
        if (corruptControllerSignature) {
            signature[0] = (signature[0].toInt() xor 1).toByte()
        }
        val m3Inner = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_IDENTIFIER, controllerId),
            Tlv8Item(TYPE_SIGNATURE, signature),
        ))
        val sealed = AirPlayCrypto.chachaSeal(verifyKey, AirPlayCrypto.nonceLabel("PV-Msg03"), m3Inner)
        verifyKey.fill(0)
        val m3 = Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(3)),
            Tlv8Item(TYPE_ENCRYPTED_DATA, sealed),
        ))
        return Exchange(rawEcdh, m3)
    }

    private fun assertSuccessM4(response: ByteArray) {
        val decoded = Tlv8Codec.decode(response)
        assertArrayEquals(byteArrayOf(4), decoded[TYPE_STATE])
        assertNull(decoded[TYPE_ERROR])
    }

    private fun assertError(response: ByteArray, state: Int) {
        val decoded = Tlv8Codec.decode(response)
        assertArrayEquals(byteArrayOf(state.toByte()), decoded[TYPE_STATE])
        assertArrayEquals(byteArrayOf(2), decoded[TYPE_ERROR])
    }

    private fun assertRevoked(responder: PairVerify) {
        assertFalse(responder.isVerified)
        assertNull(responder.verifiedControllerId)
        assertNull(responder.controlKeys)
        assertNull(responder.shared)
        assertNull(responder.deriveFreshPairTlsPsk())
    }

    private inline fun <reified T> privateField(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).run {
            isAccessible = true
            get(target) as T
        }

    private fun state(value: Int) = Tlv8Codec.encode(listOf(Tlv8Item(TYPE_STATE, byteArrayOf(value.toByte()))))

    /** Independent JCE HKDF-SHA512 extract/expand reference, not the production BC helper. */
    private fun jceHkdfSha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt, "HmacSHA512"))
        val prk = mac.doFinal(ikm)
        return try {
            mac.init(SecretKeySpec(prk, "HmacSHA512"))
            mac.update(info)
            mac.update(1.toByte())
            mac.doFinal().copyOf(length)
        } finally {
            prk.fill(0)
        }
    }

    private companion object {
        const val CONTROLLER_ID = "test-controller"
        const val TYPE_IDENTIFIER = 0x01
        const val TYPE_PUBLIC_KEY = 0x03
        const val TYPE_ENCRYPTED_DATA = 0x05
        const val TYPE_STATE = 0x06
        const val TYPE_ERROR = 0x07
        const val TYPE_SIGNATURE = 0x0a
    }
}
