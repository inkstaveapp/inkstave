package app.inkstave.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.inkstave.shared.ui.App

/**
 * Android entry point. Scaffolding only (M0): renders the shared placeholder
 * [App] composable. Real library/viewer UI, camera capture, storage, and
 * pedal HID integration land in M1-M4 per `ROADMAP.md`, owned by
 * `client-ui` and `android-platform` respectively (`.claude/agents/`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
