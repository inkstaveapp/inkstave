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
import app.inkstave.shared.sync.CaptureSessionReceiver
import app.inkstave.shared.sync.DeviceRole
import app.inkstave.shared.sync.JmDnsSyncDiscovery
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.SyncServer
import app.inkstave.shared.sync.getOrCreateDeviceIdentity
import app.inkstave.shared.ui.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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
 * `getOrCreateDeviceIdentity` (M4, `app.inkstave.shared.sync`) is called once here, not per
 * recomposition, the same one-time-provisioning-then-pass-down pattern `pedalSettingsStore.load()`
 * already uses.
 *
 * Also starts [startSyncListener]: this device advertises itself and accepts incoming, already
 * -paired capture sessions for the *entire time this app is running* -- unlike [PairingScreen]'s
 * own advertise/accept loop, which is deliberately scoped to only while that screen is open (a
 * *pairing* invitation shouldn't stand indefinitely), receiving a capture session someone already
 * paired with this device is exactly the kind of thing that should just work whenever the desktop
 * app happens to be open, not only when the user has navigated to a specific screen for it.
 */
fun main() =
    application {
        val driver = DesktopSqlDriverFactory(DesktopLibraryPaths.indexDatabaseFile()).create()
        val index = LibraryIndexRepository(driver)
        val importer = LibraryImporter(DesktopLibraryPaths.libraryDirectory(), index)
        val pedalSettingsStore = PedalSettingsStore(DesktopSettingsPaths.pedalSettingsFile())
        val syncSettingsDirectory = DesktopSettingsPaths.syncSettingsDirectory()
        val localIdentity = getOrCreateDeviceIdentity(syncSettingsDirectory)
        val peerTrustStore = PeerTrustStore(DesktopSettingsPaths.peerTrustStoreFile())
        startSyncListener(syncSettingsDirectory, peerTrustStore, importer)

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
                syncSettingsDirectory = syncSettingsDirectory,
                localIdentity = localIdentity,
                peerTrustStore = peerTrustStore,
            )
        }
    }

/**
 * Starts a [SyncServer] (authenticated by [trustStore], so an unpaired connection is rejected by
 * TLS itself before this function's own code ever runs) and advertises it via JmDNS as a
 * [DeviceRole.PROCESSING] device, then accepts capture sessions in a loop on a background daemon
 * thread for as long as the process runs -- each one handed to [CaptureSessionReceiver], which
 * imports it via [importer] the exact same way M1's local image import already does (see that
 * object's own doc for exactly what's deliberately *not* done yet: running received photos
 * through `processing-service`'s cleanup/OCR pipeline, and syncing a processed result back).
 *
 * No explicit shutdown hook: this is a single-window desktop app whose process exits directly on
 * window close ([exitApplication]), and the server socket/JmDNS registration are OS-cleaned-up
 * resources on process exit either way -- a deliberate simplification for this pass, not an
 * oversight; revisit if this app ever needs to keep running after its last window closes.
 */
private fun startSyncListener(
    syncSettingsDirectory: java.io.File,
    trustStore: PeerTrustStore,
    importer: LibraryImporter,
) {
    val identity = getOrCreateDeviceIdentity(syncSettingsDirectory)
    val server = SyncServer(syncSettingsDirectory, trustStore)
    val discovery = JmDnsSyncDiscovery.create()
    discovery.advertise(identity, server.boundPort, setOf(DeviceRole.PROCESSING))

    Thread {
        while (true) {
            try {
                server.acceptOne().use { connection -> CaptureSessionReceiver.receiveAndImport(connection, importer) }
            } catch (e: IOException) {
                // server.close() would unblock an in-progress accept()/receive() with exactly this
                // exception -- this app never calls it (see this function's doc), so in practice this
                // only happens for a single malformed/dropped connection, which must not take the
                // whole listener down: log-and-continue accepting the next one.
                System.err.println("inkstave: capture session receive failed: ${e.message}")
            }
        }
    }.apply {
        isDaemon = true
        name = "inkstave-sync-listener"
        start()
    }
}
