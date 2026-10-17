package com.shilapi.xcertplay.vehicle.ultra.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayThemeActiveComponentCrosswalkTest {
    @Test
    fun resolvesTwoCarouselZonesByExactSelectedLayoutAndZoneIdentifiers() {
        val display = display(
            layout("inactive", zone("old", "mapsMedia", component("old-a"))),
            layout(
                "selected",
                zone("maps", "mapsMedia", component("base"), component("map-active")),
                zone("fixed", "single", component("fixed-primary")),
                zone("media", "dca", component("media-active"), component("other")),
            ),
        )

        assertEquals(
            listOf(
                CarPlayThemePrimarySelection(1, 0, 1),
                CarPlayThemePrimarySelection(1, 2, 0),
            ),
            CarPlayThemeActiveComponentCrosswalk.primarySelections(
                display,
                1,
                linkedMapOf("media" to "media-active", "maps" to "map-active"),
            ),
        )
    }

    @Test
    fun sameComponentIdentifierInDifferentZonesIsUnambiguous() {
        val display = display(
            layout(
                "main",
                zone("left", "mapsMedia", component("shared")),
                zone("right", "dca", component("shared")),
            ),
        )

        assertEquals(
            listOf(CarPlayThemePrimarySelection(0, 0, 0), CarPlayThemePrimarySelection(0, 1, 0)),
            CarPlayThemeActiveComponentCrosswalk.primarySelections(
                display,
                0,
                mapOf("left" to "shared", "right" to "shared"),
            ),
        )
    }

    @Test
    fun doesNotInventSelectionForSingleZone() {
        val display = display(layout("main", zone("fixed", "single", component("fixed-primary"))))

        assertEquals(
            emptyList<CarPlayThemePrimarySelection>(),
            CarPlayThemeActiveComponentCrosswalk.primarySelections(display, 0, emptyMap()),
        )
        rejects(display, 0, mapOf("fixed" to "fixed-primary"))
    }

    @Test
    fun rejectsMissingExtraAndWrongLayoutEntries() {
        val display = display(
            layout("other", zone("other-zone", "dca", component("other-primary"))),
            layout("main", zone("carousel", "mapsMedia", component("primary"))),
        )

        rejects(display, 1, emptyMap())
        rejects(display, 1, mapOf("carousel" to "primary", "surplus" to "extra"))
        rejects(display, 1, mapOf("other-zone" to "other-primary"))
        rejects(display, 1, mapOf("carousel" to "other-primary"))
        rejects(display, -1, mapOf("carousel" to "primary"))
        rejects(display, 2, mapOf("carousel" to "primary"))
    }

    @Test
    fun rejectsOverlayAsSelectedPrimaryComponent() {
        val display = display(
            layout(
                "main",
                zone("carousel", "dca", component("overlay", "overlay"), component("primary")),
            ),
        )

        rejects(display, 0, mapOf("carousel" to "overlay"))
    }

    @Test
    fun rejectsDuplicateOrEmptyLayoutZoneAndComponentIdentifiers() {
        rejects(
            display(layout("same"), layout("same", zone("carousel", "dca", component("active")))),
            1,
            mapOf("carousel" to "active"),
        )
        rejects(display(layout("", zone("carousel", "dca", component("active")))), 0, mapOf("carousel" to "active"))
        rejects(
            display(
                layout(
                    "main",
                    zone("duplicate", "dca", component("active")),
                    zone("duplicate", "mapsMedia", component("other")),
                ),
            ),
            0,
            mapOf("duplicate" to "active"),
        )
        rejects(display(layout("main", zone("", "dca", component("active")))), 0, emptyMap())
        rejects(
            display(layout("main", zone("carousel", "mapsMedia", component("same"), component("same")))),
            0,
            mapOf("carousel" to "same"),
        )
        rejects(
            display(layout("main", zone("carousel", "mapsMedia", component("")))),
            0,
            mapOf("carousel" to ""),
        )
    }

    @Test
    fun rejectsDeckBecauseItsGetterHasDifferentUnprovenSemantics() {
        val display = display(
            layout(
                "main",
                zone("carousel", "mapsMedia", component("active")),
                zone("stack", "deck", component("deck-primary")),
            ),
        )

        rejects(display, 0, mapOf("carousel" to "active"))
        rejects(display, 0, mapOf("carousel" to "active", "stack" to "deck-primary"))
    }

    @Test
    fun resultIsImmutableAndIndependentOfCallerMap() {
        val display = display(layout("main", zone("carousel", "dca", component("active"))))
        val requested = mutableMapOf("carousel" to "active")
        val selected = CarPlayThemeActiveComponentCrosswalk.primarySelections(display, 0, requested)
        requested.clear()

        assertEquals(listOf(CarPlayThemePrimarySelection(0, 0, 0)), selected)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (selected as MutableList<CarPlayThemePrimarySelection>).clear()
        }
    }

    private fun rejects(display: Display, layoutIndex: Int, requested: Map<String, String>) {
        assertThrows(CarPlayThemeActiveComponentCrosswalkException::class.java) {
            CarPlayThemeActiveComponentCrosswalk.primarySelections(display, layoutIndex, requested)
        }
    }
}

private fun display(vararg layouts: Map<String, Any?>): Display = CarPlayThemeSchema.parseDisplay(
    mapOf(
        "id" to "cluster-display",
        "type" to "cluster",
        "layouts" to layouts.toList(),
        "size" to listOf(1920, 720),
        "density" to 1.0,
        "allowManufacturerAppIcon" to false,
        "requiresDarkAppearance" to false,
    ),
)

private fun layout(id: String, vararg zones: Map<String, Any?>): Map<String, Any?> = mapOf(
    "id" to id,
    "zones" to zones.toList(),
    "layoutType" to "primary",
    "backgroundColor" to emptyMap<String, Any?>(),
    "safeAreaInsets" to emptyMap<String, Any?>(),
    "driveModeThemeConfigurations" to emptyMap<String, Any?>(),
)

private fun zone(id: String, type: String, vararg components: Map<String, Any?>): Map<String, Any?> = mapOf(
    "id" to id,
    "frame" to listOf(listOf(0, 0), listOf(400, 300)),
    "components" to components.toList(),
    "type" to mapOf(type to emptyMap<String, Any?>()),
)

private fun component(id: String, type: String = "primary"): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to type,
    "size" to listOf(400, 300),
    "slots" to emptyList<Any>(),
)
