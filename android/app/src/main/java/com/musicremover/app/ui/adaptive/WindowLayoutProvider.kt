package com.musicremover.app.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass

/** The current window's layout info. The default keeps previews and tests on the phone path. */
val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Compact }

/**
 * Reads the window size class and fold posture. This is the only file that touches the
 * material3-adaptive / androidx.window API; everything else uses [WindowLayout].
 */
@Composable
fun rememberWindowLayout(): WindowLayout {
    val info = currentWindowAdaptiveInfo()
    val widthDp = with(LocalDensity.current) { currentWindowSize().width.toDp().value.toInt() }
    val sizeClass = info.windowSizeClass
    // Not enums in window-core 1.3, so compare with ==; an unknown class takes the phone path
    val width = when (sizeClass.windowWidthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> WidthClass.EXPANDED
        WindowWidthSizeClass.MEDIUM -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
    val height = when (sizeClass.windowHeightSizeClass) {
        WindowHeightSizeClass.COMPACT -> HeightClass.COMPACT
        WindowHeightSizeClass.EXPANDED -> HeightClass.EXPANDED
        else -> HeightClass.MEDIUM
    }
    val posture = info.windowPosture
    val hinge = posture.hingeList.firstOrNull { !it.isVertical }
    return WindowLayout(
        width = width,
        height = height,
        widthDp = widthDp,
        tabletop = posture.isTabletop,
        hingeWindowTopPx = hinge?.bounds?.top,
        hingeWindowBottomPx = hinge?.bounds?.bottom,
    )
}
