package com.shilapi.xcertplay.airplay.rcs.catalog

import com.shilapi.xcertplay.airplay.AirPlayFeature
import java.util.UUID

enum class RcsPayloadStyle {
    CAF_BINARY_PLIST_OPACK,
    UI_SYNC_PLIST,
    RAW_IAP2,

    /** iOS 27 `CarPlayUpdateData` COMM body: OPACK header, ordered chunks, and terminal ACK. */
    UPDATE_DATA_OPACK,

    /** `comm` APTransport envelope carrying a binary-plist MatchPoint message. */
    MATCH_POINT_BINARY_PLIST,

    /** UUID and carrier are known, but the application-level schema is not confirmed. */
    UNCONFIRMED_OPAQUE,
}

/**
 * How much the static catalog knows about a client type.
 *
 * This is deliberately not runtime readiness. A confirmed schema still needs a live, negotiated
 * handler before it may be advertised. Metadata-only and unknown values must remain diagnostic
 * records and cannot become capabilities merely because a UUID was observed.
 */
enum class RcsCatalogReadiness {
    CONFIRMED_SCHEMA,
    METADATA_ONLY,
    UNKNOWN,
}

/** RCS client metadata from the CarPlay Ultra references. */
data class RcsClientType(
    val name: String,
    val uuid: UUID,
    val feature: AirPlayFeature?,
    val priority: Int? = null,
    val qualityOfService: Int? = null,
    val streamPriority: Int? = null,
    val withoutReply: Boolean,
    val payloadStyle: RcsPayloadStyle,
    val catalogReadiness: RcsCatalogReadiness,
) {
    val hasConfirmedSchema: Boolean
        get() = catalogReadiness == RcsCatalogReadiness.CONFIRMED_SCHEMA
}

object RcsClientTypes {
    val CAR_PLAY_PROTOCOL_DATA = RcsClientType(
        name = "CarPlayProtocolData",
        uuid = UUID.fromString("3E2F3C61-AAD0-42CB-A8AA-BF22186DA62E"),
        feature = AirPlayFeature.VEHICLE_STATE_PROTOCOL,
        priority = 0,
        qualityOfService = 0,
        streamPriority = 0,
        withoutReply = true,
        payloadStyle = RcsPayloadStyle.CAF_BINARY_PLIST_OPACK,
        catalogReadiness = RcsCatalogReadiness.CONFIRMED_SCHEMA,
    )
    val CAR_PLAY_PROTOCOL_DATA_2 = RcsClientType(
        name = "CarPlayProtocolData2",
        uuid = UUID.fromString("FF4A6720-F2BE-4F56-A3E1-DB3B4E37D634"),
        feature = AirPlayFeature.VEHICLE_STATE_PROTOCOL,
        priority = 1,
        qualityOfService = 12,
        streamPriority = 33,
        withoutReply = true,
        payloadStyle = RcsPayloadStyle.CAF_BINARY_PLIST_OPACK,
        catalogReadiness = RcsCatalogReadiness.CONFIRMED_SCHEMA,
    )
    val CAR_PLAY_CLUSTER_CONTROL = RcsClientType(
        name = "CarPlayClusterControl",
        uuid = UUID.fromString("07D9F906-8D64-4B54-A808-A20BCA2C51C2"),
        feature = AirPlayFeature.UI_SYNC,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UI_SYNC_PLIST,
        catalogReadiness = RcsCatalogReadiness.CONFIRMED_SCHEMA,
    )
    /** Confirmed iOS 27 theme `payloadType=1` transfer subset; no other payload type is claimed. */
    val CAR_PLAY_UPDATE_DATA = RcsClientType(
        name = "CarPlayUpdateData",
        uuid = UUID.fromString("09A8AA2D-8932-4048-9492-6B658D42C2A3"),
        feature = AirPlayFeature.FILE_TRANSFER,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UPDATE_DATA_OPACK,
        catalogReadiness = RcsCatalogReadiness.CONFIRMED_SCHEMA,
    )
    val CAR_PLAY_LOGGING_DATA = RcsClientType(
        name = "CarPlayLoggingData",
        uuid = UUID.fromString("75AD9926-4777-42B2-A7D8-823EBEECF7AA"),
        feature = AirPlayFeature.LOG_TRANSFER,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
        catalogReadiness = RcsCatalogReadiness.METADATA_ONLY,
    )
    /** FitnessUIOverlay has a plist carrier and narrow action subset, not a complete app schema. */
    val CAR_PLAY_VIDEO_OVERLAY_UI = RcsClientType(
        name = "CarPlayVideoOverlayUI",
        uuid = UUID.fromString("E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654"),
        feature = AirPlayFeature.VIDEO_PLAYBACK,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
        catalogReadiness = RcsCatalogReadiness.METADATA_ONLY,
    )
    val CAR_PLAY_VIDEO_SETTINGS = RcsClientType(
        name = "CarPlayVideoSettings",
        uuid = UUID.fromString("BB493F61-A6B8-4769-8D74-80C23A9F71C4"),
        feature = AirPlayFeature.VIDEO_PLAYBACK,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
        catalogReadiness = RcsCatalogReadiness.METADATA_ONLY,
    )
    /** Confirmed VideoPlayback remote-player carrier; receiver behavior remains incomplete. */
    val CAR_PLAY_MATCH_POINT = RcsClientType(
        name = "CarPlayMatchPoint",
        uuid = UUID.fromString("A6B27562-B43A-4F2D-B75F-82391E250194"),
        feature = AirPlayFeature.VIDEO_PLAYBACK,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.MATCH_POINT_BINARY_PLIST,
        catalogReadiness = RcsCatalogReadiness.METADATA_ONLY,
    )
    val IAP_CHANNEL = RcsClientType(
        name = "iAPChannel",
        uuid = UUID.fromString("E9459FD0-BCAD-4C45-820F-1E72447EF2F2"),
        feature = AirPlayFeature.IAP_CHANNEL,
        withoutReply = false,
        payloadStyle = RcsPayloadStyle.RAW_IAP2,
        catalogReadiness = RcsCatalogReadiness.CONFIRMED_SCHEMA,
    )

