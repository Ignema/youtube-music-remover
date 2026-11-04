package com.musicremover.app

import com.musicremover.app.data.HistoryItem
import com.musicremover.app.data.PersistedUi
import com.musicremover.app.processing.BackendProvider
import com.musicremover.app.processing.internal.ModelCatalog
import com.musicremover.app.processing.internal.ModelSpec
import com.musicremover.app.processing.internal.ModelState
import com.musicremover.app.processing.internal.Mp3Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResetStateTest {
    private val spec = ModelSpec(
        ModelCatalog.HQ3, "https://example.com/m.onnx", 1L, "0".repeat(64), 6144, 3072, 256, 1024,
        1.0, "Instrumental", 256, 0.25, "hq3", false,
    )
    private val persisted = PersistedUi(
        audioOnly = true, bitrate = "320k", outputFormat = "mp3", themeMode = "dark",
        dynamicColor = false, colorPalette = "teal", language = "fr", widgetTheme = "blue",
    )

    private val prev = MainUiState(
        state = UiState.Done,
        url = "https://example.com/v",
        links = listOf("https://a.com/1", "https://b.com/2"),
        pendingLinkImport = true,
        inputTab = 1,
        selectedModel = "UVR_MDXNET_3_9662.onnx",
        progress = 100,
        statusText = "song-vocals-only.m4a",
        errorMessage = "old error",
        jobId = "local-1",
        selectedFileName = "a.mp4",
        history = listOf(HistoryItem(filename = "a", url = "", model = "m", jobId = "j")),
        serverUrl = "http://10.0.0.2:8000",
        termuxInstalled = true,
        processingMode = BackendProvider.MODE_INTERNAL,
        internalUnsupportedReason = "reason",
        internalModels = listOf(spec),
        modelStates = mapOf(ModelCatalog.HQ3 to ModelState.Ready(1L)),
        modelSpeed = mapOf(ModelCatalog.HQ3 to 4_000L),
        performance = PerformancePrefs(threads = 2, quality = "best", batch = 2),
        cookieSites = listOf(CookieSiteUi("instagram.com", true, 3, 1_730_000_000_000L, "login")),
        ytDlpVersion = "2025.11.12",
        mp3Support = Mp3Support.State.Unavailable,
        noticeDialog = NoticeRequest("Kim_Vocal_2.onnx", NoticeTrigger.Process),
        audioOnly = false,
        bitrate = "128k",
        themeMode = "light",
    )

    @Test
    fun keepsPersistedPrefs() {
        val r = resetState(prev, persisted)
        assertEquals(true, r.audioOnly)
        assertEquals("320k", r.bitrate)
        assertEquals("mp3", r.outputFormat)
        assertEquals("dark", r.themeMode)
        assertEquals(false, r.dynamicColor)
        assertEquals("teal", r.colorPalette)
        assertEquals("fr", r.language)
        assertEquals("blue", r.widgetTheme)
    }

    @Test
    fun copiesLiveFields() {
        val r = resetState(prev, persisted)
        assertEquals(prev.history, r.history)
        assertEquals(prev.serverUrl, r.serverUrl)
        assertEquals(prev.termuxInstalled, r.termuxInstalled)
        assertEquals(prev.processingMode, r.processingMode)
        assertEquals(prev.internalUnsupportedReason, r.internalUnsupportedReason)
        assertEquals(prev.internalModels, r.internalModels)
        assertEquals(prev.modelStates, r.modelStates)
        assertEquals(prev.modelSpeed, r.modelSpeed)
        assertEquals(prev.performance, r.performance)
        assertEquals(prev.cookieSites, r.cookieSites)
        assertEquals(prev.ytDlpVersion, r.ytDlpVersion)
        // Describes the device, not the job
        assertEquals(Mp3Support.State.Unavailable, r.mp3Support)
    }

    @Test
    fun clearsJobFields() {
        val r = resetState(prev, persisted)
        val d = MainUiState()
        assertEquals(UiState.Idle, r.state)
        assertEquals("", r.url)
        assertEquals(0, r.progress)
        assertEquals("", r.statusText)
        assertEquals("", r.errorMessage)
        assertNull(r.jobId)
        assertNull(r.selectedFileName)
        assertNull(r.noticeDialog)
        assertEquals(emptyList<String>(), r.links)
        assertEquals(false, r.pendingLinkImport)
        assertEquals(0, r.inputTab)
        // The selection returns to the default; applyModelsForMode sets the internal model afterwards
        assertEquals(d.selectedModel, r.selectedModel)
    }
}
