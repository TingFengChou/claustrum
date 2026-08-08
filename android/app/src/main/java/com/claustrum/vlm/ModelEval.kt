package com.claustrum.vlm

/**
 * Pure model-evaluation scoring — host-testable (no Android). Lets dev mode run a
 * fixed set of labelled frames through L1 and get a **repeatable** correctness +
 * latency report, so swapping the L1 model always gets a basic validation first.
 *
 * A dev-eval frame is named by convention: `<label>__<kw1>,<kw2>.<ext>` — the case
 * passes if the caption contains **any** listed keyword (recall-oriented).
 * e.g. `fall__倒臥,跌倒,倒地,躺.jpg`.
 */
object ModelEval {

    data class Case(val label: String, val anyOf: List<String>)
    data class CaseResult(val label: String, val caption: String, val latencyMs: Long, val pass: Boolean)
    data class Summary(val total: Int, val passed: Int, val avgLatencyMs: Long, val p50LatencyMs: Long) {
        val passRate: Double get() = if (total > 0) 100.0 * passed / total else 0.0
    }

    /** Pass if the caption contains any of the case's keywords. Empty keyword set → fail. */
    fun score(caption: String, case: Case): Boolean =
        case.anyOf.any { it.isNotBlank() && caption.contains(it) }

    fun evaluate(caption: String, latencyMs: Long, case: Case): CaseResult =
        CaseResult(case.label, caption, latencyMs, score(caption, case))

    /** Aggregate pass-rate + latency (avg + median over non-negative latencies). */
    fun summarize(results: List<CaseResult>): Summary {
        val passed = results.count { it.pass }
        val lat = results.map { it.latencyMs }.filter { it >= 0 }.sorted()
        val avg = if (lat.isNotEmpty()) lat.sum() / lat.size else 0L
        val p50 = if (lat.isNotEmpty()) lat[lat.size / 2] else 0L
        return Summary(results.size, passed, avg, p50)
    }

    /** Parse a dev-eval filename `<label>__<kw1>,<kw2>.<ext>` into a [Case]. */
    fun caseFromFileName(name: String): Case {
        val base = name.substringBeforeLast('.')
        val idx = base.indexOf("__")
        if (idx < 0) return Case(base, emptyList())
        val label = base.substring(0, idx)
        val kws = base.substring(idx + 2).split(',', '，')
            .map { it.trim() }.filter { it.isNotEmpty() }
        return Case(label, kws)
    }
}
