package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class WifiP2pCredentials(
    val ssid: String,
    val passphrase: String,
)

internal data class WifiP2pObservedGroup(
    val isGroupOwner: Boolean,
    val networkName: String?,
    val passphrase: String?,
    val ownerDeviceAddress: String? = null,
    val interfaceName: String? = null,
    val networkId: Int? = null,
)

internal data class WifiP2pGroupInspection(
    val completed: Boolean,
    val group: WifiP2pObservedGroup? = null,
) {
    init {
        require(completed || group == null) {
            "A timed-out Wi-Fi P2P inspection cannot contain a group"
        }
    }

    companion object {
        fun completed(group: WifiP2pObservedGroup?): WifiP2pGroupInspection =
            WifiP2pGroupInspection(completed = true, group = group)

        fun timedOut(): WifiP2pGroupInspection = WifiP2pGroupInspection(completed = false)
    }
}

internal enum class WifiP2pStaleGroupDecision {
    PROCEED_WITH_CREATE,
    PRESERVE_PREEXISTING_GROUP,
    INSPECTION_UNPROVEN,
    CANCEL_START,
}

/**
 * Existing P2P groups are never process-owned merely because their configuration matches.
 * A previous-process group has no exact create callback or retirement proof in this manager, so
 * recovery is fail-closed and leaves it untouched.
 */
internal fun decideWifiP2pStaleGroupRecovery(
    inspection: WifiP2pGroupInspection,
    startActive: Boolean,
): WifiP2pStaleGroupDecision {
    if (!startActive) return WifiP2pStaleGroupDecision.CANCEL_START
    if (!inspection.completed) return WifiP2pStaleGroupDecision.INSPECTION_UNPROVEN
    return if (inspection.group == null) WifiP2pStaleGroupDecision.PROCEED_WITH_CREATE
    else WifiP2pStaleGroupDecision.PRESERVE_PREEXISTING_GROUP
}

internal fun mfiCertificateWifiP2pCredentials(certificate: ByteArray): WifiP2pCredentials {
    require(certificate.isNotEmpty()) { "MFi certificate must not be empty" }
    val digest = MessageDigest.getInstance("SHA-1").digest(certificate)
    val digestHex = buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
    return WifiP2pCredentials(
        ssid = WIFI_P2P_SSID_PREFIX + digestHex.take(MFI_CERTIFICATE_SSID_SUFFIX_LENGTH),
        passphrase = digestHex.takeLast(MFI_CERTIFICATE_PASSPHRASE_LENGTH),
    )
}

/** A post-create group is usable only with credentials actually reported by Android. */
internal fun matchesWifiP2pStartCredentials(
    expected: WifiP2pCredentials,
    observedNetworkName: String?,
    observedPassphrase: String?,
): Boolean = observedNetworkName == expected.ssid &&
    !observedPassphrase.isNullOrBlank() && observedPassphrase == expected.passphrase

/** Bounds immediate groupInfo callbacks so wrong/incomplete observations cannot spin. */
internal fun wifiP2pGroupRetryDelayNanos(remainingNanos: Long): Long =
    minOf(remainingNanos.coerceAtLeast(0L), TimeUnit.MILLISECONDS.toNanos(250))

internal fun paceWifiP2pGroupRetry(
    remainingNanos: Long,
    waitNanos: (Long) -> Unit,
) {
    val delay = wifiP2pGroupRetryDelayNanos(remainingNanos)
    if (delay > 0L) waitNanos(delay)
}

internal data class WifiP2pInterfaceAddresses(
    val primary: InetAddress,
    val secondary: Inet4Address?,
)

/**
 * Returns only addresses observed on the exact interface reported for this P2P group. The
 * historically working scoped IPv6 address remains primary. An IPv4 alias is usable only when
 * it is the sole IPv4 candidate or matches Android's group-owner address on this interface.
 */
internal fun selectWifiP2pInterfaceAddresses(
    groupInterfaceName: String,
    addressInterfaceName: String,
    interfaceIndex: Int,
    addresses: Iterable<InetAddress>,
    groupOwnerAddress: InetAddress? = null,
): WifiP2pInterfaceAddresses? {
    if (groupInterfaceName != addressInterfaceName) return null

    val ipv4Candidates = ArrayList<Inet4Address>()
    var ipv6: Inet6Address? = null
    for (address in addresses) {
        when {
            address is Inet4Address &&
                !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isMulticastAddress -> {
                if (ipv4Candidates.none { it == address }) ipv4Candidates.add(address)
            }

            address is Inet6Address && address.isLinkLocalAddress && interfaceIndex > 0 &&
                (address.scopeId == 0 || address.scopeId == interfaceIndex) -> {
                val scoped = if (address.scopeId == interfaceIndex) {
                    address
                } else {
                    try {
                        Inet6Address.getByAddress(null, address.address, interfaceIndex)
                    } catch (_: UnknownHostException) {
                        continue
                    }
                }
                if (ipv6 == null || compareAddressBytes(scoped, ipv6) < 0) ipv6 = scoped
            }
        }
    }
    val ipv4 = ipv4Candidates.firstOrNull { it == groupOwnerAddress }
        ?: ipv4Candidates.singleOrNull()
    return when {
        ipv6 != null -> WifiP2pInterfaceAddresses(primary = ipv6, secondary = ipv4)
        ipv4 != null -> WifiP2pInterfaceAddresses(primary = ipv4, secondary = null)
        else -> null
    }
}

