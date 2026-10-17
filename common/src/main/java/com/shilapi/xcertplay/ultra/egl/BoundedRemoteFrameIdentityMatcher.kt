package com.shilapi.xcertplay.ultra.egl

import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import java.util.ArrayDeque
import java.util.IdentityHashMap

/** No state in this diagnostic join grants scene, renderer, or physical-display readiness. */
internal enum class RemoteFrameIdentityDisposition { PENDING, MATCHED, UNMATCHED }

internal enum class RemoteFrameTimestampBasis { CODEC_OUTPUT_PTS, TIMED_CODEC_RELEASE }

/** Copied only from a MediaCodec release receipt issued by the exact decoder Surface lease. */
internal class RemoteVideoReleaseSignal(
    val handle: AirPlayVideoStreamHandle,
    val surface: Surface,
    val surfaceLeaseGeneration: Long,
    val authenticatedFrameCounter: Long,
    val presentationTimeNtp64Bits: Long,
    val codecOutputPresentationTimeUs: Long,
    val releaseTargetMonotonicNanos: Long?,
    val expectedSurfaceTextureTimestampNanos: Long,
)

/**
 * Exact decoder-release ↔ SurfaceTexture-acquisition correlation. It does not prove that any
 * scene used this buffer, that an EGL swap finished, or that physical pixels were scanned out.
 */
internal class MatchedRemoteFrameConsumerReceipt internal constructor(
    val key: UltraEglIngressKey.Remote,
    val handle: AirPlayVideoStreamHandle,
    val surface: Surface,
    val surfaceLeaseGeneration: Long,
    val eglIngressGeneration: Long,
    val eglUpdateSequence: Long,
    val surfaceTextureTimestampNanos: Long,
    val timestampBasis: RemoteFrameTimestampBasis,
    val authenticatedFrameCounter: Long,
    val presentationTimeNtp64Bits: Long,
    val codecOutputPresentationTimeUs: Long,
)

/**
 * Bounded, per-active-ingress one-shot join. Both arrival orders work; no clock-nearness,
 * callback-count pairing, structural handle equality, or cross-generation carry is permitted.
 */
