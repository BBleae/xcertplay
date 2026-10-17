package com.shilapi.xcertplay.airplay.rcs

import com.shilapi.xcertplay.airplay.rcs.transport.RcsFrameCodec
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RcsChannelTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val serverReadKey = ByteArray(32) { 3 }
    private val serverWriteKey = ByteArray(32) { 4 }

    @Test
    fun listenerBindsExactlyTheRequestedAddressAndExchangesMessages() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
            expectedPeerAddress = loopback,
        )
        assertEquals(loopback, listener.localAddress)
        assertFalse(listener.localAddress.isAnyLocalAddress)

        val client = RcsChannel.connect(
            remote = InetSocketAddress(loopback, listener.port),
            readKey = serverWriteKey,
            writeKey = serverReadKey,
        )
        val server = listener.accept(2_000)!!
        try {
            client.sendComm(byteArrayOf(7, 8, 9))
            val received = server.receiveNext(2_000)!!
            assertEquals(0x636f6d6d, received.messageType)
            assertArrayEquals(byteArrayOf(7, 8, 9), received.body)

            server.sendComm(byteArrayOf(10, 11))
            assertArrayEquals(byteArrayOf(10, 11), client.receiveNext(2_000)!!.body)
        } finally {
            server.close()
            client.close()
            listener.close()
        }
    }

    @Test
    fun listenerRejectsAConnectionFromTheWrongPeerBeforeCreatingAChannel() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
            expectedPeerAddress = InetAddress.getByName("127.0.0.2"),
        )
        val socket = Socket()
        socket.bind(InetSocketAddress(loopback, 0))
        socket.connect(InetSocketAddress(loopback, listener.port), 2_000)
        try {
            assertThrows(RcsPeerRejectedException::class.java) {
                listener.accept(2_000)
            }
        } finally {
            socket.close()
            listener.close()
        }
    }

    @Test
    fun receiveTimeoutDoesNotCloseOrDesynchronizeTheChannel() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
        )
        val client = RcsChannel.connect(
            remote = InetSocketAddress(loopback, listener.port),
            readKey = serverWriteKey,
            writeKey = serverReadKey,
        )
        val server = listener.accept(2_000)!!
        try {
            assertNull(server.receiveNext(40))
            assertFalse(server.isClosed)
            client.sendComm(byteArrayOf(42))
            assertArrayEquals(byteArrayOf(42), server.receiveNext(2_000)!!.body)
        } finally {
            server.close()
            client.close()
            listener.close()
        }
    }

    @Test
    fun outboundCommTravelsAsAsyncCmndOnTheWire() {
        // iOS 24A446 delivers only accessory 'cmnd' packages to the RCS client; 'comm' is dropped.
        val listener = RcsChannel.listen(serverReadKey, serverWriteKey, loopback)
        val rawClient = Socket()
        rawClient.connect(InetSocketAddress(loopback, listener.port), 2_000)
        rawClient.soTimeout = 2_000
        val server = listener.accept(2_000)!!
        val phone = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        try {
            server.sendComm(byteArrayOf(5, 6, 7))
            val input = rawClient.getInputStream()
            var buffered = ByteArray(0)
            var plaintext = ByteArray(0)
            while (plaintext.size < 35) {
                val chunk = ByteArray(256)
                val read = input.read(chunk)
                check(read > 0) { "server closed before a full package" }
                buffered += chunk.copyOf(read)
                val decrypted = phone.decrypt(buffered)
                plaintext += decrypted.data
                buffered = decrypted.rest
            }
            fun u32(offset: Int) = ((plaintext[offset].toInt() and 0xff) shl 24) or
                ((plaintext[offset + 1].toInt() and 0xff) shl 16) or
                ((plaintext[offset + 2].toInt() and 0xff) shl 8) or
                (plaintext[offset + 3].toInt() and 0xff)
            assertEquals(35, u32(0))
            assertEquals(0x6173796e, u32(4)) // 'asyn'
            assertEquals(0x636d6e64, u32(16)) // 'cmnd', never 'comm'
            assertArrayEquals(byteArrayOf(5, 6, 7), plaintext.copyOfRange(32, 35))
        } finally {
            phone.close()
            rawClient.close()
            server.close()
            listener.close()
        }
    }

    @Test
    fun closeCannotDeliverASecondPackageAlreadyDecodedFromOneFrame() {
        val listener = RcsChannel.listen(serverReadKey, serverWriteKey, loopback)
        val rawClient = Socket()
        rawClient.connect(InetSocketAddress(loopback, listener.port), 2_000)
        val server = listener.accept(2_000)!!
        val sender = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        try {
            val joined = RcsMessage.comm(byteArrayOf(1)).encoded() +
                RcsMessage.comm(byteArrayOf(2)).encoded()
            rawClient.getOutputStream().apply {
                write(sender.encrypt(joined))
                flush()
            }
            assertArrayEquals(byteArrayOf(1), server.receiveNext(2_000)!!.body)
            server.close()
            assertThrows(IOException::class.java) { server.receiveNext(2_000) }
        } finally {
            sender.close()
            rawClient.close()
            server.close()
            listener.close()
        }
    }

    @Test
    fun negativeAndWrappingNanoTimeOriginsPreserveReceiveBudget() {
        var now = -5_000_000L
        val negative = RcsReceiveDeadline(10) { now }
        assertEquals(10L, negative.remainingMillis())
        now = 1_000_000L
        assertEquals(4L, negative.remainingMillis())
        now = 5_000_000L
        assertEquals(0L, negative.remainingMillis())

        now = Long.MAX_VALUE - 2_000_000L
        val wrapping = RcsReceiveDeadline(5) { now }
        now = Long.MIN_VALUE + 999_999L
        assertEquals(2L, wrapping.remainingMillis())
    }

    @Test
    fun malformedKeysFailBeforeListenAndAcceptedSocketOwnershipIsClosedOnOpenFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            RcsChannel.listen(ByteArray(31), serverWriteKey, loopback)
        }
        val socket = Socket()
        assertThrows(IllegalArgumentException::class.java) {
            RcsChannel.open(socket, ByteArray(31), serverWriteKey)
        }
        assertTrue(socket.isClosed)
    }

    @Test
    fun multiFramePackageSurvivesFragmentedSocketReadsWithoutLosingRemainders() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
        )
        val client = RcsChannel.connect(
            remote = InetSocketAddress(loopback, listener.port),
            readKey = serverWriteKey,
            writeKey = serverReadKey,
        )
        val server = listener.accept(2_000)!!
        val body = ByteArray(128 * 1024) { index -> (index * 31).toByte() }
        try {
            client.sendComm(body)
            assertArrayEquals(body, server.receiveNext(5_000)!!.body)
        } finally {
            server.close()
            client.close()
            listener.close()
        }
    }

    @Test
    fun truncatedEncryptedFrameAtEofIsAProtocolFailure() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
        )
        val rawClient = Socket()
        rawClient.connect(InetSocketAddress(loopback, listener.port), 2_000)
        val server = listener.accept(2_000)!!
        val sender = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        val encrypted = sender.encrypt(RcsMessage.comm(byteArrayOf(1, 2, 3)).encoded())
        rawClient.getOutputStream().write(encrypted, 0, encrypted.size / 2)
        rawClient.close()
        try {
            assertThrows(RcsProtocolException::class.java) {
                server.receiveNext(2_000)
            }
            assertTrue(server.isClosed)
        } finally {
            server.close()
            listener.close()
        }
    }

    @Test
    fun oversizedEncryptedLengthFailsBeforeWaitingForItsBody() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
        )
        val rawClient = Socket()
        rawClient.connect(InetSocketAddress(loopback, listener.port), 2_000)
        val server = listener.accept(2_000)!!
        try {
            rawClient.getOutputStream().apply {
                write(byteArrayOf(0x01, 0x40)) // 0x4001 plaintext bytes.
                flush()
            }
            assertThrows(RcsProtocolException::class.java) {
                server.receiveNext(2_000)
            }
            assertTrue(server.isClosed)
        } finally {
            rawClient.close()
            server.close()
            listener.close()
        }
    }

    @Test
    fun authenticatedButTruncatedPackageAtEofIsAProtocolFailure() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
        )
        val rawClient = Socket()
        rawClient.connect(InetSocketAddress(loopback, listener.port), 2_000)
        val server = listener.accept(2_000)!!
        val sender = RcsFrameCodec.duplex(serverWriteKey, serverReadKey)
        val partialPackage = RcsMessage.comm(byteArrayOf(4, 5, 6)).encoded().copyOf(10)
        rawClient.getOutputStream().apply {
            write(sender.encrypt(partialPackage))
            flush()
        }
        rawClient.close()
        try {
            assertThrows(RcsProtocolException::class.java) {
                server.receiveNext(2_000)
            }
            assertTrue(server.isClosed)
        } finally {
            server.close()
            listener.close()
        }
    }

    @Test
    fun listenerCapsAcceptedCandidatesAndCloseIsIdempotent() {
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
            maxAcceptedSockets = 1,
        )
        val first = RcsChannel.connect(
            remote = InetSocketAddress(loopback, listener.port),
            readKey = serverWriteKey,
            writeKey = serverReadKey,
        )
        val accepted = listener.accept(2_000)!!
        val ownedCodec = RcsChannel::class.java.getDeclaredField("frameCodec").run {
            isAccessible = true
            get(accepted) as RcsFrameCodec
        }
        val listenerReadKey = RcsListener::class.java.getDeclaredField("readKey").run {
            isAccessible = true
            get(listener) as ByteArray
        }
        val listenerWriteKey = RcsListener::class.java.getDeclaredField("writeKey").run {
            isAccessible = true
            get(listener) as ByteArray
        }
        try {
            assertThrows(RcsCandidateLimitException::class.java) {
                listener.accept(20)
            }
            listener.close()
            listener.close()
            assertTrue(listenerReadKey.all { it == 0.toByte() })
            assertTrue(listenerWriteKey.all { it == 0.toByte() })
            accepted.close()
            accepted.close()
            assertTrue(accepted.isClosed)
            assertThrows(IllegalStateException::class.java) {
                ownedCodec.decrypt(ByteArray(0))
            }
        } finally {
            first.close()
            accepted.close()
            listener.close()
        }
    }

    @Test
    fun tracesContainMetadataButNeverMessageBodyBytes() {
        val traces = mutableListOf<String>()
        val listener = RcsChannel.listen(
            readKey = serverReadKey,
            writeKey = serverWriteKey,
            bindAddress = loopback,
            onTrace = traces::add,
        )
        val client = RcsChannel.connect(
            remote = InetSocketAddress(loopback, listener.port),
            readKey = serverWriteKey,
            writeKey = serverReadKey,
        )
        val server = listener.accept(2_000)!!
        val secretBody = "do-not-log-this-body".toByteArray()
        try {
            client.sendComm(secretBody)
            server.receiveNext(2_000)
            assertTrue(traces.any { "bodyBytes=${secretBody.size}" in it })
            assertFalse(traces.any { "do-not-log-this-body" in it })
            assertTrue(traces.all { it.length <= 512 })
        } finally {
            server.close()
            client.close()
            listener.close()
        }
    }
}
