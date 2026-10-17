package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** A physical-panel stream bound to an already advertised legacy display UUID. */
data class AirPlayPhysicalVideoStream(
    val uuid: String,
    val zIndex: Int,
    val originXPixels: Int,
    val originYPixels: Int,
)

/** A DDP-capable display plug-in. `uid` is the lowercase key consumed by CarKit. */
data class AirPlayPhysicalDisplayPlugin(
    val uid: String,
    val managedVideoStreams: List<AirPlayPhysicalVideoStream>,
)

/** Explicit physical display panel metadata; independent of the legacy `displays` entries. */
data class AirPlayPhysicalDisplayPanel(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysical: Double,
    val heightPhysical: Double,
    val initialVideoStreams: List<AirPlayPhysicalVideoStream>,
    val displayPlugins: List<AirPlayPhysicalDisplayPlugin>,
    /** CarKit physical-screen roles; absent by default for existing explicit callers. */
    val properties: List<String> = emptyList(),
) {
    companion object {
        const val PROPERTY_CARPLAY = "CarPlay"
        const val PROPERTY_INSTRUMENT_CLUSTER = "Instrument Cluster"
        const val PROPERTY_MAP = "Map"
    }
}

/**
 * An immutable opt-in snapshot of caller-provided physical panels. The nested lists are copied at
 * construction so a later mutation cannot change a session's repeated `/info` response, including
 * through [AirPlayConfig.copy]. This does not imply iPhone acceptance or renderer readiness.
 */
class AirPlayPhysicalDisplayPanels(panels: List<AirPlayPhysicalDisplayPanel>) {
    private val frozenPanels: List<AirPlayPhysicalDisplayPanel> = Collections.unmodifiableList(
        panels.map { panel ->
            panel.copy(
                initialVideoStreams = Collections.unmodifiableList(ArrayList(panel.initialVideoStreams)),
                displayPlugins = Collections.unmodifiableList(
                    panel.displayPlugins.map { plugin ->
                        plugin.copy(
                            managedVideoStreams = Collections.unmodifiableList(ArrayList(plugin.managedVideoStreams)),
                        )
                    },
                ),
                properties = Collections.unmodifiableList(ArrayList(panel.properties)),
            )
        },
    )
    private val configuredStreamUuids: Set<String>

    init {
        // Validate the *whole* explicit configuration before any feature-scope projection. A bad
        // ALT entry cannot hide merely because altScreen was not negotiated in this response.
        AirPlayPhysicalDisplayPanelSerializer.toWire(frozenPanels, ALL_LEGACY_UUIDS)
        configuredStreamUuids = frozenPanels.flatMap { panel ->
            panel.initialVideoStreams.map(AirPlayPhysicalVideoStream::uuid)
        }.toSet()
    }

    /**
     * Returns the plug-in UID which manages one legacy `/info.displays` stream UUID. The UID is
     * a separate phone display namespace; matching it to a signed UI Sync display ID is a
     * compiler check, not proof that the associated Host Surface is physically present.
     */
    fun pluginUidManagingLegacyStream(uuid: String): String? = frozenPanels
        .asSequence()
        .flatMap { it.displayPlugins.asSequence() }
        .firstOrNull { plugin -> plugin.managedVideoStreams.any { it.uuid == uuid } }
        ?.uid

