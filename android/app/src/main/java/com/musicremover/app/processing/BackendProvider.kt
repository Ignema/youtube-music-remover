package com.musicremover.app.processing

import android.content.Context
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.SettingsStore

/**
 * Picks the processing backend for new and resumed jobs.
 * Phase 1 always returns the remote server backend; Phase 2 will choose the on-device backend here.
 */
class BackendProvider(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsStore(appContext)
    private val cache = ResultCache(appContext)

    @Volatile
    private var remote: RemoteServerBackend? = null

    fun current(): ProcessingBackend {
        val url = settings.serverUrl
        remote?.let { if (it.baseUrl == url) return it }
        return RemoteServerBackend(appContext, url, cache).also { remote = it }
    }
}
