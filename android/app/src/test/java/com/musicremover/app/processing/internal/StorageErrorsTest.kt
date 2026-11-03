package com.musicremover.app.processing.internal

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageErrorsTest {
    @Test
    fun nestedEnospcIsNoSpace() {
        val e = RuntimeException("Muxer error", IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(StorageErrors.isNoSpace(e))
    }

    @Test
    fun matchIgnoresCase() {
        assertTrue(StorageErrors.isNoSpace(IOException("no space left on device")))
        assertTrue(StorageErrors.isNoSpaceText("errno enospc"))
    }

    @Test
    fun plainMuxerErrorIsNotNoSpace() {
        assertFalse(StorageErrors.isNoSpace(RuntimeException("Muxer error")))
        assertFalse(StorageErrors.isNoSpaceText("Muxer error"))
    }

    @Test
    fun selfReferencingCauseTerminates() {
        val loop = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(StorageErrors.isNoSpace(loop))
    }

    @Test
    fun twoElementCauseLoopTerminates() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(StorageErrors.isNoSpace(a))
    }

    @Test
    fun noSpaceBeyondDepthLimitIsIgnored() {
        var e: Throwable = IOException("ENOSPC")
        repeat(10) { e = RuntimeException("wrap $it", e) }
        assertFalse(StorageErrors.isNoSpace(e))
    }

    @Test
    fun lowUsableSpaceIsLikelyNoSpace() {
        val e = IllegalStateException("Failed to write sample")
        assertTrue(StorageErrors.isLikelyNoSpace(e, 5L * 1024 * 1024))
        assertFalse(StorageErrors.isLikelyNoSpace(e, 500L * 1024 * 1024))
    }
}
