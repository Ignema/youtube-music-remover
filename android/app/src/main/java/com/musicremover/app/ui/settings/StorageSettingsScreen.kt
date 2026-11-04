package com.musicremover.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R

/** History & storage: count and cache size, Clear history (confirm), Clear cache, Export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    // Re-read after Clear cache
    var cacheVersion by remember { mutableStateOf(0) }
    val cacheSize = remember(cacheVersion, ui.history.size) { vm.getCacheSizeFormatted() }
    SettingsScaffold(
        title = stringResource(R.string.settings_row_storage),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        if (ui.history.isNotEmpty()) {
            Text(
                stringResource(R.string.processed_videos, ui.history.size, cacheSize),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { confirmClear = true }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_history))
                }
                OutlinedButton(
                    onClick = {
                        vm.clearCache()
                        cacheVersion++
                    },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.clear_cache))
                }
                OutlinedButton(onClick = { vm.exportHistory(context) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.export_history))
                }
            }
        } else {
            Text(
                stringResource(R.string.no_history),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history_title),
            text = stringResource(R.string.clear_history_text),
            confirmLabel = stringResource(R.string.clear_history),
            onConfirm = {
                confirmClear = false
                vm.clearHistory()
            },
            onDismiss = { confirmClear = false },
        )
    }
}
