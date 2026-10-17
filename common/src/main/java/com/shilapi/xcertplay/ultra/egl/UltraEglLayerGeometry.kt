package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraAffinePixelPoint
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.ultra.UltraSceneLayer

/** Pure projection shared by GLES drawing and local-draw preflight. */
internal object UltraEglLayerGeometry {
    internal data class Projected(
        val bottomLeft: UltraAffinePixelPoint,
        val bottomRight: UltraAffinePixelPoint,
        val topLeft: UltraAffinePixelPoint,
        val topRight: UltraAffinePixelPoint,
        /** Axis-aligned output-pixel clip, expressed with a top-left origin. */
        val scissorPixels: UltraPixelRect,
    ) {
        fun normalizedTriangleStrip(viewport: PixelSize): FloatArray {
            fun x(point: UltraAffinePixelPoint) =
                (2.0 * point.x / viewport.widthPixels - 1.0).toFloat()
            fun y(point: UltraAffinePixelPoint) =
                (1.0 - 2.0 * point.y / viewport.heightPixels).toFloat()
            return floatArrayOf(
                x(bottomLeft), y(bottomLeft),
                x(bottomRight), y(bottomRight),
                x(topLeft), y(topLeft),
                x(topRight), y(topRight),
            )
        }
    }

    fun project(layer: UltraSceneLayer, viewport: PixelSize): Projected? {
        val frame = layer.framePixels
        if (layer.opacity <= 0.0 || frame.widthPixels == 0 || frame.heightPixels == 0) {
            return null
        }
        // An affine moves the quad, not its output-pixel clip. With no explicit clip, the output
        // viewport is the clip. The identity case keeps the previous frame/intersection scissor.
        val scissor = intersectWithViewport(
            if (layer.transformPixels.isIdentity) frame else null,
            layer.clipPixels,
            viewport,
        ) ?: return null
        val left = frame.xPixels.toLong()
        val top = frame.yPixels.toLong()
        val right = left + frame.widthPixels
        val bottom = top + frame.heightPixels
        val transform = layer.transformPixels
        val projected = Projected(
            transform.project(left, bottom),
            transform.project(right, bottom),
            transform.project(left, top),
            transform.project(right, top),
            scissor,
        )
        if (!layer.transformPixels.isIdentity && !overlapsClip(projected)) return null
        return projected
    }

    private fun intersectWithViewport(
        frame: UltraPixelRect?,
        requested: UltraPixelRect?,
        viewport: PixelSize,
    ): UltraPixelRect? {
        var left = 0L
        var top = 0L
        var right = viewport.widthPixels.toLong()
        var bottom = viewport.heightPixels.toLong()
        for (rect in listOfNotNull(frame, requested)) {
            left = maxOf(left, rect.xPixels.toLong())
            top = maxOf(top, rect.yPixels.toLong())
            right = minOf(right, rect.xPixels.toLong() + rect.widthPixels)
            bottom = minOf(bottom, rect.yPixels.toLong() + rect.heightPixels)
        }
        if (right <= left || bottom <= top) return null
        return UltraPixelRect(left.toInt(), top.toInt(), (right - left).toInt(),
            (bottom - top).toInt())
    }

    /** Separating-axis test; a mere axis-aligned bounding-box overlap is not enough for rotations. */
    private fun overlapsClip(projected: Projected): Boolean {
        val quad = listOf(
            projected.topLeft, projected.topRight,
            projected.bottomRight, projected.bottomLeft,
        )
        val xEdge = projected.topRight.x - projected.topLeft.x
        val yEdge = projected.topRight.y - projected.topLeft.y
        val xSide = projected.bottomLeft.x - projected.topLeft.x
        val ySide = projected.bottomLeft.y - projected.topLeft.y
        if (xEdge * ySide - yEdge * xSide == 0.0) return false

        val clip = projected.scissorPixels
        val left = clip.xPixels.toDouble()
        val top = clip.yPixels.toDouble()
        val right = clip.xPixels.toLong().plus(clip.widthPixels).toDouble()
        val bottom = clip.yPixels.toLong().plus(clip.heightPixels).toDouble()
        val rectangle = listOf(
            UltraAffinePixelPoint(left, top), UltraAffinePixelPoint(right, top),
            UltraAffinePixelPoint(right, bottom), UltraAffinePixelPoint(left, bottom),
        )
        val axes = ArrayList<UltraAffinePixelPoint>(6)
        axes += UltraAffinePixelPoint(1.0, 0.0)
        axes += UltraAffinePixelPoint(0.0, 1.0)
        quad.indices.forEach { index ->
            val a = quad[index]
            val b = quad[(index + 1) % quad.size]
            axes += UltraAffinePixelPoint(a.y - b.y, b.x - a.x)
        }
        return axes.all { axis ->
            fun dot(point: UltraAffinePixelPoint) = axis.x * point.x + axis.y * point.y
            val quadValues = quad.map(::dot)
            val rectangleValues = rectangle.map(::dot)
            quadValues.maxOrNull()!! > rectangleValues.minOrNull()!! &&
                rectangleValues.maxOrNull()!! > quadValues.minOrNull()!!
        }
    }
}
