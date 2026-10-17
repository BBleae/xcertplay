package com.shilapi.xcertplay

import android.content.SharedPreferences
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayFeature
import com.shilapi.xcertplay.airplay.AirPlaySessionManagementInfo
import com.shilapi.xcertplay.airplay.AirPlayUltraRuntime
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.mfi.MfiCertificateType
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHostSettingsTest {
    @Test
    fun ultraPreferenceChangeWaitsForAllOldPhysicalCoverAcksBeforeAnyHostSave() {
        var persistedUltra = true
        var persistedOther = "original"
        var externalClusterAcknowledged = false
        val events = mutableListOf<String>()
        fun save(): Boolean = persistHostSettingsWithUltraCover(
            persistedUltraEnabled = persistedUltra,
            desiredUltraEnabled = false,
            beginMutation = {
                events += "cover"
                if (!externalClusterAcknowledged) null else Closeable { events += "ticket-close" }
            },
            persist = {
                events += "persist"
                persistedUltra = false
                persistedOther = "edited"
            },
        )

        assertFalse(save())
        assertEquals(true, persistedUltra)
        assertEquals("original", persistedOther)
        assertEquals(listOf("cover"), events)

        externalClusterAcknowledged = true
        assertTrue(save())
        assertEquals(false, persistedUltra)
        assertEquals("edited", persistedOther)
        assertEquals(listOf("cover", "cover", "persist", "ticket-close"), events)
    }

    @Test
    fun ordinarySettingsSaveNeverRequestsUltraCoverAndPeekNeverWrites() {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getBoolean" -> {
                    assertEquals("carplay_ultra_enabled", arguments!![0])
                    true
                }
                "edit" -> error("Read-only Ultra peek must not write preferences")
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
        assertTrue(AirPlayPersistence.peekCarPlayUltraEnabled(preferences))
        var savedOrdinary = false
        assertTrue(persistHostSettingsWithUltraCover(
            persistedUltraEnabled = true,
            desiredUltraEnabled = true,
            beginMutation = { error("Ordinary settings must not wait for Ultra cover") },
            persist = { savedOrdinary = true },
        ))
        assertTrue(savedOrdinary)
    }

    @Test
    fun hostSaveUsesCoverBarrierBeforePersistenceAndLeavesMenuOpenOnRejection() {
        val source = File("src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt").readText()
        val save = source.substringAfter("private fun saveSettingsAndReconnect() {")
            .substringBefore("private fun validateCatalogProbeSettings()")
        assertTrue(save.contains("persistHostSettingsWithUltraCover("))
        assertTrue(save.contains("beginMutation = UltraProfileCompositionRoot::beginConsentMutation"))
        assertTrue(save.contains("persist = ::persistMenuSettings"))
        assertTrue(save.indexOf("if (!saved)") < save.indexOf("settingsBaseline = null"))
        assertTrue(save.substringAfter("if (!saved)").substringBefore("settingsBaseline = null")
            .contains("return"))
    }

    @Test
    fun appLinksIdentificationRequiresExplicitDebugUltraOptIn() {
        assertFalse(hostAppLinksIdentificationEnabled(false, false))
        assertFalse(hostAppLinksIdentificationEnabled(false, true))
        assertFalse(hostAppLinksIdentificationEnabled(true, false))
        assertTrue(hostAppLinksIdentificationEnabled(true, true))
        assertFalse(hostAppLinksIconIdentificationEnabled(false, true))
        assertFalse(hostAppLinksIconIdentificationEnabled(true, false))
        assertTrue(hostAppLinksIconIdentificationEnabled(true, true))
    }

    @Test
    fun catalogProbeDefaultsOffAndRequiresUltraButNotCluster() {
        val defaults = CarPlayAssetCatalogProbeSettings()
        assertNull(defaults.toProbeOrNull(ultraEnabled = true))
        assertNull(hostCatalogProbeOrNull(defaults, ultraEnabled = true))

        val configured = validCatalogProbeSettings()
        assertNull(hostCatalogProbeOrNull(configured, ultraEnabled = false))
        assertNull(hostCatalogProbeOrNull(configured.copy(enabled = false), true))
        assertEquals("asset-27", hostCatalogProbeOrNull(configured, true)?.assetId)
    }

    @Test
    fun catalogProbeRejectsMalformedInputsWithoutThrowing() {
        val valid = validCatalogProbeSettings()
        val malformed = listOf(
            valid.copy(assetId = "  "),
            valid.copy(assetId = "contains\u0000nul"),
            valid.copy(assetId = "unpaired-\uD800"),
            valid.copy(assetId = "x".repeat(257)),
            valid.copy(sdkVersion = "\n"),
            valid.copy(sdkVersion = "x".repeat(257)),
            valid.copy(certificateType = "invalid"),
            valid.copy(identitySha256Hex = "a".repeat(63)),
            valid.copy(identitySha256Hex = "g".repeat(64)),
        )
        malformed.forEach { assertNull(it.toProbeOrNull(ultraEnabled = true)) }
    }

    @Test
    fun catalogProbePinAcceptsBothHexCasesAndRedactsConfiguration() {
        val lower = validCatalogProbeSettings().copy(identitySha256Hex = "ab".repeat(32))
        val upper = lower.copy(identitySha256Hex = "AB".repeat(32))
        val first = requireNotNull(lower.toProbeOrNull(ultraEnabled = true))
        val second = requireNotNull(upper.toProbeOrNull(ultraEnabled = true))
        val viaMainOnlyHost = requireNotNull(hostCatalogProbeOrNull(lower, ultraEnabled = true))

        assertTrue(first.expectedIdentitySha256.contentEquals(second.expectedIdentitySha256))
        assertTrue(first.expectedIdentitySha256.contentEquals(viaMainOnlyHost.expectedIdentitySha256))
        assertEquals(MfiCertificateType.BAA, first.expectedCertificateType)
        assertEquals(first.expectedCertificateType, viaMainOnlyHost.expectedCertificateType)
        assertEquals("27.0", first.sdkVersion)
        assertFalse(lower.toString().contains("asset-27"))
        assertFalse(lower.toString().contains("ab".repeat(32)))
        assertFalse(first.toString().contains("asset-27"))
        assertFalse(first.toString().contains("ab".repeat(32)))
    }

    @Test
    fun catalogProbeFieldsPersistAcrossReconnectButOptInCanBeCleared() {
        val preferences = memoryPreferences()
        val default = AirPlayPersistence.loadCarPlayAssetCatalogProbeSettings(preferences)
        assertFalse(default.enabled)
        assertNull(default.toProbeOrNull(ultraEnabled = true))

        val saved = validCatalogProbeSettings()
        AirPlayPersistence.saveCarPlayPresentationSettings(
            preferences,
            CarPlayPresentationSettings(clusterDisplayEnabled = true, carPlayUltraEnabled = true),
            saved,
        )
        val reconnected = AirPlayPersistence.loadCarPlayAssetCatalogProbeSettings(preferences)
        assertEquals(saved, reconnected)
        assertEquals("asset-27", hostCatalogProbeOrNull(reconnected, true)?.assetId)

        val mainOnly = CarPlayPresentationSettings(
            clusterDisplayEnabled = false,
            carPlayUltraEnabled = true,
            physicalDisplayPanelsProbeEnabled = true,
        )
        AirPlayPersistence.saveCarPlayPresentationSettings(preferences, mainOnly, reconnected)
        assertEquals(mainOnly.clusterDisplayEnabled, preferences.getBoolean("cluster_display_enabled", true))
        assertEquals(mainOnly.carPlayUltraEnabled, preferences.getBoolean("carplay_ultra_enabled", false))
        assertEquals(
            mainOnly.physicalDisplayPanelsProbeEnabled,
            preferences.getBoolean("physical_display_panels_probe_enabled", false),
        )
        val afterClusterOff = AirPlayPersistence.loadCarPlayAssetCatalogProbeSettings(preferences)
        assertEquals(saved, afterClusterOff)
        assertEquals("asset-27", hostCatalogProbeOrNull(afterClusterOff, mainOnly.carPlayUltraEnabled)?.assetId)

        AirPlayPersistence.saveCarPlayPresentationSettings(
            preferences,
            mainOnly.copy(carPlayUltraEnabled = false, physicalDisplayPanelsProbeEnabled = false),
            afterClusterOff,
        )
        val disabled = AirPlayPersistence.loadCarPlayAssetCatalogProbeSettings(preferences)
        assertFalse(disabled.enabled)
        assertEquals(saved.assetId, disabled.assetId)
        assertEquals(saved.identitySha256Hex, disabled.identitySha256Hex)
        assertNull(hostCatalogProbeOrNull(disabled, true))
    }

    private fun validCatalogProbeSettings() = CarPlayAssetCatalogProbeSettings(
        enabled = true,
        assetId = "asset-27",
        sdkVersion = "27.0",
        certificateType = "BAA",
        identitySha256Hex = "ab".repeat(32),
    )

    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "putBoolean", "putString" -> {
                    values[arguments!![0] as String] = arguments[1]
                    proxy
                }
                "apply" -> null
                "commit" -> true
                else -> error("unexpected preferences edit: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getBoolean", "getString" -> values[arguments!![0] as String] ?: arguments[1]
                "edit" -> editor
                else -> error("unexpected preferences read: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test
    fun physicalBasisMigrationRestoresKnownLegacyWidthButPreservesAmbiguousUserChoice() {
        assertEquals(
            AirPlayPhysicalSizeBasis.WIDTH,
            resolvePersistedPhysicalSizeBasis("LONGEST_EDGE", version = 1),
        )
        assertEquals(
            AirPlayPhysicalSizeBasis.LONGEST_EDGE,
            resolvePersistedPhysicalSizeBasis("LONGEST_EDGE", version = 2),
        )
        assertEquals(
            AirPlayPhysicalSizeBasis.WIDTH,
            resolvePersistedPhysicalSizeBasis("WIDTH", version = 2),
        )
        assertEquals(
            AirPlayPhysicalSizeBasis.SHORTEST_EDGE,
            resolvePersistedPhysicalSizeBasis("SHORTEST_EDGE", version = 3),
        )
        assertEquals(
            AirPlayPhysicalSizeBasis.SHORTEST_EDGE,
            resolvePersistedPhysicalSizeBasis("SHORTEST_EDGE", version = 4),
        )
        assertEquals(
            AirPlayPhysicalSizeBasis.WIDTH,
            resolvePersistedPhysicalSizeBasis(null, version = 0),
        )
    }

    @Test
    fun legacyClusterWebOutputMigratesToTheIndependentDisplayDeclaration() {
        val migrated = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = null,
            legacyClusterWebDisplayEnabled = true,
            persistedCarPlayUltraEnabled = false,
        )

        assertTrue(migrated.clusterDisplayEnabled)
        assertFalse(migrated.carPlayUltraEnabled)
        assertFalse(migrated.physicalDisplayPanelsProbeEnabled)
    }

    @Test
    fun browserDestinationAlwaysKeepsItsRequiredClusterDeclaration() {
        val resolved = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = false,
            legacyClusterWebDisplayEnabled = true,
            persistedCarPlayUltraEnabled = true,
        )

        assertTrue(resolved.clusterDisplayEnabled)
        assertTrue(resolved.carPlayUltraEnabled)
        assertFalse(resolved.physicalDisplayPanelsProbeEnabled)
    }

    @Test
    fun physicalPanelsProbeRequiresAnIndependentPersistedOptInAndUltraButNotCluster() {
        val default = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = true,
            legacyClusterWebDisplayEnabled = false,
            persistedCarPlayUltraEnabled = true,
        )
        assertFalse(default.physicalDisplayPanelsProbeEnabled)

        val optedIn = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = true,
            legacyClusterWebDisplayEnabled = false,
            persistedCarPlayUltraEnabled = true,
            persistedPhysicalDisplayPanelsProbeEnabled = true,
        )
        assertTrue(optedIn.physicalDisplayPanelsProbeEnabled)
        assertFalse(optedIn.copy(physicalDisplayPanelsProbeEnabled = false).physicalDisplayPanelsProbeEnabled)

        val ultraOff = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = true,
            legacyClusterWebDisplayEnabled = false,
            persistedCarPlayUltraEnabled = false,
            persistedPhysicalDisplayPanelsProbeEnabled = true,
        )
        assertFalse(ultraOff.physicalDisplayPanelsProbeEnabled)
        val clusterOff = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = false,
            legacyClusterWebDisplayEnabled = false,
            persistedCarPlayUltraEnabled = true,
            persistedPhysicalDisplayPanelsProbeEnabled = true,
        )
        assertTrue(clusterOff.carPlayUltraEnabled)
        assertTrue(clusterOff.physicalDisplayPanelsProbeEnabled)
        assertFalse(clusterOff.clusterDisplayEnabled)
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayPresentationSettings(
                clusterDisplayEnabled = true,
                carPlayUltraEnabled = false,
                physicalDisplayPanelsProbeEnabled = true,
            )
        }
    }

    @Test
    fun persistedUltraSurvivesLegacyClusterMigrationWithoutCreatingAnAltDisplay() {
        val resolved = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = false,
            legacyClusterWebDisplayEnabled = false,
            persistedCarPlayUltraEnabled = true,
            persistedPhysicalDisplayPanelsProbeEnabled = true,
        )
        assertEquals(
            CarPlayPresentationSettings(
                clusterDisplayEnabled = false,
                carPlayUltraEnabled = true,
                physicalDisplayPanelsProbeEnabled = true,
            ),
            resolved,
        )
    }

    @Test
    fun clusterDeclarationUsesTheConfiguredGeometryWithoutBrowserOutput() {
        val geometry = CarPlayWebDisplayConfig(
            enabled = false,
            width = 1920,
            height = 600,
            preview = true,
        )

        val display = clusterDisplayConfig(
            enabled = true,
            geometry = geometry,
            widthPhysicalMm = 240,
            fps = 60,
        )

        assertEquals(1920, display?.widthPixels)
        assertEquals(600, display?.heightPixels)
        assertEquals(75, display?.heightPhysicalMm)
        assertEquals(0, display?.primaryInputDevice)
        assertNull(
            clusterDisplayConfig(
                enabled = false,
                geometry = geometry.copy(enabled = true),
                widthPhysicalMm = 240,
                fps = 60,
            ),
        )
    }

    @Test
    fun declaredClusterWithoutBrowserKeepsAVisibleLocalPreviewSink() {
        val hidden = CarPlayWebDisplayConfig(enabled = false, width = 1280, height = 480, preview = false)
        assertEquals(hidden.copy(preview = true), requireClusterPreviewIfOnlyOutput(true, hidden))
        assertSame(hidden, requireClusterPreviewIfOnlyOutput(false, hidden))
        assertEquals(
            hidden.copy(enabled = true),
            requireClusterPreviewIfOnlyOutput(true, hidden.copy(enabled = true)),
        )
    }

    @Test
    fun restoredClusterAndPreviewToggleCannotCreateAnOutputlessLegacyStream() {
        val persisted = CarPlayPresentationSettings.resolve(
            persistedClusterDisplayEnabled = true,
            legacyClusterWebDisplayEnabled = false,
            persistedCarPlayUltraEnabled = false,
        )
        val savedWeb = CarPlayWebDisplayConfig(
            enabled = false,
            width = 1280,
            height = 480,
            preview = false,
        )
        val restoredWeb = requireClusterPreviewIfOnlyOutput(persisted.clusterDisplayEnabled, savedWeb)
        assertTrue(restoredWeb.preview)
        assertFalse(restoredWeb.enabled)

        // Both enabling the declaration and trying to turn off its only sink are normalized.
        assertTrue(requireClusterPreviewIfOnlyOutput(true, savedWeb).preview)
        assertTrue(requireClusterPreviewIfOnlyOutput(true, restoredWeb.copy(preview = false)).preview)
        // Browser-only custom output remains an intentional supported legacy configuration.
        val browserOnly = savedWeb.copy(enabled = true)
        assertSame(browserOnly, requireClusterPreviewIfOnlyOutput(true, browserOnly))
    }

    @Test
    fun browserOnlyMainOutputDoesNotCountAsVisiblePhysicalDdpSurface() {
        val hidden = CarPlayWebDisplayConfig(enabled = true, width = 1920, height = 720, preview = false)
        assertFalse(mainVideoSurfaceVisibleForDdp(true, hidden))
        assertTrue(mainVideoSurfaceVisibleForDdp(true, hidden.copy(preview = true)))
        assertTrue(mainVideoSurfaceVisibleForDdp(true, hidden.copy(enabled = false)))
        assertFalse(mainVideoSurfaceVisibleForDdp(false, hidden.copy(enabled = false)))
    }

    @Test
    fun fixedWebGeometryUsesItsOwnRotationStablePhysicalCalibration() {
        val geometry = CarPlayWebDisplayConfig(enabled = true, width = 1280, height = 720)

        assertEquals(
            com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm(356, 200),
            fixedDisplayPhysicalSize(
                geometry,
                referenceMillimeters = 200,
                basis = AirPlayPhysicalSizeBasis.SHORTEST_EDGE,
            ),
        )
        val cluster = clusterDisplayConfig(
            enabled = true,
            geometry = geometry,
            widthPhysicalMm = 200,
            fps = 60,
            physicalSizeBasis = AirPlayPhysicalSizeBasis.SHORTEST_EDGE,
        )
        assertEquals(356, cluster?.widthPhysicalMm)
        assertEquals(200, cluster?.heightPhysicalMm)
    }

    @Test
    fun sessionManagementRequiresBothTheUltraOptInAndWirelessTransport() {
        assertNull(sessionManagementInfo(false, CarPlayTransport.WIRELESS))
        assertNull(sessionManagementInfo(true, CarPlayTransport.WIRED))

        val info = sessionManagementInfo(true, CarPlayTransport.WIRELESS)
        assertEquals(1L, info?.stopSession)
        assertEquals(setOf(1L, 2L), info?.stopSessionReasons)
    }

    @Test
    fun hostUltraPlanKeepsOrdinaryModeUntouchedAndPreparesDynamicTimingOnlyForOptIn() {
        assertEquals(
            HostUltraRuntimePlan(
                createAuthorization = false,
                createFallbackRuntime = false,
                timedVideoPresentation = false,
            ),
            hostUltraRuntimePlan(ultraEnabled = false, sessionManagementInfo = null),
        )
        assertEquals(
            HostUltraRuntimePlan(
                createAuthorization = true,
                createFallbackRuntime = false,
                timedVideoPresentation = true,
            ),
            hostUltraRuntimePlan(ultraEnabled = true, sessionManagementInfo = null),
        )
        val sessionInfo = AirPlaySessionManagementInfo(1L, listOf(1L, 2L))
        assertEquals(
            HostUltraRuntimePlan(
                createAuthorization = true,
                createFallbackRuntime = true,
                timedVideoPresentation = true,
            ),
            hostUltraRuntimePlan(ultraEnabled = true, sessionManagementInfo = sessionInfo),
        )
        assertThrows(IllegalArgumentException::class.java) {
            hostUltraRuntimePlan(ultraEnabled = false, sessionManagementInfo = sessionInfo)
        }
    }

    @Test
    fun fallbackRuntimeParityAndUltraReplacementPreserveExactDisplayGeometry() {
        val runtime = AirPlayUltraRuntime()
        try {
            val sessionInfo = AirPlaySessionManagementInfo(1L, listOf(1L, 2L))
            val main = AirPlayDisplayConfig(
                widthPixels = 1920,
                heightPixels = 720,
                widthPhysicalMm = 240,
                heightPhysicalMm = 90,
            )
            val cluster = AirPlayDisplayConfig(
                widthPixels = 1280,
                heightPixels = 480,
                widthPhysicalMm = 220,
                heightPhysicalMm = 83,
            )
            val ultra = runtime.configuration(
                configuredFeatures = setOf(AirPlayFeature.SESSION_MANAGEMENT),
                sessionManagementInfo = sessionInfo,
            )
            val baseline = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "test",
                main = main,
                cluster = cluster,
                ultra = ultra,
            )

            assertTrue(hasHostUltraFallbackParity(baseline, runtime))
            assertFalse(hasHostUltraFallbackParity(baseline, null))
            assertTrue(hasHostUltraFallbackParity(baseline.copy(ultra = null), null))
            assertFalse(hasHostUltraFallbackParity(baseline.copy(ultra = null), runtime))

            val replacement = baseline.copy(ultra = ultra.copy())
            assertSame(main, replacement.main)
            assertSame(cluster, replacement.cluster)
            assertEquals(240, replacement.main.widthPhysicalMm)
            assertEquals(90, replacement.main.heightPhysicalMm)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun controllerStartsBeforeBackgroundPublicationAndFailureCannotPublish() {
        val calls = mutableListOf<String>()
        startThenPublishBackgroundSession(
            start = { calls += "start" },
            publish = { calls += "publish" },
        )
        assertEquals(listOf("start", "publish"), calls)

        var published = false
        assertThrows(IllegalStateException::class.java) {
            startThenPublishBackgroundSession(
                start = { throw IllegalStateException("synthetic start failure") },
                publish = { published = true },
            )
        }
        assertFalse(published)
    }
}
