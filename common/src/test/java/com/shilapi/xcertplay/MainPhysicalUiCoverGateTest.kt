package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.DisplayDestination
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class MainPhysicalUiCoverGateTest {
    private class ValidSurface : Surface(null as SurfaceTexture?) {
        override fun isValid() = true
    }

    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as Unsafe
    private fun surface(): Surface = unsafe.allocateInstance(ValidSurface::class.java) as Surface
    private fun texture(): SurfaceTexture =
        unsafe.allocateInstance(SurfaceTexture::class.java) as SurfaceTexture

    private class ManualWatchdog : ExternalClusterUiCoverWatchdogScheduler {
        private val callbacks = ArrayList<() -> Unit>()
        override fun schedule(recheck: () -> Unit): Closeable {
            callbacks += recheck
            return Closeable { callbacks.remove(recheck) }
        }
        fun tick() = callbacks.toList().forEach { it() }
    }

    @Test fun ordinaryMainStartsUncoveredAndTextureUpdatesCannotRevealOrCreateCover() {
        var applications = 0
        val gate = MainPhysicalUiCoverGate { _, _ -> applications++; true }
        assertFalse(gate.isCovered)
        assertFalse(gate.onTextureUpdated(10L) { true })
        assertTrue(gate.retireIfUsed())
        assertFalse(gate.isCovered)
        assertTrue(applications == 0)
    }

    @Test fun hostOverlayIsHiddenBetweenMainVideoAndGestureAndBindsBeforeLegacyRoute() {
        val source = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt").readText()
        val viewConstruction = source.substringAfter("private fun buildContentView(): View {")
            .substringBefore("val cluster = TextureView(this)")
        assertTrue(viewConstruction.contains("visibility = View.GONE"))
        assertTrue(viewConstruction.contains("isClickable = false"))
        assertTrue(viewConstruction.indexOf("addView(video)") <
            viewConstruction.indexOf("frame.addView(\n            mainCover"))
        assertTrue(viewConstruction.indexOf("frame.addView(\n            mainCover") <
            viewConstruction.indexOf("frame.addView(\n            gestureLayer"))
        val available = source.substringAfter("override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {")
            .substringBefore("override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture")
        assertTrue(available.indexOf("physicalSurfaceRegistry.publish(") <
            available.indexOf("bindMainPhysicalCover(surface, texture, it)"))
        assertTrue(available.indexOf("bindMainPhysicalCover(surface, texture, it)") <
            available.indexOf("attachSurface(surface)"))
    }

    @Test fun mainLooperTimeoutIsNotCoverAckAndRetryDefeatsLateTask() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        val mainThread = AtomicBoolean(false)
        var visibleCover = false
        val owner = MainPhysicalUiCoverOwner(
            postToMain = { queued.add(it); true },
            isMainThread = mainThread::get,
            applyVisibility = { covered -> visibleCover = covered; true },
        )
        val gate = requireNotNull(owner.newGate())
        var firstAck = true
        val worker = Thread { firstAck = gate.claimUltra() }
        worker.start()
        worker.join(1_500L)
        assertFalse(worker.isAlive)
        assertFalse(firstAck)
        assertFalse(gate.hasAppliedCover)
        assertFalse(visibleCover)

        mainThread.set(true)
        assertTrue(gate.cover())
        assertTrue(gate.hasAppliedCover)
        assertTrue(visibleCover)
        while (true) (queued.poll() ?: break).run()
        assertTrue(visibleCover)
        owner.close()
    }

    @Test fun exactFrameUpdateOnlyAndLaterCoverWinsQueuedReveal() {
        val pending = ArrayDeque<() -> Unit>()
        var visibleCover = false
        lateinit var gate: MainPhysicalUiCoverGate
        gate = MainPhysicalUiCoverGate { sequence, covered ->
            pending.addLast {
                if (gate.currentSequence == sequence) visibleCover = covered
            }
            true
        }
        assertTrue(gate.claimUltra())
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        assertTrue(visibleCover)
        assertFalse(gate.onTextureUpdated(42L) { true })
        assertTrue(gate.armUltra(42L) { true })
        assertFalse(gate.onTextureUpdated(41L) { true })
        assertTrue(gate.onTextureUpdated(42L) { true })
        assertTrue(gate.cover(retire = true))
        while (pending.isNotEmpty()) pending.removeLast().invoke()
        assertTrue(visibleCover)
        assertFalse(gate.onTextureUpdated(42L) { true })
    }

    @Test fun failedCoverAckMustRetryBeforeLaterMatchingFrameCanReveal() {
        var allowCover = true
        var covered = false
        val gate = MainPhysicalUiCoverGate { _, state ->
            if (state && !allowCover) false else {
                covered = state
                true
            }
        }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(10L) { true })
        assertTrue(gate.onTextureUpdated(10L) { true })
        assertFalse(covered)
        assertTrue(gate.armUltra(11L) { true })
        allowCover = false
        assertFalse(gate.onTextureUpdated(10L) { true })
        assertFalse(gate.hasAppliedCover)
        assertFalse(gate.onTextureUpdated(11L) { true })
        allowCover = true
        assertTrue(gate.onTextureUpdated(11L) { true })
        assertFalse(covered)
    }

    @Test fun failedRevealRetainsExactPermitForSameFrameRetry() {
        var visibleCover = false
        var failReveal = true
        val gate = MainPhysicalUiCoverGate { _, covered ->
            if (!covered && failReveal) false else {
                visibleCover = covered
                true
            }
        }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(42L) { true })
        assertFalse(gate.onTextureUpdated(42L) { true })
        assertTrue(gate.hasAppliedCover)
        assertTrue(visibleCover)
        failReveal = false
        assertTrue(gate.onTextureUpdated(42L) { true })
        assertFalse(visibleCover)
    }

    @Test fun staleCallbackCannotEraseNewerPermit() {
        var covered = false
        var oldAuthorized = true
        val gate = MainPhysicalUiCoverGate { _, state -> covered = state; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(10L) { oldAuthorized })
        assertTrue(gate.onTextureUpdated(10L) { true })
        var switched = false
        assertFalse(gate.onTextureUpdated(10L) {
            if (!switched) {
                switched = true
                oldAuthorized = false
                assertTrue(gate.armUltra(20L) { true })
            }
            true
        })
        assertTrue(covered)
        assertTrue(gate.onTextureUpdated(20L) { true })
        assertFalse(covered)
    }

    @Test fun coverBeforeNewPermitTimeoutKeepsExactPendingFrameForRetry() {
        val queued = ConcurrentLinkedQueue<Runnable>()
        val mainThread = AtomicBoolean(true)
        var covered = false
        val owner = MainPhysicalUiCoverOwner(
            postToMain = { queued.add(it); true },
            isMainThread = mainThread::get,
            applyVisibility = { value -> covered = value; true },
        )
        val gate = requireNotNull(owner.newGate())
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(10L) { true })
        assertTrue(gate.onTextureUpdated(10L) { true })
        assertFalse(covered)

        mainThread.set(false)
        var armed = true
        val worker = Thread { armed = gate.armUltra(20L) { true } }
        worker.start()
        worker.join(1_500L)
        assertFalse(worker.isAlive)
        assertFalse(armed)
        assertTrue(gate.hasPendingUltraPermit)
        assertFalse(gate.onTextureUpdated(20L) { true })
        mainThread.set(true)
        assertTrue(gate.onTextureUpdated(20L) { true })
        assertFalse(covered)
        while (true) (queued.poll() ?: break).run()
        assertFalse(covered)
        owner.close()
    }

    @Test fun coalescedNewerTimestampReportsOnceAndNeverBorrowsOldPermit() {
        val missed = ArrayList<Pair<Long, Long>>()
        val gate = MainPhysicalUiCoverGate(
            onMissedUltraFrame = { expected, actual -> missed += expected to actual },
            applyCovered = { _, _ -> true },
        )
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(10L) { true })
        assertFalse(gate.onTextureUpdated(11L) { true })
        assertFalse(gate.onTextureUpdated(12L) { true })
        assertTrue(gate.isCovered)
        assertTrue(missed == listOf(10L to 11L))
        assertTrue(gate.armUltra(12L) { true })
        assertTrue(gate.onTextureUpdated(12L) { true })
    }

    @Test fun missedFrameCallbackWaitsForExactCoverAckAndRunsOnce() {
        var allowCover = true
        var covered = false
        val misses = ArrayList<Pair<Long, Long>>()
        val gate = MainPhysicalUiCoverGate { _, state ->
            if (state && !allowCover) false else {
                covered = state
                true
            }
        }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltraWithStatus(
            timestampNanos = 10L,
            authorization = { MainPhysicalUiPermitStatus.CURRENT },
            onMissedFrame = { observed ->
                assertTrue("A miss must be covered before requesting reproof", covered)
                assertTrue(gate.hasAppliedCover)
                misses += 10L to observed
            },
        ))
        assertTrue(gate.onTextureUpdated(10L) { true })
        assertFalse(covered)

        allowCover = false
        assertFalse(gate.onTextureUpdated(11L) { true })
        assertFalse(gate.hasAppliedCover)
        assertTrue(misses.isEmpty())

        allowCover = true
        assertFalse(gate.onTextureUpdated(11L) { true })
        assertEquals(listOf(10L to 11L), misses)
        assertFalse(gate.onTextureUpdated(12L) { true })
        assertEquals(listOf(10L to 11L), misses)
    }

    @Test fun missedFrameCallbackBelongsToItsExactPermit() {
        val first = ArrayList<Pair<Long, Long>>()
        val second = ArrayList<Pair<Long, Long>>()
        val gate = MainPhysicalUiCoverGate { _, _ -> true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltraWithStatus(
            20L, authorization = { MainPhysicalUiPermitStatus.CURRENT },
            onMissedFrame = { observed -> first += 20L to observed },
        ))
        assertTrue(gate.onTextureUpdated(20L) { true })
        assertFalse(gate.onTextureUpdated(21L) { true })
        assertEquals(listOf(20L to 21L), first)
        assertTrue(second.isEmpty())

        assertTrue(gate.armUltraWithStatus(
            22L, authorization = { MainPhysicalUiPermitStatus.CURRENT },
            onMissedFrame = { observed -> second += 22L to observed },
        ))
        assertFalse(gate.onTextureUpdated(23L) { true })
        assertEquals(listOf(20L to 21L), first)
        assertEquals(listOf(22L to 23L), second)
    }

    @Test fun delayedExactFrameCannotRevealAfterNewerFrameWasObserved() {
        var allowCover = true
        var covered = false
        val misses = ArrayList<Long>()
        val gate = MainPhysicalUiCoverGate { _, state ->
            if (state && !allowCover) false else {
                covered = state
                true
            }
        }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltraWithStatus(
            10L,
            onMissedFrame = { misses += it },
            authorization = { MainPhysicalUiPermitStatus.CURRENT },
        ))
        assertTrue(gate.onTextureUpdated(10L) { true })
        assertFalse(covered)

        allowCover = false
        assertFalse(gate.onTextureUpdated(11L) { true })
        assertFalse(gate.hasAppliedCover)
        assertTrue(misses.isEmpty())

        allowCover = true
        assertFalse("A late exact frame cannot reuse the missed permit",
            gate.onTextureUpdated(10L) { true })
        assertTrue(covered)
        assertTrue(gate.hasAppliedCover)
        assertEquals(listOf(11L), misses)
        assertFalse(gate.onTextureUpdated(10L) { true })
        assertTrue(covered)

        assertTrue(gate.armUltraWithStatus(12L) { MainPhysicalUiPermitStatus.CURRENT })
        assertTrue(gate.onTextureUpdated(12L) { true })
        assertFalse(covered)
    }

    @Test fun freshOrdinaryGateWaitsForRouteAndFirstUpdate() {
        var covered = false
        val owner = MainPhysicalUiCoverOwner(
            postToMain = { it.run(); true },
            isMainThread = { true },
            applyVisibility = { value -> covered = value; true },
        )
        val old = requireNotNull(owner.newGate())
        assertTrue(old.claimUltra())
        assertTrue(old.retireIfUsed())
        val fresh = requireNotNull(owner.newGate())
        assertTrue(covered)
        var routeAttached = false
        assertFalse(fresh.armOrdinaryRecovery { routeAttached })
        assertFalse(old.onTextureUpdated(1L) { true })
        routeAttached = true
        assertTrue(fresh.armOrdinaryRecovery { routeAttached })
        assertTrue(covered)
        assertTrue(fresh.onTextureUpdated(2L) { routeAttached })
        assertFalse(covered)
        owner.close()
    }

    @Test fun browserOnlyNoPreviewCanHideCoverOnlyWhileFreshVideoIsHidden() {
        var covered = false
        val gate = MainPhysicalUiCoverGate { _, value -> covered = value; true }
        assertTrue(gate.cover())
        var hidden = false
        assertTrue(gate.armOrdinaryRecovery { true })
        assertFalse(gate.revealOrdinaryNoPreview({ true }, { hidden }))
        assertTrue(covered)
        hidden = true
        assertTrue(gate.revealOrdinaryNoPreview({ true }, { hidden }))
        assertFalse(covered)
    }

    @Test fun browserPreviewRequiresFreshTextureUpdateAfterItsRouteAttaches() {
        var covered = false
        var routeAttached = false
        val gate = MainPhysicalUiCoverGate { _, value -> covered = value; true }
        assertTrue(gate.cover())
        assertFalse(gate.armOrdinaryRecovery { routeAttached })
        assertFalse(gate.onTextureUpdated(1L) { true })
        assertTrue(covered)
        routeAttached = true
        assertTrue(gate.armOrdinaryRecovery { routeAttached })
        assertTrue(covered)
        assertTrue(gate.onTextureUpdated(2L) { routeAttached })
        assertFalse(covered)
    }

    @Test fun oldMainBorrowReceiptFreezesAdmissionAndWaitsForExactEglRetirement() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
        )
        val oldSurface = surface()
        val oldTexture = texture()
        val version = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            oldSurface, oldTexture, 800, 480,
        ))
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
        val receipt = requireNotNull(registry.captureExactMainBorrowRetirement(version, oldSurface))
        assertFalse(receipt.isRetired)
        assertNull(registry.acquirePhysicalTarget(version))
        borrowed.retireAfterEglProof()
        assertTrue(receipt.isRetired)
        assertTrue(receipt.isFor(version, oldSurface))
        assertFalse(receipt.isFor(version, surface()))
    }

    @Test fun oldMainBorrowReceiptProvesAbsenceOnlyAfterAtomicFreeze() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
        )
        val oldSurface = surface()
        val oldTexture = texture()
        val version = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            oldSurface, oldTexture, 800, 480,
        ))
        val receipt = requireNotNull(registry.captureExactMainBorrowRetirement(version, oldSurface))
        assertTrue(receipt.isRetired)
        assertNull(registry.acquirePhysicalTarget(version))
    }

    @Test fun ordinaryRecoveryNeedsExactCompletedTicketAndClearBarrier() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
        )
        val oldSurface = surface()
        val oldTexture = texture()
        val version = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            oldSurface, oldTexture, 800, 480,
        ))
        val borrower = requireNotNull(registry.acquirePhysicalTarget(version))
        val oldBorrow = requireNotNull(registry.captureExactMainBorrowRetirement(version, oldSurface))
        val oldController = Any()
        val oldSink = Any()
        val barrier = HostTeardownCoordinator()
        val ticket = barrier.submitTicket(
            HostTeardownActions(
                controllerIdentity = oldController,
                sinkIdentity = oldSink,
                requestControllerClose = {},
                awaitControllerClosed = { true },
                closeMediaSink = {},
            ),
            oldBorrow,
        )
        assertNull(provenMainOrdinaryRetirement(
            ticket, oldController, oldSink, oldBorrow, barrier.state(),
        ))
        assertTrue(drainHostTeardowns(barrier) is HostTeardownDrainResult.Failed)
        assertNull(provenMainOrdinaryRetirement(
            ticket, oldController, oldSink, oldBorrow, HostTeardownBarrierState.CLEAR,
        ))
        borrower.retireAfterEglProof()
        assertTrue(drainHostTeardowns(barrier) === HostTeardownDrainResult.Clear)
        assertNull(provenMainOrdinaryRetirement(
            ticket, oldController, Any(), oldBorrow, barrier.state(),
        ))
        assertTrue(provenMainOrdinaryRetirement(
            ticket, oldController, oldSink, oldBorrow, barrier.state(),
        ) != null)
    }

    @Test fun activityWithdrawsOldIdentityBeforeRemovalAndKeepsPhysicalGeometry() {
        val source = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt").readText()
        val rotation = source.substringAfter("private fun completePendingMainOrdinaryRecovery(): Boolean {")
            .substringBefore("private fun retireHostConstructionFailure(")
        assertTrue(rotation.indexOf("releaseDisplaySurfaceAfterRetirement(") <
            rotation.indexOf("removeView(old)"))
        assertTrue(rotation.indexOf("removeView(old)") <
            rotation.lastIndexOf("isTextureViewDetached(pending.oldTexture)"))
        assertTrue(rotation.lastIndexOf("isTextureViewDetached(pending.oldTexture)") <
            rotation.indexOf("val fresh = TextureView(this)"))
        assertTrue(rotation.contains("pending.oldView.layoutParams ?: FrameLayout.LayoutParams("))
        assertFalse(rotation.contains("widthPhysicalMm"))
        assertFalse(rotation.contains("scheduleDisplaySize"))
        val callbacks = source.substringAfter("private val textureListener = object : TextureView.SurfaceTextureListener {")
            .substringBefore("private fun bindMainPhysicalCover(")
        assertTrue(callbacks.contains("videoView?.surfaceTexture !== texture"))
        assertTrue(callbacks.contains("currentSurfaceTexture !== texture"))
        val routeProof = source.substringAfter("private fun exactMainOrdinaryRouteCurrent(")
            .substringBefore("private fun tryArmMainOrdinaryRecovery()")
        assertTrue(routeProof.contains("recovery.generation == restartGeneration"))
        assertTrue(routeProof.contains("controller === recovery.attachedController"))
        assertTrue(routeProof.contains("sink === recovery.attachedSink"))
    }

    @Test fun currentPermitExpiryCoversVisibleMainWithoutAnotherTextureUpdate() {
        var covered = false
        var authorized = true
        val gate = MainPhysicalUiCoverGate { _, state -> covered = state; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltra(20L) { authorized })
        assertTrue(gate.onTextureUpdated(20L) { true })
        assertFalse(covered)
        assertTrue(gate.recheckCurrent { true })
        authorized = false
        assertFalse(gate.recheckCurrent { true })
        assertTrue(covered)
    }

    @Test fun transientBusyAuthorizationCoversButKeepsSameExactFramePermit() {
        var covered = false
        var status = MainPhysicalUiPermitStatus.CURRENT
        val gate = MainPhysicalUiCoverGate { _, value -> covered = value; true }
        assertTrue(gate.claimUltra())
        assertTrue(gate.armUltraWithStatus(30L) { status })
        assertTrue(gate.onTextureUpdated(30L) { true })
        assertFalse(covered)
        status = MainPhysicalUiPermitStatus.BUSY
        assertFalse(gate.recheckCurrent { true })
        assertTrue(covered)
        assertFalse(gate.onTextureUpdated(30L) { true })
        status = MainPhysicalUiPermitStatus.CURRENT
        assertTrue(gate.recheckCurrent { true })
        assertTrue(gate.hasCoveredExactUltraPermitFor(30L))
        // The registration watchdog replays its last exact observed texture timestamp here.
        assertTrue(gate.onTextureUpdated(30L) { true })
        assertFalse(covered)
    }

    @Test fun busyThenRevokedDiscardsExactPermitWithoutRevealing() {
        var status = MainPhysicalUiPermitStatus.BUSY
        val gate = MainPhysicalUiCoverGate { _, _ -> true }
        assertTrue(gate.claimUltra())
        assertFalse(gate.armUltraWithStatus(30L) { status })
        assertTrue(gate.hasPendingUltraPermit)
        assertFalse(gate.onTextureUpdated(30L) { true })
        status = MainPhysicalUiPermitStatus.REVOKED
        assertFalse(gate.recheckCurrent { true })
        assertFalse(gate.hasPendingUltraPermit)
        status = MainPhysicalUiPermitStatus.CURRENT
        assertFalse(gate.onTextureUpdated(30L) { true })
        assertTrue(gate.isCovered)
        assertTrue(gate.armUltraWithStatus(31L) { MainPhysicalUiPermitStatus.CURRENT })
        assertTrue(gate.onTextureUpdated(31L) { true })
    }

    @Test fun watchdogReplaysOnlyCoveredPermitMatchingObservedTextureTimestamp() {
        val source = File("src/main/java/com/shilapi/xcertplay/HostPhysicalDisplaySurfaceRegistry.kt")
            .readText()
        val mainRegistration = source.substringAfter("internal inner class MainPhysicalUiCoverRegistration")
            .substringBefore("internal inner class ExternalClusterUiCoverRegistration")
        val watchdog = mainRegistration.substringAfter("fun startWatchdog() {")
            .substringBefore("fun onTextureUpdated(texture: SurfaceTexture")
        assertTrue(watchdog.contains("gate.recheckCurrent(::exactCurrent)"))
        assertTrue(watchdog.contains("gate.hasCoveredExactUltraPermitFor(observed)"))
        assertTrue(watchdog.contains("onTextureUpdated(texture, observed)"))
    }

    /** Real HWUI order: a TextureView frame is latched only after its UI-thread callback. */
    private class LatchedMain(
        val latched: AtomicLong = AtomicLong(0L),
        val redraws: AtomicInteger = AtomicInteger(0),
    ) {
        val watchdog = ManualWatchdog()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, coverWatchdogScheduler = watchdog,
            readLatchedTimestamp = { latched.get() },
        )
        var covered = false
        val gate = MainPhysicalUiCoverGate { _, cover -> covered = cover; true }
        lateinit var registration: HostPhysicalDisplaySurfaceRegistry.MainPhysicalUiCoverRegistration
        lateinit var borrowed: Any

        fun bind(surface: Surface, texture: SurfaceTexture) {
            val version = requireNotNull(registry.publish(
                DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
                surface, texture, 800, 480,
            ))
            registration = requireNotNull(registry.bindMainPhysicalUiCover(
                surface, texture, version, Any(), gate,
                requestRedraw = { redraws.incrementAndGet() },
            ))
            assertTrue(registry.coverExactMainPhysicalTarget(version, surface))
            borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
            assertTrue(covered)
        }
    }

    @Test fun hostReadsLatchedImageAfterDrawNotStaleCallbackTimestamp() {
        val source = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt").readText()
        val callback = source.substringAfter("override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {")
            .substringBefore("if (\n")
        assertFalse(callback.contains("onTextureUpdated(texture, texture.timestamp)"))
        assertTrue(callback.contains("mainHandler.post"))
        assertTrue(callback.contains("registration.observeLatchedFrame(texture)"))
    }

    @Test fun permittedFrameLatchedBeforeArmRevealsWithoutAnotherTextureCallback() {
        val texture = texture()
        val main = LatchedMain().apply { bind(surface(), texture) }
        // The callback for the first frame runs before any image is latched.
        assertFalse(main.registration.observeLatchedFrame(texture))
        // The permitted frame (9) is latched by the draw that ran the previous callback, and
        // the producer is static: no further onSurfaceTextureUpdated ever arrives.
        main.latched.set(9L)
        assertTrue(main.gate.armUltra(9L) { true })
        assertTrue(main.covered)
        main.watchdog.tick()
        assertFalse(main.covered)
        assertEquals(0, main.redraws.get())
    }

    @Test fun coalescedNewerLatchedFrameNeverRevealsOlderPermitAndReportsMiss() {
        val texture = texture()
        val main = LatchedMain().apply { bind(surface(), texture) }
        val missed = AtomicLong(0L)
        assertTrue(main.gate.armUltraWithStatus(9L, onMissedFrame = { missed.set(it) }) {
            MainPhysicalUiPermitStatus.CURRENT
        })
        // Frame 9 was coalesced away; the consumer latched newer content 10. A callback-time
        // read could still report 9 (the stale previous image) but is never used to reveal.
        main.latched.set(10L)
        assertFalse(main.registration.observeLatchedFrame(texture))
        main.watchdog.tick()
        assertTrue(main.covered)
        assertEquals(10L, missed.get())
        assertEquals(0, main.redraws.get())
    }

    @Test fun queuedButUnlatchedPermitRequestsBoundedRedrawThenRevealsOnlyItsOwnFrame() {
        val texture = texture()
        val main = LatchedMain().apply { bind(surface(), texture) }
        main.latched.set(8L) // an older frame is still the latched image
        assertTrue(main.gate.armUltra(9L) { true })
        repeat(20) { main.watchdog.tick() }
        assertTrue("An older latched frame must never reveal the newer permit", main.covered)
        assertEquals(5, main.redraws.get())
        main.latched.set(9L) // a redraw latched the permitted frame
        main.watchdog.tick()
        assertFalse(main.covered)
    }

    @Test fun registryBindsExactMainGenerationAndOldBorrowCannotCoverSuccessor() {
        val watchdog = ManualWatchdog()
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, coverWatchdogScheduler = watchdog,
        )
        val oldSurface = surface()
        val oldTexture = texture()
        val oldVersion = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            oldSurface, oldTexture, 800, 480,
        ))
        val activity = Any()
        var oldCovered = false
        val oldGate = MainPhysicalUiCoverGate { _, covered -> oldCovered = covered; true }
        assertNull(registry.bindMainPhysicalUiCover(
            oldSurface, oldTexture, oldVersion.copy(generation = -1L), activity, oldGate,
        ))
        val old = requireNotNull(registry.bindMainPhysicalUiCover(
            oldSurface, oldTexture, oldVersion, activity, oldGate,
        ))
        assertSame(activity, registry.currentMainCoverActivityGeneration(oldVersion, oldSurface))
        assertNull(registry.bindMainPhysicalUiCover(
            oldSurface, oldTexture, oldVersion, Any(), MainPhysicalUiCoverGate { _, _ -> true },
        ))
        assertFalse(old.onTextureUpdated(oldTexture, 5L)) // ordinary callback
        assertFalse(oldCovered)
        assertTrue(registry.coverExactMainPhysicalTarget(oldVersion, oldSurface))
        assertTrue(oldCovered)
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(oldVersion))
        assertTrue(oldGate.armUltra(9L) { true })
        assertFalse(old.onTextureUpdated(texture(), 9L))
        assertTrue(old.onTextureUpdated(oldTexture, 9L))
        assertFalse(oldCovered)

        val nextSurface = surface()
        val nextTexture = texture()
        val nextVersion = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            nextSurface, nextTexture, 800, 480,
        ))
        assertTrue(oldCovered)
        var nextCovered = false
        val next = requireNotNull(registry.bindMainPhysicalUiCover(
            nextSurface, nextTexture, nextVersion, Any(),
            MainPhysicalUiCoverGate { _, covered -> nextCovered = covered; true },
        ))
        assertFalse(old.onTextureUpdated(oldTexture, 9L))
        assertFalse(nextCovered)
        assertNull(registry.currentMainCoverActivityGeneration(oldVersion, oldSurface))
        assertFalse(registry.coverExactMainPhysicalTarget(oldVersion, nextSurface))
        assertTrue(borrowed.coverExactMainUiForRetirement())
        old.close()
        assertTrue(borrowed.coverExactMainUiForRetirement()) // monotonic old ACK
        assertFalse(nextCovered)
        borrowed.retireAfterEglProof()
        next.close()
        watchdog.tick()
    }

    @Test fun failedRetirementCoverRetriesOnlyCapturedMainGate() {
        val registry = HostPhysicalDisplaySurfaceRegistry(
            releaseSurface = {}, releaseTexture = {}, requestRetirement = {},
        )
        val surface = surface()
        val texture = texture()
        val version = requireNotNull(registry.publish(
            DisplayDestination.MAIN, HostDisplaySurfaceKind.PHYSICAL_MAIN,
            surface, texture, 800, 480,
        ))
        var allowCover = true
        var covered = false
        val gate = MainPhysicalUiCoverGate { _, cover ->
            if (cover && !allowCover) false else {
                covered = cover
                true
            }
        }
        val registration = requireNotNull(registry.bindMainPhysicalUiCover(
            surface, texture, version, Any(), gate,
        ))
        assertTrue(registry.coverExactMainPhysicalTarget(version, surface))
        val borrowed = requireNotNull(registry.acquirePhysicalTarget(version))
        assertTrue(gate.armUltra(44L) { true })
        assertTrue(registration.onTextureUpdated(texture, 44L))
        assertFalse(covered)

        allowCover = false
        assertFalse(borrowed.coverExactMainUiForRetirement())
        assertFalse(gate.hasAppliedCover)
        allowCover = true
        assertTrue(borrowed.coverExactMainUiForRetirement())
        assertTrue(covered)
        registration.close()
        assertTrue(borrowed.coverExactMainUiForRetirement())
        borrowed.retireAfterEglProof()
    }
}
