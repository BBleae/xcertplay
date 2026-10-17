package com.shilapi.xcertplay

import java.io.Closeable

/**
 * Activity-owned wake-up for a process-owned Surface retirement. The process worker never retains
 * an Activity callback. A pending→clear transition is observed without relying on another window,
 * permission, or transport event; restart and destruction cancel the exact scheduled generation.
 */
internal class HostPhysicalRetirementPoller(
    private val currentGeneration: () -> Int,
    private val hasPendingRetirement: () -> Boolean,
    private val requestRetirement: () -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onClear: () -> Unit,
) : Closeable {
    private var armedGeneration: Int? = null
    private var closed = false
    private val retry = Runnable {
        val expected = armedGeneration ?: return@Runnable
        armedGeneration = null
        if (closed || expected != currentGeneration()) return@Runnable
        if (hasPendingRetirement()) {
            requestRetirement()
            arm(expected)
        } else {
            onClear()
        }
    }

    fun arm(generation: Int) {
        if (closed || generation != currentGeneration()) return
        if (armedGeneration == generation) return
        invalidate()
        armedGeneration = generation
        postDelayed(retry, RETRY_MILLIS)
    }

    fun invalidate() {
        armedGeneration = null
        removeCallbacks(retry)
    }

    override fun close() {
        closed = true
        invalidate()
    }

    private companion object {
        const val RETRY_MILLIS = 250L
    }
}
