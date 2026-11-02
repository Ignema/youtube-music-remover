package com.musicremover.app.processing

import android.content.Context
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.SettingsStore

/**
 * Picks the processing backend: [current] for new jobs (by processing mode), [forJob] for existing ones
 * (on-device ids start with `"local-"`, everything else belongs to the remote server).
 */
class BackendProvider(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val cache = ResultCache(appContext)

    @Volatile
    private var remote: RemoteServerBackend? = null

    /** The on-device backend, one per provider. */
    val internal: InternalBackend by lazy { InternalBackend(appContext, cache) }

    fun current(): ProcessingBackend =
        if (settings.processingMode == MODE_INTERNAL) internal else remoteFor(remoteUrl())

    fun forJob(jobId: String): ProcessingBackend =
        if (jobId.startsWith(InternalJobEvents.PREFIX)) internal else remoteFor(remoteUrl())

    /** Termux URL in termux mode, else the configured server (also in internal mode, for old server jobs). */
    private fun remoteUrl(): String =
        if (settings.processingMode == MODE_TERMUX) TERMUX_URL else settings.serverUrl

    private fun remoteFor(url: String): RemoteServerBackend {
        remote?.let { if (it.baseUrl == url) return it }
        return RemoteServerBackend(appContext, url, cache).also { remote = it }
    }

    companion object {
        /** URL of the server started inside Termux on this phone. */
        const val TERMUX_URL = "http://127.0.0.1:8000"

        const val MODE_INTERNAL = "internal"
        const val MODE_TERMUX = "termux"
        const val MODE_SERVER = "server"
    }
}
