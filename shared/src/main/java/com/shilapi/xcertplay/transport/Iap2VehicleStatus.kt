package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlin.math.roundToInt

/** Normalized SI/user-facing units only. Null means unknown, never an invented zero. */
data class VehicleStatusSnapshot(
    val batteryPercent: Double? = null,
    val electricRangeKm: Double? = null,
)
interface VehicleStatusProvider : AutoCloseable {
    fun snapshot(): VehicleStatusSnapshot?
    fun awaitSnapshot(timeoutMillis: Long): VehicleStatusSnapshot? = snapshot()
    override fun close() = Unit
}

/** Partial EV status, not a claim of full EV Routing or vehicle control support. */
object Iap2VehicleStatus {
    const val START = 0xa100
    const val UPDATE = 0xa101
    const val STOP = 0xa102
    fun capabilities(status: VehicleStatusSnapshot?): Set<Int> = buildSet {
        if (status?.batteryPercent?.let { it.isFinite() && it in 0.0..100.0 } == true) add(21)
        if (status?.electricRangeKm?.let { it.isFinite() && it in 0.0..65535.0 } == true) add(11)
    }
    fun update(status: VehicleStatusSnapshot, advertised: Set<Int>, requested: Set<Int>): Iap2Frame? {
        val range = status.electricRangeKm?.takeIf {
            11 in advertised && 11 in requested && it.isFinite() && it in 0.0..65535.0
        }
        // A100 has no selector for charge fields 21–28. They belong to the subscribed status
        // plane once ElectricChargeInfo (Identification flag 21) was declared; see DiPlay evidence.
        val percent = status.batteryPercent?.takeIf {
            21 in advertised && it.isFinite() && it in 0.0..100.0
        }
        if (range == null && percent == null) return null
        return Iap2Messages.buildRaw(UPDATE) {
            range?.let { u16(11, it.toInt()) }
            percent?.let { u32(24, (it * 1_000).roundToInt()) }
        }
    }
}

/** Each control link owns its subscription, including its identification capability snapshot. */
class Iap2VehicleStatusReporter(
    private val provider: VehicleStatusProvider?,
    val advertised: Set<Int>,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var active = false
    private var requested = emptySet<Int>()
    private var nextSend = 0L
    fun handle(frame: Iap2Frame, send: (Iap2Frame) -> Unit): Boolean {
        when (frame.messageId) {
            Iap2VehicleStatus.START -> {
                requested = try {
                    Iap2BodyReader.of(frame).list().also { fields ->
                        require(fields.none { it.id in setOf(3, 4, 5, 6, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20) && it.payload.isNotEmpty() })
                    }.filter { it.payload.isEmpty() }.map { it.id }.toSet()
                } catch (_: IllegalArgumentException) { stop(); return true }
                  catch (_: com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException) { stop(); return true }
                active = provider != null && advertised.isNotEmpty()
                nextSend = 0
                tick(send)
            }
            Iap2VehicleStatus.STOP -> stop()
            else -> return false
        }
        return true
    }
    fun tick(send: (Iap2Frame) -> Unit) {
        if (!active || now() < nextSend) return
        // Backoff also applies to unavailable samples, preventing a tight retry loop.
        nextSend = now() + 30_000
        val sample = provider?.snapshot() ?: return
        Iap2VehicleStatus.update(sample, advertised, requested)?.let(send)
    }
    fun pollTimeout(timeoutMillis: Long): Long = if (active) minOf(timeoutMillis, 30_000) else timeoutMillis
    fun stop() { active = false; requested = emptySet(); nextSend = 0 }
}
