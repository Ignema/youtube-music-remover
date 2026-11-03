package com.musicremover.app.processing.internal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The H.264 re-encode failed; [errorCodeName] is [ExportException.getErrorCodeName]. */
class TranscodeException(val errorCodeName: String, cause: Throwable) :
    Exception("video transcode failed: $errorCodeName", cause)

/**
 * Re-encodes the video of a media file to a video-only H.264 MP4 with Media3 Transformer. Used when
 * MediaMuxer cannot copy the source video track (VP9, Dolby Vision, AV1 below API 34, ...) or the
 * copy failed.
 */
@OptIn(UnstableApi::class)
class VideoTranscoder(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Writes the first [endUs] µs of [source]'s video to [out] as H.264, without audio. [onProgress]
     * receives 0..100. Throws [TranscodeException].
     */
    suspend fun toH264(source: Uri, out: File, endUs: Long, onProgress: (Int) -> Unit) {
        // Transformer calls back on the thread it was created on, which needs a Looper
        withContext(Dispatchers.Main) {
            val main = Handler(Looper.getMainLooper())
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(ForceVideoEncode(DefaultEncoderFactory.Builder(appContext).build()))
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            main.removeCallbacksAndMessages(null)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            main.removeCallbacksAndMessages(null)
                            if (cont.isActive) {
                                cont.resumeWithException(TranscodeException(exportException.errorCodeName, exportException))
                            }
                        }
                    })
                    .build()

                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setEndPositionMs(endUs / 1000)
                    .build()
                val item = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(source).setClippingConfiguration(clip).build(),
                )
                    .setRemoveAudio(true)
                    .build()
                transformer.start(item, out.path)

                val holder = ProgressHolder()
                var last = -1
                val poll = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE &&
                            holder.progress != last
                        ) {
                            last = holder.progress
                            onProgress(last.coerceIn(0, 100))
                        }
                        main.postDelayed(this, PROGRESS_POLL_MS)
                    }
                }
                main.postDelayed(poll, PROGRESS_POLL_MS)

                cont.invokeOnCancellation {
                    main.post {
                        main.removeCallbacks(poll)
                        transformer.cancel()
                    }
                }
            }
        }
    }

    /**
     * Always re-encodes video: without this, Transformer would copy an H.264 source as-is, which is
     * what just failed in [VocalsEncoder].
     */
    private class ForceVideoEncode(private val delegate: Codec.EncoderFactory) : Codec.EncoderFactory {
        override fun createForAudioEncoding(format: Format): Codec = delegate.createForAudioEncoding(format)
        override fun createForVideoEncoding(format: Format): Codec = delegate.createForVideoEncoding(format)
        override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()
        override fun videoNeedsEncoding(): Boolean = true
    }

    private companion object {
        const val PROGRESS_POLL_MS = 500L
    }
}
