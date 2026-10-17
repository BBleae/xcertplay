package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/** No android.car class is linked on phones, and no setProperty method is used. */
class AndroidCarPropertyBackend(context: Context) : ReadOnlyCarPropertyBackend {
    private val context = context.applicationContext
    private val stateLock = Any()
    @Volatile private var failure: String? = "car property service not started"
    @Volatile private var car: Any? = null
    @Volatile private var pendingDisconnect: Any? = null
    private val managerPublication = CarPropertyManagerPublication()
    /** Guarded by [stateLock]; callbacks from an old Car must not retire a newer binding. */
    private var connectionGeneration: Any? = null
    @Volatile private var closed = false
    private fun newConnection(generation: Any) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(stateLock) {
            if (!closed && connectionGeneration === generation) {
                val resolvedManager = runCatching { car?.javaClass?.getMethod("getCarManager", String::class.java)
                    ?.invoke(car, "property") }.getOrNull()
                managerPublication.publish(resolvedManager)
                failure = if (resolvedManager == null) "car property manager unavailable" else null
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = synchronized(stateLock) {
            if (!closed && connectionGeneration === generation) {
                managerPublication.publish(null)
                failure = "car service disconnected"
            }
        }
        override fun onBindingDied(name: ComponentName) = synchronized(stateLock) {
            if (!closed && connectionGeneration === generation) {
                managerPublication.publish(null)
                failure = "car service binding died"
                connectionGeneration = null
                val retiredCar = car
                car = null
                if (retiredCar != null) pendingDisconnect = retiredCar
            }
        }
    }
    override fun connect(): Boolean = synchronized(stateLock) {
        if (closed) return false
        pendingDisconnect?.let { pending ->
            try {
                disconnectAndroidCar(pending)
                if (pendingDisconnect === pending) pendingDisconnect = null
            } catch (error: Exception) {
                failure = "previous car service cleanup failed"
                Log.i("VehicleData", "Previous Android Car service cleanup failed", error)
                return false
            }
        }
        if (car != null) return true
        return try {
            val type = Class.forName("android.car.Car")
            val generation = Any()
            connectionGeneration = generation
            car = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, context, newConnection(generation))
            failure = "waiting for car service"
            type.getMethod("connect").invoke(car)
            true // asynchronous binding; reads return null until connected
        } catch (error: Exception) {
            failure = if (error is ClassNotFoundException) "android.car shared library unavailable" else "car service connection failed"
            val oldCar = car
            car = null
            connectionGeneration = null
            managerPublication.publish(null)
            if (oldCar != null) {
                try {
                    disconnectAndroidCar(oldCar)
                } catch (cleanupFailure: Exception) {
                    pendingDisconnect = oldCar
                    error.addSuppressed(cleanupFailure)
                }
            }
            Log.i("VehicleData", "Android Car property service unavailable", error)
            false
        }
    }
    override val isConnected: Boolean get() = !closed && managerPublication.snapshot() != null
    override val availabilityReason: String? get() = failure
    override val sourceIdentity: Any? get() = if (closed) null else managerPublication.snapshot()?.generation
    override fun read(propertyId: Int): CarPropertyValue? = read(propertyId, 0)

    override fun readBoolean(propertyId: Int): CarPropertyBooleanValue? {
        val target = managerPublication.snapshot()?.manager ?: return null
        return readAndroidCarBooleanProperty(target, propertyId)
    }

    override fun readVideoParkNumber(propertyId: Int): CarPropertyValue? {
        val target = managerPublication.snapshot()?.manager ?: return null
        return readAndroidCarVideoParkNumber(target, propertyId)
    }

    override fun read(propertyId: Int, areaId: Int): CarPropertyValue? {
        val target = managerPublication.snapshot()?.manager ?: return null
        return readAndroidCarProperty(target, propertyId, areaId)
    }
    override fun close() {
        val owners = synchronized(stateLock) {
            closed = true
            failure = "car data source closed"
            connectionGeneration = null
            managerPublication.publish(null)
            buildList {
                pendingDisconnect?.let(::add)
                car?.takeIf { it !== pendingDisconnect }?.let(::add)
            }
        }
        var closeFailure: Exception? = null
        owners.forEach { owner ->
            try {
                disconnectAndroidCar(owner)
                synchronized(stateLock) {
                    if (pendingDisconnect === owner) pendingDisconnect = null
                    if (car === owner) car = null
                }
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (pendingDisconnect == null) pendingDisconnect = owner
                }
                if (closeFailure == null) closeFailure = error
                else if (closeFailure !== error && closeFailure!!.suppressed.size < 8) {
                    closeFailure!!.addSuppressed(error)
                }
            }
        }
        closeFailure?.let { throw it }
    }
}

