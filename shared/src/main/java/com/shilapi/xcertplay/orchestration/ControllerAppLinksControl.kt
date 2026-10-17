package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.message.Iap2AppLinksMessages
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconHash
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconResponse
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksUpdate
import com.shilapi.xcertplay.iap2.session.Iap2AppLinksIconRequestToken
import com.shilapi.xcertplay.iap2.session.Iap2TransferredAppLinksIcon
import java.io.Closeable

/** One verified, memory-only Debug image. Consumers receive a copy rather than the receiver buffer. */
class ControllerAppLinksIcon internal constructor(
    encoded: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
) {
    private val encoded = encoded.copyOf()
    fun bytes(): ByteArray = encoded.copyOf()
    override fun toString(): String = "ControllerAppLinksIcon(<redacted>)"
}

enum class ControllerAppLinksIconState {
    IDLE, QUEUED, WAITING_FOR_RESPONSE, RECEIVING, AVAILABLE, ABSENT, UNAVAILABLE, UNCERTAIN,
}

/** Explicit subscription metadata and at most one verified in-memory Debug icon. No app launch. */
data class ControllerAppLinksSnapshot(
    val available: Boolean = false,
    /** Changes whenever the exact authenticated control owner changes. */
    val ownerRevision: Long = 0L,
    val busy: Boolean = false,
    val subscribed: Boolean = false,
    /** Stop may have reached the phone; this session cannot safely send Start or Stop again. */
    val stopUncertain: Boolean = false,
    val latestUpdate: Iap2AppLinksUpdate? = null,
    val preferredIconSize: Int? = null,
    val iconFeatureAvailable: Boolean = false,
    val iconRequestAttempted: Boolean = false,
    val iconState: ControllerAppLinksIconState = ControllerAppLinksIconState.IDLE,
    val icon: ControllerAppLinksIcon? = null,
    val error: String? = null,
) {
    override fun toString(): String =
        "ControllerAppLinksSnapshot(available=$available, ownerRevision=$ownerRevision, " +
            "busy=$busy, subscribed=$subscribed, stopUncertain=$stopUncertain, " +
            "entryCount=${latestUpdate?.entries?.size ?: 0}, iconState=$iconState, " +
            "hasIcon=${icon != null}, hasError=${error != null})"
}

/**
 * One explicit Host-controlled AppLinks subscription across CSM replacements and Activity reattach.
 * [submit] must enqueue onto an owned, bounded worker; neither wire send nor UI delivery runs under
 * this broker's lock. A superseding CSM never inherits a subscription or starts automatically.
 */
