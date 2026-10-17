package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import com.shilapi.xcertplay.bench.BenchUltraProfileStageActivity

/** Variant-local explicit launch; no external app can open the picker directly. */
internal object DebugBenchStageLauncher {
    const val isAvailable = true
    fun launch(activity: Activity) {
        activity.startActivity(Intent(activity, BenchUltraProfileStageActivity::class.java))
    }
}
