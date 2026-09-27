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
 * Covers [onTextFieldFocusChanged], the decision behind [Modifier.showSoftKeyboardOnFocus]
 * (showing the soft keyboard even when a hardware pedal is connected), with a fake
 * [SoftwareKeyboardController] instead of a Compose UI test.
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
