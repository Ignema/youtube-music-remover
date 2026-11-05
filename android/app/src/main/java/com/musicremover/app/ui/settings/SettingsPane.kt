package com.musicremover.app.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel

/** Where a Settings screen is drawn: alone (phone, Medium), or as the list or detail pane of the two-pane frame. */
enum class SettingsPane { SINGLE, LIST, DETAIL }

val LocalSettingsPane = staticCompositionLocalOf { SettingsPane.SINGLE }

/**
 * List and detail side by side. Each pane consumes the insets of the inner edge it does not touch,
 * so its Scaffold pads only for the window edge it is on.
 */
@Composable
fun SettingsTwoPane(listWidth: Dp, list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(listWidth).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End))) {
            CompositionLocalProvider(LocalSettingsPane provides SettingsPane.LIST) { list() }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))) {
            CompositionLocalProvider(LocalSettingsPane provides SettingsPane.DETAIL) { detail() }
        }
    }
}

/** Settings list position, shared by every entry of the settings graph so a two-pane detail switch does not reset it. */
@OptIn(ExperimentalMaterial3Api::class)
class SettingsListState : ViewModel() {
    val scroll = ScrollState(0)
    val largeBar = TopAppBarState(-Float.MAX_VALUE, 0f, 0f) // same initial values as rememberTopAppBarState()
    val smallBar = TopAppBarState(-Float.MAX_VALUE, 0f, 0f)
}
