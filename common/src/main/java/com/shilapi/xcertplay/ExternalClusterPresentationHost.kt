package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A surface callback is scoped to one Presentation generation, never to a replacement window. */
internal interface ExternalClusterPresentationCallbacks {
    fun onAvailable(surface: Any, texture: Any, pixels: PixelSize): Boolean
    fun onSizeChanged(texture: Any, pixels: PixelSize)
    /** True only when the framework, rather than this process, owns the texture release. */
    fun onDestroyed(texture: Any): Boolean
    fun onStopped()
}

internal fun interface ExternalClusterPresentationPort {
    /** Must either return a shown, exact-generation window or dismiss a partial show on failure. */
    fun show(
        candidate: ExternalPhysicalDisplayCandidate,
        callbacks: ExternalClusterPresentationCallbacks,
    ): Closeable
}

internal interface ExternalClusterPublication : Closeable {
    val texture: Any
    val usable: Boolean get() = true
    /** False means the whole output generation must be revoked. */
    fun resize(pixels: PixelSize): Boolean
    /** Idempotent, including a later texture-destroyed callback after initial withdrawal. */
    fun withdraw(textureDestroyed: Boolean): Boolean
    override fun close() { withdraw(false) }
}

/**
 * UI-layer obscuration only. This is not a vehicle hardware blanking, optical scan-out, or
 * exact producer-buffer proof. A permit is usable only after the caller's exact EGL scene has
 * swapped and finished; the same TextureView must then report a later update.
 */
internal class ExternalClusterUiCoverGate(
    private val applyCovered: (sequence: Long, covered: Boolean) -> Boolean,
) {
    private val lock = Any()
    private var sequence = 0L
    private var covered = true
    /** Set only after the UI owner acknowledges an opaque overlay for this exact sequence. */
    private var coverApplied = true
    private var retired = false
    private var ultraClaimed = false
    private var latestUltraPermitTimestampNanos = 0L
    private class UltraPermit(val timestampNanos: Long, val stillAuthorized: () -> Boolean)
    private var ultraPermit: UltraPermit? = null

    val isCovered: Boolean get() = synchronized(lock) { covered }
    val hasAppliedCover: Boolean get() = synchronized(lock) { covered && coverApplied }
    val currentSequence: Long get() = synchronized(lock) { sequence }
    /** After claim, only an exact latched-frame observation may reveal (never a callback read). */
    val isUltraClaimed: Boolean get() = synchronized(lock) { ultraClaimed && !retired }
    /** Covered Ultra permit frame timestamp; 0 when no permit is waiting for its frame. */
    val coveredPermitTimestampNanos: Long
        get() = synchronized(lock) { if (covered) ultraPermit?.timestampNanos ?: 0L else 0L }

    /** An earlier queued reveal may never outrun a later cover. */
    fun cover(retire: Boolean = false): Boolean = coverInternal(retire, preserveNextPermit = false)

    /**
     * With [guardPermit], only the exact [expectedPermit] snapshotted by the caller may be
     * cleared, decided under the same lock as the sequence bump: a newer permit armed by the
     * producer while the caller probed liveness survives this cover and can still reveal.
     */
    private fun coverInternal(
        retire: Boolean,
        preserveNextPermit: Boolean,
        guardPermit: Boolean = false,
        expectedPermit: UltraPermit? = null,
    ): Boolean {
        val serial = synchronized(lock) {
            sequence = Math.addExact(sequence, 1L)
            covered = true
            coverApplied = false
            if (!preserveNextPermit && (!guardPermit || ultraPermit === expectedPermit)) {
                ultraPermit = null
            }
            if (retire) retired = true
            sequence
        }
        val applied = runCatching { applyCovered(serial, true) }.getOrDefault(false)
        synchronized(lock) {
            if (sequence == serial && covered && applied) coverApplied = true
        }
        return applied
    }

    /** One-way handoff: the legacy decoder may not reveal old pixels after physical borrow. */
    fun claimUltra(): Boolean {
        synchronized(lock) {
            if (retired) return false
            ultraClaimed = true
        }
        return cover()
    }

    fun armUltra(
        expectedTimestampNanos: Long,
        clearBeforeReveal: Boolean = false,
        stillAuthorized: () -> Boolean,
    ): Boolean {
        if (clearBeforeReveal && !cover()) return false
        return synchronized(lock) {
            if (retired || !ultraClaimed || (covered && !coverApplied) ||
                expectedTimestampNanos <= latestUltraPermitTimestampNanos ||
                !runCatching(stillAuthorized).getOrDefault(false)
            ) return@synchronized false
            latestUltraPermitTimestampNanos = expectedTimestampNanos
            ultraPermit = UltraPermit(expectedTimestampNanos, stillAuthorized)
            true
        }
    }

    /** A lease/freshness probe is not a frame-cadence guess; static content remains valid. */
    fun recheckCurrent(
        exactCurrent: () -> Boolean,
        legacyRouteCurrent: () -> Boolean,
    ): Boolean {
        val state = synchronized(lock) { Triple(covered, ultraClaimed, ultraPermit) }
        if (state.first) return true
        val exact = runCatching(exactCurrent).getOrDefault(false)
        val valid = exact && if (state.second) {
            state.third?.let { runCatching(it.stillAuthorized).getOrDefault(false) } == true
        } else runCatching(legacyRouteCurrent).getOrDefault(false)
        if (!valid) cover(retire = !exact)
        return valid
    }

    /** Only the exact active SurfaceTexture registration may call this method. */
    fun onTextureUpdated(
        timestampNanos: Long,
        exactCurrent: () -> Boolean,
        legacyRouteCurrent: () -> Boolean,
    ): Boolean {
        val permit = synchronized(lock) {
            if (retired) return false
            ultraPermit
        }
        val exact = runCatching(exactCurrent).getOrDefault(false)
        val allowed = exact && if (permit != null) {
            timestampNanos == permit.timestampNanos &&
                runCatching(permit.stillAuthorized).getOrDefault(false)
        } else synchronized(lock) {
            !ultraClaimed && runCatching(legacyRouteCurrent).getOrDefault(false)
        }
        if (!allowed) {
            if (exact && permit != null && timestampNanos > 0L &&
                timestampNanos < permit.timestampNanos &&
                runCatching(permit.stillAuthorized).getOrDefault(false)
            ) coverInternal(retire = false, preserveNextPermit = true)
            else if (!exact || permit != null) coverInternal(
                retire = !exact, preserveNextPermit = false,
                guardPermit = true, expectedPermit = permit,
            )
            return false
        }
        val serial = synchronized(lock) {
            if (retired || (permit != null && ultraPermit !== permit) ||
                (permit == null && ultraClaimed) ||
                !runCatching(exactCurrent).getOrDefault(false) ||
                (permit == null && !runCatching(legacyRouteCurrent).getOrDefault(false)) ||
                (permit != null && (timestampNanos != permit.timestampNanos ||
                    !runCatching(permit.stillAuthorized).getOrDefault(false)))
            ) null else {
            sequence = Math.addExact(sequence, 1L)
            covered = false
            coverApplied = false
            sequence
            }
        } ?: run {
            coverInternal(
                retire = !runCatching(exactCurrent).getOrDefault(false),
                preserveNextPermit = false, guardPermit = true, expectedPermit = permit,
            )
            return false
        }
        val applied = runCatching { applyCovered(serial, false) }.getOrDefault(false)
        if (!applied) cover()
        return applied
    }
}

