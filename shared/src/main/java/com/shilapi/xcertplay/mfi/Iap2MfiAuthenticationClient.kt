package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.Closeable
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val AA05_RECEIPT_ISSUE_PERMIT = Any()

/**
 * Authority owned by one captured MFi session. Only its current live control-channel attempt may
 * consume a receipt. An ordinary [begin] revokes the preceding generation, while an explicit
 * predecessor-bound handoff keeps that generation live until the caller commits its successor.
 */
internal class MfiAuthenticationReceiptAuthority private constructor(
    private val authenticationIdentity: MfiAuthenticationIdentity,
) : Closeable {
    private class PendingHandoff(
        val handoff: MfiAuthenticationHandoff,
        val predecessorAttempt: MfiAuthenticationAttempt,
        val predecessorReceipt: MfiAuthenticationReceipt,
        val successorAttempt: MfiAuthenticationAttempt,
    ) {
        var interruption: IllegalStateException? = null
        val deferredRevocations = mutableListOf<() -> Unit>()

        fun interrupt(message: String) {
            if (interruption == null) interruption = IllegalStateException(message)
        }

        fun defer(callback: (() -> Unit)?) {
            if (callback != null) deferredRevocations.add(callback)
        }
    }

    private data class FailedRevocation(
        val callback: () -> Unit,
        val failure: Throwable,
    )

    private val lock = Any()
    private val closeCallLock = Any()
    private var closed = false
    private var generation = 0L
    private var highestControlGeneration = -1L
    private var active: MfiAuthenticationAttempt? = null
    private var pendingHandoff: PendingHandoff? = null
    private var handoffCommitInProgress: PendingHandoff? = null
    private var authenticationSource: MfiAuthenticator? = null
    private val failedRevocations = ArrayDeque<FailedRevocation>()
    private var revocationBatchesInFlight = 0

    fun bindAuthenticationSource(source: MfiAuthenticator) {
        synchronized(lock) {
            check(!closed && active == null && pendingHandoff == null) {
                "MFi authentication source must be bound before the first attempt"
            }
            check(authenticationSource == null) { "MFi authentication source is already bound" }
            authenticationSource = source
        }
    }

    fun begin(
        controlSession: Any,
        controlGeneration: Long,
    ): MfiAuthenticationAttempt {
        require(controlGeneration >= 0L) { "MFi control generation must not be negative" }
        val revocationCallbacks = mutableListOf<() -> Unit>()
        val next: MfiAuthenticationAttempt
        synchronized(lock) {
            check(!closed) { "MFi receipt authority is closed" }
            requireNoRevocationFailuresLocked()
            check(handoffCommitInProgress == null) { "MFi authentication handoff commit is in progress" }
            check(generation != Long.MAX_VALUE) { "MFi receipt generation exhausted" }
            check(controlGeneration > highestControlGeneration) {
                "MFi control attempt generation is stale"
            }
            active?.markRevokedFromAuthority()?.let(revocationCallbacks::add)
            pendingHandoff?.successorAttempt
                ?.markRevokedFromAuthority()
                ?.let(revocationCallbacks::add)
            pendingHandoff = null
            generation += 1L
            highestControlGeneration = controlGeneration
            next = MfiAuthenticationAttempt(
                authority = this,
                controlSession = controlSession,
                authenticationIdentity = authenticationIdentity,
                authorityGeneration = generation,
                controlGeneration = controlGeneration,
            )
            active = next
            reserveRevocationsLocked(revocationCallbacks)
        }
        val revocationFailures = invokeReservedRevocations(revocationCallbacks)
        if (revocationFailures.isNotEmpty()) {
            synchronized(lock) {
                if (active === next) active = null
                next.markRevokedFromAuthority()
            }
            propagateErrors(revocationFailures)
            throw IllegalStateException(
                "MFi predecessor revocation failed; replacement attempt was revoked",
                revocationFailures.first(),
            )
        }
        val admissionFailure = synchronized(lock) {
            if (!closed && active === next && next.isLive && !hasPendingRevocationCleanupLocked()) {
                null
            } else {
                if (active === next) active = null
                next.markRevokedFromAuthority()
                IllegalStateException(
                    "MFi replacement attempt was invalidated during predecessor revocation",
                    failedRevocations.firstOrNull()?.failure,
                )
            }
        }
        admissionFailure?.let { throw it }
        return next
    }

    fun beginHandoff(
        predecessorReceipt: MfiAuthenticationReceipt,
        controlSession: Any,
        controlGeneration: Long,
    ): MfiAuthenticationHandoff = synchronized(lock) {
        require(controlGeneration >= 0L) { "MFi control generation must not be negative" }
        check(!closed) { "MFi receipt authority is closed" }
        requireNoRevocationFailuresLocked()
        check(handoffCommitInProgress == null) { "MFi authentication handoff commit is in progress" }
        check(generation != Long.MAX_VALUE) { "MFi receipt generation exhausted" }
        check(controlGeneration > highestControlGeneration) {
            "MFi control handoff generation is stale"
        }
        check(pendingHandoff == null) { "MFi authentication handoff is already pending" }
        val predecessorAttempt = checkNotNull(active) {
            "MFi authentication handoff has no live predecessor"
        }
        check(
            predecessorAttempt.isLive &&
                predecessorAttempt.hasReceipt(predecessorReceipt) &&
                predecessorReceipt.authenticationIdentity == authenticationIdentity &&
                predecessorReceipt.isUsableHandoffPredecessor(),
        ) { "MFi authentication handoff predecessor is stale or belongs to another session" }

        generation += 1L
        highestControlGeneration = controlGeneration
        val successorAttempt = MfiAuthenticationAttempt(
            authority = this,
            controlSession = controlSession,
            authenticationIdentity = authenticationIdentity,
            authorityGeneration = generation,
            controlGeneration = controlGeneration,
        )
        val handoff = MfiAuthenticationHandoff.create(this, successorAttempt)
        pendingHandoff = PendingHandoff(
            handoff = handoff,
            predecessorAttempt = predecessorAttempt,
            predecessorReceipt = predecessorReceipt,
            successorAttempt = successorAttempt,
        )
        handoff
    }

    internal fun issue(
        attempt: MfiAuthenticationAttempt,
        controlSession: Any,
        identity: MfiAuthenticationIdentity,
    ): MfiAuthenticationReceipt = synchronized(lock) {
        requireNoRevocationFailuresLocked()
        check(authenticationSource == null) {
            "Source-bound MFi authentication attempts require an exact authenticator"
        }
        issueLocked(attempt, controlSession, identity)
    }

    internal fun issue(
        attempt: MfiAuthenticationAttempt,
        controlSession: Any,
        identity: MfiAuthenticationIdentity,
        source: MfiAuthenticator,
        permit: Any,
    ): MfiAuthenticationReceipt = synchronized(lock) {
        requireNoRevocationFailuresLocked()
        check(permit === AA05_RECEIPT_ISSUE_PERMIT) {
            "MFi receipt issuance requires an ordered AA05 exchange"
        }
        requireAuthenticationSourceLocked(source)
        check(attempt.hasClaimedExchange) { "MFi authentication exchange was not claimed" }
        issueLocked(attempt, controlSession, identity)
    }

    internal fun claimExchange(
        attempt: MfiAuthenticationAttempt,
        controlSession: Any,
        source: MfiAuthenticator,
    ) {
        synchronized(lock) {
            requireNoRevocationFailuresLocked()
            requireAuthenticationSourceLocked(source)
            requireCurrentOrPendingAttemptLocked(attempt, controlSession)
            attempt.claimExchangeOnce()
        }
    }

    internal fun requireLiveExchange(
        attempt: MfiAuthenticationAttempt,
        controlSession: Any,
        source: MfiAuthenticator,
    ) {
        synchronized(lock) {
            requireNoRevocationFailuresLocked()
            requireAuthenticationSourceLocked(source)
            check(attempt.hasClaimedExchange) { "MFi authentication exchange was not claimed" }
            requireCurrentOrPendingAttemptLocked(attempt, controlSession)
        }
    }

    private fun issueLocked(
        attempt: MfiAuthenticationAttempt,
        controlSession: Any,
        identity: MfiAuthenticationIdentity,
    ): MfiAuthenticationReceipt {
        val isActiveAttempt = active === attempt
        val isPendingSuccessor = pendingHandoff?.successorAttempt === attempt
        check(
            !closed && (isActiveAttempt || isPendingSuccessor) && attempt.isLive &&
                attempt.matches(controlSession, authenticationIdentity) &&
                identity == authenticationIdentity,
        ) { "MFi authentication attempt is stale or belongs to another session" }
        return attempt.installReceipt(identity)
    }

    private fun requireAuthenticationSourceLocked(source: MfiAuthenticator) {
        check(authenticationSource === source) {
            "MFi authentication source belongs to another MFi session"
        }
    }

    private fun requireCurrentOrPendingAttemptLocked(
        attempt: MfiAuthenticationAttempt,
        controlSession: Any,
    ) {
        check(
            !closed &&
                (active === attempt || pendingHandoff?.successorAttempt === attempt) &&
                attempt.matches(controlSession, authenticationIdentity),
        ) { "MFi authentication attempt is stale or belongs to another session" }
    }

    internal fun commitHandoff(
        handoff: MfiAuthenticationHandoff,
        successorReceipt: MfiAuthenticationReceipt,
        onPromoted: (MfiAuthenticationReceipt) -> Unit,
    ): MfiAuthenticationReceipt {
        val reservation = synchronized(lock) {
            requireNoRevocationFailuresLocked()
            val pending = pendingHandoff
            check(
                !closed && handoffCommitInProgress == null && pending?.handoff === handoff &&
                    active === pending.predecessorAttempt &&
                    pending.predecessorAttempt.isLive &&
                    pending.predecessorAttempt.hasReceipt(pending.predecessorReceipt) &&
                    pending.predecessorReceipt.isUsableHandoffPredecessor() &&
                    pending.successorAttempt.isLive &&
                    pending.successorAttempt.hasReceipt(successorReceipt) &&
                    successorReceipt.authenticationIdentity == authenticationIdentity &&
                    successorReceipt.isUnconsumed(),
            ) { "MFi authentication handoff is stale or incomplete" }

            active = pending.successorAttempt
            pendingHandoff = null
            handoffCommitInProgress = pending
            pending
        }

        var promotionFailure: Throwable? = try {
            onPromoted(successorReceipt)
            null
        } catch (failure: Throwable) {
            failure
        }
        val revocations = mutableListOf<() -> Unit>()
        synchronized(lock) {
            if (handoffCommitInProgress !== reservation) {
                if (promotionFailure == null) {
                    promotionFailure = IllegalStateException(
                        "MFi authentication handoff was interrupted during promotion",
                    )
                }
            } else if (reservation.interruption != null) {
                if (promotionFailure == null) promotionFailure = reservation.interruption
                handoffCommitInProgress = null
                revocations.addAll(reservation.deferredRevocations)
            } else if (
                promotionFailure == null && !closed &&
                active === reservation.successorAttempt &&
                reservation.successorAttempt.isLive &&
                successorReceipt.isConsumedForHandoff(reservation.predecessorReceipt)
            ) {
                handoffCommitInProgress = null
                reservation.predecessorAttempt
                    .markRevokedFromAuthority()
                    ?.let(revocations::add)
            } else {
                if (promotionFailure == null) {
                    promotionFailure = IllegalStateException(
                        "MFi authentication handoff promotion did not consume the successor receipt",
                    )
                }
                handoffCommitInProgress = null
                if (!closed &&
                    (active === reservation.successorAttempt || active == null) &&
                    reservation.predecessorAttempt.isLive &&
                    reservation.predecessorReceipt.isUsableHandoffPredecessor()
                ) {
                    active = reservation.predecessorAttempt
                }
                reservation.successorAttempt
                    .markRevokedFromAuthority()
                    ?.let(revocations::add)
            }
            reserveRevocationsLocked(revocations)
        }
        fun failClosePromotedSuccessor(): List<() -> Unit> {
            val successorRevocations = mutableListOf<() -> Unit>()
            synchronized(lock) {
                if (active === reservation.successorAttempt) active = null
                reservation.successorAttempt
                    .markRevokedFromAuthority()
                    ?.let(successorRevocations::add)
                reserveRevocationsLocked(successorRevocations)
            }
            return successorRevocations
        }
        fun failCloseRestoredPredecessor(): List<() -> Unit> {
            val predecessorRevocations = mutableListOf<() -> Unit>()
            synchronized(lock) {
                if (active === reservation.predecessorAttempt) active = null
                reservation.predecessorAttempt
                    .markRevokedFromAuthority()
                    ?.let(predecessorRevocations::add)
                reserveRevocationsLocked(predecessorRevocations)
            }
            return predecessorRevocations
        }
        val revocationFailures = invokeReservedRevocations(revocations)
        if (promotionFailure == null && revocationFailures.isNotEmpty()) {
            val allFailures = revocationFailures +
                invokeReservedRevocations(failClosePromotedSuccessor())
            propagateErrors(allFailures)
            throw IllegalStateException(
                "MFi predecessor revocation failed after handoff promotion; successor failed closed",
                revocationFailures.first(),
            )
        }
        val failedPromotion = promotionFailure
        if (failedPromotion != null && revocationFailures.isNotEmpty()) {
            val allFailures = revocationFailures +
                invokeReservedRevocations(failCloseRestoredPredecessor())
            propagateErrors(allFailures)
            allFailures.forEach(failedPromotion::addSuppressed)
        }
        propagateErrors(revocationFailures)
        promotionFailure?.let { throw it }
        return successorReceipt
    }

    internal fun cancelHandoff(handoff: MfiAuthenticationHandoff) {
        val callbacks = mutableListOf<() -> Unit>()
        synchronized(lock) {
            val pending = pendingHandoff
            val committing = handoffCommitInProgress
            when {
                pending?.handoff === handoff -> {
                    pendingHandoff = null
                    pending.successorAttempt.markRevokedFromAuthority()?.let(callbacks::add)
                }
                committing?.handoff === handoff -> {
                    if (!closed && active === committing.successorAttempt &&
                        committing.predecessorAttempt.isLive &&
                        committing.predecessorReceipt.isUsableHandoffPredecessor()
                    ) {
                        active = committing.predecessorAttempt
                    }
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    committing.interrupt("MFi authentication handoff was cancelled during promotion")
                }
            }
            reserveRevocationsLocked(callbacks)
        }
        propagateErrors(invokeReservedRevocations(callbacks))
    }

    internal fun consume(
        attempt: MfiAuthenticationAttempt,
        receipt: MfiAuthenticationReceipt,
        onRevoked: () -> Unit,
    ): MfiAuthenticationIdentity? = synchronized(lock) {
        if (hasPendingRevocationCleanupLocked()) return@synchronized null
        if (closed || active !== attempt || !attempt.isLive) return@synchronized null
        if (handoffCommitInProgress?.successorAttempt === attempt) return@synchronized null
        attempt.consume(receipt, onRevoked)
    }

    internal fun consumeHandoff(
        attempt: MfiAuthenticationAttempt,
        receipt: MfiAuthenticationReceipt,
        predecessor: MfiAuthenticationReceipt,
        onRevoked: () -> Unit,
    ): MfiAuthenticationIdentity? = synchronized(lock) {
        if (hasPendingRevocationCleanupLocked()) return@synchronized null
        val committing = handoffCommitInProgress
        if (closed || active !== attempt || !attempt.isLive ||
            committing?.successorAttempt !== attempt ||
            committing.predecessorReceipt !== predecessor ||
            committing.predecessorAttempt.hasReceipt(predecessor).not() ||
            !committing.predecessorAttempt.isLive ||
            !predecessor.isUsableHandoffPredecessor()
        ) {
            return@synchronized null
        }
        attempt.consume(receipt, onRevoked, predecessor)
    }

    internal fun revokeReceipt(
        attempt: MfiAuthenticationAttempt,
        receipt: MfiAuthenticationReceipt,
    ) {
        val callbacks = mutableListOf<() -> Unit>()
        synchronized(lock) {
            val committing = handoffCommitInProgress
            when {
                committing?.predecessorAttempt === attempt -> {
                    active = null
                    committing.defer(attempt.markReceiptRevoked(receipt))
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    committing.interrupt(
                        "MFi authentication predecessor was revoked during promotion",
                    )
                }
                committing?.successorAttempt === attempt -> {
                    active = if (!closed &&
                        committing.predecessorAttempt.isLive &&
                        committing.predecessorReceipt.isUsableHandoffPredecessor()
                    ) {
                        committing.predecessorAttempt
                    } else {
                        null
                    }
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    committing.interrupt(
                        "MFi authentication successor was revoked during promotion",
                    )
                }
                active === attempt -> {
                    attempt.markReceiptRevoked(receipt)?.let(callbacks::add)
                    val pending = pendingHandoff
                    if (pending?.predecessorAttempt === attempt) {
                        pendingHandoff = null
                        pending.successorAttempt
                            .markRevokedFromAuthority()
                            ?.let(callbacks::add)
                    }
                }
                pendingHandoff?.successorAttempt === attempt -> {
                    pendingHandoff = null
                    attempt.markRevokedFromAuthority()?.let(callbacks::add)
                }
            }
            reserveRevocationsLocked(callbacks)
        }
        propagateErrors(invokeReservedRevocations(callbacks))
    }

    internal fun isLive(
        attempt: MfiAuthenticationAttempt,
        receipt: MfiAuthenticationReceipt,
    ): Boolean = synchronized(lock) {
        !hasPendingRevocationCleanupLocked() && !closed && active === attempt &&
            attempt.isLive && attempt.hasReceipt(receipt)
    }

    internal fun end(attempt: MfiAuthenticationAttempt) {
        val callbacks = mutableListOf<() -> Unit>()
        synchronized(lock) {
            val committing = handoffCommitInProgress
            when {
                committing?.successorAttempt === attempt -> {
                    committing.defer(attempt.markRevokedFromAuthority())
                    active = if (!closed &&
                        committing.predecessorAttempt.isLive &&
                        committing.predecessorReceipt.isUsableHandoffPredecessor()
                    ) {
                        committing.predecessorAttempt
                    } else {
                        null
                    }
                    committing.interrupt(
                        "MFi authentication successor ended during promotion",
                    )
                }
                committing?.predecessorAttempt === attempt -> {
                    committing.defer(attempt.markRevokedFromAuthority())
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    active = null
                    committing.interrupt(
                        "MFi authentication predecessor ended during promotion",
                    )
                }
                active === attempt -> {
                    attempt.markRevokedFromAuthority()?.let(callbacks::add)
                    active = null
                    pendingHandoff?.successorAttempt
                        ?.markRevokedFromAuthority()
                        ?.let(callbacks::add)
                    pendingHandoff = null
                }
                pendingHandoff?.successorAttempt === attempt -> {
                    attempt.markRevokedFromAuthority()?.let(callbacks::add)
                    pendingHandoff = null
                }
            }
            reserveRevocationsLocked(callbacks)
        }
        propagateErrors(invokeReservedRevocations(callbacks))
    }

    fun invalidate(controlGeneration: Long) {
        require(controlGeneration >= 0L) { "MFi control generation must not be negative" }
        val callbacks = mutableListOf<() -> Unit>()
        synchronized(lock) {
            val pending = pendingHandoff
            val committing = handoffCommitInProgress
            when {
                controlGeneration > highestControlGeneration -> {
                    highestControlGeneration = controlGeneration
                    if (committing == null) {
                        active?.markRevokedFromAuthority()?.let(callbacks::add)
                        active = null
                    } else {
                        committing.defer(committing.predecessorAttempt.markRevokedFromAuthority())
                        committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                        committing.interrupt(
                            "MFi authentication authority was invalidated during promotion",
                        )
                        active = null
                    }
                    pending?.successorAttempt
                        ?.markRevokedFromAuthority()
                        ?.let(callbacks::add)
                    pendingHandoff = null
                }
                pending != null &&
                    pending.successorAttempt.controlGeneration == controlGeneration -> {
                    pending.successorAttempt.markRevokedFromAuthority()?.let(callbacks::add)
                    pendingHandoff = null
                }
                committing != null &&
                    committing.predecessorAttempt.controlGeneration == controlGeneration -> {
                    committing.defer(committing.predecessorAttempt.markRevokedFromAuthority())
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    committing.interrupt(
                        "MFi authentication predecessor generation ended during promotion",
                    )
                    active = null
                }
                committing != null &&
                    committing.successorAttempt.controlGeneration == controlGeneration -> {
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    committing.interrupt(
                        "MFi authentication successor generation ended during promotion",
                    )
                    active = if (!closed &&
                        committing.predecessorAttempt.isLive &&
                        committing.predecessorReceipt.isUsableHandoffPredecessor()
                    ) {
                        committing.predecessorAttempt
                    } else {
                        null
                    }
                }
                active?.controlGeneration == controlGeneration -> {
                    val invalidated = active
                    invalidated?.markRevokedFromAuthority()?.let(callbacks::add)
                    active = null
                    pending?.takeIf { it.predecessorAttempt === invalidated }?.let {
                        it.successorAttempt.markRevokedFromAuthority()?.let(callbacks::add)
                        pendingHandoff = null
                    }
                }
            }
            reserveRevocationsLocked(callbacks)
        }
        propagateErrors(invokeReservedRevocations(callbacks))
    }

    override fun close() = synchronized(closeCallLock) {
        closeOnce()
    }

    /** Replays only callbacks retained by this authority; no new receipt may issue during replay. */
    fun retryRevocationCleanup() = synchronized(closeCallLock) {
        synchronized(lock) {
            check(!closed) { "MFi receipt authority is closed" }
            check(revocationBatchesInFlight == 0) {
                "MFi authentication revocation cleanup is already in progress"
            }
        }
        val failures = retryFailedRevocations()
        propagateErrors(failures)
        throwIfRevocationFailed()
    }

    private fun closeOnce() {
        val callbacks = mutableListOf<() -> Unit>()
        synchronized(lock) {
            if (!closed) {
                closed = true
                authenticationSource = null
                val committing = handoffCommitInProgress
                if (committing == null) {
                    active?.markRevokedFromAuthority()?.let(callbacks::add)
                } else {
                    committing.defer(committing.predecessorAttempt.markRevokedFromAuthority())
                    committing.defer(committing.successorAttempt.markRevokedFromAuthority())
                    committing.interrupt("MFi authentication authority closed during promotion")
                }
                active = null
                pendingHandoff?.successorAttempt
                    ?.markRevokedFromAuthority()
                    ?.let(callbacks::add)
                pendingHandoff = null
            }
            reserveRevocationsLocked(callbacks)
        }
        val revocationFailures = retryFailedRevocations() +
            invokeReservedRevocations(callbacks)
        propagateErrors(revocationFailures)
        val cleanupPending = synchronized(lock) {
            handoffCommitInProgress != null || revocationBatchesInFlight != 0
        }
        check(!cleanupPending) {
            "MFi authentication revocation is waiting for promotion callback completion"
        }
        throwIfRevocationFailed()
    }

    private fun invokeRevocation(callback: (() -> Unit)?): Throwable? {
        if (callback == null) return null
        return try {
            callback()
            null
        } catch (failure: Throwable) {
            synchronized(lock) {
                failedRevocations.addLast(FailedRevocation(callback, failure))
            }
            failure
        }
    }

    private fun invokeRevocations(callbacks: List<() -> Unit>): List<Throwable> {
        return callbacks.mapNotNull(::invokeRevocation)
    }

    private fun reserveRevocationsLocked(callbacks: List<() -> Unit>) {
        if (callbacks.isNotEmpty()) {
            check(revocationBatchesInFlight != Int.MAX_VALUE) {
                "MFi authentication revocation reservation exhausted"
            }
            revocationBatchesInFlight += 1
        }
    }

    private fun invokeReservedRevocations(callbacks: List<() -> Unit>): List<Throwable> {
        if (callbacks.isEmpty()) return emptyList()
        return try {
            invokeRevocations(callbacks)
        } finally {
            synchronized(lock) {
                check(revocationBatchesInFlight > 0) {
                    "MFi authentication revocation reservation is missing"
                }
                revocationBatchesInFlight -= 1
            }
        }
    }

    private fun propagateErrors(failures: List<Throwable>) {
        val firstError = failures.firstOrNull { it is Error } as? Error
        if (firstError != null) {
            failures.filterIsInstance<Error>()
                .filterNot { it === firstError }
                .forEach(firstError::addSuppressed)
            throw firstError
        }
    }

    private fun retryFailedRevocations(): List<Throwable> {
        val callbacks = synchronized(lock) {
            val selected = failedRevocations.map(FailedRevocation::callback)
            reserveRevocationsLocked(selected)
            failedRevocations.clear()
            selected
        }
        return invokeReservedRevocations(callbacks)
    }

    private fun requireNoRevocationFailuresLocked() {
        check(!hasPendingRevocationCleanupLocked()) {
            "MFi authentication revocation cleanup is pending retry"
        }
    }

    private fun hasPendingRevocationCleanupLocked(): Boolean =
        failedRevocations.isNotEmpty() || revocationBatchesInFlight != 0

    private fun throwIfRevocationFailed() {
        val failure = synchronized(lock) { failedRevocations.firstOrNull()?.failure }
        if (failure != null) {
            throw IllegalStateException(
                "MFi authentication revocation cleanup failed and remains retryable",
                failure,
            )
        }
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            authenticationIdentity: MfiAuthenticationIdentity,
        ): MfiAuthenticationReceiptAuthority =
            MfiAuthenticationReceiptAuthority(authenticationIdentity)
    }
}

