package com.shilapi.xcertplay.airplay

/**
 * String features proposed by the phone in the AirPlay control-session SETUP.
 *
 * Knowing a feature name is not the same as supporting it. Runtime capability negotiation must
 * additionally require a configured, session-scoped handler and every transport prerequisite.
 */
enum class AirPlayFeature(
    val wireName: String,
    val infoResponseKey: String? = null,
) {
    UI_CONTEXT("uiContext"),
    VIEW_AREAS("viewAreas"),
    CORNER_MASKS("cornerMasks"),
    FOCUS_TRANSFER("focusTransfer"),
    H264_LEVEL_5_1("h.264Level5.1"),
    MAIN_BUFFERED("mainBuffered", "mainBufferedInfo"),
    ALT_SCREEN("altScreen"),
    ENHANCED_SIRI("enhancedSiri"),
    HEVC("hevc", "hevcInfo"),
    FILE_TRANSFER("fileTransfer", "fileTransferInfo"),
    VEHICLE_STATE_PROTOCOL("vehicleStateProtocol", "vehicleStateProtocolInfo"),
    SESSION_MANAGEMENT("sessionManagement", "sessionManagementInfo"),
    VIDEO_PLAYBACK("videoPlayback", "videoPlaybackInfo"),
    LOG_TRANSFER("logTransfer", "logTransferInfo"),
    UI_SYNC("uiSync", "uiSyncInfo"),
    IAP_CHANNEL("iAPChannel"),
    ;

    val requiresInfoResponseSidecar: Boolean
        get() = infoResponseKey != null

    companion object {
        private val byWireName = entries.associateBy(AirPlayFeature::wireName)

        fun fromWireName(value: String): AirPlayFeature? = byWireName[value]
    }
}
