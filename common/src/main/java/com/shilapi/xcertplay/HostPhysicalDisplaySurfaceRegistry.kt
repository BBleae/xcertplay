package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.UltraRevealEvent
import com.shilapi.xcertplay.airplay.UltraRevealTrace

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.ultra.AndroidDisplaySurfaceTarget
import com.shilapi.xcertplay.ultra.AndroidSurfaceOwnership
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.DisplaySurfaceDescriptor
import com.shilapi.xcertplay.ultra.DisplaySurfaceId
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.AuthorizedExternalClusterUiPermit
import com.shilapi.xcertplay.ultra.AuthorizedMainPhysicalUiPermit
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A TextureView in the main window is not evidence of a separate vehicle display. */
internal enum class HostDisplaySurfaceKind {
    PHYSICAL_MAIN,
    PHYSICAL_EXTERNAL,
    PREVIEW_ONLY,
}

/** Redraw nudges per MAIN permit whose queued frame has not been latched by the consumer. */
private const val MAXIMUM_MAIN_REDRAW_REQUESTS = 5

internal fun interface ExternalClusterUiCoverWatchdogScheduler {
    fun schedule(recheck: () -> Unit): Closeable
}

private object ProcessExternalClusterUiCoverWatchdog : ExternalClusterUiCoverWatchdogScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "external-cluster-ui-cover-watchdog").apply { isDaemon = true }
    }
    override fun schedule(recheck: () -> Unit): Closeable {
        // The scheduler must not become a hidden process root for a lost Presentation callback.
        // Its exact registration owns [recheck] strongly only until close/destroy; the timer
        // cancels itself after that registration becomes unreachable.
        val weakCallback = WeakReference(recheck)
        val futureRef = AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
        val future = executor.scheduleWithFixedDelay({
            val callback = weakCallback.get()
            if (callback == null) futureRef.get()?.cancel(false)
            else runCatching(callback)
        },
            250L, 250L, TimeUnit.MILLISECONDS)
        futureRef.set(future)
        return Closeable { future.cancel(false) }
    }
}

internal data class HostDisplaySurfaceVersion(
    val destination: DisplayDestination,
    val generation: Long,
    val kind: HostDisplaySurfaceKind,
    val sizePixels: PixelSize,
    val externalCalibration: ExternalPhysicalDisplayCalibration? = null,
)

/** Read-only observation. A later borrow must revalidate the same version and Surface identity. */
internal class HostPhysicalTargetInspection(
    val version: HostDisplaySurfaceVersion,
    val surface: Surface,
    /** Changes whenever a trusted external candidate enters, leaves, or changes. */
    val externalTopologyGeneration: Long? = null,
) {
    val externalCalibration: ExternalPhysicalDisplayCalibration?
        get() = version.externalCalibration
}

/**
 * One process-owned external-display trust generation. A DisplayManager/Presentation entry does
 * not mint this proof: the holder must invalidate it as soon as its independent physical lease
 * revokes, before waiting for a controller or UI-thread lock. Invalidation is fail-closed even
 * while the registry's downstream invalidation observers are blocked.
 */
internal class ExternalPhysicalTargetTrustToken private constructor(
    private val registry: HostPhysicalDisplaySurfaceRegistry,
    val calibration: ExternalPhysicalDisplayCalibration,
) {
    private val current = AtomicBoolean(true)
    private val boundRecordId = AtomicLong(0L)
    private val routeProof = AtomicReference<(() -> Boolean)?>(null)
    private val legacyDecoderProof = AtomicReference<(() -> Boolean)?>(null)
    internal val hasBoundRouteProof: Boolean get() = routeProof.get() != null

    /** Both connector trust and the exact decoder route must remain current. */
    val isCurrent: Boolean get() = current.get() &&
        routeProof.get()?.let { runCatching(it).getOrDefault(false) } != false
    val hasAttachedLegacyDecoder: Boolean get() = isCurrent &&
        legacyDecoderProof.get()?.let { runCatching(it).getOrDefault(false) } == true

    internal fun bindRouteProof(probe: () -> Boolean) {
        check(routeProof.compareAndSet(null, probe)) {
            "An external trust generation cannot bind a second decoder route"
        }
        if (!isCurrent) invalidate()
    }

    internal fun bindLegacyDecoderProof(probe: () -> Boolean) {
        check(legacyDecoderProof.compareAndSet(null, probe)) {
            "An external trust generation cannot bind a second legacy decoder proof"
        }
    }

    fun invalidate() {
        if (current.getAndSet(false)) registry.onExternalTrustInvalidated(this)
    }

    internal fun belongsTo(owner: HostPhysicalDisplaySurfaceRegistry): Boolean = registry === owner

    internal fun bind(recordId: Long) {
        check(boundRecordId.compareAndSet(0L, recordId) || boundRecordId.get() == recordId) {
            "An external trust generation cannot authorize another Surface record"
        }
    }

    companion object {
        internal fun create(
            registry: HostPhysicalDisplaySurfaceRegistry,
            calibration: ExternalPhysicalDisplayCalibration,
        ) = ExternalPhysicalTargetTrustToken(registry, calibration)
    }
}

/**
 * Process-owned inventory of TextureView producers. It never captures an Activity or a View.
 * A version is valid only for the exact Surface, SurfaceTexture and callback pixel dimensions.
 */
