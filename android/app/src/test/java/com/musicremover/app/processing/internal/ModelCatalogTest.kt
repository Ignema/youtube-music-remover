package com.musicremover.app.processing.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelCatalogTest {
    // Gradle runs unit tests with the module directory (android/app) as working directory.
    private val asset = File("src/main/assets/models.json").readText()

    private data class Expected(
        val name: String, val label: String, val size: Long, val sha256: String, val nFft: Int, val dimF: Int,
        val compensate: Double, val primaryStem: String, val licenseNotice: Boolean,
    )

    // design.md §1a.1 (verified against the downloaded files and UVR's model data).
    private val expected = listOf(
        Expected("UVR-MDX-NET-Inst_HQ_3.onnx", "hq3", 66_759_214L,
            "317554b07fe1ea5279a77f2b1520a41ea4b93432560c4ffd08792c30fddf9adc", 6144, 3072, 1.022, "Instrumental", false),
        Expected("UVR-MDX-NET-Voc_FT.onnx", "voc_ft", 66_762_490L,
            "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111", 7680, 3072, 1.021, "Vocals", false),
        Expected("UVR_MDXNET_3_9662.onnx", "mdx3", 29_704_436L,
            "e02220e80d8253f4c2209f8924298b2b686bbdf2868b788ff5500fb9bd94aadc", 6144, 2048, 1.035, "Vocals", false),
        Expected("UVR_MDXNET_KARA_2.onnx", "kara2", 52_786_726L,
            "bf32e15105a09c0f7dddd2b67346146334d6f3ecb399ed7638eba2ab07cbf5f4", 5120, 2048, 1.065, "Instrumental", false),
        Expected("Kim_Vocal_2.onnx", "kim2", 66_759_214L,
            "ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b", 7680, 3072, 1.009, "Vocals", true),
    )

    @Test
    fun bundledCatalogHasVerifiedValues() {
        val models = ModelCatalog.parse(asset)
        assertEquals(expected.size, models.size)
        assertEquals(ModelCatalog.HQ3, models[0].name)
        for ((e, m) in expected.zip(models)) {
            assertEquals(e.name, m.name)
            assertEquals(e.label, m.label)
            assertEquals(
                "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/${e.name}",
                m.url,
            )
            assertEquals(e.name, e.size, m.size)
            assertEquals(e.name, e.sha256, m.sha256)
            assertEquals(e.name, e.nFft, m.nFft)
            assertEquals(e.name, e.dimF, m.dimF)
            assertEquals(256, m.dimT)
            assertEquals(1024, m.hop)
            assertEquals(e.name, e.compensate, m.compensate, 0.0)
            assertEquals(e.name, e.primaryStem, m.primaryStem)
            assertEquals(256, m.segmentSize)
            assertEquals(0.25, m.overlap, 0.0)
            assertEquals(e.name, e.licenseNotice, m.licenseNotice)
        }
    }

    @Test
    fun acceptsEntryWithoutLabelAndNotice() {
        val json = asset
            .replace(Regex("\"label\": \"[a-z0-9_]+\",\\s*"), "")
            .replace(Regex("\"license_notice\": true,\\s*"), "")
        assertTrue(!json.contains("\"label\"") && !json.contains("\"license_notice\""))
        val models = ModelCatalog.parse(json)
        assertEquals(expected.size, models.size)
        for (m in models) {
            assertEquals("", m.label)
            assertEquals(false, m.licenseNotice)
        }
    }

    @Test
    fun rejectsPathTraversalName() = assertRejected(mutate("\"UVR-MDX-NET-Inst_HQ_3.onnx\"", "\"../x.onnx\""), "name")

    @Test
    fun rejectsHttpUrl() = assertRejected(mutate("\"https://", "\"http://"), "url")

    @Test
    fun rejectsNFftWithFactor7() = assertRejected(mutate("\"n_fft\": 6144", "\"n_fft\": ${7 * 512}"), "n_fft")

    @Test
    fun rejectsDimFAboveBinCount() = assertRejected(mutate("\"dim_f\": 3072", "\"dim_f\": 4000"), "dim_f")

    @Test
    fun rejectsOverlapOne() = assertRejected(mutate("\"overlap\": 0.25", "\"overlap\": 1.0"), "overlap")

    @Test
    fun rejectsBadLabel() {
        assertRejected(mutate("\"label\": \"hq3\"", "\"label\": \"HQ3\""), "label")
        assertRejected(mutate("\"label\": \"hq3\"", "\"label\": \"hq-3\""), "label")
        assertRejected(mutate("\"label\": \"hq3\"", "\"label\": \"${"a".repeat(25)}\""), "label")
    }

    @Test
    fun rejectsDuplicateLabel() = assertRejected(mutate("\"label\": \"voc_ft\"", "\"label\": \"hq3\""), "label")

    @Test
    fun rejectsNonBooleanNotice() {
        assertRejected(mutate("\"license_notice\": true", "\"license_notice\": \"yes\""), "license_notice")
        assertRejected(mutate("\"license_notice\": true", "\"license_notice\": \"true\""), "license_notice")
        assertRejected(mutate("\"license_notice\": true", "\"license_notice\": 1"), "license_notice")
    }

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
