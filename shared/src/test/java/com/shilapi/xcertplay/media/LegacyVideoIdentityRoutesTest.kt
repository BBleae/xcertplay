package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayStreamIdentity
import com.shilapi.xcertplay.airplay.AirPlayVideoSessionEpoch
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyVideoIdentityRoutesTest {
    @Test
    fun sameTypeHandlesAreNeverCollapsedAndCommittedStreamSelectionIsStable() {
        val routes = LegacyVideoHandleRoutes()
        val first = handle(AirPlayStreamIdentity(110, "1", null))
        val second = handle(AirPlayStreamIdentity(110, "2", null))

        assertEquals(first, routes.observe(first).currentHandle)
        assertEquals(first, routes.observe(second).currentHandle)

        val secondActive = routes.setActive(second, true)
        assertEquals(first, secondActive.previousHandle)
        assertEquals(second, secondActive.currentHandle)
        assertTrue(secondActive.selectionChanged)
        assertTrue(secondActive.activeChanged)

        val firstActive = routes.setActive(first, true)
        assertEquals(second, firstActive.currentHandle)
        assertFalse(firstActive.selectionChanged)
        assertFalse(firstActive.activeChanged)

        val secondStopped = routes.setActive(second, false)
        assertEquals(first, secondStopped.currentHandle)
        assertTrue(secondStopped.selectionChanged)
        assertFalse(secondStopped.activeChanged)

        val firstStopped = routes.setActive(first, false)
        assertNull(firstStopped.currentHandle)
        assertTrue(firstStopped.activeChanged)
        assertFalse(firstStopped.isActive)
    }

    @Test
    fun selectionsAreIndependentAcrossDisplayTypes() {
        val routes = LegacyVideoHandleRoutes()
        val main = handle(AirPlayStreamIdentity(110, "7", null))
        val cluster = handle(AirPlayStreamIdentity(111, "7", null))

        routes.observe(main)
        routes.observe(cluster)
        routes.setActive(main, true)
        routes.setActive(cluster, true)

        assertEquals(main, routes.selected(110))
        assertEquals(cluster, routes.selected(111))
        assertTrue(routes.isSelected(main))
        assertTrue(routes.isSelected(cluster))
    }

    @Test
    fun identicalProtocolIdentitiesFromDifferentSessionsRemainDistinct() {
        val routes = LegacyVideoHandleRoutes()
        val identity = AirPlayStreamIdentity(110, "9", null)
        val oldSession = handle(identity)
        val newSession = handle(identity)

        routes.observe(oldSession)
        routes.setActive(oldSession, true)
        routes.observe(newSession)
        routes.setActive(newSession, true)

        assertEquals(oldSession, routes.selected(110))
        val oldStopped = routes.setActive(oldSession, false)
        assertEquals(newSession, oldStopped.currentHandle)
        assertTrue(routes.isSelected(newSession))
    }

    @Test
    fun fallibleRouteMutationCanRestoreItsExactPreviousSnapshot() {
        val routes = LegacyVideoHandleRoutes()
        val old = handle(AirPlayStreamIdentity(110, "old", null))
        val candidate = handle(AirPlayStreamIdentity(110, "candidate", null))
        routes.observe(old)
        routes.setActive(old, true)
        routes.observe(candidate)
        val before = routes.snapshot(110)

        routes.setActive(old, false)
        routes.restore(before)

        assertEquals(old, routes.selected(110))
        assertTrue(routes.isSelected(old))
        assertFalse(routes.isSelected(candidate))
        assertFalse(routes.setActive(candidate, true).activeChanged)
    }

    private fun handle(identity: AirPlayStreamIdentity) =
        AirPlayVideoStreamHandle(identity, AirPlayVideoSessionEpoch())
}
