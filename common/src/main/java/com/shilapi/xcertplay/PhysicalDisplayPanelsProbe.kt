package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanel
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanels
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPlugin
import com.shilapi.xcertplay.airplay.AirPlayPhysicalVideoStream
import com.shilapi.xcertplay.ultra.DisplayDestination
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PhysicalDdpPanelReadiness(val diagnostic: String) {
    READY("ready"),
    NO_MAIN_SURFACE("no current MAIN Surface"),
    MAIN_HIDDEN("MAIN local preview hidden"),
    NO_PHYSICAL_MAIN("no live PHYSICAL_MAIN target"),
    MAIN_SURFACE_MISMATCH("MAIN Surface differs from current Host Surface"),
    NO_PHYSICAL_EXTERNAL("no live PHYSICAL_EXTERNAL cluster target"),
    NO_CLUSTER("no declared cluster"),
    PIXEL_GEOMETRY_MISMATCH("Surface pixels differ from /info display geometry"),
    PHYSICAL_MM_MISMATCH("verified external physical millimetres differ from /info"),
    INVALID_PANEL_GEOMETRY("DDP pixel or physical-mm geometry invalid"),
}

internal fun physicalDdpProbeDiagnosticMessage(
    main: PhysicalDdpPanelReadiness,
    cluster: PhysicalDdpPanelReadiness,
    published: Boolean,
): String = "DDP probe local readiness: MAIN=${main.diagnostic}; " +
    "ALT=${cluster.diagnostic}; published=$published. " +
    "This is local output admission, not iPhone acceptance."

private fun validPanelGeometry(display: AirPlayDisplayConfig): Boolean {
    val widthMm = display.widthPhysicalMm ?: return false
    val heightMm = display.heightPhysicalMm ?: return false
    return widthMm in AirPlayDisplaySettings.MIN_REPORTED_PHYSICAL_MM..
        AirPlayDisplaySettings.MAX_REPORTED_PHYSICAL_MM &&
        heightMm in AirPlayDisplaySettings.MIN_REPORTED_PHYSICAL_MM..
        AirPlayDisplaySettings.MAX_REPORTED_PHYSICAL_MM &&
        display.widthPixels in 1..1_000_000 && display.heightPixels in 1..1_000_000
}

/** Keep legacy ALT and its DDP panel on the same independently calibrated physical scale. */
internal fun calibratedExternalClusterDisplay(
    configured: AirPlayDisplayConfig?,
    trusted: HostPhysicalTargetInspection?,
): AirPlayDisplayConfig? {
    val display = configured ?: return null
    val calibration = trusted?.externalCalibration ?: return display
    if (calibration.pixels.widthPixels != display.widthPixels ||
        calibration.pixels.heightPixels != display.heightPixels
    ) return display
    return display.copy(
        widthPhysicalMm = calibration.widthPhysicalMm,
        heightPhysicalMm = calibration.heightPhysicalMm,
    )
}

/** A visible main TextureView must still own this exact live physical Surface and pixel size. */
internal fun physicalMainDisplayOutputReadiness(
    registry: HostPhysicalDisplaySurfaceRegistry,
    currentSurface: Surface?,
    mainVisible: Boolean,
    main: AirPlayDisplayConfig,
): PhysicalDdpPanelReadiness {
    if (currentSurface == null) return PhysicalDdpPanelReadiness.NO_MAIN_SURFACE
    if (!mainVisible) return PhysicalDdpPanelReadiness.MAIN_HIDDEN
    val inspection = registry.inspectPhysicalTarget(DisplayDestination.MAIN)
        ?: return PhysicalDdpPanelReadiness.NO_PHYSICAL_MAIN
    val version = inspection.version
    if (version.destination != DisplayDestination.MAIN ||
        version.kind != HostDisplaySurfaceKind.PHYSICAL_MAIN
    ) return PhysicalDdpPanelReadiness.NO_PHYSICAL_MAIN
    if (inspection.surface !== currentSurface) return PhysicalDdpPanelReadiness.MAIN_SURFACE_MISMATCH
    if (version.sizePixels.widthPixels != main.widthPixels ||
        version.sizePixels.heightPixels != main.heightPixels
    ) return PhysicalDdpPanelReadiness.PIXEL_GEOMETRY_MISMATCH
    if (!validPanelGeometry(main)) return PhysicalDdpPanelReadiness.INVALID_PANEL_GEOMETRY
    return if (registry.isSameLivePhysicalTarget(version, currentSurface)) {
        PhysicalDdpPanelReadiness.READY
    } else {
        PhysicalDdpPanelReadiness.NO_PHYSICAL_MAIN
    }
}

