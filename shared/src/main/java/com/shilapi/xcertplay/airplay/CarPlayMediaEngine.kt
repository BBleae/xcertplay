package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncCommandType
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncRoutingDiagnostic
import com.shilapi.xcertplay.airplay.rcs.uisync.UiSyncPreparedScreenBinding
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedPcm16ProductPreparer
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupState
import com.shilapi.xcertplay.airplay.mainbuffered.MainBufferedV27SetupTransaction
import com.shilapi.xcertplay.airplay.video.MatchPointProductSessionFactory
import com.shilapi.xcertplay.airplay.video.MatchPointProductSessionOwner
import com.shilapi.xcertplay.media.SerializedCallbackQueue
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Opaque renderer epoch. The owning [AirPlaySession] is deliberately never exposed downstream. */
internal class AirPlayVideoSessionEpoch

/**
 * Unique, session-bound identity for one screen stream.
 *
 * Equality is intentionally referential: two sessions may legally reuse the same protocol
 * [identity], but they must never share decoder, Surface, recovery, or lifecycle state.
 */
class AirPlayVideoStreamHandle internal constructor(
    val identity: AirPlayStreamIdentity,
    @Suppress("unused") private val sessionEpoch: AirPlayVideoSessionEpoch,
    /** SETUP `uuid`, only when it exactly names this session's configured `/info` display. */
    val validatedDisplayUuid: String?,
) {
    internal constructor(
        identity: AirPlayStreamIdentity,
        sessionEpoch: AirPlayVideoSessionEpoch,
    ) : this(identity, sessionEpoch, null)

    init {
        require(validatedDisplayUuid == null ||
            (identity.type == 110 && validatedDisplayUuid == AirPlayInfoPlist.MAIN_UUID) ||
            (identity.type == 111 && validatedDisplayUuid == AirPlayInfoPlist.ALT_UUID)
        ) { "Video stream display UUID conflicts with its type" }
    }

    val type: Int
        get() = identity.type

    override fun toString(): String =
        "AirPlayVideoStreamHandle(type=$type, connectionID=" +
            "${if (identity.streamConnectionId == null) "absent" else "<redacted>"}, " +
            "clientTypeUUID=${if (identity.clientTypeUuid == null) "absent" else "<redacted>"}, " +
            "session=<redacted>)"
}

/** A SETUP UUID is useful for routing only if this connection could have advertised it. */
internal fun validatedSetupDisplayUuid(
    type: Int,
    stream: Map<String, Any?>,
    hasConfiguredCluster: Boolean,
): String? {
    val expected = when (type) {
        110 -> AirPlayInfoPlist.MAIN_UUID
        111 -> AirPlayInfoPlist.ALT_UUID.takeIf { hasConfiguredCluster }
        else -> null
    }
    return (stream["uuid"] as? String)?.takeIf { it == expected }
}

/**
 * Lock-linearized lifecycle for a prepared screen listener.
 *
 * State mutation callbacks run under [lifecycleLock], while renderer notifications are only
 * reserved there. The returned task must be run after leaving that lock. Reservation order then
 * preserves ACTIVE -> CLOSED notification order even when the close thread reaches the task first.
 */
internal class ScreenStreamLifecycleStateMachine<K : Any, R : Any, H : Any>(
    private val lifecycleLock: Any,
    private val isTerminal: (K) -> Boolean,
) {
    internal enum class Phase { NEW, PENDING, ACTIVE, CLOSED }

    internal class Entry<K : Any, R : Any, H : Any> internal constructor(
        val key: K,
        val resource: R,
        val handle: H,
    ) {
        internal val notifications = SerializedCallbackQueue()
        internal var phase = Phase.NEW
            private set

        internal fun setPhase(value: Phase) {
            phase = value
        }
    }

    private val entries = LinkedHashMap<K, Entry<K, R, H>>()

    fun create(key: K, resource: R, handle: H): Entry<K, R, H> =
        Entry(key, resource, handle)

    fun install(
        entry: Entry<K, R, H>,
        installStateLocked: () -> Boolean,
    ): Boolean = synchronized(lifecycleLock) {
        if (entry.phase != Phase.NEW || isTerminal(entry.key) || entries.containsKey(entry.key)) {
            false
        } else if (!installStateLocked()) {
            false
        } else {
            entry.setPhase(Phase.PENDING)
            entries[entry.key] = entry
            true
        }
    }

    fun promote(
        entry: Entry<K, R, H>,
        promoteStateLocked: () -> Boolean,
        notifyActive: () -> Unit,
    ): SerializedCallbackQueue.Task? = synchronized(lifecycleLock) {
        if (entries[entry.key] !== entry || entry.phase != Phase.PENDING ||
            isTerminal(entry.key) || !promoteStateLocked()
        ) {
            null
        } else {
            entry.setPhase(Phase.ACTIVE)
            entry.notifications.reserve(notifyActive)
        }
    }

    fun close(
        entry: Entry<K, R, H>,
        retainClosed: Boolean,
        closeStateLocked: () -> Unit,
        notifyInactive: () -> Unit,
    ): SerializedCallbackQueue.Task? = synchronized(lifecycleLock) {
        if (entries[entry.key] !== entry || entry.phase == Phase.CLOSED) {
            null
        } else {
            closeStateLocked()
            entry.setPhase(Phase.CLOSED)
            if (!retainClosed) entries.remove(entry.key, entry)
            entry.notifications.reserve(notifyInactive)
        }
    }

    fun entry(key: K): Entry<K, R, H>? = synchronized(lifecycleLock) { entries[key] }

    fun owns(entry: Entry<K, R, H>): Boolean = synchronized(lifecycleLock) {
        entries[entry.key] === entry && entry.phase != Phase.CLOSED
    }

    fun keys(): List<K> = synchronized(lifecycleLock) { entries.keys.toList() }

    fun forget(entry: Entry<K, R, H>) = synchronized(lifecycleLock) {
        if (entry.phase == Phase.CLOSED) entries.remove(entry.key, entry)
    }
}

private typealias EngineScreenLifecycle = ScreenStreamLifecycleStateMachine.Entry<
    CarPlayMediaEngine.StreamKey,
    Closeable,
    AirPlayVideoStreamHandle,
