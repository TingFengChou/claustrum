package com.claustrum.vlm

/**
 * Wraps a [primary] captioner (e.g. [LiteRtCaptioner]) and permanently degrades to
 * a [fallback] (e.g. [PlaceholderCaptioner]) after [maxFailures] failed results —
 * so a model whose inference times out / errors doesn't leave every admitted frame
 * with a dead-end "L1 逾時", but instead falls back to the honest diagnostic.
 * (Codex review: don't keep a backend that never produces output.)
 *
 * Failure is detected by [isFailure] on the returned string (the L1 error/timeout
 * convention). Called single-flight off the analyzer thread.
 */
class FallbackCaptioner<F>(
    private val primary: Captioner<F>,
    private val fallback: Captioner<F>,
    private val maxFailures: Int = 1,
    private val isFailure: (String) -> Boolean = { it.startsWith("L1 逾時") || it.startsWith("L1 錯誤") },
) : Captioner<F>, AutoCloseable {

    @Volatile private var degraded = false
    private var failures = 0

    override fun describe(frame: F): String {
        if (degraded) return fallback.describe(frame)
        val result = primary.describe(frame)
        if (isFailure(result)) {
            failures++
            if (failures >= maxFailures) {
                degraded = true
                (primary as? AutoCloseable)?.close()
                return fallback.describe(frame)
            }
            return result
        }
        failures = 0
        return result
    }

    override val backend: String
        get() = if (degraded) "${fallback.backend}(已降級)" else primary.backend

    override fun close() {
        (primary as? AutoCloseable)?.close()
        (fallback as? AutoCloseable)?.close()
    }
}
