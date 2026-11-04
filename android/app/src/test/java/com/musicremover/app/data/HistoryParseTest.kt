package com.musicremover.app.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryParseTest {
    private val gson = Gson()

    @Test
    fun nullIsEmpty() {
        assertTrue(parseHistory(gson, null).isEmpty())
    }

    @Test
    fun emptyArrayIsEmpty() {
        assertTrue(parseHistory(gson, "[]").isEmpty())
    }

    @Test
    fun roundTripKeepsAllFields() {
        val items = listOf(
            HistoryItem(
                filename = "a-vocals-only.mp4", url = "https://example.com/a", model = "Kim_Vocal_2.onnx",
                jobId = "job-a", timestamp = 1L, ytTitle = "A", ytDuration = 212, processedBy = "remote",
            ),
            HistoryItem(
                filename = "b-vocals-only.m4a", url = "", model = "UVR-MDX-NET-Inst_HQ_3.onnx",
                jobId = "job-b", timestamp = 2L, isFileUpload = true, sourceFileName = "b.mp3",
                sourceFileSize = 361786L, processedBy = "internal",
            ),
        )
        assertEquals(items, parseHistory(gson, gson.toJson(items)))
    }

    @Test
    fun corruptJsonIsEmpty() {
        assertTrue(parseHistory(gson, "[{\"filename\":").isEmpty())
    }

    @Test
    fun wrongShapeIsEmpty() {
        assertTrue(parseHistory(gson, "{\"a\":1}").isEmpty())
    }
}
