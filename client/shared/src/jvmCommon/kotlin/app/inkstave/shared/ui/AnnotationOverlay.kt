package app.inkstave.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import app.inkstave.shared.annotation.AnnoBounds
import app.inkstave.shared.annotation.AnnotationItem
import app.inkstave.shared.annotation.AnnotationSpatialIndex
import app.inkstave.shared.annotation.PagePointSpace
import app.inkstave.shared.format.AnnotationLayer
import app.inkstave.shared.format.Highlight
import app.inkstave.shared.format.PageMeta
import app.inkstave.shared.format.Stamp
import app.inkstave.shared.format.Stroke
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

/** The editing tool [AnnotationOverlay] is in. [VIEW] is M1's plain page-viewing behaviour -- unchanged, and the only mode where page-turning gestures are active; see [AnnotationOverlay]'s module doc for why. */
enum class AnnotationMode { VIEW, PEN, HIGHLIGHT, STAMP, TEXT, SELECT }

/** The stamped-symbol palette M2 ships with: common notation marks a musician would actually reach for while marking up a part. Drawn as simple vector shapes (or, for forte/piano, plain Latin letters) rather than Unicode musical-symbol glyphs -- those live outside the Basic Multilingual Plane and render as missing-glyph boxes on many default system fonts, on both Android and desktop, without a bundled font (a new dependency this pass deliberately avoids). Vector-drawn shapes have no font-availability risk at all. */
val STAMP_PALETTE: List<String> = listOf("fermata", "accent", "staccato", "forte", "piano", "repeat")

/**
 * The vector annotation layer for one page (`ROADMAP.md` M2), layered on
 * top of [ViewerScreen]'s page bitmap. Sized via `aspectRatio` to exactly
 * match [pageMeta]'s own proportions -- deliberately, so its measured
 * on-screen size *is* the page content's rendered size with no letterboxing
 * to account for, which is what makes [PagePointSpace]'s pixel<->point
 * conversion correct without extra offset math.
 *
 * **Editing-mode / page-turning interaction, a real UX decision:** while
 * [mode] is anything other than [AnnotationMode.VIEW], the caller
 * ([ViewerScreen]) disables swipe-to-turn and the tap-zone page turn --
 * every pointer gesture over the page is unambiguously for annotating,
 * never accidentally a page turn a drawing gesture happened to resemble.
 * Page turning while editing still works via explicit prev/next buttons in
 * [ViewerScreen]'s toolbar. This trades a small amount of edit-mode
 * convenience for eliminating an entire class of gesture-conflict bugs --
 * the right trade for M2's first pass at this feature.
 *
 * Rendering and hit-testing both go through [AnnotationSpatialIndex] (query
 * for drawing -- currently the whole page, since M2 has no page-zoom
 * feature yet to make a true sub-page viewport meaningful; hitTest for
 * [AnnotationMode.SELECT]) rather than iterating [layer]'s lists directly,
 * per `docs/performance.md`.
 *
 * @param onCommit called once per completed logical edit (a finished
 * stroke, a placed stamp, a finished drag) with the *new* full layer --
 * never for in-progress gesture state, which is drawn as a local preview
 * instead so intermediate drag positions never pollute undo history.
 * @param onTextPlaceRequested called (in [AnnotationMode.TEXT]) with the
 * page-point coordinates a tap requested a new text note at; the actual
 * text-entry dialog lives in [ViewerScreen], which owns dialog state.
 */
