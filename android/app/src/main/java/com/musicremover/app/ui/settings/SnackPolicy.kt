package com.musicremover.app.ui.settings

/** Transient messages: Settings screens show them in their Snackbar, so the Toast is only for the rest. */
object SnackPolicy {
    /** [settingsVisible] = number of Settings routes on screen. */
    fun showToast(settingsVisible: Int): Boolean = settingsVisible == 0
}
