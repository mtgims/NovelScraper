package com.novelscraper.app.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.novelscraper.app.platform.appFilesDir
import java.io.File
import java.util.Properties

actual fun openLibraryDriver(): SqlDriver = jdbcLibraryDriver(File(appFilesDir(), "library.db"))

/** A JDBC SQLite driver for [file] (or in memory when null, for tests). The
 *  schema version is kept in SQLite's user_version, as on Android. */
fun jdbcLibraryDriver(file: File?): SqlDriver {
    file?.parentFile?.mkdirs()
    val url = if (file == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${file.absolutePath}"
    return JdbcSqliteDriver(url, Properties().apply { put("foreign_keys", "true") }, LibraryDb.Schema)
}
