package com.shilapi.xcertplay.ultra.egl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremultipliedAlphaTest {
    @Test
    fun opacityScalesEveryPremultipliedChannelIncludingRgb() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0.1f, 0.05f, 0.25f),
            scalePremultipliedRgba(0.8f, 0.4f, 0.2f, 1f, 0.25f),
            0.0001f,
        )
    }

    @Test
    fun externalTextureIsTransparentUntilFirstSuccessfulUpdate() {
        val ingress = object : UltraEglDriverIngress {
            override val producerEndpoint = Any()
            override var hasUsableFrame = false
        }

        assertFalse(shouldSampleExternalIngress(ingress))
        ingress.hasUsableFrame = true
        assertTrue(shouldSampleExternalIngress(ingress))
    }
}