/**
 * Exact publication-only retry barrier. It never retains a Presentation, View, Activity or
 * controller. In particular, a transient registry/decoder failure cannot hand native ownership
 * back to TextureView or silently lose the only Surface retirement path.
 */
internal object ProcessOwnedExternalClusterPublicationRetirer {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "external-cluster-publication-retirement").apply { isDaemon = true }
    }
    private val pending = IdentityHashMap<ExternalClusterPublication, Boolean>()
    private val scheduled = AtomicBoolean(false)

    fun retire(publication: ExternalClusterPublication, textureDestroyed: Boolean): Boolean {
        try {
            publication.withdraw(textureDestroyed)
            return true
        } catch (_: Throwable) {
            synchronized(pending) {
                pending[publication] = pending[publication] == true || textureDestroyed
            }
            schedule()
            return false
        }
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        executor.schedule({
            val attempts = synchronized(pending) { pending.entries.map { it.key to it.value } }
            attempts.forEach { (publication, destroyed) ->
                if (runCatching { publication.withdraw(destroyed) }.isSuccess) {
                    synchronized(pending) {
                        // A concurrent destroyed callback may have upgraded the pending proof.
                        if (pending[publication] == destroyed) pending.remove(publication)
                    }
                }
            }
            scheduled.set(false)
            if (synchronized(pending) { pending.isNotEmpty() }) schedule()
        }, 250L, TimeUnit.MILLISECONDS)
    }
}

/** A close receipt owns exactly one resource and releases that reference only after success. */
internal class ExactExternalClusterClose(resource: Closeable) : Closeable {
    private var resource: Closeable? = resource

    @get:Synchronized
    val isClosed: Boolean get() = resource == null

    @Synchronized
    override fun close() {
        val owned = resource ?: return
        owned.close()
        resource = null
    }
}

/**
 * Process-owned retry queue for exact external-display resources. A failed close remains strongly
 * owned until the same receipt succeeds; an unrelated replacement is never used for retry.
 */
internal class ExternalClusterCloseRetirementQueue(
    private val scheduleRetry: (() -> Unit) -> Unit,
) {
    private val pending = IdentityHashMap<Closeable, Unit>()
    private var scheduled = false

    fun retire(resource: Closeable): Boolean {
        val succeeded = runCatching { resource.close() }.isSuccess
        synchronized(pending) {
            if (succeeded) pending.remove(resource) else pending[resource] = Unit
            scheduleIfNeeded()
        }
        return succeeded
    }

    /** One pass is also used by deterministic tests; production schedules the next pass. */
    fun retryPending() {
        val attempts = synchronized(pending) { pending.keys.toList() }
        attempts.forEach { resource ->
            val succeeded = runCatching { resource.close() }.isSuccess
            if (succeeded) synchronized(pending) { pending.remove(resource) }
        }
        synchronized(pending) {
            scheduled = false
            scheduleIfNeeded()
        }
    }

    private fun scheduleIfNeeded() {
        if (pending.isEmpty() || scheduled) return
        scheduled = true
        scheduleRetry(::retryPending)
    }
}

