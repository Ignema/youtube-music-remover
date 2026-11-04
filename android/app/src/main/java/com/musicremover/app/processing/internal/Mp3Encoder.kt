package com.musicremover.app.processing.internal

import android.content.Context
import java.io.File
import java.io.OutputStream

/** ffmpeg arguments for a CBR MP3 from 44.1 kHz s16le stereo on stdin. */
object Mp3Args {
    /** `-f mp3` because the temp output file has no extension. */
    fun build(bitrateBps: Int, out: File): List<String> = listOf(
        "-hide_banner", "-loglevel", "error",
        "-f", "s16le", "-ar", "44100", "-ac", "2", "-i", "pipe:0",
        "-c:a", "libmp3lame", "-b:a", "${bitrateBps / 1000}k",
        "-f", "mp3", "-y", out.path,
    )
}

/** Encodes the separated float32 PCM to MP3 by piping s16 PCM into the bundled ffmpeg ([FfmpegRunner]). */
class Mp3Encoder(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Writes [pcm] scaled by [gain] (same truncation as [VocalsEncoder.toPcm16]) to [out] at [bitrateBps]
     * CBR; ffmpeg's log goes to [workDir]. [onProgress] receives 0..100. The caller checks the [FfmpegRunner.Result].
     */
    suspend fun encode(
        pcm: File,
        gain: Float,
        bitrateBps: Int,
        out: File,
        workDir: File,
        onProgress: (Int) -> Unit,
    ): FfmpegRunner.Result = FfmpegRunner.run(appContext, Mp3Args.build(bitrateBps, out), workDir, stdin = { stream ->
        writePcm16(pcm, gain, stream, onProgress)
    })

    companion object {
        /** 64 KB of s16 stereo per write. */
        private const val BLOCK_FRAMES = 16 * 1024

        /** Little-endian interleaved s16 of [pcm] to [stream], in 64 KB blocks. */
        fun writePcm16(pcm: File, gain: Float, stream: OutputStream, onProgress: (Int) -> Unit) {
            PcmFileReader(pcm).use { reader ->
                val frames = reader.frames
                val l = FloatArray(BLOCK_FRAMES)
                val r = FloatArray(BLOCK_FRAMES)
                val bytes = ByteArray(BLOCK_FRAMES * 4)
                var written = 0L
                var lastPercent = -1
                while (written < frames) {
                    val n = minOf(BLOCK_FRAMES.toLong(), frames - written).toInt()
                    reader.read(written, l, r, 0, n)
                    var b = 0
                    for (j in 0 until n) {
                        val sl = VocalsEncoder.toPcm16(l[j], gain).toInt()
                        val sr = VocalsEncoder.toPcm16(r[j], gain).toInt()
                        bytes[b++] = sl.toByte()
                        bytes[b++] = (sl shr 8).toByte()
                        bytes[b++] = sr.toByte()
                        bytes[b++] = (sr shr 8).toByte()
                    }
                    stream.write(bytes, 0, b)
                    written += n
                    val percent = (written * 100 / frames).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }
    }
}