internal class HostPhysicalDisplaySurfaceRegistry(
    private val releaseSurface: (Surface) -> Unit = Surface::release,
    private val releaseTexture: (SurfaceTexture) -> Unit = SurfaceTexture::release,
    private val requestRetirement: () -> Unit = {},
    private val coverWatchdogScheduler: ExternalClusterUiCoverWatchdogScheduler =
        ProcessExternalClusterUiCoverWatchdog,
    /**
     * Timestamp of the buffer the consumer most recently *latched* (`SurfaceTexture`'s current
     * image). HWUI latches a TextureView frame on its RenderThread after the UI-thread
     * `onSurfaceTextureUpdated` callback, so a timestamp read inside that callback names the
     * previous frame (or 0). Only this latched value identifies what the MAIN panel shows.
     */
    private val readLatchedTimestamp: (SurfaceTexture) -> Long = SurfaceTexture::getTimestamp,
) {
    private class TextureRecord(val texture: SurfaceTexture) {
        val surfaces = ArrayList<SurfaceRecord>()
        var viewAttached = true
        var released = false
        var releaseInFlight = false
    }

    private class SurfaceRecord(
        val id: Long,
        val destination: DisplayDestination,
        var kind: HostDisplaySurfaceKind,
        val surface: Surface,
        val texture: TextureRecord,
        var generation: Long,
        var sizePixels: PixelSize?,
        var externalTrustToken: ExternalPhysicalTargetTrustToken?,
    ) {
        var active = true
        var legacyRetired = false
        var surfaceReleased = false
        var borrower: BorrowedTargetLease? = null
        var retryLegacyDetach: (() -> Unit)? = null
        var cleanupInFlight = false
        var uiCover: ExternalClusterUiCoverRegistration? = null
        var uiCoverEverBound = false
        var mainUiCover: MainPhysicalUiCoverRegistration? = null
        var borrowerCloseAwaitingCover = false
        var mainBorrowAdmissionFrozen = false
    }

    /** One Activity, TextureView, Surface and version; never a reusable MAIN destination. */
    internal inner class MainPhysicalUiCoverRegistration internal constructor(
        private val recordId: Long,
        val version: HostDisplaySurfaceVersion,
        val surface: Surface,
        val texture: SurfaceTexture,
        val activityGeneration: Any,
        private val gate: MainPhysicalUiCoverGate,
        /** Weak, Host-posted TextureView invalidate; never retains the Activity or View. */
        private val requestRedraw: () -> Unit = {},
    ) : Closeable {
        private var closed = false
        private var lastObservedTimestampNanos = 0L
        private var watchdog: Closeable? = null
        private var watchdogRecheck: (() -> Unit)? = null
        /** Bounded redraw nudges for one permit whose frame the consumer has not latched. */
        private var stalledPermitTimestampNanos = 0L
        private var stalledTicks = 0
        private var redrawRequests = 0

        /** The consumer's latched buffer timestamp, or 0 when unavailable. */
        private fun latchedTimestamp(): Long =
            runCatching { readLatchedTimestamp(texture) }.getOrDefault(0L).coerceAtLeast(0L)

        /** Debug trace: first failing exact-current component, or OK. */
        private fun exactCurrentReasonForTrace(): String = synchronized(lock) {
            val record = byId[recordId]
            when {
                closed -> "CLOSED"
                record == null -> "NO_RECORD"
                record.mainUiCover !== this -> "OTHER_COVER"
                record.version() != version -> "VERSION"
                record.surface !== surface -> "SURFACE"
                record.texture.texture !== texture -> "TEXTURE"
                !record.active -> "INACTIVE"
                active[DisplayDestination.MAIN] !== record -> "NOT_ACTIVE_MAIN"
                !record.texture.viewAttached -> "VIEW_DETACHED"
                !surface.isValid -> "SURFACE_INVALID"
                else -> "OK"
            }
        }

        private fun exactCurrent(): Boolean = synchronized(lock) {
            val record = byId[recordId]
            !closed && record != null && record.mainUiCover === this &&
                record.version() == version && record.surface === surface &&
                record.texture.texture === texture && record.active &&
                active[DisplayDestination.MAIN] === record &&
                record.texture.viewAttached && surface.isValid
        }

        val hasAppliedCover: Boolean get() = gate.hasAppliedCover

        fun startWatchdog() {
            val recheck: () -> Unit = {
                val current = gate.recheckCurrent(::exactCurrent)
                if (current) {
                    // The latched buffer, not a callback-time read, is what MAIN shows. This also
                    // reveals a permitted frame latched before arming or with no later callback.
                    val latched = latchedTimestamp()
                    val observed = if (latched > 0L) latched
                        else synchronized(lock) { lastObservedTimestampNanos }
                    val exactPermit = observed > 0L && gate.hasCoveredExactUltraPermitFor(observed)
                    val olderPermit = observed > 0L && gate.hasCoveredUltraPermitOlderThan(observed)
                    if (UltraRevealTrace.enabled && gate.hasPendingOrCoveredUltraPermitForTrace) {
                        if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                            "phase" to "WATCHDOG", "observed" to observed,
                            "permitTs" to gate.permitTimestampForTrace,
                            "exactPermit" to exactPermit, "olderPermit" to olderPermit)
                    }
                    if (exactPermit || olderPermit) {
                        onTextureUpdated(texture, observed)
                    } else if (latched > 0L) {
                        nudgeStalledPermit(latched)
                    }
                } else if (UltraRevealTrace.enabled && gate.hasPendingOrCoveredUltraPermitForTrace) {
                    if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                        "phase" to "WATCHDOG_NOT_CURRENT", "exact" to exactCurrentReasonForTrace())
                }
            }
            synchronized(lock) {
                if (closed) return
                watchdogRecheck = recheck
            }
            val scheduled = coverWatchdogScheduler.schedule(recheck)
            synchronized(lock) {
                if (closed) scheduled.close() else watchdog = scheduled
            }
        }

        /**
         * Host entry, posted after the draw whose `onSurfaceTextureUpdated` fired: by then HWUI's
         * RenderThread sync has latched that frame, so the current image is what MAIN shows.
         */
        fun observeLatchedFrame(texture: SurfaceTexture): Boolean {
            if (texture !== this.texture) return false
            val latched = latchedTimestamp()
            if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                "phase" to "LATCHED", "ts" to latched,
                "permitTs" to gate.permitTimestampForTrace)
            if (latched <= 0L) return false
            return onTextureUpdated(texture, latched)
        }

        /**
         * A permit whose frame is queued but not latched (no further consumer draw after arming)
         * asks the Host for a TextureView redraw, at most every other tick and five times. A
         * newer latched frame instead takes the existing missed-frame reproof path.
         */
        private fun nudgeStalledPermit(latched: Long) {
            val permitTimestamp = gate.pendingOrCurrentPermitTimestampNanos
            if (permitTimestamp <= 0L || latched >= permitTimestamp) return
            val request = synchronized(lock) {
                if (closed) return
                if (stalledPermitTimestampNanos != permitTimestamp) {
                    stalledPermitTimestampNanos = permitTimestamp
                    stalledTicks = 0
                    redrawRequests = 0
                }
                stalledTicks++
                if (stalledTicks % 2 == 0 && redrawRequests < MAXIMUM_MAIN_REDRAW_REQUESTS) {
                    redrawRequests++
                    true
                } else false
            }
            if (!request) return
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                "phase" to "LATCH_STALLED_REDRAW", "latched" to latched,
                "permitTs" to permitTimestamp)
            runCatching(requestRedraw).exceptionOrNull()?.let {
                UltraRevealTrace.mark(UltraRevealEvent.REVEAL_SWALLOWED,
                    "where" to "MAIN_REDRAW_REQUEST", "error" to it)
            }
        }

        fun onTextureUpdated(texture: SurfaceTexture, timestampNanos: Long): Boolean {
            if (texture !== this.texture || !exactCurrent()) {
                if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                    "phase" to "NOT_CURRENT", "ts" to timestampNanos,
                    "sameTexture" to (texture === this.texture),
                    "exact" to exactCurrentReasonForTrace())
                return false
            }
            if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                "phase" to "FRAME", "ts" to timestampNanos,
                "permitTs" to gate.permitTimestampForTrace)
            if (timestampNanos > 0L) synchronized(lock) {
                if (!closed) lastObservedTimestampNanos = maxOf(
                    lastObservedTimestampNanos, timestampNanos,
                )
            }
            return gate.onTextureUpdated(timestampNanos, ::exactCurrent)
        }

        fun armOrdinaryRecovery(stillCurrentRoute: () -> Boolean): Boolean =
            exactCurrent() && gate.armOrdinaryRecovery {
                exactCurrent() && stillCurrentRoute()
            }

        fun revealOrdinaryNoPreview(
            stillCurrentRoute: () -> Boolean,
            videoHidden: () -> Boolean,
        ): Boolean = exactCurrent() && gate.revealOrdinaryNoPreview(
            stillAuthorized = { exactCurrent() && stillCurrentRoute() },
            videoHidden = videoHidden,
        )

        fun claimUltra(): Boolean {
            synchronized(lock) { lastObservedTimestampNanos = 0L }
            return exactCurrent() && gate.claimUltra()
        }

        fun cover(retire: Boolean = false): Boolean = gate.cover(retire)
        fun retireIfUsed(): Boolean = gate.retireIfUsed()

        fun armUltra(permit: AuthorizedMainPhysicalUiPermit): Boolean {
            if (!exactCurrent() || permit.authorizationFor(
                    version, surface, activityGeneration,
                ) == MainPhysicalUiPermitStatus.REVOKED
            ) return false
            if (!gate.armUltraWithStatus(permit.producerTimestampNanos,
                authorization = {
                    if (exactCurrent()) permit.authorizationFor(
                        version, surface, activityGeneration,
                    ) else MainPhysicalUiPermitStatus.REVOKED
                }, onMissedFrame = { observed ->
                    // The gate invokes this only after an acknowledged cover for this permit.
                    permit.onMissedProducerFrame(observed)
                })) return false
            // The permitted frame may already be the latched one, with no later callback.
            val latched = latchedTimestamp()
            val observed = if (latched > 0L) latched
                else synchronized(lock) { lastObservedTimestampNanos }
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                "phase" to "ARM_LATCHED", "latched" to latched, "observed" to observed,
                "permitTs" to permit.producerTimestampNanos)
            if (observed >= permit.producerTimestampNanos) {
                onTextureUpdated(texture, observed)
            }
            return true
        }

        override fun close() {
            val scheduled = synchronized(lock) {
                if (closed) return
                closed = true
                val record = byId[recordId]
                if (record?.mainUiCover === this) record.mainUiCover = null
                watchdogRecheck = null
                watchdog.also { watchdog = null }
            }
            scheduled?.close()
            gate.retireIfUsed()
        }
    }

    /** Holds one exact Presentation texture, not a destination reusable by a later display. */
    internal inner class ExternalClusterUiCoverRegistration internal constructor(
        private val recordId: Long,
        val version: HostDisplaySurfaceVersion,
        val surface: Surface,
        val texture: SurfaceTexture,
        private val gate: ExternalClusterUiCoverGate,
        /** Weak, Host-posted TextureView invalidate; never retains the Presentation. */
        private val requestRedraw: () -> Unit = {},
    ) : Closeable {
        private var closed = false
        private var lastObservedTimestampNanos = 0L
        private var watchdog: Closeable? = null
        private var watchdogRecheck: (() -> Unit)? = null
        private var stalledPermitTimestampNanos = 0L
        private var stalledTicks = 0
        private var redrawRequests = 0

        /** The consumer's latched buffer timestamp, or 0 when unavailable. */
        private fun latchedTimestamp(): Long =
            runCatching { readLatchedTimestamp(texture) }.getOrDefault(0L).coerceAtLeast(0L)

        private fun state(): Pair<Boolean, Boolean> = synchronized(lock) {
            val record = byId[recordId]
            val current = !closed && record != null && record.uiCover === this &&
                record.version() == version && record.surface === surface &&
                record.texture.texture === texture && record.active &&
                active[record.destination] === record && record.hasCurrentPhysicalTrust() &&
                record.texture.viewAttached && surface.isValid
            current to (current && record?.borrower == null &&
                record?.externalTrustToken?.hasAttachedLegacyDecoder == true)
        }

        fun startWatchdog() {
            val recheck: () -> Unit = {
                gate.recheckCurrent(
                    exactCurrent = { state().first },
                    legacyRouteCurrent = { state().second },
                )
                // A static instrument frame may already be latched, with no later callback.
                val permitTimestamp = gate.coveredPermitTimestampNanos
                if (permitTimestamp > 0L && state().first) {
                    val latched = latchedTimestamp()
                    if (latched >= permitTimestamp) {
                        UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                            "phase" to "CLUSTER_WATCHDOG_LATCHED", "latched" to latched,
                            "permitTs" to permitTimestamp)
                        onTextureUpdated(texture, latched)
                    } else if (latched > 0L) nudgeStalledPermit(latched, permitTimestamp)
                }
            }
            synchronized(lock) {
                if (closed) return
                watchdogRecheck = recheck
            }
            val scheduled = coverWatchdogScheduler.schedule(recheck)
            synchronized(lock) {
                if (closed) scheduled.close() else watchdog = scheduled
            }
        }

        /**
         * Host `onSurfaceTextureUpdated`: HWUI latches this frame only after the callback, so its
         * timestamp names the previous image. It still drives the ordinary decoder reveal, but
         * never an Ultra permit; [observeLatchedFrame] does that after the draw has synced.
         */
        fun onTextureCallback(texture: SurfaceTexture, callbackTimestampNanos: Long): Boolean {
            if (texture !== this.texture || gate.isUltraClaimed) return false
            return onTextureUpdated(texture, callbackTimestampNanos)
        }

        /** Posted after the draw: the latched image is exactly what the CLUSTER panel shows. */
        fun observeLatchedFrame(texture: SurfaceTexture): Boolean {
            if (texture !== this.texture || !gate.isUltraClaimed) return false
            val latched = latchedTimestamp()
            if (UltraRevealTrace.enabled) UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                "phase" to "CLUSTER_LATCHED", "ts" to latched,
                "permitTs" to gate.coveredPermitTimestampNanos)
            if (latched <= 0L) return false
            return onTextureUpdated(texture, latched)
        }

        private fun nudgeStalledPermit(latched: Long, permitTimestamp: Long) {
            val request = synchronized(lock) {
                if (closed) return
                if (stalledPermitTimestampNanos != permitTimestamp) {
                    stalledPermitTimestampNanos = permitTimestamp
                    stalledTicks = 0
                    redrawRequests = 0
                }
                stalledTicks++
                if (stalledTicks % 2 == 0 && redrawRequests < MAXIMUM_MAIN_REDRAW_REQUESTS) {
                    redrawRequests++
                    true
                } else false
            }
            if (!request) return
            UltraRevealTrace.mark(UltraRevealEvent.REVEAL_MAIN_TEXTURE,
                "phase" to "CLUSTER_LATCH_STALLED_REDRAW", "latched" to latched,
                "permitTs" to permitTimestamp)
            runCatching(requestRedraw)
        }

        fun onTextureUpdated(texture: SurfaceTexture, timestampNanos: Long): Boolean {
            if (texture !== this.texture) return false
            if (timestampNanos > 0L && state().first) synchronized(lock) {
                if (!closed) lastObservedTimestampNanos = timestampNanos
            }
            return gate.onTextureUpdated(
                timestampNanos = timestampNanos,
                exactCurrent = { state().first },
                legacyRouteCurrent = { state().second },
            )
        }

        fun cover(retire: Boolean = false): Boolean = gate.cover(retire)
        val hasAppliedCover: Boolean get() = gate.hasAppliedCover
        fun claimUltra(): Boolean = gate.claimUltra()
        fun armUltra(permit: AuthorizedExternalClusterUiPermit): Boolean {
            if (!permit.isCurrentFor(version, surface)) return false
            // A prior main-thread timeout left a logical cover request unacknowledged. A retry
            // must ask the same exact UI owner again before it can consume this frame permit.
            if (gate.isCovered && !gate.hasAppliedCover && !gate.cover()) return false
            if (!gate.armUltra(
                    permit.producerTimestampNanos,
                    clearBeforeReveal = permit.clearBeforeReveal,
                ) {
                    permit.isCurrentFor(version, surface)
                }) return false
            // The permitted frame may already be latched; a newer latched image supersedes it.
            val latched = latchedTimestamp()
            val observed = if (latched > 0L) latched
                else synchronized(lock) { lastObservedTimestampNanos }
            if (observed >= permit.producerTimestampNanos) {
                onTextureUpdated(texture, observed)
            }
            return true
        }

        override fun close() {
            val scheduled = synchronized(lock) {
                if (closed) return
                closed = true
                val record = byId[recordId]
                if (record?.uiCover === this) record.uiCover = null
                watchdogRecheck = null
                watchdog.also { watchdog = null }
            }
            scheduled?.close()
            gate.cover(retire = true)
        }
    }

    /**
     * The caller must first prove its EGL owner has retired, then call [retireAfterEglProof].
     * Closing the borrowed Android target itself does not transfer this process-owned Surface.
     */
    internal class BorrowedTargetLease private constructor(
        val target: AndroidDisplaySurfaceTarget,
        val version: HostDisplaySurfaceVersion,
        private val externalTrustToken: ExternalPhysicalTargetTrustToken?,
        private val registry: HostPhysicalDisplaySurfaceRegistry,
        private val recordId: Long,
        private val exactExternalCover: ExternalClusterUiCoverRegistration?,
        private val exactMainCover: MainPhysicalUiCoverRegistration?,
    ) {
        @Volatile private var retired = false
        val retirementConfirmed: Boolean get() = retired
        /** One-way, exact old-gate ACK; remains valid if cleanup later removes the Host record. */
        private val retiringCoverAcknowledged = AtomicBoolean(false)

        val isRevoked: Boolean
            get() = retired || externalTrustToken?.isCurrent == false ||
                !registry.isCurrentBorrow(recordId, this, version)

        fun retireAfterEglProof() {
            if (retired) return
            target.close()
            registry.retireBorrow(recordId, this)
            retired = true
        }

        /** A changed Host generation cannot turn this frozen borrow into a successor cover. */
        fun coverExactExternalUiForRetirement(): Boolean {
            if (version.kind != HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) return false
            if (retiringCoverAcknowledged.get()) return true
            val capturedCover = exactExternalCover ?: return false
            val cover = synchronized(registry.lock) {
                val record = registry.byId[recordId] ?: return false
                if (record.borrower !== this || record.surface !== target.surface ||
                    capturedCover.version != version ||
                    capturedCover.surface !== record.surface ||
                    capturedCover.texture !== record.texture.texture
                ) return false
                if (!record.texture.viewAttached || record.texture.released) {
                    retiringCoverAcknowledged.set(true)
                    return true
                }
                capturedCover
            }
            val acknowledged = runCatching { cover.cover(retire = true) }.getOrDefault(false)
            if (acknowledged) retiringCoverAcknowledged.set(true)
            return acknowledged
        }

        internal val hasCapturedMainUiCover: Boolean get() = exactMainCover != null
        internal val hasAppliedMainUiCover: Boolean
            get() = exactMainCover?.hasAppliedCover == true

        /** A retired old Activity or version may acknowledge only its captured MAIN owner. */
        fun coverExactMainUiForRetirement(): Boolean {
            if (version.kind != HostDisplaySurfaceKind.PHYSICAL_MAIN) return false
            if (retiringCoverAcknowledged.get()) return true
            val capturedCover = exactMainCover ?: return false
            val cover = synchronized(registry.lock) {
                val record = registry.byId[recordId] ?: return false
                if (record.borrower !== this || record.surface !== target.surface ||
                    capturedCover.version != version ||
                    capturedCover.surface !== record.surface ||
                    capturedCover.texture !== record.texture.texture
                ) return false
                if (!record.texture.viewAttached || record.texture.released) {
                    retiringCoverAcknowledged.set(true)
                    return true
                }
                capturedCover
            }
            val acknowledged = runCatching { cover.cover(retire = true) }.getOrDefault(false)
            if (acknowledged) retiringCoverAcknowledged.set(true)
            return acknowledged
        }

        companion object {
            internal fun create(
                target: AndroidDisplaySurfaceTarget,
                version: HostDisplaySurfaceVersion,
                externalTrustToken: ExternalPhysicalTargetTrustToken?,
                registry: HostPhysicalDisplaySurfaceRegistry,
                recordId: Long,
                exactExternalCover: ExternalClusterUiCoverRegistration?,
                exactMainCover: MainPhysicalUiCoverRegistration?,
            ) = BorrowedTargetLease(
                target, version, externalTrustToken, registry, recordId, exactExternalCover,
                exactMainCover,
            )
        }
    }

    /** Resource-only completion; safe to retain in the process teardown barrier. */
    internal class LegacyRetirement private constructor(
        private val registry: HostPhysicalDisplaySurfaceRegistry,
        private val recordId: Long,
    ) {
        fun completeAfterLegacyOwnerRetired() = registry.completeLegacyRetirement(recordId)

        /** Used if an immediate sink detach failed; the action must capture resources only. */
        fun retainRetryDetacher(detach: () -> Unit) = registry.retainRetryDetacher(recordId, detach)

        companion object {
            internal fun create(registry: HostPhysicalDisplaySurfaceRegistry, recordId: Long) =
                LegacyRetirement(registry, recordId)
        }
    }

    private val lock = Any()
    private val active = HashMap<DisplayDestination, SurfaceRecord>()
    private val byId = HashMap<Long, SurfaceRecord>()
    private val byTexture = IdentityHashMap<SurfaceTexture, TextureRecord>()
    private val mainBorrowReceiptIssuer = Any()
    private val targetInvalidationSubscriptions = LinkedHashSet<ExactTargetInvalidationSubscription>()
    // A late destroyed callback for a texture already released by us must still return false.
    private val releasedTextures = WeakHashMap<SurfaceTexture, Boolean>()
    // Exact referential tombstones do not keep historical Surface wrappers alive forever.
    private val attemptedSurfaceReleases = ArrayList<WeakReference<Surface>>()
    private var nextGeneration = 1L
    private var nextRecordId = 1L
    private var externalTopologyGeneration = 1L
    @Volatile private var pendingRetirement = false

    /** One-shot exact target invalidation. Notifications are delivered outside [lock]. */
    private inner class ExactTargetInvalidationSubscription(
        val version: HostDisplaySurfaceVersion,
        val surface: Surface,
        val requiredExternalTopologyGeneration: Long?,
        private val onInvalidated: () -> Unit,
    ) : Closeable {
        private val active = AtomicBoolean(true)

        override fun close() {
            active.set(false)
            synchronized(lock) { targetInvalidationSubscriptions.remove(this) }
        }

        fun notifyInvalidated() {
            if (active.compareAndSet(true, false)) {
                // An observer cannot turn a successfully published Surface mutation into a
                // partial failure. The subscriber is responsible for its own close diagnostics.
                runCatching(onInvalidated)
            }
        }
    }

    /**
     * Atomically registers for one exact Surface/version or fires immediately if it is already
     * stale. The callback must capture only process-owned session resources and must not block
     * the producer thread. Closing the subscription suppresses a not-yet-delivered callback.
     */
    fun subscribePhysicalTargetInvalidation(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
        requiredExternalTopologyGeneration: Long? = null,
        onInvalidated: () -> Unit,
    ): Closeable {
        val subscription = ExactTargetInvalidationSubscription(
            version, surface, requiredExternalTopologyGeneration, onInvalidated,
        )
        val alreadyInvalid = synchronized(lock) {
            if (isSameLivePhysicalTargetLocked(version, surface) &&
                (requiredExternalTopologyGeneration == null ||
                    isUniqueLiveExternalPhysicalTargetLocked(
                        version, surface, requiredExternalTopologyGeneration,
                    ))
            ) {
                targetInvalidationSubscriptions += subscription
                false
            } else {
                true
            }
        }
        if (alreadyInvalid) subscription.notifyInvalidated()
        return subscription
    }

    private fun collectInvalidatedSubscriptionsLocked(): List<ExactTargetInvalidationSubscription> {
        val invalidated = ArrayList<ExactTargetInvalidationSubscription>()
        val iterator = targetInvalidationSubscriptions.iterator()
        while (iterator.hasNext()) {
            val subscription = iterator.next()
            if (!isSameLivePhysicalTargetLocked(subscription.version, subscription.surface) ||
                (subscription.requiredExternalTopologyGeneration != null &&
                    !isUniqueLiveExternalPhysicalTargetLocked(
                        subscription.version, subscription.surface,
                        subscription.requiredExternalTopologyGeneration,
                    ))
            ) {
                iterator.remove()
                invalidated += subscription
            }
        }
        return invalidated
    }

    private inline fun <T> mutateAndNotify(block: () -> T): T {
        var invalidated = emptyList<ExactTargetInvalidationSubscription>()
        var covers = emptyList<ExternalClusterUiCoverRegistration>()
        var mainCovers = emptyList<MainPhysicalUiCoverRegistration>()
        var coverRetirementNeeded = false
        return try {
            synchronized(lock) {
                try {
                    block()
                } finally {
                    invalidated = collectInvalidatedSubscriptionsLocked()
                    covers = byId.values.mapNotNull { record ->
                        record.uiCover?.takeIf {
                            !record.active || active[record.destination] !== record ||
                                record.version() != it.version ||
                                !record.hasCurrentPhysicalTrust() ||
                                !record.texture.viewAttached
                        }
                    }.distinct()
                    mainCovers = byId.values.mapNotNull { record ->
                        record.mainUiCover?.takeIf {
                            !record.active || active[record.destination] !== record ||
                                record.version() != it.version || !record.texture.viewAttached
                        }
                    }.distinct()
                    coverRetirementNeeded = byId.values.any { it.borrowerCloseAwaitingCover }
                }
            }
        } finally {
            covers.forEach { it.cover(retire = true) }
            mainCovers.forEach { it.retireIfUsed() }
            invalidated.forEach(ExactTargetInvalidationSubscription::notifyInvalidated)
            if (coverRetirementNeeded) runCatching(requestRetirement)
        }
    }

    /** A borrowed physical target is never closed before its exact UI cover was acknowledged. */
    private fun requestBorrowerCloseLocked(record: SurfaceRecord) {
        val borrower = record.borrower ?: return
        val needsExternalCover = record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
            record.texture.viewAttached && record.uiCoverEverBound &&
            record.uiCover?.hasAppliedCover != true
        val needsMainCover = borrower.version.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN &&
            record.texture.viewAttached && borrower.hasCapturedMainUiCover &&
            !borrower.hasAppliedMainUiCover
        if (needsExternalCover || needsMainCover) {
            record.borrowerCloseAwaitingCover = true
        } else {
            borrower.target.close()
        }
    }

    /** The Activity binds this owner immediately after PHYSICAL_MAIN publication. */
    fun bindMainPhysicalUiCover(
        surface: Surface,
        texture: SurfaceTexture,
        version: HostDisplaySurfaceVersion,
        activityGeneration: Any,
        gate: MainPhysicalUiCoverGate,
        requestRedraw: () -> Unit = {},
    ): MainPhysicalUiCoverRegistration? {
        val registration = synchronized(lock) {
            val record = active[DisplayDestination.MAIN]?.takeIf {
                it.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN &&
                    it.version() == version && it.surface === surface &&
                    it.texture.texture === texture && it.texture.viewAttached &&
                    it.surface.isValid &&
                    (it.borrower == null || it.borrower?.version != version) &&
                    it.mainUiCover == null
            } ?: return@synchronized null
            MainPhysicalUiCoverRegistration(
                record.id, version, surface, texture, activityGeneration, gate, requestRedraw,
            ).also { record.mainUiCover = it }
        }
        if (registration != null && runCatching { registration.startWatchdog() }.isFailure) {
            registration.close()
            return null
        }
        return registration
    }

    /** A permit is minted for a single registered Activity, never just destination MAIN. */
    fun currentMainCoverActivityGeneration(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Any? = synchronized(lock) {
        active[DisplayDestination.MAIN]?.takeIf {
            it.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN && it.version() == version &&
                it.surface === surface && it.texture.viewAttached && surface.isValid
        }?.mainUiCover?.activityGeneration
    }

    /**
     * Freezes new borrows of one old MAIN record before Host teardown starts. The captured
     * borrower can retire only after its exact EGL/router fence; absence is proof only because
     * the freeze and inspection happen under the same registry lock as acquirePhysicalTarget.
     */
    internal fun captureExactMainBorrowRetirement(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): ExactMainBorrowRetirementReceipt? = synchronized(lock) {
        val record = active[DisplayDestination.MAIN]?.takeIf {
            it.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN &&
                it.version() == version && it.surface === surface &&
                it.texture.viewAttached
        } ?: return@synchronized null
        record.mainBorrowAdmissionFrozen = true
        ExactMainBorrowRetirementReceipt(
            record.id, version, surface, record.texture.texture, record.borrower,
            mainBorrowReceiptIssuer,
        )
    }

    internal inner class ExactMainBorrowRetirementReceipt internal constructor(
        val recordId: Long,
        val version: HostDisplaySurfaceVersion,
        val surface: Surface,
        val texture: SurfaceTexture,
        private val capturedBorrower: BorrowedTargetLease?,
        private val issuer: Any,
    ) {
        val isRetired: Boolean
            get() = issuer === mainBorrowReceiptIssuer &&
                capturedBorrower?.retirementConfirmed != false

        fun isFor(expectedVersion: HostDisplaySurfaceVersion, expectedSurface: Surface): Boolean =
            issuer === mainBorrowReceiptIssuer &&
                version == expectedVersion && surface === expectedSurface
    }

    /** Cover request for one exact MAIN surface before handoff or session retirement. */
    fun coverExactMainPhysicalTarget(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Boolean {
        if (version.kind != HostDisplaySurfaceKind.PHYSICAL_MAIN) return false
        val exact = synchronized(lock) {
            byId.values.singleOrNull { it.version() == version && it.surface === surface }
                ?.let { it.mainUiCover to (!it.texture.viewAttached || it.texture.released) }
        } ?: return false
        val (cover, textureGone) = exact
        if (cover == null) return textureGone
        return cover.cover()
    }

    /** Only the Authority's authenticated, exact producer-frame permit may arm reveal. */
    internal fun armMainAfterPresentedScene(permit: AuthorizedMainPhysicalUiPermit): Boolean {
        val version = permit.version
        val surface = permit.surface
        val cover = synchronized(lock) {
            active[DisplayDestination.MAIN]?.takeIf {
                it.version() == version && it.surface === surface &&
                    it.kind == HostDisplaySurfaceKind.PHYSICAL_MAIN &&
                    it.borrower != null && it.texture.viewAttached && surface.isValid
            }?.mainUiCover
        } ?: return false
        return cover.armUltra(permit)
    }

    /** Bind the UI cover only after the exact independently trusted route was published. */
    fun bindExternalClusterUiCover(
        surface: Surface,
        texture: SurfaceTexture,
        displayId: Int,
        displayEpoch: Long,
        gate: ExternalClusterUiCoverGate,
        requestRedraw: () -> Unit = {},
    ): ExternalClusterUiCoverRegistration? {
        val registration = synchronized(lock) {
            val record = active.values.singleOrNull { candidate ->
                candidate.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                    candidate.surface === surface && candidate.texture.texture === texture &&
                    candidate.externalTrustToken?.calibration?.displayId == displayId &&
                    candidate.externalTrustToken?.calibration?.displayEpoch == displayEpoch &&
                    candidate.hasCurrentPhysicalTrust() && candidate.uiCover == null
            } ?: return@synchronized null
            val version = record.version() ?: return@synchronized null
            ExternalClusterUiCoverRegistration(
                record.id, version, surface, texture, gate, requestRedraw,
            ).also {
                record.uiCover = it
                record.uiCoverEverBound = true
            }
        }
        if (registration != null && runCatching { registration.startWatchdog() }.isFailure) {
            registration.close()
            return null
        }
        return registration
    }

    /** Called before the type-111 legacy route is retired for this exact Ultra handoff. */
    fun coverExternalForUltraHandoff(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Boolean {
        val cover = synchronized(lock) {
            active[version.destination]?.takeIf {
                it.version() == version && it.surface === surface &&
                    it.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                    it.hasCurrentPhysicalTrust()
            }?.uiCover
        } ?: return false
        return cover.claimUltra()
    }

    /** AA05/physical retirement request; no stale version may cover a successor window. */
    fun coverExactExternalPhysicalTarget(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Boolean {
        val exact = synchronized(lock) {
            byId.values.singleOrNull { it.version() == version && it.surface === surface }
                ?.let { it.uiCover to (!it.texture.viewAttached || it.texture.released) }
        } ?: return false
        val (cover, textureGone) = exact
        // There is no attached TextureView to reveal stale pixels after its destroy callback.
        if (cover == null) return textureGone
        return cover.cover(retire = true)
    }

    /**
     * Covers a still-borrowed external output without retiring its gate or EGL lease. UI Sync
     * RESET can therefore hide the old frame and later reveal only a newly authorized frame.
     * This never searches by destination for a replacement Presentation, and a missing Host UI
     * acknowledgement is failure rather than permission to leave the old pixels visible.
     */
    internal fun coverExactBorrowedExternalPhysicalTarget(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Boolean {
        if (version.kind != HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) return false
        val cover = synchronized(lock) {
            exactBorrowedExternalCoverLocked(version, surface)
        } ?: return false
        if (!runCatching { cover.cover(retire = false) }.getOrDefault(false)) return false
        // A replacement or trust revocation while the Host applied the cover cannot turn the
        // old ACK into success for this now-stale borrow.
        return synchronized(lock) {
            exactBorrowedExternalCoverLocked(version, surface) === cover
        }
    }

    private fun exactBorrowedExternalCoverLocked(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): ExternalClusterUiCoverRegistration? {
        val record = active[version.destination] ?: return null
        val borrower = record.borrower ?: return null
        if (!record.active || record.kind != HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ||
            record.version() != version || record.surface !== surface ||
            !record.hasCurrentPhysicalTrust() || !record.texture.viewAttached ||
            !surface.isValid || borrower.version != version ||
            borrower.target.surface !== surface || !borrower.target.isValid ||
            borrower.retirementConfirmed
        ) return null
        return record.uiCover?.takeIf {
            it.version == version && it.surface === surface &&
                it.texture === record.texture.texture
        }
    }

    /** An unforgeable Authority capability, not a caller-provided liveness lambda. */
    internal fun armExternalAfterPresentedScene(
        permit: AuthorizedExternalClusterUiPermit,
    ): Boolean {
        val version = permit.version
        val surface = permit.surface
        val cover = synchronized(lock) {
            active[version.destination]?.takeIf {
                it.version() == version && it.surface === surface &&
                    it.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                    it.borrower != null && it.hasCurrentPhysicalTrust() &&
                    it.texture.viewAttached && surface.isValid
            }?.uiCover
        } ?: return false
        return cover.armUltra(permit)
    }

    /** Lock-free admission check: native release and decoder detach never run on the UI thread. */
    fun hasPendingRetirement(): Boolean = pendingRetirement

    /** Mint only from the immutable snapshot of an independently verified physical lease. */
    fun newExternalPhysicalTargetTrustToken(
        calibration: ExternalPhysicalDisplayCalibration,
    ): ExternalPhysicalTargetTrustToken = ExternalPhysicalTargetTrustToken.create(this, calibration)

    /** The atomic token flip precedes this callback and cannot be delayed by any listener. */
    internal fun onExternalTrustInvalidated(token: ExternalPhysicalTargetTrustToken) {
        mutateAndNotify {
            if (active.values.any { it.externalTrustToken === token }) {
                externalTopologyGeneration++
            }
            byId.values.filter { it.externalTrustToken === token }.forEach { record ->
                requestBorrowerCloseLocked(record)
            }
            refreshPendingLocked()
        }
    }

    fun publish(
        destination: DisplayDestination,
        kind: HostDisplaySurfaceKind,
        surface: Surface,
        texture: SurfaceTexture,
        widthPixels: Int,
        heightPixels: Int,
        externalTrustToken: ExternalPhysicalTargetTrustToken? = null,
    ): HostDisplaySurfaceVersion? = mutateAndNotify {
        when (kind) {
            HostDisplaySurfaceKind.PHYSICAL_MAIN -> require(destination == DisplayDestination.MAIN) {
                "Only the default main destination can use physical main trust"
            }
            HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ->
                require(destination.role == DisplayDestinationRole.CLUSTER) {
                    "Only a cluster destination can use external physical trust"
                }
            HostDisplaySurfaceKind.PREVIEW_ONLY -> Unit
        }
        val trust = when (kind) {
            HostDisplaySurfaceKind.PHYSICAL_EXTERNAL -> requireNotNull(externalTrustToken) {
                "A physical external Surface requires an independent trust generation"
            }.also {
                require(it.belongsTo(this)) { "External trust belongs to another registry" }
                require(it.calibration.destination == destination) {
                    "External trust belongs to another cluster destination"
                }
                require(it.calibration.pixels.widthPixels == widthPixels &&
                    it.calibration.pixels.heightPixels == heightPixels) {
                    "External trust pixel geometry differs from the published Surface"
                }
                check(it.hasBoundRouteProof) {
                    "External physical trust has no exact sink route proof"
                }
                check(it.isCurrent) { "External physical trust was revoked" }
            }
            else -> {
                require(externalTrustToken == null) {
                    "Only a physical external Surface can carry external trust"
                }
                null
            }
        }
        check(!hasAttemptedSurfaceReleaseLocked(surface)) {
            "Cannot republish a Surface whose native release has begun"
        }
        check(byId.values.none { it.surface === surface && it.texture.texture !== texture }) {
            "One Surface cannot be published for different SurfaceTextures"
        }
        val dimensions = pixelSizeOrNull(widthPixels, heightPixels)
        val previous = active[destination]
        if (previous != null && previous.surface === surface && previous.texture.texture === texture) {
            trust?.bind(previous.id)
            revokeOtherActiveAliasesLocked(previous.texture, previous)
            if (previous.sizePixels != dimensions || previous.kind != kind ||
                previous.externalTrustToken !== trust
            ) {
                if (previous.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ||
                    kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL
                ) externalTopologyGeneration++
                requestBorrowerCloseLocked(previous)
                previous.generation = nextGeneration++
                previous.sizePixels = dimensions
                previous.kind = kind
                previous.externalTrustToken = trust
            }
            refreshPendingLocked()
            return@mutateAndNotify previous.version()
        }
        check(byId.values.none {
            it.destination == destination && it.surface === surface && it.texture.texture === texture
        }) {
            "A withdrawn Surface cannot be republished as another generation"
        }
        val textureRecord = byTexture[texture] ?: TextureRecord(texture)
        check(!textureRecord.released && textureRecord.viewAttached) {
            "Cannot publish a destroyed TextureView surface"
        }
        val recordId = nextRecordId
        trust?.bind(recordId)
        if (previous != null) {
            requestBorrowerCloseLocked(previous)
            previous.active = false
            active.remove(destination)
        }
        if (previous?.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ||
            kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL
        ) externalTopologyGeneration++
        if (byTexture[texture] == null) byTexture[texture] = textureRecord
        revokeOtherActiveAliasesLocked(textureRecord, keep = null)
        nextRecordId++
        val record = SurfaceRecord(
            id = recordId,
            destination = destination,
            kind = kind,
            surface = surface,
            texture = textureRecord,
            generation = nextGeneration++,
            sizePixels = dimensions,
            externalTrustToken = trust,
        )
        textureRecord.surfaces += record
        byId[record.id] = record
        active[destination] = record
        refreshPendingLocked()
        record.version()
    }

    fun resize(
        destination: DisplayDestination,
        surface: Surface,
        texture: SurfaceTexture,
        widthPixels: Int,
        heightPixels: Int,
    ): HostDisplaySurfaceVersion? {
        var incompatibleTrust: ExternalPhysicalTargetTrustToken? = null
        val result = mutateAndNotify {
            val record = active[destination]
                ?.takeIf { it.surface === surface && it.texture.texture === texture }
                ?: return@mutateAndNotify null
            if (!record.hasCurrentPhysicalTrust()) return@mutateAndNotify null
            val dimensions = pixelSizeOrNull(widthPixels, heightPixels)
            if (record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                dimensions != record.externalTrustToken?.calibration?.pixels
            ) {
                incompatibleTrust = record.externalTrustToken
                return@mutateAndNotify null
            }
            if (record.sizePixels != dimensions) {
                if (record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) externalTopologyGeneration++
                requestBorrowerCloseLocked(record)
                record.generation = nextGeneration++
                record.sizePixels = dimensions
            }
            refreshPendingLocked()
            record.version()
        }
        incompatibleTrust?.invalidate()
        return result
    }

    fun snapshot(destination: DisplayDestination): HostDisplaySurfaceVersion? = synchronized(lock) {
        active[destination]?.takeIf { it.hasCurrentPhysicalTrust() }?.version()
    }

    /** No Surface is borrowed or detached by this check; it is not an output ownership proof. */
    fun inspectPhysicalTarget(destination: DisplayDestination): HostPhysicalTargetInspection? =
        synchronized(lock) {
            val record = active[destination] ?: return@synchronized null
            val version = record.version() ?: return@synchronized null
            if (record.kind == HostDisplaySurfaceKind.PREVIEW_ONLY ||
                !record.hasCurrentPhysicalTrust() ||
                !record.texture.viewAttached || !record.surface.isValid ||
                record.texture.surfaces.any { it.borrower != null }
            ) return@synchronized null
            HostPhysicalTargetInspection(
                version, record.surface,
                externalTopologyGeneration.takeIf {
                    record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL
                },
            )
        }

    /**
     * Selects an external cluster only when the registry has exactly one currently trusted
     * non-default physical candidate. A preview, a revoked trust token, or two plausible
     * connectors cannot silently choose which instrument display `/info` should describe.
     */
    fun inspectUniquePhysicalExternalClusterTarget(): HostPhysicalTargetInspection? =
        synchronized(lock) {
            val candidates = liveExternalClusterCandidatesLocked()
            if (candidates.size != 1) return@synchronized null
            inspectPhysicalTarget(candidates.single().destination)
        }

    /** The frozen two-panel offer is invalid forever after external topology changes. */
    fun isUniqueLiveExternalPhysicalTarget(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
        externalTopologyGeneration: Long,
    ): Boolean = synchronized(lock) {
        isUniqueLiveExternalPhysicalTargetLocked(version, surface, externalTopologyGeneration)
    }

    private fun isUniqueLiveExternalPhysicalTargetLocked(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
        expectedTopologyGeneration: Long,
    ): Boolean = expectedTopologyGeneration == externalTopologyGeneration &&
        isSameLivePhysicalTargetLocked(version, surface) &&
        liveExternalClusterCandidatesLocked().singleOrNull()?.let {
            it.destination == version.destination && it.surface === surface && it.version() == version
        } == true

    private fun liveExternalClusterCandidatesLocked(): List<SurfaceRecord> =
        active.values.filter { record ->
            record.destination.role == DisplayDestinationRole.CLUSTER &&
                record.destination.instanceId.value != "default" &&
                record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL &&
                record.hasCurrentPhysicalTrust() && record.version() != null &&
                record.texture.viewAttached && record.surface.isValid
        }

    /** Version/identity liveness probe also remains true for this registry's current borrower. */
    fun isSameLivePhysicalTarget(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Boolean = synchronized(lock) {
        isSameLivePhysicalTargetLocked(version, surface)
    }

    private fun isSameLivePhysicalTargetLocked(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): Boolean {
        val record = active[version.destination]
        return record != null && record.version() == version && record.surface === surface &&
            record.kind != HostDisplaySurfaceKind.PREVIEW_ONLY &&
            record.hasCurrentPhysicalTrust() &&
            record.texture.viewAttached && record.surface.isValid
    }

    /**
     * This is an inventory borrow, not authority to switch AndroidMediaSink or WebDisplay routes.
     * A future handoff must separately prove exact legacy-route detach and hold a live,
     * manifest-bound Controller output lease before binding the target to EGL. In particular,
     * neither CAF-only activation nor a generic "Activated" result is sufficient. The in-window
     * cluster preview is excluded, even when its Surface is valid. A DisplayManager entry or
     * Presentation window must not be published as PHYSICAL_EXTERNAL without physical trust.
     */
    fun acquirePhysicalTarget(version: HostDisplaySurfaceVersion): BorrowedTargetLease? {
        val acquired = synchronized(lock) {
            val record = active[version.destination] ?: return@synchronized null
            if (record.kind == HostDisplaySurfaceKind.PREVIEW_ONLY ||
                !record.hasCurrentPhysicalTrust() ||
                record.version() != version ||
                record.mainBorrowAdmissionFrozen ||
                !record.texture.viewAttached ||
                !record.surface.isValid ||
                record.texture.surfaces.any { it.borrower != null }
            ) return@synchronized null
            val target = AndroidDisplaySurfaceTarget(
                record.surface,
                DisplaySurfaceDescriptor(
                    DisplaySurfaceId("host-${record.id}-${record.generation}"),
                    version.sizePixels,
                ),
                AndroidSurfaceOwnership.BORROWED,
            )
            val lease = BorrowedTargetLease.create(
                target, version, record.externalTrustToken, this, record.id, record.uiCover,
                record.mainUiCover,
            ).also { record.borrower = it }
            lease to when (record.kind) {
                HostDisplaySurfaceKind.PHYSICAL_EXTERNAL -> record.uiCover?.let { it::claimUltra }
                HostDisplaySurfaceKind.PHYSICAL_MAIN -> record.mainUiCover?.let { it::claimUltra }
                HostDisplaySurfaceKind.PREVIEW_ONLY -> null
            }
        } ?: return null
        val (lease, claimCover) = acquired
        if (claimCover != null && !claimCover()) {
            lease.retireAfterEglProof()
            return null
        }
        return lease.takeUnless { it.isRevoked } ?: run {
            lease.retireAfterEglProof()
            null
        }
    }

    /**
     * Withdraws the exact resource, never the replacement Activity's newer generation.
     * The TextureView callback must return false for a known texture: this owner releases it
     * only after legacy and EGL borrowers retire. [textureDestroyed] is true only in the
     * framework's onSurfaceTextureDestroyed callback, not merely in Activity.onDestroy.
     */
    fun withdraw(
        destination: DisplayDestination,
        surface: Surface,
        texture: SurfaceTexture,
        textureDestroyed: Boolean,
    ): LegacyRetirement? {
        val retirement = mutateAndNotify {
            val record = byTexture[texture]?.surfaces?.firstOrNull {
                it.destination == destination && it.surface === surface
            } ?: return@mutateAndNotify null
            if (record.active && record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) {
                externalTopologyGeneration++
            }
            record.active = false
            requestBorrowerCloseLocked(record)
            if (active[destination] === record) active.remove(destination)
            if (textureDestroyed) markTextureDestroyedLocked(record.texture)
            refreshPendingLocked()
            LegacyRetirement.create(this, record.id)
        }
        if (retirement != null) requestRetirement()
        return retirement
    }

    /** Handles a late callback after the Activity has already detached its local references. */
    fun onTextureDestroyed(texture: SurfaceTexture): Boolean {
        val frameworkOwns = mutateAndNotify {
            val holder = byTexture[texture] ?: return@mutateAndNotify releasedTextures[texture] != true
            markTextureDestroyedLocked(holder)
            refreshPendingLocked()
            false
        }
        if (!frameworkOwns) requestRetirement()
        return frameworkOwns
    }

    /** True only after the old TextureView's exact destroy callback relinquished its texture. */
    fun isTextureViewDetached(texture: SurfaceTexture): Boolean = synchronized(lock) {
        byTexture[texture]?.viewAttached == false || releasedTextures[texture] == true
    }

    /** Executes native/sink operations outside [lock]; a failed action remains retryable. */
    fun retryPendingRetirements() {
        while (true) {
            val action = synchronized(lock) { claimCleanupLocked() } ?: return
            try {
                action.run()
                synchronized(lock) { completeCleanupLocked(action) }
            } catch (failure: Throwable) {
                synchronized(lock) { releaseCleanupClaimLocked(action) }
                throw failure
            }
        }
    }

    /** A worker can avoid polling records that only await a View or EGL retirement proof. */
    fun hasActionableRetirement(): Boolean = synchronized(lock) {
        nextCleanupLocked() != null
    }

    private fun isCurrentBorrow(
        recordId: Long,
        lease: BorrowedTargetLease,
        version: HostDisplaySurfaceVersion,
    ): Boolean = synchronized(lock) {
        val record = byId[recordId]
        record != null && record.borrower === lease && record.active &&
            active[record.destination] === record && record.version() == version &&
            record.texture.viewAttached && record.hasCurrentPhysicalTrust()
    }

    private fun retireBorrow(recordId: Long, lease: BorrowedTargetLease) {
        synchronized(lock) {
            val record = byId[recordId] ?: return@synchronized
            if (record.borrower === lease) {
                record.borrower = null
                record.borrowerCloseAwaitingCover = false
            }
            else if (record.borrower != null) return@synchronized
            refreshPendingLocked()
        }
        requestRetirement()
    }

    private fun completeLegacyRetirement(recordId: Long) {
        synchronized(lock) {
            val record = byId[recordId] ?: return@synchronized
            record.legacyRetired = true
            record.retryLegacyDetach = null
            refreshPendingLocked()
        }
        requestRetirement()
    }

    private fun retainRetryDetacher(recordId: Long, detach: () -> Unit) {
        synchronized(lock) {
            val record = byId[recordId] ?: return@synchronized
            if (!record.legacyRetired) record.retryLegacyDetach = detach
            refreshPendingLocked()
        }
        requestRetirement()
    }

    private fun markTextureDestroyedLocked(holder: TextureRecord) {
        holder.viewAttached = false
        holder.surfaces.forEach { record ->
            if (record.active && record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) {
                externalTopologyGeneration++
            }
            requestBorrowerCloseLocked(record)
            record.active = false
            if (active[record.destination] === record) active.remove(record.destination)
        }
    }

    private fun revokeOtherActiveAliasesLocked(holder: TextureRecord, keep: SurfaceRecord?) {
        holder.surfaces.forEach { record ->
            if (record !== keep && record.active) {
                if (record.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL) {
                    externalTopologyGeneration++
                }
                requestBorrowerCloseLocked(record)
                record.active = false
                if (active[record.destination] === record) active.remove(record.destination)
            }
        }
    }

    private sealed interface CleanupAction {
        data class CoverBeforeBorrowedTargetClose(
            val record: SurfaceRecord,
            val borrower: BorrowedTargetLease,
            val cover: ExternalClusterUiCoverRegistration?,
            val textureAttached: Boolean,
        ) : CleanupAction
        data class Detach(val record: SurfaceRecord, val operation: () -> Unit) : CleanupAction
        data class SurfaceRelease(
            val record: SurfaceRecord,
            val claimedAliases: List<SurfaceRecord>,
        ) : CleanupAction
        data class TextureRelease(val holder: TextureRecord) : CleanupAction
    }

    private fun nextCleanupLocked(): CleanupAction? {
        byId.values.forEach { record ->
            if (record.borrowerCloseAwaitingCover && !record.cleanupInFlight) {
                record.borrower?.let { borrower ->
                    return CleanupAction.CoverBeforeBorrowedTargetClose(
                        record, borrower, record.uiCover, record.texture.viewAttached,
                    )
                }
                record.borrowerCloseAwaitingCover = false
            }
        }
        byId.values.forEach { record ->
            if (!record.cleanupInFlight) {
                if (!record.legacyRetired) record.retryLegacyDetach?.let {
                    return CleanupAction.Detach(record, it)
                }
                val aliases = sameSurfaceRecords(record)
                if (!record.surfaceReleased && aliases.all {
                    !it.active && it.legacyRetired && it.borrower == null && !it.cleanupInFlight
                }) return CleanupAction.SurfaceRelease(record, aliases)
            }
        }
        byTexture.values.forEach { holder ->
            if (!holder.releaseInFlight && !holder.viewAttached && !holder.released &&
                holder.surfaces.all(SurfaceRecord::surfaceReleased)
            ) return CleanupAction.TextureRelease(holder)
        }
        return null
    }

    private fun claimCleanupLocked(): CleanupAction? = nextCleanupLocked()?.also { action ->
        when (action) {
            is CleanupAction.CoverBeforeBorrowedTargetClose ->
                action.record.cleanupInFlight = true
            is CleanupAction.Detach -> action.record.cleanupInFlight = true
            is CleanupAction.SurfaceRelease -> {
                check(action.claimedAliases.none(SurfaceRecord::surfaceReleased)) {
                    "A released Surface cannot be claimed again"
                }
                rememberAttemptedSurfaceReleaseLocked(action.record.surface)
                action.claimedAliases.forEach { it.cleanupInFlight = true }
            }
            is CleanupAction.TextureRelease -> action.holder.releaseInFlight = true
        }
    }

    private fun CleanupAction.run() {
        when (this) {
            is CleanupAction.CoverBeforeBorrowedTargetClose -> {
                val acknowledged = when (borrower.version.kind) {
                    HostDisplaySurfaceKind.PHYSICAL_MAIN ->
                        borrower.coverExactMainUiForRetirement()
                    HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ->
                        cover?.cover(retire = true) == true
                    HostDisplaySurfaceKind.PREVIEW_ONLY -> false
                }
                check(!textureAttached || acknowledged) {
                    "Physical UI cover is not acknowledged; retain exact borrowed target"
                }
                borrower.target.close()
            }
            is CleanupAction.Detach -> operation()
            is CleanupAction.SurfaceRelease -> releaseSurface(record.surface)
            is CleanupAction.TextureRelease -> releaseTexture(holder.texture)
        }
    }

    private fun completeCleanupLocked(action: CleanupAction) {
        when (action) {
            is CleanupAction.CoverBeforeBorrowedTargetClose -> {
                action.record.borrowerCloseAwaitingCover = false
                action.record.cleanupInFlight = false
            }
            is CleanupAction.Detach -> {
                action.record.retryLegacyDetach = null
                action.record.legacyRetired = true
                action.record.cleanupInFlight = false
            }
            is CleanupAction.SurfaceRelease -> {
                action.claimedAliases.forEach {
                    it.surfaceReleased = true
                    it.cleanupInFlight = false
                }
            }
            is CleanupAction.TextureRelease -> {
                val holder = action.holder
                holder.released = true
                holder.releaseInFlight = false
                releasedTextures[holder.texture] = true
                byTexture.remove(holder.texture)
                holder.surfaces.forEach { byId.remove(it.id) }
            }
        }
        refreshPendingLocked()
    }

    private fun releaseCleanupClaimLocked(action: CleanupAction) {
        when (action) {
            is CleanupAction.CoverBeforeBorrowedTargetClose ->
                action.record.cleanupInFlight = false
            is CleanupAction.Detach -> action.record.cleanupInFlight = false
            is CleanupAction.SurfaceRelease -> action.claimedAliases.forEach {
                it.cleanupInFlight = false
            }
            is CleanupAction.TextureRelease -> action.holder.releaseInFlight = false
        }
        refreshPendingLocked()
    }

    private fun refreshPendingLocked() {
        pendingRetirement = byId.values.any { record ->
            record.borrowerCloseAwaitingCover ||
            (!record.active && (!record.legacyRetired || record.borrower != null || !record.surfaceReleased)) ||
                (record.borrower != null &&
                    (record.borrower?.version != record.version() || !record.hasCurrentPhysicalTrust()))
        } || byTexture.values.any { !it.viewAttached && !it.released }
    }

    private fun sameSurfaceRecords(record: SurfaceRecord): List<SurfaceRecord> =
        record.texture.surfaces.filter { it.surface === record.surface }

    private fun hasAttemptedSurfaceReleaseLocked(surface: Surface): Boolean {
        val iterator = attemptedSurfaceReleases.iterator()
        var found = false
        while (iterator.hasNext()) {
            val candidate = iterator.next().get()
            if (candidate == null) iterator.remove()
            else if (candidate === surface) found = true
        }
        return found
    }

    private fun rememberAttemptedSurfaceReleaseLocked(surface: Surface) {
        if (!hasAttemptedSurfaceReleaseLocked(surface)) {
            attemptedSurfaceReleases += WeakReference(surface)
        }
    }

    private fun SurfaceRecord.version(): HostDisplaySurfaceVersion? = sizePixels?.let {
        HostDisplaySurfaceVersion(
            destination, generation, kind, it,
            externalTrustToken?.calibration.takeIf { kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL },
        )
    }

    private fun SurfaceRecord.hasCurrentPhysicalTrust(): Boolean =
        kind != HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ||
            (externalTrustToken?.isCurrent == true &&
                externalTrustToken?.calibration?.destination == destination &&
                externalTrustToken?.calibration?.pixels == sizePixels)

    private fun pixelSizeOrNull(width: Int, height: Int): PixelSize? =
        if (width > 0 && height > 0) PixelSize(width, height) else null
}

/** No Activity, View, or callback capture survives Activity recreation. */
internal object HostPhysicalDisplaySurfaces {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "host-physical-surface-retirement").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)
    val registry = HostPhysicalDisplaySurfaceRegistry(requestRetirement = ::requestRetirement)

    /** Never captures an Activity; native decoder and Surface cleanup run only on this worker. */
    fun requestRetirement() {
        if (!scheduled.compareAndSet(false, true)) return
        executor.execute {
            var failed = false
            try {
                registry.retryPendingRetirements()
            } catch (failure: Throwable) {
                failed = true
                android.util.Log.w("HostSurfaceRegistry", "Surface retirement remains pending", failure)
            } finally {
                scheduled.set(false)
                if (failed) {
                    executor.schedule({ requestRetirement() }, 1_000L, TimeUnit.MILLISECONDS)
                } else if (registry.hasActionableRetirement()) {
                    requestRetirement()
                }
            }
        }
    }
}

/** Existing preview-vs-decoder selection, kept separate from physical inventory borrowing. */
internal interface HostDisplaySurfaceRoutePort {
    fun webDisplayOwns(type: Int): Boolean
    fun preview(type: Int, surface: Surface?)
    fun clearPreview(type: Int, expected: Surface)
    fun setDecoderSurface(type: Int, surface: Surface)
    fun clearDecoderSurface(type: Int, surface: Surface)
}

internal fun attachHostDisplaySurface(port: HostDisplaySurfaceRoutePort, type: Int, surface: Surface) {
    if (port.webDisplayOwns(type)) port.preview(type, surface)
    else port.setDecoderSurface(type, surface)
}

internal fun detachHostDisplaySurface(port: HostDisplaySurfaceRoutePort, type: Int, surface: Surface) {
    if (port.webDisplayOwns(type)) port.clearPreview(type, surface)
    else port.clearDecoderSurface(type, surface)
}
