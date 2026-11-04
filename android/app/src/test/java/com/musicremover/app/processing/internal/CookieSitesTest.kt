package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Site grouping with OkHttp's bundled Public Suffix List (the same jar as the app). */
class CookieSitesTest {
    @Test
    fun registrableDomainExamples() {
        assertEquals("instagram.com", CookieSites.siteOf(".www.instagram.com"))
        assertEquals("instagram.com", CookieSites.siteOf("i.instagram.com"))
        assertEquals("youtube.com", CookieSites.siteOf(".youtube.com"))
        // Separate from YouTube, so it can be switched off on its own
        assertEquals("google.com", CookieSites.siteOf("accounts.google.com"))
        assertEquals("foo.co.uk", CookieSites.siteOf("foo.co.uk"))
        assertEquals("foo.co.uk", CookieSites.siteOf(".www.foo.co.uk"))
        assertEquals("instagram.com", CookieSites.siteOf("WWW.Instagram.COM"))
    }

    @Test
    fun ipLocalhostAndBareSuffixAreTheirOwnSite() {
        assertEquals("192.168.1.10", CookieSites.siteOf("192.168.1.10"))
        assertEquals("localhost", CookieSites.siteOf("localhost"))
        assertEquals("co.uk", CookieSites.siteOf(".co.uk"))
    }

    @Test
    fun httpOnlyPrefixIsIgnored() {
        assertEquals("instagram.com", CookieSites.siteOf("#HttpOnly_.www.instagram.com"))
    }

    @Test
    fun unavailableSuffixListFallsBackToTheHost() {
        val broken: (String) -> String? = { throw IllegalStateException("Unable to load publicsuffixes.gz") }
        assertEquals("www.instagram.com", CookieSites.siteOf(".www.instagram.com", broken))
        assertEquals("i.instagram.com", CookieSites.siteOf("i.instagram.com", broken))
    }

    @Test
    fun unsafeSiteNamesAreRejected() {
        assertNull(CookieSites.siteOf("../x"))
        assertNull(CookieSites.siteOf("a..b"))
        assertNull(CookieSites.siteOf("."))
        assertNull(CookieSites.siteOf(""))
        assertNull(CookieSites.siteOf("a/b.com", { null }))
        assertFalse(CookieSites.isValidSite("."))
        assertFalse(CookieSites.isValidSite(".."))
        assertFalse(CookieSites.isValidSite("../x"))
        assertFalse(CookieSites.isValidSite("a..b"))
        assertFalse(CookieSites.isValidSite("-a.com"))
        assertFalse(CookieSites.isValidSite("A.com"))
        assertTrue(CookieSites.isValidSite("instagram.com"))
        assertTrue(CookieSites.isValidSite("murem-test.invalid"))
    }

    @Test
    fun groupKeepsLinesVerbatimPerSite() {
        val ig1 = ".instagram.com\tTRUE\t/\tTRUE\t2000000000\tsessionid\tDUMMY_A"
        val ig2 = "#HttpOnly_i.instagram.com\tFALSE\t/api\tTRUE\t0\tds_user_id\tDUMMY_B"
        val yt = ".youtube.com\tTRUE\t/\tFALSE\t2000000000\tPREF\tDUMMY_C"
        val bad = "..\tTRUE\t/\tFALSE\t0\tx\tDUMMY_D"
        val text = "\uFEFF# Netscape HTTP Cookie File\r\n# comment\r\n$ig1\r\n\r\n$yt\n$ig2\n$bad\n"
        val groups = CookieSites.group(text)
        assertEquals(listOf("instagram.com", "youtube.com"), groups.keys.toList())
        assertEquals(listOf(ig1, ig2), groups["instagram.com"])
        assertEquals(listOf(yt), groups["youtube.com"])
    }

    @Test
    fun renderOfEnabledSitesOnlyLeavesOutTheDisabledSite() {
        val text = "# Netscape HTTP Cookie File\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t2000000000\tsessionid\tDUMMY_INSTA_1\n" +
            ".example.org\tTRUE\t/\tTRUE\t2000000000\texample_name\tDUMMY_EXAMPLE_1\n"
        val enabled = setOf("instagram.com")
        val rendered = CookieSites.group(text).filterKeys { it in enabled }.values.flatten()
        assertEquals(1, rendered.size)
        assertFalse(rendered.any { "example_name" in it || "DUMMY_EXAMPLE" in it })
        assertTrue(rendered.single().endsWith("\tsessionid\tDUMMY_INSTA_1"))
    }
}
