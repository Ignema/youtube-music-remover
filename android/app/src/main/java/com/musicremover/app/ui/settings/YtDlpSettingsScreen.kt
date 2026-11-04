package com.musicremover.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.YtDlpUpdateState
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.YtDlpErrors
import java.time.LocalDate

/** Bundled yt-dlp for on-device links: version, age and Update (network only on tap). */
@Composable
fun YtDlpSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    // Opening this screen is an explicit action, so the version loads in every mode (first call unzips Python)
    LaunchedEffect(Unit) { vm.loadYtDlpVersion() }
    val update = ui.ytDlpUpdate
    SettingsScaffold(
        title = stringResource(R.string.ytdlp_title),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        if (ui.processingMode != BackendProvider.MODE_INTERNAL) {
            SettingsNote(stringResource(R.string.settings_internal_only_note))
            Spacer(Modifier.height(12.dp))
        }
        val version = ui.ytDlpVersion
        Text(
            if (version == null) stringResource(R.string.ytdlp_version_loading)
            else listOfNotNull(stringResource(R.string.ytdlp_version, version), ytDlpAge(version)).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        SettingsNote(stringResource(R.string.ytdlp_never_auto))
        if (update == YtDlpUpdateState.Running) {
            Spacer(Modifier.height(12.dp))
            // The library reports no byte progress
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            SettingsNote(stringResource(R.string.ytdlp_updating))
        }
        ytDlpResultText(update)?.let {
            Spacer(Modifier.height(12.dp))
            SettingsNote(
                it,
                color = if (update is YtDlpUpdateState.Failed && !update.busy) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = vm::updateYtDlp,
            enabled = update != YtDlpUpdateState.Running,
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ytdlp_update))
        }
    }
}

/** "357 days old", "released today", or null when the version has no date. */
@Composable
internal fun ytDlpAge(version: String): String? {
    val days = YtDlpErrors.versionAgeDays(version, LocalDate.now()) ?: return null
    if (days <= 0) return stringResource(R.string.ytdlp_released_today)
    val n = days.toInt()
    return pluralStringResource(R.plurals.ytdlp_age_days, n, n)
}

/** Result line of the last update, or null while idle or running. */
@Composable
internal fun ytDlpResultText(update: YtDlpUpdateState): String? = when (update) {
    is YtDlpUpdateState.Done ->
        if (update.updated) stringResource(R.string.ytdlp_updated, update.version)
        else stringResource(R.string.ytdlp_up_to_date, update.version)
    is YtDlpUpdateState.Failed ->
        if (update.busy) stringResource(R.string.ytdlp_update_busy)
        else stringResource(R.string.ytdlp_update_failed)
    else -> null
}
