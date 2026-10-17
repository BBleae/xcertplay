package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetAuthorization
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetCatalog
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetEvidence
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetInstallation
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetKey
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAssetProfile
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class Iap2WiredControlClientTest {
    @Test
    fun everyWiredAvailabilityNeedsCurrentEndpointWithSameTransportIdentity() {
        val initial = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            deviceIdentifier = "device",
            publicKey = "abcd",
            sourceVersion = "1.0",
        )
        var current: Iap2WiredCarPlayEndpoint? = initial
        var resolutions = 0
        val resolve: () -> Iap2WiredCarPlayEndpoint? = { resolutions++; current }
        assertSame(initial, resolveLiveWiredControlEndpoint(initial, resolve))
        assertSame(initial, resolveLiveWiredControlEndpoint(initial, resolve))
        current = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::3"),
            airPlayPort = 7000,
            deviceIdentifier = "device",
            publicKey = "abcd",
            sourceVersion = "1.0",
        )
        assertThrows(IOException::class.java) {
            resolveLiveWiredControlEndpoint(initial, resolve)
        }
        current = null
        assertThrows(IOException::class.java) {
            resolveLiveWiredControlEndpoint(initial, resolve)
        }
        assertEquals(4, resolutions)
    }

    @Test
    fun debugAcquisitionMarkerOnlyReflectsEncodedFieldsAndNeverIncludesMetadata() {
        val ordinary = Iap2Messages.buildRaw(0x4301) { string(4, "public-key") }
        val acquisition = Iap2Messages.buildRaw(0x4301) {
            string(6, "sdk-secret")
            group(7) { string(0, "asset-secret"); u32(1, 0) }
        }
        assertEquals(
            "iap2 tx=0x4301 cluster-acquisition=absent",
            clusterAssetAcquisitionSendMarker(ordinary, acquisitionRequested = true),
        )
        assertEquals(
            "iap2 tx=0x4301 cluster-acquisition=present",
            clusterAssetAcquisitionSendMarker(acquisition, acquisitionRequested = true),
        )
        assertEquals(
            "iap2 tx=0x4301 cluster-acquisition=absent",
            clusterAssetAcquisitionSendMarker(acquisition, acquisitionRequested = false),
        )
    }

    @Test
    fun carPlayStartSessionNestsAddressesInsideWiredAttributes() {
        val frame = Iap2WiredControlClient.carPlayStartSession(
            Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf("fe80::2"),
                airPlayPort = 7000,
                deviceIdentifier = "02:00:00:00:00:02",
                publicKey = "abcd",
                sourceVersion = "1.0",
            ),
        )

        assertEquals(0x4301, frame.messageId)
        val outer = Iap2BodyReader.of(frame).list()
        val wired = outer.single { it.id == 0 }
        val addresses = Iap2ParameterList.parse(wired.payload).asList()
        assertEquals(1, addresses.size)
        assertEquals(0, addresses.single().id)
        assertArrayEquals("fe80::2\u0000".encodeToByteArray(), addresses.single().payload)
        assertNull(outer.firstOrNull { it.id == 7 })
    }

    @Test
    fun clusterAssetCatalogRequiresAllThreeExplicitEligibilityStates() {
        val id = "opaque-selection"
        val version = 0xfedc_ba98L
        val ineligible = listOf(
            Iap2ClusterAssetProfile(id, version),
            Iap2ClusterAssetProfile(
                id,
                version,
                evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            ),
            Iap2ClusterAssetProfile(
                id,
                version,
                evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
                installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            ),
            Iap2ClusterAssetProfile(
                id,
                version,
                evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
                authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
            ),
            Iap2ClusterAssetProfile(
                id,
                version,
                installation = Iap2ClusterAssetInstallation.PREINSTALLED,
                authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
            ),
        )

        assertNull(Iap2ClusterAssetCatalog.EMPTY.assetOrNull(id, version))
        ineligible.forEach { profile ->
            assertNull(Iap2ClusterAssetCatalog.of(profile).assetOrNull(id, version))
        }

        val eligible = Iap2ClusterAssetProfile(
            id,
            version,
            evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
        )
        assertEquals(
            Iap2ClusterAsset(id, version),
            Iap2ClusterAssetCatalog.of(eligible).assetOrNull(id, version),
        )
    }

    @Test
    fun clusterAssetBootstrapIsIndependentOfPhoneGaugeClusterFeatureBit() {
        val (catalog, key) = eligibleSelection("wired-opaque", 7)
        val endpoint = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            deviceIdentifier = "02:00:00:00:00:02",
            publicKey = "abcd",
            sourceVersion = "1.0",
            clusterAssetCatalog = catalog,
            clusterAssetKey = key,
            sdkVersion = "27.0",
        )
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
            val frame = Iap2WiredControlClient.carPlayStartSession(endpoint, payload)
            assertEquals(0x4301, frame.messageId)
            assertEquals(label, "27.0\u0000", Iap2BodyReader.of(frame).list().single { it.id == 6 }.payload.decodeToString())
            assertEquals(label, true, Iap2BodyReader.of(frame).list().any { it.id == 7 })
        }
    }

    @Test
    fun partialClusterAssetMetadataOmitsBothSdkVersionAndAssetGroup() {
        val (catalog, key) = eligibleSelection("wired-opaque", 7)
        fun endpoint(
            selectionCatalog: Iap2ClusterAssetCatalog,
            selectionKey: Iap2ClusterAssetKey?,
            sdkVersion: String?,
        ) = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            deviceIdentifier = "02:00:00:00:00:02",
            publicKey = "abcd",
            sourceVersion = "1.0",
            clusterAssetCatalog = selectionCatalog,
            clusterAssetKey = selectionKey,
            sdkVersion = sdkVersion,
        )
        val cases = listOf(
            endpoint(catalog, key, null),
            endpoint(catalog, key, "   "),
            endpoint(catalog, null, "27.0"),
            endpoint(Iap2ClusterAssetCatalog.EMPTY, key, "27.0"),
            endpoint(catalog, Iap2ClusterAssetKey("other", 7), "27.0"),
        )

        cases.forEach { endpoint ->
            val frame = Iap2WiredControlClient.carPlayStartSession(endpoint, themeAssetAvailability(0x08))
            assertFalse(Iap2BodyReader.of(frame).list().any { it.id == 6 })
            assertFalse(Iap2BodyReader.of(frame).list().any { it.id == 7 })
        }
    }

    @Test
    fun rawOrUnverifiedClusterAssetCannotCrossWiredEndpointBoundary() {
        val key = Iap2ClusterAssetKey("unverified-wired", 9)
        val endpoint = Iap2WiredCarPlayEndpoint(
            ipv6Addresses = listOf("fe80::2"),
            airPlayPort = 7000,
            deviceIdentifier = "02:00:00:00:00:02",
            publicKey = "abcd",
            sourceVersion = "1.0",
            clusterAssetCatalog = Iap2ClusterAssetCatalog.of(
                Iap2ClusterAssetProfile(key.assetId, key.assetVersion),
            ),
            clusterAssetKey = key,
            sdkVersion = "27.0",
        )

        val frame = Iap2WiredControlClient.carPlayStartSession(
            endpoint,
            themeAssetAvailability(0x08),
        )
        assertFalse(Iap2BodyReader.of(frame).list().any { it.id == 6 })
        assertFalse(Iap2BodyReader.of(frame).list().any { it.id == 7 })
        assertFalse(
            Iap2WiredCarPlayEndpoint::class.java.constructors.any { constructor ->
                Iap2ClusterAsset::class.java in constructor.parameterTypes
            },
        )
    }

    @Test
    fun clusterAssetProfileAndCatalogRejectInvalidMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetProfile("   ", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetProfile("opaque\u0000selection", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetProfile("opaque-selection", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetProfile("opaque-selection", 0x1_0000_0000L)
        }

        val duplicate = Iap2ClusterAssetProfile("opaque-selection", 1)
        assertThrows(IllegalArgumentException::class.java) {
            Iap2ClusterAssetCatalog.of(duplicate, duplicate)
        }
    }

    private fun eligibleSelection(
        assetId: String,
        assetVersion: Long,
    ): Pair<Iap2ClusterAssetCatalog, Iap2ClusterAssetKey> {
        val profile = Iap2ClusterAssetProfile(
            assetId,
            assetVersion,
            evidence = Iap2ClusterAssetEvidence.EVIDENCE_BACKED,
            installation = Iap2ClusterAssetInstallation.PREINSTALLED,
            authorization = Iap2ClusterAssetAuthorization.AUTHORIZED,
        )
        return Iap2ClusterAssetCatalog.of(profile) to Iap2ClusterAssetKey(assetId, assetVersion)
    }

    private fun themeAssetAvailability(mask: Int?): ByteArray =
        Iap2Messages.buildRaw(0x4300) {
            mask?.let { value ->
                group(2) { u8(0, value) }
            }
        }.payload
}
