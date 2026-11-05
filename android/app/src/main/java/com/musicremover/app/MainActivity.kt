package com.musicremover.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicremover.app.processing.internal.LoginSites
import com.musicremover.app.processing.internal.WebLoginCookies
import kotlinx.coroutines.launch
import com.musicremover.app.ui.CookieLoginScreen
import com.musicremover.app.ui.HelpScreen
import com.musicremover.app.ui.HomeScreen
import com.musicremover.app.ui.PermissionsScreen
import com.musicremover.app.ui.PlayerScreen
import com.musicremover.app.ui.adaptive.AdaptiveLayout
import com.musicremover.app.ui.adaptive.LocalWindowLayout
import com.musicremover.app.ui.adaptive.rememberWindowLayout
import com.musicremover.app.ui.settings.AboutSettingsScreen
import com.musicremover.app.ui.settings.AccountsSettingsScreen
import com.musicremover.app.ui.settings.AppearanceSettingsScreen
import com.musicremover.app.ui.settings.ModelsSettingsScreen
import com.musicremover.app.ui.settings.OutputSettingsScreen
import com.musicremover.app.ui.settings.PerformanceSettingsScreen
import com.musicremover.app.ui.settings.ProcessingSettingsScreen
import com.musicremover.app.ui.settings.SettingsListState
import com.musicremover.app.ui.settings.SettingsMainScreen
import com.musicremover.app.ui.settings.SettingsNav
import com.musicremover.app.ui.settings.SettingsNavAction
import com.musicremover.app.ui.settings.SettingsRoutes
import com.musicremover.app.ui.settings.SettingsTwoPane
import com.musicremover.app.ui.settings.StorageSettingsScreen
import com.musicremover.app.ui.settings.YtDlpSettingsScreen
import com.musicremover.app.ui.theme.MusicRemoverTheme

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        vm.setForeground(true)
    }

    override fun onPause() {
        super.onPause()
        vm.setForeground(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Wipe a login session left behind if the process died on the login screen
        lifecycleScope.launch { WebLoginCookies.wipeLeftoverSession(applicationContext) }
        // A recreation (e.g. a language change) re-delivers the original intent; it was handled already
        if (savedInstanceState == null) {
            handleEntryIntent(intent)
            handlePlayIntent(intent)
            handleShareAction(intent)
        }
        setContent {
            val ui by vm.ui.collectAsState()
            MusicRemoverTheme(
                themeMode = ui.themeMode,
                dynamicColor = ui.dynamicColor,
                colorPalette = ui.colorPalette,
            ) {
                val navController = rememberNavController()

                // First run → show permissions screen
                val prefs = getSharedPreferences("app", MODE_PRIVATE)
                val isFirstRun = !prefs.getBoolean("setup_done", false)

                val windowLayout = rememberWindowLayout()
                // The holder itself (not `by`), so settingsRoute and its transitions read the current value
                val settingsTwoPane: State<Boolean> = rememberUpdatedState(AdaptiveLayout.settingsTwoPane(windowLayout.width))
                // Remembered once; they capture only navController, vm and the settingsTwoPane holder, so the
                // NavHost graph and the per-entry movable content in settingsRoute stay valid across resizes
                val exitSettings: () -> Unit = remember(navController) {
                    { navController.popBackStack(SettingsRoutes.GRAPH, inclusive = true) }
                }
                val selectSettings: (String) -> Unit = remember(navController) {
                    { target ->
                        val current = navController.currentDestination?.route ?: SettingsRoutes.MAIN
                        when (val action = SettingsNav.select(current, target, settingsTwoPane.value)) {
                            is SettingsNavAction.Push -> navController.navigate(action.route)
                            is SettingsNavAction.ReplaceDetail -> navController.navigate(action.route) {
                                popUpTo(SettingsRoutes.MAIN)
                                launchSingleTop = true
                            }
                            SettingsNavAction.PopToMain -> {
                                if (!navController.popBackStack(SettingsRoutes.MAIN, inclusive = false)) {
                                    Log.w("Murem", "settings pop to main failed from $current")
                                }
                            }
                            SettingsNavAction.None -> {}
                        }
                    }
                }
                val settingsDetail = remember<@Composable (String) -> Unit>(navController) {
                    { r ->
                        val back: () -> Unit = { navController.popBackStack() }
                        when (r) {
                            SettingsRoutes.PROCESSING -> ProcessingSettingsScreen(vm, back)
                            SettingsRoutes.MODELS -> ModelsSettingsScreen(vm, back)
                            SettingsRoutes.OUTPUT -> OutputSettingsScreen(vm, back)
                            SettingsRoutes.PERFORMANCE -> PerformanceSettingsScreen(vm, back)
                            // cookie_login is outside the settings graph, so leaving it returns here
                            SettingsRoutes.ACCOUNTS ->
                                AccountsSettingsScreen(vm, back, onLogin = { url -> navController.navigate(cookieLoginRoute(url)) })
                            SettingsRoutes.YTDLP -> YtDlpSettingsScreen(vm, back)
                            SettingsRoutes.APPEARANCE -> AppearanceSettingsScreen(vm, back)
                            SettingsRoutes.STORAGE -> StorageSettingsScreen(vm, back)
                            SettingsRoutes.ABOUT ->
                                AboutSettingsScreen(vm, back, onHelp = { navController.navigate(SettingsRoutes.HELP) })
                            else -> {} // MAIN is never a detail (SettingsNav.detailFor)
                        }
                    }
                }
                // I2: entering two-pane with Processing pushed over main shows the same thing as main, so drop it
                val topEntry by navController.currentBackStackEntryAsState()
                LaunchedEffect(settingsTwoPane.value, topEntry) {
                    val top = topEntry?.destination?.route ?: return@LaunchedEffect
                    val previous = navController.previousBackStackEntry?.destination?.route
                    if (SettingsNav.needsNormalize(top, previous, settingsTwoPane.value)) {
                        if (!navController.popBackStack(SettingsRoutes.MAIN, inclusive = false)) {
                            Log.w("Murem", "settings pop to main failed from $top")
                        }
                    }
                }
                CompositionLocalProvider(LocalWindowLayout provides windowLayout) {
                    NavHost(
                        navController = navController,
                        startDestination = if (isFirstRun) "permissions" else "home",
                        enterTransition = { slideInHorizontally { it } },
                        exitTransition = { slideOutHorizontally { -it / 3 } },
                        popEnterTransition = { slideInHorizontally { -it / 3 } },
                        popExitTransition = { slideOutHorizontally { it } },
                    ) {
                        composable("home") {
                            // Handle pending play from notification
                            androidx.compose.runtime.LaunchedEffect(vm.pendingPlayUrl) {
                                val url = vm.pendingPlayUrl
                                val title = vm.pendingPlayTitle
                                val jobId = vm.pendingPlayJobId
                                if (url != null && title != null) {
                                    vm.pendingPlayUrl = null
                                    vm.pendingPlayTitle = null
                                    vm.pendingPlayJobId = null
                                    navController.navigate(playerRoute(url, title, jobId))
                                }
                            }
                            // Handle pending navigation from widget
                            androidx.compose.runtime.LaunchedEffect(vm.pendingNavigation) {
                                val nav = vm.pendingNavigation
                                if (nav != null) {
                                    vm.pendingNavigation = null
                                    navController.navigate(nav)
                                }
                            }
                            HomeScreen(
                                vm = vm,
                                onSettingsClick = { navController.navigate("settings") },
                                onHelpClick = { navController.navigate("help") },
                                onPlay = { url, title, jobId ->
                                    navController.navigate(playerRoute(url, title, jobId))
                                },
                            )
                        }
                        // navigate("settings") (Home gear, widget) opens settings/main; system back pops to it, then Home
                        navigation(route = SettingsRoutes.GRAPH, startDestination = SettingsRoutes.MAIN) {
                            SettingsRoutes.SCREENS.forEach { route ->
                                settingsRoute(route, navController, settingsTwoPane, exitSettings, selectSettings, settingsDetail)
                            }
                        }
                        composable(
                            "cookie_login?url={url}",
                            arguments = listOf(navArgument("url") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }),
                        ) { backStackEntry ->
                            // No url: the Instagram preset
                            val site = backStackEntry.arguments?.getString("url")
                                ?.let { LoginSites.custom(it) } // Navigation decodes the Uri.encode()d arg
                                ?: LoginSites.INSTAGRAM.takeIf { backStackEntry.arguments?.getString("url") == null }
                            if (site == null) {
                                androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
                            } else {
                                // Counts as a Settings screen, so the save result shows once (Accounts Snackbar), not also as a Toast
                                androidx.compose.runtime.DisposableEffect(Unit) {
                                    vm.settingsVisible++
                                    onDispose { vm.settingsVisible-- }
                                }
                                CookieLoginScreen(vm = vm, site = site, onBack = { navController.popBackStack() })
                            }
                        }
                        composable("help") {
                            HelpScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            "player/{url}/{title}?jobId={jobId}",
                            arguments = listOf(navArgument("jobId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }),
                        ) { backStackEntry ->
                            val url = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8")
                            val title = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
                            val jobId = backStackEntry.arguments?.getString("jobId")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                            PlayerScreen(
                                url = url,
                                title = title,
                                onBack = { navController.popBackStack() },
                                jobId = jobId,
                                loadServerWaveform = { vm.serverWaveform(it) },
                            )
                        }
                        composable("permissions") {
                            // Handle system back press on first run
                            if (isFirstRun) {
                                androidx.activity.compose.BackHandler {
                                    prefs.edit().putBoolean("setup_done", true).apply()
                                    navController.navigate("home") {
                                        popUpTo("permissions") { inclusive = true }
                                    }
                                }
                            }
                            PermissionsScreen(
                                onBack = {
                                    prefs.edit().putBoolean("setup_done", true).apply()
                                    if (isFirstRun) {
                                        navController.navigate("home") {
                                            popUpTo("permissions") { inclusive = true }
                                        }
                                    } else {
                                        navController.popBackStack()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEntryIntent(intent)
        handlePlayIntent(intent)
        handleShareAction(intent)
    }

    /** Set by the widget Paste button; the clipboard is read once the window has focus (Android 10+). */
    private var pendingWidgetPaste = false

    /** Shares and widget buttons pick the Home mode (HomeInput.entryFor). Shares never start processing. */
    private fun handleEntryIntent(intent: Intent?) {
        if (intent == null) return
        val streams = sharedStreams(intent)
        val facts = HomeInput.EntryFacts(intent.action, intent.type, intent.getStringExtra(Intent.EXTRA_TEXT), streams.size)
        when (val entry = HomeInput.entryFor(facts)) {
            is HomeInput.Entry.SingleLink -> {
                vm.setInputTab(HomeInput.TAB_LINK)
                vm.onUrlChange(entry.text)
            }
            is HomeInput.Entry.Links -> vm.addLinks(entry.urls)
            is HomeInput.Entry.Files -> {
                // File mode holds one file; the card appears once the cache copy is done
                vm.setInputTab(HomeInput.TAB_FILE)
                vm.importFile(streams.first(), persist = false)
                if (entry.count > 1) vm.showTransientInfo(getString(R.string.share_first_file_only, entry.count))
            }
            HomeInput.Entry.WidgetPaste -> {
                vm.setInputTab(HomeInput.TAB_LINK)
                pendingWidgetPaste = true
                if (hasWindowFocus()) consumeWidgetPaste()
            }
            HomeInput.Entry.WidgetFile -> vm.setInputTab(HomeInput.TAB_FILE)
            HomeInput.Entry.WidgetLinks -> vm.requestLinkImport()
            HomeInput.Entry.WidgetSettings -> vm.pendingNavigation = "settings"
            HomeInput.Entry.None -> {}
        }
    }

    /** EXTRA_STREAM of SEND / SEND_MULTIPLE, else the ClipData items some senders use instead. */
    private fun sharedStreams(intent: Intent): List<android.net.Uri> {
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
                    ?.filterNotNull().orEmpty()
            else -> return emptyList()
        }
        if (streams.isNotEmpty()) return streams
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) consumeWidgetPaste()
    }

    /** One clipboard link processes right away, as before; several fill the Link list without starting. */
    private fun consumeWidgetPaste() {
        if (!pendingWidgetPaste) return
        pendingWidgetPaste = false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val links = HomeInput.extractLinks(clip)
        if (links.size >= 2 || (clip.isNotEmpty() && vm.ui.value.links.isNotEmpty())) {
            // Several links, or a list is already pending: add to the list without starting it
            vm.addLinks(links.ifEmpty { listOf(clip.trim()) })
        } else if (clip.isNotEmpty()) {
            // Only the clipboard link is processed, never the pending list or field text
            vm.processSingleUrl(clip.trim())
        }
    }

    private fun handlePlayIntent(intent: Intent?) {
        val url = intent?.getStringExtra("play_url") ?: return
        val title = intent.getStringExtra("play_title") ?: "Result"
        vm.pendingPlayUrl = url
        vm.pendingPlayTitle = title
        // Notifications from older versions only carry a /api/download/<id> play_url
        vm.pendingPlayJobId = intent.getStringExtra("play_job_id")
            ?: url.substringAfter("/api/download/", "").substringBefore('?').takeIf { it.isNotEmpty() }
    }

    private fun handleShareAction(intent: Intent?) {
        val filename = intent?.getStringExtra("share_filename") ?: "result"
        val jobId = intent?.getStringExtra("share_job_id")
        if (jobId != null) {
            vm.shareByJobId(this, jobId, filename)
            return
        }
        // Legacy notifications posted by older versions carry a server URL
        val url = intent?.getStringExtra("share_url") ?: return
        vm.shareByJobUrl(this, url, filename)
    }

    private fun playerRoute(url: String, title: String, jobId: String?): String {
        val route = "player/${java.net.URLEncoder.encode(url, "UTF-8")}/${java.net.URLEncoder.encode(title, "UTF-8")}"
        return if (jobId != null) "$route?jobId=${java.net.URLEncoder.encode(jobId, "UTF-8")}" else route
    }

    /**
     * A Settings screen; while it is composed, transient messages use its Snackbar instead of a Toast.
     * In two-pane ([twoPane]) every entry draws the list next to its detail (main shows Processing), and
     * moves between settings entries have no transition, so a row tap looks like a detail swap.
     */
    private fun NavGraphBuilder.settingsRoute(
        route: String,
        nav: NavHostController,
        twoPane: State<Boolean>,
        exitSettings: () -> Unit,
        selectSettings: (String) -> Unit,
        detailContent: @Composable (String) -> Unit,
    ) {
        val inPlace: AnimatedContentTransitionScope<NavBackStackEntry>.() -> Boolean = {
            twoPane.value && initialState.destination.route in SettingsRoutes.SCREENS &&
                targetState.destination.route in SettingsRoutes.SCREENS
        }
        // null falls back to the NavHost slides (compact, and Home <-> Settings)
        composable(
            route,
            enterTransition = { if (inPlace()) EnterTransition.None else null },
            exitTransition = { if (inPlace()) ExitTransition.None else null },
            popEnterTransition = { if (inPlace()) EnterTransition.None else null },
            popExitTransition = { if (inPlace()) ExitTransition.None else null },
        ) { entry ->
            DisposableEffect(Unit) {
                vm.settingsVisible++
                onDispose { vm.settingsVisible-- }
            }
            val detailRoute = SettingsNav.detailFor(route)
            // I5: one list position for the whole Settings session, owned by the settings graph entry
            val graphEntry = remember(entry) { nav.getBackStackEntry(SettingsRoutes.GRAPH) }
            val listState: SettingsListState = viewModel(viewModelStoreOwner = graphEntry)
            // I3: list and detail are movable content created once per entry, so a pane-mode switch moves
            // their composition (dialogs, sheets, scroll, launchers) instead of rebuilding it. They capture
            // only stable objects; the selection is a parameter.
            val list = remember {
                movableContentOf { sel: String? ->
                    SettingsMainScreen(vm, onBack = exitSettings, onNavigate = selectSettings, selectedRoute = sel, listState = listState)
                }
            }
            val detail = remember { movableContentOf { detailContent(detailRoute) } }
            val listWidthDp = AdaptiveLayout.settingsListWidthDp(LocalWindowLayout.current.widthDp)
            // I2: the back stack is the selection at every size (home, main [, one sub-screen])
            if (twoPane.value) {
                SettingsTwoPane(listWidthDp.dp, list = { list(detailRoute) }, detail = { detail() })
            } else if (route == SettingsRoutes.MAIN) {
                list(null)
            } else {
                detail()
            }
        }
    }

    /** [url] null opens the Instagram login; otherwise a custom https login URL. */
    private fun cookieLoginRoute(url: String?): String =
        if (url == null) "cookie_login" else "cookie_login?url=${android.net.Uri.encode(url)}"
}
