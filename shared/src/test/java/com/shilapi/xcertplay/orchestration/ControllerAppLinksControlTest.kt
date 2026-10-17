package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2AppLinkEntry
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksMessages
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconHash
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconResponse
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksUpdate
import com.shilapi.xcertplay.iap2.session.Iap2AppLinksIconRequestToken
import com.shilapi.xcertplay.iap2.session.Iap2TransferredAppLinksIcon
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerAppLinksControlTest {
    private class Queues {
        val worker = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val iconTimers = ArrayDeque<() -> Unit>()
        val control = ControllerAppLinksControl(
            submit = { worker.addLast(it); true },
            postToUi = { ui.addLast(it) },
            scheduleIconTimeout = { delay, action ->
                assertEquals(31_000L, delay)
                iconTimers.addLast(action)
                true
            },
            iconsEnabled = true,
        )

        fun work() { while (worker.isNotEmpty()) worker.removeFirst().invoke() }
        fun render() { while (ui.isNotEmpty()) ui.removeFirst().invoke() }
        fun expireIcons() { while (iconTimers.isNotEmpty()) iconTimers.removeFirst().invoke() }
    }

    private class Port {
        var authorized = true
        var starts = 0
        var stops = 0
        var stopResult = true
        var stopFailure: Throwable? = null
        val startedFrames = mutableListOf<Iap2Frame>()
        val iconActions = mutableListOf<String>()
        var preparedIcon: Iap2AppLinksIconRequestToken? = null
        var requestedBundle: String? = null
        var grantedResponse: Iap2AppLinksIconResponse? = null
        var iconSendResult = true
        var prepareResult = true
        var grantResult = true

        fun owner(identity: Any = Any()) = ControllerAppLinksControl.Owner(
            sessionIdentity = identity,
            isCurrentAndAuthorized = { authorized },
            start = { categories, preferredIconSize ->
                starts++
                startedFrames += Iap2AppLinksMessages.start(categories, preferredIconSize)
                true
            },
            stop = {
                stops++
                stopFailure?.let { throw it }
                stopResult
            },
            prepareIconRequest = { token ->
                iconActions += "prepare"
                preparedIcon = token
                prepareResult
            },
            requestIcon = { bundleIdentifier ->
                iconActions += "send"
                requestedBundle = bundleIdentifier
                iconSendResult
            },
            grantIconTransfer = { token, response, isCurrent ->
                iconActions += "grant"
                grantedResponse = response.takeIf { token === preparedIcon && isCurrent() }
                grantResult && grantedResponse != null
            },
            revokeIconRequest = { token ->
                iconActions += "revoke"
                token.revoked = true
            },
        )
    }

    @Test
    fun publishAndActivityAttachNeverStartAutomatically() {
        val queues = Queues()
        val port = Port()
        val observed = mutableListOf<ControllerAppLinksSnapshot>()
        assertTrue(queues.control.publish(port.owner()))
        queues.control.attachUi(observed::add)
        queues.render()

        assertEquals(0, port.starts)
        assertTrue(observed.single().available)
        assertFalse(observed.single().subscribed)
        assertNull(observed.single().latestUpdate)
    }

    @Test
    fun defaultStartKeepsIconSizeAbsentOnWire() {
        val queues = Queues()
        val port = Port()
        assertTrue(queues.control.publish(port.owner()))
        assertTrue(queues.control.start(setOf(3)))
        queues.work()

        assertEquals(1, port.starts)
        assertNull(Iap2BodyReader.of(port.startedFrames.single()).optionalU16(2))
    }

    @Test
    fun explicit64PixelChoiceIsFrozenForTheExactOwnerStartEpoch() {
        val queues = Queues()
        val port = Port()
        assertTrue(queues.control.publish(port.owner()))
        val ownerRevision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, ownerRevision))
        queues.work()

        assertEquals(64, Iap2BodyReader.of(port.startedFrames.single()).u16(2))
        assertTrue(queues.control.stop())
        queues.work()
        assertEquals(1, port.stops)
        assertTrue(queues.control.start(setOf(4), expectedOwnerRevision = ownerRevision))
        queues.work()
        assertNull(Iap2BodyReader.of(port.startedFrames.last()).optionalU16(2))
    }

    @Test
    fun staleIconChoiceCannotCrossControlOwnerReplacement() {
        val queues = Queues()
        val first = Port()
        val replacement = Port()
        assertTrue(queues.control.publish(first.owner()))
        val oldRevision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.publish(replacement.owner()))
        val newRevision = queues.control.snapshot().ownerRevision

        assertFalse(queues.control.start(setOf(3), 64, oldRevision))
        assertFalse(queues.control.start(setOf(3), 64))
        assertEquals(0, replacement.starts)
        assertTrue(queues.control.start(setOf(3), expectedOwnerRevision = newRevision))
        queues.work()
        assertNull(Iap2BodyReader.of(replacement.startedFrames.single()).optionalU16(2))
    }

    @Test
    fun bootstrapQueuedStartCannotMigrateToTunnel() {
        val queues = Queues()
        val bootstrap = Port()
        val tunnel = Port()
        val bootstrapOwner = bootstrap.owner()
        val tunnelOwner = tunnel.owner()
        assertTrue(queues.control.publish(bootstrapOwner))
        assertTrue(queues.control.start(setOf(3)))
        assertTrue(queues.control.publish(tunnelOwner))
        queues.work()

        assertEquals(0, bootstrap.starts)
        assertEquals(0, tunnel.starts)
        assertFalse(queues.control.snapshot().subscribed)
        queues.control.receive(bootstrapOwner, -1L, update("Stale"))
        assertNull(queues.control.snapshot().latestUpdate)
    }

    @Test
    fun activeBootstrapIsStoppedOnSupersessionWithoutStartingTunnel() {
        val queues = Queues()
        val bootstrap = Port()
        val tunnel = Port()
        val old = bootstrap.owner()
        assertTrue(queues.control.publish(old))
        assertTrue(queues.control.start(setOf(4)))
        queues.work()
        assertEquals(1, bootstrap.starts)
        assertTrue(queues.control.publish(tunnel.owner()))
        queues.work()

        assertEquals(1, bootstrap.stops)
        assertEquals(0, tunnel.starts)
        assertFalse(queues.control.snapshot().subscribed)
    }

    @Test
    fun stopThenStartDropsOldEpochAndCoalescesLatestMetadata() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        val observed = mutableListOf<ControllerAppLinksSnapshot>()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi(observed::add)
        queues.render()
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        val oldEpoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, oldEpoch, update("Before Stop"))
        assertTrue(queues.control.stop())
        queues.work()
        assertTrue(queues.control.start(setOf(4)))
        queues.work()
        queues.control.receive(owner, oldEpoch, update("Late from old subscription"))
        val currentEpoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, currentEpoch, update("First"))
        queues.control.receive(owner, currentEpoch, update("Latest"))
        assertEquals(1, queues.ui.size)
        queues.render()

        assertEquals(2, port.starts)
        assertEquals(1, port.stops)
        assertEquals("Latest", observed.last().latestUpdate?.entries?.single()?.localizedName)
        assertFalse(observed.any { it.latestUpdate?.entries?.singleOrNull()?.localizedName == "Before Stop" })
        assertFalse(observed.any {
            it.latestUpdate?.entries?.singleOrNull()?.localizedName == "Late from old subscription"
        })
    }

    @Test
    fun reattachedActivityGetsLatestOnlyAndOldAttachmentCannotClearNewOne() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        val oldUi = mutableListOf<ControllerAppLinksSnapshot>()
        val newUi = mutableListOf<ControllerAppLinksSnapshot>()
        assertTrue(queues.control.publish(owner))
        val oldLease = queues.control.attachUi(oldUi::add)!!
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        queues.control.receive(owner, checkNotNull(queues.control.captureActiveEpoch(owner)), update("Current"))
        queues.control.attachUi(newUi::add)
        oldLease.close()
        queues.render()

        assertTrue(oldUi.isEmpty())
        assertEquals("Current", newUi.single().latestUpdate?.entries?.single()?.localizedName)
        assertEquals(1, port.starts)
    }

    @Test
    fun reattachingTheSameListenerCannotLetOldLeaseClearItsSuccessor() {
        val queues = Queues()
        val observed = mutableListOf<ControllerAppLinksSnapshot>()
        val listener: (ControllerAppLinksSnapshot) -> Unit = { observed += it }
        assertTrue(queues.control.publish(Port().owner()))
        val oldLease = checkNotNull(queues.control.attachUi(listener))
        val newLease = checkNotNull(queues.control.attachUi(listener))
        oldLease.close()
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        queues.render()

        assertTrue(observed.isNotEmpty())
        assertTrue(observed.last().subscribed)
        newLease.close()
    }

    @Test
    fun revokedAuthenticationRejectsStartAndDropsQueuedUpdate() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        val observed = mutableListOf<ControllerAppLinksSnapshot>()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi(observed::add)
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        queues.control.receive(owner, checkNotNull(queues.control.captureActiveEpoch(owner)), update("Prior"))
        port.authorized = false
        queues.control.refreshAuthorization()
        queues.render()

        assertFalse(queues.control.start(setOf(3)))
        queues.control.receive(owner, -1L, update("Forbidden"))
        assertFalse(observed.last().available)
        assertNull(observed.last().latestUpdate)
    }

    @Test
    fun closeStopsTheExactSubscribedSessionWithoutUsingItsSuccessor() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        assertTrue(queues.control.publish(owner))
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        queues.control.close()
        queues.work()

        assertEquals(1, port.starts)
        assertEquals(1, port.stops)
        assertFalse(queues.control.publish(Port().owner()))
        assertFalse(queues.control.start(setOf(3)))
    }

    @Test
    fun attemptedStopFailureQuarantinesSameSessionAndSuppressesLateUpdates() {
        val queues = Queues()
        val port = Port()
        val identity = Any()
        val owner = port.owner(identity)
        assertTrue(queues.control.publish(owner))
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        val oldEpoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        port.stopFailure = IllegalStateException("possibly partial AD02")
        assertTrue(queues.control.stop())
        assertNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, oldEpoch, update("Too late"))
        queues.work()

        val snapshot = queues.control.snapshot()
        assertFalse(snapshot.available)
        assertFalse(snapshot.subscribed)
        assertTrue(snapshot.stopUncertain)
        assertNull(snapshot.latestUpdate)
        assertTrue(snapshot.error!!.contains("outcome is unknown"))
        assertFalse(queues.control.start(setOf(3)))
        assertFalse(queues.control.stop())
        assertFalse(queues.control.publish(port.owner(identity)))
        queues.control.revoke(owner)
        assertFalse(queues.control.publish(port.owner(identity)))
        queues.control.close()
        queues.work()
        assertEquals(1, port.stops)
    }

    @Test
    fun definitelyUnqueuedStopCanBeRetriedWithoutResumingMetadata() {
        val worker = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        var acceptingWork = true
        val control = ControllerAppLinksControl(
            submit = { action -> if (acceptingWork) worker.addLast(action); acceptingWork },
            postToUi = { ui.addLast(it) },
        )
        val port = Port()
        val owner = port.owner()
        assertTrue(control.publish(owner))
        assertTrue(control.start(setOf(3)))
        worker.removeFirst().invoke()
        val oldEpoch = checkNotNull(control.captureActiveEpoch(owner))
        acceptingWork = false
        assertFalse(control.stop())
        assertEquals(0, port.stops)
        assertTrue(control.snapshot().subscribed)
        assertFalse(control.snapshot().stopUncertain)
        assertTrue(control.snapshot().error!!.contains("not queued"))
        assertNull(control.captureActiveEpoch(owner))
        control.receive(owner, oldEpoch, update("After Stop request"))
        assertNull(control.snapshot().latestUpdate)

        acceptingWork = true
        assertTrue(control.stop())
        worker.removeFirst().invoke()
        assertEquals(1, port.stops)
        assertFalse(control.snapshot().subscribed)
        assertFalse(control.snapshot().stopUncertain)
    }

    @Test
    fun replacementChannelCanStartAfterOldStopOutcomeIsUnknown() {
        val queues = Queues()
        val old = Port()
        val oldOwner = old.owner()
        assertTrue(queues.control.publish(oldOwner))
        assertTrue(queues.control.start(setOf(3), 64, queues.control.snapshot().ownerRevision))
        queues.work()
        assertEquals(64, Iap2BodyReader.of(old.startedFrames.single()).u16(2))
        old.stopResult = false
        assertTrue(queues.control.stop())
        queues.work()
        assertTrue(queues.control.snapshot().stopUncertain)

        val replacement = Port()
        assertTrue(queues.control.publish(replacement.owner()))
        assertTrue(queues.control.start(setOf(4)))
        queues.work()
        assertEquals(1, old.stops)
        assertEquals(1, replacement.starts)
        assertNull(Iap2BodyReader.of(replacement.startedFrames.single()).optionalU16(2))
        assertTrue(queues.control.snapshot().subscribed)
        assertFalse(queues.control.snapshot().stopUncertain)
    }

    @Test
    fun queuedStopIsRetiredOnSupersessionEvenIfOriginalWorkerSkips() {
        val queues = Queues()
        val old = Port()
        val oldOwner = old.owner()
        assertTrue(queues.control.publish(oldOwner))
        assertTrue(queues.control.start(setOf(3)))
        queues.work()
        assertTrue(queues.control.stop())
        assertTrue(queues.control.publish(Port().owner()))
        queues.work()
        assertEquals(1, old.stops)
    }

    @Test
    fun manualIconRequestRequiresListHashExplicitSizeAndCurrentOwner() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi {}
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), expectedOwnerRevision = revision))
        queues.work()
        val epoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, epoch, updateWithIcon())
        assertFalse(queues.control.requestIcon(1, revision)) // Default None cannot fetch bytes.
        assertFalse(queues.control.requestIcon(1, revision - 1))
        assertTrue(queues.control.stop())
        queues.work()
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        val newEpoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        assertFalse(queues.control.requestIcon(1, revision)) // No current AD01 state 2 yet.
        queues.control.receive(owner, newEpoch, updateWithIcon(state = 1))
        assertFalse(queues.control.requestIcon(1, revision))
        queues.control.receive(owner, newEpoch, update("No hash"))
        assertFalse(queues.control.requestIcon(1, revision))
        queues.control.receive(owner, newEpoch, updateWithIcon())
        assertFalse(queues.control.requestIcon(1, revision - 1))
        assertFalse(queues.control.requestIcon(2, revision))
        assertTrue(queues.control.requestIcon(1, revision))
        assertEquals(listOf("prepare"), port.iconActions)
        queues.work()
        assertEquals(listOf("prepare", "send"), port.iconActions)
        assertEquals("com.example.one", port.requestedBundle)
        assertTrue(queues.control.snapshot().iconRequestAttempted)
        assertFalse(queues.control.snapshot().toString().contains("com.example.one"))
    }

    @Test
    fun statusOneGrantsOnlyMatchingCurrentTokenAndType8CallbackPublishesOneIcon() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi {}
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        val epoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, epoch, updateWithIcon())
        assertTrue(queues.control.requestIcon(1, revision))
        queues.work()
        val token = checkNotNull(port.preparedIcon)
        val hash = Iap2AppLinksIconHash.of(ByteArray(16) { 7 })
        val valid = Iap2AppLinksIconResponse("com.example.one", 1, 19, hash)
        queues.control.receiveIconResponse(owner, epoch - 1, valid)
        queues.control.receiveIconResponse(owner, epoch,
            Iap2AppLinksIconResponse("com.example.other", 1, 19, hash))
        queues.control.receiveIconResponse(owner, epoch,
            Iap2AppLinksIconResponse("com.example.one", 1, null, hash))
        assertNull(port.grantedResponse)
        queues.control.receiveIconResponse(owner, epoch, valid)
        assertEquals(valid, port.grantedResponse)
        assertEquals(ControllerAppLinksIconState.RECEIVING, queues.control.snapshot().iconState)
        queues.control.receiveIcon(Iap2TransferredAppLinksIcon(
            token, 20, byteArrayOf(1), "image/png", 1, 1, { true },
        ))
        assertNull(queues.control.snapshot().icon)
        val bytes = byteArrayOf(1, 2, 3)
        queues.control.receiveIcon(Iap2TransferredAppLinksIcon(
            token, 19, bytes, "image/png", 1, 1, { true },
        ))
        bytes[0] = 0
        val icon = checkNotNull(queues.control.snapshot().icon)
        assertEquals(ControllerAppLinksIconState.AVAILABLE, queues.control.snapshot().iconState)
        assertEquals(1, icon.bytes()[0].toInt())
        icon.bytes()[1] = 0
        assertEquals(2, icon.bytes()[1].toInt())
        assertFalse(queues.control.requestIcon(1, revision))
    }

    @Test
    fun statusZeroNeverGrantsAndStopStartCannotReuseSameChannelForIcon() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi {}
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        val epoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, epoch, updateWithIcon())
        assertTrue(queues.control.requestIcon(1, revision))
        queues.work()
        queues.control.receiveIconResponse(owner, epoch,
            Iap2AppLinksIconResponse("com.example.one", 0, null, null))
        queues.work()
        assertNull(port.grantedResponse)
        assertEquals(ControllerAppLinksIconState.ABSENT, queues.control.snapshot().iconState)
        assertTrue(queues.control.stop())
        queues.work()
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        val newEpoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, newEpoch, updateWithIcon())
        assertFalse(queues.control.requestIcon(1, revision))
        queues.control.receiveIconResponse(owner, epoch,
            Iap2AppLinksIconResponse("com.example.one", 1, 19,
                Iap2AppLinksIconHash.of(ByteArray(16) { 7 })))
        assertNull(queues.control.snapshot().icon)
        assertEquals(1, port.iconActions.count { it == "send" })
    }

    @Test
    fun ownerReplacementAndUiDetachRevokeOldIconWithoutMigratingBytes() {
        val queues = Queues()
        val first = Port()
        val oldOwner = first.owner()
        assertTrue(queues.control.publish(oldOwner))
        val attachment = checkNotNull(queues.control.attachUi {})
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        val epoch = checkNotNull(queues.control.captureActiveEpoch(oldOwner))
        queues.control.receive(oldOwner, epoch, updateWithIcon())
        assertTrue(queues.control.requestIcon(1, revision))
        queues.work()
        val token = checkNotNull(first.preparedIcon)
        attachment.close()
        assertTrue(token.revoked)
        assertNull(queues.control.snapshot().icon)
        assertFalse(queues.control.requestIcon(1, revision))
        val replacement = Port()
        val newOwner = replacement.owner()
        assertTrue(queues.control.publish(newOwner))
        queues.control.attachUi {}
        assertFalse(queues.control.snapshot().iconRequestAttempted)
        queues.control.receiveIcon(Iap2TransferredAppLinksIcon(
            token, 19, byteArrayOf(1), "image/png", 1, 1, { true },
        ))
        assertNull(queues.control.snapshot().icon)
        assertFalse(queues.control.requestIcon(1, revision))
    }

    @Test
    fun ambiguousAd03SendQuarantinesSameChannelUntilReplacement() {
        val queues = Queues()
        val port = Port().apply { iconSendResult = false }
        val owner = port.owner()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi {}
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        queues.control.receive(owner, checkNotNull(queues.control.captureActiveEpoch(owner)), updateWithIcon())
        assertTrue(queues.control.requestIcon(1, revision))
        queues.work()
        assertEquals(ControllerAppLinksIconState.UNCERTAIN, queues.control.snapshot().iconState)
        assertFalse(queues.control.requestIcon(1, revision))
        assertEquals(1, port.iconActions.count { it == "send" })
    }

    @Test
    fun noAd04OrTransferCompletionExpiresOneShotRequestAndDropsLateBytes() {
        val queues = Queues()
        val port = Port()
        val owner = port.owner()
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi {}
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        val epoch = checkNotNull(queues.control.captureActiveEpoch(owner))
        queues.control.receive(owner, epoch, updateWithIcon())
        assertTrue(queues.control.requestIcon(1, revision))
        queues.work()
        val token = checkNotNull(port.preparedIcon)
        queues.expireIcons()
        queues.work()
        assertTrue(token.revoked)
        assertEquals(ControllerAppLinksIconState.UNAVAILABLE, queues.control.snapshot().iconState)
        queues.control.receiveIconResponse(owner, epoch,
            Iap2AppLinksIconResponse("com.example.one", 1, 19,
                Iap2AppLinksIconHash.of(ByteArray(16) { 7 })))
        queues.control.receiveIcon(Iap2TransferredAppLinksIcon(
            token, 19, byteArrayOf(1), "image/png", 1, 1, { true },
        ))
        assertNull(queues.control.snapshot().icon)
        assertFalse(queues.control.requestIcon(1, revision))
    }

    @Test
    fun timeoutFromRetiredEpochCannotChangeLaterSubscriptionOrSameChannelQuarantine() {
        val queues = Queues()
        val port = Port()
        val identity = Any()
        val owner = port.owner(identity)
        assertTrue(queues.control.publish(owner))
        queues.control.attachUi {}
        val revision = queues.control.snapshot().ownerRevision
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        queues.control.receive(owner, checkNotNull(queues.control.captureActiveEpoch(owner)), updateWithIcon())
        assertTrue(queues.control.requestIcon(1, revision))
        queues.work()
        assertTrue(queues.control.stop())
        queues.work()
        assertTrue(queues.control.start(setOf(3), 64, revision))
        queues.work()
        queues.control.receive(owner, checkNotNull(queues.control.captureActiveEpoch(owner)), updateWithIcon())
        queues.expireIcons()
        assertEquals(ControllerAppLinksIconState.IDLE, queues.control.snapshot().iconState)
        assertTrue(queues.control.snapshot().subscribed)
        assertFalse(queues.control.requestIcon(1, revision))
        queues.control.revoke(owner)
        assertTrue(queues.control.publish(port.owner(identity)))
        assertTrue(queues.control.snapshot().iconRequestAttempted)
    }

    private fun update(name: String) = Iap2AppLinksUpdate(
        state = 2,
        entries = listOf(Iap2AppLinkEntry(1, "com.example.one", name, 3)),
        reportedCount = 1,
    )

    private fun updateWithIcon(state: Int = 2) = Iap2AppLinksUpdate(
        state = state,
        entries = listOf(Iap2AppLinkEntry(
            1, "com.example.one", "Navigation", 3,
            Iap2AppLinksIconHash.of(ByteArray(16) { 9 }),
        )),
        reportedCount = if (state == 2) 1 else null,
    )
}
