package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayUltraCustomCanvasTest {
    @Test
    fun registrationAloneCannotDrawAndUnsignedIdIsRejectedByExactChild() {
        val registry = UltraCustomCanvasRegistry()
        val painter: (Canvas, PixelSize) -> Unit = { _, _ -> }
        registry.registerPainter("signed", painter).use {
            assertFailed(registry.requestDraw("signed"))
            val child = FakeChild(setOf(UltraContentId("signed")))
            registry.attach(child).use {
                assertFailed(registry.requestDraw("unsigned"))
                assertEquals(0, child.draws.size)
                registry.registerPainter("unsigned", painter).use {
                    assertFailed(registry.requestDraw("unsigned"))
                    assertEquals(1, child.draws.size)
                }
            }
        }
    }

    @Test
    fun replacementPainterWinsAndStaleRegistrationCloseCannotRemoveIt() {
        val registry = UltraCustomCanvasRegistry()
        val child = FakeChild(setOf(UltraContentId("signed")))
        val first: (Canvas, PixelSize) -> Unit = { _, _ -> }
        val second: (Canvas, PixelSize) -> Unit = { _, _ -> }
        val old = registry.registerPainter("signed", first)
        val current = registry.registerPainter("signed", second)
        registry.attach(child).use {
            old.close()
            assertSame(second, registry.painterFor(child, UltraContentId("signed")))
            registry.requestDraw("signed").get(2, TimeUnit.SECONDS)
            assertSame(second, child.draws.single().second)
            current.close()
            assertNull(registry.painterFor(child, UltraContentId("signed")))
            assertFailed(registry.requestDraw("signed"))
        }
    }

    @Test
    fun benchFallbackNeverReplacesCustomPainterAndCannotRemoveItsReplacement() {
        val registry = UltraCustomCanvasRegistry()
        val child = FakeChild(setOf(UltraContentId("canvas.main.bench")))
        val custom: (Canvas, PixelSize) -> Unit = { _, _ -> }
        val fallback: (Canvas, PixelSize) -> Unit = { _, _ -> }
        registry.registerPainter("canvas.main.bench", custom).use { ownedCustom ->
            assertNull(registry.registerPainterIfAbsent("canvas.main.bench", fallback))
            registry.attach(child).use {
                assertSame(custom, registry.painterFor(child, UltraContentId("canvas.main.bench")))
            }
            ownedCustom.close()
        }

        val installed = requireNotNull(
            registry.registerPainterIfAbsent("canvas.main.bench", fallback),
        )
        registry.attach(child).use {
            assertSame(fallback, registry.painterFor(child, UltraContentId("canvas.main.bench")))
            val replacement = registry.registerPainter("canvas.main.bench", custom)
            installed.close()
            assertSame(custom, registry.painterFor(child, UltraContentId("canvas.main.bench")))
            replacement.close()
            assertNull(registry.painterFor(child, UltraContentId("canvas.main.bench")))
        }
    }

    @Test
    fun staleChildDetachCannotClearReconnectedChild() {
        val registry = UltraCustomCanvasRegistry()
        val painter: (Canvas, PixelSize) -> Unit = { _, _ -> }
        registry.registerPainter("signed", painter).use {
            val first = FakeChild(setOf(UltraContentId("signed")))
            val oldAttachment = registry.attach(first)
            registry.requestDraw("signed").get(2, TimeUnit.SECONDS)
            val replacement = FakeChild(setOf(UltraContentId("signed")))
            val newAttachment = registry.attach(replacement)
            assertNull(registry.painterFor(first, UltraContentId("signed")))
            oldAttachment.close()
            registry.requestDraw("signed").get(2, TimeUnit.SECONDS)
            assertEquals(1, first.draws.size)
            assertEquals(1, replacement.draws.size)
            newAttachment.close()
            assertFailed(registry.requestDraw("signed"))
            val reconnected = FakeChild(setOf(UltraContentId("signed")))
            registry.attach(reconnected).use {
                registry.requestDraw("signed").get(2, TimeUnit.SECONDS)
                assertEquals(1, reconnected.draws.size)
            }
        }
    }

    @Test
    fun registrationIsBoundedAndInvalidIdNeverEntersRegistry() {
        val registry = UltraCustomCanvasRegistry()
        val painter: (Canvas, PixelSize) -> Unit = { _, _ -> }
        val registrations = (0 until UltraCustomCanvasRegistry.MAXIMUM_PAINTERS).map {
            registry.registerPainter("content-$it", painter)
        }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                registry.registerPainter("overflow", painter)
            }
            assertThrows(IllegalArgumentException::class.java) {
                registry.registerPainter(" ", painter)
            }
            registrations.first().close()
            registry.registerPainter("new", painter).close()
        } finally {
            registrations.forEach { it.close() }
        }
        assertFailed(registry.requestDraw(""))
    }

    private fun assertFailed(future: CompletableFuture<Unit>) {
        assertThrows(ExecutionException::class.java) {
            future.get(2, TimeUnit.SECONDS)
        }
    }

    private class FakeChild(private val signed: Set<UltraContentId>) : SignedLocalCanvasChild {
        val draws = ArrayList<Pair<UltraContentId, (Canvas, PixelSize) -> Unit>>()

        override fun drawSignedLocalCanvas(
            contentId: UltraContentId,
            painter: (Canvas, PixelSize) -> Unit,
        ): CompletableFuture<Unit> {
            draws += contentId to painter
            return if (contentId in signed) CompletableFuture.completedFuture(Unit)
                else CompletableFuture<Unit>().also {
                    it.completeExceptionally(IllegalStateException("Unsigned local content"))
                }
        }
    }
}
