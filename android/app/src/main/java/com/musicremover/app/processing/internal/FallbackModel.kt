package com.musicremover.app.processing.internal

import ai.onnxruntime.OrtException
import android.util.Log
import java.nio.FloatBuffer

/** A model that knows which execution provider it runs on. */
interface AcceleratedModel : SpectrogramModel {
    val usedAccelerator: Accelerator
}

/**
 * Runs on [primary] (an accelerated session) and, the first time a run fails with an ORT error,
 * closes it, creates a CPU session with [makeCpu] on the same buffers and runs the same chunk again.
 * Some NNAPI drivers accept a model and then fail at run time (seen on an Exynos 2200), so this keeps
 * the job going instead of failing it. A CPU failure, or a second failure, is thrown as before.
 */
class FallbackModel(
    private val primary: AcceleratedModel,
    private val makeCpu: (buffers: Pair<FloatBuffer, FloatBuffer>) -> AcceleratedModel,
    private val onFallback: (OrtException) -> Unit = {},
) : SpectrogramModel {
    private var active: AcceleratedModel = primary

    /** True once the job moved to CPU. */
    var fellBack = false
        private set

    override val batch: Int get() = primary.batch
    override val input: FloatBuffer get() = primary.input
    override val output: FloatBuffer get() = primary.output

    /** What the current session runs on. */
    val usedAccelerator: Accelerator get() = active.usedAccelerator

    override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
        try {
            active.run(input, output)
        } catch (e: OrtException) {
            if (fellBack || active.usedAccelerator == Accelerator.CPU) throw e
            runCatching { Log.w(TAG, "${active.usedAccelerator.pref} failed during a run, continuing on CPU", e) }
            fellBack = true
            primary.close()
            // The input buffer still holds this chunk (ORT does not write to inputs)
            active = makeCpu(primary.input to primary.output)
            onFallback(e)
            active.run(input, output)
        }
    }

    override fun close() {
        active.close()
    }

    private companion object {
        const val TAG = "MuremInternal"
    }
}
