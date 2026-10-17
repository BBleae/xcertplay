package com.shilapi.xcertplay.media

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal enum class ResourceReleaseResult {
    RELEASED,
    RELEASE_FAILED,
}

/** A release failure retains the resource so a later close attempt can prove release. */
internal class RetryableResourceRelease<T : Any>(
    val resource: T,
    private val stopAction: (T) -> Unit,
    private val releaseAction: (T) -> Unit,
) {
    private val stateLock = Any()
    @Volatile var lastFailure: Throwable? = null
        private set
    @Volatile var isReleased: Boolean = false
        private set
    private var releaseInProgress = false
    private var inFlightAttempt: FutureTask<ResourceReleaseResult>? = null

    fun release(): ResourceReleaseResult {
        synchronized(stateLock) {
            if (isReleased) return ResourceReleaseResult.RELEASED
            if (releaseInProgress) return ResourceReleaseResult.RELEASE_FAILED
            releaseInProgress = true
        }
        runCatching { stopAction(resource) }
        val failure = try {
            releaseAction(resource)
            null
        } catch (error: Throwable) {
            error
        }
        return synchronized(stateLock) {
            releaseInProgress = false
            if (failure == null) {
                lastFailure = null
                isReleased = true
                ResourceReleaseResult.RELEASED
            } else {
                lastFailure = failure
                ResourceReleaseResult.RELEASE_FAILED
            }
        }
    }

    /**
     * Runs a retry on one tracked daemon attempt. Timeouts never cancel a possibly stuck native
     * release; later callers observe the same attempt instead of invoking release concurrently.
     */
    fun awaitTrackedRelease(timeoutMillis: Long): ResourceReleaseResult? {
        require(timeoutMillis >= 0L) { "timeoutMillis must be non-negative" }
        if (isReleased) return ResourceReleaseResult.RELEASED
        val attempt = synchronized(stateLock) {
            if (isReleased) return ResourceReleaseResult.RELEASED
            inFlightAttempt?.takeUnless(FutureTask<ResourceReleaseResult>::isDone)
                ?: FutureTask { release() }.also { task ->
                    inFlightAttempt = task
                    Thread(task, "carplay-codec-release").apply {
                        isDaemon = true
                        start()
                    }
                }
        }
        val result = try {
            attempt.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return null
        }
        synchronized(stateLock) {
            if (inFlightAttempt === attempt && attempt.isDone) inFlightAttempt = null
        }
        return result
    }
}

internal fun <T : Any> releaseWithEvidence(
    release: RetryableResourceRelease<T>,
    acknowledge: () -> Unit,
): ResourceReleaseResult = release.release().also { result ->
    if (result == ResourceReleaseResult.RELEASED) acknowledge()
}

internal fun <T> commitAfterProvenRelease(
    release: () -> Boolean,
    commit: () -> T,
): T {
    check(release()) { "Decoder release is not proven for terminal handle" }
    return commit()
}
