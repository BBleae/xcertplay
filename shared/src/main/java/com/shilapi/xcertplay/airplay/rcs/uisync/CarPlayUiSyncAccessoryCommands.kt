package com.shilapi.xcertplay.airplay.rcs.uisync

import java.util.Collections

/**
 * Typed commands this accessory is confirmed to send to the iPhone.
 *
 * These models do not enable UI Sync or bypass provisioning. They only prevent a caller from
 * accidentally sending an iPhone-only command or using a similarly named, incorrect payload key.
 */
sealed interface CarPlayUiSyncAccessoryCommand {
    val type: CarPlayUiSyncCommandType
    fun wireData(): Map<String, Any>

    class RequestLayout(
        val layoutID: String? = null,
        val fadeInNewLayout: Boolean = true,
        val fadeOutOldLayout: Boolean = true,
        activeComponents: Map<String, String> = emptyMap(),
        val appearanceMode: Int? = null,
        val driveMode: String? = null,
        uiConfiguration: Map<String, String>? = null,
    ) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.REQUEST_LAYOUT
        val activeComponents = freezeStringMap(activeComponents, "activeComponents")
        val uiConfiguration = uiConfiguration?.let {
            freezeStringMap(it, "uiConfiguration")
        }

        init {
            layoutID?.let { validateString(it, "layoutID") }
            require(appearanceMode == null || appearanceMode in -1..1) {
                "appearanceMode must be -1, 0, 1, or absent"
            }
            driveMode?.let { validateString(it, "driveMode") }
        }

        override fun wireData(): Map<String, Any> = linkedMapOf<String, Any>(
            "fadeInNewLayout" to fadeInNewLayout,
            "fadeOutOldLayout" to fadeOutOldLayout,
            "activeComponents" to activeComponents,
        ).apply {
            layoutID?.let { put("layoutID", it) }
            appearanceMode?.let { put("appearanceMode", it) }
            driveMode?.let { put("driveMode", it) }
            uiConfiguration?.let { put("uiConfiguration", it) }
        }.immutable()
    }

    class GiveFocus(val tokenID: Long) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.GIVE_FOCUS
        override fun wireData(): Map<String, Any> = mapOf("tokenID" to tokenID)
    }

    class RequestFocus(val tokenID: Long) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.REQUEST_FOCUS
        override fun wireData(): Map<String, Any> = mapOf("tokenID" to tokenID)
    }

    class AllowTransitions(val allow: Boolean) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.ALLOW_TRANSITIONS
        override fun wireData(): Map<String, Any> = mapOf("allow" to allow)
    }

    class TargetAppearanceChange(val appearanceMode: Int) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.TARGET_APPEARANCE_CHANGE

        init {
            require(appearanceMode in -1..1) { "appearanceMode must be -1, 0, or 1" }
        }

        override fun wireData(): Map<String, Any> = mapOf("appearanceMode" to appearanceMode)
    }

    class DriveModeChange(val driveMode: String) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.DRIVE_MODE_CHANGE

        init {
            validateString(driveMode, "driveMode")
        }

        override fun wireData(): Map<String, Any> = mapOf("driveMode" to driveMode)
    }

    class UiConfigurationChange(
        uiConfiguration: Map<String, String>,
    ) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.UI_CONFIGURATION_CHANGE
        val uiConfiguration = freezeStringMap(uiConfiguration, "uiConfiguration")

        override fun wireData(): Map<String, Any> =
            mapOf("uiConfiguration" to uiConfiguration)
    }

    class DriveModeDynamicAssignmentChange(
        assignments: Map<String, String>,
    ) : CarPlayUiSyncAccessoryCommand {
        override val type = CarPlayUiSyncCommandType.DRIVE_MODE_DYNAMIC_ASSIGNMENT_CHANGE
        val assignments = freezeStringMap(
            assignments,
            "driveModeToLayoutIdAssignments",
            requireNonEmptyEntries = true,
        )

        override fun wireData(): Map<String, Any> =
            mapOf("driveModeToLayoutIdAssignments" to assignments)
    }
}

fun CarPlayUiSyncAccessoryCommand.toEnvelope(): CarPlayUiSyncCommandEnvelope {
    check(confirmedAccessoryOutbound(type)) {
        "UI Sync command ${type.diagnosticName} is not accessory-to-iPhone"
    }
    return CarPlayUiSyncCommandEnvelope(type.wireValue, wireData())
}

private fun confirmedAccessoryOutbound(type: CarPlayUiSyncCommandType): Boolean =
    type.confirmedDirection == CarPlayUiSyncCommandDirection.ACCESSORY_TO_IPHONE ||
        type.confirmedDirection == CarPlayUiSyncCommandDirection.BIDIRECTIONAL

private fun freezeStringMap(
    source: Map<String, String>,
    label: String,
    requireNonEmptyEntries: Boolean = false,
): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val iterator = guardedOutboundRead(label) { source.entries.iterator() }
    while (guardedOutboundRead(label) { iterator.hasNext() }) {
        require(result.size < MAXIMUM_OUTBOUND_MAP_ENTRIES) {
            "$label exceeds $MAXIMUM_OUTBOUND_MAP_ENTRIES entries"
        }
        val entry = guardedOutboundRead(label) { iterator.next() }
        val key = guardedOutboundRead(label) { entry.key }
        val value = guardedOutboundRead(label) { entry.value }
        validateString(key, "$label key")
        validateString(value, "$label value")
        if (requireNonEmptyEntries) {
            require(key.isNotEmpty()) { "$label contains an empty key" }
            require(value.isNotEmpty()) { "$label contains an empty value" }
        }
        check(result.put(key, value) == null) { "$label contains a duplicate key" }
    }
    return Collections.unmodifiableMap(result)
}

private inline fun <T> guardedOutboundRead(label: String, block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
    throw IllegalArgumentException("$label changed or failed while being snapshotted", failure)
}

private fun validateString(value: String, label: String) {
    require(value.length <= MAXIMUM_OUTBOUND_STRING_CHARS) {
        "$label exceeds $MAXIMUM_OUTBOUND_STRING_CHARS characters"
    }
}

private fun <K, V> Map<K, V>.immutable(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))

private const val MAXIMUM_OUTBOUND_MAP_ENTRIES = 1_024
private const val MAXIMUM_OUTBOUND_STRING_CHARS = 8 * 1_024
