package com.musicremover.app.processing.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StftTest {
    private val nFft = 6144
    private val hop = 1024
    private val c = 261_120

    private fun sines(len: Int, phase: Double): FloatArray = FloatArray(len) {
        val t = it / 44_100.0
        (0.3 * sin(2 * PI * 220 * t + phase) + 0.3 * sin(2 * PI * 1000 * t) + 0.3 * sin(2 * PI * 5000 * t + 2 * phase)).toFloat()
    }

    private fun roundTripError(dimF: Int, l: FloatArray, r: FloatArray): Double {
        val stft = Stft(nFft, hop, dimF)
        assertEquals(256, stft.frames(l.size))
        val spec = OrtSpectrogramModel.allocTensorBuffer(4 * dimF * 256)
        stft.forward(l, r, spec)
        val outL = FloatArray(l.size)
        val outR = FloatArray(r.size)
        stft.inverse(spec, outL, outR)
        var err = 0.0
        for (i in l.indices) {
            err = maxOf(err, abs(outL[i] - l[i]).toDouble(), abs(outR[i] - r[i]).toDouble())
        }
        return err
    }

    @Test
    fun roundTripAllBinsRandomStereo() {
        val rnd = Random(42)
        val l = FloatArray(c) { rnd.nextFloat() * 2 - 1 }
        val r = FloatArray(c) { rnd.nextFloat() * 2 - 1 }
        val err = roundTripError(nFft / 2 + 1, l, r)
        println("STFT round trip dimF=3073 random: max err=$err")
        assertTrue("err=$err", err < 1e-4)
    }

    @Test
    fun roundTripProductionBinsOnSines() {
        val err = roundTripError(3072, sines(c, 0.0), sines(c, 0.7))
        println("STFT round trip dimF=3072 sines: max err=$err")
        assertTrue("err=$err", err < 1e-4)
    }

    @Test
    fun oneKilohertzPeaksInBin139() {
        val dimF = 3072
        val dimT = 256
        val x = FloatArray(c) { (0.5 * sin(2 * PI * 1000 * it / 44_100.0)).toFloat() }
        val spec = OrtSpectrogramModel.allocTensorBuffer(4 * dimF * dimT)
        Stft(nFft, hop, dimF).forward(x, x, spec)
        val t = 128
        var best = -1
        var bestMag = -1.0
        for (f in 0 until dimF) {
            val re = spec.get((0 * dimF + f) * dimT + t).toDouble()
            val im = spec.get((1 * dimF + f) * dimT + t).toDouble()
            val mag = hypot(re, im)
            if (mag > bestMag) { bestMag = mag; best = f }
        }
        assertEquals(139, best)
    }

    @Test
    fun leftOnlyInputLeavesRightChannelsZero() {
        val dimF = 3072
        val dimT = 256
        val l = sines(c, 0.3)
        val r = FloatArray(c)
        val spec = OrtSpectrogramModel.allocTensorBuffer(4 * dimF * dimT)
        Stft(nFft, hop, dimF).forward(l, r, spec)
        var leftNonZero = false
        for (i in 0 until 2 * dimF * dimT) if (spec.get(i) != 0f) { leftNonZero = true; break }
        assertTrue(leftNonZero)
        for (i in 2 * dimF * dimT until 4 * dimF * dimT) assertEquals(0f, spec.get(i), 0f)
    }

    @Test
    fun dimFRange() {
        Stft(nFft, hop, 1)
        Stft(nFft, hop, nFft / 2 + 1)
        assertThrows(IllegalArgumentException::class.java) { Stft(nFft, hop, 0) }
        assertThrows(IllegalArgumentException::class.java) { Stft(nFft, hop, nFft / 2 + 2) }
    }
}
