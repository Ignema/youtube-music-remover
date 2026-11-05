package com.musicremover.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.musicremover.app.ui.adaptive.AdaptiveLayout
import com.musicremover.app.ui.adaptive.HorizontalHinge
import com.musicremover.app.ui.adaptive.LocalWindowLayout
import com.musicremover.app.ui.adaptive.PlayerMode
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
    jobId: String? = null,
    loadServerWaveform: suspend (String) -> List<Float>? = { null },
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
    }

    // A playback failure (missing or unreadable file, unsupported format): shown over the video with Retry
    var playbackError by remember { mutableStateOf<androidx.media3.common.PlaybackException?>(null) }
    val retryPlayback: () -> Unit = {
        playbackError = null
        player.prepare()
        player.play()
    }

    // Keep the screen on only while the video is actually playing (paused/ended lets it sleep again)
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(player, hostView) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                hostView.keepScreenOn = playing
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("MuremPlayer", "Playback failed: ${error.errorCodeName}", error)
                playbackError = error
            }
        }
        player.addListener(listener)
        hostView.keepScreenOn = player.isPlaying || player.playWhenReady
        onDispose {
            player.removeListener(listener)
            hostView.keepScreenOn = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var looping by rememberSaveable { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewTime by remember { mutableStateOf("") }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    val fr = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    var rootHasFocus by remember { mutableStateOf(false) }
    val urlState by rememberUpdatedState(url)
    val titleState by rememberUpdatedState(title)
    val onBackState by rememberUpdatedState(onBack)

    // A restored loop flag (recreation) must reach the new player too
    LaunchedEffect(player) {
        if (looping) player.repeatMode = Player.REPEAT_MODE_ONE
    }

    // Orientation for fullscreen (I4: the only place in the app that sets it). Phones keep the
    // landscape lock for wide videos; tablets, foldables and multi-window leave the orientation alone.
    val lock = AdaptiveLayout.lockLandscapeInFullscreen(
        LocalConfiguration.current.smallestScreenWidthDp,
        activity?.isInMultiWindowMode == true,
        videoAspectRatio,
    )
    LaunchedEffect(isFullscreen, videoAspectRatio, lock) {
        // Release the lock whenever it no longer applies (fullscreen off, or e.g. entering multi-window
        // mid-fullscreen), otherwise SENSOR_LANDSCAPE from before would stay requested
        if (!isFullscreen || !lock) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // Tap-to-toggle overlay
    var showOverlay by remember { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(if (isSeeking) 0 else 120),
        label = "waveProgress",
    )

    LaunchedEffect(player) {
        while (true) {
            if (player.duration > 0) {
                durationMs = player.duration
                positionMs = player.currentPosition
                if (!isSeeking) {
                    progress = (player.currentPosition.toFloat() / player.duration).coerceIn(0f, 1f)
                }
            }
            isPlaying = player.isPlaying
            isBuffering = player.playbackState == Player.STATE_BUFFERING
            val format = player.videoFormat
            if (format != null && format.width > 0 && format.height > 0) {
                videoAspectRatio = format.width.toFloat() / format.height.toFloat()
            }
            delay(80)
        }
    }

    // Auto-hide overlay
    LaunchedEffect(showOverlay) {
        if (showOverlay) {
            delay(3000)
            showOverlay = false
        }
    }

    // Waveform — try the server's pre-computed one when streaming from the server, else decode the
    // media itself (local content:// file or server URL)
    val amplitudes = remember { mutableStateListOf<Float>() }
    LaunchedEffect(url) {
        if (jobId != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            val wave = loadServerWaveform(jobId)
            if (!wave.isNullOrEmpty()) {
                amplitudes.clear()
                amplitudes.addAll(wave)
                return@LaunchedEffect
            }
        }
        // Fall back to local streaming extraction
        streamWaveform(context, url, 150).collect { wave ->
            amplitudes.clear()
            amplitudes.addAll(wave)
        }
    }

    // I3: the video and the controls move between layouts as movable content, so a rotation, resize or
    // fold keeps the same PlayerView, waveform and volume state. They capture only remembered objects and
    // State delegates; per-layout values are parameters.
    val video = remember {
        movableContentOf { modifier: Modifier, mode: PlayerMode ->
            VideoArea(
                modifier = modifier,
                mode = mode,
                player = player,
                isBuffering = isBuffering,
                showOverlay = showOverlay,
                isPlaying = isPlaying,
                isFullscreen = isFullscreen,
                seekPreviewTime = if (isSeeking) seekPreviewTime else "",
                url = urlState,
                playbackError = playbackError,
                onRetry = retryPlayback,
                onTap = {
                    if (showOverlay) {
                        if (player.isPlaying) player.pause() else player.play()
                    }
                    showOverlay = !showOverlay
                    fr.requestFocus()
                },
                onBack = { onBackState() },
                onToggleFullscreen = { isFullscreen = !isFullscreen },
            )
        }
    }
    val panel = remember {
        movableContentOf { contentModifier: Modifier ->
            ControlsPanel(
                modifier = contentModifier,
                player = player,
                title = titleState,
                positionMs = positionMs,
                durationMs = durationMs,
                amplitudes = amplitudes,
                progress = animatedProgress,
                looping = looping,
                isPlaying = isPlaying,
                onSeek = { seekPos ->
                    isSeeking = true
                    progress = seekPos
                    val seekMs = (seekPos * player.duration).toLong()
                    seekPreviewTime = formatTime(seekMs)
                    player.seekTo(seekMs)
                    isSeeking = false
                    seekPreviewTime = ""
                },
                onToggleLoop = {
                    looping = !looping
                    player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                },
            )
        }
    }

    val wl = LocalWindowLayout.current
    val density = LocalDensity.current
    var rootTopInWindow by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { fr.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { rootTopInWindow = it.positionInWindow().y }
            .focusRequester(fr)
            .onFocusChanged { rootFocused = it.isFocused; rootHasFocus = it.hasFocus }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        true
                    }
                    // Arrows seek only while the root holds focus; otherwise they move focus between buttons
                    Key.DirectionLeft -> rootFocused.also {
                        if (it) player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                    }
                    Key.DirectionRight -> rootFocused.also {
                        if (it) player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration))
                    }
                    Key.Escape -> isFullscreen.also { if (it) isFullscreen = false }
                    else -> false
                }
            },
    ) {
        val hingeTop = wl.hingeWindowTopPx
        val hingeBottom = wl.hingeWindowBottomPx
        val hinge = if (hingeTop != null && hingeBottom != null) {
            HorizontalHinge(hingeTop - rootTopInWindow, hingeBottom - rootTopInWindow)
        } else null
        val mode = AdaptiveLayout.playerMode(maxWidth.value, maxHeight.value, wl.tabletop, hinge, density.density, isFullscreen)
        val panelWidthDp = AdaptiveLayout.playerPanelWidthDp(maxWidth.value)
        // A mode switch can remove the focused button (the panel leaves in FULLSCREEN), which leaves nothing
        // focused; give focus back to the root so Space and Escape keep working
        LaunchedEffect(mode) { if (!rootHasFocus) fr.requestFocus() }
        when (mode) {
            PlayerMode.STACKED -> Column(modifier = Modifier.fillMaxSize()) {
                // Video area — no bottom corners (bottom card overlaps)
                video(Modifier.fillMaxWidth().weight(1f), mode)

                Card(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = -24.dp.toPx() },
                ) {
                    val max = AdaptiveLayout.playerStackedPanelMaxDp(wl.width)
                    panel(
                        if (max != null) Modifier.align(Alignment.CenterHorizontally).widthIn(max = max.dp) else Modifier,
                    )
                }
            }
            // Bottom panel hidden
            PlayerMode.FULLSCREEN -> video(Modifier.fillMaxSize(), mode)
            PlayerMode.SIDE_BY_SIDE -> Row(modifier = Modifier.fillMaxSize()) {
                // The panel pads its End for a cutout or side navigation bar; add that inset to its width
                // so the controls keep the full panel width instead of being clipped
                val endInset = WindowInsets.safeDrawing.only(WindowInsetsSides.End).asPaddingValues()
                    .calculateEndPadding(LocalLayoutDirection.current)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    video(Modifier.fillMaxSize(), mode)
                }
                Card(
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier
                        .width(panelWidthDp.dp + endInset)
                        .fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // No extra padding here: ControlsPanel already pads by 20/16, and a second
                            // 20dp would clip the 272dp controls row in a 320dp panel
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        panel(Modifier)
                    }
                }
            }
            PlayerMode.TABLETOP -> {
                val h = checkNotNull(hinge)
                val topDp = with(density) { h.topPx.toDp() }
                val bottomDp = with(density) { h.bottomPx.toDp() }
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth().height(topDp)) {
                        video(Modifier.fillMaxSize(), mode)
                    }
                    Spacer(Modifier.height(bottomDp - topDp))
                    Card(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = AdaptiveLayout.PLAYER_PANEL_MAX_STACKED_DP.dp)
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.navigationBars),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            panel(Modifier)
                        }
                    }
                }
            }
        }
    }
}

