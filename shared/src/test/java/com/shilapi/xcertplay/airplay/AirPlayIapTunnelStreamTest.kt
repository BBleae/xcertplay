package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayIapTunnelStreamTest {
    @Test
    fun inputConnectionCloseKeepsEventChannelAvailableForOutboundIap() {
        val sent = mutableListOf<ByteArray>()
        val loopback = InetAddress.getByName("127.0.0.1")
        val peerEof = CountDownLatch(1)
        val session = testSession(
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) {
                    if (message.contains("AirPlay iAP tunnel peer EOF")) peerEof.countDown()
                }
            },
        )
        val bridge = AirPlayIapTunnelStream(
            session = session,
            tunnel = IapTunnel(
                readKey = ByteArray(32),
                bindAddress = loopback,
                exactBind = true,
                expectedPeerAddress = loopback,
            ),
            sendCommand = { data, _ ->
                sent += data.copyOf()
                true
            },
        )

        try {
            val port = bridge.listen()
            Socket().use { peer ->
                peer.connect(InetSocketAddress(loopback, port), 2_000)
            }
            assertTrue("server did not observe the inbound EOF", peerEof.await(2, TimeUnit.SECONDS))

            val payload = byteArrayOf(1, 2, 3)
            bridge.send(payload)

            assertTrue(sent.size == 1)
            assertArrayEquals(payload, sent.single())
        } finally {
            bridge.close()
            session.close()
        }
    }

    @Test
    fun ipv6ListenerAcceptsIpv6Loopback() {
        val loopback = InetAddress.getByName("::1")
        val session = testSession()
        val bridge = AirPlayIapTunnelStream(
            session = session,
            tunnel = IapTunnel(
                readKey = ByteArray(32),
                bindAddress = loopback,
                exactBind = true,
                expectedPeerAddress = loopback,
            ),
            sendCommand = { _, _ -> true },
        )

        try {
            val port = bridge.listen()
            Socket().use { peer ->
                peer.connect(InetSocketAddress(loopback, port), 2_000)
            }
        } finally {
            bridge.close()
            session.close()
        }
    }

    private fun testSession(
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
    ): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = listener,
        media = object : AirPlayMediaHandler {},
    )

}
