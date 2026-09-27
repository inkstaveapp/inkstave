package app.inkstave.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Shows the on-screen keyboard if [isFocused], regardless of the platform's auto-show heuristic. A separate
 * function so it can be unit-tested without a Compose UI harness ([SoftKeyboardTest]).
 */
internal fun onTextFieldFocusChanged(
    isFocused: Boolean,
    controller: SoftwareKeyboardController?,
) {
    if (isFocused) controller?.show()
}

/**
 * Every text-entry composable in this app must use this. A Bluetooth page-turner pedal registers as a hardware
 * keyboard, which makes Android stop auto-showing the soft keyboard on focus, leaving text fields unusable while
 * a pedal is connected; this requests the keyboard explicitly on focus.
 *
 * Only adds a focus listener: callers (`TextField`/`OutlinedTextField`) are already focusable, and adding
 * another `Modifier.focusable()` would create two competing focus targets.
 */
@Composable
fun Modifier.showSoftKeyboardOnFocus(): Modifier {
    val controller = LocalSoftwareKeyboardController.current
    return this.onFocusChanged { state -> onTextFieldFocusChanged(state.isFocused, controller) }
}
