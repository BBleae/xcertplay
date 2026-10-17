package com.shilapi.xcertplay.vehicle

/** A property number is scoped to its vehicle platform, with value type and unit explicit. */
enum class VehiclePlatform { STANDARD_ANDROID }
enum class VehicleGear(val letter: Char) { PARK('P'), REVERSE('R'), NEUTRAL('N'), DRIVE('D') }
data class CarPropertyReading(
    val propertyId: Int,
    val value: Number,
    val elapsedRealtimeMillis: Long,
    val origin: String,
    val sourceEventMillis: Long? = null,
)

data class VehicleTelemetrySnapshot(
    val readings: Map<Int, CarPropertyReading>,
    val platform: VehiclePlatform,
    val sourceUnavailableReason: String? = null,
    val unavailableProperties: Map<Int, String> = emptyMap(),
) {
    fun fresh(id: Int, now: Long, maxAgeMillis: Long = 30_000): Number? =
        readings[id]?.takeIf { now - it.elapsedRealtimeMillis in 0..maxAgeMillis }?.value

    // No standard Android property has a verified SOC/range mapping for CarPlay yet.
    fun batteryPercent(now: Long): Double? = null
    fun rangeKm(now: Long): Double? = null
    fun speedMetersPerSecond(now: Long): Double? =
        (readings[STANDARD_SPEED]?.takeIf { reading ->
            now - reading.elapsedRealtimeMillis in 0..2_000 &&
                reading.sourceEventMillis?.let { now - it in 0..2_000 } == true
        }?.value as? Float)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..150.0 }
    fun outsideTemperatureCelsius(now: Long): Double? {
        if (platform != VehiclePlatform.STANDARD_ANDROID) return null
        val reading = readings[STANDARD_OUTSIDE_TEMPERATURE] ?: return null
        if (now - reading.elapsedRealtimeMillis !in 0..30_000 ||
            reading.sourceEventMillis?.let { now - it in 0..30_000 } != true) return null
        return (reading.value as? Float)?.toDouble()?.takeIf { it.isFinite() && it in -100.0..100.0 }
    }
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
        /** User-selected gear; distinct from CURRENT_GEAR used by the existing iAP2 path. */
        const val STANDARD_GEAR_SELECTION = 0x11400401
        const val STANDARD_OUTSIDE_TEMPERATURE = 0x11600703
    }
}

/** Backend remains injectable, so permissions and platform failures need no car to test. */
data class CarPropertyValue(val value: Number, val sourceEventMillis: Long?)
data class CarPropertyBooleanValue(val value: Boolean, val sourceEventMillis: Long?)
enum class CarPropertySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/** Raw, same-manager acquisition. No caller-provided `parked` Boolean can stand in for this evidence. */
internal data class VideoParkSensorSnapshot(
    val sourceIdentity: Any,
    val sourceGeneration: Long,
    val sampledAtMillis: Long,
    val speed: CarPropertyValue,
    val selectedGear: CarPropertyValue,
    val parkingBrake: CarPropertyBooleanValue?,
    val parkingBrakeSupport: CarPropertySupport,
)

interface ReadOnlyCarPropertyBackend : AutoCloseable {
    val isConnected: Boolean get() = true
    val availabilityReason: String? get() = null
    /** Changes whenever the exact CarPropertyManager connection changes; null is never trusted. */
    val sourceIdentity: Any? get() = null
    /** UNKNOWN includes permission denial and failed support discovery, not just absent data. */
    val parkingBrakeSupport: CarPropertySupport get() = CarPropertySupport.UNKNOWN
    fun connect(): Boolean
    /** Legacy/global-property read, equivalent to area ID zero. */
    fun read(propertyId: Int): CarPropertyValue?

    /**
     * Exact Android Car area read. Legacy backends cannot safely reinterpret a nonzero area as
     * area zero, so they must fail closed until they implement this overload explicitly.
     */
    fun read(propertyId: Int, areaId: Int): CarPropertyValue? {
        if (areaId != 0) throw UnsupportedOperationException("Backend does not support area-aware Car property reads")
        return read(propertyId)
    }

    /** Native Boolean VHAL value; numeric zero/one must not be coerced into parking-brake state. */
    fun readBoolean(propertyId: Int): CarPropertyBooleanValue? = null

    /** Exact global property/status/type/timestamp check for safety-relevant speed and selection. */
    fun readVideoParkNumber(propertyId: Int): CarPropertyValue? = null
}

