package com.musicremover.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun WaveformView(
    amplitudes: List<Float>,
    progress: Float, // 0f..1f
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    waveColor: Color = MaterialTheme.colorScheme.primary,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    unplayedColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    cursorColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val seekPos = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(seekPos)
                }
            },
    ) {
        if (amplitudes.isEmpty()) return@Canvas

        val barCount = amplitudes.size
        val barWidth = size.width / barCount
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.8f

        amplitudes.forEachIndexed { index, amp ->
            val x = index * barWidth + barWidth / 2f
            val barHeight = (amp.coerceIn(0f, 1f) * maxBarHeight).coerceAtLeast(2f)
            val isPlayed = (index.toFloat() / barCount) <= progress

            drawLine(
                color = if (isPlayed) playedColor else unplayedColor,
                start = Offset(x, centerY - barHeight / 2f),
                end = Offset(x, centerY + barHeight / 2f),
                strokeWidth = (barWidth * 0.6f).coerceAtLeast(1.5f),
            )
        }

        // Playback cursor
        val cursorX = progress * size.width
        drawLine(
            color = cursorColor,
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, size.height),
            strokeWidth = 2f,
        )
    }
}