>

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    /**
     * Session-bound handle callbacks are the primary media path. Identity-only and type-only
     * overloads remain compatibility adapters for sinks that predate overlapping sessions or
     * multiple same-type AirPlay streams.
     */
    fun onVideoCodec(handle: AirPlayVideoStreamHandle, codec: VideoCodec) {
        onVideoCodec(handle.identity, codec)
    }
    fun onVideoCodec(identity: AirPlayStreamIdentity, codec: VideoCodec) {
        onVideoCodec(identity.type, codec)
    }
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoRecoveryHandler(
        handle: AirPlayVideoStreamHandle,
        requestKeyFrame: (() -> Boolean)?,
    ) {
        onVideoRecoveryHandler(handle.identity, requestKeyFrame)
    }
    fun onVideoRecoveryHandler(
        identity: AirPlayStreamIdentity,
        requestKeyFrame: (() -> Boolean)?,
    ) {
        onVideoRecoveryHandler(identity.type, requestKeyFrame)
    }
    fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {}
    fun onVideoConfig(handle: AirPlayVideoStreamHandle, codecData: ByteArray) {
        onVideoConfig(handle.identity, codecData)
    }
    fun onVideoConfig(identity: AirPlayStreamIdentity, codecData: ByteArray) {
        onVideoConfig(identity.type, codecData)
    }
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(handle: AirPlayVideoStreamHandle, naluBytes: ByteArray) {
        onVideoFrame(handle.identity, naluBytes)
    }
    fun onVideoFrame(identity: AirPlayStreamIdentity, naluBytes: ByteArray) {
        onVideoFrame(identity.type, naluBytes)
    }
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    fun onVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) {
        onVideoFrame(handle.identity, naluBytes, timing)
    }
    fun onVideoFrame(
        identity: AirPlayStreamIdentity,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) {
        onVideoFrame(identity.type, naluBytes, timing)
    }
    fun onVideoFrame(type: Int, naluBytes: ByteArray, timing: ScreenFrameTiming) {
        onVideoFrame(type, naluBytes)
    }
    fun onVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        onVideoFrame(
            handle.identity,
            naluBytes,
            timing,
            presentationTargetMonotonicNanos,
        )
    }
    fun onVideoFrame(
        identity: AirPlayStreamIdentity,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        onVideoFrame(identity.type, naluBytes, timing, presentationTargetMonotonicNanos)
    }
    fun onVideoFrame(
        type: Int,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        onVideoFrame(type, naluBytes, timing)
    }
    fun onScreenStreamActive(handle: AirPlayVideoStreamHandle, active: Boolean) {
        onScreenStreamActive(handle.identity, active)
    }
    fun onScreenStreamActive(identity: AirPlayStreamIdentity, active: Boolean) {
        onScreenStreamActive(identity.type, active)
    }
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(type: Int) {}
    fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(type: Int) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Main audio streams can
 * additionally return microphone audio when the phone supplies an input port.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
    private val videoPacketLog: ((Int, VideoCodec, Long, ByteArray, ByteArray, ByteArray?) -> Unit)? = null,
    private val ultraRuntime: AirPlayUltraRuntime? = null,
    private val matchPointSessionFactory: MatchPointProductSessionFactory? = null,
) : AirPlayMediaHandler, Closeable {
    /** No production issuer exists for the M4-flushed raw-secret lease, so this stays null. */
    private var mainBufferedProductPreparer: MainBufferedPcm16ProductPreparer? = null

    internal constructor(
        sink: MediaSink,
        mainBufferedProductPreparer: MainBufferedPcm16ProductPreparer,
    ) : this(sink) {
        this.mainBufferedProductPreparer = mainBufferedProductPreparer
    }

    internal data class StreamKey(
        val session: AirPlaySession,
        val identity: AirPlayStreamIdentity,
    ) {
        constructor(session: AirPlaySession, type: Int) : this(
            session,
            AirPlayStreamIdentity(type, null, null),
        )

        val type: Int
            get() = identity.type
    }

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private class SessionDataRuntime(
        val ultra: AirPlayUltraSessionRuntime?,
        val matchPoint: MatchPointProductSessionOwner?,
        val handlers: CarPlayDataStreamHandlers,
    ) : Closeable {
        private var matchPointClosed = matchPoint == null
        private var ultraClosed = ultra == null
        private var closeInFlight = false

        @Synchronized
        override fun close() {
            if (closeInFlight) throw IOException("AirPlay session data runtime close is in flight")
            closeInFlight = true
            var failure: Throwable? = null
            try {
                if (!matchPointClosed) {
                    try { matchPoint?.close(); matchPointClosed = true } catch (error: Throwable) {
                        failure = combineEngineFailures(failure, error)
                    }
                }
                if (!ultraClosed) {
                    try { ultra?.close(); ultraClosed = true } catch (error: Throwable) {
                        failure = combineEngineFailures(failure, error)
                    }
                }
            } finally {
                closeInFlight = false
            }
            failure?.let { throw it }
        }
    }

    /** Retains every owner from failed construction, including a second queued compute attempt. */
    private class PendingSessionRuntimeConstructionRetirement : Closeable {
        private class Entry(val resource: Closeable, var retired: Boolean = false)
        private val lock = Any()
        private val entries = ArrayList<Entry>()
        private var closeInFlight = false

        fun add(resource: Closeable) = synchronized(lock) { entries += Entry(resource) }
        fun isComplete(): Boolean = synchronized(lock) { entries.all(Entry::retired) }

        override fun close() {
            val pending = synchronized(lock) {
                if (closeInFlight) throw IOException("Failed session runtime cleanup is in flight")
                closeInFlight = true
                entries.filterNot(Entry::retired)
            }
            var failure: Throwable? = null
            try {
                pending.forEach { entry ->
                    try {
                        entry.resource.close()
                        synchronized(lock) { entry.retired = true }
                    } catch (error: Throwable) {
                        failure = combineEngineFailures(failure, error)
                    }
                }
            } finally {
                synchronized(lock) { closeInFlight = false }
            }
            failure?.let { throw it }
        }
    }

    private data class ScreenDetachment(
        val owned: Boolean,
        val lifecycleOwned: Boolean = false,
        val resources: List<Closeable> = emptyList(),
        val notification: SerializedCallbackQueue.Task? = null,
        val notificationOwner: PendingScreenNotification? = null,
        val rendererBinding: UiSyncPreparedScreenBinding? = null,
    )

    /**
     * Retry owner for the two sink callbacks which retire one exact session-bound handle.
     *
     * A [SerializedCallbackQueue.Task] is deliberately one-shot: once its action throws, invoking
     * the task again only rethrows the recorded failure. Keeping the callback progress separately
     * lets a later engine/session close retry only the callback which did not complete, without
     * losing the queue ordering established by the original task.
     */
    private class PendingScreenNotification(
        val key: StreamKey,
        val handle: AirPlayVideoStreamHandle,
        private val sink: MediaSink,
    ) {
        private val lock = Any()
        private var recoveryCleared = false
        private var inactiveDelivered = false
        private var operationInFlight = false
        private var onComplete: (() -> Unit)? = null

        fun deliver() {
            synchronized(lock) {
                check(!operationInFlight) { "Screen notification is already in flight" }
                operationInFlight = true
            }
            try {
                if (!synchronized(lock) { recoveryCleared }) {
                    sink.onVideoRecoveryHandler(handle, null)
                    synchronized(lock) { recoveryCleared = true }
                }
                if (!synchronized(lock) { inactiveDelivered }) {
                    sink.onScreenStreamActive(handle, false)
                    synchronized(lock) { inactiveDelivered = true }
                }
            } finally {
                synchronized(lock) { operationInFlight = false }
            }
            synchronized(lock) { onComplete }.let { callback -> callback?.invoke() }
        }

        fun isComplete(): Boolean = synchronized(lock) {
            recoveryCleared && inactiveDelivered
        }

        fun setOnComplete(callback: () -> Unit) {
            val runNow = synchronized(lock) {
                onComplete = callback
                recoveryCleared && inactiveDelivered
            }
            if (runNow) callback()
        }
    }

    /** Exact-key retirement retained until the renderer binding has proven release. */
    private class PendingScreenRetirement(
        val key: StreamKey,
        resources: List<Closeable>,
        private val rendererBinding: UiSyncPreparedScreenBinding?,
        private val notification: SerializedCallbackQueue.Task?,
        private val notificationOwner: PendingScreenNotification?,
    ) {
        val exactHandle: AirPlayVideoStreamHandle? =
            notificationOwner?.handle ?: rendererBinding?.stream
        private val lock = Any()
        private val resources = resources.toList()
        private val closedResources = BooleanArray(resources.size)
        private var bindingRetired = rendererBinding == null
        private var notificationTaskConsumed = notification == null
        private var operationInFlight = false

        fun retire(
            deliverNotification: Boolean,
            retireBinding: Boolean = true,
        ): Throwable? {
            synchronized(lock) {
                check(!operationInFlight) { "Screen retirement is already in flight" }
                operationInFlight = true
            }
            var failure: Throwable? = null
            resources.forEachIndexed { index, resource ->
                if (!synchronized(lock) { closedResources[index] }) {
                    try {
                        resource.close()
                        synchronized(lock) { closedResources[index] = true }
                    } catch (problem: Throwable) {
                        failure = combineEngineFailures(failure, problem)
                    }
                }
            }
            if (retireBinding && failure == null && !synchronized(lock) { bindingRetired }) {
                try {
                    checkNotNull(rendererBinding).close()
                    synchronized(lock) { bindingRetired = true }
                } catch (problem: Throwable) {
                    failure = combineEngineFailures(failure, problem)
                }
            }
            if (failure == null && synchronized(lock) { bindingRetired } &&
                deliverNotification && notificationOwner?.isComplete() != true
            ) {
                try {
                    val consumeTask = synchronized(lock) { !notificationTaskConsumed }
                    if (consumeTask) {
                        checkNotNull(notification).run()
                        synchronized(lock) { notificationTaskConsumed = notification.completed }
                    } else {
                        notificationOwner?.deliver()
                    }
                    if (notification?.completed == true) {
                        synchronized(lock) { notificationTaskConsumed = true }
                    }
                } catch (problem: Throwable) {
                    if (notification?.completed == true) {
                        synchronized(lock) { notificationTaskConsumed = true }
                    }
                    failure = combineEngineFailures(failure, problem)
                }
            }
            synchronized(lock) { operationInFlight = false }
            return failure
        }

        fun isComplete(): Boolean = synchronized(lock) {
            closedResources.all { it } && bindingRetired &&
                (notificationOwner == null || notificationOwner.isComplete())
        }

        fun setNotificationCompleteCallback(callback: () -> Unit) {
            notificationOwner?.setOnComplete(callback)
        }
    }

    private class PendingAudioStop(
        val session: AirPlaySession,
        val type: Int,
        private val sink: MediaSink,
    ) {
        private val lock = Any()
        private var delivered = false
        private var operationInFlight = false

        fun retire() {
            synchronized(lock) {
                if (delivered) return
                check(!operationInFlight) { "Audio stop notification is already in flight" }
                operationInFlight = true
            }
            try {
                sink.onAudioStopped(type)
                synchronized(lock) { delivered = true }
            } finally {
                synchronized(lock) { operationInFlight = false }
            }
        }

        fun isComplete(): Boolean = synchronized(lock) { delivered }
    }

    /** Retry owner for non-screen transports (notably type-130 RCS handlers). */
    private class PendingOwnedResourceRetirement(val key: StreamKey) {
        private data class Entry(val resource: Closeable, var closed: Boolean = false)

        private val lock = Any()
        private val entries = ArrayList<Entry>()
        private val identities = java.util.IdentityHashMap<Closeable, Unit>()
        private var operationInFlight = false

        fun add(resources: Collection<Closeable>) = synchronized(lock) {
            resources.forEach { resource ->
                if (identities.put(resource, Unit) == null) entries += Entry(resource)
            }
        }

        fun retire(): Throwable? {
            val pending = synchronized(lock) {
                check(!operationInFlight) { "Owned resource retirement is already in flight" }
                operationInFlight = true
                entries.filterNot(Entry::closed)
            }
            var failure: Throwable? = null
            pending.forEach { entry ->
                try {
                    entry.resource.close()
                    synchronized(lock) { entry.closed = true }
                } catch (problem: Throwable) {
                    failure = combineEngineFailures(failure, problem)
                }
            }
            synchronized(lock) { operationInFlight = false }
            return failure
        }

        fun isComplete(): Boolean = synchronized(lock) { entries.all(Entry::closed) }
    }

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val pendingStreams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val startedMicrophone = ConcurrentHashMap.newKeySet<StreamKey>()
    private val microphoneStopsInFlight = ConcurrentHashMap.newKeySet<StreamKey>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val sessionDataRuntimes = ConcurrentHashMap<AirPlaySession, SessionDataRuntime>()
    private val failedSessionRuntimeConstructions =
        ConcurrentHashMap<AirPlaySession, PendingSessionRuntimeConstructionRetirement>()
    private val sessionRuntimeCreations = LinkedHashMap<AirPlaySession, Int>()
    private val videoSessionEpochs = ConcurrentHashMap<AirPlaySession, AirPlayVideoSessionEpoch>()
    private val videoHandles = ConcurrentHashMap<StreamKey, AirPlayVideoStreamHandle>()
    private val screenRendererBindings =
        ConcurrentHashMap<StreamKey, UiSyncPreparedScreenBinding>()
    private val pendingScreenRetirements =
        ConcurrentHashMap<StreamKey, PendingScreenRetirement>()
    private val pendingOwnedResourceRetirements =
        ConcurrentHashMap<StreamKey, PendingOwnedResourceRetirement>()
    private val expectedDataClosures = ConcurrentHashMap.newKeySet<StreamKey>()
    private val stoppingMainBufferedSessions = ConcurrentHashMap.newKeySet<AirPlaySession>()
    /** Exact product transaction origins, consumed only by a quiescent retirement receipt. */
    private val mainBufferedOwnerOrigins =
        IdentityHashMap<MainBufferedV27SetupTransaction, StreamKey>()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)
    private val closeCompleted = AtomicBoolean(false)
    private val closeAttemptInFlight = AtomicBoolean(false)
    private val pendingLegacyScreenHandles =
        LinkedHashMap<AirPlayVideoStreamHandle, PendingScreenNotification>()
    private val pendingAudioStopTypes =
        LinkedHashMap<Pair<AirPlaySession, Int>, PendingAudioStop>()
    private val terminalScreenSessions = LinkedHashSet<AirPlaySession>()
    private var ownerOperationsInFlight = 0
    private var ownerAdmissionsSealed = false
    private val screenLifecycles = ScreenStreamLifecycleStateMachine<
        StreamKey,
        Closeable,
        AirPlayVideoStreamHandle,
    >(lifecycleLock) { key ->
        closed.get() || key.session.isClosed || key.session in terminalScreenSessions
    }
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler.takeUnless { closed.get() }
    }

    override fun prepareScreen(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? {
        if (!beginOwnerOperation()) return null
        return try {
            prepareScreenAdmitted(session, identity, stream)
        } finally {
            endOwnerOperation()
        }
    }

    private fun prepareScreenAdmitted(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? {
        if (closed.get() || session.isClosed) return null
        val type = identity.type
        val key = outputKey(session, stream) ?: return null
        val bindAddress = session.localAddress?.takeUnless { it.isAnyLocalAddress } ?: return null
        val expectedPeerAddress = session.remoteAddress?.takeUnless { it.isAnyLocalAddress }
            ?: return null
        val streamKey = StreamKey(session, identity)
        val handle = videoHandle(session, identity, stream) ?: return null
        val rendererBinding = try {
            sessionRuntime(session)?.ultra?.prepareScreen(handle)
        } catch (failure: Throwable) {
            stageLegacyScreenHandle(streamKey, handle)
            retryLegacyScreenHandles(session)?.let { cleanupFailure ->
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        Log.i(TAG, "airplay screen key prepared type=$type")
        val screen = ScreenStream(key, bindAddress, expectedPeerAddress, screenType = type)
        val lifecycle = screenLifecycles.create(streamKey, screen, handle)
        if (!installPendingScreenWithRenderer(lifecycle, rendererBinding)) {
            // Rejection can race an exact-key teardown. Register every newly-created owner before
            // invoking any close/callback so a transient cleanup failure cannot orphan it or
            // accidentally detach the older stream which caused this install to be rejected.
            stageAdditionalOwnedResourceRetirement(
                streamKey,
                listOfNotNull(screen, rendererBinding),
            )
            stageLegacyScreenHandle(streamKey, handle)
            var cleanupFailure = retireOwnedResources(session)
            retryLegacyScreenHandles(session)?.let { failure ->
                cleanupFailure = combineEngineFailures(cleanupFailure, failure)
            }
            cleanupFailure?.let { throw it }
            return null
        }
        var streamCodec = VideoCodec.H264
        var firstFrameLogged = false
        val port = try {
            screen.listen(
                object : ScreenStream.Listener {
                    override fun onCodec(codec: VideoCodec) {
                        streamCodec = codec
                        sink.onVideoCodec(handle, codec)
                    }
                    override fun onPacket(
                        sequence: Long,
                        header: ByteArray,
                        wire: ByteArray,
                        plain: ByteArray?,
                    ) {
                        videoPacketLog?.invoke(type, streamCodec, sequence, header, wire, plain)
                    }
                    override fun onConfig(codecData: ByteArray) {
                        session.logDebug(
                            "airplay screen config type=$type codec=$streamCodec " +
                                "bytes=${codecData.size}",
                        )
                        sink.onVideoConfig(handle, codecData)
                    }
                    override fun onFrame(naluBytes: ByteArray, timing: ScreenFrameTiming) {
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            session.logDebug(
                                "airplay screen first frame type=$type codec=$streamCodec " +
                                    "bytes=${naluBytes.size}",
                            )
                        }
                        forwardVideoFrame(
                            handle,
                            naluBytes,
                            timing,
                            session.presentationMonotonicNanos(timing),
                        )
                    }
                    override fun onClosed(cause: Throwable?) {
                        Log.w(
                            TAG,
                            "screen stream ended type=$type " +
                                "reason=${cause?.message ?: "peer EOF"}",
                        )
                        closeScreenFromListener(lifecycle)
                    }
                },
            )
        } catch (failure: Throwable) {
            rollbackScreen(lifecycle)
            throw failure
        }
        return AirPlayPreparedMediaStream(
            identity = identity,
            response = linkedMapOf("type" to type, "dataPort" to port),
            commitHook = {
                withOwnerOperationOr(false) { promotePendingScreen(lifecycle) }
            },
            rollbackHook = {
                withOwnerOperationOr(Unit) { rollbackScreen(lifecycle) }
            },
        )
    }

    internal fun forwardVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) {
        sink.onVideoFrame(handle, naluBytes, timing)
    }

    internal fun forwardVideoFrame(
        identity: AirPlayStreamIdentity,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) {
        sink.onVideoFrame(identity, naluBytes, timing)
    }

    internal fun forwardVideoFrame(
        type: Int,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
    ) {
        forwardVideoFrame(AirPlayStreamIdentity(type, null, null), naluBytes, timing)
    }

    internal fun forwardVideoFrame(
        handle: AirPlayVideoStreamHandle,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        sink.onVideoFrame(handle, naluBytes, timing, presentationTargetMonotonicNanos)
    }

    internal fun forwardVideoFrame(
        identity: AirPlayStreamIdentity,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        sink.onVideoFrame(identity, naluBytes, timing, presentationTargetMonotonicNanos)
    }

    internal fun forwardVideoFrame(
        type: Int,
        naluBytes: ByteArray,
        timing: ScreenFrameTiming,
        presentationTargetMonotonicNanos: Long?,
    ) {
        forwardVideoFrame(
            AirPlayStreamIdentity(type, null, null),
            naluBytes,
            timing,
            presentationTargetMonotonicNanos,
        )
    }

    override fun onScreen(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
    ): Int? {
        val opened = prepareScreen(session, AirPlayStreamIdentity.from(type, stream), stream)
            ?: return null
        if (!opened.commit()) return null
        return (opened.response["dataPort"] as? Number)?.toInt()
    }

    override fun prepareMainBuffered(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMainBufferedStream? {
        val preparer = mainBufferedProductPreparer ?: return null
        if (!beginOwnerOperation()) return null
        return try {
            if (closed.get() || session.isClosed || session in stoppingMainBufferedSessions) {
                return null
            }
            val transaction = preparer.prepare(session, identity, stream) ?: return null
            val key = StreamKey(session, identity)
            if (!installPending(key, transaction) || session in stoppingMainBufferedSessions) {
                // No response can be published for an unowned or stopping transaction.
                pendingStreams.remove(key, transaction)
                // A failed close must remain in the exact-key retry registry. Do not detach the
                // accepted transaction for this same key when rejecting a second prepare.
                stageAdditionalOwnedResourceRetirement(key, listOf(transaction))
                retireOwnedResources(session)?.let { throw it }
                null
            } else {
                synchronized(lifecycleLock) { mainBufferedOwnerOrigins[transaction] = key }
                AirPlayPreparedMainBufferedStream(transaction)
            }
        } finally {
            endOwnerOperation()
        }
    }

    /**
     * Exact engine-side retirement proof for a type-103 transaction already closed by its
     * AirPlaySession. A successful `onTeardown` alone is not sufficient: rejected constructions
     * and failed close retries can retain an independent native output or socket owner.
     */
    override fun isMainBufferedOwnerQuiescent(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        prepared: AirPlayPreparedMainBufferedStream,
    ): Boolean {
        val transaction = prepared.transaction
        if (identity.type != 103 || transaction.state() != MainBufferedV27SetupState.CLOSED) {
            return false
        }
        val key = StreamKey(session, identity)
        val noEngineOwner = synchronized(lifecycleLock) {
            mainBufferedOwnerOrigins[transaction] == key &&
                !pendingStreams.containsKey(key) && !streams.containsKey(key) &&
                !pendingOwnedResourceRetirements.containsKey(key) &&
                !audioCaptures.containsKey(key) && !audioMeta.containsKey(key)
        }
        if (!noEngineOwner ||
            mainBufferedProductPreparer?.hasPendingRetirements(session) != false ||
            session in stoppingMainBufferedSessions || closed.get()
        ) return false
        return synchronized(lifecycleLock) {
            if (mainBufferedOwnerOrigins[transaction] != key ||
                pendingStreams.containsKey(key) || streams.containsKey(key) ||
                pendingOwnedResourceRetirements.containsKey(key)
            ) false else {
                mainBufferedOwnerOrigins.remove(transaction)
                true
            }
        }
    }

    override fun prepareAudio(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? {
        if (!beginOwnerOperation()) return null
        return try {
            prepareAudioAdmitted(session, identity, stream)
        } finally {
            endOwnerOperation()
        }
    }

    private fun prepareAudioAdmitted(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? {
        if (closed.get() || session.isClosed) return null
        val type = identity.type
        val streamKey = StreamKey(session, identity)
        val key = outputKey(session, stream) ?: return null
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        val format = AudioStreamCodec.fromFormatBits(
            (stream["audioFormat"] as? Number)?.toLong() ?: 0L,
            type,
            audioType,
        )
        runCatching {
            Log.i(
                TAG,
                "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                    "rate=${format.sampleRate} channels=${format.channels} " +
                    "micPort=${(stream["dataPort"] as? Number)?.toInt() ?: 0}",
            )
        }
        val connectionId = stream["streamConnectionID"]
        val latencyMs = (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, type, stream, format)

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        val audio = AudioStream(key, type)
        val resource = Closeable {
            var failure: Throwable? = null
            try {
                audio.close()
            } catch (problem: Throwable) {
                failure = combineEngineFailures(failure, problem)
            }
            try {
                capture?.close()
            } catch (problem: Throwable) {
                failure = combineEngineFailures(failure, problem)
            }
            failure?.let { throw it }
        }
        val (dataPort, controlPort) = try {
            audio.listen(
                object : AudioStream.Listener {
                    override fun onStarted(firstSample: Int) {
                        meta.firstSample = firstSample
                        meta.originNs = System.nanoTime()
                        sink.onAudioStarted(type, format, firstSample)
                    }

                    override fun onRtp(rtp: ByteArray, sample: Int) =
                        sink.onAudioRtp(type, format, rtp, sample)

                    override fun onPacket(
                        wire: ByteArray,
                        rtp: ByteArray?,
                        sample: Int?,
                        error: Throwable?,
                    ) {
                        capture?.record(wire, rtp, sample, error)
                    }
                },
            )
        } catch (failure: Throwable) {
            // listen() can fail after binding endpoints/starting workers, and its own best-effort
            // cleanup is not retirement proof. Transfer the exact AudioStream/capture owner to
            // the engine before preserving and rethrowing the original startup failure/Error.
            stageAdditionalOwnedResourceRetirement(streamKey, listOf(resource))
            retireOwnedResources(session)?.let { cleanupFailure ->
                if (cleanupFailure is Error && failure !is Error) {
                    if (cleanupFailure !== failure) cleanupFailure.addSuppressed(failure)
                    throw cleanupFailure
                }
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        if (!installPending(streamKey, resource)) {
            stageAdditionalOwnedResourceRetirement(streamKey, listOf(resource))
            retireOwnedResources(session)?.let { throw it }
            return null
        }
        return AirPlayPreparedMediaStream(
            identity = identity,
            response = linkedMapOf(
                "type" to type,
                "dataPort" to dataPort,
                "controlPort" to controlPort,
                "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L),
            ),
            commitHook = {
                withOwnerOperationOr(false) {
                    if (!promotePending(streamKey, resource)) {
                        false
                    } else {
                        stopMicrophone(streamKey)
                        audioMeta.remove(streamKey)
                        audioCaptures.remove(streamKey)?.close()
                        audioMeta[streamKey] = meta
                        if (capture != null) audioCaptures[streamKey] = capture
                        if (microphone != null && startedMicrophone.add(streamKey)) {
                            sink.onMicrophoneStarted(type, microphone)
                        }
                        true
                    }
                }
            },
            rollbackHook = {
                withOwnerOperationOr(Unit) {
                    // An engine/session teardown may already have consumed and proven this exact
                    // installed owner. Never recreate it after that proof.
                    if (stageOwnedResourceRetirement(streamKey) != null) {
                        retireOwnedResources(session)?.let { throw it }
                    }
                }
            },
        )
    }

    override fun onAudio(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
    ): Map<String, Any?>? {
        val opened = prepareAudio(session, AirPlayStreamIdentity.from(type, stream), stream)
            ?: return null
        return opened.response.takeIf { opened.commit() }
    }

    override fun isRcsReady(
        session: AirPlaySession,
        clientTypes: Collection<RcsClientType>,
    ): Boolean = withOwnerOperationOr(false) {
        !closed.get() &&
            (RcsClientTypes.CAR_PLAY_MATCH_POINT !in clientTypes ||
                session.resolvedConfig.ultra?.isConfigured(AirPlayFeature.VIDEO_PLAYBACK) == true) &&
            sessionRuntime(session)?.handlers?.rcs?.let { factory ->
                clientTypes.all { clientType ->
                    runCatching {
                        factory.descriptor(clientType)
                            ?.takeIf { it.clientType == clientType }
                            ?.probe()
                            ?.isReady == true
                    }.getOrDefault(false)
                }
            } == true
    }

    override fun sendUiSyncCommand(
        session: AirPlaySession,
        displayId: String,
        command: CarPlayUiSyncCommandType,
        data: Any?,
        noAck: Boolean,
    ): Boolean = !closed.get() && sessionDataRuntimes[session]?.ultra
        ?.sendUiSyncCommand(displayId, command, data, noAck) == true

    override fun drainUiSyncDiagnostics(
        session: AirPlaySession,
    ): List<CarPlayUiSyncRoutingDiagnostic> = sessionDataRuntimes[session]?.ultra
        ?.drainUiSyncDiagnostics()
        .orEmpty()

    override fun prepareDataStream(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        clientType: RcsClientType,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? {
        if (!beginOwnerOperation()) return null
        return try {
            prepareDataStreamAdmitted(session, identity, clientType, stream)
        } finally {
            endOwnerOperation()
        }
    }

    private fun prepareDataStreamAdmitted(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        clientType: RcsClientType,
        stream: Map<String, Any?>,
    ): AirPlayPreparedMediaStream? {
        if (closed.get() || session.isClosed) return null
        if (clientType == RcsClientTypes.CAR_PLAY_MATCH_POINT &&
            session.resolvedConfig.ultra?.isConfigured(AirPlayFeature.VIDEO_PLAYBACK) != true
        ) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        val remote = session.remoteAddress ?: return null
        // A type-130 listener must never widen an accepted control connection to every interface.
        val bindAddress = session.localAddress?.takeUnless { it.isAnyLocalAddress } ?: return null
        val runtime = sessionRuntime(session) ?: return null
        val streamKey = StreamKey(session, identity)
        expectedDataClosures.remove(streamKey)
        if (clientType != RcsClientTypes.IAP_CHANNEL &&
            !runCatching {
                runtime.handlers.rcs?.descriptor(clientType)
                    ?.takeIf { it.clientType == clientType }
                    ?.probe()
                    ?.isReady == true
            }.getOrDefault(false)
        ) {
            return null
        }
        session.logDebug("AirPlay DataStream SETUP prepared type=${identity.type}")
        val context = CarPlayDataStreamConnectionContext(
            sharedSecret = shared,
            bindAddress = bindAddress,
            expectedPeerAddress = remote,
            iapBridgeFactory = CarPlayIapBridgeFactory { tunnel ->
                val bridge = AirPlayIapTunnelStream(session, tunnel)
                OpenedCarPlayIapBridge(bridge.listen(), bridge)
            },
            debugLog = session::logDebug,
            traceLog = session::logTrace,
            onTransportFailure = {
                if (!expectedDataClosures.remove(streamKey)) session.close()
            },
        )
        val opened = CarPlayDataStreamFactory().open(context, clientType, seed, runtime.handlers)
            ?: return null
        if (clientType == RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL) {
            runtime.ultra?.debugStatus(UltraDebugStatusEvent.CLUSTER_LISTENING)
        }
        if (!installPending(streamKey, opened.closeable)) {
            rollbackDataStream(streamKey, opened, installed = false)
            return null
        }
        val response = linkedMapOf<String, Any?>(
            "type" to STREAM_TYPE_DATA,
            "streamID" to 1L,
            "dataPort" to opened.port,
        ).apply {
            stream["streamConnectionID"]?.let { connectionId ->
                this["streamConnectionID"] = unsignedPlistInteger(connectionId)
            }
        }
        return AirPlayPreparedMediaStream(
            identity = identity,
            response = response,
            commitHook = {
                withOwnerOperationOr(false) {
                    if (pendingStreams[streamKey] !== opened.closeable) {
                        rollbackDataStream(streamKey, opened, installed = true)
                        false
                    } else if (!opened.commit()) {
                        rollbackDataStream(streamKey, opened, installed = true)
                        false
                    } else if (!promotePending(streamKey, opened.closeable)) {
                        rollbackDataStream(streamKey, opened, installed = true)
                        false
                    } else {
                        true
                    }
                }
            },
            rollbackHook = {
                withOwnerOperationOr(Unit) {
                    expectedDataClosures += streamKey
                    rollbackDataStream(streamKey, opened, installed = true)
                }
            },
        )
    }

    override fun onDataStream(
        session: AirPlaySession,
        stream: Map<String, Any?>,
    ): Map<String, Any?>? {
        val clientType = (stream["clientTypeUUID"] as? String)
            ?.let(RcsClientTypes::findByUuid)
            ?: return null
        val identity = AirPlayStreamIdentity.from(STREAM_TYPE_DATA, stream)
        val opened = prepareDataStream(session, identity, clientType, stream) ?: return null
        return opened.response.takeIf { opened.commit() }
    }

    override fun onSetupResponseSent(session: AirPlaySession) = Unit

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        if (closed.get()) return null
        val active = audioMeta.entries
            .filter { it.key.session === session }
            .map { it.value }
        if (active.isEmpty()) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        }
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (!beginOwnerOperation()) return
        try {
            onTeardownAdmitted(session, type)
        } finally {
            endOwnerOperation()
        }
    }

    private fun onTeardownAdmitted(session: AirPlaySession, type: Int) {
        val matching = synchronized(lifecycleLock) {
            (
                screenLifecycles.keys() + pendingStreams.keys + streams.keys +
                    pendingScreenRetirements.keys + pendingOwnedResourceRetirements.keys +
                    startedMicrophone +
                    synchronized(lifecycleLock) {
                        pendingLegacyScreenHandles.values.map(PendingScreenNotification::key)
                    }
            )
                .filter { it.session === session && it.type == type }
                .distinct()
        }
        matching.forEach { onTeardownAdmitted(session, it.identity) }
        // Preserve the legacy type-only callback contract for callers that use teardown as a
        // renderer reset even when no listener was installed.
        if (matching.isEmpty() && isScreenStreamType(type)) {
            sink.onVideoRecoveryHandler(type, null)
            sink.onScreenStreamActive(type, false)
        }
    }

    override fun onTeardown(session: AirPlaySession, identity: AirPlayStreamIdentity) {
        if (!beginOwnerOperation()) return
        try {
            onTeardownAdmitted(session, identity)
        } finally {
            endOwnerOperation()
        }
    }

    private fun onTeardownAdmitted(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
    ) {
        val key = StreamKey(session, identity)
        if (isScreenStreamType(identity.type)) {
            val teardownHandle = videoHandles[key] ?: pendingScreenRetirements[key]?.exactHandle
            val retrying = pendingScreenRetirements.containsKey(key)
            val retryingLegacy = synchronized(lifecycleLock) {
                pendingLegacyScreenHandles.values.any { it.key == key }
            }
            val detachment = detachScreen(key)
            if (detachment.owned) {
                retireScreenDetachment(key, detachment, deliverNotification = true)
            } else if (retrying) {
                pendingScreenRetirements[key]?.let { retirement ->
                    retireStagedScreen(key, retirement, deliverNotification = true)
                }
            }
            if (retrying) {
                teardownHandle?.let { sessionDataRuntimes[session]?.ultra
                    ?.finishMainScreenTeardown(it) }
                return
            }
            if (retryingLegacy) {
                retryLegacyScreenHandles(session, exactKey = key)?.let { throw it }
                teardownHandle?.let { sessionDataRuntimes[session]?.ultra
                    ?.finishMainScreenTeardown(it) }
                return
            }
            when {
                detachment.lifecycleOwned -> Unit
                detachment.notificationOwner != null -> Unit
                else -> {
                    // A type/identity-only caller cannot identify a session-bound renderer handle.
                    sink.onVideoRecoveryHandler(identity, null)
                    sink.onScreenStreamActive(identity, false)
                }
            }
            teardownHandle?.let { sessionDataRuntimes[session]?.ultra
                ?.finishMainScreenTeardown(it) }
            return
        }

        val videoHandle = videoHandles.remove(key)
        if (identity.type == STREAM_TYPE_DATA) expectedDataClosures += key
        stageOwnedResourceRetirement(key)
        retireOwnedResources(session)?.let { throw it }
        stopMicrophone(key)
        audioMeta.remove(key)
        val stillActiveType = streams.keys.any { it.session === session && it.type == identity.type } ||
            pendingOwnedResourceRetirements.keys.any {
                it.session === session && it.type == identity.type
            }
        if (!stillActiveType && identity.type in STREAM_TYPE_MAIN_AUDIO..STREAM_TYPE_MAIN_HIGH_AUDIO) {
            stageAudioStop(session, identity.type)
            retryAudioStops(session)?.let { throw it }
        }
        if (videoHandle != null) {
            stageLegacyScreenHandle(key, videoHandle)
            retryLegacyScreenHandles(session)?.let { throw it }
        }
    }

    override fun onSessionStopping(session: AirPlaySession) {
        // AirPlaySession has already published STOPPING. No engine lifecycle lock is held while
        // the independent physical output owner covers its scanout.
        val runtime = sessionDataRuntimes[session]
        runtime?.ultra?.debugSessionStatus(UltraDebugStatusEvent.SESSION_STOPPING)
        stoppingMainBufferedSessions += session
        var failure: Throwable? = null
        if (!session.coverRegisteredPhysicalStopOwners()) {
            failure = IOException("Exact in-flight Ultra physical cover is pending")
        }
        try {
            check(runtime?.ultra?.coverForSessionStop() != false) {
                "Exact Ultra MAIN/CLUSTER session cover was not acknowledged"
            }
        } catch (problem: Throwable) {
            failure = combineEngineFailures(failure, problem)
        }
        try {
            runtime?.matchPoint?.coverImmediatelyOnStop()
        } catch (problem: Throwable) {
            failure = combineEngineFailures(failure, problem)
        }
        // Revoke the detached type-103 owner before AirPlaySession waits to join a transport.
        // The transaction's close also retires its exact PCM output and M4 lease.
        (pendingStreams.entries + streams.entries)
            .filter { it.key.session === session && it.key.type == 103 }
            .forEach { (_, resource) ->
                try { resource.close() } catch (problem: Throwable) {
                    failure = combineEngineFailures(failure, problem)
                }
            }
        mainBufferedProductPreparer?.retireFailedPreparations(session)?.let { problem ->
            failure = combineEngineFailures(failure, problem)
        }
        retireFailedSessionRuntimeConstructions(session)?.let { problem ->
            failure = combineEngineFailures(failure, problem)
        }
        failure?.let { throw it }
    }

    override fun beforeScreenTeardown(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
    ): Boolean {
        if (identity.type != STREAM_TYPE_MAIN_SCREEN) return true
        // This callback is made while AirPlaySession still owns the active exact selector.
        // Neither the stream map nor its decoder may be touched before the Host cover ACK.
        if (failedSessionRuntimeConstructions.containsKey(session) ||
            synchronized(lifecycleLock) { (sessionRuntimeCreations[session] ?: 0) > 0 }
        ) return false
        val runtime = sessionDataRuntimes[session]?.ultra ?: return true
        val key = StreamKey(session, identity)
        val handle = videoHandles[key] ?: pendingScreenRetirements[key]?.exactHandle
            ?: return false
        return runtime.coverMainForExactScreenTeardown(handle)
    }

    override fun onSessionClosed(session: AirPlaySession) {
        if (!beginOwnerOperation()) {
            // The engine may be sealed while another media close is in flight or has failed.
            // Only its completed global retirement proves all exact-session screen/audio/RCS
            // owners gone; a missing MatchPoint map entry alone is insufficient.
            if (!closeCompleted.get()) throw IOException("AirPlay media engine retirement is pending")
            return
        }
        try {
            onSessionClosedAdmitted(session)
        } finally {
            endOwnerOperation()
        }
    }

    private fun onSessionClosedAdmitted(session: AirPlaySession) {
        if (synchronized(lifecycleLock) { ownerOperationsInFlight != 1 }) {
            throw IOException("AirPlay media owner operation is in flight")
        }
        // A /info readiness probe may still be inside computeIfAbsent, before its owner appears
        // in the map. Do not let AirPlaySession publish mediaRetired until that exact construction
        // and any late close attempt finish. Reentrant close from the provider must defer, not wait.
        if (synchronized(lifecycleLock) { (sessionRuntimeCreations[session] ?: 0) > 0 }) {
            throw IOException("AirPlay session data runtime construction is in flight")
        }
        mainBufferedProductPreparer?.retireFailedPreparations(session)?.let { throw it }
        retireFailedSessionRuntimeConstructions(session)?.let { throw it }
        // Linearize terminal intent before detaching each listener. Even if a close callback is
        // currently queued behind promotion, no remaining pending listener can become active.
        val preexistingScreenRetirements = pendingScreenRetirements.keys
            .filterTo(linkedSetOf()) { it.session === session }
        val lifecycleKeys = synchronized(lifecycleLock) {
            terminalScreenSessions += session
            screenLifecycles.keys().filter { it.session === session }
        }
        val lifecycleDetachments = lifecycleKeys.map { key -> detachScreen(key) }

        // AirPlaySession marks itself closed before this callback, so state-less legacy entries
        // cannot be reinserted while the snapshot is drained outside the lock.
        val (keys, legacyScreenHandles) = synchronized(lifecycleLock) {
            val ownedKeys = (
                pendingStreams.keys + streams.keys + pendingOwnedResourceRetirements.keys +
                    audioCaptures.keys + audioMeta.keys + startedMicrophone + videoHandles.keys
            )
                .filter { it.session === session }
                .distinct()
                .sortedByDescending { it.type == STREAM_TYPE_DATA }
            val ownedHandles = ownedKeys.mapNotNull { key ->
                videoHandles.remove(key)?.let { handle -> key to handle }
            }
            videoSessionEpochs.remove(session)
            ownedKeys to ownedHandles
        }
        val audioTypes = keys.map(StreamKey::type)
            .filter { it in STREAM_TYPE_MAIN_AUDIO..STREAM_TYPE_MAIN_HIGH_AUDIO }
            .toSet()
        keys.forEach { key ->
            if (key.type == STREAM_TYPE_DATA) expectedDataClosures += key
            stageOwnedResourceRetirement(key)
            audioMeta.remove(key)
        }
        legacyScreenHandles.forEach { (key, handle) ->
            stageLegacyScreenHandle(key, handle)
        }
        audioTypes.forEach { type -> stageAudioStop(session, type) }
        var screenTransportFailure: Throwable? = null
        lifecycleKeys.zip(lifecycleDetachments).forEach { (retirementKey, detachment) ->
            runCatching {
                retireScreenDetachment(
                    retirementKey,
                    detachment,
                    deliverNotification = false,
                    retireBinding = false,
                )
            }.exceptionOrNull()?.let { error ->
                screenTransportFailure = combineEngineFailures(screenTransportFailure, error)
            }
        }
        var rendererFailure: Throwable? = null
        retireOwnedResources(session)?.let { error ->
            rendererFailure = combineEngineFailures(rendererFailure, error)
        }
        if (rendererFailure == null && screenTransportFailure == null) {
            retryScreenTransportRetirements(preexistingScreenRetirements)?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
        }
        screenTransportFailure?.let { error ->
            rendererFailure = combineEngineFailures(rendererFailure, error)
        }
        val dataRuntime = sessionDataRuntimes[session]
        if (rendererFailure == null) {
            runCatching { dataRuntime?.close() }.exceptionOrNull()?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
        }
        if (rendererFailure == null) {
            retryScreenRetirements(
                session,
                deliverNotifications = true,
                retireBindings = true,
            )?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
        }
        if (rendererFailure == null) {
            if (dataRuntime != null) sessionDataRuntimes.remove(session, dataRuntime)
            keys.forEach(::stopMicrophone)
            retryLegacyScreenHandles(session)?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
            retryAudioStops(session)?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
        }
        if (rendererFailure == null) {
            if (synchronized(lifecycleLock) {
                    (sessionRuntimeCreations[session] ?: 0) > 0 || ownerOperationsInFlight != 1
                }) {
                rendererFailure = IOException("AirPlay session media construction is in flight")
            }
        }
        if (rendererFailure == null) {
            synchronized(lifecycleLock) { terminalScreenSessions.remove(session) }
        }
        expectedDataClosures.filter { it.session === session }
            .forEach(expectedDataClosures::remove)
        rendererFailure?.let { throw it }
        dataRuntime?.ultra?.debugSessionStatus(UltraDebugStatusEvent.SESSION_CLOSED)
        synchronized(lifecycleLock) {
            mainBufferedOwnerOrigins.entries.removeAll { it.value.session === session }
        }
        stoppingMainBufferedSessions.remove(session)
    }

    /**
     * Releases process-owned listeners and the shared Ultra provisioning after the controller has
     * detached every AirPlay session. Idempotence is required because session callbacks can race
     * controller teardown.
     */
    override fun close() {
        closed.set(true)
        if (closeCompleted.get()) return
        check(closeAttemptInFlight.compareAndSet(false, true)) {
            "CarPlay media engine close is already in flight"
        }
        if (closeCompleted.get()) {
            closeAttemptInFlight.set(false)
            return
        }
        var rendererFailure: Throwable? = null
        try {
            val operationsInFlight = synchronized(lifecycleLock) {
                ownerAdmissionsSealed = true
                ownerOperationsInFlight
            }
            check(operationsInFlight == 0) {
                "CarPlay media engine owner operation is still in flight"
            }
            iapTunnelHandler = null
            mainBufferedProductPreparer?.retireFailedPreparations()?.let { problem ->
                rendererFailure = combineEngineFailures(rendererFailure, problem)
            }
            val preexistingScreenRetirements = pendingScreenRetirements.keys.toSet()
            val lifecycleKeys = screenLifecycles.keys()
            val lifecycleDetachments = lifecycleKeys.map { key -> detachScreen(key) }
            var screenTransportFailure: Throwable? = null
            lifecycleKeys.zip(lifecycleDetachments).forEach { (key, detachment) ->
                runCatching {
                    retireScreenDetachment(
                        key,
                        detachment,
                        deliverNotification = false,
                        retireBinding = false,
                    )
                }.exceptionOrNull()?.let { error ->
                    screenTransportFailure = combineEngineFailures(screenTransportFailure, error)
                }
            }

            val (keys, legacyHandles) = synchronized(lifecycleLock) {
                val ownedKeys = (
                    pendingStreams.keys + streams.keys + pendingOwnedResourceRetirements.keys +
                        audioCaptures.keys + audioMeta.keys + startedMicrophone
                ).distinct()
                val handles = videoHandles.entries.map { (key, handle) -> key to handle }
                videoHandles.clear()
                videoSessionEpochs.clear()
                ownedKeys to handles
            }
            legacyHandles.forEach { (key, handle) ->
                stageLegacyScreenHandle(key, handle)
            }
            keys.asSequence()
                .filter { it.type in STREAM_TYPE_MAIN_AUDIO..STREAM_TYPE_MAIN_HIGH_AUDIO }
                .map { it.session to it.type }
                .distinct()
                .forEach { (session, type) -> stageAudioStop(session, type) }
            keys.forEach { key ->
                if (key.type == STREAM_TYPE_DATA) expectedDataClosures += key
                stageOwnedResourceRetirement(key)
                audioMeta.remove(key)
            }

            retireOwnedResources()?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
            if (rendererFailure == null && screenTransportFailure == null) {
                retryScreenTransportRetirements(preexistingScreenRetirements)?.let { error ->
                    rendererFailure = combineEngineFailures(rendererFailure, error)
                }
            }
            screenTransportFailure?.let { error ->
                rendererFailure = combineEngineFailures(rendererFailure, error)
            }
            if (rendererFailure == null) {
                retireFailedSessionRuntimeConstructions()?.let { error ->
                    rendererFailure = combineEngineFailures(rendererFailure, error)
                }
            }
            if (rendererFailure == null) {
                sessionDataRuntimes.entries.toList().forEach { (session, runtime) ->
                    val failure = runCatching { runtime.close() }.exceptionOrNull()
                    if (failure == null) {
                        sessionDataRuntimes.remove(session, runtime)
                    } else {
                        rendererFailure = combineEngineFailures(rendererFailure, failure)
                    }
                }
            }
            if (rendererFailure == null && sessionDataRuntimes.isEmpty() &&
                failedSessionRuntimeConstructions.isEmpty()
            ) {
                retryScreenRetirements(
                    deliverNotifications = true,
                    retireBindings = true,
                )?.let { error ->
                    rendererFailure = combineEngineFailures(rendererFailure, error)
                }
            }
            if (rendererFailure == null && pendingScreenRetirements.isEmpty() &&
                pendingOwnedResourceRetirements.isEmpty() && sessionDataRuntimes.isEmpty() &&
                failedSessionRuntimeConstructions.isEmpty()
            ) {
                keys.forEach { key ->
                    runCatching { stopMicrophone(key) }.exceptionOrNull()?.let { error ->
                        rendererFailure = combineEngineFailures(rendererFailure, error)
                    }
                }
                retryLegacyScreenHandles()?.let { error ->
                    rendererFailure = combineEngineFailures(rendererFailure, error)
                }
                retryAudioStops()?.let { error ->
                    rendererFailure = combineEngineFailures(rendererFailure, error)
                }
            }
            if (rendererFailure == null && pendingScreenRetirements.isEmpty() &&
                pendingOwnedResourceRetirements.isEmpty() && sessionDataRuntimes.isEmpty() &&
                failedSessionRuntimeConstructions.isEmpty() &&
                synchronized(lifecycleLock) {
                    pendingLegacyScreenHandles.isEmpty() && pendingAudioStopTypes.isEmpty()
                } && startedMicrophone.isEmpty()
            ) {
                runCatching { ultraRuntime?.close() }.exceptionOrNull()?.let { error ->
                    rendererFailure = combineEngineFailures(rendererFailure, error)
                }
            }
            expectedDataClosures.clear()
            if (rendererFailure == null) stoppingMainBufferedSessions.clear()
            if (rendererFailure == null && pendingScreenRetirements.isEmpty() &&
                pendingOwnedResourceRetirements.isEmpty() && sessionDataRuntimes.isEmpty() &&
                failedSessionRuntimeConstructions.isEmpty() &&
                synchronized(lifecycleLock) {
                    pendingLegacyScreenHandles.isEmpty() && pendingAudioStopTypes.isEmpty()
                } && startedMicrophone.isEmpty()
            ) {
                synchronized(lifecycleLock) { mainBufferedOwnerOrigins.clear() }
                closeCompleted.set(true)
            }
        } finally {
            closeAttemptInFlight.set(false)
        }
        rendererFailure?.let { throw it }
    }

    private fun installPending(key: StreamKey, resource: Closeable): Boolean {
        return synchronized(lifecycleLock) {
            if (closed.get() || key.session.isClosed) {
                false
            } else if (pendingOwnedResourceRetirements.containsKey(key) ||
                pendingScreenRetirements.containsKey(key)
            ) {
                false
            } else if (pendingStreams.containsKey(key) || streams.containsKey(key)) {
                false
            } else {
                pendingStreams[key] = resource
                true
            }
        }
    }

    private fun installPendingScreen(lifecycle: EngineScreenLifecycle): Boolean =
        installPendingScreenWithRenderer(lifecycle, null)

    private fun installPendingScreenWithRenderer(
        lifecycle: EngineScreenLifecycle,
        rendererBinding: UiSyncPreparedScreenBinding?,
    ): Boolean =
        screenLifecycles.install(lifecycle) {
            val key = lifecycle.key
            if (pendingStreams.containsKey(key) || streams.containsKey(key) ||
                videoHandles.containsKey(key) || screenRendererBindings.containsKey(key) ||
                pendingScreenRetirements.containsKey(key) ||
                pendingOwnedResourceRetirements.containsKey(key) ||
                pendingLegacyScreenHandles.values.any { it.key == key }
            ) {
                false
            } else {
                pendingStreams[key] = lifecycle.resource
                videoHandles[key] = lifecycle.handle
                rendererBinding?.let { screenRendererBindings[key] = it }
                true
            }
        }

    private fun promotePendingScreen(lifecycle: EngineScreenLifecycle): Boolean {
        val key = lifecycle.key
        val rendererBinding = screenRendererBindings[key]
        if (rendererBinding != null && !rendererBinding.commit()) {
            runCatching { rendererBinding.close() }
            return false
        }
        val task = screenLifecycles.promote(
            lifecycle,
            promoteStateLocked = {
                if (pendingStreams[key] !== lifecycle.resource ||
                    streams.containsKey(key) || videoHandles[key] !== lifecycle.handle
                ) {
                    false
                } else {
                    pendingStreams.remove(key, lifecycle.resource)
                    streams[key] = lifecycle.resource
                    true
                }
            },
            notifyActive = {
                // The no-display-UUID forceKeyFrame command targets the primary stream.
                if (key.type == STREAM_TYPE_MAIN_SCREEN) {
                    sink.onVideoRecoveryHandler(lifecycle.handle) {
                        key.session.sendCommand(mainScreenKeyFrameCommand())
                    }
                }
                sink.onScreenStreamActive(lifecycle.handle, true)
            },
        ) ?: run {
            rendererBinding?.close()
            return false
        }

        // Renderer callbacks can re-enter session teardown and must never run under lifecycleLock.
        return try {
            task.run()
            true
        } catch (failure: Throwable) {
            runCatching { rollbackScreen(lifecycle) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun closeScreenFromListener(lifecycle: EngineScreenLifecycle) {
        if (!beginOwnerOperation()) return
        try {
            closeScreenFromListenerAdmitted(lifecycle)
        } finally {
            endOwnerOperation()
        }
    }

    private fun closeScreenFromListenerAdmitted(lifecycle: EngineScreenLifecycle) {
        val detachment = synchronized(lifecycleLock) {
            if (!screenLifecycles.owns(lifecycle)) {
                ScreenDetachment(owned = false)
            } else {
                // A naturally closed screen listener terminalizes the whole AirPlay session. Mark
                // that intent before releasing the lifecycle lock so no sibling listener can be
                // installed or promoted in the interval before AirPlaySession.close().
                terminalScreenSessions += lifecycle.key.session
                detachScreen(lifecycle.key, expected = lifecycle, retainClosed = true)
            }
        }
        if (!detachment.owned) return
        val retirement = stageScreenDetachment(lifecycle.key, detachment) ?: return
        // Publish the protocol terminal barrier before touching renderer callbacks. MediaSink can
        // legitimately block while retiring a decoder/Surface; it must never keep the AirPlay
        // control session alive after its screen transport has already ended.
        val sessionFailure = runCatching { lifecycle.key.session.close() }.exceptionOrNull()
        if (sessionFailure != null) {
            runCatching { Log.w(TAG, "screen session close pending", sessionFailure) }
        }
        if (!lifecycle.key.session.isClosed) {
            return
        }
        // A retryable nested close can fail because this listener still owns an admitted media
        // operation. STOPPING is already published; retire this staged transport/renderer now so
        // the deferred AirPlay close can later prove the session fully retired.
        runCatching {
            retireStagedScreen(
                lifecycle.key,
                retirement,
                deliverNotification = true,
            )
        }.onFailure { Log.w(TAG, "screen renderer binding retirement failed", it) }
    }

    private fun rollbackScreen(lifecycle: EngineScreenLifecycle) {
        val detachment = detachScreen(
            lifecycle.key,
            expected = lifecycle,
            retainClosed = false,
        )
        // Also close an already-detached resource. ScreenStream.close() is idempotent, and this
        // covers a bind failure or a natural close racing the SETUP rollback.
        val resources = detachment.resources.toMutableList()
        if (resources.none { it === lifecycle.resource }) resources += lifecycle.resource
        val expanded = detachment.copy(resources = resources)
        runCatching { retireScreenDetachment(lifecycle.key, expanded, deliverNotification = true) }
    }

    private fun retireScreenDetachment(
        key: StreamKey,
        detachment: ScreenDetachment,
        deliverNotification: Boolean,
        retireBinding: Boolean = true,
    ) {
        val retirement = stageScreenDetachment(key, detachment) ?: return
        retireStagedScreen(key, retirement, deliverNotification, retireBinding)
    }

    private fun stageScreenDetachment(
        key: StreamKey,
        detachment: ScreenDetachment,
    ): PendingScreenRetirement? {
        if (!detachment.owned) return null
        val candidate = PendingScreenRetirement(
            key,
            detachment.resources,
            detachment.rendererBinding,
            detachment.notification,
            detachment.notificationOwner,
        )
        val retirement = pendingScreenRetirements.putIfAbsent(key, candidate) ?: candidate
        retirement.setNotificationCompleteCallback {
            if (retirement.isComplete()) pendingScreenRetirements.remove(key, retirement)
        }
        return retirement
    }

    private fun retireStagedScreen(
        key: StreamKey,
        retirement: PendingScreenRetirement,
        deliverNotification: Boolean,
        retireBinding: Boolean = true,
    ) {
        retirement.retire(deliverNotification, retireBinding)?.let { throw it }
        if (retirement.isComplete()) pendingScreenRetirements.remove(key, retirement)
    }

    private fun retryScreenRetirements(
        session: AirPlaySession? = null,
        deliverNotifications: Boolean,
        retireBindings: Boolean = true,
    ): Throwable? {
        var failure: Throwable? = null
        pendingScreenRetirements.entries.toList().forEach { (key, retirement) ->
            if (session != null && key.session !== session) return@forEach
            retirement.retire(deliverNotifications, retireBindings)?.let { problem ->
                failure = combineEngineFailures(failure, problem)
            }
            if (retirement.isComplete()) pendingScreenRetirements.remove(key, retirement)
        }
        return failure
    }

    private fun retryScreenTransportRetirements(keys: Set<StreamKey>): Throwable? {
        var failure: Throwable? = null
        keys.forEach { key ->
            val retirement = pendingScreenRetirements[key] ?: return@forEach
            retirement.retire(
                deliverNotification = false,
                retireBinding = false,
            )?.let { problem ->
                failure = combineEngineFailures(failure, problem)
            }
            if (retirement.isComplete()) pendingScreenRetirements.remove(key, retirement)
        }
        return failure
    }

    private fun beginOwnerOperation(): Boolean = synchronized(lifecycleLock) {
        if (closed.get() || ownerAdmissionsSealed || closeCompleted.get()) {
            false
        } else {
            ownerOperationsInFlight++
            true
        }
    }

    private fun endOwnerOperation() = synchronized(lifecycleLock) {
        check(ownerOperationsInFlight > 0) { "Missing media owner operation admission" }
        ownerOperationsInFlight--
    }

    private inline fun <T> withOwnerOperationOr(defaultValue: T, action: () -> T): T {
        if (!beginOwnerOperation()) return defaultValue
        return try {
            action()
        } finally {
            endOwnerOperation()
        }
    }

    private fun stageLegacyScreenHandle(
        key: StreamKey,
        handle: AirPlayVideoStreamHandle,
    ) = synchronized(lifecycleLock) {
        pendingLegacyScreenHandles.getOrPut(handle) {
            PendingScreenNotification(key, handle, sink)
        }
    }

    private fun retryLegacyScreenHandles(
        session: AirPlaySession? = null,
        exactKey: StreamKey? = null,
    ): Throwable? {
        var failure: Throwable? = null
        val pending = synchronized(lifecycleLock) {
            pendingLegacyScreenHandles.values.toList()
        }
        pending.forEach { owner ->
            if (session != null && owner.key.session !== session) return@forEach
            if (exactKey != null && owner.key != exactKey) return@forEach
            runCatching { owner.deliver() }.exceptionOrNull()?.let { problem ->
                failure = combineEngineFailures(failure, problem)
            }
            if (owner.isComplete()) {
                synchronized(lifecycleLock) {
                    if (owner.isComplete()) {
                        pendingLegacyScreenHandles.remove(owner.handle, owner)
                    }
                }
            }
        }
        return failure
    }

    private fun stageAudioStop(session: AirPlaySession, type: Int) =
        synchronized(lifecycleLock) {
            val key = session to type
            pendingAudioStopTypes.getOrPut(key) { PendingAudioStop(session, type, sink) }
        }

    private fun retryAudioStops(session: AirPlaySession? = null): Throwable? {
        var failure: Throwable? = null
        val pending = synchronized(lifecycleLock) { pendingAudioStopTypes.values.toList() }
        pending.forEach { owner ->
            if (session != null && owner.session !== session) return@forEach
            runCatching { owner.retire() }.exceptionOrNull()?.let { problem ->
                failure = combineEngineFailures(failure, problem)
            }
            if (owner.isComplete()) {
                synchronized(lifecycleLock) {
                    if (owner.isComplete()) {
                        pendingAudioStopTypes.remove(owner.session to owner.type, owner)
                    }
                }
            }
        }
        return failure
    }

    /** Adds cleanup created by a rejected install without detaching the existing exact-key owner. */
    private fun stageAdditionalOwnedResourceRetirement(
        key: StreamKey,
        resources: Collection<Closeable>,
    ): PendingOwnedResourceRetirement = synchronized(lifecycleLock) {
        val retirement = pendingOwnedResourceRetirements[key]
            ?: PendingOwnedResourceRetirement(key).also {
                pendingOwnedResourceRetirements[key] = it
            }
        retirement.add(resources)
        retirement
    }

    private fun stageOwnedResourceRetirement(
        key: StreamKey,
        additional: Collection<Closeable> = emptyList(),
    ): PendingOwnedResourceRetirement? = synchronized(lifecycleLock) {
        val resources = ArrayList<Closeable>(3 + additional.size)
        pendingStreams.remove(key)?.let(resources::add)
        streams.remove(key)?.let { resource ->
            if (resources.none { it === resource }) resources += resource
        }
        audioCaptures.remove(key)?.let { capture ->
            if (resources.none { it === capture }) resources += capture
        }
        additional.forEach { resource ->
            if (resources.none { it === resource }) resources += resource
        }
        val existing = pendingOwnedResourceRetirements[key]
        if (resources.isEmpty()) return@synchronized existing
        val retirement = existing ?: PendingOwnedResourceRetirement(key).also {
            pendingOwnedResourceRetirements[key] = it
        }
        retirement.add(resources)
        retirement
    }

    private fun retireOwnedResources(
        session: AirPlaySession? = null,
    ): Throwable? {
        var failure: Throwable? = null
        pendingOwnedResourceRetirements.entries.toList().forEach { (key, retirement) ->
            if (session != null && key.session !== session) return@forEach
            retirement.retire()?.let { problem ->
                failure = combineEngineFailures(failure, problem)
            }
            if (retirement.isComplete()) {
                synchronized(lifecycleLock) {
                    if (retirement.isComplete()) {
                        pendingOwnedResourceRetirements.remove(key, retirement)
                    }
                }
            }
        }
        return failure
    }

    private fun detachScreen(
        key: StreamKey,
        expected: EngineScreenLifecycle? = null,
        retainClosed: Boolean = false,
    ): ScreenDetachment = synchronized(lifecycleLock) {
        val lifecycle = screenLifecycles.entry(key)
        if (expected != null && lifecycle !== expected) {
            return@synchronized ScreenDetachment(owned = false)
        }
        if (lifecycle != null) {
            val resources = ArrayList<Closeable>(2)
            var rendererBinding: UiSyncPreparedScreenBinding? = null
            val notificationOwner = PendingScreenNotification(key, lifecycle.handle, sink)
            val task = screenLifecycles.close(
                lifecycle,
                retainClosed = retainClosed,
                closeStateLocked = {
                    if (pendingStreams.remove(key, lifecycle.resource)) {
                        resources += lifecycle.resource
                    }
                    if (streams.remove(key, lifecycle.resource) &&
                        resources.none { it === lifecycle.resource }
                    ) {
                        resources += lifecycle.resource
                    }
                    videoHandles.remove(key, lifecycle.handle)
                    rendererBinding = screenRendererBindings.remove(key)
                },
                notifyInactive = notificationOwner::deliver,
            )
            if (!retainClosed) screenLifecycles.forget(lifecycle)
            if (task == null) {
                // Another detachment already owns this closed lifecycle (notably the listener
                // path which calls AirPlaySession.close before publishing its retirement). Never
                // mint a second notification owner for the retained CLOSED tombstone.
                return@synchronized ScreenDetachment(owned = false)
            }
            return@synchronized ScreenDetachment(
                owned = true,
                lifecycleOwned = true,
                resources = resources,
                notification = task,
                notificationOwner = notificationOwner,
                rendererBinding = rendererBinding,
            )
        }

        // Compatibility fallback for state inserted by legacy tests or older call paths. Normal
        // production screen streams always have an explicit lifecycle entry.
        val resources = ArrayList<Closeable>(2)
        pendingStreams.remove(key)?.let(resources::add)
        streams.remove(key)?.let { resource ->
            if (resources.none { it === resource }) resources += resource
        }
        val legacyHandle = videoHandles.remove(key)
        val rendererBinding = screenRendererBindings.remove(key)
        val notificationOwner = legacyHandle?.let {
            PendingScreenNotification(key, it, sink)
        }
        ScreenDetachment(
            owned = resources.isNotEmpty() || legacyHandle != null || rendererBinding != null,
            resources = resources,
            notificationOwner = notificationOwner,
            rendererBinding = rendererBinding,
        )
    }

    private fun promotePending(key: StreamKey, resource: Closeable): Boolean {
        return synchronized(lifecycleLock) {
            if (closed.get() || key.session.isClosed ||
                streams.containsKey(key) ||
                !pendingStreams.remove(key, resource)
            ) {
                false
            } else {
                streams[key] = resource
                true
            }
        }
    }

    private fun removePending(key: StreamKey, resource: Closeable): Boolean =
        synchronized(lifecycleLock) { pendingStreams.remove(key, resource) }

    private fun rollbackDataStream(
        key: StreamKey,
        opened: OpenedCarPlayDataStream,
        installed: Boolean,
    ) {
        // Register ownership before invoking rollback: its hook may close partially and throw.
        val retirement = if (installed) {
            stageOwnedResourceRetirement(key)
        } else {
            stageAdditionalOwnedResourceRetirement(key, listOf(opened.closeable))
        }
        // A concurrent close/session teardown may already have consumed and proven an installed
        // owner. In that case invoking rollback would merely recreate work after terminal proof.
        if (retirement == null) return
        runCatching { opened.rollback() }
        retireOwnedResources(key.session)?.let { throw it }
    }

    /** Clears decoder state that may have arrived before a prepared SETUP transaction committed. */
    private fun discardScreenHandle(handle: AirPlayVideoStreamHandle) {
        sink.onVideoRecoveryHandler(handle, null)
        sink.onScreenStreamActive(handle, false)
    }

    private fun videoHandle(
        session: AirPlaySession,
        identity: AirPlayStreamIdentity,
        stream: Map<String, Any?>,
    ): AirPlayVideoStreamHandle? = synchronized(lifecycleLock) {
        if (closed.get() || session.isClosed) return@synchronized null
        // Do not reinterpret a UI Sync displayId, DDP ScreenID, or streamConnectionID as a
        // display UUID. Legacy video remains available when the phone omits/misstates `uuid`,
        // but such a handle can never satisfy a UUID-bound renderer ingress selector.
        val validatedDisplayUuid = validatedSetupDisplayUuid(
            identity.type,
            stream,
            session.resolvedConfig.cluster != null,
        )
        AirPlayVideoStreamHandle(
            identity,
            videoSessionEpochs.getOrPut(session, ::AirPlayVideoSessionEpoch),
            validatedDisplayUuid,
        )
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun stopMicrophone(key: StreamKey) {
        if (key !in startedMicrophone) return
        if (!microphoneStopsInFlight.add(key)) return
        try {
            if (key !in startedMicrophone) return
            sink.onMicrophoneStopped(key.type)
            startedMicrophone.remove(key)
        } finally {
            microphoneStopsInFlight.remove(key)
        }
    }

    private fun sessionRuntime(session: AirPlaySession): SessionDataRuntime? {
        val admitted = synchronized(lifecycleLock) {
            if (closed.get() || session.isClosed || ownerAdmissionsSealed) false else {
                sessionRuntimeCreations[session] = (sessionRuntimeCreations[session] ?: 0) + 1
                true
            }
        }
        if (!admitted) return null
        return try {
            sessionRuntimeReserved(session)
        } finally {
            synchronized(lifecycleLock) {
                val remaining = checkNotNull(sessionRuntimeCreations[session]) - 1
                if (remaining == 0) sessionRuntimeCreations.remove(session)
                else sessionRuntimeCreations[session] = remaining
            }
        }
    }

    private fun sessionRuntimeReserved(session: AirPlaySession): SessionDataRuntime? {
        if (closed.get() || session.isClosed) return null
        if (retireFailedSessionRuntimeConstructions(session) != null) return null
        val runtime = try {
            sessionDataRuntimes.computeIfAbsent(session) {
                if (failedSessionRuntimeConstructions.containsKey(session)) {
                    throw IOException("Prior session data runtime cleanup is pending")
                }
                // A resolved binding is authoritative even when it explicitly contains no Ultra
                // runtime. Only unbound legacy sessions may fall back to the constructor owner.
                val snapshot = session.runtimeSnapshot
                val runtimeRoot = if (snapshot != null) snapshot.ultraRuntime else ultraRuntime
                val sessionUltra = try {
                    runtimeRoot?.openSession(
                        beforePhysicalBind = { candidate ->
                            if (!session.registerPhysicalStopOwner(candidate)) {
                                candidate.coverForSessionStop()
                                throw IOException("Session stopped before Ultra physical bind")
                            }
                        },
                        onPhysicalCandidateRetired = session::unregisterPhysicalStopOwner,
                    )?.also { it.bindDebugStatusSink(session::logDebug) }
                } catch (failure: AirPlayUltraSessionOpenCleanupException) {
                    // The failed open owns a child that may already have activated physical output.
                    failedSessionRuntimeConstructions.computeIfAbsent(session) {
                        PendingSessionRuntimeConstructionRetirement()
                    }.add(failure)
                    throw failure
                }
                var matchPoint: MatchPointProductSessionOwner? = null
                try {
                    matchPoint = matchPointSessionFactory?.open(session)
                    SessionDataRuntime(
                        ultra = sessionUltra,
                        matchPoint = matchPoint,
                        handlers = CarPlayDataStreamHandlers(
                            iap = iapTunnelHandler,
                            rcs = composeSessionRcsHandlerFactories(
                                composeSessionRcsHandlerFactories(
                                    sessionUltra?.handlerFactory,
                                    session.connectionOverlay?.extraRcsFactory,
                                ),
                                matchPoint?.handlerFactory,
                            ),
                            onIapFallback = sink::onIapMessage,
                        ),
                    )
                } catch (failure: Throwable) {
                    val cleanup = SessionDataRuntime(
                        ultra = sessionUltra,
                        matchPoint = matchPoint,
                        handlers = CarPlayDataStreamHandlers(null, null, {}),
                    )
                    runCatching { cleanup.close() }.exceptionOrNull()?.let { cleanupFailure ->
                        failedSessionRuntimeConstructions.computeIfAbsent(session) {
                            PendingSessionRuntimeConstructionRetirement()
                        }.add(cleanup)
                        failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            runCatching {
                // Exception messages can carry session details: Debug bench builds only.
                val causes = generateSequence(failure) { it.cause }.take(4).joinToString(" <- ") {
                    if (UltraDebugStatusMarkers.enabled) {
                        "${it.javaClass.simpleName}: ${it.message?.take(160)}"
                    } else it.javaClass.simpleName
                }
                session.logDebug("AirPlay Ultra session renderer is unavailable cause=$causes")
            }
            return null
        }
        // close() marks the AirPlay session terminal before invoking onSessionClosed. If that
        // callback raced this compute, remove exactly our late value and close it here so a
        // completed session can never regain a process-owned Ultra runtime.
        if (closed.get() || session.isClosed) {
            if (sessionDataRuntimes[session] === runtime) {
                // Retain a failed exact owner for the session/engine close retry. Removing first
                // would strand a player or renderer when close reports incomplete retirement.
                if (runCatching { runtime.close() }.isSuccess) {
                    sessionDataRuntimes.remove(session, runtime)
                }
            }
            return null
        }
        return runtime
    }

    private fun retireFailedSessionRuntimeConstructions(
        session: AirPlaySession? = null,
    ): Throwable? {
        var failure: Throwable? = null
        failedSessionRuntimeConstructions.entries.toList().forEach { (ownerSession, cleanup) ->
            if (session != null && ownerSession !== session) return@forEach
            val error = runCatching { cleanup.close() }.exceptionOrNull()
            if (error == null && cleanup.isComplete()) {
                failedSessionRuntimeConstructions.remove(ownerSession, cleanup)
            } else {
                failure = combineEngineFailures(
                    failure,
                    error ?: IOException("Failed session runtime cleanup is pending"),
                )
            }
        }
        return failure
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        val port = requestedMicrophonePort(microphoneEnabled, type, stream) ?: return null
        if (format.codec == AudioCodecKind.AAC_LC) {
            Log.w(TAG, "microphone input requested with unsupported AAC-LC format")
            return null
        }
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val micRate = if (format.codec == AudioCodecKind.OPUS) {
            AudioStreamCodec.opusCaptureRate(formatBits)
        } else {
            format.sampleRate
        }
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / micRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = if (micRate <= 24_000) 48_000 else 96_000
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = micRate,
            channels = if (format.codec == AudioCodecKind.OPUS) 1 else format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val TAG = "xcertplay-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
    }
}

private fun combineEngineFailures(current: Throwable?, next: Throwable): Throwable =
    current?.also { if (it !== next) it.addSuppressed(next) } ?: next

/** The input port, rather than the downlink category, signals that the phone requests a mic. */
internal fun requestedMicrophonePort(
    microphoneEnabled: Boolean,
    type: Int,
    stream: Map<String, Any?>,
): Int? {
    if (!microphoneEnabled || type != 100) return null
    return (stream["dataPort"] as? Number)?.toLong()?.takeIf { it in 1..65535 }?.toInt()
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}

/** AirPlaySender carEndpoint_forceKeyFrame defaults to the primary stream with empty params. */
internal fun mainScreenKeyFrameCommand(): Map<String, Any?> =
    linkedMapOf("type" to "forceKeyFrame", "params" to emptyMap<String, Any?>())
