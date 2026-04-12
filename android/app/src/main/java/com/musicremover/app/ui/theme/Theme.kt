package com.musicremover.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Expressive warm palette
private val Peach = Color(0xFFFF8A65)
private val PeachDark = Color(0xFFFFAB91)
private val PeachContainer = Color(0xFFFFE0D0)
private val OnPeachContainer = Color(0xFF3E1500)
private val Surface = Color(0xFFFFFBF8)
private val SurfaceDark = Color(0xFF1A1110)

private val LightColors = lightColorScheme(
    primary = Peach,
    onPrimary = Color.White,
    primaryContainer = PeachContainer,
    onPrimaryContainer = OnPeachContainer,
    secondary = Color(0xFF77574B),
    secondaryContainer = Color(0xFFFFDBCF),
    surface = Surface,
    surfaceVariant = Color(0xFFF5DED5),
    background = Surface,
)

private val DarkColors = darkColorScheme(
    primary = PeachDark,
    onPrimary = Color(0xFF5D1900),
    primaryContainer = Color(0xFF7D2E0D),
    onPrimaryContainer = PeachContainer,
    secondary = Color(0xFFE7BDB0),
    secondaryContainer = Color(0xFF5D4037),
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF53433D),
    background = SurfaceDark,
)

@Composable
fun MusicRemoverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
