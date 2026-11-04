package com.musicremover.app

import com.musicremover.app.processing.ProcessOptions
import com.musicremover.app.processing.internal.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOptionsTest {
    private val catalog = listOf(ModelCatalog.HQ3, VOC_FT, MDX3, KARA2, KIM)
    private val allOk: (String) -> Boolean = { true }

    private fun item(model: String = ModelCatalog.HQ3) = QueueItem(
        url = "https://example.com/v", model = model, audioOnly = true, bitrate = "320k", outputFormat = "mp3",
    )

    @Test
    fun valuesComeFromTheItem() {
        val o = item().toOptions(catalog, internal = true, internalDefault = VOC_FT, noticeOk = allOk, sourceFileSize = 12L, sourceFilePath = "content://x")
        assertEquals(
            ProcessOptions(ModelCatalog.HQ3, audioOnly = true, bitrate = "320k", sourceFileSize = 12L, sourceFilePath = "content://x", outputFormat = "mp3"),
            o,
        )
    }

    @Test
    fun unacceptedNoticeModelBecomesInternalDefault() {
        val o = item(KIM).toOptions(catalog, internal = true, internalDefault = VOC_FT, noticeOk = { it != KIM }, sourceFileSize = null, sourceFilePath = null)
        assertEquals(VOC_FT, o.model)
        assertEquals("mp3", o.outputFormat)
    }

    @Test
    fun acceptedNoticeModelIsKept() {
        val o = item(KIM).toOptions(catalog, internal = true, internalDefault = VOC_FT, noticeOk = allOk, sourceFileSize = null, sourceFilePath = null)
        assertEquals(KIM, o.model)
    }

    @Test
    fun unsupportedModelBecomesInternalDefault() {
        val o = item("vocals_mel_band_roformer.ckpt").toOptions(catalog, internal = true, internalDefault = MDX3, noticeOk = allOk, sourceFileSize = null, sourceFilePath = null)
        assertEquals(MDX3, o.model)
    }

    @Test
    fun unsupportedInternalDefaultFallsBackToHq3() {
        val o = item("vocals_mel_band_roformer.ckpt").toOptions(catalog, internal = true, internalDefault = "gone.onnx", noticeOk = allOk, sourceFileSize = null, sourceFilePath = null)
        assertEquals(ModelCatalog.HQ3, o.model)
    }

    @Test
    fun remoteBackendKeepsTheModel() {
        val o = item(KIM).toOptions(listOf(KIM), internal = false, internalDefault = VOC_FT, noticeOk = { false }, sourceFileSize = null, sourceFilePath = null)
        assertEquals(KIM, o.model)
        assertEquals(true, o.audioOnly)
        assertEquals("320k", o.bitrate)
    }

    @Test
    fun jsonRoundTripWithAndWithoutOutputFormat() {
        val i = item()
        assertEquals(i, QueueItem.fromJsonMap(i.toJsonMap()))
        val legacy = i.toJsonMap() - "outputFormat"
        assertEquals("m4a", QueueItem.fromJsonMap(legacy).outputFormat)
    }

    private companion object {
        const val KIM = "Kim_Vocal_2.onnx"
        const val VOC_FT = "UVR-MDX-NET-Voc_FT.onnx"
        const val MDX3 = "UVR_MDXNET_3_9662.onnx"
        const val KARA2 = "UVR_MDXNET_KARA_2.onnx"
    }
}
