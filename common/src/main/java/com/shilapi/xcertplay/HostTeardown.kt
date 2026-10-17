package com.shilapi.xcertplay

/** Stable stages used to report a fail-closed host teardown without exposing exception text. */
internal enum class HostTeardownStage(
    val logId: String,
    val userDetail: String,
) {
    CONTROLLER_CLOSE(
        logId = "controller-close",
        userDetail = "The previous controller could not begin shutdown; a replacement stack was not started",
    ),
    CONTROLLER_AWAIT(
        logId = "controller-await",
        userDetail = "The previous transport worker is still running; a replacement stack was not started",
    ),
    MEDIA_SINK_CLOSE(
        logId = "media-sink-close",
        userDetail = "The previous media renderer could not prove Surface retirement; a replacement stack was not started",
    ),
    DEFERRED_RESOURCE_RELEASE(
        logId = "deferred-resource-release",
        userDetail = "A retired display resource could not be released; a replacement stack was not started",
    ),
}

internal sealed interface HostTeardownOutcome {
    val isClean: Boolean

    data object Clean : HostTeardownOutcome {
        override val isClean: Boolean = true
    }

    data class Failed(
        val stage: HostTeardownStage,
        val cause: Exception? = null,
    ) : HostTeardownOutcome {
        override val isClean: Boolean = false
    }
}

/**
 * Runs blocking host teardown outside the UI thread and classifies ordinary operational failures.
 *
 * The sink is not touched until controller retirement is proven. In particular, a controller
 * timeout leaves the renderer and its Surface leases intact so the same resources can be retried.
 * [Error] is deliberately not caught: VM/linkage/assertion failures are not operational teardown
 * outcomes and must retain their normal propagation semantics.
 *
 * [afterTeardown] runs for every classified outcome. Shutdown uses that guarantee to continue
 * process cleanup after an [Exception] from the media sink.
 */
internal fun runHostTeardown(
    requestControllerClose: (() -> Unit)?,
    awaitControllerClosed: (() -> Boolean)?,
    closeMediaSink: (() -> Unit)?,
    afterTeardown: (HostTeardownOutcome) -> Unit = {},
): HostTeardownOutcome {
    require((requestControllerClose == null) == (awaitControllerClosed == null)) {
        "Controller close and await operations must be supplied together"
    }

    val outcome = if (requestControllerClose != null) {
        val closeFailure = try {
            requestControllerClose()
            null
        } catch (error: Exception) {
            HostTeardownOutcome.Failed(HostTeardownStage.CONTROLLER_CLOSE, error)
        }
        closeFailure ?: run {
            val awaitResult = try {
                if (checkNotNull(awaitControllerClosed).invoke()) {
                    null
                } else {
                    HostTeardownOutcome.Failed(HostTeardownStage.CONTROLLER_AWAIT)
                }
            } catch (error: Exception) {
                HostTeardownOutcome.Failed(HostTeardownStage.CONTROLLER_AWAIT, error)
            }
            awaitResult ?: closeMediaSinkOutcome(closeMediaSink)
        }
    } else {
        closeMediaSinkOutcome(closeMediaSink)
    }
    afterTeardown(outcome)
    return outcome
}

private fun closeMediaSinkOutcome(closeMediaSink: (() -> Unit)?): HostTeardownOutcome {
    if (closeMediaSink == null) return HostTeardownOutcome.Clean
    return try {
        closeMediaSink()
        HostTeardownOutcome.Clean
    } catch (error: Exception) {
        HostTeardownOutcome.Failed(HostTeardownStage.MEDIA_SINK_CLOSE, error)
    }
}

internal enum class HostTeardownUiDecision {
    READY,
    FAILED,
    STALE_OR_STOPPING,
}

/** A stale Activity/generation can neither report over its successor nor start replacement work. */
internal fun decideHostTeardownUi(
    outcome: HostTeardownOutcome,
    expectedGeneration: Int,
    currentGeneration: Int,
    shuttingDown: Boolean,
    destroyed: Boolean,
): HostTeardownUiDecision = when {
    shuttingDown || destroyed || expectedGeneration != currentGeneration ->
        HostTeardownUiDecision.STALE_OR_STOPPING
    !outcome.isClean -> HostTeardownUiDecision.FAILED
    else -> HostTeardownUiDecision.READY
}

internal enum class BackgroundSessionAdoptionAction {
    ADOPT,
    RETIRE_CLOSED_CONTROLLER,
    RETIRE_CALLBACK_ATTACH_FAILURE,
}

/** A background pair is indivisible: failure to adopt either side retires both through barrier. */
internal fun decideBackgroundSessionAdoption(
    controllerClosed: Boolean,
    callbacksAttached: Boolean,
): BackgroundSessionAdoptionAction = when {
    controllerClosed -> BackgroundSessionAdoptionAction.RETIRE_CLOSED_CONTROLLER
    !callbacksAttached -> BackgroundSessionAdoptionAction.RETIRE_CALLBACK_ATTACH_FAILURE
    else -> BackgroundSessionAdoptionAction.ADOPT
}
