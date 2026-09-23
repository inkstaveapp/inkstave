package app.inkstave.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.inkstave.shared.annotation.AnnotationHistory
import app.inkstave.shared.format.AnnotationLayer
import app.inkstave.shared.format.PageMeta
import app.inkstave.shared.format.SmpkReader
import app.inkstave.shared.format.SmpkUpdater
import app.inkstave.shared.format.TextNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The score viewer (`ROADMAP.md` M1: "Render pages, swipe/tap/keyboard page
 * turning"; M2: annotation editing). Opens the `.smpk` at [filePath], shows
 * its (single, M1) part's pages full-screen one at a time, and supports
 * three ways to turn pages in [AnnotationMode.VIEW]: swiping (via
 * [HorizontalPager]'s own gesture handling), tapping the left/right thirds
 * of the screen, and desktop arrow keys -- which are a no-op on a
 * touch-only Android device rather than an error, since nothing there ever
 * dispatches a key event.
 *
 * Pages are decoded lazily: only the current page and its immediate
 * neighbours are ever loaded into memory at once, per
 * `docs/format-spec.md`'s per-page-file design and the "load only what's on
 * screen" principle `docs/performance.md` applies to annotations and, here,
 * to page bitmaps too. Page metadata and annotation layers are loaded on
 * the same lazy, per-page-in-view schedule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    filePath: String,
    onBack: () -> Unit,
) {
    val reader = remember(filePath) { SmpkReader(File(filePath)) }
    DisposableEffect(reader) { onDispose { reader.close() } }

    val pageIds =
        remember(reader) {
            val manifest = reader.readManifest()
            reader.readPart(manifest.parts.first()).pageOrder
        }

    val pagerState = rememberPagerState(pageCount = { pageIds.size })
    val bitmaps = remember { mutableStateMapOf<Int, ImageBitmap>() }
    val pageMetas = remember { mutableStateMapOf<Int, PageMeta>() }
    // The observable "what to render" layer per page index -- Compose recomposes on
    // writes to this map. AnnotationHistory instances (below) are plain, unobserved
    // bookkeeping for undo/redo; every operation that changes a page's layer writes
    // the result here too, which is what actually drives recomposition. See
    // ViewerScreen's module doc and AnnotationOverlay's for why the two are kept
    // separate rather than reading straight from AnnotationHistory.current.
    val currentLayers = remember { mutableStateMapOf<Int, AnnotationLayer>() }
    val histories = remember { mutableMapOf<Int, AnnotationHistory>() }
    val saveJobs = remember { mutableMapOf<Int, Job>() }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(AnnotationMode.VIEW) }
    var stampMenuExpanded by remember { mutableStateOf(false) }
    var stampSymbol by remember { mutableStateOf(STAMP_PALETTE.first()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var textDialogRequest by remember { mutableStateOf<TextDialogRequest?>(null) }

    LaunchedEffect(reader, pagerState.currentPage) {
        val lookahead = (pagerState.currentPage - 1)..(pagerState.currentPage + 1)
        for (index in lookahead) {
            if (index !in pageIds.indices) continue
            val pageId = pageIds[index]
            if (!bitmaps.containsKey(index)) {
                val bitmap = withContext(Dispatchers.IO) { decodePageBitmap(reader.readPageBytes(pageId)) }
                bitmaps[index] = bitmap
            }
            if (!pageMetas.containsKey(index)) {
                val meta = withContext(Dispatchers.IO) { reader.readPageMeta(pageId) }
                pageMetas[index] = meta
            }
            if (!histories.containsKey(index)) {
                val layer = withContext(Dispatchers.IO) { reader.readAnnotationLayer(pageId) }
                histories[index] = AnnotationHistory(layer)
                currentLayers[index] = layer
            }
        }
    }

    /** Records [newLayer] as [pageIndex]'s new current layer (both in undo history and for rendering) and schedules a debounced save -- see [SmpkUpdater]'s "one rewrite per logical edit, not per pointer-move" note. */
    fun applyLayer(
        pageIndex: Int,
        newLayer: AnnotationLayer,
    ) {
        val history = histories.getOrPut(pageIndex) { AnnotationHistory(AnnotationLayer.empty(pageIds[pageIndex])) }
        history.push(newLayer)
        currentLayers[pageIndex] = newLayer

        saveJobs[pageIndex]?.cancel()
        saveJobs[pageIndex] =
            scope.launch {
                delay(SAVE_DEBOUNCE_MILLIS)
                withContext(Dispatchers.IO) { SmpkUpdater.updateAnnotationLayer(File(filePath), newLayer) }
            }
    }

    fun undoCurrentPage() {
        val history = histories[pagerState.currentPage] ?: return
        if (history.undo()) currentLayers[pagerState.currentPage] = history.current
    }

    fun redoCurrentPage() {
        val history = histories[pagerState.currentPage] ?: return
        if (history.redo()) currentLayers[pagerState.currentPage] = history.current
    }

    fun goTo(page: Int) {
        val target = page.coerceIn(0, pageIds.lastIndex)
        scope.launch { pagerState.animateScrollToPage(target) }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> {
                            goTo(pagerState.currentPage + 1)
                            true
                        }
                        Key.DirectionLeft -> {
                            goTo(pagerState.currentPage - 1)
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Page turning gestures only in VIEW mode -- see AnnotationOverlay's module
            // doc for why editing modes disable them instead of trying to disambiguate
            // a draw gesture from a swipe.
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = mode == AnnotationMode.VIEW,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val pageMeta = pageMetas[pageIndex]
                    if (pageMeta != null) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize().padding(
                                    if (mode ==
                                        AnnotationMode.VIEW
                                    ) {
                                        0.dp
                                    } else {
                                        48.dp
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .let { m ->
                                            if (pageMeta.width > 0 &&
                                                pageMeta.height > 0
                                            ) {
                                                m.aspectRatio(pageMeta.width.toFloat() / pageMeta.height.toFloat())
                                            } else {
                                                m
                                            }
                                        },
                            ) {
                                PageContent(bitmap = bitmaps[pageIndex])
                                AnnotationOverlay(
                                    pageMeta = pageMeta,
                                    layer = currentLayers[pageIndex] ?: AnnotationLayer.empty(pageIds[pageIndex]),
                                    mode = mode,
                                    stampSymbol = stampSymbol,
                                    selectedId = selectedId,
                                    onSelectedIdChange = { selectedId = it },
                                    onCommit = { newLayer -> applyLayer(pageIndex, newLayer) },
                                    onTextPlaceRequested = { x, y ->
                                        textDialogRequest =
                                            TextDialogRequest(x, y, editingId = null, initialText = "", initialFontSizePt = 14.0)
                                    },
                                )
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            // Tap zones: left/right thirds turn the page (VIEW mode only -- in an
            // editing mode this same screen region is for annotating, per
            // AnnotationOverlay's module doc), alongside HorizontalPager's own swipe.
            if (mode == AnnotationMode.VIEW) {
                Row(modifier = Modifier.fillMaxSize()) {
                    TapZone(weight = 1f, onTap = { goTo(pagerState.currentPage - 1) }, testTag = TestTags.VIEWER_TAP_ZONE_PREVIOUS)
                    TapZone(weight = 1f, onTap = null, testTag = null)
                    TapZone(weight = 1f, onTap = { goTo(pagerState.currentPage + 1) }, testTag = TestTags.VIEWER_TAP_ZONE_NEXT)
                }
            }

            BackButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))

            AnnotationToolbar(
                mode = mode,
                onModeChange = { newMode ->
                    mode = newMode
                    selectedId = null
                },
                stampSymbol = stampSymbol,
                stampMenuExpanded = stampMenuExpanded,
                onStampMenuExpandedChange = { stampMenuExpanded = it },
                onStampSymbolChange = { stampSymbol = it },
                canUndo = histories[pagerState.currentPage]?.canUndo() ?: false,
                canRedo = histories[pagerState.currentPage]?.canRedo() ?: false,
                onUndo = ::undoCurrentPage,
                onRedo = ::redoCurrentPage,
                hasSelection = selectedId != null,
                selectedIsStamp = selectedId?.let { id -> currentLayers[pagerState.currentPage]?.stamps?.any { it.id == id } } ?: false,
                selectedIsTextNote =
                    selectedId?.let { id -> currentLayers[pagerState.currentPage]?.textNotes?.any { it.id == id } } ?: false,
                onDeleteSelected = onDelete@{
                    val id = selectedId ?: return@onDelete
                    val layer = currentLayers[pagerState.currentPage] ?: return@onDelete
                    applyLayer(pagerState.currentPage, deleteItem(layer, id))
                    selectedId = null
                },
                onShrinkSelectedStamp = onShrink@{
                    val id = selectedId ?: return@onShrink
                    val layer = currentLayers[pagerState.currentPage] ?: return@onShrink
                    applyLayer(pagerState.currentPage, rescaleStamp(layer, id, 1 / 1.1))
                },
                onGrowSelectedStamp = onGrow@{
                    val id = selectedId ?: return@onGrow
                    val layer = currentLayers[pagerState.currentPage] ?: return@onGrow
                    applyLayer(pagerState.currentPage, rescaleStamp(layer, id, 1.1))
                },
                onEditSelectedText = onEditText@{
                    val id = selectedId ?: return@onEditText
                    val note = currentLayers[pagerState.currentPage]?.textNotes?.firstOrNull { it.id == id } ?: return@onEditText
                    textDialogRequest =
                        TextDialogRequest(note.x, note.y, editingId = id, initialText = note.text, initialFontSizePt = note.fontSizePt)
                },
                onPrevPage = { goTo(pagerState.currentPage - 1) },
                onNextPage = { goTo(pagerState.currentPage + 1) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )

            // "Page X of Y": a small, genuinely useful M1-scale affordance (most
            // viewers show one) that also doubles as the one reliable signal a UI
            // test can assert on to prove a tap/swipe/key actually changed the
            // displayed page -- HorizontalPager's own internals aren't otherwise
            // observable from outside the composable.
            Text(
                "${pagerState.currentPage + 1} / ${pageIds.size}",
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .testTag(TestTags.VIEWER_PAGE_INDICATOR),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    textDialogRequest?.let { request ->
        TextAnnotationDialog(
            request = request,
            onDismiss = { textDialogRequest = null },
            onConfirm = { text, fontSizePt ->
                val layer = currentLayers[pagerState.currentPage] ?: AnnotationLayer.empty(pageIds[pagerState.currentPage])
                val updated =
                    if (request.editingId != null) {
                        layer.copy(
                            textNotes =
                                layer.textNotes.map {
                                    if (it.id ==
                                        request.editingId
                                    ) {
                                        it.copy(text = text, fontSizePt = fontSizePt)
                                    } else {
                                        it
                                    }
                                },
                        )
                    } else {
                        layer.copy(
                            textNotes =
                                layer.textNotes +
                                    TextNote(
                                        id = UUID.randomUUID().toString(),
                                        x = request.x,
                                        y = request.y,
                                        text = text,
                                        fontSizePt = fontSizePt,
                                    ),
                        )
                    }
                applyLayer(pagerState.currentPage, updated)
                textDialogRequest = null
            },
        )
    }
}

/** How long to wait, after the most recent annotation edit, before actually rewriting the `.smpk` (`SmpkUpdater`'s "one rewrite per logical edit" cost note) -- long enough to coalesce a burst of edits (e.g. several quick taps placing stamps) into one save, short enough that closing the app moments later is very unlikely to race a still-pending save. Not user-configurable; a reasonable fixed default for M2. */
private const val SAVE_DEBOUNCE_MILLIS = 600L

private data class TextDialogRequest(
    val x: Double,
    val y: Double,
    val editingId: String?,
    val initialText: String,
    val initialFontSizePt: Double,
)

@Composable
private fun TextAnnotationDialog(
    request: TextDialogRequest,
    onDismiss: () -> Unit,
    onConfirm: (text: String, fontSizePt: Double) -> Unit,
) {
    var text by remember { mutableStateOf(request.initialText) }
    var fontSizePt by remember { mutableStateOf(request.initialFontSizePt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (request.editingId != null) "Edit text" else "Add text") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = {
                    text = it
                }, label = { Text("Text") }, modifier = Modifier.testTag(TestTags.TEXT_DIALOG_FIELD))
                Row {
                    TextButton(onClick = { fontSizePt = (fontSizePt - 2.0).coerceAtLeast(6.0) }) { Text("A-") }
                    Text("${fontSizePt.toInt()}pt", modifier = Modifier.padding(horizontal = 8.dp))
                    TextButton(onClick = { fontSizePt = (fontSizePt + 2.0).coerceAtMost(72.0) }) { Text("A+") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onConfirm(text, fontSizePt)
            }, modifier = Modifier.testTag(TestTags.TEXT_DIALOG_CONFIRM)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AnnotationToolbar(
    mode: AnnotationMode,
    onModeChange: (AnnotationMode) -> Unit,
    stampSymbol: String,
    stampMenuExpanded: Boolean,
    onStampMenuExpandedChange: (Boolean) -> Unit,
    onStampSymbolChange: (String) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    hasSelection: Boolean,
    selectedIsStamp: Boolean,
    selectedIsTextNote: Boolean,
    onDeleteSelected: () -> Unit,
    onShrinkSelectedStamp: () -> Unit,
    onGrowSelectedStamp: () -> Unit,
    onEditSelectedText: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.testTag(TestTags.ANNOTATION_TOOLBAR), tonalElevation = 2.dp) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            for (candidate in AnnotationMode.entries) {
                ToolbarModeButton(candidate, isSelected = mode == candidate, onClick = { onModeChange(candidate) })
            }
            if (mode == AnnotationMode.STAMP) {
                Box {
                    TextButton(onClick = { onStampMenuExpandedChange(true) }) { Text(stampSymbol) }
                    DropdownMenu(expanded = stampMenuExpanded, onDismissRequest = { onStampMenuExpandedChange(false) }) {
                        STAMP_PALETTE.forEach { symbol ->
                            DropdownMenuItem(text = { Text(symbol) }, onClick = {
                                onStampSymbolChange(symbol)
                                onStampMenuExpandedChange(false)
                            })
                        }
                    }
                }
            }
            if (mode != AnnotationMode.VIEW) {
                TextButton(onClick = onPrevPage) { Text("‹") }
                TextButton(onClick = onNextPage) { Text("›") }
                TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
                TextButton(onClick = onRedo, enabled = canRedo) { Text("Redo") }
            }
            if (mode == AnnotationMode.SELECT && hasSelection) {
                if (selectedIsStamp) {
                    TextButton(onClick = onShrinkSelectedStamp) { Text("−") }
                    TextButton(onClick = onGrowSelectedStamp) { Text("+") }
                }
                if (selectedIsTextNote) {
                    TextButton(onClick = onEditSelectedText) { Text("Edit") }
                }
                TextButton(onClick = onDeleteSelected, modifier = Modifier.testTag(TestTags.ANNOTATION_DELETE_BUTTON)) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ToolbarModeButton(
    mode: AnnotationMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val label =
        when (mode) {
            AnnotationMode.VIEW -> "View"
            AnnotationMode.PEN -> "Pen"
            AnnotationMode.HIGHLIGHT -> "Highlight"
            AnnotationMode.STAMP -> "Stamp"
            AnnotationMode.TEXT -> "Text"
            AnnotationMode.SELECT -> "Select"
        }
    TextButton(onClick = onClick, modifier = Modifier.testTag(TestTags.modeButton(mode))) {
        Text(if (isSelected) "[$label]" else label)
    }
}

@Composable
private fun PageContent(bitmap: ImageBitmap?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun RowScope.TapZone(
    weight: Float,
    onTap: (() -> Unit)?,
    testTag: String?,
) {
    Box(
        modifier =
            Modifier
                .weight(weight)
                .fillMaxHeight()
                .let { base -> if (onTap != null) base.clickable(onClick = onTap) else base }
                .let { base -> if (testTag != null) base.testTag(testTag) else base },
    )
}

@Composable
private fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onBack).testTag(TestTags.VIEWER_BACK_BUTTON),
        tonalElevation = 2.dp,
    ) {
        Text(
            "‹ Back",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
