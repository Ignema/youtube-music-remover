package com.musicremover.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class JobTimesTest {
    @Test
    fun minutesBetweenRoundsDownAndNeverGoesNegative() {
        assertEquals(0, JobTimes.minutesBetween(0, 59_999))
        assertEquals(1, JobTimes.minutesBetween(0, 60_000))
        assertEquals(72, JobTimes.minutesBetween(1_000, 1_000 + 72 * 60_000L + 30_000))
        assertEquals(0, JobTimes.minutesBetween(10_000, 5_000))
    }

    @Test
    fun splitHoursAndMinutes() {
        assertEquals(0L to 7L, JobTimes.split(7))
        assertEquals(1L to 0L, JobTimes.split(60))
        assertEquals(2L to 5L, JobTimes.split(125))
    }

    @Test
    fun sameDay() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 5, 23, 50, 0) }
        val late = c.timeInMillis
        val nextMorning = late + 20 * 60_000L
        assertTrue(JobTimes.sameDay(late, late + 5 * 60_000L))
        assertFalse(JobTimes.sameDay(late, nextMorning))
    }
}