internal fun physicalMainDisplayOutputProven(
    registry: HostPhysicalDisplaySurfaceRegistry,
    currentSurface: Surface?,
    mainVisible: Boolean,
    main: AirPlayDisplayConfig,
): Boolean = physicalMainDisplayOutputReadiness(
    registry, currentSurface, mainVisible, main,
) == PhysicalDdpPanelReadiness.READY

/** Type-111 or PREVIEW_ONLY are insufficient; this check is a separate live target observation. */
internal fun physicalClusterDisplayOutputReadiness(
    registry: HostPhysicalDisplaySurfaceRegistry,
    cluster: AirPlayDisplayConfig?,
    destination: DisplayDestination = DisplayDestination.CLUSTER,
): PhysicalDdpPanelReadiness {
    if (cluster == null) return PhysicalDdpPanelReadiness.NO_CLUSTER
    require(destination.role == com.shilapi.xcertplay.ultra.DisplayDestinationRole.CLUSTER) {
        "A cluster DDP probe requires a CLUSTER destination"
    }
    val inspection = registry.inspectUniquePhysicalExternalClusterTarget()
        ?: return PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL
    val version = inspection.version
    if (version.destination != destination ||
        version.kind != HostDisplaySurfaceKind.PHYSICAL_EXTERNAL
    ) return PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL
    if (version.sizePixels.widthPixels != cluster.widthPixels ||
        version.sizePixels.heightPixels != cluster.heightPixels
    ) return PhysicalDdpPanelReadiness.PIXEL_GEOMETRY_MISMATCH
    if (!validPanelGeometry(cluster)) return PhysicalDdpPanelReadiness.INVALID_PANEL_GEOMETRY
    val calibration = inspection.externalCalibration
        ?: return PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL
    if (calibration.destination != destination || calibration.pixels != version.sizePixels ||
        calibration.widthPhysicalMm != cluster.widthPhysicalMm ||
        calibration.heightPhysicalMm != cluster.heightPhysicalMm
    ) return PhysicalDdpPanelReadiness.PHYSICAL_MM_MISMATCH
    return if (registry.isUniqueLiveExternalPhysicalTarget(
        version, inspection.surface, requireNotNull(inspection.externalTopologyGeneration),
    )) {
        PhysicalDdpPanelReadiness.READY
    } else {
        PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL
    }
}

internal fun physicalClusterDisplayOutputProven(
    registry: HostPhysicalDisplaySurfaceRegistry,
    cluster: AirPlayDisplayConfig?,
    destination: DisplayDestination = DisplayDestination.CLUSTER,
): Boolean = physicalClusterDisplayOutputReadiness(registry, cluster, destination) ==
    PhysicalDdpPanelReadiness.READY

