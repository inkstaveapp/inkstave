package app.inkstave.shared.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [PageMetaJson] -- mirrors [ManifestJsonTest]'s round-trip/unknown-field coverage. */
class PageMetaJsonTest {
    /** Mirrors `format/fixtures/page-meta.v1.json` (fully populated, as M4's pipeline would produce). */
    private val fullyPopulatedFixture =
        """
        {
          "id": "page-1",
          "width": 2480,
          "height": 3508,
          "aspectRatioClass": "a4",
          "processing": {
            "cropPolygon": [[12.0, 8.0], [2468.0, 10.0]],
            "dewarpMeshVersion": "v1",
            "contrastMethod": "adaptive-threshold"
          },
          "ocr": {
            "engineVersion": "tesseract-5.4.0",
            "candidates": { "title": "Clair de Lune" },
            "confidence": { "title": 0.92 }
          },
          "futureField": "value-from-a-newer-client"
        }
        """.trimIndent()

    /** An M1-imported page: no pipeline has run yet, so `processing`/`ocr` are absent. */
    private val m1ImportedFixture =
        """
        {
          "id": "page-1",
          "width": 1240,
          "height": 1754,
          "aspectRatioClass": "custom"
        }
        """.trimIndent()

    @Test
    fun `decode reads a fully populated page meta correctly`() {
        val meta = PageMetaJson.decode(fullyPopulatedFixture)

        assertEquals("page-1", meta.id)
        assertEquals("a4", meta.aspectRatioClass)
        assertEquals("v1", meta.processing?.dewarpMeshVersion)
        assertEquals("Clair de Lune", meta.ocr?.candidates?.get("title"))
    }

    @Test
    fun `decode handles an M1-imported page with no processing or ocr yet`() {
        val meta = PageMetaJson.decode(m1ImportedFixture)

        assertEquals("custom", meta.aspectRatioClass)
        assertNull(meta.processing)
        assertNull(meta.ocr)
    }

    @Test
    fun `decode preserves fields the model does not recognise`() {
        val meta = PageMetaJson.decode(fullyPopulatedFixture)

        assertTrue("futureField" in meta.unknownFields)
    }

    @Test
    fun `encode round-trips an unknown field back into the JSON`() {
        val meta = PageMetaJson.decode(fullyPopulatedFixture)

        val reparsed = Json.parseToJsonElement(PageMetaJson.encode(meta)).jsonObject

        assertTrue("futureField" in reparsed, "unknown field must survive a decode-then-encode round trip")
    }
}
