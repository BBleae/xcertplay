package com.shilapi.xcertplay.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** One absolute monotonic deadline shared by every phase of an operation. */
internal class BoundedOperationDeadline(
    timeoutMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val deadlineNanos: Long

    init {
        require(timeoutMillis in 0L..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 0 and $MAX_TIMEOUT_MILLIS"
        }
        val now = nanoTime()
        val duration = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        deadlineNanos = if (duration > 0L && now > Long.MAX_VALUE - duration) {
            Long.MAX_VALUE
        } else {
            now + duration
        }
    }

    fun remainingMillis(): Long {
        val remaining = deadlineNanos - nanoTime()
        return if (remaining <= 0L) 0L else TimeUnit.NANOSECONDS.toMillis(remaining)
    }

    companion object {
        const val MAX_TIMEOUT_MILLIS = 60_000L
    }
}

internal enum class BoundedLockResult { ACQUIRED, TIMED_OUT, INTERRUPTED }

internal fun tryLockBeforeDeadline(
    lock: ReentrantLock,
    deadline: BoundedOperationDeadline,
): BoundedLockResult = try {
    if (lock.tryLock(deadline.remainingMillis(), TimeUnit.MILLISECONDS)) {
        BoundedLockResult.ACQUIRED
    } else {
        BoundedLockResult.TIMED_OUT
    }
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    BoundedLockResult.INTERRUPTED
}

internal inline fun <T> forEachWithinDeadline(
    values: Iterable<T>,
    deadline: BoundedOperationDeadline,
    action: (T, remainingMillis: Long) -> Unit,
) {
    values.forEach { value -> action(value, deadline.remainingMillis()) }
}
