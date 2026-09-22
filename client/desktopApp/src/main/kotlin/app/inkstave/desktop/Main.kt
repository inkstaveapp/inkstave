package app.inkstave.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.inkstave.shared.ui.App

/**
 * Linux desktop entry point. Scaffolding only (M0): renders the shared
 * placeholder [App] composable in a plain window. Real desktop chrome,
 * pedal input, and the processing-service client land in M1+ per
 * `ROADMAP.md`, owned by `client-ui` and `linux-desktop` (`.claude/agents/`).
 */
fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "Inkstave") {
            App()
        }
    }
