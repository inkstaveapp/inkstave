package app.inkstave.shared.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Uses the window's `FLAG_KEEP_SCREEN_ON` (not a `WakeLock`, which keeps the CPU awake rather than the
 * screen). Cleared on dispose so leaving the viewer never leaves the flag set for the rest of the app.
 */
@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity, enabled) {
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
