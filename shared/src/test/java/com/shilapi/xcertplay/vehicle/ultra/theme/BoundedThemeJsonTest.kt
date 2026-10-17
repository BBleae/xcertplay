package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class BoundedThemeJsonTest {
    @Test
    fun decodesRfc8259ValueCorpusAndFreezesContainers() {
        val decoded = decodeThemeJson(
            """
            {
              "null": null,
              "true": true,
              "false": false,
              "zero": 0,
              "negative": -17,
              "longMaximum": 9223372036854775807,
              "big": 9223372036854775808,
              "decimal": 1.2300e2,
              "escaped": "\"\\\/\b\f\n\r\t",
              "unicode": "\u0041\uD83D\uDE00",
              "array": [1, "two", false, null, {"nested": []}]
            }
            """.trimIndent().encodeToByteArray(),
        ) as Map<*, *>

        assertNull(decoded["null"])
        assertEquals(true, decoded["true"])
        assertEquals(false, decoded["false"])
        assertEquals(0L, decoded["zero"])
        assertEquals(-17L, decoded["negative"])
        assertEquals(Long.MAX_VALUE, decoded["longMaximum"])
        assertEquals(BigInteger("9223372036854775808"), decoded["big"])
        assertEquals(BigDecimal("1.2300e2"), decoded["decimal"])
        assertEquals("\"\\/\b\u000c\n\r\t", decoded["escaped"])
        assertEquals("A😀", decoded["unicode"])

        @Suppress("UNCHECKED_CAST")
        val mutableMapView = decoded as MutableMap<String, Any?>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableMapView["new"] = true
        }
        @Suppress("UNCHECKED_CAST")
        val mutableListView = decoded.getValue("array") as MutableList<Any?>
        assertThrows(UnsupportedOperationException::class.java) {
            mutableListView += "new"
        }
    }

    @Test
    fun rejectsBomAndMalformedUtf8WithoutReplacement() {
        val malformed = listOf(
            byteArrayOf('"'.code.toByte(), 0xc3.toByte(), 0x28, '"'.code.toByte()),
            byteArrayOf('"'.code.toByte(), 0xc0.toByte(), 0xaf.toByte(), '"'.code.toByte()),
            byteArrayOf('"'.code.toByte(), 0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), '"'.code.toByte()),
            byteArrayOf('"'.code.toByte(), 0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte(), '"'.code.toByte()),
        )
        malformed.forEach { bytes ->
            assertThrows(BoundedThemeJsonException::class.java) { decodeThemeJson(bytes) }
        }

        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                    "null".encodeToByteArray(),
            )
        }
    }

    @Test
    fun rejectsNonJsonExtensionsAndTrailingContent() {
        val invalid = listOf(
            "",
            " ",
            "{]",
            "[}",
            "{\"a\":1,}",
            "[1,]",
            "{\"a\" 1}",
            "{\"a\":/* comment */1}",
            "{\"a\":NaN}",
            "{\"a\":Infinity}",
            "+1",
            ".1",
            "01",
            "-01",
            "1.",
            "1e",
            "1e+",
            "true false",
            "null\u0000",
            "\"unterminated",
            "\"bad\\x20escape\"",
            "\"raw\ncontrol\"",
        )
        invalid.forEach { json ->
            assertThrows("json length=${json.length}", BoundedThemeJsonException::class.java) {
                decodeThemeJson(json.encodeToByteArray())
            }
        }
    }

    @Test
    fun rejectsDecodedDuplicateKeysAndInvalidSurrogateEscapes() {
        listOf(
            "{\"same\":1,\"same\":2}",
            "{\"a\":1,\"\\u0061\":2}",
        ).forEach { json ->
            assertThrows(BoundedThemeJsonException::class.java) {
                decodeThemeJson(json.encodeToByteArray())
            }
        }

        val invalidStrings = listOf(
            "\"\\uD800\"",
            "\"\\uDC00\"",
            "\"\\uD800\\u0041\"",
            "\"\\uD800x\"",
            "\"\\u12G4\"",
            "\"\\u123\"",
        )
        invalidStrings.forEach { json ->
            assertThrows(BoundedThemeJsonException::class.java) {
                decodeThemeJson(json.encodeToByteArray())
            }
        }
        assertEquals("😀", decodeThemeJson("\"\\uD83D\\uDE00\"".encodeToByteArray()))
    }

    @Test
    fun enforcesInputTreeStringAndNumericBudgets() {
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                "null".encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumDataBytes = 3),
            )
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                "[1,2]".encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumNodeCount = 2),
            )
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                "[[[]]]".encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumNestingDepth = 1),
            )
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                "{\"a\":1,\"b\":2}".encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumCollectionEntries = 1),
            )
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                "{\"😀\":\"é\"}".encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumStringBytes = 5),
            )
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(
                "[123,45]".encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumNumberBytes = 4),
            )
        }

        val exactStrings = decodeThemeJson(
            "{\"😀\":\"é\"}".encodeToByteArray(),
            CarPlayThemeSchemaLimits(maximumStringBytes = 6),
        ) as Map<*, *>
        assertEquals("é", exactStrings["😀"])
    }

    @Test
    fun boundsIntegerDecimalCoefficientScaleAndExponent() {
        val largeInteger = "1" + "0".repeat(1_300)
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(largeInteger.encodeToByteArray())
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson("1e10001".encodeToByteArray())
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson("1e-10001".encodeToByteArray())
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(("0." + "0".repeat(10_000) + "1").encodeToByteArray())
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson("1.0e-10000".encodeToByteArray())
        }
        assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson(("1." + "9".repeat(1_300)).encodeToByteArray())
        }
        assertEquals(BigDecimal("1e10000"), decodeThemeJson("1e10000".encodeToByteArray()))
        assertEquals(BigDecimal("1e-10000"), decodeThemeJson("1e-10000".encodeToByteArray()))
    }

    @Test
    fun callerBufferMutationCannotChangeDecodedTree() {
        val bytes = "{\"value\":[\"stable\"]}".encodeToByteArray()
        val decoded = decodeThemeJson(bytes) as Map<*, *>
        bytes.fill(0)
        assertEquals(listOf("stable"), decoded["value"])
    }

    @Test
    fun luaLookingContentRemainsInertText() {
        val script = "return os.execute('must-not-run')"
        val decoded = decodeThemeJson(
            "{\"behaviorLua\":\"$script\"}".encodeToByteArray(),
        ) as Map<*, *>
        assertEquals(script, decoded["behaviorLua"])
        assertTrue(decoded["behaviorLua"] is String)
    }

    @Test
    fun schemaJsonEntrypointsUseTheSameLimits() {
        val assetManifest = CarPlayAssetManifestSchema.parseJson(
            """
            {"icon":{"path":"Icons/main.png","assetType":"image","destination":5}}
            """.trimIndent().encodeToByteArray(),
        )
        assertEquals("Icons/main.png", assetManifest.entries.getValue("icon").path)
        assertEquals(5, assetManifest.entries.getValue("icon").destination.bits)

        val editable = CarPlayEditableThemeSchema.parseJson(
            """{"layoutStyles":[{"id":"base"}]}""".encodeToByteArray(),
        )
        assertEquals("base", editable.layoutStyles.single().id)

        val arcPackage = CarPlayThemeSchema.parseArcPackageJson(
            MINIMAL_PACKAGE_JSON.encodeToByteArray(),
        )
        assertEquals("com.example.theme", arcPackage.identifier)
        assertEquals(DisplayType.CLUSTER, arcPackage.displays.single().type)

        assertThrows(BoundedThemeJsonException::class.java) {
            CarPlayThemeSchema.parseAssetManifestJson(
                """{"entry":{"path":"asset.bin","assetType":"resource","destination":1}}"""
                    .encodeToByteArray(),
                CarPlayThemeSchemaLimits(maximumStringBytes = 8),
            )
        }
    }

    @Test
    fun errorsDoNotEchoSensitiveNamesOrTokens() {
        val secret = "sensitive-profile-identifier"
        val error = assertThrows(BoundedThemeJsonException::class.java) {
            decodeThemeJson("{\"$secret\":1,\"$secret\":2}".encodeToByteArray())
        }
        assertFalse(error.message.orEmpty().contains(secret))
    }

    private companion object {
        val MINIMAL_PACKAGE_JSON =
            """
            {
              "identifier":"com.example.theme",
              "version":1,
              "minimumSDKVersion":"27.0",
              "buildType":"release",
              "partnerID":"example-partner",
              "driveModeLinkedByDefault":false,
              "displays":[{
                "type":"cluster",
                "layouts":[{
                  "id":"main",
                  "zones":[{
                    "id":"cluster-main",
                    "frame":{"opaque":true},
                    "components":[{
                      "id":"primary-component",
                      "type":"primary",
                      "size":{"opaque":true},
                      "slots":[{
                        "style":{"circular":{}},
                        "frame":{"opaque":true},
                        "zIndex":3,
                        "positionId":"center"
                      }]
                    }],
                    "type":{"single":{}},
                    "zoneRegion":"zone1"
                  }],
                  "layoutType":"primary",
                  "backgroundColor":{"opaque":true},
                  "safeAreaInsets":{"opaque":true},
                  "wallpapers":[],
                  "driveModeThemeConfigurations":{}
                }],
                "size":{"width":1920,"height":720},
                "density":2.0,
                "allowManufacturerAppIcon":true,
                "requiresDarkAppearance":false
              }]
            }
            """.trimIndent()
    }
}
