package com.musicremover.app.processing.internal

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The imported `cookies.txt` for on-device links, in `filesDir/cookies/cookies.txt` (excluded from backup
 * and device transfer by `backup_rules.xml` and `data_extraction_rules.xml`). Jobs never pass this file to
 * yt-dlp, which rewrites its cookie file on exit: they use a copy from [copyForJob]. Names, values and the
 * file contents are never logged.
 */
class CookieStore(context: Context) {
    private val ctx = context.applicationContext
    private val dir = File(ctx.filesDir, DIR)
    private val file = File(dir, FILE)

    sealed interface ImportResult {
        data class Imported(val summary: CookiesTxt.Summary) : ImportResult
        data class Rejected(val reason: CookiesTxt.Invalid, val line: Int) : ImportResult
        data object Unreadable : ImportResult
    }

    /** Reads at most [CookiesTxt.MAX_BYTES] + 1 bytes from [uri], validates them and replaces the stored file. */
    @Synchronized
    fun import(uri: Uri): ImportResult {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buf.size() <= CookiesTxt.MAX_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the selected cookies file: ${e.javaClass.simpleName}")
            null
        } ?: return ImportResult.Unreadable
        val text = bytes.toString(Charsets.UTF_8)
        return when (val r = CookiesTxt.validate(text, bytes.size.toLong())) {
            is CookiesTxt.Result.Rejected -> {
                Log.i(TAG, "cookies.txt rejected: ${r.reason} line ${r.line}")
                ImportResult.Rejected(r.reason, r.line)
            }
            is CookiesTxt.Result.Valid -> try {
                writeAtomic(bytes)
                Log.i(TAG, "cookies.txt imported: ${r.summary.count} cookies, ${r.summary.domains.size} domains")
                ImportResult.Imported(r.summary)
            } catch (e: IOException) {
                Log.w(TAG, "cannot store cookies.txt: ${e.javaClass.simpleName}")
                ImportResult.Unreadable
            }
        }
    }

    sealed interface SaveResult {
        /** [captured] cookies were saved; [summary] describes the whole stored file. */
        data class Saved(val captured: CookiesTxt.Summary, val summary: CookiesTxt.Summary) : SaveResult
        data class Rejected(val reason: CookiesTxt.Invalid) : SaveResult
        data object NoCookies : SaveResult
        data object Unwritable : SaveResult
    }

    /**
     * Merges cookies captured by "Log in with browser" into the stored file (see [CookiesTxt.merge]):
     * entries with the same domain and name are replaced, other sites are kept. A stored file that no
     * longer validates is replaced. The result is validated (1 MB cap) before it is written.
     */
    @Synchronized
    fun saveCaptured(entries: List<CookiesTxt.Entry>): SaveResult {
        if (entries.isEmpty()) {
            Log.i(TAG, "no cookies captured")
            return SaveResult.NoCookies
        }
        val merged = CookiesTxt.merge(readValid()?.first, entries)
        val bytes = merged.toByteArray(Charsets.UTF_8)
        return when (val r = CookiesTxt.validate(merged, bytes.size.toLong())) {
            is CookiesTxt.Result.Rejected -> {
                Log.i(TAG, "captured cookies rejected: ${r.reason}")
                SaveResult.Rejected(r.reason)
            }
            is CookiesTxt.Result.Valid -> try {
                writeAtomic(bytes)
                val captured = CookiesTxt.Summary(
                    entries.distinctBy { it.domain to it.name }.size,
                    entries.map { it.domain.removePrefix(".") }.distinct().sorted(),
                )
                Log.i(
                    TAG,
                    "captured cookies saved: ${captured.count} cookies, ${captured.domains.size} domains " +
                        "(file: ${r.summary.count} cookies, ${r.summary.domains.size} domains)",
                )
                SaveResult.Saved(captured, r.summary)
            } catch (e: IOException) {
                Log.w(TAG, "cannot store cookies.txt: ${e.javaClass.simpleName}")
                SaveResult.Unwritable
            }
        }
    }

    private fun writeAtomic(bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(bytes)
        ownerOnly(tmp)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename failed")
        }
    }

    /** Count and domains of the stored file, or null if none (or it no longer validates). */
    @Synchronized
    fun summary(): CookiesTxt.Summary? = readValid()?.second

    @Synchronized
    fun hasCookies(): Boolean = file.isFile

    @Synchronized
    fun remove(): Boolean {
        val ok = !file.exists() || file.delete()
        Log.i(TAG, "cookies.txt removed: $ok")
        return ok
    }

    /**
     * Copies the stored file to `dir/cookies.txt` for one yt-dlp run, or returns null if none is imported.
     * The caller deletes the copy (see [withJobCookies]).
     */
    @Synchronized
    fun copyForJob(dir: File): JobCookies? {
        val (text, _) = readValid() ?: return null
        dir.mkdirs()
        val copy = File(dir, FILE)
        copy.writeText(text)
        ownerOnly(copy)
        return JobCookies(copy, CookiesTxt.values(text))
    }

    private fun readValid(): Pair<String, CookiesTxt.Summary>? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            Log.w(TAG, "cannot read stored cookies.txt: ${e.javaClass.simpleName}")
            return null
        }
        val r = CookiesTxt.validate(text, file.length()) as? CookiesTxt.Result.Valid ?: return null
        return text to r.summary
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
    }
}

/** A per-run copy of the cookies; [secrets] are the cookie values, scrubbed from any yt-dlp output that is logged. */
class JobCookies(val file: File, val secrets: Set<String>)

/**
 * Runs [block] with a fresh copy of the imported cookies in [dir] (null if none are imported) and deletes
 * the copy afterwards, also on failure or cancel.
 */
inline fun <T> withJobCookies(store: CookieStore, dir: File, block: (JobCookies?) -> T): T {
    val cookies = store.copyForJob(dir)
    try {
        return block(cookies)
    } finally {
        cookies?.file?.delete()
    }
}
