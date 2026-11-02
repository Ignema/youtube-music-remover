package com.musicremover.app.processing.internal

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Numerical parity against the torch-backed audio-separator reference (design.md §8.2).
 * Skipped unless run with `-Pmurem.parityDir=<dir>` holding input.f32, the HQ_3 model and the
 * reference outputs written by run_ref.py.
 */
class MdxParityTest {
    private val modelName = "UVR-MDX-NET-Inst_HQ_3.onnx"
    private val params = MdxParams(nFft = 6144, dimF = 3072, dimT = 256, hop = 1024, compensate = 1.022f, overlap = 0.25)

    private class Stereo(val l: FloatArray, val r: FloatArray)

    private fun readStereo(file: File): Stereo = PcmFileReader(file).use { rd ->
        val n = rd.frames.toInt()
        Stereo(FloatArray(n), FloatArray(n)).also { rd.read(0, it.l, it.r, 0, n) }
    }

    private fun readFloats(file: File): FloatArray {
        val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(bb.remaining()).also { bb.get(it) }
    }

    /** Minimal RIFF reader for 16-bit PCM stereo WAV; returns samples as floats (int16 values). */
    private fun readWav16(file: File): Stereo {
        val bb = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(12)
        var channels = 0
        var bits = 0
        while (bb.remaining() >= 8) {
            val id = String(ByteArray(4).also { bb.get(it) }, Charsets.US_ASCII)
            val size = bb.int
            val start = bb.position()
            when (id) {
                "fmt " -> { bb.short; channels = bb.short.toInt(); bb.int; bb.int; bb.short; bits = bb.short.toInt() }
                "data" -> {
                    check(channels == 2 && bits == 16) { "expected 16-bit stereo, got $channels ch $bits bit" }
                    val n = size / 4
                    val l = FloatArray(n)
                    val r = FloatArray(n)
                    for (i in 0 until n) { l[i] = bb.short.toFloat(); r[i] = bb.short.toFloat() }
                    return Stereo(l, r)
                }
            }
            bb.position(start + size + (size and 1))
        }
        error("no data chunk in $file")
    }

    private class Metrics(val snrDb: Double, val corr: Double, val maxDiff: Double) {
        override fun toString() = "SNR=%.2f dB corr=%.8f maxDiff=%.3e".format(snrDb, corr, maxDiff)
    }

    private fun metrics(ref: Stereo, kt: Stereo): Metrics {
        assertEquals(ref.l.size, kt.l.size)
        var sig = 0.0; var err = 0.0; var maxDiff = 0.0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var count = 0
        for ((a, b) in listOf(ref.l to kt.l, ref.r to kt.r)) {
            for (i in a.indices) {
                val x = a[i].toDouble(); val y = b[i].toDouble()
                sig += x * x; err += (x - y) * (x - y); maxDiff = maxOf(maxDiff, abs(x - y))
                sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
                count++
            }
        }
        val cov = sxy - sx * sy / count
        val corr = cov / sqrt((sxx - sx * sx / count) * (syy - sy * sy / count))
        return Metrics(10 * log10(sig / err), corr, maxDiff)
    }

    /** Upstream write path: normalize to 0.9 peak, then `(x * 32767).astype(int16)`. */
    private fun toCliInt16(x: Stereo): Stereo {
        var peak = 0f
        for (i in x.l.indices) peak = maxOf(peak, abs(x.l[i]), abs(x.r[i]))
        val g = if (peak > 0.9f) 0.9f / peak else 1f
        return Stereo(FloatArray(x.l.size) { (x.l[it] * g * 32767f).toInt().toFloat() },
            FloatArray(x.r.size) { (x.r[it] * g * 32767f).toInt().toFloat() })
    }

