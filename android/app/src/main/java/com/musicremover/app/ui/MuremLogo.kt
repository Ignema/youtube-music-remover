package com.musicremover.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MuremLogo(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    ghostAlpha: Float = 0.3f,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeW = w * 0.12f
        val gap = w / 6f

        // Bar positions (centered)
        val bars = listOf(
            Triple(gap * 1, 0.35f, 0.65f),   // medium
            Triple(gap * 2, 0.20f, 0.80f),   // tall
            Triple(gap * 3, 0.42f, 0.58f),   // short (ghost)
            Triple(gap * 4, 0.20f, 0.80f),   // tall
            Triple(gap * 5, 0.35f, 0.65f),   // medium
        )

        bars.forEachIndexed { i, (x, top, bot) ->
            val alpha = if (i == 2) ghostAlpha else 1f
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(x, h * top),
                end = Offset(x, h * bot),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        }
    }
}
