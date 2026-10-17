package com.shilapi.xcertplay.vehicle.ultra.theme

import com.shilapi.xcertplay.vehicle.ultra.profile.UltraProfilePackageFiles
import com.shilapi.xcertplay.vehicle.ultra.profile.VerifiedUltraProfile
import java.math.BigInteger
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

enum class AssetManifestAssetType(val wireValue: String) {
    IMAGE("image"),
    FONT("font"),
    RESOURCE("resource"),
}

/** The confirmed destination OptionSet used by entries in `asset_manifest.json`. */
class AssetManifestDestination internal constructor(val bits: Int) {
    val includesPhone: Boolean
        get() = bits and PHONE_BIT != 0
    val includesAccessory: Boolean
        get() = bits and ACCESSORY_BIT != 0
    val includesArc: Boolean
        get() = bits and ARC_BIT != 0
    val includesResolution: Boolean
        get() = bits and RESOLUTION_BIT != 0

    override fun equals(other: Any?): Boolean =
        other is AssetManifestDestination && bits == other.bits

    override fun hashCode(): Int = bits

    override fun toString(): String = "AssetManifestDestination(bits=$bits)"

    companion object {
        const val PHONE_BIT = 1
        const val ACCESSORY_BIT = 2
        const val ARC_BIT = 4
        const val RESOLUTION_BIT = 8
        const val ALL_BITS = 15

        val PHONE = AssetManifestDestination(PHONE_BIT)
        val ACCESSORY = AssetManifestDestination(ACCESSORY_BIT)
        val ARC = AssetManifestDestination(ARC_BIT)
        val RESOLUTION = AssetManifestDestination(RESOLUTION_BIT)
        val ALL = AssetManifestDestination(ALL_BITS)
    }
}