/**
 * One pending, predecessor-bound transition between control channels of the same owning MFi
 * session.
 *
 * The AA05 receipt produced through this object cannot be consumed until [commit] atomically
 * promotes it. Closing or cancelling an uncommitted handoff preserves the predecessor receipt.
 */
class MfiAuthenticationHandoff private constructor(
    private val authority: MfiAuthenticationReceiptAuthority,
    private val successorAttempt: MfiAuthenticationAttempt,
    permit: Any,
) : Closeable {
    init {
        require(permit === HANDOFF_PERMIT) { "MFi handoff construction is issuer-gated" }
    }

    /**
     * Makes the exact successor current, invokes [onPromoted] while predecessor revocation is
     * deferred, and only then revokes the predecessor. [onPromoted] must synchronously consume
     * [successorReceipt] through [MfiAuthenticationReceipt.consumeForUltraHandoffRenewal] using
     * the exact predecessor and register it; throwing or returning without that lineage-bound
     * consumption rolls the promotion back and preserves the predecessor.
     */
    fun commit(
        successorReceipt: MfiAuthenticationReceipt,
        onPromoted: (MfiAuthenticationReceipt) -> Unit,
    ): MfiAuthenticationReceipt =
        authority.commitHandoff(this, successorReceipt, onPromoted)

    /** Abandons only this pending successor; the live predecessor remains authoritative. */
    fun cancel() {
        authority.cancelHandoff(this)
    }

    override fun close() = cancel()

    internal fun attemptForExchange(): MfiAuthenticationAttempt = successorAttempt

    override fun toString(): String = "MfiAuthenticationHandoff(<redacted>)"

    companion object {
        private val HANDOFF_PERMIT = Any()

        @JvmSynthetic
        internal fun create(
            authority: MfiAuthenticationReceiptAuthority,
            successorAttempt: MfiAuthenticationAttempt,
        ): MfiAuthenticationHandoff =
            MfiAuthenticationHandoff(authority, successorAttempt, HANDOFF_PERMIT)
    }
}

