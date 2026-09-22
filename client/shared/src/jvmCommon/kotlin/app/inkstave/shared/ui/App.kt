package app.inkstave.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.PickedFile
import app.inkstave.shared.index.LibraryIndexRepository

/** Which screen [App] is currently showing. Library is always the start screen. */
private sealed interface Screen {
    data object Library : Screen

    data class Viewer(
        val filePath: String,
    ) : Screen
}

/**
 * The whole app, as a single Compose Multiplatform UI tree shared by both
 * `androidApp` and `desktopApp` (`docs/architecture.md`) -- this replaced
 * M0's static placeholder once M1's real library/viewer screens existed.
 * Navigation is a two-screen stack (library, viewer) held as plain
 * `remember`ed state; there's no navigation library dependency yet because
 * two screens don't need one -- reconsider once M2+ adds more.
 *
 * @param libraryIndex the local index (ADR-0005) the library screen lists from.
 * @param importer writes newly-picked PDFs/images into the library as `.smpk` files.
 * @param pickPdf launches the platform's file picker for a single PDF; `null` means the user cancelled.
 * @param pickImages launches the platform's file picker for one or more image files, in the order to import them.
 */
@Composable
fun App(
    libraryIndex: LibraryIndexRepository,
    importer: LibraryImporter,
    pickPdf: suspend () -> PickedFile?,
    pickImages: suspend () -> List<PickedFile>,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }

    MaterialTheme {
        when (val current = screen) {
            is Screen.Library ->
                LibraryScreen(
                    index = libraryIndex,
                    importer = importer,
                    pickPdf = pickPdf,
                    pickImages = pickImages,
                    onOpenScore = { filePath -> screen = Screen.Viewer(filePath) },
                )
            is Screen.Viewer ->
                ViewerScreen(
                    filePath = current.filePath,
                    onBack = { screen = Screen.Library },
                )
        }
    }
}
