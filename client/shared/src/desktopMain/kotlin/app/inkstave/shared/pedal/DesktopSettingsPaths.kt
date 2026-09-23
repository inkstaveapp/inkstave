package app.inkstave.shared.pedal

import java.io.File

/**
 * Where the desktop app keeps user-editable settings (currently just the
 * pedal key mapping, `ROADMAP.md` M3) -- distinct from
 * `app.inkstave.shared.library.DesktopLibraryPaths`, which uses
 * `$XDG_DATA_HOME`: settings are configuration a user edits, not data the
 * app generates, and XDG draws that line deliberately (`$XDG_CONFIG_HOME`,
 * falling back to `~/.config`, is the correct directory class for this,
 * not `~/.local/share`).
 */
object DesktopSettingsPaths {
    private const val APP_DIR_NAME = "inkstave"

    private fun xdgConfigHome(): File {
        val override = System.getenv("XDG_CONFIG_HOME")
        return if (!override.isNullOrBlank()) {
            File(override)
        } else {
            File(System.getProperty("user.home"), ".config")
        }
    }

    /** File path for the saved pedal key mapping ([PedalSettingsStore]). */
    fun pedalSettingsFile(): File = File(xdgConfigHome(), "$APP_DIR_NAME/pedal-settings.json")

    /**
     * Directory for this device's sync identity and trust store (`ROADMAP.md` M4,
     * `app.inkstave.shared.sync`) -- `$XDG_CONFIG_HOME`, the same class of directory as
     * [pedalSettingsFile]: this is device configuration a user implicitly sets up by pairing,
     * not app-generated library data (`$XDG_DATA_HOME`, `DesktopLibraryPaths`).
     */
    fun syncSettingsDirectory(): File = File(xdgConfigHome(), "$APP_DIR_NAME/sync")

    /** File path for the saved trusted-peer list ([app.inkstave.shared.sync.PeerTrustStore]). */
    fun peerTrustStoreFile(): File = File(syncSettingsDirectory(), "trusted-peers.json")
}
