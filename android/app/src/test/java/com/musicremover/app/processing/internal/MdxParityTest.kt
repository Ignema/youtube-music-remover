package com.musicremover.app.processing.internal

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
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
 * Numerical parity against the torch-backed audio-separator 0.48.0 reference (phase4 design.md §1a.5).
 * Skipped unless run with `-Pmurem.parityDir=<dir>`. The dir holds input.f32, manifest.json and the
 * reference files written by run_ref.py; models are resolved as `File(File(dir, models_dir), model)`.
 */
class MdxParityTest {
    private class Manifest(
        @SerializedName("models_dir") val modelsDir: String?,
        @SerializedName("cases") val cases: List<Case>?,
    )

    private class Case(
        @SerializedName("tag") val tag: String?,
        @SerializedName("model") val model: String?,
        @SerializedName("n_fft") val nFft: Int?,
        @SerializedName("dim_f") val dimF: Int?,
        @SerializedName("compensate") val compensate: Float?,
        @SerializedName("primary") val primary: String?,
        @SerializedName("overlap") val overlap: Double?,
        @SerializedName("batch") val batch: Int?,
        @SerializedName("ref_primary") val refPrimary: String?,
        @SerializedName("ref_vocals") val refVocals: String?,
        @SerializedName("ref_spec0") val refSpec0: String?,
        @SerializedName("cli_vocals") val cliVocals: String?,
        @SerializedName("cli_instrumental") val cliInstrumental: String?,
        @SerializedName("compare_to") val compareTo: String?,
    )

    private class Stereo(val l: FloatArray, val r: FloatArray)

    private class KtResult(val primary: Stereo, val vocals: Stereo)

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
        val manifest = Gson().fromJson(File(dir, "manifest.json").readText(), Manifest::class.java)
        val modelsDir = File(dir, manifest.modelsDir ?: error("manifest: models_dir missing"))
        val cases = manifest.cases ?: error("manifest: cases missing")
        assertTrue("manifest has no cases", cases.isNotEmpty())

        val inputFile = File(dir, "input.f32")
        val mix = readStereo(inputFile)
        val n = mix.l.size
        var peak = 0f
        for (i in 0 until n) peak = maxOf(peak, abs(mix.l[i]), abs(mix.r[i]))
        val s = if (peak > 0.9f) 0.9f / peak else 1f
        println("PARITY N=$n peak=$peak s=$s cases=${cases.size}")

        val results = HashMap<String, KtResult>()
        val failures = ArrayList<String>()
        val summary = StringBuilder("case\tprimaryA\tvocalsA\tprimary0\tsecondary0\tcorrPrimaryA\tmaxDiffPrimaryA\tspec0MaxDiff\tchunks\tms\n")
        for (c in cases) {
            val tag = c.tag ?: error("manifest: case without tag")
            fun need(v: String?, field: String): String = v ?: error("manifest: $field missing for $tag")
            val primaryStem = c.primary
            if (primaryStem != "Instrumental" && primaryStem != "Vocals") error("manifest: primary missing or unknown for $tag: $primaryStem")
            val params = MdxParams(
                nFft = c.nFft ?: error("manifest: n_fft missing for $tag"),
                dimF = c.dimF ?: error("manifest: dim_f missing for $tag"),
                dimT = 256, hop = 1024,
                compensate = c.compensate ?: error("manifest: compensate missing for $tag"),
                overlap = c.overlap ?: error("manifest: overlap missing for $tag"),
            )
            val batch = c.batch ?: 1
            val modelFile = File(modelsDir, need(c.model, "model"))
            val slot = 4 * params.dimF * params.dimT

            val primary = Stereo(FloatArray(n), FloatArray(n))
            var spec0: FloatArray? = null
            val runMillis = ArrayList<Long>()
            val ktVocalsFile = File(dir, "kt_${tag}_vocals.f32")
            var chunks = 0
            var total = 0
            val t0 = System.nanoTime()
            OrtSpectrogramModel(modelFile, params.dimF, params.dimT, threads = 4, batch = batch).use { ort ->
                val model = object : SpectrogramModel {
                    override val batch: Int get() = ort.batch
                    override val input: FloatBuffer get() = ort.input
                    override val output: FloatBuffer get() = ort.output
                    override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
                        if (spec0 == null) spec0 = FloatArray(slot) { input.get(it) }
                        val r0 = System.nanoTime()
                        ort.run(input, output)
                        runMillis.add((System.nanoTime() - r0) / 1_000_000)
                    }
                    override fun close() {}
                }
                PcmFileReader(inputFile).use { src ->
                    PcmFileReader(inputFile).use { mixForSink ->
                        PcmFileWriter(ktVocalsFile).use { vocalsOut ->
                            val vocals: PrimarySink = if (primaryStem == "Vocals") PrimaryStemSink(vocalsOut)
                            else VocalsSink(mixForSink, vocalsOut, s, params.compensate)
                            val sink = PrimarySink { pos, pl, pr, count ->
                                System.arraycopy(pl, 0, primary.l, pos.toInt(), count)
                                System.arraycopy(pr, 0, primary.r, pos.toInt(), count)
                                vocals.accept(pos, pl, pr, count)
                            }
                            val sep = MdxSeparator(params, model)
                            total = sep.chunkCount(n.toLong())
                            sep.demix(src, s, sink) { done, _ -> chunks = done }
                        }
                    }
                }
            }
            val totalMillis = (System.nanoTime() - t0) / 1_000_000
            val ktVocals = readStereo(ktVocalsFile)
            ktVocalsFile.delete()
            results[tag] = KtResult(primary, ktVocals)
            println("PARITY [$tag] model=${modelFile.name} nFft=${params.nFft} dimF=${params.dimF} primary=$primaryStem " +
                "overlap=${params.overlap} batch=$batch chunks=$chunks/$total ortRunMs=$runMillis totalMs=$totalMillis")
            fun expect(ok: Boolean, what: String) { if (!ok) failures.add("[$tag] $what") }
            expect(chunks == total, "chunks $chunks != $total")

