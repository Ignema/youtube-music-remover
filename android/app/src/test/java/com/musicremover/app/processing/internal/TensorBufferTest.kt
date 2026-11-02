package com.musicremover.app.processing.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TensorBufferTest {
    @Test
    fun allocatedBufferIsDirectNativeOrder() {
        val buf = OrtSpectrogramModel.allocTensorBuffer(1000)
        assertTrue(buf.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buf.order())
        assertEquals(1000, buf.capacity())
        OrtSpectrogramModel.requireTensorBuffer(buf)
    }

    @Test
    fun heapBufferIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            OrtSpectrogramModel.requireTensorBuffer(FloatBuffer.allocate(1000))
        }
    }

    @Test
    fun nonNativeOrderDirectBufferIsRejected() {
        val other = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buf = ByteBuffer.allocateDirect(4000).order(other).asFloatBuffer()
        assertTrue(buf.isDirect)
        assertThrows(IllegalStateException::class.java) { OrtSpectrogramModel.requireTensorBuffer(buf) }
    }
}
