package app.inkstave.shared.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.inkstave.shared.pedal.PedalAction
import app.inkstave.shared.pedal.PedalKeyMapping
import kotlinx.coroutines.delay

/**
 * Shows and edits which keys trigger which [PedalAction]. The "press the key you want to use" capture flow works
 * for any pedal, whatever it sends, so users don't need to know their pedal's key codes.
 *
 * @param onRawKeyHandlerChange the Android key-dispatch bridge [ViewerScreen] also uses; registered only while
 *   [capturingFor] is non-null, so stray key presses do nothing otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PedalSettingsScreen(
    mapping: PedalKeyMapping,
    onMappingChange: (PedalKeyMapping) -> Unit,
    onBack: () -> Unit,
    onRawKeyHandlerChange: (((Key) -> Boolean)?) -> Unit = {},
) {
    var capturingFor by remember { mutableStateOf<PedalAction?>(null) }
    var lastCaptured by remember { mutableStateOf<String?>(null) }

    fun onKeyCaptured(
        action: PedalAction,
        key: Key,
    ) {
        onMappingChange(mapping.withBinding(action, key))
        lastCaptured = "Bound ${keyDisplayName(key)} to ${actionLabel(action)}"
        capturingFor = null
    }

    DisposableEffect(onRawKeyHandlerChange, capturingFor) {
        val action = capturingFor
        if (action != null) {
            onRawKeyHandlerChange { key ->
                onKeyCaptured(action, key)
                true
            }
        }
        onDispose { onRawKeyHandlerChange(null) }
    }

    // The "Bound X to Y" confirmation is transient, so it clears itself after a few seconds.
    LaunchedEffect(lastCaptured) {
        if (lastCaptured != null) {
            delay(CAPTURE_CONFIRMATION_MILLIS)
            lastCaptured = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pedal Settings") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Bind the key(s) your pedal sends to each action. Not sure what your " +
                    "pedal sends? Tap \"Add binding\", then press the pedal.",
                style = MaterialTheme.typography.bodyMedium,
            )

            // weight(1f), not fillMaxSize(): filling the height pushed the capture prompt and the Reset/Back row
            // off-screen.
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                items(PedalAction.entries) { action ->
                    PedalActionRow(
                        action = action,
                        keys = mapping.keysFor(action),
                        isCapturing = capturingFor == action,
                        onStartCapture = {
                            capturingFor = action
                            lastCaptured = null
                        },
                        onKeyCapturedFromComposeFocus = { key -> onKeyCaptured(action, key) },
                        onRemoveKey = { key -> onMappingChange(mapping.withoutBinding(action, key)) },
                    )
                }
            }

            lastCaptured?.let { message ->
                Text(message, modifier = Modifier.testTag(TestTags.PEDAL_SETTINGS_CAPTURE_PROMPT))
            }

            Row(modifier = Modifier.padding(top = 16.dp)) {
                TextButton(
                    onClick = { onMappingChange(PedalKeyMapping.DEFAULT) },
                    modifier = Modifier.testTag(TestTags.PEDAL_SETTINGS_RESET),
                ) { Text("Reset to defaults") }
                TextButton(onClick = onBack, modifier = Modifier.testTag(TestTags.PEDAL_SETTINGS_BACK)) { Text("Back") }
            }
        }
    }
}

/** How long [PedalSettingsScreen]'s "Bound X to Y" confirmation stays visible before clearing itself. */
private const val CAPTURE_CONFIRMATION_MILLIS = 4_000L

@Composable
private fun PedalActionRow(
    action: PedalAction,
    keys: Set<Key>,
    isCapturing: Boolean,
    onStartCapture: () -> Unit,
    onKeyCapturedFromComposeFocus: (Key) -> Unit,
    onRemoveKey: (Key) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(TestTags.pedalActionRow(action))) {
        Text(actionLabel(action), style = MaterialTheme.typography.titleSmall)
        Row {
            if (keys.isEmpty()) {
                Text("No keys bound", style = MaterialTheme.typography.bodySmall)
            } else {
                keys.forEach { key -> BindingChip(action = action, key = key, onRemove = { onRemoveKey(key) }) }
            }
        }
        if (isCapturing) {
            // Desktop's half of key capture; Android also needs the Activity-dispatch bridge
            // (see onRawKeyHandlerChange).
            CaptureSurface(
                prompt = "Press the key you want to use for ${actionLabel(action)} now…",
                onKeyCaptured = onKeyCapturedFromComposeFocus,
            )
        } else {
            TextButton(onClick = onStartCapture, modifier = Modifier.testTag(TestTags.pedalAddBindingButton(action))) {
                Text("Add binding")
            }
        }
    }
}

@Composable
private fun BindingChip(
    action: PedalAction,
    key: Key,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(end = 8.dp).testTag(TestTags.pedalBindingChip(action, key)),
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(keyDisplayName(key))
            // Own testTag: UI tests must click the clickable node itself, not its non-clickable wrapper.
            TextButton(
                onClick = onRemove,
                modifier = Modifier.padding(start = 4.dp).testTag(TestTags.pedalBindingChipRemove(action, key)),
            ) { Text("×") }
        }
    }
}

/**
 * The key-listening surface: requests focus when shown, then reports the next key press. Enough on desktop; on
 * Android it backs up [PedalSettingsScreen]'s `onRawKeyHandlerChange` bridge. If both fire for one press they
 * apply the same binding, which is harmless.
 */
@Composable
private fun CaptureSurface(
    prompt: String,
    onKeyCaptured: (Key) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        onKeyCaptured(event.key)
                        true
                    } else {
                        false
                    }
                }.testTag(TestTags.PEDAL_SETTINGS_CAPTURE_PROMPT),
        tonalElevation = 3.dp,
    ) {
        Text(prompt, modifier = Modifier.padding(12.dp))
    }
}

private fun actionLabel(action: PedalAction): String =
    when (action) {
        PedalAction.NEXT_PAGE -> "Next page"
        PedalAction.PREVIOUS_PAGE -> "Previous page"
    }

/**
 * A readable name for [key] when one is known, otherwise its raw key code, which still lets a user check that an
 * unfamiliar pedal sends a distinct code per button.
 */
internal fun keyDisplayName(key: Key): String =
    when (key) {
        Key.DirectionLeft -> "Left arrow"
        Key.DirectionRight -> "Right arrow"
        Key.DirectionUp -> "Up arrow"
        Key.DirectionDown -> "Down arrow"
        Key.PageUp -> "Page Up"
        Key.PageDown -> "Page Down"
        Key.Spacebar -> "Space"
        Key.Enter -> "Enter"
        Key.Tab -> "Tab"
        else -> "Key(${key.keyCode})"
    }
