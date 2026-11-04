package com.musicremover.app.processing.internal

import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class MdxParams(
    val nFft: Int,
    val dimF: Int,
    val dimT: Int,
    val hop: Int,
    val compensate: Float,
    val overlap: Double,
    /** Zero spectrogram bins 0..2 before inference (upstream behaviour); false only in tests. */
    val zeroLowBins: Boolean = true,
)

/** A model mapping a `[batch, 4, dimF, dimT]` spectrogram to one of the same shape. */
interface SpectrogramModel : Closeable {
    /** Chunks per run; slot `s` occupies floats `s·4·dimF·dimT until (s+1)·4·dimF·dimT` of each buffer. */
    val batch: Int get() = 1
    /** Input buffer owned by the model, reused for every run. */
    val input: FloatBuffer
    /** Output buffer owned by the model, reused for every run. */
    val output: FloatBuffer
    suspend fun run(input: FloatBuffer, output: FloatBuffer)
}

/** Random-access stereo float PCM. */
interface StereoSource {
    val frames: Long
    fun read(pos: Long, l: FloatArray, r: FloatArray, off: Int, count: Int)
}

/** Receives the separated primary stem in order; [pos] is the output frame of element 0. */
fun interface PrimarySink {
    fun accept(pos: Long, primaryL: FloatArray, primaryR: FloatArray, count: Int)
}

/**
 * Streaming port of audio-separator's `MDXSeparator.demix` (mdx_separator.py L338–417 at 4dd3477):
 * trim padding, overlapping chunks with a symmetric Hann window per chunk, overlap-add and division
 * by the summed windows. Memory is bounded by the chunk size, independent of the track length.
 */
class MdxSeparator(private val p: MdxParams, private val model: SpectrogramModel) {
    private val trim = p.nFft / 2
    private val chunk = p.hop * (p.dimT - 1)
    private val genSize = chunk - 2 * trim
    private val step = ((1 - p.overlap) * chunk).toInt()
    private val stft = Stft(p.nFft, p.hop, p.dimF)
    private val windows = HashMap<Int, FloatArray>()

    init {
        require(genSize > 0) { "chunk too short for nFft" }
        require(step > 0) { "overlap too large" }
        require(stft.frames(chunk) == p.dimT)
    }

    /** Number of chunks for [frames] input frames (`ceil(M / S)`). */
    fun chunkCount(frames: Long): Int = ceilDiv(paddedLength(frames), step.toLong()).toInt()

    private fun paddedLength(n: Long): Long {
        val pad = genSize + trim - (n % genSize)
        return trim + n + pad
    }

    /**
     * Separates `inputScale·src` and passes the primary stem for output frames `0 until src.frames`
     * to [sink] in order. [progress] is called with (done, total) after each chunk.
     */
    suspend fun demix(src: StereoSource, inputScale: Float, sink: PrimarySink, progress: (Int, Int) -> Unit) {
        val n = src.frames
        val m = paddedLength(n)
        val total = chunkCount(n)
        val chunkL = FloatArray(chunk)
        val chunkR = FloatArray(chunk)
        val outL = FloatArray(chunk)
        val outR = FloatArray(chunk)
        val accL = FloatArray(chunk)
        val accR = FloatArray(chunk)
        val div = FloatArray(chunk)
        val emitL = FloatArray(step)
        val emitR = FloatArray(step)
        val input = model.input
        val output = model.output
        val dimF = p.dimF
        val dimT = p.dimT
        val batch = model.batch
        val slotSize = 4 * dimF * dimT

        var base = 0L // mixture position of acc[0]
        for (group in 0 until total step batch) {
            // Fill slots 0..batch-1 with consecutive chunks; unused slots of a final partial group are zeroed.
            for (slot in 0 until batch) {
                val k = group + slot
                val offset = slot * slotSize
                if (k >= total) {
                    for (i in offset until offset + slotSize) input.put(i, 0f)
                    continue
                }
                val start = k.toLong() * step
                val actual = (minOf(start + chunk, m) - start).toInt()
                readMixture(src, n, inputScale, start, actual, chunkL, chunkR)
                stft.forward(chunkL, chunkR, input, offset)
                if (p.zeroLowBins) {
                    for (c in 0 until 4) for (f in 0 until minOf(3, dimF)) {
                        val row = offset + (c * dimF + f) * dimT
                        for (t in 0 until dimT) input.put(row + t, 0f)
                    }
                }
            }
            model.run(input, output)

            // iSTFT + overlap-add each used slot in chunk order, so emission is the same as with batch 1.
            for (k in group until minOf(group + batch, total)) {
                val start = k.toLong() * step
                val end = minOf(start + chunk, m)
                val actual = (end - start).toInt()
                stft.inverse(output, (k - group) * slotSize, outL, outR)

                // start == base: every chunk starts where the previous one stopped being final.
                val off = (start - base).toInt()
                if (p.overlap != 0.0) {
                    val w = window(actual)
                    for (j in 0 until actual) {
                        accL[off + j] += outL[j] * w[j]
                        accR[off + j] += outR[j] * w[j]
                        div[off + j] += w[j]
                    }
                } else {
                    for (j in 0 until actual) {
                        accL[off + j] += outL[j]
                        accR[off + j] += outR[j]
                        div[off + j] += 1f
                    }
                }

                val next = minOf(start + step, m)
                val from = maxOf(trim.toLong(), base)
                val until = minOf(next, trim + n)
                if (until > from) {
                    val count = (until - from).toInt()
                    val o = (from - base).toInt()
                    for (j in 0 until count) {
                        val d = div[o + j]
                        emitL[j] = if (d == 0f) 0f else accL[o + j] / d
                        emitR[j] = if (d == 0f) 0f else accR[o + j] / d
                    }
                    sink.accept(from - trim, emitL, emitR, count)
                }
                val shift = (next - base).toInt()
                shiftLeft(accL, shift)
                shiftLeft(accR, shift)
                shiftLeft(div, shift)
                base = next

                progress(k + 1, total)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** mixture(pos) = inputScale·src[pos − trim] for trim ≤ pos < trim + n, else 0; zero-filled to the chunk size. */
    private fun readMixture(
        src: StereoSource, n: Long, scale: Float, start: Long, actual: Int, l: FloatArray, r: FloatArray,
    ) {
        java.util.Arrays.fill(l, 0f)
        java.util.Arrays.fill(r, 0f)
        val from = maxOf(start, trim.toLong())
        val until = minOf(start + actual, trim + n)
        if (until <= from) return
        val off = (from - start).toInt()
        val count = (until - from).toInt()
        src.read(from - trim, l, r, off, count)
        if (scale != 1f) {
            for (j in off until off + count) {
                l[j] *= scale
                r[j] *= scale
            }
        }
    }

    /** `np.hanning(len)`: symmetric, `[1.0]` for len 1. */
    private fun window(len: Int): FloatArray = windows.getOrPut(len) {
        if (len == 1) floatArrayOf(1f)
        else FloatArray(len) { (0.5 - 0.5 * cos(2.0 * PI * it / (len - 1))).toFloat() }
    }

    private fun shiftLeft(a: FloatArray, shift: Int) {
        if (shift >= a.size) {
            java.util.Arrays.fill(a, 0f)
            return
        }
        System.arraycopy(a, shift, a, 0, a.size - shift)
        java.util.Arrays.fill(a, a.size - shift, a.size, 0f)
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b
}
