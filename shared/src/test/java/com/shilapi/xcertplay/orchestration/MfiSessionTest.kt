package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiSessionTest {
    @Test
    fun openCapturesCertificateIdentityExactlyOnceAndOwnsCloseable() {
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val session = MfiSession.open(
            authenticator(certificate = byteArrayOf(1, 2, 3), reads = reads),
            Closeable { closes.incrementAndGet() },
        )

        assertEquals(1, reads.get())
        assertEquals(64, session.authenticationIdentity.sha256Hex().length)
        session.close()
        session.close()
        assertEquals(1, closes.get())
        assertThrows(IllegalStateException::class.java) {
            session.client.readCertificate()
        }
    }

    @Test
    fun failedIdentityCaptureClosesBackingTransport() {
        val closes = AtomicInteger()
        assertThrows(IllegalArgumentException::class.java) {
            MfiSession.open(
                authenticator(certificate = ByteArray(0), reads = AtomicInteger()),
                Closeable { closes.incrementAndGet() },
            )
        }
        assertEquals(1, closes.get())
    }

    @Test
    fun failedConstructionCleanupRetainsExactBackingOwnerForRetry() {
        val closes = AtomicInteger()
        val backing = Closeable {
            if (closes.incrementAndGet() == 1) {
                throw IllegalStateException("construction cleanup failed")
            }
        }

        val failure = assertThrows(MfiSessionConstructionCleanupException::class.java) {
            MfiSession.open(
                authenticator(certificate = ByteArray(0), reads = AtomicInteger()),
                backing,
            )
        }

        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(1, failure.suppressed.size)
        assertEquals(1, closes.get())
        failure.close()
        failure.close()
        assertEquals(2, closes.get())
    }

    @Test
    fun fatalConstructionErrorKeepsItsTypeAndCarriesRetryableCleanupOwner() {
        val fatal = AssertionError("fatal capture failure")
        val closes = AtomicInteger()
        val source = object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray = throw fatal
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        }
        val backing = Closeable {
            if (closes.incrementAndGet() == 1) {
                throw IllegalStateException("fatal-path cleanup failed")
            }
        }

        val thrown = assertThrows(AssertionError::class.java) {
            MfiSession.open(source, backing)
        }

        assertSame(fatal, thrown)
        val cleanupOwner = thrown.suppressed
            .filterIsInstance<MfiSessionConstructionCleanupException>()
            .single()
        cleanupOwner.close()
        cleanupOwner.close()
        assertEquals(2, closes.get())
    }

    @Test
    fun sessionAuthenticationUsesTheSameFrozenCertificateThatWasBound() {
        val reads = AtomicInteger()
        val changing = object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor(): Int = 1
            override fun readCertificate(maximumOutputLength: Int): ByteArray =
                if (reads.incrementAndGet() == 1) byteArrayOf(1, 2, 3) else byteArrayOf(9, 9, 9)
            override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
        }

        val session = MfiSession.open(changing, null)

        assertEquals(1, reads.get())
        assertArrayEquals(byteArrayOf(1, 2, 3), session.client.readCertificate())
        assertArrayEquals(byteArrayOf(1, 2, 3), session.client.readCertificate())
        assertEquals("the underlying authenticator must not be reread", 1, reads.get())
    }

    @Test
    fun closeFailureRetainsTheExactBackingOwnerForRetry() {
        val closes = AtomicInteger()
        val backing = Closeable {
            if (closes.incrementAndGet() == 1) {
                throw IllegalStateException("backing close failed")
            }
        }
        val session = MfiSession.open(
            authenticator(certificate = byteArrayOf(4, 5, 6), reads = AtomicInteger()),
            backing,
        )

        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(1, closes.get())

        session.close()
        session.close()
        assertEquals(2, closes.get())
    }

    private fun authenticator(
        certificate: ByteArray,
        reads: AtomicInteger,
    ): MfiAuthenticator = object : MfiAuthenticator {
        override val certificateType: MfiCertificateType = MfiCertificateType.MFI

        override fun protocolMajor(): Int = 1

        override fun readCertificate(maximumOutputLength: Int): ByteArray {
            reads.incrementAndGet()
            return certificate.copyOf()
        }

        override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
    }
}
