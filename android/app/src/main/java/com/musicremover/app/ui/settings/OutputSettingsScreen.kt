package com.musicremover.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.Mp3Support

/** Output: audio-only format (Internal mode), bitrate and Audio only, all persisted prefs. */
@Composable
fun OutputSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val internal = ui.processingMode == BackendProvider.MODE_INTERNAL
    // The probe runs only when the screen opens in Internal mode
    LaunchedEffect(internal) { if (internal) vm.checkMp3() }
    SettingsScaffold(
        title = stringResource(R.string.settings_row_output),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        // Audio only
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = ui.audioOnly, role = Role.Switch, onValueChange = vm::setAudioOnly)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.audio_only), style = MaterialTheme.typography.bodyLarge)
                SettingsNote(stringResource(R.string.output_audio_only_desc))
            }
            Switch(checked = ui.audioOnly, onCheckedChange = null)
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        SectionLabel(stringResource(R.string.output_format))
        Spacer(Modifier.height(4.dp))
        if (internal) {
            SettingsNote(stringResource(R.string.output_format_note))
            Spacer(Modifier.height(8.dp))
            val mp3 = ui.mp3Support
            ChoiceRow(
                label = stringResource(R.string.output_m4a),
                note = null,
                selected = ui.outputFormat == Mp3Support.M4A,
                enabled = true,
                onClick = { vm.setOutputFormat(Mp3Support.M4A) },
            )
            ChoiceRow(
                label = stringResource(R.string.output_mp3),
                note = when (mp3) {
                    Mp3Support.State.Unknown, Mp3Support.State.Checking -> stringResource(R.string.output_mp3_checking)
                    Mp3Support.State.Available -> null
                    Mp3Support.State.Unavailable -> stringResource(R.string.output_mp3_unavailable)
                },
                // A saved mp3 stays selected (disabled) when the probe fails; the pref is not rewritten
                selected = ui.outputFormat == Mp3Support.MP3,
                enabled = mp3 == Mp3Support.State.Available,
                onClick = { vm.setOutputFormat(Mp3Support.MP3) },
            )
        } else {
            // Server and Termux write MP3 for audio-only results; no probe runs
            ChoiceRow(
                label = stringResource(R.string.output_mp3),
                note = stringResource(R.string.output_server_mp3_note),
                selected = true,
                enabled = false,
                onClick = {},
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        SectionLabel(stringResource(R.string.bitrate))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("128k", "192k", "320k").forEach { br ->
                FilterChip(
                    selected = ui.bitrate == br,
                    onClick = { vm.setBitrate(br) },
                    label = { Text(stringResource(R.string.settings_bitrate_kbps, br.removeSuffix("k"))) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}

