package com.claustrum.objects

/**
 * Bounded movement gate for the object detector. Change starts a short active window;
 * a periodic probe still runs when the scene looks static so slow/small motion is not
 * permanently invisible. This is scheduling only, never event evidence.
 */
internal class ObjectCandidateGate(
    private val changeThreshold: Int = 4,
    private val minIntervalMs: Long = 250L,
    private val activeWindowMs: Long = 1_500L,
    private val periodicProbeMs: Long = 2_000L,
) {
    init {
        require(changeThreshold in 1..64)
        require(minIntervalMs > 0)
        require(activeWindowMs >= minIntervalMs)
        require(periodicProbeMs >= minIntervalMs)
    }

    private var referenceHash: Long? = null
    private var lastAnalyzedAtMs = Long.MIN_VALUE
    private var activeUntilMs = Long.MIN_VALUE

    fun shouldAnalyze(signature: Long, atMs: Long): Boolean {
        require(atMs >= 0) { "atMs must be non-negative" }
        val reference = referenceHash
        if (reference == null) return admit(signature, atMs)
        if (atMs <= lastAnalyzedAtMs) return false

        val changed = java.lang.Long.bitCount(reference xor signature) >= changeThreshold
        if (changed) activeUntilMs = maxOf(activeUntilMs, atMs + activeWindowMs)
        val intervalElapsed = atMs - lastAnalyzedAtMs >= minIntervalMs
        val active = atMs <= activeUntilMs
        val periodic = atMs - lastAnalyzedAtMs >= periodicProbeMs
        return if (intervalElapsed && (active || changed || periodic)) {
            admit(signature, atMs)
        } else {
            false
        }
    }

    fun reset() {
        referenceHash = null
        lastAnalyzedAtMs = Long.MIN_VALUE
        activeUntilMs = Long.MIN_VALUE
    }

    private fun admit(signature: Long, atMs: Long): Boolean {
        referenceHash = signature
        lastAnalyzedAtMs = atMs
        return true
    }
}
