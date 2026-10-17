package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPresentationSource
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.MediaCodecSupport
import com.shilapi.xcertplay.media.MediaTap
import com.shilapi.xcertplay.media.VideoSyncGate
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock

/** An enabled web display owns its negotiated size; window changes only resize its preview. */
data class CarPlayWebDisplayConfig(
    val enabled: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val preview: Boolean = true,
) {
    init {
        require(width in 320..3840 && height in 240..2160 && width % 2 == 0 && height % 2 == 0) {
            "Resolution must use even dimensions between 320 x 240 and 3840 x 2160"
        }
    }

    fun override(display: AirPlayDisplayConfig): AirPlayDisplayConfig = if (!enabled) display else display.copy(
        widthPixels = width,
        heightPixels = height,
        heightPhysicalMm = display.widthPhysicalMm?.let { maxOf(1, Math.round(it * height.toDouble() / width).toInt()) },
        viewArea = null,
        safeArea = null,
        viewAreas = emptyList(),
        initialViewArea = 0,
    )
}

/**
 * Retry owner for a server whose start failed before a [CarPlayWebDisplayFactory.Session] existed.
 * The exact server identity and stop action remain strongly held until [close] succeeds.
 */
internal class WebDisplayServerConstructionException private constructor(
    private val serverIdentity: Any,
    startFailure: Throwable,
    private val stopServer: () -> Unit,
) : IllegalStateException(
    "WebDisplay server construction cleanup is pending",
    startFailure.takeUnless { it is Error },
), Closeable {
    internal val startFailure: Throwable = startFailure
    private val closeLock = Any()
    private var closeOwner: Thread? = null
    private var cleanupComplete = false

    override fun close() {
        synchronized(closeLock) {
            if (cleanupComplete) return
            val currentThread = Thread.currentThread()
            check(closeOwner !== currentThread) {
                "WebDisplay construction cleanup is already in flight on this thread"
            }
            check(closeOwner == null) {
                "WebDisplay construction cleanup owner was not released"
            }
            closeOwner = currentThread
            try {
                stopServer()
                cleanupComplete = true
            } finally {
                closeOwner = null
            }
        }
    }

    internal fun ownsServer(candidate: Any): Boolean = candidate === serverIdentity

    internal fun isCleanupComplete(): Boolean = synchronized(closeLock) { cleanupComplete }

    internal companion object {
        fun <T : Any> create(
            server: T,
            startFailure: Throwable,
            stop: (T) -> Unit,
        ) = WebDisplayServerConstructionException(
            serverIdentity = server,
            startFailure = startFailure,
            stopServer = { stop(server) },
        )
    }
}

/** Starts one exact server or proves that it stopped before propagating the start failure. */
internal fun <T : Any> startOwnedWebDisplayServer(
    server: T,
    start: (T) -> Unit,
    stop: (T) -> Unit,
): T {
    try {
        start(server)
        return server
    } catch (startFailure: Throwable) {
        val cleanupOwner = WebDisplayServerConstructionException.create(
            server,
            startFailure,
            stop,
        )
        val cleanupFailure = try {
            cleanupOwner.close()
            null
        } catch (caught: Throwable) {
            caught
        }
        if (cleanupFailure == null) throw startFailure

        when {
            startFailure is Error -> {
                if (cleanupFailure !== startFailure) cleanupOwner.addSuppressed(cleanupFailure)
                startFailure.addSuppressed(cleanupOwner)
                throw startFailure
            }
            cleanupFailure is Error -> {
                cleanupFailure.addSuppressed(cleanupOwner)
                throw cleanupFailure
            }
            else -> {
                if (cleanupFailure !== cleanupOwner) cleanupOwner.addSuppressed(cleanupFailure)
                throw cleanupOwner
            }
        }
    }
}

/** Identity-safe preview state; a stale Activity can only clear the Surface it installed. */
internal class WebDisplayPreviewRoutes {
    private val current = mutableMapOf<Int, Surface>()
    private val generations = mutableMapOf<Int, Long>()

    fun currentSurface(type: Int): Surface? = current[type]
    fun generation(type: Int): Long = generations[type] ?: 0L

    /** Sink restoration already succeeded; never attach over a successor preview. */
    fun rememberRestoredIfSameGeneration(type: Int, surface: Surface, expected: Long): Boolean {
        if (current[type] != null || generation(type) != expected) return false
        current[type] = surface
        advance(type)
        return true
    }

    private fun advance(type: Int) {
        generations[type] = Math.addExact(generation(type), 1L)
    }

    fun replace(
        type: Int,
        candidate: Surface?,
        attach: (Surface) -> Unit,
        detach: (Surface) -> Unit,
    ) {
        val prior = current[type]
        if (prior === candidate) return
        if (prior != null) {
            detach(prior)
            current.remove(type)
            advance(type)
        }
        if (candidate != null) {
            attach(candidate)
            current[type] = candidate
            advance(type)
        }
    }

