package app.inkstave.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * The in-app camera capture screen (`ROADMAP.md` M4): live preview, a shutter button, and
 * multi-photo capture (one score is typically several page photos taken in one sitting, not one
 * photo per flow) with per-photo retake and a "Done" action that hands the finished batch back to
 * [CameraCapture] (this app's [MainActivity]) as a list of file paths, via
 * [EXTRA_CAPTURED_FILE_PATHS]. Launched by [CameraCapture.captureImages], the same
 * `ActivityResultContracts` pattern [DocumentPicker] uses for the system file picker -- an
 * in-process Activity here instead of an external one, but the same shape.
 *
 * **Build-verified, unlike some earlier client-side passes' new dependencies.** CameraX
 * (`androidx.camera:*`) resolved and compiled cleanly in the session this was written in --
 * `client/README.md`'s "Known rough edges" documents a JVM-specific outbound-network restriction
 * that blocked *other* new Gradle dependencies in earlier sessions (`compose.uiTest`, M1/M2/M3),
 * but that restriction did not reproduce for this dependency in this session; `:androidApp:assembleDebug`
 * and `:androidApp:testDebugUnitTest` both ran for real, not just script-compiled. What's still
 * genuinely unverified: **real camera preview/capture behavior**, which can't be exercised without
 * either a physical device or an emulator with working camera support, neither meaningfully
 * available in this environment -- left for the repo owner to verify at a physical device, the
 * same standing caveat `ROADMAP.md`'s M3 entry already states for pedal hardware.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CaptureScreen(
                    onFinish = { files -> finishWithResult(files) },
                    onCancel = { finishCancelled() },
                )
            }
        }
    }

    private fun finishWithResult(files: List<File>) {
        val intent = Intent().putStringArrayListExtra(EXTRA_CAPTURED_FILE_PATHS, ArrayList(files.map { it.absolutePath }))
        setResult(RESULT_OK, intent)
        finish()
    }

    /** Backs out with no result. Every path that can reach this has already deleted its own
     * not-yet-finished capture files first (`CameraCaptureContent`'s Cancel button via
     * [finishAndDelete]; the permission/no-hardware screens never captured anything to begin
     * with) -- otherwise a cancelled session's photos would silently accumulate in
     * [Context.getCacheDir] forever, since nothing else ever reads or cleans up a capture that
     * was never finished. */
    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        /** [Intent] extra key for the `ArrayList<String>` of absolute cache-file paths a finished
         * capture session produced -- [CameraCapture] reads this back. */
        const val EXTRA_CAPTURED_FILE_PATHS = "captured_file_paths"

        fun intent(context: Context): Intent = Intent(context, CaptureActivity::class.java)
    }
}

/** Routes to [NoCameraMessage]/[PermissionRationale]/[CameraCaptureContent] per [captureUiState]
 * -- see that function's own doc for why this decision is factored out as plain, testable logic. */
@Composable
private fun CaptureScreen(
    onFinish: (List<File>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val hasCameraHardware = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var permissionGranted by
        remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
        }

    when (captureUiState(hasCameraHardware, permissionGranted)) {
        CaptureUiState.NoCameraHardware -> NoCameraMessage(onCancel)
        CaptureUiState.PermissionNeeded ->
            PermissionRationale(
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel,
            )
        CaptureUiState.Ready -> CameraCaptureContent(onFinish = onFinish, onCancel = onCancel)
    }
}

@Composable
private fun NoCameraMessage(onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("This device has no camera to capture with.")
        TextButton(onClick = onCancel) { Text("Back") }
    }
}

@Composable
private fun PermissionRationale(
    onRequestPermission: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Inkstave needs camera access to photograph sheet music pages.")
        Button(onClick = onRequestPermission) { Text("Grant camera access") }
        TextButton(onClick = onCancel) { Text("Not now") }
    }
}

