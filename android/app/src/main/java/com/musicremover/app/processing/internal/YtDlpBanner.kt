package com.musicremover.app.processing.internal

import java.time.LocalDate

/**
 * Home "Update now" banner for the bundled yt-dlp. Pure: it only decides what to show and what to
 * persist. It never starts an update; only the banner's and the yt-dlp screen's Update buttons do.
 */
object YtDlpBanner {
    sealed interface Banner {
        /** First Internal-mode visit; [ageDays] is null until the version has loaded (or has no date). */
        data class FirstUse(val ageDays: Long?) : Banner

        data class Outdated(val version: String, val ageDays: Long) : Banner
    }

    /** What a finished Update writes: `internal_first_use_seen = true` (prefs app). */
    data object FirstUseWrite

    /** Prefs app: epoch day of the last banner dismiss. */
    const val PREF_DISMISSED_DAY = "ytdlp_banner_dismissed_day"

    /** A dismissed Outdated banner stays hidden this many days. */
    const val SNOOZE_DAYS = 14L

    fun state(
        internalMode: Boolean,
        version: String?,
        today: LocalDate,
        firstUseSeen: Boolean,
        dismissedEpochDay: Long?,
    ): Banner? {
        if (!internalMode) return null
        val age = version?.let { YtDlpErrors.versionAgeDays(it, today) }
        if (!firstUseSeen) return Banner.FirstUse(age)
        val snoozed = dismissedEpochDay != null && today.toEpochDay() - dismissedEpochDay < SNOOZE_DAYS
        return if (version != null && age != null && age > YtDlpErrors.OUTDATED_DAYS && !snoozed) {
            Banner.Outdated(version, age)
        } else {
            null
        }
    }

    /** A successful update marks the first use as seen; a failed one writes nothing. */
    fun afterUpdate(success: Boolean): FirstUseWrite? = if (success) FirstUseWrite else null
}
