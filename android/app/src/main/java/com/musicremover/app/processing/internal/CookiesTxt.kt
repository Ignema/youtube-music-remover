package com.musicremover.app.processing.internal

/**
 * Validator for Netscape `cookies.txt` files (the format yt-dlp's `--cookies` reads). Only the cookie count
 * and domains leave this object; names and values never do. Pure.
 */
object CookiesTxt {
    const val MAX_BYTES = 1_048_576

    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private val HEADER = Regex("^#\\s*(Netscape )?HTTP Cookie File", RegexOption.IGNORE_CASE)
    private val BOOL = setOf("TRUE", "FALSE")
    private val EXPIRY = Regex("^-?\\d+(\\.\\d+)?$")
    private val DOMAIN = Regex("^\\.?[A-Za-z0-9.-]+$")

    /** What a valid file contains: [count] cookies for [domains] (sorted, without a leading dot). */
    data class Summary(val count: Int, val domains: List<String>)

    enum class Invalid { EMPTY, TOO_LARGE, BINARY, NOT_COOKIES_TXT, MALFORMED_LINE }

    sealed interface Result {
        data class Valid(val summary: Summary) : Result
        /** [line] is the 1-based line number for [Invalid.MALFORMED_LINE], else 0. */
        data class Rejected(val reason: Invalid, val line: Int = 0) : Result
    }

    /**
     * Valid when the file has a Netscape header or at least one cookie line, and every non-comment line
     * has 7 tab-separated fields (domain, TRUE/FALSE, path, TRUE/FALSE, expiry, name, value). [sizeBytes]
     * is the size read from disk, checked against [MAX_BYTES] before anything else.
     */
    fun validate(text: String, sizeBytes: Long = text.length.toLong()): Result {
        if (sizeBytes > MAX_BYTES) return Result.Rejected(Invalid.TOO_LARGE)
        if (text.isBlank()) return Result.Rejected(Invalid.EMPTY)
        if (text.contains('\u0000')) return Result.Rejected(Invalid.BINARY)
        var header = false
        val domains = sortedSetOf<String>()
        var count = 0
        for ((i, raw) in text.removePrefix("\uFEFF").lines().withIndex()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val cookieLine = when {
                line.startsWith(HTTP_ONLY_PREFIX) -> line.removePrefix(HTTP_ONLY_PREFIX)
                line.startsWith("#") -> {
                    if (HEADER.containsMatchIn(line)) header = true
                    continue
                }
                else -> line
            }
            val domain = cookieDomain(cookieLine) ?: return Result.Rejected(Invalid.MALFORMED_LINE, i + 1)
            domains += domain
            count++
        }
        if (count == 0) return Result.Rejected(if (header) Invalid.EMPTY else Invalid.NOT_COOKIES_TXT)
        return Result.Valid(Summary(count, domains.toList()))
    }

    /** The cookie values in a file that passed [validate], for scrubbing logs. Values shorter than 6 chars are skipped. */
    fun values(text: String): Set<String> = text.lines().mapNotNullTo(mutableSetOf()) { raw ->
        val line = raw.trimEnd('\r').removePrefix(HTTP_ONLY_PREFIX)
        if (line.startsWith("#")) return@mapNotNullTo null
        line.split('\t').takeIf { it.size == 7 }?.get(6)?.takeIf { it.length >= 6 }
    }

    /** One captured cookie; [domain] has a leading dot (it covers subdomains). */
    data class Entry(val domain: String, val name: String, val value: String, val expirySec: Long) {
        // Keeps the value out of logs and test failure messages
        override fun toString() = "Entry(domain=$domain, name=$name)"
    }

    const val CAPTURED_LIFETIME_SEC = 365L * 24 * 60 * 60

