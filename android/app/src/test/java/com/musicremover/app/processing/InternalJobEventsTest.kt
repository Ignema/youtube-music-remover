package com.musicremover.app.processing

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternalJobEventsTest {
    private val uuid = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val jobId = "local-$uuid"
    private val notFound = "On-device job not found"
    private val cached = File("results", "${jobId}_song-vocals-only.mp4")

    private fun info(
        state: WorkInfo.State,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ) = WorkInfo(uuid, state, setOf("internal"), output, progress)

    private fun map(info: WorkInfo?, file: File? = null) = InternalJobEvents.map(info, jobId, file, notFound)

    @Test
    fun enqueuedAndBlockedAreQueued() {
        assertEquals(listOf(JobEvent.Progress("queued", 0)), map(info(WorkInfo.State.ENQUEUED)))
        assertEquals(listOf(JobEvent.Progress("queued", 0)), map(info(WorkInfo.State.BLOCKED)))
    }

    @Test
    fun runningReportsStatusAndProgress() {
        val p = workDataOf("status" to "separating", "progress" to 42)
        assertEquals(listOf(JobEvent.Progress("separating", 42)), map(info(WorkInfo.State.RUNNING, progress = p)))
    }

    @Test
    fun runningWithoutProgressIsExtracting() {
        assertEquals(listOf(JobEvent.Progress("extracting", 0)), map(info(WorkInfo.State.RUNNING)))
    }

    @Test
    fun runningWaitingIsQueued() {
        val p = workDataOf("status" to "waiting", "progress" to 0)
        assertEquals(listOf(JobEvent.Progress("queued", 0)), map(info(WorkInfo.State.RUNNING, progress = p)))
    }

    @Test
    fun succeededWithCachedFile() {
        val out = workDataOf("filename" to "song-vocals-only.mp4")
        assertEquals(
            listOf(
                JobEvent.Progress("done", 100, filename = "song-vocals-only.mp4"),
                JobEvent.Done(cached, "song-vocals-only.mp4", null),
            ),
            map(info(WorkInfo.State.SUCCEEDED, output = out), cached),
        )
    }

    @Test
    fun succeededWithoutCachedFile() {
        val out = workDataOf("filename" to "song-vocals-only.m4a")
        assertEquals(
            listOf(
                JobEvent.Progress("done", 100, filename = "song-vocals-only.m4a"),
                JobEvent.Done(null, "song-vocals-only.m4a", null),
            ),
            map(info(WorkInfo.State.SUCCEEDED, output = out)),
        )
    }

    @Test
    fun failedCarriesError() {
        val out = workDataOf("error" to "The file has no audio track")
        assertEquals(
            listOf(JobEvent.Failed("error", "The file has no audio track")),
            map(info(WorkInfo.State.FAILED, output = out)),
        )
    }

    @Test
    fun cancelledIsCancelled() {
        assertEquals(listOf(JobEvent.Failed("cancelled", null)), map(info(WorkInfo.State.CANCELLED)))
    }

    /** null WorkInfo: a previously enqueued id that WorkManager has since pruned (resume path). */
    @Test
    fun nullInfoWithCachedFileIsDone() {
        assertEquals(
            listOf(
                JobEvent.Progress("done", 100, filename = "song-vocals-only.mp4"),
                JobEvent.Done(cached, "song-vocals-only.mp4", null),
            ),
            map(null, cached),
        )
    }

    @Test
    fun nullInfoWithoutCacheIsNotFound() {
        assertEquals(listOf(JobEvent.Failed("error", notFound)), map(null))
    }

    @Test
    fun parseId() {
        assertEquals(uuid, InternalJobEvents.parseId(jobId))
        assertNull(InternalJobEvents.parseId("local-xyz"))
        assertNull(InternalJobEvents.parseId(uuid.toString()))
        assertNull(InternalJobEvents.parseId("local-"))
    }
}
