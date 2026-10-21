package com.novelscraper.app.library

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.novelscraper.app.db.jdbcLibraryDriver
import com.novelscraper.app.extensions.ChapterItem
import com.novelscraper.app.extensions.SourceNovel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/** What a change that syncs costs the database, per record. */
class SyncCostTest {

    /** Counts every statement it runs. */
    private class Counting(private val d: SqlDriver) : SqlDriver by d {
        val statements = AtomicInteger()
        override fun <R> executeQuery(
            identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> { statements.incrementAndGet(); return d.executeQuery(identifier, sql, mapper, parameters, binders) }
        override fun execute(
            identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> { statements.incrementAndGet(); return d.execute(identifier, sql, parameters, binders) }
    }

    private class Source : SourceOrigin {
        override suspend fun name(pluginId: String) = "Fake"
        override suspend fun novel(pluginId: String, path: String) =
            SourceNovel(name = "Novel", path = path, chapters = (1..60).map { ChapterItem("Chapter $it", "$path/c$it") })
        override suspend fun chapter(pluginId: String, path: String) = "<p>$path</p>"
        override suspend fun webUrl(pluginId: String, path: String) = null
    }

    @Test fun markingAChapterReadCostsAFewStatements() = runBlocking<Unit> {
        val driver = Counting(jdbcLibraryDriver(null))
        val lib = LibraryStore(driver, Source(), LibraryStoreTest.offlineServer())
        val id = lib.openSource("fake", "n", "Novel", null).also { lib.refresh(it) }
        lib.setInLibrary(id, true)

        driver.statements.set(0)
        lib.setRead(id, listOf(1), true)
        val one = driver.statements.get()
        driver.statements.set(0)
        lib.setRead(id, (2..51).toList(), true)
        val fifty = driver.statements.get()
        val perChapter = (fifty - one) / 49.0
        println("marking read: $one statements for one chapter, $fifty for fifty, ${"%.1f".format(perChapter)} per extra chapter")
        assertTrue(perChapter <= 6, "${"%.1f".format(perChapter)} statements for each further chapter")
    }
}
