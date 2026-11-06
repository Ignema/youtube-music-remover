package com.musicremover.app.processing.internal

import kotlin.math.roundToLong

/**
 * Time estimates before a job starts, from the speed this phone measured (ms per chunk, per model).
 * A model that was never timed is estimated from a timed one with [ModelSpec.relativeCost]. Pure.
 */
object JobEstimate {
    private const val SAMPLE_RATE = 44_100L

    /** A job slower than this many times the track length counts as slow and gets a lighter-model tip. */
    const val SLOW_FACTOR = 3.0

    /** The tip only offers a model that is at least this much faster. */
    private const val MIN_SPEEDUP = 1.6

    /** Models that are not a lighter version of the same job (lead vocal only, or a license notice). */
    private val NOT_ALTERNATIVES = setOf("kara2")

    /** ms per chunk for [spec]: measured, else scaled from the closest-cost measured model; null if none. */
    fun msPerChunk(spec: ModelSpec, specs: List<ModelSpec>, measured: Map<String, Long?>): Long? {
        measured[spec.name]?.takeIf { it > 0 }?.let { return it }
        val ref = specs
            .filter { (measured[it.name] ?: 0) > 0 }
            .minByOrNull { kotlin.math.abs(it.relativeCost - spec.relativeCost) } ?: return null
        return (measured.getValue(ref.name)!! * spec.relativeCost / ref.relativeCost).roundToLong()
    }

    /** Estimated ms to separate [durationMs] of audio, or null without a speed. */
    fun totalMs(spec: ModelSpec, specs: List<ModelSpec>, measured: Map<String, Long?>, overlap: Double, durationMs: Long): Long? {
        val ms = msPerChunk(spec, specs, measured) ?: return null
        val samples = (durationMs.coerceAtLeast(1_000) * SAMPLE_RATE / 1000)
        return PerformanceSettings.chunkCount(samples, overlap, spec.nFft) * ms
    }

    /** Minutes of processing per minute of audio (e.g. 5.0 = a 4-minute song takes 20 minutes). */
    fun factor(spec: ModelSpec, specs: List<ModelSpec>, measured: Map<String, Long?>, overlap: Double): Double? =
        totalMs(spec, specs, measured, overlap, 10L * 60_000)?.let { it / (10.0 * 60_000) }

    /** A clearly faster model to suggest when [current] is slow here, or null. */
    fun lighter(current: ModelSpec, specs: List<ModelSpec>, measured: Map<String, Long?>, overlap: (ModelSpec) -> Double): Suggestion? {
        val now = factor(current, specs, measured, overlap(current)) ?: return null
        if (now < SLOW_FACTOR) return null
        val best = specs
            .filter { it.name != current.name && !it.licenseNotice && it.label !in NOT_ALTERNATIVES }
            .filter { current.relativeCost / it.relativeCost >= MIN_SPEEDUP }
            .minByOrNull { it.relativeCost } ?: return null
        val then = factor(best, specs, measured, overlap(best)) ?: return null
        return Suggestion(best, now / then)
    }

    data class Suggestion(val spec: ModelSpec, val speedup: Double)
}
