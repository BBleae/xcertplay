package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiP2pLifecycleSafetyTest {
    @Test
    fun immediateIncompleteGroupCallbacksReceiveBoundedRetryDelay() {
        val cap = TimeUnit.MILLISECONDS.toNanos(250)
        assertEquals(cap, wifiP2pGroupRetryDelayNanos(TimeUnit.SECONDS.toNanos(20)))
        assertEquals(TimeUnit.MILLISECONDS.toNanos(17),
            wifiP2pGroupRetryDelayNanos(TimeUnit.MILLISECONDS.toNanos(17)))
        assertEquals(0L, wifiP2pGroupRetryDelayNanos(0L))
        assertEquals(0L, wifiP2pGroupRetryDelayNanos(-1L))

        val waits = arrayListOf<Long>()
        paceWifiP2pGroupRetry(TimeUnit.SECONDS.toNanos(20)) { delay -> waits += delay }
        paceWifiP2pGroupRetry(TimeUnit.MILLISECONDS.toNanos(17)) { delay -> waits += delay }
        paceWifiP2pGroupRetry(0) { delay -> waits += delay }
        assertEquals(listOf(cap, TimeUnit.MILLISECONDS.toNanos(17)), waits)
    }

    @Test
    fun channelLossAfterCreateIssuedAlwaysRetainsOwner() {
        assertTrue(retainWifiP2pOwnerOnChannelLoss(true, true))
        assertFalse(retainWifiP2pOwnerOnChannelLoss(false, true))
        assertFalse(retainWifiP2pOwnerOnChannelLoss(true, false))
    }

    @Test
    fun disconnectedChannelProbeAttemptsAreBoundedAndCannotLoopForever() {
        var probes = 0
        var pauses = 0
        assertEquals(3, runBoundedDisconnectedP2pProbes(
            maxProbes = 3,
            probe = { probes++ },
            pauseBetween = { pauses++; true },
        ))
        assertEquals(3, probes)
        assertEquals(2, pauses)

        probes = 0
        pauses = 0
        assertEquals(1, runBoundedDisconnectedP2pProbes(
            maxProbes = 3,
            probe = { probes++ },
            pauseBetween = { pauses++; false },
        ))
        assertEquals(1, probes)
        assertEquals(1, pauses)
    }

    @Test
    fun scopedIpv6EndpointRequiresTheSameExactInterfaceScope() {
        val bytes = InetAddress.getByName("fe80::1").address
        val published = Inet6Address.getByAddress(null, bytes, 17)
        val liveSame = Inet6Address.getByAddress(null, bytes, 17)
        val liveOtherInterface = Inet6Address.getByAddress(null, bytes, 18)

        assertTrue(sameWifiP2pEndpoint(published, liveSame))
        assertFalse(sameWifiP2pEndpoint(published, liveOtherInterface))
        assertFalse(sameWifiP2pEndpoint(published, InetAddress.getByName("192.168.49.1")))
    }
}
