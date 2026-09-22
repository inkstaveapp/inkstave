package app.inkstave.shared.index

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Builds the [SqlDriver] the Android app passes into [LibraryIndexRepository].
 * Lives here (not in `androidApp`) so `androidApp` never needs a direct
 * dependency on `app.cash.sqldelight:android-driver` itself -- it only needs
 * this class and the common [SqlDriver] type.
 */
class AndroidSqlDriverFactory(
    private val context: Context,
) {
    fun create(): SqlDriver = AndroidSqliteDriver(InkstaveDatabase.Schema, context, "inkstave-index.db")
}
