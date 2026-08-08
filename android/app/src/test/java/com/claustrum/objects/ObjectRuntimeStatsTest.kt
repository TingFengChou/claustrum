package com.claustrum.objects

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectRuntimeStatsTest {
    @Test
    fun reportsNearestRankPercentiles() {
        val stats = ObjectRuntimeStats(capacity = 20)
        var snapshot = stats.record(10)
        for (latency in 20L..100L step 10) snapshot = stats.record(latency)

        assertEquals(10L, snapshot.processed)
        assertEquals(50L, snapshot.p50LatencyMs)
        assertEquals(100L, snapshot.p95LatencyMs)
        assertEquals(100L, snapshot.maxLatencyMs)
    }

    @Test
    fun evictsOldSamplesAndResets() {
        val stats = ObjectRuntimeStats(capacity = 2)
        stats.record(500)
        stats.record(10)
        val bounded = stats.record(20)
        assertEquals(10L, bounded.p50LatencyMs)
        assertEquals(20L, bounded.p95LatencyMs)

        stats.reset()
        val reset = stats.record(7)
        assertEquals(1L, reset.processed)
        assertEquals(7L, reset.p95LatencyMs)
    }
}
