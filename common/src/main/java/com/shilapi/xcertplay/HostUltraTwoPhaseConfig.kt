package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanels
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanelsLiveness

/** One frozen legacy geometry plus a separately admitted, pre-Controller physical DDP result. */
internal data class HostUltraFinalConfig(
    val airPlayConfig: AirPlayConfig,
    val frozenExternalClusterTarget: HostPhysicalTargetInspection?,
)

/** Do not leak a transient external panel's millimetres into ordinary ALT `/info` geometry. */
internal fun settleHostPhysicalPanelProbe(
    baseline: AirPlayConfig,
    calibratedCandidate: AirPlayConfig,
    candidatePanels: AirPlayPhysicalDisplayPanels?,
    candidateLiveness: AirPlayPhysicalDisplayPanelsLiveness?,
    capturedExternalClusterTarget: HostPhysicalTargetInspection?,
): HostUltraFinalConfig {
    val livePanels = candidatePanels?.takeIf { panels ->
        candidateLiveness?.isCurrentFor(panels) == true &&
            (panels.pluginUidManagingLegacyStream(AirPlayInfoPlist.ALT_UUID) == null ||
                capturedExternalClusterTarget != null)
    }
    val exactExternal = capturedExternalClusterTarget?.takeIf {
        livePanels?.pluginUidManagingLegacyStream(AirPlayInfoPlist.ALT_UUID) != null
    }
    val geometry = if (exactExternal == null) baseline else calibratedCandidate
    return HostUltraFinalConfig(
        if (livePanels == null) geometry else geometry.copy(
            displayPanels = livePanels,
            displayPanelsLiveness = requireNotNull(candidateLiveness),
        ),
        exactExternal,
    )
}

/**
 * Keeps a late physical-output observation from being applied to a different Host construction.
 *
 * The resolver is deliberately read-only: it may inspect the current physical registry and
 * construct a liveness proof, but must not publish a Controller or take a resource owner. A
 * future asynchronous external-display callback can hold a ticket; cancelling or superseding it
 * discards its result without changing the already frozen sink geometry.
 */
internal class HostUltraTwoPhaseConfig(
    val baseline: AirPlayConfig,
    private val constructionGeneration: Int,
) {
    internal class Ticket internal constructor(
        internal val owner: HostUltraTwoPhaseConfig,
        internal val constructionGeneration: Int,
        internal val sequence: Long,
    )

    private val lock = Any()
    private var nextSequence = 0L
    private var cancelled = false
    private var finalized = false

    init {
        require(baseline.displayPanels == null && baseline.displayPanelsLiveness == null) {
            "The first Host configuration phase must not publish DDP"
        }
    }

    /** A newer ticket invalidates an older callback, even within the same Host generation. */
    fun issueTicket(currentGeneration: Int): Ticket? = synchronized(lock) {
        if (cancelled || finalized || currentGeneration != constructionGeneration) return null
        Ticket(this, constructionGeneration, ++nextSequence)
    }

    fun isCurrent(ticket: Ticket, currentGeneration: Int): Boolean = synchronized(lock) {
        valid(ticket, currentGeneration)
    }

    /** Recheck immediately before constructing the Controller that can publish `/info`. */
    fun isFinalizedFor(ticket: Ticket, currentGeneration: Int): Boolean = synchronized(lock) {
        !cancelled && finalized && currentGeneration == constructionGeneration &&
            ticket.owner === this && ticket.constructionGeneration == constructionGeneration &&
            ticket.sequence == nextSequence
    }

    fun finalize(
        ticket: Ticket,
        currentGeneration: Int,
        resolve: (AirPlayConfig) -> HostUltraFinalConfig,
    ): HostUltraFinalConfig? {
        synchronized(lock) {
            if (!valid(ticket, currentGeneration)) return null
        }
        val result = resolve(baseline)
        synchronized(lock) {
            if (!valid(ticket, currentGeneration)) return null
            validateFrozenGeometry(result.airPlayConfig)
            finalized = true
            return result
        }
    }

    /** Safe to call from teardown or a cancelled asynchronous discovery callback. */
    fun cancel() = synchronized(lock) {
        cancelled = true
        nextSequence++
    }

    private fun valid(ticket: Ticket, currentGeneration: Int): Boolean =
        !cancelled && !finalized && currentGeneration == constructionGeneration &&
            ticket.owner === this && ticket.constructionGeneration == constructionGeneration &&
            ticket.sequence == nextSequence

    private fun validateFrozenGeometry(final: AirPlayConfig) {
        check(final.main === baseline.main) { "MAIN geometry changed after sink construction" }
        val beforeCluster = baseline.cluster
        val afterCluster = final.cluster
        check((beforeCluster == null) == (afterCluster == null)) {
            "ALT presence changed after sink construction"
        }
        if (beforeCluster != null) {
            check(afterCluster?.copy(
                widthPhysicalMm = beforeCluster.widthPhysicalMm,
                heightPhysicalMm = beforeCluster.heightPhysicalMm,
            ) == beforeCluster) { "ALT stream geometry changed after sink construction" }
        }
        check(final.copy(
            cluster = beforeCluster,
            displayPanels = null,
            displayPanelsLiveness = null,
        ) == baseline) { "Non-display Host configuration changed after sink construction" }
        check((final.displayPanels == null) == (final.displayPanelsLiveness == null)) {
            "DDP panels require their exact liveness proof"
        }
    }
}
