package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationEventSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraUiSyncDisplayId
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Resource ceilings for the evidence-backed UI Sync semantic subset.
 *
 * These limits are intentionally independent of the carrier decoder's limits: snapshots can be
 * retained and supplied by another in-process source. Every string that is retained in semantic
 * state and every inspected dictionary key is charged to the aggregate character budget.
 */
data class CarPlayUiSyncSemanticLimits(
    val maximumStringChars: Int = 8 * 1024,
    val maximumMapEntries: Int = 1_024,
    val maximumAggregateStringChars: Int = 64 * 1024,
    val maximumOpaqueNestingDepth: Int = 16,
    val maximumOpaqueNodes: Int = 4_096,
    val maximumOpaqueDataBytes: Int = 1024 * 1024,
) {
    init {
        require(maximumStringChars in 1..ABSOLUTE_MAXIMUM_STRING_CHARS)
        require(maximumMapEntries in 1..ABSOLUTE_MAXIMUM_MAP_ENTRIES)
        require(maximumAggregateStringChars in
            maximumStringChars..ABSOLUTE_MAXIMUM_AGGREGATE_STRING_CHARS)
        require(maximumOpaqueNestingDepth in 1..ABSOLUTE_MAXIMUM_OPAQUE_NESTING_DEPTH)
        require(maximumOpaqueNodes in 1..ABSOLUTE_MAXIMUM_OPAQUE_NODES)
        require(maximumOpaqueDataBytes in 1..ABSOLUTE_MAXIMUM_OPAQUE_DATA_BYTES)
    }

    private companion object {
        const val ABSOLUTE_MAXIMUM_STRING_CHARS = 64 * 1024
        const val ABSOLUTE_MAXIMUM_MAP_ENTRIES = 16 * 1024
        const val ABSOLUTE_MAXIMUM_AGGREGATE_STRING_CHARS = 4 * 1024 * 1024
        const val ABSOLUTE_MAXIMUM_OPAQUE_NESTING_DEPTH = 64
        const val ABSOLUTE_MAXIMUM_OPAQUE_NODES = 65_536
        const val ABSOLUTE_MAXIMUM_OPAQUE_DATA_BYTES = 16 * 1024 * 1024
    }
}

/**
 * Deeply frozen UI Sync value whose inner semantic field names are not yet established.
 *
 * Reads return detached, read-only collections and copied byte arrays. Diagnostics intentionally
 * expose only the top-level shape, never identifiers or payload text.
 */
class CarPlayUiSyncSemanticOpaqueValue internal constructor(
    private val storedValue: Any?,
) {
    val value: Any?
        get() = copySemanticFrozenValue(storedValue)

    override fun equals(other: Any?): Boolean =
        other is CarPlayUiSyncSemanticOpaqueValue &&
            semanticFrozenEquals(storedValue, other.storedValue)

    override fun hashCode(): Int = semanticFrozenHashCode(storedValue)

    override fun toString(): String = when (storedValue) {
        is Map<*, *> -> "CarPlayUiSyncSemanticOpaqueValue(dictionary=${storedValue.size})"
        is List<*> -> "CarPlayUiSyncSemanticOpaqueValue(array=${storedValue.size})"
        is ByteArray -> "CarPlayUiSyncSemanticOpaqueValue(data=${storedValue.size} bytes)"
        null -> "CarPlayUiSyncSemanticOpaqueValue(null)"
        else -> "CarPlayUiSyncSemanticOpaqueValue(${storedValue.javaClass.simpleName})"
    }
}

/**
 * Direction-neutral typed payloads whose iOS 27 dictionary shape is evidenced.
 * [CarPlayUiSyncSemanticReducer] separately rejects accessory-to-iPhone commands on receive.
 */
sealed interface CarPlayUiSyncSemanticCommand {
    val type: CarPlayUiSyncCommandType

    /** Typed command-type-1 variants; decoding a shape is not proof of a live phone transition. */
    sealed interface TransitionStart : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType
            get() = CarPlayUiSyncCommandType.TRANSITION_START

        /** The request-content transition sends an empty dictionary. */
        data object RequestContent : TransitionStart

        /**
         * The iOS 27 layout-transition sender builds this exact three-field dictionary. Its
         * final dynamic call target has not been captured on the wire, so this is a bounded
         * receive variant only: it must never authorize a selected layout or foreground proof.
         */
        class LayoutIntent internal constructor(
            val layoutID: String,
            activeComponents: Map<String, String>,
            val paletteID: String,
        ) : TransitionStart {
            val activeComponents: Map<String, String> = immutableStringMap(activeComponents)

            override fun equals(other: Any?): Boolean =
                other is LayoutIntent && layoutID == other.layoutID &&
                    activeComponents == other.activeComponents && paletteID == other.paletteID

            override fun hashCode(): Int =
                31 * (31 * layoutID.hashCode() + activeComponents.hashCode()) +
                    paletteID.hashCode()

            override fun toString(): String =
                "TransitionStart.LayoutIntent(activeComponentCount=${activeComponents.size})"
        }