            if (primaryStem == "Vocals") {
                var same = true
                for (i in 0 until n) if (ktVocals.l[i] != primary.l[i] || ktVocals.r[i] != primary.r[i]) { same = false; break }
                expect(same, "vocals-primary output must equal the primary exactly")
            }

            // Tier A (float, before output normalization).
            val mp = metrics(readStereo(File(dir, need(c.refPrimary, "ref_primary"))), primary)
            val mv = metrics(readStereo(File(dir, need(c.refVocals, "ref_vocals"))), ktVocals)
            println("PARITY [$tag] primary (Tier A float): $mp")
            println("PARITY [$tag] vocals  (Tier A float): $mv")
            expect(mp.snrDb > 30, "Tier A primary $mp")
            expect(mv.snrDb > 30, "Tier A vocals $mv")

            // Chunk-0 model input (slot 0) vs upstream.
            val refSpec = readFloats(File(dir, need(c.refSpec0, "ref_spec0")))
            val kt = spec0!!
            assertEquals("[$tag] spec0 size", refSpec.size, kt.size)
            var specDiff = 0.0
            var refMax = 0.0
            for (i in refSpec.indices) {
                specDiff = maxOf(specDiff, abs(refSpec[i] - kt[i]).toDouble())
                refMax = maxOf(refMax, abs(refSpec[i]).toDouble())
            }
            println("PARITY [$tag] chunk-0 spectrogram maxDiff=%.3e (ref max |x| = %.1f)".format(specDiff, refMax))
            expect(specDiff < 1e-3, "chunk-0 spectrogram maxDiff=$specDiff")

            // Tier 0 (CLI int16). Upstream separate(): primary_source = demix(s·mix)·peak,
            // secondary = s·mix − compensate·primary_source (mdx_separator.py L172, L195).
            val src0 = Stereo(FloatArray(n) { primary.l[it] * peak }, FloatArray(n) { primary.r[it] * peak })
            val sec0 = Stereo(FloatArray(n) { s * mix.l[it] - params.compensate * src0.l[it] },
                FloatArray(n) { s * mix.r[it] - params.compensate * src0.r[it] })
            val cliVocals = readWav16(File(dir, need(c.cliVocals, "cli_vocals")))
            val cliInst = readWav16(File(dir, need(c.cliInstrumental, "cli_instrumental")))
            val (cliPrimary, cliSecondary) = if (primaryStem == "Instrumental") cliInst to cliVocals else cliVocals to cliInst
            val m0p = metrics(cliPrimary, toCliInt16(src0))
            val m0s = metrics(cliSecondary, toCliInt16(sec0))
            println("PARITY [$tag] primary   ($primaryStem, Tier 0 CLI int16): $m0p")
            println("PARITY [$tag] secondary (Tier 0 CLI int16): $m0s")
            expect(m0p.snrDb > 30, "Tier 0 primary $m0p")
            expect(m0s.snrDb > 30, "Tier 0 secondary $m0s")

            c.compareTo?.let { other ->
                val o = results[other] ?: error("manifest: compare_to $other must come before $tag")
                val bp = metrics(o.primary, primary)
                val bv = metrics(o.vocals, ktVocals)
                println("PARITY [$tag] vs Kotlin [$other] primary: $bp")
                println("PARITY [$tag] vs Kotlin [$other] vocals:  $bv")
                expect(bp.snrDb > 80, "vs $other primary $bp")
                expect(bv.snrDb > 80, "vs $other vocals $bv")
            }
            summary.append("$tag\t%.2f\t%.2f\t%.2f\t%.2f\t%.8f\t%.3e\t%.3e\t$chunks\t$totalMillis\n".format(
                mp.snrDb, mv.snrDb, m0p.snrDb, m0s.snrDb, mp.corr, mp.maxDiff, specDiff))
        }
        File(dir, "kt_summary.tsv").writeText(summary.toString())
        println("PARITY summary:\n$summary")
        assertEquals(emptyList<String>(), failures)
    }
}
