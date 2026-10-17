package com.shilapi.xcertplay.orchestration

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionCapabilityOverlayFactory
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineRootOwner
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncAccessoryCommand
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnostic
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.message.Iap2HidMessages
import com.shilapi.xcertplay.iap2.message.Iap2MediaRemoteCommand
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingAccumulator
import com.shilapi.xcertplay.iap2.session.Iap2FileTransferReceiver
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayMediaSessionBridge
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationAttempt
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.RemoteMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.LocalOnlyHotspotManager
import com.shilapi.xcertplay.network.ManualHotspotManager
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.mfiCertificateWifiP2pCredentials
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Ch341DeviceMatcher
import com.shilapi.xcertplay.transport.Ch341I2cTransport
import com.shilapi.xcertplay.transport.Ch341UsbHost
import com.shilapi.xcertplay.transport.Ch341UsbSession
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.Iap2WiredCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WiredControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlResult
import com.shilapi.xcertplay.transport.Iap2WirelessControlTerminal
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/** One Bluetooth bootstrap may send 0x4301 only while its exact receipt and transport own it. */
internal fun canSendWirelessBootstrapStartSession(
    bootstrapReceipt: Any,
    controllerReceipt: Any?,
    exactMfiSession: Boolean,
    currentControl: Boolean,
    receiptLive: Boolean,
    handoffReserved: Boolean,
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    tunnelReady: Boolean,
    wirelessActive: Boolean,
    airPlaySessionActive: Boolean,
): Boolean = controllerReceipt === bootstrapReceipt && exactMfiSession && currentControl &&
    receiptLive && !handoffReserved && !handoffRequested && !tunnelActive && !tunnelReady &&
    !wirelessActive && !airPlaySessionActive

internal fun canReportWirelessHandoffActive(
    bootstrapRetired: Boolean,
    closed: Boolean,
    generationMatches: Boolean,
): Boolean = bootstrapRetired && !closed && generationMatches

/** 0x4301 must never advertise an address for which this attachment has no live listener. */
internal fun boundWirelessEndpointAddresses(
    primary: InetAddress,
    requestedSecondary: InetAddress?,
    boundAddresses: List<InetAddress>,
): List<InetAddress> {
    if (primary !in boundAddresses) {
        throw IOException("Wireless AirPlay primary address was not bound")
    }
    if (boundAddresses.any { it != primary && it != requestedSecondary }) {
        throw IOException("Wireless AirPlay bound an unexpected address")
    }
    val secondary = requestedSecondary?.takeIf { it != primary && it in boundAddresses }
    return listOfNotNull(primary, secondary)
}

/** A newly observed P2P group/address is not the listener's original network attachment. */
internal fun matchesWirelessHotspotAttachment(
    attached: WirelessHotspotInfo,
    current: WirelessHotspotInfo?,
): Boolean = current != null &&
    current.backend == attached.backend &&
    current.ssid == attached.ssid &&
    current.passphrase == attached.passphrase &&
    current.security == attached.security &&
    current.channel == attached.channel &&
    current.frequencyMHz == attached.frequencyMHz &&
    current.bssid == attached.bssid &&
    current.interfaceName == attached.interfaceName &&
    current.hostAddress == attached.hostAddress &&
    current.secondaryHostAddress == attached.secondaryHostAddress

/** Only P2P currently offers a fresh, exact-group query; preserve other backend contracts. */
internal fun wirelessHotspotForControlSend(
    attached: WirelessHotspotInfo,
    liveP2pGroup: () -> WirelessHotspotInfo?,
): WirelessHotspotInfo? = if (attached.backend == WirelessHotspotBackend.WIFI_P2P) {
    liveP2pGroup()
} else {
    attached
}

internal fun canContinuePreMuxOpen(
    closed: Boolean,
    dataPathPhaseMatches: Boolean,
    authenticationGenerationMatches: Boolean,
    mfiGenerationMatches: Boolean,
): Boolean = !closed && dataPathPhaseMatches && authenticationGenerationMatches &&
    mfiGenerationMatches

internal fun canPublishInitialAuthorizationReceipt(
    closed: Boolean,
    exactMfiSession: Boolean,
    identityMatches: Boolean,
    receiptIsCurrent: Boolean,
    receiptIsLiveForAuthorization: Boolean,
    receiptSlotIdle: Boolean,
): Boolean = !closed && exactMfiSession && identityMatches && receiptSlotIdle &&
    (receiptIsCurrent || receiptIsLiveForAuthorization)

/** Runs every retirement proof but admits the successor only when all three phases are clean. */
internal fun runWiredRestartBarrier(
    retirePublished: () -> Unit,
    awaitPriorAttempt: () -> Unit,
    retireLateOwners: () -> Unit,
    startSuccessor: () -> Unit,
) {
    var aggregate: IOException? = null
    listOf(
        "published wired stack" to retirePublished,
        "prior wired attempt" to awaitPriorAttempt,
        "late wired owners" to retireLateOwners,
    ).forEach { (name, action) ->
        try {
            action()
        } catch (failure: Exception) {
            val classified = IOException("$name retirement failed", failure)
            if (aggregate == null) aggregate = classified else aggregate?.addSuppressed(classified)
        }
    }
    aggregate?.let { throw it }
    startSuccessor()
}

/** Serializes wired restarts and lets only the newest generation admit a successor. */
internal class ControllerWiredRestartGate {
    private val stateLock = Any()
    private val runLock = Any()
    private var generation = 0L

    fun nextGeneration(): Long = synchronized(stateLock) {
        generation = Math.addExact(generation, 1L)
        generation
    }

    fun isCurrent(expectedGeneration: Long): Boolean = synchronized(stateLock) {
        generation == expectedGeneration
    }

    fun runIfCurrent(
        expectedGeneration: Long,
        action: (isCurrent: () -> Boolean) -> Unit,
    ): Boolean = synchronized(runLock) {
        if (!isCurrent(expectedGeneration)) return false
        action { isCurrent(expectedGeneration) }
        true
    }
}

/**
 * One-slot exact-identity owner gate.
 *
 * Admission, rejection cleanup, and exact-owner retirement all run under the supplied [lock]. A
 * failed retirement never clears the owner, while a stale retirement for an older identity can
 * never affect its successor.
 */
internal class ControllerExactOwnerGate<T : Any>(private val lock: Any = Any()) {
    private var owner: T? = null

    fun snapshot(): T? = synchronized(lock) { owner }

    /**
     * Publishes [candidate] only while the slot is idle and [isCurrent] still holds. Every rejected
     * candidate is retired before this call returns, without disturbing the exact published owner.
     */
    fun publishOrRetire(
        candidate: T,
        isCurrent: () -> Boolean,
        retireRejected: (T) -> Unit,
    ): Boolean = synchronized(lock) {
        if (owner == null && isCurrent()) {
            owner = candidate
            true
        } else {
            // A duplicate callback carrying the exact published object is not a second resource;
            // closing it here would tear down the live owner.
            if (owner !== candidate) retireRejected(candidate)
            false
        }
    }

    /** Retires [candidate] only if it is still the exact published owner. */
    fun retireIfOwned(candidate: T, retire: (T) -> Unit) = synchronized(lock) {
        if (owner !== candidate) return@synchronized
        retire(candidate)
        check(owner === candidate) { "Exact owner changed during retirement" }
        owner = null
    }

    /** Retires the current exact owner, retaining it when [retire] throws. */
    fun retireCurrent(retire: (T) -> Unit) = synchronized(lock) {
        val resource = owner ?: return@synchronized
        retire(resource)
        check(owner === resource) { "Exact owner changed during retirement" }
        owner = null
    }
}

/** One exact controller-owned resource retirement step. */
internal class ControllerTeardownStage(
    val name: String,
    val retire: () -> Unit,
)

internal sealed interface ControllerTeardownAttempt {
    data object Complete : ControllerTeardownAttempt

    data class RetryableFailure(
        val stageName: String,
        val cause: Exception,
    ) : ControllerTeardownAttempt
}

/** Builds the ownership-sensitive core teardown prefix used by the controller barrier. */
internal fun controllerCoreTeardownStages(
    sealAuthorizationAdmission: (() -> Unit)?,
    retireTransportsAndWorkers: () -> Unit,
    retireMedia: () -> Unit,
    retireAuthorizationRoot: (() -> Unit)?,
    retireDeferredMfi: (() -> Unit)?,
    retireUpdateDataQuarantineRoot: (() -> Unit)? = null,
): List<ControllerTeardownStage> {
    require(
        (sealAuthorizationAdmission == null) == (retireAuthorizationRoot == null) &&
            (sealAuthorizationAdmission == null) == (retireDeferredMfi == null),
    ) {
        "Authorization admission, root, and deferred MFi teardown must be supplied together"
    }
    return buildList {
        sealAuthorizationAdmission?.let { seal ->
            add(ControllerTeardownStage("AirPlay authorization admission seal", seal))
        }
        add(ControllerTeardownStage("controller transports and workers", retireTransportsAndWorkers))
        add(ControllerTeardownStage("media runtime", retireMedia))
        retireUpdateDataQuarantineRoot?.let { retire ->
            add(ControllerTeardownStage("UpdateData quarantine root", retire))
        }
        retireAuthorizationRoot?.let { closeAuthorization ->
            add(ControllerTeardownStage("CarPlay Ultra authorization root", closeAuthorization))
        }
        retireDeferredMfi?.let { closeMfi ->
            add(ControllerTeardownStage("deferred MFi authorization owners", closeMfi))
        }
    }
}

/** Preserve a failed root stage while still attempting its independent cleanup and drain probes. */
internal fun closeAndDrainUpdateDataQuarantineRoot(
    closeRoot: () -> Unit,
    retryFailedCleanups: () -> Unit,
    awaitDrained: (Long) -> Boolean,
    timeoutMillis: Long,
) {
    require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
    var failure: Exception? = null
    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            if (failure == null) failure = error else if (failure !== error) {
                failure?.addSuppressed(error)
            }
        }
    }
    attempt(closeRoot)
    attempt(retryFailedCleanups)
    attempt {
        if (!awaitDrained(timeoutMillis)) {
            throw IOException("UpdateData quarantine root did not drain")
        }
    }
    failure?.let { throw it }
}

/**
 * Success-only controller teardown journal.
 *
 * Completed stages are discarded. A stage that throws an operational [Exception] remains at the
 * head with the exact same closure (and therefore the exact same resource) strongly held for the
 * next request. [Error] is deliberately not caught: the failing attempt remains claimed and the
 * error keeps its normal uncaught-thread propagation semantics.
 */
