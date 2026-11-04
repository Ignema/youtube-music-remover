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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.SystemUpdate
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.musicremover.app.HomeInput
import com.musicremover.app.MainUiState
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.UiState
import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.displayTitle
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.YtDlpUpdateState
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelState
import com.musicremover.app.processing.internal.YtDlpBanner
import com.musicremover.app.processing.internal.YtDlpErrors
import com.musicremover.app.ui.settings.formatMb
import com.musicremover.app.ui.settings.modelBestFor
import com.musicremover.app.ui.settings.modelName
import com.musicremover.app.ui.settings.modelTag
import com.musicremover.app.ui.settings.speedHint
import com.musicremover.app.ui.settings.ytDlpResultText
import androidx.compose.ui.res.pluralStringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: MainViewModel, onSettingsClick: () -> Unit, onHelpClick: () -> Unit, onPlay: (url: String, title: String, jobId: String?) -> Unit = { _, _, _ -> }) {
    val ui by vm.ui.collectAsState()
    // The .txt picker lives in IdleContent; drop a pending widget request once the input is off screen,
    // so it doesn't pop up later at an unrelated moment (e.g. after "Process another")
    androidx.compose.runtime.LaunchedEffect(ui.state, ui.processingMinimized, ui.pendingLinkImport) {
        if (ui.pendingLinkImport && !vm.isInputVisible(ui)) vm.consumeLinkImport()
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    // The yt-dlp card needs the version (first call unzips Python); only in Internal mode
    androidx.compose.runtime.LaunchedEffect(ui.processingMode) {
        if (ui.processingMode == BackendProvider.MODE_INTERNAL) vm.loadYtDlpVersion()
    }

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
                        val statusText = if (ui.processingMode == BackendProvider.MODE_INTERNAL) {
                            if (ui.serverConnected) stringResource(R.string.status_ready_on_device)
                            else ui.internalUnsupportedReason ?: stringResource(R.string.status_model_unavailable)
                        } else if (ui.serverConnected)
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
                (ui.state == UiState.Processing && ui.processingMinimized))) {
                if (ui.history.isNotEmpty()) {
                    HistorySection(ui.history, vm, onPlay)
                } else {
                    EmptyHistoryState()
                }
            }
        }
    }

    ModelNoticeDialog(vm)

    // Video info bottom sheet
    if (ui.showInfoSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = vm::dismissInfoSheet,
        ) {
            val item = vm.currentInfoItem
            if (item != null) {
                ItemInfoSheet(item, ui, onDevice = remember(item.jobId) { vm.isResultOnDevice(item.jobId) })
                SheetActions(item = item, vm = vm, context = context)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun IdleContent(ui: MainUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = GetMediaContent(),
    ) { uri ->
        if (uri != null) vm.importFile(uri, persist = true)
    }

    // Link file picker (.txt with links); they join the Link list for review
    val urlFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                vm.importLinkText(text)
            } catch (_: Exception) {
                vm.showTransientError(context.getString(R.string.link_import_failed))
            }
        }
    }
    // Widget Links button: open the import once
    androidx.compose.runtime.LaunchedEffect(ui.pendingLinkImport) {
        if (ui.pendingLinkImport) {
            vm.consumeLinkImport()
            urlFilePicker.launch("text/*")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // First-run / yt-dlp card above the input (Internal mode only); it only offers an update
        val banner = ui.ytDlpBanner
        if (banner != null && ui.processingMode == BackendProvider.MODE_INTERNAL) {
            YtDlpBannerCard(banner, ui, vm)
            Spacer(Modifier.height(16.dp))
        }

        // Swipeable input modes: Link, File
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = HomeInput.normalizeInputTab(ui.inputTab),
            pageCount = { 2 },
        )
        // Sync pager with ViewModel
        androidx.compose.runtime.LaunchedEffect(pagerState.settledPage) {
            if (pagerState.settledPage != ui.inputTab) {
                vm.setInputTab(pagerState.settledPage)
            }
        }
        androidx.compose.runtime.LaunchedEffect(ui.inputTab) {
            if (pagerState.currentPage != ui.inputTab) {
                pagerState.animateScrollToPage(ui.inputTab)
            }
        }

        // One card holds the mode tabs, the input pages and the options
        val homeColors = homeCardColors()
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = homeColors.card,
            border = androidx.compose.foundation.BorderStroke(1.dp, homeColors.cardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Less bottom padding: the chips' 48dp touch targets already add space under them
            Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                // Pill tabs; the thumb follows the pager (drag included), a tap animates the pager via ui.inputTab
                ModeTabs(
                    pagerState = pagerState,
                    trackColor = homeColors.field,
                    thumbColor = homeColors.thumb,
                    onSelect = { vm.setInputTab(it) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // The 16dp gap under the tabs is the pages' top padding (inside the pager), so the
                // list header's touch target can extend into it and still receive taps

                // Both pages get the height of the taller one, so switching Link/File doesn't move the content below.
                // Natural heights are measured on the inner Column (unaffected by the outer min height).
                val pageHeights = remember { androidx.compose.runtime.mutableStateListOf(0, 0) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                val pageMinHeight = with(density) { (pageHeights.maxOrNull() ?: 0).toDp() }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    verticalAlignment = Alignment.Top,
                    // Pages slide to the card edge; the spacing keeps the neighbour hidden at rest
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    pageSpacing = 16.dp,
                    // Keep the other page composed so its height is known before the first switch
                    beyondViewportPageCount = 1,
                ) { page ->
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = pageMinHeight)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { if (pageHeights[page] != it.height) pageHeights[page] = it.height }
                                .padding(top = 16.dp),
                        ) {
                            when (page) {
                                0 -> LinkInputTab(ui, vm, urlFilePicker, homeColors.field)
                                1 -> FileInputTab(ui, vm, filePicker, homeColors.field)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(
                    color = homeColors.cardBorder,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(4.dp))

                // Options footer: quiet chips, start-aligned
                OptionsRow(ui, vm, Modifier.padding(horizontal = 16.dp))
            }
        }

        // Model picker bottom sheet (unchanged)
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

                    val internalMode = ui.processingMode == BackendProvider.MODE_INTERNAL
                    if (!internalMode) {
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
                            selected = ui.selectedModel == ModelCatalog.HQ3,
                            onClick = { vm.onModelSelect(ModelCatalog.HQ3) },
                        )
                    } else {
                        // Catalog models only (Roformer stays server-only): name, tag, best for, size, state, speed
                        ui.internalModels.forEachIndexed { i, spec ->
                            if (i > 0) Spacer(Modifier.height(8.dp))
                            val state = if (ui.modelStates[spec.name] is ModelState.Ready) R.string.model_state_downloaded
                            else R.string.model_state_first_use
                            ModelOption(
                                name = modelName(spec),
                                description = modelBestFor(spec) ?: spec.primaryStem,
                                tag = modelTag(spec) ?: formatMb(spec.size),
                                selected = ui.selectedModel == spec.name,
                                onClick = { vm.onModelSelect(spec.name) },
                                details = listOfNotNull(formatMb(spec.size), stringResource(state), speedHint(ui, spec))
                                    .joinToString(" · "),
                            )
                        }
                    }
                    if (internalMode) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.internal_models_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
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
        }

        Spacer(Modifier.height(24.dp))

        // Transient error banner
        AnimatedVisibility(visible = ui.snackError != null) {
            // Informational messages ("Links added") use neutral colors; errors keep the error container
            val bannerBg = if (ui.snackIsInfo) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
            val bannerFg = if (ui.snackIsInfo) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
            Column {
                Card(
                    colors = CardDefaults.cardColors(containerColor = bannerBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ui.snackError ?: "",
                            color = bannerFg,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = vm::dismissSnackError) {
                            Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(16.dp), tint = bannerFg)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // CTA: dimmed with a hint while Process would find nothing (same rule as MainViewModel.process)
        val isQueueMode = ui.state == UiState.Processing && ui.processingMinimized
        val hasSource = HomeInput.pickSource(ui.inputTab, ui.links, ui.url, ui.selectedFileUri != null) != HomeInput.Source.None
        Button(
            onClick = { vm.hapticTick(); vm.process() },
            enabled = hasSource,
            shape = CircleShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                disabledContentColor = homeColors.ctaDisabledContent,
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Outlined.MusicOff, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            val linkCount = if (ui.inputTab == HomeInput.TAB_LINK) {
                (ui.links + HomeInput.extractLinks(ui.url)).distinct().size
            } else 0
            Text(
                if (isQueueMode) stringResource(R.string.add_to_queue)
                else if (linkCount >= 2) stringResource(R.string.remove_music_n, linkCount)
                else stringResource(R.string.remove_music),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        // The hint line is always laid out (hidden when there is a source), so Recent doesn't move
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                if (HomeInput.normalizeInputTab(ui.inputTab) == HomeInput.TAB_FILE) R.string.home_cta_hint_file
                else R.string.home_cta_hint_link,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .alpha(if (hasSource) 0f else 1f)
                .then(if (hasSource) Modifier.clearAndSetSemantics {} else Modifier),
        )
    }
}

/** Theme-derived colors for the Home input card (light, dark, black and dynamic schemes). */
private data class HomeCardColors(
    val card: Color,
    val cardBorder: Color,
    val field: Color,
    val thumb: Color,
    val ctaDisabledContent: Color,
)

@Composable
private fun homeCardColors(): HomeCardColors {
    val cs = MaterialTheme.colorScheme
    val light = cs.surface.luminance() > 0.5f
    return HomeCardColors(
        // Light: a white card on the tinted page; dark: the page surface, set off by the outline
        card = if (light) cs.surfaceContainerLowest else cs.surface,
        cardBorder = cs.outlineVariant.copy(alpha = if (light) 0.6f else 0.5f),
        field = cs.surfaceContainerHigh,
        // Dark: a tonal thumb, since the neutral containers are too close to the track to read
        thumb = if (light) cs.surfaceContainerLowest else cs.secondaryContainer,
        ctaDisabledContent = if (light) cs.onPrimary.copy(alpha = 0.9f) else cs.onSurface.copy(alpha = 0.6f),
    )
}

/** Link/File pill tabs. The thumb position is the pager's current page plus the drag fraction. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeTabs(
    pagerState: androidx.compose.foundation.pager.PagerState,
    trackColor: Color,
    thumbColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        Icons.Outlined.Link to R.string.home_mode_link,
        Icons.Outlined.VideoFile to R.string.home_mode_file,
    )
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, (tabs.size - 1).toFloat())
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(trackColor),
    ) {
        val tabWidth = maxWidth / tabs.size
        // Thumb: placed in the layout phase, so dragging the pager doesn't recompose it
        Box(
            modifier = Modifier
                .offset {
                    val p = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, (tabs.size - 1).toFloat())
                    androidx.compose.ui.unit.IntOffset((tabWidth.toPx() * p).roundToInt(), 0)
                }
                .width(tabWidth)
                .fillMaxHeight()
                .padding(4.dp)
                .shadow(1.dp, CircleShape)
                .background(thumbColor, CircleShape),
        )
        Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEachIndexed { index, (icon, label) ->
                val selected = pagerState.currentPage == index
                // 1 when the thumb is fully on this tab
                val nearness = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.colorScheme.onSurface,
                    nearness,
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The label is the accessible name; the icon is decorative
                    Icon(icon, null, Modifier.size(18.dp), tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = color,
                    )
                }
            }
        }
    }
}

/** Model, Audio only and bitrate: quiet chips in the input card's footer. */
@Composable
private fun OptionsRow(ui: MainUiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val chipBorder = androidx.compose.material3.AssistChipDefaults.assistChipBorder(
        enabled = true,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    )
    val chipColors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Model chip — opens bottom sheet
        androidx.compose.material3.AssistChip(
            onClick = vm::toggleModelPicker,
            label = { Text(modelShortName(ui.selectedModel), maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp)) },
            shape = CircleShape,
            border = chipBorder,
            colors = chipColors,
        )
        // Audio only
        androidx.compose.material3.FilterChip(
            selected = ui.audioOnly,
            onClick = { vm.setAudioOnly(!ui.audioOnly) },
            label = { Text(stringResource(R.string.audio_only)) },
            leadingIcon = if (ui.audioOnly) {
                { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
            } else null,
            shape = CircleShape,
            border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = ui.audioOnly,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // Bitrate — tap for dropdown
        var showBitrateMenu by remember { mutableStateOf(false) }
        Box {
            androidx.compose.material3.AssistChip(
                onClick = { showBitrateMenu = true },
                label = { Text(ui.bitrate) },
                trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
                shape = CircleShape,
                border = chipBorder,
                colors = chipColors,
            )
            androidx.compose.material3.DropdownMenu(
                expanded = showBitrateMenu,
                onDismissRequest = { showBitrateMenu = false },
            ) {
                Text(
                    stringResource(R.string.bitrate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                listOf("128k", "192k", "320k").forEach { br ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(br) },
                        onClick = { vm.setBitrate(br); showBitrateMenu = false },
                        trailingIcon = {
                            if (ui.bitrate == br) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
        // The audio-only format (M4A/MP3) lives on Settings → Output
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
private fun DoneContent(ui: MainUiState, vm: MainViewModel, context: android.content.Context, onPlay: (String, String, String?) -> Unit) {
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
                onPlay(url, ui.statusText, ui.jobId)
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
private fun HistorySection(history: List<HistoryItem>, vm: MainViewModel, onPlay: (String, String, String?) -> Unit) {
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
private fun HistoryCard(item: HistoryItem, vm: MainViewModel, onPlay: (String, String, String?) -> Unit) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    val dateStr = dateFormat.format(Date(item.timestamp))
    val hasUrl = item.url.isNotEmpty()

    val homeColors = homeCardColors()
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = homeColors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, homeColors.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        vm.playHistoryItem(item, onPlay)
                    },
                    onLongClick = {
                        vm.hapticTick()
                        vm.showVideoInfoSheet(item)
                    },
                )
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play icon on a tonal tile
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PlayArrow, null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            // The 7-day limit is the server's; a result stored on the device does not expire
            val onDevice = remember(item.jobId) { vm.isResultOnDevice(item.jobId) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle(item.filename),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Where the result lives
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Icon(
                            if (onDevice) Icons.Outlined.PhoneAndroid else Icons.Outlined.Cloud, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(if (onDevice) R.string.history_source_device else R.string.history_source_server),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoChip(dateStr)
                    InfoChip(item.model.removeSuffix(".onnx").removeSuffix(".ckpt"))
                    val ageMs = System.currentTimeMillis() - item.timestamp
                    val ageDays = ageMs / (1000 * 60 * 60 * 24)
                    if (ageDays >= 5 && !onDevice) {
                        val daysLeft = (7 - ageDays).coerceAtLeast(0)
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.align(Alignment.CenterVertically),
                        ) {
                            Text(
                                if (daysLeft <= 0) stringResource(R.string.expired)
                                else stringResource(R.string.expires_in, daysLeft),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
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
private fun EmptyHistoryState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Stylized equalizer icon (matches app logo vibe)
        Icon(
            Icons.Outlined.GraphicEq, null,
            modifier = Modifier.size(48.dp).alpha(0.3f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_history_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.empty_history_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
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
private fun ItemInfoSheet(item: HistoryItem, ui: MainUiState, onDevice: Boolean) {
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

        // Server expiry notice, only when the result is not stored on this device
        if (!onDevice) {
            val ageMs = System.currentTimeMillis() - item.timestamp
            val ageDays = ageMs / (1000 * 60 * 60 * 24)
            val daysLeft = (7 - ageDays).coerceAtLeast(0)
            Spacer(Modifier.height(8.dp))
            Text(
                if (daysLeft <= 0) stringResource(R.string.server_expired_note)
                else stringResource(R.string.server_expiry_note, daysLeft),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
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

@Composable
private fun modelShortName(model: String): String = when (model) {
    "Kim_Vocal_2.onnx" -> "Kim Vocal 2"
    "UVR-MDX-NET-Inst_HQ_3.onnx" -> "MDX-NET HQ3"
    "UVR_MDXNET_KARA_2.onnx" -> "Karaoke"
    "vocals_mel_band_roformer.ckpt" -> "Mel-Band"
    "model_bs_roformer_ep_317_sdr_12.9755.ckpt" -> "BS-Roformer"
    else -> model.removeSuffix(".onnx").removeSuffix(".ckpt").take(15)
}

/**
 * Home card for Internal mode: the first-run hint (plus the yt-dlp age when it is over 14 days), or the
 * Outdated line. Update now is a tap only; the card never starts an update by itself.
 */
@Composable
private fun YtDlpBannerCard(banner: YtDlpBanner.Banner, ui: MainUiState, vm: MainViewModel) {
    val update = ui.ytDlpUpdate
    val ageDays = when (banner) {
        is YtDlpBanner.Banner.FirstUse -> banner.ageDays?.takeIf { it > YtDlpErrors.OUTDATED_DAYS }
        is YtDlpBanner.Banner.Outdated -> banner.ageDays
    }
    androidx.compose.material3.ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (banner is YtDlpBanner.Banner.FirstUse) Icons.Outlined.Memory else Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (banner is YtDlpBanner.Banner.FirstUse) R.string.banner_first_use_title
                        else R.string.banner_outdated_title,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::dismissBanner) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.banner_close), Modifier.size(18.dp))
                }
            }
            Column(modifier = Modifier.padding(end = 12.dp)) {
                if (banner is YtDlpBanner.Banner.FirstUse) {
                    Text(
                        stringResource(R.string.banner_first_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    update == YtDlpUpdateState.Running -> {
                        Spacer(Modifier.height(12.dp))
                        // The library reports no byte progress
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.ytdlp_updating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    update is YtDlpUpdateState.Done -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            ytDlpResultText(update) ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    ageDays != null -> {
                        Spacer(Modifier.height(8.dp))
                        val n = ageDays.toInt()
                        Text(
                            pluralStringResource(R.plurals.ytdlp_banner_age, n, n),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ytDlpResultText(update)?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = vm::updateYtDlp, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.SystemUpdate, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ytdlp_update_now))
                        }
                    }
                }
            }
        }
    }
}

/** One-time license notice (Kim Vocal 2), opened by Process or a model card's Download. */
@Composable
internal fun ModelNoticeDialog(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val request = ui.noticeDialog ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = vm::dismissNotice,
        title = { Text(stringResource(R.string.model_notice_title)) },
        text = { Text(stringResource(R.string.model_notice_kim)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { vm.acceptNotice(request) }) { Text(stringResource(R.string.model_notice_download)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.model_notice_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelOption(
    name: String,
    description: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
    details: String? = null,
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
                details?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}


/** Link mode: one link in the field (as before), or a list once several links are pasted, shared or imported. */
@Composable
private fun LinkInputTab(ui: MainUiState, vm: MainViewModel, urlFilePicker: androidx.activity.result.ActivityResultLauncher<String>, fieldColor: Color) {
    if (ui.links.isEmpty()) {
        SingleLinkField(ui, vm, fieldColor)
    } else {
        LinkList(ui, vm, fieldColor)
    }
    Spacer(Modifier.height(LinkFieldButtonsGap))
    // Small pill buttons under the field: Paste while the field is empty, Add another once it holds a link
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pillPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
        if (ui.url.isEmpty()) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            FilledTonalButton(
                onClick = {
                    val text = clip.getText()?.text.orEmpty()
                    if (ui.links.isEmpty()) {
                        if (text.isNotEmpty()) vm.onUrlChange(text)
                    } else {
                        val found = HomeInput.extractLinks(text)
                        if (found.isNotEmpty()) vm.addLinks(found) else if (text.isNotEmpty()) vm.onUrlChange(text)
                    }
                },
                shape = CircleShape,
                contentPadding = pillPadding,
            ) {
                Icon(Icons.Outlined.ContentPaste, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.paste), style = MaterialTheme.typography.labelLarge)
            }
        } else if (HomeInput.extractLinks(ui.url).isNotEmpty()) {
            FilledTonalButton(
                onClick = vm::addFieldLinkToList,
                shape = CircleShape,
                contentPadding = pillPadding,
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.link_add_another), style = MaterialTheme.typography.labelLarge)
            }
        }
        OutlinedButton(
            onClick = { urlFilePicker.launch("text/*") },
            shape = CircleShape,
            contentPadding = pillPadding,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Icon(Icons.Outlined.UploadFile, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.link_import_txt), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// The File drop zone matches the Link page's field + gap + pill row (40dp pills in a 48dp touch target)
private val LinkFieldHeight = 56.dp
private val LinkFieldButtonsGap = 8.dp
private val FileDropZoneHeight = LinkFieldHeight + LinkFieldButtonsGap + 48.dp
// Trimmed from the list header's 48dp touch target in layout. The top part overlaps the page's
// 16dp top padding, the bottom part the equal spacer under the header, so the full target stays tappable.
private val ListHeaderTrimTop = 12.dp
private val ListHeaderTrimBottom = 4.dp

@Composable
private fun filledFieldColors(fieldColor: Color) = androidx.compose.material3.TextFieldDefaults.colors(
    focusedContainerColor = fieldColor,
    unfocusedContainerColor = fieldColor,
    disabledContainerColor = fieldColor,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

@Composable
private fun LinkList(ui: MainUiState, vm: MainViewModel, fieldColor: Color) {
    // Header on the 16dp content edge: the label starts at the edge, "Clear all" text ends at it
    // (the button's 12dp text padding sits in the card padding). The row reports less height than
    // its 48dp touch target, so the gap under the tabs keeps the card's 16dp rhythm.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val top = ListHeaderTrimTop.roundToPx()
                val bottom = ListHeaderTrimBottom.roundToPx()
                layout(p.width, (p.height - top - bottom).coerceAtLeast(0)) { p.place(0, -top) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.link_count, ui.links.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::clearLinks, modifier = Modifier.offset(x = 12.dp)) {
            Text(stringResource(R.string.link_clear_all), style = MaterialTheme.typography.labelLarge)
        }
    }
    Spacer(Modifier.height(ListHeaderTrimBottom))
    // A plain Column: the page already sits in the screen's verticalScroll
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ui.links.forEachIndexed { i, link ->
            key(link) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = fieldColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            HomeInput.linkLabel(link),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.removeLink(i) }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.link_remove), Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // Next link; IME Done or the "Add another link" pill below moves it into the list
    androidx.compose.material3.TextField(
        value = ui.url,
        onValueChange = { vm.onUrlChange(it) },
        placeholder = { Text(stringResource(R.string.link_add_another)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = filledFieldColors(fieldColor),
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.addFieldLinkToList() }),
        leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(20.dp)) },
        trailingIcon = if (ui.url.isNotEmpty()) {
            {
                IconButton(onClick = { vm.onUrlChange("") }) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp)) }
            }
        } else null,
    )
}

/** The single-link field and its preview card. */
@Composable
private fun SingleLinkField(ui: MainUiState, vm: MainViewModel, fieldColor: Color) {
    androidx.compose.material3.TextField(
        value = ui.url,
        onValueChange = { vm.onUrlChange(it) },
        placeholder = { Text(stringResource(R.string.home_link_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = filledFieldColors(fieldColor),
        modifier = Modifier.fillMaxWidth().heightIn(min = LinkFieldHeight),
        leadingIcon = { Icon(Icons.Outlined.Link, null, Modifier.size(20.dp)) },
        trailingIcon = if (ui.loadingPreview || ui.url.isNotEmpty()) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.loadingPreview) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (ui.url.isNotEmpty()) {
                        IconButton(onClick = { vm.onUrlChange("") }) { Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp)) }
                    }
                }
            }
        } else null,
    )
    val preview = ui.urlPreview
    if (preview != null && preview.thumbnail.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = fieldColor),
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
private fun FileInputTab(ui: MainUiState, vm: MainViewModel, filePicker: androidx.activity.result.ActivityResultLauncher<String>, fieldColor: Color) {
    if (ui.selectedFileUri != null) {
        val context = LocalContext.current
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = fieldColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.VideoFile, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(ui.selectedFileName ?: "Selected file", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val size = ui.selectedFileSize
                    if (size != null && size > 0) {
                        Text(
                            android.text.format.Formatter.formatShortFileSize(context, size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = vm::clearFile) { Icon(Icons.Outlined.Close, "Remove", Modifier.size(18.dp)) }
            }
        }
    } else {
        // Dashed drop zone; the whole zone opens the picker
        val dashColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(FileDropZoneHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(fieldColor)
                .drawBehind {
                    val stroke = 1.5.dp.toPx()
                    drawRoundRect(
                        color = dashColor,
                        topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx() - stroke / 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                        ),
                    )
                }
                .clickable(role = Role.Button) { filePicker.launch("*/*") }
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.UploadFile, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_file_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.home_file_formats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** System picker for one video or audio file (GetContent takes one MIME type; the extra adds the second). */
private class GetMediaContent : androidx.activity.result.contract.ActivityResultContracts.GetContent() {
    override fun createIntent(context: android.content.Context, input: String): android.content.Intent =
        super.createIntent(context, input)
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
}

@Composable
private fun SheetActions(item: HistoryItem, vm: MainViewModel, context: android.content.Context) {
    val ui by vm.ui.collectAsState()
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(16.dp))

        // Share
        FilledTonalButton(
            onClick = {
                vm.shareByJobId(context, item.jobId, item.filename)
            },
            enabled = !ui.sharing,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (ui.sharing) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (ui.sharing) stringResource(R.string.sharing) else stringResource(R.string.share))
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