class AssetManifestEntry internal constructor(
    val path: String,
    val assetType: AssetManifestAssetType,
    val destination: AssetManifestDestination,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

/**
 * Direct dictionary root of `asset_manifest.json`.
 *
 * Manifest keys are intentionally not correlated with [AssetManifestEntry.path]: no such relation
 * has been established by the decoder evidence. Target paths themselves are unique.
 */
class AssetManifest internal constructor(
    entries: Map<String, AssetManifestEntry>,
) {
    val entries: Map<String, AssetManifestEntry> =
        Collections.unmodifiableMap(LinkedHashMap(entries))

    /**
     * Checks this allowlist against an already verified profile VFS.
     *
     * Every declared target is resolved below `CarPlayPhone.assets/`. Undeclared files are
     * diagnostics only: they are never silently appended to the allowlist.
     */
    fun validateAgainst(profile: VerifiedUltraProfile): AssetManifestProfileValidation =
        validatePhoneAssetFileNames(profile.candidate.fileNames)

    internal fun validatePhoneAssetFileNames(
        profileFileNames: Set<String>,
    ): AssetManifestProfileValidation {
        val root = UltraProfilePackageFiles.CARPLAY_PHONE_ASSETS_DIRECTORY
        val phoneFiles = profileFileNames.asSequence()
            .filter { it.startsWith(root) && it.length > root.length }
            .map { it.substring(root.length) }
            .toCollection(LinkedHashSet())
        val declared = entries.values.asSequence()
            .map(AssetManifestEntry::path)
            .toCollection(LinkedHashSet())
        return AssetManifestProfileValidation(
            missingTargetPaths = (declared - phoneFiles).sorted(),
            undeclaredResourcePaths = (phoneFiles - declared).sorted(),
        )
    }
}

class AssetManifestProfileValidation internal constructor(
    missingTargetPaths: List<String>,
    undeclaredResourcePaths: List<String>,
) {
    val missingTargetPaths: List<String> =
        Collections.unmodifiableList(ArrayList(missingTargetPaths))
    val undeclaredResourcePaths: List<String> =
        Collections.unmodifiableList(ArrayList(undeclaredResourcePaths))
    val allDeclaredTargetsExist: Boolean
        get() = missingTargetPaths.isEmpty()
}

/** Editable `theme.json`; its confirmed root contains only `layoutStyles`. */
class EditableTheme internal constructor(
    layoutStyles: List<LayoutStyle>,
) {
    val layoutStyles: List<LayoutStyle> =
        Collections.unmodifiableList(ArrayList(layoutStyles))
}

/**
 * Decoder-confirmed layout-style fields.
 *
 * Only `id` is known to be required. Fields whose decode requiredness remains unconfirmed retain
 * typed absence instead of inventing defaults.
 */
class LayoutStyle internal constructor(
    val id: String,
    val colorSetOverrides: CarPlayThemeOpaqueValue?,
    val defaultPaletteID: String?,
    val defaultWallpaperID: String?,
    excludedWallpaperIDs: List<String>?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
) {
    val excludedWallpaperIDs: List<String>? = excludedWallpaperIDs?.let {
        Collections.unmodifiableList(ArrayList(it))
    }
}

enum class RepresentedColorSchemeCase(val wireValue: String) {
    SINGLE("single"),
    DYNAMIC_COLOR("dynamicColor"),
    LINEAR_GRADIENT("linearGradient"),
    MULTICOLOR("multicolor"),
}

/** Dynamic color payload. Color values remain opaque, but both appearances are explicit. */
class DynamicAppearance internal constructor(
    val lightMode: CarPlayThemeOpaqueValue,
    val darkMode: CarPlayThemeOpaqueValue,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

/** Strict one-key represented-color-scheme enum. */
class RepresentedColorScheme internal constructor(
    val case: RepresentedColorSchemeCase,
    val payload: CarPlayThemeOpaqueValue,
    val dynamicAppearance: DynamicAppearance?,
)

class Palette internal constructor(
    val id: String,
    val displayName: String,
    val colors: CarPlayThemeOpaqueValue,
    /** Typed only when every ColorID uses a confirmed gradient encoding; never a paint receipt. */
    val gradientColors: Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor>>?,
    val backgroundOverrideColor: CarPlayThemeOpaqueValue?,
    val representedColorScheme: RepresentedColorScheme,
    val sortIndex: Int,
    val accentColor: CarPlayThemeOpaqueValue?,
    val prominent: Boolean,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
)

class ResolvedTheme internal constructor(
    palettes: Map<String, Palette>,
    layoutStyles: Map<String, Map<String, Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor?>>>>,
    defaultPaletteIDs: Map<String, String>,
    defaultWallpaperIDs: Map<String, String>,
    excludedWallpaperIDs: Map<String, List<String>>?,
    val extensions: Map<String, CarPlayThemeOpaqueValue>,
) {
    val palettes: Map<String, Palette> =
        Collections.unmodifiableMap(LinkedHashMap(palettes))
    /** LayoutStyle.ID → ColorSet.ID → ColorID → Dynamic<GradientColor, GradientColor?>. */
    val layoutStyles: Map<String, Map<String, Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor?>>>> =
        Collections.unmodifiableMap(
            layoutStyles.mapValuesTo(LinkedHashMap()) { (_, colorSets) ->
                Collections.unmodifiableMap(
                    colorSets.mapValuesTo(LinkedHashMap()) { (_, colors) ->
                        Collections.unmodifiableMap(LinkedHashMap(colors))
                    },
                )
            },
        )
    val defaultPaletteIDs: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(defaultPaletteIDs))
    val defaultWallpaperIDs: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(defaultWallpaperIDs))
    val excludedWallpaperIDs: Map<String, List<String>>? = excludedWallpaperIDs?.let { values ->
        Collections.unmodifiableMap(
            values.mapValuesTo(LinkedHashMap()) { (_, ids) ->
                Collections.unmodifiableList(ArrayList(ids))
            },
        )
    }
}

object CarPlayAssetManifestSchema {
    fun parseJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): AssetManifest = parse(decodeThemeJson(bytes, limits), limits)

    fun parse(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): AssetManifest {
        val frozen = freezeCarPlayThemeInput(value, limits)
        val source = requireThemeDictionary(frozen, "asset_manifest.json")
        val parsed = LinkedHashMap<String, AssetManifestEntry>(source.size)
        val paths = HashSet<String>(source.size)
        val foldedPaths = HashMap<String, String>(source.size)
        source.entries.forEachIndexed { index, (manifestKey, entryValue) ->
            val entry = parseAssetManifestEntry(entryValue, "asset_manifest.json entry[$index]")
            if (!paths.add(entry.path)) {
                throw CarPlayThemeSchemaException(
                    "asset_manifest.json contains a duplicate target path",
                )
            }
            val folded = entry.path.lowercase(Locale.ROOT)
            val previous = foldedPaths[folded]
            if (previous != null && previous != entry.path) {
                throw CarPlayThemeSchemaException(
                    "asset_manifest.json contains case-conflicting target paths",
                )
            }
            foldedPaths[folded] = entry.path
            parsed[manifestKey] = entry
        }
        return AssetManifest(parsed)
    }
}