        /**
         * DCA/Deck transition metadata. Region dictionaries remain open and bounded; only their
         * outer array type is confirmed. NTP is retained as raw UInt64 bits in a [Long].
         */
        class DynamicContent internal constructor(
            regions: List<CarPlayUiSyncSemanticOpaqueValue>,
            val currentNtpTimeBits: Long,
            targetComponents: Map<String, String>,
        ) : TransitionStart {
            val regions: List<CarPlayUiSyncSemanticOpaqueValue> =
                Collections.unmodifiableList(ArrayList(regions))
            val targetComponents: Map<String, String> = immutableStringMap(targetComponents)

            override fun equals(other: Any?): Boolean =
                other is DynamicContent && regions == other.regions &&
                    currentNtpTimeBits == other.currentNtpTimeBits &&
                    targetComponents == other.targetComponents

            override fun hashCode(): Int =
                31 * (31 * regions.hashCode() + currentNtpTimeBits.hashCode()) +
                    targetComponents.hashCode()

            override fun toString(): String =
                "TransitionStart.DynamicContent(regionCount=${regions.size}, " +
                    "targetComponentCount=${targetComponents.size})"
        }
    }

    /**
     * iPhone-originated type-3 layout intent. The three fields are an exact wire dictionary;
     * receiving it does not prove that any display or renderer has adopted the layout.
     */
    class LayoutChange internal constructor(
        val layoutID: String,
        activeComponents: Map<String, String>,
        val paletteID: String,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.LAYOUT_CHANGE
        val activeComponents: Map<String, String> = immutableStringMap(activeComponents)

        override fun equals(other: Any?): Boolean =
            other is LayoutChange && layoutID == other.layoutID &&
                activeComponents == other.activeComponents && paletteID == other.paletteID

        override fun hashCode(): Int =
            31 * (31 * layoutID.hashCode() + activeComponents.hashCode()) +
                paletteID.hashCode()

        override fun toString(): String =
            "LayoutChange(activeComponentCount=${activeComponents.size})"
    }

    /**
     * A transition timing update. All clock values are retained as raw UInt64 bits in [Long]s;
     * no clock-domain conversion or ordering is inferred here.
     */
    class TransitionData internal constructor(
        regions: List<CarPlayUiSyncSemanticOpaqueValue>,
        val presentationHostTimeBits: Long,
        val presentationNtpTimeBits: Long,
        val presentationNtpTimeOriginalBits: Long?,
        val currentNtpTimeBits: Long,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.TRANSITION_DATA
        val regions: List<CarPlayUiSyncSemanticOpaqueValue> =
            Collections.unmodifiableList(ArrayList(regions))

        override fun equals(other: Any?): Boolean =
            other is TransitionData && regions == other.regions &&
                presentationHostTimeBits == other.presentationHostTimeBits &&
                presentationNtpTimeBits == other.presentationNtpTimeBits &&
                presentationNtpTimeOriginalBits == other.presentationNtpTimeOriginalBits &&
                currentNtpTimeBits == other.currentNtpTimeBits

        override fun hashCode(): Int {
            var result = regions.hashCode()
            result = 31 * result + presentationHostTimeBits.hashCode()
            result = 31 * result + presentationNtpTimeBits.hashCode()
            result = 31 * result + (presentationNtpTimeOriginalBits?.hashCode() ?: 0)
            result = 31 * result + currentNtpTimeBits.hashCode()
            return result
        }

        override fun toString(): String =
            "TransitionData(regionCount=${regions.size}, " +
                "hasOriginalNtpTime=${presentationNtpTimeOriginalBits != null})"
    }

    /** A received transition-end packet; this is not proof that presentation has completed. */
    class TransitionEnd internal constructor(
        regions: List<CarPlayUiSyncSemanticOpaqueValue>,
        val currentNtpTimeBits: Long,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.TRANSITION_END
        val regions: List<CarPlayUiSyncSemanticOpaqueValue> =
            Collections.unmodifiableList(ArrayList(regions))

        override fun equals(other: Any?): Boolean =
            other is TransitionEnd && regions == other.regions &&
                currentNtpTimeBits == other.currentNtpTimeBits

        override fun hashCode(): Int = 31 * regions.hashCode() + currentNtpTimeBits.hashCode()
        override fun toString(): String = "TransitionEnd(regionCount=${regions.size})"
    }

    /** Type-6 wire variants. Neither variant is a renderer-completion receipt. */
    sealed interface EndLayoutChange : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType
            get() = CarPlayUiSyncCommandType.END_LAYOUT_CHANGE

        /** Normal sender form; NTP is retained as raw UInt64 bits. */
        class Timed internal constructor(
            regions: List<CarPlayUiSyncSemanticOpaqueValue>,
            val currentNtpTimeBits: Long,
        ) : EndLayoutChange {
            val regions: List<CarPlayUiSyncSemanticOpaqueValue> =
                Collections.unmodifiableList(ArrayList(regions))

            override fun equals(other: Any?): Boolean =
                other is Timed && regions == other.regions &&
                    currentNtpTimeBits == other.currentNtpTimeBits

            override fun hashCode(): Int = 31 * regions.hashCode() + currentNtpTimeBits.hashCode()
            override fun toString(): String = "EndLayoutChange.Timed(regionCount=${regions.size})"
        }

        /** Bounded, deeply frozen echo of accessory request data; its inner schema is unproven. */
        class RequestEcho internal constructor(
            val requestData: CarPlayUiSyncSemanticOpaqueValue,
        ) : EndLayoutChange {
            override fun equals(other: Any?): Boolean =
                other is RequestEcho && requestData == other.requestData

            override fun hashCode(): Int = requestData.hashCode()
            override fun toString(): String = "EndLayoutChange.RequestEcho"
        }
    }

    class MetadataTransfer internal constructor(
        val isTransitioning: Boolean,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.METADATA_TRANSFER

        override fun equals(other: Any?): Boolean =
            other is MetadataTransfer && isTransitioning == other.isTransitioning

        override fun hashCode(): Int = isTransitioning.hashCode()
        override fun toString(): String = "MetadataTransfer(isTransitioning=$isTransitioning)"
    }

    /**
     * Accessory-to-iPhone request; never a received/current-layout state claim.
     *
     * The optional/default behavior mirrors the observed dictionary initializer. In particular,
     * a missing or wrongly typed fade flag defaults to `true`, a missing or wrongly typed
     * `activeComponents` defaults to an empty map, and an absent `uiConfiguration` remains null.
     */
    class RequestLayout internal constructor(
        val layoutID: String?,
        val fadeInNewLayout: Boolean,
        val fadeOutOldLayout: Boolean,
        activeComponents: Map<String, String>,
        val appearanceMode: Int?,
        val driveMode: String?,
        uiConfiguration: Map<String, String>?,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.REQUEST_LAYOUT
        val activeComponents: Map<String, String> = immutableStringMap(activeComponents)
        val uiConfiguration: Map<String, String>? =
            uiConfiguration?.let(::immutableStringMap)

        override fun equals(other: Any?): Boolean =
            other is RequestLayout &&
                layoutID == other.layoutID &&
                fadeInNewLayout == other.fadeInNewLayout &&
                fadeOutOldLayout == other.fadeOutOldLayout &&
                activeComponents == other.activeComponents &&
                appearanceMode == other.appearanceMode &&
                driveMode == other.driveMode &&
                uiConfiguration == other.uiConfiguration

        override fun hashCode(): Int {
            var result = layoutID?.hashCode() ?: 0
            result = 31 * result + fadeInNewLayout.hashCode()
            result = 31 * result + fadeOutOldLayout.hashCode()
            result = 31 * result + activeComponents.hashCode()
            result = 31 * result + (appearanceMode ?: 0)
            result = 31 * result + (driveMode?.hashCode() ?: 0)
            result = 31 * result + (uiConfiguration?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "RequestLayout(hasLayoutID=${layoutID != null}, " +
                "fadeInNewLayout=$fadeInNewLayout, fadeOutOldLayout=$fadeOutOldLayout, " +
                "activeComponentCount=${activeComponents.size}, " +
                "appearanceMode=$appearanceMode, hasDriveMode=${driveMode != null}, " +
                "uiConfigurationEntryCount=${uiConfiguration?.size})"
    }

    class GiveFocus internal constructor(
        /** Swift `Int` is 64-bit on the observed target; retain that full signed range. */
        val tokenID: Long,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.GIVE_FOCUS

        override fun equals(other: Any?): Boolean = other is GiveFocus && tokenID == other.tokenID
        override fun hashCode(): Int = tokenID.hashCode()
        override fun toString(): String = "GiveFocus(tokenID=<redacted>)"
    }

    class PaletteChange internal constructor(
        val layoutIdentifier: String,
        val paletteIdentifier: String,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.PALETTE_CHANGE

        override fun equals(other: Any?): Boolean =
            other is PaletteChange &&
                layoutIdentifier == other.layoutIdentifier &&
                paletteIdentifier == other.paletteIdentifier

        override fun hashCode(): Int =
            31 * layoutIdentifier.hashCode() + paletteIdentifier.hashCode()

        override fun toString(): String =
            "PaletteChange(layoutIdentifier=<redacted>, paletteIdentifier=<redacted>)"
    }

    /** The raw evidenced integer is preserved, including `-1`; it is not coerced to an enum. */
    class TargetAppearanceChange internal constructor(
        val appearanceMode: Int,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType =
            CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE

        override fun equals(other: Any?): Boolean =
            other is TargetAppearanceChange && appearanceMode == other.appearanceMode

        override fun hashCode(): Int = appearanceMode
        override fun toString(): String = "TargetAppearanceChange(appearanceMode=$appearanceMode)"
    }

    class DriveModeChange internal constructor(
        val driveMode: String,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType = CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE

        override fun equals(other: Any?): Boolean =
            other is DriveModeChange && driveMode == other.driveMode

        override fun hashCode(): Int = driveMode.hashCode()
        override fun toString(): String = "DriveModeChange(driveMode=<redacted>)"
    }

    /** Raw preference values are retained; only the evidenced override domain is constrained. */
    class AppearancePreferenceChange internal constructor(
        val preference: Int,
        val appearanceOverride: Int?,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType =
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE

        override fun equals(other: Any?): Boolean =
            other is AppearancePreferenceChange && preference == other.preference &&
                appearanceOverride == other.appearanceOverride

        override fun hashCode(): Int = 31 * preference + (appearanceOverride ?: 0)
        override fun toString(): String =
            "AppearancePreferenceChange(preference=$preference, " +
                "override=$appearanceOverride)"
    }

    sealed interface AppearanceChangeBoundary : CarPlayUiSyncSemanticCommand {
        /** Observed raw domain: 0 = light, 1 = dark. */
        val targetAppearance: Int
    }

    class AppearanceChangeStart internal constructor(
        override val targetAppearance: Int,
    ) : AppearanceChangeBoundary {
        override val type: CarPlayUiSyncCommandType =
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START

        override fun equals(other: Any?): Boolean =
            other is AppearanceChangeStart && targetAppearance == other.targetAppearance

        override fun hashCode(): Int = targetAppearance
        override fun toString(): String =
            "AppearanceChangeStart(targetAppearance=$targetAppearance)"
    }

    /** Receipt of this packet is recorded without claiming the visual transition has completed. */
    class AppearanceChangeEnd internal constructor(
        override val targetAppearance: Int,
    ) : AppearanceChangeBoundary {
        override val type: CarPlayUiSyncCommandType =
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END

        override fun equals(other: Any?): Boolean =
            other is AppearanceChangeEnd && targetAppearance == other.targetAppearance

        override fun hashCode(): Int = targetAppearance
        override fun toString(): String =
            "AppearanceChangeEnd(targetAppearance=$targetAppearance)"
    }

    class DriveModeThemeConfiguration internal constructor(
        val colorPaletteId: String,
        val wallpaperId: String?,
        val layoutId: String?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is DriveModeThemeConfiguration && colorPaletteId == other.colorPaletteId &&
                wallpaperId == other.wallpaperId && layoutId == other.layoutId

        override fun hashCode(): Int {
            var result = colorPaletteId.hashCode()
            result = 31 * result + (wallpaperId?.hashCode() ?: 0)
            result = 31 * result + (layoutId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "DriveModeThemeConfiguration(hasWallpaper=${wallpaperId != null}, " +
                "hasLayout=${layoutId != null})"
    }

    class DriveModeThemeConfigurationOverrideChange internal constructor(
        configurations: Map<String, DriveModeThemeConfiguration>,
        val linked: Boolean,
    ) : CarPlayUiSyncSemanticCommand {
        override val type: CarPlayUiSyncCommandType =
            CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE
        val configurations: Map<String, DriveModeThemeConfiguration> =
            Collections.unmodifiableMap(LinkedHashMap(configurations))

        override fun equals(other: Any?): Boolean =
            other is DriveModeThemeConfigurationOverrideChange &&
                configurations == other.configurations && linked == other.linked

        override fun hashCode(): Int = 31 * configurations.hashCode() + linked.hashCode()
        override fun toString(): String =
            "DriveModeThemeConfigurationOverrideChange(" +
                "configurationCount=${configurations.size}, linked=$linked)"
    }
}

enum class CarPlayUiSyncSemanticDecodeFailure {
    UNKNOWN_COMMAND,
    UNSUPPORTED_COMMAND,
    DATA_NOT_DICTIONARY,
    INVALID_DICTIONARY,
    INVALID_KEYS,
    MISSING_FIELD,
    INVALID_FIELD_TYPE,
    INVALID_APPEARANCE_MODE,
    INVALID_OVERRIDE_VALUE,
    LIMIT_EXCEEDED,
}

sealed interface CarPlayUiSyncSemanticDecodeResult {
    data class Decoded(
        val command: CarPlayUiSyncSemanticCommand,
    ) : CarPlayUiSyncSemanticDecodeResult

    data class Rejected(
        val type: Int,
        val failure: CarPlayUiSyncSemanticDecodeFailure,
        /** A bounded, implementation-owned wire field name; never untrusted payload text. */
        val field: String? = null,
    ) : CarPlayUiSyncSemanticDecodeResult
}

/**
 * Strict, direction-neutral decoder for the small UI Sync semantic subset established from iOS
 * 27 evidence. It is also useful for validating an outbound command before serialization.
 *
 * This class does not advertise UI Sync support or install itself into any product path. The
 * received-state reducer applies [CarPlayUiSyncCommandType.confirmedDirection] after decoding.
 * Commands outside the evidenced payloads are explicitly rejected instead of being guessed.
 */
class CarPlayUiSyncSemanticDecoder(
    private val limits: CarPlayUiSyncSemanticLimits = CarPlayUiSyncSemanticLimits(),
) {
    fun decode(event: CarPlayUiSyncPresentationEvent): CarPlayUiSyncSemanticDecodeResult =
        decode(event.type, event.data)

    fun decode(event: UltraPresentationEventSnapshot): CarPlayUiSyncSemanticDecodeResult =
        decode(event.type, event.data)

    fun decode(type: Int, data: Any?): CarPlayUiSyncSemanticDecodeResult {
        val knownType = CarPlayUiSyncCommandType.fromWireValue(type)
            ?: return rejected(type, CarPlayUiSyncSemanticDecodeFailure.UNKNOWN_COMMAND)
        if (knownType !in SUPPORTED_TYPES) {
            return rejected(type, CarPlayUiSyncSemanticDecodeFailure.UNSUPPORTED_COMMAND)
        }

        val budget = SemanticBudget(limits)
        return try {
            val dictionary = budget.dictionary(data)
                ?: return rejected(type, CarPlayUiSyncSemanticDecodeFailure.DATA_NOT_DICTIONARY)
            when (knownType) {
                CarPlayUiSyncCommandType.TRANSITION_START ->
                    decodeTransitionStart(dictionary, budget)
                CarPlayUiSyncCommandType.TRANSITION_DATA ->
                    decodeTransitionData(dictionary, budget)
                CarPlayUiSyncCommandType.LAYOUT_CHANGE ->
                    decodeLayoutChange(dictionary, budget)
                CarPlayUiSyncCommandType.TRANSITION_END ->
                    decodeTransitionEnd(dictionary, budget)
                CarPlayUiSyncCommandType.REQUEST_LAYOUT -> decodeRequestLayout(dictionary, budget)
                CarPlayUiSyncCommandType.END_LAYOUT_CHANGE ->
                    decodeEndLayoutChange(dictionary, budget)
                CarPlayUiSyncCommandType.GIVE_FOCUS -> decodeGiveFocus(dictionary, budget)
                CarPlayUiSyncCommandType.METADATA_TRANSFER ->
                    decodeMetadataTransfer(dictionary, budget)
                CarPlayUiSyncCommandType.PALETTE_CHANGE ->
                    decodePaletteChange(dictionary, budget)
                CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE ->
                    decodeTargetAppearanceChange(dictionary, budget)
                CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE ->
                    decodeAppearancePreferenceChange(dictionary, budget)
                CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE ->
                    decodeDriveModeChange(dictionary, budget)
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START ->
                    decodeAppearanceChangeBoundary(dictionary, budget, start = true)
                CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END ->
                    decodeAppearanceChangeBoundary(dictionary, budget, start = false)
                CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE ->
                    decodeDriveModeThemeConfigurationOverrideChange(dictionary, budget)
                else -> rejected(type, CarPlayUiSyncSemanticDecodeFailure.UNSUPPORTED_COMMAND)
            }
        } catch (failure: SemanticDecodeException) {
            rejected(type, failure.failure, failure.field)
        }
    }

    private fun decodeTransitionStart(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        val command = when (values.keys) {
            emptySet<String>() -> CarPlayUiSyncSemanticCommand.TransitionStart.RequestContent
            setOf(LAYOUT_ID, ACTIVE_COMPONENTS, PALETTE_ID) ->
                CarPlayUiSyncSemanticCommand.TransitionStart.LayoutIntent(
                    layoutID = budget.exactString(values[LAYOUT_ID], LAYOUT_ID),
                    activeComponents = budget.exactStringMap(
                        values[ACTIVE_COMPONENTS], ACTIVE_COMPONENTS,
                    ),
                    paletteID = budget.exactString(values[PALETTE_ID], PALETTE_ID),
                )
            setOf(REGIONS, CURRENT_NTP_TIME, TARGET_COMPONENTS) ->
                CarPlayUiSyncSemanticCommand.TransitionStart.DynamicContent(
                    regions = budget.exactOpaqueDictionaryList(values[REGIONS], REGIONS),
                    currentNtpTimeBits = exactUInt64Bits(
                        values[CURRENT_NTP_TIME],
                        CURRENT_NTP_TIME,
                    ),
                    targetComponents = budget.exactStringMap(
                        values[TARGET_COMPONENTS],
                        TARGET_COMPONENTS,
                    ),
                )
            else -> throw SemanticDecodeException(
                CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
            )
        }
        return CarPlayUiSyncSemanticDecodeResult.Decoded(command)
    }

    private fun decodeLayoutChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(LAYOUT_ID, ACTIVE_COMPONENTS, PALETTE_ID))
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.LayoutChange(
                layoutID = budget.exactString(values[LAYOUT_ID], LAYOUT_ID),
                activeComponents = budget.exactStringMap(
                    values[ACTIVE_COMPONENTS],
                    ACTIVE_COMPONENTS,
                ),
                paletteID = budget.exactString(values[PALETTE_ID], PALETTE_ID),
            ),
        )
    }

    private fun decodeTransitionData(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        val requiredKeys = setOf(
            REGIONS,
            PRESENTATION_HOST_TIME,
            PRESENTATION_NTP_TIME,
            CURRENT_NTP_TIME,
        )
        if (values.keys != requiredKeys &&
            values.keys != requiredKeys + PRESENTATION_NTP_TIME_ORIGINAL
        ) {
            throw SemanticDecodeException(CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS)
        }
        val presentationHostTimeBits = exactUInt64Bits(
            values[PRESENTATION_HOST_TIME],
            PRESENTATION_HOST_TIME,
        )
        if (presentationHostTimeBits == 0L) {
            throw SemanticDecodeException(
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
                PRESENTATION_HOST_TIME,
            )
        }
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.TransitionData(
                regions = budget.exactOpaqueDictionaryList(values[REGIONS], REGIONS),
                presentationHostTimeBits = presentationHostTimeBits,
                presentationNtpTimeBits = exactUInt64Bits(
                    values[PRESENTATION_NTP_TIME],
                    PRESENTATION_NTP_TIME,
                ),
                presentationNtpTimeOriginalBits =
                    if (values.containsKey(PRESENTATION_NTP_TIME_ORIGINAL)) {
                        exactUInt64Bits(
                            values[PRESENTATION_NTP_TIME_ORIGINAL],
                            PRESENTATION_NTP_TIME_ORIGINAL,
                        )
                    } else {
                        null
                    },
                currentNtpTimeBits = exactUInt64Bits(
                    values[CURRENT_NTP_TIME],
                    CURRENT_NTP_TIME,
                ),
            ),
        )
    }

    private fun decodeTransitionEnd(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(REGIONS, CURRENT_NTP_TIME))
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.TransitionEnd(
                regions = budget.exactOpaqueDictionaryList(values[REGIONS], REGIONS),
                currentNtpTimeBits = exactUInt64Bits(
                    values[CURRENT_NTP_TIME],
                    CURRENT_NTP_TIME,
                ),
            ),
        )
    }

    private fun decodeRequestLayout(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        val command = CarPlayUiSyncSemanticCommand.RequestLayout(
            layoutID = budget.optionalString(values[LAYOUT_ID], LAYOUT_ID),
            fadeInNewLayout = values[FADE_IN] as? Boolean ?: true,
            fadeOutOldLayout = values[FADE_OUT] as? Boolean ?: true,
            activeComponents = budget.optionalStringMap(
                values[ACTIVE_COMPONENTS],
                ACTIVE_COMPONENTS,
            ) ?: emptyMap(),
            appearanceMode = optionalAppearanceMode(values[APPEARANCE_MODE]),
            driveMode = budget.optionalString(values[DRIVE_MODE], DRIVE_MODE),
            uiConfiguration = budget.optionalStringMap(
                values[UI_CONFIGURATION],
                UI_CONFIGURATION,
            ),
        )
        return CarPlayUiSyncSemanticDecodeResult.Decoded(command)
    }

    private fun decodeGiveFocus(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(TOKEN_ID))
        val token = exactLong(values[TOKEN_ID], TOKEN_ID)
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.GiveFocus(token),
        )
    }

    private fun decodeEndLayoutChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        val command = if (REGIONS in values || CURRENT_NTP_TIME in values) {
            // A malformed timed form must not silently become an opaque request echo.
            budget.requireExactKeys(values, setOf(REGIONS, CURRENT_NTP_TIME))
            CarPlayUiSyncSemanticCommand.EndLayoutChange.Timed(
                regions = budget.exactOpaqueDictionaryList(values[REGIONS], REGIONS),
                currentNtpTimeBits = exactUInt64Bits(
                    values[CURRENT_NTP_TIME],
                    CURRENT_NTP_TIME,
                ),
            )
        } else {
            CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho(
                requestData = budget.opaqueDictionary(values, "requestData"),
            )
        }
        return CarPlayUiSyncSemanticDecodeResult.Decoded(command)
    }

    private fun decodeMetadataTransfer(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(IS_TRANSITIONING))
        val isTransitioning = values[IS_TRANSITIONING] as? Boolean
            ?: throw SemanticDecodeException(
                if (values[IS_TRANSITIONING] == null) {
                    CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
                } else {
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
                },
                IS_TRANSITIONING,
            )
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.MetadataTransfer(isTransitioning),
        )
    }

    private fun decodePaletteChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(LAYOUT_IDENTIFIER, PALETTE_IDENTIFIER))
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.PaletteChange(
                layoutIdentifier = budget.exactString(
                    values[LAYOUT_IDENTIFIER],
                    LAYOUT_IDENTIFIER,
                ),
                paletteIdentifier = budget.exactString(
                    values[PALETTE_IDENTIFIER],
                    PALETTE_IDENTIFIER,
                ),
            ),
        )
    }

    private fun decodeTargetAppearanceChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(APPEARANCE_MODE))
        val appearanceMode = exactInt(values[APPEARANCE_MODE], APPEARANCE_MODE)
        if (appearanceMode !in VALID_APPEARANCE_MODES) {
            throw SemanticDecodeException(
                CarPlayUiSyncSemanticDecodeFailure.INVALID_APPEARANCE_MODE,
                APPEARANCE_MODE,
            )
        }
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.TargetAppearanceChange(appearanceMode),
        )
    }

    private fun decodeDriveModeChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(DRIVE_MODE))
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.DriveModeChange(
                budget.exactString(values[DRIVE_MODE], DRIVE_MODE),
            ),
        )
    }

    private fun decodeAppearancePreferenceChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        val requiredKeys = setOf(PREFERENCE)
        if (values.keys != requiredKeys && values.keys != requiredKeys + OVERRIDE) {
            throw SemanticDecodeException(CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS)
        }
        val override = if (values.containsKey(OVERRIDE)) {
            exactInt(values[OVERRIDE], OVERRIDE).also {
                if (it !in VALID_APPEARANCE_OVERRIDES) {
                    throw SemanticDecodeException(
                        CarPlayUiSyncSemanticDecodeFailure.INVALID_OVERRIDE_VALUE,
                        OVERRIDE,
                    )
                }
            }
        } else {
            null
        }
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.AppearancePreferenceChange(
                preference = exactInt(values[PREFERENCE], PREFERENCE),
                appearanceOverride = override,
            ),
        )
    }

    private fun decodeAppearanceChangeBoundary(
        values: Map<String, Any?>,
        budget: SemanticBudget,
        start: Boolean,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(TARGET_APPEARANCE))
        val targetAppearance = exactInt(values[TARGET_APPEARANCE], TARGET_APPEARANCE)
        if (targetAppearance !in VALID_TARGET_APPEARANCES) {
            throw SemanticDecodeException(
                CarPlayUiSyncSemanticDecodeFailure.INVALID_APPEARANCE_MODE,
                TARGET_APPEARANCE,
            )
        }
        val command = if (start) {
            CarPlayUiSyncSemanticCommand.AppearanceChangeStart(targetAppearance)
        } else {
            CarPlayUiSyncSemanticCommand.AppearanceChangeEnd(targetAppearance)
        }
        return CarPlayUiSyncSemanticDecodeResult.Decoded(command)
    }

    private fun decodeDriveModeThemeConfigurationOverrideChange(
        values: Map<String, Any?>,
        budget: SemanticBudget,
    ): CarPlayUiSyncSemanticDecodeResult {
        budget.requireExactKeys(values, setOf(CONFIGURATIONS, LINKED))
        val linked = values[LINKED] as? Boolean ?: throw SemanticDecodeException(
            if (values[LINKED] == null) {
                CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
            } else {
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
            },
            LINKED,
        )
        return CarPlayUiSyncSemanticDecodeResult.Decoded(
            CarPlayUiSyncSemanticCommand.DriveModeThemeConfigurationOverrideChange(
                configurations = budget.exactThemeConfigurationMap(
                    values[CONFIGURATIONS],
                    CONFIGURATIONS,
                ),
                linked = linked,
            ),
        )
    }

    private fun optionalAppearanceMode(value: Any?): Int? {
        val integer = optionalInt(value) ?: return null
        return integer.takeIf { it in VALID_APPEARANCE_MODES }
    }

    private fun exactInt(value: Any?, field: String): Int =
        optionalInt(value) ?: throw SemanticDecodeException(
            if (value == null) {
                CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
            } else {
                CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
            },
            field,
        )

    private fun exactLong(value: Any?, field: String): Long = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        null -> throw SemanticDecodeException(
            CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD,
            field,
        )
        else -> throw SemanticDecodeException(
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            field,
        )
    }

    /** Retains a Foundation UInt64 as its exact two's-complement bits. */
    private fun exactUInt64Bits(value: Any?, field: String): Long = when (value) {
        is Byte -> value.toLong().takeIf { it >= 0L }
        is Short -> value.toLong().takeIf { it >= 0L }
        is Int -> value.toLong().takeIf { it >= 0L }
        // An eight-byte bplist integer is decoded as raw Long bits, so negative values are valid.
        is Long -> value
        is BigInteger -> value.takeIf { it.signum() >= 0 && it.bitLength() <= 64 }?.toLong()
        else -> null
    } ?: throw SemanticDecodeException(
        if (value == null) {
            CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
        } else {
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
        },
        field,
    )

    private fun optionalInt(value: Any?): Int? = when (value) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }

    private fun rejected(
        type: Int,
        failure: CarPlayUiSyncSemanticDecodeFailure,
        field: String? = null,
    ) = CarPlayUiSyncSemanticDecodeResult.Rejected(type, failure, field)

    private class SemanticBudget(
        private val limits: CarPlayUiSyncSemanticLimits,
    ) {
        private var mapEntries = 0
        private var aggregateStringChars = 0
        private var opaqueNodes = 0
        private var opaqueDataBytes = 0
        private val activeOpaqueContainers = IdentityHashMap<Any, Unit>()

        /**
         * Takes one bounded snapshot of an untrusted dictionary. No later semantic lookup reaches
         * back into the caller's Map, closing both unbounded-iterator and lookup TOCTOU paths.
         */
        fun dictionary(value: Any?): Map<String, Any?>? {
            val source = value as? Map<*, *> ?: return null
            val detached = LinkedHashMap<String, Any?>()
            snapshotEntries(source).forEach { entry ->
                val stringKey = entry.key as? String ?: throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
                )
                consumeString(stringKey)
                if (detached.containsKey(stringKey)) {
                    throw SemanticDecodeException(
                        CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
                    )
                }
                detached[stringKey] = entry.value
            }
            return detached
        }

        fun requireExactKeys(values: Map<String, Any?>, expected: Set<String>) {
            if (values.keys != expected) {
                throw SemanticDecodeException(CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS)
            }
        }

        fun exactString(value: Any?, field: String): String {
            val string = value as? String ?: throw SemanticDecodeException(
                if (value == null) {
                    CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
                } else {
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
                },
                field,
            )
            consumeString(string, field)
            return string
        }

        fun optionalString(value: Any?, field: String): String? {
            val string = value as? String ?: return null
            consumeString(string, field)
            return string
        }

        /**
         * Returns null for a missing or wrongly typed `[String:String]` dictionary. A dictionary
         * that exceeds a resource ceiling is rejected even if another entry makes its type wrong.
         */
        fun optionalStringMap(value: Any?, field: String): Map<String, String>? {
            val values = value as? Map<*, *> ?: return null
            var correctlyTyped = true
            val detached = LinkedHashMap<String, String>()
            snapshotEntries(values).forEach { entry ->
                val rawKey = entry.key
                val rawValue = entry.value
                if (rawKey is String) consumeString(rawKey, field)
                if (rawValue is String) consumeString(rawValue, field)
                if (rawKey is String && rawValue is String) {
                    if (detached.containsKey(rawKey)) {
                        throw SemanticDecodeException(
                            CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
                            field,
                        )
                    }
                    detached[rawKey] = rawValue
                } else {
                    correctlyTyped = false
                }
            }
            return if (correctlyTyped) immutableStringMap(detached) else null
        }

        fun exactStringMap(value: Any?, field: String): Map<String, String> =
            optionalStringMap(value, field) ?: throw SemanticDecodeException(
                if (value == null) {
                    CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
                } else {
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
                },
                field,
            )

        fun exactThemeConfigurationMap(
            value: Any?,
            field: String,
        ): Map<String, CarPlayUiSyncSemanticCommand.DriveModeThemeConfiguration> {
            val source = value as? Map<*, *> ?: throw SemanticDecodeException(
                if (value == null) {
                    CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
                } else {
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
                },
                field,
            )
            val detached = LinkedHashMap<
                String,
                CarPlayUiSyncSemanticCommand.DriveModeThemeConfiguration,
                >()
            snapshotEntries(source).forEach { entry ->
                val identifier = entry.key as? String ?: throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
                    field,
                )
                consumeString(identifier, field)
                if (detached.containsKey(identifier)) {
                    throw SemanticDecodeException(
                        CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
                        field,
                    )
                }
                val configuration = dictionary(entry.value) ?: throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
                    field,
                )
                val requiredKeys = setOf(COLOR_PALETTE_ID)
                val allowedKeys = requiredKeys + setOf(WALLPAPER_ID, LAYOUT_ID_CAMEL)
                if (!configuration.keys.containsAll(requiredKeys) ||
                    !allowedKeys.containsAll(configuration.keys)
                ) {
                    throw SemanticDecodeException(
                        CarPlayUiSyncSemanticDecodeFailure.INVALID_KEYS,
                        field,
                    )
                }
                detached[identifier] =
                    CarPlayUiSyncSemanticCommand.DriveModeThemeConfiguration(
                        colorPaletteId = exactString(
                            configuration[COLOR_PALETTE_ID],
                            COLOR_PALETTE_ID,
                        ),
                        wallpaperId = if (configuration.containsKey(WALLPAPER_ID)) {
                            exactString(configuration[WALLPAPER_ID], WALLPAPER_ID)
                        } else {
                            null
                        },
                        layoutId = if (configuration.containsKey(LAYOUT_ID_CAMEL)) {
                            exactString(configuration[LAYOUT_ID_CAMEL], LAYOUT_ID_CAMEL)
                        } else {
                            null
                        },
                    )
            }
            return Collections.unmodifiableMap(detached)
        }

        fun exactOpaqueDictionaryList(
            value: Any?,
            field: String,
        ): List<CarPlayUiSyncSemanticOpaqueValue> {
            val source = value as? List<*> ?: throw SemanticDecodeException(
                if (value == null) {
                    CarPlayUiSyncSemanticDecodeFailure.MISSING_FIELD
                } else {
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE
                },
                field,
            )
            val frozen = withOpaqueContainer(source, field) {
                val iterator = guardedMapRead { source.iterator() }
                val result = ArrayList<CarPlayUiSyncSemanticOpaqueValue>()
                while (guardedMapRead { iterator.hasNext() }) {
                    consumeEntries(1)
                    val item = guardedMapRead { iterator.next() }
                    if (item !is Map<*, *>) {
                        throw SemanticDecodeException(
                            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
                            field,
                        )
                    }
                    result += CarPlayUiSyncSemanticOpaqueValue(
                        freezeOpaque(item, depth = 1, field = field),
                    )
                }
                result
            }
            return Collections.unmodifiableList(frozen)
        }

        fun opaqueDictionary(
            values: Map<String, Any?>,
            field: String,
        ): CarPlayUiSyncSemanticOpaqueValue = CarPlayUiSyncSemanticOpaqueValue(
            freezeOpaque(values, depth = 1, field = field),
        )

        private fun freezeOpaque(value: Any?, depth: Int, field: String): Any? {
            consumeOpaqueNode(depth, field)
            return when (value) {
                null,
                is Boolean,
                is Byte,
                is Short,
                is Int,
                is Long,
                -> value

                is BigInteger -> value.takeIf {
                    it >= SIGNED_INT64_MINIMUM && it <= UNSIGNED_INT64_MAXIMUM
                } ?: invalidOpaque(field)

                is Float -> value.takeIf(Float::isFinite) ?: invalidOpaque(field)
                is Double -> value.takeIf(Double::isFinite) ?: invalidOpaque(field)
                is String -> value.also { consumeString(it, field) }
                is ByteArray -> {
                    opaqueDataBytes = try {
                        Math.addExact(opaqueDataBytes, value.size)
                    } catch (_: ArithmeticException) {
                        throw SemanticDecodeException(
                            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                            field,
                        )
                    }
                    if (opaqueDataBytes > limits.maximumOpaqueDataBytes) {
                        throw SemanticDecodeException(
                            CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                            field,
                        )
                    }
                    value.copyOf()
                }
                is Map<*, *> -> freezeOpaqueMap(value, depth, field)
                is List<*> -> snapshotList(value, depth, field)
                else -> invalidOpaque(field)
            }
        }

        private fun freezeOpaqueMap(
            source: Map<*, *>,
            depth: Int,
            field: String,
        ): Map<String, Any?> = withOpaqueContainer(source, field) {
            val result = LinkedHashMap<String, Any?>()
            snapshotEntries(source).forEach { entry ->
                val key = entry.key as? String ?: invalidOpaque(field)
                consumeString(key, field)
                if (result.containsKey(key)) {
                    throw SemanticDecodeException(
                        CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
                        field,
                    )
                }
                result[key] = freezeOpaque(entry.value, depth + 1, field)
            }
            Collections.unmodifiableMap(result)
        }

        private fun snapshotList(
            source: List<*>,
            depth: Int,
            field: String,
        ): List<Any?> = withOpaqueContainer(source, field) {
            val iterator = guardedMapRead { source.iterator() }
            val result = ArrayList<Any?>()
            while (guardedMapRead { iterator.hasNext() }) {
                consumeEntries(1)
                result += freezeOpaque(
                    guardedMapRead { iterator.next() },
                    depth + 1,
                    field,
                )
            }
            Collections.unmodifiableList(result)
        }

        private inline fun <T> withOpaqueContainer(
            source: Any,
            field: String,
            block: () -> T,
        ): T {
            if (activeOpaqueContainers.put(source, Unit) != null) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
                    field,
                )
            }
            return try {
                block()
            } finally {
                activeOpaqueContainers.remove(source)
            }
        }

        private fun consumeOpaqueNode(depth: Int, field: String) {
            if (depth > limits.maximumOpaqueNestingDepth) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                    field,
                )
            }
            opaqueNodes = try {
                Math.addExact(opaqueNodes, 1)
            } catch (_: ArithmeticException) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                    field,
                )
            }
            if (opaqueNodes > limits.maximumOpaqueNodes) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                    field,
                )
            }
        }

        private fun invalidOpaque(field: String): Nothing = throw SemanticDecodeException(
            CarPlayUiSyncSemanticDecodeFailure.INVALID_FIELD_TYPE,
            field,
        )

        private fun snapshotEntries(source: Map<*, *>): List<SnapshotEntry> {
            val iterator = guardedMapRead { source.entries.iterator() }
            val detached = ArrayList<SnapshotEntry>()
            while (guardedMapRead { iterator.hasNext() }) {
                // Check before next(): an endless iterator receives at most the configured number
                // of next() calls, regardless of what source.size reports.
                consumeEntries(1)
                val entry = guardedMapRead { iterator.next() }
                val key = guardedMapRead { entry.key }
                val value = guardedMapRead { entry.value }
                detached += SnapshotEntry(key, value)
            }
            return detached
        }

        private inline fun <T> guardedMapRead(read: () -> T): T = try {
            read()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            throw SemanticDecodeException(
                CarPlayUiSyncSemanticDecodeFailure.INVALID_DICTIONARY,
            )
        }

        private fun consumeEntries(count: Int) {
            mapEntries = try {
                Math.addExact(mapEntries, count)
            } catch (_: ArithmeticException) {
                throw SemanticDecodeException(CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED)
            }
            if (mapEntries > limits.maximumMapEntries) {
                throw SemanticDecodeException(CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED)
            }
        }

        private fun consumeString(value: String, field: String? = null) {
            if (value.length > limits.maximumStringChars) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                    field,
                )
            }
            aggregateStringChars = try {
                Math.addExact(aggregateStringChars, value.length)
            } catch (_: ArithmeticException) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                    field,
                )
            }
            if (aggregateStringChars > limits.maximumAggregateStringChars) {
                throw SemanticDecodeException(
                    CarPlayUiSyncSemanticDecodeFailure.LIMIT_EXCEEDED,
                    field,
                )
            }
        }

        private data class SnapshotEntry(
            val key: Any?,
            val value: Any?,
        )
    }

    private class SemanticDecodeException(
        val failure: CarPlayUiSyncSemanticDecodeFailure,
        val field: String? = null,
    ) : RuntimeException()

    private companion object {
        val SUPPORTED_TYPES = setOf(
            CarPlayUiSyncCommandType.TRANSITION_START,
            CarPlayUiSyncCommandType.TRANSITION_DATA,
            CarPlayUiSyncCommandType.LAYOUT_CHANGE,
            CarPlayUiSyncCommandType.TRANSITION_END,
            CarPlayUiSyncCommandType.REQUEST_LAYOUT,
            CarPlayUiSyncCommandType.END_LAYOUT_CHANGE,
            CarPlayUiSyncCommandType.GIVE_FOCUS,
            CarPlayUiSyncCommandType.METADATA_TRANSFER,
            CarPlayUiSyncCommandType.PALETTE_CHANGE,
            CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE,
            CarPlayUiSyncCommandType.APPEARANCE_PREFERENCE_CHANGE,
            CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE,
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_START,
            CarPlayUiSyncCommandType.APPEARANCE_CHANGE_END,
            CarPlayUiSyncCommandType.DRIVE_MODE_THEME_CONFIGURATION_OVERRIDE_CHANGE,
        )
        val VALID_APPEARANCE_MODES = -1..1
        val VALID_APPEARANCE_OVERRIDES = 1..2
        val VALID_TARGET_APPEARANCES = 0..1
        val SIGNED_INT64_MINIMUM: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
        val UNSIGNED_INT64_MAXIMUM: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

        const val CURRENT_NTP_TIME = "current_ntp_time"
        const val PRESENTATION_HOST_TIME = "presentation_host_time"
        const val PRESENTATION_NTP_TIME = "presentation_ntp_time"
        const val PRESENTATION_NTP_TIME_ORIGINAL = "presentation_ntp_time_original"
        const val REGIONS = "regions"
        const val TARGET_COMPONENTS = "targetComponents"
        const val LAYOUT_ID = "layoutID"
        const val PALETTE_ID = "paletteID"
        const val FADE_IN = "fadeInNewLayout"
        const val FADE_OUT = "fadeOutOldLayout"
        const val ACTIVE_COMPONENTS = "activeComponents"
        const val APPEARANCE_MODE = "appearanceMode"
        const val DRIVE_MODE = "driveMode"
        const val UI_CONFIGURATION = "uiConfiguration"
        const val TOKEN_ID = "tokenID"
        const val LAYOUT_IDENTIFIER = "layoutIdentifier"
        const val PALETTE_IDENTIFIER = "paletteIdentifier"
        const val IS_TRANSITIONING = "isTransitioning"
        const val PREFERENCE = "preference"
        const val OVERRIDE = "override"
        const val TARGET_APPEARANCE = "targetAppearance"
        const val CONFIGURATIONS = "configurations"
        const val LINKED = "linked"
        const val COLOR_PALETTE_ID = "colorPaletteId"
        const val WALLPAPER_ID = "wallpaperId"
        const val LAYOUT_ID_CAMEL = "layoutId"
    }
}

