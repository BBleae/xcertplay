package com.shilapi.xcertplay.bench

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.AuthorizedAndroidPhysicalOutputSealer
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifest
import com.shilapi.xcertplay.AuthorizedUltraCompiledRuntimeManifestIssuer
import com.shilapi.xcertplay.AuthorizedUltraProfileBackendLease
import com.shilapi.xcertplay.AuthorizedUltraProfileBackendProvisioning
import com.shilapi.xcertplay.AuthorizedUltraProfilePreparation
import com.shilapi.xcertplay.CompiledCafRuntimeContract
import com.shilapi.xcertplay.CompiledInstrumentSignalDisplayAssignment
import com.shilapi.xcertplay.CompiledInstrumentSignalRuntimeContract
import com.shilapi.xcertplay.CompiledUiSyncRuntimeContract
import com.shilapi.xcertplay.ExternalClusterUiCoverGate
import com.shilapi.xcertplay.ExternalClusterUiCoverWatchdogScheduler
import com.shilapi.xcertplay.HostDisplaySurfaceKind
import com.shilapi.xcertplay.HostPhysicalDisplaySurfaceRegistry
import com.shilapi.xcertplay.HostUltraCompositionInputs
import com.shilapi.xcertplay.MainPhysicalUiCoverGate
import com.shilapi.xcertplay.UltraProfileActivationBackend
import com.shilapi.xcertplay.UltraProfileActivationCoordinator
import com.shilapi.xcertplay.UltraProfileActivationRequest
import com.shilapi.xcertplay.UltraProfileBackendPrepareResult
import com.shilapi.xcertplay.UltraProfileCommitResult
import com.shilapi.xcertplay.UltraProfileDeploymentResult
import com.shilapi.xcertplay.UltraProfilePrepareResult
import com.shilapi.xcertplay.UltraProfileProvisioningOpenResult
import com.shilapi.xcertplay.UltraProfileSessionStartResult
import com.shilapi.xcertplay.UltraProfileVerificationIdentity
import com.shilapi.xcertplay.UltraRuntimeIdentity
import com.shilapi.xcertplay.TrustedUltraVehicleIdentityResult
import com.shilapi.xcertplay.externalCalibrationForTest
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.UltraRevealTrace
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanel
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanels
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanelsLiveness
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPlugin
import com.shilapi.xcertplay.airplay.AirPlayPhysicalVideoStream
import com.shilapi.xcertplay.airplay.AirPlayTransportReadiness
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfileBridge
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandEnvelope
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEvent
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationHub
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPhoneRoleSimulator
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSessionState
import com.shilapi.xcertplay.airplay.rcs.uisync.BoundedUiSyncPresentationExecutor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncDisplayRuntimeSpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeAuthorizationExpectation
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRuntimeProvisioning
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.ultra.AndroidUltraRendererSessionCoordinator
import com.shilapi.xcertplay.ultra.AuthorizedAndroidUiSyncPhysicalOutputAuthority
import com.shilapi.xcertplay.ultra.AuthorizedStaticThemeRendererCapability
import com.shilapi.xcertplay.ultra.CarPlayUltraCustomCanvas
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PhysicalLegacyHandoffResult
import com.shilapi.xcertplay.ultra.PhysicalLegacySurfaceHandoffPort
import com.shilapi.xcertplay.ultra.PhysicalLegacySurfaceReservation
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraSceneSource
import com.shilapi.xcertplay.ultra.StaticThemeFactoryBackendLease
import com.shilapi.xcertplay.ultra.egl.AndroidUltraEglBackend
import com.shilapi.xcertplay.ultra.egl.AndroidVideoSurfaceLeaseAcquirer
import com.shilapi.xcertplay.ultra.egl.UltraEglBackendPhase
import com.shilapi.xcertplay.ultra.egl.UltraEglDriver
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverIngress
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverOutput
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverRetirementProof
import com.shilapi.xcertplay.ultra.egl.UltraEglDriverRenderedFrame
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglLocalLayerDraw
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderThread
import com.shilapi.xcertplay.ultra.egl.UltraEglResolvedScene
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import sun.misc.Unsafe

/**
 * Joins the signed Debug declaration, real issuer/AA05 child, physical authority and real EGL
 * render thread. Only the Android-native EGL driver and Host Surface/Texture are JVM stand-ins.
 * A TextureView callback proves software UI-cover admission, never optical panel scan-out.
 */
