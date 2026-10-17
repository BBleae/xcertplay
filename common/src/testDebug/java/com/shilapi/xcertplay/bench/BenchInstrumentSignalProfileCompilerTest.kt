package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
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
import java.util.Base64
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchInstrumentSignalProfileCompilerTest {
    private val compiler = BenchInstrumentSignalProfileCompiler()

    @Test fun absentOptionalManifestDoesNotCompileOrActivateAnything() {
        assertNull(compiler.compile(profile(spec = null)))
    }

    @Test fun signedSpeedTripletBindsExactThemeAndCafTreeAndProfileDigest() {
        val profile = profile()
        val result = requireNotNull(compiler.compile(profile))
        result.requireSameProfile(profile)
        assertEquals(profile.binding, result.profileBinding)
        assertEquals(profile.candidate.entries.single {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }.digest, result.phonePackageDigest)
        val binding = result.bindings.single()
        assertEquals(0, binding.profileDisplayIndex)
        assertEquals("cluster-display", binding.profileDisplayId)
        assertEquals("speed", binding.spec.address.dataIdentifier)
        assertEquals(InstrumentSignalBundleShape.SPEED_TRIPLET, binding.spec.shape)
        assertEquals(setOf(InstrumentSignalRole.SPEED, InstrumentSignalRole.SPEED_KMH,
            InstrumentSignalRole.SPEED_MPH), binding.spec.sources.map { it.role }.toSet())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (result.bindings as MutableList<*>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (binding.spec.sources as MutableList<*>).clear()
        }
    }

    @Test fun resultCannotBeReusedWithDifferentVerifiedProfileOrIssuer() {
        val original = profile()
        val compiled = requireNotNull(compiler.compile(original))
        compiled.requireSameProfile(original)
        val equalBytesButDifferentVerifiedInstance = profile()
        assertEquals(original.candidate.contentDigest,
            equalBytesButDifferentVerifiedInstance.candidate.contentDigest)
        assertEquals(original.binding, equalBytesButDifferentVerifiedInstance.binding)
        assertThrows(IllegalArgumentException::class.java) {
            compiled.requireSameProfile(equalBytesButDifferentVerifiedInstance)
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiled.requireSameProfile(profile(packageJson = THEME_PACKAGE.replace(
                "\"identifier\":\"bench\"", "\"identifier\":\"other\"",
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiled.requireSameProfile(profile(vehicleId = "other-vehicle"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiled.requireSameProfile(profile(spec = SPEED_SPEC + " "))
        }
    }

    @Test fun wrongSignedDisplaySlotAndThemeIdentifierFailClosed() {
        val specs = listOf(
            SPEED_SPEC.replace("\"profileDisplayIndex\":0", "\"profileDisplayIndex\":1"),
            SPEED_SPEC.replace("\"profileDisplayId\":\"cluster-display\"",
                "\"profileDisplayId\":\"other\""),
            SPEED_SPEC.replace("\"layoutId\":\"main\"", "\"layoutId\":\"other\""),
            SPEED_SPEC.replace("\"zoneIndex\":0", "\"zoneIndex\":1"),
            SPEED_SPEC.replace("\"zoneRegion\":\"zone1\"", "\"zoneRegion\":\"zone2\""),
            SPEED_SPEC.replace("\"componentId\":\"primary\"",
                "\"componentId\":\"other\""),
            SPEED_SPEC.replace("\"slotIndex\":0", "\"slotIndex\":1"),
            SPEED_SPEC.replace("\"positionId\":\"dial\"",
                "\"positionId\":\"other\""),
            SPEED_SPEC.replace("\"dataIdentifier\":\"speed\"",
                "\"dataIdentifier\":\"rpm\""),
        )
        specs.forEach { spec ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(spec = spec))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(packageJson = THEME_PACKAGE.replace(
                "\"rendersLocally\":true", "\"rendersLocally\":false",
            )))
        }
    }

    @Test fun serviceCharacteristicFormatUnitAndStateSemanticsMustMatchCompiledCaf() {
        val changes = listOf(
            "\"serviceIid\":2" to "\"serviceIid\":3",
            "\"serviceType\":200" to "\"serviceType\":201",
            "\"characteristicIid\":100" to "\"characteristicIid\":105",
            "\"characteristicType\":1000" to "\"characteristicType\":9999",
            "\"format\":9" to "\"format\":8",
            "\"exactCafUnits\":\"m/s\"" to "\"exactCafUnits\":\"km/h\"",
            "\"meaning\":\"PROFILE_SPEED\"" to
                "\"meaning\":\"REVOLUTIONS_PER_MINUTE\"",
        )
        changes.forEach { (from, to) ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(spec = SPEED_SPEC.replaceFirst(from, to)))
            }
        }
        // The same exact pin checks apply when the CAF wire tree, not the crosswalk, changes.
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(caf = CAF_SPEC.replaceFirst(
                "\"units\":\"m/s\"", "\"units\":\"mph\"",
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(caf = CAF_SPEC.replaceFirst(
                "\"type\":1000", "\"type\":9999",
            )))
        }
        val rpm = profile(
            packageJson = THEME_PACKAGE.replace("\"dataIdentifier\":\"speed\"",
                "\"dataIdentifier\":\"rpm\""),
            spec = RPM_SPEC,
        )
        assertEquals(InstrumentSignalBundleShape.RPM_VALUE_AND_STATE,
            requireNotNull(compiler.compile(rpm)).bindings.single().spec.shape)
        val missingStateCode = RPM_SPEC.replace(
            """{"raw":1,"name":"running"}""", """{"raw":2,"name":"running"}""")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(
                packageJson = THEME_PACKAGE.replace("\"dataIdentifier\":\"speed\"",
                    "\"dataIdentifier\":\"rpm\""),
                spec = missingStateCode,
            ))
        }
    }

    @Test fun missingRolesDuplicateIidsAndDuplicateSlotsFailClosed() {
        val missingRole = SPEED_SPEC.replaceFirst(",\n           ${numericSource(
            "SPEED_MPH", 102, 1002, "MILES_PER_HOUR", "mph",
        )}", "")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(spec = missingRole))
        }
        val duplicateIid = SPEED_SPEC.replaceFirst(
            "\"characteristicIid\":101", "\"characteristicIid\":100")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(spec = duplicateIid))
        }
        val binding = SPEED_SPEC.substringAfter("\"bindings\":[").substringBeforeLast("]")
        val repeatedSlot = SPEED_SPEC.replace("\"bindings\":[$binding]",
            "\"bindings\":[$binding,$binding]")
        assertThrows(IllegalArgumentException::class.java) {
            compiler.compile(profile(spec = repeatedSlot))
        }
    }

    @Test fun distinctProfileDisplaysMayReuseTheSameReadOnlyCafSignal() {
        val displayStart = THEME_PACKAGE.indexOf("\"displays\":[") + "\"displays\":[".length
        val displayEnd = THEME_PACKAGE.lastIndexOf("]}")
        val firstDisplay = THEME_PACKAGE.substring(displayStart, displayEnd)
        val secondDisplay = firstDisplay.replaceFirst(
            "\"id\":\"cluster-display\"", "\"id\":\"second-cluster\"",
        )
        val packageJson = THEME_PACKAGE.substring(0, displayEnd) +
            ",$secondDisplay" + THEME_PACKAGE.substring(displayEnd)

        val bindingStart = SPEED_SPEC.indexOf("\"bindings\":[") + "\"bindings\":[".length
        val bindingEnd = SPEED_SPEC.lastIndexOf("]}")
        val firstBinding = SPEED_SPEC.substring(bindingStart, bindingEnd)
        val secondBinding = firstBinding
            .replaceFirst("\"profileDisplayIndex\":0", "\"profileDisplayIndex\":1")
            .replaceFirst("\"profileDisplayId\":\"cluster-display\"",
                "\"profileDisplayId\":\"second-cluster\"")
        val crosswalk = SPEED_SPEC.substring(0, bindingEnd) +
            ",$secondBinding" + SPEED_SPEC.substring(bindingEnd)
        val compiled = requireNotNull(compiler.compile(profile(
            packageJson = packageJson,
            spec = crosswalk,
        )))
        assertEquals(listOf(0, 1), compiled.bindings.map { it.profileDisplayIndex })
        assertEquals(listOf("cluster-display", "second-cluster"),
            compiled.bindings.map { it.profileDisplayId })
        assertEquals(setOf(100L, 101L, 102L), compiled.bindings[0].spec.sources.map {
            it.pin.characteristicIid
        }.toSet())
        assertEquals(compiled.bindings[0].spec.sources.map { it.pin },
            compiled.bindings[1].spec.sources.map { it.pin })
    }

    @Test fun strictJsonAndResourceBoundsRejectMalformedOversizeAndUnknownFields() {
        val incompressiblePadding = Base64.getEncoder().encodeToString(
            ByteArray(50_000).also { Random(1L).nextBytes(it) },
        )
        val specs = listOf(
            SPEED_SPEC.replaceFirst("\"format\":", "\"format\":0,\"format\":"),
            SPEED_SPEC.replaceFirst("\"format\":",
                "\"unknown\":true,\"format\":"),
            SPEED_SPEC.replaceFirst("\"format\":\"xcertplay-instrument-signals-v1\"",
                "\"format\":\"other\""),
            SPEED_SPEC.replaceFirst("\"maximumReadAgeMillis\":1000",
                "\"maximumReadAgeMillis\":100.5"),
            SPEED_SPEC.replaceFirst("\"maximumReadAgeMillis\":1000",
                "\"maximumReadAgeMillis\":30001"),
            SPEED_SPEC + " ",
            SPEED_SPEC.dropLast(1),
            SPEED_SPEC.replaceFirst("\"format\":",
                "\"padding\":\"$incompressiblePadding\",\"format\":"),
        )
        specs.filterIndexed { index, _ -> index != 5 }.forEach { spec ->
            assertThrows(IllegalArgumentException::class.java) {
                compiler.compile(profile(spec = spec))
            }
        }
        // Whitespace remains valid JSON and changes only the Profile digest, not semantics.
        assertTrue(compiler.compile(profile(spec = specs[5])) != null)
    }

    private fun profile(
        packageJson: String = THEME_PACKAGE,
        spec: String? = SPEED_SPEC,
        caf: String = CAF_SPEC,
        vehicleId: String = "debug-bench",
    ): VerifiedUltraProfile {
        val files = listOf(
            UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY to ByteArray(0),
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON to packageJson.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_THEME_JSON to "{}".encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_PHONE_INFO_PLIST to
                BplistCodec.encode(mapOf("Bundle" to "DEBUG.BENCH")),
            BenchCafProfileCompiler.SPEC_PATH to caf.encodeToByteArray(),
            UltraProfilePackageFiles.CARPLAY_ACCESSORY_ASSETS to byteArrayOf(1),
            UltraProfilePackageFiles.CERTIFICATE_CHAIN to byteArrayOf(2),
            UltraProfilePackageFiles.ACCESSORY_ASSETS_SHA256 to byteArrayOf(3),
            UltraProfilePackageFiles.ACCESSORY_ADDITIONS to byteArrayOf(4),
            UltraProfilePackageFiles.INFO_PLIST to BplistCodec.encode(mapOf("Version" to "1")),
        ) + listOfNotNull(spec?.let {
            BenchInstrumentSignalProfileCompiler.SPEC_PATH to it.encodeToByteArray()
        })
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val inspector = UltraProfilePackageInspector()
        val candidate = (inspector.inspect(ByteArrayInputStream(archive)) as
            UltraProfileInspectionResult.Candidate).profile
        val binding = UltraProfileTrustBinding(
            vehicleId, "debug-bench-mfi", "debug-bench-assets", "1", "1",
            candidate.contentDigest,
        )
        return (inspector.verify(candidate, binding) {
            UltraProfileTrustResult.Trusted(UltraProfileTrustAttestation(
                binding, byteArrayOf(1, 2, 3),
            ))
        } as UltraProfileVerificationResult.Verified).profile
    }

    companion object {
        const val THEME_PACKAGE = """
            {"identifier":"bench","version":1,"minimumSDKVersion":"1","buildType":"debug",
             "partnerID":"debug-bench","driveModeLinkedByDefault":false,"displays":[{
              "id":"cluster-display","type":"cluster","size":[800,480],"density":1,
              "allowManufacturerAppIcon":false,"requiresDarkAppearance":false,
              "layouts":[{"id":"main","layoutType":"primary","backgroundColor":"black",
                "safeAreaInsets":{},"driveModeThemeConfigurations":{},"zones":[{
                 "id":"cluster-zone","frame":[[0,0],[800,480]],"type":{"single":{}},
                 "zoneRegion":"zone1","components":[{
                  "id":"primary","type":"primary","size":[800,480],"slots":[{
                   "style":{"circular":{}},"frame":[[0,0],[200,200]],
                   "zIndex":0,"positionId":"dial","instrument":{
                    "kind":{"gauge":{"configuration":{
                     "style":{"circular":{"config":{
                      "dataIdentifier":"speed","range":[0,300],"iconName":"dial",
                      "minLabel":"0","minLabelColor":"white",
                      "maxLabel":"300","maxLabelColor":"white",
                      "tintColors":[],"backgroundTintColors":[]}}},
                     "rendersLocally":true,"visibilityConfiguration":{}}}},
                    "backgroundColor":"black"}}]}]}]}]}]}
        """
        const val CAF_SPEC = """
            {"format":"xcertplay-debug-caf-v1","source":"synthetic","pluginID":7,
             "protocolVersion":"1.0","pluginConfig":{"pluginID":7,"bench":true},
             "configTree":{"accessories":[{"type":10,"iid":1,"services":[{
              "type":200,"iid":2,"characteristics":[
               {"type":1000,"iid":100,"format":9,"priority":0,"units":"m/s"},
               {"type":1001,"iid":101,"format":9,"priority":1,"units":"km/h"},
               {"type":1002,"iid":102,"format":9,"priority":0,"units":"mph"},
               {"type":1003,"iid":103,"format":9,"priority":1,"units":"rpm"},
               {"type":1004,"iid":104,"format":1,"priority":0,"validValues":[0,1]}],
              "controls":[]}]}]},
             "bindings":[{"iid":100,"propertyId":100,"areaId":0},
                         {"iid":101,"propertyId":101,"areaId":0},
                         {"iid":102,"propertyId":102,"areaId":0},
                         {"iid":103,"propertyId":103,"areaId":0},
                         {"iid":104,"propertyId":104,"areaId":0}],
             "errors":{"unknownIdentifier":-101,"invalidValue":-102,
                       "operationFailed":-103,"generalFailure":-104},
             "syntheticValues":[{"iid":100,"value":0.0},{"iid":101,"value":0.0},
                                {"iid":102,"value":0.0},{"iid":103,"value":0.0},
                                {"iid":104,"value":0}]}
        """
        fun numericSource(
            role: String, iid: Int, type: Int, meaning: String, units: String,
        ) = """{"role":"$role","serviceIid":2,"serviceType":200,
                    "characteristicIid":$iid,"characteristicType":$type,"format":9,
                    "semantics":{"kind":"numeric","meaning":"$meaning",
                                 "exactCafUnits":"$units"}}"""
        val SPEED_SPEC = """{"format":"xcertplay-instrument-signals-v1","bindings":[{
          "profileDisplayIndex":0,"profileDisplayId":"cluster-display",
          "layoutIndex":0,"layoutId":"main","zoneIndex":0,"zoneId":"cluster-zone",
          "zoneRegion":"zone1","componentIndex":0,"componentId":"primary",
          "slotIndex":0,"positionId":"dial","dataIdentifier":"speed",
          "shape":"SPEED_TRIPLET","maximumReadAgeMillis":1000,"sources":[
           ${numericSource("SPEED", 100, 1000, "PROFILE_SPEED", "m/s")},
           ${numericSource("SPEED_KMH", 101, 1001, "KILOMETRES_PER_HOUR", "km/h")},
           ${numericSource("SPEED_MPH", 102, 1002, "MILES_PER_HOUR", "mph")}
          ]}]}"""
        val RPM_SPEC = """{"format":"xcertplay-instrument-signals-v1","bindings":[{
          "profileDisplayIndex":0,"profileDisplayId":"cluster-display",
          "layoutIndex":0,"layoutId":"main","zoneIndex":0,"zoneId":"cluster-zone",
          "zoneRegion":"zone1","componentIndex":0,"componentId":"primary",
          "slotIndex":0,"positionId":"dial","dataIdentifier":"rpm",
          "shape":"RPM_VALUE_AND_STATE","maximumReadAgeMillis":1000,"sources":[
           ${numericSource("RPM_VALUE", 103, 1003, "REVOLUTIONS_PER_MINUTE", "rpm")},
           {"role":"RPM_STATE","serviceIid":2,"serviceType":200,
            "characteristicIid":104,"characteristicType":1004,"format":1,
            "semantics":{"kind":"state","codes":[
             {"raw":0,"name":"stopped"},{"raw":1,"name":"running"}]}}
          ]}]}"""
    }
}
