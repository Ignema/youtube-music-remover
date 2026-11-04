package com.musicremover.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPalettesTest {

    @Test
    fun defaultIsPeach() {
        assertEquals("peach", ColorPalettes.DEFAULT_ID)
        assertEquals(ColorPalettes.DEFAULT_ID, ColorPalettes.ALL.first().id)
    }

    @Test
    fun resolveIdFallsBackToDefault() {
        assertEquals("peach", ColorPalettes.resolveId(null))
        assertEquals("peach", ColorPalettes.resolveId(""))
        assertEquals("peach", ColorPalettes.resolveId("neon"))
        for (id in ColorPalettes.IDS) assertEquals(id, ColorPalettes.resolveId(id))
    }

    @Test
    fun byIdLookup() {
        assertEquals("teal", ColorPalettes.byId("teal").id)
        assertEquals("peach", ColorPalettes.byId("bogus").id)
        assertEquals("peach", ColorPalettes.byId(null).id)
        assertEquals(8, ColorPalettes.ALL.size)
        assertEquals(8, ColorPalettes.IDS.size)
    }

    @Test
    fun contrastMeetsAA() {
        for (p in ColorPalettes.ALL) {
            for ((variant, s) in listOf("light" to p.light, "dark" to p.dark, "black" to p.black)) {
                for ((pair, fg, bg) in pairs(s)) {
                    val c = contrast(fg, bg)
                    assertTrue("${p.id}/$variant $pair contrast ${"%.2f".format(c)} < 4.5", c >= 4.5)
                }
            }
        }
    }

    @Test
    fun blackIsPureBlack() {
        for (p in ColorPalettes.ALL) {
            assertEquals(p.id, Color.Black, p.black.surface)
            assertEquals(p.id, Color.Black, p.black.background)
            assertEquals(p.id, p.dark.primary, p.black.primary)
        }
    }

    @Test
    fun peachUnchangedExceptOnPrimary() {
        val peach = ColorPalettes.byId("peach")
        assertEquals(Color(0xFFFF8A65), peach.light.primary)
        assertEquals(Color(0xFFFFFBF8), peach.light.surface)
        assertEquals(Color(0xFF3E1500), peach.light.onPrimary)
        assertEquals(Color(0xFFFFAB91), peach.dark.primary)
    }

    @Test
    fun tonesHitTargetLStar() {
        val c = Tones.tone(231f, 0.48f, 40.0)
        assertTrue(abs(Tones.lStar(c.toArgb()) - 40.0) < 0.5)
        assertEquals(Color.Black, Tones.tone(231f, 0.48f, 0.0))
        assertEquals(Color.White, Tones.tone(231f, 0.48f, 100.0))
    }

    private fun pairs(s: ColorScheme) = listOf(
        Triple("onPrimary/primary", s.onPrimary, s.primary),
        Triple("onSurface/surface", s.onSurface, s.surface),
        Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        Triple("onBackground/background", s.onBackground, s.background),
    )

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a.toArgb())
        val lb = luminance(b.toArgb())
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }
}
