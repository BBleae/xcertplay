package com.shilapi.xcertplay.vehicle.ultra.theme

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileCandidate
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileInspectionResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageInspector
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustAttestation
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustResult
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileVerificationResult
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CarPlayThemeResourceSchemasTest {
    @Test
    fun parsesDirectAssetManifestDestinationBitsAndFrozenExtensions() {
        val blob = byteArrayOf(1, 2, 3)
        val input = linkedMapOf<String, Any?>(
            "primaryIcon" to linkedMapOf(
                "path" to "Images/primary.png",
                "assetType" to "image",
                "destination" to 15,
                "vendor" to mapOf("blob" to blob),
            ),
            "fontAlias" to linkedMapOf(
                "path" to "Fonts/main.otf",
                "assetType" to "font",
                "destination" to 5,
            ),
            "opaqueResource" to linkedMapOf(
                "path" to "Data/config.bin",
                "assetType" to "resource",
                "destination" to 0,
            ),
        )

        val parsed = CarPlayAssetManifestSchema.parse(input)
        blob[0] = 99

        val all = parsed.entries.getValue("primaryIcon")
        assertEquals(AssetManifestAssetType.IMAGE, all.assetType)
        assertEquals(15, all.destination.bits)
        assertTrue(all.destination.includesPhone)
        assertTrue(all.destination.includesAccessory)
        assertTrue(all.destination.includesArc)
        assertTrue(all.destination.includesResolution)

        val phoneAndArc = parsed.entries.getValue("fontAlias").destination
        assertTrue(phoneAndArc.includesPhone)
        assertFalse(phoneAndArc.includesAccessory)
        assertTrue(phoneAndArc.includesArc)
        assertFalse(phoneAndArc.includesResolution)

        val first = all.extensions.getValue("vendor").value as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), first["blob"] as ByteArray)
        (first["blob"] as ByteArray)[0] = 42
        val second = all.extensions.getValue("vendor").value as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), second["blob"] as ByteArray)
    }

    @Test
    fun rejectsUnsafeDuplicateAndCaseConflictingAssetPaths() {
        val unsafePaths = listOf(
            "../escape.png",
            "/absolute.png",
            "C:drive.png",
            "dir\\escape.png",
            "dir//empty.png",
            "dir/./dot.png",
            "dir/../escape.png",
            "control\u0000.png",
            "Cafe\u0301/icon.png",
            String(charArrayOf('\uD800')) + ".png",
        )
        unsafePaths.forEach { path ->
            assertThrows("path=$path", CarPlayThemeSchemaException::class.java) {
                CarPlayAssetManifestSchema.parse(
                    mapOf("entry" to minimalAssetEntry(path)),
                )
            }
        }

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayAssetManifestSchema.parse(
                linkedMapOf(
                    "one" to minimalAssetEntry("same.png"),
                    "two" to minimalAssetEntry("same.png"),
                ),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayAssetManifestSchema.parse(
                linkedMapOf(
                    "one" to minimalAssetEntry("Images/Icon.png"),
                    "two" to minimalAssetEntry("images/icon.png"),
                ),
            )
        }
    }

    @Test
    fun acceptsOnlyConfirmedAssetTypesAndDestinationOptionBits() {
        (0..15).forEach { bits ->
            val parsed = CarPlayAssetManifestSchema.parse(
                mapOf("entry" to minimalAssetEntry("asset-$bits.bin", destination = bits)),
            )
            assertEquals(bits, parsed.entries.getValue("entry").destination.bits)
        }

        listOf<Any>(-1, 16, 1.0, BigInteger.valueOf(Int.MAX_VALUE.toLong() + 1)).forEach { bits ->
            assertThrows("bits=$bits", CarPlayThemeSchemaException::class.java) {
                CarPlayAssetManifestSchema.parse(
                    mapOf("entry" to minimalAssetEntry("asset.bin", destination = bits)),
                )
            }
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayAssetManifestSchema.parse(
                mapOf(
                    "entry" to minimalAssetEntry("asset.bin").toMutableMap().also {
                        it["assetType"] = "video"
                    },
                ),
            )
        }
    }

    @Test
    fun verifiedProfileValidationReportsMissingAndUndeclaredWithoutAddingThem() {
        val profile = verifiedProfile(
            mapOf(
                "Icons/declared.png" to byteArrayOf(1),
                "Data/undeclared.bin" to byteArrayOf(2),
            ),
        )
        val manifest = CarPlayAssetManifestSchema.parse(
            linkedMapOf(
                "present" to minimalAssetEntry("Icons/declared.png"),
                "missing" to minimalAssetEntry("Icons/missing.png"),
            ),
        )

        val validation = manifest.validateAgainst(profile)

        assertFalse(validation.allDeclaredTargetsExist)
        assertEquals(listOf("Icons/missing.png"), validation.missingTargetPaths)
        assertTrue("Data/undeclared.bin" in validation.undeclaredResourcePaths)
        assertTrue("package.json" in validation.undeclaredResourcePaths)
        assertEquals(setOf("present", "missing"), manifest.entries.keys)

        val satisfied = CarPlayAssetManifestSchema.parse(
            mapOf("present" to minimalAssetEntry("Icons/declared.png")),
        ).validateAgainst(profile)
        assertTrue(satisfied.allDeclaredTargetsExist)
        assertTrue("Data/undeclared.bin" in satisfied.undeclaredResourcePaths)
    }

    @Test
    fun editableThemeHasExactRootAndTypedOptionalLayoutStyleFields() {
        val overrideBlob = byteArrayOf(4, 5)
        val parsed = CarPlayEditableThemeSchema.parse(
            mapOf(
                "layoutStyles" to listOf(
                    mapOf<String, Any?>("id" to "minimal"),
                    linkedMapOf(
                        "id" to "sport",
                        "colorSetOverrides" to mapOf("dynamic" to overrideBlob),
                        "defaultPaletteID" to "palette-red",
                        "defaultWallpaperID" to "wallpaper-red",
                        "excludedWallpaperIDs" to listOf("wallpaper-blue"),
                        "futureStyleField" to byteArrayOf(8),
                    ),
                ),
            ),
        )
        overrideBlob[0] = 99

        val minimal = parsed.layoutStyles.first()
        assertEquals("minimal", minimal.id)
        assertNull(minimal.colorSetOverrides)
        assertNull(minimal.defaultPaletteID)
        assertNull(minimal.defaultWallpaperID)
        assertNull(minimal.excludedWallpaperIDs)

        val sport = parsed.layoutStyles.last()
        assertEquals("palette-red", sport.defaultPaletteID)
        assertEquals("wallpaper-red", sport.defaultWallpaperID)
        assertEquals(listOf("wallpaper-blue"), sport.excludedWallpaperIDs)
        val overrides = sport.colorSetOverrides!!.value as Map<*, *>
        assertArrayEquals(byteArrayOf(4, 5), overrides["dynamic"] as ByteArray)
        assertTrue("futureStyleField" in sport.extensions)

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayEditableThemeSchema.parse(
                mapOf("layoutStyles" to emptyList<Any?>(), "futureRoot" to true),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayEditableThemeSchema.parse(
                mapOf("layoutStyles" to listOf(emptyMap<String, Any?>())),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayEditableThemeSchema.parse(
                mapOf(
                    "layoutStyles" to listOf(
                        mapOf("id" to "duplicate"),
                        mapOf("id" to "duplicate"),
                    ),
                ),
            )
        }
    }

    @Test
    fun parsesMinimalResolvedThemePaletteAndStrictColorSchemeCases() {
        val parsed = CarPlayResolvedThemeSchema.parse(minimalResolvedTheme())

        assertEquals(setOf("base"), parsed.palettes.keys)
        val palette = parsed.palettes.getValue("base")
        assertEquals("palette-base", palette.id)
        assertEquals("Base", palette.displayName)
        assertEquals(7, palette.sortIndex)
        assertTrue(palette.prominent)
        assertEquals(RepresentedColorSchemeCase.SINGLE, palette.representedColorScheme.case)
        assertNull(palette.representedColorScheme.dynamicAppearance)
        assertEquals(mapOf("cluster" to "palette-a"), parsed.defaultPaletteIDs)
        assertEquals(mapOf("cluster" to "wallpaper-base"), parsed.defaultWallpaperIDs)
        assertEquals(
            mapOf("cluster" to listOf("wallpaper-disabled")),
            parsed.excludedWallpaperIDs,
        )
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(
                minimalResolvedTheme().also {
                    it["defaultPaletteIDs"] = mapOf("cluster" to "missing")
                },
            )
        }

        val cases = linkedMapOf<String, Any?>(
            "single" to mapOf("color" to "#101820"),
            "dynamicColor" to mapOf(
                "lightMode" to mapOf("white" to 1.0),
                "darkMode" to mapOf("black" to 1.0),
            ),
            "linearGradient" to mapOf("stops" to emptyList<Any?>()),
            "multicolor" to emptyMap<String, Any?>(),
        )
        cases.forEach { (caseName, payload) ->
            val input = minimalResolvedTheme(caseName, payload)
            val scheme = CarPlayResolvedThemeSchema.parse(input)
                .palettes.getValue("base")
                .representedColorScheme
            assertEquals(caseName, scheme.case.wireValue)
            if (caseName == "dynamicColor") {
                assertTrue(scheme.dynamicAppearance != null)
            }
        }
    }

    @Test
    fun resolvedThemeRequiresMapsAndValidDynamicAppearanceShape() {
        val requiredMaps =
            listOf("palettes", "layoutStyles", "defaultPaletteIDs", "defaultWallpaperIDs")
        requiredMaps.forEach { key ->
            assertThrows("missing=$key", CarPlayThemeSchemaException::class.java) {
                CarPlayResolvedThemeSchema.parse(minimalResolvedTheme().toMutableMap().also {
                    it.remove(key)
                })
            }
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(
                minimalResolvedTheme(
                    "dynamicColor",
                    mapOf("lightMode" to mapOf("white" to true)),
                ),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(
                minimalResolvedTheme().withScheme(
                    linkedMapOf(
                        "single" to emptyMap<String, Any?>(),
                        "multicolor" to emptyMap<String, Any?>(),
                    ),
                ),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(
                minimalResolvedTheme().withScheme(mapOf("future" to emptyMap<String, Any?>())),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(
                minimalResolvedTheme().withScheme(mapOf("single" to mapOf("rgba" to "#101820"))),
            )
        }
    }

    @Test
    fun resolvedLayoutStylesDecodeNestedColorSetsAndFrozenGradients() {
        val parsed = CarPlayResolvedThemeSchema.parse(minimalResolvedTheme())
        val colors = parsed.layoutStyles.getValue("cluster").getValue("palette-a")
        assertEquals(setOf("partner.bench"), colors.keys)
        val gradient = colors.getValue("partner.bench").resolve(1)!!
        assertEquals(listOf("0"), gradient.stops.map { it.encodedPosition })
        assertEquals(CarPlayThemeColor.parse("#101820"), gradient.stops.single().color)

        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (parsed.layoutStyles as MutableMap<String, Any?>)["other"] = emptyMap<String, Any?>()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (parsed.layoutStyles.getValue("cluster") as MutableMap<String, Any?>)["other"] =
                emptyMap<String, Any?>()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (colors as MutableMap<String, Any?>)["other"] = emptyMap<String, Any?>()
        }

        val empty = CarPlayResolvedThemeSchema.parse(
            minimalResolvedTheme().also {
                it["layoutStyles"] = emptyMap<String, Any?>()
                it["defaultPaletteIDs"] = emptyMap<String, Any?>()
                it["defaultWallpaperIDs"] = emptyMap<String, Any?>()
                it["excludedWallpaperIDs"] = emptyMap<String, Any?>()
            },
        )
        assertTrue(empty.layoutStyles.isEmpty())
        val emptyColorSet = CarPlayResolvedThemeSchema.parse(
            minimalResolvedTheme().also {
                it["layoutStyles"] = mapOf("cluster" to mapOf("palette-a" to emptyMap<String, Any?>()))
            },
        )
        assertTrue(emptyColorSet.layoutStyles.getValue("cluster").getValue("palette-a").isEmpty())

        val dynamic = CarPlayResolvedThemeSchema.parse(minimalResolvedTheme().also {
            it["layoutStyles"] = mapOf("cluster" to mapOf("palette-a" to mapOf(
                "partner.bench" to mapOf("dynamic" to mapOf("value" to mapOf(
                    "lightMode" to gradientColor(),
                    "darkMode" to null,
                ))),
            )))
        }).layoutStyles.getValue("cluster").getValue("palette-a").getValue("partner.bench")
        assertEquals(CarPlayThemeColor.parse("#101820"), dynamic.resolve(1)!!.stops.single().color)
        assertNull(dynamic.resolve(2))
    }

    @Test
    fun resolvedLayoutStylesRejectEditableShapeWrongNestingAndMalformedGradients() {
        fun nestedColor(value: Any?): Map<String, Any?> =
            mapOf("cluster" to mapOf("palette-a" to mapOf("partner.bench" to value)))
        val invalid = listOf<Any?>(
            mapOf("cluster" to mapOf("id" to "cluster")),
            mapOf("cluster" to listOf("palette-a")),
            mapOf("cluster" to mapOf("palette-a" to listOf("partner.bench"))),
            nestedColor("#101820"),
            nestedColor(mapOf("single" to mapOf("value" to mapOf(
                "stops" to listOf("#101820"),
            )))),
            nestedColor(mapOf("single" to mapOf("value" to mapOf("future" to true)))),
            nestedColor(mapOf("future" to mapOf("value" to gradientColor()))),
            nestedColor(mapOf("single" to mapOf("value" to gradientColor(), "extra" to true))),
            nestedColor(mapOf("dynamic" to mapOf("value" to mapOf(
                "lightMode" to gradientColor(),
            )))),
            nestedColor(mapOf("single" to mapOf("value" to mapOf(
                "stops" to mapOf("nan" to "#101820"),
            )))),
        )
        invalid.forEachIndexed { index, styles ->
            assertThrows("invalid[$index]", CarPlayThemeSchemaException::class.java) {
                CarPlayResolvedThemeSchema.parse(minimalResolvedTheme().also {
                    it["layoutStyles"] = styles
                })
            }
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(minimalResolvedTheme().also {
                it["defaultPaletteIDs"] = mapOf("cluster" to "not-a-color-set")
            })
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parseJson(
                """{"palettes":{},"layoutStyles":{"cluster":{"palette-a":{"partner.bench":{},"partner.bench":{}}}},"defaultPaletteIDs":{},"defaultWallpaperIDs":{}}""".encodeToByteArray(),
            )
        }
        val tooManyColors = (0..CarPlayThemeAppearanceSchema.MAXIMUM_PALETTE_COLOR_IDS)
            .associate { "partner.color-$it" to mapOf("single" to mapOf("value" to gradientColor())) }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(minimalResolvedTheme().also {
                it["layoutStyles"] = mapOf("cluster" to mapOf("palette-a" to tooManyColors))
            })
        }
    }

    @Test
    fun newParsersShareMutationSafeSnapshotsAndResourceBudgets() {
        val data = byteArrayOf(1, 2, 3)
        val parsed = CarPlayResolvedThemeSchema.parse(
            minimalResolvedTheme().also { resolved ->
                @Suppress("UNCHECKED_CAST")
                val palettes = resolved.getValue("palettes") as MutableMap<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val palette = palettes.getValue("base") as MutableMap<String, Any?>
                palette["colors"] = mapOf("data" to data)
            },
        )
        data[0] = 99
        val first = parsed.palettes.getValue("base").colors.value as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), first["data"] as ByteArray)
        (first["data"] as ByteArray)[0] = 42
        val second = parsed.palettes.getValue("base").colors.value as Map<*, *>
        assertArrayEquals(byteArrayOf(1, 2, 3), second["data"] as ByteArray)

        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayAssetManifestSchema.parse(
                mapOf("entry" to minimalAssetEntry("asset.bin")),
                CarPlayThemeSchemaLimits(maximumStringBytes = 8),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayResolvedThemeSchema.parse(
                minimalResolvedTheme().also { resolved ->
                    @Suppress("UNCHECKED_CAST")
                    val palettes = resolved.getValue("palettes") as MutableMap<String, Any?>
                    @Suppress("UNCHECKED_CAST")
                    val palette = palettes.getValue("base") as MutableMap<String, Any?>
                    palette["colors"] = byteArrayOf(1, 2, 3)
                },
                CarPlayThemeSchemaLimits(maximumDataBytes = 2),
            )
        }
        assertThrows(CarPlayThemeSchemaException::class.java) {
            CarPlayAssetManifestSchema.parse(
                mapOf(
                    "entry" to minimalAssetEntry("asset.bin").toMutableMap().also {
                        it["futureNumber"] = BigInteger.ONE.shiftLeft(128)
                    },
                ),
                CarPlayThemeSchemaLimits(maximumNumberBytes = 8),
            )
        }
    }
}