    /**
     * A lone origin-zero stream on one panel is this project's full-panel declaration. Its
     * reported geometry must agree with the legacy `/info.displays` entry for the same UUID;
     * otherwise iPhone can infer two different physical scales for one video stream. Composite
     * panels retain independent geometry and are not forced to equal any one constituent stream.
     */
    internal fun validateStandaloneLegacyGeometry(
        main: AirPlayDisplayConfig,
        cluster: AirPlayDisplayConfig?,
    ) {
        val legacy = linkedMapOf(AirPlayInfoPlist.MAIN_UUID to main)
        cluster?.let { legacy[AirPlayInfoPlist.ALT_UUID] = it }
        frozenPanels.forEachIndexed { index, panel ->
            val stream = panel.initialVideoStreams.singleOrNull() ?: return@forEachIndexed
            val plugin = panel.displayPlugins.singleOrNull() ?: return@forEachIndexed
            if (stream.originXPixels != 0 || stream.originYPixels != 0 ||
                plugin.managedVideoStreams.singleOrNull()?.uuid != stream.uuid
            ) return@forEachIndexed
            val display = legacy[stream.uuid] ?: return@forEachIndexed
            val widthMm = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
                display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
            )
            val heightMm = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
                display.heightPhysicalMm ?: Math.round(
                    widthMm * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
            )
            require(
                panel.widthPixels == display.widthPixels &&
                    panel.heightPixels == display.heightPixels &&
                    panel.widthPhysical == widthMm.toDouble() &&
                    panel.heightPhysical == heightMm.toDouble()
            ) {
                "displayPanels[$index] standalone geometry differs from its legacy display"
            }
        }
    }

    /**
     * A main-screen DDP offer must describe the entire legacy MAIN stream on one independently
     * physical panel with the explicit CarPlay role. A composite panel that merely projects to
     * MAIN when ALT is inactive, or a DDP-only MAIN panel, is not evidence of a CarPlay main
     * output. This is metadata validity, not renderer readiness.
     */
    internal fun hasStandaloneMainDdpPanel(
        main: AirPlayDisplayConfig,
        cluster: AirPlayDisplayConfig?,
    ): Boolean {
        if (main.widthPixels <= 0 || main.heightPixels <= 0) return false
        val configured = if (cluster == null) {
            setOf(AirPlayInfoPlist.MAIN_UUID)
        } else {
            setOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID)
        }
        val validConfiguration = runCatching {
            validateStandaloneLegacyGeometry(main, cluster)
            toWire(configured, setOf(AirPlayInfoPlist.MAIN_UUID))
        }.isSuccess
        if (!validConfiguration) return false
        return frozenPanels.any { panel ->
            val stream = panel.initialVideoStreams.singleOrNull()
            val plugin = panel.displayPlugins.singleOrNull()
            stream?.uuid == AirPlayInfoPlist.MAIN_UUID &&
                stream.zIndex == 0 &&
                stream.originXPixels == 0 && stream.originYPixels == 0 &&
                AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY in panel.properties &&
                plugin?.managedVideoStreams?.singleOrNull() == stream
        }
    }

    internal fun toWire(
        configuredLegacyUuids: Set<String>,
        activeLegacyUuids: Set<String>,
    ): List<Map<String, Any?>> {
        require(configuredLegacyUuids.all { it in ALL_LEGACY_UUIDS }) { "unknown configured legacy display UUID" }
        require(activeLegacyUuids.all { it in configuredLegacyUuids }) { "active display is not configured" }
        require(configuredStreamUuids.all { it in configuredLegacyUuids }) {
            "physical stream UUID has no configured legacy display"
        }
        val projected = frozenPanels.mapNotNull { panel ->
            val initial = panel.initialVideoStreams.filter { it.uuid in activeLegacyUuids }
            if (initial.isEmpty()) return@mapNotNull null
            val plugins = panel.displayPlugins.mapNotNull { plugin ->
                val managed = plugin.managedVideoStreams.filter { it.uuid in activeLegacyUuids }
                if (managed.isEmpty()) null else plugin.copy(managedVideoStreams = managed)
            }
            if (plugins.isEmpty()) null else panel.copy(initialVideoStreams = initial, displayPlugins = plugins)
        }
        if (projected.isEmpty()) return emptyList()
        return AirPlayPhysicalDisplayPanelSerializer.toWire(projected, activeLegacyUuids)
    }

    private companion object {
        val ALL_LEGACY_UUIDS = setOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID)
    }
}

/**
 * Per-session process-owned liveness for one exact immutable DDP declaration. The Host probe
 * should capture only its registry, target version and Surface, never an Activity or View.
 * Metadata alone cannot prove that the advertised physical panel still exists.
 */
class AirPlayPhysicalDisplayPanelsLiveness(
    private val panels: AirPlayPhysicalDisplayPanels,
    private val currentProbe: () -> Boolean,
    private val subscribeChanges: ((() -> Unit) -> Closeable),
) {
    fun isCurrentFor(candidate: AirPlayPhysicalDisplayPanels): Boolean =
        candidate === panels && runCatching { currentProbe() }.getOrDefault(false)

    /** Register before the final liveness check, so a concurrent Host withdrawal is not lost. */
    internal fun subscribeRevocation(
        candidate: AirPlayPhysicalDisplayPanels,
        onRevoked: () -> Unit,
    ): Closeable? {
        if (candidate !== panels) return null
        val delivered = AtomicBoolean(false)
        val notifyIfRevoked = {
            if (!isCurrentFor(candidate) && delivered.compareAndSet(false, true)) onRevoked()
        }
        val registration = subscribeChanges(notifyIfRevoked)
        notifyIfRevoked()
        return registration
    }
}

/** Runtime use of configured DDP panels requires exact live Host evidence, not just metadata. */
internal fun AirPlayConfig.hasCurrentPhysicalDisplayPanels(): Boolean =
    displayPanels?.let { displayPanelsLiveness?.isCurrentFor(it) == true } ?: true

/** Serializes only fields confirmed on the examined iOS 27 physical-display path. */
private object AirPlayPhysicalDisplayPanelSerializer {
    private const val MAX_PANELS = 16
    private const val MAX_PLUGINS_PER_PANEL = 16
    private const val MAX_STREAMS_PER_PANEL = 16
    private const val MAX_GEOMETRY = 1_000_000
    private const val MAX_UID_CHARS = 256
    private val KNOWN_PROPERTIES = setOf(
        AirPlayPhysicalDisplayPanel.PROPERTY_CARPLAY,
        AirPlayPhysicalDisplayPanel.PROPERTY_INSTRUMENT_CLUSTER,
        AirPlayPhysicalDisplayPanel.PROPERTY_MAP,
    )

