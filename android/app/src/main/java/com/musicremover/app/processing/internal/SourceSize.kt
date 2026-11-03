package com.musicremover.app.processing.internal

import android.content.Context
import android.net.Uri

/** Size in bytes of the file behind [uri], or 0 if unknown. Blocking. */
internal fun sourceSize(ctx: Context, uri: Uri): Long = try {
    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
} catch (e: Exception) {
    0L
}
