package com.shilapi.xcertplay

import android.app.Activity

/** Release contains no bench destination, intent, or provider reference. */
internal object DebugBenchStageLauncher {
    const val isAvailable = false
    fun launch(activity: Activity) = Unit
}
