package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JobEstimateTest {
    private fun spec(name: String, label: String, cost: Double, nFft: Int = 6144, notice: Boolean = false) = ModelSpec(
        name, "https://example.com/$name", 1L, "0".repeat(64), nFft, 2048, 256, 1024,
        1.0, "Vocals", 256, 0.25, label, notice, cost,
    )

    private val hq3 = spec("hq3.onnx", "hq3", 1.0)
    private val mdx3 = spec("mdx3.onnx", "mdx3", 0.34)
    private val kara = spec("kara2.onnx", "kara2", 0.64, nFft = 5120)
    private val kim = spec("kim2.onnx", "kim2", 0.99, notice = true)
    private val all = listOf(hq3, mdx3, kara, kim)
    private val overlap = { _: ModelSpec -> 0.25 }

    @Test
    fun noTimingMeansNoEstimate() {
        assertNull(JobEstimate.factor(hq3, all, emptyMap(), 0.25))
        assertNull(JobEstimate.lighter(hq3, all, emptyMap(), overlap))
    }

    @Test
    fun anUntimedModelIsScaledFromATimedOne() {
        assertEquals(3400L, JobEstimate.msPerChunk(mdx3, all, mapOf(hq3.name to 10_000L)))
        // Its own measurement wins
        assertEquals(1234L, JobEstimate.msPerChunk(mdx3, all, mapOf(hq3.name to 10_000L, mdx3.name to 1234L)))
    }

    @Test
    fun totalFollowsTheChunkCount() {
        val ms = 5_000L
        val fourMin = 4L * 60_000
        val chunks = PerformanceSettings.chunkCount(fourMin * 44_100 / 1000, 0.25, 6144)
        assertEquals(chunks * ms, JobEstimate.totalMs(hq3, all, mapOf(hq3.name to ms), 0.25, fourMin))
    }

    @Test
    fun slowModelGetsTheLightestRealAlternative() {
        // A chunk adds about 4.4 s of audio at 0.25 overlap, so 20 s per chunk is about 4.5x the track
        val tip = JobEstimate.lighter(hq3, all, mapOf(hq3.name to 20_000L), overlap)
        assertNotNull(tip)
        assertEquals(mdx3.name, tip!!.spec.name) // not the lead-vocal model, not the notice model
        assertEquals(1 / 0.34, tip.speedup, 0.05)
    }

    @Test
    fun fastPhoneOrLightModelGetsNoTip() {
        assertNull(JobEstimate.lighter(hq3, all, mapOf(hq3.name to 8_000L), overlap)) // about 1.8x: fine
        assertNull(JobEstimate.lighter(hq3, all, mapOf(hq3.name to 500L), overlap))
        assertNull(JobEstimate.lighter(mdx3, all, mapOf(mdx3.name to 20_000L), overlap))
    }
}
