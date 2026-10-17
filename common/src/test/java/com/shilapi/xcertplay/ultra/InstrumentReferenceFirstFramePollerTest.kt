package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.UltraProfileSceneUseAttempt
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPhysicalGenerationStatus
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderDisposition
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderResult
import com.shilapi.xcertplay.ultra.egl.UltraEglRenderTicket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentReferenceFirstFramePollerTest {
    @Test fun asynchronousSourceOpenRetriesBusyAndOnlyRetiresTypedRevocation() {
        assertFalse(instrumentSourceShouldRetire(UiSyncPhysicalGenerationStatus.BUSY,
            UltraProfileSceneUseAttempt.Busy))
        assertFalse(instrumentSourceShouldRetire(UiSyncPhysicalGenerationStatus.CURRENT,
            UltraProfileSceneUseAttempt.Busy))
        assertFalse(instrumentSourceShouldRetire(UiSyncPhysicalGenerationStatus.BUSY,
            UltraProfileSceneUseAttempt.Admitted(Unit)))
        assertTrue(instrumentSourceShouldRetire(UiSyncPhysicalGenerationStatus.REVOKED,
            UltraProfileSceneUseAttempt.Admitted(Unit)))
        assertTrue(instrumentSourceShouldRetire(UiSyncPhysicalGenerationStatus.CURRENT,
            UltraProfileSceneUseAttempt.Revoked))
    }

    @Test fun delayedTicketAndTransientBusyNeedPresentationAndCurrentGeneration() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val ticket = UltraEglRenderTicket(7L)
            val current = AtomicBoolean(false)
            val presented = CountDownLatch(1)
            val rejected = AtomicReference<Throwable?>()
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1)).observe(ticket,
                stillOwned = { true }, revoked = { false }, ready = current::get,
                onPresented = presented::countDown, onRejected = rejected::set)
            worker.schedule({ ticket.complete(UltraEglRenderResult(7L,
                UltraEglRenderDisposition.PRESENTED)) }, 20L, TimeUnit.MILLISECONDS)
            assertFalse(presented.await(35L, TimeUnit.MILLISECONDS))
            current.set(true)
            assertTrue(presented.await(1L, TimeUnit.SECONDS))
            assertNull(rejected.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun revokeDuringPendingTicketRetiresWithoutForegroundOrFailure() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val ticket = UltraEglRenderTicket(8L)
            val revoked = AtomicBoolean(false)
            val presented = AtomicBoolean(false)
            val retired = CountDownLatch(1)
            val cause = AtomicReference<Throwable?>()
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1)).observe(ticket,
                stillOwned = { true }, revoked = revoked::get, ready = { true },
                onPresented = { presented.set(true) },
                onRejected = { cause.set(it); retired.countDown() })
            worker.schedule({ revoked.set(true) }, 15L, TimeUnit.MILLISECONDS)
            assertTrue(retired.await(1L, TimeUnit.SECONDS))
            ticket.complete(UltraEglRenderResult(8L, UltraEglRenderDisposition.PRESENTED))
            assertFalse(presented.get())
            assertNull(cause.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun missingOrFailedSwapIsRejectedWithinBoundedDeadline() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val pending = UltraEglRenderTicket(9L)
            val timedOut = CountDownLatch(1)
            val timeoutCause = AtomicReference<Throwable?>()
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.MILLISECONDS.toNanos(40)).observe(pending,
                stillOwned = { true }, revoked = { false }, ready = { true },
                onPresented = { error("Pending frame cannot present") },
                onRejected = { timeoutCause.set(it); timedOut.countDown() })
            assertTrue(timedOut.await(1L, TimeUnit.SECONDS))
            assertNotNull(timeoutCause.get())

            val failed = UltraEglRenderTicket(10L)
            failed.complete(UltraEglRenderResult(10L, UltraEglRenderDisposition.FAILED,
                IllegalStateException("synthetic swap loss")))
            val rejected = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1)).observe(failed,
                stillOwned = { true }, revoked = { false }, ready = { true },
                onPresented = { error("Failed frame cannot present") },
                onRejected = { failure.set(it); rejected.countDown() })
            assertTrue(rejected.await(1L, TimeUnit.SECONDS))
            assertEquals("synthetic swap loss", failure.get()?.cause?.message)
        } finally { worker.shutdownNow() }
    }

    @Test fun replacedHandleCannotBeRetiredByAnOlderPendingSceneTicket() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val oldTicket = UltraEglRenderTicket(11L)
            val exactOldHandle = AtomicBoolean(true)
            val retiredNewHandle = AtomicBoolean(false)
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1)).observe(oldTicket,
                stillOwned = exactOldHandle::get, revoked = { false }, ready = { true },
                onPresented = { retiredNewHandle.set(true) },
                onRejected = { retiredNewHandle.set(true) })
            exactOldHandle.set(false)
            oldTicket.complete(UltraEglRenderResult(11L, UltraEglRenderDisposition.PRESENTED))
            worker.schedule({ /* give the bounded poll a chance to observe old completion */ },
                20L, TimeUnit.MILLISECONDS).get(1L, TimeUnit.SECONDS)
            assertFalse(retiredNewHandle.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun busyBetweenTwoCurrentChecksDoesNotBecomeRevocation() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val ticket = UltraEglRenderTicket(12L)
            ticket.complete(UltraEglRenderResult(12L, UltraEglRenderDisposition.PRESENTED))
            val probes = AtomicInteger()
            val presented = CountDownLatch(1)
            val rejected = AtomicBoolean(false)
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1)).observe(ticket,
                stillOwned = { true }, revoked = { false },
                ready = { probes.incrementAndGet() != 2 },
                onPresented = presented::countDown,
                onRejected = { rejected.set(true) })
            assertTrue(presented.await(1L, TimeUnit.SECONDS))
            assertFalse(rejected.get())
            assertTrue(probes.get() >= 4)
        } finally { worker.shutdownNow() }
    }

    @Test fun supersededCoalescedTicketCannotPoisonTheNewPresentedScene() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val oldTicket = UltraEglRenderTicket(20L)
            val newTicket = UltraEglRenderTicket(21L)
            val selectedRevision = AtomicLong(20L)
            val accepted = CountDownLatch(1)
            val failure = AtomicBoolean(false)
            val poller = InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1))
            poller.observe(oldTicket,
                stillOwned = { selectedRevision.get() == 20L }, revoked = { false },
                ready = { true }, onPresented = { failure.set(true) },
                onRejected = { failure.set(true) })
            selectedRevision.set(21L)
            poller.observe(newTicket,
                stillOwned = { selectedRevision.get() == 21L }, revoked = { false },
                ready = { true }, onPresented = accepted::countDown,
                onRejected = { failure.set(true) })
            oldTicket.complete(UltraEglRenderResult(20L, UltraEglRenderDisposition.COALESCED))
            newTicket.complete(UltraEglRenderResult(21L, UltraEglRenderDisposition.PRESENTED))
            assertTrue(accepted.await(1L, TimeUnit.SECONDS))
            assertFalse(failure.get())
        } finally { worker.shutdownNow() }
    }

    @Test fun mismatchedCompletionRevisionCannotIssueFirstFrameEvidence() {
        val worker = ScheduledThreadPoolExecutor(1)
        try {
            val ticket = UltraEglRenderTicket(30L)
            ticket.complete(UltraEglRenderResult(31L, UltraEglRenderDisposition.PRESENTED))
            val rejected = CountDownLatch(1)
            InstrumentReferenceFirstFramePoller(worker, 5L,
                TimeUnit.SECONDS.toNanos(1)).observe(ticket,
                stillOwned = { true }, revoked = { false }, ready = { true },
                onPresented = { error("Foreign scene cannot prove first frame") },
                onRejected = { rejected.countDown() })
            assertTrue(rejected.await(1L, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
}