/** Exact MFi-session and iAP2-control generation allowed to complete one AA05 exchange. */
class MfiAuthenticationAttempt internal constructor(
    private val authority: MfiAuthenticationReceiptAuthority,
    private val controlSession: Any,
    private val authenticationIdentity: MfiAuthenticationIdentity,
    private val authorityGeneration: Long,
    internal val controlGeneration: Long,
) : Closeable {
    private val live = AtomicBoolean(true)
    private val exchangeClaimed = AtomicBoolean(false)
    private val installedReceipt = AtomicReference<MfiAuthenticationReceipt?>(null)

    internal val isLive: Boolean get() = live.get()
    internal val hasClaimedExchange: Boolean get() = exchangeClaimed.get()

    internal fun matches(
        session: Any,
        identity: MfiAuthenticationIdentity,
    ): Boolean =
        live.get() && controlSession === session && authenticationIdentity == identity &&
            authorityGeneration > 0L && controlGeneration >= 0L

    @JvmSynthetic
    internal fun issue(
        session: Any,
        identity: MfiAuthenticationIdentity,
    ): MfiAuthenticationReceipt = authority.issue(this, session, identity)

    @JvmSynthetic
    internal fun issue(
        session: Any,
        identity: MfiAuthenticationIdentity,
        source: MfiAuthenticator,
        permit: Any,
    ): MfiAuthenticationReceipt = authority.issue(this, session, identity, source, permit)

    internal fun claimExchange(
        session: Any,
        source: MfiAuthenticator,
    ) {
        authority.claimExchange(this, session, source)
    }

    internal fun requireLiveExchange(
        session: Any,
        source: MfiAuthenticator,
    ) {
        authority.requireLiveExchange(this, session, source)
    }

    internal fun claimExchangeOnce() {
        check(exchangeClaimed.compareAndSet(false, true)) {
            "MFi authentication attempt already has an exchange"
        }
    }

    internal fun installReceipt(identity: MfiAuthenticationIdentity): MfiAuthenticationReceipt {
        check(live.get() && identity == authenticationIdentity)
        val receipt = MfiAuthenticationReceipt.create(identity, this)
        check(installedReceipt.compareAndSet(null, receipt)) {
            "MFi authentication attempt already issued a receipt"
        }
        return receipt
    }

    internal fun consume(
        receipt: MfiAuthenticationReceipt,
        onRevoked: () -> Unit,
        handoffPredecessor: MfiAuthenticationReceipt? = null,
    ): MfiAuthenticationIdentity? {
        if (installedReceipt.get() !== receipt || !live.get()) return null
        return receipt.consumeState(onRevoked, handoffPredecessor)
    }

    internal fun consumeThroughAuthority(
        receipt: MfiAuthenticationReceipt,
        onRevoked: () -> Unit,
    ): MfiAuthenticationIdentity? = authority.consume(this, receipt, onRevoked)

    internal fun consumeHandoffThroughAuthority(
        receipt: MfiAuthenticationReceipt,
        predecessor: MfiAuthenticationReceipt,
        onRevoked: () -> Unit,
    ): MfiAuthenticationIdentity? =
        authority.consumeHandoff(this, receipt, predecessor, onRevoked)

    internal fun isLiveThroughAuthority(receipt: MfiAuthenticationReceipt): Boolean =
        authority.isLive(this, receipt)

    internal fun markReceiptRevoked(receipt: MfiAuthenticationReceipt): (() -> Unit)? =
        if (installedReceipt.get() === receipt) receipt.markRevokedState() else null

    internal fun hasReceipt(receipt: MfiAuthenticationReceipt): Boolean =
        installedReceipt.get() === receipt

    internal fun revokeThroughAuthority(receipt: MfiAuthenticationReceipt) {
        authority.revokeReceipt(this, receipt)
    }

    internal fun markRevokedFromAuthority(): (() -> Unit)? {
        live.set(false)
        return installedReceipt.get()?.markRevokedState()
    }

    override fun close() {
        authority.end(this)
    }
}

