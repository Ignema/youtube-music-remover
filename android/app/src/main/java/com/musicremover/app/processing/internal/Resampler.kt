package com.musicremover.app.processing.internal

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming band-limited stereo resampler to [outRate] (windowed sinc, Kaiser β = 8.0,
 * 64 zero crossings per side, 512-phase table with linear interpolation, cutoff
 * 0.95·min(1, outRate/inRate)·Nyquist). History is carried across [process] calls; [flush] emits
 * the tail so the total output is `round(inFrames·outRate/inRate)` frames. When `inRate == outRate`
 * the input is passed through unchanged.
 */
class Resampler(val inRate: Int, val outRate: Int = 44_100) {
    fun interface Sink { fun accept(l: FloatArray, r: FloatArray, count: Int) }

    val bypass = inRate == outRate

    private val scale = 0.95 * min(1.0, outRate.toDouble() / inRate)
    /** Half-width of the filter in input samples. */
    private val halfWidth = ZERO_CROSSINGS / scale
    private val history = ceil(halfWidth).toInt() + 2

    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)
    private var bufStart = 0L // absolute input index of buf[0]
    private var bufCount = 0
    private var inFrames = 0L
    private var outFrames = 0L
    private var outL = FloatArray(OUT_BLOCK)
    private var outR = FloatArray(OUT_BLOCK)
    private var outCount = 0

    init {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive" }
        reset()
    }

    /** Discards all buffered input and restarts the output timeline at 0. */
    fun reset() {
        bufL = FloatArray(maxOf(bufL.size, 4 * history))
        bufR = FloatArray(bufL.size)
        bufStart = -history.toLong()
        bufCount = history // leading zeros: samples before index 0 are silent
        inFrames = 0L
        outFrames = 0L
        outCount = 0
    }

    fun process(l: FloatArray, r: FloatArray, count: Int, sink: Sink) {
        if (count <= 0) return
        if (bypass) {
            inFrames += count
            outFrames += count
            sink.accept(l, r, count)
            return
        }
        append(l, r, count)
        inFrames += count
        produce(limit = Long.MAX_VALUE, sink = sink)
    }

    /** Emits the remaining output (input after the end is treated as silence). */
    fun flush(sink: Sink) {
        if (bypass) return
        val total = (inFrames.toDouble() * outRate / inRate).roundToLong()
        val zeros = FloatArray(history + 2)
        append(zeros, zeros, zeros.size)
        produce(limit = total, sink = sink)
    }

    private fun append(l: FloatArray, r: FloatArray, count: Int) {
        if (bufCount + count > bufL.size) {
            val size = maxOf(2 * bufL.size, bufCount + count)
            bufL = bufL.copyOf(size)
            bufR = bufR.copyOf(size)
        }
        System.arraycopy(l, 0, bufL, bufCount, count)
        System.arraycopy(r, 0, bufR, bufCount, count)
        bufCount += count
    }

    private fun produce(limit: Long, sink: Sink) {
        val end = bufStart + bufCount
        while (outFrames < limit) {
            // t = n·inRate/outRate, split exactly into integer and fraction to avoid drift.
            val num = outFrames * inRate
            val ip = num / outRate
            val t = ip + (num % outRate).toDouble() / outRate
            val hi = floor(t + halfWidth).toLong()
            if (hi >= end) break // needs input that has not arrived yet
            val lo = ceil(t - halfWidth).toLong()
            var accL = 0.0
            var accR = 0.0
            var i = lo
            while (i <= hi) {
                val u = kotlin.math.abs(t - i) * scale * PHASES
                val k = u.toInt()
                if (k < TABLE_LEN) {
                    val frac = u - k
                    val h = TABLE[k] + frac * (TABLE[k + 1] - TABLE[k])
                    val idx = (i - bufStart).toInt()
                    accL += h * bufL[idx]
                    accR += h * bufR[idx]
                }
                i++
            }
            outL[outCount] = (accL * scale).toFloat()
            outR[outCount] = (accR * scale).toFloat()
            outCount++
            outFrames++
            if (outCount == OUT_BLOCK) emit(sink)
        }
        emit(sink)
        compact()
    }

    private fun emit(sink: Sink) {
        if (outCount > 0) sink.accept(outL, outR, outCount)
        outCount = 0
    }

    /** Drops input that no future output needs. */
    private fun compact() {
        val num = outFrames * inRate
        val t = num / outRate + (num % outRate).toDouble() / outRate
        val keepFrom = floor(t - halfWidth).toLong() - 1
        val drop = (keepFrom - bufStart).coerceIn(0L, bufCount.toLong()).toInt()
        if (drop == 0) return
        System.arraycopy(bufL, drop, bufL, 0, bufCount - drop)
        System.arraycopy(bufR, drop, bufR, 0, bufCount - drop)
        bufCount -= drop
        bufStart += drop
    }

    companion object {
        const val ZERO_CROSSINGS = 64
        const val PHASES = 512
        const val KAISER_BETA = 8.0
        private const val OUT_BLOCK = 4096
        private const val TABLE_LEN = ZERO_CROSSINGS * PHASES

        /** h(u) = sinc(u)·kaiser(u / ZERO_CROSSINGS) sampled at u = k / PHASES, with a zero guard. */
        private val TABLE: DoubleArray = DoubleArray(TABLE_LEN + 2).also { tab ->
            val i0Beta = besselI0(KAISER_BETA)
            for (k in 0..TABLE_LEN) {
                val u = k.toDouble() / PHASES
                val sinc = if (k == 0) 1.0 else sin(PI * u) / (PI * u)
                val x = u / ZERO_CROSSINGS
                val w = besselI0(KAISER_BETA * sqrt(maxOf(0.0, 1.0 - x * x))) / i0Beta
                tab[k] = sinc * w
            }
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (term > 1e-17 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
