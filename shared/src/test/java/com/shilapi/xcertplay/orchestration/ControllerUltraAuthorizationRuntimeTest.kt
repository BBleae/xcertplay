package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanel
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanels
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanelsLiveness
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPlugin
import com.shilapi.xcertplay.airplay.AirPlayPhysicalVideoStream
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayUiSyncRendererReadinessDescriptor
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncChannelDescriptor
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncEndpoint
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMode
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncPresentationEventSink
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncBindingFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncCompleteRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestination
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalDestinationRole
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputAssignment
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputPreflightContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRequirement
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalOutputRoute
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalTargetVersion
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererBindingContext
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncRendererStaticPlan
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSchemaFingerprint
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncSemanticThemeProgramFingerprint
import com.shilapi.xcertplay.mfi.MfiAuthenticationHandoff
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.orchestration.ControllerUltraAuthorizationRuntime.ReceiptPublication.PUBLISHED
import com.shilapi.xcertplay.orchestration.ControllerUltraAuthorizationRuntime.ReceiptPublication.REJECTED
import com.shilapi.xcertplay.orchestration.ControllerUltraAuthorizationRuntime.ReceiptPublication.RETIRED_AFTER_DENIAL
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraTopologyDisplayId
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerUltraAuthorizationRuntimeTest {
    @Test
    fun unavailableFallbackDoesNotConsumeOrAuthorizeTheReceipt() {
        ReceiptFixture(1).use { receipts ->
            val receipt = receipts.initialReceipt()
            val lifecycle = FakeAuthorizationLifecycle(
                activationResult = CarPlayUltraAuthorizationResult.Unavailable,
            )
            val fixture = runtimeFixture(lifecycle)
            val published = mutableListOf<MfiAuthenticationReceipt>()

            val resolution = fixture.runtime.activateAndResolve(receipt) { exact ->
                published += exact
                PUBLISHED
            }

            assertTrue(resolution is ControllerUltraAuthorizationRuntime.Resolution.Unavailable)
            assertSame(receipt, published.single())
            assertSame(fixture.baseline, resolution.snapshot.config)
            assertNull(resolution.snapshot.config.ultra)
            assertNull(resolution.snapshot.ultraRuntime)
            assertEquals(0, lifecycle.currentChecks.size)
            assertFalse(fixture.runtime.allowsClusterAssetAcquisition("signed.asset", receipts.identity))
            assertTrue(
                fixture.runtime.tunnelAuthentication(receipt) ===
                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
            )

            // Unavailable must be reported before the lifecycle consumes the exact AA05 receipt.
            assertSame(
                receipts.identity,
                receipt.consumeForUltraActivation { },
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun clusterAssetProbeRequiresExactAuthorizedReceiptAndExplicitLifecycleGrant() {
        ReceiptFixture(84).use { receipts ->
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(
                activation, activation, allowedAssetId = "signed.asset",
            )
            val fixture = runtimeFixture(lifecycle)
            assertFalse(fixture.runtime.allowsClusterAssetAcquisition("signed.asset", receipts.identity))
            val receipt = receipts.initialReceipt()
            fixture.runtime.activateAndResolve(receipt) { PUBLISHED }

            assertTrue(fixture.runtime.allowsClusterAssetAcquisition("signed.asset", receipts.identity))
            assertFalse(fixture.runtime.allowsClusterAssetAcquisition("other.asset", receipts.identity))
            assertFalse(fixture.runtime.allowsClusterAssetAcquisition(" ", receipts.identity))
            ReceiptFixture(85).use { other ->
                assertFalse(fixture.runtime.allowsClusterAssetAcquisition("signed.asset", other.identity))
            }
            fixture.runtime.revokeAdmission()
            assertFalse(fixture.runtime.allowsClusterAssetAcquisition("signed.asset", receipts.identity))
            fixture.runtime.close()
        }
    }

    @Test
    fun clusterAssetProbeRevokedInsideLifecycleCallbackDoesNotHoldControllerGate() {
        ReceiptFixture(86).use { receipts ->
            val activation = activation()
            lateinit var fixture: RuntimeFixture
            val lifecycle = FakeAuthorizationLifecycle(
                activation, activation, allowedAssetId = "signed.asset",
                onAssetProbe = { fixture.runtime.revokeAdmission() },
            )
            fixture = runtimeFixture(lifecycle)
            fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }

            assertFalse(fixture.runtime.allowsClusterAssetAcquisition("signed.asset", receipts.identity))
            fixture.runtime.close()
        }
    }

    @Test
    fun deniedFallbackPublishesNoAuthorizedRuntime() {
        ReceiptFixture(2).use { receipts ->
            val receipt = receipts.initialReceipt()
            val denial = CarPlayUltraAuthorizationResult.Denied(
                CarPlayUltraAuthorizationDenialCode.PROVIDER_UNAVAILABLE,
            )
            val lifecycle = FakeAuthorizationLifecycle(activationResult = denial)
            val fixture = runtimeFixture(lifecycle)

            val resolution = fixture.runtime.activateAndResolve(receipt) {
                if (it === receipt) PUBLISHED else REJECTED
            }
                as ControllerUltraAuthorizationRuntime.Resolution.Denied

            assertSame(denial, resolution.denial)
            assertSame(fixture.baseline, resolution.snapshot.config)
            assertNull(resolution.snapshot.config.ultra)
            assertNull(resolution.snapshot.ultraRuntime)
            assertEquals(0, lifecycle.currentChecks.size)
            assertSame(
                resolution.snapshot,
                fixture.binding.withResolvedAdmission(fixture.generation) { it },
            )
            assertTrue(
                fixture.runtime.tunnelAuthentication(receipt) ===
                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun deniedRetiredReceiptCanBootstrapOneFreshOrdinaryFallbackReceipt() {
        ReceiptFixture(19).use { receipts ->
            val denied = CarPlayUltraAuthorizationResult.Denied(
                CarPlayUltraAuthorizationDenialCode.PROVIDER_FAILED,
            )
            val lifecycle = object : CarPlayUltraAuthorizationLifecycle {
                override fun activate(
                    receipt: MfiAuthenticationReceipt,
                    sessionManagementInfo: com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo?,
                ): CarPlayUltraAuthorizationResult {
                    checkNotNull(receipt.consumeForUltraActivation { })
                    receipt.revoke()
                    return denied
                }

                override fun hasRenewableSession(predecessor: MfiAuthenticationReceipt) = false

                override fun isCurrentActivation(
                    activation: CarPlayUltraAuthorizationResult.Activated,
                    receipt: MfiAuthenticationReceipt,
                ) = false

                override fun prepareRenewal(
                    predecessor: MfiAuthenticationReceipt,
                    successor: MfiAuthenticationReceipt,
                ): CarPlayUltraPreparedRenewal = error("not authorized")

                override fun close() = Unit
            }
            val fixture = runtimeFixture(lifecycle)
            val retired = receipts.initialReceipt()
            val resolution = fixture.runtime.activateAndResolve(retired) {
                RETIRED_AFTER_DENIAL
            }

            assertTrue(resolution is ControllerUltraAuthorizationRuntime.Resolution.Denied)
            assertTrue(
                fixture.runtime.tunnelAuthentication(null) ===
                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
            )
            val successor = receipts.initialReceipt()
            fixture.runtime.promoteFallbackReceipt(null, successor) { true }
            assertTrue(
                fixture.runtime.tunnelAuthentication(successor) ===
                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun wirelessDynamicBaselineIsTheExactResolvedFallbackConfig() {
        ReceiptFixture(20).use { receipts ->
            val receipt = receipts.initialReceipt()
            val lifecycle = FakeAuthorizationLifecycle(
                activationResult = CarPlayUltraAuthorizationResult.Unavailable,
            )
            val fixture = runtimeFixture(lifecycle)
            val wireless = fixture.baseline.copy(
                deviceId = "AA:BB:CC:DD:EE:FF",
                btMac = "11:22:33:44:55:66",
                wirelessAudio = true,
            )

            val resolution = fixture.runtime.activateAndResolve(
                receipt = receipt,
                resolvedBaselineConfig = wireless,
                publishReceipt = { if (it === receipt) PUBLISHED else REJECTED },
            )

            assertSame(wireless, resolution.snapshot.config)
            assertEquals("AA:BB:CC:DD:EE:FF", resolution.snapshot.config.deviceId)
            assertEquals("11:22:33:44:55:66", resolution.snapshot.config.btMac)
            assertTrue(resolution.snapshot.config.wirelessAudio)
            fixture.runtime.close()
        }
    }

    @Test
    fun fallbackStripsUnreadyPhysicalPanelsBeforeBindingPublication() {
        ReceiptFixture(70).use { receipts ->
            val baseline = baselineConfig().withLiveMainPhysicalPanel()
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(), baseline)
            val resolution = fixture.runtime.activateAndResolve(receipts.initialReceipt()) {
                assertNull(fixture.binding.acquire(fixture.generation))
                PUBLISHED
            }

            assertTrue(resolution is ControllerUltraAuthorizationRuntime.Resolution.Unavailable)
            assertTrue(resolution.snapshot.config !== baseline)
            assertNull(resolution.snapshot.config.displayPanels)
            assertNull(resolution.snapshot.config.displayPanelsLiveness)
            assertTrue(resolution.snapshot.config.requireMainUiSyncForDisplayPanels)
            assertSame(baseline.main, resolution.snapshot.config.main)
            assertSame(baseline.displayPanels, fixture.baseline.displayPanels)
            fixture.runtime.close()
        }
    }

    @Test
    fun authorizedWithoutMainUiSyncAlsoStripsPhysicalPanels() {
        ReceiptFixture(71).use { receipts ->
            val baseline = baselineConfig().withLiveMainPhysicalPanel()
            val activation = activation()
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(activation, activation), baseline)

            val resolution = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }
                as ControllerUltraAuthorizationRuntime.Resolution.Authorized

            assertNull(resolution.snapshot.config.displayPanels)
            assertNull(resolution.snapshot.config.displayPanelsLiveness)
            assertTrue(resolution.snapshot.config.requireMainUiSyncForDisplayPanels)
            assertSame(activation.configuration, resolution.snapshot.config.ultra)
            assertSame(activation.runtime, resolution.snapshot.ultraRuntime)
            fixture.runtime.close()
        }
    }

    @Test
    fun explicitDebugDiagnosticCanPreserveDdpOnlyPhysicalPanels() {
        ReceiptFixture(72).use { receipts ->
            val baseline = baselineConfig().withLiveMainPhysicalPanel()
            val fixture = runtimeFixture(
                FakeAuthorizationLifecycle(), baseline,
                allowDiagnosticPhysicalPanelsWithoutUiSync = true,
            )

            val resolution = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }

            assertSame(baseline, resolution.snapshot.config)
            assertSame(baseline.displayPanels, resolution.snapshot.config.displayPanels)
            assertSame(baseline.displayPanelsLiveness, resolution.snapshot.config.displayPanelsLiveness)
            assertFalse(resolution.snapshot.config.requireMainUiSyncForDisplayPanels)
            assertTrue(AirPlayInfoPlist.build(resolution.snapshot.config).containsKey("displayPanels"))
            fixture.runtime.close()
        }
    }

    @Test
    fun readyMainUiSyncRetainsPhysicalPanelsWithoutDiagnosticOverride() {
        ReceiptFixture(73).use { receipts ->
            val baseline = baselineConfig().withLiveMainPhysicalPanel()
            val activation = activation(mainUiSyncUltra())
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(activation, activation), baseline)

            val resolution = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }
                as ControllerUltraAuthorizationRuntime.Resolution.Authorized

            assertSame(baseline.displayPanels, resolution.snapshot.config.displayPanels)
            assertSame(baseline.displayPanelsLiveness, resolution.snapshot.config.displayPanelsLiveness)
            assertTrue(resolution.snapshot.config.requireMainUiSyncForDisplayPanels)
            val local = AirPlayFeatureNegotiation.snapshot(resolution.snapshot.config)
            assertTrue(local.mainDdpUiSyncReady)
            assertTrue(local.isReady(AirPlayFeature.UI_SYNC))
            val infoFirst = AirPlayInfoPlist.build(resolution.snapshot.config)
            assertFalse(infoFirst.containsKey("displayPanels"))
            assertFalse(infoFirst.containsKey("uiSyncInfo"))
            val acceptedInfo = AirPlayInfoPlist.build(
                resolution.snapshot.config, setOf("uiContext", "uiSync"),
            )
            assertTrue(acceptedInfo.containsKey("displayPanels"))
            assertTrue(acceptedInfo.containsKey("uiSyncInfo"))
            fixture.runtime.close()
        }
    }

    @Test
    fun readyMainAndClusterUiSyncRetainsBothSignedPhysicalPanels() {
        ReceiptFixture(76).use { receipts ->
            val baseline = baselineConfig().copy(
                cluster = AirPlayDisplayConfig(widthPixels = 400, heightPixels = 240),
            ).withLiveMainPhysicalPanel(includeCluster = true)
            val activation = activation(mainUiSyncUltra(includeCluster = true))
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(activation, activation), baseline)

            val resolution = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }
                as ControllerUltraAuthorizationRuntime.Resolution.Authorized

            assertSame(baseline.displayPanels, resolution.snapshot.config.displayPanels)
            assertTrue(resolution.snapshot.config.requireMainUiSyncForDisplayPanels)
            assertTrue(AirPlayFeatureNegotiation.snapshot(resolution.snapshot.config).mainDdpUiSyncReady)
            assertFalse(AirPlayInfoPlist.build(resolution.snapshot.config).containsKey("displayPanels"))
            val acceptedInfo = AirPlayInfoPlist.build(
                resolution.snapshot.config, setOf("uiContext", "altScreen", "uiSync"),
            )
            assertEquals(2, (acceptedInfo["displayPanels"] as List<*>).size)
            fixture.runtime.close()
        }
    }

    @Test
    fun mainAndClusterUiSyncDoesNotRetainMainDdpWhenClusterPanelIsMissing() {
        ReceiptFixture(77).use { receipts ->
            val baseline = baselineConfig().copy(
                cluster = AirPlayDisplayConfig(widthPixels = 400, heightPixels = 240),
            ).withLiveMainPhysicalPanel()
            val activation = activation(mainUiSyncUltra(includeCluster = true))
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(activation, activation), baseline)

            val resolution = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }
                as ControllerUltraAuthorizationRuntime.Resolution.Authorized

            assertNull(resolution.snapshot.config.displayPanels)
            assertTrue(resolution.snapshot.config.requireMainUiSyncForDisplayPanels)
            fixture.runtime.close()
        }
    }

    @Test
    fun physicalPanelLivenessLossBeforeInfoOmitsDdpEvenWithOldFeatureScope() {
        ReceiptFixture(74).use { receipts ->
            val physicalCurrent = AtomicBoolean(true)
            val baseline = baselineConfig().withLiveMainPhysicalPanel(physicalCurrent::get)
            val activation = activation(mainUiSyncUltra())
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(activation, activation), baseline)
            val resolved = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }
                .snapshot.config
            val acceptedScope = AirPlayFeatureNegotiation.snapshot(resolved).infoScope(
                setOf(AirPlayFeature.UI_CONTEXT, AirPlayFeature.UI_SYNC),
            )
            assertTrue(AirPlayInfoPlist.build(resolved, acceptedScope).containsKey("displayPanels"))

            physicalCurrent.set(false)
            assertFalse(AirPlayInfoPlist.build(resolved, acceptedScope).containsKey("displayPanels"))
            fixture.runtime.close()
        }
    }

    @Test
    fun rendererPreflightLossBeforeInfoOmitsDdpWithoutChangingDebugPolicy() {
        ReceiptFixture(75).use { receipts ->
            val rendererCurrent = AtomicBoolean(true)
            val baseline = baselineConfig().withLiveMainPhysicalPanel()
            val activation = activation(mainUiSyncUltra(rendererCurrent::get))
            val fixture = runtimeFixture(FakeAuthorizationLifecycle(activation, activation), baseline)
            val resolved = fixture.runtime.activateAndResolve(receipts.initialReceipt()) { PUBLISHED }
                .snapshot.config
            val acceptedScope = AirPlayFeatureNegotiation.snapshot(resolved).infoScope(
                setOf(AirPlayFeature.UI_CONTEXT, AirPlayFeature.UI_SYNC),
            )
            assertTrue(AirPlayInfoPlist.build(resolved, acceptedScope).containsKey("displayPanels"))

            rendererCurrent.set(false)
            assertFalse(AirPlayInfoPlist.build(resolved, acceptedScope).containsKey("displayPanels"))
            fixture.runtime.close()
        }
    }

    @Test
    fun ordinaryFallbackReceiptPromotionKeepsTheSameSnapshotAndAllowsReconnect() {
        ReceiptFixture(21).use { receipts ->
            val predecessor = receipts.initialReceipt()
            val lifecycle = FakeAuthorizationLifecycle(
                activationResult = CarPlayUltraAuthorizationResult.Unavailable,
            )
            val fixture = runtimeFixture(lifecycle)
            val resolution = fixture.runtime.activateAndResolve(predecessor) { PUBLISHED }
            assertTrue(
                fixture.runtime.tunnelAuthentication(predecessor) ===
                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
            )

            // An ordinary begin supersedes the prior unconsumed receipt before AA05 succeeds.
            val successor = receipts.initialReceipt()
            fixture.runtime.promoteFallbackReceipt(predecessor, successor) { true }

            assertSame(resolution.snapshot, fixture.binding.acquire(fixture.generation))
            assertTrue(
                fixture.runtime.tunnelAuthentication(successor) ===
                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun authorizedPublicationKeepsExactReferencesAndRevalidatesEveryAdmission() {
        ReceiptFixture(3).use { receipts ->
            val receipt = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = activation,
                activationResult = activation,
            )
            val fixture = runtimeFixture(lifecycle)

            val resolution = fixture.runtime.activateAndResolve(receipt) {
                if (it === receipt) PUBLISHED else REJECTED
            }
                as ControllerUltraAuthorizationRuntime.Resolution.Authorized

            assertSame(activation, resolution.activation)
            assertSame(fixture.generation, resolution.snapshot.generation)
            assertSame(activation.configuration, resolution.snapshot.config.ultra)
            assertSame(activation.runtime, resolution.snapshot.ultraRuntime)
            assertEquals(2, lifecycle.currentChecks.size)
            lifecycle.currentChecks.forEach { check ->
                assertSame(activation, check.activation)
                assertSame(receipt, check.receipt)
            }

            repeat(2) {
                assertSame(
                    resolution.snapshot,
                    fixture.binding.withResolvedAdmission(fixture.generation) { it },
                )
            }
            assertEquals(4, lifecycle.currentChecks.size)
            lifecycle.currentChecks.takeLast(2).forEach { check ->
                assertSame(activation, check.activation)
                assertSame(receipt, check.receipt)
            }

            val tunnel = fixture.runtime.tunnelAuthentication(receipt)
                as ControllerUltraAuthorizationRuntime.TunnelAuthentication.AuthorizedHandoff
            assertSame(receipt, tunnel.predecessor)
            fixture.runtime.close()
        }
    }

    @Test
    fun authorizedWirelessBaselineOnlyReplacesUltraAndPreservesDisplayIdentity() {
        ReceiptFixture(31).use { receipts ->
            val receipt = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(activation, activation)
            val main = AirPlayDisplayConfig(
                widthPixels = 1920,
                heightPixels = 720,
                widthPhysicalMm = 312,
                heightPhysicalMm = 117,
                fps = 59,
                viewArea = AirPlayInsets(top = 7, bottom = 8, left = 9, right = 10),
                safeArea = AirPlayInsets(top = 11, bottom = 12, left = 13, right = 14),
                safeAreaDrawOutside = true,
            )
            val cluster = AirPlayDisplayConfig(
                widthPixels = 1280,
                heightPixels = 480,
                widthPhysicalMm = 281,
                heightPhysicalMm = 105,
                fps = 58,
                safeArea = AirPlayInsets(top = 3, bottom = 4, left = 5, right = 6),
            )
            val baseline = baselineConfig().copy(main = main, cluster = cluster)
            val wireless = baseline.copy(
                deviceId = "12:34:56:78:9a:bc",
                btMac = "12:34:56:78:9a:bd",
                wirelessAudio = true,
            )
            val fixture = runtimeFixture(lifecycle, baseline)

            val resolution = fixture.runtime.activateAndResolve(
                receipt = receipt,
                resolvedBaselineConfig = wireless,
                publishReceipt = { if (it === receipt) PUBLISHED else REJECTED },
            ) as ControllerUltraAuthorizationRuntime.Resolution.Authorized
            val published = resolution.snapshot.config

            assertEquals(wireless.deviceId, published.deviceId)
            assertEquals(wireless.btMac, published.btMac)
            assertTrue(published.wirelessAudio)
            assertSame(main, published.main)
            assertSame(cluster, published.cluster)
            assertEquals(312, published.main.widthPhysicalMm)
            assertEquals(117, published.main.heightPhysicalMm)
            assertEquals(59, published.main.fps)
            assertSame(main.viewArea, published.main.viewArea)
            assertSame(main.safeArea, published.main.safeArea)
            assertEquals(281, published.cluster?.widthPhysicalMm)
            assertEquals(105, published.cluster?.heightPhysicalMm)
            assertEquals(58, published.cluster?.fps)
            assertSame(cluster.safeArea, published.cluster?.safeArea)
            assertSame(activation.configuration, published.ultra)
            assertSame(activation.runtime, resolution.snapshot.ultraRuntime)
            fixture.runtime.close()
        }
    }

    @Test
    fun constructedActivatedValueIsRejectedAndSealsTheGeneration() {
        ReceiptFixture(4).use { receipts ->
            val receipt = receipts.initialReceipt()
            val canonical = activation()
            val forged = CarPlayUltraAuthorizationResult.Activated(
                canonical.runtime,
                canonical.configuration,
            )
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = canonical,
                activationResult = forged,
            )
            val fixture = runtimeFixture(lifecycle)

            val failure = assertThrows(IllegalStateException::class.java) {
                fixture.runtime.activateAndResolve(receipt) { PUBLISHED }
            }

            assertTrue(failure.message.orEmpty().contains("exact live authorization"))
            assertEquals(1, lifecycle.currentChecks.size)
            assertSame(forged, lifecycle.currentChecks.single().activation)
            assertNull(fixture.binding.acquire(fixture.generation))
            assertNull(
                fixture.binding.withResolvedAdmission(fixture.generation) {
                    throw AssertionError("a forged activation must never admit a session")
                },
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun authorizedRuntimeCannotAliasTheLegacyFallbackRootOwner() {
        ReceiptFixture(40).use { receipts ->
            val receipt = receipts.initialReceipt()
            val fallback = AirPlayUltraRuntime()
            val fallbackConfig = fallback.configuration(emptySet())
            val baseline = baselineConfig().copy(ultra = fallbackConfig)
            val activation = CarPlayUltraAuthorizationResult.Activated(
                runtime = fallback,
                configuration = fallbackConfig,
            )
            val lifecycle = FakeAuthorizationLifecycle(activation, activation)
            val binding = AirPlaySessionRuntimeBinding()
            val generation = binding.beginGeneration()
            val runtime = ControllerUltraAuthorizationRuntime(
                lifecycle = lifecycle,
                binding = binding,
                generation = generation,
                baselineConfig = baseline,
                fallbackRuntime = fallback,
            )

            val failure = assertThrows(IllegalStateException::class.java) {
                runtime.activateAndResolve(receipt) { PUBLISHED }
            }

            assertTrue(failure.message.orEmpty().contains("different owners"))
            assertNull(binding.acquire(generation))
            runtime.close()
        }
    }

    @Test
    fun rejectedReceiptPublicationLeavesNoSnapshotAndSealsTheBinding() {
        ReceiptFixture(41).use { receipts ->
            val receipt = receipts.initialReceipt()
            val lifecycle = FakeAuthorizationLifecycle(
                activationResult = CarPlayUltraAuthorizationResult.Unavailable,
            )
            val fixture = runtimeFixture(lifecycle)
            val published = mutableListOf<MfiAuthenticationReceipt>()

            val failure = assertThrows(IllegalStateException::class.java) {
                fixture.runtime.activateAndResolve(receipt) { exact ->
                    published += exact
                    REJECTED
                }
            }

            assertTrue(failure.message.orEmpty().contains("rejected the exact AA05 receipt"))
            assertSame(receipt, published.single())
            assertNull(fixture.binding.acquire(fixture.generation))
            assertNull(
                fixture.binding.resolve(
                    fixture.generation,
                    fixture.baseline,
                    ultraRuntime = null,
                ),
            )
            assertNull(
                fixture.binding.withResolvedAdmission(fixture.generation) {
                    throw AssertionError("a rejected receipt must never admit a session")
                },
            )
            fixture.runtime.close()
        }
    }

    @Test
    fun livenessLossDuringAdmissionRevokesThePublishedGenerationPermanently() {
        ReceiptFixture(5).use { receipts ->
            val receipt = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(activation, activation)
            val fixture = runtimeFixture(lifecycle)
            val resolution = fixture.runtime.activateAndResolve(receipt) { PUBLISHED }

            lifecycle.live = false
            val actionRan = AtomicBoolean(false)

            assertNull(
                fixture.binding.withResolvedAdmission(fixture.generation) {
                    actionRan.set(true)
                    Any()
                },
            )
            assertFalse(actionRan.get())
            assertNull(fixture.binding.acquire(fixture.generation))

            // Revocation is irreversible even if an untrusted authority later reports live again.
            lifecycle.live = true
            assertNull(
                fixture.binding.withResolvedAdmission(fixture.generation) {
                    actionRan.set(true)
                    resolution.snapshot
                },
            )
            assertFalse(actionRan.get())
            fixture.runtime.close()
        }
    }

    @Test
    fun nonRenewableAuthorizedSessionFailsClosedWithoutOrdinaryFallback() {
        ReceiptFixture(51).use { receipts ->
            val receipt = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = activation,
                activationResult = activation,
                allowRenewal = false,
            )
            val fixture = runtimeFixture(lifecycle)
            val resolution = fixture.runtime.activateAndResolve(receipt) { PUBLISHED }

            val failure = assertThrows(IllegalStateException::class.java) {
                fixture.runtime.tunnelAuthentication(receipt)
            }

            assertTrue(failure.message.orEmpty().contains("not renewable"))
            assertEquals(1, lifecycle.hasRenewableCalls)
            assertEquals(0, lifecycle.renewCalls)
            assertSame(resolution.snapshot, fixture.binding.acquire(fixture.generation))
            fixture.runtime.close()
        }
    }

    @Test
    fun closeSealsAdmissionBeforeLifecycleCloseAndRetriesTheExactOwner() {
        ReceiptFixture(6).use { receipts ->
            val receipt = receipts.initialReceipt()
            val activation = activation()
            lateinit var fixture: RuntimeFixture
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = activation,
                activationResult = activation,
                closeFailures = 1,
                onClose = {
                    assertNull(fixture.binding.acquire(fixture.generation))
                },
            )
            fixture = runtimeFixture(lifecycle)
            fixture.runtime.activateAndResolve(receipt) { PUBLISHED }

            assertThrows(IOException::class.java) { fixture.runtime.close() }
            assertEquals(1, lifecycle.closeCalls)
            assertSame(lifecycle, lifecycle.closeOwners.single())
            assertNull(fixture.binding.acquire(fixture.generation))
            assertTrue(activation.runtime.isLive())

            fixture.runtime.close()
            assertEquals(2, lifecycle.closeCalls)
            assertTrue(lifecycle.closeOwners.all { it === lifecycle })
            assertFalse(activation.runtime.isLive())

            fixture.runtime.close()
            assertEquals(2, lifecycle.closeCalls)
        }
    }

    @Test
    fun blockedActivationAndCloseRaceCannotRepublishAndCloseRemainsRetryable() {
        ReceiptFixture(61).use { receipts ->
            val receipt = receipts.initialReceipt()
            val activation = activation()
            val activateEntered = CountDownLatch(1)
            val allowActivate = CountDownLatch(1)
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = activation,
                activationResult = activation,
                closeFailures = 1,
                activateEntered = activateEntered,
                allowActivate = allowActivate,
            )
            val fixture = runtimeFixture(lifecycle)
            val activationFailure = AtomicReference<Throwable?>()
            val activationWorker = Thread(
                {
                    try {
                        fixture.runtime.activateAndResolve(receipt) { PUBLISHED }
                    } catch (failure: Throwable) {
                        activationFailure.set(failure)
                    }
                },
                "blocked-ultra-activation-test",
            ).apply { start() }
            assertTrue(activateEntered.await(5, TimeUnit.SECONDS))

            val closeWhileActive = assertThrows(IllegalStateException::class.java) {
                fixture.runtime.close()
            }
            assertTrue(closeWhileActive.message.orEmpty().contains("still in flight"))
            assertEquals(0, lifecycle.closeCalls)
            assertNull(fixture.binding.acquire(fixture.generation))

            allowActivate.countDown()
            activationWorker.join(5_000)
            assertFalse("activation/close race deadlocked", activationWorker.isAlive)
            assertTrue(activationFailure.get() is IllegalStateException)
            assertNull(fixture.binding.acquire(fixture.generation))
            assertNull(
                fixture.binding.withResolvedAdmission(fixture.generation) {
                    throw AssertionError("stale activation republished an admission")
                },
            )

            assertThrows(IOException::class.java) { fixture.runtime.close() }
            assertEquals(1, lifecycle.closeCalls)
            assertSame(lifecycle, lifecycle.closeOwners.single())

            fixture.runtime.close()
            assertEquals(2, lifecycle.closeCalls)
            assertTrue(lifecycle.closeOwners.all { it === lifecycle })
            assertFalse(activation.runtime.isLive())
        }
    }

    @Test
    fun realReceiptRevocationCallbackRunsOnlyAfterSessionsMediaAndAuthorizationRoot() {
        ReceiptFixture(62).use { receipts ->
            val events = mutableListOf<String>()
            val receipt = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = activation,
                activationResult = activation,
                onReceiptRevoked = { events += "receipt-revoked" },
                onClose = { events += "authorization-root" },
            )
            val fixture = runtimeFixture(lifecycle)
            fixture.runtime.activateAndResolve(receipt) { PUBLISHED }
            val barrier = ControllerTeardownBarrier(
                controllerCoreTeardownStages(
                    sealAuthorizationAdmission = {
                        events += "seal"
                        fixture.runtime.revokeAdmission()
                    },
                    retireTransportsAndWorkers = { events += "airplay-sessions" },
                    retireMedia = { events += "media-children" },
                    retireAuthorizationRoot = fixture.runtime::close,
                    retireDeferredMfi = {
                        events += "mfi-authority"
                        receipt.revoke()
                    },
                ),
            )

            assertTrue(barrier.tryBegin())
            assertSame(ControllerTeardownAttempt.Complete, barrier.runAttempt())
            assertEquals(
                listOf(
                    "seal",
                    "airplay-sessions",
                    "media-children",
                    "authorization-root",
                    "mfi-authority",
                    "receipt-revoked",
                ),
                events,
            )
        }
    }

    @Test
    fun successfulHandoffKeepsTheSameActivationAndRuntimeSnapshot() {
        ReceiptFixture(7).use { receipts ->
            val predecessor = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(activation, activation)
            val fixture = runtimeFixture(lifecycle)
            val resolution = fixture.runtime.activateAndResolve(predecessor) { PUBLISHED }
                as ControllerUltraAuthorizationRuntime.Resolution.Authorized
            val handoff = receipts.handoff(predecessor)
            lateinit var prepared: CarPlayUltraPreparedRenewal

            val committed = fixture.runtime.withExclusiveHandoff {
                val exact = handoff.handoff.commit(handoff.successor) { promoted ->
                    assertSame(handoff.successor, promoted)
                    prepared = fixture.runtime.prepareRenewal(predecessor, promoted)
                }
                assertSame(activation, fixture.runtime.commitRenewal(prepared))
                exact
            }

            assertSame(handoff.successor, committed)
            assertFalse(predecessor.isLiveForUltraActivation())
            assertTrue(handoff.successor.isLiveForUltraActivation())
            assertSame(resolution.snapshot, fixture.binding.acquire(fixture.generation))
            assertSame(activation.runtime, resolution.snapshot.ultraRuntime)
            assertSame(activation.configuration, resolution.snapshot.config.ultra)
            assertEquals(1, lifecycle.activateCalls)
            assertEquals(1, lifecycle.renewCalls)
            assertSame(
                resolution.snapshot,
                fixture.binding.withResolvedAdmission(fixture.generation) { it },
            )
            assertSame(handoff.successor, lifecycle.currentChecks.last().receipt)

            val tunnel = fixture.runtime.tunnelAuthentication(handoff.successor)
                as ControllerUltraAuthorizationRuntime.TunnelAuthentication.AuthorizedHandoff
            assertSame(handoff.successor, tunnel.predecessor)
            fixture.runtime.close()
        }
    }

    @Test
    fun renewalFailureRollsBackSuccessorAndPreservesPredecessorPublication() {
        ReceiptFixture(8).use { receipts ->
            val predecessor = receipts.initialReceipt()
            val activation = activation()
            val renewalFailure = CarPlayUltraAuthorizationRenewalException(
                CarPlayUltraAuthorizationDenialCode.STALE_RECEIPT,
            )
            val lifecycle = FakeAuthorizationLifecycle(
                canonicalActivation = activation,
                activationResult = activation,
                renewalFailure = renewalFailure,
            )
            val fixture = runtimeFixture(lifecycle)
            val resolution = fixture.runtime.activateAndResolve(predecessor) { PUBLISHED }
            val handoff = receipts.handoff(predecessor)
            val onReady = AtomicBoolean(false)
            var prepared: CarPlayUltraPreparedRenewal? = null

            val thrown = assertThrows(CarPlayUltraAuthorizationRenewalException::class.java) {
                try {
                    fixture.runtime.withExclusiveHandoff {
                        handoff.handoff.commit(handoff.successor) { promoted ->
                            prepared = fixture.runtime.prepareRenewal(predecessor, promoted)
                        }
                        fixture.runtime.commitRenewal(checkNotNull(prepared))
                        onReady.set(true)
                    }
                } catch (failure: Throwable) {
                    prepared?.let(fixture.runtime::rollbackRenewal)
                    throw failure
                }
            }

            assertSame(renewalFailure, thrown)
            assertFalse(onReady.get())
            assertTrue(predecessor.isLiveForUltraActivation())
            assertFalse(handoff.successor.isLiveForUltraActivation())
            assertSame(resolution.snapshot, fixture.binding.acquire(fixture.generation))
            assertSame(
                resolution.snapshot,
                fixture.binding.withResolvedAdmission(fixture.generation) { it },
            )
            assertSame(predecessor, lifecycle.currentChecks.last().receipt)
            assertEquals(1, lifecycle.renewCalls)

            val tunnel = fixture.runtime.tunnelAuthentication(predecessor)
                as ControllerUltraAuthorizationRuntime.TunnelAuthentication.AuthorizedHandoff
            assertSame(predecessor, tunnel.predecessor)
            fixture.runtime.close()
        }
    }

    @Test
    fun preparedSuccessorRollsBackWhenMfiCommitCallbackFails() {
        ReceiptFixture(9).use { receipts ->
            val predecessor = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(activation, activation)
            val fixture = runtimeFixture(lifecycle)
            val resolution = fixture.runtime.activateAndResolve(predecessor) { PUBLISHED }
            val handoff = receipts.handoff(predecessor)
            var prepared: CarPlayUltraPreparedRenewal? = null
            val callbackFailure = IOException("failure after provisional renewal")

            val thrown = assertThrows(IOException::class.java) {
                try {
                    fixture.runtime.withExclusiveHandoff {
                        handoff.handoff.commit(handoff.successor) { promoted ->
                            prepared = fixture.runtime.prepareRenewal(predecessor, promoted)
                            throw callbackFailure
                        }
                    }
                } catch (failure: Throwable) {
                    prepared?.let(fixture.runtime::rollbackRenewal)
                    throw failure
                }
            }

            assertSame(callbackFailure, thrown)
            assertTrue(predecessor.isLiveForUltraActivation())
            assertFalse(handoff.successor.isLiveForUltraActivation())
            assertSame(resolution.snapshot, fixture.binding.acquire(fixture.generation))
            assertSame(
                resolution.snapshot,
                fixture.binding.withResolvedAdmission(fixture.generation) { it },
            )
            val retry = fixture.runtime.tunnelAuthentication(predecessor)
                as ControllerUltraAuthorizationRuntime.TunnelAuthentication.AuthorizedHandoff
            assertSame(predecessor, retry.predecessor)
            fixture.runtime.close()
        }
    }

    @Test
    fun fatalPreparedRenewalRollsBackAndSealsBeforeRethrow() {
        ReceiptFixture(10).use { receipts ->
            val predecessor = receipts.initialReceipt()
            val activation = activation()
            val lifecycle = FakeAuthorizationLifecycle(activation, activation)
            val fixture = runtimeFixture(lifecycle)
            fixture.runtime.activateAndResolve(predecessor) { PUBLISHED }
            val handoff = receipts.handoff(predecessor)
            var prepared: CarPlayUltraPreparedRenewal? = null
            val fatal = AssertionError("fatal handoff callback")

            val thrown = assertThrows(AssertionError::class.java) {
                try {
                    fixture.runtime.withExclusiveHandoff {
                        handoff.handoff.commit(handoff.successor) { promoted ->
                            prepared = fixture.runtime.prepareRenewal(predecessor, promoted)
                            throw fatal
                        }
                    }
                } catch (failure: Throwable) {
                    try {
                        prepared?.let(fixture.runtime::rollbackRenewal)
                        fixture.runtime.revokeAdmission()
                    } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                    throw failure
                }
            }

            assertSame(fatal, thrown)
            assertTrue(predecessor.isLiveForUltraActivation())
            assertFalse(handoff.successor.isLiveForUltraActivation())
            assertNull(fixture.binding.acquire(fixture.generation))
            assertNull(
                fixture.binding.withResolvedAdmission(fixture.generation) {
                    throw AssertionError("fatal handoff must seal admission")
                },
            )
            fixture.runtime.close()
        }
    }

    private data class RuntimeFixture(
        val runtime: ControllerUltraAuthorizationRuntime,
        val binding: AirPlaySessionRuntimeBinding,
        val generation: AirPlaySessionRuntimeBinding.Generation,
        val baseline: AirPlayConfig,
    )

    private fun runtimeFixture(
        lifecycle: CarPlayUltraAuthorizationLifecycle,
        baseline: AirPlayConfig = baselineConfig(),
        allowDiagnosticPhysicalPanelsWithoutUiSync: Boolean = false,
    ): RuntimeFixture {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        return RuntimeFixture(
            runtime = ControllerUltraAuthorizationRuntime(
                lifecycle = lifecycle,
                binding = binding,
                generation = generation,
                baselineConfig = baseline,
                fallbackRuntime = null,
                allowDiagnosticPhysicalPanelsWithoutUiSync =
                    allowDiagnosticPhysicalPanelsWithoutUiSync,
            ),
            binding = binding,
            generation = generation,
            baseline = baseline,
        )
    }

    private data class CurrentCheck(
        val activation: CarPlayUltraAuthorizationResult.Activated,
        val receipt: MfiAuthenticationReceipt,
    )

    private class FakeAuthorizationLifecycle(
        private val canonicalActivation: CarPlayUltraAuthorizationResult.Activated? = null,
        private val activationResult: CarPlayUltraAuthorizationResult =
            canonicalActivation ?: CarPlayUltraAuthorizationResult.Unavailable,
        private var closeFailures: Int = 0,
        private val renewalFailure: CarPlayUltraAuthorizationRenewalException? = null,
        private val allowRenewal: Boolean = true,
        private val activateEntered: CountDownLatch? = null,
        private val allowActivate: CountDownLatch? = null,
        private val onReceiptRevoked: () -> Unit = {},
        private val onClose: () -> Unit = {},
        private val allowedAssetId: String? = null,
        private val onAssetProbe: () -> Unit = {},
    ) : CarPlayUltraAuthorizationLifecycle {
        val currentChecks = mutableListOf<CurrentCheck>()
        val closeOwners = mutableListOf<Any>()
        var activateCalls = 0
            private set
        var renewCalls = 0
            private set
        var hasRenewableCalls = 0
            private set
        var closeCalls = 0
            private set
        var live = false
        private var currentReceipt: MfiAuthenticationReceipt? = null
        private var pendingPredecessor: MfiAuthenticationReceipt? = null
        private var pendingSuccessor: MfiAuthenticationReceipt? = null

        override fun activate(
            receipt: MfiAuthenticationReceipt,
            sessionManagementInfo: com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo?,
        ): CarPlayUltraAuthorizationResult {
            activateCalls++
            activateEntered?.countDown()
            allowActivate?.let { check(it.await(5, TimeUnit.SECONDS)) }
            if (activationResult is CarPlayUltraAuthorizationResult.Activated) {
                checkNotNull(
                    receipt.consumeForUltraActivation {
                        if (currentReceipt === receipt && pendingPredecessor !== receipt) {
                            live = false
                        }
                        onReceiptRevoked()
                    },
                )
                currentReceipt = receipt
                live = true
            }
            return activationResult
        }

        override fun hasRenewableSession(predecessor: MfiAuthenticationReceipt): Boolean {
            hasRenewableCalls++
            return allowRenewal && live && currentReceipt === predecessor
        }

        override fun isCurrentActivation(
            activation: CarPlayUltraAuthorizationResult.Activated,
            receipt: MfiAuthenticationReceipt,
        ): Boolean {
            currentChecks += CurrentCheck(activation, receipt)
            return live && activation === canonicalActivation && receipt === currentReceipt
        }

        override fun allowsClusterAssetAcquisition(
            assetId: String,
            authenticationIdentity: MfiAuthenticationIdentity,
        ): Boolean {
            onAssetProbe()
            return live && assetId == allowedAssetId &&
                currentReceipt?.authenticationIdentity == authenticationIdentity
        }

        override fun prepareRenewal(
            predecessor: MfiAuthenticationReceipt,
            successor: MfiAuthenticationReceipt,
        ): CarPlayUltraPreparedRenewal {
            renewCalls++
            renewalFailure?.let { throw it }
            check(allowRenewal && live && currentReceipt === predecessor)
            pendingPredecessor = predecessor
            pendingSuccessor = successor
            checkNotNull(
                successor.consumeForUltraHandoffRenewal(predecessor) {
                    if (pendingSuccessor === successor) {
                        if (predecessor.isLiveForUltraActivation()) {
                            currentReceipt = predecessor
                            live = true
                        } else {
                            live = false
                        }
                        pendingPredecessor = null
                        pendingSuccessor = null
                    } else if (currentReceipt === successor) {
                        live = false
                    }
                    onReceiptRevoked()
                },
            )
            val activation = checkNotNull(canonicalActivation)
            return object : CarPlayUltraPreparedRenewal {
                private var committed = false
                private var rolledBack = false

                override val activation = activation
                override val predecessor = predecessor
                override val successor = successor

                @Synchronized
                override fun commit(): CarPlayUltraAuthorizationResult.Activated {
                    check(!rolledBack)
                    if (!committed) {
                        currentReceipt = successor
                        pendingPredecessor = null
                        pendingSuccessor = null
                        committed = true
                    }
                    return activation
                }

                @Synchronized
                override fun close() {
                    if (committed || rolledBack) return
                    if (predecessor.isLiveForUltraActivation()) {
                        currentReceipt = predecessor
                    } else {
                        live = false
                    }
                    pendingPredecessor = null
                    pendingSuccessor = null
                    rolledBack = true
                }
            }
        }

        override fun close() {
            closeCalls++
            closeOwners += this
            onClose()
            if (closeFailures > 0) {
                closeFailures--
                throw IOException("synthetic authorization close failure")
            }
            live = false
            canonicalActivation?.runtime?.close()
        }
    }

    private data class HandoffFixture(
        val handoff: MfiAuthenticationHandoff,
        val successor: MfiAuthenticationReceipt,
    )

    private inner class ReceiptFixture(seed: Int) : AutoCloseable {
        val identity: MfiAuthenticationIdentity = identity(seed)
        private val authority = MfiAuthenticationReceiptAuthority.create(identity)
        private var controlGeneration = 0L

        fun initialReceipt(): MfiAuthenticationReceipt {
            val channel = Any()
            val attempt = authority.begin(channel, ++controlGeneration)
            return attempt.issue(channel, identity)
        }

        fun handoff(predecessor: MfiAuthenticationReceipt): HandoffFixture {
            val channel = Any()
            val handoff = authority.beginHandoff(
                predecessor,
                channel,
                ++controlGeneration,
            )
            val successor = handoff.attemptForExchange().issue(channel, identity)
            return HandoffFixture(handoff, successor)
        }

        override fun close() = authority.close()
    }

    private fun activation(
        configuration: AirPlayUltraConfiguration = AirPlayUltraConfiguration(),
    ): CarPlayUltraAuthorizationResult.Activated =
        CarPlayUltraAuthorizationResult.Activated(
            runtime = AirPlayUltraRuntime(),
            configuration = configuration,
        )

    private fun AirPlayConfig.withLiveMainPhysicalPanel(
        currentProbe: () -> Boolean = { true },
        includeCluster: Boolean = false,
    ): AirPlayConfig {
        val stream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.MAIN_UUID, 0, 0, 0)
        val panelRows = mutableListOf(AirPlayPhysicalDisplayPanel(
            widthPixels = main.widthPixels,
            heightPixels = main.heightPixels,
            widthPhysical = 200.0,
            heightPhysical = 120.0,
            initialVideoStreams = listOf(stream),
            displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                AirPlayInfoPlist.MAIN_UUID, listOf(stream),
            )),
            properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
        ))
        if (includeCluster) {
            val external = requireNotNull(cluster)
            val clusterStream = AirPlayPhysicalVideoStream(AirPlayInfoPlist.ALT_UUID, 0, 0, 0)
            panelRows += AirPlayPhysicalDisplayPanel(
                widthPixels = external.widthPixels,
                heightPixels = external.heightPixels,
                widthPhysical = 200.0,
                heightPhysical = 120.0,
                initialVideoStreams = listOf(clusterStream),
                displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(
                    AirPlayInfoPlist.ALT_UUID, listOf(clusterStream),
                )),
                properties = listOf(AirPlayPhysicalDisplayPanel.PROPERTY_INSTRUMENT_CLUSTER),
            )
        }
        val panels = AirPlayPhysicalDisplayPanels(panelRows)
        return copy(
            displayPanels = panels,
            displayPanelsLiveness = AirPlayPhysicalDisplayPanelsLiveness(
                panels, currentProbe, { Closeable {} },
            ),
        )
    }

    private fun mainUiSyncUltra(
        physicalPreflightProbe: () -> Boolean = { true },
        includeCluster: Boolean = false,
    ): AirPlayUltraConfiguration {
        val displayId = AirPlayInfoPlist.MAIN_UUID
        val schema = UiSyncSchemaFingerprint(ByteArray(32) { 1 })
        val binding = UiSyncBindingFingerprint(ByteArray(32) { 2 })
        val output = UltraDisplayGraphOutput(
            topologyDisplayId = UltraTopologyDisplayId(
                UUID.nameUUIDFromBytes("main-topology".toByteArray()),
            ),
            outputId = UltraDisplayOutputId.Custom("main-output"),
            uiSyncDisplayId = UltraUiSyncDisplayId(displayId),
        )
        val outputs = mutableListOf(output)
        if (includeCluster) outputs += UltraDisplayGraphOutput(
            topologyDisplayId = UltraTopologyDisplayId(UUID.fromString(AirPlayInfoPlist.ALT_UUID)),
            outputId = UltraDisplayOutputId.Custom("cluster-output"),
            uiSyncDisplayId = UltraUiSyncDisplayId(AirPlayInfoPlist.ALT_UUID),
        )
        val displayIds = outputs.mapNotNull { it.uiSyncDisplayId?.value }.toSet()
        val plan = UiSyncRendererStaticPlan(outputs, emptyList())
        val rendererBinding = UiSyncCompleteRendererBindingContext(
            UiSyncRendererBindingContext(plan, schema, binding, displayIds),
        )
        val receipt = rendererBinding.issueReadinessReceipt(
            semanticThemeProgramFingerprint = UiSyncSemanticThemeProgramFingerprint(
                ByteArray(32) { 3 },
            ),
            subscribedDisplayIds = displayIds,
            boundOutputIds = outputs.map(UltraDisplayGraphOutput::outputId),
            semanticCompilerActive = true,
            themeCompilerActive = true,
            backendLive = true,
        )
        val assignments = outputs.mapIndexed { index, row ->
            UiSyncPhysicalOutputAssignment(
                row,
                UiSyncPhysicalDestination(if (index == 0)
                    UiSyncPhysicalDestinationRole.MAIN else UiSyncPhysicalDestinationRole.CLUSTER),
            )
        }
        val requirement = UiSyncPhysicalOutputRequirement(
            UltraProfileDigest(ByteArray(32) { 7 }), assignments,
        )
        val preflight = UiSyncPhysicalOutputPreflightContext(
            requirement, plan, schema, binding,
        ).issueAvailability(
            assignments.mapIndexed { index, assignment ->
                UiSyncPhysicalOutputRoute(
                    assignment,
                    UiSyncPhysicalTargetVersion(if (index == 0) "main-target" else "cluster-target", 1),
                )
            },
            currentProbe = physicalPreflightProbe,
        )
        val factory = RcsDataStreamHandlerFactory { clientType ->
            if (clientType != RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL) {
                null
            } else {
                RcsDataStreamHandlerDescriptor(
                    clientType = clientType,
                    implementationId = "test.cluster-control",
                    readinessProbe = { RcsDataStreamHandlerProbe.ready() },
                    handlerFactory = { object : RcsDataStreamHandler {} },
                )
            }
        }
        return AirPlayUltraConfiguration.fromRuntimeProvisioning(
            configuredFeatures = setOf(AirPlayFeature.UI_SYNC),
            uiSyncInfo = AirPlayUiSyncInfo(),
            rcsFactory = factory,
            uiSyncEndpoints = displayIds.map { id -> CarPlayUiSyncEndpoint(
                descriptor = CarPlayUiSyncChannelDescriptor(id, "test-vehicle"),
                mode = CarPlayUiSyncMode.PASSIVE,
                presentationEventSink = CarPlayUiSyncPresentationEventSink { },
            ) },
            uiSyncRendererReadiness = AirPlayUiSyncRendererReadinessDescriptor.ready(
                "test.complete-renderer", preflight,
            ),
            uiSyncSchemaFingerprint = schema,
            uiSyncBindingFingerprint = binding,
            uiSyncProvisionedDisplayIds = displayIds,
            uiSyncRendererReceipt = receipt,
        )
    }

    private fun baselineConfig(): AirPlayConfig = AirPlayConfig(
        deviceName = "authorization-test",
        deviceId = "00:11:22:33:44:55",
        btMac = "00:11:22:33:44:66",
        sourceVersion = "1.0",
        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
    )

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
}
