package com.shilapi.xcertplay.airplay.rcs.uisync

import com.shilapi.xcertplay.airplay.SerializedUiSyncOutbound
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSyncForegroundRequestOutboxTest {
    @Test
    fun requestBeforeReadyWaitsForItsOwnRouterSnapshotAndAckIsNotForegroundProof() {
        val harness = Harness(listOf(MAIN))
        val outbox = boundOutbox(listOf(MAIN))
        val receipt = outbox.requestLayout(MAIN, request("dashboard"))
        assertEquals(UiSyncForegroundRequestState.QUEUED, receipt.state)

        harness.open()
        val attachment = outbox.attach(harness.outbound)
        assertEquals(0, harness.wire.size) // Passive: nothing before the phone Reset.
        assertEquals(UiSyncForegroundRequestState.QUEUED, receipt.state)

        harness.ready(MAIN)
        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, receipt.state)
        val requestCarrier = harness.wire.last()
        assertEquals(CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue,
            (CarPlayUiSyncCodec.decode(requestCarrier).message.payload as
                CarPlayUiSyncCommandEnvelope).type)

        val ack = harness.remote.getValue(MAIN).receive(requestCarrier).single()
        harness.outbound.receive(ack)
        attachment.refresh()
        // ACK retires the protocol packet; no local evidence says the scene reached foreground.
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, receipt.state)

        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun readyBeforeRequestSendsOnlyTheExplicitTypedCommand() {
        val harness = Harness(listOf(MAIN))
        val outbox = boundOutbox(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        attachment.refresh()
        val before = harness.wire.size

        val receipt = outbox.requestLayout(MAIN, request("dashboard"))

        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, receipt.state)
        assertEquals(before + 1, harness.wire.size)
        assertEquals(MAIN, CarPlayUiSyncCodec.decode(harness.wire.last()).displayId)
        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun sentReceiptIdentifiesOnlyItsExactCurrentChannelAndReadyEpoch() {
        val outbox = boundOutbox(listOf(MAIN))
        val first = Harness(listOf(MAIN))
        first.open()
        val firstAttachment = outbox.attach(first.outbound)
        val queued = outbox.requestLayout(MAIN, request("first"))
        assertNull(queued.sentGeneration)
        first.ready(MAIN)
        firstAttachment.refresh()
        val firstGeneration = requireNotNull(queued.sentGeneration)
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, queued.state)
        assertTrue(firstGeneration === outbox.currentReadyGeneration(MAIN))

        firstAttachment.close()
        assertNull(outbox.currentReadyGeneration(MAIN))
        val second = Harness(listOf(MAIN))
        second.open()
        val secondAttachment = outbox.attach(second.outbound)
        second.ready(MAIN)
        secondAttachment.refresh()
        val secondGeneration = requireNotNull(outbox.currentReadyGeneration(MAIN))
        assertFalse(firstGeneration === secondGeneration)
        assertTrue(queued.sentGeneration === firstGeneration)
        assertFalse(second.wire.any { carrier ->
            (CarPlayUiSyncCodec.decode(carrier).message.payload as?
                CarPlayUiSyncCommandEnvelope)?.type ==
                CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue
        })

        val otherOutbox = boundOutbox(listOf(MAIN))
        val other = Harness(listOf(MAIN))
        other.open()
        val otherAttachment = otherOutbox.attach(other.outbound)
        other.ready(MAIN)
        otherAttachment.refresh()
        assertFalse(firstGeneration === otherOutbox.currentReadyGeneration(MAIN))
        otherAttachment.close()
        otherOutbox.close()
        other.close()
        secondAttachment.close()
        outbox.close()
        first.close()
        second.close()
    }

    @Test
    fun sameAttachedRouterReadyResetMintsNewGenerationWithoutReplayingOldReceipt() {
        val outbox = boundOutbox(listOf(MAIN))
        val harness = Harness(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        attachment.refresh()
        val sent = outbox.requestLayout(MAIN, request("before-reset"))
        val beforeReset = requireNotNull(sent.sentGeneration)

        harness.outbound.close()
        attachment.refresh()
        assertNull(outbox.currentReadyGeneration(MAIN))
        assertTrue(harness.outbound.open().outboundCarriers.isEmpty())
        CarPlayUiSyncPhoneRoleSimulator(MAIN).handshake {
            harness.outbound.receive(it).outboundCarriers
        }
        attachment.refresh()

        assertFalse(beforeReset === outbox.currentReadyGeneration(MAIN))
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, sent.state)
        assertTrue(sent.sentGeneration === beforeReset)
        assertEquals(1, harness.wire.count { carrier ->
            (CarPlayUiSyncCodec.decode(carrier).message.payload as?
                CarPlayUiSyncCommandEnvelope)?.type ==
                CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue
        })
        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun busyAtGuardAndEnqueueRetainsExactSceneUntilTimedRetryCanSend() {
        val session = TestSessionAccess()
        session.status.set(UiSyncForegroundUseStatus.BUSY)
        val outbox = boundOutbox(listOf(MAIN), session)
        val scene = TestSceneSource()
        val guard = outbox.guardScene(MAIN, scene)!!
        val receipt = outbox.requestLayout(MAIN, request("busy-then-current"), guard)
        assertEquals(UiSyncForegroundRequestState.QUEUED, receipt.state)
        assertEquals(1, scene.activeUseCount())
        val harness = Harness(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        val before = harness.wire.size
        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.QUEUED, receipt.state)
        assertEquals(1, scene.activeUseCount())
        assertEquals(before, harness.wire.size)

        session.status.set(UiSyncForegroundUseStatus.CURRENT)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (receipt.state == UiSyncForegroundRequestState.QUEUED &&
            System.nanoTime() < deadline
        ) Thread.sleep(10)
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, receipt.state)
        assertEquals(before + 1, harness.wire.size)
        assertEquals(0, scene.activeUseCount())
        attachment.close()
        outbox.close()
        harness.close()
        assertEquals(0, session.activeUses.get())
    }

    @Test
    fun busyAfterReservationRestoresSameQueuedSceneAndDoesNotWrite() {
        val session = TestSessionAccess()
        val outbox = boundOutbox(listOf(MAIN), session)
        val scene = TestSceneSource()
        val guard = outbox.guardScene(MAIN, scene)!!
        val queued = outbox.requestLayout(MAIN, request("reserved-busy"), guard)
        val harness = Harness(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        val before = harness.wire.size
        val probes = AtomicInteger()
        session.statusProbe = {
            if (probes.incrementAndGet() == 2) {
                session.status.set(UiSyncForegroundUseStatus.BUSY)
            }
            session.status.get()
        }

        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.QUEUED, queued.state)
        assertEquals(before, harness.wire.size)
        assertEquals(1, scene.activeUseCount())

        session.statusProbe = null
        session.status.set(UiSyncForegroundUseStatus.CURRENT)
        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, queued.state)
        assertEquals(before + 1, harness.wire.size)
        assertEquals(0, scene.activeUseCount())
        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun busyImmediatelyBeforeWriteKeepsQueuedWatcherAndRevocationCancelsIt() {
        val session = TestSessionAccess()
        val outbox = boundOutbox(listOf(MAIN), session)
        val scene = TestSceneSource()
        val guard = outbox.guardScene(MAIN, scene)!!
        val queued = outbox.requestLayout(MAIN, request("prewrite-busy"), guard)
        val harness = Harness(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        val before = harness.wire.size
        val probes = AtomicInteger()
        session.statusProbe = {
            if (probes.incrementAndGet() == 3) {
                session.status.set(UiSyncForegroundUseStatus.BUSY)
            }
            session.status.get()
        }

        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.QUEUED, queued.state)
        assertEquals(before, harness.wire.size)
        assertEquals(1, scene.activeUseCount())
        scene.revoke()
        assertEquals(UiSyncForegroundRequestState.CANCELLED, queued.state)
        assertEquals(0, scene.activeUseCount())

        session.statusProbe = null
        session.status.set(UiSyncForegroundUseStatus.CURRENT)
        attachment.refresh()
        assertEquals(before, harness.wire.size)
        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun revokedAfterBusyCancelsPendingAndCloseCancelsBusyRetry() {
        val session = TestSessionAccess()
        session.status.set(UiSyncForegroundUseStatus.BUSY)
        val outbox = boundOutbox(listOf(MAIN), session)
        val scene = TestSceneSource()
        val guard = outbox.guardScene(MAIN, scene)!!
        val harness = Harness(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        attachment.refresh()
        val before = harness.wire.size
        val queued = outbox.requestLayout(MAIN, request("revoked"), guard)
        assertEquals(UiSyncForegroundRequestState.QUEUED, queued.state)
        session.status.set(UiSyncForegroundUseStatus.REVOKED)
        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.CANCELLED, queued.state)
        assertEquals(0, scene.activeUseCount())
        assertEquals(before, harness.wire.size)

        attachment.close()
        outbox.close()
        session.status.set(UiSyncForegroundUseStatus.CURRENT)
        Thread.sleep(100)
        assertEquals(before, harness.wire.size)
        harness.close()
    }

    @Test
    fun twoDisplaysAndTwoSessionsCannotBorrowReadinessOrTransport() {
        val first = Harness(listOf(MAIN, CLUSTER))
        val second = Harness(listOf(MAIN, CLUSTER))
        val firstOutbox = boundOutbox(listOf(MAIN, CLUSTER))
        val secondOutbox = boundOutbox(listOf(MAIN, CLUSTER))
        first.open()
        second.open()
        val firstAttachment = firstOutbox.attach(first.outbound)
        val secondAttachment = secondOutbox.attach(second.outbound)
        val mainReceipt = firstOutbox.requestLayout(MAIN, request("main"))
        val clusterReceipt = firstOutbox.requestLayout(CLUSTER, request("cluster"))
        val otherSessionReceipt = secondOutbox.requestLayout(CLUSTER, request("other-session"))
        val secondWireBefore = second.wire.size

        first.ready(CLUSTER)
        firstAttachment.refresh()
        assertEquals(UiSyncForegroundRequestState.QUEUED, mainReceipt.state)
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, clusterReceipt.state)
        assertEquals(UiSyncForegroundRequestState.QUEUED, otherSessionReceipt.state)
        assertEquals(CLUSTER, CarPlayUiSyncCodec.decode(first.wire.last()).displayId)
        assertEquals(secondWireBefore, second.wire.size)
        assertThrows(IllegalArgumentException::class.java) {
            firstOutbox.requestLayout("other-session-display", request("wrong"))
        }

        first.ready(MAIN)
        firstAttachment.refresh()
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, mainReceipt.state)
        assertEquals(MAIN, CarPlayUiSyncCodec.decode(first.wire.last()).displayId)
        assertEquals(secondWireBefore, second.wire.size)
        second.ready(CLUSTER)
        secondAttachment.refresh()
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, otherSessionReceipt.state)
        assertEquals(CLUSTER, CarPlayUiSyncCodec.decode(second.wire.last()).displayId)
        firstAttachment.close()
        secondAttachment.close()
        firstOutbox.close()
        secondOutbox.close()
        first.close()
        second.close()
    }

    @Test
    fun closingChannelCancelsOldGenerationAndReopenDoesNotReplayIt() {
        val registry = registryFor(listOf(MAIN))
        val outbox = boundOutbox(listOf(MAIN))
        val first = Harness(listOf(MAIN), registry)
        first.open()
        val stale = outbox.attach(first.outbound)
        val old = outbox.requestLayout(MAIN, request("old"))
        assertEquals(UiSyncForegroundRequestState.QUEUED, old.state)
        stale.close()
        assertEquals(UiSyncForegroundRequestState.CANCELLED, old.state)

        val next = Harness(listOf(MAIN), registry)
        next.open()
        val current = outbox.attach(next.outbound)
        stale.refresh()
        stale.close()
        next.ready(MAIN)
        current.refresh()
        val before = next.wire.size
        assertEquals(UiSyncForegroundRequestState.CANCELLED, old.state)
        assertFalse(next.wire.any { carrier ->
            val message = CarPlayUiSyncCodec.decode(carrier).message
            (message.payload as? CarPlayUiSyncCommandEnvelope)?.type ==
                CarPlayUiSyncCommandType.REQUEST_LAYOUT.wireValue
        })

        val fresh = outbox.requestLayout(MAIN, request("new"))
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, fresh.state)
        assertEquals(before + 1, next.wire.size)
        current.close()
        outbox.close()
        first.close()
        next.close()
    }

    @Test
    fun pendingQueueIsBoundedAndCloseRejectsFurtherRequests() {
        val outbox = boundOutbox(listOf(MAIN))
        val pending = (1..64).map { outbox.requestLayout(MAIN, request("layout-$it")) }
        assertTrue(pending.all { it.state == UiSyncForegroundRequestState.QUEUED })
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            outbox.requestLayout(MAIN, request("overflow")).state)

        outbox.close()
        assertTrue(pending.all { it.state == UiSyncForegroundRequestState.CANCELLED })
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            outbox.requestLayout(MAIN, request("closed")).state)
    }

    @Test
    fun uncertainWriteIsNeverRetriedBlindly() {
        var failWrites = false
        val harness = Harness(listOf(MAIN), onWrite = {
            if (failWrites) throw IllegalStateException("transport result unknown")
        })
        val outbox = boundOutbox(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        attachment.refresh()
        val before = harness.wire.size

        failWrites = true
        val uncertain = outbox.requestLayout(MAIN, request("uncertain"))
        assertEquals(UiSyncForegroundRequestState.WRITE_UNCERTAIN, uncertain.state)
        failWrites = false
        val next = outbox.requestLayout(MAIN, request("must-not-retry"))
        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.QUEUED, next.state)
        assertEquals(before, harness.wire.size)

        attachment.close()
        assertEquals(UiSyncForegroundRequestState.CANCELLED, next.state)
        outbox.close()
        harness.close()
    }

    @Test
    fun revocationBeforeReadyCancelsQueuedRequestWithoutWriting() {
        val session = TestSessionAccess()
        val outbox = boundOutbox(listOf(MAIN), session)
        val harness = Harness(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        val queued = outbox.requestLayout(MAIN, request("must-not-publish"))
        assertEquals(UiSyncForegroundRequestState.QUEUED, queued.state)

        session.live.set(false)
        harness.ready(MAIN)
        val before = harness.wire.size
        attachment.refresh()

        assertEquals(UiSyncForegroundRequestState.CANCELLED, queued.state)
        assertEquals(before, harness.wire.size)
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            outbox.requestLayout(MAIN, request("after-revocation")).state)
        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun replacedSceneBeforeReadyCancelsOnlyItsOwnQueuedRequest() {
        val harness = Harness(listOf(MAIN, CLUSTER))
        val outbox = boundOutbox(listOf(MAIN, CLUSTER))
        val staleScene = TestSceneSource()
        val liveScene = TestSceneSource()
        val staleGuard = outbox.guardScene(MAIN, staleScene)!!
        val liveGuard = outbox.guardScene(CLUSTER, liveScene)!!
        val stale = outbox.requestLayout(MAIN, request("stale-revision"), staleGuard)
        val live = outbox.requestLayout(CLUSTER, request("live-revision"), liveGuard)
        assertEquals(UiSyncForegroundRequestState.QUEUED, stale.state)
        assertEquals(UiSyncForegroundRequestState.QUEUED, live.state)

        staleScene.revoke()
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        harness.ready(CLUSTER)
        val before = harness.wire.size
        attachment.refresh()

        assertEquals(UiSyncForegroundRequestState.CANCELLED, stale.state)
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, live.state)
        assertEquals(before + 1, harness.wire.size)
        assertEquals(CLUSTER, CarPlayUiSyncCodec.decode(harness.wire.last()).displayId)
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            outbox.requestLayout(MAIN, request("same-stale-scene"), staleGuard).state)
        attachment.close()
        outbox.close()
        harness.close()
    }

    @Test
    fun queuedSceneRevocationCancelsImmediatelyAndReclaimsAllQueueCapacity() {
        val outbox = boundOutbox(listOf(MAIN))
        val oldScene = TestSceneSource()
        val oldGuard = outbox.guardScene(MAIN, oldScene)!!
        val queued = (1..64).map {
            outbox.requestLayout(MAIN, request("old-$it"), oldGuard)
        }
        assertTrue(queued.all { it.state == UiSyncForegroundRequestState.QUEUED })
        assertEquals(64, oldScene.activeUseCount())
        val freshScene = TestSceneSource()
        val freshGuard = outbox.guardScene(MAIN, freshScene)!!
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            outbox.requestLayout(MAIN, request("full"), freshGuard).state)

        // No ClusterControl channel or READY notification exists yet. The scene callback itself
        // must retire every stale row and unsubscribe every queued lease.
        oldScene.revoke()
        assertTrue(queued.all { it.state == UiSyncForegroundRequestState.CANCELLED })
        assertEquals(0, oldScene.activeUseCount())
        val replacement = outbox.requestLayout(MAIN, request("new"), freshGuard)
        assertEquals(UiSyncForegroundRequestState.QUEUED, replacement.state)
        assertEquals(1, freshScene.activeUseCount())
        outbox.close()
        assertEquals(UiSyncForegroundRequestState.CANCELLED, replacement.state)
        assertEquals(0, freshScene.activeUseCount())
    }

    @Test
    fun sceneGuardIsBoundToItsExactPortAndDisplay() {
        val first = boundOutbox(listOf(MAIN, CLUSTER))
        val second = boundOutbox(listOf(MAIN, CLUSTER))
        val mainGuard = first.guardScene(MAIN, TestSceneSource())!!

        assertEquals(UiSyncForegroundRequestState.REJECTED,
            first.requestLayout(CLUSTER, request("other-display"), mainGuard).state)
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            second.requestLayout(MAIN, request("other-session"), mainGuard).state)
        assertEquals(UiSyncForegroundRequestState.QUEUED,
            first.requestLayout(MAIN, request("own-scene"), mainGuard).state)
        first.close()
        second.close()
    }

    @Test
    fun sceneRevokedDuringBlockedWriteAbortsTransportAndLeavesUncertainReceipt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockWrite = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val harness = Harness(listOf(MAIN), onWrite = {
            if (blockWrite.get()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "scene revocation did not abort write" }
                if (aborted.get()) throw IOException("RCS socket closed on scene revocation")
            }
        })
        val outbox = boundOutbox(listOf(MAIN))
        val scene = TestSceneSource()
        val guard = outbox.guardScene(MAIN, scene)!!
        harness.open()
        val attachment = outbox.attach(harness.outbound) {
            aborted.set(true)
            release.countDown()
        }
        harness.ready(MAIN)
        attachment.refresh()
        val before = harness.wire.size
        val result = AtomicReference<UiSyncForegroundRequestReceipt>()
        val failure = AtomicReference<Throwable?>()
        blockWrite.set(true)
        val writer = Thread {
            runCatching { outbox.requestLayout(MAIN, request("revoked-mid-write"), guard) }
                .onSuccess(result::set).onFailure(failure::set)
        }
        writer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        scene.revoke()
        writer.join(2_000)
        assertFalse("revocation left write blocked", writer.isAlive)
        assertEquals(null, failure.get())
        assertTrue(aborted.get())
        assertEquals(UiSyncForegroundRequestState.WRITE_UNCERTAIN, result.get().state)
        assertEquals(before, harness.wire.size)
        val newScene = outbox.guardScene(MAIN, TestSceneSource())!!
        val next = outbox.requestLayout(MAIN, request("must-not-replay"), newScene)
        attachment.refresh()
        assertEquals(UiSyncForegroundRequestState.QUEUED, next.state)
        assertEquals(before, harness.wire.size)
        attachment.close()
        assertEquals(UiSyncForegroundRequestState.CANCELLED, next.state)
        outbox.close()
        harness.close()
    }

    @Test
    fun blockedWriteCanBeAbortedByConcurrentCloseWithoutHoldingOutboxMonitor() {
        val session = TestSessionAccess()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockWrite = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val harness = Harness(listOf(MAIN), onWrite = {
            if (blockWrite.get()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "write was not aborted" }
                if (aborted.get()) throw IOException("RCS socket closed during write")
            }
        })
        val outbox = boundOutbox(listOf(MAIN), session)
        harness.open()
        val attachment = outbox.attach(harness.outbound) {
            aborted.set(true)
            release.countDown()
        }
        harness.ready(MAIN)
        attachment.refresh()
        val before = harness.wire.size
        val receipt = AtomicReference<UiSyncForegroundRequestReceipt>()
        val writeFailure = AtomicReference<Throwable?>()
        blockWrite.set(true)
        val writer = Thread {
            runCatching { outbox.requestLayout(MAIN, request("aborted")) }
                .onSuccess(receipt::set).onFailure(writeFailure::set)
        }
        writer.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        session.live.set(false)
        val closer = Thread {
            attachment.close()
            outbox.close()
        }
        closer.start()
        closer.join(2_000)
        assertFalse("close waited on the blocked socket write", closer.isAlive)
        writer.join(2_000)
        assertFalse(writer.isAlive)
        assertEquals(null, writeFailure.get())
        assertEquals(UiSyncForegroundRequestState.WRITE_UNCERTAIN, receipt.get().state)
        assertEquals(before, harness.wire.size)
        assertEquals(0, session.activeUses.get())
        assertEquals(UiSyncForegroundRequestState.REJECTED,
            outbox.requestLayout(MAIN, request("stale")).state)
        harness.close()
    }

    @Test
    fun sceneRevokedOnlyAfterCompleteWriteIsSentAndKeepsTheTransport() {
        val wrote = AtomicBoolean(false)
        val harness = Harness(listOf(MAIN), onWrite = { wrote.set(true) })
        val outbox = boundOutbox(listOf(MAIN))
        harness.open()
        val attachment = outbox.attach(harness.outbound)
        harness.ready(MAIN)
        attachment.refresh()
        wrote.set(false) // handshake carriers are not the raw5 write
        // Models the phone's echo revoking the raw5 scene right after the write returned.
        val source = object : UiSyncForegroundSceneUseSource {
            private val current = AtomicBoolean(true)
            private val callbacks = CopyOnWriteArrayList<() -> Unit>()
            override fun acquireUse(onRevoked: () -> Unit): UiSyncForegroundSceneUse? {
                if (!current.get()) return null
                callbacks += onRevoked
                return object : UiSyncForegroundSceneUse {
                    override fun isCurrent(): Boolean {
                        if (wrote.get() && current.compareAndSet(true, false)) {
                            callbacks.forEach { it() }
                        }
                        return current.get()
                    }
                    override fun close() { callbacks -= onRevoked }
                }
            }
        }
        val before = harness.wire.size
        val receipt = outbox.requestLayout(MAIN, request("dashboard"),
            requireNotNull(outbox.guardScene(MAIN, source)))
        assertEquals(before + 1, harness.wire.size)
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED, receipt.state)
        // The transport was neither aborted nor marked uncertain: a later scene still sends.
        wrote.set(false)
        assertEquals(UiSyncForegroundRequestState.SENT_UNCONFIRMED,
            outbox.requestLayout(MAIN, request("next")).state)
        attachment.close()
        outbox.close()
        harness.close()
    }

    private class Harness(
        ids: List<String>,
        registry: CarPlayUiSyncEndpointRegistry = registryFor(ids),
        onWrite: () -> Unit = { },
    ) {
        val wire = ArrayList<ByteArray>()
        val outbound = SerializedUiSyncOutbound(registry) { carrier ->
            onWrite()
            wire += carrier
        }
        val remote = ids.associateWith { CarPlayUiSyncPhoneRoleSimulator(it) }

        /** Passive open: nothing is written until the phone's Reset arrives. */
        fun open() {
            assertTrue(outbound.open().outboundCarriers.isEmpty())
        }

        fun ready(displayId: String) {
            remote.getValue(displayId).handshake { outbound.receive(it).outboundCarriers }
            assertTrue(displayId in outbound.readyUiSyncDisplayIds())
        }

        fun close() {
            outbound.close()
        }
    }

    private fun request(id: String) = CarPlayUiSyncAccessoryCommand.RequestLayout(layoutID = id)

    private fun UiSyncForegroundRequestOutbox.requestLayout(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand.RequestLayout,
    ): UiSyncForegroundRequestReceipt {
        val scene = guardScene(displayId, TestSceneSource())
            ?: return UiSyncForegroundRequestReceipt(UiSyncForegroundRequestState.REJECTED)
        return requestLayout(displayId, command, scene)
    }

    private fun boundOutbox(
        ids: List<String>,
        session: TestSessionAccess = TestSessionAccess(),
    ) = UiSyncForegroundRequestOutbox(ids).also { it.bindSession(session) }

    private class TestSessionAccess : UiSyncForegroundRequestSessionAccess {
        val live = AtomicBoolean(true)
        val status = AtomicReference(UiSyncForegroundUseStatus.CURRENT)
        val activeUses = AtomicInteger()
        @Volatile var statusProbe: (() -> UiSyncForegroundUseStatus)? = null

        override fun acquireUse(): Closeable? {
            if (!live.get()) return null
            activeUses.incrementAndGet()
            return Closeable { activeUses.decrementAndGet() }
        }

        override fun isLive(): Boolean = live.get()

        override fun foregroundUseStatus(): UiSyncForegroundUseStatus =
            if (!live.get()) UiSyncForegroundUseStatus.REVOKED
            else statusProbe?.invoke() ?: status.get()
    }

    private class TestSceneSource : UiSyncForegroundSceneUseSource {
        private val current = AtomicBoolean(true)
        private val callbacks = CopyOnWriteArrayList<() -> Unit>()

        override fun acquireUse(onRevoked: () -> Unit): UiSyncForegroundSceneUse? {
            if (!current.get()) return null
            callbacks += onRevoked
            return object : UiSyncForegroundSceneUse {
                override fun isCurrent(): Boolean = current.get()
                override fun close() { callbacks -= onRevoked }
            }
        }

        fun revoke() {
            current.set(false)
            callbacks.forEach { it() }
        }

        fun activeUseCount(): Int = callbacks.size
    }

    private companion object {
        const val MAIN = "main"
        const val CLUSTER = "cluster"
        const val VEHICLE = "bench-vehicle"

        fun endpoint(id: String) = CarPlayUiSyncEndpoint(
            descriptor = CarPlayUiSyncChannelDescriptor(id, VEHICLE),
            mode = CarPlayUiSyncMode.PASSIVE,
            presentationEventSink = CarPlayUiSyncPresentationEventSink { },
        )

        fun registryFor(ids: List<String>) = CarPlayUiSyncEndpointRegistry(ids.map(::endpoint))
    }
}
