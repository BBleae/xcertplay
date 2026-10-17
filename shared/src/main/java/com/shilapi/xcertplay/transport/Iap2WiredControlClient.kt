package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2AppLinksLifecycle
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconResponse
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksMessages
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksUpdate
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAcquisitionIntent
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetCatalog
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetKey
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationAttempt
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

internal fun resolveLiveWiredControlEndpoint(
    initial: Iap2WiredCarPlayEndpoint,
    current: () -> Iap2WiredCarPlayEndpoint?,
): Iap2WiredCarPlayEndpoint {
    val live = current() ?: throw IOException("Wired NCM/AirPlay endpoint retired before 0x4301")
    if (
        live.ipv6Addresses != initial.ipv6Addresses || live.airPlayPort != initial.airPlayPort ||
        live.deviceIdentifier != initial.deviceIdentifier || live.publicKey != initial.publicKey ||
        live.sourceVersion != initial.sourceVersion
    ) {
        throw IOException("Wired endpoint identity changed before 0x4301")
    }
    return live
}

internal data class Iap2CarPlayStartSessionReply(
    val frame: Iap2Frame,
    val acquisitionRequested: Boolean,
)

internal fun wiredStartSessionForControlSend(
    initial: Iap2WiredCarPlayEndpoint,
    current: () -> Iap2WiredCarPlayEndpoint?,
    availabilityPayload: ByteArray,
    authenticationIdentity: MfiAuthenticationIdentity,
    allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean,
): Iap2CarPlayStartSessionReply {
    val live = resolveLiveWiredControlEndpoint(initial, current)
    return Iap2CarPlayStartSessionReply(
        frame = Iap2WiredControlClient.carPlayStartSession(
            live,
            availabilityPayload,
            authenticationIdentity,
            allowClusterAssetAcquisition,
        ),
        acquisitionRequested = live.clusterAssetAcquisitionIntent != null,
    )
}

/** Reports the actual encoded acquisition fields, never an intended asset ID or SDK version. */
internal fun clusterAssetAcquisitionSendMarker(
    frame: Iap2Frame,
    acquisitionRequested: Boolean,
): String {
    val ids = Iap2ParameterList.parse(frame.payload).asList().map { it.id }.toSet()
    val included = acquisitionRequested && 6 in ids && 7 in ids
    return "iap2 tx=0x4301 cluster-acquisition=${if (included) "present" else "absent"}"
}

/**
 * The wired LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, power announcement, five update subscriptions, then CarPlay availability.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-availability CSM frame to [onIncoming]; it neither opens NCM nor implements
 * an AirPlay receiver.
 */
