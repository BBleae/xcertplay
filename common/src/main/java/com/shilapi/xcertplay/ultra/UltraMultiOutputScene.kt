package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId
import com.shilapi.xcertplay.vehicle.ultra.UltraIngressId
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeColor
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeProgramInstruction
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralProgram
import com.shilapi.xcertplay.vehicle.ultra.theme.DisplayType
import java.util.Collections
import kotlin.math.abs

/** Scene source which names the exact remote ingress instead of assuming one global video input. */
sealed interface UltraSceneSource {
    data class Remote(val ingressId: UltraIngressId) : UltraSceneSource
    data class Local(val contentId: UltraContentId) : UltraSceneSource
    data class Overlay(val contentId: UltraContentId) : UltraSceneSource
    /** Dedicated opaque, full-frame CLUSTER raster; never a Canvas/overlay Surface. */
    data class Instrument(val destination: DisplayDestination) : UltraSceneSource {
        init { require(destination.role == DisplayDestinationRole.CLUSTER) }
    }
    data object PunchThrough : UltraSceneSource
}

/**
 * Renderer-owned affine transform in physical output pixels. The origin is the output's top-left,
 * with X rightward and Y downward. A point (x, y) maps to
 * (xx*x + xy*y + translateXPixels, yx*x + yy*y + translateYPixels).
 *
 * This does not describe a phone UI Sync coordinate system or authorize protocol-driven layers.
 */
data class UltraPixelAffineTransform(
    val xx: Double = 1.0,
    val xy: Double = 0.0,
    val yx: Double = 0.0,
    val yy: Double = 1.0,
    val translateXPixels: Double = 0.0,
    val translateYPixels: Double = 0.0,
) {
    val isIdentity: Boolean
        get() = xx == 1.0 && xy == 0.0 && yx == 0.0 && yy == 1.0 &&
            translateXPixels == 0.0 && translateYPixels == 0.0

    init {
        require(listOf(xx, xy, yx, yy).all {
            it.isFinite() && abs(it) <= MAX_ABSOLUTE_COEFFICIENT
        }) { "Affine coefficients must be finite and bounded" }
        require(listOf(translateXPixels, translateYPixels).all {
            it.isFinite() && abs(it) <= MAX_ABSOLUTE_PIXEL_COORDINATE
        }) { "Affine translation must be finite and bounded in output pixels" }
    }

    internal fun project(xPixels: Long, yPixels: Long): UltraAffinePixelPoint {
        val x = xx * xPixels + xy * yPixels + translateXPixels
        val y = yx * xPixels + yy * yPixels + translateYPixels
        require(x.isFinite() && y.isFinite() && (isIdentity ||
            (abs(x) <= MAX_ABSOLUTE_PIXEL_COORDINATE &&
                abs(y) <= MAX_ABSOLUTE_PIXEL_COORDINATE))
        ) { "Transformed layer geometry exceeds the bounded output-pixel range" }
        return UltraAffinePixelPoint(x, y)
    }

    companion object {
        val IDENTITY = UltraPixelAffineTransform()

        // Pixel coordinates through 2^24 retain integer precision when submitted as GL floats.
        private const val MAX_ABSOLUTE_PIXEL_COORDINATE = 16_777_216.0
        private const val MAX_ABSOLUTE_COEFFICIENT = 1_000_000.0
    }
}

internal data class UltraAffinePixelPoint(val x: Double, val y: Double)

/**
 * [framePixels] is the textured quad before [transformPixels] in output-pixel coordinates.
 * [clipPixels] remains an axis-aligned rectangle in final output pixels; it is never transformed.
 * Instrument, overlay, and punch-through layers keep the identity transform.
 */
data class UltraSceneLayer(
    val id: UltraLayerId,
    val source: UltraSceneSource,
    val framePixels: UltraPixelRect,
    val clipPixels: UltraPixelRect? = null,
    val zIndex: Int = 0,
    val opacity: Double = 1.0,
    val transformPixels: UltraPixelAffineTransform = UltraPixelAffineTransform.IDENTITY,
) {
    init {
        require(opacity.isFinite() && opacity in 0.0..1.0) {
            "Layer opacity must be finite and between 0.0 and 1.0"
        }
        if (!transformPixels.isIdentity) {
            require(source is UltraSceneSource.Remote || source is UltraSceneSource.Local) {
                "Only remote or local textured layers may use an affine transform"
            }
            val left = framePixels.xPixels.toLong()
            val top = framePixels.yPixels.toLong()
            val right = left + framePixels.widthPixels
            val bottom = top + framePixels.heightPixels
            transformPixels.project(left, top)
            transformPixels.project(right, top)
            transformPixels.project(left, bottom)
            transformPixels.project(right, bottom)
        }
    }
}