    @Test
    fun matchesTorchReference() = runBlocking {
        val dirName = System.getProperty("murem.parityDir").orEmpty()
        Assume.assumeTrue("murem.parityDir not set", dirName.isNotEmpty())
        val dir = File(dirName)

        val mix = readStereo(File(dir, "input.f32"))
        val n = mix.l.size
        var peak = 0f
        for (i in 0 until n) peak = maxOf(peak, abs(mix.l[i]), abs(mix.r[i]))
        val s = if (peak > 0.9f) 0.9f / peak else 1f

        val primary = Stereo(FloatArray(n), FloatArray(n))
        var spec0: FloatArray? = null
        val runMillis = ArrayList<Long>()
        val ktPrimaryFile = File(dir, "kt_primary.f32")
        val ktVocalsFile = File(dir, "kt_vocals.f32")
        var chunks = 0
        val t0 = System.nanoTime()
        OrtSpectrogramModel(File(dir, modelName), params.dimF, params.dimT).use { ort ->
            val model = object : SpectrogramModel {
                override val input: FloatBuffer get() = ort.input
                override val output: FloatBuffer get() = ort.output
                override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
                    if (spec0 == null) spec0 = FloatArray(input.capacity()) { input.get(it) }
                    val r0 = System.nanoTime()
                    ort.run(input, output)
                    runMillis.add((System.nanoTime() - r0) / 1_000_000)
                }
                override fun close() {}
            }
            PcmFileReader(File(dir, "input.f32")).use { src ->
                PcmFileReader(File(dir, "input.f32")).use { mixForSink ->
                    PcmFileWriter(ktVocalsFile).use { vocalsOut ->
                        PcmFileWriter(ktPrimaryFile).use { primaryOut ->
                            val vocals = VocalsSink(mixForSink, vocalsOut, s, params.compensate)
                            val sink = PrimarySink { pos, pl, pr, count ->
                                System.arraycopy(pl, 0, primary.l, pos.toInt(), count)
                                System.arraycopy(pr, 0, primary.r, pos.toInt(), count)
                                primaryOut.write(pl, pr, 0, count)
                                vocals.accept(pos, pl, pr, count)
                            }
                            MdxSeparator(params, model).demix(src, s, sink) { done, _ -> chunks = done }
                        }
                    }
                }
            }
        }
        val totalMillis = (System.nanoTime() - t0) / 1_000_000
        val ktVocals = readStereo(ktVocalsFile)

        val refPrimary = readStereo(File(dir, "ref_primary.f32"))
        val refVocals = readStereo(File(dir, "ref_vocals.f32"))
        val mp = metrics(refPrimary, primary)
        val mv = metrics(refVocals, ktVocals)
        println("PARITY N=$n peak=$peak s=$s chunks=$chunks ortRunMs=$runMillis totalMs=$totalMillis")
        println("PARITY primary (Tier A float): $mp")
        println("PARITY vocals  (Tier A float): $mv")

        val spec0File = File(dir, "ref_spec0.f32")
        var specDiff = -1.0
        if (spec0File.exists()) {
            val ref = readFloats(spec0File)
            val kt = spec0!!
            assertEquals(ref.size, kt.size)
            var d = 0.0
            var refMax = 0.0
            for (i in ref.indices) { d = maxOf(d, abs(ref[i] - kt[i]).toDouble()); refMax = maxOf(refMax, abs(ref[i]).toDouble()) }
            specDiff = d
            println("PARITY chunk-0 spectrogram maxDiff=%.3e (ref max |x| = %.1f)".format(d, refMax))
        }

        val cliVocalsFile = File(dir, "ref_cli_vocals.wav")
        val cliInstFile = File(dir, "ref_cli_instrumental.wav")
        var cliResults: Pair<Metrics, Metrics>? = null
        if (cliVocalsFile.exists() && cliInstFile.exists()) {
            // Upstream separate(): primary = demix(s·mix)·peak; vocals = −primary·compensate + s·mix.
            val inst = Stereo(FloatArray(n) { primary.l[it] * peak }, FloatArray(n) { primary.r[it] * peak })
            val voc = Stereo(FloatArray(n) { s * mix.l[it] - params.compensate * inst.l[it] },
                FloatArray(n) { s * mix.r[it] - params.compensate * inst.r[it] })
            val mi = metrics(readWav16(cliInstFile), toCliInt16(inst))
            val mvc = metrics(readWav16(cliVocalsFile), toCliInt16(voc))
            println("PARITY instrumental (Tier 0 CLI int16): $mi")
            println("PARITY vocals       (Tier 0 CLI int16): $mvc")
            cliResults = mi to mvc
        }

        assertEquals(3, chunks)
        assertTrue("primary $mp", mp.snrDb > 30)
        assertTrue("vocals $mv", mv.snrDb > 30)
        cliResults?.let { (mi, mvc) ->
            assertTrue("CLI instrumental $mi", mi.snrDb > 30)
            assertTrue("CLI vocals $mvc", mvc.snrDb > 30)
        }
        if (specDiff >= 0) assertTrue("chunk-0 spectrogram maxDiff=$specDiff", specDiff < 1e-3)
    }
}
