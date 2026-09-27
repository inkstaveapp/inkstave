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
 * Wraps the Storage Access Framework (`ACTION_OPEN_DOCUMENT`, multi-select for images) as the suspend
 * `pickPdf`/`pickImages` functions [app.inkstave.shared.ui.App] expects. SAF needs no storage
 * permission; the picker itself grants access.
 *
 * Constructed as a property of [activity] at init time because [ActivityResultContracts] launchers
 * must be registered before the activity reaches `STARTED`.
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
