package com.musicremover.app.processing.internal

import android.os.SystemClock
import kotlinx.coroutines.delay
import java.nio.FloatBuffer

/**
 * Holds the average load of the accelerator at about [limitPct] %: after each run it waits so that
 * run / (run + wait) = limitPct / 100. The job takes about 100 / [limitPct] times as long.
 * The wait is a cancellable delay, so Cancel still stops the job at once.
 */
class PacedModel(
    private val inner: SpectrogramModel,
    private val limitPct: Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : SpectrogramModel by inner {
    init {
        require(limitPct in 1..100) { "limit must be 1..100" }
    }

    override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
        val t0 = clock()
        inner.run(input, output)
        val pause = pauseMs(clock() - t0, limitPct)
        if (pause > 0) delay(pause)
    }

    companion object {
        /** Longest single pause, so one very slow chunk does not stall the job for minutes. */
        const val MAX_PAUSE_MS = 15_000L

        /** Pure. 100 % = no pause; 50 % = as long as the run; 75 % = a third of it. */
        fun pauseMs(runMs: Long, limitPct: Int): Long {
            if (limitPct >= 100 || runMs <= 0) return 0
            return (runMs * (100.0 / limitPct - 1)).toLong().coerceAtMost(MAX_PAUSE_MS)
        }
    }
}
