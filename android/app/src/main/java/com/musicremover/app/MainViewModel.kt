package com.musicremover.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musicremover.app.R
import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.HistoryStore
import com.musicremover.app.data.NotificationHelper
import com.musicremover.app.data.PersistedUi
import com.musicremover.app.data.ResultCache
import com.musicremover.app.data.SettingsStore
import com.musicremover.app.ui.theme.ColorPalettes
import com.musicremover.app.data.TermuxHelper
import com.musicremover.app.data.VideoInfo
import com.musicremover.app.data.displayTitle
import com.musicremover.app.processing.ActiveJobTracker
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.InternalBackend
import com.musicremover.app.processing.InternalJobEvents
import com.musicremover.app.processing.JobEvent
import com.musicremover.app.processing.ModelSelection
import com.musicremover.app.processing.ProcessOptions
import com.musicremover.app.processing.ProcessingBackend
import com.musicremover.app.processing.RemoteServerBackend
import com.musicremover.app.processing.internal.CookieStore
import com.musicremover.app.processing.internal.CookiesTxt
import com.musicremover.app.processing.internal.LoginSites
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelFailure
import com.musicremover.app.processing.internal.ModelManager
import com.musicremover.app.processing.internal.ModelSpec
import com.musicremover.app.processing.internal.ModelState
import com.musicremover.app.processing.internal.Mp3Support
import com.musicremover.app.processing.internal.PerformanceSettings
import com.musicremover.app.processing.internal.YtDlpBanner
import com.musicremover.app.processing.internal.YtDlpRuntime
import com.musicremover.app.processing.InternalProcessingWorker
import com.musicremover.app.processing.statusWithEta
import com.musicremover.app.ui.settings.SnackPolicy
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

/** Settings "Update yt-dlp" state. */
sealed interface YtDlpUpdateState {
    data object Idle : YtDlpUpdateState
    data object Running : YtDlpUpdateState
    data class Done(val updated: Boolean, val version: String) : YtDlpUpdateState
    /** [busy]: a link download held the yt-dlp lock. */
    data class Failed(val busy: Boolean) : YtDlpUpdateState
}

data class QueueItem(
    val url: String,
    val model: String,
    val audioOnly: Boolean,
    val bitrate: String,
    val fileUri: android.net.Uri? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    /** Audio-only result format captured when the item was queued: "m4a" or "mp3". */
    val outputFormat: String = "m4a",
) {
    /** Serialize for SharedPreferences (file URIs stored as strings) */
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "url" to url, "model" to model, "audioOnly" to audioOnly, "bitrate" to bitrate,
        "fileUri" to fileUri?.toString(), "fileName" to fileName, "fileSize" to fileSize,
        "outputFormat" to outputFormat,
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
            outputFormat = m["outputFormat"] as? String ?: "m4a",
        )
    }
}

/**
 * Options for a queued item, from the item's own values (never from the UI state). In internal mode an
 * unsupported model, or a model whose license notice is not accepted, becomes [internalDefault]
 * (HQ3 if that is not supported either).
 */
internal fun QueueItem.toOptions(
    supported: List<String>,
    internal: Boolean,
    internalDefault: String,
    noticeOk: (String) -> Boolean,
    sourceFileSize: Long?,
    sourceFilePath: String?,
): ProcessOptions {
    val chosen = if (internal && (model !in supported || !noticeOk(model))) {
        internalDefault.takeIf { it in supported } ?: ModelCatalog.HQ3
    } else {
        model
    }
    return ProcessOptions(
        chosen, audioOnly, bitrate,
        sourceFileSize = sourceFileSize,
        sourceFilePath = sourceFilePath,
        outputFormat = outputFormat,
    )
}

/** What opened the license-notice dialog. */
enum class NoticeTrigger { Process, Download }

/** The license-notice dialog for [model], opened by [trigger]. */
data class NoticeRequest(val model: String, val trigger: NoticeTrigger)

/** What the ViewModel does after the notice dialog closes. */
sealed interface NoticeAction {
    data object RunProcess : NoticeAction
    data class RunDownload(val name: String) : NoticeAction
    data object None : NoticeAction
}

/** Pure: accepting re-runs the triggering action; cancelling does nothing (the selection stays). */
fun onNoticeResult(req: NoticeRequest, accepted: Boolean): NoticeAction = when {
    !accepted -> NoticeAction.None
    req.trigger == NoticeTrigger.Process -> NoticeAction.RunProcess
    else -> NoticeAction.RunDownload(req.model)
}

/** Performance prefs as shown in Settings (0 threads = Auto). */
data class PerformancePrefs(val threads: Int = 0, val quality: String = "balanced", val batch: Int = 1)

/**
 * "Process another": a fresh state that keeps the live fields of [prev] and the persisted Output and
 * Appearance prefs [p]. Job fields, the selection and the notice dialog return to their defaults.
 */
internal fun resetState(prev: MainUiState, p: PersistedUi): MainUiState = MainUiState(
    history = prev.history,
    serverUrl = prev.serverUrl,
    termuxInstalled = prev.termuxInstalled,
    processingMode = prev.processingMode,
    internalUnsupportedReason = prev.internalUnsupportedReason,
    internalModels = prev.internalModels,
    modelStates = prev.modelStates,
    modelSpeed = prev.modelSpeed,
    performance = prev.performance,
    cookieSites = prev.cookieSites,
    ytDlpVersion = prev.ytDlpVersion,
    ytDlpBanner = prev.ytDlpBanner,
    internalModel = prev.internalModel,
    mp3Support = prev.mp3Support,
    audioOnly = p.audioOnly,
    bitrate = p.bitrate,
    outputFormat = p.outputFormat,
    themeMode = p.themeMode,
    dynamicColor = p.dynamicColor,
    colorPalette = p.colorPalette,
    language = p.language,
    widgetTheme = p.widgetTheme,
)

