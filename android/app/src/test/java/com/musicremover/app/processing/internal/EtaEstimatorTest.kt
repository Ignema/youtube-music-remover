package com.musicremover.app.processing.internal

import com.musicremover.app.processing.EtaText
import com.musicremover.app.processing.etaLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtaEstimatorTest {
    @Test
    fun nullBeforeTheFirstChunkWithoutAPrior() {
        val eta = EtaEstimator(10)
        assertNull(eta.onChunk(0, 0L))
        assertNull(eta.prior(null))
        assertNull(eta.prior(0L))
    }

    @Test
    fun priorUsesTheMeasuredSpeed() {
        assertEquals(50, EtaEstimator(10).prior(5_000L))
        assertEquals(1, EtaEstimator(3).prior(500L))
    }

    @Test
    fun linearEstimateRoundsUp() {
        val eta = EtaEstimator(10)
        assertEquals(36, eta.onChunk(1, 4_000L)) // 4 s per chunk, 9 left
        assertEquals(24, eta.onChunk(3, 10_000L)) // 3.33 s per chunk, 7 left = 23.3 s
    }

    @Test
    fun lastChunkLeavesZero() {
        assertEquals(0, EtaEstimator(4).onChunk(4, 20_000L))
    }

    @Test
    fun labelThresholds() {
        val fake: (EtaText, Int) -> String = { kind, n -> "$kind:$n" }
        assertEquals("UNDER_A_MINUTE:0", etaLabel(0, fake))
        assertEquals("UNDER_A_MINUTE:0", etaLabel(44, fake))
        assertEquals("ONE_MINUTE:1", etaLabel(45, fake))
        assertEquals("ONE_MINUTE:1", etaLabel(89, fake))
        assertEquals("MINUTES:2", etaLabel(90, fake))
        assertEquals("MINUTES:2", etaLabel(149, fake))
        assertEquals("MINUTES:3", etaLabel(150, fake))
        assertEquals("MINUTES:12", etaLabel(720, fake))
    }
}
