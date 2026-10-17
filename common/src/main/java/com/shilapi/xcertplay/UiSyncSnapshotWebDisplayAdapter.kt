package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecodeResult
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncSemanticDecoder
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.vehicle.ultra.UltraPresentationSnapshot
import java.io.Closeable

/**
 * Projects the renderer's complete replay snapshot to one custom cluster WebDisplay.
 *
 * This deliberately does not replay [UltraPresentationSnapshot.events] through the legacy event
 * sink. Those events are already history retained by the upstream hub; replaying them on every
 * notification would duplicate transient commands and manufacture new revisions.
 */
internal class UiSyncSnapshotWebDisplayAdapter(
    source: UiSyncPresentationSource,
    private val displayId: String,
    private val target: WebDisplayPresentationLease,
    limits: ClusterPresentationLimits = ClusterPresentationLimits(),
) : Closeable {
    private val lock = Any()
    private val projector = UiSyncWebSnapshotProjector(limits)
    private var subscription: Closeable? = null
    private var lastRevision = -1L
    private var terminal = false
    private var closed = false
    private var retirementInFlight = false

    init {
        try {
            val created = source.subscribeWithReplay(displayId, ::accept)
            synchronized(lock) { subscription = created }
            try {
                val initial = created.initialSnapshot
                require(initial.displayId.value == displayId) {
                    "UI Sync replay snapshot belongs to another display"
                }
                require(initial.revision >= 0L) { "UI Sync replay revision must not be negative" }
                accept(initial)
                if (synchronized(lock) { terminal }) retireSubscription(revokeTarget = false)
            } catch (failure: Throwable) {
                throw failure
            }
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun accept(snapshot: UltraPresentationSnapshot) {
        if (snapshot.displayId.value != displayId || snapshot.revision < 0L) return
        val json = projector.encode(snapshot)
        var retire = false
        synchronized(lock) {
            if (closed || terminal) return
            if (snapshot.revision < lastRevision) return
            if (snapshot.revision == lastRevision && !snapshot.closed) return

            // Publish while holding the adapter lock. This makes the revision check and channel
            // replacement one operation, so two concurrently delivered snapshots cannot publish
            // in reverse order.
            if (!target.replace(json)) return
            lastRevision = snapshot.revision
            if (snapshot.closed) {
                terminal = true
                retire = true
            }
        }
        if (retire) retireSubscription(revokeTarget = false)
    }

    override fun close() = retireSubscription(revokeTarget = true)

    private fun retireSubscription(revokeTarget: Boolean) {
        val detach = synchronized(lock) {
            if (revokeTarget) closed = true
            if (subscription == null && !revokeTarget) return
            check(!retirementInFlight) { "UI Sync WebDisplay detach is already in flight" }
            retirementInFlight = true
            subscription
        }
        var failure: Throwable? = null
        try {
            if (revokeTarget) {
                // Invalidate public publication before detaching. A failed observer close keeps
                // this exact lease available for retry, but no callback can write to the page.
                try { target.close() } catch (problem: Throwable) { failure = problem }
            }
            if (detach != null) {
                try {
                    detach.close()
                    synchronized(lock) {
                        if (subscription === detach) subscription = null
                    }
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
        } finally {
            synchronized(lock) { retirementInFlight = false }
        }
        failure?.let { throw it }
    }
}

/** Stateless, bounded, privacy-limited conversion of one complete upstream snapshot. */
internal class UiSyncWebSnapshotProjector(
    private val limits: ClusterPresentationLimits,
) {
    private val decoder = CarPlayUiSyncSemanticDecoder()

    fun encode(snapshot: UltraPresentationSnapshot): String {
        val retained = snapshot.events.takeLast(limits.maximumRetainedCommands)
        val locallyDropped = snapshot.events.size - retained.size
        val commands = retained.map { event ->
            val allowed = when (event.knownCommand) {
                CarPlayUiSyncCommandType.LAYOUT_CHANGE,
                CarPlayUiSyncCommandType.PALETTE_CHANGE -> allowlistedStructure(event)
                else -> null
            }
            val sanitized = allowed?.let { PresentationSanitizer(limits).sanitize(it) }
            var data = sanitized?.value ?: ClusterPresentationValue.Omitted("notWebDisplayAllowlisted")
            var truncated = sanitized?.truncated ?: true
            if (ClusterPresentationJson.encodeValue(data).length > limits.maximumJsonPayloadChars) {
                data = ClusterPresentationValue.Omitted("encodedSizeLimit")
                truncated = true
            }
            ClusterPresentationCommand(
                revision = event.revision,
                type = event.type,
                name = event.knownCommand?.diagnosticName,
                data = data,
                truncated = truncated,
            )
        }

        return ClusterPresentationJson.encodeBounded(
            ClusterPresentationSnapshot(
                revision = snapshot.revision,
                commands = commands,
                droppedEventCount = snapshot.droppedEventCount,
                adapterDroppedEventCount = locallyDropped,
                closed = snapshot.closed,
            ),
            limits.maximumSnapshotJsonBytes,
        )
    }

    private fun allowlistedStructure(
        event: com.shilapi.xcertplay.vehicle.ultra.UltraPresentationEventSnapshot,
    ): Map<String, Any?>? {
        val decoded = decoder.decode(event) as? CarPlayUiSyncSemanticDecodeResult.Decoded
            ?: return null
        return when (val command = decoded.command) {
            is CarPlayUiSyncSemanticCommand.LayoutChange -> linkedMapOf(
                "layoutID" to command.layoutID,
                "activeComponents" to command.activeComponents.toSortedMap(),
                "paletteID" to command.paletteID,
            )
            is CarPlayUiSyncSemanticCommand.PaletteChange -> linkedMapOf(
                "layoutIdentifier" to command.layoutIdentifier,
                "paletteIdentifier" to command.paletteIdentifier,
            )
            else -> null
        }
    }
}
