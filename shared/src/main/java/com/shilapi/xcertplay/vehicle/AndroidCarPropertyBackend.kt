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
    @Volatile private var manager: Any? = null
    @Volatile private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = synchronized(stateLock) {
            if (!closed) {
                manager = runCatching { car?.javaClass?.getMethod("getCarManager", String::class.java)
                    ?.invoke(car, "property") }.getOrNull()
                failure = if (manager == null) "car property manager unavailable" else null
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = synchronized(stateLock) { manager = null; failure = "car service disconnected" }
    }
    override fun connect(): Boolean = synchronized(stateLock) {
        if (closed) return false
        if (car != null) return true
        return try {
            val type = Class.forName("android.car.Car")
            car = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, context, connection)
            failure = "waiting for car service"
            type.getMethod("connect").invoke(car)
            true // asynchronous binding; reads return null until connected
        } catch (error: Exception) {
            failure = if (error is ClassNotFoundException) "android.car shared library unavailable" else "car service connection failed"
            val oldCar = car
            car = null
            manager = null
            runCatching { oldCar?.javaClass?.getMethod("disconnect")?.invoke(oldCar) }
            Log.i("VehicleData", "Android Car property service unavailable", error)
            false
        }
    }
    override val isConnected: Boolean get() = !closed && manager != null
    override val availabilityReason: String? get() = failure
    override fun read(propertyId: Int): CarPropertyValue? {
        val target = manager ?: return null
        val result = try {
            target.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(target, propertyId, 0)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw (error.targetException as? Exception ?: error)
        } ?: return null
        val status = result.javaClass.getMethod("getStatus").invoke(result) as? Int ?: return null
        if (status != 0) return null
        val value = result.javaClass.getMethod("getValue").invoke(result) as? Number ?: return null
        val eventNanos = result.javaClass.getMethod("getTimestamp").invoke(result) as? Long
        return CarPropertyValue(value, eventNanos?.takeIf { it > 0 }?.div(1_000_000))
    }
    override fun close() = synchronized(stateLock) {
        closed = true
        failure = "car data source closed"
        manager = null
        val oldCar = car
        car = null
        runCatching { oldCar?.javaClass?.getMethod("disconnect")?.invoke(oldCar) }
        Unit
    }
}