/** Immutable semantic projection for exactly one opaque, case-sensitive UI Sync display ID. */
class CarPlayUiSyncSemanticState internal constructor(
    val displayId: UltraUiSyncDisplayId,
    val revision: Long,
    val droppedEventCount: Long,
    val closed: Boolean,
    /** Last transition-start metadata packet; not proof that the transition completed. */
    val transitionStart: CarPlayUiSyncSemanticCommand.TransitionStart?,
    /** Last type-2 timing packet; clock values remain uninterpreted raw bits. */
    val lastTransitionData: CarPlayUiSyncSemanticCommand.TransitionData?,
    /** Last type-3 layout intent in this live display epoch; never a committed active layout. */
    val desiredLayoutChange: CarPlayUiSyncSemanticCommand.LayoutChange?,
    val lastLayoutChangeCommandRevision: Long?,
    /** Last type-4 packet received; not a renderer-completion receipt. */
    val lastTransitionEndCommand: CarPlayUiSyncSemanticCommand.TransitionEnd?,
    /** Last type-6 variant, whether timed or request echo; not a layout-completion claim. */
    val lastEndLayoutChangeCommand: CarPlayUiSyncSemanticCommand.EndLayoutChange?,
    val lastEndLayoutChangeCommandRevision: Long?,
    val focusTokenID: Long?,
    val lastMetadataTransfer: CarPlayUiSyncSemanticCommand.MetadataTransfer?,
    val palette: CarPlayUiSyncSemanticCommand.PaletteChange?,
    val lastAppearancePreferenceChange:
        CarPlayUiSyncSemanticCommand.AppearancePreferenceChange?,
    val lastAppearanceChangeStartCommand:
        CarPlayUiSyncSemanticCommand.AppearanceChangeStart?,
    /** Last type-22 command received; not proof that a visual transition completed. */
    val lastAppearanceChangeEndCommand:
        CarPlayUiSyncSemanticCommand.AppearanceChangeEnd?,
    val lastDriveModeThemeConfigurationOverrideChange:
        CarPlayUiSyncSemanticCommand.DriveModeThemeConfigurationOverrideChange?,
    /**
     * True when [desiredLayoutChange] was installed by a [CarPlayUiSyncRequestEchoLayoutAuthority]
     * rather than a received type 3. Diagnostic only; both forms carry identical authority.
     */
    val desiredLayoutChangeFromRequestEcho: Boolean = false,
)

