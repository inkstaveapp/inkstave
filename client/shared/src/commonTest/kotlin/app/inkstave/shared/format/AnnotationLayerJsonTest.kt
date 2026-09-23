package app.inkstave.shared.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [AnnotationLayerJson] -- mirrors [PartJsonTest]'s round-trip/unknown-field coverage. */
class AnnotationLayerJsonTest {
    /** Mirrors `format/fixtures/annotations.v1.json` with an added unknown top-level field. */
    private val fixtureWithUnknownField =
        """
        {
          "pageId": "page-1",
          "layerVersion": 1,
          "strokes": [
            { "id": "stroke-1", "points": [[10.5, 20.0], [12.0, 21.5]], "color": "#FF0000", "widthPt": 1.5 }
          ],
          "stamps": [
            { "id": "stamp-1", "symbol": "fermata", "x": 100.0, "y": 50.0, "scale": 1.0, "rotationDeg": 0.0 }
          ],
          "highlights": [
            { "id": "highlight-1", "rectPt": [40.0, 60.0, 120.0, 80.0], "color": "#FFFF0080" }
          ],
          "textNotes": [
            { "id": "note-1", "x": 200.0, "y": 300.0, "text": "watch the pedal here", "fontSizePt": 10.0 }
          ],
          "futureField": "value-from-a-newer-client"
        }
        """.trimIndent()

    @Test
    fun `decode reads known fields correctly`() {
        val layer = AnnotationLayerJson.decode(fixtureWithUnknownField)

        assertEquals("page-1", layer.pageId)
        assertEquals(1, layer.layerVersion)
        assertEquals(1, layer.strokes.size)
        assertEquals("stroke-1", layer.strokes.single().id)
        assertEquals(
            listOf(10.5, 20.0),
            layer.strokes
                .single()
                .points
                .first(),
        )
        assertEquals("fermata", layer.stamps.single().symbol)
        assertEquals(listOf(40.0, 60.0, 120.0, 80.0), layer.highlights.single().rectPt)
        assertEquals("watch the pedal here", layer.textNotes.single().text)
    }

    @Test
    fun `decode of a layer with no annotations round-trips empty lists`() {
        val layer = AnnotationLayerJson.decode("""{"pageId": "page-2", "layerVersion": 1}""")

        assertTrue(layer.strokes.isEmpty())
        assertTrue(layer.stamps.isEmpty())
        assertTrue(layer.highlights.isEmpty())
        assertTrue(layer.textNotes.isEmpty())
    }

    @Test
    fun `decode preserves fields the model does not recognise`() {
        val layer = AnnotationLayerJson.decode(fixtureWithUnknownField)

        assertTrue("futureField" in layer.unknownFields)
    }

    @Test
    fun `encode round-trips an unknown field back into the JSON`() {
        val layer = AnnotationLayerJson.decode(fixtureWithUnknownField)

        val reparsed = Json.parseToJsonElement(AnnotationLayerJson.encode(layer)).jsonObject

        assertTrue("futureField" in reparsed, "unknown field must survive a decode-then-encode round trip")
    }

    @Test
    fun `AnnotationLayer_empty produces an empty layer for the given page`() {
        val empty = AnnotationLayer.empty("page-9")

        assertEquals("page-9", empty.pageId)
        assertEquals(1, empty.layerVersion)
        assertTrue(empty.strokes.isEmpty() && empty.stamps.isEmpty() && empty.highlights.isEmpty() && empty.textNotes.isEmpty())
    }
}
