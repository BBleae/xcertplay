package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.UltraRevealEvent
import com.shilapi.xcertplay.airplay.UltraRevealTrace
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** An exact permit can be temporarily unavailable without having been revoked. */
internal enum class MainPhysicalUiPermitStatus { CURRENT, BUSY, REVOKED }

/**
 * The MAIN window starts with its video visible. Only an exact physical-output handoff asks
 * this gate to put an opaque UI layer over that video. This is UI obscuration, not a hardware
 * blanking or scan-out proof.
 */
internal class MainPhysicalUiCoverGate(
    private val onMissedUltraFrame: (expectedNanos: Long, observedNanos: Long) -> Unit = { _, _ -> },
    private val applyCovered: (sequence: Long, covered: Boolean) -> Boolean,
) {
    private class FramePermit(
        val timestampNanos: Long,
        val authorization: () -> MainPhysicalUiPermitStatus,
        val onMissedFrame: (observedNanos: Long) -> Unit,
        var newerObservedTimestampNanos: Long? = null,
        var missedReported: Boolean = false,
    )

    private val lock = Any()
    private var sequence = 0L
    private var covered = false
    private var coverApplied = false
    private var retired = false
    private var ultraClaimed = false
    private var ultraArmInProgress = false
    private var latestPermitTimestampNanos = 0L
    private var permit: FramePermit? = null
    private var pendingUltraPermit: FramePermit? = null
    private var ordinaryPermit: (() -> Boolean)? = null
    /** Debug trace de-duplication only: one line per permit and denial reason. */
    private var lastTracedDenial: Pair<Any?, String>? = null

    private fun traceDenial(permit: Any?, reason: String) {
        if (!UltraRevealTrace.enabled) return
        val key = permit to reason
        if (synchronized(lock) { (lastTracedDenial == key).also { lastTracedDenial = key } })
            return
        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE, "state" to "DENIED",
            "reason" to reason)
    }

    val currentSequence: Long get() = synchronized(lock) { sequence }
    val isCovered: Boolean get() = synchronized(lock) { covered }
    val hasAppliedCover: Boolean get() = synchronized(lock) { covered && coverApplied }
    val hasBeenUsed: Boolean get() = synchronized(lock) { ultraClaimed || covered }

    private fun status(permit: FramePermit): MainPhysicalUiPermitStatus =
        runCatching(permit.authorization).getOrDefault(MainPhysicalUiPermitStatus.BUSY)

    /** An old queued reveal cannot overtake a newer cover, including after a UI timeout. */
    fun cover(retire: Boolean = false): Boolean = coverInternal(retire, preservePermit = false)

    private fun coverInternal(
        retire: Boolean,
        preservePermit: Boolean,
        expectedPermit: FramePermit? = null,
        expectedSequence: Long? = null,
        guardPermit: Boolean = false,
    ): Boolean {
        val serial = synchronized(lock) {
            if (guardPermit && permit !== expectedPermit) return true
            if (guardPermit && pendingUltraPermit != null &&
                pendingUltraPermit !== expectedPermit
            ) return true
            if (expectedSequence != null && sequence != expectedSequence) return true
            if (retire) retired = true
            if (retired && covered && coverApplied) return true
            if (!covered) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE,
                "state" to "COVERED", "retire" to retired, "keepPermit" to preservePermit)
            sequence = Math.addExact(sequence, 1L)
            covered = true
            coverApplied = false
            if (!preservePermit) {
                permit = null
                pendingUltraPermit = null
                ordinaryPermit = null
            }
            sequence
        }
        val applied = runCatching { applyCovered(serial, true) }.getOrDefault(false)
        synchronized(lock) {
            if (sequence == serial && covered && applied) coverApplied = true
        }
        return applied
    }

    /** Ordinary CarPlay and WebDisplay never acquire a visible overlay during retirement. */
    fun retireIfUsed(): Boolean {
        synchronized(lock) {
            if (!ultraClaimed && !covered) {
                retired = true
                permit = null
                pendingUltraPermit = null
                ordinaryPermit = null
                return true
            }
        }
        return cover(retire = true)
    }

    fun claimUltra(): Boolean {
        synchronized(lock) {
            if (retired) return false
            ultraClaimed = true
        }
        return cover()
    }

    fun armUltra(timestampNanos: Long, stillAuthorized: () -> Boolean): Boolean =
        armUltraWithStatus(timestampNanos, authorization = {
            if (stillAuthorized()) MainPhysicalUiPermitStatus.CURRENT
            else MainPhysicalUiPermitStatus.REVOKED
        })

    fun armUltraWithStatus(
        timestampNanos: Long,
        onMissedFrame: (observedNanos: Long) -> Unit = {},
        authorization: () -> MainPhysicalUiPermitStatus,
    ): Boolean {
        val candidate = FramePermit(timestampNanos, authorization, onMissedFrame)
        if (status(candidate) == MainPhysicalUiPermitStatus.REVOKED) {
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE, "state" to "ARM_REJECTED",
                "reason" to "PERMIT_REVOKED")
            return false
        }
        val needsCover = synchronized(lock) {
            if (retired || !ultraClaimed || ultraArmInProgress ||
                timestampNanos <= 0L || timestampNanos <= latestPermitTimestampNanos
            ) {
                UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE,
                    "state" to "ARM_REJECTED", "reason" to when {
                        retired -> "RETIRED"
                        !ultraClaimed -> "NOT_CLAIMED"
                        ultraArmInProgress -> "ARM_IN_PROGRESS"
                        else -> "STALE_TIMESTAMP"
                    })
                return false
            }
            ultraArmInProgress = true
            latestPermitTimestampNanos = timestampNanos
            pendingUltraPermit = candidate
            !covered
        }
        try {
            // A visible older scene must be hidden before the next permit becomes current.
            if (needsCover && !coverInternal(retire = false, preservePermit = true)) {
                UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE,
                    "state" to "ARM_REJECTED", "reason" to "COVER_NOT_APPLIED")
                return false
            }
            return promotePendingUltraPermit(allowDuringArm = true).also { armed ->
                UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE,
                    "state" to if (armed) "ARMED" else "ARM_REJECTED",
                    "reason" to if (armed) "PERMIT_CURRENT" else "PERMIT_NOT_PROMOTED",
                    "permitTs" to timestampNanos)
            }
        } finally {
            synchronized(lock) { ultraArmInProgress = false }
        }
    }

    val hasPendingUltraPermit: Boolean get() = synchronized(lock) { pendingUltraPermit != null }

    /** Pending, else current, permit frame timestamp; 0 when no Ultra permit exists. */
    val pendingOrCurrentPermitTimestampNanos: Long
        get() = synchronized(lock) { (pendingUltraPermit ?: permit)?.timestampNanos ?: 0L }

    /** Debug trace helpers; never used for decisions. */
    internal val hasPendingOrCoveredUltraPermitForTrace: Boolean
        get() = synchronized(lock) { pendingUltraPermit != null || (permit != null && covered) }
    internal val permitTimestampForTrace: Long
        get() = synchronized(lock) { (pendingUltraPermit ?: permit)?.timestampNanos ?: 0L }

    fun hasCoveredExactUltraPermitFor(timestampNanos: Long): Boolean = synchronized(lock) {
        !retired && ultraClaimed && covered && coverApplied &&
            pendingUltraPermit == null && permit?.timestampNanos == timestampNanos
    }

    /** Watchdog replay also needs to notice a newer frame observed while admission was BUSY. */
    fun hasCoveredUltraPermitOlderThan(timestampNanos: Long): Boolean = synchronized(lock) {
        !retired && ultraClaimed && covered && coverApplied &&
            pendingUltraPermit == null &&
            permit?.let { !it.missedReported && it.timestampNanos < timestampNanos } == true
    }

    private fun promotePendingUltraPermit(allowDuringArm: Boolean = false): Boolean {
        val pending = synchronized(lock) {
            if (ultraArmInProgress && !allowDuringArm) return false
            pendingUltraPermit
        } ?: return true
        if (!hasAppliedCover && !coverInternal(retire = false, preservePermit = true)) return false
        when (status(pending)) {
            MainPhysicalUiPermitStatus.BUSY -> return false
            MainPhysicalUiPermitStatus.REVOKED -> {
                coverInternal(retire = false, preservePermit = false)
                return false
            }
            MainPhysicalUiPermitStatus.CURRENT -> Unit
        }
        return synchronized(lock) {
            if (retired || !ultraClaimed || pendingUltraPermit !== pending ||
                !covered || !coverApplied
            ) return@synchronized false
            permit = pending
            pendingUltraPermit = null
            true
        }
    }

    /** A fresh TextureView may reveal ordinary output only after its new route is attached. */
    fun armOrdinaryRecovery(stillAuthorized: () -> Boolean): Boolean = synchronized(lock) {
        if (retired || ultraClaimed || !covered || !coverApplied ||
            !runCatching(stillAuthorized).getOrDefault(false)
        ) return@synchronized false
        ordinaryPermit = stillAuthorized
        true
    }

    /** Browser-only output has no local frame to wait for; its TextureView must be hidden. */
    fun revealOrdinaryNoPreview(
        stillAuthorized: () -> Boolean,
        videoHidden: () -> Boolean,
    ): Boolean {
        val expected = synchronized(lock) { ordinaryPermit } ?: return false
        return revealOrdinary(expected) {
            runCatching(stillAuthorized).getOrDefault(false) &&
                runCatching(videoHidden).getOrDefault(false)
        }
    }

    /** Static content stays visible while its exact frame permit and Host target remain current. */
    fun recheckCurrent(exactCurrent: () -> Boolean): Boolean {
        if (hasPendingUltraPermit && !promotePendingUltraPermit()) return false
        val snapshot = synchronized(lock) { Triple(covered, ultraClaimed, permit) }
        if (!snapshot.second) return true
        val exact = runCatching(exactCurrent).getOrDefault(false)
        val currentStatus = snapshot.third?.let(::status) ?: MainPhysicalUiPermitStatus.REVOKED
        if (!exact || currentStatus != MainPhysicalUiPermitStatus.CURRENT) {
            if (!exact || !snapshot.first || !hasAppliedCover ||
                currentStatus == MainPhysicalUiPermitStatus.REVOKED && snapshot.third != null
            ) coverInternal(
                retire = !exact,
                preservePermit = exact && currentStatus == MainPhysicalUiPermitStatus.BUSY,
                expectedPermit = snapshot.third,
                guardPermit = true,
            )
        }
        else if (snapshot.first && !hasAppliedCover) {
            coverInternal(retire = false, preservePermit = true)
        }
        return exact && currentStatus == MainPhysicalUiPermitStatus.CURRENT
    }

    /** No arbitrary TextureView update can reveal MAIN; the producer timestamp must match. */
    fun onTextureUpdated(timestampNanos: Long, exactCurrent: () -> Boolean): Boolean {
        if (hasPendingUltraPermit && !promotePendingUltraPermit()) return false
        val ordinary = synchronized(lock) {
            if (!retired && !ultraClaimed) ordinaryPermit else null
        }
        if (ordinary != null) return revealOrdinary(ordinary, exactCurrent)
        val expected = synchronized(lock) {
            if (retired || !ultraClaimed || ultraArmInProgress) return false
            permit
        } ?: run {
            traceDenial(null, "NO_PERMIT")
            return false
        }
        val exact = runCatching(exactCurrent).getOrDefault(false)
        val currentStatus = status(expected)
        if (exact && currentStatus != MainPhysicalUiPermitStatus.REVOKED &&
            timestampNanos > expected.timestampNanos
        ) synchronized(lock) {
            if (permit === expected && expected.newerObservedTimestampNanos == null)
                expected.newerObservedTimestampNanos = timestampNanos
        }
        if (isCovered && !hasAppliedCover &&
            !coverInternal(retire = false, preservePermit = true)
        ) return false
        val allowed = exact && timestampNanos == expected.timestampNanos &&
            currentStatus == MainPhysicalUiPermitStatus.CURRENT &&
            synchronized(lock) { expected.newerObservedTimestampNanos == null }
        if (!allowed) {
            traceDenial(expected, when {
                !exact -> "TARGET_NOT_CURRENT"
                currentStatus != MainPhysicalUiPermitStatus.CURRENT -> "PERMIT_${currentStatus.name}"
                timestampNanos != expected.timestampNanos -> "TIMESTAMP_MISMATCH"
                else -> "NEWER_FRAME_OBSERVED"
            })
            val missedCandidate = synchronized(lock) {
                if (exact && currentStatus == MainPhysicalUiPermitStatus.CURRENT &&
                    permit === expected && !expected.missedReported
                ) expected.newerObservedTimestampNanos else null
            }
            coverInternal(
                retire = !exact,
                preservePermit = exact && currentStatus != MainPhysicalUiPermitStatus.REVOKED,
                expectedPermit = expected,
                guardPermit = true,
            )
            // A producer miss is actionable only after this exact UI owner acknowledged the
            // opaque cover. Leave missedReported clear when the main-thread cover times out.
            if (missedCandidate != null && hasCoveredExactUltraPermitFor(
                    expected.timestampNanos,
                ) && status(expected) == MainPhysicalUiPermitStatus.CURRENT
            ) {
                val report = synchronized(lock) {
                    if (permit === expected && !expected.missedReported && covered &&
                        coverApplied
                    ) {
                        expected.missedReported = true
                        true
                    } else false
                }
                if (report) {
                    UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE,
                        "state" to "MISSED_FRAME")
                    runCatching { onMissedUltraFrame(expected.timestampNanos, missedCandidate) }
                    runCatching { expected.onMissedFrame(missedCandidate) }
                }
            }
            return false
        }
        val currentBeforeCommit = runCatching(exactCurrent).getOrDefault(false) &&
            status(expected) == MainPhysicalUiPermitStatus.CURRENT
        val serial = synchronized(lock) {
            if (retired || ultraArmInProgress || permit !== expected || !currentBeforeCommit ||
                expected.newerObservedTimestampNanos != null
            ) null else {
                sequence = Math.addExact(sequence, 1L)
                covered = false
                coverApplied = false
                sequence
            }
        } ?: run {
            val stillExact = runCatching(exactCurrent).getOrDefault(false)
            val currentStatusAfterRace = status(expected)
            coverInternal(
                retire = !stillExact,
                preservePermit = stillExact &&
                    currentStatusAfterRace != MainPhysicalUiPermitStatus.REVOKED,
                expectedPermit = expected,
                guardPermit = true,
            )
            return false
        }
        val exactBeforeApply = runCatching(exactCurrent).getOrDefault(false)
        val statusBeforeApply = status(expected)
        val missBeforeApply = synchronized(lock) {
            expected.newerObservedTimestampNanos != null
        }
        if (!exactBeforeApply || statusBeforeApply != MainPhysicalUiPermitStatus.CURRENT ||
            missBeforeApply
        ) {
            coverInternal(
                retire = !exactBeforeApply,
                preservePermit = exactBeforeApply &&
                    statusBeforeApply != MainPhysicalUiPermitStatus.REVOKED,
                expectedSequence = serial,
            )
            return false
        }
        val applied = runCatching { applyCovered(serial, false) }.getOrDefault(false)
        if (!applied) coverInternal(
            retire = false,
            preservePermit = true,
            expectedSequence = serial,
        )
        val exactAfterApply = runCatching(exactCurrent).getOrDefault(false)
        val statusAfterApply = status(expected)
        val missAfterApply = synchronized(lock) {
            expected.newerObservedTimestampNanos != null
        }
        if (applied && (!exactAfterApply ||
                statusAfterApply != MainPhysicalUiPermitStatus.CURRENT || missAfterApply)
        ) {
            coverInternal(
                retire = !exactAfterApply,
                preservePermit = exactAfterApply &&
                    statusAfterApply != MainPhysicalUiPermitStatus.REVOKED,
                expectedSequence = serial,
            )
            return false
        }
        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_GATE,
            "state" to if (applied) "REVEALED" else "REVEAL_NOT_APPLIED")
        return applied
    }

    private fun revealOrdinary(
        expected: () -> Boolean,
        exactCurrent: () -> Boolean,
    ): Boolean {
        if (!runCatching(exactCurrent).getOrDefault(false) ||
            !runCatching(expected).getOrDefault(false)
        ) return false
        val serial = synchronized(lock) {
            if (retired || ultraClaimed || ordinaryPermit !== expected ||
                !covered || !coverApplied ||
                !runCatching(exactCurrent).getOrDefault(false) ||
                !runCatching(expected).getOrDefault(false)
            ) return false
            sequence = Math.addExact(sequence, 1L)
            covered = false
            coverApplied = false
            sequence
        }
        val applied = runCatching { applyCovered(serial, false) }.getOrDefault(false)
        if (!applied) coverInternal(
            retire = false,
            preservePermit = true,
            expectedSequence = serial,
        )
        return applied
    }
}

