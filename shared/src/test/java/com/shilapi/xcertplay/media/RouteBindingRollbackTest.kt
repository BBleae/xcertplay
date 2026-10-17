package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteBindingRollbackTest {
    @Test
    fun failedCandidateRetirementPreventsPreviousRouteFromDoubleBinding() {
        val candidate = Any()
        val previous = Any()
        val reconciled = mutableListOf<Any>()

        assertThrows(IllegalStateException::class.java) {
            rollbackRouteBindingsWithoutOverlap(candidate, previous) { handle ->
                reconciled += handle
                if (handle === candidate) throw IllegalStateException("candidate still owns Surface")
            }
        }

        assertEquals(listOf(candidate), reconciled)
    }

    @Test
    fun successfulCandidateRetirementRunsBeforePreviousReinstall() {
        val candidate = Any()
        val previous = Any()
        val reconciled = mutableListOf<Any>()

        rollbackRouteBindingsWithoutOverlap(candidate, previous, reconciled::add)

        assertEquals(listOf(candidate, previous), reconciled)
    }
}
