package com.musicremover.app.ui.settings

import com.musicremover.app.ui.settings.SettingsNavAction.None
import com.musicremover.app.ui.settings.SettingsNavAction.PopToMain
import com.musicremover.app.ui.settings.SettingsNavAction.Push
import com.musicremover.app.ui.settings.SettingsNavAction.ReplaceDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings list taps: push on a phone, swap the detail pane in two-pane. */
class SettingsNavTest {
    private val main = SettingsRoutes.MAIN
    private val processing = SettingsRoutes.PROCESSING
    private val subScreens = SettingsRoutes.SCREENS - main

    @Test
    fun routesOutsideTheGraphAlwaysPush() {
        for (target in listOf(SettingsRoutes.PERMISSIONS, SettingsRoutes.HELP)) {
            for (current in SettingsRoutes.SCREENS) for (twoPane in listOf(false, true)) {
                assertEquals("$current -> $target two=$twoPane", Push(target), SettingsNav.select(current, target, twoPane))
            }
        }
    }

    @Test
    fun singlePaneAlwaysPushes() {
        for (current in SettingsRoutes.SCREENS) for (target in SettingsRoutes.ALL) {
            assertEquals("$current -> $target", Push(target), SettingsNav.select(current, target, false))
        }
    }

    @Test
    fun twoPaneShownDetailIsNone() {
        assertEquals(None, SettingsNav.select(main, processing, true))
        for (r in subScreens) assertEquals(r, None, SettingsNav.select(r, r, true))
    }

    @Test
    fun twoPaneDefaultDetailPopsToMain() {
        for (r in subScreens - processing) assertEquals(r, PopToMain, SettingsNav.select(r, processing, true))
    }

    @Test
    fun twoPaneFromMainPushes() {
        for (target in subScreens - processing) assertEquals(target, Push(target), SettingsNav.select(main, target, true))
    }

    @Test
    fun twoPaneBetweenSubScreensReplacesDetail() {
        for (current in subScreens) for (target in subScreens - processing - current) {
            assertEquals("$current -> $target", ReplaceDetail(target), SettingsNav.select(current, target, true))
        }
    }

    @Test
    fun detailFor() {
        assertEquals(processing, SettingsNav.DEFAULT_DETAIL)
        assertEquals(processing, SettingsNav.detailFor(main))
        for (r in subScreens) assertEquals(r, SettingsNav.detailFor(r))
    }

    @Test
    fun needsNormalizeTruthTable() {
        assertTrue(SettingsNav.needsNormalize(processing, main, true))
        assertFalse(SettingsNav.needsNormalize(processing, main, false))
        assertFalse(SettingsNav.needsNormalize(processing, null, true))
        assertFalse(SettingsNav.needsNormalize(processing, SettingsRoutes.MODELS, true))
        assertFalse(SettingsNav.needsNormalize(SettingsRoutes.MODELS, main, true))
        assertFalse(SettingsNav.needsNormalize(main, null, true))
    }

    @Test
    fun everyTargetIsARoute() {
        for (current in SettingsRoutes.SCREENS) for (target in SettingsRoutes.ALL) for (twoPane in listOf(false, true)) {
            when (val a = SettingsNav.select(current, target, twoPane)) {
                is Push -> assertTrue(a.route, a.route in SettingsRoutes.ALL)
                is ReplaceDetail -> assertTrue(a.route, a.route in SettingsRoutes.ALL)
                None, PopToMain -> {}
            }
        }
    }
}
