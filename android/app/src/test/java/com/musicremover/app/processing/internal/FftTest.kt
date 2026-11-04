package com.musicremover.app.processing.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FftTest {
    private fun naiveDft(x: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(2 * n)
        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            for (j in 0 until n) {
                val a = -2.0 * PI * ((j.toLong() * k) % n) / n
                re += x[2 * j] * cos(a) - x[2 * j + 1] * sin(a)
                im += x[2 * j] * sin(a) + x[2 * j + 1] * cos(a)
            }
            out[2 * k] = re
            out[2 * k + 1] = im
        }
        return out
    }

    @Test
    fun forwardMatchesNaiveDft() {
        val rnd = Random(1234)
        for (n in listOf(12, 48, 60, 750, 5120, 6144, 7680)) {
            val x = DoubleArray(2 * n) { rnd.nextDouble(-1.0, 1.0) }
            val expected = naiveDft(x, n)
            val actual = x.copyOf()
            Fft(n).forward(actual)
            val err = expected.indices.maxOf { abs(expected[it] - actual[it]) }
            assertTrue("n=$n err=$err", err < 1e-9 * n)
        }
    }

    @Test
    fun inverseOfForwardIsIdentity() {
        val rnd = Random(99)
        for (n in listOf(12, 48, 60, 750, 5120, 6144, 7680)) {
            val x = DoubleArray(2 * n) { rnd.nextDouble(-1.0, 1.0) }
            val y = x.copyOf()
            val fft = Fft(n)
            fft.forward(y)
            fft.inverse(y)
            val err = x.indices.maxOf { abs(x[it] - y[it] / n) }
            assertTrue("n=$n err=$err", err < 1e-12)
        }
    }

    @Test
    fun rejectsUnsupportedSize() {
        assertThrows(IllegalArgumentException::class.java) { Fft(7 * 16) }
        assertFalse(Fft.isSupportedSize(7 * 16))
        assertTrue(Fft.isSupportedSize(7680))
        assertTrue(Fft.isSupportedSize(5120))
    }
}
