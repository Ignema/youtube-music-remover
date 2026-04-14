package com.musicremover.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.VolumeUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(url: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var durationMs by remember { mutableStateOf(0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var looping by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewTime by remember { mutableStateOf("") }

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

    // Waveform — try server pre-computed first, fall back to local streaming
    val amplitudes = remember { mutableStateListOf<Float>() }
    LaunchedEffect(url) {
        // Extract job ID from URL
        val jobId = url.substringAfterLast("/")
        // Try server waveform
        try {
            val serverUrl = url.substringBefore("/api/")
            val service = com.musicremover.app.data.ApiClient.getService(serverUrl)
            val response = service.waveform(jobId)
            if (response.waveform.isNotEmpty()) {
                amplitudes.clear()
                amplitudes.addAll(response.waveform)
                return@LaunchedEffect
            }
        } catch (_: Exception) { /* fall through to local */ }
        // Fall back to local streaming extraction
        streamWaveform(context, url, 150).collect { wave ->
            amplitudes.clear()
            amplitudes.addAll(wave)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Video area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (showOverlay) {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                        showOverlay = !showOverlay
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Ambient background — gradient from surface to black
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    Color.Black,
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                ),
                            ),
                        ),
                )

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

                // Buffering spinner
                if (isBuffering) {
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

                // Seek preview time (shown while dragging waveform)
                if (isSeeking && seekPreviewTime.isNotEmpty()) {
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

            // Bottom panel
            Card(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
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
                            progress = animatedProgress,
                            onSeek = { seekPos ->
                                isSeeking = true
                                progress = seekPos
                                val seekMs = (seekPos * player.duration).toLong()
                                seekPreviewTime = formatTime(seekMs)
                                player.seekTo(seekMs)
                                isSeeking = false
                                seekPreviewTime = ""
                            },
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
                        IconButton(onClick = {
                            looping = !looping
                            player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        }) {
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
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
