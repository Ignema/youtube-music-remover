package com.musicremover.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.musicremover.app.ui.HelpScreen
import com.musicremover.app.ui.HomeScreen
import com.musicremover.app.ui.PermissionsScreen
import com.musicremover.app.ui.PlayerScreen
import com.musicremover.app.ui.SettingsScreen
import com.musicremover.app.ui.theme.MusicRemoverTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            MusicRemoverTheme {
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
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                val urlPattern = Regex("https?://(?:www\\.)?(?:youtube\\.com|youtu\\.be)/\\S+")
                val match = urlPattern.find(shared)
                vm.onUrlChange(match?.value ?: shared)
            }
        }
    }
}
