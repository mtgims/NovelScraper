package com.novelscraper.app.sync

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavFolderTest {

    @Test
    fun theLibraryGoesInItsOwnDirectoryUnderTheAddress() {
        assertEquals("https://cloud.example.org/remote.php/dav/files/me/NovelScraper/",
            WebDavFolder.folderUrl("cloud.example.org/remote.php/dav/files/me").toString())
        assertEquals("https://cloud.example.org/dav/NovelScraper/",
            WebDavFolder.folderUrl("https://cloud.example.org/dav/").toString())
        assertEquals("https://cloud.example.org/dav/NovelScraper/",
            WebDavFolder.folderUrl("https://cloud.example.org/dav/NovelScraper").toString(),
            "a second device given the full path does not nest another")
        assertFailsWith<IllegalArgumentException> { WebDavFolder.folderUrl("ftp://x") }
    }

    @Test
    fun aPasswordGoesOverPlainHttpOnlyOnTheLocalNetwork() = runBlocking {
        assertTrue(WebDavFolder.safeToSignIn("https://cloud.example.org/".toHttpUrl()))
        assertTrue(WebDavFolder.safeToSignIn("http://127.0.0.1:8080/".toHttpUrl()))
        assertTrue(WebDavFolder.safeToSignIn("http://192.168.1.20/".toHttpUrl()))
        assertTrue(WebDavFolder.safeToSignIn("http://[fd00::5]/".toHttpUrl()))
        assertFalse(WebDavFolder.safeToSignIn("http://8.8.8.8/".toHttpUrl()))
    }

    /** As Nextcloud answers: the directory, a subdirectory, files by path. */
    @Test
    fun aListingIsTheFilesInTheDirectory() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:href>/remote.php/dav/files/me/NovelScraper/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/me/NovelScraper/old/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/me/NovelScraper/novelscraper.json</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/me/NovelScraper/a-1.0000000003.seg</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1200</d:getcontentlength></d:prop></d:propstat>
                <d:propstat><d:prop><oc:size/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
            </d:multistatus>"""
        assertEquals(
            listOf(RemoteFile("novelscraper.json", 42), RemoteFile("a-1.0000000003.seg", 1200)),
            WebDavFolder.parseListing(xml),
        )
    }

    /** Other servers: another prefix, full URLs, encoded names, no lengths. */
    @Test
    fun hrefsMayBeFullUrlsAndEncoded() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>https://dav.example.org/My%20Files/NovelScraper/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
              <D:response><D:href>https://dav.example.org/My%20Files/NovelScraper/b%2Bc.0000000001.snap</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            </D:multistatus>"""
        assertEquals(listOf(RemoteFile("b+c.0000000001.snap", 0)), WebDavFolder.parseListing(xml))
    }

    @Test
    fun aListingWithADoctypeIsRefused() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>&e;</d:href></d:response></d:multistatus>"""
        assertTrue(runCatching { WebDavFolder.parseListing(xml) }.isFailure)
    }
}
