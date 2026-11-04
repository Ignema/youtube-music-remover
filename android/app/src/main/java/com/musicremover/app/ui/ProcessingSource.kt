package com.musicremover.app.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.musicremover.app.R
import com.musicremover.app.processing.BackendProvider

/** One icon and short label per processing mode, used everywhere (Settings chips, Recent rows, Help). */
data class ProcessingSource(val icon: ImageVector, @StringRes val label: Int)

object ProcessingSources {
    val INTERNAL = ProcessingSource(Icons.Outlined.Memory, R.string.history_source_device)
    val TERMUX = ProcessingSource(Icons.Outlined.Terminal, R.string.history_source_termux)
    val SERVER = ProcessingSource(Icons.Outlined.Cloud, R.string.history_source_server)
}

/** The source for a BackendProvider.MODE_* value, or null if unknown. */
fun processingSourceOf(mode: String): ProcessingSource? = when (mode) {
    BackendProvider.MODE_INTERNAL -> ProcessingSources.INTERNAL
    BackendProvider.MODE_TERMUX -> ProcessingSources.TERMUX
    BackendProvider.MODE_SERVER -> ProcessingSources.SERVER
    else -> null
}
