package com.musicremover.app.processing

import com.musicremover.app.processing.internal.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionTest {
    private val internalModels = listOf(ModelCatalog.HQ3)
    private val serverModels = listOf(KIM, VOC_FT, ModelCatalog.HQ3)

    @Test
    fun kimToInternalAndBackRestoresKim() {
        val inInternal = ModelSelection.forMode(true, KIM, internalModels, null)
        assertEquals(ModelCatalog.HQ3 to KIM, inInternal)
        val back = ModelSelection.forMode(false, inInternal.first, serverModels, inInternal.second)
        assertEquals(KIM to null, back)
    }

    @Test
    fun hq3PickedInServerModeStaysAndRemembersNothing() {
        val inInternal = ModelSelection.forMode(true, ModelCatalog.HQ3, internalModels, null)
        assertEquals(ModelCatalog.HQ3 to null, inInternal)
        val back = ModelSelection.forMode(false, inInternal.first, serverModels, inInternal.second)
        assertEquals(ModelCatalog.HQ3 to null, back)
    }

    @Test
    fun startupInInternalModeThenServerRestoresDefault() {
        // MainUiState starts with Kim; init applies internal mode
        val start = ModelSelection.forMode(true, KIM, internalModels, null)
        assertEquals(ModelCatalog.HQ3 to KIM, start)
        assertEquals(KIM to null, ModelSelection.forMode(false, start.first, serverModels, start.second))
    }

    @Test
    fun internalToInternalIsIdempotent() {
        val once = ModelSelection.forMode(true, KIM, internalModels, null)
        val twice = ModelSelection.forMode(true, once.first, internalModels, once.second)
        assertEquals(once, twice)
    }

    @Test
    fun resetInInternalModeKeepsRememberedModel() {
        val inInternal = ModelSelection.forMode(true, VOC_FT, internalModels, null)
        assertEquals(ModelCatalog.HQ3 to VOC_FT, inInternal)
        // reset() recreates the UI state with the default Kim while still in internal mode
        val afterReset = ModelSelection.forMode(true, KIM, internalModels, inInternal.second)
        assertEquals(ModelCatalog.HQ3 to VOC_FT, afterReset)
        assertEquals(VOC_FT to null, ModelSelection.forMode(false, afterReset.first, serverModels, afterReset.second))
    }

    private companion object {
        const val KIM = "Kim_Vocal_2.onnx"
        const val VOC_FT = "UVR-MDX-NET-Voc_FT.onnx"
    }
}