/** Today's black video box: PlayerView, spinner, tap overlay, back / fullscreen / external buttons, seek preview. */
@Composable
private fun VideoArea(
    modifier: Modifier,
    mode: PlayerMode,
    player: ExoPlayer,
    isBuffering: Boolean,
    showOverlay: Boolean,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    seekPreviewTime: String,
    url: String,
    playbackError: androidx.media3.common.PlaybackException?,
    onRetry: () -> Unit,
    onTap: () -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val context = LocalContext.current
    // Outside STACKED a landscape cutout or side navigation bar could cover the buttons
    val buttonInsets = if (mode != PlayerMode.STACKED) {
        Modifier.windowInsetsPadding(
            WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal),
        )
    } else Modifier
    Box(
        modifier = modifier
            .background(Color.Black)
            // Not a Tab stop (no visible indication there); taps and clicks still work
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Main video
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Playback failed: what happened and what to do, instead of a black box
        if (playbackError != null) {
            PlaybackErrorOverlay(playbackError, url, onRetry)
        }

        // Buffering spinner
        if (isBuffering && playbackError == null) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 3.dp,
            )
        }

        // Tap overlay — play/pause icon
        androidx.compose.animation.AnimatedVisibility(
            visible = showOverlay && !isBuffering,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        // Back button — below status bar
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .then(buttonInsets)
                .padding(
                    start = 8.dp,
                    top = 40.dp, // Clear status bar
                )
                .size(40.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f)),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack, "Back",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }

        // Fullscreen toggle — bottom right
        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .then(buttonInsets)
                .padding(end = 8.dp, bottom = 36.dp)
                .size(40.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f)),
        ) {
            Icon(
                if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                "Fullscreen",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }

        // Open in external player — bottom left
        IconButton(
            onClick = {
                openInExternalPlayer(context, Uri.parse(url))
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .then(buttonInsets)
                .padding(start = 8.dp, bottom = 36.dp)
                .size(40.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f)),
        ) {
            Icon(
                Icons.Outlined.OpenInNew, "External player",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        // Seek preview time (shown while dragging waveform)
        if (seekPreviewTime.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(seekPreviewTime, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Today's bottom-card content: title and time, waveform, controls row, volume menu. */
@Composable
private fun ControlsPanel(
    // Goes first on the panel's inner Column (the STACKED caller passes the max-width modifiers)
    modifier: Modifier,
    player: ExoPlayer,
    title: String,
    positionMs: Long,
    durationMs: Long,
    amplitudes: List<Float>,
    progress: Float,
    looping: Boolean,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    onToggleLoop: () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Title + time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                formatTime(positionMs) + " / " + formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Waveform or loading
        if (amplitudes.isEmpty()) {
            // Placeholder bars while loading
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            WaveformView(
                amplitudes = amplitudes,
                progress = progress,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
                playedColor = MaterialTheme.colorScheme.primary,
                unplayedColor = MaterialTheme.colorScheme.surfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Controls — single row
        var volume by remember { mutableFloatStateOf(1f) }
        var showVolume by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Loop
            IconButton(onClick = onToggleLoop) {
                Icon(
                    Icons.Outlined.Repeat, "Loop",
                    Modifier.size(24.dp),
                    tint = if (looping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(4.dp))

            // Rewind 10s
            IconButton(onClick = { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }) {
                Icon(Icons.Outlined.Replay10, "Rewind", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.width(8.dp))

            // Play/Pause
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Icon(
                        if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        "Play/Pause",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Forward 10s
            IconButton(onClick = { player.seekTo((player.currentPosition + 10000).coerceAtMost(player.duration)) }) {
                Icon(Icons.Outlined.Forward10, "Forward", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.width(4.dp))

            // Volume — tap for popup
            Box {
                IconButton(onClick = { showVolume = !showVolume }) {
                    Icon(
                        Icons.Outlined.VolumeUp, "Volume",
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showVolume) {
                    androidx.compose.material3.DropdownMenu(
                        expanded = showVolume,
                        onDismissRequest = { showVolume = false },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = volume,
                                onValueChange = {
                                    volume = it
                                    player.volume = it
                                },
                                modifier = Modifier.width(180.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/**
 * Opens [uri] in another app. Cached results are FileProvider content:// URIs, so the receiving app needs
 * a read grant (without it, players fail to open the file). The MIME type comes from the provider or the
 * extension, so audio-only results (.m4a/.mp3) open in audio-capable apps too.
 */
internal fun openInExternalPlayer(context: android.content.Context, uri: Uri) {
    val mime = (if (uri.scheme == "content") context.contentResolver.getType(uri) else null)
        ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase())
        ?: "video/*"
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        if (uri.scheme == "content") {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The chooser forwards the grant via ClipData
            clipData = android.content.ClipData.newRawUri("", uri)
        }
    }
    val chooser = android.content.Intent.createChooser(view, null).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (uri.scheme == "content") addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(chooser)
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, context.getString(com.musicremover.app.R.string.no_external_player), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Shown over the video when ExoPlayer fails: a short reason, Retry, another app, and Report. */
@Composable
private fun PlaybackErrorOverlay(error: androidx.media3.common.PlaybackException, url: String, onRetry: () -> Unit) {
    val context = LocalContext.current
    val reason = when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> com.musicremover.app.R.string.playback_error_missing
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> com.musicremover.app.R.string.playback_error_network
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> com.musicremover.app.R.string.playback_error_format
        else -> com.musicremover.app.R.string.playback_error_generic
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            // Taps on the overlay must not toggle play/pause behind it
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline, null,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            androidx.compose.ui.res.stringResource(com.musicremover.app.R.string.playback_error_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            androidx.compose.ui.res.stringResource(reason),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(androidx.compose.ui.res.stringResource(com.musicremover.app.R.string.retry))
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { openInExternalPlayer(context, Uri.parse(url)) },
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Text(androidx.compose.ui.res.stringResource(com.musicremover.app.R.string.open_in_other_app))
            }
        }
        androidx.compose.material3.TextButton(
            onClick = {
                com.musicremover.app.data.ErrorReport.open(
                    context, com.musicremover.app.data.ErrorReport.Kind.Playback,
                    "${error.errorCodeName}: ${error.message ?: ""}" +
                        (error.cause?.let { "\nCause: ${it.javaClass.name}: ${it.message ?: ""}" } ?: ""),
                    null, null,
                )
            },
        ) {
            Text(
                androidx.compose.ui.res.stringResource(com.musicremover.app.R.string.report_problem_short),
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}
