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
    fun cookieHeaderBecomesEntriesForTheDomain() {
        val now = 1_700_000_000L
        val e = CookiesTxt.fromCookieHeader(
            "sessionid=S_1; csrftoken=C=2==;ds_user_id=42 ; mid=; sessionid=DUP",
            "instagram.com", now,
        )
        val exp = now + 365L * 24 * 60 * 60
        assertEquals(
            listOf(
                CookiesTxt.Entry(".instagram.com", "sessionid", "S_1", exp),
                CookiesTxt.Entry(".instagram.com", "csrftoken", "C=2==", exp),
                CookiesTxt.Entry(".instagram.com", "ds_user_id", "42", exp),
                CookiesTxt.Entry(".instagram.com", "mid", "", exp),
            ),
            e,
        )
        // Leading dot and case are normalized
        assertEquals(".a.com", CookiesTxt.fromCookieHeader("n=v", ".A.com", now).single().domain)
    }

    @Test
    fun garbageHeadersAndUnsafePairsAreDropped() {
        assertEquals(emptyList<CookiesTxt.Entry>(), CookiesTxt.fromCookieHeader(null, ".a.com", 0))
        assertEquals(emptyList<CookiesTxt.Entry>(), CookiesTxt.fromCookieHeader("  ", ".a.com", 0))
        assertEquals(emptyList<CookiesTxt.Entry>(), CookiesTxt.fromCookieHeader("novalue; =v; ;;", ".a.com", 0))
        assertEquals(
            listOf("ok"),
            CookiesTxt.fromCookieHeader("tab=a\tb; nl=a\nb; cr=a\rb; ok=1", ".a.com", 0).map { it.name },
        )
        assertEquals(emptyList<CookiesTxt.Entry>(), CookiesTxt.fromCookieHeader("n=v", "bad domain", 0))
        assertEquals(emptyList<CookiesTxt.Entry>(), CookiesTxt.fromCookieHeader("n=v", "", 0))
        assertFalse(CookiesTxt.Entry(".a.com", "n", "SECRET_VALUE", 0).toString().contains("SECRET"))
    }

    @Test
    fun lineHasSevenNetscapeFields() {
        assertEquals(
            ".instagram.com\tTRUE\t/\tTRUE\t1731536000\tsessionid\tS_1",
            CookiesTxt.toLine(CookiesTxt.Entry(".instagram.com", "sessionid", "S_1", 1_731_536_000L)),
        )
    }

    @Test
    fun mergeReplacesSameDomainAndNameAndKeepsTheRest() {
        val captured = CookiesTxt.fromCookieHeader("sessionid=NEW_SESSION; ds_user_id=NEW_UID", "instagram.com", 1_700_000_000L)
        val merged = CookiesTxt.merge(valid, captured)
        val lines = merged.lines()
        assertEquals("# Netscape HTTP Cookie File", lines.first())
        assertEquals(1, lines.count { it.contains("\tsessionid\t") })
        assertTrue(lines.any { it.endsWith("\tsessionid\tNEW_SESSION") })
        assertFalse(merged.contains("SECRET_SESSION_1"))
        // Other names on the domain, #HttpOnly_ lines and other domains are kept verbatim
        assertTrue("#HttpOnly_.instagram.com\tTRUE\t/\tTRUE\t1793423521\tcsrftoken\tSECRET_CSRF_2" in lines)
        assertTrue("www.youtube.com\tFALSE\t/\tFALSE\t0\tPREF\tf6=40000000" in lines)
        assertEquals(1, lines.count { it.startsWith("# Netscape HTTP Cookie File") })
        assertEquals(
            Result.Valid(CookiesTxt.Summary(4, listOf("instagram.com", "www.youtube.com"))),
            CookiesTxt.validate(merged),
        )
    }

    @Test
    fun mergeReplacesHttpOnlyLineWithSameKey() {
        val captured = CookiesTxt.fromCookieHeader("csrftoken=NEW_CSRF", ".instagram.com", 0)
        val merged = CookiesTxt.merge(valid, captured)
        assertFalse(merged.contains("SECRET_CSRF_2"))
        assertEquals(1, merged.lines().count { it.contains("\tcsrftoken\t") })
    }

    @Test
    fun instagramAndCustomSitesCoexist() {
        val ig = CookiesTxt.merge(null, CookiesTxt.fromCookieHeader("sessionid=IG_SESSION", ".instagram.com", 0))
        val both = CookiesTxt.merge(ig, CookiesTxt.fromCookieHeader("sessionid=EX_SESSION", ".example.com", 0))
        assertEquals(Result.Valid(CookiesTxt.Summary(2, listOf("example.com", "instagram.com"))), CookiesTxt.validate(both))
        // Saving Instagram again replaces only its line
        val again = CookiesTxt.merge(both, CookiesTxt.fromCookieHeader("sessionid=IG_SESSION_2", ".instagram.com", 0))
        assertTrue(again.contains("EX_SESSION"))
        assertTrue(again.contains("IG_SESSION_2"))
        assertFalse(again.contains("IG_SESSION\n"))
        assertEquals(Result.Valid(CookiesTxt.Summary(2, listOf("example.com", "instagram.com"))), CookiesTxt.validate(again))
    }

    @Test
    fun mergeOfNothingIntoNothingIsEmpty() {
        assertEquals(Result.Rejected(Invalid.EMPTY), CookiesTxt.validate(CookiesTxt.merge(null, emptyList())))
    }

    @Test
    fun oversizedMergeIsRejected() {
        val big = (0 until 20_000).joinToString("; ") { "n$it=${"v".repeat(60)}" }
        val merged = CookiesTxt.merge(null, CookiesTxt.fromCookieHeader(big, ".a.com", 0))
        val size = merged.toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(size > CookiesTxt.MAX_BYTES)
        assertEquals(Result.Rejected(Invalid.TOO_LARGE), CookiesTxt.validate(merged, size))
    }

    @Test
    fun valuesForScrubbingSkipShortOnes() {
        assertEquals(setOf("SECRET_SESSION_1", "SECRET_CSRF_2", "f6=40000000"), CookiesTxt.values(valid))
        assertEquals(emptySet<String>(), CookiesTxt.values(".a.com\tTRUE\t/\tFALSE\t0\tn\tabc"))
    }
}
