package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanelsLiveness
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HostUltraTwoPhaseConfigTest {
    private val main = AirPlayDisplayConfig(1920, 720, 241, 91)
    private val cluster = AirPlayDisplayConfig(1280, 480, 221, 83, primaryInputDevice = 0)

    private fun baseline(withCluster: Boolean = true) = AirPlayConfig(
        deviceName = "bench",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = main,
        cluster = cluster.takeIf { withCluster },
    )

    @Test
    fun latePhysicalProofUsesFrozenMainAndOnlyRefinesClusterMillimetres() {
        val base = baseline()
        val staged = HostUltraTwoPhaseConfig(base, constructionGeneration = 12)
        val ticket = staged.issueTicket(12)!!
        val panels = physicalDisplayPanelsProbe(
            ultraEnabled = true,
            clusterDisplayEnabled = false,
            optedIn = true,
            main = base.main,
            cluster = base.cluster,
            physicalMainOutputProven = true,
        )!!
        val liveness = AirPlayPhysicalDisplayPanelsLiveness(panels, { true }, { Closeable {} })
        val resolved = staged.finalize(ticket, 12) { frozen ->
            assertSame(base, frozen)
            HostUltraFinalConfig(
                frozen.copy(
                    cluster = frozen.cluster!!.copy(widthPhysicalMm = 230, heightPhysicalMm = 86),
                    displayPanels = panels,
                    displayPanelsLiveness = liveness,
                ),
                frozenExternalClusterTarget = null,
            )
        }!!
        assertSame(base.main, resolved.airPlayConfig.main)
        assertSame(panels, resolved.airPlayConfig.displayPanels)
        assertTrue(staged.isFinalizedFor(ticket, 12))
        assertNull(staged.finalize(ticket, 12) { error("resolver cannot run twice") })
    }

    @Test
    fun staleGenerationSupersededTicketAndCancellationCannotFinalize() {
        val base = baseline()
        val staged = HostUltraTwoPhaseConfig(base, constructionGeneration = 7)
        assertNull(staged.issueTicket(6))
        val old = staged.issueTicket(7)!!
        val fresh = staged.issueTicket(7)!!
        assertFalse(staged.isCurrent(old, 7))
        assertNull(staged.finalize(old, 7) { error("stale resolver must not run") })
        assertNull(staged.finalize(fresh, 8) { error("wrong-generation resolver must not run") })
        assertNull(staged.finalize(fresh, 7) {
            staged.cancel()
            HostUltraFinalConfig(it, null)
        })
        assertFalse(staged.isFinalizedFor(fresh, 7))
        assertNull(staged.issueTicket(7))
    }

    @Test
    fun changedStreamPixelsMainIdentityOrNonDisplaySettingsAreRejected() {
        val base = baseline()
        val cases = listOf(
            base.copy(main = base.main.copy()),
            base.copy(cluster = base.cluster!!.copy(widthPixels = 1279)),
            base.copy(hevc = true),
        )
        cases.forEach { changed ->
            val staged = HostUltraTwoPhaseConfig(base, 1)
            val ticket = staged.issueTicket(1)!!
            assertThrows(IllegalStateException::class.java) {
                staged.finalize(ticket, 1) { HostUltraFinalConfig(changed, null) }
            }
        }
    }

    @Test
    fun optedOutFinalizationKeepsLegacyInfoByteIdentical() {
        val base = baseline(withCluster = false)
        val staged = HostUltraTwoPhaseConfig(base, 3)
        val ticket = staged.issueTicket(3)!!
        val resolved = staged.finalize(ticket, 3) { frozen ->
            HostUltraFinalConfig(frozen, null)
        }!!
        assertSame(base, resolved.airPlayConfig)
        val before = BplistCodec.encode(AirPlayInfoPlist.build(base, emptySet()))
        val after = BplistCodec.encode(AirPlayInfoPlist.build(resolved.airPlayConfig, emptySet()))
        assertTrue(before.contentEquals(after))
    }

    @Test
    fun withdrawnExternalProofCannotLeaveCalibratedAltMillimetresInInfo() {
        val base = baseline()
        val calibrated = base.copy(cluster = base.cluster!!.copy(
            widthPhysicalMm = 230, heightPhysicalMm = 86,
        ))
        val panels = physicalDisplayPanelsProbe(
            ultraEnabled = true,
            clusterDisplayEnabled = true,
            optedIn = true,
            main = calibrated.main,
            cluster = calibrated.cluster,
            physicalMainOutputProven = true,
            physicalClusterOutputProven = true,
        )!!
        val current = AtomicBoolean(true)
        val liveness = AirPlayPhysicalDisplayPanelsLiveness(panels,
            { current.get() }, { Closeable {} })
        val rawSurface = (Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null) as Unsafe)
            .allocateInstance(Surface::class.java) as Surface
        val external = HostPhysicalTargetInspection(HostDisplaySurfaceVersion(
            DisplayDestination.CLUSTER, 1L, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
            PixelSize(1280, 480),
        ), rawSurface)

        val admitted = settleHostPhysicalPanelProbe(base, calibrated, panels, liveness, external)
        assertSame(calibrated.cluster, admitted.airPlayConfig.cluster)
        assertSame(external, admitted.frozenExternalClusterTarget)

        val missingExternal = settleHostPhysicalPanelProbe(base, calibrated, panels,
            liveness, null)
        assertSame(base.cluster, missingExternal.airPlayConfig.cluster)
        assertNull(missingExternal.airPlayConfig.displayPanels)

        current.set(false) // Withdrawn between calibration and final physical proof.
        val withdrawn = settleHostPhysicalPanelProbe(base, calibrated, panels, liveness, external)
        assertSame(base.cluster, withdrawn.airPlayConfig.cluster)
        assertNull(withdrawn.airPlayConfig.displayPanels)
        assertNull(withdrawn.frozenExternalClusterTarget)
    }

    @Test
    fun hostCreatesSinkBeforeDdpResolutionAndControllerOnlyAfterFinalConfig() {
        val source = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt").readText()
        val construction = source
            .substringAfter("private fun startCarPlay(size: DisplaySize)")
            .substringBefore("} catch (failure: Throwable) {")
        val ordered = listOf(
            "val baseAirPlayConfig = createAirPlayBaseConfig(",
            "outputsOwner = createWebDisplayOutputs()",
            "renderer = createMediaSink(",
            "val finalized = checkNotNull(staged.finalize(",
            "airPlayConfig = finalized.airPlayConfig",
            "HostUltraCompositionInputs(",
            "check(staged.isFinalizedFor(configTicket, restartGeneration))",
            "CarPlayController(",
        )
        var previous = -1
        ordered.forEach { step ->
            val found = construction.indexOf(step)
            assertTrue("Missing Host step: $step", found > previous)
            previous = found
        }
        val finalizer = source.substringAfter("private fun finalizeAirPlayConfig(baseline: AirPlayConfig)")
            .substringBefore("private fun loadAirPlayIcon()")
        assertTrue(finalizer.indexOf("if (!carPlayUltraEnabled || !physicalDisplayPanelsProbeEnabled)") <
            finalizer.indexOf("physicalSurfaceRegistry.inspectUniquePhysicalExternalClusterTarget()"))
        assertTrue(source.contains("stagedAirPlayConfig?.cancel()"))
    }
}