@Composable
fun AnnotationOverlay(
    pageMeta: PageMeta,
    layer: AnnotationLayer,
    mode: AnnotationMode,
    stampSymbol: String,
    selectedId: String?,
    onSelectedIdChange: (String?) -> Unit,
    onCommit: (AnnotationLayer) -> Unit,
    onTextPlaceRequested: (x: Double, y: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renderedSize by remember { mutableStateOf(IntSize.Zero) }
    val spatialIndex = remember(layer) { AnnotationSpatialIndex.build(AnnotationItem.allFrom(layer)) }
    val textMeasurer = rememberTextMeasurer()

    // In-progress gesture previews -- pixel-space, never written into `layer` until
    // the gesture ends, so a half-finished stroke never becomes an undo step.
    var pendingStrokePx by remember(layer, mode) { mutableStateOf<List<Offset>?>(null) }
    var pendingHighlightPx by remember(layer, mode) { mutableStateOf<Pair<Offset, Offset>?>(null) }

    // A drag in SELECT mode moves whichever item hitTest found at drag-start; these
    // hold that item's identity and pre-drag geometry so onDrag can compute an
    // absolute new position from the accumulated delta, not drift from per-step
    // rounding by repeatedly adding small deltas to already-converted point values.
    var dragTargetId by remember(layer, mode) { mutableStateOf<String?>(null) }
    var dragAccumulatedPx by remember(layer, mode) { mutableStateOf(Offset.Zero) }

    val onCommitState = rememberUpdatedState(onCommit)
    val onSelectedIdChangeState = rememberUpdatedState(onSelectedIdChange)
    val onTextPlaceRequestedState = rememberUpdatedState(onTextPlaceRequested)

    fun toPoint(offset: Offset): Pair<Double, Double> =
        PagePointSpace.pixelToPoint(offset.x.toDouble(), offset.y.toDouble(), renderedSize.width.toDouble(), renderedSize.height.toDouble())

    fun toPixel(
        x: Double,
        y: Double,
    ): Offset {
        val (px, py) = PagePointSpace.pointToPixel(x, y, renderedSize.width.toDouble(), renderedSize.height.toDouble())
        return Offset(px.toFloat(), py.toFloat())
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { renderedSize = it }
                .pointerInputForMode(
                    mode = mode,
                    onPenDragStart = { pendingStrokePx = listOf(it) },
                    onPenDrag = { point -> pendingStrokePx = (pendingStrokePx ?: emptyList()) + point },
                    onPenDragEnd = {
                        val points = pendingStrokePx
                        pendingStrokePx = null
                        if (points != null && points.size >= 2) {
                            val stroke =
                                Stroke(
                                    id = UUID.randomUUID().toString(),
                                    points = points.map { toPoint(it).toList() },
                                    color = "#FF0000",
                                    widthPt = 2.0,
                                )
                            onCommitState.value(layer.copy(strokes = layer.strokes + stroke))
                        }
                    },
                    onHighlightDragStart = { pendingHighlightPx = it to it },
                    onHighlightDrag = { start, current -> pendingHighlightPx = start to current },
                    onHighlightDragEnd = {
                        val range = pendingHighlightPx
                        pendingHighlightPx = null
                        if (range != null) {
                            val (startPt, endPt) = range
                            val (x1, y1) = toPoint(startPt)
                            val (x2, y2) = toPoint(endPt)
                            if (x1 != x2 || y1 != y2) {
                                val highlight =
                                    Highlight(
                                        id = UUID.randomUUID().toString(),
                                        rectPt = listOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)),
                                        color = "#FFFF0080",
                                    )
                                onCommitState.value(layer.copy(highlights = layer.highlights + highlight))
                            }
                        }
                    },
                    onStampTap = { offset ->
                        val (x, y) = toPoint(offset)
                        val stamp =
                            Stamp(id = UUID.randomUUID().toString(), symbol = stampSymbol, x = x, y = y, scale = 1.0, rotationDeg = 0.0)
                        onCommitState.value(layer.copy(stamps = layer.stamps + stamp))
                    },
                    onTextTap = { offset ->
                        val (x, y) = toPoint(offset)
                        onTextPlaceRequestedState.value(x, y)
                    },
                    onSelectDragStart = { offset ->
                        val (x, y) = toPoint(offset)
                        val hit = spatialIndex.hitTest(x, y)
                        dragTargetId = hit?.id
                        dragAccumulatedPx = Offset.Zero
                        onSelectedIdChangeState.value(hit?.id)
                    },
                    onSelectDrag = { delta -> dragAccumulatedPx += delta },
                    onSelectDragEnd = {
                        val targetId = dragTargetId
                        val delta = dragAccumulatedPx
                        dragTargetId = null
                        dragAccumulatedPx = Offset.Zero
                        if (targetId != null && (delta.x != 0f || delta.y != 0f)) {
                            // pixelToPoint is a pure scale (point = pixel * scale, no translation
                            // term), so converting a *delta* the same way as a position is exact --
                            // toPoint(delta) already equals toPoint(position) - toPoint(origin).
                            val (dx, dy) = toPoint(delta)
                            onCommitState.value(moveItem(layer, targetId, dx, dy))
                        }
                    },
                ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Points-per-pixel is uniform across the canvas (PagePointSpace.pixelToPoint's
            // "one scale for both axes" design) -- this is that same scale factor, needed
            // here (not just for positions) to size stroke widths and text so they stay a
            // consistent fraction of page height regardless of the page's actual rendered
            // pixel size, the same resolution-independence PagePointSpace gives positions.
            val pxPerPt = (renderedSize.height / PagePointSpace.CANVAS_HEIGHT_PT).toFloat()
            val viewport =
                AnnoBounds(0.0, 0.0, PagePointSpace.canvasWidthPt(pageMeta.width, pageMeta.height), PagePointSpace.CANVAS_HEIGHT_PT)
            for (item in spatialIndex.query(viewport)) {
                drawAnnotationItem(item, ::toPixel, textMeasurer, pxPerPt, isSelected = item.id == selectedId)
            }
            pendingHighlightPx?.let { (start, current) ->
                drawRect(
                    color = parseHexColor("#FFFF0080"),
                    topLeft = Offset(min(start.x, current.x), min(start.y, current.y)),
                    size =
                        androidx.compose.ui.geometry
                            .Size(kotlin.math.abs(current.x - start.x), kotlin.math.abs(current.y - start.y)),
                )
            }
            pendingStrokePx?.let { points ->
                if (points.size >= 2) {
                    val path =
                        Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                    drawPath(path, color = parseHexColor("#FF0000"), style = DrawStroke(width = 4f))
                }
            }
        }
    }
}

