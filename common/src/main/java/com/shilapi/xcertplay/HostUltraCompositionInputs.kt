package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AndroidVideoFrameReleaseReceipt
import android.view.Surface
import java.io.Closeable
import java.lang.ref.WeakReference

/**
 * Per-sink, one-child diagnostic callback route. This object cannot select a renderer by
 * process-global state or by an equivalent stream identifier. The registered child still has to
 * prove its exact AA05 physical batch before it may forward a release receipt to EGL.
 */
internal class ExactOwnerDiagnosticRelay<Owner : Any, Event : Any> : Closeable {
    private class Subscriber<Event : Any>(
        val identity: Any,
        val consumer: (Event) -> Unit,
    )

    private val lock = Any()
    private var owner: WeakReference<Owner>? = null
    private var subscriber: Subscriber<Event>? = null
    private var closed = false

    fun bindOwner(candidate: Owner) = synchronized(lock) {
        check(!closed && owner == null) { "Diagnostic relay already has an owner" }
        owner = WeakReference(candidate)
    }

    fun belongsTo(candidate: Owner): Boolean = synchronized(lock) {
        !closed && owner?.get() === candidate
    }

    fun attach(candidate: Owner, session: Any, consumer: (Event) -> Unit): Closeable {
        val bound = synchronized(lock) {
            check(!closed && owner?.get() === candidate && subscriber == null) {
                "Diagnostic relay does not belong to this sink or already has a child"
            }
            Subscriber(session, consumer).also { subscriber = it }
        }
        return Closeable {
            synchronized(lock) {
                if (subscriber === bound) subscriber = null
            }
        }
    }

    fun emit(event: Event) {
        // Never hold this lock while entering the renderer/authority. Its own exact-batch guard
        // rejects an in-flight callback if the child or the physical generation was revoked.
        val target = synchronized(lock) {
            if (closed || owner?.get() == null) null else subscriber?.consumer
        }
        target?.invoke(event)
    }

    override fun close() = synchronized(lock) {
        closed = true
        subscriber = null
        owner = null
    }
}

internal typealias HostVideoFrameReleaseRelay =
    ExactOwnerDiagnosticRelay<AndroidMediaSink, AndroidVideoFrameReleaseReceipt>

/** Issued only from the Host's exact current sink and its private release callback relay. */
internal class HostVideoFrameReleaseChannel private constructor(
    sink: AndroidMediaSink,
    private val relay: HostVideoFrameReleaseRelay,
) {
    private val sinkReference = WeakReference(sink)

    fun attach(session: Any, consumer: (AndroidVideoFrameReleaseReceipt) -> Unit): Closeable =
        relay.attach(checkNotNull(sinkReference.get()) {
            "The exact Host media sink has retired"
        }, session, consumer)

    companion object {
        fun forExactSink(
            sink: AndroidMediaSink,
            relay: HostVideoFrameReleaseRelay,
        ): HostVideoFrameReleaseChannel? =
            if (relay.belongsTo(sink)) HostVideoFrameReleaseChannel(sink, relay) else null
    }
}

/**
 * Exact, process-owned Host inputs for one Controller construction.
 *
 * A Debug profile compiler may use these to prove output ownership. A view, Activity, setting,
 * standalone Surface, or reconstructed sink is not a substitute for these captured objects.
 */
internal class HostUltraCompositionInputs(
    mediaSink: AndroidMediaSink,
    val physicalSurfaces: HostPhysicalDisplaySurfaceRegistry,
    val airPlayConfig: AirPlayConfig,
    frozenExternalClusterTarget: HostPhysicalTargetInspection? = null,
    externalClusterRoutes: AndroidMediaSinkExternalClusterRouteFactory? = null,
    videoFrameReleaseRelay: HostVideoFrameReleaseRelay? = null,
) {
    init {
        require(externalClusterRoutes == null ||
            externalClusterRoutes.belongsTo(mediaSink, physicalSurfaces)
        ) { "External route factory belongs to another Host sink or registry" }
    }
    // The process root may outlive the Activity/background Controller. A candidate acquires a
    // strong exact sink only while preparing its own revocable runtime; this holder is not a new
    // process-wide owner that keeps a retired decoder alive indefinitely.
    private val sinkReference = WeakReference(mediaSink)
    private val videoFrameReleaseChannel = videoFrameReleaseRelay?.let { relay ->
        requireNotNull(HostVideoFrameReleaseChannel.forExactSink(mediaSink, relay)) {
            "Video receipt relay belongs to another Host sink"
        }
    }
    private val externalRouteReference = externalClusterRoutes?.let(::WeakReference)
    private val externalClusterVersion = frozenExternalClusterTarget?.version
    private val externalClusterSurface = frozenExternalClusterTarget?.surface?.let(::WeakReference)
    private val externalTopologyGeneration = frozenExternalClusterTarget?.externalTopologyGeneration

    fun currentMediaSink(): AndroidMediaSink? = sinkReference.get()

    fun currentVideoFrameReleaseChannel(candidate: AndroidMediaSink):
        HostVideoFrameReleaseChannel? =
        videoFrameReleaseChannel.takeIf { sinkReference.get() === candidate }

    /** The signed CLUSTER instance must be the one frozen into this Controller's DDP proof. */
    fun matchesFrozenExternalCluster(candidate: HostPhysicalTargetInspection): Boolean =
        externalClusterVersion != null && candidate.version == externalClusterVersion &&
            externalTopologyGeneration != null &&
            candidate.externalTopologyGeneration == externalTopologyGeneration &&
            candidate.surface === externalClusterSurface?.get() &&
            physicalSurfaces.isSameLivePhysicalTarget(candidate.version, candidate.surface)

    /** No type/Surface lookup: both the frozen DDP target and its attached route must match. */
    fun frozenExternalClusterRoute(
        version: HostDisplaySurfaceVersion,
        surface: Surface,
    ): AndroidMediaSink.PhysicalExternalLegacyRouteLease? {
        val candidate = physicalSurfaces.inspectPhysicalTarget(version.destination) ?: return null
        if (candidate.version != version || candidate.surface !== surface ||
            !matchesFrozenExternalCluster(candidate)
        ) return null
        return externalRouteReference?.get()?.leaseForFrozenPhysicalTarget(candidate)
    }
}