/**
 * One Activity generation's MAIN overlay owner. The Host passes a weak View setter, so a
 * process-retained borrowed lease never keeps an Activity or View alive. A successor gate on
 * the same Activity also rejects tasks queued by its predecessor.
 */
internal class MainPhysicalUiCoverOwner(
    private val postToMain: (Runnable) -> Boolean,
    private val isMainThread: () -> Boolean,
    private val applyVisibility: (covered: Boolean) -> Boolean,
    private val onMissedUltraFrame: (expectedNanos: Long, observedNanos: Long) -> Unit = { _, _ -> },
) : Closeable {
    val activityGeneration: Any = Any()
    private val lock = Any()
    private var active: MainPhysicalUiCoverGate? = null
    private var stickyCovered = false
    private var closed = false

    val requiresOrdinaryRecovery: Boolean
        get() = synchronized(lock) { stickyCovered || active?.hasBeenUsed == true }

    fun retireActiveIfUsed(): Boolean = synchronized(lock) { active }?.retireIfUsed() ?: true

    fun newGate(): MainPhysicalUiCoverGate? {
        val old = synchronized(lock) { if (closed) return null else active }
        old?.retireIfUsed()
        val carryCover = synchronized(lock) { stickyCovered || old?.hasBeenUsed == true }
        lateinit var next: MainPhysicalUiCoverGate
        next = MainPhysicalUiCoverGate(
            applyCovered = { sequence, covered -> apply(next, sequence, covered) },
            onMissedUltraFrame = onMissedUltraFrame,
        )
        synchronized(lock) {
            if (closed) return null
            stickyCovered = carryCover
            active = next
        }
        if (carryCover) next.cover() else applyVisibility(false)
        return next
    }

    private fun apply(gate: MainPhysicalUiCoverGate, sequence: Long, covered: Boolean): Boolean {
        val acknowledged = AtomicBoolean(false)
        val completion = CountDownLatch(1)
        val action = Runnable {
            val current = synchronized(lock) { !closed && active === gate }
            if (current && gate.currentSequence == sequence) {
                acknowledged.set(runCatching { applyVisibility(covered) }.getOrDefault(false))
            }
            completion.countDown()
        }
        if (isMainThread()) action.run()
        else if (!postToMain(action)) return false
        return completion.await(500L, TimeUnit.MILLISECONDS) && acknowledged.get()
    }

    override fun close() {
        val old = synchronized(lock) { active }
        old?.retireIfUsed()
        synchronized(lock) {
            closed = true
            active = null
        }
    }
}
