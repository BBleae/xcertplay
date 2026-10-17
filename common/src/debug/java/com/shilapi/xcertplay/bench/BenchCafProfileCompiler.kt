package com.shilapi.xcertplay.bench

import com.shilapi.xcertplay.airplay.rcs.caf.AndroidCarPropertyCafProfile
import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat
import com.shilapi.xcertplay.airplay.rcs.caf.CafErrorPolicy
import com.shilapi.xcertplay.airplay.rcs.caf.CafFormatValue
import com.shilapi.xcertplay.airplay.rcs.caf.CafUInt64
import com.shilapi.xcertplay.airplay.rcs.caf.ProtocolCafConfigTreeDecoderFactory
import com.shilapi.xcertplay.vehicle.CarPropertyValue
import com.shilapi.xcertplay.vehicle.ReadOnlyCarPropertyBackend
import com.shilapi.xcertplay.vehicle.VehicleTelemetrySnapshot
import com.shilapi.xcertplay.vehicle.ultra.AndroidCarPropertyCafBinding
import com.shilapi.xcertplay.vehicle.ultra.SpeedProvenance
import com.shilapi.xcertplay.vehicle.ultra.SpeedUnit
import com.shilapi.xcertplay.vehicle.ultra.SpeedUnitProjection
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.decodeThemeJson
import java.math.BigDecimal
import java.math.BigInteger