class Iap2WiredControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    @Volatile private var appLinksLifecycle: Iap2AppLinksLifecycle? = null
    @Volatile private var appLinksIconChannel: Iap2AppLinksIconChannel? = null

    /** Explicit Host opt-in; no AppLinks Start is emitted by [run] itself. */
    fun startAppLinks(categoryIds: Set<Int>, preferredIconSize: Int? = null): Boolean =
        appLinksLifecycle?.start(categoryIds, preferredIconSize) ?: false

    fun stopAppLinks(): Boolean {
        val lifecycle = appLinksLifecycle ?: return false
        return try {
            lifecycle.stop()
        } finally {
            appLinksIconChannel?.takeIf { it.lifecycle === lifecycle }?.onStopped()
        }
    }

    /** One authenticated AD03 request per owned CSM; no automatic icon request or retry. */
    fun requestAppLinksIcons(
        carPlayBundleIdentifiers: Set<String>,
        externalAccessoryBundleIdentifiers: Set<String> = emptySet(),
    ): Boolean = appLinksIconChannel?.request(carPlayBundleIdentifiers, externalAccessoryBundleIdentifiers) ?: false

    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WiredCarPlayEndpoint,
        availableCurrentMilliAmps: Int,
        authenticationAttempt: MfiAuthenticationAttempt,
        bringUpTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        vehicleStatusProvider: VehicleStatusProvider? = null,
        onReady: () -> Unit = {},
        onStopped: () -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        onAuthenticated: (MfiAuthenticationReceipt) -> Unit = {},
        onAppLinksUpdate: (Iap2AppLinksUpdate) -> Unit = {},
        onAppLinksIconResponse: (Iap2AppLinksIconResponse) -> Unit = {},
        /** Re-resolve the exact NCM/AirPlay attachment after AA05 and at every 0x4300. */
        endpointForControlSend: () -> Iap2WiredCarPlayEndpoint?,
        /** A signed-profile check evaluated immediately before each 0x4301 is constructed. */
        allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean = { _, _ -> false },
        debugAssetAcquisitionMarker: Boolean = false,
    ): Iap2WiredControlResult {
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(bringUpTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "bringUpTimeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS"
        }

        val vehicleReporter = Iap2VehicleStatusReporter(vehicleStatusProvider,
            Iap2VehicleStatus.capabilities(vehicleStatusProvider?.awaitSnapshot(2_000)))
        val locationComponents = locationProvider?.supportedComponents().orEmpty()
        val controlIdentification = identification.copy(
            vehicleStatusCapabilities = vehicleReporter.advertised,
            locationInformationEnabled = locationComponents.isNotEmpty(),
            gpsLocationEnabled = locationComponents.any { it == 1 || it == 2 },
            vehicleSpeedEnabled = 4 in locationComponents,
        )
        val bringUpDeadlineNanos = deadlineAfter(bringUpTimeoutMillis)
        Iap2IdentificationClient(session).identify(
            controlIdentification,
            requireRemaining(bringUpDeadlineNanos),
        )
        onProgress("iap2 identification accepted")
        var stage = Iap2WiredControlStage.IDENTIFIED
        val authenticationReceipt = mfi.run(
            session,
            authenticationAttempt,
            requireRemaining(bringUpDeadlineNanos),
            onProgress,
        )
        stage = Iap2WiredControlStage.AUTHENTICATED
        onAuthenticated(authenticationReceipt)
        onProgress("iap2 authentication accepted")

        sendDuringBringUp(powerSourceUpdate(availableCurrentMilliAmps), bringUpDeadlineNanos)
        for (subscription in subscriptions()) {
            sendDuringBringUp(subscription, bringUpDeadlineNanos)
        }
        stage = Iap2WiredControlStage.SUBSCRIBED
        onProgress("iap2 power/subscriptions sent")

        var forwardedFrames = 0
        var carPlayStartSessions = 0
        val locationSubscription = Iap2LocationSubscription()
        var locationActive = false
        var locationSentLogged = false
        var hidStarted = false
        var ownedAppLinks: Iap2AppLinksLifecycle? = null
        var ownedAppLinksIcons: Iap2AppLinksIconChannel? = null
        try {
            sendDuringBringUp(
                Iap2HidMessages.startMediaPlaybackRemote(
                    identification.hidVendorIdentifier,
                    identification.hidProductIdentifier,
                ),
                bringUpDeadlineNanos,
            )
            hidStarted = true
            onProgress("iap2 tx=0x6800 start media playback remote")
            if (controlIdentification.appLinksEnabled) {
                ownedAppLinks = Iap2AppLinksLifecycle(
                    send = ::sendDuringControl,
                    isAuthorized = {
                        !session.isClosed &&
                            (authenticationReceipt.isCurrentAuthenticationAttempt() ||
                                authenticationReceipt.isLiveForUltraActivation())
                    },
                )
                appLinksLifecycle = ownedAppLinks
                if (controlIdentification.appLinksIconsEnabled) {
                    val lifecycle = requireNotNull(ownedAppLinks)
                    ownedAppLinksIcons = Iap2AppLinksIconChannel(
                        lifecycle = lifecycle,
                        send = ::sendDuringControl,
                        isAuthorized = {
                            !session.isClosed &&
                                (authenticationReceipt.isCurrentAuthenticationAttempt() ||
                                    authenticationReceipt.isLiveForUltraActivation())
                        },
                    )
                    appLinksIconChannel = ownedAppLinksIcons
                }
            }
            onReady()
            // The control session is long-lived. Poll timeouts only wake this loop; they do not end it.
            while (true) {
                vehicleReporter.tick(::sendDuringControl)
                if (locationActive && sendLatestLocation(locationProvider, locationSubscription) && !locationSentLogged) {
                    locationSentLogged = true
                    onProgress("iap2 tx=0xfffb location-information")
                }
                val pollTimeout = if (locationActive) {
                    LOCATION_POLL_INTERVAL_MILLIS
                } else {
                    CONTROL_POLL_INTERVAL_MILLIS
                }
                val incoming = session.recv(vehicleReporter.pollTimeout(pollTimeout))
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    continue
                }

                if (vehicleReporter.handle(incoming, ::sendDuringControl)) continue
                when (incoming.messageId) {
                    CARPLAY_AVAILABILITY -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        onProgress(carPlayAvailabilitySummary(incoming.payload))
                        // LIVI sends its wired answer on every availability notification; do not gate it on
                        // the phone's advertised availability boolean.
                        val reply = wiredStartSessionForControlSend(
                            endpoint,
                            endpointForControlSend,
                            incoming.payload,
                            authenticationReceipt.authenticationIdentity,
                            allowClusterAssetAcquisition,
                        )
                        sendDuringControl(reply.frame)
                        stage = Iap2WiredControlStage.CARPLAY_START_SENT
                        carPlayStartSessions++
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                        if (debugAssetAcquisitionMarker) {
                            onProgress(clusterAssetAcquisitionSendMarker(
                                reply.frame,
                                reply.acquisitionRequested,
                            ))
                        }
                    }

                    Iap2LocationMessages.START_LOCATION_INFORMATION -> {
                        onProgress("iap2 rx=0xfffa start-location-information")
                        locationActive = if (locationSubscription.request(incoming, locationComponents)) {
                            locationProvider?.onRequested(locationSubscription.components.intersect(locationComponents))
                            startLocationUpdates(locationProvider, onProgress)
                        } else { locationProvider?.stop(); false }
                        locationSentLogged = false
                        if (locationActive && sendLatestLocation(locationProvider, locationSubscription)) {
                            locationSentLogged = true
                            onProgress("iap2 tx=0xfffb location-information")
                        }
                    }

                    Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                        onProgress("iap2 rx=0xfffc stop-location-information")
                        locationActive = false
                        locationSentLogged = false
                        locationProvider?.stop()
                        locationSubscription.stop()
                    }

                    Iap2AppLinksMessages.UPDATE_ID -> {
                        val subscription = ownedAppLinks
                        val appLinksGeneration = subscription?.activeGeneration()
                        if (subscription != null && appLinksGeneration != null) {
                            val update = try {
                                Iap2AppLinksMessages.update(incoming)
                            } catch (malformed: Iap2ProtocolException) {
                                onProgress("iap2 ignored malformed 0xad01 AppLinksUpdate: ${malformed.message}")
                                null
                            }
                            if (update != null && !subscription.deliverIfStarted(
                                update, appLinksGeneration, onAppLinksUpdate,
                            )) {
                                onProgress("iap2 ignored stale 0xad01 AppLinksUpdate")
                            }
                        } else {
                            onProgress("iap2 ignored unrequested 0xad01 AppLinksUpdate")
                        }
                    }

                    Iap2AppLinksMessages.ICON_RESPONSE_ID -> {
                        val iconChannel = ownedAppLinksIcons?.takeIf { appLinksIconChannel === it }
                        when (iconChannel?.receive(incoming, onAppLinksIconResponse)) {
                            Iap2AppLinksIconChannel.ReceiveOutcome.MALFORMED ->
                                onProgress("iap2 ignored malformed 0xad04 AppIconResponse")
                            Iap2AppLinksIconChannel.ReceiveOutcome.STALE ->
                                onProgress("iap2 ignored stale 0xad04 AppIconResponse")
                            Iap2AppLinksIconChannel.ReceiveOutcome.DELIVERED -> Unit
                            null, Iap2AppLinksIconChannel.ReceiveOutcome.UNREQUESTED ->
                                onProgress("iap2 ignored unrequested 0xad04 AppIconResponse")
                        }
                    }

                    else -> {
                        onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
            }
        } finally {
            ownedAppLinksIcons?.close()
            if (ownedAppLinksIcons != null && appLinksIconChannel === ownedAppLinksIcons) {
                appLinksIconChannel = null
            }
            ownedAppLinks?.close()
            if (ownedAppLinks != null && appLinksLifecycle === ownedAppLinks) appLinksLifecycle = null
            vehicleReporter.stop()
            locationProvider?.stop()
            if (hidStarted && !session.isClosed) {
                runCatching {
                    session.send(Iap2HidMessages.stopMediaPlaybackRemote(), HID_STOP_TIMEOUT_MILLIS)
                }
            }
            onStopped()
        }
    }

    private fun sendDuringBringUp(frame: Iap2Frame, deadlineNanos: Long) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    private fun sendDuringControl(frame: Iap2Frame) {
        session.send(frame, CONTROL_SEND_TIMEOUT_MILLIS)
    }

    private fun sendLatestLocation(
        provider: Iap2LocationProvider?,
        subscription: Iap2LocationSubscription,
    ): Boolean {
        val sentence = provider?.latestNmea()?.let(subscription::filter) ?: return false
        session.send(
            Iap2LocationMessages.locationInformation(sentence),
            CONTROL_SEND_TIMEOUT_MILLIS,
        )
        return true
    }

    private fun startLocationUpdates(
        provider: Iap2LocationProvider?,
        onProgress: (String) -> Unit,
    ): Boolean {
        if (provider == null) return false
        return try {
            provider.start().also { started ->
                if (!started) onProgress("iap2 location provider did not start")
            }
        } catch (error: Exception) {
            onProgress("iap2 location provider start failed: ${error.message}")
            false
        }
    }

    companion object {
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        private const val CONTROL_POLL_INTERVAL_MILLIS = 60_000L
        private const val CONTROL_SEND_TIMEOUT_MILLIS = 5_000L
        private const val HID_STOP_TIMEOUT_MILLIS = 1_000L
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_BRING_UP_STEP_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Exact LIVI wired PowerSourceUpdate encoding: current and the charge-if-powered flag. */
        fun powerSourceUpdate(availableCurrentMilliAmps: Int): Iap2Frame =
            Iap2ControlMessages.powerSourceUpdate(availableCurrentMilliAmps, charging = true)

        /** Exact five subscription requests emitted by LIVI's wired bring-up. */
        fun subscriptions(): List<Iap2Frame> = Iap2ControlMessages.subscriptions()

        /** Builds the wired-only CarPlayStartSession message; no NCM or AirPlay socket is opened. */
        fun carPlayStartSession(
            endpoint: Iap2WiredCarPlayEndpoint,
            @Suppress("UNUSED_PARAMETER")
            availabilityPayload: ByteArray? = null,
            actualMfiAuthenticationIdentity: MfiAuthenticationIdentity? = null,
            allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean = { _, _ -> false },
        ): Iap2Frame {
            // An authorized, preinstalled asset can bootstrap the phone's cluster capability;
            // the earlier 0x4300 GaugeCluster bit is not a prerequisite for this announcement.
            val clusterAsset = endpoint.clusterAssetCatalog.assetOrNull(endpoint.clusterAssetKey)
                .takeIf { !endpoint.sdkVersion.isNullOrBlank() }
            val acquisition = endpoint.clusterAssetAcquisitionIntent?.takeIf { intent ->
                actualMfiAuthenticationIdentity != null &&
                    intent.expectedMfiAuthenticationIdentity == actualMfiAuthenticationIdentity &&
                    try {
                        allowClusterAssetAcquisition(intent.assetId, actualMfiAuthenticationIdentity)
                    } catch (_: Exception) {
                        false
                    }
            }
            return Iap2CarPlayMessages.startSession(
                wiredIpv6Addresses = endpoint.ipv6Addresses,
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
                sdkVersion = endpoint.sdkVersion?.takeIf { clusterAsset != null },
                clusterAsset = clusterAsset,
                clusterAssetAcquisitionIntent = acquisition,
                actualMfiAuthenticationIdentity = actualMfiAuthenticationIdentity,
            )
        }

        fun carPlayAvailabilitySummary(payload: ByteArray): String {
            return try {
                val availability = Iap2CarPlayMessages.availability(payload).wired
                val available = availability?.available
                val transport = availability?.identifier
                "iap2 4300 wiredAvailable=$available usbTransport=${transport ?: "none"}"
            } catch (error: RuntimeException) {
                "iap2 4300 decode failed: ${error.message}"
            }
        }

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wired iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                .coerceAtMost(MAX_BRING_UP_STEP_TIMEOUT_MILLIS)
        }

    }
}

