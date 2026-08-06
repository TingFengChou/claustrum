package com.claustrum.core

/**
 * Kotlin ↔ Rust bridge. Backed by `libclaustrum_core.so` (crate `claustrum-core`,
 * see core-rs/src/ffi.rs). Frames are handed over as single-channel luma byte
 * arrays; only compact results (a hash / boolean / later a Kineme) cross back —
 * the frame itself never leaves the native layer.
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
}
