package com.musicremover.app.ui.adaptive

/** Window width class (Material 3 breakpoints: < 600, 600 to < 840, >= 840 dp). */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** Window height class (Material 3 breakpoints: < 480, 480 to < 900, >= 900 dp). */
enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

/** Hinge of a tabletop posture, in px relative to the Player root. */
data class HorizontalHinge(val topPx: Float, val bottomPx: Float)

/** What the layouts need to know about the window; provided by LocalWindowLayout. */
data class WindowLayout(
    val width: WidthClass,
    val height: HeightClass,
    val widthDp: Int,
    val tabletop: Boolean,
    val hingeWindowTopPx: Float?,
    val hingeWindowBottomPx: Float?,
) {
    companion object {
        /** A phone in portrait: every layout decision returns today's phone values. */
        val Compact = WindowLayout(WidthClass.COMPACT, HeightClass.MEDIUM, 411, false, null, null)
    }
}

enum class HomePaneMode { SINGLE, SINGLE_CENTERED, TWO_PANE }
enum class TopBarStyle { LARGE, SMALL }
enum class PlayerMode { STACKED, SIDE_BY_SIDE, TABLETOP, FULLSCREEN }

/** Layout decisions per window class. Pure Kotlin so they can be unit tested; dp values are plain numbers. */
object AdaptiveLayout {
    const val HOME_COLUMN_MAX_DP = 640
    const val HOME_ROW_MAX_DP = 1200
    const val STATE_CONTENT_MAX_DP = 480
    const val SETTINGS_SINGLE_MAX_DP = 640
    const val SETTINGS_DETAIL_MAX_DP = 720
    const val DOC_MAX_DP = 720
    const val PLAYER_PANEL_MAX_STACKED_DP = 640
    const val SIDE_BY_SIDE_MIN_ASPECT = 1.4f
    const val TABLETOP_MIN_TOP_DP = 120
    const val TABLETOP_MIN_BOTTOM_DP = 160

    fun homePaneMode(w: WidthClass): HomePaneMode = when (w) {
        WidthClass.COMPACT -> HomePaneMode.SINGLE
        WidthClass.MEDIUM -> HomePaneMode.SINGLE_CENTERED
        WidthClass.EXPANDED -> HomePaneMode.TWO_PANE
    }

    /**
     * Small pinned bar on any short window (a landscape phone, split screen, a pop-up window).
     * Material3 1.3.1's LargeTopAppBar crashes ("Size(w x -h) is out of range") when it is collapsed
     * in a window shorter than the bar's expanded height plus the insets, so a short window never gets it.
     */
    fun topBarStyle(w: WidthClass, h: HeightClass): TopBarStyle =
        if (h == HeightClass.COMPACT) TopBarStyle.SMALL else TopBarStyle.LARGE

    /** Compact keeps today's Scaffold/TopAppBar inset defaults. */
    fun useCutoutInsets(w: WidthClass): Boolean = w != WidthClass.COMPACT

    /** Max width of the single Home column; TWO_PANE is capped by [HOME_ROW_MAX_DP] instead. */
    fun homeColumnMaxDp(mode: HomePaneMode): Int? =
        if (mode == HomePaneMode.SINGLE_CENTERED) HOME_COLUMN_MAX_DP else null

    fun stateContentMaxDp(w: WidthClass): Int? = if (w == WidthClass.COMPACT) null else STATE_CONTENT_MAX_DP

    fun settingsTwoPane(w: WidthClass): Boolean = w == WidthClass.EXPANDED

    fun settingsListWidthDp(windowWidthDp: Int): Int = if (windowWidthDp >= 1000) 360 else 300

    fun settingsSingleMaxDp(w: WidthClass): Int? = if (w == WidthClass.COMPACT) null else SETTINGS_SINGLE_MAX_DP

    fun docMaxDp(w: WidthClass): Int? = if (w == WidthClass.COMPACT) null else DOC_MAX_DP

    fun recentReprocessVisible(inputVisible: Boolean): Boolean = inputVisible

    /** Removing the Recent item that Done is showing must also leave Done (its cached result is deleted). */
    fun removeEndsDone(isDone: Boolean, doneJobId: String?, removedJobId: String): Boolean =
        isDone && doneJobId == removedJobId

    fun submitOnEnter(w: WidthClass): Boolean = w != WidthClass.COMPACT

    fun playerMode(
        widthDp: Float,
        heightDp: Float,
        tabletop: Boolean,
        hinge: HorizontalHinge?,
        density: Float,
        fullscreen: Boolean,
    ): PlayerMode {
        if (fullscreen) return PlayerMode.FULLSCREEN
        if (widthDp <= 0f || heightDp <= 0f) return PlayerMode.STACKED
        if (tabletop && hinge != null &&
            hinge.topPx / density >= TABLETOP_MIN_TOP_DP &&
            heightDp - hinge.bottomPx / density >= TABLETOP_MIN_BOTTOM_DP
        ) return PlayerMode.TABLETOP
        if (widthDp >= 600f && widthDp / heightDp >= SIDE_BY_SIDE_MIN_ASPECT) return PlayerMode.SIDE_BY_SIDE
        return PlayerMode.STACKED
    }

    fun playerPanelWidthDp(widthDp: Float): Float = (0.32f * widthDp).coerceIn(320f, 420f)

    fun playerStackedPanelMaxDp(w: WidthClass): Int? =
        if (w == WidthClass.COMPACT) null else PLAYER_PANEL_MAX_STACKED_DP

    /** Fullscreen forces landscape only on a phone-sized, single-window screen with a wide video. */
    fun lockLandscapeInFullscreen(smallestWidthDp: Int, inMultiWindow: Boolean, videoAspect: Float): Boolean =
        smallestWidthDp < 600 && !inMultiWindow && videoAspect > 1.2f
}

/** The Material 3 width breakpoints the design assumes; the runtime uses the library's class. */
fun widthClassOf(widthDp: Float): WidthClass = when {
    widthDp < 600f -> WidthClass.COMPACT
    widthDp < 840f -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}

/** The Material 3 height breakpoints the design assumes; the runtime uses the library's class. */
fun heightClassOf(heightDp: Float): HeightClass = when {
    heightDp < 480f -> HeightClass.COMPACT
    heightDp < 900f -> HeightClass.MEDIUM
    else -> HeightClass.EXPANDED
}
