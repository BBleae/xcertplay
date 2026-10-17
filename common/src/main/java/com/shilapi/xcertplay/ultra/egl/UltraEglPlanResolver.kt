package com.shilapi.xcertplay.ultra.egl

import com.shilapi.xcertplay.airplay.AirPlayVideoStreamHandle
import com.shilapi.xcertplay.ultra.DisplayDestination
import com.shilapi.xcertplay.ultra.PixelSize
import com.shilapi.xcertplay.ultra.UltraMultiOutputRenderSnapshot
import com.shilapi.xcertplay.ultra.UltraResolvedMultiOutputRenderPlan
import com.shilapi.xcertplay.ultra.UltraSceneSource
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphIngress
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphOutput

internal data class UltraEglBoundOutput(
    val generation: Long,
    val graphOutput: UltraDisplayGraphOutput,
    val sizePixels: PixelSize,
    val isValid: Boolean,
)

internal data class UltraEglBoundIngress(
    val generation: Long,
    val graphIngress: UltraDisplayGraphIngress?,
    val remoteHandle: AirPlayVideoStreamHandle?,
    val active: Boolean,
)

/** Pure binding step: consumes exact plan objects and never queries a mutable registry. */
internal fun resolveUltraEglPlan(
    plan: UltraResolvedMultiOutputRenderPlan,
    outputs: Map<DisplayDestination, UltraEglBoundOutput>,
    ingresses: Map<UltraEglIngressKey, UltraEglBoundIngress>,
): UltraEglResolvedSubmission {
    val scenes = plan.scenes.map { it.scene }
    val snapshot = UltraMultiOutputRenderSnapshot(plan.renderRevision, scenes)
    val outputGenerations = LinkedHashMap<DisplayDestination, Long>()
    val ingressGenerations = LinkedHashMap<UltraEglIngressKey, Long>()
    plan.scenes.forEach { resolvedScene ->
        val destination = resolvedScene.scene.destination
        require(plan.outputByDestination[destination] === resolvedScene.output) {
            "Resolved render plan output identity is inconsistent for $destination"
        }
        val output = outputs[destination]
            ?: throw IllegalArgumentException("No exact EGL output lease for $destination")
        require(output.graphOutput === resolvedScene.output) {
            "Resolved graph output does not match the exact attached EGL output resource"
        }
        check(output.isValid) { "Android output Surface is invalid for $destination" }
        require(resolvedScene.scene.viewportPixels == output.sizePixels) {
            "Scene viewport does not match attached physical output pixels"
        }
        outputGenerations[destination] = output.generation

        resolvedScene.layers.forEach { resolvedLayer ->
            val key = resolvedLayer.layer.source.toEglIngressKeyOrNull() ?: return@forEach
            val record = ingresses[key]
                ?: throw IllegalArgumentException("No exact EGL ingress lease is open for $key")
            require(record.active) { "EGL ingress is retiring: $key" }
            if (key is UltraEglIngressKey.Remote) {
                val graphIngress = requireNotNull(resolvedLayer.remoteIngress) {
                    "Remote EGL layer is not bound to a graph ingress"
                }
                require(graphIngress.id == key.ingressId) {
                    "Resolved remote ingress ID does not match the scene source"
                }
                require(record.graphIngress === graphIngress) {
                    "Resolved graph ingress does not match the exact attached EGL ingress resource"
                }
                require(record.remoteHandle === graphIngress.stream) {
                    "Resolved remote ingress stream handle does not match the exact EGL lease"
                }
            } else {
                require(resolvedLayer.remoteIngress == null) {
                    "Local EGL layer unexpectedly carries a remote graph ingress"
                }
            }
            ingressGenerations[key] = record.generation
        }
    }
    return UltraEglResolvedSubmission(
        scene = snapshot,
        graphRevision = plan.graphRevision,
        ingressGenerations = ingressGenerations.toMap(),
        outputGenerations = outputGenerations.toMap(),
    )
}

private fun UltraSceneSource.toEglIngressKeyOrNull(): UltraEglIngressKey? = when (this) {
    is UltraSceneSource.Remote -> UltraEglIngressKey.Remote(ingressId)
    is UltraSceneSource.Local -> UltraEglIngressKey.Local(contentId)
    is UltraSceneSource.Overlay -> UltraEglIngressKey.Overlay(contentId)
    is UltraSceneSource.Instrument -> UltraEglIngressKey.Instrument(destination)
    UltraSceneSource.PunchThrough -> null
}
