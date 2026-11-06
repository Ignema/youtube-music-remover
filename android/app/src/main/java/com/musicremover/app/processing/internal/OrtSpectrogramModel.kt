package com.musicremover.app.processing.internal

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * ONNX Runtime model with input `"input"` and output `"output"`, both `[batch, 4, dimF, dimT]`
 * float32. Both tensors wrap direct native-order buffers owned by this class and are created once;
 * the output is a pinned output, so a run allocates no tensor memory of its own.
 *
 * [accelerator] picks the execution provider; if it cannot create a session, CPU is used and
 * [usedAccelerator] says so.
 */
class OrtSpectrogramModel(
    modelFile: File,
    private val dimF: Int,
    private val dimT: Int,
    threads: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
    override val batch: Int = 1,
    accelerator: Accelerator = Accelerator.CPU,
    /** Load limit (100 = full speed); below 100 the GPU gets finer-grained submission. Pacing is PacedModel's job. */
    private val limitPct: Int = 100,
) : SpectrogramModel {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    /** The execution provider the session was created with (CPU after a fallback). */
    val usedAccelerator: Accelerator
    private val runOptions: OrtSession.RunOptions
    private val inputTensor: OnnxTensor
    private val outTensor: OnnxTensor
    private val inputs: Map<String, OnnxTensor>
    private val pinnedOutputs: Map<String, OnnxTensor>

    override val input: FloatBuffer
    override val output: FloatBuffer

    init {
        require(batch >= 1) { "batch must be positive" }
        val size = batch * 4 * dimF * dimT
        input = allocTensorBuffer(size)
        output = allocTensorBuffer(size)
        requireTensorBuffer(input)
        requireTensorBuffer(output)

        // An accelerator that cannot take this model falls back to CPU here, so the caller never
        // mistakes it for a damaged model file. A CPU failure is still thrown.
        var used = accelerator
        val s = try {
            createSession(modelFile, threads, accelerator)
        } catch (e: OrtException) {
            if (accelerator == Accelerator.CPU) throw e
            Log.w(TAG, "${accelerator.pref} unavailable for ${modelFile.name}, using CPU", e)
            used = Accelerator.CPU
            createSession(modelFile, threads, Accelerator.CPU)
        }
        usedAccelerator = used
        var ro: OrtSession.RunOptions? = null
        var inT: OnnxTensor? = null
        var outT: OnnxTensor? = null
        try {
            checkShape(s.inputInfo["input"]?.info)
            checkShape(s.outputInfo["output"]?.info)
            ro = OrtSession.RunOptions()
            val shape = longArrayOf(batch.toLong(), 4, dimF.toLong(), dimT.toLong())
            inT = OnnxTensor.createTensor(env, input, shape)
            outT = OnnxTensor.createTensor(env, output, shape)
            check(!inT.ownsBuffer() && !outT.ownsBuffer()) { "ORT copied a tensor buffer" }
        } catch (e: Throwable) {
            outT?.close()
            inT?.close()
            ro?.close()
            s.close()
            throw e
        }
        session = s
        runOptions = ro
        inputTensor = inT
        outTensor = outT
        inputs = mapOf("input" to inT)
        pinnedOutputs = mapOf("output" to outT)
    }

    private fun createSession(modelFile: File, threads: Int, acc: Accelerator): OrtSession {
        val opts = OrtSession.SessionOptions()
        try {
            opts.setCPUArenaAllocator(false)
            opts.setMemoryPatternOptimization(false)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            // A fixed batch lets the accelerators (NNAPI needs static shapes) take the whole graph
            opts.setSymbolicDimensionValue(BATCH_DIM, batch.toLong())
            when (acc) {
                Accelerator.CPU -> opts.setIntraOpNumThreads(threads)
                Accelerator.XNNPACK -> {
                    // XNNPACK runs its own pool; ORT's pool stays at 1 and does not spin (ORT docs)
                    opts.setIntraOpNumThreads(1)
                    opts.addConfigEntry("session.intra_op.allow_spinning", "0")
                    opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                }
                Accelerator.NNAPI -> {
                    // Nodes NNAPI cannot run go to ORT's CPU kernels instead of the slow NNAPI CPU reference
                    opts.setIntraOpNumThreads(threads)
                    opts.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED))
                }
                Accelerator.WEBGPU -> {
                    opts.setIntraOpNumThreads(threads)
                    if (limitPct < 100) {
                        // Submit each GPU step on its own, so the screen's frames get the GPU between
                        // steps instead of waiting behind a long queue (range 1-4096, ORT webgpu options)
                        opts.addConfigEntry("ep.webgpuexecutionprovider.maxNumPendingDispatches", "1")
                        opts.addConfigEntry("ep.webgpuexecutionprovider.powerPreference", "low-power")
                    }
                    opts.addWebGPU(emptyMap())
                }
            }
            return env.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
    }

    private fun checkShape(info: Any?) {
        val ok = info is TensorInfo && info.type == OnnxJavaType.FLOAT && info.shape.let { sh ->
            sh.size == 4 && (sh[0] == -1L || sh[0] == batch.toLong()) && sh[1] == 4L && sh[2] == dimF.toLong() && sh[3] == dimT.toLong()
        }
        if (!ok) throw IllegalStateException("Model does not match its metadata")
    }

    override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
        require(input === this.input && output === this.output) { "run must use the model's own buffers" }
        cancellableBlocking(onCancel = {
            try { runOptions.setTerminate(true) } catch (_: IllegalStateException) {}
        }) {
            // The output is pinned, so the requested-output set must be empty: ORT 1.31.1 rejects a
            // name that is both requested and pinned.
            session.run(inputs, emptySet<String>(), pinnedOutputs, runOptions).close()
        }
    }

    /** Closes tensors, run options and session (not the shared environment). Call after every run returned. */
    override fun close() {
        inputTensor.close()
        outTensor.close()
        runOptions.close()
        session.close()
    }

    companion object {
        private const val TAG = "MuremInternal"

        /** Symbolic batch dimension name of the UVR MDX-Net exports. */
        private const val BATCH_DIM = "batch_size"

        fun allocTensorBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** ORT wraps a buffer without copying only when it is direct and in native order. */
        fun requireTensorBuffer(buf: FloatBuffer) {
            check(buf.isDirect && buf.order() == ByteOrder.nativeOrder()) { "Tensor buffer must be direct and native-order" }
        }
    }
}
