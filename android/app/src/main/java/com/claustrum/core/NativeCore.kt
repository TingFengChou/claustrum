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

    /** Legacy ADR-0008 seam; MonitorActivity uses Kotlin LiteRtCaptioner instead. */
    @Deprecated("Legacy Rust L1 placeholder; use com.claustrum.vlm.Captioner")
    external fun describe(luma: ByteArray, width: Int, height: Int): String?
}
