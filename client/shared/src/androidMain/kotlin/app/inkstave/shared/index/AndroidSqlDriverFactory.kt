package app.inkstave.shared.index

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/** Builds the [SqlDriver] for [LibraryIndexRepository], so `androidApp` needn't depend on the SQLDelight Android driver. */
class AndroidSqlDriverFactory(
    private val context: Context,
) {
    fun create(): SqlDriver = AndroidSqliteDriver(InkstaveDatabase.Schema, context, "inkstave-index.db")
}
