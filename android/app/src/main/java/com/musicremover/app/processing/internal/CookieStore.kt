package com.musicremover.app.processing.internal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Cookies for on-device links, one Netscape file per site in `filesDir/cookies/sites/<site>.txt` plus the
 * index `sites.json` (switch, save time, source, login URL), all excluded from backup and device transfer
 * by `backup_rules.xml` and `data_extraction_rules.xml`. A site is sent only if the index says it is
 * enabled; a site file without an index entry, or an unreadable index, counts as disabled.
 *
 * A legacy single `cookies.txt` is migrated on the first method call. Jobs never pass the stored files to
 * yt-dlp, which rewrites its cookie file on exit: they use a copy from [copyForJob] with the enabled sites
 * only. All instances share one lock. Cookie names, values and file contents are never logged.
 */
class CookieStore internal constructor(private val root: File, private val resolver: ContentResolver?) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIR),
        context.applicationContext.contentResolver,
    )

    private val sitesDir = File(root, SITES_DIR)
    private val indexFile = File(root, INDEX)
    private val legacyFile = File(root, FILE)

    /** One site as shown in Settings; [count] is 0 if the site file is missing or invalid. */
    data class SiteInfo(
        val site: String,
        val enabled: Boolean,
        val count: Int,
        val savedAtMs: Long,
        val source: String,
        val loginUrl: String?,
    )

    sealed interface ImportResult {
        /** [summary] describes the imported file only. */
        data class Imported(val summary: CookiesTxt.Summary) : ImportResult
        data class Rejected(val reason: CookiesTxt.Invalid, val line: Int) : ImportResult
        data object Unreadable : ImportResult
    }

    sealed interface SaveResult {
        /** [captured] cookies were saved; [summary] describes all enabled sites (see [summary]). */
        data class Saved(val captured: CookiesTxt.Summary, val summary: CookiesTxt.Summary) : SaveResult
        data class Rejected(val reason: CookiesTxt.Invalid) : SaveResult
        data object NoCookies : SaveResult
        /** Cookies were captured but the site's session cookie is missing; nothing was saved. */
        data object NotLoggedIn : SaveResult
        data object Unwritable : SaveResult
    }

    /** All stored sites, sorted by site. */
    fun sites(): List<SiteInfo> = synchronized(LOCK) {
        migrate()
        loadIndex().values.map { e ->
            SiteInfo(e.site, e.enabled, readSite(e.site)?.second ?: 0, e.savedAt, e.source, e.loginUrl)
        }
    }

    /** Cookie count over the enabled sites with a valid file, and those sites (sorted); null if none. */
    fun summary(): CookiesTxt.Summary? = synchronized(LOCK) {
        migrate()
        var count = 0
        val sites = mutableListOf<String>()
        for (e in loadIndex().values) {
            if (!e.enabled) continue
            val n = readSite(e.site)?.second ?: continue
            count += n
            sites += e.site
        }
        if (sites.isEmpty()) null else CookiesTxt.Summary(count, sites)
    }

    fun hasCookies(): Boolean = summary() != null

    /** Reads at most [CookiesTxt.MAX_BYTES] + 1 bytes from [uri] and merges them in with [importBytes]. */
    fun import(uri: Uri): ImportResult {
        val bytes = try {
            resolver?.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buf.size() <= CookiesTxt.MAX_BYTES) {
                    val n = input.read(chunk, 0, minOf(chunk.size, CookiesTxt.MAX_BYTES + 1 - buf.size()))
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the selected cookies file: ${e.javaClass.simpleName}")
            null
        } ?: return ImportResult.Unreadable
        return importBytes(bytes)
    }

    /**
     * Validates an imported cookies.txt and merges it per site ([CookiesTxt.mergeLines]). New sites are
     * enabled, existing sites keep their switch; touched sites get the current time. Rejected as
     * [CookiesTxt.Invalid.TOO_LARGE] if all site files together would exceed [CookiesTxt.MAX_BYTES].
     */
    internal fun importBytes(bytes: ByteArray): ImportResult = synchronized(LOCK) {
        migrate()
        val text = bytes.toString(Charsets.UTF_8)
        val r = CookiesTxt.validate(text, bytes.size.toLong())
        if (r is CookiesTxt.Result.Rejected) {
            Log.i(TAG, "cookies.txt rejected: ${r.reason} line ${r.line}")
            return@synchronized ImportResult.Rejected(r.reason, r.line)
        }
        val summary = (r as CookiesTxt.Result.Valid).summary
        val groups = CookieSites.group(text)
        if (groups.isEmpty()) {
            Log.i(TAG, "cookies.txt rejected: no valid site")
            return@synchronized ImportResult.Rejected(CookiesTxt.Invalid.NOT_COOKIES_TXT, 0)
        }
        val index = loadIndex()
        val merged = groups.mapValues { (site, lines) ->
            CookiesTxt.mergeLines(readSite(site)?.first, lines.joinToString("\n")).toByteArray(Charsets.UTF_8)
        }
        if (storeSizeWith(merged) > CookiesTxt.MAX_BYTES) {
            Log.i(TAG, "cookies.txt rejected: store over the size limit")
            return@synchronized ImportResult.Rejected(CookiesTxt.Invalid.TOO_LARGE, 0)
        }
        val now = System.currentTimeMillis()
        val updated = index.toMutableMap()
        for (site in merged.keys) {
            updated[site] = index[site]?.copy(savedAt = now) ?: IndexEntry(site, true, now, SOURCE_IMPORT, null)
        }
        try {
            writeSites(merged, updated)
            Log.i(TAG, "cookies.txt imported: ${summary.count} cookies, ${merged.size} sites")
            ImportResult.Imported(summary)
        } catch (e: IOException) {
            Log.w(TAG, "cannot store cookies: ${e.javaClass.simpleName}")
            ImportResult.Unreadable
        }
    }

    /**
     * Merges cookies captured by "Log in with browser" for [site] into that site's file (see
     * [CookiesTxt.merge]) and switches the site on. A stored site file that no longer validates is replaced.
     */
    fun saveCaptured(site: LoginSites.Site, entries: List<CookiesTxt.Entry>): SaveResult = synchronized(LOCK) {
        migrate()
        if (entries.isEmpty()) {
            Log.i(TAG, "no cookies captured")
            return@synchronized SaveResult.NoCookies
        }
        val name = CookieSites.siteOf(site.cookieDomain) ?: run {
            Log.w(TAG, "login save rejected: ${site.cookieDomain}")
            return@synchronized SaveResult.Rejected(CookiesTxt.Invalid.NOT_COOKIES_TXT)
        }
        val merged = CookiesTxt.merge(readSite(name)?.first, entries)
        val bytes = merged.toByteArray(Charsets.UTF_8)
        val r = CookiesTxt.validate(merged, bytes.size.toLong())
        if (r is CookiesTxt.Result.Rejected) {
            Log.i(TAG, "captured cookies rejected: ${r.reason}")
            return@synchronized SaveResult.Rejected(r.reason)
        }
        if (storeSizeWith(mapOf(name to bytes)) > CookiesTxt.MAX_BYTES) {
            Log.i(TAG, "captured cookies rejected: store over the size limit")
            return@synchronized SaveResult.Rejected(CookiesTxt.Invalid.TOO_LARGE)
        }
        val index = loadIndex().toMutableMap()
        val loginUrl = if (site == LoginSites.INSTAGRAM) null else site.startUrl
        index[name] = IndexEntry(name, true, System.currentTimeMillis(), SOURCE_LOGIN, loginUrl)
        try {
            writeSites(mapOf(name to bytes), index)
            val captured = CookiesTxt.Summary(
                entries.distinctBy { it.domain to it.name }.size,
                entries.map { it.domain.removePrefix(".") }.distinct().sorted(),
            )
            val total = (r as CookiesTxt.Result.Valid).summary.count
            Log.i(TAG, "captured cookies saved: ${captured.count} cookies for $name (site file: $total cookies)")
            // Never null here: the site was just written and switched on
            SaveResult.Saved(captured, summary() ?: captured)
        } catch (e: IOException) {
            Log.w(TAG, "cannot store cookies: ${e.javaClass.simpleName}")
            SaveResult.Unwritable
        }
    }

    /** Switches [site] on or off; false if it is unknown or the index cannot be written. */
    fun setEnabled(site: String, on: Boolean): Boolean = synchronized(LOCK) {
        migrate()
        val index = loadIndex().toMutableMap()
        val entry = index[site] ?: return@synchronized false
        index[site] = entry.copy(enabled = on)
        try {
            writeIndex(index)
            Log.i(TAG, "site $site enabled=$on")
            true
        } catch (e: IOException) {
            Log.w(TAG, "cannot update the cookie index: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Removes [site]'s index entry, then its file. */
    fun deleteSite(site: String): Boolean = synchronized(LOCK) {
        migrate()
        if (!CookieSites.isValidSite(site)) return@synchronized false
        val index = loadIndex().toMutableMap()
        if (index.remove(site) == null) return@synchronized false
        try {
            writeIndex(index)
        } catch (e: IOException) {
            Log.w(TAG, "cannot update the cookie index: ${e.javaClass.simpleName}")
            return@synchronized false
        }
        val f = siteFile(site)
        val ok = !f.exists() || f.delete()
        if (!ok) Log.w(TAG, "cannot delete the cookie file of $site")
        Log.i(TAG, "site $site deleted")
        ok
    }

    /** Deletes the index, every site file and a leftover legacy cookies.txt. */
    fun deleteAll(): Boolean = synchronized(LOCK) {
        val index = !indexFile.exists() || indexFile.delete()
        val sites = !sitesDir.exists() || sitesDir.deleteRecursively()
        val legacy = !legacyFile.exists() || legacyFile.delete()
        val ok = index && sites && legacy
        Log.i(TAG, "all cookies deleted: $ok")
        ok
    }

    /**
     * Writes the enabled sites with a valid file into `dir/cookies.txt` for one yt-dlp run, or returns null
     * if there are none. A missing or invalid site file is skipped. The caller deletes the copy (see
     * [withJobCookies]). Throws [IOException] if the copy cannot be written.
     */
    fun copyForJob(dir: File): JobCookies? = synchronized(LOCK) {
        migrate()
        val lines = mutableListOf<String>()
        for (e in loadIndex().values) {
            if (!e.enabled) continue
            val text = readSite(e.site)?.first ?: run {
                Log.w(TAG, "site file invalid, skipped: ${e.site}")
                null
            } ?: continue
            lines += CookiesTxt.cookieLines(text)
        }
        if (lines.isEmpty()) return@synchronized null
        val text = (listOf(NETSCAPE_HEADER) + lines).joinToString("\n", postfix = "\n")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the cookie dir")
        val copy = File(dir, FILE)
        copy.writeText(text)
        ownerOnly(copy)
        JobCookies(copy, CookiesTxt.values(text))
    }

    // --- Storage (callers hold LOCK) ---

    private data class IndexEntry(
        val site: String,
        val enabled: Boolean,
        val savedAt: Long,
        val source: String,
        val loginUrl: String?,
    )

    private fun siteFile(site: String): File {
        require(CookieSites.isValidSite(site))
        return File(sitesDir, "$site.txt")
    }

    /** The validated text and cookie count of [site]'s file, or null if it is missing or invalid. */
    private fun readSite(site: String): Pair<String, Int>? {
        if (!CookieSites.isValidSite(site)) return null
        val f = siteFile(site)
        if (!f.isFile) return null
        val text = try {
            f.readText()
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the cookie file of $site: ${e.javaClass.simpleName}")
            return null
        }
        val r = CookiesTxt.validate(text, f.length()) as? CookiesTxt.Result.Valid ?: return null
        return text to r.summary.count
    }

    /** Site files on disk (valid names only). */
    private fun siteFilesOnDisk(): Map<String, File> =
        sitesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.associateBy { it.name.removeSuffix(".txt") }
            ?.filterKeys(CookieSites::isValidSite)
            .orEmpty()

    /**
     * The index merged with the site files on disk, sorted by site. A file without an entry, or every
     * file when the index cannot be read, is disabled (fail closed). Entries with invalid names are dropped.
     */
    private fun loadIndex(): Map<String, IndexEntry> {
        val out = sortedMapOf<String, IndexEntry>()
        var corrupt = false
        if (indexFile.exists()) {
            val parsed = try {
                gson.fromJson(indexFile.readText(), CookieIndexJson::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "cannot read the cookie index: ${e.javaClass.simpleName}")
                null
            }
            val sites = parsed?.takeIf { it.version == 1 }?.sites
            if (sites == null) {
                corrupt = true
            } else {
                for (s in sites) {
                    val site = s?.site ?: continue
                    if (!CookieSites.isValidSite(site) || site in out) continue
                    out[site] = IndexEntry(
                        site = site,
                        enabled = s.enabled == true,
                        savedAt = s.savedAt ?: 0L,
                        source = if (s.source == SOURCE_LOGIN) SOURCE_LOGIN else SOURCE_IMPORT,
                        loginUrl = s.loginUrl,
                    )
                }
            }
        }
        var disabled = 0
        for ((site, f) in siteFilesOnDisk()) {
            if (site in out) continue
            out[site] = IndexEntry(site, false, f.lastModified(), SOURCE_IMPORT, null)
            disabled++
        }
        if (corrupt || disabled > 0) Log.w(TAG, "cookie index missing/corrupt, $disabled sites disabled")
        return out
    }

    /** Total size of all site files if the files in [replaced] had these contents. */
    private fun storeSizeWith(replaced: Map<String, ByteArray>): Long {
        val others = siteFilesOnDisk().filterKeys { it !in replaced }.values.sumOf { it.length() }
        return others + replaced.values.sumOf { it.size.toLong() }
    }

    /**
     * Writes [files] (site to bytes), then [index]. On failure the site files that did not exist before
     * are deleted again; they would be disabled anyway, having no index entry.
     */
    private fun writeSites(files: Map<String, ByteArray>, index: Map<String, IndexEntry>) {
        val created = mutableListOf<File>()
        try {
            if (!sitesDir.isDirectory && !sitesDir.mkdirs()) throw IOException("cannot create the cookie dir")
            for ((site, bytes) in files) {
                val f = siteFile(site)
                if (!f.exists()) created += f
                writeAtomic(f, bytes)
            }
            writeIndex(index)
        } catch (e: IOException) {
            created.forEach { it.delete() }
            throw e
        }
    }

    private fun writeIndex(index: Map<String, IndexEntry>) {
        val json = CookieIndexJson(
            version = 1,
            sites = index.values.map { CookieSiteJson(it.site, it.enabled, it.savedAt, it.source, it.loginUrl) },
        )
        writeAtomic(indexFile, gson.toJson(json).toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val dir = target.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the cookie dir")
        val tmp = File(dir, "${target.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            ownerOnly(tmp)
            // rename(2) on Android; also replaces the target on the JVM test host
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e as? IOException ?: IOException("write failed", e)
        }
    }

    /**
     * Legacy single cookies.txt -> per-site files, all enabled. Runs when cookies.txt exists and sites.json
     * does not; if both exist, an earlier migration got as far as the index and cookies.txt is deleted.
     */
    private fun migrate() {
        if (!legacyFile.exists()) return
        if (indexFile.exists()) {
            legacyFile.delete()
            Log.i(TAG, "legacy cookies.txt removed after migration")
            return
        }
        val text = try {
            legacyFile.readText()
        } catch (e: IOException) {
            Log.w(TAG, "cannot read legacy cookies.txt: ${e.javaClass.simpleName}")
            return
        }
        if (CookiesTxt.validate(text, legacyFile.length()) !is CookiesTxt.Result.Valid) {
            legacyFile.delete()
            Log.i(TAG, "legacy cookies.txt invalid, removed")
            return
        }
        val savedAt = legacyFile.lastModified()
        val groups = CookieSites.group(text)
        val files = groups.mapValues { (_, lines) ->
            CookiesTxt.mergeLines(null, lines.joinToString("\n")).toByteArray(Charsets.UTF_8)
        }
        val index = files.keys.associateWith { site ->
            val source = if (site == INSTAGRAM_SITE) SOURCE_LOGIN else SOURCE_IMPORT
            IndexEntry(site, true, savedAt, source, null)
        }
        try {
            writeSites(files, index)
        } catch (e: IOException) {
            // Files written before the failure; a crash-free retry rewrites them
            files.keys.forEach { siteFile(it).delete() }
            Log.w(TAG, "cookie migration failed: ${e.javaClass.simpleName}")
            return
        }
        legacyFile.delete()
        Log.i(TAG, "legacy cookies.txt migrated: ${files.size} sites")
    }

    private fun ownerOnly(f: File) {
        f.setReadable(false, false)
        f.setWritable(false, false)
        f.setReadable(true, true)
        f.setWritable(true, true)
    }

    companion object {
        private const val TAG = "MuremCookies"
        const val DIR = "cookies"
        const val FILE = "cookies.txt"
        const val SITES_DIR = "sites"
        const val INDEX = "sites.json"
        const val SOURCE_LOGIN = "login"
        const val SOURCE_IMPORT = "import"
        private const val INSTAGRAM_SITE = "instagram.com"
        private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"

        /** Shared by all instances (the ViewModel, the backend and the worker each create their own). */
        private val LOCK = Any()
        private val gson = Gson()
    }
}

/** `sites.json`: `{"version":1,"sites":[...]}`. Fields are nullable: Gson fills missing ones with null. */
internal data class CookieIndexJson(
    @SerializedName("version") val version: Int?,
    @SerializedName("sites") val sites: List<CookieSiteJson?>?,
)

internal data class CookieSiteJson(
    @SerializedName("site") val site: String?,
    @SerializedName("enabled") val enabled: Boolean?,
    @SerializedName("saved_at") val savedAt: Long?,
    @SerializedName("source") val source: String?,
    @SerializedName("login_url") val loginUrl: String?,
)

/** A per-run copy of the cookies; [secrets] are the cookie values, scrubbed from any yt-dlp output that is logged. */
class JobCookies(val file: File, val secrets: Set<String>)

/**
 * Runs [block] with a fresh copy of the enabled cookies in [dir] (null if none) and deletes the copy
 * afterwards, also on failure or cancel.
 */
inline fun <T> withJobCookies(store: CookieStore, dir: File, block: (JobCookies?) -> T): T {
    val cookies = store.copyForJob(dir)
    try {
        return block(cookies)
    } finally {
        cookies?.file?.delete()
    }
}
