package com.shilapi.xcertplay

import java.io.Closeable

/** Host settings transaction: only an actual Ultra switch change needs the old-output cover. */
internal fun persistHostSettingsWithUltraCover(
    persistedUltraEnabled: Boolean,
    desiredUltraEnabled: Boolean,
    beginMutation: () -> Closeable?,
    persist: () -> Unit,
): Boolean {
    if (persistedUltraEnabled == desiredUltraEnabled) {
        persist()
        return true
    }
    val ticket = beginMutation() ?: return false
    ticket.use { persist() }
    return true
}