/** Applies [Modifier.pointerInput] appropriate to [mode] -- one gesture recognizer per mode, never more than one active at once, so gestures never compete for the same pointer stream. */
private fun Modifier.pointerInputForMode(
    mode: AnnotationMode,
    onPenDragStart: (Offset) -> Unit,
    onPenDrag: (Offset) -> Unit,
    onPenDragEnd: () -> Unit,
    onHighlightDragStart: (Offset) -> Unit,
    onHighlightDrag: (Offset, Offset) -> Unit,
    onHighlightDragEnd: () -> Unit,
    onStampTap: (Offset) -> Unit,
    onTextTap: (Offset) -> Unit,
    onSelectDragStart: (Offset) -> Unit,
    onSelectDrag: (Offset) -> Unit,
    onSelectDragEnd: () -> Unit,
): Modifier =
    when (mode) {
        AnnotationMode.VIEW -> this
        AnnotationMode.PEN ->
            pointerInput(mode) {
                detectDragGestures(
                    onDragStart = onPenDragStart,
                    onDrag = { change, _ -> onPenDrag(change.position) },
                    onDragEnd = onPenDragEnd,
                )
            }
        AnnotationMode.HIGHLIGHT ->
            pointerInput(mode) {
                var start = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        start = it
                        onHighlightDragStart(it)
                    },
                    onDrag = { change, _ -> onHighlightDrag(start, change.position) },
                    onDragEnd = onHighlightDragEnd,
                )
            }
        AnnotationMode.STAMP -> pointerInput(mode) { detectTapGestures(onTap = onStampTap) }
        AnnotationMode.TEXT -> pointerInput(mode) { detectTapGestures(onTap = onTextTap) }
        AnnotationMode.SELECT ->
            pointerInput(mode) {
                detectDragGestures(
                    onDragStart = onSelectDragStart,
                    onDrag = { change, dragAmount -> onSelectDrag(dragAmount) },
                    onDragEnd = onSelectDragEnd,
                )
            }
    }

