package com.musicremover.app.processing.internal

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import com.musicremover.app.R
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A fatal on-device job error; [message] is localized and shown to the user. */
class InternalJobException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The source video track kept in the result: copied by [VocalsEncoder] or, for [VideoCodecPolicy.Plan.TRANSCODE],
 * re-encoded to H.264 first by [VideoResultWriter]. [rotation] is 0, 90, 180 or 270.
 */
class VideoTrackInfo(
    val index: Int,
    val format: MediaFormat,
    val mime: String,
    val rotation: Int,
    val plan: VideoCodecPolicy.Plan = VideoCodecPolicy.Plan.PASSTHROUGH,
)

class DecodeResult(
    /** Stereo frames at 44.1 kHz written to the PCM file. */
    val frames: Long,
    /** max |sample| over both channels. */
    val peak: Float,
    val durationUs: Long,
    /** Presentation time of the first decoded audio buffer. */
    val audioStartUs: Long,
    /** null when Audio only is on or the source has no video track. */
    val videoTrack: VideoTrackInfo?,
)

/**
 * Decodes the first audio track of a media file to interleaved float32 stereo PCM at 44.1 kHz
 * (MediaExtractor + MediaCodec + [Resampler]).
 */
class AudioDecoder(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Writes the mix to [out]. [bitrateBps] is only used for the free-storage estimate.
     * [onProgress] receives 0..100 (pts / duration). Throws [InternalJobException].
     */
    // The storage check (§3.6) is a plain estimate against usableSpace; a later write failure maps to the same error
    @SuppressLint("UsableSpace")
    suspend fun decode(
        uri: Uri,
        out: File,
        audioOnly: Boolean,
        bitrateBps: Int,
        onProgress: (Int) -> Unit,
    ): DecodeResult = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(appContext, uri, null)
            } catch (e: IOException) {
                throw fail(R.string.internal_bad_source, e)
            }

            var audioIndex = -1
            var audioFormat: MediaFormat? = null
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            for ((i, f) in formats.withIndex()) {
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioIndex = i
                    audioFormat = f
                    break
                }
            }
            if (audioFormat == null) throw fail(R.string.internal_no_audio)
            val video = if (audioOnly) null else chooseVideo(formats)
            if (video != null && video.plan == VideoCodecPolicy.Plan.UNSUPPORTED) {
                Log.w(TAG, "video track ${video.index} (${video.mime}): no MP4 passthrough and no decoder")
                throw InternalJobException(
                    appContext.getString(R.string.internal_video_codec_unsupported, VideoCodecPolicy.displayMime(video.mime)),
                )
            }

            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) audioFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            // The transcode path writes a temporary H.264 copy; the source size is an upper bound for it
            val transcodeBytes = if (video?.plan == VideoCodecPolicy.Plan.TRANSCODE) sourceSize(appContext, uri) else 0L
            val neededBytes = if (durationUs > 0) {
                val seconds = durationUs / 1_000_000.0
                (seconds * PCM_BYTES_PER_SECOND + seconds * bitrateBps / 8 + SPACE_MARGIN + transcodeBytes).toLong()
            } else -1L
            if (neededBytes > 0 && appContext.filesDir.usableSpace < neededBytes) {
                throw noSpace(neededBytes, null)
            }

            extractor.selectTrack(audioIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            val c = try {
                MediaCodec.createDecoderByType(mime).also {
                    codec = it
                    it.configure(audioFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw fail(R.string.internal_codec_unsupported, e)
            }

            var channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = Resampler(audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            var peak = 0f
            var startUs = -1L
            var l = FloatArray(4096)
            var r = FloatArray(4096)
            var lastPercent = -1

            val frames = try {
                PcmFileWriter(out).use { writer ->
                    val sink = Resampler.Sink { sl, sr, n ->
                        writer.write(sl, sr, 0, n)
                        var p = peak
                        for (j in 0 until n) p = maxOf(p, abs(sl[j]), abs(sr[j]))
                        peak = p
                    }
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    while (!outputDone) {
                        ctx.ensureActive()
                        if (!inputDone) {
                            val ii = c.dequeueInputBuffer(TIMEOUT_US)
                            if (ii >= 0) {
                                val buf = c.getInputBuffer(ii)!!
                                val n = extractor.readSampleData(buf, 0)
                                if (n < 0) {
                                    c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    c.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val oi = c.dequeueOutputBuffer(info, TIMEOUT_US)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = c.outputFormat
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (rate != resampler.inRate) {
                                resampler.flush(sink)
                                resampler = Resampler(rate)
                            }
                        } else if (oi >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                if (startUs < 0) startUs = info.presentationTimeUs
                                val buf = c.getOutputBuffer(oi)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.nativeOrder())
                                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val n = info.size / bytesPerSample / channels
                                if (l.size < n) {
                                    l = FloatArray(n)
                                    r = FloatArray(n)
                                }
                                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = buf.asFloatBuffer()
                                    mapChannels(n, channels, l, r) { fb.get(it) }
                                } else {
                                    val sb = buf.asShortBuffer()
                                    mapChannels(n, channels, l, r) { sb.get(it) / 32768f }
                                }
                                resampler.process(l, r, n, sink)
                                if (durationUs > 0) {
                                    val percent = ((info.presentationTimeUs - startUs) * 100 / durationUs).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        onProgress(percent)
                                    }
                                }
                            }
                            c.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                    resampler.flush(sink)
                    writer.frames
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw noSpace(neededBytes, e)
            } catch (e: IllegalStateException) {
                // MediaCodec.CodecException and other codec state errors
                throw fail(R.string.internal_codec_unsupported, e)
            }

            if (frames == 0L) throw fail(R.string.internal_no_audio)
            DecodeResult(
                frames = frames,
                peak = peak,
                durationUs = if (durationUs > 0) durationUs else frames * 1_000_000L / SAMPLE_RATE,
                audioStartUs = startUs.coerceAtLeast(0L),
                videoTrack = video,
            )
        } finally {
            codec?.let { cd ->
                try { cd.stop() } catch (_: Exception) {}
                try { cd.release() } catch (_: Exception) {}
            }
            extractor.release()
        }
    }

    /** Mono is duplicated; 3+ channels fold the centre (ch 2) into front left/right. */
    private inline fun mapChannels(n: Int, channels: Int, l: FloatArray, r: FloatArray, sample: (Int) -> Float) {
        for (i in 0 until n) {
            val base = i * channels
            when {
                channels == 1 -> {
                    val s = sample(base)
                    l[i] = s
                    r[i] = s
                }
                channels == 2 -> {
                    l[i] = sample(base)
                    r[i] = sample(base + 1)
                }
                else -> {
                    val center = CENTER_GAIN * sample(base + 2)
                    l[i] = sample(base) + center
                    r[i] = sample(base + 1) + center
                }
            }
        }
    }

    /** The video track to keep (see [VideoCodecPolicy.chooseVideoTrack]) and how to write it. */
    private fun chooseVideo(formats: List<MediaFormat>): VideoTrackInfo? {
        val sdk = Build.VERSION.SDK_INT
        val index = VideoCodecPolicy.chooseVideoTrack(formats.map { it.getString(MediaFormat.KEY_MIME) }, sdk)
        if (index < 0) return null
        val f = formats[index]
        val mime = f.getString(MediaFormat.KEY_MIME)!!
        val rawRotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        val rotation = VideoCodecPolicy.normalizeRotation(rawRotation)
        val hasDecoder = !VideoCodecPolicy.isPassthrough(mime, sdk) && hasDecoder(f)
        val plan = VideoCodecPolicy.plan(mime, sdk, hasDecoder)
        val size = if (f.containsKey(MediaFormat.KEY_WIDTH) && f.containsKey(MediaFormat.KEY_HEIGHT)) {
            "${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}"
        } else "?"
        Log.i(TAG, "video track $index: mime=$mime size=$size rotation=$rawRotation->$rotation plan=$plan sdk=$sdk")
        return VideoTrackInfo(index, f, mime, rotation, plan)
    }

    private fun hasDecoder(format: MediaFormat): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
    } catch (e: Exception) {
        Log.w(TAG, "decoder lookup failed", e)
        false
    }

    private fun fail(res: Int, cause: Throwable? = null) = InternalJobException(appContext.getString(res), cause)

    private fun noSpace(neededBytes: Long, cause: Throwable?): InternalJobException {
        val mb = if (neededBytes > 0) neededBytes / 1_000_000 else SPACE_MARGIN / 1_000_000
        return InternalJobException(appContext.getString(R.string.internal_no_space, mb.toInt()), cause)
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        private const val TAG = "MuremInternal"
        /** mix.f32 + vocals.f32: 2 files × 44,100 frames × 8 bytes. */
        private const val PCM_BYTES_PER_SECOND = 705_600.0
        private const val SPACE_MARGIN = 50L * 1024 * 1024
        private const val CENTER_GAIN = 0.7071f
        private const val TIMEOUT_US = 10_000L
    }
}
