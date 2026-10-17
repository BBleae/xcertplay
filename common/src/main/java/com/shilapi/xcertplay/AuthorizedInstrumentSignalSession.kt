package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.rcs.caf.CafConfigTree
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateListener
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateReadSource
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateResult
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateSubscription
import com.shilapi.xcertplay.vehicle.ultra.VehicleStateUpdate
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundle
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleListener
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleSpec
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalBundleState
import com.shilapi.xcertplay.vehicle.ultra.instrument.InstrumentSignalUnavailableReason
import com.shilapi.xcertplay.vehicle.ultra.theme.Display
import java.io.Closeable

/**
 * One signed Profile slot, one real read-only CAF repository, and one current AA05/physical child.
 * This is a data-source lease only: it neither makes a Gauge renderable nor advertises readiness.
 */
internal class AuthorizedInstrumentSignalSession private constructor(
    val spec: InstrumentSignalBundleSpec,
    display: Display,
    private val source: VehicleStateReadSource,
    private val currentAuthority: () -> Boolean,
    private val onClosed: (AuthorizedInstrumentSignalSession) -> Unit,
) : Closeable {
    @Volatile private var closeRequested = false
    private val closeLock = Any()
    private var fullyClosed = false

    private val guardedSource = object : VehicleStateReadSource {
        override val configTree: CafConfigTree get() = source.configTree
        override val isCurrent: Boolean get() = this@AuthorizedInstrumentSignalSession.isLive

        override fun read(iids: Set<Long>): VehicleStateResult {
            check(isCurrent) { "Authorized instrument source is not current" }
            val result = source.read(iids)
            check(isCurrent) { "Authorized instrument source retired during read" }
            return result
        }

        override fun subscribe(
            iids: Set<Long>,
            listener: VehicleStateListener,
        ): VehicleStateSubscription {
            check(isCurrent) { "Authorized instrument source is not current" }
            val subscription = source.subscribe(iids, object : VehicleStateListener {
                override fun onUpdate(update: VehicleStateUpdate) {
                    if (isCurrent) listener.onUpdate(update)
                    else listener.onUnavailable(iids)
                }

                override fun onUnavailable(iids: Set<Long>) =
                    listener.onUnavailable(iids)
            })
            if (!isCurrent) {
                subscription.close()
                throw IllegalStateException("Authorized instrument source retired during subscribe")
            }
            return subscription
        }
    }

    private val bundle = InstrumentSignalBundle.bind(display, spec, guardedSource)

    val isLive: Boolean
        get() = !closeRequested && runCatching {
            source.isCurrent && currentAuthority() && !closeRequested
        }.getOrDefault(false)

    fun snapshot(): InstrumentSignalBundleState {
        if (closeRequested) return unavailable(InstrumentSignalUnavailableReason.CLOSED)
        if (!isLive) return unavailable(InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE)
        val candidate = bundle.snapshot()
        return if (isLive) candidate else unavailable(
            InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE,
        )
    }

    fun refreshNow(): InstrumentSignalBundleState {
        if (!isLive) return snapshot()
        bundle.refreshNow()
        return snapshot()
    }

    internal fun start(listener: InstrumentSignalBundleListener) {
        bundle.subscribe { candidate ->
            val published = if (closeRequested) unavailable(InstrumentSignalUnavailableReason.CLOSED)
            else if (isLive) candidate else unavailable(
                InstrumentSignalUnavailableReason.REPOSITORY_UNAVAILABLE,
            )
            listener.onState(published)
        }
    }

    override fun close() {
        closeRequested = true
        synchronized(closeLock) {
            if (fullyClosed) return
            bundle.close()
            fullyClosed = true
        }
        onClosed(this)
    }

    internal companion object {
        fun open(
            spec: InstrumentSignalBundleSpec,
            display: Display,
            source: VehicleStateReadSource,
            currentAuthority: () -> Boolean,
            onClosed: (AuthorizedInstrumentSignalSession) -> Unit,
        ): AuthorizedInstrumentSignalSession = AuthorizedInstrumentSignalSession(
            spec, display, source, currentAuthority, onClosed,
        )

        private fun unavailable(reason: InstrumentSignalUnavailableReason) =
            InstrumentSignalBundleState.Unavailable(reason)
    }
}
