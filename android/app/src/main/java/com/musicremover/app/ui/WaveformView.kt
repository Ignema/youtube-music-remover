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
import androidx.compose.ui.graphics.Brush
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
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
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
        val gap = 2.5f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.82f

        // Draw mirrored bars (top + bottom from center)
        amplitudes.forEachIndexed { index, amp ->
            val x = index * (barWidth + gap)
            val halfHeight = (amp.coerceIn(0f, 1f) * maxBarHeight / 2f).coerceAtLeast(1.5f)
            val isPlayed = (index.toFloat() / barCount) <= progress

            val barColor = if (isPlayed) {
                playedColor
            } else {
                unplayedColor
            }

            // Top half
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, centerY - halfHeight),
                size = Size(barWidth.coerceAtLeast(2f), halfHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
            // Bottom half (slightly dimmer)
            drawRoundRect(
                color = barColor.copy(alpha = if (isPlayed) 0.6f else 0.4f),
                topLeft = Offset(x, centerY),
                size = Size(barWidth.coerceAtLeast(2f), halfHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }

        // Center line
        drawLine(
            color = unplayedColor.copy(alpha = 0.3f),
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 1f,
        )

        // Cursor with glow
        val cursorX = progress * size.width
        // Outer glow
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(cursorColor.copy(alpha = 0.3f), Color.Transparent),
                center = Offset(cursorX, centerY),
                radius = 16f,
            ),
            topLeft = Offset(cursorX - 16f, centerY - 16f),
            size = Size(32f, 32f),
        )
        // Cursor line
        drawRoundRect(
            color = cursorColor,
            topLeft = Offset(cursorX - 1.5f, 4f),
            size = Size(3f, size.height - 8f),
            cornerRadius = CornerRadius(1.5f),
        )
        // Cursor dot
        drawCircle(
            color = cursorColor,
            radius = 5f,
            center = Offset(cursorX, centerY),
        )
    }
}
