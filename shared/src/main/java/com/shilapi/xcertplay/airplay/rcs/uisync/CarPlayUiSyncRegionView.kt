package com.shilapi.xcertplay.airplay.rcs.uisync

import java.util.Collections

/**
 * A read-only description of the few type-2 region fields confirmed in iOS 27's sender.
 *
 * This is diagnostic evidence, not a layout, clipping, z-order, clock-domain, foreground, or
 * pixel-authority decision. The original [CarPlayUiSyncSemanticCommand.TransitionData.regions]
 * retain bounded opaque fields; this view deliberately exposes no raw region dictionary.
 */
class CarPlayUiSyncRegionDiagnosticView internal constructor(
    /** Zero-based position in the sender's regions array; no front-to-back meaning is inferred. */
    val sourceIndex: Int,
    val identifier: CarPlayUiSyncRegionDiagnosticField<String>,
    val opacity: CarPlayUiSyncRegionDiagnosticField<Double>,
    val opacitySource: CarPlayUiSyncRegionOpacitySource?,
    /** The optional 16-Double `transform` input; not directly usable as a pixel transform. */
    val sourceTransform3D: CarPlayUiSyncRegionDiagnosticField<List<Double>>,
    /** The optional six-Double `transform2D` output; its coordinate domain is unproven. */
    val transform2D: CarPlayUiSyncRegionDiagnosticField<List<Double>>,
) {
    override fun toString(): String =
        "CarPlayUiSyncRegionDiagnosticView(sourceIndex=$sourceIndex, " +
            "identifier=${identifier.stateName()}, opacity=${opacity.stateName()}, " +
            "sourceTransform3D=${sourceTransform3D.stateName()}, " +
            "transform2D=${transform2D.stateName()})"
}

sealed interface CarPlayUiSyncRegionDiagnosticField<out T> {
    data object Missing : CarPlayUiSyncRegionDiagnosticField<Nothing>

    class Available<T> internal constructor(val value: T) :
        CarPlayUiSyncRegionDiagnosticField<T> {
        override fun toString(): String = "Available(redacted)"
    }

    class Invalid internal constructor(val reason: CarPlayUiSyncRegionDiagnosticIssue) :
        CarPlayUiSyncRegionDiagnosticField<Nothing> {
        override fun toString(): String = "Invalid($reason)"
    }
}

enum class CarPlayUiSyncRegionDiagnosticIssue {
    REGION_NOT_DICTIONARY,
    WRONG_TYPE,
    WRONG_LENGTH,
    NON_FINITE,
}

enum class CarPlayUiSyncRegionOpacitySource {
    EXPLICIT,
    CUMULATIVE_FALLBACK,
}

/**
 * Projects type-2 regions in sender array order without changing command acceptance. A malformed
 * known field becomes [CarPlayUiSyncRegionDiagnosticField.Invalid], while the bounded opaque
 * source remains available to other diagnostic consumers. The semantic decoder still rejects
 * non-finite numbers on the wire before this function runs.
 */
fun CarPlayUiSyncSemanticCommand.TransitionData.regionDiagnosticViews():
    List<CarPlayUiSyncRegionDiagnosticView> = Collections.unmodifiableList(
        regions.mapIndexed { index, region ->
            val values = region.value as? Map<*, *>
            if (values == null) {
                val invalid = CarPlayUiSyncRegionDiagnosticField.Invalid(
                    CarPlayUiSyncRegionDiagnosticIssue.REGION_NOT_DICTIONARY,
                )
                CarPlayUiSyncRegionDiagnosticView(index, invalid, invalid, null, invalid, invalid)
            } else {
                val opacitySource = when {
                    values.containsKey(OPACITY) -> CarPlayUiSyncRegionOpacitySource.EXPLICIT
                    values.containsKey(CUMULATIVE_OPACITY) ->
                        CarPlayUiSyncRegionOpacitySource.CUMULATIVE_FALLBACK
                    else -> null
                }
                CarPlayUiSyncRegionDiagnosticView(
                    sourceIndex = index,
                    identifier = stringField(values, IDENTIFIER),
                    opacity = when (opacitySource) {
                        CarPlayUiSyncRegionOpacitySource.EXPLICIT -> doubleField(values, OPACITY)
                        CarPlayUiSyncRegionOpacitySource.CUMULATIVE_FALLBACK ->
                            doubleField(values, CUMULATIVE_OPACITY)
                        null -> CarPlayUiSyncRegionDiagnosticField.Missing
                    },
                    opacitySource = opacitySource,
                    sourceTransform3D = doubleArrayField(values, TRANSFORM, 16),
                    transform2D = doubleArrayField(values, TRANSFORM_2D, 6),
                )
            }
        },
    )

private fun stringField(
    values: Map<*, *>,
    key: String,
): CarPlayUiSyncRegionDiagnosticField<String> {
    if (!values.containsKey(key)) return CarPlayUiSyncRegionDiagnosticField.Missing
    val value = values[key] as? String ?: return invalid(
        CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE,
    )
    return CarPlayUiSyncRegionDiagnosticField.Available(value)
}

private fun doubleField(
    values: Map<*, *>,
    key: String,
): CarPlayUiSyncRegionDiagnosticField<Double> {
    if (!values.containsKey(key)) return CarPlayUiSyncRegionDiagnosticField.Missing
    val value = values[key] as? Double ?: return invalid(
        CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE,
    )
    if (!value.isFinite()) return invalid(CarPlayUiSyncRegionDiagnosticIssue.NON_FINITE)
    return CarPlayUiSyncRegionDiagnosticField.Available(value)
}

private fun doubleArrayField(
    values: Map<*, *>,
    key: String,
    expectedSize: Int,
): CarPlayUiSyncRegionDiagnosticField<List<Double>> {
    if (!values.containsKey(key)) return CarPlayUiSyncRegionDiagnosticField.Missing
    val value = values[key] as? List<*> ?: return invalid(
        CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE,
    )
    if (value.size != expectedSize) return invalid(
        CarPlayUiSyncRegionDiagnosticIssue.WRONG_LENGTH,
    )
    if (value.any { it !is Double }) return invalid(
        CarPlayUiSyncRegionDiagnosticIssue.WRONG_TYPE,
    )
    if (value.any { !(it as Double).isFinite() }) return invalid(
        CarPlayUiSyncRegionDiagnosticIssue.NON_FINITE,
    )
    return CarPlayUiSyncRegionDiagnosticField.Available(
        Collections.unmodifiableList(value.map { it as Double }),
    )
}

private fun invalid(reason: CarPlayUiSyncRegionDiagnosticIssue) =
    CarPlayUiSyncRegionDiagnosticField.Invalid(reason)

private fun CarPlayUiSyncRegionDiagnosticField<*>.stateName(): String = when (this) {
    CarPlayUiSyncRegionDiagnosticField.Missing -> "missing"
    is CarPlayUiSyncRegionDiagnosticField.Available<*> -> "available"
    is CarPlayUiSyncRegionDiagnosticField.Invalid -> "invalid:$reason"
}

private const val IDENTIFIER = "identifier"
private const val OPACITY = "opacity"
private const val CUMULATIVE_OPACITY = "cumulative_opacity"
private const val TRANSFORM = "transform"
private const val TRANSFORM_2D = "transform2D"
