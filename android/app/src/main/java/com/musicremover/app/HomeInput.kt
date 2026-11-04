package com.musicremover.app

/**
 * Pure Home-input rules (no Android types, so they run in JVM unit tests): the Link/File modes,
 * finding several links in pasted or shared text, which source Process uses, and which mode an
 * entry intent opens.
 */
object HomeInput {
    const val TAB_LINK = 0
    const val TAB_FILE = 1

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    private const val MAX_URL = 2048
    private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val YOUTUBE_ID = Regex("^[a-zA-Z0-9_-]{11}$")
    private const val TRAILING = ".,;:!?)]}>\"'"

    /** 1 -> File; 2 (the old Batch mode) and any other value -> Link. */
    fun normalizeInputTab(raw: Int): Int = if (raw == TAB_FILE) TAB_FILE else TAB_LINK

    /**
     * http(s) URLs found anywhere in [text] (trailing `.,;:!?)]}>"'` stripped), plus lines that are
     * exactly an 11-char YouTube id; distinct, in first-seen order, each at most 2048 chars.
     */
    fun extractLinks(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (YOUTUBE_ID.matches(line)) {
                out += line
                continue
            }
            for (m in URL.findAll(line)) {
                val url = m.value.trimEnd { it in TRAILING }
                if (url.length > MAX_URL) continue
                // Something after "://"
                if (url.substringAfter("://").isEmpty()) continue
                out += url
            }
        }
        return out.toList()
    }

    /**
     * Whether a field change from [old] to [new] holding 2+ links becomes list rows: a paste or share
     * (several chars at once), or typing that ends in whitespace, so a half-typed last link is never cut.
     */
    fun isLinkListInput(old: String, new: String): Boolean =
        new.length - old.length > 1 || new.lastOrNull()?.isWhitespace() == true

    /** Short row label: host without "www." plus path and query, ellipsized to [max] chars; falls back to the input. */
    fun linkLabel(url: String, max: Int = 48): String {
        val label = try {
            val u = java.net.URI(url)
            val host = u.host?.removePrefix("www.")
            if (host.isNullOrEmpty()) url
            else host + (u.rawPath ?: "").trimEnd('/') + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) {
            url
        }
        return if (label.length > max) label.take(max - 1) + "…" else label
    }

    sealed interface Source {
        data class Links(val urls: List<String>) : Source
        data class Single(val text: String) : Source
        data object File : Source
        data object None : Source
    }

    /**
     * What Process runs. On Link the list and the field win over a stale selected file; on File the
     * file wins over stale field text. When the visible mode has nothing, the old order applies (file, then field).
     */
    fun pickSource(tab: Int, links: List<String>, fieldText: String, hasFile: Boolean): Source {
        if (normalizeInputTab(tab) == TAB_FILE && hasFile) return Source.File
        val combined = (links + extractLinks(fieldText)).distinct()
        if (combined.size >= 2) return Source.Links(combined)
        if (links.isNotEmpty()) return Source.Single(combined.first())
        // One link or other text in the field: the raw text, as before
        if (fieldText.isNotBlank()) return Source.Single(fieldText.trim())
        if (hasFile) return Source.File
        return Source.None
    }

    /** The parts of an entry Intent that decide the mode; built by MainActivity. */
    data class EntryFacts(val action: String?, val type: String?, val text: String?, val streamCount: Int)

    sealed interface Entry {
        data class Links(val urls: List<String>) : Entry
        data class SingleLink(val text: String) : Entry
        data class Files(val count: Int) : Entry
        data object WidgetPaste : Entry
        data object WidgetFile : Entry
        data object WidgetLinks : Entry
        data object WidgetSettings : Entry
        data object None : Entry
    }

    private fun isMedia(type: String?): Boolean =
        type != null && (type.startsWith("video/") || type.startsWith("audio/"))

    fun entryFor(f: EntryFacts): Entry = when (f.action) {
        ACTION_SEND -> when {
            isMedia(f.type) -> if (f.streamCount >= 1) Entry.Files(f.streamCount) else Entry.None
            f.type == "text/plain" -> {
                val text = f.text
                if (text.isNullOrBlank()) Entry.None
                else {
                    val links = extractLinks(text)
                    if (links.size >= 2) Entry.Links(links)
                    // The first link, or the whole text as before
                    else Entry.SingleLink(links.firstOrNull() ?: text)
                }
            }
            else -> Entry.None
        }
        ACTION_SEND_MULTIPLE -> if (isMedia(f.type) && f.streamCount >= 1) Entry.Files(f.streamCount) else Entry.None
        MuremWidget.ACTION_PASTE_PROCESS -> Entry.WidgetPaste
        MuremWidget.ACTION_FILE -> Entry.WidgetFile
        // Kept so widgets placed before Batch became part of Link keep working
        MuremWidget.ACTION_BATCH -> Entry.WidgetLinks
        MuremWidget.ACTION_SETTINGS -> Entry.WidgetSettings
        else -> Entry.None
    }
}
