package com.musicremover.app.processing.internal

import android.os.SystemClock
import kotlinx.coroutines.delay
import java.nio.FloatBuffer

/**
 * Waits after each run for [ratio] times as long as the run took, so the GPU (or CPU) is free for
 * the screen and the phone cools in between. The job takes about (1 + [ratio]) times as long.
 * The wait is a cancellable delay, so Cancel still stops the job at once.
 */
class PacedModel(
    private val inner: SpectrogramModel,
    private val ratio: Double = DEFAULT_RATIO,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : SpectrogramModel by inner {
    init {
        require(ratio >= 0) { "ratio must not be negative" }
    }

    override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
        val t0 = clock()
        inner.run(input, output)
        val pause = pauseMs(clock() - t0, ratio)
        if (pause > 0) delay(pause)
    }

    companion object {
        /** Half the run time: about 1.5× the total, the GPU is idle a third of the time. */
        const val DEFAULT_RATIO = 0.5

        /** Longest single pause, so one very slow chunk does not stall the job for minutes. */
        const val MAX_PAUSE_MS = 10_000L

        /** Pure. */
        fun pauseMs(runMs: Long, ratio: Double): Long =
            (runMs.coerceAtLeast(0) * ratio).toLong().coerceAtMost(MAX_PAUSE_MS)
    }
}
