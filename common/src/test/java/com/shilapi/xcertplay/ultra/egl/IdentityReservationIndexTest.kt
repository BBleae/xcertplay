package com.shilapi.xcertplay.ultra.egl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityReservationIndexTest {
    @Test
    fun sameReferenceIsRejectedWhileStructurallyEqualDistinctObjectsRemainDistinct() {
        data class Key(val value: String)
        val first = Key("same")
        val equalButDistinct = Key("same")
        val firstReservation = Any()
        val secondReservation = Any()
        val index = IdentityReservationIndex<Key, Any>()

        index.reserve(first, firstReservation)
        assertThrows(IllegalArgumentException::class.java) {
            index.reserve(first, Any())
        }
        index.reserve(equalButDistinct, secondReservation)

        assertSame(firstReservation, index[first])
        assertSame(secondReservation, index[equalButDistinct])
        assertEquals(2, index.valuesSnapshot().size)
        assertFalse(index.release(first, secondReservation))
        assertTrue(index.release(first, firstReservation))
    }
}
