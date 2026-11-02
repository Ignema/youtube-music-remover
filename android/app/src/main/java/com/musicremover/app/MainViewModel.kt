package com.musicremover.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import com.musicremover.app.R
import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.HistoryStore
import com.musicremover.app.data.NotificationHelper
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.SettingsStore
import com.musicremover.app.data.TermuxHelper
import com.musicremover.app.data.VideoInfo
import com.musicremover.app.data.displayTitle
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.InternalBackend
import com.musicremover.app.processing.InternalJobEvents
import com.musicremover.app.processing.JobEvent
import com.musicremover.app.processing.ProcessOptions
import com.musicremover.app.processing.ProcessingBackend
import com.musicremover.app.processing.RemoteServerBackend
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelCorruptException
import com.musicremover.app.processing.internal.ModelManager
import com.musicremover.app.processing.internal.ModelState
import com.musicremover.app.processing.internal.ModelStorageException
import com.musicremover.app.processing.statusLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UiState { Idle, Processing, Done, Error }

data class QueueItem(
    val url: String,
    val model: String,
    val audioOnly: Boolean,
    val bitrate: String,
    val fileUri: android.net.Uri? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
) {
    /** Serialize for SharedPreferences (file URIs stored as strings) */
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "url" to url, "model" to model, "audioOnly" to audioOnly, "bitrate" to bitrate,
        "fileUri" to fileUri?.toString(), "fileName" to fileName, "fileSize" to fileSize,
    )

    companion object {
        fun fromJsonMap(m: Map<String, Any?>): QueueItem = QueueItem(
            url = m["url"] as? String ?: "",
            model = m["model"] as? String ?: "Kim_Vocal_2.onnx",
            audioOnly = m["audioOnly"] as? Boolean ?: false,
            bitrate = m["bitrate"] as? String ?: "192k",
            fileUri = (m["fileUri"] as? String)?.let { android.net.Uri.parse(it) },
            fileName = m["fileName"] as? String,
            fileSize = (m["fileSize"] as? Number)?.toLong(),
        )
    }
}

