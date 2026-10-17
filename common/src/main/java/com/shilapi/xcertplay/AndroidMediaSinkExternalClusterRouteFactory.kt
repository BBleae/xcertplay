package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import java.io.Closeable
import java.util.IdentityHashMap

/**
 * Exact type-111 sink route for an independently trusted external Presentation producer.
 * Constructing this factory does not establish physical trust or publish a DDP panel. The
 * Presentation owner must retain it with the same sink through route and EGL retirement.
 */
internal class AndroidMediaSinkExternalClusterRouteFactory(
    private val sink: AndroidMediaSink,
    private val registry: HostPhysicalDisplaySurfaceRegistry,
) : ExternalClusterLegacyRouteFactory {
    private val lock = Any()
    private val routes = IdentityHashMap<Surface, SinkRoute>()

    override fun attach(
        destination: DisplayDestination,
        surface: Surface,
    ): ExternalClusterLegacyRoute? {
        require(destination.role == DisplayDestinationRole.CLUSTER &&
            destination.instanceId.value != "default"
        ) { "A physical external route needs a non-default CLUSTER destination" }
        val lease = sink.acquirePhysicalExternalLegacyRouteLease(111, surface) ?: return null
        val route = SinkRoute(destination, lease)
        val accepted = synchronized(lock) {
            if (routes.containsKey(surface)) false else {
                routes[surface] = route
                true
            }
        }
        if (!accepted) {
            val failure = IllegalStateException("The external Surface already has a route owner")
            runCatching { lease.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return route
    }

    fun belongsTo(mediaSink: AndroidMediaSink, surfaces: HostPhysicalDisplaySurfaceRegistry): Boolean =
        sink === mediaSink && registry === surfaces

    /**
     * Returns only the lease held by this exact Presentation publication. A Surface or type
     * pair alone is insufficient: the Controller must also carry the frozen Host version, and
     * that version must still be the unique live external physical target in the registry.
     */
    fun leaseForFrozenPhysicalTarget(
        inspection: HostPhysicalTargetInspection,
    ): AndroidMediaSink.PhysicalExternalLegacyRouteLease? {
        val version = inspection.version
        val topologyGeneration = inspection.externalTopologyGeneration ?: return null
        if (version.kind != HostDisplaySurfaceKind.PHYSICAL_EXTERNAL ||
            version.destination.role != DisplayDestinationRole.CLUSTER ||
            !registry.isUniqueLiveExternalPhysicalTarget(
                version, inspection.surface, topologyGeneration,
            )
        ) return null
        val route = synchronized(lock) { routes[inspection.surface] } ?: return null
        if (route.destination != version.destination ||
            route.lease.surface !== inspection.surface ||
            route.configuredPixels != version.sizePixels || !route.isCurrent
        ) return null
        return route.lease.takeIf { registry.isUniqueLiveExternalPhysicalTarget(
                version, inspection.surface, topologyGeneration,
            ) &&
            synchronized(lock) { routes[inspection.surface] === route } && it.isCurrent }
    }

    private inner class SinkRoute(
        val destination: DisplayDestination,
        val lease: AndroidMediaSink.PhysicalExternalLegacyRouteLease,
    ) : ExternalClusterLegacyRoute {
        override val isCurrent: Boolean get() = lease.isCurrent
        override val configuredPixels: PixelSize get() = PixelSize(
            lease.configuredWidthPixels, lease.configuredHeightPixels,
        )
        override val decoderAttached: Boolean get() = lease.decoderAttached

        override fun subscribeRevocation(onRevoked: () -> Unit): Closeable =
            lease.subscribeRevocation(onRevoked)

        override fun retire() {
            lease.close()
            synchronized(lock) {
                if (routes[lease.surface] === this) routes.remove(lease.surface)
            }
        }
    }
}
