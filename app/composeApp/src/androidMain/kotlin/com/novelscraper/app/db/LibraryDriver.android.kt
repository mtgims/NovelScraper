package com.novelscraper.app.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.novelscraper.app.platform.appContext

actual fun openLibraryDriver(): SqlDriver {
    val file = appContext.getDatabasePath(DB_NAME)
    LibraryBackup.beforeMigration(file, onDiskVersion(file), LibraryDb.Schema.version)
    return AndroidSqliteDriver(
        LibraryDb.Schema, appContext, DB_NAME,
        callback = object : AndroidSqliteDriver.Callback(LibraryDb.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
}

private const val DB_NAME = "library.db"

/** The schema version stored in the file (0 when it is new or unreadable). */
private fun onDiskVersion(file: java.io.File): Long {
    if (!file.exists()) return 0
    return runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { it.version.toLong() }
    }.getOrDefault(0L)
}
