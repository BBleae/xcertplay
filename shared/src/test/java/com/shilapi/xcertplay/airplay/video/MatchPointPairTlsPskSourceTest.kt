package com.shilapi.xcertplay.airplay.video

import androidx.media3.common.C
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPointPairTlsPskSourceTest {
    @Test
    fun requestPinsVersionOneRouteAndExplicitRange() {
        val request = MatchPointPskHttpFraming.request(
            URI("https://127.0.0.1:8443/1/route/segment.ts?token=a%2Bb"), 32, 64,
        )
        val wire = String(request.bytes, Charsets.US_ASCII)
        assertTrue(wire.startsWith("GET /1/route/segment.ts?token=a%2Bb HTTP/1.1\r\n"))
        assertTrue(wire.contains("Host: 127.0.0.1:8443\r\n"))
        assertTrue(wire.contains("Range: bytes=32-95\r\n"))
        assertEquals(95L, request.endInclusive)
    }

    @Test
    fun requestRejectsOtherRoutesTraversalAndOverflow() {
        for (path in listOf("/2/route", "/1/", "/1/../other", "/1/%2e%2e/other", "/1/a%2fb")) {
            assertThrows(IOException::class.java) {
                MatchPointPskHttpFraming.request(URI("https://127.0.0.1:443$path"), 0, -1)
            }
        }
        assertThrows(IOException::class.java) {
            MatchPointPskHttpFraming.request(URI("https://127.0.0.1/1/route"), Long.MAX_VALUE, 2)
        }
    }

    @Test
    fun responseRequiresExactContentRangeAndLength() {
        val accepted = response(
            "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Range: bytes 32-95/128\r\nContent-Length: 64\r\n\r\n",
            32, 95, true, 64,
        )
        assertEquals(64, accepted.bodyLength)
        assertEquals(listOf("bytes 32-95/128"), accepted.headers["content-range"])
        for (framing in listOf(
            "Content-Range: bytes 31-95/128\r\nContent-Length: 65\r\n",
            "Content-Range: bytes 32-96/128\r\nContent-Length: 65\r\n",
            "Content-Range: bytes 32-95/95\r\nContent-Length: 64\r\n",
            "Content-Range: bytes 32-95/128\r\nContent-Length: 63\r\n",
        )) {
            assertThrows(IOException::class.java) {
                response("HTTP/1.1 206 Partial Content\r\n$framing\r\n", 32, 95, true, 64)
            }
        }
    }

    @Test
    fun responseNeverFollowsRedirectOrAcceptsChunkedAmbiguity() {
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 302 Found\r\nLocation: https://other/\r\n\r\n", 0, null, false, -1)
        }
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", 0, null, false, -1)
        }
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n", 0, null, false, -1)
        }
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 1\r\n\r\n", 0, null, false, -1)
        }
    }

    @Test
    fun responseMustHaveBoundedBodyAndHeaders() {
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 200 OK\r\n\r\n", 0, null, false, C.LENGTH_UNSET.toLong())
        }
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 200 OK\r\nContent-Length: 536870913\r\n\r\n", 0, null, false, -1)
        }
        assertThrows(IOException::class.java) {
            response("HTTP/1.1 200 OK\r\nX-Test: ${"a".repeat(16_385)}\r\n\r\n", 0, null, false, 1)
        }
        assertEquals(
            2,
            response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n", 0, 1, true, 2).bodyLength,
        )
    }

    @Test
    fun explicitLoopbackSocketAuthorityRejectsWrongGenerationAndRevocation() {
        val sessionIdentity = MatchPointSessionIdentity.create(801)
        val other = MatchPointSessionIdentity.create(801)
        var current = true
        var created = 0
        val authority = object : MatchPointSessionNetworkSocketAuthority {
            override val identity = sessionIdentity
            override fun isCurrent(): Boolean = current
            override fun createSocket(): Socket = Socket().also { created++ }
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1_000
            val socket = MatchPointPskBoundSocketOpening.create(authority, sessionIdentity) {
                authority.isCurrent()
            }
            socket.use {
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                server.accept().use { accepted -> assertTrue(accepted.isConnected) }
            }
        }
        assertEquals(1, created)
        assertThrows(IOException::class.java) {
            MatchPointPskBoundSocketOpening.create(authority, other) { authority.isCurrent() }
        }
        assertEquals(1, created)
        current = false
        assertThrows(IOException::class.java) {
            MatchPointPskBoundSocketOpening.create(authority, sessionIdentity) { authority.isCurrent() }
        }
        assertEquals(1, created)

        var orphan: Socket? = null
        val revokedDuringCreation = object : MatchPointSessionNetworkSocketAuthority {
            override val identity = sessionIdentity
            override fun isCurrent(): Boolean = current
            override fun createSocket(): Socket = Socket().also { orphan = it; current = false }
        }
        current = true
        assertThrows(IOException::class.java) {
            MatchPointPskBoundSocketOpening.create(revokedDuringCreation, sessionIdentity) {
                revokedDuringCreation.isCurrent()
            }
        }
        assertTrue(orphan?.isClosed == true)
    }

    @Test
    fun absoluteOpenDeadlineInterruptsSlowHeaderDribble() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1_000
            val dribbler = Thread({
                try {
                    server.accept().use { accepted ->
                        val output = accepted.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-Test: ".toByteArray())
                        output.flush()
                        repeat(20) {
                            Thread.sleep(50)
                            output.write('x'.code)
                            output.flush()
                        }
                    }
                } catch (_: Exception) { /* The deadline intentionally closes the peer. */ }
            }, "matchpoint-psk-dribble-test").apply { isDaemon = true; start() }
            Socket().use { socket ->
                socket.soTimeout = 1_000
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                val start = System.nanoTime()
                MatchPointPskSocketDeadline(socket, 150).use { deadline ->
                    assertThrows(IOException::class.java) {
                        MatchPointPskHttpFraming.response(socket.getInputStream(), 0, null, false, -1)
                    }
                    assertTrue(deadline.timedOut)
                }
                assertTrue((System.nanoTime() - start) < 800_000_000L)
            }
            dribbler.join(2_000)
            assertFalse(dribbler.isAlive)
        }
    }

    @Test
    fun absoluteWholeResponseDeadlineInterruptsBodyDribble() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 1_000
            val dribbler = Thread({
                try {
                    server.accept().use { accepted ->
                        val output = accepted.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray())
                        output.flush()
                        repeat(100) {
                            Thread.sleep(50)
                            output.write('x'.code)
                            output.flush()
                        }
                    }
                } catch (_: Exception) { /* Deadline aborts the socket. */ }
            }, "matchpoint-psk-body-dribble-test").apply { isDaemon = true; start() }
            Socket().use { socket ->
                socket.soTimeout = 1_000
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                val start = System.nanoTime()
                MatchPointPskSocketDeadline(socket, 180).use { deadline ->
                    val body = MatchPointPskHttpFraming.response(
                        socket.getInputStream(), 0, null, false, -1,
                    )
                    assertEquals(100L, body.bodyLength)
                    assertThrows(IOException::class.java) {
                        repeat(body.bodyLength.toInt()) {
                            deadline.requireAlive()
                            socket.getInputStream().read()
                        }
                    }
                    assertTrue(deadline.timedOut)
                }
                assertTrue((System.nanoTime() - start) < 800_000_000L)
            }
            dribbler.join(2_000)
            assertFalse(dribbler.isAlive)
        }
    }

    @Test
    fun pskTls12RoundTripUsesOnlyPinnedGcmSuiteAndClearsBorrowedArrays() {
        val identity = byteArrayOf(1, 2, 3)
        val serverPsk = ByteArray(16) { (it + 1).toByte() }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { serverSocket ->
            serverSocket.soTimeout = 3_000
            val serverFailure = AtomicReference<Throwable?>()
            val serverThread = Thread({
                try {
                    serverSocket.accept().use { accepted ->
                        accepted.soTimeout = 3_000
                        val server = TlsServerProtocol(accepted.getInputStream(), accepted.getOutputStream())
                        server.accept(object : PSKTlsServer(
                            BcTlsCrypto(SecureRandom()),
                            object : TlsPSKIdentityManager {
                                override fun getHint(): ByteArray? = null
                                override fun getPSK(received: ByteArray): ByteArray? =
                                    if (received.contentEquals(identity)) serverPsk.copyOf() else null
                            },
                        ) {
                            override fun getSupportedCipherSuites(): IntArray = intArrayOf(
                                CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256,
                            )
                        })
                        val request = ByteArray(128)
                        assertTrue(server.inputStream.read(request) > 0)
                        server.outputStream.write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray(),
                        )
                        server.outputStream.flush()
                        server.close()
                    }
                } catch (failure: Throwable) {
                    serverFailure.set(failure)
                }
            }, "matchpoint-psk-test-server").apply { isDaemon = true; start() }
            try {
                Socket().use { socket ->
                    socket.soTimeout = 3_000
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), serverSocket.localPort), 3_000)
                    val clientIdentity = identity.copyOf()
                    val clientPsk = serverPsk.copyOf()
                    val tls = MatchPointPskTlsHandshake.connect(socket, clientIdentity, clientPsk)
                    assertArrayEquals(ByteArray(16), clientPsk)
                    assertArrayEquals(ByteArray(3), clientIdentity)
                    tls.outputStream.write("GET /1/route HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    tls.outputStream.flush()
                    val response = MatchPointPskHttpFraming.response(tls.inputStream, 0, null, false, -1)
                    assertEquals(2, response.bodyLength)
                    assertEquals('o'.code, tls.inputStream.read())
                    assertEquals('k'.code, tls.inputStream.read())
                    tls.close()
                }
            } finally {
                serverThread.join(4_000)
                assertFalse("loopback PSK server hung", serverThread.isAlive)
            }
            serverFailure.get()?.let { throw AssertionError("loopback PSK server failed", it) }
        }
    }

    private fun response(
        wire: String,
        position: Long,
        end: Long?,
        ranged: Boolean,
        requestedLength: Long,
    ): MatchPointPskHttpFraming.Response = MatchPointPskHttpFraming.response(
        ByteArrayInputStream(wire.toByteArray(Charsets.US_ASCII)),
        position,
        end,
        ranged,
        requestedLength,
    )
}
