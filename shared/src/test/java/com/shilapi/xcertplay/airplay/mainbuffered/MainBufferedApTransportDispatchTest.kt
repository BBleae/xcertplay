package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackage
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedApTransportDispatchTest {
    @Test
    fun decoderPreservesUnsignedHeaderBitsAndYieldsTypedCommand() {
        val request = request(
            tag = "strt",
            params = mapOf("streamID" to 7L, "rate" to 0L),
            groupId = Long.MIN_VALUE,
            replyToken = -1L,
        )

        val envelope = MainBufferedApTransportControlDecoder()
            .decodeAuthenticatedPackage(request)

        assertEquals(Long.MIN_VALUE, envelope.header.groupId)
        assertEquals(-1L, envelope.header.replyToken)
        assertEquals("strt", envelope.header.messageTypeFourCcOrNull())
        val command = envelope.command as MainBufferedSetRateReturnAnchor
        assertEquals(7L, command.streamId)
        assertEquals(0, command.rate)
    }

    @Test
    fun invalidHeadersBodiesAndUnknownParametersNeverReachHandler() {
        val calls = AtomicInteger()
        val dispatcher = MainBufferedApTransportControlDispatcher(
            handler = MainBufferedControlHandler {
                calls.incrementAndGet()
                MainBufferedControlDispatchReply(emptyMap())
            },
        )
        val invalid = listOf(
            request("loud", mapOf("loudnessNormalizationEnabled" to true),
                packageType = MainBufferedApTransportControlHeader.PACKAGE_TYPE_REPLY),
            request("zzzz", emptyMap<String, Any>()),
            request(0x0102_0304, BplistCodec.encode(emptyMap<String, Any>())),
            request("loud", mapOf("loudnessNormalizationEnabled" to true, "surprise" to 1L)),
            request(fourCc("loud"), byteArrayOf(1, 2, 3)),
        )

        invalid.forEach { packageValue ->
            assertThrows(IllegalArgumentException::class.java) {
                dispatcher.dispatchAuthenticatedPackage(packageValue)
            }
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun packageAndBplistLimitsRejectBeforeDispatch() {
        val calls = AtomicInteger()
        val limits = MainBufferedControlEnvelopeLimits(
            maxPackageBytes = 512,
            maxBplistBytes = 256,
            maxAuthenticatedChunkBytes = 1_024,
        )
        val dispatcher = MainBufferedApTransportControlDispatcher(
            handler = MainBufferedControlHandler {
                calls.incrementAndGet()
                MainBufferedControlDispatchReply(emptyMap())
            },
            decoder = MainBufferedApTransportControlDecoder(limits),
        )
        val tooLargeBplist = request("magc", mapOf(
            "magicCookieID" to 1L,
            "magicCookie" to ByteArray(260),
        ))
        val tooLargePackage = request(fourCc("magc"), ByteArray(512))

        assertThrows(IllegalArgumentException::class.java) {
            dispatcher.dispatchAuthenticatedPackage(tooLargeBplist)
        }
        assertThrows(IllegalArgumentException::class.java) {
            dispatcher.dispatchAuthenticatedPackage(tooLargePackage)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun dispatcherRepliesWithExactGroupTypeAndTokenAfterValidatingBody() {
        val request = request(
            tag = "loud",
            params = mapOf("loudnessNormalizationEnabled" to true),
            groupId = 0x0102_0304_0506_0708L,
            replyToken = 0x1112_1314_1516_1718L,
        )
        val calls = AtomicInteger()
        val dispatcher = MainBufferedApTransportControlDispatcher(
            handler = MainBufferedControlHandler { envelope ->
                assertTrue((envelope.command as MainBufferedLoudnessNormalization).enabled)
                calls.incrementAndGet()
                MainBufferedControlDispatchReply(emptyMap())
            },
        )

        val response = dispatcher.dispatchAuthenticatedPackage(request)
        val header = MainBufferedApTransportControlHeader.view(response)

        assertEquals(1, calls.get())
        assertEquals("rply", header.packageTypeFourCcOrNull())
        assertEquals(0x0102_0304_0506_0708L, header.groupId)
        assertEquals("loud", header.messageTypeFourCcOrNull())
        assertEquals(0x1112_1314_1516_1718L, header.replyToken)
        assertEquals(0, header.replyStatus)
        assertEquals(emptyMap<String, Any>(), BplistCodec.decode(response.body))
    }

    @Test
    fun invalidHandlerResponseAndHandlerFailureDoNotProduceAReply() {
        val request = request("anch", mapOf("streamID" to 7L))
        val invalidResponse = MainBufferedApTransportControlDispatcher(
            handler = MainBufferedControlHandler {
                MainBufferedControlDispatchReply(mapOf("status" to 0L))
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            invalidResponse.dispatchAuthenticatedPackage(request)
        }

        val failure = IllegalStateException("sink unavailable")
        val failedHandler = MainBufferedApTransportControlDispatcher(
            handler = MainBufferedControlHandler { throw failure },
        )
        assertTrue(assertThrows(IllegalStateException::class.java) {
            failedHandler.dispatchAuthenticatedPackage(request)
        } === failure)
    }

    @Test
    fun framerAcceptsFragmentedAndCoalescedAuthenticatedPackages() {
        val first = request("loud", mapOf("loudnessNormalizationEnabled" to true),
            groupId = 1L, replyToken = 10L).encoded()
        val second = request("mata", mapOf("matAtmosPlaybackEnabled" to false),
            groupId = 2L, replyToken = 20L).encoded()
        val framer = MainBufferedApTransportControlFramer()

        assertTrue(framer.acceptAuthenticatedBytes(first.copyOfRange(0, 10)).isEmpty())
        val firstDecoded = framer.acceptAuthenticatedBytes(
            first.copyOfRange(10, first.size) + second.copyOfRange(0, 20),
        )
        assertEquals(1, firstDecoded.size)
        assertEquals(1L, firstDecoded.single().header.groupId)
        assertEquals(10L, firstDecoded.single().header.replyToken)
        val secondDecoded = framer.acceptAuthenticatedBytes(second.copyOfRange(20, second.size))
        assertEquals(1, secondDecoded.size)
        assertEquals(2L, secondDecoded.single().header.groupId)
        assertEquals(20L, secondDecoded.single().header.replyToken)
        framer.finish()
        assertThrows(IllegalStateException::class.java) {
            framer.acceptAuthenticatedBytes(first)
        }
    }

    @Test
    fun malformedSecondPackageRejectsWholeBatchAndPoisonsFramer() {
        val good = request("loud", mapOf("loudnessNormalizationEnabled" to true)).encoded()
        val bad = request("loud", mapOf("unknown" to true)).encoded()
        val framer = MainBufferedApTransportControlFramer()

        assertThrows(IllegalArgumentException::class.java) {
            framer.acceptAuthenticatedBytes(good + bad)
        }
        assertThrows(IllegalStateException::class.java) {
            framer.acceptAuthenticatedBytes(good)
        }
    }

    @Test
    fun framerBoundsCountChunkAndTruncatedEof() {
        val value = request("loud", mapOf("loudnessNormalizationEnabled" to true)).encoded()
        val countLimited = MainBufferedApTransportControlFramer(
            MainBufferedControlEnvelopeLimits(maxPackagesPerChunk = 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            countLimited.acceptAuthenticatedBytes(value + value)
        }
        assertThrows(IllegalStateException::class.java) { countLimited.finish() }

        val chunkLimited = MainBufferedApTransportControlFramer(
            MainBufferedControlEnvelopeLimits(maxAuthenticatedChunkBytes = value.size - 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            chunkLimited.acceptAuthenticatedBytes(value)
        }

        val truncated = MainBufferedApTransportControlFramer()
        truncated.acceptAuthenticatedBytes(value.copyOfRange(0, value.lastIndex))
        assertThrows(ApapProtocolException::class.java) { truncated.finish() }
        assertThrows(IllegalStateException::class.java) {
            truncated.acceptAuthenticatedBytes(value)
        }
    }

    private fun request(
        tag: String,
        params: Map<String, Any>,
        groupId: Long = 1L,
        replyToken: Long = 2L,
        packageType: Int = MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC,
    ): ApTransportPackage = request(
        messageType = fourCc(tag),
        body = BplistCodec.encode(params),
        groupId = groupId,
        replyToken = replyToken,
        packageType = packageType,
    )

    private fun request(
        messageType: Int,
        body: ByteArray,
        groupId: Long = 1L,
        replyToken: Long = 2L,
        packageType: Int = MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC,
    ): ApTransportPackage = ApTransportPackageCodec.create(
        messageType = messageType,
        body = body,
        uninterpretedHeader = ByteArray(32).also { header ->
            putU32Be(header, 4, packageType)
            putU64Be(header, 8, groupId)
            putU64Be(header, 20, replyToken)
        },
    )

    private fun fourCc(value: String): Int {
        require(value.length == 4)
        return value.toByteArray(Charsets.US_ASCII).fold(0) { result, byte ->
            result shl 8 or (byte.toInt() and 0xff)
        }
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) target[offset + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun putU64Be(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) target[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}
