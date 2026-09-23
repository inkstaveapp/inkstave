package app.inkstave.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.index.AndroidSqlDriverFactory
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.pedal.PedalSettingsStore
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.getOrCreateDeviceIdentity
import app.inkstave.shared.ui.App
import java.io.File
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Android entry point. Wires up M1's real dependencies -- the local index
 * database (ADR-0005), the library's on-disk location, the importer, and
 * the SAF-backed file pickers ([DocumentPicker]) -- M3's pedal key mapping
 * (persisted at `filesDir/pedal-settings.json`), M4's in-app camera capture
 * ([CameraCapture]), and renders the shared [App] composable
 * (`docs/architecture.md`).
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

    // Same unconditional-during-init requirement as documentPicker above --
    // CameraCapture's own registerForActivityResult call needs to run before
    // this activity reaches STARTED.
    private val cameraCapture = CameraCapture(this)

    // The Android half of the raw-key-dispatch bridge App's onRawKeyHandlerChange
    // registers into (see ViewerScreen's doc on that parameter for the full
    // reasoning: dispatchKeyEvent, below, runs before Compose's own focus-based key
    // dispatch and doesn't depend on it). null whenever neither ViewerScreen nor
    // PedalSettingsScreen is the currently-composed screen.
    private var activeRawKeyHandler: ((Key) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val driver = AndroidSqlDriverFactory(applicationContext).create()
        val index = LibraryIndexRepository(driver)
        val libraryDirectory = File(filesDir, "library").apply { mkdirs() }
        val importer = LibraryImporter(libraryDirectory, index)
        val pedalSettingsStore = PedalSettingsStore(File(filesDir, "pedal-settings.json"))
        val syncSettingsDirectory = File(filesDir, "sync")
        val localIdentity = getOrCreateDeviceIdentity(syncSettingsDirectory)
        val peerTrustStore = PeerTrustStore(File(syncSettingsDirectory, "trusted-peers.json"))

        setContent {
            var pedalMapping by remember { mutableStateOf(pedalSettingsStore.load()) }

            App(
                libraryIndex = index,
                importer = importer,
                pickPdf = documentPicker::pickPdf,
                pickImages = documentPicker::pickImages,
                captureImages = cameraCapture::captureImages,
                pedalMapping = pedalMapping,
                onPedalMappingChange = { updated ->
                    pedalMapping = updated
                    pedalSettingsStore.save(updated)
                },
                onRawKeyHandlerChange = { handler -> activeRawKeyHandler = handler },
                syncSettingsDirectory = syncSettingsDirectory,
                localIdentity = localIdentity,
                peerTrustStore = peerTrustStore,
            )
        }
    }

    /**
     * The robust delivery path for pedal/hardware key events on Android -- see
     * `ViewerScreen`'s `onRawKeyHandlerChange` doc for the full reasoning behind
     * bypassing Compose's own focus-based key dispatch for this specifically.
     * `dispatchKeyEvent` runs for every key event this Activity's window receives,
     * before Compose focus even enters the picture, so it works regardless of
     * whatever focus/touch-mode state the Compose tree happens to be in.
     *
     * Converts the native [NativeKeyEvent] to Compose's own [ComposeKeyEvent] via
     * its public wrapping constructor -- deliberately *not* hand-rolled from
     * `event.keyCode`, since [Key]'s internal representation packs the native
     * keycode together with other bits ("Key ... androidx/compose/ui/input/key",
     * Android reference docs) that this composable's own [Key.DirectionRight]-style
     * comparisons rely on; going through Compose's own conversion guarantees the
     * result compares equal the same way constants like [Key.DirectionRight] do.
     */
    override fun dispatchKeyEvent(event: NativeKeyEvent): Boolean {
        val composeEvent = ComposeKeyEvent(event)
        if (composeEvent.type == KeyEventType.KeyDown) {
            val handled = activeRawKeyHandler?.invoke(composeEvent.key) ?: false
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }
}
