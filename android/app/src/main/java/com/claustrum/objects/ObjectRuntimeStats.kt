package com.claustrum.objects

import kotlin.math.ceil

internal data class ObjectRuntimeSnapshot(
    val processed: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val maxLatencyMs: Long,
)

/** Bounded, executor-confined latency window used for field diagnostics. */
internal class ObjectRuntimeStats(private val capacity: Int = 120) {
    init { require(capacity > 0) }

    private val latencies = ArrayDeque<Long>()
    private var processed = 0L

    fun record(latencyMs: Long): ObjectRuntimeSnapshot {
        require(latencyMs >= 0L)
        processed += 1
        latencies.addLast(latencyMs)
        if (latencies.size > capacity) latencies.removeFirst()
        return snapshot()
    }

    fun reset() {
        latencies.clear()
        processed = 0L
    }

    private fun snapshot(): ObjectRuntimeSnapshot {
        val sorted = latencies.sorted()
        return ObjectRuntimeSnapshot(
            processed = processed,
            p50LatencyMs = percentile(sorted, 50),
            p95LatencyMs = percentile(sorted, 95),
            maxLatencyMs = sorted.last(),
        )
    }

    private fun percentile(sorted: List<Long>, percent: Int): Long {
        val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
