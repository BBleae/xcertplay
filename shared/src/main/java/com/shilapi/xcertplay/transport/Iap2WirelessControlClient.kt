package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2AppLinksLifecycle
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksIconResponse
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksMessages
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksUpdate
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAcquisitionIntent
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetCatalog
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetKey
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationAttempt
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import java.io.IOException

internal fun resolveLiveWirelessControlEndpoint(
    initial: Iap2WirelessCarPlayEndpoint,
    current: () -> Iap2WirelessCarPlayEndpoint?,
    messageId: Int,
): Iap2WirelessCarPlayEndpoint {
    val live = current() ?: throw IOException(
        "Wireless P2P/AirPlay endpoint retired before 0x${messageId.toString(16)}",
    )
    if (
        live.ssid != initial.ssid || live.passphrase != initial.passphrase ||
        live.channel != initial.channel || live.security != initial.security ||
        live.airPlayPort != initial.airPlayPort ||
        live.deviceIdentifier != initial.deviceIdentifier ||
        live.publicKey != initial.publicKey ||
        live.sourceVersion != initial.sourceVersion
    ) {
        throw IOException("Wireless endpoint identity changed before 0x${messageId.toString(16)}")
    }
    return live
}

internal fun wirelessStartSessionForControlSend(
    initial: Iap2WirelessCarPlayEndpoint,
    current: () -> Iap2WirelessCarPlayEndpoint?,
    availabilityPayload: ByteArray,
    authenticationIdentity: MfiAuthenticationIdentity,
    allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean,
): Iap2CarPlayStartSessionReply {
    val live = resolveLiveWirelessControlEndpoint(initial, current, 0x4301)
    return Iap2CarPlayStartSessionReply(
        frame = Iap2WirelessControlClient.carPlayStartSession(
            live,
            availabilityPayload,
            authenticationIdentity,
            allowClusterAssetAcquisition,
        ),
        acquisitionRequested = live.clusterAssetAcquisitionIntent != null,
    )
}

