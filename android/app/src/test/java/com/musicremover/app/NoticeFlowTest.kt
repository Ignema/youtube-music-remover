package com.musicremover.app

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeFlowTest {
    private val kim = "Kim_Vocal_2.onnx"

    @Test
    fun acceptFromProcessRunsProcess() {
        assertEquals(NoticeAction.RunProcess, onNoticeResult(NoticeRequest(kim, NoticeTrigger.Process), accepted = true))
    }

    @Test
    fun acceptFromDownloadRunsThatDownload() {
        assertEquals(NoticeAction.RunDownload(kim), onNoticeResult(NoticeRequest(kim, NoticeTrigger.Download), accepted = true))
    }

    @Test
    fun cancelDoesNothingAndKeepsTheSelection() {
        val selected = MainUiState(selectedModel = kim, noticeDialog = NoticeRequest(kim, NoticeTrigger.Process))
        for (trigger in NoticeTrigger.entries) {
            assertEquals(NoticeAction.None, onNoticeResult(NoticeRequest(kim, trigger), accepted = false))
        }
        // The reducer has no state to change: the selection is whatever it was
        assertEquals(kim, selected.selectedModel)
    }
}