/**
 * Accessory-side layout authority for one received type-6 request echo in an assetless session.
 *
 * iOS 27.0.1 DashBoard (IDA-verified): without a phone theme asset, the
 * `DBDefaultUISyncSessionDelegate` handles an inbound requestLayout by echoing the whole request
 * `data` back as type 6 (`sendLayoutChangeEnd`, no accept/reject field), then marks the asset
 * display foregrounded and sends type 19. Type 3 is sent only by `DBTransitionMetadataHandler`,
 * which exists only for themed sessions, so an assetless session never sends one. A themed
 * session sends nothing in reply to requestLayout. The Profile/session has no reliable local
 * "assetless" bit, so the restriction is the exact echo of this accessory's own signed request.
 *
 * [authorize] is fail-closed. It requires: the receiving channel's attestation on the retained
 * event ([UltraPresentationEventSnapshot.echoesAcknowledgedRequestLayout]: the latest
 * RequestLayout of the current sequence space, psn acknowledged by an earlier phone packet, no
 * Reset since, data equal); a type-6 decode to [CarPlayUiSyncSemanticCommand.EndLayoutChange
 * .RequestEcho] (the timed form never counts); and an echo equal in every key and value to the
 * caller's exact [CarPlayUiSyncAccessoryCommand.RequestLayout] wire dictionary. The resulting
 * [layoutChange] takes `layoutID`/`activeComponents` from that request and `paletteID` from the
 * caller's same signed intent; nothing beyond equality is read from the echo. The caller owns the
 * epoch check (no Reset or semantic boundary since that request was issued).
 *
 * The authority is the same as a received matching type 3, never more: it is not a presentation,
 * foreground, or renderer-completion receipt.
 */