/** Process-owned exact-generation proof; it captures no Activity, View, or TextureView. */
internal class PhysicalDdpPanelSessionProof private constructor(
    private val registry: HostPhysicalDisplaySurfaceRegistry,
    private val main: HostPhysicalTargetInspection,
    val externalClusterTarget: HostPhysicalTargetInspection?,
) {
    fun isCurrent(): Boolean =
        registry.isSameLivePhysicalTarget(main.version, main.surface) &&
            (externalClusterTarget == null || registry.isUniqueLiveExternalPhysicalTarget(
                externalClusterTarget.version,
                externalClusterTarget.surface,
                requireNotNull(externalClusterTarget.externalTopologyGeneration),
            ))

    /** Registration rechecks each exact target atomically inside the process registry. */
    fun subscribeChanges(onInvalidated: () -> Unit): Closeable {
        val notified = AtomicBoolean(false)
        val notifyOnce = { if (notified.compareAndSet(false, true)) onInvalidated() }
        val mainSubscription = registry.subscribePhysicalTargetInvalidation(
            main.version, main.surface, onInvalidated = notifyOnce,
        )
        val clusterSubscription = try {
            externalClusterTarget?.let {
                registry.subscribePhysicalTargetInvalidation(
                    it.version, it.surface, it.externalTopologyGeneration, notifyOnce,
                )
            }
        } catch (failure: Throwable) {
            try {
                mainSubscription.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return Closeable {
            mainSubscription.close()
            clusterSubscription?.close()
        }
    }

    companion object {
        fun capture(
            registry: HostPhysicalDisplaySurfaceRegistry,
            currentMainSurface: Surface?,
            mainVisible: Boolean,
            main: AirPlayDisplayConfig,
            cluster: AirPlayDisplayConfig?,
            includePhysicalCluster: Boolean,
            clusterDestination: DisplayDestination = DisplayDestination.CLUSTER,
        ): PhysicalDdpPanelSessionProof? {
            if (physicalMainDisplayOutputReadiness(
                registry, currentMainSurface, mainVisible, main,
            ) != PhysicalDdpPanelReadiness.READY) return null
            val mainTarget = registry.inspectPhysicalTarget(DisplayDestination.MAIN)
                ?.takeIf { it.version.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN &&
                    it.surface === currentMainSurface &&
                    it.version.sizePixels.widthPixels == main.widthPixels &&
                    it.version.sizePixels.heightPixels == main.heightPixels
                } ?: return null
            val clusterTarget = if (includePhysicalCluster) {
                if (physicalClusterDisplayOutputReadiness(
                    registry, cluster, clusterDestination,
                ) != PhysicalDdpPanelReadiness.READY) {
                    return null
                }
                val configuredCluster = cluster ?: return null
                registry.inspectUniquePhysicalExternalClusterTarget()
                    ?.takeIf { it.version.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                        it.version.destination == clusterDestination &&
                        it.version.sizePixels.widthPixels == configuredCluster.widthPixels &&
                        it.version.sizePixels.heightPixels == configuredCluster.heightPixels &&
                        it.externalCalibration?.widthPhysicalMm == configuredCluster.widthPhysicalMm &&
                        it.externalCalibration?.heightPhysicalMm == configuredCluster.heightPhysicalMm
                    } ?: return null
            } else {
                null
            }
            return PhysicalDdpPanelSessionProof(registry, mainTarget, clusterTarget)
                .takeIf(PhysicalDdpPanelSessionProof::isCurrent)
        }
    }
}

/**
 * Experimental DDP declaration only. It reuses the exact frozen legacy display geometry and
 * physical millimetres; it neither provisions a theme nor enables a UI Sync feature consumer.
 * Both output proofs default false. A configured legacy stream, browser endpoint, or preview is
 * never sufficient evidence of a physical panel.
 */
internal fun physicalDisplayPanelsProbe(
    ultraEnabled: Boolean,
    clusterDisplayEnabled: Boolean,
    optedIn: Boolean,
    main: AirPlayDisplayConfig,
    cluster: AirPlayDisplayConfig?,
    physicalMainOutputProven: Boolean = false,
    physicalClusterOutputProven: Boolean = false,
): AirPlayPhysicalDisplayPanels? {
    if (!ultraEnabled || !optedIn || !physicalMainOutputProven) return null

    fun panel(
        display: AirPlayDisplayConfig,
        uuid: String,
        pluginUid: String,
        properties: List<String>,
    ): AirPlayPhysicalDisplayPanel? {
        // The legacy /info serializer clamps millimetres to this range. Refuse mismatches instead
        // of silently advertising two different physical sizes for the same stream UUID.
        if (!validPanelGeometry(display)) return null
        val widthMm = requireNotNull(display.widthPhysicalMm)
        val heightMm = requireNotNull(display.heightPhysicalMm)
        val stream = AirPlayPhysicalVideoStream(
            uuid = uuid,
            zIndex = 0,
            originXPixels = 0,
            originYPixels = 0,
        )
        return AirPlayPhysicalDisplayPanel(
            widthPixels = display.widthPixels,
            heightPixels = display.heightPixels,
            widthPhysical = widthMm.toDouble(),
            heightPhysical = heightMm.toDouble(),
            initialVideoStreams = listOf(stream),
            displayPlugins = listOf(AirPlayPhysicalDisplayPlugin(pluginUid, listOf(stream))),
            properties = properties,
        )
    }

    val mainPanel = panel(
        main, AirPlayInfoPlist.MAIN_UUID, "xcertplay-main",
        listOf(AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY),
    ) ?: return null
    // The main display is sufficient for a DDP declaration. A type-111 stream, browser output,
    // or in-window preview is not a physical external display. The caller may provide this
    // separate proof only after validating a live PHYSICAL_EXTERNAL output; the current Host
    // has no such producer. Invalid ALT geometry must not contaminate the MAIN declaration.
    val clusterPanel = cluster.takeIf { clusterDisplayEnabled && physicalClusterOutputProven }?.let {
        // This Host's ALT type-111 route is a map preview. A physical route does not establish
        // gauge/indicator safety, so do not claim the separate Instrument Cluster role yet.
        panel(
            it, AirPlayInfoPlist.ALT_UUID, "xcertplay-cluster",
            listOf(AirPlayPhysicalDisplayPanel.PROPERTY_MAP),
        )
    }
    return AirPlayPhysicalDisplayPanels(listOfNotNull(mainPanel, clusterPanel))
}
