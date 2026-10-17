package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.CompiledUiSyncDisplayAssignment
import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalPoint
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayLogicalRect
import com.shilapi.xcertplay.vehicle.ultra.theme.CarPlayThemeSchema
import com.shilapi.xcertplay.vehicle.ultra.theme.logicalSize
import java.util.Collections

/**
 * Bind an issuer-selected static program to the exact currently borrowed Host pixel dimensions.
 * Theme display density is a raster-asset hint, not Android dpi and not this scene transform.
 * Fractional pixel edges remain unsupported until an explicit verified policy is issued.
 */
internal fun buildAuthorizedSignedSceneBindings(
    profile: VerifiedUltraProfile,
    assignments: List<CompiledUiSyncDisplayAssignment>,
    destinationPixels: List<Pair<DisplayDestination, PixelSize>>,
): List<UltraThemeOutputSceneBinding> {
    require(assignments.isNotEmpty() && assignments.size == destinationPixels.size &&
        destinationPixels.map { it.first }.toSet().size == destinationPixels.size
    ) { "Signed scene destinations and live Host targets differ" }
    val pixelsByDestination = destinationPixels.toMap()
    require(assignments.map { it.destination }.toSet() == pixelsByDestination.keys) {
        "Signed scene has a missing or foreign Host destination"
    }
    val path = UltraProfilePackageFiles.CARPLAY_PHONE_PACKAGE_JSON
    val entry = requireNotNull(profile.candidate.entries.singleOrNull { it.name == path }) {
        "Verified Profile has no unique phone theme package"
    }
    val packageBytes = requireNotNull(profile.candidate.content(path)) {
        "Verified Profile lost its phone theme package"
    }
    val displays = CarPlayThemeSchema.parseArcPackageJson(packageBytes).displays
    return Collections.unmodifiableList(assignments.map { assignment ->
        val display = requireNotNull(displays.getOrNull(assignment.profileDisplayIndex)) {
            "Signed scene display index is absent"
        }
        require(assignment.phonePackageDigest == entry.digest &&
            assignment.profileDisplayId == display.id &&
            assignment.profileDisplayType == display.type
        ) { "Signed scene display differs from the exact verified Profile" }
        val viewport = requireNotNull(pixelsByDestination[assignment.destination])
        val logical = display.logicalSize()
        require(logical.width.isFinite() && logical.height.isFinite() &&
            logical.width > 0.0 && logical.height > 0.0
        ) { "Signed display has no finite positive logical size" }
        val mapper = CarPlayLogicalPixelMapper(
            AxisAlignedPixelAffine(
                viewport.widthPixels.toDouble() / logical.width,
                viewport.heightPixels.toDouble() / logical.height,
                0.0, 0.0,
            ),
            NegativeExtentPolicy.REJECT,
            PixelEdgeQuantization.REQUIRE_INTEGRAL,
            PixelEdgeQuantization.REQUIRE_INTEGRAL,
        )
        require(mapper.mapSubpixel(CarPlayLogicalRect(
            CarPlayLogicalPoint(0.0, 0.0), logical,
        )) == UltraSubpixelRect(
            0.0, 0.0,
            viewport.widthPixels.toDouble(), viewport.heightPixels.toDouble(),
        )) { "Signed logical display cannot map exactly onto the Host viewport" }
        UltraThemeOutputSceneBinding(
            source = UltraThemeVerifiedProgramSource(
                profile, path, assignment.phonePackageDigest,
                assignment.programSelection.structuralFingerprint,
                assignment.profileDisplayIndex,
                assignment.programSelection.layoutIndex,
                assignment.programSelection.stateSnapshot(),
            ),
            destination = assignment.destination,
            outputId = assignment.outputId,
            viewportPixels = viewport,
            pixelMapper = mapper,
            slotIngresses = emptyList(), // Controller resolves live signed slots from the graph.
        )
    })
}
