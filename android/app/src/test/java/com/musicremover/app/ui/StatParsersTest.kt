package com.musicremover.app.ui

import com.musicremover.app.processing.internal.PacedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatParsersTest {
    @Test
    fun cpuTicksSkipsANameWithSpacesAndParens() {
        val stat = "1234 (com.music (x) app) S 1 2 3 4 5 6 7 8 9 10 150 25 0 0 20 0 30 0 100 200"
        assertEquals(175L, StatParsers.cpuTicks(stat))
        assertNull(StatParsers.cpuTicks("garbage"))
    }

    @Test
    fun rssAndIo() {
        assertEquals(523_416L, StatParsers.rssKb("Name:\tx\nVmPeak:\t 1 kB\nVmRSS:\t  523416 kB\nThreads: 9\n"))
        assertNull(StatParsers.rssKb("Name: x\n"))
        val io = "rchar: 10\nwchar: 20\nsyscr: 1\nsyscw: 2\nread_bytes: 4096\nwrite_bytes: 8192\ncancelled_write_bytes: 0\n"
        assertEquals(4096L to 8192L, StatParsers.ioBytes(io))
        assertNull(StatParsers.ioBytes("rchar: 10\n"))
    }

    @Test
    fun gpuBusyFormats() {
        assertEquals(37f, StatParsers.gpuBusy("37\n"))
        assertEquals(37f, StatParsers.gpuBusy("37 %"))
        assertEquals(25f, StatParsers.gpuBusy("250 1000\n"))
        assertEquals(0f, StatParsers.gpuBusy("0 0"))
        assertNull(StatParsers.gpuBusy(""))
    }

    @Test
    fun pausesAreHalfTheRunAndCapped() {
        assertEquals(1000L, PacedModel.pauseMs(2000L, 0.5))
        assertEquals(0L, PacedModel.pauseMs(-5L, 0.5))
        assertEquals(PacedModel.MAX_PAUSE_MS, PacedModel.pauseMs(60_000L, 0.5))
    }
}
