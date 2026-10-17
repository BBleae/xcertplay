package com.shilapi.xcertplay.mfi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiAuthenticationIdentityTest {
    @Test
    fun mfiCaptureIsStableDefensiveAndRedacted() {
        val certificate = byteArrayOf(1, 2, 3, 4)
        val authenticator = FakeAuthenticator(MfiCertificateType.MFI, certificate)
        val first = MfiAuthenticationIdentity.capture(authenticator)
        val second = MfiAuthenticationIdentity.capture(authenticator)

        certificate[0] = 9
        val digest = first.sha256Bytes()
        digest[0] = (digest[0].toInt() xor 0xff).toByte()

        assertEquals(first, second)
        assertEquals(64, first.sha256Hex().length)
        assertArrayEquals(second.sha256Bytes(), first.sha256Bytes())
        assertFalse(first.toString().contains(first.sha256Hex()))
        assertFalse(first.toString().contains("01020304"))
    }

    @Test
    fun baaUsesOrderedLengthDelimitedLeafAndIntermediate() {
        val first = MfiAuthenticationIdentity.capture(
            FakeAuthenticator(
                certificateType = MfiCertificateType.BAA,
                certificate = byteArrayOf(99),
                baa = BaaCertificatePair(byteArrayOf(1, 2), byteArrayOf(3, 4)),
            ),
        )
        val changedOrder = MfiAuthenticationIdentity.capture(
            FakeAuthenticator(
                certificateType = MfiCertificateType.BAA,
                certificate = byteArrayOf(99),
                baa = BaaCertificatePair(byteArrayOf(3, 4), byteArrayOf(1, 2)),
            ),
        )
        val changedBoundary = MfiAuthenticationIdentity.capture(
            FakeAuthenticator(
                certificateType = MfiCertificateType.BAA,
                certificate = byteArrayOf(99),
                baa = BaaCertificatePair(byteArrayOf(1), byteArrayOf(2, 3, 4)),
            ),
        )

        assertNotEquals(first, changedOrder)
        assertNotEquals(first, changedBoundary)
    }

    @Test
    fun certificateTypeIsDomainSeparatedAndInvalidMaterialFailsClosed() {
        val bytes = byteArrayOf(5, 6, 7)
        val mfi = MfiAuthenticationIdentity.capture(FakeAuthenticator(MfiCertificateType.MFI, bytes))
        val baa = MfiAuthenticationIdentity.capture(
            FakeAuthenticator(
                certificateType = MfiCertificateType.BAA,
                certificate = bytes,
                baa = BaaCertificatePair(byteArrayOf(5), byteArrayOf(6, 7)),
            ),
        )

        assertNotEquals(mfi, baa)
        assertThrows(IllegalArgumentException::class.java) {
            MfiAuthenticationIdentity.capture(FakeAuthenticator(MfiCertificateType.MFI, ByteArray(0)))
        }
    }

    @Test
    fun sessionBoundBaaRejectsWireCertificateAndPairFromDifferentSnapshots() {
        val inconsistent = FakeAuthenticator(
            certificateType = MfiCertificateType.BAA,
            certificate = byteArrayOf(9, 9, 9),
            baa = BaaCertificatePair(byteArrayOf(1), byteArrayOf(2)),
        )

        assertThrows(MfiInvalidDataException::class.java) {
            SessionBoundMfiAuthenticator.capture(inconsistent)
        }
    }

    @Test
    fun closeCannotZeroizeCertificateStateDuringAnActiveSigningCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeFinished = AtomicBoolean(false)
        val source = object : MfiAuthenticator {
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1, 2, 3)
            override fun signChallenge(challenge: ByteArray): ByteArray {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return challenge.copyOf()
            }
        }
        val bound = SessionBoundMfiAuthenticator.capture(source)
        val signing = Thread { bound.signChallenge(byteArrayOf(7, 8)) }
        val closing = Thread {
            closeStarted.countDown()
            bound.close()
            closeFinished.set(true)
        }

        signing.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        closing.start()
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        Thread.sleep(25)
        assertFalse(closeFinished.get())
        release.countDown()
        signing.join(2_000)
        closing.join(2_000)

        assertFalse(signing.isAlive)
        assertFalse(closing.isAlive)
        assertThrows(IllegalStateException::class.java) { bound.readCertificate() }
    }

    private class FakeAuthenticator(
        override val certificateType: MfiCertificateType,
        certificate: ByteArray,
        private val baa: BaaCertificatePair? = null,
    ) : MfiAuthenticator {
        private val certificate = certificate.copyOf()

        override fun protocolMajor(): Int = 1

        override fun readCertificate(maximumOutputLength: Int): ByteArray = certificate.copyOf()

        override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)

        override fun baaCertificates(): BaaCertificatePair = requireNotNull(baa)
    }
}
