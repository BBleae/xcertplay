package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SerializedCallbackQueueTest {
    @Test
    fun selectionConfigIsPublishedBeforeNewFramesAndOldFramesAreDropped() {
        val gate = OrderedVideoCallbackGate<Any, String>()
        val old = Any()
        val current = Any()
        val events = mutableListOf<String>()
        gate.reserveSelection(old, "old-config") { events += it }.run()
        gate.reserveFrame(old) { events += "old-frame-before-switch" }.run()

        val switch = gate.reserveSelection(current, "new-config") { events += it }
        val staleFrame = gate.reserveFrame(old) { events += "stale-frame" }
        val currentFrame = gate.reserveFrame(current) { events += "new-frame" }
        // Any reserved task may become the drainer; reservation order remains authoritative.
        staleFrame.run()
        switch.run()
        currentFrame.run()

        assertEquals(
            listOf("old-config", "old-frame-before-switch", "new-config", "new-frame"),
            events,
        )
    }

    @Test
    fun framesRemainClosedUntilSelectedHandleHasAConfig() {
        val gate = OrderedVideoCallbackGate<Any, String>()
        val handle = Any()
        val events = mutableListOf<String>()

        gate.reserveSelection(handle, null) { events += it }.run()
        gate.reserveFrame(handle) { events += "too-early" }.run()
        gate.reserveConfig(handle, "config") { events += it }.run()
        gate.reserveFrame(handle) { events += "frame" }.run()

        assertEquals(listOf("config", "frame"), events)
    }

    @Test
    fun reentrantReservationDoesNotSelfDeadlockOrRunBeforeSelectionPublishes() {
        val gate = OrderedVideoCallbackGate<Any, String>()
        val handle = Any()
        val events = mutableListOf<String>()

        gate.reserveSelection(handle, "config") { config ->
            events += config
            gate.reserveFrame(handle) { events += "reentrant-frame" }.run()
            events += "config-return"
        }.run()

        assertEquals(listOf("config", "config-return", "reentrant-frame"), events)
    }

    @Test
    fun failedReplacementConfigClosesTheFrameGate() {
        val gate = OrderedVideoCallbackGate<Any, String>()
        val handle = Any()
        val events = mutableListOf<String>()
        gate.reserveSelection(handle, "initial") { events += it }.run()
        gate.reserveFrame(handle) { events += "initial-frame" }.run()

        assertThrows(IllegalStateException::class.java) {
            gate.reserveConfig(handle, "broken") {
                events += it
                throw IllegalStateException("publish failed")
            }.run()
        }
        gate.reserveFrame(handle) { events += "must-not-publish" }.run()

        assertEquals(listOf("initial", "initial-frame", "broken"), events)

        gate.reserveConfig(handle, "recovered") { events += it }.run()
        gate.reserveFrame(handle) { events += "recovered-frame" }.run()
        assertEquals(
            listOf("initial", "initial-frame", "broken", "recovered", "recovered-frame"),
            events,
        )
    }
}
