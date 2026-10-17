package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.DisplayDestinationId
import com.shilapi.xcertplay.ultra.DisplayDestinationRole
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * A DisplayManager observation is only a candidate. Its ID, type, presentation flag, name and
 * density are not proof that pixels reach a vehicle instrument cluster. In particular, Android
 * may include wireless and virtual displays in the presentation category.
 */
internal data class ExternalPhysicalDisplayCandidate(
    val displayId: Int,
    val displayEpoch: Long,
    val pixels: PixelSize,
) {
    init {
        require(displayId != Display.DEFAULT_DISPLAY)
        require(displayId >= 0)
        require(displayEpoch > 0)
    }
}

/**
 * Must be issued by a separately verified, fail-closed vehicle-display authority. The lease
 * binds an independently established connector/hardware identity to exactly one live Android
 * display ID/epoch and calibrated physical dimensions. No implementation is supplied here.
 */
internal interface ExternalPhysicalDisplayLease : Closeable {
    val stableConnectorIdentity: String
    val hardwareIdentity: String
    val displayId: Int
    val displayEpoch: Long
    val pixels: PixelSize
    val widthPhysicalMm: Int
    val heightPhysicalMm: Int
    /** Must agree with the signed runtime destination; never use CLUSTER/default. */
    val clusterInstanceId: DisplayDestinationId
    fun isCurrent(): Boolean
    fun subscribeRevocation(onRevoked: () -> Unit): Closeable
}

/** Immutable dimensions and connector lineage supplied by one independently verified lease. */
internal data class ExternalPhysicalDisplayCalibration(
    val stableConnectorIdentity: String,
    val hardwareIdentity: String,
    val displayId: Int,
    val displayEpoch: Long,
    val destination: DisplayDestination,
    val pixels: PixelSize,
    val widthPhysicalMm: Int,
    val heightPhysicalMm: Int,
) {
    init {
        require(stableConnectorIdentity.isNotBlank() && stableConnectorIdentity.length <= 128 &&
            stableConnectorIdentity.none(Char::isISOControl))
        require(hardwareIdentity.isNotBlank() && hardwareIdentity.length <= 128 &&
            hardwareIdentity.none(Char::isISOControl))
        require(displayId != Display.DEFAULT_DISPLAY && displayId >= 0 && displayEpoch > 0)
        require(destination.role == DisplayDestinationRole.CLUSTER)
        require(widthPhysicalMm in AirPlayDisplaySettings.MIN_REPORTED_PHYSICAL_MM..
            AirPlayDisplaySettings.MAX_REPORTED_PHYSICAL_MM)
        require(heightPhysicalMm in AirPlayDisplaySettings.MIN_REPORTED_PHYSICAL_MM..
            AirPlayDisplaySettings.MAX_REPORTED_PHYSICAL_MM)
    }
}

internal fun interface ExternalPhysicalDisplayTrustProvider {
    fun acquire(candidate: ExternalPhysicalDisplayCandidate): ExternalPhysicalDisplayLease?

    companion object {
        /** Product-safe default until an independent physical connector verifier exists. */
        val DENY_ALL = ExternalPhysicalDisplayTrustProvider { null }
    }
}

internal fun ExternalPhysicalDisplayLease.calibrationSnapshot(
    candidate: ExternalPhysicalDisplayCandidate,
): ExternalPhysicalDisplayCalibration? {
    // Read the lease once, then require the same frozen values on every later liveness check.
    // A mutable or stale provider cannot silently change millimetres, identity, or epoch.
    val calibration = runCatching {
        check(clusterInstanceId.value != "default")
        ExternalPhysicalDisplayCalibration(
            stableConnectorIdentity = stableConnectorIdentity,
            hardwareIdentity = hardwareIdentity,
            displayId = displayId,
            displayEpoch = displayEpoch,
            destination = clusterDestination(),
            pixels = pixels,
            widthPhysicalMm = widthPhysicalMm,
            heightPhysicalMm = heightPhysicalMm,
        )
    }.getOrNull() ?: return null
    return calibration.takeIf {
        it.displayId == candidate.displayId && it.displayEpoch == candidate.displayEpoch &&
            it.pixels == candidate.pixels && isCurrent()
    }
}

internal fun ExternalPhysicalDisplayLease.matches(candidate: ExternalPhysicalDisplayCandidate): Boolean =
    calibrationSnapshot(candidate) != null

internal fun ExternalPhysicalDisplayLease.clusterDestination(): DisplayDestination =
    DisplayDestination(DisplayDestinationRole.CLUSTER, clusterInstanceId)

