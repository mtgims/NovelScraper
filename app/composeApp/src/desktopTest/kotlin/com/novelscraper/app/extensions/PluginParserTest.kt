package com.novelscraper.app.extensions

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which HTML parser a plugin's cheerio gets. NovelScraper's own sources get
 * htmlparser2 (several times faster in QuickJS, and checked against every
 * recorded page); anyone else's keep parse5, which adds what a page leaves
 * out (an implied <body>, a <tbody> in a bare table), as LNReader's app does.
 */
class PluginParserTest {
    private val env = PluginEnvironment(OkHttpClient(), "TestAgent/1.0", { mutableMapOf() })

    /** Reports, for one page given inline, what the plugin's cheerio made of it. */
    private fun plugin(id: String) = """
        const { load } = require('cheerio');
        exports.default = {
          id: '$id', name: 'P', version: '1', site: 'https://example.invalid/',
          async parseChapter(path) {
            const ${'$'} = load('<table><tr><td>a</td></tr></table><p>hands — not\r\nthe</p>');
            return [${'$'}('body').length, ${'$'}('tbody tr').length, ${'$'}('p').html()].join('|');
          },
        };
    """.trimIndent()

    private fun parsed(id: String) = runBlocking { PluginRuntime.load(id, plugin(id), env).use { it.chapter("x") } }

    @Test fun anyoneElsesPluginKeepsParse5() {
        assertEquals("1|1|hands — not\nthe", parsed("royalroad"))
    }

    @Test fun ourOwnSourcesGetTheFastParserWrittenBackTheSame() {
        // No implied <body> or <tbody>; characters stay characters and line
        // ends are "\n", as parse5 writes them.
        assertEquals("0|0|hands — not\nthe", parsed("novelscraper.royalroad"))
    }
}
