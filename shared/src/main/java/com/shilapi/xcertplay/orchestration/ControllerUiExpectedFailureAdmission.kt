package com.shilapi.xcertplay.orchestration

internal data class ControllerUiExpectedSessionState<S : Any>(
    val closed: Boolean,
    val activeSession: S?,
    val viewAreaToken: Int,
)

/**
 * Atomically admits an asynchronous failure only while its exact session and operation token are
 * still current. [recordFailure] must only mutate state/enqueue work; it must not call listeners.
 */
internal fun <S : Any> recordExpectedSessionFailure(
    lock: Any,
    expectedSession: S,
    expectedViewAreaToken: Int,
    readState: () -> ControllerUiExpectedSessionState<S>,
    recordFailure: () -> Unit,
): Boolean = synchronized(lock) {
    val state = readState()
    if (
        state.closed ||
        state.activeSession !== expectedSession ||
        state.viewAreaToken != expectedViewAreaToken
    ) {
        false
    } else {
        recordFailure()
        true
    }
}
