package app.inkstave.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController

/**
 * Shows the on-screen keyboard, if [isFocused], regardless of the
 * platform's own auto-show heuristic. Pulled out as a pure function
 * (rather than inlined into the `Modifier.onFocusChanged` lambda that
 * calls it) specifically so it's unit-testable without a Compose UI test
 * harness -- see [SoftKeyboardTest] and this file's module doc.
 */
internal fun onTextFieldFocusChanged(
    isFocused: Boolean,
    controller: SoftwareKeyboardController?,
) {
    if (isFocused) controller?.show()
}

/**
 * Every text-entry composable in this app must use this instead of relying
 * on the platform to auto-show the keyboard on focus. Only adds a
 * focus-change *listener* -- it doesn't make its target focusable itself,
 * since every real caller (`TextField`/`OutlinedTextField`) already is;
 * adding a second, redundant `Modifier.focusable()` on top of a text
 * field's own internal one is exactly the kind of thing that looks
 * harmless but silently creates two competing focus targets.
 *
 * Why this exists (`ROADMAP.md` M3): once a Bluetooth page-turner pedal is
 * paired, Android's `InputMethodManager` sees a hardware keyboard is
 * connected and by default suppresses the soft keyboard's auto-show-on
 * -focus behavior. That's reasonable for an *actual* keyboard a user could
 * type on; it's actively wrong for a pedal with one to three buttons,
 * nowhere near enough to type with -- without this fix, every text-entry
 * surface in the app (starting with M2's text-annotation dialog) silently
 * becomes unusable the instant a pedal is connected, with no error and no
 * obvious cause. Explicitly requesting the keyboard on focus, rather than
 * trusting the platform's heuristic, fixes it on every platform, not just
 * the one where a pedal is likely to be connected.
 */
@Composable
fun Modifier.showSoftKeyboardOnFocus(): Modifier {
    val controller = LocalSoftwareKeyboardController.current
    return this.onFocusChanged { state -> onTextFieldFocusChanged(state.isFocused, controller) }
}
