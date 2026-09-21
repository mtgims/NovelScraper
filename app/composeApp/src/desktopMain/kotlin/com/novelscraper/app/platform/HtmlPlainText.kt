package com.novelscraper.app.platform

import org.ccil.cowan.tagsoup.HTMLSchema
import org.ccil.cowan.tagsoup.Parser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader

/**
 * Chapter HTML to plain text, exactly as Android's
 * `HtmlCompat.fromHtml(html, FROM_HTML_MODE_COMPACT).toString()` produces it.
 *
 * The reader's sentence highlighting, the narrator and saved positions all index
 * sentences of this text, so the desktop app has to split a chapter the same way
 * the phone does. This is a port of the text-producing half of Android's
 * `android.text.Html.HtmlToSpannedConverter` (AOSP, Apache 2.0) running on the
 * same parser Android uses (TagSoup with its HTML schema); styling spans are
 * dropped because only the characters matter here. Parity with the real Android
 * code is checked by the html-parity tests (androidUnitTest runs the framework
 * code under Robolectric, desktopTest runs this).
 */
object HtmlPlainText {
    private val schema = HTMLSchema()

    fun convert(html: String): String {
        val handler = Converter()
        val parser = Parser()
        parser.setProperty(Parser.schemaProperty, schema)
        parser.contentHandler = handler
        parser.parse(InputSource(StringReader(html)))
        return handler.text.toString()
    }

    // In compact mode every block element (p, ul, li, div, blockquote, h1-h6) wants
    // one newline before and after. Android keeps a "Newline" mark per open block
    // and consumes the most recent one on each block end: a stack.
    private val BLOCKS = setOf("p", "ul", "li", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6")

    private class Converter : DefaultHandler() {
        val text = StringBuilder()
        private var openBlocks = 0

        override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
            val tag = localName.lowercase()
            when {
                tag in BLOCKS -> { appendNewlines(1); openBlocks++ }
                // <br> is emitted on its end tag; TagSoup closes every <br>.
                tag == "img" -> text.append('￼')
            }
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            val tag = localName.lowercase()
            when {
                tag == "br" -> text.append('\n')
                tag in BLOCKS -> if (openBlocks > 0) { openBlocks--; appendNewlines(1) }
            }
        }

        // Collapse runs of ' ' and '\n' (only those two; tabs and no-break spaces
        // are kept), and drop them right after a newline or at the very start.
        override fun characters(ch: CharArray, start: Int, length: Int) {
            val sb = StringBuilder()
            for (i in 0 until length) {
                val c = ch[start + i]
                if (c == ' ' || c == '\n') {
                    val pred = when {
                        sb.isNotEmpty() -> sb[sb.length - 1]
                        text.isEmpty() -> '\n'
                        else -> text[text.length - 1]
                    }
                    if (pred != ' ' && pred != '\n') sb.append(' ')
                } else {
                    sb.append(c)
                }
            }
            text.append(sb)
        }

        override fun ignorableWhitespace(ch: CharArray, start: Int, length: Int) {}

        private fun appendNewlines(min: Int) {
            if (text.isEmpty()) return
            var existing = 0
            var i = text.length - 1
            while (i >= 0 && text[i] == '\n') { existing++; i-- }
            repeat(min - existing) { text.append('\n') }
        }
    }
}
