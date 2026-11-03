package com.musicremover.app.processing.internal

import java.io.File

/**
 * yt-dlp arguments for the on-device link requests, added with `YoutubeDLRequest.addCommands`. The library
 * puts its own options (`--js-runtimes`, `--ffmpeg-location`, `--no-cache-dir`) first, so every list ends
 * with `--` (followed only by the URL), and no input can become an option such as `--exec`. Pure.
 */
object YtDlpRequests {
    /** Metadata of one item: `--dump-json`, then `--` and [url]. */
    fun infoArgs(url: String): List<String> = listOf(
        "--dump-json",
        "--no-playlist",
        "--playlist-items", "1",
        "--ignore-config",
        "--no-warnings",
        "--",
        url,
    )

    /**
     * Download from the saved [infoJson] into [outDir] as `<format_id>.<ext>` files. `--fixup never` and
     * `--downloader native` keep yt-dlp from starting any ffmpeg child, which `destroyProcessById` could not
     * kill.
     */
    fun downloadArgs(infoJson: File, outDir: File, audioOnly: Boolean): List<String> = listOf(
        "--load-info-json", infoJson.path,
        "-f", YtDlpFormats.selector(audioOnly),
        "-o", File(outDir, "%(format_id)s.%(ext)s").path,
        "--no-playlist",
        "--no-mtime",
        "--ignore-config",
        "--fixup", "never",
        "--downloader", "native",
        "--",
    )
}
