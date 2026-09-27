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

    /** Expands these bounds by [margin] on every side, giving small or zero-area items a hit-testable extent. */
    fun expanded(margin: Double): AnnoBounds = AnnoBounds(minX - margin, minY - margin, maxX + margin, maxY + margin)
}

/**
 * One annotation object plus the [AnnoBounds] [AnnotationSpatialIndex] needs. A wrapper, so the
 * `format.*` models stay plain data.
 *
 * Z-order, bottom to top (for drawing and for hit-testing's "topmost wins"): highlights, strokes,
 * stamps, text notes. The format doesn't mandate one; text goes last so it's easiest to grab.
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

        // Must match the size AnnotationOverlay draws stamps at. Rotation is ignored, so bounds
        // over-estimate a rotated stamp: safe for culling, slightly generous for hit-testing.
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

        // Approximated from a fixed character width, since measuring glyphs is a UI concern.
        // Generous for short notes; long notes may get a slightly tight bound.
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

/** A plain 4-tuple for destructuring `rectPt`. */
private data class Quad(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
)
