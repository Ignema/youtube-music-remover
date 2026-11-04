package com.musicremover.app.processing.internal

import kotlin.math.roundToLong

/** What one on-device job runs with, resolved from the Performance prefs and live memory. */
data class RunConfig(
    val threads: Int,
    val overlap: Double,
    val batch: Int,
    /** Batch 2 was asked for but refused by the memory rule. */
    val batchReduced: Boolean,
)

/** Pure mapping of the Performance prefs (design §1d). */
object PerformanceSettings {
    const val QUALITY_FAST = "fast"
    const val QUALITY_BALANCED = "balanced"
    const val QUALITY_BEST = "best"
    val QUALITIES = setOf(QUALITY_FAST, QUALITY_BALANCED, QUALITY_BEST)

    const val OVERLAP_FAST = 0.10
    const val OVERLAP_BEST = 0.50

    /** Same reasoning as InternalCapability.MIN_TOTAL_MEM: a "6 GB" phone reports about 5.5e9. */
    const val BATCH2_MIN_TOTAL_MEM = 5_400_000_000L
    /** The on-device preflight minimum plus one more segment's activations (1.1 GB, Phase 2 §1.5). */
    const val BATCH2_MIN_AVAIL_MEM = 1_500_000_000L + 1_100_000_000L
    /** Largest thread count accepted in the work Data before coercion to the device maximum. */
    const val MAX_THREADS_INPUT = 64

    /** EMA weight of the newest ms-per-chunk measurement. */
    const val EMA_ALPHA = 0.3

    // MDX chunk geometry shared by every catalog model (hop 1024, dim_t 256)
    private const val HOP = 1024
    private const val DIM_T = 256
    private const val DEFAULT_N_FFT = 6144

    /** Auto = today's value. */
    fun autoThreads(cores: Int): Int = minOf(4, cores.coerceAtLeast(1))

    fun maxThreads(cores: Int): Int = minOf(cores.coerceAtLeast(1), 8)

    /** Auto (0) and any value outside 0..[MAX_THREADS_INPUT] give [autoThreads]; others are coerced to [maxThreads]. */
    fun threads(threadsPref: Int, cores: Int): Int = when (threadsPref) {
        in 1..MAX_THREADS_INPUT -> threadsPref.coerceAtMost(maxThreads(cores))
        else -> autoThreads(cores)
    }

    /** fast 0.10 / balanced [specOverlap] / best 0.50; anything else is balanced. */
    fun overlap(qualityPref: String?, specOverlap: Double): Double = when (qualityPref) {
        QUALITY_FAST -> OVERLAP_FAST
        QUALITY_BEST -> OVERLAP_BEST
        else -> specOverlap
    }

    fun resolve(
        threadsPref: Int,
        qualityPref: String?,
        batchPref: Int,
        cores: Int,
        totalMem: Long,
        availMem: Long,
        specOverlap: Double,
    ): RunConfig {
        val wants2 = batchPref == 2
        val allowed2 = totalMem >= BATCH2_MIN_TOTAL_MEM && availMem >= BATCH2_MIN_AVAIL_MEM
        val batch = if (wants2 && allowed2) 2 else 1
        return RunConfig(
            threads = threads(threadsPref, cores),
            overlap = overlap(qualityPref, specOverlap),
            batch = batch,
            batchReduced = wants2 && !allowed2,
        )
    }

    /**
     * Chunks MdxSeparator runs for [samples] frames at [overlap] (same formula as
     * MdxSeparator.chunkCount, for hop 1024 and dim_t 256).
     */
    fun chunkCount(samples: Long, overlap: Double, nFft: Int = DEFAULT_N_FFT): Int {
        val chunk = HOP * (DIM_T - 1)
        val trim = nFft / 2
        val genSize = chunk - 2 * trim
        val step = ((1 - overlap) * chunk).toInt()
        require(genSize > 0 && step > 0)
        val pad = genSize + trim - (samples % genSize)
        val padded = trim + samples + pad
        return ((padded + step - 1) / step).toInt()
    }

    /** Prefs `app` key of the measured ms per chunk. */
    fun speedKey(model: String, threads: Int, batch: Int): String = "perf_ms:$model:$threads:$batch"

    /** Exponential moving average; the first measurement is taken as is. */
    fun ema(previous: Long?, sample: Long): Long =
        if (previous == null || previous <= 0) sample
        else (EMA_ALPHA * sample + (1 - EMA_ALPHA) * previous).roundToLong()
}
