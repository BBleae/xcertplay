package com.shilapi.xcertplay.bench

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.UltraProfileCompositionRoot

/**
 * Human-operated SAF import for a user-pinned debug Profile. No external Intent field,
 * path, or URI is consulted. This activity is absent from release builds.
 */
class BenchUltraProfileStageActivity : Activity() {
    private lateinit var store: BenchUltraProfileImportStore
    private lateinit var status: TextView
    private lateinit var vehicleId: EditText
    private var archive: Uri? = null
    private var pin: Uri? = null
    private var requestToExport: ByteArray? = null
    private var timingToExport: ByteArray? = null
    private var timingExportCaptureId: String? = null
    private var restoringConsentCheckbox = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DEBUG Profile bench"
        store = BenchUltraProfileImportStore(
            noBackupFilesDir.toPath().resolve(BenchUltraProfileImportStore.DIRECTORY),
        )
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "DEBUG BENCH ONLY — NOT APPLE/OEM AUTHORIZED.\n" +
                "A user-owned external P-256 key signs the inspected Profile digest, " +
                "vehicle ID and current MFi certificate identity. No private key is imported.\n" +
                "Synthetic CAF values, if selected by the signed Profile, are protocol fixtures, " +
                "NOT real vehicle data. UI Sync additionally requires its own opt-in, a signed " +
                "executable catalog and exact live physical Host outputs."
            textSize = 17f
        })
        vehicleId = EditText(this).apply {
            hint = "Bench vehicle ID (signed binding)"
            isSingleLine = true
        }
        column.addView(vehicleId)
        column.button("1. Choose Profile ZIP") { pick(REQUEST_ARCHIVE) }
        column.button("2. Choose P-256 public SPKI DER pin") { pick(REQUEST_PIN) }
        column.button("3. Stage ZIP + pin + vehicle ID") {
            val zip = archive
            val publicPin = pin
            val selectedVehicleId = vehicleId.text.toString()
            if (zip == null || publicPin == null) {
                show("Choose both ZIP and public pin first")
            } else if (selectedVehicleId.isBlank()) {
                show("Enter the bench vehicle ID before staging")
            } else {
                Thread {
                    val result = runCatching {
                        contentResolver.openInputStream(zip).use { zipInput ->
                            contentResolver.openInputStream(publicPin).use { pinInput ->
                                store.stageBase(
                                    requireNotNull(zipInput), requireNotNull(pinInput),
                                    selectedVehicleId,
                                )
                            }
                        }
                    }
                    runOnUiThread {
                        show(if (result.isSuccess) {
                            "Profile staged. Connect once to capture current MFi signing request; " +
                                "then export, sign externally, import the DER signature, and reconnect."
                        } else "Stage rejected: ${benchStageFailureLabel(result.exceptionOrNull())}")
                    }
                }.start()
            }
        }
        column.button("4. Export current MFi-bound signing request") {
            val request = runCatching { store.signingRequest() }.getOrNull()
            if (request == null) show("Connect once with bench opt-in first; no MFi identity captured yet")
            else {
                requestToExport = request
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "xcertplay-bench-signing-request.bin")
                }, REQUEST_EXPORT)
            }
        }
        column.button("5. Import external DER signature") { pick(REQUEST_SIGNATURE) }
        val optIn = CheckBox(this).apply {
            text = "Enable CAF-only debug bench on next controller start"
            isChecked = getSharedPreferences(
                UltraProfileCompositionRoot.BENCH_PREFERENCES, MODE_PRIVATE,
            ).getBoolean(UltraProfileCompositionRoot.BENCH_OPT_IN_KEY, false)
            setOnCheckedChangeListener { _, checked ->
                if (restoringConsentCheckbox) return@setOnCheckedChangeListener
                val mutation = UltraProfileCompositionRoot.beginConsentMutation()
                if (mutation == null) {
                    restoringConsentCheckbox = true
                    try { isChecked = !checked } finally { restoringConsentCheckbox = false }
                    show("Bench preference unchanged: old physical output cover is pending; retry")
                    return@setOnCheckedChangeListener
                }
                val saved = runCatching {
                    mutation.use {
                        getSharedPreferences(UltraProfileCompositionRoot.BENCH_PREFERENCES,
                            MODE_PRIVATE).edit()
                            .putBoolean(UltraProfileCompositionRoot.BENCH_OPT_IN_KEY, checked)
                            .apply()
                    }
                }
                if (saved.isFailure) {
                    restoringConsentCheckbox = true
                    try { isChecked = !checked } finally { restoringConsentCheckbox = false }
                    show("Bench preference unchanged: ${benchStageFailureLabel(saved.exceptionOrNull())}")
                    return@setOnCheckedChangeListener
                }
                show(if (checked) "Bench opt-in saved; restart controller to apply" else "Bench opt-in disabled")
            }
        }
        column.addView(optIn)
        val uiSyncOptIn = CheckBox(this).apply {
            text = "Enable signed physical UI Sync bench on next controller start " +
                "(requires the CAF bench opt-in above)"
            isChecked = getSharedPreferences(
                UltraProfileCompositionRoot.BENCH_PREFERENCES, MODE_PRIVATE,
            ).getBoolean(UltraProfileCompositionRoot.UI_SYNC_BENCH_OPT_IN_KEY, false)
            setOnCheckedChangeListener { _, checked ->
                if (restoringConsentCheckbox) return@setOnCheckedChangeListener
                val mutation = UltraProfileCompositionRoot.beginConsentMutation()
                if (mutation == null) {
                    restoringConsentCheckbox = true
                    try { isChecked = !checked } finally { restoringConsentCheckbox = false }
                    show("UI Sync preference unchanged: old physical output cover is pending; retry")
                    return@setOnCheckedChangeListener
                }
                val saved = runCatching {
                    mutation.use {
                        getSharedPreferences(UltraProfileCompositionRoot.BENCH_PREFERENCES,
                            MODE_PRIVATE).edit()
                            .putBoolean(UltraProfileCompositionRoot.UI_SYNC_BENCH_OPT_IN_KEY,
                                checked).apply()
                    }
                }
                if (saved.isFailure) {
                    restoringConsentCheckbox = true
                    try { isChecked = !checked } finally { restoringConsentCheckbox = false }
                    show("UI Sync preference unchanged: " +
                        benchStageFailureLabel(saved.exceptionOrNull()))
                    return@setOnCheckedChangeListener
                }
                show(if (checked) "UI Sync bench opt-in saved; restart controller to apply"
                    else "UI Sync bench opt-in disabled")
            }
        }
        column.addView(uiSyncOptIn)
        column.addView(TextView(this).apply {
            text = "Real v3 speed CAF uses only Android Automotive's standard global speed " +
                "property. A signed Profile does not grant that permission. This is a parked/bench " +
                "test request to Android, not Apple/OEM authorization or an OEM Profile copy. " +
                "Phones without Android Automotive cannot provide this source."
        })
        val confirmSpeedBench = CheckBox(this).apply {
            text = "I confirm this is a stationary vehicle or bench and want to ask Android " +
                "Automotive for CAR_SPEED access for the real-speed Debug test"
        }
        column.addView(confirmSpeedBench)
        column.button("6. Request Android Automotive CAR_SPEED permission") {
            if (!confirmSpeedBench.isChecked) {
                show("Confirm the parked/bench real-speed test before requesting permission")
            } else when (BenchCarSpeedPermissionGate.status(this)) {
                BenchCarSpeedPermissionGate.Status.REQUESTABLE -> {
                    confirmSpeedBench.isChecked = false
                    requestPermissions(arrayOf(BenchCarSpeedPermissionGate.PERMISSION),
                        REQUEST_CAR_SPEED_PERMISSION)
                }
                BenchCarSpeedPermissionGate.Status.GRANTED ->
                    show("CAR_SPEED is currently granted by Android Automotive")
                BenchCarSpeedPermissionGate.Status.NOT_AUTOMOTIVE ->
                    show("Real speed unavailable: this device is not Android Automotive")
                BenchCarSpeedPermissionGate.Status.CAR_API_UNAVAILABLE ->
                    show("Real speed unavailable: Android Car API is absent")
                BenchCarSpeedPermissionGate.Status.PERMISSION_UNAVAILABLE ->
                    show("Real speed unavailable: Android Automotive CAR_SPEED permission is absent")
            }
        }
        column.button("7. Show live sanitized timing capture") {
            val snapshot = BenchTimingCaptureCatalog.summary()
            show(if (snapshot == null) {
                "No live signed UI Sync timing capture. The recorder never establishes readiness."
            } else {
                "Timing ${snapshot.capture.state.name}: " +
                    "${snapshot.capture.retainedRecords} records, " +
                    "${snapshot.capture.droppedOverflow} ring drops, " +
                    "${snapshot.droppedObserverSnapshots} diagnostic snapshot drops, " +
                    "${snapshot.capture.rejectedRecords} rejected. " +
                    "Raw clock domains are not mapped."
            })
        }
        column.button("8. Export live sanitized timing NDJSON") {
            Thread {
                val captureId = BenchTimingCaptureCatalog.summary()?.capture?.captureId
                val bytes = BenchTimingCaptureCatalog.exportNdjson()
                    ?.toByteArray(Charsets.UTF_8)
                runOnUiThread {
                    if (captureId == null || bytes == null || bytes.isEmpty() ||
                        BenchTimingCaptureCatalog.summary()?.capture?.captureId != captureId
                    ) {
                        show("No active timing capture to export")
                    } else {
                        timingToExport = bytes
                        timingExportCaptureId = captureId
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/x-ndjson"
                            putExtra(Intent.EXTRA_TITLE,
                                "xcertplay-debug-timing-sanitized.ndjson")
                        }, REQUEST_TIMING_EXPORT)
                    }
                }
            }.start()
        }
        status = TextView(this).apply { text = "No import selected"; setTextIsSelectable(true) }
        column.addView(status)
        setContentView(ScrollView(this).apply {
            addView(column, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAR_SPEED_PERMISSION) {
            show(if (BenchCarSpeedPermissionGate.status(this) ==
                BenchCarSpeedPermissionGate.Status.GRANTED) {
                "CAR_SPEED granted for Android Automotive real-speed Debug tests; " +
                    "restart the controller to apply"
            } else "CAR_SPEED not granted; signed real-speed CAF remains unavailable")
        }
    }

    @Deprecated("SAF result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_TIMING_EXPORT) {
                timingToExport = null
                timingExportCaptureId = null
            }
            return
        }
        val uri = data?.data ?: run {
            if (requestCode == REQUEST_TIMING_EXPORT) {
                timingToExport = null
                timingExportCaptureId = null
            }
            return
        }
        when (requestCode) {
            REQUEST_ARCHIVE -> { archive = uri; show("Profile ZIP selected for explicit staging") }
            REQUEST_PIN -> { pin = uri; show("P-256 public pin selected for explicit staging") }
            REQUEST_SIGNATURE -> Thread {
                val result = runCatching {
                    contentResolver.openInputStream(uri).use {
                        store.stageDetachedSignature(requireNotNull(it))
                    }
                }
                runOnUiThread {
                    show(if (result.isSuccess) "Signature staged; restart controller for AA05 bench attempt"
                        else "Signature import rejected: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                }
            }.start()
            REQUEST_EXPORT -> {
                val bytes = requestToExport ?: return
                requestToExport = null
                val result = runCatching {
                    contentResolver.openOutputStream(uri, "w").use { requireNotNull(it).write(bytes) }
                }
                show(if (result.isSuccess) "MFi-bound signing request exported"
                    else "Export failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
            }
            REQUEST_TIMING_EXPORT -> {
                val bytes = timingToExport ?: return
                val captureId = timingExportCaptureId
                timingToExport = null
                timingExportCaptureId = null
                Thread {
                    val current = captureId != null &&
                        BenchTimingCaptureCatalog.summary()?.capture?.captureId == captureId
                    val result = if (current) runCatching {
                        contentResolver.openOutputStream(uri, "w").use {
                            requireNotNull(it).write(bytes)
                        }
                    } else null
                    runOnUiThread {
                        show(when {
                            !current -> "Timing capture expired before export"
                            result?.isSuccess == true -> "Sanitized timing NDJSON exported"
                            else -> "Timing export failed"
                        })
                    }
                }.start()
            }
        }
    }

    private fun pick(request: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, request)
    }

    private fun LinearLayout.button(label: String, click: () -> Unit) {
        addView(Button(this@BenchUltraProfileStageActivity).apply {
            text = label
            setOnClickListener { click() }
        })
    }

    private fun show(message: String) { status.text = message }

    private companion object {
        const val REQUEST_ARCHIVE = 101
        const val REQUEST_PIN = 102
        const val REQUEST_SIGNATURE = 103
        const val REQUEST_EXPORT = 104
        const val REQUEST_TIMING_EXPORT = 105
        const val REQUEST_CAR_SPEED_PERMISSION = 106
    }
}

/** Fixed UI labels: never surface provider URIs, file contents or exception details. */
internal fun benchStageFailureLabel(failure: Throwable?): String = when {
    failure is java.io.FileNotFoundException -> "Selected file could not be opened"
    failure is IllegalArgumentException &&
        failure.message.orEmpty().contains("vehicle ID", ignoreCase = true) ->
        "Invalid bench vehicle ID"
    failure is IllegalArgumentException &&
        failure.message.orEmpty().contains("Profile ZIP", ignoreCase = true) ->
        "Invalid Profile ZIP"
    failure is IllegalArgumentException &&
        (failure.message.orEmpty().contains("P-256", ignoreCase = true) ||
            failure.message.orEmpty().contains("public key", ignoreCase = true)) ->
        "Invalid P-256 public key"
    failure is IllegalArgumentException -> "Invalid ZIP, public key, or vehicle ID"
    else -> "Could not read or stage the selected files"
}
