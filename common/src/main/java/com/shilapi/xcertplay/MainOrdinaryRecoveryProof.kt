package com.shilapi.xcertplay

/** The process queue being empty is only a barrier; this exact retired owner authorizes rotation. */
internal fun provenMainOrdinaryRetirement(
    ticket: HostTeardownTicket,
    controller: Any?,
    sink: Any?,
    oldBorrow: HostPhysicalDisplaySurfaceRegistry.ExactMainBorrowRetirementReceipt,
    barrierState: HostTeardownBarrierState,
): HostTeardownRetirementReceipt? {
    if (barrierState != HostTeardownBarrierState.CLEAR || !oldBorrow.isRetired) return null
    val retirement = ticket.retirementReceiptFor(controller, sink, oldBorrow) ?: return null
    return retirement.takeIf { it.matchesExact(controller, sink, oldBorrow) }
}
