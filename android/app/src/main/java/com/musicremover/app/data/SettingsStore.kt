package com.musicremover.app.data

import android.content.Context
import com.musicremover.app.BuildConfig
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.ui.theme.ColorPalettes

/** Output and appearance prefs that "Process another" keeps (see resetState). */
data class PersistedUi(
    val audioOnly: Boolean,
    val bitrate: String,
    val outputFormat: String,
    val themeMode: String,
    val dynamicColor: Boolean,
    val colorPalette: String,
    val language: String,
    val widgetTheme: String,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, null) ?: BuildConfig.API_BASE_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    /** "internal", "termux", or "server". Migrated once from [serverUrl] when unset. */
    var processingMode: String
        get() {
            val stored = prefs.getString(KEY_PROCESSING_MODE, null)
            if (stored in PROCESSING_MODES) return stored!!
            val migrated = migrateProcessingMode(prefs.getString(KEY_SERVER_URL, null))
            prefs.edit().putString(KEY_PROCESSING_MODE, migrated).apply()
            return migrated
        }
        set(value) {
            require(value in PROCESSING_MODES) { "Invalid processing mode: $value" }
            prefs.edit().putString(KEY_PROCESSING_MODE, value).apply()
        }

    /** "system", "light", "dark" or "black" */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    /** Whether to use Material You dynamic colors (Android 12+) */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    /** Color palette id from [ColorPalettes]; applies when Material You is off or unavailable. */
    var colorPalette: String
        get() = ColorPalettes.resolveId(prefs.getString(KEY_COLOR_PALETTE, null))
        set(value) {
            require(value in ColorPalettes.IDS) { "Invalid color palette: $value" }
            prefs.edit().putString(KEY_COLOR_PALETTE, value).apply()
        }

    /** Language code, e.g. "en", "ar", "fr". Empty = system default */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** Widget theme: "orange", "light", "dark", "transparent" */
    var widgetTheme: String
        get() = prefs.getString(KEY_WIDGET_THEME, "orange") ?: "orange"
        set(value) = prefs.edit().putString(KEY_WIDGET_THEME, value).apply()

    /** Audio-only result format for Internal jobs: "m4a" or "mp3". */
    var outputFormat: String
        get() = prefs.getString(KEY_OUTPUT_FORMAT, null)?.takeIf { it in OUTPUT_FORMATS } ?: DEFAULT_OUTPUT_FORMAT
        set(value) {
            require(value in OUTPUT_FORMATS) { "Invalid output format: $value" }
            prefs.edit().putString(KEY_OUTPUT_FORMAT, value).apply()
        }

    /** AAC/MP3 bitrate such as "192k"; an invalid stored value reads as "192k". */
    var bitrate: String
        get() = prefs.getString(KEY_BITRATE, null)?.takeIf { isValidBitrate(it) } ?: DEFAULT_BITRATE
        set(value) {
            require(isValidBitrate(value)) { "Invalid bitrate: $value" }
            prefs.edit().putString(KEY_BITRATE, value).apply()
        }

    var audioOnly: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ONLY, value).apply()

    /** ORT intra-op threads; 0 = Auto. Coerced to the device maximum by PerformanceSettings.resolve. */
    var perfThreads: Int
        get() = typedInt(KEY_PERF_THREADS, 0).takeIf { it in 0..MAX_THREADS_PREF } ?: 0
        set(value) {
            require(value in 0..MAX_THREADS_PREF) { "Invalid thread count: $value" }
            prefs.edit().putInt(KEY_PERF_THREADS, value).apply()
        }

    /** Overlap preset: "fast", "balanced" or "best". */
    var perfQuality: String
        get() = prefs.getString(KEY_PERF_QUALITY, null)?.takeIf { it in PERF_QUALITIES } ?: DEFAULT_PERF_QUALITY
        set(value) {
            require(value in PERF_QUALITIES) { "Invalid quality preset: $value" }
            prefs.edit().putString(KEY_PERF_QUALITY, value).apply()
        }

    /** ORT batch size: 1 or 2. */
    var perfBatch: Int
        get() = typedInt(KEY_PERF_BATCH, 1).takeIf { it == 1 || it == 2 } ?: 1
        set(value) {
            require(value == 1 || value == 2) { "Invalid batch size: $value" }
            prefs.edit().putInt(KEY_PERF_BATCH, value).apply()
        }

    /** The on-device model; a name that is not in the catalog is replaced by HQ3 where it is used. */
    var internalModel: String
        get() = prefs.getString(KEY_INTERNAL_MODEL, null)?.takeIf { it.isNotBlank() } ?: ModelCatalog.HQ3
        set(value) = prefs.edit().putString(KEY_INTERNAL_MODEL, value).apply()

    fun persistedUi(): PersistedUi = PersistedUi(
        audioOnly = audioOnly,
        bitrate = bitrate,
        outputFormat = outputFormat,
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        colorPalette = colorPalette,
        language = language,
        widgetTheme = widgetTheme,
    )

    /** A value of the wrong type (never written by this app) reads as [default]. */
    private fun typedInt(key: String, default: Int): Int = try {
        prefs.getInt(key, default)
    } catch (_: ClassCastException) {
        default
    }

    companion object {
        /** The prefs file of every user-facing setting. */
        const val PREFS = "settings"

        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_COLOR_PALETTE = "color_palette"
        const val KEY_LANGUAGE = "language"
        const val KEY_WIDGET_THEME = "widget_theme"
        const val KEY_PROCESSING_MODE = "processing_mode"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_BITRATE = "bitrate"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_PERF_THREADS = "perf_threads"
        const val KEY_PERF_QUALITY = "perf_quality"
        const val KEY_PERF_BATCH = "perf_batch"
        const val KEY_INTERNAL_MODEL = "internal_model"

        /** Prefix of the per-model license-notice acceptance flag (prefs [PREFS]), written by ModelManager. */
        const val NOTICE_PREFIX = "model_notice_"

        /** Every user-facing key; each one has a Settings entry. */
        val USER_KEYS: Set<String> = setOf(
            KEY_THEME_MODE, KEY_DYNAMIC_COLOR, KEY_COLOR_PALETTE, KEY_LANGUAGE, KEY_WIDGET_THEME, KEY_PROCESSING_MODE,
            KEY_SERVER_URL, KEY_OUTPUT_FORMAT, KEY_BITRATE, KEY_AUDIO_ONLY, KEY_PERF_THREADS,
            KEY_PERF_QUALITY, KEY_PERF_BATCH, KEY_INTERNAL_MODEL,
        )

        /** Bookkeeping keys that are not settings, with why; prefix entries end in `*`. */
        val NON_USER_KEYS: Map<String, String> = mapOf(
            "${NOTICE_PREFIX}*" to "license acceptance, set by the notice dialog (prefs settings, written by ModelManager)",
            "perf_ms:*" to "measured speed for the ETA (prefs app)",
            "internal_first_use_seen" to "banner state (prefs app)",
            "ytdlp_banner_dismissed_day" to "banner state (prefs app)",
            "setup_done" to "first-run permissions flow (prefs app)",
            "queue" to "pending queue items (prefs app)",
            "active_job_id" to "job to resume after a restart (prefs app)",
            "last_done_job_id" to "last finished job (prefs app)",
            "pending_internal_start" to "a queued on-device item waits for the app (prefs app)",
        )

        val PROCESSING_MODES = setOf("internal", "termux", "server")
        val OUTPUT_FORMATS = setOf("m4a", "mp3")
        val PERF_QUALITIES = setOf("fast", "balanced", "best")
        const val DEFAULT_OUTPUT_FORMAT = "m4a"
        const val DEFAULT_BITRATE = "192k"
        const val DEFAULT_PERF_QUALITY = "balanced"
        /** Largest stored thread count accepted (the work Data range); the device maximum is lower. */
        const val MAX_THREADS_PREF = 64

        private val BITRATE = Regex("^(\\d{2,3})k$")

        /** Same rule as the worker's parseBitrate: "64k".."320k". */
        fun isValidBitrate(value: String): Boolean {
            val kbps = BITRATE.matchEntire(value)?.groupValues?.get(1)?.toInt() ?: return false
            return kbps in 64..320
        }
    }
}

/** Initial processing mode for users upgrading from the URL-only setting. */
fun migrateProcessingMode(serverUrl: String?): String =
    if (serverUrl == "http://127.0.0.1:8000") "termux" else "server"
