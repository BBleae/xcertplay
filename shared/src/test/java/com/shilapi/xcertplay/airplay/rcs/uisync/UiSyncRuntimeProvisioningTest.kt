package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayReadiness
import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntimeConstructionException
import com.shilapi.xcertplay.airplay.AirPlayUltraSessionOpenCleanupException
import com.shilapi.xcertplay.airplay.AirPlayUltraSessionRuntime
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.vehicle.ultra.UltraAirPlayStreamIdentity
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBinding
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingRegistry
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayBindingResources
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationObserver
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.io.Closeable
import java.net.Socket
import java.net.InetAddress
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSyncRuntimeProvisioningTest {
    @Test
    fun physicalRendererWithoutPhoneEpochObserverIsRejectedBeforeBatchBind() {
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(
            RecordingCapability(omitPhoneEpochObserverForRuntime = true), authority,
        ).transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertTrue(authority.boundBatches.isEmpty())
        owner.close()
    }

    @Test
    fun terminalChannelStopSealsSessionAndCoversExactBatchOffCallback() {
        val authority = TestPhysicalAuthority(captureActivations = true)
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val session = owner.openSession()
        assertEquals(1, authority.boundBatches.size)
        assertTrue(session.beginTerminalUiSyncChannelStop())
        assertTrue(authority.stopPermitsRevoked.get())
        assertFalse(session.isCarrierLive())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (authority.coverCalls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L)
        }
        assertTrue(authority.coverCalls.get() >= 1)
        session.close()
        owner.close()
    }

    @Test
    fun physicalClusterResetCoversOnlyClusterAndPreservesMainAndBatch() {
        val mainId = AirPlayInfoPlist.MAIN_UUID
        val clusterId = AirPlayInfoPlist.ALT_UUID
        val main = output("main", mainId, 1)
        val cluster = output("external-cluster", clusterId, 2)
        val plan = staticPlan(listOf(main, cluster), emptyList())
        val requirement = UiSyncPhysicalOutputRequirement(
            UltraProfileDigest(ByteArray(32) { 0x5a }),
            listOf(
                UiSyncPhysicalOutputAssignment(main,
                    UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
                UiSyncPhysicalOutputAssignment(cluster,
                    UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER)),
            ),
        )
        val authority = TestPhysicalAuthority(captureActivations = true)
        val owner = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "v2")),
            displays = listOf(displaySpec(mainId), displaySpec(clusterId)),
            staticPlan = plan,
            rendererCapability = RecordingCapability(),
            callbackExecutor = BoundedTestPresentationExecutor(parallelism = 2),
            authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                PROGRAM_FINGERPRINT, "test.renderer.v2", requirement,
            ),
            physicalOutputAuthority = authority,
        ).transferToRuntime()
        val session = owner.openSession()
        val batch = authority.boundBatches.single()
        assertEquals(2, batch.routes.size)
        assertTrue(session.invalidatePhoneEpoch(clusterId))
        assertTrue(authority.clusterPhase1Revoked.get())
        assertFalse(authority.stopPermitsRevoked.get())
        assertTrue(session.isCarrierLive())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (authority.clusterCoverAcks.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L)
        }
        assertEquals(1, authority.clusterCoverAcks.get())
        assertEquals(listOf(clusterId), authority.clusterCoverDisplayIds.toList())
        assertTrue(batch.isCurrent)
        assertTrue(session.isCarrierLive())
        assertEquals(0, authority.backendCloses.get())
        // A CLUSTER-only RESET never requests the batch-wide (MAIN+CLUSTER) Host cover.
        assertEquals(0, authority.coverCalls.get())
        assertTrue(authority.coveredRoles.isEmpty())
        session.close()
        owner.close()
    }

    @Test
    fun physicalClusterResetRetriesExactCoverWithoutStoppingMain() {
        val authority = TestPhysicalAuthority(captureActivations = true,
            clusterCoverFailures = 1)
        val owner = mainClusterOwner(authority)
        val session = owner.openSession()
        val batch = authority.boundBatches.single()
        assertTrue(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (authority.clusterCoverAcks.get() == 0 && System.nanoTime() < deadline)
            Thread.sleep(5L)
        assertEquals(2, authority.clusterCoverCalls.get())
        assertEquals(1, authority.clusterCoverAcks.get())
        assertFalse(authority.stopPermitsRevoked.get())
        assertTrue(session.isCarrierLive())
        assertTrue(batch.isCurrent)
        session.close()
        owner.close()
    }

    @Test
    fun clusterPhysicalRevocationPrecedesRendererObserver() {
        val authority = TestPhysicalAuthority(captureActivations = true)
        val capability = RecordingCapability(onPhoneEpochInvalidation = { displayId ->
            assertEquals(AirPlayInfoPlist.ALT_UUID, displayId)
            assertTrue(authority.clusterPhase1Revoked.get())
            assertEquals(0, authority.clusterCoverCalls.get())
        })
        val owner = mainClusterOwner(authority, capability)
        val session = owner.openSession()
        assertTrue(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
        assertFalse(authority.stopPermitsRevoked.get())
        session.close()
        owner.close()
    }

    @Test
    fun failedClusterObserverTerminatesAfterPhysicalPhaseOne() {
        val authority = TestPhysicalAuthority(captureActivations = true)
        val capability = RecordingCapability(onPhoneEpochInvalidation = {
            assertTrue(authority.clusterPhase1Revoked.get())
            throw IllegalStateException("synthetic observer failure")
        })
        val owner = mainClusterOwner(authority, capability)
        val session = owner.openSession()
        assertFalse(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
        assertTrue(authority.stopPermitsRevoked.get())
        assertFalse(session.isCarrierLive())
        session.close()
        owner.close()
    }

    @Test
    fun exhaustedClusterHostCoverRetriesFailClosedAndStopChannel() {
        val authority = TestPhysicalAuthority(captureActivations = true,
            clusterCoverFailures = 3)
        val owner = mainClusterOwner(authority)
        val session = owner.openSession()
        assertTrue(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        while (!authority.stopPermitsRevoked.get() && System.nanoTime() < deadline)
            Thread.sleep(5L)
        assertEquals(3, authority.clusterCoverCalls.get())
        assertEquals(0, authority.clusterCoverAcks.get())
        assertTrue(authority.stopPermitsRevoked.get())
        assertFalse(session.isCarrierLive())
        session.close()
        owner.close()
    }

    @Test
    fun newerClusterResetSupersedesBlockedOldCoverWithoutStoppingMain() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authority = TestPhysicalAuthority(captureActivations = true,
            blockFirstClusterCover = entered to release)
        val owner = mainClusterOwner(authority)
        val session = owner.openSession()
        try {
            assertTrue(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
            assertTrue(entered.await(3L, TimeUnit.SECONDS))
            assertTrue(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
            while (authority.clusterCoverAcks.get() == 0 && System.nanoTime() < deadline)
                Thread.sleep(5L)
            assertEquals(2, authority.clusterCoverCalls.get())
            assertEquals(1, authority.clusterCoverAcks.get())
            assertFalse(authority.stopPermitsRevoked.get())
            assertTrue(session.isCarrierLive())
        } finally {
            release.countDown()
            session.close()
            owner.close()
        }
    }

    @Test
    fun missingClusterPhaseOneFallsBackToTerminalStopBeforeResetReply() {
        val authority = TestPhysicalAuthority(captureActivations = true,
            clusterBeginFails = true)
        val owner = mainClusterOwner(authority)
        val session = owner.openSession()
        assertFalse(session.invalidatePhoneEpoch(AirPlayInfoPlist.ALT_UUID))
        assertTrue(authority.stopPermitsRevoked.get())
        assertFalse(session.isCarrierLive())
        session.close()
        owner.close()
    }

    @Test
    fun physicalMainOnlyResetKeepsExactChannelRecoverable() {
        val mainId = AirPlayInfoPlist.MAIN_UUID
        val main = output("main-only", mainId, 1)
        val plan = staticPlan(listOf(main), emptyList())
        val authority = TestPhysicalAuthority(captureActivations = true)
        val owner = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "v2")),
            displays = listOf(displaySpec(mainId)),
            staticPlan = plan,
            rendererCapability = RecordingCapability(),
            callbackExecutor = BoundedTestPresentationExecutor(),
            authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                PROGRAM_FINGERPRINT, "test.renderer.v2",
                UiSyncPhysicalOutputRequirement(
                    UltraProfileDigest(ByteArray(32) { 0x5a }),
                    listOf(UiSyncPhysicalOutputAssignment(main,
                        UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN))),
                ),
            ),
            physicalOutputAuthority = authority,
        ).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.invalidatePhoneEpoch(mainId))
        assertFalse(authority.stopPermitsRevoked.get())
        assertTrue(session.isCarrierLive())
        session.close()
        owner.close()
    }

    @Test
    fun terminalChannelStopDoesNotSealNonphysicalDiagnosticSession() {
        val binding = legacyBinding()
        val registry = UltraDisplayBindingRegistry().apply {
            replace(legacyResources(binding), listOf(binding))
        }
        val owner = UiSyncRuntimeProvisioning.from(
            AirPlayUiSyncInfo(), listOf(displaySpec()), registry, Runnable::run,
        ).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isCarrierLive())
        assertTrue(session.beginTerminalUiSyncChannelStop())
        assertTrue(session.isCarrierLive())
        session.close()
        owner.close()
    }

    @Test
    fun readOnlyPresentationBindingIsRealSessionOnlyAndCannotSubstituteRendererSubscription() {
        val bindingCalls = AtomicInteger()
        val provisioning = readyProvisioning(
            capability = RecordingCapability(actualSubscriptionDisplayIds = emptySet()),
            readOnlyPresentationBinding = { source ->
                bindingCalls.incrementAndGet()
                source.subscribeWithReplay(DISPLAY_ID, UltraPresentationObserver { })
            },
        )
        assertEquals(0, bindingCalls.get()) // The static factory probe cannot bind a browser.
        val owner = provisioning.transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertEquals(0, bindingCalls.get()) // An uncounted browser cannot repair renderer readiness.
        owner.close()
    }

    @Test
    fun readOnlyBindingClosesBeforeHubAndFailedDetachIsRetriedByExactSession() {
        val bindingCalls = AtomicInteger()
        val detachCalls = AtomicInteger()
        val capturedSource = AtomicReference<UiSyncPresentationSource>()
        val provisioning = readyProvisioning(
            capability = RecordingCapability(),
            readOnlyPresentationBinding = { source ->
                bindingCalls.incrementAndGet()
                capturedSource.set(source)
                val subscription = source.subscribeWithReplay(
                    DISPLAY_ID, UltraPresentationObserver { },
                )
                Closeable {
                    assertFalse(source.snapshot(DISPLAY_ID).closed)
                    if (detachCalls.incrementAndGet() == 1) {
                        throw IllegalStateException("temporary WebDisplay observer detach failure")
                    }
                    subscription.close()
                }
            },
        )
        assertEquals(0, bindingCalls.get())
        val owner = provisioning.transferToRuntime()
        val session = owner.openSession()
        assertEquals(1, bindingCalls.get())
        assertTrue(session.isLive())
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertFalse(requireNotNull(capturedSource.get()).snapshot(DISPLAY_ID).closed)
        session.close()
        assertEquals(2, detachCalls.get())
        assertTrue(requireNotNull(capturedSource.get()).snapshot(DISPLAY_ID).closed)
        owner.close()
    }

    @Test
    fun readOnlyDiagnosticBindingReceivesOnlyTheExactCurrentRuntimeChildAndBatch() {
        val authority = TestPhysicalAuthority(captureActivations = true)
        val capability = RecordingCapability()
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val attached = AtomicReference<UiSyncReadOnlySessionContext>()
        val provisioning = readyProvisioning(
            capability = capability,
            physicalAuthority = authority,
            readOnlySessionBinding = { context ->
                calls.incrementAndGet()
                attached.set(context)
                Closeable {
                    assertFalse(context.presentationSource.snapshot(DISPLAY_ID).closed)
                    closes.incrementAndGet()
                }
            },
        )
        assertEquals(0, calls.get()) // Factory construction probe is never diagnostic.
        val owner = provisioning.transferToRuntime()
        val session = owner.openSession()
        val context = requireNotNull(attached.get())
        assertEquals(1, calls.get())
        assertSame(session, context.sessionIdentity)
        assertSame(capability.sessions.last(), context.rendererChild)
        assertSame(authority.boundBatches.single(), context.physicalBatch)
        assertEquals(UiSyncReadOnlySessionStatus.CURRENT, context.status)
        authority.busyBackend()
        assertEquals(UiSyncReadOnlySessionStatus.BUSY, context.status)
        authority.resumeBackend()
        assertEquals(UiSyncReadOnlySessionStatus.CURRENT, context.status)
        authority.revokeHostTargets()
        assertEquals(UiSyncReadOnlySessionStatus.REVOKED, context.status)
        session.close()
        assertEquals(1, closes.get())
        assertEquals(UiSyncReadOnlySessionStatus.REVOKED, context.status)
        owner.close()
    }

    @Test
    fun readOnlyDiagnosticCannotRepairMissingRendererSubscriptionOrStaleBatch() {
        val missingCalls = AtomicInteger()
        val missing = readyProvisioning(
            capability = RecordingCapability(actualSubscriptionDisplayIds = emptySet()),
            readOnlySessionBinding = {
                missingCalls.incrementAndGet()
                null
            },
        ).transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { missing.openSession() }
        assertEquals(0, missingCalls.get())
        missing.close()

        val staleCalls = AtomicInteger()
        val stale = readyProvisioning(
            capability = RecordingCapability(),
            physicalAuthority = TestPhysicalAuthority(TestPhysicalAuthority.Mode.STALE_BATCH),
            readOnlySessionBinding = {
                staleCalls.incrementAndGet()
                null
            },
        ).transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { stale.openSession() }
        assertEquals(0, staleCalls.get())
        stale.close()
    }

    @Test
    fun failingReadOnlyDiagnosticDoesNotBlockTheAuthorizedSession() {
        val calls = AtomicInteger()
        val owner = readyProvisioning(
            capability = RecordingCapability(),
            readOnlySessionBinding = {
                calls.incrementAndGet()
                throw IllegalStateException("diagnostic unavailable")
            },
        ).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isLive())
        assertEquals(1, calls.get())
        session.close()
        owner.close()
    }

    @Test
    fun physicalAuthorityIdentitySurvivesRuntimeTransferButNotRetirement() {
        val authority = TestPhysicalAuthority()
        val foreign = TestPhysicalAuthority()
        val provisioning = readyProvisioning(RecordingCapability(), authority)

        assertTrue(provisioning.usesPhysicalOutputAuthorityExactly(authority))
        assertFalse(provisioning.usesPhysicalOutputAuthorityExactly(foreign))

        val owner = provisioning.transferToRuntime()
        assertTrue(provisioning.usesPhysicalOutputAuthorityExactly(authority))
        owner.close()
        assertFalse(provisioning.usesPhysicalOutputAuthorityExactly(authority))
    }

    @Test
    fun readyPathUsesV2StaticPlanAndOpensFreshRendererForEverySession() {
        val capability = RecordingCapability()
        val provisioning = readyProvisioning(capability)

        assertEquals(1, capability.acquireCount)
        assertEquals(1, capability.sessions.size)
        assertTrue(capability.sessions.single().closed)
        assertEquals(2, provisioning.staticPlan?.outputs?.size)
        assertEquals(1, provisioning.staticPlan?.ingressSpecs?.size)
        assertNull(provisioning.bindingSnapshot)

        val runtime = AirPlayUltraRuntime(uiSyncProvisioning = provisioning)
        val configuration = runtime.configuration(setOf(AirPlayFeature.UI_SYNC))
        assertTrue(configuration.readiness.uiSync?.renderer?.isComplete == true)
        assertEquals(
            AirPlayReadiness.READY,
            configuration.readiness.uiSync?.renderer?.callbackDispatcher,
        )
        assertTrue(
            AirPlayFeatureNegotiation.snapshot(config(configuration))
                .isReady(AirPlayFeature.UI_SYNC),
        )

        val first = runtime.openSession()
        val second = runtime.openSession()
        assertEquals(3, capability.sessions.size)
        assertTrue(
            first.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
                ?.probe()?.isReady == true,
        )
        assertNotSame(
            capability.sessions[1].context.presentationSource,
            capability.sessions[2].context.presentationSource,
        )
        assertNotSame(
            first.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
            second.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
        )

        first.close()
        second.close()
        runtime.close()
        assertEquals(1, capability.factoryCloseCalls.get())
    }

    @Test
    fun twoOpaqueDisplaysRequireExactCompleteReceiptAndNegotiateReady() {
        val displayIds = setOf("opaque.cluster.primary", "opaque.instrument.secondary")
        val capability = RecordingCapability()
        val plan = staticPlan(
            outputs = listOf(
                output("cluster", "opaque.cluster.primary", 11),
                output("instrument", "opaque.instrument.secondary", 12),
                output("local-only", null, 13),
            ),
            ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
        )
        val provisioning = authorizedReadyProvisioning(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "multi-display-v2")),
            displays = displayIds.map(::displaySpec),
            staticPlan = plan,
            rendererCapability = capability,
            callbackExecutor = BoundedTestPresentationExecutor(parallelism = 2),
        )
        val runtime = AirPlayUltraRuntime(uiSyncProvisioning = provisioning)
        val ultra = runtime.configuration(setOf(AirPlayFeature.UI_SYNC))

        assertEquals(displayIds, ultra.uiSyncInfo?.declaredDisplayIds)
        assertTrue(AirPlayFeatureNegotiation.snapshot(config(ultra)).isReady(AirPlayFeature.UI_SYNC))
        assertEquals(displayIds, capability.sessions.single().readinessReceipt?.displayIds)
        assertEquals(
            emptySet<String>(),
            capability.sessions.single().readinessReceipt?.subscribedDisplayIds,
        )

        runtime.close()
    }

    @Test
    fun twoSessionPresentationHubsWithSameDisplayNeverMixEvents() {
        val owner = readyProvisioning(RecordingCapability()).transferToRuntime()
        val first = owner.openSession()
        val second = owner.openSession()
        val firstRouter = first.endpointRegistry.openRouter()
        val secondRouter = second.endpointRegistry.openRouter()
        try {
            readyRouter(firstRouter)
            readyRouter(secondRouter)
            val beforeSecond = second.snapshot(DISPLAY_ID)

            publishRequestLayout(firstRouter, "first-only")

            assertEquals(1L, first.snapshot(DISPLAY_ID).revision)
            assertEquals("first-only", eventLayoutId(first.snapshot(DISPLAY_ID)))
            assertEquals(beforeSecond.revision, second.snapshot(DISPLAY_ID).revision)
            assertTrue(second.snapshot(DISPLAY_ID).events.isEmpty())
        } finally {
            firstRouter.close()
            secondRouter.close()
            PEERS.remove(firstRouter)
            PEERS.remove(secondRouter)
            first.close()
            second.close()
            owner.close()
        }
    }

    @Test
    fun sameWireIdentityInTwoSessionsCreatesDistinctExactHandleCandidates() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        val first = owner.openSession()
        val second = owner.openSession()
        val identity = AirPlayStreamIdentity(111, STREAM_CONNECTION_ID, null)
        val firstHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
        val secondHandle = AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())

        val firstCandidate = requireNotNull(first.prepareScreen(firstHandle))
        val secondCandidate = requireNotNull(second.prepareScreen(secondHandle))

        assertNotSame(firstHandle, secondHandle)
        assertNotSame(firstCandidate, secondCandidate)
        assertSame(firstHandle, capability.sessions[1].candidates.single().stream)
        assertSame(secondHandle, capability.sessions[2].candidates.single().stream)
        firstCandidate.close()
        secondCandidate.close()
        first.close()
        second.close()
        owner.close()
    }

    @Test
    fun preparedScreenIsPrivateUntilCommitAndRollbackRetiresWithoutPublishing() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        val session = owner.openSession()
        val renderer = capability.sessions.last()
        val first = requireNotNull(session.prepareScreen(exactHandle()))

        assertFalse(first.isPublished)
        assertEquals(0, renderer.candidates.single().publishCalls.get())
        first.close()
        assertEquals(0, renderer.candidates.single().publishCalls.get())
        assertEquals(1, renderer.candidates.single().closeCalls.get())

        val second = requireNotNull(session.prepareScreen(exactHandle()))
        assertTrue(second.commit())
        assertTrue(second.isPublished)
        assertEquals(1, renderer.candidates.last().publishCalls.get())
        second.close()
        session.close()
        owner.close()
    }

    @Test
    fun exactSelectorDoesNotFallBackToTypeOrOpaqueText() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        val session = owner.openSession()
        val renderer = capability.sessions.last()

        assertNull(
            session.prepareScreen(
                AirPlayVideoStreamHandle(
                    AirPlayStreamIdentity(111, "wrong-connection", null),
                    AirPlayVideoSessionEpoch(),
                ),
            ),
        )
        assertNull(
            session.prepareScreen(
                AirPlayVideoStreamHandle(
                    AirPlayStreamIdentity(110, STREAM_CONNECTION_ID, null),
                    AirPlayVideoSessionEpoch(),
                ),
            ),
        )
        assertEquals(0, renderer.prepareCalls.get())
        session.close()
        owner.close()
    }

    @Test
    fun duplicateExactHandleIsReservedBeforeAnySecondRendererCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val capability = RecordingCapability(blockPrepare = entered to release)
        val owner = readyProvisioning(capability).transferToRuntime()
        val session = owner.openSession()
        val handle = exactHandle()
        val firstResult = AtomicReference<UiSyncPreparedScreenBinding?>()
        val firstFailure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                firstResult.set(session.prepareScreen(handle))
            } catch (failure: Throwable) {
                firstFailure.set(failure)
            }
        }
        thread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { session.prepareScreen(handle) }
        assertEquals(1, capability.sessions.last().prepareCalls.get())

        release.countDown()
        thread.join(5_000)
        assertNull(firstFailure.get())
        requireNotNull(firstResult.get()).close()
        session.close()
        owner.close()
    }

    @Test
    fun uncertainCandidateRetirementBlocksSameIngressUntilSessionCloseRetriesIt() {
        val capability = RecordingCapability(failCandidateCloseOnce = true)
        val owner = readyProvisioning(capability).transferToRuntime()
        val session = owner.openSession()
        val first = requireNotNull(session.prepareScreen(exactHandle()))

        assertThrows(IllegalStateException::class.java) { first.close() }
        assertThrows(IllegalStateException::class.java) {
            session.prepareScreen(exactHandle())
        }
        assertEquals(1, capability.sessions.last().prepareCalls.get())

        session.close()
        assertEquals(1, capability.sessions.last().retireScreenCalls.get())
        assertTrue(capability.sessions.last().closed)
        owner.close()
    }

    @Test
    fun closeRacingPreparedCandidateRetainsRejectedCandidateForRetry() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val capability = RecordingCapability(
            blockPrepare = entered to release,
            failCandidateCloseOnce = true,
        )
        val owner = readyProvisioning(capability).transferToRuntime()
        val session = owner.openSession()
        val prepareFailure = AtomicReference<Throwable?>()
        val preparing = Thread {
            try {
                session.prepareScreen(exactHandle())
            } catch (failure: Throwable) {
                prepareFailure.set(failure)
            }
        }
        preparing.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { session.close() }
        release.countDown()
        preparing.join(5_000)
        assertTrue(prepareFailure.get() is IllegalStateException)
        val candidate = capability.sessions.last().candidates.single()
        assertEquals(1, candidate.closeCalls.get())

        session.close()
        assertEquals(1, candidate.closeCalls.get())
        assertEquals(1, capability.sessions.last().retireScreenCalls.get())
        assertTrue(capability.sessions.last().closed)
        owner.close()
    }

    @Test
    fun wrongCandidateReceiptsWithFailedCleanupRemainOwnedAndBlockReplacement() {
        listOf(CandidateReceiptOverride.STREAM, CandidateReceiptOverride.INGRESS).forEach { wrong ->
            val capability = RecordingCapability(
                failCandidateCloseOnce = true,
                candidateReceiptOverride = wrong,
            )
            val owner = readyProvisioning(capability).transferToRuntime()
            val session = owner.openSession()

            assertThrows(IllegalArgumentException::class.java) {
                session.prepareScreen(exactHandle())
            }
            val candidate = capability.sessions.last().candidates.single()
            assertEquals(1, candidate.closeCalls.get())
            assertThrows(IllegalStateException::class.java) {
                session.prepareScreen(exactHandle())
            }
            assertEquals(1, capability.sessions.last().prepareCalls.get())

            session.close()
            assertEquals(1, candidate.closeCalls.get())
            assertEquals(1, capability.sessions.last().retireScreenCalls.get())
            owner.close()
        }
    }

    @Test
    fun legacyOneToOneRendererOverloadCannotMintCompleteReadiness() {
        val capability = RecordingCapability()
        val binding = legacyBinding()
        val registry = UltraDisplayBindingRegistry().apply {
            replace(legacyResources(binding), listOf(binding))
        }
        val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            AirPlayUiSyncInfo(),
            listOf(displaySpec()),
            registry,
            capability,
            BoundedTestPresentationExecutor(),
        )
        val runtime = AirPlayUltraRuntime(uiSyncProvisioning = provisioning)
        val firstConfiguration = runtime.configuration(setOf(AirPlayFeature.UI_SYNC))
        val renderer = requireNotNull(firstConfiguration.readiness.uiSync).renderer

        assertFalse(renderer.isComplete)
        assertEquals(AirPlayReadiness.DIAGNOSTIC_ONLY, renderer.outputBindings)
        assertEquals(0, capability.acquireCount)
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(firstConfiguration))
                .isReady(AirPlayFeature.UI_SYNC),
        )
        runtime.close()
    }

    @Test
    fun v2ResourceCapabilityCannotMintCompleteReadinessOrAcquireResources() {
        var acquireCalls = 0
        val resourceOnly = UiSyncRendererCapability {
            acquireCalls += 1
            throw AssertionError("resource-only capability must not be acquired by READY path")
        }
        val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            AirPlayUiSyncInfo(),
            listOf(displaySpec()),
            staticPlan(
                outputs = listOf(output("cluster", DISPLAY_ID, 1)),
                ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
            ),
            resourceOnly,
            BoundedTestPresentationExecutor(),
        )
        val runtime = AirPlayUltraRuntime(uiSyncProvisioning = provisioning)

        assertEquals(0, acquireCalls)
        assertFalse(
            requireNotNull(
                runtime.configuration(setOf(AirPlayFeature.UI_SYNC)).readiness.uiSync,
            ).renderer.isComplete,
        )
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(
                config(runtime.configuration(setOf(AirPlayFeature.UI_SYNC))),
            ).isReady(AirPlayFeature.UI_SYNC),
        )
        runtime.close()
    }

    @Test
    fun typeOnlyIngressSelectorIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRendererIngressSpec(
                UltraIngressId("type-only"),
                UltraAirPlayStreamIdentity(type = 111),
            )
        }
    }

    @Test
    fun uuidBoundIngressLearnsDynamicConnectionIdWithoutCrossDisplayOrDuplicateUse() {
        val spec = UiSyncRendererIngressSpec(
            UltraIngressId("cluster-dynamic"),
            UltraAirPlayStreamIdentity(type = 111),
            AirPlayInfoPlist.ALT_UUID,
        )
        val plan = staticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = listOf(spec),
        )
        val provisioning = authorizedReadyProvisioning(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "uuid-dynamic")),
            displays = listOf(displaySpec()),
            staticPlan = plan,
            rendererCapability = RecordingCapability(),
            callbackExecutor = BoundedTestPresentationExecutor(),
        )
        val owner = provisioning.transferToRuntime()
        val session = owner.openSession()
        fun handle(type: Int, connection: String, uuid: String?) =
            AirPlayVideoStreamHandle(
                AirPlayStreamIdentity(type, connection, null),
                AirPlayVideoSessionEpoch(),
                uuid,
            )
        try {
            assertNull(session.prepareScreen(handle(111, "phone-chosen-1", null)))
            assertNull(session.prepareScreen(handle(110, "phone-chosen-1", AirPlayInfoPlist.MAIN_UUID)))
            val first = requireNotNull(session.prepareScreen(
                handle(111, "phone-chosen-1", AirPlayInfoPlist.ALT_UUID),
            ))
            assertThrows(IllegalStateException::class.java) {
                session.prepareScreen(handle(111, "phone-chosen-2", AirPlayInfoPlist.ALT_UUID))
            }
            first.close()
            val second = requireNotNull(session.prepareScreen(
                handle(111, "phone-chosen-2", AirPlayInfoPlist.ALT_UUID),
            ))
            second.close()
        } finally {
            session.close()
            owner.close()
        }
        assertThrows(IllegalStateException::class.java) {
            session.prepareScreen(handle(111, "stale", AirPlayInfoPlist.ALT_UUID))
        }
    }

    @Test
    fun uuidSelectorsAreTypeBoundAmbiguityCheckedAndIncludedInProvisioningFingerprints() {
        val uuidSpec = UiSyncRendererIngressSpec(
            UltraIngressId("cluster"), UltraAirPlayStreamIdentity(111, "fixed-id"),
            AirPlayInfoPlist.ALT_UUID,
        )
        val exactSpec = UiSyncRendererIngressSpec(
            UltraIngressId("cluster"), UltraAirPlayStreamIdentity(111, "fixed-id"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRendererIngressSpec(
                UltraIngressId("wrong-display"), UltraAirPlayStreamIdentity(111),
                AirPlayInfoPlist.MAIN_UUID,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRendererIngressSpec(
                UltraIngressId("client-type-confusion"),
                UltraAirPlayStreamIdentity(111, clientTypeUuid = "client"),
                AirPlayInfoPlist.ALT_UUID,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            staticPlan(
                outputs = listOf(output("cluster", DISPLAY_ID, 1)),
                ingressSpecs = listOf(uuidSpec, exactSpec.copy(id = UltraIngressId("exact"))),
            )
        }
        val output = listOf(output("cluster", DISPLAY_ID, 1))
        val uuidProvisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            AirPlayUiSyncInfo(mapOf("renderer" to "selector-fingerprint")),
            listOf(displaySpec()), staticPlan(output, listOf(uuidSpec)),
            RecordingCapability(), BoundedTestPresentationExecutor(),
        )
        val exactProvisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            AirPlayUiSyncInfo(mapOf("renderer" to "selector-fingerprint")),
            listOf(displaySpec()), staticPlan(output, listOf(exactSpec)),
            RecordingCapability(), BoundedTestPresentationExecutor(),
        )
        try {
            assertFalse(uuidProvisioning.bindingFingerprint == exactProvisioning.bindingFingerprint)
            assertFalse(uuidProvisioning.schemaFingerprint == exactProvisioning.schemaFingerprint)
        } finally {
            uuidProvisioning.close()
            exactProvisioning.close()
        }
    }

    @Test
    fun mismatchedFactoryAndSessionReceiptsCannotMintReadiness() {
        val wrongFactory = RecordingCapability(
            factoryReceiptOverride = ReceiptOverride.SCHEMA,
        )
        assertThrows(IllegalArgumentException::class.java) { readyProvisioning(wrongFactory) }
        assertEquals(1, wrongFactory.factoryCloseCalls.get())

        val wrongSession = RecordingCapability(
            sessionReceiptOverride = ReceiptOverride.DISPLAYS,
        )
        assertThrows(IllegalArgumentException::class.java) { readyProvisioning(wrongSession) }
        assertEquals(1, wrongSession.factoryCloseCalls.get())
    }

    @Test
    fun factoryAndSessionDisplayReceiptsDoNotReadUntrustedSetSize() {
        readyProvisioning(
            RecordingCapability(factoryReceiptOverride = ReceiptOverride.HOSTILE_DISPLAY_SIZE),
        ).close()
        readyProvisioning(
            RecordingCapability(sessionReceiptOverride = ReceiptOverride.HOSTILE_DISPLAY_SIZE),
        ).close()
    }

    @Test
    fun exactFactoryLeaseCannotBeOwnedByTwoProvisionings() {
        val capability = ReusedFactoryCapability()
        val first = readyProvisioning(capability)

        assertThrows(IllegalArgumentException::class.java) {
            readyProvisioning(capability)
        }
        assertEquals(0, capability.factoryCloseCalls.get())

        first.close()
        assertEquals(1, capability.factoryCloseCalls.get())
    }

    @Test
    fun probeSessionLeaseCannotBeReturnedAgainForARealSession() {
        val shared = SharedCompleteSession()
        val capability = RecordingCapability(sharedSessionForEveryOpen = shared)
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        assertEquals(1, shared.closeCalls.get())

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertEquals(1, shared.closeCalls.get())
        assertTrue(authority.rendererSessions.isEmpty())
        assertFalse(owner.isLive())

        owner.close()
    }

    @Test
    fun twoFactoriesCannotOwnTheSameExactSessionLease() {
        val shared = SharedCompleteSession()
        val firstCapability = RecordingCapability(sharedSessionForEveryOpen = shared)
        val secondCapability = RecordingCapability(sharedSessionForEveryOpen = shared)
        val first = readyProvisioning(firstCapability)
        assertEquals(1, shared.closeCalls.get())

        assertThrows(IllegalArgumentException::class.java) {
            readyProvisioning(secondCapability)
        }
        assertEquals(1, shared.closeCalls.get())
        assertTrue(secondCapability.factoryClosed.get())

        first.close()
    }

    @Test
    fun parallelRealSessionOpensCannotOwnTheSameExactLease() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val shared = SharedCompleteSession(entered to release)
        val capability = RecordingCapability(sharedSessionForRuntimeOpens = shared)
        val owner = readyProvisioning(capability).transferToRuntime()
        val firstFailure = AtomicReference<Throwable?>()
        val opening = Thread {
            try {
                owner.openSession()
            } catch (failure: Throwable) {
                firstFailure.set(failure)
            }
        }
        opening.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertEquals(0, shared.closeCalls.get())
        release.countDown()
        opening.join(5_000)

        assertTrue(
            firstFailure.get() is IllegalStateException ||
                firstFailure.get() is IllegalArgumentException,
        )
        assertEquals(1, shared.closeCalls.get())
        owner.close()
    }

    @Test
    fun closedRealRendererLeaseCannotBeReplayedToPhysicalAuthority() {
        val capability = RecordingCapability(reuseClosedRuntimeSession = true)
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val first = owner.openSession()
        val validated = capability.sessions[1]
        assertSame(validated, authority.rendererSessions.single())
        first.close()
        assertFalse(validated.isLive)

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertEquals(1, authority.rendererSessions.size)
        assertFalse(owner.isLive())
        owner.close()
    }

    @Test
    fun sequentialClosedSessionsAreWeaklyRetainedAndCollected() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        capability.sessions.clear()
        val baseline = UiSyncRendererLeaseIdentityRegistry.sessionTombstoneCountForTests()
        val references = List(128) { openCloseAndForgetRendererSession(owner, capability) }
        assertEquals(128, references.size)

        repeat(30) {
            if (references.count { it.get() != null } < 16) return@repeat
            System.gc()
            System.runFinalization()
            Thread.sleep(10)
        }
        val remaining = references.count { it.get() != null }
        val retained = UiSyncRendererLeaseIdentityRegistry.sessionTombstoneCountForTests()

        assertTrue("too many renderer sessions remain strongly reachable: $remaining", remaining < 16)
        assertTrue("renderer tombstones did not drain: $retained", retained <= baseline + remaining)
        owner.close()
    }

    @Test
    fun missingOrIncompleteChallengeBoundReceiptCannotMintReadiness() {
        listOf(
            ReadinessReceiptOverride.MISSING,
            ReadinessReceiptOverride.SEMANTIC_INACTIVE,
            ReadinessReceiptOverride.THEME_INACTIVE,
            ReadinessReceiptOverride.BACKEND_DEAD,
        ).forEach { wrong ->
            val capability = RecordingCapability(factoryReadinessOverride = wrong)
            assertThrows(IllegalArgumentException::class.java) {
                readyProvisioning(capability)
            }
            assertEquals(1, capability.factoryCloseCalls.get())
        }

        listOf(
            ReadinessReceiptOverride.MISSING,
            ReadinessReceiptOverride.PROGRAM_CHANGED,
        ).forEach { wrong ->
            val invalidSession = RecordingCapability(sessionReadinessOverride = wrong)
            assertThrows(IllegalArgumentException::class.java) {
                readyProvisioning(invalidSession)
            }
            assertEquals(1, invalidSession.factoryCloseCalls.get())
        }
    }

    @Test
    fun staleProbeReceiptCannotAuthorizeARealSessionAndPoisonsFurtherOpens() {
        val capability = RecordingCapability(
            sessionReadinessOverride = ReadinessReceiptOverride.STALE_PROBE,
        )
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(capability, authority).transferToRuntime()

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertTrue(authority.rendererSessions.isEmpty())
        assertFalse(owner.isLive())
        assertThrows(IllegalStateException::class.java) { owner.openSession() }

        owner.close()
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun realSessionReceiptChangingDuringOpenFailsClosed() {
        val capability = RecordingCapability(
            sessionReadinessOverride = ReadinessReceiptOverride.CHANGE_AFTER_FIRST_READ,
        )
        val owner = readyProvisioning(capability).transferToRuntime()

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertFalse(owner.isLive())

        owner.close()
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun realSessionReceiptChangingAfterOpenRevokesAllFurtherUse() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        val session = owner.openSession()
        val rendererSession = capability.sessions.last()
        assertTrue(session.isLive())

        rendererSession.invalidateReadinessReceipt()

        assertFalse(session.isLive())
        assertNull(session.acquireUse())
        assertThrows(IllegalStateException::class.java) {
            session.prepareScreen(exactHandle())
        }

        session.close()
        owner.close()
    }

    @Test
    fun factoryReceiptChangingAfterProvisioningMakesOwnerAndNextOpenFailClosed() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        assertTrue(owner.isLive())

        capability.invalidateFactoryReceipt()

        assertFalse(owner.isLive())
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertFalse(owner.isLive())
        owner.close()
    }

    @Test
    fun factoryReceiptBindsStaticProgramButNeverClaimsSubscriptionsOrOutputs() {
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability).transferToRuntime()
        val receipt = requireNotNull(owner.rendererReadinessReceipt)

        assertEquals(owner.schemaFingerprint, receipt.schemaFingerprint)
        assertEquals(owner.bindingFingerprint, receipt.bindingFingerprint)
        assertEquals(owner.displayIds, receipt.displayIds)
        assertEquals(RendererReadinessReceipt.Phase.STATIC_FACTORY, receipt.phase)
        assertEquals(emptySet<String>(), receipt.subscribedDisplayIds)
        assertEquals(emptySet<UltraDisplayOutputId>(), receipt.boundOutputIds)
        assertEquals(PROGRAM_FINGERPRINT, receipt.semanticThemeProgramFingerprint)
        assertTrue(receipt.semanticCompilerActive)
        assertTrue(receipt.themeCompilerActive)
        assertTrue(receipt.backendLive)

        owner.close()
    }

    @Test
    fun semanticThemeProgramFingerprintIsExactlySha256SizedAndDefensivelyCopied() {
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncSemanticThemeProgramFingerprint(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncSemanticThemeProgramFingerprint(ByteArray(33))
        }

        val source = ByteArray(32) { it.toByte() }
        val fingerprint = UiSyncSemanticThemeProgramFingerprint(source)
        source[0] = 0x7f
        val exported = fingerprint.bytes()
        exported[1] = 0x7e

        assertEquals(0, fingerprint.bytes()[0].toInt())
        assertEquals(1, fingerprint.bytes()[1].toInt())
        assertEquals(64, fingerprint.hex().length)
    }

    @Test
    fun factoryReceiptDoesNotConsultCallerClaimedRuntimeCollections() {
        val hostileSize = RecordingCapability(
            factoryReadinessOverride = ReadinessReceiptOverride.HOSTILE_COLLECTION_SIZE,
        )
        readyProvisioning(hostileSize).close()

        listOf(
            ReadinessReceiptOverride.DUPLICATE_OUTPUTS,
            ReadinessReceiptOverride.TOO_MANY_SUBSCRIPTIONS,
        ).forEach { wrong ->
            readyProvisioning(RecordingCapability(factoryReadinessOverride = wrong)).close()
        }
    }

    @Test
    fun staticManifestAuthorizationWithoutHostPreflightCannotOfferOrOpenClusterControl() {
        val provisioning = readyProvisioning(RecordingCapability(), physicalAuthority = null)
        assertTrue(provisioning.authorizationSnapshot() != null)
        val runtime = AirPlayUltraRuntime(uiSyncProvisioning = provisioning)
        val configuration = runtime.configuration(setOf(AirPlayFeature.UI_SYNC))
        assertFalse(configuration.readiness.uiSync?.renderer?.isComplete == true)
        assertFalse(
            AirPlayFeatureNegotiation.snapshot(config(configuration))
                .isReady(AirPlayFeature.UI_SYNC),
        )
        val session = runtime.openSession()
        assertFalse(
            session.handlerFactory.descriptor(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL)
                ?.probe()?.isReady == true,
        )
        assertThrows(IllegalStateException::class.java) {
            session.prepareScreen(exactHandle())
        }
        session.close()
        runtime.close()
    }

    @Test
    fun fabricatedSessionReceiptIdsCannotReplaceObservedPresentationSubscriptions() {
        val capability = RecordingCapability(actualSubscriptionDisplayIds = emptySet())
        val owner = readyProvisioning(capability).transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertFalse(owner.isLive())
        owner.close()
    }

    @Test
    fun partialObservedSubscriptionsCannotActivateMultiDisplayClusterControl() {
        val displays = setOf("cluster.one", "cluster.two")
        val plan = staticPlan(
            outputs = listOf(
                output("first", "cluster.one", 1),
                output("second", "cluster.two", 2),
            ),
            ingressSpecs = emptyList(),
        )
        val provisioning = authorizedReadyProvisioning(
            uiSyncInfo = AirPlayUiSyncInfo(),
            displays = displays.map(::displaySpec),
            staticPlan = plan,
            rendererCapability = RecordingCapability(
                actualSubscriptionDisplayIds = setOf("cluster.one"),
            ),
            callbackExecutor = BoundedTestPresentationExecutor(parallelism = 2),
        )
        val owner = provisioning.transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertFalse(owner.isLive())
        owner.close()
    }

    @Test
    fun physicalBatchFailureAfterOfferRejectsSessionAndClosesRenderer() {
        val authority = TestPhysicalAuthority(mode = TestPhysicalAuthority.Mode.MISSING_BATCH)
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertTrue(capability.sessions.last().closed)
        assertEquals(0, authority.activationCalls.get())
        assertFalse(owner.isLive())
        owner.close()
    }

    @Test
    fun stalePhysicalBatchIsRetiredAndCannotActivateTheSession() {
        val authority = TestPhysicalAuthority(mode = TestPhysicalAuthority.Mode.STALE_BATCH)
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertEquals(1, authority.backendCloses.get())
        assertEquals(0, authority.activationCalls.get())
        owner.close()
    }

    @Test
    fun physicalActivationRunsOnceAfterExactBatchBindAndCanAwaitVideoIngress() {
        val authority = TestPhysicalAuthority(
            mode = TestPhysicalAuthority.Mode.PENDING_INPUT_STREAM,
            captureActivations = true,
        )
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()

        val session = owner.openSession()
        assertTrue(session.isLive())
        assertEquals(1, authority.activationCalls.get())
        assertSame(authority.boundContexts.single(), authority.activationContexts.single())
        assertSame(authority.boundBatches.single(), authority.activatedBatches.single())
        assertTrue(authority.activatedBatches.single().isCurrent)
        assertTrue(capability.sessions.last().isLive)

        session.close()
        assertEquals(1, authority.backendCloses.get())
        assertFalse(authority.backendClosedWhileRendererLive.get())
        owner.close()
    }

    @Test
    fun pendingLocalCanvasTransitionRetainsExactPhysicalSessionWithoutSceneSubmissionClaim() {
        val authority = TestPhysicalAuthority(
            mode = TestPhysicalAuthority.Mode.PENDING_LOCAL_CANVAS,
            captureActivations = true,
        )
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isLive())
        assertEquals(1, authority.activationCalls.get())
        assertSame(authority.boundBatches.single(), authority.activatedBatches.single())
        assertTrue(authority.activatedBatches.single().isCurrent)
        session.close()
        assertEquals(1, authority.backendCloses.get())
        owner.close()
    }

    @Test
    fun physicalActivationFailureRetiresRendererBeforeExactBatch() {
        val authority = TestPhysicalAuthority(
            mode = TestPhysicalAuthority.Mode.ACTIVATION_THROWS,
            captureActivations = true,
        )
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()

        val failure = assertThrows(IllegalStateException::class.java) { owner.openSession() }
        assertEquals("post-bind activation failed", failure.message)
        assertEquals(1, authority.activationCalls.get())
        assertSame(authority.boundBatches.single(), authority.activatedBatches.single())
        assertTrue(capability.sessions.last().closed)
        assertEquals(1, authority.backendCloses.get())
        assertFalse(authority.backendClosedWhileRendererLive.get())

        owner.close()
    }

    @Test
    fun physicalActivationExceptionKeepsFailedChildRetirementRetryable() {
        val authority = TestPhysicalAuthority(mode = TestPhysicalAuthority.Mode.ACTIVATION_THROWS)
        val capability = RecordingCapability(runtimeSessionCloseFailures = 1)
        val owner = readyProvisioning(capability, authority).transferToRuntime()

        val failure = assertThrows(IllegalStateException::class.java) { owner.openSession() }
        assertEquals("post-bind activation failed", failure.message)
        assertEquals(1, capability.sessions.last().closeCalls.get())
        assertEquals(0, authority.backendCloses.get())
        assertFalse(owner.isLive())

        owner.close()
        assertEquals(2, capability.sessions.last().closeCalls.get())
        assertEquals(1, authority.backendCloses.get())
        assertFalse(authority.backendClosedWhileRendererLive.get())
    }

    @Test
    fun failedPostActivationValidationRetainsExactChildForTypedRetry() {
        val capability = RecordingCapability(runtimeSessionCloseFailures = 2)
        val authority = TestPhysicalAuthority(
            captureActivations = true,
            onActivate = { capability.sessions.last().invalidateReadinessReceipt() },
        )
        val runtime = AirPlayUltraRuntime(
            uiSyncProvisioning = readyProvisioning(capability, authority),
        )

        val failure = assertThrows(AirPlayUltraSessionOpenCleanupException::class.java) {
            runtime.openSession()
        }
        assertTrue(failure.cause?.message.orEmpty().contains("readiness changed during validation"))
        assertEquals(1, authority.activationCalls.get())
        assertSame(authority.boundBatches.single(), authority.activatedBatches.single())
        assertEquals(2, capability.sessions.last().closeCalls.get())
        assertEquals(0, authority.backendCloses.get())
        assertEquals(2, failure.cause?.suppressed?.count {
            it.message == "session close retry"
        })

        failure.close()
        assertEquals(3, capability.sessions.last().closeCalls.get())
        assertTrue(capability.sessions.last().closed)
        assertEquals(1, authority.backendCloses.get())
        assertFalse(authority.backendClosedWhileRendererLive.get())
        failure.close()
        assertEquals(3, capability.sessions.last().closeCalls.get())
        runtime.close()
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun rendererOpenFailureWithoutAcquiredChildPreservesOriginalFailure() {
        val capability = RecordingCapability(failRuntimeOpen = true)
        val runtime = AirPlayUltraRuntime(
            uiSyncProvisioning = readyProvisioning(capability),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            runtime.openSession()
        }
        assertEquals("runtime child unavailable", failure.message)
        assertFalse(failure is AirPlayUltraSessionOpenCleanupException)
        runtime.close()
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun physicalAuthorityWithoutPostBindHookFailsClosed() {
        val delegate = TestPhysicalAuthority()
        val authority = object : UiSyncPhysicalOutputAuthority {
            override fun preflight(context: UiSyncPhysicalOutputPreflightContext) =
                delegate.preflight(context)

            override fun bindSession(context: UiSyncPhysicalOutputSessionContext) =
                delegate.bindSession(context)

            override fun coverBoundSessionForClose(
                context: UiSyncPhysicalOutputSessionContext,
                batch: UiSyncPhysicalOutputBatch,
            ) = delegate.coverBoundSessionForClose(context, batch)
        }
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()

        assertThrows(IllegalStateException::class.java) { owner.openSession() }
        assertTrue(capability.sessions.last().closed)
        assertEquals(1, delegate.backendCloses.get())
        assertFalse(delegate.backendClosedWhileRendererLive.get())
        owner.close()
    }

    @Test
    fun stopAtPrebindCandidatePreventsLatePhysicalBindAndActivation() {
        val authority = TestPhysicalAuthority(captureActivations = true)
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val candidate = AtomicReference<UiSyncProvisionedSession>()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val retired = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val opener = Thread {
            failure.set(runCatching {
                owner.openSession(beforePhysicalBind = { child ->
                    candidate.set(child)
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }, onPhysicalCandidateRetired = { child ->
                    assertSame(candidate.get(), child)
                    retired.incrementAndGet()
                })
            }.exceptionOrNull())
        }
        opener.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(requireNotNull(candidate.get()).coverForSessionStop())
        resume.countDown()
        opener.join(5_000)
        assertFalse(opener.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(authority.boundContexts.isEmpty())
        assertEquals(0, authority.activationCalls.get())
        assertEquals(1, retired.get())
        owner.close()
    }

    @Test
    fun stopDuringBlockedPhysicalBindRetainsCandidateAndCoversBeforeActivation() {
        val delegate = TestPhysicalAuthority(captureActivations = true)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val authority = object : UiSyncPhysicalOutputAuthority {
            override fun preflight(context: UiSyncPhysicalOutputPreflightContext) =
                delegate.preflight(context)
            override fun bindSession(context: UiSyncPhysicalOutputSessionContext):
                UiSyncPhysicalOutputBatch? {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return delegate.bindSession(context)
            }
            override fun coverBoundSessionForClose(
                context: UiSyncPhysicalOutputSessionContext,
                batch: UiSyncPhysicalOutputBatch,
            ) = delegate.coverBoundSessionForClose(context, batch)
            override fun activateBoundSession(
                context: UiSyncPhysicalOutputSessionContext,
                batch: UiSyncPhysicalOutputBatch,
            ) = delegate.activateBoundSession(context, batch)
        }
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val candidate = AtomicReference<UiSyncProvisionedSession>()
        val failure = AtomicReference<Throwable?>()
        val opener = Thread {
            failure.set(runCatching {
                owner.openSession(beforePhysicalBind = candidate::set)
            }.exceptionOrNull())
        }
        opener.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertFalse(requireNotNull(candidate.get()).coverForSessionStop())
        resume.countDown()
        opener.join(5_000)
        assertFalse(opener.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(delegate.coverCalls.get() >= 1)
        assertEquals(0, delegate.activationCalls.get())
        assertEquals(1, delegate.backendCloses.get())
        owner.close()
    }

    @Test
    fun postBindActivationCallbackDoesNotHoldProvisioningLifecycleLock() {
        val ownerRef = AtomicReference<UiSyncRuntimeProvisioningOwner>()
        val authority = TestPhysicalAuthority(onActivate = {
            val observed = AtomicReference<Result<Boolean>>()
            val reader = Thread {
                observed.set(runCatching { ownerRef.get().isLive() })
            }
            reader.start()
            reader.join(3_000)
            assertFalse("activation callback held the provisioning lifecycle lock", reader.isAlive)
            assertTrue(observed.get().getOrThrow())
        })
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        ownerRef.set(owner)

        val session = owner.openSession()
        assertTrue(session.isLive())
        assertEquals(1, authority.activationCalls.get())
        session.close()
        owner.close()
    }

    @Test
    fun hostTargetRevocationDisablesActiveSessionEvenWhenBatchBackendRemainsCurrent() {
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isLive())
        authority.revokeHostTargets()
        assertFalse(session.isLive())
        assertNull(session.acquireUse())
        assertThrows(IllegalStateException::class.java) {
            session.prepareScreen(exactHandle())
        }
        session.close()
        owner.close()
    }

    @Test
    fun hostRevocationBetweenScreenPreparationAndSetupFlushNeverPublishesCandidate() {
        val authority = TestPhysicalAuthority()
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val session = owner.openSession()
        val prepared = requireNotNull(session.prepareScreen(exactHandle()))
        val rendererCandidate = capability.sessions.last().candidates.single()

        authority.revokeHostTargets()
        assertFalse(prepared.commit())
        assertEquals(0, rendererCandidate.publishCalls.get())
        assertEquals(1, rendererCandidate.closeCalls.get())
        assertFalse(prepared.isPublished)

        session.close()
        owner.close()
    }

    @Test
    fun hostRevocationDuringScreenPublicationRetiresTheExactCandidate() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authority = TestPhysicalAuthority()
        val capability = RecordingCapability(blockPublish = entered to release)
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val session = owner.openSession()
        val prepared = requireNotNull(session.prepareScreen(exactHandle()))
        val result = AtomicReference<Result<Boolean>>()
        val commit = Thread { result.set(runCatching { prepared.commit() }) }
        commit.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        authority.revokeHostTargets()
        release.countDown()
        commit.join(5_000)

        assertFalse(commit.isAlive)
        assertEquals(false, result.get().getOrThrow())
        val rendererCandidate = capability.sessions.last().candidates.single()
        assertEquals(1, rendererCandidate.publishCalls.get())
        assertEquals(1, rendererCandidate.closeCalls.get())
        assertFalse(prepared.isPublished)

        session.close()
        owner.close()
    }

    @Test
    fun failedPostPublishRetirementKeepsSameCandidateRetryable() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authority = TestPhysicalAuthority()
        val capability = RecordingCapability(
            blockPublish = entered to release,
            failCandidateCloseOnce = true,
        )
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val session = owner.openSession()
        val prepared = requireNotNull(session.prepareScreen(exactHandle()))
        val commitFailure = AtomicReference<Throwable?>()
        val commit = Thread {
            try { prepared.commit() } catch (failure: Throwable) { commitFailure.set(failure) }
        }
        commit.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        authority.revokeHostTargets()
        release.countDown()
        commit.join(5_000)
        assertTrue(commitFailure.get() is IllegalStateException)
        val rendererCandidate = capability.sessions.last().candidates.single()
        assertEquals(1, rendererCandidate.closeCalls.get())

        prepared.close()
        assertEquals(2, rendererCandidate.closeCalls.get())
        session.close()
        owner.close()
    }

    @Test
    fun blockedPresentationCallbackMustDrainBeforePhysicalBatchRetirement() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authority = TestPhysicalAuthority()
        val capability = RecordingCapability(
            onRuntimePresentationSnapshot = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            },
        )
        val owner = readyProvisioning(
            capability,
            authority,
            ThreadedTestPresentationExecutor(),
        ).transferToRuntime()
        val session = owner.openSession()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val closeResult = AtomicReference<Result<Unit>>()
        val closing = Thread { closeResult.set(runCatching { session.close() }) }
        closing.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (session.isLive() && System.nanoTime() < deadline) Thread.yield()
            assertFalse(session.isLive())
            // closeRequested is published before the exact Host-cover call. Wait for the cover
            // invocation, not merely for the earlier liveness flip on another thread.
            val coverDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (authority.coverCalls.get() == 0 && System.nanoTime() < coverDeadline)
                Thread.yield()
            assertEquals(1, authority.coverCalls.get())
            assertEquals(0, authority.backendCloses.get())
            assertFalse(capability.sessions.last().closed)

            release.countDown()
            closing.join(5_000)
            assertFalse(closing.isAlive)
            closeResult.get().getOrThrow()
            assertEquals(1, authority.backendCloses.get())
            assertTrue(capability.sessions.last().closed)
        } finally {
            release.countDown()
            closing.join(5_000)
            if (!closing.isAlive) runCatching { session.close() }
            runCatching { owner.close() }
        }
    }

    @Test
    fun protocolOnlySessionClosesWithoutPhysicalCoverAuthority() {
        val route = UltraDisplayBinding(
            UltraUiSyncDisplayId(DISPLAY_ID),
            UltraTopologyDisplayId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            UltraAirPlayStreamIdentity(111, STREAM_CONNECTION_ID, null),
            UltraDisplayOutputId.Custom("protocol-only"),
        )
        val registry = UltraDisplayBindingRegistry()
        registry.replace(
            UltraDisplayBindingResources(
                listOf(route.uiSyncDisplayId), listOf(route.topologyDisplayId),
                listOf(route.airPlayStreamIdentity), listOf(route.outputId),
            ),
            listOf(route),
        )
        val owner = UiSyncRuntimeProvisioning.from(
            AirPlayUiSyncInfo(), listOf(displaySpec()), registry, Runnable::run,
        ).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isLive())
        session.close()
        owner.close()
    }

    @Test
    fun queuedHubSnapshotsCannotCallRendererAfterPhysicalBatchRetirement() {
        val deliveries = AtomicInteger()
        val executor = BoundedTestPresentationExecutor()
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(
            RecordingCapability(onRuntimePresentationSnapshot = { deliveries.incrementAndGet() }),
            authority,
            executor,
        ).transferToRuntime()
        val session = owner.openSession()
        assertEquals(0, deliveries.get())
        session.close()
        assertEquals(1, authority.backendCloses.get())
        executor.drain()
        assertEquals(0, deliveries.get())
        owner.close()
    }

    @Test
    fun rendererCallbackCannotDeadlockByClosingItsOwnSession() {
        val executor = BoundedTestPresentationExecutor()
        val authority = TestPhysicalAuthority()
        val sessionRef = AtomicReference<UiSyncProvisionedSession>()
        val callbackCloseFailure = AtomicReference<Throwable?>()
        val capability = RecordingCapability(onRuntimePresentationSnapshot = {
            try { sessionRef.get().close() }
            catch (failure: Throwable) { callbackCloseFailure.set(failure) }
        })
        val owner = readyProvisioning(
            capability,
            authority,
            executor,
        ).transferToRuntime()
        val session = owner.openSession()
        sessionRef.set(session)
        executor.drain()
        assertTrue(callbackCloseFailure.get() is IllegalStateException)
        // The source rejects retirement on its own callback thread. Cover must already have
        // been requested while the renderer child and physical batch remain attached.
        assertEquals(1, authority.coverCalls.get())
        assertEquals(0, authority.backendCloses.get())
        assertFalse(capability.sessions.last().closed)
        session.close()
        assertEquals(2, authority.coverCalls.get())
        assertEquals(1, authority.backendCloses.get())
        owner.close()
    }

    @Test
    fun failedPhysicalCoverRetainsExactChildInOwnerJournalUntilCoverRetrySucceeds() {
        val authority = TestPhysicalAuthority(
            coverFailures = 1, coverThrows = 1, captureActivations = true,
        )
        val capability = RecordingCapability()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val session = owner.openSession()
        val exactBatch = authority.boundBatches.single()

        assertThrows(IllegalStateException::class.java) { session.close() }
        assertFalse(session.isLive())
        assertTrue(exactBatch.isCurrent)
        assertFalse(capability.sessions.last().closed)
        assertEquals(0, authority.backendCloses.get())

        // The owner retains the failed child and retries its exact cover before retirement.
        assertThrows(IllegalStateException::class.java) { owner.close() }
        assertEquals(2, authority.coverCalls.get())
        assertEquals(0, authority.backendCloses.get())
        owner.close()
        assertEquals(3, authority.coverCalls.get())
        assertTrue(capability.sessions.last().closed)
        assertEquals(1, authority.backendCloses.get())
        assertFalse(authority.backendClosedWhileRendererLive.get())
    }

    @Test
    fun backendRevocationDisablesActiveSessionEvenWhenHostTargetRemainsCurrent() {
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isLive())
        authority.revokeBackend()
        assertFalse(session.isLive())
        assertNull(session.acquireUse())
        session.close()
        owner.close()
    }

    @Test
    fun physicalAuthorityReceivesTheValidatedRendererChildNotTheClosedFactoryProbe() {
        val capability = RecordingCapability()
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val probe = capability.sessions.single()
        assertFalse(probe.isLive)

        val session = owner.openSession()
        assertSame(capability.sessions[1], authority.rendererSessions.single())
        assertNotSame(probe, authority.rendererSessions.single())
        assertTrue(authority.rendererSessions.single().isLive)
        assertEquals(1, authority.activationCalls.get())
        assertTrue(session.isLive())
        assertEquals(
            RendererReadinessReceipt.Phase.SESSION_IMPLEMENTATION,
            authority.rendererSessions.single().readinessReceipt?.phase,
        )
        session.close()
        owner.close()
    }

    @Test
    fun rendererClosedDuringPhysicalBindCannotBecomeAnActiveSession() {
        val capability = RecordingCapability()
        val authority = TestPhysicalAuthority(
            mode = TestPhysicalAuthority.Mode.CLOSE_RENDERER_DURING_BIND,
        )
        val owner = readyProvisioning(capability, authority).transferToRuntime()

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertSame(capability.sessions[1], authority.rendererSessions.single())
        assertFalse(capability.sessions[1].isLive)
        assertEquals(1, authority.backendCloses.get())
        assertFalse(owner.isLive())
        owner.close()
    }

    @Test
    fun simultaneousSessionBindsReceiveDifferentExactRendererChildren() {
        val capability = RecordingCapability()
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(capability, authority).transferToRuntime()
        val start = CountDownLatch(1)
        val opened = Collections.synchronizedList(mutableListOf<UiSyncProvisionedSession>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = List(2) {
            Thread {
                start.await()
                runCatching { owner.openSession() }
                    .onSuccess(opened::add)
                    .onFailure(failures::add)
            }
        }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none(Thread::isAlive))
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(2, opened.size)
        assertEquals(2, authority.rendererSessions.size)
        assertNotSame(authority.rendererSessions[0], authority.rendererSessions[1])
        assertTrue(authority.rendererSessions.all { renderer ->
            capability.sessions.drop(1).any { it === renderer }
        })
        opened.forEach(UiSyncProvisionedSession::close)
        owner.close()
    }

    @Test
    fun changedBackendGenerationRevokesActiveSessionWithoutChangingItsOutputIds() {
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val session = owner.openSession()
        assertTrue(session.isLive())
        authority.bumpBackendGeneration()
        assertFalse(session.isLive())
        session.close()
        owner.close()
    }

    @Test
    fun transientPhysicalBusyAfterActivationWaitsForCurrentInsteadOfRetiringSession() {
        lateinit var authority: TestPhysicalAuthority
        val resumed = AtomicBoolean(false)
        val waitedWhileBusy = AtomicBoolean(false)
        val resumer = AtomicReference<Thread>()
        authority = TestPhysicalAuthority(onActivate = {
            // Models a concurrent read-only AA05 liveness probe holding its reservation
            // across the post-submit check. Resume only once the opener is parked in BUSY.
            val opener = Thread.currentThread()
            authority.busyBackend()
            resumer.set(Thread {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (opener.state != Thread.State.TIMED_WAITING &&
                    System.nanoTime() < deadline) Thread.yield()
                waitedWhileBusy.set(opener.state == Thread.State.TIMED_WAITING)
                resumed.set(true)
                authority.resumeBackend()
            }.also { it.start() })
        })
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val session = owner.openSession()
        requireNotNull(resumer.get()).join(5_000)
        assertTrue(waitedWhileBusy.get())
        assertTrue(resumed.get())
        assertEquals(1, authority.activationCalls.get())
        assertEquals(UiSyncForegroundUseStatus.CURRENT, session.foregroundUseStatus())
        assertTrue(session.isLive())
        assertEquals(0, authority.backendCloses.get())
        session.close()
        owner.close()
    }

    @Test
    fun persistentPhysicalBusyAfterActivationFailsClosedWithinTheBound() {
        lateinit var authority: TestPhysicalAuthority
        authority = TestPhysicalAuthority(onActivate = { authority.busyBackend() })
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val failure = assertThrows(IllegalArgumentException::class.java) {
            owner.openSession()
        }
        assertTrue(failure.message.orEmpty().contains("retired during activation"))
        assertEquals(1, authority.activationCalls.get())
        authority.resumeBackend()
        owner.close()
    }

    @Test
    fun exactSessionForegroundUseIsBusyOnlyWhilePhysicalBatchIsTemporarilyBusy() {
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val session = owner.openSession()
        assertEquals(UiSyncForegroundUseStatus.CURRENT, session.foregroundUseStatus())
        assertTrue(session.isCarrierLive())
        authority.busyBackend()
        assertEquals(UiSyncForegroundUseStatus.BUSY, session.foregroundUseStatus())
        assertFalse(session.isLive())
        assertTrue(session.isCarrierLive())
        session.acquireUse()!!.close()
        authority.revokeHostTargets()
        assertEquals(UiSyncForegroundUseStatus.REVOKED, session.foregroundUseStatus())
        assertFalse(session.isCarrierLive())
        assertNull(session.acquireUse())
        session.close()
        owner.close()
    }

    @Test
    fun clusterControlCarrierSurvivesPhysicalBusyWithoutClaimingForegroundReady() {
        val authority = TestPhysicalAuthority()
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val uiSyncSession = owner.openSession()
        val clientType = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
        val placeholder = RcsDataStreamHandlerDescriptor.ready(
            clientType, "test.cluster.placeholder",
        ) { object : RcsDataStreamHandler {} }
        val runtime = AirPlayUltraSessionRuntime(
            baseDescriptors = mapOf(clientType.uuid to placeholder),
            legacyUiSyncRegistry = CarPlayUiSyncEndpointRegistry(emptyList()),
            cafSession = null,
            uiSyncSession = uiSyncSession,
            onClosed = { },
        )
        val stream = RcsDataStream.open(
            clientType = clientType,
            readKey = ByteArray(32) { 1 },
            writeKey = ByteArray(32) { 2 },
            bindAddress = InetAddress.getLoopbackAddress(),
        )
        try {
            val descriptor = requireNotNull(runtime.handlerFactory.descriptor(clientType))
            val handler = descriptor.create(stream)
            try {
                // The synthetic direct-handler fixture must enter the authenticated-open
                // callback before delivering COMM; production RcsDataStream enforces this.
                handler.onStreamOpened(stream)
                authority.busyBackend()
                assertTrue(runtime.supports(clientType))
                assertTrue(descriptor.probe().isReady)
                assertFalse(runtime.sendUiSyncCommand(
                    DISPLAY_ID, CarPlayUiSyncCommandType.REQUEST_LAYOUT,
                ))
                handler.onMessage(stream, RcsMessage.comm(byteArrayOf(1)))
                assertEquals(
                    listOf(CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER),
                    runtime.drainUiSyncDiagnostics().map { it.kind },
                )

                authority.resumeBackend()
                assertTrue(uiSyncSession.isLive())
                handler.onMessage(stream, RcsMessage.comm(byteArrayOf(2)))
                assertEquals(
                    listOf(CarPlayUiSyncRoutingDiagnosticKind.MALFORMED_CARRIER),
                    runtime.drainUiSyncDiagnostics().map { it.kind },
                )
                authority.revokeHostTargets()
                assertFalse(runtime.supports(clientType))
                assertFalse(descriptor.probe().isReady)
            } finally {
                handler.close()
            }
        } finally {
            stream.close()
            runtime.close()
            owner.close()
        }
    }

    @Test
    fun physicalBatchDistinguishesBackendBusyFromRevokedHostTarget() {
        val plan = staticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = emptyList(),
        )
        val requirement = physicalRequirement(plan)
        val route = UiSyncPhysicalOutputRoute(
            requirement.assignments.single(),
            UiSyncPhysicalTargetVersion("busy-host-target", 1),
        )
        val backendStatus = AtomicReference(UiSyncPhysicalGenerationStatus.CURRENT)
        val backend = object : UiSyncPhysicalOutputBackendLease {
            override val generation = 1L
            override val isCurrent: Boolean
                get() = backendStatus.get() == UiSyncPhysicalGenerationStatus.CURRENT
            override val generationStatus: UiSyncPhysicalGenerationStatus
                get() = backendStatus.get()
            override fun close() = Unit
        }
        val targetCurrent = AtomicBoolean(true)
        val target = object : UiSyncPhysicalTargetLease {
            override val version = route.target
            override val isCurrent: Boolean
                get() = targetCurrent.get()
        }
        val context = UiSyncPhysicalOutputSessionContext(
            requirement, plan,
            UiSyncSchemaFingerprint(ByteArray(32) { 1 }),
            UiSyncBindingFingerprint(ByteArray(32) { 2 }),
            SharedCompleteSession(),
        )
        val batch = context.issueActiveBatch(backend, listOf(route), listOf(target))
        assertEquals(UiSyncPhysicalGenerationStatus.CURRENT, batch.generationStatus)
        backendStatus.set(UiSyncPhysicalGenerationStatus.BUSY)
        assertEquals(UiSyncPhysicalGenerationStatus.BUSY, batch.generationStatus)
        assertFalse(batch.isCurrent)
        targetCurrent.set(false)
        assertEquals(UiSyncPhysicalGenerationStatus.REVOKED, batch.generationStatus)
        targetCurrent.set(true)
        backendStatus.set(UiSyncPhysicalGenerationStatus.REVOKED)
        assertEquals(UiSyncPhysicalGenerationStatus.REVOKED, batch.generationStatus)
        batch.close()
        backendStatus.set(UiSyncPhysicalGenerationStatus.CURRENT)
        assertEquals(UiSyncPhysicalGenerationStatus.REVOKED, batch.generationStatus)
    }

    @Test
    fun batchFromEarlierSessionCannotAuthorizeAnotherOrRetireItsOutput() {
        val authority = TestPhysicalAuthority(mode = TestPhysicalAuthority.Mode.REPLAY_FIRST_BATCH)
        val owner = readyProvisioning(RecordingCapability(), authority).transferToRuntime()
        val first = owner.openSession()
        assertTrue(first.isLive())
        assertEquals(1, authority.activationCalls.get())

        assertThrows(IllegalArgumentException::class.java) { owner.openSession() }
        assertEquals(1, authority.activationCalls.get())
        assertEquals(0, authority.backendCloses.get())
        assertFalse(first.isLive())

        first.close()
        assertEquals(1, authority.backendCloses.get())
        owner.close()
    }

    @Test
    fun signedAssignmentAndTargetCoverageRejectsMissingSwappedOrDuplicateRows() {
        val plan = staticPlan(
            outputs = listOf(
                output("cluster", DISPLAY_ID, 1),
                output("local", null, 2),
            ),
            ingressSpecs = emptyList(),
        )
        val requirement = physicalRequirement(plan)
        val context = UiSyncPhysicalOutputSessionContext(
            requirement,
            plan,
            UiSyncSchemaFingerprint(ByteArray(32) { 1 }),
            UiSyncBindingFingerprint(ByteArray(32) { 2 }),
            SharedCompleteSession(),
        )
        val backend = object : UiSyncPhysicalOutputBackendLease {
            override val generation = 1L
            override val isCurrent = true
            override fun close() = Unit
        }
        val good = requirement.assignments.mapIndexed { index, assignment ->
            UiSyncPhysicalOutputRoute(
                assignment, UiSyncPhysicalTargetVersion("physical-$index", 1),
            )
        }
        val targetLeases = good.map { route ->
            object : UiSyncPhysicalTargetLease {
                override val version = route.target
                override val isCurrent = true
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.issueActiveBatch(backend, good.dropLast(1), targetLeases.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.issueActiveBatch(backend, good, targetLeases.dropLast(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.issueActiveBatch(
                backend,
                listOf(good.first(), good.first().copy(target = good.last().target)),
                targetLeases,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.issueActiveBatch(
                backend,
                good.mapIndexed { index, route ->
                    route.copy(assignment = route.assignment.copy(
                        destination = good[1 - index].assignment.destination,
                    ))
                },
                targetLeases,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.issueActiveBatch(
                backend,
                good.map { it.copy(target = good.first().target) },
                targetLeases,
            )
        }
        val validBatch = context.issueActiveBatch(backend, good, targetLeases)
        assertTrue(context.issued(validBatch))

        val equalButForeign = good.map { route ->
            route.copy(assignment = route.assignment.copy(output = route.assignment.output.copy()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.issueActiveBatch(backend, equalButForeign, targetLeases)
        }
        val foreignPlan = staticPlan(
            outputs = plan.outputs.map { it.copy() },
            ingressSpecs = emptyList(),
        )
        assertFalse(requirement.matchesPlan(foreignPlan))
        validBatch.close()
    }

    @Test
    fun physicalBackendAndHostTargetIdentitiesCannotSpanLiveSessionBatches() {
        val plan = staticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = emptyList(),
        )
        val requirement = physicalRequirement(plan)
        val route = UiSyncPhysicalOutputRoute(
            requirement.assignments.single(),
            UiSyncPhysicalTargetVersion("host-lease-one", 1),
        )
        val target = object : UiSyncPhysicalTargetLease {
            override val version = route.target
            override val isCurrent = true
        }
        val backendCloses = AtomicInteger()
        val backend = object : UiSyncPhysicalOutputBackendLease {
            override val generation = 7L
            override val isCurrent = true
            override fun close() { backendCloses.incrementAndGet() }
        }
        fun context() = UiSyncPhysicalOutputSessionContext(
            requirement, plan,
            UiSyncSchemaFingerprint(ByteArray(32) { 1 }),
            UiSyncBindingFingerprint(ByteArray(32) { 2 }),
            SharedCompleteSession(),
        )
        val old = context().issueActiveBatch(backend, listOf(route), listOf(target))
        assertThrows(IllegalArgumentException::class.java) {
            context().issueActiveBatch(backend, listOf(route), listOf(target))
        }
        val separateBackend = object : UiSyncPhysicalOutputBackendLease {
            override val generation = 8L
            override val isCurrent = true
            override fun close() = Unit
        }
        assertThrows(IllegalArgumentException::class.java) {
            context().issueActiveBatch(separateBackend, listOf(route), listOf(target))
        }
        val freshlyWrappedSameSurfaceVersion = object : UiSyncPhysicalTargetLease {
            override val version = route.target
            override val isCurrent = true
        }
        assertThrows(IllegalArgumentException::class.java) {
            context().issueActiveBatch(
                separateBackend, listOf(route), listOf(freshlyWrappedSameSurfaceVersion),
            )
        }
        assertEquals(0, backendCloses.get())
        old.close()
        val newer = context().issueActiveBatch(backend, listOf(route), listOf(target))
        old.close()
        assertEquals(1, backendCloses.get())
        assertTrue(newer.isCurrent)
        newer.close()
        assertEquals(2, backendCloses.get())
    }

    @Test
    fun failedPhysicalBatchRetirementRetainsExactIdentityClaimsForRetry() {
        val plan = staticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = emptyList(),
        )
        val requirement = physicalRequirement(plan)
        val route = UiSyncPhysicalOutputRoute(
            requirement.assignments.single(),
            UiSyncPhysicalTargetVersion("host-retry", 1),
        )
        val target = object : UiSyncPhysicalTargetLease {
            override val version = route.target
            override val isCurrent = true
        }
        val closeCalls = AtomicInteger()
        val backend = object : UiSyncPhysicalOutputBackendLease {
            override val generation = 9L
            override val isCurrent = true
            override fun close() {
                if (closeCalls.incrementAndGet() == 1) {
                    throw IllegalStateException("physical retirement uncertain")
                }
            }
        }
        fun context() = UiSyncPhysicalOutputSessionContext(
            requirement, plan,
            UiSyncSchemaFingerprint(ByteArray(32) { 1 }),
            UiSyncBindingFingerprint(ByteArray(32) { 2 }),
            SharedCompleteSession(),
        )
        val first = context().issueActiveBatch(backend, listOf(route), listOf(target))
        assertThrows(IllegalStateException::class.java) { first.close() }
        assertFalse(first.isCurrent)
        assertThrows(IllegalArgumentException::class.java) {
            context().issueActiveBatch(backend, listOf(route), listOf(target))
        }
        first.close()
        val second = context().issueActiveBatch(backend, listOf(route), listOf(target))
        second.close()
        assertEquals(3, closeCalls.get())
    }

    @Test
    fun physicalAssignmentFingerprintIsCanonicalAndBindsProfileAndDestinations() {
        val plan = staticPlan(
            outputs = listOf(output("first", DISPLAY_ID, 1), output("second", null, 2)),
            ingressSpecs = emptyList(),
        )
        val requirement = physicalRequirement(plan)
        val reversed = UiSyncPhysicalOutputRequirement(
            requirement.verifiedProfileDigest,
            requirement.assignments.reversed(),
        )
        assertEquals(requirement.assignmentFingerprint, reversed.assignmentFingerprint)
        val exported = requirement.assignmentFingerprint.bytes()
        exported[0] = (exported[0].toInt() xor 0xff).toByte()
        assertEquals(requirement.assignmentFingerprint, reversed.assignmentFingerprint)
        val changedProfile = UiSyncPhysicalOutputRequirement(
            UltraProfileDigest(ByteArray(32) { 0x33 }),
            requirement.assignments,
        )
        assertFalse(requirement.assignmentFingerprint == changedProfile.assignmentFingerprint)
        val changedRoute = UiSyncPhysicalOutputRequirement(
            requirement.verifiedProfileDigest,
            listOf(
                requirement.assignments.first().copy(destination = UiSyncPhysicalDestination(
                    UiSyncPhysicalDestinationRole.MAIN, "different",
                )),
                requirement.assignments.last(),
            ),
        )
        assertFalse(requirement.assignmentFingerprint == changedRoute.assignmentFingerprint)
    }

    @Test
    fun failedProbeValidationRetainsCleanupOwnerAcrossRepeatedCloseFailures() {
        val capability = RecordingCapability(
            sessionReceiptOverride = ReceiptOverride.DISPLAYS,
            probeSessionCloseFailures = 2,
        )
        val failure = assertThrows(UiSyncRendererProvisioningException::class.java) {
            readyProvisioning(capability)
        }
        val probe = capability.sessions.single()
        assertEquals(2, probe.closeCalls.get())
        assertFalse(probe.closed)
        assertFalse(capability.factoryClosed.get())

        failure.close()
        assertEquals(3, probe.closeCalls.get())
        assertTrue(probe.closed)
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun failedInitialReplayBeforeRendererChildRetainsRawObserverForRuntimeOwnerClose() {
        for (failedCloses in 1..2) {
            val snapshotFailure = IllegalStateException("runtime initial replay failed")
            val cleanupFailures = List(failedCloses) { index ->
                IllegalStateException("observer cleanup failed ${index + 1}")
            }
            val observerCloseCalls = AtomicInteger()
            val capability = RecordingCapability()
            val plan = staticPlan(
                outputs = listOf(output("cluster", DISPLAY_ID, 1)),
                ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
            )
            val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
                uiSyncInfo = AirPlayUiSyncInfo(),
                displays = listOf(displaySpec()),
                staticPlan = plan,
                rendererCapability = capability,
                callbackExecutor = BoundedTestPresentationExecutor(),
                sessionPresentationSourceFactory = {
                    presentationSource(
                        observe = { _, _ -> Closeable {
                            val attempt = observerCloseCalls.getAndIncrement()
                            cleanupFailures.getOrNull(attempt)?.let { throw it }
                        } },
                        snapshot = { throw snapshotFailure },
                    )
                },
            )
            val owner = provisioning.transferToRuntime()

            val thrown = assertThrows(IllegalStateException::class.java) {
                owner.openSession()
            }
            assertSame(snapshotFailure, thrown)
            assertTrue(thrown.suppressed.contains(cleanupFailures.first()))
            assertEquals(2, observerCloseCalls.get())
            assertEquals(failedCloses == 1, owner.isLive())
            assertFalse(capability.factoryClosed.get())

            owner.close()
            assertEquals(failedCloses + 1, observerCloseCalls.get())
            assertTrue(capability.factoryClosed.get())
        }
    }

    @Test
    fun factoryFailureWaitsForConcurrentObserverConstructionBeforeReleasingOwner() {
        val snapshotEntered = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val factoryFailed = CountDownLatch(1)
        val factoryFailure = IllegalStateException("renderer factory open failed")
        val snapshotFailure = IllegalStateException("asynchronous initial snapshot failed")
        val cleanupFailures = List(2) { index ->
            IllegalStateException("observer cleanup failed ${index + 1}")
        }
        val observerCloseCalls = AtomicInteger()
        val subscribeFailure = AtomicReference<Throwable?>()
        val subscriberThread = AtomicReference<Thread>()
        val delegate = RecordingCapability()
        val opens = AtomicInteger()
        val capability = object : UiSyncCompleteRendererCapability {
            override fun acquire(
                context: UiSyncCompleteRendererBindingContext,
            ): UiSyncCompleteRendererFactoryLease {
                val factory = delegate.acquire(context)
                return object : UiSyncCompleteRendererFactoryLease by factory {
                    override fun openSession(
                        context: UiSyncCompleteRendererSessionBindingContext,
                    ): UiSyncCompleteRendererSessionLease {
                        if (opens.getAndIncrement() == 0) return factory.openSession(context)
                        val subscriber = Thread({
                            subscribeFailure.set(runCatching {
                                context.rendererContext.presentationSource.subscribeWithReplay(
                                    DISPLAY_ID, UltraPresentationObserver { },
                                )
                            }.exceptionOrNull())
                        }, "ui-sync-concurrent-initial-snapshot")
                        subscriberThread.set(subscriber)
                        subscriber.start()
                        check(snapshotEntered.await(3, TimeUnit.SECONDS))
                        factoryFailed.countDown()
                        throw factoryFailure
                    }
                }
            }
        }
        val plan = staticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
        )
        val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            uiSyncInfo = AirPlayUiSyncInfo(),
            displays = listOf(displaySpec()),
            staticPlan = plan,
            rendererCapability = capability,
            callbackExecutor = BoundedTestPresentationExecutor(),
            sessionPresentationSourceFactory = {
                presentationSource(
                    observe = { _, _ -> Closeable {
                        val attempt = observerCloseCalls.getAndIncrement()
                        cleanupFailures.getOrNull(attempt)?.let { throw it }
                    } },
                    snapshot = {
                        snapshotEntered.countDown()
                        check(releaseSnapshot.await(3, TimeUnit.SECONDS))
                        throw snapshotFailure
                    },
                )
            },
        )
        val owner = provisioning.transferToRuntime()
        val openFailure = AtomicReference<Throwable?>()
        val opening = Thread({
            openFailure.set(runCatching { owner.openSession() }.exceptionOrNull())
        }, "ui-sync-concurrent-construction")

        opening.start()
        try {
            assertTrue(factoryFailed.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (opening.state != Thread.State.TIMED_WAITING) {
                check(System.nanoTime() < deadline) {
                    "session cleanup did not wait for the constructing subscription"
                }
                Thread.yield()
            }
            assertEquals(0, observerCloseCalls.get())
        } finally {
            releaseSnapshot.countDown()
            opening.join(3_000)
            subscriberThread.get()?.join(3_000)
        }
        assertFalse(opening.isAlive)
        assertFalse(requireNotNull(subscriberThread.get()).isAlive)
        assertSame(factoryFailure, openFailure.get())
        assertSame(snapshotFailure, subscribeFailure.get())
        assertEquals(2, observerCloseCalls.get())
        assertFalse(owner.isLive())
        assertFalse(delegate.factoryClosed.get())

        owner.close()
        assertEquals(3, observerCloseCalls.get())
        assertTrue(delegate.factoryClosed.get())
    }

    @Test
    fun throwingProbeReceiptGetterStillRetainsRetryCleanupOwner() {
        val capability = RecordingCapability(
            sessionReadinessOverride = ReadinessReceiptOverride.THROW_ON_READ,
            probeSessionCloseFailures = 2,
        )
        val failure = assertThrows(UiSyncRendererProvisioningException::class.java) {
            readyProvisioning(capability)
        }
        val probe = capability.sessions.single()
        assertEquals(2, probe.closeCalls.get())
        assertFalse(probe.closed)
        assertFalse(capability.factoryClosed.get())

        failure.close()
        assertEquals(3, probe.closeCalls.get())
        assertTrue(probe.closed)
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun rendererSessionAndFactoryCloseFailuresRetainSameChildrenForRetry() {
        val capability = RecordingCapability(
            runtimeSessionCloseFailures = 1,
            failFactoryCloseOnce = true,
        )
        val runtime = AirPlayUltraRuntime(uiSyncProvisioning = readyProvisioning(capability))
        val session = runtime.openSession()
        val rendererSession = capability.sessions.last()

        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(1, rendererSession.closeCalls.get())
        assertFalse(rendererSession.closed)
        session.close()
        assertEquals(2, rendererSession.closeCalls.get())
        assertTrue(rendererSession.closed)

        assertThrows(IllegalStateException::class.java) { runtime.close() }
        assertEquals(1, capability.factoryCloseCalls.get())
        assertThrows(IllegalStateException::class.java) { runtime.openSession() }
        runtime.close()
        assertEquals(2, capability.factoryCloseCalls.get())
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun closeRacingSessionOpenRetainsRejectedChildWhenItsFirstCloseFails() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val capability = RecordingCapability(
            blockRuntimeOpen = entered to release,
            runtimeSessionCloseFailures = 1,
        )
        val owner = readyProvisioning(capability).transferToRuntime()
        val openFailure = AtomicReference<Throwable?>()
        val opening = Thread {
            try {
                owner.openSession()
            } catch (failure: Throwable) {
                openFailure.set(failure)
            }
        }
        opening.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertThrows(IllegalStateException::class.java) { owner.close() }
        release.countDown()
        opening.join(5_000)
        assertTrue(
            openFailure.get() is IllegalStateException ||
                openFailure.get() is IllegalArgumentException,
        )
        val rejectedChild = capability.sessions.last()
        assertEquals(1, rejectedChild.closeCalls.get())
        assertFalse(rejectedChild.closed)

        owner.close()
        assertEquals(2, rejectedChild.closeCalls.get())
        assertTrue(rejectedChild.closed)
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun rendererChildOpenFailureMakesEngineRcsReadinessFailClosed() {
        val runtime = AirPlayUltraRuntime(
            uiSyncProvisioning = readyProvisioning(
                RecordingCapability(failRuntimeOpen = true),
            ),
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val session = testSession(engine)

        assertFalse(
            engine.isRcsReady(
                session,
                listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
            ),
        )

        engine.close()
        session.close()
    }

    @Test
    fun engineRetainsFailedUiSessionOpenCleanupUntilUltraRuntimeCloseRetriesIt() {
        val capability = RecordingCapability(runtimeSessionCloseFailures = 1)
        val runtime = AirPlayUltraRuntime.forTest(
            sessionRuntimeFactory = com.shilapi.xcertplay.airplay.AirPlayUltraSessionRuntimeFactory {
                    _,
                    _,
                    _,
                    _,
                    _,
                ->
                throw IllegalStateException("synthetic session-runtime construction failure")
            },
            uiSyncProvisioning = readyProvisioning(capability),
        )
        val engine = CarPlayMediaEngine(object : MediaSink {}, ultraRuntime = runtime)
        val session = testSession(engine)

        assertFalse(
            engine.isRcsReady(
                session,
                listOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
            ),
        )
        val failedChild = capability.sessions.last()
        assertEquals(1, failedChild.closeCalls.get())
        assertFalse(failedChild.closed)

        engine.close()
        assertEquals(2, failedChild.closeCalls.get())
        assertTrue(failedChild.closed)
        assertTrue(capability.factoryClosed.get())
        session.close()
    }

    @Test
    fun runtimeConstructionFailureTransfersRetryOwnerWhenOwnerCloseFails() {
        val capability = RecordingCapability(failFactoryCloseOnce = true)
        val provisioning = readyProvisioning(capability)
        val conflictingClusterControl = RcsDataStreamHandlerDescriptor.ready(
            RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL,
            "test.conflicting.cluster-control",
        ) { object : RcsDataStreamHandler {} }

        val failure = assertThrows(AirPlayUltraRuntimeConstructionException::class.java) {
            AirPlayUltraRuntime(
                cafFactory = RcsDataStreamHandlerFactory { clientType ->
                    conflictingClusterControl.takeIf { clientType == it.clientType }
                },
                uiSyncProvisioning = provisioning,
            )
        }
        assertEquals(1, capability.factoryCloseCalls.get())
        assertFalse(capability.factoryClosed.get())

        failure.close()
        assertEquals(2, capability.factoryCloseCalls.get())
        assertTrue(capability.factoryClosed.get())
    }

    @Test
    fun staticPlanCardinalitiesAreIndependentAndUiSyncCoverageIsExact() {
        val plan = staticPlan(
            outputs = listOf(
                output("cluster", DISPLAY_ID, 1),
                output("hud-local", null, 2),
                output("passenger-local", null, 3),
            ),
            ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
        )
        val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            AirPlayUiSyncInfo(),
            listOf(displaySpec()),
            plan,
            RecordingCapability(),
            BoundedTestPresentationExecutor(),
        )
        assertEquals(3, provisioning.staticPlan?.outputs?.size)
        assertEquals(1, provisioning.staticPlan?.ingressSpecs?.size)
        provisioning.close()

        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRuntimeProvisioning.fromRendererCapability(
                AirPlayUiSyncInfo(),
                listOf(displaySpec("uncovered-display")),
                plan,
                RecordingCapability(),
                BoundedTestPresentationExecutor(),
            )
        }
    }

    @Test
    fun displaySnapshotDoesNotTrustCallerCollectionSize() {
        val hostileDisplays = object : AbstractCollection<UiSyncDisplayRuntimeSpec>() {
            override val size: Int
                get() = throw AssertionError("hostile Collection.size must not be read")

            override fun iterator(): Iterator<UiSyncDisplayRuntimeSpec> =
                listOf(displaySpec()).iterator()
        }
        val provisioning = UiSyncRuntimeProvisioning.fromRendererCapability(
            AirPlayUiSyncInfo(),
            hostileDisplays,
            staticPlan(
                outputs = listOf(output("cluster", DISPLAY_ID, 1)),
                ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
            ),
            RecordingCapability(),
            BoundedTestPresentationExecutor(),
        )

        provisioning.close()
    }

    @Test
    fun displaySnapshotRejectsMoreThanSixtyFourWithoutReadingCollectionSize() {
        val hostileDisplays = object : AbstractCollection<UiSyncDisplayRuntimeSpec>() {
            override val size: Int
                get() = throw AssertionError("hostile Collection.size must not be read")

            override fun iterator(): Iterator<UiSyncDisplayRuntimeSpec> =
                List(65) { index -> displaySpec("display-$index") }.iterator()
        }

        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRuntimeProvisioning.fromRendererCapability(
                AirPlayUiSyncInfo(),
                hostileDisplays,
                staticPlan(
                    outputs = listOf(output("cluster", DISPLAY_ID, 1)),
                    ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
                ),
                RecordingCapability(),
                BoundedTestPresentationExecutor(),
            )
        }
    }

    @Test
    fun staticPlanSnapshotsDoNotReadCallerCollectionSize() {
        val hostileOutputs = object : AbstractCollection<UltraDisplayGraphOutput>() {
            override val size: Int
                get() = throw AssertionError("renderer output Collection.size must not be read")

            override fun iterator(): Iterator<UltraDisplayGraphOutput> =
                listOf(output("cluster", DISPLAY_ID, 1)).iterator()
        }
        val hostileIngresses = object : AbstractCollection<UiSyncRendererIngressSpec>() {
            override val size: Int
                get() = throw AssertionError("renderer ingress Collection.size must not be read")

            override fun iterator(): Iterator<UiSyncRendererIngressSpec> =
                listOf(ingressSpec(STREAM_CONNECTION_ID)).iterator()
        }

        val plan = staticPlan(hostileOutputs, hostileIngresses)

        assertEquals(1, plan.outputs.size)
        assertEquals(1, plan.ingressSpecs.size)
    }

    @Test
    fun rendererBindingContextsBoundDisplayIdsWithoutReadingCollectionSize() {
        val displayIds = object : AbstractCollection<String>() {
            override val size: Int
                get() = throw AssertionError("binding display Collection.size must not be read")

            override fun iterator(): Iterator<String> = listOf(DISPLAY_ID).iterator()
        }
        val plan = staticPlan(
            outputs = listOf(output("cluster", DISPLAY_ID, 1)),
            ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
        )
        val schema = UiSyncSchemaFingerprint(ByteArray(32) { 1 })
        val binding = UiSyncBindingFingerprint(ByteArray(32) { 2 })
        val source = object : UiSyncPresentationSource {
            override fun subscribeWithReplay(
                displayId: String,
                observer: UltraPresentationObserver,
            ): UiSyncPresentationSubscription = throw UnsupportedOperationException()

            override fun snapshot(displayId: String): UltraPresentationSnapshot =
                throw UnsupportedOperationException()
        }

        val factoryContext = UiSyncRendererBindingContext(plan, schema, binding, displayIds)
        val sessionContext = UiSyncRendererSessionBindingContext(
            source,
            plan,
            schema,
            binding,
            UiSyncForegroundRequestOutbox(listOf(DISPLAY_ID)),
            displayIds,
        )

        assertEquals(setOf(DISPLAY_ID), factoryContext.displayIds)
        assertEquals(setOf(DISPLAY_ID), sessionContext.displayIds)
        assertThrows(IllegalArgumentException::class.java) {
            UiSyncRendererBindingContext(
                plan,
                schema,
                binding,
                listOf(DISPLAY_ID, DISPLAY_ID),
            )
        }
    }

    private fun readyProvisioning(
        capability: UiSyncCompleteRendererCapability,
        physicalAuthority: UiSyncPhysicalOutputAuthority? = TestPhysicalAuthority(),
        callbackExecutor: UiSyncBoundedPresentationExecutor = BoundedTestPresentationExecutor(),
        readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)? = null,
        readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)? = null,
    ): UiSyncRuntimeProvisioning = authorizedReadyProvisioning(
        uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "v2")),
        displays = listOf(displaySpec()),
        staticPlan = staticPlan(
            outputs = listOf(
                output("cluster", DISPLAY_ID, 1),
                output("local-only", null, 2),
            ),
            ingressSpecs = listOf(ingressSpec(STREAM_CONNECTION_ID)),
        ),
        rendererCapability = capability,
        callbackExecutor = callbackExecutor,
        physicalAuthority = physicalAuthority,
        readOnlyPresentationBinding = readOnlyPresentationBinding,
        readOnlySessionBinding = readOnlySessionBinding,
    )

    private fun mainClusterOwner(
        authority: TestPhysicalAuthority,
        capability: UiSyncCompleteRendererCapability = RecordingCapability(),
    ): UiSyncRuntimeProvisioningOwner {
        val mainId = AirPlayInfoPlist.MAIN_UUID
        val clusterId = AirPlayInfoPlist.ALT_UUID
        val main = output("main", mainId, 1)
        val cluster = output("external-cluster", clusterId, 2)
        val plan = staticPlan(listOf(main, cluster), emptyList())
        return UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            uiSyncInfo = AirPlayUiSyncInfo(mapOf("renderer" to "v2")),
            displays = listOf(displaySpec(mainId), displaySpec(clusterId)),
            staticPlan = plan,
            rendererCapability = capability,
            callbackExecutor = BoundedTestPresentationExecutor(parallelism = 2),
            authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
                PROGRAM_FINGERPRINT, "test.renderer.v2",
                UiSyncPhysicalOutputRequirement(
                    UltraProfileDigest(ByteArray(32) { 0x5a }),
                    listOf(
                        UiSyncPhysicalOutputAssignment(main,
                            UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.MAIN)),
                        UiSyncPhysicalOutputAssignment(cluster,
                            UiSyncPhysicalDestination(UiSyncPhysicalDestinationRole.CLUSTER)),
                    ),
                ),
            ),
            physicalOutputAuthority = authority,
        ).transferToRuntime()
    }

    private fun authorizedReadyProvisioning(
        uiSyncInfo: AirPlayUiSyncInfo,
        displays: Collection<UiSyncDisplayRuntimeSpec>,
        staticPlan: UiSyncRendererStaticPlan,
        rendererCapability: UiSyncCompleteRendererCapability,
        callbackExecutor: UiSyncBoundedPresentationExecutor,
        physicalAuthority: UiSyncPhysicalOutputAuthority? = TestPhysicalAuthority(),
        readOnlyPresentationBinding: ((UiSyncPresentationSource) -> Closeable?)? = null,
        readOnlySessionBinding: ((UiSyncReadOnlySessionContext) -> Closeable?)? = null,
    ): UiSyncRuntimeProvisioning = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
        uiSyncInfo = uiSyncInfo,
        displays = displays,
        staticPlan = staticPlan,
        rendererCapability = rendererCapability,
        callbackExecutor = callbackExecutor,
        authorizationExpectation = UiSyncRuntimeAuthorizationExpectation(
            semanticThemeProgramFingerprint = PROGRAM_FINGERPRINT,
            rendererImplementationId = "test.renderer.v2",
            physicalOutputRequirement = physicalRequirement(staticPlan),
        ),
        physicalOutputAuthority = physicalAuthority,
        readOnlyPresentationBinding = readOnlyPresentationBinding,
        readOnlySessionBinding = readOnlySessionBinding,
    )

    private fun physicalRequirement(plan: UiSyncRendererStaticPlan) =
        UiSyncPhysicalOutputRequirement(
            verifiedProfileDigest = UltraProfileDigest(ByteArray(32) { 0x5a }),
            assignments = plan.outputs.mapIndexed { index, graphOutput ->
                UiSyncPhysicalOutputAssignment(
                    graphOutput,
                    UiSyncPhysicalDestination(
                        UiSyncPhysicalDestinationRole.AUXILIARY,
                        "test-physical-$index",
                    ),
                )
            },
        )

    private class TestPhysicalAuthority(
        private val mode: Mode = Mode.NORMAL,
        private val captureActivations: Boolean = false,
        private val onActivate: (() -> Unit)? = null,
        coverFailures: Int = 0,
        coverThrows: Int = 0,
        clusterCoverFailures: Int = 0,
        private val clusterBeginFails: Boolean = false,
        private val blockFirstClusterCover: Pair<CountDownLatch, CountDownLatch>? = null,
    ) : UiSyncPhysicalOutputAuthority {
        enum class Mode {
            NORMAL,
            MISSING_BATCH,
            STALE_BATCH,
            REPLAY_FIRST_BATCH,
            CLOSE_RENDERER_DURING_BIND,
            PENDING_INPUT_STREAM,
            PENDING_LOCAL_CANVAS,
            ACTIVATION_THROWS,
        }
        private val generation = AtomicInteger()
        private val backendGenerationOffset = AtomicInteger()
        private val backendCurrent = AtomicBoolean(true)
        private val backendBusy = AtomicBoolean(false)
        private val hostCurrent = AtomicBoolean(true)
        val backendCloses = AtomicInteger()
        val backendClosedWhileRendererLive = AtomicBoolean(false)
        val activationCalls = AtomicInteger()
        val coverCalls = AtomicInteger()
        val stopPermitsRevoked = AtomicBoolean()
        val clusterPhase1Revoked = AtomicBoolean()
        val clusterCoverCalls = AtomicInteger()
        val clusterCoverAcks = AtomicInteger()
        val clusterCoverDisplayIds = Collections.synchronizedList(mutableListOf<String>())
        private val clusterEpoch = AtomicInteger()
        val coveredRoles = Collections.synchronizedList(
            mutableListOf<Set<UiSyncPhysicalDestinationRole>>(),
        )
        private val coverFailuresRemaining = AtomicInteger(coverFailures)
        private val coverThrowsRemaining = AtomicInteger(coverThrows)
        private val clusterCoverFailuresRemaining = AtomicInteger(clusterCoverFailures)
        private val batchesByContext =
            WeakHashMap<UiSyncPhysicalOutputSessionContext, WeakReference<UiSyncPhysicalOutputBatch>>()
        val boundContexts = Collections.synchronizedList(mutableListOf<UiSyncPhysicalOutputSessionContext>())
        val boundBatches = Collections.synchronizedList(mutableListOf<UiSyncPhysicalOutputBatch>())
        val activationContexts = Collections.synchronizedList(mutableListOf<UiSyncPhysicalOutputSessionContext>())
        val activatedBatches = Collections.synchronizedList(mutableListOf<UiSyncPhysicalOutputBatch>())
        private val rendererReferences = Collections.synchronizedList(
            mutableListOf<WeakReference<UiSyncCompleteRendererSessionLease>>(),
        )
        val rendererSessions: List<UiSyncCompleteRendererSessionLease>
            get() = synchronized(rendererReferences) {
                rendererReferences.mapNotNull { it.get() }
            }
        private var firstBatch: UiSyncPhysicalOutputBatch? = null

        fun revokeHostTargets() { hostCurrent.set(false) }
        fun revokeBackend() { backendCurrent.set(false) }
        fun busyBackend() { backendBusy.set(true) }
        fun resumeBackend() { backendBusy.set(false) }
        fun bumpBackendGeneration() { backendGenerationOffset.incrementAndGet() }

        override fun preflight(context: UiSyncPhysicalOutputPreflightContext):
            UiSyncPhysicalOutputAvailability = context.issueAvailability(
                routes = routes(context.requirement, 0),
                currentProbe = { hostCurrent.get() },
            )

        override fun bindSession(context: UiSyncPhysicalOutputSessionContext):
            UiSyncPhysicalOutputBatch? {
            rendererReferences += WeakReference(context.rendererSession)
            if (mode == Mode.MISSING_BATCH) return null
            if (mode == Mode.REPLAY_FIRST_BATCH) firstBatch?.let { return it }
            val currentGeneration = generation.incrementAndGet().toLong()
            val backend = object : UiSyncPhysicalOutputBackendLease {
                private val closed = AtomicBoolean(false)
                override val generation: Long
                    get() = currentGeneration + backendGenerationOffset.get()
                override val isCurrent: Boolean
                    get() = backendCurrent.get() && !backendBusy.get() && !closed.get()
                override val generationStatus: UiSyncPhysicalGenerationStatus
                    get() = when {
                        !backendCurrent.get() || closed.get() -> UiSyncPhysicalGenerationStatus.REVOKED
                        backendBusy.get() -> UiSyncPhysicalGenerationStatus.BUSY
                        else -> UiSyncPhysicalGenerationStatus.CURRENT
                    }
                override fun close() {
                    if (context.rendererSession.isLive) {
                        backendClosedWhileRendererLive.set(true)
                    }
                    if (closed.compareAndSet(false, true)) backendCloses.incrementAndGet()
                }
            }
            // Test sessions model distinct physical benches. Product Host borrowing never
            // permits two current leases for one real Surface/version at the same time.
            val currentRoutes = routes(context.requirement, currentGeneration)
            val targets = currentRoutes.map { route ->
                object : UiSyncPhysicalTargetLease {
                    override val version = route.target
                    override val isCurrent: Boolean get() = hostCurrent.get()
                }
            }
            val issued = context.issueActiveBatch(backend, currentRoutes, targets)
            synchronized(batchesByContext) { batchesByContext[context] = WeakReference(issued) }
            if (mode == Mode.STALE_BATCH) hostCurrent.set(false)
            if (mode == Mode.REPLAY_FIRST_BATCH) firstBatch = issued
            if (mode == Mode.CLOSE_RENDERER_DURING_BIND) context.rendererSession.close()
            if (captureActivations) {
                boundContexts += context
                boundBatches += issued
            }
            return issued
        }

        override fun revokeBoundSessionForStop(
            context: UiSyncPhysicalOutputSessionContext,
            batch: UiSyncPhysicalOutputBatch,
        ): Boolean {
            val exact = context.issued(batch) &&
                synchronized(batchesByContext) { batchesByContext[context]?.get() === batch }
            if (exact) stopPermitsRevoked.set(true)
            return exact
        }

        override fun beginClusterPhoneEpochCover(
            context: UiSyncPhysicalOutputSessionContext,
            batch: UiSyncPhysicalOutputBatch,
            displayId: String,
        ): UiSyncClusterPhoneEpochCoverTicket? {
            if (clusterBeginFails || !context.issued(batch) ||
                synchronized(batchesByContext) { batchesByContext[context]?.get() !== batch } ||
                batch.routes.count { route ->
                    route.assignment.destination.role == UiSyncPhysicalDestinationRole.CLUSTER &&
                        route.assignment.output.uiSyncDisplayId?.value == displayId
                } != 1
            ) return null
            val epoch = clusterEpoch.incrementAndGet()
            clusterPhase1Revoked.set(true)
            clusterCoverDisplayIds += displayId
            return object : UiSyncClusterPhoneEpochCoverTicket {
                override fun coverExactClusterUi(): UiSyncClusterPhoneEpochCoverResult {
                    val attempt = clusterCoverCalls.incrementAndGet()
                    if (attempt == 1) blockFirstClusterCover?.let { (entered, release) ->
                        entered.countDown()
                        check(release.await(3L, TimeUnit.SECONDS))
                    }
                    if (clusterEpoch.get() != epoch)
                        return UiSyncClusterPhoneEpochCoverResult.SUPERSEDED
                    if (!batch.isCurrent) return UiSyncClusterPhoneEpochCoverResult.REVOKED
                    if (clusterCoverFailuresRemaining.get() > 0) {
                        clusterCoverFailuresRemaining.decrementAndGet()
                        return UiSyncClusterPhoneEpochCoverResult.RETRY
                    }
                    clusterCoverAcks.incrementAndGet()
                    return UiSyncClusterPhoneEpochCoverResult.ACKNOWLEDGED
                }
            }
        }

        override fun coverBoundSessionForClose(
            context: UiSyncPhysicalOutputSessionContext,
            batch: UiSyncPhysicalOutputBatch,
        ): Boolean {
            coverCalls.incrementAndGet()
            coveredRoles += batch.routes.mapTo(linkedSetOf()) {
                it.assignment.destination.role
            }
            if (!context.issued(batch) ||
                synchronized(batchesByContext) { batchesByContext[context]?.get() !== batch }
            ) return false
            if (coverFailuresRemaining.get() > 0) {
                coverFailuresRemaining.decrementAndGet()
                return false
            }
            if (coverThrowsRemaining.get() > 0) {
                coverThrowsRemaining.decrementAndGet()
                throw IllegalStateException("Host UI cover dispatch failed")
            }
            return true
        }

        override fun activateBoundSession(
            context: UiSyncPhysicalOutputSessionContext,
            batch: UiSyncPhysicalOutputBatch,
        ): UiSyncPhysicalActivationResult {
            activationCalls.incrementAndGet()
            if (captureActivations) {
                activationContexts += context
                activatedBatches += batch
            }
            check(context.issued(batch) && batch.isCurrent) {
                "post-bind activation did not receive the exact live batch"
            }
            onActivate?.invoke()
            if (mode == Mode.ACTIVATION_THROWS) {
                throw IllegalStateException("post-bind activation failed")
            }
            return when (mode) {
                Mode.PENDING_INPUT_STREAM -> UiSyncPhysicalActivationResult.PENDING_INPUT_STREAM
                Mode.PENDING_LOCAL_CANVAS -> UiSyncPhysicalActivationResult.PENDING_LOCAL_CANVAS
                else -> UiSyncPhysicalActivationResult.SUBMITTED
            }
        }

        private fun routes(
            requirement: UiSyncPhysicalOutputRequirement,
            sessionGeneration: Long,
        ):
            List<UiSyncPhysicalOutputRoute> = requirement.assignments.mapIndexed {
                index, assignment ->
                UiSyncPhysicalOutputRoute(
                    assignment,
                    UiSyncPhysicalTargetVersion(
                        "test-host-target-$index-session-$sessionGeneration",
                        sessionGeneration + 1,
                    ),
                )
            }
    }

    private fun displaySpec(displayId: String = DISPLAY_ID) = UiSyncDisplayRuntimeSpec(
        descriptor = CarPlayUiSyncChannelDescriptor(displayId, "test-vehicle"),
        mode = CarPlayUiSyncMode.PASSIVE,
        outboundResetConfig = mapOf("appearance" to "day"),
    )

    private fun staticPlan(
        outputs: Collection<UltraDisplayGraphOutput>,
        ingressSpecs: Collection<UiSyncRendererIngressSpec>,
    ) = UiSyncRendererStaticPlan(outputs, ingressSpecs)

    private fun output(
        outputId: String,
        displayId: String?,
        uuidTail: Int,
    ) = UltraDisplayGraphOutput(
        topologyDisplayId = UltraTopologyDisplayId(
            UUID.fromString("00000000-0000-0000-0000-%012d".format(uuidTail)),
        ),
        outputId = UltraDisplayOutputId.Custom(outputId),
        uiSyncDisplayId = displayId?.let(::UltraUiSyncDisplayId),
    )

    private fun ingressSpec(connectionId: String) = UiSyncRendererIngressSpec(
        UltraIngressId("cluster-video-$connectionId"),
        UltraAirPlayStreamIdentity(111, connectionId, null),
    )

    private fun exactHandle() = AirPlayVideoStreamHandle(
        AirPlayStreamIdentity(111, STREAM_CONNECTION_ID, null),
        AirPlayVideoSessionEpoch(),
    )

    private fun openCloseAndForgetRendererSession(
        owner: UiSyncRuntimeProvisioningOwner,
        capability: RecordingCapability,
    ): WeakReference<UiSyncCompleteRendererSessionLease> {
        val session = owner.openSession()
        val renderer = capability.sessions.last()
        session.close()
        capability.sessions.remove(renderer)
        return WeakReference(renderer)
    }

    private fun legacyBinding() = UltraDisplayBinding(
        uiSyncDisplayId = UltraUiSyncDisplayId(DISPLAY_ID),
        topologyDisplayId = UltraTopologyDisplayId(
            UUID.fromString("00000000-0000-0000-0000-000000000111"),
        ),
        airPlayStreamIdentity = UltraAirPlayStreamIdentity(111, STREAM_CONNECTION_ID, null),
        outputId = UltraDisplayOutputId.Custom("legacy-output"),
    )

    private fun legacyResources(binding: UltraDisplayBinding) = UltraDisplayBindingResources(
        listOf(binding.uiSyncDisplayId),
        listOf(binding.topologyDisplayId),
        listOf(binding.airPlayStreamIdentity),
        listOf(binding.outputId),
    )

    private fun readyRouter(router: CarPlayUiSyncRouter) {
        val peer = CarPlayUiSyncPhoneRoleSimulator(DISPLAY_ID)
        assertTrue(router.open().outboundCarriers.isEmpty())
        peer.handshake { router.receive(it).outboundCarriers }
        PEERS[router] = peer
    }

    private fun publishRequestLayout(router: CarPlayUiSyncRouter, layoutId: String) {
        val peer = requireNotNull(PEERS[router])
        val carrier = peer.sendCommand(
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            mapOf("layoutID" to layoutId),
        )
        val received = router.receive(carrier)
        received.outboundCarriers.forEach { peer.receive(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun eventLayoutId(
        snapshot: com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot,
    ): String? = (snapshot.events.single().data as Map<String, Any?>)["layoutID"] as? String

    private fun config(ultra: com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration) =
        com.shilapi.xcertplay.airplay.AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(800, 240),
            ultra = ultra,
        )

    private fun testSession(engine: CarPlayMediaEngine): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(800, 240),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = engine,
    )

    private enum class ReceiptOverride {
        NONE,
        SCHEMA,
        BINDING,
        DISPLAYS,
        HOSTILE_DISPLAY_SIZE,
    }
    private enum class ReadinessReceiptOverride {
        NONE,
        MISSING,
        OUTPUTS,
        SUBSCRIPTIONS,
        SEMANTIC_INACTIVE,
        THEME_INACTIVE,
        BACKEND_DEAD,
        PROGRAM_CHANGED,
        THROW_ON_READ,
        DUPLICATE_OUTPUTS,
        TOO_MANY_SUBSCRIPTIONS,
        HOSTILE_COLLECTION_SIZE,
        STALE_PROBE,
        CHANGE_AFTER_FIRST_READ,
    }
    private enum class CandidateReceiptOverride { NONE, STREAM, INGRESS }

    private class ReusedFactoryCapability : UiSyncCompleteRendererCapability {
        private val delegate = RecordingCapability()
        private var sharedFactory: UiSyncCompleteRendererFactoryLease? = null
        val factoryCloseCalls: AtomicInteger
            get() = delegate.factoryCloseCalls

        override fun acquire(
            context: UiSyncCompleteRendererBindingContext,
        ): UiSyncCompleteRendererFactoryLease = synchronized(this) {
            sharedFactory ?: delegate.acquire(context).also { sharedFactory = it }
        }
    }

    private class RecordingCapability(
        private val factoryReceiptOverride: ReceiptOverride = ReceiptOverride.NONE,
        private val sessionReceiptOverride: ReceiptOverride = ReceiptOverride.NONE,
        private val blockPrepare: Pair<CountDownLatch, CountDownLatch>? = null,
        private val blockPublish: Pair<CountDownLatch, CountDownLatch>? = null,
        private val blockRuntimeOpen: Pair<CountDownLatch, CountDownLatch>? = null,
        private val runtimeSessionCloseFailures: Int = 0,
        private val failFactoryCloseOnce: Boolean = false,
        private val failCandidateCloseOnce: Boolean = false,
        private val failRuntimeOpen: Boolean = false,
        private val probeSessionCloseFailures: Int = 0,
        private val factoryReadinessOverride: ReadinessReceiptOverride =
            ReadinessReceiptOverride.NONE,
        private val sessionReadinessOverride: ReadinessReceiptOverride =
            ReadinessReceiptOverride.NONE,
        private val sharedSessionForEveryOpen: SharedCompleteSession? = null,
        private val sharedSessionForRuntimeOpens: SharedCompleteSession? = null,
        private val reuseClosedRuntimeSession: Boolean = false,
        private val candidateReceiptOverride: CandidateReceiptOverride =
            CandidateReceiptOverride.NONE,
        private val actualSubscriptionDisplayIds: Set<String>? = null,
        private val onRuntimePresentationSnapshot: ((UltraPresentationSnapshot) -> Unit)? = null,
        private val omitPhoneEpochObserverForRuntime: Boolean = false,
        private val onPhoneEpochInvalidation: ((String) -> Unit)? = null,
    ) : UiSyncCompleteRendererCapability {
        val sessions = Collections.synchronizedList(mutableListOf<RecordingSession>())
        val factoryCloseCalls = AtomicInteger()
        val factoryClosed = AtomicBoolean(false)
        private val factoryReceiptInvalidated = AtomicBoolean(false)
        var acquireCount = 0
            private set

        fun invalidateFactoryReceipt() {
            factoryReceiptInvalidated.set(true)
        }

        override fun acquire(
            context: UiSyncCompleteRendererBindingContext,
        ): UiSyncCompleteRendererFactoryLease {
            acquireCount += 1
            val factoryReceipt = readinessReceipt(context, factoryReadinessOverride)
            val invalidatedFactoryReceipt = readinessReceipt(
                context,
                ReadinessReceiptOverride.BACKEND_DEAD,
            )
            val sessionOpenCalls = AtomicInteger()
            return object : UiSyncCompleteRendererFactoryLease {
                override val isLive: Boolean
                    get() = !factoryClosed.get()
                override val schemaFingerprint = overrideSchema(
                    context.schemaFingerprint,
                    factoryReceiptOverride,
                )
                override val bindingFingerprint = overrideBinding(
                    context.bindingFingerprint,
                    factoryReceiptOverride,
                )
                override val displayIds = overrideDisplays(
                    context.displayIds,
                    factoryReceiptOverride,
                )
                override val implementationId = "test.renderer.v2"
                override val readinessReceipt: RendererReadinessReceipt?
                    get() = if (factoryReceiptInvalidated.get()) {
                        invalidatedFactoryReceipt
                    } else {
                        factoryReceipt
                    }

                override fun openSession(
                    context: UiSyncCompleteRendererSessionBindingContext,
                ): UiSyncCompleteRendererSessionLease {
                    val runtimeIndex = sessionOpenCalls.getAndIncrement()
                    if (runtimeIndex > 0) {
                        blockRuntimeOpen?.first?.countDown()
                        blockRuntimeOpen?.let {
                            assertTrue(it.second.await(5, TimeUnit.SECONDS))
                        }
                        if (failRuntimeOpen) throw IllegalStateException("runtime child unavailable")
                    }
                    if (reuseClosedRuntimeSession && runtimeIndex > 1) return sessions[1]
                    val shared = sharedSessionForEveryOpen
                        ?: sharedSessionForRuntimeOpens?.takeIf { runtimeIndex > 0 }
                    if (shared != null) {
                        shared.bindIfAbsent(context, implementationId)
                        return shared
                    }
                    val staleReceipt = if (
                        runtimeIndex > 0 &&
                        sessionReadinessOverride == ReadinessReceiptOverride.STALE_PROBE
                    ) {
                        sessions.first().readinessReceipt
                    } else {
                        null
                    }
                    val opened = RecordingSession(
                        completeContext = context,
                        implementationId = implementationId,
                        receiptOverride = sessionReceiptOverride,
                        blockPrepare = blockPrepare,
                        blockPublish = blockPublish,
                        closeFailures = if (runtimeIndex == 0) {
                            probeSessionCloseFailures
                        } else {
                            runtimeSessionCloseFailures
                        },
                        failCandidateCloseOnce = failCandidateCloseOnce,
                        candidateReceiptOverride = candidateReceiptOverride,
                        actualSubscriptionDisplayIds = actualSubscriptionDisplayIds,
                        observerCallback = onRuntimePresentationSnapshot.takeIf {
                            runtimeIndex > 0
                        },
                        onPhoneEpochInvalidation = onPhoneEpochInvalidation.takeIf {
                            runtimeIndex > 0
                        },
                        readinessOverride = sessionReadinessOverride,
                        forcedReadinessReceipt = staleReceipt,
                        changeReceiptAfterFirstRead =
                            runtimeIndex > 0 &&
                                sessionReadinessOverride ==
                                ReadinessReceiptOverride.CHANGE_AFTER_FIRST_READ,
                    ).also(sessions::add)
                    return if (omitPhoneEpochObserverForRuntime && runtimeIndex > 0) {
                        object : UiSyncCompleteRendererSessionLease by opened {}
                    } else opened
                }

                override fun close() {
                    val call = factoryCloseCalls.incrementAndGet()
                    if (failFactoryCloseOnce && call == 1) {
                        throw IllegalStateException("factory close retry")
                    }
                    factoryClosed.set(true)
                }
            }
        }
    }

    private class RecordingSession(
        val completeContext: UiSyncCompleteRendererSessionBindingContext,
        override val implementationId: String,
        receiptOverride: ReceiptOverride,
        private val blockPrepare: Pair<CountDownLatch, CountDownLatch>?,
        private val blockPublish: Pair<CountDownLatch, CountDownLatch>?,
        private val closeFailures: Int,
        private val failCandidateCloseOnce: Boolean,
        private val candidateReceiptOverride: CandidateReceiptOverride,
        actualSubscriptionDisplayIds: Set<String>?,
        private val observerCallback: ((UltraPresentationSnapshot) -> Unit)?,
        private val onPhoneEpochInvalidation: ((String) -> Unit)?,
        readinessOverride: ReadinessReceiptOverride,
        private val forcedReadinessReceipt: RendererReadinessReceipt?,
        private val changeReceiptAfterFirstRead: Boolean,
    ) : UiSyncCompleteRendererSessionLease, UiSyncPhoneEpochObserver {
        val context: UiSyncRendererSessionBindingContext = completeContext.rendererContext
        val candidates = Collections.synchronizedList(mutableListOf<RecordingCandidate>())
        val prepareCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val retireScreenCalls = AtomicInteger()
        private val readinessReads = AtomicInteger()
        private val readinessInvalidated = AtomicBoolean(false)
        private val throwOnRead = readinessOverride == ReadinessReceiptOverride.THROW_ON_READ
        private val issuedReadinessReceipt = readinessReceipt(completeContext, readinessOverride)
        private val subscriptions: List<Closeable> =
            (actualSubscriptionDisplayIds ?: context.displayIds).map { displayId ->
            context.presentationSource.subscribeWithReplay(
                displayId,
                UltraPresentationObserver { snapshot -> observerCallback?.invoke(snapshot) },
            )
            }
        private val changedReadinessReceipt = readinessReceipt(
            completeContext,
            ReadinessReceiptOverride.NONE,
            PROGRAM_FINGERPRINT,
        )
        @Volatile var closed = false
            private set
        override val isLive: Boolean
            get() = !closed
        override val schemaFingerprint = overrideSchema(context.schemaFingerprint, receiptOverride)
        override val bindingFingerprint = overrideBinding(context.bindingFingerprint, receiptOverride)
        override val displayIds = overrideDisplays(context.displayIds, receiptOverride)
        override val readinessReceipt: RendererReadinessReceipt?
            get() {
                if (throwOnRead) throw IllegalStateException("receipt getter failed")
                if (forcedReadinessReceipt != null) return forcedReadinessReceipt
                return if (
                    readinessInvalidated.get() ||
                    (changeReceiptAfterFirstRead && readinessReads.incrementAndGet() > 1)
                ) {
                    changedReadinessReceipt
                } else {
                    issuedReadinessReceipt
                }
            }

        override fun invalidatePhoneEpoch(
            displayId: String,
            presentationRevisionFloor: Long,
        ): Boolean {
            onPhoneEpochInvalidation?.invoke(displayId)
            return !closed && displayId in displayIds && presentationRevisionFloor >= 0L
        }

        fun invalidateReadinessReceipt() {
            readinessInvalidated.set(true)
        }

        override fun prepareScreen(
            context: UiSyncRendererScreenContext,
        ): UiSyncRendererScreenCandidate {
            prepareCalls.incrementAndGet()
            blockPrepare?.first?.countDown()
            blockPrepare?.let { assertTrue(it.second.await(5, TimeUnit.SECONDS)) }
            return RecordingCandidate(
                context.stream,
                context.ingressId,
                failCandidateCloseOnce,
                candidateReceiptOverride,
                blockPublish,
            ).also(candidates::add)
        }

        override fun retireScreens(): Boolean {
            retireScreenCalls.incrementAndGet()
            return true
        }

        override fun close() {
            val call = closeCalls.incrementAndGet()
            if (call <= closeFailures) throw IllegalStateException("session close retry")
            subscriptions.forEach(Closeable::close)
            closed = true
        }
    }

    /** Deliberately malicious lease which stays live and may be returned more than once. */
    private class SharedCompleteSession(
        private val blockFirstReceiptRead: Pair<CountDownLatch, CountDownLatch>? = null,
    ) : UiSyncCompleteRendererSessionLease, UiSyncPhoneEpochObserver {
        override fun invalidatePhoneEpoch(
            displayId: String,
            presentationRevisionFloor: Long,
        ): Boolean = displayId in displayIds && presentationRevisionFloor >= 0L
        private val lock = Any()
        private val receiptReadStarted = AtomicBoolean(false)
        private var context: UiSyncCompleteRendererSessionBindingContext? = null
        private var receipt: RendererReadinessReceipt? = null
        private var implementation: String? = null
        val closeCalls = AtomicInteger()

        fun bindIfAbsent(
            context: UiSyncCompleteRendererSessionBindingContext,
            implementationId: String,
        ) {
            synchronized(lock) {
                if (this.context != null) return
                this.context = context
                implementation = implementationId
                receipt = readinessReceipt(context, ReadinessReceiptOverride.NONE)
            }
        }

        private fun boundContext(): UiSyncCompleteRendererSessionBindingContext =
            synchronized(lock) { checkNotNull(context) }

        override val isLive: Boolean
            get() = true
        override val schemaFingerprint: UiSyncSchemaFingerprint
            get() = boundContext().schemaFingerprint
        override val bindingFingerprint: UiSyncBindingFingerprint
            get() = boundContext().bindingFingerprint
        override val displayIds: Set<String>
            get() = boundContext().displayIds
        override val implementationId: String
            get() = synchronized(lock) { checkNotNull(implementation) }
        override val readinessReceipt: RendererReadinessReceipt?
            get() {
                blockFirstReceiptRead?.let { (entered, release) ->
                    if (receiptReadStarted.compareAndSet(false, true)) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
                return synchronized(lock) { receipt }
            }

        override fun prepareScreen(
            context: UiSyncRendererScreenContext,
        ): UiSyncRendererScreenCandidate? = null

        override fun retireScreens(): Boolean = true

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class RecordingCandidate(
        expectedStream: AirPlayVideoStreamHandle,
        expectedIngressId: UltraIngressId,
        private val failFirstClose: Boolean,
        receiptOverride: CandidateReceiptOverride,
        private val blockPublish: Pair<CountDownLatch, CountDownLatch>?,
    ) : UiSyncRendererScreenCandidate {
        override val stream = if (receiptOverride == CandidateReceiptOverride.STREAM) {
            AirPlayVideoStreamHandle(expectedStream.identity, AirPlayVideoSessionEpoch())
        } else {
            expectedStream
        }
        override val ingressId = if (receiptOverride == CandidateReceiptOverride.INGRESS) {
            UltraIngressId("wrong-ingress")
        } else {
            expectedIngressId
        }
        val publishCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        override fun publish(): Boolean {
            publishCalls.incrementAndGet()
            blockPublish?.first?.countDown()
            blockPublish?.let { assertTrue(it.second.await(5, TimeUnit.SECONDS)) }
            return true
        }

        override fun close() {
            val call = closeCalls.incrementAndGet()
            if (failFirstClose && call == 1) throw IllegalStateException("candidate close retry")
        }
    }

    private class BoundedTestPresentationExecutor(
        override val parallelism: Int = 1,
    ) : UiSyncBoundedPresentationExecutor {
        private val pending = ArrayDeque<Runnable>()
        override val maximumPendingCallbacks = 64
        override val mayExecuteInline = false
        override fun execute(command: Runnable) {
            synchronized(pending) {
                if (pending.size >= maximumPendingCallbacks) {
                    throw RejectedExecutionException("test callback queue is full")
                }
                pending.addLast(command)
            }
        }

        fun drain() {
            while (true) {
                val next = synchronized(pending) {
                    if (pending.isEmpty()) null else pending.removeFirst()
                } ?: return
                next.run()
            }
        }
    }

    private class ThreadedTestPresentationExecutor : UiSyncBoundedPresentationExecutor {
        override val maximumPendingCallbacks = 64
        override val parallelism = 64
        override val mayExecuteInline = false
        override fun execute(command: Runnable) { Thread(command, "ui-sync-test-delivery").start() }
    }

    private companion object {
        const val DISPLAY_ID = AirPlayInfoPlist.ALT_UUID
        const val STREAM_CONNECTION_ID = "cluster-stream-111"
        val PEERS = java.util.IdentityHashMap<CarPlayUiSyncRouter, CarPlayUiSyncPhoneRoleSimulator>()
        val PROGRAM_FINGERPRINT = UiSyncSemanticThemeProgramFingerprint(
            ByteArray(32) { index -> (index + 1).toByte() },
        )
        val DIFFERENT_PROGRAM_FINGERPRINT = UiSyncSemanticThemeProgramFingerprint(
            ByteArray(32) { index -> (0x7f - index).toByte() },
        )

        fun readinessReceipt(
            context: UiSyncCompleteRendererBindingContext,
            override: ReadinessReceiptOverride,
            programFingerprint: UiSyncSemanticThemeProgramFingerprint = PROGRAM_FINGERPRINT,
        ): RendererReadinessReceipt? {
            if (override == ReadinessReceiptOverride.MISSING) return null
            val outputs = context.staticPlan.outputs.map { it.outputId }.let {
                if (override == ReadinessReceiptOverride.OUTPUTS) it.dropLast(1) else it
            }.let {
                if (override == ReadinessReceiptOverride.DUPLICATE_OUTPUTS) {
                    it + it.first()
                } else {
                    it
                }
            }
            val subscriptions: Collection<String> = when (override) {
                ReadinessReceiptOverride.SUBSCRIPTIONS -> emptySet()
                ReadinessReceiptOverride.TOO_MANY_SUBSCRIPTIONS ->
                    List(65) { index -> "subscribed-$index" }
                ReadinessReceiptOverride.HOSTILE_COLLECTION_SIZE ->
                    object : AbstractCollection<String>() {
                        override val size: Int
                            get() = throw AssertionError("receipt issuer must not read size")

                        override fun iterator(): Iterator<String> =
                            context.displayIds.iterator()
                    }
                else -> context.displayIds
            }
            return context.issueReadinessReceipt(
                semanticThemeProgramFingerprint = if (
                    override == ReadinessReceiptOverride.PROGRAM_CHANGED
                ) {
                    DIFFERENT_PROGRAM_FINGERPRINT
                } else {
                    programFingerprint
                },
                subscribedDisplayIds = subscriptions,
                boundOutputIds = outputs,
                semanticCompilerActive =
                    override != ReadinessReceiptOverride.SEMANTIC_INACTIVE,
                themeCompilerActive = override != ReadinessReceiptOverride.THEME_INACTIVE,
                backendLive = override != ReadinessReceiptOverride.BACKEND_DEAD,
            )
        }

        fun readinessReceipt(
            context: UiSyncCompleteRendererSessionBindingContext,
            override: ReadinessReceiptOverride,
            programFingerprint: UiSyncSemanticThemeProgramFingerprint = PROGRAM_FINGERPRINT,
        ): RendererReadinessReceipt? {
            if (override == ReadinessReceiptOverride.MISSING) return null
            val outputs = context.staticPlan.outputs.map { it.outputId }.let {
                if (override == ReadinessReceiptOverride.OUTPUTS) it.dropLast(1) else it
            }.let {
                if (override == ReadinessReceiptOverride.DUPLICATE_OUTPUTS) {
                    it + it.first()
                } else {
                    it
                }
            }
            val subscriptions: Collection<String> = when (override) {
                ReadinessReceiptOverride.SUBSCRIPTIONS -> emptySet()
                ReadinessReceiptOverride.TOO_MANY_SUBSCRIPTIONS ->
                    List(65) { index -> "subscribed-$index" }
                ReadinessReceiptOverride.HOSTILE_COLLECTION_SIZE ->
                    object : AbstractCollection<String>() {
                        override val size: Int
                            get() = throw AssertionError("receipt issuer must not read size")

                        override fun iterator(): Iterator<String> =
                            context.displayIds.iterator()
                    }
                else -> context.displayIds
            }
            return context.issueReadinessReceipt(
                semanticThemeProgramFingerprint = if (
                    override == ReadinessReceiptOverride.PROGRAM_CHANGED
                ) {
                    DIFFERENT_PROGRAM_FINGERPRINT
                } else {
                    programFingerprint
                },
                subscribedDisplayIds = subscriptions,
                boundOutputIds = outputs,
                semanticCompilerActive =
                    override != ReadinessReceiptOverride.SEMANTIC_INACTIVE,
                themeCompilerActive = override != ReadinessReceiptOverride.THEME_INACTIVE,
                backendLive = override != ReadinessReceiptOverride.BACKEND_DEAD,
            )
        }

        fun overrideSchema(
            value: UiSyncSchemaFingerprint,
            override: ReceiptOverride,
        ) = if (override == ReceiptOverride.SCHEMA) {
            UiSyncSchemaFingerprint(ByteArray(32) { 0x5a })
        } else {
            value
        }

        fun overrideBinding(
            value: UiSyncBindingFingerprint,
            override: ReceiptOverride,
        ) = if (override == ReceiptOverride.BINDING) {
            UiSyncBindingFingerprint(ByteArray(32) { 0x6b })
        } else {
            value
        }

        fun overrideDisplays(
            value: Set<String>,
            override: ReceiptOverride,
        ): Set<String> = when (override) {
            ReceiptOverride.DISPLAYS -> setOf("wrong-display")
            ReceiptOverride.HOSTILE_DISPLAY_SIZE -> object : AbstractSet<String>() {
                override val size: Int
                    get() = throw AssertionError("renderer lease Set.size must not be read")

                override fun iterator(): Iterator<String> = value.iterator()
            }
            else -> value
        }
    }
}
