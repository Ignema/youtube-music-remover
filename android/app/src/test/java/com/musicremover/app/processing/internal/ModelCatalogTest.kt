package com.musicremover.app.processing.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelCatalogTest {
    // Gradle runs unit tests with the module directory (android/app) as working directory.
    private val asset = File("src/main/assets/models.json").readText()

    @Test
    fun bundledCatalogHasHq3Values() {
        val models = ModelCatalog.parse(asset)
        assertEquals(1, models.size)
        val m = models[0]
        assertEquals(ModelCatalog.HQ3, m.name)
        assertEquals(
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx",
            m.url,
        )
        assertEquals(66_759_214L, m.size)
        assertEquals("317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc", m.sha256)
        assertEquals(6144, m.nFft)
        assertEquals(3072, m.dimF)
        assertEquals(256, m.dimT)
        assertEquals(1024, m.hop)
        assertEquals(1.022, m.compensate, 0.0)
        assertEquals("Instrumental", m.primaryStem)
        assertEquals(256, m.segmentSize)
        assertEquals(0.25, m.overlap, 0.0)
    }

    @Test
    fun rejectsPathTraversalName() = assertRejected(mutate("\"UVR-MDX-NET-Inst_HQ_3.onnx\"", "\"../x.onnx\""), "name")

    @Test
    fun rejectsHttpUrl() = assertRejected(mutate("\"https://", "\"http://"), "url")

    @Test
    fun rejectsNFftWithFactor5() = assertRejected(mutate("\"n_fft\": 6144", "\"n_fft\": 7680"), "n_fft")

    @Test
    fun rejectsDimFAboveBinCount() = assertRejected(mutate("\"dim_f\": 3072", "\"dim_f\": 4000"), "dim_f")

    @Test
    fun rejectsOverlapOne() = assertRejected(mutate("\"overlap\": 0.25", "\"overlap\": 1.0"), "overlap")

    @Test
    fun rejectsDuplicateNames() {
        val start = asset.indexOf('[')
        val end = asset.lastIndexOf(']')
        val entry = asset.substring(start + 1, end)
        assertRejected(asset.substring(0, start + 1) + entry + "," + entry + asset.substring(end), "name")
    }

    @Test
    fun rejectsWrongVersion() = assertRejected(mutate("\"version\": 1", "\"version\": 2"), "version")

    private fun mutate(from: String, to: String): String {
        assertTrue("asset must contain $from", asset.contains(from))
        return asset.replace(from, to)
    }

    private fun assertRejected(json: String, field: String) {
        try {
            ModelCatalog.parse(json)
            fail("expected rejection of $field")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.startsWith("models.json: $field invalid for "))
        }
    }
}
