package com.musicremover.app.processing.internal

import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MdxSeparatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val identityParams = MdxParams(6144, 3072, 256, 1024, 1.022f, 0.25, zeroLowBins = false)
    private val params = identityParams.copy(zeroLowBins = true)
    private val trim = 3072
    private val chunk = 1024 * 255
    private val gen = chunk - 2 * trim
    private val step = (0.75 * chunk).toInt()

    private class ArraySource(val l: FloatArray, val r: FloatArray) : StereoSource {
        override val frames: Long get() = l.size.toLong()
        override fun read(pos: Long, l: FloatArray, r: FloatArray, off: Int, count: Int) {
            System.arraycopy(this.l, pos.toInt(), l, off, count)
            System.arraycopy(this.r, pos.toInt(), r, off, count)
        }
    }

    private open class MockModel(p: MdxParams, val f: (FloatBuffer, FloatBuffer, Int) -> Unit) : SpectrogramModel {
        val size = 4 * p.dimF * p.dimT
        override val input: FloatBuffer = OrtSpectrogramModel.allocTensorBuffer(size)
        override val output: FloatBuffer = OrtSpectrogramModel.allocTensorBuffer(size)
        var runs = 0
        override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
            runs++
            f(input, output, size)
        }
        override fun close() {}
    }

    private fun identity(p: MdxParams) = MockModel(p) { i, o, n -> for (k in 0 until n) o.put(k, i.get(k)) }
    private fun tanhModel(p: MdxParams) = MockModel(p) { i, o, n -> for (k in 0 until n) o.put(k, (tanh(i.get(k).toDouble()) * 0.7).toFloat()) }

    private fun signal(n: Int, phase: Double): FloatArray = FloatArray(n) {
        val t = it / 44_100.0
        (0.25 * sin(2 * PI * 220 * t + phase) + 0.25 * sin(2 * PI * 1000 * t) + 0.25 * sin(2 * PI * 5000 * t + phase)).toFloat()
    }

    private class Collector(n: Int) : PrimarySink {
        val l = FloatArray(n)
        val r = FloatArray(n)
        var written = 0L
        override fun accept(pos: Long, primaryL: FloatArray, primaryR: FloatArray, count: Int) {
            assertEquals("sink spans must be contiguous", written, pos)
            System.arraycopy(primaryL, 0, l, pos.toInt(), count)
            System.arraycopy(primaryR, 0, r, pos.toInt(), count)
            written += count
        }
    }

    private fun paddedLength(n: Int): Int = trim + n + (gen + trim - n % gen)

    @Test
    fun identityModelReconstructsInput() = runBlocking {
        for (n in listOf(1, 1000, gen, gen + 1, chunk + 1, 441_000)) {
            val l = signal(n, 0.0)
            val r = signal(n, 1.1)
            val sink = Collector(n)
            MdxSeparator(identityParams, identity(identityParams)).demix(ArraySource(l, r), 1f, sink) { _, _ -> }
            assertEquals("length for n=$n", n.toLong(), sink.written)
            var err = 0.0
            for (i in 0 until n) err = maxOf(err, abs(sink.l[i] - l[i]).toDouble(), abs(sink.r[i] - r[i]).toDouble())
            println("MDX identity n=$n max err=$err")
            assertTrue("n=$n err=$err", err < 1e-3)
        }
    }

    @Test
    fun lowBinsAreZeroedBeforeTheModel() = runBlocking {
        val dimF = params.dimF
        val dimT = params.dimT
        val failures = ArrayList<String>()
        val model = MockModel(params) { input, _, _ ->
            var bin3NonZero = false
            for (c in 0 until 4) {
                for (f in 0 until 3) for (t in 0 until dimT) {
                    if (input.get((c * dimF + f) * dimT + t) != 0f) failures.add("c=$c f=$f t=$t")
                }
                for (t in 0 until dimT) if (input.get((c * dimF + 3) * dimT + t) != 0f) bin3NonZero = true
            }
            if (!bin3NonZero) failures.add("bin 3 all zero")
        }
        val n = 441_000
        MdxSeparator(params, model).demix(ArraySource(signal(n, 0.0), signal(n, 0.5)), 1f, Collector(n)) { _, _ -> }
        assertEquals(3, model.runs)
        assertEquals(emptyList<String>(), failures.take(5))
    }

    @Test
    fun zeroModelGivesScaledMixAsVocals() = runBlocking {
        val n = 100_000
        val l = signal(n, 0.0)
        val r = signal(n, 0.4)
        val mixFile = tmp.newFile("mix.f32")
        PcmFileWriter(mixFile).use { it.write(l, r, 0, n) }
        val vocalsFile = File(tmp.root, "vocals.f32")
        val s = 0.9f / 0.95f
        val zero = MockModel(params) { _, o, size -> for (k in 0 until size) o.put(k, 0f) }
        var peakVocals = -1f
        PcmFileReader(mixFile).use { src ->
            PcmFileReader(mixFile).use { mixForSink ->
                PcmFileWriter(vocalsFile).use { out ->
                    val sink = VocalsSink(mixForSink, out, s, 1.022f)
                    MdxSeparator(params, zero).demix(src, s, sink) { _, _ -> }
                    peakVocals = sink.peakVocals
                }
            }
        }
        val vl = FloatArray(n)
        val vr = FloatArray(n)
        PcmFileReader(vocalsFile).use { assertEquals(n.toLong(), it.frames); it.read(0, vl, vr, 0, n) }
        var peak = 0f
        for (i in 0 until n) {
            assertEquals(s * l[i], vl[i], 0f)
            assertEquals(s * r[i], vr[i], 0f)
            peak = maxOf(peak, abs(s * l[i]), abs(s * r[i]))
        }
        assertEquals(peak, peakVocals, 0f)
    }

    @Test
    fun streamingMatchesWholeArrayReference() = runBlocking {
        val n = 441_000
        val l = signal(n, 0.2)
        val r = signal(n, 0.9)
        val s = 0.8f
        val sink = Collector(n)
        MdxSeparator(params, tanhModel(params)).demix(ArraySource(l, r), s, sink) { _, _ -> }
        val (refL, refR) = referenceDemix(FloatArray(n) { s * l[it] }, FloatArray(n) { s * r[it] }, tanhModel(params))
        var diff = 0.0
        for (i in 0 until n) diff = maxOf(diff, abs(sink.l[i] - refL[i]).toDouble(), abs(sink.r[i] - refR[i]).toDouble())
        println("MDX streaming vs whole-array max diff=$diff")
        assertTrue("diff=$diff", diff < 1e-5)
    }

    /** Whole-array transcription of mdx_separator.py L338–417 (demix) with run_model (L427–463). */
    private suspend fun referenceDemix(mixL: FloatArray, mixR: FloatArray, model: SpectrogramModel): Pair<FloatArray, FloatArray> {
        val n = mixL.size
        val pad = gen + trim - (n % gen)
        val m = trim + n + pad
        val mixture = arrayOf(FloatArray(m), FloatArray(m))
        System.arraycopy(mixL, 0, mixture[0], trim, n)
        System.arraycopy(mixR, 0, mixture[1], trim, n)
        val result = arrayOf(FloatArray(m), FloatArray(m))
        val divider = arrayOf(FloatArray(m), FloatArray(m))
        val stft = Stft(params.nFft, params.hop, params.dimF)
        var i = 0
        while (i < m) {
            val start = i
            val end = minOf(i + chunk, m)
            val actual = end - start
            val window = if (actual == 1) floatArrayOf(1f) else FloatArray(actual) { (0.5 - 0.5 * cos(2 * PI * it / (actual - 1))).toFloat() }
            val partL = FloatArray(chunk)
            val partR = FloatArray(chunk)
            System.arraycopy(mixture[0], start, partL, 0, actual)
            System.arraycopy(mixture[1], start, partR, 0, actual)
            // run_model: stft -> spek[:, :, :3, :] *= 0 -> model -> istft
            stft.forward(partL, partR, model.input)
            for (c in 0 until 4) for (f in 0 until 3) for (t in 0 until params.dimT) model.input.put((c * params.dimF + f) * params.dimT + t, 0f)
            model.run(model.input, model.output)
            val tarL = FloatArray(chunk)
            val tarR = FloatArray(chunk)
            stft.inverse(model.output, tarL, tarR)
            for (j in 0 until actual) {
                tarL[j] *= window[j]
                tarR[j] *= window[j]
                divider[0][start + j] += window[j]
                divider[1][start + j] += window[j]
                result[0][start + j] += tarL[j]
                result[1][start + j] += tarR[j]
            }
            i += step
        }
        val outL = FloatArray(n) { result[0][trim + it] / divider[0][trim + it] }
        val outR = FloatArray(n) { result[1][trim + it] / divider[1][trim + it] }
        return outL to outR
    }

    @Test
    fun chunkCountAndProgress() = runBlocking {
        for (n in listOf(1, gen + 1, 441_000)) {
            val expected = (paddedLength(n) + step - 1) / step
            val model = MockModel(params) { _, _, _ -> }
            val sep = MdxSeparator(params, model)
            assertEquals(expected, sep.chunkCount(n.toLong()))
            val calls = ArrayList<Pair<Int, Int>>()
            sep.demix(ArraySource(FloatArray(n), FloatArray(n)), 1f, Collector(n)) { done, total -> calls.add(done to total) }
            assertEquals(expected, model.runs)
            assertEquals((1..expected).map { it to expected }, calls)
        }
        assertEquals(3, (paddedLength(441_000) + step - 1) / step)
    }

    @Test
    fun cancellationStopsDuringModelRun() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger()
        val model = object : SpectrogramModel {
            override val input: FloatBuffer = OrtSpectrogramModel.allocTensorBuffer(4 * params.dimF * params.dimT)
            override val output: FloatBuffer = OrtSpectrogramModel.allocTensorBuffer(4 * params.dimF * params.dimT)
            override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
                cancellableBlocking(onCancel = { cancels.incrementAndGet(); release.countDown() }) {
                    started.countDown()
                    release.await(30, TimeUnit.SECONDS) // released only by onCancel (like setTerminate)
                }
            }
            override fun close() {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val thrown = AtomicReference<Throwable?>(null)
        val n = 441_000
        val job = scope.launch {
            try {
                MdxSeparator(params, model).demix(ArraySource(FloatArray(n), FloatArray(n)), 1f, Collector(n)) { _, _ -> }
            } catch (e: Throwable) {
                thrown.set(e)
                throw e
            }
        }
        assertTrue(started.await(30, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        job.cancel()
        withTimeout(1_000) { job.join() }
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L)
        assertTrue("got ${thrown.get()}", thrown.get() is CancellationException)
        assertEquals(1, cancels.get())
        scope.cancel()
    }
}