/**
 * A scene's base clear, independent for each explicitly routed output. Transparent remains the
 * default. Opaque backgrounds can only be selected from a confirmed theme instruction; this is
 * not a claim that the remaining theme layers can be rendered.
 */
sealed interface UltraSceneBackground {
    data object Transparent : UltraSceneBackground

    class OpaqueSrgb private constructor(
        val layoutAddress: CarPlayThemeSceneLayoutAddress,
        val destination: DisplayDestination,
        val viewportPixels: PixelSize,
        internal val graphSnapshot: UltraDisplayGraphSnapshot,
        val graphOutput: UltraDisplayGraphOutput,
        val color: CarPlayThemeColor,
    ) : UltraSceneBackground {
        val graphRevision: Long
            get() = graphSnapshot.revision

        init {
            require(color.alpha == 1.0) { "A theme clear must be fully opaque" }
        }

        companion object {
            internal fun selectFromTheme(
                program: CarPlayThemeStructuralProgram,
                layoutAddress: CarPlayThemeSceneLayoutAddress,
                destination: DisplayDestination,
                graph: UltraDisplayGraphSnapshot,
                outputId: UltraDisplayOutputId,
                viewportPixels: PixelSize,
                fullViewportMappingConfirmed: Boolean,
            ): OpaqueSrgb {
                require(fullViewportMappingConfirmed) {
                    "The selected theme layout must be confirmed to cover the complete output viewport"
                }
                // These are semantic guards, not an inferred output assignment. The exact
                // destination instance and graph output still come from the caller's binding.
                val supportedRole = when (program.displayType) {
                    DisplayType.CENTER_CONSOLE -> DisplayDestinationRole.MAIN
                    DisplayType.CLUSTER -> DisplayDestinationRole.CLUSTER
                    DisplayType.PASSENGER, DisplayType.SECONDARY_CLUSTER -> null
                }
                require(supportedRole != null && destination.role == supportedRole) {
                    "Theme display type has no confirmed mapping to this output role"
                }
                require(program.logicalSize.width.isFinite() && program.logicalSize.width > 0.0 &&
                    program.logicalSize.height.isFinite() && program.logicalSize.height > 0.0
                ) { "Theme display logical size is invalid" }
                require(program.instructions.count {
                    it is CarPlayThemeProgramInstruction.Layout && it.address == layoutAddress
                } == 1) { "The selected theme layout address is missing or ambiguous" }
                val backgrounds = program.instructions.filterIsInstance<
                    CarPlayThemeProgramInstruction.FillLayoutBackground
                >().filter { it.address == layoutAddress }
                require(backgrounds.size == 1) {
                    "The selected theme layout has no unique confirmed fixed background"
                }
                val color = backgrounds.single().color
                require(color.alpha == 1.0) {
                    "A translucent theme background cannot be used as an EGL clear"
                }
                val output = requireNotNull(graph.output(outputId)) {
                    "The selected theme background output is unavailable in the exact graph"
                }
                return OpaqueSrgb(
                    layoutAddress, destination, viewportPixels, graph, output, color,
                )
            }
        }
    }
}

