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
import app.inkstave.shared.pedal.PedalAction
import app.inkstave.shared.pedal.PedalKeyMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The score viewer (`ROADMAP.md` M1: "Render pages, swipe/tap/keyboard page
 * turning"; M2: annotation editing; M3: pedal input + performance mode).
 * Opens the `.smpk` at [filePath], shows its (single, M1) part's pages
 * full-screen one at a time, and supports turning pages in
 * [AnnotationMode.VIEW] via swiping ([HorizontalPager]'s own gesture
 * handling), tapping the left/right thirds of the screen, or any key
 * [pedalMapping] binds to [PedalAction.NEXT_PAGE]/[PedalAction.PREVIOUS_PAGE]
 * -- which covers both a desktop arrow-key press (delivered straight to
 * this composable's own [onPreviewKeyEvent], the standard Compose Desktop
 * mechanism) and an Android pedal press (delivered indirectly: see
 * [onRawKeyHandlerChange]'s doc for why Android needs a different delivery
 * path than desktop does, even though both end up calling the exact same
 * [handlePedalAction] once the key event arrives).
 *
 * Pages are decoded lazily: only the current page and its immediate
 * neighbours are ever loaded into memory at once, per
 * `docs/format-spec.md`'s per-page-file design and the "load only what's on
 * screen" principle `docs/performance.md` applies to annotations and, here,
 * to page bitmaps too. Page metadata and annotation layers are loaded on
 * the same lazy, per-page-in-view schedule.
 *
 * @param pedalMapping which key triggers which [PedalAction] -- see
 *   `PedalKeyMapping.DEFAULT`'s doc for what it covers out of the box, and
 *   `PedalSettingsScreen` for how a user changes it. Desktop consumes this
 *   directly (this composable's own key handling); Android consumes it via
 *   [onRawKeyHandlerChange].
 * @param onRawKeyHandlerChange Android-only bridge (a no-op default, since
 *   desktop doesn't need it), shared with `PedalSettingsScreen`'s own
 *   "press the key you want to use" capture flow -- both register into the
 *   same mechanism, one raw `Key` at a time, because both have the exact
 *   same underlying problem: hardware key events on Android are dispatched
 *   to `Activity.dispatchKeyEvent`, *outside* the Compose tree entirely,
 *   before Compose's own focus-based key dispatch even runs. Relying on
 *   this composable's [onPreviewKeyEvent] alone would make pedal handling
 *   on Android fragile in a way that's hard to verify without physical
 *   hardware (whether this composable's focus request actually "sticks"
 *   against Android's touch-mode focus suppression after the touch-driven
 *   page-turning gestures this same screen offers). Registering a plain
 *   `(Key) -> Boolean` callback here, for `MainActivity` to call from
 *   `dispatchKeyEvent`, sidesteps that uncertainty entirely by not
 *   depending on Compose focus for key handling on Android at all. This
 *   composable's own registration wraps [pedalMapping]'s lookup around
 *   [handlePedalAction]; registered on mount, cleared (`null`) on dispose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    filePath: String,
    pedalMapping: PedalKeyMapping,
    onBack: () -> Unit,
    onRawKeyHandlerChange: (((Key) -> Boolean)?) -> Unit = {},
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
    // Performance mode (ROADMAP.md M3): minimal chrome + keep-awake (Android only,
    // KeepScreenOnEffect below) for on-stage use. Page turning (swipe/tap-zone/pedal)
    // stays fully live in performance mode -- only the toolbar/back-button/page
    // -indicator chrome hides; hiding the thing a performer needs mid-performance would
    // defeat the entire point of the feature.
    var performanceModeEnabled by remember { mutableStateOf(false) }
    // HorizontalPager's pageSpacing, made negative, is what produces the "half-page
    // /overlap" transition ROADMAP.md M3 asks for -- a real Compose Foundation
    // parameter already built for exactly this kind of visual effect, not a custom
    // animation. See OVERLAP_PAGE_SPACING's own doc for the specific value.
    var overlapTurnEnabled by remember { mutableStateOf(false) }

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

    /**
     * What a pedal press (or its desktop-arrow-key/space/page-up-down equivalent)
     * actually does -- the one place both delivery paths ([onPreviewKeyEvent] below,
     * for desktop, and [onRawKeyHandlerChange], for Android) end up calling, so
     * the two platforms can never drift into handling the same [PedalAction]
     * differently. `goTo` already clamps to the score's page range, so calling this
     * on the first/last page is a safe no-op, not a bug to guard against here too.
     */
    fun handlePedalAction(action: PedalAction): Boolean {
        when (action) {
            PedalAction.NEXT_PAGE -> goTo(pagerState.currentPage + 1)
            PedalAction.PREVIOUS_PAGE -> goTo(pagerState.currentPage - 1)
        }
        return true
    }

    // Android's half of the key-dispatch bridge (see onRawKeyHandlerChange's param
    // doc): publish a handler that maps a raw Key through pedalMapping while this
    // screen is on-screen, and withdraw it on dispose so a key press after leaving
    // the viewer doesn't call a stale handler closing over a reader that's already
    // been closed.
    DisposableEffect(onRawKeyHandlerChange, pedalMapping) {
        onRawKeyHandlerChange { key -> pedalMapping.actionFor(key)?.let { action -> handlePedalAction(action) } ?: false }
        onDispose { onRawKeyHandlerChange(null) }
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
                    // Desktop's delivery path for PedalAction: this composable has
                    // focus (focusRequester above) and Compose Desktop has no
                    // touch-mode concept to fight, unlike Android -- see
                    // onRawKeyHandlerChange's doc for why Android needs a
                    // different path to the same handlePedalAction.
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Back, Key.Escape -> {
                            onBack()
                            true
                        }
                        else -> pedalMapping.actionFor(event.key)?.let { action -> handlePedalAction(action) } ?: false
                    }
                },
    ) {
        // Android-only (see PerformanceMode.kt); a documented no-op on desktop.
        KeepScreenOnEffect(enabled = performanceModeEnabled)

        Box(modifier = Modifier.fillMaxSize()) {
            // Page turning gestures only in VIEW mode -- see AnnotationOverlay's module
            // doc for why editing modes disable them instead of trying to disambiguate
            // a draw gesture from a swipe.
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = mode == AnnotationMode.VIEW,
                pageSpacing = if (overlapTurnEnabled) OVERLAP_PAGE_SPACING else 0.dp,
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

            // Performance mode (ROADMAP.md M3) hides everything below except page
            // content and the tap zones/pedal handling above -- but never this toggle
            // itself, which stays visible so there's always a way back out of it.
            if (!performanceModeEnabled) {
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
                    selectedIsStamp =
                        selectedId?.let { id -> currentLayers[pagerState.currentPage]?.stamps?.any { it.id == id } } ?: false,
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
                    overlapTurnEnabled = overlapTurnEnabled,
                    onOverlapTurnEnabledChange = { overlapTurnEnabled = it },
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

            PerformanceModeToggle(
                enabled = performanceModeEnabled,
                onEnabledChange = { enabled ->
                    performanceModeEnabled = enabled
                    // Entering performance mode always returns to plain viewing -- the
                    // mode-switching UI that would let a performer accidentally start
                    // drawing mid-piece is exactly what this mode just hid.
                    if (enabled) {
                        mode = AnnotationMode.VIEW
                        selectedId = null
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
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

/**
 * `HorizontalPager`'s `pageSpacing`, made negative, is Compose Foundation's own
 * documented way to make adjacent pages overlap during the swipe transition
 * (`ROADMAP.md` M3's "optional half-page/overlap turn behavior") -- a real
 * layout parameter, not a custom transition. 48dp is a visible-but-not
 * -excessive overlap at typical phone/tablet/desktop-window widths; not tuned
 * against real usage (there isn't any yet). There's a known, not-fully
 * -triaged upstream Compose issue around negative `pageSpacing` edge cases
 * (issuetracker.google.com/issues/395489594) -- worth a visual check once
 * this can be verified on a real device/window, not just from a passing test.
 */
private val OVERLAP_PAGE_SPACING = (-48).dp

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
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    // ROADMAP.md M3: don't trust Android's default soft-keyboard
                    // auto-show heuristic, which a connected pedal (seen as a
                    // hardware keyboard) suppresses -- see showSoftKeyboardOnFocus's
                    // own doc (SoftKeyboard.kt) for why that would otherwise make
                    // this field silently untypeable the moment a pedal is paired.
                    modifier = Modifier.testTag(TestTags.TEXT_DIALOG_FIELD).showSoftKeyboardOnFocus(),
                )
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
    overlapTurnEnabled: Boolean,
    onOverlapTurnEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.testTag(TestTags.ANNOTATION_TOOLBAR), tonalElevation = 2.dp) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            for (candidate in AnnotationMode.entries) {
                ToolbarModeButton(candidate, isSelected = mode == candidate, onClick = { onModeChange(candidate) })
            }
            // "Optional half-page/overlap turn behavior" (ROADMAP.md M3) -- a page
            // -turning preference, not tied to any one AnnotationMode, so it's always
            // visible here rather than nested under one of the mode-specific blocks
            // below.
            TextButton(
                onClick = { onOverlapTurnEnabledChange(!overlapTurnEnabled) },
                modifier = Modifier.testTag(TestTags.OVERLAP_TURN_TOGGLE),
            ) { Text(if (overlapTurnEnabled) "Overlap: On" else "Overlap: Off") }
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

/**
 * The one control performance mode (`ROADMAP.md` M3) never hides -- see
 * [ViewerScreen]'s own performance-mode block. Deliberately plain text, not
 * an icon: consistent with [STAMP_PALETTE]'s own reasoning (`AnnotationOverlay.kt`)
 * for avoiding icon-font/glyph dependencies this project doesn't have yet.
 */
@Composable
private fun PerformanceModeToggle(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable { onEnabledChange(!enabled) }.testTag(TestTags.PERFORMANCE_MODE_TOGGLE),
        tonalElevation = 2.dp,
    ) {
        Text(
            if (enabled) "Exit performance mode" else "Performance mode",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