internal class ControllerAppLinksControl(
    private val submit: (() -> Unit) -> Boolean,
    private val postToUi: (() -> Unit) -> Unit,
    private val scheduleIconTimeout: (Long, () -> Unit) -> Boolean = { _, _ -> true },
    private val iconsEnabled: Boolean = false,
) {
    class Owner(
        val sessionIdentity: Any,
        val isCurrentAndAuthorized: () -> Boolean,
        val start: (Set<Int>, Int?) -> Boolean,
        val stop: () -> Boolean,
        val prepareIconRequest: (Iap2AppLinksIconRequestToken) -> Boolean = { false },
        val requestIcon: (String) -> Boolean = { false },
        val grantIconTransfer: (
            Iap2AppLinksIconRequestToken,
            Iap2AppLinksIconResponse,
            () -> Boolean,
        ) -> Boolean = { _, _, _ -> false },
        val revokeIconRequest: (Iap2AppLinksIconRequestToken) -> Unit = {},
    )

    private class IconAttempt(
        val owner: Owner,
        val epoch: Long,
        val index: Int,
        val bundleIdentifier: String,
        val updateHash: Iap2AppLinksIconHash,
        val token: Iap2AppLinksIconRequestToken,
    ) {
        var sendStarted = false
        var responseTransferId: Int? = null
    }

    private val lock = Any()
    private var owner: Owner? = null
    private var ownerRevision = 0L
    private var epoch = 0L
    private var busy = false
    private var starting = false
    private var subscribed = false
    private var stopRequested = false
    private var stopUncertain = false
    /** A pending/uncertain AD02 cannot be bypassed by wrapping the same CSM in a new Owner. */
    private var quarantinedSessionIdentity: Any? = null
    private var latestUpdate: Iap2AppLinksUpdate? = null
    private var pendingPreferredIconSize: Int? = null
    private var preferredIconSize: Int? = null
    private var iconRequestNonce = 0L
    private var iconAttemptedSessionIdentity: Any? = null
    private var iconAttempt: IconAttempt? = null
    private var iconState = ControllerAppLinksIconState.IDLE
    private var icon: ControllerAppLinksIcon? = null
    private var error: String? = null
    private var closed = false
    private var uiListener: ((ControllerAppLinksSnapshot) -> Unit)? = null
    private var uiAttachmentToken = 0L
    private var uiPending: PendingUi? = null
    private var uiPosted = false

    private data class PendingUi(
        val owner: Owner?,
        val epoch: Long,
        val attachmentToken: Long,
        val snapshot: ControllerAppLinksSnapshot,
    )

    fun attachUi(listener: (ControllerAppLinksSnapshot) -> Unit): Closeable? {
        refreshAuthorization()
        val (attachment, retiring) = synchronized(lock) {
            if (closed) return null
            // A replacement Activity must explicitly request its own icon; no image survives a
            // UI attachment boundary even when the AppLinks subscription itself remains live.
            val oldIcon = retireIconLocked()
            uiAttachmentToken = Math.addExact(uiAttachmentToken, 1L)
            val token = uiAttachmentToken
            uiListener = listener
            queueUiLocked()
            Closeable {
                val revoke = synchronized(lock) {
                    if (uiAttachmentToken == token && uiListener === listener) {
                        uiListener = null
                        retireIconLocked()
                    } else null
                }
                retireIcon(revoke)
            } to oldIcon
        }
        retireIcon(retiring)
        return attachment
    }

    fun snapshot(): ControllerAppLinksSnapshot = synchronized(lock) { snapshotLocked() }

    fun publish(next: Owner): Boolean {
        if (!next.isCurrentAndAuthorized()) return false
        val (previous, retiring) = synchronized(lock) {
            if (closed) return false
            val old = owner
            if (old === next) return true
            if (quarantinedSessionIdentity === next.sessionIdentity) return false
            owner = next
            ownerRevision = Math.addExact(ownerRevision, 1L)
            epoch = Math.addExact(epoch, 1L)
            // If Stop was queued but has not resolved, its worker may skip after this epoch
            // changes. Retire the exact old owner; the lifecycle serializes duplicate calls and
            // refuses a second AD02 after an uncertain first write.
            val stopOld = old?.takeIf { subscribed || (busy && starting) ||
                (stopRequested && !stopUncertain) }
            val oldIcon = retireIconLocked()
            if (iconAttemptedSessionIdentity !== next.sessionIdentity) {
                iconAttemptedSessionIdentity = null
            }
            busy = false
            starting = false
            subscribed = false
            stopRequested = false
            stopUncertain = false
            quarantinedSessionIdentity = null
            latestUpdate = null
            pendingPreferredIconSize = null
            preferredIconSize = null
            error = null
            queueUiLocked()
            stopOld to oldIcon
        }
        retireIcon(retiring)
        previous?.let(::retireSubscription)
        return true
    }

    fun revoke(expected: Owner) {
        val (previous, retiring) = synchronized(lock) {
            if (owner !== expected) return
            owner = null
            ownerRevision = Math.addExact(ownerRevision, 1L)
            epoch = Math.addExact(epoch, 1L)
            val stopOld = expected.takeIf { subscribed || (busy && starting) ||
                (stopRequested && !stopUncertain) }
            val oldIcon = retireIconLocked()
            busy = false
            starting = false
            subscribed = false
            stopRequested = false
            stopUncertain = false
            latestUpdate = null
            pendingPreferredIconSize = null
            preferredIconSize = null
            error = null
            queueUiLocked()
            stopOld to oldIcon
        }
        retireIcon(retiring)
        previous?.let(::retireSubscription)
    }

    fun refreshAuthorization() {
        val current = synchronized(lock) { owner } ?: return
        if (!current.isCurrentAndAuthorized()) revoke(current)
    }

    /** Explicit Start only. Unsupported or empty categories are rejected before touching state. */
    fun start(
        categoryIds: Set<Int>,
        preferredIconSize: Int? = null,
        expectedOwnerRevision: Long? = null,
    ): Boolean {
        Iap2AppLinksMessages.start(categoryIds, preferredIconSize)
        // An explicit size belongs to the control owner shown when the user selected it. A
        // reconnect must never silently apply that preference to its replacement owner.
        if (preferredIconSize != null && expectedOwnerRevision == null) return false
        refreshAuthorization()
        val selected = categoryIds.toSet()
        val (target, requestEpoch) = synchronized(lock) {
            val exact = owner ?: return false
            if (closed || busy || subscribed || stopRequested || stopUncertain) return false
            if (expectedOwnerRevision != null && ownerRevision != expectedOwnerRevision) return false
            epoch = Math.addExact(epoch, 1L)
            busy = true
            starting = true
            latestUpdate = null
            pendingPreferredIconSize = preferredIconSize
            this.preferredIconSize = null
            error = null
            queueUiLocked()
            exact to epoch
        }
        if (!submit {
                val sent = if (isRequestCurrent(target, requestEpoch) &&
                    target.isCurrentAndAuthorized()
                ) {
                    runCatching { target.start(selected, preferredIconSize) }.getOrDefault(false)
                } else false
                val authorizedAfterSend = sent && target.isCurrentAndAuthorized()
                val stale = synchronized(lock) {
                    if (owner !== target || epoch != requestEpoch || closed) {
                        true
                    } else {
                        busy = false
                        starting = false
                        subscribed = authorizedAfterSend
                        this.preferredIconSize = pendingPreferredIconSize.takeIf { subscribed }
                        pendingPreferredIconSize = null
                        if (!subscribed) {
                            latestUpdate = null
                            error = "AppLinks Start was not accepted by this authenticated session"
                        }
                        queueUiLocked()
                        false
                    }
                }
                if (sent && (stale || !authorizedAfterSend)) runCatching { target.stop() }
            }) {
            failSubmission(target, requestEpoch)
            return false
        }
        return true
    }

    fun stop(): Boolean {
        refreshAuthorization()
        val (target, requestEpoch, retiring) = synchronized(lock) {
            val exact = owner ?: return false
            if (closed || busy || !subscribed || stopUncertain) return false
            epoch = Math.addExact(epoch, 1L)
            busy = true
            starting = false
            subscribed = false
            stopRequested = true
            quarantinedSessionIdentity = exact.sessionIdentity
            val oldIcon = retireIconLocked()
            latestUpdate = null
            preferredIconSize = null
            pendingPreferredIconSize = null
            error = null
            queueUiLocked()
            Triple(exact, epoch, oldIcon)
        }
        retireIcon(retiring)
        if (!submit {
                val sent = if (isRequestCurrent(target, requestEpoch)) {
                    runCatching { target.stop() }.getOrDefault(false)
                } else false
                synchronized(lock) {
                    if (owner === target && epoch == requestEpoch && !closed) {
                        busy = false
                        stopRequested = !sent
                        stopUncertain = !sent
                        if (sent) quarantinedSessionIdentity = null
                        if (!sent) error =
                            "AppLinks Stop outcome is unknown; reconnect this control session before using AppLinks again"
                        queueUiLocked()
                    }
                }
            }) {
            failSubmission(target, requestEpoch, preserveSubscription = true)
            return false
        }
        return true
    }

    /** Capture before handing metadata to another scheduler; Stop→Start changes this epoch. */
    fun captureActiveEpoch(expected: Owner): Long? = synchronized(lock) {
        epoch.takeIf {
            !closed && owner === expected && !stopRequested && !stopUncertain &&
                (subscribed || (busy && starting))
        }
    }

    /** Called only by the exact control client's AD01 callback after its own epoch validation. */
    fun receive(expected: Owner, expectedEpoch: Long, update: Iap2AppLinksUpdate) {
        if (!expected.isCurrentAndAuthorized()) {
            revoke(expected)
            return
        }
        val retiring = synchronized(lock) {
            if (closed || owner !== expected || epoch != expectedEpoch ||
                stopRequested || stopUncertain || !(subscribed || (busy && starting))
            ) return
            latestUpdate = update.copy(entries = update.entries.toList())
            val active = iconAttempt
            val oldIcon = if (active != null && !entryStillMatchesLocked(active)) {
                retireIconLocked()
            } else null
            queueUiLocked()
            oldIcon
        }
        retireIcon(retiring)
    }

    /** The UI supplies only an AD01 row index and the owner revision it displayed. */
    fun requestIcon(entryIndex: Int, expectedOwnerRevision: Long): Boolean {
        refreshAuthorization()
        val attempt = synchronized(lock) {
            val exact = owner ?: return false
            val update = latestUpdate ?: return false
            val entry = update.entries.singleOrNull { it.index == entryIndex } ?: return false
            val hash = entry.iconHash ?: return false
            if (closed || !iconsEnabled || uiListener == null ||
                ownerRevision != expectedOwnerRevision ||
                busy || !subscribed || stopRequested || stopUncertain ||
                preferredIconSize == null || preferredIconSize!! <= 0 || update.state != 2 ||
                iconAttemptedSessionIdentity === exact.sessionIdentity || iconAttempt != null
            ) return false
            iconRequestNonce = Math.addExact(iconRequestNonce, 1L)
            val request = IconAttempt(
                owner = exact,
                epoch = epoch,
                index = entry.index,
                bundleIdentifier = entry.bundleIdentifier,
                updateHash = hash,
                token = Iap2AppLinksIconRequestToken(
                    exact.sessionIdentity, epoch, iconRequestNonce, entry.bundleIdentifier,
                ),
            )
            iconAttemptedSessionIdentity = exact.sessionIdentity
            iconAttempt = request
            icon = null
            iconState = ControllerAppLinksIconState.QUEUED
            queueUiLocked()
            request
        }
        if (!isIconAttemptCurrent(attempt) ||
            !runCatching { attempt.owner.prepareIconRequest(attempt.token) }.getOrDefault(false)
        ) {
            failIconAttempt(attempt, ControllerAppLinksIconState.UNAVAILABLE)
            return false
        }
        if (!runCatching {
                scheduleIconTimeout(ICON_REQUEST_TIMEOUT_MILLIS) { expireIconAttempt(attempt) }
            }.getOrDefault(false)
        ) {
            failIconAttempt(attempt, ControllerAppLinksIconState.UNAVAILABLE)
            return false
        }
        if (!submit {
                val maySend = synchronized(lock) {
                    if (iconAttempt === attempt && !closed && owner === attempt.owner &&
                        epoch == attempt.epoch && entryStillMatchesLocked(attempt)
                    ) {
                        attempt.sendStarted = true
                        true
                    } else false
                }
                val sent = maySend && attempt.owner.isCurrentAndAuthorized() &&
                    runCatching { attempt.owner.requestIcon(attempt.bundleIdentifier) }.getOrDefault(false)
                if (!sent) failIconAttempt(attempt, ControllerAppLinksIconState.UNCERTAIN)
                else synchronized(lock) {
                    if (iconAttempt === attempt && iconState == ControllerAppLinksIconState.QUEUED) {
                        iconState = ControllerAppLinksIconState.WAITING_FOR_RESPONSE
                        queueUiLocked()
                    }
                }
            }) {
            failIconAttempt(attempt, ControllerAppLinksIconState.UNAVAILABLE)
            return false
        }
        return true
    }

    /** AD04 is metadata; only status 1 can grant the existing type-8 receiver. */
    fun receiveIconResponse(
        expected: Owner,
        expectedEpoch: Long,
        response: Iap2AppLinksIconResponse,
    ) {
        if (!expected.isCurrentAndAuthorized()) {
            revoke(expected)
            return
        }
        val decision = synchronized(lock) {
            val active = iconAttempt ?: return
            if (closed || owner !== expected || active.owner !== expected ||
                epoch != expectedEpoch || active.epoch != expectedEpoch ||
                !active.sendStarted || !subscribed || stopRequested || stopUncertain ||
                !entryStillMatchesLocked(active) ||
                response.bundleIdentifier != active.bundleIdentifier ||
                active.responseTransferId != null
            ) return
            when (response.status) {
                0 -> {
                    if (response.fileTransferBufferId != null || response.iconHash != null) return
                    val retired = retireIconLocked(ControllerAppLinksIconState.ABSENT)
                    queueUiLocked()
                    retired to false
                }
                1 -> {
                    if (response.fileTransferBufferId == null ||
                        response.fileTransferBufferId !in 0..255 || response.iconHash == null
                    ) return
                    active.responseTransferId = response.fileTransferBufferId
                    iconState = ControllerAppLinksIconState.RECEIVING
                    queueUiLocked()
                    active to true
                }
                else -> return
            }
        }
        val (attempt, grant) = decision
        if (!grant) {
            retireIcon(attempt)
            return
        }
        val grantedAttempt = attempt ?: return
        val accepted = runCatching {
            grantedAttempt.owner.grantIconTransfer(grantedAttempt.token, response) {
                isIconAttemptCurrent(grantedAttempt) && synchronized(lock) {
                    iconAttempt === grantedAttempt &&
                        grantedAttempt.responseTransferId == response.fileTransferBufferId
                }
            }
        }.getOrDefault(false)
        if (!accepted) failIconAttempt(grantedAttempt, ControllerAppLinksIconState.UNAVAILABLE)
    }

    /** Called by the one existing session-12 receiver after hash and image-header verification. */
    fun receiveIcon(transferred: Iap2TransferredAppLinksIcon) {
        val attempt = synchronized(lock) {
            val active = iconAttempt ?: return
            if (closed || active.token !== transferred.token ||
                active.owner.sessionIdentity !== transferred.token.sessionIdentity ||
                active.epoch != transferred.token.subscriptionEpoch ||
                active.responseTransferId != transferred.fileTransferId ||
                !subscribed || stopRequested || stopUncertain || !entryStillMatchesLocked(active)
            ) return
            active
        }
        if (!isIconAttemptCurrent(attempt) || !transferred.isCurrent()) return
        synchronized(lock) {
            if (iconAttempt !== attempt || attempt.token.revoked || closed ||
                !entryStillMatchesLocked(attempt)
            ) return
            icon = ControllerAppLinksIcon(
                transferred.bytes, transferred.mimeType, transferred.width, transferred.height,
            )
            iconState = ControllerAppLinksIconState.AVAILABLE
            queueUiLocked()
        }
    }

    fun close() {
        val (previous, retiring) = synchronized(lock) {
            if (closed) return
            closed = true
            val old = owner?.takeIf { subscribed || (busy && starting) ||
                (stopRequested && !stopUncertain) }
            val oldIcon = retireIconLocked()
            owner = null
            ownerRevision = Math.addExact(ownerRevision, 1L)
            epoch = Math.addExact(epoch, 1L)
            busy = false
            starting = false
            subscribed = false
            stopRequested = false
            stopUncertain = false
            quarantinedSessionIdentity = null
            iconAttemptedSessionIdentity = null
            latestUpdate = null
            preferredIconSize = null
            pendingPreferredIconSize = null
            uiAttachmentToken = Math.addExact(uiAttachmentToken, 1L)
            uiListener = null
            uiPending = null
            old to oldIcon
        }
        retireIcon(retiring)
        previous?.let(::retireSubscription)
    }

    private fun retireSubscription(old: Owner) {
        // A closing control client also closes its own lifecycle. This is an explicit early Stop
        // while that exact CSM is still live; rejection never falls back to the replacement CSM.
        submit { runCatching { old.stop() } }
    }

    private fun entryStillMatchesLocked(attempt: IconAttempt): Boolean {
        val update = latestUpdate ?: return false
        if (update.state != 2) return false
        val entry = update.entries.singleOrNull { it.index == attempt.index } ?: return false
        return entry.bundleIdentifier == attempt.bundleIdentifier &&
            entry.iconHash == attempt.updateHash
    }

    private fun isIconAttemptCurrent(attempt: IconAttempt): Boolean {
        val live = synchronized(lock) {
            !closed && iconAttempt === attempt && owner === attempt.owner &&
                epoch == attempt.epoch && subscribed && !stopRequested && !stopUncertain &&
                preferredIconSize != null && entryStillMatchesLocked(attempt) &&
                uiListener != null && !attempt.token.revoked
        }
        return live && runCatching { attempt.owner.isCurrentAndAuthorized() }.getOrDefault(false)
    }

    private fun failIconAttempt(attempt: IconAttempt, state: ControllerAppLinksIconState) {
        val retired = synchronized(lock) {
            if (iconAttempt !== attempt) return
            val old = retireIconLocked(state)
            queueUiLocked()
            old
        }
        retireIcon(retired)
    }

    private fun expireIconAttempt(attempt: IconAttempt) {
        val pending = synchronized(lock) {
            iconAttempt === attempt && iconState in setOf(
                ControllerAppLinksIconState.QUEUED,
                ControllerAppLinksIconState.WAITING_FOR_RESPONSE,
                ControllerAppLinksIconState.RECEIVING,
            )
        }
        if (pending) failIconAttempt(attempt, ControllerAppLinksIconState.UNAVAILABLE)
    }

    /** Mark the token revoked synchronously; the receiver's best-effort FAIL runs off the UI. */
    private fun retireIconLocked(
        nextState: ControllerAppLinksIconState = ControllerAppLinksIconState.IDLE,
    ): IconAttempt? {
        val old = iconAttempt
        old?.token?.revoked = true
        iconAttempt = null
        icon = null
        iconState = nextState
        return old
    }

    private fun retireIcon(attempt: IconAttempt?) {
        if (attempt == null) return
        submit { runCatching { attempt.owner.revokeIconRequest(attempt.token) } }
    }

    private fun isRequestCurrent(expected: Owner, requestEpoch: Long): Boolean = synchronized(lock) {
        !closed && owner === expected && epoch == requestEpoch
    }

    private fun failSubmission(
        expected: Owner,
        requestEpoch: Long,
        preserveSubscription: Boolean = false,
    ) {
        synchronized(lock) {
            if (closed || owner !== expected || epoch != requestEpoch) return
            busy = false
            starting = false
            subscribed = preserveSubscription
            latestUpdate = null
            pendingPreferredIconSize = null
            preferredIconSize = null
            error = if (preserveSubscription) {
                "AppLinks Stop was not queued; retry Stop on this session"
            } else {
                "AppLinks worker is unavailable"
            }
            queueUiLocked()
        }
    }

    private fun snapshotLocked() = ControllerAppLinksSnapshot(
        available = !closed && owner != null && !stopUncertain,
        ownerRevision = ownerRevision,
        busy = busy,
        subscribed = subscribed,
        stopUncertain = stopUncertain,
        latestUpdate = latestUpdate,
        preferredIconSize = preferredIconSize,
        iconFeatureAvailable = iconsEnabled,
        iconRequestAttempted = owner != null &&
            owner?.sessionIdentity === iconAttemptedSessionIdentity,
        iconState = iconState,
        icon = icon,
        error = error,
    )

    private fun queueUiLocked() {
        uiPending = PendingUi(owner, epoch, uiAttachmentToken, snapshotLocked())
        if (uiPosted) return
        uiPosted = true
        postToUi(::drainUi)
    }

    private fun drainUi() {
        val delivery = synchronized(lock) {
            uiPosted = false
            val pending = uiPending ?: return
            uiPending = null
            val listener = uiListener ?: return
            if (closed || owner !== pending.owner || epoch != pending.epoch ||
                uiAttachmentToken != pending.attachmentToken
            ) return
            listener to pending
        }
        val (listener, pending) = delivery
        val valid = synchronized(lock) {
            !closed && uiListener === listener && owner === pending.owner &&
                epoch == pending.epoch && uiAttachmentToken == pending.attachmentToken
        }
        if (valid && (pending.owner == null || pending.owner.isCurrentAndAuthorized())) {
            listener(pending.snapshot)
        }
    }

    private companion object {
        const val ICON_REQUEST_TIMEOUT_MILLIS = 31_000L
    }
}
