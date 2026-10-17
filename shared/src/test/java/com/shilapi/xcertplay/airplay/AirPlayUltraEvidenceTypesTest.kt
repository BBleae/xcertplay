package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsCatalogReadiness
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayUltraEvidenceTypesTest {
    @Test
    fun mainBufferedInfoContainsOnlyTheConfirmedBoolean() {
        val enabled = AirPlayMainBufferedInfo.fromWireMap(mapOf("DCXEnabled" to true))
        val disabled = AirPlayMainBufferedInfo.fromWireMap(mapOf("DCXEnabled" to false))

        assertEquals(mapOf("DCXEnabled" to true), enabled.toWireMap())
        assertEquals(mapOf("DCXEnabled" to false), disabled.toWireMap())
        listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("DCXEnabled" to 1),
            mapOf("DCXEnabled" to "true"),
            mapOf("DCXEnabled" to null),
            mapOf("DCXEnabled" to true, "audioFormats" to emptyList<Any?>()),
            mapOf("dcxEnabled" to true),
        ).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) {
                AirPlayMainBufferedInfo.fromWireMap(values)
            }
        }
    }

    @Test
    fun videoPlaybackInfoContainsOnlyTheRequiredAllowedBoolean() {
        val enabled = AirPlayVideoPlaybackInfo.fromWireMap(
            mapOf("videoPlaybackAllowed" to true),
        )
        val disabled = AirPlayVideoPlaybackInfo.fromWireMap(
            mapOf("videoPlaybackAllowed" to false),
        )

        assertEquals(mapOf("videoPlaybackAllowed" to true), enabled.toWireMap())
        assertEquals(mapOf("videoPlaybackAllowed" to false), disabled.toWireMap())
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVideoPlaybackInfo.fromWireMap(emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVideoPlaybackInfo.fromWireMap(mapOf("videoPlaybackAllowed" to 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayVideoPlaybackInfo.fromWireMap(
                mapOf("videoPlaybackAllowed" to true, "extra" to false),
            )
        }
    }

    @Test
    fun evidenceMetadataDoesNotMakeDisabledFeaturesNegotiable() {
        // Constructing the exact evidence types is deliberately orthogonal to feature readiness.
        AirPlayMainBufferedInfo(dcxEnabled = true)
        AirPlayVideoPlaybackInfo(videoPlaybackAllowed = true)
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(1280, 720),
        )
        val proposal = AirPlayFeatureNegotiation.propose(
            AirPlaySessionCapabilityInput(
                config,
                AirPlayTransportReadiness(
                    secureControlChannel = true,
                    eventChannel = true,
                    alternateScreenSink = true,
                    readyRcsClientTypes = setOf(
                        RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI,
                        RcsClientTypes.CAR_PLAY_VIDEO_SETTINGS,
                    ),
                    wirelessSession = true,
                ),
            ),
            listOf("mainBuffered", "videoPlayback"),
        )

        assertTrue(proposal.enabledFeatures.isEmpty())
        assertFalse(proposal.capabilities.supports(AirPlayFeature.MAIN_BUFFERED))
        assertFalse(proposal.capabilities.supports(AirPlayFeature.VIDEO_PLAYBACK))
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraConfiguration(
                configuredFeatures = setOf(
                    AirPlayFeature.MAIN_BUFFERED,
                    AirPlayFeature.VIDEO_PLAYBACK,
                ),
            )
        }
    }

    @Test
    fun confirmedVideoRcsUuidsRemainMetadataOnly() {
        val expected = mapOf(
            RcsClientTypes.CAR_PLAY_VIDEO_OVERLAY_UI to
                UUID.fromString("E3DC3EA6-E6C3-4B30-847C-B7ACFEBEA654"),
            RcsClientTypes.CAR_PLAY_VIDEO_SETTINGS to
                UUID.fromString("BB493F61-A6B8-4769-8D74-80C23A9F71C4"),
            RcsClientTypes.CAR_PLAY_MATCH_POINT to
                UUID.fromString("A6B27562-B43A-4F2D-B75F-82391E250194"),
        )
        expected.forEach { (type, uuid) ->
            assertEquals(uuid, type.uuid)
            assertEquals(AirPlayFeature.VIDEO_PLAYBACK, type.feature)
            assertEquals(RcsCatalogReadiness.METADATA_ONLY, type.catalogReadiness)
            assertFalse(type.hasConfirmedSchema)
        }
    }
}
