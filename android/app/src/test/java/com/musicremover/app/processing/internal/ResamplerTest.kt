package com.musicremover.app.processing.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {
    /** Feeds [x] (both channels) in uneven blocks to exercise the streaming history. */
    private fun resample(x: FloatArray, inRate: Int): Pair<FloatArray, FloatArray> {
        val rs = Resampler(inRate)
        val outL = ArrayList<Float>()
        val outR = ArrayList<Float>()
        val sink = Resampler.Sink { l, r, count ->
            for (i in 0 until count) { outL.add(l[i]); outR.add(r[i]) }
        }
        var pos = 0
        var block = 997
        while (pos < x.size) {
            val n = minOf(block, x.size - pos)
            val l = x.copyOfRange(pos, pos + n)
            rs.process(l, l.copyOf(), n, sink)
            pos += n
            block = if (block == 997) 4096 else 997
        }
        rs.flush(sink)
        return outL.toFloatArray() to outR.toFloatArray()
    }

    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, until: Int): Double {
        var s = 0.0
        for (i in from until until) s += x[i].toDouble() * x[i]
        return sqrt(s / (until - from))
    }

    @Test
    fun downsamples48kSine() {
        val input = sine(1000.0, 48_000, 2.0)
        val (l, r) = resample(input, 48_000)
        val expectedLen = (input.size * 44_100.0 / 48_000).roundToLong()
        assertTrue("len=${l.size} expected=$expectedLen", abs(l.size - expectedLen) <= 1)
        assertArrayEquals(l, r, 0f)

        // Steady state (skip the filter's edge transients): compare against the ideal sine.
        val margin = 2_000
        var err = 0.0
        for (n in margin until l.size - margin) {
            val ideal = 0.5 * sin(2 * PI * 1000 * n / 44_100.0)
            err = maxOf(err, abs(l[n] - ideal))
        }
        println("Resampler 48k->44.1k 1 kHz: len=${l.size} expected=$expectedLen amplitude err=$err")
        assertTrue("amplitude err=$err", err < 1e-3)

        // Dominant frequency from interpolated rising zero crossings.
        var first = -1.0
        var last = -1.0
        var crossings = 0
        for (n in margin until l.size - margin) {
            if (l[n - 1] < 0f && l[n] >= 0f) {
                val t = (n - 1 + l[n - 1] / (l[n - 1] - l[n])) / 44_100.0
                if (first < 0) first = t
                last = t
                crossings++
            }
        }
        val freq = (crossings - 1) / (last - first)
        println("Resampler 1 kHz dominant frequency: $freq Hz")
        assertEquals(1000.0, freq, 1.0)
    }

    @Test
    fun bypassAt44100IsBitExact() {
        val input = sine(440.0, 44_100, 0.5)
        val (l, _) = resample(input, 44_100)
        assertArrayEquals(input, l, 0f)
    }

    @Test
    fun stopBandAttenuatesAboveOutputNyquist() {
        val ref = resample(sine(1000.0, 48_000, 1.0), 48_000).first
        val alias = resample(sine(23_000.0, 48_000, 1.0), 48_000).first
        val margin = 2_000
        val ratioDb = 20 * log10(rms(alias, margin, alias.size - margin) / rms(ref, margin, ref.size - margin))
        println("Resampler 23 kHz stop band: $ratioDb dB")
        assertTrue("stop band ratio=$ratioDb dB", ratioDb < -60)
    }
}
