package com.musicremover.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.musicremover.app.R
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt

/** Converts an HSL seed to a color with a given CIE L* tone (0 = black, 100 = white). */
internal object Tones {
    fun tone(hue: Float, sat: Float, tone: Double): Color {
        if (tone <= 0.0) return Color.Black
        if (tone >= 100.0) return Color.White
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (lStar(hslToArgb(hue, sat, mid)) < tone) lo = mid else hi = mid
        }
        return Color(hslToArgb(hue, sat, (lo + hi) / 2))
    }

    fun lStar(argb: Int): Double {
        val y = 0.2126 * linear(argb shr 16 and 0xFF) +
            0.7152 * linear(argb shr 8 and 0xFF) +
            0.0722 * linear(argb and 0xFF)
        val f = if (y > 216.0 / 24389.0) cbrt(y) else (24389.0 / 27.0 * y + 16.0) / 116.0
        return 116.0 * f - 16.0
    }

    private fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun hslToArgb(hue: Float, sat: Float, l: Double): Int {
        val c = (1 - abs(2 * l - 1)) * sat
        val h = ((hue % 360f) + 360f) % 360f / 60.0
        val x = c * (1 - abs(h % 2 - 1))
        val (r1, g1, b1) = when {
            h < 1 -> Triple(c, x, 0.0)
            h < 2 -> Triple(x, c, 0.0)
            h < 3 -> Triple(0.0, c, x)
            h < 4 -> Triple(0.0, x, c)
            h < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2
        fun ch(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

internal class TonalPalette(private val hue: Float, private val sat: Float) {
    operator fun get(t: Number): Color = Tones.tone(hue, sat, t.toDouble())
}

/** Builds a full Material 3 scheme from HSL seeds using the M3 tone mapping. */
internal fun schemeFromSeed(
    hue: Float,
    sat: Float,
    secSat: Float,
    terSat: Float,
    nSat: Float,
    nvSat: Float,
    dark: Boolean,
): ColorScheme {
    val p = TonalPalette(hue, sat)
    val s = TonalPalette(hue, secSat)
    val t = TonalPalette((hue + 60f) % 360f, terSat)
    val n = TonalPalette(hue, nSat)
    val nv = TonalPalette(hue, nvSat)
    return if (!dark) lightColorScheme(
        primary = p[40], onPrimary = p[100], primaryContainer = p[90], onPrimaryContainer = p[10],
        inversePrimary = p[80], surfaceTint = p[40],
        secondary = s[40], onSecondary = s[100], secondaryContainer = s[90], onSecondaryContainer = s[10],
        tertiary = t[40], onTertiary = t[100], tertiaryContainer = t[90], onTertiaryContainer = t[10],
        background = n[98], onBackground = n[10], surface = n[98], onSurface = n[10],
        surfaceVariant = nv[90], onSurfaceVariant = nv[30],
        outline = nv[50], outlineVariant = nv[80],
        inverseSurface = n[20], inverseOnSurface = n[95], scrim = n[0],
        surfaceDim = n[87], surfaceBright = n[98],
        surfaceContainerLowest = n[100], surfaceContainerLow = n[96], surfaceContainer = n[94],
        surfaceContainerHigh = n[92], surfaceContainerHighest = n[90],
    ) else darkColorScheme(
        primary = p[80], onPrimary = p[20], primaryContainer = p[30], onPrimaryContainer = p[90],
        inversePrimary = p[40], surfaceTint = p[80],
        secondary = s[80], onSecondary = s[20], secondaryContainer = s[30], onSecondaryContainer = s[90],
        tertiary = t[80], onTertiary = t[20], tertiaryContainer = t[30], onTertiaryContainer = t[90],
        background = n[6], onBackground = n[90], surface = n[6], onSurface = n[90],
        surfaceVariant = nv[30], onSurfaceVariant = nv[80],
        outline = nv[60], outlineVariant = nv[30],
        inverseSurface = n[90], inverseOnSurface = n[20], scrim = n[0],
        surfaceDim = n[6], surfaceBright = n[24],
        surfaceContainerLowest = n[4], surfaceContainerLow = n[10], surfaceContainer = n[12],
        surfaceContainerHigh = n[17], surfaceContainerHighest = n[22],
    )
}

/** Pure-black surfaces for the Black (OLED) theme, keeping the dark scheme's accents. */
internal fun blackOf(dark: ColorScheme): ColorScheme = dark.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF1A1A1A),
    surfaceBright = Color(0xFF1F1F1F),
)

class ColorPalette(
    val id: String,
    @StringRes val nameRes: Int,
    light: () -> ColorScheme,
    dark: () -> ColorScheme,
    black: (ColorScheme) -> ColorScheme = ::blackOf,
) {
    val light: ColorScheme by lazy(light)
    val dark: ColorScheme by lazy(dark)
    val black: ColorScheme by lazy { black(this.dark) }
}

// Peach: the app's original colors. Unset roles fall back to the M3 baseline, as before.
private val Peach = Color(0xFFFF8A65)
private val PeachDark = Color(0xFFFFAB91)
private val PeachContainer = Color(0xFFFFE0D0)
private val OnPeachContainer = Color(0xFF3E1500)
private val Surface = Color(0xFFFFFBF8)
private val SurfaceDark = Color(0xFF1A1110)

private fun peachLight() = lightColorScheme(
    primary = Peach,
    onPrimary = OnPeachContainer, // dark text on peach: white was ~2.3:1, below WCAG AA
    primaryContainer = PeachContainer,
    onPrimaryContainer = OnPeachContainer,
    secondary = Color(0xFF77574B),
    secondaryContainer = Color(0xFFFFDBCF),
    surface = Surface,
    surfaceVariant = Color(0xFFF5DED5),
    background = Surface,
)

private fun peachDarkScheme() = darkColorScheme(
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

private fun peachBlack() = darkColorScheme(
    primary = PeachDark,
    onPrimary = Color(0xFF5D1900),
    primaryContainer = Color(0xFF7D2E0D),
    onPrimaryContainer = PeachContainer,
    secondary = Color(0xFFE7BDB0),
    secondaryContainer = Color(0xFF5D4037),
    surface = Color.Black,
    surfaceVariant = Color(0xFF1A1110),
    background = Color.Black,
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF1A1A1A),
)

private fun seeded(
    id: String,
    @StringRes nameRes: Int,
    hue: Float,
    sat: Float,
    secSat: Float,
    terSat: Float,
    nSat: Float,
    nvSat: Float,
) = ColorPalette(
    id = id,
    nameRes = nameRes,
    light = { schemeFromSeed(hue, sat, secSat, terSat, nSat, nvSat, dark = false) },
    dark = { schemeFromSeed(hue, sat, secSat, terSat, nSat, nvSat, dark = true) },
)

object ColorPalettes {
    const val DEFAULT_ID = "peach"

    val ALL: List<ColorPalette> = listOf(
        ColorPalette(DEFAULT_ID, R.string.palette_peach, ::peachLight, ::peachDarkScheme, { peachBlack() }),
        seeded("indigo", R.string.palette_indigo, 231f, 0.48f, 0.16f, 0.30f, 0.05f, 0.10f),
        seeded("teal", R.string.palette_teal, 174f, 0.70f, 0.16f, 0.30f, 0.04f, 0.08f),
        seeded("green", R.string.palette_green, 130f, 0.45f, 0.16f, 0.30f, 0.04f, 0.08f),
        seeded("amber", R.string.palette_amber, 40f, 0.90f, 0.20f, 0.30f, 0.05f, 0.10f),
        seeded("rose", R.string.palette_rose, 345f, 0.65f, 0.18f, 0.30f, 0.05f, 0.10f),
        seeded("purple", R.string.palette_purple, 275f, 0.50f, 0.16f, 0.30f, 0.05f, 0.10f),
        seeded("slate", R.string.palette_slate, 215f, 0.18f, 0.10f, 0.18f, 0.03f, 0.06f),
    )

    val IDS: Set<String> = ALL.map { it.id }.toSet()

    fun byId(id: String?): ColorPalette = ALL.firstOrNull { it.id == id } ?: ALL.first()

    fun resolveId(stored: String?): String = stored?.takeIf { it in IDS } ?: DEFAULT_ID
}
