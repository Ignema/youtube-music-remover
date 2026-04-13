package com.musicremover.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.musicremover.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.how_to_use)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Server setup — first because it's required
            SectionTitle(stringResource(R.string.help_server_setup))
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.help_server_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            TipCard(Icons.Outlined.PhoneAndroid, stringResource(R.string.help_local_termux),
                stringResource(R.string.help_local_termux_desc))
            Spacer(Modifier.height(8.dp))
            TipCard(Icons.Outlined.Cloud, stringResource(R.string.help_remote_server),
                stringResource(R.string.help_remote_server_desc))
            Spacer(Modifier.height(8.dp))
            TipCard(Icons.Outlined.Terminal, stringResource(R.string.help_start_pc),
                "Clone the repo first:\ngit clone https://github.com/Ignema/youtube-music-remover.git\ncd youtube-music-remover\n\nThen start the server:\nuvx --with fastapi --with yt-dlp --with \"audio-separator[gpu]\" uvicorn api.main:app --host 0.0.0.0 --port 8000")
            Spacer(Modifier.height(8.dp))
            TipCard(Icons.Outlined.Cloud, stringResource(R.string.help_docker),
                "docker build -t music-remover api/\ndocker run -p 8000:8000 music-remover\n\nThat's it — no Python or ffmpeg setup needed.")

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // Quick start
            SectionTitle(stringResource(R.string.help_quick_start))
            Spacer(Modifier.height(12.dp))

            StepCard(1, Icons.Outlined.ContentPaste, stringResource(R.string.help_paste_link), stringResource(R.string.help_paste_desc))
            Spacer(Modifier.height(8.dp))
            StepCard(2, Icons.Outlined.MusicOff, stringResource(R.string.help_tap_remove), stringResource(R.string.help_tap_remove_desc))
            Spacer(Modifier.height(8.dp))
            StepCard(3, Icons.Outlined.PlayArrow, stringResource(R.string.help_play_save), stringResource(R.string.help_play_save_desc))

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // Local files
            SectionTitle(stringResource(R.string.help_local_files))
            Spacer(Modifier.height(12.dp))

            StepCard(1, Icons.Outlined.VideoFile, stringResource(R.string.help_pick_file), stringResource(R.string.help_pick_file_desc))
            Spacer(Modifier.height(8.dp))
            StepCard(2, Icons.Outlined.MusicOff, stringResource(R.string.help_process_usual), stringResource(R.string.help_process_usual_desc))

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // Share from YouTube
            SectionTitle(stringResource(R.string.help_share_yt))
            Spacer(Modifier.height(12.dp))

            StepCard(1, Icons.Outlined.Share, stringResource(R.string.help_tap_share), stringResource(R.string.help_tap_share_desc))
            Spacer(Modifier.height(8.dp))
            StepCard(2, Icons.Outlined.MusicOff, stringResource(R.string.help_pick_murem),
                stringResource(R.string.help_pick_murem_desc))

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))

            // Models
            SectionTitle(stringResource(R.string.help_ai_models))
            Spacer(Modifier.height(12.dp))

            TipCard(Icons.Outlined.Tune, stringResource(R.string.model_default_name),
                stringResource(R.string.model_default_desc))
            Spacer(Modifier.height(8.dp))
            TipCard(Icons.Outlined.Tune, stringResource(R.string.model_quality_name),
                stringResource(R.string.model_quality_desc))
            Spacer(Modifier.height(8.dp))
            TipCard(Icons.Outlined.Tune, stringResource(R.string.model_karaoke_name),
                stringResource(R.string.model_karaoke_desc))

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StepCard(step: Int, icon: ImageVector, title: String, description: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Step number badge
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    text = "$step",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TipCard(icon: ImageVector, title: String, description: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
