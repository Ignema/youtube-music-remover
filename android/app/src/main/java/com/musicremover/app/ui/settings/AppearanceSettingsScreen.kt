package com.musicremover.app.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.ui.theme.ColorPalette
import com.musicremover.app.ui.theme.ColorPalettes

/** Language codes and their names in that language; "" = system default (name filled in by the caller). */
internal val LANGUAGES = listOf(
    "en" to "English",
    "ar" to "العربية",
    "fr" to "Français",
    "es" to "Español",
    "de" to "Deutsch",
    "tr" to "Türkçe",
    "id" to "Bahasa Indonesia",
    "ms" to "Bahasa Melayu",
    "pt" to "Português",
    "it" to "Italiano",
    "ru" to "Русский",
    "ja" to "日本語",
    "ko" to "한국어",
    "zh" to "中文",
    "hi" to "हिन्दी",
    "ur" to "اردو",
)

@Composable
internal fun themeLabel(mode: String): String = when (mode) {
    "light" -> stringResource(R.string.light)
    "dark" -> stringResource(R.string.dark)
    "black" -> stringResource(R.string.black_oled)
    else -> stringResource(R.string.system)
}

@Composable
internal fun languageLabel(code: String): String =
    LANGUAGES.firstOrNull { it.first == code }?.second ?: stringResource(R.string.system_default)

/** Appearance: theme, Material You (Android 12+), color palette, language and widget theme. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var showThemePicker by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    var showWidgetThemePicker by remember { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.appearance),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        SettingsRow(
            label = stringResource(R.string.theme),
            value = themeLabel(ui.themeMode),
            onClick = { showThemePicker = true },
        )

        Spacer(Modifier.height(4.dp))

        // Dynamic color toggle
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.material_you), style = MaterialTheme.typography.bodyMedium)
                    SettingsNote(stringResource(R.string.material_you_desc))
                }
                Switch(
                    checked = ui.dynamicColor,
                    onCheckedChange = { vm.setDynamicColor(it) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Color palette (Material You replaces it with wallpaper colors when on)
        val dynamicActive = ui.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(stringResource(R.string.palette_title), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                maxItemsInEachRow = 4,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .alpha(if (dynamicActive) 0.38f else 1f),
            ) {
                ColorPalettes.ALL.forEach { palette ->
                    PaletteSwatch(
                        palette = palette,
                        selected = palette.id == ui.colorPalette,
                        enabled = !dynamicActive,
                        isDark = isDark,
                        onClick = { vm.setColorPalette(palette.id) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsNote(
                if (dynamicActive) stringResource(R.string.palette_material_you_hint)
                else stringResource(ColorPalettes.byId(ui.colorPalette).nameRes),
            )
        }

        Spacer(Modifier.height(4.dp))

        SettingsRow(
            label = stringResource(R.string.language),
            value = languageLabel(ui.language),
            onClick = { showLangPicker = true },
        )

        Spacer(Modifier.height(8.dp))

        val widgetThemeLabel = when (ui.widgetTheme) {
            "light" -> stringResource(R.string.light)
            "dark" -> stringResource(R.string.dark)
            "transparent" -> stringResource(R.string.widget_transparent)
            else -> "Orange"
        }
        SettingsRow(
            label = stringResource(R.string.widget_theme),
            value = widgetThemeLabel,
            onClick = { showWidgetThemePicker = true },
        )
    }

    if (showThemePicker) {
        ModalBottomSheet(onDismissRequest = { showThemePicker = false }) {
            // Scrolls when a short window (landscape phone) can't fit the options
            Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                listOf(
                    "system" to stringResource(R.string.system_default),
                    "light" to stringResource(R.string.light),
                    "dark" to stringResource(R.string.dark),
                    "black" to stringResource(R.string.black_oled),
                ).forEach { (value, label) ->
                    PickerOption(
                        label = label,
                        selected = ui.themeMode == value,
                        onClick = { vm.setThemeMode(value); showThemePicker = false },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (showLangPicker) {
        ModalBottomSheet(onDismissRequest = { showLangPicker = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                (listOf("" to stringResource(R.string.system_default)) + LANGUAGES).forEach { (code, name) ->
                    PickerOption(
                        label = name,
                        selected = ui.language == code,
                        onClick = { vm.setLanguage(code); showLangPicker = false },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    if (showWidgetThemePicker) {
        ModalBottomSheet(onDismissRequest = { showWidgetThemePicker = false }) {
            Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.widget_theme), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                listOf(
                    "orange" to "Orange",
                    "light" to stringResource(R.string.light),
                    "dark" to stringResource(R.string.dark),
                    "transparent" to stringResource(R.string.widget_transparent),
                ).forEach { (value, label) ->
                    PickerOption(
                        label = label,
                        selected = ui.widgetTheme == value,
                        onClick = { vm.setWidgetTheme(value); showWidgetThemePicker = false },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** A 48dp circular palette swatch; the selected one gets a ring and a check mark. */
@Composable
private fun PaletteSwatch(
    palette: ColorPalette,
    selected: Boolean,
    enabled: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val scheme = if (isDark) palette.dark else palette.light
    val name = stringResource(palette.nameRes)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
    ) {
        if (selected) {
            Box(Modifier.size(46.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.primary),
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
