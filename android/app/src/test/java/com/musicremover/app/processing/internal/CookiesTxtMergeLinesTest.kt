package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Line-level import merge (design §3b.3). */
class CookiesTxtMergeLinesTest {
    private val header = "# Netscape HTTP Cookie File"

    private fun line(domain: String, path: String, name: String, value: String, secure: String = "TRUE") =
        "$domain\tTRUE\t$path\t$secure\t2000000000\t$name\t$value"

    private fun render(vararg lines: String) = (listOf(header) + lines).joinToString("\n", postfix = "\n")

    @Test
    fun nullExistingGivesHeaderAndImportedLines() {
        val a = line(".example.com", "/", "a", "DUMMY_1")
        assertEquals(render(a), CookiesTxt.mergeLines(null, "$header\n$a\n"))
    }

    @Test
    fun sameDomainPathAndNameIsReplaced() {
        val old = line(".example.com", "/", "a", "DUMMY_OLD")
        val keep = line(".example.com", "/", "b", "DUMMY_KEEP")
        val new = line(".example.com", "/", "a", "DUMMY_NEW")
        assertEquals(render(keep, new), CookiesTxt.mergeLines(render(old, keep), new))
    }

    @Test
    fun sameNameWithAnotherPathIsKept() {
        val root = line(".example.com", "/", "a", "DUMMY_ROOT")
        val api = line(".example.com", "/api", "a", "DUMMY_API")
        assertEquals(render(root, api), CookiesTxt.mergeLines(render(root), api))
    }

    @Test
    fun leadingDotAndCaseDoNotChangeTheKey() {
        val dotted = line(".example.com", "/", "a", "DUMMY_DOTTED")
        val plain = line("Example.com", "/", "a", "DUMMY_PLAIN")
        assertEquals(render(plain), CookiesTxt.mergeLines(render(dotted), plain))
    }

    @Test
    fun httpOnlyPrefixAndSecureFalseAreKeptVerbatim() {
        val existing = line(".example.com", "/", "a", "DUMMY_OLD")
        val imported = "#HttpOnly_" + line(".example.com", "/", "a", "DUMMY_NEW", secure = "FALSE")
        assertEquals(render(imported), CookiesTxt.mergeLines(render(existing), imported))
        // And the other way round: an existing #HttpOnly_ line is replaced by a plain one with the same key
        val plain = line("example.com", "/", "a", "DUMMY_PLAIN")
        assertEquals(render(plain), CookiesTxt.mergeLines(render(imported), plain))
    }

    @Test
    fun headerIsWrittenOnceAndCommentsAreDropped() {
        val a = line(".example.com", "/", "a", "DUMMY_1")
        val b = line(".example.com", "/", "b", "DUMMY_2")
        val out = CookiesTxt.mergeLines("$header\n# a comment\n$a\n", "# HTTP Cookie File\n# other\n$b\n")
        assertEquals(render(a, b), out)
        assertEquals(1, out.lines().count { it.startsWith("#") })
    }

    @Test
    fun lastDuplicateInTheImportWins() {
        val first = line(".example.com", "/", "a", "DUMMY_FIRST")
        val other = line(".example.com", "/", "b", "DUMMY_OTHER")
        val last = line("example.com", "/", "a", "DUMMY_LAST")
        assertEquals(render(other, last), CookiesTxt.mergeLines(null, "$first\n$other\n$last\n"))
    }

    @Test
    fun bomAndCrlfAreStripped() {
        val a = line(".example.com", "/", "a", "DUMMY_A")
        val b = line(".example.com", "/", "b", "DUMMY_B")
        val out = CookiesTxt.mergeLines("\uFEFF$header\r\n$a\r\n", "\uFEFF$header\r\n$b\r\n\r\n")
        assertEquals(render(a, b), out)
    }
}