class CarPropertyDataStore(
    private val backend: ReadOnlyCarPropertyBackend,
    val platform: VehiclePlatform,
    private val now: () -> Long,
) : AutoCloseable {
    private val readings = java.util.concurrent.ConcurrentHashMap<Int, CarPropertyReading>()
    private val failures = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val unavailable = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val parkLock = Any()
    private var parkSourceIdentity: Any? = null
    private var parkSourceGeneration = 0L
    private var parkSample: VideoParkSensorSnapshot? = null
    @Volatile private var connected = false
    fun connect(): Boolean {
        if (connected) return true
        unavailable.clear()
        failures.clear()
        readings.clear()
        invalidateVideoParkEvidence()
        connected = backend.connect()
        return connected
    }

    /**
     * Independent of iAP2 wheel-speed subscriptions. Every sample reads speed, selected gear, and
     * native Boolean brake from one exact connected backend manager; any read failure drops the
     * entire sample rather than mixing old and new properties.
     */
    internal fun sampleVideoParkEvidence(): VideoParkSensorSnapshot? {
        val before = backend.sourceIdentity
        if (!connected || !backend.isConnected || before == null) {
            invalidateVideoParkEvidence()
            return null
        }
        val generation = synchronized(parkLock) {
            if (parkSourceIdentity !== before) {
                parkSourceGeneration = nextParkGeneration(parkSourceGeneration)
                parkSourceIdentity = before
                parkSample = null
            }
            parkSourceGeneration
        }
        val values = try {
            val speed = backend.readVideoParkNumber(VehicleTelemetrySnapshot.STANDARD_SPEED)
            val gear = backend.readVideoParkNumber(VehicleTelemetrySnapshot.STANDARD_GEAR_SELECTION)
            val brake = backend.readBoolean(STANDARD_PARKING_BRAKE_ON)
            val brakeSupport = backend.parkingBrakeSupport
            if (speed == null || gear == null) null else VideoParkSensorSnapshot(
                before, generation, now(), speed, gear, brake, brakeSupport,
            )
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            null
        }
        val after = backend.sourceIdentity
        return synchronized(parkLock) {
            if (!connected || !backend.isConnected || after !== before ||
                parkSourceIdentity !== before || parkSourceGeneration != generation
            ) {
                retireVideoParkSourceLocked()
                null
            } else {
                parkSample = values
                values
            }
        }
    }

    /** Rechecks the live manager identity so a cached P cannot survive a disconnect/rebind. */
    internal fun latestVideoParkEvidence(): VideoParkSensorSnapshot? {
        val source = backend.sourceIdentity
        return synchronized(parkLock) {
            if (!connected || !backend.isConnected || source == null || source !== parkSourceIdentity) {
                retireVideoParkSourceLocked()
                null
            } else parkSample
        }
    }

    internal fun invalidateVideoParkEvidence() = synchronized(parkLock) {
        retireVideoParkSourceLocked()
    }

    private fun retireVideoParkSourceLocked() {
        if (parkSourceIdentity != null || parkSample != null) {
            parkSourceGeneration = nextParkGeneration(parkSourceGeneration)
        }
        parkSourceIdentity = null
        parkSample = null
    }

    private fun nextParkGeneration(previous: Long): Long =
        if (previous == Long.MAX_VALUE) 1L else previous + 1L
    /** A denied/unsupported property is not retried continuously; reconnect resets the policy. */
    fun refresh(ids: Iterable<Int>) {
        if (!connected) return
        if (!backend.isConnected) {
            readings.clear()
            if (!backend.connect() || !backend.isConnected) return
        }
        for (id in ids) {
            if (id in unavailable) continue
            var failure: String? = null
            val value = try { backend.read(id) } catch (error: Exception) {
                failure = if (error is SecurityException) "permission denied" else if (error is IllegalArgumentException || error is UnsupportedOperationException) "unsupported property" else "temporary service failure"
                if (error is SecurityException || error is IllegalArgumentException ||
                    error is UnsupportedOperationException) unavailable += id
                null // transient failures invalidate immediately but recover on the next poll
            }
            synchronized(readings) {
                if (!connected) return
                if (value == null) {
                    readings.remove(id)
                    failures[id] = failure ?: "no available reading"
                } else {
                    failures.remove(id)
                    readings[id] = CarPropertyReading(id, value.value, now(),
                        "android.car/${platform.name}/0x${id.toString(16)}", value.sourceEventMillis)
                }
            }
        }
    }
    fun snapshot() = VehicleTelemetrySnapshot(if (backend.isConnected) readings.toMap() else emptyMap(), platform,
        if (!backend.isConnected) backend.availabilityReason ?: "car property service disconnected" else null,
        failures.toMap())
    override fun close() {
        connected = false
        invalidateVideoParkEvidence()
        synchronized(readings) {
            readings.clear()
            unavailable.clear()
            failures.clear()
        }
        backend.close()
    }

    private companion object {
        const val STANDARD_PARKING_BRAKE_ON = 0x11200402
    }
}
