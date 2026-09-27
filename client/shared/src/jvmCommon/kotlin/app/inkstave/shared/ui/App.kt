package app.inkstave.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.PickedFile
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.pedal.PedalKeyMapping
import app.inkstave.shared.sync.CaptureSessionSendOutcome
import app.inkstave.shared.sync.DeviceIdentity
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.TrustedPeer
import java.io.File

/** Which screen [App] is currently showing. Library is always the start screen. */
private sealed interface Screen {
    data object Library : Screen

    data class Viewer(
        val filePath: String,
    ) : Screen

    data object PedalSettings : Screen

    data object Pairing : Screen
}

/**
 * The whole app: one Compose UI tree shared by `androidApp` and `desktopApp`. Navigation is plain `remember`ed
 * state over [Screen]; there are too few screens to justify a navigation library.
 *
 * @param libraryIndex the local index (ADR-0005) the library screen lists from.
 * @param importer writes newly picked PDFs/images into the library as `.smpk` files.
 * @param pickPdf opens the platform file picker for one PDF; `null` means the user cancelled.
 * @param pickImages opens the platform file picker for one or more images, in import order.
 * @param captureImages the in-app camera flow, returning page photos in capture order; `null` (desktop) means the
 *   platform has no camera flow and [LibraryScreen] doesn't offer one.
 * @param pedalMapping current pedal key bindings; [onPedalMappingChange] is called when the user edits them, and the
 *   platform entry point persists the change.
 * @param onRawKeyHandlerChange Android's key-dispatch bridge that [ViewerScreen] and [PedalSettingsScreen] register
 *   into while active (see [ViewerScreen]). Desktop's no-op default is correct: it delivers keys to the focused
 *   composable directly.
 * @param syncSettingsDirectory where this device's sync identity and trust store live; chosen by the platform.
 * @param localIdentity this device's persistent sync identity, provisioned once by the platform entry point.
 * @param peerTrustStore paired devices; [PairingScreen] edits it and [LibraryScreen] uses it to decide whether
 *   "send to desktop" is offered.
 * @param sendCaptureSession sends a finished capture session to a paired peer; `null` (desktop) means it's never
 *   offered, since desktop has no capture flow.
 * @param acquireMulticastLock held while [PairingScreen] needs to receive mDNS traffic: Android drops incoming
 *   multicast unless a `WifiManager.MulticastLock` is held. `null` (desktop) means there is nothing to hold.
 */
@Composable
fun App(
    libraryIndex: LibraryIndexRepository,
    importer: LibraryImporter,
    pickPdf: suspend () -> PickedFile?,
    pickImages: suspend () -> List<PickedFile>,
    captureImages: (suspend () -> List<PickedFile>)? = null,
    pedalMapping: PedalKeyMapping,
    onPedalMappingChange: (PedalKeyMapping) -> Unit,
    onRawKeyHandlerChange: (((Key) -> Boolean)?) -> Unit = {},
    syncSettingsDirectory: File,
    localIdentity: DeviceIdentity,
    peerTrustStore: PeerTrustStore,
    sendCaptureSession: (suspend (peer: TrustedPeer, scoreTitle: String, photos: List<ByteArray>) -> CaptureSessionSendOutcome)? = null,
    acquireMulticastLock: (() -> AutoCloseable)? = null,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }

    MaterialTheme {
        when (val current = screen) {
            is Screen.Library -> {
                LibraryScreen(
                    index = libraryIndex,
                    importer = importer,
                    pickPdf = pickPdf,
                    pickImages = pickImages,
                    captureImages = captureImages,
                    trustStore = peerTrustStore,
                    sendCaptureSession = sendCaptureSession,
                    onOpenScore = { filePath -> screen = Screen.Viewer(filePath) },
                    onOpenPedalSettings = { screen = Screen.PedalSettings },
                    onOpenPairing = { screen = Screen.Pairing },
                )
            }

            is Screen.Viewer -> {
                ViewerScreen(
                    filePath = current.filePath,
                    pedalMapping = pedalMapping,
                    onBack = { screen = Screen.Library },
                    onRawKeyHandlerChange = onRawKeyHandlerChange,
                )
            }

            is Screen.PedalSettings -> {
                PedalSettingsScreen(
                    mapping = pedalMapping,
                    onMappingChange = onPedalMappingChange,
                    onBack = { screen = Screen.Library },
                    onRawKeyHandlerChange = onRawKeyHandlerChange,
                )
            }

            is Screen.Pairing -> {
                PairingScreen(
                    settingsDirectory = syncSettingsDirectory,
                    localIdentity = localIdentity,
                    trustStore = peerTrustStore,
                    onBack = { screen = Screen.Library },
                    acquireMulticastLock = acquireMulticastLock,
                )
            }
        }
    }
}
