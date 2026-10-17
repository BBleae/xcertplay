package com.shilapi.xcertplay.bench

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only gate for the v3 signed, real Android Car speed projection. */
internal class BenchCarSpeedPermissionGate(private val hasCurrentGrant: () -> Boolean) {
    fun allows(compiled: BenchCafProfileCompiler.Compiled): Boolean =
        !compiled.requiresCarSpeedPermission || runCatching(hasCurrentGrant).getOrDefault(false)

    internal enum class Status {
        NOT_AUTOMOTIVE,
        CAR_API_UNAVAILABLE,
        PERMISSION_UNAVAILABLE,
        REQUESTABLE,
        GRANTED,
    }

    companion object {
        const val PERMISSION = "android.car.permission.CAR_SPEED"

        /** A ZIP can never request permission. Only this live OS state is trusted. */
        fun forAndroid(context: Context): BenchCarSpeedPermissionGate =
            BenchCarSpeedPermissionGate { status(context) == Status.GRANTED }

        fun unavailable(): BenchCarSpeedPermissionGate = BenchCarSpeedPermissionGate { false }

        fun status(context: Context): Status = runCatching {
            val app = context.applicationContext
            val pm = app.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                return@runCatching Status.NOT_AUTOMOTIVE
            }
            if (runCatching { Class.forName("android.car.Car") }.isFailure) {
                return@runCatching Status.CAR_API_UNAVAILABLE
            }
            val permission = pm.getPermissionInfo(PERMISSION, 0)
            if (permission.protection != PermissionInfo.PROTECTION_DANGEROUS) {
                return@runCatching Status.PERMISSION_UNAVAILABLE
            }
            if (app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                Status.GRANTED
            } else Status.REQUESTABLE
        }.getOrDefault(Status.PERMISSION_UNAVAILABLE)
    }
}

/** One Debug v3 bridge owns this bounded observer until its CAF resources are closed. */
internal class BenchCarSpeedPermissionMonitor(
    private val observeCurrent: () -> Boolean,
    intervalMillis: Long = 100L,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "debug-caf-speed-permission").apply { isDaemon = true }
    }
    @Volatile private var poll: ScheduledFuture<*>? = null

    init {
        require(intervalMillis in 20L..200L) { "Speed permission monitor period is invalid" }
        try {
            poll = worker.scheduleWithFixedDelay({
                if (!closed.get() && !runCatching(observeCurrent).getOrDefault(false)) close()
            }, 0L, intervalMillis, TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            worker.shutdownNow()
            throw failure
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            poll?.cancel(false)
            worker.shutdownNow()
        }
    }
}
