package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBonjourTest {
    private val config = AirPlayConfig(
        deviceName = "xcertplay",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        model = "LIVI",
    )
    private val identity = AirPlayIdentity(
        privateKey = ByteArray(32),
        publicKey = byteArrayOf(0x01, 0x23, 0xab.toByte()),
        pairingId = "pairing-1",
    )

    @Test
    fun airPlayTxtRecordsMatchLivi() {
        assertEquals(
            linkedMapOf(
                "deviceid" to "02:00:00:00:00:02",
                "features" to "0x44540380,0x61",
                "flags" to "0x4",
                "model" to "LIVI",
                "srcvers" to "366.0",
                "protovers" to "1.1",
                "pi" to "pairing-1",
                "pk" to "0123ab",
            ),
            CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
        )
    }

    @Test
    fun connectProbeRequestMatchesExactRequestLineAndHeaders() {
        assertEquals(
            "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                "Host: [fe80::1]:7000\r\n" +
                "User-Agent: AirPlay/366.0\r\n" +
                "AirPlay-Receiver-Device-ID: 2199023255554\r\n" +
                "Connection: close\r\n" +
                "\r\n",
            CarPlayBonjourProtocol.connectProbeRequest(
                host = "fe80::1%wlan0",
                port = 7000,
                sourceVersion = "366.0",
                deviceId = "02:00:00:00:00:02",
            ),
        )
    }

    @Test
    fun connectProbeRequestConvertsHexMacToUnsignedDecimal() {
        assertEquals("244837814094590", receiverDeviceIdHeader("de:ad:be:ef:ca:fe"))
        assertEquals("244837814094590", receiverDeviceIdHeader("deadbeefcafe"))
        assertEquals("281474976710655", receiverDeviceIdHeader("FF:FF:FF:FF:FF:FF"))
        assertEquals("0", receiverDeviceIdHeader("00:00:00:00:00:00"))
    }

    @Test
    fun connectProbeRequestRejectsNon48BitOrNonHexDeviceIds() {
        listOf(
            "",
            "00:00:00:00:00",
            "00:00:00:00:00:00:00",
            "00:00:00:00:00:0g",
            "02:00:00:00:00:02\r\nInjected: yes",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                receiverDeviceIdHeader(invalid)
            }
        }
    }

    @Test
    fun probeBindRequiresMatchingIpAddressFamilies() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val otherIpv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2))
        val ipv6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
        val otherIpv6 = InetAddress.getByAddress(ByteArray(16).apply { this[15] = 2 })

        assertTrue(canBindBonjourProbeTo(ipv4, otherIpv4))
        assertTrue(canBindBonjourProbeTo(ipv6, otherIpv6))
        assertFalse(canBindBonjourProbeTo(ipv4, ipv6))
        assertFalse(canBindBonjourProbeTo(ipv6, ipv4))
    }

    private fun receiverDeviceIdHeader(deviceId: String): String =
        CarPlayBonjourProtocol.connectProbeRequest(
            host = "192.0.2.1",
            port = 7000,
            sourceVersion = "366.0",
            deviceId = deviceId,
        ).split("\r\n")
            .single { it.startsWith("AirPlay-Receiver-Device-ID: ") }
            .substringAfter(": ")

    @Test
    fun workerCannotPublishRetirementFromItsOwnStack() {
        val failure = AtomicReference<Throwable?>()
        val attempted = CountDownLatch(1)
        val worker = Thread {
            try {
                proveCarPlayBonjourWorkerRetired(Thread.currentThread(), 100)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                attempted.countDown()
            }
        }

        worker.start()

        assertTrue(attempted.await(1, TimeUnit.SECONDS))
        assertTrue(failure.get() is IOException)
        proveCarPlayBonjourWorkerRetired(worker, 100)
    }

    @Test
    fun workerIsPublishedBeforeItsBodyCanRunAndRemainsPublishedOnStartError() {
        val published = AtomicReference<Thread?>()
        val observed = CountDownLatch(1)
        val worker = Thread {
            assertSame(Thread.currentThread(), published.get())
            observed.countDown()
        }

        startPublishedWorker(worker, published::set)

        assertTrue(observed.await(1, TimeUnit.SECONDS))
        worker.join(1_000)

        val failing = object : Thread() {
            override fun start() {
                throw AssertionError("start failed")
            }
        }
        try {
            startPublishedWorker(failing, published::set)
            throw AssertionError("start Error expected")
        } catch (failure: AssertionError) {
            assertTrue(failure.message.orEmpty().contains("start failed"))
        }
        assertSame(failing, published.get())
    }

    @Test
    fun lateSocketCloseFailureCannotReclaimAnAlreadyRetiredOwner() {
        Socket().use { retired ->
            Socket().use { replacement ->
                val lateFailure = IOException("late close failed")
                assertTrue(ownsBonjourSocket(retired, retired))
                assertTrue(!ownsBonjourSocket(null, retired))
                assertTrue(!ownsBonjourSocket(replacement, retired))
                assertNull(
                    retainBonjourSocketCloseFailure(
                        null,
                        retired,
                        retained = null,
                        failure = lateFailure,
                    ),
                )
                assertNull(
                    retainBonjourSocketCloseFailure(
                        replacement,
                        retired,
                        retained = null,
                        failure = lateFailure,
                    ),
                )
                assertSame(
                    lateFailure,
                    retainBonjourSocketCloseFailure(
                        retired,
                        retired,
                        retained = null,
                        failure = lateFailure,
                    ),
                )
            }
        }
    }
}
