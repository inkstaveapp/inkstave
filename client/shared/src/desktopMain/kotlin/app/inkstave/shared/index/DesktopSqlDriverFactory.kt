package app.inkstave.shared.index

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/** Builds the [SqlDriver] for [LibraryIndexRepository], backed by the SQLite file [databaseFile]; creates the schema if the file is new. */
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
