package com.shilapi.xcertplay.vehicle

/** A property number is scoped to its vehicle platform, with value type and unit explicit. */
enum class VehiclePlatform { STANDARD_ANDROID }
enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }
data class CarPropertyReading(
    val propertyId: Int,
    val value: Number,
    val elapsedRealtimeMillis: Long,
    val origin: String,
)

data class VehicleTelemetrySnapshot(
    val readings: Map<Int, CarPropertyReading>,
    val platform: VehiclePlatform,
) {
    fun fresh(id: Int, now: Long, maxAgeMillis: Long = 30_000): Number? =
        readings[id]?.takeIf { now - it.elapsedRealtimeMillis in 0..maxAgeMillis }?.value

    // No standard Android property has a verified SOC/range mapping for CarPlay yet.
    fun batteryPercent(now: Long): Double? = null
    fun rangeKm(now: Long): Double? = null
    fun speedMetersPerSecond(now: Long): Double? =
        (fresh(STANDARD_SPEED, now, 2_000) as? Float)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..150.0 }
    fun gear(now: Long): VehicleGear? {
        return when (fresh(STANDARD_GEAR, now) as? Int) {
            1 -> VehicleGear.NEUTRAL; 2 -> VehicleGear.REVERSE
            4 -> VehicleGear.PARK; 8 -> VehicleGear.DRIVE
            else -> null
        }
    }
    companion object {
        const val STANDARD_SPEED = 0x11600207
        const val STANDARD_GEAR = 0x11400400
    }
}

/** Backend remains injectable, so permissions and platform failures need no car to test. */
interface ReadOnlyCarPropertyBackend : AutoCloseable {
    fun connect(): Boolean
    fun read(propertyId: Int): Number?
}

class CarPropertyDataStore(
    private val backend: ReadOnlyCarPropertyBackend,
    val platform: VehiclePlatform,
    private val now: () -> Long,
) : AutoCloseable {
    private val readings = java.util.concurrent.ConcurrentHashMap<Int, CarPropertyReading>()
    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var connected = false
    fun connect(): Boolean {
        if (connected) return true
        unavailable.clear()
        readings.clear()
        connected = backend.connect()
        return connected
    }
    /** A denied/unsupported property is not retried continuously; reconnect resets the policy. */
    fun refresh(ids: Iterable<Int>) {
        if (!connected) return
        for (id in ids) {
            if (id in unavailable) continue
            val value = try { backend.read(id) } catch (_: Exception) {
                unavailable += id
                null
            }
            if (value == null) readings.remove(id) else readings[id] =
                CarPropertyReading(id, value, now(), "android.car/${platform.name}/0x${id.toString(16)}")
        }
    }
    fun snapshot() = VehicleTelemetrySnapshot(readings.toMap(), platform)
    override fun close() {
        connected = false
        readings.clear()
        unavailable.clear()
        backend.close()
    }
}
