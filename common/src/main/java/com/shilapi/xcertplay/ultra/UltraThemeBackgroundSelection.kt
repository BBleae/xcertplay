package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSceneLayoutAddress
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeStructuralProgram
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayGraphSnapshot
import com.shilapi.xcertplay.vehicle.ultra.UltraDisplayOutputId

/**
 * Selects one *exact* compiled layout's confirmed solid background for one physical-pixel
 * viewport and one graph output. The caller must independently establish that this layout covers
 * that entire output;
 * displayId, role, asset density, and logical-to-pixel scaling are not inferred here.
 *
 * This background-only selection is not a complete theme renderer or a trust decision.
 */
fun selectUltraOpaqueThemeBackground(
    program: CarPlayThemeStructuralProgram,
    layoutAddress: CarPlayThemeSceneLayoutAddress,
    destination: DisplayDestination,
    graph: UltraDisplayGraphSnapshot,
    outputId: UltraDisplayOutputId,
    viewportPixels: PixelSize,
    fullViewportMappingConfirmed: Boolean,
): UltraSceneBackground.OpaqueSrgb = UltraSceneBackground.OpaqueSrgb.selectFromTheme(
    program, layoutAddress, destination, graph, outputId, viewportPixels,
    fullViewportMappingConfirmed,
)