    /**
     * [attach] atomically replaces the sink's previous type route. A busy candidate must leave
     * the old visible preview installed; only a null candidate explicitly detaches it.
     */
    fun tryReplace(
        type: Int,
        candidate: Surface?,
        attach: (Surface) -> Boolean,
        detach: (Surface) -> Unit,
    ): Boolean {
        val prior = current[type]
        if (prior === candidate) return true
        if (candidate != null) {
            if (!attach(candidate)) return false
            current[type] = candidate
            advance(type)
        } else if (prior != null) {
            detach(prior)
            current.remove(type)
            advance(type)
        }
        return true
    }

    fun clearIfCurrent(type: Int, expected: Surface, detach: (Surface) -> Unit): Boolean {
        if (current[type] !== expected) return false
        detach(expected)
        current.remove(type)
        advance(type)
        return true
    }
}

/**
 * Creates both displays as one session behind one HTTPS/WebSocket endpoint (HTTPS so browsers
 * expose WebCodecs). The phone's compressed
 * video is forwarded untouched for the browser to decode; the main display also carries decoded
 * audio down and touch up. Local decoding only happens for an enabled preview.
 */
object CarPlayWebDisplayFactory {
    const val MAIN = 110
    const val CLUSTER = 111
    const val PORT = 8080
    /** Browsers ping every two seconds; a silent socket is a vanished browser. */
    private const val SOCKET_TIMEOUT_MS = 8000
    private const val CERTIFICATE_FILE = "web-display-tls.p12"
    private val sessions = ConcurrentHashMap<AndroidMediaSink, Session>()

    fun find(sink: AndroidMediaSink?): Session? = sink?.let(sessions::get)

    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun urls(type: Int, key: String): List<String> = addresses().map {
        "https://$it:$PORT/${if (type == MAIN) "main" else "cluster"}?key=$key"
    }

    fun create(
        context: Context,
        configs: Map<Int, CarPlayWebDisplayConfig>,
        key: String,
        sendTouch: (List<AirPlayContact>) -> Boolean,
    ): Session? {
        val enabled = configs.filterValues { it.enabled }
        if (enabled.isEmpty()) return null
        val channels = enabled.mapValues { (type, config) -> WebDisplayChannel(type, config.width, config.height) }
        val tls = WebDisplayCertificate.sslContext(File(context.noBackupFilesDir, CERTIFICATE_FILE), addresses())
        val server = WebDisplayServer(context.assets.open("carplay-display.html").use { it.readBytes() }, channels, key, sendTouch, tls = tls)
        startOwnedWebDisplayServer(
            server = server,
            start = { it.start(SOCKET_TIMEOUT_MS, true) },
            stop = WebDisplayServer::stop,
        )
        return Session(configs, channels, server)
    }

