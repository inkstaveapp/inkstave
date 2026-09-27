package app.inkstave.shared.annotation

/**
 * The annotation coordinate space. The format spec only requires coordinates independent of the
 * raster's pixel size; this fixes the scale: every page is [CANVAS_HEIGHT_PT] points tall, with
 * width following the page's own aspect ratio ([canvasWidthPt]).
 *
 * Coordinates therefore don't depend on the source image size or on the device an annotation was
 * made on. Changing this scale is a format change and belongs in `docs/format-spec.md`.
 */
object PagePointSpace {
    /** The page's height in page-point units, by definition, regardless of aspect ratio. */
    const val CANVAS_HEIGHT_PT: Double = 1000.0

    /** The page's width in page-point units, preserving [pageWidthPx]/[pageHeightPx]'s aspect ratio. */
    fun canvasWidthPt(
        pageWidthPx: Int,
        pageHeightPx: Int,
    ): Double {
        require(pageHeightPx > 0) { "pageHeightPx must be positive, was $pageHeightPx" }
        return CANVAS_HEIGHT_PT * pageWidthPx / pageHeightPx
    }

    /**
     * Converts ([px], [py]) within the rendered page content (not the letterboxed view) to
     * page-point coordinates. Uses one scale, from the height, for both axes so shapes aren't
     * distorted; [renderedWidthPx] is currently unused.
     */
    fun pixelToPoint(
        px: Double,
        py: Double,
        renderedWidthPx: Double,
        renderedHeightPx: Double,
    ): Pair<Double, Double> {
        require(renderedHeightPx > 0) { "renderedHeightPx must be positive, was $renderedHeightPx" }
        val scale = CANVAS_HEIGHT_PT / renderedHeightPx
        return (px * scale) to (py * scale)
    }

    /** The inverse of [pixelToPoint]: page-point coordinates back to a pixel offset for the given rendered size. */
    fun pointToPixel(
        ptX: Double,
        ptY: Double,
        renderedWidthPx: Double,
        renderedHeightPx: Double,
    ): Pair<Double, Double> {
        val scale = renderedHeightPx / CANVAS_HEIGHT_PT
        return (ptX * scale) to (ptY * scale)
    }
}
