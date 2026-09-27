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

/** The editing tool [AnnotationOverlay] is in. [VIEW] is plain viewing, the only mode where page-turn gestures are active. */
enum class AnnotationMode { VIEW, PEN, HIGHLIGHT, STAMP, TEXT, SELECT }

/**
 * The stamp symbols offered. Drawn as vector shapes (forte/piano as plain letters) rather than Unicode
 * musical-symbol glyphs, which lie outside the Basic Multilingual Plane and show as missing-glyph boxes on many
 * default fonts without bundling one.
 */
val STAMP_PALETTE: List<String> = listOf("fermata", "accent", "staccato", "forte", "piano", "repeat")

/**
 * The vector annotation layer for one page, drawn over [ViewerScreen]'s page bitmap. Sized with `aspectRatio` to
 * match [pageMeta] exactly, so its on-screen size is the rendered page size and [PagePointSpace]'s pixel/point
 * conversion needs no letterbox offset.
 *
 * Outside [AnnotationMode.VIEW], [ViewerScreen] disables swipe and tap-zone page turns, so every gesture over the
 * page annotates and none turns the page by accident; the toolbar's prev/next buttons still work.
 *
 * Drawing and hit-testing go through [AnnotationSpatialIndex] rather than iterating [layer]'s lists
 * (`docs/performance.md`).
 *
 * @param onCommit called once per completed edit (finished stroke, placed stamp, finished drag) with the new full
 *   layer; in-progress gestures are only previewed locally, so they never become undo steps.
 * @param onTextPlaceRequested called in [AnnotationMode.TEXT] with the page-point position of a tap; the
 *   text-entry dialog lives in [ViewerScreen].
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

    // In-progress gesture previews, in pixels; written into `layer` only when the gesture ends.
    var pendingStrokePx by remember(layer, mode) { mutableStateOf<List<Offset>?>(null) }
    var pendingHighlightPx by remember(layer, mode) { mutableStateOf<Pair<Offset, Offset>?>(null) }

    // The dragged item and its pre-drag geometry, so onDrag computes an absolute position from the total
    // delta instead of accumulating per-step rounding errors.
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
                            // pixelToPoint is a pure scale (no translation), so converting a delta like a position is exact.
                            val (dx, dy) = toPoint(delta)
                            onCommitState.value(moveItem(layer, targetId, dx, dy))
                        }
                    },
                ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // One uniform scale for both axes, also used to size stroke widths and text so they stay proportional
            // to the page whatever its rendered size.
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

/** Installs the one gesture recognizer for [mode], so gestures never compete for the same pointer stream. */
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
        AnnotationMode.VIEW -> {
            this
        }

        AnnotationMode.PEN -> {
            pointerInput(mode) {
                detectDragGestures(
                    onDragStart = onPenDragStart,
                    onDrag = { change, _ -> onPenDrag(change.position) },
                    onDragEnd = onPenDragEnd,
                )
            }
        }

        AnnotationMode.HIGHLIGHT -> {
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
        }

        AnnotationMode.STAMP -> {
            pointerInput(mode) { detectTapGestures(onTap = onStampTap) }
        }

        AnnotationMode.TEXT -> {
            pointerInput(mode) { detectTapGestures(onTap = onTextTap) }
        }

        AnnotationMode.SELECT -> {
            pointerInput(mode) {
                detectDragGestures(
                    onDragStart = onSelectDragStart,
                    onDrag = { change, dragAmount -> onSelectDrag(dragAmount) },
                    onDragEnd = onSelectDragEnd,
                )
            }
        }
    }

/**
 * Returns [layer] with the stamp, text note or highlight [itemId] moved by ([dxPt], [dyPt]) page points. Strokes
 * can't be moved; for a stroke id, [layer] is returned unchanged.
 */
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

/** Returns [layer] with stamp [stampId] scaled by [factor] (e.g. 1.1 to grow); driven by toolbar buttons, not pinch. */
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

        is AnnotationItem.StampItem -> {
            drawStamp(item.stamp, toPixel, textMeasurer)
        }

        is AnnotationItem.TextNoteItem -> {
            val topLeft = toPixel(item.textNote.x, item.textNote.y)
            // fontSizePt is in page points: scale to pixels via pxPerPt, then to sp, since TextStyle takes sp.
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

/** Draws one stamp as a vector shape, or plain text for forte/piano (see [STAMP_PALETTE]). */
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

        "staccato" -> {
            drawCircle(color = Color.Black, radius = extentPx * 0.25f, center = center)
        }

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

        // "forte"/"piano" and anything unrecognised: a plain Latin letter, which every default font can render.
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
        6 -> {
            Color(
                red = clean.substring(0, 2).toInt(16) / 255f,
                green = clean.substring(2, 4).toInt(16) / 255f,
                blue = clean.substring(4, 6).toInt(16) / 255f,
                alpha = 1f,
            )
        }

        8 -> {
            Color(
                red = clean.substring(0, 2).toInt(16) / 255f,
                green = clean.substring(2, 4).toInt(16) / 255f,
                blue = clean.substring(4, 6).toInt(16) / 255f,
                alpha = clean.substring(6, 8).toInt(16) / 255f,
            )
        }

        else -> {
            Color.Black
        }
    }
}
