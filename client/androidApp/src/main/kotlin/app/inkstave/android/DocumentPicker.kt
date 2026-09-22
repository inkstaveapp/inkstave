package app.inkstave.android

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.inkstave.shared.importer.PickedFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT` /
 * `ACTION_OPEN_DOCUMENT` with `EXTRA_ALLOW_MULTIPLE`) as the suspend
 * `pickPdf`/`pickImages` functions `app.inkstave.shared.ui.App` expects.
 * SAF needs no storage permission -- the system picker itself is the grant
 * (`docs/format-spec.md`/`ROADMAP.md` M1's "no runtime permissions" intent
 * for import, distinct from where the library itself is stored -- see
 * `MainActivity.kt`'s app-specific `filesDir` choice).
 *
 * [ActivityResultContracts] launchers must be registered unconditionally
 * during the activity's initialization, before it reaches `STARTED` --
 * hence this being constructed as a property of [activity] at class-init
 * time (see `MainActivity.kt`), not lazily inside a click handler.
 */
class DocumentPicker(
    private val activity: ComponentActivity,
) {
    private var pendingPdf: CancellableContinuation<PickedFile?>? = null
    private var pendingImages: CancellableContinuation<List<PickedFile>>? = null

    private val openPdfLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingPdf?.resume(uri?.let(::readPickedFile))
            pendingPdf = null
        }

    private val openImagesLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingImages?.resume(uris.mapNotNull(::readPickedFile))
            pendingImages = null
        }

    /** Launches the system picker for a single PDF; `null` if the user cancelled. */
    suspend fun pickPdf(): PickedFile? =
        suspendCancellableCoroutine { continuation ->
            pendingPdf = continuation
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

    /** Launches the system picker for one or more images; empty if the user cancelled. */
    suspend fun pickImages(): List<PickedFile> =
        suspendCancellableCoroutine { continuation ->
            pendingImages = continuation
            openImagesLauncher.launch(arrayOf("image/*"))
        }

    private fun readPickedFile(uri: Uri): PickedFile? {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "imported"
        return PickedFile(bytes, displayName)
    }

    private fun queryDisplayName(uri: Uri): String? {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0 && cursor.moveToFirst()) return cursor.getString(nameColumn)
        }
        return null
    }
}
