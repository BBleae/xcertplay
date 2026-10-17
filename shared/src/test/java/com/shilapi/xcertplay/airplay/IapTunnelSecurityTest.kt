package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IapTunnelSecurityTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun secureModeBindsOnlyTheExactAddressAndAcceptsTheExpectedPeer() {
        val traces = CopyOnWriteArrayList<String>()
        val opened = CountDownLatch(1)
        val tunnel = IapTunnel(
            readKey = ByteArray(32),
            bindAddress = loopback,
            exactBind = true,
            expectedPeerAddress = loopback,
        )
        val port = tunnel.listen(
            object : IapTunnel.Listener {
                override fun onOpen(remoteAddress: String?) {
                    opened.countDown()
                }

                override fun onDebug(message: String) {
                    traces += message
                }
            },
        )
        val client = Socket(loopback, port)
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            assertTrue(traces.any { "listener bound=/127.0.0.1:" in it })
            assertFalse(traces.any { "secondary listener" in it })
            assertTrue(traces.all { it.length <= 512 })
        } finally {
            client.close()
            tunnel.close()
        }
    }

    @Test
    fun unexpectedPeerCannotClaimTheTunnel() {
        val expected = InetAddress.getByName("192.0.2.1")
        val opened = CountDownLatch(1)
        val openCount = AtomicInteger()
        val tunnel = IapTunnel(
            readKey = ByteArray(32),
            bindAddress = loopback,
            exactBind = true,
            expectedPeerAddress = expected,
        )
        val port = tunnel.listen(
            object : IapTunnel.Listener {
                override fun onOpen(remoteAddress: String?) {
                    openCount.incrementAndGet()
                    opened.countDown()
                }
            },
        )

        Socket().use { wrong ->
            wrong.bind(InetSocketAddress(loopback, 0))
            wrong.connect(InetSocketAddress(loopback, port), 2_000)
        }
        assertFalse(opened.await(100, TimeUnit.MILLISECONDS))
        assertEquals(0, openCount.get())
        tunnel.close()
    }

    @Test
    fun dualFamilyLegacyListenersCannotOverwriteAnActiveConnection() {
        val opened = CountDownLatch(1)
        val openCount = AtomicInteger()
        val traces = CopyOnWriteArrayList<String>()
        val tunnel = IapTunnel(
            readKey = ByteArray(32),
            bindAddress = loopback,
        )
        val port = tunnel.listen(
            object : IapTunnel.Listener {
                override fun onOpen(remoteAddress: String?) {
                    openCount.incrementAndGet()
                    opened.countDown()
                }

                override fun onDebug(message: String) {
                    traces += message
                }
            },
        )
        if (traces.none { "secondary listener bound=" in it }) {
            tunnel.close()
            assumeTrue("host does not permit a dual-family listener on one port", false)
        }
        val ipv4 = Socket(InetAddress.getByName("127.0.0.1"), port)
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        val ipv6 = Socket(InetAddress.getByName("::1"), port)
        try {
            Thread.sleep(100)
            assertEquals(1, openCount.get())
        } finally {
            ipv6.close()
            ipv4.close()
            tunnel.close()
        }
    }
}
