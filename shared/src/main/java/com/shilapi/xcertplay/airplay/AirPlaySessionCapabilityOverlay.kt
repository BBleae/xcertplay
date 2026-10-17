package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandlerFactory
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import java.io.Closeable

/**
 * Opens one capability owner for one authenticated AirPlay control connection.
 *
 * The caller must invoke this only inside [AirPlaySessionRuntimeBinding.withResolvedAdmission],
 * after an encrypted Pair Verify-complete `/info` or SETUP request. Merely accepting TCP must
 * never reserve a one-shot UpdateData quota or publish an Ultra sidecar.
 * In particular, a one-shot RCS reservation and the configuration that advertises it must be
 * produced together from the exact admitted snapshot, never cached on the attachment or runtime.
 */
fun interface AirPlaySessionCapabilityOverlayFactory {
    fun open(snapshot: AirPlaySessionRuntimeBinding.ResolvedSnapshot): AirPlaySessionCapabilityOverlay
}

/**
 * Exact per-connection configuration, optional additional RCS registration, and its cleanup owner.
 *
 * A factory must return a fresh instance for every TCP connection. The registration is additive;
 * duplicate client types in the base Ultra runtime and this overlay are rejected, not overridden.
 * Closing this object retires the owner only after all media for its AirPlay session has retired.
 */
class AirPlaySessionCapabilityOverlay(
    val config: AirPlayConfig,
    val extraRcsFactory: RcsDataStreamHandlerFactory? = null,
    private val owner: Closeable = Closeable {},
) : Closeable {
    private var claimed = false
    private var closed = false

    @Synchronized
    internal fun claimForSession(): Boolean {
        if (claimed || closed) return false
        claimed = true
        return true
    }

    @Synchronized
    override fun close() {
        if (closed) return
        owner.close()
        closed = true
    }
}

/** Exact-type composition; an overlay must never silently replace the authorized base handler. */
internal fun composeSessionRcsHandlerFactories(
    base: RcsDataStreamHandlerFactory?,
    extra: RcsDataStreamHandlerFactory?,
): RcsDataStreamHandlerFactory? = when {
    base == null -> extra
    extra == null -> base
    else -> RcsDataStreamHandlerFactory { clientType: RcsClientType ->
        val baseDescriptor = base.descriptor(clientType)
        val extraDescriptor = extra.descriptor(clientType)
        require(baseDescriptor == null || extraDescriptor == null) {
            "Multiple RCS factories registered ${clientType.name}"
        }
        (baseDescriptor ?: extraDescriptor)?.also { descriptor ->
            require(descriptor.clientType == clientType) {
                "RCS factory returned a descriptor for another client type"
            }
        }
    }
}
