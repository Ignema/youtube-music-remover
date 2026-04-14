package com.musicremover.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun WaveformView(
    amplitudes: List<Float>,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    unplayedColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    cursorColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        if (amplitudes.isEmpty()) return@Canvas

        val barCount = amplitudes.size
        val gap = 2f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.85f

        amplitudes.forEachIndexed { index, amp ->
            val x = index * (barWidth + gap)
            val barHeight = (amp.coerceIn(0f, 1f) * maxBarHeight).coerceAtLeast(3f)
            val isPlayed = (index.toFloat() / barCount) <= progress

            drawRoundRect(
                color = if (isPlayed) playedColor else unplayedColor,
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth.coerceAtLeast(2f), barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }

        // Glowing cursor
        val cursorX = progress * size.width
        // Glow
        drawRoundRect(
            color = cursorColor.copy(alpha = 0.2f),
            topLeft = Offset(cursorX - 4f, 0f),
            size = Size(8f, size.height),
            cornerRadius = CornerRadius(4f, 4f),
        )
        // Cursor line
        drawRoundRect(
            color = cursorColor,
            topLeft = Offset(cursorX - 1.5f, 0f),
            size = Size(3f, size.height),
            cornerRadius = CornerRadius(1.5f, 1.5f),
        )
    }
}
