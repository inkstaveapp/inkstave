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
import app.inkstave.shared.sync.DeviceIdentity
import app.inkstave.shared.sync.PeerTrustStore
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
 * The whole app, as a single Compose Multiplatform UI tree shared by both
 * `androidApp` and `desktopApp` (`docs/architecture.md`) -- this replaced
 * M0's static placeholder once M1's real library/viewer screens existed.
 * Navigation is a two-screen stack (library, viewer) held as plain
 * `remember`ed state; there's no navigation library dependency yet because
 * two screens don't need one -- reconsider once M2+ adds more.
 *
 * @param libraryIndex the local index (ADR-0005) the library screen lists from.
 * @param importer writes newly-picked PDFs/images into the library as `.smpk` files.
 * @param pickPdf launches the platform's file picker for a single PDF; `null` means the user cancelled.
 * @param pickImages launches the platform's file picker for one or more image files, in the order to import them.
 * @param captureImages launches the in-app camera capture flow (`ROADMAP.md` M4), returning the finished
 *   batch of page photos in capture order -- `null` (desktop's default) means this platform has no camera
 *   capture flow at all, in which case [LibraryScreen] simply doesn't offer it, rather than offering an
 *   action that would always fail or do nothing.
 * @param pedalMapping current pedal key bindings (`ROADMAP.md` M3); [onPedalMappingChange] persists a change
 *   the user makes in [PedalSettingsScreen] -- the platform entry point (`MainActivity`/`Main.kt`) owns
 *   actually saving it via `PedalSettingsStore`, the same division of responsibility `LibraryImporter`
 *   already uses for where the library itself lives.
 * @param onRawKeyHandlerChange the Android key-dispatch bridge both [ViewerScreen] and [PedalSettingsScreen]
 *   register into while they're the active screen -- see [ViewerScreen]'s own doc on this parameter for why
 *   it exists. Desktop's default no-op is correct: `Main.kt` never calls the registered handler, since
 *   desktop delivers key events to the focused composable directly.
 * @param syncSettingsDirectory where this device's sync identity/trust store lives (`ROADMAP.md` M4,
 *   `app.inkstave.shared.sync`) -- the platform entry point decides the actual path (`DesktopSettingsPaths`
 *   on desktop, `filesDir/sync` on Android), the same division of responsibility `pedalMapping`'s storage uses.
 * @param localIdentity this device's own persistent sync identity (`DeviceIdentityProvisioning`), provisioned
 *   once by the platform entry point on first run and passed down rather than re-derived here, so it stays
 *   stable across recompositions without this composable needing to know how provisioning works.
 * @param peerTrustStore the paired-device trust store [PairingScreen] reads/writes.
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
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }

    MaterialTheme {
        when (val current = screen) {
            is Screen.Library ->
                LibraryScreen(
                    index = libraryIndex,
                    importer = importer,
                    pickPdf = pickPdf,
                    pickImages = pickImages,
                    captureImages = captureImages,
                    onOpenScore = { filePath -> screen = Screen.Viewer(filePath) },
                    onOpenPedalSettings = { screen = Screen.PedalSettings },
                    onOpenPairing = { screen = Screen.Pairing },
                )
            is Screen.Viewer ->
                ViewerScreen(
                    filePath = current.filePath,
                    pedalMapping = pedalMapping,
                    onBack = { screen = Screen.Library },
                    onRawKeyHandlerChange = onRawKeyHandlerChange,
                )
            is Screen.PedalSettings ->
                PedalSettingsScreen(
                    mapping = pedalMapping,
                    onMappingChange = onPedalMappingChange,
                    onBack = { screen = Screen.Library },
                    onRawKeyHandlerChange = onRawKeyHandlerChange,
                )
            is Screen.Pairing ->
                PairingScreen(
                    settingsDirectory = syncSettingsDirectory,
                    localIdentity = localIdentity,
                    trustStore = peerTrustStore,
                    onBack = { screen = Screen.Library },
                )
        }
    }
}
