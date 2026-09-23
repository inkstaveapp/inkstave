package app.inkstave.shared.ui

import androidx.compose.runtime.Composable

/**
 * Keeps the screen from sleeping/locking while [enabled] (`ROADMAP.md` M3's
 * performance-mode "screen-on lock") -- an Android-only concern in
 * practice: a phone mid-performance shouldn't lock itself while the
 * musician's hands are busy playing, not typing. A laptop's own
 * display-sleep timing is the user's own OS-level setting, not something
 * this app has a good reason to override, so the desktop `actual`
 * (`PerformanceMode.desktop.kt`) is a deliberate no-op, not an oversight --
 * see its own doc.
 */
@Composable
expect fun KeepScreenOnEffect(enabled: Boolean)
