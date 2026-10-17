package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspMessageTest {
    @Test
    fun copiedBodiesAndPartialRemainderSurviveSourceBufferRetirement() {
        val firstBody = byteArrayOf(1, 2, 3)
        val secondBody = byteArrayOf(4, 5, 6, 7)
        val first = request("SETUP", firstBody)
        val second = request("POST", secondBody)
        val split = second.size - 2
        val source = first + second.copyOf(split)

        val parsed = RtspMessage.parseMessages(source)
        source.fill(0)
        assertEquals(1, parsed.messages.size)
        assertArrayEquals(firstBody, parsed.messages.single().body)
        assertArrayEquals(second.copyOf(split), parsed.rest)

        val resumed = RtspMessage.parseMessages(parsed.rest +
            second.copyOfRange(split, second.size))
        assertEquals(1, resumed.messages.size)
        assertArrayEquals(secondBody, resumed.messages.single().body)
        assertTrue(resumed.rest.isEmpty())
    }

    @Test
    fun ordinaryPipelinedRequestsRetainDistinctBodiesAfterFrameCopyIsWiped() {
        val source = request("SETUP", byteArrayOf(9, 8)) +
            request("POST", byteArrayOf(7, 6, 5))
        val parsed = RtspMessage.parseMessages(source)
        source.fill(0)
        assertEquals(2, parsed.messages.size)
        assertArrayEquals(byteArrayOf(9, 8), parsed.messages[0].body)
        assertArrayEquals(byteArrayOf(7, 6, 5), parsed.messages[1].body)
        assertTrue(parsed.rest.isEmpty())
    }

    private fun request(method: String, body: ByteArray): ByteArray =
        ("$method / RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body.size}\r\n\r\n")
            .toByteArray(Charsets.US_ASCII) + body
}