    fun toWire(
        panels: List<AirPlayPhysicalDisplayPanel>,
        allowedStreamUuids: Set<String>,
    ): List<Map<String, Any?>> {
        require(panels.size in 1..MAX_PANELS) { "displayPanels count is out of bounds" }
        require(allowedStreamUuids.isNotEmpty()) { "no legacy display UUID is advertised" }
        val usedInitialUuids = hashSetOf<String>()
        val usedManagedUuids = hashSetOf<String>()
        val usedPluginUids = hashSetOf<String>()
        return panels.mapIndexed { panelIndex, panel ->
            val path = "displayPanels[$panelIndex]"
            require(panel.widthPixels in 1..MAX_GEOMETRY && panel.heightPixels in 1..MAX_GEOMETRY) {
                "$path pixel geometry is out of bounds"
            }
            require(panel.widthPhysical.isFinite() && panel.widthPhysical > 0.0 && panel.widthPhysical <= MAX_GEOMETRY) {
                "$path.widthPhysical is out of bounds"
            }
            require(panel.heightPhysical.isFinite() && panel.heightPhysical > 0.0 && panel.heightPhysical <= MAX_GEOMETRY) {
                "$path.heightPhysical is out of bounds"
            }
            require(panel.initialVideoStreams.size in 1..MAX_STREAMS_PER_PANEL) {
                "$path.initialVideoStreams count is out of bounds"
            }
            require(panel.displayPlugins.size in 1..MAX_PLUGINS_PER_PANEL) {
                "$path.displayPlugins must be a nonempty bounded array"
            }
            require(panel.properties.size <= KNOWN_PROPERTIES.size &&
                panel.properties.all { it in KNOWN_PROPERTIES } &&
                panel.properties.size == panel.properties.toSet().size
            ) {
                "$path.properties contains unknown or duplicate physical-screen roles"
            }
            val initialByUuid = linkedMapOf<String, AirPlayPhysicalVideoStream>()
            val initialStreams = panel.initialVideoStreams.mapIndexed { streamIndex, stream ->
                val streamPath = "$path.initialVideoStreams[$streamIndex]"
                validateStream(stream, allowedStreamUuids, streamPath)
                require(initialByUuid.putIfAbsent(stream.uuid, stream) == null) {
                    "$streamPath repeats a panel stream UUID"
                }
                require(usedInitialUuids.add(stream.uuid)) {
                    "$streamPath repeats a physical-panel stream UUID"
                }
                stream.toWire()
            }
            val plugins = panel.displayPlugins.mapIndexed { pluginIndex, plugin ->
                val pluginPath = "$path.displayPlugins[$pluginIndex]"
                require(plugin.uid.isNotBlank() && plugin.uid.length <= MAX_UID_CHARS &&
                    plugin.uid.none(Char::isISOControl)) {
                    "$pluginPath.uid is invalid"
                }
                require(usedPluginUids.add(plugin.uid)) { "$pluginPath.uid is duplicated" }
                require(plugin.managedVideoStreams.size in 1..MAX_STREAMS_PER_PANEL) {
                    "$pluginPath.managedVideoStreams count is out of bounds"
                }
                val pluginStreamUuids = hashSetOf<String>()
                val managedStreams = plugin.managedVideoStreams.mapIndexed { streamIndex, stream ->
                    val streamPath = "$pluginPath.managedVideoStreams[$streamIndex]"
                    validateStream(stream, allowedStreamUuids, streamPath)
                    require(pluginStreamUuids.add(stream.uuid) && usedManagedUuids.add(stream.uuid)) {
                        "$streamPath repeats a managed stream UUID"
                    }
                    require(stream.uuid in initialByUuid) {
                        "$streamPath.uuid does not match a panel initial stream UUID"
                    }
                    stream.toWire()
                }
                linkedMapOf<String, Any?>(
                    "uid" to plugin.uid,
                    "managedVideoStreams" to managedStreams,
                )
            }
            linkedMapOf<String, Any?>(
                "widthPixels" to panel.widthPixels,
                "heightPixels" to panel.heightPixels,
                "widthPhysical" to panel.widthPhysical,
                "heightPhysical" to panel.heightPhysical,
                "initialVideoStreams" to initialStreams,
                "displayPlugins" to plugins,
            ).also { wire ->
                if (panel.properties.isNotEmpty()) wire["properties"] = panel.properties
            }
        }
    }

    private fun validateStream(
        stream: AirPlayPhysicalVideoStream,
        allowedStreamUuids: Set<String>,
        path: String,
    ) {
        require(stream.uuid in allowedStreamUuids) {
            "$path.uuid must match an advertised legacy display UUID"
        }
        require(stream.zIndex in -MAX_GEOMETRY..MAX_GEOMETRY &&
            stream.originXPixels in -MAX_GEOMETRY..MAX_GEOMETRY &&
            stream.originYPixels in -MAX_GEOMETRY..MAX_GEOMETRY) {
            "$path stream placement is out of bounds"
        }
    }

    private fun AirPlayPhysicalVideoStream.toWire(): Map<String, Any?> = linkedMapOf(
        "uuid" to uuid,
        "zIndex" to zIndex,
        "originXPixels" to originXPixels,
        "originYPixels" to originYPixels,
    )
}
