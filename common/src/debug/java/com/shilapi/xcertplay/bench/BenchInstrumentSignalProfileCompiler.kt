package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafUInt64
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentCafCharacteristicPin
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentNumericMeaning
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundle
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleShape
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalRole
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSemantics
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalSource
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentStateCode
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentThemeSignalAddress
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileDigest
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfileTrustBinding
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneComponentAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneSlotAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneZoneAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.ZoneRegion
import com.shilapi.xcertplay.vehicle.ultra.theme.decodeThemeJson
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

/** One Profile-ordinal address. A display ID alone is not assumed unique or sufficient. */
internal data class BenchInstrumentDisplayBinding(
    val profileDisplayIndex: Int,
    val profileDisplayId: String?,
    val spec: InstrumentSignalBundleSpec,
)

/**
 * Optional Debug-only compiler for a signed Profile's explicit instrument crosswalk. The
 * signature proves only local bench provenance; this does not grant OEM/Apple authorization,
 * activate CAF, read sensors, render gauges, or certify a physical cluster display.
 */
internal class BenchInstrumentSignalProfileCompiler {
    class Compiled internal constructor(
        private val exactVerifiedProfile: VerifiedUltraProfile,
        val profileBinding: UltraProfileTrustBinding,
        val phonePackageDigest: UltraProfileDigest,
        bindings: List<BenchInstrumentDisplayBinding>,
    ) {
        val bindings: List<BenchInstrumentDisplayBinding> =
            Collections.unmodifiableList(ArrayList(bindings))

        /** Reject reuse after the selected trusted Profile changes, even if IDs look identical. */
        fun requireSameProfile(profile: VerifiedUltraProfile) {
            require(profile === exactVerifiedProfile &&
                profile.binding == profileBinding &&
                profile.candidate.contentDigest == profileBinding.contentDigest &&
                profile.candidate.entries.singleOrNull {
                    it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
                }?.digest == phonePackageDigest
            ) { "Instrument crosswalk belongs to another verified Profile" }
        }
    }

    /** Null means no optional manifest; malformed or mismatched manifests always throw. */
    fun compile(profile: VerifiedUltraProfile): Compiled? {
        val bytes = profile.candidate.content(SPEC_PATH) ?: return null
        require(bytes.size in 1..MAX_SPEC_BYTES) { "Instrument crosswalk size is invalid" }
        val root = objectMap(decodeThemeJson(bytes), "instrument crosswalk")
        require(root.keys == ROOT_KEYS && root["format"] == FORMAT) {
            "Instrument crosswalk has unknown fields or format"
        }
        val packageBytes = requireNotNull(profile.candidate.content(
            UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON,
        )) { "Verified Profile has no phone package" }
        val packageDigest = requireNotNull(profile.candidate.entries.singleOrNull {
            it.name == UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
        }) { "Verified Profile has no unique phone package entry" }.digest
        val arc = CarPlayThemeSchema.parseArcPackageJson(packageBytes)
        val caf = BenchCafProfileCompiler().compile(profile)
        val tree = caf.syntheticMutationProfile?.configTree ?: caf.profile.configTree
        val rows = list(root["bindings"], "bindings", MAX_BINDINGS)
        val usedSlots = LinkedHashSet<Pair<Int, CarPlayThemeSceneSlotAddress>>()
        val bindings = rows.map { item ->
            val row = objectMap(item, "instrument binding")
            require(row.keys == BINDING_KEYS) { "Instrument binding has missing or unknown fields" }
            val displayIndex = index(row["profileDisplayIndex"], "profileDisplayIndex")
            val display = requireNotNull(arc.displays.getOrNull(displayIndex)) {
                "Instrument binding selects an absent Profile display"
            }
            val displayId = nullableIdentifier(row["profileDisplayId"], "profileDisplayId")
            require(display.id == displayId) { "Instrument display ID differs from Profile" }
            val layoutAddress = CarPlayThemeSceneLayoutAddress(
                index(row["layoutIndex"], "layoutIndex"),
                identifier(row["layoutId"], "layoutId"),
            )
            val zoneAddress = CarPlayThemeSceneZoneAddress(
                layoutAddress,
                index(row["zoneIndex"], "zoneIndex"),
                identifier(row["zoneId"], "zoneId"),
            )
            val componentAddress = CarPlayThemeSceneComponentAddress(
                zoneAddress,
                index(row["componentIndex"], "componentIndex"),
                identifier(row["componentId"], "componentId"),
            )
            val slotAddress = CarPlayThemeSceneSlotAddress(
                componentAddress,
                index(row["slotIndex"], "slotIndex"),
                identifier(row["positionId"], "positionId"),
            )
            val zoneRegion = when (val value = row["zoneRegion"]) {
                null -> null
                is String -> ZoneRegion.entries.singleOrNull { it.wireValue == value }
                    ?: throw IllegalArgumentException("Instrument zoneRegion is unknown")
                else -> throw IllegalArgumentException("Instrument zoneRegion is invalid")
            }
            val address = InstrumentThemeSignalAddress(
                identifier(row["dataIdentifier"], "dataIdentifier"),
                zoneRegion,
                slotAddress,
            )
            val shape = enumValue<InstrumentSignalBundleShape>(row["shape"], "shape")
            val sources = list(row["sources"], "sources", MAX_SOURCES).map(::parseSource)
            val spec = InstrumentSignalBundleSpec(
                address,
                shape,
                sources,
                exactLong(row["maximumReadAgeMillis"], "maximumReadAgeMillis"),
            )
            require(usedSlots.add(displayIndex to slotAddress)) {
                "Instrument crosswalk repeats one Profile slot"
            }
            // Reuse the runtime binder's exact Theme/CAF check without creating its worker or
            // subscribing to a data source. No IID is inferred from a Theme identifier.
            InstrumentSignalBundle.validateDeclaration(display, spec, tree)
            BenchInstrumentDisplayBinding(displayIndex, displayId, spec)
        }
        require(bindings.size == usedSlots.size)
        return Compiled(profile, profile.binding, packageDigest, bindings)
    }

