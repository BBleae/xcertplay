package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/**
 * One selectable view area, anchored at the display origin. The display itself is declared as the
 * envelope of all its areas, so switching areas never changes the negotiated stream size.
 */
data class AirPlayViewArea(
    val widthPixels: Int,
    val heightPixels: Int,
    val safeArea: AirPlayInsets? = null,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /** When non-empty, replaces the single [viewArea]/[safeArea] declaration. */
    val viewAreas: List<AirPlayViewArea> = emptyList(),
    val initialViewArea: Int = 0,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val wirelessAudio: Boolean = false,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
    /**
     * Optional, explicitly provisioned Ultra capabilities for this control session.
     *
     * Appended after the original constructor fields so existing positional callers retain their
     * source and data-class component ordering. Cluster geometry deliberately remains in [cluster].
     * [ultra] contains only protocol provisioning and immutable readiness evidence, so ordinary type-111
     * custom UI behavior and Ultra data channels cannot drift onto two different display
     * descriptions.
     */
    val ultra: AirPlayUltraConfiguration? = null,
    /**
     * Explicit physical DDP panels for `/info`. Null leaves legacy CarPlay unchanged. An explicit
     * list must be nonempty and reference only displays present in that response's feature scope.
     */
    val displayPanels: AirPlayPhysicalDisplayPanels? = null,
    /** Exact process-owned liveness for [displayPanels]; absent is not product DDP readiness. */
    val displayPanelsLiveness: AirPlayPhysicalDisplayPanelsLiveness? = null,
    /**
     * Set by the non-diagnostic AA05 publication path for a physical-panel candidate. At the
     * actual `/info` serialization, omit DDP panels if MAIN UI Sync readiness has since changed.
     * False preserves existing explicit callers and the opt-in Debug DDP-only probe.
     */
    val requireMainUiSyncForDisplayPanels: Boolean = false,
)
