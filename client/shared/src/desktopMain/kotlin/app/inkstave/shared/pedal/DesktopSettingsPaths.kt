package app.inkstave.shared.pedal

import java.io.File

/** Where the desktop app keeps settings: under `$XDG_CONFIG_HOME` (default `~/.config`), since they're configuration, not data. */
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

    /** Directory for this device's sync identity and trust store. */
    fun syncSettingsDirectory(): File = File(xdgConfigHome(), "$APP_DIR_NAME/sync")

    /** File path for the saved trusted-peer list ([app.inkstave.shared.sync.PeerTrustStore]). */
    fun peerTrustStoreFile(): File = File(syncSettingsDirectory(), "trusted-peers.json")
}
