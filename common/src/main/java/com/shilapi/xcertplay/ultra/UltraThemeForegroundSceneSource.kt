package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneUse
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncForegroundSceneUseSource
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * Revocable, exact-render-revision source for the session-owned UI Sync outbox. The validity
 * closure rereads the renderer's graph, AA05, physical batch, and presentation proof; merely
 * holding a source never makes an unpresented scene current.
 */
internal class UltraThemeForegroundSceneSource(
    private val valid: () -> Boolean,
) : UiSyncForegroundSceneUseSource {
    private val lock = Any()
    private val active = IdentityHashMap<Use, Unit>()
    private var revoked = false

    override fun acquireUse(onRevoked: () -> Unit): UiSyncForegroundSceneUse? {
        if (!isCurrent()) return null
        val use = synchronized(lock) {
            if (revoked) null else Use(onRevoked).also { active[it] = Unit }
        } ?: return null
        if (!use.isCurrent()) {
            use.close()
            return null
        }
        return use
    }

    fun isCurrent(): Boolean {
        if (synchronized(lock) { revoked }) return false
        if (runCatching(valid).getOrDefault(false)) return true
        revoke()
        return false
    }

    /** Revocation is immediate; transport-abort callbacks run outside renderer/provisioning locks. */
    fun revoke() {
        val callbacks = synchronized(lock) {
            if (revoked) return
            revoked = true
            active.keys.map { it.onRevoked }.also { active.clear() }
        }
        callbacks.forEach { callback ->
            abortExecutor.execute { runCatching(callback) }
        }
    }

    private inner class Use(val onRevoked: () -> Unit) : UiSyncForegroundSceneUse {
        private var closed = false

        override fun isCurrent(): Boolean = synchronized(lock) { !closed && !revoked } &&
            this@UltraThemeForegroundSceneSource.isCurrent()

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                active.remove(this)
            }
        }
    }

    private companion object {
        /** Separate from protocol writes and EGL proof; callbacks must not wait for either. */
        val abortExecutor = Executors.newFixedThreadPool(2) { command ->
            Thread(command, "xcertplay-ultra-scene-abort").apply { isDaemon = true }
        }
    }
}