object CarPlayEditableThemeSchema {
    fun parseJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): EditableTheme = parse(decodeThemeJson(bytes, limits), limits)

    fun parse(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): EditableTheme {
        val frozen = freezeCarPlayThemeInput(value, limits)
        val fields = ResourceThemeFields(frozen, "theme.json")
        val styles = parseResourceList(
            fields.required("layoutStyles"),
            "theme.json.layoutStyles",
            ::parseLayoutStyle,
        )
        requireUniqueLayoutStyleIds(styles, "theme.json.layoutStyles")
        fields.requireNoRemainingFields()
        return EditableTheme(styles)
    }
}

object CarPlayResolvedThemeSchema {
    fun parseJson(
        bytes: ByteArray,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ResolvedTheme = parse(decodeThemeJson(bytes, limits), limits)

    fun parse(
        value: Any?,
        limits: CarPlayThemeSchemaLimits = CarPlayThemeSchemaLimits(),
    ): ResolvedTheme {
        val frozen = freezeCarPlayThemeInput(value, limits)
        val fields = ResourceThemeFields(frozen, "ResolvedTheme")
        val palettes = parseResourceMap(
                fields.required("palettes"),
                "ResolvedTheme.palettes",
                ::parsePalette,
            )
        val layoutStyles = parseResolvedLayoutStyles(
            fields.required("layoutStyles"),
            "ResolvedTheme.layoutStyles",
            limits,
        )
        val defaultPaletteIDs = parseResourceStringMap(
                fields.required("defaultPaletteIDs"),
                "ResolvedTheme.defaultPaletteIDs",
            )
        val defaultWallpaperIDs = parseResourceStringMap(
                fields.required("defaultWallpaperIDs"),
                "ResolvedTheme.defaultWallpaperIDs",
            )
        val excludedWallpaperIDs = fields.optional("excludedWallpaperIDs") {
            parseResourceStringListMap(it, "ResolvedTheme.excludedWallpaperIDs")
        }
        val paletteIds = palettes.values.map(Palette::id)
        val layoutIds = layoutStyles.keys
        if (paletteIds.toSet().size != paletteIds.size) {
            throw CarPlayThemeSchemaException("ResolvedTheme contains duplicate model ids")
        }
        if (!layoutIds.containsAll(defaultPaletteIDs.keys) ||
            !layoutIds.containsAll(defaultWallpaperIDs.keys) ||
            excludedWallpaperIDs?.keys?.all(layoutIds::contains) == false
        ) {
            throw CarPlayThemeSchemaException("ResolvedTheme defaults refer to an unknown layout style")
        }
        if (defaultPaletteIDs.any { (layoutId, colorSetId) ->
                !layoutStyles.getValue(layoutId).containsKey(colorSetId)
            }
        ) {
            throw CarPlayThemeSchemaException("ResolvedTheme defaultPaletteIDs refer to an unknown color set")
        }
        return ResolvedTheme(
            palettes = palettes,
            layoutStyles = layoutStyles,
            defaultPaletteIDs = defaultPaletteIDs,
            defaultWallpaperIDs = defaultWallpaperIDs,
            excludedWallpaperIDs = excludedWallpaperIDs,
            extensions = fields.extensions(),
        )
    }
}

private fun parseAssetManifestEntry(value: Any?, label: String): AssetManifestEntry {
    val fields = ResourceThemeFields(value, label)
    val path = fields.requiredString("path")
    validateAssetManifestPath(path, "$label.path")
    val assetType = when (val raw = fields.requiredString("assetType")) {
        "image" -> AssetManifestAssetType.IMAGE
        "font" -> AssetManifestAssetType.FONT
        "resource" -> AssetManifestAssetType.RESOURCE
        else -> throw CarPlayThemeSchemaException("$label.assetType has an unknown enum value")
    }
    val destinationBits = parseResourceInt(fields.required("destination"), "$label.destination")
    if (destinationBits < 0 ||
        (destinationBits and AssetManifestDestination.ALL_BITS) != destinationBits
    ) {
        throw CarPlayThemeSchemaException("$label.destination contains unknown OptionSet bits")
    }
    return AssetManifestEntry(
        path = path,
        assetType = assetType,
        destination = AssetManifestDestination(destinationBits),
        extensions = fields.extensions(),
    )
}

private fun validateAssetManifestPath(path: String, label: String) {
    val invalid = path.isEmpty() ||
        path.startsWith('/') ||
        WINDOWS_ASSET_PATH_PREFIX.containsMatchIn(path) ||
        '\\' in path ||
        path.any { it == '\u0000' || it.isISOControl() } ||
        hasUnpairedSurrogate(path) ||
        !Normalizer.isNormalized(path, Normalizer.Form.NFC) ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." }
    if (invalid) {
        throw CarPlayThemeSchemaException("$label must be a strict NFC relative path")
    }
}

private fun hasUnpairedSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    return true
                }
                index += 2
            }
            Character.isLowSurrogate(current) -> return true
            else -> index += 1
        }
    }
    return false
}

