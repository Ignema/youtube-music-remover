package com.musicremover.app.processing.internal

/**
 * Which source video tracks MediaMuxer (MUXER_OUTPUT_MPEG_4) can copy as-is, and what to do with the
 * rest. Pure Kotlin so it can be unit tested on the JVM.
 *
 * MP4 video codecs accepted by the framework muxer (MediaMuxer docs, MPEG4Writer getFourCCForMime):
 * H.263, MPEG-4 Part 2 and AVC since API 16, HEVC since API 24, AV1 since API 34. VP8/VP9 are
 * WebM-only. Dolby Vision is left out on purpose: MPEG4Writer aborts the process (TRESPASS) for
 * profiles it cannot map, so it is re-encoded instead.
 */
object VideoCodecPolicy {
    enum class Plan { PASSTHROUGH, TRANSCODE, UNSUPPORTED }

    private val BASE_MIMES = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp")
    private const val AV1 = "video/av01"
    private const val API_AV1_MUX = 34
    private const val MAX_DISPLAY = 40
    private val DISPLAY_UNSAFE = Regex("[^a-z0-9./+-]")

    /** The [VocalsEncoder] failure stage that is not caused by the video track. */
    const val STAGE_AUDIO = "audio"

    fun passthroughMimes(sdkInt: Int): Set<String> =
        if (sdkInt >= API_AV1_MUX) BASE_MIMES + AV1 else BASE_MIMES

    fun isPassthrough(mime: String, sdkInt: Int): Boolean = mime.lowercase() in passthroughMimes(sdkInt)

    /**
     * Index into [mimes] of the first `video/` track that can be copied, else the first `video/` track,
     * else -1.
     */
    fun chooseVideoTrack(mimes: List<String?>, sdkInt: Int): Int {
        var firstVideo = -1
        for ((i, m) in mimes.withIndex()) {
            if (m == null || !m.lowercase().startsWith("video/")) continue
            if (isPassthrough(m, sdkInt)) return i
            if (firstVideo < 0) firstVideo = i
        }
        return firstVideo
    }

    fun plan(mime: String, sdkInt: Int, hasDecoder: Boolean): Plan = when {
        isPassthrough(mime, sdkInt) -> Plan.PASSTHROUGH
        hasDecoder -> Plan.TRANSCODE
        else -> Plan.UNSUPPORTED
    }

    /** 0/90/180/270 (what MediaMuxer.setOrientationHint accepts); anything else becomes 0. */
    fun normalizeRotation(deg: Int): Int {
        val d = ((deg % 360) + 360) % 360
        return if (d % 90 == 0) d else 0
    }

    /** A container-supplied MIME made safe to show in an error message. */
    fun displayMime(mime: String?): String {
        val s = mime?.lowercase()?.replace(DISPLAY_UNSAFE, "")?.take(MAX_DISPLAY)
        return if (s.isNullOrEmpty()) "unknown" else s
    }

    /** Clip end for the H.264 transcode, in µs. */
    fun transcodeEndUs(audioEndUs: Long): Long = audioEndUs.coerceAtLeast(0L)

    /** Whether a muxing failure at [stage] should be retried by re-encoding the video. */
    fun isVideoFailure(stage: String, hasVideo: Boolean): Boolean = hasVideo && stage != STAGE_AUDIO
}
