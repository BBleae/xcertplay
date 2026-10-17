package com.shilapi.xcertplay.airplay.video

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlayFeatureNegotiation
import com.shilapi.xcertplay.airplay.AirPlayUltraConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MatchPointFeatureGateTest {
    @Test
    fun evidenceModelCannotEnableVideoPlaybackNegotiation() {
        val config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(1280, 720),
        )
        val proposal = AirPlayFeatureNegotiation.propose(
            config,
            listOf(AirPlayFeature.VIDEO_PLAYBACK.wireName),
            eventPortAvailable = true,
        )

        assertFalse(proposal.capabilities.supports(AirPlayFeature.VIDEO_PLAYBACK))
        assertFalse(proposal.enabledFeatures.contains(AirPlayFeature.VIDEO_PLAYBACK.wireName))
        assertFalse(
            AirPlayUltraConfiguration.CONFIGURABLE_FEATURES.contains(
                AirPlayFeature.VIDEO_PLAYBACK,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AirPlayUltraConfiguration(
                configuredFeatures = setOf(AirPlayFeature.VIDEO_PLAYBACK),
            )
        }
    }
}