internal class ControllerTeardownBarrier(stages: List<ControllerTeardownStage>) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val pending = java.util.ArrayDeque(stages)
    private var inFlight = false
    private var complete = stages.isEmpty()

    fun tryBegin(): Boolean = lock.withLock {
        if (complete || inFlight) return false
        inFlight = true
        true
    }

    fun runAttempt(): ControllerTeardownAttempt {
        while (true) {
            val stage = lock.withLock {
                check(inFlight) { "Controller teardown attempt is not claimed" }
                pending.peekFirst() ?: run {
                    complete = true
                    inFlight = false
                    changed.signalAll()
                    return ControllerTeardownAttempt.Complete
                }
            }
            try {
                stage.retire()
            } catch (failure: Exception) {
                lock.withLock {
                    inFlight = false
                    changed.signalAll()
                }
                return ControllerTeardownAttempt.RetryableFailure(stage.name, failure)
            }
            lock.withLock {
                check(pending.peekFirst() === stage) {
                    "Controller teardown stage changed during execution"
                }
                pending.removeFirst()
            }
        }
    }

    /** Releases a claim when the teardown thread itself could not be launched. */
    fun launchFailed() {
        lock.withLock {
            check(inFlight) { "Controller teardown launch failure has no active claim" }
            inFlight = false
            changed.signalAll()
        }
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return try {
            lock.withLock {
                while (!complete && inFlight && remainingNanos > 0L) {
                    remainingNanos = changed.awaitNanos(remainingNanos)
                }
                complete
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    internal fun pendingStageName(): String? = lock.withLock { pending.peekFirst()?.name }

    internal fun isInFlight(): Boolean = lock.withLock { inFlight }
}

/**
 * Fail-closed journal for child close APIs that cannot prove a retry after throwing.
 *
 * Several Android/transport owners mark themselves closed before releasing their final native or
 * socket resource. Calling them again can therefore return normally without proving that the
 * first failed release completed. We still invoke the same child on every retry, but retain and
 * rethrow the first ambiguous failure instead of converting a later no-op into false success.
 */
internal class ControllerAmbiguousRetirementJournal {
    private class Entry(
        val resource: Any,
        val retry: () -> Unit,
        val failure: Exception,
    )

    private val failures = IdentityHashMap<Any, Entry>()

    /**
     * Records a failure observed in a nested owner whose cleanup may already have made retries a
     * no-op. The exact resource and retry remain fail-closed just like a failure seen by [retire].
     */
    fun <T : Any> retainUnprovenFailure(
        resource: T,
        action: (T) -> Unit,
        failure: Exception,
    ) = synchronized(failures) {
        val previous = failures[resource]
        if (previous == null) {
            failures[resource] = Entry(
                resource = resource,
                retry = { action(resource) },
                failure = failure,
            )
        } else if (failure !== previous.failure && previous.failure.suppressed.size < 8) {
            previous.failure.addSuppressed(failure)
        }
    }

    fun <T : Any> retire(resource: T, action: (T) -> Unit) = synchronized(failures) {
        // The child action stays inside this monitor. Otherwise a first caller can mark the child
        // closed and block just before recording its failure while a second caller observes a
        // normal no-op and prematurely drops the exact resource.
        val previous = failures[resource]
        val current = try {
            action(resource)
            null
        } catch (failure: Exception) {
            failure
        }
        if (previous == null && current == null) return@synchronized

        val retained = previous ?: Entry(
            resource = resource,
            retry = { action(resource) },
            failure = checkNotNull(current),
        ).also { failures[resource] = it }
        if (
            current != null && current !== retained.failure &&
            retained.failure.suppressed.size < 8
        ) {
            retained.failure.addSuppressed(current)
        }
        throw retained.failure
    }

    /** Retries every exact child whose earlier failure made later no-op success ambiguous. */
    fun retryFailures() = synchronized(failures) {
        var first: Exception? = null
        failures.values.toList().forEach { entry ->
            try {
                entry.retry()
            } catch (failure: Exception) {
                if (
                    failure !== entry.failure &&
                    entry.failure.suppressed.size < 8
                ) {
                    entry.failure.addSuppressed(failure)
                }
            }
            if (first == null) first = entry.failure
        }
        first?.let { throw it }
    }
}

/**
 * Bounded exact-identity ownership journal for a resource before another controller owner accepts
 * it.
 *
 * A reservation is taken before asynchronous USB discovery is queued. The connected resource is
 * then strongly owned by this journal until either [transfer] returns successfully or [retire]
 * returns successfully. A throwing transfer or retirement restores the exact entry to the owned
 * state. This also prevents teardown from racing a USBMUX handshake against a close of the same
 * pipe.
 */
internal class ControllerExactHandoffJournal<T : Any>(
    maximumOwners: Int,
    private val resourceName: String,
    private val retireResource: (T) -> Unit,
) {
    internal class Reservation internal constructor()

    private enum class EntryState { OWNED, TRANSFERRING, RETIRING }

    private class Entry<T : Any>(
        val resource: T,
        var state: EntryState = EntryState.OWNED,
    )

    private val maximumOwners = maximumOwners.also {
        require(it > 0) { "maximumOwners must be positive" }
    }
    private val lock = Any()
    private val reservations = IdentityHashMap<Reservation, Unit>()
    private val entries = IdentityHashMap<T, Entry<T>>()
    private val queue = java.util.ArrayDeque<Entry<T>>()

    /** Returns null without starting another asynchronous open when the bounded journal is full. */
    fun reserve(): Reservation? = synchronized(lock) {
        if (reservations.size + entries.size >= maximumOwners) return null
        Reservation().also { reservations[it] = Unit }
    }

    /** Releases a reservation for an asynchronous open that produced no session. */
    fun cancel(reservation: Reservation): Boolean = synchronized(lock) {
        reservations.remove(reservation) != null
    }

    /** Converts one exact reservation into ownership of [resource]. */
    fun bind(reservation: Reservation, resource: T): Unit = synchronized(lock) {
        check(reservations.containsKey(reservation)) {
            "$resourceName ownership reservation is not active"
        }
        check(!entries.containsKey(resource)) {
            "$resourceName is already owned"
        }
        check(entries.size < maximumOwners) {
            "$resourceName ownership journal exceeded its reserved bound"
        }
        reservations.remove(reservation)
        val entry = Entry(resource)
        entries[resource] = entry
        queue.addLast(entry)
    }

    /**
     * Runs [accept] while retaining exact ownership. Ownership moves to the returned result only
     * after [accept] returns normally; any throwable restores this journal's owner entry.
     */
    fun <R> transfer(resource: T, accept: (T) -> R): R {
        val entry = claim(resource, EntryState.TRANSFERRING)
        val accepted = try {
            accept(resource)
        } catch (failure: Throwable) {
            restore(entry, EntryState.TRANSFERRING)
            throw failure
        }
        release(entry, EntryState.TRANSFERRING)
        return accepted
    }

    /** Removes [resource] only after its exact retirement action returns normally. */
    fun retire(resource: T) {
        val entry = synchronized(lock) {
            entries[resource] ?: return
        }
        claim(entry, EntryState.RETIRING)
        try {
            retireResource(resource)
        } catch (failure: Throwable) {
            restore(entry, EntryState.RETIRING)
            throw failure
        }
        release(entry, EntryState.RETIRING)
    }

    /** Best final sweep: all exact owners are attempted, while every failed owner remains queued. */
    fun retireAll() {
        val snapshot = synchronized(lock) { queue.map { it.resource } }
        var aggregate: IOException? = null
        snapshot.forEach { resource ->
            try {
                retire(resource)
            } catch (failure: Exception) {
                val classified = IOException("$resourceName retirement failed", failure)
                if (aggregate == null) aggregate = classified else aggregate?.addSuppressed(classified)
            }
        }
        aggregate?.let { throw it }
    }

    internal fun pendingOwnerCount(): Int = synchronized(lock) { entries.size }

    internal fun reservationCount(): Int = synchronized(lock) { reservations.size }

    private fun claim(resource: T, nextState: EntryState): Entry<T> = synchronized(lock) {
        val entry = entries[resource]
            ?: throw IllegalStateException("$resourceName is not owned")
        claim(entry, nextState)
        entry
    }

    private fun claim(entry: Entry<T>, nextState: EntryState) = synchronized(lock) {
        check(entries[entry.resource] === entry) { "$resourceName owner entry is stale" }
        if (entry.state != EntryState.OWNED) {
            throw IOException("$resourceName ownership operation is already in progress")
        }
        entry.state = nextState
    }

    private fun restore(entry: Entry<T>, expectedState: EntryState) = synchronized(lock) {
        check(entries[entry.resource] === entry && entry.state == expectedState) {
            "$resourceName owner entry changed during a failed operation"
        }
        entry.state = EntryState.OWNED
    }

    private fun release(entry: Entry<T>, expectedState: EntryState) = synchronized(lock) {
        check(entries[entry.resource] === entry && entry.state == expectedState) {
            "$resourceName owner entry changed during a successful operation"
        }
        entries.remove(entry.resource)
        check(queue.remove(entry)) { "$resourceName owner queue lost its exact entry" }
    }
}

/**
 * Exact admission references borrowed by both wired and wireless AirPlay attachments.
 * The controller owns the authorization, not the optional per-connection factory; each accepted
 * TCP session owns only the overlay returned by that factory.
 */
internal class ControllerAirPlayAttachmentAdmission(
    authorization: ControllerUltraAuthorizationRuntime?,
    val sessionOverlayFactory: AirPlaySessionCapabilityOverlayFactory?,
) {
    init {
        require(sessionOverlayFactory == null || authorization != null) {
            "A session capability overlay requires controller Ultra authorization"
        }
    }

    val runtimeBinding = authorization?.binding
    val runtimeGeneration = authorization?.generation

    fun <T> forwardToAttachment(
        attach: (
            AirPlaySessionRuntimeBinding?,
            AirPlaySessionRuntimeBinding.Generation?,
            AirPlaySessionCapabilityOverlayFactory?,
        ) -> T,
    ): T = attach(runtimeBinding, runtimeGeneration, sessionOverlayFactory)
}

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shilapi.xcertplay.transport.VehicleStatusProvider? = null,
    private val ultraAuthorization: ControllerUltraAuthorizationRuntime? = null,
    sessionOverlayFactory: AirPlaySessionCapabilityOverlayFactory? = null,
    private val updateDataQuarantineOwner: CarPlayUpdateDataQuarantineRootOwner? = null,
) : Closeable {
    private class WirelessEndpointBinding(
        val identification: Iap2IdentificationConfig,
        val endpoint: Iap2WirelessCarPlayEndpoint,
        val endpointForControlSend: () -> Iap2WirelessCarPlayEndpoint?,
        val mfiSession: MfiSession,
    )

    private data class UiBinding(
        val listener: AirPlaySessionListener,
        val statusReporter: (CarPlayStatus) -> Unit,
        val token: Long,
        val sessionReplayCursor: ControllerUiSessionReplayCursor =
            ControllerUiSessionReplayCursor(),
    )

    private data class OwnedVpnAttachment(
        val service: CarPlayVpnService,
        val token: CarPlayVpnService.AttachmentToken,
    ) : Closeable {
        override fun close() {
            if (
                service.retire(token) !=
                CarPlayVpnService.AttachmentRetirementProof.RETIRED_CLEAN
            ) {
                throw IOException(
                    "AirPlay attachment token is stale; prior service retirement is unproven",
                )
            }
        }
    }

    private class AuthorizedReceiptHandoffReservation(
        val session: MfiSession,
        val predecessor: MfiAuthenticationReceipt,
        val successor: MfiAuthenticationReceipt,
    )

    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        require(ultraAuthorization == null || ultraAuthorization.baselineConfig === airPlayConfig) {
            "Controller Ultra authorization must use the exact controller AirPlay baseline"
        }
    }

    private val airPlayAttachmentAdmission = ControllerAirPlayAttachmentAdmission(
        authorization = ultraAuthorization,
        sessionOverlayFactory = sessionOverlayFactory,
    )

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val bluetoothAdapter =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val iphoneHost = IphoneUsbHost(
        appContext,
        usbManager,
        if (config.iphoneDevices.isNotEmpty()) {
            IphoneUsbMatcher(config.iphoneDevices)
        } else {
            IphoneUsbMatcher.appleVendor()
        },
    )
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val appLinksExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
    ) { command -> Thread(command, "xcertplay-app-links").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLinksControl = ControllerAppLinksControl(
        submit = { command ->
            try {
                appLinksExecutor.execute(command)
                true
            } catch (_: Exception) {
                false
            }
        },
        postToUi = { command -> mainHandler.post(command) },
        scheduleIconTimeout = { delayMillis, command ->
            mainHandler.postDelayed(command, delayMillis)
        },
        iconsEnabled = config.identification.appLinksIconsEnabled,
    )
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    private val wirelessCloseLock = Any()
    private val wiredRestartGate = ControllerWiredRestartGate()
    private val ambiguousRetirements = ControllerAmbiguousRetirementJournal()
    private val preMuxUsbSessions = ControllerExactHandoffJournal<Iap2UsbSession>(
        maximumOwners = MAX_PENDING_PRE_MUX_USB_SESSIONS,
        resourceName = "Pre-mux iAP2 USB session",
        retireResource = { session ->
            ambiguousRetirements.retire(session, Iap2UsbSession::close)
        },
    )
    private val unpublishedMuxHosts = ControllerExactHandoffJournal<Iap2UsbMuxHost>(
        maximumOwners = MAX_PENDING_PRE_MUX_USB_SESSIONS,
        resourceName = "Unpublished USBMUX host",
        retireResource = { host ->
            ambiguousRetirements.retire(host, Iap2UsbMuxHost::close)
        },
    )
    private val unpublishedWiredCsmSessions = ControllerExactHandoffJournal<Iap2Session>(
        maximumOwners = MAX_PENDING_PRE_MUX_USB_SESSIONS,
        resourceName = "Unpublished wired CSM session",
        retireResource = { session ->
            ambiguousRetirements.retire(session, Iap2Session::close)
        },
    )
    private val ownedWorkerLock = Object()
    private val ownedWorkerThreads = IdentityHashMap<Thread, Unit>()
    private var workerRegistrationClosed = false
    private val uiBindingLock = Any()
    private val uiSessionReplay = ControllerUiSessionReplayState()
    @Volatile private var uiBinding: UiBinding? = UiBinding(listener, reportStatus, 0L)
    private var uiBindingGeneration = 0L
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private val mfiGeneration = AtomicInteger(0)
    private val mfiControlGeneration = AtomicLong(0L)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    private var lastReportedStatus: CarPlayStatus? = null
    private var mfiResetLogged = false

    @Volatile private var closed = false
    private var controllerStarted = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var ch341Host: Ch341UsbHost? = null
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var mfiAuthenticationReceipt: MfiAuthenticationReceipt? = null
    private var authorizedReceiptHandoff: AuthorizedReceiptHandoffReservation? = null
    private val deferredMfiAuthenticationAttempts =
        java.util.ArrayDeque<MfiAuthenticationAttempt>()
    private val pendingMfiSessionRetirements = java.util.ArrayDeque<MfiSession>()
    private val pendingMfiConstructionCleanups =
        java.util.ArrayDeque<MfiSessionConstructionCleanupException>()
    private val pendingMfiPreconstructionOwners = java.util.ArrayDeque<Closeable>()
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    /** Survives AirPlay reconnects so a new session is moved to the area the host is showing. */
    @Volatile var viewAreaIndex: Int = airPlayConfig.main.initialViewArea
        private set
    private val viewAreaGeneration = AtomicInteger(0)
    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    private val wirelessTunnelOwner = ControllerExactOwnerGate<Iap2Session>(wirelessCloseLock)
    @Volatile private var activeMediaRemoteSession: Iap2Session? = null
    private val fileTransferReceivers = IdentityHashMap<Iap2Session, Iap2FileTransferReceiver>()
    @Volatile private var wirelessEndpointBinding: WirelessEndpointBinding? = null
    @Volatile private var vpnService: CarPlayVpnService? = null
    @Volatile private var vpnBound = false
    private var vpnBindGeneration = 0L
    private val vpnAttachmentAuthority = CarPlayVpnService.AttachmentAuthority()
    private var ownedVpnAttachment: OwnedVpnAttachment? = null
    private val pendingVpnAttachmentRetirements = java.util.ArrayDeque<OwnedVpnAttachment>()
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val nowPlaying = Iap2NowPlayingAccumulator()

    @Volatile private var permissionCloseable: Closeable? = null
    @Volatile private var attachCloseable: Closeable? = null
    @Volatile private var ch341PermissionCloseable: Closeable? = null
    private var vpnLatch = CountDownLatch(1)
    @Volatile private var teardownBarrier: ControllerTeardownBarrier? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as CarPlayVpnService.LocalBinder).service
            synchronized(lifecycleLock) {
                if (!closed && vpnBound) vpnService = service
            }
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val report = synchronized(lifecycleLock) {
                vpnService = null
                !closed
            }
            if (report) {
                fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
            }
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            val accepted = publishUiSessionUpdate(
                update = {
                    val snapshot = uiSessionReplay.onActive(session) ?: return@publishUiSessionUpdate null
                    activeSession = session
                    snapshot
                },
                deliverEvent = { listener -> listener.onSessionActive(session) },
            )
            if (!accepted) return
            val area = viewAreaIndex
            val token = viewAreaGeneration.get()
            if (area != airPlayConfig.main.initialViewArea) {
                runCatching {
                    touchExecutor.execute {
                        if (!closed && activeSession === session && viewAreaGeneration.get() == token) {
                            session.showViewArea(area)
                        }
                    }
                }
            }
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
        }

        override fun onSessionEnded(session: AirPlaySession) {
            val accepted = publishUiSessionUpdate(
                update = {
                    val snapshot = uiSessionReplay.onEnded(session) ?: return@publishUiSessionUpdate null
                    if (activeSession === session) activeSession = null
                    snapshot
                },
                deliverEvent = { listener -> listener.onSessionEnded(session) },
            )
            debugLog("AirPlay session ended peer=${session.host}")
            if (!accepted) debugLog("Ignored stale AirPlay session-ended callback")
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            publishTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiBinding?.listener?.onDeviceInfo(session, info)
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            publishUiSessionUpdate(
                update = { uiSessionReplay.onHostUiRequested(session) },
                deliverEvent = { listener -> listener.onHostUiRequested(session) },
            )
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiBinding?.listener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ): Closeable? {
        val binding = synchronized(uiBindingLock) {
            if (closed) return null
            uiBindingGeneration = Math.addExact(uiBindingGeneration, 1L)
            UiBinding(listener, reportStatus, uiBindingGeneration).also { next ->
                uiBinding = next
                val replay = uiSessionReplay.snapshot()
                mainHandler.post {
                    if (uiBinding === next) {
                        lastReportedStatus?.let(next.statusReporter)
                        deliverUiSessionReplay(next, replay)
                    }
                }
            }
        }
        return Closeable {
            synchronized(uiBindingLock) {
                if (uiBinding === binding) {
                    uiBinding = null
                    uiBindingGeneration = Math.addExact(uiBindingGeneration, 1L)
                }
            }
        }
    }

    /** Debug Host-only, explicit AppLinks control; no Start is sent on UI attach or CSM handoff. */
    fun attachAppLinksUi(listener: (ControllerAppLinksSnapshot) -> Unit): Closeable? =
        appLinksControl.attachUi(listener)

    fun startAppLinks(
        categoryIds: Set<Int>,
        preferredIconSize: Int? = null,
        expectedOwnerRevision: Long? = null,
    ): Boolean {
        if (closed || !config.identification.appLinksEnabled) return false
        return appLinksControl.start(categoryIds, preferredIconSize, expectedOwnerRevision)
    }

    fun stopAppLinks(): Boolean = appLinksControl.stop()

    /** Debug UI passes a displayed AD01 index, never a caller-supplied bundle identifier. */
    fun requestAppLinksIcon(entryIndex: Int, expectedOwnerRevision: Long): Boolean {
        if (closed || !config.identification.appLinksIconsEnabled) return false
        return appLinksControl.requestIcon(entryIndex, expectedOwnerRevision)
    }

    private fun <T> publishAppLinksOwner(
        channel: Iap2Session,
        mfi: MfiSession,
        controlGeneration: Long,
        isExactChannel: () -> Boolean,
        client: T,
        start: T.(Set<Int>, Int?) -> Boolean,
        stop: T.() -> Boolean,
        requestIcon: T.(Set<String>) -> Boolean,
    ): ControllerAppLinksControl.Owner? {
        if (!config.identification.appLinksEnabled) return null
        val receipt = mfiAuthenticationReceipt ?: return null
        val fileReceiver = synchronized(fileTransferReceivers) {
            fileTransferReceivers[channel]
        } ?: return null
        val owner = ControllerAppLinksControl.Owner(
            sessionIdentity = channel,
            isCurrentAndAuthorized = {
                !closed && !channel.isClosed && mfiSession === mfi &&
                    mfiAuthenticationReceipt === receipt &&
                    receipt.authenticationIdentity == mfi.authenticationIdentity &&
                    (receipt.isCurrentAuthenticationAttempt() || receipt.isLiveForUltraActivation()) &&
                    mfiControlGeneration.get() == controlGeneration && isExactChannel()
            },
            start = { categoryIds, preferredIconSize -> client.start(categoryIds, preferredIconSize) },
            stop = { client.stop() },
            prepareIconRequest = fileReceiver::prepareIconRequest,
            requestIcon = { bundleIdentifier -> client.requestIcon(setOf(bundleIdentifier)) },
            grantIconTransfer = fileReceiver::grantIconTransfer,
            revokeIconRequest = fileReceiver::revokeIconRequest,
        )
        return owner.takeIf(appLinksControl::publish)
    }

    /** Records and queues updates in one order; listener code never runs while holding the lock. */
    private fun publishUiSessionUpdate(
        update: () -> ControllerUiSessionSnapshot?,
        deliverEvent: (AirPlaySessionListener) -> Unit,
    ): Boolean = synchronized(uiBindingLock) {
        publishUiSessionUpdateLocked(update, deliverEvent)
    }

    /** Caller holds [uiBindingLock]; this records state and only enqueues listener delivery. */
    private fun publishUiSessionUpdateLocked(
        update: () -> ControllerUiSessionSnapshot?,
        deliverEvent: (AirPlaySessionListener) -> Unit,
    ): Boolean {
        val snapshot = update() ?: return false
        val binding = uiBinding ?: return true
        mainHandler.post {
            deliverLiveUiSessionUpdate(binding, snapshot, deliverEvent)
        }
        return true
    }

    private fun publishTransportError(message: String) {
        publishUiSessionUpdate(
            update = { uiSessionReplay.onTransportError(message) },
            deliverEvent = { listener -> listener.onTransportError(message) },
        )
    }

    private fun publishTransportErrorIfCurrent(
        expectedSession: AirPlaySession,
        expectedViewAreaToken: Int,
        message: String,
    ): Boolean = recordExpectedSessionFailure(
        lock = uiBindingLock,
        expectedSession = expectedSession,
        expectedViewAreaToken = expectedViewAreaToken,
        readState = {
            ControllerUiExpectedSessionState(
                closed = closed,
                activeSession = activeSession,
                viewAreaToken = viewAreaGeneration.get(),
            )
        },
        recordFailure = {
            publishUiSessionUpdateLocked(
                update = { uiSessionReplay.onTransportError(message) },
                deliverEvent = { listener -> listener.onTransportError(message) },
            )
        },
    )

    /** Live delivery uses its event form unless this is the binding's first observed revision. */
    private fun deliverLiveUiSessionUpdate(
        binding: UiBinding,
        snapshot: ControllerUiSessionSnapshot,
        deliverEvent: (AirPlaySessionListener) -> Unit,
    ) {
        if (uiBinding !== binding) return
        val previousRevision = binding.sessionReplayCursor.acceptAfter(snapshot) ?: return
        if (uiBinding !== binding) return
        if (requiresCompleteUiSessionReplay(previousRevision, snapshot)) {
            replayControllerUiSessionSnapshot(binding.listener, snapshot)
        } else {
            deliverEvent(binding.listener)
        }
    }

    private fun deliverUiSessionReplay(
        binding: UiBinding,
        snapshot: ControllerUiSessionSnapshot,
    ) {
        if (uiBinding !== binding || !binding.sessionReplayCursor.accept(snapshot)) return
        if (uiBinding !== binding) return
        replayControllerUiSessionSnapshot(binding.listener, snapshot)
    }

    fun isClosed(): Boolean = closed

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && ownedVpnAttachment?.let { it.service.isAttached(it.token) } == true
    }

    fun start() {
        synchronized(this) {
            if (closed || controllerStarted) return
            controllerStarted = true
            CarPlayMediaSessionBridge.attach(appContext, this, ::sendMediaRemoteCommand)
            if (config.transport == CarPlayTransport.WIRED) {
                permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
                attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
            }
            startMfi()
        }
    }

    /** Reopens the CH341/MFi path without restarting the app. */
    fun reconnectMfi() {
        synchronized(this) {
            if (closed) return
            if (ultraAuthorization != null) {
                // One authorized helper owns one immutable binding generation. Reconnect requires
                // a new controller/helper rather than invalidating its receipt behind live sessions.
                close()
                return
            }
            invalidateMfiAuthenticationAttempts()
            closeMfiSession()
            if (config.transport == CarPlayTransport.WIRED) {
                restartWired(
                    retireMfi = true,
                    startSuccessor = ::startMfi,
                )
            } else {
                startMfi()
            }
        }
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() {
        synchronized(this) {
            if (closed) return
            if (ultraAuthorization != null) {
                close()
                return
            }
            invalidateMfiAuthenticationAttempts()
            if (mfiSession == null) {
                if (config.transport == CarPlayTransport.WIRED) {
                    restartWired(
                        retireMfi = true,
                        startSuccessor = ::startMfi,
                    )
                } else {
                    startMfi()
                }
            } else if (config.transport == CarPlayTransport.WIRELESS) {
                restartWireless()
            } else {
                restartWired(
                    retireMfi = false,
                    startSuccessor = ::startIphone,
                )
            }
        }
    }

    /** The main display as declared to the phone, including its view areas. */
    val mainDisplay: AirPlayDisplayConfig get() = airPlayConfig.main

    /** Switches only after viewAreas negotiation; the token and session identity discard stale tasks. */
    fun showViewArea(index: Int): Boolean {
        if (closed || index !in airPlayConfig.main.viewAreas.indices) return false
        val session = activeSession ?: return false
        if (!session.acceptsViewAreas()) return false
        val token = synchronized(uiBindingLock) {
            if (closed || activeSession !== session) return false
            viewAreaGeneration.incrementAndGet().also { viewAreaIndex = index }
        }
        return try {
            touchExecutor.execute {
                if (closed || activeSession !== session || viewAreaGeneration.get() != token) return@execute
                if (!session.showViewArea(index)) {
                    val currentFailure = publishTransportErrorIfCurrent(
                        expectedSession = session,
                        expectedViewAreaToken = token,
                        message = "AirPlay view area switch rejected",
                    )
                    if (currentFailure) {
                        debugLog("AirPlay view area $index not accepted by session")
                    } else {
                        debugLog("Ignored stale AirPlay view area $index rejection")
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Queues one per-display UI Sync command on the active authenticated control session. */
    fun sendUiSyncCommand(
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any? = null,
        noAck: Boolean = false,
    ): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session &&
                    !media.sendUiSyncCommand(session, displayId, command, data, noAck)
                ) {
                    debugLog("UI Sync command was not accepted")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendUiSyncCommand(
        displayId: String,
        command: CarPlayUiSyncAccessoryCommand,
        noAck: Boolean = false,
    ): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute {
                if (activeSession === session &&
                    !media.sendUiSyncCommand(session, displayId, command, noAck)
                ) {
                    debugLog("Typed UI Sync command was not accepted")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun drainUiSyncDiagnostics(): List<CarPlayUiSyncRoutingDiagnostic> =
        activeSession?.let(media::drainUiSyncDiagnostics).orEmpty()

    private fun onIap2Incoming(frame: Iap2Frame) {
        if (frame.messageId != NOW_PLAYING_UPDATE) return
        try {
            CarPlayMediaSessionBridge.publish(this, nowPlaying.update(frame))
        } catch (error: RuntimeException) {
            debugLog("Could not decode iAP2 NowPlayingUpdate", error)
        }
    }

    private fun activateMediaRemote(session: Iap2Session) {
        startFileTransferReceiver(session)
        activeMediaRemoteSession = session
        CarPlayMediaSessionBridge.setControlsAvailable(this, true)
    }

    private fun deactivateMediaRemote(session: Iap2Session) {
        stopFileTransferReceiver(session)
        if (activeMediaRemoteSession !== session) return
        activeMediaRemoteSession = null
        CarPlayMediaSessionBridge.setControlsAvailable(this, false)
    }

    private fun startFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) {
            if (fileTransferReceivers.containsKey(session)) return
            Iap2FileTransferReceiver(
                session = session,
                isArtworkCached = { fileTransferId ->
                    CarPlayMediaSessionBridge.hasArtwork(this, fileTransferId)
                },
                onArtwork = { artwork ->
                    CarPlayMediaSessionBridge.publishArtwork(
                        this,
                        artwork.fileTransferId,
                        artwork.bytes,
                    )
                },
                onIcon = appLinksControl::receiveIcon,
                onLog = ::debugLog,
            ).also { fileTransferReceivers[session] = it }
        }
        receiver.start()
    }

    private fun stopFileTransferReceiver(session: Iap2Session) {
        val receiver = synchronized(fileTransferReceivers) { fileTransferReceivers[session] }
            ?: return
        try {
            receiver.close()
            synchronized(fileTransferReceivers) {
                if (fileTransferReceivers[session] === receiver) {
                    fileTransferReceivers.remove(session)
                }
            }
        } catch (failure: Exception) {
            // Keep the exact receiver owned so final controller teardown can retry it.
            debugLog("file-transfer receiver teardown failed; retry retained", failure)
        }
    }

    private fun closeFileTransferReceiversStrict() {
        while (true) {
            val (session, receiver) = synchronized(fileTransferReceivers) {
                fileTransferReceivers.entries.firstOrNull()?.let { it.key to it.value }
            } ?: return
            receiver.close()
            synchronized(fileTransferReceivers) {
                if (fileTransferReceivers[session] === receiver) {
                    fileTransferReceivers.remove(session)
                }
            }
        }
    }

    private fun sendMediaRemoteCommand(command: Iap2MediaRemoteCommand): Boolean {
        if (closed) return false
        val session = activeMediaRemoteSession ?: return false
        if (session.isClosed) return false
        return try {
            touchExecutor.execute {
                if (closed || activeMediaRemoteSession !== session || session.isClosed) return@execute
                try {
                    session.send(Iap2HidMessages.mediaReport(command), MEDIA_REMOTE_SEND_TIMEOUT_MILLIS)
                    // Consumer Control buttons are momentary: hold the press long enough for the
                    // phone to register the 0→1 edge before releasing, otherwise it may debounce
                    // the button as a glitch and never change track.
                    Thread.sleep(HID_REPORT_HOLD_MILLIS)
                    session.send(Iap2HidMessages.releaseMediaButtons(), MEDIA_REMOTE_SEND_TIMEOUT_MILLIS)
                } catch (error: Exception) {
                    debugLog("Could not send iAP2 media command $command", error)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        val barrier = synchronized(this) {
            if (!closed) {
                // Build the complete retry owner before publishing the terminal flag. Ordinary
                // initialization failure therefore leaves the controller open instead of creating
                // an uncloseable `closed == true && teardownBarrier == null` state.
                val authenticationGeneration = nextMfiControlGeneration()
                val candidateBarrier = createTeardownBarrier(authenticationGeneration)
                synchronized(uiBindingLock) {
                    val nextUiBindingGeneration = Math.addExact(uiBindingGeneration, 1L)
                    closed = true
                    wiredRestartGate.nextGeneration()
                    uiBinding = null
                    uiBindingGeneration = nextUiBindingGeneration
                }
                synchronized(ownedWorkerLock) {
                    workerRegistrationClosed = true
                    ownedWorkerLock.notifyAll()
                }
                teardownBarrier = candidateBarrier
                vpnAttachmentAuthority.cancel()
                availabilityPollGeneration.incrementAndGet()
                mfiGeneration.incrementAndGet()
                wirelessGeneration.incrementAndGet()
                permissionPollGeneration += 1
                activeMediaRemoteSession = null
            }
            teardownBarrier ?: return
        }
        appLinksControl.close()
        requestTeardown(barrier)
    }

    /**
     * Waits for every transport/worker and process-owned media/provider resource to retire.
     * An operational failure returns false and leaves the exact failed stage available to a later
     * [close] request; it is never reported as a completed controller teardown.
     */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return teardownBarrier?.awaitClosed(timeoutMillis) == true
    }

    private fun createTeardownBarrier(
        authenticationGeneration: Long,
    ): ControllerTeardownBarrier = ControllerTeardownBarrier(
        buildList {
            add(
                ControllerTeardownStage("AppLinks subscription") {
                    appLinksControl.close()
                    awaitAppLinksCommandsStrict()
                },
            )
            val deferredMfiRetirement = ultraAuthorization != null
            addAll(
                controllerCoreTeardownStages(
                    sealAuthorizationAdmission = ultraAuthorization?.let { authorization ->
                        { authorization.revokeAdmission() }
                    },
                    retireTransportsAndWorkers = {
                        retireControllerSideStrict(
                            authenticationGeneration,
                            deferredMfiRetirement,
                        )
                    },
                    retireMedia = { (media as? Closeable)?.close() },
                    retireUpdateDataQuarantineRoot = updateDataQuarantineOwner?.let { owner ->
                        {
                            closeAndDrainUpdateDataQuarantineRoot(
                                closeRoot = owner::close,
                                retryFailedCleanups = { owner.retryFailedCleanups() },
                                awaitDrained = owner::awaitDrained,
                                timeoutMillis = UPDATE_DATA_QUARANTINE_DRAIN_TIMEOUT_MILLIS,
                            )
                        }
                    },
                    retireAuthorizationRoot = ultraAuthorization?.let { authorization ->
                        { authorization.close() }
                    },
                    retireDeferredMfi = ultraAuthorization?.let {
                        { retireDeferredMfiStrict(authenticationGeneration) }
                    },
                ),
            )
            add(
                ControllerTeardownStage("location provider") {
                    locationProvider?.close()
                },
            )
            add(
                ControllerTeardownStage("vehicle data provider") {
                    vehicleStatusProvider?.close()
                },
            )
            add(
                ControllerTeardownStage("controller terminal state") {
                    activeSession = null
                    activeMediaRemoteSession = null
                    wirelessEndpointBinding = null
                },
            )
        },
    )

    private fun retireControllerSideStrict(
        authenticationGeneration: Long,
        deferMfiRetirement: Boolean,
    ) {
        val actions = buildList<Pair<String, () -> Unit>> {
            add(
                "initial iPhone permission receiver" to {
                    closeOwnedFieldStrict(
                    read = { permissionCloseable },
                    clear = { resource ->
                        if (permissionCloseable === resource) permissionCloseable = null
                    },
                    retire = Closeable::close,
                )
                },
            )
            add(
                "initial iPhone attach receiver" to {
                    closeOwnedFieldStrict(
                    read = { attachCloseable },
                    clear = { resource ->
                        if (attachCloseable === resource) attachCloseable = null
                    },
                    retire = Closeable::close,
                )
                },
            )
            add(
                "initial MFi permission receiver" to {
                    closeOwnedFieldStrict(
                    read = { ch341PermissionCloseable },
                    clear = { resource ->
                        if (ch341PermissionCloseable === resource) ch341PermissionCloseable = null
                    },
                    retire = Closeable::close,
                )
                },
            )
            add(
                "initial file-transfer receivers" to {
                    closeFileTransferReceiversStrict()
                },
            )
            if (!deferMfiRetirement) {
                add(
                    "MFi authentication authority" to {
                        retireMfiAuthentication(authenticationGeneration)
                    },
                )
            }
            addControllerTransportActions("initial", deferMfiRetirement)
            add("controller workers" to ::awaitWorkerTerminationStrict)
            // A worker may publish immediately before observing cancellation. The second sweep is
            // after the worker proof, so a clean aggregate cannot miss that late resource.
            add(
                "final iPhone permission receiver" to {
                    closeOwnedFieldStrict(
                        read = { permissionCloseable },
                        clear = { resource ->
                            if (permissionCloseable === resource) permissionCloseable = null
                        },
                        retire = Closeable::close,
                    )
                },
            )
            add(
                "final iPhone attach receiver" to {
                    closeOwnedFieldStrict(
                        read = { attachCloseable },
                        clear = { resource ->
                            if (attachCloseable === resource) attachCloseable = null
                        },
                        retire = Closeable::close,
                    )
                },
            )
            add(
                "final MFi permission receiver" to {
                    closeOwnedFieldStrict(
                        read = { ch341PermissionCloseable },
                        clear = { resource ->
                            if (ch341PermissionCloseable === resource) ch341PermissionCloseable = null
                        },
                        retire = Closeable::close,
                    )
                },
            )
            add("final file-transfer receivers" to ::closeFileTransferReceiversStrict)
            // No callback can still be opening USBMUX after the worker proof above. Any exact
            // candidate or session whose stale/failure cleanup did not prove retirement is retried.
            add("final unpublished wired CSM sessions" to unpublishedWiredCsmSessions::retireAll)
            add("final unpublished USBMUX hosts" to unpublishedMuxHosts::retireAll)
            add("final pre-mux iAP2 USB sessions" to preMuxUsbSessions::retireAll)
            addControllerTransportActions("final", deferMfiRetirement)
            add(
                "media-session bridge" to {
                    ambiguousRetirements.retire(CarPlayMediaSessionBridge) {
                        it.detach(appContext, this@CarPlayController)
                    }
                },
            )
            add("ambiguous child retirements" to { ambiguousRetirements.retryFailures() })
        }
        runAllControllerTeardownActions(actions)
    }

    private fun MutableList<Pair<String, () -> Unit>>.addControllerTransportActions(
        prefix: String,
        deferMfiRetirement: Boolean,
    ) {
        if (config.transport == CarPlayTransport.WIRELESS) {
            add("$prefix wireless stack" to ::closeWirelessStackStrict)
        } else {
            add(
                "$prefix CSM" to {
                    closeOwnedFieldStrict(
                        read = { csm },
                        clear = { resource -> if (csm === resource) csm = null },
                        retire = Iap2Session::close,
                    )
                },
            )
            add("$prefix VPN/NCM attachment" to ::closeOwnedVpnAttachmentStrict)
        }
        add(
            "$prefix USBMUX" to {
                closeOwnedFieldStrict(
                    read = { mux },
                    clear = { resource -> if (mux === resource) mux = null },
                    retire = Iap2UsbMuxHost::close,
                )
            },
        )
        if (!deferMfiRetirement) {
            add("$prefix MFi construction cleanup" to ::closeMfiConstructionCleanupsStrict)
            add("$prefix MFi preconstruction owners" to ::closeMfiPreconstructionOwnersStrict)
            add("$prefix MFi session" to ::closeMfiSessionStrict)
        }
        add("$prefix VPN service binding" to ::unbindVpnStrict)
    }

    private fun retireDeferredMfiStrict(authenticationGeneration: Long) {
        check(synchronized(lifecycleLock) { authorizedReceiptHandoff == null }) {
            "Authorized receipt handoff is still in flight"
        }
        runAllControllerTeardownActions(
            listOf(
                "MFi authentication authority" to {
                    retireMfiAuthentication(authenticationGeneration)
                },
                "deferred MFi authentication attempts" to
                    ::closeDeferredMfiAuthenticationAttemptsStrict,
                "MFi construction cleanup" to ::closeMfiConstructionCleanupsStrict,
                "MFi preconstruction owners" to ::closeMfiPreconstructionOwnersStrict,
                "MFi session" to ::closeMfiSessionStrict,
            ),
        )
    }

    private fun runAllControllerTeardownActions(actions: List<Pair<String, () -> Unit>>) {
        var aggregate: IOException? = null
        actions.forEach { (name, action) ->
            try {
                action()
            } catch (failure: Exception) {
                val classified = IOException("$name teardown failed", failure)
                if (aggregate == null) aggregate = classified else aggregate?.addSuppressed(classified)
            }
        }
        aggregate?.let { throw it }
    }

    private fun requestTeardown(barrier: ControllerTeardownBarrier) {
        if (!barrier.tryBegin()) return
        try {
            Thread(
                {
                    when (val outcome = barrier.runAttempt()) {
                        ControllerTeardownAttempt.Complete ->
                            debugLog("controller teardown complete")
                        is ControllerTeardownAttempt.RetryableFailure ->
                            debugLog(
                                "${outcome.stageName} teardown failed; retry retained",
                                outcome.cause,
                            )
                    }
                },
                "xcertplay-controller-teardown",
            ).apply {
                isDaemon = true
                start()
            }
        } catch (failure: Exception) {
            barrier.launchFailed()
            debugLog("controller teardown thread could not start; retry retained", failure)
        }
    }

    /** Registers every directly-created controller worker before it can race final teardown. */
    private fun startOwnedWorker(name: String, action: () -> Unit): Boolean {
        lateinit var worker: Thread
        worker = Thread(
            {
                try {
                    action()
                } finally {
                    synchronized(ownedWorkerLock) {
                        ownedWorkerThreads.remove(worker)
                        ownedWorkerLock.notifyAll()
                    }
                }
            },
            name,
        ).apply { isDaemon = true }
        synchronized(ownedWorkerLock) {
            if (workerRegistrationClosed || closed) return false
            ownedWorkerThreads[worker] = Unit
            try {
                worker.start()
            } catch (failure: Throwable) {
                ownedWorkerThreads.remove(worker)
                ownedWorkerLock.notifyAll()
                throw failure
            }
        }
        return true
    }

    private fun awaitWorkerTerminationStrict() {
        val executors = listOf(executor, touchExecutor, tunnelExecutor, appLinksExecutor)
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(EXECUTOR_CLOSE_TIMEOUT_MILLIS)
        executors.forEachIndexed { index, worker ->
            worker.shutdownNow()
            val startedNanos = System.nanoTime()
            val terminated = try {
                worker.awaitTermination(remainingNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while retiring controller worker $index", failure)
            }
            if (!terminated) {
                throw IOException("Controller worker $index did not terminate before the deadline")
            }
            remainingNanos = (remainingNanos - (System.nanoTime() - startedNanos)).coerceAtLeast(0L)
        }
        while (true) {
            val worker = synchronized(ownedWorkerLock) {
                ownedWorkerThreads.keys.firstOrNull()
            } ?: return
            if (remainingNanos <= 0L) {
                throw IOException("Controller-owned worker ${worker.name} did not terminate before the deadline")
            }
            val startedNanos = System.nanoTime()
            try {
                worker.join(
                    remainingNanos / 1_000_000L,
                    (remainingNanos % 1_000_000L).toInt(),
                )
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while retiring controller-owned worker ${worker.name}", failure)
            }
            remainingNanos = (remainingNanos - (System.nanoTime() - startedNanos)).coerceAtLeast(0L)
            if (worker.isAlive) {
                throw IOException("Controller-owned worker ${worker.name} did not terminate before the deadline")
            }
        }
    }

    private fun awaitAppLinksCommandsStrict() {
        val drained = CountDownLatch(1)
        try {
            appLinksExecutor.execute { drained.countDown() }
        } catch (failure: Exception) {
            throw IOException("Could not queue AppLinks subscription retirement proof", failure)
        }
        val completed = try {
            drained.await(APP_LINKS_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while retiring AppLinks subscription", failure)
        }
        if (!completed) throw IOException("AppLinks subscription did not retire before the deadline")
    }

    private fun <T : Any> closeOwnedFieldStrict(
        read: () -> T?,
        clear: (T) -> Unit,
        retire: (T) -> Unit,
    ) {
        val resource = read() ?: return
        ambiguousRetirements.retire(resource, retire)
        clear(resource)
    }

    private fun <T : Any> closeRetryableOwnedFieldStrict(
        read: () -> T?,
        clear: (T) -> Unit,
        retire: (T) -> Unit,
    ) {
        val resource = read() ?: return
        retire(resource)
        clear(resource)
    }

    private fun retireMfiAuthentication(authenticationGeneration: Long) {
        val (session, receipt) = synchronized(lifecycleLock) {
            mfiSession to mfiAuthenticationReceipt
        }
        session?.invalidateAuthenticationAttempts(authenticationGeneration)
        receipt?.revoke()
        synchronized(lifecycleLock) {
            if (mfiAuthenticationReceipt === receipt) mfiAuthenticationReceipt = null
        }
    }

    private fun closeMfiSessionStrict() {
        val session = synchronized(lifecycleLock) { mfiSession }
        if (session != null) {
            ambiguousRetirements.retire(session, MfiSession::close)
            synchronized(lifecycleLock) {
                if (mfiSession === session) {
                    mfiSession = null
                    mfiAuthenticationReceipt = null
                }
            }
        }
        closePendingMfiSessionsStrict()
    }

    private fun closePendingMfiSessionsStrict() {
        while (true) {
            val session = synchronized(lifecycleLock) {
                pendingMfiSessionRetirements.peekFirst()
            } ?: return
            ambiguousRetirements.retire(session, MfiSession::close)
            synchronized(lifecycleLock) {
                if (pendingMfiSessionRetirements.peekFirst() === session) {
                    pendingMfiSessionRetirements.removeFirst()
                }
            }
        }
    }

    /**
     * Keeps an authorization-bearing authentication attempt alive until AirPlay children and the
     * authorization root have retired. Closing it earlier can synchronously revoke the root.
     */
    private fun ownDeferredMfiAuthenticationAttempt(attempt: MfiAuthenticationAttempt) {
        synchronized(lifecycleLock) {
            check(deferredMfiAuthenticationAttempts.size < MAX_PENDING_MFI_CLEANUP_OWNERS) {
                "Too many deferred MFi authentication attempts"
            }
            check(deferredMfiAuthenticationAttempts.none { it === attempt }) {
                "MFi authentication attempt is already deferred"
            }
            deferredMfiAuthenticationAttempts.addLast(attempt)
        }
    }

    private fun closeDeferredMfiAuthenticationAttemptsStrict() {
        while (true) {
            val attempt = synchronized(lifecycleLock) {
                deferredMfiAuthenticationAttempts.peekFirst()
            } ?: return
            ambiguousRetirements.retire(attempt, MfiAuthenticationAttempt::close)
            synchronized(lifecycleLock) {
                if (deferredMfiAuthenticationAttempts.peekFirst() === attempt) {
                    deferredMfiAuthenticationAttempts.removeFirst()
                }
            }
        }
    }

    private fun closeMfiConstructionCleanupsStrict() {
        while (true) {
            val cleanup = synchronized(lifecycleLock) {
                pendingMfiConstructionCleanups.peekFirst()
            } ?: return
            ambiguousRetirements.retire(cleanup, MfiSessionConstructionCleanupException::close)
            synchronized(lifecycleLock) {
                if (pendingMfiConstructionCleanups.peekFirst() === cleanup) {
                    pendingMfiConstructionCleanups.removeFirst()
                }
            }
        }
    }

    private fun closeMfiPreconstructionOwnersStrict() {
        while (true) {
            val owner = synchronized(lifecycleLock) {
                pendingMfiPreconstructionOwners.peekFirst()
            } ?: return
            ambiguousRetirements.retire(owner, Closeable::close)
            synchronized(lifecycleLock) {
                if (pendingMfiPreconstructionOwners.peekFirst() === owner) {
                    pendingMfiPreconstructionOwners.removeFirst()
                }
            }
        }
    }

    private fun retainMfiPreconstructionOwner(owner: Closeable) {
        synchronized(lifecycleLock) {
            if (pendingMfiPreconstructionOwners.none { it === owner }) {
                check(pendingMfiPreconstructionOwners.size < MAX_PENDING_MFI_CLEANUP_OWNERS) {
                    "Too many pending MFi preconstruction cleanup owners"
                }
                pendingMfiPreconstructionOwners.addLast(owner)
            }
        }
    }

    private fun retireOrRetainMfiPreconstructionOwner(
        owner: Closeable,
        primaryFailure: Throwable,
    ) {
        try {
            ambiguousRetirements.retire(owner, Closeable::close)
        } catch (cleanupFailure: Exception) {
            retainMfiPreconstructionOwner(owner)
            if (cleanupFailure !== primaryFailure) primaryFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun startMfi() {
        val generation = synchronized(lifecycleLock) {
            phase = Phase.MFI
            mfiGeneration.incrementAndGet()
        }
        availabilityPollGeneration.incrementAndGet()
        onStatus(CarPlayStatus.DiscoveringMfi)
        when (config.mfiTarget) {
            MfiTarget.USB_CH341 -> {
                debugLog("mfi discovery backend=CH341 devices=${config.ch341Devices}")
                val host = ch341Host ?: Ch341UsbHost(
                    appContext,
                    usbManager,
                    Ch341DeviceMatcher(config.ch341Devices),
                ).also {
                    ch341Host = it
                    ch341PermissionCloseable = it.registerPermissionReceiver(::onCh341Permission)
                }
                checkCh341Mfi(host, generation)
            }
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi(generation)
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi(generation)
            }
            MfiTarget.LOCAL_FILES -> {
                debugLog("mfi discovery backend=Local documents")
                openLocalMfi(generation)
            }
        }
    }

    private fun openLocalMfi(generation: Int) {
        executor.execute {
            try {
                val resolver = appContext.contentResolver
                val certificateUri = Uri.parse(checkNotNull(config.localMfiCertificateUri))
                val privateKeyUri = Uri.parse(checkNotNull(config.localMfiPrivateKeyUri))
                val client = resolver.openInputStream(certificateUri)?.use { certificateInput ->
                    resolver.openInputStream(privateKeyUri)?.use { privateKeyInput ->
                        LocalMfiAuthenticationClient.load(certificateInput, privateKeyInput)
                    } ?: throw IOException("Could not open the selected local MFi private key")
                } ?: throw IOException("Could not open the selected local MFi certificate")
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                val session = MfiSession.open(client, null)
                if (!publishMfiSession(session, generation)) return@execute
                debugLog("mfi local documents ready protocolMajor=$protocolMajor")
                onStatus(CarPlayStatus.MfiReady)
                startPhone(session)
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun openRemoteMfi(generation: Int) {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                val session = MfiSession.open(client, null)
                if (!publishMfiSession(session, generation)) return@execute
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone(session)
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun checkCh341Mfi(host: Ch341UsbHost, generation: Int) {
        if (closed || phase != Phase.MFI || generation != mfiGeneration.get()) return
        val device = host.discover().firstOrNull()
        if (device == null) {
            waitForMfi(generation)
        } else {
            availabilityPollGeneration.incrementAndGet()
            requestCh341Permission(device, generation)
        }
    }

    private fun openLinuxMfi(generation: Int) {
        executor.execute {
            try {
                val transport = LinuxI2cTransport.open(config.linuxI2cPath!!)
                val client = try {
                    MfiRuntime.scan(transport)
                } catch (error: Throwable) {
                    retireOrRetainMfiPreconstructionOwner(transport, error)
                    throw error
                }
                // MfiSession.open takes ownership of transport on both success and failure.
                val session = MfiSession.open(client, transport)
                if (!publishMfiSession(session, generation)) return@execute
                debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone(session)
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi(generation)
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun requestCh341Permission(device: UsbDevice, generation: Int) {
        try {
            when (val request = ch341Host!!.requestPermission(device, generation)) {
                is Ch341UsbHost.PermissionRequest.AlreadyGranted -> {
                    permissionGrant.set(false)
                    onCh341Permission(
                        Ch341UsbHost.PermissionResult.Granted(
                            request.device,
                            request.requestToken,
                        ),
                    )
                }
                is Ch341UsbHost.PermissionRequest.Requested -> {
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingMfiPermission)
                    pollCh341Permission(device, generation)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onCh341Permission(result: Ch341UsbHost.PermissionResult) {
        val generation = result.requestToken
        if (closed || phase != Phase.MFI || generation != mfiGeneration.get()) return
        when (result) {
            is Ch341UsbHost.PermissionResult.Granted -> {
                // The system broadcast and CarUsbHandler's direct grant can both observe success.
                if (!permissionGrant.compareAndSet(false, true)) return
                permissionPollGeneration++
                openCh341(result.device, generation)
            }
            is Ch341UsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("CH341 USB permission was denied"))
            }
        }
    }

    /** Some car systems grant USB access through CarUsbHandler without delivering a broadcast. */
    private fun pollCh341Permission(device: UsbDevice, mfiRequestGeneration: Int) {
        val permissionGeneration = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || phase != Phase.MFI ||
                    permissionGeneration != permissionPollGeneration ||
                    mfiRequestGeneration != mfiGeneration.get()
                ) {
                    return
                }
                if (usbManager.hasPermission(device)) {
                    onCh341Permission(
                        Ch341UsbHost.PermissionResult.Granted(device, mfiRequestGeneration),
                    )
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "MFi USB permission was not granted; reconnect the CH341 to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun openCh341(device: UsbDevice, generation: Int) {
        ch341Host!!.openAsync(device, executor) { result ->
            when (result) {
                is Ch341UsbHost.OpenResult.Connected -> {
                    val session: Ch341UsbSession = result.session
                    var mfiFactoryOwnsSession = false
                    if (closed || phase != Phase.MFI) {
                        try {
                            ambiguousRetirements.retire(session, Ch341UsbSession::close)
                        } catch (cleanupFailure: Exception) {
                            retainMfiPreconstructionOwner(session)
                            fail(cleanupFailure)
                        }
                        return@openAsync
                    }
                    try {
                        val transport = Ch341I2cTransport(session)
                        config.ch341MfiResetGpio?.let { gpio ->
                            transport.pulseActiveLowReset(gpio)
                            if (!mfiResetLogged) {
                                mfiResetLogged = true
                                debugLog("mfi reset pulse gpio=D$gpio mode=low/high-z")
                            }
                        }
                        val client = MfiRuntime.scan(transport)
                        val probes = mfiCandidateAddresses
                            .associateWith { address -> probeMfiCandidate(transport, address) }
                        for ((address, probe) in probes) {
                            debugLog("mfi probe address=0x${address.toString(16)} ${probe.describe()}")
                        }
                        val selected = preferCertificateBearingAddress(client, transport, probes)
                        debugLog(
                            "mfi coprocessor address=0x${selected.address7Bit.toString(16)} " +
                                "protocolMajor=${selected.protocolMajor()}",
                        )
                        // The factory closes the USB session if certificate capture fails.
                        mfiFactoryOwnsSession = true
                        val mfi = MfiSession.open(selected, session)
                        if (!publishMfiSession(mfi, generation)) return@openAsync
                        debugLog("mfi CH341 session ready")
                        onStatus(CarPlayStatus.MfiReady)
                        startPhone(mfi)
                    } catch (error: MfiCoprocessorNotFoundException) {
                        debugLog("mfi CH341 discovery failed: ${error.message}")
                        Log.w(IphoneCarPlayConfiguration.TAG, error.message ?: "MFi discovery failed")
                        if (!mfiFactoryOwnsSession) {
                            retireOrRetainMfiPreconstructionOwner(session, error)
                        }
                        waitForMfi(generation)
                    } catch (error: Throwable) {
                        if (!mfiFactoryOwnsSession) {
                            retireOrRetainMfiPreconstructionOwner(session, error)
                        }
                        fail(error)
                    }
                }
                is Ch341UsbHost.OpenResult.Failed -> when (result.error) {
                    is I2cTransportException.DeviceUnavailable -> waitForMfi(generation)
                    else -> fail(result.error)
                }
            }
        }
    }

    private val mfiCandidateAddresses = listOf(0x10, 0x11)
    private val maxMfiCertificateBytes = 1280

    private data class MfiCandidateProbe(
        val deviceVersion: Int?,
        val firmwareVersion: Int?,
        val protocolMajor: Int?,
        val accessoryCertificateLength: Int?,
        val appleCertificateLength: Int?,
        val failure: String?,
    ) {
        val hasAccessoryCertificate: Boolean
            get() = (accessoryCertificateLength ?: 0) in 1..1280

        fun describe(): String {
            if (failure != null) return failure
            return "deviceVersion=" + hex(deviceVersion) +
                " firmwareVersion=" + hex(firmwareVersion) +
                " protocolMajor=" + hex(protocolMajor) +
                " accessoryCertificateLength=" + accessoryCertificateLength +
                " appleCertificateLength=" + appleCertificateLength
        }

        private fun hex(value: Int?): String =
            if (value == null) "?" else "0x" + value.toString(16).padStart(2, '0')
    }

    private fun probeMfiCandidate(transport: I2cTransport, address7Bit: Int): MfiCandidateProbe = try {
        MfiCandidateProbe(
            deviceVersion = readMfiRegister(transport, address7Bit, 0x00, 1),
            firmwareVersion = readMfiRegister(transport, address7Bit, 0x01, 1),
            protocolMajor = readMfiRegister(transport, address7Bit, 0x02, 1),
            accessoryCertificateLength = readMfiRegister(transport, address7Bit, 0x30, 2),
            appleCertificateLength = readMfiRegister(transport, address7Bit, 0x50, 2),
            failure = null,
        )
    } catch (error: Throwable) {
        MfiCandidateProbe(
            deviceVersion = null,
            firmwareVersion = null,
            protocolMajor = null,
            accessoryCertificateLength = null,
            appleCertificateLength = null,
            failure = "failed: " + error.javaClass.simpleName + ": " + error.message,
        )
    }

    private fun readMfiRegister(
        transport: I2cTransport,
        address7Bit: Int,
        register: Int,
        length: Int,
    ): Int {
        transport.transaction(address7Bit, byteArrayOf(register.toByte()), 0)
        var value = 0
        for (byte in transport.transaction(address7Bit, ByteArray(0), length)) {
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private fun preferCertificateBearingAddress(
        client: MfiAuthenticationClient,
        transport: I2cTransport,
        probes: Map<Int, MfiCandidateProbe>,
    ): MfiAuthenticationClient {
        if (probes[client.address7Bit]?.hasAccessoryCertificate == true) return client
        val alternative = probes.entries.firstOrNull { (address, probe) ->
            address != client.address7Bit && probe.hasAccessoryCertificate
        } ?: return client
        debugLog(
            "mfi address override: 0x" + client.address7Bit.toString(16) +
                " has no accessory certificate; using 0x" + alternative.key.toString(16),
        )
        return MfiAuthenticationClient(transport, alternative.key)
    }

    private fun waitForMfi(generation: Int) {
        if (closed || phase != Phase.MFI || generation != mfiGeneration.get()) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI) {
            when (config.mfiTarget) {
                MfiTarget.USB_CH341 -> ch341Host?.let { checkCh341Mfi(it, generation) }
                MfiTarget.I2C -> openLinuxMfi(generation)
                MfiTarget.REMOTE, MfiTarget.LOCAL_FILES -> Unit
            }
        }
    }

    private fun startPhone(expectedMfiSession: MfiSession? = mfiSession) {
        if (closed || expectedMfiSession == null || mfiSession !== expectedMfiSession) return
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.prewarm() == true
            } catch (error: Throwable) {
                Log.w(
                    IphoneCarPlayConfiguration.TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.WIRELESS
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
        onStatus(CarPlayStatus.StartingHotspot)
        val generation = wirelessGeneration.incrementAndGet()
        val authenticationGeneration = nextMfiControlGeneration()
        executor.execute {
            runWireless(generation, authenticationGeneration)
        }
    }

    private fun restartWireless() {
        wirelessGeneration.incrementAndGet()
        val started = startOwnedWorker("xcertplay-wireless-restart") {
                val retired = closeWirelessStack()
                if (!closed && retired) {
                    startWireless()
                } else if (!closed) {
                    fail(IOException("Previous wireless stack could not retire; retry is required"))
                }
        }
        if (!started && !closed) {
            fail(IOException("Wireless restart worker was rejected"))
        }
    }

    private fun restartWired(
        retireMfi: Boolean,
        startSuccessor: () -> Unit,
    ) {
        val restartGeneration = wiredRestartGate.nextGeneration()
        val started = startOwnedWorker("xcertplay-wired-restart") {
            wiredRestartGate.runIfCurrent(restartGeneration) { isCurrentRestart ->
                try {
                    runWiredRestartBarrier(
                        retirePublished = ::closePublishedWiredStackStrict,
                        awaitPriorAttempt = ::awaitPriorWiredAttemptStrict,
                        retireLateOwners = { closeLateWiredOwnersStrict(retireMfi) },
                        startSuccessor = {
                            if (!closed && isCurrentRestart()) startSuccessor()
                        },
                    )
                } catch (failure: Exception) {
                    if (!closed && isCurrentRestart()) {
                        fail(
                            IOException(
                                "Previous wired stack could not retire; retry is required",
                                failure,
                            ),
                        )
                    }
                }
            }
        }
        if (!started && !closed) {
            fail(IOException("Wired restart worker was rejected"))
        }
    }

    private fun closePublishedWiredStackStrict() {
        runAllControllerTeardownActions(
            listOf(
                "wired CSM" to {
                    closeOwnedFieldStrict(
                        read = { synchronized(lifecycleLock) { csm } },
                        clear = { resource ->
                            synchronized(lifecycleLock) {
                                if (csm === resource) csm = null
                            }
                        },
                        retire = Iap2Session::close,
                    )
                },
                "wired VPN/NCM attachment" to ::closeOwnedVpnAttachmentStrict,
                "wired USBMUX" to {
                    closeOwnedFieldStrict(
                        read = { synchronized(lifecycleLock) { mux } },
                        clear = { resource ->
                            synchronized(lifecycleLock) {
                                if (mux === resource) mux = null
                            }
                        },
                        retire = Iap2UsbMuxHost::close,
                    )
                },
            ),
        )
    }

    private fun awaitPriorWiredAttemptStrict() {
        val drained = CountDownLatch(1)
        try {
            executor.execute { drained.countDown() }
        } catch (failure: Exception) {
            throw IOException("Could not queue the wired-attempt retirement barrier", failure)
        }
        val completed = try {
            drained.await(WIRED_RESTART_DRAIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while awaiting the prior wired attempt", failure)
        }
        if (!completed) {
            throw IOException("Prior wired attempt did not finish before the restart deadline")
        }
    }

    private fun closeLateWiredOwnersStrict(retireMfi: Boolean) {
        runAllControllerTeardownActions(
            buildList {
                add(
                    "late published wired stack" to ::closePublishedWiredStackStrict,
                )
                add("unpublished wired CSM sessions" to unpublishedWiredCsmSessions::retireAll)
                add("unpublished USBMUX hosts" to unpublishedMuxHosts::retireAll)
                add("pre-mux iAP2 USB sessions" to preMuxUsbSessions::retireAll)
                if (retireMfi) {
                    add("retired MFi sessions" to ::closePendingMfiSessionsStrict)
                }
                add(
                    "ambiguous wired child retirements" to {
                        ambiguousRetirements.retryFailures()
                        Unit
                    },
                )
            },
        )
    }

    private fun runWireless(generation: Int, authenticationGeneration: Long) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            if (!closeWirelessStack()) {
                throw IOException("Previous wireless stack could not retire; retry is required")
            }
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val mfiSessionSnapshot = mfiSession
                ?: throw IOException("MFi coprocessor client is unavailable")
            val mfi = mfiSessionSnapshot.client
            val hotspotInfo = startWirelessHotspot(generation, mfi)
            val hotspotOwner = hotspot
                ?: throw IOException("Wireless hotspot owner retired during startup")
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val hostAddress = hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val secondaryHostAddress = hotspotInfo.secondaryHostAddress
            if (secondaryHostAddress != null &&
                (hotspotInfo.backend != WirelessHotspotBackend.WIFI_P2P ||
                    secondaryHostAddress !is Inet4Address)
            ) {
                throw IOException("Only Wi-Fi P2P may provide a verified secondary IPv4 address")
            }
            val deviceIdentifier = hotspotInfo.bssid ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "host=$hostAddressText bssid=${hotspotInfo.bssid ?: "unavailable"} " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                ),
            )
            onStatus(CarPlayStatus.WaitingForPairedIphone)

            val adapter = bluetoothAdapter
                ?: throw IOException("Bluetooth adapter is unavailable")
            if (!adapter.isEnabled) throw IOException("Bluetooth is not enabled")
            val devices = selectWirelessBluetoothDevices(adapter)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            debugLog(
                "wireless Bluetooth PHONE_SMART candidates=${devices.size} " +
                    "localBt=$hostBluetoothMac",
            )
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
                wirelessAudio = true,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            val wirelessAttachmentToken = when (
                val result = airPlayAttachmentAdmission.forwardToAttachment {
                    runtimeBinding, runtimeGeneration, sessionOverlayFactory ->
                    service.attachWireless(
                        bindAddress = hostAddress,
                        secondaryBindAddress = secondaryHostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = sessionListener,
                        media = media,
                        authority = vpnAttachmentAuthority,
                        runtimeBinding = runtimeBinding,
                        runtimeGeneration = runtimeGeneration,
                        sessionOverlayFactory = sessionOverlayFactory,
                    )
                }
            ) {
                is CarPlayVpnService.AttachResult.Started -> {
                    if (
                        !publishVpnAttachment(service, result.token) {
                            !isStaleWirelessRun(generation)
                        }
                    ) {
                        return
                    }
                    boundWirelessEndpointAddresses(
                        primary = hostAddress,
                        requestedSecondary = secondaryHostAddress,
                        boundAddresses = result.boundAddresses,
                    )
                    result.token
                }
                CarPlayVpnService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayVpnService.AttachResult.Failed -> {
                    result.cleanupToken?.let { cleanupToken ->
                        publishVpnAttachment(service, cleanupToken) { false }
                    }
                    throw IOException(result.message)
                }
            }
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=${airPlayConfig.port}",
            )
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }

            if (ultraAuthorization == null) {
                startWirelessBonjour(wirelessAirPlayConfig, hostAddress, generation)
                if (isStaleWirelessRun(generation)) {
                    closeWirelessStack()
                    return
                }
            }

            val channel = connectWirelessBluetoothDevices(devices, generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            val identification = config.identification.copy(
                wireless = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid),
            )
            val endpointForControlSend: () -> Iap2WirelessCarPlayEndpoint? = {
                if (isStaleWirelessRun(generation) || hotspot !== hotspotOwner) {
                    null
                } else {
                    // A still-bound TCP listener alone cannot prove its P2P group still owns
                    // the advertised address. Re-query the exact group on the control thread.
                    val liveHotspot = wirelessHotspotForControlSend(hotspotInfo) {
                        (hotspotOwner as? WifiP2pGroupManager)?.liveOwnedGroupInfo()
                    }
                    if (!matchesWirelessHotspotAttachment(hotspotInfo, liveHotspot)) {
                        null
                    } else service.liveBoundAddresses(wirelessAttachmentToken)?.let { live ->
                        val addresses = boundWirelessEndpointAddresses(
                            primary = hostAddress,
                            requestedSecondary = secondaryHostAddress,
                            boundAddresses = live,
                        ).map(::hostAddressText)
                        if (isStaleWirelessRun(generation) || hotspot !== hotspotOwner) null
                        else config.wirelessCarPlayEndpoint(
                            ssid = hotspotInfo.ssid,
                            passphrase = hotspotInfo.passphrase,
                            channel = hotspotInfo.channel,
                            security = hotspotInfo.security,
                            ipAddresses = addresses,
                            airPlayPort = airPlayConfig.port,
                            deviceIdentifier = deviceIdentifier,
                            publicKey = identity.publicKeyHex,
                            sourceVersion = airPlayConfig.sourceVersion,
                            actualMfiAuthenticationIdentity = mfiSessionSnapshot.authenticationIdentity,
                        )
                    }
                }
            }
            val endpoint = endpointForControlSend()
                ?: throw IOException("Wireless AirPlay attachment retired before 0x4301")
            debugLog("wireless 0x4301 initial live AirPlay endpoints=${endpoint.ipAddresses.joinToString()}")
            wirelessEndpointBinding = WirelessEndpointBinding(
                identification = identification,
                endpoint = endpoint,
                endpointForControlSend = endpointForControlSend,
                mfiSession = mfiSessionSnapshot,
            )
            media.setIapTunnelHandler { stream ->
                startWirelessTunnelControl(stream, generation)
            }

            onStatus(CarPlayStatus.RunningWireless)
            debugLog("wireless Bluetooth iAP2 control starting")
            val authenticationAttempt = mfiSessionSnapshot.beginAuthenticationAttempt(
                channel,
                authenticationGeneration,
            )
            if (ultraAuthorization != null) {
                try {
                    ownDeferredMfiAuthenticationAttempt(authenticationAttempt)
                } catch (failure: Throwable) {
                    authenticationAttempt.close()
                    throw failure
                }
            }
            val controlClient = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            )
            var appLinksOwner: ControllerAppLinksControl.Owner? = null
            val result = try {
                controlClient.run(
                    identification = identification.copy(locationInformationEnabled = false, vehicleStatusCapabilities = emptySet()),
                    endpoint = endpoint,
                    endpointForControlSend = endpointForControlSend,
                    allowClusterAssetAcquisition = { assetId, authenticationIdentity ->
                        ultraAuthorization?.allowsClusterAssetAcquisition(
                            assetId,
                            authenticationIdentity,
                        ) == true
                    },
                    debugAssetAcquisitionMarker =
                        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    bootstrapStartSessionAllowed = { receipt ->
                        canCurrentWirelessBootstrapSendStartSession(
                            generation,
                            authenticationGeneration,
                            channel,
                            mfiSessionSnapshot,
                            receipt,
                        )
                    },
                    authenticationAttempt = authenticationAttempt,
                    bringUpTimeoutMillis = CONTROL_BRING_UP_TIMEOUT_MILLIS,
                    locationProvider = null,
                    vehicleStatusProvider = null,
                    onReady = {
                        activateMediaRemote(channel)
                        appLinksOwner = publishAppLinksOwner(
                            channel = channel,
                            mfi = mfiSessionSnapshot,
                            controlGeneration = authenticationGeneration,
                            isExactChannel = {
                                generation == wirelessGeneration.get() && csm === channel
                            },
                            client = controlClient,
                            start = { categories, preferredIconSize ->
                                startAppLinks(categories, preferredIconSize)
                            },
                            stop = { stopAppLinks() },
                            requestIcon = { bundles -> requestAppLinksIcons(bundles) },
                        )
                    },
                    onStopped = {
                        appLinksOwner?.let(appLinksControl::revoke)
                        deactivateMediaRemote(channel)
                    },
                    onIncoming = ::onIap2Incoming,
                    onAppLinksUpdate = { update ->
                        appLinksOwner?.let { owner ->
                            appLinksControl.captureActiveEpoch(owner)?.let { epoch ->
                                appLinksControl.receive(owner, epoch, update)
                            }
                        }
                    },
                    onAppLinksIconResponse = { response ->
                        appLinksOwner?.let { owner ->
                            appLinksControl.captureActiveEpoch(owner)?.let { epoch ->
                                appLinksControl.receiveIconResponse(owner, epoch, response)
                            }
                        }
                    },
                    onProgress = ::debugLog,
                    onAuthenticated = { receipt ->
                        val authorization = ultraAuthorization
                        if (authorization == null) {
                            recordMfiAuthenticationReceipt(mfiSessionSnapshot, receipt)
                        } else {
                            val resolved = authorization.activateAndResolve(
                                receipt = receipt,
                                resolvedBaselineConfig = wirelessAirPlayConfig,
                                publishReceipt = { exactReceipt ->
                                    publishInitialAuthorizationReceipt(
                                        mfiSessionSnapshot,
                                        exactReceipt,
                                    )
                                },
                            )
                            when (resolved) {
                                is ControllerUltraAuthorizationRuntime.Resolution.Authorized ->
                                    debugLog("CarPlay Ultra authorization activated")
                                is ControllerUltraAuthorizationRuntime.Resolution.Unavailable ->
                                    debugLog("CarPlay Ultra authorization unavailable; using fallback")
                                is ControllerUltraAuthorizationRuntime.Resolution.Denied ->
                                    debugLog(
                                        "CarPlay Ultra authorization denied " +
                                            "code=${resolved.denial.code}; using fallback",
                                    )
                            }
                            startWirelessBonjour(
                                config = resolved.snapshot.config,
                                hostAddress = hostAddress,
                                generation = generation,
                            )
                        }
                    },
                )
            } finally {
                if (ultraAuthorization == null) authenticationAttempt.close()
            }
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack()
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = wirelessTunnelOwner.snapshot() != null,
                            sessionActive = activeSession != null,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is Error) closeAuthorizationBeforeFatal(error)
            if (closed || generation != wirelessGeneration.get()) {
                return
            }
            if (wirelessActiveReported.get()) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                closeWirelessStack()
                fail(error)
                if (ultraAuthorization != null) close()
            }
        }
    }

    private fun canCurrentWirelessBootstrapSendStartSession(
        generation: Int,
        controlGeneration: Long,
        bootstrapChannel: Iap2Session,
        exactMfiSession: MfiSession,
        bootstrapReceipt: MfiAuthenticationReceipt,
    ): Boolean {
        val controllerReceipt: MfiAuthenticationReceipt?
        val sessionCurrent: Boolean
        val controlCurrent: Boolean
        val handoffReserved: Boolean
        synchronized(lifecycleLock) {
            controllerReceipt = mfiAuthenticationReceipt
            sessionCurrent = mfiSession === exactMfiSession
            controlCurrent = !closed && phase == Phase.WIRELESS &&
                generation == wirelessGeneration.get() &&
                controlGeneration == mfiControlGeneration.get() &&
                csm === bootstrapChannel && !bootstrapChannel.isClosed
            handoffReserved = authorizedReceiptHandoff != null
        }
        return canSendWirelessBootstrapStartSession(
            bootstrapReceipt = bootstrapReceipt,
            controllerReceipt = controllerReceipt,
            exactMfiSession = sessionCurrent,
            currentControl = controlCurrent,
            receiptLive = bootstrapReceipt.isCurrentAuthenticationAttempt() ||
                bootstrapReceipt.isLiveForUltraActivation(),
            handoffReserved = handoffReserved,
            handoffRequested = wirelessHandoffRequested.get(),
            tunnelActive = wirelessTunnelOwner.snapshot() != null,
            tunnelReady = wirelessTunnelReady.get(),
            wirelessActive = wirelessActiveReported.get(),
            airPlaySessionActive = activeSession != null,
        )
    }

    private fun startWirelessTunnelControl(
        stream: BlockingDuplexByteStream,
        expectedWirelessGeneration: Int,
    ): Boolean {
        if (closed || expectedWirelessGeneration != wirelessGeneration.get()) return false
        if (closed || config.transport != CarPlayTransport.WIRELESS) return false
        val binding = wirelessEndpointBinding ?: return false
        val mfiSessionSnapshot = mfiSession ?: return false
        if (mfiSessionSnapshot !== binding.mfiSession) {
            debugLog("wireless tunnel rejected: MFi session differs from endpoint binding")
            return false
        }
        val identification = binding.identification
        val endpoint = binding.endpoint
        val endpointForControlSend = binding.endpointForControlSend
        val mfi = mfiSessionSnapshot.client
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            if (error is Error) closeAuthorizationBeforeFatal(error)
            return false
        }
        val generation = expectedWirelessGeneration
        val published = try {
            wirelessTunnelOwner.publishOrRetire(
                candidate = channel,
                isCurrent = {
                    !closed &&
                        config.transport == CarPlayTransport.WIRELESS &&
                        generation == wirelessGeneration.get()
                },
                retireRejected = { rejected ->
                    ambiguousRetirements.retire(rejected, Iap2Session::close)
                },
            )
        } catch (error: Throwable) {
            debugLog("Could not retire rejected tunneled iAP2 link", error)
            if (error is Error) closeAuthorizationBeforeFatal(error)
            return false
        }
        if (!published) {
            debugLog("wireless type-130 tunnel rejected because an exact owner is active or stale")
            return false
        }
        val authenticationGeneration = nextMfiControlGeneration()
        debugLog("wireless iAP2 tunnel control starting")
        return try {
            tunnelExecutor.execute {
                var authorizedTunnel = false
                var authorizedPromotionCommitted = false
                try {
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    val authorization = ultraAuthorization
                    val controllerReceipt = synchronized(lifecycleLock) {
                        mfiAuthenticationReceipt
                    }
                    val authentication = authorization?.tunnelAuthentication(controllerReceipt)
                    val result = when (authentication) {
                        null,
                        ControllerUltraAuthorizationRuntime.TunnelAuthentication.Ordinary,
                        -> mfiSessionSnapshot.beginAuthenticationAttempt(
                            channel,
                            authenticationGeneration,
                        ).use { authenticationAttempt ->
                            runWirelessTunnelClient(
                                channel = channel,
                                mfi = mfi,
                                controlMfiSession = mfiSessionSnapshot,
                                identification = identification,
                                endpoint = endpoint,
                                endpointForControlSend = endpointForControlSend,
                                generation = generation,
                                controlGeneration = authenticationGeneration,
                                authenticationAttempt = authenticationAttempt,
                                onAuthenticated = { receipt ->
                                    if (authorization == null) {
                                        recordMfiAuthenticationReceipt(mfiSessionSnapshot, receipt)
                                    } else {
                                        val predecessor = controllerReceipt
                                        authorization.promoteFallbackReceipt(
                                            predecessor,
                                            receipt,
                                        ) {
                                            replaceFallbackControllerReceipt(
                                                session = mfiSessionSnapshot,
                                                predecessor = predecessor,
                                                successor = receipt,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        is ControllerUltraAuthorizationRuntime.TunnelAuthentication.AuthorizedHandoff -> {
                            authorizedTunnel = true
                            val exactAuthorization = checkNotNull(authorization)
                            mfiSessionSnapshot.beginAuthenticationHandoff(
                                predecessorReceipt = authentication.predecessor,
                                controlSession = channel,
                                controlGeneration = authenticationGeneration,
                            ).use { handoff ->
                                runWirelessTunnelClient(
                                    channel = channel,
                                    mfi = mfi,
                                    controlMfiSession = mfiSessionSnapshot,
                                    identification = identification,
                                    endpoint = endpoint,
                                    endpointForControlSend = endpointForControlSend,
                                    generation = generation,
                                    controlGeneration = authenticationGeneration,
                                    authenticationAttempt = handoff.attemptForExchange(),
                                    onAuthenticated = { candidate ->
                                        val reservation = reserveAuthorizedReceiptHandoff(
                                            session = mfiSessionSnapshot,
                                            predecessor = authentication.predecessor,
                                            successor = candidate,
                                        ) ?: throw IOException(
                                            "Controller could not reserve the authorized receipt handoff",
                                        )
                                        lateinit var exactPrepared: CarPlayUltraPreparedRenewal
                                        var rollbackOwner: CarPlayUltraPreparedRenewal? = null
                                        try {
                                            exactAuthorization.withExclusiveHandoff {
                                                handoff.commit(candidate) { promoted ->
                                                    exactPrepared = exactAuthorization.prepareRenewal(
                                                        authentication.predecessor,
                                                        promoted,
                                                    )
                                                    rollbackOwner = exactPrepared
                                                }
                                                exactAuthorization.commitRenewal(exactPrepared)
                                                publishAuthorizedReceiptHandoff(reservation)
                                            }
                                            authorizedPromotionCommitted = true
                                        } catch (failure: Throwable) {
                                            rollbackOwner?.let { pending ->
                                                try {
                                                    exactAuthorization.rollbackRenewal(pending)
                                                } catch (cleanupFailure: Throwable) {
                                                    if (cleanupFailure is Error) {
                                                        if (cleanupFailure !== failure) {
                                                            cleanupFailure.addSuppressed(failure)
                                                        }
                                                        cancelAuthorizedReceiptHandoff(reservation)
                                                        throw cleanupFailure
                                                    }
                                                    if (cleanupFailure !== failure) {
                                                        failure.addSuppressed(cleanupFailure)
                                                    }
                                                }
                                            }
                                            cancelAuthorizedReceiptHandoff(reservation)
                                            throw failure
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (closed || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                            if (authorizedPromotionCommitted) close()
                        }
                    }
                } catch (error: Throwable) {
                    if (authorizedTunnel) {
                        if (authorizedPromotionCommitted) {
                            close()
                        } else {
                            val exactAuthorization = ultraAuthorization
                            val currentReceipt = synchronized(lifecycleLock) {
                                mfiAuthenticationReceipt
                            }
                            val predecessorPreserved = try {
                                exactAuthorization?.tunnelAuthentication(currentReceipt) is
                                    ControllerUltraAuthorizationRuntime.TunnelAuthentication.AuthorizedHandoff
                            } catch (fatal: Error) {
                                if (fatal !== error) fatal.addSuppressed(error)
                                closeAuthorizationBeforeFatal(fatal)
                            } catch (_: Exception) {
                                false
                            }
                            if (!predecessorPreserved) close()
                        }
                    }
                    if (error is Error) {
                        closeAuthorizationBeforeFatal(error)
                    }
                    if (!closed && generation == wirelessGeneration.get()) {
                        debugLog("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    closeWirelessTunnelBestEffort(channel)
                }
            }
            true
        } catch (error: Throwable) {
            try {
                closeWirelessTunnelBestEffort(channel)
            } catch (cleanupFatal: Error) {
                if (cleanupFatal !== error) cleanupFatal.addSuppressed(error)
                closeAuthorizationBeforeFatal(cleanupFatal)
            }
            if (error is Error) closeAuthorizationBeforeFatal(error)
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun startWirelessBonjour(
        config: AirPlayConfig,
        hostAddress: InetAddress,
        generation: Int,
    ) {
        val candidate = CarPlayBonjour(
            context = appContext,
            config = config,
            identity = identity,
            advertisedHost = hostAddress.hostAddress,
            onEvent = { event -> debugLog("wireless bonjour: $event") },
        )
        val published = synchronized(wirelessCloseLock) {
            if (isStaleWirelessRun(generation) || bonjour != null) {
                false
            } else {
                bonjour = candidate
                true
            }
        }
        if (!published) {
            candidate.close()
            throw IOException("Wireless Bonjour owner is stale or already published")
        }
        try {
            candidate.start()
        } catch (failure: Throwable) {
            val retired = synchronized(wirelessCloseLock) {
                closeRetryableOwnedFieldBestEffort(
                    name = "Bonjour",
                    read = { bonjour?.takeIf { it === candidate } },
                    clear = { resource -> if (bonjour === resource) bonjour = null },
                    retire = CarPlayBonjour::close,
                )
            }
            if (!retired) {
                failure.addSuppressed(IOException("Failed Bonjour owner retained for retry"))
            }
            throw failure
        }
        debugLog("wireless Bonjour services started")
    }

    private fun runWirelessTunnelClient(
        channel: Iap2Session,
        mfi: MfiAuthenticator,
        controlMfiSession: MfiSession,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        endpointForControlSend: () -> Iap2WirelessCarPlayEndpoint?,
        generation: Int,
        controlGeneration: Long,
        authenticationAttempt: MfiAuthenticationAttempt,
        onAuthenticated: (MfiAuthenticationReceipt) -> Unit,
    ): Iap2WirelessControlResult {
        val controlClient = Iap2WirelessControlClient(
            session = channel,
            mfi = Iap2MfiAuthenticationClient(mfi),
        )
        var appLinksOwner: ControllerAppLinksControl.Owner? = null
        return controlClient.run(
        identification = identification,
        endpoint = endpoint,
        endpointForControlSend = endpointForControlSend,
        authenticationAttempt = authenticationAttempt,
        bringUpTimeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
        locationProvider = locationProvider,
        vehicleStatusProvider = vehicleStatusProvider,
        onReady = {
            if (!closed && generation == wirelessGeneration.get() &&
                wirelessTunnelOwner.snapshot() === channel
            ) {
                activateMediaRemote(channel)
                appLinksOwner = publishAppLinksOwner(
                    channel = channel,
                    mfi = controlMfiSession,
                    controlGeneration = controlGeneration,
                    isExactChannel = {
                        generation == wirelessGeneration.get() &&
                                activeMediaRemoteSession === channel
                    },
                    client = controlClient,
                    start = { categories, preferredIconSize ->
                        startAppLinks(categories, preferredIconSize)
                    },
                    stop = { stopAppLinks() },
                    requestIcon = { bundles -> requestAppLinksIcons(bundles) },
                )
                onWirelessTunnelReady(generation)
            }
        },
        onStopped = {
            appLinksOwner?.let(appLinksControl::revoke)
            deactivateMediaRemote(channel)
        },
        onIncoming = ::onIap2Incoming,
        onProgress = { message -> debugLog("iAP tunnel $message") },
        onAuthenticated = onAuthenticated,
        startSessionOnAvailability = false,
        onAppLinksUpdate = { update ->
            appLinksOwner?.let { owner ->
                appLinksControl.captureActiveEpoch(owner)?.let { epoch ->
                    appLinksControl.receive(owner, epoch, update)
                }
            }
        },
        onAppLinksIconResponse = { response ->
            appLinksOwner?.let { owner ->
                appLinksControl.captureActiveEpoch(owner)?.let { epoch ->
                    appLinksControl.receiveIconResponse(owner, epoch, response)
                }
            }
        },
    )
    }

    private fun reserveAuthorizedReceiptHandoff(
        session: MfiSession,
        predecessor: MfiAuthenticationReceipt,
        successor: MfiAuthenticationReceipt,
    ): AuthorizedReceiptHandoffReservation? = synchronized(lifecycleLock) {
        if (closed || mfiSession !== session || mfiAuthenticationReceipt !== predecessor ||
            authorizedReceiptHandoff != null ||
            predecessor.authenticationIdentity != session.authenticationIdentity ||
            successor.authenticationIdentity != session.authenticationIdentity
        ) {
            null
        } else {
            AuthorizedReceiptHandoffReservation(session, predecessor, successor).also {
                authorizedReceiptHandoff = it
            }
        }
    }

    /** All exact checks completed at reservation time; post-commit publication cannot fail. */
    private fun publishAuthorizedReceiptHandoff(
        reservation: AuthorizedReceiptHandoffReservation,
    ) = synchronized(lifecycleLock) {
        mfiAuthenticationReceipt = reservation.successor
        authorizedReceiptHandoff = null
    }

    private fun cancelAuthorizedReceiptHandoff(
        reservation: AuthorizedReceiptHandoffReservation,
    ) = synchronized(lifecycleLock) {
        if (authorizedReceiptHandoff === reservation) authorizedReceiptHandoff = null
    }

    private fun onWirelessTunnelReady(generation: Int) {
        if (
            closed ||
            phase != Phase.WIRELESS ||
            generation != wirelessGeneration.get()
        ) {
            return
        }
        wirelessTunnelReady.set(true)
        debugLog(
            "wireless iAP2 tunnel ready; " +
                "handoffRequested=${wirelessHandoffRequested.get()}",
        )
        maybeCompleteWirelessHandoff()
    }

    private fun maybeCompleteWirelessHandoff() {
        if (!wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
        if (!wirelessActiveReported.compareAndSet(false, true)) return
        val generation = wirelessGeneration.get()
        val started = try {
            startOwnedWorker("xcertplay-wireless-handoff") {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get()
                ) {
                    return@startOwnedWorker
                }
                debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                val retired = closeBluetoothBootstrapTransport()
                if (
                    canReportWirelessHandoffActive(
                        bootstrapRetired = retired,
                        closed = closed,
                        generationMatches = generation == wirelessGeneration.get(),
                    )
                ) {
                    onStatus(CarPlayStatus.WirelessActive)
                } else if (!closed && generation == wirelessGeneration.get()) {
                    wirelessActiveReported.compareAndSet(true, false)
                    fail(IOException("Bluetooth bootstrap transport could not retire"))
                }
            }
        } catch (failure: Exception) {
            if (!closed && generation == wirelessGeneration.get()) {
                wirelessActiveReported.compareAndSet(true, false)
                fail(failure)
            }
            false
        }
        if (!started && generation == wirelessGeneration.get()) {
            wirelessActiveReported.compareAndSet(true, false)
        }
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog(
                    "wireless handoff timed out waiting for tunnel iAP2 readiness; " +
                        "continuing with Bluetooth iAP2",
                )
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun closeBluetoothBootstrapTransport(): Boolean {
        return synchronized(wirelessCloseLock) {
            var clean = closeOwnedFieldBestEffort(
                name = "wireless CSM",
                read = { csm },
                clear = { resource -> if (csm === resource) csm = null },
                retire = Iap2Session::close,
            )
            clean = closeOwnedFieldBestEffort(
                name = "wireless RFCOMM stream",
                read = { bluetoothStream },
                clear = { resource -> if (bluetoothStream === resource) bluetoothStream = null },
                retire = BluetoothRfcommDuplexStream::close,
            ) && clean
            clean = closeOwnedFieldBestEffort(
                name = "wireless Bluetooth socket",
                read = { bluetoothSocket },
                clear = { resource -> if (bluetoothSocket === resource) bluetoothSocket = null },
                retire = BluetoothSocket::close,
            ) && clean
            clean
        }
    }

    private fun startIphone() {
        availabilityPollGeneration.incrementAndGet()
        synchronized(lifecycleLock) { phase = Phase.IPHONE }
        reenumerationAttempts = 0
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                // The system broadcast and the polling fallback can both observe the grant.
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        if (IphoneCarPlayConfiguration.find(result.device) != null) {
                            openDataPaths(result.device)
                        } else if (reenumerationAttempts < MAXIMUM_REENUMERATION_ATTEMPTS) {
                            beginReenumeration(result.device)
                        } else {
                            fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    /** Some Android builds grant the dialog without delivering the permission broadcast. */
    private fun pollIphonePermission(device: UsbDevice) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (usbManager.hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; tap Reconnect iPhone to retry",
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested ->
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                is IphoneUsbHost.TransitionResult.Failed -> fail(transition.error)
            }
        }
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        val (authenticationGeneration, expectedMfiGeneration, expectedMfiSession) =
            synchronized(lifecycleLock) {
                phase = Phase.DATAPATHS
                Triple(nextMfiControlGeneration(), mfiGeneration.get(), mfiSession)
            }
        val preMuxReservation = preMuxUsbSessions.reserve()
        if (preMuxReservation == null) {
            fail(
                IOException(
                    "Too many pre-mux iAP2 USB sessions are awaiting proven retirement",
                ),
            )
            return
        }
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        try {
            iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
                when (result) {
                    is IphoneUsbHost.Iap2SessionResult.Connected -> {
                        val usbSession = result.session
                        try {
                            preMuxUsbSessions.bind(preMuxReservation, usbSession)
                            if (!isCurrentPreMuxOpen(
                                    authenticationGeneration,
                                    expectedMfiGeneration,
                                    expectedMfiSession,
                                )
                            ) {
                                retirePreMuxUsbSessionBestEffort(usbSession)
                                return@openIap2UsbSessionAsync
                            }
                            val ncm = try {
                                openNcm(device)
                            } catch (error: Throwable) {
                                retirePreMuxUsbSessionBestEffort(usbSession, error)
                                fail(error)
                                return@openIap2UsbSessionAsync
                            }
                            runStack(
                                usbSession,
                                ncm,
                                authenticationGeneration,
                                expectedMfiGeneration,
                                expectedMfiSession,
                            )
                        } catch (failure: Throwable) {
                            // IphoneUsbHost will call session.close() itself when this callback
                            // escapes. Seed the ambiguity first so that its unobserved close cannot
                            // turn a later no-op through the published mux into false success.
                            ambiguousRetirements.retainUnprovenFailure(
                                resource = usbSession,
                                action = Iap2UsbSession::close,
                                failure = IOException(
                                    "iAP2 USB callback escaped before host cleanup was proven",
                                    failure,
                                ),
                            )
                            throw failure
                        }
                    }
                    is IphoneUsbHost.Iap2SessionResult.Failed -> {
                        check(preMuxUsbSessions.cancel(preMuxReservation)) {
                            "Failed iAP2 USB open lost its pre-mux reservation"
                        }
                        if (isCurrentPreMuxOpen(
                                authenticationGeneration,
                                expectedMfiGeneration,
                                expectedMfiSession,
                            )
                        ) {
                            fail(result.error)
                        } else {
                            debugLog("Ignored stale iAP2 USB open failure", result.error)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            preMuxUsbSessions.cancel(preMuxReservation)
            fail(error)
        }
    }

    private fun isCurrentPreMuxOpen(
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
    ): Boolean = isCurrentWiredAttempt(
        authenticationGeneration = authenticationGeneration,
        expectedMfiGeneration = expectedMfiGeneration,
        expectedMfiSession = expectedMfiSession,
        expectedPhase = Phase.DATAPATHS,
    )

    private fun isCurrentWiredControl(
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
    ): Boolean = isCurrentWiredAttempt(
        authenticationGeneration = authenticationGeneration,
        expectedMfiGeneration = expectedMfiGeneration,
        expectedMfiSession = expectedMfiSession,
        expectedPhase = Phase.CONTROL,
    )

    private fun isCurrentWiredAttempt(
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
        expectedPhase: Phase,
    ): Boolean = synchronized(lifecycleLock) {
        phase == expectedPhase && isCurrentWiredGenerationLocked(
            authenticationGeneration,
            expectedMfiGeneration,
            expectedMfiSession,
        )
    }

    private fun isCurrentWiredGeneration(
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
    ): Boolean = synchronized(lifecycleLock) {
        isCurrentWiredGenerationLocked(
            authenticationGeneration,
            expectedMfiGeneration,
            expectedMfiSession,
        )
    }

    private fun isCurrentWiredGenerationLocked(
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
    ): Boolean =
        expectedMfiSession != null && mfiSession === expectedMfiSession &&
            canContinuePreMuxOpen(
                closed = closed,
                dataPathPhaseMatches = true,
                authenticationGenerationMatches =
                    authenticationGeneration == mfiControlGeneration.get(),
                mfiGenerationMatches = expectedMfiGeneration == mfiGeneration.get(),
            )

    private fun retirePreMuxUsbSessionBestEffort(
        session: Iap2UsbSession,
        primaryFailure: Throwable? = null,
    ) {
        try {
            preMuxUsbSessions.retire(session)
        } catch (cleanupFailure: Exception) {
            if (primaryFailure != null && cleanupFailure !== primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
            debugLog("Pre-mux iAP2 USB session retirement failed; exact owner retained", cleanupFailure)
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function)
    }

    private fun runStack(
        usbSession: Iap2UsbSession,
        ncm: NcmUsbBridge,
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
    ) {
        var ncmOwnedLocally = true
        var stackFailure: Throwable? = null
        var muxCandidate: Iap2UsbMuxHost? = null
        var csmCandidate: Iap2Session? = null
        try {
            if (!isCurrentPreMuxOpen(
                    authenticationGeneration,
                    expectedMfiGeneration,
                    expectedMfiSession,
                )
            ) return
            val muxReservation = unpublishedMuxHosts.reserve()
                ?: throw IOException("Too many unpublished USBMUX hosts await proven retirement")
            val mux = try {
                preMuxUsbSessions.transfer(usbSession) { exactSession ->
                    val opened = openMuxWithRetirementEvidence(exactSession)
                    unpublishedMuxHosts.bind(muxReservation, opened)
                    opened
                }
            } catch (failure: Throwable) {
                unpublishedMuxHosts.cancel(muxReservation)
                throw failure
            }
            muxCandidate = mux
            unpublishedMuxHosts.transfer(mux) { exactMux ->
                synchronized(lifecycleLock) {
                    if (!isCurrentPreMuxOpen(
                            authenticationGeneration,
                            expectedMfiGeneration,
                            expectedMfiSession,
                        )
                    ) {
                        throw IOException("USBMUX handshake completed for a stale wired attempt")
                    }
                    if (this.mux != null) {
                        throw IOException("Controller already owns another USBMUX host")
                    }
                    this.mux = exactMux
                    phase = Phase.CONTROL
                }
            }
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux)
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                if (!isInvalidPairRecord(error)) throw error
                debugLog("saved Lockdown pair record rejected; pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            val csmReservation = unpublishedWiredCsmSessions.reserve()
                ?: throw IOException("Too many unpublished wired CSM sessions await retirement")
            val csm = try {
                Iap2Session.open(
                    carkit,
                    traceContext = "wired",
                    onTrace = ::debugLog,
                ).also { opened ->
                    unpublishedWiredCsmSessions.bind(csmReservation, opened)
                }
            } catch (failure: Throwable) {
                unpublishedWiredCsmSessions.cancel(csmReservation)
                throw failure
            }
            csmCandidate = csm
            unpublishedWiredCsmSessions.transfer(csm) { exactCsm ->
                synchronized(lifecycleLock) {
                    if (!isCurrentWiredControl(
                            authenticationGeneration,
                            expectedMfiGeneration,
                            expectedMfiSession,
                        )
                    ) {
                        throw IOException("Wired CSM opened for a stale control attempt")
                    }
                    if (this.csm != null) {
                        throw IOException("Controller already owns another wired CSM session")
                    }
                    this.csm = exactCsm
                }
            }
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!isCurrentWiredControl(
                    authenticationGeneration,
                    expectedMfiGeneration,
                    expectedMfiSession,
                )
            ) {
                throw IOException("Wired control attempt became stale before VPN attachment")
            }
            if (!attachVpn(
                    ncm,
                    ncmHostMac,
                    authenticationGeneration,
                    expectedMfiGeneration,
                    expectedMfiSession,
                )
            ) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                detachOwnedVpnAttachment()
                return
            }

            if (!isCurrentWiredControl(
                    authenticationGeneration,
                    expectedMfiGeneration,
                    expectedMfiSession,
                )
            ) {
                throw IOException("Wired control attempt became stale before authentication")
            }
            val mfiSessionSnapshot = checkNotNull(expectedMfiSession)
            val mfi = mfiSessionSnapshot.client
            val endpoint = config.wiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
                actualMfiAuthenticationIdentity = mfiSessionSnapshot.authenticationIdentity,
            )
            val endpointForControlSend: () -> Iap2WiredCarPlayEndpoint? = {
                // AA05 activation and an NCM attachment can both change after the endpoint was
                // first assembled. Re-check the exact listener owner for each 0x4300 response.
                val attachment = synchronized(lifecycleLock) {
                    if (isCurrentWiredControl(
                            authenticationGeneration,
                            expectedMfiGeneration,
                            expectedMfiSession,
                        )
                    ) ownedVpnAttachment else null
                }
                if (attachment?.service?.isAttached(attachment.token) != true ||
                    !isCurrentWiredControl(
                        authenticationGeneration,
                        expectedMfiGeneration,
                        expectedMfiSession,
                    ) || synchronized(lifecycleLock) { ownedVpnAttachment !== attachment }
                ) {
                    null
                } else {
                    config.wiredCarPlayEndpoint(
                        ipv6Addresses = listOf(config.linkLocal),
                        airPlayPort = airPlayConfig.port,
                        publicKey = identity.publicKeyHex,
                        sourceVersion = airPlayConfig.sourceVersion,
                        deviceIdentifier = ncmHostMac.macString(),
                        actualMfiAuthenticationIdentity = mfiSessionSnapshot.authenticationIdentity,
                    )
                }
            }
            onStatus(CarPlayStatus.RunningControl)
            debugLog("wired iAP2 control starting")
            val authenticationAttempt = mfiSessionSnapshot.beginAuthenticationAttempt(
                csm,
                authenticationGeneration,
            )
            if (ultraAuthorization != null) {
                try {
                    ownDeferredMfiAuthenticationAttempt(authenticationAttempt)
                } catch (failure: Throwable) {
                    authenticationAttempt.close()
                    throw failure
                }
            }
            val controlClient = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi))
            var appLinksOwner: ControllerAppLinksControl.Owner? = null
            val result = try {
                controlClient.run(
                    identification = config.identification,
                    endpoint = endpoint,
                    endpointForControlSend = endpointForControlSend,
                    allowClusterAssetAcquisition = { assetId, authenticationIdentity ->
                        ultraAuthorization?.allowsClusterAssetAcquisition(
                            assetId,
                            authenticationIdentity,
                        ) == true
                    },
                    debugAssetAcquisitionMarker =
                        appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                    authenticationAttempt = authenticationAttempt,
                    bringUpTimeoutMillis = CONTROL_BRING_UP_TIMEOUT_MILLIS,
                    locationProvider = locationProvider,
                    vehicleStatusProvider = vehicleStatusProvider,
                    onReady = {
                        activateMediaRemote(csm)
                        appLinksOwner = publishAppLinksOwner(
                            channel = csm,
                            mfi = mfiSessionSnapshot,
                            controlGeneration = authenticationGeneration,
                            isExactChannel = { this.csm === csm },
                            client = controlClient,
                            start = { categories, preferredIconSize ->
                                startAppLinks(categories, preferredIconSize)
                            },
                            stop = { stopAppLinks() },
                            requestIcon = { bundles -> requestAppLinksIcons(bundles) },
                        )
                    },
                    onStopped = {
                        appLinksOwner?.let(appLinksControl::revoke)
                        deactivateMediaRemote(csm)
                    },
                    onIncoming = ::onIap2Incoming,
                    onAppLinksUpdate = { update ->
                        appLinksOwner?.let { owner ->
                            appLinksControl.captureActiveEpoch(owner)?.let { epoch ->
                                appLinksControl.receive(owner, epoch, update)
                            }
                        }
                    },
                    onAppLinksIconResponse = { response ->
                        appLinksOwner?.let { owner ->
                            appLinksControl.captureActiveEpoch(owner)?.let { epoch ->
                                appLinksControl.receiveIconResponse(owner, epoch, response)
                            }
                        }
                    },
                    onProgress = { message -> debugLog("wired $message") },
                    onAuthenticated = { receipt ->
                        val authorization = ultraAuthorization
                        if (authorization == null) {
                            recordMfiAuthenticationReceipt(mfiSessionSnapshot, receipt)
                        } else {
                            when (
                                val resolved = authorization.activateAndResolve(
                                    receipt = receipt,
                                    publishReceipt = { exactReceipt ->
                                        publishInitialAuthorizationReceipt(
                                            mfiSessionSnapshot,
                                            exactReceipt,
                                        )
                                    },
                                )
                            ) {
                                is ControllerUltraAuthorizationRuntime.Resolution.Authorized ->
                                    debugLog("CarPlay Ultra authorization activated")
                                is ControllerUltraAuthorizationRuntime.Resolution.Unavailable ->
                                    debugLog("CarPlay Ultra authorization unavailable; using fallback")
                                is ControllerUltraAuthorizationRuntime.Resolution.Denied ->
                                    debugLog(
                                        "CarPlay Ultra authorization denied " +
                                            "code=${resolved.denial.code}; using fallback",
                                    )
                            }
                        }
                    },
                )
            } finally {
                if (ultraAuthorization == null) authenticationAttempt.close()
            }
            onStatus(
                when (result.terminal) {
                    Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                },
            )
            if (ultraAuthorization != null) close()
        } catch (error: Throwable) {
            stackFailure = error
            if (error is Error) closeAuthorizationBeforeFatal(error)
            debugLog("wired bring-up failed", error)
            if (!ncmOwnedLocally) detachOwnedVpnAttachment()
            if (isCurrentWiredGeneration(
                    authenticationGeneration,
                    expectedMfiGeneration,
                    expectedMfiSession,
                )
            ) {
                fail(error)
                if (ultraAuthorization != null) close()
            }
        } finally {
            csmCandidate?.let { retireUnpublishedWiredCsmBestEffort(it, stackFailure) }
            muxCandidate?.let { retireUnpublishedMuxHostBestEffort(it, stackFailure) }
            // This is a no-op after a successful USBMUX ownership transfer. Every stale or failed
            // pre-transfer path instead requires a successful exact close before the owner drops.
            retirePreMuxUsbSessionBestEffort(usbSession, stackFailure)
            if (ncmOwnedLocally) ncm.close()
        }
    }

    private fun openMuxWithRetirementEvidence(session: Iap2UsbSession): Iap2UsbMuxHost = try {
        Iap2UsbMuxHost.open(session)
    } catch (failure: Throwable) {
        // Iap2UsbMuxHost.open rethrows protocol/transport IphoneUsbException only after its nested
        // host.close() returned, which proves the pipe close. Any other throwable may itself be the
        // nested close failure after Iap2UsbSession already marked itself closed; preserve that
        // ambiguity before the controller's later close can observe only a no-op.
        if (failure !is IphoneUsbException) {
            ambiguousRetirements.retainUnprovenFailure(
                resource = session,
                action = Iap2UsbSession::close,
                failure = IOException(
                    "USBMUX open failed without proof that its nested USB session retired",
                    failure,
                ),
            )
        }
        throw failure
    }

    private fun retireUnpublishedMuxHostBestEffort(
        host: Iap2UsbMuxHost,
        primaryFailure: Throwable? = null,
    ) {
        try {
            unpublishedMuxHosts.retire(host)
        } catch (cleanupFailure: Exception) {
            if (primaryFailure != null && cleanupFailure !== primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
            debugLog("Unpublished USBMUX host retirement failed; exact owner retained", cleanupFailure)
        }
    }

    private fun retireUnpublishedWiredCsmBestEffort(
        session: Iap2Session,
        primaryFailure: Throwable? = null,
    ) {
        try {
            unpublishedWiredCsmSessions.retire(session)
        } catch (cleanupFailure: Exception) {
            if (primaryFailure != null && cleanupFailure !== primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
            debugLog("Unpublished wired CSM retirement failed; exact owner retained", cleanupFailure)
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun isInvalidPairRecord(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause.message?.contains("InvalidPairRecord", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun startWirelessHotspot(
        generation: Int,
        mfi: MfiAuthenticator,
    ): WirelessHotspotInfo {
        val hotspotMode = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P
        ) {
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT
        } else {
            config.wirelessHotspotMode
        }
        val manager: WirelessHotspotManager = when (hotspotMode) {
            WirelessHotspotMode.WIFI_P2P ->
                mfiCertificateWifiP2pCredentials(mfi.readCertificate()).let { credentials ->
                    WifiP2pGroupManager(
                        context = appContext,
                        networkName = credentials.ssid,
                        passphrase = credentials.passphrase,
                    )
                }
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext)
            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
                context = appContext,
                ssid = config.manualHotspotSsid
                    ?: throw IOException("Manual hotspot SSID is not configured"),
                passphrase = config.manualHotspotPassphrase.orEmpty(),
                band = config.manualHotspotBand,
                channel = config.manualHotspotChannel,
                security = config.manualHotspotSecurity,
            )
        }
        hotspot = manager
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            val retired = synchronized(wirelessCloseLock) {
                closeOwnedFieldBestEffort(
                    name = hotspotMode.name,
                    read = { hotspot?.takeIf { it === manager } },
                    clear = { resource -> if (hotspot === resource) hotspot = null },
                    retire = WirelessHotspotManager::close,
                )
            }
            if (!retired) {
                failure.addSuppressed(
                    IOException("Failed hotspot owner was retained for teardown retry"),
                )
            }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get()

    @SuppressLint("MissingPermission")
    private fun selectWirelessBluetoothDevices(adapter: BluetoothAdapter): List<BluetoothDevice> {
        val bonded = adapter.bondedDevices.orEmpty()
        val smartPhones = bonded.filter { device ->
            val bluetoothClass = device.bluetoothClass
            debugLog(
                "wireless Bluetooth bonded name=${device.name ?: "unknown"} " +
                    "address=${device.address} class=${bluetoothClass ?: "unknown"} " +
                    "deviceClass=${bluetoothClass?.deviceClass?.toString(16) ?: "unknown"}",
            )
            bluetoothClass?.deviceClass == BluetoothClass.Device.PHONE_SMART
        }
        if (smartPhones.isEmpty()) {
            throw IOException("No bonded PHONE_SMART devices found; pair an iPhone and retry")
        }
        val directlyConnectedSmartPhones = smartPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedSmartPhones=${smartPhones.size} " +
                "directlyConnected=${directlyConnectedSmartPhones.size}",
        )
        val connectedAddresses = if (directlyConnectedSmartPhones.isNotEmpty()) {
            directlyConnectedSmartPhones.mapTo(mutableSetOf()) { it.address }
        } else {
            connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
        }
        return smartPhones.sortedWith(
            compareByDescending<BluetoothDevice> { it.address in connectedAddresses }
                .thenBy { it.address },
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectWirelessBluetoothDevices(
        devices: List<BluetoothDevice>,
        generation: Int,
    ): Iap2Session {
        var lastFailure: Exception? = null
        for ((index, device) in devices.withIndex()) {
            if (isStaleWirelessRun(generation)) {
                throw IOException("Wireless Bluetooth connection was cancelled")
            }
            onStatus(CarPlayStatus.ConnectingBluetooth)
            debugLog(
                "wireless RFCOMM attempt=${index + 1}/${devices.size} " +
                    "name=${device.name ?: "unknown"} address=${device.address} " +
                    "uuid=$IAP2_IPHONE_UUID",
            )
            try {
                val socket = device
                    .createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                    .also { bluetoothSocket = it }
                connectBluetoothSocket(socket, device.address)
                if (isStaleWirelessRun(generation)) {
                    throw IOException("Wireless Bluetooth connection was cancelled")
                }
                debugLog("wireless RFCOMM connected address=${device.address}")
                val stream = BluetoothRfcommDuplexStream(socket).also { bluetoothStream = it }
                val channel = Iap2Session.openWireless(
                    stream,
                    traceContext = "wireless-rfcomm",
                    onTrace = ::debugLog,
                ).also { csm = it }
                debugLog("wireless iAP2 CSM channel opened address=${device.address}")
                return channel
            } catch (error: Exception) {
                val retired = closeBluetoothBootstrapTransport()
                if (isStaleWirelessRun(generation)) throw error
                if (!retired) {
                    throw IOException(
                        "Previous Bluetooth bootstrap transport could not retire",
                        error,
                    )
                }
                lastFailure = error
                debugLog("wireless RFCOMM attempt failed address=${device.address}", error)
            }
        }
        throw IOException(
            "Could not open iAP2 over Bluetooth with any of ${devices.size} bonded PHONE_SMART devices",
            lastFailure,
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        val started = startOwnedWorker("wireless-rfcomm-connect") {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                    if (error is Error) throw error
                } finally {
                    connected.countDown()
                }
        }
        if (!started) throw IOException("Wireless RFCOMM connection was cancelled")
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        when (val failure = result.get()) {
            null -> Unit
            is IOException -> throw failure
            else -> throw IOException("Could not connect RFCOMM to $address", failure)
        }
    }

    /** Best-effort live restart cleanup. Failed exact resources remain published for a retry. */
    private fun closeWirelessStack(): Boolean {
        return synchronized(wirelessCloseLock) {
            var clean = closeBestEffort("iAP tunnel handler") {
                media.setIapTunnelHandler(null)
            }
            clean = closeWirelessTunnelBestEffort() && clean
            clean = closeBluetoothBootstrapTransport() && clean
            clean = closeRetryableOwnedFieldBestEffort(
                name = "Bonjour",
                read = { bonjour },
                clear = { resource -> if (bonjour === resource) bonjour = null },
                retire = CarPlayBonjour::close,
            ) && clean
            clean = closeOwnedFieldBestEffort(
                name = "wireless hotspot",
                read = { hotspot },
                clear = { resource -> if (hotspot === resource) hotspot = null },
                retire = WirelessHotspotManager::close,
            ) && clean
            clean = closeBestEffort("AirPlay service") {
                closeOwnedVpnAttachmentStrict()
            } && clean
            clearWirelessRuntimeState()
            clean
        }
    }

    /** Strict final cleanup used by the success-only controller teardown barrier. */
    private fun closeWirelessStackStrict() {
        synchronized(wirelessCloseLock) {
            runAllControllerTeardownActions(
                listOf(
                    "iAP tunnel handler" to { media.setIapTunnelHandler(null) },
                    "tunneled iAP2 link" to {
                        wirelessTunnelOwner.retireCurrent(Iap2Session::close)
                    },
                    "wireless CSM" to {
                        closeOwnedFieldStrict(
                            read = { csm },
                            clear = { resource -> if (csm === resource) csm = null },
                            retire = Iap2Session::close,
                        )
                    },
                    "wireless RFCOMM stream" to {
                        closeOwnedFieldStrict(
                            read = { bluetoothStream },
                            clear = { resource ->
                                if (bluetoothStream === resource) bluetoothStream = null
                            },
                            retire = BluetoothRfcommDuplexStream::close,
                        )
                    },
                    "wireless Bluetooth socket" to {
                        closeOwnedFieldStrict(
                            read = { bluetoothSocket },
                            clear = { resource ->
                                if (bluetoothSocket === resource) bluetoothSocket = null
                            },
                            retire = BluetoothSocket::close,
                        )
                    },
                    "Bonjour" to {
                        closeOwnedFieldStrict(
                            read = { bonjour },
                            clear = { resource -> if (bonjour === resource) bonjour = null },
                            retire = CarPlayBonjour::close,
                        )
                    },
                    "wireless hotspot" to {
                        closeOwnedFieldStrict(
                            read = { hotspot },
                            clear = { resource -> if (hotspot === resource) hotspot = null },
                            retire = WirelessHotspotManager::close,
                        )
                    },
                    "AirPlay service" to ::closeOwnedVpnAttachmentStrict,
                    "wireless terminal state" to ::clearWirelessRuntimeState,
                ),
            )
        }
    }

    private fun closeWirelessTunnelBestEffort(candidate: Iap2Session? = null): Boolean = try {
        val retire: (Iap2Session) -> Unit = { resource ->
            ambiguousRetirements.retire(resource, Iap2Session::close)
        }
        if (candidate == null) {
            wirelessTunnelOwner.retireCurrent(retire)
        } else {
            wirelessTunnelOwner.retireIfOwned(candidate, retire)
        }
        true
    } catch (failure: Exception) {
        // The gate retains the exact published owner; ambiguousRetirements additionally retains
        // child close APIs that may turn a later retry into a misleading no-op.
        debugLog("tunneled iAP2 link teardown failed; retry retained", failure)
        false
    }

    private fun clearWirelessRuntimeState() {
        wirelessEndpointBinding = null
        wirelessHandoffRequested.set(false)
        wirelessTunnelReady.set(false)
        wirelessActiveReported.set(false)
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        return address
            ?.takeIf {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("LocalOnlyHotspot host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit): Boolean =
        try {
            close()
            true
        } catch (error: Exception) {
            debugLog("$name teardown failed", error)
            false
        }

    private fun <T : Any> closeOwnedFieldBestEffort(
        name: String,
        read: () -> T?,
        clear: (T) -> Unit,
        retire: (T) -> Unit,
    ): Boolean = try {
            closeOwnedFieldStrict(read, clear, retire)
            true
        } catch (failure: Exception) {
            // Do not clear the field: it is the retry owner for this exact resource.
            debugLog("$name teardown failed; retry retained", failure)
            false
        }

    private fun <T : Any> closeRetryableOwnedFieldBestEffort(
        name: String,
        read: () -> T?,
        clear: (T) -> Unit,
        retire: (T) -> Unit,
    ): Boolean = try {
        closeRetryableOwnedFieldStrict(read, clear, retire)
        true
    } catch (failure: Exception) {
        debugLog("$name teardown failed; retry retained", failure)
        false
    }

    private fun attachVpn(
        ncm: NcmUsbBridge,
        hostMac: ByteArray,
        authenticationGeneration: Long,
        expectedMfiGeneration: Int,
        expectedMfiSession: MfiSession?,
    ): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            airPlayAttachmentAdmission.forwardToAttachment {
                runtimeBinding, runtimeGeneration, sessionOverlayFactory ->
                service.attach(
                    ncm = ncm,
                    linkLocal = config.linkLocal,
                    hostMac = hostMac,
                    config = airPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = expectedMfiSession?.client,
                    listener = sessionListener,
                    media = media,
                    authority = vpnAttachmentAuthority,
                    runtimeBinding = runtimeBinding,
                    runtimeGeneration = runtimeGeneration,
                    sessionOverlayFactory = sessionOverlayFactory,
                )
            }
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            is CarPlayVpnService.AttachResult.Started -> {
                if (publishVpnAttachment(service, result.token) {
                        isCurrentWiredControl(
                            authenticationGeneration,
                            expectedMfiGeneration,
                            expectedMfiSession,
                        )
                    }
                ) {
                    debugLog("wired VPN/NCM transport attach result=started")
                    true
                } else {
                    false
                }
            }
            CarPlayVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is CarPlayVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message}")
                if (result.cleanupToken != null) {
                    publishVpnAttachment(service, result.cleanupToken) { false }
                } else {
                    ncm.close()
                }
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun publishVpnAttachment(
        service: CarPlayVpnService,
        token: CarPlayVpnService.AttachmentToken,
        isCurrent: () -> Boolean,
    ): Boolean {
        val candidate = OwnedVpnAttachment(service, token)
        val accepted = synchronized(lifecycleLock) {
            if (closed || !vpnAttachmentAuthority.isValid() || !isCurrent()) {
                pendingVpnAttachmentRetirements.addLast(candidate)
                false
            } else {
                ownedVpnAttachment?.let(pendingVpnAttachmentRetirements::addLast)
                ownedVpnAttachment = candidate
                true
            }
        }
        val pendingRetired = try {
            closePendingVpnAttachmentsStrict()
            true
        } catch (failure: Exception) {
            debugLog(
                "${if (accepted) "previous" else "stale"} VPN attachment teardown failed; retry retained",
                failure,
            )
            false
        }
        if (accepted && !pendingRetired) {
            synchronized(lifecycleLock) {
                if (ownedVpnAttachment === candidate) {
                    ownedVpnAttachment = null
                    pendingVpnAttachmentRetirements.addLast(candidate)
                }
            }
            return false
        }
        return accepted && pendingRetired
    }

    private fun detachOwnedVpnAttachment() {
        closeBestEffort("VPN/NCM") { closeOwnedVpnAttachmentStrict() }
    }

    private fun closeOwnedVpnAttachmentStrict() {
        val attachment = synchronized(lifecycleLock) { ownedVpnAttachment }
        if (attachment != null) {
            ambiguousRetirements.retire(attachment, OwnedVpnAttachment::close)
            synchronized(lifecycleLock) {
                if (ownedVpnAttachment === attachment) ownedVpnAttachment = null
            }
        }
        closePendingVpnAttachmentsStrict()
    }

    private fun closePendingVpnAttachmentsStrict() {
        while (true) {
            val attachment = synchronized(lifecycleLock) {
                pendingVpnAttachmentRetirements.peekFirst()
            } ?: return
            ambiguousRetirements.retire(attachment, OwnedVpnAttachment::close)
            synchronized(lifecycleLock) {
                if (pendingVpnAttachmentRetirements.peekFirst() === attachment) {
                    pendingVpnAttachmentRetirements.removeFirst()
                }
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): CarPlayVpnService? {
        if (closed) return null
        vpnService?.let { return it }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) && !closed) {
                vpnService
            } else {
                null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        val bindGeneration = synchronized(lifecycleLock) {
            if (closed || vpnBound) null else {
                vpnBindGeneration = Math.addExact(vpnBindGeneration, 1L)
                vpnBound = true
                vpnBindGeneration
            }
        } ?: return
        val bound = try {
            val intent = Intent(appContext, CarPlayVpnService::class.java)
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) {
            false
        }
        val stale = synchronized(lifecycleLock) {
            val stillCurrent = !closed && vpnBound && vpnBindGeneration == bindGeneration
            if (!bound && vpnBindGeneration == bindGeneration) vpnBound = false
            !stillCurrent
        }
        if (!bound) {
            vpnLatch.countDown()
            return
        }
        if (stale) {
            try {
                appContext.unbindService(serviceConnection)
            } catch (_: Exception) {
                // close() may already have invalidated and unbound this exact request.
            }
        }
    }

    private fun unbindVpnStrict() {
        val shouldUnbind = synchronized(lifecycleLock) {
            vpnService = null
            if (!vpnBound) {
                false
            } else {
                vpnBindGeneration = Math.addExact(vpnBindGeneration, 1L)
                true
            }
        }
        if (!shouldUnbind) return
        try {
            appContext.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Android uses IllegalArgumentException to report an already-unregistered connection.
            // That is equivalent to the desired terminal state.
        }
        synchronized(lifecycleLock) {
            vpnBound = false
            vpnService = null
        }
    }

    private fun nextMfiControlGeneration(): Long = synchronized(lifecycleLock) {
        mfiControlGeneration.updateAndGet { previous ->
            check(previous != Long.MAX_VALUE) { "MFi control generation exhausted" }
            previous + 1L
        }
    }

    /** Detaches under the controller lock, then runs authority callbacks without that lock held. */
    private fun invalidateMfiAuthenticationAttempts(): Long {
        val generation = nextMfiControlGeneration()
        val (currentSession, currentReceipt) = synchronized(lifecycleLock) {
            mfiSession to mfiAuthenticationReceipt.also { mfiAuthenticationReceipt = null }
        }
        currentSession?.invalidateAuthenticationAttempts(generation)
        currentReceipt?.revoke()
        appLinksControl.refreshAuthorization()
        return generation
    }

    private fun closeMfiSession() {
        val (session, receipt) = synchronized(lifecycleLock) {
            mfiSession.also {
                mfiSession = null
                if (it != null) pendingMfiSessionRetirements.addLast(it)
            } to mfiAuthenticationReceipt.also { mfiAuthenticationReceipt = null }
        }
        receipt?.revoke()
        appLinksControl.refreshAuthorization()
        if (session != null) {
            try {
                executor.execute {
                    closeBestEffort("retired MFi session") {
                        closePendingMfiSessionsStrict()
                    }
                }
            } catch (failure: Exception) {
                debugLog("MFi retirement executor rejected cleanup; retry retained", failure)
            }
        }
    }

    /** Publishes one fully captured certificate generation or closes it when the attempt is stale. */
    private fun publishMfiSession(candidate: MfiSession, generation: Int): Boolean {
        var previousReceipt: MfiAuthenticationReceipt? = null
        val published = synchronized(lifecycleLock) {
            if (closed || phase != Phase.MFI || generation != mfiGeneration.get() || mfiSession != null) {
                false
            } else {
                mfiSession = candidate
                previousReceipt = mfiAuthenticationReceipt
                mfiAuthenticationReceipt = null
                true
            }
        }
        previousReceipt?.revoke()
        if (!published) {
            synchronized(lifecycleLock) {
                pendingMfiSessionRetirements.addLast(candidate)
            }
            closeBestEffort("stale MFi session") { closePendingMfiSessionsStrict() }
        }
        return published
    }

    /** Publishes an Active fallback receipt or an exact lifecycle-consumed authorization receipt. */
    private fun publishInitialAuthorizationReceipt(
        session: MfiSession,
        receipt: MfiAuthenticationReceipt,
    ): ControllerUltraAuthorizationRuntime.ReceiptPublication = synchronized(lifecycleLock) {
        val exactSession = mfiSession === session
        val identityMatches = receipt.authenticationIdentity == session.authenticationIdentity
        val slotIdle = mfiAuthenticationReceipt == null
        if (closed || !exactSession || !identityMatches || !slotIdle) {
            ControllerUltraAuthorizationRuntime.ReceiptPublication.REJECTED
        } else if (
            canPublishInitialAuthorizationReceipt(
                closed = false,
                exactMfiSession = true,
                identityMatches = true,
                receiptIsCurrent = receipt.isCurrentAuthenticationAttempt(),
                receiptIsLiveForAuthorization = receipt.isLiveForUltraActivation(),
                receiptSlotIdle = true,
            )
        ) {
                mfiAuthenticationReceipt = receipt
            ControllerUltraAuthorizationRuntime.ReceiptPublication.PUBLISHED
        } else {
            ControllerUltraAuthorizationRuntime.ReceiptPublication.RETIRED_AFTER_DENIAL
        }
    }

    private fun replaceFallbackControllerReceipt(
        session: MfiSession,
        predecessor: MfiAuthenticationReceipt?,
        successor: MfiAuthenticationReceipt,
    ): Boolean = synchronized(lifecycleLock) {
        if (closed || mfiSession !== session || mfiAuthenticationReceipt !== predecessor ||
            successor.authenticationIdentity != session.authenticationIdentity ||
            !successor.isCurrentAuthenticationAttempt()
        ) {
            false
        } else {
            mfiAuthenticationReceipt = successor
            true
        }
    }

    /** Records only an AA05 receipt for the exact still-current certificate generation. */
    private fun recordMfiAuthenticationReceipt(
        session: MfiSession,
        receipt: MfiAuthenticationReceipt,
    ) {
        var previousReceipt: MfiAuthenticationReceipt? = null
        val accepted = synchronized(lifecycleLock) {
            if (closed || mfiSession !== session ||
                receipt.authenticationIdentity != session.authenticationIdentity ||
                !receipt.isCurrentAuthenticationAttempt()
            ) {
                false
            } else {
                previousReceipt = mfiAuthenticationReceipt
                mfiAuthenticationReceipt = receipt
                true
            }
        }
        previousReceipt?.revoke()
        if (!accepted) receipt.revoke()
    }

    private fun fail(error: Throwable) {
        if (error is MfiSessionConstructionCleanupException) {
            synchronized(lifecycleLock) {
                if (pendingMfiConstructionCleanups.none { it === error }) {
                    pendingMfiConstructionCleanups.addLast(error)
                }
            }
        }
        val fatal = when {
            error is Error -> error
            error is MfiSessionConstructionCleanupException && error.cause is Error ->
                error.cause as Error
            else -> null
        }
        if (fatal != null) throw fatal
        if (closed) return
        onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
    }

    private fun closeAuthorizationBeforeFatal(fatal: Error): Nothing {
        try {
            ultraAuthorization?.revokeAdmission()
            close()
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== fatal) fatal.addSuppressed(cleanupFailure)
        }
        throw fatal
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiBinding?.listener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiBinding?.listener?.onDebugLog(
                "$message: ${error.message ?: error.javaClass.simpleName}",
            )
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun onStatus(status: CarPlayStatus) {
        if (closed) return
        mainHandler.post {
            if (!closed && status != lastReportedStatus) {
                lastReportedStatus = status
                val binding = uiBinding
                binding?.listener?.onDebugLog(status.debugLogMessage())
                binding?.statusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val NOW_PLAYING_UPDATE = 0x5001
        private const val MEDIA_REMOTE_SEND_TIMEOUT_MILLIS = 2_000L
        private const val HID_REPORT_HOLD_MILLIS = 50L
        private const val MAX_PENDING_MFI_CLEANUP_OWNERS = 64
        private const val MAX_PENDING_PRE_MUX_USB_SESSIONS = 64
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CONTROL_BRING_UP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val WIRED_RESTART_DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val UPDATE_DATA_QUARANTINE_DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val APP_LINKS_CLOSE_TIMEOUT_MILLIS = 6_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}
