package com.novelscraper.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a request made from inside the page says its body is. */
class PageRequestHeadersTest {

    private val form = "application/x-www-form-urlencoded"

    /** Wuxiabox's search: without its type, the site found no form and no results. */
    @Test fun anEncodedFormKeepsItsType() {
        val headers = mapOf("Content-Type" to form, "Referer" to "https://example.com/search.html")
        assertEquals(headers, pageRequestHeaders(headers, BrowserBody.Text("keyboard=sword")))
    }

    @Test fun anEncodedBodyWithoutATypeGetsTheOneItCameWith() {
        assertEquals(
            mapOf("Content-Type" to "$form;charset=UTF-8"),
            pageRequestHeaders(emptyMap(), BrowserBody.Text("a=1", "$form;charset=UTF-8")),
        )
    }

    @Test fun aTypeGivenByThePluginWins() {
        val headers = mapOf("content-type" to "application/json")
        assertEquals(headers, pageRequestHeaders(headers, BrowserBody.Text("{}", "$form;charset=UTF-8")))
    }

    /** The browser sets a form's boundary itself; a type given here would lack it. */
    @Test fun aFormOfPartsLeavesItsTypeToTheBrowser() {
        val headers = mapOf("Content-Type" to "multipart/form-data", "X-Requested-With" to "XMLHttpRequest")
        assertEquals(
            mapOf("X-Requested-With" to "XMLHttpRequest"),
            pageRequestHeaders(headers, BrowserBody.Form(listOf("action" to "list"))),
        )
    }
}
