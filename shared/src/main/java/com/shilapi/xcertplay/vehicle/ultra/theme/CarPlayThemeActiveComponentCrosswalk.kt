package com.shilapi.xcertplay.vehicle.ultra.theme

import java.util.Collections

class CarPlayThemeActiveComponentCrosswalkException(message: String) : IllegalArgumentException(message)

/**
 * Resolves a UI Sync `activeComponents` map to ordinals in one parsed theme Display.
 *
 * The observed CarPlayAssetUI carousel getter maps Zone.id to the active Component.id for
 * mapsMedia/dca Zones. It does not establish selection semantics for single or deck Zones.
 * This is an exact identifier crosswalk only: the caller must independently verify the theme,
 * UI Sync session, selected layout, and renderer authority. No selection is guessed or defaulted.
 */
object CarPlayThemeActiveComponentCrosswalk {
    @JvmStatic
    fun primarySelections(
        display: Display,
        layoutIndex: Int,
        activeComponents: Map<String, String>,
    ): List<CarPlayThemePrimarySelection> {
        val layout = display.layouts.getOrNull(layoutIndex)
            ?: throw CarPlayThemeActiveComponentCrosswalkException("Unknown selected layout index")
        val seenLayoutIDs = HashSet<String>()
        display.layouts.forEach { candidate ->
            if (candidate.id.isBlank() || !seenLayoutIDs.add(candidate.id)) {
                throw CarPlayThemeActiveComponentCrosswalkException("Layout IDs must be nonempty and unique")
            }
        }

        val requested = LinkedHashMap(activeComponents)
        val expectedZoneIDs = HashSet<String>()
        val seenZoneIDs = HashSet<String>()
        val selections = ArrayList<CarPlayThemePrimarySelection>()
        layout.zones.forEachIndexed { zoneIndex, zone ->
            if (zone.id.isBlank() || !seenZoneIDs.add(zone.id)) {
                throw CarPlayThemeActiveComponentCrosswalkException("Zone IDs must be nonempty and unique in the selected layout")
            }
            val seenComponentIDs = HashSet<String>()
            zone.components.forEach { component ->
                if (component.id.isBlank() || !seenComponentIDs.add(component.id)) {
                    throw CarPlayThemeActiveComponentCrosswalkException("Component IDs must be nonempty and unique in each Zone")
                }
            }

            when (zone.zoneType.case) {
                ZoneTypeCase.MAPS_MEDIA, ZoneTypeCase.DCA -> {
                    expectedZoneIDs.add(zone.id)
                    val selectedID = requested[zone.id]
                        ?: throw CarPlayThemeActiveComponentCrosswalkException(
                            "Active component is missing for a carousel Zone",
                        )
                    val componentIndex = zone.components.indexOfFirst { it.id == selectedID }
                    if (componentIndex < 0) {
                        throw CarPlayThemeActiveComponentCrosswalkException(
                            "Active component does not belong to its selected carousel Zone",
                        )
                    }
                    if (zone.components[componentIndex].type != ComponentType.PRIMARY) {
                        throw CarPlayThemeActiveComponentCrosswalkException(
                            "Active carousel component must be primary",
                        )
                    }
                    selections.add(CarPlayThemePrimarySelection(layoutIndex, zoneIndex, componentIndex))
                }
                ZoneTypeCase.SINGLE -> Unit
                ZoneTypeCase.DECK -> throw CarPlayThemeActiveComponentCrosswalkException(
                    "Deck active-component semantics are not established",
                )
            }
        }
        if (requested.keys != expectedZoneIDs) {
            throw CarPlayThemeActiveComponentCrosswalkException(
                "Active components contain entries outside the selected carousel Zones",
            )
        }
        return Collections.unmodifiableList(selections)
    }
}
