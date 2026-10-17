package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import java.io.Closeable
import java.util.concurrent.CompletableFuture

/**
 * App-facing painting hook for a Local Canvas slot in the currently selected signed UI Sync
 * theme. Registering a painter does not enable UI Sync, create a slot, or open a Surface. The
 * callback runs asynchronously on the renderer child's Canvas producer thread; it should draw a
 * complete frame in physical pixels and must not retain the supplied [Canvas].
 *
 * The Debug signed bench is currently the only product composition that attaches a renderer
 * child. Release builds keep this API inert until a separately authorized product composition
 * exists. Neither this hook nor its completion future is a CarPlay protocol ACK or proof that
 * pixels reached a display.
 */
object CarPlayUltraCustomCanvas {
    private val registry = UltraCustomCanvasRegistry()

    /** Replaces the painter for [contentId]; closing an older registration cannot remove it. */
    @JvmStatic
    fun registerPainter(
        contentId: String,
        painter: (Canvas, PixelSize) -> Unit,
    ): Closeable = registry.registerPainter(contentId, painter)

    /** A Debug fallback must never replace an app's registered custom painter. */
    internal fun registerPainterIfAbsent(
        contentId: String,
        painter: (Canvas, PixelSize) -> Unit,
    ): Closeable? = registry.registerPainterIfAbsent(contentId, painter)

    /** Queues one frame only when [contentId] names the active child's signed local slot. */
    @JvmStatic
    fun requestDraw(contentId: String): CompletableFuture<Unit> = registry.requestDraw(contentId)

    internal fun attach(child: SignedLocalCanvasChild): Closeable = registry.attach(child)

    internal fun painterFor(
        child: SignedLocalCanvasChild,
        contentId: UltraContentId,
    ): ((Canvas, PixelSize) -> Unit)? = registry.painterFor(child, contentId)
}

/** Only the exact, sealed renderer child may implement this port; no Surface escapes it. */
internal fun interface SignedLocalCanvasChild {
    fun drawSignedLocalCanvas(
        contentId: UltraContentId,
        painter: (Canvas, PixelSize) -> Unit,
    ): CompletableFuture<Unit>
}

/** Separate instance for deterministic lifecycle tests; product uses the singleton above. */
internal class UltraCustomCanvasRegistry {
    private class PainterRegistration(
        val painter: (Canvas, PixelSize) -> Unit,
    )

    private class ChildAttachment(
        val child: SignedLocalCanvasChild,
        val generation: Long,
    )

    private val lock = Any()
    private val painters = LinkedHashMap<UltraContentId, PainterRegistration>()
    private var attachment: ChildAttachment? = null
    private var nextGeneration = 0L

    fun registerPainter(
        contentId: String,
        painter: (Canvas, PixelSize) -> Unit,
    ): Closeable {
        val id = UltraContentId(contentId)
        val registration = PainterRegistration(painter)
        synchronized(lock) {
            require(painters.containsKey(id) || painters.size < MAXIMUM_PAINTERS) {
                "Custom Canvas painter registry is full"
            }
            painters[id] = registration
        }
        return Closeable {
            synchronized(lock) {
                if (painters[id] === registration) painters.remove(id)
            }
        }
    }

    fun registerPainterIfAbsent(
        contentId: String,
        painter: (Canvas, PixelSize) -> Unit,
    ): Closeable? {
        val id = UltraContentId(contentId)
        val registration = PainterRegistration(painter)
        synchronized(lock) {
            if (painters.containsKey(id)) return null
            require(painters.size < MAXIMUM_PAINTERS) {
                "Custom Canvas painter registry is full"
            }
            painters[id] = registration
        }
        return Closeable {
            synchronized(lock) {
                if (painters[id] === registration) painters.remove(id)
            }
        }
    }

    fun attach(child: SignedLocalCanvasChild): Closeable {
        val owned = synchronized(lock) {
            check(nextGeneration < Long.MAX_VALUE) {
                "Custom Canvas renderer generation exhausted"
            }
            ChildAttachment(child, ++nextGeneration).also { attachment = it }
        }
        return Closeable {
            synchronized(lock) {
                if (attachment === owned) attachment = null
            }
        }
    }

    fun painterFor(
        child: SignedLocalCanvasChild,
        contentId: UltraContentId,
    ): ((Canvas, PixelSize) -> Unit)? = synchronized(lock) {
        if (attachment?.child === child) painters[contentId]?.painter else null
    }

    fun requestDraw(contentId: String): CompletableFuture<Unit> {
        val id = try {
            UltraContentId(contentId)
        } catch (failure: Throwable) {
            return failedFuture(failure)
        }
        val (active, painter) = synchronized(lock) {
            attachment?.child to painters[id]?.painter
        }
        if (active == null || painter == null) return failedFuture(
            IllegalStateException("No active signed Local Canvas painter for $contentId"),
        )
        // The child checks exact current AA05, batch, graph, and selected signed slot. Never
        // call it while holding this registry lock; its painter runs on the producer thread.
        return try {
            active.drawSignedLocalCanvas(id, painter)
        } catch (failure: Throwable) {
            failedFuture(failure)
        }
    }

    private fun failedFuture(failure: Throwable): CompletableFuture<Unit> =
        CompletableFuture<Unit>().also { it.completeExceptionally(failure) }

    internal companion object {
        const val MAXIMUM_PAINTERS = 64
    }
}