internal object ProcessOwnedExternalClusterCloseRetirer {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "external-cluster-resource-retirement").apply { isDaemon = true }
    }
    private val queue = ExternalClusterCloseRetirementQueue { retry ->
        executor.schedule(retry, 250L, TimeUnit.MILLISECONDS)
    }

    fun retire(resource: Closeable): Boolean = queue.retire(resource)
}

internal data class ExternalClusterReadySnapshot(
    /** The exact publication object, not a destination or reusable display ID. */
    val publication: Any,
    val destination: DisplayDestination,
)

/** One-shot readiness signal; callers are never invoked on the controller/registry lock stack. */
internal class ExternalClusterReadySignals(
    private val dispatch: (Runnable) -> Boolean,
    private val snapshot: () -> ExternalClusterReadySnapshot?,
) : Closeable {
    private class Subscriber(val callback: (DisplayDestination) -> Unit) : Closeable {
        private var closed = false

        @Synchronized
        fun deliver(destination: DisplayDestination) {
            if (closed) return
            closed = true
            callback(destination)
        }

        @Synchronized
        override fun close() { closed = true }
    }

    private val lock = Any()
    private val subscribers = LinkedHashSet<Subscriber>()
    private val delivering = LinkedHashSet<Subscriber>()
    private var closed = false

    fun subscribe(callback: (DisplayDestination) -> Unit): Closeable {
        val subscriber = Subscriber(callback)
        synchronized(lock) {
            if (closed) {
                subscriber.close()
                return subscriber
            }
            subscribers += subscriber
        }
        snapshot()?.let { offer(it.publication) }
        return Closeable {
            synchronized(lock) { subscribers.remove(subscriber) }
            subscriber.close()
        }
    }

    fun offer(publication: Any) {
        val shouldPost = synchronized(lock) { !closed && subscribers.isNotEmpty() }
        if (!shouldPost) return
        if (!dispatch(Runnable { deliver(publication) })) {
            // A stopped handler cannot be used as a substitute for a ready notification.
            close()
        }
    }

    private fun deliver(publication: Any) {
        val current = snapshot()?.takeIf { it.publication === publication } ?: return
        val recipients = synchronized(lock) {
            if (closed) return
            subscribers.toList().also {
                subscribers.clear()
                delivering.addAll(it)
            }
        }
        recipients.forEach { subscriber ->
            try {
                // A revocation after the first snapshot must not consume a pending owner.
                if (snapshot()?.publication === publication) {
                    // One subscriber cannot starve other ready owners or escape onto the Handler.
                    runCatching { subscriber.deliver(current.destination) }
                } else {
                    synchronized(lock) { if (!closed) subscribers += subscriber }
                }
            } finally {
                synchronized(lock) { delivering.remove(subscriber) }
            }
        }
    }

    override fun close() {
        val recipients = synchronized(lock) {
            closed = true
            (subscribers + delivering).toList().also { subscribers.clear() }
        }
        recipients.forEach { it.close() }
    }
}

internal fun interface ExternalClusterPublicationPort {
    /** A returned publication owns the Surface and Texture, even if [usable] is false. */
    fun publish(
        destination: DisplayDestination,
        surface: Any,
        texture: Any,
        pixels: PixelSize,
        trustGate: ExternalClusterTrustGate,
        stillAuthorized: () -> Boolean,
    ): ExternalClusterPublication
}

/** The registry token is minted only by the exact process registry after lease verification. */
internal interface ExternalClusterTrustGate {
    val registryToken: ExternalPhysicalTargetTrustToken?
    val calibration: ExternalPhysicalDisplayCalibration
    val isCurrent: Boolean
    fun invalidate()
}

internal class RegistryExternalClusterTrustGate(
    override val registryToken: ExternalPhysicalTargetTrustToken,
) : ExternalClusterTrustGate {
    override val calibration: ExternalPhysicalDisplayCalibration get() = registryToken.calibration
    override val isCurrent: Boolean get() = registryToken.isCurrent
    override fun invalidate() = registryToken.invalidate()
}

/**
 * Pure generation controller. Ports make display enumeration, window creation, native Surface
 * ownership and exact legacy-route retirement testable without Android framework objects.
 * No lease is synthesized from DisplayManager. All caller callbacks must be serialized; the
 * Android wrapper dispatches them on the main handler.
 */
