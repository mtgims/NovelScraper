package com.novelscraper.app.db

import app.cash.sqldelight.db.SqlDriver

/** The library database file ([LibraryDb]) on this platform, created or migrated
 *  to the current schema, with foreign keys enforced (chapter rows go with their
 *  novel). */
expect fun openLibraryDriver(): SqlDriver
