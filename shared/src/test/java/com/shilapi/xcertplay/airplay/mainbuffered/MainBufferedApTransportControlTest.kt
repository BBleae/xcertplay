package com.shilapi.xcertplay.airplay.mainbuffered

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import kotlin.collections.AbstractList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MainBufferedApTransportControlTest {
    @Test
    fun typedHeaderViewReadsEveryExactBigEndianField() {
        val request = syncRequest(
            groupId = Long.MIN_VALUE,
            messageType = fourCc("strt"),
            replyToken = -1L,
            status = -7,
            body = mapOf("rate" to 1L),
        )

        val header = MainBufferedApTransportControlHeader.view(request)

        assertEquals(request.encodedSize.toLong(), header.totalLength)
        assertEquals(MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC, header.packageType)
        assertEquals("sync", header.packageTypeFourCcOrNull())
        assertEquals(Long.MIN_VALUE, header.groupId)
        assertEquals(fourCc("strt"), header.messageType)
        assertEquals("strt", header.messageTypeFourCcOrNull())
        assertEquals(-1L, header.replyToken)
        assertEquals(-7, header.replyStatus)
    }

    @Test
    fun replyChangesOnlyPackageTypeStatusAndLengthAroundBoundedBplist() {
        val request = syncRequest(
            groupId = 0x0102_0304_0506_0708L,
            messageType = fourCc("anch"),
            replyToken = 0x1112_1314_1516_1718L,
            status = 123,
            body = mapOf("request" to true),
        )
        val mutableData = byteArrayOf(1, 2, 3)
        val responseBody = linkedMapOf<String, Any>(
            "status" to 0L,
            "payload" to mutableData,
            "items" to arrayListOf<Any>("one", 2L),
        )

        val reply = MainBufferedApTransportReplyEncoder.encodeReply(
            request = request,
            body = responseBody,
            replyStatus = -2,
        )
        mutableData[0] = 99
        responseBody.clear()

        val header = MainBufferedApTransportControlHeader.view(reply)
        assertEquals("rply", header.packageTypeFourCcOrNull())
        assertEquals(0x0102_0304_0506_0708L, header.groupId)
        assertEquals(fourCc("anch"), header.messageType)
        assertEquals(0x1112_1314_1516_1718L, header.replyToken)
        assertEquals(-2, header.replyStatus)

        val decoded = BplistCodec.decode(reply.body) as Map<*, *>
        assertEquals(0L, decoded["status"])
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded["payload"] as ByteArray)
        assertEquals(listOf("one", 2L), decoded["items"])
    }

    @Test
    fun replyRejectsNonSyncSourceWithoutInventingRouting() {
        val template = headerTemplate(
            packageType = MainBufferedApTransportControlHeader.PACKAGE_TYPE_REPLY,
            groupId = 1L,
            replyToken = 2L,
            status = 0,
        )
        val source = ApTransportPackageCodec.create(
            messageType = fourCc("anch"),
            body = BplistCodec.encode(mapOf("x" to 1L)),
            uninterpretedHeader = template,
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(source, mapOf("ok" to true))
        }
        assertTrue(failure.message!!.contains("'sync'"))
    }

    @Test
    fun headerDecoderRequiresACompleteExactPackage() {
        val encoded = syncRequest(
            groupId = 1L,
            messageType = fourCc("fshb"),
            replyToken = 2L,
            status = 0,
            body = emptyMap<String, Any>(),
        ).encoded()

        assertThrows(ApapProtocolException::class.java) {
            MainBufferedApTransportControlHeader.decodeHeader(encoded.copyOf(31))
        }
        val wrongLength = encoded.copyOf().also { it[3] = (it[3] - 1).toByte() }
        val failure = assertThrows(ApapProtocolException::class.java) {
            MainBufferedApTransportControlHeader.decodeHeader(wrongLength)
        }
        assertTrue(failure.message!!.contains("does not match"))
    }

    @Test
    fun boundedBplistSnapshotRejectsCyclesUnsupportedValuesAndNonFiniteNumbers() {
        val request = syncRequest(1L, fourCc("test"), 2L, 0, emptyMap<String, Any>())
        val cycle = arrayListOf<Any>()
        cycle.add(cycle)

        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(request, mapOf("cycle" to cycle))
        }
        @Suppress("UNCHECKED_CAST")
        val invalidKeys = mapOf<Any, Any>(1 to true) as Map<String, Any?>
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(request, invalidKeys)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(request, mapOf("x" to Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(request, mapOf("x" to Any()))
        }
    }

    @Test
    fun boundedBplistSnapshotDoesNotTrustHostileListSize() {
        val request = syncRequest(1L, fourCc("test"), 2L, 0, emptyMap<String, Any>())
        val hostile = object : AbstractList<Any>() {
            override val size: Int
                get() = error("size must not be read")

            override fun get(index: Int): Any = index

            override fun iterator(): Iterator<Any> = object : Iterator<Any> {
                private var index = 0
                override fun hasNext(): Boolean = true
                override fun next(): Any = index++
            }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(
                request = request,
                body = mapOf("items" to hostile),
                bplistLimits = MainBufferedReplyBplistLimits(
                    maxContainerItems = 3,
                    maxNodes = 8,
                ),
            )
        }
        assertTrue(failure.message!!.contains("3 items"))
    }

    @Test
    fun replyBoundsDataStringsNodesAndFinalPackage() {
        val request = syncRequest(1L, fourCc("test"), 2L, 0, emptyMap<String, Any>())
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(
                request,
                mapOf("data" to ByteArray(4)),
                bplistLimits = MainBufferedReplyBplistLimits(maxDataBytes = 3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(
                request,
                mapOf("text" to "four"),
                bplistLimits = MainBufferedReplyBplistLimits(maxStringChars = 3),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(
                request,
                mapOf("items" to listOf(1L, 2L)),
                bplistLimits = MainBufferedReplyBplistLimits(maxNodes = 4),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MainBufferedApTransportReplyEncoder.encodeReply(
                request,
                mapOf("payload" to ByteArray(128)),
                maxPackageBytes = 64,
            )
        }
    }

    private fun syncRequest(
        groupId: Long,
        messageType: Int,
        replyToken: Long,
        status: Int,
        body: Any,
    ) = ApTransportPackageCodec.create(
        messageType = messageType,
        body = BplistCodec.encode(body),
        uninterpretedHeader = headerTemplate(
            packageType = MainBufferedApTransportControlHeader.PACKAGE_TYPE_SYNC,
            groupId = groupId,
            replyToken = replyToken,
            status = status,
        ),
    )

    private fun headerTemplate(
        packageType: Int,
        groupId: Long,
        replyToken: Long,
        status: Int,
    ): ByteArray = ByteArray(32).also { header ->
        putU32Be(header, 4, packageType)
        putU64Be(header, 8, groupId)
        putU64Be(header, 20, replyToken)
        putU32Be(header, 28, status)
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putU64Be(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            target[offset + index] = (value ushr (56 - index * 8)).toByte()
        }
    }

    private fun fourCc(value: String): Int {
        require(value.length == 4)
        return value.toByteArray(Charsets.US_ASCII).fold(0) { result, byte ->
            result shl 8 or (byte.toInt() and 0xff)
        }
    }
}
