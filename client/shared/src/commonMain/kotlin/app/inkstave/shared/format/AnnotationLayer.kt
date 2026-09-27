package app.inkstave.shared.format

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A page's `annotations/<page-id>.json` (`docs/format-spec.md`): its strokes, stamps, highlights
 * and text notes, in page-point space (`app.inkstave.shared.annotation.PagePointSpace`) so they
 * render the same at any raster resolution or screen size.
 *
 * [unknownFields] preserves top-level keys this model doesn't recognise, as [Manifest] does. The
 * nested objects don't track unknown fields.
 */
@Serializable
data class AnnotationLayer(
    val pageId: String,
    val layerVersion: Int,
    val strokes: List<Stroke> = emptyList(),
    val stamps: List<Stamp> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val textNotes: List<TextNote> = emptyList(),
    @Transient
    val unknownFields: Map<String, JsonElement> = emptyMap(),
) {
    /** An empty layer for [pageId], used for pages that have never been annotated. */
    companion object {
        fun empty(pageId: String): AnnotationLayer = AnnotationLayer(pageId = pageId, layerVersion = 1)
    }
}

/**
 * One freehand stroke. [points] are `[x, y]` pairs in page-point space, in
 * drawing order; [widthPt] is the stroke's visual width in the same unit.
 */
@Serializable
data class Stroke(
    val id: String,
    val points: List<List<Double>>,
    val color: String,
    val widthPt: Double,
)

/**
 * One placed stamp symbol. [x]/[y] are its centre in page-point space, [scale] multiplies its base
 * size (1.0 = default), and [rotationDeg] is clockwise rotation.
 */
@Serializable
data class Stamp(
    val id: String,
    val symbol: String,
    val x: Double,
    val y: Double,
    val scale: Double,
    val rotationDeg: Double,
)

/** One highlighted rectangular region. [rectPt] is `[minX, minY, maxX, maxY]` in page-point space. */
@Serializable
data class Highlight(
    val id: String,
    val rectPt: List<Double>,
    val color: String,
)

/** One free-floating text annotation, anchored at [x]/[y] (top-left, page-point space). */
@Serializable
data class TextNote(
    val id: String,
    val x: Double,
    val y: Double,
    val text: String,
    val fontSizePt: Double,
)

/** JSON object keys [AnnotationLayer] models directly; everything else is an "unknown field". */
private val KNOWN_ANNOTATION_LAYER_KEYS = setOf("pageId", "layerVersion", "strokes", "stamps", "highlights", "textNotes")

/** Reads and writes [AnnotationLayer] as JSON, preserving unrecognised top-level keys (same approach as [ManifestJson]). */
object AnnotationLayerJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    /** Parses [text] as a v1 annotation layer, preserving any top-level fields this model doesn't recognise. */
    fun decode(text: String): AnnotationLayer {
        val root = json.parseToJsonElement(text).jsonObject
        val known = json.decodeFromJsonElement<AnnotationLayer>(root)
        val unknown = root.filterKeys { it !in KNOWN_ANNOTATION_LAYER_KEYS }
        return known.copy(unknownFields = unknown)
    }

    /** Serialises [layer] back to `<page-id>.json` text, re-including preserved unknown fields. */
    fun encode(layer: AnnotationLayer): String {
        val knownElement = json.encodeToJsonElement(layer).jsonObject
        val merged =
            buildJsonObject {
                layer.unknownFields.forEach { (key, value) -> put(key, value) }
                knownElement.forEach { (key, value) -> put(key, value) }
            }
        return json.encodeToString(JsonObject.serializer(), merged)
    }
}
