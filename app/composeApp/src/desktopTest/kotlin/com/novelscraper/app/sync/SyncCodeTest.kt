package com.novelscraper.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncCodeTest {

    @Test
    fun aCodeReadsBackAsItself() {
        val code = SyncCode.generate()
        val text = code.toString()
        assertTrue(Regex("^([0-9A-Z]{5}-){5}[0-9A-Z]{5}$").matches(text), text)
        assertEquals(code, SyncCode.parse(text))
        // Typed loosely: lower case, spaces, no dashes.
        assertEquals(code, SyncCode.parse(text.lowercase().replace("-", " ")))
        assertNotEquals(code, SyncCode.generate())
    }

    @Test
    fun lookalikeLettersAreForgiven() {
        val code = generateSequence { SyncCode.generate() }.first { '0' in it.toString() || '1' in it.toString() }
        val sloppy = code.toString().replace('0', 'O').replace('1', 'l')
        assertEquals(code, SyncCode.parse(sloppy))
    }

    @Test
    fun aTypoIsRefusedNotTurnedIntoAnotherLibrary() {
        val text = SyncCode.generate().toString()
        val i = text.indexOfFirst { it.isLetterOrDigit() }
        val wrong = text.replaceRange(i, i + 1, if (text[i] == 'A') "B" else "A")
        assertFailsWith<SyncCodeException> { SyncCode.parse(wrong) }
        assertFailsWith<SyncCodeException> { SyncCode.parse(text.dropLast(1)) }
        assertFailsWith<SyncCodeException> { SyncCode.parse("hello") }
    }

    @Test
    fun theDerivedSecretsAreSeparateAndTheTagMoves() {
        val code = SyncCode.generate()
        val k = PairKeys(code)
        assertNotEquals(k.tag(1), k.tag(2), "the broadcast tag changes with the time window")
        assertEquals(k.tag(7), PairKeys(SyncCode.parse(code.toString())).tag(7), "the same on every device with the code")
        assertNotEquals(k.proof("server", "n", "d"), k.proof("client", "n", "d"), "a proof is bound to its role")
        val other = PairKeys(SyncCode.generate())
        assertNotEquals(k.tag(1), other.tag(1))
        assertTrue(!k.key.encoded.contentEquals(other.key.encoded))
    }
}
