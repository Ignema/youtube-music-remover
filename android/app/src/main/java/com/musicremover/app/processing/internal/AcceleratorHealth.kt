package com.musicremover.app.processing.internal

import android.content.SharedPreferences

/**
 * Remembers which accelerators failed on this phone (prefs `app`, key `accel_failed:<pref>`), so
 * Settings → Performance can say so. A later job that runs on that accelerator clears it.
 */
object AcceleratorHealth {
    const val PREFIX = "accel_failed:"

    fun key(acc: Accelerator) = PREFIX + acc.pref

    fun markFailed(prefs: SharedPreferences, acc: Accelerator) {
        if (acc != Accelerator.CPU) prefs.edit().putLong(key(acc), System.currentTimeMillis()).apply()
    }

    fun markWorked(prefs: SharedPreferences, acc: Accelerator) {
        if (prefs.contains(key(acc))) prefs.edit().remove(key(acc)).apply()
    }

    fun failed(prefs: SharedPreferences): Set<Accelerator> =
        Accelerator.entries.filter { it != Accelerator.CPU && prefs.contains(key(it)) }.toSet()
}
