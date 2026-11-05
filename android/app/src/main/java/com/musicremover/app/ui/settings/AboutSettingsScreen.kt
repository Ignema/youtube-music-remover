package com.musicremover.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Gavel
import com.musicremover.app.data.ErrorReport
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.BuildConfig
import com.musicremover.app.MainViewModel
import com.musicremover.app.R

/** About & help: version (5 taps open the easter egg), description, Help and open-source components. */
@Composable
fun AboutSettingsScreen(vm: MainViewModel, onBack: () -> Unit, onHelp: () -> Unit, onLicenses: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var tapCount by remember { mutableStateOf(0) }
    SettingsScaffold(
        title = stringResource(R.string.settings_row_about),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
    ) {
        Text(
            stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clickable {
                tapCount++
                if (tapCount >= 5) {
                    tapCount = 0
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://quran.com/31/6"),
                    )
                    context.startActivity(intent)
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        SettingsNote(stringResource(R.string.about_desc))
        Spacer(Modifier.height(8.dp))
        NavRow(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.about_help), null, onClick = onHelp)
        NavRow(
            Icons.Outlined.BugReport,
            stringResource(R.string.report_problem),
            stringResource(R.string.report_problem_desc),
            onClick = { ErrorReport.open(context, ErrorReport.Kind.Manual, null, ui.processingMode, ui.selectedModel) },
        )
        NavRow(
            Icons.Outlined.Gavel,
            stringResource(R.string.licenses_title),
            stringResource(R.string.licenses_desc),
            onClick = onLicenses,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.about_components_title))
        Spacer(Modifier.height(8.dp))
        SettingsNote(stringResource(R.string.about_components))
    }
}
