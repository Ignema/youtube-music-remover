package com.musicremover.app.ui.settings

/** What a tap on a Settings list row does to the back stack. */
sealed interface SettingsNavAction {
    data object None : SettingsNavAction
    data object PopToMain : SettingsNavAction
    data class Push(val route: String) : SettingsNavAction
    /** navigate(route) { popUpTo(MAIN); launchSingleTop = true } */
    data class ReplaceDetail(val route: String) : SettingsNavAction
}

/** Settings navigation rules; in two-pane the stack is main, or main plus one detail. */
object SettingsNav {
    /** The detail shown next to the list while only main is on the stack. */
    const val DEFAULT_DETAIL = SettingsRoutes.PROCESSING

    fun detailFor(topRoute: String): String = if (topRoute == SettingsRoutes.MAIN) DEFAULT_DETAIL else topRoute

    fun select(currentRoute: String, target: String, twoPane: Boolean): SettingsNavAction = when {
        target !in SettingsRoutes.SCREENS -> SettingsNavAction.Push(target)
        !twoPane -> SettingsNavAction.Push(target)
        target == detailFor(currentRoute) -> SettingsNavAction.None
        target == DEFAULT_DETAIL -> SettingsNavAction.PopToMain
        currentRoute == SettingsRoutes.MAIN -> SettingsNavAction.Push(target)
        else -> SettingsNavAction.ReplaceDetail(target)
    }

    /** True when the stack must be normalized after entering two-pane (Processing pushed over main). */
    fun needsNormalize(topRoute: String, previousRoute: String?, twoPane: Boolean): Boolean =
        twoPane && topRoute == DEFAULT_DETAIL && previousRoute == SettingsRoutes.MAIN
}