data class MainUiState(
    val state: UiState = UiState.Idle,
    val inputTab: Int = 0, // 0=Link, 1=File (HomeInput.normalizeInputTab)
    /** Link mode list (several links pasted, shared or imported); empty = the single-link field */
    val links: List<String> = emptyList(),
    /** One-shot: Home opens the .txt link import picker (widget Links button) */
    val pendingLinkImport: Boolean = false,
    val url: String = "",
    val selectedModel: String = "Kim_Vocal_2.onnx",
    val models: List<String> = RemoteServerBackend.SERVER_MODELS,
    val customModel: String = "",
    val progress: Int = 0,
    val statusText: String = "",
    val errorMessage: String = "",
    val jobId: String? = null,
    val showModelPicker: Boolean = false,
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
    // Output options; mirror the prefs, changed only by setAudioOnly/setBitrate/setOutputFormat
    val audioOnly: Boolean = false,
    val bitrate: String = "192k",
    val outputFormat: String = "m4a",
    /** Whether the bundled ffmpeg can write MP3 on this phone (describes the device, kept by reset) */
    val mp3Support: Mp3Support.State = Mp3Support.State.Unknown,
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
    /** [snackError] is informational (neutral style), not an error */
    val snackIsInfo: Boolean = false,
    // Appearance
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
    val colorPalette: String = ColorPalettes.DEFAULT_ID,
    val language: String = "",
    val widgetTheme: String = "orange",
    // Processing mode: "internal", "termux" or "server"
    val processingMode: String = BackendProvider.MODE_SERVER,
    // On-device models (catalog order) and their download state
    val internalModels: List<ModelSpec> = emptyList(),
    val modelStates: Map<String, ModelState> = emptyMap(),
    /** Measured ms per chunk for the current Performance prefs; a missing or null entry = no hint. */
    val modelSpeed: Map<String, Long?> = emptyMap(),
    /** License-notice dialog, or null when closed */
    val noticeDialog: NoticeRequest? = null,
    val performance: PerformancePrefs = PerformancePrefs(),
    /** Why this phone cannot run on-device processing, or null if it can */
    val internalUnsupportedReason: String? = null,
    // On-device links (yt-dlp); null until loaded, the first load unzips Python
    val ytDlpVersion: String? = null,
    val ytDlpUpdate: YtDlpUpdateState = YtDlpUpdateState.Idle,
    /** Home yt-dlp card (Internal mode only); it only offers an update */
    val ytDlpBanner: YtDlpBanner.Banner? = null,
    /** The saved on-device model (pref internal_model), also outside Internal mode */
    val internalModel: String = ModelCatalog.HQ3,
    /** Stored cookie sites for on-device links, sorted by site (counts only, never names or values). */
    val cookieSites: List<CookieSiteUi> = emptyList(),
)

