package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionCapabilityOverlayFactory
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import com.shilapi.xcertplay.airplay.AirPlaySessionTransport
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the AirPlay TCP listener for both NCM/VPN and local-only Wi-Fi transports.
 *
 * The wired path also owns the Android VPN tunnel and NCM IPv6 bridge. VPN consent is requested
 * with [prepare] before binding.
 */
class CarPlayVpnService : VpnService() {
    inner class LocalBinder : Binder() {
        val service: CarPlayVpnService get() = this@CarPlayVpnService
    }

    sealed class AttachResult {
        data class Started(
            val token: AttachmentToken,
            /** Exact addresses whose AirPlay sockets and accept workers both started. */
            val boundAddresses: List<InetAddress> = emptyList(),
        ) : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(
            val message: String,
            val cleanupToken: AttachmentToken? = null,
        ) : AttachResult()
    }

    /** Controller-owned cancellation authority checked before replacing any service attachment. */
    class AttachmentAuthority internal constructor() {
        private val valid = AtomicBoolean(true)

        internal fun isValid(): Boolean = valid.get()
        internal fun cancel() {
            valid.set(false)
        }
    }

    /** Opaque authority to detach only the exact attachment returned by one successful attach. */
    class AttachmentToken internal constructor(internal val generation: Int)

    enum class AttachmentRetirementProof {
        RETIRED_CLEAN,
        RETIREMENT_UNPROVEN,
    }

    private data class AirPlayAttachment(
        val bindAddress: InetAddress,
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: MfiAuthenticator?,
        val listener: AirPlaySessionListener,
        val media: AirPlayMediaHandler,
        val sessionTransport: AirPlaySessionTransport,
        val authority: AttachmentAuthority,
        val runtimeBinding: AirPlaySessionRuntimeBinding?,
        val runtimeGeneration: AirPlaySessionRuntimeBinding.Generation?,
        val sessionOverlayFactory: AirPlaySessionCapabilityOverlayFactory?,
    )

