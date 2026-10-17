package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.MfiAuthenticationAttempt
import com.shilapi.xcertplay.mfi.MfiAuthenticationHandoff
import com.shilapi.xcertplay.mfi.MfiAuthenticationIdentity
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceipt
import com.shilapi.xcertplay.mfi.MfiAuthenticationReceiptAuthority
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiDeviceScanner
import com.shilapi.xcertplay.mfi.SessionBoundMfiAuthenticator
import com.shilapi.xcertplay.transport.I2cTransport
import java.io.Closeable
import java.io.IOException

/** Signals the normal, retryable state where the MFi address probe found no chip. */
internal class MfiCoprocessorNotFoundException(
    message: String = "No MFi authentication coprocessor responded to the device probe",
) : IOException(message)

/**
 * Construction failed and at least one exact cleanup owner could not be released. Calling [close]
 * retries only the owners whose preceding close failed; successful owners are never closed twice.
 */
class MfiSessionConstructionCleanupException internal constructor(
    cause: Throwable,
    private var receiptAuthorityOwner: Closeable?,
    private var sessionAuthenticatorOwner: Closeable?,
    private var backingCloseableOwner: Closeable?,
    cleanupFailures: List<Throwable>,
) : IllegalStateException(
    "MFi session construction failed and cleanup remains retryable",
    cause,
), Closeable {
    private var closeInProgress = false

    init {
        cleanupFailures.forEach(::addSuppressed)
    }

    @Synchronized
    override fun close() {
        if (closeInProgress) return
        closeInProgress = true
        val failures = mutableListOf<Throwable>()
        try {
            receiptAuthorityOwner?.let { owner ->
                try {
                    owner.close()
                    receiptAuthorityOwner = null
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            sessionAuthenticatorOwner?.let { owner ->
                try {
                    owner.close()
                    sessionAuthenticatorOwner = null
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            backingCloseableOwner?.let { owner ->
                try {
                    owner.close()
                    backingCloseableOwner = null
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
        } finally {
            closeInProgress = false
        }
        throwCloseFailures(failures)
    }
}

/** An opened MFi coprocessor client plus the handle that releases its backing transport. */
class MfiSession private constructor(
    private val sessionAuthenticator: SessionBoundMfiAuthenticator,
    val authenticationIdentity: MfiAuthenticationIdentity,
    private val receiptAuthority: MfiAuthenticationReceiptAuthority,
    private val closeable: Closeable?,
) : Closeable {
    private var closeInProgress = false
    private var receiptAuthorityOwned = true
    private var sessionAuthenticatorOwned = true
    private var backingCloseableOwned = closeable != null

    val client: MfiAuthenticator
        get() = sessionAuthenticator

    fun beginAuthenticationAttempt(
        controlSession: Iap2Session,
        controlGeneration: Long,
    ): MfiAuthenticationAttempt = receiptAuthority.begin(controlSession, controlGeneration)

    /**
     * Starts an AA05 successor on another control channel without revoking [predecessorReceipt].
     * The predecessor must be the exact live receipt issued by this [MfiSession].
     */
    fun beginAuthenticationHandoff(
        predecessorReceipt: MfiAuthenticationReceipt,
        controlSession: Iap2Session,
        controlGeneration: Long,
    ): MfiAuthenticationHandoff = receiptAuthority.beginHandoff(
        predecessorReceipt = predecessorReceipt,
        controlSession = controlSession,
        controlGeneration = controlGeneration,
    )

    fun invalidateAuthenticationAttempts(controlGeneration: Long) {
        receiptAuthority.invalidate(controlGeneration)
    }

    @Synchronized
    override fun close() {
        if (closeInProgress) return
        closeInProgress = true
        val failures = mutableListOf<Throwable>()
        try {
            if (receiptAuthorityOwned) {
                try {
                    receiptAuthority.close()
                    receiptAuthorityOwned = false
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            if (sessionAuthenticatorOwned) {
                try {
                    sessionAuthenticator.close()
                    sessionAuthenticatorOwned = false
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
            if (backingCloseableOwned) {
                try {
                    checkNotNull(closeable).close()
                    backingCloseableOwned = false
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
            }
        } finally {
            closeInProgress = false
        }
        if (failures.isNotEmpty()) {
            throwCloseFailures(failures)
        }
    }

    companion object {
        /** Captures the exact live certificate identity before any Ultra activation can run. */
        fun open(client: MfiAuthenticator, closeable: Closeable?): MfiSession {
            var sessionAuthenticator: SessionBoundMfiAuthenticator? = null
            var receiptAuthority: MfiAuthenticationReceiptAuthority? = null
            try {
                sessionAuthenticator = SessionBoundMfiAuthenticator.capture(client)
                val identity = MfiAuthenticationIdentity.capture(sessionAuthenticator)
                receiptAuthority = MfiAuthenticationReceiptAuthority.create(identity)
                receiptAuthority.bindAuthenticationSource(sessionAuthenticator)
                return MfiSession(
                    sessionAuthenticator = sessionAuthenticator,
                    authenticationIdentity = identity,
                    receiptAuthority = receiptAuthority,
                    closeable = closeable,
                )
            } catch (failure: Throwable) {
                var receiptAuthorityOwner: Closeable? = receiptAuthority
                var sessionAuthenticatorOwner: Closeable? = sessionAuthenticator
                var backingCloseableOwner = closeable
                val cleanupFailures = mutableListOf<Throwable>()
                if (receiptAuthorityOwner != null) {
                    try {
                        receiptAuthorityOwner.close()
                        receiptAuthorityOwner = null
                    } catch (cleanupFailure: Throwable) {
                        cleanupFailures.add(cleanupFailure)
                    }
                }
                if (sessionAuthenticatorOwner != null) {
                    try {
                        sessionAuthenticatorOwner.close()
                        sessionAuthenticatorOwner = null
                    } catch (cleanupFailure: Throwable) {
                        cleanupFailures.add(cleanupFailure)
                    }
                }
                if (backingCloseableOwner != null) {
                    try {
                        backingCloseableOwner.close()
                        backingCloseableOwner = null
                    } catch (cleanupFailure: Throwable) {
                        cleanupFailures.add(cleanupFailure)
                    }
                }
                if (cleanupFailures.isNotEmpty()) {
                    val cleanupOwner = MfiSessionConstructionCleanupException(
                        cause = failure,
                        receiptAuthorityOwner = receiptAuthorityOwner,
                        sessionAuthenticatorOwner = sessionAuthenticatorOwner,
                        backingCloseableOwner = backingCloseableOwner,
                        cleanupFailures = cleanupFailures,
                    )
                    if (failure is Error) {
                        failure.addSuppressed(cleanupOwner)
                        throw failure
                    }
                    throw cleanupOwner
                }
                throw failure
            }
        }
    }
}

private fun throwCloseFailures(failures: List<Throwable>) {
    if (failures.isEmpty()) return
    val first = failures.first()
    failures.drop(1).forEach(first::addSuppressed)
    throw first
}

/** Runs the documented address probe and wraps the first responding MFi coprocessor. */
object MfiRuntime {
    fun scan(transport: I2cTransport): MfiAuthenticationClient {
        val discovery = MfiDeviceScanner(
            transport = transport,
            probeTimeoutMillis = MFI_STARTUP_PROBE_TIMEOUT_MILLIS,
        ).scan()
        val chip = discovery.chip
            ?: throw MfiCoprocessorNotFoundException(
                "No MFi authentication coprocessor responded: " +
                    discovery.failures.joinToString { "0x${it.address7Bit.toString(16)}=${it.error}" },
            )
        return MfiAuthenticationClient(transport, chip.address7Bit)
    }

    private const val MFI_STARTUP_PROBE_TIMEOUT_MILLIS = 15_000L
}