/** Proof object emitted only after the phone sends iAP2 AuthenticationSucceeded (0xAA05). */
class MfiAuthenticationReceipt private constructor(
    val authenticationIdentity: MfiAuthenticationIdentity,
    private val attempt: MfiAuthenticationAttempt,
    permit: Any,
) {
    private sealed interface State {
        object Active : State
        class Consumed(
            val onRevoked: () -> Unit,
            val handoffPredecessor: MfiAuthenticationReceipt?,
        ) : State
        object Revoked : State
    }

    private val state = AtomicReference<State>(State.Active)

    init {
        require(permit === RECEIPT_PERMIT) { "MFi receipt construction is issuer-gated" }
    }

    fun consumeForUltraActivation(
        onRevoked: () -> Unit,
    ): MfiAuthenticationIdentity? = attempt.consumeThroughAuthority(this, onRevoked)

    /**
     * Consumes this receipt only inside the exact same-authority promotion from [predecessor].
     * This proof cannot be replayed after the promotion callback returns.
     */
    fun consumeForUltraHandoffRenewal(
        predecessor: MfiAuthenticationReceipt,
        onRevoked: () -> Unit,
    ): MfiAuthenticationIdentity? =
        attempt.consumeHandoffThroughAuthority(this, predecessor, onRevoked)

    fun isLiveForUltraActivation(): Boolean = state.get() is State.Consumed

    internal fun isCurrentAuthenticationAttempt(): Boolean =
        state.get() === State.Active && attempt.isLiveThroughAuthority(this)

    internal fun consumeState(
        onRevoked: () -> Unit,
        handoffPredecessor: MfiAuthenticationReceipt? = null,
    ): MfiAuthenticationIdentity? {
        return if (
            state.compareAndSet(
                State.Active,
                State.Consumed(onRevoked, handoffPredecessor),
            )
        ) {
            authenticationIdentity
        } else {
            null
        }
    }

    internal fun revoke() {
        attempt.revokeThroughAuthority(this)
    }

    internal fun markRevokedState(): (() -> Unit)? {
        val previous = state.getAndSet(State.Revoked)
        return (previous as? State.Consumed)?.onRevoked
    }

    internal fun isUsableHandoffPredecessor(): Boolean = state.get() !is State.Revoked

    internal fun isUnconsumed(): Boolean = state.get() === State.Active

    internal fun isConsumedForHandoff(predecessor: MfiAuthenticationReceipt): Boolean =
        (state.get() as? State.Consumed)?.handoffPredecessor === predecessor

    override fun toString(): String = "MfiAuthenticationReceipt(<redacted>)"

    companion object {
        private val RECEIPT_PERMIT = Any()

        @JvmSynthetic
        internal fun create(
            authenticationIdentity: MfiAuthenticationIdentity,
            attempt: MfiAuthenticationAttempt,
        ): MfiAuthenticationReceipt =
            MfiAuthenticationReceipt(authenticationIdentity, attempt, RECEIPT_PERMIT)
    }
}

