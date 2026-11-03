package com.musicremover.app.processing.internal

/**
 * Overall download percent from yt-dlp stdout lines, for [expectedFiles] files downloaded one after another.
 * The library's own progress Float is sticky (it repeats the last value for unrelated lines and needs `ETA`),
 * so only the line is used. Pure; not thread-safe (the library calls back from one stdout thread).
 */
class DownloadProgress(private val expectedFiles: Int) {
    private var fileIndex = 0
    private var percent = 0.0
    private var last = 0

    /** The new overall value 0..100 (never decreasing), or null if [line] is not a download line. */
    fun onLine(line: String): Int? {
        val s = line.trim()
        when {
            s.startsWith(DESTINATION) -> {
                fileIndex++
                percent = 0.0
            }
            s.startsWith(DOWNLOAD) && s.contains(ALREADY) -> {
                fileIndex++
                percent = 100.0
            }
            else -> {
                val m = PERCENT.find(s) ?: return null
                percent = m.groupValues[1].toDouble().coerceIn(0.0, 100.0)
            }
        }
        val index = fileIndex.coerceIn(1, expectedFiles)
        val overall = (((index - 1) * 100 + percent) / expectedFiles).toInt().coerceIn(0, 100)
        last = maxOf(last, overall)
        return last
    }

    private companion object {
        const val DOWNLOAD = "[download]"
        const val DESTINATION = "[download] Destination:"
        const val ALREADY = "has already been downloaded"
        val PERCENT = Regex("^\\[download]\\s+(\\d+(?:\\.\\d+)?)%")
    }
}
