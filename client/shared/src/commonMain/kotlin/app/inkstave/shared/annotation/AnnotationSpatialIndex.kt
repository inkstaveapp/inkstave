package app.inkstave.shared.annotation

import kotlin.math.floor

/**
 * A uniform-grid spatial index over one page's [AnnotationItem]s, built so
 * [query] (viewport culling) and [hitTest] (tap-to-select) are sub-linear
 * in item count rather than an O(n) scan per interaction --
 * `docs/performance.md`'s "hundreds of annotation objects, no visible
 * stutter" requirement.
 *
 * A uniform grid, not a quad-tree, is the deliberate choice here: this
 * project's annotation counts are bounded (hundreds per page, per the
 * performance target -- not the millions of points a quad-tree's adaptive
 * depth earns its complexity for), and a grid's bucket lookup is exact
 * arithmetic with no tree traversal, simpler to reason about and to test.
 * If a page ever needs to scale well past "hundreds," a quad-tree becomes
 * the right trade; not needed for M2.
 *
 * Each item is inserted into every grid cell its [AnnoBounds] overlaps (an
 * item spanning multiple cells appears in each), so both [query] and
 * [hitTest] only ever need to look at the (small, bounded) set of cells the
 * query region touches -- never every item on the page.
 */
class AnnotationSpatialIndex private constructor(
    private val cellSizePt: Double,
    private val buckets: Map<Pair<Int, Int>, List<AnnotationItem>>,
    /** Every item this index was built from, in their original (z-)order -- see [AnnotationItem]'s z-order note. */
    val items: List<AnnotationItem>,
) {
    // Precomputed once, not recomputed per hitTest call: id -> position in `items`,
    // so hitTest's z-order tie-break is an O(1) lookup per candidate rather than an
    // indexOf scan over the whole page's items for every candidate in the cell.
    private val orderById: Map<String, Int> = items.withIndex().associate { (index, item) -> item.id to index }

    /** All items whose [AnnoBounds] intersect [queryBounds], each appearing exactly once even if it spans multiple grid cells. */
    fun query(queryBounds: AnnoBounds): List<AnnotationItem> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<AnnotationItem>()
        for (cell in cellsOverlapping(queryBounds)) {
            for (item in buckets[cell].orEmpty()) {
                if (item.bounds.intersects(queryBounds) && seen.add(item.id)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    /**
     * The topmost (per [AnnotationItem]'s z-order) item whose bounds
     * contain ([x], [y]), or `null` if none do. Only inspects the single
     * grid cell containing the point -- correct, not just fast, because an
     * item is inserted into *every* cell its bounds overlap, so if an
     * item's bounds contain this point, this cell is necessarily one of
     * them.
     */
    fun hitTest(
        x: Double,
        y: Double,
    ): AnnotationItem? {
        val cell = cellOf(x, y)
        var best: AnnotationItem? = null
        var bestOrder = -1
        for (item in buckets[cell].orEmpty()) {
            if (item.bounds.contains(x, y)) {
                val order = orderById.getValue(item.id)
                if (order > bestOrder) {
                    best = item
                    bestOrder = order
                }
            }
        }
        return best
    }

    private fun cellOf(
        x: Double,
        y: Double,
    ): Pair<Int, Int> = Pair(floor(x / cellSizePt).toInt(), floor(y / cellSizePt).toInt())

    private fun cellsOverlapping(bounds: AnnoBounds): List<Pair<Int, Int>> {
        val minCellX = floor(bounds.minX / cellSizePt).toInt()
        val maxCellX = floor(bounds.maxX / cellSizePt).toInt()
        val minCellY = floor(bounds.minY / cellSizePt).toInt()
        val maxCellY = floor(bounds.maxY / cellSizePt).toInt()
        val cells = mutableListOf<Pair<Int, Int>>()
        for (cx in minCellX..maxCellX) {
            for (cy in minCellY..maxCellY) {
                cells.add(Pair(cx, cy))
            }
        }
        return cells
    }

    companion object {
        /**
         * Default grid cell size: `PagePointSpace.CANVAS_HEIGHT_PT / 10`, i.e.
         * a page is roughly a 10x10 grid vertically. Not tuned against real
         * usage data yet (there isn't any) -- a reasonable starting bucket
         * granularity for a page-point space of that scale, coarse enough
         * that most annotation objects (which are small relative to a full
         * page) fall into one or a handful of cells rather than dozens.
         */
        const val DEFAULT_CELL_SIZE_PT = PagePointSpace.CANVAS_HEIGHT_PT / 10

        fun build(
            items: List<AnnotationItem>,
            cellSizePt: Double = DEFAULT_CELL_SIZE_PT,
        ): AnnotationSpatialIndex {
            require(cellSizePt > 0) { "cellSizePt must be positive, was $cellSizePt" }
            val buckets = mutableMapOf<Pair<Int, Int>, MutableList<AnnotationItem>>()
            for (item in items) {
                val b = item.bounds
                val minCellX = floor(b.minX / cellSizePt).toInt()
                val maxCellX = floor(b.maxX / cellSizePt).toInt()
                val minCellY = floor(b.minY / cellSizePt).toInt()
                val maxCellY = floor(b.maxY / cellSizePt).toInt()
                for (cx in minCellX..maxCellX) {
                    for (cy in minCellY..maxCellY) {
                        buckets.getOrPut(Pair(cx, cy)) { mutableListOf() }.add(item)
                    }
                }
            }
            return AnnotationSpatialIndex(cellSizePt, buckets, items)
        }
    }
}
