package com.musicremover.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.musicremover.app.processing.internal.LoginSites
import com.musicremover.app.ui.CookieLoginScreen
import com.musicremover.app.ui.HelpScreen
import com.musicremover.app.ui.HomeScreen
import com.musicremover.app.ui.PermissionsScreen
import com.musicremover.app.ui.PlayerScreen
import com.musicremover.app.ui.SettingsScreen
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
        handleShareIntent(intent)
        handlePlayIntent(intent)
        handleShareAction(intent)
        handleWidgetPaste(intent)
        setContent {
            val ui by vm.ui.collectAsState()
            MusicRemoverTheme(
                themeMode = ui.themeMode,
                dynamicColor = ui.dynamicColor,
            ) {
                val navController = rememberNavController()

                // First run → show permissions screen
                val prefs = getSharedPreferences("app", MODE_PRIVATE)
                val isFirstRun = !prefs.getBoolean("setup_done", false)

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
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            onBack = { navController.popBackStack() },
                            onPermissionsClick = { navController.navigate("permissions") },
                            onLoginClick = { url -> navController.navigate(cookieLoginRoute(url)) },
                        )
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        handlePlayIntent(intent)
        handleShareAction(intent)
        handleWidgetPaste(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                val urlPattern = Regex("https?://\\S+")
                val match = urlPattern.find(shared)
                vm.onUrlChange(match?.value ?: shared)
            }
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

    /** [url] null opens the Instagram login; otherwise a custom https login URL. */
    private fun cookieLoginRoute(url: String?): String =
        if (url == null) "cookie_login" else "cookie_login?url=${android.net.Uri.encode(url)}"

    private fun handleWidgetPaste(intent: Intent?) {
        if (intent?.action == MuremWidget.ACTION_PASTE_PROCESS) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.isNotEmpty()) {
                vm.onUrlChange(clip)
                vm.process()
            }
        } else if (intent?.action == MuremWidget.ACTION_FILE) {
            vm.setInputTab(1)
        } else if (intent?.action == MuremWidget.ACTION_BATCH) {
            vm.setInputTab(2)
        } else if (intent?.action == MuremWidget.ACTION_SETTINGS) {
            vm.pendingNavigation = "settings"
        }
    }
}