internal class ExternalClusterPresentationController(
    private val trustProvider: ExternalPhysicalDisplayTrustProvider,
    private val presentationPort: ExternalClusterPresentationPort,
    private val publicationPort: ExternalClusterPublicationPort,
    private val candidateIsCurrent: (ExternalPhysicalDisplayCandidate) -> Boolean,
    private val trustGateFactory: (ExternalPhysicalDisplayCalibration) -> ExternalClusterTrustGate,
    /** Must enqueue work; it must not invoke a product callback on this controller's lock stack. */
    private val onReadyPublication: (Any) -> Unit = {},
) : Closeable {
    private class Generation(
        val candidate: ExternalPhysicalDisplayCandidate,
        val lease: ExternalPhysicalDisplayLease,
        val calibration: ExternalPhysicalDisplayCalibration,
    ) {
        val leaseRetirement = ExactExternalClusterClose(lease)
        var revocation: Closeable? = null
        var window: Closeable? = null
        var publication: ExternalClusterPublication? = null
        val trustGate = AtomicReference<ExternalClusterTrustGate?>()
        val revoked = AtomicBoolean(false)
        var inFlightTexture: Any? = null
        var inFlightDestroyed = false
        // A duplicate callback must still return false, but a retired publication must not
        // retain every historical SurfaceTexture for this entire Presentation generation.
        private val retiredTextures = ArrayList<WeakReference<Any>>()
        var stopped = false

        fun rememberRetiredTexture(texture: Any) {
            if (!wasRetiredTexture(texture)) retiredTextures += WeakReference(texture)
        }

        fun wasRetiredTexture(texture: Any): Boolean {
            var found = false
            val iterator = retiredTextures.iterator()
            while (iterator.hasNext()) {
                val previous = iterator.next().get()
                if (previous == null) iterator.remove()
                else if (previous === texture) found = true
            }
            return found
        }
    }

    private var active: Generation? = null
    private var closed = false
    private var lastCandidates: List<ExternalPhysicalDisplayCandidate> = emptyList()
    private val pendingCloseReceipts = IdentityHashMap<ExactExternalClusterClose, Unit>()
    private val pendingPublications = IdentityHashMap<ExternalClusterPublication, Boolean>()

    private fun retire(resource: ExactExternalClusterClose) {
        if (!ProcessOwnedExternalClusterCloseRetirer.retire(resource)) {
            pendingCloseReceipts[resource] = Unit
        } else {
            pendingCloseReceipts.remove(resource)
        }
    }

    private fun retryPendingCloses(): Boolean {
        pendingCloseReceipts.keys.toList().forEach(::retire)
        return pendingCloseReceipts.isEmpty()
    }

    private fun retirePublication(publication: ExternalClusterPublication, textureDestroyed: Boolean) {
        val proof = pendingPublications[publication] == true || textureDestroyed
        if (ProcessOwnedExternalClusterPublicationRetirer.retire(publication, proof)) {
            pendingPublications.remove(publication)
        } else {
            pendingPublications[publication] = proof
        }
    }

    private fun retryPendingPublications(): Boolean {
        pendingPublications.entries.toList().forEach { (publication, destroyed) ->
            retirePublication(publication, destroyed)
        }
        return pendingPublications.isEmpty()
    }

    private data class VerifiedCandidate(
        val candidate: ExternalPhysicalDisplayCandidate,
        val lease: ExternalPhysicalDisplayLease,
        val calibration: ExternalPhysicalDisplayCalibration,
    )

    private fun acquireVerified(candidate: ExternalPhysicalDisplayCandidate): VerifiedCandidate? {
        if (!runCatching { candidateIsCurrent(candidate) }.getOrDefault(false)) return null
        val lease = runCatching { trustProvider.acquire(candidate) }.getOrNull() ?: return null
        val calibration = runCatching { lease.calibrationSnapshot(candidate) }.getOrNull()
        if (calibration == null) {
            retire(ExactExternalClusterClose(lease))
            return null
        }
        return VerifiedCandidate(candidate, lease, calibration)
    }

    @Synchronized
    fun activeDestination(): DisplayDestination? = readySnapshot()?.destination

    @Synchronized
    fun readySnapshot(): ExternalClusterReadySnapshot? {
        val generation = active?.takeIf { !it.stopped && isLive(it) } ?: return null
        val publication = generation.publication?.takeIf {
            runCatching { it.usable }.getOrDefault(false)
        } ?: return null
        return ExternalClusterReadySnapshot(publication, generation.calibration.destination)
    }

    @Synchronized
    fun onCandidatesChanged(candidates: List<ExternalPhysicalDisplayCandidate>) {
        if (closed) return
        lastCandidates = candidates.distinct()
        val current = active
        if (current != null && current.candidate in lastCandidates && isLive(current)) {
            // A first valid connector is not a uniqueness proof. Recheck all other candidates
            // before preserving an already advertised external panel.
            var competingTrustedConnector = false
            lastCandidates.filterNot { it == current.candidate }.forEach { candidate ->
                acquireVerified(candidate)?.let { competing ->
                    competingTrustedConnector = true
                    retire(ExactExternalClusterClose(competing.lease))
                }
            }
            if (!competingTrustedConnector) return
            stop(current)
            return
        }
        if (current != null) stop(current)
        var selected: VerifiedCandidate? = null
        for (candidate in lastCandidates) {
            val verified = acquireVerified(candidate) ?: continue
            if (selected != null) {
                val previous = selected
                retire(ExactExternalClusterClose(previous.lease))
                retire(ExactExternalClusterClose(verified.lease))
                return
            }
            selected = verified
        }
        val verified = selected ?: return
        val generation = Generation(verified.candidate, verified.lease, verified.calibration)
        val candidate = verified.candidate
        val lease = verified.lease
        try {
            active = generation
            val subscription = lease.subscribeRevocation {
                generation.revoked.set(true)
                // Atomic token invalidation precedes any controller-lock wait.
                generation.trustGate.get()?.let { runCatching { it.invalidate() } }
                onLeaseRevoked(generation)
            }
            if (active !== generation) {
                retire(ExactExternalClusterClose(subscription))
                return
            }
            generation.revocation = subscription
            if (!isLive(generation)) {
                stop(generation)
                return
            }
            val callbacks = callbacksFor(generation)
            val window = presentationPort.show(candidate, callbacks)
            if (active !== generation) {
                retire(ExactExternalClusterClose(window))
            } else {
                generation.window = window
            }
        } catch (_: Throwable) {
            // A denied/removed display, lease error, or failed show never publishes a panel.
            stop(generation)
        }
    }

    @Synchronized
    private fun onLeaseRevoked(generation: Generation) {
        val current = active ?: return
        if (current === generation) stop(current)
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            active?.let(::stop)
            lastCandidates = emptyList()
        }
        val resourcesClosed = retryPendingCloses()
        val publicationsWithdrawn = retryPendingPublications()
        check(resourcesClosed && publicationsWithdrawn) {
            "External Presentation resource retirement is incomplete; retry this exact controller close"
        }
    }

    private fun callbacksFor(generation: Generation) = object : ExternalClusterPresentationCallbacks {
        override fun onAvailable(surface: Any, texture: Any, pixels: PixelSize): Boolean =
            synchronized(this@ExternalClusterPresentationController) {
                if (active !== generation || generation.publication != null) return@synchronized false
                if (!isLive(generation) ||
                    !runCatching { pixels == generation.lease.pixels }.getOrDefault(false)
                ) {
                    stop(generation)
                    return@synchronized false
                }
                val trustGate = try {
                    trustGateFactory(generation.calibration).also { generation.trustGate.set(it) }
                } catch (_: Throwable) {
                    stop(generation)
                    return@synchronized false
                }
                if (trustGate.calibration != generation.calibration || !trustGate.isCurrent) {
                    stop(generation)
                    return@synchronized false
                }
                generation.inFlightTexture = texture
                generation.inFlightDestroyed = false
                val publication = try {
                    publicationPort.publish(
                        generation.calibration.destination, surface, texture, pixels,
                        trustGate,
                        { active === generation && !generation.stopped && isLive(generation) &&
                            trustGate.isCurrent },
                    )
                } catch (_: Throwable) {
                    runCatching { trustGate.invalidate() }
                    generation.trustGate.compareAndSet(trustGate, null)
                    generation.inFlightTexture = null
                    // The port contract requires no ownership transfer on throw. A failed
                    // publication cannot leave a seemingly active blank Presentation.
                    stop(generation)
                    return@synchronized false
                }
                // publish() may synchronously revoke the lease while routeFactory.attach runs.
                // Keep process ownership of its returned Surface even in that reentrant case.
                generation.publication = publication
                val destroyedDuringPublish = generation.inFlightDestroyed
                generation.inFlightTexture = null
                if (destroyedDuringPublish) {
                    runCatching { trustGate.invalidate() }
                    generation.trustGate.compareAndSet(trustGate, null)
                    retirePublication(publication, true)
                    generation.rememberRetiredTexture(texture)
                    generation.publication = null
                    return@synchronized true
                }
                if (active !== generation || generation.stopped || !isLive(generation) ||
                    !trustGate.isCurrent ||
                    !runCatching { publication.usable }.getOrDefault(false)
                ) {
                    if (!generation.stopped) stop(generation)
                    else retirePublication(publication, false)
                } else {
                    // The signal only queues a task. The task must recheck this same publication.
                    runCatching { onReadyPublication(publication) }
                }
                // Ownership was transferred even if the exact route immediately failed.
                true
            }

        override fun onSizeChanged(texture: Any, pixels: PixelSize) {
            synchronized(this@ExternalClusterPresentationController) {
                val publication = generation.publication ?: return
                if (publication.texture !== texture || active !== generation) return
                if (!runCatching { pixels == generation.lease.pixels }.getOrDefault(false) ||
                    !isLive(generation) ||
                    !runCatching { publication.resize(pixels) }.getOrDefault(false)
                ) stop(generation)
            }
        }

        override fun onDestroyed(texture: Any): Boolean =
            synchronized(this@ExternalClusterPresentationController) {
                val publication = generation.publication
                if (publication != null && publication.texture === texture) {
                    generation.trustGate.getAndSet(null)?.let {
                        runCatching { it.invalidate() }
                    }
                    retirePublication(publication, true)
                    generation.rememberRetiredTexture(texture)
                    generation.publication = null
                    return@synchronized false
                }
                if (generation.inFlightTexture === texture) {
                    generation.inFlightDestroyed = true
                    generation.trustGate.get()?.let { runCatching { it.invalidate() } }
                    return@synchronized false
                }
                !generation.wasRetiredTexture(texture)
            }

        override fun onStopped() {
            synchronized(this@ExternalClusterPresentationController) {
                if (active === generation) stop(generation)
                // Presentation stop/removal is not a TextureView destruction callback. Keep
                // process ownership, but release its SurfaceTexture only after onDestroyed.
                generation.publication?.let {
                    retirePublication(it, false)
                }
            }
        }
    }

    /** Withdraws the exact generation before dismissing its window or closing its trust lease. */
    private fun stop(generation: Generation) {
        if (generation.stopped) return
        generation.stopped = true
        generation.revoked.set(true)
        if (active === generation) active = null
        generation.trustGate.getAndSet(null)?.let { runCatching { it.invalidate() } }
        generation.publication?.let {
            retirePublication(it, false)
        }
        generation.window?.let { retire(ExactExternalClusterClose(it)) }
        generation.revocation?.let { retire(ExactExternalClusterClose(it)) }
        retire(generation.leaseRetirement)
    }

    private fun isLive(generation: Generation): Boolean =
        runCatching {
            !generation.revoked.get() && candidateIsCurrent(generation.candidate) &&
                generation.lease.calibrationSnapshot(generation.candidate) == generation.calibration &&
                (generation.trustGate.get()?.isCurrent != false)
        }.getOrDefault(false)
}

