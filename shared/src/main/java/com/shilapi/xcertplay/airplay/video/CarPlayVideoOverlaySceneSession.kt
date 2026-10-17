package com.shilapi.xcertplay.airplay.video

import java.util.Collections
import java.util.Locale

/** Local memory ceilings, not claims about the iOS wire protocol or a drawable screen. */
data class CarPlayVideoOverlaySceneLimits(
    val maximumLayers: Int = 256,
    val maximumAssets: Int = 64,
    val maximumAssetBytes: Int = 512 * 1024,
    val maximumTextBytes: Int = 256 * 1024,
    val maximumChildrenPerLayer: Int = 128,
    val maximumTransactionChildren: Int = 128,
) {
    init {
        require(maximumLayers in 1..4096)
        require(maximumAssets in 1..1024)
        require(maximumAssetBytes in 1..(4 * 1024 * 1024))
        require(maximumTextBytes in 1..(8 * 1024 * 1024))
        require(maximumChildrenPerLayer in 1..4096)
        require(maximumTransactionChildren in 1..1024)
    }
}

/**
 * Detached, in-memory interpretation of the narrow *known* overlay actions. It does not own an
 * RCS client, acknowledge the phone, create pixels, or alter ordinary CarPlay/Ultra capabilities.
 * Each action (including a transaction) is copied, validated, and committed under one lock.
 * Unknown semantics fail closed without changing the previous revision.
 */