private fun parseLayoutStyle(value: Any?, label: String): LayoutStyle {
    val fields = ResourceThemeFields(value, label)
    return LayoutStyle(
        id = fields.requiredString("id"),
        colorSetOverrides = fields.optionalOpaque("colorSetOverrides"),
        defaultPaletteID = fields.optionalString("defaultPaletteID"),
        defaultWallpaperID = fields.optionalString("defaultWallpaperID"),
        excludedWallpaperIDs = fields.optional("excludedWallpaperIDs") {
            parseResourceStringList(it, "$label.excludedWallpaperIDs")
        },
        extensions = fields.extensions(),
    )
}

/**
 * `ResolvedTheme.layoutStyles` is not the editable [LayoutStyle] array. The iOS decoder's
 * confirmed value is LayoutStyle.ID → ColorSet.ID → ColorID →
 * Dynamic<GradientColor, GradientColor?>. These are decoded values only, not paint authority.
 */
private fun parseResolvedLayoutStyles(
    value: Any?,
    label: String,
    limits: CarPlayThemeSchemaLimits,
): Map<String, Map<String, Map<String, CarPlayThemeDynamic<CarPlayThemeGradientColor?>>>> {
    val styles = requireThemeDictionary(value, label)
    if (styles.size > MAXIMUM_RESOLVED_LAYOUT_STYLES) {
        throw CarPlayThemeSchemaException("$label exceeds the local layout-style limit")
    }
    return parseResourceMap(value, label) { colorSetsValue, styleLabel ->
        val colorSets = requireThemeDictionary(colorSetsValue, styleLabel)
        if (colorSets.size > MAXIMUM_RESOLVED_COLOR_SETS_PER_STYLE) {
            throw CarPlayThemeSchemaException("$styleLabel exceeds the local color-set limit")
        }
        parseResourceMap(colorSetsValue, styleLabel) { colorsValue, setLabel ->
            val colors = requireThemeDictionary(colorsValue, setLabel)
            if (colors.size > CarPlayThemeAppearanceSchema.MAXIMUM_PALETTE_COLOR_IDS) {
                throw CarPlayThemeSchemaException("$setLabel exceeds the local ColorID limit")
            }
            parseResourceMap(colorsValue, setLabel) { colorValue, colorLabel ->
                parseResolvedDynamicGradientColor(colorValue, colorLabel, limits)
            }
        }
    }
}

private fun parseResolvedDynamicGradientColor(
    value: Any?,
    label: String,
    limits: CarPlayThemeSchemaLimits,
): CarPlayThemeDynamic<CarPlayThemeGradientColor?> {
    val cases = requireThemeDictionary(value, label)
    if (cases.size != 1) {
        throw CarPlayThemeSchemaException("$label must contain exactly one Dynamic case")
    }
    val (caseName, caseValue) = cases.entries.single()
    val wrapper = requireThemeDictionary(caseValue, "$label.$caseName")
    if (wrapper.keys != setOf("value")) {
        throw CarPlayThemeSchemaException("$label.$caseName must contain only 'value'")
    }
    val wrapped = wrapper.getValue("value")
    return when (caseName) {
        "single" -> CarPlayThemeDynamic.Single(
            CarPlayThemeAppearanceSchema.parseGradientColor(wrapped, limits),
        )
        "dynamic" -> {
            val appearances = requireThemeDictionary(wrapped, "$label.dynamic.value")
            if (appearances.keys != setOf("lightMode", "darkMode")) {
                throw CarPlayThemeSchemaException(
                    "$label.dynamic.value must contain only lightMode and darkMode",
                )
            }
            fun parseAppearance(raw: Any?): CarPlayThemeGradientColor? =
                raw?.let { CarPlayThemeAppearanceSchema.parseGradientColor(it, limits) }
            CarPlayThemeDynamic.Appearance(
                lightMode = parseAppearance(appearances.getValue("lightMode")),
                darkMode = parseAppearance(appearances.getValue("darkMode")),
            )
        }
        else -> throw CarPlayThemeSchemaException("$label has an unknown Dynamic case")
    }
}