data class MainUiState(
    val state: UiState = UiState.Idle,
    val inputTab: Int = 0, // 0=URL, 1=File, 2=Batch
    val batchText: String = "",
    val url: String = "",
    val selectedModel: String = "Kim_Vocal_2.onnx",
    val models: List<String> = RemoteServerBackend.SERVER_MODELS,
    val customModel: String = "",
    val progress: Int = 0,
    val statusText: String = "",
    val errorMessage: String = "",
    val jobId: String? = null,
    val showModelPicker: Boolean = false,
    val showModeDialog: Boolean = false,
    val downloading: Boolean = false,
    val sharing: Boolean = false,
    val checkingPlay: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val serverUrl: String = "",
    val termuxInstalled: Boolean = false,
    val selectedFileUri: android.net.Uri? = null,
    // Queue
    val queue: List<QueueItem> = emptyList(),
    val processingMinimized: Boolean = false,
    val selectedFileName: String? = null,
    val selectedFileSize: Long? = null,
    val originalFileUri: String? = null,
    val videoInfo: VideoInfo? = null,
    val showInfoSheet: Boolean = false,
    val loadingInfo: Boolean = false,
    val videoInfoError: Boolean = false,
    val fileInfoItem: HistoryItem? = null,
    val currentInfoItem: HistoryItem? = null,
    // New options
    val audioOnly: Boolean = false,
    val bitrate: String = "192k",
    // Thumbnail preview
    val urlPreview: VideoInfo? = null,
    val loadingPreview: Boolean = false,
    // Termux operation status
    val termuxOperation: String? = null, // null = no operation, else = status message
    val termuxServerOnline: Boolean = false,
    // Server connectivity
    val serverConnected: Boolean = false,
    val serverChecking: Boolean = true,
    // Transient error (auto-dismissing)
    val snackError: String? = null,
    // Appearance
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val language: String = "",
    val widgetTheme: String = "orange",
    // Processing mode: "internal", "termux" or "server"
    val processingMode: String = BackendProvider.MODE_SERVER,
    // On-device model (HQ_3)
    val modelState: ModelState = ModelState.NotDownloaded,
    val modelSizeBytes: Long = 66_759_214,
    /** Why this phone cannot run on-device processing, or null if it can */
    val internalUnsupportedReason: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    private val historyStore = HistoryStore(application)
    private val settingsStore = SettingsStore(application)
    private val notif = NotificationHelper(application)
    private val cache = ResultCache(application)
    private val backends = BackendProvider(application)
    private fun backend() = backends.current()
    private fun isInternalMode() = _ui.value.processingMode == BackendProvider.MODE_INTERNAL
    private val modelManager = ModelManager.get(application)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = application.getSharedPreferences("app", Context.MODE_PRIVATE)
    private var pollingJob: kotlinx.coroutines.Job? = null

    /** A queued file item waits for the app to come to the foreground (on-device mode, §3.5) */
    @Volatile
    private var pendingInternalStart = false

    private val gson = com.google.gson.Gson()

    private fun saveQueue(queue: List<QueueItem>) {
        val json = gson.toJson(queue.map { it.toJsonMap() })
        prefs.edit().putString("queue", json).apply()
    }

    private fun loadQueue(): List<QueueItem> {
        val json = prefs.getString("queue", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
            val list: List<Map<String, Any?>> = gson.fromJson(json, type)
            list.map { QueueItem.fromJsonMap(it) }
        } catch (_: Exception) { emptyList() }
    }

    init {
        _ui.value = _ui.value.copy(
            history = historyStore.getAll(),
            serverUrl = settingsStore.serverUrl,
            termuxInstalled = TermuxHelper.isTermuxInstalled(application),
            themeMode = settingsStore.themeMode,
            dynamicColor = settingsStore.dynamicColor,
            language = settingsStore.language,
            widgetTheme = settingsStore.widgetTheme,
            processingMode = settingsStore.processingMode,
        )
        applyModelsForMode()
        // Resume polling if app was killed mid-processing
        val savedJobId = prefs.getString("active_job_id", null)
        // Remove temps, markers and work dirs left by killed jobs; keep the resumed job and live workers
        bgScope.launch {
            val keep = try {
                val live = androidx.work.WorkManager.getInstance(application)
                    .getWorkInfosByTagFlow(InternalBackend.WORK_TAG).first()
                    .filter { !it.state.isFinished }
                    .map { InternalJobEvents.PREFIX + it.id }
                setOfNotNull(savedJobId) + live
            } catch (e: Exception) {
                // Deleting a live worker's temp file is worse than leaving a stale one
                android.util.Log.w(TAG, "Skipping stale result cleanup", e)
                null
            }
            if (keep != null) {
                cache.cleanStale(keep)
                backends.internal.cleanStaleWork(keep)
            }
        }
        val savedQueue = loadQueue()
        if (savedJobId != null) {
            _ui.value = _ui.value.copy(
                state = UiState.Processing, jobId = savedJobId,
                statusText = application.getString(R.string.resuming),
                queue = savedQueue,
            )
            pollingJob = bgScope.launch { runJob(savedJobId) }
        } else if (prefs.getBoolean(PENDING_INTERNAL_START, false) && savedQueue.isNotEmpty()) {
            // A queued on-device item was waiting for the app; the first onResume starts it
            pendingInternalStart = true
            _ui.value = _ui.value.copy(
                state = UiState.Idle,
                statusText = application.getString(R.string.internal_waiting_foreground),
                queue = savedQueue,
            )
        } else {
            if (savedQueue.isNotEmpty()) {
                // No active job but queue was saved — clear stale queue
                prefs.edit().remove("queue").apply()
            }
            prefs.edit().remove(PENDING_INTERNAL_START).apply()
        }

        // On-device model state for Settings
        bgScope.launch {
            modelManager.state.collect { states ->
                _ui.value = _ui.value.copy(modelState = states[ModelCatalog.HQ3] ?: ModelState.NotDownloaded)
            }
        }
        bgScope.launch {
            val reason = backends.internal.unsupportedReason()
            _ui.value = _ui.value.copy(internalUnsupportedReason = reason)
        }

        // Periodic server connectivity check (only when app is in foreground)
        bgScope.launch {
            while (true) {
                if (appInForeground) {
                    val connected = backend().isReady()
                    _ui.value = _ui.value.copy(serverConnected = connected, serverChecking = false)
                    kotlinx.coroutines.delay(10000)
                    _ui.value = _ui.value.copy(serverChecking = true)
                } else {
                    kotlinx.coroutines.delay(2000)
                }
            }
        }
    }

    @Volatile
    private var appInForeground = true

    fun setForeground(fg: Boolean) {
        appInForeground = fg
        if (fg && pendingInternalStart && _ui.value.state != UiState.Processing) {
            pendingInternalStart = false
            prefs.edit().remove(PENDING_INTERNAL_START).apply()
            processNextInQueue()
        }
    }

    private fun clearPendingInternalStart() {
        pendingInternalStart = false
        prefs.edit().remove(PENDING_INTERNAL_START).apply()
    }

    // --- Processing mode / on-device model ---
    /** Model list of the current backend; the selected model is coerced in internal mode */
    private fun applyModelsForMode() {
        val models = backend().supportedModels()
        val ui = _ui.value
        val selected = if (ui.processingMode == BackendProvider.MODE_INTERNAL && ui.selectedModel !in models) {
            ModelCatalog.HQ3
        } else ui.selectedModel
        _ui.value = ui.copy(models = models, selectedModel = selected)
    }

    fun setProcessingMode(mode: String) {
        if (mode !in com.musicremover.app.data.SettingsStore.PROCESSING_MODES) return
        settingsStore.processingMode = mode
        _ui.value = _ui.value.copy(processingMode = mode)
        when (mode) {
            // Same as the old Local chip
            BackendProvider.MODE_TERMUX -> onServerUrlChange(BackendProvider.TERMUX_URL)
            // Same as the old Custom chip
            BackendProvider.MODE_SERVER -> if (_ui.value.serverUrl == BackendProvider.TERMUX_URL) onServerUrlChange("http://")
        }
        applyModelsForMode()
        refresh()
    }

    fun downloadModel() {
        bgScope.launch {
            try {
                modelManager.ensure(ModelCatalog.HQ3)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ModelStorageException) {
                showTransientError(getApplication<Application>().getString(R.string.internal_no_space, (e.neededBytes / 1_000_000).toInt()))
            } catch (e: ModelCorruptException) {
                showTransientError(str(R.string.internal_model_corrupt))
            } catch (e: Exception) {
                showTransientError(getApplication<Application>().getString(R.string.internal_model_download_failed, e.message ?: ""))
            }
        }
    }

    fun deleteModel() {
        if (!modelManager.delete(ModelCatalog.HQ3)) {
            showTransientError(str(R.string.internal_model_in_use))
        }
    }

    // --- Haptics ---
    fun hapticTick() {
        try {
            val app = getApplication<Application>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
            // Ignore vibration failures
        }
    }

    // --- Basic state ---
    fun setInputTab(tab: Int) {
        _ui.value = _ui.value.copy(inputTab = tab, showModeDialog = false)
    }

    fun showModeDialog() {
        _ui.value = _ui.value.copy(showModeDialog = true)
    }

    fun dismissModeDialog() {
        _ui.value = _ui.value.copy(showModeDialog = false)
    }

    fun onBatchTextChange(text: String) {
        _ui.value = _ui.value.copy(batchText = text)
    }

    fun onUrlChange(url: String) {
        previewJob?.cancel()
        // On-device mode cannot fetch link metadata
        if (looksLikeUrl(url) && !isInternalMode()) {
            _ui.value = _ui.value.copy(url = url, urlPreview = null, loadingPreview = true)
            fetchUrlPreview(url)
        } else {
            _ui.value = _ui.value.copy(url = url, urlPreview = null, loadingPreview = false)
        }
    }

    fun onModelSelect(model: String) {
        _ui.value = _ui.value.copy(selectedModel = model, showModelPicker = false)
    }

    fun onCustomModelChange(value: String) {
        _ui.value = _ui.value.copy(customModel = value)
    }

    fun applyCustomModel() {
        val custom = _ui.value.customModel.trim()
        if (custom.isNotEmpty()) {
            _ui.value = _ui.value.copy(selectedModel = custom, showModelPicker = false)
        }
    }

    fun toggleModelPicker() {
        _ui.value = _ui.value.copy(showModelPicker = !_ui.value.showModelPicker)
    }

    fun setAudioOnly(value: Boolean) {
        _ui.value = _ui.value.copy(audioOnly = value)
    }

    fun setBitrate(value: String) {
        _ui.value = _ui.value.copy(bitrate = value)
    }

    fun setThemeMode(mode: String) {
        _ui.value = _ui.value.copy(themeMode = mode)
        settingsStore.themeMode = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        _ui.value = _ui.value.copy(dynamicColor = enabled)
        settingsStore.dynamicColor = enabled
    }

    fun setLanguage(code: String) {
        _ui.value = _ui.value.copy(language = code)
        settingsStore.language = code
        // Apply locale change via AppCompat
        val locales = if (code.isEmpty()) {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        } else {
            androidx.core.os.LocaleListCompat.forLanguageTags(code)
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setWidgetTheme(theme: String) {
        _ui.value = _ui.value.copy(widgetTheme = theme)
        settingsStore.widgetTheme = theme
        // Update all widgets
        val app = getApplication<Application>()
        val mgr = android.appwidget.AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(android.content.ComponentName(app, MuremWidget::class.java))
        for (id in ids) MuremWidget.updateWidget(app, mgr, id)
    }

    fun reset() {
        _ui.value = MainUiState(
            history = _ui.value.history,
            serverUrl = _ui.value.serverUrl,
            termuxInstalled = _ui.value.termuxInstalled,
            processingMode = _ui.value.processingMode,
            modelState = _ui.value.modelState,
            internalUnsupportedReason = _ui.value.internalUnsupportedReason,
        )
        applyModelsForMode()
    }

    /** Pull-to-refresh: reload history and check server status */
    fun refresh() {
        _ui.value = _ui.value.copy(history = historyStore.getAll(), serverChecking = true)
        bgScope.launch {
            val connected = backend().isReady()
            _ui.value = _ui.value.copy(serverConnected = connected, serverChecking = false)
        }
    }

    fun onFileSelected(uri: android.net.Uri?, name: String?, size: Long? = null, originalUri: String? = null) {
        _ui.value = _ui.value.copy(selectedFileUri = uri, selectedFileName = name, selectedFileSize = size, originalFileUri = originalUri)
    }

    fun reprocessFromHistory(item: HistoryItem) {
        val uriStr = item.sourceFilePath
        if (uriStr != null) {
            val uri = android.net.Uri.parse(uriStr)
            // Copy to cache again for processing (persistable permission lets us read it)
            val context = getApplication<Application>()
            try {
                val fileName = item.sourceFileName ?: "video.mp4"
                val cacheFile = java.io.File(context.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                val cachedUri = android.net.Uri.fromFile(cacheFile)
                _ui.value = _ui.value.copy(
                    selectedFileUri = cachedUri,
                    selectedFileName = item.sourceFileName,
                    selectedFileSize = item.sourceFileSize,
                    originalFileUri = uriStr,
                    url = "",
                    urlPreview = null,
                )
            } catch (_: Exception) {
                showTransientError("Original file no longer available")
            }
        } else {
            showTransientError("Original file path not saved")
        }
    }

    fun clearFile() {
        _ui.value = _ui.value.copy(selectedFileUri = null, selectedFileName = null, selectedFileSize = null, originalFileUri = null)
    }

    // --- URL preview ---
    private var previewJob: kotlinx.coroutines.Job? = null

    private fun fetchUrlPreview(url: String) {
        previewJob?.cancel()
        previewJob = bgScope.launch {
            delay(600) // Debounce
            try {
                val info = kotlinx.coroutines.withTimeout(10_000) {
                    backend().info(url)
                }
                if (_ui.value.url.isNotEmpty()) {
                    _ui.value = _ui.value.copy(urlPreview = info, loadingPreview = false)
                }
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(loadingPreview = false)
            }
        }
    }

    /** Check if input looks like a URL or YouTube video ID */
    private fun looksLikeUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.length < 5) return false
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return true
        return trimmed.matches(Regex("^https?://\\S+$"))
    }

    // --- History ---
    fun clearHistory() {
        historyStore.clear()
        _ui.value = _ui.value.copy(history = emptyList())
    }

    fun deleteHistoryItem(item: HistoryItem) {
        val updated = _ui.value.history.filter { it.jobId != item.jobId }
        historyStore.replace(updated)
        cache.delete(item.jobId)
        _ui.value = _ui.value.copy(history = updated)
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".mp3") -> "audio/mpeg"
        name.endsWith(".m4a") -> "audio/mp4"
        else -> "video/mp4"
    }

    private fun viewFile(context: Context, file: java.io.File) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(cache.contentUri(file), mimeFor(file.name))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun tryPlayHistoryItem(item: HistoryItem, context: Context) {
        bgScope.launch {
            // Try local cache first
            val cachedFile = cache.getCachedFile(item.jobId)
                ?: if (!_ui.value.serverConnected) cache.getUnverifiedFile(item.jobId) else null
            if (cachedFile != null) {
                viewFile(context, cachedFile)
                return@launch
            }
            // Last resort when the server cannot provide the result: an unverified local file
            val openUnverifiedOrUrl = {
                val unverified = cache.getUnverifiedFile(item.jobId)
                if (unverified != null) {
                    viewFile(context, unverified)
                } else {
                    _ui.value = _ui.value.copy(url = item.url)
                }
            }
            // Fall back to server, cache for next time
            try {
                val backend = backends.forJob(item.jobId)
                if (backend.isResultAvailable(item.jobId)) {
                    val file = backend.fetchResult(item.jobId, item.filename)
                    if (file != null) {
                        viewFile(context, file)
                    }
                } else {
                    openUnverifiedOrUrl()
                }
            } catch (_: Exception) {
                openUnverifiedOrUrl()
            }
        }
    }

    // Track which history item is being viewed (for delete)
    var currentInfoItem: HistoryItem? = null
        private set

    // Pending play from notification action
    var pendingPlayUrl: String? = null
    var pendingPlayTitle: String? = null
    var pendingPlayJobId: String? = null
    var pendingNavigation: String? = null

    fun dismissInfoSheet() {
        shareJob?.cancel()
        _ui.value = _ui.value.copy(showInfoSheet = false, videoInfo = null, videoInfoError = false, fileInfoItem = null, sharing = false)
        currentInfoItem = null
    }

    fun showFileInfo(item: HistoryItem) {
        currentInfoItem = item
        _ui.value = _ui.value.copy(showInfoSheet = true, fileInfoItem = null, videoInfoError = false, loadingInfo = false, videoInfo = null)
    }

    /** Show info sheet for any history item — uses cached YT data if available */
    fun showVideoInfoSheet(item: HistoryItem) {
        currentInfoItem = item
        // Use cached YouTube metadata if available
        val cachedInfo = if (item.ytTitle != null || item.ytThumbnail != null) {
            VideoInfo(
                title = item.ytTitle ?: "",
                channel = item.ytChannel ?: "",
                thumbnail = item.ytThumbnail ?: "",
                duration = item.ytDuration,
                view_count = item.ytViewCount,
                upload_date = item.ytUploadDate ?: "",
                description = item.ytDescription ?: "",
            )
        } else null

        val hasUrl = item.url.isNotEmpty()
        val needsFetch = hasUrl && cachedInfo == null

        _ui.value = _ui.value.copy(
            showInfoSheet = true, fileInfoItem = null,
            loadingInfo = needsFetch,
            videoInfo = cachedInfo,
            videoInfoError = false,
        )
        if (needsFetch) {
            bgScope.launch {
                try {
                    val info = kotlinx.coroutines.withTimeout(10_000) { backend().info(item.url) }
                    _ui.value = _ui.value.copy(videoInfo = info, loadingInfo = false)
                } catch (_: Exception) {
                    _ui.value = _ui.value.copy(loadingInfo = false, videoInfoError = true)
                }
            }
        } else if (hasUrl && cachedInfo != null && cachedInfo.duration == 0) {
            // Have basic info but missing rich data — try server in background
            bgScope.launch {
                try {
                    val rich = kotlinx.coroutines.withTimeout(8_000) { backend().info(item.url) }
                    if (_ui.value.showInfoSheet) {
                        _ui.value = _ui.value.copy(videoInfo = rich)
                    }
                } catch (_: Exception) { /* keep cached data */ }
            }
        }
    }

    // --- Server/Termux ---
    fun onServerUrlChange(url: String) {
        _ui.value = _ui.value.copy(serverUrl = url)
        settingsStore.serverUrl = url
    }

    /** Discover Murem server on local network via mDNS/NSD */
    fun discoverServer() {
        val app = getApplication<Application>()
        val nsdManager = app.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
        showTransientError("Searching for server…")

        var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
        discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                showTransientError("Discovery failed")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(si: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: android.net.nsd.NsdServiceInfo) {
                        val host = si.host?.hostAddress ?: return
                        val port = si.port
                        val url = "http://$host:$port"
                        onServerUrlChange(url)
                        showTransientError("Found server at $url")
                        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
                    }
                })
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {}
        }

        try {
            nsdManager.discoverServices("_murem._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            bgScope.launch {
                delay(10000)
                try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            showTransientError("Discovery error: ${e.message}")
        }
    }

    fun installTermuxServer() {
        val error = TermuxHelper.installServer(getApplication())
        if (error != null) {
            _ui.value = _ui.value.copy(state = UiState.Error, errorMessage = error)
        } else {
            _ui.value = _ui.value.copy(termuxOperation = str(R.string.installing_termux))
        }
    }

    fun startTermuxServer() {
        val error = TermuxHelper.startServer(getApplication())
        if (error != null) {
            _ui.value = _ui.value.copy(state = UiState.Error, errorMessage = error)
        } else {
            setProcessingMode(BackendProvider.MODE_TERMUX)
            _ui.value = _ui.value.copy(termuxOperation = str(R.string.starting_server))
            pollServerOnline()
        }
    }

    fun stopTermuxServer() {
        val error = TermuxHelper.stopServer(getApplication())
        if (error != null) {
            _ui.value = _ui.value.copy(state = UiState.Error, errorMessage = error)
        } else {
            _ui.value = _ui.value.copy(termuxOperation = str(R.string.stopping_server), termuxServerOnline = false)
            bgScope.launch {
                delay(2000)
                _ui.value = _ui.value.copy(termuxOperation = null)
            }
        }
    }

    fun updateTermuxServer() {
        val error = TermuxHelper.updateServer(getApplication())
        if (error != null) {
            _ui.value = _ui.value.copy(state = UiState.Error, errorMessage = error)
        } else {
            _ui.value = _ui.value.copy(termuxOperation = str(R.string.updating_termux))
        }
    }

    fun dismissTermuxOperation() {
        _ui.value = _ui.value.copy(termuxOperation = null)
    }

    fun getCacheSizeFormatted(): String {
        val bytes = cache.totalSize()
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun clearCache() {
        cache.clear()
    }

    /** Export all history results — downloads from server and shares as a batch */
    fun exportHistory(context: Context) {
        val items = _ui.value.history
        if (items.isEmpty()) return
        showTransientError("Exporting ${items.size} items…")
        bgScope.launch {
            try {
                val exportDir = java.io.File(context.cacheDir, "export")
                exportDir.mkdirs()
                // Clean old exports
                exportDir.listFiles()?.forEach { it.delete() }

                var exported = 0
                for (item in items) {
                    try {
                        // Try local cache first
                        val cachedFile = cache.getCachedFile(item.jobId)
                        val target = java.io.File(exportDir, item.filename)
                        if (cachedFile != null) {
                            cachedFile.copyTo(target, overwrite = true)
                            exported++
                        } else {
                            // Download from server
                            var downloaded = false
                            try {
                                val remote = backends.forJob(item.jobId).remoteResultUrl(item.jobId)
                                if (remote != null) {
                                    val conn = java.net.URL(remote).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 10000
                                    conn.readTimeout = 30000
                                    conn.connect()
                                    if (conn.responseCode == 200) {
                                        conn.inputStream.use { input ->
                                            target.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        downloaded = true
                                    }
                                    conn.disconnect()
                                }
                            } catch (_: Exception) { /* try the unverified local file */ }
                            if (downloaded) {
                                exported++
                            } else {
                                val unverified = cache.getUnverifiedFile(item.jobId)
                                if (unverified != null) {
                                    unverified.copyTo(target, overwrite = true)
                                    exported++
                                }
                            }
                        }
                    } catch (_: Exception) { /* skip failed items */ }
                }

                if (exported > 0) {
                    // Share all files
                    val uris = exportDir.listFiles()?.map {
                        androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", it
                        )
                    } ?: emptyList()

                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "video/*"
                        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = android.content.Intent.createChooser(intent, "Export $exported files")
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                } else {
                    showTransientError("No files available to export")
                }
            } catch (e: Exception) {
                showTransientError("Export failed: ${e.message}")
            }
        }
    }

    private fun pollServerOnline() {
        bgScope.launch {
            repeat(60) { // Try for ~2 minutes
                delay(2000)
                try {
                    val response = java.net.URL("${BackendProvider.TERMUX_URL}/api/models").readText()
                    if (response.contains("models")) {
                        _ui.value = _ui.value.copy(
                            termuxOperation = str(R.string.server_online),
                            termuxServerOnline = true,
                        )
                        hapticTick()
                        delay(2000)
                        _ui.value = _ui.value.copy(termuxOperation = null)
                        return@launch
                    }
                } catch (_: Exception) {
                    // Not ready yet
                }
            }
            // Timed out
            _ui.value = _ui.value.copy(
                termuxOperation = str(R.string.server_timeout),
            )
        }
    }

    // --- Processing ---
    fun process() {
        // Handle batch mode
        if (_ui.value.inputTab == 2) {
            val urls = _ui.value.batchText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (urls.isEmpty()) {
                showTransientError(str(R.string.enter_url_or_file))
                return
            }
            if (isInternalMode()) {
                showTransientError(str(R.string.internal_url_unsupported))
                return
            }
            queueUrls(urls)
            _ui.value = _ui.value.copy(batchText = "")
            return
        }

        val fileUri = _ui.value.selectedFileUri
        val url = _ui.value.url.trim()

        if (fileUri == null && url.isEmpty()) {
            showTransientError(str(R.string.enter_url_or_file))
            return
        }
        // On-device mode handles local files only; the link stays in the field
        if (fileUri == null && isInternalMode()) {
            showTransientError(str(R.string.internal_url_unsupported))
            return
        }

        // If already processing, add to queue instead
        if (_ui.value.state == UiState.Processing) {
            if (fileUri != null) {
                addFileToQueue(fileUri)
                clearFile()
            } else if (url.isNotEmpty()) {
                addToQueue(url)
                _ui.value = _ui.value.copy(url = "", urlPreview = null)
            }
            return
        }

        if (fileUri != null) {
            processFile(fileUri)
            clearFile()
        } else if (url.isNotEmpty()) {
            processUrl(url)
            _ui.value = _ui.value.copy(url = "", urlPreview = null)
        }
        hapticTick()
    }

    fun addToQueue(url: String) {
        val item = QueueItem(
            url = url,
            model = _ui.value.selectedModel,
            audioOnly = _ui.value.audioOnly,
            bitrate = _ui.value.bitrate,
        )
        val updated = _ui.value.queue + item
        _ui.value = _ui.value.copy(queue = updated)
        saveQueue(updated)
        hapticTick()
    }

    /** Queue multiple URLs at once (from URL file import) */
    fun queueUrls(urls: List<String>) {
        if (isInternalMode()) {
            showTransientError(str(R.string.internal_url_unsupported))
            return
        }
        val items = urls.map { url ->
            QueueItem(
                url = url,
                model = _ui.value.selectedModel,
                audioOnly = _ui.value.audioOnly,
                bitrate = _ui.value.bitrate,
            )
        }
        if (_ui.value.state == UiState.Processing) {
            val updated = _ui.value.queue + items
            _ui.value = _ui.value.copy(queue = updated)
            saveQueue(updated)
        } else {
            // Start processing the first, queue the rest
            val first = items.first()
            val rest = items.drop(1)
            _ui.value = _ui.value.copy(queue = rest, url = first.url)
            saveQueue(rest)
            processUrl(first.url)
        }
        showTransientError("${urls.size} URLs queued")
        hapticTick()
    }

    private fun addFileToQueue(uri: android.net.Uri) {
        val item = QueueItem(
            url = "",
            model = _ui.value.selectedModel,
            audioOnly = _ui.value.audioOnly,
            bitrate = _ui.value.bitrate,
            fileUri = uri,
            fileName = _ui.value.selectedFileName,
            fileSize = _ui.value.selectedFileSize,
        )
        val updated = _ui.value.queue + item
        _ui.value = _ui.value.copy(queue = updated)
        saveQueue(updated)
        hapticTick()
    }

    fun removeFromQueue(index: Int) {
        val updated = _ui.value.queue.toMutableList()
        if (index in updated.indices) updated.removeAt(index)
        _ui.value = _ui.value.copy(queue = updated)
        saveQueue(updated)
        if (updated.isEmpty()) clearPendingInternalStart()
    }

    private fun processNextInQueue() {
        val queue = _ui.value.queue
        if (queue.isEmpty()) {
            saveQueue(emptyList())
            clearPendingInternalStart()
            return
        }
        val next = queue.first()
        // A worker enqueued in the background cannot start its foreground service: wait for the app
        if (next.fileUri != null && backend() is InternalBackend && !appInForeground) {
            pendingInternalStart = true
            prefs.edit().putBoolean(PENDING_INTERNAL_START, true).apply()
            val text = str(R.string.internal_waiting_foreground)
            _ui.value = _ui.value.copy(state = UiState.Idle, statusText = text)
            notif.showError(text)
            return
        }
        if (pendingInternalStart) clearPendingInternalStart()
        val remaining = queue.drop(1)
        _ui.value = _ui.value.copy(
            queue = remaining,
            url = next.url,
            selectedModel = next.model,
            audioOnly = next.audioOnly,
            bitrate = next.bitrate,
            selectedFileUri = next.fileUri,
            selectedFileName = next.fileName,
            selectedFileSize = next.fileSize,
        )
        saveQueue(remaining)
        if (next.fileUri != null) {
            processFile(next.fileUri)
        } else {
            processUrl(next.url)
        }
    }

    /** Retry the last failed operation */
    fun retry() {
        _ui.value = _ui.value.copy(state = UiState.Idle, errorMessage = "")
        process()
    }

    /** Cancel current item — processes next in queue if any */
    fun cancelProcessing() {
        val jobId = _ui.value.jobId
        pollingJob?.cancel()
        pollingJob = null
        activeJobMeta = null
        prefs.edit().remove("active_job_id").apply()
        notif.dismiss()
        val backend = jobId?.let { backends.forJob(it) }
        if (jobId != null && backend != null && backend.managesNotifications) {
            cancelInternalThenContinue(backend, jobId)
            return
        }
        ProcessingService.stop(getApplication())
        if (jobId != null && backend != null) {
            bgScope.launch { try { backend.cancel(jobId) } catch (_: Exception) {} }
        }
        if (_ui.value.queue.isNotEmpty()) {
            _ui.value = _ui.value.copy(progress = 0, statusText = "")
            processNextInQueue()
        } else {
            setIdle()
        }
    }

    /** Cancel current item and clear entire queue */
    fun cancelAll() {
        val jobId = _ui.value.jobId
        pollingJob?.cancel()
        pollingJob = null
        activeJobMeta = null
        prefs.edit().remove("active_job_id").remove("queue").apply()
        clearPendingInternalStart()
        notif.dismiss()
        val backend = jobId?.let { backends.forJob(it) }
        if (jobId != null && backend != null && backend.managesNotifications) {
            _ui.value = _ui.value.copy(queue = emptyList())
            cancelInternalThenContinue(backend, jobId)
            return
        }
        ProcessingService.stop(getApplication())
        if (jobId != null && backend != null) {
            bgScope.launch { try { backend.cancel(jobId) } catch (_: Exception) {} }
        }
        _ui.value = _ui.value.copy(state = UiState.Idle, progress = 0, statusText = "", processingMinimized = false, queue = emptyList())
    }

    /**
     * On-device cancel: wait until the work is CANCELLED before the next enqueue, so APPEND_OR_REPLACE
     * never chains the next job behind a work that is about to be cancelled (§3.5).
     */
    private fun cancelInternalThenContinue(backend: ProcessingBackend, jobId: String) {
        _ui.value = _ui.value.copy(progress = 0, statusText = "")
        bgScope.launch {
            backend.cancel(jobId)
            withContext(Dispatchers.Main) {
                if (_ui.value.queue.isNotEmpty()) processNextInQueue() else setIdle()
            }
        }
    }

    private fun setIdle() {
        prefs.edit().remove("queue").apply()
        clearPendingInternalStart()
        _ui.value = _ui.value.copy(state = UiState.Idle, progress = 0, statusText = "", processingMinimized = false, queue = emptyList())
    }

    fun minimizeProcessing() {
        _ui.value = _ui.value.copy(processingMinimized = true)
    }

    fun expandProcessing() {
        _ui.value = _ui.value.copy(processingMinimized = false)
    }

    private fun str(id: Int): String = getApplication<Application>().getString(id)

    private var snackJob: kotlinx.coroutines.Job? = null
    private var shareJob: kotlinx.coroutines.Job? = null

    fun showTransientError(msg: String) {
        snackJob?.cancel()
        _ui.value = _ui.value.copy(snackError = msg)
        // Also show a Toast so it's visible even when scrolled
        try {
            val app = getApplication<Application>()
            kotlinx.coroutines.MainScope().launch {
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
        snackJob = bgScope.launch {
            delay(4000)
            _ui.value = _ui.value.copy(snackError = null)
        }
    }

    fun dismissSnackError() {
        snackJob?.cancel()
        _ui.value = _ui.value.copy(snackError = null)
    }

    /** Metadata captured at job start — used when saving to history */
    private data class JobMeta(
        val url: String,
        val model: String,
        val isFileUpload: Boolean,
        val sourceFileName: String?,
        val sourceFileSize: Long?,
        val sourceFilePath: String? = null,
        val ytTitle: String? = null,
        val ytChannel: String? = null,
        val ytThumbnail: String? = null,
        val ytDuration: Int = 0,
        val ytViewCount: Long = 0,
        val ytUploadDate: String? = null,
        val ytDescription: String? = null,
    )
    private var activeJobMeta: JobMeta? = null

    private fun processFile(uri: android.net.Uri) {
        activeJobMeta = JobMeta(
            url = "", model = _ui.value.selectedModel,
            isFileUpload = true,
            sourceFileName = _ui.value.selectedFileName,
            sourceFileSize = _ui.value.selectedFileSize,
            sourceFilePath = _ui.value.originalFileUri,
        )
        pollingJob = bgScope.launch {
            val backend = backend()
            val managed = backend.managesNotifications
            // On-device jobs do not upload anything
            val text = if (managed) str(R.string.starting) else str(R.string.uploading)
            _ui.value = _ui.value.copy(state = UiState.Processing, progress = 0, statusText = text)
            if (!managed) ProcessingService.start(getApplication(), text, 0)
            try {
                val fileName = _ui.value.selectedFileName ?: "video.mp4"
                val jobId = backend.startFile(uri, fileName, options(backend))
                _ui.value = _ui.value.copy(jobId = jobId)
                prefs.edit().putString("active_job_id", jobId).apply()
                runJob(jobId)
            } catch (e: Exception) {
                if (!managed) ProcessingService.stop(getApplication())
                showTransientError(e.message ?: "Upload failed")
                if (_ui.value.queue.isNotEmpty()) {
                    processNextInQueue()
                } else {
                    _ui.value = _ui.value.copy(state = UiState.Idle)
                }
            }
        }
    }

    private fun processUrl(url: String) {
        activeJobMeta = JobMeta(
            url = url, model = _ui.value.selectedModel,
            isFileUpload = false,
            sourceFileName = null, sourceFileSize = null,
        )
        pollingJob = bgScope.launch {
            val backend = backend()
            val managed = backend.managesNotifications
            _ui.value = _ui.value.copy(state = UiState.Processing, progress = 0, statusText = str(R.string.starting))
            if (!managed) ProcessingService.start(getApplication(), str(R.string.starting), 0)
            try {
                val jobId = backend.startUrl(url, options(backend))
                _ui.value = _ui.value.copy(jobId = jobId)
                prefs.edit().putString("active_job_id", jobId).apply()
                runJob(jobId)
            } catch (e: Exception) {
                if (!managed) ProcessingService.stop(getApplication())
                showTransientError(e.message ?: "Connection failed")
                if (_ui.value.queue.isNotEmpty()) {
                    processNextInQueue()
                } else {
                    _ui.value = _ui.value.copy(state = UiState.Idle)
                }
            }
        }
    }

    private fun options(backend: ProcessingBackend): ProcessOptions {
        // The on-device backend only has its own models (§3.6)
        val supported = backend.supportedModels()
        val model = _ui.value.selectedModel.let {
            if (backend is InternalBackend && it !in supported) supported.first() else it
        }
        return ProcessOptions(
            model, _ui.value.audioOnly, _ui.value.bitrate,
            sourceFileSize = _ui.value.selectedFileSize,
            sourceFilePath = _ui.value.originalFileUri,
        )
    }

    private fun statusText(status: String, error: String?): String =
        statusLabel(getApplication(), status, error)

    private suspend fun runJob(jobId: String) {
        val backend = backends.forJob(jobId)
        // The on-device worker posts its own notifications and runs its own foreground service
        val managed = backend.managesNotifications
        if (!managed) notif.showProgress(str(R.string.starting), 0)
        backend.events(jobId).collect { e ->
            when (e) {
                is JobEvent.Progress -> {
                    val text = statusText(e.status, e.error)
                    _ui.value = _ui.value.copy(progress = e.progress, statusText = text)
                    if (!managed) ProcessingService.start(getApplication(), text, e.progress)
                    if (e.status == "done") {
                        val filename = e.filename ?: "Done"
                        // Show the result as soon as the job finishes, while it is stored locally
                        _ui.value = _ui.value.copy(state = UiState.Done, statusText = filename)
                        // Write history before the result download starts, so a cancel cannot lose it
                        val meta = activeJobMeta
                        // Parse metadata from server (yt-dlp data)
                        val ytMeta = e.metadata
                        historyStore.add(HistoryItem(
                            filename = filename,
                            url = meta?.url ?: "",
                            model = meta?.model ?: _ui.value.selectedModel,
                            jobId = jobId,
                            isFileUpload = meta?.isFileUpload ?: false,
                            sourceFileName = meta?.sourceFileName,
                            sourceFileSize = meta?.sourceFileSize,
                            sourceFilePath = meta?.sourceFilePath,
                            ytTitle = ytMeta?.title ?: meta?.ytTitle,
                            ytChannel = ytMeta?.channel ?: meta?.ytChannel,
                            ytThumbnail = ytMeta?.thumbnail ?: meta?.ytThumbnail,
                            ytDuration = ytMeta?.duration ?: meta?.ytDuration ?: 0,
                            ytViewCount = ytMeta?.view_count ?: meta?.ytViewCount ?: 0,
                            ytUploadDate = ytMeta?.upload_date ?: meta?.ytUploadDate,
                            ytDescription = ytMeta?.description ?: meta?.ytDescription,
                        ))
                        _ui.value = _ui.value.copy(history = historyStore.getAll())
                    }
                }
                is JobEvent.Done -> {
                    val filename = e.filename
                    if (_ui.value.jobId == jobId) {
                        _ui.value = _ui.value.copy(state = UiState.Done, statusText = filename)
                    }
                    prefs.edit().remove("active_job_id").putString("last_done_job_id", jobId).apply()
                    if (!managed) {
                        ProcessingService.stop(getApplication())
                        notif.showDone(filename, jobId, resultPlayUrl(jobId))
                    }
                    hapticTick()
                    activeJobMeta = null
                    // Process next in queue if any
                    if (_ui.value.queue.isNotEmpty()) {
                        processNextInQueue()
                    }
                }
                is JobEvent.Failed -> {
                    val text = statusText(e.status, e.error)
                    prefs.edit().remove("active_job_id").apply()
                    if (!managed) {
                        ProcessingService.stop(getApplication())
                        notif.showError(text)
                    }
                    showTransientError(text)
                    if (_ui.value.queue.isNotEmpty()) {
                        processNextInQueue()
                    } else {
                        _ui.value = _ui.value.copy(state = UiState.Idle)
                    }
                }
                JobEvent.ConnectionLost -> {
                    prefs.edit().remove("active_job_id").apply()
                    if (!managed) {
                        ProcessingService.stop(getApplication())
                        notif.showError(str(R.string.lost_connection))
                    }
                    showTransientError(str(R.string.lost_connection))
                    if (_ui.value.queue.isNotEmpty()) {
                        processNextInQueue()
                    } else {
                        _ui.value = _ui.value.copy(state = UiState.Idle)
                    }
                }
            }
        }
    }

    // --- Results ---
    /** Local cached result as a content:// URI, else the remote download URL */
    private fun resultPlayUrl(jobId: String): String? =
        cache.cachedUri(jobId)?.toString() ?: backends.forJob(jobId).remoteResultUrl(jobId)

    /** Play a history item — local cached file first, else the server if it still has the result */
    fun playHistoryItem(item: HistoryItem, onPlay: (String, String, String?) -> Unit) {
        val title = displayTitle(item.filename)
        val cachedUri = cache.cachedUri(item.jobId)
        if (cachedUri != null) {
            onPlay(cachedUri.toString(), title, item.jobId)
            return
        }
        // Offline: play an unverified local file right away instead of waiting for the server
        if (!_ui.value.serverConnected) {
            val unverified = cache.getUnverifiedFile(item.jobId)
            if (unverified != null) {
                onPlay(cache.contentUri(unverified).toString(), title, item.jobId)
                return
            }
        }
        _ui.value = _ui.value.copy(checkingPlay = true)
        bgScope.launch {
            try {
                val backend = backends.forJob(item.jobId)
                val available = backend.isResultAvailable(item.jobId)
                _ui.value = _ui.value.copy(checkingPlay = false)
                val url = backend.remoteResultUrl(item.jobId)
                if (available && url != null) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onPlay(url, title, item.jobId)
                    }
                } else if (!playUnverified(item, title, onPlay)) {
                    showTransientError("File not ready or no longer available")
                }
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(checkingPlay = false)
                if (!playUnverified(item, title, onPlay)) {
                    showTransientError("Server not reachable")
                }
            }
        }
    }

    /** Last resort: play an unverified local file. Returns false if there is none. */
    private suspend fun playUnverified(item: HistoryItem, title: String, onPlay: (String, String, String?) -> Unit): Boolean {
        val unverified = cache.getUnverifiedFile(item.jobId) ?: return false
        val uri = cache.contentUri(unverified).toString()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onPlay(uri, title, item.jobId)
        }
        return true
    }

    /** Server pre-computed waveform for the player, or null */
    suspend fun serverWaveform(jobId: String): List<Float>? {
        val backend = backends.forJob(jobId)
        // On-device results are local files; the player decodes them itself
        return if (!_ui.value.serverConnected || backend is InternalBackend) null else backend.waveform(jobId)
    }

    fun getStreamUrl(): String? {
        val jobId = _ui.value.jobId ?: return null
        return resultPlayUrl(jobId)
    }

    fun getFilename(): String = _ui.value.statusText.ifEmpty { "vocals-only.mp4" }

    fun shareResult(context: Context) {
        val jobId = _ui.value.jobId ?: return
        shareByJobId(context, jobId, getFilename())
    }

    fun shareByJobId(context: Context, jobId: String, filename: String) {
        shareJob?.cancel()
        _ui.value = _ui.value.copy(sharing = true)
        shareJob = bgScope.launch {
            try {
                kotlinx.coroutines.withTimeout(60_000) {
                    val cacheDir = java.io.File(context.cacheDir, "shared")
                    cacheDir.mkdirs()
                    val file = java.io.File(cacheDir, filename)

                    // Offline: use an unverified local file right away instead of waiting for the server
                    val cached = cache.getCachedFile(jobId)
                        ?: if (!_ui.value.serverConnected) cache.getUnverifiedFile(jobId) else null
                    if (cached != null) {
                        // Copy so the shared file keeps its original name (cache names carry a jobId prefix)
                        cached.copyTo(file, overwrite = true)
                    } else {
                        try {
                            val url = java.net.URL(backends.forJob(jobId).remoteResultUrl(jobId) ?: throw Exception("File no longer available on server"))
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.connect()
                            if (conn.responseCode == 404) {
                                conn.disconnect()
                                throw Exception("File no longer available on server")
                            }
                            conn.inputStream.use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                            conn.disconnect()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Last resort: an unverified local file
                            val unverified = cache.getUnverifiedFile(jobId) ?: throw e
                            unverified.copyTo(file, overwrite = true)
                        }
                    }

                    val uri = cache.contentUri(file)
                    val mime = mimeFor(filename)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = android.content.Intent.createChooser(intent, "Share")
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                    dismissInfoSheet()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                showTransientError("Share timed out")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (!msg.contains("cancel", ignoreCase = true) && !msg.contains("routine", ignoreCase = true)) {
                    showTransientError(msg.ifEmpty { "Share failed" })
                }
            }
            _ui.value = _ui.value.copy(sharing = false)
        }
    }

    fun shareByJobUrl(context: Context, downloadUrl: String, filename: String) {
        _ui.value = _ui.value.copy(sharing = true)
        bgScope.launch {
            try {
                val cacheDir = java.io.File(context.cacheDir, "shared")
                cacheDir.mkdirs()
                val file = java.io.File(cacheDir, filename)

                val url = java.net.URL(downloadUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connect()
                if (conn.responseCode == 404) {
                    conn.disconnect()
                    _ui.value = _ui.value.copy(sharing = false)
                    showTransientError("File no longer available on server")
                    return@launch
                }
                conn.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val mime = mimeFor(filename)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = android.content.Intent.createChooser(intent, "Share")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                showTransientError("Share failed: ${e.message}")
            }
            _ui.value = _ui.value.copy(sharing = false)
        }
    }

    fun saveToUri(context: Context, uri: android.net.Uri) {
        val jobId = _ui.value.jobId ?: return
        _ui.value = _ui.value.copy(downloading = true)
        bgScope.launch {
            try {
                val cached = cache.getCachedFile(jobId)
                if (cached != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        cached.inputStream().use { it.copyTo(out) }
                    }
                } else {
                    val url = java.net.URL(backends.forJob(jobId).remoteResultUrl(jobId) ?: throw Exception("File not available"))
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connect()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        conn.inputStream.use { it.copyTo(out) }
                    }
                    conn.disconnect()
                }
                _ui.value = _ui.value.copy(downloading = false)
            } catch (e: Exception) {
                // Last resort: an unverified local file ("wt" truncates any partial write)
                val saved = cache.getUnverifiedFile(jobId)?.let { unverified ->
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            unverified.inputStream().use { it.copyTo(out) }
                        } != null
                    } catch (_: Exception) { false }
                } ?: false
                if (saved) {
                    _ui.value = _ui.value.copy(downloading = false)
                } else {
                    _ui.value = _ui.value.copy(downloading = false, state = UiState.Error, errorMessage = "Save failed: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        /** Pref: a queued on-device item is waiting for the app to come to the foreground */
        const val PENDING_INTERNAL_START = "pending_internal_start"
    }
}
