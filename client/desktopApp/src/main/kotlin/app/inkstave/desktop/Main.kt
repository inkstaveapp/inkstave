package app.inkstave.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.index.DesktopSqlDriverFactory
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.library.DesktopLibraryPaths
import app.inkstave.shared.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Linux desktop entry point. Wires up M1's real dependencies -- the local
 * index database (ADR-0005) at [DesktopLibraryPaths.indexDatabaseFile],
 * the library at [DesktopLibraryPaths.libraryDirectory] (XDG convention),
 * the importer, and the `JFileChooser`-backed pickers ([DesktopFilePicker])
 * -- and renders the shared [App] composable (`docs/architecture.md`).
 */
fun main() =
    application {
        val driver = DesktopSqlDriverFactory(DesktopLibraryPaths.indexDatabaseFile()).create()
        val index = LibraryIndexRepository(driver)
        val importer = LibraryImporter(DesktopLibraryPaths.libraryDirectory(), index)

        Window(onCloseRequest = ::exitApplication, title = "Inkstave") {
            App(
                libraryIndex = index,
                importer = importer,
                pickPdf = { withContext(Dispatchers.IO) { DesktopFilePicker.pickPdf() } },
                pickImages = { withContext(Dispatchers.IO) { DesktopFilePicker.pickImages() } },
            )
        }
    }
