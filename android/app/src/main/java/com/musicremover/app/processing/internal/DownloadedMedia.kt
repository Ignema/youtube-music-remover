package com.musicremover.app.processing.internal

import java.io.File

/**
 * The files yt-dlp downloaded for one link: [audio] is decoded, [video] (null for Audio only) supplies the
 * video track. A single muxed file can be both.
 */
class DownloadedMedia(val audio: File, val video: File?) {
    /** What MediaExtractor found in one downloaded file. */
    class Probe(val file: File, val size: Long, val hasAudio: Boolean, val hasVideo: Boolean)

    companion object {
        /**
         * audio = the largest file with audio and no video, else the largest file with audio;
         * video = null if [audioOnly], else the largest file with video. Pure.
         * Throws [YtDlpException] (NO_MEDIA) if no file has audio.
         */
        fun choose(probes: List<Probe>, audioOnly: Boolean): DownloadedMedia {
            val audio = probes.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { it.size }
                ?: probes.filter { it.hasAudio }.maxByOrNull { it.size }
                ?: throw YtDlpException(YtDlpErrors.Kind.NO_MEDIA, "no downloaded file has audio (${probes.size} files)")
            val video = if (audioOnly) null else probes.filter { it.hasVideo }.maxByOrNull { it.size }
            return DownloadedMedia(audio.file, video?.file)
        }
    }
}