/**
 * An exact legacy type-111 decoder route. A null result denies physical promotion. attach()
 * must either return a resource-only lease or leave no route behind when it throws. The lease
 * must not capture a replacement Activity/sink.
 */
internal fun interface ExternalClusterLegacyRouteFactory {
    fun attach(destination: DisplayDestination, surface: Surface): ExternalClusterLegacyRoute?

    companion object {
        val NONE = ExternalClusterLegacyRouteFactory { _, _ -> null }
    }
}

internal fun interface ExternalClusterLegacyRoute {
    /** A pre-SETUP route reservation is valid; this does not claim that a decoder has a frame. */
    val isCurrent: Boolean get() = true

    /** Product routes expose frozen sink geometry; test ports may leave it unspecified. */
    val configuredPixels: PixelSize? get() = null

    /** An attached decoder plus this TextureView's update, not pre-SETUP route readiness. */
    val decoderAttached: Boolean get() = false

    /** Revocation is delivered outside the sink's routing locks by a product route lease. */
    fun subscribeRevocation(onRevoked: () -> Unit): Closeable = Closeable { }

    /** Retryable and idempotent; captures the exact decoder/WebDisplay owner used at attach. */
    fun retire()
}

/**
 * Not installed in the product root. A future trusted provider must be independently verified,
 * and any activation using this output must retain and recheck that same lease through retirement.
 */
