package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAcquisitionIntent
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAuthorization
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetCatalog
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetEvidence
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetInstallation
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetKey
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetProfile
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WiredControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import com.shilapi.xcertplay.transport.wiredStartSessionForControlSend
import com.shilapi.xcertplay.transport.wirelessBootstrapStartSessionForControlSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayClusterAssetEndpointWiringTest {
    @Test
    fun defaultRuntimeConfigOmitsSdkAndAssetOnBothTransports() {
        startSessions(runtimeConfig()).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun exactDeploymentSelectionReachesBothPreAuthenticationEndpoints() {
        val key = Iap2ClusterAssetKey("deployment-opaque-asset", 0x0102_0304)
        val profile = Iap2ClusterAssetProfile(
            assetId = key.assetId,
            assetVersion = key.assetVersion,
            evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
        )
        val selection = CarPlayClusterAssetMetadataSelection(
            catalog = Iap2ClusterAssetCatalog.of(profile),
            key = key,
            sdkVersion = "27.0-test-sdk",
            expectedMfiAuthenticationIdentity = identity(1),
        )

        startSessions(runtimeConfig(selection)).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertEquals("27.0-test-sdk\u0000", body.require(6).payload.decodeToString())
            val asset = Iap2ParameterList.parse(body.require(7).payload)
            assertEquals("deployment-opaque-asset\u0000", asset.require(0).payload.decodeToString())
            assertEquals(key.assetVersion, Iap2BodyReader.of(asset).u32(1))
        }
    }

    @Test
    fun differentMfiIdentityOmitsSdkAndAssetOnBothTransports() {
        val key = Iap2ClusterAssetKey("identity-bound-asset", 42)
        val selection = CarPlayClusterAssetMetadataSelection(
            catalog = Iap2ClusterAssetCatalog.of(
                Iap2ClusterAssetProfile(
                    assetId = key.assetId,
                    assetVersion = key.assetVersion,
                    evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
                    installation = Iap2ClusterAssetInstallation.PREINSTALLED,
                    authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
                ),
            ),
            key = key,
            sdkVersion = "27.0-test-sdk",
            expectedMfiAuthenticationIdentity = identity(1),
        )

        startSessions(runtimeConfig(selection), actualMfiIdentity = identity(2)).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun selectedButIneligibleAssetStillOmitsBothFieldsOnBothTransports() {
        val key = Iap2ClusterAssetKey("unverified-asset", 7)
        val selection = CarPlayClusterAssetMetadataSelection(
            catalog = Iap2ClusterAssetCatalog.of(Iap2ClusterAssetProfile(key.assetId, key.assetVersion)),
            key = key,
            sdkVersion = "27.0",
            expectedMfiAuthenticationIdentity = identity(1),
        )

        startSessions(runtimeConfig(selection)).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun acquisitionIntentRequestsCatalogUpdateWithExplicitZeroVersion() {
        val intent = Iap2ClusterAssetAcquisitionIntent(
            assetId = "catalog-request",
            sdkVersion = "27.0-test-sdk",
            expectedMfiAuthenticationIdentity = identity(1),
        )

        val unavailable = Iap2Messages.buildRaw(0x4300) { group(2) { u8(0, 0) } }.payload
        startSessions(
            runtimeConfig(acquisitionIntent = intent),
            availabilityPayload = unavailable,
        ).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals("27.0-test-sdk\u0000", body.require(6).payload.decodeToString())
            val assetGroup = body.require(7).payload
            assertEquals(
                "00140000636174616c6f672d72657175657374000008000100000000",
                assetGroup.joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
            assertEquals(listOf(0, 1), Iap2ParameterList.parse(assetGroup).asList().map { it.id })
            assertEquals(0L, Iap2BodyReader.of(Iap2ParameterList.parse(assetGroup)).u32(1))
        }
    }

    @Test
    fun unsignedDirectAcquisitionIntentCannotReachEitherWireEncoder() {
        val config = runtimeConfig(
            acquisitionIntent = Iap2ClusterAssetAcquisitionIntent(
                "catalog-request",
                "27.0-test-sdk",
                identity(1),
            ),
        )
        startSessions(config, authorizeAcquisition = false).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun eachAvailabilityRechecksSignedAssetPermissionAfterEndpointConstruction() {
        val liveIdentity = identity(1)
        val config = runtimeConfig(probe = catalogProbe(liveIdentity))
        val wired = config.wiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            publicKey = "aabbcc",
            sourceVersion = "1.0",
            deviceIdentifier = "02:00:00:00:00:02",
            actualMfiAuthenticationIdentity = liveIdentity,
        )
        val wireless = config.wirelessCarPlayEndpoint(
            ssid = "TestSSID",
            passphrase = "test-passphrase",
            channel = 36,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf("192.168.2.1"),
            airPlayPort = 7000,
            deviceIdentifier = "test-device",
            publicKey = "aabbcc",
            sourceVersion = "1.0",
            actualMfiAuthenticationIdentity = liveIdentity,
        )
        var authorized = true
        var wiredResolutions = 0
        var wirelessResolutions = 0
        val signedPredicate: (String, MfiAuthenticationIdentity) -> Boolean = { assetId, identity ->
            authorized && assetId == "catalog-probe" && identity == liveIdentity
        }
        val wiredEndpoint = { wiredResolutions++; wired }
        val wirelessEndpoint = { wirelessResolutions++; wireless }
        val availability = Iap2Messages.buildRaw(0x4300) { group(2) { u8(0, 0) } }.payload
        fun framesForAvailability(
            predicate: (String, MfiAuthenticationIdentity) -> Boolean,
        ): List<Iap2Frame> = listOf(
            wiredStartSessionForControlSend(
                wired, wiredEndpoint, availability, liveIdentity, predicate,
            ).frame,
            requireNotNull(wirelessBootstrapStartSessionForControlSend(
                wireless, wirelessEndpoint, availability, liveIdentity, predicate,
                bootstrapSendAllowed = { true },
            )).frame,
        )
        val frames = framesForAvailability(signedPredicate)
        frames.forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertEquals(0L, Iap2BodyReader.of(Iap2ParameterList.parse(body.require(7).payload)).u32(1))
        }

        authorized = false
        framesForAvailability(signedPredicate).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
        authorized = true
        val wrongAssetPredicate: (String, MfiAuthenticationIdentity) -> Boolean = { assetId, _ ->
            assetId == "another-signed-asset"
        }
        framesForAvailability(wrongAssetPredicate).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
        framesForAvailability { _, _ -> throw IllegalStateException("authorization unavailable") }
            .forEach { frame ->
                val body = Iap2BodyReader.of(frame)
                assertEquals(0x4301, frame.messageId)
                assertFalse(body.has(6))
                assertFalse(body.has(7))
            }
        assertEquals(4, wiredResolutions)
        assertEquals(4, wirelessResolutions)
    }

    @Test
    fun staleBluetoothBootstrapCannotSendSecondStartSessionAcrossSameIdentityHandoff() {
        val identity = identity(1)
        data class ReceiptLike(val mfiIdentity: MfiAuthenticationIdentity)
        val bootstrapReceipt = ReceiptLike(identity)
        val successorReceipt = ReceiptLike(identity)
        // Value identity is the same; only the exact controller receipt owner changes.
        assertEquals(bootstrapReceipt, successorReceipt)
        val endpoint = runtimeConfig().wirelessCarPlayEndpoint(
            ssid = "TestSSID",
            passphrase = "test-passphrase",
            channel = 36,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf("192.168.2.1"),
            airPlayPort = 7000,
            deviceIdentifier = "test-device",
            publicKey = "aabbcc",
            sourceVersion = "1.0",
            actualMfiAuthenticationIdentity = identity,
        )
        val availability = Iap2Messages.buildRaw(0x4300) { group(2) { u8(0, 1) } }.payload
        var controllerReceipt: Any? = bootstrapReceipt
        var handoffRequested = false
        var tunnelActive = false
        var endpointResolutions = 0
        val currentEndpoint = { endpointResolutions++; endpoint }
        fun admitted() = canSendWirelessBootstrapStartSession(
            bootstrapReceipt = bootstrapReceipt,
            controllerReceipt = controllerReceipt,
            exactMfiSession = true,
            currentControl = true,
            receiptLive = true,
            handoffReserved = false,
            handoffRequested = handoffRequested,
            tunnelActive = tunnelActive,
            tunnelReady = false,
            wirelessActive = false,
            airPlaySessionActive = false,
        )
        fun reply(sendAllowed: () -> Boolean = ::admitted) =
            wirelessBootstrapStartSessionForControlSend(
                endpoint,
                currentEndpoint,
                availability,
                identity,
                allowClusterAssetAcquisition = { _, _ -> false },
                bootstrapSendAllowed = sendAllowed,
            )

        assertNotNull(reply()) // Initial ordinary Bluetooth bootstrap still starts CarPlay.
        assertEquals(1, endpointResolutions)
        handoffRequested = true
        assertNull(reply()) // Before tunnel AA05 or successor publication.
        assertEquals(1, endpointResolutions)
        handoffRequested = false
        tunnelActive = true
        assertNull(reply()) // An accepted tunnel suppresses the old CSM before handoff finishes.
        tunnelActive = false
        controllerReceipt = successorReceipt
        assertNull(reply()) // Same MFi identity is insufficient after exact receipt replacement.
        assertEquals(1, endpointResolutions)

        controllerReceipt = bootstrapReceipt
        var checks = 0
        assertNull(reply {
            checks++
            if (checks == 2) handoffRequested = true
            admitted()
        }) // Handoff beginning while resolving the endpoint also suppresses 0x4301.
        assertEquals(2, checks)
        assertEquals(2, endpointResolutions)
    }

    @Test
    fun acquisitionIntentWithDifferentMfiIdentityOmitsBothFieldsOnBothTransports() {
        val intent = Iap2ClusterAssetAcquisitionIntent("catalog-request", "27.0", identity(1))
        startSessions(
            runtimeConfig(acquisitionIntent = intent),
            actualMfiIdentity = identity(2),
        ).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun acquisitionIntentAlsoChecksLiveReceiptAfterEndpointCreation() {
        val intent = Iap2ClusterAssetAcquisitionIntent("catalog-request", "27.0", identity(1))
        startSessions(
            runtimeConfig(acquisitionIntent = intent),
            endpointMfiIdentity = identity(1),
            actualMfiIdentity = identity(2),
        ).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun pinnedCatalogProbeRequestsExplicitZeroVersionOnBothTransports() {
        val probe = catalogProbe(identity(1))
        startSessions(runtimeConfig(probe = probe)).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertEquals("27.0-test-sdk\u0000", body.require(6).payload.decodeToString())
            val asset = Iap2ParameterList.parse(body.require(7).payload)
            assertEquals("catalog-probe\u0000", asset.require(0).payload.decodeToString())
            assertEquals(0L, Iap2BodyReader.of(asset).u32(1))
            assertEquals(listOf(0, 1), asset.asList().map { it.id })
        }
    }

    @Test
    fun catalogProbeOmitsBothFieldsForMismatchedDigestOrCertificateType() {
        val matchingIdentity = identity(1)
        val wrongDigest = catalogProbe(identity(2))
        val wrongType = ClusterAssetCatalogProbe(
            "catalog-probe",
            "27.0-test-sdk",
            MfiCertificateType.BAA,
            matchingIdentity.sha256Bytes(),
        )
        listOf(wrongDigest, wrongType).forEach { probe ->
            startSessions(runtimeConfig(probe = probe), actualMfiIdentity = matchingIdentity)
                .forEach { frame ->
                    val body = Iap2BodyReader.of(frame)
                    assertEquals(0x4301, frame.messageId)
                    assertFalse(body.has(6))
                    assertFalse(body.has(7))
                }
        }
    }

    @Test
    fun catalogProbeDefensivelyCopiesInputAndExposedDigest() {
        val liveIdentity = identity(1)
        val digest = liveIdentity.sha256Bytes()
        val probe = ClusterAssetCatalogProbe(
            "catalog-probe",
            "27.0-test-sdk",
            liveIdentity.certificateType,
            digest,
        )
        digest.fill(0)
        probe.expectedIdentitySha256.fill(0)

        startSessions(runtimeConfig(probe = probe), actualMfiIdentity = liveIdentity)
            .forEach { frame ->
                val body = Iap2BodyReader.of(frame)
                assertEquals("27.0-test-sdk\u0000", body.require(6).payload.decodeToString())
                assertEquals(
                    "catalog-probe\u0000",
                    Iap2ParameterList.parse(body.require(7).payload).require(0).payload.decodeToString(),
                )
            }
    }

    @Test
    fun catalogProbeChecksLiveReceiptAgainAfterBothEndpointsAreBuilt() {
        startSessions(
            runtimeConfig(probe = catalogProbe(identity(1))),
            endpointMfiIdentity = identity(1),
            actualMfiIdentity = identity(2),
        ).forEach { frame ->
            val body = Iap2BodyReader.of(frame)
            assertEquals(0x4301, frame.messageId)
            assertFalse(body.has(6))
            assertFalse(body.has(7))
        }
    }

    @Test
    fun catalogProbeCannotCoexistWithPreinstalledSelectionOrAcquisitionIntent() {
        val liveIdentity = identity(1)
        val probe = catalogProbe(liveIdentity)
        val selection = CarPlayClusterAssetMetadataSelection(
            Iap2ClusterAssetCatalog.EMPTY,
            Iap2ClusterAssetKey("installed", 1),
            "27.0",
            liveIdentity,
        )
        val intent = Iap2ClusterAssetAcquisitionIntent("catalog-request", "27.0", liveIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            runtimeConfig(selection = selection, probe = probe)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtimeConfig(acquisitionIntent = intent, probe = probe)
        }
    }

    @Test
    fun catalogProbeRejectsMalformedPinAndMetadata() {
        val liveIdentity = identity(1)
        assertThrows(IllegalArgumentException::class.java) {
            ClusterAssetCatalogProbe("catalog-probe", "27.0", MfiCertificateType.MFI, ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterAssetCatalogProbe(" ", "27.0", MfiCertificateType.MFI, liveIdentity.sha256Bytes())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterAssetCatalogProbe("catalog-probe", " ", MfiCertificateType.MFI, liveIdentity.sha256Bytes())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClusterAssetCatalogProbe("catalog\u0000probe", "27.0", MfiCertificateType.MFI, liveIdentity.sha256Bytes())
        }
    }

    @Test
    fun catalogProbeAcceptsTheLocalByteBoundaryButRejectsLargerOrControlFields() {
        val liveIdentity = identity(1)
        val digest = liveIdentity.sha256Bytes()
        listOf("a".repeat(256), "é".repeat(128)).forEach { boundary ->
            assertEquals(
                boundary,
                ClusterAssetCatalogProbe(boundary, "27.0", MfiCertificateType.MFI, digest).assetId,
            )
            assertEquals(
                boundary,
                ClusterAssetCatalogProbe("catalog-probe", boundary, MfiCertificateType.MFI, digest).sdkVersion,
            )
        }
        listOf("a".repeat(257), "é".repeat(129), "bad\nfield", "bad\u007ffield", "bad\u0085field")
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    ClusterAssetCatalogProbe(invalid, "27.0", MfiCertificateType.MFI, digest)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    ClusterAssetCatalogProbe("catalog-probe", invalid, MfiCertificateType.MFI, digest)
                }
            }
    }

    @Test
    fun catalogProbeRejectsUnpairedSurrogatesWithoutRejectingAValidPair() {
        val liveIdentity = identity(1)
        val digest = liveIdentity.sha256Bytes()
        val validPair = "emoji-\uD83D\uDE80"
        assertEquals(
            validPair,
            ClusterAssetCatalogProbe(validPair, "27.0", MfiCertificateType.MFI, digest).assetId,
        )
        listOf("bad-\uD800", "bad-\uDC00").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ClusterAssetCatalogProbe(invalid, "27.0", MfiCertificateType.MFI, digest)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ClusterAssetCatalogProbe("catalog-probe", invalid, MfiCertificateType.MFI, digest)
            }
        }
    }

    @Test
    fun acquisitionIntentAndPreinstalledSelectionConflictAtConfigBoundary() {
        val key = Iap2ClusterAssetKey("installed", 1)
        val selection = CarPlayClusterAssetMetadataSelection(
            catalog = Iap2ClusterAssetCatalog.EMPTY,
            key = key,
            sdkVersion = "27.0",
            expectedMfiAuthenticationIdentity = identity(1),
        )
        val intent = Iap2ClusterAssetAcquisitionIntent("catalog-request", "27.0", identity(1))
        assertThrows(IllegalArgumentException::class.java) {
            runtimeConfig(selection = selection, acquisitionIntent = intent)
        }
    }

    @Test
    fun acquisitionIntentRejectsInvalidAssetIdAndSdkVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetAcquisitionIntent("   ", "27.0", identity(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetAcquisitionIntent("asset\u0000name", "27.0", identity(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetAcquisitionIntent("asset", "   ", identity(1))
        }
    }

    @Test
    fun directAcquisitionIntentCannotBypassCatalogProbeTextBounds() {
        val liveIdentity = identity(1)
        listOf("a".repeat(256), "é".repeat(128)).forEach { boundary ->
            assertEquals(
                boundary,
                Iap2ClusterAssetAcquisitionIntent(boundary, "27.0", liveIdentity).assetId,
            )
            assertEquals(
                boundary,
                Iap2ClusterAssetAcquisitionIntent("asset", boundary, liveIdentity).sdkVersion,
            )
        }
        listOf("a".repeat(257), "é".repeat(129), "bad\nfield", "bad\u007ffield", "bad\u0085field")
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    Iap2ClusterAssetAcquisitionIntent(invalid, "27.0", liveIdentity)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    Iap2ClusterAssetAcquisitionIntent("asset", invalid, liveIdentity)
                }
            }
    }

    @Test
    fun directAcquisitionIntentRejectsUnpairedSurrogatesWithoutRejectingAValidPair() {
        val liveIdentity = identity(1)
        val validPair = "emoji-\uD83D\uDE80"
        assertEquals(
            validPair,
            Iap2ClusterAssetAcquisitionIntent(validPair, "27.0", liveIdentity).assetId,
        )
        listOf("bad-\uD800", "bad-\uDC00").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                Iap2ClusterAssetAcquisitionIntent(invalid, "27.0", liveIdentity)
            }
            assertThrows(IllegalArgumentException::class.java) {
                Iap2ClusterAssetAcquisitionIntent("asset", invalid, liveIdentity)
            }
        }
    }

    @Test
    fun selectionRejectsBlankSdkVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayClusterAssetMetadataSelection(
                Iap2ClusterAssetCatalog.EMPTY,
                Iap2ClusterAssetKey("opaque", 1),
                "   ",
                identity(1),
            )
        }
    }

    private fun startSessions(
        config: CarPlayRuntimeConfig,
        actualMfiIdentity: MfiAuthenticationIdentity = identity(1),
        endpointMfiIdentity: MfiAuthenticationIdentity = actualMfiIdentity,
        availabilityPayload: ByteArray? = null,
        authorizeAcquisition: Boolean = true,
    ): List<Iap2Frame> {
        val wired = config.wiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            publicKey = "aabbcc",
            sourceVersion = "1.0",
            deviceIdentifier = "02:00:00:00:00:02",
            actualMfiAuthenticationIdentity = endpointMfiIdentity,
        )
        val wireless = config.wirelessCarPlayEndpoint(
            ssid = "TestSSID",
            passphrase = "test-passphrase",
            channel = 36,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf("192.168.2.1"),
            airPlayPort = 7000,
            deviceIdentifier = "test-device",
            publicKey = "aabbcc",
            sourceVersion = "1.0",
            actualMfiAuthenticationIdentity = endpointMfiIdentity,
        )
        return listOf(
            Iap2WiredControlClient.carPlayStartSession(
                wired,
                availabilityPayload,
                actualMfiAuthenticationIdentity = actualMfiIdentity,
                allowClusterAssetAcquisition = { _, _ -> authorizeAcquisition },
            ),
            Iap2WirelessControlClient.carPlayStartSession(
                wireless,
                availabilityPayload,
                actualMfiAuthenticationIdentity = actualMfiIdentity,
                allowClusterAssetAcquisition = { _, _ -> authorizeAcquisition },
            ),
        )
    }

    private fun runtimeConfig(
        selection: CarPlayClusterAssetMetadataSelection? = null,
        acquisitionIntent: Iap2ClusterAssetAcquisitionIntent? = null,
        probe: ClusterAssetCatalogProbe? = null,
    ): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.REMOTE,
        remoteMfiServer = "127.0.0.1:8765",
        identification = Iap2IdentificationConfig(
            name = "Test",
            modelIdentifier = "Test",
            manufacturer = "Test",
            serialNumber = "Test",
            firmwareVersion = "1",
            hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
        clusterAssetMetadataSelection = selection,
        clusterAssetAcquisitionIntent = acquisitionIntent,
        clusterAssetCatalogProbe = probe,
    )

    private fun catalogProbe(identity: MfiAuthenticationIdentity) = ClusterAssetCatalogProbe(
        assetId = "catalog-probe",
        sdkVersion = "27.0-test-sdk",
        expectedCertificateType = identity.certificateType,
        expectedIdentitySha256 = identity.sha256Bytes(),
    )

    private fun identity(seed: Int): MfiAuthenticationIdentity =
        MfiAuthenticationIdentity.capture(
            object : MfiAuthenticator {
                override val certificateType = MfiCertificateType.MFI
                override fun protocolMajor(): Int = 1
                override fun readCertificate(maximumOutputLength: Int): ByteArray =
                    byteArrayOf(seed.toByte(), (seed + 1).toByte(), (seed + 2).toByte())
                override fun signChallenge(challenge: ByteArray): ByteArray = byteArrayOf(1)
            },
        )
}
