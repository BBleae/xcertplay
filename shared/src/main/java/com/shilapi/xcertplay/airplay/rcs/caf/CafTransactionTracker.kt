package com.shilapi.xcertplay.airplay.rcs.caf

import java.util.concurrent.atomic.AtomicLong

data class CafPendingTransaction(
    val pluginId: Long,
    val transactionId: Long,
    val requestCommand: CafCommand,
    val expectedResponses: Set<CafCommand>,
    val startedAtNanos: Long,
    val deadlineNanos: Long,
)

/** Bounded, expiring transaction state owned by exactly one CAF channel. */
class CafTransactionTracker(
    firstTransactionId: Long = 1L,
    private val maxPending: Int = 256,
    private val timeoutMillis: Long = 15_000L,
    private val nowNanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val nextTransactionId = AtomicLong(firstTransactionId)
    private val pending = LinkedHashMap<Long, CafPendingTransaction>()
    private var closed = false

    init {
        require(firstTransactionId > 0) { "CAF transaction IDs must start above zero" }
        require(maxPending > 0) { "maxPending must be positive" }
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "timeoutMillis is out of range" }
    }

    @Synchronized
    fun begin(
        pluginId: Long,
        requestCommand: CafCommand,
        expectedResponses: Set<CafCommand>,
    ): CafPendingTransaction {
        check(!closed) { "CAF transaction tracker is closed" }
        require(requestCommand in CafCommand.REQUESTS) {
            "${requestCommand.wireName} is not a CAF request"
        }
        require(expectedResponses.isNotEmpty() && expectedResponses.all { it.isResponse }) {
            "${requestCommand.wireName} has invalid expected responses"
        }
        expireLocked(nowNanos())
        if (pending.size >= maxPending) {
            throw CafProtocolException("CAF pending transaction limit $maxPending reached")
        }
        repeat(maxPending + 1) {
            val transactionId = nextTransactionId.getAndUpdate { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            }
            if (transactionId !in pending) {
                val started = nowNanos()
                val timeoutNanos = timeoutMillis * NANOS_PER_MILLISECOND
                val deadline = if (started > Long.MAX_VALUE - timeoutNanos) {
                    Long.MAX_VALUE
                } else {
                    started + timeoutNanos
                }
                return CafPendingTransaction(
                    pluginId = pluginId,
                    transactionId = transactionId,
                    requestCommand = requestCommand,
                    expectedResponses = expectedResponses.toSet(),
                    startedAtNanos = started,
                    deadlineNanos = deadline,
                ).also { pending[transactionId] = it }
            }
        }
        throw CafProtocolException("Unable to allocate a CAF transaction ID")
    }

    @Synchronized
    fun accept(reader: CafMessageReader): CafPendingTransaction? {
        if (closed) throw CafProtocolException("CAF transaction tracker is closed")
        val transactionId = reader.transactionId ?: return null
        if (!reader.command.isResponse) return null
        expireLocked(nowNanos())
        val transaction = pending[transactionId]
            ?: throw CafProtocolException(
                "CAF response ${reader.command.wireName} has unknown transactionID " +
                    CafUInt64.toString(transactionId),
            )
        if (reader.pluginId != transaction.pluginId) {
            throw CafProtocolException(
                "CAF transaction ${CafUInt64.toString(transactionId)} belongs to plugin " +
                    "${CafUInt64.toString(transaction.pluginId)}, not " +
                    CafUInt64.toString(reader.pluginId),
            )
        }
        if (reader.command !in transaction.expectedResponses) {
            throw CafProtocolException(
                "CAF transaction ${CafUInt64.toString(transactionId)} expected " +
                    transaction.expectedResponses.joinToString { it.wireName } +
                    ", got ${reader.command.wireName}",
            )
        }
        pending.remove(transactionId)
        return transaction
    }

    @Synchronized
    fun expire(): List<CafPendingTransaction> {
        if (closed) return emptyList()
        return expireLocked(nowNanos())
    }

    @Synchronized
    fun cancel(transaction: CafPendingTransaction): Boolean =
        pending.remove(transaction.transactionId, transaction)

    @Synchronized
    fun cancelAll(): List<CafPendingTransaction> {
        val cancelled = pending.values.toList()
        pending.clear()
        return cancelled
    }

    @Synchronized
    fun size(): Int = pending.size

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        pending.clear()
    }

    private fun expireLocked(now: Long): List<CafPendingTransaction> {
        val expired = pending.values.filter { now - it.deadlineNanos >= 0 }
        expired.forEach { pending.remove(it.transactionId) }
        return expired
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_TIMEOUT_MILLIS = 5 * 60 * 1_000L
    }
}
