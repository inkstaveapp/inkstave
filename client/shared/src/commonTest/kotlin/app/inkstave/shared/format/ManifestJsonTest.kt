package app.inkstave.shared.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ManifestJson] -- the "spec / contract" level test
 * `docs/testing-strategy.md` calls for on `format/`'s field validation and
 * round-trip behaviour.
 */
class ManifestJsonTest {
    /** Mirrors `format/fixtures/manifest.v1.json`, with one field this model
     * doesn't know about (`futureField`) added to prove forward-compatible
     * preservation actually works, not just that the happy path parses. */
    private val fixtureWithUnknownField =
        """
        {
          "formatVersion": 1,
          "id": "11111111-1111-1111-1111-111111111111",
          "title": "Clair de Lune",
          "subtitle": null,
          "composer": "Claude Debussy",
          "arranger": null,
          "lyricist": null,
          "genre": "Classical",
          "tags": ["piano", "impressionist"],
          "parts": ["part-piano"],
          "createdAt": "2026-09-23T00:00:00Z",
          "modifiedAt": "2026-09-23T00:00:00Z",
          "source": { "type": "pdf-import", "details": { "originalFilename": "clair-de-lune.pdf" } },
          "futureField": { "nested": "value-from-a-newer-client" }
        }
        """.trimIndent()

    @Test
    fun `decode reads known fields correctly`() {
        val manifest = ManifestJson.decode(fixtureWithUnknownField)

        assertEquals(1, manifest.formatVersion)
        assertEquals("Clair de Lune", manifest.title)
        assertEquals("Claude Debussy", manifest.composer)
        assertEquals(listOf("piano", "impressionist"), manifest.tags)
        assertEquals("pdf-import", manifest.source.type)
    }

    @Test
    fun `decode preserves fields the model does not recognise`() {
        val manifest = ManifestJson.decode(fixtureWithUnknownField)

        assertTrue("futureField" in manifest.unknownFields)
    }

    @Test
    fun `encode round-trips an unknown field back into the JSON`() {
        val manifest = ManifestJson.decode(fixtureWithUnknownField)

        val reEncoded = ManifestJson.encode(manifest)
        val reparsed = Json.parseToJsonElement(reEncoded).jsonObject

        assertTrue("futureField" in reparsed, "unknown field must survive a decode-then-encode round trip")
        assertEquals("Clair de Lune", reparsed.getValue("title").jsonPrimitive.content)
    }
}
