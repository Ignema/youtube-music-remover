package com.musicremover.app.processing.internal

import ai.onnxruntime.OrtException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class FallbackModelTest {
    private class Fake(
        override val usedAccelerator: Accelerator,
        override val input: FloatBuffer = FloatBuffer.allocate(8),
        override val output: FloatBuffer = FloatBuffer.allocate(8),
        val failRuns: Boolean = false,
    ) : AcceleratedModel {
        var runs = 0
        var closed = false
        override suspend fun run(input: FloatBuffer, output: FloatBuffer) {
            runs++
            if (failRuns) throw OrtException("driver failed")
            output.put(0, input.get(0) * 2)
        }
        override fun close() { closed = true }
    }

    @Test
    fun accelerated_failure_reruns_the_chunk_on_cpu_with_the_same_buffers() = runBlocking {
        val nnapi = Fake(Accelerator.NNAPI, failRuns = true)
        var cpu: Fake? = null
        var notified = 0
        val m = FallbackModel(nnapi, { (i, o) -> Fake(Accelerator.CPU, i, o).also { cpu = it } }, { notified++ })
        m.input.put(0, 21f)
        m.run(m.input, m.output)
        assertTrue(m.fellBack)
        assertTrue(nnapi.closed)
        assertSame(nnapi.input, cpu!!.input)
        assertEquals(42f, m.output.get(0))
        assertEquals(Accelerator.CPU, m.usedAccelerator)
        assertEquals(1, notified)
        m.run(m.input, m.output)
        assertEquals(1, nnapi.runs)
        assertEquals(2, cpu!!.runs)
        m.close()
        assertTrue(cpu!!.closed)
    }

    @Test
    fun cpu_failure_is_thrown() {
        val m = FallbackModel(Fake(Accelerator.CPU, failRuns = true), { error("no second CPU session") })
        assertThrows(OrtException::class.java) { runBlocking { m.run(m.input, m.output) } }
        assertFalse(m.fellBack)
    }

    @Test
    fun second_failure_after_fallback_is_thrown() {
        val m = FallbackModel(Fake(Accelerator.WEBGPU, failRuns = true), { (i, o) -> Fake(Accelerator.CPU, i, o, failRuns = true) })
        assertThrows(OrtException::class.java) { runBlocking { m.run(m.input, m.output) } }
        assertTrue(m.fellBack)
    }
}