    class Session internal constructor(
        private val configs: Map<Int, CarPlayWebDisplayConfig>,
        private val displays: Map<Int, WebDisplayChannel>,
        private val stopServer: () -> Unit,
    ) : MediaTap, Closeable {
        internal constructor(
            configs: Map<Int, CarPlayWebDisplayConfig>,
            displays: Map<Int, WebDisplayChannel>,
            server: WebDisplayServer,
        ) : this(configs, displays, server::stop)

        private data class InstalledClusterPresentation(
            val generation: Long,
            val adapter: Closeable,
        )

        private val closed = AtomicBoolean(false)
        private val closeCompleted = AtomicBoolean(false)
        private val closeAttemptLock = Any()
        private var closeAttemptOwner: Thread? = null
        private var serverStopped = false
        @Volatile private var sink: AndroidMediaSink? = null
        private val previews = WebDisplayPreviewRoutes()
        /** A handoff suppresses all later preview attaches in this Session, even after failure. */
        private val suspendedPhysicalHandoff = mutableMapOf<Int, Surface>()
        private val clusterPresentationLock = Any()
        private var clusterPresentation: InstalledClusterPresentation? = null
        private var clusterPresentationOperation: Any? = null
        private var clusterPresentationGeneration = 0L

        fun bind(sink: AndroidMediaSink) = synchronized(closeAttemptLock) {
            check(!closed.get()) { "WebDisplay session is closed" }
            check(this.sink == null || this.sink === sink) {
                "WebDisplay session is already bound to another media sink"
            }
            if (this.sink === sink) return@synchronized
            synchronized(sessions) {
                this.sink = sink
                sessions[sink] = this
            }
            displays.forEach { (type, display) ->
                display.keyFrameSource = { sink.requestKeyFrame(type) }
            }
        }

        fun owns(type: Int) = displays.containsKey(type)

        /**
         * Bridges UI Sync presentation metadata into the optional cluster WebDisplay. The
         * coordinator remains controller-owned; replacing or closing this binding does not affect
         * its protocol lifetime.
         */
        fun attachClusterPresentation(
            coordinator: ClusterPresentationCoordinator,
        ): Closeable = replaceClusterPresentation { target ->
            ClusterPresentationWebDisplayAdapter(coordinator, target)
        }

        /**
         * Adapts a replayable renderer snapshot without feeding its historical events back through
         * the legacy event accumulator. This is only a presentation seam; it does not negotiate or
         * enable UI Sync.
         */
        fun attachClusterPresentation(
            source: UiSyncPresentationSource,
            displayId: String,
            limits: ClusterPresentationLimits = ClusterPresentationLimits(),
        ): Closeable = replaceClusterPresentation { target ->
            UiSyncSnapshotWebDisplayAdapter(
                source = source,
                displayId = displayId,
                target = target,
                limits = limits,
            )
        }

        /**
         * Two-phase replacement. The state lock only reserves/publishes identity; lease allocation,
         * source subscription, and every close happen outside it. Reentrant close invalidates the
         * operation token, so a candidate created after close can only be reclaimed, never installed.
         */
        internal fun replaceClusterPresentation(
            create: (WebDisplayPresentationLease) -> Closeable,
        ): Closeable {
            val channel = displays[CLUSTER] ?: return Closeable { }
            val operation = synchronized(clusterPresentationLock) {
                if (closed.get()) return Closeable { }
                check(clusterPresentationOperation == null) {
                    "A cluster presentation replacement is already in progress"
                }
                Any().also { clusterPresentationOperation = it }
            }

            val previous = synchronized(clusterPresentationLock) { clusterPresentation }
            val target = try {
                channel.acquirePresentationLease()
            } catch (failure: Throwable) {
                abortClusterPresentationReplacement(operation, previous, failure)
            }
            val candidate = try {
                create(target)
            } catch (failure: Throwable) {
                try {
                    target.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                abortClusterPresentationReplacement(operation, previous, failure)
            }

            try {
                previous?.adapter?.close()
            } catch (failure: Throwable) {
                try {
                    candidate.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                abortClusterPresentationReplacement(operation, previous, failure)
            }

            val installedGeneration = synchronized(clusterPresentationLock) {
                if (clusterPresentationOperation !== operation) {
                    null
                } else if (closed.get()) {
                    clusterPresentationOperation = null
                    null
                } else {
                    check(clusterPresentationGeneration != Long.MAX_VALUE) {
                        "Cluster presentation generation exhausted"
                    }
                    clusterPresentationGeneration += 1L
                    clusterPresentation = InstalledClusterPresentation(
                        clusterPresentationGeneration,
                        candidate,
                    )
                    clusterPresentationOperation = null
                    clusterPresentationGeneration
                }
            }
            if (installedGeneration == null) {
                candidate.close()
                return Closeable { }
            }

            return Closeable {
                synchronized(clusterPresentationLock) {
                    val current = clusterPresentation
                    if (
                        current?.generation == installedGeneration &&
                        current.adapter === candidate
                    ) {
                        clusterPresentation = null
                    }
                }
                candidate.close()
            }
        }

        private fun abortClusterPresentationReplacement(
            operation: Any,
            previous: InstalledClusterPresentation?,
            failure: Throwable,
        ): Nothing {
            val retire = synchronized(clusterPresentationLock) {
                if (clusterPresentationOperation === operation) {
                    clusterPresentationOperation = null
                    if (clusterPresentation === previous) {
                        clusterPresentation = null
                        previous?.adapter
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            try {
                retire?.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }

        @Synchronized fun preview(type: Int, surface: Surface?): Boolean {
            if (closed.get()) return false
            val target = surface.takeIf { configs[type]?.preview == true }
            val sink = sink ?: return false
            // The old Surface remains tombstoned for this Session. A later *different* Surface
            // may still become its preview after a denied handoff; the sink's type-wide gate
            // independently rejects that attach while an actual handoff is still in flight.
            val suspended = suspendedPhysicalHandoff[type]
            if (suspended != null && (target == null || target === suspended)) return false
            return previews.tryReplace(
                type,
                target,
                attach = { sink.trySetSurfaceForPreview(type, it) },
                detach = { sink.clearSurface(type, it) },
            )
        }

        /** Called before sink capture so a UI refresh cannot reattach during handoff. */
        @Synchronized internal fun suspendPreviewForPhysicalHandoff(
            type: Int,
            expected: Surface,
        ): Boolean {
            if (closed.get() || !owns(type) ||
                previews.currentSurface(type)?.let { it !== expected } == true
            ) return false
            val current = suspendedPhysicalHandoff[type]
            if (current != null && current !== expected) return false
            suspendedPhysicalHandoff[type] = expected
            return true
        }

        /** No old Activity may clear a different Activity's successor preview. */
        @Synchronized fun clearPreview(type: Int, expected: Surface) {
            if (closed.get()) return
            val sink = sink ?: return
            previews.clearIfCurrent(type, expected) { sink.clearSurface(type, it) }
        }

        /** Exact synchronous preview detachment for a process-owned physical handoff. */
        @Synchronized internal fun retirePreviewForPhysicalHandoff(type: Int, expected: Surface): Long? {
            if (closed.get()) return null
            val sink = sink ?: return null
            return if (previews.clearIfCurrent(type, expected) { sink.clearSurface(type, it) }) {
                previews.generation(type)
            } else null
        }

        /**
         * Restores metadata only after the captured sink reservation atomically restores its old
         * decoder binding. The same Session must still own this sink, and a successor Activity's
         * preview can never be overwritten. No Activity or View is retained by this operation.
         */
        internal fun restorePreviewAfterPhysicalHandoff(
            type: Int,
            expected: Surface,
            expectedSink: AndroidMediaSink,
            expectedPreviewGeneration: Long,
            restoreSink: () -> Boolean,
        ): Boolean = synchronized(closeAttemptLock) {
            synchronized(this) previewRestore@ {
                synchronized(sessions) {
                    if (closed.get() || sink !== expectedSink || sessions[expectedSink] !== this ||
                        configs[type]?.preview != true || previews.currentSurface(type) != null ||
                        previews.generation(type) != expectedPreviewGeneration
                    ) return@previewRestore false
                    if (!restoreSink()) return@previewRestore false
                    check(previews.rememberRestoredIfSameGeneration(
                        type, expected, expectedPreviewGeneration,
                    )) {
                        "WebDisplay preview changed during exact legacy restoration"
                    }
                    true
                }
            }
        }

        override fun forwardsVideo(type: Int) = displays.containsKey(type)
        override fun onVideoConfig(type: Int, codec: VideoCodec, codecData: ByteArray) { displays[type]?.config(codec, codecData) }
        override fun onVideoFrame(type: Int, accessUnit: ByteArray, arrivalUs: Long) { displays[type]?.frame(accessUnit, arrivalUs) }
        override fun onVideoActive(type: Int, active: Boolean) { displays[type]?.setActive(active) }
        override fun takesAudio() = displays[MAIN]?.hasViewer == true
        override fun onAudioPcm(stream: Int, sampleRate: Int, channels: Int, pcm: ByteArray, offset: Int, length: Int) {
            displays[MAIN]?.audio(stream, sampleRate, channels, pcm, offset, length)
        }
        override fun onAudioStopped(stream: Int) { displays[MAIN]?.audioStopped(stream) }

        override fun close() {
            // Admission closes immediately, independently of whether external retirement succeeds.
            closed.set(true)
            synchronized(closeAttemptLock) {
                if (closeCompleted.get()) return
                val currentThread = Thread.currentThread()
                check(closeAttemptOwner !== currentThread) {
                    "WebDisplay close is already in flight on this thread"
                }
                check(closeAttemptOwner == null) {
                    "WebDisplay close attempt owner was not released"
                }
                closeAttemptOwner = currentThread
                try {
                    var failure: Throwable? = null

                    val presentation = synchronized(clusterPresentationLock) {
                        clusterPresentationOperation = null
                        clusterPresentation
                    }
                    if (presentation != null) {
                        try {
                            presentation.adapter.close()
                            synchronized(clusterPresentationLock) {
                                if (clusterPresentation === presentation) {
                                    clusterPresentation = null
                                } else {
                                    check(clusterPresentation == null) {
                                        "WebDisplay presentation owner changed after close admission"
                                    }
                                }
                            }
                        } catch (caught: Throwable) {
                            // Keep the exact adapter installed for the next close attempt.
                            failure = combineWebDisplayCloseFailures(failure, caught)
                        }
                    }

                    val boundSink = sink
                    if (boundSink != null) {
                        try {
                            // A closed WebDisplay must not leave its old preview decoder route
                            // installed. Keep this Session discoverable until exact detachment
                            // finishes; failed clears retain their preview entry for retry.
                            synchronized(this) {
                                configs.keys.forEach { type ->
                                    previews.currentSurface(type)?.let { current ->
                                        previews.clearIfCurrent(type, current) {
                                            boundSink.clearSurface(type, it)
                                        }
                                    }
                                }
                                suspendedPhysicalHandoff.clear()
                            }
                            synchronized(sessions) { sessions.remove(boundSink, this) }
                            displays.values.forEach { display ->
                                display.keyFrameSource = { false }
                            }
                            if (sink === boundSink) sink = null
                        } catch (caught: Throwable) {
                            // Keep the exact sink identity until registry detachment completes.
                            failure = combineWebDisplayCloseFailures(failure, caught)
                        }
                    }

                    if (!serverStopped) {
                        try {
                            stopServer()
                            serverStopped = true
                        } catch (caught: Throwable) {
                            // The bound method retains the exact server instance for retry.
                            failure = combineWebDisplayCloseFailures(failure, caught)
                        }
                    }

                    if (failure == null) {
                        check(synchronized(clusterPresentationLock) {
                            clusterPresentation == null && clusterPresentationOperation == null
                        }) { "WebDisplay presentation retirement is incomplete" }
                        check(sink == null) { "WebDisplay sink detachment is incomplete" }
                        check(serverStopped) { "WebDisplay server retirement is incomplete" }
                        closeCompleted.set(true)
                    }
                    failure?.let { throw it }
                } finally {
                    closeAttemptOwner = null
                }
            }
        }
    }
}

/** Preserve a fatal Error as the primary throwable while retaining every ordinary close failure. */
private fun combineWebDisplayCloseFailures(current: Throwable?, next: Throwable): Throwable = when {
    current == null -> next
    next is Error && current !is Error -> next.also { if (current !== next) it.addSuppressed(current) }
    else -> current.also { if (current !== next) it.addSuppressed(next) }
}

/**
 * One display's stream, fanned out to every connected browser. Binary messages to the browser:
 * `1 codec(0=H.264,1=H.265) avcC|hvcC`, `2 flags(1=key) u64 arrivalUs accessUnit`,
 * `3 stream u32 sampleRate u8 channels s16le-pcm`, `4 stream` (audio stopped).
 */
internal class WebDisplayChannel(
    val type: Int,
    val width: Int,
    val height: Int,
    private val now: () -> Long = System::nanoTime,
) {
    private class Config(val codec: VideoCodec, val message: ByteArray)
    private val viewers = CopyOnWriteArrayList<WebDisplayViewer>()
    private val keyLock = Any()
    private var lastKeyRequest: Long? = null
    @Volatile private var config: Config? = null
    @Volatile private var presentation: String? = null
    private var presentationOwner: Any? = null
    @Volatile var active = false; private set
    @Volatile var keyFrameSource: () -> Boolean = { false }
    /** On the main display, a connected browser also takes the phone's audio from the local speaker. */
    val hasViewer get() = viewers.isNotEmpty()
    val viewerCount get() = viewers.size

    @Synchronized fun attach(viewer: WebDisplayViewer) {
        viewers += viewer
        viewer.state(state())
        presentation?.let(viewer::presentation)
        // Joining mid-stream: the decoder needs the parameter sets and a random access picture now.
        config?.let { viewer.config(it.codec, it.message); requestKeyFrame(force = true) }
    }

    fun detach(viewer: WebDisplayViewer) { viewers.remove(viewer) }

    @Synchronized fun setActive(value: Boolean) {
        active = value
        if (!value) config = null
        val state = state()
        viewers.forEach { if (!value) it.reset(); it.state(state) }
    }

    @Synchronized fun config(codec: VideoCodec, codecData: ByteArray) {
        val message = ByteArray(2 + codecData.size)
        message[0] = CONFIG
        message[1] = if (codec == VideoCodec.H265) 1 else 0
        codecData.copyInto(message, 2)
        // The phone repeats unchanged parameter sets; restarting browsers on them would stall video.
        if (config?.let { it.codec == codec && it.message.contentEquals(message) } == true) return
        config = Config(codec, message)
        viewers.forEach { it.config(codec, message) }
    }

    fun frame(accessUnit: ByteArray, arrivalUs: Long) {
        val current = config ?: return
        if (viewers.isEmpty()) return
        val types = try {
            VideoSyncGate.nalUnitTypes(current.codec, MediaCodecSupport.lengthPrefixedHeaders(accessUnit))
        } catch (_: IllegalArgumentException) { return }
        if (types.isEmpty()) return
        val message = ByteArray(10 + accessUnit.size)
        message[0] = FRAME
        message[1] = if (VideoSyncGate.isRandomAccess(current.codec, types)) 1 else 0
        ByteBuffer.wrap(message, 2, 8).putLong(arrivalUs)
        accessUnit.copyInto(message, 10)
        var waiting = false
        viewers.forEach { if (it.video(types, message)) waiting = true }
        if (waiting) requestKeyFrame()
    }

    fun audio(stream: Int, sampleRate: Int, channels: Int, pcm: ByteArray, offset: Int, length: Int) {
        if (viewers.isEmpty() || length <= 0) return
        val message = ByteArray(7 + length)
        message[0] = AUDIO
        message[1] = stream.toByte()
        ByteBuffer.wrap(message, 2, 4).putInt(sampleRate)
        message[6] = channels.toByte()
        pcm.copyInto(message, 7, offset, offset + length)
        viewers.forEach { it.audio(message) }
    }

    fun audioStopped(stream: Int) {
        val message = byteArrayOf(AUDIO_STOPPED, stream.toByte())
        viewers.forEach { it.control(message) }
    }

    /**
     * A replacement lease makes publication linearizable: delayed callbacks from an older source
     * cannot replace the latest presentation after a new source has taken ownership.
     */
    @Synchronized fun acquirePresentationLease(): WebDisplayPresentationLease {
        require(type == CarPlayWebDisplayFactory.CLUSTER) {
            "Presentation metadata is only supported by the cluster WebDisplay"
        }
        val owner = Any()
        presentationOwner = owner
        if (presentation != null) clearPresentationForViewers()
        return WebDisplayPresentationLease(this, owner)
    }

    @Synchronized internal fun replacePresentation(owner: Any, json: String): Boolean {
        if (presentationOwner !== owner) return false
        presentation = json
        viewers.forEach { it.presentation(json) }
        return true
    }

    @Synchronized internal fun releasePresentation(owner: Any) {
        if (presentationOwner !== owner) return
        presentationOwner = null
        clearPresentationForViewers()
    }

    /** Caller holds this channel's monitor. Reset is local and never enters UI Sync state. */
    private fun clearPresentationForViewers() {
        presentation = null
        viewers.forEach { it.presentation(PRESENTATION_RESET) }
    }

    /** Rate limited: every browser waiting for sync asks on every rejected picture. */
    fun requestKeyFrame(force: Boolean = false) {
        val time = now()
        synchronized(keyLock) {
            val last = lastKeyRequest
            if (!force && last != null && time - last < KEY_FRAME_INTERVAL_NS) return
            lastKeyRequest = time
        }
        keyFrameSource()
    }

    private fun state() = """{"type":"state","active":$active,"width":$width,"height":$height,""" +
        """"control":${type == CarPlayWebDisplayFactory.MAIN},"audio":${type == CarPlayWebDisplayFactory.MAIN}}"""

    companion object {
        const val CONFIG: Byte = 1
        const val FRAME: Byte = 2
        const val AUDIO: Byte = 3
        const val AUDIO_STOPPED: Byte = 4
        const val PRESENTATION_RESET = "{\"type\":\"clusterPresentationReset\",\"version\":1}"
        private val KEY_FRAME_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(500)
    }
}

/** Identity-scoped publication access for one replayable cluster presentation source. */
internal class WebDisplayPresentationLease(
    private val channel: WebDisplayChannel,
    private val owner: Any,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun replace(json: String): Boolean =
        !closed.get() && channel.replacePresentation(owner, json)

    override fun close() {
        if (closed.compareAndSet(false, true)) channel.releasePresentation(owner)
    }
}

/**
 * One browser connection. Its own sender thread keeps a slow browser from stalling the phone's
 * stream; when it falls behind, queued pictures are dropped and it resumes at random access.
 */
internal class WebDisplayViewer(private val transport: Transport, start: Boolean = true) : Closeable {
    interface Transport {
        fun send(message: ByteArray)
        fun send(message: String)
        fun close()
    }

    private class Message(val bytes: ByteArray?, val text: String?, val kind: Int)
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val queue = ArrayDeque<Message>()
    private var videoFrames = 0
    private var videoBytes = 0L
    private var audioPackets = 0
    private var codec: VideoCodec? = null
    private var gate: VideoSyncGate? = null
    private var closed = false

    init {
        if (start) Thread(::run, "carplay-web-viewer").apply { isDaemon = true; start() }
    }

    fun state(json: String) = lock.withLock { enqueue(Message(null, json, CONTROL)) }

    /** Coalesced because every presentation message is a complete replayable snapshot. */
    fun presentation(json: String) = lock.withLock {
        drop(PRESENTATION)
        enqueue(Message(null, json, PRESENTATION))
    }

    fun control(message: ByteArray) = lock.withLock { enqueue(Message(message, null, CONTROL)) }

    /** New parameter sets: decoding restarts at the next random access picture. */
    fun config(codec: VideoCodec, message: ByteArray) = lock.withLock {
        this.codec = codec
        gate = VideoSyncGate(codec)
        enqueue(Message(message, null, CONTROL))
    }

    /** The browser's decoder failed or was rebuilt. */
    fun restartVideo() = lock.withLock {
        drop(VIDEO)
        codec?.let { gate = VideoSyncGate(it) }
    }

    fun reset() = lock.withLock {
        drop(VIDEO)
        codec = null
        gate = null
    }

    /** Returns true while this browser is waiting for a random access picture. */
    fun video(types: List<Int>, message: ByteArray): Boolean = lock.withLock {
        val current = gate ?: return false
        val codec = codec ?: return false
        if (!current.acceptTypes(types)) return current.waitingForRandomAccess
        if (videoFrames >= MAX_VIDEO_FRAMES || videoBytes + message.size > MAX_VIDEO_BYTES) {
            // Behind by more than a few frames: skip ahead instead of adding latency.
            drop(VIDEO)
            if (!VideoSyncGate.isRandomAccess(codec, types)) {
                gate = VideoSyncGate(codec)
                return true
            }
        }
        enqueue(Message(message, null, VIDEO))
        false
    }

    fun audio(message: ByteArray) = lock.withLock {
        if (audioPackets >= MAX_AUDIO_PACKETS) {
            val oldest = queue.indexOfFirst { it.kind == AUDIO }
            if (oldest >= 0) { queue.removeAt(oldest); audioPackets-- }
        }
        enqueue(Message(message, null, AUDIO))
    }

    /** Next queued message, or null after [timeoutMs] or once closed. */
    internal fun next(timeoutMs: Long): Any? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (queue.isEmpty() && !closed && remaining > 0) remaining = ready.awaitNanos(remaining)
        if (closed) return null
        // Audio is independent of the picture order; it never waits behind queued pictures.
        val audio = if (audioPackets > 0) queue.indexOfFirst { it.kind == AUDIO } else -1
        val message = (if (audio >= 0) queue.removeAt(audio) else queue.removeFirstOrNull()) ?: return null
        when (message.kind) {
            VIDEO -> { videoFrames--; videoBytes -= message.bytes!!.size }
            AUDIO -> audioPackets--
        }
        message.bytes ?: message.text
    }

    override fun close() = lock.withLock {
        closed = true
        queue.clear()
        ready.signalAll()
    }

    private fun run() {
        try {
            while (true) {
                when (val message = next(1000)) {
                    is ByteArray -> transport.send(message)
                    is String -> transport.send(message)
                    null -> if (lock.withLock { closed }) return
                }
            }
        } catch (_: IOException) {
            close()
            transport.close()
        }
    }

    private fun enqueue(message: Message) {
        if (closed) return
        queue.addLast(message)
        when (message.kind) {
            VIDEO -> { videoFrames++; videoBytes += message.bytes!!.size }
            AUDIO -> audioPackets++
        }
        ready.signal()
    }

    private fun drop(kind: Int) {
        val iterator = queue.iterator()
        while (iterator.hasNext()) if (iterator.next().kind == kind) iterator.remove()
        if (kind == VIDEO) { videoFrames = 0; videoBytes = 0 }
        if (kind == AUDIO) audioPackets = 0
    }

    private companion object {
        const val CONTROL = 0
        const val VIDEO = 1
        const val AUDIO = 2
        const val PRESENTATION = 3
        const val MAX_VIDEO_FRAMES = 12
        const val MAX_VIDEO_BYTES = 4L * 1024 * 1024
        /** About half a second of 20 ms packets. */
        const val MAX_AUDIO_PACKETS = 25
    }
}

/**
 * Touch state from one browser at a time. Every message carries all pressed contacts, so a lost
 * or reordered move cannot leave the phone with a wrong finger set; a short lease releases fingers
 * whose browser vanished. A finger lifts where it last was: a lift reported elsewhere reads as a
 * drag there, which cancels taps and flings scrolls.
 */
internal class WebDisplayTouch(
    private val send: (List<AirPlayContact>) -> Boolean,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private var owner: String? = null
    private var touchedAt = 0L
    private var pressedAt = 0L
    private val slots = MutableList(SLOTS) { AirPlayContact(it, 0.0, 0.0, false) }

    @Synchronized fun submit(client: String, value: String, now: Long): Boolean {
        require(client.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        val contacts = if (value.isEmpty()) emptyList() else value.split(';').map {
            val fields = it.split(',')
            require(fields.size == 3)
            AirPlayContact(fields[0].toInt(), fields[1].toDouble(), fields[2].toDouble(), true)
        }
        require(contacts.size <= SLOTS && contacts.map { it.id }.distinct().size == contacts.size && contacts.all {
            it.id in 0 until SLOTS && it.x.isFinite() && it.y.isFinite() && it.x in 0.0..1.0 && it.y in 0.0..1.0
        })
        expire(now)
        if (owner != null && owner != client) return false
        // A press and release reported within a few milliseconds can be read as no tap at all.
        if (contacts.isEmpty() && owner == client && now - pressedAt < MIN_PRESS_MS) sleep(MIN_PRESS_MS - (now - pressedAt))
        if (!report(List(SLOTS) { id -> contacts.find { it.id == id } ?: slots[id].copy(down = false) })) return false
        if (owner == null && contacts.isNotEmpty()) pressedAt = now
        touchedAt = now
        owner = client.takeIf { contacts.isNotEmpty() }
        return true
    }

    @Synchronized fun expire(now: Long) { if (owner != null && now - touchedAt >= LEASE_MS) release() }
    @Synchronized fun release(client: String) { if (owner == client) release() }
    @Synchronized fun release() { if (owner != null) report(slots.map { it.copy(down = false) }); owner = null }

    private fun report(next: List<AirPlayContact>): Boolean {
        if (!send(next)) return false
        next.forEachIndexed { id, contact -> slots[id] = contact }
        return true
    }

    private companion object {
        const val SLOTS = 2
        const val LEASE_MS = 1500L
        const val MIN_PRESS_MS = 30L
    }
}

internal class WebDisplayServer(
    private val page: ByteArray,
    private val channels: Map<Int, WebDisplayChannel>,
    private val key: String,
    sendTouch: (List<AirPlayContact>) -> Boolean,
    port: Int = CarPlayWebDisplayFactory.PORT,
    tls: SSLContext,
) : NanoWSD("0.0.0.0", port) {
    private val touch = WebDisplayTouch(sendTouch)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-web-touch").apply { isDaemon = true }
    }

    init {
        setServerSocketFactory { TlsServerSocket(tls.socketFactory) }
        watchdog.scheduleWithFixedDelay({ touch.expire(nowMs()) }, 250, 250, TimeUnit.MILLISECONDS)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.parameters["key"]?.firstOrNull() != key) return text(Response.Status.UNAUTHORIZED, "Display key required")
        val parts = session.uri.trim('/').split('/')
        val type = route(parts.first()) ?: return text(Response.Status.NOT_FOUND, "Display not found")
        if (channels[type] == null) return text(Response.Status.NOT_FOUND, "Display is disabled")
        if (parts.size == 1 && session.method == Method.GET) return bytes("text/html; charset=utf-8", page)
        if (parts.size != 2 || parts[1] != "ws") return text(Response.Status.NOT_FOUND, "Not found")
        if (!isWebsocketRequested(session)) return text(Response.Status.BAD_REQUEST, "WebSocket required")
        val sameOrigin = runCatching {
            val origin = URI(session.headers["origin"] ?: "")
            origin.scheme == "https" && origin.rawAuthority.equals(session.headers["host"], true)
        }.getOrDefault(false)
        if (!sameOrigin) return text(Response.Status.FORBIDDEN, "Origin rejected")
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val type = checkNotNull(route(handshake.uri.trim('/').substringBefore('/')))
        return DisplaySocket(handshake, type, channels.getValue(type))
    }

    override fun stop() { watchdog.shutdownNow(); touch.release(); super.stop() }

    private inner class DisplaySocket(
        handshake: IHTTPSession,
        private val type: Int,
        private val channel: WebDisplayChannel,
    ) : WebSocket(handshake) {
        private val client = UUID.randomUUID().toString()
        @Volatile private var viewer: WebDisplayViewer? = null

        override fun onOpen() {
            val socket = this
            viewer = WebDisplayViewer(object : WebDisplayViewer.Transport {
                override fun send(message: ByteArray) = socket.send(message)
                override fun send(message: String) = socket.send(message)
                override fun close() {
                    try { socket.close(WebSocketFrame.CloseCode.GoingAway, "Too slow", false) } catch (_: IOException) {}
                }
            }).also(channel::attach)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            viewer?.let { channel.detach(it); it.close() }
            viewer = null
            if (type == CarPlayWebDisplayFactory.MAIN) touch.release(client)
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            when {
                text == "k" -> { viewer?.restartVideo(); channel.requestKeyFrame(force = true) }
                // A browser that buffers the stream (MSE) can only release data before a random access picture.
                text == "K" -> channel.requestKeyFrame()
                text.startsWith("t:") && type == CarPlayWebDisplayFactory.MAIN && channel.active -> {
                    val accepted = try { touch.submit(client, text.substring(2), nowMs()) } catch (_: IllegalArgumentException) { false }
                    if (!accepted) viewer?.state("""{"type":"busy"}""")
                }
            }
        }

        override fun onPong(pong: WebSocketFrame) = Unit
        override fun onException(exception: IOException) = Unit
    }

    private fun route(name: String) = when (name) {
        "main" -> CarPlayWebDisplayFactory.MAIN
        "cluster" -> CarPlayWebDisplayFactory.CLUSTER
        else -> null
    }

    private fun nowMs() = System.nanoTime() / 1_000_000
    private fun text(status: Response.Status, value: String) = newFixedLengthResponse(status, "text/plain", value).apply { secure() }
    private fun bytes(mime: String, value: ByteArray) =
        newFixedLengthResponse(Response.Status.OK, mime, value.inputStream(), value.size.toLong()).apply { secure() }
    private fun Response.secure() {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
    }
}

/**
 * TLS without Nagle delay for small touch-driven updates. NanoHTTPD flushes after every response
 * and WebSocket frame, so the output buffer above TLS makes each frame one record.
 */
private class TlsServerSocket(private val tls: SSLSocketFactory) : ServerSocket() {
    override fun accept(): Socket {
        val plain = Socket().also { implAccept(it); it.tcpNoDelay = true }
        // The handshake runs on the first read, in the connection's own thread.
        val socket = tls.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket
        socket.useClientMode = false
        return TlsSocket(socket)
    }
}

/**
 * Exposes an accepted [SSLSocket] with buffered output; only what NanoHTTPD uses is delegated.
 * Browsers abort handshakes until the certificate is accepted; NanoHTTPD would answer those over the
 * dead connection and log each failure, so they end the connection quietly instead.
 */
private class TlsSocket(private val socket: SSLSocket) : Socket() {
    private val input by lazy {
        object : FilterInputStream(socket.inputStream) {
            override fun read(): Int = quiet { super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = quiet { super.read(b, off, len) }
        }
    }
    private val output by lazy {
        object : BufferedOutputStream(socket.outputStream, 64 * 1024) {
            override fun flush() { if (!socket.isClosed) super.flush() }
            override fun close() { if (!socket.isClosed) super.close() }
        }
    }
    private inline fun <T> quiet(read: () -> T): T = try { read() } catch (e: SSLException) {
        socket.close()
        throw SocketException("NanoHttpd Shutdown")
    }
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun getInetAddress(): InetAddress? = socket.inetAddress
    override fun getPort(): Int = socket.port
    override fun setSoTimeout(timeout: Int) { socket.soTimeout = timeout }
    override fun getSoTimeout(): Int = socket.soTimeout
    override fun isConnected(): Boolean = socket.isConnected
    override fun isClosed(): Boolean = socket.isClosed
    override fun close() = socket.close()
}
