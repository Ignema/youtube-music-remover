package com.musicremover.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musicremover.app.CookieSiteUi
import com.musicremover.app.MainViewModel
import com.musicremover.app.R
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.CookieStore
import com.musicremover.app.processing.internal.LoginSites
import java.text.DateFormat
import java.util.Date

/**
 * Accounts & cookies: one card per stored site (count, saved date, source, switch, Log in again,
 * Delete), Log in with browser, Import cookies.txt and Delete all. Never shows cookie names or values.
 * [onLogin] gets null for the Instagram preset, else an https login URL.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsSettingsScreen(vm: MainViewModel, onBack: () -> Unit, onLogin: (url: String?) -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.loadCookieSites() }
    var showLogin by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> vm.importCookies(uri) }
    val sites = ui.cookieSites

    SettingsScaffold(
        title = stringResource(R.string.cookies_title),
        onBack = onBack,
        snackMessage = ui.snackError,
        onSnackDismissed = vm::dismissSnackError,
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cookies_delete_all)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    enabled = sites.isNotEmpty(),
                    onClick = {
                        showMenu = false
                        confirmDeleteAll = true
                    },
                )
            }
        },
    ) {
        if (ui.processingMode != BackendProvider.MODE_INTERNAL) {
            SettingsNote(stringResource(R.string.cookies_not_internal_note))
            Spacer(Modifier.height(12.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = { showLogin = true }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cookies_login))
            }
            OutlinedButton(
                // cookies.txt has no registered MIME type; the file is validated on import
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.FileOpen, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.cookies_import))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (sites.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Cookie, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.cookies_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.cookies_empty_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        for (s in sites) {
            SiteCard(
                s = s,
                onToggle = { on -> vm.setCookieSiteEnabled(s.site, on) },
                onLoginAgain = { vm.cookieLoginUrlFor(s.site) { url -> onLogin(url) } },
                onDelete = { confirmDelete = s.site },
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(4.dp))
        SettingsNote(stringResource(R.string.cookies_help))
    }

    if (showLogin) {
        LoginSiteDialog(
            onDismiss = { showLogin = false },
            onContinue = { url ->
                showLogin = false
                onLogin(url)
            },
        )
    }
    confirmDelete?.let { site ->
        ConfirmDialog(
            title = stringResource(R.string.cookies_delete_site_title, site),
            text = stringResource(R.string.cookies_delete_site_text),
            confirmLabel = stringResource(R.string.dialog_delete),
            onConfirm = {
                confirmDelete = null
                vm.deleteCookieSite(site)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    if (confirmDeleteAll) {
        ConfirmDialog(
            title = stringResource(R.string.cookies_delete_all_title),
            text = stringResource(R.string.cookies_delete_all_text),
            confirmLabel = stringResource(R.string.cookies_delete_all),
            onConfirm = {
                confirmDeleteAll = false
                vm.deleteAllCookies()
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
private fun SiteCard(s: CookieSiteUi, onToggle: (Boolean) -> Unit, onLoginAgain: () -> Unit, onDelete: () -> Unit) {
    val desc = stringResource(R.string.cookies_send_for, s.site)
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.savedAtMs))
    val source = stringResource(
        if (s.source == CookieStore.SOURCE_LOGIN) R.string.cookies_from_login else R.string.cookies_from_import,
    )
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.site, style = MaterialTheme.typography.titleMedium)
                    SettingsNote(pluralStringResource(R.plurals.cookies_site_summary, s.count, s.count, date) + " · " + source)
                }
                Switch(
                    checked = s.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = desc },
                )
            }
            Row {
                TextButton(onClick = onLoginAgain) { Text(stringResource(R.string.cookies_login_again)) }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.cookies_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Site picker and notice before the login screen; [onContinue] gets null for Instagram, else the https URL. */
@Composable
private fun LoginSiteDialog(onDismiss: () -> Unit, onContinue: (String?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    val customOk = LoginSites.custom(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cookies_login_notice_title)) },
        text = {
            Column {
                Text(stringResource(R.string.cookies_login_notice), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(selected = !custom, role = Role.RadioButton) { custom = false },
                ) {
                    RadioButton(selected = !custom, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cookies_login_site_instagram))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(selected = custom, role = Role.RadioButton) { custom = true },
                ) {
                    RadioButton(selected = custom, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cookies_login_site_custom))
                }
                if (custom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text(stringResource(R.string.cookies_login_url_hint)) },
                        singleLine = true,
                        isError = url.isNotBlank() && !customOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !custom || customOk,
                onClick = { onContinue(if (custom) url.trim() else null) },
            ) {
                Text(stringResource(R.string.cookies_login_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cookies_login_cancel)) }
        },
    )
}