    private val binder = LocalBinder()
    private val active = AtomicBoolean(false)
    private val acceptAdmissionLock = Any()
    private val sessionsLock = Any()
    private val sessions = mutableSetOf<AirPlaySession>()
    private val acceptedSockets = arrayListOf<Socket>()
    @Volatile private var attachment: AirPlayAttachment? = null
    private val airPlayListeners = ExactAirPlayListenerSet(acceptAdmissionLock) { generation, server ->
        acceptLoop(generation, server)
    }
    private var bridge: Ipv6NcmBridge? = null
    private var tun: ParcelFileDescriptor? = null
    private var attachGeneration = 0
    private val retirementLedger = AttachmentRetirementLedger()
    private data class TransportErrorTarget(
        val listener: AirPlaySessionListener,
        val message: String,
    )
    private val transportErrorTeardown = RetryableGenerationTeardown<TransportErrorTarget>(
        attemptRetirement = { generation -> retireTransportErrorGeneration(generation) },
        onRetired = { target ->
            try {
                target.listener.onTransportError(target.message)
            } finally {
                stopSelf()
            }
        },
    )

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    fun attach(
        ncm: NcmUsbBridge,
        linkLocal: String,
        hostMac: ByteArray,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        authority: AttachmentAuthority,
        runtimeBinding: AirPlaySessionRuntimeBinding? = null,
        runtimeGeneration: AirPlaySessionRuntimeBinding.Generation? = null,
        sessionOverlayFactory: AirPlaySessionCapabilityOverlayFactory? = null,
    ): AttachResult {
        requireAirPlaySessionRuntimeAdmissionPair(runtimeBinding, runtimeGeneration)
        require(sessionOverlayFactory == null || runtimeBinding != null) {
            "A session capability overlay requires a trusted runtime admission binding"
        }
        if (!authority.isValid()) return AttachResult.Failed("Attachment authority is cancelled")
        if (attachGeneration > 0 &&
            retirementLedger.proof(attachGeneration) != AttachmentRetirementProof.RETIRED_CLEAN
        ) {
            Log.i(TAG, "replacing stale NCM/VPN attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        retirementLedger.activate(generation)
        return try {
            val address = InetAddress.getByName(linkLocal)
            if (address !is Inet6Address || !address.isLinkLocalAddress) {
                throw IllegalArgumentException("linkLocal must be a link-local IPv6 literal")
            }
            require(hostMac.size == 6) { "hostMac must be 6 bytes" }

            val tunFd = Builder()
                .addAddress(linkLocal, LINK_PREFIX)
                .addRoute(LINK_LOCAL_ROUTE, LINK_PREFIX)
                .setSession(SESSION_NAME)
                .setMtu(TUN_MTU)
                .setBlocking(true)
                // An empty app list routes every UID through this VPN. Scope it before establish;
                // rejection must reach the existing attachment cleanup, never an unscoped retry.
                .addAllowedApplication(packageName)
                .establish()
                ?: throw IOException("VpnService.establish returned null")
            tun = tunFd

            val ipv6Bridge = Ipv6NcmBridge(ncm, tunFd, hostMac) { error ->
                onTransportError(generation, listener, error)
            }
            // Publish the exact bridge before its first fallible start operation. Construction
            // succeeded, so ownership of both NCM and TUN transfers exclusively to the bridge;
            // the service must not retain a second TUN close authority.
            bridge = ipv6Bridge
            tun = null
            ipv6Bridge.start()

            if (!authority.isValid()) throw IOException("Attachment authority was cancelled")
            startAirPlayServer(
                generation,
                AirPlayAttachment(
                    address,
                    config,
                    identity,
                    pairings,
                    mfi,
                    listener,
                    media,
                    AirPlaySessionTransport.WIRED_NCM,
                    authority,
                    runtimeBinding,
                    runtimeGeneration,
                    sessionOverlayFactory,
                ),
            )
            if (!authority.isValid()) {
                throw IOException("Attachment authority was cancelled")
            } else {
                AttachResult.Started(AttachmentToken(generation))
            }
        } catch (error: Exception) {
            failedAttachResult(generation, error)
        }
    }

    /**
     * Starts the AirPlay listener on the local-only Wi-Fi AP address without establishing a VPN or
     * NCM bridge.
     */
    @Synchronized
    fun attachWireless(
        bindAddress: InetAddress,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener,
        media: AirPlayMediaHandler,
        authority: AttachmentAuthority,
        runtimeBinding: AirPlaySessionRuntimeBinding? = null,
        runtimeGeneration: AirPlaySessionRuntimeBinding.Generation? = null,
        sessionOverlayFactory: AirPlaySessionCapabilityOverlayFactory? = null,
        secondaryBindAddress: InetAddress? = null,
    ): AttachResult {
        requireAirPlaySessionRuntimeAdmissionPair(runtimeBinding, runtimeGeneration)
        require(sessionOverlayFactory == null || runtimeBinding != null) {
            "A session capability overlay requires a trusted runtime admission binding"
        }
        if (!authority.isValid()) return AttachResult.Failed("Attachment authority is cancelled")
        if (attachGeneration > 0 &&
            retirementLedger.proof(attachGeneration) != AttachmentRetirementProof.RETIRED_CLEAN
        ) {
            Log.i(TAG, "replacing stale local-only Wi-Fi attachment")
            releaseLocked()
        }
        active.set(true)
        val generation = ++attachGeneration
        retirementLedger.activate(generation)
        return try {
            if (!authority.isValid()) throw IOException("Attachment authority was cancelled")
            val boundAddresses = startAirPlayServer(
                generation,
                AirPlayAttachment(
                    bindAddress,
                    config,
                    identity,
                    pairings,
                    mfi,
                    listener,
                    media,
                    AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK,
                    authority,
                    runtimeBinding,
                    runtimeGeneration,
                    sessionOverlayFactory,
                ),
                secondaryBindAddress,
            )
            if (!authority.isValid()) {
                throw IOException("Attachment authority was cancelled")
            } else {
                AttachResult.Started(AttachmentToken(generation), boundAddresses)
            }
        } catch (error: Exception) {
            failedAttachResult(generation, error)
        }
    }

    private fun failedAttachResult(generation: Int, failure: Exception): AttachResult.Failed {
        var cleanupToken: AttachmentToken? = null
        try {
            releaseLocked()
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            cleanupToken = AttachmentToken(generation)
        }
        return AttachResult.Failed(
            message = failure.message ?: failure.javaClass.simpleName,
            cleanupToken = cleanupToken,
        )
    }

    /** Releases the active AirPlay listener and whichever VPN/NCM transport resources are active. */
    @Synchronized
    fun detach() {
        releaseLocked()
    }

    /** Detaches only when [token] still names the current attachment. */
    @Synchronized
    fun detach(token: AttachmentToken): Boolean {
        return retire(token) == AttachmentRetirementProof.RETIRED_CLEAN
    }

    /** Proves clean retirement for current or previously service-retired attachment generations. */
    @Synchronized
    fun retire(token: AttachmentToken): AttachmentRetirementProof {
        if (token.generation == attachGeneration &&
            retirementLedger.proof(token.generation) != AttachmentRetirementProof.RETIRED_CLEAN
        ) {
            releaseLocked()
        }
        return retirementLedger.proof(token.generation)
    }

    @Synchronized
    fun isAttached(): Boolean = synchronized(acceptAdmissionLock) {
        val current = attachment
        active.get() && current != null && current.authority.isValid() &&
            current.bindAddress in airPlayListeners.boundAddresses(attachGeneration)
    }

    @Synchronized
    fun isAttached(token: AttachmentToken): Boolean = liveBoundAddresses(token) != null

    /**
     * Revalidate the exact attachment immediately before publishing wireless endpoints. A null
     * result means the token or authority is stale, or the primary accept worker/socket is dead.
     * The returned list is a detached immutable snapshot of only currently live exact listeners;
     * a failed or retired secondary is never included.
     */
    @Synchronized
    fun liveBoundAddresses(token: AttachmentToken): List<InetAddress>? =
        synchronized(acceptAdmissionLock) {
            val current = attachment
            liveAirPlayAddressesForToken(
                token = token,
                currentGeneration = attachGeneration,
                active = active.get(),
                authority = current?.authority,
                primaryAddress = current?.bindAddress,
                liveAddresses = airPlayListeners.boundAddresses(attachGeneration),
            )
        }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startAirPlayServer(
        generation: Int,
        replacement: AirPlayAttachment,
        secondaryBindAddress: InetAddress? = null,
    ): List<InetAddress> {
        require(!replacement.bindAddress.isAnyLocalAddress) {
            "AirPlay primary listener requires an exact address"
        }
        airPlayListeners.listen(
            generation = generation,
            address = replacement.bindAddress,
            port = replacement.config.port,
            optional = false,
            beforeAccept = { attachment = replacement },
        )
        Log.i(TAG, "airplay primary listener bound=${replacement.bindAddress.hostAddress}")
        if (secondaryBindAddress != null && secondaryBindAddress != replacement.bindAddress) {
            if (secondaryBindAddress.isAnyLocalAddress ||
                !airPlayListeners.listen(
                    generation = generation,
                    address = secondaryBindAddress,
                    port = replacement.config.port,
                    optional = true,
                )
            ) {
                Log.w(TAG, "airplay secondary exact listener unavailable; primary remains active")
            } else {
                Log.i(TAG, "airplay secondary listener bound=${secondaryBindAddress.hostAddress}")
            }
        }
        return airPlayListeners.boundAddresses(generation).also { bound ->
            if (replacement.bindAddress !in bound) {
                throw IOException("AirPlay primary accept worker did not remain listening")
            }
        }.let { Collections.unmodifiableList(ArrayList(it)) }
    }

    private fun acceptLoop(
        generation: Int,
        server: ServerSocket,
    ) {
        try {
            while (active.get()) {
                val socket: Socket = server.accept()
                // Publication and detachment share the same monitor. An accept racing retirement
                // is tracked even when stale; the post-join cleanup pass proves it was closed.
                val acceptedCurrent = synchronized(acceptAdmissionLock) {
                    synchronized(sessionsLock) { acceptedSockets.addIdentity(socket) }
                    val current = selectAttachmentForAcceptedSocket(
                        acceptGeneration = generation,
                        currentGeneration = attachGeneration,
                        acceptingServer = server,
                        currentServers = airPlayListeners.activeSockets(generation),
                        active = active.get(),
                        currentAttachment = attachment,
                    )
                    current != null && current.authority.isValid()
                }
                if (!acceptedCurrent) {
                    closeAcceptedSocket(socket)
                    return
                }
                var transferred = false
                try {
                    fun publishAndStartSession(
                        current: AirPlayAttachment,
                        runtimeSnapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot?,
                    ): Boolean {
                        val capacityAvailable = synchronized(sessionsLock) {
                            canAdmitAirPlayControlSession(
                                liveSessions = sessions.size,
                                unauthenticatedSessions = sessions.count {
                                    !it.isAuthenticatedControl
                                },
                                maxLiveSessions = MAX_LIVE_AIRPLAY_SESSIONS,
                                maxUnauthenticatedSessions = MAX_UNAUTHENTICATED_AIRPLAY_SESSIONS,
                            )
                        }
                        if (!capacityAvailable) return false
                        val overlayAdmission: ((AirPlaySession) -> Boolean)? =
                            current.sessionOverlayFactory?.let { factory ->
                                { session ->
                                    withAirPlaySessionRuntimeAdmission(
                                        binding = requireNotNull(current.runtimeBinding),
                                        generation = requireNotNull(current.runtimeGeneration),
                                    ) { exact ->
                                        synchronized(acceptAdmissionLock) {
                                            val stillCurrent = selectAttachmentForAcceptedSocket(
                                                acceptGeneration = generation,
                                                currentGeneration = attachGeneration,
                                                acceptingServer = server,
                                                currentServers = airPlayListeners.activeSockets(generation),
                                                active = active.get(),
                                                currentAttachment = attachment,
                                            )
                                            if (stillCurrent !== current ||
                                                !current.authority.isValid() ||
                                                session.runtimeSnapshot !== exact ||
                                                synchronized(sessionsLock) { session !in sessions }
                                            ) {
                                                false
                                            } else {
                                                session.admitConnectionOverlay(
                                                    requireNotNull(exact),
                                                    factory,
                                                )
                                            }
                                        }
                                    } == true
                                }
                            }
                        val session = AirPlaySession(
                            socket = socket,
                            config = runtimeSnapshot?.config ?: current.config,
                            identity = current.identity,
                            pairings = current.pairings,
                            mfi = current.mfi,
                            listener = object : AirPlaySessionListener by current.listener {
                                override fun onSessionEnded(session: AirPlaySession) {
                                    removeSession(session)
                                    current.listener.onSessionEnded(session)
                                }
                            },
                            media = current.media,
                            sessionTransport = current.sessionTransport,
                            runtimeSnapshot = runtimeSnapshot,
                            connectionOverlayAdmission = overlayAdmission,
                        )
                        synchronized(sessionsLock) {
                            sessions.add(session)
                            acceptedSockets.removeIdentity(socket)
                            transferred = true
                        }
                        session.start()
                        return true
                    }

                    Log.i(TAG, "airplay connection accepted from ${socket.remoteSocketAddress}")
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.setSoLinger(true, 0)
                    socket.soTimeout = PRE_AUTH_READ_TIMEOUT_MILLIS
                    val boundCurrent = synchronized(acceptAdmissionLock) {
                        val current = selectAttachmentForAcceptedSocket(
                            acceptGeneration = generation,
                            currentGeneration = attachGeneration,
                            acceptingServer = server,
                            currentServers = airPlayListeners.activeSockets(generation),
                            active = active.get(),
                            currentAttachment = attachment,
                        )
                        if (current == null || !current.authority.isValid()) {
                            closeAcceptedSocket(socket)
                            return
                        }
                        if (current.runtimeBinding == null) {
                            // Preserve the legacy single-lock admission path exactly.
                            publishAndStartSession(current, runtimeSnapshot = null)
                            null
                        } else {
                            current
                        }
                    }
                    if (boundCurrent != null) {
                        val admitted = withAirPlaySessionRuntimeAdmission(
                            binding = boundCurrent.runtimeBinding,
                            generation = boundCurrent.runtimeGeneration,
                        ) { runtimeSnapshot ->
                            synchronized(acceptAdmissionLock) {
                                val stillCurrent = selectAttachmentForAcceptedSocket(
                                    acceptGeneration = generation,
                                    currentGeneration = attachGeneration,
                                    acceptingServer = server,
                                    currentServers = airPlayListeners.activeSockets(generation),
                                    active = active.get(),
                                    currentAttachment = attachment,
                                )
                                if (stillCurrent !== boundCurrent ||
                                    !boundCurrent.authority.isValid()
                                ) {
                                    return@synchronized false
                                }
                                // Both binding revocation and detach are excluded until construction,
                                // publication, and start of this exact session are one admission step.
                                publishAndStartSession(boundCurrent, runtimeSnapshot)
                            }
                        }
                        if (admitted != true) closeAcceptedSocket(socket)
                    }
                } finally {
                    if (!transferred) closeAcceptedSocket(socket)
                }
            }
        } catch (error: Exception) {
            if (active.get()) {
                attachment?.listener?.let { onTransportError(generation, it, error) }
            }
        }
    }

    private fun removeSession(session: AirPlaySession?) {
        if (session == null) return
        synchronized(sessionsLock) { sessions.remove(session) }
    }

    private fun closeSessionsLocked() {
        var failure: Exception? = null
        val snapshot = synchronized(sessionsLock) { sessions.toList() }
        snapshot.forEach { session ->
            try {
                session.close()
                synchronized(sessionsLock) { sessions.remove(session) }
            } catch (error: Exception) {
                failure = combineRetirementFailure(failure, error)
            }
        }
        failure?.let { throw it }
    }

    private fun closeAcceptedSocket(socket: Socket) {
        synchronized(sessionsLock) {
            if (acceptedSockets.none { it === socket }) return
            socket.close()
            acceptedSockets.removeIdentity(socket)
        }
    }

    private fun onTransportError(
        generation: Int,
        listener: AirPlaySessionListener,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        runCatching { Log.e(TAG, "CarPlay transport stopped: $message", error) }
        transportErrorTeardown.request(
            generation,
            TransportErrorTarget(listener, message),
        )
    }

    private fun retireTransportErrorGeneration(
        generation: Int,
    ): RetryableGenerationTeardown.Outcome = synchronized(this) {
        if (generation != attachGeneration) {
            RetryableGenerationTeardown.Outcome.STALE
        } else {
            releaseLocked()
            if (retirementLedger.proof(generation) == AttachmentRetirementProof.RETIRED_CLEAN) {
                RetryableGenerationTeardown.Outcome.RETIRED
            } else {
                RetryableGenerationTeardown.Outcome.RETRY
            }
        }
    }

    /** Caller must hold this service's monitor. Closes only resources active for this attachment. */
    private fun releaseLocked() {
        synchronized(acceptAdmissionLock) { active.set(false) }
        retirementLedger.unproven(attachGeneration)
        var failure: Exception? = null
        try {
            airPlayListeners.closeSockets()
        } catch (error: Exception) {
            failure = combineRetirementFailure(failure, error)
        }
        try {
            closeAcceptedSockets()
        } catch (error: Exception) {
            failure = combineRetirementFailure(failure, error)
        }
        try {
            closeSessionsLocked()
        } catch (error: Exception) {
            failure = combineRetirementFailure(failure, error)
        }
        try {
            airPlayListeners.retireWorkers(ACCEPT_RETIREMENT_TIMEOUT_MILLIS)
        } catch (error: Exception) {
            failure = combineRetirementFailure(failure, error)
        }
        // A closed listener may deliver one already-queued accept after the first socket sweep.
        // Once every accept worker has ended, this final pass accounts for that exact race.
        try {
            closeAcceptedSockets()
        } catch (error: Exception) {
            failure = combineRetirementFailure(failure, error)
        }
        bridge?.let { owner ->
            try {
                owner.close()
                if (bridge === owner) bridge = null
            } catch (error: Exception) {
                failure = combineRetirementFailure(failure, error)
            }
        }
        tun?.let { owner ->
            try {
                owner.close()
                if (tun === owner) tun = null
            } catch (error: Exception) {
                failure = combineRetirementFailure(failure, error)
            }
        }
        val remainingOwners = synchronized(sessionsLock) {
            sessions.isNotEmpty() || acceptedSockets.isNotEmpty()
        } || airPlayListeners.hasOwners() || bridge != null || tun != null
        if (failure == null && remainingOwners) {
            failure = IOException("AirPlay attachment retirement remains unproven")
        }
        if (failure == null) {
            attachment = null
            retirementLedger.clean(attachGeneration)
        } else {
            throw failure
        }
    }

    private fun closeAcceptedSockets() {
        var failure: Exception? = null
        synchronized(sessionsLock) {
            acceptedSockets.toList().forEach { owner ->
                try {
                    owner.close()
                    acceptedSockets.removeIdentity(owner)
                } catch (error: Exception) {
                    failure = combineRetirementFailure(failure, error)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun combineRetirementFailure(first: Exception?, next: Exception): Exception {
        if (first == null) return next
        if (first !== next && first.suppressed.size < 8) first.addSuppressed(next)
        return first
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        private const val LINK_PREFIX = 64
        private const val LINK_LOCAL_ROUTE = "fe80::"
        private const val SESSION_NAME = "xcertplay CarPlay"
        private const val TUN_MTU = 1500
        private const val ACCEPT_RETIREMENT_TIMEOUT_MILLIS = 2_000L
        private const val PRE_AUTH_READ_TIMEOUT_MILLIS = 60_000
        private const val MAX_LIVE_AIRPLAY_SESSIONS = 16
        private const val MAX_UNAUTHENTICATED_AIRPLAY_SESSIONS = 4

        /** Returns the VPN consent intent, or null when consent is already granted. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)
    }
}

/** Exact-address AirPlay listeners owned by one service attachment generation. */
internal class ExactAirPlayListenerSet(
    private val admissionLock: Any,
    private val serverFactory: () -> ServerSocket = { ServerSocket() },
    private val threadFactory: (Runnable, String) -> Thread = { body, name ->
        Thread(body, name).apply { isDaemon = true }
    },
    private val acceptLoop: (Int, ServerSocket) -> Unit,
) {
    private class Owner(
        val generation: Int,
        val address: InetAddress,
        val socket: ServerSocket,
    ) {
        var worker: Thread? = null
        var accepting = false
        var closed = false
    }

    private val owners = arrayListOf<Owner>()

    /** An optional bind failure is isolated from an already active primary listener. */
    fun listen(
        generation: Int,
        address: InetAddress,
        port: Int,
        optional: Boolean,
        beforeAccept: () -> Unit = {},
    ): Boolean {
        require(!address.isAnyLocalAddress) { "AirPlay listener requires an exact address" }
        val socket = try {
            serverFactory()
        } catch (error: IOException) {
            if (optional) return false
            throw error
        }
        return synchronized(admissionLock) {
            // Register ownership before bind: even a partial bind or worker-start failure must be
            // closed by the ordinary retirement path, not abandoned as an untracked descriptor.
            val owner = Owner(generation, address, socket)
            owners.add(owner)
            try {
                socket.bind(InetSocketAddress(address, port))
            } catch (bindFailure: IOException) {
                if (!optional) throw bindFailure
                try {
                    socket.close()
                    owner.closed = true
                    owners.removeIdentity(owner)
                } catch (closeFailure: IOException) {
                    bindFailure.addSuppressed(closeFailure)
                    // A close failure leaves the owner tracked and requires fail-closed cleanup.
                    throw bindFailure
                }
                return@synchronized false
            }
            beforeAccept()
            val worker = threadFactory(
                Runnable { acceptLoop(generation, socket) },
                "airplay-accept-${address.hostAddress}",
            )
            owner.worker = worker
            owner.accepting = true
            worker.start()
            true
        }
    }

    fun activeSockets(generation: Int? = null): List<ServerSocket> = synchronized(admissionLock) {
        owners.filter {
            (generation == null || it.generation == generation) &&
                it.accepting && it.worker?.isAlive == true &&
                it.socket.isBound && !it.socket.isClosed
        }
            .map { it.socket }
    }

    fun boundAddresses(generation: Int? = null): List<InetAddress> = synchronized(admissionLock) {
        owners.filter {
            (generation == null || it.generation == generation) &&
                it.accepting && it.worker?.isAlive == true &&
                it.socket.isBound && !it.socket.isClosed
        }
            .map { it.address }
    }

    fun closeSockets() {
        var failure: IOException? = null
        synchronized(admissionLock) {
            owners.forEach { owner ->
                if (!owner.closed) {
                    try {
                        owner.socket.close()
                        owner.closed = true
                    } catch (error: IOException) {
                        failure = combineFailure(failure, error)
                    }
                }
            }
        }
        failure?.let { throw it }
    }

    /** Every worker must terminate and every socket must close before the group is retired. */
    fun retireWorkers(timeoutMillis: Long) {
        require(timeoutMillis > 0)
        var failure: IOException? = null
        val snapshot = synchronized(admissionLock) { owners.toList() }
        snapshot.forEach { owner ->
            val worker = owner.worker
            if (worker != null) {
                worker.interrupt()
                if (worker !== Thread.currentThread() &&
                    worker.state != Thread.State.NEW &&
                    worker.isAlive
                ) {
                    try {
                        worker.join(timeoutMillis)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failure = combineFailure(
                            failure,
                            IOException("Interrupted while retiring AirPlay accept worker", error),
                        )
                    }
                }
                if (worker.isAlive || worker === Thread.currentThread()) {
                    failure = combineFailure(
                        failure,
                        IOException("Timed out retiring AirPlay accept worker"),
                    )
                }
            }
            if (owner.closed && (worker == null || !worker.isAlive) &&
                worker !== Thread.currentThread()
            ) {
                synchronized(admissionLock) { owners.removeIdentity(owner) }
            }
        }
        failure?.let { throw it }
    }

    fun hasOwners(): Boolean = synchronized(admissionLock) { owners.isNotEmpty() }

    private fun combineFailure(first: IOException?, next: IOException): IOException {
        if (first == null) return next
        if (first !== next && first.suppressed.size < 8) first.addSuppressed(next)
        return first
    }
}

/** One retrying teardown claim per transport generation. */
internal class RetryableGenerationTeardown<T>(
    private val attemptRetirement: (Int) -> Outcome,
    private val onRetired: (T) -> Unit,
    private val initialRetryMillis: Long = 25L,
    private val maxRetryMillis: Long = 1_000L,
    private val threadFactory: (Runnable, String) -> Thread = { body, name ->
        Thread(body, name)
    },
) {
    enum class Outcome { RETIRED, RETRY, STALE }

    private class Claim<T>(
        val generation: Int,
        val target: T,
    ) {
        val terminal = AtomicBoolean(false)
        lateinit var worker: Thread
    }

    private val lock = Any()
    private val claims = LinkedHashMap<Int, Claim<T>>()

    init {
        require(initialRetryMillis > 0) { "initialRetryMillis must be positive" }
        require(maxRetryMillis >= initialRetryMillis) {
            "maxRetryMillis must be at least initialRetryMillis"
        }
    }

    /** Returns false when this exact generation already has a retained claim. */
    fun request(generation: Int, target: T): Boolean {
        val claim = synchronized(lock) {
            if (generation in claims) return false
            reserveClaimCapacity()
            Claim(generation, target).also { created ->
                val worker = threadFactory(
                    Runnable { runClaim(created) },
                    "airplay-teardown-$generation",
                ).apply { isDaemon = true }
                created.worker = worker
                claims[generation] = created
            }
        }
        try {
            claim.worker.start()
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (claims[generation] === claim &&
                    claim.worker.state == Thread.State.NEW &&
                    !claim.worker.isAlive
                ) {
                    claims.remove(generation)
                }
            }
            throw failure
        }
        return true
    }

    private fun runClaim(claim: Claim<T>) {
        var retryMillis = initialRetryMillis
        while (!claim.terminal.get()) {
            val outcome = try {
                attemptRetirement(claim.generation)
            } catch (_: Exception) {
                Outcome.RETRY
            }
            when (outcome) {
                Outcome.RETIRED -> {
                    if (claim.terminal.compareAndSet(false, true)) onRetired(claim.target)
                    return
                }
                Outcome.STALE -> {
                    claim.terminal.set(true)
                    return
                }
                Outcome.RETRY -> {
                    try {
                        Thread.sleep(retryMillis)
                    } catch (_: InterruptedException) {
                        // Interruption only accelerates the next exact-owner retry.
                    }
                    retryMillis = (retryMillis * 2).coerceAtMost(maxRetryMillis)
                }
            }
        }
    }

    private fun reserveClaimCapacity() {
        while (claims.size >= MAX_GENERATION_CLAIMS) {
            val terminal = claims.entries.firstOrNull { it.value.terminal.get() }
                ?: error("Too many unretired AirPlay teardown generations")
            claims.remove(terminal.key)
        }
    }

    private companion object {
        const val MAX_GENERATION_CLAIMS = 128
    }
}

internal class AttachmentRetirementLedger {
    private val states = LinkedHashMap<Int, CarPlayVpnService.AttachmentRetirementProof>()

    fun activate(generation: Int) {
        states[generation] = CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN
        trimCleanHistory()
    }

    fun unproven(generation: Int) {
        if (generation > 0) {
            states[generation] = CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN
        }
    }

    fun clean(generation: Int) {
        if (generation > 0) {
            states[generation] = CarPlayVpnService.AttachmentRetirementProof.RETIRED_CLEAN
            trimCleanHistory()
        }
    }

    fun proof(generation: Int): CarPlayVpnService.AttachmentRetirementProof =
        states[generation] ?: CarPlayVpnService.AttachmentRetirementProof.RETIREMENT_UNPROVEN

    private fun trimCleanHistory() {
        while (states.size > MAX_GENERATIONS) {
            val clean = states.entries.firstOrNull {
                it.value == CarPlayVpnService.AttachmentRetirementProof.RETIRED_CLEAN
            } ?: error("Too many unproven AirPlay attachment generations")
            states.remove(clean.key)
        }
    }

    private companion object {
        const val MAX_GENERATIONS = 128
    }
}

internal fun ownsAttachment(
    currentGeneration: Int,
    active: Boolean,
    token: CarPlayVpnService.AttachmentToken,
): Boolean = active && token.generation == currentGeneration

/** Pure final endpoint gate, fed only by a listener set observed under its admission lock. */
internal fun liveAirPlayAddressesForToken(
    token: CarPlayVpnService.AttachmentToken,
    currentGeneration: Int,
    active: Boolean,
    authority: CarPlayVpnService.AttachmentAuthority?,
    primaryAddress: InetAddress?,
    liveAddresses: List<InetAddress>,
): List<InetAddress>? {
    if (!ownsAttachment(currentGeneration, active, token) ||
        authority?.isValid() != true ||
        primaryAddress == null ||
        liveAddresses.none { it == primaryAddress }
    ) return null
    return Collections.unmodifiableList(ArrayList(liveAddresses))
}

/** Refuses new control workers while preserving already admitted/retiring sessions. */
internal fun canAdmitAirPlayControlSession(
    liveSessions: Int,
    unauthenticatedSessions: Int,
    maxLiveSessions: Int,
    maxUnauthenticatedSessions: Int,
): Boolean {
    require(maxLiveSessions > 0 && maxUnauthenticatedSessions in 1..maxLiveSessions)
    require(liveSessions >= 0 && unauthenticatedSessions in 0..liveSessions)
    return liveSessions < maxLiveSessions &&
        unauthenticatedSessions < maxUnauthenticatedSessions
}

internal fun requireAirPlaySessionRuntimeAdmissionPair(
    binding: AirPlaySessionRuntimeBinding?,
    generation: AirPlaySessionRuntimeBinding.Generation?,
) {
    require((binding == null) == (generation == null)) {
        "AirPlay session runtime binding and generation must be supplied together"
    }
}

/**
 * Runs [admit] as an ordinary legacy admission when no binding is supplied, or atomically under
 * the exact resolved binding generation. A null result means the generation is pending, stale, or
 * revoked and no admission action ran.
 */
internal fun <T : Any> withAirPlaySessionRuntimeAdmission(
    binding: AirPlaySessionRuntimeBinding?,
    generation: AirPlaySessionRuntimeBinding.Generation?,
    admit: (AirPlaySessionRuntimeBinding.ResolvedSnapshot?) -> T,
): T? {
    requireAirPlaySessionRuntimeAdmissionPair(binding, generation)
    return if (binding == null) {
        admit(null)
    } else {
        binding.withResolvedAdmission(requireNotNull(generation)) { snapshot ->
            admit(snapshot)
        }
    }
}

private fun <T : Any> MutableList<T>.addIdentity(value: T) {
    if (none { it === value }) add(value)
}

private fun <T : Any> MutableList<T>.removeIdentity(value: T) {
    val index = indexOfFirst { it === value }
    if (index >= 0) removeAt(index)
}

/**
 * Selects the attachment owned by the exact listener epoch that accepted a socket.
 *
 * Kept as a pure function so the stale-accept race can be tested without constructing an Android
 * [VpnService]. Both the monotonically increasing generation and the server identity are required:
 * either one alone leaves a replacement window in which an old socket could inherit new state.
 */
internal fun <T> selectAttachmentForAcceptedSocket(
    acceptGeneration: Int,
    currentGeneration: Int,
    acceptingServer: ServerSocket,
    currentServer: ServerSocket?,
    active: Boolean,
    currentAttachment: T?,
): T? = selectAttachmentForAcceptedSocket(
    acceptGeneration = acceptGeneration,
    currentGeneration = currentGeneration,
    acceptingServer = acceptingServer,
    currentServers = listOfNotNull(currentServer),
    active = active,
    currentAttachment = currentAttachment,
)

/** Membership is socket identity, never address or port equality. */
internal fun <T> selectAttachmentForAcceptedSocket(
    acceptGeneration: Int,
    currentGeneration: Int,
    acceptingServer: ServerSocket,
    currentServers: Collection<ServerSocket>,
    active: Boolean,
    currentAttachment: T?,
): T? = currentAttachment.takeIf {
    active &&
        acceptGeneration == currentGeneration &&
        currentServers.any { it === acceptingServer }
}
