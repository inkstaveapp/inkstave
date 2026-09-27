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
 * One part's `parts/<part-id>/part.json` (`docs/format-spec.md`). A single-part score still has
 * exactly one, so multi-part support can be added without a migration.
 *
 * [unknownFields] preserves unrecognised keys; see [Manifest.unknownFields].
 */
@Serializable
data class Part(
    val id: String,
    val name: String,
    val pageOrder: List<String>,
    @Transient
    val unknownFields: Map<String, JsonElement> = emptyMap(),
)

/** JSON object keys [Part] models directly; everything else is an "unknown field". */
private val KNOWN_PART_KEYS = setOf("id", "name", "pageOrder")

/** Reads and writes [Part] as JSON, preserving unrecognised keys (same approach as [ManifestJson]). */
object PartJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    /** Parses [text] as a v1 `part.json`, preserving any fields this model doesn't recognise. */
    fun decode(text: String): Part {
        val root = json.parseToJsonElement(text).jsonObject
        val known = json.decodeFromJsonElement<Part>(root)
        val unknown = root.filterKeys { it !in KNOWN_PART_KEYS }
        return known.copy(unknownFields = unknown)
    }

    /** Serialises [part] back to `part.json` text, re-including any preserved unknown fields. */
    fun encode(part: Part): String {
        val knownElement = json.encodeToJsonElement(part).jsonObject
        val merged =
            buildJsonObject {
                part.unknownFields.forEach { (key, value) -> put(key, value) }
                knownElement.forEach { (key, value) -> put(key, value) }
            }
        return json.encodeToString(JsonObject.serializer(), merged)
    }
}