private fun minimalAssetEntry(
    path: String,
    destination: Any = 1,
): Map<String, Any?> = linkedMapOf(
    "path" to path,
    "assetType" to "resource",
    "destination" to destination,
)

private fun minimalResolvedTheme(
    schemeCase: String = "single",
    schemePayload: Any? = mapOf("color" to "#101820"),
): MutableMap<String, Any?> = linkedMapOf(
    "palettes" to linkedMapOf<String, Any?>(
        "base" to linkedMapOf(
            "id" to "palette-base",
            "displayName" to "Base",
            "colors" to mapOf("primary" to mapOf("rgba" to listOf(1, 1, 1, 1))),
            "representedColorScheme" to mapOf(schemeCase to schemePayload),
            "sortIndex" to 7,
            "prominent" to true,
        ),
    ),
    "layoutStyles" to linkedMapOf<String, Any?>(
        "cluster" to mapOf("palette-a" to mapOf(
            "partner.bench" to mapOf("single" to mapOf("value" to gradientColor())),
        )),
    ),
    "defaultPaletteIDs" to linkedMapOf("cluster" to "palette-a"),
    "defaultWallpaperIDs" to linkedMapOf("cluster" to "wallpaper-base"),
    "excludedWallpaperIDs" to mapOf("cluster" to listOf("wallpaper-disabled")),
)

