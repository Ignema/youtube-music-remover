package com.musicremover.app.processing.internal

/**
 * yt-dlp `-f` selectors. The comma downloads video and audio as separate files (no ffmpeg merge); H.264
 * first because MediaMuxer copies it, capped at 1080p. Pure.
 *
 * Video alternatives, first match wins:
 * 1. H.264 up to 1080p (video-only or muxed). YouTube always matches here (its muxed format 18 is H.264).
 * 2. The best muxed file up to 1080p, height unknown allowed. Instagram reels whose DASH video is VP9-only
 *    also offer muxed H.264+AAC files with unknown codecs; copying one is faster than transcoding VP9.
 * 3. Any video up to 1080p, then any video, then the best muxed file.
 */
object YtDlpFormats {
    const val VIDEO = "(bv*[vcodec^=avc1][height<=1080]/b[height<=?1080]/bv*[height<=1080]/bv*/b),(ba/b)"
    const val AUDIO_ONLY = "ba/b"

    fun selector(audioOnly: Boolean): String = if (audioOnly) AUDIO_ONLY else VIDEO
}
