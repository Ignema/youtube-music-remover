package com.musicremover.app.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs
import kotlin.math.max

/**
 * Stream waveform amplitudes incrementally as audio is decoded.
 * Emits growing list of normalized amplitudes (0f..1f).
 */
fun streamWaveform(context: Context, url: String, barCount: Int = 150): Flow<List<Float>> = flow {
    try {
        val extractor = MediaExtractor()
        extractor.setDataSource(url)

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
        if (audioTrackIndex == -1 || audioFormat == null) return@flow

        extractor.selectTrack(audioTrackIndex)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@flow

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(audioFormat, null, null, 0)
        codec.start()

        val allChunks = mutableListOf<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastEmitSize = 0

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(5_000)
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

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5_000)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                val shortBuffer = outputBuffer.asShortBuffer()
                val samples = ShortArray(shortBuffer.remaining())
                shortBuffer.get(samples)

                var maxAmp = 0f
                for (s in samples) {
                    maxAmp = max(maxAmp, abs(s.toFloat()) / Short.MAX_VALUE)
                }
                allChunks.add(maxAmp)
                codec.releaseOutputBuffer(outputIndex, false)

                // Emit incrementally — downsample current chunks to barCount
                if (allChunks.size - lastEmitSize >= 5 || inputDone) {
                    emit(downsample(allChunks, barCount))
                    lastEmitSize = allChunks.size
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        // Final emit
        emit(downsample(allChunks, barCount))

        codec.stop()
        codec.release()
        extractor.release()
    } catch (_: Exception) {
        // Silent fail — waveform just won't show
    }
}.flowOn(Dispatchers.IO)

private fun downsample(chunks: List<Float>, barCount: Int): List<Float> {
    if (chunks.isEmpty()) return emptyList()
    val result = mutableListOf<Float>()
    val chunkSize = max(1, chunks.size / barCount)
    for (i in 0 until barCount) {
        val start = i * chunkSize
        val end = minOf(start + chunkSize, chunks.size)
        if (start >= chunks.size) break
        result.add(chunks.subList(start, end).max())
    }
    return result
}
