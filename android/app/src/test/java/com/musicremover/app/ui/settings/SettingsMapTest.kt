package com.musicremover.app.ui.settings

import com.musicremover.app.data.SettingsStore
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every setting and Settings action has a place in the new navigation, and prefs keys go through constants. */
class SettingsMapTest {
    // Gradle runs unit tests with the module dir (android/app) as the working dir
    private val src = File("src/main/java")

    @Test
    fun everyUserKeyHasAnEntry() {
        assertEquals(SettingsStore.USER_KEYS, SettingsMap.ENTRIES.keys)
    }

    @Test
    fun everyRouteExists() {
        for ((id, route) in SettingsMap.ENTRIES + SettingsMap.ACTIONS) {
            assertTrue("$id -> $route is not a route", route in SettingsRoutes.ALL)
        }
        assertTrue(SettingsRoutes.MAIN in SettingsRoutes.ALL)
        assertEquals(10, SettingsRoutes.SCREENS.size)
        assertTrue(SettingsRoutes.SCREENS.all { it.startsWith(SettingsRoutes.GRAPH + "/") })
    }

    @Test
    fun masterListIsCovered() {
        val covered = SettingsMap.ENTRIES.keys + SettingsMap.ACTIONS.keys
        for (id in MASTER) assertTrue("$id has no Settings location", id in covered)
    }

    @Test
    fun settingsPrefsUseKeyConstants() {
        val selected = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
            val t = f.readText()
            "getSharedPreferences(\"settings\"" in t || "SettingsStore.PREFS" in t ||
                (f.name == "SettingsStore.kt" && "getSharedPreferences(PREFS" in t)
        }.toList()
        assertTrue("no prefs-settings file found under ${src.absolutePath}", selected.isNotEmpty())
        assertTrue("SettingsStore.kt not selected: $selected", selected.any { it.name == "SettingsStore.kt" })
        for (f in selected) {
            f.readLines().forEachIndexed { i, line ->
                assertTrue("${f.name}:${i + 1} uses a literal prefs key: ${line.trim()}", !LITERAL_KEY.containsMatchIn(line))
            }
        }
    }

    /** The Settings → Update yt-dlp path named in the error texts points at a real row. */
    @Test
    fun ytDlpRowIsTitledUpdateYtDlp() {
        val main = File(src, "com/musicremover/app/ui/settings/SettingsMainScreen.kt").readText()
        val at = main.indexOf("onNavigate(SettingsRoutes.YTDLP)")
        assertTrue("no yt-dlp row in settings/main", at > 0)
        val row = main.substring(main.lastIndexOf("NavRow(", at), at)
        assertTrue("yt-dlp row title: $row", "R.string.settings_row_update_ytdlp" in row)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings_internal.xml"))
        val nodes = doc.getElementsByTagName("string")
        val value = (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttribute("name") == "settings_row_update_ytdlp" }.textContent
        assertEquals("Update yt-dlp", value)
    }

    private companion object {
        val MASTER = listOf(
            "theme_mode", "dynamic_color", "language", "widget_theme", "processing_mode", "server_url",
            "discover_server", "termux_install", "termux_start", "termux_stop", "termux_update",
            "model_download", "model_delete", "ytdlp_update", "cookies_login", "cookies_import",
            "clear_history", "clear_cache", "export_history", "permissions", "about", "help",
        )
        val LITERAL_KEY = Regex("""\b(get|put)(String|Boolean|Int|Long)\("""")
    }
}
