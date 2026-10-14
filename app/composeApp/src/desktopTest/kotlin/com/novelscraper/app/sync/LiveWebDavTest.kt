package com.novelscraper.app.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WebDavFolder] against a real server, only with
 * `-PliveSync=webdav:<address>|<user>|<password>`. The library's directory
 * under the address is emptied first and left empty.
 */
class LiveWebDavTest {

    private val spec = System.getProperty("live.sync").orEmpty()

    private fun folder(password: String? = null): WebDavFolder? {
        if (!spec.startsWith("webdav:")) return null
        val (address, user, pw) = spec.removePrefix("webdav:").split('|').let { Triple(it[0], it[1], it[2]) }
        return WebDavFolder(address, user, password ?: pw, OkHttpClient())
    }

    @Test
    fun theFolderBehavesAsSyncNeedsIt() = runBlocking<Unit> {
        val f = folder() ?: return@runBlocking
        f.list().forEach { f.delete(it.name) }
        assertTrue(f.list().isEmpty())

        val a = byteArrayOf(1, 2, 3)
        f.write("dev.0000000001.seg", a)
        f.write("novelscraper.json", "{}".toByteArray())
        assertEquals(setOf("dev.0000000001.seg" to 3L, "novelscraper.json" to 2L),
            f.list().map { it.name to it.size }.toSet())
        assertContentEquals(a, f.read("dev.0000000001.seg"))

        f.write("dev.0000000001.seg", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), f.read("dev.0000000001.seg"), "a write replaces")

        f.delete("dev.0000000001.seg")
        assertNull(f.read("dev.0000000001.seg"))
        f.delete("dev.0000000001.seg")   // already gone: fine
        f.delete("novelscraper.json")
        assertTrue(f.list().isEmpty())
    }

    @Test
    fun aWrongPasswordIsAnAuthFailure() = runBlocking<Unit> {
        val f = folder(password = "certainly-not-it") ?: return@runBlocking
        assertFailsWith<FolderAuthException> { f.list() }
    }
}
