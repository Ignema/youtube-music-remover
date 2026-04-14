package com.musicremover.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import com.musicremover.app.data.ApiClient
import com.musicremover.app.R
import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.HistoryStore
import com.musicremover.app.data.NotificationHelper
import com.musicremover.app.data.ProcessRequest
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.SettingsStore
import com.musicremover.app.data.TermuxHelper
import com.musicremover.app.data.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull

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
            model = m["model"] as? String ?: "UVR-MDX-NET-Inst_HQ_3.onnx",
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
    val url: String = "",
    val selectedModel: String = "UVR-MDX-NET-Inst_HQ_3.onnx",
    val models: List<String> = listOf(
        "UVR-MDX-NET-Inst_HQ_3.onnx",
        "Kim_Vocal_2.onnx",
        "UVR_MDXNET_KARA_2.onnx",
    ),
    val progress: Int = 0,
    val statusText: String = "",
    val errorMessage: String = "",
    val jobId: String? = null,
    val showModelPicker: Boolean = false,
    val downloading: Boolean = false,
    val history: List<HistoryItem> = emptyList(),
    val serverUrl: String = "",
    val termuxInstalled: Boolean = false,
    val selectedFileUri: android.net.Uri? = null,
    // Queue
    val queue: List<QueueItem> = emptyList(),
    val processingMinimized: Boolean = false,
    val selectedFileName: String? = null,
    val selectedFileSize: Long? = null,
    val videoInfo: VideoInfo? = null,
    val showInfoSheet: Boolean = false,
    val loadingInfo: Boolean = false,
    val videoInfoError: Boolean = false,
    val fileInfoItem: HistoryItem? = null,
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
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    private val historyStore = HistoryStore(application)
    private val settingsStore = SettingsStore(application)
    private val notif = NotificationHelper(application)
    private val cache = ResultCache(application)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = application.getSharedPreferences("app", Context.MODE_PRIVATE)

    private val serverUrl: String get() = _ui.value.serverUrl
    private fun api() = ApiClient.getService(serverUrl)
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
        )
        // Resume polling if app was killed mid-processing
        val savedJobId = prefs.getString("active_job_id", null)
        val savedQueue = loadQueue()
        if (savedJobId != null) {
            _ui.value = _ui.value.copy(
                state = UiState.Processing, jobId = savedJobId,
                statusText = application.getString(R.string.resuming),
                queue = savedQueue,
            )
            bgScope.launch { pollStatus(savedJobId) }
        } else if (savedQueue.isNotEmpty()) {
            // No active job but queue was saved — clear stale queue
            prefs.edit().remove("queue").apply()
        }

        // Periodic server connectivity check
        bgScope.launch {
            while (true) {
                val connected = try {
                    val conn = java.net.URL("${_ui.value.serverUrl}/health").openConnection()
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.getInputStream().bufferedReader().readText().contains("ok")
                } catch (_: Exception) { false }
                _ui.value = _ui.value.copy(serverConnected = connected, serverChecking = false)
                kotlinx.coroutines.delay(10000)
                _ui.value = _ui.value.copy(serverChecking = true)
            }
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
    fun onUrlChange(url: String) {
        _ui.value = _ui.value.copy(url = url, urlPreview = null, loadingPreview = false)
        previewJob?.cancel()
        // Auto-fetch preview for YouTube URLs
        if (url.length > 10 && (url.contains("youtu") || url.contains("youtube"))) {
            fetchUrlPreview(url)
        } else if (url.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            fetchUrlPreview(url)
        }
    }

    fun onModelSelect(model: String) {
        _ui.value = _ui.value.copy(selectedModel = model, showModelPicker = false)
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

    fun reset() {
        _ui.value = MainUiState(
            history = _ui.value.history,
            serverUrl = _ui.value.serverUrl,
            termuxInstalled = _ui.value.termuxInstalled,
        )
    }

    fun onFileSelected(uri: android.net.Uri?, name: String?, size: Long? = null) {
        _ui.value = _ui.value.copy(selectedFileUri = uri, selectedFileName = name, selectedFileSize = size)
    }

    fun clearFile() {
        _ui.value = _ui.value.copy(selectedFileUri = null, selectedFileName = null, selectedFileSize = null)
    }

    // --- URL preview ---
    private var previewJob: kotlinx.coroutines.Job? = null

    private fun fetchUrlPreview(url: String) {
        previewJob?.cancel()
        previewJob = bgScope.launch {
            delay(500) // Debounce
            _ui.value = _ui.value.copy(loadingPreview = true)
            try {
                val info = fetchYouTubeInfoDirect(url)
                if (info != null && _ui.value.url.isNotEmpty()) {
                    _ui.value = _ui.value.copy(urlPreview = info, loadingPreview = false)
                } else {
                    _ui.value = _ui.value.copy(loadingPreview = false)
                }
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(loadingPreview = false)
            }
        }
    }

    /**
     * Fetch YouTube video info directly via oEmbed + thumbnail URL.
     * No server dependency — works even when Termux is busy.
     */
    private fun fetchYouTubeInfoDirect(url: String): VideoInfo? {
        val videoId = extractVideoId(url) ?: return null
        val ytUrl = "https://www.youtube.com/watch?v=$videoId"
        val oembedUrl = "https://www.youtube.com/oembed?url=$ytUrl&format=json"

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder().url(oembedUrl).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val data = com.google.gson.JsonParser.parseString(body).asJsonObject

        return VideoInfo(
            title = data.get("title")?.asString ?: "",
            channel = data.get("author_name")?.asString ?: "",
            thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
        )
    }

    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed
        val patterns = listOf(
            Regex("(?:https?://)?(?:www\\.)?youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Regex("(?:https?://)?(?:www\\.)?youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})"),
            Regex("(?:https?://)?(?:www\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
        )
        for (p in patterns) {
            val match = p.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
        return null
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

    fun tryPlayHistoryItem(item: HistoryItem, context: Context) {
        bgScope.launch {
            // Try local cache first
            val cachedFile = cache.getCachedFile(item.jobId)
            if (cachedFile != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", cachedFile
                )
                val mime = if (cachedFile.name.endsWith(".mp3")) "audio/mpeg" else "video/mp4"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return@launch
            }
            // Fall back to server, cache for next time
            try {
                val status = api().status(item.jobId)
                if (status.status == "done") {
                    val file = cache.cacheResult(serverUrl, item.jobId, item.filename)
                    if (file != null) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        val mime = if (file.name.endsWith(".mp3")) "audio/mpeg" else "video/mp4"
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                } else {
                    _ui.value = _ui.value.copy(url = item.url)
                }
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(url = item.url)
            }
        }
    }

    fun fetchVideoInfo(url: String, item: HistoryItem? = null) {
        if (url.isEmpty()) return
        _ui.value = _ui.value.copy(
            loadingInfo = true, showInfoSheet = true, videoInfo = null, videoInfoError = false,
            fileInfoItem = if (item?.isFileUpload == true) item else null,
        )
        currentInfoItem = item
        bgScope.launch {
            try {
                // Try direct oEmbed first (fast, no server needed)
                val directInfo = fetchYouTubeInfoDirect(url)
                if (directInfo != null) {
                    _ui.value = _ui.value.copy(videoInfo = directInfo, loadingInfo = false)
                    // Try server API in background for richer data (duration, views, etc.)
                    try {
                        val richInfo = kotlinx.coroutines.withTimeout(8_000) { api().info(url) }
                        _ui.value = _ui.value.copy(videoInfo = richInfo)
                    } catch (_: Exception) { /* keep oEmbed data */ }
                } else {
                    // Fallback to server API
                    val info = kotlinx.coroutines.withTimeout(10_000) { api().info(url) }
                    _ui.value = _ui.value.copy(videoInfo = info, loadingInfo = false)
                }
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(loadingInfo = false, videoInfoError = true)
            }
        }
    }

    // Track which history item is being viewed (for delete)
    var currentInfoItem: HistoryItem? = null
        private set

    fun dismissInfoSheet() {
        _ui.value = _ui.value.copy(showInfoSheet = false, videoInfo = null, videoInfoError = false, fileInfoItem = null)
        currentInfoItem = null
    }

    fun showFileInfo(item: HistoryItem) {
        currentInfoItem = item
        _ui.value = _ui.value.copy(showInfoSheet = true, fileInfoItem = item, videoInfoError = false, loadingInfo = false, videoInfo = null)
    }

    /** Show video info sheet for YouTube history items — fetches oEmbed data */
    fun showVideoInfoSheet(item: HistoryItem) {
        currentInfoItem = item
        _ui.value = _ui.value.copy(
            showInfoSheet = true, fileInfoItem = null,
            loadingInfo = true, videoInfo = null, videoInfoError = false,
        )
        bgScope.launch {
            try {
                val directInfo = fetchYouTubeInfoDirect(item.url)
                if (directInfo != null) {
                    _ui.value = _ui.value.copy(videoInfo = directInfo, loadingInfo = false)
                } else {
                    _ui.value = _ui.value.copy(loadingInfo = false, videoInfoError = true)
                }
            } catch (_: Exception) {
                _ui.value = _ui.value.copy(loadingInfo = false, videoInfoError = true)
            }
        }
    }

    // --- Server/Termux ---
    fun onServerUrlChange(url: String) {
        _ui.value = _ui.value.copy(serverUrl = url)
        settingsStore.serverUrl = url
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
            onServerUrlChange("http://127.0.0.1:8000")
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

    private fun pollServerOnline() {
        bgScope.launch {
            repeat(60) { // Try for ~2 minutes
                delay(2000)
                try {
                    val response = java.net.URL("${serverUrl}/api/models").readText()
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
        val fileUri = _ui.value.selectedFileUri
        val url = _ui.value.url.trim()

        if (fileUri == null && url.isEmpty()) {
            // Don't clobber processing state — just show a transient error
            showTransientError(str(R.string.enter_url_or_file))
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
    }

    private fun processNextInQueue() {
        val queue = _ui.value.queue
        if (queue.isEmpty()) {
            saveQueue(emptyList())
            return
        }
        val next = queue.first()
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

    /** Cancel the current processing */
    fun cancelProcessing() {
        pollingJob?.cancel()
        pollingJob = null
        prefs.edit().remove("active_job_id").remove("queue").apply()
        ProcessingService.stop(getApplication())
        notif.dismiss()
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

    fun showTransientError(msg: String) {
        snackJob?.cancel()
        _ui.value = _ui.value.copy(snackError = msg)
        snackJob = bgScope.launch {
            delay(4000)
            _ui.value = _ui.value.copy(snackError = null)
        }
    }

    fun dismissSnackError() {
        snackJob?.cancel()
        _ui.value = _ui.value.copy(snackError = null)
    }

    private var pollingJob: kotlinx.coroutines.Job? = null

    private fun processFile(uri: android.net.Uri) {
        pollingJob = bgScope.launch {
            _ui.value = _ui.value.copy(state = UiState.Processing, progress = 0, statusText = str(R.string.uploading))
            ProcessingService.start(getApplication(), str(R.string.uploading), 0)
            try {
                val context = getApplication<Application>()
                val fileName = _ui.value.selectedFileName ?: "video.mp4"
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Cannot read file")

                val filePart = okhttp3.MultipartBody.Part.createFormData(
                    "file", fileName,
                    okhttp3.RequestBody.create("video/*".toMediaTypeOrNull(), bytes),
                )
                val model = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), _ui.value.selectedModel)
                val batch = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), "4")
                val audioOnly = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), _ui.value.audioOnly.toString())
                val bitrate = okhttp3.RequestBody.create("text/plain".toMediaTypeOrNull(), _ui.value.bitrate)

                val resp = api().upload(filePart, model, batch, audioOnly, bitrate)
                _ui.value = _ui.value.copy(jobId = resp.job_id)
                prefs.edit().putString("active_job_id", resp.job_id).apply()
                // Clean up cached upload file
                if (uri.scheme == "file") {
                    try { java.io.File(uri.path!!).delete() } catch (_: Exception) {}
                }
                pollStatus(resp.job_id)
            } catch (e: Exception) {
                ProcessingService.stop(getApplication())
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
        pollingJob = bgScope.launch {
            _ui.value = _ui.value.copy(state = UiState.Processing, progress = 0, statusText = str(R.string.starting))
            ProcessingService.start(getApplication(), str(R.string.starting), 0)
            try {
                val resp = api().process(
                    ProcessRequest(
                        url = url,
                        model = _ui.value.selectedModel,
                        audio_only = _ui.value.audioOnly,
                        bitrate = _ui.value.bitrate,
                    )
                )
                _ui.value = _ui.value.copy(jobId = resp.job_id)
                prefs.edit().putString("active_job_id", resp.job_id).apply()
                pollStatus(resp.job_id)
            } catch (e: Exception) {
                ProcessingService.stop(getApplication())
                showTransientError(e.message ?: "Connection failed")
                if (_ui.value.queue.isNotEmpty()) {
                    processNextInQueue()
                } else {
                    _ui.value = _ui.value.copy(state = UiState.Idle)
                }
            }
        }
    }

    private suspend fun pollStatus(jobId: String) {
        notif.showProgress(str(R.string.starting), 0)
        var retries = 0
        while (true) {
            delay(2500)
            try {
                val status = api().status(jobId)
                retries = 0
                val text = when (status.status) {
                    "queued" -> str(R.string.queued)
                    "uploading" -> str(R.string.uploading)
                    "extracting" -> str(R.string.extracting)
                    "downloading" -> str(R.string.downloading)
                    "separating" -> str(R.string.separating)
                    "merging" -> str(R.string.merging)
                    "done" -> str(R.string.vocals_extracted)
                    "error" -> status.error ?: "Error"
                    else -> status.status
                }
                _ui.value = _ui.value.copy(progress = status.progress, statusText = text)
                ProcessingService.start(getApplication(), text, status.progress)

                when (status.status) {
                    "done" -> {
                        val filename = status.filename ?: "Done"
                        _ui.value = _ui.value.copy(state = UiState.Done, statusText = filename)
                        // Cache result on-device
                        cache.cacheResult(serverUrl, jobId, filename)
                        historyStore.add(HistoryItem(
                            filename = filename, url = _ui.value.url,
                            model = _ui.value.selectedModel, jobId = jobId,
                            isFileUpload = _ui.value.selectedFileUri != null,
                            sourceFileName = _ui.value.selectedFileName,
                            sourceFileSize = _ui.value.selectedFileSize,
                        ))
                        _ui.value = _ui.value.copy(history = historyStore.getAll())
                        prefs.edit().remove("active_job_id").apply()
                        ProcessingService.stop(getApplication())
                        notif.showDone(filename)
                        hapticTick()
                        // Process next in queue if any
                        if (_ui.value.queue.isNotEmpty()) {
                            processNextInQueue()
                        }
                        return
                    }
                    "error" -> {
                        prefs.edit().remove("active_job_id").apply()
                        ProcessingService.stop(getApplication())
                        notif.showError(text)
                        showTransientError(text)
                        if (_ui.value.queue.isNotEmpty()) {
                            processNextInQueue()
                        } else {
                            _ui.value = _ui.value.copy(state = UiState.Idle)
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                retries++
                if (retries >= 30) {
                    prefs.edit().remove("active_job_id").apply()
                    ProcessingService.stop(getApplication())
                    notif.showError(str(R.string.lost_connection))
                    showTransientError(str(R.string.lost_connection))
                    if (_ui.value.queue.isNotEmpty()) {
                        processNextInQueue()
                    } else {
                        _ui.value = _ui.value.copy(state = UiState.Idle)
                    }
                    return
                }
                delay(retries * 1000L)
            }
        }
    }

    // --- Results ---
    fun getStreamUrl(): String? {
        val jobId = _ui.value.jobId ?: return null
        return "${serverUrl}/api/download/$jobId"
    }

    fun getFilename(): String = _ui.value.statusText.ifEmpty { "vocals-only.mp4" }

    fun shareResult(context: Context) {
        val jobId = _ui.value.jobId ?: return
        shareByJobId(context, jobId, getFilename())
    }

    fun shareByJobId(context: Context, jobId: String, filename: String) {
        bgScope.launch {
            try {
                val cacheDir = java.io.File(context.cacheDir, "shared")
                cacheDir.mkdirs()
                val file = java.io.File(cacheDir, filename)

                val url = java.net.URL("${serverUrl}/api/download/$jobId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connect()
                conn.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val mime = if (filename.endsWith(".mp3")) "audio/mpeg" else "video/mp4"
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = android.content.Intent.createChooser(intent, "Share")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(state = UiState.Error, errorMessage = "Share failed: ${e.message}")
            }
        }
    }

    fun saveToUri(context: Context, uri: android.net.Uri) {
        val jobId = _ui.value.jobId ?: return
        _ui.value = _ui.value.copy(downloading = true)
        bgScope.launch {
            try {
                val url = java.net.URL("${serverUrl}/api/download/$jobId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connect()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                }
                conn.disconnect()
                _ui.value = _ui.value.copy(downloading = false)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(downloading = false, state = UiState.Error, errorMessage = "Save failed: ${e.message}")
            }
        }
    }
}
