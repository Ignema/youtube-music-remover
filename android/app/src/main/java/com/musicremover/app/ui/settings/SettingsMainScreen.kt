package com.musicremover.app.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.BuildConfig
import com.musicremover.app.MainUiState
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.ModelState
import com.musicremover.app.processing.internal.PerformanceSettings
import com.musicremover.app.ui.theme.ColorPalettes

/**
 * Top-level Settings list. Summaries come from the UI state only: nothing here starts network work or
 * the yt-dlp unzip ("Tap to check" until Home in Internal mode or the yt-dlp screen loaded the version).
 * [selectedRoute] is the detail shown next to the list in two-pane (null in a single pane).
 */
@Composable
fun SettingsMainScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onNavigate: (route: String) -> Unit,
    selectedRoute: String? = null,
    listState: SettingsListState? = null,
) {
    val ui by vm.ui.collectAsState()
    // D3: reload the cookie list (also runs the lazy migration)
    LaunchedEffect(Unit) { vm.loadCookieSites() }
    val internal = ui.processingMode == BackendProvider.MODE_INTERNAL


    SettingsScaffold(
        title = stringResource(R.string.settings),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
        listState = listState,
    ) {
        SectionLabel(stringResource(R.string.settings_group_processing))
        Spacer(Modifier.height(4.dp))
        NavRow(
            // The current mode's icon (chip = on-device, terminal = Termux, cloud = server)
            com.musicremover.app.ui.processingSourceOf(ui.processingMode)?.icon ?: Icons.Outlined.Memory,
            stringResource(R.string.processing_mode),
            modeSummary(ui),
            selected = selectedRoute?.let { SettingsRoutes.PROCESSING == it },
        ) { onNavigate(SettingsRoutes.PROCESSING) }
        NavRow(
            Icons.Outlined.Layers,
            stringResource(R.string.settings_row_models),
            onlyInternal(internal, modelsSummary(ui)),
            selected = selectedRoute?.let { SettingsRoutes.MODELS == it },
        ) { onNavigate(SettingsRoutes.MODELS) }
        NavRow(
            Icons.Outlined.Speed,
            stringResource(R.string.settings_row_performance),
            onlyInternal(internal, performanceSummary(ui)),
            selected = selectedRoute?.let { SettingsRoutes.PERFORMANCE == it },
        ) { onNavigate(SettingsRoutes.PERFORMANCE) }
        NavRow(
            Icons.Outlined.Cookie,
            stringResource(R.string.cookies_title),
            onlyInternal(internal, accountsSummary(ui)),
            selected = selectedRoute?.let { SettingsRoutes.ACCOUNTS == it },
        ) { onNavigate(SettingsRoutes.ACCOUNTS) }
        // Opens the yt-dlp screen; it never starts an update by itself
        NavRow(
            Icons.Outlined.SystemUpdate,
            stringResource(R.string.settings_row_update_ytdlp),
            onlyInternal(internal, ytDlpSummary(ui)),
            selected = selectedRoute?.let { SettingsRoutes.YTDLP == it },
        ) { onNavigate(SettingsRoutes.YTDLP) }

        SettingsDivider()
        SectionLabel(stringResource(R.string.settings_group_output))
        Spacer(Modifier.height(4.dp))
        NavRow(
            Icons.Outlined.AudioFile,
            stringResource(R.string.settings_row_output),
            outputSummary(ui),
            selected = selectedRoute?.let { SettingsRoutes.OUTPUT == it },
        ) { onNavigate(SettingsRoutes.OUTPUT) }

        SettingsDivider()
        SectionLabel(stringResource(R.string.settings_group_app))
        Spacer(Modifier.height(4.dp))
        NavRow(
            Icons.Outlined.Palette,
            stringResource(R.string.appearance),
            appearanceSummary(ui),
            selected = selectedRoute?.let { SettingsRoutes.APPEARANCE == it },
        ) { onNavigate(SettingsRoutes.APPEARANCE) }
        NavRow(
            Icons.Outlined.History,
            stringResource(R.string.settings_row_storage),
            storageSummary(ui, vm),
            selected = selectedRoute?.let { SettingsRoutes.STORAGE == it },
        ) { onNavigate(SettingsRoutes.STORAGE) }
        NavRow(
            Icons.Outlined.Security,
            stringResource(R.string.permissions),
            stringResource(R.string.permissions_desc),
            // Permissions opens full screen (outside the list-detail), so it is a plain link, never a tab
            selected = null,
        ) { onNavigate(SettingsRoutes.PERMISSIONS) }
        NavRow(
            Icons.Outlined.Info,
            stringResource(R.string.settings_row_about),
            stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
            selected = selectedRoute?.let { SettingsRoutes.ABOUT == it },
        ) { onNavigate(SettingsRoutes.ABOUT) }
    }
}

