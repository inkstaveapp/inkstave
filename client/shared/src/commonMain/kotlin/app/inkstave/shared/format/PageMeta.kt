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
 * A page's `pages/<page-id>.meta.json` (see `docs/format-spec.md`,
 * "`pages/<page-id>.meta.json`"). For an M1-imported page, [processing] and
 * [ocr] are `null` -- nothing has run the cleanup/OCR pipeline yet, that's
 * M4's job (`docs/image-pipeline.md`) -- and [aspectRatioClass] is
 * `"custom"`, since no aspect-ratio normalization has happened either.
 *
 * [unknownFields] preserves any JSON object keys this model doesn't
 * recognise yet; see [Manifest.unknownFields].
 */
@Serializable
data class PageMeta(
    val id: String,
    val width: Int,
    val height: Int,
    val aspectRatioClass: String,
    val processing: PageProcessing? = null,
    val ocr: PageOcr? = null,
    @Transient
    val unknownFields: Map<String, JsonElement> = emptyMap(),
)

/**
 * Reproducibility data for a page's cleanup pipeline (`docs/image-pipeline.md`).
 * [cropPolygon] is a list of `[x, y]` pairs, matching
 * `format/schema/page-meta.v1.schema.json`'s array-of-2-number-arrays shape.
 */
@Serializable
data class PageProcessing(
    val cropPolygon: List<List<Double>> = emptyList(),
    val dewarpMeshVersion: String? = null,
    val contrastMethod: String? = null,
)

/**
 * OCR metadata candidates for a page. Always proposals, never auto-committed
 * to `manifest.json` -- see `docs/image-pipeline.md`.
 */
@Serializable
data class PageOcr(
    val engineVersion: String,
    val candidates: Map<String, String> = emptyMap(),
    val confidence: Map<String, Double> = emptyMap(),
)

/** JSON object keys [PageMeta] models directly; everything else is an "unknown field". */
private val KNOWN_PAGE_META_KEYS = setOf("id", "width", "height", "aspectRatioClass", "processing", "ocr")

/**
 * Reads and writes [PageMeta] as `<page-id>.meta.json` text, preserving
 * unrecognised keys. See [ManifestJson] -- same approach.
 */
object PageMetaJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    /** Parses [text] as v1 page metadata, preserving any fields this model doesn't recognise. */
    fun decode(text: String): PageMeta {
        val root = json.parseToJsonElement(text).jsonObject
        val known = json.decodeFromJsonElement<PageMeta>(root)
        val unknown = root.filterKeys { it !in KNOWN_PAGE_META_KEYS }
        return known.copy(unknownFields = unknown)
    }

    /** Serialises [pageMeta] back to `<page-id>.meta.json` text, re-including preserved unknown fields. */
    fun encode(pageMeta: PageMeta): String {
        val knownElement = json.encodeToJsonElement(pageMeta).jsonObject
        val merged =
            buildJsonObject {
                pageMeta.unknownFields.forEach { (key, value) -> put(key, value) }
                knownElement.forEach { (key, value) -> put(key, value) }
            }
        return json.encodeToString(JsonObject.serializer(), merged)
    }
}
