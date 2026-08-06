package com.claustrum.vlm

import com.claustrum.core.ChangeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host JVM tests for the L0 gate + L1 describe split — no camera, no Android. */
class PerceptionPipelineTest {

    private class FakeCaptioner(override val backend: String = "fake") : Captioner<String> {
        var calls = 0
        override fun describe(frame: String): String {
            calls++; return "desc:$frame"
        }
    }

    private class ClosableCaptioner : Captioner<String>, AutoCloseable {
        var closed = false
        override fun describe(frame: String) = "x"
        override val backend = "closable"
        override fun close() { closed = true }
    }

    @Test fun admitGatesAndCountsStats() {
        val p = PerceptionPipeline(ChangeGate(threshold = 8), FakeCaptioner())
        assertTrue(p.admit(0L))              // first frame admitted
        assertFalse(p.admit(0L))             // identical → skipped
        assertTrue(p.admit(0xFFFF_FFFFL))    // big change → admitted
        assertEquals(3, p.total)
        assertEquals(2, p.admittedCount)
        assertTrue(p.lastAdmitted)
        assertEquals(32, p.lastDistance)     // 0 xor 0xFFFFFFFF = 32 bits
    }

    @Test fun describeRunsCaptionerAndStoresResult() {
        val fake = FakeCaptioner()
        val p = PerceptionPipeline(ChangeGate(threshold = 8), fake)
        p.describe("frame-1")
        assertEquals(1, fake.calls)
        assertEquals("desc:frame-1", p.lastCaption)
    }

    @Test fun savedPctReflectsSkips() {
        val p = PerceptionPipeline(ChangeGate(threshold = 8), FakeCaptioner())
        p.admit(0L); p.admit(0L); p.admit(0L)   // 1 admit, 2 skips
        assertEquals(3, p.total)
        assertEquals(1, p.admittedCount)
        assertTrue("saved% should exceed 60", p.savedPct > 60.0)
    }

    @Test fun exposesBackendAndThreshold() {
        val p = PerceptionPipeline(ChangeGate(threshold = 8), FakeCaptioner())
        assertEquals("fake", p.backend)
        assertEquals(8, p.threshold)
    }

    @Test fun swapCaptionerInstallsNewBackendAndUsesIt() {
        val p = PerceptionPipeline(ChangeGate(threshold = 8), FakeCaptioner("placeholder"))
        assertEquals("placeholder", p.backend)
        p.swapCaptioner(FakeCaptioner("litert"))
        assertEquals("litert", p.backend)
        p.describe("frame")
        assertEquals("desc:frame", p.lastCaption)   // routed to the new backend
    }

    @Test fun swapCaptionerClosesPreviousResourcefulBackend() {
        val old = ClosableCaptioner()
        val p = PerceptionPipeline(ChangeGate(threshold = 8), old)
        p.swapCaptioner(FakeCaptioner("next"))
        assertTrue("old backend must be closed on swap", old.closed)
    }

    @Test fun swapCaptionerToSameIsNoOpAndKeepsItOpen() {
        val same = ClosableCaptioner()
        val p = PerceptionPipeline(ChangeGate(threshold = 8), same)
        p.swapCaptioner(same)
        assertFalse("swapping to the same backend must not close it", same.closed)
    }
}
