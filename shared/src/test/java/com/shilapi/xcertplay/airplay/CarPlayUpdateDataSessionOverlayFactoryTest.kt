package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataLimits
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineQuota
import com.shilapi.xcertplay.airplay.rcs.update.CarPlayUpdateDataQuarantineRootOwner
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayUpdateDataSessionOverlayFactoryTest {
    @Test
    fun eachConnectionGetsItsOwnReservationAndFullQuotaFallsBackToOrdinaryConfig() {
        val root = Files.createTempDirectory("update-overlay-")
        val owner = owner(root.toFile())
        try {
            val snapshot = resolved(baseConfig())
            val factory = CarPlayUpdateDataSessionOverlayFactory(owner)
            val first = factory.open(snapshot)
            try {
                assertTrue(AirPlayFeatureNegotiation.snapshot(first.config)
                    .isReady(AirPlayFeature.FILE_TRANSFER))
                assertNotNull(first.extraRcsFactory?.descriptor(RcsClientTypes.CAR_PLAY_UPDATE_DATA))
                assertTrue(first.config !== snapshot.config)
                assertTrue(owner.activeTransferCount == 1)

                val second = factory.open(snapshot)
                try {
                    assertSame(snapshot.config, second.config)
                    assertFalse(AirPlayFeatureNegotiation.snapshot(second.config)
                        .isReady(AirPlayFeature.FILE_TRANSFER))
                    assertTrue(second.extraRcsFactory == null)
                } finally {
                    second.close()
                }
            } finally {
                first.close()
            }
            assertTrue(owner.awaitDrained(2_000))

            val third = factory.open(snapshot)
            try {
                assertTrue(AirPlayFeatureNegotiation.snapshot(third.config)
                    .isReady(AirPlayFeature.FILE_TRANSFER))
                assertTrue(third !== first)
            } finally {
                third.close()
            }
        } finally {
            owner.close()
            assertTrue(owner.awaitDrained(2_000))
        }
    }

    @Test
    fun existingUltraConfigurationIsPreservedWhenAddingTransferForOneConnection() {
        val root = Files.createTempDirectory("update-overlay-config-")
        val owner = owner(root.toFile())
        try {
            val existing = AirPlayUltraConfiguration(
                configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
            )
            val snapshot = resolved(baseConfig().copy(ultra = existing), AirPlayUltraRuntime())
            val overlay = CarPlayUpdateDataSessionOverlayFactory(owner).open(snapshot)
            try {
                assertTrue(overlay.config.ultra!!.isConfigured(AirPlayFeature.SESSION_MANAGEMENT))
                assertTrue(overlay.config.ultra!!.isConfigured(AirPlayFeature.FILE_TRANSFER))
                assertSame(existing.readiness.uiSync, overlay.config.ultra!!.readiness.uiSync)
                assertTrue(AirPlayFeatureNegotiation.snapshot(overlay.config)
                    .isReady(AirPlayFeature.FILE_TRANSFER))
            } finally {
                overlay.close()
                snapshot.ultraRuntime?.close()
            }
        } finally {
            owner.close()
            assertTrue(owner.awaitDrained(2_000))
        }
    }

    @Test
    fun alreadyConfiguredBaseTransferDoesNotReserveOrReplaceItsHandler() {
        val root = Files.createTempDirectory("update-overlay-existing-")
        val owner = owner(root.toFile())
        val runtime = AirPlayUltraRuntime()
        try {
            val base = baseConfig().copy(
                ultra = AirPlayUltraConfiguration(
                    configuredFeatures = setOf(AirPlayFeature.FILE_TRANSFER),
                    fileTransferInfo = AirPlayFileTransferInfo(),
                ),
            )
            val overlay = CarPlayUpdateDataSessionOverlayFactory(owner).open(resolved(base, runtime))
            try {
                assertSame(base, overlay.config)
                assertTrue(overlay.extraRcsFactory == null)
                assertTrue(owner.activeTransferCount == 0)
            } finally {
                overlay.close()
            }
        } finally {
            runtime.close()
            owner.close()
            assertTrue(owner.awaitDrained(2_000))
        }
    }

    private fun owner(root: java.io.File) = CarPlayUpdateDataQuarantineRootOwner(
        root = root,
        quota = CarPlayUpdateDataQuarantineQuota(maxBytes = 9_000, maxEntries = 1),
        limits = CarPlayUpdateDataLimits(maxMetadataBytes = 0, maxArchiveBytes = 4_096),
        maxConcurrentTransfers = 1,
    )

    private fun resolved(
        config: AirPlayConfig,
        runtime: AirPlayUltraRuntime? = null,
    ): AirPlaySessionRuntimeBinding.ResolvedSnapshot {
        val binding = AirPlaySessionRuntimeBinding()
        val generation = binding.beginGeneration()
        return requireNotNull(binding.resolve(generation, config, runtime))
    }

    private fun baseConfig() = AirPlayConfig(
        deviceName = "update-overlay-test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:01",
        sourceVersion = "950.7.1",
        main = AirPlayDisplayConfig(1280, 720),
    )
}