/** Read-only event source. Registration and callbacks run on the supplied (normally main) Handler. */
internal interface ExternalPhysicalDisplaySource : Closeable {
    fun observe(onCandidatesChanged: (List<ExternalPhysicalDisplayCandidate>) -> Unit): Closeable
    fun isCurrent(candidate: ExternalPhysicalDisplayCandidate): Boolean
}

/** Listener registration is retired only when Android confirms unregister succeeded. */
internal class RetryableExternalDisplayListenerRegistration(
    private val unregister: () -> Unit,
) : Closeable {
    var registered: Boolean = false
        private set

    fun markRegistered() {
        check(!registered)
        registered = true
    }

    override fun close() {
        if (!registered) return
        unregister()
        registered = false
    }
}

/**
 * Android discovery only: never consult DisplayManager for physical-mm or connector trust.
 * Every add/change/remove event advances the epoch, including display-ID reuse. A new source
 * instance also gets fresh epochs, so an Activity recreation cannot resurrect an old lease.
 */
internal class AndroidExternalPhysicalDisplaySource(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ExternalPhysicalDisplaySource {
    private val displayManager = requireNotNull(
        context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager,
    )
    private val lock = Any()
    private val observers = LinkedHashSet<(List<ExternalPhysicalDisplayCandidate>) -> Unit>()
    private val epochs = HashMap<Int, Long>()
    private var closed = false

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed(displayId)
        override fun onDisplayChanged(displayId: Int) = changed(displayId)
        override fun onDisplayRemoved(displayId: Int) = changed(displayId)
    }
    private val registration = RetryableExternalDisplayListenerRegistration {
        displayManager.unregisterDisplayListener(listener)
    }

    override fun observe(
        onCandidatesChanged: (List<ExternalPhysicalDisplayCandidate>) -> Unit,
    ): Closeable {
        val snapshot: List<ExternalPhysicalDisplayCandidate>
        synchronized(lock) {
            check(!closed) { "External display source is closed" }
            if (!registration.registered) {
                // Events may have occurred while no observer was registered. Never reuse an
                // old display-ID epoch after that gap.
                epochs.clear()
                displayManager.registerDisplayListener(listener, handler)
                registration.markRegistered()
            }
            observers += onCandidatesChanged
            snapshot = runCatching { candidatesLocked() }.getOrDefault(emptyList())
        }
        try {
            onCandidatesChanged(snapshot)
        } catch (failure: Throwable) {
            synchronized(lock) {
                observers.remove(onCandidatesChanged)
                if (observers.isEmpty() && registration.registered) {
                    try {
                        registration.close()
                    } catch (_: Throwable) {
                        // observe() cannot return its subscription after an initial callback
                        // failure. Transfer its exact listener to process-owned retry.
                        ProcessOwnedExternalClusterCloseRetirer.retire(
                            ExactExternalClusterClose(this),
                        )
                    }
                }
            }
            throw failure
        }
        return Closeable {
            synchronized(lock) {
                observers.remove(onCandidatesChanged)
                if (observers.isEmpty()) registration.close()
            }
        }
    }

    override fun isCurrent(candidate: ExternalPhysicalDisplayCandidate): Boolean = synchronized(lock) {
        !closed && runCatching { candidatesLocked().contains(candidate) }.getOrDefault(false)
    }

    override fun close() {
        synchronized(lock) {
            // Logical closure denies all future callbacks immediately; failed unregister still
            // remains owned and is attempted again by the same close receipt.
            closed = true
            observers.clear()
            registration.close()
        }
    }

    private fun changed(displayId: Int) {
        val callbacks: List<(List<ExternalPhysicalDisplayCandidate>) -> Unit>
        val snapshot: List<ExternalPhysicalDisplayCandidate>
        synchronized(lock) {
            if (closed) return
            epochs[displayId] = nextEpoch.getAndIncrement()
            snapshot = runCatching { candidatesLocked() }.getOrDefault(emptyList())
            callbacks = observers.toList()
        }
        callbacks.forEach { callback -> runCatching { callback(snapshot) } }
    }

    private fun candidatesLocked(): List<ExternalPhysicalDisplayCandidate> =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).mapNotNull { display ->
            if (!display.isValid || display.displayId == Display.DEFAULT_DISPLAY) return@mapNotNull null
            val mode = display.mode
            if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return@mapNotNull null
            ExternalPhysicalDisplayCandidate(
                displayId = display.displayId,
                displayEpoch = epochs.getOrPut(display.displayId) { nextEpoch.getAndIncrement() },
                pixels = PixelSize(mode.physicalWidth, mode.physicalHeight),
            )
        }

    private companion object {
        val nextEpoch = AtomicLong(1L)
    }
}
