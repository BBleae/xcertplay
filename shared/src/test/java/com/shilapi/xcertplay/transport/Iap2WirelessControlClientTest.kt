package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAuthorization
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetCatalog
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetEvidence
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetInstallation
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetKey
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetProfile
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2WirelessControlClientTest {
    @Test
    fun wifiConfigurationAndStartSessionResolveEachLiveEndpointAndRetirementFailsClosed() {
        val initial = endpoint()
        val first = endpoint().withAddressesForTest(listOf("fe80::1", "192.168.49.1"))
        val second = endpoint().withAddressesForTest(listOf("fe80::1"))
        var current: Iap2WirelessCarPlayEndpoint? = first
        var calls = 0
        val provider: () -> Iap2WirelessCarPlayEndpoint? = {
            calls++
            current
        }

        assertSame(first, resolveLiveWirelessControlEndpoint(initial, provider, 0x5703))
        current = second
        assertSame(second, resolveLiveWirelessControlEndpoint(initial, provider, 0x4301))
        current = null
        assertThrows(IOException::class.java) {
            resolveLiveWirelessControlEndpoint(initial, provider, 0x5703)
        }
        assertEquals(3, calls)
    }

    @Test
    fun liveEndpointCannotReplaceWifiOrAirPlayIdentity() {
        val initial = endpoint()
        val replacement = Iap2WirelessCarPlayEndpoint(
            ssid = "LIVI-replacement",
            passphrase = initial.passphrase,
            channel = initial.channel,
            security = initial.security,
            ipAddresses = initial.ipAddresses,
            airPlayPort = initial.airPlayPort,
            deviceIdentifier = initial.deviceIdentifier,
            publicKey = initial.publicKey,
            sourceVersion = initial.sourceVersion,
        )

        assertThrows(IOException::class.java) {
            resolveLiveWirelessControlEndpoint(initial, { replacement }, 0x4301)
        }
    }

    @Test
    fun accessoryWiFiConfigurationMatchesLiviVector() {
        val frame = Iap2WirelessControlClient.accessoryWiFiConfiguration(endpoint())

        assertEquals(
            "404000275703000900014c49564900000e00027365637265743132330000050003030005000424",
            frame.encodedFrame().hex(),
        )
    }

    @Test
    fun carPlayStartSessionMatchesLiviWirelessVector() {
        val frame = Iap2WirelessControlClient.carPlayStartSession(endpoint())

        assertEquals(
            "40400060430100350001000900004c49564900000e000173656372657431323300000500022400100003" +
                "3139322e3136382e322e31000005000403000800020000c000000a00036465762d3100000b000461" +
                "61626263630000080005312e3000",
            frame.encodedFrame().hex(),
        )
    }

    @Test
    fun eligibleClusterAssetUsesByteIdenticalGroupOnWiredAndWirelessPaths() {
        val profile = Iap2ClusterAssetProfile(
            assetId = "cluster-opaque",
            assetVersion = 0x0102_0304,
            evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
        )
        val catalog = Iap2ClusterAssetCatalog.of(profile)
        val key = Iap2ClusterAssetKey(profile.assetId, profile.assetVersion)
        val availability = themeAssetAvailability(0x08)
        val wiredFrameWithAvailability = Iap2WiredControlClient.carPlayStartSession(
            Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf("fe80::2"),
                airPlayPort = 7000,
                deviceIdentifier = "02:00:00:00:00:02",
                publicKey = "abcd",
                sourceVersion = "1.0",
                clusterAssetCatalog = catalog,
                clusterAssetKey = key,
                sdkVersion = "27.0",
            ),
            availability,
        )
        val wirelessFrame = Iap2WirelessControlClient.carPlayStartSession(
            endpoint(catalog, key, "27.0"),
            availability,
        )

        assertEquals("27.0\u0000", parameters(wiredFrameWithAvailability.payload).single { it.id == 6 }.payload.decodeToString())
        assertEquals("27.0\u0000", parameters(wirelessFrame.payload).single { it.id == 6 }.payload.decodeToString())
        val wiredAssetGroup = parameters(wiredFrameWithAvailability.payload).single { it.id == 7 }.payload
        val wirelessAssetGroup = parameters(wirelessFrame.payload).single { it.id == 7 }.payload
        assertArrayEquals(wiredAssetGroup, wirelessAssetGroup)
        assertEquals(
            "00130000636c75737465722d6f7061717565000008000101020304",
            wiredAssetGroup.hex(),
        )
    }

    @Test
    fun clusterAssetBootstrapIsIndependentOfPhoneGaugeClusterFeatureBit() {
        val profile = Iap2ClusterAssetProfile(
            assetId = "wireless-opaque",
            assetVersion = 8,
            evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
        )
        val catalog = Iap2ClusterAssetCatalog.of(profile)
        val key = Iap2ClusterAssetKey(profile.assetId, profile.assetVersion)
        val cases = listOf(
            "no features" to themeAssetAvailability(0x00),
            "legacy boolean one is not gauge cluster" to themeAssetAvailability(0x01),
            "gauge cluster" to themeAssetAvailability(0x08),
            "passenger display only" to themeAssetAvailability(0x20),
            "gauge cluster and passenger display" to themeAssetAvailability(0x28),
            "unknown bit only" to themeAssetAvailability(0x80),
            "absent" to themeAssetAvailability(null),
            "malformed" to byteArrayOf(0, 3, 0, 2),
        )

        cases.forEach { (label, payload) ->
            val frame = Iap2WirelessControlClient.carPlayStartSession(
                endpoint(catalog, key, "27.0"),
                payload,
            )
            assertEquals(0x4301, frame.messageId)
            assertTrue(label, parameters(frame.payload).any { it.id == 6 })
            assertTrue(label, parameters(frame.payload).any { it.id == 7 })
        }
    }

    @Test
    fun partialClusterAssetMetadataOmitsBothSdkVersionAndAssetGroup() {
        val profile = Iap2ClusterAssetProfile(
            assetId = "wireless-opaque",
            assetVersion = 8,
            evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
        )
        val catalog = Iap2ClusterAssetCatalog.of(profile)
        val key = Iap2ClusterAssetKey(profile.assetId, profile.assetVersion)
        val cases = listOf(
            endpoint(catalog, key),
            endpoint(catalog, key, "   "),
            endpoint(catalog, null, "27.0"),
            endpoint(Iap2ClusterAssetCatalog.EMPTY, key, "27.0"),
            endpoint(catalog, Iap2ClusterAssetKey("other", 8), "27.0"),
        )

        cases.forEach { endpoint ->
            val frame = Iap2WirelessControlClient.carPlayStartSession(endpoint, themeAssetAvailability(0x08))
            assertFalse(parameters(frame.payload).any { it.id == 6 })
            assertFalse(parameters(frame.payload).any { it.id == 7 })
        }
    }

    @Test
    fun rawOrUnverifiedClusterAssetCannotCrossWirelessEndpointBoundary() {
        val key = Iap2ClusterAssetKey("unverified-wireless", 10)
        val catalog = Iap2ClusterAssetCatalog.of(
            Iap2ClusterAssetProfile(key.assetId, key.assetVersion),
        )
        val frame = Iap2WirelessControlClient.carPlayStartSession(
            endpoint(catalog, key, "27.0"),
            themeAssetAvailability(0x08),
        )

        assertFalse(parameters(frame.payload).any { it.id == 6 })
        assertFalse(parameters(frame.payload).any { it.id == 7 })
        assertFalse(
            Iap2WirelessCarPlayEndpoint::class.java.constructors.any { constructor ->
                Iap2ClusterAsset::class.java in constructor.parameterTypes
            },
        )
    }

    @Test
    fun wirelessIdentificationAdvertisesTransportComponentsWithoutUsbHost() {
        val config = Iap2IdentificationConfig(
            name = "LIVI",
            modelIdentifier = "LIVI",
            manufacturer = "LIVI",
            serialNumber = "0123456",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            wireless = Iap2WirelessIdentification(
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                ssid = "LIVI",
            ),
        )
        val parameters = parameters(
            Iap2IdentificationClient.identificationInformation(config).payload,
        )

        assertNull(parameters.firstOrNull { it.id == 16 })
        assertArrayEquals(byteArrayOf(0), parameters.single { it.id == 8 }.payload)

        val bluetooth = parameters(parameters.single { it.id == 17 }.payload)
        assertEquals("blue\u0000", bluetooth.single { it.id == 1 }.payload.decodeToString())
        assertArrayEquals(
            byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
            bluetooth.single { it.id == 3 }.payload,
        )

        val wireless = parameters(parameters.single { it.id == 24 }.payload)
        assertEquals("LIVI\u0000", wireless.single { it.id == 1 }.payload.decodeToString())

        val sent = u16Values(parameters.single { it.id == 6 }.payload)
        val received = u16Values(parameters.single { it.id == 7 }.payload)
        assertTrue(0x5703 in sent)
        assertTrue(0x4301 in sent)
        assertFalse(0xae03 in sent)
        assertTrue(0x4300 in received)
        assertTrue(0x6801 in received)
        assertTrue(0x4e0d in received)
        assertTrue(0x4e0e in received)
        assertTrue(0x5702 in received)
    }

    @Test
    fun wirelessCarPlayUpdateReadsBooleanAvailability() {
        val frame = Iap2Frame(
            0x4e0d,
            Iap2ParameterList.of(Iap2Parameter(0, byteArrayOf(1))).encode(),
        )

        assertTrue(Iap2WirelessMessages.wirelessCarPlayAvailability(frame))
    }

    @Test
    fun wiredIdentificationKeepsCarPlayStartSessionDeclaration() {
        val config = Iap2IdentificationConfig(
            name = "wired",
            modelIdentifier = "wired",
            manufacturer = "test",
            serialNumber = "1",
            firmwareVersion = "1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 4,
        )
        val parameters = parameters(
            Iap2IdentificationClient.identificationInformation(config).payload,
        )

        val sent = u16Values(parameters.single { it.id == 6 }.payload)
        assertTrue(0x4301 in sent)
        assertTrue(0x6800 in sent)
        assertTrue(0x6802 in sent)
        assertTrue(0x6803 in sent)
        assertTrue(0x4300 in u16Values(parameters.single { it.id == 7 }.payload))
        assertTrue(0x6801 in u16Values(parameters.single { it.id == 7 }.payload))
        val hid = parameters(parameters.single { it.id == 18 }.payload)
        assertArrayEquals(byteArrayOf(0, 0), hid.single { it.id == 0 }.payload)
        assertEquals("Media Playback Remote\u0000", hid.single { it.id == 1 }.payload.decodeToString())
        assertArrayEquals(byteArrayOf(1), hid.single { it.id == 2 }.payload)
    }

    @Test
    fun themeAssetsCapableIsClaimedOnlyWhenExplicitlyEnabled() {
        fun wirelessTransportSubParams(themeAssetsCapable: Boolean): List<Int> {
            val config = Iap2IdentificationConfig(
                name = "LIVI",
                modelIdentifier = "LIVI",
                manufacturer = "LIVI",
                serialNumber = "0123456",
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 0,
                wireless = Iap2WirelessIdentification(
                    bluetoothMac = "AA:BB:CC:DD:EE:FF",
                    ssid = "LIVI",
                ),
                themeAssetsCapable = themeAssetsCapable,
            )
            val group = parameters(
                Iap2IdentificationClient.identificationInformation(config).payload,
            ).single { it.id == 24 }
            return parameters(group.payload).map { it.id }
        }

        // An ordinary pairing must not look Ultra-capable: iOS would record an Ultra opt-out.
        assertFalse(5 in wirelessTransportSubParams(themeAssetsCapable = false))
        assertTrue(5 in wirelessTransportSubParams(themeAssetsCapable = true))
        // No ThemeAssetIdentifier (sub-param 6) is ever sent; a stored one cannot be cleared.
        assertFalse(6 in wirelessTransportSubParams(themeAssetsCapable = true))
    }

    @Test
    fun identificationInformationEmitsParametersInAscendingOrder() {
        val wirelessConfig = Iap2IdentificationConfig(
            name = "LIVI",
            modelIdentifier = "LIVI",
            manufacturer = "LIVI",
            serialNumber = "0123456",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            wireless = Iap2WirelessIdentification(
                bluetoothMac = "AA:BB:CC:DD:EE:FF",
                ssid = "LIVI",
            ),
        )
        val wiredConfig = Iap2IdentificationConfig(
            name = "wired",
            modelIdentifier = "wired",
            manufacturer = "test",
            serialNumber = "1",
            firmwareVersion = "1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 4,
        )

        for (config in listOf(wirelessConfig, wiredConfig)) {
            val ids = parameters(
                Iap2IdentificationClient.identificationInformation(config).payload,
            ).map { it.id }
            assertEquals(ids.sorted(), ids)
        }
    }

    private fun endpoint(
        clusterAssetCatalog: Iap2ClusterAssetCatalog = Iap2ClusterAssetCatalog.EMPTY,
        clusterAssetKey: Iap2ClusterAssetKey? = null,
        sdkVersion: String? = null,
    ): Iap2WirelessCarPlayEndpoint =
        Iap2WirelessCarPlayEndpoint(
            ssid = "LIVI",
            passphrase = "secret123",
            channel = 36,
            security = Iap2WirelessSecurity.WPA3_TRANSITION,
            ipAddresses = listOf("192.168.2.1"),
            airPlayPort = 49152,
            deviceIdentifier = "dev-1",
            publicKey = "aabbcc",
            sourceVersion = "1.0",
            clusterAssetCatalog = clusterAssetCatalog,
            clusterAssetKey = clusterAssetKey,
            sdkVersion = sdkVersion,
        )

    private fun Iap2WirelessCarPlayEndpoint.withAddressesForTest(
        addresses: List<String>,
    ): Iap2WirelessCarPlayEndpoint = Iap2WirelessCarPlayEndpoint(
        ssid = ssid,
        passphrase = passphrase,
        channel = channel,
        security = security,
        ipAddresses = addresses,
        airPlayPort = airPlayPort,
        deviceIdentifier = deviceIdentifier,
        publicKey = publicKey,
        sourceVersion = sourceVersion,
    )

    private fun u16Values(bytes: ByteArray): List<Int> =
        List(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) shl 8) or (bytes[index * 2 + 1].toInt() and 0xff)
        }

    private fun parameters(bytes: ByteArray) = Iap2ParameterList.parse(bytes).asList()

    private fun themeAssetAvailability(mask: Int?): ByteArray =
        Iap2Messages.buildRaw(0x4300) {
            mask?.let { value ->
                group(2) { u8(0, value) }
            }
        }.payload

    private fun ByteArray.hex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
