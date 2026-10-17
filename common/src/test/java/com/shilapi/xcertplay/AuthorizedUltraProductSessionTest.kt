package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.mfi.MfiAuthenticationHandoff
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.orchestration.CarPlayUltraAuthorizationDenialCode
import com.shilapi.xcertplay.orchestration.CarPlayUltraAuthorizationResult
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedUltraProductSessionTest {
    @Test
    fun consentWriteBarrierKeepsExactOldOwnerWhenCoverAckFailsThenRetriesSameTicket() {
        val queued = AtomicReference<Runnable?>()
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle"))
        val oldOwner = FakeCoordinatorPort(
            policyCoverAllowed = false,
            onPolicyCover = {
                val rootProbeFinished = CountDownLatch(1)
                Thread {
                    UltraProfileCompositionRoot.capturePolicyEpoch()
                    rootProbeFinished.countDown()
                }.start()
                assertTrue("Host cover ACK must run outside the root monitor",
                    rootProbeFinished.await(2, TimeUnit.SECONDS))
            },
        )
        val product = productSession(epoch, { oldOwner },
            UltraPolicyRetirementDispatcher { task ->
                queued.set(task)
                true
            })
        val exactReceipt = receipt(identity(46))
        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated
        val field = UltraProfileCompositionRoot::class.java.getDeclaredField("capturedEpochs")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val captured = field.get(UltraProfileCompositionRoot) as
            MutableList<WeakReference<UltraProfilePolicyEpoch>>
        captured += WeakReference(epoch)

        assertNull(UltraProfileCompositionRoot.beginConsentMutation())
        assertFalse(epoch.isLive())
        assertFalse(product.isCurrentActivation(activated, exactReceipt))
        assertEquals(0, oldOwner.closeCalls)
        assertEquals(1, oldOwner.policyCoverCalls)
        assertNotNull(queued.get())

        oldOwner.policyCoverAllowed = true
        requireNotNull(UltraProfileCompositionRoot.beginConsentMutation()).close()
        assertEquals(2, oldOwner.policyCoverCalls)
        requireNotNull(queued.get()).run()
        assertEquals(1, oldOwner.closeCalls)
        assertFalse(oldOwner.runtime.isLive())
        product.close()
    }

    @Test
    fun debugRootConsentWithdrawalRevokesItsAlreadyCapturedEpoch() {
        val epoch = UltraProfileCompositionRoot.capturePolicyEpoch()
        assertTrue(epoch.isLive())

        requireNotNull(UltraProfileCompositionRoot.beginConsentMutation()).close()

        assertFalse(epoch.isLive())
    }

    @Test
    fun rootMutationTicketDeniesConcurrentWriterAndAnyEpochCapturedMidWrite() {
        val old = UltraProfileCompositionRoot.capturePolicyEpoch()
        val ticket = requireNotNull(UltraProfileCompositionRoot.beginConsentMutation())
        val during = UltraProfileCompositionRoot.capturePolicyEpoch()

        assertFalse(old.isLive())
        assertFalse(during.isLive())
        assertNull(UltraProfileCompositionRoot.beginConsentMutation())

        ticket.close()
        ticket.close()
        assertFalse(during.isLive())
        assertTrue(UltraProfileCompositionRoot.capturePolicyEpoch().isLive())
    }

    @Test
    fun notifiedConsentRevocationImmediatelyDeniesChildThenRetiresItsExactOwner() {
        val queued = AtomicReference<Runnable?>()
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle"))
        val exactOwner = FakeCoordinatorPort()
        val product = productSession(epoch, { exactOwner },
            UltraPolicyRetirementDispatcher { task ->
                assertTrue(queued.compareAndSet(null, task))
                true
            })
        val exactReceipt = receipt(identity(49))
        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        epoch.revoke()

        assertFalse(product.isCurrentActivation(activated, exactReceipt))
        assertEquals(0, exactOwner.closeCalls)
        requireNotNull(queued.get()).run()
        assertEquals(1, exactOwner.closeCalls)
        assertFalse(exactOwner.runtime.isLive())
        product.close()
    }

    @Test
    fun notifiedRevocationRollsBackAbandonedRenewalBeforeClosingExactOwner() {
        val queued = AtomicReference<Runnable?>()
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle"))
        val exactOwner = FakeCoordinatorPort()
        val product = productSession(epoch, { exactOwner },
            UltraPolicyRetirementDispatcher { task ->
                queued.set(task)
                true
            })
        val fixture = receiptHandoffFixture(identity(48))
        val activated = product.activate(fixture.predecessor)
            as CarPlayUltraAuthorizationResult.Activated
        lateinit var prepared: com.shilapi.xcertplay.orchestration.CarPlayUltraPreparedRenewal
        fixture.handoff.commit(fixture.successor) { successor ->
            prepared = product.prepareRenewal(fixture.predecessor, successor)
        }

        epoch.revoke()
        assertFalse(product.isCurrentActivation(activated, fixture.predecessor))
        requireNotNull(queued.get()).run()

        assertEquals(1, exactOwner.rollbackCalls)
        assertEquals(1, exactOwner.closeCalls)
        assertFalse(exactOwner.runtime.isLive())
        prepared.close()
        product.close()
    }

    @Test
    fun failedRetirementKeepsExactOwnerAndNextDeniedAdmissionRetriesIt() {
        val tasks = ArrayDeque<Runnable>()
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle"))
        val exactOwner = FakeCoordinatorPort(closeFailures = 1)
        val product = productSession(epoch, { exactOwner },
            UltraPolicyRetirementDispatcher { task ->
                tasks.addLast(task)
                true
            })
        val exactReceipt = receipt(identity(47))
        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        epoch.revoke()
        tasks.removeFirst().run()
        assertEquals(1, exactOwner.closeCalls)
        assertTrue(exactOwner.runtime.isLive())
        assertFalse(product.isCurrentActivation(activated, exactReceipt))
        tasks.removeFirst().run()

        assertEquals(2, exactOwner.closeCalls)
        assertFalse(exactOwner.runtime.isLive())
        product.close()
        assertEquals(2, exactOwner.closeCalls)
    }

    @Test
    fun releaseAckDuringInFlightFalseCoverSchedulesOneExactRetirementWakeup() {
        val firstCoverEntered = CountDownLatch(1)
        val releaseFirstCover = CountDownLatch(1)
        val firstWorkerFinished = CountDownLatch(1)
        val nextTask = AtomicReference<Runnable?>()
        val firstCoverCalls = AtomicInteger()
        val oldOwner = FakeCoordinatorPort(
            policyCoverDecision = {
                if (firstCoverCalls.incrementAndGet() == 1) {
                    firstCoverEntered.countDown()
                    assertTrue(releaseFirstCover.await(5, TimeUnit.SECONDS))
                    false
                } else true
            },
        )
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle"))
        val dispatcherCalls = AtomicInteger()
        val product = productSession(epoch, { oldOwner },
            UltraPolicyRetirementDispatcher { task ->
                if (dispatcherCalls.incrementAndGet() == 1) {
                    Thread {
                        task.run()
                        firstWorkerFinished.countDown()
                    }.start()
                } else nextTask.set(task)
                true
            })
        val exactReceipt = receipt(identity(45))
        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        assertTrue(epoch.markRevoked())
        assertFalse(product.isCurrentActivation(activated, exactReceipt))
        assertTrue(firstCoverEntered.await(5, TimeUnit.SECONDS))
        assertTrue(epoch.coverAck())
        epoch.releaseRetirementAfterCover()
        releaseFirstCover.countDown()
        assertTrue(firstWorkerFinished.await(5, TimeUnit.SECONDS))
        assertEquals(0, oldOwner.closeCalls)

        requireNotNull(nextTask.get()).run()
        assertEquals(1, oldOwner.closeCalls)
        assertFalse(oldOwner.runtime.isLive())
        product.close()
    }

    @Test
    fun coordinatorConstructedFromEpochUsesItsProviderAndIssuerAfterGlobalSelectionChanges() {
        val firstAcquireCalls = AtomicInteger()
        val firstProvider = object : UltraProfileDeploymentProvider {
            override fun acquire(request: UltraProfileActivationRequest): UltraProfileDeploymentResult {
                firstAcquireCalls.incrementAndGet()
                return UltraProfileDeploymentResult.Unavailable
            }

            override fun stage(
                request: UltraProfileActivationRequest,
                candidateArchive: InputStream,
            ): UltraProfileDeploymentResult = UltraProfileDeploymentResult.Unavailable
        }
        val firstIssuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 1 })
        val first = UltraProfilePolicyEpoch(
            vehicleIdentityAuthority = resolvedAuthority("trusted-vehicle"),
            deploymentProvider = firstProvider,
            manifestIssuer = firstIssuer,
            ultraConsentSelected = true,
            uiSyncConsentSelected = false,
            currentSelection = { true },
        )
        val coordinator = UltraProfileActivationCoordinator.fromPolicyEpoch(first)
        val issuerField = coordinator.javaClass.getDeclaredField("manifestIssuer")
            .apply { isAccessible = true }
        assertSame(firstIssuer, issuerField.get(coordinator))
        val exactReceipt = receipt(identity(50))

        val started = coordinator.beginSession("trusted-vehicle", exactReceipt)
            as UltraProfileSessionStartResult.Started
        val denied = coordinator.prepare(started.token, exactReceipt)
            as UltraProfilePrepareResult.Denied

        assertEquals(UltraProfileActivationDenialCode.PROVIDER_UNAVAILABLE, denied.code)
        assertEquals(1, firstAcquireCalls.get())
        coordinator.close()
    }

    @Test
    fun rootSwapDuringIdentityResolutionCannotMixIdentityAndDeploymentEpochs() {
        val selected = AtomicReference<UltraProfilePolicyEpoch>()
        val next = testEpoch(resolvedAuthority("other-vehicle")) { selected.get() === it }
        val factoryCalls = AtomicInteger()
        lateinit var first: UltraProfilePolicyEpoch
        first = testEpoch(
            TrustedUltraVehicleIdentityAuthority {
                selected.set(next)
                TrustedUltraVehicleIdentityResult.Resolved("trusted-vehicle")
            },
        ) { selected.get() === it }
        selected.set(first)
        val product = productSession(first, {
            factoryCalls.incrementAndGet()
            FakeCoordinatorPort()
        })
        val unusedReceipt = receipt(identity(51))

        val denied = product.activate(unusedReceipt) as CarPlayUltraAuthorizationResult.Denied

        assertEquals(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION, denied.code)
        assertEquals(0, factoryCalls.get())
        // No coordinator reached beginSession, so this exact AA05 capability remains unconsumed.
        val proof = UltraProfileActivationCoordinator()
        assertTrue(proof.beginSession("proof-only-vehicle", unusedReceipt) is
            UltraProfileSessionStartResult.Started)
        proof.close()
        product.close()
    }

    @Test
    fun rootSwapInsideFactoryRetiresOnlyCapturedOwnerBeforeAa05() {
        val selected = AtomicReference<UltraProfilePolicyEpoch>()
        val next = testEpoch(resolvedAuthority("other-vehicle")) { selected.get() === it }
        lateinit var first: UltraProfilePolicyEpoch
        first = testEpoch(resolvedAuthority("trusted-vehicle")) { selected.get() === it }
        selected.set(first)
        val exactOwner = FakeCoordinatorPort()
        val product = productSession(first, { captured ->
            assertSame(first, captured)
            assertSame(first.deploymentProvider, captured.deploymentProvider)
            assertSame(first.manifestIssuer, captured.manifestIssuer)
            selected.set(next)
            exactOwner
        })
        val unusedReceipt = receipt(identity(52))

        val denied = product.activate(unusedReceipt) as CarPlayUltraAuthorizationResult.Denied

        assertEquals(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION, denied.code)
        assertEquals(listOf("close"), exactOwner.calls)
        val proof = UltraProfileActivationCoordinator()
        assertTrue(proof.beginSession("proof-only-vehicle", unusedReceipt) is
            UltraProfileSessionStartResult.Started)
        proof.close()
        product.close()
    }

    @Test
    fun sameCapturedEpochFeedsIdentityAndCoordinatorThroughAuthorizedActivation() {
        val selected = AtomicReference<UltraProfilePolicyEpoch>()
        lateinit var epoch: UltraProfilePolicyEpoch
        epoch = testEpoch(resolvedAuthority("trusted-vehicle")) { selected.get() === it }
        selected.set(epoch)
        val exactOwner = FakeCoordinatorPort()
        val product = productSession(epoch, { captured ->
            assertSame(epoch, captured)
            assertSame(epoch.vehicleIdentityAuthority, captured.vehicleIdentityAuthority)
            assertSame(epoch.deploymentProvider, captured.deploymentProvider)
            assertSame(epoch.manifestIssuer, captured.manifestIssuer)
            exactOwner
        })
        val exactReceipt = receipt(identity(53))

        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        assertTrue(product.isCurrentActivation(activated, exactReceipt))
        assertEquals(listOf("begin", "prepare", "commit", "open"), exactOwner.calls)
        product.close()
    }

    @Test
    fun clusterAssetProbeRequiresCurrentConsentReceiptAndCoordinatorMatch() {
        val epoch = UltraProfilePolicyEpoch(
            vehicleIdentityAuthority = resolvedAuthority("trusted-vehicle"),
            deploymentProvider = DisabledUltraProfileDeploymentProvider,
            manifestIssuer = UltraProfileCompositionRoot.manifestIssuer(),
            ultraConsentSelected = true,
            uiSyncConsentSelected = true,
            currentSelection = { true },
        )
        val owner = FakeCoordinatorPort(catalogAssetId = "signed.asset")
        val product = productSession(epoch, { owner })
        val exactIdentity = identity(88)
        val exactReceipt = receipt(exactIdentity)
        product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        assertTrue(product.allowsClusterAssetAcquisition("signed.asset", exactIdentity))
        assertFalse(product.allowsClusterAssetAcquisition("other.asset", exactIdentity))
        assertFalse(product.allowsClusterAssetAcquisition("signed.asset", identity(89)))
        epoch.revoke()
        assertFalse(product.allowsClusterAssetAcquisition("signed.asset", exactIdentity))
        product.close()
    }

    @Test
    fun clusterAssetProbeRevokedDuringCoordinatorReadFailsClosedWithoutInlineRetirement() {
        val queued = AtomicReference<Runnable?>()
        lateinit var epoch: UltraProfilePolicyEpoch
        epoch = UltraProfilePolicyEpoch(
            vehicleIdentityAuthority = resolvedAuthority("trusted-vehicle"),
            deploymentProvider = DisabledUltraProfileDeploymentProvider,
            manifestIssuer = UltraProfileCompositionRoot.manifestIssuer(),
            ultraConsentSelected = true,
            uiSyncConsentSelected = true,
            currentSelection = { true },
        )
        val owner = FakeCoordinatorPort(
            catalogAssetId = "signed.asset",
            onCatalogProbe = { epoch.revoke() },
        )
        val product = productSession(epoch, { owner },
            UltraPolicyRetirementDispatcher { task -> queued.set(task); true })
        val exactIdentity = identity(90)
        product.activate(receipt(exactIdentity)) as CarPlayUltraAuthorizationResult.Activated

        assertFalse(product.allowsClusterAssetAcquisition("signed.asset", exactIdentity))
        assertEquals(0, owner.closeCalls)
        requireNotNull(queued.get()).run()
        product.close()
    }

    @Test
    fun revokedEpochDeniesInsideAdmissionGateAndClosesExactOwnerOffGate() {
        val current = AtomicReference(true)
        val gate = Any()
        val closeEntered = CountDownLatch(1)
        val retirementFinished = CountDownLatch(1)
        val exactOwner = FakeCoordinatorPort(onClose = {
            closeEntered.countDown()
            synchronized(gate) { Unit }
        })
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle")) { current.get() }
        val product = productSession(
            epoch,
            { exactOwner },
            UltraPolicyRetirementDispatcher { task ->
                Thread({
                    task.run()
                    retirementFinished.countDown()
                }, "test-ultra-policy-retirement").start()
                true
            },
        )
        val exactReceipt = receipt(identity(54))
        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        synchronized(gate) {
            current.set(false)
            assertFalse(product.isCurrentActivation(activated, exactReceipt))
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
            assertFalse(retirementFinished.await(50, TimeUnit.MILLISECONDS))
        }
        assertTrue(retirementFinished.await(5, TimeUnit.SECONDS))
        assertEquals(1, exactOwner.closeCalls)
        assertFalse(exactOwner.runtime.isLive())
        product.close()
        assertEquals(1, exactOwner.closeCalls)
    }

    @Test
    fun retirementDispatchRejectionStillDeniesAdmissionAndRetainsExactRetryOwner() {
        val current = AtomicReference(true)
        val exactOwner = FakeCoordinatorPort()
        val epoch = testEpoch(resolvedAuthority("trusted-vehicle")) { current.get() }
        val product = productSession(
            epoch,
            { exactOwner },
            UltraPolicyRetirementDispatcher { false },
        )
        val exactReceipt = receipt(identity(55))
        val activated = product.activate(exactReceipt) as CarPlayUltraAuthorizationResult.Activated

        current.set(false)
        assertFalse(product.isCurrentActivation(activated, exactReceipt))
        assertFalse(product.hasRenewableSession(exactReceipt))
        assertEquals(0, exactOwner.closeCalls)
        assertEquals(CarPlayUltraAuthorizationDenialCode.STALE_GENERATION,
            (product.activate(receipt(identity(56))) as CarPlayUltraAuthorizationResult.Denied).code)
        product.close()
        assertEquals(1, exactOwner.closeCalls)
        assertFalse(exactOwner.runtime.isLive())
    }

    @Test
    fun fixedUnavailableCompositionDoesNotConstructCoordinatorOrConsumeReceipt() {
        val identity = identity(1)
        val receipt = receipt(identity)
        val fixedProduct = AuthorizedUltraProductSession()

        assertSame(CarPlayUltraAuthorizationResult.Unavailable, fixedProduct.activate(receipt))

        // A second exact consumer can still claim the receipt, proving Unavailable did not.
        val proof = UltraProfileActivationCoordinator()
        assertTrue(
            proof.beginSession("proof-only-vehicle", receipt) is
                UltraProfileSessionStartResult.Started,
        )
        proof.close()
        fixedProduct.close()

        val factoryCalls = AtomicInteger()
        val product = productSession(
            UltraProfileCompositionRoot.vehicleIdentityAuthority(),
        ) {
            factoryCalls.incrementAndGet()
            FakeCoordinatorPort()
        }

        assertSame(
            CarPlayUltraAuthorizationResult.Unavailable,
            product.activate(receipt(identity(11))),
        )
        assertEquals(0, factoryCalls.get())
        product.close()
    }

    @Test
    fun activationRunsStrictTransactionAndReturnsRuntimeConfigurationFromOneOpen() {
        val calls = mutableListOf<String>()
        val port = FakeCoordinatorPort(calls = calls)
        val product = productSession(
            TrustedUltraVehicleIdentityAuthority { authenticationIdentity ->
                calls += "identity"
                assertEquals(identity(2), authenticationIdentity)
                TrustedUltraVehicleIdentityResult.Resolved("trusted-vehicle")
            },
        ) {
            calls += "factory"
            port
        }
        val sessionManagement = AirPlaySessionManagementInfo(1L, listOf(1L, 2L))

        val activated = product.activate(receipt(identity(2)), sessionManagement)
            as CarPlayUltraAuthorizationResult.Activated

        assertEquals(
            listOf("identity", "factory", "begin", "prepare", "commit", "open"),
            calls,
        )
        assertSame(port.runtime, activated.runtime)
        assertSame(port.configuration, activated.configuration)
        assertSame(sessionManagement, activated.configuration.sessionManagementInfo)
        assertTrue(activated.configuration.isConfigured(AirPlayFeature.SESSION_MANAGEMENT))
        assertTrue(product.isCurrentActivation(activated, port.currentReceipt))
        assertFalse(
            product.isCurrentActivation(
                CarPlayUltraAuthorizationResult.Activated(
                    activated.runtime,
                    activated.configuration,
                ),
                port.currentReceipt,
            ),
        )

        val duplicate = product.activate(receipt(identity(2)))
            as CarPlayUltraAuthorizationResult.Denied
        assertEquals(CarPlayUltraAuthorizationDenialCode.ALREADY_ACTIVE, duplicate.code)
        assertEquals(1, port.openCalls)
        product.close()
    }

    @Test
    fun exactHandoffRenewalKeepsTheSameRuntimeConfigurationAndActivatedIdentity() {
        val fixture = receiptHandoffFixture(identity(3))
        val port = FakeCoordinatorPort()
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val activated = product.activate(fixture.predecessor)
            as CarPlayUltraAuthorizationResult.Activated

        assertTrue(product.hasRenewableSession(fixture.predecessor))
        lateinit var prepared: com.shilapi.xcertplay.orchestration.CarPlayUltraPreparedRenewal
        val renewed = fixture.handoff.commit(fixture.successor) { promoted ->
            assertSame(fixture.successor, promoted)
            prepared = product.prepareRenewal(fixture.predecessor, promoted)
            assertSame(activated, prepared.activation)
        }
        assertSame(activated, prepared.commit())
        assertSame(activated, prepared.commit())
        prepared.close()

        assertSame(fixture.successor, renewed)
        assertFalse(product.hasRenewableSession(fixture.predecessor))
        assertTrue(product.hasRenewableSession(fixture.successor))
        assertSame(port.runtime, activated.runtime)
        assertSame(port.configuration, activated.configuration)
        assertTrue(product.isCurrentActivation(activated, fixture.successor))
        assertEquals(1, port.openCalls)
        assertEquals(1, port.renewCalls)
        product.close()
    }

    @Test
    fun foreignPredecessorDenialThrowsSoHandoffRollsBackWithoutRuntimeRebuild() {
        val fixture = receiptHandoffFixture(identity(4))
        val port = FakeCoordinatorPort()
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val activated = product.activate(fixture.predecessor)
            as CarPlayUltraAuthorizationResult.Activated
        val foreign = receipt(identity(4))

        val failure = assertThrows(IllegalStateException::class.java) {
            fixture.handoff.commit(fixture.successor) { promoted ->
                product.prepareRenewal(foreign, promoted)
            }
        }

        assertTrue(failure.message.orEmpty().contains("STALE_GENERATION"))
        assertTrue(product.hasRenewableSession(fixture.predecessor))
        assertFalse(product.hasRenewableSession(fixture.successor))
        assertSame(port.runtime, activated.runtime)
        assertEquals(1, port.openCalls)
        assertEquals(0, port.renewCalls)
        product.close()
    }

    @Test
    fun deadRuntimeCannotConsumeOrPromoteHandoffSuccessor() {
        val fixture = receiptHandoffFixture(identity(41))
        val port = FakeCoordinatorPort()
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val activated = product.activate(fixture.predecessor)
            as CarPlayUltraAuthorizationResult.Activated
        activated.runtime.close()

        val failure = assertThrows(IllegalStateException::class.java) {
            fixture.handoff.commit(fixture.successor) { promoted ->
                product.prepareRenewal(fixture.predecessor, promoted)
            }
        }

        assertTrue(failure.message.orEmpty().contains("LEASE_NOT_LIVE"))
        assertTrue(fixture.predecessor.isLiveForUltraActivation())
        assertFalse(fixture.successor.isLiveForUltraActivation())
        assertFalse(product.hasRenewableSession(fixture.predecessor))
        assertFalse(product.hasRenewableSession(fixture.successor))
        assertEquals(1, port.openCalls)
        assertEquals(1, port.renewCalls)
        product.close()
    }

    @Test
    fun failedOuterHandoffRollsPreparedTokenBackToTheSameLiveActivation() {
        val fixture = receiptHandoffFixture(identity(42))
        val port = FakeCoordinatorPort()
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val activated = product.activate(fixture.predecessor)
            as CarPlayUltraAuthorizationResult.Activated
        lateinit var prepared: com.shilapi.xcertplay.orchestration.CarPlayUltraPreparedRenewal

        assertThrows(IllegalStateException::class.java) {
            fixture.handoff.commit(fixture.successor) { promoted ->
                prepared = product.prepareRenewal(fixture.predecessor, promoted)
                fixture.handoff.cancel()
            }
        }
        prepared.close()

        assertTrue(fixture.predecessor.isLiveForUltraActivation())
        assertFalse(fixture.successor.isLiveForUltraActivation())
        assertTrue(product.hasRenewableSession(fixture.predecessor))
        assertTrue(product.isCurrentActivation(activated, fixture.predecessor))
        assertSame(activated, prepared.activation)
        assertEquals(1, port.openCalls)
        product.close()
    }

    @Test
    fun lifecycleCloseDuringPreparedRenewalRemainsRetryableUntilTokenCommits() {
        val fixture = receiptHandoffFixture(identity(43))
        val port = FakeCoordinatorPort()
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val activated = product.activate(fixture.predecessor)
            as CarPlayUltraAuthorizationResult.Activated
        lateinit var prepared: com.shilapi.xcertplay.orchestration.CarPlayUltraPreparedRenewal

        fixture.handoff.commit(fixture.successor) { promoted ->
            prepared = product.prepareRenewal(fixture.predecessor, promoted)
        }

        assertThrows(IllegalStateException::class.java) { product.close() }
        assertSame(activated, prepared.commit())
        product.close()
        assertEquals(1, port.closeCalls)
        assertFalse(port.runtime.isLive())
    }

    @Test
    fun preparedRollbackFailureAndFatalErrorRetainTheSameTokenForRetry() {
        val fixture = receiptHandoffFixture(identity(44))
        val port = FakeCoordinatorPort(rollbackFailures = 1, rollbackFatalErrors = 1)
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        product.activate(fixture.predecessor) as CarPlayUltraAuthorizationResult.Activated
        lateinit var prepared: com.shilapi.xcertplay.orchestration.CarPlayUltraPreparedRenewal

        assertThrows(IllegalStateException::class.java) {
            fixture.handoff.commit(fixture.successor) { promoted ->
                prepared = product.prepareRenewal(fixture.predecessor, promoted)
                fixture.handoff.cancel()
            }
        }

        assertThrows(IllegalStateException::class.java) { prepared.close() }
        assertThrows(AssertionError::class.java) { prepared.close() }
        prepared.close()
        prepared.close()
        assertEquals(3, port.rollbackCalls)
        assertTrue(product.hasRenewableSession(fixture.predecessor))
        product.close()
    }

    @Test
    fun closeFailureRetainsExactCoordinatorAndSecondCloseRetriesOnlyThatOwner() {
        val port = FakeCoordinatorPort(closeFailures = 1)
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val receipt = receipt(identity(5))
        product.activate(receipt) as CarPlayUltraAuthorizationResult.Activated

        assertThrows(IllegalStateException::class.java) { product.close() }
        assertEquals(1, port.closeCalls)
        assertFalse(product.hasRenewableSession(receipt))
        assertTrue(port.runtime.isLive())

        product.close()
        assertEquals(2, port.closeCalls)
        assertFalse(port.runtime.isLive())
        product.close()
        assertEquals(2, port.closeCalls)
    }

    @Test
    fun providerUnavailableAfterAdmissionIsDeniedAndRetiredNotReportedAsSafeUnavailable() {
        val port = FakeCoordinatorPort(denyStage = "prepare")
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }

        val result = product.activate(receipt(identity(6)))
            as CarPlayUltraAuthorizationResult.Denied

        assertEquals(CarPlayUltraAuthorizationDenialCode.PROVIDER_UNAVAILABLE, result.code)
        assertEquals(listOf("begin", "prepare", "close"), port.calls)
        product.close()
    }

    @Test
    fun closeCanCancelPublicationWhilePrepareIsBlockedWithoutWaitingOnProductMonitor() {
        val prepareEntered = CountDownLatch(1)
        val allowPrepare = CountDownLatch(1)
        val port = FakeCoordinatorPort(
            prepareEntered = prepareEntered,
            allowPrepare = allowPrepare,
        )
        val product = productSession(resolvedAuthority("trusted-vehicle")) { port }
        val result = AtomicReference<CarPlayUltraAuthorizationResult>()
        val activation = Thread {
            result.set(product.activate(receipt(identity(7))))
        }
        activation.start()
        assertTrue(prepareEntered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { product.close() }
        allowPrepare.countDown()
        activation.join(5_000)

        assertFalse(activation.isAlive)
        assertEquals(
            CarPlayUltraAuthorizationDenialCode.CLOSED,
            (result.get() as CarPlayUltraAuthorizationResult.Denied).code,
        )
        assertEquals(1, port.closeCalls)
        product.close()
        assertEquals(1, port.closeCalls)
    }

    private class SessionHandle : AuthorizedUltraCoordinatorSessionHandle
    private class PreparationHandle : AuthorizedUltraCoordinatorPreparationHandle
    private class LeaseHandle : AuthorizedUltraCoordinatorLeaseHandle
    private class RuntimeHandle : AuthorizedUltraCoordinatorRuntimeHandle
    private class RenewalHandle(
        val predecessor: MfiAuthenticationReceipt,
        val successor: MfiAuthenticationReceipt,
    ) : AuthorizedUltraCoordinatorRenewalHandle {
        var committed = false
        var rolledBack = false
    }

    private class FakeCoordinatorPort(
        val calls: MutableList<String> = mutableListOf(),
        private val denyStage: String? = null,
        private var closeFailures: Int = 0,
        private var rollbackFailures: Int = 0,
        private var rollbackFatalErrors: Int = 0,
        private val prepareEntered: CountDownLatch? = null,
        private val allowPrepare: CountDownLatch? = null,
        private val onClose: (() -> Unit)? = null,
        var policyCoverAllowed: Boolean = true,
        private val onPolicyCover: (() -> Unit)? = null,
        private val policyCoverDecision: (() -> Boolean)? = null,
        private val catalogAssetId: String? = null,
        private val onCatalogProbe: () -> Unit = {},
    ) : AuthorizedUltraProductCoordinatorPort {
        var policyBeginCalls = 0
        var policyCoverCalls = 0

        override fun beginPolicyRevocation() { policyBeginCalls += 1 }

        override fun policyCoverAck(): Boolean {
            policyCoverCalls += 1
            onPolicyCover?.invoke()
            return policyCoverDecision?.invoke() ?: policyCoverAllowed
        }

        private val token = SessionHandle()
        private val preparation = PreparationHandle()
        private val lease = LeaseHandle()
        private val runtimeHandle = RuntimeHandle()
        val runtime = AirPlayUltraRuntime()
        lateinit var configuration: com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
        var openCalls = 0
        var renewCalls = 0
        var rollbackCalls = 0
        var closeCalls = 0
        lateinit var currentReceipt: MfiAuthenticationReceipt

        override fun beginSession(
            vehicleId: String,
            receipt: MfiAuthenticationReceipt,
        ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorSessionHandle> {
            calls += "begin"
            assertEquals("trusted-vehicle", vehicleId)
            if (denyStage == "begin") return denied(CarPlayUltraAuthorizationDenialCode.STALE_RECEIPT)
            if (receipt.consumeForUltraActivation { } == null) {
                return denied(CarPlayUltraAuthorizationDenialCode.STALE_RECEIPT)
            }
            currentReceipt = receipt
            return AuthorizedUltraCoordinatorResult.Granted(token)
        }

        override fun prepare(
            token: AuthorizedUltraCoordinatorSessionHandle,
            receipt: MfiAuthenticationReceipt,
        ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorPreparationHandle> {
            calls += "prepare"
            assertSame(this.token, token)
            prepareEntered?.countDown()
            allowPrepare?.let { check(it.await(5, TimeUnit.SECONDS)) }
            if (denyStage == "prepare") {
                return denied(CarPlayUltraAuthorizationDenialCode.PROVIDER_UNAVAILABLE)
            }
            return AuthorizedUltraCoordinatorResult.Granted(preparation)
        }

        override fun commit(
            preparation: AuthorizedUltraCoordinatorPreparationHandle,
            receipt: MfiAuthenticationReceipt,
        ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorLeaseHandle> {
            calls += "commit"
            assertSame(this.preparation, preparation)
            if (denyStage == "commit") {
                return denied(CarPlayUltraAuthorizationDenialCode.COMMIT_FAILED)
            }
            return AuthorizedUltraCoordinatorResult.Granted(lease)
        }

        override fun openRuntime(
            lease: AuthorizedUltraCoordinatorLeaseHandle,
            sessionManagementInfo: AirPlaySessionManagementInfo?,
        ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorRuntimeBinding> {
            calls += "open"
            openCalls += 1
            assertSame(this.lease, lease)
            if (denyStage == "open") {
                return denied(CarPlayUltraAuthorizationDenialCode.RUNTIME_CONSTRUCTION_FAILED)
            }
            configuration = runtime.configuration(
                configuredFeatures = if (sessionManagementInfo == null) {
                    emptySet()
                } else {
                    setOf(AirPlayFeature.SESSION_MANAGEMENT)
                },
                sessionManagementInfo = sessionManagementInfo,
            )
            if (catalogAssetId != null) {
                configuration = configuration.copy(
                    configuredFeatures = configuration.configuredFeatures + AirPlayFeature.UI_SYNC,
                )
            }
            return AuthorizedUltraCoordinatorResult.Granted(
                AuthorizedUltraCoordinatorRuntimeBinding(
                    runtimeHandle,
                    runtime,
                    configuration,
                ),
            )
        }

        override fun prepareRenewal(
            token: AuthorizedUltraCoordinatorSessionHandle,
            runtime: AuthorizedUltraCoordinatorRuntimeHandle,
            predecessor: MfiAuthenticationReceipt,
            successor: MfiAuthenticationReceipt,
        ): AuthorizedUltraCoordinatorResult<AuthorizedUltraCoordinatorRenewalHandle> {
            calls += "renew"
            renewCalls += 1
            assertSame(this.token, token)
            assertSame(runtimeHandle, runtime)
            if (!this.runtime.isLive()) {
                return denied(CarPlayUltraAuthorizationDenialCode.LEASE_NOT_LIVE)
            }
            return if (successor.consumeForUltraHandoffRenewal(predecessor) { } == null) {
                denied(CarPlayUltraAuthorizationDenialCode.STALE_RECEIPT)
            } else if (this.runtime.isLive()) {
                AuthorizedUltraCoordinatorResult.Granted(
                    RenewalHandle(predecessor, successor),
                )
            } else {
                denied(CarPlayUltraAuthorizationDenialCode.LEASE_NOT_LIVE)
            }
        }

        override fun commitRenewal(
            renewal: AuthorizedUltraCoordinatorRenewalHandle,
        ): CarPlayUltraAuthorizationDenialCode? {
            val exact = renewal as RenewalHandle
            if (exact.rolledBack) return CarPlayUltraAuthorizationDenialCode.STALE_GENERATION
            exact.committed = true
            currentReceipt = exact.successor
            return null
        }

        override fun rollbackRenewal(
            renewal: AuthorizedUltraCoordinatorRenewalHandle,
        ): CarPlayUltraAuthorizationDenialCode? {
            rollbackCalls += 1
            if (rollbackFailures > 0) {
                rollbackFailures -= 1
                throw IllegalStateException("synthetic rollback failure")
            }
            if (rollbackFatalErrors > 0) {
                rollbackFatalErrors -= 1
                throw AssertionError("synthetic fatal rollback failure")
            }
            val exact = renewal as RenewalHandle
            if (!exact.committed) exact.rolledBack = true
            return null
        }

        override fun isRuntimeLive(handle: AuthorizedUltraCoordinatorRuntimeHandle): Boolean =
            handle === runtimeHandle && runtime.isLive()

        override fun allowsClusterAssetAcquisition(
            handle: AuthorizedUltraCoordinatorRuntimeHandle,
            assetId: String,
            authenticationIdentity: MfiAuthenticationIdentity,
        ): Boolean {
            onCatalogProbe()
            return handle === runtimeHandle && runtime.isLive() &&
                assetId == catalogAssetId &&
                currentReceipt.authenticationIdentity == authenticationIdentity
        }

        override fun close() {
            calls += "close"
            closeCalls += 1
            onClose?.invoke()
            if (closeFailures > 0) {
                closeFailures -= 1
                throw IllegalStateException("synthetic close failure")
            }
            runtime.close()
        }

        private fun denied(
            code: CarPlayUltraAuthorizationDenialCode,
        ): AuthorizedUltraCoordinatorResult.Denied = AuthorizedUltraCoordinatorResult.Denied(code)
    }

    private data class ReceiptHandoffFixture(
        val predecessor: MfiAuthenticationReceipt,
        val handoff: MfiAuthenticationHandoff,
        val successor: MfiAuthenticationReceipt,
    )

    private fun productSession(
        authority: TrustedUltraVehicleIdentityAuthority,
        factory: () -> AuthorizedUltraProductCoordinatorPort,
    ): AuthorizedUltraProductSession {
        val epoch = UltraProfilePolicyEpoch(
            vehicleIdentityAuthority = authority,
            deploymentProvider = DisabledUltraProfileDeploymentProvider,
            manifestIssuer = UltraProfileCompositionRoot.manifestIssuer(),
            ultraConsentSelected = true,
            uiSyncConsentSelected = false,
            currentSelection = { true },
        )
        return productSession(epoch, { factory() })
    }

    private fun testEpoch(
        authority: TrustedUltraVehicleIdentityAuthority,
        current: (UltraProfilePolicyEpoch) -> Boolean = { true },
    ): UltraProfilePolicyEpoch {
        lateinit var epoch: UltraProfilePolicyEpoch
        epoch = UltraProfilePolicyEpoch(
            vehicleIdentityAuthority = authority,
            deploymentProvider = DisabledUltraProfileDeploymentProvider,
            manifestIssuer = UltraProfileCompositionRoot.manifestIssuer(),
            ultraConsentSelected = true,
            uiSyncConsentSelected = false,
            currentSelection = { current(epoch) },
        )
        return epoch
    }

    private fun productSession(
        epoch: UltraProfilePolicyEpoch,
        factory: (UltraProfilePolicyEpoch) -> AuthorizedUltraProductCoordinatorPort,
        dispatcher: UltraPolicyRetirementDispatcher = UltraPolicyRetirementDispatcher { task ->
            task.run()
            true
        },
    ): AuthorizedUltraProductSession {
        val constructor = AuthorizedUltraProductSession::class.java.declaredConstructors
            .single { it.parameterCount == 3 }
            .apply { isAccessible = true }
        return constructor.newInstance(epoch, factory, dispatcher) as AuthorizedUltraProductSession
    }

    private fun resolvedAuthority(vehicleId: String) =
        TrustedUltraVehicleIdentityAuthority {
            TrustedUltraVehicleIdentityResult.Resolved(vehicleId)
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

    private fun receipt(identity: MfiAuthenticationIdentity): MfiAuthenticationReceipt {
        val authorityClass = Class.forName(
            "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
        )
        val authority = authorityClass.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }
            .newInstance(identity)
        val begin = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("begin") && !method.name.startsWith("beginHandoff") &&
                method.parameterCount == 2
        }.apply { isAccessible = true }
        val channel = Any()
        val attempt = requireNotNull(begin.invoke(authority, channel, 1L))
        return issue(attempt, channel, identity)
    }

    private fun receiptHandoffFixture(
        identity: MfiAuthenticationIdentity,
    ): ReceiptHandoffFixture {
        val authorityClass = Class.forName(
            "com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority",
        )
        val authority = authorityClass.declaredConstructors.single { it.parameterCount == 1 }
            .apply { isAccessible = true }
            .newInstance(identity)
        val begin = authorityClass.declaredMethods.single { method ->
            method.name.startsWith("begin") && !method.name.startsWith("beginHandoff") &&
                method.parameterCount == 2
        }.apply { isAccessible = true }
        val firstChannel = Any()
        val firstAttempt = requireNotNull(begin.invoke(authority, firstChannel, 1L))
        val predecessor = issue(firstAttempt, firstChannel, identity)
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
        val attemptForExchange = handoff.javaClass.declaredMethods.single { method ->
            method.name.startsWith("attemptForExchange") && method.parameterCount == 0
        }.apply { isAccessible = true }
        val successor = issue(
            requireNotNull(attemptForExchange.invoke(handoff)),
            secondChannel,
            identity,
        )
        return ReceiptHandoffFixture(predecessor, handoff, successor)
    }

    private fun issue(
        attempt: Any,
        channel: Any,
        identity: MfiAuthenticationIdentity,
    ): MfiAuthenticationReceipt {
        val issue = attempt.javaClass.declaredMethods.single { method ->
            method.name.startsWith("issue") && method.parameterCount == 2
        }.apply { isAccessible = true }
        return issue.invoke(attempt, channel, identity) as MfiAuthenticationReceipt
    }
}
