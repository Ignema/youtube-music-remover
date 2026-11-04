package com.musicremover.app.processing.internal

import kotlin.math.ceil

/** Remaining separation time from the chunks done so far (design §1d.4). Pure. */
class EtaEstimator(private val totalChunks: Int) {
    /** Seconds left after [done] chunks took [elapsedMs]; null before the first chunk. */
    fun onChunk(done: Int, elapsedMs: Long): Int? {
        if (done < 1 || totalChunks <= 0) return null
        val left = (totalChunks - done).coerceAtLeast(0)
        return ceil(elapsedMs.toDouble() / done * left / 1000.0).toInt()
    }

    /** Estimate before the first chunk from an earlier measurement of [msPerChunk]; null without one. */
    fun prior(msPerChunk: Long?): Int? {
        if (msPerChunk == null || msPerChunk <= 0 || totalChunks <= 0) return null
        return (msPerChunk * totalChunks / 1000).toInt()
    }
}
