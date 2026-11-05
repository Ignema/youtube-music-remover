package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceSettingsTest {
    private val bigTotal = 8_000_000_000L
    private val bigAvail = 4_000_000_000L

    private fun resolve(
        threads: Int = 0,
        quality: String? = "balanced",
        batch: Int = 1,
        cores: Int = 8,
        total: Long = bigTotal,
        avail: Long = bigAvail,
    ) = PerformanceSettings.resolve(threads, quality, batch, cores, total, avail, specOverlap = 0.25)

    @Test
    fun autoIsMinOfFourAndCores() {
        assertEquals(4, resolve(threads = 0, cores = 8).threads)
        assertEquals(2, resolve(threads = 0, cores = 2).threads)
        assertEquals(1, resolve(threads = 0, cores = 1).threads)
    }

    @Test
    fun threadsAreCoercedToMinOfCoresAndEight() {
        assertEquals(6, resolve(threads = 6, cores = 8).threads)
        assertEquals(8, resolve(threads = 12, cores = 16).threads)
        assertEquals(4, resolve(threads = 6, cores = 4).threads)
        assertEquals(1, resolve(threads = 1, cores = 8).threads)
        assertEquals(8, PerformanceSettings.maxThreads(10))
        assertEquals(3, PerformanceSettings.maxThreads(3))
    }

    @Test
    fun invalidThreadsAreAuto() {
        assertEquals(4, resolve(threads = -1, cores = 8).threads)
        assertEquals(4, resolve(threads = 65, cores = 8).threads)
    }

    @Test
    fun presetToOverlap() {
        assertEquals(0.10, resolve(quality = "fast").overlap, 0.0)
        assertEquals(0.25, resolve(quality = "balanced").overlap, 0.0)
        assertEquals(0.50, resolve(quality = "best").overlap, 0.0)
    }

    @Test
    fun invalidPresetIsBalanced() {
        assertEquals(0.25, resolve(quality = "turbo").overlap, 0.0)
        assertEquals(0.25, resolve(quality = null).overlap, 0.0)
        assertEquals(0.25, resolve(quality = "").overlap, 0.0)
    }

    @Test
    fun batchTwoAllowedAtTheThreshold() {
        val c = resolve(batch = 2, total = 5_400_000_000L, avail = 2_600_000_000L)
        assertEquals(2, c.batch)
        assertFalse(c.batchReduced)
    }

    @Test
    fun batchTwoRefusedBelowTotalMem() {
        val c = resolve(batch = 2, total = 5_399_999_999L, avail = bigAvail)
        assertEquals(1, c.batch)
        assertTrue(c.batchReduced)
    }

    @Test
    fun batchTwoRefusedBelowAvailMem() {
        val c = resolve(batch = 2, total = bigTotal, avail = 2_599_999_999L)
        assertEquals(1, c.batch)
        assertTrue(c.batchReduced)
    }

    @Test
    fun batchOneIsNeverReduced() {
        val c = resolve(batch = 1, total = 1L, avail = 1L)
        assertEquals(1, c.batch)
        assertFalse(c.batchReduced)
    }

    @Test
    fun invalidBatchIsOne() {
        for (b in listOf(0, 3, -2, 4)) {
            val c = resolve(batch = b)
            assertEquals(1, c.batch)
            assertFalse(c.batchReduced)
        }
    }

    @Test
    fun chunkCountMatchesMdxSeparator() {
        for (nFft in listOf(5120, 6144, 7680)) {
            for (overlap in listOf(0.10, 0.25, 0.50)) {
                val params = MdxParams(nFft, 2048, 256, 1024, 1f, overlap)
                val sep = MdxSeparator(params, IdentityModel(2048, 256))
                for (n in listOf(1L, 441_000L, 4L * 60 * 44_100, 10_000_000L)) {
                    assertEquals("nFft $nFft overlap $overlap n $n", sep.chunkCount(n), PerformanceSettings.chunkCount(n, overlap, nFft))
                }
            }
        }
    }

    @Test
    fun presetsScaleTheChunkCount() {
        val n = 4L * 60 * 44_100
        val balanced = PerformanceSettings.chunkCount(n, 0.25).toDouble()
        assertEquals(0.83, PerformanceSettings.chunkCount(n, 0.10) / balanced, 0.05)
        assertEquals(1.5, PerformanceSettings.chunkCount(n, 0.50) / balanced, 0.05)
    }

    @Test
    fun emaAndKey() {
        assertEquals(1000L, PerformanceSettings.ema(null, 1000L))
        assertEquals(1300L, PerformanceSettings.ema(1000L, 2000L))
        assertEquals("perf_ms:UVR-MDX-NET-Inst_HQ_3.onnx:4:2", PerformanceSettings.speedKey("UVR-MDX-NET-Inst_HQ_3.onnx", 4, 2))
        // CPU keeps the old key so measurements from before 4.2 stay valid
        assertEquals("perf_ms:m.onnx:4:1", PerformanceSettings.speedKey("m.onnx", 4, 1, Accelerator.CPU))
        assertEquals("perf_ms:m.onnx:4:1:nnapi", PerformanceSettings.speedKey("m.onnx", 4, 1, Accelerator.NNAPI))
        assertEquals("perf_ms:m.onnx:4:1:xnnpack", PerformanceSettings.speedKey("m.onnx", 4, 1, Accelerator.XNNPACK))
    }

    @Test
    fun acceleratorPrefs() {
        assertEquals(Accelerator.CPU, Accelerator.resolve(null, 35))
        assertEquals(Accelerator.CPU, Accelerator.resolve("gpu", 35))
        assertEquals(Accelerator.XNNPACK, Accelerator.resolve("xnnpack", 26))
        assertEquals(Accelerator.NNAPI, Accelerator.resolve("nnapi", 27))
        assertEquals(Accelerator.CPU, Accelerator.resolve("nnapi", 26))
        assertEquals(Accelerator.WEBGPU, Accelerator.resolve("webgpu", 26))
    }

    /** Only its geometry matters for chunkCount. */
    private class IdentityModel(dimF: Int, dimT: Int) : SpectrogramModel {
        override val input: java.nio.FloatBuffer = java.nio.FloatBuffer.allocate(4 * dimF * dimT)
        override val output: java.nio.FloatBuffer = java.nio.FloatBuffer.allocate(4 * dimF * dimT)
        override suspend fun run(input: java.nio.FloatBuffer, output: java.nio.FloatBuffer) = Unit
        override fun close() = Unit
    }
}
