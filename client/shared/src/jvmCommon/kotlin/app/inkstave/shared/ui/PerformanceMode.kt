package app.inkstave.shared.ui

import androidx.compose.runtime.Composable

/**
 * Keeps the screen from sleeping while [enabled] (performance mode), so a device doesn't lock mid-performance.
 * The desktop implementation is a deliberate no-op: display sleep there is the user's own OS setting.
 */
@Composable
expect fun KeepScreenOnEffect(enabled: Boolean)
