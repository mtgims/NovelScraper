package com.novelscraper.app.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.novelscraper.app.platform.appContext

actual fun openLibraryDriver(): SqlDriver =
    AndroidSqliteDriver(
        LibraryDb.Schema, appContext, "library.db",
        callback = object : AndroidSqliteDriver.Callback(LibraryDb.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    )
