package app.inkstave.shared.library

import java.io.File

/** Where the desktop app keeps its `.smpk` library and index database: under `$XDG_DATA_HOME` (default `~/.local/share`). */
object DesktopLibraryPaths {
    private const val APP_DIR_NAME = "inkstave"

    private fun xdgDataHome(): File {
        val override = System.getenv("XDG_DATA_HOME")
        return if (!override.isNullOrBlank()) {
            File(override)
        } else {
            File(System.getProperty("user.home"), ".local/share")
        }
    }

    /** Directory `.smpk` files live in, created if it doesn't exist yet. */
    fun libraryDirectory(): File = File(xdgDataHome(), "$APP_DIR_NAME/library").apply { mkdirs() }

    /** File path for the local SQLDelight index database (ADR-0005). */
    fun indexDatabaseFile(): File = File(xdgDataHome(), "$APP_DIR_NAME/index.db")
}
