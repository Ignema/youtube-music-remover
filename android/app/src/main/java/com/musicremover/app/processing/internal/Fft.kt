package com.musicremover.app.processing.internal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-place complex FFT of size [n] = 2^a·3^b on interleaved re/im [DoubleArray]s.
 *
 * Iterative mixed-radix decimation in time: the input is permuted by mixed-radix digit reversal,
 * then radix-3 stages run before radix-2 stages. Both directions are unnormalized
 * (`inverse(forward(x)) == n·x`).
 */
class Fft(val n: Int) {
    private val radices: IntArray
    private val perm: IntArray
    private val cosTab: DoubleArray
    private val sinTab: DoubleArray
    private val scratch: DoubleArray

    init {
        require(isSupportedSize(n)) { "FFT size must be 2^a*3^b, got $n" }
        val r = ArrayList<Int>()
        var m = n
        while (m % 3 == 0) { r.add(3); m /= 3 }
        while (m % 2 == 0) { r.add(2); m /= 2 }
        radices = r.toIntArray()

        perm = IntArray(n)
        for (idx in 0 until n) {
            var rest = idx
            var size = n
            var pos = 0
            for (s in radices.indices.reversed()) {
                val p = radices[s]
                size /= p
                pos += (rest % p) * size
                rest /= p
            }
            perm[idx] = pos
        }

        cosTab = DoubleArray(n) { cos(2.0 * PI * it / n) }
        sinTab = DoubleArray(n) { sin(2.0 * PI * it / n) }
        scratch = DoubleArray(2 * n)
    }

    /** X[k] = Σ x[j]·e^(−2πi·jk/n). */
    fun forward(data: DoubleArray) = transform(data, -1.0)

    /** x[j] = Σ X[k]·e^(+2πi·jk/n), not divided by n. */
    fun inverse(data: DoubleArray) = transform(data, 1.0)

    private fun transform(data: DoubleArray, sign: Double) {
        require(data.size >= 2 * n) { "data must hold ${2 * n} doubles" }
        System.arraycopy(data, 0, scratch, 0, 2 * n)
        for (idx in 0 until n) {
            val p = perm[idx]
            data[2 * p] = scratch[2 * idx]
            data[2 * p + 1] = scratch[2 * idx + 1]
        }
        var sub = 1 // size of the already transformed sub-blocks
        for (r in radices) {
            val len = sub * r
            val twStep = n / len
            if (r == 3) radix3(data, sub, len, twStep, sign) else radix2(data, sub, len, twStep, sign)
            sub = len
        }
    }

    private fun radix2(d: DoubleArray, sub: Int, len: Int, twStep: Int, sign: Double) {
        var block = 0
        while (block < n) {
            for (j in 0 until sub) {
                val i0 = 2 * (block + j)
                val i1 = 2 * (block + j + sub)
                val wr = cosTab[j * twStep]
                val wi = sign * sinTab[j * twStep]
                val br = d[i1] * wr - d[i1 + 1] * wi
                val bi = d[i1] * wi + d[i1 + 1] * wr
                val ar = d[i0]
                val ai = d[i0 + 1]
                d[i0] = ar + br
                d[i0 + 1] = ai + bi
                d[i1] = ar - br
                d[i1 + 1] = ai - bi
            }
            block += len
        }
    }

    private fun radix3(d: DoubleArray, sub: Int, len: Int, twStep: Int, sign: Double) {
        // e^(sign·2πi/3) = −1/2 + i·sign·√3/2
        val s3 = sign * SQRT3_2
        var block = 0
        while (block < n) {
            for (j in 0 until sub) {
                val i0 = 2 * (block + j)
                val i1 = 2 * (block + j + sub)
                val i2 = 2 * (block + j + 2 * sub)
                val w1 = j * twStep
                val w2 = 2 * j * twStep
                val w1r = cosTab[w1]
                val w1i = sign * sinTab[w1]
                val w2r = cosTab[w2]
                val w2i = sign * sinTab[w2]
                val ar = d[i0]
                val ai = d[i0 + 1]
                val br = d[i1] * w1r - d[i1 + 1] * w1i
                val bi = d[i1] * w1i + d[i1 + 1] * w1r
                val cr = d[i2] * w2r - d[i2 + 1] * w2i
                val ci = d[i2] * w2i + d[i2 + 1] * w2r
                val tr = br + cr
                val ti = bi + ci
                val ur = br - cr
                val ui = bi - ci
                val mr = ar - 0.5 * tr
                val mi = ai - 0.5 * ti
                // (i·s3)·u = (−s3·ui, s3·ur)
                d[i0] = ar + tr
                d[i0 + 1] = ai + ti
                d[i1] = mr - s3 * ui
                d[i1 + 1] = mi + s3 * ur
                d[i2] = mr + s3 * ui
                d[i2 + 1] = mi - s3 * ur
            }
            block += len
        }
    }

    companion object {
        private val SQRT3_2 = sqrt(3.0) / 2.0

        fun isSupportedSize(n: Int): Boolean {
            if (n < 1) return false
            var m = n
            while (m % 3 == 0) m /= 3
            while (m % 2 == 0) m /= 2
            return m == 1
        }
    }
}
