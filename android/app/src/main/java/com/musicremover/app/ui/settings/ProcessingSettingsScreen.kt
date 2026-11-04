package com.musicremover.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.processing.BackendProvider

/** Processing mode: mode chips and the Internal, Termux or Server details of the chosen mode. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProcessingSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(
        title = stringResource(R.string.processing_mode),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                Triple(BackendProvider.MODE_INTERNAL, com.musicremover.app.ui.ProcessingSources.INTERNAL.icon, R.string.mode_internal),
                Triple(BackendProvider.MODE_TERMUX, com.musicremover.app.ui.ProcessingSources.TERMUX.icon, R.string.local_termux),
                Triple(BackendProvider.MODE_SERVER, com.musicremover.app.ui.ProcessingSources.SERVER.icon, R.string.mode_server),
            ).forEach { (mode, icon, label) ->
                FilterChip(
                    selected = ui.processingMode == mode,
                    onClick = { vm.setProcessingMode(mode) },
                    label = { Text(stringResource(label)) },
                    leadingIcon = {
                        Icon(icon, null, Modifier.size(18.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = ui.processingMode == BackendProvider.MODE_INTERNAL) {
            Column {
                ui.internalUnsupportedReason?.let { reason ->
                    SettingsNote(reason, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                }
                SettingsNote(stringResource(R.string.internal_warning))
            }
        }

        AnimatedVisibility(visible = ui.processingMode == BackendProvider.MODE_TERMUX) {
            Column {
                SettingsNote(stringResource(R.string.on_device_desc))
                Spacer(Modifier.height(16.dp))
                if (ui.termuxInstalled) {
                    TermuxControls(vm)
                } else {
                    TermuxNotInstalled()
                }
            }
        }

        AnimatedVisibility(visible = ui.processingMode == BackendProvider.MODE_SERVER) {
            Column {
                OutlinedTextField(
                    value = ui.serverUrl,
                    onValueChange = vm::onServerUrlChange,
                    label = { Text(stringResource(R.string.server_url)) },
                    placeholder = { Text("http://192.168.1.100:8000") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = vm::discoverServer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.discover_server))
                }
            }
        }
    }

    // Termux operation bottom sheet
    val termuxOp = ui.termuxOperation
    if (termuxOp != null) {
        ModalBottomSheet(onDismissRequest = vm::dismissTermuxOperation) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (ui.termuxServerOnline) {
                    Icon(
                        Icons.Outlined.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    termuxOp,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (!ui.termuxServerOnline && termuxOp.contains("Switch back")) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.termux_come_back),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TermuxControls(vm: MainViewModel) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.termux_detected),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = vm::installTermuxServer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.DownloadDone, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.install))
                }
                FilledTonalButton(
                    onClick = vm::startTermuxServer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.start))
                }
                OutlinedButton(
                    onClick = vm::stopTermuxServer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.stop))
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = vm::updateTermuxServer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.update_server))
            }

            Spacer(Modifier.height(12.dp))
            SettingsNote(stringResource(R.string.first_time_hint))
        }
    }
}

@Composable
private fun TermuxNotInstalled() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.termux_not_found),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SettingsNote(stringResource(R.string.termux_not_found_desc))
        }
    }
}
