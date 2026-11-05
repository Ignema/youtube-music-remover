package com.musicremover.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musicremover.app.R
import com.musicremover.app.ui.adaptive.AdaptiveLayout
import com.musicremover.app.ui.adaptive.LocalWindowLayout
import com.musicremover.app.ui.adaptive.TopBarStyle

/**
 * Shared frame of every Settings screen: collapsing large top bar with a back arrow, a scrolling
 * column with 24 dp side padding, and a Snackbar for [snackMessage] (the ViewModel's transient message).
 * In the two-pane frame ([LocalSettingsPane]) the detail has no back arrow and the list shows no Snackbar.
 * [listState] is the graph-scoped list position; only the Settings list passes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackMessage: String?,
    onSnackDismissed: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    listState: SettingsListState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pane = LocalSettingsPane.current
    val windowLayout = LocalWindowLayout.current
    val bar = AdaptiveLayout.topBarStyle(windowLayout.width, windowLayout.height)
    // All states exist at every size, so the slots never depend on the size or on listState
    val ownScroll = rememberScrollState()
    val ownLarge = rememberTopAppBarState()
    val ownSmall = rememberTopAppBarState()
    val scroll = listState?.scroll ?: ownScroll
    val largeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = listState?.largeBar ?: ownLarge)
    val smallBar = TopAppBarDefaults.pinnedScrollBehavior(state = listState?.smallBar ?: ownSmall)
    val active = if (bar == TopBarStyle.LARGE) largeBar else smallBar
    val snackbarHost = remember { SnackbarHostState() }
    // The list pane passes no message, so a two-pane message shows once, in the detail
    val shownMessage = if (pane == SettingsPane.LIST) null else snackMessage
    // A new message replaces the old one; null (the ViewModel's 4 s timeout) cancels and hides it
    LaunchedEffect(shownMessage) {
        if (shownMessage != null) {
            val r = snackbarHost.showSnackbar(shownMessage, withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.Dismissed) onSnackDismissed()
        }
    }
    // Off compact, keep content and bar actions out of a display cutout; compact keeps the defaults
    val cutoutInsets = AdaptiveLayout.useCutoutInsets(windowLayout.width)
    val contentInsets = if (cutoutInsets) WindowInsets.systemBars.union(WindowInsets.displayCutout)
    else ScaffoldDefaults.contentWindowInsets
    val barInsets = if (cutoutInsets) {
        WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
    } else TopAppBarDefaults.windowInsets
    val navigationIcon: @Composable () -> Unit = {
        if (pane != SettingsPane.DETAIL) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        }
    }
    val max = when (pane) {
        SettingsPane.SINGLE -> AdaptiveLayout.settingsSingleMaxDp(windowLayout.width)
        SettingsPane.DETAIL -> AdaptiveLayout.SETTINGS_DETAIL_MAX_DP
        SettingsPane.LIST -> null
    }
    Scaffold(
        modifier = Modifier.nestedScroll(active.nestedScrollConnection),
        contentWindowInsets = contentInsets,
        snackbarHost = { if (pane != SettingsPane.LIST) SnackbarHost(snackbarHost) },
        topBar = {
            if (bar == TopBarStyle.LARGE) {
                LargeTopAppBar(
                    title = { Text(title) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    windowInsets = barInsets,
                    scrollBehavior = largeBar,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            } else {
                // Short window that isn't compact-width (landscape phone): a 64dp pinned bar
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    windowInsets = barInsets,
                    scrollBehavior = smallBar,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(scroll),
        ) {
            // I3: the wrapper is emitted at every size and only its modifier changes, so content() keeps
            // its slot (dialogs, sheets, launchers) when the window crosses the compact boundary
            Column(
                Modifier
                    .then(if (max != null) Modifier.align(Alignment.CenterHorizontally).widthIn(max = max.dp) else Modifier)
                    .fillMaxWidth(),
            ) {
                content()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Muted explanatory text under a section. */
@Composable
fun SettingsNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A row of the Settings list that opens another screen. [selected] is null in a single-pane list;
 * in the two-pane list every row is a tab and the shown detail's row is highlighted.
 */
@Composable
fun NavRow(icon: ImageVector, title: String, summary: String?, selected: Boolean? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it, maxLines = 2) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = if (selected == true) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when (selected) {
                    null -> Modifier.clickable(onClick = onClick)
                    true -> Modifier.clip(RoundedCornerShape(16.dp)).selectable(true, role = Role.Tab, onClick = onClick)
                    false -> Modifier.selectable(false, role = Role.Tab, onClick = onClick)
                },
            ),
    )
}

/** Confirmation for a hard-to-reverse action; [confirmLabel] is the destructive button. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/** Decimal megabytes, as in the model catalog ("29.7 MB"). */
fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1_000_000.0)
