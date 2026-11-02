package com.musicremover.app.processing.internal

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/** Gson DTO for assets/models.json. All fields nullable; [ModelCatalog.parse] validates them. Kept by ProGuard. */
data class ModelCatalogJson(
    @SerializedName("version") val version: Int?,
    @SerializedName("models") val models: List<ModelSpecJson>?,
)

/** Gson DTO for one model entry. Kept by ProGuard. */
data class ModelSpecJson(
    @SerializedName("name") val name: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("size") val size: Long?,
    @SerializedName("sha256") val sha256: String?,
    @SerializedName("n_fft") val nFft: Int?,
    @SerializedName("dim_f") val dimF: Int?,
    @SerializedName("dim_t") val dimT: Int?,
    @SerializedName("hop") val hop: Int?,
    @SerializedName("compensate") val compensate: Double?,
    @SerializedName("primary_stem") val primaryStem: String?,
    @SerializedName("segment_size") val segmentSize: Int?,
    @SerializedName("overlap") val overlap: Double?,
)

/** Validated model metadata (never touched by reflection). [dimT] is the real frame count. */
data class ModelSpec(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val nFft: Int,
    val dimF: Int,
    val dimT: Int,
    val hop: Int,
    val compensate: Double,
    val primaryStem: String,
    val segmentSize: Int,
    val overlap: Double,
)

object ModelCatalog {
    const val HQ3 = "UVR-MDX-NET-Inst_HQ_3.onnx"
    const val ASSET = "models.json"

    private val NAME = Regex("^[A-Za-z0-9._-]+\\.onnx$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    @Volatile
    private var loaded: List<ModelSpec>? = null

    /** The bundled catalog, parsed once per process. */
    fun get(context: Context): List<ModelSpec> = loaded ?: synchronized(this) {
        loaded ?: parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }).also { loaded = it }
    }

    fun spec(context: Context, name: String): ModelSpec? = get(context).firstOrNull { it.name == name }

    /** Pure. Throws IllegalStateException("models.json: <field> invalid for <name>") on any violation. */
    fun parse(json: String): List<ModelSpec> {
        val root = Gson().fromJson(json, ModelCatalogJson::class.java)
            ?: throw IllegalStateException("models.json: root invalid for catalog")
        if (root.version != 1) throw IllegalStateException("models.json: version invalid for catalog")
        val models = root.models ?: throw IllegalStateException("models.json: models invalid for catalog")
        val names = HashSet<String>()
        return models.map { m ->
            val label = m.name ?: "<unnamed>"
            fun fail(field: String): Nothing = throw IllegalStateException("models.json: $field invalid for $label")

            val name = m.name?.takeIf { NAME.matches(it) } ?: fail("name")
            if (!names.add(name)) fail("name")
            val url = m.url?.takeIf { it.startsWith("https://") } ?: fail("url")
            val size = m.size?.takeIf { it > 0 } ?: fail("size")
            val sha = m.sha256?.takeIf { SHA256.matches(it) } ?: fail("sha256")
            val nFft = m.nFft?.takeIf { it > 0 && it % 2 == 0 && isSmooth23(it) } ?: fail("n_fft")
            val dimF = m.dimF?.takeIf { it in 1..(nFft / 2 + 1) } ?: fail("dim_f")
            val dimT = m.dimT?.takeIf { it > 0 } ?: fail("dim_t")
            val hop = m.hop?.takeIf { it > 0 } ?: fail("hop")
            val segmentSize = m.segmentSize ?: fail("segment_size")
            if (segmentSize != dimT) fail("segment_size")
            if (hop.toLong() * (segmentSize - 1) <= nFft) fail("segment_size")
            val overlap = m.overlap?.takeIf { it >= 0.0 && it < 1.0 } ?: fail("overlap")
            val compensate = m.compensate?.takeIf { it > 0.0 && it <= 2.0 } ?: fail("compensate")
            val stem = m.primaryStem?.takeIf { it == "Instrumental" || it == "Vocals" } ?: fail("primary_stem")
            ModelSpec(name, url, size, sha, nFft, dimF, dimT, hop, compensate, stem, segmentSize, overlap)
        }
    }

    /** True when n = 2^a * 3^b. */
    private fun isSmooth23(n: Int): Boolean {
        var x = n
        while (x % 2 == 0) x /= 2
        while (x % 3 == 0) x /= 3
        return x == 1
    }
}
