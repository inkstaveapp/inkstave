package app.inkstave.shared.annotation

/**
 * Defines the "normalized page-point space" `docs/format-spec.md`'s
 * `annotations/<page-id>.json` section specifies but doesn't pin an exact
 * scale for -- it says only that annotation coordinates are "independent of
 * the rendered raster's pixel dimensions." A concrete scale is needed to
 * actually convert a pointer event's pixel offset into page-point
 * coordinates (and back, to draw), so this object resolves that gap: **a
 * fixed virtual canvas [CANVAS_HEIGHT_PT] points tall**, with width derived
 * from the page's own pixel aspect ratio ([canvasWidthPt]) so it matches
 * each page's actual proportions rather than assuming a fixed real-world
 * paper size (which would be wrong for `aspectRatioClass == "custom"`
 * pages -- the common case until M4's normalization exists).
 *
 * This keeps a stroke's `widthPt`/a text note's `fontSizePt` visually
 * consistent as a fraction of page height across differently-sized source
 * images, and -- because it's derived purely from [pageWidthPx]/[pageHeightPx],
 * never from a rendered view's on-screen pixel size -- the same annotation
 * layer produces the same page-point coordinates regardless of which
 * device or window size it was authored on, satisfying the format spec's
 * actual requirement even though the spec itself doesn't state the exact
 * unit. If this resolution ever needs revisiting, it belongs in
 * `docs/format-spec.md` as an explicit addition, not just here.
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
     * Converts a point at ([px], [py]) within a page rendered at
     * [renderedWidthPx] x [renderedHeightPx] (the on-screen size of the page
     * content itself -- not the containing view, which may be larger if
     * letterboxed) into page-point coordinates.
     *
     * Deliberately uses one uniform scale (derived from height alone) for
     * both axes rather than scaling x and y independently: that's what
     * keeps a stroke or stamp's proportions undistorted. [renderedWidthPx]
     * is still taken as a parameter -- unused in the current calculation --
     * so the call site always states the actual rendered width it's
     * converting against, in case a future caller ever renders a page at a
     * size that doesn't preserve its own aspect ratio (which would be a bug
     * elsewhere, not something this function should silently compensate
     * for by scaling axes unevenly).
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