internal class ExternalClusterPresentationHost(
    context: Context,
    trustProvider: ExternalPhysicalDisplayTrustProvider = ExternalPhysicalDisplayTrustProvider.DENY_ALL,
    private val source: ExternalPhysicalDisplaySource = AndroidExternalPhysicalDisplaySource(context),
    registry: HostPhysicalDisplaySurfaceRegistry = HostPhysicalDisplaySurfaces.registry,
    routeFactory: ExternalClusterLegacyRouteFactory = ExternalClusterLegacyRouteFactory.NONE,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : Closeable {
    private val applicationContext = context.applicationContext
    private val displayManager = requireNotNull(
        applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager,
    )
    private val readiness: ExternalClusterReadySignals = ExternalClusterReadySignals(
        dispatch = { task -> handler.post(task) },
        snapshot = { controller.readySnapshot() },
    )
    private val controller: ExternalClusterPresentationController = ExternalClusterPresentationController(
        trustProvider = trustProvider,
        presentationPort = ExternalClusterPresentationPort { candidate, callbacks ->
            val display = displayManager.getDisplay(candidate.displayId)
                ?.takeIf { it.isValid && it.displayId != Display.DEFAULT_DISPLAY &&
                    it.mode.physicalWidth == candidate.pixels.widthPixels &&
                    it.mode.physicalHeight == candidate.pixels.heightPixels &&
                    source.isCurrent(candidate)
                } ?: error("External display candidate expired before Presentation.show")
            val presentation = ClusterSurfacePresentation(
                applicationContext, display, candidate, registry, handler, callbacks,
            )
            val dismissal = ExactExternalClusterClose(PresentationDismissal(presentation, handler))
            try {
                presentation.show()
            } catch (failure: Throwable) {
                ProcessOwnedExternalClusterCloseRetirer.retire(dismissal)
                throw failure
            }
            dismissal
        },
        publicationPort = RegistryExternalClusterPublicationPort(registry, routeFactory),
        candidateIsCurrent = source::isCurrent,
        trustGateFactory = { calibration ->
            RegistryExternalClusterTrustGate(registry.newExternalPhysicalTargetTrustToken(calibration))
        },
        onReadyPublication = readiness::offer,
    )
    private val subscription = try {
        source.observe { candidates ->
            if (Looper.myLooper() == Looper.getMainLooper()) controller.onCandidatesChanged(candidates)
            else handler.post { controller.onCandidatesChanged(candidates) }
        }
    } catch (failure: Throwable) {
        readiness.close()
        ProcessOwnedExternalClusterCloseRetirer.retire(ExactExternalClusterClose(source))
        runCatching { controller.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    private val subscriptionRetirement = ExactExternalClusterClose(subscription)
    private val sourceRetirement = ExactExternalClusterClose(source)

    fun activeDestination(): DisplayDestination? = controller.activeDestination()

    /** One-shot: replay the current usable publication or await the next exact generation. */
    fun subscribeReady(onReady: (DisplayDestination) -> Unit): Closeable = readiness.subscribe(onReady)

    override fun close() {
        readiness.close()
        val subscriptionClosed = ProcessOwnedExternalClusterCloseRetirer.retire(subscriptionRetirement)
        val sourceClosed = ProcessOwnedExternalClusterCloseRetirer.retire(sourceRetirement)
        val controllerClosed = runCatching { controller.close() }.isSuccess
        check(subscriptionClosed && sourceClosed && controllerClosed) {
            "External cluster host retirement is incomplete; retry close on this exact host"
        }
    }

    /** A background retry cannot report success merely because it posted a main-thread dismiss. */
    private class PresentationDismissal(
        presentation: ClusterSurfacePresentation,
        private val handler: Handler,
    ) : Closeable {
        private var presentation: ClusterSurfacePresentation? = presentation
        private var queued = false

        override fun close() {
            val current = synchronized(this) { presentation ?: return }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val shouldPost = synchronized(this) {
                    if (presentation == null) return
                    if (queued) false else { queued = true; true }
                }
                if (shouldPost && !handler.post {
                    try {
                        close()
                    } catch (_: Throwable) {
                        synchronized(this) { queued = false }
                    }
                }) {
                    synchronized(this) { queued = false }
                    error("Main-thread Presentation dismissal could not be posted")
                }
                error("Main-thread Presentation dismissal is still pending")
            }
            try {
                current.dismiss()
            } catch (failure: Throwable) {
                synchronized(this) { queued = false }
                throw failure
            }
            synchronized(this) {
                if (presentation === current) presentation = null
                queued = false
            }
        }
    }

    private class ClusterSurfacePresentation(
        outerContext: Context,
        display: Display,
        private val candidate: ExternalPhysicalDisplayCandidate,
        private val registry: HostPhysicalDisplaySurfaceRegistry,
        private val handler: Handler,
        private val callbacks: ExternalClusterPresentationCallbacks,
    ) : Presentation(outerContext, display) {
        private class PendingTexture(val surface: Surface) {
            var destroyed = false
        }

        private var activeCover: ExternalClusterUiCoverGate? = null

        /** A delayed UI task can apply only to this gate and its latest cover sequence. */
        private fun applyCover(
            gate: ExternalClusterUiCoverGate,
            overlay: View,
            sequence: Long,
            covered: Boolean,
        ): Boolean {
            val applied = AtomicBoolean(false)
            val completion = CountDownLatch(1)
            val action = Runnable {
                if (activeCover !== gate) {
                    // A successor texture has its own initially opaque overlay. The old gate
                    // cannot reveal it, so retirement of the old resource may continue.
                    applied.set(true)
                } else if (gate.currentSequence == sequence) {
                    overlay.visibility = if (covered) View.VISIBLE else View.GONE
                    applied.set(true)
                }
                completion.countDown()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action.run()
            else if (!handler.post(action)) return false
            // Never claim a synchronous UI cover if the Presentation's main thread is stuck.
            return completion.await(500L, TimeUnit.MILLISECONDS) && applied.get()
        }

        private fun coverBeforeStop() {
            activeCover?.cover(retire = true)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val surfaceView = TextureView(context)
            val unavailable = TextView(context).apply {
                text = "DATA UNAVAILABLE"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                visibility = View.VISIBLE
            }
            surfaceView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private val acceptedTextures = java.util.IdentityHashMap<SurfaceTexture, Boolean>()
                private val pendingTextures = java.util.IdentityHashMap<SurfaceTexture, PendingTexture>()
                private val covers = java.util.IdentityHashMap<SurfaceTexture,
                    HostPhysicalDisplaySurfaceRegistry.ExternalClusterUiCoverRegistration>()

                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    activeCover?.cover(retire = true)
                    unavailable.visibility = View.VISIBLE
                    lateinit var gate: ExternalClusterUiCoverGate
                    gate = ExternalClusterUiCoverGate { sequence, covered ->
                        applyCover(gate, unavailable, sequence, covered)
                    }
                    activeCover = gate
                    val surface = Surface(texture)
                    val pending = PendingTexture(surface)
                    pendingTextures[texture] = pending
                    val accepted = runCatching {
                        width > 0 && height > 0 &&
                            callbacks.onAvailable(surface, texture, PixelSize(width, height))
                    }.getOrDefault(false)
                    pendingTextures.remove(texture)
                    if (accepted) {
                        val weakView = java.lang.ref.WeakReference(surfaceView)
                        registry.bindExternalClusterUiCover(
                            surface, texture, candidate.displayId, candidate.displayEpoch, gate,
                            requestRedraw = {
                                weakView.get()?.let { view -> view.post { view.invalidate() } }
                            },
                        )?.let { covers[texture] = it } ?: gate.cover(retire = true)
                        if (!pending.destroyed) acceptedTextures[texture] = true
                        else {
                            covers.remove(texture)?.close()
                            runCatching { callbacks.onDestroyed(texture) }
                        }
                    } else {
                        gate.cover(retire = true)
                        surface.release()
                        // onDestroyed already returned false while availability was in flight.
                        if (pending.destroyed) texture.release()
                    }
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    if (acceptedTextures.containsKey(texture) && width > 0 && height > 0) {
                        callbacks.onSizeChanged(texture, PixelSize(width, height))
                    }
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    covers.remove(texture)?.close()
                    pendingTextures[texture]?.let { pending ->
                        pending.destroyed = true
                        runCatching { callbacks.onDestroyed(texture) }
                        return false
                    }
                    if (acceptedTextures.remove(texture) != true) return true
                    // The process registry, not TextureView, releases this exact texture.
                    runCatching { callbacks.onDestroyed(texture) }
                    return false
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                    val cover = covers[texture] ?: return
                    // Ordinary decoder output keeps its callback-driven reveal. An Ultra permit
                    // is matched only against the image HWUI latched for this draw, which is
                    // readable after the draw has synced, never inside this callback.
                    cover.onTextureCallback(texture, texture.timestamp)
                    surfaceView.post {
                        if (covers[texture] === cover) cover.observeLatchedFrame(texture)
                    }
                }
            }
            setContentView(FrameLayout(context).apply {
                addView(surfaceView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                addView(unavailable, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            })
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        override fun onDisplayChanged() {
            try { coverBeforeStop(); callbacks.onStopped() } finally { super.onDisplayChanged() }
        }

        override fun onDisplayRemoved() {
            try { coverBeforeStop(); callbacks.onStopped() } finally { super.onDisplayRemoved() }
        }

        override fun onStop() {
            try { coverBeforeStop(); callbacks.onStopped() } finally { super.onStop() }
        }
    }
}

internal class RegistryExternalClusterPublicationPort(
    private val registry: HostPhysicalDisplaySurfaceRegistry,
    private val routeFactory: ExternalClusterLegacyRouteFactory,
) : ExternalClusterPublicationPort {
    override fun publish(
        destination: DisplayDestination,
        surface: Any,
        texture: Any,
        pixels: PixelSize,
        trustGate: ExternalClusterTrustGate,
        stillAuthorized: () -> Boolean,
    ): ExternalClusterPublication {
        require(destination.role == DisplayDestinationRole.CLUSTER && destination.instanceId.value != "default")
        val androidSurface = surface as Surface
        val androidTexture = texture as SurfaceTexture
        require(androidSurface.isValid)
        check(registry.snapshot(destination) == null) {
            "Another producer already owns this external cluster destination"
        }
        val staged = registry.publish(
            destination, HostDisplaySurfaceKind.PREVIEW_ONLY,
            androidSurface, androidTexture, pixels.widthPixels, pixels.heightPixels,
        ) ?: error("External Surface could not be published")
        val publication = RegistryPublication(
            registry, destination, androidSurface, androidTexture, trustGate,
        )
        try {
            val route = requireNotNull(routeFactory.attach(destination, androidSurface)) {
                "External physical output requires an exact legacy type-111 route lease"
            }
            publication.route = route
            check(route.isCurrent) { "External legacy type-111 route was not current" }
            check(route.configuredPixels?.let { it == pixels } != false) {
                "External route pixels differ from its Presentation Surface"
            }
            // A route may install a decoder producer. Keep the record unborrowable until that
            // exact route is known and the registry still owns this staging generation.
            check(registry.snapshot(destination) == staged) {
                "External Surface staging generation changed during route attach"
            }
            check(stillAuthorized()) {
                "External display lease was revoked during route attach"
            }
            val token = requireNotNull(trustGate.registryToken) {
                "External publication requires its exact registry trust token"
            }
            token.bindRouteProof { route.isCurrent }
            token.bindLegacyDecoderProof { route.decoderAttached }
            publication.routeRevocation = route.subscribeRevocation(token::invalidate)
            check(token.calibration == trustGate.calibration &&
                token.calibration.destination == destination && token.calibration.pixels == pixels &&
                token.isCurrent) {
                "External publication differs from its verified connector calibration"
            }
            check(registry.publish(
                destination, HostDisplaySurfaceKind.PHYSICAL_EXTERNAL,
                androidSurface, androidTexture, pixels.widthPixels, pixels.heightPixels,
                externalTrustToken = token,
            )?.kind == HostDisplaySurfaceKind.PHYSICAL_EXTERNAL)
        } catch (_: Throwable) {
            // attach() is all-or-nothing on throw. Keep registry ownership; the controller
            // retires this unusable generation, and TextureView still returns false.
            publication.failed = true
        }
        return publication
    }

    private class RegistryPublication(
        private val registry: HostPhysicalDisplaySurfaceRegistry,
        private val destination: DisplayDestination,
        private val surface: Surface,
        override val texture: SurfaceTexture,
        private val trustGate: ExternalClusterTrustGate,
    ) : ExternalClusterPublication {
        var route: ExternalClusterLegacyRoute? = null
        var routeRevocation: Closeable? = null
        var failed = false
        override val usable: Boolean get() = !failed && !withdrawn &&
            trustGate.isCurrent && runCatching { route?.isCurrent == true }.getOrDefault(false)
        private var withdrawn = false

        override fun resize(pixels: PixelSize): Boolean =
            !withdrawn && registry.resize(
                destination, surface, texture, pixels.widthPixels, pixels.heightPixels,
            ) != null

        @Synchronized
        override fun withdraw(textureDestroyed: Boolean): Boolean {
            if (!withdrawn) {
                val retirement = registry.withdraw(destination, surface, texture, textureDestroyed)
                val oldRoute = route
                if (oldRoute == null) {
                    retirement?.completeAfterLegacyOwnerRetired()
                    routeRevocation?.close()
                } else {
                    val detach: () -> Unit = {
                        oldRoute.retire()
                        routeRevocation?.close()
                    }
                    if (retirement == null) detach() else retirement.retainRetryDetacher(detach)
                }
                withdrawn = true
            } else if (textureDestroyed) {
                registry.onTextureDestroyed(texture)
            }
            return false
        }

        override fun close() { withdraw(false) }
    }
}
