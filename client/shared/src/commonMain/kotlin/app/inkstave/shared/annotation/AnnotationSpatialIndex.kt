package app.inkstave.shared.annotation

import kotlin.math.floor

/**
 * A uniform-grid spatial index over one page's [AnnotationItem]s, so viewport culling ([query])
 * and tap-to-select ([hitTest]) don't scan every item (`docs/performance.md`).
 *
 * A grid rather than a quad-tree: pages hold hundreds of items, not millions, and a grid is
 * simpler. Each item is stored in every cell its bounds overlap.
 */
class AnnotationSpatialIndex private constructor(
    private val cellSizePt: Double,
    private val buckets: Map<Pair<Int, Int>, List<AnnotationItem>>,
    /** Every item this index was built from, in z-order (see [AnnotationItem]). */
    val items: List<AnnotationItem>,
) {
    // id -> position in `items`, so hitTest's z-order tie-break is a lookup, not a scan.
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
     * The topmost item whose bounds contain ([x], [y]), or `null`. Checking only the point's own
     * cell is enough, because every item is stored in every cell its bounds overlap.
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
        /** A tenth of the page height, so most items fall into one or a few cells. Not yet tuned. */
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
