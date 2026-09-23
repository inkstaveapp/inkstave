package app.inkstave.shared.ui

import androidx.compose.runtime.Composable

/**
 * Deliberate no-op on desktop -- see [KeepScreenOnEffect]'s own doc for
 * why. Not implemented as "do nothing at all silently"; documented here so
 * a future reader who expects `enabled = true` to do something on this
 * platform finds the reasoning immediately, not a mystery.
 */
@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    // Intentionally no platform call here -- see this file's module doc.
}