class CarPlayUiSyncRequestEchoLayoutAuthority private constructor(
    /** The exact retained event revision this authority may be applied to, once. */
    val eventRevision: Long,
    val echo: CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho,
    val layoutChange: CarPlayUiSyncSemanticCommand.LayoutChange,
) {
    override fun toString(): String =
        "CarPlayUiSyncRequestEchoLayoutAuthority(eventRevision=$eventRevision)"

    companion object {
        fun authorize(
            event: UltraPresentationEventSnapshot,
            request: CarPlayUiSyncAccessoryCommand.RequestLayout,
            signedPaletteID: String,
            limits: CarPlayUiSyncSemanticLimits = CarPlayUiSyncSemanticLimits(),
        ): CarPlayUiSyncRequestEchoLayoutAuthority? {
            if (!event.echoesAcknowledgedRequestLayout || event.revision <= 0L ||
                event.knownCommand != CarPlayUiSyncCommandType.END_LAYOUT_CHANGE
            ) return null
            val layoutID = request.layoutID ?: return null
            if (layoutID.length > limits.maximumStringChars ||
                signedPaletteID.length > limits.maximumStringChars ||
                request.activeComponents.size > limits.maximumMapEntries
            ) return null
            val echo = ((CarPlayUiSyncSemanticDecoder(limits).decode(event) as?
                CarPlayUiSyncSemanticDecodeResult.Decoded)?.command as?
                CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho) ?: return null
            if (!carPlayUiSyncWireValueEquals(echo.requestData.value, request.wireData(),
                    limits.maximumOpaqueNestingDepth)
            ) return null
            return CarPlayUiSyncRequestEchoLayoutAuthority(
                event.revision, echo,
                CarPlayUiSyncSemanticCommand.LayoutChange(
                    layoutID, request.activeComponents, signedPaletteID,
                ),
            )
        }
    }
}

