package app.inkstave.shared.annotation

import app.inkstave.shared.format.Highlight
import app.inkstave.shared.format.Stamp
import app.inkstave.shared.format.TextNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correctness tests for [AnnotationSpatialIndex] against a small, exactly
 * -known dataset (the large-scale stress/timing-budget test lives in
 * `jvmCommonTest`, since it needs JVM timing APIs -- see
 * `AnnotationSpatialIndexPerformanceTest`).
 */
class AnnotationSpatialIndexTest {
    private val near = AnnotationItem.StampItem(Stamp(id = "near", symbol = "accent", x = 50.0, y = 50.0, scale = 1.0, rotationDeg = 0.0))
    private val far = AnnotationItem.StampItem(Stamp(id = "far", symbol = "accent", x = 900.0, y = 900.0, scale = 1.0, rotationDeg = 0.0))
    private val wideHighlight =
        AnnotationItem.HighlightItem(
            Highlight(id = "wide", rectPt = listOf(0.0, 400.0, 999.0, 420.0), color = "#FFFF00"),
        )
    private val index = AnnotationSpatialIndex.build(listOf(near, far, wideHighlight))

    @Test
    fun `query returns exactly the items whose bounds intersect the query region, no more and no less`() {
        val result = index.query(AnnoBounds(0.0, 0.0, 100.0, 100.0))

        assertEquals(setOf("near"), result.map { it.id }.toSet())
    }

    @Test
    fun `query against the far corner does not return items near the origin`() {
        val result = index.query(AnnoBounds(850.0, 850.0, 950.0, 950.0))

        assertEquals(setOf("far"), result.map { it.id }.toSet())
    }

    @Test
    fun `an item spanning many grid cells is returned exactly once, not once per cell`() {
        val result = index.query(AnnoBounds(0.0, 405.0, 999.0, 415.0))

        assertEquals(1, result.count { it.id == "wide" }, "a wide item must not be duplicated across the cells it spans")
    }

    @Test
    fun `hitTest finds the item whose bounds actually contain the point`() {
        assertEquals("near", index.hitTest(50.0, 50.0)?.id)
        assertEquals("far", index.hitTest(900.0, 900.0)?.id)
    }

    @Test
    fun `hitTest returns null where nothing is present`() {
        assertNull(index.hitTest(500.0, 100.0))
    }

    @Test
    fun `hitTest picks the topmost item by z-order when several overlap the same point`() {
        val overlappingHighlight =
            AnnotationItem.HighlightItem(
                Highlight(id = "bottom", rectPt = listOf(0.0, 0.0, 200.0, 200.0), color = "#00FF00"),
            )
        val overlappingTextNote = AnnotationItem.TextNoteItem(TextNote(id = "top", x = 10.0, y = 10.0, text = "on top", fontSizePt = 12.0))
        // AnnotationItem's documented z-order is highlight < stroke < stamp < text note,
        // so with both covering (10, 10), the text note must win.
        val overlapIndex = AnnotationSpatialIndex.build(listOf(overlappingHighlight, overlappingTextNote))

        assertEquals("top", overlapIndex.hitTest(15.0, 15.0)?.id)
    }

    @Test
    fun `items exposes every item the index was built from`() {
        assertTrue(index.items.map { it.id }.toSet() == setOf("near", "far", "wide"))
    }
}
