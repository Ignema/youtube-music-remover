package com.musicremover.app.processing.internal

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelNoticeTest {
    private fun spec(notice: Boolean) = ModelSpec(
        name = "Kim_Vocal_2.onnx",
        url = "https://example.com/Kim_Vocal_2.onnx",
        size = 1L,
        sha256 = "0".repeat(64),
        nFft = 7680,
        dimF = 3072,
        dimT = 256,
        hop = 1024,
        compensate = 1.0,
        primaryStem = "Vocals",
        segmentSize = 256,
        overlap = 0.25,
        label = "kim2",
        licenseNotice = notice,
    )

    @Test
    fun gateTruthTable() {
        assertTrue(ModelManager.requiresNotice(spec(notice = true), accepted = false))
        assertFalse(ModelManager.requiresNotice(spec(notice = true), accepted = true))
        assertFalse(ModelManager.requiresNotice(spec(notice = false), accepted = false))
        assertFalse(ModelManager.requiresNotice(spec(notice = false), accepted = true))
    }

    @Test
    fun failureMapsToNoticeRequired() {
        assertEquals(ModelFailure.NoticeRequired, ModelFailure.of(ModelNoticeRequiredException("x"), 1L))
    }

    @Test
    fun noticeExceptionIsNotAnIoException() {
        assertFalse(IOException::class.java.isAssignableFrom(ModelNoticeRequiredException::class.java))
        assertTrue(IllegalStateException::class.java.isAssignableFrom(ModelNoticeRequiredException::class.java))
        assertEquals("x", ModelNoticeRequiredException("x").name)
    }

    @Test
    fun onlyKimHasANoticeInTheBundledCatalog() {
        val json = java.io.File("src/main/assets/models.json").readText()
        val withNotice = ModelCatalog.parse(json).filter { it.licenseNotice }.map { it.name }
        assertEquals(listOf("Kim_Vocal_2.onnx"), withNotice)
    }
}
