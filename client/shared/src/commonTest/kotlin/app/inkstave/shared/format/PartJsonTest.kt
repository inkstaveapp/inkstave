package app.inkstave.shared.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [PartJson] -- mirrors [ManifestJsonTest]'s round-trip/unknown-field coverage. */
class PartJsonTest {
    /** Mirrors `format/fixtures/part.v1.json` with an added unknown field. */
    private val fixtureWithUnknownField =
        """
        {
          "id": "part-piano",
          "name": "Piano",
          "pageOrder": ["page-1", "page-2", "page-3"],
          "futureField": "value-from-a-newer-client"
        }
        """.trimIndent()

    @Test
    fun `decode reads known fields correctly`() {
        val part = PartJson.decode(fixtureWithUnknownField)

        assertEquals("part-piano", part.id)
        assertEquals("Piano", part.name)
        assertEquals(listOf("page-1", "page-2", "page-3"), part.pageOrder)
    }

    @Test
    fun `decode preserves fields the model does not recognise`() {
        val part = PartJson.decode(fixtureWithUnknownField)

        assertTrue("futureField" in part.unknownFields)
    }

    @Test
    fun `encode round-trips an unknown field back into the JSON`() {
        val part = PartJson.decode(fixtureWithUnknownField)

        val reparsed = Json.parseToJsonElement(PartJson.encode(part)).jsonObject

        assertTrue("futureField" in reparsed, "unknown field must survive a decode-then-encode round trip")
    }
}
