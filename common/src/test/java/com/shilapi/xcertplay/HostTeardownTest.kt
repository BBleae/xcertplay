package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostTeardownTest {
    @Test
    fun controllerTimeoutDoesNotTouchSinkOrAllowReplacement() {
        var sinkCloseCalls = 0

        val outcome = runHostTeardown(
            requestControllerClose = {},
            awaitControllerClosed = { false },
            closeMediaSink = { sinkCloseCalls++ },
        )

        assertEquals(
            HostTeardownStage.CONTROLLER_AWAIT,
            (outcome as HostTeardownOutcome.Failed).stage,
        )
        assertFalse(outcome.isClean)
        assertEquals(0, sinkCloseCalls)
        assertEquals(
            HostTeardownUiDecision.FAILED,
            decideHostTeardownUi(outcome, 7, 7, shuttingDown = false, destroyed = false),
        )
    }

    @Test
    fun sinkExceptionIsAStableFailedStage() {
        val failure = IllegalStateException("decoder retirement uncertain")

        val outcome = runHostTeardown(
            requestControllerClose = {},
            awaitControllerClosed = { true },
            closeMediaSink = { throw failure },
        )

        outcome as HostTeardownOutcome.Failed
        assertEquals(HostTeardownStage.MEDIA_SINK_CLOSE, outcome.stage)
        assertSame(failure, outcome.cause)
        assertEquals("media-sink-close", outcome.stage.logId)
    }

    @Test
    fun controllerAwaitAndSinkCloseMustBothSucceed() {
        val operations = mutableListOf<String>()

        val outcome = runHostTeardown(
            requestControllerClose = { operations += "controller-close" },
            awaitControllerClosed = {
                operations += "controller-await"
                true
            },
            closeMediaSink = { operations += "sink-close" },
        )

        assertSame(HostTeardownOutcome.Clean, outcome)
        assertTrue(outcome.isClean)
        assertEquals(
            listOf("controller-close", "controller-await", "sink-close"),
            operations,
        )
    }

    @Test
    fun staleGenerationCannotCompleteUiOrStartReplacement() {
        assertEquals(
            HostTeardownUiDecision.STALE_OR_STOPPING,
            decideHostTeardownUi(
                HostTeardownOutcome.Clean,
                expectedGeneration = 11,
                currentGeneration = 12,
                shuttingDown = false,
                destroyed = false,
            ),
        )
        assertEquals(
            HostTeardownUiDecision.STALE_OR_STOPPING,
            decideHostTeardownUi(
                HostTeardownOutcome.Clean,
                expectedGeneration = 12,
                currentGeneration = 12,
                shuttingDown = false,
                destroyed = true,
            ),
        )
    }

    @Test
    fun shutdownContinuationRunsAfterSinkException() {
        var cleanupOutcome: HostTeardownOutcome? = null

        val outcome = runHostTeardown(
            requestControllerClose = {},
            awaitControllerClosed = { true },
            closeMediaSink = { throw IllegalStateException("retryable") },
            afterTeardown = { cleanupOutcome = it },
        )

        assertSame(outcome, cleanupOutcome)
        assertEquals(
            HostTeardownStage.MEDIA_SINK_CLOSE,
            (cleanupOutcome as HostTeardownOutcome.Failed).stage,
        )
    }

    @Test
    fun errorsAreNotClassifiedOrSwallowed() {
        val fatal = AssertionError("fatal")
        try {
            runHostTeardown(
                requestControllerClose = null,
                awaitControllerClosed = null,
                closeMediaSink = { throw fatal },
            )
            fail("Expected the Error to propagate")
        } catch (actual: AssertionError) {
            assertSame(fatal, actual)
        }
    }

    @Test
    fun backgroundAdoptionRetiresTheWholePairWhenControllerOrCallbacksAreUnavailable() {
        assertEquals(
            BackgroundSessionAdoptionAction.RETIRE_CLOSED_CONTROLLER,
            decideBackgroundSessionAdoption(
                controllerClosed = true,
                callbacksAttached = false,
            ),
        )
        assertEquals(
            BackgroundSessionAdoptionAction.RETIRE_CALLBACK_ATTACH_FAILURE,
            decideBackgroundSessionAdoption(
                controllerClosed = false,
                callbacksAttached = false,
            ),
        )
        assertEquals(
            BackgroundSessionAdoptionAction.ADOPT,
            decideBackgroundSessionAdoption(
                controllerClosed = false,
                callbacksAttached = true,
            ),
        )
    }
}
