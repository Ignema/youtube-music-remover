package com.musicremover.app

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePersistenceTest {
    private val gson = Gson()

    @Test
    fun roundTrip() {
        // fileUri stays null: android.net.Uri is a stub in JVM tests
        val items = listOf(
            QueueItem(url = "https://example.com/v", model = "Kim_Vocal_2.onnx", audioOnly = false, bitrate = "192k"),
            QueueItem(
                url = "", model = "UVR-MDX-NET-Inst_HQ_3.onnx", audioOnly = true, bitrate = "320k",
                fileName = "tone.mp3", fileSize = 361786L, outputFormat = "mp3",
            ),
        )
        val json = gson.toJson(items.map { it.toJsonMap() })
        assertEquals(items, parseQueue(gson, json))
    }

    @Test
    fun corruptIsEmpty() {
        assertTrue(parseQueue(gson, "[{\"url\":").isEmpty())
    }

    @Test
    fun nullIsEmpty() {
        assertTrue(parseQueue(gson, null).isEmpty())
    }
}
