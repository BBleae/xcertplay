package com.shilapi.xcertplay.airplay

import java.io.Closeable

/**
 * A future MatchPoint transport owner's claim on one verified AirPlay control-session epoch.
 *
 * This object contains no key bytes. [withPskFor] lends a temporary 16-byte copy only while the
 * same session is alive and the original Pair Verify generation remains verified. The copy is
 * wiped before the call returns (including on failure). A transport that retains key material
 * must use [openOwnedTransportFor], which registers its exact Closeable owner with the AirPlay
 * session before the temporary borrow can finish. [withPskFor] is for transient operations only.
 */
internal class AirPlayPairTlsPskLease internal constructor(
    internal val owner: AirPlaySession,
    internal val controlEpoch: Any,
    internal val pairVerifyGeneration: Any,
) : Closeable {
    @Volatile internal var retired = false
        private set

    fun <T> withPskFor(session: AirPlaySession, consume: (ByteArray) -> T): T? =
        owner.withPairTlsPsk(this, session, consume)

    fun <T : Closeable> openOwnedTransportFor(
        session: AirPlaySession,
        construct: (ByteArray) -> T,
    ): T? = owner.openPairTlsPskTransport(this, session, construct)

    override fun close() = owner.releasePairTlsPsk(this)

    internal fun retire() {
        retired = true
    }

    override fun toString(): String = "AirPlayPairTlsPskLease(session=<redacted>, psk=<redacted>)"
}
