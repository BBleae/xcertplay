package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit-launch, debug-variant-only device harness for the synthetic Ultra transaction.
 *
 * This component is deliberately not a product activation entry point. It uses no Apple/OEM
 * profile, certificate, trust root, entitlement, or production composition-root provider.
 */
class SyntheticUltraProfileDebugActivity : Activity() {
    private lateinit var output: TextView
    private val destroyed = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DEBUG Synthetic CarPlay Ultra"

        val density = resources.displayMetrics.density
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextIsSelectable(true)
            setPadding((16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (24 * density).toInt())
            text = "DEBUG SYNTHETIC CARPLAY ULTRA\n" +
                "No Apple/OEM authorization material is used.\n\nRUNNING…\n"
        }
        setContentView(
            ScrollView(this).apply {
                addView(
                    output,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )

        Thread(
            {
                SyntheticUltraProfileDeviceHarness(
                    context = applicationContext,
                    report = ::report,
                ).run()
            },
            "debug-synthetic-ultra-profile",
        ).apply {
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        destroyed.set(true)
        super.onDestroy()
    }

    private fun report(line: String) {
        Log.i(LOG_TAG, line)
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get()) {
                output.append("$line\n")
                (output.parent as? ScrollView)?.post {
                    (output.parent as? ScrollView)?.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "SyntheticUltraProfile"
    }
}
