package app.inkstave.shared.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` -- the current,
 * non-deprecated way to keep an Android screen on while a specific
 * `Activity`/window is showing (unlike a `PowerManager.WakeLock` held
 * independent of any window, which is the older, heavier-weight mechanism
 * for a different problem: keeping the *CPU* awake with the screen off).
 * Cleared on dispose (mode turned off, or the viewer screen leaves
 * composition) so leaving the viewer -- with performance mode still
 * enabled or not -- never leaves the flag stuck on for the rest of the
 * app.
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
