package com.shilapi.xcertplay.airplay.video

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayPairTlsPskLease
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerDescriptor
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerProbe
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * An independently verified Pair-TLS-PSK identity and narrow route for one control connection.
 * No implementation is inferred from a phone URL or from a null TLS identity hint. The returned
 * identity array is transferred to the session owner and erased when it is consumed or retired.
 */
internal interface MatchPointVerifiedPairTlsRoute {
    val identity: MatchPointSessionIdentity
    val policy: MatchPointPeerUrlPolicy
    fun isCurrent(): Boolean
    fun takeVerifiedPskIdentity(): ByteArray?
    fun isAuthorized(uri: URI): Boolean
}

/** All authorities must be supplied independently for the same fresh MatchPoint generation. */
internal class MatchPointProductAuthorityBundle(
    val picIssuer: MatchPointPicAuthorizer? = null,
    val content: MatchPointItemContentAuthority? = null,
    val certifiedPark: MatchPointParkPlaybackAuthority? = null,
    val physicalOutput: MatchPointAndroidSurfaceRoute? = null,
    val networkSockets: MatchPointSessionNetworkSocketAuthority? = null,
    val verifiedPairTlsRoute: MatchPointVerifiedPairTlsRoute? = null,
)

internal fun interface MatchPointProductAuthorityProvider {
    fun forSession(
        session: AirPlaySession,
        identity: MatchPointSessionIdentity,
    ): MatchPointProductAuthorityBundle?
}

internal fun interface MatchPointProductPlayerPortFactory {
    fun create(
        identity: MatchPointSessionIdentity,
        bundle: MatchPointProductAuthorityBundle,
        transport: MatchPointPairTlsPskSource,
    ): MatchPointPlaybackPort
}

/**
 * Optional product construction seam. No default provider exists in the app. A host must supply
 * real, independently issued grants; incomplete grants reserve neither a PSK lease nor a player.
 * This does not configure, negotiate, or advertise the VideoPlayback feature.
 */
class MatchPointProductSessionFactory internal constructor(
    private val authorities: MatchPointProductAuthorityProvider,
    private val playerPortFactory: MatchPointProductPlayerPortFactory,
    private val featureEligible: (AirPlaySession) -> Boolean = {
        it.resolvedConfig.ultra?.isConfigured(AirPlayFeature.VIDEO_PLAYBACK) == true
    },
) {
    private val generations = AtomicLong()

    internal fun open(session: AirPlaySession): MatchPointProductSessionOwner {
        val identity = MatchPointSessionIdentity.create(generations.incrementAndGet())
        // VIDEO_PLAYBACK is not configurable today. In that state even an injected provider is
        // never called, so a routine /info probe cannot reserve a grant, PSK, Surface, or player.
        val eligible = try { featureEligible(session) } catch (_: Throwable) { false }
        val offered = if (eligible) try {
            authorities.forSession(session, identity)
        } catch (_: Throwable) { null } else null
        val prepared = prepare(session, identity, offered)
        return MatchPointProductSessionOwner(
            session, identity, prepared, playerPortFactory, featureEligible,
        )
    }

    private fun prepare(
        session: AirPlaySession,
        identity: MatchPointSessionIdentity,
        offered: MatchPointProductAuthorityBundle?,
    ): MatchPointPreparedAuthorities? {
        if (offered == null || session.isClosed || !session.isAuthenticatedControl) return null
        val pic = offered.picIssuer ?: return null
        val content = offered.content ?: return null
        val park = offered.certifiedPark ?: return null
        val output = offered.physicalOutput ?: return null
        val sockets = offered.networkSockets ?: return null
        val route = offered.verifiedPairTlsRoute ?: return null
        if (pic.identity !== identity || content.identity !== identity ||
            park.identity !== identity || output.identity !== identity ||
            sockets.identity !== identity || route.identity !== identity ||
            route.policy.transport != MatchPointHttpTransport.PAIR_TLS_PSK ||
            route.policy.socketAddress().address?.address
                ?.contentEquals(session.remoteAddress?.address) != true
        ) return null
        val current = try {
            park.isCurrent() && output.isCurrent() && sockets.isCurrent() && route.isCurrent()
        } catch (_: Throwable) { false }
        if (!current) return null
        val identityBytes = try { route.takeVerifiedPskIdentity() } catch (_: Throwable) { null }
            ?: return null
        if (identityBytes.size > 256) {
            identityBytes.fill(0)
            return null
        }
        val stillCurrent = try {
            park.isCurrent() && output.isCurrent() && sockets.isCurrent() && route.isCurrent()
        } catch (_: Throwable) { false }
        if (!stillCurrent || session.isClosed || !session.isAuthenticatedControl) {
            identityBytes.fill(0)
            return null
        }
        return MatchPointPreparedAuthorities(offered, identityBytes)
    }

    companion object {
        /** Production player constructor; callers must still provide the external authorities. */
        internal fun media3(
            context: Context,
            authorities: MatchPointProductAuthorityProvider,
        ): MatchPointProductSessionFactory = MatchPointProductSessionFactory(
            authorities,
            MatchPointProductPlayerPortFactory { identity, bundle, transport ->
                MatchPointAndroidPlaybackPort.createMedia3(
                    context, identity, requireNotNull(bundle.physicalOutput),
                    requireNotNull(bundle.content), requireNotNull(bundle.certifiedPark),
                    listOf(transport),
                )
            },
        )
    }
}

