package com.claustrum.monitor

/**
 * Thread-safe camera/analysis health state for the guardian.
 *
 * Camera permission and binding callbacks run on the main thread while frame analysis
 * runs on CameraX's analyzer thread. Keeping their transitions here makes retry and
 * health semantics explicit and host-unit-testable instead of spreading booleans across
 * the Activity.
 */
class GuardianSession(
    private val analysisFailureThreshold: Int = 3,
) {
    init {
        require(analysisFailureThreshold > 0)
    }

    data class Snapshot(
        val active: Boolean,
        val guarding: Boolean,
        val error: String?,
    )

    private var starting = false
    private var cameraBound = false
    private var receivedFrame = false
    private var consecutiveAnalysisFailures = 0
    private var error: String? = null

    /** Returns true only when the caller should start permission/binding work. */
    @Synchronized
    fun beginActivation(): Boolean {
        if (starting || cameraBound) return false
        starting = true
        receivedFrame = false
        consecutiveAnalysisFailures = 0
        error = null
        return true
    }

    /** Permission/provider/bind failure: return to a retryable inactive state. */
    @Synchronized
    fun activationFailed(message: String) {
        starting = false
        cameraBound = false
        receivedFrame = false
        consecutiveAnalysisFailures = 0
        error = message
    }

    /** Binding succeeded; guarding becomes true only after an analyzable frame arrives. */
    @Synchronized
    fun cameraBound() {
        starting = false
        cameraBound = true
        receivedFrame = false
        consecutiveAnalysisFailures = 0
        error = null
    }

    @Synchronized
    fun frameProcessed() {
        if (!cameraBound) return
        receivedFrame = true
        consecutiveAnalysisFailures = 0
        error = null
    }

    /**
     * A transient analyzer failure keeps the last healthy status. Repeated failures mark
     * the active camera as degraded; a later good frame automatically recovers it.
     */
    @Synchronized
    fun frameFailed(message: String) {
        if (!cameraBound) return
        consecutiveAnalysisFailures++
        if (consecutiveAnalysisFailures >= analysisFailureThreshold) error = message
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        active = starting || cameraBound,
        guarding = cameraBound && receivedFrame && error == null,
        error = error,
    )
}
