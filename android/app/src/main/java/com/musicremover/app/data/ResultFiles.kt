package com.musicremover.app.data

import java.io.File
import java.io.IOException

/**
 * Result files inside one directory, without Android dependencies so it can be unit tested.
 *
 * Layout:
 * - result `<jobId>_<filename>`
 * - temp `.tmp_<jobId>` (never matches the result prefix)
 * - marker `.ok_<jobId>` with `"<byteLength>\n<resultFileName>"`, written via `.ok_<jobId>.tmp`
 *
 * A result counts as complete only if its marker matches its name and length.
 */
class ResultFiles(private val dir: File) {

    private fun resultPrefix(jobId: String) = "${jobId}_"
    private fun tmpName(jobId: String) = ".tmp_$jobId"
    private fun markerName(jobId: String) = ".ok_$jobId"
    private fun markerTmpName(jobId: String) = ".ok_$jobId.tmp"

    private fun resultFiles(jobId: String): List<File> {
        val prefix = resultPrefix(jobId)
        return dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }?.toList() ?: emptyList()
    }

    /** Temp file to download or encode a result into. Any existing temp is deleted first. */
    fun tempFile(jobId: String): File {
        val tmp = File(dir, tmpName(jobId))
        tmp.delete()
        return tmp
    }

    /**
     * Move [tmp] to `<jobId>_<filename>` and write its marker. Returns the result, or null if the
     * rename failed (tmp is deleted). If only the marker could not be written, [onMarkerError] is
     * called and the file is still returned; it then counts as unverified.
     */
    fun commit(
        jobId: String,
        filename: String,
        tmp: File,
        onMarkerError: (IOException) -> Unit = {},
    ): File? {
        val target = File(dir, "${resultPrefix(jobId)}$filename")
        resultFiles(jobId).forEach { it.delete() }
        File(dir, markerName(jobId)).delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        val markerTmp = File(dir, markerTmpName(jobId))
        try {
            markerTmp.writeText("${target.length()}\n${target.name}", Charsets.UTF_8)
            if (!markerTmp.renameTo(File(dir, markerName(jobId)))) {
                throw IOException("Cannot rename ${markerTmp.name}")
            }
        } catch (e: IOException) {
            markerTmp.delete()
            onMarkerError(e)
        }
        return target
    }

    /** The result for [jobId], only if its marker matches its name and length. */
    fun getCachedFile(jobId: String): File? {
        val marker = File(dir, markerName(jobId))
        val text = try {
            if (!marker.isFile) return null
            marker.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        val length = text.substringBefore('\n').toLongOrNull() ?: return null
        val name = text.substringAfter('\n', "")
        if (!name.startsWith(resultPrefix(jobId)) || name.contains('/') || name.contains('\\')) return null
        val file = File(dir, name)
        return file.takeIf { it.isFile && it.length() == length }
    }

    /** A non-empty result without a valid marker (pre-upgrade or truncated). Last resort only. */
    fun getUnverifiedFile(jobId: String): File? {
        if (getCachedFile(jobId) != null) return null
        return resultFiles(jobId).firstOrNull { it.length() > 0 }
    }

    /** Delete the result, temp and markers of [jobId]. */
    fun delete(jobId: String) {
        resultFiles(jobId).forEach { it.delete() }
        File(dir, tmpName(jobId)).delete()
        File(dir, markerName(jobId)).delete()
        File(dir, markerTmpName(jobId)).delete()
    }

    /**
     * Delete temps and marker temps of jobs not in [keepJobIds], and markers without a result file.
     */
    fun cleanStale(keepJobIds: Set<String>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            val name = f.name
            when {
                name.startsWith(".tmp_") -> {
                    if (name.removePrefix(".tmp_") !in keepJobIds) f.delete()
                }
                name.startsWith(".ok_") && name.endsWith(".tmp") -> {
                    if (name.removePrefix(".ok_").removeSuffix(".tmp") !in keepJobIds) f.delete()
                }
                name.startsWith(".ok_") -> {
                    if (resultFiles(name.removePrefix(".ok_")).isEmpty()) f.delete()
                }
            }
        }
    }

    /** Total size in bytes of everything in the directory */
    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    /** Delete everything in the directory */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

private val MEDIA_EXTENSIONS = setOf("mp4", "m4a", "mp3")

/** [name] without its extension if it is a known media extension (case-insensitive). */
fun displayTitle(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext in MEDIA_EXTENSIONS) name.substringBeforeLast('.') else name
}

/** MIME type of a result file from its extension (case-insensitive); anything else is video/mp4. */
fun resultMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    else -> "video/mp4"
}
