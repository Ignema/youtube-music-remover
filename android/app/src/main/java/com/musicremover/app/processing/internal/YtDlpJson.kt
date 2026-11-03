package com.musicremover.app.processing.internal

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.musicremover.app.data.VideoInfo

/**
 * Metadata of one link from `yt-dlp --dump-json`. [rawJson] is passed back to yt-dlp with
 * `--load-info-json` and is never logged. [knownBytes] is the sum of `filesize ?: filesize_approx` of the
 * formats yt-dlp selected, or null if none is known.
 */
class YtDlpInfo(val videoInfo: VideoInfo, val isLive: Boolean, val rawJson: String, val knownBytes: Long?)

/** Maps `--dump-json` stdout to [YtDlpInfo], the same way as the server's `/api/info`. Pure. */
object YtDlpJson {
    private const val DESCRIPTION_CHARS = 300
    private val LIVE_STATUSES = setOf("is_live", "is_upcoming", "post_live")

    /** Parses the first stdout line that starts with `{`. Throws [YtDlpException] (OTHER) if there is none. */
    fun parse(stdout: String): YtDlpInfo {
        val line = stdout.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("{") }
            ?: throw YtDlpException(YtDlpErrors.Kind.OTHER, "no JSON in yt-dlp output")
        val obj = try {
            JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            throw YtDlpException(YtDlpErrors.Kind.OTHER, "invalid yt-dlp JSON", e)
        } ?: throw YtDlpException(YtDlpErrors.Kind.OTHER, "yt-dlp JSON is not an object")

        val info = VideoInfo(
            title = obj.str("title"),
            channel = obj.str("channel").ifEmpty { obj.str("uploader") },
            duration = obj.num("duration")?.toInt() ?: 0,
            thumbnail = obj.str("thumbnail"),
            view_count = obj.long("view_count") ?: 0L,
            upload_date = obj.str("upload_date"),
            description = obj.str("description").take(DESCRIPTION_CHARS),
        )
        val isLive = obj.bool("is_live") == true || obj.str("live_status") in LIVE_STATUSES
        return YtDlpInfo(info, isLive, line, knownBytes(obj))
    }

    private fun knownBytes(obj: JsonObject): Long? {
        val requested = obj.get("requested_formats")?.takeIf { it.isJsonArray }?.asJsonArray
        val sizes = if (requested != null && requested.size() > 0) {
            requested.mapNotNull { f -> f.takeIf { it.isJsonObject }?.asJsonObject?.formatBytes() }
        } else {
            listOfNotNull(obj.formatBytes())
        }
        return if (sizes.isEmpty()) null else sizes.sum()
    }

    private fun JsonObject.formatBytes(): Long? = long("filesize") ?: long("filesize_approx")

    private fun JsonObject.prim(key: String): JsonElement? =
        get(key)?.takeIf { it.isJsonPrimitive }

    /** A JSON string, else "" (null, missing or another type). */
    private fun JsonObject.str(key: String): String =
        prim(key)?.asJsonPrimitive?.takeIf { it.isString }?.asString ?: ""

    private fun JsonObject.num(key: String): Double? =
        prim(key)?.asJsonPrimitive?.takeIf { it.isNumber }?.asDouble

    private fun JsonObject.long(key: String): Long? =
        prim(key)?.asJsonPrimitive?.takeIf { it.isNumber }?.asLong

    private fun JsonObject.bool(key: String): Boolean? =
        prim(key)?.asJsonPrimitive?.takeIf { it.isBoolean }?.asBoolean
}