/** Independent scene and physical-pixel coordinate space for one output destination. */
class UltraOutputScene(
    val destination: DisplayDestination,
    val viewportPixels: PixelSize,
    layers: List<UltraSceneLayer>,
    val background: UltraSceneBackground = UltraSceneBackground.Transparent,
) {
    val layers: List<UltraSceneLayer>

    init {
        if (background is UltraSceneBackground.OpaqueSrgb) {
            require(background.destination == destination) {
                "Confirmed theme background destination does not match the output scene"
            }
            require(background.viewportPixels == viewportPixels) {
                "Confirmed theme background viewport does not match the output scene"
            }
        }
        val copied = boundedList(layers, MAXIMUM_LAYERS_PER_OUTPUT, "output scene layers")
        if (copied.any { it.source is UltraSceneSource.Instrument }) {
            require(destination.role == DisplayDestinationRole.CLUSTER && copied.size == 1) {
                "A reference instrument owns one whole CLUSTER frame, never a composited layer"
            }
            val raster = copied.single()
            require(raster.source == UltraSceneSource.Instrument(destination) &&
                raster.framePixels == UltraPixelRect(
                    0, 0, viewportPixels.widthPixels, viewportPixels.heightPixels,
                ) && raster.clipPixels == null && raster.opacity == 1.0 &&
                raster.transformPixels.isIdentity
            ) { "Reference instrument must cover the exact opaque CLUSTER viewport" }
        }
        require(copied.map(UltraSceneLayer::id).toSet().size == copied.size) {
            "Output scene layer IDs must be unique"
        }
        val ordered = copied.withIndex()
            .sortedWith(compareBy<IndexedValue<UltraSceneLayer>>({ it.value.zIndex }, { it.index }))
            .map(IndexedValue<UltraSceneLayer>::value)
        this.layers = Collections.unmodifiableList(ArrayList(ordered))
    }

    private companion object {
        const val MAXIMUM_LAYERS_PER_OUTPUT = 4_096
    }
}

/** One layer resolved to the exact session-bound ingress object, when it is remote. */
class UltraResolvedSceneLayer internal constructor(
    val layer: UltraSceneLayer,
    val remoteIngress: UltraDisplayGraphIngress?,
) {
    init {
        val remote = layer.source as? UltraSceneSource.Remote
        require((remote == null) == (remoteIngress == null)) {
            "Only a remote scene layer may carry one resolved ingress"
        }
        require(remote == null || remoteIngress?.id == remote.ingressId) {
            "Resolved ingress does not match the remote scene layer"
        }
    }
}

/** One scene resolved to the exact output object from one graph revision. */
class UltraResolvedOutputScene internal constructor(
    val scene: UltraOutputScene,
    val output: UltraDisplayGraphOutput,
    layers: List<UltraResolvedSceneLayer>,
) {
    val layers: List<UltraResolvedSceneLayer> =
        Collections.unmodifiableList(ArrayList(layers))

    init {
        require(this.layers.size == scene.layers.size) {
            "Resolved output scene must retain every source layer"
        }
        require(this.layers.indices.all { this.layers[it].layer === scene.layers[it] }) {
            "Resolved output scene must preserve exact source-layer identity and order"
        }
    }
}

/**
 * Immutable render plan. A backend consumes these resolved objects directly and must not re-query a
 * mutable [com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphRegistry] by opaque ID.
 */
class UltraResolvedMultiOutputRenderPlan internal constructor(
    val renderRevision: Long,
    val graphRevision: Long,
    scenes: List<UltraResolvedOutputScene>,
    outputByDestination: Map<DisplayDestination, UltraDisplayGraphOutput>,
) {
    val scenes: List<UltraResolvedOutputScene> =
        Collections.unmodifiableList(ArrayList(scenes))
    val outputByDestination: Map<DisplayDestination, UltraDisplayGraphOutput> =
        Collections.unmodifiableMap(LinkedHashMap(outputByDestination))
    private val byDestination = this.scenes.associateBy { it.scene.destination }

    init {
        require(byDestination.size == this.scenes.size) {
            "Resolved render plan contains duplicate destinations"
        }
        require(this.outputByDestination.keys == byDestination.keys) {
            "Resolved render plan must bind every destination exactly once"
        }
        require(this.scenes.all { resolved ->
            this.outputByDestination[resolved.scene.destination] === resolved.output
        }) {
            "Resolved render plan output mapping lost exact resource identity"
        }
    }

    fun scene(destination: DisplayDestination): UltraResolvedOutputScene? =
        byDestination[destination]
}

/**
 * One immutable frame containing independent scenes for all outputs.
 *
 * A remote ingress may occur in several scenes, and a scene may contain several remote ingresses.
 * This is a renderer contract only; it does not activate UI Sync or allocate Android surfaces.
 */
