package app.inkstave.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder root composable shared by [app.inkstave.android] and the
 * desktop app -- this is M0 scaffolding, not M1's real library/viewer UI.
 * Both platform entry points render this so the two apps stay on one
 * Compose Multiplatform UI tree from the very first commit, per
 * `docs/architecture.md`.
 */
@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Inkstave")
            }
        }
    }
}