/** Returns [layer] with the item identified by [itemId] moved by ([dxPt], [dyPt]) in page-point space -- a stamp/text note's x/y, or a highlight's whole rect, shifted by that delta. Strokes aren't repositionable in M2 (see `AnnotationOverlay`'s module doc); if [itemId] names one, [layer] is returned unchanged. */
internal fun moveItem(
    layer: AnnotationLayer,
    itemId: String,
    dxPt: Double,
    dyPt: Double,
): AnnotationLayer =
    layer.copy(
        stamps = layer.stamps.map { if (it.id == itemId) it.copy(x = it.x + dxPt, y = it.y + dyPt) else it },
        highlights =
            layer.highlights.map {
                if (it.id ==
                    itemId
                ) {
                    it.copy(rectPt = listOf(it.rectPt[0] + dxPt, it.rectPt[1] + dyPt, it.rectPt[2] + dxPt, it.rectPt[3] + dyPt))
                } else {
                    it
                }
            },
        textNotes = layer.textNotes.map { if (it.id == itemId) it.copy(x = it.x + dxPt, y = it.y + dyPt) else it },
    )

/** Returns [layer] with the item identified by [itemId] removed, whichever list it's in. */
internal fun deleteItem(
    layer: AnnotationLayer,
    itemId: String,
): AnnotationLayer =
    layer.copy(
        strokes = layer.strokes.filterNot { it.id == itemId },
        stamps = layer.stamps.filterNot { it.id == itemId },
        highlights = layer.highlights.filterNot { it.id == itemId },
        textNotes = layer.textNotes.filterNot { it.id == itemId },
    )

