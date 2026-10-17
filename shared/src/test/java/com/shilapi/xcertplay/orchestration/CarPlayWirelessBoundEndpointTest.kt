package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayWirelessBoundEndpointTest {
    @Test
    fun advertisesScopedIpv6PrimaryAndOnlyActuallyBoundIpv4Secondary() {
        val primary = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 17)
        val secondary = InetAddress.getByName("192.168.49.1")

        assertEquals(
            listOf(primary, secondary),
            boundWirelessEndpointAddresses(primary, secondary, listOf(primary, secondary)),
        )
        assertEquals(
            listOf(primary),
            boundWirelessEndpointAddresses(primary, secondary, listOf(primary)),
        )
    }

    @Test
    fun cannotAdvertiseUnboundPrimaryOrUnexpectedAddress() {
        val primary = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 17)
        val secondary = InetAddress.getByName("192.168.49.1")
        val unrelated = InetAddress.getByName("192.168.49.2")

        assertTrue(
            assertThrows(IOException::class.java) {
                boundWirelessEndpointAddresses(primary, secondary, listOf(secondary))
            }.message!!.contains("primary"),
        )
        assertTrue(
            assertThrows(IOException::class.java) {
                boundWirelessEndpointAddresses(primary, secondary, listOf(primary, unrelated))
            }.message!!.contains("unexpected"),
        )
    }

    @Test
    fun ipv4OnlyFallbackUsesItsActuallyBoundSinglePrimary() {
        val primary = InetAddress.getByName("192.168.49.1")
        assertEquals(
            listOf(primary),
            boundWirelessEndpointAddresses(primary, null, listOf(primary)),
        )
    }

    @Test
    fun p2pAttachmentRejectsMissingOrChangedGroupAndInterfaceAddress() {
        val primary = InetAddress.getByName("192.168.49.1")
        fun group(
            ssid: String = "DIRECT-xcertplay",
            address: InetAddress = primary,
            backend: WirelessHotspotBackend = WirelessHotspotBackend.WIFI_P2P,
        ) = WirelessHotspotInfo(
            ssid = ssid,
            passphrase = "test-passphrase",
            security = Iap2WirelessSecurity.WPA_WPA2,
            channel = 36,
            frequencyMHz = 5180,
            bssid = "02:00:00:00:00:01",
            interfaceName = "p2p0",
            hostAddress = address,
            bandLabel = "5 GHz",
            backend = backend,
        )
        val attached = group()

        assertTrue(matchesWirelessHotspotAttachment(attached, group()))
        assertFalse(matchesWirelessHotspotAttachment(attached, null))
        assertFalse(matchesWirelessHotspotAttachment(attached, group(ssid = "DIRECT-other")))
        assertFalse(
            matchesWirelessHotspotAttachment(
                attached,
                group(address = InetAddress.getByName("192.168.49.2")),
            ),
        )

        var p2pQueries = 0
        assertEquals(attached, wirelessHotspotForControlSend(attached) {
            p2pQueries++
            attached
        })
        assertEquals(1, p2pQueries)
        assertEquals(null, wirelessHotspotForControlSend(attached) { null })

        // Local-only and manual hotspot keep their prior listener-bound path: neither has
        // WifiP2pGroupManager's exact group evidence, so this is not equivalent P2P proof.
        for (backend in listOf(
            WirelessHotspotBackend.LOCAL_ONLY_HOTSPOT,
            WirelessHotspotBackend.MANUAL_HOTSPOT,
        )) {
            val original = group(backend = backend)
            assertEquals(original, wirelessHotspotForControlSend(original) {
                p2pQueries++
                null
            })
            assertTrue(matchesWirelessHotspotAttachment(original, original))
        }
        assertEquals(1, p2pQueries)
    }
}
