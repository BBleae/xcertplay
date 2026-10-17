package com.shilapi.xcertplay.vehicle.ultra.theme

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayThemeResolvedStyleWitnessTest {
    @Test
    fun exactSignedPaletteSelectsMatchingStaticBackgroundWithoutRecoloring() {
        val profile = profile(packageJson(), inertTheme())
        val default = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0)
        val alternate = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(
            profile, 0, 0, "palette-same",
        )
        assertEquals("palette-a", default.defaultPaletteID)
        assertEquals("palette-a", default.selectedPaletteID)
        assertEquals("palette-same", alternate.selectedPaletteID)
        assertEquals(default.phonePackageDigest, alternate.phonePackageDigest)
        assertEquals(profile.candidate.contentDigest, alternate.profileContentDigest)
        assertEquals(profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest, alternate.phonePackageDigest)

        val compiler = CarPlayThemeProgramCompiler()
        val defaultAssessment = compiler.assessExecutableStaticSubset(
            default.display, 0, 0, resolvedStyleWitness = default,
        )
        val alternateAssessment = compiler.assessExecutableStaticSubset(
            alternate.display, 0, 0, resolvedStyleWitness = alternate,
        )
        assertNotNull(defaultAssessment.proof)
        assertNotNull(alternateAssessment.proof)
        assertEquals(defaultAssessment.structuralProgram.structuralFingerprint,
            alternateAssessment.structuralProgram.structuralFingerprint)
        assertEquals(requireNotNull(defaultAssessment.proof).backgroundColor,
            requireNotNull(alternateAssessment.proof).backgroundColor)
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0, "palette-blue")
        }
        // The alternate blue color is signed and inert, but no same-layout recoloring is
        // inferred: the exact Layout.backgroundColor remains the only executable fill.
    }

    @Test
    fun selectionMustUseExactPaletteIdInSelectedStyleAndExactDisplayIdentity() {
        val profile = profile(packageJson(), inertTheme())
        listOf("palette-SAME", "palette-missing", "", "palette-other-style").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0, id)
            }
        }
        val witness = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0,
            "palette-same")
        val foreignProfile = profile(packageJson(displayID = "foreign"), inertTheme())
        val foreign = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(foreignProfile, 0, 0,
            "palette-same")
        assertNotEquals(witness.phonePackageDigest, foreign.phonePackageDigest)
        val assessment = CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
            foreign.display, 0, 0, resolvedStyleWitness = witness,
        )
        assertTrue(assessment.proof == null)
        assertTrue(assessment.issues.any {
            it.code == CarPlayThemeProgramIssueCode.OPAQUE_LAYOUT_FIELD
        })
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 1, 0, "palette-same")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 1, "palette-same")
        }
    }

    @Test
    fun inactiveComplexPalettesDoNotChangeSelectedStaticProof() {
        val colorSet = """{"primary":{"single":{"value":{"stops":{"0":"#ffffff"}}}}}"""
        val complexTheme = inertTheme(
            defaultColors = """{"primary":"#ffffff"}""",
            defaultExtra = ", \"backgroundOverrideColor\":\"#ffffff\"",
            otherColor = "#112233",
            otherColors = """{"secondary":"#ffffff"}""",
            otherColorSet = colorSet,
            otherExtra = ", \"accentColor\":\"#ffffff\"",
        )
        val profile = profile(packageJson(), complexTheme)
        val selected = CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0,
            "palette-same")
        assertEquals("palette-a", selected.defaultPaletteID)
        assertEquals("palette-same", selected.selectedPaletteID)
        assertNotNull(CarPlayThemeProgramCompiler().assessExecutableStaticSubset(
            selected.display, 0, 0, resolvedStyleWitness = selected,
        ).proof)
        // Explicitly selecting either complex palette must fail, even though its signed
        // representedColorScheme.single now equals this same Layout.backgroundColor.
        listOf<String?>(null, "palette-a", "palette-blue").forEach { requested ->
            assertThrows(IllegalArgumentException::class.java) {
                CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0, requested)
            }
        }
        val dynamicProfile = profile(packageJson(), inertTheme(otherScheme =
            """{"dynamicColor":{"lightMode":"#112233","darkMode":"#112233"}}"""))
        assertNotNull(CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(dynamicProfile, 0, 0,
            "palette-same"))
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(dynamicProfile, 0, 0,
                "palette-blue")
        }
    }

    @Test
    fun drawableOverridesDynamicColorsAndUnknownFieldsNeverMintWitness() {
        val colorSet = """{"primary":{"single":{"value":{"stops":{"0":"#ffffff"}}}}}"""
        val invalidThemes = listOf(
            inertTheme(selectedColors = """{"primary":"#ffffff"}"""),
            inertTheme(selectedExtra = ", \"backgroundOverrideColor\":\"#112233\""),
            inertTheme(selectedExtra = ", \"accentColor\":\"#ffffff\""),
            inertTheme(selectedExtra = ", \"unknownPaintField\":{}"),
            inertTheme(selectedScheme = """{"linearGradient":{}}"""),
            inertTheme(selectedScheme = """{"single":{"color":"#11223380"}}"""),
            inertTheme(selectedColorSet = colorSet),
            inertTheme(rootExtra = ", \"unknownResolvedThemeField\":{}"),
            inertTheme(selectedID = "different-palette-id"),
        )
        invalidThemes.forEachIndexed { index, json ->
            val profile = profile(packageJson(), json)
            assertThrows("unsafe theme case $index", IllegalArgumentException::class.java) {
                CarPlayThemeResolvedStyleWitness.fromVerifiedProfile(profile, 0, 0,
                    "palette-same")
            }
        }
    }

    private fun packageJson(displayID: String = "main") = """{
      "identifier":"bench-package","version":1,"minimumSDKVersion":"1",
      "buildType":"debug","partnerID":"bench","driveModeLinkedByDefault":false,
      "displays":[{"id":"$displayID","type":"centerConsole","size":[400,200],
        "density":1,"allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
        "layouts":[{"id":"layout-main","layoutType":"primary",
          "layoutStyleID":"bench-style","backgroundColor":"#112233",
          "safeAreaInsets":[0,0,0,0],"driveModeThemeConfigurations":{},"zones":[]}]}]
    }""".trimIndent()

    private fun inertTheme(
        selectedColors: String = "{}",
        selectedColorSet: String = "{}",
        selectedScheme: String = """{"single":{"color":"#112233"}}""",
        selectedExtra: String = "",
        defaultColors: String = "{}",
        defaultExtra: String = "",
        otherColor: String = "#445566",
        otherColors: String = "{}",
        otherColorSet: String = "{}",
        otherScheme: String? = null,
        otherExtra: String = "",
        rootExtra: String = "",
        selectedID: String = "palette-same",
    ) = """{
      "palettes":{
        "palette-a":${palette("palette-a", "#112233", defaultColors,
            extra = defaultExtra)},
        "palette-same":${palette(selectedID, "#112233", selectedColors,
            selectedScheme, selectedExtra)},
        "palette-blue":${palette("palette-blue", otherColor, otherColors,
            scheme = otherScheme ?: """{"single":{"color":"$otherColor"}}""",
            extra = otherExtra)},
        "palette-other-style":${palette("palette-other-style", "#112233")}},
      "layoutStyles":{"bench-style":{"palette-a":{},"palette-same":$selectedColorSet,
        "palette-blue":$otherColorSet},"other-style":{"palette-other-style":{}}},
      "defaultPaletteIDs":{"bench-style":"palette-a",
        "other-style":"palette-other-style"},"defaultWallpaperIDs":{}$rootExtra
    }""".trimIndent()

    private fun palette(
        id: String,
        color: String,
        colors: String = "{}",
        scheme: String = """{"single":{"color":"$color"}}""",
        extra: String = "",
    ) = """{"id":"$id","displayName":"$id","colors":$colors,
      "representedColorScheme":$scheme,"sortIndex":0,"prominent":false$extra}"""

    private fun profile(packageJson: String, themeJson: String): VerifiedUltraProfile {
        val entries = listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageJson.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to themeJson.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "Phone")),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(1),
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(2),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(3),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(4),
            UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to "1")),
        )
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val inspector = UltraProfilePackageInspector()
        val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
            UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding("vehicle", "certificate", "asset", "ios",
            "accessory", candidate.contentDigest)
        return (inspector.verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(binding, byteArrayOf(1)))
        } as UltraProfileVerificationResult.Verified).profile
    }
}