/** Returns [layer] with the stamp identified by [stampId] rescaled by [factor] (e.g. 1.1 to grow, 1/1.1 to shrink) -- M2's resize-handle equivalent for stamps (`ROADMAP.md` M2: "resize"), a pair of toolbar buttons rather than a pinch gesture; see `ViewerScreen`'s toolbar. */
internal fun rescaleStamp(
    layer: AnnotationLayer,
    stampId: String,
    factor: Double,
): AnnotationLayer =
    layer.copy(
        stamps =
            layer.stamps.map {
                if (it.id ==
                    stampId
                ) {
                    it.copy(scale = (it.scale * factor).coerceIn(0.2, 6.0))
                } else {
                    it
                }
            },
    )

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotationItem(
    item: AnnotationItem,
    toPixel: (Double, Double) -> Offset,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    pxPerPt: Float,
    isSelected: Boolean,
) {
    when (item) {
        is AnnotationItem.HighlightItem -> {
            val (x1, y1, x2, y2) = item.highlight.rectPt
            val topLeft = toPixel(x1, y1)
            val bottomRight = toPixel(x2, y2)
            drawRect(
                color = parseHexColor(item.highlight.color),
                topLeft = topLeft,
                size =
                    androidx.compose.ui.geometry
                        .Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
            )
        }
        is AnnotationItem.StrokeItem -> {
            val pixelPoints = item.stroke.points.map { toPixel(it[0], it[1]) }
            if (pixelPoints.size >= 2) {
                val path =
                    Path().apply {
                        moveTo(pixelPoints.first().x, pixelPoints.first().y)
                        pixelPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                drawPath(
                    path,
                    color = parseHexColor(item.stroke.color),
                    style = DrawStroke(width = (item.stroke.widthPt * pxPerPt).toFloat()),
                )
            }
        }
        is AnnotationItem.StampItem -> drawStamp(item.stamp, toPixel, textMeasurer)
        is AnnotationItem.TextNoteItem -> {
            val topLeft = toPixel(item.textNote.x, item.textNote.y)
            // fontSizePt is page-point space (same unit as everything else in the layer) --
            // scale to pixels via pxPerPt like any other length, then to sp via density,
            // since TextStyle.fontSize takes sp/em, not raw pixels.
            val fontSizeSp = (item.textNote.fontSizePt * pxPerPt / density).sp
            drawText(textMeasurer, item.textNote.text, topLeft = topLeft, style = TextStyle(fontSize = fontSizeSp, color = Color.Black))
        }
    }
    if (isSelected) {
        val bounds = item.bounds
        val topLeft = toPixel(bounds.minX, bounds.minY)
        val bottomRight = toPixel(bounds.maxX, bounds.maxY)
        drawRect(
            color = Color(0f, 0.5f, 1f, 0.9f),
            topLeft = topLeft,
            size =
                androidx.compose.ui.geometry
                    .Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
            style = DrawStroke(width = 3f),
        )
    }
}

/** Draws one stamp at its position, vector-shape (or, for forte/piano, plain-text) per [STAMP_PALETTE] -- see that constant's doc for why no Unicode musical-symbol glyphs are used. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStamp(
    stamp: Stamp,
    toPixel: (Double, Double) -> Offset,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val center = toPixel(stamp.x, stamp.y)
    val extentPt = AnnotationItem.StampItem.STAMP_BASE_HALF_EXTENT_PT * stamp.scale
    val extentPx = (toPixel(stamp.x + extentPt, stamp.y).x - center.x)
    when (stamp.symbol) {
        "fermata" -> {
            drawArc(
                color = Color.Black,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - extentPx, center.y - extentPx),
                size =
                    androidx.compose.ui.geometry
                        .Size(extentPx * 2, extentPx * 2),
                style = DrawStroke(width = 3f),
            )
            drawCircle(color = Color.Black, radius = extentPx * 0.12f, center = Offset(center.x, center.y - extentPx * 0.4f))
        }
        "accent" -> {
            drawLine(Color.Black, Offset(center.x - extentPx, center.y), Offset(center.x, center.y - extentPx * 0.6f), strokeWidth = 3f)
            drawLine(Color.Black, Offset(center.x, center.y - extentPx * 0.6f), Offset(center.x + extentPx, center.y), strokeWidth = 3f)
        }
        "staccato" -> drawCircle(color = Color.Black, radius = extentPx * 0.25f, center = center)
        "repeat" -> {
            drawLine(Color.Black, Offset(center.x, center.y - extentPx), Offset(center.x, center.y + extentPx), strokeWidth = 4f)
            drawCircle(
                color = Color.Black,
                radius = extentPx * 0.12f,
                center =
                    Offset(
                        center.x + extentPx * 0.35f,
                        center.y - extentPx * 0.35f,
                    ),
            )
            drawCircle(
                color = Color.Black,
                radius = extentPx * 0.12f,
                center =
                    Offset(
                        center.x + extentPx * 0.35f,
                        center.y + extentPx * 0.35f,
                    ),
            )
        }
        // "forte"/"piano", and anything unrecognised: a plain drawn letter -- ordinary
        // Latin letters are in the Basic Multilingual Plane, universally renderable by
        // any default font, no glyph-availability risk (see STAMP_PALETTE's doc).
        else -> {
            val label =
                if (stamp.symbol == "piano") {
                    "p"
                } else if (stamp.symbol == "forte") {
                    "f"
                } else {
                    "?"
                }
            val layoutResult = textMeasurer.measure(label, style = TextStyle(fontSize = (extentPx * 1.4f / density).sp))
            drawText(layoutResult, topLeft = Offset(center.x - layoutResult.size.width / 2f, center.y - layoutResult.size.height / 2f))
        }
    }
}

private fun parseHexColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    return when (clean.length) {
        6 ->
            Color(
                red = clean.substring(0, 2).toInt(16) / 255f,
                green = clean.substring(2, 4).toInt(16) / 255f,
                blue = clean.substring(4, 6).toInt(16) / 255f,
                alpha = 1f,
            )
        8 ->
            Color(
                red = clean.substring(0, 2).toInt(16) / 255f,
                green = clean.substring(2, 4).toInt(16) / 255f,
                blue = clean.substring(4, 6).toInt(16) / 255f,
                alpha = clean.substring(6, 8).toInt(16) / 255f,
            )
        else -> Color.Black
    }
}
