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
 * The v1 `manifest.json` contained in a `.smpk` score package
 * (see `docs/format-spec.md`, "`manifest.json`" section).
 *
 * Every field mirrors the spec exactly. [unknownFields] carries any JSON
 * object keys this version of the model does not recognise, captured
 * verbatim from the source so they survive a read-modify-write round trip.
 * The format spec requires this ("Unknown fields are always preserved on
 * read-modify-write, never dropped") so that, for example, a future OMR
 * integration can attach data older clients don't understand yet without
 * those clients silently deleting it when they save. Application code
 * should never read or set [unknownFields] itself -- it exists only to be
 * carried through unchanged by [ManifestJson].
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

/**
 * `manifest.json`'s `source` object. [details] is deliberately untyped JSON
 * rather than a sealed hierarchy: its shape depends on [type] and the spec
 * describes it only as "type-specific, e.g. original filename" without
 * enumerating every field yet -- modelling it strictly now would mean
 * guessing at a schema the format doesn't actually define.
 */
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
 * Reads and writes [Manifest] as `manifest.json` text, preserving any JSON
 * object keys this codebase doesn't model yet (see [Manifest.unknownFields]).
 *
 * A plain `@Serializable data class` alone cannot satisfy this:
 * kotlinx.serialization silently drops unrecognised keys on decode, which
 * would violate `docs/format-spec.md`'s forward-compatibility guarantee.
 * This object instead decodes to a raw [JsonObject] first, splits it into
 * known and unknown keys, and re-merges them on encode.
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
