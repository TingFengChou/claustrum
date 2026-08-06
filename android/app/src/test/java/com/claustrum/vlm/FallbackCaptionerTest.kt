package com.claustrum.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host JVM tests for degrade-on-failure. */
class FallbackCaptionerTest {

    private class Fixed(val out: String, override val backend: String) : Captioner<String> {
        var calls = 0
        override fun describe(frame: String): String { calls++; return out }
    }

    @Test fun passesThroughWhenPrimarySucceeds() {
        val primary = Fixed("一人坐於沙發", "real")
        val fb = Fixed("diag", "placeholder")
        val c = FallbackCaptioner(primary, fb)
        assertEquals("一人坐於沙發", c.describe("f"))
        assertEquals("real", c.backend)
        assertEquals(0, fb.calls)
    }

    @Test fun degradesToFallbackAfterFailure() {
        val primary = Fixed("L1 逾時", "real")
        val fb = Fixed("diag", "placeholder")
        val c = FallbackCaptioner(primary, fb, maxFailures = 1)
        assertEquals("diag", c.describe("f"))          // primary fails → degrade → fallback
        assertTrue(c.backend.contains("placeholder"))
        assertEquals("diag", c.describe("f2"))         // subsequent calls use fallback
        assertEquals(1, primary.calls)                 // primary not called again after degrade
        assertEquals(2, fb.calls)
    }

    @Test fun errorPrefixAlsoDegrades() {
        val c = FallbackCaptioner(Fixed("L1 錯誤:boom", "real"), Fixed("diag", "ph"), maxFailures = 1)
        assertEquals("diag", c.describe("f"))
    }
}