/** Strict message-order state for one certificate/challenge/AA05 exchange. */
internal class Iap2MfiAuthenticationExchangeState {
    private enum class Phase { INITIAL, CERTIFICATE_SENT, CHALLENGE_RESPONSE_SENT }

    private var phase = Phase.INITIAL

    fun requireCertificateRequest() {
        if (phase != Phase.INITIAL) {
            throw Iap2MfiAuthenticationException(
                "iAP2 MFi certificate request is duplicated or out of order",
            )
        }
    }

    fun certificateSent() {
        check(phase == Phase.INITIAL)
        phase = Phase.CERTIFICATE_SENT
    }

    fun requireChallengeRequest() {
        if (phase != Phase.CERTIFICATE_SENT) {
            throw Iap2MfiAuthenticationException("iAP2 MFi challenge request is out of order")
        }
    }

    fun challengeResponseSent() {
        check(phase == Phase.CERTIFICATE_SENT)
        phase = Phase.CHALLENGE_RESPONSE_SENT
    }

    fun requireAuthenticationSucceeded() {
        if (phase != Phase.CHALLENGE_RESPONSE_SENT) {
            throw Iap2MfiAuthenticationException(
                "iAP2 MFi AuthenticationSucceeded arrived before challenge response",
            )
        }
    }
}

