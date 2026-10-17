package com.shilapi.xcertplay.media

/** Retires the failed candidate before reinstalling the previous route; failure stops immediately. */
internal fun <H : Any> rollbackRouteBindingsWithoutOverlap(
    failedCandidate: H?,
    previous: H?,
    reconcile: (H) -> Unit,
) {
    if (failedCandidate != null) reconcile(failedCandidate)
    if (previous != null && previous !== failedCandidate) reconcile(previous)
}
