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

    @Test
    fun logTextWithoutThrowableIsLikelyNoSpace() {
        val tail = "[mp3 @ 0x7f] Error writing trailer: No space left on device"
        assertTrue(StorageErrors.isLikelyNoSpace(null, tail, 500L * 1024 * 1024))
    }

    @Test
    fun benignTextAndAmpleSpaceIsNotNoSpace() {
        assertFalse(StorageErrors.isLikelyNoSpace(null, null, 500L * 1024 * 1024))
        assertFalse(StorageErrors.isLikelyNoSpace(null, "pipe:0: Invalid data found", 500L * 1024 * 1024))
        assertFalse(StorageErrors.isLikelyNoSpace(IOException("Broken pipe"), "", 500L * 1024 * 1024))
    }

    @Test
    fun lowSpaceWithoutThrowableOrTextIsLikelyNoSpace() {
        assertTrue(StorageErrors.isLikelyNoSpace(null, null, 5L * 1024 * 1024))
    }

    @Test
    fun enospcThrowableWithBenignTextIsLikelyNoSpace() {
        assertTrue(StorageErrors.isLikelyNoSpace(IOException("write failed: ENOSPC"), "", 500L * 1024 * 1024))
    }
}