    val ALL: List<RcsClientType> = listOf(
        CAR_PLAY_PROTOCOL_DATA,
        CAR_PLAY_PROTOCOL_DATA_2,
        CAR_PLAY_CLUSTER_CONTROL,
        CAR_PLAY_UPDATE_DATA,
        CAR_PLAY_LOGGING_DATA,
        CAR_PLAY_VIDEO_OVERLAY_UI,
        CAR_PLAY_VIDEO_SETTINGS,
        CAR_PLAY_MATCH_POINT,
        IAP_CHANNEL,
    )

    private val byUuid = ALL.associateBy(RcsClientType::uuid)
    private val byName = ALL.associateBy(RcsClientType::name)

    fun find(uuid: UUID): RcsClientType? = byUuid[uuid]

    fun findByUuid(uuid: String): RcsClientType? =
        runCatching { UUID.fromString(uuid) }.getOrNull()?.let(::find)

    fun findByName(name: String): RcsClientType? = byName[name]

    /**
     * Returns catalog metadata without discarding an unrecognized UUID.
     *
     * Unknown client types are intentionally opaque and non-ready. [announcedName] is bounded and
     * retained only as diagnostic metadata; it does not participate in known-name lookup.
     */
    fun metadata(uuid: UUID, announcedName: String? = null): RcsClientType =
        find(uuid) ?: RcsClientType(
            name = announcedName
                ?.take(MAX_DIAGNOSTIC_NAME_CHARS)
                ?.filterNot(Char::isISOControl)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "UnknownRcsClient",
            uuid = uuid,
            feature = null,
            withoutReply = false,
            payloadStyle = RcsPayloadStyle.UNCONFIRMED_OPAQUE,
            catalogReadiness = RcsCatalogReadiness.UNKNOWN,
        )

    fun metadataByUuid(uuid: String, announcedName: String? = null): RcsClientType? =
        runCatching { UUID.fromString(uuid) }.getOrNull()?.let { metadata(it, announcedName) }

    fun require(uuid: UUID): RcsClientType =
        requireNotNull(byUuid[uuid]) { "Unknown RCS client UUID $uuid" }

    fun requireByUuid(uuid: String): RcsClientType =
        requireNotNull(findByUuid(uuid)) { "Unknown RCS client UUID $uuid" }

    fun requireByName(name: String): RcsClientType =
        requireNotNull(byName[name]) { "Unknown RCS client name $name" }

    private const val MAX_DIAGNOSTIC_NAME_CHARS = 128
}
