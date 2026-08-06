package com.claustrum.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the L0 gate — no Android, no hardware. */
class ChangeGateTest {

    @Test fun firstFrameIsAlwaysAdmitted() {
        val gate = ChangeGate(threshold = 8)
        assertTrue(gate.admit(0x0L))
    }

    @Test fun identicalFrameIsSkipped() {
        val gate = ChangeGate(threshold = 8)
        gate.admit(0xABCD_1234_5678_9F00uL.toLong())
        assertFalse(gate.admit(0xABCD_1234_5678_9F00uL.toLong()))
    }

    @Test fun bigChangeIsAdmitted() {
        val gate = ChangeGate(threshold = 8)
        gate.admit(0x0L)
        // 0xFFFFFFFF = 32 bits set → distance 32 ≥ threshold.
        assertTrue(gate.admit(0xFFFF_FFFFL))
    }

    @Test fun subThresholdDriftIsSkipped() {
        val gate = ChangeGate(threshold = 8)
        gate.admit(0x0L)
        // 7 bits flipped < threshold 8 → skip.
        assertFalse(gate.admit(0b0111_1111L))
    }

    @Test fun distanceMatchesBitCount() {
        val gate = ChangeGate(threshold = 8)
        gate.admit(0x0L)
        assertEquals(32, gate.distanceFrom(0xFFFF_FFFFL))
    }

    @Test fun prevAdvancesOnlyOnAdmission() {
        val gate = ChangeGate(threshold = 8)
        gate.admit(0x0L)                 // prev = 0
        assertFalse(gate.admit(0b11L))   // 2 bits < 8 → skip; prev stays 0
        // still compared against 0, not against 0b11:
        assertEquals(2, gate.distanceFrom(0b11L))
    }

    @Test fun resetForgetsPrev() {
        val gate = ChangeGate(threshold = 8)
        gate.admit(0xFFFFL)
        gate.reset()
        assertTrue(gate.admit(0xFFFFL)) // first-after-reset admitted again
    }
}
