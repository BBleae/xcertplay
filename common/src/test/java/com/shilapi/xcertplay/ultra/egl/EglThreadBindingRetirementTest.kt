package com.shilapi.xcertplay.ultra.egl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EglThreadBindingRetirementTest {
    @Test
    fun failedUnbindNeverProvesRetirementAndSuccessfulRetryDoes() {
        val binding = EglThreadBindingRetirement()
        assertFalse(binding.isReleased)

        assertFalse(binding.release { false })
        assertFalse(binding.isReleased)

        assertTrue(binding.release { true })
        assertTrue(binding.isReleased)
    }

    @Test
    fun rebindingOrAmbiguousRetryRevokesEarlierReleaseProof() {
        val binding = EglThreadBindingRetirement()
        assertTrue(binding.release { true })

        binding.markBoundOrUnknown()
        assertFalse(binding.isReleased)
        assertTrue(binding.release { true })
        assertTrue(binding.isReleased)

        assertFalse(binding.release { false })
        assertFalse(binding.isReleased)
    }
}