class UltraMultiOutputRenderSnapshot(
    val revision: Long,
    scenes: List<UltraOutputScene>,
) {
    val scenes: List<UltraOutputScene>
    private val byDestination: Map<DisplayDestination, UltraOutputScene>

    init {
        require(revision >= 0L) { "Render revision must not be negative" }
        val copied = boundedList(scenes, MAXIMUM_OUTPUT_SCENES, "output scenes")
        require(copied.map(UltraOutputScene::destination).toSet().size == copied.size) {
            "Render snapshot contains duplicate output destinations"
        }
        var totalLayers = 0
        copied.forEach { scene ->
            totalLayers = Math.addExact(totalLayers, scene.layers.size)
            require(totalLayers <= MAXIMUM_TOTAL_LAYERS) {
                "Render snapshot exceeds $MAXIMUM_TOTAL_LAYERS total layers"
            }
        }
        this.scenes = Collections.unmodifiableList(ArrayList(copied))
        byDestination = this.scenes.associateBy(UltraOutputScene::destination)
    }

    fun scene(destination: DisplayDestination): UltraOutputScene? = byDestination[destination]

    /**
     * Validates explicit typed destination→output mapping and every referenced ingress against one
     * complete graph revision. No relation is inferred from string equality.
     */
    fun resolveAgainst(
        graph: UltraDisplayGraphSnapshot,
        outputByDestination: Map<DisplayDestination, UltraDisplayOutputId>,
    ): UltraResolvedMultiOutputRenderPlan {
        val frozenOutputIds = boundedMap(
            outputByDestination,
            MAXIMUM_OUTPUT_SCENES,
            "destination to output mappings",
        )
        require(frozenOutputIds.keys == byDestination.keys) {
            "Every output scene requires one explicit output mapping"
        }
        require(frozenOutputIds.values.toSet().size == frozenOutputIds.size) {
            "One output resource cannot back multiple destinations in one snapshot"
        }

        val resolvedOutputs = LinkedHashMap<DisplayDestination, UltraDisplayGraphOutput>()
        val resolvedScenes = scenes.map { scene ->
            val outputId = checkNotNull(frozenOutputIds[scene.destination])
            val output = requireNotNull(graph.output(outputId)) {
                "Output scene refers to an unavailable output resource"
            }
            val background = scene.background
            if (background is UltraSceneBackground.OpaqueSrgb) {
                require(background.graphSnapshot === graph && background.graphOutput === output) {
                    "Theme background is bound to a different graph snapshot or output resource"
                }
            }
            resolvedOutputs[scene.destination] = output
            val resolvedLayers = scene.layers.map { layer ->
                val ingress = (layer.source as? UltraSceneSource.Remote)?.let { remote ->
                    requireNotNull(graph.ingress(remote.ingressId)) {
                        "Remote scene layer refers to an unavailable ingress"
                    }
                }
                UltraResolvedSceneLayer(layer, ingress)
            }
            UltraResolvedOutputScene(scene, output, resolvedLayers)
        }
        return UltraResolvedMultiOutputRenderPlan(
            renderRevision = revision,
            graphRevision = graph.revision,
            scenes = resolvedScenes,
            outputByDestination = resolvedOutputs,
        )
    }

    private companion object {
        const val MAXIMUM_OUTPUT_SCENES = 64
        const val MAXIMUM_TOTAL_LAYERS = 4_096
    }
}

private fun <T> boundedList(values: List<T>, limit: Int, label: String): List<T> {
    val copied = ArrayList<T>(minOf(limit, 16))
    val iterator = values.iterator()
    while (iterator.hasNext()) {
        if (copied.size == limit) {
            iterator.next()
            throw IllegalArgumentException("$label exceed $limit entries")
        }
        copied += iterator.next()
    }
    return copied
}

private fun <K, V> boundedMap(values: Map<K, V>, limit: Int, label: String): Map<K, V> {
    val copied = LinkedHashMap<K, V>(minOf(limit, 16))
    val iterator = values.entries.iterator()
    while (iterator.hasNext()) {
        if (copied.size == limit) {
            iterator.next()
            throw IllegalArgumentException("$label exceed $limit entries")
        }
        val entry = iterator.next()
        require(!copied.containsKey(entry.key)) { "$label contain a duplicate key" }
        copied[entry.key] = entry.value
    }
    return Collections.unmodifiableMap(copied)
}
