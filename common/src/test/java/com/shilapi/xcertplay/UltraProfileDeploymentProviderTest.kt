package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustVerifier
import java.io.InputStream
import java.nio.file.Paths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraProfileDeploymentProviderTest {
    @Test fun runtimeIdentityCopiesDigestAndRedactsEveryStableIdentifier() {
        val source = ByteArray(32) { it.toByte() }
        val identity = UltraRuntimeIdentity(
            vehicleId = "vehicle-secret-17",
            authenticationKind = UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION,
            authenticationIdentitySha256 = source,
        )

        source.fill(0x7f)
        assertArrayEquals(ByteArray(32) { it.toByte() }, identity.authenticationIdentitySha256)

        val returned = identity.authenticationIdentitySha256
        returned.fill(0x33)
        assertArrayEquals(ByteArray(32) { it.toByte() }, identity.authenticationIdentitySha256)

        val rendered = identity.toString()
        assertEquals("UltraRuntimeIdentity(<redacted>)", rendered)
        assertFalse(rendered.contains(identity.vehicleId))
        assertFalse(rendered.contains(identity.authenticationKind.name))
        assertFalse(rendered.contains(ByteArray(32) { it.toByte() }.joinToString()))
    }

    @Test fun runtimeIdentityRejectsAmbiguousOrMalformedIdentityMaterial() {
        val digest = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) {
            UltraRuntimeIdentity(
                " ",
                UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION,
                digest,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraRuntimeIdentity(
                "vehicle\u0000id",
                UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION,
                digest,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraRuntimeIdentity(
                "e\u0301",
                UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION,
                digest,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraRuntimeIdentity(
                "vehicle",
                UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION,
                ByteArray(31),
            )
        }
    }

    @Test fun compositionRootAlwaysReturnsTheSingleDisabledProvider() {
        assertSame(
            DisabledUltraProfileDeploymentProvider,
            UltraProfileCompositionRoot.deploymentProvider(),
        )
        assertSame(
            UltraProfileCompositionRoot.deploymentProvider(),
            UltraProfileCompositionRoot.deploymentProvider(),
        )
    }

    @Test fun runtimeIdentityMapsTheCapturedMfiCertificateKindWithoutExposingBytes() {
        val mfi = MfiAuthenticationIdentity.capture(
            object : MfiAuthenticator {
                override val certificateType = MfiCertificateType.MFI
                override fun protocolMajor(): Int = 1
                override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(1, 2, 3)
                override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
            },
        )

        val identity = UltraRuntimeIdentity.from("vehicle", mfi)

        assertEquals(
            UltraRuntimeAuthenticationKind.MFI_ACCESSORY_AUTHENTICATION,
            identity.authenticationKind,
        )
        assertArrayEquals(mfi.sha256Bytes(), identity.authenticationIdentitySha256)
    }

    @Test fun disabledProviderReturnsOneStableNonSensitiveResultWithoutReadingCandidate() {
        val authenticationIdentity = MfiAuthenticationIdentity.capture(
            object : MfiAuthenticator {
                override val certificateType = MfiCertificateType.MFI
                override fun protocolMajor(): Int = 1
                override fun readCertificate(maximumOutputLength: Int) = byteArrayOf(5, 6, 7)
                override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
            },
        )
        val identity = UltraRuntimeIdentity.from("vehicle", authenticationIdentity)
        val request = UltraProfileActivationRequest(identity, authenticationIdentity)
        val trap = TrapInputStream()

        val acquired = DisabledUltraProfileDeploymentProvider.acquire(request)
        val staged = DisabledUltraProfileDeploymentProvider.stage(request, trap)

        assertSame(UltraProfileDeploymentResult.Unavailable, acquired)
        assertSame(acquired, staged)
        assertEquals(
            "authorized-ultra-profile-provider-unavailable",
            UltraProfileDeploymentResult.Unavailable.CODE,
        )
        assertEquals(0, trap.operations)
        assertFalse(trap.closed)
        val rendered = staged.toString()
        assertFalse(rendered.contains(identity.vehicleId))
        assertFalse(rendered.contains(identity.authenticationKind.name))
    }

    @Test fun storeRootIsTheFixedNormalizedImmediateNoBackupChild() {
        val noBackup = Paths.get("/private/app/data/no_backup/../no_backup")
        val expectedParent = noBackup.normalize()

        val root = UltraProfileCompositionRoot.storeRoot(noBackup)

        assertEquals(expectedParent.resolve("ultra-profile-v1"), root)
        assertEquals(expectedParent, root.parent)
        assertEquals(ULTRA_PROFILE_STORE_DIRECTORY_NAME, root.fileName.toString())
        assertTrue(root.startsWith(expectedParent))
        assertThrows(IllegalArgumentException::class.java) {
            UltraProfileCompositionRoot.storeRoot(Paths.get("relative/no_backup"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UltraProfileCompositionRoot.storeRoot(Paths.get("/"))
        }
    }

    @Test fun providerBoundaryHasNoDynamicImplementationOrTrustInjectionApi() {
        val forbiddenTypes = setOf(
            Class::class.java,
            String::class.java,
            java.nio.file.Path::class.java,
            UltraProfileStore::class.java,
            UltraProfileTrustVerifier::class.java,
        )
        val methods = UltraProfileDeploymentProvider::class.java.declaredMethods.toList()

        assertEquals(setOf("acquire", "stage"), methods.map { it.name }.toSet())
        methods.flatMap { it.parameterTypes.toList() }.forEach { parameter ->
            assertFalse("forbidden provider input: ${parameter.name}", parameter in forbiddenTypes)
        }
        assertEquals(
            emptyList<Class<*>>(),
            UltraProfileCompositionRoot::class.java.declaredMethods
                .flatMap { it.parameterTypes.toList() }
                .filter { it == Class::class.java || it == String::class.java },
        )
    }

    private class TrapInputStream : InputStream() {
        var operations = 0
        var closed = false

        override fun read(): Int {
            operations += 1
            throw AssertionError("disabled provider read candidate bytes")
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            operations += 1
            throw AssertionError("disabled provider read candidate bytes")
        }

        override fun skip(count: Long): Long {
            operations += 1
            throw AssertionError("disabled provider skipped candidate bytes")
        }

        override fun available(): Int {
            operations += 1
            throw AssertionError("disabled provider queried candidate bytes")
        }

        override fun close() {
            operations += 1
            closed = true
            throw AssertionError("disabled provider closed caller input")
        }
    }
}