/**
 * The real capture UI: a live [PreviewView] behind a thumbnail strip of already-captured pages
 * and a Cancel/Shutter/Done control row. CameraX setup ([Preview] bound to [PreviewView]'s
 * surface, [ImageCapture] for the shutter) happens once, in a [LaunchedEffect] keyed on [Unit] --
 * [ProcessCameraProvider.bindToLifecycle] itself handles unbinding when [LocalLifecycleOwner]
 * (this Activity) stops, so there's no matching manual unbind needed here.
 */
@Composable
private fun CameraCaptureContent(
    onFinish: (List<File>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capturedFiles = remember { mutableStateListOf<File>() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProvider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
        val capture = ImageCapture.Builder().build()
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        imageCapture = capture
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
            if (capturedFiles.isNotEmpty()) {
                LazyRow(modifier = Modifier.padding(bottom = 12.dp)) {
                    items(capturedFiles.toList(), key = { it.absolutePath }) { file ->
                        CapturedThumbnail(
                            file = file,
                            onRemove = {
                                capturedFiles.remove(file)
                                file.delete()
                            },
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { finishAndDelete(capturedFiles, onCancel) }) { Text("Cancel") }
                Button(
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        captureOnePage(context, capture) { file -> capturedFiles.add(file) }
                    },
                ) { Text("Capture page") }
                Button(
                    onClick = { onFinish(capturedFiles.toList()) },
                    enabled = capturedFiles.isNotEmpty(),
                ) { Text("Done (${capturedFiles.size})") }
            }
        }
    }
}

/** Cancel's actual cleanup step -- deletes every not-yet-finished capture before calling
 * [onCancel], factored out of the click lambda above purely for readability. */
private fun finishAndDelete(
    capturedFiles: List<File>,
    onCancel: () -> Unit,
) {
    capturedFiles.forEach { it.delete() }
    onCancel()
}

/** Triggers one [ImageCapture.takePicture] call, writing a new JPEG into [Context.getCacheDir]
 * and invoking [onSaved] with it once CameraX confirms the write -- capture failures (a real,
 * if rare, CameraX outcome: storage full, hardware busy) are swallowed rather than crashing the
 * activity, since a failed shutter press should let the user just try again, not lose the whole
 * capture session. */
private fun captureOnePage(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (File) -> Unit,
) {
    val target = File(context.cacheDir, "inkstave-capture-${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(target).build()
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved(target)

            override fun onError(exception: ImageCaptureException) = Unit
        },
    )
}

@Composable
private fun CapturedThumbnail(
    file: File,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.size(72.dp).padding(4.dp)) {
        val bitmap = remember(file.absolutePath) { decodeSampledThumbnail(file, targetSizePx = 144) }
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        // A plain "x" glyph, not an icon-font/Material-icons dependency, consistent with the
        // stamp palette's own reasoning (AnnotationOverlay.kt, M2) for staying on plain text
        // rather than an icon dependency this project hasn't otherwise needed.
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
            Text("×")
        }
    }
}

/**
 * Decodes [file] downsampled to roughly [targetSizePx] on its longer side, rather than the full
 * capture resolution -- a captured page photo is typically several megapixels, and decoding that
 * in full just to show a 72dp thumbnail would be a real, needless memory/time cost per photo in
 * the strip. `null` if the file can't be decoded (corrupt/incomplete write) -- shown as an empty
 * thumbnail slot rather than crashing the screen over one bad capture.
 */
private fun decodeSampledThumbnail(
    file: File,
    targetSizePx: Int,
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetSizePx && bounds.outHeight / (sampleSize * 2) >= targetSizePx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

/** Adapts [ProcessCameraProvider.getInstance]'s `ListenableFuture` callback API into a suspend
 * call -- the standard CameraX-with-coroutines bridge (there's no first-party suspend entry
 * point for this in CameraX itself). */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ continuation.resume(future.get()) }, ContextCompat.getMainExecutor(this))
    }
