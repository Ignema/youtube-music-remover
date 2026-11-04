package com.musicremover.app.processing

import com.musicremover.app.processing.internal.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionTest {
    private val catalog = listOf(ModelCatalog.HQ3, VOC_FT, MDX3, KARA2, KIM)
    private val serverModels = listOf(KIM, VOC_FT, ModelCatalog.HQ3, KARA2)
    private val hq3 = ModelCatalog.HQ3

    @Test
    fun kimToInternalAndBackRestoresKim() {
        // Kim is a catalog model, but the internal model comes from the pref, never from the remote pick
        val inInternal = ModelSelection.forMode(true, KIM, catalog, null, hq3)
        assertEquals(hq3 to KIM, inInternal)
        val back = ModelSelection.forMode(false, inInternal.first, serverModels, inInternal.second, hq3)
        assertEquals(KIM to null, back)
    }

    @Test
    fun enteringUsesTheInternalModelPref() {
        assertEquals(VOC_FT to KIM, ModelSelection.forMode(true, KIM, catalog, null, VOC_FT))
    }

    @Test
    fun hq3PickedInServerModeIsRestoredOnLeaving() {
        val inInternal = ModelSelection.forMode(true, hq3, catalog, null, hq3)
        assertEquals(hq3 to hq3, inInternal)
        val back = ModelSelection.forMode(false, inInternal.first, serverModels, inInternal.second, hq3)
        assertEquals(hq3 to null, back)
    }

    @Test
    fun startupInInternalModeThenServerRestoresDefault() {
        // MainUiState starts with Kim; init applies internal mode
        val start = ModelSelection.forMode(true, KIM, catalog, null, hq3)
        assertEquals(hq3 to KIM, start)
        assertEquals(KIM to null, ModelSelection.forMode(false, start.first, serverModels, start.second, hq3))
    }

    @Test
    fun internalToInternalIsIdempotent() {
        val once = ModelSelection.forMode(true, KIM, catalog, null, hq3)
        val twice = ModelSelection.forMode(true, once.first, catalog, once.second, hq3)
        assertEquals(once, twice)
    }

    @Test
    fun resetInInternalModeKeepsRememberedModel() {
        val inInternal = ModelSelection.forMode(true, VOC_FT, catalog, null, hq3)
        assertEquals(hq3 to VOC_FT, inInternal)
        // reset() recreates the UI state with the default Kim while still in internal mode
        val afterReset = ModelSelection.forMode(true, KIM, catalog, inInternal.second, hq3)
        assertEquals(hq3 to VOC_FT, afterReset)
        assertEquals(VOC_FT to null, ModelSelection.forMode(false, afterReset.first, serverModels, afterReset.second, hq3))
    }

    @Test
    fun resetWithStaleKimSelectedUsesThePrefAndKeepsRemembered() {
        assertEquals(hq3 to VOC_FT, ModelSelection.forMode(true, KIM, catalog, VOC_FT, hq3))
    }

    @Test
    fun unsupportedInternalDefaultFallsBackToHq3() {
        assertEquals(hq3 to KIM, ModelSelection.forMode(true, KIM, catalog, null, "missing.onnx"))
        assertEquals(hq3 to KIM, ModelSelection.forMode(true, KIM, listOf(hq3), null, VOC_FT))
    }

    @Test
    fun userPickInInternalModeSticks() {
        // onModelSelect wrote internal_model = 3_9662 and selectedModel = 3_9662
        assertEquals(MDX3 to KIM, ModelSelection.forMode(true, MDX3, catalog, KIM, MDX3))
    }

    @Test
    fun serverModeWithKimIsUnchanged() {
        assertEquals(KIM to null, ModelSelection.forMode(false, KIM, serverModels, null, VOC_FT))
    }

    private companion object {
        const val KIM = "Kim_Vocal_2.onnx"
        const val VOC_FT = "UVR-MDX-NET-Voc_FT.onnx"
        const val MDX3 = "UVR_MDXNET_3_9662.onnx"
        const val KARA2 = "UVR_MDXNET_KARA_2.onnx"
    }
}
