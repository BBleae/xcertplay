package com.shilapi.xcertplay.bench

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.ultra.CarPlayUltraCustomCanvas
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable

/** Clearly labelled Debug MAIN test frame; never contains vehicle or instrument values. */
internal object BenchLocalCanvasPainter {
    const val CONTENT_ID = "canvas.main.bench"

    /** Installs only if an app-provided painter has not claimed this signed content ID. */
    fun installIfAbsent(): Closeable? = CarPlayUltraCustomCanvas.registerPainterIfAbsent(
        CONTENT_ID, ::paint,
    )

    /** Exact, compiler-selected MAIN v2 slot; never install for video or native instruments. */
    fun selectedBy(assignments: Collection<CompiledUiSyncDisplayAssignment>): Boolean {
        val main = assignments.singleOrNull { it.destination == DisplayDestination.MAIN }
            ?: return false
        return main.additionalVariants.isEmpty() &&
            main.slotIngressAssignments.isEmpty() &&
            main.instrumentReferenceAssignments.isEmpty() &&
            main.localCanvasAssignments.singleOrNull()?.contentId == CONTENT_ID
    }

    private fun paint(canvas: Canvas, size: PixelSize) {
        val width = size.widthPixels.toFloat()
        val height = size.heightPixels.toFloat()
        require(width > 0f && height > 0f)
        canvas.drawColor(Color.rgb(14, 26, 40))
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 79, 112) }
        canvas.drawRect(0f, 0f, width, height * 0.17f, stripe)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (height * 0.095f).coerceIn(20f, 72f)
            isFakeBoldText = true
        }
        val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 196, 108)
            textSize = (height * 0.062f).coerceIn(16f, 46f)
            isFakeBoldText = true
        }
        canvas.drawText("XCERTPLAY LOCAL CANVAS", width * 0.055f, height * 0.39f, title)
        canvas.drawText("BENCH / NOT VEHICLE SAFE", width * 0.055f, height * 0.57f, detail)
    }
}
