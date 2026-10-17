package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraContentId
import com.shilapi.xcertplay.ultra.UltraLayerId
import com.shilapi.xcertplay.ultra.UltraPixelAffineTransform
import com.shilapi.xcertplay.ultra.UltraPixelRect
import com.shilapi.xcertplay.ultra.UltraSceneLayer
import com.shilapi.xcertplay.ultra.UltraSceneSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UltraEglLayerGeometryTest {
    private val viewport = PixelSize(100, 100)

    @Test
    fun identityKeepsFrameClipAndOriginalFourCornerProjection() {
        val layer = layer(UltraPixelRect(-10, 20, 40, 50),
            clip = UltraPixelRect(0, 30, 20, 10))

        val projected = checkNotNull(UltraEglLayerGeometry.project(layer, viewport))

        assertEquals(UltraPixelRect(0, 30, 20, 10), projected.scissorPixels)
        assertArrayEquals(floatArrayOf(
            -1.2f, -0.4f,
            -0.4f, -0.4f,
            -1.2f, 0.6f,
            -0.4f, 0.6f,
        ), projected.normalizedTriangleStrip(viewport), 0.00001f)
    }

    @Test
    fun translatedFrameUsesOutputPixelClipAfterTransformation() {
        val transform = UltraPixelAffineTransform(translateXPixels = 50.0)
        val fromOutside = layer(UltraPixelRect(-40, 10, 20, 20), transform = transform)
        val projected = checkNotNull(UltraEglLayerGeometry.project(fromOutside, viewport))

        assertEquals(UltraPixelRect(0, 0, 100, 100), projected.scissorPixels)
        assertEquals(10.0, projected.topLeft.x, 0.0)
        assertEquals(30.0, projected.bottomRight.x, 0.0)
        assertEquals(UltraPixelRect(15, 0, 10, 100),
            UltraEglLayerGeometry.project(fromOutside.copy(
                clipPixels = UltraPixelRect(15, 0, 10, 100)), viewport)?.scissorPixels)
        assertNull(UltraEglLayerGeometry.project(fromOutside.copy(
            clipPixels = UltraPixelRect(35, 0, 10, 100)), viewport))
    }

    @Test
    fun rotationAndShearProjectEachCornerWithoutTransformingTextureCoordinates() {
        val layer = layer(UltraPixelRect(10, 20, 10, 20),
            transform = UltraPixelAffineTransform(
                xx = 0.0, xy = -1.0, yx = 1.0, yy = 0.0,
                translateXPixels = 50.0,
            ))

        val projected = checkNotNull(UltraEglLayerGeometry.project(layer, viewport))

        assertArrayEquals(floatArrayOf(
            -0.8f, 0.8f,
            -0.8f, 0.6f,
            -0.4f, 0.8f,
            -0.4f, 0.6f,
        ), projected.normalizedTriangleStrip(viewport), 0.00001f)
    }

    @Test
    fun rotatedBoundingBoxOverlapAloneDoesNotCountAsDraw() {
        val diamond = layer(UltraPixelRect(0, 0, 10, 10),
            clip = UltraPixelRect(40, 50, 2, 2),
            transform = UltraPixelAffineTransform(
                xx = 1.0, xy = -1.0, yx = 1.0, yy = 1.0,
                translateXPixels = 50.0, translateYPixels = 50.0,
            ))

        assertNull(UltraEglLayerGeometry.project(diamond, viewport))
    }

    @Test
    fun largeSignedRectangleArithmeticDoesNotWrapAndTransformedOutputIsBounded() {
        val huge = UltraPixelRect(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        val identity = layer(huge)
        assertNull(UltraEglLayerGeometry.project(identity, viewport))
        assertThrows(IllegalArgumentException::class.java) {
            layer(huge, transform = UltraPixelAffineTransform(xx = 2.0))
        }
    }

    private fun layer(
        frame: UltraPixelRect,
        clip: UltraPixelRect? = null,
        transform: UltraPixelAffineTransform = UltraPixelAffineTransform.IDENTITY,
    ) = UltraSceneLayer(
        UltraLayerId("local"), UltraSceneSource.Local(UltraContentId("content")),
        frame, clipPixels = clip, transformPixels = transform,
    )
}