/** One stored cookie site; [count] is 0 if its file is missing or invalid. [source] is "login" or "import". */
data class CookieSiteUi(
    val site: String,
    val enabled: Boolean,
    val count: Int,
    val savedAtMs: Long,
    val source: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    private val historyStore = HistoryStore(application)
    private val settingsStore = SettingsStore(application)
    private val notif = NotificationHelper(application)
    private val cache = ResultCache(application)

    /** True if the job's result is stored on this device (on-device jobs, or a downloaded server result). */
    fun isResultOnDevice(jobId: String): Boolean = jobId.isNotEmpty() && cache.isCached(jobId)
    private val backends = BackendProvider(application)
    private fun backend() = backends.current()
    private val modelManager = ModelManager.get(application)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = application.getSharedPreferences("app", Context.MODE_PRIVATE)
    /** The running job (start + events), separate from [MainUiState.jobId], which the Done screen keeps */
    private val tracker = ActiveJobTracker()

    /** The Server/Termux model replaced by HQ3 while in internal mode; restored when leaving it. In memory only. */
    private var remoteModelBeforeInternal: String? = null

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
            colorPalette = settingsStore.colorPalette,
            language = settingsStore.language,
            widgetTheme = settingsStore.widgetTheme,
            processingMode = settingsStore.processingMode,
            audioOnly = settingsStore.audioOnly,
            bitrate = settingsStore.bitrate,
            outputFormat = settingsStore.outputFormat,
            internalModels = ModelCatalog.get(application),
            internalModel = settingsStore.internalModel,
            modelStates = modelManager.state.value,
            performance = PerformancePrefs(settingsStore.perfThreads, settingsStore.perfQuality, settingsStore.perfBatch),
        )
        loadModelSpeed()
        applyModelsForMode()
        refreshBanner()
        // The first cookie store call also migrates a legacy cookies.txt (D3)
        loadCookieSites()
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
            // Cookie copies of previews interrupted by a killed process (D6)
            backends.internal.cleanStalePreviews()
        }
        val savedQueue = loadQueue()
        if (savedJobId != null) {
            _ui.value = _ui.value.copy(
                state = UiState.Processing, jobId = savedJobId,
                statusText = application.getString(R.string.resuming),
                queue = savedQueue,
            )
            val resumed = backends.forJob(savedJobId)
            tracker.resume(
                bgScope, resumed, savedJobId,
                run = { runJob(it) },
                onError = { onStartError(resumed, it, "Connection failed") },
            )
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
                _ui.value = _ui.value.copy(modelStates = states)
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
        val (selected, remembered) = ModelSelection.forMode(
            internal = ui.processingMode == BackendProvider.MODE_INTERNAL,
            selected = ui.selectedModel,
            supported = models,
            remembered = remoteModelBeforeInternal,
            internalDefault = settingsStore.internalModel,
        )
        remoteModelBeforeInternal = remembered
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
        refreshBanner()
        refresh()
    }

    /** On-device models card: saves [name] as the on-device model; in Internal mode it is also selected. */
    fun selectInternalModel(name: String) {
        if (_ui.value.internalModels.none { it.name == name }) return
        settingsStore.internalModel = name
        _ui.value = _ui.value.copy(internalModel = name)
        if (_ui.value.processingMode == BackendProvider.MODE_INTERNAL) {
            _ui.value = _ui.value.copy(selectedModel = name)
        }
    }

    /** Model card Download; a model with an unaccepted license notice opens the notice dialog instead. */
    fun downloadModel(name: String) {
        if (modelManager.needsNotice(name)) {
            _ui.value = _ui.value.copy(noticeDialog = NoticeRequest(name, NoticeTrigger.Download))
            return
        }
        val size = _ui.value.internalModels.firstOrNull { it.name == name }?.size ?: 0L
        bgScope.launch {
            try {
                modelManager.ensure(name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same mapping as the Settings card, so both say the same thing
                showTransientError(modelFailureText(ModelFailure.of(e, size + MODEL_SPACE_MARGIN)))
            }
        }
    }

    /** Notice dialog "Download": records the OK and re-runs the action that opened the dialog. */
    fun acceptNotice(request: NoticeRequest) {
        modelManager.acceptNotice(request.model)
        _ui.value = _ui.value.copy(noticeDialog = null)
        when (val action = onNoticeResult(request, accepted = true)) {
            NoticeAction.RunProcess -> process()
            is NoticeAction.RunDownload -> downloadModel(action.name)
            NoticeAction.None -> Unit
        }
    }

    /** Notice dialog "Cancel": nothing starts and the selection stays. */
    fun dismissNotice() {
        _ui.value = _ui.value.copy(noticeDialog = null)
    }

    private fun modelFailureText(f: ModelFailure): String = when (f) {
        is ModelFailure.NoSpace -> getApplication<Application>().getString(R.string.internal_no_space, (f.neededBytes / 1_000_000).toInt())
        ModelFailure.Corrupt -> str(R.string.internal_model_corrupt)
        ModelFailure.Network -> str(R.string.internal_model_failed_network)
        ModelFailure.Other -> str(R.string.internal_processing_failed)
        ModelFailure.NoticeRequired -> str(R.string.model_notice_required)
    }

    /**
     * ms per chunk per catalog model for the current Performance prefs (D4): key
     * perf_ms:<name>:<resolved threads>:<batch pref>. Missing = no hint.
     */
    private fun loadModelSpeed() {
        val perf = _ui.value.performance
        val threads = PerformanceSettings.threads(perf.threads, Runtime.getRuntime().availableProcessors())
        val speed = _ui.value.internalModels.associate { spec ->
            spec.name to prefs.getLong(PerformanceSettings.speedKey(spec.name, threads, perf.batch), -1L).takeIf { it > 0 }
        }
        _ui.value = _ui.value.copy(modelSpeed = speed)
    }

    fun setPerfThreads(value: Int) {
        settingsStore.perfThreads = value
        _ui.value = _ui.value.copy(performance = _ui.value.performance.copy(threads = settingsStore.perfThreads))
        loadModelSpeed()
    }

    fun setPerfQuality(value: String) {
        settingsStore.perfQuality = value
        _ui.value = _ui.value.copy(performance = _ui.value.performance.copy(quality = settingsStore.perfQuality))
        loadModelSpeed()
    }

    fun setPerfBatch(value: Int) {
        settingsStore.perfBatch = value
        _ui.value = _ui.value.copy(performance = _ui.value.performance.copy(batch = settingsStore.perfBatch))
        loadModelSpeed()
    }

    /** Loads the bundled yt-dlp version once (Settings, Internal mode). */
    fun loadYtDlpVersion() {
        if (_ui.value.ytDlpVersion != null) return
        bgScope.launch {
            val v = try {
                YtDlpRuntime.version(getApplication())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "yt-dlp version failed", e)
                "?"
            }
            _ui.value = _ui.value.copy(ytDlpVersion = v)
            refreshBanner()
        }
    }

    /** Recomputes the Home yt-dlp card from the mode, the loaded version and the banner prefs. */
    private fun refreshBanner() {
        val ui = _ui.value
        val banner = YtDlpBanner.state(
            internalMode = ui.processingMode == BackendProvider.MODE_INTERNAL,
            version = ui.ytDlpVersion,
            today = java.time.LocalDate.now(),
            firstUseSeen = prefs.getBoolean(InternalProcessingWorker.FIRST_USE_SEEN, false),
            dismissedEpochDay = prefs.getLong(YtDlpBanner.PREF_DISMISSED_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
        )
        _ui.value = _ui.value.copy(ytDlpBanner = banner)
    }

    /** Home card close: hides the first-use card for good and the Outdated card for 14 days. */
    fun dismissBanner() {
        prefs.edit()
            .putBoolean(InternalProcessingWorker.FIRST_USE_SEEN, true)
            .putLong(YtDlpBanner.PREF_DISMISSED_DAY, java.time.LocalDate.now().toEpochDay())
            .apply()
        _ui.value = _ui.value.copy(ytDlpBanner = null)
    }

    /** Reloads [MainUiState.cookieSites]; the first call of the process also migrates a legacy cookies.txt. */
    fun loadCookieSites() {
        bgScope.launch { refreshCookieSites() }
    }

    /** Blocking; call on [bgScope]. */
    private fun refreshCookieSites() {
        val sites = CookieStore(getApplication()).sites().map {
            CookieSiteUi(it.site, it.enabled, it.count, it.savedAtMs, it.source)
        }
        _ui.value = _ui.value.copy(cookieSites = sites)
    }

    /** Accounts switch: whether [site]'s cookies are sent with on-device links. */
    fun setCookieSiteEnabled(site: String, on: Boolean) {
        bgScope.launch {
            if (!CookieStore(getApplication()).setEnabled(site, on)) showTransientError(str(R.string.cookies_change_failed))
            refreshCookieSites()
        }
    }

    /** Accounts "Delete" (after the confirm dialog). */
    fun deleteCookieSite(site: String) {
        bgScope.launch {
            if (!CookieStore(getApplication()).deleteSite(site)) showTransientError(str(R.string.cookies_change_failed))
            refreshCookieSites()
        }
    }

    /** Accounts "Delete all" (after the confirm dialog): every site and a leftover legacy cookies.txt. */
    fun deleteAllCookies() {
        bgScope.launch {
            val ok = CookieStore(getApplication()).deleteAll()
            showTransientError(str(if (ok) R.string.cookies_removed else R.string.cookies_change_failed))
            refreshCookieSites()
        }
    }

    /**
     * Accounts "Log in again" for [site]: calls [open] on the main thread with null for the Instagram
     * preset (`cookie_login`), or the https login URL (`cookie_login?url=…`): the site's stored custom URL
     * if [LoginSites.custom] accepts it, else `https://<site>/`. If no login page can be built, shows
     * `cookies_cannot_open_login` and does not call [open].
     */
    fun cookieLoginUrlFor(site: String, open: (url: String?) -> Unit) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                CookieStore(getApplication()).sites().firstOrNull { it.site == site }
            }
            val stored = info?.loginUrl
            when {
                site == INSTAGRAM_SITE && stored == null -> open(null)
                stored != null && LoginSites.custom(stored) != null -> open(stored)
                else -> {
                    val fallback = LoginSites.custom("https://$site/")
                    if (fallback != null) open(fallback.startUrl) else showTransientError(str(R.string.cookies_cannot_open_login))
                }
            }
        }
    }

    /** Settings "Import" (SAF document); invalid files are rejected with a message and leave the stored sites. */
    fun importCookies(uri: android.net.Uri?) {
        if (uri == null) return
        bgScope.launch {
            val app = getApplication<Application>()
            val msg = when (val r = CookieStore(app).import(uri)) {
                is CookieStore.ImportResult.Imported -> str(R.string.cookies_imported)
                is CookieStore.ImportResult.Rejected -> when (r.reason) {
                    CookiesTxt.Invalid.TOO_LARGE -> str(R.string.cookies_invalid_too_large)
                    CookiesTxt.Invalid.EMPTY -> str(R.string.cookies_invalid_empty)
                    CookiesTxt.Invalid.BINARY, CookiesTxt.Invalid.NOT_COOKIES_TXT -> str(R.string.cookies_invalid_format)
                    CookiesTxt.Invalid.MALFORMED_LINE -> app.getString(R.string.cookies_invalid_line, r.line)
                }
                CookieStore.ImportResult.Unreadable -> str(R.string.cookies_unreadable)
            }
            refreshCookieSites()
            showTransientError(msg)
        }
    }

    /**
     * Login screen "Save cookies": stores the WebView's cookies for [site] and clears the WebView. The
     * message names only the cookie count and domains.
     */
    suspend fun saveWebCookies(
        site: com.musicremover.app.processing.internal.LoginSites.Site,
        currentUrl: String?,
        webView: android.webkit.WebView?,
    ) {
        val app = getApplication<Application>()
        val store = CookieStore(app)
        val r = com.musicremover.app.processing.internal.WebLoginCookies.saveAndClear(store, site, currentUrl, webView)
        val msg = when (r) {
            is CookieStore.SaveResult.Saved -> {
                app.resources.getQuantityString(
                    R.plurals.cookies_login_saved, r.captured.count, r.captured.count, r.captured.domains.joinToString(", "),
                )
            }
            is CookieStore.SaveResult.Rejected -> when (r.reason) {
                CookiesTxt.Invalid.TOO_LARGE -> str(R.string.cookies_invalid_too_large)
                CookiesTxt.Invalid.EMPTY -> str(R.string.cookies_login_none)
                else -> str(R.string.cookies_invalid_format)
            }
            CookieStore.SaveResult.NoCookies -> str(R.string.cookies_login_none)
            CookieStore.SaveResult.NotLoggedIn -> str(R.string.cookies_login_not_logged_in)
            CookieStore.SaveResult.Unwritable -> str(R.string.cookies_login_save_failed)
        }
        loadCookieSites()
        showTransientError(msg)
    }

    /** Settings "Update yt-dlp"; a second tap while it runs is ignored. */
    fun updateYtDlp() {
        if (_ui.value.ytDlpUpdate == YtDlpUpdateState.Running) return
        _ui.value = _ui.value.copy(ytDlpUpdate = YtDlpUpdateState.Running)
        bgScope.launch {
            val state = when (val r = YtDlpRuntime.update(getApplication())) {
                is YtDlpRuntime.UpdateResult.Updated -> YtDlpUpdateState.Done(updated = true, version = r.version)
                is YtDlpRuntime.UpdateResult.UpToDate -> YtDlpUpdateState.Done(updated = false, version = r.version)
                YtDlpRuntime.UpdateResult.Busy -> YtDlpUpdateState.Failed(busy = true)
                YtDlpRuntime.UpdateResult.Failed -> YtDlpUpdateState.Failed(busy = false)
            }
            // update() re-reads the version with --version after a change
            val version = (state as? YtDlpUpdateState.Done)?.version?.takeIf { it.isNotEmpty() }
            // The first-use card does not come back next launch; a shown card stays until closed to show the result
            if (YtDlpBanner.afterUpdate(success = state is YtDlpUpdateState.Done) != null) {
                prefs.edit().putBoolean(InternalProcessingWorker.FIRST_USE_SEEN, true).apply()
            }
            _ui.value = _ui.value.copy(ytDlpUpdate = state, ytDlpVersion = version ?: _ui.value.ytDlpVersion)
        }
    }

    fun deleteModel(name: String) {
        if (!modelManager.delete(name)) {
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
    /** Legacy callers may still pass 2 (the old Batch mode); it lands on Link. */
    fun setInputTab(tab: Int) {
        _ui.value = _ui.value.copy(inputTab = HomeInput.normalizeInputTab(tab))
    }

    /** Text holding several links (paste, typing) becomes list rows; one link or other text stays in the field. */
    fun onUrlChange(url: String) {
        previewJob?.cancel()
        val found = HomeInput.extractLinks(url)
        // Typing one char at a time must not cut a half-typed second link; a paste inserts many at once
        if (found.size >= 2 && HomeInput.isLinkListInput(_ui.value.url, url)) {
            addLinks(found)
            _ui.value = _ui.value.copy(url = "", urlPreview = null, loadingPreview = false)
            return
        }
        // With a list shown, the field only adds rows; its preview card is hidden, so skip the fetch
        if (_ui.value.links.isNotEmpty()) {
            _ui.value = _ui.value.copy(url = url, urlPreview = null, loadingPreview = false)
            return
        }
        if (looksLikeUrl(url)) {
            _ui.value = _ui.value.copy(url = url, urlPreview = null, loadingPreview = true)
            fetchUrlPreview(url)
        } else {
            _ui.value = _ui.value.copy(url = url, urlPreview = null, loadingPreview = false)
        }
    }

    /** Appends [urls] to the Link list (distinct, order kept) and shows Link mode. */
    fun addLinks(urls: List<String>) {
        val s = _ui.value
        _ui.value = s.copy(links = (s.links + urls).distinct(), inputTab = HomeInput.TAB_LINK)
    }

    /** "Add another link": moves the field's link into the list so the field is free for the next one. */
    fun addFieldLinkToList() {
        val found = HomeInput.extractLinks(_ui.value.url)
        if (found.isEmpty()) {
            showTransientError(str(R.string.enter_url_or_file))
            return
        }
        previewJob?.cancel()
        addLinks(found)
        _ui.value = _ui.value.copy(url = "", urlPreview = null, loadingPreview = false)
    }

    fun removeLink(index: Int) {
        val links = _ui.value.links
        if (index !in links.indices) return
        _ui.value = _ui.value.copy(links = links.toMutableList().apply { removeAt(index) })
    }

    fun clearLinks() {
        _ui.value = _ui.value.copy(links = emptyList())
    }

    /** "Import .txt": the file's links join the list for review; Remove music starts them. */
    fun importLinkText(text: String) {
        val found = HomeInput.extractLinks(text)
        if (found.isEmpty()) {
            showTransientError(str(R.string.link_import_none))
            return
        }
        // A link typed in the field joins the list first, so it is not lost behind the rows
        val field = HomeInput.extractLinks(_ui.value.url)
        addLinks(field + found)
        if (field.isNotEmpty()) _ui.value = _ui.value.copy(url = "", urlPreview = null, loadingPreview = false)
        showTransientInfo(getApplication<Application>().getString(R.string.links_added, found.size))
    }

    /**
     * Widget Links button: Home opens the .txt import once, but only while the input is on screen
     * (Idle/Error, or minimized processing). Otherwise the picker would pop up later at an unrelated moment.
     */
    fun requestLinkImport() {
        val s = _ui.value
        _ui.value = s.copy(pendingLinkImport = isInputVisible(s), inputTab = HomeInput.TAB_LINK)
    }

    /** Whether Home shows the Link/File input (where the .txt picker lives). */
    fun isInputVisible(s: MainUiState = _ui.value): Boolean =
        s.state == UiState.Idle || s.state == UiState.Error || (s.state == UiState.Processing && s.processingMinimized)

    /**
     * Widget Paste with one link: processes (or queues, if busy) only that link. The Link list and the
     * field are left untouched.
     */
    fun processSingleUrl(url: String) {
        setInputTab(HomeInput.TAB_LINK)
        if (_ui.value.processingMode == BackendProvider.MODE_INTERNAL && modelManager.needsNotice(_ui.value.selectedModel)) {
            // Same gate as Process: the license dialog first; the link waits in the field
            _ui.value = _ui.value.copy(url = url, noticeDialog = NoticeRequest(_ui.value.selectedModel, NoticeTrigger.Process))
            return
        }
        if (_ui.value.state == UiState.Processing) addToQueue(url) else processUrl(url)
        hapticTick()
    }

    fun consumeLinkImport() {
        _ui.value = _ui.value.copy(pendingLinkImport = false)
    }

    /**
     * Picker or shared file: name/size query and a cache copy off the main thread, then File mode.
     * [persist] takes a persistable grant (picker); share grants are temporary, so the copy happens now.
     */
    fun importFile(uri: android.net.Uri, persist: Boolean) {
        val context = getApplication<Application>()
        // Switch once, up front: a switch back to Link during a long copy is not undone when it finishes
        setInputTab(HomeInput.TAB_FILE)
        bgScope.launch {
            if (persist) {
                // Take persistable permission so we can re-read this file later
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            var name: String? = null
            var size: Long? = null
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val n = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (n >= 0) name = it.getString(n)
                        val s = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (s >= 0 && !it.isNull(s)) size = it.getLong(s)
                    }
                }
            } catch (_: Exception) {}
            // Copy to cache for immediate processing (original URI kept for redo)
            val cachedUri = try {
                val fileName = name ?: "video.mp4"
                val cacheFile = java.io.File(context.cacheDir, "upload_${System.currentTimeMillis()}_$fileName")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                android.net.Uri.fromFile(cacheFile)
            } catch (_: Exception) { uri }
            onFileSelected(cachedUri, name, size, uri.toString())
        }
    }

    /** In internal mode a catalog pick is saved as internal_model first, so applyModelsForMode keeps it. */
    fun onModelSelect(model: String) {
        val ui = _ui.value
        if (ui.processingMode == BackendProvider.MODE_INTERNAL && ui.internalModels.any { it.name == model }) {
            settingsStore.internalModel = model
            _ui.value = _ui.value.copy(internalModel = model)
        }
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
        settingsStore.audioOnly = value
        _ui.value = _ui.value.copy(audioOnly = value)
    }

    fun setBitrate(value: String) {
        if (!SettingsStore.isValidBitrate(value)) return
        settingsStore.bitrate = value
        _ui.value = _ui.value.copy(bitrate = value)
    }

    /** Probes MP3 support once per process (only success is cached); never throws. */
    fun checkMp3() {
        val s = _ui.value.mp3Support
        if (s == Mp3Support.State.Checking || s == Mp3Support.State.Available) return
        _ui.value = _ui.value.copy(mp3Support = Mp3Support.State.Checking)
        viewModelScope.launch {
            val ok = Mp3Support.available(getApplication())
            _ui.value = _ui.value.copy(mp3Support = if (ok) Mp3Support.State.Available else Mp3Support.State.Unavailable)
        }
    }

    fun setOutputFormat(value: String) {
        if (value !in SettingsStore.OUTPUT_FORMATS) return
        settingsStore.outputFormat = value
        _ui.value = _ui.value.copy(outputFormat = value)
    }

    fun setThemeMode(mode: String) {
        _ui.value = _ui.value.copy(themeMode = mode)
        settingsStore.themeMode = mode
    }

    fun setDynamicColor(enabled: Boolean) {
        _ui.value = _ui.value.copy(dynamicColor = enabled)
        settingsStore.dynamicColor = enabled
    }

    fun setColorPalette(id: String) {
        if (id !in ColorPalettes.IDS) return
        _ui.value = _ui.value.copy(colorPalette = id)
        settingsStore.colorPalette = id
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
        _ui.value = resetState(_ui.value, settingsStore.persistedUi())
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
                val info = kotlinx.coroutines.withTimeout(infoTimeoutMs(10_000)) {
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

    /** On-device info needs a cold Python start plus extraction on a phone; remote backends keep [remoteDefault]. */
    private fun infoTimeoutMs(remoteDefault: Long) = if (backend() is InternalBackend) 30_000L else remoteDefault

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
                    val info = kotlinx.coroutines.withTimeout(infoTimeoutMs(10_000)) { backend().info(item.url) }
                    _ui.value = _ui.value.copy(videoInfo = info, loadingInfo = false)
                } catch (_: Exception) {
                    _ui.value = _ui.value.copy(loadingInfo = false, videoInfoError = true)
                }
            }
        } else if (hasUrl && cachedInfo != null && cachedInfo.duration == 0) {
            // Have basic info but missing rich data — try server in background
            bgScope.launch {
                try {
                    val rich = kotlinx.coroutines.withTimeout(infoTimeoutMs(8_000)) { backend().info(item.url) }
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
        showTransientError(str(R.string.discover_searching))

        var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
        discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                showTransientError(str(R.string.discover_failed))
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
                        showTransientError(app.getString(R.string.discover_found, url))
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
            showTransientError(app.getString(R.string.discover_error, e.message ?: e.javaClass.simpleName))
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
        showTransientError(context.resources.getQuantityString(R.plurals.exporting_items, items.size, items.size))
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
                    showTransientError(str(R.string.export_none))
                }
            } catch (e: Exception) {
                showTransientError(context.getString(R.string.export_failed, e.message ?: e.javaClass.simpleName))
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
        // A model with an unaccepted license notice: ask first, nothing is queued or started
        val selected = _ui.value.selectedModel
        if (_ui.value.processingMode == BackendProvider.MODE_INTERNAL && modelManager.needsNotice(selected)) {
            _ui.value = _ui.value.copy(noticeDialog = NoticeRequest(selected, NoticeTrigger.Process))
            return
        }
        val s = _ui.value
        val fileUri = s.selectedFileUri
        val source = HomeInput.pickSource(s.inputTab, s.links, s.url, fileUri != null)

        if (source == HomeInput.Source.None) {
            showTransientError(str(R.string.enter_url_or_file))
            return
        }

        // Field text that is not a link is not part of the job; keep it instead of dropping it silently
        val leftoverText = if (s.links.isNotEmpty() && HomeInput.extractLinks(s.url).isEmpty()) s.url else ""

        // Several links: the existing queue (appends while processing, else starts the first)
        if (source is HomeInput.Source.Links) {
            queueUrls(source.urls)
            _ui.value = _ui.value.copy(links = emptyList(), url = leftoverText, urlPreview = null)
            return
        }

        // If already processing, add to queue instead
        if (s.state == UiState.Processing) {
            if (source == HomeInput.Source.File && fileUri != null) {
                addFileToQueue(fileUri)
                clearFile()
            } else if (source is HomeInput.Source.Single) {
                addToQueue(source.text)
                _ui.value = _ui.value.copy(url = leftoverText, urlPreview = null, links = emptyList())
            }
            return
        }

        if (source == HomeInput.Source.File && fileUri != null) {
            processFile(fileUri)
            clearFile()
        } else if (source is HomeInput.Source.Single) {
            // A one-row list runs like the field did
            if (s.links.isNotEmpty()) _ui.value = _ui.value.copy(url = source.text, links = emptyList())
            processUrl(source.text)
            _ui.value = _ui.value.copy(url = leftoverText, urlPreview = null)
        }
        hapticTick()
    }

    fun addToQueue(url: String) {
        val item = QueueItem(
            url = url,
            model = _ui.value.selectedModel,
            audioOnly = _ui.value.audioOnly,
            bitrate = _ui.value.bitrate,
            outputFormat = _ui.value.outputFormat,
        )
        val updated = _ui.value.queue + item
        _ui.value = _ui.value.copy(queue = updated)
        saveQueue(updated)
        hapticTick()
    }

    /** Queue multiple URLs at once (from URL file import) */
    fun queueUrls(urls: List<String>) {
        val items = urls.map { url ->
            QueueItem(
                url = url,
                model = _ui.value.selectedModel,
                audioOnly = _ui.value.audioOnly,
                bitrate = _ui.value.bitrate,
                outputFormat = _ui.value.outputFormat,
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
            processUrl(first.url, first)
        }
        showTransientInfo(getApplication<Application>().resources.getQuantityString(R.plurals.links_queued, urls.size, urls.size))
        hapticTick()
    }

    private fun addFileToQueue(uri: android.net.Uri) {
        val item = QueueItem(
            url = "",
            model = _ui.value.selectedModel,
            audioOnly = _ui.value.audioOnly,
            bitrate = _ui.value.bitrate,
            outputFormat = _ui.value.outputFormat,
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
        if (backend() is InternalBackend && !appInForeground) {
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
            selectedFileUri = next.fileUri,
            selectedFileName = next.fileName,
            selectedFileSize = next.fileSize,
        )
        saveQueue(remaining)
        // The item carries its own Output values; the prefs and the chips are never changed by a queue run
        if (next.fileUri != null) {
            processFile(next.fileUri, next)
        } else {
            processUrl(next.url, next)
        }
    }

    /** Retry the last failed operation */
    fun retry() {
        _ui.value = _ui.value.copy(state = UiState.Idle, errorMessage = "")
        process()
    }

    /** Cancel current item — processes next in queue if any */
    fun cancelProcessing() {
        val slot = tracker.detach()
        activeJobMeta = null
        prefs.edit().remove("active_job_id").apply()
        notif.dismiss()
        if (slot != null && slot.backend.managesNotifications) {
            cancelInternalThenContinue(slot)
            return
        }
        ProcessingService.stop(getApplication())
        if (_ui.value.queue.isNotEmpty()) {
            _ui.value = _ui.value.copy(progress = 0, statusText = "")
            processNextInQueue()
        } else {
            setIdle()
        }
        slot?.let { cancelDetachedRemote(it) }
    }

    /** Cancel current item and clear entire queue */
    fun cancelAll() {
        val slot = tracker.detach()
        activeJobMeta = null
        prefs.edit().remove("active_job_id").remove("queue").apply()
        clearPendingInternalStart()
        notif.dismiss()
        if (slot != null && slot.backend.managesNotifications) {
            _ui.value = _ui.value.copy(queue = emptyList())
            cancelInternalThenContinue(slot)
            return
        }
        ProcessingService.stop(getApplication())
        _ui.value = _ui.value.copy(state = UiState.Idle, progress = 0, statusText = "", processingMinimized = false, queue = emptyList())
        slot?.let { cancelDetachedRemote(it) }
    }

    /**
     * On-device cancel: wait until the work is CANCELLED before the next enqueue, so APPEND_OR_REPLACE
     * never chains the next job behind a work that is about to be cancelled (§3.5).
     */
    private fun cancelInternalThenContinue(slot: ActiveJobTracker.Slot) {
        _ui.value = _ui.value.copy(progress = 0, statusText = "")
        bgScope.launch {
            ActiveJobTracker.cancelAndTakeId(slot)?.let { slot.backend.cancel(it) }
            withContext(Dispatchers.Main) {
                if (_ui.value.queue.isNotEmpty()) processNextInQueue() else setIdle()
            }
        }
    }

    /** Server/Termux cancel of a detached slot; the queue has already moved on. */
    private fun cancelDetachedRemote(slot: ActiveJobTracker.Slot) {
        bgScope.launch {
            ActiveJobTracker.cancelAndTakeId(slot)?.let { id -> runCatching { slot.backend.cancel(id) } }
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

    /**
     * Settings routes on screen, counted by a DisposableEffect in each route (main thread). While > 0
     * the Settings Snackbar shows [MainUiState.snackError] and no Toast is shown.
     */
    @Volatile
    var settingsVisible: Int = 0

    /** A neutral (non-error) transient message, e.g. "Links added". Same lifetime as [showTransientError]. */
    fun showTransientInfo(msg: String) = showTransient(msg, isInfo = true)

    fun showTransientError(msg: String) = showTransient(msg, isInfo = false)

    private fun showTransient(msg: String, isInfo: Boolean) {
        snackJob?.cancel()
        _ui.value = _ui.value.copy(snackError = msg, snackIsInfo = isInfo)
        // Also show a Toast so it's visible even when scrolled (Home); Settings screens use their Snackbar
        if (SnackPolicy.showToast(settingsVisible)) try {
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
        /** Requested audio-only format of the built [ProcessOptions], for the MP3 fallback notice */
        val outputFormat: String = "m4a",
        /** Processing mode at job start (Termux and Server share the remote backend) */
        val mode: String? = null,
    )
    private var activeJobMeta: JobMeta? = null

    private fun processFile(uri: android.net.Uri, item: QueueItem? = null) {
        activeJobMeta = JobMeta(
            url = "", model = _ui.value.selectedModel,
            isFileUpload = true,
            sourceFileName = _ui.value.selectedFileName,
            sourceFileSize = _ui.value.selectedFileSize,
            sourceFilePath = _ui.value.originalFileUri,
            mode = _ui.value.processingMode,
        )
        val backend = backend()
        val managed = backend.managesNotifications
        // On-device jobs do not upload anything
        val text = if (managed) str(R.string.starting) else str(R.string.uploading)
        _ui.value = _ui.value.copy(state = UiState.Processing, progress = 0, statusText = text, jobId = null)
        if (!managed) ProcessingService.start(getApplication(), text, 0)
        val fileName = _ui.value.selectedFileName ?: "video.mp4"
        val options = options(backend, item)
        activeJobMeta = activeJobMeta?.copy(outputFormat = options.outputFormat)
        tracker.launch(
            bgScope, backend,
            start = { backend.startFile(uri, fileName, options) },
            run = { runStartedJob(it) },
            onError = { onStartError(backend, it, "Upload failed") },
        )
    }

    private fun processUrl(url: String, item: QueueItem? = null) {
        activeJobMeta = JobMeta(
            url = url, model = _ui.value.selectedModel,
            isFileUpload = false,
            sourceFileName = null, sourceFileSize = null,
            mode = _ui.value.processingMode,
        )
        val backend = backend()
        val managed = backend.managesNotifications
        _ui.value = _ui.value.copy(state = UiState.Processing, progress = 0, statusText = str(R.string.starting), jobId = null)
        if (!managed) ProcessingService.start(getApplication(), str(R.string.starting), 0)
        val options = options(backend, item)
        activeJobMeta = activeJobMeta?.copy(outputFormat = options.outputFormat)
        tracker.launch(
            bgScope, backend,
            start = { backend.startUrl(url, options) },
            run = { runStartedJob(it) },
            onError = { onStartError(backend, it, "Connection failed") },
        )
    }

    /** A started job: remember it for resume, then follow its events. */
    private suspend fun runStartedJob(jobId: String) {
        _ui.value = _ui.value.copy(jobId = jobId)
        prefs.edit().putString("active_job_id", jobId).apply()
        runJob(jobId)
    }

    /** Start (or event) failure of the tracked job; never called for a cancel. [fallback] is the toast without a message. */
    private suspend fun onStartError(backend: ProcessingBackend, e: Exception, fallback: String) {
        withContext(Dispatchers.Main) {
            if (!backend.managesNotifications) ProcessingService.stop(getApplication())
            showTransientError(e.message ?: fallback)
            if (_ui.value.queue.isNotEmpty()) {
                processNextInQueue()
            } else {
                _ui.value = _ui.value.copy(state = UiState.Idle)
            }
        }
    }

    /** A queued [item] uses its own values; a direct Process uses the UI state, which mirrors the prefs. */
    private fun options(backend: ProcessingBackend, item: QueueItem?): ProcessOptions {
        val ui = _ui.value
        val source = item ?: QueueItem(
            url = ui.url,
            model = ui.selectedModel,
            audioOnly = ui.audioOnly,
            bitrate = ui.bitrate,
            outputFormat = ui.outputFormat,
        )
        // The on-device backend only has its own models (§3.6)
        return source.toOptions(
            supported = backend.supportedModels(),
            internal = backend is InternalBackend,
            internalDefault = settingsStore.internalModel,
            noticeOk = { !modelManager.needsNotice(it) },
            sourceFileSize = ui.selectedFileSize,
            sourceFilePath = ui.originalFileUri,
        )
    }

    private fun statusText(status: String, error: String?, etaSec: Int? = null): String =
        statusWithEta(getApplication(), status, error, etaSec)

    private suspend fun runJob(jobId: String) {
        val backend = backends.forJob(jobId)
        // The on-device worker posts its own notifications and runs its own foreground service
        val managed = backend.managesNotifications
        if (!managed) notif.showProgress(str(R.string.starting), 0)
        backend.events(jobId).collect { e ->
            when (e) {
                is JobEvent.Progress -> {
                    val text = statusText(e.status, e.error, e.etaSec)
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
                            processedBy = meta?.mode ?: _ui.value.processingMode,
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
                    // The worker wrote M4A because this phone cannot encode MP3
                    if (activeJobMeta?.outputFormat == Mp3Support.MP3 && filename.endsWith(".m4a")) {
                        showTransientError(str(R.string.mp3_unavailable_fallback))
                    }
                    activeJobMeta = null
                    // The worker stored a new speed measurement and marked the first use as seen
                    if (managed) {
                        loadModelSpeed()
                        refreshBanner()
                    }
                    // Process next in queue if any
                    if (_ui.value.queue.isNotEmpty()) {
                        processNextInQueue()
                    }
                }
                is JobEvent.Failed -> {
                    val text = statusText(e.status, e.error)
                    prefs.edit().remove("active_job_id").apply()
                    if (managed) loadModelSpeed()
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

        /** ModelManager's free-space margin, for a failure that did not reach the model state */
        const val MODEL_SPACE_MARGIN = 50L * 1024 * 1024
        /** Pref: a queued on-device item is waiting for the app to come to the foreground */
        const val PENDING_INTERNAL_START = "pending_internal_start"
        /** The site of [LoginSites.INSTAGRAM]: "Log in again" opens the preset when it has no custom URL */
        const val INSTAGRAM_SITE = "instagram.com"
    }
}
