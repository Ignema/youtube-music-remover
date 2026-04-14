package com.musicremover.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.musicremover.app.MainUiState
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.UiState
import com.musicremover.app.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onSettingsClick: () -> Unit, onHelpClick: () -> Unit, onPlay: (url: String, title: String) -> Unit = { _, _ -> }) {
    val ui by vm.ui.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MuremLogo(size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.app_name))
                        Spacer(Modifier.width(8.dp))
                        // Server status dot
                        val statusText = if (ui.serverConnected)
                            stringResource(R.string.server_online)
                        else stringResource(R.string.lost_connection)

                        val pulseTransition = rememberInfiniteTransition(label = "serverPulse")
                        val pulseAlpha by pulseTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(600),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "dotPulse",
                        )

                        val dotColor = when {
                            ui.serverChecking -> Color(0xFFBDBDBD).copy(alpha = pulseAlpha)
                            ui.serverConnected -> Color(0xFF4CAF50)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        }

                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                                .clickable {
                                    android.widget.Toast.makeText(context, statusText, android.widget.Toast.LENGTH_SHORT).show()
                                },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onHelpClick) {
                        Icon(Icons.Outlined.HelpOutline, "Help", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            // Mini progress banner when processing is minimized
            if (ui.state == UiState.Processing && ui.processingMinimized) {
                ProcessingBanner(ui, vm)
                Spacer(Modifier.height(16.dp))
                IdleContent(ui, vm)
            } else {
                AnimatedContent(
                    targetState = ui.state,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut()
                    },
                    label = "state",
                ) { state ->
                    when (state) {
                        UiState.Idle, UiState.Error -> IdleContent(ui, vm)
                        UiState.Processing -> ProcessingContent(ui, vm)
                        UiState.Done -> DoneContent(ui, vm, context, onPlay)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            if ((ui.state == UiState.Idle ||
                (ui.state == UiState.Processing && ui.processingMinimized)) && ui.history.isNotEmpty()) {
                HistorySection(ui.history, vm, onPlay)
            }
        }
    }

    // Video info bottom sheet
    if (ui.showInfoSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = vm::dismissInfoSheet,
        ) {
            val item = vm.currentInfoItem
            if (item != null) {
                ItemInfoSheet(item, ui)
                SheetActions(item = item, vm = vm, context = context)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdleContent(ui: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            // Take persistable permission so we can re-read this file later
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val name = try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else null
                    } else null
                }
            } catch (_: Exception) { null }
            val size = try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (idx >= 0) it.getLong(idx) else null
                    } else null
                }
            } catch (_: Exception) { null }
            // Copy to cache for immediate processing (original URI kept for redo)
            val cachedUri = try {
                val fileName = name ?: "video.mp4"
                val cacheFile = java.io.File(context.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                android.net.Uri.fromFile(cacheFile)
            } catch (_: Exception) { uri }
            vm.onFileSelected(cachedUri, name, size, uri.toString())
        }
    }

    // URL file picker (txt with URLs, one per line)
    val urlFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (urls.isNotEmpty()) {
                    vm.queueUrls(urls)
                }
            } catch (_: Exception) {
                vm.showTransientError("Failed to read URL file")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Tab content (URL is default)
        when (ui.inputTab) {
            0 -> UrlInputTab(ui, vm)
            1 -> FileInputTab(ui, vm, filePicker)
            2 -> BatchInputTab(ui, vm, urlFilePicker)
        }

        Spacer(Modifier.height(16.dp))

        // Model selector — opens bottom sheet
        FilledTonalButton(
            onClick = vm::toggleModelPicker,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                modelDisplayName(ui.selectedModel),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.change),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
            )
        }

        // Model picker bottom sheet
        if (ui.showModelPicker) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = vm::toggleModelPicker,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.ai_model), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.choose_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))

                    ModelOption(
                        name = stringResource(R.string.model_quality_name),
                        description = stringResource(R.string.model_quality_desc),
                        tag = stringResource(R.string.tag_default),
                        selected = ui.selectedModel == "Kim_Vocal_2.onnx",
                        onClick = { vm.onModelSelect("Kim_Vocal_2.onnx") },
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelOption(
                        name = stringResource(R.string.model_default_name),
                        description = stringResource(R.string.model_default_desc),
                        tag = stringResource(R.string.tag_fast),
                        selected = ui.selectedModel == "UVR-MDX-NET-Inst_HQ_3.onnx",
                        onClick = { vm.onModelSelect("UVR-MDX-NET-Inst_HQ_3.onnx") },
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelOption(
                        name = stringResource(R.string.model_karaoke_name),
                        description = stringResource(R.string.model_karaoke_desc),
                        tag = stringResource(R.string.tag_karaoke),
                        selected = ui.selectedModel == "UVR_MDXNET_KARA_2.onnx",
                        onClick = { vm.onModelSelect("UVR_MDXNET_KARA_2.onnx") },
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.premium_models), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.premium_models_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    ModelOption(
                        name = stringResource(R.string.model_melband_name),
                        description = stringResource(R.string.model_melband_desc),
                        tag = stringResource(R.string.tag_best),
                        selected = ui.selectedModel == "vocals_mel_band_roformer.ckpt",
                        onClick = { vm.onModelSelect("vocals_mel_band_roformer.ckpt") },
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelOption(
                        name = stringResource(R.string.model_bsroformer_name),
                        description = stringResource(R.string.model_bsroformer_desc),
                        tag = stringResource(R.string.tag_premium),
                        selected = ui.selectedModel == "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
                        onClick = { vm.onModelSelect("model_bs_roformer_ep_317_sdr_12.9755.ckpt") },
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.custom_model), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ui.customModel,
                        onValueChange = vm::onCustomModelChange,
                        label = { Text(stringResource(R.string.custom_model_hint)) },
                        placeholder = { Text("model_name.ckpt") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = vm::applyCustomModel,
                        enabled = ui.customModel.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.use_custom_model))
                    }
                }
            }
        }

        // Options row: audio-only + bitrate
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.FilterChip(
                selected = ui.audioOnly,
                onClick = { vm.setAudioOnly(!ui.audioOnly) },
                label = { Text(stringResource(R.string.audio_only)) },
                shape = RoundedCornerShape(12.dp),
            )
            // Bitrate selector
            listOf("128k", "192k", "320k").forEach { br ->
                androidx.compose.material3.FilterChip(
                    selected = ui.bitrate == br,
                    onClick = { vm.setBitrate(br) },
                    label = { Text(br) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Transient error banner
        AnimatedVisibility(visible = ui.snackError != null) {
            Column {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ui.snackError ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = vm::dismissSnackError) {
                            Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // CTA row with mode switcher
        val isQueueMode = ui.state == UiState.Processing && ui.processingMinimized
        val modeIcon = when (ui.inputTab) {
            1 -> Icons.Outlined.VideoFile
            2 -> Icons.Outlined.ContentPaste
            else -> Icons.Outlined.MusicOff
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Main CTA
            Button(
                onClick = vm::process,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(
                    if (isQueueMode) stringResource(R.string.add_to_queue)
                    else stringResource(R.string.remove_music),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // Mode switcher — cycles through tabs
            OutlinedButton(
                onClick = { vm.setInputTab((ui.inputTab + 1) % 3) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Icon(modeIcon, stringResource(R.string.tab_url), Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ProcessingContent(ui: MainUiState, vm: MainViewModel) {
    val animatedProgress by animateFloatAsState(
        targetValue = ui.progress / 100f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "progress",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "processing")

    // Pulsing alpha for the icon
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    // Rotating for the icon
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation",
    )

    // Bouncing scale
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )

    // Pick icon based on current step
    val stepIcon = when {
        ui.statusText.contains("Download", ignoreCase = true) -> Icons.Outlined.CloudDownload
        ui.statusText.contains("Separat", ignoreCase = true) -> Icons.Outlined.GraphicEq
        ui.statusText.contains("Merg", ignoreCase = true) -> Icons.Outlined.Merge
        ui.statusText.contains("Extract", ignoreCase = true) -> Icons.Outlined.GraphicEq
        ui.statusText.contains("Upload", ignoreCase = true) -> Icons.Outlined.CloudDownload
        else -> Icons.Outlined.MusicOff
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Animated step icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(100.dp),
        ) {
            // Background circle
            Card(
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f * pulseAlpha),
                ),
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale },
            ) {}

            // Icon
            AnimatedContent(
                targetState = stepIcon,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "stepIcon",
            ) { icon ->
                Icon(
                    icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(44.dp)
                        .alpha(pulseAlpha)
                        .graphicsLayer {
                            if (icon == Icons.Outlined.GraphicEq) {
                                // Waveform doesn't rotate, just pulses
                            } else {
                                rotationZ = rotation
                            }
                        },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Percentage
        Text(
            text = "${ui.progress}%",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(6.dp))

        // Status text
        AnimatedContent(
            targetState = ui.statusText,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "statusText",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(28.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.leave_app_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        var confirmAction by remember { mutableStateOf<String?>(null) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::minimizeProcessing,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.minimize), style = MaterialTheme.typography.labelMedium)
            }
            if (ui.queue.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmAction = "skip" },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.skip), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = { confirmAction = "cancelAll" },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.cancel_all), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = { confirmAction = "cancel" },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (confirmAction != null) {
            val message = when (confirmAction) {
                "skip" -> stringResource(R.string.confirm_skip)
                "cancelAll" -> stringResource(R.string.confirm_cancel_all)
                else -> stringResource(R.string.confirm_cancel)
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmAction = null },
                title = { Text(stringResource(R.string.are_you_sure)) },
                text = { Text(message) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        when (confirmAction) {
                            "skip" -> vm.cancelProcessing()
                            "cancelAll" -> vm.cancelAll()
                            else -> vm.cancelProcessing()
                        }
                        confirmAction = null
                    }) {
                        Text(
                            if (confirmAction == "cancelAll") stringResource(R.string.cancel_all)
                            else if (confirmAction == "skip") stringResource(R.string.skip)
                            else stringResource(R.string.cancel),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmAction = null }) {
                        Text(stringResource(R.string.nevermind))
                    }
                },
            )
        }

        // Queue display
        if (ui.queue.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.queue_count, ui.queue.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ui.queue.forEachIndexed { index, item ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Mini thumbnail for YouTube URLs
                        val ytId = item.url.let { url ->
                            Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(url)?.groupValues?.get(1)
                                ?: Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)?.groupValues?.get(1)
                        }
                        if (ytId != null) {
                            coil.compose.AsyncImage(
                                model = "https://img.youtube.com/vi/$ytId/default.jpg",
                                contentDescription = null,
                                modifier = Modifier
                                    .size(width = 36.dp, height = 26.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = if (item.fileUri != null) (item.fileName ?: "File") else item.url.takeLast(30),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.removeFromQueue(index) }) {
                            Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ProcessingBanner(ui: MainUiState, vm: MainViewModel) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { vm.expandProcessing() },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${ui.progress}% · ${ui.statusText}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (ui.queue.isNotEmpty()) {
                    Text(
                        "+${ui.queue.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { ui.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneContent(ui: MainUiState, vm: MainViewModel, context: android.content.Context, onPlay: (String, String) -> Unit) {
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        if (uri != null) vm.saveToUri(context, uri)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Animated check icon
        AnimatedVisibility(
            visible = true,
            enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) + fadeIn(),
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.vocals_extracted), style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(6.dp))

        Text(
            text = ui.statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(32.dp))

        // Play — primary action
        Button(
            onClick = {
                val url = vm.getStreamUrl() ?: return@Button
                onPlay(url, ui.statusText)
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.play), style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(10.dp))

        // Save and Share — side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { saveLauncher.launch(vm.getFilename()) },
                enabled = !ui.downloading,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Outlined.SaveAlt, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (ui.downloading) "Saving…" else "Save", style = MaterialTheme.typography.titleSmall)
            }
            OutlinedButton(
                onClick = { vm.shareResult(context) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Outlined.Share, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.share), style = MaterialTheme.typography.titleSmall)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Process another — tertiary
        FilledTonalButton(onClick = vm::reset, shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.process_another))
        }
    }
}

@Composable
private fun HistorySection(history: List<HistoryItem>, vm: MainViewModel, onPlay: (String, String) -> Unit) {
    var filterText by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searchExpanded) {
                // Expanded search field
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = { Text(stringResource(R.string.filter_history), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    trailingIcon = {
                        IconButton(onClick = {
                            filterText = ""
                            searchExpanded = false
                        }) {
                            Icon(Icons.Outlined.Close, "Close", Modifier.size(16.dp))
                        }
                    },
                )
            } else {
                // Normal header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.History, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (history.size > 3) {
                    IconButton(onClick = { searchExpanded = true }) {
                        Icon(
                            Icons.Outlined.Search, "Search",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(
                    Icons.Outlined.Refresh, "Refresh",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        val filtered = if (filterText.isBlank()) history else history.filter {
            it.filename.contains(filterText, ignoreCase = true) ||
            it.model.contains(filterText, ignoreCase = true) ||
            it.url.contains(filterText, ignoreCase = true) ||
            (it.ytTitle ?: "").contains(filterText, ignoreCase = true) ||
            (it.ytChannel ?: "").contains(filterText, ignoreCase = true)
        }

        Spacer(Modifier.height(10.dp))

        filtered.forEach { item ->
            key(item.jobId) {
                HistoryCard(item, vm, onPlay)
                Spacer(Modifier.height(8.dp))
            }
        }

        if (filtered.isEmpty() && filterText.isNotBlank()) {
            Text(
                stringResource(R.string.no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(item: HistoryItem, vm: MainViewModel, onPlay: (String, String) -> Unit) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    val dateStr = dateFormat.format(Date(item.timestamp))
    val serverUrl = vm.ui.collectAsState().value.serverUrl
    val hasUrl = item.url.isNotEmpty()

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        val url = "$serverUrl/api/download/${item.jobId}"
                        onPlay(url, item.filename.removeSuffix(".mp4"))
                    },
                    onLongClick = {
                        vm.hapticTick()
                        vm.showVideoInfoSheet(item)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play icon
            Icon(
                Icons.Outlined.PlayArrow, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.filename.removeSuffix(".mp4"),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(dateStr)
                    InfoChip(item.model.removeSuffix(".onnx").removeSuffix(".ckpt"))
                }
            }
            IconButton(onClick = {
                if (hasUrl) vm.onUrlChange(item.url)
                else vm.reprocessFromHistory(item)
            }) {
                Icon(Icons.Outlined.ContentPaste, "Reprocess", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}


@Composable
private fun VideoInfoSheet(ui: MainUiState) {
    Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        if (ui.loadingInfo) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
            }
        } else if (ui.videoInfoError) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.CloudDownload, null,
                    Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.video_info_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (ui.videoInfo != null) {
            val info = ui.videoInfo

            // Thumbnail
            if (info.thumbnail.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = info.thumbnail,
                    contentDescription = "Thumbnail",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.height(16.dp))
            }

            // Title
            Text(
                text = info.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(6.dp))

            // Channel
            if (info.channel.isNotEmpty()) {
                Text(
                    text = info.channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            // Stats row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (info.duration > 0) {
                    val mins = info.duration / 60
                    val secs = info.duration % 60
                    InfoChip("${mins}:${"%02d".format(secs)}")
                }
                if (info.view_count > 0) {
                    val views = when {
                        info.view_count >= 1_000_000 -> "${info.view_count / 1_000_000}M views"
                        info.view_count >= 1_000 -> "${info.view_count / 1_000}K views"
                        else -> "${info.view_count} views"
                    }
                    InfoChip(views)
                }
                if (info.upload_date.length == 8) {
                    val formatted = "${info.upload_date.substring(0, 4)}-${info.upload_date.substring(4, 6)}-${info.upload_date.substring(6, 8)}"
                    InfoChip(formatted)
                }
            }

            // Description
            if (info.description.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}


@Composable
private fun ItemInfoSheet(item: HistoryItem, ui: MainUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 16.dp),
    ) {
        // YouTube preview (thumbnail + title) if loading or loaded
        if (ui.loadingInfo) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        } else if (ui.videoInfo != null) {
            val info = ui.videoInfo
            if (info.thumbnail.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = info.thumbnail,
                    contentDescription = "Thumbnail",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (info.title.isNotEmpty()) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
            if (info.channel.isNotEmpty()) {
                Text(info.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            // Stats
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info.duration > 0) {
                    val mins = info.duration / 60
                    val secs = info.duration % 60
                    InfoChip("${mins}:${"%02d".format(secs)}")
                }
                if (info.view_count > 0) {
                    val views = when {
                        info.view_count >= 1_000_000 -> "${info.view_count / 1_000_000}M"
                        info.view_count >= 1_000 -> "${info.view_count / 1_000}K"
                        else -> "${info.view_count}"
                    }
                    InfoChip("$views views")
                }
                if (info.upload_date.length == 8) {
                    val formatted = "${info.upload_date.substring(0, 4)}-${info.upload_date.substring(4, 6)}-${info.upload_date.substring(6, 8)}"
                    InfoChip(formatted)
                }
            }
            if (info.description.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
        } else if (ui.videoInfoError) {
            // Error fetching — just skip, show item info below
        }

        // Always show item's own metadata
        InfoRow("Output", item.filename)
        Spacer(Modifier.height(8.dp))
        InfoRow("Model", item.model.removeSuffix(".onnx"))
        Spacer(Modifier.height(8.dp))
        val dateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        InfoRow("Processed", dateFormat.format(Date(item.timestamp)))

        if (item.sourceFileName != null) {
            Spacer(Modifier.height(8.dp))
            InfoRow("Source", item.sourceFileName)
        }
        if (item.sourceFileSize != null && item.sourceFileSize > 0) {
            Spacer(Modifier.height(8.dp))
            val sizeStr = when {
                item.sourceFileSize >= 1_073_741_824 -> "%.1f GB".format(item.sourceFileSize / 1_073_741_824.0)
                item.sourceFileSize >= 1_048_576 -> "%.1f MB".format(item.sourceFileSize / 1_048_576.0)
                item.sourceFileSize >= 1024 -> "%.1f KB".format(item.sourceFileSize / 1024.0)
                else -> "${item.sourceFileSize} B"
            }
            InfoRow("Size", sizeStr)
        }

        // Original video link
        if (item.url.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val context = LocalContext.current
            Text(
                text = stringResource(R.string.view_original),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(item.url))
                    context.startActivity(intent)
                },
            )
        }
    }
}

@Composable
private fun FileInfoSheet(item: HistoryItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
    ) {
        // Icon + title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.VideoFile, null,
                Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.file_details), style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(20.dp))

        // Source file name
        InfoRow("Source", item.sourceFileName ?: "Unknown")

        // File size
        if (item.sourceFileSize != null && item.sourceFileSize > 0) {
            Spacer(Modifier.height(8.dp))
            val sizeStr = when {
                item.sourceFileSize >= 1_073_741_824 -> "%.1f GB".format(item.sourceFileSize / 1_073_741_824.0)
                item.sourceFileSize >= 1_048_576 -> "%.1f MB".format(item.sourceFileSize / 1_048_576.0)
                item.sourceFileSize >= 1024 -> "%.1f KB".format(item.sourceFileSize / 1024.0)
                else -> "${item.sourceFileSize} B"
            }
            InfoRow("Size", sizeStr)
        }

        // Output file
        Spacer(Modifier.height(8.dp))
        InfoRow("Output", item.filename)

        // Model used
        Spacer(Modifier.height(8.dp))
        InfoRow("Model", item.model.removeSuffix(".onnx"))

        // Processed date
        Spacer(Modifier.height(8.dp))
        val dateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        InfoRow("Processed", dateFormat.format(Date(item.timestamp)))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}


@Composable
private fun modelDisplayName(model: String): String = when (model) {
    "Kim_Vocal_2.onnx" -> "${stringResource(R.string.model_quality_name)} · ${stringResource(R.string.tag_default)}"
    "UVR-MDX-NET-Inst_HQ_3.onnx" -> "${stringResource(R.string.model_default_name)} · ${stringResource(R.string.tag_fast)}"
    "UVR_MDXNET_KARA_2.onnx" -> "${stringResource(R.string.model_karaoke_name)} · ${stringResource(R.string.tag_karaoke)}"
    "vocals_mel_band_roformer.ckpt" -> "${stringResource(R.string.model_melband_name)} · ${stringResource(R.string.tag_best)}"
    "model_bs_roformer_ep_317_sdr_12.9755.ckpt" -> "${stringResource(R.string.model_bsroformer_name)} · ${stringResource(R.string.tag_premium)}"
    else -> model.removeSuffix(".onnx").removeSuffix(".ckpt")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelOption(
    name: String,
    description: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection indicator
            Icon(
                if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.Tune,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Card(
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


@Composable
private fun UrlInputTab(ui: MainUiState, vm: MainViewModel) {
    OutlinedTextField(
        value = ui.url,
        onValueChange = { vm.onUrlChange(it) },
        label = { Text(stringResource(R.string.url_label)) },
        placeholder = { Text(stringResource(R.string.url_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.loadingPreview) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                }
                if (ui.url.isNotEmpty()) {
                    IconButton(onClick = { vm.onUrlChange("") }) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp)) }
                } else {
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    IconButton(onClick = { clip.getText()?.text?.let { if (it.isNotEmpty()) vm.onUrlChange(it) } }) {
                        Icon(Icons.Outlined.ContentPaste, "Paste", Modifier.size(18.dp))
                    }
                }
            }
        },
    )
    val preview = ui.urlPreview
    if (preview != null && preview.thumbnail.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                coil.compose.AsyncImage(
                    model = preview.thumbnail, contentDescription = "Thumbnail",
                    modifier = Modifier.size(width = 80.dp, height = 45.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(preview.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (preview.channel.isNotEmpty()) {
                        Text(preview.channel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileInputTab(ui: MainUiState, vm: MainViewModel, filePicker: androidx.activity.result.ActivityResultLauncher<String>) {
    if (ui.selectedFileUri != null) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VideoFile, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(ui.selectedFileName ?: "Selected file", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = vm::clearFile) { Icon(Icons.Outlined.Close, "Remove", Modifier.size(18.dp)) }
            }
        }
    } else {
        OutlinedButton(
            onClick = { filePicker.launch("video/*") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Outlined.VideoFile, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.pick_video), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BatchInputTab(ui: MainUiState, vm: MainViewModel, urlFilePicker: androidx.activity.result.ActivityResultLauncher<String>) {
    OutlinedTextField(
        value = ui.batchText,
        onValueChange = vm::onBatchTextChange,
        label = { Text(stringResource(R.string.batch_label)) },
        placeholder = { Text(stringResource(R.string.batch_placeholder)) },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(120.dp),
        maxLines = 6,
    )
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        val count = ui.batchText.lines().count { it.trim().isNotEmpty() }
        Text(
            if (count > 0) "$count URL${if (count > 1) "s" else ""}" else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
        androidx.compose.material3.TextButton(onClick = { urlFilePicker.launch("text/*") }) {
            Text(stringResource(R.string.import_url_file), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SheetActions(item: HistoryItem, vm: MainViewModel, context: android.content.Context) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))

        // Share
        FilledTonalButton(
            onClick = {
                vm.shareByJobId(context, item.jobId, item.filename)
                vm.dismissInfoSheet()
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share))
        }

        Spacer(Modifier.height(8.dp))

        // Delete
        OutlinedButton(
            onClick = {
                vm.deleteHistoryItem(item)
                vm.dismissInfoSheet()
                vm.hapticTick()
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Outlined.Close, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.remove_from_history))
        }

        Spacer(Modifier.height(24.dp))
    }
}
