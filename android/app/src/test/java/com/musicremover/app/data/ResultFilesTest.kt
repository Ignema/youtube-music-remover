package com.musicremover.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResultFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var files: ResultFiles

    @Before
    fun setUp() {
        dir = tmp.newFolder("results")
        files = ResultFiles(dir)
    }

    private fun commit(jobId: String, name: String, content: String): File {
        val t = files.tempFile(jobId)
        t.writeText(content)
        return files.commit(jobId, name, t)!!
    }

    @Test
    fun commitWritesMarkerAndGetCachedFileReturnsIt() {
        val result = commit("job1", "song.mp4", "hello")
        assertEquals("job1_song.mp4", result.name)
        assertFalse(File(dir, ".tmp_job1").exists())
        assertFalse(File(dir, ".ok_job1.tmp").exists())
        assertEquals("5\njob1_song.mp4", File(dir, ".ok_job1").readText())
        assertEquals(result, files.getCachedFile("job1"))
        assertNull(files.getUnverifiedFile("job1"))
    }

    @Test
    fun commitReplacesOlderResultOfSameJob() {
        commit("job1", "old.mp4", "old")
        val result = commit("job1", "new.mp4", "newer")
        assertFalse(File(dir, "job1_old.mp4").exists())
        assertEquals(result, files.getCachedFile("job1"))
    }

    @Test
    fun tempFileDeletesExistingTemp() {
        File(dir, ".tmp_job1").writeText("partial")
        assertFalse(files.tempFile("job1").exists())
    }

    @Test
    fun truncatedFileIsUnverified() {
        val result = commit("job1", "song.mp4", "hello")
        result.writeText("hel")
        assertNull(files.getCachedFile("job1"))
        assertEquals(result, files.getUnverifiedFile("job1"))
    }

    @Test
    fun fileWithoutMarkerIsUnverified() {
        val legacy = File(dir, "job1_song.mp4").apply { writeText("legacy") }
        assertNull(files.getCachedFile("job1"))
        assertEquals(legacy, files.getUnverifiedFile("job1"))
    }

    @Test
    fun emptyFileWithoutMarkerIsNotReturned() {
        File(dir, "job1_song.mp4").createNewFile()
        assertNull(files.getCachedFile("job1"))
        assertNull(files.getUnverifiedFile("job1"))
    }

    @Test
    fun deleteRemovesResultTempAndMarkers() {
        commit("job1", "song.mp4", "hello")
        File(dir, ".tmp_job1").writeText("partial")
        File(dir, ".ok_job1.tmp").writeText("x")
        files.delete("job1")
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun cleanStaleKeepsActiveTempAndRemovesOthersAndOrphanMarkers() {
        commit("done", "song.mp4", "hello")
        File(dir, ".tmp_active").writeText("a")
        File(dir, ".ok_active.tmp").writeText("a")
        File(dir, ".tmp_stale").writeText("s")
        File(dir, ".ok_stale.tmp").writeText("s")
        File(dir, ".ok_orphan").writeText("1\norphan_x.mp4")

        files.cleanStale(setOf("active"))

        assertEquals(
            setOf("done_song.mp4", ".ok_done", ".tmp_active", ".ok_active.tmp"),
            dir.list()!!.toSet(),
        )
        assertNotNull(files.getCachedFile("done"))
    }

    @Test
    fun prefixDoesNotConfuseSimilarJobIds() {
        val abc = commit("abc", "song.mp4", "hello")
        assertNull(files.getCachedFile("ab"))
        assertNull(files.getUnverifiedFile("ab"))

        val ab = commit("ab", "other.mp4", "world")
        assertTrue(abc.exists())
        assertEquals(ab, files.getCachedFile("ab"))
        assertEquals(abc, files.getCachedFile("abc"))

        files.delete("ab")
        assertTrue(abc.exists())
        assertEquals(abc, files.getCachedFile("abc"))
    }

    @Test
    fun displayTitleStripsOnlyMediaExtensions() {
        assertEquals("song", displayTitle("song.mp4"))
        assertEquals("song-vocals-only", displayTitle("song-vocals-only.m4a"))
        assertEquals("Song", displayTitle("Song.MP3"))
        assertEquals("a.b.wav", displayTitle("a.b.wav"))
        assertEquals("noext", displayTitle("noext"))
    }
}
