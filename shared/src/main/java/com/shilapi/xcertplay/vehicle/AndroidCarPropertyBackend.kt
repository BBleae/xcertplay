package com.shilapi.xcertplay.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/** No android.car class is linked on phones, and no setProperty method is used. */
class AndroidCarPropertyBackend(context: Context) : ReadOnlyCarPropertyBackend {
    private val context = context.applicationContext
    @Volatile private var car: Any? = null
    @Volatile private var manager: Any? = null
    @Volatile private var closed = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            manager = runCatching { car?.javaClass?.getMethod("getCarManager", String::class.java)
                ?.invoke(car, "property") }.getOrNull()
        }
        override fun onServiceDisconnected(name: ComponentName) { manager = null }
    }
    override fun connect(): Boolean {
        if (closed) return false
        return try {
            val type = Class.forName("android.car.Car")
            car = type.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                .invoke(null, context, connection)
            type.getMethod("connect").invoke(car)
            true // asynchronous binding; reads return null until connected
        } catch (error: Exception) {
            Log.i("VehicleData", "Android Car property service unavailable", error)
            false
        }
    }
    override fun read(propertyId: Int): Number? {
        val target = manager ?: return null
        val result = target.javaClass.getMethod("getProperty", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).invoke(target, propertyId, 0) ?: return null
        val status = result.javaClass.getMethod("getStatus").invoke(result) as? Int ?: return null
        if (status != 0) return null
        return result.javaClass.getMethod("getValue").invoke(result) as? Number
    }
    override fun close() {
        closed = true
        manager = null
        val oldCar = car
        car = null
        runCatching { oldCar?.javaClass?.getMethod("disconnect")?.invoke(oldCar) }
    }
}
