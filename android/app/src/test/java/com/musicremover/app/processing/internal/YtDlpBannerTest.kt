package com.musicremover.app.processing.internal

import com.musicremover.app.processing.internal.YtDlpBanner.Banner
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpBannerTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val old = "2025.11.12" // 357 days before today
    private val fresh = "2026.10.30" // 5 days

    private fun state(
        internal: Boolean = true,
        version: String? = old,
        firstUseSeen: Boolean = true,
        dismissed: Long? = null,
    ) = YtDlpBanner.state(internal, version, today, firstUseSeen, dismissed)

    @Test
    fun notInternalShowsNothing() {
        assertNull(state(internal = false))
        assertNull(state(internal = false, firstUseSeen = false))
    }

    @Test
    fun firstUseWithoutVersionHasNoAge() {
        assertEquals(Banner.FirstUse(null), state(version = null, firstUseSeen = false))
    }

    @Test
    fun firstUseCarriesTheAgeOnceLoaded() {
        assertEquals(Banner.FirstUse(357), state(firstUseSeen = false))
        assertEquals(Banner.FirstUse(5), state(version = fresh, firstUseSeen = false))
        // A version without a date ("?" after a failed load)
        assertEquals(Banner.FirstUse(null), state(version = "?", firstUseSeen = false))
    }

    @Test
    fun firstUseWinsOverADismissDay() {
        assertEquals(Banner.FirstUse(357), state(firstUseSeen = false, dismissed = today.toEpochDay()))
    }

    @Test
    fun outdatedAfterFirstUse() {
        assertEquals(Banner.Outdated(old, 357), state())
    }

    @Test
    fun notOutdatedAtOrBelow14Days() {
        assertNull(state(version = fresh))
        assertNull(state(version = "2026.10.21")) // exactly 14 days
        assertEquals(Banner.Outdated("2026.10.20", 15), state(version = "2026.10.20"))
    }

    @Test
    fun unknownVersionShowsNothingAfterFirstUse() {
        assertNull(state(version = null))
        assertNull(state(version = "?"))
    }

    @Test
    fun dismissSnoozesFor14Days() {
        val d = today.toEpochDay()
        assertNull(state(dismissed = d))
        assertNull(state(dismissed = d - 13))
        assertEquals(Banner.Outdated(old, 357), state(dismissed = d - 14))
        assertEquals(Banner.Outdated(old, 357), state(dismissed = d - 100))
    }

    @Test
    fun afterUpdateWritesFirstUseOnlyOnSuccess() {
        assertEquals(YtDlpBanner.FirstUseWrite, YtDlpBanner.afterUpdate(success = true))
        assertNull(YtDlpBanner.afterUpdate(success = false))
    }

    @Test
    fun aSuccessfulUpdateToAFreshVersionClearsOutdated() {
        // After the update the pref says first use seen and the new version is recent
        assertEquals(Banner.FirstUse(357), state(firstUseSeen = false))
        val seen = YtDlpBanner.afterUpdate(success = true) != null
        assertNull(state(version = fresh, firstUseSeen = seen))
    }
}
