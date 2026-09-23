package app.inkstave.android

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.inkstave.shared.importer.PickedFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Wraps [CaptureActivity] as the suspend `captureImages` function
 * `app.inkstave.shared.ui.App` expects (`ROADMAP.md` M4) -- the exact same shape as
 * [DocumentPicker.pickImages], and deliberately so: a finished capture session feeds into
 * [app.inkstave.shared.importer.LibraryImporter.importImages] via the identical path M1's
 * "import a set of images" flow already uses (`LibraryScreen`'s import menu), just with
 * [CaptureActivity] as the source of the [PickedFile]s instead of the system picker.
 *
 * [ActivityResultContracts] launchers must be registered unconditionally during the activity's
 * initialization, before it reaches `STARTED` -- hence this being constructed as a property of
 * [activity] at class-init time, the same requirement [DocumentPicker] documents.
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
 * Reads each of [files] into a [PickedFile] (numbered `"capture-1.jpg"`, `"capture-2.jpg"`, ...
 * regardless of their actual cache filenames -- those are opaque temp names, not something a user
 * ever sees or that should end up as `manifest.json`'s `source.details.originalFilename`), in the
 * given order, which the caller must already have as the intended reading order (same M1
 * constraint [ImportPipeline]'s own doc states: no in-app page-reordering yet). Deletes each file
 * after reading it -- once its bytes are in the returned [PickedFile], the cache copy in
 * [CaptureActivity]'s output directory has no further purpose and would otherwise accumulate
 * across capture sessions.
 *
 * A plain function over [File] (not [CaptureActivity]/CameraX types) specifically so it's
 * unit-testable against real temporary files with synthetic bytes, the same honest-fixture
 * approach `ImportPipelineTest` uses -- see `CameraCaptureTest.kt`.
 */
internal fun readCapturedFiles(files: List<File>): List<PickedFile> =
    files.mapIndexed { index, file ->
        val bytes = file.readBytes()
        file.delete()
        PickedFile(bytes = bytes, displayName = "capture-${index + 1}.jpg")
    }
