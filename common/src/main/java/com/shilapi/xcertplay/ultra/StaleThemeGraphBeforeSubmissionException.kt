package com.shilapi.xcertplay.ultra

/**
 * A changed immutable graph was detected before the EGL backend's resolve/submit phase began.
 * Only this specific pre-submit failure may be retried with a fresh graph. Generic backend or
 * post-submit failures can have uncertain side effects and must not be retried as stale input.
 */
internal class StaleThemeGraphBeforeSubmissionException : IllegalStateException(
    "Activation requires the exact current renderer graph",
)