private fun gradientColor(): Map<String, Any?> = mapOf("stops" to mapOf("0" to "#101820"))

private fun MutableMap<String, Any?>.withScheme(
    scheme: Map<String, Any?>,
): MutableMap<String, Any?> = also { resolved ->
    @Suppress("UNCHECKED_CAST")
    val palettes = resolved.getValue("palettes") as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val palette = palettes.getValue("base") as MutableMap<String, Any?>
    palette["representedColorScheme"] = scheme
}

private fun verifiedProfile(
    phoneFiles: Map<String, ByteArray>,
): VerifiedUltraProfile {
    val root = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY
    val entries = mutableListOf(
        root to ByteArray(0),
        UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to "{}".encodeToByteArray(),
        UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
        UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
            BplistCodec.encode(mapOf("Bundle" to "Phone")),
        UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(1),
        UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(2),
        UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(3),
        UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(4),
        UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Test" to true)),
    )
    phoneFiles.forEach { (path, bytes) -> entries += ("$root$path" to bytes) }

    val archive = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
    val inspector = UltraProfilePackageInspector()
    val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
        UltraProfileInspectionResult.Candidate).profile
    val binding = testBinding(candidate)
    val verified = inspector.verify(candidate, binding) {
        UltraProfileTrustResult.Trusted(
            UltraProfileTrustAttestation(binding, byteArrayOf(1)),
        )
    }
    return (verified as UltraProfileVerificationResult.Verified).profile
}

private fun testBinding(candidate: UltraProfileCandidate) = UltraProfileTrustBinding(
    vehicleId = "vehicle",
    mfiCertificateIdentity = "certificate",
    assetIdentifier = "asset",
    iosContentVersion = "ios",
    accessoryContentVersion = "accessory",
    contentDigest = candidate.contentDigest,
)
