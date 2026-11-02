package com.musicremover.app.processing.internal

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.musicremover.app.R
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Encodes 44.1 kHz float32 stereo PCM to AAC LC and muxes it into MP4, optionally together with the
 * source's video track copied sample by sample (no re-encode).
 */
class VocalsEncoder(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Encodes [pcm] (scaled by [gain]) into [out]. When [video] is set, its samples are copied from
     * [videoSource] and samples past the end of the audio are dropped. [onProgress] receives 0..100.
     * Throws [InternalJobException] (`internal_encode_failed`).
     */
    suspend fun encode(
        pcm: File,
        gain: Float,
        bitrateBps: Int,
        audioStartUs: Long,
        video: VideoTrackInfo?,
        videoSource: Uri?,
        out: File,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var muxerStarted = false
        var muxerStopped = false
        try {
            PcmFileReader(pcm).use { reader ->
                val totalFrames = reader.frames
                val audioEndUs = audioStartUs + totalFrames * 1_000_000L / SAMPLE_RATE

                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder = enc
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()

                val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = mux

                // Video copy: a second extractor with only the video track selected
                var videoTrack = -1
                var vx: MediaExtractor? = null
                if (video != null && videoSource != null) {
                    vx = MediaExtractor().also { videoExtractor = it }
                    vx.setDataSource(appContext, videoSource, null)
                    vx.selectTrack(video.index)
                    videoTrack = mux.addTrack(video.format)
                    if (video.rotation != 0) mux.setOrientationHint(video.rotation)
                }
                var videoBuf = ByteBuffer.allocateDirect(initialVideoBufferSize(video))
                val videoInfo = MediaCodec.BufferInfo()
                var videoPending = false
                var videoDone = vx == null

                fun readVideo() {
                    val x = vx ?: return
                    while (true) {
                        val n = try {
                            x.readSampleData(videoBuf, 0)
                        } catch (e: IllegalArgumentException) {
                            // Buffer too small: the extractor has not advanced, so read the same sample again
                            if (videoBuf.capacity() >= MAX_VIDEO_BUFFER) throw e
                            videoBuf = ByteBuffer.allocateDirect(videoBuf.capacity() * 2)
                            continue
                        }
                        if (n < 0) {
                            videoDone = true
                            videoPending = false
                            return
                        }
                        val pts = x.sampleTime
                        if (pts > audioEndUs) {
                            // Like ffmpeg -shortest: drop video past the end of the audio
                            videoDone = true
                            videoPending = false
                            return
                        }
                        val flags = if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        videoInfo.set(0, n, pts, flags)
                        videoPending = true
                        return
                    }
                }

                var audioTrack = -1
                val audioInfo = MediaCodec.BufferInfo()
                var audioIndex = -1 // pending encoder output buffer
                var audioOutDone = false
                var inputDone = false
                var framesQueued = 0L
                var lastPercent = -1
                val pcmL = FloatArray(MAX_INPUT_SIZE / 4)
                val pcmR = FloatArray(MAX_INPUT_SIZE / 4)

                while (!audioOutDone || videoPending || (!videoDone && muxerStarted)) {
                    ctx.ensureActive()

                    // Feed PCM16 into the encoder
                    if (!inputDone) {
                        val ii = enc.dequeueInputBuffer(TIMEOUT_US)
                        if (ii >= 0) {
                            val buf = enc.getInputBuffer(ii)!!
                            buf.clear()
                            buf.order(ByteOrder.nativeOrder())
                            val maxFrames = minOf(buf.capacity(), MAX_INPUT_SIZE) / 4
                            val n = minOf(maxFrames.toLong(), totalFrames - framesQueued).toInt()
                            val pts = audioStartUs + framesQueued * 1_000_000L / SAMPLE_RATE
                            if (n <= 0) {
                                enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                reader.read(framesQueued, pcmL, pcmR, 0, n)
                                for (j in 0 until n) {
                                    buf.putShort(toPcm16(pcmL[j], gain))
                                    buf.putShort(toPcm16(pcmR[j], gain))
                                }
                                enc.queueInputBuffer(ii, 0, n * 4, pts, 0)
                                framesQueued += n
                                val percent = (framesQueued * 100 / totalFrames).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }

                    // Take the next encoded audio sample
                    if (audioIndex < 0 && !audioOutDone) {
                        val oi = enc.dequeueOutputBuffer(audioInfo, TIMEOUT_US)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            audioTrack = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted = true
                            if (!videoDone) readVideo()
                        } else if (oi >= 0) {
                            if (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || audioInfo.size == 0) {
                                val eos = audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                enc.releaseOutputBuffer(oi, false)
                                if (eos) audioOutDone = true
                            } else {
                                audioIndex = oi
                            }
                        }
                    }

                    if (!muxerStarted) continue

                    // Write whichever pending sample has the smaller pts
                    val writeAudio = audioIndex >= 0 &&
                        (!videoPending || audioInfo.presentationTimeUs <= videoInfo.presentationTimeUs)
                    val writeVideo = videoPending && !writeAudio && (audioIndex >= 0 || audioOutDone)
                    if (writeAudio) {
                        val buf = enc.getOutputBuffer(audioIndex)!!
                        buf.position(audioInfo.offset)
                        buf.limit(audioInfo.offset + audioInfo.size)
                        mux.writeSampleData(audioTrack, buf, audioInfo)
                        val eos = audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(audioIndex, false)
                        audioIndex = -1
                        if (eos) audioOutDone = true
                    } else if (writeVideo) {
                        videoBuf.position(0)
                        videoBuf.limit(videoInfo.size)
                        mux.writeSampleData(videoTrack, videoBuf, videoInfo)
                        videoPending = false
                        vx!!.advance()
                        readVideo()
                    }
                }

                mux.stop()
                muxerStopped = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InternalJobException) {
            throw e
        } catch (e: Exception) {
            // IllegalStateException / IOException / IllegalArgumentException from MediaCodec or MediaMuxer
            throw InternalJobException(appContext.getString(R.string.internal_encode_failed), e)
        } finally {
            encoder?.let { en ->
                try { en.stop() } catch (_: Exception) {}
                try { en.release() } catch (_: Exception) {}
            }
            muxer?.let { m ->
                if (muxerStarted && !muxerStopped) try { m.stop() } catch (_: Exception) {}
                try { m.release() } catch (_: Exception) {}
            }
            videoExtractor?.release()
        }
    }

    private fun initialVideoBufferSize(video: VideoTrackInfo?): Int {
        val f = video?.format ?: return 1
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            val size = f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            if (size > 0) return size
        }
        return DEFAULT_VIDEO_BUFFER
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val MAX_INPUT_SIZE = 16_384
        private const val DEFAULT_VIDEO_BUFFER = 2 * 1024 * 1024
        private const val MAX_VIDEO_BUFFER = 16 * 1024 * 1024
        private const val TIMEOUT_US = 10_000L

        /** Truncation like audio-separator's `(x * 32767).astype(int16)`. */
        fun toPcm16(v: Float, gain: Float): Short = (v * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
