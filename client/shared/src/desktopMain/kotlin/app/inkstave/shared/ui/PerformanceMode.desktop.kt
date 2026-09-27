package app.inkstave.shared.ui

import androidx.compose.runtime.Composable

/** Deliberate no-op on desktop: display sleep is the user's OS setting (see [KeepScreenOnEffect]). */
@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    // Intentionally empty.
}
