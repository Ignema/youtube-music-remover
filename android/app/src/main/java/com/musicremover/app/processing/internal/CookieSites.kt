package com.musicremover.app.processing.internal

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Groups cookies by site: the registrable domain of a cookie's domain field (OkHttp's bundled Public
 * Suffix List), e.g. `.www.instagram.com` and `i.instagram.com` both belong to `instagram.com`. A site
 * name is also a file name in the cookie store, so it must pass [isValidSite]. Only counts and site
 * names are logged. Pure apart from logging.
 */
object CookieSites {
    private const val TAG = "MuremCookies"
    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private val SITE = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$")

    /**
     * The site for a cookie [domainField] (with or without `#HttpOnly_` and a leading dot), or null if
     * the result is not a safe site name. IPs, `localhost` and bare public suffixes are their own site.
     * If the suffix list cannot be loaded ([lookup] throws [IllegalStateException]), the host is the site.
     */
    fun siteOf(domainField: String, lookup: (String) -> String? = ::pslLookup): String? {
        val host = domainField.removePrefix(HTTP_ONLY_PREFIX).trim().lowercase().removePrefix(".")
        if (host.isEmpty()) return null
        val top = try {
            lookup(host)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "PSL unavailable")
            null
        }
        return (top ?: host).takeIf(::isValidSite)
    }

    /** True if [site] can be used as a file name: `^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$`, no `..`. */
    fun isValidSite(site: String): Boolean =
        site != "." && site != ".." && !site.contains("..") && SITE.matches(site)

    /**
     * The cookie lines of [text] (see [CookiesTxt.cookieLines]) grouped by [siteOf] their domain field,
     * sorted by site. Lines are kept verbatim; lines without a valid site are dropped (counted in a log).
     */
    fun group(text: String, lookup: (String) -> String? = ::pslLookup): Map<String, List<String>> {
        val out = sortedMapOf<String, MutableList<String>>()
        var dropped = 0
        for (line in CookiesTxt.cookieLines(text)) {
            val site = siteOf(line.substringBefore('\t'), lookup)
            if (site == null) dropped++ else out.getOrPut(site) { mutableListOf() } += line
        }
        if (dropped > 0) Log.i(TAG, "$dropped cookie lines without a valid site dropped")
        return out
    }

    private fun pslLookup(host: String): String? = "https://$host/".toHttpUrlOrNull()?.topPrivateDomain()
}
