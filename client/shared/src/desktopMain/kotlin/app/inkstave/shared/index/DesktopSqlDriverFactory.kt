package app.inkstave.shared.index

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * Builds the [SqlDriver] the desktop app passes into [LibraryIndexRepository],
 * backed by a real SQLite file at [databaseFile] (the same JDBC driver
 * `docs/decisions/0001-client-framework.md` already commits to for the
 * desktop target). Runs [InkstaveDatabase.Schema.create] once, the first
 * time the database file doesn't exist yet -- on every later launch the
 * existing file is opened as-is.
 */
class DesktopSqlDriverFactory(
    private val databaseFile: File,
) {
    fun create(): SqlDriver {
        val isNewDatabase = !databaseFile.exists()
        databaseFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
        if (isNewDatabase) {
            InkstaveDatabase.Schema.create(driver)
        }
        return driver
    }
}
