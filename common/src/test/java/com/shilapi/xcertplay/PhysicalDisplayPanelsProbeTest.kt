package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanelsLiveness
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class PhysicalDisplayPanelsProbeTest {
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private class FailingValidationSurface : Surface(null as SurfaceTexture?) {
        var failValidation = false
        override fun isValid(): Boolean {
            if (failValidation) throw IllegalStateException("cluster surface validation failed")
            return true
        }
    }

    private val unsafe by lazy {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        field.get(null) as Unsafe
    }
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun failingSurface(): FailingValidationSurface =
        unsafe.allocateInstance(FailingValidationSurface::class.java) as FailingValidationSurface
    private fun texture(): SurfaceTexture = unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture

    private val main = AirPlayDisplayConfig(
        widthPixels = 1920,
        heightPixels = 720,
        widthPhysicalMm = 241,
        heightPhysicalMm = 91,
    )
    private val cluster = AirPlayDisplayConfig(
        widthPixels = 1280,
        heightPixels = 480,
        widthPhysicalMm = 221,
        heightPhysicalMm = 83,
        primaryInputDevice = 0,
    )
    private val externalDestination = DisplayDestination(
        DisplayDestinationRole.CLUSTER, DisplayDestinationId("bench-cluster-7"),
    )
    private fun externalTrust(
        registry: HostPhysicalDisplaySurfaceRegistry,
        destination: DisplayDestination = externalDestination,
    ): ExternalPhysicalTargetTrustToken = registry.newExternalPhysicalTargetTrustToken(
        externalCalibrationForTest(
            destination = destination,
            pixels = PixelSize(cluster.widthPixels, cluster.heightPixels),
            widthMm = requireNotNull(cluster.widthPhysicalMm),
            heightMm = requireNotNull(cluster.heightPhysicalMm),
        ),
    ).also { it.bindRouteProof { true } }

    @Test
    fun diagnosticNamesTheLocalGateWithoutClaimingIphoneRejection() {
        assertEquals(
            "DDP probe local readiness: MAIN=MAIN local preview hidden; " +
                "ALT=no live PHYSICAL_EXTERNAL cluster target; published=false. " +
                "This is local output admission, not iPhone acceptance.",
            physicalDdpProbeDiagnosticMessage(
                PhysicalDdpPanelReadiness.MAIN_HIDDEN,
                PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL,
                published = false,
            ),
        )
    }

    @Test
    fun defaultAndDisabledSwitchKeepExactLegacyInfoBytes() {
        val baseline = config()
        val baselineBytes = BplistCodec.encode(AirPlayInfoPlist.build(baseline, setOf("altScreen", "uiContext")))
        for (flags in listOf(
            Triple(false, false, false),
            Triple(false, true, true),
            Triple(true, true, false),
        )) {
            val panels = physicalDisplayPanelsProbe(
                ultraEnabled = flags.first,
                clusterDisplayEnabled = flags.second,
                optedIn = flags.third,
                main = main,
                cluster = cluster,
            )
            assertNull(panels)
            val info = AirPlayInfoPlist.build(baseline.copy(displayPanels = panels), setOf("altScreen", "uiContext"))
            assertTrue(baselineBytes.contentEquals(BplistCodec.encode(info)))
            assertFalse(info.containsKey("displayPanels"))
        }
    }

    @Test
    fun mainOnlyOptInUsesTheExactExistingMainGeometryAndUuid() {
        val baseline = config().copy(cluster = null)
        val panels = physicalDisplayPanelsProbe(
            true, false, true, baseline.main, baseline.cluster,
            physicalMainOutputProven = true,
        )
        val configured = baseline.copy(displayPanels = panels)
        val ordinary = AirPlayInfoPlist.build(baseline, setOf("uiContext"))
        val info = AirPlayInfoPlist.build(configured, setOf("uiContext"))
        val legacy = (info["displays"] as List<*>).single() as Map<*, *>
        val panel = (info["displayPanels"] as List<*>).single() as Map<*, *>
        val initial = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
        val plugin = (panel["displayPlugins"] as List<*>).single() as Map<*, *>
        val managed = (plugin["managedVideoStreams"] as List<*>).single() as Map<*, *>

        assertEquals(AirPlayInfoPlist.MAIN_UUID, legacy["uuid"])
        assertEquals(legacy["uuid"], initial["uuid"])
        assertEquals(legacy["uuid"], managed["uuid"])
        assertEquals(legacy["widthPixels"], panel["widthPixels"])
        assertEquals(legacy["heightPixels"], panel["heightPixels"])
        assertEquals((legacy["widthPhysical"] as Int).toDouble(), panel["widthPhysical"])
        assertEquals((legacy["heightPhysical"] as Int).toDouble(), panel["heightPhysical"])
        assertEquals(listOf("CarPlay"), panel["properties"])
        assertTrue(BplistCodec.encode(ordinary).contentEquals(BplistCodec.encode(info - "displayPanels")))
        assertFalse(AirPlayFeature.UI_SYNC in AirPlayFeatureNegotiation.snapshot(configured).readyFeatureSet)
        assertFalse(info.containsKey("uiSyncInfo"))
    }

    @Test
    fun mainDdpRequiresExactLiveShownPhysicalMainSurfaceAndGeometry() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val current = surface()
        val texture = texture()
        assertEquals(
            PhysicalDdpPanelReadiness.NO_MAIN_SURFACE,
            physicalMainDisplayOutputReadiness(registry, null, true, main),
        )
        assertFalse(physicalMainDisplayOutputProven(registry, null, true, main))
        assertEquals(
            PhysicalDdpPanelReadiness.NO_PHYSICAL_MAIN,
            physicalMainDisplayOutputReadiness(registry, current, true, main),
        )
        assertFalse(physicalMainDisplayOutputProven(registry, current, true, main))
        assertNull(physicalDisplayPanelsProbe(true, false, true, main, null))

        registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            current,
            texture,
            main.widthPixels,
            main.heightPixels,
        )
        assertEquals(
            PhysicalDdpPanelReadiness.MAIN_HIDDEN,
            physicalMainDisplayOutputReadiness(registry, current, false, main),
        )
        assertFalse(physicalMainDisplayOutputProven(registry, current, false, main))
        assertNull(physicalDisplayPanelsProbe(
            true, false, true, main, null,
            physicalMainOutputProven = physicalMainDisplayOutputProven(registry, current, false, main),
        ))
        assertEquals(
            PhysicalDdpPanelReadiness.MAIN_SURFACE_MISMATCH,
            physicalMainDisplayOutputReadiness(registry, surface(), true, main),
        )
        assertFalse(physicalMainDisplayOutputProven(registry, surface(), true, main))
        assertEquals(
            PhysicalDdpPanelReadiness.PIXEL_GEOMETRY_MISMATCH,
            physicalMainDisplayOutputReadiness(
                registry, current, true, main.copy(widthPixels = main.widthPixels + 1),
            ),
        )
        assertFalse(physicalMainDisplayOutputProven(
            registry, current, true, main.copy(widthPixels = main.widthPixels + 1),
        ))
        assertEquals(
            PhysicalDdpPanelReadiness.INVALID_PANEL_GEOMETRY,
            physicalMainDisplayOutputReadiness(registry, current, true, main.copy(widthPhysicalMm = null)),
        )
        assertEquals(
            PhysicalDdpPanelReadiness.READY,
            physicalMainDisplayOutputReadiness(registry, current, true, main),
        )
        assertTrue(physicalMainDisplayOutputProven(registry, current, true, main))
        val panels = physicalDisplayPanelsProbe(
            true, false, true, main, null,
            physicalMainOutputProven = physicalMainDisplayOutputProven(registry, current, true, main),
        )
        val info = AirPlayInfoPlist.build(config().copy(cluster = null, displayPanels = panels), emptySet())
        assertEquals(1, (info["displayPanels"] as List<*>).size)

        registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PREVIEW_ONLY,
            current,
            texture,
            main.widthPixels,
            main.heightPixels,
        )
        assertFalse(physicalMainDisplayOutputProven(registry, current, true, main))
    }

    @Test
    fun externalClusterProofRequiresLivePhysicalExternalTargetWithMatchingPixels() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val current = surface()
        val texture = texture()
        assertEquals(
            PhysicalDdpPanelReadiness.NO_CLUSTER,
            physicalClusterDisplayOutputReadiness(registry, null),
        )
        assertEquals(
            PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL,
            physicalClusterDisplayOutputReadiness(registry, cluster, externalDestination),
        )
        assertFalse(physicalClusterDisplayOutputProven(registry, cluster, externalDestination))
        registry.publish(
            externalDestination,
            HostDisplaySurfaceKind.PREVIEW_ONLY,
            current,
            texture,
            cluster.widthPixels,
            cluster.heightPixels,
        )
        assertFalse(physicalClusterDisplayOutputProven(registry, cluster, externalDestination))
        registry.publish(
            externalDestination,
            HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            current,
            texture,
            cluster.widthPixels,
            cluster.heightPixels,
            externalTrustToken = externalTrust(registry),
        )
        assertFalse(physicalClusterDisplayOutputProven(
            registry, cluster.copy(heightPixels = cluster.heightPixels + 1), externalDestination,
        ))
        assertEquals(
            PhysicalDdpPanelReadiness.PIXEL_GEOMETRY_MISMATCH,
            physicalClusterDisplayOutputReadiness(
                registry, cluster.copy(heightPixels = cluster.heightPixels + 1), externalDestination,
            ),
        )
        assertEquals(
            PhysicalDdpPanelReadiness.INVALID_PANEL_GEOMETRY,
            physicalClusterDisplayOutputReadiness(
                registry, cluster.copy(heightPhysicalMm = null), externalDestination,
            ),
        )
        assertEquals(
            PhysicalDdpPanelReadiness.READY,
            physicalClusterDisplayOutputReadiness(registry, cluster, externalDestination),
        )
        assertTrue(physicalClusterDisplayOutputProven(registry, cluster, externalDestination))
        registry.publish(
            externalDestination,
            HostDisplaySurfaceKind.PREVIEW_ONLY,
            current,
            texture,
            cluster.widthPixels,
            cluster.heightPixels,
        )
        assertFalse(physicalClusterDisplayOutputProven(registry, cluster, externalDestination))
    }

    @Test
    fun uniqueTrustedNonDefaultExternalClusterCanBackFrozenDdpProof() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {},
        )
        val mainSurface = surface()
        registry.publish(DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            mainSurface, texture(), main.widthPixels, main.heightPixels)
        val destination = DisplayDestination(
            DisplayDestinationRole.CLUSTER, DisplayDestinationId("hdmi-1"),
        )
        val clusterSurface = surface()
        val trust = externalTrust(registry, destination)
        registry.publish(destination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface, texture(), cluster.widthPixels, cluster.heightPixels,
            externalTrustToken = trust)

        assertEquals(destination,
            registry.inspectUniquePhysicalExternalClusterTarget()?.version?.destination)
        assertEquals(PhysicalDdpPanelReadiness.READY,
            physicalClusterDisplayOutputReadiness(registry, cluster, destination))
        val proof = requireNotNull(PhysicalDdpPanelSessionProof.capture(
            registry, mainSurface, mainVisible = true, main = main, cluster = cluster,
            includePhysicalCluster = true, clusterDestination = destination,
        ))
        assertTrue(proof.isCurrent())
        trust.invalidate()
        assertFalse(proof.isCurrent())
        assertNull(registry.inspectUniquePhysicalExternalClusterTarget())
    }

    @Test
    fun samePixelGeometryWithWrongPhysicalMillimetresCannotPublishAltDdp() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        registry.publish(
            externalDestination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            surface(), texture(), cluster.widthPixels, cluster.heightPixels,
            externalTrustToken = externalTrust(registry),
        )
        val inspection = requireNotNull(registry.inspectUniquePhysicalExternalClusterTarget())
        val miscalibrated = cluster.copy(widthPhysicalMm = requireNotNull(cluster.widthPhysicalMm) + 1)
        assertEquals(PhysicalDdpPanelReadiness.PHYSICAL_MM_MISMATCH,
            physicalClusterDisplayOutputReadiness(registry, miscalibrated, externalDestination))
        val calibrated = requireNotNull(calibratedExternalClusterDisplay(miscalibrated, inspection))
        assertEquals(cluster.widthPhysicalMm, calibrated.widthPhysicalMm)
        assertEquals(cluster.heightPhysicalMm, calibrated.heightPhysicalMm)
        val mismatchedPixels = miscalibrated.copy(widthPixels = cluster.widthPixels + 1)
        val classicAlt = requireNotNull(calibratedExternalClusterDisplay(mismatchedPixels, inspection))
        assertEquals(mismatchedPixels, classicAlt)
        assertEquals(PhysicalDdpPanelReadiness.PIXEL_GEOMETRY_MISMATCH,
            physicalClusterDisplayOutputReadiness(registry, classicAlt, externalDestination))
        val classicWithMainDdp = config().copy(
            cluster = classicAlt,
            displayPanels = physicalDisplayPanelsProbe(
                true, true, true, main, classicAlt,
                physicalMainOutputProven = true,
                physicalClusterOutputProven = false,
            ),
        )
        val info = AirPlayInfoPlist.build(classicWithMainDdp, setOf("altScreen", "uiContext"))
        assertEquals(2, (info["displays"] as List<*>).size)
        assertEquals(1, (info["displayPanels"] as List<*>).size)
        assertEquals(PhysicalDdpPanelReadiness.READY,
            physicalClusterDisplayOutputReadiness(registry, calibrated, externalDestination))
        assertEquals(4, inspection.externalCalibration?.displayId)
        assertEquals(101L, inspection.externalCalibration?.displayEpoch)
    }

    @Test
    fun secondTrustedExternalCandidatePermanentlyRevokesFrozenTwoPanelProof() {
        val registry = HostPhysicalDisplaySurfaceRegistry(releaseSurface = {}, releaseTexture = {})
        val mainSurface = surface()
        registry.publish(DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            mainSurface, texture(), main.widthPixels, main.heightPixels)
        val firstSurface = surface()
        registry.publish(externalDestination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            firstSurface, texture(), cluster.widthPixels, cluster.heightPixels,
            externalTrustToken = externalTrust(registry))
        val proof = requireNotNull(PhysicalDdpPanelSessionProof.capture(
            registry, mainSurface, mainVisible = true, main = main, cluster = cluster,
            includePhysicalCluster = true, clusterDestination = externalDestination,
        ))
        var revoked = 0
        proof.subscribeChanges { revoked++ }
        assertTrue(proof.isCurrent())
        val secondDestination = DisplayDestination(
            DisplayDestinationRole.CLUSTER, DisplayDestinationId("bench-cluster-8"),
        )
        val secondSurface = surface()
        val secondTexture = texture()
        registry.publish(secondDestination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            secondSurface, secondTexture, cluster.widthPixels, cluster.heightPixels,
            externalTrustToken = registry.newExternalPhysicalTargetTrustToken(
                externalCalibrationForTest(
                    destination = secondDestination,
                    pixels = PixelSize(cluster.widthPixels, cluster.heightPixels),
                    widthMm = requireNotNull(cluster.widthPhysicalMm),
                    heightMm = requireNotNull(cluster.heightPhysicalMm),
                    displayId = 5, epoch = 102L,
                ),
            ).also { it.bindRouteProof { true } })
        assertNull(registry.inspectUniquePhysicalExternalClusterTarget())
        assertFalse(proof.isCurrent())
        assertEquals(1, revoked)
        registry.withdraw(secondDestination, secondSurface, secondTexture,
            textureDestroyed = false)!!.completeAfterLegacyOwnerRetired()
        assertFalse(proof.isCurrent())
        assertEquals(1, revoked)
    }

    @Test
    fun frozenMainPanelLivenessRevokesWhenOldActivityWithdrawsItsExactSurface() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val shownSurface = surface()
        val shownTexture = texture()
        registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            shownSurface,
            shownTexture,
            main.widthPixels,
            main.heightPixels,
        )
        val panels = requireNotNull(physicalDisplayPanelsProbe(
            true, false, true, main, null,
            physicalMainOutputProven = true,
        ))
        val exactProof = requireNotNull(PhysicalDdpPanelSessionProof.capture(
            registry,
            shownSurface,
            mainVisible = true,
            main = main,
            cluster = null,
            includePhysicalCluster = false,
        ))
        val sessionLiveness = AirPlayPhysicalDisplayPanelsLiveness(
            panels, exactProof::isCurrent, exactProof::subscribeChanges,
        )
        var revoked = 0
        val subscription = exactProof.subscribeChanges { revoked++ }
        assertTrue(sessionLiveness.isCurrentFor(panels))
        registry.withdraw(
            DisplayDestination.MAIN,
            shownSurface,
            shownTexture,
            textureDestroyed = false,
        )!!.completeAfterLegacyOwnerRetired()
        assertFalse(sessionLiveness.isCurrentFor(panels))
        assertEquals(1, revoked)
        subscription.close()

        registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface(),
            texture(),
            main.widthPixels,
            main.heightPixels,
        )
        assertFalse(sessionLiveness.isCurrentFor(panels))
    }

    @Test
    fun frozenTwoPanelLivenessRevokesWhenExternalClusterBecomesPreviewOnly() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val mainSurface = surface()
        val mainTexture = texture()
        val clusterSurface = surface()
        val clusterTexture = texture()
        registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            mainSurface,
            mainTexture,
            main.widthPixels,
            main.heightPixels,
        )
        registry.publish(
            externalDestination,
            HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface,
            clusterTexture,
            cluster.widthPixels,
            cluster.heightPixels,
            externalTrustToken = externalTrust(registry),
        )
        val proof = requireNotNull(PhysicalDdpPanelSessionProof.capture(
            registry,
            mainSurface,
            mainVisible = true,
            main = main,
            cluster = cluster,
            includePhysicalCluster = true,
            clusterDestination = externalDestination,
        ))
        assertTrue(proof.isCurrent())
        var revoked = 0
        val subscription = proof.subscribeChanges { revoked++ }
        registry.publish(
            externalDestination,
            HostDisplaySurfaceKind.PREVIEW_ONLY,
            clusterSurface,
            clusterTexture,
            cluster.widthPixels,
            cluster.heightPixels,
        )
        assertFalse(proof.isCurrent())
        assertEquals(1, revoked)
        registry.withdraw(
            DisplayDestination.MAIN,
            mainSurface,
            mainTexture,
            textureDestroyed = false,
        )!!.completeAfterLegacyOwnerRetired()
        assertEquals(1, revoked)
        subscription.close()
    }

    @Test
    fun failedSecondSubscriptionClosesAlreadyRegisteredMainListener() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {},
            releaseTexture = {},
        )
        val mainSurface = surface()
        val mainTexture = texture()
        val clusterSurface = failingSurface()
        val clusterTexture = texture()
        registry.publish(
            DisplayDestination.MAIN,
            HostDisplaySurfaceKind.PHYSICAL_MAIN,
            mainSurface,
            mainTexture,
            main.widthPixels,
            main.heightPixels,
        )
        registry.publish(
            externalDestination,
            HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            clusterSurface,
            clusterTexture,
            cluster.widthPixels,
            cluster.heightPixels,
            externalTrustToken = externalTrust(registry),
        )
        val proof = requireNotNull(PhysicalDdpPanelSessionProof.capture(
            registry,
            mainSurface,
            mainVisible = true,
            main = main,
            cluster = cluster,
            includePhysicalCluster = true,
            clusterDestination = externalDestination,
        ))
        clusterSurface.failValidation = true
        var callbacks = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            proof.subscribeChanges { callbacks++ }
        }
        assertEquals("cluster surface validation failed", failure.message)
        clusterSurface.failValidation = false
        registry.withdraw(
            DisplayDestination.MAIN,
            mainSurface,
            mainTexture,
            textureDestroyed = false,
        )!!.completeAfterLegacyOwnerRetired()
        assertEquals(0, callbacks)
    }

    @Test
    fun configuredLegacyClusterWithoutPhysicalProofDeclaresOnlyMainDdpPanel() {
        val baseline = config()
        val configured = baseline.copy(
            displayPanels = physicalDisplayPanelsProbe(
                true, true, true, main, cluster,
                physicalMainOutputProven = true,
            ),
        )
        val ordinary = AirPlayInfoPlist.build(baseline, setOf("altScreen", "uiContext"))
        val info = AirPlayInfoPlist.build(configured, setOf("altScreen", "uiContext"))
        val legacy = info["displays"] as List<*>
        val panels = info["displayPanels"] as List<*>
        assertEquals(2, legacy.size)
        assertEquals(1, panels.size)
        assertEquals(
            AirPlayInfoPlist.MAIN_UUID,
            (((panels.single() as Map<*, *>)["initialVideoStreams"] as List<*>).single() as Map<*, *>)["uuid"],
        )
        assertEquals(listOf("CarPlay"), (panels.single() as Map<*, *>)["properties"])
        assertTrue(BplistCodec.encode(ordinary).contentEquals(BplistCodec.encode(info - "displayPanels")))
        assertFalse(AirPlayFeature.UI_SYNC in AirPlayFeatureNegotiation.snapshot(configured).readyFeatureSet)
    }

    @Test
    fun independentlyProvenPhysicalClusterDeclaresBothPanelsWithExactLegacyGeometry() {
        val baseline = config()
        val panels = physicalDisplayPanelsProbe(
            true, true, true, baseline.main, baseline.cluster,
            physicalMainOutputProven = true,
            physicalClusterOutputProven = true,
        )
        val configured = baseline.copy(displayPanels = panels)
        val ordinary = AirPlayInfoPlist.build(baseline, setOf("altScreen", "uiContext"))
        val info = AirPlayInfoPlist.build(configured, setOf("altScreen", "uiContext"))
        val legacy = info["displays"] as List<*>
        val physical = info["displayPanels"] as List<*>

        assertEquals(2, legacy.size)
        assertEquals(2, physical.size)
        assertTrue(BplistCodec.encode(ordinary).contentEquals(BplistCodec.encode(info - "displayPanels")))
        for (index in 0..1) {
            val display = legacy[index] as Map<*, *>
            val panel = physical[index] as Map<*, *>
            val stream = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
            val plugin = (panel["displayPlugins"] as List<*>).single() as Map<*, *>
            val managed = (plugin["managedVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(display["uuid"], stream["uuid"])
            assertEquals(display["uuid"], managed["uuid"])
            assertEquals((display["widthPixels"] as Int), panel["widthPixels"])
            assertEquals((display["heightPixels"] as Int), panel["heightPixels"])
            assertEquals((display["widthPhysical"] as Int).toDouble(), panel["widthPhysical"])
            assertEquals((display["heightPhysical"] as Int).toDouble(), panel["heightPhysical"])
            assertEquals(0, stream["zIndex"])
            assertEquals(0, stream["originXPixels"])
            assertEquals(0, stream["originYPixels"])
            // Physical ALT currently presents only a map stream; it has no proven gauge renderer.
            assertEquals(if (index == 0) listOf("CarPlay") else listOf("Map"), panel["properties"])
        }
        assertFalse(AirPlayFeature.UI_SYNC in AirPlayFeatureNegotiation.snapshot(configured).readyFeatureSet)
        assertFalse(info.containsKey("uiSyncInfo"))
    }

    @Test
    fun featureScopeProjectsOutTheAltPanelWithoutMutatingTheFrozenConfig() {
        val configured = config().copy(displayPanels = physicalDisplayPanelsProbe(
            true, true, true, main, cluster,
            physicalMainOutputProven = true,
            physicalClusterOutputProven = true,
        ))
        val before = BplistCodec.encode(AirPlayInfoPlist.build(configured, setOf("altScreen", "uiContext")))
        val mainOnly = AirPlayInfoPlist.build(configured, emptySet())
        val displays = mainOnly["displays"] as List<*>
        val panels = mainOnly["displayPanels"] as List<*>
        assertEquals(1, displays.size)
        assertEquals(1, panels.size)
        assertEquals(
            (displays.single() as Map<*, *>)["uuid"],
            ((panels.single() as Map<*, *>)["initialVideoStreams"] as List<*>).single().let {
                (it as Map<*, *>)["uuid"]
            },
        )
        assertTrue(before.contentEquals(BplistCodec.encode(AirPlayInfoPlist.build(configured, setOf("altScreen", "uiContext")))))
    }

    @Test
    fun missingOrInvalidPhysicalSizeFailsClosedInsteadOfGuessingOrClamping() {
        for (invalid in listOf(
            main.copy(widthPhysicalMm = null),
            main.copy(heightPhysicalMm = null),
            main.copy(widthPhysicalMm = 0),
            main.copy(heightPhysicalMm = 2001),
            main.copy(widthPixels = 0),
        )) {
            assertNull(physicalDisplayPanelsProbe(
                true, true, true, invalid, cluster,
                physicalMainOutputProven = true,
                physicalClusterOutputProven = true,
            ))
        }
        for (invalid in listOf(
            null,
            cluster.copy(heightPhysicalMm = null),
            cluster.copy(heightPixels = 1_000_001),
        )) {
            val panels = physicalDisplayPanelsProbe(
                true, true, true, main, invalid,
                physicalMainOutputProven = true,
                physicalClusterOutputProven = true,
            )
            val configured = config().copy(cluster = invalid, displayPanels = panels)
            val physical = AirPlayInfoPlist.build(configured, setOf("altScreen"))["displayPanels"] as List<*>
            assertEquals(1, physical.size)
            val stream = ((physical.single() as Map<*, *>)["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(AirPlayInfoPlist.MAIN_UUID, stream["uuid"])
        }
        val disabled = physicalDisplayPanelsProbe(
            true, false, true, main, cluster,
            physicalMainOutputProven = true,
            physicalClusterOutputProven = true,
        )
        val displayed = AirPlayInfoPlist.build(config().copy(displayPanels = disabled), setOf("altScreen"))
        assertEquals(1, (displayed["displayPanels"] as List<*>).size)
    }

    private fun config() = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "test",
        main = main,
        cluster = cluster,
    )
}
