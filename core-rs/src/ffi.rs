//! JNI bridge — Android (Kotlin) → Rust perception core.
//!
//! Device-only glue (see lib.rs cfg). Kotlin side: `com.claustrum.core.NativeCore`
//! with `external fun` declarations matching these symbols. The active path copies
//! single-channel luma bytes in only long enough to return a compact aHash. L1 runs
//! entirely in Kotlin/LiteRT (ADR-0009).

use std::sync::{Mutex, OnceLock};

use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;

use crate::event_bridge::EventEngineRegistry;
use crate::events::{ActantSlot, Observation, Pose};
use crate::gate::frame_signature;

static EVENT_ENGINES: OnceLock<Mutex<EventEngineRegistry>> = OnceLock::new();

fn event_engines() -> &'static Mutex<EventEngineRegistry> {
    EVENT_ENGINES.get_or_init(|| Mutex::new(EventEngineRegistry::default()))
}

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

/// `NativeCore.createEventEngine(sourceId: String): Long` — create one isolated,
/// stateful L2 session. The returned value is an opaque registry handle, not a pointer.
#[no_mangle]
pub extern "system" fn Java_com_claustrum_core_NativeCore_createEventEngine(
    mut env: JNIEnv,
    _class: JClass,
    source_id: JString,
) -> jlong {
    let source_id: String = match env.get_string(&source_id) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    let Ok(mut registry) = event_engines().lock() else {
        return 0;
    };
    registry.create(&source_id).unwrap_or(0) as jlong
}

/// `NativeCore.destroyEventEngine(handle: Long)` — idempotently release a session.
#[no_mangle]
pub extern "system" fn Java_com_claustrum_core_NativeCore_destroyEventEngine(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Ok(handle) = u64::try_from(handle) else {
        return;
    };
    if let Ok(mut registry) = event_engines().lock() {
        registry.destroy(handle);
    }
}

/// Consume one anonymous fast-path observation and return one JSON string per newly
/// crossed event transition. An empty array means "no event"; null means the bridge
/// rejected the handle or payload. Pixels never cross this boundary.
#[allow(clippy::too_many_arguments)]
#[no_mangle]
pub extern "system" fn Java_com_claustrum_core_NativeCore_processEventObservation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    at_ms: jlong,
    actant: jint,
    secondary_actant: jint,
    pose: jint,
    rapid_descent_score: jfloat,
    impact_score: jfloat,
    motion_score: jfloat,
    close_contact_score: jfloat,
    strike_score: jfloat,
    visible_people: jint,
    zone_exit: jboolean,
) -> jobjectArray {
    let Some(observation) = observation_from_jni(
        at_ms,
        actant,
        secondary_actant,
        pose,
        rapid_descent_score,
        impact_score,
        motion_score,
        close_contact_score,
        strike_score,
        visible_people,
        zone_exit,
    ) else {
        return std::ptr::null_mut();
    };
    let Ok(handle) = u64::try_from(handle) else {
        return std::ptr::null_mut();
    };
    let json_events = {
        let Ok(mut registry) = event_engines().lock() else {
            return std::ptr::null_mut();
        };
        match registry.process(handle, observation) {
            Ok(events) => events,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    let Ok(array_length) = jint::try_from(json_events.len()) else {
        return std::ptr::null_mut();
    };
    let array = match env.new_object_array(array_length, "java/lang/String", JObject::null()) {
        Ok(array) => array,
        Err(_) => return std::ptr::null_mut(),
    };
    for (index, json) in json_events.into_iter().enumerate() {
        let Ok(value) = env.new_string(json) else {
            return std::ptr::null_mut();
        };
        if env
            .set_object_array_element(&array, index as jint, value)
            .is_err()
        {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

#[allow(clippy::too_many_arguments)]
fn observation_from_jni(
    at_ms: jlong,
    actant: jint,
    secondary_actant: jint,
    pose: jint,
    rapid_descent_score: jfloat,
    impact_score: jfloat,
    motion_score: jfloat,
    close_contact_score: jfloat,
    strike_score: jfloat,
    visible_people: jint,
    zone_exit: jboolean,
) -> Option<Observation> {
    let at_ms = u64::try_from(at_ms).ok()?;
    let actant = ActantSlot(u16::try_from(actant).ok()?);
    let secondary_actant = if secondary_actant == -1 {
        None
    } else {
        Some(ActantSlot(u16::try_from(secondary_actant).ok()?))
    };
    let pose = match pose {
        0 => Pose::Unknown,
        1 => Pose::Upright,
        2 => Pose::Seated,
        3 => Pose::Crouched,
        4 => Pose::Horizontal,
        5 => Pose::Prone,
        _ => return None,
    };
    Some(Observation {
        at_ms,
        actant,
        secondary_actant,
        pose,
        rapid_descent_score,
        impact_score,
        motion_score,
        close_contact_score,
        strike_score,
        visible_people: u8::try_from(visible_people).ok()?,
        zone_exit: zone_exit != 0,
    })
}
