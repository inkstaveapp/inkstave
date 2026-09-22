package app.inkstave.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.index.AndroidSqlDriverFactory
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.ui.App
import java.io.File

/**
 * Android entry point. Wires up M1's real dependencies -- the local index
 * database (ADR-0005), the library's on-disk location, the importer, and
 * the SAF-backed file pickers ([DocumentPicker]) -- and renders the shared
 * [App] composable (`docs/architecture.md`).
 *
 * The library itself lives in app-specific internal storage
 * (`Context.filesDir`), not shared/public storage: it needs zero runtime
 * permissions and isn't meant to be browsed by other apps -- unlike
 * *importing*, which deliberately does read from shared storage, but only
 * via SAF's own per-file grant ([DocumentPicker]), never a broad storage
 * permission.
 */
class MainActivity : ComponentActivity() {
    // A direct property initializer, not `by lazy` or anything constructed inside
    // onCreate: registerForActivityResult (which DocumentPicker's constructor calls)
    // must run unconditionally during activity initialization, before the activity
    // reaches STARTED -- a class-body property initializer is the documented-safe
    // place for that, same as registering an ActivityResultLauncher directly would be.
    private val documentPicker = DocumentPicker(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val driver = AndroidSqlDriverFactory(applicationContext).create()
        val index = LibraryIndexRepository(driver)
        val libraryDirectory = File(filesDir, "library").apply { mkdirs() }
        val importer = LibraryImporter(libraryDirectory, index)

        setContent {
            App(
                libraryIndex = index,
                importer = importer,
                pickPdf = documentPicker::pickPdf,
                pickImages = documentPicker::pickImages,
            )
        }
    }
}