    private fun parseSource(item: Any?): InstrumentSignalSource {
        val row = objectMap(item, "instrument source")
        require(row.keys == SOURCE_KEYS) { "Instrument source has missing or unknown fields" }
        val pin = InstrumentCafCharacteristicPin(
            CafUInt64.parse(row["serviceIid"], "serviceIid"),
            CafUInt64.parse(row["serviceType"], "serviceType"),
            CafUInt64.parse(row["characteristicIid"], "characteristicIid"),
            CafUInt64.parse(row["characteristicType"], "characteristicType"),
            CafCharacteristicFormat.fromWireValue(row["format"]),
        )
        val semantics = objectMap(row["semantics"], "instrument semantics")
        val meaning = when (semantics["kind"]) {
            "numeric" -> {
                require(semantics.keys == NUMERIC_KEYS) {
                    "Numeric semantics has missing or unknown fields"
                }
                InstrumentSignalSemantics.Numeric(
                    enumValue<InstrumentNumericMeaning>(semantics["meaning"], "meaning"),
                    identifier(semantics["exactCafUnits"], "exactCafUnits", 128),
                )
            }
            "state" -> {
                require(semantics.keys == STATE_KEYS) {
                    "State semantics has missing or unknown fields"
                }
                val codes = list(semantics["codes"], "codes", MAX_STATE_CODES).map { code ->
                    val fields = objectMap(code, "state code")
                    require(fields.keys == STATE_CODE_KEYS)
                    InstrumentStateCode(
                        stateCode(fields["raw"]),
                        identifier(fields["name"], "state name", 64),
                    )
                }
                InstrumentSignalSemantics.State(codes)
            }
            else -> throw IllegalArgumentException("Instrument semantics kind is unknown")
        }
        return InstrumentSignalSource(
            enumValue<InstrumentSignalRole>(row["role"], "role"), pin, meaning,
        )
    }

    private fun stateCode(raw: Any?): Any = when (raw) {
        is Boolean -> raw
        is String -> identifier(raw, "state code", 128)
        is Byte, is Short, is Int, is Long -> raw
        is BigInteger -> {
            require(raw.bitLength() <= 64) { "State code exceeds CAF integer range" }
            raw
        }
        is BigDecimal -> {
            val integer = try {
                raw.toBigIntegerExact()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("State code must be an integer", error)
            }
            require(integer.bitLength() <= 64) { "State code exceeds CAF integer range" }
            integer
        }
        else -> throw IllegalArgumentException("State code must be boolean, integer, or string")
    }

    private fun objectMap(value: Any?, label: String): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) {
            "$label must be an object"
        }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun list(value: Any?, label: String, maximum: Int): List<*> {
        require(value is List<*> && value.size in 1..maximum) {
            "$label must contain 1..$maximum entries"
        }
        return value
    }

    private fun index(value: Any?, label: String): Int = exactLong(value, label).let {
        require(it in 0..MAX_ORDINAL) { "$label is outside the bounded index range" }
        it.toInt()
    }

    private fun exactLong(value: Any?, label: String): Long = try {
        when (value) {
            is BigInteger -> {
                if (value.bitLength() > 63) throw ArithmeticException("$label is out of range")
                value.toLong()
            }
            is BigDecimal -> exactLong(value.toBigIntegerExact(), label)
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> throw IllegalArgumentException("$label must be an integer")
        }
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$label must fit an exact signed 64-bit integer", error)
    }

    private fun identifier(value: Any?, label: String, maximum: Int = 256): String {
        require(value is String && value.isNotBlank() && value.length <= maximum &&
            value.none(Char::isISOControl)
        ) { "$label is invalid" }
        return value
    }

    private fun nullableIdentifier(value: Any?, label: String): String? =
        if (value == null) null else identifier(value, label)

    private inline fun <reified E : Enum<E>> enumValue(value: Any?, label: String): E =
        enumValues<E>().singleOrNull { it.name == value }
            ?: throw IllegalArgumentException("$label is unknown")

    companion object {
        const val SPEC_PATH = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-instrument-signals-v1.json"
        private const val FORMAT = "xcertplay-instrument-signals-v1"
        private const val MAX_SPEC_BYTES = 64 * 1024
        private const val MAX_BINDINGS = 16
        private const val MAX_SOURCES = 8
        private const val MAX_STATE_CODES = 32
        private const val MAX_ORDINAL = 4095
        private val ROOT_KEYS = setOf("format", "bindings")
        private val BINDING_KEYS = setOf(
            "profileDisplayIndex", "profileDisplayId", "layoutIndex", "layoutId",
            "zoneIndex", "zoneId", "zoneRegion", "componentIndex", "componentId",
            "slotIndex", "positionId", "dataIdentifier", "shape", "maximumReadAgeMillis",
            "sources",
        )
        private val SOURCE_KEYS = setOf(
            "role", "serviceIid", "serviceType", "characteristicIid", "characteristicType",
            "format", "semantics",
        )
        private val NUMERIC_KEYS = setOf("kind", "meaning", "exactCafUnits")
        private val STATE_KEYS = setOf("kind", "codes")
        private val STATE_CODE_KEYS = setOf("raw", "name")
    }
}
