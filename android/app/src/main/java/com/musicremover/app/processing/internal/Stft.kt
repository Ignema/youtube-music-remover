package com.musicremover.app.processing.internal

import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT/iSTFT with the semantics of `torch.stft`/`torch.istft` as used by audio-separator's MDX
 * pipeline: periodic Hann window, `center=True` with reflect padding, one-sided spectrum truncated
 * to the first [dimF] bins.
 *
 * Spectrogram layout is row-major NCHW `[1, 4, dimF, frames]` with channels
 * `[L re, L im, R re, R im]`, i.e. index `((c·dimF) + f)·frames + t`. Buffers are accessed with
 * absolute get/put only. Low-bin zeroing is done by the caller, not here.
 */
class Stft(val nFft: Int, val hop: Int, val dimF: Int) {
    private val fft = Fft(nFft)
    private val half = nFft / 2
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2.0 * PI * it / nFft) }
    private val frame = DoubleArray(2 * nFft)
    private var y = DoubleArray(0)
    private var env = DoubleArray(0)
    private var envLength = -1

    init {
        require(nFft >= 2 && nFft % 2 == 0) { "nFft must be even" }
        require(hop > 0) { "hop must be positive" }
        require(dimF in 1..half + 1) { "dimF must be in 1..${half + 1}" }
    }

    /** Number of frames produced for a chunk of [length] samples. */
    fun frames(length: Int): Int = 1 + length / hop

    /** Writes the spectrogram of the stereo chunk ([chunkL], [chunkR], equal lengths) into [out]. */
    fun forward(chunkL: FloatArray, chunkR: FloatArray, out: FloatBuffer) {
        val len = chunkL.size
        require(chunkR.size == len) { "channel lengths differ" }
        require(len > half) { "chunk must be longer than nFft/2" }
        val dimT = frames(len)
        require(out.capacity() >= 4 * dimF * dimT) { "output buffer too small" }
        for (c in 0 until 2) {
            val x = if (c == 0) chunkL else chunkR
            val reBase = (2 * c) * dimF * dimT
            val imBase = (2 * c + 1) * dimF * dimT
            for (t in 0 until dimT) {
                val start = t * hop - half
                for (k in 0 until nFft) {
                    frame[2 * k] = window[k] * x[reflect(start + k, len)]
                    frame[2 * k + 1] = 0.0
                }
                fft.forward(frame)
                for (f in 0 until dimF) {
                    out.put(reBase + f * dimT + t, frame[2 * f].toFloat())
                    out.put(imBase + f * dimT + t, frame[2 * f + 1].toFloat())
                }
            }
        }
    }

    /**
     * Inverse of [forward]: bins `>= dimF` are treated as 0 and the imaginary parts of the DC and
     * Nyquist bins are ignored (irfft semantics). Writes `outL.size` samples per channel.
     */
    fun inverse(spec: FloatBuffer, outL: FloatArray, outR: FloatArray) {
        val len = outL.size
        require(outR.size == len) { "channel lengths differ" }
        require(len > half) { "chunk must be longer than nFft/2" }
        val dimT = frames(len)
        require(spec.capacity() >= 4 * dimF * dimT) { "spectrogram buffer too small" }
        val total = (dimT - 1) * hop + nFft
        prepareEnvelope(len, dimT, total)
        val scale = 1.0 / nFft
        for (c in 0 until 2) {
            val out = if (c == 0) outL else outR
            val reBase = (2 * c) * dimF * dimT
            val imBase = (2 * c + 1) * dimF * dimT
            java.util.Arrays.fill(y, 0, total, 0.0)
            for (t in 0 until dimT) {
                java.util.Arrays.fill(frame, 0.0)
                for (f in 0 until minOf(dimF, half + 1)) {
                    val re = spec.get(reBase + f * dimT + t).toDouble()
                    val im = if (f == 0 || f == half) 0.0 else spec.get(imBase + f * dimT + t).toDouble()
                    frame[2 * f] = re
                    frame[2 * f + 1] = im
                    if (f != 0 && f != half) {
                        frame[2 * (nFft - f)] = re
                        frame[2 * (nFft - f) + 1] = -im
                    }
                }
                fft.inverse(frame)
                val start = t * hop
                for (k in 0 until nFft) y[start + k] += window[k] * frame[2 * k] * scale
            }
            for (k in 0 until len) out[k] = (y[k + half] / env[k + half]).toFloat()
        }
    }

    private fun prepareEnvelope(len: Int, dimT: Int, total: Int) {
        if (envLength == len) return
        y = DoubleArray(total)
        env = DoubleArray(total)
        for (t in 0 until dimT) {
            val start = t * hop
            for (k in 0 until nFft) env[start + k] += window[k] * window[k]
        }
        envLength = len
    }

    /** torch `reflect` padding: the edge sample is not repeated. */
    private fun reflect(i: Int, len: Int): Int = when {
        i < 0 -> -i
        i >= len -> 2 * (len - 1) - i
        else -> i
    }
}
