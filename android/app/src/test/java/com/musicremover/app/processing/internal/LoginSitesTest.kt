package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSitesTest {
    @Test
    fun instagramPresetCoversWwwAndApiHosts() {
        val s = LoginSites.INSTAGRAM
        assertEquals("https://www.instagram.com/accounts/login/", s.startUrl)
        assertEquals(listOf("https://www.instagram.com", "https://i.instagram.com"), s.cookieUrls)
        assertEquals(".instagram.com", s.cookieDomain)
    }

    @Test
    fun instagramNeedsSessionidButCustomSitesDoNot() {
        val ig = LoginSites.INSTAGRAM
        assertFalse(LoginSites.isLoggedIn(ig, listOf("csrftoken", "mid", "ig_did")))
        assertTrue(LoginSites.isLoggedIn(ig, listOf("csrftoken", "sessionid")))
        assertTrue(LoginSites.isLoggedIn(LoginSites.custom("https://example.com/")!!, listOf("anything")))
    }

    @Test
    fun customHttpsUrlIsAccepted() {
        val s = LoginSites.custom("  https://www.Example.com/login?next=/x  ")!!
        assertEquals("https://www.Example.com/login?next=/x", s.startUrl)
        assertEquals(listOf("https://www.example.com"), s.cookieUrls)
        assertEquals(".example.com", s.cookieDomain)
        assertEquals(listOf("https://sub.site.org:8443"), LoginSites.custom("https://sub.site.org:8443/")!!.cookieUrls)
        assertEquals(".sub.site.org", LoginSites.custom("https://sub.site.org:8443/")!!.cookieDomain)
    }

    @Test
    fun customRejectsNonHttpsAndGarbage() {
        for (bad in listOf(
            "", "   ", "http://example.com/", "example.com", "ftp://example.com", "https://", "https:///path",
            "javascript:alert(1)", "file:///sdcard/x", "https://exa mple.com/", "https://bad_host.com/", "not a url",
        )) {
            assertNull(bad, LoginSites.custom(bad))
        }
    }

    @Test
    fun cookieUrlsAddTheCurrentPageOnlyOnTheSameDomain() {
        val site = LoginSites.custom("https://example.com/login")!!
        assertEquals(
            listOf("https://example.com", "https://accounts.example.com"),
            LoginSites.cookieUrls(site, "https://accounts.example.com/done"),
        )
        assertEquals(listOf("https://example.com"), LoginSites.cookieUrls(site, "https://example.com/feed"))
        assertEquals(listOf("https://example.com"), LoginSites.cookieUrls(site, "https://evil-example.com/"))
        assertEquals(listOf("https://example.com"), LoginSites.cookieUrls(site, "http://www.example.com/"))
        assertEquals(listOf("https://example.com"), LoginSites.cookieUrls(site, null))
        assertEquals(
            LoginSites.INSTAGRAM.cookieUrls,
            LoginSites.cookieUrls(LoginSites.INSTAGRAM, "https://www.instagram.com/accounts/onetap/"),
        )
    }

    @Test
    fun navigationIsHttpsOnly() {
        assertTrue(LoginSites.isAllowedNavigation("https://www.instagram.com/"))
        assertTrue(LoginSites.isAllowedNavigation("HTTPS://www.instagram.com/"))
        for (bad in listOf(
            "http://www.instagram.com/", "intent://x#Intent;scheme=y;end", "file:///data/x", "content://x/y",
            "javascript:alert(1)", "data:text/html,hi", "about:blank", "", null,
        )) {
            assertFalse("$bad", LoginSites.isAllowedNavigation(bad))
        }
    }

    @Test
    fun googleAndFacebookLoginPagesGetTheHint() {
        assertTrue(LoginSites.isEmbeddedBlockedLogin("https://accounts.google.com/o/oauth2/v2/auth?client_id=x"))
        assertTrue(LoginSites.isEmbeddedBlockedLogin("https://www.facebook.com/dialog/oauth?client_id=x"))
        assertTrue(LoginSites.isEmbeddedBlockedLogin("https://m.facebook.com/login.php?next=x"))
        assertTrue(LoginSites.isEmbeddedBlockedLogin("https://facebook.com/v19.0/dialog/oauth"))
        assertFalse(LoginSites.isEmbeddedBlockedLogin("https://www.instagram.com/accounts/login/"))
        assertFalse(LoginSites.isEmbeddedBlockedLogin("https://www.facebook.com/somepage"))
        assertFalse(LoginSites.isEmbeddedBlockedLogin("https://notfacebook.com/login"))
        assertFalse(LoginSites.isEmbeddedBlockedLogin(null))
    }
}
