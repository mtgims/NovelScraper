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
    if (file != null) LibraryBackup.beforeMigration(file, onDiskVersion(file), LibraryDb.Schema.version)
    val url = if (file == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:${file.absolutePath}"
    return JdbcSqliteDriver(url, Properties().apply { put("foreign_keys", "true") }, LibraryDb.Schema)
}

/** The schema version stored in the file (0 when it is new or unreadable). */
private fun onDiskVersion(file: File): Long {
    if (!file.exists()) return 0
    return runCatching {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
    }.getOrDefault(0L)
}