/**
 * The AD03/AD04 half of one authenticated AppLinks CSM. AD04 has no request identifier, so a
 * channel may send AD03 only once; even Stop→Start may not reuse a bundle on that CSM. A send
 * exception is ambiguous after bytes can have left the process and permanently consumes it.
 *
 * The AppLinks lifecycle lock is outermost so Start/Stop and icon send/delivery cannot cross.
 */
internal class Iap2AppLinksIconChannel(
    val lifecycle: Iap2AppLinksLifecycle,
    private val send: (Iap2Frame) -> Unit,
    private val isAuthorized: () -> Boolean,
) {
    enum class ReceiveOutcome { DELIVERED, UNREQUESTED, MALFORMED, STALE }

    private var consumed = false
    private var sent = false
    private var closed = false
    private var requestGeneration: Long? = null
    private val pendingBundleIdentifiers = LinkedHashSet<String>()

    fun request(
        carPlayBundleIdentifiers: Set<String>,
        externalAccessoryBundleIdentifiers: Set<String> = emptySet(),
    ): Boolean {
        val carPlay = carPlayBundleIdentifiers.toSet()
        val externalAccessory = externalAccessoryBundleIdentifiers.toSet()
        require(carPlay.intersect(externalAccessory).isEmpty()) {
            "An AppLinks icon bundle may not be requested from two subfeatures on one CSM"
        }
        val frame = Iap2AppLinksMessages.requestIcons(carPlay, externalAccessory)
        return synchronized(lifecycle) {
            synchronized(this) {
                if (closed || consumed || !isAuthorized()) return@synchronized false
                val generation = lifecycle.activeGeneration() ?: return@synchronized false
                consumed = true
                requestGeneration = generation
                pendingBundleIdentifiers += carPlay
                pendingBundleIdentifiers += externalAccessory
                try {
                    send(frame)
                    sent = true
                    val stillCurrent = lifecycle.activeGeneration() == generation && isAuthorized()
                    if (!stillCurrent) {
                        pendingBundleIdentifiers.clear()
                        requestGeneration = null
                    }
                    stillCurrent
                } catch (_: Exception) {
                    // A timeout does not tell us whether AD03 reached the phone. Never resend on
                    // this CSM or accept a response from a partially sent request.
                    pendingBundleIdentifiers.clear()
                    requestGeneration = null
                    false
                }
            }
        }
    }

    /** Call on the CSM receiver thread before parsing the AD04 body. */
    fun activeRequestGeneration(): Long? = synchronized(lifecycle) {
        synchronized(this) {
            lifecycle.activeGeneration()?.takeIf { generation ->
                !closed && sent && requestGeneration == generation &&
                    pendingBundleIdentifiers.isNotEmpty() && isAuthorized()
            }
        }
    }

    fun receive(
        frame: Iap2Frame,
        callback: (Iap2AppLinksIconResponse) -> Unit,
    ): ReceiveOutcome {
        // This generation must be captured before codec parsing. The decoder is bounded but may
        // still overlap a Stop/Start on another thread.
        val generation = activeRequestGeneration() ?: return ReceiveOutcome.UNREQUESTED
        val response = try {
            Iap2AppLinksMessages.iconResponse(frame)
        } catch (_: Iap2ProtocolException) {
            return ReceiveOutcome.MALFORMED
        }
        return if (deliver(response, generation, callback)) {
            ReceiveOutcome.DELIVERED
        } else {
            ReceiveOutcome.STALE
        }
    }

    /** A callback begins only for one requested bundle on this exact live Start generation. */
    fun deliver(
        response: Iap2AppLinksIconResponse,
        expectedGeneration: Long,
        callback: (Iap2AppLinksIconResponse) -> Unit,
    ): Boolean = synchronized(lifecycle) {
        synchronized(this) {
            if (closed || !sent || !isAuthorized() ||
                requestGeneration != expectedGeneration ||
                lifecycle.activeGeneration() != expectedGeneration ||
                !pendingBundleIdentifiers.remove(response.bundleIdentifier)
            ) {
                return@synchronized false
            }
            callback(response)
            true
        }
    }

    fun onStopped() = synchronized(lifecycle) {
        synchronized(this) { pendingBundleIdentifiers.clear() }
    }

    fun close() = synchronized(lifecycle) {
        synchronized(this) {
            closed = true
            pendingBundleIdentifiers.clear()
        }
    }
}

