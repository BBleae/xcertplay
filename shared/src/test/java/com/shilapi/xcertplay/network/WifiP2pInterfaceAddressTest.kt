package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiP2pInterfaceAddressTest {
    @Test
    fun postCreateGroupNeverBorrowsMissingOrForeignCredentialsFromThisAttempt() {
        val expected = WifiP2pCredentials("DIRECT-xcertplay1234", "deadbeef")

        assertTrue(matchesWifiP2pStartCredentials(expected, expected.ssid, expected.passphrase))
        assertFalse(matchesWifiP2pStartCredentials(expected, "DIRECT-other", expected.passphrase))
        assertFalse(matchesWifiP2pStartCredentials(expected, expected.ssid, "otherpass"))
        assertFalse(matchesWifiP2pStartCredentials(expected, expected.ssid, null))
        assertFalse(matchesWifiP2pStartCredentials(expected, expected.ssid, ""))
    }

    @Test
    fun preservesScopedIpv6PrimaryAndAddsExactInterfaceIpv4RegardlessOfOrder() {
        val ipv4 = address("192.168.49.1")
        val ipv6 = address("fe80::1")

        for (addresses in listOf(listOf(ipv6, ipv4), listOf(ipv4, ipv6))) {
            val selected = select(addresses)!!
            assertTrue(selected.primary is Inet6Address)
            assertEquals(INTERFACE_INDEX, (selected.primary as Inet6Address).scopeId)
            assertEquals(ipv4, selected.secondary)
        }
    }

    @Test
    fun usesGroupOwnerIpv4OnlyWhenItExactlyBelongsToTheGroupInterface() {
        val first = address("192.168.49.1")
        val second = address("192.168.49.2")
        val ipv6 = address("fe80::1")

        assertEquals(second, select(listOf(ipv6, first, second), second)?.secondary)
        assertEquals(second, select(listOf(second, first, ipv6), second)?.secondary)
        assertNull(select(listOf(ipv6, first, second), address("192.168.49.3"))?.secondary)
        assertNull(select(listOf(first, second), address("192.168.49.3")))
    }

    @Test
    fun usesScopedLinkLocalIpv6EvenWhenIpv4IsAbsent() {
        val selected = select(listOf(address("fe80::1")))!!.primary

        assertTrue(selected is Inet6Address)
        assertEquals(INTERFACE_INDEX, (selected as Inet6Address).scopeId)
    }

    @Test
    fun usesOnlyUniqueVerifiedIpv4AsFallbackPrimary() {
        val ipv4 = address("192.168.49.1")
        val selected = select(listOf(ipv4))!!

        assertEquals(ipv4, selected.primary)
        assertNull(selected.secondary)
    }

    @Test
    fun rejectsIpv4AnyLoopbackLinkLocalAndMulticastAddresses() {
        val invalid = listOf("0.0.0.0", "127.0.0.1", "169.254.1.2", "224.0.0.1")
            .map(::address)

        assertNull(select(invalid))
        assertEquals(address("192.168.49.1"), select(invalid + address("192.168.49.1"))?.primary)
    }

    @Test
    fun rejectsAddressesReportedForAnotherInterface() {
        assertNull(
            selectWifiP2pInterfaceAddresses(
                groupInterfaceName = "p2p0",
                addressInterfaceName = "wlan0",
                interfaceIndex = INTERFACE_INDEX,
                addresses = listOf(address("192.168.49.1")),
                groupOwnerAddress = address("192.168.49.1"),
            ),
        )
    }

    @Test
    fun doesNotRescopeAnIpv6AddressFromAnotherInterface() {
        val foreignScoped = Inet6Address.getByAddress(
            null,
            address("fe80::1").address,
            INTERFACE_INDEX + 1,
        )

        assertNull(select(listOf(foreignScoped)))
        assertEquals(
            address("192.168.49.1"),
            select(listOf(foreignScoped, address("192.168.49.1")))?.primary,
        )
    }

    private fun select(
        addresses: Iterable<InetAddress>,
        groupOwnerAddress: InetAddress? = null,
    ): WifiP2pInterfaceAddresses? =
        selectWifiP2pInterfaceAddresses(
            groupInterfaceName = "p2p0",
            addressInterfaceName = "p2p0",
            interfaceIndex = INTERFACE_INDEX,
            addresses = addresses,
            groupOwnerAddress = groupOwnerAddress,
        )

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private companion object {
        const val INTERFACE_INDEX = 17
    }
}