private fun parsePalette(value: Any?, label: String): Palette {
    val fields = ResourceThemeFields(value, label)
    val rawColors = fields.required("colors")
        ?: throw CarPlayThemeSchemaException("$label.colors must not be null")
    return Palette(
        id = fields.requiredString("id"),
        displayName = fields.requiredString("displayName"),
        colors = CarPlayThemeOpaqueValue(rawColors),
        gradientColors = CarPlayThemeAppearanceSchema.parsePaletteColorsIfSupported(
            rawColors, "$label.colors",
        ),
        backgroundOverrideColor = fields.optionalOpaque("backgroundOverrideColor"),
        representedColorScheme = parseRepresentedColorScheme(
            fields.required("representedColorScheme"),
            "$label.representedColorScheme",
        ),
        sortIndex = parseResourceInt(fields.required("sortIndex"), "$label.sortIndex"),
        accentColor = fields.optionalOpaque("accentColor"),
        prominent = fields.requiredBoolean("prominent"),
        extensions = fields.extensions(),
    )
}

private fun parseRepresentedColorScheme(
    value: Any?,
    label: String,
): RepresentedColorScheme {
    val source = requireThemeDictionary(value, label)
    if (source.size != 1) {
        throw CarPlayThemeSchemaException("$label must contain exactly one enum case")
    }
    val (caseName, payload) = source.entries.single()
    val schemeCase = when (caseName) {
        "single" -> RepresentedColorSchemeCase.SINGLE
        "dynamicColor" -> RepresentedColorSchemeCase.DYNAMIC_COLOR
        "linearGradient" -> RepresentedColorSchemeCase.LINEAR_GRADIENT
        "multicolor" -> RepresentedColorSchemeCase.MULTICOLOR
        else -> throw CarPlayThemeSchemaException("$label has an unknown enum value")
    }
    if (schemeCase == RepresentedColorSchemeCase.SINGLE) {
        val single = requireThemeDictionary(payload, "$label.single")
        if (single.keys != setOf("color") ||
            CarPlayThemeColor.parse(single["color"]) == null
        ) {
            throw CarPlayThemeSchemaException("$label.single must contain one supported color")
        }
    }
    return RepresentedColorScheme(
        case = schemeCase,
        payload = CarPlayThemeOpaqueValue(payload),
        dynamicAppearance = if (schemeCase == RepresentedColorSchemeCase.DYNAMIC_COLOR) {
            parseDynamicAppearance(payload, "$label.dynamicColor")
        } else {
            null
        },
    )
}

private fun parseDynamicAppearance(value: Any?, label: String): DynamicAppearance {
    val fields = ResourceThemeFields(value, label)
    return DynamicAppearance(
        lightMode = fields.requiredOpaque("lightMode"),
        darkMode = fields.requiredOpaque("darkMode"),
        extensions = fields.extensions(),
    )
}

private class ResourceThemeFields(value: Any?, private val label: String) {
    private val remaining = LinkedHashMap(requireThemeDictionary(value, label))

    fun required(key: String): Any? {
        if (!remaining.containsKey(key)) {
            throw CarPlayThemeSchemaException("$label is missing required field '$key'")
        }
        return remaining.remove(key)
    }

    fun requiredString(key: String): String = requireResourceString(required(key), "$label.$key")

    fun requiredBoolean(key: String): Boolean =
        required(key) as? Boolean
            ?: throw CarPlayThemeSchemaException("$label.$key must be a boolean")

    fun requiredOpaque(key: String): CarPlayThemeOpaqueValue {
        val value = required(key)
        if (value == null) {
            throw CarPlayThemeSchemaException("$label.$key must not be null")
        }
        return CarPlayThemeOpaqueValue(value)
    }

