package app.inkstave.shared.ui

import androidx.compose.ui.platform.SoftwareKeyboardController
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingKeyboardController : SoftwareKeyboardController {
    var showCalls = 0
    var hideCalls = 0

    override fun show() {
        showCalls++
    }

    override fun hide() {
        hideCalls++
    }
}

/**
 * Covers [onTextFieldFocusChanged] -- the pure decision behind
 * [Modifier.showSoftKeyboardOnFocus] (`ROADMAP.md` M3's pedal-suppresses
 * -the-soft-keyboard fix) -- without needing a Compose UI test harness
 * (`compose.uiTest`, unresolvable in this sandboxed session; see
 * `client/README.md`'s "Known rough edges"). Extracting the actual
 * gain-focus-or-not-then-show logic into a plain function taking a
 * `Boolean` and a fake [SoftwareKeyboardController] -- rather than only
 * being reachable through the `Modifier.onFocusChanged` lambda that calls
 * it -- is what makes this fully testable without that dependency at all,
 * not just a partial workaround for it.
 */
class SoftKeyboardTest {
    @Test
    fun `gaining focus shows the keyboard`() {
        val controller = RecordingKeyboardController()
        onTextFieldFocusChanged(isFocused = true, controller = controller)
        assertEquals(1, controller.showCalls)
        assertEquals(0, controller.hideCalls)
    }

    @Test
    fun `losing focus does not hide the keyboard -- only gaining focus is this function's job`() {
        val controller = RecordingKeyboardController()
        onTextFieldFocusChanged(isFocused = false, controller = controller)
        assertEquals(0, controller.showCalls)
        assertEquals(0, controller.hideCalls)
    }

    @Test
    fun `a null controller -- no active text input session -- is a no-op, not a crash`() {
        onTextFieldFocusChanged(isFocused = true, controller = null)
    }
}
