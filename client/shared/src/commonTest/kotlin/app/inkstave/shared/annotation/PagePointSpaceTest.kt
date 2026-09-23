package app.inkstave.shared.annotation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PagePointSpaceTest {
    @Test
    fun `canvasWidthPt preserves the page's pixel aspect ratio`() {
        // A 2:1 landscape page -> a 2000x1000pt canvas.
        assertEquals(2000.0, PagePointSpace.canvasWidthPt(pageWidthPx = 200, pageHeightPx = 100))
        // A square page -> a square canvas.
        assertEquals(1000.0, PagePointSpace.canvasWidthPt(pageWidthPx = 100, pageHeightPx = 100))
    }

    @Test
    fun `canvasWidthPt rejects a non-positive height`() {
        assertFailsWith<IllegalArgumentException> { PagePointSpace.canvasWidthPt(pageWidthPx = 100, pageHeightPx = 0) }
    }

    @Test
    fun `pixelToPoint and pointToPixel are inverses`() {
        val (ptX, ptY) = PagePointSpace.pixelToPoint(px = 150.0, py = 300.0, renderedWidthPx = 600.0, renderedHeightPx = 800.0)
        val (px, py) = PagePointSpace.pointToPixel(ptX, ptY, renderedWidthPx = 600.0, renderedHeightPx = 800.0)

        assertEquals(150.0, px, absoluteTolerance = 1e-9)
        assertEquals(300.0, py, absoluteTolerance = 1e-9)
    }

    @Test
    fun `pixelToPoint scales uniformly, independent of rendered pixel resolution`() {
        // The center of a page rendered small and the center of the same page
        // rendered large must both map to the canvas's own center point --
        // that's the entire point of a resolution-independent coordinate space.
        val small = PagePointSpace.pixelToPoint(px = 100.0, py = 100.0, renderedWidthPx = 200.0, renderedHeightPx = 200.0)
        val large = PagePointSpace.pixelToPoint(px = 1000.0, py = 1000.0, renderedWidthPx = 2000.0, renderedHeightPx = 2000.0)

        assertEquals(small, large)
        assertEquals(PagePointSpace.CANVAS_HEIGHT_PT / 2, small.second)
    }

    private fun assertEquals(
        expected: Double,
        actual: Double,
        absoluteTolerance: Double,
    ) {
        kotlin.test.assertTrue(
            kotlin.math.abs(expected - actual) <= absoluteTolerance,
            "expected $expected, got $actual (tolerance $absoluteTolerance)",
        )
    }
}
