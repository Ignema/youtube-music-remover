package com.musicremover.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The Done screen only appears for the job on screen (#2/#4: Done came back after "Process another"). */
class DoneForJobTest {
    private val processing = MainUiState(state = UiState.Processing, jobId = "job-1", progress = 90, statusText = "Merging")

    @Test
    fun currentJobBecomesDone() {
        val r = doneForJob(processing, "job-1", "song-vocals-only.mp4")
        assertEquals(UiState.Done, r.state)
        assertEquals("song-vocals-only.mp4", r.statusText)
        assertEquals("job-1", r.jobId)
    }

    @Test
    fun afterProcessAnotherLateEventKeepsIdle() {
        // resetState clears jobId, so a late "done" for the old job must not change the Idle screen
        val idle = MainUiState(state = UiState.Idle, jobId = null)
        assertSame(idle, doneForJob(idle, "job-1", "song-vocals-only.mp4"))
    }

    @Test
    fun otherJobDoesNotReplaceTheCurrentOne() {
        val other = processing.copy(jobId = "job-2")
        assertSame(other, doneForJob(other, "job-1", "song-vocals-only.mp4"))
    }
}
