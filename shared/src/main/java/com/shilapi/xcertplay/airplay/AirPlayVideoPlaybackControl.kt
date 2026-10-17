package com.shilapi.xcertplay.airplay

import java.text.Normalizer
import java.util.Collections

/** Captured readiness for the local sender and audio-only playback backend. */
class AirPlayVideoPlaybackBackendReadiness private constructor(
    val isReady: Boolean,
    val unavailableReason: String?,
) {
    companion object {
        fun ready(): AirPlayVideoPlaybackBackendReadiness =
            AirPlayVideoPlaybackBackendReadiness(isReady = true, unavailableReason = null)

        fun unavailable(reason: String): AirPlayVideoPlaybackBackendReadiness {
            require(reason.isNotBlank()) { "Video-playback backend unavailability needs a reason" }
            return AirPlayVideoPlaybackBackendReadiness(
                isReady = false,
                unavailableReason = reason,
            )
        }
    }
}

/** Sends one accessory-originated AirPlay command to the phone. */
fun interface AirPlayVideoPlaybackCommandSender {
    fun send(command: Map<String, Any?>)
}

/** Consumes the phone-originated request to change the local player to audio-only playback. */
fun interface AirPlayVideoPlaybackBackend {
    fun changeVideoPlaybackToAudioOnly()
}

/**
 * Provisioning descriptor for [AirPlayVideoPlaybackControl].
 *
 * This remains independent of feature readiness. Metadata or a ready local implementation cannot
 * make `videoPlayback` negotiable while the feature stays permanently disabled.
 */
class AirPlayVideoPlaybackBackendDescriptor(
    val implementationId: String,
    private val readinessProbe: () -> AirPlayVideoPlaybackBackendReadiness,
    private val commandSenderFactory: () -> AirPlayVideoPlaybackCommandSender,
    private val backendFactory: () -> AirPlayVideoPlaybackBackend,
) {
    init {
        require(implementationId.isNotBlank()) {
            "Video-playback backend implementationId must not be blank"
        }
    }

    internal fun readyComponentsOrNull(): Components? {
        val readiness = failClosed(readinessProbe) ?: return null
        if (!readiness.isReady) return null
        val sender = failClosed(commandSenderFactory) ?: return null
        val backend = failClosed(backendFactory) ?: return null
        return Components(sender, backend)
    }

    private inline fun <T> failClosed(action: () -> T): T? = try {
        action()
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        null
    }

    internal class Components(
        val sender: AirPlayVideoPlaybackCommandSender,
        val backend: AirPlayVideoPlaybackBackend,
    )
}

/** Exact accessory-originated command builders confirmed by the iOS 27 implementation. */
object AirPlayVideoPlaybackCommandBuilder {
    const val MAX_PLAYBACK_STATUS_CHARS = 128

    fun setVideoPlaybackAllowed(
        allowed: Boolean,
        playbackStatus: String? = null,
    ): Map<String, Any?> {
        validatePlaybackStatus(playbackStatus)
        val params = linkedMapOf<String, Any?>(VIDEO_PLAYBACK_ALLOWED to allowed)
        playbackStatus?.let { params[PLAYBACK_STATUS] = it }
        return immutableCommand(
            SET_VIDEO_PLAYBACK_ALLOWED,
            Collections.unmodifiableMap(params),
        )
    }

    fun videoPlayerBackButtonEvent(
        playbackStatus: String? = null,
    ): Map<String, Any?> {
        validatePlaybackStatus(playbackStatus)
        val params = playbackStatus?.let {
            Collections.unmodifiableMap(linkedMapOf<String, Any?>(PLAYBACK_STATUS to it))
        }
        return immutableCommand(VIDEO_PLAYER_BACK_BUTTON_EVENT, params)
    }

    private fun immutableCommand(
        type: String,
        params: Map<String, Any?>?,
    ): Map<String, Any?> = Collections.unmodifiableMap(
        linkedMapOf<String, Any?>(TYPE to type).apply {
            params?.let { put(PARAMS, it) }
        },
    )

    private fun validatePlaybackStatus(value: String?) {
        require(
            value == null ||
                value.isNotBlank() &&
                value.length <= MAX_PLAYBACK_STATUS_CHARS &&
                value.none { it == '\u0000' || it.isISOControl() } &&
                Normalizer.isNormalized(value, Normalizer.Form.NFC),
        ) {
            "playbackStatus must be bounded, non-blank, NFC text without control characters"
        }
    }
}

enum class AirPlayVideoPlaybackSendResult {
    SENT,
    UNCHANGED,
    IN_FLIGHT,
}

enum class AirPlayVideoPlaybackCommandResult {
    HANDLED,
    REJECTED,
}

