package com.musicremover.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainUiState
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.UiState
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.ModelFailure
import com.musicremover.app.processing.internal.ModelSpec
import com.musicremover.app.processing.internal.ModelState
import com.musicremover.app.processing.internal.PerformanceSettings
import com.musicremover.app.ui.ModelNoticeDialog

/** On-device models: one card per catalog model with selection, state and Download/Delete. */
@Composable
fun ModelsSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var confirmDelete by remember { mutableStateOf<ModelSpec?>(null) }
    SettingsScaffold(
        title = stringResource(R.string.settings_row_models),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        if (ui.processingMode != BackendProvider.MODE_INTERNAL) {
            SettingsNote(stringResource(R.string.settings_internal_only_note))
            Spacer(Modifier.height(12.dp))
        }
        ui.internalUnsupportedReason?.let { reason ->
            SettingsNote(reason, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }
        SettingsNote(stringResource(R.string.internal_warning))
        Spacer(Modifier.height(12.dp))
        if (ui.internalModels.none { ui.modelStates[it.name] is ModelState.Ready }) {
            SettingsNote(stringResource(R.string.models_empty))
            Spacer(Modifier.height(12.dp))
        }
        val anyDownloading = ui.modelStates.values.any { it is ModelState.Downloading }
        for (spec in ui.internalModels) {
            ModelCard(
                ui = ui,
                spec = spec,
                anyDownloading = anyDownloading,
                onSelect = { vm.selectInternalModel(spec.name) },
                onDownload = { vm.downloadModel(spec.name) },
                onDelete = { confirmDelete = spec },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
    confirmDelete?.let { spec ->
        val name = modelName(spec)
        ConfirmDialog(
            title = stringResource(R.string.model_delete_title, name),
            text = stringResource(R.string.model_delete_text),
            confirmLabel = stringResource(R.string.dialog_delete),
            onConfirm = {
                confirmDelete = null
                vm.deleteModel(spec.name)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    // A Download of a model with a license notice opens this dialog
    ModelNoticeDialog(vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCard(
    ui: MainUiState,
    spec: ModelSpec,
    anyDownloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = ui.modelStates[spec.name] ?: ModelState.NotDownloaded
    val selected = ui.internalModel == spec.name
    ElevatedCard(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modelName(spec), style = MaterialTheme.typography.titleSmall)
                        modelTag(spec)?.let { tag ->
                            Spacer(Modifier.width(8.dp))
                            TagChip(tag, highlighted = selected)
                        }
                    }
                    modelBestFor(spec)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val statusText = when (state) {
                is ModelState.Ready -> stringResource(R.string.internal_model_downloaded, formatMb(state.sizeBytes))
                is ModelState.Downloading -> stringResource(
                    R.string.internal_model_downloading,
                    formatMb(state.bytes),
                    formatMb(if (state.total > 0) state.total else spec.size),
                )
                is ModelState.Failed -> when (val f = state.failure) {
                    is ModelFailure.NoSpace -> stringResource(R.string.internal_no_space, (f.neededBytes / 1_000_000).toInt())
                    ModelFailure.Corrupt -> stringResource(R.string.internal_model_corrupt)
                    ModelFailure.Network -> stringResource(R.string.internal_model_failed_network)
                    ModelFailure.Other -> stringResource(R.string.internal_processing_failed)
                    ModelFailure.NoticeRequired -> stringResource(R.string.model_notice_needed)
                }
                ModelState.NotDownloaded -> stringResource(R.string.internal_model_not_downloaded, formatMb(spec.size))
            }
            val noticeOnly = (state as? ModelState.Failed)?.failure == ModelFailure.NoticeRequired
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is ModelState.Failed && !noticeOnly) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            speedHint(ui, spec)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state is ModelState.Downloading) {
                Spacer(Modifier.height(8.dp))
                val totalBytes = if (state.total > 0) state.total else spec.size
                LinearProgressIndicator(
                    progress = { (state.bytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is ModelState.Ready -> OutlinedButton(
                        onClick = onDelete,
                        // Busy rule: no delete while an on-device job runs
                        enabled = !(ui.state == UiState.Processing && ui.processingMode == BackendProvider.MODE_INTERNAL),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete_model))
                    }
                    is ModelState.Downloading -> Unit
                    else -> FilledTonalButton(
                        onClick = onDownload,
                        enabled = ui.internalUnsupportedReason == null && !anyDownloading,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.download_model))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TagChip(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Display name from the catalog label; an unknown label shows the file name. */
@Composable
internal fun modelName(spec: ModelSpec): String = when (spec.label) {
    "hq3" -> stringResource(R.string.internal_model_name)
    "voc_ft" -> stringResource(R.string.model_name_voc_ft)
    "mdx3" -> stringResource(R.string.model_name_mdx3)
    "kara2" -> stringResource(R.string.model_karaoke_name)
    "kim2" -> stringResource(R.string.model_quality_name)
    else -> spec.name.removeSuffix(".onnx")
}

@Composable
internal fun modelTag(spec: ModelSpec): String? = when (spec.label) {
    "hq3" -> stringResource(R.string.tag_default)
    "voc_ft" -> stringResource(R.string.tag_best_vocals)
    "mdx3" -> stringResource(R.string.tag_fastest)
    "kara2" -> stringResource(R.string.tag_lead_vocal)
    "kim2" -> stringResource(R.string.tag_server_default)
    else -> null
}

@Composable
internal fun modelBestFor(spec: ModelSpec): String? = when (spec.label) {
    "hq3" -> stringResource(R.string.model_best_hq3)
    "voc_ft" -> stringResource(R.string.model_best_voc_ft)
    "mdx3" -> stringResource(R.string.model_best_mdx3)
    "kara2" -> stringResource(R.string.model_best_kara2)
    "kim2" -> stringResource(R.string.model_best_kim2)
    else -> null
}

/** "≈ N min for a 4-min track" from the measured ms per chunk at the current preset, or null if unmeasured. */
@Composable
internal fun speedHint(ui: MainUiState, spec: ModelSpec): String? {
    val ms = ui.modelSpeed[spec.name] ?: return null
    val overlap = PerformanceSettings.overlap(ui.performance.quality, spec.overlap)
    val chunks = PerformanceSettings.chunkCount(FOUR_MIN_SAMPLES, overlap, spec.nFft)
    val min = Math.round(chunks * ms / 60_000.0).toInt().coerceAtLeast(1)
    return pluralStringResource(R.plurals.model_speed_hint, min, min)
}

private const val FOUR_MIN_SAMPLES = 4L * 60 * 44_100