internal class MatchPointPreparedAuthorities(
    val bundle: MatchPointProductAuthorityBundle,
    val pskIdentity: ByteArray,
)

/** One exact AirPlay session owns at most one MatchPoint stream and its player/key retirement. */
internal class MatchPointProductSessionOwner(
    private val session: AirPlaySession,
    val identity: MatchPointSessionIdentity,
    private val prepared: MatchPointPreparedAuthorities?,
    private val playerPortFactory: MatchPointProductPlayerPortFactory,
    private val featureEligible: (AirPlaySession) -> Boolean,
) : Closeable {
    private val lock = Any()
    @Volatile private var closed = false
    private var creationStarted = false
    private var creationInFlight = false
    private var closeInFlight = false
    private var runtime: MatchPointRuntime? = null
    private var backend: MatchPointPlannerPlayerBackend? = null
    private var failedPort: MatchPointPlaybackPort? = null
    private var source: MatchPointPairTlsPskSource? = null
    @Volatile private var lease: AirPlayPairTlsPskLease? = null
    private var covered = false
    private var coverInProgress = false
    private var runtimeRetired = false
    private var backendRetired = false
    private var failedPortRetired = false
    private var sourceRetired = false
    private var leaseRetired = false

    val handlerFactory = RcsDataStreamHandlerFactory { clientType ->
        if (clientType != RcsClientTypes.CAR_PLAY_MATCH_POINT) null else
            RcsDataStreamHandlerDescriptor(
                clientType,
                "matchpoint.product.session",
                ::probe,
                ::createHandler,
            )
    }

    private fun current(): Boolean {
        val grants = prepared?.bundle ?: return false
        val activeLease = lease
        return !closed && !session.isClosed && session.isAuthenticatedControl &&
            runCatching { featureEligible(session) }.getOrDefault(false) &&
            session.pairVerify.verifiedGeneration != null &&
            (activeLease == null || (!activeLease.retired &&
                session.pairVerify.verifiedGeneration === activeLease.pairVerifyGeneration)) &&
            try {
                grants.certifiedPark?.isCurrent() == true &&
                    grants.physicalOutput?.isCurrent() == true &&
                    grants.networkSockets?.isCurrent() == true &&
                    grants.verifiedPairTlsRoute?.isCurrent() == true
            } catch (_: Throwable) { false }
    }

    private fun probe(): RcsDataStreamHandlerProbe {
        synchronized(lock) {
            if (closed || session.isClosed) return RcsDataStreamHandlerProbe.notReady("session_closed")
            if (prepared == null) return RcsDataStreamHandlerProbe.notReady("authority_missing")
            if (creationStarted) return RcsDataStreamHandlerProbe.notReady("stream_already_claimed")
        }
        // Grant liveness callbacks are external and must never delay STOPPING cover behind this
        // owner's monitor. The local terminal state is rechecked after those callbacks return.
        if (!runCatching { featureEligible(session) }.getOrDefault(false)) {
            return RcsDataStreamHandlerProbe.notReady("feature_disabled")
        }
        if (!current()) return RcsDataStreamHandlerProbe.notReady("authority_revoked")
        return synchronized(lock) {
            if (closed || session.isClosed) RcsDataStreamHandlerProbe.notReady("session_closed")
            else if (creationStarted) RcsDataStreamHandlerProbe.notReady("stream_already_claimed")
            else RcsDataStreamHandlerProbe.ready()
        }
    }

    private fun createHandler(stream: RcsDataStream): RcsDataStreamHandler {
        if (!probe().isReady) throw IOException("MatchPoint product authority is not ready")
        val preparedAuthorities = synchronized(lock) {
            if (closed || session.isClosed || creationStarted) {
                throw IOException("MatchPoint product authority changed before construction")
            }
            creationStarted = true
            creationInFlight = true
            requireNotNull(prepared)
        }
        val grants = preparedAuthorities.bundle
        val route = requireNotNull(grants.verifiedPairTlsRoute)
        var openedSource: MatchPointPairTlsPskSource? = null
        var openedPort: MatchPointPlaybackPort? = null
        return try {
            if (!current()) throw IOException("MatchPoint authority changed before PSK claim")
            val claimedLease = session.claimPairTlsPskLease()
                ?: throw IOException("MatchPoint Pair Verify generation is unavailable")
            lease = claimedLease
            if (!current()) throw IOException("MatchPoint authority changed while claiming PSK")
            openedSource = MatchPointPairTlsPskSource.openOwned(
                claimedLease, session, identity, route.policy,
                requireNotNull(grants.networkSockets), preparedAuthorities.pskIdentity,
                routeIsAuthorized = { requested, uri ->
                    requested === identity && route.isCurrent() && route.isAuthorized(uri)
                },
                sessionIsCurrent = ::current,
                responseDeadlineMillis = 60_000,
            ) ?: throw IOException("Exact Pair Verify PSK lease was revoked")
            synchronized(lock) { source = openedSource }
            preparedAuthorities.pskIdentity.fill(0)
            if (!current() || !openedSource.isCurrent()) {
                throw IOException("MatchPoint authority changed during transport construction")
            }
            // External Media3/Surface construction can block or synchronously call session.close.
            // STOPPING must be able to cover while this call is still in progress.
            openedPort = playerPortFactory.create(identity, grants, openedSource)
            synchronized(lock) { failedPort = openedPort }
            if (!current() || !openedSource.isCurrent()) {
                throw IOException("MatchPoint authority changed during player construction")
            }
            val openedBackend = MatchPointPlannerPlayerBackend(identity, openedPort)
            val openedRuntime = MatchPointRuntime(identity, stream, openedBackend,
                requireNotNull(grants.picIssuer))
            synchronized(lock) {
                if (closed || session.isClosed) {
                    throw IOException("MatchPoint session stopped during handler construction")
                }
                backend = openedBackend
                runtime = openedRuntime
                failedPort = null
                creationInFlight = false
            }
            openedRuntime
        } catch (failure: Throwable) {
            closed = true
            preparedAuthorities.pskIdentity.fill(0)
            try { coverImmediatelyOnStop() }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try {
                openedPort?.close()
                synchronized(lock) { failedPort = null; failedPortRetired = true }
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            try {
                openedSource?.close()
                synchronized(lock) { sourceRetired = true }
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            try {
                lease?.close()
                synchronized(lock) { leaseRetired = true }
            }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            synchronized(lock) { creationInFlight = false }
            throw failure
        }
    }

    /** Called synchronously at STOPPING, before the session waits for its Pair-TLS-PSK owner. */
    fun coverImmediatelyOnStop() {
        val output = synchronized(lock) {
            closed = true
            if (!creationStarted || covered) return
            if (coverInProgress) throw IOException("MatchPoint physical cover is in flight")
            coverInProgress = true
            requireNotNull(prepared?.bundle?.physicalOutput)
        }
        try {
            output.coverImmediately()
            synchronized(lock) { covered = true }
        } finally {
            synchronized(lock) {
                coverInProgress = false
            }
        }
    }

    override fun close() {
        coverImmediatelyOnStop()
        synchronized(lock) {
            if (creationInFlight) throw IOException("MatchPoint handler construction is in flight")
            if (closeInFlight) throw IOException("MatchPoint retirement is in flight")
            closeInFlight = true
        }
        var failure: Throwable? = null
        fun attempt(block: () -> Unit): Boolean = try { block(); true } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
            false
        }
        try {
            if (!runtimeRetired && attempt { runtime?.close() }) runtimeRetired = true
            if (!failedPortRetired && attempt { failedPort?.close() }) failedPortRetired = true
            if (!sourceRetired && attempt { source?.close() }) sourceRetired = true
            if (!backendRetired && attempt { backend?.close()?.await(1_500) }) backendRetired = true
            if (!leaseRetired && attempt { lease?.close() }) leaseRetired = true
            prepared?.pskIdentity?.fill(0)
        } finally {
            synchronized(lock) { closeInFlight = false }
        }
        failure?.let { throw it }
    }
}
