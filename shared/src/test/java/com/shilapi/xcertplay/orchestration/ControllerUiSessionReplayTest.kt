package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerUiSessionReplayTest {
    @Test
    fun activeAndHostUiStateSurviveADetachAttachGap() {
        val state = ControllerUiSessionReplayState()
        val session = session()

        state.onActive(session)
        val replay = state.onHostUiRequested(session) as ControllerUiSessionSnapshot.Active

        assertSame(session, replay.session)
        assertTrue(replay.hostUiRequested)
        assertSame(replay, state.snapshot())
    }

    @Test
    fun endedAndTransportErrorRemainReplayableTerminalStates() {
        val state = ControllerUiSessionReplayState()
        val session = session()
        state.onActive(session)

        val ended = state.onEnded(session) as ControllerUiSessionSnapshot.Ended
        assertSame(session, ended.session)
        val failed = state.onTransportError("link lost")
            as ControllerUiSessionSnapshot.TransportError
        assertTrue(failed.revision > ended.revision)
        assertTrue(failed.message == "link lost")
    }

    @Test
    fun replayCursorDeliversEachRevisionOnceAndRejectsLateOlderWork() {
        val state = ControllerUiSessionReplayState()
        val cursor = ControllerUiSessionReplayCursor()
        val session = session()
        val active = checkNotNull(state.onActive(session))
        val hostUi = checkNotNull(state.onHostUiRequested(session))

        assertTrue(cursor.accept(hostUi))
        assertFalse(cursor.accept(active))
        assertFalse(cursor.accept(hostUi))
        assertTrue(cursor.accept(checkNotNull(state.onEnded(session))))
    }

    @Test
    fun anOldSessionEndingCannotOverwriteANewerActiveSession() {
        val state = ControllerUiSessionReplayState()
        val old = session()
        val current = session()
        state.onActive(old)
        val active = checkNotNull(state.onActive(current))

        assertTrue(state.onEnded(old) == null)
        assertSame(active, state.snapshot())
    }

    @Test
    fun terminalLatestSessionRejectsLateEndedAndHostUiFromAnOlderSession() {
        val state = ControllerUiSessionReplayState()
        val old = session()
        val latest = session()
        checkNotNull(state.onActive(old))
        checkNotNull(state.onActive(latest))
        val terminal = checkNotNull(state.onEnded(latest))

        assertNull(state.onEnded(old))
        assertNull(state.onHostUiRequested(old))
        assertNull(state.onActive(old))
        assertSame(terminal, state.snapshot())
    }

    @Test
    fun hostUiWithoutPriorActiveIsAcceptedOnlyFromIdleAndCannotReviveTerminalSession() {
        val state = ControllerUiSessionReplayState()
        val session = session()
        val hostUi = checkNotNull(state.onHostUiRequested(session))

        assertTrue((hostUi as ControllerUiSessionSnapshot.Active).hostUiRequested)
        checkNotNull(state.onEnded(session))
        assertNull(state.onHostUiRequested(session))
    }

    @Test
    fun skippedRevisionRequiresCompleteReplaySoHostUiCannotOvertakeActive() {
        val state = ControllerUiSessionReplayState()
        val cursor = ControllerUiSessionReplayCursor()
        val session = session()
        assertTrue(cursor.accept(state.snapshot()))
        val active = checkNotNull(state.onActive(session))
        val hostUi = checkNotNull(state.onHostUiRequested(session))

        val previous = checkNotNull(cursor.acceptAfter(hostUi))
        assertTrue(requiresCompleteUiSessionReplay(previous, hostUi))
        assertFalse(cursor.accept(active))

        val callbacks = mutableListOf<String>()
        replayControllerUiSessionSnapshot(
            object : AirPlaySessionListener {
                override fun onSessionActive(session: AirPlaySession) {
                    callbacks += "active"
                }

                override fun onHostUiRequested(session: AirPlaySession) {
                    callbacks += "host-ui"
                }
            },
            hostUi,
        )
        assertEquals(listOf("active", "host-ui"), callbacks)
    }

    @Test
    fun queuedViewAreaRejectionRemainsReplayableAcrossADetachGap() {
        val state = ControllerUiSessionReplayState()
        checkNotNull(state.onActive(session()))
        val rejection = state.onTransportError("AirPlay view area switch rejected")
        val errors = mutableListOf<String>()

        replayControllerUiSessionSnapshot(
            object : AirPlaySessionListener {
                override fun onTransportError(message: String) {
                    errors += message
                }
            },
            rejection,
        )

        assertEquals(listOf("AirPlay view area switch rejected"), errors)
    }

    @Test
    fun attachGapReplayRestoresActiveBeforeHostUiAndReplaysTerminalState() {
        val session = session()
        val callbacks = mutableListOf<String>()
        val listener = object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                callbacks += "active"
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                callbacks += "host-ui"
            }

            override fun onSessionEnded(session: AirPlaySession) {
                callbacks += "ended"
            }
        }
        val state = ControllerUiSessionReplayState()
        state.onActive(session)
        val activeWithHostUi = checkNotNull(state.onHostUiRequested(session))

        replayControllerUiSessionSnapshot(listener, activeWithHostUi)
        assertEquals(listOf("active", "host-ui"), callbacks)

        callbacks.clear()
        replayControllerUiSessionSnapshot(listener, checkNotNull(state.onEnded(session)))
        assertEquals(listOf("ended"), callbacks)
    }

    private fun session(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(800, 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
