//! JNI bridge — Android (Kotlin) → Rust perception core.
//!
//! Device-only glue (see lib.rs cfg). Kotlin side: `com.claustrum.core.NativeCore`
//! with `external fun` declarations matching these symbols. The active path copies
//! single-channel luma bytes in only long enough to return a compact aHash. The
//! legacy `describe` export below is not called by MonitorActivity; L1 now runs in
//! Kotlin/LiteRT (ADR-0009).

use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::gate::frame_signature;
use crate::vlm::{Captioner, PlaceholderCaptioner};

/// `NativeCore.nativeHello(): String` — proves the JNI bridge is live.
#[no_mangle]
pub extern "system" fn Java_com_claustrum_core_NativeCore_nativeHello(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let msg = format!(
        "claustrum-core {} — Rust core online",
        env!("CARGO_PKG_VERSION")
    );
    match env.new_string(msg) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `NativeCore.frameSignature(luma: ByteArray, width: Int, height: Int): Long`
/// — the L0 aHash of a frame. Kotlin holds the previous hash and gates via the
/// Hamming distance (Long.bitCount(prev xor cur)); the pure logic lives in `gate`.
#[no_mangle]
pub extern "system" fn Java_com_claustrum_core_NativeCore_frameSignature(
    env: JNIEnv,
    _class: JClass,
    luma: JByteArray,
    width: jint,
    height: jint,
) -> jlong {
    if width <= 0 || height <= 0 {
        return 0;
    }
    let bytes = match env.convert_byte_array(&luma) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    // frame_signature is bounds-safe (returns Signature(0) if luma.len() < w*h),
    // so a mismatched array cannot cause an out-of-bounds panic.
    frame_signature(&bytes, width as usize, height as usize).0 as jlong
}

/// Legacy ADR-0008 diagnostic export. The active L1 path is Kotlin
/// `LiteRtCaptioner`; keep this temporarily only until the ABI cleanup recorded in
/// HANDOFF removes `core-rs::vlm` and this JNI symbol together.
#[no_mangle]
pub extern "system" fn Java_com_claustrum_core_NativeCore_describe(
    env: JNIEnv,
    _class: JClass,
    luma: JByteArray,
    width: jint,
    height: jint,
) -> jstring {
    let fallback = |env: &JNIEnv| {
        env.new_string("L1 佔位:無效幀")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut())
    };
    if width <= 0 || height <= 0 {
        return fallback(&env);
    }
    let bytes = match env.convert_byte_array(&luma) {
        Ok(b) => b,
        Err(_) => return fallback(&env),
    };
    let mut captioner = PlaceholderCaptioner;
    let desc = captioner.describe(&bytes, width as usize, height as usize);
    match env.new_string(desc) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