    /**
     * Parses a `Cookie:`-style header (`a=1; b=2`, as returned by `CookieManager.getCookie`) into entries
     * for [domain], expiring [CAPTURED_LIFETIME_SEC] after [nowSec]. Values may contain `=`. Pairs with an
     * empty name, or a name or value containing a tab, CR, LF or `;`, are dropped; the first of two pairs
     * with the same name wins. An invalid [domain] gives no entries.
     */
    fun fromCookieHeader(header: String?, domain: String, nowSec: Long): List<Entry> {
        if (header.isNullOrBlank()) return emptyList()
        val dotted = "." + domain.trim().removePrefix(".").lowercase()
        if (dotted == "." || !DOMAIN.matches(dotted) || dotted.contains("..")) return emptyList()
        val seen = mutableSetOf<String>()
        return header.split(';').mapNotNull { part ->
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty() || BAD_CHARS.any { it in name || it in value } || !seen.add(name)) return@mapNotNull null
            Entry(dotted, name, value, nowSec + CAPTURED_LIFETIME_SEC)
        }
    }

    /** The Netscape line for [e]: include-subdomains TRUE, path `/`, secure TRUE. */
    fun toLine(e: Entry): String = listOf(e.domain, "TRUE", "/", "TRUE", e.expirySec.toString(), e.name, e.value)
        .joinToString("\t")

    /**
     * [existing] (a stored cookies.txt, or null) with [captured] added. Lines with the same domain and
     * name as a captured entry are replaced; all other lines, `#HttpOnly_` ones included, are kept as
     * they are. The result starts with the Netscape header.
     */
    fun merge(existing: String?, captured: List<Entry>): String {
        val newKeys = captured.mapTo(mutableSetOf()) { key(it.domain, it.name) }
        val out = mutableListOf(NETSCAPE_HEADER)
        existing?.removePrefix("\uFEFF")?.lines()?.forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> Unit
                line.startsWith(HTTP_ONLY_PREFIX) -> if (!replaced(line.removePrefix(HTTP_ONLY_PREFIX), newKeys)) out += line
                line.startsWith("#") -> if (!HEADER.containsMatchIn(line)) out += line
                else -> if (!replaced(line, newKeys)) out += line
            }
        }
        val written = mutableSetOf<String>()
        for (e in captured) if (written.add(key(e.domain, e.name))) out += toLine(e)
        return out.joinToString("\n", postfix = "\n")
    }

    /**
     * The cookie lines of [text]: non-blank lines that are not comments, or start with `#HttpOnly_`. A
     * leading BOM and each line's trailing `\r` are removed; the header and other comments are dropped.
     */
    fun cookieLines(text: String): List<String> = text.removePrefix("\uFEFF").lines().mapNotNull { raw ->
        val line = raw.trimEnd('\r')
        line.takeIf { it.isNotBlank() && (!it.startsWith("#") || it.startsWith(HTTP_ONLY_PREFIX)) }
    }

    /**
     * Import merge: [existing] (a stored site file, or null) with the cookie lines of [imported]. A line's
     * key is its domain (without `#HttpOnly_` and a leading dot, lowercased), path and name. The result is
     * the Netscape header, the existing lines whose key is not imported (in order), then the imported
     * lines verbatim; of imported lines with the same key the last one wins.
     */
    fun mergeLines(existing: String?, imported: String): String {
        val last = LinkedHashMap<String, String>()
        for (line in cookieLines(imported)) {
            val k = lineKey(line)
            last.remove(k)
            last[k] = line
        }
        val out = mutableListOf(NETSCAPE_HEADER)
        existing?.let { e -> cookieLines(e).filterTo(out) { lineKey(it) !in last } }
        out += last.values
        return out.joinToString("\n", postfix = "\n")
    }

    private fun lineKey(line: String): String {
        val f = line.removePrefix(HTTP_ONLY_PREFIX).split('\t')
        return f[0].removePrefix(".").lowercase() + "\t" + f.getOrElse(2) { "" } + "\t" + f.getOrElse(5) { "" }
    }

    private fun replaced(line: String, keys: Set<String>): Boolean {
        val f = line.split('\t')
        return f.size == 7 && key(f[0], f[5]) in keys
    }

    private fun key(domain: String, name: String) = domain.removePrefix(".").lowercase() + "\t" + name

    private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
    private val BAD_CHARS = charArrayOf('\t', '\r', '\n', ';')

    private fun cookieDomain(line: String): String? {
        val f = line.split('\t')
        if (f.size != 7) return null
        if (!DOMAIN.matches(f[0]) || f[1] !in BOOL || !f[2].startsWith("/") || f[3] !in BOOL) return null
        if (!EXPIRY.matches(f[4]) || f[5].isEmpty()) return null
        return f[0].removePrefix(".").lowercase()
    }
}
