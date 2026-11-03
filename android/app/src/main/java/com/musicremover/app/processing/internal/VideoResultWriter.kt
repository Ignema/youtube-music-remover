package com.musicremover.app.processing.internal

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.musicremover.app.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Writes the vocals result: AAC only, AAC + the source video copied as-is, or, when the video cannot
 * be copied, AAC + the video re-encoded to H.264 by [VideoTranscoder].
 */
class VideoResultWriter(context: Context) {
    private val appContext = context.applicationContext

    /**
     * [pcm], [gain], [bitrateBps] and [audioStartUs] are passed to [VocalsEncoder.encode]. [source] is
     * the file [video] belongs to; [workDir] holds the temporary H.264 file. [forceTranscode] skips the
     * copy attempt. [onProgress] receives 0..100. Throws [InternalJobException].
     */
    suspend fun write(
        pcm: File,
        gain: Float,
        bitrateBps: Int,
        audioStartUs: Long,
        video: VideoTrackInfo?,
        source: Uri?,
        out: File,
        workDir: File,
        forceTranscode: Boolean = false,
        onProgress: (Int) -> Unit,
    ) {
        val encoder = VocalsEncoder(appContext)
        if (video == null || source == null) {
            encoder.encode(pcm, gain, bitrateBps, audioStartUs, null, null, out, onProgress)
            return
        }
        if (video.plan == VideoCodecPolicy.Plan.PASSTHROUGH && !forceTranscode) {
            try {
                encoder.encode(pcm, gain, bitrateBps, audioStartUs, video, source, out, onProgress)
                return
            } catch (e: VideoCopyException) {
                Log.w(
                    TAG,
                    "video copy failed (mime=${e.mime}, stage=${e.stage}); partial output ${out.path} " +
                        "(${out.length()} bytes) will be overwritten by the H.264 fallback",
                    e,
                )
            }
        }

        // Re-encode the video (0-60), then mux it with the vocals (60-100)
        val h264 = File(workDir, TRANSCODED_NAME)
        try {
            val frames = withContext(Dispatchers.IO) { PcmFileReader(pcm).use { it.frames } }
            val audioEndUs = audioStartUs + frames * 1_000_000L / AudioDecoder.SAMPLE_RATE
            Log.i(TAG, "transcoding video (${video.mime}) to H.264 into ${h264.path}")
            VideoTranscoder(appContext).toH264(source, h264, VideoCodecPolicy.transcodeEndUs(audioEndUs)) { p ->
                onProgress(p * 60 / 100)
            }
            val converted = withContext(Dispatchers.IO) { transcodedTrack(h264) }
            encoder.encode(pcm, gain, bitrateBps, audioStartUs, converted, Uri.fromFile(h264), out) { p ->
                onProgress(60 + p * 40 / 100)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscodeException) {
            Log.e(TAG, "video transcode failed (mime=${video.mime}, error=${e.errorCodeName})", e)
            throw convertFailed(video, e)
        } catch (e: VideoCopyException) {
            Log.e(
                TAG,
                "muxing the H.264 copy failed (source mime=${video.mime}, stage=${e.stage}); " +
                    "output ${out.path} (${out.length()} bytes)",
                e,
            )
            throw convertFailed(video, e)
        } catch (e: InternalJobException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "video fallback failed (mime=${video.mime})", e)
            throw convertFailed(video, e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { h264.delete() }
        }
    }

    /** The video track of the transcoded file, ready for [VocalsEncoder]. */
    private fun transcodedTrack(file: File): VideoTrackInfo {
        val x = MediaExtractor()
        try {
            x.setDataSource(file.path)
            for (i in 0 until x.trackCount) {
                val f = x.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val raw = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                Log.i(TAG, "transcoded track $i: mime=$mime rotation=$raw bytes=${file.length()}")
                return VideoTrackInfo(i, f, mime, VideoCodecPolicy.normalizeRotation(raw), VideoCodecPolicy.Plan.PASSTHROUGH)
            }
            throw IllegalStateException("transcoded file has no video track")
        } finally {
            x.release()
        }
    }

    private fun convertFailed(video: VideoTrackInfo, cause: Throwable) = InternalJobException(
        appContext.getString(R.string.internal_video_convert_failed, VideoCodecPolicy.displayMime(video.mime)),
        cause,
    )

    private companion object {
        const val TAG = "MuremInternal"
        const val TRANSCODED_NAME = "video-h264.mp4"
    }
}