/** A configured wired AirPlay endpoint; configuration does not claim that either service is live. */
class Iap2WiredCarPlayEndpoint(
    ipv6Addresses: List<String>,
    val airPlayPort: Int,
    val publicKey: String,
    val sourceVersion: String,
    val deviceIdentifier: String? = null,
    val clusterAssetCatalog: Iap2ClusterAssetCatalog = Iap2ClusterAssetCatalog.EMPTY,
    val clusterAssetKey: Iap2ClusterAssetKey? = null,
    val sdkVersion: String? = null,
    val clusterAssetAcquisitionIntent: Iap2ClusterAssetAcquisitionIntent? = null,
) {
    /** A stable copy so a caller cannot mutate a validated endpoint before it is encoded. */
    val ipv6Addresses: List<String> = ipv6Addresses.toList()

    init {
        require(ipv6Addresses.isNotEmpty()) { "At least one wired IPv6 address is required" }
        require(ipv6Addresses.all(::isIpv6Literal)) { "Every wired address must be an IPv6 text literal" }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        require(sdkVersion == null || '\u0000' !in sdkVersion) { "sdkVersion must not contain U+0000" }
        require(
            clusterAssetAcquisitionIntent == null ||
                (clusterAssetKey == null && clusterAssetCatalog.profiles.isEmpty() && sdkVersion == null),
        ) { "cluster asset acquisition and preinstalled asset selection are mutually exclusive" }
        deviceIdentifier?.let {
            require(it.isNotEmpty()) { "deviceIdentifier is optional, but must not be empty when provided" }
            require('\u0000' !in it) { "deviceIdentifier must not contain U+0000" }
        }
    }

    private companion object {
        fun isIpv6Literal(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                InetAddress.getByName(value) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class Iap2WiredControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    CARPLAY_START_SENT,
}

enum class Iap2WiredControlTerminal { CHANNEL_CLOSED }

/** End state of the control loop only; it is not evidence of NCM or AirPlay availability. */
data class Iap2WiredControlResult(
    val terminal: Iap2WiredControlTerminal,
    val stage: Iap2WiredControlStage,
    val forwardedFrames: Int,
    val carPlayStartSessionsSent: Int,
)
