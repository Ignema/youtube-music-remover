package com.musicremover.app.processing.internal

import java.net.URI

/** Sites for "Log in with browser": where the login starts and which cookies are saved. Pure. */
object LoginSites {
    /**
     * Login starts at [startUrl]; the cookies the WebView holds for [cookieUrls] are saved for
     * [cookieDomain] (leading dot, so they cover every subdomain).
     */
    data class Site(val startUrl: String, val cookieUrls: List<String>, val cookieDomain: String)

    /**
     * yt-dlp's Instagram extractor checks `sessionid` on www.instagram.com and reads `csrftoken` there;
     * the API host i.instagram.com shares the `.instagram.com` cookies.
     */
    val INSTAGRAM = Site(
        startUrl = "https://www.instagram.com/accounts/login/",
        cookieUrls = listOf("https://www.instagram.com", "https://i.instagram.com"),
        cookieDomain = ".instagram.com",
    )

    /** A site from a user-typed https URL, or null if it is not https or has no host. */
    fun custom(url: String): Site? {
        val uri = parse(url.trim()) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() && HOST.matches(it) } ?: return null
        return Site(uri.toString(), listOf(origin(uri, host)), "." + host.removePrefix("www."))
    }

    /**
     * The URLs to read cookies from: [site]'s, plus the origin of [currentUrl] (the page shown when Save
     * is tapped) if it is https and on the site's domain. Cookies on other hosts are not saved.
     */
    fun cookieUrls(site: Site, currentUrl: String?): List<String> {
        val uri = currentUrl?.let(::parse)
        val host = uri?.host?.lowercase()
        val base = site.cookieDomain.removePrefix(".")
        val extra = if (uri != null && host != null && uri.scheme.equals("https", ignoreCase = true) &&
            (host == base || host.endsWith(".$base"))
        ) origin(uri, host) else null
        return (site.cookieUrls + listOfNotNull(extra)).distinct()
    }

    /** The login WebView only follows https links (no http, intent, file, content, javascript or data). */
    fun isAllowedNavigation(url: String?): Boolean {
        val uri = url?.let(::parse) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty()
    }

    /** Google and Facebook block their sign-in pages in embedded browsers. */
    fun isEmbeddedBlockedLogin(url: String?): Boolean {
        val uri = url?.let(::parse) ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        if (host == "accounts.google.com") return true
        val facebook = host == "facebook.com" || host.endsWith(".facebook.com")
        return facebook && (path.contains("/dialog/oauth") || path.contains("/login"))
    }

    private fun origin(uri: URI, host: String) = "https://$host" + if (uri.port != -1) ":${uri.port}" else ""

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: Exception) {
        null
    }

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")
}
