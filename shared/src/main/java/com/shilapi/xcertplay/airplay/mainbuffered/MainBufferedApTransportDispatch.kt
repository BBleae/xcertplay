package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.BplistDecodeLimits
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackage
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec

/**
 * Resource bounds for the detached `mctl` control envelope. These limits can be lowered by a
 * caller, but not raised beyond the hard caps without reviewing the control-plane admission path.
 */
data class MainBufferedControlEnvelopeLimits(
    val maxPackageBytes: Int = 256 * 1024,
    val maxBplistBytes: Int = 128 * 1024,
    val maxPackagesPerChunk: Int = 64,
    val maxAuthenticatedChunkBytes: Int = 512 * 1024,
) {
    init {
        require(maxPackageBytes in (ApTransportPackageCodec.HEADER_SIZE + 1)..HARD_MAX_PACKAGE_BYTES)
        require(maxBplistBytes in 1..minOf(
            HARD_MAX_BPLIST_BYTES,
            maxPackageBytes - ApTransportPackageCodec.HEADER_SIZE,
        ))
        require(maxPackagesPerChunk in 1..HARD_MAX_PACKAGES_PER_CHUNK)
        require(maxAuthenticatedChunkBytes in 1..HARD_MAX_CHUNK_BYTES)
    }

    private companion object {
        const val HARD_MAX_PACKAGE_BYTES = 256 * 1024
        const val HARD_MAX_BPLIST_BYTES = 128 * 1024
        const val HARD_MAX_PACKAGES_PER_CHUNK = 64
        const val HARD_MAX_CHUNK_BYTES = 512 * 1024
    }
}

/** A fully validated `sync` command and its exact APTransport request metadata. */
class MainBufferedControlEnvelope private constructor(
    val header: MainBufferedApTransportControlHeader,
    val command: MainBufferedControlCommand,
    internal val sourcePackage: ApTransportPackage,
) {
    companion object {
        internal fun validated(
            header: MainBufferedApTransportControlHeader,
            command: MainBufferedControlCommand,
            sourcePackage: ApTransportPackage,
        ) = MainBufferedControlEnvelope(header, command, sourcePackage)
    }
}

/**
 * Decodes one APTransport package received from an *already authenticated* `mctl` channel.
 *
 * This layer does not establish a socket, derive a MediaDataControl key, or authenticate bytes.
 * Its caller must prove those properties before invoking it. No product feature is advertised by
 * merely constructing this decoder.
 */
class MainBufferedApTransportControlDecoder(
    private val limits: MainBufferedControlEnvelopeLimits = MainBufferedControlEnvelopeLimits(),
) {
    fun decodeAuthenticatedPackage(packageValue: ApTransportPackage): MainBufferedControlEnvelope {
        require(packageValue.encodedSize <= limits.maxPackageBytes) {
            "main-buffered control package exceeds ${limits.maxPackageBytes} bytes"
        }
        val header = MainBufferedApTransportControlHeader.view(packageValue)
        require(header.packageType == MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC) {
            "main-buffered control request must be a 'sync' package"
        }
        val tag = header.messageTypeFourCcOrNull()
            ?: throw ApapProtocolException("main-buffered control message type is not a FourCC")
        val body = packageValue.body
        require(body.size <= limits.maxBplistBytes) {
            "main-buffered control bplist exceeds ${limits.maxBplistBytes} bytes"
        }
        val params = BplistCodec.decode(body, controlBplistLimits(limits.maxBplistBytes))
        val command = MainBufferedControlParser.parse(tag, params)
        return MainBufferedControlEnvelope.validated(header, command, packageValue)
    }
}

/** A handler's response body and transport-level status; malformed replies are never emitted. */
data class MainBufferedControlDispatchReply(
    val body: Map<String, Any?>,
    val replyStatus: Int = 0,
)

fun interface MainBufferedControlHandler {
    fun handle(request: MainBufferedControlEnvelope): MainBufferedControlDispatchReply
}

/**
 * Dispatches only after the entire request has passed header, size, bplist, tag and parameter
 * validation. Handler failure is propagated; no guessed error reply or side effect is added here.
 */
