package com.musicremover.app.ui.adaptive

import com.musicremover.app.ui.adaptive.AdaptiveLayout.lockLandscapeInFullscreen
import com.musicremover.app.ui.adaptive.AdaptiveLayout.playerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout decisions per window class; COMPACT must always give today's phone values. */
class AdaptiveLayoutTest {
    private val c = WidthClass.COMPACT
    private val m = WidthClass.MEDIUM
    private val e = WidthClass.EXPANDED

    @Test
    fun widthBreakpoints() {
        assertEquals(c, widthClassOf(599f))
        assertEquals(m, widthClassOf(600f))
        assertEquals(m, widthClassOf(839f))
        assertEquals(e, widthClassOf(840f))
    }

    @Test
    fun heightBreakpoints() {
        assertEquals(HeightClass.COMPACT, heightClassOf(479f))
        assertEquals(HeightClass.MEDIUM, heightClassOf(480f))
        assertEquals(HeightClass.MEDIUM, heightClassOf(899f))
        assertEquals(HeightClass.EXPANDED, heightClassOf(900f))
    }

    @Test
    fun compactDefaultIsAPhone() {
        val wl = WindowLayout.Compact
        assertEquals(c, wl.width)
        assertEquals(HeightClass.MEDIUM, wl.height)
        assertEquals(411, wl.widthDp)
        assertFalse(wl.tabletop)
        assertNull(wl.hingeWindowTopPx)
        assertNull(wl.hingeWindowBottomPx)
    }

    @Test
    fun homePaneMode() {
        assertEquals(HomePaneMode.SINGLE, AdaptiveLayout.homePaneMode(c))
        assertEquals(HomePaneMode.SINGLE_CENTERED, AdaptiveLayout.homePaneMode(m))
        assertEquals(HomePaneMode.TWO_PANE, AdaptiveLayout.homePaneMode(e))
    }

    @Test
    fun cutoutInsetsOnlyOffCompact() {
        assertFalse(AdaptiveLayout.useCutoutInsets(c))
        assertTrue(AdaptiveLayout.useCutoutInsets(m))
        assertTrue(AdaptiveLayout.useCutoutInsets(e))
    }

    @Test
    fun homeColumnMax() {
        assertNull(AdaptiveLayout.homeColumnMaxDp(HomePaneMode.SINGLE))
        assertEquals(640, AdaptiveLayout.homeColumnMaxDp(HomePaneMode.SINGLE_CENTERED))
        assertNull(AdaptiveLayout.homeColumnMaxDp(HomePaneMode.TWO_PANE))
        assertEquals(1200, AdaptiveLayout.HOME_ROW_MAX_DP)
    }

    @Test
    fun maxWidthsPerClass() {
        assertNull(AdaptiveLayout.stateContentMaxDp(c))
        assertEquals(480, AdaptiveLayout.stateContentMaxDp(m))
        assertEquals(480, AdaptiveLayout.stateContentMaxDp(e))

        assertNull(AdaptiveLayout.settingsSingleMaxDp(c))
        assertEquals(640, AdaptiveLayout.settingsSingleMaxDp(m))
        assertEquals(640, AdaptiveLayout.settingsSingleMaxDp(e))

        assertNull(AdaptiveLayout.docMaxDp(c))
        assertEquals(720, AdaptiveLayout.docMaxDp(m))
        assertEquals(720, AdaptiveLayout.docMaxDp(e))

        assertNull(AdaptiveLayout.playerStackedPanelMaxDp(c))
        assertEquals(640, AdaptiveLayout.playerStackedPanelMaxDp(m))
        assertEquals(640, AdaptiveLayout.playerStackedPanelMaxDp(e))
    }

    @Test
    fun settingsTwoPaneOnlyExpanded() {
        assertFalse(AdaptiveLayout.settingsTwoPane(c))
        assertFalse(AdaptiveLayout.settingsTwoPane(m))
        assertTrue(AdaptiveLayout.settingsTwoPane(e))
    }

    @Test
    fun submitOnEnterOnlyOffCompact() {
        assertFalse(AdaptiveLayout.submitOnEnter(c))
        assertTrue(AdaptiveLayout.submitOnEnter(m))
        assertTrue(AdaptiveLayout.submitOnEnter(e))
    }

    @Test
    fun topBarStyleAllPairs() {
        for (w in WidthClass.entries) for (h in HeightClass.entries) {
            val expected = if (h == HeightClass.COMPACT) TopBarStyle.SMALL else TopBarStyle.LARGE
            assertEquals("$w x $h", expected, AdaptiveLayout.topBarStyle(w, h))
        }
        // A short compact-width window (pop-up or split screen) too: the large bar crashes there
        assertEquals(TopBarStyle.SMALL, AdaptiveLayout.topBarStyle(c, HeightClass.COMPACT))
        assertEquals(TopBarStyle.SMALL, AdaptiveLayout.topBarStyle(m, HeightClass.COMPACT))
        assertEquals(TopBarStyle.SMALL, AdaptiveLayout.topBarStyle(e, HeightClass.COMPACT))
    }

