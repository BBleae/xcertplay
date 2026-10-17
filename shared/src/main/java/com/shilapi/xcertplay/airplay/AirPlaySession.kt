package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.airplay.mainbuffered.AirPlayMainBufferedM4Lease
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedM4FlushedSessionLease
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedVerifiedSecretBorrow
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27RequestKey
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27RequestTicket
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupState
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupTransaction
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnostic
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.IdentityHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncProvisionedSession

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    fun onSessionActive(session: AirPlaySession) {}
    fun onSessionEnded(session: AirPlaySession) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: AirPlaySession) {}
    fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {}
    fun onDebugLog(message: String) {}
}

/** Stable identity for one AirPlay stream; type 130 is never keyed by type alone. */
data class AirPlayStreamIdentity(
    val type: Int,
    val streamConnectionId: String?,
    val clientTypeUuid: String?,
) {
    val hasDiscriminator: Boolean
        get() = streamConnectionId != null || clientTypeUuid != null

    fun matches(selector: AirPlayStreamIdentity): Boolean =
        type == selector.type &&
            (selector.streamConnectionId == null || streamConnectionId == selector.streamConnectionId) &&
            (selector.clientTypeUuid == null || clientTypeUuid == selector.clientTypeUuid)

    companion object {
        fun from(type: Int, stream: Map<String, Any?>): AirPlayStreamIdentity =
            AirPlayStreamIdentity(
                type = type,
                streamConnectionId = unsignedPlistDecimal(stream["streamConnectionID"]),
                clientTypeUuid = (stream["clientTypeUUID"] as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.uppercase(Locale.US),
            )
    }
}

/** A listener prepared before SETUP is written and committed only after that response is flushed. */
class AirPlayPreparedMediaStream internal constructor(
    val identity: AirPlayStreamIdentity,
    val response: Map<String, Any?>,
    private val commitHook: () -> Boolean = { true },
    private val rollbackHook: () -> Unit = {},
) {
    private enum class State { PREPARED, COMMITTED, ROLLED_BACK }
    private var state = State.PREPARED

    @Synchronized
    fun commit(): Boolean = when (state) {
        State.COMMITTED -> true
        State.ROLLED_BACK -> false
        State.PREPARED -> {
            if (runCatching(commitHook).getOrDefault(false)) {
                state = State.COMMITTED
                true
            } else {
                state = State.ROLLED_BACK
                runCatching(rollbackHook)
                false
            }
        }
    }

    @Synchronized
    fun rollback() {
        if (state != State.PREPARED) return
        state = State.ROLLED_BACK
        runCatching(rollbackHook)
    }
}

/** Exact, still-uncommitted type-103 transaction returned by a future product media owner. */
class AirPlayPreparedMainBufferedStream internal constructor(
    internal val transaction: MainBufferedV27SetupTransaction,
)

/** Stream transport seam; media decode/render is supplied by a later layer. */
interface AirPlayMediaHandler {
    fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun prepareMainBuffered(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMainBufferedStream? = null
    fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: AirPlaySession): Map<String, Any?>? = null
    fun onTeardown(session: AirPlaySession, type: Int) {}
    /** Exact engine-side receipt after the old type-103 transaction has closed. */
    fun isMainBufferedOwnerQuiescent(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        prepared: AirPlayPreparedMainBufferedStream,
    ): Boolean = false
    /** Keep a selected MAIN stream active until its exact physical UI has been covered. */
    fun beforeScreenTeardown(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
    ): Boolean = true
    /** Synchronously cover any session-owned physical video before transport retirement waits. */
    fun onSessionStopping(session: AirPlaySession) {}
    fun onSessionClosed(session: AirPlaySession) {}
    fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: AirPlaySession) {}

    fun isRcsReady(session: AirPlaySession, clientTypes: Collection<RcsClientType>): Boolean = false
    fun sendUiSyncCommand(
        session: AirPlaySession,
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): Boolean = false
    fun sendUiSyncCommand(
        session: AirPlaySession,
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand,
        noAck: Boolean = false,
    ): Boolean = sendUiSyncCommand(session, displayId, command.type, command.wireData(), noAck)
    fun drainUiSyncDiagnostics(session: AirPlaySession): List<CarPlayUiSyncRoutingDiagnostic> =
        emptyList()

    fun prepareScreen(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? = onScreen(session, identity.type, stream)?.let { port ->
        AirPlayPreparedMediaStream(
            identity,
            linkedMapOf("type" to identity.type, "dataPort" to port),
            rollbackHook = { onTeardown(session, identity) },
        )
    }

    fun prepareAudio(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? = onAudio(session, identity.type, stream)?.let { response ->
        AirPlayPreparedMediaStream(
            identity,
            response,
            rollbackHook = { onTeardown(session, identity) },
        )
    }

    fun prepareDataStream(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        clientType: RcsClientType,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? = onDataStream(session, stream)?.let { response ->
        AirPlayPreparedMediaStream(
            identity,
            response,
            rollbackHook = { onTeardown(session, identity) },
        )
    }

    fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) =
        onTeardown(session, identity.type)
}

/**
 * One CarPlay AirPlay control connection.
 *
 * It owns the RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID
 * input, stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
internal enum class AirPlaySessionTransport {
    WIRED_NCM,
    WIRELESS_LOCAL_NETWORK,
}

class AirPlaySession internal constructor(
    private val socket: Socket,
    config: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    private val mfi: MfiAuthenticator?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    private val sessionTransport: AirPlaySessionTransport = AirPlaySessionTransport.WIRED_NCM,
    private val retirementJoinTimeoutMillis: Long = DEFAULT_RETIREMENT_JOIN_TIMEOUT_MILLIS,
    private val keepAliveSocketFactory: () -> DatagramSocket = { DatagramSocket(null) },
    private val eventServerFactory: () -> ServerSocket = { ServerSocket() },
    internal val runtimeSnapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot? = null,
    private val connectionOverlayAdmission: ((AirPlaySession) -> Boolean)? = null,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val infoOnlyReservationLifetimeMillis: Long = DEFAULT_INFO_ONLY_RESERVATION_LIFETIME_MILLIS,
    private val infoOnlyReservationPollMillis: Int = INFO_ONLY_RESERVATION_POLL_MILLIS,
) : Closeable {
    private class ConnectionCapabilities(
        val config: AirPlayConfig,
        val overlay: AirPlaySessionCapabilityOverlay?,
    )

    private val baseConfig: AirPlayConfig = runtimeSnapshot?.config ?: config
    @Volatile private var capabilities = ConnectionCapabilities(baseConfig, null)
    /** The configuration and additional RCS owner are published as one per-session holder. */
    internal val resolvedConfig: AirPlayConfig get() = capabilities.config
    internal val connectionOverlay: AirPlaySessionCapabilityOverlay? get() = capabilities.overlay
    private val config: AirPlayConfig get() = capabilities.config
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    @Volatile internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    internal val activeStreams = linkedSetOf<AirPlayStreamIdentity>()

    init {
        require(retirementJoinTimeoutMillis > 0) {
            "retirementJoinTimeoutMillis must be positive"
        }
        require(connectionOverlayAdmission == null || runtimeSnapshot != null) {
            "A session capability overlay requires a trusted runtime snapshot"
        }
        require(infoOnlyReservationLifetimeMillis in 1..MAX_INFO_ONLY_RESERVATION_LIFETIME_MILLIS) {
            "Info-only reservation lifetime is out of bounds"
        }
        require(infoOnlyReservationPollMillis in 1..INFO_ONLY_RESERVATION_POLL_MILLIS) {
            "Info-only reservation poll period is out of bounds"
        }
    }

    /** True only after every resource and worker owned by this session has retired. */
    private val closed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val retirementRequested = AtomicBoolean(false)
    private val sessionStopCoverInFlight = AtomicBoolean(false)
    private val physicalStopOwnersLock = Any()
    private val physicalStopOwners = IdentityHashMap<UiSyncProvisionedSession, Unit>()
    private val physicalPanelSubscriptionLock = Any()
    private var physicalPanelSubscribedPanels: AirPlayPhysicalDisplayPanels? = null
    private var physicalPanelSubscribedProof: AirPlayPhysicalDisplayPanelsLiveness? = null
    private var physicalPanelSubscription: Closeable? = null
    private val physicalPanelRetirementScheduled = AtomicBoolean(false)
    private val authenticatedControl = AtomicBoolean(false)
    private val pairTlsPskLeaseLock = Any()
    private val pairTlsControlEpoch = Any()
    private var pairTlsPskLease: AirPlayPairTlsPskLease? = null
    private var pairTlsBorrowThread: Thread? = null
    private var pairTlsBorrowCompletion: CountDownLatch? = null
    private var pairTlsBorrowLease: AirPlayPairTlsPskLease? = null
    private var pairTlsTransportOwner: PendingPairTlsPskTransport? = null
    /** The Pair Verify generation whose M4 response actually enabled this control cipher. */
    @Volatile private var authenticatedPairVerifyGeneration: Any? = null
    private val mainBufferedM4LeaseLock = Any()
    /** Independent positive numeric epoch; the PairVerify token is always checked by reference. */
    private var mainBufferedM4NumericGeneration = 0L
    private var mainBufferedM4PairVerifyGeneration: Any? = null
    /** A duplicate M4 publisher never re-arms an existing epoch. */
    private var lastPublishedMainBufferedM4PairVerifyGeneration: Any? = null
    private var mainBufferedM4ReplayHmacKey: ByteArray? = null
    private var mainBufferedM4LeaseIssued = false
    private var mainBufferedM4IssuedOrdinal = 0L
    private var currentMainBufferedM4Lease: AirPlayMainBufferedM4Lease? = null
    private class MainBufferedM4Reservation(
        val token: Any,
        val generation: Long,
        val lease: AirPlayMainBufferedM4Lease,
        val connectionIdBits: Long?,
        val mctlSeedBits: Long?,
        val shkHmac: String?,
        val requestMarker: Any?,
    ) {
        var transaction: MainBufferedV27SetupTransaction? = null
        var prepared: AirPlayPreparedMainBufferedStream? = null
        var responseWriteStarted = false
        var responseFlushedAndCommitted = false
        var tombstoned = false
    }
    private var currentMainBufferedM4Reservation: MainBufferedM4Reservation? = null
    /** Separate replay sets: changing two fields never permits reuse of the third. */
    private val usedMainBufferedConnectionIds = HashSet<Long>()
    private val usedMainBufferedMctlSeeds = HashSet<Long>()
    private val usedMainBufferedShkFingerprints = HashSet<String>()
    private val retainedMainBufferedM4Leases =
        IdentityHashMap<AirPlayMainBufferedM4Lease, Unit>()
    private class PendingPairTlsPskTransport(
        val lease: AirPlayPairTlsPskLease,
        private val resource: Closeable,
    ) {
        private class CloseAttempt {
            val finished = CountDownLatch(1)
            @Volatile var failure: Throwable? = null
            lateinit var worker: Thread
        }

        private val lock = Any()
        private var retired = false
        private var closeAttempt: CloseAttempt? = null

        fun retire(timeoutMillis: Long) {
            val (attempt, startWorker) = synchronized(lock) {
                if (retired) return
                val existing = closeAttempt
                if (existing != null) {
                    existing to false
                } else {
                    val newAttempt = CloseAttempt()
                    newAttempt.worker = Thread(
                        {
                            try {
                                resource.close()
                            } catch (failure: Throwable) {
                                newAttempt.failure = failure
                            } finally {
                                newAttempt.finished.countDown()
                            }
                        },
                        "airplay-pair-tls-retirement",
                    ).apply { isDaemon = true }
                    closeAttempt = newAttempt
                    newAttempt to true
                }
            }
            if (startWorker) {
                try {
                    attempt.worker.start()
                } catch (failure: Throwable) {
                    attempt.failure = failure
                    attempt.finished.countDown()
                }
            }
            check(attempt.worker !== Thread.currentThread()) {
                "Pair-TLS-PSK transport cannot retire itself reentrantly"
            }
            val completed = try {
                attempt.finished.await(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
            if (!completed) throw IOException("Timed out retiring Pair-TLS-PSK transport")
            synchronized(lock) {
                if (closeAttempt === attempt) {
                    if (attempt.failure == null) retired = true
                    closeAttempt = null
                }
            }
            attempt.failure?.let { failure ->
                if (failure is IOException) throw failure
                throw IOException("Pair-TLS-PSK transport close failed", failure)
            }
        }

        fun isRetired(): Boolean = synchronized(lock) { retired }

        fun isRetiringOn(thread: Thread): Boolean = synchronized(lock) {
            closeAttempt?.let { it.worker === thread && it.finished.count > 0 } == true
        }
    }
    internal val isClosed: Boolean
        get() = stopping.get() || closed.get()

    /** Register the exact candidate before physical bind; STOPPING never waits for construction. */
    internal fun registerPhysicalStopOwner(owner: UiSyncProvisionedSession): Boolean =
        synchronized(physicalStopOwnersLock) {
            physicalStopOwners.keys.removeAll { it.isRetired }
            if (stopping.get() || closed.get()) return@synchronized false
            if (physicalStopOwners.size >= 32) return@synchronized false
            physicalStopOwners[owner] = Unit
            true
        }

    internal fun unregisterPhysicalStopOwner(owner: UiSyncProvisionedSession) {
        synchronized(physicalStopOwnersLock) { physicalStopOwners.remove(owner) }
    }

    internal fun coverRegisteredPhysicalStopOwners(): Boolean {
        val owners = synchronized(physicalStopOwnersLock) {
            physicalStopOwners.keys.removeAll { it.isRetired }
            physicalStopOwners.keys.toList()
        }
        var covered = true
        owners.forEach { owner ->
            if (!runCatching { owner.coverForSessionStop() }.getOrDefault(false)) covered = false
        }
        return covered
    }
    internal val isAuthenticatedControl: Boolean
        get() = authenticatedControl.get()
    private val notified = AtomicBoolean(false)
    private val retirementLock = Any()
    private val retirementAttemptLock = Any()
    private var controlSocketRetired = false
    private var controlThread: Thread? = null
    private var mediaRetired = false
    private var connectionOverlayRetired = false
    private var pendingOverlayOwner: AirPlaySessionCapabilityOverlay? = null
    private var ntpRetired = false
    private var deferredRetirementThread: Thread? = null
    private var eventServer: ServerSocket? = null
    private var eventSocket: Socket? = null
    private var eventCipher: ControlCipher? = null
    private var eventOutboundNonceUsed: AtomicBoolean? = null
    private var eventCseq = 0
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    private val preAuthStartedNanos = monotonicNanos()
    private val ntp = NtpClock()
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private class KeepAliveOwner(val socket: DatagramSocket) {
        var thread: Thread? = null
        var socketRetired: Boolean = false
    }
    private val ownedKeepAlive = arrayListOf<KeepAliveOwner>()
    private val ownedEventServers = arrayListOf<ServerSocket>()
    private val ownedEventSockets = arrayListOf<Socket>()
    /** Includes candidates whose eventCipher slot was cleared before session retirement. */
    private val ownedEventCiphers = arrayListOf<ControlCipher>()
    private val eventWriteLock = Any()
    private val commandDispatchLock = Any()
    private val mainBufferedTransactionsLock = Any()
    /** Includes prepared transactions so a failed rollback remains owned until session close. */
    private val mainBufferedTransactions =
        LinkedHashMap<AirPlayStreamIdentity, MainBufferedV27SetupTransaction>()
    private val eventThreads = CopyOnWriteArrayList<Thread>()
    private val displayControl = AirPlayDisplayControlFactory.create(baseConfig, ::sendCommand) {
        debugLog(it)
    }
    @Volatile
    private var negotiatedFeatures: Set<AirPlayFeature> = emptySet()
    private var featureSetupSeen = false
    private var overlayAdmissionAttempted = false
    private var overlayAdmissionAccepted = false
    private var authenticatedSetupSeen = false
    private var infoOnlyReservationDeadlineNanos: Long? = null

    /** A pre-stream file-transfer reservation cannot remain idle indefinitely. */
    private fun armInfoOnlyReservationDeadline() {
        if (authenticatedSetupSeen || infoOnlyReservationDeadlineNanos != null) return
        // Poll without extending the absolute deadline when a peer sends repeated /info requests.
        try {
            socket.soTimeout = infoOnlyReservationPollMillis
        } catch (failure: Exception) {
            retirementRequested.set(true)
            throw failure
        }
        infoOnlyReservationDeadlineNanos = monotonicNanos() +
            infoOnlyReservationLifetimeMillis * NANOS_PER_MILLISECOND
    }

    private fun noteAuthenticatedSetup() {
        if (infoOnlyReservationDeadlineNanos != null) {
            try {
                socket.soTimeout = 0
            } catch (failure: Exception) {
                retirementRequested.set(true)
                throw failure
            }
            infoOnlyReservationDeadlineNanos = null
        }
        authenticatedSetupSeen = true
    }

    /** Called only under the exact runtime admission gate, before this session's scope freezes. */
    internal fun admitConnectionOverlay(
        snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot,
        factory: AirPlaySessionCapabilityOverlayFactory,
    ): Boolean = synchronized(commandDispatchLock) {
        if (runtimeSnapshot !== snapshot || isTerminating() || capabilities.overlay != null) {
            return@synchronized false
        }
        val overlay = factory.open(snapshot)
        // A reused overlay belongs to its first session; never close it on this rejected admission.
        if (!overlay.claimForSession()) return@synchronized false
        pendingOverlayOwner = overlay
        if (isTerminating()) {
            overlay.close()
            pendingOverlayOwner = null
            return@synchronized false
        }
        // This preserves pre-auth discovery URLs and response sequencing on the same control
        // object. Only Ultra/panel declarations may change; ordinary identity and geometry stay.
        displayControl.reconfigureUltra(overlay.config)
        capabilities = ConnectionCapabilities(overlay.config, overlay)
        pendingOverlayOwner = null
        if (!armPhysicalPanelRevocation(overlay.config)) {
            requestPhysicalPanelRetirement(
                overlay.config.displayPanels,
                overlay.config.displayPanelsLiveness,
            )
            return@synchronized false
        }
        true
    }

    /** The Host/UI callback only dispatches; this worker linearizes against overlay replacement. */
    private fun requestPhysicalPanelRetirement(
        expectedPanels: AirPlayPhysicalDisplayPanels?,
        expectedProof: AirPlayPhysicalDisplayPanelsLiveness?,
    ) {
        Thread(
            {
                val retire = synchronized(commandDispatchLock) {
                    val current = config
                    if (current.displayPanels !== expectedPanels ||
                        current.displayPanelsLiveness !== expectedProof ||
                        !physicalPanelRetirementScheduled.compareAndSet(false, true)
                    ) {
                        false
                    } else {
                        stopping.set(true)
                        retirementRequested.set(true)
                        true
                    }
                }
                if (!retire) return@Thread
                try {
                    close()
                } catch (_: DeferredRetirementException) {
                    // The retained coordinator completes this exact session's retirement.
                } catch (failure: Throwable) {
                    runCatching { Log.e(TAG, "airplay physical panel retirement deferred", failure) }
                    // STOPPING is sticky; close() retains its exact owner on recoverable failure.
                }
            },
            "airplay-physical-panel-retirement",
        ).apply { isDaemon = true }.start()
    }

    /** Register against a process-owned version before checking it, so withdraw cannot race by. */
    private fun armPhysicalPanelRevocation(next: AirPlayConfig): Boolean {
        val panels = next.displayPanels
        val proof = next.displayPanelsLiveness
        if (panels == null) {
            retirePhysicalPanelSubscription()
            return true
        }
        if (proof == null || !proof.isCurrentFor(panels)) return false
        synchronized(physicalPanelSubscriptionLock) {
            if (physicalPanelSubscribedPanels === panels &&
                physicalPanelSubscribedProof === proof && physicalPanelSubscription != null
            ) return proof.isCurrentFor(panels)
        }
        val subscription = runCatching {
            proof.subscribeRevocation(panels) {
                requestPhysicalPanelRetirement(panels, proof)
            }
        }.getOrNull() ?: return false
        val previous = synchronized(physicalPanelSubscriptionLock) {
            if (isTerminating()) {
                subscription.close()
                return false
            }
            val old = physicalPanelSubscription
            physicalPanelSubscribedPanels = panels
            physicalPanelSubscribedProof = proof
            physicalPanelSubscription = subscription
            old
        }
        runCatching { previous?.close() }
        return proof.isCurrentFor(panels)
    }

    private fun retirePhysicalPanelSubscription() {
        val registration = synchronized(physicalPanelSubscriptionLock) {
            val old = physicalPanelSubscription
            physicalPanelSubscription = null
            physicalPanelSubscribedPanels = null
            physicalPanelSubscribedProof = null
            old
        }
        runCatching { registration?.close() }
    }

    private fun ensureAuthenticatedOverlay(): Boolean {
        val admit = connectionOverlayAdmission ?: return true
        if (overlayAdmissionAttempted) return overlayAdmissionAccepted
        overlayAdmissionAttempted = true
        overlayAdmissionAccepted = try {
            admit(this)
        } catch (error: Exception) {
            debugLog("airplay authenticated capability admission failed: ${error.javaClass.simpleName}")
            false
        }
        if (!overlayAdmissionAccepted) retirementRequested.set(true)
        return overlayAdmissionAccepted
    }

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    internal val isWireless: Boolean =
        sessionTransport == AirPlaySessionTransport.WIRELESS_LOCAL_NETWORK
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    internal val sharedSecret: ByteArray? get() = pairVerify.shared

    /**
     * The sole production issuer for a type-103 raw-secret lease. The marker below is published
     * only after the plaintext M4 RTSP response has flushed and this session's cipher/control
     * authentication has been installed. It is never inferred from PairVerify.isVerified alone.
     */
    /** Legacy detached probe: without a frozen request it cannot be reissued within this M4. */
    internal fun claimMainBufferedM4FlushedLease(): MainBufferedM4FlushedSessionLease? =
        claimMainBufferedM4FlushedLeaseForKey(null, null, null)

    /** Product claim: the replay key is frozen before any socket, native output, or key borrow. */
    internal fun claimMainBufferedM4FlushedLease(
        ticket: MainBufferedV27RequestTicket,
    ): MainBufferedM4FlushedSessionLease? {
        val (token, generation, disposableKey) = synchronized(mainBufferedM4LeaseLock) {
            val epoch = mainBufferedM4PairVerifyGeneration ?: return null
            val secret = mainBufferedM4ReplayHmacKey?.copyOf() ?: return null
            Triple(epoch, mainBufferedM4NumericGeneration, secret)
        }
        val input = ticket.key.shkSha256.toByteArray(Charsets.US_ASCII)
        val fingerprint = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(disposableKey, "HmacSHA256"))
            val digest = mac.doFinal(input)
            try {
                digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            } finally {
                digest.fill(0)
            }
        } finally {
            disposableKey.fill(0)
            input.fill(0)
        }
        return claimMainBufferedM4FlushedLeaseForKey(ticket, token, generation to fingerprint)
    }

    private fun claimMainBufferedM4FlushedLeaseForKey(
        ticket: MainBufferedV27RequestTicket?,
        expectedToken: Any?,
        replayProof: Pair<Long, String>?,
    ): MainBufferedM4FlushedSessionLease? =
        synchronized(mainBufferedM4LeaseLock) {
            val token = mainBufferedM4PairVerifyGeneration ?: return@synchronized null
            if (ticket != null && (expectedToken !== token ||
                replayProof?.first != mainBufferedM4NumericGeneration ||
                mainBufferedM4ReplayHmacKey == null)
            ) return@synchronized null
            val requestKey: MainBufferedV27RequestKey? = ticket?.key
            val local = localAddress ?: return@synchronized null
            val peer = remoteAddress ?: return@synchronized null
            if (!isWireless || isTerminating() || !authenticatedControl.get() || cipher == null ||
                mainBufferedM4LeaseIssued || currentMainBufferedM4Reservation != null ||
                mainBufferedM4NumericGeneration <= 0L ||
                authenticatedPairVerifyGeneration !== token ||
                pairVerify.verifiedGeneration !== token ||
                local.isAnyLocalAddress || local.isMulticastAddress ||
                peer.isAnyLocalAddress || peer.isMulticastAddress
            ) return@synchronized null
            if (requestKey != null && (
                requestKey.connectionIdBits in usedMainBufferedConnectionIds ||
                    requestKey.mctlSeedBits?.let { it in usedMainBufferedMctlSeeds } == true ||
                    replayProof?.second in usedMainBufferedShkFingerprints
                )) return@synchronized null
            if (mainBufferedM4IssuedOrdinal >= MAX_MAIN_BUFFERED_M4_CHILDREN_PER_EPOCH) {
                return@synchronized null
            }
            val streamId = nextMainBufferedStreamId.getAndIncrement()
            if (streamId !in 1L..MAX_MAIN_BUFFERED_STREAM_ID) return@synchronized null
            val marker = if (ticket == null) null else Any().also { owner ->
                if (!ticket.bindClaim(owner)) return@synchronized null
            }
            AirPlayMainBufferedM4Lease(
                this, token, mainBufferedM4NumericGeneration, local, peer, streamId,
                marker, mainBufferedM4IssuedOrdinal + 1L,
            ).also { lease ->
                mainBufferedM4IssuedOrdinal++
                requestKey?.let { key ->
                    usedMainBufferedConnectionIds += key.connectionIdBits
                    key.mctlSeedBits?.let(usedMainBufferedMctlSeeds::add)
                    usedMainBufferedShkFingerprints += checkNotNull(replayProof).second
                }
                retainedMainBufferedM4Leases[lease] = Unit
                currentMainBufferedM4Lease = lease
                currentMainBufferedM4Reservation = MainBufferedM4Reservation(
                    token, mainBufferedM4NumericGeneration, lease,
                    requestKey?.connectionIdBits, requestKey?.mctlSeedBits,
                    replayProof?.second, marker,
                )
                mainBufferedM4LeaseIssued = true
            }
        }

    private fun publishMainBufferedM4FlushedGeneration() {
        val candidateKey = ByteArray(32).also(SecureRandom()::nextBytes)
        synchronized(mainBufferedM4LeaseLock) {
            val token = authenticatedPairVerifyGeneration
            if (isTerminating() || !authenticatedControl.get() || cipher == null || token == null ||
                pairVerify.verifiedGeneration !== token ||
                lastPublishedMainBufferedM4PairVerifyGeneration === token ||
                mainBufferedM4NumericGeneration == Long.MAX_VALUE
            ) {
                candidateKey.fill(0)
                return@synchronized
            }
            retainedMainBufferedM4Leases.keys.forEach(AirPlayMainBufferedM4Lease::revoke)
            currentMainBufferedM4Lease = null
            currentMainBufferedM4Reservation = null
            usedMainBufferedConnectionIds.clear()
            usedMainBufferedMctlSeeds.clear()
            usedMainBufferedShkFingerprints.clear()
            mainBufferedM4IssuedOrdinal = 0L
            mainBufferedM4ReplayHmacKey?.fill(0)
            mainBufferedM4ReplayHmacKey = candidateKey
            mainBufferedM4NumericGeneration++
            mainBufferedM4PairVerifyGeneration = token
            lastPublishedMainBufferedM4PairVerifyGeneration = token
            mainBufferedM4LeaseIssued = false
        }
    }

    private fun revokeMainBufferedM4Leases() = synchronized(mainBufferedM4LeaseLock) {
        mainBufferedM4PairVerifyGeneration = null
        mainBufferedM4ReplayHmacKey?.fill(0)
        mainBufferedM4ReplayHmacKey = null
        retainedMainBufferedM4Leases.keys.forEach(AirPlayMainBufferedM4Lease::revoke)
        currentMainBufferedM4Reservation?.tombstoned = true
    }

    private fun mainBufferedM4LeaseCurrentLocked(
        lease: AirPlayMainBufferedM4Lease,
        requestedGeneration: Long,
    ): Boolean = retainedMainBufferedM4Leases.containsKey(lease) &&
        currentMainBufferedM4Lease === lease &&
        !lease.revoked && !lease.closed &&
        lease.session === this && requestedGeneration == lease.generation &&
        lease.generation > 0L && mainBufferedM4NumericGeneration == lease.generation &&
        mainBufferedM4PairVerifyGeneration === lease.pairVerifyGeneration &&
        authenticatedPairVerifyGeneration === lease.pairVerifyGeneration &&
        pairVerify.verifiedGeneration === lease.pairVerifyGeneration &&
        authenticatedControl.get() && cipher != null && !isTerminating() && isWireless &&
        lease.bindAddress == localAddress && lease.expectedPeer == remoteAddress

    internal fun isMainBufferedM4LeaseCurrent(
        lease: AirPlayMainBufferedM4Lease,
        requestedGeneration: Long,
    ): Boolean = synchronized(mainBufferedM4LeaseLock) {
        mainBufferedM4LeaseCurrentLocked(lease, requestedGeneration)
    }

    internal fun borrowMainBufferedVerifiedSharedSecret(
        lease: AirPlayMainBufferedM4Lease,
        requestedGeneration: Long,
    ): MainBufferedVerifiedSecretBorrow? {
        val borrow = synchronized(mainBufferedM4LeaseLock) {
            if (!mainBufferedM4LeaseCurrentLocked(lease, requestedGeneration)) return@synchronized null
            pairVerify.beginVerifiedSharedSecretBorrow(
                lease.pairVerifyGeneration, lease.generation,
            )
        } ?: return null
        if (!lease.isCurrent(requestedGeneration)) {
            borrow.close()
            return null
        }
        return borrow
    }

    internal fun releaseMainBufferedM4Lease(lease: AirPlayMainBufferedM4Lease) =
        synchronized(mainBufferedM4LeaseLock) {
            if (retainedMainBufferedM4Leases.remove(lease) == null) return@synchronized
            lease.markClosed()
            if (currentMainBufferedM4Lease === lease) currentMainBufferedM4Lease = null
        }

    private fun bindMainBufferedM4Reservation(
        identity: AirPlayStreamIdentity,
        prepared: AirPlayPreparedMainBufferedStream,
    ): Boolean = synchronized(mainBufferedM4LeaseLock) {
        val reservation = currentMainBufferedM4Reservation ?: return@synchronized false
        val marker = reservation.requestMarker ?: return@synchronized false
        if (reservation.tombstoned || reservation.transaction != null ||
            identity.streamConnectionId != reservation.connectionIdBits
                ?.let(java.lang.Long::toUnsignedString) ||
            !prepared.transaction.isBoundTo(reservation.lease, marker) ||
            !mainBufferedM4LeaseCurrentLocked(reservation.lease, reservation.generation)
        ) return@synchronized false
        reservation.transaction = prepared.transaction
        reservation.prepared = prepared
        true
    }

    private fun markMainBufferedResponseWriteStarted(
        prepared: List<AirPlayPreparedMediaStream>,
    ) = synchronized(mainBufferedM4LeaseLock) {
        val type103 = prepared.filter { it.identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO }
        if (type103.isEmpty()) return@synchronized
        val reservation = currentMainBufferedM4Reservation
        check(type103.size == 1 && reservation != null &&
            reservation.prepared != null && !reservation.tombstoned &&
            !reservation.responseWriteStarted
        ) { "type-103 response has no exact unspent M4 reservation" }
        reservation.responseWriteStarted = true
    }

    private fun markMainBufferedResponseFlushCompleted(
        prepared: List<AirPlayPreparedMediaStream>,
    ) {
        if (prepared.none { it.identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO }) return
        val reservation = synchronized(mainBufferedM4LeaseLock) {
            currentMainBufferedM4Reservation
        } ?: return
        val transactionState = reservation.transaction?.state()
        val identity = AirPlayStreamIdentity(
            STREAM_TYPE_MAIN_BUFFERED_AUDIO,
            reservation.connectionIdBits?.let(java.lang.Long::toUnsignedString),
            null,
        )
        val selectorActive = identity in activeStreams
        synchronized(mainBufferedM4LeaseLock) {
            if (currentMainBufferedM4Reservation !== reservation) return@synchronized
            if (reservation.responseWriteStarted && !reservation.tombstoned &&
                transactionState == MainBufferedV27SetupState.COMMITTED && selectorActive
            ) {
                reservation.responseFlushedAndCommitted = true
            } else {
                reservation.tombstoned = true
            }
        }
    }

    private fun tombstoneMainBufferedResponse(
        prepared: List<AirPlayPreparedMediaStream>,
    ) = synchronized(mainBufferedM4LeaseLock) {
        if (prepared.any { it.identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO }) {
            currentMainBufferedM4Reservation?.tombstoned = true
        }
    }

    /** Disable the old transport immediately, preserving its M4 epoch for a later clean child. */
    private fun revokeMainBufferedChildForTeardown(identity: AirPlayStreamIdentity) =
        synchronized(mainBufferedM4LeaseLock) {
            val reservation = currentMainBufferedM4Reservation ?: return@synchronized
            if (reservation.connectionIdBits == null || identity.streamConnectionId ==
                java.lang.Long.toUnsignedString(reservation.connectionIdBits)
            ) reservation.lease.revoke()
        }

    private fun hasBoundMainBufferedReservation(identity: AirPlayStreamIdentity): Boolean =
        synchronized(mainBufferedM4LeaseLock) {
            currentMainBufferedM4Reservation?.let { reservation ->
                reservation.requestMarker != null && reservation.transaction != null &&
                    identity.streamConnectionId ==
                        reservation.connectionIdBits?.let(java.lang.Long::toUnsignedString)
            } == true
        }

    /** All potentially blocking joins and engine queries run outside the M4 ledger monitor. */
    private fun completeMainBufferedQuiescence(identity: AirPlayStreamIdentity): Boolean {
        val reservation = synchronized(mainBufferedM4LeaseLock) {
            currentMainBufferedM4Reservation?.takeIf { entry ->
                entry.requestMarker != null &&
                    entry.responseFlushedAndCommitted && entry.transaction != null &&
                    entry.prepared != null &&
                    identity.streamConnectionId ==
                        entry.connectionIdBits?.let(java.lang.Long::toUnsignedString)
            }
        } ?: return false
        val transaction = checkNotNull(reservation.transaction)
        val prepared = checkNotNull(reservation.prepared)
        if (transaction.state() != MainBufferedV27SetupState.CLOSED ||
            identity !in activeStreams ||
            synchronized(mainBufferedTransactionsLock) {
                mainBufferedTransactions.containsKey(identity)
            }
        ) return false
        try {
            // An exact borrow/lease latch can be pending without any failed close. Keep the
            // selector and transaction receipt so the same TEARDOWN may retry after return.
            reservation.lease.awaitClosed(retirementJoinTimeoutMillis)
            pairVerify.awaitSecretBorrows(retirementJoinTimeoutMillis)
        } catch (_: Throwable) {
            return false
        }
        if (media !is CarPlayMediaEngine ||
            !media.isMainBufferedOwnerQuiescent(this, identity, prepared)
        ) return false
        // Commit selector retirement and M4 child release at one exact control-session boundary.
        // Match commitSetup's lock order (command dispatch before M4 ledger); no socket, close,
        // parser, or wait runs while either monitor is held.
        return synchronized(commandDispatchLock) {
            synchronized(mainBufferedM4LeaseLock) {
                if (currentMainBufferedM4Reservation !== reservation ||
                    mainBufferedM4PairVerifyGeneration !== reservation.token ||
                    mainBufferedM4NumericGeneration != reservation.generation ||
                    authenticatedPairVerifyGeneration !== reservation.token ||
                    pairVerify.verifiedGeneration !== reservation.token ||
                    !reservation.responseFlushedAndCommitted ||
                    !reservation.lease.closed || isTerminating() ||
                    identity !in activeStreams
                ) false else {
                    activeStreams.remove(identity)
                    if (!reservation.tombstoned) {
                        currentMainBufferedM4Reservation = null
                        mainBufferedM4LeaseIssued = false
                    }
                    true
                }
            }
        }
    }

    private fun awaitMainBufferedPreparations() {
        val leases = synchronized(mainBufferedM4LeaseLock) {
            retainedMainBufferedM4Leases.keys.toList()
        }
        leases.forEach { it.awaitPreparation(retirementJoinTimeoutMillis) }
    }

    private fun awaitMainBufferedLeaseReleases() {
        val leases = synchronized(mainBufferedM4LeaseLock) {
            retainedMainBufferedM4Leases.keys.toList()
        }
        leases.forEach { it.awaitClosed(retirementJoinTimeoutMillis) }
    }

    /**
     * Called by a future, explicitly provisioned MatchPoint transport owner after the M4 response
     * has been flushed. Only one live lease exists for this exact control-session generation.
     * This does not enable or advertise videoPlayback or create a MatchPoint socket.
     */
    internal fun claimPairTlsPskLease(): AirPlayPairTlsPskLease? =
        synchronized(pairTlsPskLeaseLock) {
            if (isTerminating() || !authenticatedControl.get() || cipher == null) {
                return@synchronized null
            }
            val generation = pairVerify.verifiedGeneration ?: return@synchronized null
            if (authenticatedPairVerifyGeneration !== generation) return@synchronized null
            if (pairTlsBorrowCompletion != null || pairTlsTransportOwner != null) {
                return@synchronized null
            }
            pairTlsPskLease?.let { existing ->
                if (!existing.retired && existing.pairVerifyGeneration === generation) {
                    return@synchronized null
                }
                existing.retire()
            }
            AirPlayPairTlsPskLease(this, pairTlsControlEpoch, generation).also {
                pairTlsPskLease = it
            }
        }

    internal fun <T> withPairTlsPsk(
        lease: AirPlayPairTlsPskLease,
        requester: AirPlaySession,
        consume: (ByteArray) -> T,
        retain: ((T) -> Unit)? = null,
    ): T? {
        val reserved = synchronized(pairTlsPskLeaseLock) {
            if (requester !== this || lease.owner !== this ||
                lease.controlEpoch !== pairTlsControlEpoch || pairTlsPskLease !== lease ||
                lease.retired || isTerminating() || !authenticatedControl.get() || cipher == null ||
                authenticatedPairVerifyGeneration !== lease.pairVerifyGeneration ||
                pairTlsBorrowThread != null || pairTlsTransportOwner != null
            ) {
                return@synchronized null
            }
            val borrow = pairVerify.beginFreshPairTlsPskBorrow(lease.pairVerifyGeneration)
                ?: return@synchronized null
            val completion = CountDownLatch(1)
            pairTlsBorrowThread = Thread.currentThread()
            pairTlsBorrowCompletion = completion
            pairTlsBorrowLease = lease
            borrow to completion
        } ?: return null
        return try {
            // No session/Pair Verify lock is held while the future TLS engine is constructed.
            reserved.first.use(consume).also { result -> retain?.invoke(result) }
        } finally {
            try {
                reserved.first.retire()
            } finally {
                synchronized(pairTlsPskLeaseLock) {
                    pairTlsBorrowThread = null
                    pairTlsBorrowCompletion = null
                    pairTlsBorrowLease = null
                    reserved.second.countDown()
                }
            }
        }
    }

    internal fun <T : Closeable> openPairTlsPskTransport(
        lease: AirPlayPairTlsPskLease,
        requester: AirPlaySession,
        construct: (ByteArray) -> T,
    ): T? {
        val created = withPairTlsPsk(
            lease,
            requester,
            construct,
            retain = { transport ->
                // The borrow completion fence is not released until this exact owner is retained.
                synchronized(pairTlsPskLeaseLock) {
                    check(pairTlsTransportOwner == null)
                    pairTlsTransportOwner = PendingPairTlsPskTransport(lease, transport)
                }
            },
        ) ?: return null
        if (lease.retired || isTerminating()) {
            retirePairTlsPskTransport(lease)
            return null
        }
        return created
    }

    internal fun releasePairTlsPsk(lease: AirPlayPairTlsPskLease) {
        val activeBorrow = synchronized(pairTlsPskLeaseLock) {
            if (lease.owner !== this || lease.controlEpoch !== pairTlsControlEpoch) return
            lease.retire()
            if (pairTlsPskLease === lease) pairTlsPskLease = null
            if (pairTlsBorrowLease === lease) {
                pairTlsBorrowThread to pairTlsBorrowCompletion
            } else {
                null
            }
        }
        activeBorrow?.let { (thread, completion) ->
            check(thread !== Thread.currentThread()) {
                "Pair-TLS-PSK lease cannot retire its own active borrow"
            }
            val completed = try {
                completion?.await(retirementJoinTimeoutMillis, TimeUnit.MILLISECONDS) ?: true
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
            if (!completed) {
                throw IOException("Timed out retiring Pair-TLS-PSK lease borrow")
            }
        }
        retirePairTlsPskTransport(lease)
    }

    private fun retirePairTlsPskTransport(lease: AirPlayPairTlsPskLease? = null) {
        val pending = synchronized(pairTlsPskLeaseLock) {
            pairTlsTransportOwner?.takeIf { lease == null || it.lease === lease }
        } ?: return
        if (pending.isRetiringOn(Thread.currentThread())) {
            // An owner's close callback may release its credential lease. The outer retirement
            // remains the exact owner and completes the fence when that callback returns.
            return
        }
        pending.retire(retirementJoinTimeoutMillis)
        synchronized(pairTlsPskLeaseLock) {
            if (pending.isRetired() && pairTlsTransportOwner === pending) {
                pairTlsTransportOwner = null
            }
        }
    }

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun presentationMonotonicNanos(timing: ScreenFrameTiming): Long? {
        if (AirPlayFeature.UI_SYNC !in negotiatedFeatures) return null
        return timing.presentationTimeNtp64Bits?.let { raw ->
            ntp.presentationMonotonicNanos(raw, timing.arrivalMonotonicNanos)
        }
    }

    internal fun logDebug(message: String) = debugLog(message)

    internal fun logTrace(message: String) = trace(message)

    fun start() {
        var startFailure: Throwable? = null
        synchronized(retirementLock) {
            check(!isTerminating()) { "AirPlaySession is terminating" }
            check(controlThread == null) { "AirPlaySession has already been started" }
            Thread(::runControl, "airplay-control").apply {
                isDaemon = true
                controlThread = this
                try {
                    start()
                } catch (failure: Throwable) {
                    startFailure = failure
                }
            }
        }
        startFailure?.let { failure ->
            try {
                close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    override fun close() {
        // Publish STOPPING without waiting for a renderer publish or listener callback that may
        // already be running under commandDispatchLock. Their commit paths recheck this atomic
        // state after the external call; the physical cover must not wait for them to return.
        val caller = Thread.currentThread()
        stopping.set(true)
        revokeMainBufferedM4Leases()
        if (closed.get()) return
        retirementRequested.set(true)
        if (!sessionStopCoverInFlight.compareAndSet(false, true)) {
            // Another close (usually the retained coordinator) owns the cover. Once the
            // coordinator is scheduled this is a hand-off, not a failure: a worker's final
            // close() must not escape its thread and kill the process.
            try {
                scheduleDeferredRetirement(caller)
            } catch (coordinatorFailure: Throwable) {
                val failure = IOException("Session stopping cover is already in flight")
                failure.addSuppressed(coordinatorFailure)
                throw failure
            }
            throw DeferredRetirementException(
                "Session stopping cover is already in flight; retirement deferred to the coordinator",
            )
        }
        try {
            // This must precede Pair-TLS-PSK borrow/transport waits: a stalled socket may still
            // leave a decoded frame visible until its independent physical cover takes effect.
            media.onSessionStopping(this)
        } catch (error: Throwable) {
            try { scheduleDeferredRetirement(caller) } catch (coordinatorFailure: Throwable) {
                if (coordinatorFailure !== error) error.addSuppressed(coordinatorFailure)
            }
            throw error
        } finally {
            sessionStopCoverInFlight.set(false)
        }
        // Once the exact physical cover ACKs, join any command/SETUP callback already admitted
        // before retiring its transport. This preserves the old commit fence without delaying
        // STOPPING or the cover behind a potentially blocked renderer callback.
        synchronized(commandDispatchLock) { }
        retirePhysicalPanelSubscription()
        try {
            awaitMainBufferedPreparations()
        } catch (failure: Throwable) {
            try { scheduleDeferredRetirement(caller) } catch (coordinatorFailure: Throwable) {
                if (coordinatorFailure !== failure) failure.addSuppressed(coordinatorFailure)
            }
            throw failure
        }
        val (pendingPskBorrow, pendingTlsOwner) = synchronized(pairTlsPskLeaseLock) {
            pairTlsPskLease?.retire()
            pairTlsPskLease = null
            (pairTlsBorrowThread to pairTlsBorrowCompletion) to pairTlsTransportOwner
        }
        if (closed.get()) return

        if (pendingTlsOwner?.isRetiringOn(caller) == true) {
            // This call came from the resource whose retirement is already in progress. It
            // cannot synchronously wait for itself; let the outer close complete, then finish
            // session teardown on the retained coordinator. Returning here never publishes end.
            scheduleDeferredRetirement(caller)
            return
        }

        if (pendingPskBorrow.first === caller) {
            // A TLS-construction callback cannot prove its own temporary PSK has been wiped.
            // The retained coordinator retries after the callback's finally block exits.
            scheduleDeferredRetirement(caller)
            throw DeferredRetirementException(caller)
        }
        pendingPskBorrow.second?.let { completion ->
            try {
                if (!completion.await(retirementJoinTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw IOException("Timed out retiring Pair-TLS-PSK borrow")
                }
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                try {
                    scheduleDeferredRetirement(caller)
                } catch (coordinatorFailure: Exception) {
                    if (coordinatorFailure !== failure) failure.addSuppressed(coordinatorFailure)
                }
                throw failure
            }
        }

        if (isOwnedWorker(caller)) {
            // An owned worker cannot prove its own termination. A distinct retained coordinator
            // retries the same exact resources after this callback unwinds; until then the service
            // remains the session owner because onSessionEnded is deliberately not published.
            scheduleDeferredRetirement(caller)
            throw DeferredRetirementException(caller)
        }

        var publishEnded = false
        try {
            synchronized(retirementAttemptLock) {
                if (closed.get()) return@synchronized
                // The exact TLS owner must retire before Pair Verify material can be wiped. A
                // failed close leaves it retained for this same session's next close attempt.
                retirePairTlsPskTransport()
                retireOwnedResources()
                retireMainBufferedTransactions()
                if (!mediaRetired) {
                    media.onSessionClosed(this)
                    mediaRetired = true
                }
                if (!connectionOverlayRetired) {
                    connectionOverlay?.close()
                    pendingOverlayOwner?.close()
                    pendingOverlayOwner = null
                    connectionOverlayRetired = true
                }
            }
            // Neither a still-constructing type-103 owner nor a raw PairVerify borrow may
            // outlive secret retirement. Both joins are bounded and outside session monitors.
            awaitMainBufferedLeaseReleases()
            pairVerify.requestSessionSecretsRetirement()
            pairVerify.awaitSecretBorrows(retirementJoinTimeoutMillis)
            synchronized(retirementAttemptLock) {
                if (!closed.get()) {
                    retireSessionSecrets()
                    closed.set(true)
                    publishEnded = notified.compareAndSet(false, true)
                }
            }
        } catch (error: Throwable) {
            // The caller can be a media-owned ScreenStream/AudioStream worker rather than one of
            // this session's directly tracked workers. Its media retirement correctly fails until
            // that callback unwinds, so retain a distinct coordinator to retry the same exact
            // owners instead of leaving the session permanently STOPPING.
            try {
                scheduleDeferredRetirement(caller)
            } catch (coordinatorFailure: Throwable) {
                if (coordinatorFailure !== error) error.addSuppressed(coordinatorFailure)
            }
            throw error
        }
        // Listener code may re-enter the service/session and must never run under teardown locks.
        if (publishEnded) listener.onSessionEnded(this)
    }

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    private fun sendCommandLocked(command: Map<String, Any?>): Boolean {
        if (stopping.get() || closed.get()) return false
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        trace(
            "airplay event tx cseq=$eventCseq type=${command["type"] ?: "unknown"} " +
                "bodyBytes=${body.size}",
        )
        return try {
            // A failed write after encryption still consumes this cipher's nonce.
            eventOutboundNonceUsed?.set(true)
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            if (eventSocket === socket) {
                eventSocket = null
                eventCipher = null
            }
            safeClose(socket)
            false
        }
    }

    fun acceptsViewAreas(): Boolean = !isClosed && displayControl.acceptsViewAreas()

    /** Sends updateViewArea through POST /command, serialized by the [sendCommand] write lock. */
    fun showViewArea(index: Int): Boolean = !isClosed && displayControl.showViewArea(index)

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val scaled = contacts.map {
            it.copy(x = it.x * config.main.widthPixels, y = it.y * config.main.heightPixels)
        }
        val report = AirPlayHid.touchReport(scaled)
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = scaled.firstOrNull()
            Log.i(
                TAG,
                "airplay touch report sent contacts=${scaled.size} first=" +
                    "(${first?.x},${first?.y},down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean) =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int) {
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
    }

    fun sendTelephony(index: Int) {
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
    }

    fun invokeSiri() {
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (isTerminating()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        pendingNightMode = night
        sendPendingNightModeLocked()
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = pendingNightMode ?: return true
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (sent) pendingNightMode = null
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )

    private fun runControl() {
        var closeReason = "session closed"
        var accumulated = ByteArray(0)
        try {
            if (!armPhysicalPanelRevocation(config)) {
                throw IOException("AirPlay physical display proof is not current")
            }
            // Stream acquisition belongs inside the retirement finally path. A socket accepted
            // just before detach may already be closed when this worker first runs.
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val buffer = ByteArray(READ_CHUNK_BYTES)
            controlLoop@ while (!isRetirementRequested()) {
                checkPreAuthDeadline()
                checkInfoOnlyReservationDeadline()
                val count = try {
                    input.read(buffer)
                } catch (timeout: SocketTimeoutException) {
                    if (infoOnlyReservationDeadlineNanos == null) throw timeout
                    checkInfoOnlyReservationDeadline()
                    continue@controlLoop
                }
                checkPreAuthDeadline()
                checkInfoOnlyReservationDeadline()
                if (count < 0) {
                    closeReason = "peer EOF"
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                val requestArrivedEncrypted = activeCipher != null
                if (activeCipher != null) {
                    encBuf = appendBounded(encBuf, plaintext, "encrypted control")
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $closeReason encrypted=${encBuf.size}", error)
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                val previousAccumulated = accumulated
                accumulated = try {
                    appendBounded(previousAccumulated, plaintext, "control request")
                } finally {
                    // Both arrays have transferred into the new bounded frame buffer. Neither
                    // is an unconsumed remainder after this copy, even for a split SETUP body.
                    previousAccumulated.fill(0)
                    plaintext.fill(0)
                }
                val parsed = try {
                    RtspMessage.parseMessages(accumulated)
                } finally {
                    // parseMessages copies every complete Request.body and the partial rest.
                    // The source buffer cannot be retained as a second plaintext shk copy.
                    accumulated.fill(0)
                }
                accumulated = parsed.rest
                if (parsed.messages.isNotEmpty()) displayControl.responseStarted()
                var terminateAfterBatch = false
                try {
                for ((requestIndex, request) in parsed.messages.withIndex()) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    debugLog(
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}",
                        showInDebugOverlay,
                    )
                    trace(
                        "airplay control rx method=${request.method} path=${request.path} " +
                            "cseq=$cseq bodyBytes=${request.body.size}",
                    )
                    val handled = try {
                        handle(request, requestArrivedEncrypted)
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        HandledResponse(RtspMessage.Response(status = 500))
                    }
                    val response = handled.response
                    debugLog(
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}",
                        showInDebugOverlay,
                    )
                    val wire = RtspMessage.buildResponse(request, response)
                    trace(
                        "airplay control tx status=${response.status ?: 200} cseq=$cseq " +
                            "bodyBytes=${response.body.size} wireBytes=${wire.size}",
                    )
                    try {
                        handled.responseWriteStarted()
                        output.write(cipher?.encrypt(wire) ?: wire)
                        // A prepared listener must not attach while its response is still merely
                        // buffered: a failed flush means the phone never learned the port.
                        output.flush()
                        var enabledControlEncryption = false
                        val pendingControlKeys = if (cipher == null) pairVerify.controlKeys else null
                        if (pendingControlKeys != null) {
                            try {
                                cipher = ControlCipher(pendingControlKeys.readKey, pendingControlKeys.writeKey)
                            } finally {
                                pendingControlKeys.readKey.fill(0)
                                pendingControlKeys.writeKey.fill(0)
                            }
                            socket.soTimeout = 0
                            synchronized(pairTlsPskLeaseLock) {
                                authenticatedPairVerifyGeneration =
                                    if (isTerminating()) null else pairVerify.verifiedGeneration
                                authenticatedControl.set(authenticatedPairVerifyGeneration != null)
                            }
                            // This is downstream of output.flush(), cipher installation, and
                            // authenticatedControl publication. Failed M4 writes never arm it.
                            publishMainBufferedM4FlushedGeneration()
                            enabledControlEncryption = true
                            debugLog("airplay control encryption enabled")
                        }
                        handled.responseFlushed()
                        if (isRetirementRequested()) {
                            // A flushed stopSession response closes atomically. Do not dispatch
                            // any request that shared the already-parsed encrypted batch.
                            terminateAfterBatch = true
                            break
                        }
                        if (enabledControlEncryption && !requestArrivedEncrypted &&
                            (requestIndex != parsed.messages.lastIndex || accumulated.isNotEmpty())
                        ) {
                            // Requests parsed beside the final plaintext pair-verify exchange do
                            // not become authenticated merely because its response enabled the
                            // cipher. Drop the whole trailing plaintext pipeline.
                            closeReason = "plaintext request pipelined across pair-verify"
                            accumulated.fill(0)
                            accumulated = ByteArray(0)
                            terminateAfterBatch = true
                            break
                        }
                    } catch (error: Throwable) {
                        handled.responseFailed()
                        throw error
                    }
                }
                } finally {
                    // Each Request.body is a parser-owned copy. This also covers a trailing
                    // pipelined SETUP that is deliberately dropped before handleSetup runs.
                    parsed.messages.forEach { it.body.fill(0) }
                }
                if (parsed.messages.isNotEmpty()) displayControl.responseSent()
                if (terminateAfterBatch) break@controlLoop
            }
        } catch (error: Exception) {
            closeReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!isRetirementRequested()) runCatching { Log.e(TAG, "airplay $closeReason", error) }
        } finally {
            accumulated.fill(0)
            debugLog("airplay control closing reason=$closeReason activeStreams=$activeStreams")
            try {
                close()
            } catch (_: DeferredRetirementException) {
                // The retained coordinator completes retirement after this worker exits.
            } catch (failure: Exception) {
                // close() has already scheduled the retained coordinator for every recoverable
                // failure; an escaping exception here would only kill the whole process.
                runCatching { Log.w(TAG, "airplay control final close deferred", failure) }
            }
        }
    }

    private class HandledResponse(
        val response: RtspMessage.Response,
        private val onWriteStarted: () -> Unit = {},
        private val onFlushed: () -> Unit = {},
        private val onFailed: () -> Unit = {},
    ) {
        private val completed = AtomicBoolean(false)

        fun responseWriteStarted() {
            check(!completed.get()) { "RTSP response is already terminal" }
            onWriteStarted()
        }

        fun responseFlushed() {
            if (completed.compareAndSet(false, true)) onFlushed()
        }

        fun responseFailed() {
            if (completed.compareAndSet(false, true)) onFailed()
        }
    }

    private fun handle(
        request: RtspMessage.Request,
        arrivedEncrypted: Boolean,
    ): HandledResponse {
        if (stopping.get()) {
            return HandledResponse(
                RtspMessage.Response(status = 409, statusText = "Session Stopping"),
            )
        }
        when (request.method) {
            "SETUP" -> {
                if (!isAuthenticatedRequest(arrivedEncrypted)) return forbiddenResponse()
                return handleSetup(request, arrivedEncrypted)
            }
            "RECORD" -> {
                if (!isAuthenticatedRequest(arrivedEncrypted)) return forbiddenResponse()
                listener.onSessionActive(this)
                return HandledResponse(RtspMessage.Response(status = 200))
            }
            "TEARDOWN" -> {
                if (!isAuthenticatedRequest(arrivedEncrypted)) return forbiddenResponse()
                return HandledResponse(handleTeardown(request))
            }
        }

        val path = request.path.lowercase()
        // Re-pairing on an already encrypted control stream would replace PairVerify's ECDH
        // generation while [cipher] still holds the old traffic keys. A new generation requires
        // a new TCP control session; reject without passing the frame to either state machine.
        if (cipher != null && (path.endsWith("/pair-verify") || path.endsWith("/pair-setup"))) {
            return forbiddenResponse()
        }
        val response = when {
            path.endsWith("/pair-setup") -> RtspMessage.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> {
                if (runCatching {
                        Tlv8Codec.decode(request.body)[0x06]?.contentEquals(byteArrayOf(1))
                    }.getOrDefault(false) == true) revokeMainBufferedM4Leases()
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                    body = pairVerify.handle(request.body),
                )
            }
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { MfiSapAuthSetup.handle(request.body, it) }
                if (body == null) RtspMessage.Response(status = 400)
                else RtspMessage.Response(headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE), body = body)
            }
            path.endsWith("/info") -> {
                // Once Pair Verify has completed, a plaintext request must not retrieve a scope
                // that a preceding encrypted /info or SETUP may have frozen with Ultra sidecars.
                if (pairVerify.isVerified && !isAuthenticatedRequest(arrivedEncrypted)) {
                    return forbiddenResponse()
                }
                val requestInfo = try {
                    if (request.body.isEmpty()) emptyMap() else asMap(BplistCodec.decode(request.body)) ?: emptyMap()
                } catch (error: Exception) {
                    val prefix = String(
                        request.body,
                        0,
                        minOf(request.body.size, 8),
                        Charsets.UTF_8,
                    ).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
                    val magic = when {
                        prefix.startsWith("bplist00") -> "bplist"
                        prefix.startsWith("<?xml") || prefix.startsWith("<plist") -> "xml"
                        else -> "other"
                    }
                    val contentType = request.headers["content-type"].orEmpty()
                        .replace('\r', ' ').replace('\n', ' ').take(128)
                    val reason = error.message.orEmpty()
                        .replace('\r', ' ').replace('\n', ' ').take(160)
                    Log.w(
                        TAG,
                        "airplay /info decode failed bytes=${request.body.size} " +
                            "contentType=$contentType magic=$magic " +
                            "error=${error.javaClass.simpleName}: $reason",
                    )
                    return HandledResponse(RtspMessage.Response(status = 400))
                }
                val info = try {
                    if (isAuthenticatedRequest(arrivedEncrypted)) {
                        if (!ensureAuthenticatedOverlay()) {
                            return HandledResponse(
                                RtspMessage.Response(status = 409, statusText = "Session Stopping"),
                            )
                        }
                        displayControl.info(requestInfo, infoTransportReadiness(arrivedEncrypted))
                    } else {
                        // Discovery can request /info before Pair Verify. Keep that ordinary response
                        // stateless so it cannot permanently exclude an authorized Ultra sidecar once
                        // the encrypted control session is established.
                        displayControl.discoveryInfo(requestInfo)
                    }
                } catch (_: AirPlayFeatureScopeRevokedException) {
                    debugLog("airplay /info rejected: physical display proof or feature scope revoked")
                    return physicalPanelRevokedResponse()
                }
                debugLog(
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                    uiVisible = false,
                )
                debugLog(
                    "airplay /info audio=${if (config.wirelessAudio) "wireless PCM+Opus" else "wired PCM"} " +
                        "microphone=${config.microphone}",
                )
                debugLog(
                    "airplay /info order=${if (featureSetupSeen) "setup-first" else "info-first"} " +
                        "ultraSidecars=" +
                        "vsp:${"vehicleStateProtocolInfo" in info}," +
                        "uiSync:${"uiSyncInfo" in info}," +
                        "sessionManagement:${"sessionManagementInfo" in info}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                val body = BplistCodec.encode(info)
                // A lease may be held even when current transport readiness hides its sidecar.
                // The product overlay installs a one-shot UpdateData factory only with its
                // reservation; a base runtime with no extra factory is not subject to this timer.
                if (connectionOverlay?.extraRcsFactory != null &&
                    config.ultra?.isConfigured(AirPlayFeature.FILE_TRANSFER) == true
                ) {
                    armInfoOnlyReservationDeadline()
                }
                RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = body,
                )
            }
            request.method == "POST" && path.endsWith("/command") -> {
                if (isAuthenticatedRequest(arrivedEncrypted)) {
                    return handleCommand(request)
                } else {
                    RtspMessage.Response(status = 403, statusText = "Forbidden")
                }
            }
            request.method == "POST" && path.endsWith("/feedback") -> {
                if (!isAuthenticatedRequest(arrivedEncrypted)) {
                    return forbiddenResponse()
                }
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessage.Response(status = 200)
                } else {
                    RtspMessage.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessage.Response(status = 200)
        }
        return HandledResponse(response)
    }

    private fun isAuthenticatedRequest(arrivedEncrypted: Boolean): Boolean =
        arrivedEncrypted && pairVerify.isVerified

    /**
     * `/info` can precede feature SETUP, so only an encrypted, pair-verified wireless session may
     * advertise provisioned Ultra sidecars. The event bind address establishes that its channel
     * is provisionable; SETUP must still open the port and re-probe every actual transport gate.
     */
    private fun infoTransportReadiness(arrivedEncrypted: Boolean): AirPlayTransportReadiness? {
        if (!isAuthenticatedRequest(arrivedEncrypted) || cipher == null) return null
        val configuredRcsTypes = AirPlayUltraConfiguration.CONFIGURABLE_FEATURES
            .filter { config.ultra?.isConfigured(it) == true }
            .flatMapTo(linkedSetOf(), AirPlayFeatureCatalog::requiredRcsClientTypes)
        val readyRcsTypes = configuredRcsTypes.filterTo(linkedSetOf()) { clientType ->
            runCatching { media.isRcsReady(this, listOf(clientType)) }.getOrDefault(false)
        }
        return AirPlayTransportReadiness(
            secureControlChannel = true,
            eventChannel = localAddress?.takeUnless(InetAddress::isAnyLocalAddress) != null,
            alternateScreenSink = config.cluster != null,
            readyRcsClientTypes = readyRcsTypes,
            wirelessSession = isWireless,
        )
    }

    private fun forbiddenResponse(): HandledResponse = HandledResponse(
        RtspMessage.Response(status = 403, statusText = "Forbidden"),
    )

    private fun physicalPanelRevokedResponse(): HandledResponse {
        stopping.set(true)
        return HandledResponse(
            response = RtspMessage.Response(status = 409, statusText = "Session Stopping"),
            onFlushed = {
                runCatching { socket.setSoLinger(false, 0) }
                close()
            },
        )
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        // Local JVM tests use the Android stub jar, whose Log methods throw. Diagnostics must
        // never be able to terminate a control session in production either.
        runCatching { Log.i(TAG, message) }
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private fun trace(message: String) {
        try {
            listener.onDebugLog("TRACE $message")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    private data class PreparedStreamBatch(
        val responses: List<Any?>,
        val prepared: List<AirPlayPreparedMediaStream>,
    )

    private fun handleSetup(
        request: RtspMessage.Request,
        arrivedEncrypted: Boolean,
    ): HandledResponse = handleSetup(request, arrivedEncrypted, BplistCodec::decodeOwned)

    /** Decoder injection is private and used only to audit partial Data ownership in tests. */
    private fun handleSetup(
        request: RtspMessage.Request,
        arrivedEncrypted: Boolean,
        decodeOwned: (ByteArray) -> OwnedDecodedPlist,
    ): HandledResponse {
        if (!isAuthenticatedRequest(arrivedEncrypted)) {
            // No handler may retain an unauthenticated SETUP body, which may still carry shk.
            request.body.fill(0)
            return forbiddenResponse()
        }
        val decoded = try {
            decodeOwned(request.body)
        } catch (error: Exception) {
            // An invalid SETUP may still have decoded a partial type-103 shk before failing.
            // decodeOwned wipes those Data copies; this request owns its RTSP body copy.
            request.body.fill(0)
            runCatching {
                Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            }
            return HandledResponse(RtspMessage.Response(status = 400))
        }
        val dict = asMap(decoded.root) ?: run {
            decoded.release()
            request.body.fill(0)
            return HandledResponse(RtspMessage.Response(status = 400))
        }
        val sourceStreams = dict["streams"] as? List<*>
        val sensitiveShk = sourceStreams.orEmpty().mapNotNull { entry ->
            asMap(entry)?.get("shk") as? ByteArray
        } + listOfNotNull(dict["shk"] as? ByteArray)
        val containsMainBuffered = sourceStreams.orEmpty().any { entry ->
            val stream = asMap(entry) ?: return@any false
            long(stream["type"]) == STREAM_TYPE_MAIN_BUFFERED_AUDIO.toLong() ||
                "shk" in stream
        } || "shk" in dict
        var handlerReceivedDecodedData = false
        try {
        if (!ensureAuthenticatedOverlay()) {
            return HandledResponse(
                RtspMessage.Response(status = 409, statusText = "Session Stopping"),
            )
        }
        if (!config.hasCurrentPhysicalDisplayPanels()) {
            debugLog("airplay SETUP rejected: physical display proof is not current")
            return physicalPanelRevokedResponse()
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = if (containsMainBuffered && sourceStreams != null) {
            detachOtherStreamSecretAliases(sourceStreams, sensitiveShk)
        } else {
            sourceStreams
        }
        if ("streams" in dict && streams == null) {
            return HandledResponse(RtspMessage.Response(status = 400))
        }
        if (streams != null) {
            if (streams.size > MAX_SETUP_STREAMS) {
                debugLog(
                    "airplay SETUP rejected: streams=${streams.size} limit=$MAX_SETUP_STREAMS",
                )
                return HandledResponse(RtspMessage.Response(status = 400))
            }
            if (streams.any { asMap(it) == null }) {
                debugLog("airplay SETUP rejected: stream entry is not a dictionary")
                return HandledResponse(RtspMessage.Response(status = 400))
            }
            val batch = handleStreams(streams) { handlerReceivedDecodedData = true }
            debugLog("airplay SETUP response streams=${batch.responses}")
            val body = try {
                BplistCodec.encode(linkedMapOf("streams" to batch.responses))
            } catch (failure: Throwable) {
                batch.prepared.forEach(AirPlayPreparedMediaStream::rollback)
                throw failure
            }
            trace("airplay SETUP response bodyBytes=${body.size} streams=${batch.responses.size}")
            val cseq = request.headers["cseq"] ?: "-"
            noteAuthenticatedSetup()
            return HandledResponse(
                response = RtspMessage.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = body,
                ),
                onWriteStarted = { markMainBufferedResponseWriteStarted(batch.prepared) },
                onFlushed = {
                    try {
                        commitSetup(cseq, batch.prepared)
                    } finally {
                        markMainBufferedResponseFlushCompleted(batch.prepared)
                    }
                },
                onFailed = {
                    debugLog("airplay SETUP rollback cseq=$cseq reason=response_write_failed")
                    tombstoneMainBufferedResponse(batch.prepared)
                    batch.prepared.forEach(AirPlayPreparedMediaStream::rollback)
                },
            )
        }

        // A second feature SETUP would have to reuse the already published timing/event ports
        // and atomically revoke any active Ultra channels before changing the feature set. This
        // control session implements neither renegotiation nor a second timing listener; retire
        // it before any new device-info callback or port allocation can occur.
        if (featureSetupSeen) {
            debugLog("airplay repeated feature SETUP rejected: reconnect for a new feature scope")
            return physicalPanelRevokedResponse()
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val eventPort = openEvent()
        val response = linkedMapOf<String, Any?>(
            "timingPort" to openTiming(peerTimingPort),
            "eventPort" to eventPort,
        )
        if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
            response["keepAlivePort"] = openKeepAlive()
        }
        val configuredRcsTypes = AirPlayUltraConfiguration.CONFIGURABLE_FEATURES
            .filter { config.ultra?.isConfigured(it) == true }
            .flatMapTo(linkedSetOf(), AirPlayFeatureCatalog::requiredRcsClientTypes)
        val readyRcsTypes = configuredRcsTypes.filterTo(linkedSetOf()) { clientType ->
            media.isRcsReady(this, listOf(clientType))
        }
        val secureControl = pairVerify.isVerified && cipher != null
        val transport = AirPlayTransportReadiness(
            secureControlChannel = secureControl,
            eventChannel = eventPort > 0,
            alternateScreenSink = config.cluster != null,
            readyRcsClientTypes = readyRcsTypes,
            wirelessSession = isWireless,
        )
        val enabled = try {
            displayControl.negotiate(dict["features"], transport)
        } catch (_: AirPlayFeatureScopeRevokedException) {
            debugLog("airplay SETUP rejected: repeated feature scope changed or lost readiness")
            return physicalPanelRevokedResponse()
        }
        if (!config.hasCurrentPhysicalDisplayPanels()) {
            debugLog("airplay SETUP rejected: physical display proof changed during negotiation")
            return physicalPanelRevokedResponse()
        }
        featureSetupSeen = true
        negotiatedFeatures = enabled.mapNotNullTo(linkedSetOf(), AirPlayFeature::fromWireName)
        response["enabledFeatures"] = enabled
        val body = BplistCodec.encode(response)
        noteAuthenticatedSetup()
        return HandledResponse(
            RtspMessage.Response(
                headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                body = body,
            ),
        )
        } finally {
            if (!handlerReceivedDecodedData) {
                // Rejected/malformed SETUP never transferred any decoded Data to a media
                // handler, including streams that was not an array or had non-map elements.
                decoded.release()
                request.body.fill(0)
            } else if (containsMainBuffered) {
                // Only type-103 shk identities are erased. Other decoded Data may already be
                // owned by ordinary media handlers; a shared plist reference was detached
                // before dispatch so their alias remains valid.
                decoded.wipeSelectedByIdentity(sensitiveShk)
                request.body.fill(0)
                decoded.detachWithoutWipe()
            } else {
                decoded.detachWithoutWipe()
            }
        }
    }

    /** Preserve ordinary stream ownership when a plist object aliases type-103 raw shk Data. */
    private fun detachOtherStreamSecretAliases(
        streams: List<*>,
        sensitiveShk: List<ByteArray>,
    ): List<Any?> {
        if (sensitiveShk.isEmpty()) return streams.map { it }
        val aliases = IdentityHashMap<ByteArray, Unit>()
        sensitiveShk.forEach { aliases[it] = Unit }

        fun detach(value: Any?): Any? = when (value) {
            is ByteArray -> if (aliases.containsKey(value)) value.copyOf() else value
            is List<*> -> value.map(::detach)
            is Map<*, *> -> LinkedHashMap<Any?, Any?>(value.size).apply {
                value.forEach { (key, entry) -> put(key, detach(entry)) }
            }
            else -> value
        }

        return streams.map { entry ->
            val stream = asMap(entry)
            if (stream == null || long(stream["type"]) == STREAM_TYPE_MAIN_BUFFERED_AUDIO.toLong() ||
                "shk" in stream
            ) entry else detach(entry)
        }
    }

    private fun handleStreams(streams: List<*>): PreparedStreamBatch =
        handleStreams(streams) {}

    private fun handleStreams(
        streams: List<*>,
        onDecodedDataDispatch: () -> Unit,
    ): PreparedStreamBatch {
        val result = arrayListOf<Any?>()
        val prepared = arrayListOf<AirPlayPreparedMediaStream>()
        val identities = linkedSetOf<AirPlayStreamIdentity>()
        try {
            for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            val streamIdentity = AirPlayStreamIdentity.from(type, stream)
            if (!identities.add(streamIdentity)) {
                debugLog("airplay SETUP duplicate stream identity rejected identity=$streamIdentity")
                if (type == STREAM_TYPE_MAIN_BUFFERED_AUDIO) {
                    throw IllegalArgumentException("type-103 SETUP batch repeats a stream identity")
                }
                continue
            }
            if (streamIdentity in activeStreams) {
                debugLog("airplay SETUP already-active stream identity rejected identity=$streamIdentity")
                if (type == STREAM_TYPE_MAIN_BUFFERED_AUDIO) {
                    throw IllegalStateException("type-103 SETUP stream is already active")
                }
                continue
            }
            if (activeStreams.size + prepared.size >= MAX_SESSION_STREAMS) {
                debugLog(
                    "airplay SETUP stream rejected: active+pending limit=$MAX_SESSION_STREAMS",
                )
                if (type == STREAM_TYPE_MAIN_BUFFERED_AUDIO) {
                    throw IllegalStateException("type-103 SETUP exceeds stream limit")
                }
                continue
            }
            debugLog(
                "airplay SETUP stream type=$type keys=${stream.keys.sorted()} " +
                    "connectionID=${streamIdentity.streamConnectionId ?: "none"} " +
                    "clientTypeUUID=${streamIdentity.clientTypeUuid ?: "none"}",
            )
            when (type) {
                STREAM_TYPE_MAIN_SCREEN, STREAM_TYPE_ALT_SCREEN -> {
                    if (type == STREAM_TYPE_ALT_SCREEN && !displayControl.acceptsAlternateScreen()) {
                        debugLog("airplay screen stream rejected type=111: altScreen/uiContext not negotiated")
                        continue
                    }
                    onDecodedDataDispatch()
                    val opened = media.prepareScreen(this, streamIdentity, stream)
                    debugLog(
                        "airplay screen stream type=$type " +
                            "dataPort=${opened?.response?.get("dataPort") ?: "rejected"}",
                    )
                    if (opened != null) {
                        prepared += opened
                        result += opened.response
                    }
                }
                STREAM_TYPE_MAIN_AUDIO, STREAM_TYPE_ALT_AUDIO, STREAM_TYPE_MAIN_HIGH_AUDIO -> {
                    onDecodedDataDispatch()
                    val opened = media.prepareAudio(this, streamIdentity, stream)
                    val streamResponse = opened?.response
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (opened != null) {
                        prepared += opened
                        result += streamResponse
                    }
                }
                STREAM_TYPE_MAIN_BUFFERED_AUDIO -> {
                    // The feature is currently never advertised. Keep this future control-session
                    // seam closed unless the exact session has negotiated complete readiness.
                    if (AirPlayFeature.MAIN_BUFFERED !in negotiatedFeatures) {
                        debugLog("airplay main-buffered stream rejected: feature not negotiated")
                        continue
                    }
                    onDecodedDataDispatch()
                    val preparedOwner = media.prepareMainBuffered(this, streamIdentity, stream)
                        ?: throw IllegalStateException(
                            "type-103 SETUP has no admitted M4-bound product owner",
                        )
                    val transaction = preparedOwner.transaction
                    val staged = synchronized(mainBufferedTransactionsLock) {
                        if (mainBufferedTransactions.containsKey(streamIdentity)) {
                            false
                        } else {
                            mainBufferedTransactions[streamIdentity] = transaction
                            true
                        }
                    }
                    if (!staged) {
                        // The preparer returned a second owner for an already-staged identity.
                        // Closing it cannot affect the first, exact transaction.
                        transaction.close()
                        throw IllegalStateException("type-103 SETUP already has an exact owner")
                    }
                    if (!bindMainBufferedM4Reservation(streamIdentity, preparedOwner)) {
                        synchronized(mainBufferedM4LeaseLock) {
                            currentMainBufferedM4Reservation?.tombstoned = true
                        }
                        try {
                            retireMainBufferedTransaction(streamIdentity, transaction)
                        } catch (failure: Throwable) {
                            retirementRequested.set(true)
                            throw failure
                        }
                        throw IllegalStateException(
                            "type-103 SETUP transaction is not bound to its frozen M4 request",
                        )
                    }
                    val response = try {
                        mainBufferedResponseEntry(transaction, streamIdentity)
                    } catch (failure: Throwable) {
                        try {
                            retireMainBufferedTransaction(streamIdentity, transaction)
                        } catch (closeFailure: Throwable) {
                            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                            retirementRequested.set(true)
                        }
                        throw failure
                    }
                    val opened = AirPlayPreparedMediaStream(
                        identity = streamIdentity,
                        response = response,
                        commitHook = {
                            if (isTerminating() ||
                                synchronized(mainBufferedTransactionsLock) {
                                    mainBufferedTransactions[streamIdentity] !== transaction
                                }
                            ) {
                                false
                            } else {
                                transaction.commitAfterResponseFlushed()
                                true
                            }
                        },
                        rollbackHook = {
                            try {
                                retireMainBufferedTransaction(streamIdentity, transaction)
                            } catch (failure: Exception) {
                                // AirPlayPreparedMediaStream catches rollback failures. Retain the
                                // exact owner and ensure the control session reaches close retry.
                                retirementRequested.set(true)
                                throw failure
                            }
                        },
                    )
                    prepared += opened
                    result += response
                }
                STREAM_TYPE_DATA -> {
                    if (!pairVerify.isVerified || cipher == null) {
                        debugLog("airplay data stream rejected: verified encrypted control required")
                        continue
                    }
                    val clientType = streamIdentity.clientTypeUuid
                        ?.let(RcsClientTypes::findByUuid)
                    if (clientType == null) {
                        debugLog(
                            "airplay data stream rejected: unknown clientTypeUUID=" +
                                (streamIdentity.clientTypeUuid ?: "missing"),
                        )
                        continue
                    }
                    val feature = clientType.feature
                    if (feature == null || feature !in negotiatedFeatures) {
                        debugLog(
                            "airplay data stream rejected uuid=${clientType.uuid} " +
                                "reason=feature_not_negotiated",
                        )
                        continue
                    }
                    val clientUuid = requireNotNull(streamIdentity.clientTypeUuid)
                    val clientTypeAlreadyOpen = activeStreams.any {
                        it.type == STREAM_TYPE_DATA && it.clientTypeUuid == clientUuid
                    } || prepared.any {
                        it.identity.type == STREAM_TYPE_DATA &&
                            it.identity.clientTypeUuid == clientUuid
                    }
                    if (clientTypeAlreadyOpen) {
                        debugLog(
                            "airplay data stream rejected uuid=${clientType.uuid} " +
                                "reason=client_type_cardinality",
                        )
                        continue
                    }
                    onDecodedDataDispatch()
                    val opened = media.prepareDataStream(this, streamIdentity, clientType, stream)
                    val streamResponse = opened?.response
                    debugLog(
                        "airplay data stream type=$type uuid=${clientType.uuid} " +
                            "accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (opened != null) {
                        prepared += opened
                        result += streamResponse
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
            }
        } catch (failure: Throwable) {
            prepared.forEach(AirPlayPreparedMediaStream::rollback)
            throw failure
        }
        return PreparedStreamBatch(result, prepared)
    }

    private fun commitSetup(cseq: String, prepared: List<AirPlayPreparedMediaStream>) =
        synchronized(commandDispatchLock) {
            val committed = arrayListOf<AirPlayStreamIdentity>()

            fun teardownCommitted() {
                committed.asReversed().forEach { identity ->
                    if (activeStreams.remove(identity)) teardownCommittedStream(identity)
                }
                committed.clear()
            }

            fun abortForTermination(nextPreparedIndex: Int) {
                prepared.drop(nextPreparedIndex).forEach(AirPlayPreparedMediaStream::rollback)
                teardownCommitted()
                debugLog("airplay SETUP commit aborted cseq=$cseq reason=session_stopping")
            }

            if (isTerminating()) {
                abortForTermination(0)
                return@synchronized
            }
            for ((index, stream) in prepared.withIndex()) {
                // This monitor is shared with stopSession dispatch. Therefore a stop command that
                // has entered STOPPING always wins before the next commit point, including when a
                // response-flush callback from another channel races this batch.
                if (isTerminating()) {
                    abortForTermination(index)
                    return@synchronized
                }
                if (!stream.commit()) {
                    prepared.drop(index + 1).forEach(AirPlayPreparedMediaStream::rollback)
                    teardownCommitted()
                    debugLog("airplay SETUP commit failed cseq=$cseq identity=${stream.identity}")
                    close()
                    return@synchronized
                }
                if (isTerminating()) {
                    teardownCommittedStream(stream.identity)
                    prepared.drop(index + 1).forEach(AirPlayPreparedMediaStream::rollback)
                    teardownCommitted()
                    debugLog("airplay SETUP commit aborted cseq=$cseq reason=session_stopping")
                    return@synchronized
                }
                activeStreams += stream.identity
                committed += stream.identity
                if (stream.identity.type == STREAM_TYPE_MAIN_SCREEN ||
                    stream.identity.type == STREAM_TYPE_ALT_SCREEN
                ) {
                    displayControl.screenReady(stream.identity.type)
                }
            }
            if (isTerminating()) {
                teardownCommitted()
                debugLog("airplay SETUP callback suppressed cseq=$cseq reason=session_stopping")
                return@synchronized
            }
            try {
                // Compatibility callback for older media handlers. Concrete xcertplay streams
                // commit through the exact prepared objects above.
                media.onSetupResponseSent(this)
            } catch (error: Exception) {
                Log.w(TAG, "airplay SETUP response callback failed cseq=$cseq", error)
            }
            debugLog("airplay SETUP committed cseq=$cseq streams=${committed.size}")
        }

    private fun handleCommand(request: RtspMessage.Request): HandledResponse =
        synchronized(commandDispatchLock) {
            if (stopping.get()) {
                return HandledResponse(
                    RtspMessage.Response(status = 409, statusText = "Session Stopping"),
                )
            }
            val body = try {
                asMap(BplistCodec.decode(request.body))
            } catch (_: Exception) {
                null
            } ?: return HandledResponse(RtspMessage.Response(status = 400))
            val type = body["type"] as? String
                ?: return HandledResponse(RtspMessage.Response(status = 400))
            val params = if ("params" in body) {
                asMap(body["params"])
                    ?: return HandledResponse(RtspMessage.Response(status = 400))
            } else {
                emptyMap()
            }

            if (type == STOP_SESSION_COMMAND) {
                return handleStopSessionCommand(body, params)
            }
            try {
                displayControl.receive(type, params)
            } catch (error: IllegalArgumentException) {
                debugLog("airplay display command rejected type=$type reason=${error.message}")
                return HandledResponse(RtspMessage.Response(status = 400))
            }
            debugLog("airplay command type=$type keys=${params.keys.sorted()}")
            if (type == "modesChanged") {
                val resources = (params["resources"] as? List<*>)
                    ?.mapNotNull(::asMap)
                    ?.sortedBy { long(it["resourceID"]) ?: Long.MAX_VALUE }
                    ?.joinToString { resource ->
                        "id=${long(resource["resourceID"])} owner=${long(resource["entity"])} " +
                            "permanent=${long(resource["permanentEntity"])}"
                    }
                debugLog("airplay modesChanged resources=${resources ?: "missing"}")
            }
            if (type == "requestUI") listener.onHostUiRequested(this)
            listener.onCommand(this, type, params)
            return HandledResponse(RtspMessage.Response(status = 200))
        }

    private fun handleStopSessionCommand(
        body: Map<String, Any?>,
        params: Map<String, Any?>,
    ): HandledResponse {
        val sidecar = config.ultra?.sessionManagementInfo
        val reason = params[DISCONNECT_REASON]?.let(::strictCommandInteger)
        val valid = AirPlayFeature.SESSION_MANAGEMENT in negotiatedFeatures &&
            sidecar != null &&
            body.keys == setOf("type", "params") &&
            params.keys == setOf(DISCONNECT_REASON) &&
            reason != null &&
            reason in sidecar.stopSessionReasons
        if (!valid) {
            debugLog(
                "airplay stopSession rejected negotiated=" +
                    (AirPlayFeature.SESSION_MANAGEMENT in negotiatedFeatures) +
                    " keys=${params.keys.sorted()}",
            )
            return HandledResponse(RtspMessage.Response(status = 400))
        }
        if (!stopping.compareAndSet(false, true)) {
            return HandledResponse(
                RtspMessage.Response(status = 409, statusText = "Session Stopping"),
            )
        }
        revokeMainBufferedM4Leases()

        return HandledResponse(
            response = RtspMessage.Response(status = 200),
            onFlushed = {
                // close() is atomic/idempotent. It is deliberately reached only after the
                // successful 200 response flush on either the control or event channel. The
                // command is consumed here rather than delegated to an optional host callback.
                debugLog("airplay command type=$STOP_SESSION_COMMAND disconnectReason=$reason")
                // CarPlayVpnService uses abortive close while a socket is unauthenticated. A
                // successful stop response must instead leave via FIN after its flushed 200.
                runCatching { socket.setSoLinger(false, 0) }
                close()
            },
        )
    }

    private fun handleTeardown(request: RtspMessage.Request): RtspMessage.Response {
        val selectors = try {
            val decoded = BplistCodec.decode(request.body)
            val dict = asMap(decoded)
            (dict?.get("streams") as? List<*>)
                ?.mapNotNull { entry ->
                    val stream = asMap(entry) ?: return@mapNotNull null
                    val type = long(stream["type"])?.toInt() ?: return@mapNotNull null
                    AirPlayStreamIdentity.from(type, stream)
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        debugLog(
            "airplay TEARDOWN selectors=$selectors activeBefore=$activeStreams " +
                "body=${request.body.size} bytes",
        )
        trace("airplay TEARDOWN bodyBytes=${request.body.size} selectors=${selectors.size}")

        val targets = if (selectors.isEmpty()) {
            activeStreams.toList()
        } else {
            selectors.flatMap { selector ->
                if (selector.hasDiscriminator) {
                    activeStreams.filter { it.matches(selector) }
                } else {
                    activeStreams.filter { it.type == selector.type }
                }
            }.distinct()
        }
        if (targets.any { it.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO }) {
            // No mctl command may start after a type-103 teardown, even if port retirement
            // subsequently fails and the exact selector must remain retryable. Preserve the
            // enclosing M4 token: only a proved QUIESCENT child can admit a fresh request.
            targets.filter { it.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO }
                .forEach(::revokeMainBufferedChildForTeardown)
        }
        // A multi-selector request may include type-130. Cover MAIN before any selected handler
        // or decoder is quiesced, while the exact type-110 selector is still active.
        targets.filter { it.type == STREAM_TYPE_MAIN_SCREEN && it in activeStreams }
            .forEach { identity ->
                val covered = runCatching {
                    media.beforeScreenTeardown(this, identity)
                }.getOrDefault(false)
                if (!covered) {
                    return RtspMessage.Response(status = 503, statusText = "Display Cover Pending")
                }
            }
        // Ultra owns independent listeners and handler state. Tear those down first so a CAF/UI
        // callback can never race ordinary screen or audio disposal.
        targets.sortedByDescending { it.type == STREAM_TYPE_DATA }.forEach { identity ->
            if (identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO && identity in activeStreams) {
                // Retire the separate authenticated TCP ingress before releasing its decoder.
                // A failed close retains both the transaction and active selector for retry.
                if (runCatching { retireMainBufferedTransaction(identity) }.isFailure) {
                    synchronized(mainBufferedM4LeaseLock) {
                        currentMainBufferedM4Reservation?.tombstoned = true
                    }
                    return RtspMessage.Response(status = 503, statusText = "Stream Retirement Pending")
                }
            }
            if (identity !in activeStreams) return@forEach
            // A failed decoder/renderer retirement keeps the exact selector retryable on this
            // control session. The independent MAIN cover has already ACKed above.
            if (runCatching { media.onTeardown(this, identity) }.isFailure) {
                if (identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO) {
                    synchronized(mainBufferedM4LeaseLock) {
                        currentMainBufferedM4Reservation?.tombstoned = true
                    }
                }
                return RtspMessage.Response(status = 503, statusText = "Stream Retirement Pending")
            }
            if (identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO &&
                hasBoundMainBufferedReservation(identity)
            ) {
                if (!completeMainBufferedQuiescence(identity)) {
                    // Preserve this exact selector and old owner. A known outstanding borrow
                    // may return later; an ambiguous close remains tombstoned from above.
                    return RtspMessage.Response(status = 503, statusText = "Stream Retirement Pending")
                }
            } else {
                activeStreams.remove(identity)
            }
            if (activeStreams.none { it.type == identity.type }) {
                displayControl.screenClosed(identity.type)
            }
        }
        return RtspMessage.Response(status = 200)
    }

    private fun mainBufferedResponseEntry(
        transaction: MainBufferedV27SetupTransaction,
        identity: AirPlayStreamIdentity,
    ): Map<String, Any?> {
        val entries = transaction.responseBody["streams"] as? List<*>
        require(entries?.size == 1) { "type-103 transaction must respond with exactly one stream" }
        val entry = asMap(entries.single())
            ?: throw IllegalArgumentException("type-103 transaction response is not a dictionary")
        require(long(entry["type"]) == STREAM_TYPE_MAIN_BUFFERED_AUDIO.toLong() &&
            AirPlayStreamIdentity.from(STREAM_TYPE_MAIN_BUFFERED_AUDIO, entry) == identity
        ) { "type-103 transaction response identity changed" }
        return entry
    }

    private fun retireMainBufferedTransaction(
        identity: AirPlayStreamIdentity,
        expected: MainBufferedV27SetupTransaction? = null,
    ) {
        val owner = synchronized(mainBufferedTransactionsLock) {
            mainBufferedTransactions[identity]?.takeIf { expected == null || it === expected }
        } ?: return
        owner.close()
        synchronized(mainBufferedTransactionsLock) {
            if (mainBufferedTransactions[identity] === owner) mainBufferedTransactions.remove(identity)
        }
    }

    private fun retireMainBufferedTransactions() {
        val staged = synchronized(mainBufferedTransactionsLock) {
            mainBufferedTransactions.keys.toList()
        }
        var failure: Exception? = null
        staged.forEach { identity ->
            try {
                retireMainBufferedTransaction(identity)
            } catch (error: Exception) {
                failure = combineCloseFailure(failure, error)
            }
        }
        failure?.let { throw it }
    }

    private fun teardownCommittedStream(identity: AirPlayStreamIdentity) {
        if (identity.type == STREAM_TYPE_MAIN_BUFFERED_AUDIO) {
            retireMainBufferedTransaction(identity)
        }
        media.onTeardown(this, identity)
    }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen()
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        val socket = keepAliveSocketFactory()
        val owner = KeepAliveOwner(socket)
        synchronized(retirementLock) {
            ownedKeepAlive += owner
        }
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            val thread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
                isDaemon = true
            }
            synchronized(retirementLock) {
                if (isTerminating()) {
                    throw IOException("AirPlay session is terminating")
                }
                owner.thread = thread
                keepAliveSocket = socket
                keepAliveThread = thread
                thread.start()
            }
            return socket.localPort
        } catch (failure: Throwable) {
            try {
                retireFailedKeepAliveOwner(owner)
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun retireFailedKeepAliveOwner(owner: KeepAliveOwner) {
        synchronized(retirementLock) {
            owner.socket.close()
            owner.socketRetired = true
            if (keepAliveSocket === owner.socket) keepAliveSocket = null
            val worker = owner.thread
            if (worker == null || !worker.isAlive) {
                ownedKeepAlive.removeIdentity(owner)
                if (keepAliveThread === worker) keepAliveThread = null
            }
        }
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!isRetirementRequested()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (isRetirementRequested()) return
            }
        }
    }

    private fun openEvent(): Int {
        val bindAddress = localAddress?.takeUnless(InetAddress::isAnyLocalAddress)
            ?: throw IOException("AirPlay event channel requires an exact local address")
        val server = eventServerFactory()
        synchronized(retirementLock) {
            ownedEventServers.addIdentity(server)
        }
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(bindAddress, 0), MAX_EVENT_CONNECTION_ATTEMPTS)
            synchronized(retirementLock) {
                if (isTerminating()) {
                    throw IOException("AirPlay session is terminating")
                }
                eventServer = server
                spawnEventLocked("airplay-event-accept") { acceptEvent(server) }
            }
            return server.localPort
        } catch (failure: Throwable) {
            try {
                retireFailedEventServer(server)
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun retireFailedEventServer(owner: ServerSocket) {
        synchronized(retirementLock) {
            owner.close()
            ownedEventServers.removeIdentity(owner)
            if (eventServer === owner) eventServer = null
        }
    }

    /**
     * Retires every session-owned transport and worker without losing a failed owner.
     *
     * The second transport pass is intentional: a SETUP that was already creating a listener
     * when STOPPING won may register it before its control worker exits. Joining the workers first
     * closes that registration window; the second pass then retires the exact late owner.
     */
    private fun retireOwnedResources() {
        var failure: Exception? = null

        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Exception) {
                failure = combineCloseFailure(failure, error)
            }
        }

        attempt(::closeControlSocket)
        attempt(::closeTrackedTransportSockets)
        attempt(::closeNtp)
        attempt(::joinOwnedWorkers)
        attempt(::closeTrackedTransportSockets)
        attempt(::closeNtp)

        val unretired = synchronized(retirementLock) {
            buildList {
                if (!controlSocketRetired) add("control socket")
                if (!ntpRetired) add("NTP clock")
                if (controlThread != null) add("control worker")
                if (ownedEventServers.isNotEmpty()) add("event server")
                if (ownedEventSockets.isNotEmpty()) add("event socket")
                if (eventThreads.isNotEmpty()) add("event worker")
                if (ownedKeepAlive.isNotEmpty()) add("keepalive socket/worker")
            }
        }
        if (unretired.isNotEmpty()) {
            failure = combineCloseFailure(
                failure,
                IOException("AirPlay retirement remains unproven: ${unretired.joinToString()}"),
            )
        }
        failure?.let { throw it }
    }

    private fun retireSessionSecrets() {
        // STOPPING blocks new sends, transport close waited out any eventWriteLock holder, and
        // retireOwnedResources joined all directly owned workers before this point.
        val activeEventCipher = synchronized(eventWriteLock) {
            eventCipher.also {
                eventCipher = null
                eventOutboundNonceUsed = null
            }
        }
        synchronized(retirementLock) {
            cipher?.close()
            cipher = null
            activeEventCipher?.close()
            ownedEventCiphers.forEach(ControlCipher::close)
            ownedEventCiphers.clear()
        }
        pairVerify.finishSessionSecretsRetirement()
        synchronized(pairTlsPskLeaseLock) {
            authenticatedPairVerifyGeneration = null
            authenticatedControl.set(false)
        }
        encBuf.fill(0)
        encBuf = ByteArray(0)
    }

    private fun closeControlSocket() {
        synchronized(retirementLock) {
            if (controlSocketRetired) return
            socket.close()
            controlSocketRetired = true
        }
    }

    private fun closeNtp() {
        synchronized(retirementLock) {
            if (ntpRetired) return
            ntp.close()
            ntpRetired = true
        }
    }

    private fun closeTrackedTransportSockets() {
        var failure: Exception? = null
        synchronized(retirementLock) {
            ownedEventServers.toList().forEach { owner ->
                try {
                    owner.close()
                    ownedEventServers.removeIdentity(owner)
                    if (eventServer === owner) eventServer = null
                } catch (error: Exception) {
                    failure = combineCloseFailure(failure, error)
                }
            }
            ownedEventSockets.toList().forEach { owner ->
                try {
                    owner.close()
                    ownedEventSockets.removeIdentity(owner)
                    synchronized(eventWriteLock) {
                        if (eventSocket === owner) {
                            eventSocket = null
                            eventCipher = null
                        }
                    }
                } catch (error: Exception) {
                    failure = combineCloseFailure(failure, error)
                }
            }
            ownedKeepAlive.toList().forEach { owner ->
                if (!owner.socketRetired) {
                    try {
                        owner.socket.close()
                        owner.socketRetired = true
                        if (keepAliveSocket === owner.socket) keepAliveSocket = null
                    } catch (error: Exception) {
                        failure = combineCloseFailure(failure, error)
                    }
                }
                if (owner.socketRetired && owner.thread?.isAlive != true) {
                    ownedKeepAlive.removeIdentity(owner)
                    if (keepAliveThread === owner.thread) keepAliveThread = null
                }
            }
        }
        failure?.let { throw it }
    }

    private fun joinOwnedWorkers() {
        val current = Thread.currentThread()
        val workers = ownedWorkersSnapshot()
        val gracefulDeadlineNanos = System.nanoTime() +
            retirementJoinTimeoutMillis * NANOS_PER_MILLISECOND
        var failure: Exception? = null

        // Closing the owned sockets is normally sufficient and lets an in-flight SETUP finish its
        // rollback hook. Only workers that miss the bounded graceful deadline are interrupted.
        for (thread in workers) {
            if (thread === current) {
                failure = combineCloseFailure(
                    failure,
                    IOException("AirPlay worker cannot prove its own retirement: ${thread.name}"),
                )
                continue
            }
            if (thread.state != Thread.State.NEW && thread.isAlive) {
                val remainingNanos = gracefulDeadlineNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    val millis = (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                    try {
                        thread.join(millis)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failure = combineCloseFailure(
                            failure,
                            IOException("Interrupted while retiring ${thread.name}", error),
                        )
                    }
                }
            }
        }

        val survivors = workers.filter { it !== current && it.isAlive }
        survivors.forEach(Thread::interrupt)
        val interruptDeadlineNanos = System.nanoTime() +
            minOf(retirementJoinTimeoutMillis, INTERRUPTED_WORKER_JOIN_TIMEOUT_MILLIS) *
            NANOS_PER_MILLISECOND
        for (thread in workers) {
            if (thread !== current && thread.isAlive) {
                val remainingNanos = interruptDeadlineNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    try {
                        thread.join(
                            (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                        )
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failure = combineCloseFailure(
                            failure,
                            IOException("Interrupted while retiring ${thread.name}", error),
                        )
                    }
                }
            }
            if (thread.isAlive) {
                failure = combineCloseFailure(
                    failure,
                    IOException("Timed out retiring AirPlay worker ${thread.name}"),
                )
            } else {
                forgetTerminatedWorker(thread)
            }
        }
        failure?.let { throw it }
    }

    private fun ownedWorkersSnapshot(): List<Thread> = synchronized(retirementLock) {
        buildList {
            controlThread?.let(::add)
            eventThreads.forEach { candidate ->
                if (none { it === candidate }) add(candidate)
            }
            ownedKeepAlive.forEach { owner ->
                owner.thread?.let { candidate ->
                    if (none { it === candidate }) add(candidate)
                }
            }
        }
    }

    private fun forgetTerminatedWorker(thread: Thread) = synchronized(retirementLock) {
        if (controlThread === thread) controlThread = null
        eventThreads.removeIf { it === thread }
        ownedKeepAlive.toList().forEach { owner ->
            if (owner.thread === thread && owner.socketRetired) {
                ownedKeepAlive.removeIdentity(owner)
                if (keepAliveThread === thread) keepAliveThread = null
            }
        }
    }

    private fun isOwnedWorker(thread: Thread): Boolean = synchronized(retirementLock) {
        controlThread === thread ||
            eventThreads.any { it === thread } ||
            ownedKeepAlive.any { it.thread === thread }
    }

    private fun scheduleDeferredRetirement(caller: Thread) {
        val coordinator = synchronized(retirementLock) {
            deferredRetirementThread
                ?.takeUnless { it.state == Thread.State.TERMINATED }
                ?.let { return }
            Thread(
                {
                    var retryDelayMillis = DEFERRED_RETIREMENT_INITIAL_RETRY_MILLIS
                    try {
                        while (!closed.get()) {
                            try {
                                close()
                            } catch (error: Throwable) {
                                runCatching {
                                    Log.w(
                                        TAG,
                                        "deferred AirPlay retirement retry after ${caller.name}",
                                        error,
                                    )
                                }
                            }
                            if (closed.get()) return@Thread
                            try {
                                Thread.sleep(retryDelayMillis)
                            } catch (_: InterruptedException) {
                                // An interruption only accelerates the next bounded retry.
                            }
                            retryDelayMillis =
                                (retryDelayMillis * 2).coerceAtMost(
                                    DEFERRED_RETIREMENT_MAX_RETRY_MILLIS,
                                )
                        }
                    } finally {
                        synchronized(retirementLock) {
                            if (deferredRetirementThread === Thread.currentThread()) {
                                deferredRetirementThread = null
                            }
                        }
                    }
                },
                "airplay-retirement",
            ).apply { isDaemon = true }.also { deferredRetirementThread = it }
        }
        try {
            coordinator.start()
        } catch (failure: Throwable) {
            synchronized(retirementLock) {
                if (deferredRetirementThread === coordinator) deferredRetirementThread = null
            }
            throw failure
        }
    }

    private fun closeEventSocketOrRequestRetirement(
        owner: Socket,
        cause: Exception? = null,
    ) {
        var closeFailure: Exception? = cause
        try {
            synchronized(retirementLock) {
                if (ownedEventSockets.any { it === owner }) {
                    owner.close()
                    ownedEventSockets.removeIdentity(owner)
                }
                synchronized(eventWriteLock) {
                    if (eventSocket === owner) {
                        eventSocket = null
                        eventCipher = null
                    }
                }
            }
        } catch (error: Exception) {
            closeFailure = combineCloseFailure(closeFailure, error)
        }
        closeFailure?.let { error ->
            runCatching { Log.w(TAG, "AirPlay event owner requires retirement retry", error) }
            close()
        }
    }

    private fun acceptEvent(server: ServerSocket) {
        var attempts = 0
        while (!isRetirementRequested() && attempts < MAX_EVENT_CONNECTION_ATTEMPTS) {
            val candidate = try {
                server.accept()
            } catch (error: Exception) {
                if (!isRetirementRequested()) {
                    runCatching { Log.e(TAG, "airplay event accept failed", error) }
                }
                return
            }
            synchronized(retirementLock) {
                ownedEventSockets.addIdentity(candidate)
            }
            if (isTerminating()) {
                closeEventSocketOrRequestRetirement(candidate)
                return
            }
            attempts++
            try {
                candidate.setSoLinger(true, 0)
                candidate.tcpNoDelay = true
            } catch (error: Exception) {
                closeEventSocketOrRequestRetirement(candidate, error)
                return
            }

            val candidatePeer = (candidate.remoteSocketAddress as? InetSocketAddress)?.address
            val expectedPeer = peerAddress
            if (candidatePeer == null || expectedPeer == null ||
                !candidatePeer.address.contentEquals(expectedPeer.address)
            ) {
                debugLog(
                    "airplay event candidate rejected reason=foreign_peer " +
                        "attempt=$attempts/$MAX_EVENT_CONNECTION_ATTEMPTS",
                )
                closeEventSocketOrRequestRetirement(candidate)
                continue
            }

            val shared = pairVerify.shared
            if (!pairVerify.isVerified || shared == null) {
                debugLog(
                    "airplay event candidate rejected reason=pair_verify_incomplete " +
                        "attempt=$attempts/$MAX_EVENT_CONNECTION_ATTEMPTS",
                )
                closeEventSocketOrRequestRetirement(candidate)
                continue
            }
            var writeKey: ByteArray? = null
            var readKey: ByteArray? = null
            val candidateCipher = try {
                writeKey = AirPlayCrypto.hkdfSha512(
                    shared,
                    "Events-Salt".asciiBytes(),
                    "Events-Write-Encryption-Key".asciiBytes(),
                    32,
                )
                readKey = AirPlayCrypto.hkdfSha512(
                    shared,
                    "Events-Salt".asciiBytes(),
                    "Events-Read-Encryption-Key".asciiBytes(),
                    32,
                )
                ControlCipher(readKey, writeKey)
            } finally {
                shared.fill(0)
                readKey?.fill(0)
                writeKey?.fill(0)
            }
            synchronized(retirementLock) {
                ownedEventCiphers.addIdentity(candidateCipher)
            }
            try {
                // The phone may wait for the accessory's first iAP command before sending any
                // event frame. The exact peer and completed pair-verify admit accessory writes.
                candidate.soTimeout = 0
                candidate.setSoLinger(false, 0)
            } catch (error: Exception) {
                closeEventSocketOrRequestRetirement(candidate, error)
                return
            }
            val outboundNonceUsed = AtomicBoolean(false)
            val inboundAuthenticated = AtomicBoolean(false)
            var admitted = false
            try {
                synchronized(eventWriteLock) {
                    if (!isRetirementRequested()) {
                        eventSocket = candidate
                        eventCipher = candidateCipher
                        eventOutboundNonceUsed = outboundNonceUsed
                        admitted = true
                        sendPendingNightModeLocked()
                    }
                }
                if (admitted) {
                    debugLog(
                        "airplay event candidate admitted peer=${candidate.remoteSocketAddress} " +
                            "attempt=$attempts/$MAX_EVENT_CONNECTION_ATTEMPTS",
                    )
                    displayControl.flush()
                    runEventRead(
                        candidate,
                        candidateCipher,
                        inboundAuthenticated,
                        outboundNonceUsed,
                    )
                }
            } catch (error: Exception) {
                if (!isRetirementRequested()) {
                    runCatching { Log.e(TAG, "airplay event candidate failed", error) }
                }
            } finally {
                synchronized(eventWriteLock) {
                    if (eventSocket === candidate) {
                        eventSocket = null
                        eventCipher = null
                    }
                    if (eventOutboundNonceUsed === outboundNonceUsed) {
                        eventOutboundNonceUsed = null
                    }
                }
                closeEventSocketOrRequestRetirement(candidate)
            }
            if (!admitted) return
            debugLog(
                "airplay event candidate ended authenticated=${inboundAuthenticated.get()} " +
                    "outboundUsed=${outboundNonceUsed.get()} " +
                    "attempt=$attempts/$MAX_EVENT_CONNECTION_ATTEMPTS",
            )
            if ((inboundAuthenticated.get() || outboundNonceUsed.get()) &&
                !isRetirementRequested()
            ) {
                // Any authenticated input or attempted output consumes a nonce. Reconnect needs
                // a fresh pair-verify exchange rather than another cipher starting at nonce zero.
                debugLog("airplay used event channel ended; closing control session")
                close()
                return
            }
        }
        if (!isRetirementRequested()) {
            debugLog("airplay event candidate limit exhausted")
            close()
        }
    }

    private fun runEventRead(
        socket: Socket,
        cipher: ControlCipher,
        inboundAuthenticated: AtomicBoolean,
        outboundNonceUsed: AtomicBoolean,
    ) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!isRetirementRequested()) {
                val count = input.read(buffer)
                if (count < 0) break
                encrypted = appendBounded(encrypted, buffer.copyOf(count), "encrypted event")
                var encryptedOffset = 0
                while (true) {
                    val decrypted = try {
                        cipher.decryptFrame(encrypted, encryptedOffset)
                    } catch (error: Exception) {
                        runCatching {
                            Log.e(
                                TAG,
                                "airplay event decrypt failed authenticated=${inboundAuthenticated.get()} " +
                                    "encrypted=${encrypted.size}",
                                error,
                            )
                        }
                        // If an earlier frame in this same TCP read authenticated, returning true
                        // is mandatory: acceptEvent must terminate the whole session instead of
                        // deriving the same keys again and reusing nonce zero on a reconnect.
                        throw error
                    }
                    if (decrypted == null) break
                    encryptedOffset += decrypted.consumedBytes
                    inboundAuthenticated.set(true)
                    plaintext = appendBounded(plaintext, decrypted.data, "event request")
                    val parsed = RtspMessage.parseMessages(plaintext)
                    plaintext = parsed.rest
                    for (message in parsed.messages) {
                        if (message.method.startsWith("RTSP/") ||
                            message.method.startsWith("HTTP/")
                        ) {
                            continue
                        }
                        debugLog(
                            "airplay event rx ${message.method} ${message.path} " +
                                "cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}",
                        )
                        displayControl.responseStarted()
                        val handled = if (
                            message.method == "POST" &&
                            message.path.lowercase().endsWith("/command")
                        ) {
                            handleCommand(message)
                        } else {
                            HandledResponse(RtspMessage.Response(status = 200))
                        }
                        val result = handled.response
                        val response = RtspMessage.buildResponse(message, result)
                        trace(
                            "airplay event rx method=${message.method} path=${message.path} " +
                                "cseq=${message.headers["cseq"] ?: "-"} " +
                                "bodyBytes=${message.body.size}",
                        )
                        trace(
                            "airplay event tx status=${result.status ?: 200} " +
                                "wireBytes=${response.size}",
                        )
                        try {
                            synchronized(eventWriteLock) {
                                outboundNonceUsed.set(true)
                                output.write(cipher.encrypt(response))
                                output.flush()
                            }
                            handled.responseFlushed()
                        } catch (error: Throwable) {
                            handled.responseFailed()
                            throw error
                        }
                        displayControl.responseSent()
                        if (isRetirementRequested()) return
                    }
                }
                if (encryptedOffset > 0) {
                    encrypted = encrypted.copyOfRange(encryptedOffset, encrypted.size)
                }
            }
        } catch (error: Exception) {
            if (!isRetirementRequested()) {
                runCatching { Log.e(TAG, "airplay event read failed", error) }
            }
        } finally {
            debugLog("airplay event connection closed")
        }
    }

    private fun isTerminating(): Boolean = stopping.get() || closed.get()

    private fun isRetirementRequested(): Boolean = retirementRequested.get() || closed.get()

    private fun checkPreAuthDeadline() {
        if (cipher == null &&
            monotonicNanos() - preAuthStartedNanos > MAX_PRE_AUTH_LIFETIME_NANOS
        ) {
            throw SocketTimeoutException("AirPlay pre-authentication lifetime exceeded")
        }
    }

    private fun checkInfoOnlyReservationDeadline() {
        val deadline = infoOnlyReservationDeadlineNanos ?: return
        if (monotonicNanos() - deadline >= 0) {
            throw SocketTimeoutException("AirPlay info-only file-transfer reservation expired")
        }
    }

    private fun appendBounded(current: ByteArray, next: ByteArray, label: String): ByteArray {
        if (current.size > MAX_CONTROL_BUFFER_BYTES - next.size) {
            throw IOException("AirPlay $label exceeds $MAX_CONTROL_BUFFER_BYTES bytes")
        }
        return current + next
    }

    /** Caller holds [retirementLock]. */
    private fun spawnEventLocked(name: String, body: () -> Unit) {
        val thread = Thread(
            {
                try {
                    body()
                } catch (_: DeferredRetirementException) {
                    // The retained coordinator completes retirement after this worker exits.
                } catch (failure: Exception) {
                    runCatching { Log.w(TAG, "airplay $name worker ended with failure", failure) }
                }
            },
            name,
        ).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private class DeferredRetirementException(message: String) : IOException(message) {
        constructor(worker: Thread) : this(
            "AirPlay session retirement was deferred until worker ${worker.name} exits",
        )
    }

    private companion object {
        private const val MAX_MAIN_BUFFERED_STREAM_ID = 0xffff_ffffL
        /** Bounds all three per-M4 replay ledgers even on a long-lived paired control socket. */
        private const val MAX_MAIN_BUFFERED_M4_CHILDREN_PER_EPOCH = 256L
        private val nextMainBufferedStreamId = AtomicLong(1L)
        const val TAG = "xcertplay-usb"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_MAIN_BUFFERED_AUDIO = 103
        const val STREAM_TYPE_DATA = 130

        const val READ_CHUNK_BYTES = 16 * 1024
        const val MAX_CONTROL_BUFFER_BYTES = 8 * 1024 * 1024
        const val MAX_SETUP_STREAMS = 16
        const val MAX_SESSION_STREAMS = 16
        const val MAX_EVENT_CONNECTION_ATTEMPTS = 8
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DEFAULT_RETIREMENT_JOIN_TIMEOUT_MILLIS = 4_000L
        const val MAX_PRE_AUTH_LIFETIME_NANOS = 180_000_000_000L
        const val DEFAULT_INFO_ONLY_RESERVATION_LIFETIME_MILLIS = 180_000L
        const val MAX_INFO_ONLY_RESERVATION_LIFETIME_MILLIS = 300_000L
        const val INFO_ONLY_RESERVATION_POLL_MILLIS = 30_000
        const val INTERRUPTED_WORKER_JOIN_TIMEOUT_MILLIS = 250L
        const val DEFERRED_RETIREMENT_INITIAL_RETRY_MILLIS = 25L
        const val DEFERRED_RETIREMENT_MAX_RETRY_MILLIS = 1_000L
        const val STOP_SESSION_COMMAND = "stopSession"
        const val DISCONNECT_REASON = "disconnectReason"
    }
}

private fun combineCloseFailure(first: Exception?, next: Exception): Exception {
    if (first == null) return next
    if (first !== next && first.suppressed.size < 8) first.addSuppressed(next)
    return first
}

private fun <T : Any> MutableList<T>.addIdentity(value: T) {
    if (none { it === value }) add(value)
}

private fun <T : Any> MutableList<T>.removeIdentity(value: T) {
    val index = indexOfFirst { it === value }
    if (index >= 0) removeAt(index)
}

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}


private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()

private fun strictCommandInteger(value: Any?): Long? = when (value) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    else -> null
}
