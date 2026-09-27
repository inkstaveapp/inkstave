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
import app.inkstave.shared.processing.ProcessingServiceClient
import app.inkstave.shared.processing.ProcessingServiceLauncher
import app.inkstave.shared.sync.CaptureSessionImportOutcome
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
 * Linux desktop entry point: wires the index database, library directory, importer, file pickers
 * and pedal settings into the shared [App]. `onRawKeyHandlerChange` stays a no-op because desktop
 * delivers key events to the focused composable directly.
 *
 * Also starts [startSyncListener] for the app's whole lifetime (unlike [PairingScreen]'s listener,
 * which only runs while pairing), so paired devices can send captures whenever the app is open, and
 * tries to start the processing service up front.
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
        val processingServiceClient = ProcessingServiceClient()
        ProcessingServiceLauncher.ensureRunningInBackground(processingServiceClient)
        startSyncListener(syncSettingsDirectory, peerTrustStore, importer, processingServiceClient)

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
 * Advertises a [SyncServer] as a [DeviceRole.PROCESSING] device and accepts capture sessions on a
 * daemon thread for the life of the process. Unpaired connections are rejected by TLS via
 * [trustStore]. Each session goes through [CaptureSessionReceiver] (processed when the service is
 * reachable, raw otherwise) and the outcome is logged.
 *
 * No shutdown hook: the process exits on window close and the OS releases the socket and mDNS
 * registration.
 */
private fun startSyncListener(
    syncSettingsDirectory: java.io.File,
    trustStore: PeerTrustStore,
    importer: LibraryImporter,
    processingClient: ProcessingServiceClient,
) {
    val identity = getOrCreateDeviceIdentity(syncSettingsDirectory)
    val server = SyncServer(syncSettingsDirectory, trustStore)
    val discovery = JmDnsSyncDiscovery.create()
    discovery.advertise(identity, server.boundPort, setOf(DeviceRole.PROCESSING))

    Thread {
        while (true) {
            try {
                server.acceptOne().use { connection ->
                    when (val outcome = CaptureSessionReceiver.receiveAndImport(connection, importer, processingClient)) {
                        is CaptureSessionImportOutcome.Processed -> {
                            println("inkstave: capture session imported and processed: '${outcome.manifest.title}'")
                        }

                        is CaptureSessionImportOutcome.ImportedRaw -> {
                            println("inkstave: capture session imported raw (${outcome.reason}): '${outcome.manifest.title}'")
                        }
                    }
                }
            } catch (e: IOException) {
                // One bad connection must not stop the listener: log and keep accepting.
                System.err.println("inkstave: capture session receive failed: ${e.message}")
            }
        }
    }.apply {
        isDaemon = true
        name = "inkstave-sync-listener"
        start()
    }
}