    fun optionalString(key: String): String? = optional(key) {
        requireResourceString(it, "$label.$key")
    }

    fun optionalOpaque(key: String): CarPlayThemeOpaqueValue? = optional(key) {
        if (it == null) throw CarPlayThemeSchemaException("$label.$key must not be null")
        CarPlayThemeOpaqueValue(it)
    }

    fun <T> optional(key: String, parser: (Any?) -> T): T? {
        if (!remaining.containsKey(key)) return null
        return parser(remaining.remove(key))
    }

    fun extensions(): Map<String, CarPlayThemeOpaqueValue> {
        val result = LinkedHashMap<String, CarPlayThemeOpaqueValue>(remaining.size)
        remaining.forEach { (key, item) -> result[key] = CarPlayThemeOpaqueValue(item) }
        remaining.clear()
        return Collections.unmodifiableMap(result)
    }

    fun requireNoRemainingFields() {
        if (remaining.isNotEmpty()) {
            throw CarPlayThemeSchemaException("$label contains an unknown root field")
        }
    }
}

private fun requireThemeDictionary(value: Any?, label: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return value as? Map<String, Any?>
        ?: throw CarPlayThemeSchemaException("$label must be a dictionary")
}

private fun requireResourceString(value: Any?, label: String): String =
    value as? String ?: throw CarPlayThemeSchemaException("$label must be a string")

private fun parseResourceInt(value: Any?, label: String): Int {
    val integer = when (value) {
        is Byte -> BigInteger.valueOf(value.toLong())
        is Short -> BigInteger.valueOf(value.toLong())
        is Int -> BigInteger.valueOf(value.toLong())
        is Long -> BigInteger.valueOf(value)
        is BigInteger -> value
        else -> throw CarPlayThemeSchemaException("$label must be an integer")
    }
    if (integer < RESOURCE_INT_MIN || integer > RESOURCE_INT_MAX) {
        throw CarPlayThemeSchemaException("$label is outside the Int range")
    }
    return integer.toInt()
}

private inline fun <T> parseResourceList(
    value: Any?,
    label: String,
    parser: (Any?, String) -> T,
): List<T> {
    val source = value as? List<*>
        ?: throw CarPlayThemeSchemaException("$label must be an array")
    val result = ArrayList<T>(source.size)
    source.forEachIndexed { index, item -> result += parser(item, "$label[$index]") }
    return Collections.unmodifiableList(result)
}

private fun parseResourceStringList(value: Any?, label: String): List<String> =
    parseResourceList(value, label) { item, itemLabel ->
        requireResourceString(item, itemLabel)
    }

private fun parseResourceStringListMap(
    value: Any?,
    label: String,
): Map<String, List<String>> {
    val source = requireThemeDictionary(value, label)
    val result = LinkedHashMap<String, List<String>>(source.size)
    source.forEach { (key, item) ->
        result[key] = parseResourceStringList(item, "$label.$key")
    }
    return Collections.unmodifiableMap(result)
}

private fun requireUniqueLayoutStyleIds(styles: List<LayoutStyle>, label: String) {
    if (styles.map(LayoutStyle::id).toSet().size != styles.size) {
        throw CarPlayThemeSchemaException("$label contains duplicate ids")
    }
}

private inline fun <T> parseResourceMap(
    value: Any?,
    label: String,
    parser: (Any?, String) -> T,
): Map<String, T> {
    val source = requireThemeDictionary(value, label)
    val result = LinkedHashMap<String, T>(source.size)
    source.forEach { (key, item) -> result[key] = parser(item, "$label.$key") }
    return Collections.unmodifiableMap(result)
}

private fun parseResourceStringMap(value: Any?, label: String): Map<String, String> {
    val source = requireThemeDictionary(value, label)
    val result = LinkedHashMap<String, String>(source.size)
    source.forEach { (key, item) ->
        result[key] = requireResourceString(item, "$label.$key")
    }
    return Collections.unmodifiableMap(result)
}

private val WINDOWS_ASSET_PATH_PREFIX = Regex("^[A-Za-z]:")
private val RESOURCE_INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
private val RESOURCE_INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
private const val MAXIMUM_RESOLVED_LAYOUT_STYLES = 256
private const val MAXIMUM_RESOLVED_COLOR_SETS_PER_STYLE = 256