enum class CarPlayUiSyncSemanticReduceFailure {
    DISPLAY_MISMATCH,
    TERMINAL_STATE,
    INVALID_SNAPSHOT,
    HISTORY_GAP,
    UNKNOWN_COMMAND,
    WRONG_DIRECTION,
    UNSUPPORTED_COMMAND,
    MALFORMED_PAYLOAD,
    /** A supplied request-echo authority did not name exactly one newly applied attested echo. */
    REQUEST_ECHO_AUTHORITY_MISMATCH,
}

sealed interface CarPlayUiSyncSemanticReduceResult {
    /** State was advanced only after every newly observed event decoded successfully. */
    data class Applied(
        val state: CarPlayUiSyncSemanticState,
        val appliedEventCount: Int,
    ) : CarPlayUiSyncSemanticReduceResult

    /** Duplicate or older delivery; no event was decoded or applied again. */
    data class Unchanged(
        val state: CarPlayUiSyncSemanticState,
    ) : CarPlayUiSyncSemanticReduceResult

    /** The old [state] remains the reducer's exact published state. */
    data class Rejected(
        val state: CarPlayUiSyncSemanticState,
        val failure: CarPlayUiSyncSemanticReduceFailure,
        val eventRevision: Long? = null,
        val commandType: Int? = null,
    ) : CarPlayUiSyncSemanticReduceResult
}

