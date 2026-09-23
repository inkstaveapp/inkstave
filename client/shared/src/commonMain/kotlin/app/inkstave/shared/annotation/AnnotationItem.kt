package app.inkstave.shared.annotation

import app.inkstave.shared.format.AnnotationLayer
import app.inkstave.shared.format.Highlight
import app.inkstave.shared.format.Stamp
import app.inkstave.shared.format.Stroke
import app.inkstave.shared.format.TextNote
import kotlin.math.max
import kotlin.math.min

/** An axis-aligned bounding box in page-point space (`PagePointSpace`). */
data class AnnoBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    init {
        require(minX <= maxX && minY <= maxY) { "invalid bounds: ($minX,$minY)-($maxX,$maxY)" }
    }

    fun intersects(other: AnnoBounds): Boolean = minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY

    fun contains(
        x: Double,
        y: Double,
    ): Boolean = x in minX..maxX && y in minY..maxY

    /** Expands these bounds by [margin] on every side -- used to give small/zero-area items (a stroke point, a tap) a hit-testable extent. */
    fun expanded(margin: Double): AnnoBounds = AnnoBounds(minX - margin, minY - margin, maxX + margin, maxY + margin)
}

/**
 * One annotation object, wrapped with the [AnnoBounds] [AnnotationSpatialIndex]
 * needs -- kept separate from the four `format.*` data classes themselves
 * (rather than having e.g. `Stamp` implement an interface) so the format
 * models stay plain data with no rendering/indexing concerns baked in.
 *
 * Z-order (bottom to top, for both drawing and [AnnotationSpatialIndex.hitTest]'s
 * "topmost wins" tie-break) is [AnnotationLayer]'s own field order:
 * highlights first (background washes), then strokes, then stamps, then
 * text notes last -- a defensible authoring convention (text should be the
 * easiest thing to grab back out) documented here since the format itself
 * doesn't mandate a z-order.
 */
sealed interface AnnotationItem {
    val id: String
    val bounds: AnnoBounds

    data class HighlightItem(
        val highlight: Highlight,
    ) : AnnotationItem {
        override val id get() = highlight.id
        override val bounds: AnnoBounds
            get() {
                require(highlight.rectPt.size == 4) { "Highlight.rectPt must have exactly 4 elements, had ${highlight.rectPt.size}" }
                val (x1, y1, x2, y2) = highlight.rectPt.let { Quad(it[0], it[1], it[2], it[3]) }
                return AnnoBounds(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
            }
    }

    data class StrokeItem(
        val stroke: Stroke,
    ) : AnnotationItem {
        override val id get() = stroke.id
        override val bounds: AnnoBounds
            get() {
                val xs = stroke.points.map { it[0] }
                val ys = stroke.points.map { it[1] }
                val halfWidth = stroke.widthPt / 2
                return AnnoBounds(xs.min() - halfWidth, ys.min() - halfWidth, xs.max() + halfWidth, ys.max() + halfWidth)
            }
    }

    data class StampItem(
        val stamp: Stamp,
    ) : AnnotationItem {
        override val id get() = stamp.id

        // Stamps are drawn at a fixed base half-extent (see AnnotationOverlay's
        // STAMP_BASE_HALF_EXTENT_PT, which this must stay consistent with),
        // scaled by `stamp.scale`. Rotation isn't accounted for in the bounds
        // (a tight rotated-rect bound), which makes bounds a conservative
        // over-estimate for a rotated stamp -- correct for culling (draws a
        // superset of what's needed, never misses anything) and for hit-testing
        // (slightly generous hit area, an acceptable UX trade for M2's simplicity).
        override val bounds: AnnoBounds
            get() {
                val halfExtent = STAMP_BASE_HALF_EXTENT_PT * stamp.scale
                return AnnoBounds(stamp.x - halfExtent, stamp.y - halfExtent, stamp.x + halfExtent, stamp.y + halfExtent)
            }

        companion object {
            const val STAMP_BASE_HALF_EXTENT_PT = 20.0
        }
    }

    data class TextNoteItem(
        val textNote: TextNote,
    ) : AnnotationItem {
        override val id get() = textNote.id

        // Text width isn't known without measuring the actual glyphs (a UI-layer
        // concern), so this approximates using a fixed characters-per-fontSizePt
        // ratio -- generous enough that real hit-testing/culling won't clip a
        // typical short annotation's actual rendered extent. Long text notes may
        // have a slightly tight bound; acceptable for M2, revisit if it's a real
        // problem once real usage shows it.
        override val bounds: AnnoBounds
            get() {
                val approxCharWidth = textNote.fontSizePt * 0.6
                val width = max(textNote.text.length * approxCharWidth, textNote.fontSizePt)
                val height = textNote.fontSizePt * 1.4
                return AnnoBounds(textNote.x, textNote.y, textNote.x + width, textNote.y + height)
            }
    }

    companion object {
        /** All items in [layer], in the z-order documented on this interface. */
        fun allFrom(layer: AnnotationLayer): List<AnnotationItem> =
            layer.highlights.map(::HighlightItem) +
                layer.strokes.map(::StrokeItem) +
                layer.stamps.map(::StampItem) +
                layer.textNotes.map(::TextNoteItem)
    }
}

/** A plain 4-tuple, local to this file -- avoids pulling in a Pair-of-Pairs or a fifth data class just to destructure `rectPt`. */
private data class Quad(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
)