class BenchSignedInstrumentAuthorityEndToEndTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun mainProfileNeedsDetachedSignatureAndSeparateUiSyncBenchOptIn() {
        val root = temporaryFolder.newFolder("main-signature-opt-in").toPath()
        val key = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val imports = BenchUltraProfileImportStore(root.resolve(
            BenchUltraProfileImportStore.DIRECTORY))
        val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) =
                "signed-main-opt-in-test".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        })
        val sink = unsafe.allocateInstance(AndroidMediaSink::class.java) as AndroidMediaSink
        // Identity resolution only needs non-null Host inputs; this Unsafe sink has no
        // usable media/physical route and can never satisfy the later acquire/render gate.
        val host = HostUltraCompositionInputs(sink,
            HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {}),
            AirPlayConfig("preflight-only", "02:00:00:00:00:02", "02:00:00:00:00:01",
                "1.0", AirPlayDisplayConfig(800, 480)),
        )
        fun provider(allowUiSync: Boolean) = BenchUltraProfileDeploymentProvider(
            importRoot = root.resolve(BenchUltraProfileImportStore.DIRECTORY),
            profileStoreRoot = root.resolve("verified-profiles"),
            issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 0x43 }),
            hostInputs = host,
            allowUiSyncBench = allowUiSync,
            bridgeFactory = { compiled -> AndroidCarPropertyCafProfileBridge.fromBackend(
                compiled.profile, compiled.syntheticBackend()) },
        )
        val provider = provider(false)
        val optedInProvider = provider(true)
        try {
            imports.stageBase(generatedMainArchive().inputStream(),
                key.public.encoded.inputStream(), profileVehicleId)
            assertEquals(null, imports.loadActive()?.detachedSignature)
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
            assertTrue(optedInProvider.resolve(identity) is
                TrustedUltraVehicleIdentityResult.Unavailable)
            val payload = requireNotNull(imports.signingRequest())
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(payload)
                sign()
            }
            imports.stageDetachedSignature(signature.inputStream())
            assertTrue(imports.loadActive()?.detachedSignature?.contentEquals(signature) == true)
            assertTrue(provider.resolve(identity) is TrustedUltraVehicleIdentityResult.Unavailable)
            assertTrue(provider.acquire(UltraProfileActivationRequest(
                UltraRuntimeIdentity.from(profileVehicleId, identity), identity,
            )) is UltraProfileDeploymentResult.Unavailable)
            assertTrue(optedInProvider.resolve(identity) is
                TrustedUltraVehicleIdentityResult.Resolved)
        } finally {
            provider.close()
            optedInProvider.close()
        }
    }

    @Test fun signedAssetlessMainOnlyPreflightFreezesDdpAndUiSyncInOneAa05Child() {
        Fixture(mainOnly = true).use { fixture ->
            val config = fixture.mainAirPlayConfig()
            val exchange = AirPlayFeatureNegotiation.exchange(config)
            val transport = fixture.mainTransport()
            val selected = exchange.negotiate(listOf("uiContext", "uiSync"), transport)
            assertEquals(listOf("uiContext", "uiSync"), selected.enabledFeatures)
            val scope = exchange.infoScope(transport)
            assertTrue(scope.includes(AirPlayFeature.UI_SYNC))
            val info = AirPlayInfoPlist.build(config, scope)
            assertTrue(info.containsKey("uiSyncInfo"))
            assertTrue(info.containsKey("displayPanels"))
            assertEquals("xcertplay-main", fixture.mainUiSyncDisplayId())
            assertEquals(fixture.mainUiSyncDisplayId(),
                config.displayPanels!!.pluginUidManagingLegacyStream(AirPlayInfoPlist.MAIN_UUID))
            assertEquals(listOf(AirPlayInfoPlist.MAIN_UUID),
                (info["displays"] as List<*>).map {
                    (it as Map<*, *>)["uuid"]
                })
            val panel = (info["displayPanels"] as List<*>).single() as Map<*, *>
            val plugin = (panel["displayPlugins"] as List<*>).single() as Map<*, *>
            assertEquals(fixture.mainUiSyncDisplayId(), plugin["uid"])
            val legacy = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(AirPlayInfoPlist.MAIN_UUID, legacy["uuid"])
            assertEquals(null, info["themeAssetIdentifier"])

            fixture.openAuthorizedSession()
            assertTrue(fixture.mainGate?.hasAppliedCover == true)
            assertTrue(fixture.hasBoundExactMainBatch())
            assertTrue(fixture.mainGate?.isCovered == true)
            val timestamp = fixture.driver.awaitFinishedLocalCanvasFrame()
            val revealed = fixture.awaitExactMainReveal(timestamp)
            assertTrue(fixture.mainProofDiagnostics(timestamp), revealed)
            assertFalse(fixture.mainGate?.isCovered == true)
            // This helper closes the synthetic type-130 peer after RESET/READY, which can
            // invalidate controller activation. Prove the first local frame before that
            // teardown; RESET/READY by themselves are never physical frame receipts.
            assertTrue(fixture.sameSessionClusterControlReady())
            assertTrue(fixture.hasBoundExactMainBatch())
        }
    }

    /**
     * iOS 27.0.1 assetless DashBoard order on the real shared session, RCS stream, router,
     * controller and Authority: READY, the Host's queued signed raw-5, the phone's commandAck,
     * then its type-6 echo of exactly that request data. The echo must stand for the signed
     * type 3 it never sends: a fresh scene revision, its own EGL first frame, then MAIN reveal.
     */
    @Test fun signedAssetlessMainRequestEchoAfterAckRevealsFreshSignedScene() {
        val trace = CopyOnWriteArrayList<String>()
        UltraRevealTrace.testObserver = { trace += it }
        try {
            echoThenType19RevealsAgain()
        } finally {
            UltraRevealTrace.testObserver = null
        }
        // The Debug reveal markers trace each stage of that run in order.
        fun index(prefix: String) = trace.indexOfFirst { it.startsWith(prefix) }
        val echo = index("ULTRA_STATUS event=REVEAL_ECHO_AUTHORITY decision=ACCEPTED " +
            "reason=EXACT_ACKED_ECHO layout=main-local-canvas")
        val echoScene = trace.withIndex().indexOfFirst { (i, line) ->
            i > echo && line.startsWith("ULTRA_STATUS event=REVEAL_SCENE_SUBMITTED") &&
                line.contains(" choice=ECHO ")
        }
        val appearance = index("ULTRA_STATUS event=REVEAL_APPEARANCE decision=ACCEPTED " +
            "reason=SAME_SIGNED_SCENE preference=1")
        val revealed = trace.withIndex().indexOfLast { (_, line) ->
            line == "ULTRA_STATUS event=REVEAL_MAIN_GATE state=REVEALED"
        }
        assertTrue(trace.joinToString("\n"), echo >= 0 && echoScene > echo &&
            appearance > echoScene && revealed > appearance)
        assertTrue(trace.any { it.startsWith("ULTRA_STATUS event=REVEAL_LOCAL_CANVAS " +
            "phase=FIRST_FRAME") })
        assertTrue(trace.any { it.startsWith("ULTRA_STATUS event=REVEAL_AUTHORITY_MAIN " +
            "decision=ARMED") })
        assertTrue(trace.any { it.startsWith("ULTRA_STATUS event=REVEAL_MAIN_COVER phase=ACK") })
    }

    private fun echoThenType19RevealsAgain() {
        Fixture(mainOnly = true).use { fixture ->
            fixture.openAuthorizedSession()
            val first = fixture.driver.awaitFinishedLocalCanvasFrame()
            assertTrue(fixture.mainProofDiagnostics(first), fixture.awaitExactMainReveal(first))
            val revisionBefore = fixture.sceneRevision()
            fixture.withAssetlessClusterControl { phone ->
                assertEquals(CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue,
                    phone.request.type)
                assertEquals(mapOf(
                    "fadeInNewLayout" to true, "fadeOutOldLayout" to true,
                    "activeComponents" to emptyMap<String, String>(),
                    "layoutID" to "main-local-canvas",
                ), phone.request.data)
                phone.send(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, phone.request.data)
                assertTrue(awaitTrue(3_000) { fixture.sceneRevision() > revisionBefore })
                val fresh = fixture.driver.awaitLocalCanvasFrameAfter(first)
                assertTrue(fixture.mainProofDiagnostics(fresh), fixture.awaitExactMainReveal(fresh))
                assertFalse(fixture.mainGate?.isCovered == true)

                // DashBoard then states its appearance preference (type 19) and never sends a
                // layout choice. MAIN re-covers, the same signed scene is re-proved as a fresh
                // revision, and only its own presented frame reveals MAIN again.
                val echoRevision = fixture.sceneRevision()
                phone.send(CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
                    mapOf("preference" to 1))
                assertTrue(awaitTrue(3_000) { fixture.sceneRevision() > echoRevision })
                assertFalse("The pre-19 frame must not reveal the covered MAIN",
                    fixture.awaitExactMainReveal(fresh, timeoutMillis = 100L))
                val reproved = fixture.driver.awaitLocalCanvasFrameAfter(fresh)
                assertTrue(fixture.mainProofDiagnostics(reproved),
                    fixture.awaitExactMainReveal(reproved))
                assertFalse(fixture.mainGate?.isCovered == true)
            }
        }
    }

    /** Fail-closed type-19 cases on the same real path: MAIN stays covered, no new revision. */
    @Test fun signedAssetlessMainUnsupportedType19OrOpenType21StaysCovered() {
        listOf(
            listOf(CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                mapOf("preference" to 7)),
            listOf(CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START to
                mapOf("targetAppearance" to 1),
                CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE to
                    mapOf("preference" to 1)),
        ).forEach { events ->
            Fixture(mainOnly = true).use { fixture ->
                fixture.openAuthorizedSession()
                val first = fixture.driver.awaitFinishedLocalCanvasFrame()
                assertTrue(fixture.awaitExactMainReveal(first))
                fixture.withAssetlessClusterControl { phone ->
                    phone.send(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, phone.request.data)
                    val fresh = fixture.driver.awaitLocalCanvasFrameAfter(first)
                    assertTrue(fixture.awaitExactMainReveal(fresh))
                    val revision = fixture.sceneRevision()
                    events.forEach { (type, data) -> phone.send(type, data) }
                    assertTrue(awaitTrue(3_000) { fixture.mainGate?.isCovered == true })
                    Thread.sleep(300L)
                    assertEquals(revision, fixture.sceneRevision())
                    assertFalse(fixture.awaitExactMainReveal(fixture.driver.currentTimestamp(),
                        timeoutMillis = 300L))
                    assertTrue(fixture.mainGate?.isCovered == true)
                }
            }
        }
    }

    /**
     * A phone Reset after the echo: the old epoch's MAIN permit is revoked, the Host re-requests
     * the same signed layout once the new handshake reaches READY, and the new epoch's ACKed
     * echo recovers MAIN through a fresh revision exactly like the first one.
     */
    @Test fun signedAssetlessMainRecoversAfterPhoneResetFromNewEpochEcho() {
        Fixture(mainOnly = true).use { fixture ->
            fixture.openAuthorizedSession()
            val first = fixture.driver.awaitFinishedLocalCanvasFrame()
            assertTrue(fixture.awaitExactMainReveal(first))
            fixture.withAssetlessClusterControl { phone ->
                phone.send(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, phone.request.data)
                val fresh = fixture.driver.awaitLocalCanvasFrameAfter(first)
                assertTrue(fixture.awaitExactMainReveal(fresh))
                val oldRequest = phone.request

                phone.resetAndAcknowledgeNextRequest()
                assertTrue(fixture.mainGate?.isCovered == true)
                assertEquals(oldRequest.data, phone.request.data)
                // The raw-5 came from a fresh, still covered re-request revision.
                val reRequested = fixture.sceneRevision()
                val coveredFrame = fixture.driver.awaitLocalCanvasFrameAfter(fresh)
                assertFalse(fixture.awaitExactMainReveal(coveredFrame, timeoutMillis = 100L))
                phone.send(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, phone.request.data)
                assertTrue(awaitTrue(3_000) { fixture.sceneRevision() > reRequested })
                val recovered = fixture.driver.awaitLocalCanvasFrameAfter(coveredFrame)
                assertTrue(fixture.mainProofDiagnostics(recovered),
                    fixture.awaitExactMainReveal(recovered))
                assertFalse(fixture.mainGate?.isCovered == true)
            }
        }
    }

    @Test fun signedAssetlessMainWithoutExactAcknowledgedEchoStaysCovered() {
        Fixture(mainOnly = true).use { fixture ->
            fixture.openAuthorizedSession()
            val first = fixture.driver.awaitFinishedLocalCanvasFrame()
            assertTrue(fixture.mainProofDiagnostics(first), fixture.awaitExactMainReveal(first))
            val revisionBefore = fixture.sceneRevision()
            fixture.withAssetlessClusterControl { phone ->
                @Suppress("UNCHECKED_CAST")
                val changed = LinkedHashMap(phone.request.data as Map<String, Any?>).apply {
                    put("fadeInNewLayout", false)
                }
                phone.send(CarPlayUiSyncCommandType.END_LAYOUT_CHANGE, changed)
                assertTrue(awaitTrue(3_000) { fixture.mainGate?.isCovered == true })
                Thread.sleep(300L)
                assertEquals(revisionBefore, fixture.sceneRevision())
                assertFalse(fixture.awaitExactMainReveal(fixture.driver.currentTimestamp(),
                    timeoutMillis = 300L))
                assertTrue(fixture.mainGate?.isCovered == true)
            }
        }
    }

    /**
     * Real Android order: HWUI latches each TextureView frame after its UI-thread callback, so
     * the callback never names the newest frame and a static scene sends no later callback.
     * MAIN must reveal from the latched permitted frame alone, and never from an older one.
     */
    @Test fun signedMainRevealsFromLatchedPermittedFrameWithoutTextureCallbacks() {
        Fixture(mainOnly = true, latchedTimestamp = { it.currentTimestamp() }).use { fixture ->
            fixture.openAuthorizedSession()
            fixture.driver.awaitFinishedLocalCanvasFrame()
            assertTrue(fixture.mainProofDiagnostics(0L), awaitTrue(5_000) {
                fixture.watchdog.tick()
                fixture.mainGate?.isCovered == false
            })
        }
        Fixture(mainOnly = true, latchedTimestamp = { 1L }).use { fixture ->
            fixture.openAuthorizedSession()
            fixture.driver.awaitFinishedLocalCanvasFrame()
            repeat(20) {
                fixture.watchdog.tick()
                Thread.sleep(10L)
            }
            assertTrue("An older latched image must never reveal MAIN",
                fixture.mainGate?.isCovered == true)
        }
    }

    @Test fun signedAssetlessMainInfoFirstDoesNotRetroactivelyAdvertiseUiSync() {
        Fixture(mainOnly = true).use { fixture ->
            val config = fixture.mainAirPlayConfig()
            val exchange = AirPlayFeatureNegotiation.exchange(config)
            val first = AirPlayInfoPlist.build(config, exchange.infoScope(fixture.mainTransport()))
            assertFalse(first.containsKey("uiSyncInfo"))
            assertFalse(first.containsKey("displayPanels"))
            val selected = exchange.negotiate(listOf("uiContext", "uiSync"),
                fixture.mainTransport())
            assertFalse(AirPlayFeature.UI_SYNC in selected.enabledFeatureSet)
            assertFalse(AirPlayInfoPlist.build(config,
                exchange.infoScope(fixture.mainTransport())).containsKey("uiSyncInfo"))
        }
    }

    @Test fun fixtureCloseRetriesOnlyTransientUnprovenIngressRetirement() {
        val fixture = Fixture()
        var calls = 0
        fixture.installCloseCandidateForTest(Closeable {
            calls++
            if (calls == 1) {
                throw IllegalStateException("Owned ingress retirement is not proven: INTERRUPTED")
            }
        })
        fixture.close()
        fixture.close()
        assertEquals(2, calls)
    }

    @Test fun fixtureCloseKeepsFirstFailureAndAllowsLaterExplicitRetry() {
        val fixture = Fixture()
        val first = IllegalStateException("Owned ingress retirement is not proven: INTERRUPTED")
        var calls = 0
        var allowClose = false
        fixture.installCloseCandidateForTest(Closeable {
            calls++
            if (!allowClose) throw if (calls == 1) first else
                IllegalStateException("Owned ingress retirement is not proven: INTERRUPTED")
        })
        try {
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                fixture.close()
            }
            assertTrue(failure === first)
            assertEquals(Fixture.MAX_SESSION_CLOSE_ATTEMPTS, calls)
            assertTrue(failure.suppressed.isNotEmpty())
        } finally {
            allowClose = true
            fixture.close()
        }
        assertEquals(Fixture.MAX_SESSION_CLOSE_ATTEMPTS + 1, calls)
    }

    @Test fun signedMainWrongPluginUidAndMissingPhysicalOrTransportFailClosed() {
        Fixture(mainOnly = true).use { fixture ->
            val wrong = fixture.mainAirPlayConfig(pluginUid = "foreign-main")
            assertFalse(AirPlayFeatureNegotiation.snapshot(wrong).isReady(AirPlayFeature.UI_SYNC))
            val wrongExchange = AirPlayFeatureNegotiation.exchange(wrong)
            assertFalse(AirPlayFeature.UI_SYNC in wrongExchange.negotiate(
                listOf("uiContext", "uiSync"), fixture.mainTransport()).enabledFeatureSet)
            val wrongInfo = AirPlayInfoPlist.build(wrong,
                wrongExchange.infoScope(fixture.mainTransport()))
            assertFalse(wrongInfo.containsKey("uiSyncInfo"))
            assertFalse(wrongInfo.containsKey("displayPanels"))
            assertEquals(AirPlayInfoPlist.MAIN_UUID,
                ((wrongInfo["displays"] as List<*>).single() as Map<*, *>)["uuid"])

            val exact = fixture.mainAirPlayConfig()
            listOf(
                fixture.mainTransport().copy(eventChannel = false),
                fixture.mainTransport().copy(wirelessSession = false),
                fixture.mainTransport().copy(readyRcsClientTypes = emptySet()),
            ).forEach { unavailable ->
                val exchange = AirPlayFeatureNegotiation.exchange(exact)
                assertFalse(AirPlayFeature.UI_SYNC in exchange.negotiate(
                    listOf("uiContext", "uiSync"), unavailable).enabledFeatureSet)
                val info = AirPlayInfoPlist.build(exact, exchange.infoScope(unavailable))
                assertFalse(info.containsKey("uiSyncInfo"))
                assertFalse(info.containsKey("displayPanels"))
            }
            fixture.changeMainPhysicalGeneration()
            assertFalse(AirPlayFeatureNegotiation.snapshot(exact)
                .isReady(AirPlayFeature.UI_SYNC))
        }
    }

    @Test fun diagnosticDdpAloneIsNotAcceptedUiSyncOrClusterControl() {
        Fixture(mainOnly = true).use { fixture ->
            val diagnostic = fixture.mainAirPlayConfig().copy(
                ultra = null, requireMainUiSyncForDisplayPanels = false)
            val exchange = AirPlayFeatureNegotiation.exchange(diagnostic)
            assertFalse(AirPlayFeature.UI_SYNC in exchange.negotiate(
                listOf("uiContext", "uiSync"), fixture.mainTransport()).enabledFeatureSet)
            val info = AirPlayInfoPlist.build(diagnostic,
                exchange.infoScope(fixture.mainTransport()))
            assertTrue(info.containsKey("displayPanels"))
            assertFalse(info.containsKey("uiSyncInfo"))
            assertEquals(AirPlayInfoPlist.MAIN_UUID,
                ((info["displays"] as List<*>).single() as Map<*, *>)["uuid"])
        }
    }

    @Test fun signedUnavailableClusterFrameRevealsOnlyAfterExactTextureCallback() {
        Fixture().use { fixture ->
            assertFalse(fixture.registration!!.onTextureUpdated(fixture.wrongTexture, 101L))
            fixture.openAuthorizedSession()
            val timestamp = fixture.driver.awaitFinishedInstrumentFrame()
            assertTrue(timestamp > 0L)
            val initialClear = fixture.driver.uploads.first()
            assertTrue(initialClear.all { it ushr 24 == 0xff })
            assertTrue(initialClear.toSet().size > 1) // BENCH + DATA UNAVAILABLE
            assertEquals(-0x8d2e2e, initialClear[0]) // Opaque BENCH warning bar.
            assertTrue(initialClear.any { it == -0x1a66 }) // Unavailable warning text.
            assertFalse(initialClear.any { it == -0xd9d6d0 }) // No numeric card.
            assertTrue(fixture.cover.isCovered)
            assertFalse(fixture.registration!!.onTextureUpdated(fixture.wrongTexture, timestamp))
            assertTrue(fixture.cover.isCovered)
            val revealed = fixture.awaitExactReveal(timestamp)
            assertFalse(fixture.cover.isCovered)
            assertFalse(fixture.registration!!.onTextureUpdated(fixture.texture, revealed - 1))
            assertTrue(fixture.cover.isCovered)
        }
    }

    /**
     * Product order through the real shared session, Authority, controller and instrument owner:
     * a phone RESET revokes the old CLUSTER permit synchronously, the exact Host cover ACK is held,
     * and the fresh signed type-3 scene plus its post-RESET safety clear present first. The
     * CLUSTER must stay covered until that ACK and then reveal without another phone event,
     * while the issued physical batch (MAIN's batch) stays the same.
     */
    @Test fun clusterResetFreshSceneBeforeHostAckRevealsOnlyAfterExactAck() {
        Fixture().use { fixture ->
            fixture.openAuthorizedSession()
            val firstReveal = fixture.awaitExactReveal(fixture.driver.awaitFinishedInstrumentFrame())
            assertFalse(fixture.cover.isCovered)
            val batch = requireNotNull(fixture.activeIssuedBatch())
            val revisionBefore = fixture.sceneRevision()
            val uploadsBefore = fixture.driver.uploads.size
            val hold = CountDownLatch(1)
            fixture.coverHold.set(hold)
            try {
                assertTrue(fixture.resetClusterPhoneEpoch())
                // Phase 1 revoked the old permit before any Host ACK: the next callback covers.
                assertFalse(fixture.registration!!.onTextureUpdated(fixture.texture, firstReveal))
                assertTrue(fixture.cover.isCovered)
                assertTrue(awaitTrue(3_000) { fixture.coverAckBlocked.get() })
                assertTrue(awaitTrue(3_000) { fixture.driver.uploads.size > uploadsBefore })
                fixture.sendClusterLayout("main", "palette-a")
                assertTrue(awaitTrue(3_000) { fixture.sceneRevision() > revisionBefore })
                Thread.sleep(200)
                assertFalse("A pre-ACK replay revealed the CLUSTER", fixture.registration!!
                    .onTextureUpdated(fixture.texture, fixture.driver.currentTimestamp()))
                assertTrue(fixture.cover.isCovered)
            } finally {
                hold.countDown()
            }
            val revealed = fixture.awaitExactReveal(firstReveal + 1)
            assertTrue(revealed > firstReveal)
            assertFalse(fixture.cover.isCovered)
            assertTrue(fixture.activeIssuedBatch() === batch)
            fixture.driver.uploads.drop(uploadsBefore).forEach { upload ->
                assertFalse(upload.any { it == -0xd9d6d0 }) // No numeric card.
                assertTrue(upload.any { it == -0x1a66 }) // DATA UNAVAILABLE warning.
            }
        }
    }

    private fun awaitTrue(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test fun aa05RevocationCoversPreviouslyRevealedCluster() {
        Fixture().use { fixture ->
            fixture.openAuthorizedSession()
            fixture.awaitExactReveal(fixture.driver.awaitFinishedInstrumentFrame())
            assertFalse(fixture.cover.isCovered)
            fixture.revokeAa05()
            fixture.watchdog.tick()
            assertTrue(fixture.cover.isCovered)
        }
    }

    @Test fun physicalTrustRevocationCoversPreviouslyRevealedCluster() {
        Fixture().use { fixture ->
            fixture.openAuthorizedSession()
            fixture.awaitExactReveal(fixture.driver.awaitFinishedInstrumentFrame())
            assertFalse(fixture.cover.isCovered)
            fixture.trust!!.invalidate()
            assertTrue(fixture.cover.isCovered)
        }
    }

    private class Fixture(
        private val mainOnly: Boolean = false,
        /** Models HWUI: the consumer's latched image, read without any texture callback. */
        private val latchedTimestamp: ((TimestampDriver) -> Long)? = null,
    ) : Closeable {
        private val identity = MfiAuthenticationIdentity.capture(object : MfiAuthenticator {
            override val certificateType = MfiCertificateType.MFI
            override fun protocolMajor() = 1
            override fun readCertificate(maximumOutputLength: Int) =
                "joined-signed-instrument-test".encodeToByteArray()
            override fun signChallenge(challenge: ByteArray) = byteArrayOf(1)
        })
        private val profile = signedProfile(identity, mainOnly)
        private val output = UltraDisplayGraphOutput(
            UltraTopologyDisplayId(UUID.fromString(if (mainOnly) AirPlayInfoPlist.MAIN_UUID
                else AirPlayInfoPlist.ALT_UUID)),
            UltraDisplayOutputId.Android(if (mainOnly) "host.main" else "host.cluster"),
            UltraUiSyncDisplayId(if (mainOnly) "xcertplay-main" else "ui.cluster"),
        )
        private val declared = BenchUiSyncProfileCompiler().compile(profile,
            listOf(BenchUiSyncHostOutput(0,
                if (mainOnly) AirPlayInfoPlist.MAIN_UUID else AirPlayInfoPlist.ALT_UUID,
                if (mainOnly) DisplayDestination.MAIN else DisplayDestination.CLUSTER,
                output)), emptyList())
        private val cafCompiled = BenchCafProfileCompiler().compile(profile)
        private val bridge = AndroidCarPropertyCafProfileBridge.fromBackend(
            cafCompiled.profile, cafCompiled.syntheticBackend())
        val source = UnavailableSource(cafCompiled.profile.configTree)
        val watchdog = ManualWatchdog()
        private val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
            coverWatchdogScheduler = watchdog,
            readLatchedTimestamp = { requireNotNull(latchedTimestamp)(driver) },
        )
        val surface = validSurface()
        val texture = texture()
        val wrongTexture = texture()
        val trust = if (mainOnly) null else registry.newExternalPhysicalTargetTrustToken(
            externalCalibrationForTest(pixels = PixelSize(800, 480)),
        ).also { it.bindRouteProof { true }; it.bindLegacyDecoderProof { true } }
        private val destination = if (mainOnly) DisplayDestination.MAIN else DisplayDestination.CLUSTER
        private val version = requireNotNull(registry.publish(
            destination, if (mainOnly) HostDisplaySurfaceKind.PHYSICAL_MAIN
                else HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            surface, texture, 800, 480, trust,
        ))
        val mainGate = if (mainOnly) MainPhysicalUiCoverGate { _, _ -> true } else null
        private val mainRegistration = mainGate?.let {
            requireNotNull(registry.bindMainPhysicalUiCover(surface, texture, version, Any(), it))
        }
        /** Holds only the shared RESET ticket's Host cover, to order it after a fresh scene. */
        val coverHold = AtomicReference<CountDownLatch?>()
        val coverAckBlocked = AtomicBoolean(false)
        val cover = ExternalClusterUiCoverGate { _, covered ->
            if (covered && Thread.currentThread().name == "xcertplay-ui-sync-cluster-cover") {
                coverHold.get()?.let { latch ->
                    coverAckBlocked.set(true)
                    latch.await(10, TimeUnit.SECONDS)
                }
            }
            true
        }
        val registration = if (mainOnly) null else requireNotNull(
            registry.bindExternalClusterUiCover(surface, texture, 4, 101L, cover))
        private val inputs = UiSyncRuntimeProvisioning.prepareAuthorizedStaticInputs(
            AirPlayUiSyncInfo(), listOf(UiSyncDisplayRuntimeSpec(
                CarPlayUiSyncChannelDescriptor(output.uiSyncDisplayId!!.value,
                    profile.binding.vehicleId),
                CarPlayUiSyncMode.PASSIVE,
            )), UiSyncRendererStaticPlan(declared.outputs, emptyList()),
        )
        private val requirement = UiSyncPhysicalOutputRequirement(
            profile.candidate.contentDigest,
            listOf(UiSyncPhysicalOutputAssignment(output,
                UiSyncPhysicalDestination(if (mainOnly) UiSyncPhysicalDestinationRole.MAIN
                    else UiSyncPhysicalDestinationRole.CLUSTER))),
        )
        private lateinit var activation: UltraProfileActivationCoordinator
        private val handoff = TestHandoff(mainOnly)
        val driver = TimestampDriver(mainOnly)
        private var coordinatorOpens = 0
        private var sessionBackend: AndroidUltraEglBackend? = null
        private val authority = AuthorizedAndroidUiSyncPhysicalOutputAuthority(
            profile, requirement, inputs.staticPlan, inputs.schemaFingerprint,
            inputs.bindingFingerprint, registry, handoff,
            { token -> activation.currentPhysicalGenerationProof(token) },
            mapOf(destination to if (mainOnly) 110 else 111),
            generationStatusSource = { token -> activation.physicalGenerationStatus(token) },
        )
        private val callbacks = BoundedUiSyncPresentationExecutor(1, 64)
        private val capability = AuthorizedStaticThemeRendererCapability(
            profile, declared.assignments, inputs, authority,
            openFactoryBackend = {
                val backend = AndroidUltraEglBackend(UltraEglRenderThread(
                    TimestampDriver(mainOnly)))
                object : StaticThemeFactoryBackendLease {
                    override val isLive get() = backend.diagnostics().phase == UltraEglBackendPhase.OPEN
                    override fun close() = backend.close()
                }
            },
            openCoordinator = { context ->
                coordinatorOpens++
                val backend = AndroidUltraEglBackend(UltraEglRenderThread(
                    if (coordinatorOpens == 1) TimestampDriver(mainOnly) else driver,
                ))
                if (coordinatorOpens > 1) sessionBackend = backend
                AndroidUltraRendererSessionCoordinator(
                    context, backend,
                    AndroidVideoSurfaceLeaseAcquirer { _, _ ->
                        error("Signed instrument scene has no remote video")
                    },
                )
            },
            testSeam = Unit,
        )
        private val ui = UiSyncRuntimeProvisioning.fromAuthorizedRendererCapability(
            inputs, capability, callbacks,
            UiSyncRuntimeAuthorizationExpectation(
                declared.semanticThemeProgramFingerprint,
                AndroidUltraRendererSessionCoordinator.IMPLEMENTATION_ID, requirement,
            ), authority,
        )
        private val snapshot = requireNotNull(ui.authorizationSnapshot())
        private val issuer = AuthorizedUltraCompiledRuntimeManifestIssuer(ByteArray(32) { 0x33 })
        private val request = UltraProfileActivationRequest(
            UltraRuntimeIdentity.from(profile.binding.vehicleId, identity), identity,
        )
        private val crosswalk = BenchInstrumentSignalProfileCompiler().compile(profile)
        private val manifest = issuer.issue(request, profile,
            CompiledUiSyncRuntimeContract.from(snapshot, declared.assignments),
            CompiledCafRuntimeContract.from(requireNotNull(
                bridge.provisioning.authorizationSnapshot())),
            crosswalk?.let { signed -> CompiledInstrumentSignalRuntimeContract(profile,
                profile.candidate.entries.single {
                    it.name == BenchInstrumentSignalProfileCompiler.SPEC_PATH
                }.digest, signed.phonePackageDigest,
                requireNotNull(bridge.provisioning.authorizationSnapshot()).schemaFingerprint,
                signed.bindings.map { row ->
                    CompiledInstrumentSignalDisplayAssignment(
                        row.profileDisplayIndex, row.profileDisplayId, row.spec,
                    )
                },
            ) },
        )
        private val sealer = AuthorizedAndroidPhysicalOutputSealer(authority)
        private val resources = Resources(ui, callbacks, bridge, source, authority)
        private val receipt = receipt(identity)
        private val canvasPainter = if (mainOnly) CarPlayUltraCustomCanvas.registerPainter(
            BenchLocalCanvasPainter.CONTENT_ID,
        ) { _, _ -> } else null
        private var session: Closeable? = null
        private var runtime: AirPlayUltraRuntime? = null
        private var ultraConfiguration: AirPlayUltraConfiguration? = null
        private var closed = false

        fun installCloseCandidateForTest(candidate: Closeable) {
            check(session == null)
            session = candidate
        }

        init {
            val ctor = UltraProfileActivationCoordinator::class.java.getDeclaredConstructor(
                UltraProfileActivationBackend::class.java,
                AuthorizedUltraCompiledRuntimeManifestIssuer::class.java,
            ).apply { isAccessible = true }
            activation = ctor.newInstance(UltraProfileActivationBackend { current ->
                check(current.runtimeIdentity == request.runtimeIdentity)
                UltraProfileBackendPrepareResult.Prepared(
                    Preparation(request, profile, manifest, resources, sealer, mainOnly),
                )
            }, issuer)
        }

        private fun prepareAuthorizedChild() {
            if (runtime != null) return
            val token = (activation.beginSession(profile.binding.vehicleId,
                receipt.first) as UltraProfileSessionStartResult.Started).token
            val prepared = (activation.prepare(token,
                receipt.first) as UltraProfilePrepareResult.Prepared).activation
            val parent = (activation.commit(prepared,
                receipt.first) as UltraProfileCommitResult.Activated).lease
            val opened = parent.openRuntime()
            check(opened is UltraProfileProvisioningOpenResult.Opened) {
                "Signed Debug child denied: $opened"
            }
            val child = opened.runtimeLease
            assertEquals(null, child.sealPhysicalOutputAuthority())
            if (mainOnly) ultraConfiguration = child.configuration()
            runtime = child.runtimeForMediaEngine()
        }

        fun openAuthorizedSession() {
            prepareAuthorizedChild()
            val runtime = requireNotNull(runtime)
            // Shared keeps this product session entry internal across Gradle module boundaries.
            // Reflection invokes the real method; no session or physical batch is constructed.
            val open = runtime.javaClass.declaredMethods.single {
                it.name.startsWith("openSession") && it.parameterCount == 0
            }.apply { isAccessible = true }
            session = try {
                open.invoke(runtime) as Closeable
            } catch (failure: java.lang.reflect.InvocationTargetException) {
                if (!mainOnly) throw failure
                throw AssertionError("MAIN activation failed: ${mainProofDiagnostics(0L)}", failure)
            }
            assertTrue(cover.isCovered)
        }

        fun awaitExactReveal(timestamp: Long): Long {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val candidate = driver.currentTimestamp().coerceAtLeast(timestamp)
                if (requireNotNull(registration).onTextureUpdated(texture, candidate)) return candidate
                Thread.sleep(10L)
            }
            error("No exact signed frame permit after EGL completion: ${driver.trace()}; " +
                "gate covered=${cover.isCovered}, currentTimestamp=${driver.currentTimestamp()}, " +
                "${diagnostics()}")
        }

        fun awaitExactMainReveal(timestamp: Long, timeoutMillis: Long = 5_000L): Boolean {
            check(mainOnly)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (System.nanoTime() < deadline) {
                if (requireNotNull(mainRegistration).onTextureUpdated(
                        texture, timestamp)) return true
                Thread.sleep(10L)
            }
            return false
        }

        fun mainProofDiagnostics(timestamp: Long): String = runCatching {
            val records = authority.javaClass.getDeclaredField("registrations").apply {
                isAccessible = true
            }.get(authority) as Map<*, *>
            val child = records.keys.singleOrNull()
            val controller = child?.javaClass?.getDeclaredField("controller")?.apply {
                isAccessible = true
            }?.get(child)
            val state = controller?.javaClass?.methods?.firstOrNull {
                it.name == "stateSnapshot" && it.parameterCount == 0
            }?.invoke(controller)
            val failure = controller?.javaClass?.getDeclaredField("failure")?.apply {
                isAccessible = true
            }?.get(controller)
            val foreground = controller?.javaClass?.getDeclaredField("foregroundScene")?.apply {
                isAccessible = true
            }?.get(controller)
            val ticket = foreground?.javaClass?.getDeclaredField("ticket")?.apply {
                isAccessible = true
            }?.get(foreground) as? com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
            val ticketResult = ticket?.await(0L)
            "timestamp=$timestamp, mainGate=${mainGate?.isCovered}, " +
                "pending=${mainGate?.hasPendingUltraPermit}, state=$state, failure=$failure, " +
                "ticket=$ticketResult, " +
                "egl=${driver.trace()}, canvas=${LocalProducerSurface.events}"
        }.getOrElse { "main proof diagnostics failed: $it" }

        fun mainAirPlayConfig(pluginUid: String = "xcertplay-main"): AirPlayConfig {
            check(mainOnly)
            prepareAuthorizedChild()
            val stream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
            val panels = AirPlayPhysicalDisplayPanels(listOf(AirPlayPhysicalDisplayPanel(
                widthPixels = 800, heightPixels = 480,
                widthPhysical = 200.0, heightPhysical = 120.0,
                initialVideoStreams = listOf(stream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                    pluginUid, listOf(stream))),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
            )))
            return AirPlayConfig(
                deviceName = "signed-debug-main", deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
                main = AirPlayDisplayConfig(800, 480, widthPhysicalMm = 200,
                    heightPhysicalMm = 120),
                wirelessAudio = true,
                ultra = requireNotNull(ultraConfiguration),
                displayPanels = panels,
                displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
                    panels,
                    { registry.isSameLivePhysicalTarget(version, surface) },
                    { Closeable {} },
                ),
                requireMainUiSyncForDisplayPanels = true,
            )
        }

        fun mainTransport() = AirPlayTransportReadiness(
            secureControlChannel = true, eventChannel = true,
            alternateScreenSink = false,
            readyRcsClientTypes = setOf(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL),
            wirelessSession = true,
        )

        fun mainUiSyncDisplayId(): String = output.uiSyncDisplayId!!.value

        fun changeMainPhysicalGeneration() {
            check(mainOnly)
            requireNotNull(registry.resize(destination, surface, texture, 801, 480))
        }

        fun hasBoundExactMainBatch(): Boolean {
            check(mainOnly)
            val field = authority.javaClass.getDeclaredField("activeIssuedBatch").apply {
                isAccessible = true
            }
            return field.get(authority) != null
        }

        fun sameSessionClusterControlReady(): Boolean {
            check(mainOnly)
            val boundSession = requireNotNull(session)
            val type = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
            val factory = boundSession.javaClass.getDeclaredField("handlerFactory").apply {
                isAccessible = true
            }.get(boundSession) as RcsDataStreamHandlerFactory
            val descriptor = requireNotNull(factory.descriptor(type))
            check(descriptor.probe().isReady)
            val loopback = InetAddress.getByName("127.0.0.1")
            val accessoryReadKey = ByteArray(32) { 0x31 }
            val accessoryWriteKey = ByteArray(32) { 0x32 }
            val stream = RcsDataStream.open(type, accessoryReadKey, accessoryWriteKey,
                loopback, loopback)
            val port = stream.listen()
            val create = descriptor.javaClass.declaredMethods.single {
                it.name.startsWith("create") && it.parameterCount == 1
            }.apply { isAccessible = true }
            val handler = create.invoke(descriptor, stream) as RcsDataStreamHandler
            stream.attach(handler)
            val peer = RcsChannel.connect(InetSocketAddress(loopback, port),
                accessoryWriteKey, accessoryReadKey)
            // iPhone role: its own Reset first, then our plist Reset, its ResetComplete, ours.
            val phone = CarPlayUiSyncPhoneRoleSimulator(output.uiSyncDisplayId!!.value)
            try {
                val replies = ArrayList<ByteArray>()
                phone.handshake { carrier ->
                    peer.sendComm(carrier)
                    listOf(requireNotNull(peer.receiveNext(2_000)).body).also(replies::addAll)
                }
                assertEquals(
                    listOf(CarPlayUiSyncMessageType.RESET, CarPlayUiSyncMessageType.RESET_COMPLETE),
                    replies.map { CarPlayUiSyncCodec.decode(it).message.type },
                )
                check(phone.state == CarPlayUiSyncSessionState.READY)

                // Inspect the router owned by this exact type-130 handler, not the synthetic
                // readiness probe that ran during static provisioning.
                val delegate = handler.javaClass.getDeclaredField("delegate").apply {
                    isAccessible = true
                }.get(handler)
                val outbound = delegate.javaClass.getDeclaredField("outbound").apply {
                    isAccessible = true
                }.get(delegate)
                val ready = outbound.javaClass.declaredMethods.single {
                    it.name.startsWith("readyUiSyncDisplayIds") && it.parameterCount == 0
                }.apply { isAccessible = true }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (System.nanoTime() < deadline) {
                    if (ready.invoke(outbound) == setOf(output.uiSyncDisplayId!!.value)) return true
                    Thread.sleep(5)
                }
                return false
            } finally {
                peer.close()
                stream.close()
                awaitOwnedHandlerRetired(handler)
            }
        }

        /** The phone role on one open, exact type-130 stream after it ACKed the Host raw-5. */
        class AssetlessPhone(
            private val phone: CarPlayUiSyncPhoneRoleSimulator,
            private val peer: RcsChannel,
            request: CarPlayUiSyncCommandEnvelope,
        ) {
            var request: CarPlayUiSyncCommandEnvelope = request
                private set

            /** Phone reopens its session (new epoch), then ACKs the Host's next raw-5 first. */
            fun resetAndAcknowledgeNextRequest() {
                phone.handshake { carrier ->
                    peer.sendComm(carrier)
                    listOf(requireNotNull(peer.receiveNext(2_000)).body)
                }
                val raw5 = requireNotNull(peer.receiveNext(5_000)) { "No raw-5 after Reset" }
                val before = phone.receivedCommands.size
                val ack = phone.receive(raw5.body).single()
                assertEquals(null, phone.lastDropReason)
                assertEquals(before + 1, phone.receivedCommands.size)
                request = phone.receivedCommands.last()
                assertEquals(CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue, request.type)
                peer.sendComm(ack)
            }

            /** Sends one phone command and applies the Host's commandAck for it. */
            fun send(type: CarPlayUiSyncCommandType, data: Any?) {
                peer.sendComm(phone.sendCommand(type, data))
                val ack = requireNotNull(peer.receiveNext(2_000)) { "Host did not ACK $type" }
                assertTrue(phone.receive(ack.body).isEmpty())
                assertEquals(null, phone.lastDropReason)
            }
        }

        /**
         * Like [sameSessionClusterControlReady], but the stream stays open while [block] runs:
         * READY, then the Host's queued signed raw-5 arrives and the phone ACKs it first, as
         * DashBoard does before its assetless delegate replies. Closing the peer afterwards can
         * retire the controller activation, so every assertion belongs inside [block].
         */
        fun withAssetlessClusterControl(block: (AssetlessPhone) -> Unit) {
            check(mainOnly)
            val boundSession = requireNotNull(session)
            val type = RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL
            val factory = boundSession.javaClass.getDeclaredField("handlerFactory").apply {
                isAccessible = true
            }.get(boundSession) as RcsDataStreamHandlerFactory
            val descriptor = requireNotNull(factory.descriptor(type))
            check(descriptor.probe().isReady)
            val loopback = InetAddress.getByName("127.0.0.1")
            val accessoryReadKey = ByteArray(32) { 0x41 }
            val accessoryWriteKey = ByteArray(32) { 0x42 }
            val stream = RcsDataStream.open(type, accessoryReadKey, accessoryWriteKey,
                loopback, loopback)
            val port = stream.listen()
            val create = descriptor.javaClass.declaredMethods.single {
                it.name.startsWith("create") && it.parameterCount == 1
            }.apply { isAccessible = true }
            val handler = create.invoke(descriptor, stream) as RcsDataStreamHandler
            stream.attach(handler)
            val peer = RcsChannel.connect(InetSocketAddress(loopback, port),
                accessoryWriteKey, accessoryReadKey)
            val phone = CarPlayUiSyncPhoneRoleSimulator(output.uiSyncDisplayId!!.value)
            try {
                phone.handshake { carrier ->
                    peer.sendComm(carrier)
                    listOf(requireNotNull(peer.receiveNext(2_000)).body)
                }
                check(phone.state == CarPlayUiSyncSessionState.READY)
                val raw5 = requireNotNull(peer.receiveNext(3_000)) { "No Host raw-5 after READY" }
                val ack = phone.receive(raw5.body).single()
                assertEquals(null, phone.lastDropReason)
                val request = phone.receivedCommands.single()
                peer.sendComm(ack)
                block(AssetlessPhone(phone, peer, request))
            } finally {
                peer.close()
                stream.close()
                awaitOwnedHandlerRetired(handler)
            }
        }

        /**
         * Deterministic join with the stream's own termination. When the peer's EOF wins, the
         * RCS auth thread runs terminate()/onStreamClosed()/handler.close() asynchronously and
         * stream.close() returns at once. A Fixture session close racing that callback gets a
         * retryable "callbacks in flight" refusal (production retains and retries that
         * retirement); this helper instead waits for the exact handler's own retirement.
         */
        private fun awaitOwnedHandlerRetired(handler: RcsDataStreamHandler) {
            var type: Class<*>? = handler.javaClass
            while (type != null && type.declaredFields.none { it.name == "closed" }) {
                type = type.superclass
            }
            val owner = requireNotNull(type) { "Session-owned RCS handler has no closed state" }
            val lock = owner.getDeclaredField("lock").apply { isAccessible = true }.get(handler)
            val closed = owner.getDeclaredField("closed").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!synchronized(lock) { closed.getBoolean(handler) }) {
                check(System.nanoTime() < deadline) {
                    "Synthetic type-130 stream never retired its session-owned handler"
                }
                Thread.sleep(2L)
            }
        }

        private fun diagnostics(): String = runCatching {
            fun Any.field(name: String): Any? = javaClass.getDeclaredField(name).apply {
                isAccessible = true
            }.get(this)
            val registrations = authority.field("registrations") as Map<*, *>
            val child = registrations.keys.single()
            val owner = requireNotNull(child).field("instrumentOwner")!!
            val handle = owner.field("active")
            val proof = owner.field("latestProof")
            val batch = authority.field("activeIssuedBatch")
            val egl = sessionBackend?.field("renderThread")
            val replayOutbox = egl?.field("clusterReplayOutbox")
            val latestReplay = replayOutbox?.field("latest")
            val subscriber = replayOutbox?.field("subscriber")
            val status = if (handle != null && latestReplay != null) {
                owner.javaClass.declaredMethods.single {
                    it.name == "safeReplayAdmissionStatus" && it.parameterCount == 2
                }.apply { isAccessible = true }.invoke(owner, handle, latestReplay)
            } else null
            val coordinator = child.field("coordinator") as AndroidUltraRendererSessionCoordinator
            "registrations=${registrations.size}, batch=$batch, " +
                "handle=${handle != null}, sceneRevision=${handle?.field("latestSceneRevision")}, " +
                "clearSequence=${handle?.field("initialSafetyClearSequence")}, " +
                "pendingReplay=${handle?.field("pendingReplay")}, proof=${proof != null}, " +
                "eglReplay=${latestReplay != null}, outboxRevoked=${subscriber?.field("revoked")}, " +
                "replayStatus=$status, replay=${latestReplay?.let { replay ->
                    listOf("sceneRevision", "graphRevision", "instrumentFrameSequence",
                        "nativeOutputBatchGeneration", "nativeOutputGeneration",
                        "instrumentIngressGeneration", "producerTimestampNanos", "cause",
                        "instrumentOnlyClusterScene").associateWith { replay.field(it) }
                }}, coordinator=${coordinator.stateSnapshot()}, coordinatorOpens=$coordinatorOpens"
        }.getOrElse { "diagnosticFailure=${it.javaClass.simpleName}:${it.message}" }

        fun revokeAa05() = receipt.second()

        private fun Any.reflectField(name: String): Any? =
            javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

        private fun provisionedSession(): Any =
            requireNotNull((ui.reflectField("sessions") as Map<*, *>).keys.single())

        /** Shared RCS-thread entry: Authority phase 1, controller/owner, then Host cover ticket. */
        fun resetClusterPhoneEpoch(): Boolean {
            val session = provisionedSession()
            val invalidate = session.javaClass.declaredMethods.single {
                it.name.startsWith("invalidatePhoneEpoch") && it.parameterCount == 1
            }.apply { isAccessible = true }
            return invalidate.invoke(session, output.uiSyncDisplayId!!.value) as Boolean
        }

        /** The exact runtime-owned hub the router would feed with a reduced type-3 command. */
        fun sendClusterLayout(layoutId: String, paletteId: String) {
            val hub = provisionedSession().reflectField("presentationHub") as UltraPresentationHub
            hub.sink(output.uiSyncDisplayId!!).onPresentationEvent(
                CarPlayUiSyncPresentationEvent.KnownCommand(
                    CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                    mapOf("layoutID" to layoutId,
                        "activeComponents" to emptyMap<String, String>(),
                        "paletteID" to paletteId),
                ))
        }

        fun activeIssuedBatch(): Any? = authority.reflectField("activeIssuedBatch")

        fun sceneRevision(): Long {
            val child = (authority.reflectField("registrations") as Map<*, *>).keys.single()
            val coordinator = requireNotNull(child).reflectField("coordinator") as
                AndroidUltraRendererSessionCoordinator
            return coordinator.stateSnapshot().lastSubmittedRenderRevision
        }

        override fun close() {
            if (closed) return
            closeSessionWithRetry()
            closed = true
            activation.close()
            canvasPainter?.close()
            registration?.close()
            mainRegistration?.close()
            receipt.third.close()
        }

        private fun closeSessionWithRetry() {
            val ownedSession = session ?: return
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L)
            var firstFailure: Throwable? = null
            for (attempt in 1..MAX_SESSION_CLOSE_ATTEMPTS) {
                try {
                    ownedSession.close()
                    return
                } catch (problem: Throwable) {
                    if (firstFailure == null) firstFailure = problem
                    else if (firstFailure !== problem) firstFailure.addSuppressed(problem)
                    val retryable = problem is IllegalStateException &&
                        problem.message == "Owned ingress retirement is not proven: INTERRUPTED"
                    if (!retryable || attempt == MAX_SESSION_CLOSE_ATTEMPTS ||
                        Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) {
                        throwCloseFailure(requireNotNull(firstFailure), attempt)
                    }
                    try {
                        Thread.sleep(25L)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        requireNotNull(firstFailure).addSuppressed(interrupted)
                        throwCloseFailure(firstFailure, attempt)
                    }
                }
            }
        }

        private fun throwCloseFailure(original: Throwable, attempts: Int): Nothing {
            val diagnostics = if (Thread.currentThread().isInterrupted) "skipped: interrupted" else
                runCatching { sessionBackend?.diagnostics() }.fold(
                    onSuccess = { snapshot ->
                        "phase=${snapshot?.phase}, failure=${snapshot?.failure?.javaClass?.simpleName}:" +
                            "${snapshot?.failure?.message}"
                    },
                    onFailure = { "unavailable: ${it.javaClass.simpleName}: ${it.message}" },
                )
            original.addSuppressed(IllegalStateException(
                "Fixture close failed after $attempts attempt(s); " +
                    "threadInterrupted=${Thread.currentThread().isInterrupted}; EGL=$diagnostics",
            ))
            throw original
        }

        companion object {
            const val MAX_SESSION_CLOSE_ATTEMPTS = 5
        }
    }

    private class Preparation(
        request: UltraProfileActivationRequest,
        override val verifiedProfile: VerifiedUltraProfile,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val resources: Resources,
        private val sealer: AuthorizedAndroidPhysicalOutputSealer,
        private val mainOnly: Boolean,
    ) : AuthorizedUltraProfilePreparation {
        override val requestIdentity = request.runtimeIdentity
        override val authenticationIdentity = request.authenticationIdentity
        private var transferred = false
        override fun commit(verificationIdentity: UltraProfileVerificationIdentity):
            AuthorizedUltraProfileBackendLease {
            transferred = true
            return BackendLease(verificationIdentity, compiledManifest, resources, sealer,
                mainOnly)
        }
        override fun close() { if (!transferred) resources.close() }
    }

    private class BackendLease(
        override val verificationIdentity: UltraProfileVerificationIdentity,
        override val compiledManifest: AuthorizedUltraCompiledRuntimeManifest,
        private val resources: Resources,
        private val sealer: AuthorizedAndroidPhysicalOutputSealer,
        private val mainOnly: Boolean,
    ) : AuthorizedUltraProfileBackendLease {
        private val live = AtomicBoolean(true)
        override val isLive get() = live.get()
        override fun openProvisioning(): AuthorizedUltraProfileBackendProvisioning {
            check(live.get())
            return object : AuthorizedUltraProfileBackendProvisioning {
                private val childLive = AtomicBoolean(true)
                override val verificationIdentity = this@BackendLease.verificationIdentity
                override val compiledManifest = this@BackendLease.compiledManifest
                override val isLive get() = childLive.get() && live.get()
                override val uiSyncProvisioning get() = resources.ui
                override val cafProvisioning get() = resources.bridge.provisioning
                override val instrumentReadSource: VehicleStateReadSource? get() =
                    if (mainOnly) null else resources.source
                override val physicalOutputSealer get() = sealer
                override fun close() { childLive.set(false); resources.close() }
            }
        }
        override fun close() { live.set(false); resources.close() }
    }

    private class Resources(
        val ui: UiSyncRuntimeProvisioning,
        private val callbacks: BoundedUiSyncPresentationExecutor,
        val bridge: AndroidCarPropertyCafProfileBridge,
        val source: UnavailableSource,
        private val authority: AuthorizedAndroidUiSyncPhysicalOutputAuthority,
    ) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                authority.revoke()
                ui.close()
                callbacks.close()
                bridge.close()
                source.close()
            }
        }
    }

    private class UnavailableSource(
        override val configTree: com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree,
    ) : VehicleStateReadSource, Closeable {
        private val live = AtomicBoolean(true)
        override val isCurrent get() = live.get()
        override fun read(iids: Set<Long>): VehicleStateResult {
            return VehicleStateResult(emptyMap())
        }
        override fun subscribe(iids: Set<Long>, listener: VehicleStateListener) =
            VehicleStateSubscription { }
        override fun close() { live.set(false) }
    }

    private class TestHandoff(private val mainOnly: Boolean) : PhysicalLegacySurfaceHandoffPort {
        override fun retireAndReserve(type: Int, surface: Surface): PhysicalLegacyHandoffResult {
            check(type == if (mainOnly) 110 else 111)
            return PhysicalLegacyHandoffResult.Reserved(object : PhysicalLegacySurfaceReservation {
                private var held = true
                private var externalUse: Any? = null
                private var retired = false
                override val isHeld get() = held
                override fun beginExternalUse(): Any = Any().also { externalUse = it }
                override fun confirmExternalUseRetired(token: Any) {
                    check(token === externalUse)
                    retired = true
                }
                override fun close() {
                    check(externalUse == null || retired)
                    held = false
                }
            })
        }
    }

    private class ManualWatchdog : ExternalClusterUiCoverWatchdogScheduler {
        @Volatile private var callback: (() -> Unit)? = null
        override fun schedule(recheck: () -> Unit): Closeable {
            callback = recheck
            return Closeable { if (callback === recheck) callback = null }
        }
        fun tick() = requireNotNull(callback).invoke()
    }

    private class TimestampDriver(private val allowLocalCanvas: Boolean = false) : UltraEglDriver {
        private class Ingress(override val producerEndpoint: Any = Any()) : UltraEglDriverIngress {
            override var hasUsableFrame = false
        }
        private class Output(val destination: DisplayDestination,
            val size: PixelSize) : UltraEglDriverOutput
        val uploads = CopyOnWriteArrayList<IntArray>()
        private val finishedFrame = CountDownLatch(1)
        private val events = CopyOnWriteArrayList<String>()
        @Volatile private var timestamp = 100L
        @Volatile private var lastLocalTimestamp = 0L
        override fun initialize() = Unit
        override fun createExternalIngress(key: UltraEglIngressKey, sizePixels: PixelSize?,
            onFrameAvailable: (UltraEglDriverIngress) -> Unit): UltraEglDriverIngress =
            if (allowLocalCanvas && key is UltraEglIngressKey.Local) {
                val surface = unsafe.allocateInstance(LocalProducerSurface::class.java)
                    as LocalProducerSurface
                Ingress(surface).also { ingress ->
                    surface.onPost = {
                        ingress.hasUsableFrame = true
                        onFrameAvailable(ingress)
                    }
                }
            } else error("No external ingress in instrument-only scene")
        override fun createInstrumentIngress(destination: DisplayDestination,
            sizePixels: PixelSize): UltraEglDriverIngress = Ingress()
        override fun uploadInstrumentFrame(ingress: UltraEglDriverIngress, argbPixels: IntArray) {
            check(ingress is Ingress && argbPixels.size == 800 * 480)
            uploads += argbPixels.copyOf()
            ingress.hasUsableFrame = true
            events += "upload"
        }
        override fun updateIngressFrame(ingress: UltraEglDriverIngress) {
            check(allowLocalCanvas && ingress is Ingress && ingress.hasUsableFrame) {
                "No SurfaceTexture ingress"
            }
        }
        override fun createWindowOutput(destination: DisplayDestination, nativeWindow: Any,
            sizePixels: PixelSize): UltraEglDriverOutput = Output(destination, sizePixels)
        override fun validateOutputSize(output: UltraEglDriverOutput, expectedSize: PixelSize) {
            check(output is Output && output.size == expectedSize)
        }
        override fun render(output: UltraEglDriverOutput, scene: UltraEglResolvedScene) {
            check(output is Output && output.destination == scene.destination)
            if (!allowLocalCanvas) check(scene.layers.all {
                it.ingress?.hasUsableFrame == true
            })
        }
        override fun renderWithPresentationReceipt(output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene): Long {
            render(output, scene)
            events += "swap"
            timestamp++
            return timestamp
        }
        override fun renderWithLocalDrawPresentationReceipt(
            output: UltraEglDriverOutput,
            scene: UltraEglResolvedScene,
        ): UltraEglDriverRenderedFrame {
            check(allowLocalCanvas)
            render(output, scene)
            val draws = scene.layers.mapIndexedNotNull { index, resolved ->
                val local = resolved.layer.source as? UltraSceneSource.Local
                    ?: return@mapIndexedNotNull null
                check(resolved.ingress?.hasUsableFrame == true)
                UltraEglLocalLayerDraw(scene.destination,
                    UltraEglIngressKey.Local(local.contentId), index, resolved.layer)
            }.toSet()
            events += "local-swap"
            timestamp++
            lastLocalTimestamp = timestamp
            return UltraEglDriverRenderedFrame(draws, timestamp)
        }
        override fun clear(output: UltraEglDriverOutput, destination: DisplayDestination,
            sizePixels: PixelSize) = Unit
        override fun finish() {
            events += "finish"
            if (events.contains("swap")) finishedFrame.countDown()
        }
        override fun destroyIngress(ingress: UltraEglDriverIngress) = Unit
        override fun destroyOutput(output: UltraEglDriverOutput) = Unit
        override fun closeWithProof(deadlineNanos: Long) = UltraEglDriverRetirementProof(true)
        fun awaitFinishedInstrumentFrame(): Long {
            check(finishedFrame.await(5, TimeUnit.SECONDS)) { "No EGL swap/finish" }
            val trace = events.toList()
            check(trace.indexOf("upload") >= 0 && trace.indexOf("upload") < trace.indexOf("swap") &&
                trace.indexOf("swap") < trace.lastIndexOf("finish")) { "Wrong EGL order: $trace" }
            return timestamp
        }
        fun awaitFinishedLocalCanvasFrame(): Long {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (allowLocalCanvas && System.nanoTime() < deadline) {
                val trace = events.toList()
                if (trace.indexOf("local-swap") >= 0 &&
                    trace.lastIndexOf("finish") > trace.indexOf("local-swap") &&
                    lastLocalTimestamp > 0L) break
                Thread.sleep(5L)
            }
            check(lastLocalTimestamp > 0L &&
                events.lastIndexOf("finish") > events.indexOf("local-swap")) {
                "No Local Canvas EGL swap/finish: ${trace()}"
            }
            check(LocalProducerSurface.events.contains("post")) {
                "No signed Local Canvas Surface post: ${LocalProducerSurface.events}"
            }
            return lastLocalTimestamp
        }
        fun currentTimestamp(): Long = timestamp
        fun trace(): List<String> = events.toList()

        /** A later Local Canvas swap/finish than [previous], i.e. a new scene's own frame. */
        fun awaitLocalCanvasFrameAfter(previous: Long): Long {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val candidate = lastLocalTimestamp
                if (candidate > previous && events.lastOrNull() == "finish") return candidate
                Thread.sleep(5L)
            }
            error("No Local Canvas frame after $previous: ${trace()}")
        }
    }

    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }
    private class LocalProducerSurface : Surface(null as SurfaceTexture?) {
        companion object { val events = CopyOnWriteArrayList<String>() }
        lateinit var onPost: () -> Unit
        override fun isValid() = true
        override fun lockCanvas(dirty: Rect?): Canvas {
            events += "lock"
            return unsafe.allocateInstance(TestCanvas::class.java) as Canvas
        }
        override fun unlockCanvasAndPost(canvas: Canvas) {
            events += "post"
            onPost()
        }
    }
    private class TestCanvas : Canvas() {
        override fun drawColor(color: Int, mode: PorterDuff.Mode) {
            LocalProducerSurface.events += "clear"
        }
        override fun save(): Int {
            LocalProducerSurface.events += "save"
            return 1
        }
        override fun clipRect(left: Int, top: Int, right: Int, bottom: Int): Boolean {
            LocalProducerSurface.events += "clip"
            return true
        }
        override fun restoreToCount(saveCount: Int) {
            LocalProducerSurface.events += "restore"
        }
    }
    private companion object {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }.get(null) as Unsafe
        fun validSurface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
        fun texture(): SurfaceTexture = unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture

        fun signedProfile(identity: MfiAuthenticationIdentity,
            mainOnly: Boolean = false): VerifiedUltraProfile {
            val samples = BenchInstrumentSignalProfileCompilerTest
            val packageJson = samples.THEME_PACKAGE.replace(
                "\"safeAreaInsets\":{}", "\"safeAreaInsets\":[0,0,0,0]",
            ).replace("\"backgroundColor\":\"black\"",
                "\"backgroundColor\":\"#000000\"").replace(
                "\"backgroundColor\":\"#000000\"}}",
                "\"backgroundColor\":\"#000000\",\"clipsToShape\":false}}",
            )
            val catalog = """{"format":"xcertplay-debug-uisync-v1","displays":[{
                "profileDisplayIndex":0,"profileDisplayId":"cluster-display",
                "advertisedScreenId":"${AirPlayInfoPlist.ALT_UUID}",
                "destinationRole":"CLUSTER","destinationInstanceId":"default",
                "topologyDisplayId":"${AirPlayInfoPlist.ALT_UUID}",
                "outputId":{"kind":"android","value":"host.cluster"},
                "uiSyncDisplayId":"ui.cluster","layouts":[{
                "layoutIndex":0,"layoutID":"main","paletteID":"palette-a",
                "activeComponents":{},"activePrimarySelections":[{"zoneIndex":0,"componentIndex":0}],
                "slotIngresses":[]}]}
              ]}""".trimIndent()
            val files = listOf(
                UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to byteArrayOf(),
                UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageJson.encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                    BplistCodec.encode(mapOf("Bundle" to "DEBUG.BENCH")),
                BenchUiSyncProfileCompiler.SPEC_PATH to catalog.encodeToByteArray(),
                BenchCafProfileCompiler.SPEC_PATH to samples.CAF_SPEC.encodeToByteArray(),
                BenchInstrumentSignalProfileCompiler.SPEC_PATH to
                    samples.SPEED_SPEC.encodeToByteArray(),
                UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(2),
                UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(3),
                UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(4),
                UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(5),
                UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to "1")),
            )
            val archive = if (mainOnly) generatedMainArchive() else ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    files.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
            val inspector = UltraProfilePackageInspector()
            val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
                UltraProfileInspectionResult.Candidate).profile
            val manifest = candidate.manifest.values
            val binding = UltraProfileTrustBinding(profileVehicleId,
                "xcertplay-mfi-authentication-v1:${identity.certificateType.name}:${identity.sha256Hex()}",
                if (mainOnly) requireNotNull(manifest["AssetIdentifier"] as? String) else "asset",
                if (mainOnly) requireNotNull(manifest["iOSContentVersion"] as? String) else "ios",
                if (mainOnly) requireNotNull(manifest["AccessoryContentVersion"] as? String)
                    else "accessory", candidate.contentDigest)
            val localSignature = if (mainOnly) {
                val keyPair = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
                val signed = BenchUltraProfileSignatureBinding(profileVehicleId, identity,
                    binding.assetIdentifier, binding.iosContentVersion,
                    binding.accessoryContentVersion, candidate.contentDigest)
                val der = Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(signed.canonicalSignedPayload())
                    sign()
                }
                val envelope = BenchUltraProfileSignatureVerifier
                    .envelopeForDetachedSignature(signed, der)
                check(BenchUltraProfileSignatureVerifier(keyPair.public.encoded)
                    .verify(signed, envelope) ===
                    BenchUltraProfileSignatureResult.MatchesUserPinnedKeyOnly) {
                    "Self-built Debug MAIN Profile detached signature was rejected"
                }
                envelope
            } else byteArrayOf(1)
            return (inspector.verify(candidate, binding) {
                UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding,
                    localSignature))
            } as UltraProfileVerificationResult.Verified).profile
        }

        fun generatedMainArchive(): ByteArray {
            val script = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
                it.parentFile
            }.map { File(it, "scripts/create_debug_bench_profile.py") }
                .firstOrNull(File::isFile)
            check(script != null) { "Debug MAIN Profile generator is absent" }
            val directory = Files.createTempDirectory("xcertplay-signed-main-preflight-")
            val output = directory.resolve("profile.zip")
            try {
                val process = ProcessBuilder("python3", script.absolutePath,
                    "--main-local-canvas", output.toString())
                    .redirectErrorStream(true).start()
                val diagnostic = process.inputStream.bufferedReader().use { it.readText() }
                check(process.waitFor() == 0) { diagnostic }
                return Files.readAllBytes(output)
            } finally {
                Files.deleteIfExists(output)
                Files.deleteIfExists(directory)
            }
        }

        const val profileVehicleId = "vehicle"

        fun receipt(identity: MfiAuthenticationIdentity): Triple<MfiAuthenticationReceipt,
            () -> Unit, Closeable> {
            val type = Class.forName("com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority")
            val authority = type.declaredConstructors.single { it.parameterCount == 1 }
                .apply { isAccessible = true }.newInstance(identity)
            val begin = type.declaredMethods.single { it.name.startsWith("begin") &&
                !it.name.startsWith("beginHandoff") && it.parameterCount == 2
            }.apply { isAccessible = true }
            val channel = Any()
            val attempt = begin.invoke(authority, channel, 1L)
            val issue = attempt.javaClass.declaredMethods.single {
                it.name.startsWith("issue") && it.parameterCount == 2
            }.apply { isAccessible = true }
            return Triple(issue.invoke(attempt, channel, identity) as MfiAuthenticationReceipt,
                { begin.invoke(authority, Any(), 2L); Unit }, authority as Closeable)
        }
    }
}
