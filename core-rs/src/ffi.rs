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
    let bytes = match env.convert_byte_array(&luma) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    frame_signature(&bytes, width.max(0) as usize, height.max(0) as usize).0 as jlong
}
