package com.musicremover.app.processing

import com.musicremover.app.data.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalHistoryTest {
    private val url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

    @Test
    fun fileJob() {
        val item = InternalHistory.item(
            jobId = "local-1", model = "HQ3", outName = "song-vocals-only.m4a", durationS = 200,
            url = null, info = null, fileName = "song.mp3", sourceFileSize = 1234L, sourceFilePath = "content://x/1",
        )
        assertEquals("song-vocals-only.m4a", item.filename)
        assertEquals("", item.url)
        assertEquals("HQ3", item.model)
        assertEquals("local-1", item.jobId)
        assertTrue(item.isFileUpload)
        assertEquals("song.mp3", item.sourceFileName)
        assertEquals(1234L, item.sourceFileSize)
        assertEquals("content://x/1", item.sourceFilePath)
        assertEquals("song", item.ytTitle)
        assertEquals(200, item.ytDuration)
        assertNull(item.ytThumbnail)
    }

    @Test
    fun urlJobWithInfo() {
        val info = VideoInfo(
            title = "Me at the zoo", channel = "jawed", duration = 19, thumbnail = "https://i/t.jpg",
            view_count = 42, upload_date = "20050424", description = "desc",
        )
        val item = InternalHistory.item(
            jobId = "local-2", model = "HQ3", outName = "Me at the zoo-vocals-only.mp4", durationS = 18,
            url = url, info = info, fileName = "audio", sourceFileSize = null, sourceFilePath = null,
        )
        assertEquals(url, item.url)
        assertFalse(item.isFileUpload)
        assertNull(item.sourceFileName)
        assertEquals("Me at the zoo", item.ytTitle)
        assertEquals("jawed", item.ytChannel)
        assertEquals("https://i/t.jpg", item.ytThumbnail)
        assertEquals(19, item.ytDuration)
        assertEquals(42L, item.ytViewCount)
        assertEquals("20050424", item.ytUploadDate)
        assertEquals("desc", item.ytDescription)
    }

    @Test
    fun urlJobWithZeroInfoDurationUsesDecodedDuration() {
        val item = InternalHistory.item(
            jobId = "local-3", model = "HQ3", outName = "x-vocals-only.mp4", durationS = 18,
            url = url, info = VideoInfo(title = "x"), fileName = "audio", sourceFileSize = null, sourceFilePath = null,
        )
        assertEquals(18, item.ytDuration)
    }

    @Test
    fun urlResumeWithoutInfo() {
        for (name in listOf("Me at the zoo-vocals-only.mp4", "Me at the zoo-vocals-only.m4a")) {
            val item = InternalHistory.item(
                jobId = "local-4", model = "HQ3", outName = name, durationS = 0,
                url = url, info = null, fileName = "audio", sourceFileSize = null, sourceFilePath = null,
            )
            assertEquals(url, item.url)
            assertFalse(item.isFileUpload)
            assertNull(item.sourceFileName)
            assertEquals("Me at the zoo", item.ytTitle)
            assertEquals(0, item.ytDuration)
            assertEquals(name, item.filename)
        }
    }
}
