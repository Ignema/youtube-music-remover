package com.musicremover.app.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Extract waveform amplitudes from a media URL.
 * Returns a list of normalized amplitudes (0f..1f) for visualization.
 */
suspend fun extractWaveform(context: Context, url: String, barCount: Int = 150): List<Float> =
    withContext(Dispatchers.IO) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(url)

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return@withContext emptyList()

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            val duration = audioFormat.getLong(MediaFormat.KEY_DURATION) // microseconds
            val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val allSamples = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                    // Read 16-bit PCM samples
                    val shortBuffer = outputBuffer.asShortBuffer()
                    val samples = ShortArray(shortBuffer.remaining())
                    shortBuffer.get(samples)
                    // Take max amplitude per chunk
                    var maxAmp = 0f
                    for (s in samples) {
                        maxAmp = max(maxAmp, abs(s.toFloat()) / Short.MAX_VALUE)
                    }
                    allSamples.add(maxAmp)
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            // Downsample to barCount
            if (allSamples.isEmpty()) return@withContext emptyList()
            val result = mutableListOf<Float>()
            val chunkSize = max(1, allSamples.size / barCount)
            for (i in 0 until barCount) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, allSamples.size)
                if (start >= allSamples.size) break
                result.add(allSamples.subList(start, end).max())
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