/** Signed *bench* schema. Its bytes are inside the inspected package's content digest. */
internal class BenchCafProfileCompiler {
    data class Compiled(
        private val readonlyProfile: AndroidCarPropertyCafProfile?,
        val source: Source,
        val syntheticValues: Map<Pair<Int, Int>, Number>,
        val syntheticMutationProfile: BenchSyntheticMutationProfile? = null,
        val requiresCarSpeedPermission: Boolean = false,
    ) {
        enum class Source { ANDROID_CAR, SYNTHETIC }

        /** The v1 Android Car bridge deliberately cannot represent v2 mutable semantics. */
        val profile: AndroidCarPropertyCafProfile
            get() = requireNotNull(readonlyProfile) { "Mutable synthetic CAF has no Android Car profile" }

        /** The synthetic backend is protocol-only and must never be reported as vehicle telemetry. */
        fun syntheticBackend(): ReadOnlyCarPropertyBackend {
            check(source == Source.SYNTHETIC && syntheticMutationProfile == null)
            return object : ReadOnlyCarPropertyBackend {
                @Volatile private var open = false
                override val isConnected: Boolean get() = open
                override fun connect(): Boolean { open = true; return true }
                override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, 0)
                override fun read(propertyId: Int, areaId: Int): CarPropertyValue? =
                    if (open) syntheticValues[propertyId to areaId]?.let { CarPropertyValue(it, null) }
                    else null
                override fun close() { open = false }
            }
        }
    }

    fun compile(verified: VerifiedUltraProfile): Compiled {
        val v1 = verified.candidate.content(SPEC_PATH)
        val v2 = verified.candidate.content(SPEC_PATH_V2)
        val v3 = verified.candidate.content(SPEC_PATH_V3)
        require(listOf(v1, v2, v3).count { it != null } == 1) {
            "Signed bench profile must carry exactly one CAF specification version"
        }
        return when {
            v3 != null -> compileV3(v3)
            v2 != null -> compileV2(v2)
            else -> compileV1(requireNotNull(v1))
        }
    }

    /** No inferred mappings: every projected IID and its source/target units are signed bytes. */
    private fun compileV3(raw: ByteArray): Compiled {
        require(raw.size in 1..MAX_SPEC_BYTES) { "Signed bench CAF v3 specification size is invalid" }
        val root = objectMap(decodeThemeJson(raw), "bench CAF v3 specification")
        require(root.keys == REQUIRED_KEYS && root["format"] == "xcertplay-debug-caf-v3" &&
            root["source"] == "androidCar"
        ) { "Speed projection requires the exact read-only Android Car v3 schema" }
        val pluginId = CafUInt64.parse(root["pluginID"], "pluginID")
        val protocolVersion = string(root["protocolVersion"], "protocolVersion", 64)
        require(protocolVersion == "1.0") { "Unsupported bench CAF v3 protocol version" }
        val pluginConfig = objectMap(root["pluginConfig"], "pluginConfig")
        require(pluginConfig.keys == setOf("pluginID", "bench") && pluginConfig["bench"] == true &&
            CafUInt64.parse(pluginConfig["pluginID"], "pluginConfig.pluginID") == pluginId
        ) { "Speed projection requires exact Debug plugin configuration" }

        val treeWire = objectMap(root["configTree"], "configTree")
        require(treeWire.keys == setOf("accessories"))
        val accessoryRows = list(treeWire["accessories"], "accessories")
        require(accessoryRows.size == 1)
        val accessory = objectMap(accessoryRows.single(), "VehicleMotion accessory")
        require(accessory.keys == setOf("type", "iid", "version", "services") &&
            CafUInt64.parse(accessory["type"], "VehicleMotion type") == VEHICLE_MOTION_TYPE
        ) { "Speed projection requires exactly one VehicleMotion accessory" }
        string(accessory["version"], "VehicleMotion version", 64)
        val serviceRows = list(accessory["services"], "VehicleMotion services")
        require(serviceRows.size == 1)
        val service = objectMap(serviceRows.single(), "DisplayedSpeed service")
        require(service.keys == setOf("type", "iid", "characteristics", "controls") &&
            CafUInt64.parse(service["type"], "DisplayedSpeed type") == DISPLAYED_SPEED_TYPE &&
            list(service["controls"], "DisplayedSpeed controls").isEmpty()
        ) { "Speed projection requires one read-only DisplayedSpeed service" }
        val characteristicRows = list(service["characteristics"], "speed characteristics")
        require(characteristicRows.size in 2..3) { "Speed projection needs two or three outputs" }
        val unitsByIid = LinkedHashMap<Long, SpeedUnit>()
        val priorities = LinkedHashSet<Int>()
        characteristicRows.forEach { item ->
            val characteristic = objectMap(item, "speed characteristic")
            require(characteristic.keys == setOf("type", "iid", "format", "priority", "units")) {
                "Speed characteristic cannot advertise writes, synthetic values or unknown fields"
            }
            val type = CafUInt64.parse(characteristic["type"], "speed characteristic type")
            val expectedUnit = SPEED_CHARACTERISTIC_UNITS[type]
                ?: throw IllegalArgumentException("Unknown Speed characteristic type")
            val iid = CafUInt64.parse(characteristic["iid"], "speed characteristic iid")
            require(iid != 0L && exactInt(characteristic["format"], "speed format") ==
                CafCharacteristicFormat.FLOAT.wireValue.toInt() &&
                exactInt(characteristic["units"], "speed units").toLong() == expectedUnit.cafUnits
            ) { "Speed characteristic must use FLOAT and its exact CAF units" }
            val priority = exactInt(characteristic["priority"], "speed priority")
            require(priority in 0..1)
            priorities += priority
            require(unitsByIid.putIfAbsent(iid, expectedUnit) == null) {
                "Duplicate Speed characteristic IID"
            }
        }
        require(unitsByIid.values.toSet().size == unitsByIid.size &&
            priorities == setOf(0, 1)
        ) { "Speed outputs need distinct units on both DATA priorities" }
        val configTree = CafConfigTree.decode(wireMap(treeWire, "configTree"))

        val bindingRows = list(root["bindings"], "speed bindings")
        require(bindingRows.size == unitsByIid.size) { "Every speed output needs a signed binding" }
        val boundIids = LinkedHashSet<Long>()
        val bindings = bindingRows.map { item ->
            val row = objectMap(item, "speed binding")
            require(row.keys == setOf("iid", "propertyId", "areaId", "sourceUnit", "targetUnit")) {
                "Speed binding has missing or unknown fields"
            }
            val iid = CafUInt64.parse(row["iid"], "speed binding iid")
            val targetUnit = requireNotNull(unitsByIid[iid]) { "Speed binding references unknown IID" }
            require(boundIids.add(iid)) { "Duplicate speed binding IID" }
            require(exactInt(row["propertyId"], "speed propertyId") ==
                VehicleTelemetrySnapshot.STANDARD_SPEED &&
                exactInt(row["areaId"], "speed areaId") == 0 &&
                exactInt(row["sourceUnit"], "sourceUnit").toLong() ==
                    SpeedUnit.METERS_PER_SECOND.cafUnits &&
                exactInt(row["targetUnit"], "targetUnit").toLong() == targetUnit.cafUnits
            ) { "Speed binding requires standard global VHAL Float m/s and exact target unit" }
            AndroidCarPropertyCafBinding(
                iid = iid,
                propertyId = VehicleTelemetrySnapshot.STANDARD_SPEED,
                areaId = 0,
                speedProjection = SpeedUnitProjection(
                    SpeedProvenance.STANDARD_ANDROID_VHAL_FLOAT,
                    SpeedUnit.METERS_PER_SECOND,
                    targetUnit,
                ),
            )
        }
        require(boundIids == unitsByIid.keys) { "Every speed output needs a signed binding" }

        val errors = objectMap(root["errors"], "errors")
        require(errors.keys == setOf(
            "unknownIdentifier", "invalidValue", "operationFailed", "generalFailure",
        )) { "Speed projection needs exact signed CAF error fields" }
        val errorPolicy = CafErrorPolicy(
            unknownIdentifier = long(errors["unknownIdentifier"], "unknownIdentifier"),
            invalidValue = long(errors["invalidValue"], "invalidValue"),
            operationFailed = long(errors["operationFailed"], "operationFailed"),
            generalFailure = long(errors["generalFailure"], "generalFailure"),
        )
        require(setOf(
            errorPolicy.unknownIdentifier, errorPolicy.invalidValue,
            errorPolicy.operationFailed, errorPolicy.generalFailure,
        ).size == 4) { "Bench CAF errors must be distinct" }
        val profile = AndroidCarPropertyCafProfile(
            profileConfigTree = configTree,
            pluginId = pluginId,
            protocolVersion = protocolVersion,
            pluginConfig = wireMap(pluginConfig, "pluginConfig"),
            bindings = bindings,
            errorPolicy = errorPolicy,
            decoderImplementationId = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1.implementationId,
            handlerImplementationId = HANDLER_ID,
            pluginName = "xcertplay DEBUG signed VHAL speed CAF — NOT OEM AUTHORIZED",
        )
        return Compiled(profile, Compiled.Source.ANDROID_CAR, emptyMap(),
            requiresCarSpeedPermission = true)
    }

    private fun compileV1(raw: ByteArray): Compiled {
        require(raw.size in 1..MAX_SPEC_BYTES) { "Signed bench CAF specification size is invalid" }
        val root = objectMap(decodeThemeJson(raw), "bench CAF specification")
        require(root.keys == REQUIRED_KEYS || root.keys == REQUIRED_KEYS + "syntheticValues") {
            "Signed bench CAF specification has missing or unknown fields"
        }
        require(root["format"] == "xcertplay-debug-caf-v1") { "Unsupported bench CAF format" }
        val source = when (root["source"]) {
            "androidCar" -> Compiled.Source.ANDROID_CAR
            "synthetic" -> Compiled.Source.SYNTHETIC
            else -> throw IllegalArgumentException("Unknown bench CAF source")
        }
        val pluginId = CafUInt64.parse(root["pluginID"], "pluginID")
        val protocolVersion = string(root["protocolVersion"], "protocolVersion", 64)
        val configTree = CafConfigTree.decode(wireMap(root["configTree"], "configTree"))
        val pluginConfig = wireMap(root["pluginConfig"], "pluginConfig")
        val bindings = list(root["bindings"], "bindings").also {
            require(it.size in 1..128) { "Signed bench CAF binding count is invalid" }
        }.map { item ->
            val row = objectMap(item, "binding")
            require(row.keys == setOf("iid", "propertyId", "areaId"))
            AndroidCarPropertyCafBinding(
                iid = CafUInt64.parse(row["iid"], "iid"),
                propertyId = exactInt(row["propertyId"], "propertyId"),
                areaId = exactInt(row["areaId"], "areaId"),
            )
        }
        require(bindings.all { it.propertyId > 0 } &&
            bindings.map { it.propertyId to it.areaId }.toSet().size == bindings.size &&
            bindings.all { binding ->
                configTree.characteristicsByIid[binding.iid]?.format in NUMERIC_FORMATS
            }
        ) { "Signed bench CAF bindings must name distinct numeric read-only properties" }
        if (source == Compiled.Source.ANDROID_CAR) {
            require(bindings.none { it.propertyId in LEGACY_REAL_SPEED_PROPERTY_IDS } &&
                configTree.characteristicsByIid.values.none {
                    it.type.value in SPEED_CHARACTERISTIC_UNITS
                }
            ) {
                "Real vehicle speed requires the exact signed v3 projection and permission gate"
            }
        }
        val errors = objectMap(root["errors"], "errors")
        require(errors.keys == setOf(
            "unknownIdentifier", "invalidValue", "operationFailed", "generalFailure",
        ))
        val errorPolicy = CafErrorPolicy(
            unknownIdentifier = long(errors["unknownIdentifier"], "unknownIdentifier"),
            invalidValue = long(errors["invalidValue"], "invalidValue"),
            operationFailed = long(errors["operationFailed"], "operationFailed"),
            generalFailure = long(errors["generalFailure"], "generalFailure"),
        )
        require(setOf(
            errorPolicy.unknownIdentifier, errorPolicy.invalidValue,
            errorPolicy.operationFailed, errorPolicy.generalFailure,
        ).size == 4) { "Bench CAF errors must be distinct" }
        val syntheticRows = when (source) {
            Compiled.Source.ANDROID_CAR -> {
                require("syntheticValues" !in root) { "Android Car profile cannot carry synthetic values" }
                emptyMap()
            }
            Compiled.Source.SYNTHETIC -> {
                val values = list(root["syntheticValues"], "syntheticValues")
                require(values.size == bindings.size) { "Synthetic values must cover every binding" }
                val bindingsByIid = bindings.associateBy { it.iid }
                val mapped = LinkedHashMap<Pair<Int, Int>, Number>()
                values.forEach { item ->
                    val row = objectMap(item, "synthetic value")
                    require(row.keys == setOf("iid", "value"))
                    val binding = requireNotNull(bindingsByIid[CafUInt64.parse(row["iid"], "iid")]) {
                        "Synthetic value references an unbound IID"
                    }
                    val value = numeric(row["value"], "synthetic value")
                    CafFormatValue.decode(
                        value,
                        requireNotNull(configTree.characteristicsByIid[binding.iid]).format,
                        "signed synthetic bench value",
                    )
                    require(mapped.putIfAbsent(binding.propertyId to binding.areaId, value) == null) {
                        "Duplicate synthetic value"
                    }
                }
                require(mapped.size == bindings.size)
                mapped.toMap()
            }
        }
        val profile = AndroidCarPropertyCafProfile(
            profileConfigTree = configTree,
            pluginId = pluginId,
            protocolVersion = protocolVersion,
            pluginConfig = pluginConfig,
            bindings = bindings,
            errorPolicy = errorPolicy,
            decoderImplementationId = ProtocolCafConfigTreeDecoderFactory.FIRMWARE_V1.implementationId,
            handlerImplementationId = HANDLER_ID,
            pluginName = "xcertplay DEBUG bench CAF — NOT OEM AUTHORIZED",
        )
        return Compiled(profile, source, syntheticRows)
    }

    private fun compileV2(raw: ByteArray): Compiled {
        require(raw.size in 1..MAX_SPEC_BYTES) { "Signed bench CAF v2 specification size is invalid" }
        val root = objectMap(decodeThemeJson(raw), "bench CAF v2 specification")
        require(root.keys == V2_KEYS && root["format"] == "xcertplay-debug-caf-v2" &&
            root["source"] == "synthetic"
        ) { "Mutable bench CAF requires the exact v2 synthetic schema" }
        val policy = objectMap(root["mutationPolicy"], "mutationPolicy")
        require(policy.keys == setOf("enabled", "maxTransactionsPerChannel") &&
            policy["enabled"] == true
        ) { "Synthetic CAF mutations require explicit signed opt-in" }
        val maxTransactions = exactInt(
            policy["maxTransactionsPerChannel"], "maxTransactionsPerChannel",
        )
        require(maxTransactions in 1..64) { "Synthetic CAF replay cache limit is invalid" }

        val pluginId = CafUInt64.parse(root["pluginID"], "pluginID")
        val protocolVersion = string(root["protocolVersion"], "protocolVersion", 64)
        val tree = CafConfigTree.decode(wireMap(root["configTree"], "configTree"))
        val pluginConfig = wireMap(root["pluginConfig"], "pluginConfig")
        require(CafUInt64.parse(pluginConfig["pluginID"], "pluginConfig.pluginID") == pluginId)
        require(tree.characteristicsByIid.size in 1..128 && tree.controlsByIid.size <= 64)
        require(tree.characteristicsByIid.values.all {
            it.format in NUMERIC_FORMATS && it.priority in setOf(0L, 1L)
        } && tree.controlsByIid.values.all { it.priority in setOf(0L, 1L) })
        require((tree.characteristicsByIid.values.map { it.priority } +
            tree.controlsByIid.values.map { it.priority }).toSet() == setOf(0L, 1L)) {
            "Synthetic CAF needs routes on both DATA priorities"
        }

        val errors = objectMap(root["errors"], "errors")
        require(errors.keys == setOf(
            "unknownIdentifier", "invalidValue", "operationFailed", "generalFailure",
        ))
        val errorPolicy = CafErrorPolicy(
            unknownIdentifier = long(errors["unknownIdentifier"], "unknownIdentifier"),
            invalidValue = long(errors["invalidValue"], "invalidValue"),
            operationFailed = long(errors["operationFailed"], "operationFailed"),
            generalFailure = long(errors["generalFailure"], "generalFailure"),
        )
        require(setOf(
            errorPolicy.unknownIdentifier, errorPolicy.invalidValue,
            errorPolicy.operationFailed, errorPolicy.generalFailure,
        ).size == 4) { "Bench CAF errors must be distinct" }

        val initialRows = list(root["syntheticValues"], "syntheticValues")
        require(initialRows.size == tree.characteristicsByIid.size)
        val initial = LinkedHashMap<Long, Any?>()
        initialRows.forEach { item ->
            val row = objectMap(item, "synthetic value")
            require(row.keys == setOf("iid", "value"))
            val iid = CafUInt64.parse(row["iid"], "synthetic value iid")
            val value = numericOrBool(row["value"], "synthetic value")
            require(iid in tree.characteristicsByIid)
            tree.validateCharacteristicValue(iid, value)
            require(initial.putIfAbsent(iid, value) == null)
        }
        require(initial.keys == tree.characteristicsByIid.keys)

        val writes = LinkedHashMap<Long, BenchSyntheticWriteBinding>()
        list(root["writeBindings"], "writeBindings").also { require(it.size in 1..128) }
            .forEach { item ->
                val row = objectMap(item, "write binding")
                require(row.keys == setOf("iid", "allowedValues"))
                val iid = CafUInt64.parse(row["iid"], "write binding iid")
                val metadata = requireNotNull(tree.characteristicsByIid[iid])
                require(metadata.writable == true || metadata.oemWritable == true)
                val allowed = allowedValues(row["allowedValues"], iid, tree)
                require(writes.putIfAbsent(iid, BenchSyntheticWriteBinding(iid, allowed)) == null)
            }
        require(writes.keys == tree.characteristicsByIid.values.filter {
            it.writable == true || it.oemWritable == true
        }.map { it.iid.value }.toSet()) {
            "Every advertised writable IID needs an explicit signed synthetic permission"
        }

        val controls = LinkedHashMap<Long, BenchSyntheticControlBinding>()
        list(root["controlBindings"], "controlBindings").also { require(it.size in 1..64) }
            .forEach { item ->
                val row = objectMap(item, "control binding")
                require(row.keys == setOf("iid", "targetIid", "requestField", "allowedValues"))
                val iid = CafUInt64.parse(row["iid"], "control binding iid")
                val targetIid = CafUInt64.parse(row["targetIid"], "control target iid")
                val metadata = requireNotNull(tree.controlsByIid[iid])
                val target = requireNotNull(tree.characteristicsByIid[targetIid])
                val requestField = string(row["requestField"], "requestField", 64)
                require(metadata.sender == com.shilapi.xcertplay.airplay.rcs.caf.CafControlSender.DEVICE &&
                    metadata.requestParameters.size == 1 &&
                    metadata.requestParameters.single().name == requestField &&
                    metadata.requestParameters.single().format == target.format &&
                    (if (metadata.hasResponse) metadata.responseParameters.size == 1 &&
                        metadata.responseParameters.single().format == target.format
                    else metadata.responseParameters.isEmpty())
                ) { "Synthetic control must set one numeric value with matching response shape" }
                val allowed = allowedValues(row["allowedValues"], targetIid, tree)
                require(controls.putIfAbsent(iid, BenchSyntheticControlBinding(
                    iid, targetIid, requestField,
                    metadata.responseParameters.singleOrNull()?.name, allowed,
                )) == null)
            }
        require(controls.keys == tree.controlsByIid.keys) {
            "Every advertised control needs an explicit signed synthetic binding"
        }
        val profile = BenchSyntheticMutationProfile(
            tree, pluginId, protocolVersion, pluginConfig, errorPolicy,
            initial, writes, controls, maxTransactions,
        )
        return Compiled(null, Compiled.Source.SYNTHETIC, emptyMap(), profile)
    }

    private fun allowedValues(value: Any?, iid: Long, tree: CafConfigTree): Set<CafFormatValue> {
        val rows = list(value, "allowedValues")
        require(rows.size in 1..32)
        return rows.mapTo(LinkedHashSet()) { item ->
            tree.validateCharacteristicValue(iid, numericOrBool(item, "allowed value"))
        }.also { require(it.size == rows.size) }
    }

    private fun wireMap(value: Any?, label: String): Map<String, Any?> =
        objectMap(value, label).mapValues { (_, child) -> wireValue(child) }

    private fun wireValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> objectMap(value, "wire map").mapValues { (_, child) -> wireValue(child) }
        is List<*> -> value.map(::wireValue)
        is BigInteger -> if (value.bitLength() <= 63) value.toLong() else value
        is BigDecimal -> if (value.scale() <= 0) {
            value.toBigIntegerExact().let { integer ->
                if (integer.bitLength() <= 63) integer.toLong() else integer
            }
        } else value.toDouble().also { require(it.isFinite()) }
        else -> value
    }

    private fun objectMap(value: Any?, label: String): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "$label must be an object" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun list(value: Any?, label: String): List<*> {
        require(value is List<*>) { "$label must be an array" }
        return value
    }

    private fun string(value: Any?, label: String, maximum: Int): String {
        require(value is String && value.isNotBlank() && value.length <= maximum &&
            value.none(Char::isISOControl)) { "$label is invalid" }
        return value
    }

    private fun long(value: Any?, label: String): Long = when (value) {
        is BigInteger -> {
            require(value.bitLength() <= 63) { "$label exceeds signed Long range" }
            value.toLong()
        }
        is BigDecimal -> value.longValueExact()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> throw IllegalArgumentException("$label must be an integer")
    }

    private fun exactInt(value: Any?, label: String): Int = long(value, label).let {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "$label is outside Int range" }
        it.toInt()
    }

    private fun numeric(value: Any?, label: String): Number = when (value) {
        is BigInteger -> if (value.bitLength() <= 63) value.toLong() else value
        is BigDecimal -> if (value.scale() <= 0) {
            value.toBigIntegerExact().let { integer ->
                if (integer.bitLength() <= 63) integer.toLong() else integer
            }
        } else value.toDouble().also { require(it.isFinite()) }
        is Number -> value
        else -> throw IllegalArgumentException("$label must be numeric")
    }

    private fun numericOrBool(value: Any?, label: String): Any =
        if (value is Boolean) value else numeric(value, label)

    companion object {
        const val SPEC_PATH = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-bench-caf-v1.json"
        const val SPEC_PATH_V2 = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-bench-caf-v2.json"
        const val SPEC_PATH_V3 = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY +
            "xcertplay-bench-caf-v3.json"
        const val HANDLER_ID = "xcertplay.debug.bench.caf.readonly.v1"
        const val MUTABLE_HANDLER_ID = "xcertplay.debug.bench.caf.synthetic-mutable.v2"
        private const val MAX_SPEC_BYTES = 256 * 1024
        private const val VEHICLE_MOTION_TYPE = 0x09000001L
        private const val DISPLAYED_SPEED_TYPE = 0x1A000009L
        // PERF_VEHICLE_SPEED and PERF_VEHICLE_SPEED_DISPLAY are both Float m/s and require
        // Android Automotive CAR_SPEED; v1 has no verified unit, freshness, or permission contract.
        private val LEGACY_REAL_SPEED_PROPERTY_IDS = setOf(
            VehicleTelemetrySnapshot.STANDARD_SPEED, 0x11600208,
        )
        private val SPEED_CHARACTERISTIC_UNITS = mapOf(
            0x30000021L to SpeedUnit.METERS_PER_SECOND,
            0x30000046L to SpeedUnit.KILOMETERS_PER_HOUR,
            0x30000047L to SpeedUnit.MILES_PER_HOUR,
        )
        private val REQUIRED_KEYS = setOf(
            "format", "source", "pluginID", "protocolVersion", "configTree",
            "pluginConfig", "bindings", "errors",
        )
        private val V2_KEYS = setOf(
            "format", "source", "pluginID", "protocolVersion", "configTree",
            "pluginConfig", "errors", "syntheticValues", "mutationPolicy",
            "writeBindings", "controlBindings",
        )
        private val NUMERIC_FORMATS = setOf(
            CafCharacteristicFormat.BOOL,
            CafCharacteristicFormat.UINT8,
            CafCharacteristicFormat.UINT16,
            CafCharacteristicFormat.UINT32,
            CafCharacteristicFormat.UINT64,
            CafCharacteristicFormat.INT8,
            CafCharacteristicFormat.INT16,
            CafCharacteristicFormat.INT32,
            CafCharacteristicFormat.INT64,
            CafCharacteristicFormat.FLOAT,
        )
    }
}
