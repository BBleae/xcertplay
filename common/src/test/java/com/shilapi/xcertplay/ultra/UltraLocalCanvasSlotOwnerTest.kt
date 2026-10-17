package com.shilapi.xcertplay.ultra

import android.graphics.Canvas
import android.view.Surface
import com.shilapi.xcertplay.ultra.egl.UltraEglIngressKey
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneComponentAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneZoneAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraLocalCanvasSlotOwnerTest {
    private val size = PixelSize(320, 180)
    private val slot = CarPlayThemeSceneSlotAddress(
        CarPlayThemeSceneComponentAddress(
            CarPlayThemeSceneZoneAddress(CarPlayThemeSceneLayoutAddress(0, "main"), 0, "zone"),
            0, "component",
        ),
        0, "position",
    )

    @Test
    fun postReceiptExistsOnlyAfterSuccessfulExactSurfacePostAndExpiresOnReselection() {
        val events = ArrayList<String>()
        val drawPort = object : UltraLocalCanvasDrawPort {
            override fun draw(
                surface: Surface?, sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
            ) = error("Fenced post required")

            override fun drawWithPostFence(
                surface: Surface?, sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
                finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
            ) {
                finalPost({ events += "clear" }, { events += "post" })
            }
        }
        val owner = owner(Port(), drawPort)
        try {
            val handle = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val receipt = checkNotNull(owner.drawWithPostReceipt(handle) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
            assertEquals(listOf("post"), events)
            assertSame(handle, receipt.handle)
            assertEquals(1L, receipt.postSequence)
            assertTrue(owner.acceptsPostReceipt(receipt))
            // A second successful post can leave an older callback in flight. The old receipt
            // becomes unusable, and the reused ingress cannot mint a new first-post receipt.
            assertNull(owner.drawWithPostReceipt(handle) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
            assertFalse(owner.acceptsPostReceipt(receipt))
            owner.ensure(slot, UltraContentId("canvas-b"), size) { true }
            assertSame(handle, owner.ensure(slot, UltraContentId("canvas-a"), size) { true })
            assertNull(owner.drawWithPostReceipt(handle) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
            assertFalse(owner.acceptsPostReceipt(receipt))
        } finally {
            owner.close()
        }
    }

    @Test
    fun omittedOrFailedFinalPostCannotProduceAReceipt() {
        val noPost = owner(Port())
        try {
            val handle = noPost.ensure(slot, UltraContentId("canvas-a"), size) { true }
            assertNull(noPost.drawWithPostReceipt(handle) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
        } finally {
            noPost.close()
        }
        val failedPost = owner(Port(), object : UltraLocalCanvasDrawPort {
            override fun draw(
                surface: Surface?, sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
            ) = error("Fenced post required")

            override fun drawWithPostFence(
                surface: Surface?, sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
                finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
            ) {
                finalPost({}, { throw IllegalStateException("synthetic post failure") })
            }
        })
        try {
            val handle = failedPost.ensure(slot, UltraContentId("canvas-b"), size) { true }
            assertThrows(ExecutionException::class.java) {
                failedPost.drawWithPostReceipt(handle) { _, _ -> }.get(1, TimeUnit.SECONDS)
            }
        } finally {
            failedPost.close()
        }
    }

    @Test
    fun carryLeaseFreezesOnlyExactProvenHandleAndReleasesWithoutLeaking() {
        val drawPort = object : UltraLocalCanvasDrawPort {
            override fun draw(surface: Surface?, sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit) = error("Fenced post required")

            override fun drawWithPostFence(surface: Surface?, sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
                finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
            ) = finalPost({}, {})
        }
        val owner = owner(Port(), drawPort)
        try {
            val handle = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val first = checkNotNull(owner.drawWithPostReceipt(handle) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
            val cluster = owner.ensure(slot, UltraContentId("canvas-b"), size) { true }
            val carry = checkNotNull(owner.acquireCarryLease(handle, first))
            assertTrue(carry.isCurrent())
            assertNull(owner.acquireCarryLease(handle, first))
            checkNotNull(owner.drawWithPostReceipt(cluster) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
            assertTrue(carry.isCurrent())
            assertThrows(ExecutionException::class.java) {
                owner.drawWithPostReceipt(handle) { _, _ -> }.get(1, TimeUnit.SECONDS)
            }
            owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            assertFalse(carry.isCurrent())
            carry.close()
            carry.close()
            assertNull(owner.drawWithPostReceipt(handle) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
            assertNull(owner.acquireCarryLease(handle, first))
        } finally {
            owner.close()
        }
    }

    @Test
    fun opensOnlyAfterAdmissionAndReusesExactSlotContentAndPixelSize() {
        val port = Port()
        val owner = owner(port)
        try {
            assertThrows(IllegalStateException::class.java) {
                owner.ensure(slot, UltraContentId("canvas-a"), size) { false }
            }
            assertEquals(0, port.openCount)
            val first = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            assertSame(first, owner.ensure(slot, UltraContentId("canvas-a"), size) { true })
            assertEquals(UltraEglIngressKey.Local(UltraContentId("canvas-a")), first.key)
            assertEquals(1, first.generation)
            assertEquals(1, port.openCount)
            assertThrows(IllegalArgumentException::class.java) {
                owner.ensure(slot, UltraContentId("canvas-a"), PixelSize(640, 360)) { true }
            }
        } finally {
            owner.close()
        }
        assertEquals(1, port.retireCount)
    }

    @Test
    fun revokedDuringIngressOpenRetiresBorrowedLeaseWithoutPublishingAHandle() {
        val port = Port()
        val owner = owner(port)
        var admissions = 0
        try {
            assertThrows(IllegalStateException::class.java) {
                owner.ensure(slot, UltraContentId("canvas-a"), size) {
                    ++admissions == 1
                }
            }
            assertEquals(2, admissions)
            assertEquals(1, port.openCount)
            assertEquals(1, port.retireCount)
        } finally {
            owner.close()
        }
    }

    @Test
    fun revokeDrainsCurrentDrawAndRejectsQueuedDrawBeforeRetirement() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var drawCount = 0
        val port = Port()
        val owner = owner(port, UltraLocalCanvasDrawPort { _, _, _ ->
            drawCount++
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
        })
        val handle = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
        val first = owner.draw(handle) { _, _ -> }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val queued = owner.draw(handle) { _, _ -> }
        val revoker = Thread { owner.revoke() }.apply { start() }
        try {
            var waits = 0
            while (!owner.isRevoked && waits++ < 1_000) Thread.sleep(1)
            assertTrue(owner.isRevoked)
            // Revocation cannot retire the borrowed Surface while the first Canvas is locked.
            assertEquals(0, port.retireCount)
            release.countDown()
            revoker.join(2_000)
            assertFalse(revoker.isAlive)
            assertThrows(ExecutionException::class.java) {
                first.get(1, TimeUnit.SECONDS)
            }
            assertThrows(ExecutionException::class.java) {
                queued.get(1, TimeUnit.SECONDS)
            }
            assertEquals(1, drawCount)
            assertThrows(ExecutionException::class.java) {
                owner.draw(handle) { _, _ -> }.get(1, TimeUnit.SECONDS)
            }
        } finally {
            release.countDown()
            revoker.join(2_000)
            owner.close()
        }
        assertEquals(1, port.retireCount)
    }

    @Test
    fun slowPainterBurstHasBoundedQueueAndCannotStarveRetirement() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var drawCount = 0
        val port = Port()
        val owner = owner(port, UltraLocalCanvasDrawPort { _, _, _ ->
            drawCount++
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        })
        try {
            val handle = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val accepted = ArrayList<java.util.concurrent.CompletableFuture<Unit>>()
            accepted += owner.draw(handle) { _, _ -> }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            repeat(UltraLocalCanvasSlotOwner.MAXIMUM_PENDING_DRAW_TASKS - 1) {
                accepted += owner.draw(handle) { _, _ -> }
            }
            val retirement = owner.retireExceptAfterPresented(setOf(handle),
                completedTicket(6, UltraEglRenderDisposition.PRESENTED)) { true }
            repeat(64) {
                val rejected = owner.draw(handle) { _, _ -> }
                val failure = assertThrows(ExecutionException::class.java) {
                    rejected.get(1, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is RejectedExecutionException)
            }
            val rejectedReceipt = owner.drawWithPostReceipt(handle) { _, _ -> }
            val receiptFailure = assertThrows(ExecutionException::class.java) {
                rejectedReceipt.get(1, TimeUnit.SECONDS)
            }
            assertTrue(receiptFailure.cause is RejectedExecutionException)
            release.countDown()
            accepted.forEach { it.get(1, TimeUnit.SECONDS) }
            assertTrue(retirement.get(1, TimeUnit.SECONDS))
            assertEquals(UltraLocalCanvasSlotOwner.MAXIMUM_PENDING_DRAW_TASKS, drawCount)
        } finally {
            release.countDown()
            owner.close()
        }
        assertEquals(1, port.retireCount)
    }

    @Test
    fun queuedAFrameCannotSurviveABAReselectionOfTheSameHandle() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var enteredDrawPort = 0
        val port = Port()
        val owner = owner(port, UltraLocalCanvasDrawPort { _, _, _ ->
            enteredDrawPort++
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        })
        try {
            val a = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val inFlight = owner.draw(a) { _, _ -> }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val staleQueuedA = owner.draw(a) { _, _ -> }
            owner.ensure(slot, UltraContentId("canvas-b"), size) { true }
            assertSame(a, owner.ensure(slot, UltraContentId("canvas-a"), size) { true })
            release.countDown()
            assertThrows(ExecutionException::class.java) {
                inFlight.get(1, TimeUnit.SECONDS)
            }
            val rejected = assertThrows(ExecutionException::class.java) {
                staleQueuedA.get(1, TimeUnit.SECONDS)
            }
            assertTrue(rejected.cause is IllegalStateException)
            assertEquals(1, enteredDrawPort)
        } finally {
            release.countDown()
            owner.close()
        }
    }

    @Test
    fun inFlightAFrameIsClearedUnderSelectionFenceBeforeItsPost() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val postEvents = ArrayList<String>()
        val drawPort = object : UltraLocalCanvasDrawPort {
            override fun draw(
                surface: Surface?,
                sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
            ) = error("Product must use the fenced Canvas post")

            override fun drawWithPostFence(
                surface: Surface?,
                sizePixels: PixelSize,
                painter: (Canvas, PixelSize) -> Unit,
                finalPost: (clear: () -> Unit, post: () -> Unit) -> Unit,
            ) {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                finalPost(
                    { postEvents += "clear" },
                    { postEvents += "post" },
                )
            }
        }
        val owner = owner(Port(), drawPort)
        try {
            val a = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val inFlight = owner.draw(a) { _, _ -> }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            owner.ensure(slot, UltraContentId("canvas-b"), size) { true }
            assertSame(a, owner.ensure(slot, UltraContentId("canvas-a"), size) { true })
            release.countDown()
            assertThrows(ExecutionException::class.java) {
                inFlight.get(1, TimeUnit.SECONDS)
            }
            assertEquals(listOf("clear", "post"), postEvents)
            assertNull(owner.drawWithPostReceipt(a) { _, _ -> }
                .get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            owner.close()
        }
    }

    @Test
    fun priorLeaseRetiresOnlyAfterExactReplacementPresentation() {
        val port = Port()
        val owner = owner(port)
        try {
            val old = owner.ensure(slot, UltraContentId("canvas-old"), size) { true }
            val replacement = owner.ensure(slot, UltraContentId("canvas-new"), size) { true }
            val coalesced = completedTicket(1, UltraEglRenderDisposition.COALESCED)
            assertFalse(owner.retireExceptAfterPresented(setOf(replacement), coalesced) { true }
                .get(1, TimeUnit.SECONDS))
            assertEquals(0, port.retireCount)
            val presentedButRevoked = completedTicket(2, UltraEglRenderDisposition.PRESENTED)
            assertFalse(owner.retireExceptAfterPresented(setOf(replacement),
                presentedButRevoked) { false }.get(1, TimeUnit.SECONDS))
            assertEquals(0, port.retireCount)
            val presented = completedTicket(3, UltraEglRenderDisposition.PRESENTED)
            assertTrue(owner.retireExceptAfterPresented(setOf(replacement), presented) { true }
                .get(1, TimeUnit.SECONDS))
            assertEquals(1, port.retireCount)
            assertThrows(ExecutionException::class.java) {
                owner.draw(old) { _, _ -> }.get(1, TimeUnit.SECONDS)
            }
            val reopened = owner.ensure(slot, UltraContentId("canvas-old"), size) { true }
            assertNotSame(old, reopened)
            assertEquals(3, port.openCount)
        } finally {
            owner.close()
        }
        assertEquals(3, port.retireCount)
    }

    @Test
    fun reselectingSameAHandleWhileBPresentationGuardRunsPreventsStaleRetirement() {
        val port = Port()
        val owner = owner(port)
        val guardEntered = CountDownLatch(1)
        val finishGuard = CountDownLatch(1)
        try {
            val a = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val b = owner.ensure(slot, UltraContentId("canvas-b"), size) { true }
            val ticket = completedTicket(4, UltraEglRenderDisposition.PRESENTED)
            val retirement = owner.retireExceptAfterPresented(setOf(b), ticket) {
                guardEntered.countDown()
                assertTrue(finishGuard.await(2, TimeUnit.SECONDS))
                true
            }
            assertTrue(guardEntered.await(1, TimeUnit.SECONDS))
            // Controller's A→B→A selection may reuse the very same A lease. Generation of a
            // newly opened resource is therefore insufficient to detect this handoff.
            assertSame(a, owner.ensure(slot, UltraContentId("canvas-a"), size) { true })
            finishGuard.countDown()
            assertFalse(retirement.get(1, TimeUnit.SECONDS))
            assertEquals(0, port.retireCount)
            owner.draw(a) { _, _ -> }.get(1, TimeUnit.SECONDS)
        } finally {
            finishGuard.countDown()
            owner.close()
        }
        assertEquals(2, port.retireCount)
    }

    @Test
    fun reselectingAfterGuardBeforeRetirementReservationStillKeepsA() {
        val port = Port()
        val guardReturned = CountDownLatch(1)
        val permitReservation = CountDownLatch(1)
        val owner = owner(port, afterGuardBeforeReservation = {
            guardReturned.countDown()
            assertTrue(permitReservation.await(2, TimeUnit.SECONDS))
        })
        try {
            val a = owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
            val b = owner.ensure(slot, UltraContentId("canvas-b"), size) { true }
            val retirement = owner.retireExceptAfterPresented(setOf(b),
                completedTicket(5, UltraEglRenderDisposition.PRESENTED)) { true }
            assertTrue(guardReturned.await(1, TimeUnit.SECONDS))
            assertSame(a, owner.ensure(slot, UltraContentId("canvas-a"), size) { true })
            permitReservation.countDown()
            assertFalse(retirement.get(1, TimeUnit.SECONDS))
            assertEquals(0, port.retireCount)
        } finally {
            permitReservation.countDown()
            owner.close()
        }
        assertEquals(2, port.retireCount)
    }

    @Test
    fun uncertainRetirementRemainsOwnedAndChildCloseCanRetry() {
        val port = Port().apply { retireFailures = 1 }
        val owner = owner(port)
        owner.ensure(slot, UltraContentId("canvas-a"), size) { true }
        assertThrows(IllegalStateException::class.java) { owner.close() }
        assertEquals(1, port.retireCount)
        owner.close()
        assertEquals(2, port.retireCount)
    }

    private fun owner(
        port: Port,
        drawPort: UltraLocalCanvasDrawPort = UltraLocalCanvasDrawPort { _, _, _ -> },
        afterGuardBeforeReservation: () -> Unit = {},
    ) = UltraLocalCanvasSlotOwner(port, drawPort, Executors.newSingleThreadExecutor(),
        afterGuardBeforeReservation)

    private fun completedTicket(
        revision: Long,
        disposition: UltraEglRenderDisposition,
    ): UltraEglRenderTicket = UltraEglRenderTicket(revision).also {
        it.complete(UltraEglRenderResult(revision, disposition))
    }

    private class Port : UltraLocalCanvasIngressPort {
        var openCount = 0
        var retireCount = 0
        var retireFailures = 0

        override fun open(
            contentId: UltraContentId,
            sizePixels: PixelSize,
        ): UltraLocalCanvasIngress {
            openCount++
            return UltraLocalCanvasIngress(UltraEglIngressKey.Local(contentId), null, null) {
                retireCount++
                if (retireFailures > 0) {
                    retireFailures--
                    throw IllegalStateException("Native retirement proof is uncertain")
                }
            }
        }
    }
}
