package com.claustrum.core

/**
 * L0 change-gate (Kotlin side). Holds the last *admitted* frame signature and
 * admits a new frame only when it differs by at least [threshold] bits (Hamming
 * distance). Mirrors the Rust `gate::ChangeGate` contract — signatures are the
 * aHash produced by [NativeCore.frameSignature]; this class keeps the stateful
 * "compare against the last processed frame" so slow drift accumulates.
 *
 * Pure and hardware-free: unit-testable on the JVM.
 */
class ChangeGate(val threshold: Int = DEFAULT_THRESHOLD) {

    private var hasPrev = false
    private var prev: Long = 0

    /** Hamming distance from the last admitted signature (or [threshold] for the first frame). */
    fun distanceFrom(signature: Long): Int =
        if (!hasPrev) threshold else (prev xor signature).countOneBits()

    /**
     * Returns true if [signature] differs enough from the last admitted frame.
     * The first frame is always admitted. `prev` advances only on admission, so
     * gradual change is measured against the last frame we actually processed.
     */
    fun admit(signature: Long): Boolean {
        val changed = !hasPrev || (prev xor signature).countOneBits() >= threshold
        if (changed) {
            prev = signature
            hasPrev = true
        }
        return changed
    }

    fun reset() {
        hasPrev = false
        prev = 0
    }

    companion object {
        /** Starting threshold in bits (of 64); 6–10 is a sensible range. */
        const val DEFAULT_THRESHOLD = 8
    }
}
