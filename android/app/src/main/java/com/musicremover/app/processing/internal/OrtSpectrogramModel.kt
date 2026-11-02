package com.musicremover.app.processing.internal

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ONNX Runtime (CPU) model with input `"input"` and output `"output"`, both `[1, 4, dimF, dimT]`
 * float32. Both tensors wrap direct native-order buffers owned by this class and are created once;
 * the output is a pinned output, so a run allocates no tensor memory of its own.
 */
class OrtSpectrogramModel(modelFile: File, private val dimF: Int, private val dimT: Int) : SpectrogramModel {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val runOptions: OrtSession.RunOptions
    private val inputTensor: OnnxTensor
    private val outTensor: OnnxTensor
    private val inputs: Map<String, OnnxTensor>
    private val pinnedOutputs: Map<String, OnnxTensor>

    override val input: FloatBuffer
    override val output: FloatBuffer

    init {
        val size = 4 * dimF * dimT
        input = allocTensorBuffer(size)
        output = allocTensorBuffer(size)
        requireTensorBuffer(input)
        requireTensorBuffer(output)

        val opts = OrtSession.SessionOptions()
        val s = try {
            opts.setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
            opts.setCPUArenaAllocator(false)
            opts.setMemoryPatternOptimization(false)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
            env.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }
        var ro: OrtSession.RunOptions? = null
        var inT: OnnxTensor? = null
        var outT: OnnxTensor? = null
        try {
            checkShape(s.inputInfo["input"]?.info)
            checkShape(s.outputInfo["output"]?.info)
            ro = OrtSession.RunOptions()
            val shape = longArrayOf(1, 4, dimF.toLong(), dimT.toLong())
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

    private fun checkShape(info: Any?) {
        val ok = info is TensorInfo && info.type == OnnxJavaType.FLOAT && info.shape.let { sh ->
            sh.size == 4 && (sh[0] == -1L || sh[0] == 1L) && sh[1] == 4L && sh[2] == dimF.toLong() && sh[3] == dimT.toLong()
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
        fun allocTensorBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(4 * n).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** ORT wraps a buffer without copying only when it is direct and in native order. */
        fun requireTensorBuffer(buf: FloatBuffer) {
            check(buf.isDirect && buf.order() == ByteOrder.nativeOrder()) { "Tensor buffer must be direct and native-order" }
        }
    }
}