/** Runs one authority-bound iAP2 CSM MFi exchange without owning [Iap2Session]. */
class Iap2MfiAuthenticationClient(
    private val authentication: MfiAuthenticator,
    private val maximumCertificateLength: Int = MfiAuthenticationClient.DEFAULT_MAXIMUM_CERTIFICATE_OUTPUT_LENGTH,
) {
    init {
        require(maximumCertificateLength in 1..MAX_CSM_CERTIFICATE_BYTES) {
            "maximumCertificateLength must be in 1..$MAX_CSM_CERTIFICATE_BYTES"
        }
    }

    /** Blocks until the phone confirms AA05, or throws a typed authentication failure. */
    fun run(
        session: Iap2Session,
        authenticationAttempt: MfiAuthenticationAttempt,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ): MfiAuthenticationReceipt {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }
        authenticationAttempt.claimExchange(session, authentication)
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val certificateType = authentication.certificateType
        val certificate = authentication.readCertificate(maximumCertificateLength)
        val baaPair = if (certificateType == MfiCertificateType.BAA) {
            authentication.baaCertificates().also { pair ->
                val expected = Iap2AuthenticationMessages.baaCertificatePackage(
                    pair.leaf,
                    pair.intermediate,
                ).payload
                if (!MessageDigest.isEqual(certificate, expected)) {
                    throw MfiInvalidDataException(
                        "BAA certificate package changed before iAP2 authentication",
                    )
                }
            }
        } else {
            null
        }
        val authenticationIdentity = MfiAuthenticationIdentity.fromCaptured(
            certificateType,
            certificate,
            baaPair,
        )
        authenticationAttempt.requireLiveExchange(session, authentication)
        val certificateFrame = when (certificateType) {
            MfiCertificateType.MFI -> Iap2AuthenticationMessages.accessoryCertificate(certificate)
            MfiCertificateType.BAA -> Iap2AuthenticationMessages.accessoryCertificateBody(certificate)
        }
        val certificatePayloadBytes = certificateFrame.payload.size
        onProgress(
            "mfi type=$certificateType " +
                "certificate loaded bytes=${certificate.size} " +
                "payload bytes=$certificatePayloadBytes",
        )
        val exchange = Iap2MfiAuthenticationExchangeState()
        while (true) {
            authenticationAttempt.requireLiveExchange(session, authentication)
            val remaining = remainingMillis(deadlineNanos)
            if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
            val frame = session.recv(remaining)
                ?: throw Iap2MfiAuthenticationException("Timed out waiting for iAP2 MFi authentication")
            when (frame.messageId) {
                REQUEST_CERTIFICATE -> {
                    exchange.requireCertificateRequest()
                    onProgress("iap2 mfi rx=0xaa00 request-certificate")
                    authenticationAttempt.requireLiveExchange(session, authentication)
                    send(session, certificateFrame, deadlineNanos)
                    exchange.certificateSent()
                    onProgress("iap2 mfi tx=0xaa01 certificate bytes=$certificatePayloadBytes")
                }
                REQUEST_CHALLENGE -> {
                    exchange.requireChallengeRequest()
                    val challenge = requireParameterZero(frame)
                    if (challenge.size !in 1..MAXIMUM_CHALLENGE_BYTES) {
                        throw Iap2MfiAuthenticationException("Invalid iAP2 MFi challenge length ${challenge.size}")
                    }
                    onProgress("iap2 mfi rx=0xaa02 challenge bytes=${challenge.size}")
                    authenticationAttempt.requireLiveExchange(session, authentication)
                    val signature = authentication.signChallenge(challenge)
                    authenticationAttempt.requireLiveExchange(session, authentication)
                    send(session, Iap2AuthenticationMessages.response(signature), deadlineNanos)
                    exchange.challengeResponseSent()
                    onProgress("iap2 mfi tx=0xaa03 signature bytes=${signature.size}")
                }
                AUTHENTICATION_SUCCEEDED -> {
                    exchange.requireAuthenticationSucceeded()
                    onProgress("iap2 mfi rx=0xaa05 authentication-succeeded")
                    authenticationAttempt.requireLiveExchange(session, authentication)
                    return authenticationAttempt.issue(
                        session,
                        authenticationIdentity,
                        authentication,
                        AA05_RECEIPT_ISSUE_PERMIT,
                    )
                }
                AUTHENTICATION_FAILED -> throw Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")
                else -> throw Iap2MfiAuthenticationException(
                    "Unexpected iAP2 MFi message 0x${frame.messageId.toString(16).padStart(4, '0')}",
                )
            }
        }
    }

    /**
     * Runs AA05 for a pending same-session handoff. Any exchange failure cancels only the pending
     * successor; a successful receipt remains non-current until the caller invokes [MfiAuthenticationHandoff.commit].
     */
    fun run(
        session: Iap2Session,
        authenticationHandoff: MfiAuthenticationHandoff,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onProgress: (String) -> Unit = {},
    ): MfiAuthenticationReceipt = try {
        run(
            session = session,
            authenticationAttempt = authenticationHandoff.attemptForExchange(),
            timeoutMillis = timeoutMillis,
            onProgress = onProgress,
        )
    } catch (failure: Throwable) {
        authenticationHandoff.cancel()
        throw failure
    }

    private fun send(
        session: Iap2Session,
        frame: Iap2Frame,
        deadlineNanos: Long,
    ) {
        val remaining = remainingMillis(deadlineNanos)
        if (remaining == 0L) throw Iap2MfiAuthenticationException("Timed out sending iAP2 MFi authentication reply")
        session.send(frame, remaining)
    }

    private fun requireParameterZero(frame: Iap2Frame): ByteArray {
        return try {
            Iap2AuthenticationMessages.challenge(frame)
        } catch (failure: Exception) {
            throw Iap2MfiAuthenticationException("iAP2 MFi challenge is missing parameter 0", failure)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingMillis(deadlineNanos: Long): Long {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return 0
        return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            .coerceAtMost(MAX_TIMEOUT_MILLIS)
    }

    companion object {
        private const val REQUEST_CERTIFICATE = 0xaa00
        private const val CERTIFICATE = 0xaa01
        private const val REQUEST_CHALLENGE = 0xaa02
        private const val RESPONSE = 0xaa03
        private const val AUTHENTICATION_FAILED = 0xaa04
        private const val AUTHENTICATION_SUCCEEDED = 0xaa05
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val MAX_CSM_CERTIFICATE_BYTES = 65_525
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/** A malformed, rejected, unexpected, or timed-out iAP2 MFi authentication exchange. */
class Iap2MfiAuthenticationException(message: String, cause: Throwable? = null) : MfiException(message, cause)