/** The old Bluetooth CSM must not start another CarPlay session after tunnel handoff begins. */
internal fun wirelessBootstrapStartSessionForControlSend(
    initial: Iap2WirelessCarPlayEndpoint,
    current: () -> Iap2WirelessCarPlayEndpoint?,
    availabilityPayload: ByteArray,
    authenticationIdentity: MfiAuthenticationIdentity,
    allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean,
    bootstrapSendAllowed: () -> Boolean,
): Iap2CarPlayStartSessionReply? {
    fun admitted(): Boolean = try {
        bootstrapSendAllowed()
    } catch (_: Exception) {
        false
    }
    if (!admitted()) return null
    val reply = wirelessStartSessionForControlSend(
        initial,
        current,
        availabilityPayload,
        authenticationIdentity,
        allowClusterAssetAcquisition,
    )
    // Live endpoint resolution and signed-profile checks can overlap handoff. Re-check before
    // the actual CSM send, while keeping controller locks out of the blocking send itself.
    return reply.takeIf { admitted() }
}

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
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
        endpoint: Iap2WirelessCarPlayEndpoint,
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
        /** Revalidate exact Wi-Fi group and AirPlay listener immediately before 0x5703/0x4301. */
        endpointForControlSend: () -> Iap2WirelessCarPlayEndpoint?,
        /** A signed-profile check evaluated immediately before each 0x4301 is constructed. */
        allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean = { _, _ -> false },
        debugAssetAcquisitionMarker: Boolean = false,
        /** Exact Bluetooth-bootstrap receipt and handoff fence; tunnel mode never uses it. */
        bootstrapStartSessionAllowed: (MfiAuthenticationReceipt) -> Boolean = { false },
        /**
         * Whether 0x4300 is answered with 0x4301. The AirPlay-tunneled link runs inside an
         * already started session; iOS treats every 0x4301 as a new CarPlay session request.
         */
        startSessionOnAvailability: Boolean = true,
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires an Iap2IdentificationConfig with wireless transport"
        }
        require(
            bringUpTimeoutMillis == NO_TIMEOUT_MILLIS ||
                bringUpTimeoutMillis in 1..MAX_TIMEOUT_MILLIS,
        ) {
            "bringUpTimeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
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
        val bringUpDeadlineNanos = if (bringUpTimeoutMillis == NO_TIMEOUT_MILLIS) {
            Long.MAX_VALUE
        } else {
            deadlineAfter(bringUpTimeoutMillis)
        }
        Iap2IdentificationClient(session).identify(
            controlIdentification,
            requireRemaining(bringUpDeadlineNanos),
        )
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        val authenticationReceipt = mfi.run(
            session,
            authenticationAttempt,
            requireRemaining(bringUpDeadlineNanos),
            onProgress,
        )
        stage = Iap2WirelessControlStage.AUTHENTICATED
        onAuthenticated(authenticationReceipt)
        onProgress("iap2 authentication accepted")

        for (subscription in Iap2WiredControlClient.subscriptions()) {
            sendDuringBringUp(subscription, bringUpDeadlineNanos)
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")

        var forwardedFrames = 0
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayAvailableSeen = false
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
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    continue
                }

                if (vehicleReporter.handle(incoming, ::sendDuringControl)) continue
                when (incoming.messageId) {
                    REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                        onProgress("iap2 rx=0x5702 request-wifi-configuration")
                        val postTransport = transportNotificationSeen
                        val sentCount = if (postTransport) {
                            postTransportWiFiConfigurationsSent
                        } else {
                            preTransportWiFiConfigurationsSent
                        }
                        val limit = if (postTransport) {
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        } else {
                            MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        }
                        if (sentCount >= limit) {
                            onProgress(
                                "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            val liveEndpoint = resolveLiveWirelessControlEndpoint(
                                endpoint,
                                endpointForControlSend,
                                ACCESSORY_WIFI_CONFIGURATION,
                            )
                            sendDuringControl(accessoryWiFiConfiguration(liveEndpoint))
                            stage = later(
                                stage,
                                if (postTransport) {
                                    Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                                } else {
                                    Iap2WirelessControlStage.WIFI_CONFIG_SENT
                                },
                            )
                            wifiConfigurationsSent++
                            if (postTransport) {
                                postTransportWiFiConfigurationsSent++
                            } else {
                                preTransportWiFiConfigurationsSent++
                            }
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                        }
                    }

                    CARPLAY_AVAILABILITY -> if (!startSessionOnAvailability) {
                        onProgress(
                            "iap2 rx=0x4300 carplay-availability; " +
                                "0x4301 not sent inside the active AirPlay session",
                        )
                    } else {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        // A cached address or receipt may belong to a retired bootstrap.
                        val reply = wirelessBootstrapStartSessionForControlSend(
                            endpoint,
                            endpointForControlSend,
                            incoming.payload,
                            authenticationReceipt.authenticationIdentity,
                            allowClusterAssetAcquisition,
                            bootstrapSendAllowed = {
                                bootstrapStartSessionAllowed(authenticationReceipt)
                            },
                        )
                        if (reply == null) {
                            onProgress("iap2 0x4301 suppressed: stale Bluetooth bootstrap")
                        } else {
                            sendDuringControl(reply.frame)
                            stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                            carPlayStartSessionsSent++
                            onProgress("iap2 tx=0x4301 carplay-start-session")
                            if (debugAssetAcquisitionMarker) {
                                onProgress(clusterAssetAcquisitionSendMarker(
                                    reply.frame,
                                    reply.acquisitionRequested,
                                ))
                            }
                        }
                    }

                    WIRELESS_CARPLAY_UPDATE -> {
                        val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                        if (available) wirelessCarPlayAvailableSeen = true
                        onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                    }

                    DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                        transportNotificationSeen = true
                        val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                        stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "resending 0x5703",
                        )
                        if (
                            postTransportWiFiConfigurationsSent >=
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        ) {
                            onProgress(
                                "iap2 post-transport 0x5703 ignored: " +
                                    "maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            val liveEndpoint = resolveLiveWirelessControlEndpoint(
                                endpoint,
                                endpointForControlSend,
                                ACCESSORY_WIFI_CONFIGURATION,
                            )
                            sendDuringControl(accessoryWiFiConfiguration(liveEndpoint))
                            stage = later(
                                stage,
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                            )
                            wifiConfigurationsSent++
                            postTransportWiFiConfigurationsSent++
                            onProgress(
                                "iap2 tx=0x5703 post-transport accessory-wifi-configuration",
                            )
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
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        private const val LOCATION_POLL_INTERVAL_MILLIS = 1_000L
        private const val CONTROL_POLL_INTERVAL_MILLIS = 60_000L
        private const val CONTROL_SEND_TIMEOUT_MILLIS = 5_000L
        private const val HID_STOP_TIMEOUT_MILLIS = 1_000L
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_BRING_UP_STEP_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Reference-compatible 0x5703 body. BSSID is omitted when the platform does not expose it. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(
            endpoint: Iap2WirelessCarPlayEndpoint,
            @Suppress("UNUSED_PARAMETER")
            availabilityPayload: ByteArray? = null,
            actualMfiAuthenticationIdentity: MfiAuthenticationIdentity? = null,
            allowClusterAssetAcquisition: (String, MfiAuthenticationIdentity) -> Boolean = { _, _ -> false },
        ): Iap2Frame {
            // A locally authorized, installed asset may need to bootstrap the phone's feature
            // state. The previous 0x4300 mask is not a prerequisite for advertising that asset.
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
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
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

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next

        private fun deadlineAfter(timeoutMillis: Long): Long {
            val now = System.nanoTime()
            val delta = timeoutMillis * NANOS_PER_MILLISECOND
            return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
        }

        private fun requireRemaining(deadlineNanos: Long): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wireless iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return 0L
            return ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                .coerceAtMost(MAX_BRING_UP_STEP_TIMEOUT_MILLIS)
        }
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    val clusterAssetCatalog: Iap2ClusterAssetCatalog = Iap2ClusterAssetCatalog.EMPTY,
    val clusterAssetKey: Iap2ClusterAssetKey? = null,
    val sdkVersion: String? = null,
    val clusterAssetAcquisitionIntent: Iap2ClusterAssetAcquisitionIntent? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()

    init {
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        require(sdkVersion == null || '\u0000' !in sdkVersion) { "sdkVersion must not contain U+0000" }
        require(
            clusterAssetAcquisitionIntent == null ||
                (clusterAssetKey == null && clusterAssetCatalog.profiles.isEmpty() && sdkVersion == null),
        ) { "cluster asset acquisition and preinstalled asset selection are mutually exclusive" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
)