class CarPlayVideoOverlaySceneSession(
    private val limits: CarPlayVideoOverlaySceneLimits = CarPlayVideoOverlaySceneLimits(),
) : AutoCloseable {
    enum class LayerKind { RING, TEXT, IMAGE }

    class LayerSnapshot internal constructor(
        val uuid: String,
        val kind: LayerKind,
        val radius: Double?,
        val text: String?,
        /** Source ImageContent identifier only; this does not assert the asset is available. */
        val imageAssetIdentifier: String?,
        /** Raw createAsset payload observed, not decoded, bound, rendered, or render-ready. */
        val imageAssetPayloadPresent: Boolean,
        val origin: CarPlayVideoOverlayGeometrySchema.Point?,
        val size: CarPlayVideoOverlayGeometrySchema.Size?,
        val centerAnchor: CarPlayVideoOverlayGeometrySchema.CenterAnchor?,
        val transform: CarPlayVideoOverlayGeometrySchema.Transform?,
        val opacity: Double?,
        val parentUuid: String?,
        children: List<String>,
    ) {
        val children: List<String> = Collections.unmodifiableList(ArrayList(children))
    }

    class AssetSnapshot internal constructor(
        val name: String,
        val mimeType: String,
        data: ByteArray,
        val sprite: CarPlayVideoOverlayAction.Sprite?,
    ) {
        private val ownedData = data.copyOf()
        val data: ByteArray get() = ownedData.copyOf()
        val byteCount: Int get() = ownedData.size
    }

    class Snapshot internal constructor(
        val revision: Long,
        val terminal: Boolean,
        layers: Map<String, LayerSnapshot>,
        assets: Map<String, AssetSnapshot>,
    ) {
        val layers: Map<String, LayerSnapshot> = Collections.unmodifiableMap(LinkedHashMap(layers))
        val assets: Map<String, AssetSnapshot> = Collections.unmodifiableMap(LinkedHashMap(assets))
    }

    private class Layer(
        val kind: LayerKind,
        var radius: Double? = null,
        var text: String? = null,
        var imageAssetIdentifier: String? = null,
        var origin: CarPlayVideoOverlayGeometrySchema.Point? = null,
        var size: CarPlayVideoOverlayGeometrySchema.Size? = null,
        var centerAnchor: CarPlayVideoOverlayGeometrySchema.CenterAnchor? = null,
        var transform: CarPlayVideoOverlayGeometrySchema.Transform? = null,
        var opacity: Double? = null,
        var parentUuid: String? = null,
        val children: MutableList<String> = ArrayList(),
    ) {
        fun copy(): Layer = Layer(
            kind, radius, text, imageAssetIdentifier, origin, size, centerAnchor, transform,
            opacity, parentUuid, ArrayList(children),
        )
    }

    private class Asset(
        val name: String,
        val mimeType: String,
        val data: ByteArray,
        val sprite: CarPlayVideoOverlayAction.Sprite?,
    )

    private class State(
        val layers: LinkedHashMap<String, Layer> = LinkedHashMap(),
        val assets: LinkedHashMap<String, Asset> = LinkedHashMap(),
        var assetBytes: Long = 0,
        var textBytes: Long = 0,
    ) {
        fun copy(): State = State(
            LinkedHashMap<String, Layer>(layers.size).apply {
                layers.forEach { (uuid, layer) -> put(uuid, layer.copy()) }
            },
            // Asset data is owned by the session and never mutated after creation.
            LinkedHashMap(assets),
            assetBytes,
            textBytes,
        )
    }

    private var state = State()
    private var revision = 0L
    private var terminal = false

    /** Returns false on an opaque/invalid/over-limit/stale action; no partial state escapes. */
    @Synchronized
    fun apply(action: CarPlayVideoOverlayAction): Boolean {
        if (terminal || revision == Long.MAX_VALUE) return false
        val actions = if (action is CarPlayVideoOverlayAction.Transaction) {
            if (action.children.size > limits.maximumTransactionChildren) return false
            action.children
        } else {
            listOf(action)
        }
        val candidate = state.copy()
        for (child in actions) {
            if (child is CarPlayVideoOverlayAction.Transaction ||
                child is CarPlayVideoOverlayAction.Opaque ||
                !candidate.applyKnown(child)
            ) return false
        }
        state = candidate
        revision++
        return true
    }

    /** A terminal revocation never reopens and drops all session-owned layers and assets. */
    @Synchronized
    fun revoke() {
        if (terminal) return
        terminal = true
        state = State()
        if (revision < Long.MAX_VALUE) revision++
    }

    override fun close() = revoke()

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        revision = revision,
        terminal = terminal,
        layers = state.layers.mapValuesTo(LinkedHashMap()) { (uuid, layer) ->
            LayerSnapshot(
                uuid = uuid,
                kind = layer.kind,
                radius = layer.radius,
                text = layer.text,
                imageAssetIdentifier = layer.imageAssetIdentifier,
                imageAssetPayloadPresent = layer.imageAssetIdentifier?.let(state.assets::containsKey) ?: false,
                origin = layer.origin,
                size = layer.size,
                centerAnchor = layer.centerAnchor,
                transform = layer.transform,
                opacity = layer.opacity,
                parentUuid = layer.parentUuid,
                children = layer.children,
            )
        },
        assets = state.assets.mapValuesTo(LinkedHashMap()) { (_, asset) ->
            AssetSnapshot(asset.name, asset.mimeType, asset.data, asset.sprite)
        },
    )

    private fun State.applyKnown(action: CarPlayVideoOverlayAction): Boolean = when (action) {
        is CarPlayVideoOverlayAction.CreateRing -> {
            val uuid = action.objectUuid.canonicalUuid()
            if (layers.size >= limits.maximumLayers || layers.containsKey(uuid) ||
                action.radius < 0.0 || !action.radius.isFinite()
            ) false else {
                layers[uuid] = Layer(LayerKind.RING, radius = action.radius)
                true
            }
        }
        is CarPlayVideoOverlayAction.CreateText -> {
            val uuid = action.objectUuid.canonicalUuid()
            val bytes = action.text.textStorageBytes()
            if (layers.size >= limits.maximumLayers || layers.containsKey(uuid) ||
                bytes > limits.maximumTextBytes.toLong() - textBytes
            ) false else {
                layers[uuid] = Layer(LayerKind.TEXT, text = action.text)
                textBytes += bytes
                true
            }
        }
        is CarPlayVideoOverlayAction.CreateImageAsset -> {
            val uuid = action.objectUuid.canonicalUuid()
            if (layers.size >= limits.maximumLayers || layers.containsKey(uuid)) false else {
                // Registration/presentation order is unproven; retain the identifier even if
                // createAsset has not arrived. No pixels or render-ready claim follow from it.
                layers[uuid] = Layer(LayerKind.IMAGE, imageAssetIdentifier = action.assetIdentifier)
                true
            }
        }
        is CarPlayVideoOverlayAction.UpdateRadiusCandidate -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer?.kind != LayerKind.RING || action.radius < 0.0 || !action.radius.isFinite()) {
                false
            } else {
                layer.radius = action.radius
                true
            }
        }
        is CarPlayVideoOverlayAction.UpdateTextCandidate -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer?.kind != LayerKind.TEXT) false else {
                val oldBytes = requireNotNull(layer.text).textStorageBytes()
                val newBytes = action.text.textStorageBytes()
                if (newBytes > limits.maximumTextBytes.toLong() - (textBytes - oldBytes)) {
                    false
                } else {
                    layer.text = action.text
                    textBytes += newBytes - oldBytes
                    true
                }
            }
        }
        is CarPlayVideoOverlayAction.SetOpacity -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer == null || action.opacity !in 0.0..1.0) false else {
                layer.opacity = action.opacity
                true
            }
        }
        is CarPlayVideoOverlayAction.SetOrigin -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer == null) false else {
                layer.origin = action.origin
                true
            }
        }
        is CarPlayVideoOverlayAction.SetSize -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer == null) false else {
                layer.size = action.size
                true
            }
        }
        is CarPlayVideoOverlayAction.SetCenterAnchor -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer == null) false else {
                layer.centerAnchor = action.centerAnchor
                true
            }
        }
        is CarPlayVideoOverlayAction.SetTransform -> {
            val layer = layers[action.objectUuid.canonicalUuid()]
            if (layer == null) false else {
                layer.transform = action.transform
                true
            }
        }
        is CarPlayVideoOverlayAction.InsertLayer -> insert(action)
        is CarPlayVideoOverlayAction.RemoveLayer -> remove(action.objectUuid.canonicalUuid())
        is CarPlayVideoOverlayAction.CreateAsset -> {
            val data = action.data
            if (action.name.isEmpty() || assets.size >= limits.maximumAssets ||
                assets.containsKey(action.name) ||
                data.size.toLong() > limits.maximumAssetBytes.toLong() - assetBytes
            ) false else {
                assets[action.name] = Asset(action.name, action.mimeType, data, action.sprite)
                assetBytes += data.size
                true
            }
        }
        is CarPlayVideoOverlayAction.RemoveAsset -> {
            val removed = assets.remove(action.name)
            if (removed == null) false else {
                assetBytes -= removed.data.size
                true
            }
        }
        is CarPlayVideoOverlayAction.Transaction,
        is CarPlayVideoOverlayAction.Opaque -> false
    }

    private fun State.insert(action: CarPlayVideoOverlayAction.InsertLayer): Boolean {
        val parentUuid = action.objectUuid.canonicalUuid()
        val childUuid = action.subLayerUuid.canonicalUuid()
        if (parentUuid == childUuid) return false
        val parent = layers[parentUuid] ?: return false
        val child = layers[childUuid] ?: return false
        if (child.parentUuid != null || action.index !in 0..parent.children.size ||
            parent.children.size >= limits.maximumChildrenPerLayer
        ) return false
        var ancestor: String? = parentUuid
        while (ancestor != null) {
            if (ancestor == childUuid) return false
            ancestor = layers[ancestor]?.parentUuid
        }
        parent.children.add(action.index, childUuid)
        child.parentUuid = parentUuid
        return true
    }

    private fun State.remove(uuid: String): Boolean {
        val layer = layers[uuid] ?: return false
        // No guessed cascade/detach semantics for a parent with live children.
        if (layer.children.isNotEmpty()) return false
        val parentUuid = layer.parentUuid
        if (parentUuid != null && layers[parentUuid]?.children?.remove(uuid) != true) return false
        if (layer.kind == LayerKind.TEXT) textBytes -= requireNotNull(layer.text).textStorageBytes()
        layers.remove(uuid)
        return true
    }

    private fun String.canonicalUuid(): String = lowercase(Locale.ROOT)

    // A conservative local retention budget: a UTF-16 code unit occupies two bytes even when a
    // plist happened to encode its ASCII representation more compactly.
    private fun String.textStorageBytes(): Long = length.toLong() * 2L
}
