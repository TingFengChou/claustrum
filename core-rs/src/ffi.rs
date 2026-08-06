//! JNI bridge — Android (Kotlin) → Rust perception core.
//!
//! Device-only glue (see lib.rs cfg). Kotlin side: `com.claustrum.core.NativeCore`
//! with `external fun` declarations matching these symbols. Frames are passed as
//! single-channel luma byte arrays; only compact results (a hash / boolean / later
//! a Kineme) cross back — never the frame itself.

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
    let msg = format!("claustrum-core {} — Rust core online", env!("CARGO_PKG_VERSION"));
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

/// `NativeCore.describe(luma: ByteArray, width: Int, height: Int): String`
/// — the L1 scene description for an *admitted* frame (the L0 gate decides when
/// to call this). Currently the diagnostic [`PlaceholderCaptioner`]; the real
/// llama.cpp VLM backend (ADR-0008) slots in behind the same `Captioner` trait
/// without changing this signature. Frames are passed as luma; only the text
/// description crosses back — the frame never leaves native.
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
