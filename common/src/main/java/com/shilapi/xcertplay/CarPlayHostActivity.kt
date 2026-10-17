package com.shilapi.xcertplay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.AirPlayPhysicalDisplayPanelsLiveness
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlaySessionRuntimeBinding
import com.shilapi.xcertplay.airplay.AirPlayUiSyncInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.CarPlayUpdateDataSessionOverlayFactory
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataLimits
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineQuota
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineRootOwner
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.components.Banner
import com.shilapi.xcertplay.ui.components.BannerKind
import com.shilapi.xcertplay.ui.components.FieldRow
import com.shilapi.xcertplay.ui.components.HelpText
import com.shilapi.xcertplay.ui.components.KeyValueList
import com.shilapi.xcertplay.ui.components.LargeTopBar
import com.shilapi.xcertplay.ui.components.LinearMeter
import com.shilapi.xcertplay.ui.components.ListRow
import com.shilapi.xcertplay.ui.components.LvIcon
import com.shilapi.xcertplay.ui.components.LvIconButton
import com.shilapi.xcertplay.ui.components.PillKind
import com.shilapi.xcertplay.ui.components.ProvideGroupColor
import com.shilapi.xcertplay.ui.components.SectionHeader
import com.shilapi.xcertplay.ui.components.SegmentedRow
import com.shilapi.xcertplay.ui.components.SelectRow
import com.shilapi.xcertplay.ui.components.SettingsGroup
import com.shilapi.xcertplay.ui.components.SliderRow
import com.shilapi.xcertplay.ui.components.SmallTopBar
import com.shilapi.xcertplay.ui.components.StatusPill
import com.shilapi.xcertplay.ui.components.SwitchRow
import com.shilapi.xcertplay.ui.components.TrailingValue
import com.shilapi.xcertplay.ui.components.tabular
import com.shilapi.xcertplay.ui.home.HomeScreen
import com.shilapi.xcertplay.ui.home.StreamingLogOverlay
import com.shilapi.xcertplay.ui.i18n.I18n
import com.shilapi.xcertplay.ui.i18n.tr
import com.shilapi.xcertplay.ui.i18n.trOr
import com.shilapi.xcertplay.ui.theme.LavenderCode
import com.shilapi.xcertplay.ui.theme.LavenderDark
import com.shilapi.xcertplay.ui.theme.LavenderLight
import com.shilapi.xcertplay.ui.theme.Radius
import com.shilapi.xcertplay.ui.theme.Space
import com.shilapi.xcertplay.ui.theme.XcertplayTheme
import com.shilapi.xcertplay.vehicle.VehicleDataConfig
import com.shilapi.xcertplay.vehicle.VehicleDataFeature
import com.shilapi.xcertplay.vehicle.VehicleLocationProvider
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.MainMediaAudioBuffer
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import com.shilapi.xcertplay.media.MicrophoneGain
import com.shilapi.xcertplay.media.MicrophoneLevelMonitor
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.ControllerAppLinksSnapshot
import com.shilapi.xcertplay.orchestration.ControllerAppLinksIcon
import com.shilapi.xcertplay.orchestration.ControllerAppLinksIconState
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ControllerUltraAuthorizationRuntime
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.iap2.message.Iap2AppLinksCategory
import com.shilapi.xcertplay.iap2.message.Iap2AppLinkEntry
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Builds the single type-111 geometry independently of its local/browser output destinations. */
internal fun clusterDisplayConfig(
    enabled: Boolean,
    geometry: CarPlayWebDisplayConfig,
    widthPhysicalMm: Int,
    fps: Int,
    physicalSizeBasis: AirPlayPhysicalSizeBasis = AirPlayPhysicalSizeBasis.WIDTH,
): AirPlayDisplayConfig? = if (!enabled) {
    null
} else {
    val physical = fixedDisplayPhysicalSize(geometry, widthPhysicalMm, physicalSizeBasis)
    AirPlayDisplayConfig(
        widthPixels = geometry.width,
        heightPixels = geometry.height,
        widthPhysicalMm = physical.widthMm,
        heightPhysicalMm = physical.heightMm,
        fps = fps,
        primaryInputDevice = 0,
    )
}

/** A declared type-111 stream must retain a visible local sink if no browser owns it. */
internal fun requireClusterPreviewIfOnlyOutput(
    clusterDisplayEnabled: Boolean,
    config: CarPlayWebDisplayConfig,
): CarPlayWebDisplayConfig = if (clusterDisplayEnabled && !config.enabled && !config.preview) {
    config.copy(preview = true)
} else {
    config
}

/** A browser-only main stream does not make the hidden in-window Surface a physical DDP panel. */
internal fun mainVideoSurfaceVisibleForDdp(
    viewShown: Boolean,
    webDisplay: CarPlayWebDisplayConfig,
): Boolean = viewShown && (!webDisplay.enabled || webDisplay.preview)

/** Ten units are one percent; ignore near-black codec noise without storing sampled pixels. */
internal fun mainTextureNonBlackPercentTenths(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 0
    val nonBlack = pixels.count { pixel ->
        ((pixel ushr 16) and 0xff) > 12 ||
            ((pixel ushr 8) and 0xff) > 12 ||
            (pixel and 0xff) > 12
    }
    return (nonBlack.toLong() * 1_000L / pixels.size).toInt()
}

/** A fixed browser/display geometry is calibrated from itself, never from Activity orientation. */
internal fun fixedDisplayPhysicalSize(
    geometry: CarPlayWebDisplayConfig,
    referenceMillimeters: Int,
    basis: AirPlayPhysicalSizeBasis,
): AirPlayPhysicalSizeMm = AirPlayDisplaySettings.resolvePhysicalSizeMm(
    currentWidthPixels = geometry.width,
    currentHeightPixels = geometry.height,
    maximumWidthPixels = geometry.width,
    maximumHeightPixels = geometry.height,
    referenceMillimeters = referenceMillimeters,
    basis = basis,
)

/** The experimental stack must be explicitly enabled and has only been observed wirelessly. */
internal fun sessionManagementInfo(
    ultraEnabled: Boolean,
    transport: CarPlayTransport,
): AirPlaySessionManagementInfo? = if (
    ultraEnabled && transport == CarPlayTransport.WIRELESS
) {
    AirPlaySessionManagementInfo(
        stopSession = 1L,
        stopSessionReasons = listOf(1L, 2L),
    )
} else {
    null
}

/** The Debug Ultra opt-in may advertise only the AppLinks messages we actually implement. */
internal fun hostAppLinksIdentificationEnabled(
    ultraEnabled: Boolean,
    debugBenchAvailable: Boolean,
): Boolean = ultraEnabled && debugBenchAvailable

/** Optional AD03/AD04 are identified up front, regardless of the later None/64px choice. */
internal fun hostAppLinksIconIdentificationEnabled(
    ultraEnabled: Boolean,
    debugBenchAvailable: Boolean,
): Boolean = hostAppLinksIdentificationEnabled(ultraEnabled, debugBenchAvailable)

internal fun hostAppLinksOverlayVisible(
    debuggable: Boolean,
    ultraEnabled: Boolean,
    streamActive: Boolean,
    menuOpen: Boolean,
    safeAreaEditorActive: Boolean,
): Boolean = debuggable && ultraEnabled && streamActive && !menuOpen && !safeAreaEditorActive

/** Debug-only choices; None preserves the existing AD00 wire shape with no size field. */
internal enum class HostAppLinksPreferredIconSize(val pixels: Int?) {
    NONE(null),
    PIXELS_64(64),
}

internal fun hostAppLinksEntrySummary(entry: Iap2AppLinkEntry): String =
    "${entry.localizedName} · category ${entry.categoryId}" +
        if (entry.iconHash != null) " · icon hash metadata present" else ""

internal fun hostCanRequestAppLinksIcon(
    snapshot: ControllerAppLinksSnapshot,
    entry: Iap2AppLinkEntry,
): Boolean {
    val update = snapshot.latestUpdate ?: return false
    return snapshot.available && snapshot.subscribed && !snapshot.busy &&
        !snapshot.stopUncertain && snapshot.iconFeatureAvailable &&
        snapshot.preferredIconSize?.let { it > 0 } == true && update.state == 2 &&
        update.entries.singleOrNull { it.index == entry.index }?.let {
            it.bundleIdentifier == entry.bundleIdentifier && it.iconHash == entry.iconHash
        } == true && entry.iconHash != null && !snapshot.iconRequestAttempted
}

/** This must run on the bounded decoder worker, never from Compose or the main thread. */
internal fun hostDecodeAppLinksIcon(icon: ControllerAppLinksIcon): ImageBitmap? {
    val bytes = icon.bytes()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth != icon.width || bounds.outHeight != icon.height ||
        bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048
    ) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
    }) ?: return null
    if (bitmap.width !in 1..128 || bitmap.height !in 1..128) {
        bitmap.recycle()
        return null
    }
    return bitmap.asImageBitmap()
}

/** One identity-bound decode at a time; cancellation drops even a decoder that ignores interrupt. */
internal class HostAppLinksIconDecodeGate<Key : Any, Decoded : Any>(
    private val submit: (() -> Unit) -> Closeable?,
    private val postToUi: (() -> Unit) -> Boolean,
    private val decode: (Key) -> Decoded?,
    private val onResult: (Long, Key, Decoded?) -> Unit,
) : Closeable {
    private class Request<Key : Any>(
        val ownerRevision: Long,
        val key: Key,
        var cancellation: Closeable? = null,
    )

    private val lock = Any()
    private var current: Request<Key>? = null
    private var closed = false

    fun request(ownerRevision: Long, key: Key): Boolean {
        val (next, previous) = synchronized(lock) {
            if (closed) return false
            val existing = current
            if (existing != null && existing.ownerRevision == ownerRevision && existing.key === key) {
                return true
            }
            val replacement = Request(ownerRevision, key)
            current = replacement
            replacement to existing?.cancellation
        }
        runCatching { previous?.close() }
        val cancellation = try {
            submit { runDecode(next) }
        } catch (_: RuntimeException) {
            null
        }
        if (cancellation == null) {
            publish(next, null)
            return false
        }
        val stale = synchronized(lock) {
            if (closed || current !== next) true else {
                next.cancellation = cancellation
                false
            }
        }
        if (stale) runCatching { cancellation.close() }
        return !stale
    }

    fun clear() {
        val cancellation = synchronized(lock) {
            current?.cancellation.also { current = null }
        }
        runCatching { cancellation?.close() }
    }

    override fun close() {
        val cancellation = synchronized(lock) {
            closed = true
            current?.cancellation.also { current = null }
        }
        runCatching { cancellation?.close() }
    }

    private fun runDecode(request: Request<Key>) {
        if (!isCurrent(request)) return
        val result = try {
            decode(request.key)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        publish(request, result)
    }

    private fun publish(request: Request<Key>, result: Decoded?) {
        if (!isCurrent(request)) return
        postToUi {
            if (isCurrent(request)) {
                onResult(request.ownerRevision, request.key, result)
            }
        }
    }

    private fun isCurrent(request: Request<Key>): Boolean = synchronized(lock) {
        !closed && current === request
    }
}

/** Construction policy kept pure so Ultra opt-in cannot accidentally alter ordinary CarPlay. */
internal data class HostUltraRuntimePlan(
    val createAuthorization: Boolean,
    val createFallbackRuntime: Boolean,
    val timedVideoPresentation: Boolean,
)

internal fun hostUltraRuntimePlan(
    ultraEnabled: Boolean,
    sessionManagementInfo: AirPlaySessionManagementInfo?,
): HostUltraRuntimePlan {
    require(sessionManagementInfo == null || ultraEnabled) {
        "SessionManagement fallback requires the explicit Ultra opt-in"
    }
    return HostUltraRuntimePlan(
        createAuthorization = ultraEnabled,
        createFallbackRuntime = sessionManagementInfo != null,
        // A later authorized UI Sync result cannot rebuild the sink. Ordinary fallback sessions
        // still produce no presentation target because UI Sync was not negotiated.
        timedVideoPresentation = ultraEnabled,
    )
}

internal fun hasHostUltraFallbackParity(
    config: AirPlayConfig,
    fallbackRuntime: AirPlayUltraRuntime?,
): Boolean = (config.ultra == null) == (fallbackRuntime == null)

/** Never hold ordinary/opted-out startup for an external producer the product cannot trust. */
internal fun shouldAwaitTrustedExternalOutput(
    ultraEnabled: Boolean,
    clusterEnabled: Boolean,
    probeEnabled: Boolean,
    trustProvider: ExternalPhysicalDisplayTrustProvider,
): Boolean = ultraEnabled && clusterEnabled && probeEnabled &&
    trustProvider !== ExternalPhysicalDisplayTrustProvider.DENY_ALL

internal fun hostCatalogProbeOrNull(
    settings: CarPlayAssetCatalogProbeSettings,
    ultraEnabled: Boolean,
) = settings.toProbeOrNull(ultraEnabled)

internal fun startThenPublishBackgroundSession(
    start: () -> Unit,
    publish: () -> Unit,
) {
    start()
    publish()
}

/** One exact construction owner; a throwing head stage remains available for the next retry. */
internal class HostConstructionCleanupStage(
    val name: String,
    val retire: () -> Unit,
)

internal class HostConstructionCleanup(
    stages: List<HostConstructionCleanupStage>,
) : Closeable {
    private val pending = ArrayDeque(stages)

    @Synchronized
    override fun close() {
        while (true) {
            val stage = pending.peekFirst() ?: return
            stage.retire()
            check(pending.peekFirst() === stage) {
                "Host construction cleanup stage changed during retirement"
            }
            pending.removeFirst()
        }
    }

    @Synchronized
    internal fun pendingStageName(): String? = pending.peekFirst()?.name
}

/** The sink, not an Activity, retains its exact WebDisplay and external Presentation owners. */
internal class HostSinkOutputLifecycle(
    private val webOutputs: CarPlayWebDisplayFactory.Session?,
    private val videoFrameReleaseRelay: HostVideoFrameReleaseRelay? = null,
) : Closeable {
    private var externalOutput: HostExternalOutputOwner? = null

    @Synchronized
    fun attachExternal(owner: HostExternalOutputOwner) {
        check(externalOutput == null) { "This sink already owns an external output" }
        externalOutput = owner
    }

    @Synchronized
    override fun close() {
        // Retire callbacks before any retryable output teardown can leave the sink alive.
        videoFrameReleaseRelay?.close()
        externalOutput?.let { owner ->
            owner.closeAndRequireRetired()
        }
        webOutputs?.close()
    }
}

/** A pre-Controller wait retains resources only; its completion callback holds the Activity weakly. */
private class PendingHostExternalBootstrap(
    val generation: Int,
    val width: Int,
    val height: Int,
    val runtimeConfig: CarPlayRuntimeConfig,
    val viewAreas: CarPlayViewAreas?,
    val presentationCoordinator: ClusterPresentationCoordinator?,
    val ultraPlan: HostUltraRuntimePlan,
    val fallbackRuntime: AirPlayUltraRuntime?,
    val sink: AndroidMediaSink,
    val videoFrameReleaseRelay: HostVideoFrameReleaseRelay?,
    val staged: HostUltraTwoPhaseConfig,
    val ticket: HostUltraTwoPhaseConfig.Ticket,
    val owner: HostExternalOutputOwner,
    val cleanup: HostConstructionCleanup,
) {
    var outcome: HostExternalOutputOwner.Outcome? = null
}

/** A received theme remains untrusted. This acquisition owns its root until construction succeeds. */
internal class HostUpdateDataQuarantineAcquisition(
    val owner: CarPlayUpdateDataQuarantineRootOwner,
) : Closeable {
    val overlayFactory = CarPlayUpdateDataSessionOverlayFactory(owner)
    private var transferred = false

    @Synchronized
    fun transferToController() {
        check(!transferred) { "UpdateData root already transferred" }
        transferred = true
    }

    @Synchronized
    override fun close() {
        if (!transferred) retireHostUpdateDataQuarantineRoot(owner)
    }
}

/** Only a caller-owned, existing app-private directory may hold untrusted theme bytes. */
internal fun acquireHostUpdateDataQuarantine(
    enabled: Boolean,
    noBackupDirectory: File,
    onUnavailable: (Exception) -> Unit,
): HostUpdateDataQuarantineAcquisition? {
    if (!enabled) return null
    return try {
        val parent = noBackupDirectory.toPath()
        if (!noBackupDirectory.isAbsolute || parent != parent.normalize()) {
            throw IOException("App-private no-backup directory is not an absolute normalized path")
        }
        val parentAttributes = Files.readAttributes(
            parent, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        if (!parentAttributes.isDirectory || parentAttributes.isSymbolicLink) {
            throw IOException("App-private no-backup directory is not a real directory")
        }
        val root = parent.resolve(HOST_UPDATE_DATA_QUARANTINE_DIRECTORY)
        try {
            Files.createDirectory(root)
        } catch (_: FileAlreadyExistsException) {
            // Reuse committed and unconfirmed records; never clear or replace the root here.
        }
        val rootAttributes = Files.readAttributes(
            root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) {
            throw IOException("UpdateData quarantine root is not a real directory")
        }
        HostUpdateDataQuarantineAcquisition(
            CarPlayUpdateDataQuarantineRootOwner(
                root = root.toFile(),
                quota = CarPlayUpdateDataQuarantineQuota(
                    maxBytes = HOST_UPDATE_DATA_QUARANTINE_MAX_BYTES,
                    maxEntries = HOST_UPDATE_DATA_QUARANTINE_MAX_ENTRIES,
                ),
                limits = CarPlayUpdateDataLimits(
                    maxRcsBodyBytes = 2 * 1024 * 1024,
                    maxChunkBytes = 1024 * 1024,
                    maxMetadataBytes = 256 * 1024,
                    maxArchiveBytes = 512L * 1024 * 1024,
                    maxChunkCount = 16_384,
                ),
                maxConcurrentTransfers = 2,
            ),
        )
    } catch (error: Exception) {
        onUnavailable(error)
        null
    }
}

/** A failed close is retryable; the construction stage is removed only after the drain proves clean. */
internal fun retireHostUpdateDataQuarantineRoot(
    owner: CarPlayUpdateDataQuarantineRootOwner,
    timeoutMillis: Long = HOST_UPDATE_DATA_QUARANTINE_DRAIN_TIMEOUT_MILLIS,
) {
    var failure: Exception? = null
    try {
        owner.close()
    } catch (error: Exception) {
        failure = error
    }
    try {
        owner.retryFailedCleanups()
    } catch (error: Exception) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
    }
    val drained = try {
        owner.awaitDrained(timeoutMillis)
    } catch (error: Exception) {
        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        false
    }
    if (!drained) {
        val notDrained = IOException("UpdateData quarantine workers did not drain")
        failure?.let(notDrained::addSuppressed)
        throw notDrained
    }
    failure?.let { throw it }
}

private const val HOST_UPDATE_DATA_QUARANTINE_DIRECTORY = "carplay-update-data-quarantine-v1"
private const val HOST_UPDATE_DATA_QUARANTINE_MAX_BYTES = 2L * 1024 * 1024 * 1024
private const val HOST_UPDATE_DATA_QUARANTINE_MAX_ENTRIES = 16
private const val HOST_UPDATE_DATA_QUARANTINE_DRAIN_TIMEOUT_MILLIS = 10_000L

/** An explicit exit may stop the VPN service or kill the process only after every owner retires. */
internal fun canCompleteHostTermination(result: HostTeardownDrainResult): Boolean =
    result === HostTeardownDrainResult.Clear

/** Finds retry owners carried by a construction failure without looping through cause cycles. */
internal fun closeableHostConstructionFailures(failure: Throwable): List<Closeable> {
    val pending = ArrayDeque<Throwable>()
    val visited = IdentityHashMap<Throwable, Unit>()
    val owners = ArrayList<Closeable>()
    pending.addLast(failure)
    while (pending.isNotEmpty() && visited.size < MAX_HOST_CONSTRUCTION_FAILURE_GRAPH) {
        val current = pending.removeFirst()
        if (visited.put(current, Unit) != null) continue
        if (current is Closeable && owners.none { it === current }) owners += current
        current.suppressed.forEach(pending::addLast)
        current.cause?.let(pending::addLast)
    }
    return owners
}

private const val MAX_HOST_CONSTRUCTION_FAILURE_GRAPH = 64

/** Safe constructor-time binding; the Activity lease is attached only after construction. */
private object DetachedAirPlaySessionListener : AirPlaySessionListener

/** Process-barrier payload containing display resources only, never an Activity closure. */
private class DeferredDisplaySurfaceRelease(
    private val surface: Surface,
    private val texture: SurfaceTexture?,
) : () -> Unit {
    private var surfaceReleased = false
    private var textureReleased = false

    @Synchronized override fun invoke() {
        if (!surfaceReleased) {
            surface.release()
            surfaceReleased = true
        }
        if (!textureReleased) {
            texture?.release()
            textureReleased = true
        }
    }
}

/** Retryable identity-based sink detach; this process-owned operation retains no Activity. */
private class RetryDisplaySurfaceDetach(
    private val sink: AndroidMediaSink?,
    private val webDisplay: CarPlayWebDisplayFactory.Session?,
    private val type: Int,
    private val surface: Surface,
) : () -> Unit {
    override fun invoke() {
        webDisplay?.clearPreview(type, surface)
        sink?.clearSurface(type, surface)
    }
}

internal class CurrentHostDisplaySurfaceRoute(
    private val webDisplay: CarPlayWebDisplayFactory.Session?,
    private val sink: AndroidMediaSink?,
    private val reportUnavailable: (Int, Throwable?) -> Unit = { type, failure ->
        if (failure == null) {
            Log.w("CarPlayHostActivity", "Host Surface unavailable during physical handoff type=$type")
        } else {
            Log.w("CarPlayHostActivity", "Host Surface attachment unavailable type=$type", failure)
        }
    },
) : HostDisplaySurfaceRoutePort {
    override fun webDisplayOwns(type: Int): Boolean = webDisplay?.owns(type) == true
    override fun preview(type: Int, surface: Surface?) {
        try {
            if (webDisplay?.preview(type, surface) == false) reportUnavailable(type, null)
        } catch (failure: Exception) {
            // TextureView callbacks must not crash the Activity. The exact sink/Host owners keep
            // their retryable resources; the Activity retains its cleanup association.
            reportUnavailable(type, failure)
        }
    }
    override fun clearPreview(type: Int, expected: Surface) {
        webDisplay?.clearPreview(type, expected)
    }
    override fun setDecoderSurface(type: Int, surface: Surface) {
        try {
            if (sink?.trySetSurfaceForPreview(type, surface) == false) {
                reportUnavailable(type, null)
            }
        } catch (failure: Exception) {
            reportUnavailable(type, failure)
        }
    }
    override fun clearDecoderSurface(type: Int, surface: Surface) {
        sink?.clearSurface(type, surface)
    }
}

private class CloseControllerOperation(
    private val controller: CarPlayController,
) : () -> Unit {
    override fun invoke() = controller.close()
}

private class AwaitControllerOperation(
    private val controller: CarPlayController,
    private val timeoutMillis: Long,
) : () -> Boolean {
    override fun invoke(): Boolean = controller.awaitClosed(timeoutMillis)
}

private class CloseMediaSinkOperation(
    private val sink: AndroidMediaSink,
) : () -> Unit {
    override fun invoke() = sink.close()
}

/** Process-barrier operation retaining only an exact failed construction owner. */
private class CloseHostConstructionOperation(
    private val cleanup: HostConstructionCleanup,
) : () -> Unit {
    override fun invoke() = cleanup.close()
}

/** One exact process-barrier claim per attempt; no Activity or active-session lookup. */
internal class HostProcessRetirementRunner(
    private val barrier: HostTeardownCoordinator,
    private val scheduleWork: (Long, () -> Unit) -> Unit,
    private val reportFailure: (Throwable) -> Unit = {},
) {
    private val scheduled = AtomicBoolean(false)

    fun request() = schedule(0L)

    private fun schedule(delayMillis: Long) {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            scheduleWork(delayMillis, ::drain)
        } catch (failure: Throwable) {
            scheduled.set(false)
            reportFailure(failure)
            // The process barrier still retains the exact cleanup for the next Activity.
        }
    }

    private fun drain() {
        // One process-barrier claim per attempt keeps this worker bounded. The barrier contains
        // only explicitly retired exact resources; it never discovers a replacement background
        // Controller or sink by type, Activity, or static session lookup.
        val retryDelay = try {
            when (val claim = barrier.claim()) {
                HostTeardownClaimResult.Clear -> null
                HostTeardownClaimResult.InFlight -> 500L
                is HostTeardownClaimResult.Claimed -> when (val outcome = barrier.execute(claim.claim)) {
                    HostTeardownOutcome.Clean -> 0L
                    is HostTeardownOutcome.Failed -> {
                        reportFailure(outcome.cause ?: IllegalStateException(
                            "Process Host cleanup retry ${outcome.stage.logId}",
                        ))
                        2_000L
                    }
                }
            }
        } catch (failure: Throwable) {
            reportFailure(failure)
            2_000L
        }
        scheduled.set(false)
        if (barrier.state() != HostTeardownBarrierState.CLEAR) {
            schedule(retryDelay ?: 0L)
        }
    }
}

