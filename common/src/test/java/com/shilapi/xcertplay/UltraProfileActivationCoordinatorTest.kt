package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.caf.CafRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandler
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistration
import com.shilapi.xcertplay.airplay.rcs.caf.CafPluginRegistry
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolSession
import com.shilapi.xcertplay.airplay.rcs.caf.CafSchemaBoundPluginHandler
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecodeResult
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecoder
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAvailability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputBatch
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputSessionContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererCapability
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererFactoryLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererSessionLease
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererScreenCandidate
import com.shilapi.xcertplay.airplay.rcs.uisync.RendererReadinessReceipt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBoundedPresentationExecutor
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererIngressSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationHandoff
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramCompiler
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeResolvedStyleWitness
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneState
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralFingerprint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemePrimarySelection
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralSource
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.util.ArrayDeque
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraProfileActivationCoordinatorTest {
    private val manifestIssuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
        ByteArray(32) { 0x51 },
    )

    @Test fun productCoordinatorBindsOnlyItsSelectedProviderAndRetiresExactProofLease() {
        val provider = object : UltraProfileDeploymentProvider,
            UltraProfilePhysicalGenerationProofConsumer {
            var source: ((UltraProfileSessionToken) -> UltraProfilePhysicalGenerationProof?)? = null
            var releases = 0

            override fun acquire(request: UltraProfileActivationRequest):
                UltraProfileDeploymentResult = UltraProfileDeploymentResult.Unavailable

            override fun stage(request: UltraProfileActivationRequest,
                candidateArchive: InputStream): UltraProfileDeploymentResult =
                UltraProfileDeploymentResult.Unavailable

            override fun bindPhysicalGenerationProof(
                source: (UltraProfileSessionToken) -> UltraProfilePhysicalGenerationProof?,
            ): Closeable {
                check(this.source == null)
                this.source = source
                return Closeable {
                    check(this.source === source)
                    this.source = null
                    releases++
                }
            }
        }
        val constructor = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
            UltraProfileDeploymentProvider::class.java,
            AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
        ).apply { isAccessible = true }
        val coordinator = constructor.newInstance(provider, manifestIssuer)
        assertTrue(provider.source != null)
        coordinator.close()
        assertNull(provider.source)
        assertEquals(1, provider.releases)
        coordinator.close()
        assertEquals(1, provider.releases)
    }

    @Test
    fun physicalGenerationProofIsExactLiveLineageNotEquivalentMfiIdentity() {
        val authenticationIdentity = identity(88)
        val firstReceipt = receipt(authenticationIdentity)
        val secondReceipt = receipt(authenticationIdentity)
        val coordinator = UltraProfileActivationCoordinator()
        val first = coordinator.beginSession("vehicle", firstReceipt).started()
        val proof = requireNotNull(coordinator.currentPhysicalGenerationProof(first))
        assertTrue(proof.matchesToken(first))
        assertEquals(authenticationIdentity, proof.authenticationIdentity)
        assertEquals(UltraRuntimeIdentity.from("vehicle", authenticationIdentity),
            proof.runtimeIdentity)
        assertTrue(proof.toString().contains("<redacted>"))
        assertThrows(IllegalArgumentException::class.java) {
            UltraProfilePhysicalGenerationProof.issue(
                Any(), proof.runtimeIdentity, proof.authenticationIdentity, first, Any(),
            )
        }

        val second = coordinator.beginSession("vehicle", secondReceipt).started()
        val successorProof = requireNotNull(coordinator.currentPhysicalGenerationProof(second))
        assertFalse(successorProof.matchesToken(first))
        assertFalse(proof.sameLineage(successorProof))
        assertEquals(proof.runtimeIdentity, successorProof.runtimeIdentity)
        assertEquals(proof.authenticationIdentity, successorProof.authenticationIdentity)
        assertEquals(null, coordinator.currentPhysicalGenerationProof(first))
        coordinator.close()
        assertEquals(null, coordinator.currentPhysicalGenerationProof(second))
    }

    @Test
    fun physicalGenerationProbeIsBusyThenCurrentAcrossCommittedAa05Renewal() {
        val handoff = receiptHandoffFixture(identity(89))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", handoff.predecessor).started()
        val active = coordinator.commit(
            coordinator.prepare(token, handoff.predecessor).prepared(),
            handoff.predecessor,
        ).activated()
        val before = (coordinator.physicalGenerationStatus(token) as
            UltraProfilePhysicalGenerationStatus.Current).proof
        assertTrue(before.matchesToken(token))
        assertFalse(coordinator.physicalGenerationStatus(token).toString().contains("vehicle"))

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newSingleThreadExecutor()
        try {
            val probing = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(UltraProfilePhysicalGenerationStatus.Busy,
                coordinator.physicalGenerationStatus(token))
            assertNull(coordinator.currentPhysicalGenerationProof(token))
            assertEquals(UltraProfilePhysicalGenerationStatus.Revoked,
                coordinator.physicalGenerationStatus(
                    UltraProfileSessionToken.create(Any(), token.generation),
                ))
            release.countDown()
            assertTrue(probing.get(2, TimeUnit.SECONDS))
            val afterProbe = (coordinator.physicalGenerationStatus(token) as
                UltraProfilePhysicalGenerationStatus.Current).proof
            assertTrue(before.sameLineage(afterProbe))

            lateinit var renewal: PreparedUltraProfileSessionRenewal
            handoff.handoff.commit(handoff.successor) { promoted ->
                renewal = coordinator.prepareSessionRenewal(
                    token, handoff.predecessor, promoted,
                )
            }
            // The live provisional successor already preserves this exact token's lineage.
            val duringRenewal = (coordinator.physicalGenerationStatus(token) as
                UltraProfilePhysicalGenerationStatus.Current).proof
            assertTrue(before.sameLineage(duringRenewal))
            renewal.commit()
            val afterRenewal = (coordinator.physicalGenerationStatus(token) as
                UltraProfilePhysicalGenerationStatus.Current).proof
            assertTrue(before.sameLineage(afterRenewal))
            assertTrue(active.isLive)
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun concurrentLivenessProbeWaitsForAnotherThreadsProbeInsteadOfReportingDead() {
        // Live/load regression: a local-canvas producer's liveness probe briefly holds the
        // reservation; a concurrent activation/first-frame probe must not read that as revoked.
        val receipt = receipt(identity(92))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            val profile = verifiedProfile("vehicle", request.authenticationIdentity)
            val caf = authorizedCafProvisioning()
            val cafContract = CompiledCafRuntimeContract.from(
                requireNotNull(caf.authorizationSnapshot()),
            )
            preparation = FakePreparation(
                request = request,
                verifiedProfile = profile,
                compiledManifestOverride = manifestIssuer.issue(
                    request,
                    profile,
                    uiSync = null,
                    caf = cafContract,
                ),
                cafProvisioning = caf,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(), receipt,
        ).activated()
        val child = (active.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newFixedThreadPool(3)
        try {
            val firstProbe = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val childProbe = executor.submit<Boolean> { child.isLive }
            val leaseProbe = executor.submit<Boolean> { active.isLive }
            // Both concurrent probes are parked behind the foreign probe, not answered "dead".
            Thread.sleep(50)
            assertFalse(childProbe.isDone)
            assertFalse(leaseProbe.isDone)
            release.countDown()
            assertTrue(firstProbe.get(2, TimeUnit.SECONDS))
            assertTrue(childProbe.get(2, TimeUnit.SECONDS))
            assertTrue(leaseProbe.get(2, TimeUnit.SECONDS))
            assertTrue(child.isLive)
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun concurrentLivenessProbeStillFailsClosedWhenTheForeignProbeOutlastsTheBound() {
        val receipt = receipt(identity(93))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            val profile = verifiedProfile("vehicle", request.authenticationIdentity)
            val caf = authorizedCafProvisioning()
            val cafContract = CompiledCafRuntimeContract.from(
                requireNotNull(caf.authorizationSnapshot()),
            )
            preparation = FakePreparation(
                request = request,
                verifiedProfile = profile,
                compiledManifestOverride = manifestIssuer.issue(
                    request,
                    profile,
                    uiSync = null,
                    caf = cafContract,
                ),
                cafProvisioning = caf,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(), receipt,
        ).activated()
        val child = (active.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstProbe = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertFalse(child.isLive) // bounded wait expires: fail closed, as before
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
            release.countDown()
            assertTrue(firstProbe.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun physicalGenerationIsRevokedWhenEndSessionInterruptsBusyProbe() {
        val receipt = receipt(identity(90))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(), receipt,
        ).activated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newFixedThreadPool(2)
        try {
            val probing = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(UltraProfilePhysicalGenerationStatus.Busy,
                coordinator.physicalGenerationStatus(token))
            val ending = executor.submit<UltraProfileActivationDenialCode?> {
                coordinator.endSession(token, receipt)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (coordinator.physicalGenerationStatus(token) !=
                UltraProfilePhysicalGenerationStatus.Revoked && System.nanoTime() < deadline
            ) Thread.yield()
            assertEquals(UltraProfilePhysicalGenerationStatus.Revoked,
                coordinator.physicalGenerationStatus(token))
            assertFalse(ending.isDone)
            release.countDown()
            assertFalse(probing.get(2, TimeUnit.SECONDS))
            assertNull(ending.get(2, TimeUnit.SECONDS))
            assertEquals(UltraProfilePhysicalGenerationStatus.Revoked,
                coordinator.physicalGenerationStatus(token))
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun physicalGenerationReceiptLossIsRevokedBeforeBusyProbeCompletes() {
        val receipt = receipt(identity(91))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(), receipt,
        ).activated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newSingleThreadExecutor()
        try {
            val probing = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(UltraProfilePhysicalGenerationStatus.Busy,
                coordinator.physicalGenerationStatus(token))
            val markRevoked = receipt.javaClass.declaredMethods.single { method ->
                method.name.startsWith("markRevokedState") && method.parameterCount == 0
            }.apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val delayedCallback = markRevoked.invoke(receipt) as (() -> Unit)?
            assertEquals(UltraProfilePhysicalGenerationStatus.Revoked,
                coordinator.physicalGenerationStatus(token))
            assertNull(coordinator.currentPhysicalGenerationProof(token))
            release.countDown()
            assertFalse(probing.get(2, TimeUnit.SECONDS))
            requireNotNull(delayedCallback).invoke()
            assertEquals(UltraProfilePhysicalGenerationStatus.Revoked,
                coordinator.physicalGenerationStatus(token))
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun defaultCompositionRemainsUnavailableAfterARealReceiptIsPresented() {
        val receipt = receipt(identity(1))
        val coordinator = UltraProfileActivationCoordinator()
        val session = coordinator.beginSession("vehicle", receipt).started()

        val prepared = coordinator.prepare(session, receipt).denied()

        assertEquals(UltraProfileActivationDenialCode.PROVIDER_UNAVAILABLE, prepared)
        coordinator.close()
    }

    @Test
    fun oneAa05ReceiptCannotBeConsumedByTwoCoordinators() {
        val receipt = receipt(identity(27))
        val first = UltraProfileActivationCoordinator()
        val second = UltraProfileActivationCoordinator()

        first.beginSession("vehicle", receipt).started()
        assertEquals(
            UltraProfileActivationDenialCode.STALE_RECEIPT,
            second.beginSession("vehicle", receipt).denied(),
        )

        first.close()
        second.close()
    }

    @Test
    fun onlyTheExactCurrentReceiptCanPrepareAndCommitTheVerifiedProfile() {
        val authenticationIdentity = identity(2)
        val currentReceipt = receipt(authenticationIdentity)
        val sameCertificateDifferentReceipt = receipt(authenticationIdentity)
        val profile = verifiedProfile(
            "vehicle",
            authenticationIdentity,
            proof = byteArrayOf(7, 8, 9),
        )
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(request, profile)
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", currentReceipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.STALE_RECEIPT,
            coordinator.prepare(token, sameCertificateDifferentReceipt).denied(),
        )
        val prepared = coordinator.prepare(token, currentReceipt).prepared()
        assertEquals(
            UltraProfileActivationDenialCode.STALE_RECEIPT,
            coordinator.commit(prepared, sameCertificateDifferentReceipt).denied(),
        )

        val active = coordinator.commit(prepared, currentReceipt).activated()

        assertTrue(active.isLive)
        assertEquals(prepared.verificationIdentity, active.verificationIdentity)
        assertEquals(prepared.verificationIdentity, preparation.committedIdentity)
        assertFalse(preparation.lease.closed)
        assertEquals(
            UltraProfileActivationDenialCode.ALREADY_ACTIVE,
            coordinator.prepare(token, currentReceipt).denied(),
        )
        coordinator.close()
        assertTrue(preparation.lease.closed)
        assertFalse(active.isLive)
    }

    @Test
    fun reconnectRevokesPreparedAndActiveStateAndNeverAcceptsAUsedReceiptAgain() {
        val firstReceipt = receipt(identity(3))
        val secondReceipt = receipt(identity(4))
        val preparations = ArrayList<FakePreparation>()
        val coordinator = coordinator { request ->
            val candidate = FakePreparation(
                request,
                verifiedProfile(
                    request.runtimeIdentity.vehicleId,
                    request.authenticationIdentity,
                ),
            )
            preparations += candidate
            UltraProfileBackendPrepareResult.Prepared(candidate)
        }
        val firstToken = coordinator.beginSession("vehicle-a", firstReceipt).started()
        val stalePreparation = coordinator.prepare(firstToken, firstReceipt).prepared()

        val secondToken = coordinator.beginSession("vehicle-b", secondReceipt).started()

        assertTrue(preparations[0].closed)
        assertTrue(stalePreparation.revoked)
        assertEquals(
            UltraProfileActivationDenialCode.STALE_GENERATION,
            coordinator.commit(stalePreparation, firstReceipt).denied(),
        )
        assertEquals(
            UltraProfileActivationDenialCode.STALE_RECEIPT,
            coordinator.beginSession("vehicle-a", firstReceipt).denied(),
        )

        val secondPreparation = coordinator.prepare(secondToken, secondReceipt).prepared()
        val secondLease = coordinator.commit(secondPreparation, secondReceipt).activated()
        val thirdReceipt = receipt(identity(5))
        coordinator.beginSession("vehicle-c", thirdReceipt).started()

        assertTrue(preparations[1].lease.closed)
        assertFalse(secondLease.isLive)
        coordinator.close()
    }

    @Test
    fun providerCannotSubstituteVehicleOrCertificateIdentity() {
        val receipt = receipt(identity(6))
        val wrongAuthenticationIdentity = identity(7)
        val wrongVehiclePreparation = arrayOfNulls<FakePreparation>(1)
        val wrongVehicle = coordinator { request ->
            FakePreparation(
                request,
                verifiedProfile("different-vehicle", request.authenticationIdentity),
            ).also {
                wrongVehiclePreparation[0] = it
            }.let(UltraProfileBackendPrepareResult::Prepared)
        }
        val wrongVehicleToken = wrongVehicle.beginSession("vehicle", receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH,
            wrongVehicle.prepare(wrongVehicleToken, receipt).denied(),
        )
        assertTrue(requireNotNull(wrongVehiclePreparation[0]).closed)
        wrongVehicle.close()

        val secondReceipt = receipt(identity(8))
        val wrongCertificatePreparation = arrayOfNulls<FakePreparation>(1)
        val wrongCertificate = coordinator { request ->
            FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
                authenticationIdentity = wrongAuthenticationIdentity,
            ).also {
                wrongCertificatePreparation[0] = it
            }.let(UltraProfileBackendPrepareResult::Prepared)
        }
        val wrongCertificateToken =
            wrongCertificate.beginSession("vehicle", secondReceipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH,
            wrongCertificate.prepare(wrongCertificateToken, secondReceipt).denied(),
        )
        assertTrue(requireNotNull(wrongCertificatePreparation[0]).closed)
        wrongCertificate.close()

        val profileBindingReceipt = receipt(identity(25))
        val otherProfileIdentity = identity(26)
        lateinit var wrongProfileBindingPreparation: FakePreparation
        val wrongProfileBinding = coordinator { request ->
            wrongProfileBindingPreparation = FakePreparation(
                request,
                verifiedProfile("vehicle", otherProfileIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(wrongProfileBindingPreparation)
        }
        val profileBindingToken =
            wrongProfileBinding.beginSession("vehicle", profileBindingReceipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH,
            wrongProfileBinding.prepare(profileBindingToken, profileBindingReceipt).denied(),
        )
        assertTrue(wrongProfileBindingPreparation.closed)
        wrongProfileBinding.close()
    }

    @Test
    fun providerAndCommitFailuresFailClosedAndReleasePreparedResources() {
        val receipt = receipt(identity(9))
        val throwingProvider = coordinator {
            throw IOException("provider detail must not escape")
        }
        val token = throwingProvider.beginSession("vehicle", receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.PROVIDER_FAILED,
            throwingProvider.prepare(token, receipt).denied(),
        )
        throwingProvider.close()

        val commitReceipt = receipt(identity(10))
        lateinit var preparation: FakePreparation
        val commitFailure = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
                commitFailure = IOException("compiler detail must not escape"),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val commitToken = commitFailure.beginSession("vehicle", commitReceipt).started()
        val prepared = commitFailure.prepare(commitToken, commitReceipt).prepared()

        assertEquals(
            UltraProfileActivationDenialCode.COMMIT_FAILED,
            commitFailure.commit(prepared, commitReceipt).denied(),
        )
        assertTrue(preparation.closed)
        assertFalse(preparation.leaseEverPublished)
        commitFailure.close()
    }

    @Test
    fun reconnectBecomesTerminalWhenPreparedResourcesCannotBeRevoked() {
        val firstReceipt = receipt(identity(20))
        val secondReceipt = receipt(identity(21))
        val coordinator = coordinator { request ->
            UltraProfileBackendPrepareResult.Prepared(
                FakePreparation(
                    request,
                    verifiedProfile("vehicle-a", request.authenticationIdentity),
                    closeFailure = IOException("synthetic close failure"),
                ),
            )
        }
        val first = coordinator.beginSession("vehicle-a", firstReceipt).started()
        coordinator.prepare(first, firstReceipt).prepared()

        assertEquals(
            UltraProfileActivationDenialCode.REVOCATION_FAILED,
            coordinator.beginSession("vehicle-b", secondReceipt).denied(),
        )
        assertEquals(
            UltraProfileActivationDenialCode.CLOSED,
            coordinator.beginSession("vehicle-c", receipt(identity(22))).denied(),
        )
        coordinator.close()
    }

    @Test
    fun closeReportsABackendLeaseThatCouldNotBeRevoked() {
        val receipt = receipt(identity(23))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            UltraProfileBackendPrepareResult.Prepared(
                FakePreparation(
                    request,
                    verifiedProfile("vehicle", request.authenticationIdentity),
                    leaseCloseFailure = IOException("synthetic lease close failure"),
                ).also { preparation = it },
            )
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        coordinator.commit(coordinator.prepare(token, receipt).prepared(), receipt).activated()

        assertThrows(UltraProfileActivationRevocationException::class.java) {
            coordinator.close()
        }
        coordinator.close()
        assertEquals(2, preparation.lease.closeAttempts)
    }

    @Test
    fun failedReceiptRevocationPropagatesToMfiAndExactCallbackReentryRetriesRetirement() {
        val fixture = receiptFixture(identity(81))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            UltraProfileBackendPrepareResult.Prepared(
                FakePreparation(
                    request,
                    verifiedProfile("vehicle", request.authenticationIdentity),
                    leaseCloseFailure = IOException("synthetic lease close failure"),
                    leaseCloseFailureCount = 3,
                ).also { preparation = it },
            )
        }
        val token = coordinator.beginSession("vehicle", fixture.receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, fixture.receipt).prepared(),
            fixture.receipt,
        ).activated()

        val first = assertThrows(InvocationTargetException::class.java) {
            fixture.beginReplacement()
        }
        assertTrue(first.cause is IllegalStateException)
        assertEquals(2, preparation.lease.closeAttempts)
        assertFalse(active.isLive)
        assertEquals(
            UltraProfileActivationDenialCode.CLOSED,
            coordinator.beginSession("vehicle", receipt(identity(82))).denied(),
        )
        assertThrows(InvocationTargetException::class.java) { fixture.beginReplacement() }

        assertThrows(InvocationTargetException::class.java) {
            fixture.retryRevocationCleanup()
        }
        assertEquals(3, preparation.lease.closeAttempts)
        fixture.retryRevocationCleanup()
        assertEquals(4, preparation.lease.closeAttempts)
        coordinator.close()
    }

    @Test
    fun lifecycleCloseCanRetryFailedReceiptRevocationBeforeMfiReplaysCallback() {
        val fixture = receiptFixture(identity(83))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            UltraProfileBackendPrepareResult.Prepared(
                FakePreparation(
                    request,
                    verifiedProfile("vehicle", request.authenticationIdentity),
                    leaseCloseFailure = IOException("synthetic lease close failure"),
                    leaseCloseFailureCount = 3,
                ).also { preparation = it },
            )
        }
        val token = coordinator.beginSession("vehicle", fixture.receipt).started()
        coordinator.commit(
            coordinator.prepare(token, fixture.receipt).prepared(),
            fixture.receipt,
        ).activated()

        assertThrows(InvocationTargetException::class.java) { fixture.beginReplacement() }
        assertThrows(UltraProfileActivationRevocationException::class.java) {
            coordinator.close()
        }
        assertEquals(3, preparation.lease.closeAttempts)
        coordinator.close()
        assertEquals(4, preparation.lease.closeAttempts)
        fixture.retryRevocationCleanup()
        assertEquals(4, preparation.lease.closeAttempts)
    }

    @Test
    fun synchronousReceiptRevocationCannotAcknowledgeAnInFlightOperation() {
        val fixture = receiptFixture(identity(84))
        var callbackFailure: InvocationTargetException? = null
        var prematureRetryFailure: InvocationTargetException? = null
        val coordinator = coordinator {
            callbackFailure = assertThrows(InvocationTargetException::class.java) {
                fixture.beginReplacement()
            }
            prematureRetryFailure = assertThrows(InvocationTargetException::class.java) {
                fixture.retryRevocationCleanup()
            }
            UltraProfileBackendPrepareResult.Unavailable
        }
        val token = coordinator.beginSession("vehicle", fixture.receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.CLOSED,
            coordinator.prepare(token, fixture.receipt).denied(),
        )
        assertTrue(callbackFailure?.cause is IllegalStateException)
        assertTrue(prematureRetryFailure?.cause is IllegalStateException)
        assertThrows(InvocationTargetException::class.java) { fixture.beginReplacement() }
        fixture.retryRevocationCleanup()
        coordinator.close()
    }

    @Test
    fun newActivationIsBusyUntilReceiptRevocationFinishesRetiringItsLease() {
        val fixture = receiptFixture(identity(85))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            UltraProfileBackendPrepareResult.Prepared(
                FakePreparation(
                    request,
                    verifiedProfile("vehicle", request.authenticationIdentity),
                ).also { preparation = it },
            )
        }
        val token = coordinator.beginSession("vehicle", fixture.receipt).started()
        coordinator.commit(
            coordinator.prepare(token, fixture.receipt).prepared(),
            fixture.receipt,
        ).activated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.closeEntered = entered
        preparation.lease.closeRelease = release
        val executor = Executors.newSingleThreadExecutor()
        try {
            val revocation = executor.submit { fixture.beginReplacement() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(
                UltraProfileActivationDenialCode.BUSY,
                coordinator.beginSession("vehicle-next", receipt(identity(86))).denied(),
            )
            release.countDown()
            revocation.get(5, TimeUnit.SECONDS)
            assertTrue(preparation.lease.closed)
            coordinator.close()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun mismatchedOrDeadCommittedLeaseIsClosedBeforeItCanBecomeVisible() {
        val receipt = receipt(identity(11))
        val otherReceipt = receipt(identity(12))
        val otherRuntimeIdentity = UltraRuntimeIdentity.from(
            "other-vehicle",
            otherReceipt.authenticationIdentity,
        )
        val otherIdentity = UltraProfileVerificationIdentity.from(
            otherRuntimeIdentity,
            otherReceipt.authenticationIdentity,
            verifiedProfile("other-vehicle", otherReceipt.authenticationIdentity),
        )
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
                leaseIdentityOverride = otherIdentity,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val prepared = coordinator.prepare(token, receipt).prepared()

        assertEquals(
            UltraProfileActivationDenialCode.PROFILE_BINDING_MISMATCH,
            coordinator.commit(prepared, receipt).denied(),
        )
        assertTrue(preparation.closed)
        assertTrue(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun aLeaseWithoutCompiledUiSyncOrCafProvisioningFailsClosed() {
        val receipt = receipt(identity(13))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(),
            receipt,
        ).activated()

        assertEquals(
            UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH,
            active.openRuntime().denied(),
        )
        assertTrue(preparation.lease.bundle.closed)
        assertTrue(preparation.lease.closed)
        assertFalse(active.isLive)
        coordinator.close()
    }

    @Test
    fun coordinatorOwnsTransferredRuntimeUntilAa05GenerationEnds() {
        val receipt = receipt(identity(41))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            val profile = verifiedProfile("vehicle", request.authenticationIdentity)
            val caf = authorizedCafProvisioning()
            val cafContract = CompiledCafRuntimeContract.from(
                requireNotNull(caf.authorizationSnapshot()),
            )
            preparation = FakePreparation(
                request = request,
                verifiedProfile = profile,
                compiledManifestOverride = manifestIssuer.issue(
                    request,
                    profile,
                    uiSync = null,
                    caf = cafContract,
                ),
                cafProvisioning = caf,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(),
            receipt,
        ).activated()

        val child = (active.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease

        assertTrue(child.isLive)
        assertTrue(child.runtimeIsLive())
        assertNull(child.sealPhysicalOutputAuthority()) // CAF-only never touches a sealer.
        assertEquals(null, coordinator.endSession(token, receipt))
        assertFalse(child.isLive)
        assertFalse(child.runtimeIsLive())
        assertTrue(preparation.lease.bundle.closed)
        assertTrue(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun reentrantSceneSubmissionRevocationRetainsOwnersForCloseRetry() {
        val receipt = receipt(identity(89))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            val profile = verifiedProfile("vehicle", request.authenticationIdentity)
            val caf = authorizedCafProvisioning()
            preparation = FakePreparation(
                request = request,
                verifiedProfile = profile,
                compiledManifestOverride = manifestIssuer.issue(
                    request, profile, uiSync = null,
                    caf = CompiledCafRuntimeContract.from(
                        requireNotNull(caf.authorizationSnapshot()),
                    ),
                ),
                cafProvisioning = caf,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val lease = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(), receipt,
        ).activated()
        val child = (lease.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease

        child.withSceneSubmissionUse {
            assertThrows(UltraProfileActivationRevocationException::class.java) {
                coordinator.close()
            }
            assertFalse(preparation.lease.bundle.closed)
            assertFalse(preparation.lease.closed)
            assertThrows(IllegalStateException::class.java) {
                child.withSceneSubmissionUse { Unit }
            }
        }
        coordinator.close()
        assertTrue(preparation.lease.bundle.closed)
        assertTrue(preparation.lease.closed)
    }

    @Test
    fun equalButSeparatelyIssuedManifestCannotReplacePreparationManifestAtCommit() {
        val receipt = receipt(identity(42))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            val profile = verifiedProfile("vehicle", request.authenticationIdentity)
            val first = manifestIssuer.issue(
                request,
                profile,
                uiSync = null,
                caf = testCompiledCafContract(),
            )
            val equalButDistinct = manifestIssuer.issue(
                request,
                profile,
                uiSync = null,
                caf = testCompiledCafContract(),
            )
            assertEquals(first.manifestDigest, equalButDistinct.manifestDigest)
            assertFalse(first === equalButDistinct)
            preparation = FakePreparation(
                request = request,
                verifiedProfile = profile,
                compiledManifestOverride = first,
                leaseManifestOverride = equalButDistinct,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH,
            coordinator.commit(coordinator.prepare(token, receipt).prepared(), receipt).denied(),
        )
        assertTrue(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun manifestFromAnotherCompositionAuthorityCannotPrepare() {
        val receipt = receipt(identity(43))
        lateinit var preparation: FakePreparation
        val foreignIssuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
            ByteArray(32) { 0x52 },
        )
        val coordinator = coordinator { request ->
            val profile = verifiedProfile("vehicle", request.authenticationIdentity)
            val localManifest = manifestIssuer.issue(
                request,
                profile,
                uiSync = null,
                caf = testCompiledCafContract(),
            )
            val foreignManifest = foreignIssuer.issue(
                request,
                profile,
                uiSync = null,
                caf = testCompiledCafContract(),
            )
            assertNotEquals(localManifest.manifestDigest, foreignManifest.manifestDigest)
            preparation = FakePreparation(
                request = request,
                verifiedProfile = profile,
                compiledManifestOverride = foreignManifest,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH,
            coordinator.prepare(token, receipt).denied(),
        )
        assertTrue(preparation.closed)
        coordinator.close()
    }

    @Test
    fun manifestForAnotherVerifiedProfileCannotPrepare() {
        val receipt = receipt(identity(44))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            val actual = verifiedProfile(
                "vehicle",
                request.authenticationIdentity,
                proof = byteArrayOf(4, 4),
            )
            val other = verifiedProfile(
                "vehicle",
                request.authenticationIdentity,
                proof = byteArrayOf(5, 5),
            )
            preparation = FakePreparation(
                request = request,
                verifiedProfile = actual,
                compiledManifestOverride = manifestIssuer.issue(
                    request,
                    other,
                    uiSync = null,
                    caf = testCompiledCafContract(),
                ),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.RUNTIME_MANIFEST_MISMATCH,
            coordinator.prepare(token, receipt).denied(),
        )
        assertTrue(preparation.closed)
        coordinator.close()
    }

    @Test
    fun compiledUiSyncContractRejectsDuplicateDisplaysAndCanonicalizesPlanOrder() {
        val displayA = UltraUiSyncDisplayId("display-a")
        val displayB = UltraUiSyncDisplayId("display-b")
        val outputA = UltraDisplayGraphOutput(
            topologyDisplayId = UltraTopologyDisplayId(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
            ),
            outputId = UltraDisplayOutputId.Android("output-a"),
            uiSyncDisplayId = displayA,
        )
        val outputB = UltraDisplayGraphOutput(
            topologyDisplayId = UltraTopologyDisplayId(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
            ),
            outputId = UltraDisplayOutputId.Custom("output-b"),
            uiSyncDisplayId = displayB,
        )
        val ingressA = UiSyncRendererIngressSpec(
            UltraIngressId("ingress-a"),
            UltraAirPlayStreamIdentity(type = 111, streamConnectionId = "connection-a"),
        )
        val ingressB = UiSyncRendererIngressSpec(
            UltraIngressId("ingress-b"),
            UltraAirPlayStreamIdentity(type = 111, clientTypeUuid = "client-b"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            compiledUiSyncContract(
                displayIds = setOf(displayA.value),
                outputs = listOf(
                    outputA,
                    outputB.copy(uiSyncDisplayId = displayA),
                ),
                ingresses = emptyList(),
            )
        }

        val first = compiledUiSyncContract(
            displayIds = setOf(displayA.value, displayB.value),
            outputs = listOf(outputB, outputA),
            ingresses = listOf(ingressB, ingressA),
        )
        val second = compiledUiSyncContract(
            displayIds = setOf(displayB.value, displayA.value),
            outputs = listOf(outputA, outputB),
            ingresses = listOf(ingressA, ingressB),
        )

        assertEquals(first.outputs, second.outputs)
        assertEquals(first.ingressSpecs, second.ingressSpecs)
    }

    @Test
    fun issuerPinsExactVerifiedPackageRowsAndTypedOutputNamespacesInDigest() {
        val authentication = identity(61)
        val profile = verifiedProfile(
            "vehicle", authentication,
            phonePackageJson = twoMainDisplayPackage(),
        )
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val androidOutput = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 1L)),
            UltraDisplayOutputId.Android("same-text"), UltraUiSyncDisplayId("opaque-a"),
        )
        val customOutput = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 2L)),
            UltraDisplayOutputId.Custom("same-text"), UltraUiSyncDisplayId("opaque-b"),
        )
        val digest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        fun row(index: Int, output: UltraDisplayGraphOutput, instance: String) =
            CompiledUiSyncDisplayAssignment(
                digest, index, "profile-$index", DisplayType.CENTER_CONSOLE,
                DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId(instance)),
                output.topologyDisplayId, output.outputId,
                requireNotNull(output.uiSyncDisplayId),
                themeProgramSelection(profile, index),
                emptyList(),
            )
        fun contract(rows: List<CompiledUiSyncDisplayAssignment>) =
            CompiledUiSyncRuntimeContract(
                schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21),
                bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x22),
                semanticThemeProgramFingerprint =
                    deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, rows),
                rendererImplementationId = "test.renderer.v1",
                displayIds = setOf("opaque-a", "opaque-b"),
                outputs = listOf(androidOutput, customOutput),
                ingressSpecs = emptyList(),
                displayAssignments = rows,
            )
        val rows = listOf(row(0, androidOutput, "panel-a"), row(1, customOutput, "panel-b"))
        val correct = contract(rows)
        val manifest = manifestIssuer.issue(request, profile, correct, null)
        assertEquals(androidOutput.outputId.value, customOutput.outputId.value)
        assertNotEquals(androidOutput.outputId, customOutput.outputId)
        val reversed = manifestIssuer.issue(request, profile, contract(rows.reversed()), null)
        assertEquals(manifest.manifestDigest, reversed.manifestDigest)
        assertEquals(correct.semanticThemeProgramFingerprint,
            contract(rows.reversed()).semanticThemeProgramFingerprint)
        assertNotEquals(correct.semanticThemeProgramFingerprint,
            contract(listOf(rows[0], row(1, customOutput, "panel-c")))
                .semanticThemeProgramFingerprint)
        assertNotEquals(correct.semanticThemeProgramFingerprint,
            contract(listOf(row(0, customOutput, "panel-a"),
                row(1, androidOutput, "panel-b"))).semanticThemeProgramFingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile, CompiledUiSyncRuntimeContract(
                correct.schemaFingerprint, correct.bindingFingerprint,
                UiSyncSemanticThemeProgramFingerprint(ByteArray(32) { 0x23 }),
                correct.rendererImplementationId, correct.displayIds, correct.outputs,
                correct.ingressSpecs, correct.displayAssignments,
            ), null)
        }
        val changedPackage = twoMainDisplayPackage().decodeToString()
            .replace("#111111", "#101010").encodeToByteArray()
        val otherProfile = verifiedProfile("vehicle", authentication,
            phonePackageJson = changedPackage)
        val otherPackageDigest = otherProfile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val otherRows = rows.mapIndexed { index, original ->
            original.copyForTest(phonePackageDigest = otherPackageDigest,
                programSelection = themeProgramSelection(otherProfile, index))
        }
        assertNotEquals(correct.semanticThemeProgramFingerprint,
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(otherProfile, otherRows))
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, otherProfile, correct, null)
        }
        assertEquals(rows, manifest.displayAssignmentsFor(profile))
        assertNotEquals(manifest.manifestDigest,
            manifestIssuer.issue(request, profile,
                contract(listOf(rows[0], row(1, customOutput, "panel-c"))), null).manifestDigest)
        assertNotEquals(manifest.manifestDigest,
            manifestIssuer.issue(request, profile,
                contract(listOf(row(0, customOutput, "panel-a"),
                    row(1, androidOutput, "panel-b"))), null).manifestDigest)
        val secondLayout = rows[0].copyForTest(
            programSelection = themeProgramSelection(profile, 0, layoutIndex = 1),
        )
        assertEquals(rows[0].programSelection.structuralFingerprint,
            secondLayout.programSelection.structuralFingerprint)
        assertNotEquals(manifest.manifestDigest,
            manifestIssuer.issue(request, profile,
                contract(listOf(secondLayout, rows[1])), null).manifestDigest)
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile, contract(listOf(rows[0].copyForTest(
                programSelection = CompiledUiSyncThemeProgramSelection(
                    0, CarPlayThemeSceneState(),
                    CarPlayThemeStructuralFingerprint(ByteArray(32) { 0x66 }),
                ),
            ), rows[1])), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile, contract(listOf(rows[0].copyForTest(
                programSelection = CompiledUiSyncThemeProgramSelection(
                    2, CarPlayThemeSceneState(),
                    rows[0].programSelection.structuralFingerprint,
                ),
            ), rows[1])), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile, contract(listOf(rows[0].copyForTest(
                programSelection = CompiledUiSyncThemeProgramSelection(
                    0, CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0))),
                    rows[0].programSelection.structuralFingerprint,
                ),
            ), rows[1])), null)
        }

        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(listOf(rows[0].copyForTest(profileDisplayId = "wrong"), rows[1])), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(listOf(rows[0].copyForTest(profileDisplayType = DisplayType.CLUSTER),
                    rows[1])), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(listOf(rows[0].copyForTest(profileDisplayIndex = 2), rows[1])), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(rows.map { it.copyForTest(phonePackageDigest =
                    com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest(
                        ByteArray(32) { 0x55 },
                    )) }), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0], rows[0]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0], rows[1].copyForTest(
                profileDisplayIndex = 0, profileDisplayId = "profile-0",
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0], rows[1].copyForTest(destination = rows[0].destination)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0]))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0], rows[1].copyForTest(
                topologyDisplayId = rows[0].topologyDisplayId,
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0], rows[1].copyForTest(outputId = rows[0].outputId)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(listOf(rows[0].copyForTest(uiSyncDisplayId = rows[1].uiSyncDisplayId),
                rows[1].copyForTest(uiSyncDisplayId = rows[0].uiSyncDisplayId)))
        }
        val equivalentButSeparate = verifiedProfile(
            "vehicle", authentication, phonePackageJson = twoMainDisplayPackage(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            manifest.displayAssignmentsFor(equivalentButSeparate)
        }
    }

    @Test
    fun signedStaticVariantCatalogSelectsOnlyExactLayoutAndOpaquePaletteIntent() {
        val authentication = identity(91)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = twoMainDisplayPackage(),
        )
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 91L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val firstIntent = CompiledUiSyncThemeIntent("first", emptyMap(), "opaque-palette-a")
        val secondIntent = CompiledUiSyncThemeIntent("second", emptyMap(), "opaque-palette-b")
        fun assignment(
            primary: Int,
            first: CompiledUiSyncThemeIntent = firstIntent,
            second: CompiledUiSyncThemeIntent = secondIntent,
            secondProgram: CompiledUiSyncThemeProgramSelection =
                themeProgramSelection(profile, 0, layoutIndex = 1),
        ): CompiledUiSyncDisplayAssignment {
            val intents = listOf(first, second)
            val primaryProgram = themeProgramSelection(profile, 0, layoutIndex = primary)
            val alternate = if (primary == 0) secondProgram else
                themeProgramSelection(profile, 0, layoutIndex = 0)
            return CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
                DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
                output.topologyDisplayId, output.outputId,
                requireNotNull(output.uiSyncDisplayId), primaryProgram, emptyList(),
                primaryIntent = intents[primary],
                additionalVariants = listOf(CompiledUiSyncThemeVariant(
                    intents[1 - primary], alternate, emptyList(),
                )),
            )
        }
        val primary = assignment(primary = 0)
        val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(primary),
        )
        val provisioning = staticAuthorizedUiSyncProvisioning(output, program = program)
        val snapshot = requireNotNull(provisioning.authorizationSnapshot())
        fun issue(row: CompiledUiSyncDisplayAssignment) = manifestIssuer.issue(
            request, profile, CompiledUiSyncRuntimeContract(
                snapshot.schemaFingerprint, snapshot.bindingFingerprint,
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(row)),
                snapshot.rendererImplementationId, snapshot.displayIds,
                snapshot.outputs, snapshot.ingressSpecs, listOf(row),
                snapshot.physicalOutputRequirement,
            ), null,
        )
        val manifest = issue(primary)
        // The primary row remains the legacy executor's initial program, so changing it must
        // change the manifest even if both catalogs contain the same two intents.
        assertNotEquals(manifest.manifestDigest, issue(assignment(primary = 1)).manifestDigest)
        assertNotEquals(program, deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(assignment(primary = 1))))
        assertTrue(manifest.matchesProfile(request, profile, manifest.verificationIdentity))
        assertTrue(manifest.matchesProvisioning(snapshot, null))
        val driftedProvisioning = staticAuthorizedUiSyncProvisioning(output)
        val driftedSnapshot = requireNotNull(driftedProvisioning.authorizationSnapshot())
        assertFalse(manifest.matchesProvisioning(driftedSnapshot, null))
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                CompiledUiSyncRuntimeContract.from(driftedSnapshot, listOf(primary)), null)
        }
        driftedProvisioning.close()
        assertEquals(0, requireNotNull(manifest.selectThemeVariantFor(
            profile, requireNotNull(output.uiSyncDisplayId), firstIntent,
        )).programSelection.layoutIndex)
        assertEquals(1, requireNotNull(manifest.selectThemeVariantFor(
            profile, requireNotNull(output.uiSyncDisplayId), secondIntent,
        )).programSelection.layoutIndex)
        assertNull(manifest.selectThemeVariantFor(
            profile, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeIntent("second", emptyMap(), "different-palette"),
        ))
        assertNull(manifest.selectThemeVariantFor(
            profile, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeIntent("second", mapOf("zone" to "component"),
                "opaque-palette-b"),
        ))
        assertNull(manifest.selectThemeVariantFor(
            profile, UltraUiSyncDisplayId("foreign"), secondIntent,
        ))
        val equivalentButSeparate = verifiedProfile(
            "vehicle", authentication, phonePackageJson = twoMainDisplayPackage(),
        )
        assertNull(manifest.selectThemeVariantFor(
            equivalentButSeparate, requireNotNull(output.uiSyncDisplayId), firstIntent,
        ))
        assertFalse(manifest.matchesProfile(
            request, equivalentButSeparate, manifest.verificationIdentity,
        ))
        assertNotEquals(manifest.manifestDigest,
            issue(assignment(0, second = CompiledUiSyncThemeIntent(
                "second", emptyMap(), "another-signed-palette",
            ))).manifestDigest)
        assertNotEquals(program, deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(assignment(0, second = CompiledUiSyncThemeIntent(
                "second", emptyMap(), "another-signed-palette",
            )))))
        assertThrows(IllegalArgumentException::class.java) {
            issue(assignment(0, second = CompiledUiSyncThemeIntent(
                "wrong-layout", emptyMap(), "opaque-palette-b",
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            issue(assignment(0, second = CompiledUiSyncThemeIntent(
                "second", mapOf("zone" to "component"), "opaque-palette-b",
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            issue(assignment(0, secondProgram = CompiledUiSyncThemeProgramSelection(
                1, CarPlayThemeSceneState(),
                CarPlayThemeStructuralFingerprint(ByteArray(32) { 0x77 }),
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
                DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
                output.topologyDisplayId, output.outputId,
                requireNotNull(output.uiSyncDisplayId), themeProgramSelection(profile, 0),
                emptyList(), primaryIntent = firstIntent,
                additionalVariants = listOf(CompiledUiSyncThemeVariant(
                    firstIntent, themeProgramSelection(profile, 0, layoutIndex = 1), emptyList(),
                )),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
                DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
                output.topologyDisplayId, output.outputId,
                requireNotNull(output.uiSyncDisplayId), themeProgramSelection(profile, 0),
                emptyList(), additionalVariants = listOf(CompiledUiSyncThemeVariant(
                    secondIntent, themeProgramSelection(profile, 0, layoutIndex = 1), emptyList(),
                )),
            )
        }
        val duplicatedLayoutIds = verifiedProfile(
            "vehicle", authentication,
            phonePackageJson = twoMainDisplayPackage().decodeToString()
                .replace("\"id\":\"second\"", "\"id\":\"first\"").encodeToByteArray(),
        )
        val duplicateRequest = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val duplicateDigest = duplicatedLayoutIds.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(duplicateRequest, duplicatedLayoutIds,
                CompiledUiSyncRuntimeContract.from(snapshot, listOf(
                    CompiledUiSyncDisplayAssignment(
                        duplicateDigest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
                        DisplayDestination(DisplayDestinationRole.MAIN,
                            DisplayDestinationId("panel")),
                        output.topologyDisplayId, output.outputId,
                        requireNotNull(output.uiSyncDisplayId),
                        themeProgramSelection(duplicatedLayoutIds, 0), emptyList(),
                    ),
                )), null)
        }
        provisioning.close()
    }

    @Test
    fun signedV3CarouselCatalogSelectsExactComponentAndPaletteOnOneLayout() {
        val authentication = identity(191)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = v3CarouselPackage(),
            signedUiSyncCatalogJson = v3CarouselCatalog(),
        )
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 111L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val digest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val display = CarPlayThemeSchema.parseArcPackageJson(requireNotNull(
            profile.candidate.content(UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON),
        )).displays.single()
        fun variant(componentIndex: Int, componentId: String, paletteId: String,
            contentId: String): Pair<CompiledUiSyncThemeIntent, Pair<
            CompiledUiSyncThemeProgramSelection, CompiledUiSyncLocalCanvasAssignment>> {
            val state = CarPlayThemeSceneState(listOf(
                CarPlayThemePrimarySelection(0, 0, componentIndex),
            ))
            val program = CarPlayThemeProgramCompiler().compile(display, state).structuralProgram
            val slot = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .single { it.address.component.zone.layout.layoutIndex == 0 &&
                    it.address.component.componentIndex == componentIndex }
            return CompiledUiSyncThemeIntent(
                "main-carousel-canvas", mapOf("main-carousel-zone" to componentId), paletteId,
            ) to (CompiledUiSyncThemeProgramSelection(
                0, state, program.structuralFingerprint,
            ) to CompiledUiSyncLocalCanvasAssignment(slot.address, contentId))
        }
        val mapA = variant(0, "map", "palette-a", "canvas.main.bench")
        val mediaA = variant(1, "media", "palette-a", "canvas.main.carousel.media")
        val mapB = variant(0, "map", "palette-b", "canvas.main.carousel.map.alt")
        var completeValidationPasses = 0
        val observingIssuer = AuthorizedUltraCompiledRuntimeManifestIssuer(
            ByteArray(32) { 0x51 },
        ) { completeValidationPasses++ }
        fun assignment(
            first: Pair<CompiledUiSyncThemeIntent, Pair<
                CompiledUiSyncThemeProgramSelection, CompiledUiSyncLocalCanvasAssignment>> = mapA,
            others: List<Pair<CompiledUiSyncThemeIntent, Pair<
                CompiledUiSyncThemeProgramSelection, CompiledUiSyncLocalCanvasAssignment>>> =
                listOf(mediaA, mapB),
        ) = CompiledUiSyncDisplayAssignment(
            digest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            first.second.first, emptyList(), primaryIntent = first.first,
            additionalVariants = others.map {
                CompiledUiSyncThemeVariant(it.first, it.second.first, emptyList(),
                    localCanvasAssignments = listOf(it.second.second))
            },
            localCanvasAssignments = listOf(first.second.second),
        )
        fun issue(row: CompiledUiSyncDisplayAssignment): AuthorizedUltraCompiledRuntimeManifest {
            val rows = listOf(row)
            return observingIssuer.issue(request, profile, CompiledUiSyncRuntimeContract(
                schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x51),
                bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x52),
                semanticThemeProgramFingerprint =
                    deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, rows),
                rendererImplementationId = "test.renderer.signed-v3.v1",
                displayIds = setOf("opaque-panel"), outputs = listOf(output),
                ingressSpecs = emptyList(), displayAssignments = rows,
            ), null)
        }
        val manifest = issue(assignment())
        assertEquals(1, completeValidationPasses)
        assertTrue(manifest.matchesProfile(request, profile, manifest.verificationIdentity))
        val passesBeforeType3 = completeValidationPasses
        listOf(mapA, mediaA, mapB).forEach { variant ->
            val selected = requireNotNull(manifest.selectThemeVariantFor(
                profile, requireNotNull(output.uiSyncDisplayId), variant.first,
            ))
            assertEquals(0, selected.programSelection.layoutIndex)
            assertEquals(variant.first, selected.intent)
            assertEquals(variant.second.first.activePrimarySelections,
                selected.programSelection.activePrimarySelections)
            assertEquals(variant.second.second.contentId,
                selected.localCanvasAssignments.single().contentId)
            val command = (CarPlayUiSyncSemanticDecoder().decode(3, mapOf(
                "layoutID" to variant.first.layoutID,
                "activeComponents" to variant.first.activeComponents,
                "paletteID" to variant.first.paletteID,
            )) as CarPlayUiSyncSemanticDecodeResult.Decoded).command as
                CarPlayUiSyncSemanticCommand.LayoutChange
            repeat(8) {
                assertSame(selected, manifest.selectThemeVariantFor(
                    profile, requireNotNull(output.uiSyncDisplayId), command,
                ))
            }
        }
        assertNull(manifest.selectThemeVariantFor(profile, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeIntent("main-carousel-canvas",
                mapOf("main-carousel-zone" to "map"), "palette-unknown")))
        assertNull(manifest.selectThemeVariantFor(profile, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeIntent("main-carousel-canvas",
                mapOf("main-carousel-zone" to "unknown"), "palette-a")))
        assertNull(manifest.selectThemeVariantFor(profile, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeIntent("main-carousel-canvas", emptyMap(), "palette-a")))
        assertNull(manifest.selectThemeVariantFor(profile, UltraUiSyncDisplayId("foreign"),
            mapA.first))
        val separatelyVerified = verifiedProfile(
            "vehicle", authentication, phonePackageJson = v3CarouselPackage(),
            signedUiSyncCatalogJson = v3CarouselCatalog(),
        )
        assertEquals(profile.candidate.contentDigest, separatelyVerified.candidate.contentDigest)
        assertNull(manifest.selectThemeVariantFor(separatelyVerified,
            requireNotNull(output.uiSyncDisplayId), mapA.first))
        // The observed complete pass includes package SHA, JSON parse and scene compilation.
        // Repeated same/different exact type-3 intents never repeat any part of that pass.
        assertEquals(passesBeforeType3, completeValidationPasses)

        val runtimeAssignment = assignment()
        val runtimeProgram = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(runtimeAssignment),
        )
        val renderer = staticAuthorizedUiSyncProvisioning(output, program = runtimeProgram)
        val runtimeManifest = manifestIssuer.issue(
            request, profile, CompiledUiSyncRuntimeContract.from(
                requireNotNull(renderer.authorizationSnapshot()), listOf(runtimeAssignment),
            ), null,
        )
        val receiptFixture = receiptFixture(authentication)
        val coordinator = coordinator { activation ->
            UltraProfileBackendPrepareResult.Prepared(FakePreparation(
                activation, profile, compiledManifestOverride = runtimeManifest,
                uiSyncProvisioning = renderer,
            ))
        }
        val token = coordinator.beginSession("vehicle", receiptFixture.receipt).started()
        val lease = coordinator.commit(
            coordinator.prepare(token, receiptFixture.receipt).prepared(),
            receiptFixture.receipt,
        ).activated()
        val child = (lease.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease
        val mapCommand = (CarPlayUiSyncSemanticDecoder().decode(3, mapOf(
            "layoutID" to mapA.first.layoutID,
            "activeComponents" to mapA.first.activeComponents,
            "paletteID" to mapA.first.paletteID,
        )) as CarPlayUiSyncSemanticDecodeResult.Decoded).command as
            CarPlayUiSyncSemanticCommand.LayoutChange
        assertEquals(mapA.first, child.selectThemeVariantFor(
            profile, requireNotNull(output.uiSyncDisplayId), mapCommand,
        )?.intent)
        assertNull(child.selectThemeVariantFor(
            separatelyVerified, requireNotNull(output.uiSyncDisplayId), mapCommand,
        ))
        receiptFixture.beginReplacement()
        assertFalse(child.isLive)
        assertThrows(IllegalStateException::class.java) {
            child.selectThemeVariantFor(profile, requireNotNull(output.uiSyncDisplayId), mapCommand)
        }
        coordinator.close()

        assertThrows(IllegalArgumentException::class.java) {
            issue(assignment(first = mediaA, others = listOf(mapA, mapB)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            issue(assignment(first = mapA, others = listOf(mediaA, mediaA)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            issue(assignment(first = mapA.copy(second =
                mapA.second.copy(first = mediaA.second.first)), others = listOf(mediaA, mapB)))
        }
    }

    @Test
    fun signedV3StyledLayoutIssuesDistinctInertPaletteVariantsOnly() {
        val authentication = identity(193)
        val styledPackage = v3CarouselPackage().decodeToString().replace(
            "\"backgroundColor\":\"#101820\"",
            "\"layoutStyleID\":\"bench-style\",\"backgroundColor\":\"#101820\"",
        ).also { require("\"layoutStyleID\":\"bench-style\"" in it) }.encodeToByteArray()
        val signedTheme = """{
          "palettes":{
            "palette-a":{"id":"palette-a","displayName":"Bench A","colors":{},
              "representedColorScheme":{"single":{"color":"#101820"}},
              "sortIndex":0,"prominent":false},
            "palette-b":{"id":"palette-b","displayName":"Bench B","colors":{},
              "representedColorScheme":{"single":{"color":"#101820"}},
              "sortIndex":1,"prominent":false}},
          "layoutStyles":{"bench-style":{"palette-a":{},"palette-b":{}}},
          "defaultPaletteIDs":{"bench-style":"palette-a"},
          "defaultWallpaperIDs":{}
        }""".trimIndent()
        fun profile(theme: String, primaryPalette: String = "palette-a") = verifiedProfile(
            "vehicle", authentication, phonePackageJson = styledPackage,
            signedUiSyncCatalogJson = v3CarouselCatalog(primaryPalette),
            resolvedThemeJson = theme.encodeToByteArray(),
        )
        val verified = profile(signedTheme)
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 111L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val packageDigest = verified.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val display = CarPlayThemeSchema.parseArcPackageJson(styledPackage).displays.single()
        fun variant(componentIndex: Int, componentId: String, paletteId: String,
            contentId: String) = run {
            val state = CarPlayThemeSceneState(listOf(
                CarPlayThemePrimarySelection(0, 0, componentIndex),
            ))
            val program = CarPlayThemeProgramCompiler().compile(display, state).structuralProgram
            val slot = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .single { it.address.component.componentIndex == componentIndex }
            Triple(
                CompiledUiSyncThemeIntent("main-carousel-canvas",
                    mapOf("main-carousel-zone" to componentId), paletteId),
                CompiledUiSyncThemeProgramSelection(0, state,
                    program.structuralFingerprint),
                CompiledUiSyncLocalCanvasAssignment(slot.address, contentId),
            )
        }
        val mapA = variant(0, "map", "palette-a", "canvas.main.bench")
        val mediaA = variant(1, "media", "palette-a", "canvas.main.carousel.media")
        fun assignment(secondPalette: String = "palette-b",
            primaryPalette: String = "palette-a"):
            CompiledUiSyncDisplayAssignment {
            val primary = variant(0, "map", primaryPalette, "canvas.main.bench")
            val alternate = variant(0, "map", secondPalette,
                "canvas.main.carousel.map.alt")
            return CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
                DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
                output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
                primary.second, emptyList(), primaryIntent = primary.first,
                additionalVariants = listOf(mediaA, alternate).map {
                    CompiledUiSyncThemeVariant(it.first, it.second, emptyList(),
                        localCanvasAssignments = listOf(it.third))
                },
                localCanvasAssignments = listOf(primary.third),
            )
        }
        val selected = assignment()
        val semanticFingerprint = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            verified, listOf(selected),
        )
        fun contract(row: CompiledUiSyncDisplayAssignment) = CompiledUiSyncRuntimeContract(
            schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x71),
            bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x72),
            semanticThemeProgramFingerprint = semanticFingerprint,
            rendererImplementationId = "test.renderer.styled-v3.v1",
            displayIds = setOf("opaque-panel"), outputs = listOf(output),
            ingressSpecs = emptyList(), displayAssignments = listOf(row),
        )
        val manifest = manifestIssuer.issue(request, verified, contract(selected), null)
        assertTrue(manifest.matchesProfile(request, verified, manifest.verificationIdentity))
        listOf(mapA.first, mediaA.first,
            CompiledUiSyncThemeIntent("main-carousel-canvas",
                mapOf("main-carousel-zone" to "map"), "palette-b"),
        ).forEach { intent ->
            assertEquals(intent, requireNotNull(manifest.selectThemeVariantFor(
                verified, requireNotNull(output.uiSyncDisplayId), intent,
            )).intent)
        }
        val primaryB = assignment(secondPalette = "palette-a",
            primaryPalette = "palette-b")
        val primaryBInertProfile = profile(signedTheme, primaryPalette = "palette-b")
        val inertDenial = assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, primaryBInertProfile, contract(primaryB), null)
        }
        assertTrue(inertDenial.message.orEmpty().contains("signed default palette"))
        val complexDefaultTheme = signedTheme.replace(
            "\"displayName\":\"Bench A\",\"colors\":{}",
            "\"displayName\":\"Bench A\",\"colors\":{\"primary\":\"#ffffff\"}",
        )
        assertNotEquals(signedTheme, complexDefaultTheme)
        val primaryBComplexProfile = profile(complexDefaultTheme,
            primaryPalette = "palette-b")
        // Selected-only B is safe in isolation, but it cannot license an implicit initial
        // scene while the phone's signed default A has unmodeled paint semantics.
        assertEquals("palette-b", CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
            primaryBComplexProfile, 0, 0, "palette-b",
        ).selectedPaletteID)
        val complexDenial = assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, primaryBComplexProfile, contract(primaryB), null)
        }
        assertTrue(complexDenial.message.orEmpty().contains("signed default palette"))
        val primaryAComplexProfile = profile(complexDefaultTheme)
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, primaryAComplexProfile, contract(selected), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, verified, contract(assignment("palette-unsigned")), null)
        }
        val paletteBOffset = signedTheme.indexOf("\"palette-b\":{\"id\":\"palette-b\"")
        require(paletteBOffset >= 0)
        val mismatchedColorTheme = signedTheme.substring(0, paletteBOffset) +
            signedTheme.substring(paletteBOffset).replaceFirst(
                "\"color\":\"#101820\"", "\"color\":\"#ffffff\"",
            )
        assertNotEquals(signedTheme, mismatchedColorTheme)
        val mismatchedColor = profile(mismatchedColorTheme)
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, mismatchedColor, contract(selected), null)
        }
        val unknownPaletteTheme = signedTheme.replace(
            "\"sortIndex\":1,\"prominent\":false",
            "\"sortIndex\":1,\"prominent\":false,\"unknownDrawing\":true",
        )
        assertNotEquals(signedTheme, unknownPaletteTheme)
        val unknownPaletteField = profile(unknownPaletteTheme)
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, unknownPaletteField, contract(selected), null)
        }
    }

    @Test
    fun signedV1CatalogCannotBeReissuedWithForgedRoutePaletteOrIngress() {
        val authentication = identity(192)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = slotBindingPackage(),
            signedUiSyncCatalogJson = v1VideoCatalog(), signedUiSyncCatalogVersion = 1,
        )
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 112L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val digest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val display = CarPlayThemeSchema.parseArcPackageJson(requireNotNull(
            profile.candidate.content(UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON),
        )).displays.single()
        val slot = requireNotNull(CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
            display, 0, 0, state,
        ).proof).videoSlots.single().address
        val ingress = UiSyncRendererIngressSpec(UltraIngressId("ingress.video"),
            UltraAirPlayStreamIdentity(110), setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID)
        val program = themeProgramSelection(profile, 0, state = state)
        val intent = CompiledUiSyncThemeIntent("layout", emptyMap(), "palette-a")
        fun assignment(
            destination: DisplayDestination =
                DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
            palette: String = "palette-a",
            spec: UiSyncRendererIngressSpec = ingress,
        ) = CompiledUiSyncDisplayAssignment(
            digest, 0, "profile", DisplayType.CENTER_CONSOLE, destination,
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            program, listOf(CompiledUiSyncSlotIngressAssignment(slot, "video", spec)),
            primaryIntent = CompiledUiSyncThemeIntent("layout", emptyMap(), palette),
        )
        val valid = assignment()
        val signedFingerprint = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(valid),
        )
        fun contract(row: CompiledUiSyncDisplayAssignment,
            spec: UiSyncRendererIngressSpec = ingress) = CompiledUiSyncRuntimeContract(
            schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x53),
            bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x54),
            semanticThemeProgramFingerprint = signedFingerprint,
            rendererImplementationId = "test.renderer.signed-v1.v1",
            displayIds = setOf("opaque-panel"), outputs = listOf(output),
            ingressSpecs = listOf(spec), displayAssignments = listOf(row),
        )
        val manifest = manifestIssuer.issue(request, profile, contract(valid), null)
        assertTrue(manifest.matchesProfile(request, profile, manifest.verificationIdentity))
        assertEquals(intent, manifest.selectThemeVariantFor(profile,
            requireNotNull(output.uiSyncDisplayId), intent)?.intent)
        val forgedIngress = UiSyncRendererIngressSpec(UltraIngressId("forged.ingress"),
            UltraAirPlayStreamIdentity(110), setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID)
        listOf(
            assignment(destination = DisplayDestination(DisplayDestinationRole.MAIN,
                DisplayDestinationId("forged-panel"))) to ingress,
            assignment(palette = "forged-palette") to ingress,
            assignment(spec = forgedIngress) to forgedIngress,
        ).forEach { (forged, spec) ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                manifestIssuer.issue(request, profile, contract(forged, spec), null)
            }
            assertTrue(failure.message.orEmpty(),
                failure.message?.contains("signed v1 catalog") == true)
        }
    }

    @Test
    fun issuerRejectsDuplicateNonemptyDisplayIdsOutsideSelectedOutput() {
        val authentication = identity(193)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = twoMainDisplayPackage()
                .decodeToString().replace("\"id\":\"profile-1\"", "\"id\":\"profile-0\"")
                .encodeToByteArray(),
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 113L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val digest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val assignment = CompiledUiSyncDisplayAssignment(
            digest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel")),
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            themeProgramSelection(profile, 0), emptyList(),
        )
        val failure = assertThrows(IllegalArgumentException::class.java) {
            deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(assignment))
        }
        assertTrue(failure.message.orEmpty(),
            failure.message?.contains("duplicate nonempty display IDs") == true)
    }

    @Test
    fun physicalRequirementBindsVerifiedProfileSignedDestinationsAndStaticOutputInstances() {
        val authentication = identity(74)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = twoMainDisplayPackage(),
        )
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val first = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 31L)),
            UltraDisplayOutputId.Android("panel-a"), UltraUiSyncDisplayId("opaque-a"),
        )
        val second = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 32L)),
            UltraDisplayOutputId.Custom("panel-b"), UltraUiSyncDisplayId("opaque-b"),
        )
        val outputs = listOf(first, second)
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val destinations = listOf(
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel-a")),
            DisplayDestination(DisplayDestinationRole.CLUSTER, DisplayDestinationId("panel-b")),
        )
        val rows = outputs.mapIndexed { index, output ->
            CompiledUiSyncDisplayAssignment(
                packageDigest, index, "profile-$index", DisplayType.CENTER_CONSOLE,
                destinations[index], output.topologyDisplayId, output.outputId,
                requireNotNull(output.uiSyncDisplayId), themeProgramSelection(profile, index),
                emptyList(),
            )
        }
        fun requirement(
            digest: UltraProfileDigest = profile.candidate.contentDigest,
            values: List<UiSyncPhysicalOutputAssignment> = listOf(
                UiSyncPhysicalOutputAssignment(first,
                    UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN, "panel-a")),
                UiSyncPhysicalOutputAssignment(second,
                    UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER, "panel-b")),
            ),
        ) = UiSyncPhysicalOutputRequirement(digest, values)
        fun contract(
            physical: UiSyncPhysicalOutputRequirement?,
        ) = CompiledUiSyncRuntimeContract(
            schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21),
            bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x22),
            semanticThemeProgramFingerprint =
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, rows),
            rendererImplementationId = "test.renderer.physical.v1",
            displayIds = setOf("opaque-a", "opaque-b"),
            outputs = outputs,
            ingressSpecs = emptyList(),
            displayAssignments = rows,
            physicalOutputRequirement = physical,
        )

        val valid = contract(requirement())
        val issued = manifestIssuer.issue(request, profile, valid, null)
        assertTrue(issued.matchesProfile(request, profile, issued.verificationIdentity))
        assertEquals(profile.candidate.contentDigest,
            requireNotNull(issued.uiSync?.physicalOutputRequirement).verifiedProfileDigest)
        assertNotEquals(issued.manifestDigest,
            manifestIssuer.issue(request, profile, contract(null), null).manifestDigest)
        assertEquals(issued.manifestDigest, manifestIssuer.issue(request, profile,
            contract(requirement(values = requirement().assignments.reversed())), null,
        ).manifestDigest)

        val swapped = requirement(values = listOf(
            UiSyncPhysicalOutputAssignment(first,
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER, "panel-b")),
            UiSyncPhysicalOutputAssignment(second,
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN, "panel-a")),
        ))
        assertThrows(IllegalArgumentException::class.java) { contract(swapped) }
        assertThrows(IllegalArgumentException::class.java) {
            contract(requirement(values = requirement().assignments.take(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(requirement(values = listOf(
                UiSyncPhysicalOutputAssignment(first.copy(), requirement().assignments[0].destination),
                requirement().assignments[1],
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(requirement(UltraProfileDigest(ByteArray(32) { 0x59 }))), null)
        }
        // The final manifest digest is not a valid pre-provisioning Profile identity.
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(requirement(UltraProfileDigest(issued.manifestDigest.bytes()))), null)
        }

        val otherBinding = binding(profile.candidate, "another-vehicle", authentication)
        val sameArchiveOtherBinding = (UltraProfilePackageInspector().verify(
            profile.candidate, otherBinding,
        ) {
            UltraProfileTrustResult.Trusted(
                UltraProfileTrustAttestation(otherBinding, byteArrayOf(1, 2, 3)),
            )
        } as UltraProfileVerificationResult.Verified).profile
        assertEquals(profile.candidate.contentDigest,
            sameArchiveOtherBinding.candidate.contentDigest)
        val otherRequest = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("another-vehicle", authentication), authentication,
        )
        val rebound = manifestIssuer.issue(otherRequest, sameArchiveOtherBinding, valid, null)
        assertNotEquals(issued.manifestDigest, rebound.manifestDigest)
        assertFalse(issued.matchesProfile(
            otherRequest, sameArchiveOtherBinding, rebound.verificationIdentity,
        ))
    }

    @Test
    fun compiledRuntimeMatchesOnlyThePinnedPhysicalRequirementFromProvisioning() {
        val profile = verifiedProfile(
            "vehicle", identity(75), phonePackageJson = twoMainDisplayPackage(),
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 33L)),
            UltraDisplayOutputId.Android("panel-a"), UltraUiSyncDisplayId("opaque-a"),
        )
        val physicalRow = UiSyncPhysicalOutputAssignment(
            output, UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN, "panel-a"),
        )
        val expected = UiSyncPhysicalOutputRequirement(
            profile.candidate.contentDigest, listOf(physicalRow),
        )
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val assignment = CompiledUiSyncDisplayAssignment(
            packageDigest, 0, "profile-0", DisplayType.CENTER_CONSOLE,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel-a")),
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            themeProgramSelection(profile, 0), emptyList(),
        )
        val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(assignment),
        )
        val provisioning = staticAuthorizedUiSyncProvisioning(output, expected, program = program)
        val snapshot = requireNotNull(provisioning.authorizationSnapshot())
        val contract = CompiledUiSyncRuntimeContract.from(snapshot, listOf(assignment))
        assertTrue(contract.matches(snapshot))
        val without = staticAuthorizedUiSyncProvisioning(output)
        assertFalse(contract.matches(requireNotNull(without.authorizationSnapshot())))
        val wrongDigest = staticAuthorizedUiSyncProvisioning(
            output, UiSyncPhysicalOutputRequirement(
                UltraProfileDigest(ByteArray(32) { 0x5a }), listOf(physicalRow),
            ),
        )
        assertFalse(contract.matches(requireNotNull(wrongDigest.authorizationSnapshot())))
        wrongDigest.close()
        without.close()
        provisioning.close()
    }

    @Test
    fun issuerRejectsActiveUnexecutableGaugeMaskAndUnknownDrawingBeforeProvisioning() {
        val authentication = identity(76)
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 34L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val spec = UiSyncRendererIngressSpec(
            UltraIngressId("profile-video"), UltraAirPlayStreamIdentity(110, "profile-video"),
        )
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        fun issue(
            packageJson: ByteArray,
            selectedState: CarPlayThemeSceneState = state,
        ): AuthorizedUltraCompiledRuntimeManifest {
            val profile = verifiedProfile(
                "vehicle", authentication, phonePackageJson = packageJson,
            )
            val display = CarPlayThemeSchema.parseArcPackageJson(packageJson).displays.single()
            val compiled = CarPlayThemeProgramCompiler().compile(display, selectedState)
                .structuralProgram
            val ingressRows = compiled.instructions
                .filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .mapNotNull { slot ->
                    (slot.source as? CarPlayThemeStructuralSource.InputStream)?.let {
                        CompiledUiSyncSlotIngressAssignment(
                            slot.address, it.streamIdentifier, spec,
                        )
                    }
                }
            val assignment = CompiledUiSyncDisplayAssignment(
                profile.candidate.entries.single {
                    it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
                }.digest,
                0, "profile", DisplayType.CENTER_CONSOLE, DisplayDestination.MAIN,
                output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
                CompiledUiSyncThemeProgramSelection(
                    0, selectedState, compiled.structuralFingerprint,
                ),
                ingressRows,
            )
            val contract = CompiledUiSyncRuntimeContract(
                schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21),
                bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x22),
                semanticThemeProgramFingerprint =
                    deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
                        listOf(assignment)),
                rendererImplementationId = "test.renderer.proof.v1",
                displayIds = setOf("opaque-panel"), outputs = listOf(output),
                ingressSpecs = listOf(spec), displayAssignments = listOf(assignment),
            )
            return manifestIssuer.issue(request, profile, contract, null)
        }
        val baseline = slotBindingPackage().decodeToString()
        assertTrue(issue(baseline.encodeToByteArray()).authorizedFeatures.contains(
            AirPlayFeature.UI_SYNC,
        ))
        val gauge = baseline.replace(
            "\"kind\":{\"inputStream\":{\"_0\":\"video\"}}",
            "\"kind\":{\"gauge\":{\"configuration\":{\"style\":{\"spinner\":{}}," +
                "\"rendersLocally\":true,\"visibilityConfiguration\":{\"visible\":true}}}}",
        )
        val mask = baseline.replace(
            "\"requiresDarkAppearance\":false,",
            "\"requiresDarkAppearance\":false,\"iPhoneVideoMask\":{},",
        )
        val unknown = baseline.replace(
            "\"zIndex\":0,\"positionId\":\"slot\"",
            "\"zIndex\":0,\"futureDraw\":true,\"positionId\":\"slot\"",
        )
        listOf(gauge, mask, unknown).forEach { variant ->
            assertNotEquals(baseline, variant)
            val rejected = assertThrows(IllegalArgumentException::class.java) {
                issue(variant.encodeToByteArray())
            }
            assertTrue(rejected.message.orEmpty().contains(
                "Selected verified Profile layout has unresolved active drawing",
            ))
        }
        val opaquePackageFields = listOf(
            baseline.replace(
                "\"identifier\":\"slot-binding\",",
                "\"identifier\":\"slot-binding\",\"futurePackageDrawing\":true,",
            ),
            baseline.replace(
                "\"driveModeLinkedByDefault\":false,",
                "\"driveModeLinkedByDefault\":false," +
                    "\"driveModeDynamicAssignmentConfiguration\":{},",
            ),
            baseline.replace(
                "\"driveModeLinkedByDefault\":false,",
                "\"driveModeLinkedByDefault\":false,\"vehicleType\":{},",
            ),
            baseline.replace(
                "\"driveModeLinkedByDefault\":false,",
                "\"driveModeLinkedByDefault\":false,\"vehicleTypes\":[{}],",
            ),
        )
        opaquePackageFields.forEach { variant ->
            assertNotEquals(baseline, variant)
            val rejected = assertThrows(IllegalArgumentException::class.java) {
                issue(variant.encodeToByteArray())
            }
            assertTrue(rejected.message.orEmpty().contains(
                "Active ArcPackage semantics are not executable",
            ))
        }
        val firstZone = """{"id":"zone-a","frame":[[0,0],[400,200]],
            "type":{"single":{}},"components":[{"id":"component-a","type":"primary",
            "size":[400,200],"slots":[{"style":{"rectangular":{}},
            "frame":[[0,0],[100,100]],"zIndex":0,"positionId":"video-a",
            "instrument":{"kind":{"inputStream":{"_0":"video-a"}},
            "backgroundColor":"#00000000","clipsToShape":false}}]}]}""".trimIndent()
        val secondZone = firstZone.replace("zone-a", "zone-b")
            .replace("component-a", "component-b")
            .replace("video-a", "video-b")
        val overlappingPackage = """{
            "identifier":"overlapping-video","version":1,"minimumSDKVersion":"1",
            "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
            "displays":[{"id":"profile","type":"centerConsole","size":[400,200],
              "density":2,"allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
              "layouts":[{"id":"layout","layoutType":"primary",
                "backgroundColor":"#ff0000","safeAreaInsets":[0,0,0,0],
                "driveModeThemeConfigurations":{},
                "zones":[$firstZone,$secondZone]}]}]
        }""".trimIndent().encodeToByteArray()
        val twoSelectedZones = CarPlayThemeSceneState(listOf(
            CarPlayThemePrimarySelection(0, 0, 0), CarPlayThemePrimarySelection(0, 1, 0),
        ))
        val overlap = assertThrows(IllegalArgumentException::class.java) {
            issue(overlappingPackage, twoSelectedZones)
        }
        assertTrue(overlap.message.orEmpty().contains("STATIC_CROSS_COMPONENT_VIDEO_OVERLAP"))
    }

    @Test
    fun compiledThemeStateCanonicalizesIndependentSelectionsAndRejectsDuplicateZones() {
        val first = CarPlayThemePrimarySelection(0, 0, 0)
        val second = CarPlayThemePrimarySelection(1, 0, 0)
        val fingerprint = CarPlayThemeStructuralFingerprint(ByteArray(32) { 0x72 })
        val ordered = CompiledUiSyncThemeProgramSelection(
            0, CarPlayThemeSceneState(listOf(first, second)), fingerprint,
        )
        val reversed = CompiledUiSyncThemeProgramSelection(
            0, CarPlayThemeSceneState(listOf(second, first)), fingerprint,
        )
        assertEquals(ordered, reversed)
        assertTrue(ordered.matches(0, CarPlayThemeSceneState(listOf(second, first)), fingerprint))
        assertThrows(IllegalArgumentException::class.java) {
            CompiledUiSyncThemeProgramSelection(
                0, CarPlayThemeSceneState(listOf(first, first)), fingerprint,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompiledUiSyncThemeProgramSelection(
                0, CarPlayThemeSceneState(trackingOverlayPresent = true), fingerprint,
            )
        }
    }

    @Test
    fun issuedManifestCommitsEveryActiveInputStreamSlotToExactStaticIngressSpec() {
        val authentication = identity(63)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = slotBindingPackage(),
        )
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 9L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(0, 0, 0)))
        val display = CarPlayThemeSchema.parseArcPackageJson(slotBindingPackage()).displays.single()
        val compiled = CarPlayThemeProgramCompiler().compile(display, state).structuralProgram
        val slot = compiled.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
            .single { it.source is CarPlayThemeStructuralSource.InputStream }
        val firstSpec = UiSyncRendererIngressSpec(
            UltraIngressId("first-ingress"), UltraAirPlayStreamIdentity(110, "first-stream"),
        )
        val secondSpec = UiSyncRendererIngressSpec(
            UltraIngressId("second-ingress"), UltraAirPlayStreamIdentity(110, "second-stream"),
        )
        val uuidBoundFirstSpec = firstSpec.copy(setupDisplayUuid = AirPlayInfoPlist.MAIN_UUID)
        val packageDigest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        fun assignment(spec: UiSyncRendererIngressSpec, streamIdentifier: String = "video") =
            CompiledUiSyncDisplayAssignment(
                packageDigest, 0, "profile", DisplayType.CENTER_CONSOLE,
                DisplayDestination.MAIN, output.topologyDisplayId, output.outputId,
                requireNotNull(output.uiSyncDisplayId),
                CompiledUiSyncThemeProgramSelection(0, state, compiled.structuralFingerprint),
                listOf(CompiledUiSyncSlotIngressAssignment(
                    slot.address, streamIdentifier, spec,
                )),
            )
        fun contract(
            row: CompiledUiSyncDisplayAssignment,
            specs: List<UiSyncRendererIngressSpec> = listOf(firstSpec, secondSpec),
        ) = CompiledUiSyncRuntimeContract(
            schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21),
            bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x22),
            semanticThemeProgramFingerprint =
                deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile, listOf(row)),
            rendererImplementationId = "test.renderer.slot-bound.v1",
            displayIds = setOf("opaque-panel"), outputs = listOf(output),
            ingressSpecs = specs,
            displayAssignments = listOf(row),
        )
        val first = manifestIssuer.issue(request, profile, contract(assignment(firstSpec)), null)
        val second = manifestIssuer.issue(request, profile, contract(assignment(secondSpec)), null)
        assertNotEquals(first.manifestDigest, second.manifestDigest)
        assertNotEquals(first.uiSync?.semanticThemeProgramFingerprint,
            second.uiSync?.semanticThemeProgramFingerprint)
        val uuidBound = manifestIssuer.issue(
            request, profile,
            contract(assignment(uuidBoundFirstSpec), listOf(uuidBoundFirstSpec, secondSpec)),
            null,
        )
        assertNotEquals(first.manifestDigest, uuidBound.manifestDigest)
        assertNotEquals(first.uiSync?.semanticThemeProgramFingerprint,
            uuidBound.uiSync?.semanticThemeProgramFingerprint)
        assertThrows(IllegalArgumentException::class.java) {
            contract(assignment(firstSpec), listOf(firstSpec, uuidBoundFirstSpec))
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(assignment(firstSpec, streamIdentifier = "wrong")), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile,
                contract(assignment(firstSpec).copyForTest(slotIngressAssignments = emptyList())),
                null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            contract(assignment(UiSyncRendererIngressSpec(
                UltraIngressId("foreign"), UltraAirPlayStreamIdentity(110, "foreign-stream"),
            )))
        }
    }

    @Test
    fun eachSignedLayoutVariantProvesItsOwnExactVideoSlotIngressMapping() {
        val authentication = identity(92)
        val packageJson = twoSlotVariantPackage()
        val profile = verifiedProfile("vehicle", authentication, phonePackageJson = packageJson)
        val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from("vehicle", authentication), authentication,
        )
        val display = CarPlayThemeSchema.parseArcPackageJson(packageJson).displays.single()
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 92L)),
            UltraDisplayOutputId.Android("panel"), UltraUiSyncDisplayId("opaque-panel"),
        )
        val digest = profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest
        val firstSpec = UiSyncRendererIngressSpec(
            UltraIngressId("first-ingress"), UltraAirPlayStreamIdentity(110, "first-stream"),
        )
        val secondSpec = UiSyncRendererIngressSpec(
            UltraIngressId("second-ingress"), UltraAirPlayStreamIdentity(110, "second-stream"),
        )
        fun selection(index: Int): Pair<CompiledUiSyncThemeProgramSelection,
            com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress> {
            val state = CarPlayThemeSceneState(listOf(CarPlayThemePrimarySelection(index, 0, 0)))
            val program = CarPlayThemeProgramCompiler().compile(display, state).structuralProgram
            val slot = program.instructions.filterIsInstance<CarPlayThemeProgramInstruction.Slot>()
                .single { it.source is CarPlayThemeStructuralSource.InputStream }
            return CompiledUiSyncThemeProgramSelection(
                index, state, program.structuralFingerprint,
            ) to slot.address
        }
        val (firstProgram, firstSlot) = selection(0)
        val (secondProgram, secondSlot) = selection(1)
        fun row(
            secondStream: String = "video-b",
            includeSecondSlot: Boolean = true,
        ) = CompiledUiSyncDisplayAssignment(
            digest, 0, "profile", DisplayType.CENTER_CONSOLE,
            DisplayDestination.MAIN, output.topologyDisplayId, output.outputId,
            requireNotNull(output.uiSyncDisplayId), firstProgram,
            listOf(CompiledUiSyncSlotIngressAssignment(firstSlot, "video-a", firstSpec)),
            primaryIntent = CompiledUiSyncThemeIntent("first", emptyMap(), "palette-a"),
            additionalVariants = listOf(CompiledUiSyncThemeVariant(
                CompiledUiSyncThemeIntent("second", emptyMap(), "palette-b"), secondProgram,
                if (includeSecondSlot) listOf(CompiledUiSyncSlotIngressAssignment(
                    secondSlot, secondStream, secondSpec,
                )) else emptyList(),
            )),
        )
        fun contract(assignment: CompiledUiSyncDisplayAssignment) =
            CompiledUiSyncRuntimeContract(
                schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21),
                bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x22),
                semanticThemeProgramFingerprint =
                    deriveVerifiedUiSyncSemanticThemeProgramFingerprint(profile,
                        listOf(assignment)),
                rendererImplementationId = "test.renderer.variants.v1",
                displayIds = setOf("opaque-panel"), outputs = listOf(output),
                ingressSpecs = listOf(firstSpec, secondSpec),
                displayAssignments = listOf(assignment),
            )
        val manifest = manifestIssuer.issue(request, profile, contract(row()), null)
        val second = requireNotNull(manifest.selectThemeVariantFor(
            profile, requireNotNull(output.uiSyncDisplayId),
            CompiledUiSyncThemeIntent("second", emptyMap(), "palette-b"),
        ))
        assertEquals(listOf(secondSlot), second.slotIngressAssignments.map { it.slot })
        assertEquals(secondSpec, second.slotIngressAssignments.single().ingressSpec)
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile, contract(row(secondStream = "video-a")), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifestIssuer.issue(request, profile, contract(row(includeSecondSlot = false)), null)
        }
    }

    @Test
    fun liveUiSyncAssignmentComesFromIssuerManifestAndDiesWithAa05Receipt() {
        val authentication = identity(62)
        val receiptFixture = receiptFixture(authentication)
        val profile = verifiedProfile(
            "vehicle", authentication, phonePackageJson = twoMainDisplayPackage(),
        )
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, 8L)),
            UltraDisplayOutputId.Android("panel-a"), UltraUiSyncDisplayId("opaque-channel-a"),
        )
        val assignment = CompiledUiSyncDisplayAssignment(
            profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest,
            0, "profile-0", DisplayType.CENTER_CONSOLE,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel-a")),
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            themeProgramSelection(profile, 0),
            emptyList(),
        )
        val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(assignment),
        )
        val uiSync = staticAuthorizedUiSyncProvisioning(output, program = program)
        val snapshot = requireNotNull(uiSync.authorizationSnapshot())
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request, profile,
                compiledManifestOverride = manifestIssuer.issue(
                    request, profile,
                    CompiledUiSyncRuntimeContract.from(snapshot, listOf(assignment)), null,
                ),
                uiSyncProvisioning = uiSync,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receiptFixture.receipt).started()
        val lease = coordinator.commit(
            coordinator.prepare(token, receiptFixture.receipt).prepared(),
            receiptFixture.receipt,
        ).activated()
        val child = (lease.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease
        assertEquals(listOf(assignment), child.displayAssignmentsFor(profile))
        // A signed/static display assignment is not proof that this control session has
        // subscribed to the display or installed its exact Host physical-output batch.
        val staticOnlyConfiguration = child.configuration()
        assertTrue(staticOnlyConfiguration.isConfigured(AirPlayFeature.UI_SYNC))
        assertFalse(staticOnlyConfiguration.readiness.uiSync?.isComplete == true)
        assertThrows(IllegalArgumentException::class.java) {
            child.displayAssignmentsFor(verifiedProfile(
                "vehicle", authentication, phonePackageJson = twoMainDisplayPackage(),
            ))
        }

        receiptFixture.beginReplacement()
        assertFalse(child.isLive)
        assertThrows(IllegalStateException::class.java) {
            child.displayAssignmentsFor(profile)
        }
        coordinator.close()
        assertTrue(preparation.lease.bundle.closed)
    }

    @Test
    fun physicalSealRunsOnceOnlyAfterRealChildPublicationWithExactManifestAndAa05Proof() {
        lateinit var fixture: PhysicalSealFixture
        fixture = physicalSealFixture(91) { manifest, issuer, token, proof ->
            assertSame(fixture.manifest, manifest)
            assertTrue(manifest.issuedBy(issuer))
            assertSame(fixture.token, token)
            assertTrue(proof.matchesToken(token))
            assertTrue(fixture.coordinator.physicalGenerationStatus(token) is
                UltraProfilePhysicalGenerationStatus.Current)
            assertTrue(proof.sameLineage(requireNotNull(
                fixture.coordinator.currentPhysicalGenerationProof(token),
            )))
        }
        try {
            assertEquals(0, fixture.sealer.calls)
            assertFalse(fixture.child.physicalOutputSealed)
            val foreign = UltraProfileActivationCoordinator()
            val foreignToken = foreign.beginSession("vehicle", receipt(identity(92))).started()
            assertNull(fixture.coordinator.currentPhysicalGenerationProof(foreignToken))
            assertNull(fixture.child.sealPhysicalOutputAuthority())
            assertTrue(fixture.child.physicalOutputSealed)
            assertEquals(1, fixture.sealer.calls)
            assertEquals(UltraProfileActivationDenialCode.ALREADY_ACTIVE,
                fixture.child.sealPhysicalOutputAuthority())
            assertEquals(1, fixture.sealer.calls)
            foreign.close()
        } finally {
            fixture.coordinator.close()
        }
    }

    @Test
    fun clusterAssetProbeMatchesOnlySealedCurrentSignedUiSyncChild() {
        val fixture = physicalSealFixture(121)
        val exactIdentity = fixture.receipt.receipt.authenticationIdentity
        try {
            assertFalse(fixture.child.allowsClusterAssetAcquisition("asset", exactIdentity))
            assertNull(fixture.child.sealPhysicalOutputAuthority())
            assertTrue(fixture.child.allowsClusterAssetAcquisition("asset", exactIdentity))
            assertFalse(fixture.child.allowsClusterAssetAcquisition("different", exactIdentity))
            assertFalse(fixture.child.allowsClusterAssetAcquisition("asset", identity(122)))
            fixture.receipt.beginReplacement()
            assertFalse(fixture.child.allowsClusterAssetAcquisition("asset", exactIdentity))
        } finally {
            fixture.coordinator.close()
        }
    }

    @Test
    fun missingOrForeignPreflightSealerRetiresUnpublishedUiSyncChild() {
        val absent = physicalSealFixture(93, supplySealer = false)
        assertEquals(UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED,
            absent.child.sealPhysicalOutputAuthority())
        assertFalse(absent.child.isLive)
        assertTrue(absent.preparation.lease.bundle.closed)
        absent.coordinator.close()

        val foreign = physicalSealFixture(94, foreignSealerAuthority = true)
        assertEquals(UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED,
            foreign.child.sealPhysicalOutputAuthority())
        assertEquals(0, foreign.sealer.calls)
        assertFalse(foreign.child.isLive)
        assertTrue(foreign.preparation.lease.bundle.closed)
        foreign.coordinator.close()
    }

    @Test
    fun sealRejectsProviderGetterSwitchAfterRuntimeCapturedExactUiSyncProvisioning() {
        val fixture = physicalSealFixture(97)
        val replacement = fixture.makeReplacementUiSync()
        try {
            // The runtime was constructed from A. B has matching fingerprints and even uses
            // the same authority, but cannot retroactively become the runtime's UI Sync owner.
            fixture.preparation.lease.bundle.uiSyncProvisioning = replacement
            assertEquals(UltraProfileActivationDenialCode.RUNTIME_CONSTRUCTION_FAILED,
                fixture.child.sealPhysicalOutputAuthority())
            assertEquals(0, fixture.sealer.calls)
            assertFalse(fixture.child.isLive)
        } finally {
            fixture.coordinator.close()
            replacement.close()
        }
    }

    @Test
    fun sealThrowRetainsExactFailedRetirementOwnerForLifecycleRetry() {
        val fixture = physicalSealFixture(95, leaseCloseFailure = IllegalStateException(
            "synthetic exact lease close failure",
        ), leaseCloseFailureCount = 2) {
            _, _, _, _ -> throw IllegalStateException("synthetic seal failure")
        }
        assertEquals(UltraProfileActivationDenialCode.REVOCATION_FAILED,
            fixture.child.sealPhysicalOutputAuthority())
        assertFalse(fixture.child.isLive)
        assertEquals(1, fixture.sealer.calls)
        assertEquals(2, fixture.preparation.lease.closeAttempts)
        fixture.coordinator.close()
        assertEquals(3, fixture.preparation.lease.closeAttempts)
    }

    @Test
    fun concurrentReceiptRevocationCannotPublishASealedUiSyncChild() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = physicalSealFixture(96) { _, _, _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val result = java.util.concurrent.atomic.AtomicReference<UltraProfileActivationDenialCode?>()
        val sealThread = Thread { result.set(fixture.child.sealPhysicalOutputAuthority()) }
        sealThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val revocation = Thread { fixture.receipt.beginReplacement() }
        revocation.start()
        try {
            // Revocation closes admission while the exact seal reservation is in flight.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fixture.receipt.receipt.isLiveForUltraActivation() &&
                System.nanoTime() < deadline
            ) Thread.yield()
            assertFalse(fixture.receipt.receipt.isLiveForUltraActivation())
        } finally {
            release.countDown()
        }
        sealThread.join(5_000)
        revocation.join(5_000)
        assertFalse(sealThread.isAlive)
        assertFalse(revocation.isAlive)
        assertEquals(UltraProfileActivationDenialCode.LEASE_NOT_LIVE, result.get())
        assertFalse(fixture.child.physicalOutputSealed)
        assertFalse(fixture.child.isLive)
        fixture.coordinator.close()
    }

    @Test
    fun committedSameMfiHandoffRenewsReceiptBeforeOldRevocationCallback() {
        val authenticationIdentity = identity(45)
        val handoffFixture = receiptHandoffFixture(authenticationIdentity)
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession(
            "vehicle",
            handoffFixture.predecessor,
        ).started()
        val active = coordinator.commit(
            coordinator.prepare(token, handoffFixture.predecessor).prepared(),
            handoffFixture.predecessor,
        ).activated()
        val beforeHandoff = requireNotNull(coordinator.currentPhysicalGenerationProof(token))

        lateinit var renewal: PreparedUltraProfileSessionRenewal
        handoffFixture.handoff.commit(handoffFixture.successor) { promoted ->
            renewal = coordinator.prepareSessionRenewal(
                token,
                handoffFixture.predecessor,
                promoted,
            )
        }
        assertTrue(active.isLive)
        renewal.commit()
        renewal.commit()
        renewal.close()

        val afterHandoff = requireNotNull(coordinator.currentPhysicalGenerationProof(token))
        assertTrue(beforeHandoff.sameLineage(afterHandoff))
        assertTrue(afterHandoff.matchesToken(token))

        assertTrue(active.isLive)
        assertFalse(preparation.lease.closed)
        handoffFixture.revokeSuccessor()
        assertEquals(null, coordinator.currentPhysicalGenerationProof(token))
        assertFalse(active.isLive)
        assertTrue(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun deniedCoordinatorRenewalRollsHandoffBackToLivePredecessor() {
        val authenticationIdentity = identity(46)
        val handoffFixture = receiptHandoffFixture(authenticationIdentity)
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession(
            "vehicle",
            handoffFixture.predecessor,
        ).started()
        val active = coordinator.commit(
            coordinator.prepare(token, handoffFixture.predecessor).prepared(),
            handoffFixture.predecessor,
        ).activated()
        val foreignToken = UltraProfileSessionToken.create(Any(), 999L)

        assertThrows(UltraProfileSessionRenewalException::class.java) {
            handoffFixture.handoff.commit(handoffFixture.successor) { promoted ->
                coordinator.prepareSessionRenewal(
                    foreignToken,
                    handoffFixture.predecessor,
                    promoted,
                )
            }
        }

        assertTrue(active.isLive)
        assertFalse(preparation.lease.closed)
        handoffFixture.revokeSuccessor()
        assertFalse(active.isLive)
        coordinator.close()
    }

    @Test
    fun interruptedOuterHandoffRestoresPredecessorWithoutClosingRuntimeTree() {
        val handoffFixture = receiptHandoffFixture(identity(49))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", handoffFixture.predecessor).started()
        val active = coordinator.commit(
            coordinator.prepare(token, handoffFixture.predecessor).prepared(),
            handoffFixture.predecessor,
        ).activated()
        lateinit var renewal: PreparedUltraProfileSessionRenewal

        assertThrows(IllegalStateException::class.java) {
            handoffFixture.handoff.commit(handoffFixture.successor) { promoted ->
                renewal = coordinator.prepareSessionRenewal(
                    token,
                    handoffFixture.predecessor,
                    promoted,
                )
                handoffFixture.handoff.cancel()
            }
        }
        renewal.close()

        assertTrue(handoffFixture.predecessor.isLiveForUltraActivation())
        assertFalse(handoffFixture.successor.isLiveForUltraActivation())
        assertTrue(active.isLive)
        assertFalse(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun preparedRenewalCannotCommitBeforeOuterHandoffCompletes() {
        val handoffFixture = receiptHandoffFixture(identity(50))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", handoffFixture.predecessor).started()
        val active = coordinator.commit(
            coordinator.prepare(token, handoffFixture.predecessor).prepared(),
            handoffFixture.predecessor,
        ).activated()

        assertThrows(UltraProfileSessionRenewalException::class.java) {
            handoffFixture.handoff.commit(handoffFixture.successor) { promoted ->
                coordinator.prepareSessionRenewal(
                    token,
                    handoffFixture.predecessor,
                    promoted,
                ).commit()
            }
        }

        assertTrue(handoffFixture.predecessor.isLiveForUltraActivation())
        assertTrue(active.isLive)
        assertFalse(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun revokedSuccessorAfterOuterCommitFailsClosedWhenPredecessorIsAlreadyDead() {
        val handoffFixture = receiptHandoffFixture(identity(51))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", handoffFixture.predecessor).started()
        val active = coordinator.commit(
            coordinator.prepare(token, handoffFixture.predecessor).prepared(),
            handoffFixture.predecessor,
        ).activated()
        lateinit var renewal: PreparedUltraProfileSessionRenewal
        handoffFixture.handoff.commit(handoffFixture.successor) { promoted ->
            renewal = coordinator.prepareSessionRenewal(
                token,
                handoffFixture.predecessor,
                promoted,
            )
        }

        handoffFixture.revokeSuccessor()

        assertFalse(active.isLive)
        assertFalse(preparation.lease.closed)
        renewal.close()
        assertFalse(preparation.lease.closed)
        coordinator.close()
        assertTrue(preparation.lease.closed)
    }

    @Test
    fun revokedReceiptCannotProbeOrOpenRuntimeBeforeItsCallbackAcquiresCoordinatorLock() {
        val receipt = receipt(identity(47))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(),
            receipt,
        ).activated()
        val markRevoked = receipt.javaClass.declaredMethods.single { method ->
            method.name.startsWith("markRevokedState") && method.parameterCount == 0
        }.apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val delayedCallback = markRevoked.invoke(receipt) as (() -> Unit)?

        assertFalse(active.isLive)
        assertEquals(
            UltraProfileActivationDenialCode.STALE_RECEIPT,
            active.openRuntime().denied(),
        )
        assertFalse(preparation.lease.closed)

        requireNotNull(delayedCallback).invoke()
        assertTrue(preparation.lease.closed)
        coordinator.close()
    }

    @Test
    fun sameCertificateReceiptFromAnotherAuthorityCannotRenewSession() {
        val authenticationIdentity = identity(48)
        val predecessor = receipt(authenticationIdentity)
        val foreignFixture = receiptFixture(authenticationIdentity)
        val foreignSuccessor = foreignFixture.receipt
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", predecessor).started()
        val active = coordinator.commit(
            coordinator.prepare(token, predecessor).prepared(),
            predecessor,
        ).activated()

        assertThrows(UltraProfileSessionRenewalException::class.java) {
            coordinator.prepareSessionRenewal(token, predecessor, foreignSuccessor)
        }

        assertTrue(active.isLive)
        assertFalse(preparation.lease.closed)
        coordinator.close()
        foreignFixture.beginReplacement()
    }

    @Test
    fun reentrantProviderMutationIsBusyAndCannotReplaceTheReceiptGeneration() {
        val firstReceipt = receipt(identity(14))
        val secondReceipt = receipt(identity(15))
        lateinit var coordinator: UltraProfileActivationCoordinator
        lateinit var candidate: FakePreparation
        var reentrantResult: UltraProfileSessionStartResult? = null
        coordinator = coordinator { request ->
            reentrantResult = coordinator.beginSession("replacement", secondReceipt)
            candidate = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(candidate)
        }
        val token = coordinator.beginSession("vehicle", firstReceipt).started()

        val prepared = coordinator.prepare(token, firstReceipt).prepared()

        assertEquals(
            UltraProfileActivationDenialCode.BUSY,
            (reentrantResult as UltraProfileSessionStartResult.Denied).code,
        )
        assertFalse(candidate.closed)
        coordinator.commit(prepared, firstReceipt).activated()
        coordinator.beginSession("replacement", secondReceipt).started()
        coordinator.close()
    }

    @Test
    fun reentrantCloseDuringPrepareDiscardsTheCandidateAndClosesTheCoordinator() {
        val receipt = receipt(identity(16))
        lateinit var coordinator: UltraProfileActivationCoordinator
        lateinit var candidate: FakePreparation
        coordinator = coordinator { request ->
            coordinator.close()
            candidate = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(candidate)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()

        assertEquals(
            UltraProfileActivationDenialCode.CLOSED,
            coordinator.prepare(token, receipt).denied(),
        )
        assertTrue(candidate.closed)
        assertEquals(
            UltraProfileActivationDenialCode.CLOSED,
            coordinator.beginSession("vehicle", receipt(identity(17))).denied(),
        )
    }

    @Test
    fun crossThreadCloseWaitsForBlockedProviderRetirementAndLatePreparationIsRevoked() {
        val receipt = receipt(identity(24))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var candidate: FakePreparation
        val coordinator = coordinator { request ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            candidate = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(candidate)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val preparing = executor.submit<UltraProfilePrepareResult> {
                coordinator.prepare(token, receipt)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))

            val closing = executor.submit { coordinator.close() }
            assertFalse(closing.isDone)
            release.countDown()

            closing.get(2, TimeUnit.SECONDS)
            assertEquals(
                UltraProfileActivationDenialCode.CLOSED,
                preparing.get(2, TimeUnit.SECONDS).denied(),
            )
            assertTrue(candidate.closed)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun leaseCloseWaitsForAnInFlightProbeRetirementBarrier() {
        val receipt = receipt(identity(28))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, receipt).prepared(),
            receipt,
        ).activated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newFixedThreadPool(2)
        try {
            val probing = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val closeStarted = CountDownLatch(1)
            val closing = executor.submit {
                closeStarted.countDown()
                active.close()
            }
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
            val revokedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!active.revoked && System.nanoTime() < revokedDeadline) Thread.yield()
            assertTrue(active.revoked)
            assertFalse(closing.isDone)

            release.countDown()

            assertFalse(probing.get(2, TimeUnit.SECONDS))
            closing.get(2, TimeUnit.SECONDS)
            assertTrue(preparation.lease.closed)
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun receiptGenerationReplacementWaitsForAnInFlightLeaseRetirement() {
        val fixture = receiptFixture(identity(29))
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            preparation = FakePreparation(
                request,
                verifiedProfile("vehicle", request.authenticationIdentity),
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", fixture.receipt).started()
        val active = coordinator.commit(
            coordinator.prepare(token, fixture.receipt).prepared(),
            fixture.receipt,
        ).activated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        preparation.lease.liveProbeEntered = entered
        preparation.lease.liveProbeRelease = release
        val executor = Executors.newFixedThreadPool(2)
        try {
            val probing = executor.submit<Boolean> { active.isLive }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val replacing = executor.submit { fixture.beginReplacement() }
            val revokedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!active.revoked && System.nanoTime() < revokedDeadline) Thread.yield()
            assertTrue(active.revoked)
            assertFalse(replacing.isDone)

            release.countDown()

            assertFalse(probing.get(2, TimeUnit.SECONDS))
            replacing.get(2, TimeUnit.SECONDS)
            assertTrue(preparation.lease.closed)
        } finally {
            release.countDown()
            executor.shutdownNow()
            coordinator.close()
        }
    }

    @Test
    fun verificationIdentityDefensivelyCopiesAndBindsProofAndReceiptIdentity() {
        val firstAuthentication = identity(18)
        val secondAuthentication = identity(19)
        val firstRuntime = UltraRuntimeIdentity.from("vehicle", firstAuthentication)
        val secondRuntime = UltraRuntimeIdentity.from("vehicle", secondAuthentication)
        val firstProfile = verifiedProfile(
            "vehicle",
            firstAuthentication,
            proof = byteArrayOf(1),
        )
        val changedProofProfile = verifiedProfile(
            "vehicle",
            firstAuthentication,
            proof = byteArrayOf(2),
        )
        val first = UltraProfileVerificationIdentity.from(
            firstRuntime,
            firstAuthentication,
            firstProfile,
        )
        val same = UltraProfileVerificationIdentity.from(
            firstRuntime,
            firstAuthentication,
            firstProfile,
        )
        val changedProof = UltraProfileVerificationIdentity.from(
            firstRuntime,
            firstAuthentication,
            changedProofProfile,
        )
        val changedAuthentication = UltraProfileVerificationIdentity.from(
            secondRuntime,
            secondAuthentication,
            firstProfile,
        )

        assertEquals(first, same)
        assertNotEquals(first, changedProof)
        assertNotEquals(first, changedAuthentication)
        val bytes = first.bytes()
        val expected = bytes.copyOf()
        bytes.fill(0)
        assertArrayEquals(expected, first.bytes())
        assertEquals("UltraProfileVerificationIdentity(<redacted>)", first.toString())
    }

    @Test
    fun coordinatorHasNoEntryPointThatAcceptsBareMfiIdentityAsAuthenticationProof() {
        val authenticationProofMethods = UltraProfileActivationCoordinator::class.java
            .declaredMethods
            .filter { it.name in setOf("beginSession", "prepare", "commit", "endSession") }

        assertTrue(authenticationProofMethods.isNotEmpty())
        assertFalse(
            authenticationProofMethods.any { method ->
                method.parameterTypes.any { it == MfiAuthenticationIdentity::class.java }
            },
        )
        assertTrue(
            authenticationProofMethods.any { method ->
                method.parameterTypes.any { it == MfiAuthenticationReceipt::class.java }
            },
        )
        UltraProfileActivationCoordinator::class.java.declaredConstructors
            .filterNot { constructor ->
                Modifier.isPrivate(constructor.modifiers) ||
                    // Kotlin's companion factory for the private, captured-epoch constructor
                    // needs this synthetic accessor. It is not a provider/issuer/identity API.
                    (constructor.isSynthetic && constructor.parameterTypes.size == 2 &&
                        constructor.parameterTypes[0] == UltraProfilePolicyEpoch::class.java &&
                        constructor.parameterTypes[1].name ==
                            "kotlin.jvm.internal.DefaultConstructorMarker")
            }
            .forEach { constructor -> assertEquals(0, constructor.parameterCount) }
        assertTrue(
            MfiAuthenticationReceipt::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertFalse(
            MfiAuthenticationReceipt::class.java.declaredConstructors.any { constructor ->
                !constructor.isSynthetic &&
                    constructor.parameterTypes.contentEquals(
                        arrayOf(MfiAuthenticationIdentity::class.java),
                    )
            },
        )
    }

    private class PhysicalPreflightAuthority : UiSyncPhysicalOutputAuthority {
        override fun preflight(context: UiSyncPhysicalOutputPreflightContext):
            UiSyncPhysicalOutputAvailability = context.issueAvailability(
                context.requirement.assignments.mapIndexed { index, assignment ->
                    UiSyncPhysicalOutputRoute(assignment,
                        UiSyncPhysicalTargetVersion("physical-seal-test-$index", 1L))
                },
            ) { true }

        override fun bindSession(context: UiSyncPhysicalOutputSessionContext):
            UiSyncPhysicalOutputBatch? = null
    }

    private class RecordingPhysicalSealer(
        override val authority: UiSyncPhysicalOutputAuthority,
        private val onSeal: (
            AuthorizedUltraCompiledRuntimeManifest,
            AuthorizedUltraCompiledRuntimeManifestIssuer,
            UltraProfileSessionToken,
            UltraProfilePhysicalGenerationProof,
        ) -> Unit,
    ) : AuthorizedUltraPhysicalOutputSealer {
        var calls = 0
        override fun seal(
            manifest: AuthorizedUltraCompiledRuntimeManifest,
            issuer: AuthorizedUltraCompiledRuntimeManifestIssuer,
            token: UltraProfileSessionToken,
            proof: UltraProfilePhysicalGenerationProof,
            provisioning: AuthorizedUltraProfileProvisioning,
        ) {
            calls++
            onSeal(manifest, issuer, token, proof)
        }
    }

    private data class PhysicalSealFixture(
        val coordinator: UltraProfileActivationCoordinator,
        val token: UltraProfileSessionToken,
        val receipt: ReceiptFixture,
        val manifest: AuthorizedUltraCompiledRuntimeManifest,
        val preparation: FakePreparation,
        val child: AuthorizedUltraProfileProvisioning,
        val sealer: RecordingPhysicalSealer,
        val makeReplacementUiSync: () -> UiSyncRuntimeProvisioning,
    )

    private fun physicalSealFixture(
        seed: Int,
        supplySealer: Boolean = true,
        foreignSealerAuthority: Boolean = false,
        leaseCloseFailure: Throwable? = null,
        leaseCloseFailureCount: Int = 1,
        onSeal: (
            AuthorizedUltraCompiledRuntimeManifest,
            AuthorizedUltraCompiledRuntimeManifestIssuer,
            UltraProfileSessionToken,
            UltraProfilePhysicalGenerationProof,
        ) -> Unit = { _, _, _, _ -> },
    ): PhysicalSealFixture {
        val authentication = identity(seed)
        val receipt = receiptFixture(authentication)
        val profile = verifiedProfile("vehicle", authentication,
            phonePackageJson = twoMainDisplayPackage())
        val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID(0L, seed.toLong())),
            UltraDisplayOutputId.Android("panel-a"), UltraUiSyncDisplayId("opaque-panel-a"),
        )
        val physical = UiSyncPhysicalOutputRequirement(profile.candidate.contentDigest,
            listOf(UiSyncPhysicalOutputAssignment(output,
                UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN, "panel-a"))))
        val authority = PhysicalPreflightAuthority()
        val assignment = CompiledUiSyncDisplayAssignment(
            profile.candidate.entries.single {
                it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
            }.digest,
            0, "profile-0", DisplayType.CENTER_CONSOLE,
            DisplayDestination(DisplayDestinationRole.MAIN, DisplayDestinationId("panel-a")),
            output.topologyDisplayId, output.outputId, requireNotNull(output.uiSyncDisplayId),
            themeProgramSelection(profile, 0), emptyList(),
        )
        val program = deriveVerifiedUiSyncSemanticThemeProgramFingerprint(
            profile, listOf(assignment),
        )
        val uiSync = staticAuthorizedUiSyncProvisioning(output, physical, authority,
            program = program)
        val snapshot = requireNotNull(uiSync.authorizationSnapshot())
        val sealer = RecordingPhysicalSealer(
            if (foreignSealerAuthority) PhysicalPreflightAuthority() else authority, onSeal,
        )
        lateinit var manifest: AuthorizedUltraCompiledRuntimeManifest
        lateinit var preparation: FakePreparation
        val coordinator = coordinator { request ->
            manifest = manifestIssuer.issue(request, profile,
                CompiledUiSyncRuntimeContract.from(snapshot, listOf(assignment)), null)
            preparation = FakePreparation(request, profile,
                compiledManifestOverride = manifest,
                uiSyncProvisioning = uiSync,
                physicalOutputSealer = if (supplySealer) sealer else null,
                leaseCloseFailure = leaseCloseFailure,
                leaseCloseFailureCount = leaseCloseFailureCount,
            )
            UltraProfileBackendPrepareResult.Prepared(preparation)
        }
        val token = coordinator.beginSession("vehicle", receipt.receipt).started()
        val prepared = coordinator.prepare(token, receipt.receipt).prepared()
        val lease = coordinator.commit(prepared, receipt.receipt).activated()
        val child = (lease.openRuntime() as UltraProfileProvisioningOpenResult.Opened).runtimeLease
        return PhysicalSealFixture(coordinator, token, receipt, manifest, preparation,
            child, sealer) {
            staticAuthorizedUiSyncProvisioning(output, physical, authority, program = program)
        }
    }

    private inner class FakePreparation(
        request: UltraProfileActivationRequest,
        override val verifiedProfile: VerifiedUltraProfile,
        override val authenticationIdentity: MfiAuthenticationIdentity =
            request.authenticationIdentity,
        override val requestIdentity: UltraRuntimeIdentity = request.runtimeIdentity,
        private val commitFailure: Throwable? = null,
        private val leaseIdentityOverride: UltraProfileVerificationIdentity? = null,
        private val compiledManifestOverride: AuthorizedUltraCompiledRuntimeManifest? = null,
        private val leaseManifestOverride: AuthorizedUltraCompiledRuntimeManifest? = null,
        private val uiSyncProvisioning: UiSyncRuntimeProvisioning? = null,
        private val cafProvisioning: CafRuntimeProvisioning? = null,
        private val physicalOutputSealer: AuthorizedUltraPhysicalOutputSealer? = null,
        private val closeFailure: Throwable? = null,
        private val leaseCloseFailure: Throwable? = null,
        private val leaseCloseFailureCount: Int = 1,
    ) : AuthorizedUltraProfilePreparation {
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest =
            compiledManifestOverride
                ?: manifestIssuer.issue(
                    request,
                    verifiedProfile,
                    uiSync = null,
                    caf = testCompiledCafContract(),
                )
        var closed = false
        private var pendingCloseFailure = closeFailure
        var committedIdentity: UltraProfileVerificationIdentity? = null
        var leaseEverPublished = false
        lateinit var lease: FakeLease

        override fun commit(
            verificationIdentity: UltraProfileVerificationIdentity,
        ): AuthorizedUltraProfileBackendLease {
            commitFailure?.let { throw it }
            committedIdentity = verificationIdentity
            lease = FakeLease(
                leaseIdentityOverride ?: verificationIdentity,
                leaseManifestOverride ?: compiledManifest,
                uiSyncProvisioning = uiSyncProvisioning,
                cafProvisioning = cafProvisioning,
                physicalOutputSealer = physicalOutputSealer,
                closeFailure = leaseCloseFailure,
                closeFailureCount = leaseCloseFailureCount,
            )
            leaseEverPublished = true
            return lease
        }

        override fun close() {
            closed = true
            pendingCloseFailure?.let {
                pendingCloseFailure = null
                throw it
            }
        }
    }

    private class FakeLease(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        uiSyncProvisioning: UiSyncRuntimeProvisioning? = null,
        cafProvisioning: CafRuntimeProvisioning? = null,
        physicalOutputSealer: AuthorizedUltraPhysicalOutputSealer? = null,
        initialLive: Boolean = true,
        private val closeFailure: Throwable? = null,
        closeFailureCount: Int = 1,
    ) : AuthorizedUltraProfileBackendLease {
        private var live = initialLive
        var liveProbeEntered: CountDownLatch? = null
        var liveProbeRelease: CountDownLatch? = null
        override val isLive: Boolean
            get() {
                liveProbeEntered?.countDown()
                liveProbeRelease?.await(5, TimeUnit.SECONDS)
                return live
            }
        var closed = false
        var closeAttempts = 0
        var closeEntered: CountDownLatch? = null
        var closeRelease: CountDownLatch? = null
        private var closeFailuresRemaining = if (closeFailure == null) 0 else closeFailureCount
        val bundle = FakeBundle(
            verificationIdentity,
            compiledManifest,
            uiSyncProvisioning = uiSyncProvisioning,
            cafProvisioning = cafProvisioning,
            physicalOutputSealer = physicalOutputSealer,
        )

        override fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning = bundle

        override fun close() {
            closeAttempts++
            closeEntered?.countDown()
            closeRelease?.let { check(it.await(5, TimeUnit.SECONDS)) }
            closed = true
            live = false
            closeFailure?.let {
                if (closeFailuresRemaining > 0) {
                    closeFailuresRemaining--
                    throw it
                }
            }
        }
    }

    private class FakeBundle(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        override var isLive: Boolean = true,
        override var uiSyncProvisioning: UiSyncRuntimeProvisioning? = null,
        override val cafProvisioning: CafRuntimeProvisioning? = null,
        override val physicalOutputSealer: AuthorizedUltraPhysicalOutputSealer? = null,
    ) : AuthorizedUltraProfileBackendProvisioning {
        var closed = false

        override fun close() {
            closed = true
            isLive = false
        }
    }

    private fun authorizedCafProvisioning(): CafRuntimeProvisioning {
        val pluginId = 7L
        val handlerImplementationId = "test.caf.handler.v1"
        val tree = CafConfigTree.decode(
            linkedMapOf(
                "accessories" to listOf(
                    linkedMapOf(
                        "type" to 0x0000000001000001L,
                        "iid" to 1L,
                        "version" to "test-only-1",
                        "services" to listOf(
                            linkedMapOf(
                                "type" to 0x0000000011000002L,
                                "iid" to 2L,
                                "characteristics" to listOf(
                                    linkedMapOf(
                                        "type" to 0x0000000031000019L,
                                        "iid" to 3L,
                                        "format" to 9L,
                                        "priority" to 0L,
                                        "initialValue" to 21.5,
                                    ),
                                    linkedMapOf(
                                        "type" to 0x00000000FF000002L,
                                        "iid" to 4L,
                                        "format" to 1L,
                                        "priority" to 1L,
                                        "initialValue" to 1L,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val handlerFactory = object : CafPluginHandlerFactory {
            override val implementationId: String = handlerImplementationId

            override fun create(session: CafProtocolSession): CafPluginHandler =
                object : CafSchemaBoundPluginHandler {
                    override val implementationId: String = handlerImplementationId
                    override fun schemaSnapshot(): CafConfigTree = tree
                }
        }
        val registry = CafPluginRegistry.from(
            listOf(
                CafPluginRegistration(
                    pluginId = pluginId,
                    protocolVersion = "1.0",
                    clientTypes = CafPluginRegistry.DEFAULT_CLIENT_TYPES,
                    handlerFactory = handlerFactory,
                    schemaProvider = { tree },
                    runtimeReady = { true },
                ),
            ),
        )
        return CafRuntimeProvisioning.fromAuthorizedProfile(
            source = registry,
            authorizationExpectation = CafRuntimeAuthorizationExpectation(
                decoderImplementationId = "xcertplay.caf.decoder.firmware-v1",
                handlerImplementationIds = mapOf(pluginId to handlerImplementationId),
            ),
        )
    }

    private fun compiledUiSyncContract(
        displayIds: Collection<String>,
        outputs: Collection<UltraDisplayGraphOutput>,
        ingresses: Collection<UiSyncRendererIngressSpec>,
    ): CompiledUiSyncRuntimeContract = CompiledUiSyncRuntimeContract(
        schemaFingerprint = fingerprint(UiSyncSchemaFingerprint::class.java, 0x21),
        bindingFingerprint = fingerprint(UiSyncBindingFingerprint::class.java, 0x22),
        semanticThemeProgramFingerprint =
            UiSyncSemanticThemeProgramFingerprint(ByteArray(32) { 0x23 }),
        rendererImplementationId = "test.renderer.v1",
        displayIds = displayIds,
        outputs = outputs,
        ingressSpecs = ingresses,
        displayAssignments = outputs.mapIndexed { index, output ->
            CompiledUiSyncDisplayAssignment(
                phonePackageDigest = com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest(
                    ByteArray(32) { 0x44 },
                ),
                profileDisplayIndex = index,
                profileDisplayId = "profile-$index",
                profileDisplayType = DisplayType.CENTER_CONSOLE,
                destination = DisplayDestination(
                    DisplayDestinationRole.MAIN, DisplayDestinationId("instance-$index"),
                ),
                topologyDisplayId = output.topologyDisplayId,
                outputId = output.outputId,
                uiSyncDisplayId = requireNotNull(output.uiSyncDisplayId),
                programSelection = CompiledUiSyncThemeProgramSelection(
                    0, CarPlayThemeSceneState(),
                    CarPlayThemeStructuralFingerprint(ByteArray(32) { 0x45 }),
                ),
                slotIngressAssignments = emptyList(),
            )
        },
    )

    private fun CompiledUiSyncDisplayAssignment.copyForTest(
        phonePackageDigest: com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest =
            this.phonePackageDigest,
        profileDisplayIndex: Int = this.profileDisplayIndex,
        profileDisplayId: String? = this.profileDisplayId,
        profileDisplayType: DisplayType = this.profileDisplayType,
        destination: DisplayDestination = this.destination,
        topologyDisplayId: UltraTopologyDisplayId = this.topologyDisplayId,
        outputId: UltraDisplayOutputId = this.outputId,
        uiSyncDisplayId: UltraUiSyncDisplayId = this.uiSyncDisplayId,
        programSelection: CompiledUiSyncThemeProgramSelection = this.programSelection,
        slotIngressAssignments: Collection<CompiledUiSyncSlotIngressAssignment> =
            this.slotIngressAssignments,
    ) = CompiledUiSyncDisplayAssignment(
        phonePackageDigest, profileDisplayIndex, profileDisplayId, profileDisplayType,
        destination, topologyDisplayId, outputId, uiSyncDisplayId, programSelection,
        slotIngressAssignments,
    )

    private fun themeProgramSelection(
        profile: VerifiedUltraProfile,
        displayIndex: Int,
        layoutIndex: Int = 0,
        state: CarPlayThemeSceneState = CarPlayThemeSceneState(),
    ): CompiledUiSyncThemeProgramSelection {
        val display = CarPlayThemeSchema.parseArcPackageJson(requireNotNull(
            profile.candidate.content(UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON),
        )).displays[displayIndex]
        return CompiledUiSyncThemeProgramSelection(
            layoutIndex, state,
            CarPlayThemeProgramCompiler().compile(display, state)
                .structuralProgram.structuralFingerprint,
        )
    }

    private fun twoMainDisplayPackage(): ByteArray = """{
        "identifier":"test-two-main","version":1,"minimumSDKVersion":"1",
        "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
        "displays":[
          {"id":"profile-0","type":"centerConsole","layouts":[
            {"id":"first","layoutType":"primary","zones":[],"backgroundColor":"#111111",
             "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{}},
            {"id":"second","layoutType":"primary","zones":[],"backgroundColor":"#222222",
             "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{}}
           ],"size":[800,480],
           "density":1,"allowManufacturerAppIcon":false,"requiresDarkAppearance":false},
          {"id":"profile-1","type":"centerConsole","layouts":[
            {"id":"first","layoutType":"primary","zones":[],"backgroundColor":"#111111",
             "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{}},
            {"id":"second","layoutType":"primary","zones":[],"backgroundColor":"#222222",
             "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{}}
           ],"size":[800,480],
           "density":1,"allowManufacturerAppIcon":false,"requiresDarkAppearance":false}
        ]
    }""".trimIndent().encodeToByteArray()

    private fun v3CarouselPackage(): ByteArray = """{
      "identifier":"test-v3-carousel","version":1,"minimumSDKVersion":"1",
      "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
      "displays":[{"id":"profile-0","type":"centerConsole","size":[800,480],
        "density":1,"allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
        "layouts":[{"id":"main-carousel-canvas","layoutType":"primary",
          "backgroundColor":"#101820","safeAreaInsets":[0,0,0,0],
          "driveModeThemeConfigurations":{},
          "zones":[{"id":"main-carousel-zone","frame":[[0,0],[800,480]],
            "type":{"mapsMedia":{}},"components":[
              {"id":"map","type":"primary","size":[800,480],
               "slots":[{"style":{"rectangular":{}},"frame":[[0,0],[800,480]],
                 "zIndex":0,"positionId":"local-map"}]},
              {"id":"media","type":"primary","size":[800,480],
               "slots":[{"style":{"rectangular":{}},"frame":[[0,0],[800,480]],
                 "zIndex":0,"positionId":"local-media"}]}]}]}]}]
    }""".trimIndent().encodeToByteArray()

    private fun v3CarouselCatalog(primaryPalette: String = "palette-a"): ByteArray {
        require(primaryPalette == "palette-a" || primaryPalette == "palette-b")
        fun variant(componentIndex: Int, componentId: String, paletteId: String,
            contentId: String): String = """{
              "layoutIndex":0,"layoutID":"main-carousel-canvas","paletteID":"$paletteId",
              "activeComponents":{"main-carousel-zone":"$componentId"},
              "activePrimarySelections":[{"zoneIndex":0,"componentIndex":$componentIndex}],
              "slotIngresses":[],"localCanvasSlots":[
                {"zoneIndex":0,"componentIndex":$componentIndex,"slotIndex":0,
                 "contentId":"$contentId"}]
            }""".trimIndent()
        return """{
          "format":"xcertplay-debug-uisync-v3","displays":[{
            "profileDisplayIndex":0,"profileDisplayId":"profile-0",
            "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}",
            "destinationRole":"MAIN","destinationInstanceId":"panel",
            "topologyDisplayId":"${UUID(0L, 111L)}",
            "outputId":{"kind":"android","value":"panel"},
            "uiSyncDisplayId":"opaque-panel","layouts":[
              ${variant(0, "map", primaryPalette, "canvas.main.bench")},
              ${variant(1, "media", "palette-a", "canvas.main.carousel.media")},
              ${variant(0, "map", if (primaryPalette == "palette-a") "palette-b" else
                  "palette-a", "canvas.main.carousel.map.alt")}
            ]
          }]
        }""".trimIndent().encodeToByteArray()
    }

    private fun v1VideoCatalog(): ByteArray = """{
      "format":"xcertplay-debug-uisync-v1","displays":[{
        "profileDisplayIndex":0,"profileDisplayId":"profile",
        "advertisedScreenId":"${AirPlayInfoPlist.MAIN_UUID}",
        "destinationRole":"MAIN","destinationInstanceId":"panel",
        "topologyDisplayId":"${UUID(0L, 112L)}",
        "outputId":{"kind":"android","value":"panel"},
        "uiSyncDisplayId":"opaque-panel","layouts":[{
          "layoutIndex":0,"layoutID":"layout","paletteID":"palette-a",
          "activeComponents":{},
          "activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
          "slotIngresses":[{"zoneIndex":0,"componentIndex":0,"slotIndex":0,
            "streamIdentifier":"video","ingressId":"ingress.video"}]
        }]
      }]
    }""".trimIndent().encodeToByteArray()

    private fun slotBindingPackage(): ByteArray = """{
      "identifier":"slot-binding","version":1,"minimumSDKVersion":"1",
      "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
      "displays":[{"id":"profile","type":"centerConsole","size":[400,200],"density":2,
        "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
        "layouts":[{"id":"layout","layoutType":"primary",
          "backgroundColor":"#ff0000","safeAreaInsets":[0,0,0,0],
          "driveModeThemeConfigurations":{},
          "zones":[{"id":"zone","frame":[[0,0],[400,200]],"type":{"single":{}},
            "components":[{"id":"primary","type":"primary","size":[400,200],
              "slots":[{"style":{"rectangular":{}},"frame":[[0,0],[100,100]],
                "zIndex":0,"positionId":"slot","instrument":{
                  "kind":{"inputStream":{"_0":"video"}},
                  "backgroundColor":"#00000000","clipsToShape":false
                }}]
            }]
          }]
        }]
      }]
    }""".trimIndent().encodeToByteArray()

    private fun twoSlotVariantPackage(): ByteArray {
        fun layout(id: String, stream: String) = """{
          "id":"$id","layoutType":"primary","backgroundColor":"#ff0000",
          "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{},
          "zones":[{"id":"zone","frame":[[0,0],[400,200]],"type":{"single":{}},
            "components":[{"id":"primary","type":"primary","size":[400,200],
              "slots":[{"style":{"rectangular":{}},"frame":[[0,0],[100,100]],
                "zIndex":0,"positionId":"slot","instrument":{
                  "kind":{"inputStream":{"_0":"$stream"}},
                  "backgroundColor":"#00000000","clipsToShape":false
                }}]
            }]
          }]
        }""".trimIndent()
        return """{
          "identifier":"two-slot-variants","version":1,"minimumSDKVersion":"1",
          "buildType":"debug","partnerID":"test","driveModeLinkedByDefault":false,
          "displays":[{"id":"profile","type":"centerConsole","size":[400,200],
            "density":2,"allowManufacturerAppIcon":false,
            "requiresDarkAppearance":false,
            "layouts":[${layout("first", "video-a")},${layout("second", "video-b")}]
          }]
        }""".trimIndent().encodeToByteArray()
    }

    private fun testCompiledCafContract(): CompiledCafRuntimeContract =
        CompiledCafRuntimeContract(
            schemaFingerprint = fingerprint(CafSchemaFingerprint::class.java, 0x31),
            decoderImplementationId = "test.caf.decoder.v1",
            handlerImplementationIds = mapOf(7L to "test.caf.handler.v1"),
        )

    private fun staticAuthorizedUiSyncProvisioning(
        output: UltraDisplayGraphOutput,
        physicalOutputRequirement: UiSyncPhysicalOutputRequirement? = null,
        physicalOutputAuthority: UiSyncPhysicalOutputAuthority? = null,
        program: UiSyncSemanticThemeProgramFingerprint =
            UiSyncSemanticThemeProgramFingerprint(ByteArray(32) { 0x64 }),
    ): UiSyncRuntimeProvisioning {
        val implementation = "test.renderer.authorized-display.v1"
        val capability = object : UiSyncCompleteRendererCapability {
            override fun acquire(
                context: UiSyncCompleteRendererBindingContext,
            ): UiSyncCompleteRendererFactoryLease = object : UiSyncCompleteRendererFactoryLease {
                private var live = true
                override val isLive: Boolean get() = live
                override val implementationId: String = implementation
                override val schemaFingerprint = context.schemaFingerprint
                override val bindingFingerprint = context.bindingFingerprint
                override val displayIds = context.displayIds
                override val readinessReceipt: RendererReadinessReceipt =
                    context.issueReadinessReceipt(
                        program, emptySet(), emptySet(),
                        semanticCompilerActive = true, themeCompilerActive = true,
                        backendLive = true,
                    )

                override fun openSession(
                    context: UiSyncCompleteRendererSessionBindingContext,
                ): UiSyncCompleteRendererSessionLease = object : UiSyncCompleteRendererSessionLease {
                    private var sessionLive = true
                    override val isLive: Boolean get() = sessionLive
                    override val implementationId: String = implementation
                    override val schemaFingerprint = context.schemaFingerprint
                    override val bindingFingerprint = context.bindingFingerprint
                    override val displayIds = context.displayIds
                    override val readinessReceipt: RendererReadinessReceipt =
                        context.issueReadinessReceipt(
                            program, emptySet(), emptySet(),
                            semanticCompilerActive = true, themeCompilerActive = true,
                            backendLive = true,
                        )
                    override fun prepareScreen(context: UiSyncRendererScreenContext):
                        UiSyncRendererScreenCandidate? = null
                    override fun retireScreens(): Boolean = true
                    override fun close() { sessionLive = false }
                }

                override fun close() { live = false }
            }
        }
        val pending = ArrayDeque<Runnable>()
        val executor = object : UiSyncBoundedPresentationExecutor {
            override val maximumPendingCallbacks: Int = 64
            override val parallelism: Int = 1
            override val mayExecuteInline: Boolean = false
            override fun execute(command: Runnable) {
                if (pending.size >= maximumPendingCallbacks) {
                    throw RejectedExecutionException("Test callback queue is full")
                }
                pending.addLast(command)
            }
        }
        return UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "test")),
            displays = listOf(UiSyncDisplayRuntimeSpec(
                CarPlayUiSyncChannelDescriptor(requireNotNull(output.uiSyncDisplayId).value,
                    "vehicle"),
                CarPlayUiSyncMode.PASSIVE,
            )),
            staticPlan = UiSyncRendererStaticPlan(listOf(output), emptyList()),
            rendererCapability = capability,
            callbackExecutor = executor,
            authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                program, implementation, physicalOutputRequirement,
            ),
            physicalOutputAuthority = physicalOutputAuthority,
        )
    }

    private fun <T : Any> fingerprint(type: Class<T>, seed: Int): T {
        val constructor = type.getDeclaredConstructor(ByteArray::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(ByteArray(32) { seed.toByte() })
    }

    private fun identity(seed: Int): MfiAuthenticationIdentity =
        MfiAuthenticationIdentity.capture(
            object : MfiAuthenticator {
                override val certificateType = MfiCertificateType.MFI
                override fun protocolMajor(): Int = 1
                override fun readCertificate(maximumOutputLength: Int): ByteArray =
                    byteArrayOf(seed.toByte(), (seed + 1).toByte(), (seed + 2).toByte())

                override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
            },
        )

    private data class ReceiptFixture(
        val receipt: MfiAuthenticationReceipt,
        val beginReplacement: () -> Unit,
        val retryRevocationCleanup: () -> Unit,
    )

    private data class ReceiptHandoffFixture(
        val predecessor: MfiAuthenticationReceipt,
        val handoff: MfiAuthenticationHandoff,
        val successor: MfiAuthenticationReceipt,
        val revokeSuccessor: () -> Unit,
    )

    private fun receipt(identity: MfiAuthenticationIdentity): MfiAuthenticationReceipt =
        receiptFixture(identity).receipt

    private fun receiptFixture(identity: MfiAuthenticationIdentity): ReceiptFixture {
        val authorityClass = Class.forName(
            "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
        )
        val authorityConstructor = authorityClass.declaredConstructors.single { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(MfiAuthenticationIdentity::class.java),
            )
        }
        authorityConstructor.isAccessible = true
        val authority = authorityConstructor.newInstance(identity)
        val channelIdentity = Any()
        val begin = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("begin") && method.parameterCount == 2
        }
        begin.isAccessible = true
        val attempt = begin.invoke(authority, channelIdentity, 1L)
        val issue = attempt.javaClass.declaredMethods.single { method ->
            method.name.startsWith("issue") && method.parameterCount == 2
        }
        issue.isAccessible = true
        val receipt = issue.invoke(attempt, channelIdentity, identity) as MfiAuthenticationReceipt
        val retry = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("retryRevocationCleanup") && method.parameterCount == 0
        }.apply { isAccessible = true }
        return ReceiptFixture(
            receipt = receipt,
            beginReplacement = { begin.invoke(authority, Any(), 2L) },
            retryRevocationCleanup = { retry.invoke(authority) },
        )
    }

    private fun receiptHandoffFixture(
        identity: MfiAuthenticationIdentity,
    ): ReceiptHandoffFixture {
        val authorityClass = Class.forName(
            "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
        )
        val authorityConstructor = authorityClass.declaredConstructors.single { constructor ->
            constructor.parameterTypes.contentEquals(
                arrayOf(MfiAuthenticationIdentity::class.java),
            )
        }
        authorityConstructor.isAccessible = true
        val authority = authorityConstructor.newInstance(identity)
        val begin = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("begin") && !method.name.startsWith("beginHandoff") &&
                method.parameterCount == 2
        }.apply { isAccessible = true }
        val firstChannel = Any()
        val firstAttempt = begin.invoke(authority, firstChannel, 1L)
        val issue = firstAttempt.javaClass.declaredMethods.single { method ->
            method.name.startsWith("issue") && method.parameterCount == 2
        }.apply { isAccessible = true }
        val predecessor = issue.invoke(
            firstAttempt,
            firstChannel,
            identity,
        ) as MfiAuthenticationReceipt

        val secondChannel = Any()
        val beginHandoff = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("beginHandoff") && method.parameterCount == 3
        }.apply { isAccessible = true }
        val handoff = beginHandoff.invoke(
            authority,
            predecessor,
            secondChannel,
            2L,
        ) as MfiAuthenticationHandoff
        val attemptMethod = handoff.javaClass.declaredMethods.single { method ->
            method.name.startsWith("attemptForExchange") && method.parameterCount == 0
        }.apply { isAccessible = true }
        val successorAttempt = attemptMethod.invoke(handoff)
        val successor = issue.invoke(
            successorAttempt,
            secondChannel,
            identity,
        ) as MfiAuthenticationReceipt
        return ReceiptHandoffFixture(
            predecessor = predecessor,
            handoff = handoff,
            successor = successor,
            revokeSuccessor = { begin.invoke(authority, Any(), 3L) },
        )
    }

    /** The production constructor deliberately has no provider injection seam. */
    private fun coordinator(
        backend: UltraProfileActivationBackend,
    ): UltraProfileActivationCoordinator {
        val constructor = UltraProfileActivationCoordinator::class.java
            .getDeclaredConstructor(
                UltraProfileActivationBackend::class.java,
                AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
            )
        constructor.isAccessible = true
        return constructor.newInstance(backend, manifestIssuer)
    }

    private fun verifiedProfile(
        vehicleId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
        proof: ByteArray = byteArrayOf(1, 2, 3),
        phonePackageJson: ByteArray = "{}".encodeToByteArray(),
        signedUiSyncCatalogJson: ByteArray? = null,
        signedUiSyncCatalogVersion: Int = 3,
        resolvedThemeJson: ByteArray = "{}".encodeToByteArray(),
    ): VerifiedUltraProfile {
        val archive = zip(
            listOf(
                UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to phonePackageJson,
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to resolvedThemeJson,
                UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                    BplistCodec.encode(mapOf("Bundle" to "Phone")),
                "CarPlayPhone.assets/layout.bin" to "layout".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to
                    "accessory-assets".encodeToByteArray(),
                UltraProfilePackageFiles.CERTIFICATE_CHAIN to
                    "certificate-chain".encodeToByteArray(),
                UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to
                    "opaque-sha256".encodeToByteArray(),
                UltraProfilePackageFiles.ACCESSORY_ADDITIONS to
                    "accessory-additions".encodeToByteArray(),
                UltraProfilePackageFiles.INFO_PLIST to
                    BplistCodec.encode(mapOf("Version" to "1")),
            ) + listOfNotNull(signedUiSyncCatalogJson?.let {
                require(signedUiSyncCatalogVersion in 1..3)
                "CarPlayPhone.assets/xcertplay-bench-uisync-v$signedUiSyncCatalogVersion.json" to it
            }),
        )
        val inspection = UltraProfilePackageInspector().inspect(ByteArrayInputStream(archive))
        val candidate = (inspection as UltraProfileInspectionResult.Candidate).profile
        val binding = binding(candidate, vehicleId, authenticationIdentity)
        val verification = UltraProfilePackageInspector().verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, proof))
        }
        return (verification as UltraProfileVerificationResult.Verified).profile
    }

    private fun binding(
        candidate: UltraProfileCandidate,
        vehicleId: String,
        authenticationIdentity: MfiAuthenticationIdentity,
    ) =
        UltraProfileTrustBinding(
            vehicleId = vehicleId,
            mfiCertificateIdentity = buildString {
                append("xcertplay-mfi-authentication-v1:")
                append(authenticationIdentity.certificateType.name)
                append(':')
                append(authenticationIdentity.sha256Hex())
            },
            assetIdentifier = "asset",
            iosContentVersion = "ios",
            accessoryContentVersion = "accessory",
            contentDigest = candidate.contentDigest,
        )

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun UltraProfileSessionStartResult.started(): UltraProfileSessionToken =
        (this as UltraProfileSessionStartResult.Started).token

    private fun UltraProfileSessionStartResult.denied(): UltraProfileActivationDenialCode =
        (this as UltraProfileSessionStartResult.Denied).code

    private fun UltraProfilePrepareResult.prepared(): PreparedUltraProfileActivation =
        (this as UltraProfilePrepareResult.Prepared).activation

    private fun UltraProfilePrepareResult.denied(): UltraProfileActivationDenialCode =
        (this as UltraProfilePrepareResult.Denied).code

    private fun UltraProfileCommitResult.activated(): AuthorizedUltraProfileLease =
        (this as UltraProfileCommitResult.Activated).lease

    private fun UltraProfileCommitResult.denied(): UltraProfileActivationDenialCode =
        (this as UltraProfileCommitResult.Denied).code

    private fun UltraProfileProvisioningOpenResult.denied(): UltraProfileActivationDenialCode =
        (this as UltraProfileProvisioningOpenResult.Denied).code
}