internal fun sameWifiP2pEndpoint(expected: InetAddress?, current: InetAddress?): Boolean =
    expected != null && current != null && expected == current &&
        (expected !is Inet6Address ||
            current is Inet6Address && expected.scopeId == current.scopeId)

internal fun retainWifiP2pOwnerOnChannelLoss(
    createIssued: Boolean,
    channelDisconnected: Boolean,
): Boolean = createIssued && channelDisconnected

internal fun runBoundedDisconnectedP2pProbes(
    maxProbes: Int,
    probe: () -> Unit,
    pauseBetween: () -> Boolean,
): Int {
    require(maxProbes > 0)
    var probes = 0
    while (probes < maxProbes) {
        probe()
        probes++
        if (probes < maxProbes && !pauseBetween()) break
    }
    return probes
}

private fun compareAddressBytes(left: InetAddress, right: InetAddress): Int {
    val leftBytes = left.address
    val rightBytes = right.address
    for (index in leftBytes.indices) {
        val difference = (leftBytes[index].toInt() and 0xff) -
            (rightBytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return 0
}

/**
 * Creates a temporary 5 GHz Wi-Fi Direct group owner that can also be joined as a legacy AP.
 *
 * The group is deliberately not persistent. [close] retains the exact callback thread until
 * removal is observed; an incomplete close throws so its caller can keep this retry owner.
 */
@SuppressLint("MissingPermission", "NewApi")
class WifiP2pGroupManager(
    context: Context,
    private val networkName: String,
    private val passphrase: String,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val stateLock = Object()

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var closed = false
    private var startAttempt: StartAttempt? = null
    private var activeAttempt: StartAttempt? = null
    private var retirementAttempt: StartAttempt? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Wi-Fi P2P credentials require Android 10 (API 29) or newer")
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "WifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val credentials = WifiP2pCredentials(networkName, passphrase)
        val attempt = synchronized(stateLock) {
            check(!closed) { "WifiP2pGroupManager is closed" }
            check(startAttempt == null && activeAttempt == null && retirementAttempt == null) {
                "A Wi-Fi P2P group is already starting, active, or being cleaned up"
            }
            StartAttempt(credentials).also { startAttempt = it }
        }

        val thread = HandlerThread("xcertplay-wifi-p2p")
        attempt.thread = thread
        val deadlineNanos = deadlineAfter(timeoutMillis)

        try {
            thread.start()
            val p2pChannel = p2pManager.initialize(
                appContext,
                thread.looper,
                createChannelListener(attempt),
            )
            attempt.channel = p2pChannel
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                channel = p2pChannel
                callbackThread = thread
            }

            val config = WifiP2pConfig.Builder()
                .setNetworkName(credentials.ssid)
                .setPassphrase(credentials.passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                config.groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MAX
            }

            ensureNoPreexistingGroup(attempt, p2pChannel, deadlineNanos)
            createGroupOnce(
                attempt = attempt,
                channel = p2pChannel,
                config = config,
                deadlineNanos = deadlineNanos,
                timeoutMillis = timeoutMillis,
            )

            val group = awaitUsableGroup(
                attempt = attempt,
                channel = p2pChannel,
                credentials = credentials,
                deadlineNanos = deadlineNanos,
                timeoutMillis = timeoutMillis,
            )
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                activeAttempt = attempt
                attempt.publishedInfo = group
                startAttempt = null
                attempt.startExited = true
                stateLock.notifyAll()
            }
            return group
        } catch (failure: Throwable) {
            cleanupFailedStart(attempt)
            throw failure
        }
    }

    override fun close() {
        val attempt: StartAttempt?
        synchronized(stateLock) {
            closed = true
            attempt = retirementAttempt ?: startAttempt ?: activeAttempt
            attempt?.stopped = true
            stateLock.notifyAll()
        }
        if (attempt == null) return
        scheduleRetirement(attempt)
        awaitRetirementIfOffMainThread(attempt)
        synchronized(stateLock) {
            if (!attempt.retired) {
                val reason = if (attempt.channelDisconnected) {
                    "Wi-Fi P2P Channel disconnected; exact group cleanup is unproven. " +
                        "Reset Wi-Fi Direct externally, then retry teardown"
                } else {
                    attempt.retirementUncertainReason
                        ?: "Wi-Fi P2P cleanup is pending; exact owner retained for retry"
                }
                throw IOException(reason)
            }
        }
    }

    /** Fresh, bounded observation for one still-owned group; never trust a cached startup address.
     * Android can recycle an ephemeral group with the same visible fingerprint, so this is not
     * cryptographic provenance or an atomic guarantee across the following iAP2 send.
     */
    fun liveOwnedGroupInfo(): WirelessHotspotInfo? {
        val attempt = synchronized(stateLock) {
            activeAttempt?.takeIf {
                !closed && !it.stopped && !it.channelDisconnected &&
                    it.publishedInfo != null && it.createOutcome == CreateGroupOutcome.Success
            }
        } ?: return null
        if (Looper.myLooper() == Looper.getMainLooper() ||
            Looper.myLooper() == attempt.thread?.looper
        ) return null
        val activeChannel = attempt.channel ?: return null
        val inspection = inspectRawGroup(activeChannel)
        val rawGroup = inspection.group ?: return null
        if (!inspection.completed) return null
        val observed = observeGroup(rawGroup)
        if (
            classifyWifiP2pRetirementGroup(
                expected = attempt.credentials,
                acceptedFingerprint = attempt.acceptedGroup,
                observed = observed,
            ) != WifiP2pRetirementGroupState.EXACT
        ) return null
        val original = attempt.publishedInfo ?: return null
        if (rawGroup.frequency != original.frequencyMHz ||
            wifiFrequencyMhzToChannel(rawGroup.frequency) != original.channel ||
            groupSecurity(rawGroup) != original.security
        ) return null
        val interfaceName = observed.interfaceName ?: return null
        val liveAddresses = interfaceAddresses(interfaceName, original.secondaryHostAddress)
            ?: return null
        if (!sameWifiP2pEndpoint(original.hostAddress, liveAddresses.primary)) return null
        if (original.secondaryHostAddress != null && liveAddresses.secondary != null &&
            original.secondaryHostAddress != liveAddresses.secondary
        ) return null
        val liveBssid = interfaceHardwareAddress(interfaceName)
            ?: observed.ownerDeviceAddress
        if (original.bssid != null && liveBssid != original.bssid) return null
        val liveGroup = synchronized(stateLock) {
            if (closed || activeAttempt !== attempt || attempt.stopped ||
                attempt.channelDisconnected || attempt.publishedInfo !== original
            ) null else original
        } ?: return null
        return WirelessHotspotInfo(
            ssid = observed.networkName ?: return null,
            passphrase = observed.passphrase ?: return null,
            security = liveGroup.security,
            channel = liveGroup.channel,
            frequencyMHz = liveGroup.frequencyMHz,
            bssid = liveBssid,
            interfaceName = interfaceName,
            hostAddress = liveAddresses.primary,
            bandLabel = liveGroup.bandLabel,
            backend = WirelessHotspotBackend.WIFI_P2P,
            secondaryHostAddress = liveAddresses.secondary,
        )
    }

    private fun createChannelListener(
        attempt: StartAttempt,
    ): WifiP2pManager.ChannelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            synchronized(stateLock) {
                attempt.channelDisconnected = true
                attempt.publishedInfo = null
                attempt.retirementUncertainReason =
                    "Wi-Fi P2P Channel disconnected; exact group cleanup is unproven"
                stateLock.notifyAll()
            }
            failAttempt(attempt, IOException("Wi-Fi P2P channel disconnected"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun createGroupOnce(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        val generation = synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
            attempt.createGeneration += 1
            attempt.createOutcome = null
            // Publish the pending callback before issuing the asynchronous framework operation.
            // A concurrent close must retain the callback Looper rather than assume no group.
            attempt.createIssued = true
            attempt.createGeneration
        }
        try {
            p2pManager.createGroup(
                channel,
                config,
                createActionListener(attempt, generation),
            )
        } catch (failure: RuntimeException) {
            // The framework may have accepted an asynchronous request before throwing. Leave
            // CREATE_PENDING in the retirement owner; a missing callback is not clean proof.
            throw IOException("Could not issue Wi-Fi P2P createGroup", failure)
        }
        awaitGroupCreated(attempt, generation, deadlineNanos, timeoutMillis)
    }

    private fun createActionListener(
        attempt: StartAttempt,
        generation: Long,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            synchronized(stateLock) {
                if (attempt.createGeneration != generation) return
                attempt.createOutcome = CreateGroupOutcome.Success
                stateLock.notifyAll()
            }
        }

        override fun onFailure(reason: Int) {
            synchronized(stateLock) {
                if (
                    attempt.createGeneration == generation &&
                    attempt.createOutcome == null
                ) {
                    attempt.createOutcome = CreateGroupOutcome.Failure(reason)
                    stateLock.notifyAll()
                }
            }
        }
    }

    private fun failAttempt(attempt: StartAttempt, failure: IOException) {
        synchronized(stateLock) {
            if (startAttempt === attempt && !attempt.stopped && !closed) {
                if (attempt.terminalFailure == null) attempt.terminalFailure = failure
                stateLock.notifyAll()
            }
        }
    }

    private fun awaitGroupCreated(
        attempt: StartAttempt,
        generation: Long,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        synchronized(stateLock) {
            while (true) {
                ensureStartActiveLocked(attempt)
                attempt.terminalFailure?.let { throw it }
                if (attempt.createGeneration != generation) {
                    throw IOException("Wi-Fi P2P createGroup attempt was superseded")
                }
                when (val outcome = attempt.createOutcome) {
                    CreateGroupOutcome.Success -> return
                    is CreateGroupOutcome.Failure -> throw CreateGroupException(
                        reason = outcome.reason,
                        reasonDescription = failureReason(outcome.reason),
                    )
                    null -> Unit
                }

                val remainingNanos = remainingNanos(deadlineNanos)
                if (remainingNanos <= 0) {
                    throw IOException(
                        "Timed out after ${timeoutMillis}ms waiting for Wi-Fi P2P group creation",
                    )
                }
                waitNanos(remainingNanos)
            }
        }
    }

    private fun ensureNoPreexistingGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        deadlineNanos: Long,
    ) {
        val inspection = inspectCurrentGroup(
            attempt = attempt,
            channel = channel,
            timeoutNanos = minOf(remainingNanos(deadlineNanos), INITIAL_GROUP_INSPECTION_NANOS),
        )
        when (decideWifiP2pStaleGroupRecovery(inspection, startActive = true)) {
            WifiP2pStaleGroupDecision.PROCEED_WITH_CREATE -> Unit
            WifiP2pStaleGroupDecision.PRESERVE_PREEXISTING_GROUP -> throw IOException(
                "A pre-existing Wi-Fi P2P group is active; ownership is unproven, preserving it",
            )
            WifiP2pStaleGroupDecision.INSPECTION_UNPROVEN -> throw IOException(
                "Could not prove the Wi-Fi P2P interface has no pre-existing group",
            )
            WifiP2pStaleGroupDecision.CANCEL_START -> ensureStartActive(attempt)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun inspectCurrentGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): WifiP2pGroupInspection {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(channel) { group ->
            result.set(group)
            latch.countDown()
        }
        if (!awaitWhileStartActive(latch, timeoutNanos, attempt)) {
            return WifiP2pGroupInspection.timedOut()
        }
        ensureStartActive(attempt)
        val group = result.get()
        return WifiP2pGroupInspection.completed(group?.let(::observeGroup))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun awaitUsableGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        credentials: WifiP2pCredentials,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        while (true) {
            ensureStartActive(attempt)
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for a usable Wi-Fi P2P group: " +
                        lastReason,
                )
            }

            val group = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(remainingNanos, REQUEST_POLL_NANOS),
            )
            if (group == null) {
                awaitNextGroupPoll(attempt, deadlineNanos)
                continue
            }
            if (!group.isGroupOwner) {
                throw IOException("Wi-Fi P2P device became a group client instead of owner")
            }

            val networkName = group.networkName?.takeIf { it.isNotBlank() }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
            if (!matchesWifiP2pStartCredentials(credentials, networkName, passphrase)) {
                lastReason = "observed group credentials do not match this start attempt"
                awaitNextGroupPoll(attempt, deadlineNanos)
                continue
            }
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                val fingerprint = observeGroup(group)
                attempt.acceptedGroup = fingerprint
                attempt.acceptedExactGroupEverObserved =
                    classifyWifiP2pRetirementGroup(credentials, fingerprint, fingerprint) ==
                    WifiP2pRetirementGroupState.EXACT
            }
            val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
            val frequencyMHz = group.frequency
            val channelNumber = wifiFrequencyMhzToChannel(frequencyMHz)
            if (
                networkName == null ||
                interfaceName == null ||
                frequencyMHz <= 0 ||
                channelNumber == null
            ) {
                lastReason = "networkName=$networkName interface=$interfaceName " +
                    "frequencyMHz=$frequencyMHz"
                awaitNextGroupPoll(attempt, deadlineNanos)
                continue
            }
            if (!is5Ghz(frequencyMHz)) {
                throw IOException(
                    "Wi-Fi P2P created the group at ${frequencyMHz}MHz instead of 5 GHz",
                )
            }

            val groupOwnerAddress = try {
                requestConnectionAddress(
                    attempt = attempt,
                    channel = channel,
                    timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
                )
            } catch (failure: RuntimeException) {
                // The optional owner hint must not break an already observed group interface.
                Log.w(TAG, "Wi-Fi P2P owner IP hint unavailable", failure)
                null
            }
            val addresses = interfaceAddresses(interfaceName, groupOwnerAddress)
            if (addresses == null) {
                lastReason = "interface $interfaceName has no usable IPv6 or IPv4 address"
                awaitNextGroupPoll(attempt, deadlineNanos)
                continue
            }

            return WirelessHotspotInfo(
                ssid = networkName,
                passphrase = requireNotNull(passphrase),
                security = groupSecurity(group),
                channel = channelNumber,
                frequencyMHz = frequencyMHz,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = addresses.primary,
                bandLabel = "5 GHz",
                backend = WirelessHotspotBackend.WIFI_P2P,
                secondaryHostAddress = addresses.secondary,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun requestGroupInfo(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!awaitWhileStartActive(latch, timeoutNanos, attempt)) return null
        ensureStartActive(attempt)
        return result.get()
    }

    private fun observeGroup(group: WifiP2pGroup): WifiP2pObservedGroup = WifiP2pObservedGroup(
        isGroupOwner = group.isGroupOwner,
        networkName = group.networkName,
        passphrase = group.passphrase,
        ownerDeviceAddress = group.owner?.deviceAddress,
        interfaceName = group.getInterface(),
        networkId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) group.networkId else null,
    )

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!awaitWhileStartActive(latch, timeoutNanos, attempt)) return null
        ensureStartActive(attempt)
        val info = result.get() ?: return null
        if (!info.groupFormed) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }

    private fun awaitWhileStartActive(
        latch: CountDownLatch,
        timeoutNanos: Long,
        attempt: StartAttempt,
    ): Boolean {
        val deadlineNanos = deadlineAfterNanos(timeoutNanos.coerceAtLeast(0L))
        while (true) {
            ensureStartActive(attempt)
            if (latch.count == 0L) return true
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0L) return latch.count == 0L
            try {
                if (
                    latch.await(
                        minOf(remainingNanos, CALLBACK_WAIT_POLL_NANOS),
                        TimeUnit.NANOSECONDS,
                    )
                ) {
                    ensureStartActive(attempt)
                    return true
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
            }
        }
    }

    private fun awaitNextGroupPoll(attempt: StartAttempt, deadlineNanos: Long) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
            paceWifiP2pGroupRetry(remainingNanos(deadlineNanos), ::waitNanos)
            ensureStartActiveLocked(attempt)
        }
    }

    private fun interfaceAddresses(
        interfaceName: String,
        groupOwnerAddress: InetAddress?,
    ): WifiP2pInterfaceAddresses? {
        val networkInterface = networkInterface(interfaceName) ?: return null
        return selectWifiP2pInterfaceAddresses(
            groupInterfaceName = interfaceName,
            addressInterfaceName = networkInterface.name,
            interfaceIndex = networkInterface.index,
            addresses = Collections.list(networkInterface.inetAddresses),
            groupOwnerAddress = groupOwnerAddress,
        )
    }

    private fun interfaceHardwareAddress(interfaceName: String): String? =
        networkInterface(interfaceName)
            ?.hardwareAddress
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun networkInterface(interfaceName: String): NetworkInterface? = try {
        NetworkInterface.getByName(interfaceName)
    } catch (_: SocketException) {
        null
    }

    private fun groupSecurity(group: WifiP2pGroup): Iap2WirelessSecurity {
        if (Build.VERSION.SDK_INT < 36) return Iap2WirelessSecurity.WPA_WPA2
        return when (group.securityType) {
            WifiP2pGroup.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
            WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY ->
                Iap2WirelessSecurity.WPA3_TRANSITION
            WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
            // Vendor frameworks can leave the type unset (-1) even after a successful
            // createGroup. Groups created here always use a WPA2-PSK passphrase, so an
            // unreported type must not fail the start and tear the group down.
            else -> {
                Log.w(
                    TAG,
                    "Unreported Wi-Fi P2P security type ${group.securityType}, assuming WPA2-PSK",
                )
                Iap2WirelessSecurity.WPA_WPA2
            }
        }
    }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("WifiP2pGroupManager closed while starting")
        if (startAttempt !== attempt) throw IOException("Wi-Fi P2P startup was cancelled")
        if (attempt.stopped) throw IOException("Wi-Fi P2P group stopped before startup completed")
        attempt.terminalFailure?.let { throw it }
    }

    private fun cleanupFailedStart(attempt: StartAttempt) {
        synchronized(stateLock) {
            attempt.stopped = true
            attempt.startExited = true
            if (startAttempt === attempt) startAttempt = null
            stateLock.notifyAll()
        }
        try {
            scheduleRetirement(attempt)
        } catch (failure: Exception) {
            // The exact attempt remains retained. A later close retries worker construction.
            Log.w(TAG, "Wi-Fi P2P failed-start cleanup worker could not start", failure)
        }
    }

    private fun scheduleRetirement(attempt: StartAttempt) {
        val worker = synchronized(stateLock) {
            check(retirementAttempt == null || retirementAttempt === attempt) {
                "Another Wi-Fi P2P cleanup owner is still active"
            }
            retirementAttempt = attempt
            if (attempt.retired || attempt.retirementRunning) null else {
                attempt.retirementRunning = true
                Thread({ retireAttemptUntilProven(attempt) }, "xcertplay-wifi-p2p-retire").apply {
                    isDaemon = true
                }
            }
        } ?: return
        try {
            worker.start()
        } catch (failure: RuntimeException) {
            synchronized(stateLock) {
                attempt.retirementRunning = false
                stateLock.notifyAll()
            }
            throw IOException("Could not start Wi-Fi P2P cleanup worker", failure)
        }
    }

    private fun awaitRetirementIfOffMainThread(attempt: StartAttempt) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        val deadline = deadlineAfter(RETIREMENT_CLOSE_WAIT_MILLIS)
        synchronized(stateLock) {
            while (!attempt.retired && remainingNanos(deadline) > 0) {
                try {
                    stateLock.wait(
                        minOf(
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadline)).coerceAtLeast(1),
                            100L,
                        ),
                    )
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun retireAttemptUntilProven(attempt: StartAttempt) {
        var lastRemoval = WifiP2pRetirementRemovalOutcome.NOT_ATTEMPTED
        try {
            while (true) {
                val state = synchronized(stateLock) {
                    if (retirementAttempt !== attempt || attempt.retired) return
                    RetirementSnapshot(
                        startExited = attempt.startExited,
                        createIssued = attempt.createIssued,
                        createOutcome = attempt.createOutcome,
                        channelDisconnected = attempt.channelDisconnected,
                    )
                }
                if (!state.startExited) {
                    if (!waitForRetirementProgress()) return
                    continue
                }
                if (retainWifiP2pOwnerOnChannelLoss(
                    state.createIssued,
                    state.channelDisconnected,
                )) {
                    reconcileDisconnectedChannel(attempt)
                    return
                }
                if (!state.createIssued) {
                    finishRetirement(attempt)
                    return
                }
                if (state.createOutcome == null) {
                    if (!waitForRetirementProgress(RETIREMENT_UNCERTAIN_RETRY_MILLIS)) return
                    continue
                }
                val activeChannel = attempt.channel
                if (activeChannel == null) {
                    if (!waitForRetirementProgress(RETIREMENT_UNCERTAIN_RETRY_MILLIS)) return
                    continue
                }
                val inspection = inspectRetirementGroup(activeChannel)
                if (!inspection.completed) {
                    if (!waitForRetirementProgress(RETIREMENT_UNCERTAIN_RETRY_MILLIS)) return
                    continue
                }
                val observed = inspection.group
                if (state.createOutcome == CreateGroupOutcome.Success && attempt.acceptedGroup == null &&
                    observed?.isGroupOwner == true &&
                    matchesWifiP2pStartCredentials(
                        attempt.credentials,
                        observed.networkName,
                        observed.passphrase,
                    )
                ) {
                    // A close can race the very first post-create groupInfo. Capture its stable
                    // fingerprint only after a second identical read on this live channel.
                    val confirmation = inspectRetirementGroup(activeChannel)
                    if (confirmation.completed && confirmation.group == observed) {
                        synchronized(stateLock) {
                            if (retirementAttempt === attempt && attempt.acceptedGroup == null) {
                                attempt.acceptedGroup = observed
                            }
                        }
                    }
                }
                val groupState = classifyWifiP2pRetirementGroup(
                    expected = attempt.credentials,
                    acceptedFingerprint = attempt.acceptedGroup,
                    observed = observed,
                )
                if (groupState == WifiP2pRetirementGroupState.EXACT) {
                    attempt.acceptedExactGroupEverObserved = true
                }
                val decision = decideWifiP2pRetirement(
                    createOutcome = if (state.createOutcome == CreateGroupOutcome.Success) {
                        WifiP2pRetirementCreateOutcome.SUCCEEDED
                    } else {
                        WifiP2pRetirementCreateOutcome.FAILED
                    },
                    groupState = groupState,
                    lastRemoval = lastRemoval,
                    acceptedExactGroupEverObserved = attempt.acceptedExactGroupEverObserved,
                )
                when (decision) {
                    WifiP2pRetirementDecision.RETIRED -> {
                        // A callback may precede groupInfo settling. Require a second fresh
                        // observation before releasing the only callback Looper/owner.
                        if (!waitForRetirementProgress()) return
                        val absenceConfirmation = inspectRetirementGroup(activeChannel)
                        if (!absenceConfirmation.completed ||
                            classifyWifiP2pRetirementGroup(
                                expected = attempt.credentials,
                                acceptedFingerprint = attempt.acceptedGroup,
                                observed = absenceConfirmation.group,
                            ) != WifiP2pRetirementGroupState.ABSENT
                        ) {
                            continue
                        }
                        finishRetirement(attempt)
                        return
                    }
                    WifiP2pRetirementDecision.RETAIN -> {
                        synchronized(stateLock) {
                            attempt.retirementUncertainReason =
                                "Wi-Fi P2P create/remove result has no exact group-absence proof; " +
                                    "owner retained for retry"
                        }
                        if (!waitForRetirementProgress(RETIREMENT_UNCERTAIN_RETRY_MILLIS)) return
                        continue
                    }
                    WifiP2pRetirementDecision.REMOVE_EXACT_GROUP -> Unit
                }
                val confirmation = inspectRetirementGroup(activeChannel)
                if (!confirmation.completed || confirmation.group != inspection.group) {
                    if (!waitForRetirementProgress()) return
                    continue
                }
                if (synchronized(stateLock) { attempt.channelDisconnected }) {
                    reconcileDisconnectedChannel(attempt)
                    return
                }
                // Android's removeGroup targets the Channel, not a group ID. Even two identical
                // snapshots cannot exclude a same-fingerprint replacement in this TOCTOU gap.
                val removal = removeGroupForRetirement(activeChannel)
                lastRemoval = when (removal) {
                    P2pActionOutcome.Success -> WifiP2pRetirementRemovalOutcome.SUCCEEDED
                    is P2pActionOutcome.Failure -> WifiP2pRetirementRemovalOutcome.FAILED
                    P2pActionOutcome.Uncertain -> WifiP2pRetirementRemovalOutcome.TIMED_OUT
                }
                if (removal != P2pActionOutcome.Success) {
                    if (!waitForRetirementProgress(RETIREMENT_UNCERTAIN_RETRY_MILLIS)) return
                    continue
                }
                // Success is not a removal proof. Re-probe until the exact group is absent.
                if (!waitForRetirementProgress()) return
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P cleanup remains retryable after worker failure", failure)
        } finally {
            synchronized(stateLock) {
                attempt.retirementRunning = false
                stateLock.notifyAll()
            }
        }
    }

    private data class RawGroupInspection(
        val completed: Boolean,
        val group: WifiP2pGroup?,
    )

    private fun inspectRawGroup(channel: WifiP2pManager.Channel): RawGroupInspection {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        try {
            p2pManager.requestGroupInfo(channel) { group ->
                result.set(group)
                latch.countDown()
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P cleanup group probe failed", failure)
            return RawGroupInspection(completed = false, group = null)
        }
        val completed = try {
            latch.await(REQUEST_POLL_NANOS, TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return RawGroupInspection(completed, if (completed) result.get() else null)
    }

    private fun inspectRetirementGroup(channel: WifiP2pManager.Channel): WifiP2pGroupInspection {
        val inspection = inspectRawGroup(channel)
        return if (inspection.completed) {
            WifiP2pGroupInspection.completed(inspection.group?.let(::observeGroup))
        } else {
            WifiP2pGroupInspection.timedOut()
        }
    }

    private fun removeGroupForRetirement(channel: WifiP2pManager.Channel): P2pActionOutcome {
        val result = AtomicReference<P2pActionOutcome>()
        val latch = CountDownLatch(1)
        try {
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    result.set(P2pActionOutcome.Success)
                    latch.countDown()
                }

                override fun onFailure(reason: Int) {
                    result.set(P2pActionOutcome.Failure(reason))
                    latch.countDown()
                }
            })
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P removeGroup could not be issued", failure)
            return P2pActionOutcome.Uncertain
        }
        val completed = try {
            latch.await(REMOVE_GROUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (completed) result.get() ?: P2pActionOutcome.Uncertain
        else P2pActionOutcome.Uncertain
    }

    private fun waitForRetirementProgress(
        delayMillis: Long = RETIREMENT_RETRY_MILLIS,
    ): Boolean = synchronized(stateLock) {
        try {
            stateLock.wait(delayMillis)
            true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun finishRetirement(attempt: StartAttempt): Boolean {
        synchronized(stateLock) {
            if (retirementAttempt !== attempt) return false
            if (retainWifiP2pOwnerOnChannelLoss(
                    attempt.createIssued,
                    attempt.channelDisconnected,
                )
            ) {
                attempt.retirementUncertainReason =
                    "Wi-Fi P2P Channel disconnected before cleanup proof; owner retained"
                return false
            }
            attempt.retired = true
            retirementAttempt = null
            if (startAttempt === attempt) startAttempt = null
            if (activeAttempt === attempt) activeAttempt = null
            if (channel === attempt.channel) channel = null
            if (callbackThread === attempt.thread) callbackThread = null
            stateLock.notifyAll()
        }
        attempt.thread?.takeIf { it.isAlive }?.quitSafely()
        return true
    }

    private fun reconcileDisconnectedChannel(attempt: StartAttempt) {
        val disconnectedChannel = attempt.channel
        runBoundedDisconnectedP2pProbes(
            maxProbes = DISCONNECTED_CHANNEL_PROBE_LIMIT,
            probe = {
                if (disconnectedChannel != null) {
                    // Diagnostics only: a callback on this disconnected Channel cannot prove
                    // that a late createGroup did not materialize. Never remove/mark clean.
                    inspectRawGroup(disconnectedChannel)
                }
            },
            pauseBetween = { waitForRetirementProgress(RETIREMENT_RETRY_MILLIS) },
        )
        synchronized(stateLock) {
            attempt.retirementUncertainReason =
                "Wi-Fi P2P Channel disconnected during create/remove; reset Wi-Fi Direct " +
                    "externally and retry teardown"
            stateLock.notifyAll()
        }
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val delta = if (timeoutMillis > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            Long.MAX_VALUE
        } else {
            timeoutMillis * NANOS_PER_MILLISECOND
        }
        return deadlineAfterNanos(delta)
    }

    private fun deadlineAfterNanos(timeoutNanos: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutNanos.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun failureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P is unsupported"
        WifiP2pManager.BUSY -> "Wi-Fi P2P is busy"
        WifiP2pManager.ERROR -> "generic error"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
        else -> "reason $reason"
    }

    private fun is5Ghz(frequencyMHz: Int): Boolean = frequencyMHz in 5150..5895

    private class StartAttempt(
        val credentials: WifiP2pCredentials,
    ) {
        var channel: WifiP2pManager.Channel? = null
        var thread: HandlerThread? = null
        var createGeneration = 0L
        var createIssued = false
        var createOutcome: CreateGroupOutcome? = null
        var acceptedGroup: WifiP2pObservedGroup? = null
        var acceptedExactGroupEverObserved = false
        var publishedInfo: WirelessHotspotInfo? = null
        var channelDisconnected = false
        var retirementUncertainReason: String? = null
        var terminalFailure: IOException? = null
        var stopped = false
        var startExited = false
        var retirementRunning = false
        var retired = false
    }

    private data class RetirementSnapshot(
        val startExited: Boolean,
        val createIssued: Boolean,
        val createOutcome: CreateGroupOutcome?,
        val channelDisconnected: Boolean,
    )

    private sealed interface CreateGroupOutcome {
        object Success : CreateGroupOutcome

        data class Failure(val reason: Int) : CreateGroupOutcome
    }

    private sealed interface P2pActionOutcome {
        object Success : P2pActionOutcome

        data class Failure(val reason: Int) : P2pActionOutcome

        object Uncertain : P2pActionOutcome
    }

    private class CreateGroupException(
        val reason: Int,
        reasonDescription: String,
    ) : IOException("Wi-Fi P2P createGroup failed: $reasonDescription")

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REMOVE_GROUP_TIMEOUT_MILLIS = 2_000L
        const val RETIREMENT_CLOSE_WAIT_MILLIS = 4_000L
        const val RETIREMENT_RETRY_MILLIS = 250L
        const val RETIREMENT_UNCERTAIN_RETRY_MILLIS = 2_000L
        const val DISCONNECTED_CHANNEL_PROBE_LIMIT = 3
        val INITIAL_GROUP_INSPECTION_NANOS: Long = TimeUnit.SECONDS.toNanos(2)
        val REQUEST_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
        val CALLBACK_WAIT_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(50)
    }
}

private const val WIFI_P2P_SSID_PREFIX = "DIRECT-xcertplay"
private const val MFI_CERTIFICATE_SSID_SUFFIX_LENGTH = 4
private const val MFI_CERTIFICATE_PASSPHRASE_LENGTH = 8
private const val HEX_DIGITS = "0123456789abcdef"
