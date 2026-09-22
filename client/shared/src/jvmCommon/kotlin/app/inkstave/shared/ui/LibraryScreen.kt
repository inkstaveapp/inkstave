package app.inkstave.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.PickedFile
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.index.ScoreSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library screen (`ROADMAP.md` M1: "Basic library screen ... backed by
 * the local index database"): lists every score from [index] -- never by
 * scanning `.smpk` files on load, per ADR-0005 -- and offers importing a
 * PDF or a set of images via [pickPdf]/[pickImages] + [importer]. Tapping a
 * row opens that score in the viewer via [onOpenScore].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    index: LibraryIndexRepository,
    importer: LibraryImporter,
    pickPdf: suspend () -> PickedFile?,
    pickImages: suspend () -> List<PickedFile>,
    onOpenScore: (filePath: String) -> Unit,
) {
    var scores by remember { mutableStateOf(index.listAll()) }
    var importing by remember { mutableStateOf(false) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Picks up scores imported elsewhere (e.g. a future sync receive) while this
    // screen is visible -- cheap, since it's just an indexed SQL query (ADR-0005),
    // not a filesystem scan.
    LaunchedEffect(Unit) { scores = index.listAll() }

    fun runImport(action: suspend () -> Unit) {
        importMenuExpanded = false
        scope.launch {
            importing = true
            try {
                withContext(Dispatchers.IO) { action() }
                scores = index.listAll()
            } finally {
                importing = false
            }
        }
    }

    fun importPdfAction() =
        runImport {
            val picked = pickPdf() ?: return@runImport
            importer.importPdf(
                title = picked.displayName.substringBeforeLast('.').ifBlank { "Untitled" },
                pdfBytes = picked.bytes,
                originalFilename = picked.displayName,
            )
        }

    fun importImagesAction() =
        runImport {
            val picked = pickImages()
            if (picked.isEmpty()) return@runImport
            importer.importImages(
                title =
                    picked
                        .first()
                        .displayName
                        .substringBeforeLast('.')
                        .ifBlank { "Untitled" },
                imageFiles = picked.map { it.bytes },
                originalFilename = picked.first().displayName,
            )
        }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inkstave") }) },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    text = { Text(if (importing) "Importing..." else "Import") },
                    onClick = { if (!importing) importMenuExpanded = true },
                    icon = { if (importing) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) },
                )
                DropdownMenu(expanded = importMenuExpanded, onDismissRequest = { importMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Import PDF") }, onClick = { importPdfAction() })
                    DropdownMenuItem(text = { Text("Import images") }, onClick = { importImagesAction() })
                }
            }
        },
    ) { padding ->
        if (scores.isEmpty()) {
            EmptyLibrary(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(scores, key = ScoreSummary::id) { score ->
                    ListItem(
                        headlineContent = { Text(score.title) },
                        supportingContent = { score.composer?.let { Text(it) } },
                        modifier = Modifier.clickable { onOpenScore(score.filePath) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column {
            Text("No scores yet", style = MaterialTheme.typography.titleMedium)
            Text("Import a PDF or a set of images to get started.")
        }
    }
}
