package com.musicremover.app.ui.settings

import com.musicremover.app.data.SettingsStore

/** Routes of the nested Settings graph, plus the existing screens reached from it. */
object SettingsRoutes {
    /** The nested graph; `navigate("settings")` opens [MAIN]. */
    const val GRAPH = "settings"
    const val MAIN = "settings/main"
    const val PROCESSING = "settings/processing"
    const val MODELS = "settings/models"
    const val OUTPUT = "settings/output"
    const val PERFORMANCE = "settings/performance"
    const val ACCOUNTS = "settings/accounts"
    const val YTDLP = "settings/ytdlp"
    const val APPEARANCE = "settings/appearance"
    const val STORAGE = "settings/storage"
    const val ABOUT = "settings/about"
    const val PERMISSIONS = "permissions"
    const val HELP = "help"
    const val LICENSES = "licenses"

    /** The Settings sub-screens (the routes registered inside [GRAPH]). */
    val SCREENS = listOf(MAIN, PROCESSING, MODELS, OUTPUT, PERFORMANCE, ACCOUNTS, YTDLP, APPEARANCE, STORAGE, ABOUT)

    val ALL: Set<String> = (SCREENS + PERMISSIONS + HELP + LICENSES).toSet()
}

/** Where every setting and Settings action lives; SettingsMapTest checks that nothing is lost. */
object SettingsMap {
    /** Pref key (prefs [SettingsStore.PREFS]) to route. */
    val ENTRIES: Map<String, String> = mapOf(
        SettingsStore.KEY_THEME_MODE to SettingsRoutes.APPEARANCE,
        SettingsStore.KEY_DYNAMIC_COLOR to SettingsRoutes.APPEARANCE,
        SettingsStore.KEY_COLOR_PALETTE to SettingsRoutes.APPEARANCE,
        SettingsStore.KEY_LANGUAGE to SettingsRoutes.APPEARANCE,
        SettingsStore.KEY_WIDGET_THEME to SettingsRoutes.APPEARANCE,
        SettingsStore.KEY_PROCESSING_MODE to SettingsRoutes.PROCESSING,
        SettingsStore.KEY_SERVER_URL to SettingsRoutes.PROCESSING,
        SettingsStore.KEY_OUTPUT_FORMAT to SettingsRoutes.OUTPUT,
        SettingsStore.KEY_BITRATE to SettingsRoutes.OUTPUT,
        SettingsStore.KEY_AUDIO_ONLY to SettingsRoutes.OUTPUT,
        SettingsStore.KEY_PERF_THREADS to SettingsRoutes.PERFORMANCE,
        SettingsStore.KEY_PERF_QUALITY to SettingsRoutes.PERFORMANCE,
        SettingsStore.KEY_PERF_BATCH to SettingsRoutes.PERFORMANCE,
        SettingsStore.KEY_PERF_ACCELERATOR to SettingsRoutes.PERFORMANCE,
        SettingsStore.KEY_INTERNAL_MODEL to SettingsRoutes.MODELS,
    )

    /** Action id to route. */
    val ACTIONS: Map<String, String> = mapOf(
        "discover_server" to SettingsRoutes.PROCESSING,
        "termux_install" to SettingsRoutes.PROCESSING,
        "termux_start" to SettingsRoutes.PROCESSING,
        "termux_stop" to SettingsRoutes.PROCESSING,
        "termux_update" to SettingsRoutes.PROCESSING,
        "termux_operation" to SettingsRoutes.PROCESSING,
        "model_download" to SettingsRoutes.MODELS,
        "model_delete" to SettingsRoutes.MODELS,
        "ytdlp_update" to SettingsRoutes.YTDLP,
        "cookies_login" to SettingsRoutes.ACCOUNTS,
        "cookies_import" to SettingsRoutes.ACCOUNTS,
        "cookies_toggle" to SettingsRoutes.ACCOUNTS,
        "cookies_login_again" to SettingsRoutes.ACCOUNTS,
        "cookies_delete" to SettingsRoutes.ACCOUNTS,
        "cookies_delete_all" to SettingsRoutes.ACCOUNTS,
        "clear_history" to SettingsRoutes.STORAGE,
        "clear_cache" to SettingsRoutes.STORAGE,
        "export_history" to SettingsRoutes.STORAGE,
        "permissions" to SettingsRoutes.PERMISSIONS,
        "about" to SettingsRoutes.ABOUT,
        "help" to SettingsRoutes.HELP,
        "licenses" to SettingsRoutes.LICENSES,
        "report_problem" to SettingsRoutes.ABOUT,
    )
}
