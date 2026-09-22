package app.inkstave.shared.library

import java.io.File

/**
 * Where the desktop app keeps its library (`.smpk` files) and local index
 * database (ADR-0005) by default -- there's no settings UI to override this
 * yet (`docs/format-spec.md`'s "Local index vs. source of truth" section;
 * `ROADMAP.md` doesn't call for one until later). Follows the XDG Base
 * Directory convention (`$XDG_DATA_HOME`, falling back to `~/.local/share`
 * per the spec), the standard Linux convention for a desktop app's own data.
 */
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
