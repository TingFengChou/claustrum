//! claustrum perception core (Rust).
//!
//! Pure, host-testable logic for the on-device perception pipeline (ADR-0007):
//! L0 gating, and (later) the L2/L3 event engine. The JNI/CameraX/llama.cpp glue
//! lives in the Android layer; everything here runs under `cargo test` on the
//! host with synthetic inputs — no Android hardware required.

pub mod gate;
