package app.inkstave.shared.annotation

import app.inkstave.shared.format.Highlight
import app.inkstave.shared.format.Stamp
import app.inkstave.shared.format.Stroke
import app.inkstave.shared.format.TextNote
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/performance.md`'s M2 QA gate ("pan/zoom on a page with several
 * hundred annotation objects holds native frame rate, no visible stutter")
 * stated as a frame-rate target, but literal frame-timing isn't
 * meaningfully automatable in a headless test. **This is the honest
 * automated proxy, not a literal frame-rate measurement**: it asserts
 * [AnnotationSpatialIndex.query]/[hitTest] stay fast and, separately and
 * just as importantly, stay *correct* under a stress-sized dataset --
 * don't read a green run of this test as "M2 hits 60fps," only as "the
 * spatial index that viewport culling and hit-testing depend on doesn't
 * degrade to a linear scan as annotation count grows."
 *
 * Budget: **2ms per query, averaged over many queries.** Justification: a
 * 60fps frame has a ~16.7ms budget; a real frame does a query (or two --
 * culling plus a possible hit-test) plus everything else (layout, drawing,
 * page-bitmap composition), so the query itself needs to be a small
 * fraction of that, not most of it. 2ms leaves roughly 8x headroom under a
 * single frame's total budget even in the worst case of this being the
 * only thing happening in a frame -- comfortable margin for a CI machine
 * slower than a dev workstation, without being so loose the test stops
 * meaning anything.
 */
class AnnotationSpatialIndexPerformanceTest {
    /** A deterministic (fixed-seed) but realistically-scattered stress dataset, mixing all four annotation kinds. */
    private fun stressItems(count: Int): List<AnnotationItem> {
        val random = Random(42)
        return (0 until count).map { i ->
            when (i % 4) {
                0 ->
                    AnnotationItem.StampItem(
                        Stamp(
                            id = "stamp-$i",
                            symbol = "accent",
                            x = random.nextDouble(1000.0),
                            y = random.nextDouble(1000.0),
                            scale = 1.0,
                            rotationDeg = 0.0,
                        ),
                    )
                1 -> {
                    val x = random.nextDouble(1000.0)
                    val y = random.nextDouble(1000.0)
                    AnnotationItem.HighlightItem(
                        Highlight(id = "highlight-$i", rectPt = listOf(x, y, x + 20.0, y + 10.0), color = "#FFFF0080"),
                    )
                }
                2 -> {
                    val x = random.nextDouble(1000.0)
                    val y = random.nextDouble(1000.0)
                    AnnotationItem.StrokeItem(
                        Stroke(
                            id = "stroke-$i",
                            points = listOf(listOf(x, y), listOf(x + 5.0, y + 5.0), listOf(x + 10.0, y)),
                            color = "#000000",
                            widthPt = 1.0,
                        ),
                    )
                }
                else ->
                    AnnotationItem.TextNoteItem(
                        TextNote(
                            id = "text-$i",
                            x = random.nextDouble(1000.0),
                            y = random.nextDouble(1000.0),
                            text = "note",
                            fontSizePt = 8.0,
                        ),
                    )
            }
        }
    }

    @Test
    fun `query and hitTest stay well within budget on a 500-plus item stress page`() {
        val items = stressItems(600)
        val index = AnnotationSpatialIndex.build(items)
        val random = Random(7)

        val queryNanos =
            LongArray(2000) {
                val x = random.nextDouble(900.0)
                val y = random.nextDouble(900.0)
                val viewport = AnnoBounds(x, y, x + 100.0, y + 100.0)
                val start = System.nanoTime()
                index.query(viewport)
                System.nanoTime() - start
            }
        val hitTestNanos =
            LongArray(2000) {
                val x = random.nextDouble(1000.0)
                val y = random.nextDouble(1000.0)
                val start = System.nanoTime()
                index.hitTest(x, y)
                System.nanoTime() - start
            }

        val avgQueryMs = queryNanos.average() / 1_000_000.0
        val avgHitTestMs = hitTestNanos.average() / 1_000_000.0
        println(
            "AnnotationSpatialIndex stress (600 items): avg query = ${"%.4f".format(
                avgQueryMs,
            )}ms, avg hitTest = ${"%.4f".format(avgHitTestMs)}ms over 2000 iterations each",
        )

        assertTrue(avgQueryMs < 2.0, "average query time ${avgQueryMs}ms exceeded the 2ms budget")
        assertTrue(avgHitTestMs < 2.0, "average hitTest time ${avgHitTestMs}ms exceeded the 2ms budget")
    }

    @Test
    fun `query on a stress dataset still returns exactly the correct items, not just fast ones`() {
        val items = stressItems(600)
        val index = AnnotationSpatialIndex.build(items)
        val queryBounds = AnnoBounds(200.0, 200.0, 350.0, 350.0)

        val indexed = index.query(queryBounds).map { it.id }.toSet()
        val bruteForce = items.filter { it.bounds.intersects(queryBounds) }.map { it.id }.toSet()

        assertEquals(
            bruteForce,
            indexed,
            "the index must return exactly the same set a brute-force scan would -- speed without correctness is worthless",
        )
    }
}
