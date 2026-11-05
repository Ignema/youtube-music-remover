package com.musicremover.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.musicremover.app.BuildConfig
import com.musicremover.app.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder

/**
 * "Report a problem": opens GitHub's new-issue page pre-filled with the app version, mode, model, device,
 * Android version and the error. Nothing is sent by the app; the user reviews the text and taps Submit.
 * Links, content URIs and storage paths are removed from the error text first, so the report never
 * contains what the user processed. Crashes are written to a file by [install] and offered on next launch.
 */
object ErrorReport {
    enum class Kind { Manual, JobFailed, Crash, Playback }

    /** What the report says about the app and device; built by [currentInfo], fixed values in tests. */
    data class Info(
        val appVersion: String,
        val versionCode: Int,
        val mode: String?,
        val model: String?,
        val device: String,
        val abi: String,
        val android: String,
        val sdk: Int,
    )

    const val ISSUES_NEW_URL = "https://github.com/Ignema/youtube-music-remover/issues/new"

    /** Error text kept in the report; GitHub refuses very long new-issue URLs (about 8 KB). */
    const val MAX_DETAIL = 3500

    private const val CRASH_FILE = "last_crash.txt"
    private const val TAG = "MuremReport"

    private val LINK = Regex("""(?i)\b(?:https?|content|file|rtmp)://\S+""")
    private val PATH = Regex("""(?:/storage|/sdcard|/mnt|/data/user|/data/data|/data/media)/\S+""")

    /** Removes links and storage paths (what the user processed) and caps the length. Pure. */
    fun sanitize(text: String): String {
        val clean = text.replace(LINK, "<link>").replace(PATH, "<path>").trim()
        return if (clean.length <= MAX_DETAIL) clean else clean.take(MAX_DETAIL) + "\n… (truncated)"
    }

    /** Issue title. Pure. */
    fun title(kind: Kind, detail: String?): String {
        val first = detail?.let { sanitize(it) }?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(80)
        return when (kind) {
            Kind.Crash -> "[Crash] " + (first ?: "App crashed")
            Kind.JobFailed -> "[Bug] Processing failed" + (first?.let { ": $it" } ?: "")
            Kind.Playback -> "[Bug] Playback failed" + (first?.let { ": $it" } ?: "")
            Kind.Manual -> ""
        }
    }

    /** Issue body (Markdown). Pure. */
    fun body(kind: Kind, info: Info, detail: String?): String = buildString {
        appendLine("**What happened**")
        appendLine(
            when (kind) {
                Kind.Crash -> "<!-- What were you doing when Murem closed? -->"
                Kind.JobFailed -> "<!-- What did you process (a link or a file, which site, roughly how long)? -->"
                Kind.Playback -> "<!-- Was it a video or audio result, and did it play before? -->"
                Kind.Manual -> "<!-- Describe what you did and what went wrong -->"
            },
        )
        appendLine()
        appendLine("**Details**")
        appendLine("- App: Murem ${info.appVersion} (${info.versionCode})")
        info.mode?.let { appendLine("- Mode: ${modeLabel(it)}") }
        info.model?.let { appendLine("- Model: $it") }
        appendLine("- Device: ${info.device} (${info.abi})")
        appendLine("- Android: ${info.android} (API ${info.sdk})")
        val d = detail?.let { sanitize(it) }?.takeIf { it.isNotEmpty() }
        if (d != null) {
            appendLine()
            appendLine(if (kind == Kind.Crash) "**Stack trace**" else "**Error**")
            appendLine("```")
            appendLine(d)
            appendLine("```")
        }
    }

    /** The new-issue URL with title and body. Pure. */
    fun issueUrl(kind: Kind, info: Info, detail: String?): String {
        val params = buildList {
            title(kind, detail).takeIf { it.isNotEmpty() }?.let { add("title=" + enc(it)) }
            add("body=" + enc(body(kind, info, detail)))
        }
        return ISSUES_NEW_URL + "?" + params.joinToString("&")
    }

    private fun modeLabel(mode: String) = when (mode) {
        "internal" -> "Internal (on-device)"
        "termux" -> "Local (Termux)"
        "server" -> "Server"
        else -> mode
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun currentInfo(mode: String?, model: String?) = Info(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        mode = mode,
        model = model,
        device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        android = Build.VERSION.RELEASE ?: "?",
        sdk = Build.VERSION.SDK_INT,
    )

    /** Opens the pre-filled issue in the browser. */
    fun open(context: Context, kind: Kind, detail: String?, mode: String?, model: String?) {
        val url = issueUrl(kind, currentInfo(mode, model), detail)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "No browser for the report: ${e.javaClass.simpleName}")
            Toast.makeText(context, context.getString(R.string.report_no_browser), Toast.LENGTH_LONG).show()
        }
    }

    // --- Crashes ---

    /** Writes uncaught exceptions to a file (then lets the system crash the app as usual). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, CRASH_FILE).writeText("Thread: ${thread.name}\n$trace")
            } catch (_: Throwable) {
                // Never let reporting hide the original crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The stack trace of the last crash, if the user has not seen the prompt yet. */
    fun pendingCrash(context: Context): String? =
        File(context.filesDir, CRASH_FILE).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun clearCrash(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
    }
}
