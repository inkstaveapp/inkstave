package app.inkstave.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import app.inkstave.shared.format.SmpkReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The score viewer (`ROADMAP.md` M1: "Render pages, swipe/tap/keyboard page
 * turning"). Opens the `.smpk` at [filePath], shows its (single, M1) part's
 * pages full-screen one at a time, and supports three ways to turn pages:
 * swiping (via [HorizontalPager]'s own gesture handling), tapping the left/
 * right thirds of the screen, and desktop arrow keys -- which are a no-op
 * on a touch-only Android device rather than an error, since nothing there
 * ever dispatches a key event.
 *
 * Pages are decoded lazily: only the current page and its immediate
 * neighbours are ever loaded into memory at once, per
 * `docs/format-spec.md`'s per-page-file design and the "load only what's on
 * screen" principle `docs/performance.md` applies to annotations and, here,
 * to page bitmaps too.
 */
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(reader, pagerState.currentPage) {
        val lookahead = (pagerState.currentPage - 1)..(pagerState.currentPage + 1)
        for (index in lookahead) {
            if (index !in pageIds.indices || bitmaps.containsKey(index)) continue
            val bitmap = withContext(Dispatchers.IO) { decodePageBitmap(reader.readPageBytes(pageIds[index])) }
            bitmaps[index] = bitmap
        }
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
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
                PageContent(bitmap = bitmaps[pageIndex])
            }

            // Tap zones: left/right thirds turn the page, alongside HorizontalPager's
            // own swipe handling -- an alternative input, not a replacement for it.
            Row(modifier = Modifier.fillMaxSize()) {
                TapZone(weight = 1f, onTap = { goTo(pagerState.currentPage - 1) })
                TapZone(weight = 1f, onTap = null)
                TapZone(weight = 1f, onTap = { goTo(pagerState.currentPage + 1) })
            }

            BackButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
        }
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
) {
    Box(
        modifier =
            Modifier
                .weight(weight)
                .fillMaxHeight()
                .let { base -> if (onTap != null) base.clickable(onClick = onTap) else base },
    )
}

@Composable
private fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onBack),
        tonalElevation = 2.dp,
    ) {
        Text(
            "‹ Back",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