/** Publish manager and source token together so a rebind cannot pair the new manager with the old token. */
internal class CarPropertyManagerPublication(
    private val newGeneration: () -> Any = { Any() },
) {
    internal class Snapshot(val manager: Any, val generation: Any)

    @Volatile private var current: Snapshot? = null

    fun snapshot(): Snapshot? = current

    fun publish(manager: Any?) {
        val next = manager?.let { Snapshot(it, newGeneration()) }
        current = next
    }
}

/** Android's global PARKING_BRAKE_ON; a numeric read must never stand in for this Boolean signal. */
internal const val ANDROID_PARKING_BRAKE_ON = 0x11200402

/**
 * Fail-closed reflection seam for the safety-relevant parking brake. A value is usable only when
 * Android Car identifies the exact property and global area, reports AVAILABLE, supplies an actual
 * Boolean, and timestamps the source event in elapsed-realtime nanoseconds. Old managers lacking
 * any of these accessors cannot silently turn into a positive parking-brake reading.
 */
internal fun readAndroidCarBooleanProperty(target: Any, propertyId: Int): CarPropertyBooleanValue? {
    if (propertyId != ANDROID_PARKING_BRAKE_ON) return null
    val result = try {
        target.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(target, propertyId, 0)
    } catch (_: NoSuchMethodException) {
        return null
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: error)
    } ?: return null
    return try {
        if (result.javaClass.getMethod("getPropertyId").invoke(result) != ANDROID_PARKING_BRAKE_ON ||
            result.javaClass.getMethod("getAreaId").invoke(result) != 0 ||
            result.javaClass.getMethod("getStatus").invoke(result) != 0) return null
        val value = result.javaClass.getMethod("getValue").invoke(result) as? Boolean ?: return null
        val eventNanos = result.javaClass.getMethod("getTimestamp").invoke(result) as? Long ?: return null
        if (eventNanos <= 0L) return null
        CarPropertyBooleanValue(value, eventNanos / 1_000_000L)
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: error)
    } catch (_: ReflectiveOperationException) {
        null
    }
}

/** Exact global speed/selected-gear reader; generic numeric reads are not video safety evidence. */
internal fun readAndroidCarVideoParkNumber(target: Any, propertyId: Int): CarPropertyValue? {
    if (propertyId != VehicleTelemetrySnapshot.STANDARD_SPEED &&
        propertyId != VehicleTelemetrySnapshot.STANDARD_GEAR_SELECTION) return null
    val result = try {
        target.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(target, propertyId, 0)
    } catch (_: NoSuchMethodException) {
        return null
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: error)
    } ?: return null
    return try {
        if (result.javaClass.getMethod("getPropertyId").invoke(result) != propertyId ||
            result.javaClass.getMethod("getAreaId").invoke(result) != 0 ||
            result.javaClass.getMethod("getStatus").invoke(result) != 0) return null
        val raw = result.javaClass.getMethod("getValue").invoke(result)
        val value = when (propertyId) {
            VehicleTelemetrySnapshot.STANDARD_SPEED -> raw as? Float
            else -> raw as? Int
        } ?: return null
        val eventNanos = result.javaClass.getMethod("getTimestamp").invoke(result) as? Long ?: return null
        if (eventNanos <= 0L) return null
        CarPropertyValue(value, eventNanos / 1_000_000L)
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: error)
    } catch (_: ReflectiveOperationException) {
        null
    }
}

/** Numeric reads accept only an available value for the exact requested property and area. */
internal fun readAndroidCarProperty(target: Any, propertyId: Int, areaId: Int): CarPropertyValue? {
    val result = try {
        target.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(target, propertyId, areaId)
    } catch (_: NoSuchMethodException) {
        return null
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: error)
    } catch (_: ReflectiveOperationException) {
        return null
    } ?: return null
    return try {
        if (result.javaClass.getMethod("getPropertyId").invoke(result) != propertyId ||
            result.javaClass.getMethod("getAreaId").invoke(result) != areaId ||
            result.javaClass.getMethod("getStatus").invoke(result) != 0) return null
        val value = result.javaClass.getMethod("getValue").invoke(result) as? Number ?: return null
        val eventNanos = result.javaClass.getMethod("getTimestamp").invoke(result) as? Long
        CarPropertyValue(value, eventNanos?.takeIf { it > 0 }?.div(1_000_000))
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: error)
    } catch (_: ReflectiveOperationException) {
        null
    }
}

internal fun disconnectAndroidCar(owner: Any) {
    try {
        owner.javaClass.getMethod("disconnect").invoke(owner)
    } catch (error: java.lang.reflect.InvocationTargetException) {
        when (val target = error.targetException) {
            is Error -> throw target
            is Exception -> throw target
            else -> throw error
        }
    }
}
