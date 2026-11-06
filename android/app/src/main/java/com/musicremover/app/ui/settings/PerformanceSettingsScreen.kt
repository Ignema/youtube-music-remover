package com.musicremover.app.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.Accelerator
import com.musicremover.app.processing.internal.PerformanceSettings

/** Performance (Internal mode): ORT threads, overlap preset and batch size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val totalMem = remember {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    }
    val perf = ui.performance
    SettingsScaffold(
        title = stringResource(R.string.settings_row_performance),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        if (ui.processingMode != BackendProvider.MODE_INTERNAL) {
            SettingsNote(stringResource(R.string.settings_internal_only_note))
            Spacer(Modifier.height(16.dp))
        }

        SectionLabel(stringResource(R.string.perf_threads))
        Spacer(Modifier.height(4.dp))
        SettingsNote(stringResource(R.string.perf_threads_desc))
        Spacer(Modifier.height(8.dp))
        val max = PerformanceSettings.maxThreads(cores)
        // A stored value above the device maximum is used as the maximum
        val shown = if (perf.threads > max) max else perf.threads
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = shown == 0,
                onClick = { vm.setPerfThreads(0) },
                label = { Text(stringResource(R.string.perf_threads_auto, PerformanceSettings.autoThreads(cores).toString())) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
            for (n in 1..max) {
                FilterChip(
                    selected = shown == n,
                    onClick = { vm.setPerfThreads(n) },
                    label = { Text(n.toString()) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        SectionLabel(stringResource(R.string.perf_quality))
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple(PerformanceSettings.QUALITY_FAST, R.string.settings_quality_fast, R.string.perf_fast_time),
            Triple(PerformanceSettings.QUALITY_BALANCED, R.string.settings_quality_balanced, R.string.perf_balanced_time),
            Triple(PerformanceSettings.QUALITY_BEST, R.string.settings_quality_best, R.string.perf_best_time),
        ).forEach { (value, label, time) ->
            ChoiceRow(
                label = stringResource(label),
                note = stringResource(time),
                selected = perf.quality == value,
                enabled = true,
                onClick = { vm.setPerfQuality(value) },
            )
        }
        Spacer(Modifier.height(4.dp))
        SettingsNote(stringResource(R.string.perf_quality_help))

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        SectionLabel(stringResource(R.string.perf_batch))
        Spacer(Modifier.height(8.dp))
        val batch2Ok = totalMem >= PerformanceSettings.BATCH2_MIN_TOTAL_MEM
        ChoiceRow(
            label = stringResource(R.string.perf_batch_1),
            note = null,
            selected = perf.batch == 1,
            enabled = true,
            onClick = { vm.setPerfBatch(1) },
        )
        ChoiceRow(
            label = stringResource(R.string.perf_batch_2),
            note = stringResource(if (batch2Ok) R.string.perf_batch_2_desc else R.string.perf_batch_2_needs_ram),
            selected = perf.batch == 2,
            enabled = batch2Ok,
            onClick = { vm.setPerfBatch(2) },
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        SectionLabel(stringResource(R.string.perf_accelerator))
        Spacer(Modifier.height(4.dp))
        SettingsNote(stringResource(R.string.perf_accelerator_desc))
        Spacer(Modifier.height(8.dp))
        val nnapiOk = Build.VERSION.SDK_INT >= Accelerator.NNAPI_MIN_SDK
        val shownAcc = Accelerator.resolve(perf.accelerator, Build.VERSION.SDK_INT)
        listOf(
            Triple(Accelerator.CPU, R.string.perf_acc_cpu, R.string.perf_acc_cpu_desc),
            Triple(Accelerator.WEBGPU, R.string.perf_acc_webgpu, R.string.perf_acc_webgpu_desc),
            Triple(Accelerator.NNAPI, R.string.perf_acc_nnapi, R.string.perf_acc_nnapi_desc),
            Triple(Accelerator.XNNPACK, R.string.perf_acc_xnnpack, R.string.perf_acc_xnnpack_desc),
        ).forEach { (acc, label, desc) ->
            val enabled = acc != Accelerator.NNAPI || nnapiOk
            ChoiceRow(
                label = stringResource(label),
                note = stringResource(if (enabled) desc else R.string.perf_acc_nnapi_needs_android),
                selected = shownAcc == acc,
                enabled = enabled,
                onClick = { vm.setPerfAccelerator(acc.pref) },
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(8.dp))
        SectionLabel(stringResource(R.string.perf_limit))
        Spacer(Modifier.height(4.dp))
        SettingsNote(stringResource(R.string.perf_limit_desc))
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple(PerformanceSettings.LIMIT_FULL, stringResource(R.string.perf_limit_full), stringResource(R.string.perf_limit_full_desc)),
            Triple(75, stringResource(R.string.perf_limit_pct, 75), stringResource(R.string.perf_limit_75_desc)),
            Triple(50, stringResource(R.string.perf_limit_pct, 50), stringResource(R.string.perf_limit_50_desc)),
        ).forEach { (value, label, note) ->
            ChoiceRow(
                label = label,
                note = note,
                selected = perf.limit == value,
                enabled = true,
                onClick = { vm.setPerfLimit(value) },
            )
        }
    }
}

@Composable
internal fun ChoiceRow(label: String, note: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            note?.let { SettingsNote(it) }
        }
    }
}
