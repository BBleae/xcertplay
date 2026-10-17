package com.shilapi.xcertplay.vehicle.ultra

/** The only source currently proven by the standard Android VHAL speed property contract. */
enum class SpeedProvenance { STANDARD_ANDROID_VHAL_FLOAT }

/** CAF's exact unit IDs for the VehicleMotion speed measurements. */
enum class SpeedUnit(val cafUnits: Long) {
    METERS_PER_SECOND(0x407L),
    KILOMETERS_PER_HOUR(0x408L),
    MILES_PER_HOUR(0x409L),
}

/** Explicit source provenance and unit conversion for one signed CAF speed characteristic. */
data class SpeedUnitProjection(
    val provenance: SpeedProvenance,
    val sourceUnit: SpeedUnit,
    val targetUnit: SpeedUnit,
) {
    internal fun project(raw: Number): Double {
        require(provenance == SpeedProvenance.STANDARD_ANDROID_VHAL_FLOAT)
        require(sourceUnit == SpeedUnit.METERS_PER_SECOND)
        // VehiclePropertyIds.PERF_VEHICLE_SPEED is a Float in m/s. A generic Number conversion
        // could silently reinterpret a different VHAL type, so reject it even when numeric.
        val metersPerSecond = (raw as? Float)?.toDouble()
            ?: throw IllegalArgumentException("Standard Android VHAL speed must be Float")
        require(metersPerSecond.isFinite() && metersPerSecond in 0.0..MAX_SPEED_MPS) {
            "Unusable Android VHAL speed"
        }
        val factor = when (targetUnit) {
            SpeedUnit.METERS_PER_SECOND -> 1.0
            SpeedUnit.KILOMETERS_PER_HOUR -> 3.6
            SpeedUnit.MILES_PER_HOUR -> 3.6 / 1.609344
        }
        val converted = metersPerSecond * factor
        require(converted.isFinite() && converted in 0.0..(MAX_SPEED_MPS * factor)) {
            "Unusable converted speed"
        }
        return converted
    }

    private companion object {
        // Same conservative limit as VehicleTelemetrySnapshot.speedMetersPerSecond().
        const val MAX_SPEED_MPS = 150.0
    }
}