internal class BoundedRemoteFrameIdentityMatcher(
    private val maximumPendingPerIngress: Int = DEFAULT_MAXIMUM_PENDING_PER_INGRESS,
    private val maximumMatchedReceipts: Int = DEFAULT_MAXIMUM_MATCHED_RECEIPTS,
) {
    private class Binding(
        val key: UltraEglIngressKey.Remote,
        val handle: AirPlayVideoStreamHandle,
        val surface: Surface,
        val surfaceLeaseGeneration: Long,
        val eglIngressGeneration: Long,
    ) {
        var lastCounter = -1L
        var lastReleaseTimestamp = 0L
        var lastEglSequence = 0L
        var lastAcquiredTimestamp = 0L
        val releases = LinkedHashMap<Long, Pair<RemoteVideoReleaseSignal,
            RemoteFrameTimestampBasis>>()
        val acquisitions = LinkedHashMap<Long, UltraEglAcquiredRemoteFrame>()
    }

    private val lock = Any()
    private val byHandle = IdentityHashMap<AirPlayVideoStreamHandle, Binding>()
    private val byKey = LinkedHashMap<UltraEglIngressKey.Remote, Binding>()
    private val matched = ArrayDeque<MatchedRemoteFrameConsumerReceipt>()

    fun bind(
        key: UltraEglIngressKey.Remote,
        handle: AirPlayVideoStreamHandle,
        surface: Surface,
        surfaceLeaseGeneration: Long,
        eglIngressGeneration: Long,
    ) {
        synchronized(lock) {
            require(surfaceLeaseGeneration > 0L && eglIngressGeneration > 0L)
            require(key !in byKey && !byHandle.containsKey(handle)) {
                "Remote frame identity binding already exists"
            }
            Binding(key, handle, surface, surfaceLeaseGeneration, eglIngressGeneration).also {
                byKey[key] = it
                byHandle[handle] = it
            }
        }
    }

    /** Revocation removes pending and already matched evidence for this exact generation. */
    fun revoke(key: UltraEglIngressKey.Remote, eglIngressGeneration: Long) {
        synchronized(lock) {
            val binding = byKey[key] ?: return@synchronized
            if (binding.eglIngressGeneration != eglIngressGeneration) return@synchronized
            byKey.remove(key)
            byHandle.remove(binding.handle)
            matched.removeAll { it.key == key && it.eglIngressGeneration == eglIngressGeneration }
        }
    }

    fun clear() {
        synchronized(lock) {
            byKey.clear()
            byHandle.clear()
            matched.clear()
        }
    }

    fun acceptRelease(signal: RemoteVideoReleaseSignal): RemoteFrameIdentityDisposition =
        synchronized(lock) {
            val binding = byHandle[signal.handle]
                ?: return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            if (signal.handle !== binding.handle || signal.surface !== binding.surface ||
                signal.surfaceLeaseGeneration != binding.surfaceLeaseGeneration ||
                signal.authenticatedFrameCounter <= binding.lastCounter ||
                signal.authenticatedFrameCounter < 0L
            ) return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            val timestamp = releaseTimestampOrNull(signal)
                ?: return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            if (timestamp <= binding.lastReleaseTimestamp) {
                return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            }
            if (timestamp < binding.lastAcquiredTimestamp &&
                timestamp !in binding.acquisitions
            ) return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            binding.lastCounter = signal.authenticatedFrameCounter
            binding.lastReleaseTimestamp = timestamp
            val acquired = binding.acquisitions.remove(timestamp)
            if (acquired != null) {
                enqueueMatched(binding, signal, acquired, timestamp)
                RemoteFrameIdentityDisposition.MATCHED
            } else {
                binding.releases[timestamp] = signal to timestampBasis(signal)
                trim(binding.releases)
                RemoteFrameIdentityDisposition.PENDING
            }
        }

    fun observeAcquired(frame: UltraEglAcquiredRemoteFrame): RemoteFrameIdentityDisposition =
        synchronized(lock) {
            val binding = byKey[frame.key]
                ?: return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            if (frame.ingressGeneration != binding.eglIngressGeneration ||
                frame.ingress.producerEndpoint !== binding.surface ||
                frame.updateSequence <= binding.lastEglSequence ||
                frame.surfaceTextureTimestampNanos <= binding.lastAcquiredTimestamp ||
                frame.surfaceTextureTimestampNanos <= 0L
            ) return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            if (frame.surfaceTextureTimestampNanos < binding.lastReleaseTimestamp &&
                frame.surfaceTextureTimestampNanos !in binding.releases
            ) return@synchronized RemoteFrameIdentityDisposition.UNMATCHED
            binding.lastEglSequence = frame.updateSequence
            binding.lastAcquiredTimestamp = frame.surfaceTextureTimestampNanos
            val release = binding.releases.remove(frame.surfaceTextureTimestampNanos)
            if (release != null) {
                enqueueMatched(binding, release.first, frame,
                    frame.surfaceTextureTimestampNanos)
                RemoteFrameIdentityDisposition.MATCHED
            } else {
                binding.acquisitions[frame.surfaceTextureTimestampNanos] = frame
                trim(binding.acquisitions)
                RemoteFrameIdentityDisposition.PENDING
            }
        }

    /** The caller can drain identity evidence; absence is never a rendering prohibition. */
    fun drainMatched(): List<MatchedRemoteFrameConsumerReceipt> = synchronized(lock) {
        buildList(matched.size) {
            while (matched.isNotEmpty()) add(matched.removeFirst())
        }
    }

    private fun enqueueMatched(
        binding: Binding,
        release: RemoteVideoReleaseSignal,
        acquired: UltraEglAcquiredRemoteFrame,
        timestamp: Long,
    ) {
        while (matched.size >= maximumMatchedReceipts) matched.removeFirst()
        matched.addLast(MatchedRemoteFrameConsumerReceipt(
            key = binding.key,
            handle = binding.handle,
            surface = binding.surface,
            surfaceLeaseGeneration = binding.surfaceLeaseGeneration,
            eglIngressGeneration = binding.eglIngressGeneration,
            eglUpdateSequence = acquired.updateSequence,
            surfaceTextureTimestampNanos = timestamp,
            timestampBasis = timestampBasis(release),
            authenticatedFrameCounter = release.authenticatedFrameCounter,
            presentationTimeNtp64Bits = release.presentationTimeNtp64Bits,
            codecOutputPresentationTimeUs = release.codecOutputPresentationTimeUs,
        ))
    }

    private fun releaseTimestampOrNull(signal: RemoteVideoReleaseSignal): Long? {
        val expected = signal.expectedSurfaceTextureTimestampNanos
        if (expected <= 0L) return null
        val target = signal.releaseTargetMonotonicNanos
        if (target != null) return expected.takeIf { it == target }
        if (signal.codecOutputPresentationTimeUs <= 0L) return null
        return try {
            expected.takeIf {
                it == Math.multiplyExact(signal.codecOutputPresentationTimeUs, 1_000L)
            }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun timestampBasis(signal: RemoteVideoReleaseSignal): RemoteFrameTimestampBasis =
        if (signal.releaseTargetMonotonicNanos != null) {
            RemoteFrameTimestampBasis.TIMED_CODEC_RELEASE
        } else {
            RemoteFrameTimestampBasis.CODEC_OUTPUT_PTS
        }

    private fun <V> trim(values: LinkedHashMap<Long, V>) {
        while (values.size > maximumPendingPerIngress) {
            values.remove(values.keys.first())
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_PENDING_PER_INGRESS = 64
        const val DEFAULT_MAXIMUM_MATCHED_RECEIPTS = 128
    }

    init {
        require(maximumPendingPerIngress in 1..256)
        require(maximumMatchedReceipts in 1..512)
    }
}