/** Drains cancelled pre-Controller construction after its Activity has been destroyed. */
private object ProcessHostConstructionRetirer {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "host-construction-retirement").apply { isDaemon = true }
    }
    private val runner = HostProcessRetirementRunner(
        HostTeardownProcessBarrier.coordinator,
        scheduleWork = { delay, action ->
            executor.schedule(action, delay, TimeUnit.MILLISECONDS)
        },
        reportFailure = { failure ->
            Log.w("CarPlayHostActivity", "Process Host cleanup retry", failure)
        },
    )

    fun request() = runner.request()
}

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
        val physicalPanelsProbeEnabled: Boolean,
        val catalogProbeEnabled: Boolean,
    )

    private data class DecodedAppLinksIcon(
        val ownerRevision: Long,
        val source: ControllerAppLinksIcon,
        val bitmap: ImageBitmap,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            appLinksEnabled = hostAppLinksIdentificationEnabled(
                carPlayUltraEnabled,
                DebugBenchStageLauncher.isAvailable,
            ),
            appLinksIconsEnabled = hostAppLinksIconIdentificationEnabled(
                carPlayUltraEnabled,
                DebugBenchStageLauncher.isAvailable,
            ),
            // Only when this build can actually reach Ultra; see themeAssetsAdvertisable.
            themeAssetsCapable = UltraProfileCompositionRoot.themeAssetsAdvertisable(
                this, carPlayUltraEnabled,
            ),
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
        clusterAssetCatalogProbe = hostCatalogProbeOrNull(
            settings = catalogProbeSettings,
            ultraEnabled = carPlayUltraEnabled,
        ),
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private var vehicleSpeedPermissionPending = false
    private var vehicleSpeedPermissionAttempted = false
    private val vehicleSpeedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vehicleSpeedPermissionPending = false
            appendLog(if (granted) "Vehicle speed permission granted; save and reconnect to retry the source"
                else "Vehicle speed permission denied; wheel speed remains unavailable")
            updateVehicleDataStatus()
            if (!menuOpen) requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "certificate")) {
                localMfiCertificateUri = uri.toString()
                mfiError = null
            }
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "private key")) {
                localMfiPrivateKeyUri = uri.toString()
                mfiError = null
            }
        }

    // Settings and UI state is snapshot state, so the Compose UI follows every change.
    private var mainWebDisplay by mutableStateOf(CarPlayWebDisplayConfig())
    private var clusterWebDisplay by mutableStateOf(CarPlayWebDisplayConfig(width = 1920))
    private var clusterDisplayEnabled by mutableStateOf(false)
    private var carPlayUltraEnabled by mutableStateOf(false)
    private var ultraSaveBlocked by mutableStateOf(false)
    private var physicalDisplayPanelsProbeEnabled by mutableStateOf(false)
    private var catalogProbeSettings by mutableStateOf(CarPlayAssetCatalogProbeSettings())
    private var catalogProbeConfigError by mutableStateOf(false)
    private val webDisplays get() = CarPlayWebDisplayFactory.find(sink)
    private var clusterPreview: TextureView? = null
    private var clusterPreviewSurface: Surface? = null
    private var clusterPreviewSurfaceTexture: SurfaceTexture? = null
    private var clusterPreviewWindow: FrameLayout? = null
    private var clusterPreviewHandle: android.widget.ImageView? = null
    private var clusterPreviewMinimize: android.widget.ImageView? = null
    private var clusterWindowX = 0f
    private var clusterWindowY = 0f
    private var clusterWindowScale = 1f
    private var clusterPreviewContainer: FrameLayout? = null
    private var clusterPreviewCollapsed = false
    private val webDisplayDrafts = mutableStateMapOf<Int, WebDisplayDraft>()
    private var webDisplayAddresses by mutableStateOf<Map<Int, String>>(emptyMap())
    private var videoView: TextureView? = null
    private var contentRoot: FrameLayout? = null
    private var mainFrame: FrameLayout? = null
    private var mainPhysicalCoverOwner: MainPhysicalUiCoverOwner? = null
    private var mainPhysicalCoverRegistration:
        HostPhysicalDisplaySurfaceRegistry.MainPhysicalUiCoverRegistration? = null
    private class PendingMainOrdinaryRecovery(
        val generation: Int,
        val oldView: TextureView,
        val oldSurface: Surface,
        val oldTexture: SurfaceTexture,
        val oldController: CarPlayController?,
        val oldSink: AndroidMediaSink?,
        val borrowRetirement:
            HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
        val teardownTicket: HostTeardownTicket,
    ) {
        var viewRemoved = false
    }
    private class MainOrdinaryRecovery(
        val generation: Int,
        val retirement: HostTeardownRetirementReceipt,
        val oldController: CarPlayController?,
        val oldSink: AndroidMediaSink?,
        val borrowRetirement:
            HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    ) {
        var freshTexture: SurfaceTexture? = null
        var attachedController: CarPlayController? = null
        var attachedSink: AndroidMediaSink? = null
    }
    private var pendingMainOrdinaryRecovery: PendingMainOrdinaryRecovery? = null
    private var mainOrdinaryRecovery: MainOrdinaryRecovery? = null
    private var gestureOverlay: View? = null
    private var mfiError by mutableStateOf<String?>(null)
    private var settingsBaseline: SettingsBaseline? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var micTestRunning by mutableStateOf(false)
    private var micPeakPercent by mutableIntStateOf(0)
    private var micLevelText by mutableStateOf("0%")
    private var connectionStatus by mutableStateOf("Preparing CarPlay")
    private var handshakeTimeline by mutableStateOf<HandshakeTimeline?>(null)
    private var timelineRevision by mutableIntStateOf(0)
    private var timelineRenderScheduled = false
    private var lastAttemptFailure: String? = null
    private var configSummary by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var manualHotspotError by mutableStateOf<String?>(null)
    private var manualHotspotChannelText by mutableStateOf("0")
    private var airPlayIcon by mutableStateOf<ImageBitmap?>(null)
    private var airPlayIconCustom by mutableStateOf(false)
    private var safeAreaSummaryText by mutableStateOf("")
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive by mutableStateOf(false)
    private var mediaMetricsOverlay: MediaMetricsOverlayView? = null
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var pendingHostExternalBootstrap: PendingHostExternalBootstrap? = null
    private var controllerUiAttachment: Closeable? = null
    private var appLinksUiAttachment: Closeable? = null
    private var sinkUiAttachment: Closeable? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var mainTextureDiagnosticGeneration = -1
    private var mainTextureDiagnosticSession: AirPlaySession? = null
    private var mainTextureDiagnosticTexture: SurfaceTexture? = null
    private var mainTextureDiagnosticUpdateCount = 0L
    private var mainTextureDiagnosticLastSampleMs = Long.MIN_VALUE
    private val physicalSurfaceRegistry = HostPhysicalDisplaySurfaces.registry
    private data class AttachedWebPreview(
        val type: Int,
        val session: CarPlayWebDisplayFactory.Session,
    )
    /** Exact cleanup association for an attempted preview, not proof it visibly attached. */
    private val attachedWebPreviews = IdentityHashMap<Surface, AttachedWebPreview>()
    private val unavailableWebPreviewsLogged = mutableSetOf<Int>()
    private var activeDisplaySize: DisplaySize? = null
    private data class DisplayLayout(val splitScreen: Boolean, val portrait: Boolean)
    private fun currentDisplayLayout() = DisplayLayout(
        isInMultiWindowMode,
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
    )
    private var activeDisplayLayout: DisplayLayout? = null
    private var pendingDisplayLayout: DisplayLayout? = null
    private var pendingDisplaySize: DisplaySize? = null
    /** View areas declared by the current controller; null for a web display or no controller. */
    private var viewAreas: CarPlayViewAreas? = null
    private var displayScaleTenths by mutableIntStateOf(CarPlayDisplayScale.DEFAULT_TENTHS)
    private var hevcEnabled by mutableStateOf(true)
    private var hevcSoftwareDecoderEnabled by mutableStateOf(false)
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping by mutableStateOf(false)
    private var audioFocusEnabled by mutableStateOf(true)
    private var mainMediaAudioBufferDurationMs by mutableIntStateOf(MainMediaAudioBuffer.DEFAULT_DURATION_MS)
    // Snapshot state is safe to read from the media and log threads.
    private var debugLogsEnabled by mutableStateOf(false)
    private var videoFrameLogsEnabled by mutableStateOf(false)
    private var mediaMetricsEnabled by mutableStateOf(false)
    private var audioPacketCaptureEnabled by mutableStateOf(false)
    private var moreGesturesToSettings by mutableStateOf(false)
    private var autoStartOnBoot by mutableStateOf(false)
    private var manufacturer by mutableStateOf(AirPlayPersistence.DEFAULT_MANUFACTURER)
    private var model by mutableStateOf(AirPlayPersistence.DEFAULT_MODEL)
    private var oemLabel by mutableStateOf(AirPlayPersistence.DEFAULT_OEM_LABEL)
    private var fps by mutableIntStateOf(AirPlayDisplaySettings.DEFAULT_FPS)
    private var widthPhysicalMm by mutableIntStateOf(AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM)
    private var physicalSizeBasis by mutableStateOf(AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS)
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive by mutableStateOf(false)
    private var hideTopBar by mutableStateOf(true)
    private var hideBottomBar by mutableStateOf(true)
    private var safeAreaDrawOutside by mutableStateOf(true)
    private var currentVehicleProvider: com.shilapi.xcertplay.vehicle.CarPropertyVehicleProvider? = null
    private var currentGpsProvider: VehicleLocationProvider? = null
    private var currentWheelProvider: com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider? = null
    private var vehicleDataStatusText by mutableStateOf("")
    private var lastVehicleDataDiagnostics: String? = null
    private var vehicleDataConfig by mutableStateOf(VehicleDataConfig())
    private var locationReportingEnabled by mutableStateOf(false)
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var microphoneGainPercent by mutableIntStateOf(MicrophoneGain.DEFAULT_PERCENT)
    private var wirelessEnabled by mutableStateOf(false)
    private var mfiTarget by mutableStateOf(MfiTarget.USB_CH341)
    private var mfiI2cPath by mutableStateOf(AirPlayPersistence.DEFAULT_MFI_I2C_PATH)
    private var remoteMfiServer by mutableStateOf("")
    private var remoteMfiToken by mutableStateOf("")
    private var localMfiCertificateUri by mutableStateOf("")
    private var localMfiPrivateKeyUri by mutableStateOf("")
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode by mutableStateOf(WirelessHotspotMode.WIFI_P2P)
    private var manualHotspotSsid by mutableStateOf("")
    private var manualHotspotPassphrase by mutableStateOf("")
    private var manualHotspotBand by mutableStateOf(ManualHotspotBand.AUTO)
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity by mutableStateOf(ManualHotspotSecurity.OPEN)
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus by mutableStateOf(HotspotStatus(state = "off"))
    private var menuOpen by mutableStateOf(false)
    private var hostUiRequested by mutableStateOf(false)
    private var settingsCategory by mutableStateOf<SettingsCategory?>(null)
    private var settingsTwoPane = false
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private var clusterPresentationCoordinator: ClusterPresentationCoordinator? = null
    private var activeScreenStreamTypes = mutableSetOf<Int>()
    private var mainStreamActive by mutableStateOf(false)
    private var appLinksSnapshot by mutableStateOf(ControllerAppLinksSnapshot())
    private var decodedAppLinksIcon by mutableStateOf<DecodedAppLinksIcon?>(null)
    private var appLinksIconDecodeFailed by mutableStateOf(false)
    private var appLinksPanelOpen by mutableStateOf(false)
    private var appLinksSelectedCategories by mutableStateOf(setOf(Iap2AppLinksCategory.NAVIGATION.id))
    private var appLinksPreferredIconSize by mutableStateOf(HostAppLinksPreferredIconSize.NONE)
    private var handshakeResetInProgress by mutableStateOf(false)
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var screenLogs by mutableStateOf<List<ScreenLogLine>>(emptyList())
    private val sessionLog get() = CarPlayBackgroundSession.log
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var edgeSettingsGestureCaptured = false
    private var edgeSettingsGestureEligible = false
    private val shuttingDown = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private data class HostTeardownContinuation(
        val generation: Int,
        val onClear: () -> Unit,
        val onFailure: (HostTeardownOutcome.Failed) -> Unit,
    )
    private var hostTeardownContinuation: HostTeardownContinuation? = null
    private val hostTeardownDriveScheduled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appLinksIconDecodeExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
    ) { command -> Thread(command, "xcertplay-applinks-icon-decode").apply { isDaemon = true } }
    private val appLinksIconDecodeGate = HostAppLinksIconDecodeGate<ControllerAppLinksIcon, ImageBitmap>(
        submit = { task ->
            val future = FutureTask<Unit>(Runnable { task() }, Unit)
            try {
                appLinksIconDecodeExecutor.execute(future)
                Closeable {
                    future.cancel(true)
                    appLinksIconDecodeExecutor.remove(future)
                }
            } catch (_: RejectedExecutionException) {
                null
            }
        },
        postToUi = { action -> mainHandler.post(action) },
        decode = ::hostDecodeAppLinksIcon,
        onResult = { ownerRevision, source, bitmap ->
            val snapshot = appLinksSnapshot
            if (!destroyed.get() && snapshot.ownerRevision == ownerRevision &&
                snapshot.icon === source
            ) {
                decodedAppLinksIcon = bitmap?.let { DecodedAppLinksIcon(ownerRevision, source, it) }
                appLinksIconDecodeFailed = bitmap == null
            }
        },
    )
    private val teardownExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var shutdownRetryAttempts = 0
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingScreenLogsLock = Any()
    private var screenDrainPosted = false
    private val drainScreenLogs = Runnable {
        val pending = synchronized(pendingScreenLogsLock) {
            screenDrainPosted = false
            pendingScreenLogs.toList().also { pendingScreenLogs.clear() }
        }
        if (debugLogsEnabled && !menuOpen) {
            pending.forEach { entry ->
                if (entry.generation == restartGeneration) {
                    appendScreenLog(entry.timestampMillis, entry.message)
                }
            }
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var logRenderScheduled = false
    private val renderLogLines = Runnable {
        logRenderScheduled = false
        refreshLogView(System.currentTimeMillis())
    }
    private val retryHostTeardownBarrier = Runnable { launchHostTeardownDrain() }
    private val physicalRetirementPoller = HostPhysicalRetirementPoller(
        currentGeneration = { restartGeneration },
        hasPendingRetirement = {
            physicalSurfaceRegistry.hasPendingRetirement() ||
                HostExternalOutputOwner.hasPendingProcessRetirement()
        },
        requestRetirement = HostPhysicalDisplaySurfaces::requestRetirement,
        postDelayed = { callback, millis -> mainHandler.postDelayed(callback, millis) },
        removeCallbacks = mainHandler::removeCallbacks,
        onClear = { maybeStartCarPlay() },
    )
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (destroyed.get() || videoView?.surfaceTexture !== texture) return
            // A framework recreation of the old View cannot publish a successor while its
            // exact old ticket is still pending; the recovery path installs a new View later.
            if (pendingMainOrdinaryRecovery?.oldView === videoView) return
            mainOrdinaryRecovery?.takeIf { it.generation == restartGeneration }
                ?.freshTexture = texture
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.let { oldSurface ->
                        releaseDisplaySurfaceAfterRetirement(
                            SCREEN_TYPE_MAIN,
                            oldSurface,
                            currentSurfaceTexture,
                        )
                    }
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            val version = physicalSurfaceRegistry.publish(
                DisplayDestination.MAIN,
                HostDisplaySurfaceKind.PHYSICAL_MAIN,
                surface,
                texture,
                width,
                height,
            )
            if (mainPhysicalCoverRegistration?.let {
                    it.surface === surface && it.texture === texture && it.version == version
                } != true
            ) {
                mainPhysicalCoverRegistration?.close()
                mainPhysicalCoverRegistration = version?.let {
                    bindMainPhysicalCover(surface, texture, it)
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateWebDisplayPreviews()
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            if (currentSurfaceTexture !== texture) return
            currentSurface?.let { surface ->
                val version = physicalSurfaceRegistry.resize(
                    DisplayDestination.MAIN, surface, texture, width, height,
                )
                if (currentSurfaceTexture === texture &&
                    mainPhysicalCoverRegistration?.version != version
                ) {
                    mainPhysicalCoverRegistration?.close()
                    mainPhysicalCoverRegistration = version?.let {
                        bindMainPhysicalCover(surface, texture, it)
                    }
                }
            }
            updateWebDisplayPreviews()
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) {
                val frameworkMayRelease = noteTextureDestroyed(texture)
                if (pendingMainOrdinaryRecovery?.oldTexture === texture) {
                    mainHandler.post {
                        if (completePendingMainOrdinaryRecovery()) launchHostTeardownDrain()
                    }
                }
                return frameworkMayRelease
            }
            mainPhysicalCoverRegistration?.close()
            mainPhysicalCoverRegistration = null
            val frameworkMayReleaseTexture = currentSurface?.let { surface ->
                releaseDisplaySurfaceAfterRetirement(SCREEN_TYPE_MAIN, surface, texture, textureDestroyed = true)
            } ?: noteTextureDestroyed(texture)
            currentSurface = null
            currentSurfaceTexture = null
            mainTextureDiagnosticTexture = null
            if (pendingMainOrdinaryRecovery?.oldTexture === texture) {
                mainHandler.post {
                    if (completePendingMainOrdinaryRecovery()) launchHostTeardownDrain()
                }
            }
            appendLog("Texture surface destroyed")
            return frameworkMayReleaseTexture
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            // HWUI latches this frame on its RenderThread only after this UI-thread callback,
            // so texture.timestamp here still names the previous image (or 0). Observe the
            // latched image after this draw has synced; a stale read could reveal new content.
            mainPhysicalCoverRegistration?.let { registration ->
                mainHandler.post {
                    if (mainPhysicalCoverRegistration === registration) {
                        registration.observeLatchedFrame(texture)
                    }
                }
            }
            if (
                (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 ||
                !carPlayUltraEnabled || !physicalDisplayPanelsProbeEnabled || menuOpen ||
                destroyed.get() || currentSurfaceTexture !== texture
            ) return

            val session = activeAirPlaySession
            if (
                mainTextureDiagnosticGeneration != restartGeneration ||
                mainTextureDiagnosticSession !== session ||
                mainTextureDiagnosticTexture !== texture
            ) {
                mainTextureDiagnosticGeneration = restartGeneration
                mainTextureDiagnosticSession = session
                mainTextureDiagnosticTexture = texture
                mainTextureDiagnosticUpdateCount = 0L
                mainTextureDiagnosticLastSampleMs = Long.MIN_VALUE
            }
            mainTextureDiagnosticUpdateCount++
            val now = android.os.SystemClock.elapsedRealtime()
            if (
                mainTextureDiagnosticLastSampleMs != Long.MIN_VALUE &&
                now - mainTextureDiagnosticLastSampleMs < 5_000L
            ) return
            mainTextureDiagnosticLastSampleMs = now
            val view = videoView ?: return
            val visibility = "shown=${view.isShown} view=${view.visibility} " +
                "alpha=${view.alpha} size=${view.width}x${view.height} " +
                "window=${view.windowVisibility} frame=${mainFrame?.visibility}/${mainFrame?.alpha} " +
                "editor=${safeAreaEditor?.visibility}"
            var bitmap: Bitmap? = null
            try {
                // One bounded readback per five seconds; no image or sampled pixels are retained.
                bitmap = view.getBitmap(64, 64)
                if (bitmap == null) {
                    Log.i(TAG, "MAIN DDP texture sample unavailable updates=$mainTextureDiagnosticUpdateCount $visibility")
                    return
                }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val tenths = mainTextureNonBlackPercentTenths(pixels)
                Log.i(
                    TAG,
                    "MAIN DDP texture sample updates=$mainTextureDiagnosticUpdateCount " +
                        "nonBlack=${tenths / 10}.${tenths % 10}% $visibility",
                )
            } catch (failure: Exception) {
                // Diagnostic readback must never interrupt the render or session callbacks.
                Log.w(TAG, "MAIN DDP texture sample failed ${failure.javaClass.simpleName} $visibility")
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun bindMainPhysicalCover(
        surface: Surface,
        texture: SurfaceTexture,
        version: HostDisplaySurfaceVersion,
    ): HostPhysicalDisplaySurfaceRegistry.MainPhysicalUiCoverRegistration? {
        val owner = mainPhysicalCoverOwner ?: return null
        val gate = owner.newGate() ?: return null
        // A weak View and a Looper-only Handler: the process registry never retains the Activity.
        val weakVideo = java.lang.ref.WeakReference(videoView)
        val redrawHandler = Handler(Looper.getMainLooper())
        return physicalSurfaceRegistry.bindMainPhysicalUiCover(
            surface, texture, version, owner.activityGeneration, gate,
            requestRedraw = { redrawHandler.post { weakVideo.get()?.invalidate() } },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeSessionLog()
        HostPhysicalDisplaySurfaces.requestRetirement()
        I18n.init(this)
        darkMode = isDarkMode(resources.configuration.uiMode)
        prepareEdgeToEdgeWindow()
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        when {
                            safeAreaEditorActive -> closeSafeAreaEditor()
                            settingsCategory != null && !settingsTwoPane -> settingsCategory = null
                            else -> cancelSettingsEdits()
                        }
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (!reusedBackgroundSession &&
            HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR
        ) {
            requestHostTeardownDrain(
                generation = restartGeneration,
                onClear = { maybeStartCarPlay() },
            )
        }
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        mainWebDisplay = AirPlayPersistence.loadWebDisplay(this, SCREEN_TYPE_MAIN)
        clusterWebDisplay = AirPlayPersistence.loadWebDisplay(this, SCREEN_TYPE_ALT)
        AirPlayPersistence.loadCarPlayPresentationSettings(this).let { settings ->
            clusterDisplayEnabled = settings.clusterDisplayEnabled
            // The opt-in currently enables only capabilities whose complete local consumer is
            // present (sessionManagement on wireless). UI Sync and VSP remain independently
            // gated by absent authorized provisioning, so restoring this flag cannot advertise
            // either incomplete feature.
            carPlayUltraEnabled = settings.carPlayUltraEnabled
            physicalDisplayPanelsProbeEnabled = settings.physicalDisplayPanelsProbeEnabled
        }
        val visibleClusterOutput = requireClusterPreviewIfOnlyOutput(
            clusterDisplayEnabled,
            clusterWebDisplay,
        )
        if (visibleClusterOutput != clusterWebDisplay) {
            clusterWebDisplay = visibleClusterOutput
            AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_ALT, visibleClusterOutput)
        }
        catalogProbeSettings = AirPlayPersistence.loadCarPlayAssetCatalogProbeSettings(this).let {
            if (carPlayUltraEnabled) it else it.copy(enabled = false)
        }
        catalogProbeConfigError = false
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this)
        mainMediaAudioBufferDurationMs =
            AirPlayPersistence.loadMainMediaAudioBufferDurationMs(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        videoFrameLogsEnabled = AirPlayPersistence.loadVideoFrameLogsEnabled(this)
        sessionLog.videoFramesEnabled = videoFrameLogsEnabled
        mediaMetricsEnabled = AirPlayPersistence.loadMediaMetricsEnabled(this)
        audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(this)
        moreGesturesToSettings = AirPlayPersistence.loadMoreGesturesToSettings(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        vehicleDataConfig = AirPlayPersistence.loadVehicleDataConfig(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        manualHotspotChannelText = manualHotspotChannel.toString()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED) && !vehicleSpeedPermissionAttempted &&
            canRequestVehicleSpeedPermission() && checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) {
            requestVehicleSpeedPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onResume() {
        super.onResume()
        HostPhysicalDisplaySurfaces.requestRetirement()
        CarPlayBackgroundSession.audioFocus(this).apply {
            retain(this@CarPlayHostActivity)
            setEnabled(audioFocusEnabled)
            requestFromHost()
        }
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        applyFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onStop() {
        stopMicrophoneGainTest()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only reached for language changes if `locale` is ever added to configChanges;
        // otherwise the system recreates the activity and onCreate reloads the strings.
        I18n.refresh(this)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
            styleClusterPreviewControls()
        }
        applyFullscreenMode()
        val generation = restartGeneration
        videoView?.post {
            if (destroyed.get() || generation != restartGeneration) return@post
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshDisplaySizeAfterLayout()
    }

    override fun onDestroy() {
        // Invalidate callbacks already posted by this Activity before detaching identity-bound
        // leases. A replacement Activity may already have attached newer leases to the same
        // background controller/sink; closing these old tokens cannot clear the newer bindings.
        destroyed.set(true)
        restartGeneration += 1
        pendingMainOrdinaryRecovery = null
        mainOrdinaryRecovery = null
        mainPhysicalCoverRegistration?.close()
        mainPhysicalCoverRegistration = null
        mainPhysicalCoverOwner?.close()
        mainPhysicalCoverOwner = null
        cancelPendingHostExternalBootstrap()
        if (HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR) {
            ProcessHostConstructionRetirer.request()
        }
        detachActivityCallbacks()
        CarPlayBackgroundSession.audioFocus(this).release(this)
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        clusterPreviewSurface?.let {
            releaseDisplaySurfaceAfterRetirement(
                SCREEN_TYPE_ALT,
                it,
                clusterPreviewSurfaceTexture,
            )
        }
        clusterPreviewSurface = null
        clusterPreviewSurfaceTexture = null
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        mainHandler.removeCallbacks(drainScreenLogs)
        mainHandler.removeCallbacks(publishTimeline)
        mainHandler.removeCallbacks(retryHostTeardownBarrier)
        physicalRetirementPoller.close()
        hostTeardownContinuation = null
        currentSurface?.let { surface ->
            releaseDisplaySurfaceAfterRetirement(
                SCREEN_TYPE_MAIN,
                surface,
                currentSurfaceTexture,
            )
        }
        currentSurface = null
        currentSurfaceTexture = null
        airPlayCommandExecutor.shutdownNow()
        appLinksIconDecodeGate.close()
        appLinksIconDecodeExecutor.shutdownNow()
        // Already-submitted teardown remains allowed to finish, but this destroyed Activity can
        // never enqueue another controller transition.
        if (!shuttingDown.get()) teardownExecutor.shutdown()
        controller = null
        sink = null
        activeAirPlaySession = null
        clusterPresentationCoordinator = null
        sessionLog.append("Activity destroyed; background session and log remain active")
        super.onDestroy()
    }

    /**
     * View layers keep the video plumbing (TextureViews, touch forwarding, overlays drawn over
     * video); everything else is one Compose layer on top. The window is edge-to-edge: the video
     * is padded by the system bars and display cutout, Compose content by `safeDrawing`.
     */
    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(NO_VIDEO_BACKGROUND)
        }
        contentRoot = root
        val video = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = textureListener
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val mainCover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val weakMainCover = WeakReference(mainCover)
        val coverHandler = mainHandler
        mainPhysicalCoverOwner = MainPhysicalUiCoverOwner(
            postToMain = { action -> coverHandler.post(action) },
            isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            applyVisibility = { covered ->
                weakMainCover.get()?.let { overlay ->
                    overlay.visibility = if (covered) View.VISIBLE else View.GONE
                    true
                } ?: false
            },
            onMissedUltraFrame = { expected, observed ->
                Log.w(TAG, "MAIN Ultra frame permit missed expected=$expected observed=$observed")
            },
        )
        val frame = FrameLayout(this).apply { addView(video) }
        frame.addView(
            mainCover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMediaMetricsOverlayLayout(right - left, bottom - top)
            }
        }
        mainFrame = frame
        val cluster = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(clusterWebDisplay.width, clusterWebDisplay.height)
                    clusterPreviewSurface?.let { oldSurface ->
                        releaseDisplaySurfaceAfterRetirement(
                            SCREEN_TYPE_ALT,
                            oldSurface,
                            clusterPreviewSurfaceTexture,
                        )
                    }
                    clusterPreviewSurfaceTexture = texture
                    clusterPreviewSurface = Surface(texture).also { surface ->
                        physicalSurfaceRegistry.publish(
                            DisplayDestination.CLUSTER,
                            HostDisplaySurfaceKind.PREVIEW_ONLY,
                            surface,
                            texture,
                            width,
                            height,
                        )
                        attachDisplaySurface(SCREEN_TYPE_ALT, surface)
                    }
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    clusterPreviewSurface?.let { surface ->
                        physicalSurfaceRegistry.resize(
                            DisplayDestination.CLUSTER,
                            surface,
                            texture,
                            width,
                            height,
                        )
                    }
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    if (clusterPreviewSurfaceTexture !== texture) return noteTextureDestroyed(texture)
                    val frameworkMayReleaseTexture = clusterPreviewSurface?.let {
                        releaseDisplaySurfaceAfterRetirement(SCREEN_TYPE_ALT, it, texture, textureDestroyed = true)
                    } ?: noteTextureDestroyed(texture)
                    clusterPreviewSurface = null
                    clusterPreviewSurfaceTexture = null
                    return frameworkMayReleaseTexture
                }
            }
        }
        clusterPreview = cluster
        val displays = FrameLayout(this).apply {
            addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        buildClusterPreviewWindow(displays, cluster)
        padForSystemBars(displays)
        root.addView(displays, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Areas without a composable underneath pass touches through to the layers below.
        val compose = ComposeView(this).apply {
            setContent {
                XcertplayTheme {
                    HostOverlay()
                }
            }
        }
        root.addView(
            compose,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateWebDisplayPreviews()
            }
        }
        videoView = video
        gestureOverlay = gestureLayer
        safeAreaEditor = editor
        handshakeTimeline = HandshakeTimeline(wirelessEnabled, System.currentTimeMillis())
        timelineRevision++
        updateWebDisplayPreviews()
        updateDebugOverlays()
        return root
    }

    /**
     * The main display always fills the screen; the instrument preview floats over it as a
     * draggable picture-in-picture window that can be tucked into a handle on the left edge.
     */
    private fun buildClusterPreviewWindow(container: FrameLayout, cluster: TextureView) {
        val minimize = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_ms_arrow_back)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = I18n.text("settings.web.instrument")
            setOnClickListener { setClusterPreviewCollapsed(true) }
        }
        val window = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            elevation = dp(6).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            setBackgroundColor(Color.BLACK)
            addView(cluster, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(minimize, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(8), dp(8), 0, 0)
            })
            setOnTouchListener(clusterWindowDragListener())
        }
        val handle = android.widget.ImageView(this).apply {
            visibility = View.GONE
            setImageResource(R.drawable.ic_ms_chevron_right)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            elevation = dp(3).toFloat()
            contentDescription = I18n.text("settings.web.instrument")
            setOnClickListener { setClusterPreviewCollapsed(false) }
        }
        container.addView(window, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        container.addView(handle, FrameLayout.LayoutParams(dp(24), dp(64), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(16)
        })
        clusterPreviewWindow = window
        clusterPreviewMinimize = minimize
        clusterPreviewHandle = handle
        clusterPreviewContainer = container
        clusterWindowX = dp(16).toFloat()
        clusterWindowY = dp(16).toFloat()
        styleClusterPreviewControls()
        var area = 0 to 0
        container.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val next = (right - left - view.paddingLeft - view.paddingRight) to (bottom - top - view.paddingTop - view.paddingBottom)
            if (next != area) {
                area = next
                view.post { if (!destroyed.get()) updateWebDisplayPreviews() }
            }
        }
    }

    /** Lavender roles for the preview controls; reapplied when the system switches light/dark. */
    private fun styleClusterPreviewControls() {
        val scheme = if (darkMode) LavenderDark else LavenderLight
        val container = scheme.surfaceContainerHigh.toArgb()
        val content = android.content.res.ColorStateList.valueOf(scheme.onSurface.toArgb())
        clusterPreviewMinimize?.apply {
            imageTintList = content
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(container)
            }
        }
        clusterPreviewHandle?.apply {
            imageTintList = content
            background = android.graphics.drawable.GradientDrawable().apply {
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setColor(container)
                setStroke(dp(1), scheme.outlineVariant.toArgb())
            }
        }
    }

    /**
     * One finger drags the preview window past the touch slop; two fingers pinch it around its
     * centre. A gesture that pinched does not drag afterwards, so lifting one finger cannot jump.
     */
    private fun clusterWindowDragListener() = object : View.OnTouchListener {
        private val slop = android.view.ViewConfiguration.get(this@CarPlayHostActivity).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var pinched = false
        private val pinch = android.view.ScaleGestureDetector(
            this@CarPlayHostActivity,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val params = clusterPreviewWindow?.layoutParams ?: return false
                    val centerX = clusterWindowX + params.width / 2f
                    val centerY = clusterWindowY + params.height / 2f
                    clusterWindowScale *= detector.scaleFactor
                    layoutClusterWindow()
                    moveClusterWindow(centerX - params.width / 2f, centerY - params.height / 2f)
                    return true
                }
            },
        )

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = clusterWindowX; startY = clusterWindowY
                    dragging = false
                    pinched = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> { pinched = true; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (pinched) return true
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) moveClusterWindow(startX + dx, startY + dy)
                }
            }
            return true
        }
    }

    /**
     * Sizes the preview window: a default fit inside the safe area times the pinch
     * [clusterWindowScale], from [CLUSTER_WINDOW_MIN_WIDTH_DP] wide up to filling the safe area.
     */
    private fun layoutClusterWindow() {
        val window = clusterPreviewWindow ?: return
        val container = clusterPreviewContainer ?: return
        val availableWidth = container.width - container.paddingLeft - container.paddingRight
        val availableHeight = container.height - container.paddingTop - container.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return
        val fit = minOf(availableWidth.toDouble() / clusterWebDisplay.width, availableHeight.toDouble() / clusterWebDisplay.height)
        val default = minOf(
            availableWidth * CLUSTER_WINDOW_MAX_WIDTH_FRACTION / clusterWebDisplay.width,
            availableHeight * CLUSTER_WINDOW_MAX_HEIGHT_FRACTION / clusterWebDisplay.height,
        )
        val min = minOf(dp(CLUSTER_WINDOW_MIN_WIDTH_DP).toDouble() / clusterWebDisplay.width, default)
        clusterWindowScale = clusterWindowScale.coerceIn((min / default).toFloat(), (fit / default).toFloat())
        val scale = default * clusterWindowScale
        val params = window.layoutParams
        val width = (clusterWebDisplay.width * scale).toInt().coerceAtLeast(1)
        val height = (clusterWebDisplay.height * scale).toInt().coerceAtLeast(1)
        if (params.width != width || params.height != height) { params.width = width; params.height = height; window.layoutParams = params }
        moveClusterWindow(clusterWindowX, clusterWindowY)
    }

    /** Places the preview window at ([x], [y]) inside the safe area, clamped to stay fully visible. */
    private fun moveClusterWindow(x: Float, y: Float) {
        val window = clusterPreviewWindow ?: return
        val container = clusterPreviewContainer ?: return
        val maxX = container.width - container.paddingLeft - container.paddingRight - window.layoutParams.width
        val maxY = container.height - container.paddingTop - container.paddingBottom - window.layoutParams.height
        clusterWindowX = x.coerceIn(0f, maxOf(0, maxX).toFloat())
        clusterWindowY = y.coerceIn(0f, maxOf(0, maxY).toFloat())
        window.translationX = clusterWindowX
        window.translationY = clusterWindowY
    }

    /** Keeps [view]'s content (the CarPlay video) clear of visible system bars and the cutout. */
    private fun padForSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Transparent system bars over our own surfaces; layout runs into the cutout area. */
    private fun prepareEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_MAIN, mainWebDisplay)
        AirPlayPersistence.saveWebDisplay(this, SCREEN_TYPE_ALT, clusterWebDisplay)
        AirPlayPersistence.saveCarPlayPresentationSettings(
            this,
            CarPlayPresentationSettings(
                clusterDisplayEnabled = clusterDisplayEnabled,
                carPlayUltraEnabled = carPlayUltraEnabled,
                physicalDisplayPanelsProbeEnabled = physicalDisplayPanelsProbeEnabled,
            ),
            catalogProbeSettings,
        )
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveVehicleDataConfig(this, vehicleDataConfig)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveAudioFocusEnabled(this, audioFocusEnabled)
        AirPlayPersistence.saveMainMediaAudioBufferDurationMs(
            this,
            mainMediaAudioBufferDurationMs,
        )
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveVideoFrameLogsEnabled(this, videoFrameLogsEnabled)
        AirPlayPersistence.saveMediaMetricsEnabled(this, mediaMetricsEnabled)
        AirPlayPersistence.saveAudioPacketCaptureEnabled(this, audioPacketCaptureEnabled)
        AirPlayPersistence.saveMoreGesturesToSettings(this, moreGesturesToSettings)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
            physicalPanelsProbeEnabled = physicalDisplayPanelsProbeEnabled,
            catalogProbeEnabled = catalogProbeSettings.enabled,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        resetWebDisplayDrafts()
        updateWebDisplayPreviews()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        syncMfiSettingsControls()
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun webDisplayConfig(type: Int) = if (type == SCREEN_TYPE_MAIN) mainWebDisplay else clusterWebDisplay

    private fun setWebDisplayConfig(type: Int, config: CarPlayWebDisplayConfig) {
        if (type == SCREEN_TYPE_MAIN) {
            mainWebDisplay = config
        } else {
            // A browser is an output destination for the type-111 stream, not the capability
            // switch itself. It cannot own a stream that the accessory did not declare.
            if (config.enabled) clusterDisplayEnabled = true
            clusterWebDisplay = requireClusterPreviewIfOnlyOutput(clusterDisplayEnabled, config)
            clusterPreview?.surfaceTexture?.setDefaultBufferSize(
                clusterWebDisplay.width,
                clusterWebDisplay.height,
            )
        }
        updateWebDisplayPreviews()
        updateResolutionMenu()
    }

    private fun updateClusterDisplayEnabled(enabled: Boolean) {
        clusterDisplayEnabled = enabled
        if (!enabled) {
            // The old cluster browser consumed type-111 and cannot outlive its declaration.
            // Ultra, MAIN DDP, and the catalog probe are independent opt-ins.
            clusterWebDisplay = clusterWebDisplay.copy(enabled = false)
        } else {
            clusterWebDisplay = requireClusterPreviewIfOnlyOutput(enabled, clusterWebDisplay)
        }
        updateWebDisplayPreviews()
        updateResolutionMenu()
    }

    private fun updateWebDisplayAddresses() {
        val key = AirPlayPersistence.webDisplayKey(this)
        webDisplayAddresses = listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT).associateWith { type ->
            CarPlayWebDisplayFactory.urls(type, key).joinToString("\n")
        }
    }

    private fun validateWebDisplaySettings(): Boolean {
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            val config = webDisplayConfig(type)
            if (!config.enabled && !(type == SCREEN_TYPE_ALT && clusterDisplayEnabled)) continue
            val draft = webDisplayDrafts[type] ?: continue
            val updated = runCatching {
                config.copy(width = draft.width.trim().toInt(), height = draft.height.trim().toInt())
            }.getOrElse {
                webDisplayDrafts[type] = draft.copy(error = true)
                settingsCategory = SettingsCategory.WEB
                return false
            }
            setWebDisplayConfig(type, updated)
        }
        return true
    }

    private fun updateMfiTargetFields() {
        mfiError = null
    }

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return "Not selected"
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return "Selection unavailable"
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: "Selected document"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected document"
        }
    }

    private fun retainDocumentReadPermission(uri: Uri, label: String): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (failure: SecurityException) {
        Log.e(TAG, "Could not retain local MFi $label document permission", failure)
        Toast.makeText(
            this,
            "Could not keep access to the selected $label",
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    private fun syncMfiSettingsControls() {
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "Remote"
        MfiTarget.LOCAL_FILES -> "Local files"
    }

    private fun canRequestVehicleSpeedPermission(): Boolean = runCatching {
        val info = packageManager.getPermissionInfo("android.car.permission.CAR_SPEED", 0)
        val isCar = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) ||
            runCatching { Class.forName("android.car.Car") }.isSuccess
        isCar && (info.protection) ==
            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    private fun requestVehicleSpeedPermission() {
        if (vehicleSpeedPermissionPending || !vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED) ||
            !canRequestVehicleSpeedPermission() || checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) return
        vehicleSpeedPermissionAttempted = true
        vehicleSpeedPermissionPending = true
        vehicleSpeedPermission.launch("android.car.permission.CAR_SPEED")
    }

    private fun updateVehicleDataStatus() {
        if (menuOpen && lastVehicleDataDiagnostics != null) {
            val speedPermission = if (!canRequestVehicleSpeedPermission()) "not applicable on this device"
                else if (checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) "granted"
                else "not granted"
            vehicleDataStatusText = "Settings pauses CarPlay. The data below was captured before it stopped; " +
                "Save & Reconnect acquires new readings.\n" + lastVehicleDataDiagnostics +
                "\nCurrent vehicle speed permission: $speedPermission" +
                "\nNext connection source: ${vehicleDataConfig.platform.name.replace('_', ' ')}" +
                "\nEnabled for next connection: ${vehicleDataConfig.enabled.filter { it.sourceImplemented }.joinToString { it.label }}"
            return
        }
        val provider = currentVehicleProvider
        val sample = provider?.snapshot()
        val telemetry = provider?.telemetry()
        val errors = telemetry?.unavailableProperties?.values?.distinct()?.joinToString(", ")
        val reason = telemetry?.sourceUnavailableReason ?: errors?.takeIf { it.isNotBlank() } ?: "no fresh reading / unsupported property"
        fun disabled(feature: VehicleDataFeature) = !vehicleDataConfig.isEnabled(feature)
        fun missingState(ids: Set<Int>, continuous: Boolean = false, maxAge: Long = 30_000): String {
            val now = android.os.SystemClock.elapsedRealtime()
            val stale = telemetry?.readings?.values?.filter { it.propertyId in ids }?.any {
                now - it.elapsedRealtimeMillis !in 0..maxAge ||
                    (continuous && it.sourceEventMillis?.let { event -> now - event !in 0..maxAge } == true)
            } == true
            return if (stale) "stale" else "unavailable"
        }
        val subscription = if (provider?.subscriptionActive == true) "phone subscribed" else "waiting for phone subscription"
        vehicleDataStatusText = buildString {
            append("Last connection diagnostics\n")
            append("GPS · Android location: ")
            append(when {
                !locationReportingEnabled -> "disabled"
                !hasFineLocationPermission() -> "precise location permission required"
                currentGpsProvider == null -> "unavailable — connect to acquire a source"
                else -> currentGpsProvider!!.state().name.lowercase().replace('_', ' ')
            })
            append("\nCar source: ").append(provider?.platform?.name?.replace('_', ' ') ?: "not connected")
            append("\nWheel speed & gear: ")
            val speed = provider?.latestSpeed()
            append(when {
                disabled(VehicleDataFeature.WHEEL_SPEED) -> "disabled"
                canRequestVehicleSpeedPermission() && checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED -> "vehicle speed permission required"
                speed == null -> "${missingState(setOf(com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot.STANDARD_SPEED), true, 2_000)} — $reason"
                else -> "${String.format(java.util.Locale.US, "%.1f", speed.metersPerSecond * 3.6)} km/h · ${speed.gear.letter} · ${if (currentWheelProvider?.isPhoneSubscribed == true) "phone subscribed" else "waiting for phone subscription"}"
            })
            append("\nEV battery & range: ")
            append(when {
                disabled(VehicleDataFeature.EV_STATUS) -> "disabled"
                sample?.batteryPercent == null && sample?.electricRangeKm == null -> "unavailable — no verified SOC/range property for this source"
                else -> "SOC ${sample?.batteryPercent?.let { "$it%" } ?: "unknown"} · range ${sample?.electricRangeKm?.let { "$it km" } ?: "unknown"} · $subscription"
            })
            append("\nOutside temperature: ")
            append(when {
                disabled(VehicleDataFeature.OUTSIDE_TEMPERATURE) -> "disabled"
                sample?.outsideTemperatureCelsius == null -> "${missingState(setOf(com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot.STANDARD_OUTSIDE_TEMPERATURE), true)} — $reason"
                else -> "${sample.outsideTemperatureCelsius} °C · $subscription"
            })
        }
    }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun mainMediaAudioBufferLabel(durationMs: Int): String =
        String.format(
            Locale.US,
            "%d ms",
            MainMediaAudioBuffer.sanitizeDurationMs(durationMs),
        )

    private fun microphoneGainLabel(percent: Int): String {
        val gain = MicrophoneGain.sanitize(percent)
        // Sub-unity steps are only ~0.06x apart, so one decimal would show duplicates.
        return String.format(Locale.US, if (gain < 100) "%.2fx" else "%.1fx", gain / 100.0)
    }

    private fun syncMainMediaAudioBufferControls() {
        mainMediaAudioBufferDurationMs = MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)
    }

    private fun syncMicrophoneGainControls() {
        microphoneGainPercent = MicrophoneGain.sanitize(microphoneGainPercent)
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || handshakeResetInProgress || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            micLevelText = tr("settings.audio.permission_required")
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    micPeakPercent = peak
                    micLevelText = "$peak%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    micTestRunning = false
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        micPeakPercent = 0
                        micLevelText = error.message ?: tr("settings.audio.test_failed")
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        micTestRunning = true
        micPeakPercent = 0
        micLevelText = tr("settings.audio.listening")
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            micTestRunning = false
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        micTestRunning = false
        micPeakPercent = 0
        micLevelText = "0%"
    }

    private fun openSystemBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            appendLog("System Bluetooth settings are unavailable: ${error.message}")
            Toast.makeText(this, "System Bluetooth settings are unavailable", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open system Bluetooth settings: ${error.message}")
            Toast.makeText(this, "Cannot open system Bluetooth settings", Toast.LENGTH_LONG).show()
        }
    }

    /** Black backdrop and drag surface; its title and buttons are drawn by [SafeAreaEditorChrome]. */
    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        padForSystemBars(overlay)
        safeAreaEditorView = editor
        return overlay
    }

    private fun updateManualHotspotFields() {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) manualHotspotError = null
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                "I2C device path is required"
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                "Remote server address is required"
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                "Remote server address must start with http:// or https://"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiCertificateUri.isBlank() ->
                "Select a local certificate"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiPrivateKeyUri.isBlank() ->
                "Select a local private key"
            '\u0000' in mfiI2cPath -> "I2C device path contains U+0000"
            '\u0000' in remoteMfiServer -> "Remote server address contains U+0000"
            '\u0000' in remoteMfiToken -> "Remote token contains U+0000"
            '\u0000' in localMfiCertificateUri -> "Local certificate URI contains U+0000"
            '\u0000' in localMfiPrivateKeyUri -> "Local private key URI contains U+0000"
            else -> null
        }
        mfiError = error
        if (error != null) settingsCategory = SettingsCategory.CONNECTION
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> "Hotspot SSID is required"
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                "Hotspot SSID must be at most 32 UTF-8 bytes"
            '\u0000' in manualHotspotSsid -> "Hotspot SSID contains U+0000"
            manualHotspotChannel !in 0..196 -> "Channel must be 0 or 1-196"
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                "Channel is not valid for the selected band"
            '\u0000' in manualHotspotPassphrase -> "Hotspot password contains U+0000"
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                "Password must be empty when security is Open"
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                "WPA2/WPA3 password must be 8-63 characters"
            else -> null
        }
        manualHotspotError = error
        if (error != null) settingsCategory = SettingsCategory.CONNECTION
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi P2P (5 GHz)"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "LocalOnlyHotspot"
        WirelessHotspotMode.MANUAL -> "Manual hotspot"
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "Starting")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "Ready",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "Waiting for paired iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "Connecting Bluetooth")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "Running")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "Starting AirPlay service")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "Error")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    /** The hotspot card reads [hotspotStatus] directly; kept as the single update hook. */
    private fun updateHotspotStatusBlock() = Unit

    private fun updateResolutionMenu() {
        val native = activeDisplaySize ?: currentActivitySize()
        val handshake = if (native == null) {
            tr("summary.waiting_display")
        } else {
            val negotiated = controller?.mainDisplay ?: mainWebDisplay.override(
                CarPlayDisplayScale.apply(
                    AirPlayDisplayConfig(
                        widthPixels = native.width,
                        heightPixels = native.height,
                        widthPhysicalMm = widthPhysicalMm,
                        fps = fps,
                    ),
                    displayScaleTenths,
                ),
            )
            "${native.width} × ${native.height} → ${negotiated.widthPixels} × ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            tr(if (hevcSoftwareDecoderEnabled) "summary.video.hevc_software" else "summary.video.hevc_hardware")
        }
        configSummary = buildList {
            add(tr("summary.handshake") to handshake)
            add(tr("summary.scale") to CarPlayDisplayScale.label(displayScaleTenths))
            add(tr("summary.identity") to "${normalizedManufacturer()} / ${normalizedModel()}")
            add(tr("summary.oem_label") to oemLabel.ifBlank { tr("summary.empty") })
            add(tr("summary.frame_rate") to tr("unit.fps", "value" to fps))
            add(tr("summary.detected_max") to "$maximumDetectedWidthPixels × $maximumDetectedHeightPixels px")
            add(
                tr("summary.physical_reference") to tr(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.SHORTEST_EDGE ->
                            "summary.physical_reference.short_edge"
                        AirPlayPhysicalSizeBasis.LONGEST_EDGE -> "summary.physical_reference.long_edge"
                        AirPlayPhysicalSizeBasis.WIDTH -> "summary.physical_reference.width"
                        AirPlayPhysicalSizeBasis.HEIGHT -> "summary.physical_reference.height"
                    },
                    "value" to widthPhysicalMm,
                ),
            )
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                add(tr("summary.physical_size") to "${physical.widthMm} × ${physical.heightMm} mm")
            }
            add(
                tr("summary.driving_side") to
                    if (rightHandDrive) tr("settings.identity.driving_side.right") else tr("settings.identity.driving_side.left"),
            )
            add(
                tr("summary.system_bars") to tr(
                    "summary.system_bars.value",
                    "top" to tr(if (hideTopBar) "summary.hidden" else "summary.shown"),
                    "bottom" to tr(if (hideBottomBar) "summary.hidden" else "summary.shown"),
                ),
            )
            add(tr("summary.video") to transport)
            add(tr("summary.location") to tr(if (locationReportingEnabled) "summary.enabled" else "summary.disabled"))
            if (advancedAudioChannelMappingSupported) {
                add(
                    tr("summary.audio_mapping") to
                        tr(if (advancedAudioChannelMapping) "summary.audio_mapping.aaos" else "summary.audio_mapping.mobile"),
                )
            }
            add(tr("summary.safe_area") to safeAreaSummary())
        }
    }

    private fun createViewAreas(size: DisplaySize): CarPlayViewAreas? {
        if (mainWebDisplay.enabled) return null
        return CarPlayViewAreas.build(
            currentWidth = size.width,
            currentHeight = size.height,
            savedSizes = AirPlayPersistence.loadSafeAreaSizes(this),
            scaleTenths = displayScaleTenths,
        ) { width, height -> AirPlayPersistence.loadSafeAreaRect(this, width, height) }
    }

    private fun createAirPlayBaseConfig(
        size: DisplaySize,
        areas: CarPlayViewAreas?,
        ultraRuntime: AirPlayUltraRuntime?,
        configuredUltraFeatures: Set<AirPlayFeature> = emptySet(),
        sessionManagementInfo: AirPlaySessionManagementInfo? = null,
    ): AirPlayConfig {
        val physical = if (mainWebDisplay.enabled) {
            fixedDisplayPhysicalSize(mainWebDisplay, widthPhysicalMm, physicalSizeBasis)
        } else {
            resolvePhysicalSize(size)
        }
        val display = if (areas != null) {
            areas.toAirPlayDisplayConfig(
                physicalSize = physical,
                fps = fps,
                safeAreaDrawOutside = safeAreaDrawOutside,
            )
        } else {
            val baseDisplay = AirPlayDisplayConfig(
                widthPixels = size.width,
                heightPixels = size.height,
                widthPhysicalMm = physical.widthMm,
                heightPhysicalMm = physical.heightMm,
                fps = fps,
            )
            val scaledDisplay = CarPlayDisplayScale.apply(
                baseDisplay,
                displayScaleTenths,
            )
            mainWebDisplay.override(scaledDisplay.copy(
                safeArea = AirPlaySafeArea.toInsets(
                    mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                    activityWidthPixels = size.width,
                    activityHeightPixels = size.height,
                    displayWidthPixels = scaledDisplay.widthPixels,
                    displayHeightPixels = scaledDisplay.heightPixels,
                ),
                safeAreaDrawOutside = safeAreaDrawOutside,
            ))
        }
        val cluster = clusterDisplayConfig(
            enabled = clusterDisplayEnabled,
            geometry = clusterWebDisplay,
            widthPhysicalMm = widthPhysicalMm,
            fps = fps,
            physicalSizeBasis = physicalSizeBasis,
        )
        return AirPlayConfig(
            deviceName = "xcertplay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            cluster = cluster,
            ultra = ultraRuntime?.configuration(
                configuredFeatures = configuredUltraFeatures,
                uiSyncInfo = AirPlayUiSyncInfo().takeIf {
                    AirPlayFeature.UI_SYNC in configuredUltraFeatures
                },
                sessionManagementInfo = sessionManagementInfo,
            ),
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    /** Called only after the exact sink and any independently trusted output are ready. */
    private fun finalizeAirPlayConfig(
        baseline: AirPlayConfig,
        readyExternal: HostPhysicalTargetInspection?,
        externalOwner: HostExternalOutputOwner?,
    ): HostUltraFinalConfig {
        if (!carPlayUltraEnabled || !physicalDisplayPanelsProbeEnabled) {
            // Classic and Ultra-without-DDP preserve exactly the phase-one /info geometry.
            return HostUltraFinalConfig(baseline, null)
        }
        // An unavailable/timed-out external owner must not calibrate from an unrelated or
        // already-retiring registry publication. A Ready result is only a hint until its exact
        // sink route, Surface identity, and connector topology are rechecked here.
        val trustedExternalCluster = readyExternal?.takeIf { candidate ->
            clusterDisplayEnabled && externalOwner?.isCurrent(candidate) == true &&
                physicalSurfaceRegistry.inspectUniquePhysicalExternalClusterTarget()?.let { live ->
                    live.version == candidate.version && live.surface === candidate.surface &&
                        live.externalTopologyGeneration == candidate.externalTopologyGeneration
                } == true
        }
        // Trusted ALT millimetres can refine /info only after a live target has been observed.
        // Pixel dimensions stay frozen at the dimensions used to construct the exact sink.
        val cluster = calibratedExternalClusterDisplay(baseline.cluster, trustedExternalCluster)
        val configured = if (cluster === baseline.cluster) baseline else baseline.copy(cluster = cluster)
        val currentMainSurface = currentSurface
        val mainVisible = mainVideoSurfaceVisibleForDdp(videoView?.isShown == true, mainWebDisplay)
        val mainPanelReadiness = physicalMainDisplayOutputReadiness(
            physicalSurfaceRegistry,
            currentMainSurface,
            mainVisible,
            configured.main,
        )
        val clusterPhysicalDestination = trustedExternalCluster?.version?.destination
            ?: DisplayDestination.CLUSTER
        val clusterPanelReadiness = if (trustedExternalCluster == null) {
            if (configured.cluster == null) PhysicalDdpPanelReadiness.NO_CLUSTER
            else PhysicalDdpPanelReadiness.NO_PHYSICAL_EXTERNAL
        } else physicalClusterDisplayOutputReadiness(
            physicalSurfaceRegistry,
            configured.cluster,
            clusterPhysicalDestination,
        )
        val candidatePanels = physicalDisplayPanelsProbe(
            ultraEnabled = carPlayUltraEnabled,
            clusterDisplayEnabled = clusterDisplayEnabled,
            optedIn = physicalDisplayPanelsProbeEnabled,
            main = configured.main,
            cluster = configured.cluster,
            physicalMainOutputProven = mainPanelReadiness == PhysicalDdpPanelReadiness.READY,
            // The in-window CLUSTER preview is PREVIEW_ONLY. ALT is admitted only when one
            // independently trusted, size-matched external producer is currently published.
            physicalClusterOutputProven = clusterPanelReadiness == PhysicalDdpPanelReadiness.READY,
        )
        // The controller can outlive this Activity. Freeze only the registry and exact Surface
        // generations into the shared per-session check; a later withdraw/replace invalidates it
        // before /info or SETUP can reuse stale DDP metadata.
        var capturedPanelProof: PhysicalDdpPanelSessionProof? = null
        val frozenOwner = externalOwner
        val frozenRoute = if (clusterPanelReadiness == PhysicalDdpPanelReadiness.READY) {
            trustedExternalCluster?.let { frozenOwner?.routeFactory?.leaseForFrozenPhysicalTarget(it) }
        } else null
        val panelLiveness = candidatePanels?.let { candidate ->
            PhysicalDdpPanelSessionProof.capture(
                registry = physicalSurfaceRegistry,
                currentMainSurface = currentMainSurface,
                mainVisible = mainVisible,
                main = configured.main,
                cluster = configured.cluster,
                includePhysicalCluster = clusterPanelReadiness == PhysicalDdpPanelReadiness.READY,
                clusterDestination = clusterPhysicalDestination,
            )?.takeIf { proof ->
                clusterPanelReadiness != PhysicalDdpPanelReadiness.READY ||
                    (frozenRoute != null && trustedExternalCluster?.let { frozen ->
                        proof.externalClusterTarget?.version == frozen.version &&
                            proof.externalClusterTarget?.surface === frozen.surface &&
                            proof.externalClusterTarget?.externalTopologyGeneration ==
                                frozen.externalTopologyGeneration
                    } == true)
            }?.let { proof ->
                capturedPanelProof = proof
                AirPlayPhysicalDisplayPanelsLiveness(
                    candidate,
                    {
                        proof.isCurrent() && (proof.externalClusterTarget == null ||
                            (frozenOwner?.isCurrent(proof.externalClusterTarget) == true &&
                                frozenRoute?.isCurrent == true))
                    },
                    { onInvalidated ->
                        val panelSubscription = proof.subscribeChanges(onInvalidated)
                        val routeSubscription = try {
                            frozenRoute?.subscribeRevocation(onInvalidated)
                        } catch (failure: Throwable) {
                            try {
                                panelSubscription.close()
                            } catch (cleanupFailure: Throwable) {
                                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                            }
                            throw failure
                        }
                        Closeable {
                            var closeFailure: Throwable? = null
                            try {
                                panelSubscription.close()
                            } catch (failure: Throwable) {
                                closeFailure = failure
                            }
                            try {
                                routeSubscription?.close()
                            } catch (failure: Throwable) {
                                if (closeFailure == null) closeFailure = failure
                                else if (closeFailure !== failure) closeFailure.addSuppressed(failure)
                            }
                            closeFailure?.let { throw it }
                        }
                    },
                )
            }
        }
        val final = settleHostPhysicalPanelProbe(
            baseline,
            configured,
            candidatePanels,
            panelLiveness,
            capturedPanelProof?.externalClusterTarget.takeIf {
                clusterPanelReadiness == PhysicalDdpPanelReadiness.READY
            },
        )
        val panels = final.airPlayConfig.displayPanels
        if (carPlayUltraEnabled && physicalDisplayPanelsProbeEnabled) {
            appendLog(physicalDdpProbeDiagnosticMessage(
                mainPanelReadiness,
                clusterPanelReadiness,
                panels != null,
            ))
            if (candidatePanels != null && panelLiveness == null) {
                appendLog("DDP probe exact physical target changed before session freeze; no panels advertised")
            }
        }
        return final
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        airPlayIcon = bitmap?.asImageBitmap()
        airPlayIconCustom = customBitmap != null
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm {
        val maximum = currentMaximumDisplaySize(size)
        return AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maximum.width,
            maximumHeightPixels = maximum.height,
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )
    }

    /**
     * Returns the current display's maximum window bounds in the activity's orientation.
     *
     * Physical-size metadata must not depend on a historical Activity size: a temporary `wm
     * size`, rotation, desktop window, or external display can otherwise poison preferences and
     * make later CarPlay sessions render at the wrong apparent DPI.
     */
    private fun currentMaximumDisplaySize(current: DisplaySize): DisplaySize {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            DisplaySize(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
            DisplaySize(metrics.widthPixels, metrics.heightPixels)
        }
        val (width, height) = alignMaximumDisplaySize(
            currentWidth = current.width,
            currentHeight = current.height,
            reportedMaximumWidth = raw.width,
            reportedMaximumHeight = raw.height,
        )
        return DisplaySize(width, height)
    }

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return tr("safe_area.waiting")
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            tr("safe_area.full", "width" to size.width, "height" to size.height)
        } else {
            tr(
                "safe_area.custom",
                "rect_width" to mapping.width,
                "rect_height" to mapping.height,
                "left" to mapping.left,
                "top" to mapping.top,
                "width" to size.width,
                "height" to size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryText = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            Toast.makeText(this, tr("safe_area.unavailable"), Toast.LENGTH_SHORT).show()
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        applySystemBarAppearance()
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        updateSafeAreaSummary()
        updateResolutionMenu()
        applySystemBarAppearance()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            Toast.makeText(this, tr("safe_area.unavailable"), Toast.LENGTH_SHORT).show()
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        val generation = restartGeneration
        videoView?.post {
            if (destroyed.get() || generation != restartGeneration) return@post
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createWebDisplayOutputs(): CarPlayWebDisplayFactory.Session? =
        CarPlayWebDisplayFactory.create(
            context = applicationContext,
            configs = mapOf(SCREEN_TYPE_MAIN to mainWebDisplay, SCREEN_TYPE_ALT to clusterWebDisplay),
            key = AirPlayPersistence.webDisplayKey(this),
            sendTouch = { CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(it) ?: false },
        )

    private fun createMediaSink(
        airPlayConfig: AirPlayConfig,
        outputs: CarPlayWebDisplayFactory.Session?,
        outputLifecycle: HostSinkOutputLifecycle,
        timedVideoPresentation: Boolean,
        videoFrameReleaseRelay: HostVideoFrameReleaseRelay?,
        onConstructed: (AndroidMediaSink) -> Unit,
    ): AndroidMediaSink {
        val renderer = AndroidMediaSink(
            context = applicationContext,
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            videoSizes = buildMap {
                put(SCREEN_TYPE_MAIN, airPlayConfig.main.widthPixels to airPlayConfig.main.heightPixels)
                airPlayConfig.cluster?.let { put(SCREEN_TYPE_ALT, it.widthPixels to it.heightPixels) }
            },
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            mainMediaAudioBufferDurationMs = mainMediaAudioBufferDurationMs,
            microphoneGainPercent = microphoneGainPercent,
            timedVideoPresentation = timedVideoPresentation,
            mediaMetricsMonitor = mediaMetricsMonitor,
            onClosed = outputLifecycle::close,
            onVideoFrameReleased = videoFrameReleaseRelay?.let { relay -> relay::emit },
            mediaTap = outputs,
            audioFocus = CarPlayBackgroundSession.audioFocus(this),
        )
        // Publish the exact owner before the first fallible post-construction binding operation.
        onConstructed(renderer)
        videoFrameReleaseRelay?.bindOwner(renderer)
        outputs?.bind(renderer)
        return renderer
    }

    private fun createMediaEngine(
        sink: AndroidMediaSink,
        ultraRuntime: AirPlayUltraRuntime?,
    ): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
            videoPacketLog = CarPlayBackgroundSession.log::appendVideoPacket,
            ultraRuntime = ultraRuntime,
        )

    private fun attachActivityCallbacks(
        targetController: CarPlayController,
        targetSink: AndroidMediaSink,
        generation: Int,
    ): Boolean {
        if (destroyed.get()) return false
        detachActivityCallbacks()
        val controllerAttachment = targetController.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        ) ?: return false
        val sinkAttachment = targetSink.attachScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(generation, type, active)
        } ?: run {
            controllerAttachment.close()
            return false
        }
        val linksAttachment = targetController.attachAppLinksUi { snapshot ->
            if (!destroyed.get() && generation == restartGeneration) {
                if (appLinksSnapshot.ownerRevision != snapshot.ownerRevision) {
                    appLinksPreferredIconSize = HostAppLinksPreferredIconSize.NONE
                }
                if (appLinksSnapshot.ownerRevision != snapshot.ownerRevision ||
                    appLinksSnapshot.icon !== snapshot.icon
                ) {
                    decodedAppLinksIcon = null
                    appLinksIconDecodeFailed = false
                }
                appLinksSnapshot = snapshot
                snapshot.icon?.let { icon ->
                    appLinksIconDecodeGate.request(snapshot.ownerRevision, icon)
                } ?: appLinksIconDecodeGate.clear()
            }
        } ?: run {
            sinkAttachment.close()
            controllerAttachment.close()
            return false
        }
        controllerUiAttachment = controllerAttachment
        appLinksUiAttachment = linksAttachment
        sinkUiAttachment = sinkAttachment
        return true
    }

    private fun detachActivityCallbacks() {
        appLinksIconDecodeGate.clear()
        runCatching { controllerUiAttachment?.close() }
        controllerUiAttachment = null
        runCatching { appLinksUiAttachment?.close() }
        appLinksUiAttachment = null
        runCatching { sinkUiAttachment?.close() }
        sinkUiAttachment = null
        appLinksSnapshot = ControllerAppLinksSnapshot()
        decodedAppLinksIcon = null
        appLinksIconDecodeFailed = false
        appLinksPanelOpen = false
        appLinksPreferredIconSize = HostAppLinksPreferredIconSize.NONE
    }

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    reconnectAttempts = 0
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    hostUiRequested = false
                    failHandshake("CarPlay session ended", "iPhone closed the AirPlay session")
                    setConnectionStage("CarPlay session ended; reconnecting")
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    hostUiRequested = false
                    failHandshake("Transport error", message)
                    setConnectionStage("Transport error; reconnecting")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    val active = activeAirPlaySession
                    if (active != null && active !== session) return@runOnUiThread
                    // A live host-UI update can race attach-time lifecycle replay, so accept this
                    // authenticated callback even if onSessionActive has not reached the UI yet.
                    if (active == null) activeAirPlaySession = session
                    // `requestUI` is advisory. Keep streaming until the user explicitly chooses
                    // an action instead of turning this event into a hidden session teardown.
                    hostUiRequested = true
                    appendLog("iPhone requested host controls; CarPlay remains active")
                }
            }

            override fun onDebugLog(message: String) {
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                if (HandshakeTimeline.isRelevant(message)) {
                    mainHandler.post { recordHandshake(controllerGeneration, message, now) }
                }
                if (!debugLogsEnabled) return
                val summary = ScreenLogPolicy.summary(message) ?: return
                synchronized(pendingScreenLogsLock) {
                    if (!debugLogsEnabled) return
                    pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, summary))
                    if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    if (!screenDrainPosted) {
                        screenDrainPosted = true
                        mainHandler.post(drainScreenLogs)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> {
                    failHandshake("CarPlay failed", description)
                    reconnectAfterLoss(description)
                }
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        if (destroyed.get()) return false
        if (physicalSurfaceRegistry.hasPendingRetirement() ||
            HostExternalOutputOwner.hasPendingProcessRetirement()
        ) {
            HostPhysicalDisplaySurfaces.requestRetirement()
            schedulePhysicalRetirementPoll()
            return false
        }
        if (HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR) {
            return false
        }
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        val generation = restartGeneration
        val adoption = if (snapshot.controller.isClosed()) {
            decideBackgroundSessionAdoption(
                controllerClosed = true,
                callbacksAttached = false,
            )
        } else {
            decideBackgroundSessionAdoption(
                controllerClosed = false,
                callbacksAttached = attachActivityCallbacks(
                    snapshot.controller,
                    snapshot.sink,
                    generation,
                ),
            )
        }
        if (adoption != BackgroundSessionAdoptionAction.ADOPT) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            submitHostTeardown(snapshot.controller, snapshot.sink)
            appendLog(
                when (adoption) {
                    BackgroundSessionAdoptionAction.RETIRE_CLOSED_CONTROLLER ->
                        "Retiring closed background CarPlay stack before replacement"
                    BackgroundSessionAdoptionAction.RETIRE_CALLBACK_ATTACH_FAILURE ->
                        "Retiring unadoptable background CarPlay stack before replacement"
                    BackgroundSessionAdoptionAction.ADOPT -> error("unreachable")
                },
            )
            requestHostTeardownDrain(
                generation = generation,
                replaceContinuation = true,
                onClear = { maybeStartCarPlay() },
            )
            return false
        }
        controller = snapshot.controller
        sink = snapshot.sink
        clusterPresentationCoordinator = snapshot.clusterPresentationCoordinator
        viewAreas = snapshot.viewAreas
        handshakeTimeline = snapshot.handshakeTimeline
        activeScreenStreamTypes = snapshot.activeScreenStreamTypes
        timelineRevision++
        snapshot.sink.setMediaMetricsMonitor(mediaMetricsMonitor)
        if (snapshot.width > 0 && snapshot.height > 0) {
            val restored = snapshot.viewAreas?.activitySize(snapshot.controller.viewAreaIndex)
            activeDisplaySize = restored?.let { DisplaySize(it.first, it.second) }
                ?: DisplaySize(snapshot.width, snapshot.height)
            activeDisplayLayout = currentDisplayLayout()
        }
        currentSurface?.let(::attachSurface)
        clusterPreviewSurface?.let { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
        updateWebDisplayPreviews()
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                "CarPlay service already running"
            } else {
                "CarPlay session already running"
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun resumeHostExternalBootstrap(
        pending: PendingHostExternalBootstrap,
        outcome: HostExternalOutputOwner.Outcome,
    ) {
        if (pendingHostExternalBootstrap !== pending) return
        if (destroyed.get() || shuttingDown.get() || menuOpen ||
            pending.generation != restartGeneration ||
            activeDisplaySize != DisplaySize(pending.width, pending.height) ||
            !pending.staged.isCurrent(pending.ticket, restartGeneration)
        ) {
            cancelPendingHostExternalBootstrap()
            if (!destroyed.get() && !shuttingDown.get()) {
                requestHostTeardownDrain(restartGeneration, onClear = { maybeStartCarPlay() })
            }
            return
        }
        if (outcome is HostExternalOutputOwner.Outcome.Ready &&
            !pending.owner.isCurrent(outcome.target)
        ) {
            retireReadyExternalBootstrap(pending)
            return
        }
        pending.outcome = outcome
        startCarPlay(DisplaySize(pending.width, pending.height))
    }

    private fun retireReadyExternalBootstrap(pending: PendingHostExternalBootstrap) {
        pending.outcome = null
        pending.owner.close()
        val activity = WeakReference(this)
        val uiHandler = Handler(Looper.getMainLooper())
        pending.owner.retirementReceipt().whenComplete { _, _ ->
            uiHandler.post {
                activity.get()?.resumeHostExternalBootstrap(
                    pending,
                    HostExternalOutputOwner.Outcome.Unavailable(
                        HostExternalOutputOwner.Reason.WITHDRAWN,
                    ),
                )
            }
        }
    }

    /** A cancelled pre-Controller wait is retired by the process barrier, not the dead Activity. */
    private fun cancelPendingHostExternalBootstrap() {
        val pending = pendingHostExternalBootstrap ?: return
        pendingHostExternalBootstrap = null
        pending.staged.cancel()
        pending.owner.close()
        HostTeardownProcessBarrier.coordinator.submit(
            HostTeardownActions(
                controllerIdentity = null,
                sinkIdentity = pending.cleanup,
                requestControllerClose = null,
                awaitControllerClosed = null,
                closeMediaSink = CloseHostConstructionOperation(pending.cleanup),
            ),
        )
    }

    private fun startCarPlay(size: DisplaySize) {
        if (destroyed.get() || shuttingDown.get() || menuOpen) return
        val resumedBootstrap = pendingHostExternalBootstrap
        if (resumedBootstrap != null && resumedBootstrap.outcome == null) return
        if (resumedBootstrap != null && (
                resumedBootstrap.generation != restartGeneration ||
                    resumedBootstrap.width != size.width || resumedBootstrap.height != size.height
            )) {
            cancelPendingHostExternalBootstrap()
            requestHostTeardownDrain(restartGeneration, onClear = { maybeStartCarPlay() })
            return
        }
        val previouslyReady = resumedBootstrap?.outcome as? HostExternalOutputOwner.Outcome.Ready
        if (resumedBootstrap != null && previouslyReady != null &&
            !resumedBootstrap.owner.isCurrent(previouslyReady.target)
        ) {
            // A physical-pending or teardown-pending poll may have deferred this continuation.
            // Retire the stale Presenter before falling back to non-ALT finalization.
            resumedBootstrap.outcome = null
            resumeHostExternalBootstrap(resumedBootstrap, previouslyReady)
            return
        }
        if (physicalSurfaceRegistry.hasPendingRetirement() ||
            (resumedBootstrap == null && HostExternalOutputOwner.hasPendingProcessRetirement())
        ) {
            HostPhysicalDisplaySurfaces.requestRetirement()
            schedulePhysicalRetirementPoll()
            return
        }
        if (HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR) {
            requestHostTeardownDrain(
                generation = restartGeneration,
                onClear = { maybeStartCarPlay() },
            )
            return
        }
        if (handshakeResetInProgress || controller != null) return
        if (resumedBootstrap == null) hostUiRequested = false
        val controllerGeneration = restartGeneration
        if (resumedBootstrap == null) {
            handshakeTimeline = HandshakeTimeline(
                wirelessEnabled, System.currentTimeMillis(), lastAttemptFailure,
            )
            lastAttemptFailure = null
            timelineRevision++
            updateDebugOverlays()
        }
        val config = resumedBootstrap?.runtimeConfig ?: createRuntimeConfig()
        val areas = if (resumedBootstrap != null) resumedBootstrap.viewAreas else createViewAreas(size)
        val presentationCoordinator = if (resumedBootstrap != null) {
            resumedBootstrap.presentationCoordinator
        } else if (carPlayUltraEnabled && clusterDisplayEnabled) {
            ClusterPresentationCoordinator()
        } else null
        // SessionManagement has a complete built-in stopSession consumer and needs no OEM schema.
        // It is wireless-only. UI Sync/VSP still require one authorized vehicle/theme/CAF profile
        // and remain absent while preserving ordinary type-111, preview, PIP, and WebDisplay.
        val sessionManagementInfo = sessionManagementInfo(
            ultraEnabled = carPlayUltraEnabled,
            transport = config.transport,
        )
        val ultraPlan = resumedBootstrap?.ultraPlan
            ?: hostUltraRuntimePlan(carPlayUltraEnabled, sessionManagementInfo)
        val videoFrameReleaseRelay = resumedBootstrap?.videoFrameReleaseRelay
            ?: if (ultraPlan.createAuthorization) HostVideoFrameReleaseRelay() else null
        val configuredUltraFeatures = if (sessionManagementInfo != null) {
            setOf(AirPlayFeature.SESSION_MANAGEMENT)
        } else {
            emptySet()
        }
        var fallbackOwner: AirPlayUltraRuntime? = resumedBootstrap?.fallbackRuntime
        var lifecycleOwner: AuthorizedUltraProductSession? = null
        var authorizationOwner: ControllerUltraAuthorizationRuntime? = null
        var vehicleProviderOwner:
            com.shilapi.xcertplay.vehicle.CarPropertyVehicleProvider? = null
        var locationProviderOwner: Iap2LocationProvider? = null
        var outputsOwner: CarPlayWebDisplayFactory.Session? = null
        var rendererOwner: AndroidMediaSink? = resumedBootstrap?.sink
        var externalOutputOwner: HostExternalOutputOwner? = resumedBootstrap?.owner
        var mediaOwner: CarPlayMediaEngine? = null
        var updateDataAcquisition: HostUpdateDataQuarantineAcquisition? = null
        var stagedAirPlayConfig: HostUltraTwoPhaseConfig? = resumedBootstrap?.staged
        lateinit var airPlayConfig: AirPlayConfig

        val next = try {
            val fallbackRuntime: AirPlayUltraRuntime?
            val staged: HostUltraTwoPhaseConfig
            val renderer: AndroidMediaSink
            val configTicket: HostUltraTwoPhaseConfig.Ticket
            if (resumedBootstrap != null) {
                check(pendingHostExternalBootstrap === resumedBootstrap) {
                    "The external output wait belongs to another Host construction"
                }
                pendingHostExternalBootstrap = null
                fallbackRuntime = resumedBootstrap.fallbackRuntime
                staged = resumedBootstrap.staged
                renderer = resumedBootstrap.sink
                configTicket = resumedBootstrap.ticket
            } else {
                fallbackOwner = if (ultraPlan.createFallbackRuntime) AirPlayUltraRuntime() else null
                fallbackRuntime = fallbackOwner
                val baseAirPlayConfig = createAirPlayBaseConfig(
                    size,
                    areas,
                    fallbackRuntime,
                    configuredUltraFeatures,
                    sessionManagementInfo,
                )
                check(hasHostUltraFallbackParity(baseAirPlayConfig, fallbackRuntime)) {
                    "Host fallback AirPlay configuration/runtime parity failed"
                }
                // Phase one freezes both stream pixel geometries without any DDP declaration.
                // No Controller, AirPlay server, or /info exists at this point.
                staged = HostUltraTwoPhaseConfig(baseAirPlayConfig, controllerGeneration)
                stagedAirPlayConfig = staged
                outputsOwner = createWebDisplayOutputs()
                val exactOutputs = outputsOwner
                val outputLifecycle = HostSinkOutputLifecycle(exactOutputs, videoFrameReleaseRelay)
                renderer = createMediaSink(
                    airPlayConfig = baseAirPlayConfig,
                    outputs = exactOutputs,
                    outputLifecycle = outputLifecycle,
                    timedVideoPresentation = ultraPlan.timedVideoPresentation,
                    videoFrameReleaseRelay = videoFrameReleaseRelay,
                    onConstructed = { exactRenderer ->
                        rendererOwner = exactRenderer
                        // AndroidMediaSink.onClosed now owns the exact output session.
                        outputsOwner = null
                    },
                )
                presentationCoordinator?.let { exactOutputs?.attachClusterPresentation(it) }
                configTicket = checkNotNull(staged.issueTicket(restartGeneration)) {
                    "Host construction changed before physical display finalization"
                }
                val fixedTrustProvider = UltraProfileCompositionRoot.externalPhysicalDisplayTrustProvider()
                if (shouldAwaitTrustedExternalOutput(
                    carPlayUltraEnabled, clusterDisplayEnabled,
                    physicalDisplayPanelsProbeEnabled, fixedTrustProvider,
                )) {
                    val owner = HostExternalOutputOwner.open(
                        applicationContext, renderer, physicalSurfaceRegistry, fixedTrustProvider,
                    )
                    externalOutputOwner = owner
                    outputLifecycle.attachExternal(owner)
                    val pending = PendingHostExternalBootstrap(
                        controllerGeneration, size.width, size.height, config, areas,
                        presentationCoordinator, ultraPlan, fallbackRuntime, renderer,
                        videoFrameReleaseRelay,
                        staged, configTicket, owner,
                        HostConstructionCleanup(buildList {
                            add(HostConstructionCleanupStage("external output", owner::closeAndRequireRetired))
                            fallbackRuntime?.let { resource ->
                                add(HostConstructionCleanupStage("fallback Ultra runtime", resource::close))
                            }
                            add(HostConstructionCleanupStage("media sink", renderer::close))
                        }),
                    )
                    pendingHostExternalBootstrap = pending
                    val activity = WeakReference(this)
                    val uiHandler = Handler(Looper.getMainLooper())
                    owner.awaitReady(staged, configTicket).whenComplete { outcome, failure ->
                        val resolved = outcome ?: HostExternalOutputOwner.Outcome.Unavailable(
                            HostExternalOutputOwner.Reason.SUBSCRIPTION_FAILED,
                        )
                        if (failure != null) owner.close()
                        val readyToContinue = if (resolved is HostExternalOutputOwner.Outcome.Unavailable) {
                            owner.retirementReceipt()
                        } else null
                        if (readyToContinue == null) {
                            uiHandler.post { activity.get()?.resumeHostExternalBootstrap(pending, resolved) }
                        } else {
                            readyToContinue.whenComplete { _, _ ->
                                uiHandler.post { activity.get()?.resumeHostExternalBootstrap(pending, resolved) }
                            }
                        }
                    }
                    return
                }
            }
            val readyExternal = (resumedBootstrap?.outcome as? HostExternalOutputOwner.Outcome.Ready)
                ?.target?.takeIf { externalOutputOwner?.isCurrent(it) == true }
            if (resumedBootstrap?.outcome is HostExternalOutputOwner.Outcome.Ready &&
                readyExternal == null
            ) {
                // The exact route went stale between the earlier admission and final freeze.
                // Do not start Controller until that Presenter and its type-111 route retire.
                pendingHostExternalBootstrap = resumedBootstrap
                retireReadyExternalBootstrap(resumedBootstrap)
                return
            }
            val finalized = checkNotNull(staged.finalize(
                configTicket,
                restartGeneration,
                { baseline -> finalizeAirPlayConfig(baseline, readyExternal, externalOutputOwner) },
            )) { "Host construction changed during physical display finalization" }
            airPlayConfig = finalized.airPlayConfig
            val frozenExternalClusterTarget = finalized.frozenExternalClusterTarget
            check(hasHostUltraFallbackParity(airPlayConfig, fallbackRuntime)) {
                "Final Host AirPlay configuration/runtime parity failed"
            }

            val persistenceContext = applicationContext
            updateDataAcquisition = acquireHostUpdateDataQuarantine(
                enabled = carPlayUltraEnabled,
                noBackupDirectory = persistenceContext.noBackupFilesDir,
                onUnavailable = { error ->
                    Log.w(TAG, "UpdateData quarantine unavailable; file transfer stays off", error)
                    appendLog("UpdateData quarantine unavailable; continuing ordinary CarPlay")
                },
            )
            val pairings = AirPlayPersistence.loadPairings(persistenceContext) { id, key ->
                AirPlayPersistence.savePairing(persistenceContext, id, key)
            }

            if (vehicleDataConfig.isEnabled(VehicleDataFeature.EV_STATUS) ||
                vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED) ||
                vehicleDataConfig.isEnabled(VehicleDataFeature.OUTSIDE_TEMPERATURE)
            ) {
                val candidate = com.shilapi.xcertplay.vehicle.CarPropertyVehicleProvider(
                    com.shilapi.xcertplay.vehicle.CarPropertyDataStore(
                        com.shilapi.xcertplay.vehicle.AndroidCarPropertyBackend(this),
                        vehicleDataConfig.platform,
                        android.os.SystemClock::elapsedRealtime,
                    ),
                    vehicleDataConfig,
                    android.os.SystemClock::elapsedRealtime,
                )
                vehicleProviderOwner = candidate
                candidate.start()
            }
            val vehicleProvider = vehicleProviderOwner
            lastVehicleDataDiagnostics = null
            currentVehicleProvider = vehicleProvider
            currentGpsProvider = null
            val positionProvider: Iap2LocationProvider? =
                if (config.locationReportingEnabled) {
                    VehicleLocationProvider(
                        AndroidCarPlayLocationProvider(this),
                        vehicleDataConfig,
                        android.os.SystemClock::elapsedRealtime,
                    ).also {
                        locationProviderOwner = it
                        currentGpsProvider = it
                    }
                } else {
                    null
                }
            currentWheelProvider = null
            val locationProvider = if (
                vehicleProvider != null &&
                vehicleDataConfig.isEnabled(VehicleDataFeature.WHEEL_SPEED)
            ) {
                com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider(
                    positionProvider,
                    vehicleProvider,
                ).also {
                    // The wrapper now owns the position provider; the vehicle provider remains an
                    // independent controller owner.
                    locationProviderOwner = it
                    currentWheelProvider = it
                }
            } else {
                positionProvider
            }

            appendLog(
                "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                    "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                    "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                    "viewAreas=${areas ?: "single"} " +
                    "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                    "${airPlayConfig.main.heightPhysicalMm}mm " +
                    "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                    "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                    "microphone=${airPlayConfig.microphone} " +
                    "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                    "ultraUiSync=${airPlayConfig.ultra?.isConfigured(AirPlayFeature.UI_SYNC) == true} " +
                    "physicalPanels=${airPlayConfig.displayPanels != null} " +
                    "sessionManagement=${airPlayConfig.ultra?.isConfigured(AirPlayFeature.SESSION_MANAGEMENT) == true} " +
                    "mfi=${mfiTargetLabel(config.mfiTarget)}",
            )
            Log.i(
                TAG,
                "starting controller display=${size.width}x${size.height} " +
                    "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                    "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                    "hevc=${airPlayConfig.hevc} " +
                    "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                    "microphone=${airPlayConfig.microphone} " +
                    "location=${config.locationReportingEnabled} " +
                    "ultraUiSync=${airPlayConfig.ultra?.isConfigured(AirPlayFeature.UI_SYNC) == true} " +
                    "physicalPanels=${airPlayConfig.displayPanels != null} " +
                    "sessionManagement=${airPlayConfig.ultra?.isConfigured(AirPlayFeature.SESSION_MANAGEMENT) == true} " +
                    "mfi=${config.mfiTarget}",
            )

            val media = createMediaEngine(renderer, fallbackRuntime)
            mediaOwner = media
            // The media engine is now the sole close owner for the fallback runtime.
            fallbackOwner = null

            // Reconfigure on *every* Controller construction, including an Ultra opt-out.
            // Otherwise the Debug root can retain a stale provider and its old Host inputs
            // while the next ordinary CarPlay Controller never consults that provider.
            val debugBenchEnabled = UltraProfileCompositionRoot.configureHost(
                persistenceContext,
                carPlayUltraEnabled,
                HostUltraCompositionInputs(
                    renderer, physicalSurfaceRegistry, airPlayConfig, frozenExternalClusterTarget,
                    externalClusterRoutes = externalOutputOwner?.routeFactory,
                    videoFrameReleaseRelay = videoFrameReleaseRelay,
                ),
            )
            if (ultraPlan.createAuthorization) {
                // Construct the fixed product root only after both process-owned media objects
                // exist, but before a Controller can activate or publish a session. This is the
                // only safe Host seam for a future root to capture this exact sink; an Activity,
                // view, preference, or independently constructed renderer must not supply it.
                if (debugBenchEnabled) {
                    appendLog(
                        "DEBUG user-signed Profile bench enabled: CAF; UI Sync only with its " +
                            "separate opt-in, signed catalog and live physical outputs. " +
                            "NOT Apple/OEM authorized; synthetic values are NOT vehicle data",
                    )
                }
                val lifecycle = AuthorizedUltraProductSession()
                lifecycleOwner = lifecycle
                val runtimeBinding = AirPlaySessionRuntimeBinding()
                val generation = runtimeBinding.beginGeneration()
                authorizationOwner = ControllerUltraAuthorizationRuntime(
                    lifecycle = lifecycle,
                    binding = runtimeBinding,
                    generation = generation,
                    baselineConfig = airPlayConfig,
                    fallbackRuntime = fallbackRuntime,
                    allowDiagnosticPhysicalPanelsWithoutUiSync =
                        physicalDisplayPanelsProbeEnabled &&
                            UltraProfileCompositionRoot.allowDiagnosticPhysicalPanelsWithoutUiSync(),
                )
                // The helper is now the sole close owner for the lifecycle/authorized root.
                lifecycleOwner = null
            }

            val authorization = authorizationOwner
            check(staged.isFinalizedFor(configTicket, restartGeneration)) {
                "Host construction changed before Controller publication"
            }
            CarPlayController(
                context = persistenceContext,
                config = config,
                airPlayConfig = airPlayConfig,
                identity = airPlayIdentity,
                pairings = pairings,
                listener = DetachedAirPlaySessionListener,
                media = media,
                reportStatus = {},
                loadPairRecord = { AirPlayPersistence.loadLockdownRecord(persistenceContext) },
                savePairRecord = { record ->
                    AirPlayPersistence.saveLockdownRecord(persistenceContext, record)
                },
                clearPairRecord = { AirPlayPersistence.clearLockdownRecord(persistenceContext) },
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleProvider,
                ultraAuthorization = authorization,
                sessionOverlayFactory = updateDataAcquisition?.overlayFactory,
                updateDataQuarantineOwner = updateDataAcquisition?.owner,
            ).also {
                // Controller teardown now owns these exact resources. Renderer retirement remains
                // a separate Host barrier stage after controller completion.
                updateDataAcquisition?.transferToController()
                updateDataAcquisition = null
                mediaOwner = null
                authorizationOwner = null
                locationProviderOwner = null
                vehicleProviderOwner = null
            }
        } catch (failure: Throwable) {
            if (pendingHostExternalBootstrap?.staged === stagedAirPlayConfig) {
                pendingHostExternalBootstrap = null
            }
            stagedAirPlayConfig?.cancel()
            val cleanup = HostConstructionCleanup(
                buildList {
                    closeableHostConstructionFailures(failure).forEachIndexed { index, owner ->
                        add(
                            HostConstructionCleanupStage(
                                "construction failure owner[$index]",
                                owner::close,
                            ),
                        )
                    }
                    externalOutputOwner?.let { owner ->
                        add(HostConstructionCleanupStage(
                            "external output", owner::closeAndRequireRetired,
                        ))
                    }
                    mediaOwner?.let { resource ->
                        add(HostConstructionCleanupStage("media runtime", resource::close))
                    } ?: fallbackOwner?.let { resource ->
                        add(HostConstructionCleanupStage("fallback Ultra runtime", resource::close))
                    }
                    authorizationOwner?.let { resource ->
                        add(HostConstructionCleanupStage("Ultra authorization", resource::close))
                    } ?: lifecycleOwner?.let { resource ->
                        add(HostConstructionCleanupStage("Ultra lifecycle", resource::close))
                    }
                    updateDataAcquisition?.let { resource ->
                        add(HostConstructionCleanupStage("UpdateData quarantine root", resource::close))
                    }
                    locationProviderOwner?.let { resource ->
                        add(HostConstructionCleanupStage("location provider", resource::close))
                    }
                    vehicleProviderOwner?.let { resource ->
                        add(HostConstructionCleanupStage("vehicle provider", resource::close))
                    }
                    rendererOwner?.let { resource ->
                        add(HostConstructionCleanupStage("media sink", resource::close))
                    } ?: outputsOwner?.let { resource ->
                        add(HostConstructionCleanupStage("WebDisplay outputs", resource::close))
                    }
                },
            )
            currentVehicleProvider = null
            currentGpsProvider = null
            currentWheelProvider = null
            retireHostConstructionFailure(cleanup, failure, controllerGeneration)
            return
        }

        val renderer = checkNotNull(rendererOwner)
        if (!attachActivityCallbacks(next, renderer, controllerGeneration)) {
            sink = null
            clusterPresentationCoordinator = null
            currentVehicleProvider = null
            currentGpsProvider = null
            currentWheelProvider = null
            handshakeResetInProgress = true
            submitHostTeardown(next, renderer)
            requestHostTeardownDrain(
                generation = controllerGeneration,
                replaceContinuation = true,
                onClear = {
                    handshakeResetInProgress = false
                    failHandshake(
                        "CarPlay UI attachment failed",
                        "The unbound controller and renderer retired cleanly; no replacement was started",
                    )
                    setConnectionStage("CarPlay UI attachment failed")
                },
            )
            return
        }
        try {
            controller = next
            sink = renderer
            clusterPresentationCoordinator = presentationCoordinator
            viewAreas = areas
            currentSurface?.let(::attachSurface)
            clusterPreviewSurface?.let { attachDisplaySurface(SCREEN_TYPE_ALT, it) }
            updateWebDisplayPreviews()
            startThenPublishBackgroundSession(
                start = next::start,
                publish = {
                    CarPlayBackgroundSession.store(
                        next,
                        renderer,
                        size.width,
                        size.height,
                        checkNotNull(handshakeTimeline),
                        activeScreenStreamTypes,
                        areas,
                        presentationCoordinator,
                    )
                },
            )
        } catch (failure: Throwable) {
            retireControllerStartupFailure(
                next,
                renderer,
                controllerGeneration,
                failure,
            )
        }
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!audioPacketCaptureEnabled) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || destroyed.get() || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        val layout = currentDisplayLayout()
        if (size == pendingDisplaySize && layout == pendingDisplayLayout) return
        val active = activeDisplaySize
        if (size == active && layout == activeDisplayLayout) {
            // Back to the shown size before the debounce fired: the change was transient.
            pendingDisplaySize = null
            mainHandler.removeCallbacks(applyDisplaySize)
            return
        }
        pendingDisplaySize = size
        pendingDisplayLayout = layout
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (destroyed.get() || shuttingDown.get()) return
        val previous = activeDisplaySize
        val layout = currentDisplayLayout()
        val layoutChanged = activeDisplayLayout != null && layout != activeDisplayLayout
        activeDisplaySize = size
        activeDisplayLayout = layout
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (applyViewArea(size)) return
        if (size == previous && !layoutChanged) {
            maybeStartCarPlay()
            return
        }
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null) {
            maybeStartCarPlay()
        } else if (mainWebDisplay.enabled) {
            // The web display owns its negotiated size; only the preview follows the window.
            updateWebDisplayPreviews()
        } else if (!layoutChanged && canKeepDisplayWindow(size)) {
            appendLog(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height} " +
                    "inside declared ${viewAreas?.activityWidthPixels}x${viewAreas?.activityHeightPixels}; " +
                    "keeping session",
            )
            updateWebDisplayPreviews()
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun canKeepDisplayWindow(size: DisplaySize): Boolean {
        val areas = viewAreas ?: return false
        val active = controller ?: return false
        if (currentDisplayLayout().splitScreen) return false
        val window = areas.activitySize(active.viewAreaIndex)
        // Temporary overlays may shrink the current layout, but cannot change its orientation.
        return size.width <= window.first && size.height <= window.second &&
            CarPlayDisplayOrientation.from(size.width, size.height) == areas.orientation &&
            (areas.indexFor(size.width, size.height) == null || active.viewAreaIndex == areas.indexFor(size.width, size.height))
    }

    /** Switches to a declared area; failure falls through to the renegotiation decision. */
    private fun applyViewArea(size: DisplaySize): Boolean {
        val areas = viewAreas ?: return false
        val active = controller ?: return false
        if (active.isClosed() || menuOpen || handshakeResetInProgress || shuttingDown.get()) return false
        val current = active.viewAreaIndex
        // Opposite-orientation dimensions were not part of this handshake. Falling through makes
        // applyDisplaySize restart the session with the new direction as view area zero.
        if (CarPlayDisplayOrientation.from(size.width, size.height) != areas.orientation) return false
        val layout = currentDisplayLayout()
        val target = areas.indexFor(size.width, size.height, layout.splitScreen, layout.portrait) ?: return false
        if (target == current && !layout.splitScreen) return false
        if (!active.showViewArea(target)) return false
        appendLog(
            "View area $current -> $target for ${size.width}x${size.height} " +
                "(${areas.areas[target].widthPixels}x${areas.areas[target].heightPixels})",
        )
        updateWebDisplayPreviews()
        return true
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val maximum = currentMaximumDisplaySize(size)
        val width = maximum.width
        val height = maximum.height
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (destroyed.get() || shuttingDown.get() || menuOpen) return
        pendingHostExternalBootstrap?.let { pending ->
            if (pending.generation != restartGeneration ||
                activeDisplaySize != DisplaySize(pending.width, pending.height)
            ) {
                cancelPendingHostExternalBootstrap()
                requestHostTeardownDrain(restartGeneration, onClear = { maybeStartCarPlay() })
            } else if (pending.outcome != null) {
                startCarPlay(DisplaySize(pending.width, pending.height))
            }
            return
        }
        if (HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR) {
            requestHostTeardownDrain(
                generation = restartGeneration,
                onClear = { maybeStartCarPlay() },
            )
            return
        }
        if (physicalSurfaceRegistry.hasPendingRetirement() ||
            HostExternalOutputOwner.hasPendingProcessRetirement()
        ) {
            HostPhysicalDisplaySurfaces.requestRetirement()
            schedulePhysicalRetirementPoll()
            return
        }
        if (handshakeResetInProgress) return
        if (controller == null) {
            if (adoptBackgroundSession()) return
            // An adoption failure publishes the indivisible controller/sink pair above. Do not
            // continue this same maybeStart call past that newly-created process barrier.
            if (HostTeardownProcessBarrier.coordinator.state() != HostTeardownBarrierState.CLEAR) {
                return
            }
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun schedulePhysicalRetirementPoll() {
        if (destroyed.get() || shuttingDown.get()) return
        physicalRetirementPoller.arm(restartGeneration)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            minOf(
                RECONNECT_DELAY_MILLIS shl reconnectAttempts.coerceAtMost(RECONNECT_MAX_SHIFT),
                RECONNECT_MAX_DELAY_MILLIS,
            ).also { reconnectAttempts++ }
        }
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                if (generation != restartGeneration) return@postDelayed
                reconnectScheduled = false
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        cancelPendingHostExternalBootstrap()
        physicalRetirementPoller.invalidate()
        val oldController = controller
        val oldSink = sink
        detachActivityCallbacks()
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        viewAreas = null
        sink = null
        clusterPresentationCoordinator = null
        handshakeResetInProgress = true
        reconnectScheduled = false
        activeAirPlaySession = null
        hostUiRequested = false
        submitHostTeardown(oldController, oldSink)
        requestHostTeardownDrain(
            generation = generation,
            replaceContinuation = true,
            onClear = {
                handshakeResetInProgress = false
                maybeStartCarPlay()
            },
        )
    }

    private fun openSettingsMenu() {
        if (menuOpen || shuttingDown.get()) return
        appLinksPanelOpen = false
        updateVehicleDataStatus()
        lastVehicleDataDiagnostics = vehicleDataStatusText
        stopMicrophoneGainTest()
        settingsBaseline = captureSettingsBaseline()
        ultraSaveBlocked = false
        menuOpen = true
        updateVehicleDataStatus()
        handshakeResetInProgress = true
        startAfterHandshakeReset = false
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        val generation = ++restartGeneration
        cancelPendingHostExternalBootstrap()
        physicalRetirementPoller.invalidate()
        controller?.sendTouch(emptyList())
        val oldController = controller
        val oldSink = sink
        detachActivityCallbacks()
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        viewAreas = null
        sink = null
        clusterPresentationCoordinator = null
        activeScreenStreamTypes.clear()
        setConnectionStage("Reconnecting after settings")
        gestureOverlay?.visibility = View.GONE
        settingsCategory = null
        mfiError = null
        manualHotspotError = null
        resetWebDisplayDrafts()
        syncMainMediaAudioBufferControls()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateDebugOverlays()
        clearScreenLogs()
        appendLog("Settings opened; CarPlay handshake reset")
        updateResolutionMenu()
        submitHostTeardown(oldController, oldSink)
        requestHostTeardownDrain(
            generation = generation,
            replaceContinuation = true,
            onClear = {
                handshakeResetInProgress = false
                if (!menuOpen && startAfterHandshakeReset) {
                    startAfterHandshakeReset = false
                    maybeStartCarPlay()
                }
            },
        )
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        if (!validateWebDisplaySettings()) return
        if (!validateCatalogProbeSettings()) return
        val persistedUltra = AirPlayPersistence.peekCarPlayUltraEnabled(this)
        val saved = persistHostSettingsWithUltraCover(
            persistedUltraEnabled = persistedUltra,
            desiredUltraEnabled = carPlayUltraEnabled,
            beginMutation = UltraProfileCompositionRoot::beginConsentMutation,
            persist = ::persistMenuSettings,
        )
        if (!saved) {
            // Keep other unsaved edits and the menu. Revert only the rejected Ultra switch and
            // its dependent probes; no preference, successor provider or DDP output was changed.
            val baseline = settingsBaseline
            carPlayUltraEnabled = persistedUltra
            physicalDisplayPanelsProbeEnabled = if (persistedUltra) {
                baseline?.physicalPanelsProbeEnabled == true
            } else false
            catalogProbeSettings = catalogProbeSettings.copy(
                enabled = persistedUltra && baseline?.catalogProbeEnabled == true,
            )
            catalogProbeConfigError = false
            ultraSaveBlocked = true
            settingsCategory = SettingsCategory.WEB
            appendLog("Ultra setting unchanged: exact old physical output cover is pending")
            return
        }
        ultraSaveBlocked = false
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun validateCatalogProbeSettings(): Boolean {
        catalogProbeConfigError = carPlayUltraEnabled && catalogProbeSettings.enabled &&
            catalogProbeSettings.toProbeOrNull(ultraEnabled = true) == null
        if (catalogProbeConfigError) settingsCategory = SettingsCategory.WEB
        return !catalogProbeConfigError
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsCategory = null
        gestureOverlay?.visibility = View.VISIBLE
        updateWebDisplayPreviews()
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    /** Publishes resources to the process barrier without retaining this Activity. */
    private fun submitHostTeardown(
        oldController: CarPlayController?,
        oldSink: AndroidMediaSink?,
    ) {
        val timeoutMillis = CONTROLLER_CLOSE_TIMEOUT_MILLIS
        val actions = HostTeardownActions(
            controllerIdentity = oldController,
            sinkIdentity = oldSink,
            requestControllerClose = oldController?.let(::CloseControllerOperation),
            awaitControllerClosed = oldController?.let { resource ->
                AwaitControllerOperation(resource, timeoutMillis)
            },
            closeMediaSink = oldSink?.let(::CloseMediaSinkOperation),
        )
        val oldView = videoView
        val oldSurface = currentSurface
        val oldTexture = currentSurfaceTexture
        val version = mainPhysicalCoverRegistration?.takeIf {
            it.surface === oldSurface && it.texture === oldTexture
        }?.version ?: physicalSurfaceRegistry.snapshot(DisplayDestination.MAIN)
        val borrowRetirement = if (
            !destroyed.get() && !shuttingDown.get() && actions.isEmpty.not() &&
            mainPhysicalCoverOwner?.requiresOrdinaryRecovery == true &&
            oldView != null && oldSurface != null && oldTexture != null && version != null
        ) physicalSurfaceRegistry.captureExactMainBorrowRetirement(version, oldSurface) else null
        if (borrowRetirement != null && oldView != null && oldSurface != null &&
            oldTexture != null
        ) {
            mainPhysicalCoverOwner?.retireActiveIfUsed()
            val ticket = HostTeardownProcessBarrier.coordinator.submitTicket(
                actions, borrowRetirement,
            )
            pendingMainOrdinaryRecovery = PendingMainOrdinaryRecovery(
                restartGeneration, oldView, oldSurface, oldTexture, oldController, oldSink,
                borrowRetirement, ticket,
            )
        } else {
            HostTeardownProcessBarrier.coordinator.submit(actions)
        }
    }

    /** The process ticket and Clear barrier must both precede the old View withdrawal. */
    private fun completePendingMainOrdinaryRecovery(): Boolean {
        val pending = pendingMainOrdinaryRecovery ?: return true
        if (destroyed.get() || shuttingDown.get() || pending.generation != restartGeneration) {
            return false
        }
        val retirement = provenMainOrdinaryRetirement(
            pending.teardownTicket, pending.oldController, pending.oldSink,
            pending.borrowRetirement, HostTeardownProcessBarrier.coordinator.state(),
        ) ?: return false
        if (!pending.viewRemoved) {
            val oldStillPublished = currentSurface === pending.oldSurface &&
                currentSurfaceTexture === pending.oldTexture
            val oldAlreadyDestroyed = currentSurface == null &&
                currentSurfaceTexture == null &&
                physicalSurfaceRegistry.isTextureViewDetached(pending.oldTexture)
            if (videoView !== pending.oldView ||
                (!oldStillPublished && !oldAlreadyDestroyed) ||
                !pending.borrowRetirement.isFor(
                    pending.borrowRetirement.version, pending.oldSurface,
                ) || mainPhysicalCoverOwner?.retireActiveIfUsed() != true
            ) return false
            mainPhysicalCoverRegistration?.close()
            mainPhysicalCoverRegistration = null
            currentSurface = null
            currentSurfaceTexture = null
            mainTextureDiagnosticTexture = null
            // The registry records exact old identity withdrawal before the framework detaches
            // the TextureView. Its destroy callback then only marks this texture View-detached.
            if (oldStillPublished) releaseDisplaySurfaceAfterRetirement(
                SCREEN_TYPE_MAIN, pending.oldSurface, pending.oldTexture,
            )
            pending.viewRemoved = true
            pending.oldView.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
        }
        if (!physicalSurfaceRegistry.isTextureViewDetached(pending.oldTexture)) return false
        val frame = mainFrame ?: return false
        if (videoView !== pending.oldView) return false
        val recovery = MainOrdinaryRecovery(
            pending.generation, retirement, pending.oldController, pending.oldSink,
            pending.borrowRetirement,
        )
        mainOrdinaryRecovery = recovery
        pendingMainOrdinaryRecovery = null
        val fresh = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        videoView = fresh
        frame.addView(
            fresh, 0,
            FrameLayout.LayoutParams(
                pending.oldView.layoutParams ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            ),
        )
        updateWebDisplayPreviews()
        appendLog("MAIN ordinary output received a fresh TextureView generation")
        return true
    }

    /**
     * Retires owners acquired before a controller could take them. A failed exact cleanup is
     * transferred to the same process barrier used by Activity recreation and later retried.
     */
    private fun retireHostConstructionFailure(
        cleanup: HostConstructionCleanup,
        failure: Throwable,
        generation: Int,
    ) {
        val cleanupFailure = try {
            cleanup.close()
            null
        } catch (caught: Throwable) {
            if (caught !== failure) failure.addSuppressed(caught)
            caught
        }
        if (cleanupFailure != null) {
            handshakeResetInProgress = true
            HostTeardownProcessBarrier.coordinator.submit(
                HostTeardownActions(
                    controllerIdentity = null,
                    sinkIdentity = cleanup,
                    requestControllerClose = null,
                    awaitControllerClosed = null,
                    closeMediaSink = CloseHostConstructionOperation(cleanup),
                ),
            )
            requestHostTeardownDrain(
                generation = generation,
                replaceContinuation = true,
                onClear = {
                    handshakeResetInProgress = false
                    failHandshake(
                        "CarPlay construction failed",
                        failure.message ?: "Construction resources retired after a retry",
                    )
                    setConnectionStage("CarPlay construction failed")
                },
            )
        } else {
            failHandshake(
                "CarPlay construction failed",
                failure.message ?: "Could not construct the CarPlay stack",
            )
            setConnectionStage("CarPlay construction failed")
        }
        appendLog("CarPlay construction failed: ${failure.message ?: failure.javaClass.simpleName}")
        when {
            failure is Error -> throw failure
            cleanupFailure is Error -> throw cleanupFailure
        }
    }

    /** A started or constructed controller is always retired through the process barrier. */
    private fun retireControllerStartupFailure(
        failedController: CarPlayController,
        failedSink: AndroidMediaSink,
        generation: Int,
        failure: Throwable,
    ) {
        detachActivityCallbacks()
        CarPlayBackgroundSession.clear(failedController)
        if (controller === failedController) controller = null
        if (sink === failedSink) sink = null
        viewAreas = null
        clusterPresentationCoordinator = null
        currentVehicleProvider = null
        currentGpsProvider = null
        currentWheelProvider = null
        activeAirPlaySession = null
        activeScreenStreamTypes.clear()
        handshakeResetInProgress = true
        submitHostTeardown(failedController, failedSink)
        requestHostTeardownDrain(
            generation = generation,
            replaceContinuation = true,
            onClear = {
                handshakeResetInProgress = false
                failHandshake(
                    "CarPlay startup failed",
                    failure.message ?: "The partially started stack retired cleanly",
                )
                setConnectionStage("CarPlay startup failed")
            },
        )
        appendLog("CarPlay startup failed: ${failure.message ?: failure.javaClass.simpleName}")
        if (failure is Error) throw failure
    }

    /**
     * Installs an Activity-local continuation, while all resources remain process-owned. A newer
     * generation or explicit transition may replace a generic maybeStart continuation.
     */
    private fun requestHostTeardownDrain(
        generation: Int,
        replaceContinuation: Boolean = false,
        onClear: () -> Unit,
        onFailure: (HostTeardownOutcome.Failed) -> Unit = { reportHostTeardownFailure(it) },
    ) {
        if (destroyed.get() || shuttingDown.get()) return
        val current = hostTeardownContinuation
        if (current == null || replaceContinuation || current.generation != generation) {
            hostTeardownContinuation = HostTeardownContinuation(generation, onClear, onFailure)
        }
        launchHostTeardownDrain()
    }

    /** Claims and runs process-owned teardown outside both the coordinator and UI locks. */
    private fun launchHostTeardownDrain() {
        if (destroyed.get() || shuttingDown.get() || hostTeardownContinuation == null) return
        if (!hostTeardownDriveScheduled.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(retryHostTeardownBarrier)
        try {
            teardownExecutor.execute {
                val result = drainHostTeardowns(HostTeardownProcessBarrier.coordinator)
                if (result is HostTeardownDrainResult.Failed) {
                    logHostTeardownFailure(result.outcome)
                }
                runOnUiThread {
                    hostTeardownDriveScheduled.set(false)
                    val continuation = hostTeardownContinuation ?: return@runOnUiThread
                    val uiDecision = decideHostTeardownUi(
                        outcome = (result as? HostTeardownDrainResult.Failed)?.outcome
                            ?: HostTeardownOutcome.Clean,
                        expectedGeneration = continuation.generation,
                        currentGeneration = restartGeneration,
                        shuttingDown = shuttingDown.get(),
                        destroyed = destroyed.get(),
                    )
                    if (uiDecision == HostTeardownUiDecision.STALE_OR_STOPPING) {
                        hostTeardownContinuation = null
                        return@runOnUiThread
                    }
                    when (result) {
                        HostTeardownDrainResult.Clear -> {
                            if (!completePendingMainOrdinaryRecovery()) {
                                mainHandler.postDelayed(
                                    retryHostTeardownBarrier,
                                    HOST_TEARDOWN_IN_FLIGHT_POLL_MILLIS,
                                )
                                return@runOnUiThread
                            }
                            hostTeardownContinuation = null
                            continuation.onClear()
                        }
                        HostTeardownDrainResult.InFlight -> mainHandler.postDelayed(
                            retryHostTeardownBarrier,
                            HOST_TEARDOWN_IN_FLIGHT_POLL_MILLIS,
                        )
                        is HostTeardownDrainResult.Failed -> {
                            check(uiDecision == HostTeardownUiDecision.FAILED)
                            continuation.onFailure(result.outcome)
                            mainHandler.postDelayed(
                                retryHostTeardownBarrier,
                                HOST_TEARDOWN_FAILURE_RETRY_MILLIS,
                            )
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            hostTeardownDriveScheduled.set(false)
        }
    }

    private fun logHostTeardownFailure(failure: HostTeardownOutcome.Failed) {
        val message = "CarPlay teardown failed stage=${failure.stage.logId}"
        failure.cause?.let { Log.e(TAG, message, it) } ?: Log.e(TAG, message)
        sessionLog.append(message)
    }

    private fun reportHostTeardownFailure(failure: HostTeardownOutcome.Failed) {
        failHandshake("CarPlay teardown failed", failure.stage.userDetail)
        setConnectionStage("CarPlay teardown failed (${failure.stage.logId})")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        stopMicrophoneGainTest()
        restartGeneration += 1
        cancelPendingHostExternalBootstrap()
        physicalRetirementPoller.close()
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        detachActivityCallbacks()
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        viewAreas = null
        sink = null
        clusterPresentationCoordinator = null
        mainHandler.removeCallbacks(retryHostTeardownBarrier)
        hostTeardownContinuation = null
        submitHostTeardown(oldController, oldSink)
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute { attemptHostShutdown(terminateProcess) }
    }

    /** Keep this executor alive across Activity destruction until the process barrier is clear. */
    private fun attemptHostShutdown(terminateProcess: Boolean) {
        val teardown = drainHostTeardowns(HostTeardownProcessBarrier.coordinator)
        if (!canCompleteHostTermination(teardown)) {
            val stage = when (teardown) {
                HostTeardownDrainResult.Clear -> error("unreachable")
                HostTeardownDrainResult.InFlight -> "in-flight"
                is HostTeardownDrainResult.Failed -> teardown.outcome.stage.logId.also {
                    logHostTeardownFailure(teardown.outcome)
                }
            }
            val delay = if (teardown === HostTeardownDrainResult.InFlight) {
                HOST_TEARDOWN_IN_FLIGHT_POLL_MILLIS
            } else {
                minOf(
                    HOST_TEARDOWN_FAILURE_RETRY_MILLIS shl shutdownRetryAttempts.coerceAtMost(4),
                    HOST_SHUTDOWN_MAX_RETRY_MILLIS,
                ).also { shutdownRetryAttempts++ }
            }
            sessionLog.append("CarPlay exit pending; teardown stage=$stage; retrying")
            Log.w(TAG, "shutdown retained process resources stage=$stage retryIn=${delay}ms")
            teardownExecutor.schedule(
                { attemptHostShutdown(terminateProcess) },
                delay,
                TimeUnit.MILLISECONDS,
            )
            return
        }
        airPlayCommandExecutor.shutdown()
        if (terminateProcess) {
            applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
        }
        sessionLog.append("CarPlay stopped; clean=true")
        sessionLog.close()
        Log.i(TAG, "shutdown complete clean=true")
        teardownExecutor.shutdown()
        if (terminateProcess) Process.killProcess(Process.myPid())
    }

    private fun attachSurface(surface: Surface): Boolean {
        val currentSink = sink ?: return false
        val session = CarPlayWebDisplayFactory.find(currentSink)
        if (!rememberWebPreview(SCREEN_TYPE_MAIN, surface, session)) return false
        val attached = if (session?.owns(SCREEN_TYPE_MAIN) == true) {
            tryPreviewWebDisplay(session, SCREEN_TYPE_MAIN, surface)
        } else {
            try {
                currentSink.trySetSurfaceForPreview(SCREEN_TYPE_MAIN, surface)
            } catch (failure: Exception) {
                Log.w(TAG, "MAIN ordinary route attachment unavailable", failure)
                false
            }
        }
        if (attached) {
            mainOrdinaryRecovery?.takeIf {
                it.generation == restartGeneration && currentSurface === surface &&
                    it.freshTexture === currentSurfaceTexture
            }?.let {
                it.attachedController = controller
                it.attachedSink = currentSink
            }
            tryArmMainOrdinaryRecovery()
        }
        return attached
    }

    private fun exactMainOrdinaryRouteCurrent(
        recovery: MainOrdinaryRecovery,
        surface: Surface,
        texture: SurfaceTexture,
    ): Boolean {
        val registration = mainPhysicalCoverRegistration ?: return false
        return !destroyed.get() && !shuttingDown.get() &&
            recovery === mainOrdinaryRecovery && recovery.generation == restartGeneration &&
            recovery.retirement.matchesExact(
                recovery.oldController, recovery.oldSink, recovery.borrowRetirement,
            ) && recovery.borrowRetirement.isRetired &&
            recovery.freshTexture === texture &&
            currentSurface === surface && currentSurfaceTexture === texture &&
            videoView?.surfaceTexture === texture &&
            recovery.attachedController != null && controller === recovery.attachedController &&
            recovery.attachedSink != null && sink === recovery.attachedSink &&
            registration.surface === surface && registration.texture === texture &&
            physicalSurfaceRegistry.isSameLivePhysicalTarget(registration.version, surface) &&
            HostTeardownProcessBarrier.coordinator.state() == HostTeardownBarrierState.CLEAR
    }

    private fun tryArmMainOrdinaryRecovery() {
        val recovery = mainOrdinaryRecovery ?: return
        val surface = currentSurface ?: return
        val texture = currentSurfaceTexture ?: return
        if (!exactMainOrdinaryRouteCurrent(recovery, surface, texture)) return
        val registration = mainPhysicalCoverRegistration ?: return
        val routeProof = { exactMainOrdinaryRouteCurrent(recovery, surface, texture) }
        if (!registration.armOrdinaryRecovery(routeProof)) return
        if (mainWebDisplay.enabled && !mainWebDisplay.preview) {
            registration.revealOrdinaryNoPreview(
                routeProof,
                videoHidden = { videoView?.visibility != View.VISIBLE },
            )
        }
    }

    private fun attachDisplaySurface(type: Int, surface: Surface) {
        val currentSink = sink
        val session = CarPlayWebDisplayFactory.find(currentSink)
        if (!rememberWebPreview(type, surface, session)) return
        attachHostDisplaySurface(
            CurrentHostDisplaySurfaceRoute(session, currentSink),
            type,
            surface,
        )
    }

    private fun detachSurface(type: Int, surface: Surface) {
        val currentSink = sink
        val captured = attachedWebPreviews.remove(surface)
        detachHostDisplaySurface(
            CurrentHostDisplaySurfaceRoute(captured?.session, currentSink),
            type,
            surface,
        )
        // This exact clear is harmless after a successful WebDisplay detach, and also handles
        // a stale Activity whose originally attached Session has already closed.
        if (captured != null) currentSink?.clearSurface(type, surface)
    }

    private fun rememberWebPreview(
        type: Int,
        surface: Surface,
        session: CarPlayWebDisplayFactory.Session?,
    ): Boolean {
        val previous = attachedWebPreviews[surface]
        if (previous != null && (previous.session !== session || previous.type != type)) {
            try {
                previous.session.clearPreview(previous.type, surface)
            } catch (failure: Exception) {
                // Keep the exact old Session for retry; a new one must not overwrite an
                // uncertain decoder detach or crash a TextureView callback.
                Log.w(TAG, "Previous Web preview detach remains pending type=${previous.type}",
                    failure)
                return false
            }
            attachedWebPreviews.remove(surface)
        }
        if (session?.owns(type) == true) {
            attachedWebPreviews[surface] = AttachedWebPreview(type, session)
        }
        return true
    }

    private fun noteWebPreviewAdmission(type: Int, attached: Boolean) {
        if (attached) {
            unavailableWebPreviewsLogged.remove(type)
        } else if (unavailableWebPreviewsLogged.add(type)) {
            appendLog("Web preview unavailable for display type=$type; physical handoff or session retirement is pending")
        }
    }

    private fun tryPreviewWebDisplay(
        session: CarPlayWebDisplayFactory.Session,
        type: Int,
        surface: Surface,
    ): Boolean = try {
        session.preview(type, surface)
    } catch (failure: Exception) {
        Log.w(TAG, "Web preview attachment unavailable type=$type", failure)
        false
    }

    private fun noteTextureDestroyed(texture: SurfaceTexture): Boolean = try {
        physicalSurfaceRegistry.onTextureDestroyed(texture)
    } catch (failure: Exception) {
        // The registry keeps ownership after a failed native release; the framework must not
        // release this texture a second time. A later host can retry the resource-only record.
        Log.w(TAG, "Texture release remains pending", failure)
        false
    }

    /**
     * Never releases a Surface/SurfaceTexture while a process-owned sink may still reference it.
     * Returning false transfers SurfaceTexture ownership away from TextureView until the barrier
     * runs the resource-only release closure.
     */
    private fun releaseDisplaySurfaceAfterRetirement(
        type: Int,
        surface: Surface,
        texture: SurfaceTexture?,
        textureDestroyed: Boolean = false,
    ): Boolean {
        val destination = when (type) {
            SCREEN_TYPE_MAIN -> DisplayDestination.MAIN
            SCREEN_TYPE_ALT -> DisplayDestination.CLUSTER
            else -> null
        }
        val retirement = if (destination != null && texture != null) {
            physicalSurfaceRegistry.withdraw(destination, surface, texture, textureDestroyed)
        } else null
        if (retirement != null) {
            val capturedWebDisplay = attachedWebPreviews.remove(surface)?.session
            val deferred = try {
                HostTeardownProcessBarrier.coordinator.deferReleaseWhilePending(
                    identity = surface,
                    release = retirement::completeAfterLegacyOwnerRetired,
                )
            } catch (failure: Exception) {
                retirement.retainRetryDetacher(RetryDisplaySurfaceDetach(
                    sink, capturedWebDisplay, type, surface,
                ))
                Log.w(TAG, "Surface retirement registration pending type=$type", failure)
                return false
            }
            if (deferred) {
                Log.i(TAG, "Surface retirement deferred until host teardown type=$type")
                return false
            }
            // Decoder detach and native release can block or fail. The process owner retries
            // both stages off the UI thread and the admission gate remains closed until clear.
            retirement.retainRetryDetacher(RetryDisplaySurfaceDetach(
                sink, capturedWebDisplay, type, surface,
            ))
            return false
        }
        val deferred = HostTeardownProcessBarrier.coordinator.deferReleaseWhilePending(
            identity = surface,
            release = DeferredDisplaySurfaceRelease(surface, texture),
        )
        if (deferred) {
            Log.i(TAG, "Surface release deferred until host teardown type=$type")
            return false
        }
        detachSurface(type, surface)
        surface.release()
        return true
    }

    private fun updateWebDisplayPreviews() {
        val view = videoView
        view?.visibility = if (!mainWebDisplay.enabled || mainWebDisplay.preview) View.VISIBLE else View.INVISIBLE
        val matrix = android.graphics.Matrix()
        val placement = view?.let { mainAreaPlacement(it.width, it.height) }
        if (view != null && placement != null) {
            // The decoder output fills the view by default; scale so only the active area does.
            val (rect, share) = placement
            matrix.setScale(
                (rect[2] / view.width / share[0]).toFloat(),
                (rect[3] / view.height / share[1]).toFloat(),
            )
            matrix.postTranslate(rect[0].toFloat(), rect[1].toFloat())
        }
        view?.setTransform(matrix)
        clusterPreviewWindow?.let { window ->
            val shown = clusterDisplayEnabled && clusterWebDisplay.preview
            window.visibility = if (shown && !clusterPreviewCollapsed) View.VISIBLE else View.GONE
            clusterPreviewHandle?.visibility = if (shown && clusterPreviewCollapsed) View.VISIBLE else View.GONE
            layoutClusterWindow()
        }
        webDisplays?.let { session ->
            currentSurface?.let { surface ->
                val ownerReady = rememberWebPreview(SCREEN_TYPE_MAIN, surface, session)
                noteWebPreviewAdmission(SCREEN_TYPE_MAIN,
                    ownerReady && tryPreviewWebDisplay(session, SCREEN_TYPE_MAIN, surface))
            }
            clusterPreviewSurface?.let { surface ->
                val ownerReady = rememberWebPreview(SCREEN_TYPE_ALT, surface, session)
                noteWebPreviewAdmission(SCREEN_TYPE_ALT,
                    ownerReady && tryPreviewWebDisplay(session, SCREEN_TYPE_ALT, surface))
            }
        }
        tryArmMainOrdinaryRecovery()
    }

    /**
     * Where the main stream's active area is drawn in a view of this size, as
     * [left, top, width, height], plus the area's [width, height] share of the stream. Null when
     * the stream simply fills the view.
     */
    private fun mainAreaPlacement(viewWidth: Int, viewHeight: Int): Pair<DoubleArray, DoubleArray>? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (mainWebDisplay.enabled) {
            return CarPlayTouchMapper.contentRect(viewWidth, viewHeight, mainWebDisplay.width, mainWebDisplay.height) to
                doubleArrayOf(1.0, 1.0)
        }
        val areas = viewAreas ?: return null
        val index = controller?.viewAreaIndex?.takeIf { it in 0 until areas.size } ?: return null
        val area = areas.areas[index]
        val share = doubleArrayOf(
            area.widthPixels.toDouble() / areas.widthPixels,
            area.heightPixels.toDouble() / areas.heightPixels,
        )
        // The area's own activity size fills the view; any other size inside the negotiated extent is
        // letterboxed so the phone's layout keeps its aspect ratio.
        val rect = if (areas.activitySize(index) == (viewWidth to viewHeight)) {
            doubleArrayOf(0.0, 0.0, viewWidth.toDouble(), viewHeight.toDouble())
        } else {
            CarPlayTouchMapper.contentRect(viewWidth, viewHeight, area.widthPixels, area.heightPixels)
        }
        return rect to share
    }

    private fun setClusterPreviewCollapsed(collapsed: Boolean) {
        clusterPreviewCollapsed = collapsed
        updateWebDisplayPreviews()
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                edgeSettingsGestureCaptured = moreGesturesToSettings &&
                    event.x in 0f..(view.width / 8f) &&
                    event.y in 0f..(view.height / 4f)
                edgeSettingsGestureEligible = edgeSettingsGestureCaptured
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (edgeSettingsGestureCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> edgeSettingsGestureEligible = false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || event.x !in 0f..(view.width / 8f)) {
                        edgeSettingsGestureEligible = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val openSettings = edgeSettingsGestureEligible &&
                        event.x in 0f..(view.width / 8f) &&
                        event.y in (view.height * 3f / 4f)..view.height.toFloat()
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    if (openSettings) openSettingsMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                }
            }
            return true
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        if (mainWebDisplay.enabled && !mainWebDisplay.preview) return true
        val placement = mainAreaPlacement(view.width, view.height)
        val contacts = if (placement == null) {
            CarPlayTouchMapper.contacts(event, view.width, view.height)
        } else {
            // Touch is normalized to the active area, then to the whole declared display.
            val (rect, share) = placement
            CarPlayTouchMapper.contacts(event, rect)
                .map { it.copy(x = it.x * share[0], y = it.y * share[1]) }
        }
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        connectionStatus = message
        updateDebugOverlays()
    }

    /** Re-derives what the overlay shows; the Compose layer reads the state set here. */
    private fun updateDebugOverlays() {
        mainStreamActive = SCREEN_TYPE_MAIN in activeScreenStreamTypes
        applySystemBarAppearance()
        updateMediaMetricsOverlay()
    }

    /** Dark bar icons only over our own light surfaces; video and the safe-area editor are dark. */
    private fun applySystemBarAppearance() {
        val overThemeSurface = !safeAreaEditorActive && (menuOpen || !mainStreamActive)
        val light = overThemeSurface && !darkMode
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun recordHandshake(controllerGeneration: Int, message: String, atMillis: Long) {
        if (controllerGeneration != restartGeneration) return
        val timeline = handshakeTimeline ?: return
        if (timeline.accept(message, atMillis)) notifyTimelineChanged()
    }

    private val publishTimeline = Runnable {
        timelineRenderScheduled = false
        timelineRevision++
    }

    /** Coalesces bursts of handshake records into one UI update. */
    private fun notifyTimelineChanged() {
        if (timelineRenderScheduled) return
        timelineRenderScheduled = true
        mainHandler.postDelayed(publishTimeline, TIMELINE_RENDER_INTERVAL_MILLIS)
    }

    private fun failHandshake(title: String, detail: String) {
        val timeline = handshakeTimeline ?: return
        timeline.fail(title, detail, System.currentTimeMillis())
        lastAttemptFailure = timeline.failure?.let { "${it.title}: ${it.detail}" }
        notifyTimelineChanged()
    }

    private fun updateMediaMetricsOverlay() {
        val frame = mainFrame ?: return
        if (!mediaMetricsEnabled) {
            sink?.setMediaMetricsMonitor(null)
            mediaMetricsOverlay?.let(frame::removeView)
            mediaMetricsOverlay = null
            mediaMetricsMonitor = null
            return
        }
        val monitor = mediaMetricsMonitor ?: MediaMetricsMonitor().also {
            mediaMetricsMonitor = it
        }
        sink?.setMediaMetricsMonitor(monitor)
        // The frame-rate axis follows the configured target, which the settings menu can change
        // while the overlay stays alive.
        val overlay = mediaMetricsOverlay ?: MediaMetricsOverlayView(
            this,
            monitor,
            fpsAxisMax = { fps.toFloat() },
        ).also {
            mediaMetricsOverlay = it
            frame.addView(it, metricsLayoutParams(frame.width, frame.height))
        }
        overlay.visibility = if (!menuOpen && !safeAreaEditorActive) View.VISIBLE else View.GONE
        updateMediaMetricsOverlayLayout(frame.width, frame.height)
    }

    private fun updateMediaMetricsOverlayLayout(screenWidth: Int, screenHeight: Int) {
        val overlay = mediaMetricsOverlay ?: return
        val desired = metricsLayoutParams(screenWidth, screenHeight)
        val current = overlay.layoutParams
        if (current.width != desired.width || current.height != desired.height) {
            overlay.layoutParams = desired
        }
    }

    private fun metricsLayoutParams(width: Int, height: Int): FrameLayout.LayoutParams {
        val fallback = resources.displayMetrics
        val panelWidth = (width.takeIf { it > 0 } ?: fallback.widthPixels) / 3
        val panelHeight = (height.takeIf { it > 0 } ?: fallback.heightPixels) / 3
        return FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        val summary = ScreenLogPolicy.summary(message) ?: return
        logLines.add(timestampMillis, formattedLogLine(summary, timestampMillis))
        if (!logRenderScheduled) {
            logRenderScheduled = true
            mainHandler.postDelayed(renderLogLines, LOG_RENDER_INTERVAL_MILLIS)
        }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) =
        sessionLog.appendTimestamped(message, timestampMillis)

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        val logFile = File(File(baseDirectory, "logs"), "xcertplay.log")
        sessionLog.start(
            logFile,
            "xcertplay log started " +
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                "pid=${Process.myPid()} path=${logFile.absolutePath}",
            onError = { Log.e(TAG, it) },
        )
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        screenLogs = logLines.snapshot()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
            screenDrainPosted = false
        }
        mainHandler.removeCallbacks(drainScreenLogs)
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        logRenderScheduled = false
        screenLogs = emptyList()
    }

    private fun applyFullscreenMode() {
        // Always edge-to-edge: the video pads itself by the visible bars, Compose by safeDrawing.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTopBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottomBar) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        applySystemBarAppearance()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // -----------------------------------------------------------------------------------------
    // Compose UI: home, settings (two levels) and safe-area editor chrome
    // -----------------------------------------------------------------------------------------

    private enum class SettingsCategory(val key: String, @param:DrawableRes val icon: Int) {
        CONNECTION("connection", R.drawable.ic_ms_link),
        VEHICLE("vehicle", R.drawable.ic_ms_directions_car),
        AUDIO("audio", R.drawable.ic_ms_graphic_eq),
        DISPLAY("display", R.drawable.ic_ms_display_settings),
        WEB("web", R.drawable.ic_ms_cast),
        IDENTITY("identity", R.drawable.ic_ms_badge),
        WINDOW("window", R.drawable.ic_ms_fullscreen),
        DIAGNOSTICS("diagnostics", R.drawable.ic_ms_bug_report),
        ;

        val title: String get() = tr("settings.category.$key")
        val summary: String get() = tr("settings.category.$key.summary")
    }

    private data class WebDisplayDraft(val width: String, val height: String, val error: Boolean = false)

    @Composable
    private fun HostOverlay() {
        Box(Modifier.fillMaxSize()) {
            when {
                safeAreaEditorActive -> SafeAreaEditorChrome()
                menuOpen -> SettingsScreen()
                !mainStreamActive -> HomeScreen(
                    timeline = handshakeTimeline,
                    revision = timelineRevision,
                    status = connectionStatus,
                    wireless = handshakeTimeline?.wireless ?: wirelessEnabled,
                    debugLogs = debugLogsEnabled,
                    logLines = screenLogs,
                    onOpenSettings = { openSettingsMenu() },
                )
                debugLogsEnabled -> StreamingLogOverlay(screenLogs, Modifier.align(Alignment.BottomStart))
            }
            if (hostAppLinksOverlayVisible(
                    debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    ultraEnabled = carPlayUltraEnabled,
                    streamActive = mainStreamActive,
                    menuOpen = menuOpen,
                    safeAreaEditorActive = safeAreaEditorActive,
                )
            ) {
                AppLinksStreamingOverlay(Modifier.align(Alignment.TopEnd))
            }
            if (hostUiRequested && !menuOpen && !safeAreaEditorActive) {
                HostUiRequestOverlay(Modifier.align(Alignment.TopCenter))
            }
        }
    }

    @Composable
    private fun AppLinksStreamingOverlay(modifier: Modifier = Modifier) {
        Column(
            modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Space.s2)
                .widthIn(max = 380.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FilledTonalButton(onClick = { appLinksPanelOpen = !appLinksPanelOpen }) {
                Text(if (appLinksPanelOpen) "Close AppLinks" else "AppLinks · DEBUG")
            }
            if (!appLinksPanelOpen) return@Column
            M3Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp),
                shape = RoundedCornerShape(Radius.lg),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(Space.s3),
                    verticalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    Text("Experimental AppLinks metadata", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Explicit subscription and one manual icon request per control session only. " +
                            "64px advertises a preferred size; it does not fetch images by itself. " +
                            "No app launch. Icons are verified in memory and cleared on disconnect. " +
                            "Updates may lag after Stop/Start; not an authoritative app inventory.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        when {
                            appLinksSnapshot.stopUncertain ->
                                "AppLinks Stop outcome unknown; reconnect CarPlay control"
                            !appLinksSnapshot.available -> "Authenticated iAP2 control is not ready"
                            appLinksSnapshot.busy -> "Sending command…"
                            appLinksSnapshot.subscribed -> "Subscribed to selected categories"
                            else -> "Ready; no subscription active"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowButtons {
                        Iap2AppLinksCategory.entries.forEach { category ->
                            val selected = category.id in appLinksSelectedCategories
                            OutlinedButton(
                                onClick = {
                                    appLinksSelectedCategories = if (selected) {
                                        appLinksSelectedCategories - category.id
                                    } else {
                                        appLinksSelectedCategories + category.id
                                    }
                                },
                                enabled = !appLinksSnapshot.busy && !appLinksSnapshot.subscribed,
                            ) {
                                Text("${if (selected) "✓ " else ""}${category.name.replace('_', ' ')}")
                            }
                        }
                    }
                    Text("Preferred icon size for next Start", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        HostAppLinksPreferredIconSize.entries.forEach { choice ->
                            OutlinedButton(
                                onClick = { appLinksPreferredIconSize = choice },
                                enabled = appLinksSnapshot.available && !appLinksSnapshot.busy &&
                                    !appLinksSnapshot.subscribed,
                            ) {
                                val label = if (choice == HostAppLinksPreferredIconSize.NONE) "None" else "64px"
                                Text("${if (choice == appLinksPreferredIconSize) "✓ " else ""}$label")
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        Button(
                            onClick = {
                                controller?.startAppLinks(
                                    appLinksSelectedCategories,
                                    appLinksPreferredIconSize.pixels,
                                    appLinksSnapshot.ownerRevision,
                                )
                            },
                            enabled = appLinksSnapshot.available && !appLinksSnapshot.busy &&
                                !appLinksSnapshot.subscribed && appLinksSelectedCategories.isNotEmpty(),
                        ) { Text("Start") }
                        OutlinedButton(
                            onClick = {
                                appLinksIconDecodeGate.clear()
                                decodedAppLinksIcon = null
                                appLinksIconDecodeFailed = false
                                appLinksPreferredIconSize = HostAppLinksPreferredIconSize.NONE
                                controller?.stopAppLinks()
                            },
                            enabled = appLinksSnapshot.available && !appLinksSnapshot.busy &&
                                appLinksSnapshot.subscribed,
                        ) { Text("Stop") }
                    }
                    appLinksSnapshot.error?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                    appLinksSnapshot.latestUpdate?.let { update ->
                        Text(
                            "Latest update · state ${update.state} · " +
                                "${update.entries.size} entries" +
                                (update.reportedCount?.let { " · reported $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        update.entries.take(8).forEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                                Text(
                                    hostAppLinksEntrySummary(entry),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (entry.iconHash != null) {
                                    OutlinedButton(
                                        onClick = {
                                            controller?.requestAppLinksIcon(
                                                entry.index,
                                                appLinksSnapshot.ownerRevision,
                                            )
                                        },
                                        enabled = hostCanRequestAppLinksIcon(appLinksSnapshot, entry),
                                    ) { Text("Request icon") }
                                }
                            }
                        }
                        if (update.entries.size > 8) Text("…and ${update.entries.size - 8} more")
                    }
                    if (appLinksSnapshot.iconRequestAttempted) {
                        Text(
                            when (appLinksSnapshot.iconState) {
                                ControllerAppLinksIconState.IDLE -> "Icon request retired"
                                ControllerAppLinksIconState.QUEUED -> "Queuing icon request…"
                                ControllerAppLinksIconState.WAITING_FOR_RESPONSE -> "Waiting for icon response…"
                                ControllerAppLinksIconState.RECEIVING -> "Receiving verified icon…"
                                ControllerAppLinksIconState.AVAILABLE -> "Verified icon received"
                                ControllerAppLinksIconState.ABSENT -> "Phone reported no icon"
                                ControllerAppLinksIconState.UNAVAILABLE -> "Icon request unavailable; reconnect to retry"
                                ControllerAppLinksIconState.UNCERTAIN -> "Icon request outcome unknown; reconnect to retry"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    appLinksSnapshot.icon?.let { verifiedIcon ->
                        val completed = decodedAppLinksIcon?.takeIf {
                            it.ownerRevision == appLinksSnapshot.ownerRevision &&
                                it.source === verifiedIcon
                        }
                        if (completed != null) {
                            Image(
                                bitmap = completed.bitmap,
                                contentDescription = "Verified AppLinks icon",
                                modifier = Modifier.size(64.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else if (appLinksIconDecodeFailed) {
                            Text("Verified icon could not be decoded", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Decoding verified icon…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HostUiRequestOverlay(modifier: Modifier = Modifier) {
        M3Surface(
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Space.s4)
                .fillMaxWidth(),
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Text(
                    tr("host.ui_requested"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowButtons {
                    TextButton(onClick = { hostUiRequested = false }) {
                        Text(tr("host.ui_requested.dismiss"))
                    }
                    FilledTonalButton(
                        onClick = {
                            hostUiRequested = false
                            openSettingsMenu()
                        },
                    ) {
                        Text(tr("host.ui_requested.open_settings"))
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val scheme = MaterialTheme.colorScheme
        M3Surface(Modifier.fillMaxSize(), color = scheme.surface, contentColor = scheme.onSurface) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
                SideEffect { settingsTwoPane = twoPane }
                if (twoPane) {
                    val selected = settingsCategory ?: SettingsCategory.CONNECTION
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(CATEGORY_PANE_WIDTH).fillMaxHeight()) {
                            SmallTopBar(
                                title = tr("settings.title"),
                                navigation = {
                                    LvIconButton(R.drawable.ic_ms_close, tr("settings.discard"), { cancelSettingsEdits() })
                                },
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = Space.s3),
                                verticalArrangement = Arrangement.spacedBy(Space.s1),
                            ) {
                                SettingsCategory.entries.forEach { category ->
                                    ListRow(
                                        headline = category.title,
                                        support = category.summary,
                                        leadingIcon = category.icon,
                                        selected = category == selected,
                                        onClick = { settingsCategory = category },
                                    )
                                }
                            }
                            SettingsActions(stacked = true)
                        }
                        M3Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = Space.s3, end = Space.s3, bottom = Space.s3),
                            shape = RoundedCornerShape(Radius.xl),
                            color = scheme.surfaceContainerLow,
                        ) {
                            ProvideGroupColor(scheme.surfaceContainerHigh) {
                                Column(Modifier.fillMaxSize()) {
                                    SmallTopBar(title = selected.title)
                                    CategoryContent(selected, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    val current = settingsCategory
                    Column(Modifier.fillMaxSize()) {
                        if (current == null) {
                            LargeTopBar(
                                title = tr("settings.title"),
                                navigation = {
                                    LvIconButton(R.drawable.ic_ms_close, tr("settings.discard"), { cancelSettingsEdits() })
                                },
                            )
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                SettingsCategory.entries.forEach { category ->
                                    ListRow(
                                        headline = category.title,
                                        support = category.summary,
                                        leadingIcon = category.icon,
                                        leadingBox = true,
                                        onClick = { settingsCategory = category },
                                        trailing = { TrailingValue(null) },
                                    )
                                }
                                Spacer(Modifier.height(Space.s4))
                            }
                        } else {
                            SmallTopBar(
                                title = current.title,
                                navigation = {
                                    LvIconButton(R.drawable.ic_ms_arrow_back, tr("common.back"), { settingsCategory = null })
                                },
                            )
                            CategoryContent(current, Modifier.weight(1f))
                        }
                        SettingsActions(stacked = false)
                    }
                }
            }
        }
    }

    /** Save is the one filled action per screen; leaving the app is a separate, quieter action. */
    @Composable
    private fun SettingsActions(stacked: Boolean) {
        val scheme = MaterialTheme.colorScheme
        val save: @Composable (Modifier) -> Unit = { modifier ->
            Button(
                onClick = { saveSettingsAndReconnect() },
                modifier = modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
            ) {
                LvIcon(R.drawable.ic_ms_refresh, size = 18.dp)
                Spacer(Modifier.width(Space.s2))
                Text(tr("settings.save"), style = MaterialTheme.typography.labelLarge)
            }
        }
        val exit: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(
                onClick = { exitApplication() },
                modifier = modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
            ) {
                LvIcon(R.drawable.ic_ms_power_settings_new, size = 18.dp)
                Spacer(Modifier.width(Space.s2))
                Text(tr("settings.exit"), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                save(Modifier.fillMaxWidth())
                exit(Modifier.fillMaxWidth())
            }
        } else {
            M3Surface(color = scheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    exit(Modifier)
                    Spacer(Modifier.weight(1f))
                    save(Modifier)
                }
            }
        }
    }

    @Composable
    private fun CategoryContent(category: SettingsCategory, modifier: Modifier) {
        key(category) {
            Column(
                modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Space.s6),
            ) {
                when (category) {
                    SettingsCategory.CONNECTION -> ConnectionSettings()
                    SettingsCategory.VEHICLE -> VehicleSettings()
                    SettingsCategory.AUDIO -> AudioSettings()
                    SettingsCategory.DISPLAY -> DisplaySettings()
                    SettingsCategory.WEB -> WebDisplaySettings()
                    SettingsCategory.IDENTITY -> IdentitySettings()
                    SettingsCategory.WINDOW -> WindowSettings()
                    SettingsCategory.DIAGNOSTICS -> DiagnosticsSettings()
                }
            }
        }
    }

    @Composable
    private fun ConnectionSettings() {
        SectionHeader(tr("settings.connection.mfi"))
        SettingsGroup {
            row {
                SelectRow(
                    title = tr("settings.mfi.target"),
                    options = listOf(
                        MfiTarget.USB_CH341 to tr("settings.mfi.target.ch341"),
                        MfiTarget.I2C to tr("settings.mfi.target.i2c"),
                        MfiTarget.REMOTE to tr("settings.mfi.target.remote"),
                        MfiTarget.LOCAL_FILES to tr("settings.mfi.target.local"),
                    ),
                    selected = mfiTarget,
                    onSelected = ::onMfiTargetSelected,
                )
            }
            when (mfiTarget) {
                MfiTarget.USB_CH341 -> row { HelpText(tr("settings.mfi.ch341_help")) }
                MfiTarget.I2C -> row {
                    FieldRow(
                        label = tr("settings.mfi.i2c_path"),
                        value = mfiI2cPath,
                        onValueChange = {
                            mfiI2cPath = it
                            mfiError = null
                        },
                        help = tr("settings.mfi.i2c_help"),
                        monospace = true,
                    )
                }
                MfiTarget.REMOTE -> {
                    row {
                        FieldRow(
                            label = tr("settings.mfi.server"),
                            value = remoteMfiServer,
                            onValueChange = {
                                remoteMfiServer = it
                                mfiError = null
                            },
                            monospace = true,
                        )
                    }
                    row {
                        FieldRow(
                            label = tr("settings.mfi.token"),
                            value = remoteMfiToken,
                            onValueChange = { remoteMfiToken = it },
                            password = true,
                            help = tr("settings.mfi.remote_help"),
                        )
                    }
                }
                MfiTarget.LOCAL_FILES -> {
                    row {
                        DocumentRow(tr("settings.mfi.certificate"), localMfiCertificateUri) {
                            externalActivityInProgress = true
                            localMfiCertificatePicker.launch(arrayOf("*/*"))
                        }
                    }
                    row {
                        DocumentRow(tr("settings.mfi.private_key"), localMfiPrivateKeyUri) {
                            externalActivityInProgress = true
                            localMfiPrivateKeyPicker.launch(arrayOf("*/*"))
                        }
                    }
                    row { HelpText(tr("settings.mfi.local_help")) }
                }
            }
        }
        mfiError?.let {
            Spacer(Modifier.height(Space.s3))
            Banner(BannerKind.ERR, tr("settings.mfi.error_title"), body = it)
        }

        SectionHeader(tr("settings.connection.transport"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.wireless"),
                    support = tr("settings.wireless.support"),
                    checked = wirelessEnabled,
                    onCheckedChange = ::onWirelessChanged,
                )
            }
            row {
                SelectRow(
                    title = tr("settings.wifi_session"),
                    options = buildList {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(WirelessHotspotMode.WIFI_P2P to tr("settings.wifi_session.p2p"))
                        }
                        add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to tr("settings.wifi_session.local_only"))
                        add(WirelessHotspotMode.MANUAL to tr("settings.wifi_session.manual"))
                    },
                    selected = wirelessHotspotMode,
                    onSelected = ::onHotspotModeSelected,
                )
            }
        }

        if (wirelessHotspotMode == WirelessHotspotMode.MANUAL) {
            SectionHeader(tr("settings.manual_hotspot"))
            SettingsGroup {
                row {
                    FieldRow(
                        label = tr("settings.manual_hotspot.ssid"),
                        value = manualHotspotSsid,
                        onValueChange = {
                            manualHotspotSsid = it
                            manualHotspotError = null
                        },
                    )
                }
                row {
                    SegmentedRow(
                        title = tr("settings.manual_hotspot.band"),
                        options = listOf(
                            ManualHotspotBand.AUTO to tr("settings.manual_hotspot.band.auto"),
                            ManualHotspotBand.GHZ_2_4 to "2.4 GHz",
                            ManualHotspotBand.GHZ_5 to "5 GHz",
                        ),
                        selected = manualHotspotBand,
                        onSelected = {
                            manualHotspotBand = it
                            manualHotspotError = null
                        },
                    )
                }
                row {
                    FieldRow(
                        label = tr("settings.manual_hotspot.channel"),
                        value = manualHotspotChannelText,
                        onValueChange = { value ->
                            manualHotspotChannelText = value
                            manualHotspotChannel = value.toIntOrNull() ?: -1
                            manualHotspotError = null
                        },
                        numeric = true,
                        help = tr("settings.manual_hotspot.channel.help"),
                    )
                }
                row {
                    SelectRow(
                        title = tr("settings.manual_hotspot.security"),
                        options = listOf(
                            ManualHotspotSecurity.OPEN to tr("settings.manual_hotspot.security.open"),
                            ManualHotspotSecurity.WPA2 to "WPA2",
                            ManualHotspotSecurity.WPA3_TRANSITION to tr("settings.manual_hotspot.security.wpa3_transition"),
                            ManualHotspotSecurity.WPA3 to "WPA3",
                        ),
                        selected = manualHotspotSecurity,
                        onSelected = {
                            manualHotspotSecurity = it
                            manualHotspotError = null
                        },
                    )
                }
                row {
                    FieldRow(
                        label = tr("settings.manual_hotspot.password"),
                        value = manualHotspotPassphrase,
                        onValueChange = {
                            manualHotspotPassphrase = it
                            manualHotspotError = null
                        },
                        password = true,
                    )
                }
            }
            manualHotspotError?.let {
                Spacer(Modifier.height(Space.s3))
                Banner(BannerKind.ERR, tr("settings.manual_hotspot.error_title"), body = it)
            }
        }

        SectionHeader(tr("settings.hotspot_status"))
        SettingsGroup { row { HotspotStatusBlock() } }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(Space.s4))
            Banner(BannerKind.INFO, tr("settings.compat.title"), body = tr("settings.compat.p2p"))
        }
    }

    @Composable
    private fun HotspotStatusBlock() {
        val status = hotspotStatus
        val (kind, icon) = when {
            !wirelessEnabled -> PillKind.IDLE to R.drawable.ic_ms_radio_button_unchecked
            status.state == "Error" -> PillKind.ERR to R.drawable.ic_ms_error_fill
            status.state in setOf("Ready", "Running", "Active") -> PillKind.OK to R.drawable.ic_ms_check_circle_fill
            status.state in setOf("off", "stopped") -> PillKind.IDLE to R.drawable.ic_ms_radio_button_unchecked
            else -> PillKind.INFO to R.drawable.ic_ms_pending
        }
        val stateKey = if (!wirelessEnabled) "off" else status.state
        val label = trOr("hotspot.state.${stateKey.lowercase(Locale.ROOT).replace(' ', '_')}", stateKey)
        Column(Modifier.fillMaxWidth().padding(top = Space.s3)) {
            StatusPill(kind, icon, label, Modifier.padding(horizontal = Space.s4))
            if (wirelessEnabled) {
                val entries = buildList {
                    status.ssid?.let { add(tr("hotspot.ssid") to it) }
                    status.backend?.let { add(tr("hotspot.backend") to it) }
                    status.band?.let { add(tr("hotspot.band") to it) }
                    status.channel?.let {
                        add(tr("hotspot.channel") to if (it == 0) tr("settings.manual_hotspot.band.auto") else it.toString())
                    }
                }
                if (entries.isNotEmpty()) KeyValueList(entries) else Spacer(Modifier.height(Space.s3))
            } else {
                HelpText(tr("hotspot.off_help"))
            }
        }
    }

    @Composable
    private fun DocumentRow(label: String, uri: String, onChoose: () -> Unit) {
        val name = remember(uri) { localMfiDocumentLabel(uri) }
        ListRow(
            headline = label,
            support = name,
            leadingIcon = R.drawable.ic_ms_description,
            trailing = {
                FilledTonalButton(onClick = onChoose) {
                    Text(tr("common.choose"), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    @Composable
    private fun VehicleSettings() {
        SectionHeader(tr("settings.vehicle.location"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.vehicle.report_location"),
                    support = tr("settings.vehicle.report_location.support"),
                    checked = locationReportingEnabled,
                    onCheckedChange = ::onLocationReportingChanged,
                )
            }
        }

        SectionHeader(tr("settings.vehicle.properties"))
        val platforms = com.shilapi.xcertplay.vehicle.VehiclePlatform.entries
        SettingsGroup {
            row {
                SelectRow(
                    title = tr("settings.vehicle.source"),
                    options = listOf(platforms[0] to tr("settings.vehicle.source.aaos")),
                    selected = vehicleDataConfig.platform,
                    onSelected = { platform ->
                        vehicleDataConfig = vehicleDataConfig.copy(platform = platform)
                        updateVehicleDataStatus()
                    },
                )
            }
            VehicleDataFeature.entries.forEach { feature ->
                row {
                    if (!feature.sourceImplemented) {
                        ListRow(
                            headline = feature.label,
                            support = tr("settings.vehicle.feature_unavailable", "reason" to feature.description),
                            enabled = false,
                        )
                    } else {
                        SwitchRow(
                            title = feature.label,
                            support = feature.description,
                            checked = vehicleDataConfig.isEnabled(feature),
                            onCheckedChange = { onVehicleFeatureChanged(feature, it) },
                        )
                    }
                }
            }
        }
        HelpText(tr("settings.vehicle.applies_on_close"))

        SectionHeader(tr("settings.vehicle.diagnostics"))
        SettingsGroup {
            row {
                Text(
                    vehicleDataStatusText,
                    style = LavenderCode.medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(Space.s4),
                )
            }
            row {
                FlowButtons {
                    FilledTonalButton(onClick = { updateVehicleDataStatus() }) {
                        LvIcon(R.drawable.ic_ms_refresh, size = 18.dp)
                        Spacer(Modifier.width(Space.s2))
                        Text(tr("settings.vehicle.refresh"), style = MaterialTheme.typography.labelLarge)
                    }
                    if (canRequestVehicleSpeedPermission()) {
                        OutlinedButton(onClick = { requestVehicleSpeedPermission() }) {
                            LvIcon(R.drawable.ic_ms_speed, size = 18.dp)
                            Spacer(Modifier.width(Space.s2))
                            Text(tr("settings.vehicle.grant_speed"), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        HelpText(tr("settings.vehicle.no_adapter"))
        HelpText(tr("settings.vehicle.requirements"))
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FlowButtons(content: @Composable () -> Unit) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) { content() }
    }

    @Composable
    private fun AudioSettings() {
        SectionHeader(tr("settings.audio.playback"))
        val bufferValues = remember {
            (MainMediaAudioBuffer.MIN_DURATION_MS..MainMediaAudioBuffer.MAX_DURATION_MS step
                MainMediaAudioBuffer.STEP_DURATION_MS).toList()
        }
        SettingsGroup {
            row {
                SliderRow(
                    title = tr("settings.audio.buffer"),
                    support = tr("settings.audio.buffer.support"),
                    valueLabel = mainMediaAudioBufferLabel(mainMediaAudioBufferDurationMs),
                    index = bufferValues.indexOf(mainMediaAudioBufferDurationMs).coerceAtLeast(0),
                    count = bufferValues.size,
                    onIndexChange = { mainMediaAudioBufferDurationMs = bufferValues[it] },
                )
            }
            if (advancedAudioChannelMappingSupported) {
                row {
                    SwitchRow(
                        title = tr("settings.audio.channel_mapping"),
                        support = tr("settings.audio.channel_mapping.support"),
                        checked = advancedAudioChannelMapping,
                        onCheckedChange = ::onAdvancedAudioChannelMappingChanged,
                    )
                }
            }
            row {
                SwitchRow(
                    title = tr("settings.audio.focus"),
                    support = tr("settings.audio.focus.support"),
                    checked = audioFocusEnabled,
                    onCheckedChange = ::onAudioFocusEnabledChanged,
                )
            }
        }

        SectionHeader(tr("settings.audio.microphone"))
        SettingsGroup {
            row {
                SliderRow(
                    title = tr("settings.audio.mic_gain"),
                    support = tr("settings.audio.mic_gain.support"),
                    valueLabel = microphoneGainLabel(microphoneGainPercent),
                    index = MicrophoneGain.stepForPercent(microphoneGainPercent),
                    count = MicrophoneGain.STEP_COUNT,
                    onIndexChange = { microphoneGainPercent = MicrophoneGain.percentForStep(it) },
                )
            }
            row { MicrophoneTestRow() }
        }
    }

    @Composable
    private fun MicrophoneTestRow() {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            LvIcon(R.drawable.ic_ms_mic, tint = if (micTestRunning) scheme.primary else scheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Row {
                    Text(tr("settings.audio.peak_level"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(micLevelText, style = MaterialTheme.typography.labelMedium.tabular, color = scheme.onSurfaceVariant)
                }
                LinearMeter(micPeakPercent / 100f)
            }
            FilledTonalButton(
                onClick = { if (microphoneLevelMonitor?.isRunning == true) stopMicrophoneGainTest() else startMicrophoneGainTest() },
                enabled = menuOpen && !handshakeResetInProgress,
            ) {
                Text(
                    if (micTestRunning) tr("settings.audio.stop") else tr("settings.audio.test"),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    @Composable
    private fun DisplaySettings() {
        SectionHeader(tr("settings.display.resolution"))
        val fpsValues = remember {
            (AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS step AirPlayDisplaySettings.FPS_STEP).toList()
        }
        val lengthValues = remember {
            (AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM step
                AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP).toList()
        }
        SettingsGroup {
            row {
                SliderRow(
                    title = tr("settings.display.scale"),
                    support = tr("settings.display.scale.support"),
                    valueLabel = CarPlayDisplayScale.label(displayScaleTenths),
                    index = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS,
                    count = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS + 1,
                    onIndexChange = {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(CarPlayDisplayScale.MIN_TENTHS + it)
                        updateResolutionMenu()
                    },
                    startLabel = CarPlayDisplayScale.label(CarPlayDisplayScale.MIN_TENTHS),
                    endLabel = CarPlayDisplayScale.label(CarPlayDisplayScale.MAX_TENTHS),
                )
            }
            row {
                SliderRow(
                    title = tr("settings.display.fps"),
                    valueLabel = tr("unit.fps", "value" to fps),
                    index = fpsValues.indexOf(fps).coerceAtLeast(0),
                    count = fpsValues.size,
                    onIndexChange = {
                        fps = fpsValues[it]
                        updateResolutionMenu()
                    },
                )
            }
            row {
                SegmentedRow(
                    title = tr("settings.display.physical_basis"),
                    options = listOf(
                        AirPlayPhysicalSizeBasis.SHORTEST_EDGE to
                            tr("settings.display.physical_basis.short_edge"),
                        AirPlayPhysicalSizeBasis.LONGEST_EDGE to
                            tr("settings.display.physical_basis.long_edge"),
                        AirPlayPhysicalSizeBasis.WIDTH to tr("settings.display.physical_basis.width"),
                        AirPlayPhysicalSizeBasis.HEIGHT to tr("settings.display.physical_basis.height"),
                    ),
                    selected = physicalSizeBasis,
                    onSelected = {
                        physicalSizeBasis = it
                        updateResolutionMenu()
                    },
                )
            }
            row {
                SliderRow(
                    title = tr("settings.display.physical_length"),
                    valueLabel = tr("unit.mm", "value" to widthPhysicalMm),
                    index = lengthValues.indexOf(widthPhysicalMm).coerceAtLeast(0),
                    count = lengthValues.size,
                    onIndexChange = {
                        widthPhysicalMm = lengthValues[it]
                        updateResolutionMenu()
                    },
                )
            }
        }

        SectionHeader(tr("settings.display.video"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.display.hevc"),
                    support = tr("settings.display.hevc.support"),
                    checked = hevcEnabled,
                    onCheckedChange = ::onHevcChanged,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                row {
                    SwitchRow(
                        title = tr("settings.display.hevc_software"),
                        support = tr("settings.display.hevc_software.support"),
                        checked = hevcSoftwareDecoderEnabled,
                        enabled = hevcEnabled,
                        onCheckedChange = ::onHevcSoftwareDecoderChanged,
                    )
                }
            }
        }

        SectionHeader(tr("settings.display.safe_area"))
        SettingsGroup {
            row {
                ListRow(
                    headline = tr("settings.display.safe_area.current"),
                    support = safeAreaSummaryText,
                    leadingIcon = R.drawable.ic_ms_crop_free,
                )
            }
            row {
                FlowButtons {
                    FilledTonalButton(onClick = { openSafeAreaEditor() }) {
                        Text(tr("settings.display.safe_area.edit"), style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { resetSafeAreaForCurrentSize() }) {
                        LvIcon(R.drawable.ic_ms_restart_alt, size = 18.dp)
                        Spacer(Modifier.width(Space.s2))
                        Text(tr("settings.display.safe_area.reset"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            row {
                SwitchRow(
                    title = tr("settings.display.safe_area.draw_outside"),
                    support = tr("settings.display.safe_area.draw_outside.support"),
                    checked = safeAreaDrawOutside,
                    onCheckedChange = {
                        safeAreaDrawOutside = it
                        updateResolutionMenu()
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(Space.s4))
            Banner(BannerKind.INFO, tr("settings.compat.title"), body = tr("settings.compat.hevc"))
        }
    }

    @Composable
    private fun WebDisplaySettings() {
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            val config = webDisplayConfig(type)
            val draft = webDisplayDrafts[type] ?: WebDisplayDraft(config.width.toString(), config.height.toString())
            SectionHeader(if (type == SCREEN_TYPE_MAIN) tr("settings.web.main") else tr("settings.web.instrument"))
            SettingsGroup {
                if (type == SCREEN_TYPE_ALT) {
                    row {
                        SwitchRow(
                            title = tr("settings.web.cluster_display"),
                            support = tr("settings.web.cluster_display.support"),
                            checked = clusterDisplayEnabled,
                            onCheckedChange = ::updateClusterDisplayEnabled,
                        )
                    }
                }
                val displayDeclared =
                    type == SCREEN_TYPE_MAIN && config.enabled ||
                        type == SCREEN_TYPE_ALT && clusterDisplayEnabled
                if (displayDeclared) {
                    row {
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                FieldRow(
                                    label = tr("settings.web.width"),
                                    value = draft.width,
                                    onValueChange = { webDisplayDrafts[type] = draft.copy(width = it, error = false) },
                                    numeric = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FieldRow(
                                    label = tr("settings.web.height"),
                                    value = draft.height,
                                    onValueChange = { webDisplayDrafts[type] = draft.copy(height = it, error = false) },
                                    numeric = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                if (draft.error) tr("settings.web.dimension_error") else tr("settings.web.dimension_help"),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (draft.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = Space.s8, end = Space.s4, bottom = Space.s3),
                            )
                        }
                    }
                    row {
                        SwitchRow(
                            title = tr("settings.web.preview"),
                            support = tr(
                                if (type == SCREEN_TYPE_ALT && clusterDisplayEnabled && !config.enabled) {
                                    "settings.web.instrument_local_preview.required"
                                } else if (type == SCREEN_TYPE_ALT) {
                                    "settings.web.instrument_local_preview.support"
                                } else {
                                    "settings.web.preview.support"
                                },
                            ),
                            checked = config.preview,
                            enabled = type != SCREEN_TYPE_ALT || !clusterDisplayEnabled || config.enabled,
                            onCheckedChange = { setWebDisplayConfig(type, webDisplayConfig(type).copy(preview = it)) },
                        )
                    }
                }
                if (type == SCREEN_TYPE_ALT) {
                    row {
                        SwitchRow(
                            title = tr("settings.web.ultra"),
                            support = when {
                                DebugBenchStageLauncher.isAvailable ->
                                    "DEBUG: a user-signed test Profile may enable CAF v1 reads or " +
                                        "v2 synthetic mutations; static UI Sync additionally needs " +
                                        "its own opt-in and live DDP physical output. Not full Ultra."
                                else -> tr("settings.web.ultra.provisioning_unavailable")
                            },
                            checked = carPlayUltraEnabled,
                            onCheckedChange = {
                                ultraSaveBlocked = false
                                carPlayUltraEnabled = it
                                if (!it) {
                                    physicalDisplayPanelsProbeEnabled = false
                                    catalogProbeSettings = catalogProbeSettings.copy(enabled = false)
                                    catalogProbeConfigError = false
                                }
                            },
                        )
                    }
                    if (ultraSaveBlocked) {
                        row {
                            Banner(BannerKind.ERR,
                                "Ultra setting not saved: old physical output cover is pending. " +
                                    "Change the switch again and retry Save after cover.")
                        }
                    }
                    if (carPlayUltraEnabled) {
                        if (DebugBenchStageLauncher.isAvailable) {
                            row {
                                Column(Modifier.fillMaxWidth().padding(Space.s4)) {
                                    FilledTonalButton(onClick = {
                                        DebugBenchStageLauncher.launch(this@CarPlayHostActivity)
                                    }) {
                                        Text("DEBUG Profile bench")
                                    }
                                    HelpText(
                                        "User-pinned local test only, not Apple/OEM authorized. " +
                                            "Opt-in changes take effect on the next Controller start; " +
                                            "synthetic values are not vehicle data.",
                                    )
                                }
                            }
                        }
                        row {
                            SwitchRow(
                                title = tr("settings.web.ultra.physical_panels_probe"),
                                support = tr("settings.web.ultra.physical_panels_probe.support"),
                                checked = physicalDisplayPanelsProbeEnabled,
                                onCheckedChange = { physicalDisplayPanelsProbeEnabled = it },
                            )
                        }
                        row { HelpText(tr("settings.web.ultra.session_management")) }
                        row {
                            SwitchRow(
                                title = tr("settings.web.ultra.catalog_probe_label"),
                                support = tr("settings.web.ultra.catalog_probe_description"),
                                checked = catalogProbeSettings.enabled,
                                onCheckedChange = { enabled ->
                                    catalogProbeSettings = catalogProbeSettings.copy(enabled = enabled)
                                    catalogProbeConfigError = false
                                },
                            )
                        }
                        if (catalogProbeSettings.enabled) {
                            row {
                                FieldRow(
                                    label = tr("settings.web.ultra.catalog_probe_asset_id"),
                                    value = catalogProbeSettings.assetId,
                                    onValueChange = {
                                        catalogProbeSettings = catalogProbeSettings.copy(assetId = it)
                                        catalogProbeConfigError = false
                                    },
                                    monospace = true,
                                )
                            }
                            row {
                                FieldRow(
                                    label = tr("settings.web.ultra.catalog_probe_sdk_version"),
                                    value = catalogProbeSettings.sdkVersion,
                                    onValueChange = {
                                        catalogProbeSettings = catalogProbeSettings.copy(sdkVersion = it)
                                        catalogProbeConfigError = false
                                    },
                                    monospace = true,
                                )
                            }
                            row {
                                SelectRow(
                                    title = tr("settings.web.ultra.catalog_probe_mfi_certificate_type"),
                                    options = listOf("MFI" to "MFI", "BAA" to "BAA"),
                                    selected = catalogProbeSettings.certificateType,
                                    onSelected = {
                                        catalogProbeSettings = catalogProbeSettings.copy(certificateType = it)
                                        catalogProbeConfigError = false
                                    },
                                )
                            }
                            row {
                                FieldRow(
                                    label = tr("settings.web.ultra.catalog_probe_sha256_pin"),
                                    value = catalogProbeSettings.identitySha256Hex,
                                    onValueChange = {
                                        catalogProbeSettings = catalogProbeSettings.copy(identitySha256Hex = it)
                                        catalogProbeConfigError = false
                                    },
                                    password = true,
                                    monospace = true,
                                )
                            }
                            if (catalogProbeConfigError) {
                                row {
                                    Banner(
                                        BannerKind.ERR,
                                        tr("settings.web.ultra.catalog_probe_invalid_config"),
                                    )
                                }
                            }
                        }
                    }
                    row { HelpText(tr("settings.web.ultra.vsp_unavailable")) }
                    row { HelpText(tr("settings.web.ultra.transfer_unavailable")) }
                }
                row {
                    SwitchRow(
                        title = tr("settings.web.enabled"),
                        support = tr(
                            if (type == SCREEN_TYPE_ALT) {
                                "settings.web.instrument_browser.support"
                            } else {
                                "settings.web.enabled.support"
                            },
                        ),
                        checked = config.enabled,
                        onCheckedChange = {
                            setWebDisplayConfig(type, webDisplayConfig(type).copy(enabled = it))
                        },
                    )
                }
                if (config.enabled) {
                    row {
                        Column(Modifier.fillMaxWidth().padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                            Text(
                                tr("settings.web.address"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    webDisplayAddresses[type].orEmpty().ifEmpty { tr("settings.web.no_network") },
                                    style = LavenderCode.large,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
        HelpText(tr("settings.web.https_note"), Modifier.padding(top = Space.s2))
        HelpText(tr("settings.web.apply_note"))
    }

    @Composable
    private fun IdentitySettings() {
        SectionHeader(tr("settings.identity.vehicle"))
        SettingsGroup {
            row {
                FieldRow(tr("settings.identity.manufacturer"), manufacturer, {
                    manufacturer = it
                    updateResolutionMenu()
                })
            }
            row {
                FieldRow(tr("settings.identity.model"), model, {
                    model = it
                    updateResolutionMenu()
                })
            }
            row {
                FieldRow(tr("settings.identity.oem_label"), oemLabel, {
                    oemLabel = it
                    updateResolutionMenu()
                })
            }
        }

        SectionHeader(tr("settings.identity.appearance"))
        SettingsGroup {
            row { AirPlayIconRow() }
            row {
                SegmentedRow(
                    title = tr("settings.identity.driving_side"),
                    options = listOf(false to tr("settings.identity.driving_side.left"), true to tr("settings.identity.driving_side.right")),
                    selected = rightHandDrive,
                    onSelected = {
                        rightHandDrive = it
                        updateResolutionMenu()
                    },
                )
            }
        }
    }

    @Composable
    private fun AirPlayIconRow() {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(Radius.md)).background(scheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val icon = airPlayIcon
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = tr("settings.identity.icon"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LvIcon(R.drawable.ic_ms_image, tint = scheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(tr("settings.identity.icon"), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Text(
                    if (airPlayIconCustom) tr("settings.identity.icon.custom") else tr("settings.identity.icon.default"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FilledTonalButton(onClick = {
                        externalActivityInProgress = true
                        imagePicker.launch("image/*")
                    }) { Text(tr("settings.identity.icon.choose"), style = MaterialTheme.typography.labelLarge) }
                    TextButton(
                        onClick = {
                            AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                            updateAirPlayIconPreview()
                        },
                        enabled = airPlayIconCustom,
                    ) { Text(tr("settings.identity.icon.reset"), style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }

    @Composable
    private fun WindowSettings() {
        SectionHeader(tr("settings.window.startup"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.window.auto_start"),
                    support = tr("settings.window.auto_start.support"),
                    checked = autoStartOnBoot,
                    onCheckedChange = {
                        autoStartOnBoot = it
                        appendLog("Boot auto-start ${if (it) "enabled" else "disabled"}")
                    },
                )
            }
        }
        SectionHeader(tr("settings.window.fullscreen"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.window.hide_top"),
                    support = tr("settings.window.hide_top.support"),
                    checked = hideTopBar,
                    onCheckedChange = {
                        hideTopBar = it
                        applyFullscreenMode()
                        refreshDisplaySizeAfterLayout()
                    },
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.window.hide_bottom"),
                    support = tr("settings.window.hide_bottom.support"),
                    checked = hideBottomBar,
                    onCheckedChange = {
                        hideBottomBar = it
                        applyFullscreenMode()
                        refreshDisplaySizeAfterLayout()
                    },
                )
            }
        }
        SectionHeader(tr("settings.window.gestures"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.window.edge_gesture"),
                    support = tr("settings.window.edge_gesture.support"),
                    checked = moreGesturesToSettings,
                    onCheckedChange = { moreGesturesToSettings = it },
                )
            }
        }
        HelpText(tr("settings.window.three_finger_help"))
    }

    @Composable
    private fun DiagnosticsSettings() {
        SectionHeader(tr("settings.diagnostics.monitoring"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.latency"),
                    support = tr("settings.diagnostics.latency.support"),
                    checked = mediaMetricsEnabled,
                    onCheckedChange = ::onMediaMetricsChanged,
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.debug_logs"),
                    support = tr("settings.diagnostics.debug_logs.support"),
                    checked = debugLogsEnabled,
                    onCheckedChange = ::onDebugLogsChanged,
                )
            }
        }
        SectionHeader(tr("settings.diagnostics.capture"))
        SettingsGroup {
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.audio_capture"),
                    support = tr("settings.diagnostics.audio_capture.support"),
                    checked = audioPacketCaptureEnabled,
                    onCheckedChange = ::onAudioPacketCaptureChanged,
                )
            }
            row {
                SwitchRow(
                    title = tr("settings.diagnostics.raw_video"),
                    support = tr("settings.diagnostics.raw_video.support"),
                    checked = videoFrameLogsEnabled,
                    onCheckedChange = ::onVideoFrameLogsChanged,
                )
            }
        }
        SectionHeader(tr("settings.diagnostics.system"))
        SettingsGroup {
            row {
                ListRow(
                    headline = tr("settings.diagnostics.bluetooth"),
                    support = tr("settings.diagnostics.bluetooth.support"),
                    leadingIcon = R.drawable.ic_ms_bluetooth,
                    onClick = { openSystemBluetoothSettings() },
                    trailing = { LvIcon(R.drawable.ic_ms_open_in_new, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
        SectionHeader(tr("settings.diagnostics.summary"))
        SettingsGroup { row { KeyValueList(configSummary) } }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(Space.s4))
            Banner(
                BannerKind.INFO,
                tr("settings.compat.title"),
                body = tr("settings.compat.p2p") + "\n" + tr("settings.compat.hevc"),
            )
        }
    }

    /** Title and Save/Cancel over the safe-area editor; the editor view below takes the drags. */
    @Composable
    private fun SafeAreaEditorChrome() {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s4)) {
            M3Surface(
                modifier = Modifier.align(Alignment.TopStart),
                shape = RoundedCornerShape(Radius.lg),
                color = scheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = Space.s4, vertical = Space.s3)) {
                    Text(tr("safe_area.editor.title"), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    Text(tr("safe_area.editor.help"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            M3Surface(
                modifier = Modifier.align(Alignment.BottomCenter),
                shape = RoundedCornerShape(Radius.xl),
                color = scheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(Space.s3),
                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    TextButton(onClick = { closeSafeAreaEditor() }) {
                        Text(tr("common.cancel"), style = MaterialTheme.typography.labelLarge)
                    }
                    Button(onClick = { saveSafeAreaEditor() }) {
                        Text(tr("common.save"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    // ----- Settings change handlers (formerly view listeners) --------------------------------

    private fun onWirelessChanged(checked: Boolean) {
        if (wirelessEnabled == checked) return
        wirelessEnabled = checked
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        appendLog("Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; applies when settings close")
        requestStartupPrerequisites()
    }

    private fun onMfiTargetSelected(target: MfiTarget) {
        if (mfiTarget == target) return
        mfiTarget = target
        updateMfiTargetFields()
        appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
    }

    private fun onHotspotModeSelected(mode: WirelessHotspotMode) {
        if (wirelessHotspotMode == mode) return
        wirelessHotspotMode = mode
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
        updateManualHotspotFields()
        appendLog("Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; applies when settings close")
    }

    private fun onVehicleFeatureChanged(feature: VehicleDataFeature, checked: Boolean) {
        vehicleDataConfig = vehicleDataConfig.with(feature, checked)
        if (feature == VehicleDataFeature.WHEEL_SPEED && checked) requestVehicleSpeedPermission()
        updateVehicleDataStatus()
    }

    private fun onAdvancedAudioChannelMappingChanged(checked: Boolean) {
        advancedAudioChannelMapping = checked
        appendLog("Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; applies when settings close")
        updateResolutionMenu()
    }

    private fun onAudioFocusEnabledChanged(checked: Boolean) {
        if (audioFocusEnabled == checked) return
        audioFocusEnabled = checked
        AirPlayPersistence.saveAudioFocusEnabled(this, checked)
        CarPlayBackgroundSession.audioFocus(this).apply {
            setEnabled(checked)
            requestFromHost()
        }
        appendLog("Audio focus ${if (checked) "enabled" else "disabled"}")
    }

    private fun onHevcChanged(checked: Boolean) {
        if (hevcEnabled == checked) return
        hevcEnabled = checked
        appendLog("HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; applies when settings close")
        updateResolutionMenu()
    }

    private fun onHevcSoftwareDecoderChanged(checked: Boolean) {
        if (hevcSoftwareDecoderEnabled == checked) return
        hevcSoftwareDecoderEnabled = checked
        appendLog(
            "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
    }

    private fun onDebugLogsChanged(checked: Boolean) {
        debugLogsEnabled = checked
        if (!checked) clearScreenLogs()
        appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
        updateDebugOverlays()
    }

    private fun onVideoFrameLogsChanged(checked: Boolean) {
        videoFrameLogsEnabled = checked
        sessionLog.videoFramesEnabled = checked
        appendLog("Raw video file logging ${if (checked) "enabled" else "disabled"}")
    }

    private fun onMediaMetricsChanged(checked: Boolean) {
        mediaMetricsEnabled = checked
        appendLog("Media latency monitor ${if (checked) "enabled" else "disabled"}")
        updateMediaMetricsOverlay()
    }

    private fun onAudioPacketCaptureChanged(checked: Boolean) {
        audioPacketCaptureEnabled = checked
        appendLog("Audio diagnostic capture ${if (checked) "enabled" else "disabled"}; applies when settings close")
    }

    private fun resetWebDisplayDrafts() {
        webDisplayDrafts.clear()
        for (type in listOf(SCREEN_TYPE_MAIN, SCREEN_TYPE_ALT)) {
            val config = webDisplayConfig(type)
            webDisplayDrafts[type] = WebDisplayDraft(config.width.toString(), config.height.toString())
        }
        updateWebDisplayAddresses()
    }

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: $backend, $ssid, $band, " +
                "channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val CLUSTER_WINDOW_MAX_WIDTH_FRACTION = 0.45
        const val CLUSTER_WINDOW_MAX_HEIGHT_FRACTION = 0.35
        const val CLUSTER_WINDOW_MIN_WIDTH_DP = 160
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val LOG_RENDER_INTERVAL_MILLIS = 100L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val RECONNECT_MAX_DELAY_MILLIS = 30_000L
        const val RECONNECT_MAX_SHIFT = 4
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val HOST_TEARDOWN_IN_FLIGHT_POLL_MILLIS = 100L
        const val HOST_TEARDOWN_FAILURE_RETRY_MILLIS = 1_000L
        const val HOST_SHUTDOWN_MAX_RETRY_MILLIS = 30_000L
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val TIMELINE_RENDER_INTERVAL_MILLIS = 120L
        val TWO_PANE_MIN_WIDTH = 720.dp
        val CATEGORY_PANE_WIDTH = 320.dp
        val NO_VIDEO_BACKGROUND = Color.BLACK
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal enum class CarPlayDisplayOrientation {
    LANDSCAPE,
    PORTRAIT,
    SQUARE;

    companion object {
        fun from(width: Int, height: Int): CarPlayDisplayOrientation = when {
            width > height -> LANDSCAPE
            width < height -> PORTRAIT
            else -> SQUARE
        }
    }
}

/** Aligns platform maximum-window bounds with the current Activity without inventing an extent. */
internal fun alignMaximumDisplaySize(
    currentWidth: Int,
    currentHeight: Int,
    reportedMaximumWidth: Int,
    reportedMaximumHeight: Int,
): Pair<Int, Int> {
    if (reportedMaximumWidth <= 0 || reportedMaximumHeight <= 0) {
        return currentWidth to currentHeight
    }
    val orientation = CarPlayDisplayOrientation.from(currentWidth, currentHeight)
    val aligned = when {
        CarPlayDisplayOrientation.from(reportedMaximumWidth, reportedMaximumHeight) == orientation ->
            reportedMaximumWidth to reportedMaximumHeight
        CarPlayDisplayOrientation.from(reportedMaximumHeight, reportedMaximumWidth) == orientation ->
            reportedMaximumHeight to reportedMaximumWidth
        else -> currentWidth to currentHeight
    }
    return maxOf(currentWidth, aligned.first) to maxOf(currentHeight, aligned.second)
}

/**
 * Collects the current size and saved safe-area sizes that fit its exact orientation.
 *
 * Index zero is always the current activity size and therefore owns the negotiated stream extent.
 * Smaller same-orientation areas can be selected without reconnecting. A rotation gets a fresh
 * handshake instead of synthesizing a square envelope from unrelated width/height maxima.
 */
class CarPlayViewAreas private constructor(
    private val activitySizes: List<Pair<Int, Int>>,
    val areas: List<AirPlayViewArea>,
) {
    internal val orientation: CarPlayDisplayOrientation =
        CarPlayDisplayOrientation.from(activitySizes.first().first, activitySizes.first().second)

    /** Negotiated display geometry is the scaled current size at index zero. */
    val widthPixels: Int = areas.first().widthPixels
    val heightPixels: Int = areas.first().heightPixels

    /** Current activity geometry is also index zero; saved areas never enlarge this extent. */
    val activityWidthPixels: Int = activitySizes.first().first
    val activityHeightPixels: Int = activitySizes.first().second

    val size: Int get() = areas.size

    fun activitySize(index: Int): Pair<Int, Int> = activitySizes[index]

    var splitScreenRatio: Double? = null
        private set

    fun indexFor(width: Int, height: Int, splitScreen: Boolean = false, portrait: Boolean = height > width): Int? {
        if (splitScreen) {
            val fullWidth = if (portrait) minOf(activityWidthPixels, activityHeightPixels) else activityWidthPixels
            val fullHeight = if (portrait) activityHeightPixels else minOf(activityWidthPixels, activityHeightPixels)
            splitScreenRatio = minOf(width.toDouble() / fullWidth, height.toDouble() / fullHeight)
        }
        // Match only declared dimensions; unknown split-screen sizes require renegotiation.
        return activitySizes.indexOf(width to height).takeIf { it >= 0 }
    }

    /** Whether an activity of this size still fits the direction and extent declared at startup. */
    fun contains(width: Int, height: Int): Boolean =
        CarPlayDisplayOrientation.from(width, height) == orientation &&
            width <= activityWidthPixels && height <= activityHeightPixels

    internal fun toAirPlayDisplayConfig(
        physicalSize: AirPlayPhysicalSizeMm,
        fps: Int,
        safeAreaDrawOutside: Boolean,
    ): AirPlayDisplayConfig = AirPlayDisplayConfig(
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        widthPhysicalMm = physicalSize.widthMm,
        heightPhysicalMm = physicalSize.heightMm,
        fps = fps,
        safeAreaDrawOutside = safeAreaDrawOutside,
        viewAreas = areas,
        initialViewArea = 0,
    )

    override fun toString(): String = activitySizes.mapIndexed { index, (w, h) ->
        val area = areas[index]
        "$index:${w}x$h->${area.widthPixels}x${area.heightPixels}"
    }.joinToString(prefix = "[", postfix = "] display=${widthPixels}x$heightPixels")

    companion object {
        fun build(
            currentWidth: Int,
            currentHeight: Int,
            savedSizes: List<Pair<Int, Int>>,
            scaleTenths: Int,
            safeArea: (width: Int, height: Int) -> SafeAreaRect?,
        ): CarPlayViewAreas {
            require(currentWidth > 0 && currentHeight > 0) { "Activity dimensions must be positive" }
            val current = currentWidth to currentHeight
            val orientation = CarPlayDisplayOrientation.from(currentWidth, currentHeight)
            val sizes = linkedSetOf(current)
            savedSizes.forEach { (w, h) ->
                if (
                    w > 0 && h > 0 &&
                    w <= currentWidth && h <= currentHeight &&
                    CarPlayDisplayOrientation.from(w, h) == orientation
                ) {
                    sizes += w to h
                }
            }
            val declared = sizes.toList()
            val areas = declared.map { (w, h) ->
                val scaled = CarPlayDisplayScale.apply(AirPlayDisplayConfig(w, h), scaleTenths)
                AirPlayViewArea(
                    widthPixels = scaled.widthPixels,
                    heightPixels = scaled.heightPixels,
                    safeArea = AirPlaySafeArea.toInsets(
                        mapping = safeArea(w, h),
                        activityWidthPixels = w,
                        activityHeightPixels = h,
                        displayWidthPixels = scaled.widthPixels,
                        displayHeightPixels = scaled.heightPixels,
                    ),
                )
            }
            return CarPlayViewAreas(declared, areas)
        }
    }
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    val log = SessionLog()
    private var audioFocusSession: AndroidMediaSink.AudioFocusSession? = null

    @Synchronized
    fun audioFocus(context: android.content.Context): AndroidMediaSink.AudioFocusSession =
        audioFocusSession ?: AndroidMediaSink.createAudioFocus(context).also { audioFocusSession = it }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val handshakeTimeline: HandshakeTimeline,
        val activeScreenStreamTypes: MutableSet<Int>,
        val viewAreas: CarPlayViewAreas?,
        val clusterPresentationCoordinator: ClusterPresentationCoordinator?,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var handshakeTimeline: HandshakeTimeline? = null
    private var activeScreenStreamTypes = mutableSetOf<Int>()
    private var viewAreas: CarPlayViewAreas? = null
    private var clusterPresentationCoordinator: ClusterPresentationCoordinator? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val timeline = handshakeTimeline ?: return null
        return Snapshot(
            currentController,
            currentSink,
            width,
            height,
            timeline,
            activeScreenStreamTypes,
            viewAreas,
            clusterPresentationCoordinator,
        )
    }

    @Synchronized
    fun store(
        controller: CarPlayController,
        sink: AndroidMediaSink,
        width: Int,
        height: Int,
        handshakeTimeline: HandshakeTimeline,
        activeScreenStreamTypes: MutableSet<Int>,
        viewAreas: CarPlayViewAreas?,
        clusterPresentationCoordinator: ClusterPresentationCoordinator?,
    ) {
        this.viewAreas = viewAreas
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.handshakeTimeline = handshakeTimeline
        this.activeScreenStreamTypes = activeScreenStreamTypes
        this.clusterPresentationCoordinator = clusterPresentationCoordinator
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
        handshakeTimeline = null
        activeScreenStreamTypes = mutableSetOf()
        viewAreas = null
        clusterPresentationCoordinator = null
    }
}
