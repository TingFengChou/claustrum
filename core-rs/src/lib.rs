//! claustrum perception core (Rust).
//!
//! Pure, host-testable logic for the on-device perception pipeline (ADR-0007):
//! L0 signatures/gating contracts and the L2 event engine. CameraX and LiteRT L1
//! live in the Android layer; everything here runs under `cargo test` on the
//! host with synthetic inputs — no Android hardware required.

pub mod event_bridge;
pub mod events;
pub mod gate;

// JNI bridge to the Android layer — device-only glue (compiled for Android only,
// so host `cargo test` stays pure). See docs/design/core-rs.
#[cfg(target_os = "android")]
pub mod ffi;