/**
 * Atomic, per-display replay reducer for [UltraPresentationSnapshot].
 *
 * New revisions must be reconstructible without a history gap. A coalesced delivery is accepted
 * when its retained event history still contains every revision not already applied. Unknown,
 * unsupported, or malformed commands reject the complete candidate update. Closing a source is a
 * terminal revision and can never be reversed. This reducer is deliberately not installed by any
 * feature advertisement or renderer composition root.
 */
class CarPlayUiSyncSemanticReducer(
    displayId: UltraUiSyncDisplayId,
    limits: CarPlayUiSyncSemanticLimits = CarPlayUiSyncSemanticLimits(),
) {
    constructor(
        displayId: String,
        limits: CarPlayUiSyncSemanticLimits = CarPlayUiSyncSemanticLimits(),
    ) : this(UltraUiSyncDisplayId(displayId), limits)

    private val lock = Any()
    private val decoder = CarPlayUiSyncSemanticDecoder(limits)
    private var current = CarPlayUiSyncSemanticState(
        displayId = displayId,
        revision = 0L,
        droppedEventCount = 0L,
        closed = false,
        transitionStart = null,
        lastTransitionData = null,
        desiredLayoutChange = null,
        lastLayoutChangeCommandRevision = null,
        lastTransitionEndCommand = null,
        lastEndLayoutChangeCommand = null,
        lastEndLayoutChangeCommandRevision = null,
        focusTokenID = null,
        lastMetadataTransfer = null,
        palette = null,
        lastAppearancePreferenceChange = null,
        lastAppearanceChangeStartCommand = null,
        lastAppearanceChangeEndCommand = null,
        lastDriveModeThemeConfigurationOverrideChange = null,
    )

    fun state(): CarPlayUiSyncSemanticState = synchronized(lock) { current }

    fun reduce(snapshot: UltraPresentationSnapshot): CarPlayUiSyncSemanticReduceResult =
        reduce(snapshot, emptyList())

    /**
     * As [reduce], additionally applying each [requestEchoAuthorities] entry to its exact newly
     * applied, channel-attested type-6 request echo as if that event were the matching type 3.
     * An authority for any other revision, command, or echo rejects the whole candidate update.
     */
    fun reduce(
        snapshot: UltraPresentationSnapshot,
        requestEchoAuthorities: Collection<CarPlayUiSyncRequestEchoLayoutAuthority>,
    ): CarPlayUiSyncSemanticReduceResult =
        synchronized(lock) {
            val old = current
            if (snapshot.displayId != old.displayId) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.DISPLAY_MISMATCH,
                )
            }
            val echoAuthorities = LinkedHashMap<Long, CarPlayUiSyncRequestEchoLayoutAuthority>()
            requestEchoAuthorities.forEach { authority ->
                if (authority.eventRevision <= old.revision ||
                    authority.eventRevision > snapshot.revision ||
                    echoAuthorities.put(authority.eventRevision, authority) != null
                ) {
                    return@synchronized rejected(
                        old,
                        CarPlayUiSyncSemanticReduceFailure.REQUEST_ECHO_AUTHORITY_MISMATCH,
                        eventRevision = authority.eventRevision,
                    )
                }
            }
            if (snapshot.revision < 0L || snapshot.droppedEventCount < 0L) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT,
                )
            }
            if (snapshot.revision < old.revision) {
                return@synchronized CarPlayUiSyncSemanticReduceResult.Unchanged(old)
            }
            // A hub closed before this display was ever created has no event revision to
            // increment. Its exact empty revision-zero snapshot is still a terminal state.
            if (snapshot.revision == 0L && old.revision == 0L && !old.closed &&
                snapshot.closed && snapshot.droppedEventCount == 0L && snapshot.events.isEmpty()
            ) {
                val terminal = CarPlayUiSyncSemanticState(
                    displayId = old.displayId,
                    revision = 0L,
                    droppedEventCount = 0L,
                    closed = true,
                    transitionStart = null,
                    lastTransitionData = null,
                    desiredLayoutChange = null,
                    lastLayoutChangeCommandRevision = null,
                    lastTransitionEndCommand = null,
                    lastEndLayoutChangeCommand = null,
                    lastEndLayoutChangeCommandRevision = null,
                    focusTokenID = null,
                    lastMetadataTransfer = null,
                    palette = null,
                    lastAppearancePreferenceChange = null,
                    lastAppearanceChangeStartCommand = null,
                    lastAppearanceChangeEndCommand = null,
                    lastDriveModeThemeConfigurationOverrideChange = null,
                    desiredLayoutChangeFromRequestEcho = false,
                )
                current = terminal
                return@synchronized CarPlayUiSyncSemanticReduceResult.Applied(terminal, 0)
            }
            if (snapshot.revision == old.revision) {
                return@synchronized if (
                    snapshot.closed == old.closed &&
                    snapshot.droppedEventCount == old.droppedEventCount
                ) {
                    CarPlayUiSyncSemanticReduceResult.Unchanged(old)
                } else {
                    rejected(old, CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT)
                }
            }
            if (old.closed) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.TERMINAL_STATE,
                )
            }
            if (snapshot.droppedEventCount < old.droppedEventCount) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT,
                )
            }
            if (snapshot.closed && snapshot.revision == 0L) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT,
                )
            }

            val lastEventRevision =
                if (snapshot.closed) snapshot.revision - 1L else snapshot.revision
            val retainedEventCount = snapshot.events.size.toLong()
            if (
                snapshot.droppedEventCount > lastEventRevision ||
                snapshot.droppedEventCount > Long.MAX_VALUE - retainedEventCount ||
                snapshot.droppedEventCount + retainedEventCount != lastEventRevision
            ) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT,
                )
            }
            var expectedRetainedRevision = snapshot.droppedEventCount
            snapshot.events.forEach { event ->
                if (
                    expectedRetainedRevision == Long.MAX_VALUE ||
                    event.revision != expectedRetainedRevision + 1L
                ) {
                    return@synchronized rejected(
                        old,
                        CarPlayUiSyncSemanticReduceFailure.INVALID_SNAPSHOT,
                        eventRevision = event.revision,
                        commandType = event.type,
                    )
                }
                expectedRetainedRevision = event.revision
            }

            var lastAppliedRevision = old.revision
            var transitionStart = old.transitionStart
            var lastTransitionData = old.lastTransitionData
            var desiredLayoutChange = old.desiredLayoutChange
            var lastLayoutChangeCommandRevision = old.lastLayoutChangeCommandRevision
            var lastTransitionEndCommand = old.lastTransitionEndCommand
            var lastEndLayoutChangeCommand = old.lastEndLayoutChangeCommand
            var lastEndLayoutChangeCommandRevision = old.lastEndLayoutChangeCommandRevision
            var focusTokenID = old.focusTokenID
            var lastMetadataTransfer = old.lastMetadataTransfer
            var palette = old.palette
            var lastAppearancePreferenceChange = old.lastAppearancePreferenceChange
            var lastAppearanceChangeStartCommand = old.lastAppearanceChangeStartCommand
            var lastAppearanceChangeEndCommand = old.lastAppearanceChangeEndCommand
            var lastDriveModeThemeConfigurationOverrideChange =
                old.lastDriveModeThemeConfigurationOverrideChange
            var desiredLayoutChangeFromRequestEcho = old.desiredLayoutChangeFromRequestEcho
            var appliedEvents = 0

            snapshot.events.forEach { event ->
                if (event.revision <= old.revision) return@forEach
                if (
                    lastAppliedRevision == Long.MAX_VALUE ||
                    event.revision != lastAppliedRevision + 1L
                ) {
                    return@synchronized rejected(
                        old,
                        CarPlayUiSyncSemanticReduceFailure.HISTORY_GAP,
                        eventRevision = lastAppliedRevision.takeIf { it != Long.MAX_VALUE }
                            ?.plus(1L),
                    )
                }
                val knownType = CarPlayUiSyncCommandType.fromWireValue(event.type)
                if (knownType?.confirmedDirection ==
                    CarPlayUiSyncCommandDirection.ACCESSORY_TO_IPHONE
                ) {
                    return@synchronized rejected(
                        old,
                        CarPlayUiSyncSemanticReduceFailure.WRONG_DIRECTION,
                        eventRevision = event.revision,
                        commandType = event.type,
                    )
                }
                when (val decoded = decoder.decode(event)) {
                    is CarPlayUiSyncSemanticDecodeResult.Decoded -> when (
                        val command = decoded.command
                    ) {
                        is CarPlayUiSyncSemanticCommand.TransitionStart ->
                            transitionStart = command
                        is CarPlayUiSyncSemanticCommand.TransitionData ->
                            lastTransitionData = command
                        is CarPlayUiSyncSemanticCommand.LayoutChange -> {
                            desiredLayoutChange = command
                            lastLayoutChangeCommandRevision = event.revision
                            desiredLayoutChangeFromRequestEcho = false
                        }
                        is CarPlayUiSyncSemanticCommand.TransitionEnd ->
                            lastTransitionEndCommand = command
                        is CarPlayUiSyncSemanticCommand.RequestLayout ->
                            return@synchronized rejected(
                                old,
                                CarPlayUiSyncSemanticReduceFailure.WRONG_DIRECTION,
                                eventRevision = event.revision,
                                commandType = event.type,
                            )
                        is CarPlayUiSyncSemanticCommand.EndLayoutChange -> {
                            lastEndLayoutChangeCommand = command
                            lastEndLayoutChangeCommandRevision = event.revision
                            echoAuthorities.remove(event.revision)?.let { authority ->
                                if (command !is
                                        CarPlayUiSyncSemanticCommand.EndLayoutChange.RequestEcho ||
                                    command != authority.echo ||
                                    !event.echoesAcknowledgedRequestLayout
                                ) {
                                    return@synchronized rejected(
                                        old,
                                        CarPlayUiSyncSemanticReduceFailure
                                            .REQUEST_ECHO_AUTHORITY_MISMATCH,
                                        eventRevision = event.revision,
                                        commandType = event.type,
                                    )
                                }
                                desiredLayoutChange = authority.layoutChange
                                lastLayoutChangeCommandRevision = event.revision
                                desiredLayoutChangeFromRequestEcho = true
                            }
                        }
                        is CarPlayUiSyncSemanticCommand.GiveFocus ->
                            focusTokenID = command.tokenID
                        is CarPlayUiSyncSemanticCommand.MetadataTransfer ->
                            lastMetadataTransfer = command
                        is CarPlayUiSyncSemanticCommand.PaletteChange ->
                            palette = command
                        is CarPlayUiSyncSemanticCommand.AppearancePreferenceChange ->
                            lastAppearancePreferenceChange = command
                        is CarPlayUiSyncSemanticCommand.AppearanceChangeStart ->
                            lastAppearanceChangeStartCommand = command
                        is CarPlayUiSyncSemanticCommand.AppearanceChangeEnd ->
                            lastAppearanceChangeEndCommand = command
                        is CarPlayUiSyncSemanticCommand.DriveModeThemeConfigurationOverrideChange ->
                            lastDriveModeThemeConfigurationOverrideChange = command
                        is CarPlayUiSyncSemanticCommand.TargetAppearanceChange,
                        is CarPlayUiSyncSemanticCommand.DriveModeChange,
                        -> return@synchronized rejected(
                            old,
                            CarPlayUiSyncSemanticReduceFailure.WRONG_DIRECTION,
                            eventRevision = event.revision,
                            commandType = event.type,
                        )
                    }
                    is CarPlayUiSyncSemanticDecodeResult.Rejected -> {
                        return@synchronized rejected(
                            old,
                            decoded.failure.toReduceFailure(),
                            eventRevision = event.revision,
                            commandType = event.type,
                        )
                    }
                }
                appliedEvents++
                lastAppliedRevision = event.revision
            }
            if (lastAppliedRevision != lastEventRevision) {
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.HISTORY_GAP,
                    eventRevision = lastAppliedRevision.takeIf { it != Long.MAX_VALUE }
                        ?.plus(1L),
                )
            }
            echoAuthorities.keys.firstOrNull()?.let { unused ->
                return@synchronized rejected(
                    old,
                    CarPlayUiSyncSemanticReduceFailure.REQUEST_ECHO_AUTHORITY_MISMATCH,
                    eventRevision = unused,
                )
            }

            val replacement = CarPlayUiSyncSemanticState(
                displayId = old.displayId,
                revision = snapshot.revision,
                droppedEventCount = snapshot.droppedEventCount,
                closed = snapshot.closed,
                transitionStart = transitionStart,
                lastTransitionData = lastTransitionData,
                desiredLayoutChange = if (snapshot.closed) null else desiredLayoutChange,
                lastLayoutChangeCommandRevision = lastLayoutChangeCommandRevision,
                lastTransitionEndCommand = lastTransitionEndCommand,
                lastEndLayoutChangeCommand = lastEndLayoutChangeCommand,
                lastEndLayoutChangeCommandRevision = lastEndLayoutChangeCommandRevision,
                focusTokenID = focusTokenID,
                lastMetadataTransfer = lastMetadataTransfer,
                palette = palette,
                lastAppearancePreferenceChange = lastAppearancePreferenceChange,
                lastAppearanceChangeStartCommand = lastAppearanceChangeStartCommand,
                lastAppearanceChangeEndCommand = lastAppearanceChangeEndCommand,
                lastDriveModeThemeConfigurationOverrideChange =
                    lastDriveModeThemeConfigurationOverrideChange,
                desiredLayoutChangeFromRequestEcho =
                    !snapshot.closed && desiredLayoutChangeFromRequestEcho,
            )
            current = replacement
            CarPlayUiSyncSemanticReduceResult.Applied(replacement, appliedEvents)
        }

    private fun rejected(
        old: CarPlayUiSyncSemanticState,
        failure: CarPlayUiSyncSemanticReduceFailure,
        eventRevision: Long? = null,
        commandType: Int? = null,
    ) = CarPlayUiSyncSemanticReduceResult.Rejected(
        state = old,
        failure = failure,
        eventRevision = eventRevision,
        commandType = commandType,
    )

    private fun CarPlayUiSyncSemanticDecodeFailure.toReduceFailure():
        CarPlayUiSyncSemanticReduceFailure = when (this) {
        CarPlayUiSyncSemanticDecodeFailure.UNKNOWN_COMMAND ->
            CarPlayUiSyncSemanticReduceFailure.UNKNOWN_COMMAND
        CarPlayUiSyncSemanticDecodeFailure.UNSUPPORTED_COMMAND ->
            CarPlayUiSyncSemanticReduceFailure.UNSUPPORTED_COMMAND
        else -> CarPlayUiSyncSemanticReduceFailure.MALFORMED_PAYLOAD
    }
}