/**
 * Evidence-scoped video-playback control.
 *
 * `setVideoPlaybackAllowed` and `videoPlayerBackButtonEvent` are accessory-originated commands.
 * The only accepted phone-originated command is `changeVideoPlaybackToAudioOnly`, which invokes
 * the local backend. Both external boundaries are always called without holding [lock].
 */
class AirPlayVideoPlaybackControl private constructor(
    private val sender: AirPlayVideoPlaybackCommandSender,
    private val backend: AirPlayVideoPlaybackBackend,
    initialVideoPlaybackAllowed: Boolean,
) {
    private val lock = Any()
    private var committedVideoPlaybackAllowed = initialVideoPlaybackAllowed
    private var pendingSetAllowed: PendingSetAllowed? = null

    fun setVideoPlaybackAllowed(
        allowed: Boolean,
        playbackStatus: String? = null,
    ): AirPlayVideoPlaybackSendResult {
        val command = AirPlayVideoPlaybackCommandBuilder.setVideoPlaybackAllowed(
            allowed,
            playbackStatus,
        )
        val pending = synchronized(lock) {
            if (pendingSetAllowed != null) return AirPlayVideoPlaybackSendResult.IN_FLIGHT
            if (committedVideoPlaybackAllowed == allowed) {
                return AirPlayVideoPlaybackSendResult.UNCHANGED
            }
            PendingSetAllowed(allowed).also { pendingSetAllowed = it }
        }

        try {
            sender.send(command)
        } catch (error: Exception) {
            synchronized(lock) {
                if (pendingSetAllowed === pending) pendingSetAllowed = null
            }
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
        synchronized(lock) {
            if (pendingSetAllowed === pending) {
                committedVideoPlaybackAllowed = pending.allowed
                pendingSetAllowed = null
            }
        }
        return AirPlayVideoPlaybackSendResult.SENT
    }

    /** Every invocation emits a distinct user back-button event. */
    fun videoPlayerBackButtonEvent(
        playbackStatus: String? = null,
    ): AirPlayVideoPlaybackSendResult {
        val command = AirPlayVideoPlaybackCommandBuilder.videoPlayerBackButtonEvent(playbackStatus)
        try {
            sender.send(command)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
        return AirPlayVideoPlaybackSendResult.SENT
    }

    /** Strictly consumes the sole confirmed phone-originated command. */
    fun handleCommand(value: Any?): AirPlayVideoPlaybackCommandResult {
        if (!isChangeToAudioOnlyCommand(value)) {
            return AirPlayVideoPlaybackCommandResult.REJECTED
        }
        try {
            backend.changeVideoPlaybackToAudioOnly()
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
        return AirPlayVideoPlaybackCommandResult.HANDLED
    }

    private class PendingSetAllowed(val allowed: Boolean)

    companion object {
        fun open(
            descriptor: AirPlayVideoPlaybackBackendDescriptor,
            initialInfo: AirPlayVideoPlaybackInfo,
        ): AirPlayVideoPlaybackControl? = descriptor.readyComponentsOrNull()?.let { components ->
            AirPlayVideoPlaybackControl(
                sender = components.sender,
                backend = components.backend,
                initialVideoPlaybackAllowed = initialInfo.videoPlaybackAllowed,
            )
        }

        private fun isChangeToAudioOnlyCommand(value: Any?): Boolean {
            val values = value as? Map<*, *> ?: return false
            return try {
                var count = 0
                var typeSeen = false
                var paramsSeen = false
                for ((rawKey, nested) in values) {
                    if (++count > 2) return false
                    when (rawKey as? String ?: return false) {
                        TYPE -> {
                            if (typeSeen || nested != CHANGE_VIDEO_PLAYBACK_TO_AUDIO_ONLY) {
                                return false
                            }
                            typeSeen = true
                        }
                        PARAMS -> {
                            if (paramsSeen || !isEmptyMap(nested)) return false
                            paramsSeen = true
                        }
                        else -> return false
                    }
                }
                typeSeen && (count == 1 || count == 2 && paramsSeen)
            } catch (_: RuntimeException) {
                false
            }
        }

        private fun isEmptyMap(value: Any?): Boolean {
            val values = value as? Map<*, *> ?: return false
            return !values.entries.iterator().hasNext()
        }
    }
}

private const val TYPE = "type"
private const val PARAMS = "params"
private const val VIDEO_PLAYBACK_ALLOWED = "videoPlaybackAllowed"
private const val PLAYBACK_STATUS = "playbackStatus"
private const val SET_VIDEO_PLAYBACK_ALLOWED = "setVideoPlaybackAllowed"
private const val VIDEO_PLAYER_BACK_BUTTON_EVENT = "videoPlayerBackButtonEvent"
private const val CHANGE_VIDEO_PLAYBACK_TO_AUDIO_ONLY = "changeVideoPlaybackToAudioOnly"
