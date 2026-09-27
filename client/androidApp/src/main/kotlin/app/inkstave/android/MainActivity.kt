package app.inkstave.android

import android.content.Context
import android.net.wifi.WifiManager
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
import app.inkstave.shared.sync.CaptureSessionSendOutcome
import app.inkstave.shared.sync.CaptureSessionSender
import app.inkstave.shared.sync.JmDnsSyncDiscovery
import app.inkstave.shared.sync.LanSyncTransport
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.TrustedPeer
import app.inkstave.shared.sync.getOrCreateDeviceIdentity
import app.inkstave.shared.ui.App
import java.io.File
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Android entry point: wires the index database, library directory, importer, SAF pickers,
 * camera capture, pedal settings and sync, then renders the shared [App].
 *
 * The library lives in app-internal storage (`filesDir`), which needs no permissions; importing
 * reads shared storage only through SAF's per-file grants ([DocumentPicker]).
 */
class MainActivity : ComponentActivity() {
    // Property initializers, not lazy: registerForActivityResult must run before the activity
    // reaches STARTED.
    private val documentPicker = DocumentPicker(this)

    private val cameraCapture = CameraCapture(this)

    // Raw key handler registered by the viewer/pedal screens, or null when neither is shown.
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
                sendCaptureSession = { peer, scoreTitle, photos ->
                    sendCaptureSession(applicationContext, syncSettingsDirectory, peerTrustStore, peer, scoreTitle, photos)
                },
                acquireMulticastLock = { acquireMulticastLock(applicationContext) },
            )
        }
    }

    /**
     * Delivers pedal and hardware keys before Compose's focus-based dispatch, so they work whatever
     * the Compose focus state is. Converts via Compose's own [ComposeKeyEvent] constructor, because
     * [Key] packs more than the native keycode and must compare equal to constants like
     * [Key.DirectionRight].
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

/**
 * Holds a WiFi multicast lock until the returned [AutoCloseable] is closed. Android drops
 * incoming multicast without one, so mDNS discovery would never see replies. Reference-counted so
 * the pairing screen's lock and a concurrent send's lock don't release each other.
 */
private fun acquireMulticastLock(context: Context): AutoCloseable {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifiManager.createMulticastLock("inkstave-sync")
    lock.setReferenceCounted(true)
    lock.acquire()
    return AutoCloseable { lock.release() }
}

/**
 * Sends a capture session to [peer] using a discovery instance scoped to this one send, holding a
 * multicast lock for its duration.
 */
private suspend fun sendCaptureSession(
    context: Context,
    syncSettingsDirectory: File,
    trustStore: PeerTrustStore,
    peer: TrustedPeer,
    scoreTitle: String,
    photos: List<ByteArray>,
): CaptureSessionSendOutcome {
    val multicastLock = acquireMulticastLock(context)
    val discovery = JmDnsSyncDiscovery.create()
    return try {
        val sender = CaptureSessionSender(discovery, LanSyncTransport(syncSettingsDirectory, trustStore))
        sender.send(peer, scoreTitle, photos)
    } finally {
        discovery.close()
        multicastLock.close()
    }
}
