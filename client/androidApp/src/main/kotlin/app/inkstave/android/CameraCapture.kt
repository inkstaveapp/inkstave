package app.inkstave.android

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.inkstave.shared.importer.PickedFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Wraps [CaptureActivity] as the suspend `captureImages` function [app.inkstave.shared.ui.App] expects,
 * mirroring [DocumentPicker.pickImages] so captures take the same `LibraryImporter.importImages` path.
 *
 * Constructed as a property of [activity] at init time because [ActivityResultContracts] launchers
 * must be registered before the activity reaches `STARTED`.
 */
class CameraCapture(
    private val activity: ComponentActivity,
) {
    private var pending: CancellableContinuation<List<PickedFile>>? = null

    private val captureLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val paths = result.data?.getStringArrayListExtra(CaptureActivity.EXTRA_CAPTURED_FILE_PATHS).orEmpty()
            pending?.resume(readCapturedFiles(paths.map(::File)))
            pending = null
        }

    /** Launches [CaptureActivity]; empty if the user captured nothing or backed out. */
    suspend fun captureImages(): List<PickedFile> =
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            captureLauncher.launch(CaptureActivity.intent(activity))
        }
}

/**
 * Reads [files] into [PickedFile]s named `capture-1.jpg`, `capture-2.jpg`, ... in the given order
 * (which becomes page order; the cache names are opaque), deleting each cache file once read so they
 * don't accumulate. Takes plain [File]s so it can be unit-tested.
 */
internal fun readCapturedFiles(files: List<File>): List<PickedFile> =
    files.mapIndexed { index, file ->
        val bytes = file.readBytes()
        file.delete()
        PickedFile(bytes = bytes, displayName = "capture-${index + 1}.jpg")
    }
