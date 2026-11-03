package com.musicremover.app.processing.internal

/**
 * yt-dlp `-f` selectors. The comma downloads video and audio as separate files (no ffmpeg merge); H.264
 * first because MediaMuxer copies it, capped at 1080p. Pure.
 */
object YtDlpFormats {
    const val VIDEO = "(bv*[vcodec^=avc1][height<=1080]/bv*[height<=1080]/bv*/b),(ba/b)"
    const val AUDIO_ONLY = "ba/b"

    fun selector(audioOnly: Boolean): String = if (audioOnly) AUDIO_ONLY else VIDEO
}
