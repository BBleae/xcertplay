package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Type-3 use admission is one exact, live child, not a liveness probe plus a later submission. */
class UltraProfileSceneUseAttemptTest {
    @Test
    fun sameChildAndThreadCanNestWithoutBorrowingRevocationAuthority() {
        fixture().use { fixture ->
            val child = fixture.runtimeLease
            assertEquals(
                UltraProfileSceneUseAttempt.Admitted("outer"),
                child.tryWithSceneSubmissionUse {
                    assertEquals(
                        UltraProfileSceneUseAttempt.Admitted("inner"),
                        child.tryWithSceneSubmissionUse { "inner" },
                    )
                    "outer"
                },
            )
            assertEquals(
                UltraProfileSceneUseAttempt.Admitted(7),
                child.tryWithSceneSubmissionUse { 7 },
            )
        }
    }

    @Test
    fun ordinaryParentProbeReportsBusyWithoutRevokingTheChild() {
        fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.setNextParentProbeHook {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                val probe = executor.submit(Callable { fixture.probeParentLease() })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(
                    UltraProfileSceneUseAttempt.Busy,
                    fixture.runtimeLease.tryWithSceneSubmissionUse { "not run" },
                )
                release.countDown()
                assertTrue(probe.get(5, TimeUnit.SECONDS))
                assertEquals(
                    UltraProfileSceneUseAttempt.Admitted("live"),
                    fixture.runtimeLease.tryWithSceneSubmissionUse { "live" },
                )
                fixture.revoke()
                assertEquals(
                    UltraProfileSceneUseAttempt.Revoked,
                    fixture.runtimeLease.tryWithSceneSubmissionUse { "late" },
                )
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun exactChildNestedUseCannotLendAdmissionToAnotherChildDuringBusyProbe() {
        fixture().use { fixture ->
            val otherChild = fixture.openSecondRuntime()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.setNextParentProbeHook {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                assertEquals(
                    UltraProfileSceneUseAttempt.Admitted("first"),
                    fixture.runtimeLease.tryWithSceneSubmissionUse {
                        val probe = executor.submit(Callable { fixture.probeParentLease() })
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        try {
                            assertEquals(
                                UltraProfileSceneUseAttempt.Admitted("nested"),
                                fixture.runtimeLease.tryWithSceneSubmissionUse { "nested" },
                            )
                            assertEquals(
                                UltraProfileSceneUseAttempt.Busy,
                                otherChild.tryWithSceneSubmissionUse { "not run" },
                            )
                        } finally {
                            release.countDown()
                        }
                        assertTrue(probe.get(5, TimeUnit.SECONDS))
                        "first"
                    },
                )
                assertEquals(
                    UltraProfileSceneUseAttempt.Admitted("second"),
                    otherChild.tryWithSceneSubmissionUse { "second" },
                )
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun revokedReceiptSealsNewUsesAndWaitsForAnAdmittedUse() {
        fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val useReturned = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val admitted = executor.submit(Callable {
                    fixture.runtimeLease.tryWithSceneSubmissionUse {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        useReturned.countDown()
                        "presented"
                    }
                })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val revocationStarted = CountDownLatch(1)
                val revoked = executor.submit {
                    revocationStarted.countDown()
                    fixture.revoke()
                }
                assertTrue(revocationStarted.await(5, TimeUnit.SECONDS))
                assertEquals(UltraProfileSceneUseAttempt.Revoked, awaitRevoked(fixture))
                assertFalse("revoke must await the admitted handoff", revoked.isDone)
                release.countDown()
                assertEquals(
                    UltraProfileSceneUseAttempt.Admitted("presented"),
                    admitted.get(5, TimeUnit.SECONDS),
                )
                assertTrue(useReturned.await(5, TimeUnit.SECONDS))
                revoked.get(5, TimeUnit.SECONDS)
                assertEquals(
                    UltraProfileSceneUseAttempt.Revoked,
                    fixture.runtimeLease.tryWithSceneSubmissionUse { "late" },
                )
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    private fun awaitRevoked(fixture: AuthorizedUltraDisplayLeaseTestFixture):
        UltraProfileSceneUseAttempt<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val attempt = fixture.runtimeLease.tryWithSceneSubmissionUse { "too-late" }
            if (attempt == UltraProfileSceneUseAttempt.Revoked) return attempt
            Thread.yield()
        }
        error("Receipt revocation did not seal scene admission")
    }

    private fun fixture(): AuthorizedUltraDisplayLeaseTestFixture {
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) =
                "scene-use-exact-child".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        })
        val programJson = """{
          "identifier":"scene-use-test","version":1,"minimumSDKVersion":"1",
          "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
          "displays":[{"id":"main","type":"centerConsole","size":[800,480],
            "density":1,"allowManufacturerAppIcon":false,
            "requiresDarkAppearance":false,"layouts":[{"id":"primary",
              "layoutType":"primary","zones":[],"backgroundColor":"#111111",
              "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{}}]}]
        }""".trimIndent().encodeToByteArray()
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf(
                    UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                    UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to programJson,
                    UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
                    UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                        BplistCodec.encode(mapOf("Bundle" to "Phone")),
                    "CarPlayPhone.assets/layout.bin" to byteArrayOf(1),
                    UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(2),
                    UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(3),
                    UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(4),
                    UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(5),
                    UltraProfilePackageFiles.INFO_PLIST to
                        BplistCodec.encode(mapOf("Version" to "1")),
                ).forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val inspector = UltraProfilePackageInspector()
        val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
            UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding(
            "vehicle",
            "xcertplay-mfi-authentication-v1:${identity.certificateType.name}:${identity.sha256Hex()}",
            "asset", "ios", "accessory", candidate.contentDigest,
        )
        val profile = (inspector.verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, byteArrayOf(1)))
        } as UltraProfileVerificationResult.Verified).profile
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 1L)),
            UltraDisplayOutputId.Android("main"), UltraUiSyncDisplayId("main"),
        )
        val display = CarPlayThemeSchema.parseArcPackageJson(programJson).displays.single()
        val state = CarPlayThemeSceneState(emptyList())
        val compiled = CarPlayThemeProgramCompiler().compile(display, state)
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val assignment = CompiledUiSyncDisplayAssignment(
            packageDigest, 0, "main", DisplayType.CENTER_CONSOLE, DisplayDestination.MAIN,
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeProgramSelection(
                0, state, compiled.structuralProgram.structuralFingerprint,
            ),
            emptyList(),
        )
        return AuthorizedUltraDisplayLeaseTestFixture.open(
            profile, identity, listOf(output), listOf(assignment),
        )
    }
}