class MainBufferedApTransportControlDispatcher(
    private val handler: MainBufferedControlHandler,
    private val decoder: MainBufferedApTransportControlDecoder =
        MainBufferedApTransportControlDecoder(),
    private val maxReplyPackageBytes: Int = 256 * 1024,
    private val replyBplistLimits: MainBufferedReplyBplistLimits =
        MainBufferedReplyBplistLimits(
            maxDepth = 8,
            maxNodes = 1_024,
            maxContainerItems = 256,
            maxDataBytes = 96 * 1024,
            maxStringChars = 4 * 1024,
            maxAggregateValueBytes = 128 * 1024,
        ),
) {
    init {
        require(maxReplyPackageBytes in (ApTransportPackageCodec.HEADER_SIZE + 1)..256 * 1024)
        require(replyBplistLimits.maxDepth <= 8)
        require(replyBplistLimits.maxNodes <= 1_024)
        require(replyBplistLimits.maxContainerItems <= 256)
        require(replyBplistLimits.maxDataBytes <= 96 * 1024)
        require(replyBplistLimits.maxStringChars <= 4 * 1024)
        require(replyBplistLimits.maxAggregateValueBytes <= 128 * 1024)
    }

    fun dispatchAuthenticatedPackage(packageValue: ApTransportPackage): ApTransportPackage =
        dispatch(decoder.decodeAuthenticatedPackage(packageValue))

    fun dispatch(request: MainBufferedControlEnvelope): ApTransportPackage {
        val reply = handler.handle(request)
        val encoded = MainBufferedApTransportReplyEncoder.encodeReply(
            request = request.sourcePackage,
            body = reply.body,
            replyStatus = reply.replyStatus,
            maxPackageBytes = maxReplyPackageBytes,
            bplistLimits = replyBplistLimits,
        )
        // Validate the exact bytes to be emitted, not a mutable handler map that could change
        // between validation and the reply encoder's bounded defensive snapshot.
        val decodedBody = BplistCodec.decode(
            encoded.body,
            controlBplistLimits(minOf(maxReplyPackageBytes, 128 * 1024)),
        )
        MainBufferedControlResponseParser.parse(request.command.tag.wireValue, decodedBody)
        return encoded
    }
}

private fun controlBplistLimits(maxInputBytes: Int): BplistDecodeLimits = BplistDecodeLimits(
    maxInputBytes = maxInputBytes,
    maxObjects = 1_024,
    maxObjectBytes = maxInputBytes,
    maxNestingDepth = 8,
    maxContainerItems = 256,
    maxDataBytes = minOf(maxInputBytes, 96 * 1024),
    maxStringBytes = minOf(maxInputBytes, 8 * 1024),
    maxDecodedBytes = 256L * 1024L,
)

/**
 * Incremental framing for plaintext chunks supplied by an already authenticated channel.
 * Every complete package in a chunk is validated before any envelope is returned. One malformed
 * package poisons this framer, discards its remainder, and prevents later dispatch through it.
 */
class MainBufferedApTransportControlFramer(
    private val limits: MainBufferedControlEnvelopeLimits = MainBufferedControlEnvelopeLimits(),
) {
    private val decoder = MainBufferedApTransportControlDecoder(limits)
    private var remainder = ByteArray(0)
    private var terminal = false

    @Synchronized
    fun acceptAuthenticatedBytes(chunk: ByteArray): List<MainBufferedControlEnvelope> {
        check(!terminal) { "main-buffered control framer is terminal" }
        return try {
            require(chunk.size <= limits.maxAuthenticatedChunkBytes) {
                "main-buffered control chunk exceeds ${limits.maxAuthenticatedChunkBytes} bytes"
            }
            require(remainder.size.toLong() + chunk.size <=
                limits.maxPackageBytes.toLong() + limits.maxAuthenticatedChunkBytes) {
                "main-buffered control buffered bytes exceed configured limit"
            }
            val decoded = ApTransportPackageCodec.decodeAvailable(
                bytes = remainder + chunk,
                maxPackageBytes = limits.maxPackageBytes,
                maxPackages = limits.maxPackagesPerChunk,
            )
            val envelopes = decoded.packages.map(decoder::decodeAuthenticatedPackage)
            remainder = decoded.remainder
            envelopes
        } catch (failure: Exception) {
            poison()
            throw failure
        }
    }

    @Synchronized
    fun finish() {
        check(!terminal) { "main-buffered control framer is terminal" }
        val incomplete = remainder.isNotEmpty()
        poison()
        if (incomplete) throw ApapProtocolException("truncated main-buffered control package at EOF")
    }

    private fun poison() {
        remainder.fill(0)
        remainder = ByteArray(0)
        terminal = true
    }
}
