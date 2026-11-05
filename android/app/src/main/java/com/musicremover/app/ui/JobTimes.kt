package com.musicremover.app.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.musicremover.app.R
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

/** Start times and durations of jobs, for the processing screen and Recent. The math is pure. */
object JobTimes {
    /** Whole minutes between [startMs] and [nowMs], never negative (a clock change can go backwards). */
    fun minutesBetween(startMs: Long, nowMs: Long): Long = ((nowMs - startMs) / 60_000L).coerceAtLeast(0)

    /** Hours and the remaining minutes of [minutes]. */
    fun split(minutes: Long): Pair<Long, Long> = minutes / 60 to minutes % 60

    fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }
}

/** "3 min", "1 h 12 min", or "less than a minute" ("<1 min" when [short], for one-line rows). */
@Composable
fun durationText(minutes: Long, short: Boolean = false): String {
    if (minutes < 1) {
        return stringResource(if (short) R.string.duration_under_minute_short else R.string.duration_under_minute)
    }
    val (h, m) = JobTimes.split(minutes)
    return when {
        h == 0L -> pluralStringResource(R.plurals.duration_minutes, m.toInt(), m.toInt())
        m == 0L -> pluralStringResource(R.plurals.duration_hours, h.toInt(), h.toInt())
        else -> stringResource(
            R.string.duration_hours_minutes,
            pluralStringResource(R.plurals.duration_hours, h.toInt(), h.toInt()),
            pluralStringResource(R.plurals.duration_minutes, m.toInt(), m.toInt()),
        )
    }
}

/** Time of day in the user's 12/24 h format, with the date in front when it is not today. */
@Composable
fun clockText(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val ctx = LocalContext.current
    val time = DateFormat.getTimeFormat(ctx).format(Date(atMs))
    return if (JobTimes.sameDay(atMs, nowMs)) time
    else DateFormat.getMediumDateFormat(ctx).format(Date(atMs)) + ", " + time
}

/** "Started 3:42 PM · 4 min ago", updated every 30 s while shown. */
@Composable
fun startedLabel(startedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val minutes = JobTimes.minutesBetween(startedAt, now)
    val ago = if (minutes < 1) stringResource(R.string.started_just_now)
    else stringResource(R.string.started_ago, durationText(minutes))
    return stringResource(R.string.started_at, clockText(startedAt, now), ago)
}
