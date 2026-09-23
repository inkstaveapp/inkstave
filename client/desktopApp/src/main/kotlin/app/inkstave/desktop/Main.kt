package app.inkstave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.index.DesktopSqlDriverFactory
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.library.DesktopLibraryPaths
import app.inkstave.shared.pedal.DesktopSettingsPaths
import app.inkstave.shared.pedal.PedalSettingsStore
import app.inkstave.shared.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Linux desktop entry point. Wires up M1's real dependencies -- the local
 * index database (ADR-0005) at [DesktopLibraryPaths.indexDatabaseFile],
 * the library at [DesktopLibraryPaths.libraryDirectory] (XDG convention),
 * the importer, and the `JFileChooser`-backed pickers ([DesktopFilePicker])
 * -- plus M3's pedal key mapping, persisted at
 * [DesktopSettingsPaths.pedalSettingsFile] -- and renders the shared [App]
 * composable (`docs/architecture.md`). [App]'s `onRawKeyHandlerChange` is
 * left at its default no-op: desktop delivers key events to the focused
 * composable directly ([App]'s own doc on that parameter), so there's
 * nothing for this entry point to bridge the way `MainActivity` does.
 */
fun main() =
    application {
        val driver = DesktopSqlDriverFactory(DesktopLibraryPaths.indexDatabaseFile()).create()
        val index = LibraryIndexRepository(driver)
        val importer = LibraryImporter(DesktopLibraryPaths.libraryDirectory(), index)
        val pedalSettingsStore = PedalSettingsStore(DesktopSettingsPaths.pedalSettingsFile())

        Window(onCloseRequest = ::exitApplication, title = "Inkstave") {
            var pedalMapping by remember { mutableStateOf(pedalSettingsStore.load()) }

            App(
                libraryIndex = index,
                importer = importer,
                pickPdf = { withContext(Dispatchers.IO) { DesktopFilePicker.pickPdf() } },
                pickImages = { withContext(Dispatchers.IO) { DesktopFilePicker.pickImages() } },
                pedalMapping = pedalMapping,
                onPedalMappingChange = { updated ->
                    pedalMapping = updated
                    pedalSettingsStore.save(updated)
                },
            )
        }
    }
