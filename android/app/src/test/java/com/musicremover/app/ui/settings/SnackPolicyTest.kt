package com.musicremover.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnackPolicyTest {
    @Test
    fun toastOnlyWhenNoSettingsScreenIsVisible() {
        assertTrue(SnackPolicy.showToast(0))
        assertFalse(SnackPolicy.showToast(1))
        // Two routes overlap during a navigation transition
        assertFalse(SnackPolicy.showToast(2))
    }
}
