package com.shilapi.xcertplay.vehicle

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Binder queries run on one background worker; the iAP2 loop reads immutable cached snapshots. */
class CarPropertyVehicleProvider(
    private val store: CarPropertyDataStore,
    private val config: VehicleDataConfig,
    private val now: () -> Long,
) : VehicleStatusProvider {
    private val sampleReady = Object()
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vehicle-read-only").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var started = false
    @Synchronized fun start(): Boolean {
        if (closed) return false
        if (started) return true
        if (!store.connect()) return false
        started = true
        val ids = emptyList<Int>()
        if (ids.isNotEmpty()) worker.scheduleWithFixedDelay({
            if (!closed) {
                store.refresh(ids)
                synchronized(sampleReady) { sampleReady.notifyAll() }
            }
        }, 0, 1, TimeUnit.SECONDS)
        return true
    }
    override fun awaitSnapshot(timeoutMillis: Long): VehicleStatusSnapshot? {
        if (!started || closed || !config.isEnabled(VehicleDataFeature.EV_STATUS)) return snapshot()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(sampleReady) {
            while (!closed && snapshot() == null) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                sampleReady.wait(remaining)
            }
        }
        return snapshot()
    }
    fun telemetry() = store.snapshot()
    override fun snapshot(): VehicleStatusSnapshot? {
        if (closed || !config.isEnabled(VehicleDataFeature.EV_STATUS)) return null
        val sample = store.snapshot()
        val percent = sample.batteryPercent(now())
        val range = sample.rangeKm(now())
        return if (percent == null && range == null) null else VehicleStatusSnapshot(percent, range)
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        synchronized(sampleReady) { sampleReady.notifyAll() }
        worker.shutdownNow()
        store.close()
    }
}