/** Rows that only matter in Internal mode stay visible in every mode, prefixed "Internal mode ·" outside it. */
@Composable
private fun onlyInternal(internal: Boolean, summary: String): String =
    if (internal) summary else stringResource(R.string.settings_internal_only, summary)

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
}

/**
 * Theme, plus the palette when it is not the default and Material You is off, plus the language when
 * one is chosen; "System default" when all of them are defaults.
 */
@Composable
private fun appearanceSummary(ui: MainUiState): String {
    val systemTheme = themeLabel(ui.themeMode) == themeLabel("system")
    val dynamicActive = ui.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val paletteShown = ui.colorPalette != ColorPalettes.DEFAULT_ID && !dynamicActive
    if (systemTheme && ui.language.isEmpty() && !paletteShown) return stringResource(R.string.system_default)
    val parts = mutableListOf(themeLabel(ui.themeMode))
    if (paletteShown) parts += stringResource(ColorPalettes.byId(ui.colorPalette).nameRes)
    if (ui.language.isNotEmpty()) parts += languageLabel(ui.language)
    return parts.joinToString(" · ")
}

@Composable
private fun modeSummary(ui: MainUiState): String = when (ui.processingMode) {
    BackendProvider.MODE_INTERNAL -> stringResource(R.string.mode_internal)
    BackendProvider.MODE_TERMUX -> stringResource(R.string.local_termux)
    else -> {
        val host = android.net.Uri.parse(ui.serverUrl).host?.takeIf { it.isNotBlank() } ?: ui.serverUrl
        if (host.isBlank()) stringResource(R.string.mode_server) else stringResource(R.string.settings_mode_server, host)
    }
}

@Composable
private fun modelsSummary(ui: MainUiState): String {
    val spec = ui.internalModels.firstOrNull { it.name == ui.internalModel } ?: ui.internalModels.firstOrNull()
    val name = spec?.let { modelName(it) } ?: ui.internalModel.removeSuffix(".onnx")
    val downloaded = ui.internalModels.count { ui.modelStates[it.name] is ModelState.Ready }
    return pluralStringResource(R.plurals.settings_models_summary, downloaded, name, downloaded)
}

@Composable
private fun performanceSummary(ui: MainUiState): String {
    val p = ui.performance
    val threads = if (p.threads == 0) stringResource(R.string.settings_threads_auto)
    else pluralStringResource(R.plurals.settings_threads_n, p.threads, p.threads)
    val quality = stringResource(
        when (p.quality) {
            PerformanceSettings.QUALITY_FAST -> R.string.settings_quality_fast
            PerformanceSettings.QUALITY_BEST -> R.string.settings_quality_best
            else -> R.string.settings_quality_balanced
        },
    )
    return "$threads · $quality"
}

@Composable
private fun accountsSummary(ui: MainUiState): String {
    if (ui.cookieSites.isEmpty()) return stringResource(R.string.cookies_empty_title)
    val on = ui.cookieSites.count { it.enabled }
    return pluralStringResource(R.plurals.settings_accounts_on, on, on)
}

@Composable
private fun ytDlpSummary(ui: MainUiState): String {
    val v = ui.ytDlpVersion ?: return stringResource(R.string.settings_ytdlp_tap_to_check)
    return listOfNotNull(v, ytDlpAge(v)).joinToString(" · ")
}

@Composable
private fun outputSummary(ui: MainUiState): String {
    val parts = mutableListOf<String>()
    // Server and Termux always write MP3 for audio-only, so the format only matters in Internal mode
    if (ui.processingMode == BackendProvider.MODE_INTERNAL) parts += ui.outputFormat.uppercase()
    parts += stringResource(R.string.settings_bitrate_kbps, ui.bitrate.removeSuffix("k"))
    parts += stringResource(if (ui.audioOnly) R.string.settings_audio_only_on else R.string.settings_audio_only_off)
    return parts.joinToString(" · ")
}

@Composable
private fun storageSummary(ui: MainUiState, vm: MainViewModel): String {
    if (ui.history.isEmpty()) return stringResource(R.string.no_history)
    val size = remember(ui.history.size) { vm.getCacheSizeFormatted() }
    return pluralStringResource(R.plurals.settings_storage_summary, ui.history.size, ui.history.size, size)
}
