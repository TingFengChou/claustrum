package com.claustrum.core

/**
 * Kotlin ↔ Rust bridge. Backed by `libclaustrum_core.so` (crate `claustrum-core`,
 * see core-rs/src/ffi.rs). The active path copies single-channel luma bytes into
 * Rust only long enough to compute an aHash, then receives the compact signature
 * back. Full-color L1 frames stay in Kotlin/LiteRT.
 */
object NativeCore {
    init {
        System.loadLibrary("claustrum_core")
    }

    /** Proves the JNI bridge is live; returns a version banner from the Rust core. */
    external fun nativeHello(): String

    /**
     * L0 change-gate aHash of a frame. The caller keeps the previous hash and
     * gates on the Hamming distance: `java.lang.Long.bitCount(prev xor cur)`.
     *
     * Malformed input is *skipped, not fatal*: non-positive dimensions or a
     * `luma` shorter than `width * height` return `0L` rather than throwing —
     * the bounds guard is enforced once in the Rust core (`gate::frame_signature`,
     * host-tested) so a bad frame is dropped without crashing the perception loop.
     */
    external fun frameSignature(luma: ByteArray, width: Int, height: Int): Long

    /** Create one stateful Rust L2 session; returns 0 when the source is invalid. */
    external fun createEventEngine(sourceId: String): Long

    /**
     * Feed one anonymous, pixel-free fast-path observation into Rust L2.
     * Each returned string is one `schemas/event.schema.json` event. An empty array
     * means no transition; null means JNI rejected the handle or payload.
     */
    external fun processEventObservation(
        handle: Long,
        atMs: Long,
        actant: Int,
        secondaryActant: Int,
        pose: Int,
        rapidDescentScore: Float,
        impactScore: Float,
        motionScore: Float,
        closeContactScore: Float,
        strikeScore: Float,
        visiblePeople: Int,
        zoneExit: Boolean,
    ): Array<String>?

    /** Release an L2 session; repeated or invalid handles are ignored by Rust. */
    external fun destroyEventEngine(handle: Long)
}