private fun immutableStringMap(source: Map<String, String>): Map<String, String> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun copySemanticFrozenValue(value: Any?): Any? = when (value) {
    is ByteArray -> value.copyOf()
    is List<*> -> Collections.unmodifiableList(value.map(::copySemanticFrozenValue))
    is Map<*, *> -> Collections.unmodifiableMap(
        value.entries.associateTo(LinkedHashMap()) { (key, item) ->
            check(key is String)
            key to copySemanticFrozenValue(item)
        },
    )
    else -> value
}

private fun semanticFrozenEquals(left: Any?, right: Any?): Boolean = when {
    left is ByteArray && right is ByteArray -> left.contentEquals(right)
    left is List<*> && right is List<*> ->
        left.size == right.size && left.indices.all { index ->
            semanticFrozenEquals(left[index], right[index])
        }
    left is Map<*, *> && right is Map<*, *> ->
        left.size == right.size && left.entries.all { (key, value) ->
            right.containsKey(key) && semanticFrozenEquals(value, right[key])
        }
    else -> left == right
}

private fun semanticFrozenHashCode(value: Any?): Int = when (value) {
    is ByteArray -> value.contentHashCode()
    is List<*> -> value.fold(1) { hash, item ->
        31 * hash + semanticFrozenHashCode(item)
    }
    is Map<*, *> -> value.entries.fold(0) { hash, (key, item) ->
        hash + ((key?.hashCode() ?: 0) xor semanticFrozenHashCode(item))
    }
    else -> value?.hashCode() ?: 0
}
