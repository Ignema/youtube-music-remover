package com.musicremover.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun MusicRemoverTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = true,
    colorPalette: String = ColorPalettes.DEFAULT_ID,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        "dark", "black" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val isBlack = themeMode == "black"
    val palette = ColorPalettes.byId(colorPalette)

    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            val base = if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            if (isBlack) base.copy(
                surface = Color.Black,
                background = Color.Black,
                surfaceVariant = Color(0xFF1A1A1A),
            ) else base
        }
        isBlack -> palette.black
        isDark -> palette.dark
        else -> palette.light
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
