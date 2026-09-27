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
 * The v1 `manifest.json` of a `.smpk` package (`docs/format-spec.md`); fields mirror the spec.
 *
 * [unknownFields] holds keys this model doesn't recognise, so they survive read-modify-write: the
 * format requires unknown fields are never dropped, so newer clients' data isn't deleted by older
 * ones. Only [ManifestJson] should touch it.
 */
@Serializable
data class Manifest(
    val formatVersion: Int,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val composer: String? = null,
    val arranger: String? = null,
    val lyricist: String? = null,
    val genre: String? = null,
    val tags: List<String> = emptyList(),
    val parts: List<String> = emptyList(),
    val createdAt: String,
    val modifiedAt: String,
    val source: ManifestSource,
    @Transient
    val unknownFields: Map<String, JsonElement> = emptyMap(),
)

/** `manifest.json`'s `source` object. [details] is untyped JSON because the spec leaves its shape to each [type]. */
@Serializable
data class ManifestSource(
    val type: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

/** JSON object keys [Manifest] models directly; everything else is an "unknown field". */
private val KNOWN_MANIFEST_KEYS =
    setOf(
        "formatVersion",
        "id",
        "title",
        "subtitle",
        "composer",
        "arranger",
        "lyricist",
        "genre",
        "tags",
        "parts",
        "createdAt",
        "modifiedAt",
        "source",
    )

/**
 * Reads and writes [Manifest] as JSON, preserving unrecognised keys ([Manifest.unknownFields]).
 * kotlinx.serialization drops unknown keys on decode, so this splits the raw [JsonObject] into known
 * and unknown keys and merges them back on encode.
 */
object ManifestJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    /** Parses [text] as a v1 manifest, preserving any fields this model doesn't recognise. */
    fun decode(text: String): Manifest {
        val root = json.parseToJsonElement(text).jsonObject
        val known = json.decodeFromJsonElement<Manifest>(root)
        val unknown = root.filterKeys { it !in KNOWN_MANIFEST_KEYS }
        return known.copy(unknownFields = unknown)
    }

    /** Serialises [manifest] back to `manifest.json` text, re-including any preserved unknown fields. */
    fun encode(manifest: Manifest): String {
        val knownElement = json.encodeToJsonElement(manifest).jsonObject
        val merged =
            buildJsonObject {
                manifest.unknownFields.forEach { (key, value) -> put(key, value) }
                knownElement.forEach { (key, value) -> put(key, value) }
            }
        return json.encodeToString(JsonObject.serializer(), merged)
    }
}
