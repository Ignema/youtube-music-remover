package com.musicremover.app.processing.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    @Test
    fun twoFilesIncludingStickyCase() {
        val p = DownloadProgress(expectedFiles = 2)
        assertNull(p.onLine("[youtube] jNQXAC9IVRw: Downloading webpage"))
        assertEquals(0, p.onLine("[download] Destination: /w/dl/137.mp4"))
        assertEquals(25, p.onLine("[download]  50.0% of    1.00MiB at  1.00MiB/s ETA 00:01"))
        assertEquals(50, p.onLine("[download] 100% of    1.00MiB in 00:00:01 at 1.00MiB/s"))
        // The library would repeat 100.0 here; this line must not pin file 2
        assertNull(p.onLine("[info] Writing something"))
        // The second Destination starts file 2 at 0%: overall stays 50
        assertEquals(50, p.onLine("[download] Destination: /w/dl/140.m4a"))
        assertEquals(60, p.onLine("[download]  20.0% of ~  500.00KiB at  1.00MiB/s ETA 00:01 (frag 1/5)"))
        assertEquals(100, p.onLine("[download] 100% of  500.00KiB in 00:00:05 at 100.00KiB/s"))
    }

    @Test
    fun alreadyDownloadedCountsAsFinishedFile() {
        val p = DownloadProgress(expectedFiles = 2)
        assertEquals(50, p.onLine("[download] /w/dl/137.mp4 has already been downloaded"))
        assertEquals(50, p.onLine("[download] Destination: /w/dl/140.m4a"))
        assertEquals(75, p.onLine("[download]  50.0% of 1.00MiB at 1.00MiB/s ETA 00:01"))
    }

    @Test
    fun finalLineWithoutEtaIsParsed() {
        val p = DownloadProgress(expectedFiles = 1)
        assertEquals(0, p.onLine("[download] Destination: /w/dl/140.m4a"))
        assertEquals(100, p.onLine("[download] 100% of    1.23MiB in 00:00:05 at 251.64KiB/s"))
    }

    @Test
    fun neverDecreasesAndStaysInRange() {
        val p = DownloadProgress(expectedFiles = 1)
        p.onLine("[download] Destination: a")
        assertEquals(80, p.onLine("[download]  80.0% of 1MiB"))
        assertEquals(80, p.onLine("[download]  10.0% of 1MiB"))
        // More files than expected: clamped to the last one
        val q = DownloadProgress(expectedFiles = 1)
        q.onLine("[download] Destination: a")
        q.onLine("[download] 100% of 1MiB")
        assertEquals(100, q.onLine("[download] Destination: b"))
        assertEquals(100, q.onLine("[download]  30.0% of 1MiB"))
        val v = q.onLine("[download] 100% of 1MiB")
        assertTrue(v != null && v in 0..100)
    }
}