    @Test
    fun settingsListWidth() {
        assertEquals(300, AdaptiveLayout.settingsListWidthDp(999))
        assertEquals(360, AdaptiveLayout.settingsListWidthDp(1000))
    }

    @Test
    fun playerModeReferenceWindows() {
        assertEquals(PlayerMode.STACKED, playerMode(411f, 914f, false, null, 2.625f, false))
        assertEquals(PlayerMode.SIDE_BY_SIDE, playerMode(914f, 411f, false, null, 2.625f, false))
        assertEquals(PlayerMode.SIDE_BY_SIDE, playerMode(1280f, 800f, false, null, 2f, false))
        assertEquals(PlayerMode.STACKED, playerMode(800f, 1280f, false, null, 2f, false))
        assertEquals(PlayerMode.STACKED, playerMode(841f, 700f, false, null, 2.625f, false))
    }

    @Test
    fun playerModeTabletop() {
        val d = 2.625f
        // 841x700 window, hinge across the middle (350..351dp)
        val mid = HorizontalHinge(350f * d, 351f * d)
        assertEquals(PlayerMode.TABLETOP, playerMode(841f, 700f, true, mid, d, false))
        // Top area under 120dp
        assertEquals(PlayerMode.STACKED, playerMode(841f, 700f, true, HorizontalHinge(100f * d, 101f * d), d, false))
        // Posture reports tabletop but there is no horizontal hinge
        assertEquals(PlayerMode.STACKED, playerMode(841f, 700f, true, null, d, false))
        // Bottom area under 160dp (700 - 560 = 140)
        assertEquals(PlayerMode.STACKED, playerMode(841f, 700f, true, HorizontalHinge(550f * d, 560f * d), d, false))
        // A hinge without the tabletop posture does not count
        assertEquals(PlayerMode.STACKED, playerMode(841f, 700f, false, mid, d, false))
    }

    @Test
    fun fullscreenOverridesEveryMode() {
        val d = 2.625f
        val mid = HorizontalHinge(350f * d, 351f * d)
        assertEquals(PlayerMode.FULLSCREEN, playerMode(411f, 914f, false, null, d, true))
        assertEquals(PlayerMode.FULLSCREEN, playerMode(914f, 411f, false, null, d, true))
        assertEquals(PlayerMode.FULLSCREEN, playerMode(1280f, 800f, false, null, 2f, true))
        assertEquals(PlayerMode.FULLSCREEN, playerMode(800f, 1280f, false, null, 2f, true))
        assertEquals(PlayerMode.FULLSCREEN, playerMode(841f, 700f, true, mid, d, true))
        assertEquals(PlayerMode.FULLSCREEN, playerMode(0f, 0f, false, null, d, true))
    }

    @Test
    fun playerModeUnmeasuredWindowIsStacked() {
        assertEquals(PlayerMode.STACKED, playerMode(0f, 0f, false, null, 2.625f, false))
        assertEquals(PlayerMode.STACKED, playerMode(0f, 0f, true, HorizontalHinge(0f, 0f), 2.625f, false))
    }

    @Test
    fun playerPanelWidthClamps() {
        assertEquals(320f, AdaptiveLayout.playerPanelWidthDp(914f), 0.001f)
        assertEquals(409.6f, AdaptiveLayout.playerPanelWidthDp(1280f), 0.001f)
        assertEquals(420f, AdaptiveLayout.playerPanelWidthDp(2000f), 0.001f)
    }

    @Test
    fun lockLandscapeTruthTable() {
        assertTrue(lockLandscapeInFullscreen(599, false, 16f / 9f))
        assertFalse(lockLandscapeInFullscreen(600, false, 16f / 9f))
        assertFalse(lockLandscapeInFullscreen(599, true, 16f / 9f))
        assertFalse(lockLandscapeInFullscreen(599, false, 1.2f))
        assertTrue(lockLandscapeInFullscreen(599, false, 1.21f))
        assertFalse(lockLandscapeInFullscreen(600, true, 1.21f))
    }

    @Test
    fun recentReprocessFollowsInputVisibility() {
        assertTrue(AdaptiveLayout.recentReprocessVisible(true))
        assertFalse(AdaptiveLayout.recentReprocessVisible(false))
    }

    @Test
    fun removeEndsDone() {
        assertTrue(AdaptiveLayout.removeEndsDone(true, "a", "a"))
        assertFalse(AdaptiveLayout.removeEndsDone(true, "b", "a"))
        assertFalse(AdaptiveLayout.removeEndsDone(true, null, "a"))
        assertFalse(AdaptiveLayout.removeEndsDone(false, "a", "a"))
    }
}
