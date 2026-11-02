package com.musicremover.app.processing.internal

import com.musicremover.app.processing.internal.InternalCapability.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternalCapabilityTest {
    @Test
    fun refuses32Bit() {
        assertEquals(Refusal.NOT_64_BIT, InternalCapability.check(false, 8_000_000_000L, false))
    }

    @Test
    fun refusesLowRamDevice() {
        assertEquals(Refusal.LOW_RAM_DEVICE, InternalCapability.check(true, 8_000_000_000L, true))
    }

    @Test
    fun refusesBelowTotalMemory() {
        assertEquals(Refusal.TOTAL_MEM, InternalCapability.check(true, 3_999_999_999L, false))
    }

    @Test
    fun acceptsAtThreshold() {
        assertNull(InternalCapability.check(true, 4_000_000_000L, false))
    }
}
