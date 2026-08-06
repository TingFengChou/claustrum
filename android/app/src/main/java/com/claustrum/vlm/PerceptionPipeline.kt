package com.claustrum.vlm

import com.claustrum.core.ChangeGate

/**
 * L0→L1 orchestration, host-unit-testable (no camera, no Android; fake F=String).
 *
 * Split so the heavy L1 inference never runs on the CameraX analyzer thread:
 *  - [admit] is the fast L0 gate + stats (call on the analyzer thread).
 *  - [describe] runs the (possibly slow) captioner (call on a separate executor,
 *    single-flight — see MonitorActivity).
 *
 * Generic over frame type [F] so tests use a fake; production F=Bitmap.
 */
class PerceptionPipeline<F>(
    private val gate: ChangeGate,
    private val captioner: Captioner<F>,
) : AutoCloseable {
    val backend: String get() = captioner.backend
    val threshold: Int get() = gate.threshold

    @Volatile var total: Long = 0; private set
    @Volatile var admittedCount: Long = 0; private set
    @Volatile var lastSignature: Long = 0; private set
    @Volatile var lastDistance: Int = 0; private set
    @Volatile var lastAdmitted: Boolean = false; private set
    @Volatile var lastCaption: String = "（尚無:等待第一個放行幀）"; private set

    /** Percentage of frames skipped by L0 (the compute saved). */
    val savedPct: Double get() = if (total > 0) 100.0 * (total - admittedCount) / total else 0.0

    /** L0 gate + stats. Fast; safe to call on the analyzer thread. Returns admitted. */
    fun admit(signature: Long): Boolean {
        total++
        lastSignature = signature
        lastDistance = gate.distanceFrom(signature)
        val admitted = gate.admit(signature)
        lastAdmitted = admitted
        if (admitted) admittedCount++
        return admitted
    }

    /** L1 — run the captioner and store the result. Call OFF the analyzer thread. */
    fun describe(frame: F) {
        lastCaption = captioner.describe(frame)
    }

    override fun close() {
        (captioner as? AutoCloseable)?.close()
    }
}
