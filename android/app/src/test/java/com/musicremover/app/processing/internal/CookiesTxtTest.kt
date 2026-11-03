package com.musicremover.app.processing.internal

import com.musicremover.app.processing.internal.CookiesTxt.Invalid
import com.musicremover.app.processing.internal.CookiesTxt.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookiesTxtTest {
    private val valid = listOf(
        "# Netscape HTTP Cookie File",
        "# https://curl.haxx.se/rfc/cookie_spec.html",
        "",
        ".instagram.com\tTRUE\t/\tTRUE\t1793423521\tsessionid\tSECRET_SESSION_1",
        "#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t1793423521\tcsrftoken\tSECRET_CSRF_2",
        "www.youtube.com\tFALSE\t/\tFALSE\t0\tPREF\tf6=40000000",
    ).joinToString("\n")

    @Test
    fun validFileGivesCountAndDomainsOnly() {
        val r = CookiesTxt.validate(valid)
        assertEquals(Result.Valid(CookiesTxt.Summary(3, listOf("instagram.com", "www.youtube.com"))), r)
        assertFalse(r.toString().contains("SECRET"))
    }

    @Test
    fun crlfAndBomAndNoHeaderAreAccepted() {
        val text = "\uFEFF.example.com\tTRUE\t/\tFALSE\t1700000000\tname\tvalue\r\n"
        assertEquals(Result.Valid(CookiesTxt.Summary(1, listOf("example.com"))), CookiesTxt.validate(text))
    }

    @Test
    fun emptyValueStillHasSevenFields() {
        assertTrue(CookiesTxt.validate(".a.com\tTRUE\t/\tFALSE\t0\tname\t") is Result.Valid)
    }

    @Test
    fun rejections() {
        assertEquals(Result.Rejected(Invalid.EMPTY), CookiesTxt.validate("   \n"))
        assertEquals(Result.Rejected(Invalid.EMPTY), CookiesTxt.validate("# Netscape HTTP Cookie File\n"))
        assertEquals(Result.Rejected(Invalid.NOT_COOKIES_TXT), CookiesTxt.validate("# just a comment\n"))
        assertEquals(Result.Rejected(Invalid.BINARY), CookiesTxt.validate("PK\u0003\u0004\u0000\u0000"))
        assertEquals(Result.Rejected(Invalid.TOO_LARGE), CookiesTxt.validate(valid, CookiesTxt.MAX_BYTES + 1L))
        // JSON export (EditThisCookie) instead of Netscape
        assertEquals(Result.Rejected(Invalid.MALFORMED_LINE, 1), CookiesTxt.validate("[{\"domain\": \".instagram.com\"}]"))
        // Spaces instead of tabs, on line 2
        assertEquals(
            Result.Rejected(Invalid.MALFORMED_LINE, 2),
            CookiesTxt.validate("# Netscape HTTP Cookie File\n.a.com TRUE / FALSE 0 n v"),
        )
        // Bad flag, path and expiry fields
        assertEquals(Result.Rejected(Invalid.MALFORMED_LINE, 1), CookiesTxt.validate(".a.com\tyes\t/\tFALSE\t0\tn\tv"))
        assertEquals(Result.Rejected(Invalid.MALFORMED_LINE, 1), CookiesTxt.validate(".a.com\tTRUE\tx\tFALSE\t0\tn\tv"))
        assertEquals(Result.Rejected(Invalid.MALFORMED_LINE, 1), CookiesTxt.validate(".a.com\tTRUE\t/\tFALSE\tsoon\tn\tv"))
        assertEquals(Result.Rejected(Invalid.MALFORMED_LINE, 1), CookiesTxt.validate("a b.com\tTRUE\t/\tFALSE\t0\tn\tv"))
    }

    @Test
    fun maxSizeIsOneMegabyte() {
        assertEquals(1_048_576, CookiesTxt.MAX_BYTES)
        assertTrue(CookiesTxt.validate(valid, CookiesTxt.MAX_BYTES.toLong()) is Result.Valid)
    }

    @Test
    fun valuesForScrubbingSkipShortOnes() {
        assertEquals(setOf("SECRET_SESSION_1", "SECRET_CSRF_2", "f6=40000000"), CookiesTxt.values(valid))
        assertEquals(emptySet<String>(), CookiesTxt.values(".a.com\tTRUE\t/\tFALSE\t0\tn\tabc"))
    }
}
