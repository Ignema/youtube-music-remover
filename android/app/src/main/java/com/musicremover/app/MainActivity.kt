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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
                            if (url != null && title != null) {
                                vm.pendingPlayUrl = null
                                vm.pendingPlayTitle = null
                                navController.navigate("player/${java.net.URLEncoder.encode(url, "UTF-8")}/${java.net.URLEncoder.encode(title, "UTF-8")}")
                            }
                        }
                        HomeScreen(
                            vm = vm,
                            onSettingsClick = { navController.navigate("settings") },
                            onHelpClick = { navController.navigate("help") },
                            onPlay = { url, title ->
                                navController.navigate("player/${java.net.URLEncoder.encode(url, "UTF-8")}/${java.net.URLEncoder.encode(title, "UTF-8")}")
                            },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            vm = vm,
                            onBack = { navController.popBackStack() },
                            onPermissionsClick = { navController.navigate("permissions") },
                        )
                    }
                    composable("help") {
                        HelpScreen(onBack = { navController.popBackStack() })
                    }
                    composable("player/{url}/{title}") { backStackEntry ->
                        val url = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("url") ?: "", "UTF-8")
                        val title = java.net.URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
                        PlayerScreen(url = url, title = title, onBack = { navController.popBackStack() })
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
    }

    private fun handleShareAction(intent: Intent?) {
        val url = intent?.getStringExtra("share_url") ?: return
        val filename = intent.getStringExtra("share_filename") ?: "result"
        vm.shareByJobUrl(this, url, filename)
    }

    private fun handleWidgetPaste(intent: Intent?) {
        if (intent?.action != MuremWidget.ACTION_PASTE_PROCESS) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (clip.isNotEmpty()) {
            vm.onUrlChange(clip)
            vm.process()
        }
    }
}
