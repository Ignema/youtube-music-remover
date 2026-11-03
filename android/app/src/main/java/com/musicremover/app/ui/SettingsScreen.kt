package com.musicremover.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.UiState
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.ModelFailure
import com.musicremover.app.processing.internal.ModelState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onPermissionsClick: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Appearance ---
            SectionLabel(stringResource(R.string.appearance))
            Spacer(Modifier.height(12.dp))

            // Theme selector
            var showThemePicker by remember { mutableStateOf(false) }
            val themeLabel = when (ui.themeMode) {
                "light" -> stringResource(R.string.light)
                "dark" -> stringResource(R.string.dark)
                "black" -> stringResource(R.string.black_oled)
                else -> stringResource(R.string.system)
            }
            SettingsRow(
                label = stringResource(R.string.theme),
                value = themeLabel,
                onClick = { showThemePicker = true },
            )

            if (showThemePicker) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showThemePicker = false },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        listOf(
                            "system" to stringResource(R.string.system_default),
                            "light" to stringResource(R.string.light),
                            "dark" to stringResource(R.string.dark),
                            "black" to stringResource(R.string.black_oled),
                        ).forEach { (value, label) ->
                            PickerOption(
                                label = label,
                                selected = ui.themeMode == value,
                                onClick = { vm.setThemeMode(value); showThemePicker = false },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Dynamic color toggle
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.material_you), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.material_you_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = ui.dynamicColor,
                        onCheckedChange = { vm.setDynamicColor(it) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Language selector
            var showLangPicker by remember { mutableStateOf(false) }
            val sysDefault = stringResource(R.string.system_default)
            val languages = listOf(
                "" to sysDefault,
                "en" to "English",
                "ar" to "العربية",
                "fr" to "Français",
                "es" to "Español",
                "de" to "Deutsch",
                "tr" to "Türkçe",
                "id" to "Bahasa Indonesia",
                "ms" to "Bahasa Melayu",
                "pt" to "Português",
                "it" to "Italiano",
                "ru" to "Русский",
                "ja" to "日本語",
                "ko" to "한국어",
                "zh" to "中文",
                "hi" to "हिन्दी",
                "ur" to "اردو",
            )
            val currentLangName = languages.firstOrNull { it.first == ui.language }?.second ?: stringResource(R.string.system_default)
            SettingsRow(
                label = stringResource(R.string.language),
                value = currentLangName,
                onClick = { showLangPicker = true },
            )

            if (showLangPicker) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showLangPicker = false },
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 32.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        languages.forEach { (code, name) ->
                            PickerOption(
                                label = name,
                                selected = ui.language == code,
                                onClick = { vm.setLanguage(code); showLangPicker = false },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Widget theme
            var showWidgetThemePicker by remember { mutableStateOf(false) }
            val widgetThemeLabel = when (ui.widgetTheme) {
                "light" -> stringResource(R.string.light)
                "dark" -> stringResource(R.string.dark)
                "transparent" -> stringResource(R.string.widget_transparent)
                else -> "Orange"
            }
            SettingsRow(
                label = stringResource(R.string.widget_theme),
                value = widgetThemeLabel,
                onClick = { showWidgetThemePicker = true },
            )

            if (showWidgetThemePicker) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showWidgetThemePicker = false },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                        Text(stringResource(R.string.widget_theme), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        listOf(
                            "orange" to "Orange",
                            "light" to stringResource(R.string.light),
                            "dark" to stringResource(R.string.dark),
                            "transparent" to stringResource(R.string.widget_transparent),
                        ).forEach { (value, label) ->
                            PickerOption(
                                label = label,
                                selected = ui.widgetTheme == value,
                                onClick = { vm.setWidgetTheme(value); showWidgetThemePicker = false },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // --- Processing mode ---
            SectionLabel(stringResource(R.string.processing_mode))
            Spacer(Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Triple(BackendProvider.MODE_INTERNAL, Icons.Outlined.Memory, R.string.mode_internal),
                    Triple(BackendProvider.MODE_TERMUX, Icons.Outlined.PhoneAndroid, R.string.local_termux),
                    Triple(BackendProvider.MODE_SERVER, Icons.Outlined.Cloud, R.string.mode_server),
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
                InternalModelCard(vm)
            }

            AnimatedVisibility(visible = ui.processingMode == BackendProvider.MODE_TERMUX) {
                Column {
                    Text(
                        stringResource(R.string.on_device_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // --- History & Cache ---
            SectionLabel(stringResource(R.string.history_and_cache))
            Spacer(Modifier.height(8.dp))

            if (ui.history.isNotEmpty()) {
                Text(
                    stringResource(R.string.processed_videos, ui.history.size, vm.getCacheSizeFormatted()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::clearHistory,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.clear_history))
                    }
                    OutlinedButton(
                        onClick = vm::clearCache,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.clear_cache))
                    }
                }
                Spacer(Modifier.height(8.dp))
                val exportContext = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton(
                    onClick = { vm.exportHistory(exportContext) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.export_history))
                }
            } else {
                Text(
                    stringResource(R.string.no_history),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // --- Permissions ---
            SectionLabel(stringResource(R.string.permissions))
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.permissions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onPermissionsClick,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.manage_permissions))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // --- About ---
            SectionLabel(stringResource(R.string.about))
            Spacer(Modifier.height(8.dp))

            var tapCount by remember { mutableStateOf(0) }
            val aboutContext = androidx.compose.ui.platform.LocalContext.current

            Text(
                "Murem v3.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    tapCount++
                    if (tapCount >= 5) {
                        tapCount = 0
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://quran.com/31/6"),
                        )
                        aboutContext.startActivity(intent)
                    }
                },
            )
            Text(
                stringResource(R.string.about_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    // Termux operation bottom sheet
    val termuxOp = ui.termuxOperation
    if (termuxOp != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = vm::dismissTermuxOperation,
        ) {
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
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    termuxOp,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (!ui.termuxServerOnline && termuxOp.contains("Switch back")) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Come back here when Termux finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
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
            Text(
                stringResource(R.string.first_time_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun InternalModelCard(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val modelState = ui.modelState
    val total = formatMb(ui.modelSizeBytes)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ui.internalUnsupportedReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.internal_model_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            val statusText = when (modelState) {
                is ModelState.Ready -> stringResource(R.string.internal_model_downloaded, formatMb(modelState.sizeBytes))
                is ModelState.Downloading -> stringResource(
                    R.string.internal_model_downloading,
                    formatMb(modelState.bytes),
                    formatMb(if (modelState.total > 0) modelState.total else ui.modelSizeBytes),
                )
                is ModelState.Failed -> when (val f = modelState.failure) {
                    is ModelFailure.NoSpace -> stringResource(R.string.internal_no_space, (f.neededBytes / 1_000_000).toInt())
                    ModelFailure.Corrupt -> stringResource(R.string.internal_model_corrupt)
                    ModelFailure.Network -> stringResource(R.string.internal_model_failed_network)
                    ModelFailure.Other -> stringResource(R.string.internal_processing_failed)
                }
                ModelState.NotDownloaded -> stringResource(R.string.internal_model_not_downloaded, total)
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (modelState is ModelState.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (modelState is ModelState.Downloading) {
                Spacer(Modifier.height(8.dp))
                val totalBytes = if (modelState.total > 0) modelState.total else ui.modelSizeBytes
                LinearProgressIndicator(
                    progress = { (modelState.bytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            when (modelState) {
                is ModelState.Ready -> OutlinedButton(
                    onClick = vm::deleteModel,
                    enabled = ui.state != UiState.Processing,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.delete_model))
                }
                is ModelState.Downloading -> Unit
                else -> FilledTonalButton(
                    onClick = vm::downloadModel,
                    enabled = ui.internalUnsupportedReason == null,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.download_model))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.internal_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Text(
                stringResource(R.string.termux_not_found_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
