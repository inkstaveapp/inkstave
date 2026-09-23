package app.inkstave.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.inkstave.shared.importer.LibraryImporter
import app.inkstave.shared.importer.PickedFile
import app.inkstave.shared.index.LibraryIndexRepository
import app.inkstave.shared.index.ScoreSummary
import app.inkstave.shared.pedal.PedalAction
import app.inkstave.shared.sync.CaptureSessionSendOutcome
import app.inkstave.shared.sync.PeerTrustStore
import app.inkstave.shared.sync.TrustedPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library screen (`ROADMAP.md` M1: "Basic library screen ... backed by
 * the local index database"): lists every score from [index] -- never by
 * scanning `.smpk` files on load, per ADR-0005 -- and offers importing a
 * PDF or a set of images via [pickPdf]/[pickImages] + [importer]. Tapping a
 * row opens that score in the viewer via [onOpenScore]. The top bar's
 * "Pedal Settings" action ([onOpenPedalSettings], `ROADMAP.md` M3) is the
 * one navigation entry point into [PedalSettingsScreen] -- there's no
 * other settings surface yet for it to live under.
 *
 * [captureImages] (`ROADMAP.md` M4) adds a third "Capture photos" import
 * option alongside PDF/images when non-`null` (Android only, as of M4 --
 * `null` on desktop, which has no in-app camera flow). Its result normally
 * feeds [importer]'s existing [LibraryImporter.importImages] path -- the
 * exact same code M1's "import a set of images" already uses, whether the
 * images came from the system picker or the camera -- **unless** at least
 * one peer is paired ([trustStore]) and [sendCaptureSession] is non-`null`
 * (Android only, same reasoning as [captureImages]), in which case the user
 * is asked whether to send the batch to that peer instead
 * (`CaptureSessionSender`, `docs/sync-protocol.md`) or still import it
 * locally -- local import is always offered and is the safe default if the
 * send fails or the dialog is dismissed, per this integration's "local
 * first, sync later" scope decision (never lose a just-captured batch).
 * Picking a specific peer when more than one is paired isn't built yet --
 * this always offers [trustStore]'s first entry; real future work if that
 * becomes a real need, not attempted in this pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    index: LibraryIndexRepository,
    importer: LibraryImporter,
    pickPdf: suspend () -> PickedFile?,
    pickImages: suspend () -> List<PickedFile>,
    captureImages: (suspend () -> List<PickedFile>)? = null,
    trustStore: PeerTrustStore? = null,
    sendCaptureSession: (suspend (peer: TrustedPeer, scoreTitle: String, photos: List<ByteArray>) -> CaptureSessionSendOutcome)? = null,
    onOpenScore: (filePath: String) -> Unit,
    onOpenPedalSettings: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    var scores by remember { mutableStateOf(index.listAll()) }
    var importing by remember { mutableStateOf(false) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    var pendingCaptureChoice by remember { mutableStateOf<List<PickedFile>?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Picks up scores imported elsewhere (e.g. a future sync receive) while this
    // screen is visible -- cheap, since it's just an indexed SQL query (ADR-0005),
    // not a filesystem scan.
    LaunchedEffect(Unit) { scores = index.listAll() }

    fun runImport(action: suspend () -> Unit) {
        importMenuExpanded = false
        scope.launch {
            importing = true
            try {
                withContext(Dispatchers.IO) { action() }
                scores = index.listAll()
            } finally {
                importing = false
            }
        }
    }

    fun importPdfAction() =
        runImport {
            val picked = pickPdf() ?: return@runImport
            importer.importPdf(
                title = picked.displayName.substringBeforeLast('.').ifBlank { "Untitled" },
                pdfBytes = picked.bytes,
                originalFilename = picked.displayName,
            )
        }

    fun importImagesAction() =
        runImport {
            val picked = pickImages()
            if (picked.isEmpty()) return@runImport
            importer.importImages(
                title =
                    picked
                        .first()
                        .displayName
                        .substringBeforeLast('.')
                        .ifBlank { "Untitled" },
                imageFiles = picked.map { it.bytes },
                originalFilename = picked.first().displayName,
            )
        }

    // A capture session's photos have no meaningful filename to derive a title guess from
    // (CameraCapture.kt names them opaquely, "capture-1.jpg", ...) -- unlike importPdfAction/
    // importImagesAction above, so this always falls back to "Untitled" rather than the
    // filename-derived guess those use. Correcting the title is exactly what M4's still-pending
    // OCR-confirmation UI (ROADMAP.md) will eventually help with; M4's camera-capture slice on
    // its own doesn't have a better signal to offer yet.
    fun importLocally(captured: List<PickedFile>) =
        runImport {
            importer.importImages(title = "Untitled", imageFiles = captured.map { it.bytes })
        }

    fun importCaptureAction(capture: suspend () -> List<PickedFile>) {
        importMenuExpanded = false
        scope.launch {
            val captured = withContext(Dispatchers.IO) { capture() }
            if (captured.isEmpty()) return@launch
            if (sendCaptureSession != null && trustStore != null && trustStore.list().isNotEmpty()) {
                // Ask -- see this function's doc for why local import is always the fallback, never
                // a silently-lost batch.
                pendingCaptureChoice = captured
            } else {
                importLocally(captured)
            }
        }
    }

    fun sendPendingCapture(peer: TrustedPeer) {
        val captured = pendingCaptureChoice ?: return
        pendingCaptureChoice = null
        val send = sendCaptureSession ?: return
        scope.launch {
            statusMessage = "Sending to ${peer.displayName}..."
            val outcome = withContext(Dispatchers.IO) { send(peer, "Untitled", captured.map { it.bytes }) }
            when (outcome) {
                is CaptureSessionSendOutcome.Sent -> statusMessage = "Sent to ${peer.displayName}"
                is CaptureSessionSendOutcome.PeerNotFound ->
                    statusMessage = "${peer.displayName} wasn't found on the network -- is it running? Importing locally instead."
                is CaptureSessionSendOutcome.Failed ->
                    statusMessage = "Couldn't send to ${peer.displayName} (${outcome.reason}) -- importing locally instead."
            }
            // Both failure outcomes above fall back to local import, same "never lose a just
            // -captured batch" reasoning as dismissing the dialog outright -- only a confirmed Sent
            // skips it, since the photos already have a home on the peer's device.
            if (outcome !is CaptureSessionSendOutcome.Sent) importLocally(captured)
        }
    }

    fun importPendingCaptureLocally() {
        val captured = pendingCaptureChoice ?: return
        pendingCaptureChoice = null
        importLocally(captured)
    }

    pendingCaptureChoice?.let { captured ->
        val peer = trustStore?.list()?.firstOrNull()
        AlertDialog(
            onDismissRequest = ::importPendingCaptureLocally,
            title = { Text("Send captured pages?") },
            text = {
                Text(
                    if (peer != null) {
                        "Send ${captured.size} captured page(s) to ${peer.displayName}, or import them on this device?"
                    } else {
                        "Import ${captured.size} captured page(s) on this device?"
                    },
                )
            },
            confirmButton = {
                if (peer != null) {
                    TextButton(onClick = { sendPendingCapture(peer) }, modifier = Modifier.testTag(TestTags.CAPTURE_SEND_TO_DESKTOP)) {
                        Text("Send to ${peer.displayName}")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = ::importPendingCaptureLocally,
                    modifier = Modifier.testTag(TestTags.CAPTURE_IMPORT_LOCALLY),
                ) { Text("Import locally") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inkstave") },
                actions = {
                    TextButton(onClick = onOpenPairing, modifier = Modifier.testTag(TestTags.PAIRING_ENTRY)) {
                        Text("Sync Pairing")
                    }
                    TextButton(onClick = onOpenPedalSettings, modifier = Modifier.testTag(TestTags.PEDAL_SETTINGS_ENTRY)) {
                        Text("Pedal Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    text = { Text(if (importing) "Importing..." else "Import") },
                    onClick = { if (!importing) importMenuExpanded = true },
                    icon = { if (importing) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) },
                    modifier = Modifier.testTag(TestTags.IMPORT_FAB),
                )
                DropdownMenu(expanded = importMenuExpanded, onDismissRequest = { importMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Import PDF") },
                        onClick = { importPdfAction() },
                        modifier = Modifier.testTag(TestTags.IMPORT_PDF_MENU_ITEM),
                    )
                    DropdownMenuItem(
                        text = { Text("Import images") },
                        onClick = { importImagesAction() },
                        modifier = Modifier.testTag(TestTags.IMPORT_IMAGES_MENU_ITEM),
                    )
                    if (captureImages != null) {
                        DropdownMenuItem(
                            text = { Text("Capture photos") },
                            onClick = { importCaptureAction(captureImages) },
                            modifier = Modifier.testTag(TestTags.CAPTURE_PHOTOS_MENU_ITEM),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            statusMessage?.let { Text(it, modifier = Modifier.padding(12.dp).testTag(TestTags.CAPTURE_STATUS_MESSAGE)) }
            if (scores.isEmpty()) {
                EmptyLibrary(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(TestTags.SCORE_LIST)) {
                    items(scores, key = ScoreSummary::id) { score ->
                        ListItem(
                            headlineContent = { Text(score.title) },
                            supportingContent = { score.composer?.let { Text(it) } },
                            modifier =
                                Modifier
                                    .clickable { onOpenScore(score.filePath) }
                                    .testTag(TestTags.scoreListItem(score.id)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag(TestTags.EMPTY_LIBRARY), contentAlignment = Alignment.Center) {
        Column {
            Text("No scores yet", style = MaterialTheme.typography.titleMedium)
            Text("Import a PDF or a set of images to get started.")
        }
    }
}

/**
 * Stable Compose `testTag` identifiers for [LibraryScreen] and [ViewerScreen],
 * kept in one place so UI tests (`client/shared/src/desktopTest/.../ui/`)
 * reference the same constants the composables set, rather than duplicating
 * tag strings that could silently drift apart. Not part of this module's
 * public API surface for app code -- UI tests only.
 */
internal object TestTags {
    const val IMPORT_FAB = "library-fab-import"
    const val IMPORT_PDF_MENU_ITEM = "library-menu-import-pdf"
    const val IMPORT_IMAGES_MENU_ITEM = "library-menu-import-images"
    const val CAPTURE_PHOTOS_MENU_ITEM = "library-menu-capture-photos"
    const val CAPTURE_SEND_TO_DESKTOP = "library-capture-send-to-desktop"
    const val CAPTURE_IMPORT_LOCALLY = "library-capture-import-locally"
    const val CAPTURE_STATUS_MESSAGE = "library-capture-status-message"
    const val EMPTY_LIBRARY = "library-empty-state"
    const val SCORE_LIST = "library-score-list"
    const val VIEWER_PAGE_INDICATOR = "viewer-page-indicator"
    const val VIEWER_TAP_ZONE_PREVIOUS = "viewer-tap-zone-previous"
    const val VIEWER_TAP_ZONE_NEXT = "viewer-tap-zone-next"
    const val VIEWER_BACK_BUTTON = "viewer-back-button"
    const val ANNOTATION_TOOLBAR = "annotation-toolbar"
    const val ANNOTATION_DELETE_BUTTON = "annotation-delete-button"
    const val TEXT_DIALOG_FIELD = "annotation-text-dialog-field"
    const val TEXT_DIALOG_CONFIRM = "annotation-text-dialog-confirm"
    const val PERFORMANCE_MODE_TOGGLE = "viewer-performance-mode-toggle"
    const val OVERLAP_TURN_TOGGLE = "viewer-overlap-turn-toggle"
    const val PEDAL_SETTINGS_ENTRY = "library-pedal-settings-entry"
    const val PEDAL_SETTINGS_RESET = "pedal-settings-reset"
    const val PEDAL_SETTINGS_CAPTURE_PROMPT = "pedal-settings-capture-prompt"
    const val PEDAL_SETTINGS_BACK = "pedal-settings-back"
    const val PAIRING_ENTRY = "library-pairing-entry"
    const val PAIRING_CONFIRMATION = "pairing-confirmation"
    const val PAIRING_SHORT_CODE = "pairing-short-code"
    const val PAIRING_CONFIRM = "pairing-confirm"
    const val PAIRING_REJECT = "pairing-reject"
    const val PAIRING_DEVICE_LIST = "pairing-device-list"
    const val PAIRING_TRUSTED_LIST = "pairing-trusted-list"
    const val PAIRING_BACK = "pairing-back"

    fun scoreListItem(scoreId: String) = "library-score-item-$scoreId"

    fun modeButton(mode: AnnotationMode) = "annotation-mode-${mode.name.lowercase()}"

    fun pedalActionRow(action: PedalAction) = "pedal-settings-action-${action.name.lowercase()}"

    fun pedalAddBindingButton(action: PedalAction) = "pedal-settings-add-${action.name.lowercase()}"

    fun pedalBindingChip(
        action: PedalAction,
        key: Key,
    ) = "pedal-settings-chip-${action.name.lowercase()}-${key.keyCode}"

    fun pedalBindingChipRemove(
        action: PedalAction,
        key: Key,
    ) = "pedal-settings-chip-remove-${action.name.lowercase()}-${key.keyCode}"

    fun pairingDeviceButton(deviceId: String) = "pairing-device-$deviceId"

    fun pairingRevokeButton(deviceId: String) = "pairing-revoke-$deviceId"
}
